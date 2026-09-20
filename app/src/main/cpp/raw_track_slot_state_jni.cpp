#include <jni.h>

#include "raw_track_slot_state.h"

namespace {

rawsmusic::track_slot::AudioFormat makeFormat(
    jint sampleRate,
    jint channels,
    jint bitsPerSample
) noexcept {
    return rawsmusic::track_slot::AudioFormat{
        static_cast<int32_t>(sampleRate),
        static_cast<int32_t>(channels),
        static_cast<int32_t>(bitsPerSample),
    };
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackSlotState_nativeResetAll(
    JNIEnv*,
    jclass
) {
    rawsmusic::track_slot::resetAll();
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackSlotState_nativeInstallCurrent(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation,
    jint sampleRate,
    jint channels,
    jint bitsPerSample
) {
    rawsmusic::track_slot::installCurrent(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        makeFormat(sampleRate, channels, bitsPerSample)
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackSlotState_nativeBeginPendingV2(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation,
    jint sampleRate,
    jint channels,
    jint bitsPerSample,
    jlong minimumReadyFrames,
    jlong targetReadyFrames
) {
    (void)rawsmusic::track_slot::beginPending(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        makeFormat(sampleRate, channels, bitsPerSample),
        static_cast<int64_t>(minimumReadyFrames),
        static_cast<int64_t>(targetReadyFrames)
    );
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackSlotState_nativeBeginPendingV3(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation,
    jint sampleRate,
    jint channels,
    jint bitsPerSample,
    jlong minimumReadyFrames,
    jlong targetReadyFrames
) {
    return static_cast<jint>(rawsmusic::track_slot::beginPending(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        makeFormat(sampleRate, channels, bitsPerSample),
        static_cast<int64_t>(minimumReadyFrames),
        static_cast<int64_t>(targetReadyFrames)
    ));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackSlotState_nativeUpdatePendingReadyV2(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation,
    jlong readyFrames,
    jboolean eofDuringPrime
) {
    return rawsmusic::track_slot::updatePendingReady(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        static_cast<int64_t>(readyFrames),
        eofDuringPrime == JNI_TRUE
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackSlotState_nativeIsPendingReady(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation
) {
    return rawsmusic::track_slot::isPendingReady(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackSlotState_nativeCommitPendingV2(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation
) {
    return rawsmusic::track_slot::commitPending(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackSlotState_nativeRetirePendingV2(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation,
    jint reasonCode
) {
    rawsmusic::track_slot::retirePending(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        static_cast<int32_t>(reasonCode)
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackSlotState_nativeRetireCurrentV2(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation,
    jint reasonCode
) {
    rawsmusic::track_slot::retireCurrent(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        static_cast<int32_t>(reasonCode)
    );
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackSlotState_nativeSnapshot(
    JNIEnv* env,
    jclass
) {
    const std::string value = rawsmusic::track_slot::snapshot();
    return env->NewStringUTF(value.c_str());
}
