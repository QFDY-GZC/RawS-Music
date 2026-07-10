#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <pthread.h>
#include <sched.h>
#include <unistd.h>
#include <sys/resource.h>
#include <cerrno>

#define LOG_TAG "AndroidAudioIdentity"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct IdentityState {
    std::mutex mutex;
    std::condition_variable cv;
    JavaVM* vm = nullptr;
    jobject callback = nullptr;
    // Native ABI v6u: 固定方法名，不再用容易漂移的短名
    jmethodID createTrack = nullptr;
    jmethodID startTrack = nullptr;
    jmethodID writeTrack = nullptr;
    // AndroidAudioIdentity 只负责 Android 系统可见的 AudioTrack 身份。
    // USB event loop 由 UsbAudioEngine 自己的 USB 线程驱动；不要在 identity 线程里重复 pump，
    // 否则会和 native USB owner 争用，产生大量 BUSY/invalid-state 式返回值。
    jmethodID pumpUsbEvents = nullptr; // disabled by design
    jmethodID destroyTrack = nullptr;
    jmethodID log = nullptr;
    std::thread worker;
    std::atomic<bool> running{false};
    std::atomic<bool> stopRequested{false};
    std::atomic<int64_t> writes{0};
    std::atomic<int64_t> writeBytes{0};
    std::atomic<int64_t> writeErrors{0};
    std::atomic<int64_t> pumpErrors{0};
    std::atomic<int64_t> repairs{0};
    int sampleRate = 48000;
    int channels = 2;
    int bits = 16;
    int framesPerTick = 384;
    uint64_t generation = 0;
    std::string reason;
};

static IdentityState gState;

static bool clearException(JNIEnv* env, const char* where) {
    if (!env || !env->ExceptionCheck()) return false;
    LOGE("JNI exception at %s", where ? where : "?");
    env->ExceptionDescribe();
    env->ExceptionClear();
    return true;
}

static std::string jstringToString(JNIEnv* env, jstring text) {
    if (!env || !text) return {};
    const char* raw = env->GetStringUTFChars(text, nullptr);
    if (!raw) {
        clearException(env, "GetStringUTFChars");
        return {};
    }
    std::string result(raw);
    env->ReleaseStringUTFChars(text, raw);
    return result;
}

static void setIdentityThreadPriority() {
    // 尽力提高身份轨线程优先级；普通 app 可能没有 CAP_SYS_NICE，失败不影响播放。
    errno = 0;
    setpriority(PRIO_PROCESS, 0, -16);
    sched_param sp{};
    sp.sched_priority = 2;
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp);
}

static jmethodID getRequiredMethod(JNIEnv* env, jclass cls, const char* name, const char* sig) {
    if (!env || !cls || !name || !sig) return nullptr;
    jmethodID id = env->GetMethodID(cls, name, sig);
    const bool hadException = clearException(env, name);
    if (!id || hadException) {
        LOGE("required callback missing: %s%s", name, sig);
        return nullptr;
    }
    return id;
}

static jmethodID getOptionalMethod(JNIEnv* env, jclass cls, const char* name, const char* sig) {
    if (!env || !cls || !name || !sig) return nullptr;
    jmethodID id = env->GetMethodID(cls, name, sig);
    const bool hadException = clearException(env, name);
    if (!id || hadException) {
        LOGW("optional callback missing: %s%s", name, sig);
        return nullptr;
    }
    return id;
}

static void callbackLog(JNIEnv* env, jobject callback, jmethodID logMethod, int level, const std::string& message) {
    if (!env || !callback || !logMethod) {
        if (level >= ANDROID_LOG_ERROR) LOGE("%s", message.c_str());
        else if (level >= ANDROID_LOG_WARN) LOGW("%s", message.c_str());
        else LOGI("%s", message.c_str());
        return;
    }
    jstring text = env->NewStringUTF(message.c_str());
    if (!text) {
        clearException(env, "NewStringUTF(log)");
        return;
    }
    env->CallVoidMethod(callback, logMethod, static_cast<jint>(level), text);
    env->DeleteLocalRef(text);
    clearException(env, "callback.log");
}

