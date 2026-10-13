#include "raw_file_spectrum_analyzer.h"
#include "raw_file_quality_metrics.h"

#include <algorithm>
#include <cmath>
#include <complex>
#include <cstring>
#include <limits>

namespace {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kMinDb = -120.0f;
constexpr uint32_t kMagic = 0x52534131; // RSA1
constexpr uint32_t kVersion = 5;
constexpr int32_t kRailThreshold = 0x7fff0000; // 16-bit PCM full-scale after S32 conversion.
constexpr uint64_t kMinimumSustainedRailFrames = 8;

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
    RawFileQualityMetrics quality;
    std::vector<uint8_t> partialFrame;
    size_t partialFrameBytes = 0;

    std::vector<float> window;
    float windowSum = 0.0f;
    std::vector<float> fifoLeft;
    std::vector<float> fifoRight;
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
    uint64_t decodedFrameCount = 0;
    uint64_t nearRailFrameCount = 0;
    uint64_t sustainedRailFrameCount = 0;
    uint64_t sustainedRailRunCount = 0;
    uint64_t currentRailRun = 0;
    uint64_t nonzeroSampleCount = 0;
    uint64_t aligned16SampleCount = 0;
    uint64_t aligned24SampleCount = 0;

    void flushRailRun() {
        if (currentRailRun >= kMinimumSustainedRailFrames) {
            sustainedRailFrameCount += currentRailRun;
            ++sustainedRailRunCount;
        }
        currentRailRun = 0;
    }

