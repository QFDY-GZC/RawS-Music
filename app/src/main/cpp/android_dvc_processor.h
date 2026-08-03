#pragma once

#include <atomic>

class AndroidDvcProcessor {
public:
    void setSampleRate(int sampleRate);
    void setEnabled(bool enabled);
    void setTargetGain(float gain);
    void setNoDvcHeadroomDb(float gainDb);
    bool isEnabled() const;
    void process(float* samples, int sampleCount);

private:
    std::atomic<bool> pendingEnabled_{false};
    std::atomic<float> pendingTargetGain_{1.0f};
    std::atomic<float> pendingNoDvcGain_{1.0f};
    float currentGain_ = 1.0f;
    int sampleRate_ = 44100;
};
