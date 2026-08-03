#include <jni.h>
#include <android/log.h>
#include <algorithm>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#define LOG_TAG "AiStemEncoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

int encodeStem(
    const char* inputPath,
    const char* outputPath,
    bool lossless
) {
    AVFormatContext* input = nullptr;
    AVFormatContext* output = nullptr;
    AVCodecContext* decoder = nullptr;
    AVCodecContext* encoder = nullptr;
    SwrContext* resampler = nullptr;
    AVAudioFifo* fifo = nullptr;
    AVPacket* inputPacket = nullptr;
    AVPacket* outputPacket = nullptr;
    AVFrame* decodedFrame = nullptr;
    AVStream* outputStream = nullptr;
    int audioStreamIndex = -1;
    int64_t nextPts = 0;
    bool outputOpened = false;
    bool headerWritten = false;

    auto cleanup = [&]() {
        if (headerWritten && output) av_write_trailer(output);
        if (outputOpened && output && !(output->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&output->pb);
        }
        av_packet_free(&inputPacket);
        av_packet_free(&outputPacket);
        av_frame_free(&decodedFrame);
        av_audio_fifo_free(fifo);
        swr_free(&resampler);
        avcodec_free_context(&encoder);
        avcodec_free_context(&decoder);
        if (output) avformat_free_context(output);
        if (input) avformat_close_input(&input);
    };
    auto fail = [&](const char* message, int code) {
        char errorText[AV_ERROR_MAX_STRING_SIZE] = {};
        if (code < 0) {
            av_strerror(code, errorText, sizeof(errorText));
        } else {
            snprintf(errorText, sizeof(errorText), "internal encoder error");
        }
        LOGE(
            "%s code=%d reason=%s input=%s output=%s",
            message,
            code,
            errorText,
            inputPath,
            outputPath
        );
        cleanup();
        return code < 0 ? code : -1;
    };

    int result = avformat_open_input(&input, inputPath, nullptr, nullptr);
    if (result < 0) return fail("open input failed", result);
    result = avformat_find_stream_info(input, nullptr);
    if (result < 0) return fail("stream info failed", result);
    for (unsigned int index = 0; index < input->nb_streams; ++index) {
        if (input->streams[index]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audioStreamIndex = static_cast<int>(index);
            break;
        }
    }
    if (audioStreamIndex < 0) return fail("audio stream missing", -1);

    AVCodecParameters* inputParameters = input->streams[audioStreamIndex]->codecpar;
    const AVCodec* decoderCodec = avcodec_find_decoder(inputParameters->codec_id);
    if (!decoderCodec) return fail("decoder missing", -1);
    decoder = avcodec_alloc_context3(decoderCodec);
    if (!decoder) return fail("decoder allocation failed", AVERROR(ENOMEM));
    result = avcodec_parameters_to_context(decoder, inputParameters);
    if (result < 0) return fail("decoder parameters failed", result);
    result = avcodec_open2(decoder, decoderCodec, nullptr);
    if (result < 0) return fail("decoder open failed", result);

    const AVCodecID codecId = lossless ? AV_CODEC_ID_FLAC : AV_CODEC_ID_AAC;
    const AVCodec* encoderCodec = avcodec_find_encoder(codecId);
    if (!encoderCodec) return fail("encoder missing", -1);
    result = avformat_alloc_output_context2(
        &output,
        nullptr,
        lossless ? "flac" : "ipod",
        outputPath
    );
    if (result < 0 || !output) return fail("output context failed", result);

    encoder = avcodec_alloc_context3(encoderCodec);
    if (!encoder) return fail("encoder allocation failed", AVERROR(ENOMEM));
    encoder->sample_rate = 44100;
    encoder->channels = 2;
    encoder->channel_layout = AV_CH_LAYOUT_STEREO;
    encoder->sample_fmt = encoderCodec->sample_fmts
        ? encoderCodec->sample_fmts[0]
        : AV_SAMPLE_FMT_S16;
    encoder->time_base = AVRational{1, encoder->sample_rate};
    encoder->bits_per_raw_sample = 16;
    if (lossless) {
        encoder->compression_level = 8;
        av_opt_set_int(encoder->priv_data, "compression_level", 8, 0);
    } else {
        encoder->bit_rate = 256000;
        av_opt_set(encoder->priv_data, "aac_coder", "twoloop", 0);
    }
    if (output->oformat->flags & AVFMT_GLOBALHEADER) {
        encoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }
    result = avcodec_open2(encoder, encoderCodec, nullptr);
    if (result < 0) return fail("encoder open failed", result);

    outputStream = avformat_new_stream(output, nullptr);
    if (!outputStream) return fail("output stream allocation failed", AVERROR(ENOMEM));
    outputStream->time_base = encoder->time_base;
    result = avcodec_parameters_from_context(outputStream->codecpar, encoder);
    if (result < 0) return fail("output parameters failed", result);

    if (!(output->oformat->flags & AVFMT_NOFILE)) {
        result = avio_open(&output->pb, outputPath, AVIO_FLAG_WRITE);
        if (result < 0) return fail("output open failed", result);
        outputOpened = true;
    }
    result = avformat_write_header(output, nullptr);
    if (result < 0) return fail("output header failed", result);
    headerWritten = true;

    int64_t inputLayout = decoder->channel_layout;
    if (!inputLayout) inputLayout = av_get_default_channel_layout(decoder->channels);
    if (!inputLayout) inputLayout = AV_CH_LAYOUT_STEREO;
    resampler = swr_alloc_set_opts(
        nullptr,
        encoder->channel_layout,
        encoder->sample_fmt,
        encoder->sample_rate,
        inputLayout,
        decoder->sample_fmt,
        decoder->sample_rate,
        0,
        nullptr
    );
    if (!resampler || swr_init(resampler) < 0) {
        return fail("resampler initialization failed", -1);
    }
    fifo = av_audio_fifo_alloc(encoder->sample_fmt, encoder->channels, 1);
    if (!fifo) return fail("audio fifo allocation failed", AVERROR(ENOMEM));

    inputPacket = av_packet_alloc();
    outputPacket = av_packet_alloc();
    decodedFrame = av_frame_alloc();
    if (!inputPacket || !outputPacket || !decodedFrame) {
        return fail("packet/frame allocation failed", AVERROR(ENOMEM));
    }

    auto writeEncodedPackets = [&]() -> int {
        while (true) {
            int receive = avcodec_receive_packet(encoder, outputPacket);
            if (receive == AVERROR(EAGAIN) || receive == AVERROR_EOF) return 0;
            if (receive < 0) return receive;
            av_packet_rescale_ts(outputPacket, encoder->time_base, outputStream->time_base);
            outputPacket->stream_index = outputStream->index;
            receive = av_interleaved_write_frame(output, outputPacket);
            av_packet_unref(outputPacket);
            if (receive < 0) return receive;
        }
    };
    auto encodeAvailable = [&](bool flushAll) -> int {
        const int fixedFrameSize = encoder->frame_size;
        while (av_audio_fifo_size(fifo) >= (fixedFrameSize > 0 ? fixedFrameSize : 1) ||
               (flushAll && av_audio_fifo_size(fifo) > 0)) {
            int samples = fixedFrameSize > 0
                ? fixedFrameSize
                : av_audio_fifo_size(fifo);
            int available = av_audio_fifo_size(fifo);
            if (flushAll) samples = fixedFrameSize > 0 ? fixedFrameSize : available;
            AVFrame* frame = av_frame_alloc();
            if (!frame) return AVERROR(ENOMEM);
            frame->nb_samples = samples;
            frame->format = encoder->sample_fmt;
            frame->sample_rate = encoder->sample_rate;
            frame->channel_layout = encoder->channel_layout;
            int localResult = av_frame_get_buffer(frame, 0);
            if (localResult >= 0) {
                int readSamples = av_audio_fifo_read(
                    fifo,
                    reinterpret_cast<void**>(frame->data),
                    std::min(samples, available)
                );
                if (readSamples < samples) {
                    av_samples_set_silence(
                        frame->data,
                        std::max(0, readSamples),
                        samples - std::max(0, readSamples),
                        encoder->channels,
                        encoder->sample_fmt
                    );
                }
                frame->pts = nextPts;
                nextPts += samples;
                localResult = avcodec_send_frame(encoder, frame);
            }
            av_frame_free(&frame);
            if (localResult < 0) return localResult;
            localResult = writeEncodedPackets();
            if (localResult < 0) return localResult;
        }
        return 0;
    };
    auto consumeDecodedFrame = [&]() -> int {
        const int capacity = av_rescale_rnd(
            swr_get_delay(resampler, decoder->sample_rate) + decodedFrame->nb_samples,
            encoder->sample_rate,
            decoder->sample_rate,
            AV_ROUND_UP
        );
        uint8_t** converted = nullptr;
        int lineSize = 0;
        int localResult = av_samples_alloc_array_and_samples(
            &converted,
            &lineSize,
            encoder->channels,
            capacity,
            encoder->sample_fmt,
            0
        );
        if (localResult < 0) return localResult;
        int convertedSamples = swr_convert(
            resampler,
            converted,
            capacity,
            const_cast<const uint8_t**>(decodedFrame->extended_data),
            decodedFrame->nb_samples
        );
        if (convertedSamples > 0) {
            localResult = av_audio_fifo_realloc(
                fifo,
                av_audio_fifo_size(fifo) + convertedSamples
            );
            if (localResult >= 0) {
                localResult = av_audio_fifo_write(
                    fifo,
                    reinterpret_cast<void**>(converted),
                    convertedSamples
                );
                if (localResult >= 0) localResult = encodeAvailable(false);
            }
        } else if (convertedSamples < 0) {
            localResult = convertedSamples;
        }
        if (converted) {
            av_freep(&converted[0]);
            av_freep(&converted);
        }
        return localResult;
    };

    while (av_read_frame(input, inputPacket) >= 0) {
        if (inputPacket->stream_index != audioStreamIndex) {
            av_packet_unref(inputPacket);
            continue;
        }
        result = avcodec_send_packet(decoder, inputPacket);
        av_packet_unref(inputPacket);
        if (result < 0) return fail("decoder send failed", result);
        while ((result = avcodec_receive_frame(decoder, decodedFrame)) >= 0) {
            result = consumeDecodedFrame();
            av_frame_unref(decodedFrame);
            if (result < 0) return fail("audio conversion failed", result);
        }
        if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
            return fail("decoder receive failed", result);
        }
    }

    result = avcodec_send_packet(decoder, nullptr);
    if (result >= 0) {
        while ((result = avcodec_receive_frame(decoder, decodedFrame)) >= 0) {
            result = consumeDecodedFrame();
            av_frame_unref(decodedFrame);
            if (result < 0) return fail("decoder flush conversion failed", result);
        }
    }
    result = encodeAvailable(true);
    if (result < 0) return fail("encoder fifo flush failed", result);
    result = avcodec_send_frame(encoder, nullptr);
    if (result < 0) return fail("encoder flush failed", result);
    result = writeEncodedPackets();
    if (result < 0) return fail("packet flush failed", result);

    headerWritten = false;
    result = av_write_trailer(output);
    if (result < 0) return fail("trailer write failed", result);
    LOGI(
        "encoded format=%s sampleRate=44100 bits=16 compression=%d output=%s",
        lossless ? "flac" : "aac",
        lossless ? 8 : 256,
        outputPath
    );
    cleanup();
    return 0;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_separation_AiStemAudioEncoder_nativeEncode(
    JNIEnv* env,
    jobject,
    jstring inputPath,
    jstring outputPath,
    jboolean lossless
) {
    const char* input = env->GetStringUTFChars(inputPath, nullptr);
    const char* output = env->GetStringUTFChars(outputPath, nullptr);
    const int result = encodeStem(input, output, lossless == JNI_TRUE);
    env->ReleaseStringUTFChars(inputPath, input);
    env->ReleaseStringUTFChars(outputPath, output);
    return result;
}
