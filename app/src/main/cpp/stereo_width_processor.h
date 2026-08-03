#pragma once

#include <atomic>

/**
 * Continuous full-band stereo expansion.
 *
 * Public amount is 0.0 .. 1.0 and maps linearly to a Side multiplier of
 * 1.0 .. 3.0 while leaving Mid unchanged:
 *
 *   M = (L + R) / 2
 *   L' = M + (L - M) * (1 + 2 * amount)
 *   R' = M + (R - M) * (1 + 2 * amount)
 *
 * Amount 0 is an exact bypass. Peak management remains owned by the global
 * output limiter later in the DSP chain; this processor does not normalize,
 * smooth, clip, delay or decorrelate the signal.
 */
class StereoWidthProcessor {
public:
    void process(float* samples, int numFrames, int channels);
    void setParameter(int parameterId, float value);
    void setSampleRate(int sampleRate);
    bool isEnabled() const;

private:
    static float sanitizeAmount(float value);

    std::atomic<float> pendingAmount_{0.0f};
};
