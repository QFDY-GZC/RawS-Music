#include "android_binaural_spatial_processor.h"

#include <algorithm>
#include <array>
#include <cmath>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace {
constexpr float kPi = 3.14159265358979323846f;
constexpr float kHalfPi = 1.57079632679489661923f;
constexpr float kPeakCeiling = 0.982f;
constexpr float kTailThreshold = 1.0e-4f;
constexpr const char* kLogTag = "RawSpatialDSP";
}

AndroidBinauralSpatialProcessor::AndroidBinauralSpatialProcessor() {
    setSampleRate(sampleRate_);
}

float AndroidBinauralSpatialProcessor::clamp(float value, float minimum, float maximum) {
    return std::max(minimum, std::min(maximum, value));
}

float AndroidBinauralSpatialProcessor::sanitize(float value) {
    return std::isfinite(value) ? value : 0.0f;
}

float AndroidBinauralSpatialProcessor::onePoleCoefficient(float cutoffHz, float sampleRate) {
    const float safeRate = std::max(8000.0f, sampleRate);
    const float safeCutoff = clamp(cutoffHz, 5.0f, safeRate * 0.45f);
    return 1.0f - std::exp(-2.0f * kPi * safeCutoff / safeRate);
}

void AndroidBinauralSpatialProcessor::normalizeQuaternion(
    float& x,
    float& y,
    float& z,
    float& w
) {
    const float norm = std::sqrt(x * x + y * y + z * z + w * w);
    if (!std::isfinite(norm) || norm < 1.0e-7f) {
        x = 0.0f;
        y = 0.0f;
        z = 0.0f;
        w = 1.0f;
        return;
    }
    const float inverse = 1.0f / norm;
    x *= inverse;
    y *= inverse;
    z *= inverse;
    w *= inverse;
}

void AndroidBinauralSpatialProcessor::setSampleRate(int sampleRate) {
    if (sampleRate <= 0) return;
    sampleRate_ = sampleRate;
    const float rate = static_cast<float>(sampleRate_);
    parameterSmoothing_ = 1.0f - std::exp(-1.0f / (0.028f * rate));
    bassCoefficient_ = onePoleCoefficient(132.0f, rate);
    spatialBassCoefficient_ = onePoleCoefficient(155.0f, rate);
    limiterReleaseCoefficient_ = 1.0f - std::exp(-1.0f / (0.260f * rate));

    scene_.setSampleRate(sampleRate_);
    hrtfDecoder_.setSampleRate(sampleRate_);
    brirRenderer_.setSampleRate(sampleRate_);
    reset();
}

void AndroidBinauralSpatialProcessor::setEnabled(bool enabled) {
    pendingEnabled_.store(enabled, std::memory_order_release);
    parametersDirty_.store(true, std::memory_order_release);
    if (enabled) processingTail_.store(true, std::memory_order_release);
#ifdef __ANDROID__
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "setEnabled requested=%d algorithm=adaptive_7src_hoa2_headtrack_hrtf_brir",
        enabled ? 1 : 0
    );
#endif
}

void AndroidBinauralSpatialProcessor::setParameters(
    float intensityPercent,
    float roomPercent
) {
    pendingIntensityPercent_.store(
        clamp(sanitize(intensityPercent), 0.0f, 100.0f),
        std::memory_order_relaxed
    );
    pendingRoomPercent_.store(
        clamp(sanitize(roomPercent), 0.0f, 100.0f),
        std::memory_order_relaxed
    );
    parametersDirty_.store(true, std::memory_order_release);
}

void AndroidBinauralSpatialProcessor::setAdvancedParameters(
    bool brirEnabled,
    float separationPercent,
    float headSizeCentimeters,
    float pinnaDetailPercent
) {
    pendingBrirEnabled_.store(brirEnabled, std::memory_order_relaxed);
    pendingSeparationPercent_.store(
        clamp(sanitize(separationPercent), 0.0f, 100.0f),
        std::memory_order_relaxed
    );
    pendingHeadSizeCentimeters_.store(
        clamp(sanitize(headSizeCentimeters), 48.0f, 68.0f),
        std::memory_order_relaxed
    );
    pendingPinnaDetailPercent_.store(
        clamp(sanitize(pinnaDetailPercent), 0.0f, 100.0f),
        std::memory_order_relaxed
    );
    parametersDirty_.store(true, std::memory_order_release);
}

