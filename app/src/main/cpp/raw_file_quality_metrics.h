#pragma once

#include <array>
#include <cstdint>
#include <limits>
#include "third_party/libebur128/ebur128.h"

struct RawFileQualityResult {
    uint32_t flags = 0;
    float integratedLufs = std::numeric_limits<float>::quiet_NaN();
    float loudnessRangeLu = std::numeric_limits<float>::quiet_NaN();
    float truePeakDb = std::numeric_limits<float>::quiet_NaN();
    float leftDcOffset = std::numeric_limits<float>::quiet_NaN();
    float rightDcOffset = std::numeric_limits<float>::quiet_NaN();
    float stereoCorrelation = std::numeric_limits<float>::quiet_NaN();
    float midRmsDb = std::numeric_limits<float>::quiet_NaN();
    float sideRmsDb = std::numeric_limits<float>::quiet_NaN();
    uint64_t silentFrameCount = 0;
    uint64_t longestSilentFrameCount = 0;
    uint64_t hardRailFrameCount = 0;
    float leftPeakDb = std::numeric_limits<float>::quiet_NaN();
    float rightPeakDb = std::numeric_limits<float>::quiet_NaN();
    float leftRmsDb = std::numeric_limits<float>::quiet_NaN();
    float rightRmsDb = std::numeric_limits<float>::quiet_NaN();
};

// Owns bounded streaming statistics and upstream EBU measurement state.
// Input is decoded normalized S32 PCM, with one or two measured channels.
class RawFileQualityMetrics {
public:
    RawFileQualityMetrics(int sampleRate, int channels);
    ~RawFileQualityMetrics();
    RawFileQualityMetrics(const RawFileQualityMetrics&) = delete;
    RawFileQualityMetrics& operator=(const RawFileQualityMetrics&) = delete;
    void addFrame(int32_t left, int32_t right, bool anyChannelAtHardRail);
    const RawFileQualityResult& finish();
    bool finished() const { return finished_; }

private:
    void flushEbu();
    const int channels_;
    ebur128_state* ebu_ = nullptr;
    std::array<int, 2048> ebuSamples_{}; // 1024 stereo frames, reused; no per-sample allocations.
    size_t ebuBufferedFrames_ = 0;
    uint64_t frames_ = 0;
    uint64_t nextLraFrame_ = 0;
    uint64_t lraHop_ = 0;
    bool hasLraBlock_ = false;
    bool finished_ = false;
    double leftMean_ = 0.0, rightMean_ = 0.0;
    double leftVarianceSum_ = 0.0, rightVarianceSum_ = 0.0, covarianceSum_ = 0.0;
    double leftSquareSum_ = 0.0, rightSquareSum_ = 0.0;
    double midSquareSum_ = 0.0, sideSquareSum_ = 0.0;
    int64_t leftPeak_ = 0, rightPeak_ = 0;
    uint64_t silentRun_ = 0;
    RawFileQualityResult result_;
};
