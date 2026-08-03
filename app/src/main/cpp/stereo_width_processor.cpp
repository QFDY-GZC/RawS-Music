#include "stereo_width_processor.h"

#include <algorithm>
#include <cmath>


float StereoWidthProcessor::sanitizeAmount(float value) {
    if (!std::isfinite(value)) return 0.0f;
    return std::max(0.0f, std::min(1.0f, value));
}

void StereoWidthProcessor::process(
    float* samples,
    int numFrames,
    int channels
) {
    if (samples == nullptr || numFrames <= 0 || channels != 2) return;

    // Latch the UI value once per block. The whole block therefore uses one
    // matrix and the realtime thread never observes a partially changed value.
    const float amount = pendingAmount_.load(std::memory_order_acquire);
    if (amount <= 0.0f) return;

    const float width = 1.0f + 2.0f * amount;
    for (int frame = 0; frame < numFrames; ++frame) {
        const int offset = frame * 2;
        const float left = samples[offset];
        const float right = samples[offset + 1];
        const float mid = (left + right) * 0.5f;

        samples[offset] = mid + (left - mid) * width;
        samples[offset + 1] = mid + (right - mid) * width;
    }
}

void StereoWidthProcessor::setParameter(int parameterId, float value) {
    if (parameterId != 0) return;
    pendingAmount_.store(sanitizeAmount(value), std::memory_order_release);
}

void StereoWidthProcessor::setSampleRate(int) {
    // The matrix is sample-rate independent.
}

bool StereoWidthProcessor::isEnabled() const {
    return pendingAmount_.load(std::memory_order_acquire) > 0.0f;
}
