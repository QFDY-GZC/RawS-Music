#include <jni.h>

#include <cstdint>
#include <string>

#include "ai_separation_engine.h"

namespace {
constexpr int kBridgeAbiVersion = 4;

std::string fromJString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (text == nullptr) return {};
    std::string result(text);
    env->ReleaseStringUTFChars(value, text);
    return result;
}

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

jmethodID findInstanceMethod(
    JNIEnv* env,
    jclass owner,
    const char* name,
    const char* signature) {
    jmethodID method = env->GetMethodID(owner, name, signature);
    if (method == nullptr && env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    return method;
}

std::string runnerError(JNIEnv* env, jobject runner, jmethodID method) {
    jstring value = static_cast<jstring>(env->CallObjectMethod(runner, method));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return "Unable to read ONNX Runtime error";
    }
    std::string result = fromJString(env, value);
    if (value != nullptr) env->DeleteLocalRef(value);
    return result;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_separation_AiSeparationRuntimeBridge_nativeBridgeAbiVersion(
    JNIEnv*, jclass) {
    return kBridgeAbiVersion;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_separation_AiSeparationRuntimeBridge_nativeSeparateSegment(
    JNIEnv* env,
    jclass,
    jobject mixtureBuffer,
    jobject vocalBuffer,
    jint sampleRate,
    jint segmentSamples,
    jint fftSize,
    jint hopLength,
    jint frequencyBins,
    jint timeFrames,
    jboolean center,
    jint paddingMode,
    jint normalization,
    jint outputType,
    jint chunkMode,
    jint edgeTrimSamples,
    jdouble compensation,
    jobject inputBuffer,
    jobject outputBuffer,
    jobject runner) {
    if (mixtureBuffer == nullptr || vocalBuffer == nullptr ||
        inputBuffer == nullptr || outputBuffer == nullptr || runner == nullptr) {
        return toJString(env, "ERROR:Missing realtime separation object");
    }
    auto* mixture = static_cast<float*>(env->GetDirectBufferAddress(mixtureBuffer));
    auto* vocal = static_cast<float*>(env->GetDirectBufferAddress(vocalBuffer));
    auto* input = static_cast<float*>(env->GetDirectBufferAddress(inputBuffer));
    auto* output = static_cast<float*>(env->GetDirectBufferAddress(outputBuffer));
    const jlong mixtureBytes = env->GetDirectBufferCapacity(mixtureBuffer);
    const jlong vocalBytes = env->GetDirectBufferCapacity(vocalBuffer);
    const jlong inputBytes = env->GetDirectBufferCapacity(inputBuffer);
    const jlong outputBytes = env->GetDirectBufferCapacity(outputBuffer);
    if (mixture == nullptr || vocal == nullptr || input == nullptr || output == nullptr) {
        return toJString(env, "ERROR:Realtime buffers must be direct ByteBuffers");
    }

    jclass runnerClass = env->GetObjectClass(runner);
    if (runnerClass == nullptr) {
        return toJString(env, "ERROR:Unable to resolve realtime runner class");
    }
    jmethodID runMethod = findInstanceMethod(env, runnerClass, "runModelFromNative", "()Z");
    jmethodID runErrorMethod = findInstanceMethod(
        env, runnerClass, "lastErrorForNative", "()Ljava/lang/String;");
    if (runMethod == nullptr || runErrorMethod == nullptr) {
        return toJString(env, "ERROR:Missing realtime ONNX callback");
    }

    AiSeparationConfig config;
    config.sampleRate = sampleRate;
    config.segmentSamples = segmentSamples;
    config.fftSize = fftSize;
    config.hopLength = hopLength;
    config.frequencyBins = frequencyBins;
    config.timeFrames = timeFrames;
    config.center = center == JNI_TRUE;
    config.paddingMode = paddingMode;
    config.normalization = normalization;
    config.outputType = outputType;
    config.chunkMode = chunkMode;
    config.edgeTrimSamples = edgeTrimSamples;
    config.compensation = compensation;
    config.denoise = false;

    const AiModelRunner runModel = [&](std::string& error) -> bool {
        const jboolean success = env->CallBooleanMethod(runner, runMethod);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            error = "ONNX Runtime realtime callback threw an exception";
            return false;
        }
        if (success != JNI_TRUE) {
            error = runnerError(env, runner, runErrorMethod);
            if (error.empty()) error = "ONNX Runtime realtime inference failed";
            return false;
        }
        return true;
    };

    std::string error;
    const bool success = runAiSeparationSegment(
        mixture,
        static_cast<int>(mixtureBytes / (sizeof(float) * 2)),
        vocal,
        static_cast<int>(vocalBytes / (sizeof(float) * 2)),
        config,
        input,
        inputBytes / static_cast<jlong>(sizeof(float)),
        output,
        outputBytes / static_cast<jlong>(sizeof(float)),
        runModel,
        error);
    return toJString(
        env,
        success ? "OK" : "ERROR:" + (
            error.empty() ? std::string("Realtime separation failed") : error));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_separation_AiSeparationRuntimeBridge_nativeSeparatePcm(
    JNIEnv* env,
    jclass,
    jstring pcmPathValue,
    jstring vocalsPathValue,
    jstring instrumentalPathValue,
    jint sampleRate,
    jint segmentSamples,
    jdouble overlap,
    jint fftSize,
    jint hopLength,
    jint frequencyBins,
    jint timeFrames,
    jboolean center,
    jint paddingMode,
    jint normalization,
    jint outputType,
    jint chunkMode,
    jint edgeTrimSamples,
    jdouble compensation,
    jboolean denoise,
    jobject inputBuffer,
    jobject outputBuffer,
    jobject runner,
    jobject callback) {
    if (inputBuffer == nullptr || outputBuffer == nullptr || runner == nullptr || callback == nullptr) {
        return toJString(env, "ERROR:Missing native separation object");
    }
    auto* input = static_cast<float*>(env->GetDirectBufferAddress(inputBuffer));
    auto* output = static_cast<float*>(env->GetDirectBufferAddress(outputBuffer));
    const jlong inputBytes = env->GetDirectBufferCapacity(inputBuffer);
    const jlong outputBytes = env->GetDirectBufferCapacity(outputBuffer);
    if (input == nullptr || output == nullptr || inputBytes <= 0 || outputBytes <= 0) {
        return toJString(env, "ERROR:Tensor buffers must be direct ByteBuffers");
    }

    jclass runnerClass = env->GetObjectClass(runner);
    jclass callbackClass = env->GetObjectClass(callback);
    if (runnerClass == nullptr || callbackClass == nullptr) {
        return toJString(env, "ERROR:Unable to resolve callback classes");
    }
    jmethodID runMethod = findInstanceMethod(
        env, runnerClass, "runModelFromNative", "()Z");
    if (runMethod == nullptr) {
        return toJString(env, "ERROR:Missing JNI method runModelFromNative()Z");
    }
    jmethodID runErrorMethod = findInstanceMethod(
        env,
        runnerClass, "lastErrorForNative", "()Ljava/lang/String;");
    if (runErrorMethod == nullptr) {
        return toJString(env, "ERROR:Missing JNI method lastErrorForNative()String");
    }
    jmethodID cancelMethod = findInstanceMethod(
        env, callbackClass, "isCancelled", "()Z");
    if (cancelMethod == nullptr) {
        return toJString(env, "ERROR:Missing JNI callback isCancelled()Z");
    }
    jmethodID progressMethod = findInstanceMethod(
        env, callbackClass, "onProgress", "(JJII)V");
    if (progressMethod == nullptr) {
        return toJString(env, "ERROR:Missing JNI callback onProgress(JJII)V");
    }

    AiSeparationConfig config;
    config.sampleRate = sampleRate;
    config.segmentSamples = segmentSamples;
    config.overlap = overlap;
    config.fftSize = fftSize;
    config.hopLength = hopLength;
    config.frequencyBins = frequencyBins;
    config.timeFrames = timeFrames;
    config.center = center == JNI_TRUE;
    config.paddingMode = paddingMode;
    config.normalization = normalization;
    config.outputType = outputType;
    config.chunkMode = chunkMode;
    config.edgeTrimSamples = edgeTrimSamples;
    config.compensation = compensation;
    config.denoise = denoise == JNI_TRUE;

    const AiModelRunner runModel = [&](std::string& error) -> bool {
        const jboolean success = env->CallBooleanMethod(runner, runMethod);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            error = "ONNX Runtime callback threw an exception";
            return false;
        }
        if (success != JNI_TRUE) {
            error = runnerError(env, runner, runErrorMethod);
            if (error.empty()) error = "ONNX Runtime inference failed";
            return false;
        }
        return true;
    };
    const AiCancelCheck isCancelled = [&]() -> bool {
        const jboolean cancelled = env->CallBooleanMethod(callback, cancelMethod);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return true;
        }
        return cancelled == JNI_TRUE;
    };
    const AiProgressCallback onProgress = [&](int64_t processed, int64_t total, int index, int count) {
        env->CallVoidMethod(
            callback, progressMethod,
            static_cast<jlong>(processed), static_cast<jlong>(total),
            static_cast<jint>(index), static_cast<jint>(count));
        if (env->ExceptionCheck()) env->ExceptionClear();
    };

    AiSeparationStats stats;
    std::string error;
    const bool success = runAiSeparation(
        fromJString(env, pcmPathValue),
        fromJString(env, vocalsPathValue),
        fromJString(env, instrumentalPathValue),
        config,
        input,
        inputBytes / static_cast<jlong>(sizeof(float)),
        output,
        outputBytes / static_cast<jlong>(sizeof(float)),
        runModel,
        isCancelled,
        onProgress,
        stats,
        error);
    if (!success) {
        return toJString(env, "ERROR:" + (error.empty() ? std::string("Separation failed") : error));
    }
    return toJString(
        env,
        "OK:" + std::to_string(stats.totalFrames) + "|" +
            std::to_string(stats.processedSegments) + "|" +
            std::to_string(stats.elapsedMs));
}
