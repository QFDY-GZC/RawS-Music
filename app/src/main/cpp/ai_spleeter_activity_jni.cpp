#include <jni.h>

#include <string>

#include "ai_spleeter_activity.h"

namespace {
std::string fromJString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_separation_AiSeparationRuntimeBridge_nativeAnalyzeSpleeterVocalActivity(
    JNIEnv* env,
    jclass,
    jstring pcmPathValue,
    jint sampleRate,
    jobject inputBuffer,
    jobject outputBuffer,
    jobject runner,
    jobject callback) {
    if (inputBuffer == nullptr || outputBuffer == nullptr || runner == nullptr || callback == nullptr) {
        return toJString(env, "ERROR:缺少 Spleeter 分析对象");
    }
    auto* input = static_cast<float*>(env->GetDirectBufferAddress(inputBuffer));
    auto* output = static_cast<float*>(env->GetDirectBufferAddress(outputBuffer));
    const jlong inputBytes = env->GetDirectBufferCapacity(inputBuffer);
    const jlong outputBytes = env->GetDirectBufferCapacity(outputBuffer);
    if (input == nullptr || output == nullptr || inputBytes <= 0 || outputBytes <= 0) {
        return toJString(env, "ERROR:Spleeter 张量缓冲区必须是 direct ByteBuffer");
    }
    jclass runnerClass = env->GetObjectClass(runner);
    if (runnerClass == nullptr) {
        return toJString(env, "ERROR:无法解析 Spleeter 推理回调");
    }
    const jmethodID runMethod = env->GetMethodID(runnerClass, "runModelFromNative", "()Z");
    const jmethodID errorMethod = env->GetMethodID(
        runnerClass, "lastErrorForNative", "()Ljava/lang/String;");
    if (runMethod == nullptr || errorMethod == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return toJString(env, "ERROR:缺少 Spleeter ONNX 回调");
    }
    jclass callbackClass = env->GetObjectClass(callback);
    const jmethodID cancelledMethod = callbackClass == nullptr
        ? nullptr
        : env->GetMethodID(callbackClass, "isCancelled", "()Z");
    const jmethodID progressMethod = callbackClass == nullptr
        ? nullptr
        : env->GetMethodID(callbackClass, "onProgress", "(JJII)V");
    if (cancelledMethod == nullptr || progressMethod == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return toJString(env, "ERROR:缺少 Spleeter 进度回调");
    }
    const SpleeterModelRunner runModel = [&](std::string& error) -> bool {
        if (env->CallBooleanMethod(runner, runMethod) != JNI_TRUE) {
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                error = "Spleeter ONNX 回调异常";
                return false;
            }
            auto* value = static_cast<jstring>(env->CallObjectMethod(runner, errorMethod));
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                error = "无法读取 Spleeter ONNX 错误";
                return false;
            }
            error = fromJString(env, value);
            if (value != nullptr) env->DeleteLocalRef(value);
            if (error.empty()) error = "Spleeter ONNX 推理失败";
            return false;
        }
        return true;
    };
    const SpleeterProgressCallback reportProgress = [&](int64_t completed, int64_t total) -> bool {
        if (env->CallBooleanMethod(callback, cancelledMethod) == JNI_TRUE) {
            return false;
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        env->CallVoidMethod(
            callback,
            progressMethod,
            static_cast<jlong>(completed),
            static_cast<jlong>(total),
            static_cast<jint>(completed),
            static_cast<jint>(total));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
        return true;
    };
    std::string result;
    std::string error;
    const bool success = runSpleeterVocalActivity(
        fromJString(env, pcmPathValue),
        sampleRate,
        input,
        inputBytes / static_cast<jlong>(sizeof(float)),
        output,
        outputBytes / static_cast<jlong>(sizeof(float)),
        runModel,
        reportProgress,
        result,
        error);
    return toJString(env, success ? result : "ERROR:" + error);
}
