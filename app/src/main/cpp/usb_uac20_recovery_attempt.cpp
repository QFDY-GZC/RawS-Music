#include "usb_uac20_recovery_attempt.h"

#include <algorithm>
#include <chrono>
#include <sstream>

namespace rawsmusic::usb {
namespace {

int64_t nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

} // namespace

void Uac20RecoveryAttemptTracker::initialize(int budget) {
    budget_ = std::max(1, budget);
    budgetTotal_ = budget_;
    initialized_ = true;
    attemptIndex_ = 0;
    history_.clear();
}

void Uac20RecoveryAttemptTracker::reset() {
    attemptIndex_ = 0;
    history_.clear();
    budget_ = budgetTotal_;
}

void Uac20RecoveryAttemptTracker::record(
        Uac20RecoverySignal signal,
        Uac20RecoveryDecisionAction decision,
        const std::string& source,
        const std::string& reason) {
    if (!initialized_) return;
    if (signal == Uac20RecoverySignal::None) return;

    attemptIndex_ += 1;
    budget_ = std::max(0, budget_ - 1);

    Uac20RecoveryAttempt entry;
    entry.index = attemptIndex_;
    entry.signal = signal;
    entry.decision = decision;
    entry.source = source;
    entry.reason = reason;
    entry.timestampMs = nowMs();
    history_.push_back(entry);

    if (history_.size() > 32) {
        history_.erase(history_.begin());
    }
}

Uac20RecoveryAttemptStats Uac20RecoveryAttemptTracker::snapshot() const {
    Uac20RecoveryAttemptStats stats;
    stats.initialized = initialized_;
    stats.budgetTotal = budgetTotal_;
    stats.budgetRemaining = budget_;

    if (history_.empty()) {
        rebuildReportLocked(stats);
        return stats;
    }

    const Uac20RecoveryAttempt& last = history_.back();
    stats.hasDecision = true;
    stats.attemptIndex = last.index;
    stats.totalAttempts = static_cast<int>(history_.size());
    stats.lastSignal = last.signal;
    stats.lastDecision = last.decision;

    int fullReopen = 0, resetAlt = 0, lowerFormat = 0, androidFallback = 0, transportLost = 0;
    int consecutive = 1;
    for (size_t i = 0; i < history_.size(); ++i) {
        const auto& a = history_[i];
        switch (a.decision) {
            case Uac20RecoveryDecisionAction::FullReopenSameFormat: fullReopen++; break;
            case Uac20RecoveryDecisionAction::ResetAltAndRestart: resetAlt++; break;
            case Uac20RecoveryDecisionAction::LowerFormatSameTransport: lowerFormat++; break;
            case Uac20RecoveryDecisionAction::AndroidHalFallback: androidFallback++; break;
            case Uac20RecoveryDecisionAction::MarkTransportLost: transportLost++; break;
            default: break;
        }
        if (i + 1 < history_.size() && history_[i + 1].decision == last.decision) {
            consecutive++;
        } else if (i + 1 < history_.size()) {
            consecutive = 1;
        }
    }
    // Recalculate consecutiveSameAction properly from the tail
    consecutive = 1;
    for (int i = static_cast<int>(history_.size()) - 2; i >= 0; --i) {
        if (history_[i].decision == last.decision) consecutive++;
        else break;
    }

    stats.consecutiveSameAction = consecutive;
    stats.fullReopenCount = fullReopen;
    stats.resetAltCount = resetAlt;
    stats.lowerFormatCount = lowerFormat;
    stats.androidFallbackCount = androidFallback;
    stats.transportLostCount = transportLost;
    stats.budgetExhausted = budget_ <= 0;

    // Next action: if budget remains, same decision; otherwise escalate.
    if (budget_ <= 0) {
        if (last.decision == Uac20RecoveryDecisionAction::FullReopenSameFormat &&
                consecutive >= 2) {
            stats.nextAction = Uac20RecoveryDecisionAction::LowerFormatSameTransport;
        } else if (last.decision == Uac20RecoveryDecisionAction::LowerFormatSameTransport) {
            stats.nextAction = Uac20RecoveryDecisionAction::AndroidHalFallback;
        } else if (last.decision == Uac20RecoveryDecisionAction::AndroidHalFallback) {
            stats.nextAction = Uac20RecoveryDecisionAction::MarkTransportLost;
        } else {
            stats.nextAction = Uac20RecoveryDecisionAction::AndroidHalFallback;
        }
        stats.fallbackSuggested = true;
    } else {
        stats.nextAction = last.decision;
    }

    rebuildReportLocked(stats);
    return stats;
}

void Uac20RecoveryAttemptTracker::rebuildReportLocked(Uac20RecoveryAttemptStats& stats) const {
    std::ostringstream os;
    os << "initialized=" << (stats.initialized ? "yes" : "no")
       << " hasDecision=" << (stats.hasDecision ? "yes" : "no");
    if (stats.hasDecision) {
        os << " attempt=" << stats.attemptIndex
           << " total=" << stats.totalAttempts
           << " source=" << (history_.empty() ? "" : history_.back().source)
           << " signal=" << uac20RecoverySignalName(stats.lastSignal)
           << " decision=" << uac20RecoveryDecisionName(stats.lastDecision)
           << " next=" << uac20RecoveryDecisionName(stats.nextAction)
           << " fullReopen=" << stats.fullReopenCount
           << " resetAlt=" << stats.resetAltCount
           << " lowerFormat=" << stats.lowerFormatCount
           << " androidFallback=" << stats.androidFallbackCount
           << " transportLost=" << stats.transportLostCount
           << " budgetRemaining=" << stats.budgetRemaining
           << "/" << stats.budgetTotal
           << " fallbackSuggested=" << (stats.fallbackSuggested ? "yes" : "no");
    }
    stats.report = os.str();

    std::ostringstream hs;
    const size_t limit = std::min<size_t>(history_.size(), 8);
    const size_t start = history_.size() > limit ? history_.size() - limit : 0;
    for (size_t i = start; i < history_.size(); ++i) {
        if (i > start) hs << " | ";
        hs << "#" << history_[i].index
           << " " << history_[i].source
           << " signal=" << uac20RecoverySignalName(history_[i].signal)
           << " decision=" << uac20RecoveryDecisionName(history_[i].decision)
           << " next=" << uac20RecoveryDecisionName(
                   (i == history_.size() - 1) ? stats.nextAction : history_[i].decision);
    }
    stats.historySummary = hs.str();
}

std::string describeUac20RecoveryAttemptStats(const Uac20RecoveryAttemptStats& stats) {
    return stats.report;
}

} // namespace rawsmusic::usb
