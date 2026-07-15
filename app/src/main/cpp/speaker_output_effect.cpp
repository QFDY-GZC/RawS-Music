#include "speaker_output_effect.h"

#include <jni.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstddef>

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr int kMaximumProcessedChannels = 8;
constexpr float kMinimumOutputGain = 0.72f; // 最多约 -2.85 dB 的瞬态预留。

inline float clampFloat(float value, float minimum, float maximum) {
    if (!std::isfinite(value)) return minimum;
    return std::max(minimum, std::min(maximum, value));
}

inline float dbToLinear(float db) {
    return std::pow(10.0f, db / 20.0f);
}

inline float millisecondsToCoefficient(float milliseconds, float sampleRate) {
    const float safeMs = std::max(milliseconds, 0.05f);
    const float safeRate = std::max(sampleRate, 8000.0f);
    return std::exp(-1.0f / (safeMs * 0.001f * safeRate));
}

inline float followEnvelope(
    float input,
    float current,
    float attackCoefficient,
    float releaseCoefficient
) {
    const float coefficient = input > current ? attackCoefficient : releaseCoefficient;
    return coefficient * current + (1.0f - coefficient) * input;
}

inline float smoothStep01(float value) {
    const float x = clampFloat(value, 0.0f, 1.0f);
    return x * x * (3.0f - 2.0f * x);
}

/**
 * 仅在接近满幅时介入，用于约束并行分支的偶发过冲；主处理链仍保留最终安全限制。
 */
inline float softProtect(float value) {
    if (!std::isfinite(value)) return 0.0f;
    constexpr float knee = 0.985f;
    constexpr float span = 0.014f;
    const float magnitude = std::fabs(value);
    if (magnitude <= knee) return value;
    const float protectedMagnitude = knee + span * std::tanh((magnitude - knee) / span);
    return std::copysign(std::min(protectedMagnitude, 0.9995f), value);
}

class BandBiquad {
public:
    void resetState() {
        x1 = x2 = y1 = y2 = 0.0f;
    }

    void setHighPass(float sampleRate, float frequency, float q) {
        configure(sampleRate, frequency, q, true);
    }

    void setLowPass(float sampleRate, float frequency, float q) {
        configure(sampleRate, frequency, q, false);
    }

    float process(float input) {
        const float output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1;
        x1 = input;
        y2 = y1;
        y1 = std::isfinite(output) ? output : 0.0f;
        return y1;
    }

private:
    float b0 = 1.0f;
    float b1 = 0.0f;
    float b2 = 0.0f;
    float a1 = 0.0f;
    float a2 = 0.0f;
    float x1 = 0.0f;
    float x2 = 0.0f;
    float y1 = 0.0f;
    float y2 = 0.0f;

    void configure(float sampleRate, float frequency, float q, bool highPass) {
        const float safeRate = std::max(sampleRate, 8000.0f);
        const float nyquistLimited = std::min(frequency, safeRate * 0.45f);
        const float omega = 2.0f * kPi * std::max(10.0f, nyquistLimited) / safeRate;
        const float cosine = std::cos(omega);
        const float sine = std::sin(omega);
        const float alpha = sine / (2.0f * std::max(q, 0.1f));

        float rawB0;
        float rawB1;
        float rawB2;
        if (highPass) {
            rawB0 = (1.0f + cosine) * 0.5f;
            rawB1 = -(1.0f + cosine);
            rawB2 = rawB0;
        } else {
            rawB0 = (1.0f - cosine) * 0.5f;
            rawB1 = 1.0f - cosine;
            rawB2 = rawB0;
        }

        const float rawA0 = 1.0f + alpha;
        b0 = rawB0 / rawA0;
        b1 = rawB1 / rawA0;
        b2 = rawB2 / rawA0;
        a1 = (-2.0f * cosine) / rawA0;
        a2 = (1.0f - alpha) / rawA0;
    }
};

class FirstOrderAllPass {
public:
    void resetState() {
        previousInput = 0.0f;
        previousOutput = 0.0f;
    }

    void setCoefficient(float value) {
        coefficient = clampFloat(value, -0.72f, 0.72f);
    }

    float process(float input) {
        const float output = -coefficient * input + previousInput + coefficient * previousOutput;
        previousInput = input;
        previousOutput = std::isfinite(output) ? output : 0.0f;
        return previousOutput;
    }

private:
    float coefficient = 0.42f;
    float previousInput = 0.0f;
    float previousOutput = 0.0f;
};

struct ElasticityParameters {
    // 默认参数强调可感知的瞬态对比，同时避免固定低频架持续抬升。
    float strengthPercent = 82.0f;
    float detectorLowHz = 85.0f;
    float detectorHighHz = 1350.0f;
    float fastAttackMs = 0.35f;
    float fastReleaseMs = 20.0f;
    float slowAttackMs = 34.0f;
    float slowReleaseMs = 165.0f;
    float gainAttackMs = 0.30f;
    float gainReleaseMs = 62.0f;
    float maxBoostDb = 4.2f;
    float noiseGateDb = -50.0f;
    float headroomCeiling = 0.9772f; // 约 -0.2 dBFS。
    float peakReleaseMs = 70.0f;
    float sensitivity = 1.92f; // 对应旧 UI 的约 82%。
};

struct PowerfulParameters {
    // 默认参数提供更明显的厚度与持续能量，同时保持动态低频退让和峰值预测。
    float strengthPercent = 84.0f;
    float bodyLowHz = 65.0f;
    float bodyHighHz = 390.0f;
    float bassBoostDb = 4.0f;
    float harmonicPercent = 34.0f;
    float compressorThresholdDb = -20.0f;
    float compressorRatio = 3.5f;
    float compressorAttackMs = 10.0f;
    float compressorReleaseMs = 200.0f;
    float parallelMixPercent = 48.0f;
    float makeupGainDb = 3.4f;
    float presenceBoostDb = 1.3f;
    float headroomCeiling = 0.9716f; // 约 -0.25 dBFS。
};

struct WideParameters {
    float strengthPercent = 76.0f;
    float crossoverHz = 760.0f;
    float widthDb = 3.2f;
    float decorrelationPercent = 18.0f;
    float bassCenterPercent = 58.0f;
    float centerProtectionPercent = 70.0f;
    float headroomCeiling = 0.9716f; // 约 -0.25 dBFS。
};
} // namespace

