#pragma once

#include <atomic>

class MoogLadderFilter {
public:
    enum class Mode : int {
        LowPass24 = 0,
        LowPass12 = 1,
        HighPass24 = 2,
        BandPass12 = 3,
        Notch = 4
    };

    void setSampleRate(int sampleRate);
    void setEnabled(bool enabled);
    void setParameters(
        int mode,
        float cutoffHz,
        float resonancePercent,
        float driveDb,
        float mixPercent);
    bool isEnabled() const;
    void process(float* samples, int numFrames, int channels);
    void reset();

private:
    struct StageResult {
        float input;
        float stage[4];
    };

    void applyPendingParameters();
    StageResult processOversampled(float input, int channel, float g, float resonance);
    float selectOutput(const StageResult& result) const;

    std::atomic<bool> pendingEnabled_{false};
    std::atomic<int> pendingMode_{0};
    std::atomic<float> pendingCutoffHz_{12000.0f};
    std::atomic<float> pendingResonance_{20.0f};
    std::atomic<float> pendingDriveDb_{0.0f};
    std::atomic<float> pendingMix_{100.0f};
    std::atomic<bool> parametersDirty_{true};

    bool enabled_ = false;
    int sampleRate_ = 44100;
    Mode mode_ = Mode::LowPass24;
    float cutoffHz_ = 12000.0f;
    float resonance_ = 0.2f;
    float driveDb_ = 0.0f;
    float targetMix_ = 1.0f;
    float smoothedMix_ = 0.0f;
    float smoothedG_ = 0.0f;
    float smoothedResonance_ = 0.0f;
    float smoothedDriveDb_ = 0.0f;
    float state_[2][4]{};
    float previousInput_[2]{};
};
