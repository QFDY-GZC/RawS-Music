#pragma once

#include <atomic>

/** Folds the final stereo signal to mono without changing its overall level. */
class MonoOutputProcessor {
public:
    void setEnabled(bool enabled);
    bool isEnabled() const;
    void process(float* samples, int numFrames, int channels);

private:
    void applyPendingParameters();

    std::atomic<bool> pendingEnabled_{false};
    std::atomic<bool> parametersDirty_{true};
    bool enabled_ = false;
};
