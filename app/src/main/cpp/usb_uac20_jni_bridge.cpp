#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <memory>
#include <string>

#include "usb_uac20_session.h"

#define TAG "RawUac20JniBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace rawsmusic::usb {

namespace {

constexpr int FLAG_RESET_ALT = 1 << 0;
constexpr int FLAG_PREFER_EXPLICIT_FEEDBACK = 1 << 1;
constexpr int FLAG_FORBID_LEARNED_NO_FEEDBACK = 1 << 2;
constexpr int FLAG_MINIMAL_MIXER_CONTROL = 1 << 3;
constexpr int FLAG_PREFER_24_IN_32 = 1 << 4;
constexpr int FLAG_FULL_REOPEN_ON_NOT_OUTPUTTING = 1 << 5;
constexpr int FLAG_DEBUG_REAL_OUT_SUBMITTER = 1 << 6;
constexpr int FLAG_DEBUG_REAL_OUT_FEEDER = 1 << 7;
constexpr int FLAG_DEBUG_REAL_OUT_AUTO_RESUBMIT = 1 << 8;
constexpr int FLAG_DEBUG_RUNTIME_GUARD = 1 << 9;
constexpr int FLAG_DEBUG_RECOVERY_EXECUTOR = 1 << 10;
constexpr int FLAG_DEBUG_FORMAT_FALLBACK_EXECUTOR = 1 << 11;
constexpr int FLAG_RAW_STREAM_REAL_OUT_TAKEOVER = 1 << 12;

Uac20Params paramsFromJni(
        jint sourceSampleRate,
        jint sourceBits,
        jint sourceChannels,
        jint requestedSampleRate,
        jint requestedBits,
        jint requestedSubslotBytes,
        jint flags) {
    Uac20Params params;
    params.sourceSampleRate = sourceSampleRate;
    params.sourceBits = sourceBits;
    params.sourceChannels = sourceChannels;
    params.requestedSampleRate = requestedSampleRate;
    params.requestedBits = requestedBits;
    params.requestedSubslotBytes = requestedSubslotBytes;
    params.resetAltBeforeStart = (flags & FLAG_RESET_ALT) != 0;
    params.preferExplicitFeedback = (flags & FLAG_PREFER_EXPLICIT_FEEDBACK) != 0;
    params.forbidLearnedNoFeedback = (flags & FLAG_FORBID_LEARNED_NO_FEEDBACK) != 0;
    params.minimalMixerControl = (flags & FLAG_MINIMAL_MIXER_CONTROL) != 0;
    params.prefer24In32 = (flags & FLAG_PREFER_24_IN_32) != 0;
    params.fullReopenOnNotOutputting = (flags & FLAG_FULL_REOPEN_ON_NOT_OUTPUTTING) != 0;
    params.enableDebugRealOutSubmitter = (flags & FLAG_DEBUG_REAL_OUT_SUBMITTER) != 0;
    params.debugRealOutFeedFromWriteRing = (flags & FLAG_DEBUG_REAL_OUT_FEEDER) != 0;
    params.debugRealOutAutoResubmit = (flags & FLAG_DEBUG_REAL_OUT_AUTO_RESUBMIT) != 0;
    params.enableDebugPlaybackRuntimeGuard = (flags & FLAG_DEBUG_RUNTIME_GUARD) != 0;
    params.enableDebugRecoveryExecutor = (flags & FLAG_DEBUG_RECOVERY_EXECUTOR) != 0;
    params.enableDebugFormatFallbackExecutor = (flags & FLAG_DEBUG_FORMAT_FALLBACK_EXECUTOR) != 0;
    params.enableRawStreamRealOutTakeover = (flags & FLAG_RAW_STREAM_REAL_OUT_TAKEOVER) != 0;
    return params;
}

Uac20Session* fromHandle(jlong handle) {
    return reinterpret_cast<Uac20Session*>(static_cast<intptr_t>(handle));
}

jlong toHandle(Uac20Session* session) {
    return static_cast<jlong>(reinterpret_cast<intptr_t>(session));
}

} // namespace

} // namespace rawsmusic::usb

