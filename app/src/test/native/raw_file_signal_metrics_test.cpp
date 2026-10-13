#include "../../main/cpp/raw_file_spectrum_analyzer.h"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <limits>
#include <vector>

namespace {
constexpr double pi = 3.14159265358979323846;
template <typename T> T at(const std::vector<uint8_t>& p, size_t offset) {
    assert(offset + sizeof(T) <= p.size());
    T value;
    std::memcpy(&value, p.data() + offset, sizeof(T));
    return value;
}
void near(float actual, double expected, double tolerance = 1e-5) {
    if (!std::isfinite(actual) || std::abs(actual - expected) > tolerance) {
        std::fprintf(stderr, "actual %.9g, expected %.9g +/- %.9g\n",
                     actual, expected, tolerance);
        std::abort();
    }
}
int32_t pcm(double value) { return static_cast<int32_t>(std::llround(value * 2147483648.0)); }
std::vector<uint8_t> analyze(const std::vector<int32_t>& samples, int channels = 2,
                             int rate = 48000, size_t slice = 4096) {
    auto* a = raw_file_spectrum_create(rate, channels, 1000, 1024);
    assert(a);
    const auto* bytes = reinterpret_cast<const uint8_t*>(samples.data());
    const size_t size = samples.size() * 4;
    for (size_t offset = 0; offset < size; offset += slice) {
        raw_file_spectrum_process_s32le(a, bytes + offset,
            static_cast<int>(std::min(slice, size - offset)));
    }
    const auto result = raw_file_spectrum_finish(a);
    // Finalization must be repeatable, including delayed true-peak flushing.
    assert(raw_file_spectrum_finish(a) == result);
    raw_file_spectrum_release(a);
    assert(at<uint32_t>(result, 4) == 5);
    const size_t expectedSize = 180 + at<int32_t>(result, 20) * 4 +
        at<int32_t>(result, 28) * (8 + 2 * at<int32_t>(result, 24));
    assert(result.size() == expectedSize);
    return result;
}
std::vector<int32_t> tone(int rate, int frames, double amplitude,
                          int channels, double rightSign = 1.0, double dc = 0.0,
                          double frequency = 1000.0, double phase = 0.0) {
    std::vector<int32_t> data;
    data.reserve(static_cast<size_t>(frames) * channels);
    for (int i = 0; i < frames; ++i) {
        const double value = amplitude * std::sin(2 * pi * frequency * i / rate + phase);
        data.push_back(pcm(value + dc));
        if (channels == 2) data.push_back(pcm(rightSign * value - dc));
    }
    return data;
}
void rawTailAndOverlap() {
    // Only the final sample is loud. No complete FFT contains it.
    std::vector<int32_t> data(2050 * 2, 0);
    data[data.size() - 2] = pcm(0.5);
    const auto p = analyze(data);
    near(at<float>(p, 48), -6.020599913);
    near(at<float>(p, 56), 20 * std::log10(0.5 / std::sqrt(2050.0)));
    assert(at<uint64_t>(p, 64) == 2050);
    assert((at<uint32_t>(p, 120) & 1) != 0);
    // A sample in the overlap must have exactly the same energy as the tail.
    data[data.size() - 2] = 0;
    data[700 * 2] = pcm(0.5);
    const auto overlap = analyze(data);
    near(at<float>(overlap, 56), at<float>(p, 56));
    // Whole samples below the FFT display floor are still measurable.
    const auto tiny = analyze({1, 0});
    near(at<float>(tiny, 48), -186.638597312, 2e-5);
}
void dcStereoAndMono() {
    const auto same = analyze(tone(48000, 48000, 0.25, 2));
    near(at<float>(same, 144), 1.0);
    near(at<float>(same, 148), -15.051499783, 1e-4);
    near(at<float>(same, 152), -120.0);
    const auto inverse = analyze(tone(48000, 48000, 0.25, 2, -1));
    near(at<float>(inverse, 144), -1.0);
    near(at<float>(inverse, 148), -120.0);
    near(at<float>(inverse, 152), -15.051499783, 1e-4);
    const auto biased = analyze(tone(48000, 48000, 0.125, 2, 1, 0.25));
    near(at<float>(biased, 136), 0.25);
    near(at<float>(biased, 140), -0.25);
    near(at<float>(biased, 144), 1.0); // Remove DC before correlation.
    near(at<float>(biased, 152), -12.041199826); // M/S energy retains DC.
    const auto mono = analyze({pcm(0.25)}, 1, 4000, 1);
    assert(at<int32_t>(mono, 8) == 4000);
    assert(at<int32_t>(mono, 12) == 1);
    assert(at<uint64_t>(mono, 64) == 1);
    near(at<float>(mono, 48), -12.041199826);
    near(at<float>(mono, 56), -12.041199826);
    near(at<float>(mono, 136), 0.25);
    assert(std::isnan(at<float>(mono, 140)));
    assert(std::isnan(at<float>(mono, 144)));
    assert(std::isnan(at<float>(mono, 148)));
    assert(std::isnan(at<float>(mono, 152)));
    const auto constant = analyze(std::vector<int32_t>(2000, pcm(0.25)));
    assert(std::isnan(at<float>(constant, 144)));
    // Tiny AC on a large DC offset still has a well-defined correlation.
    std::vector<int32_t> tinyAc;
    for (int i = 0; i < 4000; ++i) {
        tinyAc.push_back(0x40000000 + (i % 2));
        tinyAc.push_back(0x40000000 - (i % 2));
    }
    const auto dcDominated = analyze(tinyAc);
    near(at<float>(dcDominated, 144), -1, 1e-6);
}
void silenceRailsAndByteBoundaries() {
    // 21474 / 2^31 is below 1e-5; 21475 is above. Both channels must pass.
    const std::vector<int32_t> data = {0,0, 21474,-21474, 21475,0, 0,21475,
        0,0, 1,-1, 0,0, INT32_MAX,0, 0,INT32_MIN, INT32_MAX,INT32_MIN,
        0x7fff0000,0, INT32_MAX-1,INT32_MIN+1};
    const auto p = analyze(data, 2, 48000, 3);
    assert(at<uint64_t>(p, 64) == 12);
    assert(at<uint64_t>(p, 156) == 5);
    assert(at<uint64_t>(p, 164) == 3);
    assert(at<uint64_t>(p, 172) == 3); // Frames, exact digital rails only.
    const auto whole = analyze(data);
    assert(p == whole);
    const auto silence = analyze(std::vector<int32_t>(48000 * 2, 0), 2, 48000, 17);
    near(at<float>(silence, 48), -120);
    near(at<float>(silence, 56), -120);
    assert(std::isnan(at<float>(silence, 144)));
    assert(std::isnan(at<float>(silence, 124)));
    assert(std::isnan(at<float>(silence, 128)));
    assert(std::isnan(at<float>(silence, 132)));
    assert(at<uint32_t>(silence, 120) == 1);
    assert(at<uint64_t>(silence, 156) == 48000);
    assert(at<uint64_t>(silence, 164) == 48000);
    const auto tinySilence = analyze({1,1, -1,-1, 0,0});
    assert(std::isnan(at<float>(tinySilence, 144)));
    const auto empty = analyze({});
    assert(at<uint32_t>(empty, 120) == 0);
    assert(std::isnan(at<float>(empty, 48)));
    assert(std::isnan(at<float>(empty, 136)));
}
void loudnessAndTruePeak() {
    // 1 kHz sine with -20 dBFS peak: approximately -23 LUFS mono, -20 stereo.
    const auto mono = analyze(tone(48000, 48000 * 4, 0.1, 1), 1);
    near(at<float>(mono, 124), -23.0, 0.1);
    near(at<float>(mono, 128), 0.0, 0.1);
    assert(at<uint32_t>(mono, 120) == 15);
    const auto stereo = analyze(tone(48000, 48000 * 4, 0.1, 2), 2, 48000, 4093);
    near(at<float>(stereo, 124), -20.0, 0.1);
    near(at<float>(stereo, 132), -20.0, 0.05);
    const auto shortTone = analyze(tone(48000, 1000, 0.1, 1), 1);
    assert((at<uint32_t>(shortTone, 120) & 6) == 0);
    assert(std::isnan(at<float>(shortTone, 124)));
    assert(std::isnan(at<float>(shortTone, 128)));
    // Fs/4 phase-shifted sine: stored samples < 1, reconstructed peak > 1.
    auto overs = tone(48000, 48000, 1.1, 1, 1, 0, 12000, pi / 4);
    // Fade boundaries to isolate steady intersample peaks from discontinuities.
    for (size_t i = 0; i < overs.size(); ++i) {
        const double fade = std::min(1.0, std::min(i, overs.size() - 1 - i) / 480.0);
        overs[i] = static_cast<int32_t>(overs[i] * fade);
    }
    const auto intersample = analyze(overs, 1, 48000, 4093);
    near(at<float>(intersample, 48), -2.182445, 0.01);
    near(at<float>(intersample, 132), 0.827854, 0.4);
    assert(at<uint64_t>(intersample, 172) == 0);
    // Final delayed FIR output must match explicitly zero-padded input, while
    // padding must not invent raw silence or alter the file RMS denominator.
    const std::vector<int32_t> tail = {pcm(0.75), pcm(0.75), pcm(-0.75), pcm(-0.75)};
    const auto delayed = analyze(tail, 1);
    auto paddedTail = tail;
    paddedTail.resize(64, 0);
    const auto padded = analyze(paddedTail, 1);
    near(at<float>(delayed, 132), at<float>(padded, 132));
    assert(at<float>(delayed, 132) > at<float>(delayed, 48));
    assert(at<uint64_t>(delayed, 64) == 4);
    assert(at<uint64_t>(delayed, 156) == 0);
    near(at<float>(delayed, 56), -2.49877473);
    const auto unavailable = analyze({pcm(0.5)}, 1, 1);
    assert(at<int32_t>(unavailable, 8) == 1);
    assert(at<uint32_t>(unavailable, 120) == 1);
    assert(std::isnan(at<float>(unavailable, 132)));
    const auto high = analyze({pcm(0.5)}, 1, 2822401);
    assert(at<int32_t>(high, 8) == 2822401);
    assert(at<uint32_t>(high, 120) == 1);
}
void lraAndSampleRates() {
    auto levels = tone(48000, 48000 * 10, 0.1, 1);
    const auto lower = tone(48000, 48000 * 10, 0.0316227766017, 1);
    levels.insert(levels.end(), lower.begin(), lower.end());
    const auto range = analyze(levels, 1, 48000, 701);
    near(at<float>(range, 128), 10.0, 0.2);
    assert((at<uint32_t>(range, 120) & 4) != 0);
    const auto low = analyze(tone(4000, 16000, 0.1, 1), 1, 4000, 37);
    assert(at<int32_t>(low, 8) == 4000);
    assert(at<uint32_t>(low, 120) == 15);
    const auto tooLow = analyze({pcm(0.5)}, 1, 3363);
    assert(at<uint32_t>(tooLow, 120) == 1); // K shelf is above Nyquist.
    const auto high = analyze(tone(768000, 768000 * 3, 0.1, 2), 2, 768000, 65536);
    assert(at<int32_t>(high, 8) == 768000);
    assert(at<uint32_t>(high, 120) == 15);
    near(at<float>(high, 132), -20.0, 0.01);
    // Extremely high valid upstream rate: bounded 400 ms I ring, no 3 s LRA ring.
    const auto capped = analyze({pcm(0.5),pcm(0.5)}, 2, 2822400);
    assert(at<uint32_t>(capped, 120) == 9);
    assert(std::isnan(at<float>(capped, 128)));
}
} // namespace
int main(int argc, char** argv) {
    rawTailAndOverlap();
    dcStereoAndMono();
    silenceRailsAndByteBoundaries();
    loudnessAndTruePeak();
    lraAndSampleRates();
    // Optional fixture for verifying the host CLI against a known 4 s tone.
    if (argc == 2) {
        const auto data = tone(48000, 48000 * 4, 0.1, 2);
        std::ofstream fixture(argv[1], std::ios::binary);
        fixture.write(reinterpret_cast<const char*>(data.data()), data.size() * sizeof(int32_t));
        assert(fixture.good());
    }
    std::puts("PASS raw file signal metrics: tails, DC, stereo, mono, silence, rails, chunks, LUFS, LRA, true peak");
}
