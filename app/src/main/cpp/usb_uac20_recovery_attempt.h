#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "usb_uac20_recovery_policy.h"

namespace rawsmusic::usb {

// Recovery attempt lifecycle tracker. Records each policy decision so we can
// observe attempt budgets and next-action without executing recovery yet.
// This stage never auto-reopens/resets/lower-format/falls-back; it only tracks.
struct Uac20RecoveryAttempt {
    int index = 0;
    Uac20RecoverySignal signal = Uac20RecoverySignal::None;
    Uac20RecoveryDecisionAction decision = Uac20RecoveryDecisionAction::None;
    Uac20RecoveryDecisionAction nextAction = Uac20RecoveryDecisionAction::None;
    std::string source;
    std::string reason;
    int64_t timestampMs = 0;
};

struct Uac20RecoveryAttemptStats {
    bool initialized = false;
    bool hasDecision = false;
    bool budgetExhausted = false;
    bool fallbackSuggested = false;

    int attemptIndex = 0;
    int totalAttempts = 0;
    int consecutiveSameAction = 0;
    int budgetRemaining = 0;
    int budgetTotal = 0;

    Uac20RecoverySignal lastSignal = Uac20RecoverySignal::None;
    Uac20RecoveryDecisionAction lastDecision = Uac20RecoveryDecisionAction::None;
    Uac20RecoveryDecisionAction nextAction = Uac20RecoveryDecisionAction::None;

    int fullReopenCount = 0;
    int resetAltCount = 0;
    int lowerFormatCount = 0;
    int androidFallbackCount = 0;
    int transportLostCount = 0;

    std::string historySummary;
    std::string report;
};

class Uac20RecoveryAttemptTracker {
public:
    void initialize(int budget = 3);
    void reset();
    void record(
            Uac20RecoverySignal signal,
            Uac20RecoveryDecisionAction decision,
            const std::string& source,
            const std::string& reason);
    Uac20RecoveryAttemptStats snapshot() const;

private:
    void rebuildReportLocked(Uac20RecoveryAttemptStats& stats) const;

private:
    int budget_ = 0;
    int budgetTotal_ = 0;
    bool initialized_ = false;
    int attemptIndex_ = 0;
    std::vector<Uac20RecoveryAttempt> history_;
};

std::string describeUac20RecoveryAttemptStats(const Uac20RecoveryAttemptStats& stats);

} // namespace rawsmusic::usb
