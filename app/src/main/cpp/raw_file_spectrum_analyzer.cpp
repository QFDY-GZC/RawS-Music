#include "raw_file_spectrum_analyzer.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstring>
#include <limits>

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kMinDb = -120.0f;
constexpr uint32_t kMagic = 0x52534131; // RSA1
constexpr uint32_t kVersion = 2;

template <typename T>
void appendValue(std::vector<uint8_t>& output, const T& value) {
    const auto* bytes = reinterpret_cast<const uint8_t*>(&value);
    output.insert(output.end(), bytes, bytes + sizeof(T));
}

float toDb(float amplitude) {
    if (!std::isfinite(amplitude) || amplitude <= 1.0e-8f) return kMinDb;
    return std::max(kMinDb, std::min(0.0f, 20.0f * std::log10(amplitude)));
}

float interpolate(const std::vector<float>& values, float position) {
    if (values.empty()) return 0.0f;
    if (values.size() == 1) return values.front();
    const float bounded = std::max(0.0f, std::min(position, static_cast<float>(values.size() - 1)));
    const size_t low = static_cast<size_t>(bounded);
    const size_t high = std::min(low + 1, values.size() - 1);
    const float fraction = bounded - static_cast<float>(low);
    return values[low] + (values[high] - values[low]) * fraction;
}

} // namespace

struct RawFileSpectrumAnalyzer {
    const int sampleRate;
    const int channelCount;
    const int64_t durationMs;
    const int fftSize;
    const int halfSize;
    const int hopSize;
    const int waterfallBins = 768;
    const int waterfallFrames;
    const float nyquist;

    std::vector<float> window;
    float windowSum = 0.0f;
    std::vector<float> fifoLeft;
    std::vector<float> fifoRight;
    size_t fifoRead = 0;
    std::vector<std::complex<float>> fftBuffer;
    std::vector<float> leftPower;
    std::vector<float> rightPower;
    std::vector<float> averagePower;
    std::vector<float> leftLevels;
    std::vector<float> rightLevels;
    std::vector<uint8_t> leftWaterfall;
    std::vector<uint8_t> rightWaterfall;
    int64_t processedSamples = 0;
    int frameCount = 0;
    float currentLeftDb = kMinDb;
    float currentRightDb = kMinDb;
    float leftPeakDb = kMinDb;
    float rightPeakDb = kMinDb;
    float leftRmsPower = 0.0f;
    float rightRmsPower = 0.0f;

    RawFileSpectrumAnalyzer(int sr, int channels, int64_t duration, int fft)
        : sampleRate(std::max(sr, 8000)),
          channelCount(std::max(channels, 1)),
          durationMs(std::max<int64_t>(duration, 1)),
          fftSize(std::max(1024, fft)),
          halfSize(fftSize / 2),
          hopSize(sampleRate > 192000 ? fftSize : fftSize / 2),
          waterfallFrames(std::max(180, std::min(1200, static_cast<int>(
              std::max<int64_t>(180, (durationMs + 499) / 500))))),
          nyquist(static_cast<float>(std::max(sr, 8000)) * 0.5f),
          window(static_cast<size_t>(std::max(1024, fft))),
          fftBuffer(static_cast<size_t>(std::max(1024, fft))),
          leftPower(static_cast<size_t>(std::max(1024, fft) / 2 + 1)),
          rightPower(static_cast<size_t>(std::max(1024, fft) / 2 + 1)),
          averagePower(static_cast<size_t>(std::max(1024, fft) / 2 + 1), 0.0f),
          leftLevels(static_cast<size_t>(waterfallFrames), kMinDb),
          rightLevels(static_cast<size_t>(waterfallFrames), kMinDb),
          leftWaterfall(static_cast<size_t>(waterfallFrames) * waterfallBins, 0),
          rightWaterfall(static_cast<size_t>(waterfallFrames) * waterfallBins, 0) {
        for (int i = 0; i < fftSize; ++i) {
            window[static_cast<size_t>(i)] =
                0.5f - 0.5f * std::cos(2.0f * kPi * static_cast<float>(i) /
                                        static_cast<float>(fftSize - 1));
            windowSum += window[static_cast<size_t>(i)];
        }
        fifoLeft.reserve(static_cast<size_t>(fftSize * 2));
        fifoRight.reserve(static_cast<size_t>(fftSize * 2));
    }

