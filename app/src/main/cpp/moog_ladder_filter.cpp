#include "moog_ladder_filter.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace {
constexpr float kPi = 3.14159265358979323846f;

float dbToLinear(float db) {
    return std::pow(10.0f, db / 20.0f);
}

float saturate(float input, float driveDb) {
    if (driveDb <= 0.01f) return input;
    const float gain = dbToLinear(driveDb);
    return std::tanh(input * gain) / std::tanh(gain);
}
}

void MoogLadderFilter::setSampleRate(int sampleRate) {
    sampleRate_ = std::max(sampleRate, 8000);
    reset();
}

void MoogLadderFilter::setEnabled(bool enabled) {
    pendingEnabled_.store(enabled, std::memory_order_release);
    parametersDirty_.store(true, std::memory_order_release);
}

void MoogLadderFilter::setParameters(
        int mode,
        float cutoffHz,
        float resonancePercent,
        float driveDb,
        float mixPercent) {
    pendingMode_.store(std::clamp(mode, 0, 4), std::memory_order_relaxed);
    pendingCutoffHz_.store(std::clamp(cutoffHz, 20.0f, 20000.0f), std::memory_order_relaxed);
    pendingResonance_.store(std::clamp(resonancePercent, 0.0f, 100.0f), std::memory_order_relaxed);
    pendingDriveDb_.store(std::clamp(driveDb, 0.0f, 18.0f), std::memory_order_relaxed);
    pendingMix_.store(std::clamp(mixPercent, 0.0f, 100.0f), std::memory_order_relaxed);
    parametersDirty_.store(true, std::memory_order_release);
}

bool MoogLadderFilter::isEnabled() const {
    return pendingEnabled_.load(std::memory_order_acquire);
}

void MoogLadderFilter::reset() {
    std::memset(state_, 0, sizeof(state_));
    previousInput_[0] = previousInput_[1] = 0.0f;
    smoothedMix_ = enabled_ ? targetMix_ : 0.0f;
    smoothedResonance_ = resonance_;
    smoothedDriveDb_ = driveDb_;
    const float oversampledRate = static_cast<float>(sampleRate_ * 2);
    const float safeCutoff = std::min(cutoffHz_, oversampledRate * 0.45f);
    const float g = std::tan(kPi * safeCutoff / oversampledRate);
    smoothedG_ = g / (1.0f + g);
}

void MoogLadderFilter::applyPendingParameters() {
    if (!parametersDirty_.exchange(false, std::memory_order_acq_rel)) return;
    const bool nextEnabled = pendingEnabled_.load(std::memory_order_acquire);
    mode_ = static_cast<Mode>(pendingMode_.load(std::memory_order_relaxed));
    cutoffHz_ = pendingCutoffHz_.load(std::memory_order_relaxed);
    resonance_ = pendingResonance_.load(std::memory_order_relaxed) * 0.01f;
    driveDb_ = pendingDriveDb_.load(std::memory_order_relaxed);
    targetMix_ = pendingMix_.load(std::memory_order_relaxed) * 0.01f;
    if (!enabled_ && nextEnabled) reset();
    enabled_ = nextEnabled;
}

MoogLadderFilter::StageResult MoogLadderFilter::processOversampled(
        float input,
        int channel,
        float g,
        float resonance) {
    float* z = state_[channel];
    const float oneMinusG = 1.0f - g;
    const float g2 = g * g;
    const float g3 = g2 * g;
    const float g4 = g2 * g2;
    const float sigma =
        g3 * oneMinusG * z[0] +
        g2 * oneMinusG * z[1] +
        g * oneMinusG * z[2] +
        oneMinusG * z[3];
    const float predictedOutput = (g4 * input + sigma) / (1.0f + resonance * g4);
    float stageInput = input - resonance * predictedOutput;

    StageResult result{};
    result.input = stageInput;
    for (int stage = 0; stage < 4; ++stage) {
        const float v = (stageInput - z[stage]) * g;
        float output = v + z[stage];
        z[stage] = output + v;
        if (!std::isfinite(output) || std::fabs(output) > 16.0f) {
            output = std::tanh(std::isfinite(output) ? output : 0.0f);
            z[stage] = output;
        }
        result.stage[stage] = output;
        stageInput = output;
    }
    return result;
}

float MoogLadderFilter::selectOutput(const StageResult& result) const {
    const float y1 = result.stage[0];
    const float y2 = result.stage[1];
    const float y3 = result.stage[2];
    const float y4 = result.stage[3];
    switch (mode_) {
        case Mode::LowPass12:
            return y2;
        case Mode::HighPass24:
            return result.input - 4.0f * y1 + 6.0f * y2 - 4.0f * y3 + y4;
        case Mode::BandPass12:
            return 4.0f * (y2 - 2.0f * y3 + y4);
        case Mode::Notch:
            return y4 + result.input - 4.0f * y1 + 6.0f * y2 - 4.0f * y3 + y4;
        case Mode::LowPass24:
        default:
            return y4;
    }
}

void MoogLadderFilter::process(float* samples, int numFrames, int channels) {
    applyPendingParameters();
    if (!enabled_ || samples == nullptr || numFrames <= 0 || channels <= 0) return;

    const int processedChannels = std::min(channels, 2);
    const float oversampledRate = static_cast<float>(sampleRate_ * 2);
    const float safeCutoff = std::min(cutoffHz_, oversampledRate * 0.45f);
    const float targetRawG = std::tan(kPi * safeCutoff / oversampledRate);
    const float targetG = targetRawG / (1.0f + targetRawG);
    const float smoothing = 1.0f - std::exp(-1.0f / (0.015f * sampleRate_));

    for (int frame = 0; frame < numFrames; ++frame) {
        smoothedG_ += (targetG - smoothedG_) * smoothing;
        smoothedResonance_ += (resonance_ - smoothedResonance_) * smoothing;
        smoothedDriveDb_ += (driveDb_ - smoothedDriveDb_) * smoothing;
        smoothedMix_ += (targetMix_ - smoothedMix_) * smoothing;
        const float feedback = 3.95f * smoothedResonance_;

        for (int channel = 0; channel < processedChannels; ++channel) {
            const int index = frame * channels + channel;
            const float dry = std::isfinite(samples[index]) ? samples[index] : 0.0f;
            const float midpoint = 0.5f * (previousInput_[channel] + dry);
            const StageResult first = processOversampled(
                saturate(midpoint, smoothedDriveDb_), channel, smoothedG_, feedback);
            const StageResult second = processOversampled(
                saturate(dry, smoothedDriveDb_), channel, smoothedG_, feedback);
            previousInput_[channel] = dry;
            const float wet = 0.5f * (selectOutput(first) + selectOutput(second));
            const float output = dry + (wet - dry) * smoothedMix_;
            samples[index] = std::isfinite(output) ? output : 0.0f;
        }
    }
}
