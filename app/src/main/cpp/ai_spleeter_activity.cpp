#include "ai_spleeter_activity.h"

#include <android/log.h>

extern "C" {
#include "libavutil/tx.h"
}

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <limits>
#include <string>
#include <vector>

namespace {
constexpr int kFftSize = 4096;
constexpr int kHopLength = 1024;
constexpr int kFrequencyBins = 1024;
constexpr int kTimeFrames = 512;
constexpr int kChannels = 2;
constexpr float kPcmScale = 1.0f / 2147483648.0f;
constexpr double kPi = 3.1415926535897932384626433832795;
constexpr const char* kLogTag = "AiSpleeterActivity";

class Rdft {
public:
    ~Rdft() { av_tx_uninit(&context_); }

    bool init(std::string& error) {
        const float scale = 1.0f;
        if (av_tx_init(
                &context_, &transform_, AV_TX_FLOAT_RDFT, 0, kFftSize, &scale,
                AV_TX_UNALIGNED) < 0) {
            error = "无法初始化 Spleeter STFT";
            return false;
        }
        return true;
    }

    void forward(float* output, float* input) const {
        transform_(context_, output, input, sizeof(float));
    }

private:
    AVTXContext* context_ = nullptr;
    av_tx_fn transform_ = nullptr;
};

int64_t frameCountForFile(const std::string& path) {
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input) return 0;
    const auto bytes = input.tellg();
    if (bytes <= 0) return 0;
    return static_cast<int64_t>(bytes) / static_cast<int64_t>(sizeof(int32_t) * kChannels);
}

bool readSamples(
    std::ifstream& input,
    int64_t startFrame,
    int64_t totalFrames,
    int64_t sampleCount,
    std::vector<float>& destination,
    std::string& error) {
    destination.assign(static_cast<size_t>(sampleCount) * kChannels, 0.0f);
    const int64_t available = std::min(sampleCount, std::max<int64_t>(0, totalFrames - startFrame));
    if (available <= 0) return true;
    if (startFrame < 0) {
        error = "Spleeter PCM 起点无效";
        return false;
    }
    std::vector<int32_t> encoded(static_cast<size_t>(available) * kChannels);
    input.clear();
    input.seekg(startFrame * sizeof(int32_t) * kChannels, std::ios::beg);
    if (!input.good()) {
        error = "无法定位 Spleeter PCM";
        return false;
    }
    input.read(
        reinterpret_cast<char*>(encoded.data()),
        static_cast<std::streamsize>(encoded.size() * sizeof(int32_t)));
    if (input.gcount() != static_cast<std::streamsize>(encoded.size() * sizeof(int32_t))) {
        error = "Spleeter PCM 读取不完整";
        return false;
    }
    for (size_t index = 0; index < encoded.size(); ++index) {
        destination[index] = static_cast<float>(encoded[index]) * kPcmScale;
    }
    return true;
}

float sampleAt(
    const std::vector<float>& samples,
    int channel,
    int64_t index,
    int64_t sampleCount) {
    if (index < 0 || index >= sampleCount) return 0.0f;
    return samples[static_cast<size_t>(index) * kChannels + static_cast<size_t>(channel)];
}

void appendValue(std::string& output, float value) {
    if (!output.empty()) output.push_back(',');
    output += std::to_string(value);
}
}  // namespace

