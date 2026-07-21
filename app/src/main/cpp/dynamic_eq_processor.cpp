#include "dynamic_eq_processor.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPi = 3.14159265358979323846f;

float coefficient(float milliseconds, int sampleRate) {
    return 1.0f - std::exp(-1.0f / (milliseconds * 0.001f * sampleRate));
}

float dbToLinear(float db) {
    return std::pow(10.0f, db / 20.0f);
}
}

void DynamicEqProcessor::setSampleRate(int sampleRate) {
    sampleRate_ = std::max(sampleRate, 8000);
    reset();
}

void DynamicEqProcessor::setEnabled(bool enabled) {
    pendingEnabled_.store(enabled, std::memory_order_release);
    parametersDirty_.store(true, std::memory_order_release);
}

void DynamicEqProcessor::setParameters(
        float intensityPercent,
        float deEsserPercent,
        float deEsserFrequencyHz) {
    pendingIntensity_.store(std::clamp(intensityPercent, 0.0f, 100.0f), std::memory_order_relaxed);
    pendingDeEsser_.store(std::clamp(deEsserPercent, 0.0f, 100.0f), std::memory_order_relaxed);
    pendingDeEsserFrequency_.store(
        std::clamp(deEsserFrequencyHz, 4000.0f, 10000.0f),
        std::memory_order_relaxed);
    parametersDirty_.store(true, std::memory_order_release);
}

bool DynamicEqProcessor::isEnabled() const {
    return pendingEnabled_.load(std::memory_order_acquire);
}

void DynamicEqProcessor::reset() {
    bodyLowState_[0] = bodyLowState_[1] = 0.0f;
    presenceLowState_[0] = presenceLowState_[1] = 0.0f;
    presenceHighState_[0] = presenceHighState_[1] = 0.0f;
    deEsserLowState_[0] = deEsserLowState_[1] = 0.0f;
    programEnvelope_ = 0.0f;
    sibilanceEnvelope_ = 0.0f;
    reductionGain_ = 1.0f;
}

void DynamicEqProcessor::applyPendingParameters() {
    if (!parametersDirty_.exchange(false, std::memory_order_acq_rel)) return;
    const bool nextEnabled = pendingEnabled_.load(std::memory_order_acquire);
    intensity_ = pendingIntensity_.load(std::memory_order_relaxed) * 0.01f;
    deEsser_ = pendingDeEsser_.load(std::memory_order_relaxed) * 0.01f;
    deEsserFrequency_ = pendingDeEsserFrequency_.load(std::memory_order_relaxed);
    if (!enabled_ && nextEnabled) reset();
    enabled_ = nextEnabled;
}

void DynamicEqProcessor::process(float* samples, int numFrames, int channels) {
    applyPendingParameters();
    if (!enabled_ || samples == nullptr || numFrames <= 0 || channels <= 0) return;

    const int processedChannels = std::min(channels, 2);
    const float bodyAlpha = 1.0f - std::exp(-2.0f * kPi * 180.0f / sampleRate_);
    const float presenceLowAlpha = 1.0f - std::exp(-2.0f * kPi * 1800.0f / sampleRate_);
    const float presenceHighAlpha = 1.0f - std::exp(-2.0f * kPi * 5200.0f / sampleRate_);
    const float deEsserAlpha = 1.0f - std::exp(-2.0f * kPi * deEsserFrequency_ / sampleRate_);
    const float programAttack = coefficient(8.0f, sampleRate_);
    const float programRelease = coefficient(180.0f, sampleRate_);
    const float sibilanceAttack = coefficient(1.5f, sampleRate_);
    const float sibilanceRelease = coefficient(90.0f, sampleRate_);
    const float gainAttack = coefficient(2.0f, sampleRate_);
    const float gainRelease = coefficient(110.0f, sampleRate_);
    const float threshold = dbToLinear(-30.0f);

    for (int frame = 0; frame < numFrames; ++frame) {
        float inputs[2]{};
        float highs[2]{};
        float peak = 0.0f;
        float highPeak = 0.0f;
        for (int channel = 0; channel < processedChannels; ++channel) {
            const int index = frame * channels + channel;
            inputs[channel] = std::isfinite(samples[index]) ? samples[index] : 0.0f;
            deEsserLowState_[channel] += deEsserAlpha * (inputs[channel] - deEsserLowState_[channel]);
            highs[channel] = inputs[channel] - deEsserLowState_[channel];
            peak = std::max(peak, std::fabs(inputs[channel]));
            highPeak = std::max(highPeak, std::fabs(highs[channel]));
        }

        programEnvelope_ += (peak - programEnvelope_) *
            (peak > programEnvelope_ ? programAttack : programRelease);
        sibilanceEnvelope_ += (highPeak - sibilanceEnvelope_) *
            (highPeak > sibilanceEnvelope_ ? sibilanceAttack : sibilanceRelease);

        float targetReduction = 1.0f;
        if (sibilanceEnvelope_ > threshold) {
            const float over = std::clamp((sibilanceEnvelope_ - threshold) / (1.0f - threshold), 0.0f, 1.0f);
            const float maxReductionDb = 12.0f * deEsser_;
            targetReduction = dbToLinear(-maxReductionDb * std::sqrt(over));
        }
        reductionGain_ += (targetReduction - reductionGain_) *
            (targetReduction < reductionGain_ ? gainAttack : gainRelease);

        // At lower programme levels a small linked low-band lift restores body;
        // dense passages approach unity so this stage never pumps bass upward.
        const float quiet = std::clamp((0.65f - programEnvelope_) / 0.55f, 0.0f, 1.0f);
        const float dense = std::clamp((programEnvelope_ - 0.55f) / 0.35f, 0.0f, 1.0f);
        const float bodyGain = dbToLinear(intensity_ * (5.0f * quiet - 2.0f * dense));
        const float presenceGain = dbToLinear(2.0f * intensity_ * quiet);
        const float headroom = dbToLinear(-1.2f * intensity_ * quiet);

        for (int channel = 0; channel < processedChannels; ++channel) {
            const int index = frame * channels + channel;
            bodyLowState_[channel] += bodyAlpha * (inputs[channel] - bodyLowState_[channel]);
            presenceLowState_[channel] += presenceLowAlpha *
                (inputs[channel] - presenceLowState_[channel]);
            presenceHighState_[channel] += presenceHighAlpha *
                (inputs[channel] - presenceHighState_[channel]);
            const float presence = presenceHighState_[channel] - presenceLowState_[channel];
            const float deEssed = inputs[channel] + highs[channel] * (reductionGain_ - 1.0f);
            const float output = (
                deEssed +
                bodyLowState_[channel] * (bodyGain - 1.0f) +
                presence * (presenceGain - 1.0f)
            ) * headroom;
            samples[index] = std::isfinite(output) ? output : 0.0f;
        }
    }
}
