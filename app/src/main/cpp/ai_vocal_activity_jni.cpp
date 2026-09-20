#include <jni.h>

#include <string>

#include "ai_vocal_activity.h"

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
Java_com_rawsmusic_separation_AiSeparationRuntimeBridge_nativeAnalyzeVocalActivity(
    JNIEnv* env,
    jclass,
    jstring vocalPathValue,
    jstring sourceIdentityValue,
    jstring analyzerVersionValue,
    jint windowMs,
    jint hopMs,
    jint startHangMs,
    jint stopHangMs,
    jint mergeGapMs,
    jint preRollMs,
    jint postRollMs) {
    const std::string vocalPath = fromJString(env, vocalPathValue);
    const std::string sourceIdentity = fromJString(env, sourceIdentityValue);
    const std::string analyzerVersion = fromJString(env, analyzerVersionValue);
    if (vocalPath.empty() || sourceIdentity.empty() || analyzerVersion.empty()) {
        return toJString(env, "ERROR:Missing vocal activity input");
    }
    AiVocalActivityOptions options;
    options.windowMs = windowMs;
    options.hopMs = hopMs;
    options.startHangMs = startHangMs;
    options.stopHangMs = stopHangMs;
    options.mergeGapMs = mergeGapMs;
    options.preRollMs = preRollMs;
    options.postRollMs = postRollMs;
    AiVocalActivityResult result;
    std::string error;
    if (!analyzeAiVocalActivity(
            vocalPath, sourceIdentity, analyzerVersion, options, result, error)) {
        return toJString(env, "ERROR:" + (error.empty() ? "Voice activity analysis failed" : error));
    }
    return toJString(env, aiVocalActivityResultJson(result));
}
