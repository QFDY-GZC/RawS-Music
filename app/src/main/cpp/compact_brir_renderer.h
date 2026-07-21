#pragma once

#include <array>
#include <vector>

/**
 * Compact binaural room renderer.
 *
 * This is a generated BRIR/FDN hybrid intended for real-time mobile playback:
 * - sparse, asymmetric binaural early-reflection taps;
 * - four-line damped feedback-delay network for a short room tail;
 * - bass-safe high-pass injection and linked output normalization.
 *
 * It is not a measured room dataset. The class boundary is deliberately small so
 * a measured BRIR convolution engine can replace it later without changing JNI.
 */
class CompactBrirRenderer {
public:
    CompactBrirRenderer();

    void setSampleRate(int sampleRate);
    void setEnabled(bool enabled);
    void setAmount(float amount);
    void reset();
    void process(float inputLeft, float inputRight, float& outputLeft, float& outputRight);

private:
    struct ReflectionTap {
        int delayLeft = 1;
        int delayRight = 1;
        float ll = 0.0f;
        float lr = 0.0f;
        float rl = 0.0f;
        float rr = 0.0f;
    };

    static float clamp(float value, float minimum, float maximum);
    static int delaySamples(float seconds, int sampleRate, int maximum);
    static int wrapIndex(int index, int size);
    static float onePoleCoefficient(float cutoffHz, float sampleRate);

    float readInput(const std::vector<float>& line, int delay) const;
    float readFdn(int line, int delay) const;
    void rebuildStorage();
    void updateTaps();

    int sampleRate_ = 48000;
    bool enabled_ = true;
    float amount_ = 0.18f;
    float currentAmount_ = 0.18f;
    float smoothingCoefficient_ = 0.001f;
    float highPassCoefficient_ = 0.98f;
    float dampingCoefficient_ = 0.1f;

    std::vector<float> inputDelayLeft_;
    std::vector<float> inputDelayRight_;
    int inputWriteIndex_ = 0;
    std::array<ReflectionTap, 8> taps_{};

    std::array<std::vector<float>, 4> fdnDelay_{};
    std::array<int, 4> fdnWriteIndex_{};
    std::array<int, 4> fdnDelaySamples_{};
    std::array<float, 4> fdnDampingState_{};

    float highPassPreviousInputLeft_ = 0.0f;
    float highPassPreviousInputRight_ = 0.0f;
    float highPassPreviousOutputLeft_ = 0.0f;
    float highPassPreviousOutputRight_ = 0.0f;
};