bool runSpleeterVocalActivity(
    const std::string& pcmPath,
    int sampleRate,
    float* inputTensor,
    int64_t inputFloats,
    float* outputTensor,
    int64_t outputFloats,
    const SpleeterModelRunner& runModel,
    const SpleeterProgressCallback& reportProgress,
    std::string& result,
    std::string& error) {
    if (sampleRate <= 0 || inputTensor == nullptr || outputTensor == nullptr || !runModel) {
        error = "Spleeter 活动分析参数无效";
        return false;
    }
    const int64_t tensorFloats =
        static_cast<int64_t>(kChannels) * kTimeFrames * kFrequencyBins;
    if (inputFloats < tensorFloats || outputFloats < tensorFloats) {
        error = "Spleeter 张量缓冲区过小";
        return false;
    }
    const int64_t totalFrames = frameCountForFile(pcmPath);
    if (totalFrames <= 0) {
        error = "Spleeter PCM 为空";
        return false;
    }
    const int64_t samplesPerSplit =
        static_cast<int64_t>(kFftSize) + static_cast<int64_t>(kTimeFrames - 1) * kHopLength;
    const int64_t framesPerSplit = static_cast<int64_t>(kTimeFrames);
    const int64_t splitCount =
        (totalFrames + framesPerSplit * kHopLength - 1) / (framesPerSplit * kHopLength);

    Rdft rdft;
    if (!rdft.init(error)) return false;
    std::vector<float> window(kFftSize);
    for (int index = 0; index < kFftSize; ++index) {
        window[static_cast<size_t>(index)] = static_cast<float>(
            0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(index) / kFftSize));
    }
    std::vector<float> samples;
    std::vector<float> frame(kFftSize);
    std::vector<float> spectrum(static_cast<size_t>(kFftSize));
    std::ifstream input(pcmPath, std::ios::binary);
    if (!input) {
        error = "无法打开 Spleeter PCM";
        return false;
    }

    result = "OK|" + std::to_string(sampleRate) + "|" +
        std::to_string(kHopLength) + "|" +
        std::to_string(totalFrames * 1000LL / sampleRate) + "|";
    result.reserve(result.size() + static_cast<size_t>(totalFrames / kHopLength) * 10u);

    for (int64_t split = 0; split < splitCount; ++split) {
        if (reportProgress && !reportProgress(split, splitCount)) {
            error = "Spleeter activity analysis cancelled";
            return false;
        }
        const int64_t splitStart = split * framesPerSplit * kHopLength;
        if (!readSamples(input, splitStart, totalFrames, samplesPerSplit, samples, error)) {
            return false;
        }
        std::fill(inputTensor, inputTensor + tensorFloats, 0.0f);
        for (int channel = 0; channel < kChannels; ++channel) {
            for (int time = 0; time < kTimeFrames; ++time) {
                const int64_t frameStart = static_cast<int64_t>(time) * kHopLength;
                for (int sample = 0; sample < kFftSize; ++sample) {
                    frame[static_cast<size_t>(sample)] = sampleAt(
                        samples, channel, frameStart + sample, samplesPerSplit) *
                        window[static_cast<size_t>(sample)];
                }
                rdft.forward(spectrum.data(), frame.data());
                for (int frequency = 0; frequency < kFrequencyBins; ++frequency) {
                    const size_t index =
                        (static_cast<size_t>(channel) * kTimeFrames + static_cast<size_t>(time)) *
                        kFrequencyBins + static_cast<size_t>(frequency);
                    // AVTX's RDFT stores DC and Nyquist as two real values at the
                    // beginning; the remaining bins use interleaved real/imaginary
                    // pairs. Do not treat the Nyquist value as DC's imaginary part.
                    const float real = spectrum[static_cast<size_t>(frequency == 0 ? 0 : frequency * 2)];
                    const float imaginary = frequency == 0
                        ? 0.0f
                        : spectrum[static_cast<size_t>(frequency * 2 + 1)];
                    inputTensor[index] = std::sqrt(real * real + imaginary * imaginary);
                }
            }
        }
        std::string modelError;
        if (!runModel(modelError)) {
            error = modelError.empty() ? "Spleeter ONNX 推理失败" : modelError;
            return false;
        }
        const int64_t remainingFrames = std::max<int64_t>(0, totalFrames - splitStart);
        const int validFrames = static_cast<int>(std::min<int64_t>(kTimeFrames,
            (remainingFrames + kHopLength - 1) / kHopLength));
        for (int time = 0; time < validFrames; ++time) {
            long double energy = 0.0;
            for (int channel = 0; channel < kChannels; ++channel) {
                for (int frequency = 0; frequency < kFrequencyBins; ++frequency) {
                    const size_t index =
                        (static_cast<size_t>(channel) * kTimeFrames + static_cast<size_t>(time)) *
                        kFrequencyBins + static_cast<size_t>(frequency);
                    const float value = outputTensor[index];
                    if (!std::isfinite(value)) {
                        error = "Spleeter 输出包含 NaN 或 Inf";
                        return false;
                    }
                    energy += static_cast<long double>(value) * value;
                }
            }
            appendValue(result, static_cast<float>(std::sqrt(energy / (kChannels * kFrequencyBins))));
        }
        __android_log_print(
            ANDROID_LOG_DEBUG, kLogTag, "activity split=%lld/%lld",
            static_cast<long long>(split + 1), static_cast<long long>(splitCount));
        if (reportProgress && !reportProgress(split + 1, splitCount)) {
            error = "Spleeter activity analysis cancelled";
            return false;
        }
    }
    return true;
}
