#include "stereo_spectrum_analyzer.h"

#include <jni.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <mutex>

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kMinimumFrequency = 25.0f;
constexpr float kMaximumFrequency = 20000.0f;
constexpr float kAnalysisLowPassHz = 18500.0f;
constexpr float kNoiseFloorDb = -66.0f;
constexpr float kSpectrumCeilingDb = -8.0f;
constexpr float kRisePerSecond = 20.0f;
constexpr float kFallPerSecond = 4.5f;
constexpr float kSilenceRms = 0.00030f;
constexpr float kBreathHz = 0.18f;
constexpr float kEnergyFloorDb = -42.0f;
constexpr float kEnergyCeilingDb = -10.0f;
constexpr float kEnergyRisePerSecond = 18.0f;
constexpr float kEnergyFallPerSecond = 4.5f;

constexpr int kPcmS16Le = 1;
constexpr int kPcmS24PackedLe = 2;
constexpr int kPcmS32Le = 3;
constexpr int kPcmFloat32Le = 4;

double monotonicSeconds() {
    return std::chrono::duration<double>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

int bytesPerSampleForEncoding(int encoding) {
    switch (encoding) {
        case kPcmS16Le: return 2;
        case kPcmS24PackedLe: return 3;
        case kPcmS32Le:
        case kPcmFloat32Le: return 4;
        default: return 0;
    }
}

MonoSpectrumAnalyzer gAnalyzer;
std::mutex gAnalyzerMutex;
}

MonoSpectrumAnalyzer::MonoSpectrumAnalyzer()
    : m_fftBuffer(kFftSize) {
    for (int i = 0; i < kFftSize; ++i) {
        m_hann[i] = 0.5f - 0.5f * std::cos(
            2.0f * kPi * static_cast<float>(i) / static_cast<float>(kFftSize - 1)
        );
    }
}

bool MonoSpectrumAnalyzer::analyzePcm(
    const std::uint8_t* data,
    int byteCount,
    int channels,
    int sourceSampleRate,
    int sampleEncoding,
    int validBitsPerSample,
    float* output,
    int outputCount
) {
    const int bytesPerSample = bytesPerSampleForEncoding(sampleEncoding);
    if (!data || byteCount <= 0 || channels <= 0 || sourceSampleRate <= 0 ||
        bytesPerSample <= 0 || output == nullptr || outputCount < kBandCount) {
        return false;
    }

    const int frameBytes = bytesPerSample * channels;
    const int frameCount = byteCount / frameBytes;
    if (frameCount <= 0) return false;

    if (sourceSampleRate != m_sourceSampleRate) {
        m_sourceSampleRate = sourceSampleRate;
        m_resampleAccumulator = 0;
        m_lowPassMono = 0.0f;
    }

    double squareSum = 0.0;
    for (int frame = 0; frame < frameCount; ++frame) {
        const std::uint8_t* base = data + frame * frameBytes;
        double mixed = 0.0;
        for (int channel = 0; channel < channels; ++channel) {
            mixed += decodeSample(
                base + channel * bytesPerSample,
                sampleEncoding,
                validBitsPerSample
            );
        }
        const float mono = static_cast<float>(mixed / static_cast<double>(channels));
        appendSourceFrame(mono, sourceSampleRate);
        squareSum += static_cast<double>(mono) * mono;
    }

    const float rms = std::sqrt(
        static_cast<float>(squareSum / static_cast<double>(frameCount))
    );
    m_lastInputRms = rms;

    // A partially filled Hann window produces severe spectral leakage and unstable peaks.
    // Keep the first activation visually quiet until one complete FFT window is available.
    if (m_validFrames < kFftSize) {
        m_smoothed.fill(0.0f);
        m_lastTargets.fill(0.0f);
        m_lastPcmSeconds = monotonicSeconds();
        copyOutput(output, outputCount);
        return true;
    }

    std::array<float, kBandCount> targets{};
    transform(targets);
    m_lastTargets = targets;
    m_lastPcmSeconds = monotonicSeconds();
    updateSmoothedBands(targets, rms, nextDeltaSeconds(), true);
    copyOutput(output, outputCount);
    return true;
}

