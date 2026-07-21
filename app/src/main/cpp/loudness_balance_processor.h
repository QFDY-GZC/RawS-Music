#pragma once

#include <atomic>

class LoudnessBalanceProcessor {
public:
    void setSampleRate(int sampleRate);
    void setEnabled(bool enabled);
    void setParameters(float loudnessPercent, float balance);
    bool isEnabled() const;
    void process(float* samples, int numFrames, int channels);
    void reset();

private:
    void applyPendingParameters();

    std::atomic<bool> pendingEnabled_{false};
    std::atomic<float> pendingLoudness_{35.0f};
    std::atomic<float> pendingBalance_{0.0f};
    std::atomic<bool> parametersDirty_{true};

    bool enabled_ = false;
    int sampleRate_ = 44100;
    float targetLoudness_ = 0.35f;
    float smoothedLoudness_ = 0.0f;
    float targetBalance_ = 0.0f;
    float smoothedBalance_ = 0.0f;
    float lowState_[2]{};
    float highLowState_[2]{};
};
