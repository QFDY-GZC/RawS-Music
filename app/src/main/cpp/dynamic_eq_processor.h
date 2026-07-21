#pragma once

#include <atomic>

class DynamicEqProcessor {
public:
    void setSampleRate(int sampleRate);
    void setEnabled(bool enabled);
    void setParameters(float intensityPercent, float deEsserPercent, float deEsserFrequencyHz);
    bool isEnabled() const;
    void process(float* samples, int numFrames, int channels);
    void reset();

private:
    void applyPendingParameters();

    std::atomic<bool> pendingEnabled_{false};
    std::atomic<float> pendingIntensity_{50.0f};
    std::atomic<float> pendingDeEsser_{45.0f};
    std::atomic<float> pendingDeEsserFrequency_{6500.0f};
    std::atomic<bool> parametersDirty_{true};
    bool enabled_ = false;
    int sampleRate_ = 44100;
    float intensity_ = 0.5f;
    float deEsser_ = 0.45f;
    float deEsserFrequency_ = 6500.0f;
    float bodyLowState_[2]{};
    float presenceLowState_[2]{};
    float presenceHighState_[2]{};
    float deEsserLowState_[2]{};
    float programEnvelope_ = 0.0f;
    float sibilanceEnvelope_ = 0.0f;
    float reductionGain_ = 1.0f;
};