class SpeakerOutputEffect::Impl {
public:
    std::atomic<bool> pendingEnabled{false};
    std::atomic<bool> enabledForQuery{false};
    std::atomic<int> pendingMode{static_cast<int>(SpeakerOutputEffect::Mode::Elasticity)};
    std::atomic<bool> modeDirty{true};
    std::atomic<bool> parametersDirty{true};
    std::atomic<bool> powerfulParametersDirty{true};
    std::atomic<bool> wideParametersDirty{true};
    std::atomic<bool> enabledDirty{true};
    std::array<std::atomic<float>, 14> pendingParameters;
    std::array<std::atomic<float>, 13> pendingPowerfulParameters;
    std::array<std::atomic<float>, 7> pendingWideParameters;

    int sampleRate = 44100;
    bool enabled = false;
    SpeakerOutputEffect::Mode mode = SpeakerOutputEffect::Mode::Elasticity;
    ElasticityParameters parameters;
    PowerfulParameters powerfulParameters;
    WideParameters wideParameters;

    // Each channel has its own band filter state. Detection uses band energy, not L+R,
    // so opposite-phase stereo content cannot cancel a kick/snare trigger.
    std::array<BandBiquad, kMaximumProcessedChannels> bandHighPass;
    std::array<BandBiquad, kMaximumProcessedChannels> bandLowPass;

    float fastEnvelope = 0.0f;
    float slowEnvelope = 0.0f;
    float recentPeakEnvelope = 0.0f;
    float transientEnvelope = 0.0f;
    float heldTransient = 0.0f;
    float pendingBodyDuck = 0.0f;
    float bodyDuckEnvelope = 0.0f;
    float previousOnset = 0.0f;

    int holdSamplesRemaining = 0;
    int bodyDuckDelaySamplesRemaining = 0;
    int holdSamples = 1;

    float fastAttackCoefficient = 0.0f;
    float fastReleaseCoefficient = 0.0f;
    float slowAttackCoefficient = 0.0f;
    float slowReleaseCoefficient = 0.0f;
    float transientAttackCoefficient = 0.0f;
    float transientReleaseCoefficient = 0.0f;
    float peakReleaseCoefficient = 0.0f;
    float bodyDuckReleaseCoefficient = 0.0f;

    // Powerful mode uses independent filter and envelope state so mode switches never inherit
    // elasticity detector history or body-duck tails.
    std::array<BandBiquad, kMaximumProcessedChannels> powerfulBodyHighPass;
    std::array<BandBiquad, kMaximumProcessedChannels> powerfulBodyLowPass;
    std::array<BandBiquad, kMaximumProcessedChannels> powerfulPresenceHighPass;
    std::array<BandBiquad, kMaximumProcessedChannels> powerfulPresenceLowPass;
    float powerfulBodyEnvelope = 0.0f;
    float powerfulCompressionEnvelope = 0.0f;
    float powerfulRecentPeakEnvelope = 0.0f;
    float powerfulBodyAttackCoefficient = 0.0f;
    float powerfulBodyReleaseCoefficient = 0.0f;
    float powerfulCompressionAttackCoefficient = 0.0f;
    float powerfulCompressionReleaseCoefficient = 0.0f;
    float powerfulPeakReleaseCoefficient = 0.0f;

    static constexpr int kMaximumStereoPairs = kMaximumProcessedChannels / 2;
    std::array<BandBiquad, kMaximumStereoPairs> wideSideHighPass;
    std::array<BandBiquad, kMaximumStereoPairs> wideSideLowPass;
    std::array<FirstOrderAllPass, kMaximumStereoPairs> wideSideAllPass;
    std::array<float, kMaximumStereoPairs> wideMidEnvelope{};
    std::array<float, kMaximumStereoPairs> wideSideEnvelope{};
    float wideRecentPeakEnvelope = 0.0f;
    float wideEnvelopeAttackCoefficient = 0.0f;
    float wideEnvelopeReleaseCoefficient = 0.0f;
    float widePeakReleaseCoefficient = 0.0f;

    Impl() {
        const ElasticityParameters defaults;
        const float values[14] = {
            defaults.strengthPercent,
            defaults.detectorLowHz,
            defaults.detectorHighHz,
            defaults.fastAttackMs,
            defaults.fastReleaseMs,
            defaults.slowAttackMs,
            defaults.slowReleaseMs,
            defaults.gainAttackMs,
            defaults.gainReleaseMs,
            defaults.maxBoostDb,
            defaults.noiseGateDb,
            defaults.headroomCeiling,
            defaults.peakReleaseMs,
            defaults.sensitivity
        };
        for (std::size_t index = 0; index < pendingParameters.size(); ++index) {
            pendingParameters[index].store(values[index], std::memory_order_relaxed);
        }

        const PowerfulParameters powerfulDefaults;
        const float powerfulValues[13] = {
            powerfulDefaults.strengthPercent,
            powerfulDefaults.bodyLowHz,
            powerfulDefaults.bodyHighHz,
            powerfulDefaults.bassBoostDb,
            powerfulDefaults.harmonicPercent,
            powerfulDefaults.compressorThresholdDb,
            powerfulDefaults.compressorRatio,
            powerfulDefaults.compressorAttackMs,
            powerfulDefaults.compressorReleaseMs,
            powerfulDefaults.parallelMixPercent,
            powerfulDefaults.makeupGainDb,
            powerfulDefaults.presenceBoostDb,
            powerfulDefaults.headroomCeiling
        };
        for (std::size_t index = 0; index < pendingPowerfulParameters.size(); ++index) {
            pendingPowerfulParameters[index].store(powerfulValues[index], std::memory_order_relaxed);
        }

        const WideParameters wideDefaults;
        const float wideValues[7] = {
            wideDefaults.strengthPercent,
            wideDefaults.crossoverHz,
            wideDefaults.widthDb,
            wideDefaults.decorrelationPercent,
            wideDefaults.bassCenterPercent,
            wideDefaults.centerProtectionPercent,
            wideDefaults.headroomCeiling
        };
        for (std::size_t index = 0; index < pendingWideParameters.size(); ++index) {
            pendingWideParameters[index].store(wideValues[index], std::memory_order_relaxed);
        }
        updateDerivedValues();
    }

