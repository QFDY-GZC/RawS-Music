#include "ai_separation_engine.h"
#include "ai_separation_wav.h"

#include <android/log.h>

extern "C" {
#include "libavutil/tx.h"
}

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <fstream>
#include <limits>
#include <string>
#include <vector>

namespace {
constexpr double kPi = 3.1415926535897932384626433832795;
constexpr float kPcmScale = 1.0f / 2147483648.0f;
constexpr float kWindowEpsilon = 1.0e-8f;
constexpr const char* kLogTag = "AiSeparationNative";

int64_t elapsedMilliseconds(std::chrono::steady_clock::time_point started) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - started).count();
}

class RdftPair {
public:
    ~RdftPair() {
        av_tx_uninit(&forwardContext_);
        av_tx_uninit(&inverseContext_);
    }

    bool init(int size, std::string& error) {
        size_ = size;
        const float forwardScale = 1.0f;
        if (av_tx_init(
                &forwardContext_, &forward_, AV_TX_FLOAT_RDFT, 0, size, &forwardScale,
                AV_TX_UNALIGNED) < 0) {
            error = "Unable to initialize forward FFmpeg AVTX";
            return false;
        }
        const float inverseScale = 1.0f / static_cast<float>(size);
        if (av_tx_init(
                &inverseContext_, &inverse_, AV_TX_FLOAT_RDFT, 1, size, &inverseScale,
                AV_TX_UNALIGNED) < 0) {
            error = "Unable to initialize inverse FFmpeg AVTX";
            return false;
        }
        return true;
    }

    void forward(float* outputComplex, float* inputReal) const {
        forward_(forwardContext_, outputComplex, inputReal, sizeof(float));
    }

    void inverse(float* outputReal, AVComplexFloat* inputComplex) const {
        inverse_(inverseContext_, outputReal, inputComplex, sizeof(AVComplexFloat));
    }

private:
    int size_ = 0;
    AVTXContext* forwardContext_ = nullptr;
    AVTXContext* inverseContext_ = nullptr;
    av_tx_fn forward_ = nullptr;
    av_tx_fn inverse_ = nullptr;
};

int64_t tensorIndex(int channel, int frequency, int time, int frequencyBins, int timeFrames) {
    return (static_cast<int64_t>(channel) * frequencyBins + frequency) * timeFrames + time;
}

int reflectIndex(int index, int size) {
    if (size <= 1) return 0;
    while (index < 0 || index >= size) {
        if (index < 0) index = -index;
        if (index >= size) index = 2 * size - 2 - index;
    }
    return index;
}

float paddedSample(
    const std::vector<float>& stereo,
    int channel,
    int sampleIndex,
    int segmentSamples,
    int paddingMode) {
    if (sampleIndex >= 0 && sampleIndex < segmentSamples) {
        return stereo[static_cast<size_t>(sampleIndex) * 2u + static_cast<size_t>(channel)];
    }
    if (paddingMode == 1) {
        const int reflected = reflectIndex(sampleIndex, segmentSamples);
        return stereo[static_cast<size_t>(reflected) * 2u + static_cast<size_t>(channel)];
    }
    return 0.0f;
}

bool readPcmSegment(
    std::ifstream& input,
    int64_t startFrame,
    int segmentSamples,
    int64_t totalFrames,
    std::vector<float>& mixture,
    std::string& error) {
    mixture.assign(static_cast<size_t>(segmentSamples) * 2u, 0.0f);
    const int64_t readStart = std::max<int64_t>(0, startFrame);
    const int64_t readEnd = std::min<int64_t>(totalFrames, startFrame + segmentSamples);
    if (readEnd <= readStart) return true;
    const int64_t available = readEnd - readStart;
    const int64_t destinationOffset = readStart - startFrame;
    std::vector<int32_t> encoded(static_cast<size_t>(available) * 2u);
    input.clear();
    input.seekg(readStart * 8, std::ios::beg);
    if (!input.good()) {
        error = "Unable to seek decoded PCM";
        return false;
    }
    input.read(
        reinterpret_cast<char*>(encoded.data()),
        static_cast<std::streamsize>(encoded.size() * sizeof(int32_t)));
    if (input.gcount() != static_cast<std::streamsize>(encoded.size() * sizeof(int32_t))) {
        error = "Decoded PCM ended unexpectedly";
        return false;
    }
    const size_t outputBase = static_cast<size_t>(destinationOffset) * 2u;
    for (size_t i = 0; i < encoded.size(); ++i) {
        mixture[outputBase + i] = static_cast<float>(encoded[i]) * kPcmScale;
    }
    return true;
}