bool MonoSpectrumAnalyzer::advanceIdle(bool paused, float* output, int outputCount) {
    if (output == nullptr || outputCount < kBandCount) return false;
    std::array<float, kBandCount> zeros{};
    const bool holdLatestTarget = !paused && m_lastPcmSeconds > 0.0 &&
        (monotonicSeconds() - m_lastPcmSeconds) < 0.090;
    updateSmoothedBands(
        holdLatestTarget ? m_lastTargets : zeros,
        holdLatestTarget ? m_lastInputRms : 0.0f,
        nextDeltaSeconds(),
        paused
    );
    copyOutput(output, outputCount);
    return true;
}

void MonoSpectrumAnalyzer::reset() {
    m_ring.fill(0.0f);
    m_smoothed.fill(0.0f);
    m_lastTargets.fill(0.0f);
    m_writeIndex = 0;
    m_validFrames = 0;
    m_sourceSampleRate = 0;
    m_resampleAccumulator = 0;
    m_lowPassMono = 0.0f;
    m_lastAnalyzeSeconds = 0.0;
    m_lastPcmSeconds = 0.0;
    m_breathPhase = 0.0f;
    m_energyEnvelope = 0.0f;
    m_lastInputRms = 0.0f;
}

float MonoSpectrumAnalyzer::decodeSample(
    const std::uint8_t* sample,
    int sampleEncoding,
    int validBitsPerSample
) const {
    switch (sampleEncoding) {
        case kPcmS16Le: {
            const std::int16_t value = static_cast<std::int16_t>(
                static_cast<std::uint16_t>(sample[0]) |
                (static_cast<std::uint16_t>(sample[1]) << 8)
            );
            return static_cast<float>(value) / 32768.0f;
        }
        case kPcmS24PackedLe: {
            std::int32_t value = static_cast<std::int32_t>(sample[0]) |
                (static_cast<std::int32_t>(sample[1]) << 8) |
                (static_cast<std::int32_t>(sample[2]) << 16);
            if ((value & 0x00800000) != 0) {
                value |= static_cast<std::int32_t>(0xFF000000);
            }
            return static_cast<float>(value) / 8388608.0f;
        }
        case kPcmFloat32Le: {
            float value = 0.0f;
            std::memcpy(&value, sample, sizeof(float));
            return std::isfinite(value) ? std::clamp(value, -1.25f, 1.25f) : 0.0f;
        }
        case kPcmS32Le: {
            const std::int32_t value = static_cast<std::int32_t>(
                static_cast<std::uint32_t>(sample[0]) |
                (static_cast<std::uint32_t>(sample[1]) << 8) |
                (static_cast<std::uint32_t>(sample[2]) << 16) |
                (static_cast<std::uint32_t>(sample[3]) << 24)
            );
            // The playback/DSP path uses a signed S32LE container for all >16-bit PCM.
            (void) validBitsPerSample;
            return static_cast<float>(value) / 2147483648.0f;
        }
        default:
            return 0.0f;
    }
}

void MonoSpectrumAnalyzer::appendSourceFrame(float mono, int sourceSampleRate) {
    const float safeMono = std::isfinite(mono) ? mono : 0.0f;

    // Dedicated anti-alias branch. Playback samples remain untouched.
    const float alpha = 1.0f - std::exp(
        -2.0f * kPi * std::min(kAnalysisLowPassHz, sourceSampleRate * 0.42f) /
        static_cast<float>(sourceSampleRate)
    );
    m_lowPassMono += alpha * (safeMono - m_lowPassMono);

    m_resampleAccumulator += kAnalysisSampleRate;
    while (m_resampleAccumulator >= sourceSampleRate) {
        m_resampleAccumulator -= sourceSampleRate;
        appendFrame(m_lowPassMono);
    }
}

void MonoSpectrumAnalyzer::appendFrame(float mono) {
    m_ring[m_writeIndex] = mono;
    m_writeIndex = (m_writeIndex + 1) % kFftSize;
    m_validFrames = std::min(kFftSize, m_validFrames + 1);
}

