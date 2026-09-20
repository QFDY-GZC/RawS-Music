#pragma once

#include "pcm_to_dsd_converter.h"

#include <array>
#include <cstdint>
#include <vector>

namespace rawsmusic::transcode {

/**
 * Offline wrapper around the existing stateful PCM->DSD modulator.
 *
 * Deliberately owns the per-channel PcmToDsdConverter instances directly
 * instead of using the realtime stereo helper, so DSD256+ file conversion does
 * not create the USB path's high-priority channel helper thread.
 */
class OfflineDsdEncoder {
public:
    bool init(int input_rate_hz, int channels, int dsd_multiplier);

    bool convertInterleavedS32(
        const int32_t* pcm,
        uint32_t frame_count,
        std::array<std::vector<uint8_t>, 2>& output
    );

    int channels() const { return channels_; }
    int inputRateHz() const { return input_rate_hz_; }
    uint32_t dsdSampleRateHz() const;
    uint32_t upsamplingFactor() const;

private:
    std::array<rawsmusic::PcmToDsdConverter, 2> converters_{};
    std::array<std::vector<int32_t>, 2> channel_pcm_{};
    int input_rate_hz_ = 0;
    int channels_ = 0;
    bool initialized_ = false;
};

}  // namespace rawsmusic::transcode