void buildWindow(int fftSize, std::vector<float>& window) {
    window.resize(static_cast<size_t>(fftSize));
    for (int i = 0; i < fftSize; ++i) {
        window[static_cast<size_t>(i)] = static_cast<float>(
            0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(i) / fftSize));
    }
}

bool computeStft(
    const std::vector<float>& mixture,
    const AiSeparationConfig& config,
    const RdftPair& rdft,
    const std::vector<float>& window,
    float* inputTensor,
    double& mean,
    double& standardDeviation,
    std::string& error) {
    const int complexBins = config.fftSize / 2 + 1;
    std::vector<float> frame(static_cast<size_t>(config.fftSize));
    std::vector<AVComplexFloat> spectrum(static_cast<size_t>(complexBins));
    const int centerPad = config.center ? config.fftSize / 2 : 0;

    for (int channel = 0; channel < 2; ++channel) {
        const int realChannel = channel == 0 ? 0 : 2;
        const int imaginaryChannel = realChannel + 1;
        for (int time = 0; time < config.timeFrames; ++time) {
            const int frameStart = time * config.hopLength - centerPad;
            for (int sample = 0; sample < config.fftSize; ++sample) {
                frame[static_cast<size_t>(sample)] =
                    paddedSample(
                        mixture, channel, frameStart + sample, config.segmentSamples,
                        config.paddingMode) *
                    window[static_cast<size_t>(sample)];
            }
            rdft.forward(reinterpret_cast<float*>(spectrum.data()), frame.data());
            for (int frequency = 0; frequency < config.frequencyBins; ++frequency) {
                const auto& value = spectrum[static_cast<size_t>(frequency)];
                inputTensor[tensorIndex(
                    realChannel, frequency, time, config.frequencyBins, config.timeFrames)] = value.re;
                inputTensor[tensorIndex(
                    imaginaryChannel, frequency, time, config.frequencyBins, config.timeFrames)] = value.im;
            }
        }
    }

    mean = 0.0;
    standardDeviation = 1.0;
    if (config.normalization == 1) {
        const int64_t elements =
            static_cast<int64_t>(4) * config.frequencyBins * config.timeFrames;
        long double sum = 0.0;
        long double squared = 0.0;
        for (int64_t i = 0; i < elements; ++i) {
            const long double value = inputTensor[i];
            sum += value;
            squared += value * value;
        }
        mean = static_cast<double>(sum / elements);
        const long double variance =
            std::max<long double>(0.0, squared / elements - mean * mean);
        standardDeviation = std::sqrt(static_cast<double>(variance) + 1.0e-8);
        if (!std::isfinite(mean) || !std::isfinite(standardDeviation) || standardDeviation <= 0.0) {
            error = "Invalid STFT normalization statistics";
            return false;
        }
        for (int64_t i = 0; i < elements; ++i) {
            inputTensor[i] = static_cast<float>((inputTensor[i] - mean) / standardDeviation);
        }
    }
    return true;
}

float originalInputValue(const float* input, int64_t index, int normalization, double mean, double stddev) {
    if (normalization == 1) return static_cast<float>(input[index] * stddev + mean);
    return input[index];
}