void MonoSpectrumAnalyzer::transform(std::array<float, kBandCount>& targetBands) {
    const int zeroPadding = kFftSize - m_validFrames;
    double mean = 0.0;
    for (int i = 0; i < m_validFrames; ++i) {
        const int source = (m_writeIndex - m_validFrames + i + kFftSize) % kFftSize;
        mean += m_ring[source];
    }
    const float dc = m_validFrames > 0 ? static_cast<float>(mean / m_validFrames) : 0.0f;

    for (int i = 0; i < kFftSize; ++i) {
        float value = 0.0f;
        if (i >= zeroPadding) {
            const int validIndex = i - zeroPadding;
            const int source = (m_writeIndex - m_validFrames + validIndex + kFftSize) % kFftSize;
            value = m_ring[source] - dc;
        }
        m_fftBuffer[i] = std::complex<float>(value * m_hann[i], 0.0f);
    }
    fft(m_fftBuffer);

    const float maxFrequency = std::min(kMaximumFrequency, kAnalysisSampleRate * 0.49f);
    const float ratio = maxFrequency / kMinimumFrequency;
    const float magnitudeScale = 4.0f / static_cast<float>(kFftSize);
    for (int band = 0; band < kBandCount; ++band) {
        const float lowT = static_cast<float>(band) / kBandCount;
        const float highT = static_cast<float>(band + 1) / kBandCount;
        const float lowFrequency = kMinimumFrequency * std::pow(ratio, lowT);
        const float highFrequency = kMinimumFrequency * std::pow(ratio, highT);
        int lowBin = static_cast<int>(std::floor(lowFrequency * kFftSize / kAnalysisSampleRate));
        int highBin = static_cast<int>(std::ceil(highFrequency * kFftSize / kAnalysisSampleRate));
        lowBin = std::clamp(lowBin, 1, kFftSize / 2 - 1);
        highBin = std::clamp(std::max(lowBin, highBin), lowBin, kFftSize / 2 - 1);

        float peakMagnitude = 0.0f;
        float energy = 0.0f;
        int binCount = 0;
        for (int bin = lowBin; bin <= highBin; ++bin) {
            const float magnitude = std::abs(m_fftBuffer[bin]) * magnitudeScale;
            peakMagnitude = std::max(peakMagnitude, magnitude);
            energy += magnitude * magnitude;
            ++binCount;
        }
        const float rmsMagnitude = binCount > 0 ? std::sqrt(energy / binCount) : 0.0f;
        const float magnitude = peakMagnitude * 0.68f + rmsMagnitude * 0.32f;
        const float db = 20.0f * std::log10(std::max(magnitude, 1.0e-9f));
        const float normalized = std::clamp(
            (db - kNoiseFloorDb) / (kSpectrumCeilingDb - kNoiseFloorDb),
            0.0f,
            1.0f
        );

        // Keep the natural low-to-high energy slope instead of lifting weak treble into a wall.
        const float tilt = 1.06f - 0.16f * highT;
        targetBands[band] = std::clamp(std::pow(normalized, 1.45f) * tilt, 0.0f, 1.0f);
    }
}

void MonoSpectrumAnalyzer::fft(std::vector<std::complex<float>>& values) const {
    for (int i = 1, j = 0; i < kFftSize; ++i) {
        int bit = kFftSize >> 1;
        for (; (j & bit) != 0; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) std::swap(values[i], values[j]);
    }
    for (int length = 2; length <= kFftSize; length <<= 1) {
        const float angle = -2.0f * kPi / static_cast<float>(length);
        const std::complex<float> root(std::cos(angle), std::sin(angle));
        for (int base = 0; base < kFftSize; base += length) {
            std::complex<float> factor(1.0f, 0.0f);
            const int half = length >> 1;
            for (int index = 0; index < half; ++index) {
                const std::complex<float> even = values[base + index];
                const std::complex<float> odd = values[base + index + half] * factor;
                values[base + index] = even + odd;
                values[base + index + half] = even - odd;
                factor *= root;
            }
        }
    }
}

void MonoSpectrumAnalyzer::updateSmoothedBands(
    const std::array<float, kBandCount>& targets,
    float inputRms,
    float deltaSeconds,
    bool breatheWhenSilent
) {
    const bool silent = inputRms < kSilenceRms;
    m_breathPhase = std::fmod(
        m_breathPhase + 2.0f * kPi * kBreathHz * deltaSeconds,
        2.0f * kPi
    );
    const float riseStep = kRisePerSecond * deltaSeconds;
    const float fallStep = kFallPerSecond * deltaSeconds;
    const float inputDb = 20.0f * std::log10(std::max(inputRms, 1.0e-7f));
    const float energyTarget = std::pow(
        std::clamp(
            (inputDb - kEnergyFloorDb) / (kEnergyCeilingDb - kEnergyFloorDb),
            0.0f,
            1.0f
        ),
        1.65f
    );
    const float energyStep = (energyTarget > m_energyEnvelope
        ? kEnergyRisePerSecond
        : kEnergyFallPerSecond) * deltaSeconds;
    if (energyTarget > m_energyEnvelope) {
        m_energyEnvelope = std::min(energyTarget, m_energyEnvelope + energyStep);
    } else {
        m_energyEnvelope = std::max(energyTarget, m_energyEnvelope - energyStep);
    }
    // Do not invent energy in quiet passages. The tiny floor only prevents numerical flicker.
    const float energyScale = 0.02f + 1.06f * m_energyEnvelope;

    for (int band = 0; band < kBandCount; ++band) {
        const float bandPhase = static_cast<float>(band) * 0.13f;
        const float breath = 0.009f + 0.009f * (
            0.5f + 0.5f * std::sin(m_breathPhase + bandPhase)
        );
        const float target = silent
            ? (breatheWhenSilent ? breath : 0.0f)
            : std::clamp(targets[band] * energyScale, 0.0f, 1.0f);
        float& current = m_smoothed[band];
        if (target > current) {
            current = std::min(target, current + riseStep);
        } else {
            current = std::max(target, current - fallStep);
        }
    }
}

