#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <cstdarg>
#include <cstring>
#include <string>
#include <vector>

extern "C" {
#include <libavformat/avformat.h>
#include <libavformat/avio.h>
#include <libavutil/error.h>
#include <libavutil/log.h>
}

namespace {
constexpr const char *kTag = "FFmpegRuntimeDiag";
std::atomic<bool> g_capture_enabled{false};

std::string trimLine(std::string value) {
    while (!value.empty() && (value.back() == '\n' || value.back() == '\r')) {
        value.pop_back();
    }
    if (value.size() > 1200) {
        value.resize(1200);
        value += "...";
    }
    return value;
}

void onlineAvLogCallback(void *ptr, int level, const char *fmt, va_list vl) {
    if (!g_capture_enabled.load(std::memory_order_relaxed)) {
        return;
    }
    if (level > AV_LOG_INFO) {
        return;
    }

    char line[1536] = {0};
    int printPrefix = 1;
    va_list copy;
    va_copy(copy, vl);
    av_log_format_line2(ptr, level, fmt, copy, line, sizeof(line), &printPrefix);
    va_end(copy);

    const std::string message = trimLine(line);
    if (message.empty()) {
        return;
    }
    const int priority = level <= AV_LOG_WARNING ? ANDROID_LOG_ERROR : ANDROID_LOG_INFO;
    __android_log_print(
        priority,
        kTag,
        "ONLINE_PIPE FFMPEG_NATIVE_LOG level=%d msg=%s",
        level,
        message.c_str());
}

std::string inputProtocols() {
    std::vector<std::string> protocols;
    void *opaque = nullptr;
    const char *name = nullptr;
    while ((name = avio_enum_protocols(&opaque, 0)) != nullptr) {
        protocols.emplace_back(name);
    }
    std::string joined;
    for (const auto &protocol : protocols) {
        if (!joined.empty()) joined += ',';
        joined += protocol;
    }
    return joined;
}

std::string jstringToUtf8(JNIEnv *env, jstring value) {
    if (!value) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_OnlineFfmpegRuntimeDiagnostics_nativeBeginCapture(
    JNIEnv *env,
    jobject,
    jstring url) {
    const std::string value = jstringToUtf8(env, url);
    const int networkInit = avformat_network_init();
    const char *recognized = value.empty() ? nullptr : avio_find_protocol_name(value.c_str());
    const std::string protocols = inputProtocols();

    av_log_set_level(AV_LOG_INFO);
    g_capture_enabled.store(true, std::memory_order_release);
    av_log_set_callback(onlineAvLogCallback);

    std::string result = "networkInit=" + std::to_string(networkInit) +
        " recognized=" + (recognized ? std::string(recognized) : std::string("-")) +
        " inputProtocols=" + (protocols.empty() ? std::string("-") : protocols);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_OnlineFfmpegRuntimeDiagnostics_nativeEndCapture(
    JNIEnv *,
    jobject) {
    g_capture_enabled.store(false, std::memory_order_release);
    av_log_set_callback(av_log_default_callback);
}
