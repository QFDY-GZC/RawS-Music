#include "raw_pcm_channel_matrix.h"

#include <cstddef>

namespace rawsmusic::usb {

bool buildExplicitChannelMatrix(
        int sourceChannels,
        int deviceChannels,
        std::vector<double>* matrix) {
    if (!matrix || sourceChannels <= 0 || deviceChannels <= 0 ||
        sourceChannels == deviceChannels) {
        return false;
    }

    matrix->assign(static_cast<std::size_t>(sourceChannels) * deviceChannels, 0.0);
    if (sourceChannels == 1 && deviceChannels == 2) {
        // Duplicate mono without adding gain.
        (*matrix)[0] = 1.0;
        (*matrix)[1] = 1.0;
        return true;
    }
    if (sourceChannels == 2 && deviceChannels == 1) {
        // Average L/R so a mono fold-down cannot add 6 dB.
        (*matrix)[0] = 0.5;
        (*matrix)[1] = 0.5;
        return true;
    }

    matrix->clear();
    return false;
}

}  // namespace rawsmusic::usb
