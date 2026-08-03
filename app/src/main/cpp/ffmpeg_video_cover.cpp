#include <jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
#include <libavutil/dict.h>
#include <libswscale/swscale.h>
}

namespace {

constexpr const char* kTag = "FFmpegVideoCover";
std::once_flag gNetworkInit;

#define VLOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define VLOGW(...) __android_log_print(ANDROID_LOG_WARN, kTag, __VA_ARGS__)
#define VLOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

struct VideoCoverSession {
    int sourceFd = -1;
    std::string sourceUrl;
    ANativeWindow* window = nullptr;
    std::atomic<bool> stop{false};
    std::atomic<bool> active{true};
    std::thread worker;

    ~VideoCoverSession() {
        stop.store(true, std::memory_order_release);
        if (worker.joinable()) worker.join();
        if (window) ANativeWindow_release(window);
        if (sourceFd >= 0) close(sourceFd);
    }
};

bool waitUntilActive(VideoCoverSession& session) {
    while (!session.stop.load(std::memory_order_acquire) &&
           !session.active.load(std::memory_order_acquire)) {
        std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    return !session.stop.load(std::memory_order_acquire);
}

bool waitForPresentation(VideoCoverSession& session, int64_t targetUs) {
    while (!session.stop.load(std::memory_order_acquire)) {
        if (!session.active.load(std::memory_order_acquire)) return false;
        const auto nowUs = std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
        const int64_t remainingUs = targetUs - nowUs;
        if (remainingUs <= 0) return true;
        std::this_thread::sleep_for(
            std::chrono::microseconds(std::min<int64_t>(remainingUs, 5000)));
    }
    return false;
}

bool renderFrame(VideoCoverSession& session, AVFrame* frame, SwsContext*& sws) {
    const int targetWidth = ANativeWindow_getWidth(session.window);
    const int targetHeight = ANativeWindow_getHeight(session.window);
    if (targetWidth <= 0 || targetHeight <= 0 || frame->width <= 0 || frame->height <= 0) {
        return false;
    }

    const double scale = std::max(
        static_cast<double>(targetWidth) / frame->width,
        static_cast<double>(targetHeight) / frame->height);
    const int scaledWidth = std::max(targetWidth, static_cast<int>(std::ceil(frame->width * scale)));
    const int scaledHeight = std::max(targetHeight, static_cast<int>(std::ceil(frame->height * scale)));
    const int scaledStride = scaledWidth * 4;
    std::vector<uint8_t> rgba(static_cast<size_t>(scaledStride) * scaledHeight);
    uint8_t* dstData[4] = {rgba.data(), nullptr, nullptr, nullptr};
    int dstLinesize[4] = {scaledStride, 0, 0, 0};

    sws = sws_getCachedContext(
        sws,
        frame->width,
        frame->height,
        static_cast<AVPixelFormat>(frame->format),
        scaledWidth,
        scaledHeight,
        AV_PIX_FMT_RGBA,
        SWS_BILINEAR,
        nullptr,
        nullptr,
        nullptr);
    if (!sws) return false;
    if (sws_scale(sws, frame->data, frame->linesize, 0, frame->height, dstData, dstLinesize) <= 0) {
        return false;
    }

    if (ANativeWindow_setBuffersGeometry(
            session.window, targetWidth, targetHeight, WINDOW_FORMAT_RGBA_8888) != 0) {
        return false;
    }
    ANativeWindow_Buffer out{};
    if (ANativeWindow_lock(session.window, &out, nullptr) != 0) return false;

    const int cropX = std::max(0, (scaledWidth - targetWidth) / 2);
    const int cropY = std::max(0, (scaledHeight - targetHeight) / 2);
    const int copyWidth = std::min(targetWidth, out.width);
    const int copyHeight = std::min(targetHeight, out.height);
    auto* outBytes = static_cast<uint8_t*>(out.bits);
    for (int y = 0; y < copyHeight; ++y) {
        const uint8_t* src = rgba.data() + static_cast<size_t>(cropY + y) * scaledStride + cropX * 4;
        uint8_t* dst = outBytes + static_cast<size_t>(y) * out.stride * 4;
        std::copy_n(src, copyWidth * 4, dst);
    }
    ANativeWindow_unlockAndPost(session.window);
    return true;
}

bool playOnce(VideoCoverSession& session) {
    std::string sourcePath;
    if (!session.sourceUrl.empty()) {
        sourcePath = session.sourceUrl;
    } else {
        if (lseek(session.sourceFd, 0, SEEK_SET) < 0) {
            VLOGW("source fd is not seekable; loop reopen may fail");
        }
        char fdPath[64];
        std::snprintf(fdPath, sizeof(fdPath), "/proc/self/fd/%d", session.sourceFd);
        sourcePath = fdPath;
    }

    AVFormatContext* format = nullptr;
    AVDictionary* options = nullptr;
    if (!session.sourceUrl.empty()) {
        std::call_once(gNetworkInit, [] {
            const int result = avformat_network_init();
            VLOGI("network_init result=%d", result);
        });
        av_dict_set(&options, "user_agent", "Mozilla/5.0 (Linux; Android) RawS-Music", 0);
        av_dict_set(&options, "referer", "https://music.apple.com/", 0);
    }
    const int openResult = avformat_open_input(
        &format,
        sourcePath.c_str(),
        nullptr,
        &options);
    av_dict_free(&options);
    if (openResult < 0) {
        if (!session.sourceUrl.empty()) {
            VLOGE("avformat_open_input failed url=%s", session.sourceUrl.c_str());
        } else {
            VLOGE("avformat_open_input failed fd=%d", session.sourceFd);
        }
        return false;
    }
    std::unique_ptr<AVFormatContext, void (*)(AVFormatContext*)> formatGuard(
        format, [](AVFormatContext* value) { avformat_close_input(&value); });
    if (avformat_find_stream_info(format, nullptr) < 0) {
        VLOGE("avformat_find_stream_info failed");
        return false;
    }

    const int streamIndex = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (streamIndex < 0) {
        VLOGE("video stream not found");
        return false;
    }
    AVStream* stream = format->streams[streamIndex];
    const AVCodec* codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) {
        VLOGE("video decoder missing codecId=%d", stream->codecpar->codec_id);
        return false;
    }
    AVCodecContext* codecContext = avcodec_alloc_context3(codec);
    if (!codecContext) return false;
    std::unique_ptr<AVCodecContext, void (*)(AVCodecContext*)> codecGuard(
        codecContext, [](AVCodecContext* value) { avcodec_free_context(&value); });
    if (avcodec_parameters_to_context(codecContext, stream->codecpar) < 0 ||
        avcodec_open2(codecContext, codec, nullptr) < 0) {
        VLOGE("video decoder open failed codec=%s", codec->name);
        return false;
    }

    AVPacket* packet = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    if (!packet || !frame) {
        av_packet_free(&packet);
        av_frame_free(&frame);
        return false;
    }
    std::unique_ptr<AVPacket, void (*)(AVPacket*)> packetGuard(
        packet, [](AVPacket* value) { av_packet_free(&value); });
    std::unique_ptr<AVFrame, void (*)(AVFrame*)> frameGuard(
        frame, [](AVFrame* value) { av_frame_free(&value); });

    SwsContext* sws = nullptr;
    int64_t firstPtsUs = AV_NOPTS_VALUE;
    int64_t clockOriginUs = 0;
    int64_t fallbackFrame = 0;
    AVRational frameRate = av_guess_frame_rate(format, stream, nullptr);
    double fallbackFrameUs = frameRate.num > 0 && frameRate.den > 0
        ? 1000000.0 * frameRate.den / frameRate.num
        : 33333.0;
    VLOGI("start codec=%s size=%dx%d fps=%d/%d", codec->name, codecContext->width,
          codecContext->height, frameRate.num, frameRate.den);

    auto receiveFrames = [&]() {
        while (!session.stop.load(std::memory_order_acquire)) {
            const int result = avcodec_receive_frame(codecContext, frame);
            if (result == AVERROR(EAGAIN) || result == AVERROR_EOF) return;
            if (result < 0) return;
            if (!waitUntilActive(session)) return;

            int64_t framePtsUs;
            if (frame->best_effort_timestamp != AV_NOPTS_VALUE) {
                framePtsUs = av_rescale_q(
                    frame->best_effort_timestamp, stream->time_base, AVRational{1, 1000000});
            } else {
                framePtsUs = static_cast<int64_t>(fallbackFrame * fallbackFrameUs);
            }
            ++fallbackFrame;
            const int64_t nowUs = std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
            if (firstPtsUs == AV_NOPTS_VALUE || clockOriginUs == 0) {
                firstPtsUs = framePtsUs;
                clockOriginUs = nowUs;
            }
            const int64_t targetUs = clockOriginUs + std::max<int64_t>(0, framePtsUs - firstPtsUs);
            if (!waitForPresentation(session, targetUs)) {
                clockOriginUs = std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now().time_since_epoch()).count() -
                    std::max<int64_t>(0, framePtsUs - firstPtsUs);
                if (!waitUntilActive(session)) return;
            }
            renderFrame(session, frame, sws);
            av_frame_unref(frame);
        }
    };

