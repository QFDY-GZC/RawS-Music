#include "raw_fft_convolver.h"

#include <jni.h>

extern "C" RawFftConvolver* rawsmusic_dsp_get_fft_convolver(jlong handle);

namespace {

static RawFftConvolver* convolverFromHandle(jlong handle) {
    return rawsmusic_dsp_get_fft_convolver(handle);
}

} // namespace

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeFftConvolverBridge_nativeSetEnabled(
        JNIEnv*, jobject, jlong handle, jboolean enabled) {
    if (auto* convolver = convolverFromHandle(handle)) {
        convolver->setEnabled(enabled == JNI_TRUE);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeFftConvolverBridge_nativeSetWetDry(
        JNIEnv*, jobject, jlong handle, jfloat wet, jfloat dry) {
    if (auto* convolver = convolverFromHandle(handle)) {
        convolver->setWetDry(wet, dry);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeFftConvolverBridge_nativeSetGainDb(
        JNIEnv*, jobject, jlong handle, jfloat gainDb) {
    if (auto* convolver = convolverFromHandle(handle)) {
        convolver->setGainDb(gainDb);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeFftConvolverBridge_nativeSetPreDelayMs(
        JNIEnv*, jobject, jlong handle, jfloat preDelayMs) {
    if (auto* convolver = convolverFromHandle(handle)) {
        convolver->setPreDelayMs(preDelayMs);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeFftConvolverBridge_nativeSetMix(
        JNIEnv*, jobject, jlong handle, jfloat wet, jfloat dry, jfloat gainDb, jfloat preDelayMs) {
    if (auto* convolver = convolverFromHandle(handle)) {
        convolver->setMix(wet, dry, gainDb, preDelayMs);
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_dsp_NativeFftConvolverBridge_nativeLoadIr(
        JNIEnv* env, jobject, jlong handle, jfloatArray ir, jint frames, jint irChannels) {
    if (handle == 0 || ir == nullptr || frames <= 0 || irChannels <= 0) return JNI_FALSE;

    const jsize arrayLength = env->GetArrayLength(ir);
    const jlong needed = static_cast<jlong>(frames) * static_cast<jlong>(irChannels);
    if (needed <= 0 || needed > static_cast<jlong>(arrayLength)) return JNI_FALSE;

    jfloat* irData = env->GetFloatArrayElements(ir, nullptr);
    if (irData == nullptr) return JNI_FALSE;

    bool ok = false;
    if (auto* convolver = convolverFromHandle(handle)) {
        ok = convolver->loadIr(irData, frames, irChannels);
    }

    env->ReleaseFloatArrayElements(ir, irData, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeFftConvolverBridge_nativeClearIr(
        JNIEnv*, jobject, jlong handle) {
    if (auto* convolver = convolverFromHandle(handle)) {
        convolver->clearIr();
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_dsp_NativeFftConvolverBridge_nativeIsReady(
        JNIEnv*, jobject, jlong handle) {
    if (auto* convolver = convolverFromHandle(handle)) {
        return convolver->isReady() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_dsp_NativeFftConvolverBridge_nativeGetLatencyFrames(
        JNIEnv*, jobject, jlong handle) {
    if (auto* convolver = convolverFromHandle(handle)) {
        return convolver->latencyFrames();
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_dsp_NativeFftConvolverBridge_nativeGetPreDelayFrames(
        JNIEnv*, jobject, jlong handle) {
    if (auto* convolver = convolverFromHandle(handle)) {
        return convolver->preDelayFrames();
    }
    return 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_dsp_NativeFftConvolverBridge_nativeGetStreamChannels(
        JNIEnv*, jobject, jlong handle) {
    if (auto* convolver = convolverFromHandle(handle)) {
        return convolver->streamChannels();
    }
    return 0;
}