    void resetState() {
        for (auto& filter : bandHighPass) filter.resetState();
        for (auto& filter : bandLowPass) filter.resetState();
        fastEnvelope = 0.0f;
        slowEnvelope = 0.0f;
        recentPeakEnvelope = 0.0f;
        transientEnvelope = 0.0f;
        heldTransient = 0.0f;
        pendingBodyDuck = 0.0f;
        bodyDuckEnvelope = 0.0f;
        previousOnset = 0.0f;
        holdSamplesRemaining = 0;
        bodyDuckDelaySamplesRemaining = 0;

        for (auto& filter : powerfulBodyHighPass) filter.resetState();
        for (auto& filter : powerfulBodyLowPass) filter.resetState();
        for (auto& filter : powerfulPresenceHighPass) filter.resetState();
        for (auto& filter : powerfulPresenceLowPass) filter.resetState();
        powerfulBodyEnvelope = 0.0f;
        powerfulCompressionEnvelope = 0.0f;
        powerfulRecentPeakEnvelope = 0.0f;

        for (auto& filter : wideSideHighPass) filter.resetState();
        for (auto& filter : wideSideLowPass) filter.resetState();
        for (auto& filter : wideSideAllPass) filter.resetState();
        wideMidEnvelope.fill(0.0f);
        wideSideEnvelope.fill(0.0f);
        wideRecentPeakEnvelope = 0.0f;

        updateBandFilters();
        updatePowerfulFilters();
        updateWideFilters();
    }

    void updateBandFilters() {
        const float rate = static_cast<float>(sampleRate);
        for (auto& filter : bandHighPass) {
            filter.setHighPass(rate, parameters.detectorLowHz, 0.70710678f);
        }
        for (auto& filter : bandLowPass) {
            filter.setLowPass(rate, parameters.detectorHighHz, 0.70710678f);
        }
    }

    void updatePowerfulFilters() {
        const float rate = static_cast<float>(sampleRate);
        for (auto& filter : powerfulBodyHighPass) {
            filter.setHighPass(rate, powerfulParameters.bodyLowHz, 0.70710678f);
        }
        for (auto& filter : powerfulBodyLowPass) {
            filter.setLowPass(rate, powerfulParameters.bodyHighHz, 0.70710678f);
        }
        // 存在感频段保持固定，避免模式参数过度复杂。
        for (auto& filter : powerfulPresenceHighPass) {
            filter.setHighPass(rate, 1800.0f, 0.70710678f);
        }
        for (auto& filter : powerfulPresenceLowPass) {
            filter.setLowPass(rate, 6500.0f, 0.70710678f);
        }
    }

    void updateWideFilters() {
        const float rate = static_cast<float>(sampleRate);
        for (auto& filter : wideSideHighPass) {
            filter.setHighPass(rate, wideParameters.crossoverHz, 0.70710678f);
        }
        for (auto& filter : wideSideLowPass) {
            filter.setLowPass(rate, wideParameters.crossoverHz, 0.70710678f);
        }
        const float normalized = wideParameters.decorrelationPercent * 0.01f;
        const float coefficient = 0.22f + 0.34f * clampFloat(normalized, 0.0f, 1.0f);
        for (auto& filter : wideSideAllPass) {
            filter.setCoefficient(coefficient);
        }
    }

    void updateDerivedValues() {
        const float rate = static_cast<float>(std::max(sampleRate, 8000));
        fastAttackCoefficient = millisecondsToCoefficient(parameters.fastAttackMs, rate);
        fastReleaseCoefficient = millisecondsToCoefficient(parameters.fastReleaseMs, rate);
        slowAttackCoefficient = millisecondsToCoefficient(parameters.slowAttackMs, rate);
        slowReleaseCoefficient = millisecondsToCoefficient(parameters.slowReleaseMs, rate);
        transientAttackCoefficient = millisecondsToCoefficient(parameters.gainAttackMs, rate);
        transientReleaseCoefficient = millisecondsToCoefficient(parameters.gainReleaseMs, rate);
        peakReleaseCoefficient = millisecondsToCoefficient(parameters.peakReleaseMs, rate);

        // The body recovery is intentionally a little slower than the wet transient.
        // This produces a perceptible "hit -> collect -> rebound" contrast without pumping.
        const float bodyReleaseMs = std::max(55.0f, parameters.gainReleaseMs * 1.20f);
        bodyDuckReleaseCoefficient = millisecondsToCoefficient(bodyReleaseMs, rate);

        // Hold is derived from the user's recovery value so no extra persisted parameter is
        // required. Default: about 11.8 ms; valid range: 8..18 ms.
        const float holdMs = clampFloat(7.5f + parameters.gainReleaseMs * 0.07f, 8.0f, 18.0f);
        holdSamples = std::max(1, static_cast<int>(holdMs * 0.001f * rate + 0.5f));
        updateBandFilters();

        powerfulBodyAttackCoefficient = millisecondsToCoefficient(18.0f, rate);
        powerfulBodyReleaseCoefficient = millisecondsToCoefficient(220.0f, rate);
        powerfulCompressionAttackCoefficient = millisecondsToCoefficient(
            powerfulParameters.compressorAttackMs,
            rate
        );
        powerfulCompressionReleaseCoefficient = millisecondsToCoefficient(
            powerfulParameters.compressorReleaseMs,
            rate
        );
        powerfulPeakReleaseCoefficient = millisecondsToCoefficient(
            std::max(80.0f, powerfulParameters.compressorReleaseMs * 0.75f),
            rate
        );

        wideEnvelopeAttackCoefficient = millisecondsToCoefficient(18.0f, rate);
        wideEnvelopeReleaseCoefficient = millisecondsToCoefficient(180.0f, rate);
        widePeakReleaseCoefficient = millisecondsToCoefficient(95.0f, rate);

        updatePowerfulFilters();
        updateWideFilters();
    }

