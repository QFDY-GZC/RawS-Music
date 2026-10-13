#include "../../main/cpp/raw_file_spectrum_analyzer.h"

#include <array>
#include <charconv>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <memory>
#include <string>

namespace {
template <typename T> bool parse(const char* text, T& value) {
    const char* end = text + std::strlen(text);
    const auto parsed = std::from_chars(text, end, value);
    return parsed.ec == std::errc{} && parsed.ptr == end;
}
template <typename T> T field(const std::vector<uint8_t>& payload, size_t offset) {
    T value{};
    std::memcpy(&value, payload.data() + offset, sizeof(value));
    return value;
}
template <typename T> void print(const char* name, const std::vector<uint8_t>& payload, size_t offset) {
    std::cout << name << '=' << field<T>(payload, offset) << '\n';
}
} // namespace

int main(int argc, char** argv) {
    if (argc != 5) {
        std::cerr << "Usage: raw_file_spectrum_fixture_cli path.s32le sampleRate channels durationMs\n";
        return 2;
    }
    int rate = 0, channels = 0;
    int64_t duration = 0;
    if (!parse(argv[2], rate) || rate <= 0 || !parse(argv[3], channels) ||
        channels < 1 || channels > 64 || !parse(argv[4], duration) || duration < 0) {
        std::cerr << "Invalid positive sample rate, channel count (1..64), or duration (>=0).\n";
        return 2;
    }
    std::ifstream input(argv[1], std::ios::binary);
    if (!input) {
        std::cerr << "Cannot open input PCM file.\n";
        return 2;
    }
    std::unique_ptr<RawFileSpectrumAnalyzer, decltype(&raw_file_spectrum_release)> analyzer(
        raw_file_spectrum_create(rate, channels, duration, 32768), raw_file_spectrum_release);
    if (!analyzer) return 2;
    std::array<uint8_t, 65536> buffer{};
    uint64_t totalBytes = 0;
    while (input) {
        input.read(reinterpret_cast<char*>(buffer.data()), buffer.size());
        const auto bytes = input.gcount();
        if (bytes > 0) {
            raw_file_spectrum_process_s32le(analyzer.get(), buffer.data(), static_cast<int>(bytes));
            totalBytes += static_cast<uint64_t>(bytes);
        }
    }
    if (input.bad() || totalBytes % (static_cast<uint64_t>(channels) * 4) != 0) {
        std::cerr << "Read failure or incomplete S32LE frame at EOF.\n";
        return 2;
    }
    const auto payload = raw_file_spectrum_finish(analyzer.get());
    if (payload.size() < 180) return 2;
    std::cout << std::setprecision(9);
    print<uint32_t>("version", payload, 4);
    print<int32_t>("sampleRate", payload, 8);
    print<int32_t>("channels", payload, 12);
    print<int64_t>("durationMs", payload, 32);
    std::cout << "payloadBytes=" << payload.size() << '\n';
    print<float>("leftPeakDb", payload, 48);
    print<float>("rightPeakDb", payload, 52);
    print<float>("leftRmsDb", payload, 56);
    print<float>("rightRmsDb", payload, 60);
    print<uint64_t>("decodedFrameCount", payload, 64);
    print<uint64_t>("nearRailFrameCount", payload, 72);
    print<uint64_t>("sustainedRailFrameCount", payload, 80);
    print<uint64_t>("sustainedRailRunCount", payload, 88);
    print<uint64_t>("nonzeroSampleCount", payload, 96);
    print<uint64_t>("aligned16SampleCount", payload, 104);
    print<uint64_t>("aligned24SampleCount", payload, 112);
    print<uint32_t>("qualityFlags", payload, 120);
    print<float>("integratedLufs", payload, 124);
    print<float>("loudnessRangeLu", payload, 128);
    print<float>("truePeakDb", payload, 132);
    print<float>("leftDcOffset", payload, 136);
    print<float>("rightDcOffset", payload, 140);
    print<float>("stereoCorrelation", payload, 144);
    print<float>("midRmsDb", payload, 148);
    print<float>("sideRmsDb", payload, 152);
    print<uint64_t>("silentFrameCount", payload, 156);
    print<uint64_t>("longestSilentFrameCount", payload, 164);
    print<uint64_t>("hardRailFrameCount", payload, 172);
}
