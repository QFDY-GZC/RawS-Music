#pragma once

#include <cstddef>
#include <cstdint>

namespace rawsmusic::gapless {

struct StitchResult final {
    int32_t total_bytes = 0;
    int32_t pending_bytes = 0;
    int32_t boundary_frame_offset = 0;
};

/**
 * Joins the final complete PCM frames of the current slot and the first complete
 * PCM frames of the pending slot inside one renderer block.
 *
 * The function performs no allocation, takes no locks, and never changes sample
 * values. It only copies frame-aligned bytes, preserving bit-perfect PCM for
 * compatible non-DSD renderer paths.
 */
StitchResult stitch_block(
    uint8_t* output,
    size_t output_capacity,
    int current_bytes,
    const uint8_t* pending,
    int pending_bytes,
    int target_bytes,
    int frame_size
) noexcept;

}  // namespace rawsmusic::gapless
