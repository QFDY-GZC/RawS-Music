#pragma once

#include <cstdint>

namespace rawsmusic::dsp {

// Converts the decoder's signed 32-bit PCM container to signed 16-bit PCM.
// The target 16 bits are the upper half of the 32-bit container.
int convertS32ToS16Rounded(
        const std::uint8_t* source,
        int sourceBytes,
        std::uint8_t* destination,
        int destinationCapacity);

}  // namespace rawsmusic::dsp
