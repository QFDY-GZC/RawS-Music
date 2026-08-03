#include "raw_file_spectrum_analyzer.h"

#include <algorithm>
#include <cstdint>
#include <jni.h>
#include <limits>

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_core_common_spectrum_AudioSpectrumNative_nativeCreate(
    JNIEnv*, jobject, jint sample_rate, jint channels, jlong duration_ms, jint fft_size
) {
    return reinterpret_cast<jlong>(raw_file_spectrum_create(
        sample_rate, channels, static_cast<int64_t>(duration_ms), fft_size));
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_rawsmusic_core_common_spectrum_AudioSpectrumNative_nativeProcessS32Le(
    JNIEnv* env, jobject, jlong handle, jbyteArray data, jint offset, jint length
) {
    auto* analyzer = reinterpret_cast<RawFileSpectrumAnalyzer*>(handle);
    if (!analyzer || !data || offset < 0 || length <= 0) return nullptr;
    const jsize size = env->GetArrayLength(data);
    if (offset >= size) return nullptr;
    const jint safeLength = std::min(length, static_cast<jint>(size - offset));
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return nullptr;
    raw_file_spectrum_process_s32le(
        analyzer,
        reinterpret_cast<const uint8_t*>(bytes + offset),
        safeLength
    );
    float left = -120.0f;
    float right = -120.0f;
    raw_file_spectrum_current_levels(analyzer, &left, &right);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    jfloatArray result = env->NewFloatArray(2);
    if (result) {
        const float values[2] = {left, right};
        env->SetFloatArrayRegion(result, 0, 2, values);
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_rawsmusic_core_common_spectrum_AudioSpectrumNative_nativeFinish(
    JNIEnv* env, jobject, jlong handle
) {
    auto* analyzer = reinterpret_cast<RawFileSpectrumAnalyzer*>(handle);
    if (!analyzer) return nullptr;
    const std::vector<uint8_t> payload = raw_file_spectrum_finish(analyzer);
    if (payload.empty() || payload.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    jbyteArray result = env->NewByteArray(static_cast<jsize>(payload.size()));
    if (result) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(payload.size()),
            reinterpret_cast<const jbyte*>(payload.data())
        );
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_core_common_spectrum_AudioSpectrumNative_nativeRelease(
    JNIEnv*, jobject, jlong handle
) {
    raw_file_spectrum_release(reinterpret_cast<RawFileSpectrumAnalyzer*>(handle));
}