bool reconstructVocal(
    const AiSeparationConfig& config,
    const RdftPair& rdft,
    const std::vector<float>& window,
    const float* inputTensor,
    const float* outputTensor,
    double mean,
    double standardDeviation,
    std::vector<float>& vocal,
    std::string& error) {
    const int complexBins = config.fftSize / 2 + 1;
    const int synthesisLength = (config.timeFrames - 1) * config.hopLength + config.fftSize;
    const int cropStart = config.center ? config.fftSize / 2 : 0;
    if (cropStart + config.segmentSamples > synthesisLength) {
        error = "ISTFT coverage is shorter than segmentSamples";
        return false;
    }

    std::vector<float> windowSum(static_cast<size_t>(synthesisLength), 0.0f);
    for (int time = 0; time < config.timeFrames; ++time) {
        const int frameStart = time * config.hopLength;
        for (int sample = 0; sample < config.fftSize; ++sample) {
            const float value = window[static_cast<size_t>(sample)];
            windowSum[static_cast<size_t>(frameStart + sample)] += value * value;
        }
    }

    vocal.assign(static_cast<size_t>(config.segmentSamples) * 2u, 0.0f);
    std::vector<AVComplexFloat> halfSpectrum(static_cast<size_t>(complexBins));
    std::vector<float> inverseFrame(static_cast<size_t>(config.fftSize));
    std::vector<float> synthesis(static_cast<size_t>(synthesisLength));

    for (int channel = 0; channel < 2; ++channel) {
        std::fill(synthesis.begin(), synthesis.end(), 0.0f);
        const int realChannel = channel == 0 ? 0 : 2;
        const int imaginaryChannel = realChannel + 1;
        for (int time = 0; time < config.timeFrames; ++time) {
            std::fill(halfSpectrum.begin(), halfSpectrum.end(), AVComplexFloat{0.0f, 0.0f});
            for (int frequency = 0; frequency < config.frequencyBins; ++frequency) {
                const int64_t realIndex = tensorIndex(
                    realChannel, frequency, time, config.frequencyBins, config.timeFrames);
                const int64_t imaginaryIndex = tensorIndex(
                    imaginaryChannel, frequency, time, config.frequencyBins, config.timeFrames);
                float outputReal = outputTensor[realIndex];
                float outputImaginary = outputTensor[imaginaryIndex];
                if (!std::isfinite(outputReal) || !std::isfinite(outputImaginary)) {
                    error = "Model output contains NaN or Inf";
                    return false;
                }
                if (config.outputType == 0) {
                    if (config.normalization == 1) {
                        outputReal = static_cast<float>(outputReal * standardDeviation + mean);
                        outputImaginary = static_cast<float>(outputImaginary * standardDeviation + mean);
                    }
                } else {
                    const float inputReal = originalInputValue(
                        inputTensor, realIndex, config.normalization, mean, standardDeviation);
                    const float inputImaginary = originalInputValue(
                        inputTensor, imaginaryIndex, config.normalization, mean, standardDeviation);
                    const float maskReal = outputReal;
                    const float maskImaginary = outputImaginary;
                    outputReal = maskReal * inputReal - maskImaginary * inputImaginary;
                    outputImaginary = maskReal * inputImaginary + maskImaginary * inputReal;
                }
                halfSpectrum[static_cast<size_t>(frequency)] = {outputReal, outputImaginary};
            }
            rdft.inverse(inverseFrame.data(), halfSpectrum.data());
            const int frameStart = time * config.hopLength;
            for (int sample = 0; sample < config.fftSize; ++sample) {
                synthesis[static_cast<size_t>(frameStart + sample)] +=
                    inverseFrame[static_cast<size_t>(sample)] * window[static_cast<size_t>(sample)];
            }
        }
        for (int sample = 0; sample < config.segmentSamples; ++sample) {
            const int synthesisIndex = cropStart + sample;
            const float denominator = windowSum[static_cast<size_t>(synthesisIndex)];
            float value = denominator > kWindowEpsilon
                ? synthesis[static_cast<size_t>(synthesisIndex)] / denominator
                : 0.0f;
            if (!std::isfinite(value)) value = 0.0f;
            vocal[static_cast<size_t>(sample) * 2u + static_cast<size_t>(channel)] = value;
        }
    }
    return true;
}

bool writeRange(
    AiFloatWavWriter& vocals,
    AiFloatWavWriter& instrumental,
    const std::vector<float>& mixture,
    const std::vector<float>& vocal,
    int localStart,
    int localEnd,
    std::string& error) {
    for (int i = localStart; i < localEnd; ++i) {
        const size_t base = static_cast<size_t>(i) * 2u;
        const float vocalLeft = vocal[base];
        const float vocalRight = vocal[base + 1u];
        if (!vocals.writeStereo(vocalLeft, vocalRight, error)) return false;
        if (!instrumental.writeStereo(
                mixture[base] - vocalLeft,
                mixture[base + 1u] - vocalRight,
                error)) {
            return false;
        }
    }
    return true;
}

bool writeOverlap(
    AiFloatWavWriter& vocals,
    AiFloatWavWriter& instrumental,
    const std::vector<float>& currentMixture,
    const std::vector<float>& previousVocal,
    const std::vector<float>& currentVocal,
    int stride,
    int count,
    int overlapSamples,
    std::string& error) {
    for (int i = 0; i < count; ++i) {
        const double phase = (static_cast<double>(i) + 0.5) / std::max(1, overlapSamples);
        const float currentWeight = static_cast<float>(
            std::sin(phase * kPi * 0.5) * std::sin(phase * kPi * 0.5));
        const float previousWeight = 1.0f - currentWeight;
        const size_t previousBase = static_cast<size_t>(stride + i) * 2u;
        const size_t currentBase = static_cast<size_t>(i) * 2u;
        const float vocalLeft = previousVocal[previousBase] * previousWeight +
            currentVocal[currentBase] * currentWeight;
        const float vocalRight = previousVocal[previousBase + 1u] * previousWeight +
            currentVocal[currentBase + 1u] * currentWeight;
        if (!vocals.writeStereo(vocalLeft, vocalRight, error)) return false;
        if (!instrumental.writeStereo(
                currentMixture[currentBase] - vocalLeft,
                currentMixture[currentBase + 1u] - vocalRight,
                error)) {
            return false;
        }
    }
    return true;
}