    void fftOne(const std::vector<float>& samples, std::vector<float>& power) {
        for (int i = 0; i < fftSize; ++i) {
            fftBuffer[static_cast<size_t>(i)] = std::complex<float>(
                samples[static_cast<size_t>(i)] * window[static_cast<size_t>(i)], 0.0f);
        }

        for (int i = 1, j = 0; i < fftSize; ++i) {
            int bit = fftSize >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) std::swap(fftBuffer[static_cast<size_t>(i)], fftBuffer[static_cast<size_t>(j)]);
        }

        for (int length = 2; length <= fftSize; length <<= 1) {
            const float angle = -2.0f * kPi / static_cast<float>(length);
            const std::complex<float> step(std::cos(angle), std::sin(angle));
            for (int start = 0; start < fftSize; start += length) {
                std::complex<float> factor(1.0f, 0.0f);
                const int half = length >> 1;
                for (int i = 0; i < half; ++i) {
                    const auto even = fftBuffer[static_cast<size_t>(start + i)];
                    const auto odd = factor * fftBuffer[static_cast<size_t>(start + i + half)];
                    fftBuffer[static_cast<size_t>(start + i)] = even + odd;
                    fftBuffer[static_cast<size_t>(start + i + half)] = even - odd;
                    factor *= step;
                }
            }
        }

