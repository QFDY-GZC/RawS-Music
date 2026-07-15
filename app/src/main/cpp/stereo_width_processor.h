#pragma once

#include <atomic>

/**
 * Continuous full-band stereo expansion.
 *
 * Public amount is 0.0 .. 1.0 and maps linearly to a Side multiplier of
 * 1.0 .. 3.0:
 *
 *   M = (L + R) / 2
 *   S = (L - R) / 2
 *   L' = M + S * (1 + 2 * amount)
 *   R' = M - S * (1 + 2 * amount)
 *
 * This is equivalent to:
 *
 *   L' = L + amount * (L - R)
 *   R' = R - amount * (L - R)
 *
 * A linked stereo peak controller follows the matrix. Both channels always
 * receive the same gain, preserving image position and channel balance.
 */
class StereoWidthProcessor {
public:
    StereoWidthProcessor();

    void process(float* samples, int numFrames, int channels);
    void setParameter(int parameterId, float value);
    void setSampleRate(int sampleRate);
    bool isEnabled() const;

private:
    static float clamp(float value, float minimum, float maximum);
    static float sanitize(float value);
    static float exponentialCoefficient(float milliseconds, float sampleRate);

    void applyPendingParameters();
    void updateCoefficients();
    void resetState(bool resetAmount);

    int sampleRate_ = 44100;

    float targetAmount_ = 0.0f;
    float currentAmount_ = 0.0f;

    float limiterTargetGain_ = 1.0f;
    float limiterGain_ = 1.0f;

    float amountSmoothing_ = 0.0f;
    float limiterAttackStep_ = 0.0f;
    float limiterReleaseStep_ = 0.0f;

    std::atomic<float> pendingAmount_{0.0f};
    std::atomic<bool> parametersDirty_{false};
    std::atomic<bool> processingTail_{false};
};