    void applyPendingChanges() {
        if (enabledDirty.exchange(false, std::memory_order_acq_rel)) {
            const bool newEnabled = pendingEnabled.load(std::memory_order_acquire);
            if (!enabled && newEnabled) resetState();
            enabled = newEnabled;
            enabledForQuery.store(newEnabled, std::memory_order_release);
        }

        if (modeDirty.exchange(false, std::memory_order_acq_rel)) {
            const int requested = pendingMode.load(std::memory_order_acquire);
            SpeakerOutputEffect::Mode newMode = SpeakerOutputEffect::Mode::Elasticity;
            if (requested == static_cast<int>(SpeakerOutputEffect::Mode::Powerful)) {
                newMode = SpeakerOutputEffect::Mode::Powerful;
            } else if (requested == static_cast<int>(SpeakerOutputEffect::Mode::Wide)) {
                newMode = SpeakerOutputEffect::Mode::Wide;
            }
            if (newMode != mode) {
                mode = newMode;
                resetState();
            }
        }

        bool derivedValuesChanged = false;
        if (parametersDirty.exchange(false, std::memory_order_acq_rel)) {
            float value[14];
            for (std::size_t index = 0; index < pendingParameters.size(); ++index) {
                value[index] = pendingParameters[index].load(std::memory_order_relaxed);
            }

            parameters.strengthPercent = clampFloat(value[0], 0.0f, 100.0f);
            parameters.detectorLowHz = clampFloat(value[1], 40.0f, 300.0f);
            parameters.detectorHighHz = clampFloat(value[2], 300.0f, 3000.0f);
            parameters.detectorHighHz = std::max(
                parameters.detectorHighHz,
                parameters.detectorLowHz + 80.0f
            );
            parameters.fastAttackMs = clampFloat(value[3], 0.1f, 10.0f);
            parameters.fastReleaseMs = clampFloat(value[4], 5.0f, 150.0f);
            parameters.slowAttackMs = clampFloat(value[5], 2.0f, 100.0f);
            parameters.slowReleaseMs = clampFloat(value[6], 30.0f, 500.0f);
            parameters.gainAttackMs = clampFloat(value[7], 0.1f, 10.0f);
            parameters.gainReleaseMs = clampFloat(value[8], 10.0f, 250.0f);
            parameters.maxBoostDb = clampFloat(value[9], 0.0f, 6.0f);
            parameters.noiseGateDb = clampFloat(value[10], -80.0f, -24.0f);
            parameters.headroomCeiling = clampFloat(value[11], 0.70f, 0.995f);
            parameters.peakReleaseMs = clampFloat(value[12], 10.0f, 300.0f);
            parameters.sensitivity = clampFloat(value[13], 0.25f, 3.0f);
            derivedValuesChanged = true;
        }

        if (powerfulParametersDirty.exchange(false, std::memory_order_acq_rel)) {
            float value[13];
            for (std::size_t index = 0; index < pendingPowerfulParameters.size(); ++index) {
                value[index] = pendingPowerfulParameters[index].load(std::memory_order_relaxed);
            }
            powerfulParameters.strengthPercent = clampFloat(value[0], 0.0f, 100.0f);
            powerfulParameters.bodyLowHz = clampFloat(value[1], 40.0f, 140.0f);
            powerfulParameters.bodyHighHz = clampFloat(value[2], 180.0f, 700.0f);
            powerfulParameters.bodyHighHz = std::max(
                powerfulParameters.bodyHighHz,
                powerfulParameters.bodyLowHz + 100.0f
            );
            powerfulParameters.bassBoostDb = clampFloat(value[3], 0.0f, 6.0f);
            powerfulParameters.harmonicPercent = clampFloat(value[4], 0.0f, 100.0f);
            powerfulParameters.compressorThresholdDb = clampFloat(value[5], -36.0f, -6.0f);
            powerfulParameters.compressorRatio = clampFloat(value[6], 1.0f, 8.0f);
            powerfulParameters.compressorAttackMs = clampFloat(value[7], 2.0f, 80.0f);
            powerfulParameters.compressorReleaseMs = clampFloat(value[8], 40.0f, 500.0f);
            powerfulParameters.parallelMixPercent = clampFloat(value[9], 0.0f, 100.0f);
            powerfulParameters.makeupGainDb = clampFloat(value[10], 0.0f, 6.0f);
            powerfulParameters.presenceBoostDb = clampFloat(value[11], 0.0f, 4.0f);
            powerfulParameters.headroomCeiling = clampFloat(value[12], 0.70f, 0.995f);
            derivedValuesChanged = true;
        }

        if (wideParametersDirty.exchange(false, std::memory_order_acq_rel)) {
            float value[7];
            for (std::size_t index = 0; index < pendingWideParameters.size(); ++index) {
                value[index] = pendingWideParameters[index].load(std::memory_order_relaxed);
            }
            wideParameters.strengthPercent = clampFloat(value[0], 0.0f, 100.0f);
            wideParameters.crossoverHz = clampFloat(value[1], 300.0f, 2200.0f);
            wideParameters.widthDb = clampFloat(value[2], 0.0f, 6.0f);
            wideParameters.decorrelationPercent = clampFloat(value[3], 0.0f, 60.0f);
            wideParameters.bassCenterPercent = clampFloat(value[4], 0.0f, 100.0f);
            wideParameters.centerProtectionPercent = clampFloat(value[5], 0.0f, 100.0f);
            wideParameters.headroomCeiling = clampFloat(value[6], 0.70f, 0.995f);
            derivedValuesChanged = true;
        }

        if (derivedValuesChanged) updateDerivedValues();
    }