static bool resolveMethods(JNIEnv* env, jobject callback) {
    clearException(env, "resolve.entry");
    jclass cls = env->GetObjectClass(callback);
    if (!cls || clearException(env, "GetObjectClass(callback)")) {
        LOGE("identity callback class resolution failed");
        return false;
    }

    gState.createTrack = getRequiredMethod(env, cls, "createTrack", "(IIII)Z");
    gState.startTrack = getRequiredMethod(env, cls, "startTrack", "()V");
    gState.writeTrack = getRequiredMethod(env, cls, "writeTrack", "(I)I");
    gState.destroyTrack = getRequiredMethod(env, cls, "destroyTrack", "()V");
    gState.log = getOptionalMethod(env, cls, "log", "(ILjava/lang/String;)V");
    gState.pumpUsbEvents = nullptr;

    env->DeleteLocalRef(cls);
    clearException(env, "resolve.exit");

    if (!gState.createTrack || !gState.startTrack || !gState.writeTrack || !gState.destroyTrack) {
        return false;
    }
    if (!gState.pumpUsbEvents) {
        LOGI("nativeStart: USB event pump disabled in AndroidAudioIdentity; UsbAudioEngine owns event pumping");
    }
    return true;
}

static bool callCreateAndStart(JNIEnv* env, jobject callback, int sampleRate, int channels, int bits, int framesPerTick) {
    const int bytesPerFrame = std::max(1, channels * (bits / 8));
    const int bytesPerTick = std::max(bytesPerFrame, framesPerTick * bytesPerFrame);
    const int requestedBufferBytes = std::max(64 * 1024, sampleRate * bytesPerFrame / 2);

    jboolean created = env->CallBooleanMethod(
        callback,
        gState.createTrack,
        static_cast<jint>(sampleRate),
        static_cast<jint>(channels),
        static_cast<jint>(bits),
        static_cast<jint>(requestedBufferBytes));
    if (clearException(env, "createTrack") || !created) {
        return false;
    }

    env->CallVoidMethod(callback, gState.startTrack);
    if (clearException(env, "startTrack")) {
        return false;
    }

    LOGI("identity AudioTrack create/start ok sr=%d ch=%d bits=%d bytesPerTick=%d buffer=%d",
         sampleRate, channels, bits, bytesPerTick, requestedBufferBytes);
    return true;
}

static void destroyTrack(JNIEnv* env, jobject callback, const char* where) {
    if (!env || !callback || !gState.destroyTrack) return;
    clearException(env, "destroy.before");
    env->CallVoidMethod(callback, gState.destroyTrack);
    clearException(env, where ? where : "destroyTrack");
}

static void repairAudioTrack(JNIEnv* env, jobject callback, int sampleRate, int channels, int bits, int framesPerTick, const char* reason) {
    gState.repairs.fetch_add(1, std::memory_order_relaxed);
    callbackLog(env, callback, gState.log, ANDROID_LOG_WARN,
                std::string("Android audio identity repairing AudioTrack: ") + (reason ? reason : "unknown"));
    destroyTrack(env, callback, "repair.destroyTrack");
    std::this_thread::sleep_for(std::chrono::milliseconds(250));
    callCreateAndStart(env, callback, sampleRate, channels, bits, framesPerTick);
}

