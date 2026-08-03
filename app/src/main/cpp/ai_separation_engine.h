#pragma once

#include <cstdint>
#include <functional>
#include <string>

struct AiSeparationConfig {
    int sampleRate = 44100;
    int segmentSamples = 0;
    double overlap = 0.25;
    int fftSize = 0;
    int hopLength = 0;
    int frequencyBins = 0;
    int timeFrames = 0;
    bool center = true;
    int paddingMode = 0;   // 0 constant, 1 reflect
    int normalization = 0; // 0 none, 1 global mean/std
    int outputType = 0;    // 0 complex spectrum, 1 complex mask
    int chunkMode = 0;     // 0 generic full segment, 1 UVR MDX center trim
    int edgeTrimSamples = 0;
    double compensation = 1.0;
    bool denoise = false;  // UVR shift trick: 0.5*f(x)-0.5*f(-x)
};

struct AiSeparationStats {
    int64_t totalFrames = 0;
    int processedSegments = 0;
    int64_t elapsedMs = 0;
};

using AiModelRunner = std::function<bool(std::string& error)>;
using AiCancelCheck = std::function<bool()>;
using AiProgressCallback = std::function<void(int64_t, int64_t, int, int)>;

bool runAiSeparation(
    const std::string& pcmPath,
    const std::string& vocalsPath,
    const std::string& instrumentalPath,
    const AiSeparationConfig& config,
    float* inputTensor,
    int64_t inputFloats,
    float* outputTensor,
    int64_t outputFloats,
    const AiModelRunner& runModel,
    const AiCancelCheck& isCancelled,
    const AiProgressCallback& onProgress,
    AiSeparationStats& stats,
    std::string& error);

bool runAiSeparationSegment(
    const float* mixtureStereo,
    int mixtureFrames,
    float* vocalStereo,
    int vocalCapacityFrames,
    const AiSeparationConfig& config,
    float* inputTensor,
    int64_t inputFloats,
    float* outputTensor,
    int64_t outputFloats,
    const AiModelRunner& runModel,
    std::string& error);
