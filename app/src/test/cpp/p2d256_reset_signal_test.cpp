#include "pcm_to_dsd_converter.h"
#include <cassert>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <vector>
using namespace rawsmusic;
int main() {
    for (int rate : {44100, 48000, 192000}) for (bool dop : {false, true}) {
        DsdConfig config;
        config.rate = DsdRate::DSD256;
        config.enable_dop = dop;
        config.enable_dither = true;
        StereoPcmToDsdConverter converter, fresh;
        assert(converter.init(config, rate, 2));
        assert(fresh.init(config, rate, 2));
        const int frames = rate / 100;
        std::vector<int32_t> pcm(frames * 2);
        for (int i = 0; i < frames; ++i) {
            pcm[2*i] = static_cast<int32_t>(0.2 * 2147483647.0 * std::sin(i * 0.1));
            pcm[2*i+1] = -pcm[2*i];
        }
        std::vector<uint8_t> left(32768), right(32768), expectedLeft(32768), expectedRight(32768);
        std::array<uint32_t, 2> written{}, expected{};
        auto run = [&](StereoPcmToDsdConverter& c, std::vector<uint8_t>& l,
                       std::vector<uint8_t>& r, std::array<uint32_t,2>& count) {
            assert(c.convertInterleaved(pcm.data(), frames, 2, 24, 4,
                    l.data(), r.data(), static_cast<uint32_t>(l.size()), count));
            assert(count[0] == count[1] && count[0] > 0);
        };
        run(fresh, expectedLeft, expectedRight, expected);
        auto begin = std::chrono::steady_clock::now();
        for (int block = 0; block < 50; ++block) run(converter, left, right, written);
        const double wallMs = std::chrono::duration<double, std::milli>(
                std::chrono::steady_clock::now()-begin).count();
        converter.reset();
        assert(converter.init(config, rate, 2));
        run(converter, left, right, written);
        assert(written == expected);
        assert(std::equal(left.begin(), left.begin()+written[0], expectedLeft.begin()));
        assert(std::equal(right.begin(), right.begin()+written[1], expectedRight.begin()));
        printf("PASS DSD256 %dHz %s reset matches fresh stereo converter; host wall=%.2fms audio=500ms speed=%.2fx\n",
                rate, dop ? "DoP" : "Native", wallMs, 500.0/wallMs);
    }
}
