#include "usb_uac20_recovery_execution.h"

#include <cstdio>
#include <sstream>

namespace rawsmusic::usb {
namespace {

const char* yesNo(bool v) {
    return v ? "yes" : "no";
}

std::string endpointText(int ep) {
    if (ep <= 0) return "0x00";
    char buf[12];
    snprintf(buf, sizeof(buf), "0x%02X", ep & 0xff);
    return std::string(buf);
}

void addStep(
        Uac20RecoveryExecutionPlanSnapshot& out,
        const char* name,
        const std::string& detail,
        bool destructive = false,
        bool requiresDeviceHandle = false) {
    Uac20RecoveryExecutionStep step;
    step.index = static_cast<int>(out.steps.size());
    step.name = name ? name : "unnamed";
    step.detail = detail;
    step.destructive = destructive;
    step.requiresDeviceHandle = requiresDeviceHandle;
    out.steps.push_back(step);
}

std::string fmtText(int sr, int bits, int channels, int subslot) {
    std::ostringstream os;
    os << sr << "Hz/" << bits << "bit/" << channels << "ch/subslot=" << subslot;
    return os.str();
}

std::string ifaceText(const Uac20RecoveryExecutionPlanInput& input) {
    std::ostringstream os;
    os << "AC=" << input.audioControlInterface
       << " AS=" << input.audioStreamingInterface
       << " alt=" << input.altSetting
       << " out=" << endpointText(input.outEndpoint)
       << " fb=" << endpointText(input.feedbackEndpoint);
    return os.str();
}

void addCommonStopSteps(Uac20RecoveryExecutionPlanSnapshot& out, const Uac20RecoveryExecutionPlanInput& input) {
    if (input.outProbeActive || input.running) {
        addStep(out, "cancel-out-transfers", "Cancel silent/diagnostic OUT transfers before changing alt or format", true, true);
        out.requiresOutRestart = true;
    }
    if (input.feedbackPersistentActive || input.running) {
        addStep(out, "cancel-feedback-transfer", "Cancel persistent explicit feedback transfer and wait for callback", true, true);
        out.requiresFeedbackRestart = true;
    }
    if (input.eventThreadStarted || input.running) {
        addStep(out, "stop-event-loop", "Stop libusb event loop after transfer cancellation", true, false);
        out.requiresStop = true;
    }
}

void addPrepareSteps(Uac20RecoveryExecutionPlanSnapshot& out, const Uac20RecoveryExecutionPlanInput& input) {
    addStep(out, "claim-interfaces", "Claim AudioControl and selected AudioStreaming interfaces if not already claimed: " + ifaceText(input), false, true);
    out.requiresClaimInterfaces = true;
    addStep(out, "set-alt-zero", "Set AS interface to alt0 before clock/rate preparation", true, true);
    out.requiresAltReset = true;
    addStep(out, "configure-clock", "Select/SET_CUR UAC2 clock for target " + fmtText(out.targetSampleRate, out.targetValidBits, out.targetChannels, out.targetSubslotBytes), false, true);
    out.requiresClockSet = true;
    addStep(out, "set-playback-alt", "Set selected playback alt and keep descriptor-selected OUT/feedback endpoints", true, true);
    out.requiresPlaybackAlt = true;
    out.requiresFeedbackRestart = true;
    addStep(out, "start-event-loop", "Start dedicated libusb event loop before feedback and OUT", false, false);
    addStep(out, "enqueue-feedback", "Submit persistent feedback transfer if explicit endpoint is present: fb=" + endpointText(input.feedbackEndpoint), false, true);
    addStep(out, "prepare-out-ring", "Prepare OUT transfer ring/PCM adapter plan; do not feed production PCM in dry-run", false, true);
    out.requiresOutRestart = true;
}

} // namespace

std::string describeUac20RecoveryExecutionStep(const Uac20RecoveryExecutionStep& step) {
    std::ostringstream os;
    os << '#' << step.index << ' ' << step.name
       << " destructive=" << yesNo(step.destructive)
       << " needsHandle=" << yesNo(step.requiresDeviceHandle);
    if (!step.detail.empty()) {
        os << " detail=" << step.detail;
    }
    return os.str();
}

std::string describeUac20RecoveryExecutionPlan(
        const Uac20RecoveryExecutionPlanSnapshot& snapshot) {
    if (!snapshot.initialized) return "initialized=no";
    std::ostringstream os;
    os << "initialized=yes dryRun=yes selected=" << snapshot.candidateIndex
       << " action=" << snapshot.candidateActionName
       << " label=" << snapshot.candidateLabel
       << " fmt=" << fmtText(snapshot.targetSampleRate, snapshot.targetValidBits,
                              snapshot.targetChannels, snapshot.targetSubslotBytes)
       << " steps=" << snapshot.stepCount
       << " stop=" << yesNo(snapshot.requiresStop)
       << " close=" << yesNo(snapshot.requiresClose)
       << " reopen=" << yesNo(snapshot.requiresReopen)
       << " claim=" << yesNo(snapshot.requiresClaimInterfaces)
       << " alt0=" << yesNo(snapshot.requiresAltReset)
       << " clock=" << yesNo(snapshot.requiresClockSet)
       << " playbackAlt=" << yesNo(snapshot.requiresPlaybackAlt)
       << " feedback=" << yesNo(snapshot.requiresFeedbackRestart)
       << " out=" << yesNo(snapshot.requiresOutRestart)
       << " lower=" << yesNo(snapshot.requiresFormatChange)
       << " fallback=" << yesNo(snapshot.requiresAndroidFallback)
       << " lost=" << yesNo(snapshot.marksTransportLost)
       << " terminal=" << yesNo(snapshot.terminal)
       << " blocked=" << yesNo(snapshot.blocked);
    if (!snapshot.blockingReason.empty()) {
        os << " reason=" << snapshot.blockingReason;
    }
    return os.str();
}

Uac20RecoveryExecutionPlanSnapshot buildUac20RecoveryExecutionPlan(
        const Uac20RecoveryExecutionPlanInput& input) {
    Uac20RecoveryExecutionPlanSnapshot out;
    out.initialized = true;
    out.dryRunOnly = true;
    out.hasSelectedCandidate = input.candidatePlan.hasSelected;
    out.candidateIndex = input.candidatePlan.selectedIndex;
    out.candidateAction = static_cast<int>(input.candidatePlan.selected.action);
    out.candidateActionName = uac20RecoveryDecisionName(input.candidatePlan.selected.action);
    out.candidateLabel = input.candidatePlan.selected.label;
    out.targetSampleRate = input.candidatePlan.selected.sampleRate > 0 ? input.candidatePlan.selected.sampleRate : input.currentSampleRate;
    out.targetValidBits = input.candidatePlan.selected.bits > 0 ? input.candidatePlan.selected.bits : input.currentValidBits;
    out.targetSubslotBytes = input.candidatePlan.selected.subslotBytes > 0 ? input.candidatePlan.selected.subslotBytes : input.currentSubslotBytes;
    out.targetChannels = input.channels;

    if (!input.candidatePlan.initialized || !input.candidatePlan.hasSelected) {
        out.blocked = true;
        out.blockingReason = "no-selected-recovery-candidate";
        addStep(out, "continue-diagnostics", "No candidate selected; keep collecting v2 diagnostics", false, false);
        out.stepCount = static_cast<int>(out.steps.size());
        out.stepsSummary = describeUac20RecoveryExecutionStep(out.steps.front());
        out.summary = describeUac20RecoveryExecutionPlan(out);
        return out;
    }

    const auto action = input.candidatePlan.selected.action;
    out.requiresFormatChange = input.candidatePlan.selected.lowerFormat;
    out.requiresAndroidFallback = input.candidatePlan.selected.androidFallback;
    out.marksTransportLost = input.candidatePlan.selected.transportLost;

    switch (action) {
        case Uac20RecoveryDecisionAction::None:
            addStep(out, "continue-current-session", "No recovery action; keep current descriptor/clock/feedback/OUT diagnostics", false, false);
            break;

        case Uac20RecoveryDecisionAction::FullReopenSameFormat:
            addCommonStopSteps(out, input);
            if (input.interfacesClaimed) {
                addStep(out, "set-alt-zero-before-release", "Return AS interface to alt0 before releasing interfaces", true, true);
                addStep(out, "release-interfaces", "Release AS then AC interfaces before closing libusb handle", true, true);
            }
            addStep(out, "close-libusb-handle", "Close libusb handle and wrapped fd state", true, true);
            addStep(out, "reopen-from-fresh-fd", "Kotlin must open a fresh UsbDeviceConnection/fd, then native wraps it", true, false);
            out.requiresClose = true;
            out.requiresReopen = true;
            addStep(out, "parse-descriptors", "Re-read active config descriptor and rebuild native UAC20 model", false, true);
            addPrepareSteps(out, input);
            break;

        case Uac20RecoveryDecisionAction::ResetAltAndRestart:
            addCommonStopSteps(out, input);
            addPrepareSteps(out, input);
            break;

        case Uac20RecoveryDecisionAction::LowerFormatSameTransport:
            addCommonStopSteps(out, input);
            addStep(out, "select-lower-format", "Switch target to lower candidate " + fmtText(out.targetSampleRate, out.targetValidBits, out.targetChannels, out.targetSubslotBytes), true, true);
            out.requiresFormatChange = true;
            addPrepareSteps(out, input);
            break;

        case Uac20RecoveryDecisionAction::AndroidHalFallback:
            addCommonStopSteps(out, input);
            if (input.interfacesClaimed) {
                addStep(out, "release-native-interfaces", "Release claimed native UAC interfaces so Android USB HAL can own the DAC", true, true);
            }
            addStep(out, "surface-android-hal-fallback", "Return a fallback suggestion to Kotlin/UI; do not auto-switch in native dry-run", false, false);
            out.requiresAndroidFallback = true;
            out.terminal = true;
            break;

        case Uac20RecoveryDecisionAction::MarkTransportLost:
            addCommonStopSteps(out, input);
            addStep(out, "mark-transport-lost", "Stop attempting native recovery until USB detach/reattach or fresh fd", true, false);
            out.marksTransportLost = true;
            out.terminal = true;
            break;
    }

    out.stepCount = static_cast<int>(out.steps.size());
    std::ostringstream stepSummary;
    for (size_t i = 0; i < out.steps.size(); ++i) {
        if (i) stepSummary << " | ";
        stepSummary << describeUac20RecoveryExecutionStep(out.steps[i]);
    }
    out.stepsSummary = stepSummary.str();
    out.summary = describeUac20RecoveryExecutionPlan(out);
    return out;
}

} // namespace rawsmusic::usb