    RawFileSpectrumAnalyzer(int sr, int channels, int64_t duration, int fft)
        : sampleRate(sr),
          channelCount(std::max(channels, 1)),
          durationMs(std::max<int64_t>(duration, 1)),
          fftSize(std::max(1024, fft)),
          halfSize(fftSize / 2),
          hopSize(sampleRate > 192000 ? fftSize : fftSize / 2),
          waterfallFrames(std::max(180, std::min(1200, static_cast<int>(
              std::max<int64_t>(180, (durationMs + 499) / 500))))),
          nyquist(static_cast<float>(sr) * 0.5f),
          quality(sr, channelCount),
          partialFrame(static_cast<size_t>(channelCount) * 4),
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
        fifoLeft.reserve(static_cast<size_t>(fftSize));
        fifoRight.reserve(static_cast<size_t>(fftSize));
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
        fftOne(left, leftPower);
        fftOne(right, rightPower);
        currentLeftDb = toDb(std::sqrt(leftPowerValue));
        currentRightDb = toDb(std::sqrt(rightPowerValue));
        for (int i = 0; i <= halfSize; ++i) {
            averagePower[static_cast<size_t>(i)] +=
                (leftPower[static_cast<size_t>(i)] + rightPower[static_cast<size_t>(i)]) * 0.5f;
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

    void processRawFrame(const uint8_t* source) {
        int32_t leftRaw = 0;
        int32_t rightRaw = 0;
        std::memcpy(&leftRaw, source, sizeof(leftRaw));
        if (channelCount > 1) {
            std::memcpy(&rightRaw, source + 4, sizeof(rightRaw));
        } else {
            rightRaw = leftRaw;
        }
        const auto atRail = [](int32_t sample) {
            return sample >= kRailThreshold || sample <= -kRailThreshold;
        };
        const auto atHardRail = [](int32_t sample) {
            return sample == INT32_MAX || sample == INT32_MIN;
        };
        const bool rail = atRail(leftRaw) || (channelCount > 1 && atRail(rightRaw));
        bool hardRail = atHardRail(leftRaw) || (channelCount > 1 && atHardRail(rightRaw));
        for (int channel = 2; channel < channelCount; ++channel) {
            int32_t raw = 0;
            std::memcpy(&raw, source + static_cast<size_t>(channel) * 4, sizeof(raw));
            hardRail = hardRail || atHardRail(raw);
        }
        quality.addFrame(leftRaw, rightRaw, hardRail);
        const auto countPrecision = [this](int32_t sample) {
            if (sample == 0) return;
            ++nonzeroSampleCount;
            if ((static_cast<uint32_t>(sample) & 0xffffu) == 0u) ++aligned16SampleCount;
            if ((static_cast<uint32_t>(sample) & 0xffu) == 0u) ++aligned24SampleCount;
        };
        countPrecision(leftRaw);
        if (channelCount > 1) countPrecision(rightRaw);
        ++decodedFrameCount;
        if (rail) {
            ++nearRailFrameCount;
            ++currentRailRun;
        } else {
            flushRailRun();
        }
        fifoLeft.push_back(static_cast<float>(leftRaw) / 2147483648.0f);
        fifoRight.push_back(static_cast<float>(rightRaw) / 2147483648.0f);
        if (fifoLeft.size() == static_cast<size_t>(fftSize)) {
            processFrame(fifoLeft, fifoRight);
            const size_t remaining = static_cast<size_t>(fftSize - hopSize);
            if (remaining != 0) {
                std::memmove(fifoLeft.data(), fifoLeft.data() + hopSize, remaining * sizeof(float));
                std::memmove(fifoRight.data(), fifoRight.data() + hopSize, remaining * sizeof(float));
            }
            fifoLeft.resize(remaining);
            fifoRight.resize(remaining);
        }
    }

    void process(const uint8_t* data, int byteCount) {
        if (!data || byteCount <= 0 || quality.finished()) return;
        const size_t bytesPerFrame = partialFrame.size();
        size_t remaining = static_cast<size_t>(byteCount);
        if (partialFrameBytes != 0) {
            const size_t take = std::min(remaining, bytesPerFrame - partialFrameBytes);
            std::memcpy(partialFrame.data() + partialFrameBytes, data, take);
            partialFrameBytes += take;
            data += take;
            remaining -= take;
            if (partialFrameBytes == bytesPerFrame) {
                processRawFrame(partialFrame.data());
                partialFrameBytes = 0;
            }
        }
        while (remaining >= bytesPerFrame) {
            processRawFrame(data);
            data += bytesPerFrame;
            remaining -= bytesPerFrame;
        }
        if (remaining != 0) {
            std::memcpy(partialFrame.data(), data, remaining);
            partialFrameBytes = remaining;
        }
    }

    std::vector<uint8_t> finish() {
        const auto& metrics = quality.finish();
        std::vector<uint8_t> output;
        output.reserve(180 + static_cast<size_t>(1024) * 4 +
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
        appendValue(output, cutoffHz);
        appendValue(output, confidence);
        appendValue(output, metrics.leftPeakDb);
        appendValue(output, metrics.rightPeakDb);
        appendValue(output, metrics.leftRmsDb);
        appendValue(output, metrics.rightRmsDb);
        appendValue(output, decodedFrameCount);
        appendValue(output, nearRailFrameCount);
        appendValue(output, sustainedRailFrameCount +
            (currentRailRun >= kMinimumSustainedRailFrames ? currentRailRun : 0));
        appendValue(output, sustainedRailRunCount +
            (currentRailRun >= kMinimumSustainedRailFrames ? 1u : 0u));
        appendValue(output, nonzeroSampleCount);
        appendValue(output, aligned16SampleCount);
        appendValue(output, aligned24SampleCount);
        // Explicit serialization: uint64 counters intentionally start unaligned
        // at byte 156. Never serialize a padded C++ struct as the JNI wire format.
        appendValue(output, metrics.flags);                 // 120
        appendValue(output, metrics.integratedLufs);        // 124
        appendValue(output, metrics.loudnessRangeLu);       // 128
        appendValue(output, metrics.truePeakDb);            // 132
        appendValue(output, metrics.leftDcOffset);          // 136
        appendValue(output, metrics.rightDcOffset);         // 140
        appendValue(output, metrics.stereoCorrelation);     // 144
        appendValue(output, metrics.midRmsDb);              // 148
        appendValue(output, metrics.sideRmsDb);             // 152
        appendValue(output, metrics.silentFrameCount);      // 156
        appendValue(output, metrics.longestSilentFrameCount); // 164
        appendValue(output, metrics.hardRailFrameCount);     // 172; arrays at 180
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
