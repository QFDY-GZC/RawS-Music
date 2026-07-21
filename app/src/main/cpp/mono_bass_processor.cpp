#include "mono_bass_processor.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPi = 3.14159265358979323846f;
}

void MonoBassProcessor::setSampleRate(int sampleRate) {
    sampleRate_ = std::max(sampleRate, 8000);
    reset();
}

void MonoBassProcessor::setEnabled(bool enabled) {
    pendingEnabled_.store(enabled, std::memory_order_release);
    parametersDirty_.store(true, std::memory_order_release);
}

void MonoBassProcessor::setParameters(float crossoverHz, float amountPercent) {
    pendingCrossoverHz_.store(std::clamp(crossoverHz, 60.0f, 300.0f), std::memory_order_relaxed);
    pendingAmount_.store(std::clamp(amountPercent, 0.0f, 100.0f), std::memory_order_relaxed);
    parametersDirty_.store(true, std::memory_order_release);
}

bool MonoBassProcessor::isEnabled() const {
    return pendingEnabled_.load(std::memory_order_acquire);
}

void MonoBassProcessor::reset() {
    lowState_[0] = lowState_[1] = 0.0f;
    smoothedAmount_ = enabled_ ? targetAmount_ : 0.0f;
}

void MonoBassProcessor::applyPendingParameters() {
    if (!parametersDirty_.exchange(false, std::memory_order_acq_rel)) return;
    const bool nextEnabled = pendingEnabled_.load(std::memory_order_acquire);
    crossoverHz_ = pendingCrossoverHz_.load(std::memory_order_relaxed);
    targetAmount_ = pendingAmount_.load(std::memory_order_relaxed) * 0.01f;
    if (!enabled_ && nextEnabled) reset();
    enabled_ = nextEnabled;
}

void MonoBassProcessor::process(float* samples, int numFrames, int channels) {
    applyPendingParameters();
    if (!enabled_ || samples == nullptr || numFrames <= 0 || channels < 2) return;

    const float alpha = 1.0f - std::exp(-2.0f * kPi * crossoverHz_ / sampleRate_);
    const float smooth = 1.0f - std::exp(-1.0f / (0.025f * sampleRate_));
    for (int frame = 0; frame < numFrames; ++frame) {
        const int index = frame * channels;
        const float left = std::isfinite(samples[index]) ? samples[index] : 0.0f;
        const float right = std::isfinite(samples[index + 1]) ? samples[index + 1] : 0.0f;
        lowState_[0] += alpha * (left - lowState_[0]);
        lowState_[1] += alpha * (right - lowState_[1]);
        smoothedAmount_ += (targetAmount_ - smoothedAmount_) * smooth;

        const float monoLow = (lowState_[0] + lowState_[1]) * 0.5f;
        const float lowLeft = lowState_[0] + (monoLow - lowState_[0]) * smoothedAmount_;
        const float lowRight = lowState_[1] + (monoLow - lowState_[1]) * smoothedAmount_;
        samples[index] = (left - lowState_[0]) + lowLeft;
        samples[index + 1] = (right - lowState_[1]) + lowRight;
    }
}
