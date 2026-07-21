#include "stereo_width_processor.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPeakCeiling = 0.999f;
constexpr float kAmountIdleThreshold = 1.0e-5f;
constexpr float kGainIdleThreshold = 1.0e-5f;

// The linked limiter changes gain by approximately 1/128
// on attack and 1/8192 on release at 48 kHz. These time constants preserve
// that behavior across sample rates.
constexpr float kLimiterAttackSeconds = 128.0f / 48000.0f;
constexpr float kLimiterReleaseSeconds = 8192.0f / 48000.0f;
}

StereoWidthProcessor::StereoWidthProcessor() {
    updateCoefficients();
}

float StereoWidthProcessor::clamp(
    float value,
    float minimum,
    float maximum
) {
    return std::max(minimum, std::min(maximum, value));
}

float StereoWidthProcessor::sanitize(float value) {
    return std::isfinite(value) ? value : 0.0f;
}

float StereoWidthProcessor::exponentialCoefficient(
    float milliseconds,
    float sampleRate
) {
    const float safeRate = std::max(sampleRate, 8000.0f);
    const float seconds = std::max(milliseconds, 0.05f) * 0.001f;
    return 1.0f - std::exp(-1.0f / (seconds * safeRate));
}

void StereoWidthProcessor::updateCoefficients() {
    const float safeRate = static_cast<float>(std::max(sampleRate_, 8000));

    // Parameter smoothing is deliberately short: it removes zipper noise while
    // preserving the immediate response of a continuously dragged 0–100% slider.
    amountSmoothing_ = exponentialCoefficient(8.0f, safeRate);

    limiterAttackStep_ = clamp(
        1.0f / (safeRate * kLimiterAttackSeconds),
        1.0e-6f,
        1.0f
    );
    limiterReleaseStep_ = clamp(
        1.0f / (safeRate * kLimiterReleaseSeconds),
        1.0e-7f,
        1.0f
    );
}

void StereoWidthProcessor::resetState(bool resetAmount) {
    limiterTargetGain_ = 1.0f;
    limiterGain_ = 1.0f;
    if (resetAmount) {
        currentAmount_ = 0.0f;
    }
}

void StereoWidthProcessor::applyPendingParameters() {
    if (!parametersDirty_.exchange(false, std::memory_order_acq_rel)) {
        return;
    }

    const float nextAmount = clamp(
        pendingAmount_.load(std::memory_order_relaxed),
        0.0f,
        1.0f
    );

    const bool wasInactive =
        targetAmount_ <= kAmountIdleThreshold &&
        currentAmount_ <= kAmountIdleThreshold &&
        limiterGain_ >= 1.0f - kGainIdleThreshold;

    targetAmount_ = nextAmount;
    if (nextAmount > kAmountIdleThreshold) {
        processingTail_.store(true, std::memory_order_release);
        if (wasInactive) {
            resetState(true);
        }
    }
}

void StereoWidthProcessor::process(
    float* samples,
    int numFrames,
    int channels
) {
    applyPendingParameters();

    if (samples == nullptr || numFrames <= 0 || channels != 2) {
        return;
    }

    if (!processingTail_.load(std::memory_order_acquire) &&
        targetAmount_ <= kAmountIdleThreshold) {
        return;
    }

    for (int frame = 0; frame < numFrames; ++frame) {
        currentAmount_ +=
            (targetAmount_ - currentAmount_) * amountSmoothing_;
        currentAmount_ = clamp(currentAmount_, 0.0f, 1.0f);

        const int offset = frame * 2;
        const float left = sanitize(samples[offset]);
        const float right = sanitize(samples[offset + 1]);

        // Continuous stereo-width matrix. The Mid component is
        // unchanged before linked peak control; only Side is multiplied.
        const float difference = left - right;
        const float expandedLeft = left + currentAmount_ * difference;
        const float expandedRight = right - currentAmount_ * difference;

        const float peak = std::max(
            std::fabs(expandedLeft),
            std::fabs(expandedRight)
        );
        const float immediateSafeGain = peak > kPeakCeiling
            ? kPeakCeiling / peak
            : 1.0f;

        // The detector falls immediately to a new lower target and recovers at
        // the same slow linear rate as the linked gain. This avoids image pumping
        // and keeps rapid peaks from repeatedly reopening the limiter.
        if (immediateSafeGain < limiterTargetGain_) {
            limiterTargetGain_ = immediateSafeGain;
        } else {
            limiterTargetGain_ = std::min(
                1.0f,
                limiterTargetGain_ + limiterReleaseStep_
            );
        }

        if (limiterTargetGain_ < limiterGain_) {
            limiterGain_ = std::max(
                limiterTargetGain_,
                limiterGain_ - limiterAttackStep_
            );
        } else if (limiterTargetGain_ > limiterGain_) {
            limiterGain_ = std::min(
                limiterTargetGain_,
                limiterGain_ + limiterReleaseStep_
            );
        }

        // The instantaneous cap guarantees that the current sample never clips;
        // limiterGain_ supplies the linked release envelope for following frames.
        const float appliedGain = std::min(limiterGain_, immediateSafeGain);

        samples[offset] = sanitize(expandedLeft * appliedGain);
        samples[offset + 1] = sanitize(expandedRight * appliedGain);
    }

    if (targetAmount_ <= kAmountIdleThreshold &&
        currentAmount_ <= 5.0e-4f &&
        limiterTargetGain_ >= 1.0f - kGainIdleThreshold &&
        limiterGain_ >= 1.0f - kGainIdleThreshold) {
        currentAmount_ = 0.0f;
        limiterTargetGain_ = 1.0f;
        limiterGain_ = 1.0f;
        processingTail_.store(false, std::memory_order_release);
    }
}

void StereoWidthProcessor::setParameter(int parameterId, float value) {
    if (parameterId != 0) {
        return;
    }

    pendingAmount_.store(
        clamp(sanitize(value), 0.0f, 1.0f),
        std::memory_order_relaxed
    );
    parametersDirty_.store(true, std::memory_order_release);
}

void StereoWidthProcessor::setSampleRate(int sampleRate) {
    if (sampleRate <= 0 || sampleRate == sampleRate_) {
        return;
    }

    sampleRate_ = sampleRate;
    updateCoefficients();
    resetState(true);
    processingTail_.store(
        pendingAmount_.load(std::memory_order_acquire) > kAmountIdleThreshold,
        std::memory_order_release
    );
}

bool StereoWidthProcessor::isEnabled() const {
    return pendingAmount_.load(std::memory_order_acquire) > kAmountIdleThreshold ||
        processingTail_.load(std::memory_order_acquire);
}
