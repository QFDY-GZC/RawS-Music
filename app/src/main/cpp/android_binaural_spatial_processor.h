#pragma once

#include "analytic_hrtf_decoder.h"
#include "compact_brir_renderer.h"
#include "second_order_ambisonics.h"
#include "stereo_coherence_scene.h"

#include <atomic>
#include <cstdint>

/**
 * RawSMusic-owned Android-output spatial renderer.
 *
 * Pipeline:
 *   adaptive multiband Mid/Side separation
 *   -> seven virtual sources
 *   -> world-locked second-order Ambisonics
 *   -> parameterised HRTF
 *   -> compact BRIR/FDN room
 *   -> bass anchor and linked peak protection.
 */
class AndroidBinauralSpatialProcessor {
public:
    AndroidBinauralSpatialProcessor();

    void setSampleRate(int sampleRate);
    void setEnabled(bool enabled);
    void setParameters(float intensityPercent, float roomPercent);
    void setAdvancedParameters(
        bool brirEnabled,
        float separationPercent,
        float headSizeCentimeters,
        float pinnaDetailPercent
    );
    void setHeadPose(
        bool enabled,
        float quaternionX,
        float quaternionY,
        float quaternionZ,
        float quaternionW
    );
    void process(float* samples, int numFrames, int channels);
    void reset();
    bool isEnabled() const;

private:
    static float clamp(float value, float minimum, float maximum);
    static float sanitize(float value);
    static float onePoleCoefficient(float cutoffHz, float sampleRate);
    static void normalizeQuaternion(float& x, float& y, float& z, float& w);

    void applyPendingParameters();
    void updateHeadPoseForBlock(int numFrames);
    void recordDiagnostics(float dryL, float dryR, float outL, float outR);
    void maybeLogDiagnostics();

    int sampleRate_ = 48000;
    float parameterSmoothing_ = 0.0015f;
    float bassCoefficient_ = 0.02f;
    float spatialBassCoefficient_ = 0.02f;
    float limiterReleaseCoefficient_ = 0.0001f;

    StereoCoherenceScene scene_;
    SecondOrderAmbisonicsEncoder ambisonicsEncoder_;
    AnalyticHrtfDecoder hrtfDecoder_;
    CompactBrirRenderer brirRenderer_;

    float dryBassL_ = 0.0f;
    float dryBassR_ = 0.0f;
    float spatialBassL_ = 0.0f;
    float spatialBassR_ = 0.0f;
    float limiterGain_ = 1.0f;

    bool enabled_ = false;
    bool brirEnabled_ = true;
    bool headTrackingEnabled_ = false;
    float targetIntensity_ = 0.0f;
    float currentIntensity_ = 0.0f;
    float targetRoom_ = 0.0f;
    float currentRoom_ = 0.0f;
    float separation_ = 0.72f;
    float headSizeCentimeters_ = 57.0f;
    float pinnaDetailPercent_ = 55.0f;

    float targetHeadQx_ = 0.0f;
    float targetHeadQy_ = 0.0f;
    float targetHeadQz_ = 0.0f;
    float targetHeadQw_ = 1.0f;
    float currentHeadQx_ = 0.0f;
    float currentHeadQy_ = 0.0f;
    float currentHeadQz_ = 0.0f;
    float currentHeadQw_ = 1.0f;

    std::uint64_t diagnosticFrames_ = 0;
    double diagnosticInputEnergy_ = 0.0;
    double diagnosticDeltaEnergy_ = 0.0;
    double diagnosticOutputEnergy_ = 0.0;
    double diagnosticCoherence_ = 0.0;
    double diagnosticDiffuseness_ = 0.0;
    double diagnosticVocal_ = 0.0;
    double diagnosticTransient_ = 0.0;

    std::atomic<bool> pendingEnabled_{false};
    std::atomic<float> pendingIntensityPercent_{55.0f};
    std::atomic<float> pendingRoomPercent_{18.0f};
    std::atomic<bool> pendingBrirEnabled_{true};
    std::atomic<float> pendingSeparationPercent_{72.0f};
    std::atomic<float> pendingHeadSizeCentimeters_{57.0f};
    std::atomic<float> pendingPinnaDetailPercent_{55.0f};
    std::atomic<bool> pendingHeadTrackingEnabled_{false};
    std::atomic<float> pendingHeadQx_{0.0f};
    std::atomic<float> pendingHeadQy_{0.0f};
    std::atomic<float> pendingHeadQz_{0.0f};
    std::atomic<float> pendingHeadQw_{1.0f};
    std::atomic<bool> parametersDirty_{true};
    std::atomic<bool> poseDirty_{true};
    std::atomic<bool> processingTail_{false};
};
