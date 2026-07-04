#include "usb_uac20_recovery_policy.h"

#include <sstream>

namespace rawsmusic::usb {
namespace {

Uac20RecoveryPolicyDecision makeDecision(
        Uac20RecoveryDecisionAction action,
        const char* reason) {
    Uac20RecoveryPolicyDecision d;
    d.action = action;
    d.reason = reason ? reason : "";
    d.keepExplicitFeedback = true;
    switch (action) {
        case Uac20RecoveryDecisionAction::ResetAltAndRestart:
            d.requireAltReset = true;
            break;
        case Uac20RecoveryDecisionAction::FullReopenSameFormat:
            d.requireFullReopen = true;
            break;
        case Uac20RecoveryDecisionAction::LowerFormatSameTransport:
            d.lowerFormat = true;
            break;
        case Uac20RecoveryDecisionAction::AndroidHalFallback:
            d.androidHalFallback = true;
            break;
        case Uac20RecoveryDecisionAction::MarkTransportLost:
            d.markTransportLost = true;
            break;
        case Uac20RecoveryDecisionAction::None:
            break;
    }
    return d;
}

Uac20RecoveryPolicyDecision finish(Uac20RecoveryPolicyDecision d) {
    d.summary = describeUac20RecoveryPolicyDecision(d);
    return d;
}

} // namespace

const char* uac20RecoverySignalName(Uac20RecoverySignal signal) {
    switch (signal) {
        case Uac20RecoverySignal::None: return "None";
        case Uac20RecoverySignal::StartFailure: return "StartFailure";
        case Uac20RecoverySignal::UsbNotOutputting: return "UsbNotOutputting";
        case Uac20RecoverySignal::FeedbackTimeout: return "FeedbackTimeout";
        case Uac20RecoverySignal::FeedbackTransferError: return "FeedbackTransferError";
        case Uac20RecoverySignal::OutSubmitError: return "OutSubmitError";
        case Uac20RecoverySignal::OutTransferError: return "OutTransferError";
        case Uac20RecoverySignal::ClockRejected: return "ClockRejected";
        case Uac20RecoverySignal::DeviceDetached: return "DeviceDetached";
    }
    return "Unknown";
}

const char* uac20RecoveryDecisionName(Uac20RecoveryDecisionAction action) {
    switch (action) {
        case Uac20RecoveryDecisionAction::None: return "None";
        case Uac20RecoveryDecisionAction::ResetAltAndRestart: return "ResetAltAndRestart";
        case Uac20RecoveryDecisionAction::FullReopenSameFormat: return "FullReopenSameFormat";
        case Uac20RecoveryDecisionAction::LowerFormatSameTransport: return "LowerFormatSameTransport";
        case Uac20RecoveryDecisionAction::AndroidHalFallback: return "AndroidHalFallback";
        case Uac20RecoveryDecisionAction::MarkTransportLost: return "MarkTransportLost";
    }
    return "Unknown";
}

Uac20RecoveryPolicyDecision decideUac20Recovery(const Uac20RecoveryPolicyInput& input) {
    if (input.signal == Uac20RecoverySignal::None) {
        return finish(makeDecision(Uac20RecoveryDecisionAction::None, "idle"));
    }

    if (input.signal == Uac20RecoverySignal::DeviceDetached) {
        return finish(makeDecision(
                Uac20RecoveryDecisionAction::MarkTransportLost,
                "device detached; stop stream and let upper layer reopen after attach"));
    }

    if (input.signal == Uac20RecoverySignal::UsbNotOutputting) {
        if (input.descriptorHasExplicitFeedback) {
            auto d = makeDecision(
                    input.fullReopenAllowed
                            ? Uac20RecoveryDecisionAction::FullReopenSameFormat
                            : Uac20RecoveryDecisionAction::ResetAltAndRestart,
                    "under-output does not prove feedback is bad; keep descriptor feedback and rebuild stream");
            d.disableFeedback = false;
            d.keepExplicitFeedback = true;
            d.requireFullReopen = input.fullReopenAllowed;
            d.requireAltReset = !input.fullReopenAllowed && input.resetAltAllowed;
            return finish(d);
        }
        if (input.resetAltAllowed) {
            return finish(makeDecision(
                    Uac20RecoveryDecisionAction::ResetAltAndRestart,
                    "under-output without feedback endpoint; reset alt before lowering format"));
        }
        if (input.lowerFormatAvailable) {
            return finish(makeDecision(
                    Uac20RecoveryDecisionAction::LowerFormatSameTransport,
                    "under-output without feedback endpoint; lower format after reset-alt is unavailable"));
        }
    }

    if (input.signal == Uac20RecoverySignal::FeedbackTimeout ||
        input.signal == Uac20RecoverySignal::FeedbackTransferError) {
        if (input.persistentFeedbackLocked && input.resetAltAllowed) {
            return finish(makeDecision(
                    Uac20RecoveryDecisionAction::ResetAltAndRestart,
                    "feedback was previously locked; treat error as stream disturbance, not no-feedback proof"));
        }
        if (input.fullReopenAllowed) {
            return finish(makeDecision(
                    Uac20RecoveryDecisionAction::FullReopenSameFormat,
                    "feedback not stable yet; full reopen before considering fallback"));
        }
        if (input.lowerFormatAvailable) {
            return finish(makeDecision(
                    Uac20RecoveryDecisionAction::LowerFormatSameTransport,
                    "feedback not stable and reopen unavailable; lower format"));
        }
    }

    if (input.signal == Uac20RecoverySignal::OutSubmitError ||
        input.signal == Uac20RecoverySignal::OutTransferError ||
        input.signal == Uac20RecoverySignal::StartFailure ||
        input.signal == Uac20RecoverySignal::ClockRejected) {
        if (input.resetAltAllowed) {
            return finish(makeDecision(
                    Uac20RecoveryDecisionAction::ResetAltAndRestart,
                    "stream/control error; reset alt before changing transport model"));
        }
        if (input.fullReopenAllowed) {
            return finish(makeDecision(
                    Uac20RecoveryDecisionAction::FullReopenSameFormat,
                    "stream/control error; full reopen before changing format"));
        }
        if (input.lowerFormatAvailable) {
            return finish(makeDecision(
                    Uac20RecoveryDecisionAction::LowerFormatSameTransport,
                    "stream/control error; lower format after reset/reopen unavailable"));
        }
    }

    if (input.androidHalFallbackAllowed) {
        return finish(makeDecision(
                Uac20RecoveryDecisionAction::AndroidHalFallback,
                "native recovery choices exhausted"));
    }

    return finish(makeDecision(
            Uac20RecoveryDecisionAction::MarkTransportLost,
            "native recovery choices exhausted and Android fallback disabled"));
}

std::string describeUac20RecoveryPolicyInput(const Uac20RecoveryPolicyInput& input) {
    std::ostringstream os;
    os << "signal=" << uac20RecoverySignalName(input.signal)
       << " descFb=" << (input.descriptorHasExplicitFeedback ? 1 : 0)
       << " explicitSelected=" << (input.explicitFeedbackSelected ? 1 : 0)
       << " fbLocked=" << (input.persistentFeedbackLocked ? 1 : 0)
       << " fullReopenAllowed=" << (input.fullReopenAllowed ? 1 : 0)
       << " resetAltAllowed=" << (input.resetAltAllowed ? 1 : 0)
       << " lowerFmt=" << (input.lowerFormatAvailable ? 1 : 0)
       << " androidFallback=" << (input.androidHalFallbackAllowed ? 1 : 0)
       << " outRatio=" << input.outCompletionRatio
       << " stallMs=" << input.stallMs
       << " fbErr=" << input.feedbackErrorCount
       << " submitErr=" << input.submitErrorCount
       << " xferErr=" << input.transferErrorCount;
    return os.str();
}

std::string describeUac20RecoveryPolicyDecision(const Uac20RecoveryPolicyDecision& decision) {
    std::ostringstream os;
    os << "action=" << uac20RecoveryDecisionName(decision.action)
       << " disableFb=" << (decision.disableFeedback ? 1 : 0)
       << " keepExplicitFb=" << (decision.keepExplicitFeedback ? 1 : 0)
       << " fullReopen=" << (decision.requireFullReopen ? 1 : 0)
       << " resetAlt=" << (decision.requireAltReset ? 1 : 0)
       << " lowerFmt=" << (decision.lowerFormat ? 1 : 0)
       << " androidFallback=" << (decision.androidHalFallback ? 1 : 0)
       << " transportLost=" << (decision.markTransportLost ? 1 : 0)
       << " reason=" << decision.reason;
    return os.str();
}

} // namespace rawsmusic::usb
