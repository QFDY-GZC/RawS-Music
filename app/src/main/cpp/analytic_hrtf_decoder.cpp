#include "analytic_hrtf_decoder.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kDegreesToRadians = kPi / 180.0f;
}

AnalyticHrtfDecoder::AnalyticHrtfDecoder() {
    configureSpeakers();
    setSampleRate(sampleRate_);
}

float AnalyticHrtfDecoder::clamp(float value, float minimum, float maximum) {
    return std::max(minimum, std::min(maximum, value));
}

float AnalyticHrtfDecoder::onePoleCoefficient(float cutoffHz, float sampleRate) {
    const float safeRate = std::max(8000.0f, sampleRate);
    const float safeCutoff = clamp(cutoffHz, 5.0f, safeRate * 0.45f);
    return 1.0f - std::exp(-2.0f * kPi * safeCutoff / safeRate);
}

int AnalyticHrtfDecoder::delaySamples(float seconds, int sampleRate, int maximum) {
    const int result = static_cast<int>(std::lround(seconds * static_cast<float>(sampleRate)));
    return std::max(1, std::min(maximum, result));
}

void AnalyticHrtfDecoder::configureSpeakers() {
    constexpr std::array<std::array<float, 2>, kSpeakerCount> directions = {{
        {{   0.0f,  0.0f }},
        {{ -45.0f,  0.0f }},
        {{  45.0f,  0.0f }},
        {{ -90.0f,  0.0f }},
        {{  90.0f,  0.0f }},
        {{-135.0f,  0.0f }},
        {{ 135.0f,  0.0f }},
        {{ 180.0f,  0.0f }},
        {{ -45.0f, 42.0f }},
        {{  45.0f, 42.0f }},
        {{-125.0f, 32.0f }},
        {{ 125.0f, 32.0f }},
    }};

    constexpr std::array<float, SecondOrderAmbisonicsEncoder::kChannelCount> orderWeight = {
        0.64f,
        0.84f, 0.84f, 0.84f,
        0.50f, 0.50f, 0.50f, 0.50f, 0.50f,
    };

    for (int index = 0; index < kSpeakerCount; ++index) {
        SpeakerState& speaker = speakers_[static_cast<size_t>(index)];
        speaker.azimuthDegrees = directions[static_cast<size_t>(index)][0];
        speaker.elevationDegrees = directions[static_cast<size_t>(index)][1];
        const auto harmonics = SecondOrderAmbisonicsEncoder::sphericalHarmonics(
            speaker.azimuthDegrees,
            speaker.elevationDegrees
        );
        for (int channel = 0; channel < SecondOrderAmbisonicsEncoder::kChannelCount; ++channel) {
            speaker.decodeCoefficient[static_cast<size_t>(channel)] =
                harmonics[static_cast<size_t>(channel)] *
                orderWeight[static_cast<size_t>(channel)] *
                0.235f;
        }
    }
}

void AnalyticHrtfDecoder::configureSpeaker(SpeakerState& speaker, bool allocateStorage) {
    const float azimuth = speaker.azimuthDegrees * kDegreesToRadians;
    const float elevation = speaker.elevationDegrees * kDegreesToRadians;
    const float absolutePan = std::fabs(std::sin(azimuth) * std::cos(elevation));
    const float front = 0.5f * (1.0f + std::cos(azimuth));
    const float rear = 1.0f - front;
    const float height = clamp(std::sin(std::max(0.0f, elevation)), 0.0f, 1.0f);
    speaker.pan = std::sin(azimuth) * std::cos(elevation);

    // Allocate once for the largest supported head/pinna configuration. Runtime
    // personalisation only changes taps and gains at an audio-block boundary.
    const int lineSize = std::max(96, static_cast<int>(std::ceil(sampleRate_ * 0.0052f)) + 24);
    if (allocateStorage || static_cast<int>(speaker.delayLine.size()) != lineSize) {
        speaker.delayLine.assign(static_cast<size_t>(lineSize), 0.0f);
        speaker.writeIndex = 0;
    }
    const int maximum = lineSize - 2;

    const float headScale = clamp(headSizeCentimeters_ / 57.0f, 0.82f, 1.22f);
    const float pinnaScale = 0.68f + 0.72f * pinnaDetail_;
    speaker.itdDelay = delaySamples(
        (0.00078f * headScale * absolutePan + 0.000025f) * (0.94f + 0.10f * pinnaDetail_),
        sampleRate_,
        maximum
    );
    speaker.pinnaTapA = delaySamples(
        (0.00015f + 0.00013f * height) * pinnaScale,
        sampleRate_,
        maximum
    );
    speaker.pinnaTapB = delaySamples(
        (0.00048f + 0.00034f * rear) * pinnaScale,
        sampleRate_,
        maximum
    );
    speaker.pinnaTapC = delaySamples(
        (0.00092f + 0.00021f * front + 0.00018f * height) * pinnaScale,
        sampleRate_,
        maximum
    );
    speaker.rearTap = delaySamples(
        (0.00112f + 0.00048f * rear) * (0.92f + 0.18f * pinnaDetail_),
        sampleRate_,
        maximum
    );

    const float farCutoff =
        (6500.0f - 4860.0f * absolutePan) / (0.91f + 0.17f * headScale);
    speaker.farLowPassCoefficient = onePoleCoefficient(
        farCutoff,
        static_cast<float>(sampleRate_)
    );
    speaker.elevationLowPassCoefficient = onePoleCoefficient(
        5900.0f - 1200.0f * pinnaDetail_,
        static_cast<float>(sampleRate_)
    );

    speaker.nearGain = 0.68f + (0.33f + 0.10f * headScale) * absolutePan;
    speaker.farGain = 0.69f - (0.39f + 0.09f * headScale) * absolutePan;
    speaker.pinnaGainA = (0.075f + 0.22f * height + 0.055f * front) * pinnaScale;
    speaker.pinnaGainB = -(0.055f + 0.18f * rear + 0.08f * height) * pinnaScale;
    speaker.pinnaGainC = (0.035f + 0.10f * front - 0.045f * rear) * pinnaScale;
    speaker.rearGain = (0.08f + 0.10f * pinnaDetail_) * rear;
    speaker.elevationGain = (0.14f + 0.24f * pinnaDetail_) * height;
    speaker.farLow = 0.0f;
    speaker.elevationLow = 0.0f;
}