static void workerMain(uint64_t generation) {
    setIdentityThreadPriority();
    JNIEnv* env = nullptr;
    bool attached = false;
    {
        std::lock_guard<std::mutex> lk(gState.mutex);
        if (!gState.vm) {
            gState.running.store(false, std::memory_order_release);
            return;
        }
        if (gState.vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (gState.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("AttachCurrentThread failed");
                gState.running.store(false, std::memory_order_release);
                return;
            }
            attached = true;
        }
    }

    jobject callback = nullptr;
    int sampleRate = 48000;
    int channels = 2;
    int bits = 16;
    int framesPerTick = 384;
    std::string startReason;
    {
        std::lock_guard<std::mutex> lk(gState.mutex);
        callback = gState.callback;
        sampleRate = gState.sampleRate;
        channels = gState.channels;
        bits = gState.bits;
        framesPerTick = gState.framesPerTick;
        startReason = gState.reason;
    }

    if (!callback) {
        gState.running.store(false, std::memory_order_release);
        if (attached) gState.vm->DetachCurrentThread();
        return;
    }

    callbackLog(env, callback, gState.log, ANDROID_LOG_INFO,
                "Android audio identity native worker start: reason=" + startReason);

    if (!callCreateAndStart(env, callback, sampleRate, channels, bits, framesPerTick)) {
        callbackLog(env, callback, gState.log, ANDROID_LOG_ERROR,
                    "Android audio identity create/start failed; worker exits");
        gState.running.store(false, std::memory_order_release);
        if (attached) gState.vm->DetachCurrentThread();
        return;
    }

    const int bytesPerFrame = std::max(1, channels * (bits / 8));
    const int bytesPerTick = std::max(bytesPerFrame, framesPerTick * bytesPerFrame);
    const int sleepUs = std::max(2000, (framesPerTick * 1000000) / std::max(1, sampleRate));
    int consecutiveWriteErrors = 0;
    int64_t heartbeatCounter = 0;

    while (!gState.stopRequested.load(std::memory_order_acquire)) {
        {
            std::lock_guard<std::mutex> lk(gState.mutex);
            if (generation != gState.generation) break;
        }

        jint written = env->CallIntMethod(callback, gState.writeTrack, static_cast<jint>(bytesPerTick));
        if (clearException(env, "writeTrack") || written <= 0) {
            gState.writeErrors.fetch_add(1, std::memory_order_relaxed);
            consecutiveWriteErrors++;
            if (consecutiveWriteErrors >= 3) {
                repairAudioTrack(env, callback, sampleRate, channels, bits, framesPerTick, "write_failed");
                consecutiveWriteErrors = 0;
            }
        } else {
            gState.writes.fetch_add(1, std::memory_order_relaxed);
            gState.writeBytes.fetch_add(written, std::memory_order_relaxed);
            consecutiveWriteErrors = 0;
        }

        heartbeatCounter++;
        if ((heartbeatCounter % 1250) == 0) {
            callbackLog(env, callback, gState.log, ANDROID_LOG_INFO,
                        std::string("Android audio identity native heartbeat: writes=") +
                        std::to_string(gState.writes.load()) +
                        " bytes=" + std::to_string(gState.writeBytes.load()) +
                        " writeErrors=" + std::to_string(gState.writeErrors.load()) +
                        " usbPump=disabled"
                        " repairs=" + std::to_string(gState.repairs.load()));
        }

        std::unique_lock<std::mutex> lk(gState.mutex);
        gState.cv.wait_for(lk, std::chrono::microseconds(sleepUs), [] {
            return gState.stopRequested.load(std::memory_order_acquire);
        });
    }

    callbackLog(env, callback, gState.log, ANDROID_LOG_INFO, "Android audio identity native worker exit");
    gState.running.store(false, std::memory_order_release);
    if (attached) {
        gState.vm->DetachCurrentThread();
    }
}

