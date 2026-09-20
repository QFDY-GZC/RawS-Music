#include "mono_output_processor.h"

#include <cmath>

void MonoOutputProcessor::setEnabled(bool enabled) {
    pendingEnabled_.store(enabled, std::memory_order_release);
    parametersDirty_.store(true, std::memory_order_release);
}

bool MonoOutputProcessor::isEnabled() const {
    return pendingEnabled_.load(std::memory_order_acquire);
}

void MonoOutputProcessor::applyPendingParameters() {
    if (!parametersDirty_.exchange(false, std::memory_order_acq_rel)) return;
    enabled_ = pendingEnabled_.load(std::memory_order_acquire);
}

void MonoOutputProcessor::process(float* samples, int numFrames, int channels) {
    applyPendingParameters();
    if (!enabled_ || samples == nullptr || numFrames <= 0 || channels < 2) return;

    for (int frame = 0; frame < numFrames; ++frame) {
        const int index = frame * channels;
        const float left = std::isfinite(samples[index]) ? samples[index] : 0.0f;
        const float right = std::isfinite(samples[index + 1]) ? samples[index + 1] : 0.0f;
        const float mono = (left + right) * 0.5f;
        samples[index] = mono;
        samples[index + 1] = mono;
    }
}
