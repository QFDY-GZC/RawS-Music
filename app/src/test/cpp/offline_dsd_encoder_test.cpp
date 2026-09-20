#include "../../main/cpp/offline_dsd_encoder.h"
#include "../../main/cpp/audio_transcode_policy.h"

#include <array>
#include <cassert>
#include <cstdint>
#include <vector>

int main() {
    using namespace rawsmusic::transcode;

    for (const int sourceRate : kSelectablePcmSampleRatesHz) {
        for (const int multiplier : kSelectableDsdMultipliers) {
            OfflineDsdEncoder encoder;
            assert(encoder.init(sourceRate, 2, multiplier));
            assert(encoder.dsdSampleRateHz() == static_cast<uint32_t>(
                resolveDsdSampleRate(sourceRate, multiplier)
            ));
            assert(encoder.upsamplingFactor() > 0u);
            assert((encoder.upsamplingFactor() % 8u) == 0u);
        }
    }

    OfflineDsdEncoder encoder;
    assert(encoder.init(44100, 2, 64));
    std::array<int32_t, 16> pcm{};
    for (size_t frame = 0; frame < 8; ++frame) {
        pcm[frame * 2] = frame % 2 == 0 ? 0x08000000 : -0x08000000;
        pcm[frame * 2 + 1] = -pcm[frame * 2];
    }
    std::array<std::vector<uint8_t>, 2> output;
    assert(encoder.convertInterleavedS32(pcm.data(), 8, output));
    assert(output[0].size() == 8u * 8u);
    assert(output[1].size() == output[0].size());

    return 0;
}