bool runModelForSegment(
    const AiSeparationConfig& config,
    float* inputTensor,
    float* outputTensor,
    int64_t tensorFloats,
    const AiModelRunner& runModel,
    std::string& error) {
    if (!runModel(error)) {
        if (error.empty()) error = "ONNX Runtime inference failed";
        return false;
    }
    if (!config.denoise) return true;

    std::vector<float> positive(outputTensor, outputTensor + tensorFloats);
    for (int64_t i = 0; i < tensorFloats; ++i) inputTensor[i] = -inputTensor[i];
    if (!runModel(error)) {
        for (int64_t i = 0; i < tensorFloats; ++i) inputTensor[i] = -inputTensor[i];
        if (error.empty()) error = "ONNX Runtime denoise inference failed";
        return false;
    }
    for (int64_t i = 0; i < tensorFloats; ++i) {
        outputTensor[i] = 0.5f * positive[static_cast<size_t>(i)] - 0.5f * outputTensor[i];
        inputTensor[i] = -inputTensor[i];
    }
    return true;
}

void applyCompensation(std::vector<float>& vocal, double compensation) {
    const float scale = static_cast<float>(compensation);
    if (std::abs(scale - 1.0f) < 1.0e-7f) return;
    for (float& value : vocal) {
        value *= scale;
        if (!std::isfinite(value)) value = 0.0f;
    }
}

void buildChunkBlendWindow(int length, bool enabled, std::vector<float>& weights) {
    weights.resize(static_cast<size_t>(std::max(0, length)));
    if (!enabled || length <= 1) {
        std::fill(weights.begin(), weights.end(), 1.0f);
        return;
    }
    const double denominator = static_cast<double>(length - 1);
    for (int i = 0; i < length; ++i) {
        weights[static_cast<size_t>(i)] = static_cast<float>(
            0.5 - 0.5 * std::cos(2.0 * kPi * static_cast<double>(i) / denominator));
    }
}

bool writeResolvedSample(
    AiFloatWavWriter& vocals,
    AiFloatWavWriter& instrumental,
    float mixLeft,
    float mixRight,
    float vocalLeft,
    float vocalRight,
    std::string& error) {
    if (!vocals.writeStereo(vocalLeft, vocalRight, error)) return false;
    return instrumental.writeStereo(
        mixLeft - vocalLeft,
        mixRight - vocalRight,
        error);
}

bool writeSingleChunkRange(
    AiFloatWavWriter& vocals,
    AiFloatWavWriter& instrumental,
    int64_t globalStart,
    int64_t globalEnd,
    int64_t chunkStart,
    const std::vector<float>& mixture,
    const std::vector<float>& vocal,
    std::string& error) {
    for (int64_t global = globalStart; global < globalEnd; ++global) {
        const int64_t local = global - chunkStart;
        if (local < 0 || local >= static_cast<int64_t>(vocal.size() / 2u)) {
            error = "UVR pending chunk range is invalid";
            return false;
        }
        const size_t base = static_cast<size_t>(local) * 2u;
        if (!writeResolvedSample(
                vocals, instrumental,
                mixture[base], mixture[base + 1u],
                vocal[base], vocal[base + 1u],
                error)) {
            return false;
        }
    }
    return true;
}

bool writeWeightedChunkOverlap(
    AiFloatWavWriter& vocals,
    AiFloatWavWriter& instrumental,
    int64_t globalStart,
    int64_t globalEnd,
    int64_t previousStart,
    int64_t currentStart,
    const std::vector<float>& currentMixture,
    const std::vector<float>& previousVocal,
    const std::vector<float>& currentVocal,
    const std::vector<float>& previousWeights,
    const std::vector<float>& currentWeights,
    std::string& error) {
    for (int64_t global = globalStart; global < globalEnd; ++global) {
        const int64_t previousLocal = global - previousStart;
        const int64_t currentLocal = global - currentStart;
        if (previousLocal < 0 || currentLocal < 0 ||
            previousLocal >= static_cast<int64_t>(previousWeights.size()) ||
            currentLocal >= static_cast<int64_t>(currentWeights.size())) {
            error = "UVR overlap geometry is invalid";
            return false;
        }
        const float previousWeight = previousWeights[static_cast<size_t>(previousLocal)];
        const float currentWeight = currentWeights[static_cast<size_t>(currentLocal)];
        const float denominator = previousWeight + currentWeight;
        const size_t previousBase = static_cast<size_t>(previousLocal) * 2u;
        const size_t currentBase = static_cast<size_t>(currentLocal) * 2u;
        float vocalLeft = 0.0f;
        float vocalRight = 0.0f;
        if (denominator > kWindowEpsilon) {
            vocalLeft = (
                previousVocal[previousBase] * previousWeight +
                currentVocal[currentBase] * currentWeight) / denominator;
            vocalRight = (
                previousVocal[previousBase + 1u] * previousWeight +
                currentVocal[currentBase + 1u] * currentWeight) / denominator;
        } else {
            // This can only occur at two coincident Hann endpoints. Averaging is finite and
            // prevents a one-sample hole without biasing either chunk.
            vocalLeft = 0.5f * (previousVocal[previousBase] + currentVocal[currentBase]);
            vocalRight = 0.5f * (previousVocal[previousBase + 1u] + currentVocal[currentBase + 1u]);
        }
        if (!writeResolvedSample(
                vocals, instrumental,
                currentMixture[currentBase], currentMixture[currentBase + 1u],
                vocalLeft, vocalRight,
                error)) {
            return false;
        }
    }
    return true;
}