#include <mutex>
#include <string>

static std::mutex g_uac20LastCreateErrorMutex;
static std::string g_uac20LastCreateError;

static void setUac20LastCreateError(const std::string& error) {
    std::lock_guard<std::mutex> lock(g_uac20LastCreateErrorMutex);
    g_uac20LastCreateError = error;
}

static std::string getUac20LastCreateErrorString() {
    std::lock_guard<std::mutex> lock(g_uac20LastCreateErrorMutex);
    return g_uac20LastCreateError;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeCreateUac20Session(
        JNIEnv*, jobject,
        jint fd,
        jint sourceSampleRate,
        jint sourceBits,
        jint sourceChannels,
        jint requestedSampleRate,
        jint requestedBits,
        jint requestedSubslotBytes,
        jint flags) {
    using namespace rawsmusic::usb;

    setUac20LastCreateError("begin");

    if (fd < 0) {
        setUac20LastCreateError("invalid-fd");
        return 0;
    }

    auto session = std::make_unique<Uac20Session>();
    if (!session->openFromFd(fd)) {
        const std::string runtime = session->runtimeJson();
        setUac20LastCreateError("openFromFd-failed fd=" + std::to_string(fd) + " runtime=" + runtime);
        LOGE("nativeCreateUac20Session: openFromFd failed fd=%d state=%s", fd, runtime.c_str());
        return 0;
    }

    const Uac20Params params = paramsFromJni(
            sourceSampleRate,
            sourceBits,
            sourceChannels,
            requestedSampleRate,
            requestedBits,
            requestedSubslotBytes,
            flags);
    if (!session->prepare(params)) {
        setUac20LastCreateError("prepare-failed runtime=" + session->runtimeJson());
        LOGE("nativeCreateUac20Session: prepare failed state=%s", session->runtimeJson().c_str());
        return 0;
    }

    Uac20Session* raw = session.release();
    setUac20LastCreateError("ok");
    LOGI("nativeCreateUac20Session ok handle=%p state=%s", raw, raw->runtimeJson().c_str());
    return toHandle(raw);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeUac20Start(
        JNIEnv*, jobject, jlong handle) {
    using namespace rawsmusic::usb;
    auto* session = fromHandle(handle);
    if (session == nullptr) return JNI_FALSE;
    return session->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeUac20Write(
        JNIEnv* env, jobject, jlong handle, jbyteArray data, jint offset, jint length) {
    using namespace rawsmusic::usb;
    auto* session = fromHandle(handle);
    if (session == nullptr) return -1001;
    if (data == nullptr || offset < 0 || length < 0) return -1005;

    const jsize size = env->GetArrayLength(data);
    if (offset > size || length > size - offset) return -1005;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return -1005;
    const int written = session->write(
            reinterpret_cast<const uint8_t*>(bytes + offset),
            static_cast<int>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return static_cast<jint>(written);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeUac20Stop(
        JNIEnv*, jobject, jlong handle) {
    using namespace rawsmusic::usb;
    auto* session = fromHandle(handle);
    if (session != nullptr) {
        session->stop("jni_stop");
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeUac20Close(
        JNIEnv*, jobject, jlong handle) {
    using namespace rawsmusic::usb;
    auto* session = fromHandle(handle);
    if (session != nullptr) {
        LOGI("nativeUac20Close handle=%p state=%s", session, session->runtimeJson().c_str());
        delete session;
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeUac20RuntimeJson(
        JNIEnv* env, jobject, jlong handle) {
    using namespace rawsmusic::usb;
    auto* session = fromHandle(handle);
    if (session == nullptr) {
        return env->NewStringUTF("{\"opened\":false,\"lastError\":\"no-handle\"}");
    }
    const std::string json = session->runtimeJson();
    return env->NewStringUTF(json.c_str());
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeUac20LastCreateError(
        JNIEnv* env,
        jobject /* thiz */) {
    const std::string error = getUac20LastCreateErrorString();
    return env->NewStringUTF(error.c_str());
}