    void processPowerful(float* samples, int numFrames, int channels) {
        const float rawStrength = powerfulParameters.strengthPercent * 0.01f;
        if (rawStrength <= 0.0001f) return;

        const float strength = std::sqrt(clampFloat(rawStrength, 0.0f, 1.0f));
        const float bodyGainDelta = dbToLinear(powerfulParameters.bassBoostDb) - 1.0f;
        const float harmonicAmount = powerfulParameters.harmonicPercent * 0.01f * strength;
        const float compressionMix = powerfulParameters.parallelMixPercent * 0.01f * strength;
        const float makeupGain = dbToLinear(powerfulParameters.makeupGainDb * strength);
        const float presenceGainDelta = dbToLinear(
            powerfulParameters.presenceBoostDb * strength
        ) - 1.0f;
        const float compressionThreshold = dbToLinear(
            powerfulParameters.compressorThresholdDb
        );
        const float ratio = std::max(1.0f, powerfulParameters.compressorRatio);
        const float ceiling = powerfulParameters.headroomCeiling;

        std::array<float, kMaximumProcessedChannels> bodySample{};
        std::array<float, kMaximumProcessedChannels> presenceSample{};

        for (int frame = 0; frame < numFrames; ++frame) {
            const int base = frame * channels;
            const int processedChannels = std::min(channels, kMaximumProcessedChannels);
            float framePeak = 0.0f;
            float bodyEnergy = 0.0f;
            float fullBandEnergy = 0.0f;

            for (int channel = 0; channel < channels; ++channel) {
                const float input = std::isfinite(samples[base + channel])
                    ? samples[base + channel]
                    : 0.0f;
                samples[base + channel] = input;
                framePeak = std::max(framePeak, std::fabs(input));
                fullBandEnergy += input * input;

                if (channel < processedChannels) {
                    float body = powerfulBodyHighPass[channel].process(input);
                    body = powerfulBodyLowPass[channel].process(body);
                    bodySample[channel] = body;
                    bodyEnergy += body * body;

                    float presence = powerfulPresenceHighPass[channel].process(input);
                    presence = powerfulPresenceLowPass[channel].process(presence);
                    presenceSample[channel] = presence;
                }
            }

            const float bodyLevel = processedChannels > 0
                ? std::sqrt(bodyEnergy / static_cast<float>(processedChannels))
                : 0.0f;
            const float fullBandLevel = channels > 0
                ? std::sqrt(fullBandEnergy / static_cast<float>(channels))
                : 0.0f;

            powerfulBodyEnvelope = followEnvelope(
                bodyLevel,
                powerfulBodyEnvelope,
                powerfulBodyAttackCoefficient,
                powerfulBodyReleaseCoefficient
            );
            powerfulCompressionEnvelope = followEnvelope(
                fullBandLevel,
                powerfulCompressionEnvelope,
                powerfulCompressionAttackCoefficient,
                powerfulCompressionReleaseCoefficient
            );
            powerfulRecentPeakEnvelope = std::max(
                framePeak,
                powerfulRecentPeakEnvelope * powerfulPeakReleaseCoefficient
            );

            // When the original track already contains dense low-mid energy, the dynamic bass
            // branch retreats. Sparse material receives more lift, dense masters receive less.
            const float bodyOccupancy = smoothStep01(
                (powerfulBodyEnvelope - 0.045f) / 0.20f
            );
            const float dynamicBodyScale = 1.0f - 0.50f * bodyOccupancy;
            const float bodyWetGain = bodyGainDelta * strength * dynamicBodyScale * 1.06f;

            float compressorGain = 1.0f;
            if (powerfulCompressionEnvelope > compressionThreshold && ratio > 1.001f) {
                const float over = powerfulCompressionEnvelope / compressionThreshold;
                compressorGain = std::pow(over, -(1.0f - 1.0f / ratio));
            }
            const float denseBranchGain = compressorGain * makeupGain;

            // Reserve grows only as recent peaks approach the target ceiling. It allows the
            // parallel branches to remain audible on mastered tracks without hard clipping.
            const float pressureStart = ceiling * 0.78f;
            const float pressureRange = std::max(0.02f, ceiling - pressureStart);
            const float pressure = smoothStep01(
                (powerfulRecentPeakEnvelope - pressureStart) / pressureRange
            );
            const float reserveGain = 1.0f - 0.15f * strength * pressure;

            float maximumPredictedPeak = 0.0f;
            std::array<float, kMaximumProcessedChannels> candidate{};
            for (int channel = 0; channel < processedChannels; ++channel) {
                const float input = samples[base + channel];
                const float compressedBlend = input * (
                    1.0f + compressionMix * (denseBranchGain - 1.0f)
                );

                const float body = bodySample[channel];
                // Soft saturation adds mostly upper harmonics of the low-mid body. Subtracting
                // the linear term prevents the branch from behaving as a second bass shelf.
                const float harmonicDrive = 1.8f + 2.2f * harmonicAmount;
                const float saturated = std::tanh(body * harmonicDrive) / harmonicDrive;
                const float harmonic = (body - saturated) * harmonicAmount * 1.8f;
                const float bodyWet = body * bodyWetGain + harmonic;
                const float presenceWet = presenceSample[channel] * presenceGainDelta;
                candidate[channel] = compressedBlend * reserveGain + bodyWet + presenceWet;
                maximumPredictedPeak = std::max(
                    maximumPredictedPeak,
                    std::fabs(candidate[channel])
                );
            }

            const float safetyScale = maximumPredictedPeak > ceiling
                ? clampFloat(ceiling / (maximumPredictedPeak + 1.0e-6f), 0.55f, 1.0f)
                : 1.0f;

            for (int channel = 0; channel < channels; ++channel) {
                const float output = channel < processedChannels
                    ? candidate[channel] * safetyScale
                    : samples[base + channel] * reserveGain;
                samples[base + channel] = softProtect(output);
            }
        }
    }