bool runUvrMdxSeparation(
    std::ifstream& input,
    int64_t totalFrames,
    AiFloatWavWriter& vocalsWriter,
    AiFloatWavWriter& instrumentalWriter,
    const AiSeparationConfig& config,
    const RdftPair& rdft,
    const std::vector<float>& window,
    float* inputTensor,
    float* outputTensor,
    int64_t expectedFloats,
    const AiModelRunner& runModel,
    const AiCancelCheck& isCancelled,
    const AiProgressCallback& onProgress,
    int& segmentCount,
    std::string& error) {
    const int trim = config.edgeTrimSamples;
    const int chunkSize = config.segmentSamples;
    const int genSize = chunkSize - 2 * trim;
    if (trim <= 0 || genSize <= 0) {
        error = "UVR MDX center trim geometry is invalid";
        return false;
    }

    // Mirrors UVR's demix geometry: a leading trim, enough trailing zeros for a complete final
    // useful region, and chunk starts based on the full model input size. The full-song result and
    // divider arrays used by desktop UVR are streamed here with only two chunks resident.
    const int64_t remainder = totalFrames % genSize;
    const int64_t trailingPad = static_cast<int64_t>(genSize) + trim - remainder;
    const int64_t paddedFrames = trim + totalFrames + trailingPad;
    const bool useBlendWindow = config.overlap > 0.0;
    const int stride = useBlendWindow
        ? std::max(1, static_cast<int>(std::floor(chunkSize * (1.0 - config.overlap))))
        : chunkSize;
    if (stride > chunkSize) {
        error = "UVR chunk stride exceeds chunk size";
        return false;
    }
    segmentCount = static_cast<int>((paddedFrames + stride - 1) / stride);

    const int64_t desiredStart = trim;
    const int64_t desiredEnd = trim + totalFrames;
    int64_t writeCursor = desiredStart;

    std::vector<float> currentMixture;
    std::vector<float> currentVocal;
    std::vector<float> currentWeights;
    std::vector<float> previousMixture;
    std::vector<float> previousVocal;
    std::vector<float> previousWeights;
    int64_t previousStart = 0;
    int previousActual = 0;
    bool havePrevious = false;
    int64_t stftElapsedMs = 0;
    int64_t inferenceElapsedMs = 0;
    int64_t istftElapsedMs = 0;
    int64_t outputElapsedMs = 0;

    for (int segmentIndex = 0; segmentIndex < segmentCount; ++segmentIndex) {
        if (isCancelled()) {
            error = "CANCELLED";
            return false;
        }
        const int64_t currentStart = static_cast<int64_t>(segmentIndex) * stride;
        if (currentStart >= paddedFrames) break;
        const int currentActual = static_cast<int>(
            std::min<int64_t>(chunkSize, paddedFrames - currentStart));
        const int64_t sourceStart = currentStart - trim;
        if (!readPcmSegment(
                input, sourceStart, chunkSize, totalFrames, currentMixture, error)) {
            return false;
        }
        double mean = 0.0;
        double standardDeviation = 1.0;
        auto phaseStarted = std::chrono::steady_clock::now();
        if (!computeStft(
                currentMixture, config, rdft, window, inputTensor,
                mean, standardDeviation, error)) {
            return false;
        }
        stftElapsedMs += elapsedMilliseconds(phaseStarted);
        phaseStarted = std::chrono::steady_clock::now();
        if (!runModelForSegment(
                config, inputTensor, outputTensor, expectedFloats, runModel, error)) {
            return false;
        }
        inferenceElapsedMs += elapsedMilliseconds(phaseStarted);
        if (isCancelled()) {
            error = "CANCELLED";
            return false;
        }
        phaseStarted = std::chrono::steady_clock::now();
        if (!reconstructVocal(
                config, rdft, window, inputTensor, outputTensor,
                mean, standardDeviation, currentVocal, error)) {
            return false;
        }
        istftElapsedMs += elapsedMilliseconds(phaseStarted);
        phaseStarted = std::chrono::steady_clock::now();
        applyCompensation(currentVocal, config.compensation);
        buildChunkBlendWindow(currentActual, useBlendWindow, currentWeights);

        if (havePrevious) {
            const int64_t previousEnd = previousStart + previousActual;
            const int64_t currentEnd = currentStart + currentActual;
            if (currentStart > previousEnd) {
                error = "UVR chunks contain an uncovered gap";
                return false;
            }

            const int64_t singleEnd = std::min({currentStart, previousEnd, desiredEnd});
            const int64_t singleStart = std::max(writeCursor, desiredStart);
            if (singleEnd > singleStart && !writeSingleChunkRange(
                    vocalsWriter, instrumentalWriter,
                    singleStart, singleEnd, previousStart,
                    previousMixture, previousVocal, error)) {
                return false;
            }
            writeCursor = std::max(writeCursor, singleEnd);

            const int64_t overlapStart = std::max({currentStart, writeCursor, desiredStart});
            const int64_t overlapEnd = std::min({previousEnd, currentEnd, desiredEnd});
            if (overlapEnd > overlapStart && !writeWeightedChunkOverlap(
                    vocalsWriter, instrumentalWriter,
                    overlapStart, overlapEnd,
                    previousStart, currentStart,
                    currentMixture,
                    previousVocal, currentVocal,
                    previousWeights, currentWeights,
                    error)) {
                return false;
            }
            writeCursor = std::max(writeCursor, overlapEnd);
        }
        outputElapsedMs += elapsedMilliseconds(phaseStarted);

        previousStart = currentStart;
        previousActual = currentActual;
        previousMixture.swap(currentMixture);
        previousVocal.swap(currentVocal);
        previousWeights.swap(currentWeights);
        havePrevious = true;

        const int64_t processed = std::min<int64_t>(totalFrames, std::max<int64_t>(0, writeCursor - trim));
        if (!vocalsWriter.flushProgress(error) || !instrumentalWriter.flushProgress(error)) {
            return false;
        }
        onProgress(processed, totalFrames, segmentIndex + 1, segmentCount);
        if (segmentIndex == 0 || (segmentIndex + 1) % 8 == 0) {
            __android_log_print(
                ANDROID_LOG_INFO,
                kLogTag,
                "AI_PERF_NATIVE_PROGRESS segment=%d/%d stft_ms=%lld infer_ms=%lld "
                "istft_ms=%lld output_ms=%lld",
                segmentIndex + 1,
                segmentCount,
                static_cast<long long>(stftElapsedMs),
                static_cast<long long>(inferenceElapsedMs),
                static_cast<long long>(istftElapsedMs),
                static_cast<long long>(outputElapsedMs));
        }
    }

    if (!havePrevious) {
        error = "UVR produced no chunks";
        return false;
    }
    const int64_t previousEnd = previousStart + previousActual;
    const int64_t finalStart = std::max(writeCursor, desiredStart);
    const int64_t finalEnd = std::min(previousEnd, desiredEnd);
    if (finalEnd > finalStart && !writeSingleChunkRange(
            vocalsWriter, instrumentalWriter,
            finalStart, finalEnd, previousStart,
            previousMixture, previousVocal, error)) {
        return false;
    }
    writeCursor = std::max(writeCursor, finalEnd);
    if (writeCursor != desiredEnd) {
        error = "UVR output coverage does not match source length";
        return false;
    }
    onProgress(totalFrames, totalFrames, segmentCount, segmentCount);
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "AI_PERF_NATIVE frames=%lld segments=%d stft_ms=%lld infer_ms=%lld "
        "istft_ms=%lld output_ms=%lld",
        static_cast<long long>(totalFrames),
        segmentCount,
        static_cast<long long>(stftElapsedMs),
        static_cast<long long>(inferenceElapsedMs),
        static_cast<long long>(istftElapsedMs),
        static_cast<long long>(outputElapsedMs));
    return true;
}

}  // namespace

