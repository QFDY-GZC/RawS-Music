#include "android_dvc_processor.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kRampMilliseconds = 20.0f;
}

void AndroidDvcProcessor::setSampleRate(int sampleRate) {
    sampleRate_ = std::max(8000, sampleRate);
}

void AndroidDvcProcessor::setEnabled(bool enabled) {
    pendingEnabled_.store(enabled, std::memory_order_release);
}

void AndroidDvcProcessor::setTargetGain(float gain) {
    const float safe = std::isfinite(gain)
        ? std::clamp(gain, 0.0f, 1.0f)
        : 1.0f;
    pendingTargetGain_.store(safe, std::memory_order_release);
}

void AndroidDvcProcessor::setNoDvcHeadroomDb(float gainDb) {
    const float safeDb = std::isfinite(gainDb)
        ? std::clamp(gainDb, -24.0f, 0.0f)
        : -6.0f;
    pendingNoDvcGain_.store(
        std::pow(10.0f, safeDb / 20.0f),
        std::memory_order_release
    );
}

bool AndroidDvcProcessor::isEnabled() const {
    return pendingEnabled_.load(std::memory_order_acquire) ||
        pendingNoDvcGain_.load(std::memory_order_acquire) < 0.99999f;
}

void AndroidDvcProcessor::process(float* samples, int sampleCount) {
    if (samples == nullptr || sampleCount <= 0) return;

    const bool enabled = pendingEnabled_.load(std::memory_order_acquire);
    const float target = enabled
        ? pendingTargetGain_.load(std::memory_order_acquire)
        : pendingNoDvcGain_.load(std::memory_order_acquire);
    const int rampSamples = std::max(
        1,
        static_cast<int>(sampleRate_ * kRampMilliseconds / 1000.0f)
    );
    const float maxStep = 1.0f / static_cast<float>(rampSamples);

    for (int i = 0; i < sampleCount; ++i) {
        const float delta = target - currentGain_;
        if (std::fabs(delta) <= maxStep) {
            currentGain_ = target;
        } else {
            currentGain_ += std::copysign(maxStep, delta);
        }
        samples[i] *= currentGain_;
    }
}
