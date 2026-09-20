#pragma once

#include <vector>

namespace rawsmusic::usb {

// Builds a conservative output-major matrix for mono/stereo conversion.
// Returns false for multichannel layouts so libswresample keeps its standard
// role-aware matrix instead of receiving a guessed one.
bool buildExplicitChannelMatrix(
        int sourceChannels,
        int deviceChannels,
        std::vector<double>* matrix);

}  // namespace rawsmusic::usb