bool runAiSeparationSegment(
    const float* mixtureStereo,
    int mixtureFrames,
    float* vocalStereo,
    int vocalCapacityFrames,
    const AiSeparationConfig& config,
    float* inputTensor,
    int64_t inputFloats,
    float* outputTensor,
    int64_t outputFloats,
    const AiModelRunner& runModel,
    std::string& error) {
    if (mixtureStereo == nullptr || vocalStereo == nullptr ||
        mixtureFrames != config.segmentSamples ||
        vocalCapacityFrames < config.segmentSamples) {
        error = "Realtime segment buffers do not match the model segment";
        return false;
    }
    if (config.sampleRate <= 0 || config.segmentSamples <= 0 || config.fftSize <= 0 ||
        config.hopLength <= 0 || config.frequencyBins <= 0 || config.timeFrames <= 0) {
        error = "Invalid realtime separation configuration";
        return false;
    }
    const int64_t expectedFloats =
        static_cast<int64_t>(4) * config.frequencyBins * config.timeFrames;
    if (inputTensor == nullptr || outputTensor == nullptr ||
        inputFloats < expectedFloats || outputFloats < expectedFloats) {
        error = "Realtime tensor buffers are too small";
        return false;
    }

    RdftPair rdft;
    if (!rdft.init(config.fftSize, error)) return false;
    std::vector<float> window;
    buildWindow(config.fftSize, window);
    std::vector<float> mixture(
        mixtureStereo,
        mixtureStereo + static_cast<size_t>(mixtureFrames) * 2u);
    double mean = 0.0;
    double standardDeviation = 1.0;
    if (!computeStft(
            mixture, config, rdft, window, inputTensor,
            mean, standardDeviation, error)) {
        return false;
    }
    if (!runModelForSegment(
            config, inputTensor, outputTensor, expectedFloats, runModel, error)) {
        return false;
    }
    std::vector<float> vocal;
    if (!reconstructVocal(
            config, rdft, window, inputTensor, outputTensor,
            mean, standardDeviation, vocal, error)) {
        return false;
    }
    applyCompensation(vocal, config.compensation);
    std::copy(vocal.begin(), vocal.end(), vocalStereo);
    return true;
}