    void processWide(float* samples, int numFrames, int channels) {
        if (channels < 2) return;
        const float rawStrength = wideParameters.strengthPercent * 0.01f;
        if (rawStrength <= 0.0001f) return;

        const float strength = std::sqrt(clampFloat(rawStrength, 0.0f, 1.0f));
        const float widthDelta = (dbToLinear(wideParameters.widthDb) - 1.0f) * strength;
        const float decorrelationMix = wideParameters.decorrelationPercent * 0.01f * strength;
        const float bassCenterAmount = wideParameters.bassCenterPercent * 0.01f * strength;
        const float protection = wideParameters.centerProtectionPercent * 0.01f;
        const float sideRatioThreshold = 1.35f - 0.65f * protection;
        const float ceiling = wideParameters.headroomCeiling;
        const int stereoPairs = std::min(channels / 2, kMaximumStereoPairs);

        for (int frame = 0; frame < numFrames; ++frame) {
            const int base = frame * channels;
            float inputPeak = 0.0f;
            for (int channel = 0; channel < channels; ++channel) {
                const float input = std::isfinite(samples[base + channel])
                    ? samples[base + channel]
                    : 0.0f;
                samples[base + channel] = input;
                inputPeak = std::max(inputPeak, std::fabs(input));
            }
            wideRecentPeakEnvelope = std::max(
                inputPeak,
                wideRecentPeakEnvelope * widePeakReleaseCoefficient
            );

            const float pressureStart = ceiling * 0.80f;
            const float pressureRange = std::max(0.02f, ceiling - pressureStart);
            const float peakPressure = smoothStep01(
                (wideRecentPeakEnvelope - pressureStart) / pressureRange
            );
            const float peakGuard = 1.0f - 0.35f * peakPressure;

            for (int pair = 0; pair < stereoPairs; ++pair) {
                const int leftIndex = base + pair * 2;
                const int rightIndex = leftIndex + 1;
                const float left = samples[leftIndex];
                const float right = samples[rightIndex];
                const float mid = (left + right) * 0.5f;
                const float side = (left - right) * 0.5f;

                const float highSide = wideSideHighPass[pair].process(side);
                const float lowSide = wideSideLowPass[pair].process(side);
                const float shiftedSide = wideSideAllPass[pair].process(highSide);
                const float decorrelatedSide = highSide + (
                    shiftedSide - highSide
                ) * decorrelationMix;

                wideMidEnvelope[pair] = followEnvelope(
                    std::fabs(mid),
                    wideMidEnvelope[pair],
                    wideEnvelopeAttackCoefficient,
                    wideEnvelopeReleaseCoefficient
                );
                wideSideEnvelope[pair] = followEnvelope(
                    std::fabs(side),
                    wideSideEnvelope[pair],
                    wideEnvelopeAttackCoefficient,
                    wideEnvelopeReleaseCoefficient
                );

                const float sideRatio = wideSideEnvelope[pair] / (
                    wideMidEnvelope[pair] + 0.012f
                );
                const float ratioPressure = smoothStep01(
                    (sideRatio - sideRatioThreshold) / 0.85f
                );
                const float centerGuard = 1.0f - protection * 0.72f * ratioPressure;
                const float extraSideGain = widthDelta * centerGuard * peakGuard;

                const float widenedSide = side
                    + decorrelatedSide * extraSideGain
                    - lowSide * bassCenterAmount;
                float candidateLeft = mid + widenedSide;
                float candidateRight = mid - widenedSide;

                const float candidatePeak = std::max(
                    std::fabs(candidateLeft),
                    std::fabs(candidateRight)
                );
                if (candidatePeak > ceiling) {
                    const float scale = clampFloat(
                        ceiling / (candidatePeak + 1.0e-6f),
                        0.68f,
                        1.0f
                    );
                    candidateLeft *= scale;
                    candidateRight *= scale;
                }

                samples[leftIndex] = softProtect(candidateLeft);
                samples[rightIndex] = softProtect(candidateRight);
            }
        }
    }

};

SpeakerOutputEffect::SpeakerOutputEffect() : m_impl(std::make_unique<Impl>()) {}
SpeakerOutputEffect::~SpeakerOutputEffect() = default;

void SpeakerOutputEffect::setSampleRate(int sampleRate) {
    if (sampleRate <= 0 || sampleRate == m_impl->sampleRate) return;
    m_impl->sampleRate = sampleRate;
    m_impl->resetState();
    m_impl->updateDerivedValues();
}

void SpeakerOutputEffect::reset() {
    m_impl->resetState();
}

void SpeakerOutputEffect::setEnabled(bool enabled) {
    m_impl->pendingEnabled.store(enabled, std::memory_order_release);
    m_impl->enabledForQuery.store(enabled, std::memory_order_release);
    m_impl->enabledDirty.store(true, std::memory_order_release);
}

bool SpeakerOutputEffect::isEnabled() const {
    return m_impl->enabledForQuery.load(std::memory_order_acquire);
}

void SpeakerOutputEffect::setMode(Mode mode) {
    m_impl->pendingMode.store(static_cast<int>(mode), std::memory_order_release);
    m_impl->modeDirty.store(true, std::memory_order_release);
}

void SpeakerOutputEffect::setElasticityParameters(
    float strengthPercent,
    float detectorLowHz,
    float detectorHighHz,
    float fastAttackMs,
    float fastReleaseMs,
    float slowAttackMs,
    float slowReleaseMs,
    float gainAttackMs,
    float gainReleaseMs,
    float maxBoostDb,
    float noiseGateDb,
    float headroomCeiling,
    float peakReleaseMs,
    float sensitivity
) {
    const float values[14] = {
        strengthPercent, detectorLowHz, detectorHighHz,
        fastAttackMs, fastReleaseMs, slowAttackMs, slowReleaseMs,
        gainAttackMs, gainReleaseMs, maxBoostDb, noiseGateDb,
        headroomCeiling, peakReleaseMs, sensitivity
    };
    for (std::size_t index = 0; index < m_impl->pendingParameters.size(); ++index) {
        m_impl->pendingParameters[index].store(values[index], std::memory_order_relaxed);
    }
    m_impl->parametersDirty.store(true, std::memory_order_release);
}

void SpeakerOutputEffect::setPowerfulParameters(
    float strengthPercent,
    float bodyLowHz,
    float bodyHighHz,
    float bassBoostDb,
    float harmonicPercent,
    float compressorThresholdDb,
    float compressorRatio,
    float compressorAttackMs,
    float compressorReleaseMs,
    float parallelMixPercent,
    float makeupGainDb,
    float presenceBoostDb,
    float headroomCeiling
) {
    const float values[13] = {
        strengthPercent, bodyLowHz, bodyHighHz, bassBoostDb, harmonicPercent,
        compressorThresholdDb, compressorRatio, compressorAttackMs,
        compressorReleaseMs, parallelMixPercent, makeupGainDb,
        presenceBoostDb, headroomCeiling
    };
    for (std::size_t index = 0; index < m_impl->pendingPowerfulParameters.size(); ++index) {
        m_impl->pendingPowerfulParameters[index].store(values[index], std::memory_order_relaxed);
    }
    m_impl->powerfulParametersDirty.store(true, std::memory_order_release);
}

