#pragma once

#include <string>
#include <vector>

#include "usb_uac20_recovery_policy.h"

namespace rawsmusic::usb {

struct Uac20RecoveryCandidate {
    int index = 0;
    Uac20RecoveryDecisionAction action = Uac20RecoveryDecisionAction::None;
    std::string label;
    int sampleRate = 0;
    int bits = 0;
    int subslotBytes = 0;
    bool keepExplicitFeedback = true;
    bool disableFeedback = false;
    bool requireFullReopen = false;
    bool requireAltReset = false;
    bool lowerFormat = false;
    bool androidFallback = false;
    bool transportLost = false;
    std::string reason;
};

struct Uac20RecoveryCandidatePlanStats {
    bool initialized = false;
    bool hasCandidates = false;
    bool hasSelected = false;
    int candidateCount = 0;
    int selectedIndex = -1;
    Uac20RecoveryCandidate selected;

    std::string candidateList;
    std::string report;
};

class Uac20RecoveryCandidatePlanner {
public:
    void initialize();
    void reset();
    void build(
            Uac20RecoveryDecisionAction action,
            int currentSampleRate,
            int currentBits,
            int currentSubslotBytes,
            bool descriptorHasExplicitFeedback,
            bool keepExplicitFeedback);
    Uac20RecoveryCandidatePlanStats snapshot() const;

private:
    bool initialized_ = false;
    std::vector<Uac20RecoveryCandidate> candidates_;
    int selectedIndex_ = -1;
};

std::string describeUac20RecoveryCandidatePlanStats(const Uac20RecoveryCandidatePlanStats& stats);

} // namespace rawsmusic::usb
