#pragma once

#include "second_order_ambisonics.h"

#include <array>
#include <vector>

/**
 * Second-order Ambisonics binaural decoder using a fixed virtual loudspeaker
 * layout followed by a compact, parameterised HRTF model.
 *
 * The head-size and pinna-detail controls provide deterministic parametric
 * personalisation. This is not a measured SOFA profile; the public boundary is
 * kept compatible with a later measured-HRTF convolver.
 */
class AnalyticHrtfDecoder {
public:
    AnalyticHrtfDecoder();

    void setSampleRate(int sampleRate);
    void setPersonalization(float headSizeCentimeters, float pinnaDetailPercent);
    void reset();
    void decode(
        const std::array<float, SecondOrderAmbisonicsEncoder::kChannelCount>& bus,
        float intensity,
        float& left,
        float& right
    );

private:
    static constexpr int kSpeakerCount = 12;

    struct SpeakerState {
        float azimuthDegrees = 0.0f;
        float elevationDegrees = 0.0f;
        std::array<float, SecondOrderAmbisonicsEncoder::kChannelCount> decodeCoefficient{};
        std::vector<float> delayLine;
        int writeIndex = 0;
        int itdDelay = 1;
        int pinnaTapA = 1;
        int pinnaTapB = 2;
        int pinnaTapC = 3;
        int rearTap = 4;
        float farLowPassCoefficient = 0.1f;
        float elevationLowPassCoefficient = 0.1f;
        float farLow = 0.0f;
        float elevationLow = 0.0f;
        float pan = 0.0f;
        float nearGain = 0.7f;
        float farGain = 0.7f;
        float pinnaGainA = 0.1f;
        float pinnaGainB = -0.1f;
        float pinnaGainC = 0.05f;
        float rearGain = 0.0f;
        float elevationGain = 0.0f;
    };

    static float clamp(float value, float minimum, float maximum);
    static float onePoleCoefficient(float cutoffHz, float sampleRate);
    static int delaySamples(float seconds, int sampleRate, int maximum);
    static float readDelay(const SpeakerState& speaker, int delay);

    void configureSpeakers();
    void configureSpeaker(SpeakerState& speaker, bool allocateStorage);

    int sampleRate_ = 48000;
    float headSizeCentimeters_ = 57.0f;
    float pinnaDetail_ = 0.50f;
    std::array<SpeakerState, kSpeakerCount> speakers_{};
};