static void stopInternal(JNIEnv* env, const std::string& reason) {
    std::thread workerToJoin;
    jobject callbackToRelease = nullptr;
    jmethodID destroyMethod = nullptr;
    jmethodID logMethod = nullptr;

    {
        std::lock_guard<std::mutex> lk(gState.mutex);
        gState.stopRequested.store(true, std::memory_order_release);
        gState.cv.notify_all();
        if (gState.worker.joinable()) {
            workerToJoin = std::move(gState.worker);
        }
    }

    if (workerToJoin.joinable()) {
        workerToJoin.join();
    }

    {
        std::lock_guard<std::mutex> lk(gState.mutex);
        callbackToRelease = gState.callback;
        destroyMethod = gState.destroyTrack;
        logMethod = gState.log;
        gState.callback = nullptr;
        gState.createTrack = nullptr;
        gState.startTrack = nullptr;
        gState.writeTrack = nullptr;
        gState.pumpUsbEvents = nullptr;
        gState.destroyTrack = nullptr;
        gState.log = nullptr;
        gState.running.store(false, std::memory_order_release);
        gState.stopRequested.store(false, std::memory_order_release);
    }

    if (callbackToRelease && env) {
        if (destroyMethod) {
            clearException(env, "stop.before_destroyTrack");
            env->CallVoidMethod(callbackToRelease, destroyMethod);
            clearException(env, "stop.destroyTrack");
        }
        if (logMethod) {
            callbackLog(env, callbackToRelease, logMethod, ANDROID_LOG_INFO,
                        "Android audio identity native stopped: reason=" + reason);
        }
        env->DeleteGlobalRef(callbackToRelease);
    }

    LOGI("identity stopped reason=%s", reason.c_str());
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_AndroidAudioIdentityNativeBridge_nativeStart(
        JNIEnv* env,
        jobject /*thiz*/,
        jobject callback,
        jint sampleRate,
        jint channelCount,
        jint bitsPerSample,
        jint framesPerTick,
        jstring reason) {
    if (!env || !callback) return JNI_FALSE;
    clearException(env, "nativeStart.entry");

    const std::string reasonStr = jstringToString(env, reason);

    if (gState.running.load(std::memory_order_acquire)) {
        std::lock_guard<std::mutex> lk(gState.mutex);
        gState.reason = reasonStr;
        gState.cv.notify_all();
        LOGI("identity already running; pulse reason=%s", reasonStr.c_str());
        return JNI_TRUE;
    }

    stopInternal(env, "restart_before_start");
    clearException(env, "nativeStart.after_restart_stop");

    jobject globalCallback = env->NewGlobalRef(callback);
    if (!globalCallback || clearException(env, "NewGlobalRef(callback)")) return JNI_FALSE;

    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || !vm) {
        env->DeleteGlobalRef(globalCallback);
        return JNI_FALSE;
    }

    {
        std::lock_guard<std::mutex> lk(gState.mutex);
        gState.vm = vm;
        gState.callback = globalCallback;
        gState.sampleRate = sampleRate > 0 ? sampleRate : 48000;
        gState.channels = channelCount > 0 ? channelCount : 2;
        gState.bits = bitsPerSample > 0 ? bitsPerSample : 16;
        gState.framesPerTick = framesPerTick > 0 ? framesPerTick : 384;
        gState.reason = reasonStr;
        gState.writes.store(0, std::memory_order_relaxed);
        gState.writeBytes.store(0, std::memory_order_relaxed);
        gState.writeErrors.store(0, std::memory_order_relaxed);
        gState.pumpErrors.store(0, std::memory_order_relaxed);
        gState.repairs.store(0, std::memory_order_relaxed);
        gState.stopRequested.store(false, std::memory_order_release);
        gState.generation++;
    }

    if (!resolveMethods(env, globalCallback)) {
        LOGE("identity callback method resolution failed");
        clearException(env, "nativeStart.resolve_failed");
        stopInternal(env, "resolve_failed");
        clearException(env, "nativeStart.after_resolve_failed_stop");
        return JNI_FALSE;
    }

    uint64_t generation = 0;
    {
        std::lock_guard<std::mutex> lk(gState.mutex);
        gState.running.store(true, std::memory_order_release);
        generation = gState.generation;
        gState.worker = std::thread(workerMain, generation);
    }

    LOGI("identity native thread launched reason=%s", reasonStr.c_str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_AndroidAudioIdentityNativeBridge_nativeStop(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring reason) {
    if (env) clearException(env, "nativeStop.entry");
    stopInternal(env, jstringToString(env, reason));
    if (env) clearException(env, "nativeStop.exit");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_AndroidAudioIdentityNativeBridge_nativePulse(
        JNIEnv* env,
        jobject /*thiz*/,
        jstring reason) {
    const std::string reasonStr = jstringToString(env, reason);
    {
        std::lock_guard<std::mutex> lk(gState.mutex);
        gState.reason = reasonStr;
        gState.cv.notify_all();
    }
    LOGI("identity pulse reason=%s running=%d", reasonStr.c_str(), gState.running.load() ? 1 : 0);
    return gState.running.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_AndroidAudioIdentityNativeBridge_nativeIsRunning(
        JNIEnv* /*env*/,
        jobject /*thiz*/) {
    return gState.running.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_AndroidAudioIdentityNativeBridge_nativeStats(
        JNIEnv* env,
        jobject /*thiz*/) {
    std::string stats = "running=" + std::to_string(gState.running.load(std::memory_order_acquire) ? 1 : 0) +
        " writes=" + std::to_string(gState.writes.load(std::memory_order_relaxed)) +
        " bytes=" + std::to_string(gState.writeBytes.load(std::memory_order_relaxed)) +
        " writeErrors=" + std::to_string(gState.writeErrors.load(std::memory_order_relaxed)) +
        " usbPump=disabled"
        " repairs=" + std::to_string(gState.repairs.load(std::memory_order_relaxed));
    return env->NewStringUTF(stats.c_str());
}
