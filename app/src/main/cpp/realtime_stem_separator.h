#pragma once

#include <atomic>

/**
 * Low-latency, allocation-free stem emphasis for the active playback PCM stream.
 *
 * This processor intentionally owns no files or persistent cache. It uses stereo
 * coherence and frequency-aware mid/side analysis so it can run inside the audio
 * callback without the multi-second buffering required by the offline ONNX model.
 */
class RealtimeStemSeparator {
public:
    enum class StemMode : int {
        Vocals = 0,
        Instrumental = 1,
    };

    void setSampleRate(int sampleRate);
    void setEnabled(bool enabled);
    void setMode(int mode);
    void setStrength(float strength);

    bool isActive() const;
    void process(float* interleavedSamples, int numFrames, int channels);

private:
    void resetState();

    std::atomic<bool> m_enabled{false};
    std::atomic<int> m_mode{static_cast<int>(StemMode::Vocals)};
    std::atomic<float> m_strength{1.0f};

    int m_sampleRate = 44100;
    float m_wet = 0.0f;
    float m_modeMix = 0.0f;
    float m_centerConfidence = 0.0f;
    float m_lowMid = 0.0f;
    float m_presenceMid = 0.0f;
};
