#pragma once

#include <atomic>

class ExperimentalGainProcessor {
public:
    void setEnabled(bool enabled);
    void setGainDb(float gainDb);
    bool isEnabled() const;
    float getGainDb() const;
    void process(float* samples, int sampleCount);

private:
    static float softClip(float sample);

    std::atomic<bool> m_enabled{false};
    std::atomic<float> m_gainDb{0.0f};
};