void AndroidBinauralSpatialProcessor::setHeadPose(
    bool enabled,
    float quaternionX,
    float quaternionY,
    float quaternionZ,
    float quaternionW
) {
    pendingHeadTrackingEnabled_.store(enabled, std::memory_order_relaxed);
    pendingHeadQx_.store(sanitize(quaternionX), std::memory_order_relaxed);
    pendingHeadQy_.store(sanitize(quaternionY), std::memory_order_relaxed);
    pendingHeadQz_.store(sanitize(quaternionZ), std::memory_order_relaxed);
    pendingHeadQw_.store(sanitize(quaternionW), std::memory_order_relaxed);
    poseDirty_.store(true, std::memory_order_release);
}

void AndroidBinauralSpatialProcessor::applyPendingParameters() {
    if (!parametersDirty_.exchange(false, std::memory_order_acq_rel)) return;

    const bool nextEnabled = pendingEnabled_.load(std::memory_order_acquire);
    if (!enabled_ && nextEnabled) {
        reset();
        processingTail_.store(true, std::memory_order_release);
    }
    enabled_ = nextEnabled;
    targetIntensity_ = enabled_
        ? pendingIntensityPercent_.load(std::memory_order_relaxed) * 0.01f
        : 0.0f;
    targetRoom_ = enabled_
        ? pendingRoomPercent_.load(std::memory_order_relaxed) * 0.01f
        : 0.0f;
    brirEnabled_ = pendingBrirEnabled_.load(std::memory_order_relaxed);
    separation_ = pendingSeparationPercent_.load(std::memory_order_relaxed) * 0.01f;
    headSizeCentimeters_ = pendingHeadSizeCentimeters_.load(std::memory_order_relaxed);
    pinnaDetailPercent_ = pendingPinnaDetailPercent_.load(std::memory_order_relaxed);

    hrtfDecoder_.setPersonalization(headSizeCentimeters_, pinnaDetailPercent_);
    brirRenderer_.setEnabled(brirEnabled_ && enabled_);
    brirRenderer_.setAmount(targetRoom_);
}

void AndroidBinauralSpatialProcessor::updateHeadPoseForBlock(int numFrames) {
    if (poseDirty_.exchange(false, std::memory_order_acq_rel)) {
        headTrackingEnabled_ = pendingHeadTrackingEnabled_.load(std::memory_order_relaxed);
        targetHeadQx_ = pendingHeadQx_.load(std::memory_order_relaxed);
        targetHeadQy_ = pendingHeadQy_.load(std::memory_order_relaxed);
        targetHeadQz_ = pendingHeadQz_.load(std::memory_order_relaxed);
        targetHeadQw_ = pendingHeadQw_.load(std::memory_order_relaxed);
        normalizeQuaternion(targetHeadQx_, targetHeadQy_, targetHeadQz_, targetHeadQw_);
        if (!headTrackingEnabled_) {
            targetHeadQx_ = 0.0f;
            targetHeadQy_ = 0.0f;
            targetHeadQz_ = 0.0f;
            targetHeadQw_ = 1.0f;
        }
    }

    // Approximately 38 ms orientation smoothing; nlerp handles sensor jitter and
    // makes dynamic sensor reconnect/recenter transitions click-free.
    const float blockSeconds = static_cast<float>(std::max(1, numFrames)) /
        static_cast<float>(std::max(8000, sampleRate_));
    const float blend = 1.0f - std::exp(-blockSeconds / 0.038f);
    float dot = currentHeadQx_ * targetHeadQx_ + currentHeadQy_ * targetHeadQy_ +
        currentHeadQz_ * targetHeadQz_ + currentHeadQw_ * targetHeadQw_;
    float tx = targetHeadQx_;
    float ty = targetHeadQy_;
    float tz = targetHeadQz_;
    float tw = targetHeadQw_;
    if (dot < 0.0f) {
        tx = -tx;
        ty = -ty;
        tz = -tz;
        tw = -tw;
    }
    currentHeadQx_ += (tx - currentHeadQx_) * blend;
    currentHeadQy_ += (ty - currentHeadQy_) * blend;
    currentHeadQz_ += (tz - currentHeadQz_) * blend;
    currentHeadQw_ += (tw - currentHeadQw_) * blend;
    normalizeQuaternion(currentHeadQx_, currentHeadQy_, currentHeadQz_, currentHeadQw_);
    ambisonicsEncoder_.setHeadRotation(
        currentHeadQx_,
        currentHeadQy_,
        currentHeadQz_,
        currentHeadQw_,
        headTrackingEnabled_ ? 1.0f : 0.0f
    );
}

