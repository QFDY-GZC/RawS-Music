#include "experimental_gain.h"

#include <algorithm>
#include <cmath>

void ExperimentalGainProcessor::setEnabled(bool enabled) {
    m_enabled.store(enabled, std::memory_order_release);
}

void ExperimentalGainProcessor::setGainDb(float gainDb) {
    const float safeGain = std::isfinite(gainDb)
        ? std::max(0.0f, std::min(gainDb, 30.0f))
        : 0.0f;
    m_gainDb.store(safeGain, std::memory_order_release);
}

bool ExperimentalGainProcessor::isEnabled() const {
    return m_enabled.load(std::memory_order_acquire);
}

float ExperimentalGainProcessor::getGainDb() const {
    return m_gainDb.load(std::memory_order_acquire);
}

float ExperimentalGainProcessor::softClip(float sample) {
    if (!std::isfinite(sample)) return 0.0f;

    constexpr float knee = 0.82f;
    const float magnitude = std::fabs(sample);
    if (magnitude <= knee) return sample;
    if (magnitude >= 1.0f) return std::copysign(1.0f, sample);

    // p(u) = u + u^2 - u^3 preserves unit slope at the knee and reaches
    // the ceiling with zero slope. This avoids the corner produced by a hard
    // clipper without adding any level detector or time-dependent limiting.
    const float u = (magnitude - knee) / (1.0f - knee);
    const float shaped = knee + (1.0f - knee) * (u + u * u - u * u * u);
    return std::copysign(shaped, sample);
}

void ExperimentalGainProcessor::process(float* samples, int sampleCount) {
    if (!samples || sampleCount <= 0 || !isEnabled()) return;

    const float gainDb = getGainDb();
    if (gainDb <= 0.0001f) return;
    const float linearGain = std::pow(10.0f, gainDb / 20.0f);

    for (int i = 0; i < sampleCount; ++i) {
        samples[i] = softClip(samples[i] * linearGain);
    }
}
