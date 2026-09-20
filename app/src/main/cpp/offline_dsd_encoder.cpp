#include "offline_dsd_encoder.h"

#include <limits>

namespace rawsmusic::transcode {
namespace {

bool toDsdRate(int multiplier, rawsmusic::DsdRate& rate) {
    switch (multiplier) {
        case 64: rate = rawsmusic::DsdRate::DSD64; return true;
        case 128: rate = rawsmusic::DsdRate::DSD128; return true;
        case 256: rate = rawsmusic::DsdRate::DSD256; return true;
        case 512: rate = rawsmusic::DsdRate::DSD512; return true;
        case 1024: rate = rawsmusic::DsdRate::DSD1024; return true;
        default: return false;
    }
}

}  // namespace

bool OfflineDsdEncoder::init(int input_rate_hz, int channels, int dsd_multiplier) {
    initialized_ = false;
    input_rate_hz_ = 0;
    channels_ = 0;
    for (auto& pcm : channel_pcm_) pcm.clear();

    rawsmusic::DsdRate rate{};
    if (input_rate_hz <= 0 || channels <= 0 || channels > 2 || !toDsdRate(dsd_multiplier, rate)) {
        return false;
    }

    rawsmusic::DsdConfig config;
    config.rate = rate;
    config.type = rawsmusic::DsdConversionType::Standard;
    config.enable_dither = false;
    config.enable_dop = false;
    config.volume_scale = 1.0;

    for (int channel = 0; channel < channels; ++channel) {
        if (!converters_[static_cast<size_t>(channel)].init(config, input_rate_hz)) {
            return false;
        }
    }
    const uint32_t factor = converters_[0].getActualUpsamplingFactor();
    if (factor == 0u || (factor % 8u) != 0u) return false;
    for (int channel = 1; channel < channels; ++channel) {
        if (converters_[static_cast<size_t>(channel)].getDsdRateHz() != converters_[0].getDsdRateHz() ||
            converters_[static_cast<size_t>(channel)].getActualUpsamplingFactor() != factor) {
            return false;
        }
    }

    input_rate_hz_ = input_rate_hz;
    channels_ = channels;
    initialized_ = true;
    return true;
}

bool OfflineDsdEncoder::convertInterleavedS32(
    const int32_t* pcm,
    uint32_t frame_count,
    std::array<std::vector<uint8_t>, 2>& output
) {
    if (!initialized_ || !pcm || frame_count == 0u) return false;
    const uint32_t factor = upsamplingFactor();
    if (factor == 0u || (factor % 8u) != 0u) return false;

    const uint64_t bytes64 = static_cast<uint64_t>(frame_count) *
        static_cast<uint64_t>(factor / 8u);
    if (bytes64 == 0u || bytes64 > std::numeric_limits<uint32_t>::max()) return false;
    const uint32_t bytesPerChannel = static_cast<uint32_t>(bytes64);

    for (int channel = 0; channel < channels_; ++channel) {
        auto& mono = channel_pcm_[static_cast<size_t>(channel)];
        mono.resize(frame_count);
        for (uint32_t frame = 0; frame < frame_count; ++frame) {
            mono[frame] = pcm[static_cast<size_t>(frame) * static_cast<size_t>(channels_) +
                              static_cast<size_t>(channel)];
        }
        auto& encoded = output[static_cast<size_t>(channel)];
        encoded.resize(bytesPerChannel);
        const uint32_t written = converters_[static_cast<size_t>(channel)].convert(
            mono.data(),
            frame_count,
            32,
            encoded.data(),
            bytesPerChannel
        );
        if (written != bytesPerChannel) return false;
    }
    for (int channel = channels_; channel < 2; ++channel) output[static_cast<size_t>(channel)].clear();
    return true;
}

uint32_t OfflineDsdEncoder::dsdSampleRateHz() const {
    return initialized_ ? converters_[0].getDsdRateHz() : 0u;
}

uint32_t OfflineDsdEncoder::upsamplingFactor() const {
    return initialized_ ? converters_[0].getActualUpsamplingFactor() : 0u;
}

}  // namespace rawsmusic::transcode
