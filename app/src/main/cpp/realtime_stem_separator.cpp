#include "realtime_stem_separator.h"

#include <algorithm>
#include <cmath>

namespace {

constexpr float kPi = 3.14159265358979323846f;

inline float clamp01(float value) {
    return std::max(0.0f, std::min(1.0f, value));
}

inline float onePoleCoefficient(float cutoffHz, int sampleRate) {
    const float safeRate = static_cast<float>(std::max(sampleRate, 8000));
    const float safeCutoff = std::max(10.0f, std::min(cutoffHz, safeRate * 0.45f));
    return 1.0f - std::exp(-2.0f * kPi * safeCutoff / safeRate);
}

inline float smoothingCoefficient(float milliseconds, int sampleRate) {
    const float samples = std::max(1.0f, milliseconds * 0.001f *
        static_cast<float>(std::max(sampleRate, 8000)));
    return 1.0f - std::exp(-1.0f / samples);
}

inline float sanitize(float value) {
    return std::isfinite(value) ? value : 0.0f;
}

}  // namespace

void RealtimeStemSeparator::setSampleRate(int sampleRate) {
    m_sampleRate = std::max(sampleRate, 8000);
    resetState();
}

void RealtimeStemSeparator::setEnabled(bool enabled) {
    m_enabled.store(enabled, std::memory_order_release);
}

void RealtimeStemSeparator::setMode(int mode) {
    m_mode.store(
        mode == static_cast<int>(StemMode::Instrumental)
            ? static_cast<int>(StemMode::Instrumental)
            : static_cast<int>(StemMode::Vocals),
        std::memory_order_release
    );
}

void RealtimeStemSeparator::setStrength(float strength) {
    m_strength.store(clamp01(strength), std::memory_order_release);
}

bool RealtimeStemSeparator::isActive() const {
    return m_enabled.load(std::memory_order_acquire) || m_wet > 0.0001f;
}

void RealtimeStemSeparator::resetState() {
    m_wet = 0.0f;
    m_modeMix = m_mode.load(std::memory_order_acquire) ==
        static_cast<int>(StemMode::Instrumental) ? 1.0f : 0.0f;
    m_centerConfidence = 0.0f;
    m_lowMid = 0.0f;
    m_presenceMid = 0.0f;
}

void RealtimeStemSeparator::process(
    float* interleavedSamples,
    int numFrames,
    int channels
) {
    if (interleavedSamples == nullptr || numFrames <= 0 || channels < 2) {
        return;
    }

    const bool enabled = m_enabled.load(std::memory_order_acquire);
    const float requestedStrength = clamp01(
        m_strength.load(std::memory_order_acquire)
    );
    const float wetTarget = enabled ? requestedStrength : 0.0f;
    const float modeTarget =
        m_mode.load(std::memory_order_acquire) ==
            static_cast<int>(StemMode::Instrumental) ? 1.0f : 0.0f;

    const float lowCoefficient = onePoleCoefficient(180.0f, m_sampleRate);
    const float presenceCoefficient = onePoleCoefficient(7200.0f, m_sampleRate);
    const float wetCoefficient = smoothingCoefficient(28.0f, m_sampleRate);
    const float modeCoefficient = smoothingCoefficient(45.0f, m_sampleRate);
    const float confidenceAttack = smoothingCoefficient(8.0f, m_sampleRate);
    const float confidenceRelease = smoothingCoefficient(65.0f, m_sampleRate);

    for (int frame = 0; frame < numFrames; ++frame) {
        const int offset = frame * channels;
        const float left = sanitize(interleavedSamples[offset]);
        const float right = sanitize(interleavedSamples[offset + 1]);
        const float mid = 0.5f * (left + right);
        const float side = 0.5f * (left - right);

        m_lowMid += lowCoefficient * (mid - m_lowMid);
        m_presenceMid += presenceCoefficient * (mid - m_presenceMid);

        // A centered source has strong Mid energy and little Side energy. Smooth
        // this confidence asymmetrically so stereo ambience does not chatter.
        const float midEnergy = std::fabs(mid);
        const float sideEnergy = std::fabs(side);
        const float rawConfidence = clamp01(
            (midEnergy - sideEnergy * 0.72f) /
            (midEnergy + sideEnergy + 1.0e-6f)
        );
        const float confidenceCoefficient =
            rawConfidence > m_centerConfidence
                ? confidenceAttack
                : confidenceRelease;
        m_centerConfidence += confidenceCoefficient *
            (rawConfidence - m_centerConfidence);

        const float vocalBand = m_presenceMid - m_lowMid;
        const float upperAir = mid - m_presenceMid;
        const float centerWeight = 0.18f + 0.82f * m_centerConfidence;

        // Vocals retain a controlled amount of centered bass and air, while
        // rejecting decorrelated Side content. Instrumental mode subtracts the
        // same estimate from the original channels and preserves low bass.
        const float vocalCenter =
            vocalBand * centerWeight +
            m_lowMid * (0.30f + 0.25f * m_centerConfidence) +
            upperAir * (0.12f + 0.30f * m_centerConfidence);
        const float vocalSide = side * (0.035f + 0.10f * (1.0f - m_centerConfidence));
        const float vocalLeft = vocalCenter + vocalSide;
        const float vocalRight = vocalCenter - vocalSide;

        const float removableCenter =
            vocalBand * (0.18f + 0.80f * m_centerConfidence) +
            upperAir * (0.08f + 0.38f * m_centerConfidence);
        const float instrumentalLeft = left - removableCenter;
        const float instrumentalRight = right - removableCenter;

        m_wet += wetCoefficient * (wetTarget - m_wet);
        m_modeMix += modeCoefficient * (modeTarget - m_modeMix);

        const float targetLeft =
            vocalLeft + (instrumentalLeft - vocalLeft) * m_modeMix;
        const float targetRight =
            vocalRight + (instrumentalRight - vocalRight) * m_modeMix;
        interleavedSamples[offset] = left + (targetLeft - left) * m_wet;
        interleavedSamples[offset + 1] = right + (targetRight - right) * m_wet;
    }
}
