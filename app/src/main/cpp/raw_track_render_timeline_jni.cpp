#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <new>
#include <string>

#include "raw_track_render_timeline.h"

namespace {

using rawsmusic::track_timeline::Event;
using rawsmusic::track_timeline::Timeline;
using rawsmusic::track_timeline::Update;

constexpr uint64_t kFrameMask = (1ULL << 60U) - 1ULL;

Timeline* fromHandle(jlong handle) noexcept {
    return reinterpret_cast<Timeline*>(static_cast<intptr_t>(handle));
}

jlong pack(Update update) noexcept {
    const uint64_t event = static_cast<uint64_t>(update.event) & 0x0fULL;
    const uint64_t frames = static_cast<uint64_t>(std::max<int64_t>(0, update.position_frames)) & kFrameMask;
    return static_cast<jlong>((event << 60U) | frames);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackRenderTimeline_nativeCreate(
    JNIEnv*, jclass
) {
    auto* timeline = new (std::nothrow) Timeline();
    return static_cast<jlong>(reinterpret_cast<intptr_t>(timeline));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackRenderTimeline_nativeInstallCurrent(
    JNIEnv*, jclass, jlong handle, jlong serial, jint generation, jint sample_rate, jlong position_frames
) {
    auto* timeline = fromHandle(handle);
    return timeline && timeline->installCurrent(serial, generation, sample_rate, position_frames)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackRenderTimeline_nativeArmPending(
    JNIEnv*, jclass, jlong handle, jlong serial, jint generation, jint sample_rate
) {
    auto* timeline = fromHandle(handle);
    return timeline && timeline->armPending(serial, generation, sample_rate) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackRenderTimeline_nativeOnPendingFramesRendered(
    JNIEnv*, jclass, jlong handle, jlong serial, jint generation, jlong frames
) {
    auto* timeline = fromHandle(handle);
    return timeline ? pack(timeline->onPendingFramesRendered(serial, generation, frames)) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackRenderTimeline_nativeCommitPending(
    JNIEnv*, jclass, jlong handle, jlong serial, jint generation
) {
    auto* timeline = fromHandle(handle);
    return timeline ? pack(timeline->commitPending(serial, generation)) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackRenderTimeline_nativeRetirePrevious(
    JNIEnv*, jclass, jlong handle, jlong serial, jint generation
) {
    auto* timeline = fromHandle(handle);
    return timeline ? pack(timeline->retirePrevious(serial, generation)) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackRenderTimeline_nativeCancelPending(
    JNIEnv*, jclass, jlong handle, jlong serial, jint generation
) {
    auto* timeline = fromHandle(handle);
    return timeline ? pack(timeline->cancelPending(serial, generation)) : 0;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackRenderTimeline_nativeCurrentPositionFrames(
    JNIEnv*, jclass, jlong handle
) {
    auto* timeline = fromHandle(handle);
    return timeline ? static_cast<jlong>(timeline->currentPositionFrames()) : 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackRenderTimeline_nativeSnapshot(
    JNIEnv* env, jclass, jlong handle
) {
    auto* timeline = fromHandle(handle);
    const std::string value = timeline ? timeline->snapshot() : "track_timeline_unavailable";
    return env->NewStringUTF(value.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackRenderTimeline_nativeReset(
    JNIEnv*, jclass, jlong handle
) {
    if (auto* timeline = fromHandle(handle)) timeline->reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_transition_NativeTrackRenderTimeline_nativeClose(
    JNIEnv*, jclass, jlong handle
) {
    delete fromHandle(handle);
}