void AndroidBinauralSpatialProcessor::recordDiagnostics(
    float dryL,
    float dryR,
    float outL,
    float outR
) {
    const double inL = static_cast<double>(dryL);
    const double inR = static_cast<double>(dryR);
    const double deltaL = static_cast<double>(outL - dryL);
    const double deltaR = static_cast<double>(outR - dryR);
    diagnosticInputEnergy_ += inL * inL + inR * inR;
    diagnosticDeltaEnergy_ += deltaL * deltaL + deltaR * deltaR;
    diagnosticOutputEnergy_ +=
        static_cast<double>(outL) * static_cast<double>(outL) +
        static_cast<double>(outR) * static_cast<double>(outR);
    ++diagnosticFrames_;
}

void AndroidBinauralSpatialProcessor::maybeLogDiagnostics() {
    const std::uint64_t interval =
        static_cast<std::uint64_t>(std::max(8000, sampleRate_)) * 2ULL;
    if (diagnosticFrames_ < interval) return;

    const double divisor = std::max(1.0, static_cast<double>(diagnosticFrames_) * 2.0);
    const double inputRms = std::sqrt(diagnosticInputEnergy_ / divisor);
    const double outputRms = std::sqrt(diagnosticOutputEnergy_ / divisor);
    const double deltaRms = std::sqrt(diagnosticDeltaEnergy_ / divisor);
    const double ratio = inputRms > 1.0e-9 ? deltaRms / inputRms : 0.0;
    const double frameDivisor = std::max(1.0, static_cast<double>(diagnosticFrames_));
    const double coherence = diagnosticCoherence_ / frameDivisor;
    const double diffuseness = diagnosticDiffuseness_ / frameDivisor;
    const double vocal = diagnosticVocal_ / frameDivisor;
    const double transient = diagnosticTransient_ / frameDivisor;

#ifdef __ANDROID__
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "processed algorithm=adaptive_7src_hoa2_headtrack_hrtf_brir frames=%llu enabled=%d intensity=%.1f room=%.1f separation=%.1f brir=%d head=%d coherence=%.3f diffuse=%.3f vocal=%.3f transient=%.3f inRms=%.6f outRms=%.6f deltaRatio=%.3f",
        static_cast<unsigned long long>(diagnosticFrames_),
        enabled_ ? 1 : 0,
        currentIntensity_ * 100.0f,
        currentRoom_ * 100.0f,
        separation_ * 100.0f,
        brirEnabled_ ? 1 : 0,
        headTrackingEnabled_ ? 1 : 0,
        coherence,
        diffuseness,
        vocal,
        transient,
        inputRms,
        outputRms,
        ratio
    );
#else
    (void)inputRms;
    (void)outputRms;
    (void)ratio;
    (void)coherence;
    (void)diffuseness;
    (void)vocal;
    (void)transient;
#endif

    diagnosticFrames_ = 0;
    diagnosticInputEnergy_ = 0.0;
    diagnosticDeltaEnergy_ = 0.0;
    diagnosticOutputEnergy_ = 0.0;
    diagnosticCoherence_ = 0.0;
    diagnosticDiffuseness_ = 0.0;
    diagnosticVocal_ = 0.0;
    diagnosticTransient_ = 0.0;
}

