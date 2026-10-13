#include "../../main/cpp/pcm_to_dsd_converter.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

// Host regression: compile with pcm_to_dsd_converter.cpp, no Android/USB device required.
int main() {
    constexpr double pi = 3.14159265358979323846;
    int failures = 0;
    for (int rate : {44100, 48000, 96000, 176400, 192000, 384000}) for (int multiplier : {64, 128, 256}) {
        for (double amplitude : {0.0001, 0.5, 0.98}) {
            rawsmusic::DsdConfig config;
            config.rate = static_cast<rawsmusic::DsdRate>(multiplier);
            config.enable_dither = false;
            rawsmusic::PcmToDsdConverter converter;
            if (!converter.init(config, rate)) return 2;
            std::vector<int32_t> pcm(rate / 2);
            for (size_t i = 0; i < pcm.size(); ++i) {
                pcm[i] = static_cast<int32_t>(amplitude * 2147483647.0 *
                        std::sin(2 * pi * 1000 * i / rate));
            }
            const size_t expectedBytes = static_cast<uint64_t>(pcm.size()) *
                    converter.getDsdRateHz() / rate / 8;
            std::vector<uint8_t> output(expectedBytes + 64);
            const size_t written = converter.convert(pcm.data(), pcm.size(), 32,
                    output.data(), output.size());
            if (written != expectedBytes) return 3;
            const double fs = converter.getDsdRateHz();
            const size_t skip = static_cast<size_t>(fs / 10);
            const size_t count = written * 8 - skip;
            const double step = 2 * pi * 1000 / fs;
            const double cs = std::cos(step), sn = std::sin(step);
            double c = std::cos(step * skip), s = std::sin(step * skip);
            double real = 0, imag = 0, dc = 0;
            for (size_t i = skip; i < written * 8; ++i) {
                const double value = (output[i / 8] >> (7 - i % 8)) & 1 ? 1 : -1;
                real += value * c; imag += value * s; dc += value;
                const double nextC = c * cs - s * sn;
                s = s * cs + c * sn; c = nextC;
            }
            // Existing output headroom is retained by the fix. Allow 5% for interpolation/filter
            // response and finite observation, but never permit a quiet tone to disappear.
            const double gain = multiplier == 64 ? 0.7 : multiplier == 128 ? 5.0 / 7 : 0.85;
            const double measured = 2 * std::hypot(real, imag) / count;
            const double ratio = measured / (amplitude * gain);
            const bool ok = ratio > 0.95 && ratio < 1.05 && std::fabs(dc / count) < 0.00001;
            std::printf("%s sr=%d DSD%d amplitude=%.5f gain_ratio=%.6f dc=%.8f\n",
                    ok ? "PASS" : "FAIL", rate, multiplier, amplitude, ratio, dc / count);
            if (!ok) ++failures;
        }
    }
    // Loud -> silence -> quiet, including fractional-rate scheduling (32 kHz), must
    // produce exactly the same bitstream regardless of USB/decoder block boundaries.
    for (int rate : {32000, 44100, 48000, 96000, 192000}) {
        for (int multiplier : {64, 128, 256, 512}) for (bool dither : {false, true}) {
            rawsmusic::DsdConfig config;
            config.rate = static_cast<rawsmusic::DsdRate>(multiplier);
            config.enable_dither = dither;
            rawsmusic::PcmToDsdConverter wholeConverter, splitConverter;
            wholeConverter.init(config, rate); splitConverter.init(config, rate);
            std::vector<int32_t> pcm(rate / 10);
            for (size_t i = 0; i < pcm.size(); ++i) {
                const double level = i < pcm.size()/3 ? 0.98 : i < pcm.size()*2/3 ? 0 : 0.0001;
                pcm[i] = static_cast<int32_t>(level * 2147483647.0 * std::sin(2*pi*1000*i/rate));
            }
            const size_t bytes = static_cast<uint64_t>(pcm.size()) * wholeConverter.getDsdRateHz() / rate / 8;
            std::vector<uint8_t> whole(bytes+64), split(bytes+64);
            size_t a = wholeConverter.convert(pcm.data(),pcm.size(),32,whole.data(),whole.size()), b=0;
            const size_t chunk = rate / 100;
            for (size_t i=0;i<pcm.size();i+=chunk) {
                b += splitConverter.convert(pcm.data()+i,std::min(chunk,pcm.size()-i),32,
                        split.data()+b,split.size()-b);
            }
            const bool ok = a == bytes && b == a && std::equal(whole.begin(),whole.begin()+a,split.begin());
            if (!ok) { std::printf("FAIL continuity sr=%d DSD%d dither=%d\n",rate,multiplier,dither); ++failures; }
            // Reset/seek must reproduce a fresh converter, not retain a previous song's integrators.
            splitConverter.reset();
            b = splitConverter.convert(pcm.data(),pcm.size(),32,split.data(),split.size());
            if (a != b || !std::equal(whole.begin(),whole.begin()+a,split.begin())) ++failures;
        }
    }
    std::printf("failures=%d (54 amplitude cases, 40 continuity/reset cases)\n", failures);
    return failures ? 1 : 0;
}
