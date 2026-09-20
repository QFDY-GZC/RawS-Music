#pragma once

#include <cstdint>
#include <functional>
#include <string>

using SpleeterModelRunner = std::function<bool(std::string& error)>;
using SpleeterProgressCallback = std::function<bool(int64_t completed, int64_t total)>;

bool runSpleeterVocalActivity(
    const std::string& pcmPath,
    int sampleRate,
    float* inputTensor,
    int64_t inputFloats,
    float* outputTensor,
    int64_t outputFloats,
    const SpleeterModelRunner& runModel,
    const SpleeterProgressCallback& reportProgress,
    std::string& result,
    std::string& error);
