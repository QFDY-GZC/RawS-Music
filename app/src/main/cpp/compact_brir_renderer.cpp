#include "compact_brir_renderer.h"

#include <algorithm>
#include <cmath>

namespace {
constexpr float kPi = 3.14159265358979323846f;
}

CompactBrirRenderer::CompactBrirRenderer() {
    setSampleRate(sampleRate_);
}

float CompactBrirRenderer::clamp(float value, float minimum, float maximum) {
    return std::max(minimum, std::min(maximum, value));
}

int CompactBrirRenderer::delaySamples(float seconds, int sampleRate, int maximum) {
    const int result = static_cast<int>(std::lround(seconds * static_cast<float>(sampleRate)));
    return std::max(1, std::min(maximum, result));
}

int CompactBrirRenderer::wrapIndex(int index, int size) {
    while (index < 0) index += size;
    while (index >= size) index -= size;
    return index;
}

float CompactBrirRenderer::onePoleCoefficient(float cutoffHz, float sampleRate) {
    const float rate = std::max(8000.0f, sampleRate);
    const float cutoff = clamp(cutoffHz, 5.0f, rate * 0.45f);
    return 1.0f - std::exp(-2.0f * kPi * cutoff / rate);
}

void CompactBrirRenderer::setSampleRate(int sampleRate) {
    if (sampleRate <= 0) return;
    sampleRate_ = sampleRate;
    const float rate = static_cast<float>(sampleRate_);
    smoothingCoefficient_ = 1.0f - std::exp(-1.0f / (0.035f * rate));
    // Difference-equation high pass at roughly 165 Hz.
    highPassCoefficient_ = std::exp(-2.0f * kPi * 165.0f / rate);
    dampingCoefficient_ = onePoleCoefficient(5200.0f, rate);
    rebuildStorage();
    updateTaps();
    reset();
}

void CompactBrirRenderer::setEnabled(bool enabled) {
    enabled_ = enabled;
}

void CompactBrirRenderer::setAmount(float amount) {
    amount_ = clamp(std::isfinite(amount) ? amount : 0.0f, 0.0f, 1.0f);
    // More room means a darker, denser tail.
    dampingCoefficient_ = onePoleCoefficient(
        7200.0f - 3900.0f * amount_,
        static_cast<float>(sampleRate_)
    );
    updateTaps();
}

void CompactBrirRenderer::rebuildStorage() {
    const int inputSize = std::max(128, static_cast<int>(std::ceil(sampleRate_ * 0.050f)) + 32);
    inputDelayLeft_.assign(static_cast<size_t>(inputSize), 0.0f);
    inputDelayRight_.assign(static_cast<size_t>(inputSize), 0.0f);
    inputWriteIndex_ = 0;

    constexpr std::array<float, 4> delaySeconds = {0.0297f, 0.0371f, 0.0439f, 0.0523f};
    for (int line = 0; line < 4; ++line) {
        const int size = std::max(
            64,
            static_cast<int>(std::ceil(sampleRate_ * (delaySeconds[static_cast<size_t>(line)] + 0.004f)))
        );
        fdnDelay_[static_cast<size_t>(line)].assign(static_cast<size_t>(size), 0.0f);
        fdnDelaySamples_[static_cast<size_t>(line)] = delaySamples(
            delaySeconds[static_cast<size_t>(line)],
            sampleRate_,
            size - 2
        );
        fdnWriteIndex_[static_cast<size_t>(line)] = 0;
        fdnDampingState_[static_cast<size_t>(line)] = 0.0f;
    }
}

void CompactBrirRenderer::updateTaps() {
    if (inputDelayLeft_.empty()) return;
    const int maximum = static_cast<int>(inputDelayLeft_.size()) - 2;
    constexpr std::array<float, 8> baseDelay = {
        0.0034f, 0.0059f, 0.0088f, 0.0124f,
        0.0172f, 0.0236f, 0.0321f, 0.0437f,
    };
    constexpr std::array<float, 8> baseGain = {
        0.34f, 0.27f, 0.22f, 0.18f, 0.145f, 0.115f, 0.086f, 0.064f,
    };
    const float roomStretch = 0.92f + 0.34f * amount_;
    for (int index = 0; index < 8; ++index) {
        ReflectionTap& tap = taps_[static_cast<size_t>(index)];
        const float delay = baseDelay[static_cast<size_t>(index)] * roomStretch;
        const float asymmetry = (index % 2 == 0 ? 0.00031f : -0.00024f);
        tap.delayLeft = delaySamples(delay + asymmetry, sampleRate_, maximum);
        tap.delayRight = delaySamples(delay - asymmetry, sampleRate_, maximum);
        const float gain = baseGain[static_cast<size_t>(index)] * (0.78f + 0.42f * amount_);
        const float cross = 0.24f + 0.12f * static_cast<float>(index % 3);
        const float polarity = (index == 2 || index == 5) ? -1.0f : 1.0f;
        tap.ll = gain * (1.0f - 0.25f * cross);
        tap.rr = gain * (1.0f - 0.25f * cross);
        tap.lr = gain * cross * polarity;
        tap.rl = -gain * cross * polarity * 0.92f;
    }
}

float CompactBrirRenderer::readInput(const std::vector<float>& line, int delay) const {
    if (line.empty()) return 0.0f;
    const int index = wrapIndex(inputWriteIndex_ - delay, static_cast<int>(line.size()));
    return line[static_cast<size_t>(index)];
}

