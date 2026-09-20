#pragma once

#include <cstdint>
#include <string>
#include <vector>

struct AiVocalActivityOptions {
    int windowMs = 40;
    int hopMs = 10;
    int startHangMs = 80;
    int stopHangMs = 220;
    int mergeGapMs = 140;
    int preRollMs = 80;
    int postRollMs = 180;
};

struct AiVocalActivitySpan {
    int64_t startMs = 0;
    int64_t endMs = 0;
    float confidence = 0.0f;
};

struct AiVocalActivityResult {
    std::string sourceIdentity;
    std::string analyzerVersion;
    int sampleRate = 0;
    int hopMs = 0;
    int64_t durationMs = 0;
    std::vector<AiVocalActivitySpan> spans;
};

bool analyzeAiVocalActivity(
    const std::string& wavPath,
    const std::string& sourceIdentity,
    const std::string& analyzerVersion,
    const AiVocalActivityOptions& options,
    AiVocalActivityResult& result,
    std::string& error);

std::string aiVocalActivityResultJson(const AiVocalActivityResult& result);
