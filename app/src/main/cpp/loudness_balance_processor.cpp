#include "loudness_balance_processor.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPi = 3.14159265358979323846f;

float onePoleCoefficient(float frequency, int sampleRate) {
    return 1.0f - std::exp(-2.0f * kPi * frequency / static_cast<float>(sampleRate));
}

float dbToLinear(float db) {
    return std::pow(10.0f, db / 20.0f);
}
}

void LoudnessBalanceProcessor::setSampleRate(int sampleRate) {
    sampleRate_ = std::max(sampleRate, 8000);
    reset();
}

void LoudnessBalanceProcessor::setEnabled(bool enabled) {
    pendingEnabled_.store(enabled, std::memory_order_release);
    parametersDirty_.store(true, std::memory_order_release);
}

void LoudnessBalanceProcessor::setParameters(float loudnessPercent, float balance) {
    pendingLoudness_.store(std::clamp(loudnessPercent, 0.0f, 100.0f), std::memory_order_relaxed);
    pendingBalance_.store(std::clamp(balance, -1.0f, 1.0f), std::memory_order_relaxed);
    parametersDirty_.store(true, std::memory_order_release);
}

bool LoudnessBalanceProcessor::isEnabled() const {
    return pendingEnabled_.load(std::memory_order_acquire);
}

void LoudnessBalanceProcessor::reset() {
    lowState_[0] = lowState_[1] = 0.0f;
    highLowState_[0] = highLowState_[1] = 0.0f;
    smoothedLoudness_ = enabled_ ? targetLoudness_ : 0.0f;
    smoothedBalance_ = targetBalance_;
}

void LoudnessBalanceProcessor::applyPendingParameters() {
    if (!parametersDirty_.exchange(false, std::memory_order_acq_rel)) return;
    const bool nextEnabled = pendingEnabled_.load(std::memory_order_acquire);
    targetLoudness_ = pendingLoudness_.load(std::memory_order_relaxed) * 0.01f;
    targetBalance_ = pendingBalance_.load(std::memory_order_relaxed);
    if (!enabled_ && nextEnabled) reset();
    enabled_ = nextEnabled;
}

void LoudnessBalanceProcessor::process(float* samples, int numFrames, int channels) {
    applyPendingParameters();
    if (!enabled_ || samples == nullptr || numFrames <= 0 || channels <= 0) return;

    const float lowAlpha = onePoleCoefficient(120.0f, sampleRate_);
    const float highAlpha = onePoleCoefficient(6000.0f, sampleRate_);
    const float smooth = 1.0f - std::exp(-1.0f / (0.020f * sampleRate_));
    const int processedChannels = std::min(channels, 2);

    for (int frame = 0; frame < numFrames; ++frame) {
        smoothedLoudness_ += (targetLoudness_ - smoothedLoudness_) * smooth;
        smoothedBalance_ += (targetBalance_ - smoothedBalance_) * smooth;

        // Keep the default 35% position clearly audible without turning the
        // control into a plain volume boost. The linked limiter still owns the
        // final peak, while this smaller compensation preserves the contour.
        const float lowGain = dbToLinear(11.0f * smoothedLoudness_);
        const float highGain = dbToLinear(4.5f * smoothedLoudness_);
        const float headroom = dbToLinear(-2.2f * smoothedLoudness_);
        const float leftGain = smoothedBalance_ > 0.0f
            ? std::cos(smoothedBalance_ * kPi * 0.5f)
            : 1.0f;
        const float rightGain = smoothedBalance_ < 0.0f
            ? std::cos(-smoothedBalance_ * kPi * 0.5f)
            : 1.0f;

        for (int channel = 0; channel < processedChannels; ++channel) {
            const int index = frame * channels + channel;
            const float input = std::isfinite(samples[index]) ? samples[index] : 0.0f;
            lowState_[channel] += lowAlpha * (input - lowState_[channel]);
            highLowState_[channel] += highAlpha * (input - highLowState_[channel]);
            const float high = input - highLowState_[channel];
            float output = input + lowState_[channel] * (lowGain - 1.0f) + high * (highGain - 1.0f);
            output *= headroom * (channel == 0 ? leftGain : rightGain);
            samples[index] = std::isfinite(output) ? output : 0.0f;
        }
    }
}
