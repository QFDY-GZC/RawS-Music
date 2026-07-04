#include "usb_uac20_phase.h"

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

Uac20SessionPhaseTracker::Uac20SessionPhaseTracker()
    : current_(Uac20SessionPhase::Constructed)
    , previous_(Uac20SessionPhase::Constructed)
    , transitionCount_(0)
    , nonMonotonicCount_(0)
    , errorCount_(0) {
}

void Uac20SessionPhaseTracker::transitionTo(Uac20SessionPhase newPhase) {
    if (newPhase == current_) return;

    const int oldRank = uac20SessionPhaseRank(current_);
    const int newRank = uac20SessionPhaseRank(newPhase);
    const bool monotonic = newRank >= oldRank;

    Uac20SessionPhaseTransition t;
    t.index = transitionCount_ + 1;
    t.from = current_;
    t.to = newPhase;
    t.timestampMs = nowMs();
    t.monotonic = monotonic;
    history_.push_back(t);
    if (history_.size() > 64) {
        history_.erase(history_.begin());
    }

    previous_ = current_;
    current_ = newPhase;
    transitionCount_++;
    if (!monotonic) nonMonotonicCount_++;
}

void Uac20SessionPhaseTracker::reset() {
    current_ = Uac20SessionPhase::Constructed;
    previous_ = Uac20SessionPhase::Constructed;
    transitionCount_ = 0;
    nonMonotonicCount_ = 0;
    errorCount_ = 0;
    history_.clear();
}

void Uac20SessionPhaseTracker::markError() {
    errorCount_++;
}

Uac20SessionPhase Uac20SessionPhaseTracker::current() const {
    return current_;
}

Uac20SessionPhaseStats Uac20SessionPhaseTracker::snapshot() const {
    Uac20SessionPhaseStats stats;
    stats.currentPhase = current_;
    stats.previousPhase = previous_;
    stats.phaseRank = uac20SessionPhaseRank(current_);
    stats.transitionCount = transitionCount_;
    stats.nonMonotonicCount = nonMonotonicCount_;
    stats.errorCount = errorCount_;

    std::ostringstream os;
    os << "phase=" << uac20SessionPhaseName(current_)
       << " prev=" << uac20SessionPhaseName(previous_)
       << " rank=" << stats.phaseRank
       << " transitions=" << transitionCount_
       << " nonMonotonic=" << nonMonotonicCount_
       << " errors=" << errorCount_;
    stats.summary = os.str();

    std::ostringstream hs;
    const size_t limit = std::min<size_t>(history_.size(), 16);
    const size_t start = history_.size() > limit ? history_.size() - limit : 0;
    for (size_t i = start; i < history_.size(); ++i) {
        if (i > start) hs << " | ";
        hs << "#" << history_[i].index
           << " " << uac20SessionPhaseName(history_[i].from)
           << "->" << uac20SessionPhaseName(history_[i].to);
        if (!history_[i].monotonic) hs << "(non-monotonic)";
    }
    stats.history = hs.str();
    return stats;
}

const char* uac20SessionPhaseName(Uac20SessionPhase phase) {
    switch (phase) {
        case Uac20SessionPhase::Constructed: return "Constructed";
        case Uac20SessionPhase::Closed: return "Closed";
        case Uac20SessionPhase::Opened: return "Opened";
        case Uac20SessionPhase::DescriptorParsed: return "DescriptorParsed";
        case Uac20SessionPhase::StreamSelected: return "StreamSelected";
        case Uac20SessionPhase::InterfacesClaimed: return "InterfacesClaimed";
        case Uac20SessionPhase::AltReset: return "AltReset";
        case Uac20SessionPhase::ClockConfigured: return "ClockConfigured";
        case Uac20SessionPhase::PlaybackAltSet: return "PlaybackAltSet";
        case Uac20SessionPhase::FeedbackPrepared: return "FeedbackPrepared";
        case Uac20SessionPhase::OutPrepared: return "OutPrepared";
        case Uac20SessionPhase::EventLoopRunning: return "EventLoopRunning";
        case Uac20SessionPhase::FeedbackRunning: return "FeedbackRunning";
        case Uac20SessionPhase::OutProbeRunning: return "OutProbeRunning";
        case Uac20SessionPhase::RealOutRingReady: return "RealOutRingReady";
        case Uac20SessionPhase::OutFeederDryRun: return "OutFeederDryRun";
        case Uac20SessionPhase::DebugRealOutRunning: return "DebugRealOutRunning";
        case Uac20SessionPhase::ShadowWriteReady: return "ShadowWriteReady";
        case Uac20SessionPhase::RecoveryPlanned: return "RecoveryPlanned";
        case Uac20SessionPhase::Stopping: return "Stopping";
        case Uac20SessionPhase::Released: return "Released";
        case Uac20SessionPhase::Error: return "Error";
    }
    return "Unknown";
}

int uac20SessionPhaseRank(Uac20SessionPhase phase) {
    return static_cast<int>(phase);
}

std::string describeUac20SessionPhaseStats(const Uac20SessionPhaseStats& stats) {
    return stats.summary;
}

} // namespace rawsmusic::usb
