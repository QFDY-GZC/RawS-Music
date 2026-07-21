#include "stereo_coherence_scene.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPi = 3.14159265358979323846f;
}

StereoCoherenceScene::StereoCoherenceScene() {
    setSampleRate(sampleRate_);
}

float StereoCoherenceScene::clamp(float value, float minimum, float maximum) {
    return std::max(minimum, std::min(maximum, value));
}

float StereoCoherenceScene::smoothStep(float value) {
    const float x = clamp(value, 0.0f, 1.0f);
    return x * x * (3.0f - 2.0f * x);
}

float StereoCoherenceScene::sanitize(float value) {
    return std::isfinite(value) ? value : 0.0f;
}

float StereoCoherenceScene::onePoleCoefficient(float cutoffHz, float sampleRate) {
    const float safeRate = std::max(8000.0f, sampleRate);
    const float safeCutoff = clamp(cutoffHz, 5.0f, safeRate * 0.45f);
    return 1.0f - std::exp(-2.0f * kPi * safeCutoff / safeRate);
}

int StereoCoherenceScene::delaySamples(float seconds, int sampleRate, int maximum) {
    const int result = static_cast<int>(std::lround(seconds * static_cast<float>(sampleRate)));
    return std::max(1, std::min(maximum, result));
}

void StereoCoherenceScene::rebuildDelayLines() {
    const int lineSize = std::max(96, static_cast<int>(std::ceil(sampleRate_ * 0.018f)) + 16);
    diffuseDelay_.assign(static_cast<size_t>(lineSize), 0.0f);
    heightDelay_.assign(static_cast<size_t>(lineSize), 0.0f);
    writeIndex_ = 0;

    const int maximum = lineSize - 2;
    diffuseTapA_ = delaySamples(0.0029f, sampleRate_, maximum);
    diffuseTapB_ = delaySamples(0.0061f, sampleRate_, maximum);
    heightTapA_ = delaySamples(0.0087f, sampleRate_, maximum);
    heightTapB_ = delaySamples(0.0137f, sampleRate_, maximum);
}

void StereoCoherenceScene::setSampleRate(int sampleRate) {
    if (sampleRate <= 0) return;
    sampleRate_ = sampleRate;
    const float rate = static_cast<float>(sampleRate_);

    crossoverCoefficient_[0] = onePoleCoefficient(230.0f, rate);
    crossoverCoefficient_[1] = onePoleCoefficient(1250.0f, rate);
    crossoverCoefficient_[2] = onePoleCoefficient(5200.0f, rate);
    envelopeMemory_ = std::exp(-1.0f / (0.034f * rate));
    fastEnvelopeMemory_ = std::exp(-1.0f / (0.0045f * rate));
    slowEnvelopeMemory_ = std::exp(-1.0f / (0.095f * rate));

    rebuildDelayLines();
    reset();
}

float StereoCoherenceScene::readDelay(const std::vector<float>& line, int delay) const {
    if (line.empty()) return 0.0f;
    int index = writeIndex_ - delay;
    while (index < 0) index += static_cast<int>(line.size());
    return line[static_cast<size_t>(index)];
}