float CompactBrirRenderer::readFdn(int line, int delay) const {
    const auto& buffer = fdnDelay_[static_cast<size_t>(line)];
    if (buffer.empty()) return 0.0f;
    const int index = wrapIndex(
        fdnWriteIndex_[static_cast<size_t>(line)] - delay,
        static_cast<int>(buffer.size())
    );
    return buffer[static_cast<size_t>(index)];
}

void CompactBrirRenderer::process(
    float inputLeft,
    float inputRight,
    float& outputLeft,
    float& outputRight
) {
    currentAmount_ += ((enabled_ ? amount_ : 0.0f) - currentAmount_) * smoothingCoefficient_;
    if (inputDelayLeft_.empty()) {
        outputLeft = inputLeft;
        outputRight = inputRight;
        return;
    }

    inputDelayLeft_[static_cast<size_t>(inputWriteIndex_)] = inputLeft;
    inputDelayRight_[static_cast<size_t>(inputWriteIndex_)] = inputRight;

    // Bass-safe injection prevents the room from accumulating sub-bass energy.
    const float highLeft = inputLeft - highPassPreviousInputLeft_ +
        highPassCoefficient_ * highPassPreviousOutputLeft_;
    const float highRight = inputRight - highPassPreviousInputRight_ +
        highPassCoefficient_ * highPassPreviousOutputRight_;
    highPassPreviousInputLeft_ = inputLeft;
    highPassPreviousInputRight_ = inputRight;
    highPassPreviousOutputLeft_ = highLeft;
    highPassPreviousOutputRight_ = highRight;

    float earlyLeft = 0.0f;
    float earlyRight = 0.0f;
    for (const ReflectionTap& tap : taps_) {
        const float delayedLeft = readInput(inputDelayLeft_, tap.delayLeft);
        const float delayedRight = readInput(inputDelayRight_, tap.delayRight);
        earlyLeft += delayedLeft * tap.ll + delayedRight * tap.lr;
        earlyRight += delayedLeft * tap.rl + delayedRight * tap.rr;
    }

    std::array<float, 4> delayed{};
    for (int line = 0; line < 4; ++line) {
        delayed[static_cast<size_t>(line)] = readFdn(
            line,
            fdnDelaySamples_[static_cast<size_t>(line)]
        );
        float& damped = fdnDampingState_[static_cast<size_t>(line)];
        damped += dampingCoefficient_ * (delayed[static_cast<size_t>(line)] - damped);
        delayed[static_cast<size_t>(line)] = damped;
    }

    // Orthogonal-ish Hadamard feedback keeps the tail diffuse and bounded.
    const float h0 = delayed[0] + delayed[1] + delayed[2] + delayed[3];
    const float h1 = delayed[0] - delayed[1] + delayed[2] - delayed[3];
    const float h2 = delayed[0] + delayed[1] - delayed[2] - delayed[3];
    const float h3 = delayed[0] - delayed[1] - delayed[2] + delayed[3];
    const float feedback = 0.39f + 0.31f * currentAmount_;
    const std::array<float, 4> injection = {
        0.52f * highLeft + 0.18f * highRight,
        -0.19f * highLeft + 0.49f * highRight,
        0.31f * (highLeft - highRight),
        0.24f * (highLeft + highRight),
    };
    const std::array<float, 4> mixed = {h0, h1, h2, h3};
    for (int line = 0; line < 4; ++line) {
        auto& buffer = fdnDelay_[static_cast<size_t>(line)];
        const int write = fdnWriteIndex_[static_cast<size_t>(line)];
        buffer[static_cast<size_t>(write)] =
            injection[static_cast<size_t>(line)] + mixed[static_cast<size_t>(line)] * 0.5f * feedback;
        fdnWriteIndex_[static_cast<size_t>(line)] = (write + 1) % static_cast<int>(buffer.size());
    }

    const float lateLeft = 0.29f * delayed[0] + 0.23f * delayed[1] -
        0.17f * delayed[2] + 0.13f * delayed[3];
    const float lateRight = -0.17f * delayed[0] + 0.28f * delayed[1] +
        0.21f * delayed[2] + 0.14f * delayed[3];

    const float roomWet = currentAmount_ * (0.36f + 0.30f * currentAmount_);
    outputLeft = inputLeft + roomWet * (earlyLeft * 0.56f + lateLeft * 0.43f);
    outputRight = inputRight + roomWet * (earlyRight * 0.56f + lateRight * 0.43f);

    inputWriteIndex_ = (inputWriteIndex_ + 1) % static_cast<int>(inputDelayLeft_.size());
}

void CompactBrirRenderer::reset() {
    std::fill(inputDelayLeft_.begin(), inputDelayLeft_.end(), 0.0f);
    std::fill(inputDelayRight_.begin(), inputDelayRight_.end(), 0.0f);
    inputWriteIndex_ = 0;
    for (auto& buffer : fdnDelay_) std::fill(buffer.begin(), buffer.end(), 0.0f);
    fdnWriteIndex_.fill(0);
    fdnDampingState_.fill(0.0f);
    highPassPreviousInputLeft_ = 0.0f;
    highPassPreviousInputRight_ = 0.0f;
    highPassPreviousOutputLeft_ = 0.0f;
    highPassPreviousOutputRight_ = 0.0f;
    currentAmount_ = enabled_ ? amount_ : 0.0f;
}