        std::fill(power.begin(), power.end(), 0.0f);
        const float normalization = std::max(windowSum, 1.0f);
        for (int i = 0; i <= halfSize; ++i) {
            const float magnitude = std::abs(fftBuffer[static_cast<size_t>(i)]) /
                normalization * ((i == 0 || i == halfSize) ? 1.0f : 2.0f);
            power[static_cast<size_t>(i)] = magnitude * magnitude;
        }
    }

    float samplePower(const std::vector<float>& samples) const {
        double sum = 0.0;
        for (float sample : samples) sum += static_cast<double>(sample) * sample;
        return static_cast<float>(sum / static_cast<double>(std::max(1, fftSize)));
    }

    float frameDb(const std::vector<float>& power) const {
        double sum = 0.0;
        for (float value : power) sum += value;
        return toDb(std::sqrt(static_cast<float>(sum / static_cast<double>(power.size()))));
    }

    float powerAt(const std::vector<float>& power, float bin) const {
        return interpolate(power, bin);
    }

    void writeWaterfallFrame(int bucket) {
        if (bucket < 0 || bucket >= waterfallFrames) return;
        const size_t offset = static_cast<size_t>(bucket) * waterfallBins;
        for (int bin = 0; bin < waterfallBins; ++bin) {
            const float fftBin = static_cast<float>(bin) * static_cast<float>(halfSize) /
                static_cast<float>(std::max(1, waterfallBins - 1));
            const float leftDb = toDb(std::sqrt(std::max(0.0f, powerAt(leftPower, fftBin))));
            const float rightDb = toDb(std::sqrt(std::max(0.0f, powerAt(rightPower, fftBin))));
            const auto encode = [](float db) {
                const float normalized = std::max(0.0f, std::min(1.0f, (db - kMinDb) / -kMinDb));
                return static_cast<uint8_t>(std::round(normalized * 255.0f));
            };
            leftWaterfall[offset + static_cast<size_t>(bin)] = encode(leftDb);
            rightWaterfall[offset + static_cast<size_t>(bin)] = encode(rightDb);
        }
        leftLevels[static_cast<size_t>(bucket)] = currentLeftDb;
        rightLevels[static_cast<size_t>(bucket)] = currentRightDb;
    }

    void processFrame(const std::vector<float>& left, const std::vector<float>& right) {
        const float leftPowerValue = samplePower(left);
        const float rightPowerValue = samplePower(right);
        leftRmsPower += leftPowerValue;
        rightRmsPower += rightPowerValue;
        const float leftPeak = *std::max_element(left.begin(), left.end(),
            [](float a, float b) { return std::abs(a) < std::abs(b); });
        const float rightPeak = *std::max_element(right.begin(), right.end(),
            [](float a, float b) { return std::abs(a) < std::abs(b); });
        leftPeakDb = std::max(leftPeakDb, toDb(std::abs(leftPeak)));
        rightPeakDb = std::max(rightPeakDb, toDb(std::abs(rightPeak)));

        fftOne(left, leftPower);
        const std::vector<float> leftFramePower = leftPower;
        fftOne(right, rightPower);
        currentLeftDb = toDb(std::sqrt(leftPowerValue));
        currentRightDb = toDb(std::sqrt(rightPowerValue));
        for (int i = 0; i <= halfSize; ++i) {
            averagePower[static_cast<size_t>(i)] +=
                (leftFramePower[static_cast<size_t>(i)] + rightPower[static_cast<size_t>(i)]) * 0.5f;
        }

        const int64_t centerSample = processedSamples + fftSize / 2;
        const int bucket = durationMs > 0
            ? std::min(waterfallFrames - 1, std::max(0, static_cast<int>(
                centerSample * static_cast<int64_t>(waterfallFrames) /
                std::max<int64_t>(1, durationMs * sampleRate / 1000))))
            : frameCount % waterfallFrames;
        writeWaterfallFrame(bucket);
        ++frameCount;
        processedSamples += hopSize;
    }

    void process(const uint8_t* data, int byteCount) {
        if (!data || byteCount < 8) return;
        const int bytesPerFrame = std::max(1, channelCount) * 4;
        const int frameCountInChunk = byteCount / bytesPerFrame;
        for (int frame = 0; frame < frameCountInChunk; ++frame) {
            const uint8_t* source = data + static_cast<size_t>(frame) * bytesPerFrame;
            int32_t leftRaw = 0;
            int32_t rightRaw = 0;
            std::memcpy(&leftRaw, source, sizeof(leftRaw));
            if (channelCount > 1) {
                std::memcpy(&rightRaw, source + 4, sizeof(rightRaw));
            } else {
                rightRaw = leftRaw;
            }
            fifoLeft.push_back(static_cast<float>(leftRaw) / 2147483648.0f);
            fifoRight.push_back(static_cast<float>(rightRaw) / 2147483648.0f);
        }

        while (fifoLeft.size() - fifoRead >= static_cast<size_t>(fftSize)) {
            std::vector<float> left(fifoLeft.begin() + static_cast<long>(fifoRead),
                                    fifoLeft.begin() + static_cast<long>(fifoRead + fftSize));
            std::vector<float> right(fifoRight.begin() + static_cast<long>(fifoRead),
                                     fifoRight.begin() + static_cast<long>(fifoRead + fftSize));
            processFrame(left, right);
            fifoRead += static_cast<size_t>(hopSize);
        }
        if (fifoRead > static_cast<size_t>(fftSize * 2)) {
            fifoLeft.erase(fifoLeft.begin(), fifoLeft.begin() + static_cast<long>(fifoRead));
            fifoRight.erase(fifoRight.begin(), fifoRight.begin() + static_cast<long>(fifoRead));
            fifoRead = 0;
        }
    }

    std::vector<uint8_t> finish() const {
        std::vector<uint8_t> output;
        output.reserve(64 + static_cast<size_t>(halfSize) * 4 +
                       static_cast<size_t>(waterfallFrames) * (waterfallBins * 2 + 8));
        const uint32_t magic = kMagic;
        const uint32_t version = kVersion;
        const int32_t sr = sampleRate;
        const int32_t channels = channelCount;
        const int32_t fft = fftSize;
        const int32_t averageBins = 1024;
        const int32_t wfBins = waterfallBins;
        const int32_t wfFrames = waterfallFrames;
        appendValue(output, magic);
        appendValue(output, version);
        appendValue(output, sr);
        appendValue(output, channels);
        appendValue(output, fft);
        appendValue(output, averageBins);
        appendValue(output, wfBins);
        appendValue(output, wfFrames);
        appendValue(output, durationMs);

        std::vector<float> averageDb(static_cast<size_t>(averageBins), kMinDb);
        const float count = static_cast<float>(std::max(1, frameCount));
        std::vector<float> meanPower(averagePower.size(), 0.0f);
        for (size_t i = 0; i < averagePower.size(); ++i) meanPower[i] = averagePower[i] / count;
        for (int bin = 0; bin < averageBins; ++bin) {
            const float fftBin = static_cast<float>(bin) * static_cast<float>(halfSize) /
                static_cast<float>(std::max(1, averageBins - 1));
            averageDb[static_cast<size_t>(bin)] =
                toDb(std::sqrt(std::max(0.0f, interpolate(meanPower, fftBin))));
        }

        const int noiseStart = std::max(1, halfSize * 9 / 10);
        double noisePower = 0.0;
        int noiseCount = 0;
        for (int i = noiseStart; i <= halfSize; ++i) {
            noisePower += meanPower[static_cast<size_t>(i)];
            ++noiseCount;
        }
        const float noiseDb = toDb(std::sqrt(static_cast<float>(noisePower /
            static_cast<double>(std::max(1, noiseCount)))));
        const float threshold = std::max(-100.0f, noiseDb + 6.0f);
        int lastAbove = 0;
        int run = 0;
        for (int i = halfSize; i >= 1; --i) {
            const float db = toDb(std::sqrt(std::max(0.0f, meanPower[static_cast<size_t>(i)])));
            if (db > threshold) {
                lastAbove = i;
                if (++run >= 6) break;
            } else {
                run = 0;
            }
        }
        const float cutoffHz = lastAbove > 0
            ? static_cast<float>(lastAbove) * nyquist / static_cast<float>(halfSize)
            : 0.0f;
        const float confidence = lastAbove > 0
            ? std::max(0.0f, std::min(1.0f, 1.0f - noiseDb / -120.0f))
            : 0.0f;
        const float leftRmsDb = toDb(std::sqrt(leftRmsPower / count));
        const float rightRmsDb = toDb(std::sqrt(rightRmsPower / count));
        appendValue(output, cutoffHz);
        appendValue(output, confidence);
        appendValue(output, leftPeakDb);
        appendValue(output, rightPeakDb);
        appendValue(output, leftRmsDb);
        appendValue(output, rightRmsDb);
        for (float value : averageDb) appendValue(output, value);
        for (float value : leftLevels) appendValue(output, value);
        for (float value : rightLevels) appendValue(output, value);
        output.insert(output.end(), leftWaterfall.begin(), leftWaterfall.end());
        output.insert(output.end(), rightWaterfall.begin(), rightWaterfall.end());
        return output;
    }
};

RawFileSpectrumAnalyzer* raw_file_spectrum_create(
    int sample_rate,
    int channels,
    int64_t duration_ms,
    int fft_size
) {
    if (sample_rate <= 0 || fft_size < 1024) return nullptr;
    return new RawFileSpectrumAnalyzer(sample_rate, channels, duration_ms, fft_size);
}

void raw_file_spectrum_release(RawFileSpectrumAnalyzer* analyzer) {
    delete analyzer;
}

void raw_file_spectrum_process_s32le(
    RawFileSpectrumAnalyzer* analyzer,
    const uint8_t* data,
    int byte_count
) {
    if (analyzer) analyzer->process(data, byte_count);
}

std::vector<uint8_t> raw_file_spectrum_finish(RawFileSpectrumAnalyzer* analyzer) {
    return analyzer ? analyzer->finish() : std::vector<uint8_t>();
}

void raw_file_spectrum_current_levels(
    const RawFileSpectrumAnalyzer* analyzer,
    float* left_db,
    float* right_db
) {
    if (!analyzer) return;
    if (left_db) *left_db = analyzer->currentLeftDb;
    if (right_db) *right_db = analyzer->currentRightDb;
}