StereoCoherenceScene::Frame StereoCoherenceScene::analyse(
    float left,
    float right,
    float intensity,
    float room,
    float separation
) {
    Frame result;
    left = sanitize(left);
    right = sanitize(right);
    intensity = clamp(intensity, 0.0f, 1.0f);
    room = clamp(room, 0.0f, 1.0f);
    separation = clamp(separation, 0.0f, 1.0f);

    for (int i = 0; i < 3; ++i) {
        lowPassL_[static_cast<size_t>(i)] += crossoverCoefficient_[static_cast<size_t>(i)] *
            (left - lowPassL_[static_cast<size_t>(i)]);
        lowPassR_[static_cast<size_t>(i)] += crossoverCoefficient_[static_cast<size_t>(i)] *
            (right - lowPassR_[static_cast<size_t>(i)]);
    }

    const std::array<float, 4> bandL = {
        lowPassL_[0],
        lowPassL_[1] - lowPassL_[0],
        lowPassL_[2] - lowPassL_[1],
        left - lowPassL_[2],
    };
    const std::array<float, 4> bandR = {
        lowPassR_[0],
        lowPassR_[1] - lowPassR_[0],
        lowPassR_[2] - lowPassR_[1],
        right - lowPassR_[2],
    };

    constexpr std::array<float, 4> centreThresholdLow = { -0.35f, -0.05f, 0.08f, 0.18f };
    constexpr std::array<float, 4> centreThresholdHigh = { 0.38f, 0.55f, 0.68f, 0.78f };
    constexpr std::array<float, 4> centreBandGain = { 1.08f, 1.00f, 0.91f, 0.72f };
    constexpr std::array<float, 4> frontBandGain = { 0.46f, 0.88f, 1.06f, 1.10f };
    constexpr std::array<float, 4> surroundBandGain = { 0.00f, 0.22f, 0.78f, 1.00f };
    constexpr std::array<float, 4> heightBandGain = { 0.00f, 0.00f, 0.28f, 1.00f };

    const float envelopeInput = 1.0f - envelopeMemory_;
    const float highDrive = smoothStep((intensity - 0.50f) / 0.50f);

    float frontCentre = 0.0f;
    float frontLeft = 0.0f;
    float frontRight = 0.0f;
    float diffuse = 0.0f;
    float heightDiffuse = 0.0f;
    float weightedCoherence = 0.0f;
    float coherenceWeight = 0.0f;
    float weightedVocal = 0.0f;
    float vocalWeight = 0.0f;
    float weightedTransient = 0.0f;

    for (int band = 0; band < 4; ++band) {
        const float l = bandL[static_cast<size_t>(band)];
        const float r = bandR[static_cast<size_t>(band)];
        BandState& state = bandState_[static_cast<size_t>(band)];
        state.energyL = envelopeMemory_ * state.energyL + envelopeInput * l * l;
        state.energyR = envelopeMemory_ * state.energyR + envelopeInput * r * r;
        state.cross = envelopeMemory_ * state.cross + envelopeInput * l * r;

        const float mid = 0.5f * (l + r);
        const float side = 0.5f * (l - r);
        state.midEnergy = envelopeMemory_ * state.midEnergy + envelopeInput * mid * mid;
        state.sideEnergy = envelopeMemory_ * state.sideEnergy + envelopeInput * side * side;
        const float instantaneousEnergy = l * l + r * r;
        state.fastEnergy = fastEnvelopeMemory_ * state.fastEnergy +
            (1.0f - fastEnvelopeMemory_) * instantaneousEnergy;
        state.slowEnergy = slowEnvelopeMemory_ * state.slowEnergy +
            (1.0f - slowEnvelopeMemory_) * instantaneousEnergy;

        const float denominator = std::sqrt(std::max(1.0e-12f, state.energyL * state.energyR));
        const float signedCorrelation = clamp(state.cross / denominator, -1.0f, 1.0f);
        const float coherence = clamp(std::fabs(state.cross) / denominator, 0.0f, 1.0f);
        const float correlationMask = smoothStep(
            (signedCorrelation - centreThresholdLow[static_cast<size_t>(band)]) /
            (centreThresholdHigh[static_cast<size_t>(band)] - centreThresholdLow[static_cast<size_t>(band)])
        );
        const float midDominance = state.midEnergy /
            std::max(1.0e-10f, state.midEnergy + state.sideEnergy);
        const float dominanceMask = smoothStep((midDominance - 0.48f) / 0.43f);
        const float transientRatio = state.fastEnergy /
            std::max(1.0e-10f, state.slowEnergy);
        const float transientMask = smoothStep((transientRatio - 1.32f) / 2.2f);
        const float vocalBand = (band == 1 || band == 2) ? 1.0f : 0.28f;
        const float vocalMask = correlationMask * dominanceMask * vocalBand;
        const float centreMask = clamp(
            correlationMask * (0.72f + 0.28f * dominanceMask) +
                vocalMask * 0.20f * separation,
            0.0f,
            1.0f
        );

        const float centreComponent = mid * centreMask;
        const float residualL = l - centreComponent;
        const float residualR = r - centreComponent;
        // Drum attacks and other sharp transients remain near the front instead
        // of being smeared into rear/height channels.
        const float diffuseMask = (1.0f - centreMask) *
            (1.0f - transientMask * (0.44f + 0.42f * separation));

        frontCentre += centreComponent * centreBandGain[static_cast<size_t>(band)];
        frontLeft += residualL * frontBandGain[static_cast<size_t>(band)];
        frontRight += residualR * frontBandGain[static_cast<size_t>(band)];
        diffuse += side * diffuseMask * surroundBandGain[static_cast<size_t>(band)];
        heightDiffuse += side * diffuseMask * heightBandGain[static_cast<size_t>(band)];

        const float bandEnergy = state.energyL + state.energyR;
        weightedCoherence += coherence * bandEnergy;
        coherenceWeight += bandEnergy;
        weightedVocal += vocalMask * bandEnergy;
        vocalWeight += bandEnergy;
        weightedTransient += transientMask * bandEnergy;
    }

    const float averageCoherence = coherenceWeight > 1.0e-10f
        ? clamp(weightedCoherence / coherenceWeight, 0.0f, 1.0f)
        : 1.0f;
    const float diffuseness = clamp(1.0f - averageCoherence, 0.0f, 1.0f);
    const float vocalConfidence = vocalWeight > 1.0e-10f
        ? clamp(weightedVocal / vocalWeight, 0.0f, 1.0f)
        : 0.0f;
    const float transient = coherenceWeight > 1.0e-10f
        ? clamp(weightedTransient / coherenceWeight, 0.0f, 1.0f)
        : 0.0f;

    diffuseDelay_[static_cast<size_t>(writeIndex_)] = diffuse;
    heightDelay_[static_cast<size_t>(writeIndex_)] = heightDiffuse;

    const float diffuseA = readDelay(diffuseDelay_, diffuseTapA_);
    const float diffuseB = readDelay(diffuseDelay_, diffuseTapB_);
    const float heightA = readDelay(heightDelay_, heightTapA_);
    const float heightB = readDelay(heightDelay_, heightTapB_);

    // Source drive becomes intentionally non-linear above 65%. At 100% the
    // scene is no longer a subtle width effect: side/rear/height objects receive
    // enough energy for a clearly externalised presentation.
    const float separationDrive = 0.64f + 0.58f * separation;
    const float frontDrive = (0.88f + 0.40f * intensity + 0.32f * highDrive) *
        (0.92f + 0.14f * separation);
    const float centreDrive = (0.94f - 0.10f * highDrive) *
        (0.94f + 0.18f * vocalConfidence * separation);
    const float diffuseDrive =
        (0.22f + 0.78f * intensity + 0.86f * highDrive) *
        (0.56f + 0.72f * diffuseness) *
        (0.78f + 0.52f * room) * separationDrive *
        (1.0f - 0.40f * transient);
    const float heightDrive =
        (0.04f + 0.30f * intensity + 0.96f * highDrive) *
        (0.38f + 0.86f * diffuseness) *
        (0.68f + 0.72f * room) * separationDrive *
        (1.0f - 0.58f * transient);

    result.source[FrontCentre] = frontCentre * centreDrive;
    result.source[FrontLeft] = frontLeft * frontDrive;
    result.source[FrontRight] = frontRight * frontDrive;
    result.source[SurroundLeft] =
        (diffuse * 0.82f + diffuseA * 0.43f - diffuseB * 0.21f) * diffuseDrive;
    result.source[SurroundRight] =
        (-diffuse * 0.82f + diffuseA * 0.43f + diffuseB * 0.21f) * diffuseDrive;
    result.source[HeightLeft] =
        (heightDiffuse * 0.62f + heightA * 0.46f - heightB * 0.24f) * heightDrive;
    result.source[HeightRight] =
        (-heightDiffuse * 0.62f + heightA * 0.46f + heightB * 0.24f) * heightDrive;
    result.coherence = averageCoherence;
    result.diffuseness = diffuseness;
    result.vocalConfidence = vocalConfidence;
    result.transient = transient;

    ++writeIndex_;
    if (writeIndex_ >= static_cast<int>(diffuseDelay_.size())) writeIndex_ = 0;
    return result;
}

void StereoCoherenceScene::reset() {
    lowPassL_.fill(0.0f);
    lowPassR_.fill(0.0f);
    for (BandState& state : bandState_) state = BandState{};
    std::fill(diffuseDelay_.begin(), diffuseDelay_.end(), 0.0f);
    std::fill(heightDelay_.begin(), heightDelay_.end(), 0.0f);
    writeIndex_ = 0;
}
