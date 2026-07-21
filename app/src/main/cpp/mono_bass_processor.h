#pragma once

#include <atomic>

class MonoBassProcessor {
public:
    void setSampleRate(int sampleRate);
    void setEnabled(bool enabled);
    void setParameters(float crossoverHz, float amountPercent);
    bool isEnabled() const;
    void process(float* samples, int numFrames, int channels);
    void reset();

private:
    void applyPendingParameters();

    std::atomic<bool> pendingEnabled_{false};
    std::atomic<float> pendingCrossoverHz_{160.0f};
    std::atomic<float> pendingAmount_{70.0f};
    std::atomic<bool> parametersDirty_{true};
    bool enabled_ = false;
    int sampleRate_ = 44100;
    float crossoverHz_ = 160.0f;
    float targetAmount_ = 0.7f;
    float smoothedAmount_ = 0.0f;
    float lowState_[2]{};
};