void AndroidBinauralSpatialProcessor::process(
    float* samples,
    int numFrames,
    int channels
) {
    applyPendingParameters();
    if (samples == nullptr || numFrames <= 0 || channels != 2) return;

    if (!processingTail_.load(std::memory_order_acquire) &&
        targetIntensity_ <= kTailThreshold) {
        return;
    }

    updateHeadPoseForBlock(numFrames);

    for (int frame = 0; frame < numFrames; ++frame) {
        currentIntensity_ += (targetIntensity_ - currentIntensity_) * parameterSmoothing_;
        currentRoom_ += (targetRoom_ - currentRoom_) * parameterSmoothing_;

        const int offset = frame * 2;
        const float dryL = sanitize(samples[offset]);
        const float dryR = sanitize(samples[offset + 1]);

        const float sceneIntensity = clamp(
            currentIntensity_ * (0.88f + 0.12f * currentIntensity_),
            0.0f,
            1.0f
        );
        const StereoCoherenceScene::Frame sceneFrame = scene_.analyse(
            dryL,
            dryR,
            sceneIntensity,
            currentRoom_,
            separation_
        );

        std::array<float, SecondOrderAmbisonicsEncoder::kChannelCount> hoaBus{};
        ambisonicsEncoder_.encode(sceneFrame.source, hoaBus);

        float spatialL = 0.0f;
        float spatialR = 0.0f;
        hrtfDecoder_.decode(hoaBus, sceneIntensity, spatialL, spatialR);
        brirRenderer_.process(spatialL, spatialR, spatialL, spatialR);

        dryBassL_ += bassCoefficient_ * (dryL - dryBassL_);
        dryBassR_ += bassCoefficient_ * (dryR - dryBassR_);
        spatialBassL_ += spatialBassCoefficient_ * (spatialL - spatialBassL_);
        spatialBassR_ += spatialBassCoefficient_ * (spatialR - spatialBassR_);
        const float dryBassMid = 0.5f * (dryBassL_ + dryBassR_);
        const float highSpatialL = spatialL - spatialBassL_;
        const float highSpatialR = spatialR - spatialBassR_;
        const float bassMonoAmount = 0.54f + 0.34f * sceneIntensity;
        const float bassAnchorL =
            dryBassL_ * (1.0f - bassMonoAmount) + dryBassMid * bassMonoAmount;
        const float bassAnchorR =
            dryBassR_ * (1.0f - bassMonoAmount) + dryBassMid * bassMonoAmount;
        spatialL = highSpatialL + bassAnchorL;
        spatialR = highSpatialR + bassAnchorR;

        const float wetControl = clamp(
            currentIntensity_ + 0.19f * currentIntensity_ * currentIntensity_ * currentIntensity_,
            0.0f,
            1.0f
        );
        const float dryGain = std::cos(wetControl * kHalfPi);
        const float wetGain = std::sin(wetControl * kHalfPi);
        const float highDriveInput = clamp((sceneIntensity - 0.54f) / 0.46f, 0.0f, 1.0f);
        const float highDrive =
            highDriveInput * highDriveInput * (3.0f - 2.0f * highDriveInput);
        const float wetOutputGain = 1.24f + 1.02f * highDrive;

        float outL = dryL * dryGain + spatialL * wetGain * wetOutputGain;
        float outR = dryR * dryGain + spatialR * wetGain * wetOutputGain;

        const float peak = std::max(std::fabs(outL), std::fabs(outR));
        const float requiredGain = peak > kPeakCeiling ? kPeakCeiling / peak : 1.0f;
        if (requiredGain < limiterGain_) {
            limiterGain_ = requiredGain;
        } else {
            limiterGain_ += (1.0f - limiterGain_) * limiterReleaseCoefficient_;
        }

        outL = sanitize(outL * limiterGain_);
        outR = sanitize(outR * limiterGain_);
        samples[offset] = outL;
        samples[offset + 1] = outR;

        diagnosticCoherence_ += sceneFrame.coherence;
        diagnosticDiffuseness_ += sceneFrame.diffuseness;
        diagnosticVocal_ += sceneFrame.vocalConfidence;
        diagnosticTransient_ += sceneFrame.transient;
        recordDiagnostics(dryL, dryR, outL, outR);
    }

    maybeLogDiagnostics();

    if (targetIntensity_ <= kTailThreshold &&
        currentIntensity_ <= 5.0e-4f &&
        currentRoom_ <= 5.0e-4f &&
        limiterGain_ >= 0.9995f) {
        currentIntensity_ = 0.0f;
        currentRoom_ = 0.0f;
        processingTail_.store(false, std::memory_order_release);
    }
}

void AndroidBinauralSpatialProcessor::reset() {
    scene_.reset();
    ambisonicsEncoder_.resetHeadRotation();
    hrtfDecoder_.reset();
    brirRenderer_.reset();
    dryBassL_ = 0.0f;
    dryBassR_ = 0.0f;
    spatialBassL_ = 0.0f;
    spatialBassR_ = 0.0f;
    limiterGain_ = 1.0f;
    currentIntensity_ = 0.0f;
    currentRoom_ = 0.0f;
    currentHeadQx_ = 0.0f;
    currentHeadQy_ = 0.0f;
    currentHeadQz_ = 0.0f;
    currentHeadQw_ = 1.0f;
    diagnosticFrames_ = 0;
    diagnosticInputEnergy_ = 0.0;
    diagnosticDeltaEnergy_ = 0.0;
    diagnosticOutputEnergy_ = 0.0;
    diagnosticCoherence_ = 0.0;
    diagnosticDiffuseness_ = 0.0;
    diagnosticVocal_ = 0.0;
    diagnosticTransient_ = 0.0;
}

bool AndroidBinauralSpatialProcessor::isEnabled() const {
    return pendingEnabled_.load(std::memory_order_acquire) ||
        processingTail_.load(std::memory_order_acquire);
}