void SpeakerOutputEffect::setWideParameters(
    float strengthPercent,
    float crossoverHz,
    float widthDb,
    float decorrelationPercent,
    float bassCenterPercent,
    float centerProtectionPercent,
    float headroomCeiling
) {
    const float values[7] = {
        strengthPercent, crossoverHz, widthDb, decorrelationPercent,
        bassCenterPercent, centerProtectionPercent, headroomCeiling
    };
    for (std::size_t index = 0; index < m_impl->pendingWideParameters.size(); ++index) {
        m_impl->pendingWideParameters[index].store(values[index], std::memory_order_relaxed);
    }
    m_impl->wideParametersDirty.store(true, std::memory_order_release);
}

void SpeakerOutputEffect::process(float* samples, int numFrames, int channels) {
    m_impl->applyPendingChanges();
    if (!m_impl->enabled || samples == nullptr || numFrames <= 0 || channels <= 0) return;

    if (m_impl->mode == Mode::Powerful) {
        m_impl->processPowerful(samples, numFrames, channels);
        return;
    }
    if (m_impl->mode == Mode::Wide) {
        m_impl->processWide(samples, numFrames, channels);
        return;
    }

    const float rawStrength = m_impl->parameters.strengthPercent * 0.01f;
    if (rawStrength <= 0.0001f) return;

    // Square-root response gives the useful middle of the UI slider more authority while
    // retaining a true zero at 0% and full scale at 100%.
    const float effectiveStrength = std::sqrt(clampFloat(rawStrength, 0.0f, 1.0f));
    const float maximumBandGainDelta = dbToLinear(m_impl->parameters.maxBoostDb) - 1.0f;
    const float gateLinear = dbToLinear(m_impl->parameters.noiseGateDb);
    const float ceiling = m_impl->parameters.headroomCeiling;

    // Precompute the two small attenuation endpoints once per audio block. Per-sample
    // interpolation is sufficient for these sub-1.2 dB moves and avoids pow()/exp() in
    // the real-time inner loop.
    const float maximumBodyDuckDb = 0.55f + 0.60f * effectiveStrength;
    const float minimumBodyDuckGain = dbToLinear(-maximumBodyDuckDb * effectiveStrength);
    const float minimumReserveGain = dbToLinear(-1.20f * effectiveStrength);

    // 仅叠加检测到的低中频起音频段，避免持续抬高人声与高频。
    constexpr float parallelImpactScale = 1.15f;

    std::array<float, kMaximumProcessedChannels> bandSample{};

    for (int frame = 0; frame < numFrames; ++frame) {
        const int base = frame * channels;
        const int processedChannels = std::min(channels, kMaximumProcessedChannels);
        float bandEnergy = 0.0f;
        float bandPeak = 0.0f;
        float framePeak = 0.0f;

        for (int channel = 0; channel < channels; ++channel) {
            const float input = std::isfinite(samples[base + channel])
                ? samples[base + channel]
                : 0.0f;
            samples[base + channel] = input;
            framePeak = std::max(framePeak, std::fabs(input));

            if (channel < processedChannels) {
                float band = m_impl->bandHighPass[channel].process(input);
                band = m_impl->bandLowPass[channel].process(band);
                bandSample[channel] = band;
                bandEnergy += band * band;
                bandPeak = std::max(bandPeak, std::fabs(band));
            }
        }

        const float detectorLevel = processedChannels > 0
            ? std::sqrt(bandEnergy / static_cast<float>(processedChannels))
            : 0.0f;

        m_impl->fastEnvelope = followEnvelope(
            detectorLevel,
            m_impl->fastEnvelope,
            m_impl->fastAttackCoefficient,
            m_impl->fastReleaseCoefficient
        );
        m_impl->slowEnvelope = followEnvelope(
            detectorLevel,
            m_impl->slowEnvelope,
            m_impl->slowAttackCoefficient,
            m_impl->slowReleaseCoefficient
        );
        m_impl->recentPeakEnvelope = std::max(
            framePeak,
            m_impl->recentPeakEnvelope * m_impl->peakReleaseCoefficient
        );

        float onset = 0.0f;
        if (m_impl->fastEnvelope > gateLinear) {
            const float relativeRise =
                (m_impl->fastEnvelope - m_impl->slowEnvelope) /
                (m_impl->slowEnvelope + gateLinear * 1.5f);
            onset = smoothStep01(relativeRise * m_impl->parameters.sensitivity);
        }

        // Hold the strongest detected edge for a few milliseconds. This turns an otherwise
        // almost single-sample peak into a perceptible impact plateau.
        if (onset >= m_impl->heldTransient) {
            m_impl->heldTransient = onset;
            m_impl->holdSamplesRemaining = m_impl->holdSamples;
        } else if (m_impl->holdSamplesRemaining > 0) {
            --m_impl->holdSamplesRemaining;
        } else {
            m_impl->heldTransient = followEnvelope(
                onset,
                m_impl->heldTransient,
                m_impl->transientAttackCoefficient,
                m_impl->transientReleaseCoefficient
            );
        }

        m_impl->transientEnvelope = followEnvelope(
            m_impl->heldTransient,
            m_impl->transientEnvelope,
            m_impl->transientAttackCoefficient,
            m_impl->transientReleaseCoefficient
        );

        // Queue a gentle body reduction after a newly rising attack. It is deliberately
        // delayed until the impact hold finishes, creating contrast instead of cancelling
        // the attack itself. Dense attacks update the pending level without restarting delay.
        const bool risingAttack = onset > 0.10f && onset > m_impl->previousOnset + 0.02f;
        if (risingAttack) {
            m_impl->pendingBodyDuck = std::max(m_impl->pendingBodyDuck, onset);
            if (m_impl->bodyDuckDelaySamplesRemaining <= 0) {
                m_impl->bodyDuckDelaySamplesRemaining = m_impl->holdSamples;
            }
        }
        m_impl->previousOnset = onset;

        if (m_impl->bodyDuckDelaySamplesRemaining > 0) {
            --m_impl->bodyDuckDelaySamplesRemaining;
            if (m_impl->bodyDuckDelaySamplesRemaining == 0) {
                m_impl->bodyDuckEnvelope = std::max(
                    m_impl->bodyDuckEnvelope,
                    m_impl->pendingBodyDuck
                );
                m_impl->pendingBodyDuck = 0.0f;
            }
        } else {
            m_impl->bodyDuckEnvelope *= m_impl->bodyDuckReleaseCoefficient;
        }

        const float wetGain = maximumBandGainDelta * effectiveStrength *
            m_impl->transientEnvelope * parallelImpactScale;

        // Up to roughly 1.15 dB of post-impact body collection at full strength. Interpolate
        // in linear gain to keep the per-sample path cheap and monotonic.
        const float bodyDuckGain = 1.0f -
            (1.0f - minimumBodyDuckGain) * m_impl->bodyDuckEnvelope;

        // 已压满的母带通常没有正峰值余量，因此使用自适应干声预留保留起音与主体对比。
        const float pressureStart = ceiling * 0.82f;
        const float pressureRange = std::max(0.02f, ceiling - pressureStart);
        const float peakPressure = smoothStep01(
            (m_impl->recentPeakEnvelope - pressureStart) / pressureRange
        );
        const float reserveGain = 1.0f -
            (1.0f - minimumReserveGain) * peakPressure;
        float dryGain = bodyDuckGain * reserveGain;
        float wetScale = 1.0f;

        // Frame-local prediction first spends dry gain down to kMinimumOutputGain, then
        // reduces wet signal only if required. This preserves the attack/body contrast.
        float predictedPeak = framePeak * dryGain + bandPeak * wetGain;
        if (predictedPeak > ceiling && framePeak > 1.0e-6f) {
            const float requiredDryGain =
                (ceiling - bandPeak * wetGain) / (framePeak + 1.0e-6f);
            dryGain = std::min(dryGain, std::max(kMinimumOutputGain, requiredDryGain));
            predictedPeak = framePeak * dryGain + bandPeak * wetGain;
        }
        if (predictedPeak > ceiling && bandPeak * wetGain > 1.0e-6f) {
            wetScale = clampFloat(
                (ceiling - framePeak * dryGain) / (bandPeak * wetGain),
                0.25f,
                1.0f
            );
        }

        for (int channel = 0; channel < channels; ++channel) {
            const float dry = samples[base + channel] * dryGain;
            const float wet = channel < processedChannels
                ? bandSample[channel] * wetGain * wetScale
                : 0.0f;
            samples[base + channel] = softProtect(dry + wet);
        }
    }
}

