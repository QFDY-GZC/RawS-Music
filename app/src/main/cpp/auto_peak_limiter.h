#pragma once

#include <atomic>

/**
 * Linked-channel peak limiter for the floating-point DSP path.
 *
 * The envelope deliberately uses fixed linear steps rather than time constants:
 * scalar attack/release are 1/128 and 1/8192 per frame, while the ARM NEON path
 * uses 1/64 and 1/4096 per four-frame block. This preserves the original DSP's
 * behaviour, including its sample-rate-dependent envelope timing.
 */
class AutoPeakLimiter {
public:
    void setSampleRate(int sampleRate);
    void setEnabled(bool enabled);
    bool isEnabled() const;

    /**
     * Selects the float extended-dynamic-range ceiling. Fixed PCM uses 1.0;
     * extended float uses 1 / masterGain so a later master stage remains safe.
     */
    void setFloatExtendedDynamicRange(bool enabled);
    void setMasterGainLinear(float gain);

    void process(float* samples, int numFrames, int channels);
    float getCurrentGainReductionDb() const;

private:
    void applyPendingState();
    void resetState();
    float resolvedCeiling() const;

    int m_sampleRate = 44100;
    bool m_enabled = false;
    bool m_floatExtendedDynamicRange = false;
    float m_masterGainLinear = 1.0f;
    float m_currentGain = 1.0f;
    float m_targetGain = 1.0f;

    std::atomic<bool> m_pendingEnabled{false};
    std::atomic<bool> m_pendingFloatExtendedDynamicRange{false};
    std::atomic<float> m_pendingMasterGainLinear{1.0f};
    std::atomic<bool> m_stateDirty{false};
    std::atomic<bool> m_resetPending{false};
    std::atomic<float> m_currentGainReductionDb{0.0f};
};
