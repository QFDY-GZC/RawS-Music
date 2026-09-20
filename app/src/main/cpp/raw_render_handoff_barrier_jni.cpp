#include <jni.h>

#include "raw_render_handoff_barrier.h"

namespace {

rawsmusic::render_handoff::HandoffMode modeFromInt(jint mode) noexcept {
    return mode == static_cast<jint>(rawsmusic::render_handoff::HandoffMode::Crossfade)
        ? rawsmusic::render_handoff::HandoffMode::Crossfade
        : rawsmusic::render_handoff::HandoffMode::Gapless;
}

}  // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_transition_NativeRenderHandoffBarrier_nativeReset(
    JNIEnv*,
    jclass
) {
    rawsmusic::render_handoff::reset();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeRenderHandoffBarrier_nativeArm(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation,
    jint mode,
    jint frameSize
) {
    return rawsmusic::render_handoff::arm(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        modeFromInt(mode),
        static_cast<int32_t>(frameSize)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeRenderHandoffBarrier_nativeCommitAtBlockBoundary(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation,
    jlong completedBlockFrames
) {
    return rawsmusic::render_handoff::commitAtBlockBoundary(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        static_cast<int64_t>(completedBlockFrames)
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_transition_NativeRenderHandoffBarrier_nativeCancel(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation,
    jint reasonCode
) {
    rawsmusic::render_handoff::cancel(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        static_cast<int32_t>(reasonCode)
    );
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_transition_NativeRenderHandoffBarrier_nativeSnapshot(
    JNIEnv* env,
    jclass
) {
    const std::string value = rawsmusic::render_handoff::snapshot();
    return env->NewStringUTF(value.c_str());
}