float MonoSpectrumAnalyzer::nextDeltaSeconds() {
    const double now = monotonicSeconds();
    const float deltaSeconds = m_lastAnalyzeSeconds > 0.0
        ? static_cast<float>(std::clamp(now - m_lastAnalyzeSeconds, 0.008, 0.050))
        : 1.0f / 60.0f;
    m_lastAnalyzeSeconds = now;
    return deltaSeconds;
}

void MonoSpectrumAnalyzer::copyOutput(float* output, int outputCount) const {
    if (output == nullptr || outputCount < kBandCount) return;
    std::copy(m_smoothed.begin(), m_smoothed.end(), output);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_dsp_NativeStereoSpectrumAnalyzer_nativeAnalyze(
    JNIEnv* env,
    jobject,
    jbyteArray buffer,
    jint read,
    jint channels,
    jint sampleRate,
    jint sampleEncoding,
    jint validBitsPerSample,
    jfloatArray output
) {
    if (buffer == nullptr || output == nullptr) return JNI_FALSE;
    const jsize inputLength = env->GetArrayLength(buffer);
    const jsize outputLength = env->GetArrayLength(output);
    const int safeRead = std::clamp(static_cast<int>(read), 0, static_cast<int>(inputLength));
    if (safeRead <= 0 || outputLength < MonoSpectrumAnalyzer::kBandCount) {
        return JNI_FALSE;
    }

    thread_local std::vector<std::uint8_t> inputCopy;
    thread_local std::array<float, MonoSpectrumAnalyzer::kBandCount> outputCopy{};
    inputCopy.resize(safeRead);
    env->GetByteArrayRegion(
        buffer,
        0,
        safeRead,
        reinterpret_cast<jbyte*>(inputCopy.data())
    );
    if (env->ExceptionCheck()) return JNI_FALSE;

    bool success;
    {
        std::lock_guard<std::mutex> lock(gAnalyzerMutex);
        success = gAnalyzer.analyzePcm(
            inputCopy.data(),
            safeRead,
            channels,
            sampleRate,
            sampleEncoding,
            validBitsPerSample,
            outputCopy.data(),
            static_cast<int>(outputCopy.size())
        );
    }
    if (success) {
        env->SetFloatArrayRegion(
            output,
            0,
            static_cast<jsize>(outputCopy.size()),
            outputCopy.data()
        );
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_dsp_NativeStereoSpectrumAnalyzer_nativeTick(
    JNIEnv* env,
    jobject,
    jboolean paused,
    jfloatArray output
) {
    if (output == nullptr) return JNI_FALSE;
    const jsize outputLength = env->GetArrayLength(output);
    if (outputLength < MonoSpectrumAnalyzer::kBandCount) return JNI_FALSE;

    thread_local std::array<float, MonoSpectrumAnalyzer::kBandCount> outputCopy{};
    bool success;
    {
        std::lock_guard<std::mutex> lock(gAnalyzerMutex);
        success = gAnalyzer.advanceIdle(
            paused == JNI_TRUE,
            outputCopy.data(),
            static_cast<int>(outputCopy.size())
        );
    }
    if (success) {
        env->SetFloatArrayRegion(
            output,
            0,
            static_cast<jsize>(outputCopy.size()),
            outputCopy.data()
        );
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeStereoSpectrumAnalyzer_nativeReset(
    JNIEnv*,
    jobject
) {
    std::lock_guard<std::mutex> lock(gAnalyzerMutex);
    gAnalyzer.reset();
}