    while (!session.stop.load(std::memory_order_acquire) && av_read_frame(format, packet) >= 0) {
        if (packet->stream_index == streamIndex && avcodec_send_packet(codecContext, packet) >= 0) {
            receiveFrames();
        }
        av_packet_unref(packet);
    }
    if (!session.stop.load(std::memory_order_acquire)) {
        avcodec_send_packet(codecContext, nullptr);
        receiveFrames();
    }
    sws_freeContext(sws);
    return true;
}

void runVideoCover(VideoCoverSession* session) {
    while (!session->stop.load(std::memory_order_acquire)) {
        if (!waitUntilActive(*session)) break;
        if (!playOnce(*session)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(500));
        }
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeCreateVideoCoverSession(
    JNIEnv* env, jobject, jint fileDescriptor, jobject surface) {
    if (fileDescriptor < 0 || !surface) return 0;
    const int ownedFd = dup(fileDescriptor);
    if (ownedFd < 0) return 0;
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        close(ownedFd);
        return 0;
    }
    auto session = std::make_unique<VideoCoverSession>();
    session->sourceFd = ownedFd;
    session->window = window;
    auto* raw = session.release();
    raw->worker = std::thread(runVideoCover, raw);
    return reinterpret_cast<jlong>(raw);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeCreateVideoCoverUrlSession(
    JNIEnv* env, jobject, jstring source, jobject surface) {
    if (!source || !surface) return 0;
    const char* chars = env->GetStringUTFChars(source, nullptr);
    if (!chars || chars[0] == '\0') {
        if (chars) env->ReleaseStringUTFChars(source, chars);
        return 0;
    }
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        env->ReleaseStringUTFChars(source, chars);
        return 0;
    }
    auto session = std::make_unique<VideoCoverSession>();
    session->sourceUrl = chars;
    session->window = window;
    env->ReleaseStringUTFChars(source, chars);
    VLOGI("url_session_created source=%s", session->sourceUrl.c_str());
    auto* raw = session.release();
    raw->worker = std::thread(runVideoCover, raw);
    return reinterpret_cast<jlong>(raw);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeSetVideoCoverSessionActive(
    JNIEnv*, jobject, jlong handle, jboolean active) {
    auto* session = reinterpret_cast<VideoCoverSession*>(handle);
    if (session) session->active.store(active == JNI_TRUE, std::memory_order_release);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeReleaseVideoCoverSession(
    JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<VideoCoverSession*>(handle);
}
