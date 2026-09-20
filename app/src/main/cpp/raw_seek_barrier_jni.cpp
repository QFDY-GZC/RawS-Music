#include <jni.h>

#include "raw_seek_barrier.h"
#include "raw_transition_trace.h"

namespace {

using rawsmusic::seek_barrier::Barrier;
using rawsmusic::seek_barrier::Phase;

Barrier* from_handle(jlong handle) noexcept {
    return reinterpret_cast<Barrier*>(handle);
}

void trace_phase(rawsmusic::transition_trace::EventCode code, jlong serial, jlong value) noexcept {
    rawsmusic::transition_trace::record(
        code,
        static_cast<int64_t>(serial),
        static_cast<int64_t>(value)
    );
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeCreate(
    JNIEnv*,
    jclass
) {
    return reinterpret_cast<jlong>(new Barrier());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeArm(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong serial,
    jlong target_ms,
    jboolean keep_paused,
    jboolean require_fade_out
) {
    auto* barrier = from_handle(handle);
    const bool ok = barrier && barrier->arm(
        static_cast<uint64_t>(serial),
        static_cast<int64_t>(target_ms),
        keep_paused == JNI_TRUE,
        require_fade_out == JNI_TRUE
    );
    if (ok) trace_phase(rawsmusic::transition_trace::EventCode::SeekArm, serial, target_ms);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeMarkFadeOutComplete(
    JNIEnv*, jclass, jlong handle, jlong serial
) {
    auto* barrier = from_handle(handle);
    const bool ok = barrier && barrier->mark_fade_out_complete(static_cast<uint64_t>(serial));
    if (ok) trace_phase(rawsmusic::transition_trace::EventCode::SeekFadeOutComplete, serial, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeIsCurrent(
    JNIEnv*, jclass, jlong handle, jlong serial
) {
    const auto* barrier = from_handle(handle);
    return barrier && barrier->is_current(static_cast<uint64_t>(serial)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeCanStartDecoder(
    JNIEnv*, jclass, jlong handle, jlong serial
) {
    const auto* barrier = from_handle(handle);
    return barrier && barrier->can_start_decoder(static_cast<uint64_t>(serial)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeMarkDecoderCommitted(
    JNIEnv*, jclass, jlong handle, jlong serial
) {
    auto* barrier = from_handle(handle);
    const bool ok = barrier && barrier->mark_decoder_committed(static_cast<uint64_t>(serial));
    if (ok) trace_phase(rawsmusic::transition_trace::EventCode::SeekDecoderCommitted, serial, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeMarkOutputCommitted(
    JNIEnv*, jclass, jlong handle, jlong serial
) {
    auto* barrier = from_handle(handle);
    const bool ok = barrier && barrier->mark_output_committed(static_cast<uint64_t>(serial));
    if (ok) trace_phase(rawsmusic::transition_trace::EventCode::SeekOutputCommitted, serial, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeReleasePaused(
    JNIEnv*, jclass, jlong handle, jlong serial
) {
    auto* barrier = from_handle(handle);
    const bool ok = barrier && barrier->release_paused(static_cast<uint64_t>(serial));
    if (ok) trace_phase(rawsmusic::transition_trace::EventCode::SeekOutputCommitted, serial, 1);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeCancel(
    JNIEnv*, jclass, jlong handle, jlong serial
) {
    auto* barrier = from_handle(handle);
    const bool ok = barrier && barrier->cancel(static_cast<uint64_t>(serial));
    if (ok) trace_phase(rawsmusic::transition_trace::EventCode::SeekCancelled, serial, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeFail(
    JNIEnv*, jclass, jlong handle, jlong serial
) {
    auto* barrier = from_handle(handle);
    const bool ok = barrier && barrier->fail(static_cast<uint64_t>(serial));
    if (ok) trace_phase(rawsmusic::transition_trace::EventCode::SeekFailed, serial, 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeSnapshot(
    JNIEnv* env, jclass, jlong handle
) {
    const auto* barrier = from_handle(handle);
    const auto snapshot = barrier ? barrier->snapshot() : rawsmusic::seek_barrier::Snapshot{};
    const jlong values[4] = {
        static_cast<jlong>(snapshot.serial),
        static_cast<jlong>(snapshot.target_ms),
        static_cast<jlong>(snapshot.phase),
        snapshot.keep_paused ? 1L : 0L,
    };
    jlongArray result = env->NewLongArray(4);
    if (result) env->SetLongArrayRegion(result, 0, 4, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_transition_NativeSeekBarrier_nativeClose(
    JNIEnv*, jclass, jlong handle
) {
    delete from_handle(handle);
}