void AnalyticHrtfDecoder::setSampleRate(int sampleRate) {
    if (sampleRate <= 0) return;
    sampleRate_ = sampleRate;
    for (SpeakerState& speaker : speakers_) configureSpeaker(speaker, true);
}

void AnalyticHrtfDecoder::setPersonalization(
    float headSizeCentimeters,
    float pinnaDetailPercent
) {
    const float nextHead = clamp(
        std::isfinite(headSizeCentimeters) ? headSizeCentimeters : 57.0f,
        48.0f,
        68.0f
    );
    const float nextPinna = clamp(
        std::isfinite(pinnaDetailPercent) ? pinnaDetailPercent * 0.01f : 0.50f,
        0.0f,
        1.0f
    );
    if (std::fabs(nextHead - headSizeCentimeters_) < 0.001f &&
        std::fabs(nextPinna - pinnaDetail_) < 0.0001f) {
        return;
    }
    headSizeCentimeters_ = nextHead;
    pinnaDetail_ = nextPinna;
    for (SpeakerState& speaker : speakers_) configureSpeaker(speaker, false);
}

float AnalyticHrtfDecoder::readDelay(const SpeakerState& speaker, int delay) {
    if (speaker.delayLine.empty()) return 0.0f;
    int index = speaker.writeIndex - delay;
    while (index < 0) index += static_cast<int>(speaker.delayLine.size());
    return speaker.delayLine[static_cast<size_t>(index)];
}

void AnalyticHrtfDecoder::decode(
    const std::array<float, SecondOrderAmbisonicsEncoder::kChannelCount>& bus,
    float intensity,
    float& left,
    float& right
) {
    intensity = clamp(intensity, 0.0f, 1.0f);
    const float highDriveInput = clamp((intensity - 0.50f) / 0.50f, 0.0f, 1.0f);
    const float highDrive = highDriveInput * highDriveInput * (3.0f - 2.0f * highDriveInput);

    float sumLeft = 0.0f;
    float sumRight = 0.0f;
    for (SpeakerState& speaker : speakers_) {
        float feed = 0.0f;
        for (int channel = 0; channel < SecondOrderAmbisonicsEncoder::kChannelCount; ++channel) {
            feed += bus[static_cast<size_t>(channel)] *
                speaker.decodeCoefficient[static_cast<size_t>(channel)];
        }

        speaker.delayLine[static_cast<size_t>(speaker.writeIndex)] = feed;
        const float pinna =
            feed +
            readDelay(speaker, speaker.pinnaTapA) * speaker.pinnaGainA +
            readDelay(speaker, speaker.pinnaTapB) * speaker.pinnaGainB +
            readDelay(speaker, speaker.pinnaTapC) * speaker.pinnaGainC -
            readDelay(speaker, speaker.rearTap) * speaker.rearGain;

        speaker.elevationLow += speaker.elevationLowPassCoefficient * (feed - speaker.elevationLow);
        const float elevationHigh = feed - speaker.elevationLow;
        const float nearSignal = pinna +
            elevationHigh * speaker.elevationGain * (0.62f + 0.66f * highDrive);

        const float farInput = readDelay(speaker, speaker.itdDelay);
        speaker.farLow += speaker.farLowPassCoefficient * (farInput - speaker.farLow);
        const float farSignal = speaker.farLow;

        const float nearGain = speaker.nearGain * (0.92f + 0.35f * highDrive);
        const float farGain = speaker.farGain * (1.0f - 0.40f * highDrive);
        if (speaker.pan < -0.0001f) {
            sumLeft += nearSignal * nearGain;
            sumRight += farSignal * farGain;
        } else if (speaker.pan > 0.0001f) {
            sumRight += nearSignal * nearGain;
            sumLeft += farSignal * farGain;
        } else {
            sumLeft += nearSignal * 0.70f;
            sumRight += nearSignal * 0.70f;
        }

        ++speaker.writeIndex;
        if (speaker.writeIndex >= static_cast<int>(speaker.delayLine.size())) speaker.writeIndex = 0;
    }

    const float decoderGain = 0.42f + 0.09f * highDrive;
    left = sumLeft * decoderGain;
    right = sumRight * decoderGain;
}

void AnalyticHrtfDecoder::reset() {
    for (SpeakerState& speaker : speakers_) {
        std::fill(speaker.delayLine.begin(), speaker.delayLine.end(), 0.0f);
        speaker.writeIndex = 0;
        speaker.farLow = 0.0f;
        speaker.elevationLow = 0.0f;
    }
}
