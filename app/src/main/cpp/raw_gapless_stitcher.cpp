#include "raw_gapless_stitcher.h"

#include <algorithm>
#include <cstring>

namespace rawsmusic::gapless {
namespace {

inline int align_down(int value, int frame_size) noexcept {
    if (value <= 0 || frame_size <= 0) return 0;
    return value - (value % frame_size);
}

}  // namespace

StitchResult stitch_block(
    uint8_t* output,
    size_t output_capacity,
    int current_bytes,
    const uint8_t* pending,
    int pending_bytes,
    int target_bytes,
    int frame_size
) noexcept {
    StitchResult result{};
    if (!output || !pending || frame_size <= 0 || output_capacity == 0) return result;

    const int capacity = static_cast<int>(std::min<size_t>(
        output_capacity,
        static_cast<size_t>(0x7fffffff)
    ));
    const int aligned_current = align_down(std::min(current_bytes, capacity), frame_size);
    const int aligned_target = align_down(std::min(target_bytes, capacity), frame_size);
    const int aligned_pending = align_down(std::max(0, pending_bytes), frame_size);
    if (aligned_current <= 0 || aligned_target <= aligned_current || aligned_pending <= 0) {
        return result;
    }

    const int writable = aligned_target - aligned_current;
    const int copied = std::min(writable, aligned_pending);
    if (copied <= 0) return result;

    std::memcpy(output + aligned_current, pending, static_cast<size_t>(copied));
    result.total_bytes = aligned_current + copied;
    result.pending_bytes = copied;
    result.boundary_frame_offset = aligned_current / frame_size;
    return result;
}

}  // namespace rawsmusic::gapless
