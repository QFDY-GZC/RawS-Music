#pragma once

#include <cstdint>
#include <string>

namespace rawsmusic::render_handoff {

enum class HandoffMode : int32_t {
    Gapless = 1,
    Crossfade = 2,
};

void reset() noexcept;

/** Arms the matching READY pending slot for a later block-boundary commit. */
bool arm(
    int64_t decoderSerial,
    int32_t generation,
    HandoffMode mode,
    int32_t frameSize
) noexcept;

/**
 * Commits only an armed, still-READY pending slot at a renderer-declared block
 * boundary. Crossfade commits require a non-empty completed mixed block;
 * gapless commits may use zero because EOF itself is the boundary.
 */
bool commitAtBlockBoundary(
    int64_t decoderSerial,
    int32_t generation,
    int64_t completedBlockFrames
) noexcept;

void cancel(int64_t decoderSerial, int32_t generation, int32_t reasonCode) noexcept;

std::string snapshot();

}  // namespace rawsmusic::render_handoff
