#pragma once

#include <string>
#include <vector>

namespace rawsmusic::usb {

struct Uac20FormatFallbackCandidate {
    int index = 0;
    int sampleRate = 0;
    int bits = 0;
    int subslotBytes = 0;
    int channels = 0;
    bool keepExplicitFeedback = true;
    std::string reason;
};

struct Uac20FormatFallbackPlan {
    bool initialized = false;
    bool executionEnabled = false;
    bool hasCandidates = false;
    int selectedIndex = -1;
    int candidateCount = 0;
    std::string summary;
    std::string candidateSummary;
    std::vector<Uac20FormatFallbackCandidate> candidates;
};

struct Uac20FormatFallbackInput {
    bool executionEnabled = false;
    bool lowerFormatSuggested = false;
    bool explicitFeedbackSelected = true;
    int currentSampleRate = 0;
    int currentBits = 0;
    int currentSubslotBytes = 0;
    int channels = 0;
};

Uac20FormatFallbackPlan buildUac20FormatFallbackPlan(const Uac20FormatFallbackInput& input);
std::string describeUac20FormatFallbackPlan(const Uac20FormatFallbackPlan& plan);

} // namespace rawsmusic::usb
