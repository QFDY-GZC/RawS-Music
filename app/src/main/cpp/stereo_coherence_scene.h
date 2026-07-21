#pragma once

#include <array>
#include <vector>

/**
 * Multiband stereo scene separator.
 *
 * It combines signed inter-channel correlation, Mid/Side energy dominance,
 * transient protection and band-dependent masks to produce seven virtual sources:
 * front centre, front left/right, surround left/right and height left/right.
 */
class StereoCoherenceScene {
public:
    static constexpr int kSourceCount = 7;

    enum SourceIndex {
        FrontCentre = 0,
        FrontLeft = 1,
        FrontRight = 2,
        SurroundLeft = 3,
        SurroundRight = 4,
        HeightLeft = 5,
        HeightRight = 6,
    };

    struct Frame {
        std::array<float, kSourceCount> source{};
        float coherence = 1.0f;
        float diffuseness = 0.0f;
        float vocalConfidence = 0.0f;
        float transient = 0.0f;
    };

    StereoCoherenceScene();

    void setSampleRate(int sampleRate);
    void reset();
    Frame analyse(float left, float right, float intensity, float room, float separation);

private:
    struct BandState {
        float energyL = 1.0e-7f;
        float energyR = 1.0e-7f;
        float cross = 0.0f;
        float midEnergy = 1.0e-7f;
        float sideEnergy = 1.0e-7f;
        float fastEnergy = 1.0e-7f;
        float slowEnergy = 1.0e-7f;
    };

    static float clamp(float value, float minimum, float maximum);
    static float smoothStep(float value);
    static float sanitize(float value);
    static float onePoleCoefficient(float cutoffHz, float sampleRate);
    static int delaySamples(float seconds, int sampleRate, int maximum);

    float readDelay(const std::vector<float>& line, int delay) const;
    void rebuildDelayLines();

    int sampleRate_ = 48000;
    int writeIndex_ = 0;

    std::array<float, 3> crossoverCoefficient_{};
    std::array<float, 3> lowPassL_{};
    std::array<float, 3> lowPassR_{};
    std::array<BandState, 4> bandState_{};
    float envelopeMemory_ = 0.995f;
    float fastEnvelopeMemory_ = 0.96f;
    float slowEnvelopeMemory_ = 0.998f;

    std::vector<float> diffuseDelay_;
    std::vector<float> heightDelay_;
    int diffuseTapA_ = 130;
    int diffuseTapB_ = 270;
    int heightTapA_ = 360;
    int heightTapB_ = 560;
};
