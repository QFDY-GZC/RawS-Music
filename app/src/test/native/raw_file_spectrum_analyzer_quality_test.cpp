#include "../../main/cpp/raw_file_spectrum_analyzer.h"

#include <cassert>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {
template <typename T>
T readAt(const std::vector<uint8_t>& bytes, size_t offset) {
    T value{};
    assert(offset + sizeof(value) <= bytes.size());
    std::memcpy(&value, bytes.data() + offset, sizeof(value));
    return value;
}

void runWithSlices(const std::vector<int32_t>& samples, size_t cut,
                   uint64_t expectedRail, uint64_t expectedSustained,
                   uint64_t expectedRuns, uint64_t expectedAligned) {
    auto* analyzer = raw_file_spectrum_create(8000, 1, 1000, 1024);
    assert(analyzer);
    const auto* data = reinterpret_cast<const uint8_t*>(samples.data());
    raw_file_spectrum_process_s32le(analyzer, data, static_cast<int>(cut * sizeof(int32_t)));
    raw_file_spectrum_process_s32le(analyzer, data + cut * sizeof(int32_t),
        static_cast<int>((samples.size() - cut) * sizeof(int32_t)));
    const auto payload = raw_file_spectrum_finish(analyzer);
    raw_file_spectrum_release(analyzer);
    assert(readAt<uint32_t>(payload, 4) == 5); // Raw metrics extend quantization evidence.
    assert(readAt<uint64_t>(payload, 64) == samples.size());
    assert(readAt<uint64_t>(payload, 72) == expectedRail);
    assert(readAt<uint64_t>(payload, 80) == expectedSustained);
    assert(readAt<uint64_t>(payload, 88) == expectedRuns);
    const uint64_t nonzero = readAt<uint64_t>(payload, 96);
    const uint64_t aligned16 = readAt<uint64_t>(payload, 104);
    const uint64_t aligned24 = readAt<uint64_t>(payload, 112);
    assert(nonzero == samples.size());
    assert(aligned16 == expectedAligned);
    assert(aligned24 == expectedAligned);
}
} // namespace

int main() {
    std::vector<int32_t> samples(4096, 0x20000000);
    for (int i = 500; i < 516; ++i) samples[i] = INT32_MAX;
    for (int i = 1000; i < 1048; ++i) samples[i] = INT32_MIN;
    runWithSlices(samples, 508, 64, 64, 2, 4080); // One run crosses the chunk boundary.

    std::vector<int32_t> isolated(4096, 0x40000000);
    for (int i = 0; i < 32; ++i) isolated[i * 64] = INT32_MAX;
    runWithSlices(isolated, 2048, 32, 0, 0, 4064); // Loud isolated peaks are not sustained clipping.
}
