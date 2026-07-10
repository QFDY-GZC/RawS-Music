#pragma once

#include <string>

namespace rawsmusic::usb {

struct Uac20RecoveryExecutorInput {
    bool executionEnabled = false;
    bool sessionOpened = false;
    bool sessionPrepared = false;
    bool sessionRunning = false;
    bool interfacesClaimed = false;
    bool explicitFeedbackSelected = true;

    int candidateAction = 0;
    int targetSampleRate = 0;
    int targetBits = 0;
    int targetSubslotBytes = 0;
    int targetChannels = 0;

    bool requiresStop = false;
    bool requiresClose = false;
    bool requiresReopen = false;
    bool requiresAltReset = false;
    bool requiresClockSet = false;
    bool requiresPlaybackAlt = false;
    bool requiresFeedbackRestart = false;
    bool requiresOutRestart = false;
};

struct Uac20RecoveryExecutorResult {
    bool initialized = false;
    bool enabled = false;
    bool attempted = false;
    bool executed = false;
    bool blocked = true;
    bool sameSessionRestartEligible = false;
    bool fullReopenEligible = false;
    bool destructive = false;
    int candidateAction = 0;
    int stepBudget = 0;
    std::string blockedReason;
    std::string summary;
};

Uac20RecoveryExecutorResult evaluateUac20RecoveryExecutor(const Uac20RecoveryExecutorInput& input);
std::string describeUac20RecoveryExecutorResult(const Uac20RecoveryExecutorResult& result);

} // namespace rawsmusic::usb