// DSPChain is owned by dsp_engine.cpp. The narrow accessor keeps its layout private.
extern "C" SpeakerOutputEffect* rawsmusic_dsp_get_speaker_output_effect(jlong handle);

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetSpeakerOutputEnabled(
    JNIEnv*, jobject, jlong handle, jboolean enabled
) {
    if (auto* effect = rawsmusic_dsp_get_speaker_output_effect(handle)) {
        effect->setEnabled(enabled == JNI_TRUE);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetSpeakerOutputMode(
    JNIEnv*, jobject, jlong handle, jint mode
) {
    if (auto* effect = rawsmusic_dsp_get_speaker_output_effect(handle)) {
        SpeakerOutputEffect::Mode target = SpeakerOutputEffect::Mode::Elasticity;
        if (mode == static_cast<jint>(SpeakerOutputEffect::Mode::Powerful)) {
            target = SpeakerOutputEffect::Mode::Powerful;
        } else if (mode == static_cast<jint>(SpeakerOutputEffect::Mode::Wide)) {
            target = SpeakerOutputEffect::Mode::Wide;
        }
        effect->setMode(target);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetSpeakerPowerfulParameters(
    JNIEnv*, jobject, jlong handle,
    jfloat strengthPercent,
    jfloat bodyLowHz,
    jfloat bodyHighHz,
    jfloat bassBoostDb,
    jfloat harmonicPercent,
    jfloat compressorThresholdDb,
    jfloat compressorRatio,
    jfloat compressorAttackMs,
    jfloat compressorReleaseMs,
    jfloat parallelMixPercent,
    jfloat makeupGainDb,
    jfloat presenceBoostDb,
    jfloat headroomCeiling
) {
    if (auto* effect = rawsmusic_dsp_get_speaker_output_effect(handle)) {
        effect->setPowerfulParameters(
            strengthPercent, bodyLowHz, bodyHighHz, bassBoostDb, harmonicPercent,
            compressorThresholdDb, compressorRatio, compressorAttackMs,
            compressorReleaseMs, parallelMixPercent, makeupGainDb,
            presenceBoostDb, headroomCeiling
        );
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetSpeakerElasticityParameters(
    JNIEnv*, jobject, jlong handle,
    jfloat strengthPercent,
    jfloat detectorLowHz,
    jfloat detectorHighHz,
    jfloat fastAttackMs,
    jfloat fastReleaseMs,
    jfloat slowAttackMs,
    jfloat slowReleaseMs,
    jfloat gainAttackMs,
    jfloat gainReleaseMs,
    jfloat maxBoostDb,
    jfloat noiseGateDb,
    jfloat headroomCeiling,
    jfloat peakReleaseMs,
    jfloat sensitivity
) {
    if (auto* effect = rawsmusic_dsp_get_speaker_output_effect(handle)) {
        effect->setElasticityParameters(
            strengthPercent, detectorLowHz, detectorHighHz,
            fastAttackMs, fastReleaseMs, slowAttackMs, slowReleaseMs,
            gainAttackMs, gainReleaseMs, maxBoostDb, noiseGateDb,
            headroomCeiling, peakReleaseMs, sensitivity
        );
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_dsp_NativeDSPEngine_nativeSetSpeakerWideParameters(
    JNIEnv*, jobject, jlong handle,
    jfloat strengthPercent,
    jfloat crossoverHz,
    jfloat widthDb,
    jfloat decorrelationPercent,
    jfloat bassCenterPercent,
    jfloat centerProtectionPercent,
    jfloat headroomCeiling
) {
    if (auto* effect = rawsmusic_dsp_get_speaker_output_effect(handle)) {
        effect->setWideParameters(
            strengthPercent,
            crossoverHz,
            widthDb,
            decorrelationPercent,
            bassCenterPercent,
            centerProtectionPercent,
            headroomCeiling
        );
    }
}
