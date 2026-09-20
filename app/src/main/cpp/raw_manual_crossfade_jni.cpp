#include <jni.h>

#include <algorithm>
#include <array>
#include <cstdint>

#include "raw_manual_crossfade.h"
#include "raw_transition_trace.h"

namespace {

using rawsmusic::manual_crossfade::Processor;
using rawsmusic::manual_crossfade::SampleFormat;

constexpr uint64_t kCompletedFlag = 1ULL << 63;
constexpr uint64_t kActiveFlag = 1ULL << 62;
constexpr uint64_t kFrameMask = kActiveFlag - 1ULL;
constexpr size_t kPendingScratchCapacity = 64U * 1024U;
thread_local std::array<uint8_t, kPendingScratchCapacity> gPendingScratch{};

Processor* from_handle(jlong handle) noexcept {
    return reinterpret_cast<Processor*>(handle);
}

SampleFormat format_from_int(jint format) noexcept {
    switch (format) {
        case 2: return SampleFormat::PcmS32;
        case 3: return SampleFormat::PcmFloat;
        case 4: return SampleFormat::PcmS24Packed;
        default: return SampleFormat::PcmS16;
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_NativeManualCrossfadeProcessor_nativeCreate(
    JNIEnv*,
    jclass
) {
    return reinterpret_cast<jlong>(new Processor());
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_NativeManualCrossfadeProcessor_nativeArm(
    JNIEnv*,
    jclass,
    jlong handle,
    jint duration_ms
) {
    if (auto* processor = from_handle(handle)) {
        processor->arm(duration_ms);
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::ManualCrossfadeArm,
            static_cast<int64_t>(duration_ms),
            0
        );
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_NativeManualCrossfadeProcessor_nativeClear(
    JNIEnv*,
    jclass,
    jlong handle
) {
    if (auto* processor = from_handle(handle)) {
        processor->clear();
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::ManualCrossfadeClear,
            static_cast<int64_t>(handle),
            0
        );
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_NativeManualCrossfadeProcessor_nativeProcess(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray current_buffer,
    jbyteArray pending_buffer,
    jint offset,
    jint length,
    jint sample_rate,
    jint frame_size,
    jint format
) {
    auto* processor = from_handle(handle);
    if (!processor || !current_buffer || !pending_buffer || offset < 0 || length <= 0 || frame_size <= 0) {
        return 0;
    }
    const jsize current_length = env->GetArrayLength(current_buffer);
    const jsize pending_length = env->GetArrayLength(pending_buffer);
    if (offset > current_length || length > current_length - offset ||
        offset > pending_length || length > pending_length - offset) {
        return 0;
    }

    if (static_cast<size_t>(length) > gPendingScratch.size()) return 0;
    env->GetByteArrayRegion(
        pending_buffer,
        offset,
        length,
        reinterpret_cast<jbyte*>(gPendingScratch.data())
    );
    if (env->ExceptionCheck()) return 0;

    auto* current = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(current_buffer, nullptr));
    if (!current) return 0;
    const int64_t before_frames = processor->processed_frames();
    const uint64_t completion_before = processor->completion_serial();
    processor->process(
        reinterpret_cast<uint8_t*>(current) + offset,
        gPendingScratch.data(),
        static_cast<size_t>(length),
        sample_rate,
        frame_size,
        format_from_int(format)
    );
    env->ReleasePrimitiveArrayCritical(current_buffer, current, 0);

    const int64_t after_frames = processor->processed_frames();
    const uint64_t completion_after = processor->completion_serial();
    if (before_frames == 0 && after_frames > 0) {
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::ManualCrossfadeFirstMix,
            after_frames,
            processor->total_frames()
        );
    }
    const bool completed = completion_after != completion_before;
    if (completed) {
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::ManualCrossfadeComplete,
            after_frames,
            processor->total_frames()
        );
    }

    uint64_t packed = static_cast<uint64_t>(std::max<int64_t>(0, after_frames)) & kFrameMask;
    if (processor->is_active_or_pending()) packed |= kActiveFlag;
    if (completed) packed |= kCompletedFlag;
    return static_cast<jlong>(packed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_NativeManualCrossfadeProcessor_nativeClose(
    JNIEnv*,
    jclass,
    jlong handle
) {
    delete from_handle(handle);
}
