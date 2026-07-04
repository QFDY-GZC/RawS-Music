#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace rawsmusic::usb {

// UAC20 v2 session lifecycle phase model. Replaces scattered bools with an
// explicit ordered phase tracker, similar to a native USB session lifecycle.
enum class Uac20SessionPhase : int {
    Constructed = 0,
    Closed = 1,
    Opened = 2,
    DescriptorParsed = 3,
    StreamSelected = 4,
    InterfacesClaimed = 5,
    AltReset = 6,
    ClockConfigured = 7,
    PlaybackAltSet = 8,
    FeedbackPrepared = 9,
    OutPrepared = 10,
    EventLoopRunning = 11,
    FeedbackRunning = 12,
    OutProbeRunning = 13,
    RealOutRingReady = 14,
    OutFeederDryRun = 15,
    DebugRealOutRunning = 16,
    ShadowWriteReady = 17,
    RecoveryPlanned = 18,
    Stopping = 19,
    Released = 20,
    Error = 21,
};

struct Uac20SessionPhaseTransition {
    int index = 0;
    Uac20SessionPhase from = Uac20SessionPhase::Constructed;
    Uac20SessionPhase to = Uac20SessionPhase::Constructed;
    int64_t timestampMs = 0;
    bool monotonic = true;
};

struct Uac20SessionPhaseStats {
    Uac20SessionPhase currentPhase = Uac20SessionPhase::Constructed;
    Uac20SessionPhase previousPhase = Uac20SessionPhase::Constructed;
    int phaseRank = 0;
    int transitionCount = 0;
    int nonMonotonicCount = 0;
    int errorCount = 0;
    std::string summary;
    std::string history;
};

class Uac20SessionPhaseTracker {
public:
    Uac20SessionPhaseTracker();

    void transitionTo(Uac20SessionPhase newPhase);
    void reset();
    void markError();
    Uac20SessionPhase current() const;
    Uac20SessionPhaseStats snapshot() const;

private:
    Uac20SessionPhase current_;
    Uac20SessionPhase previous_;
    int transitionCount_;
    int nonMonotonicCount_;
    int errorCount_;
    std::vector<Uac20SessionPhaseTransition> history_;
};

const char* uac20SessionPhaseName(Uac20SessionPhase phase);
int uac20SessionPhaseRank(Uac20SessionPhase phase);
std::string describeUac20SessionPhaseStats(const Uac20SessionPhaseStats& stats);

} // namespace rawsmusic::usb