bool runAiSeparation(
    const std::string& pcmPath,
    const std::string& vocalsPath,
    const std::string& instrumentalPath,
    const AiSeparationConfig& config,
    float* inputTensor,
    int64_t inputFloats,
    float* outputTensor,
    int64_t outputFloats,
    const AiModelRunner& runModel,
    const AiCancelCheck& isCancelled,
    const AiProgressCallback& onProgress,
    AiSeparationStats& stats,
    std::string& error) {
    const auto started = std::chrono::steady_clock::now();
    if (config.sampleRate <= 0 || config.segmentSamples <= 0 || config.fftSize <= 0 ||
        config.hopLength <= 0 || config.frequencyBins <= 0 || config.timeFrames <= 0) {
        error = "Invalid separation configuration";
        return false;
    }
    const int64_t expectedFloats =
        static_cast<int64_t>(4) * config.frequencyBins * config.timeFrames;
    if (inputTensor == nullptr || outputTensor == nullptr ||
        inputFloats < expectedFloats || outputFloats < expectedFloats) {
        error = "Direct tensor buffers are too small";
        return false;
    }
    if (config.overlap < 0.0 || config.overlap > 0.5) {
        error = "Overlap must be between 0 and 0.5";
        return false;
    }
    if (config.compensation < 0.1 || config.compensation > 4.0) {
        error = "Compensation is outside the supported range";
        return false;
    }
    if (config.denoise && config.outputType != 0) {
        error = "Denoise shift trick requires complex spectrum output";
        return false;
    }

    std::ifstream input(pcmPath, std::ios::binary | std::ios::ate);
    if (!input.is_open()) {
        error = "Unable to open decoded PCM";
        return false;
    }
    const std::streamoff fileBytes = input.tellg();
    if (fileBytes <= 0 || fileBytes % 8 != 0) {
        error = "Decoded PCM size is invalid";
        return false;
    }
    const int64_t totalFrames = static_cast<int64_t>(fileBytes / 8);
    const uint64_t wavBytes = static_cast<uint64_t>(totalFrames) * 8u + 44u;
    if (wavBytes > std::numeric_limits<uint32_t>::max()) {
        error = "Output exceeds 32-bit RIFF WAV limit";
        return false;
    }
    input.seekg(0, std::ios::beg);

    const int overlapSamples = static_cast<int>(
        std::llround(config.segmentSamples * config.overlap));
    const int stride = config.segmentSamples - overlapSamples;
    if (stride <= 0) {
        error = "Segment stride is invalid";
        return false;
    }
    const int segmentCount = totalFrames <= config.segmentSamples
        ? 1
        : 1 + static_cast<int>((totalFrames - config.segmentSamples + stride - 1) / stride);

    RdftPair rdft;
    if (!rdft.init(config.fftSize, error)) return false;
    std::vector<float> window;
    buildWindow(config.fftSize, window);

    AiFloatWavWriter vocalsWriter;
    AiFloatWavWriter instrumentalWriter;
    if (!vocalsWriter.open(vocalsPath, config.sampleRate, error) ||
        !instrumentalWriter.open(instrumentalPath, config.sampleRate, error)) {
        return false;
    }

    if (config.chunkMode == 1) {
        int uvrSegmentCount = 0;
        if (!runUvrMdxSeparation(
                input, totalFrames, vocalsWriter, instrumentalWriter, config, rdft, window,
                inputTensor, outputTensor, expectedFloats, runModel, isCancelled, onProgress,
                uvrSegmentCount, error)) {
            return false;
        }
        if (vocalsWriter.framesWritten() != totalFrames ||
            instrumentalWriter.framesWritten() != totalFrames) {
            error = "Output frame count mismatch";
            return false;
        }
        if (!vocalsWriter.finalize(error) || !instrumentalWriter.finalize(error)) return false;
        const auto ended = std::chrono::steady_clock::now();
        stats.totalFrames = totalFrames;
        stats.processedSegments = uvrSegmentCount;
        stats.elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(ended - started).count();
        return true;
    }
    if (config.chunkMode != 0) {
        error = "Unknown chunk mode";
        return false;
    }

    std::vector<float> mixture;
    std::vector<float> vocal;
    std::vector<float> previousVocal;
    std::vector<float> lastMixture;
    bool havePrevious = false;

    for (int segmentIndex = 0; segmentIndex < segmentCount; ++segmentIndex) {
        if (isCancelled()) {
            error = "CANCELLED";
            return false;
        }
        const int64_t startFrame = static_cast<int64_t>(segmentIndex) * stride;
        if (!readPcmSegment(
                input, startFrame, config.segmentSamples, totalFrames, mixture, error)) {
            return false;
        }
        double mean = 0.0;
        double standardDeviation = 1.0;
        if (!computeStft(
                mixture, config, rdft, window, inputTensor, mean, standardDeviation, error)) {
            return false;
        }
        if (!runModelForSegment(
                config, inputTensor, outputTensor, expectedFloats, runModel, error)) {
            return false;
        }
        if (isCancelled()) {
            error = "CANCELLED";
            return false;
        }
        if (!reconstructVocal(
                config, rdft, window, inputTensor, outputTensor, mean,
                standardDeviation, vocal, error)) {
            return false;
        }
        applyCompensation(vocal, config.compensation);

        const int available = static_cast<int>(
            std::min<int64_t>(config.segmentSamples, totalFrames - startFrame));
        if (!havePrevious) {
            const int firstEnd = std::min(stride, available);
            if (!writeRange(
                    vocalsWriter, instrumentalWriter, mixture, vocal, 0, firstEnd, error)) {
                return false;
            }
            havePrevious = true;
        } else {
            const int overlapCount = std::min(overlapSamples, available);
            if (!writeOverlap(
                    vocalsWriter, instrumentalWriter, mixture, previousVocal, vocal,
                    stride, overlapCount, overlapSamples, error)) {
                return false;
            }
            const int middleEnd = std::min(stride, available);
            if (middleEnd > overlapCount && !writeRange(
                    vocalsWriter, instrumentalWriter, mixture, vocal,
                    overlapCount, middleEnd, error)) {
                return false;
            }
        }
        previousVocal = vocal;
        lastMixture = mixture;

        const int64_t processed = std::min<int64_t>(totalFrames, startFrame + config.segmentSamples);
        if (!vocalsWriter.flushProgress(error) || !instrumentalWriter.flushProgress(error)) {
            return false;
        }
        onProgress(processed, totalFrames, segmentIndex + 1, segmentCount);
    }

    const int64_t lastStart = static_cast<int64_t>(segmentCount - 1) * stride;
    const int finalAvailable = static_cast<int>(
        std::min<int64_t>(config.segmentSamples, totalFrames - lastStart));
    if (finalAvailable > stride && !writeRange(
            vocalsWriter, instrumentalWriter, lastMixture, previousVocal,
            stride, finalAvailable, error)) {
        return false;
    }

    if (vocalsWriter.framesWritten() != totalFrames ||
        instrumentalWriter.framesWritten() != totalFrames) {
        error = "Output frame count mismatch";
        return false;
    }
    if (!vocalsWriter.finalize(error) || !instrumentalWriter.finalize(error)) return false;

    const auto ended = std::chrono::steady_clock::now();
    stats.totalFrames = totalFrames;
    stats.processedSegments = segmentCount;
    stats.elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(ended - started).count();
    return true;
}
