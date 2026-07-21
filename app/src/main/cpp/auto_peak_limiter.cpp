#include "auto_peak_limiter.h"

#include <algorithm>
#include <cmath>
#if defined(__ARM_NEON) || defined(__ARM_NEON__)
#include <arm_neon.h>
#endif

namespace {
constexpr float kFixedCeiling = 1.0f;
constexpr float kScalarAttackStep = 1.0f / 128.0f;
constexpr float kScalarReleaseStep = 1.0f / 8192.0f;
constexpr float kNeonAttackStep = 1.0f / 64.0f;
constexpr float kNeonReleaseStep = 1.0f / 4096.0f;
constexpr float kSilentMasterGain = 0.001f;
constexpr float kSilentDynamicCeiling = 1000.0f;
constexpr float kEpsilon = 1.0e-12f;

inline void updateEnvelope(
    float peak,
    float ceiling,
    float attackStep,
    float releaseStep,
    float& targetGain,
    float& currentGain
) {
    if (peak > ceiling) {
        targetGain = std::min(targetGain, ceiling / std::max(peak, kEpsilon));
    } else if (targetGain < 1.0f) {
        targetGain = std::min(1.0f, targetGain + releaseStep);
    }

    if (currentGain > targetGain) {
        currentGain = std::max(targetGain, currentGain - attackStep);
    } else if (currentGain < targetGain) {
        currentGain = std::min(targetGain, currentGain + releaseStep);
    }
}
}

void AutoPeakLimiter::setSampleRate(int sampleRate) {
    if (sampleRate <= 0) return;
    if (sampleRate == m_sampleRate) return;
    m_sampleRate = sampleRate;
    m_resetPending.store(true, std::memory_order_release);
}

void AutoPeakLimiter::setEnabled(bool enabled) {
    const bool previous = m_pendingEnabled.exchange(enabled, std::memory_order_acq_rel);
    if (previous != enabled) {
        m_resetPending.store(true, std::memory_order_release);
        m_stateDirty.store(true, std::memory_order_release);
        if (!enabled) {
            m_currentGainReductionDb.store(0.0f, std::memory_order_release);
        }
    }
}

bool AutoPeakLimiter::isEnabled() const {
    return m_pendingEnabled.load(std::memory_order_acquire);
}

void AutoPeakLimiter::setFloatExtendedDynamicRange(bool enabled) {
    const bool previous = m_pendingFloatExtendedDynamicRange.exchange(
        enabled,
        std::memory_order_acq_rel
    );
    if (previous != enabled) {
        m_stateDirty.store(true, std::memory_order_release);
    }
}

void AutoPeakLimiter::setMasterGainLinear(float gain) {
    const float safeGain = std::isfinite(gain)
        ? std::max(0.0f, std::min(gain, 1000.0f))
        : 1.0f;
    const float previous = m_pendingMasterGainLinear.exchange(
        safeGain,
        std::memory_order_acq_rel
    );
    if (previous != safeGain) {
        m_stateDirty.store(true, std::memory_order_release);
    }
}

void AutoPeakLimiter::process(float* samples, int numFrames, int channels) {
    applyPendingState();
    if (!m_enabled || samples == nullptr || numFrames <= 0 || channels <= 0) return;

    float currentGain = m_currentGain;
    float targetGain = m_targetGain;
    const float ceiling = resolvedCeiling();
    int frame = 0;

#if defined(__ARM_NEON) || defined(__ARM_NEON__)
    if (channels == 2) {
        for (; frame + 4 <= numFrames; frame += 4) {
            float* block = samples + frame * 2;
            float32x4_t first = vld1q_f32(block);
            float32x4_t second = vld1q_f32(block + 4);
#if defined(__aarch64__)
            const float peak = vmaxnmvq_f32(
                vmaxnmq_f32(vabsq_f32(first), vabsq_f32(second))
            );
#else
            const float32x4_t maxima = vmaxq_f32(vabsq_f32(first), vabsq_f32(second));
            const float32x2_t pairMax = vpmax_f32(vget_low_f32(maxima), vget_high_f32(maxima));
            const float32x2_t blockMax = vpmax_f32(pairMax, pairMax);
            const float peak = vget_lane_f32(blockMax, 0);
#endif
            updateEnvelope(
                peak,
                ceiling,
                kNeonAttackStep,
                kNeonReleaseStep,
                targetGain,
                currentGain
            );
            if (currentGain != 1.0f) {
                first = vmulq_n_f32(first, currentGain);
                second = vmulq_n_f32(second, currentGain);
                vst1q_f32(block, first);
                vst1q_f32(block + 4, second);
            }
        }
    }
#endif

    for (; frame < numFrames; ++frame) {
        float peak = 0.0f;
        const int base = frame * channels;
        for (int channel = 0; channel < channels; ++channel) {
            const float sample = samples[base + channel];
            if (!std::isfinite(sample)) continue;
            peak = std::max(peak, std::fabs(sample));
        }

        updateEnvelope(
            peak,
            ceiling,
            kScalarAttackStep,
            kScalarReleaseStep,
            targetGain,
            currentGain
        );
        for (int channel = 0; channel < channels; ++channel) {
            float sample = samples[base + channel];
            if (!std::isfinite(sample)) sample = 0.0f;
            samples[base + channel] = sample * currentGain;
        }
    }

    m_currentGain = currentGain;
    m_targetGain = targetGain;
    const float reductionDb = currentGain < 1.0f
        ? -20.0f * std::log10(std::max(currentGain, kEpsilon))
        : 0.0f;
    m_currentGainReductionDb.store(reductionDb, std::memory_order_release);
}

float AutoPeakLimiter::getCurrentGainReductionDb() const {
    return m_currentGainReductionDb.load(std::memory_order_acquire);
}

void AutoPeakLimiter::applyPendingState() {
    if (m_stateDirty.exchange(false, std::memory_order_acq_rel)) {
        m_enabled = m_pendingEnabled.load(std::memory_order_acquire);
        m_floatExtendedDynamicRange =
            m_pendingFloatExtendedDynamicRange.load(std::memory_order_acquire);
        m_masterGainLinear = m_pendingMasterGainLinear.load(std::memory_order_acquire);
    }
    if (m_resetPending.exchange(false, std::memory_order_acq_rel)) {
        resetState();
    }
}

void AutoPeakLimiter::resetState() {
    m_currentGain = 1.0f;
    m_targetGain = 1.0f;
    m_currentGainReductionDb.store(0.0f, std::memory_order_release);
}

float AutoPeakLimiter::resolvedCeiling() const {
    if (!m_floatExtendedDynamicRange) return kFixedCeiling;
    return m_masterGainLinear > kSilentMasterGain
        ? 1.0f / m_masterGainLinear
        : kSilentDynamicCeiling;
}
