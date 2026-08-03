#pragma once

#include <string>

namespace rawsmusic::usb {

// Native-only UAC2 recovery policy model. It mirrors the shape of mature
// UAC20 stacks: stream errors are classified first, then a transport action is
// selected. Device-specific logs such as TP55/HyperOS are regression cases for
// this policy, not hard-coded branches here.
enum class Uac20RecoverySignal : int {
    None = 0,
    StartFailure = 1,
    UsbNotOutputting = 2,
    FeedbackTimeout = 3,
    FeedbackTransferError = 4,
    OutSubmitError = 5,
    OutTransferError = 6,
    ClockRejected = 7,
    DeviceDetached = 8,
};

enum class Uac20RecoveryDecisionAction : int {
    None = 0,
    ResetAltAndRestart = 1,
    FullReopenSameFormat = 2,
    LowerFormatSameTransport = 3,
    AndroidHalFallback = 4,
    MarkTransportLost = 5,
};

struct Uac20RecoveryPolicyInput {
    Uac20RecoverySignal signal = Uac20RecoverySignal::None;

    bool descriptorHasExplicitFeedback = false;
    bool explicitFeedbackSelected = false;
    bool persistentFeedbackLocked = false;
    bool fullReopenAllowed = true;
    bool resetAltAllowed = true;
    bool lowerFormatAvailable = false;
    bool androidHalFallbackAllowed = true;

    // Recent transport health. A value <= 0 means unknown/not sampled yet.
    double outCompletionRatio = 0.0;
    int stallMs = 0;
    int feedbackErrorCount = 0;
    int submitErrorCount = 0;
    int transferErrorCount = 0;
};

struct Uac20RecoveryPolicyDecision {
    Uac20RecoveryDecisionAction action = Uac20RecoveryDecisionAction::None;

    // Explicitly model flags that legacy recovery used to mix into profile
    // mutation. In the v2 policy, disabling descriptor feedback is not a first
    // response to under-output; a real feedback endpoint remains first-class.
    bool disableFeedback = false;
    bool keepExplicitFeedback = true;
    bool requireFullReopen = false;
    bool requireAltReset = false;
    bool lowerFormat = false;
    bool androidHalFallback = false;
    bool markTransportLost = false;

    std::string reason;
    std::string summary;
};

Uac20RecoveryPolicyDecision decideUac20Recovery(const Uac20RecoveryPolicyInput& input);

const char* uac20RecoverySignalName(Uac20RecoverySignal signal);
const char* uac20RecoveryDecisionName(Uac20RecoveryDecisionAction action);
std::string describeUac20RecoveryPolicyInput(const Uac20RecoveryPolicyInput& input);
std::string describeUac20RecoveryPolicyDecision(const Uac20RecoveryPolicyDecision& decision);

} // namespace rawsmusic::usb
