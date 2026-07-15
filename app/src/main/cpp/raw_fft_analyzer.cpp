#include "raw_fft_analyzer.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <vector>

namespace {
constexpr int kFftSize = 256;
constexpr float kPi = 3.14159265358979323846f;

void fft(std::vector<std::complex<float>>& values) {
    const int count = static_cast<int>(values.size());
    for (int i = 1, j = 0; i < count; ++i) {
        int bit = count >> 1;
        for (; (j & bit) != 0; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(values[i], values[j]);
    }
    for (int length = 2; length <= count; length <<= 1) {
        const float angle = -2.0f * kPi / static_cast<float>(length);
        const std::complex<float> root(std::cos(angle), std::sin(angle));
        for (int base = 0; base < count; base += length) {
            std::complex<float> factor(1.0f, 0.0f);
            const int half = length >> 1;
            for (int index = 0; index < half; ++index) {
                const auto even = values[base + index];
                const auto odd = values[base + index + half] * factor;
                values[base + index] = even + odd;
                values[base + index + half] = even - odd;
                factor *= root;
            }
        }
    }
}
} // namespace

float rawsmusic_fft_weighted_energy(const float* samples, int sample_count) {
    if (!samples || sample_count <= 0) return 0.0f;

    std::vector<std::complex<float>> spectrum(kFftSize);
    float square_sum = 0.0f;
    for (int i = 0; i < kFftSize; ++i) {
        const int source = std::min(sample_count - 1, i * sample_count / kFftSize);
        const float value = std::isfinite(samples[source]) ? samples[source] : 0.0f;
        square_sum += value * value;
        const float window = 0.5f - 0.5f * std::cos(2.0f * kPi * i / (kFftSize - 1));
        spectrum[i] = std::complex<float>(value * window, 0.0f);
    }
    const float rms = std::sqrt(square_sum / kFftSize);
    if (rms <= 0.00001f) return 0.0f;

    fft(spectrum);
    float weighted = 0.0f;
    float total = 0.0f;
    for (int bin = 2; bin < kFftSize / 2; ++bin) {
        const float magnitude = std::norm(spectrum[bin]);
        const float frequencyWeight = 0.35f + 0.65f * bin / static_cast<float>(kFftSize / 2);
        weighted += magnitude * frequencyWeight;
        total += magnitude;
    }
    const float spectralShape = total > 0.00001f ? std::sqrt(weighted / total) : 0.0f;
    return std::max(0.0f, std::min(rms * spectralShape, 1.0f));
}
