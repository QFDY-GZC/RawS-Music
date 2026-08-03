#pragma once

#include <string>
#include <vector>

#include "usb_uac20_recovery_candidates.h"

namespace rawsmusic::usb {

// Diagnostic-only execution planner for recovery candidates. This does not
// perform any USB operation. It records the native USB sequence that a
// future executor should follow for the selected candidate.
struct Uac20RecoveryExecutionStep {
    int index = 0;
    std::string name;
    std::string detail;
    bool destructive = false;
    bool requiresDeviceHandle = false;
};

struct Uac20RecoveryExecutionPlanInput {
    Uac20RecoveryCandidatePlanStats candidatePlan{};

    int currentSampleRate = 0;
    int currentValidBits = 0;
    int currentSubslotBytes = 0;
    int channels = 0;

    int audioControlInterface = -1;
    int audioStreamingInterface = -1;
    int altSetting = 0;
    int outEndpoint = 0;
    int feedbackEndpoint = 0;

    bool opened = false;
    bool prepared = false;
    bool running = false;
    bool interfacesClaimed = false;
    bool clockConfigured = false;
    bool eventThreadStarted = false;
    bool feedbackPersistentActive = false;
    bool outProbeActive = false;
};

struct Uac20RecoveryExecutionPlanSnapshot {
    bool initialized = false;
    bool dryRunOnly = true;
    bool hasSelectedCandidate = false;
    bool terminal = false;
    bool blocked = false;

    int candidateIndex = -1;
    int candidateAction = 0;
    std::string candidateActionName;
    std::string candidateLabel;

    int targetSampleRate = 0;
    int targetValidBits = 0;
    int targetSubslotBytes = 0;
    int targetChannels = 0;

    bool requiresStop = false;
    bool requiresClose = false;
    bool requiresReopen = false;
    bool requiresClaimInterfaces = false;
    bool requiresAltReset = false;
    bool requiresClockSet = false;
    bool requiresPlaybackAlt = false;
    bool requiresFeedbackRestart = false;
    bool requiresOutRestart = false;
    bool requiresFormatChange = false;
    bool requiresAndroidFallback = false;
    bool marksTransportLost = false;

    int stepCount = 0;
    std::string blockingReason;
    std::string summary;
    std::string stepsSummary;
    std::vector<Uac20RecoveryExecutionStep> steps;
};

Uac20RecoveryExecutionPlanSnapshot buildUac20RecoveryExecutionPlan(
        const Uac20RecoveryExecutionPlanInput& input);

std::string describeUac20RecoveryExecutionStep(const Uac20RecoveryExecutionStep& step);
std::string describeUac20RecoveryExecutionPlan(
        const Uac20RecoveryExecutionPlanSnapshot& snapshot);

} // namespace rawsmusic::usb
