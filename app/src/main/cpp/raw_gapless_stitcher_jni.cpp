#include <jni.h>

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>

#include "raw_gapless_stitcher.h"
#include "raw_transition_trace.h"

namespace {

constexpr size_t kPendingScratchCapacity = 64U * 1024U;
thread_local std::array<uint8_t, kPendingScratchCapacity> gPendingScratch{};

constexpr uint64_t kFieldMask = 0x1fffffULL;  // 21 bits per byte field.

uint64_t pack_result(const rawsmusic::gapless::StitchResult& result) noexcept {
    if (result.total_bytes <= 0 || result.pending_bytes <= 0) return 0;
    const uint64_t total = static_cast<uint64_t>(result.total_bytes) & kFieldMask;
    const uint64_t pending = static_cast<uint64_t>(result.pending_bytes) & kFieldMask;
    const uint64_t boundary = static_cast<uint64_t>(result.boundary_frame_offset) & kFieldMask;
    return (boundary << 42) | (pending << 21) | total;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_NativeGaplessStitcher_nativeStitch(
    JNIEnv* env,
    jclass,
    jbyteArray output_buffer,
    jint current_bytes,
    jbyteArray pending_buffer,
    jint pending_bytes,
    jint target_bytes,
    jint frame_size
) {
    if (!output_buffer || !pending_buffer || current_bytes <= 0 || pending_bytes <= 0 ||
        target_bytes <= current_bytes || frame_size <= 0) {
        return 0;
    }

    const jsize output_length = env->GetArrayLength(output_buffer);
    const jsize pending_length = env->GetArrayLength(pending_buffer);
    if (output_length <= 0 || pending_length <= 0 || current_bytes > output_length ||
        target_bytes > output_length || pending_bytes > pending_length ||
        static_cast<size_t>(pending_bytes) > gPendingScratch.size()) {
        return 0;
    }

    env->GetByteArrayRegion(
        pending_buffer,
        0,
        pending_bytes,
        reinterpret_cast<jbyte*>(gPendingScratch.data())
    );
    if (env->ExceptionCheck()) return 0;

    auto* output = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(output_buffer, nullptr));
    if (!output) return 0;
    const auto result = rawsmusic::gapless::stitch_block(
        reinterpret_cast<uint8_t*>(output),
        static_cast<size_t>(output_length),
        current_bytes,
        gPendingScratch.data(),
        pending_bytes,
        target_bytes,
        frame_size
    );
    env->ReleasePrimitiveArrayCritical(output_buffer, output, 0);

    if (result.total_bytes > 0) {
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::GaplessBlockStitched,
            result.boundary_frame_offset,
            result.pending_bytes / frame_size
        );
    }
    return static_cast<jlong>(pack_result(result));
}
