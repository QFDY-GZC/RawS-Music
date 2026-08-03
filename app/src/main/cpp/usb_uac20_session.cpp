#include "usb_uac20_session.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <sstream>
#include <thread>
#include <vector>
#include <unistd.h>

#include "libusb.h"
#include "usb_uac20_clock.h"
#include "usb_uac20_diagnostics.h"
#include "usb_uac20_feedback.h"
#include "usb_uac20_out_feeder.h"
#include "usb_uac20_packet_scheduler.h"
#include "usb_uac20_pcm_adapter.h"
#include "usb_uac20_phase.h"
#include "usb_uac20_policy.h"
#include "usb_uac20_real_out_ring.h"
#include "usb_uac20_real_out_submitter.h"
#include "usb_uac20_playback_guard.h"
#include "usb_uac20_recovery_executor.h"
#include "usb_uac20_format_fallback.h"
#include "usb_uac20_recovery_attempt.h"
#include "usb_uac20_recovery_candidates.h"
#include "usb_uac20_recovery_execution.h"
#include "usb_uac20_recovery_policy.h"
#include "usb_uac20_write_ring.h"

#define TAG "RawUac20Session"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace rawsmusic::usb {
namespace {

std::string jsonEscape(const std::string& input) {
    std::string out;
    out.reserve(input.size() + 8);
    for (const char c : input) {
        switch (c) {
            case '\\': out += "\\\\"; break;
            case '"': out += "\\\""; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out;
}


void appendSummary(std::ostringstream& os, const std::string& item) {
    if (item.empty()) return;
    if (os.tellp() > 0) os << "; ";
    os << item;
}

void closeOwnedFd(int* fd) {
    if (fd == nullptr || *fd < 0) return;
    const int oldFd = *fd;
    *fd = -1;
    close(oldFd);
}

std::string configureAndroidLibusbNoDeviceDiscovery() {
#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000106)
    // Android UsbManager already handed us a permitted fd.  Letting libusb scan
    // /dev/bus/usb during libusb_init can fail with LIBUSB_ERROR_IO on modern
    // Android/MIUI.  This mirrors the proven legacy usb_audio_engine.cpp path:
    // gate on LIBUSB_API_VERSION, not defined(LIBUSB_OPTION_NO_DEVICE_DISCOVERY),
    // because the option is an enum constant in many libusb headers, not a macro.
    const int rc = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    std::ostringstream os;
    os << "no-device-discovery rc=" << rc
       << " api=0x" << std::hex << LIBUSB_API_VERSION << std::dec;
    return os.str();
#else
    std::ostringstream os;
    os << "no-device-discovery unavailable";
#ifdef LIBUSB_API_VERSION
    os << " api=0x" << std::hex << LIBUSB_API_VERSION << std::dec;
#else
    os << " api=missing";
#endif
    return os.str();
#endif
}

std::mutex& libusbOpenCloseMutex() {
    static std::mutex mutex;
    return mutex;
}

std::string libusbRcSummary(const char* op, int rc, int attempt) {
    std::ostringstream os;
    os << (op ? op : "libusb") << " rc=" << rc << " attempt=" << attempt;
    return os.str();
}

int initLibusbContextWithRetry(libusb_context** outContext, std::string* summary) {
    if (outContext == nullptr) return -1;
    *outContext = nullptr;

    int lastRc = 0;
    std::ostringstream attempts;
    constexpr int kMaxAttempts = 3;
    constexpr int kDelayMs[kMaxAttempts] = {0, 25, 100};
    for (int attempt = 1; attempt <= kMaxAttempts; ++attempt) {
        if (kDelayMs[attempt - 1] > 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(kDelayMs[attempt - 1]));
        }
        libusb_context* ctx = nullptr;
        const int rc = libusb_init(&ctx);
        lastRc = rc;
        if (rc == 0 && ctx != nullptr) {
            *outContext = ctx;
            if (summary != nullptr) {
                attempts << "libusb_init ok attempt=" << attempt;
                *summary = attempts.str();
            }
            return 0;
        }

        if (attempts.tellp() > 0) attempts << "; ";
        attempts << libusbRcSummary("libusb_init", rc, attempt);
        LOGW("libusb_init failed rc=%d attempt=%d", rc, attempt);
        if (ctx != nullptr) {
            libusb_exit(ctx);
            ctx = nullptr;
        }
    }
    if (summary != nullptr) *summary = attempts.str();
    return lastRc != 0 ? lastRc : -1;
}

int defaultSourceSubslotBytes(int validBits) {
    if (validBits <= 16) return 2;
    if (validBits <= 24) {
        // FFmpeg usually hands RawSMusic 24-bit PCM as signed 32-bit samples.
        // The selected USB device container may still be packed S24_3LE. Keep
        // this assumption native-local so Kotlin does not need to model every
        // PCM container during v2 migration.
        return 4;
    }
    return 4;
}

void capturePcmAdapterStats(Uac20RuntimeState& state, const Uac20PcmAdapterStats& stats) {
    state.pcmAdapterConfigured = stats.configured;
    state.pcmAdapterMode = static_cast<int>(stats.mode);
    state.pcmAdapterSourceFrameBytes = stats.sourceFrameBytes;
    state.pcmAdapterDeviceFrameBytes = stats.deviceFrameBytes;
    state.pcmAdapterLastInputBytes = stats.lastInputBytes;
    state.pcmAdapterLastOutputBytes = stats.lastOutputBytes;
    state.pcmAdapterLastRemainderBytes = stats.lastRemainderBytes;
    state.pcmAdapterTotalInputBytes = stats.totalInputBytes;
    state.pcmAdapterTotalOutputBytes = stats.totalOutputBytes;
    state.pcmAdapterConvertCalls = stats.convertCalls;
    state.pcmAdapterUnalignedCalls = stats.unalignedCalls;
    state.pcmAdapterSummary = describeUac20PcmAdapterStats(stats);
}

void capturePcmPipelineStats(Uac20RuntimeState& state, const RawPcmPipelineStats& stats) {
    state.pcmPipelineConfigured = stats.configured;
    state.pcmPipelineResamplerRequired = stats.resamplerRequired;
    state.pcmPipelineResamplerReady = stats.resamplerInitialized;
    state.pcmPipelineSourceFrameBytes = stats.sourceFrameBytes;
    state.pcmPipelineSWRFrameBytes = stats.swrFrameBytes;
    state.pcmPipelineDeviceFrameBytes = stats.deviceFrameBytes;
    state.pcmPipelineTotalInputBytes = stats.totalInputBytes;
    state.pcmPipelineTotalConsumedBytes = stats.totalConsumedInputBytes;
    state.pcmPipelineTotalProducedBytes = stats.totalProducedDeviceBytes;
    state.pcmPipelineProcessCalls = stats.processCalls;
    state.pcmPipelineUnalignedCalls = stats.unalignedInputCalls;
    state.pcmPipelineZeroOutputCalls = stats.zeroOutputCalls;
    state.pcmPipelineLastInputBytes = stats.lastInputBytes;
    state.pcmPipelineLastConsumedBytes = stats.lastConsumedInputBytes;
    state.pcmPipelineLastOutputBytes = stats.lastProducedDeviceBytes;
    state.pcmPipelineLastRemainderBytes = stats.lastDroppedRemainderBytes;
    state.pcmPipelineLastErrorCode = stats.lastErrorCode;
    state.pcmPipelineSummary = describeRawPcmPipelineStats(stats);
}

void capturePhaseStats(Uac20RuntimeState& state, const Uac20SessionPhaseStats& stats);

void runRecoveryPolicy(
        Uac20RuntimeState& state,
        const Uac20Params& params,
        Uac20RecoverySignal signal,
        const char* source,
        Uac20RecoveryAttemptTracker& attemptTracker,
        Uac20RecoveryCandidatePlanner& candidatePlanner,
        Uac20SessionPhaseTracker& phaseTracker) {
    const bool hasFeedbackEndpoint = state.feedbackEndpoint != 0;
    Uac20RecoveryPolicyInput input;
    input.signal = signal;
    input.descriptorHasExplicitFeedback = hasFeedbackEndpoint;
    input.explicitFeedbackSelected = state.pacingMode == UacPacingMode::ExplicitFeedback;
    input.persistentFeedbackLocked = state.feedbackPersistentStarted && state.feedbackCompleteCount > 0;
    input.fullReopenAllowed = params.fullReopenOnNotOutputting;
    input.resetAltAllowed = params.resetAltBeforeStart;
    input.lowerFormatAvailable = false;
    input.androidHalFallbackAllowed = true;
    input.outCompletionRatio = state.outProbeCompletionRatio;
    input.stallMs = 0;
    input.feedbackErrorCount = state.feedbackErrorCount;
    input.submitErrorCount = state.submitErrorCount;
    input.transferErrorCount = state.transferErrorCount;

    const auto decision = decideUac20Recovery(input);
    state.recoveryPolicySignal = static_cast<int>(signal);
    state.recoveryPolicyDecision = static_cast<int>(decision.action);
    state.recoveryPolicyDisableFeedback = decision.disableFeedback;
    state.recoveryPolicyKeepExplicitFeedback = decision.keepExplicitFeedback;
    state.recoveryPolicyFullReopen = decision.requireFullReopen;
    state.recoveryPolicyResetAlt = decision.requireAltReset;
    state.recoveryPolicyLowerFormat = decision.lowerFormat;
    state.recoveryPolicyAndroidFallback = decision.androidHalFallback;
    state.recoveryPolicyTransportLost = decision.markTransportLost;
    state.recoveryPolicySource = source ? source : "";
    state.recoveryPolicyInputSummary = describeUac20RecoveryPolicyInput(input);
    state.recoveryPolicyDecisionSummary = decision.summary;

    // 0024: record this attempt so budget and nextAction are observable
    attemptTracker.record(signal, decision.action, source ? source : "", decision.reason);
    const auto attemptStats = attemptTracker.snapshot();
    state.recoveryAttemptInitialized = attemptStats.initialized;
    state.recoveryAttemptHasDecision = attemptStats.hasDecision;
    state.recoveryAttemptBudgetExhausted = attemptStats.budgetExhausted;
    state.recoveryAttemptFallbackSuggested = attemptStats.fallbackSuggested;
    state.recoveryAttemptIndex = attemptStats.attemptIndex;
    state.recoveryAttemptTotal = attemptStats.totalAttempts;
    state.recoveryAttemptNextAction = static_cast<int>(attemptStats.nextAction);
    state.recoveryAttemptNextActionName = uac20RecoveryDecisionName(attemptStats.nextAction);
    state.recoveryAttemptFullReopenCount = attemptStats.fullReopenCount;
    state.recoveryAttemptResetAltCount = attemptStats.resetAltCount;
    state.recoveryAttemptLowerFormatCount = attemptStats.lowerFormatCount;
    state.recoveryAttemptBudgetRemaining = attemptStats.budgetRemaining;
    state.recoveryAttemptReport = attemptStats.report;
    state.recoveryAttemptHistory = attemptStats.historySummary;

    // 0025: build candidate plan from the decision
    candidatePlanner.build(
            decision.action,
            state.sampleRate,
            state.validBits,
            state.subslotBytes,
            hasFeedbackEndpoint,
            decision.keepExplicitFeedback);
    const auto candidateStats = candidatePlanner.snapshot();
    state.recoveryCandidatePlanInitialized = candidateStats.initialized;
    state.recoveryCandidatePlanHasCandidates = candidateStats.hasCandidates;
    state.recoveryCandidatePlanHasSelected = candidateStats.hasSelected;
    state.recoveryCandidateCount = candidateStats.candidateCount;
    state.recoveryCandidateSelectedIndex = candidateStats.selectedIndex;
    state.recoveryCandidateSelectedAction = static_cast<int>(candidateStats.selected.action);
    state.recoveryCandidateSelectedSampleRate = candidateStats.selected.sampleRate;
    state.recoveryCandidateSelectedBits = candidateStats.selected.bits;
    state.recoveryCandidateSelectedSubslotBytes = candidateStats.selected.subslotBytes;
    state.recoveryCandidateSelectedKeepExplicitFeedback = candidateStats.selected.keepExplicitFeedback;
    state.recoveryCandidateSelectedDisableFeedback = candidateStats.selected.disableFeedback;
    state.recoveryCandidateSelectedFullReopen = candidateStats.selected.requireFullReopen;
    state.recoveryCandidateSelectedResetAlt = candidateStats.selected.requireAltReset;
    state.recoveryCandidateSelectedLowerFormat = candidateStats.selected.lowerFormat;
    state.recoveryCandidateSelectedAndroidFallback = candidateStats.selected.androidFallback;
    state.recoveryCandidatePlanReport = candidateStats.report;
    state.recoveryCandidateList = candidateStats.candidateList;

    // 0026/0027: build execution dry-run plan from the selected candidate
    Uac20RecoveryExecutionPlanInput execInput;
    execInput.candidatePlan = candidateStats;
    execInput.currentSampleRate = state.sampleRate;
    execInput.currentValidBits = state.validBits;
    execInput.currentSubslotBytes = state.subslotBytes;
    execInput.channels = state.channels;
    execInput.audioControlInterface = state.audioControlInterface;
    execInput.audioStreamingInterface = state.audioStreamingInterface;
    execInput.altSetting = state.altSetting;
    execInput.outEndpoint = state.outEndpoint;
    execInput.feedbackEndpoint = state.feedbackEndpoint;
    execInput.opened = state.opened;
    execInput.prepared = state.prepared;
    execInput.running = state.running;
    execInput.interfacesClaimed = state.interfacesClaimed;
    execInput.clockConfigured = state.clockConfigured;
    execInput.eventThreadStarted = state.eventThreadStarted;
    execInput.feedbackPersistentActive = state.feedbackPersistentStarted;
    execInput.outProbeActive = state.outProbeAttempted;

    const auto execPlan = buildUac20RecoveryExecutionPlan(execInput);
    state.recoveryExecutionPlanInitialized = execPlan.initialized;
    state.recoveryExecutionDryRunOnly = execPlan.dryRunOnly;
    state.recoveryExecutionHasSelectedCandidate = execPlan.hasSelectedCandidate;
    state.recoveryExecutionTerminal = execPlan.terminal;
    state.recoveryExecutionBlocked = execPlan.blocked;
    state.recoveryExecutionCandidateIndex = execPlan.candidateIndex;
    state.recoveryExecutionCandidateAction = execPlan.candidateAction;
    state.recoveryExecutionCandidateActionName = execPlan.candidateActionName;
    state.recoveryExecutionCandidateLabel = execPlan.candidateLabel;
    state.recoveryExecutionTargetSampleRate = execPlan.targetSampleRate;
    state.recoveryExecutionTargetBits = execPlan.targetValidBits;
    state.recoveryExecutionTargetSubslotBytes = execPlan.targetSubslotBytes;
    state.recoveryExecutionTargetChannels = execPlan.targetChannels;
    state.recoveryExecutionStepCount = execPlan.stepCount;
    state.recoveryExecutionRequiresStop = execPlan.requiresStop;
    state.recoveryExecutionRequiresClose = execPlan.requiresClose;
    state.recoveryExecutionRequiresReopen = execPlan.requiresReopen;
    state.recoveryExecutionRequiresClaimInterfaces = execPlan.requiresClaimInterfaces;
    state.recoveryExecutionRequiresAltReset = execPlan.requiresAltReset;
    state.recoveryExecutionRequiresClockSet = execPlan.requiresClockSet;
    state.recoveryExecutionRequiresPlaybackAlt = execPlan.requiresPlaybackAlt;
    state.recoveryExecutionRequiresFeedbackRestart = execPlan.requiresFeedbackRestart;
    state.recoveryExecutionRequiresOutRestart = execPlan.requiresOutRestart;
    state.recoveryExecutionRequiresFormatChange = execPlan.requiresFormatChange;
    state.recoveryExecutionRequiresAndroidFallback = execPlan.requiresAndroidFallback;
    state.recoveryExecutionMarksTransportLost = execPlan.marksTransportLost;
    state.recoveryExecutionBlockingReason = execPlan.blockingReason;
    state.recoveryExecutionPlanSummary = execPlan.summary;
    state.recoveryExecutionStepsSummary = execPlan.stepsSummary;

    // 0030: transition to RecoveryPlanned after execution dry-run is generated
    phaseTracker.transitionTo(Uac20SessionPhase::RecoveryPlanned);
    capturePhaseStats(state, phaseTracker.snapshot());
}

void capturePhaseStats(Uac20RuntimeState& state, const Uac20SessionPhaseStats& stats) {
    state.sessionPhase = static_cast<int>(stats.currentPhase);
    state.sessionPhaseName = uac20SessionPhaseName(stats.currentPhase);
    state.sessionPreviousPhase = static_cast<int>(stats.previousPhase);
    state.sessionPreviousPhaseName = uac20SessionPhaseName(stats.previousPhase);
    state.sessionPhaseRank = stats.phaseRank;
    state.sessionPhaseTransitionCount = stats.transitionCount;
    state.sessionPhaseNonMonotonicCount = stats.nonMonotonicCount;
    state.sessionPhaseErrorCount = stats.errorCount;
    state.sessionPhaseSummary = stats.summary;
    state.sessionPhaseHistory = stats.history;
}

} // namespace

Uac20Session::Uac20Session() = default;

Uac20Session::~Uac20Session() {
    close("destructor");
}

bool Uac20Session::openFromFd(int fd) {
    close("openFromFd");
    state_.opened = false;
    phaseTracker_.reset();
    phaseTracker_.transitionTo(Uac20SessionPhase::Closed);
    capturePhaseStats(state_, phaseTracker_.snapshot());

    // Align the split UAC20 open path with the proven legacy native engine:
    // never let libusb own the Java-side fd directly.  Work on a native dup so
    // the Java UsbDeviceConnection lifecycle cannot invalidate our wrapped fd.
    const int dupFd = dup(fd);
    if (dupFd < 0) {
        std::ostringstream err;
        err << "dup fd failed javaFd=" << fd << " errno=" << errno
            << " msg=" << strerror(errno);
        setError(err.str().c_str());
        LOGE("%s", err.str().c_str());
        return false;
    }
    fd_ = dupFd;
    LOGI("dup fd ok javaFd=%d dupFd=%d", fd, fd_);

    std::string initSummary;
    std::ostringstream openSummary;
    int rc = 0;
    {
        // Android/libusb can fail if it scans /dev/bus/usb without direct file
        // permissions.  Serialize init/wrap/close/exit and set Android's
        // no-discovery option before init, then retry init before declaring the
        // UAC session dead.
        std::lock_guard<std::mutex> lock(libusbOpenCloseMutex());
        appendSummary(openSummary, configureAndroidLibusbNoDeviceDiscovery());
        rc = initLibusbContextWithRetry(&usbContext_, &initSummary);
        appendSummary(openSummary, initSummary);
        if (rc != 0 || usbContext_ == nullptr) {
            std::ostringstream err;
            err << "libusb_init failed rc=" << rc << " javaFd=" << fd
                << " dupFd=" << fd_ << " attempts=" << openSummary.str();
            setError(err.str().c_str());
            LOGE("%s", err.str().c_str());
            if (usbContext_ != nullptr) {
                libusb_exit(usbContext_);
                usbContext_ = nullptr;
            }
            closeOwnedFd(&fd_);
            return false;
        }

        rc = libusb_wrap_sys_device(usbContext_, static_cast<intptr_t>(fd_), &deviceHandle_);
        if (rc != 0 || deviceHandle_ == nullptr) {
            std::ostringstream err;
            err << "libusb_wrap_sys_device failed rc=" << rc << " javaFd=" << fd
                << " dupFd=" << fd_ << " init=" << openSummary.str();
            setError(err.str().c_str());
            LOGE("%s", err.str().c_str());
            if (deviceHandle_ != nullptr) {
                libusb_close(deviceHandle_);
                deviceHandle_ = nullptr;
            }
            if (usbContext_ != nullptr) {
                libusb_exit(usbContext_);
                usbContext_ = nullptr;
            }
            closeOwnedFd(&fd_);
            return false;
        }

        libusb_set_auto_detach_kernel_driver(deviceHandle_, 1);
    }

    state_.opened = true;
    phaseTracker_.transitionTo(Uac20SessionPhase::Opened);
    capturePhaseStats(state_, phaseTracker_.snapshot());
    LOGI("openFromFd ok javaFd=%d dupFd=%d init=%s", fd, fd_, openSummary.str().c_str());
    return parseDescriptors();
}

bool Uac20Session::prepare(const Uac20Params& params) {
    if (!state_.opened || deviceHandle_ == nullptr) {
        setError("prepare called before open");
        return false;
    }

    params_ = params;

    // USB 初始化顺序目标：
    //   descriptor parse -> match stream -> alt0 -> clock source/SET_CUR -> playback alt
    //   -> feedback transfers -> OUT transfers.
    if (!selectStreamLocked(params)) return false;
    phaseTracker_.transitionTo(Uac20SessionPhase::DescriptorParsed);
    phaseTracker_.transitionTo(Uac20SessionPhase::StreamSelected);
    capturePhaseStats(state_, phaseTracker_.snapshot());
    if (!claimInterfacesLocked()) return false;
    phaseTracker_.transitionTo(Uac20SessionPhase::InterfacesClaimed);
    capturePhaseStats(state_, phaseTracker_.snapshot());
    if (params.resetAltBeforeStart && !resetAltLocked()) return false;
    phaseTracker_.transitionTo(Uac20SessionPhase::AltReset);
    capturePhaseStats(state_, phaseTracker_.snapshot());
    if (!configureClockLocked(params)) return false;
    phaseTracker_.transitionTo(Uac20SessionPhase::ClockConfigured);
    capturePhaseStats(state_, phaseTracker_.snapshot());
    if (!setPlaybackAltLocked()) return false;
    phaseTracker_.transitionTo(Uac20SessionPhase::PlaybackAltSet);
    capturePhaseStats(state_, phaseTracker_.snapshot());
    if (!prepareFeedbackLocked(params)) return false;
    phaseTracker_.transitionTo(Uac20SessionPhase::FeedbackPrepared);
    capturePhaseStats(state_, phaseTracker_.snapshot());
    if (!prepareOutTransfersLocked()) return false;
    phaseTracker_.transitionTo(Uac20SessionPhase::OutPrepared);
    capturePhaseStats(state_, phaseTracker_.snapshot());

    state_.prepared = true;
    state_.running = false;
    return true;
}

bool Uac20Session::start() {
    if (!state_.prepared) {
        setError("start called before prepare");
        return false;
    }
    // Persistent feedback is driven by the libusb event thread, so start the
    // event loop first, then enqueue feedback before any OUT transfer.
    recoveryAttemptTracker_.initialize(3);
    recoveryCandidatePlanner_.initialize();
    if (!startEventLoopLocked()) return false;
    if (!submitFeedbackFirstLocked()) return false;
    if (!submitOutTransfersLocked()) return false;
    // 0085: feed-from-write-ring debug playback must not submit the real OUT
    // queue while the ring is still empty. The recent MOONDROP traces showed
    // zeroFilledBytes == transferBytes * submitCount and then no callback. Keep
    // the submitter prepared/visible, but arm it lazily from write() after the
    // first decoded PCM chunk has reached the ring.
    if (rawStreamTakeoverEnabledLocked()) {
        // Step 98: RawUac20Stream real OUT takeover is lazy-started after
        // the new stream FIFO reaches its prebuffer threshold. Keep the legacy
        // submitter suppressed unless the takeover falls back.
        state_.rawStreamRealOutTakeoverEnabled = true;
        state_.rawStreamRealOutTakeoverLegacySuppressed = true;
        state_.rawStreamRealOutTakeoverReason = "armed-at-start";
        captureRawStreamDryRunLocked();
        if (!state_.rawStreamRealOutTakeoverPrepared) {
            fallbackRawStreamRealOutTakeoverLocked("raw-stream-not-prepared-at-start");
        }
    } else if (params_.enableDebugRealOutSubmitter && params_.debugRealOutFeedFromWriteRing) {
        prepareDebugRealOutSubmitterLocked("uac20_v2_start_arm_only");
    } else if (!startDebugRealOutSubmitterLocked("uac20_v2_start")) {
        return false;
    }
    state_.running = true;
    phaseTracker_.transitionTo(Uac20SessionPhase::EventLoopRunning);
    phaseTracker_.transitionTo(Uac20SessionPhase::FeedbackRunning);
    phaseTracker_.transitionTo(Uac20SessionPhase::OutProbeRunning);
    phaseTracker_.transitionTo(Uac20SessionPhase::ShadowWriteReady);
    capturePhaseStats(state_, phaseTracker_.snapshot());
    LOGI("start ok sr=%d bits=%d subslot=%d iface=%d alt=%d out=0x%x fb=0x%x",
         state_.sampleRate, state_.validBits, state_.subslotBytes,
         state_.audioStreamingInterface, state_.altSetting,
         state_.outEndpoint, state_.feedbackEndpoint);
    return true;
}

void Uac20Session::stop(const char* reason) {
    if (state_.running) {
        LOGI("stop reason=%s", reason ? reason : "null");
    }
    rawStreamDryRun_.stop(reason ? reason : "session_stop");
    captureRawStreamDryRunLocked();

    realOutSubmitter_.cancelAndRelease(reason ? reason : "session_stop");
    captureRealOutSubmitterLocked();
    outProbe_.stop(reason ? reason : "session_stop");
    const auto outStats = outProbe_.snapshot();
    state_.outProbeAttempted = outStats.attempted;
    state_.outProbeSubmitted = outStats.submitted;
    state_.outProbeActive = outStats.active;
    state_.outProbeCancelled = outStats.cancelled;
    state_.outProbeSubmitResult = outStats.submitResult;
    state_.outProbeCancelResult = outStats.cancelResult;
    state_.outProbeTransferStatus = outStats.transferStatus;
    state_.outProbeSubmittedTransfers = outStats.submittedTransferCount;
    state_.outProbeActiveTransfers = outStats.activeTransferCount;
    state_.outProbeCompleteCount = outStats.completeCount;
    state_.outProbeResubmitCount = outStats.resubmitCount;
    state_.outProbeErrorCount = outStats.errorCount;
    state_.outProbeSubmitErrorCount = outStats.submitErrorCount;
    state_.outProbeScheduledBytes = outStats.scheduledBytes;
    state_.outProbeCompletedBytes = outStats.completedBytes;
    state_.outProbeCompletedBytesPerSecond = outStats.completedBytesPerSecond;
    state_.outProbeExpectedBytesPerSecond = outStats.expectedBytesPerSecond;
    state_.outProbeElapsedMs = outStats.elapsedMs;
    state_.outProbeCompletionRatio = outStats.completionRatio;
    state_.completedBytesPerSecond = outStats.completedBytesPerSecond;
    state_.transferErrorCount = outStats.errorCount;
    state_.submitErrorCount = outStats.submitErrorCount;
    state_.outTransferSummary = outStats.attempted ? describeUac20OutProbeStats(outStats) : state_.outTransferSummary;

    feedbackTransfer_.stop(reason ? reason : "session_stop");
    const auto feedbackStats = feedbackTransfer_.snapshot();
    state_.feedbackPersistentActive = feedbackStats.active;
    state_.feedbackCancelled = feedbackStats.cancelled;
    state_.feedbackCancelResult = feedbackStats.cancelResult;
    state_.feedbackTransferStatus = feedbackStats.transferStatus;
    state_.feedbackFirstPacketBytes = feedbackStats.actualLength;
    state_.feedbackCompleteCount = feedbackStats.completeCount;
    state_.feedbackResubmitCount = feedbackStats.resubmitCount;
    state_.feedbackErrorCount = feedbackStats.errorCount;
    state_.feedbackRawValue = feedbackStats.rawValue;
    state_.feedbackFramesPerMicroframe = feedbackStats.feedbackFramesPerMicroframe;
    state_.feedbackSummary = describeUac20FeedbackRuntime(feedbackStats);

    eventLoop_.stop(reason ? reason : "session_stop");
    state_.eventThreadStarted = eventLoop_.running();
    state_.eventLoopTicks = eventLoop_.tickCount();
    state_.eventLoopOkCount = eventLoop_.okCount();
    state_.eventLoopTimeoutCount = eventLoop_.timeoutCount();
    state_.eventLoopWakeCount = eventLoop_.wakeCount();
    state_.eventLoopErrors = eventLoop_.errorCount();
    state_.eventLoopLastError = eventLoop_.lastError();
    state_.eventLoopSummary = eventLoop_.summary();
    state_.running = false;
    state_.prepared = false;
}

void Uac20Session::close(const char* reason) {
    stop(reason);
    releaseInterfacesLocked();
    {
        std::lock_guard<std::mutex> lock(libusbOpenCloseMutex());
        if (deviceHandle_ != nullptr) {
            libusb_close(deviceHandle_);
            deviceHandle_ = nullptr;
        }
        if (usbContext_ != nullptr) {
            libusb_exit(usbContext_);
            usbContext_ = nullptr;
        }
    }
    closeOwnedFd(&fd_);
    descriptorSnapshot_ = Uac20DescriptorSnapshot{};
    selectedAlt_ = Uac20AltSnapshot{};
    outTransferPlan_ = Uac20OutTransferPlan{};
    realOutSubmitter_.cancelAndRelease(reason ? reason : "session_close");
    captureRealOutSubmitterLocked();
    rawStreamDryRun_.close(reason ? reason : "session_close");
    captureRawStreamDryRunLocked();
    writeRing_.reset(reason ? reason : "session_close");
    pcmPipeline_.reset();
    state_.opened = false;
}

int Uac20Session::write(const uint8_t* data, int length) {
    // 0040-0043: debug v2 playback needs to prebuffer before start(). Keep
    // production behavior unchanged by allowing this only for an explicitly
    // gated debug-real-OUT session after prepare() has completed.
    if (!state_.running && !(params_.enableDebugRealOutSubmitter && state_.prepared)) return -1003;
    if (data == nullptr || length <= 0) return 0;

    const auto pipelineBefore = pcmPipeline_.snapshot();
    if (!pipelineBefore.configured) {
        state_.lastError = "PCM pipeline not configured";
        capturePcmPipelineStats(state_, pipelineBefore);
        return -1011;
    }

    const auto ringBefore = writeRing_.snapshot();
    const int deviceFrame = std::max(1, pipelineBefore.deviceFrameBytes > 0
            ? pipelineBefore.deviceFrameBytes
            : state_.frameBytes);
    const bool rawTakeoverSinkReady = rawStreamTakeoverSinkReadyLocked();
    int sinkCapacity = std::max(0, ringBefore.capacityBytes);
    int sinkLevel = std::max(0, ringBefore.levelBytes);
    if (rawTakeoverSinkReady) {
        const auto rawStatus = rawStreamDryRun_.status();
        sinkCapacity = std::max(0, rawStatus.writeRingCapacityBytes);
        sinkLevel = std::max(0, rawStatus.writeRingLevelBytes);
    }
    int freeBytes = sinkCapacity > sinkLevel ? (sinkCapacity - sinkLevel) : 0;
    freeBytes -= freeBytes % deviceFrame;
    if (freeBytes <= 0) {
        state_.writeRingSummary = describeUac20WriteRingStats(ringBefore);
        state_.lastError = rawTakeoverSinkReady
                ? "PCM pipeline backpressure: raw stream FIFO full"
                : "PCM pipeline backpressure: write ring full";
        return 0;
    }

    pcmPipelineBuffer_.resize(static_cast<size_t>(freeBytes));
    const RawPcmPipelineProcessResult processed = pcmPipeline_.process(
            data,
            length,
            pcmPipelineBuffer_.data(),
            static_cast<int>(pcmPipelineBuffer_.size()));
    const auto pipelineStats = pcmPipeline_.snapshot();
    capturePcmPipelineStats(state_, pipelineStats);

    if (processed.errorCode < 0) {
        state_.lastError = processed.reason;
        return processed.errorCode;
    }

    int written = 0;
    const bool useRawTakeover = rawTakeoverSinkReady;
    if (processed.producedDeviceBytes > 0) {
        if (useRawTakeover) {
            written = writeRawStreamRealOutTakeoverLocked(
                    pcmPipelineBuffer_.data(),
                    processed.producedDeviceBytes);
            if (state_.rawStreamRealOutTakeoverFallbackUsed) {
                written = writeRing_.write(pcmPipelineBuffer_.data(), processed.producedDeviceBytes);
            }
        } else {
            written = writeRing_.write(pcmPipelineBuffer_.data(), processed.producedDeviceBytes);
            shadowWriteRawStreamDryRunLocked(
                    pcmPipelineBuffer_.data(),
                    processed.producedDeviceBytes,
                    written);
        }
    }
    const auto stats = writeRing_.snapshot();
    state_.writeRingInitialized = stats.initialized;
    state_.writeRingShadowMode = stats.shadowMode;
    state_.writeRingFrameBytes = stats.frameBytes;
    state_.writeRingCapacityBytes = stats.capacityBytes;
    state_.writeRingLevelBytes = stats.levelBytes;
    state_.writeRingMaxLevelBytes = stats.maxLevelBytes;
    state_.writeRingAppInBytesPerSecond = stats.appInBytesPerSecond;
    state_.writeRingLastWriteBytes = stats.lastWriteBytes;
    state_.writeRingLastAcceptedBytes = stats.lastAcceptedBytes;
    state_.writeRingLastDroppedBytes = stats.lastDroppedBytes;
    state_.writeRingLastAlignmentRemainder = stats.lastAlignmentRemainder;
    state_.writeRingTotalInputBytes = stats.totalInputBytes;
    state_.writeRingTotalAcceptedBytes = stats.totalAcceptedBytes;
    state_.writeRingTotalDroppedBytes = stats.totalDroppedBytes;
    state_.writeRingWriteCalls = stats.totalWriteCalls;
    state_.writeRingUnalignedWriteCalls = stats.unalignedWriteCalls;
    state_.writeRingSummary = describeUac20WriteRingStats(stats);

    if (written < 0) return written;

    // 0085/0091: lazy-start real OUT only after decoded PCM has actually
    // populated the write ring with device-format PCM produced by RawPcmPipeline.
    // This keeps the first queued URBs from becoming zero-fill and also ensures
    // resampler/adapter output, not source-container PCM, feeds ISO OUT.
    if ((!rawStreamTakeoverEnabledLocked() || state_.rawStreamRealOutTakeoverFallbackUsed) &&
        params_.enableDebugRealOutSubmitter && params_.debugRealOutFeedFromWriteRing && state_.running) {
        const auto submitter = realOutSubmitter_.snapshot();
        const bool alreadyStarted = submitter.submitted || submitter.active || submitter.activeTransferCount > 0;
        const int minStartBytes = std::max(state_.frameBytes, submitter.queueBytes > 0
                ? submitter.queueBytes
                : (submitter.transferBytes > 0 ? submitter.transferBytes : state_.frameBytes * 8));
        if (!alreadyStarted && stats.levelBytes >= minStartBytes) {
            startDebugRealOutSubmitterLocked("first_pipeline_pcm_write");
        } else {
            captureRealOutSubmitterLocked();
        }
    }

    // Return source bytes consumed by the native PCM pipeline. For the swr path,
    // this may be positive even if swr buffered the input and produced zero output.
    return processed.consumedInputBytes;
}

Uac20RuntimeState Uac20Session::runtimeState() const {
    return state_;
}

std::string Uac20Session::runtimeJson() const {
    const auto liveFeedback = feedbackTransfer_.snapshot();
    const bool hasLiveFeedback = liveFeedback.attempted || state_.feedbackPersistentStarted;
    const auto liveOut = outProbe_.snapshot();
    const bool hasLiveOut = liveOut.attempted || state_.outProbeAttempted;
    const auto liveWrite = writeRing_.snapshot();
    const bool hasLiveWrite = liveWrite.initialized || state_.writeRingInitialized;
    const auto pcmStats = pcmAdapter_.snapshot();

    const bool explicitFeedbackSelected = state_.feedbackEndpoint != 0 &&
            state_.pacingMode == UacPacingMode::ExplicitFeedback;
    const std::string feedbackHealth = summarizeUac20FeedbackHealth(
            liveFeedback,
            explicitFeedbackSelected,
            state_.feedbackEndpoint);
    const std::string outHealth = summarizeUac20OutProbeHealth(liveOut);
    const std::string clockHealth = summarizeUac20ClockHealth(
            state_.clockConfigured,
            state_.clockVerified,
            state_.selectedClockSource,
            state_.deviceSampleRate,
            state_.sampleRate,
            state_.clockSetResult,
            state_.clockGetResult);
    const std::string eventHealth = summarizeUac20EventLoopHealth(
            eventLoop_.running(),
            eventLoop_.tickCount(),
            eventLoop_.errorCount(),
            eventLoop_.lastError());
    const std::string writeHealth = summarizeUac20WriteRingHealth(liveWrite);
    const std::string pcmHealth = summarizeUac20PcmAdapterHealth(pcmStats);

    // 0052-0059: Evaluate playback guard, recovery executor, and format fallback
    Uac20PlaybackGuardInput guardInput;
    guardInput.debugRealOutEnabled = params_.enableDebugRealOutSubmitter;
    guardInput.sessionStarted = state_.running;
    guardInput.decodedPcmSource = params_.debugRealOutFeedFromWriteRing && liveWrite.totalInputBytes > 0;
    // preferExplicitFeedback means "use it when the descriptor exposes one"; it
    // must not make no-feedback devices fail the runtime guard. Only require
    // feedback lock when this session actually selected an explicit feedback EP.
    guardInput.explicitFeedbackExpected = explicitFeedbackSelected;
    guardInput.sampleRate = state_.sampleRate;
    guardInput.frameBytes = state_.frameBytes;
    guardInput.writeRingInputBytes = liveWrite.totalInputBytes;
    guardInput.writeRingAcceptedBytes = liveWrite.totalAcceptedBytes;
    guardInput.writeRingDroppedBytes = liveWrite.totalDroppedBytes;
    guardInput.writeRingUnalignedCalls = liveWrite.unalignedWriteCalls;
    guardInput.feedbackCompleteCount = liveFeedback.completeCount;
    guardInput.feedbackErrorCount = liveFeedback.errorCount;
    guardInput.realOutSubmittedBytes = static_cast<int>(state_.realOutSubmitterSubmittedBytes);
    guardInput.realOutCompletedBytes = static_cast<int>(state_.realOutSubmitterCompletedBytes);
    guardInput.realOutCompletedBytesPerSecond = state_.realOutSubmitterCompletedBytesPerSecond;
    guardInput.realOutExpectedBytesPerSecond = state_.realOutSubmitterExpectedBytesPerSecond > 0
            ? state_.realOutSubmitterExpectedBytesPerSecond
            : state_.expectedBytesPerSecond;
    guardInput.realOutCompletionRatio = state_.realOutSubmitterCompletionRatio;
    guardInput.realOutSubmitErrorCount = state_.realOutSubmitterSubmitErrorCount;
    guardInput.realOutTransferErrorCount = state_.realOutSubmitterTransferErrorCount;
    guardInput.realOutFeederUnderrunCount = state_.realOutSubmitterFeederUnderrunCount;
    guardInput.realOutZeroFilledBytes = state_.realOutSubmitterZeroFilledBytes;
    guardInput.realOutPendingAfterCancel = state_.realOutSubmitterPendingAfterCancel;
    guardInput.realOutReleaseDeferred = state_.realOutSubmitterReleaseDeferred;
    const auto guardResult = evaluateUac20PlaybackGuard(guardInput);

    Uac20RecoveryExecutorInput execInput;
    execInput.executionEnabled = params_.enableDebugRecoveryExecutor;
    execInput.sessionOpened = state_.opened;
    execInput.sessionPrepared = state_.prepared;
    execInput.sessionRunning = state_.running;
    execInput.interfacesClaimed = state_.interfacesClaimed;
    execInput.explicitFeedbackSelected = explicitFeedbackSelected;
    const auto execResult = evaluateUac20RecoveryExecutor(execInput);

    Uac20FormatFallbackInput fallbackInput;
    fallbackInput.executionEnabled = params_.enableDebugFormatFallbackExecutor;
    fallbackInput.lowerFormatSuggested = state_.recoveryPolicyLowerFormat;
    fallbackInput.explicitFeedbackSelected = execInput.explicitFeedbackSelected;
    fallbackInput.currentSampleRate = state_.sampleRate;
    fallbackInput.currentBits = state_.validBits;
    fallbackInput.currentSubslotBytes = state_.subslotBytes;
    fallbackInput.channels = state_.channels;
    const auto fallbackPlan = buildUac20FormatFallbackPlan(fallbackInput);

    const std::string recoveryReport = summarizeUac20RecoveryPolicyForReport(
            state_.recoveryPolicySource,
            static_cast<Uac20RecoverySignal>(state_.recoveryPolicySignal),
            static_cast<Uac20RecoveryDecisionAction>(state_.recoveryPolicyDecision),
            state_.recoveryPolicyDisableFeedback,
            state_.recoveryPolicyKeepExplicitFeedback,
            state_.recoveryPolicyFullReopen,
            state_.recoveryPolicyResetAlt,
            state_.recoveryPolicyLowerFormat,
            state_.recoveryPolicyAndroidFallback,
            state_.recoveryPolicyTransportLost);
    const std::string runtimeHeadline = makeUac20RuntimeHeadline(
            state_.sampleRate,
            state_.validBits,
            state_.channels,
            state_.subslotBytes,
            state_.audioStreamingInterface,
            state_.altSetting,
            state_.outEndpoint,
            state_.feedbackEndpoint,
            feedbackHealth,
            outHealth,
            recoveryReport);
    const std::string nextAction = recommendUac20NextAction(
            static_cast<Uac20RecoverySignal>(state_.recoveryPolicySignal),
            static_cast<Uac20RecoveryDecisionAction>(state_.recoveryPolicyDecision),
            state_.recoveryPolicyKeepExplicitFeedback,
            liveOut.attempted || state_.outProbeAttempted,
            liveOut.completionRatio > 0.0 ? liveOut.completionRatio : state_.outProbeCompletionRatio,
            liveWrite.initialized && liveWrite.unalignedWriteCalls == 0 && liveWrite.totalDroppedBytes == 0,
            pcmStats.configured && pcmStats.mode != Uac20PcmAdapterMode::Unsupported &&
                    pcmStats.unalignedCalls == 0 && pcmStats.lastRemainderBytes == 0);

    std::ostringstream os;
    os << "{";
    os << "\"opened\":" << (state_.opened ? "true" : "false") << ',';
    os << "\"prepared\":" << (state_.prepared ? "true" : "false") << ',';
    os << "\"running\":" << (state_.running ? "true" : "false") << ',';
    os << "\"diagnosticsHeadline\":\"" << jsonEscape(runtimeHeadline) << "\",";
    os << "\"feedbackHealthSummary\":\"" << jsonEscape(feedbackHealth) << "\",";
    os << "\"outProbeHealthSummary\":\"" << jsonEscape(outHealth) << "\",";
    os << "\"clockHealthSummary\":\"" << jsonEscape(clockHealth) << "\",";
    os << "\"eventLoopHealthSummary\":\"" << jsonEscape(eventHealth) << "\",";
    os << "\"writeRingHealthSummary\":\"" << jsonEscape(writeHealth) << "\",";
    os << "\"pcmAdapterHealthSummary\":\"" << jsonEscape(pcmHealth) << "\",";
    os << "\"recoveryPolicyReport\":\"" << jsonEscape(recoveryReport) << "\",";
    os << "\"recommendedNextAction\":\"" << jsonEscape(nextAction) << "\",";
    os << "\"sampleRate\":" << state_.sampleRate << ',';
    os << "\"channels\":" << state_.channels << ',';
    os << "\"validBits\":" << state_.validBits << ',';
    os << "\"subslotBytes\":" << state_.subslotBytes << ',';
    os << "\"frameBytes\":" << state_.frameBytes << ',';
    os << "\"bytesPerSecond\":" << state_.bytesPerSecond << ',';
    os << "\"acIface\":" << state_.audioControlInterface << ',';
    os << "\"iface\":" << state_.audioStreamingInterface << ',';
    os << "\"alt\":" << state_.altSetting << ',';
    os << "\"outEndpoint\":" << state_.outEndpoint << ',';
    os << "\"feedbackEndpoint\":" << state_.feedbackEndpoint << ',';
    os << "\"pacingMode\":" << static_cast<int>(state_.pacingMode) << ',';
    os << "\"feedbackState\":" << static_cast<int>(state_.feedbackState) << ',';
    os << "\"pendingRecovery\":" << static_cast<int>(state_.pendingRecovery) << ',';
    os << "\"recoveryPolicySignal\":" << state_.recoveryPolicySignal << ',';
    os << "\"recoveryPolicyDecision\":" << state_.recoveryPolicyDecision << ',';
    os << "\"recoveryPolicyDisableFeedback\":" << (state_.recoveryPolicyDisableFeedback ? "true" : "false") << ',';
    os << "\"recoveryPolicyKeepExplicitFeedback\":" << (state_.recoveryPolicyKeepExplicitFeedback ? "true" : "false") << ',';
    os << "\"recoveryPolicyFullReopen\":" << (state_.recoveryPolicyFullReopen ? "true" : "false") << ',';
    os << "\"recoveryPolicySource\":\"" << jsonEscape(state_.recoveryPolicySource) << "\",";
    os << "\"recoveryPolicyResetAlt\":" << (state_.recoveryPolicyResetAlt ? "true" : "false") << ',';
    os << "\"recoveryPolicyLowerFormat\":" << (state_.recoveryPolicyLowerFormat ? "true" : "false") << ',';
    os << "\"recoveryPolicyAndroidFallback\":" << (state_.recoveryPolicyAndroidFallback ? "true" : "false") << ',';
    os << "\"recoveryPolicyTransportLost\":" << (state_.recoveryPolicyTransportLost ? "true" : "false") << ',';
    os << "\"recoveryAttemptReport\":\"" << jsonEscape(state_.recoveryAttemptReport) << "\",";
    os << "\"recoveryAttemptHistory\":\"" << jsonEscape(state_.recoveryAttemptHistory) << "\",";
    os << "\"recoveryAttemptInitialized\":" << (state_.recoveryAttemptInitialized ? "true" : "false") << ',';
    os << "\"recoveryAttemptHasDecision\":" << (state_.recoveryAttemptHasDecision ? "true" : "false") << ',';
    os << "\"recoveryAttemptBudgetExhausted\":" << (state_.recoveryAttemptBudgetExhausted ? "true" : "false") << ',';
    os << "\"recoveryAttemptFallbackSuggested\":" << (state_.recoveryAttemptFallbackSuggested ? "true" : "false") << ',';
    os << "\"recoveryAttemptIndex\":" << state_.recoveryAttemptIndex << ',';
    os << "\"recoveryAttemptTotal\":" << state_.recoveryAttemptTotal << ',';
    os << "\"recoveryAttemptNextAction\":" << state_.recoveryAttemptNextAction << ',';
    os << "\"recoveryAttemptNextActionName\":\"" << jsonEscape(state_.recoveryAttemptNextActionName) << "\",";
    os << "\"recoveryAttemptFullReopenCount\":" << state_.recoveryAttemptFullReopenCount << ',';
    os << "\"recoveryAttemptResetAltCount\":" << state_.recoveryAttemptResetAltCount << ',';
    os << "\"recoveryAttemptLowerFormatCount\":" << state_.recoveryAttemptLowerFormatCount << ',';
    os << "\"recoveryAttemptBudgetRemaining\":" << state_.recoveryAttemptBudgetRemaining << ',';
    os << "\"recoveryCandidatePlanReport\":\"" << jsonEscape(state_.recoveryCandidatePlanReport) << "\",";
    os << "\"recoveryCandidateList\":\"" << jsonEscape(state_.recoveryCandidateList) << "\",";
    os << "\"recoveryCandidatePlanInitialized\":" << (state_.recoveryCandidatePlanInitialized ? "true" : "false") << ',';
    os << "\"recoveryCandidatePlanHasCandidates\":" << (state_.recoveryCandidatePlanHasCandidates ? "true" : "false") << ',';
    os << "\"recoveryCandidatePlanHasSelected\":" << (state_.recoveryCandidatePlanHasSelected ? "true" : "false") << ',';
    os << "\"recoveryCandidateCount\":" << state_.recoveryCandidateCount << ',';
    os << "\"recoveryCandidateSelectedIndex\":" << state_.recoveryCandidateSelectedIndex << ',';
    os << "\"recoveryCandidateSelectedAction\":" << state_.recoveryCandidateSelectedAction << ',';
    os << "\"recoveryCandidateSelectedSampleRate\":" << state_.recoveryCandidateSelectedSampleRate << ',';
    os << "\"recoveryCandidateSelectedBits\":" << state_.recoveryCandidateSelectedBits << ',';
    os << "\"recoveryCandidateSelectedSubslotBytes\":" << state_.recoveryCandidateSelectedSubslotBytes << ',';
    os << "\"recoveryCandidateSelectedKeepExplicitFeedback\":" << (state_.recoveryCandidateSelectedKeepExplicitFeedback ? "true" : "false") << ',';
    os << "\"recoveryCandidateSelectedDisableFeedback\":" << (state_.recoveryCandidateSelectedDisableFeedback ? "true" : "false") << ',';
    os << "\"recoveryCandidateSelectedFullReopen\":" << (state_.recoveryCandidateSelectedFullReopen ? "true" : "false") << ',';
    os << "\"recoveryCandidateSelectedResetAlt\":" << (state_.recoveryCandidateSelectedResetAlt ? "true" : "false") << ',';
    os << "\"recoveryCandidateSelectedLowerFormat\":" << (state_.recoveryCandidateSelectedLowerFormat ? "true" : "false") << ',';
    os << "\"recoveryCandidateSelectedAndroidFallback\":" << (state_.recoveryCandidateSelectedAndroidFallback ? "true" : "false") << ',';
    os << "\"recoveryExecutionPlanReport\":\"" << jsonEscape(state_.recoveryExecutionPlanSummary) << "\",";
    os << "\"recoveryExecutionSteps\":\"" << jsonEscape(state_.recoveryExecutionStepsSummary) << "\",";
    os << "\"recoveryExecutionPlanInitialized\":" << (state_.recoveryExecutionPlanInitialized ? "true" : "false") << ',';
    os << "\"recoveryExecutionDryRunOnly\":" << (state_.recoveryExecutionDryRunOnly ? "true" : "false") << ',';
    os << "\"recoveryExecutionHasSelectedCandidate\":" << (state_.recoveryExecutionHasSelectedCandidate ? "true" : "false") << ',';
    os << "\"recoveryExecutionCandidateActionName\":\"" << jsonEscape(state_.recoveryExecutionCandidateActionName) << "\",";
    os << "\"recoveryExecutionTargetSampleRate\":" << state_.recoveryExecutionTargetSampleRate << ',';
    os << "\"recoveryExecutionTargetBits\":" << state_.recoveryExecutionTargetBits << ',';
    os << "\"recoveryExecutionTargetSubslotBytes\":" << state_.recoveryExecutionTargetSubslotBytes << ',';
    os << "\"recoveryExecutionStepCount\":" << state_.recoveryExecutionStepCount << ',';
    os << "\"recoveryExecutionRequiresStop\":" << (state_.recoveryExecutionRequiresStop ? "true" : "false") << ',';
    os << "\"recoveryExecutionRequiresClose\":" << (state_.recoveryExecutionRequiresClose ? "true" : "false") << ',';
    os << "\"recoveryExecutionRequiresReopen\":" << (state_.recoveryExecutionRequiresReopen ? "true" : "false") << ',';
    os << "\"recoveryExecutionRequiresClaimInterfaces\":" << (state_.recoveryExecutionRequiresClaimInterfaces ? "true" : "false") << ',';
    os << "\"recoveryExecutionRequiresAltReset\":" << (state_.recoveryExecutionRequiresAltReset ? "true" : "false") << ',';
    os << "\"recoveryExecutionRequiresClockSet\":" << (state_.recoveryExecutionRequiresClockSet ? "true" : "false") << ',';
    os << "\"recoveryExecutionRequiresPlaybackAlt\":" << (state_.recoveryExecutionRequiresPlaybackAlt ? "true" : "false") << ',';
    os << "\"recoveryExecutionRequiresFeedbackRestart\":" << (state_.recoveryExecutionRequiresFeedbackRestart ? "true" : "false") << ',';
    os << "\"recoveryExecutionRequiresOutRestart\":" << (state_.recoveryExecutionRequiresOutRestart ? "true" : "false") << ',';
    os << "\"sessionPhase\":" << state_.sessionPhase << ',';
    os << "\"sessionPhaseName\":\"" << jsonEscape(state_.sessionPhaseName) << "\",";
    os << "\"sessionPreviousPhase\":" << state_.sessionPreviousPhase << ',';
    os << "\"sessionPreviousPhaseName\":\"" << jsonEscape(state_.sessionPreviousPhaseName) << "\",";
    os << "\"sessionPhaseRank\":" << state_.sessionPhaseRank << ',';
    os << "\"sessionPhaseTransitionCount\":" << state_.sessionPhaseTransitionCount << ',';
    os << "\"sessionPhaseNonMonotonicCount\":" << state_.sessionPhaseNonMonotonicCount << ',';
    os << "\"sessionPhaseErrorCount\":" << state_.sessionPhaseErrorCount << ',';
    os << "\"sessionPhaseSummary\":\"" << jsonEscape(state_.sessionPhaseSummary) << "\",";
    os << "\"sessionPhaseHistory\":\"" << jsonEscape(state_.sessionPhaseHistory) << "\",";
    os << "\"realOutRingInitialized\":" << (state_.realOutRingInitialized ? "true" : "false") << ',';
    os << "\"realOutRingDryRunOnly\":" << (state_.realOutRingDryRunOnly ? "true" : "false") << ',';
    os << "\"realOutRingReadyForFeeder\":" << (state_.realOutRingReadyForFeeder ? "true" : "false") << ',';
    os << "\"realOutRingEndpoint\":" << state_.realOutRingEndpoint << ',';
    os << "\"realOutRingTransferCount\":" << state_.realOutRingTransferCount << ',';
    os << "\"realOutRingTransferBytes\":" << state_.realOutRingTransferBytes << ',';
    os << "\"realOutRingQueueBytes\":" << state_.realOutRingQueueBytes << ',';
    os << "\"realOutRingFrameBytes\":" << state_.realOutRingFrameBytes << ',';
    os << "\"outFeederInitialized\":" << (state_.outFeederInitialized ? "true" : "false") << ',';
    os << "\"outFeederDryRunOnly\":" << (state_.outFeederDryRunOnly ? "true" : "false") << ',';
    os << "\"outFeederReady\":" << (state_.outFeederReady ? "true" : "false") << ',';
    os << "\"outFeederUnderflowRisk\":" << (state_.outFeederUnderflowRisk ? "true" : "false") << ',';
    os << "\"outFeederRingLevelBytes\":" << state_.outFeederRingLevelBytes << ',';
    os << "\"outFeederTransferBudgetBytes\":" << state_.outFeederTransferBudgetBytes << ',';
    os << "\"outFeederWouldSubmitTransfers\":" << state_.outFeederWouldSubmitTransfers << ',';
    os << "\"outFeederScheduledBytes\":" << state_.outFeederScheduledBytes << ',';
    os << "\"outFeederScheduledFrames\":" << state_.outFeederScheduledFrames << ',';
    os << "\"outFeederAlignmentRemainder\":" << state_.outFeederAlignmentRemainder << ',';
    os << "\"packetSchedulerInitialized\":" << (state_.packetSchedulerInitialized ? "true" : "false") << ',';
    os << "\"packetSchedulerModeName\":\"" << jsonEscape(state_.packetSchedulerModeName) << "\",";
    os << "\"packetSchedulerExplicitFeedback\":" << (state_.packetSchedulerExplicitFeedback ? "true" : "false") << ',';
    os << "\"packetSchedulerFeedbackLocked\":" << (state_.packetSchedulerFeedbackLocked ? "true" : "false") << ',';
    os << "\"packetSchedulerPacketCount\":" << state_.packetSchedulerPacketCount << ',';
    os << "\"packetSchedulerNominalPacketBytes\":" << state_.packetSchedulerNominalPacketBytes << ',';
    os << "\"packetSchedulerPatternSummary\":\"" << jsonEscape(state_.packetSchedulerPatternSummary) << "\",";
    os << "\"realOutSubmitterInitialized\":" << (state_.realOutSubmitterInitialized ? "true" : "false") << ',';
    os << "\"realOutSubmitterAllocated\":" << (state_.realOutSubmitterAllocated ? "true" : "false") << ',';
    os << "\"realOutSubmitterAttempted\":" << (state_.realOutSubmitterAttempted ? "true" : "false") << ',';
    os << "\"realOutSubmitterSubmitted\":" << (state_.realOutSubmitterSubmitted ? "true" : "false") << ',';
    os << "\"realOutSubmitterActive\":" << (state_.realOutSubmitterActive ? "true" : "false") << ',';
    os << "\"realOutSubmitterSubmissionEnabled\":" << (state_.realOutSubmitterSubmissionEnabled ? "true" : "false") << ',';
    os << "\"realOutSubmitterDryRunBlocked\":" << (state_.realOutSubmitterDryRunBlocked ? "true" : "false") << ',';
    os << "\"realOutSubmitterFeedFromWriteRing\":" << (state_.realOutSubmitterFeedFromWriteRing ? "true" : "false") << ',';
    os << "\"realOutSubmitterAutoResubmit\":" << (state_.realOutSubmitterAutoResubmit ? "true" : "false") << ',';
    os << "\"realOutSubmitterBudgetExpired\":" << (state_.realOutSubmitterBudgetExpired ? "true" : "false") << ',';
    os << "\"realOutSubmitterTransferCount\":" << state_.realOutSubmitterTransferCount << ',';
    os << "\"realOutSubmitterAllocatedTransfers\":" << state_.realOutSubmitterAllocatedTransfers << ',';
    os << "\"realOutSubmitterSubmittedTransfers\":" << state_.realOutSubmitterSubmittedTransfers << ',';
    os << "\"realOutSubmitterPacketsPerTransfer\":" << state_.realOutSubmitterPacketsPerTransfer << ',';
    os << "\"realOutSubmitterTransferBytes\":" << state_.realOutSubmitterTransferBytes << ',';
    os << "\"realOutSubmitterQueueBytes\":" << state_.realOutSubmitterQueueBytes << ',';
    os << "\"realOutSubmitterCallbackCount\":" << state_.realOutSubmitterCallbackCount << ',';
    os << "\"realOutSubmitterCompleteCount\":" << state_.realOutSubmitterCompleteCount << ',';
    os << "\"realOutSubmitterResubmitCount\":" << state_.realOutSubmitterResubmitCount << ',';
    os << "\"realOutSubmitterSubmitErrorCount\":" << state_.realOutSubmitterSubmitErrorCount << ',';
    os << "\"realOutSubmitterSubmitOkCount\":" << state_.realOutSubmitterSubmitOkCount << ',';
    os << "\"realOutSubmitterSubmitFailCount\":" << state_.realOutSubmitterSubmitFailCount << ',';
    os << "\"realOutSubmitterTransferErrorCount\":" << state_.realOutSubmitterTransferErrorCount << ',';
    os << "\"realOutSubmitterFeederUnderrunCount\":" << state_.realOutSubmitterFeederUnderrunCount << ',';
    os << "\"realOutSubmitterLayoutValid\":" << (state_.realOutSubmitterLayoutValid ? "true" : "false") << ',';
    os << "\"realOutSubmitterLayoutMismatch\":" << (state_.realOutSubmitterLayoutMismatch ? "true" : "false") << ',';
    os << "\"realOutSubmitterLayoutError\":\"" << jsonEscape(state_.realOutSubmitterLayoutError) << "\",";
    os << "\"realOutSubmitterEndpointMaxPacketSize\":" << state_.realOutSubmitterEndpointMaxPacketSize << ',';
    os << "\"realOutSubmitterPacketLengthTotal\":" << state_.realOutSubmitterPacketLengthTotal << ',';
    os << "\"realOutSubmitterPacketLengthMin\":" << state_.realOutSubmitterPacketLengthMin << ',';
    os << "\"realOutSubmitterPacketLengthMax\":" << state_.realOutSubmitterPacketLengthMax << ',';
    os << "\"realOutSubmitterZeroLengthPacketCount\":" << state_.realOutSubmitterZeroLengthPacketCount << ',';
    os << "\"realOutSubmitterSubmittedBytes\":" << state_.realOutSubmitterSubmittedBytes << ',';
    os << "\"realOutSubmitterCompletedBytes\":" << state_.realOutSubmitterCompletedBytes << ',';
    os << "\"realOutSubmitterCompletedBytesPerSecond\":" << state_.realOutSubmitterCompletedBytesPerSecond << ',';
    os << "\"realOutSubmitterExpectedBytesPerSecond\":" << state_.realOutSubmitterExpectedBytesPerSecond << ',';
    os << "\"realOutSubmitterCompletionRatio\":" << state_.realOutSubmitterCompletionRatio << ',';
    os << "\"realOutSubmitterFedBytes\":" << state_.realOutSubmitterFedBytes << ',';
    os << "\"realOutSubmitterZeroFilledBytes\":" << state_.realOutSubmitterZeroFilledBytes << ',';
    os << "\"realOutSubmitterElapsedMs\":" << state_.realOutSubmitterElapsedMs << ',';
    os << "\"realOutSubmitterLastSubmitResult\":" << state_.realOutSubmitterLastSubmitResult << ',';
    os << "\"realOutSubmitterLastTransferStatus\":" << state_.realOutSubmitterLastTransferStatus << ',';
    os << "\"realOutSubmitterLastIsoPacketStatus\":" << state_.realOutSubmitterLastIsoPacketStatus << ',';
    os << "\"realOutSubmitterLastIsoActualLength\":" << state_.realOutSubmitterLastIsoActualLength << ',';
    os << "\"realOutSubmitterLastIsoPacketLength\":" << state_.realOutSubmitterLastIsoPacketLength << ',';
    os << "\"realOutSubmitterFirstSubmitMs\":" << state_.realOutSubmitterFirstSubmitMs << ',';
    os << "\"realOutSubmitterLastSubmitMs\":" << state_.realOutSubmitterLastSubmitMs << ',';
    os << "\"realOutSubmitterFirstCallbackMs\":" << state_.realOutSubmitterFirstCallbackMs << ',';
    os << "\"realOutSubmitterLastCallbackMs\":" << state_.realOutSubmitterLastCallbackMs << ',';
    os << "\"realOutSubmitterNoCompletionMs\":" << state_.realOutSubmitterNoCompletionMs << ',';
    os << "\"realOutSubmitterCleanStopRequested\":" << (state_.realOutSubmitterCleanStopRequested ? "true" : "false") << ',';
    os << "\"realOutSubmitterReleaseComplete\":" << (state_.realOutSubmitterReleaseComplete ? "true" : "false") << ',';
    os << "\"realOutSubmitterReleaseDeferred\":" << (state_.realOutSubmitterReleaseDeferred ? "true" : "false") << ',';
    os << "\"realOutSubmitterActiveTransferCount\":" << state_.realOutSubmitterActiveTransferCount << ',';
    os << "\"realOutSubmitterPendingAfterCancel\":" << state_.realOutSubmitterPendingAfterCancel << ',';
    os << "\"realOutSubmitterCancelWaitMs\":" << state_.realOutSubmitterCancelWaitMs << ',';
    os << "\"realOutSubmitterCancelCalls\":" << state_.realOutSubmitterCancelCalls << ',';
    os << "\"realOutSubmitterSummary\":\"" << jsonEscape(state_.realOutSubmitterSummary) << "\",";
    os << "\"playbackGuardInitialized\":" << (guardResult.initialized ? "true" : "false") << ',';
    os << "\"playbackGuardPassed\":" << (guardResult.passed ? "true" : "false") << ',';
    os << "\"playbackGuardBlocksPromotion\":" << (guardResult.blocksPromotion ? "true" : "false") << ',';
    os << "\"playbackGuardReason\":\"" << jsonEscape(guardResult.reason) << "\",";
    os << "\"playbackGuardSummary\":\"" << jsonEscape(guardResult.summary) << "\",";
    os << "\"playbackGuardAcceptedRatio\":" << guardResult.acceptedRatio << ',';
    os << "\"playbackGuardCompletedRatio\":" << guardResult.completedRatio << ',';
    os << "\"playbackGuardThroughputRatio\":" << guardResult.throughputRatio << ',';
    os << "\"playbackGuardZeroFillRatio\":" << guardResult.zeroFillRatio << ',';
    os << "\"recoveryExecutorInitialized\":" << (execResult.initialized ? "true" : "false") << ',';
    os << "\"recoveryExecutorEnabled\":" << (execResult.enabled ? "true" : "false") << ',';
    os << "\"recoveryExecutorAttempted\":" << (execResult.attempted ? "true" : "false") << ',';
    os << "\"recoveryExecutorExecuted\":" << (execResult.executed ? "true" : "false") << ',';
    os << "\"recoveryExecutorBlocked\":" << (execResult.blocked ? "true" : "false") << ',';
    os << "\"recoveryExecutorSummary\":\"" << jsonEscape(execResult.summary) << "\",";
    os << "\"formatFallbackPlanInitialized\":" << (fallbackPlan.initialized ? "true" : "false") << ',';
    os << "\"formatFallbackExecutionEnabled\":" << (fallbackPlan.executionEnabled ? "true" : "false") << ',';
    os << "\"formatFallbackHasCandidates\":" << (fallbackPlan.hasCandidates ? "true" : "false") << ',';
    os << "\"formatFallbackCandidateCount\":" << fallbackPlan.candidateCount << ',';
    os << "\"formatFallbackSelectedIndex\":" << fallbackPlan.selectedIndex << ',';
    os << "\"formatFallbackSummary\":\"" << jsonEscape(fallbackPlan.summary) << "\",";
    os << "\"formatFallbackCandidates\":\"" << jsonEscape(fallbackPlan.candidateSummary) << "\",";
    os << "\"recoveryPolicyInputSummary\":\"" << jsonEscape(state_.recoveryPolicyInputSummary) << "\",";
    os << "\"recoveryPolicyDecisionSummary\":\"" << jsonEscape(state_.recoveryPolicyDecisionSummary) << "\",";
    os << "\"expectedBytesPerSecond\":" << state_.expectedBytesPerSecond << ',';
    os << "\"interfacesClaimed\":" << (state_.interfacesClaimed ? "true" : "false") << ',';
    os << "\"altReset\":" << (state_.altReset ? "true" : "false") << ',';
    os << "\"playbackAltSet\":" << (state_.playbackAltSet ? "true" : "false") << ',';
    os << "\"clockConfigured\":" << (state_.clockConfigured ? "true" : "false") << ',';
    os << "\"clockVerified\":" << (state_.clockVerified ? "true" : "false") << ',';
    os << "\"selectedClockSource\":" << state_.selectedClockSource << ',';
    os << "\"deviceSampleRate\":" << state_.deviceSampleRate << ',';
    os << "\"clockSetResult\":" << state_.clockSetResult << ',';
    os << "\"clockGetResult\":" << state_.clockGetResult << ',';
    os << "\"feedbackProbeAttempted\":" << (state_.feedbackProbeAttempted ? "true" : "false") << ',';
    os << "\"feedbackProbeSubmitted\":" << ((state_.feedbackProbeSubmitted || liveFeedback.submitted) ? "true" : "false") << ',';
    os << "\"feedbackProbeCompleted\":" << ((state_.feedbackProbeCompleted || liveFeedback.completeCount > 0) ? "true" : "false") << ',';
    os << "\"feedbackProbeTimedOut\":" << (state_.feedbackProbeTimedOut ? "true" : "false") << ',';
    os << "\"feedbackPersistentStarted\":" << ((state_.feedbackPersistentStarted || liveFeedback.submitted) ? "true" : "false") << ',';
    os << "\"feedbackPersistentActive\":" << (liveFeedback.active ? "true" : "false") << ',';
    os << "\"feedbackCancelled\":" << ((state_.feedbackCancelled || liveFeedback.cancelled) ? "true" : "false") << ',';
    os << "\"feedbackSubmitResult\":" << (hasLiveFeedback ? liveFeedback.submitResult : state_.feedbackSubmitResult) << ',';
    os << "\"feedbackCancelResult\":" << (hasLiveFeedback ? liveFeedback.cancelResult : state_.feedbackCancelResult) << ',';
    os << "\"feedbackTransferStatus\":" << (hasLiveFeedback ? liveFeedback.transferStatus : state_.feedbackTransferStatus) << ',';
    os << "\"feedbackFirstPacketBytes\":" << (hasLiveFeedback ? liveFeedback.actualLength : state_.feedbackFirstPacketBytes) << ',';
    os << "\"feedbackCompleteCount\":" << (hasLiveFeedback ? liveFeedback.completeCount : state_.feedbackCompleteCount) << ',';
    os << "\"feedbackResubmitCount\":" << (hasLiveFeedback ? liveFeedback.resubmitCount : state_.feedbackResubmitCount) << ',';
    os << "\"feedbackErrorCount\":" << (hasLiveFeedback ? liveFeedback.errorCount : state_.feedbackErrorCount) << ',';
    os << "\"feedbackRawValue\":" << (hasLiveFeedback ? liveFeedback.rawValue : state_.feedbackRawValue) << ',';
    os << "\"feedbackFramesPerMicroframe\":" << (hasLiveFeedback ? liveFeedback.feedbackFramesPerMicroframe : state_.feedbackFramesPerMicroframe) << ',';
    os << "\"eventThreadStarted\":" << (eventLoop_.running() ? "true" : "false") << ',';
    os << "\"eventLoopTicks\":" << eventLoop_.tickCount() << ',';
    os << "\"eventLoopOkCount\":" << eventLoop_.okCount() << ',';
    os << "\"eventLoopTimeoutCount\":" << eventLoop_.timeoutCount() << ',';
    os << "\"eventLoopWakeCount\":" << eventLoop_.wakeCount() << ',';
    os << "\"eventLoopErrors\":" << eventLoop_.errorCount() << ',';
    os << "\"eventLoopLastError\":" << eventLoop_.lastError() << ',';
    os << "\"outTransferPlanPrepared\":" << (state_.outTransferPlanPrepared ? "true" : "false") << ',';
    os << "\"outTransferCount\":" << state_.outTransferCount << ',';
    os << "\"outPacketsPerTransfer\":" << state_.outPacketsPerTransfer << ',';
    os << "\"outPacketBytes\":" << state_.outPacketBytes << ',';
    os << "\"outMinPacketBytes\":" << state_.outMinPacketBytes << ',';
    os << "\"outMaxPacketBytesInPattern\":" << state_.outMaxPacketBytesInPattern << ',';
    os << "\"outPackageAdjustMode\":" << state_.outPackageAdjustMode << ',';
    os << "\"outTargetFramesPerPacket\":" << state_.outTargetFramesPerPacket << ',';
    os << "\"outFeedbackFramesPerMicroframe\":" << state_.outFeedbackFramesPerMicroframe << ',';
    os << "\"outPacketPatternSummary\":\"" << jsonEscape(state_.outPacketPatternSummary) << "\",";
    os << "\"outTransferBytes\":" << state_.outTransferBytes << ',';
    os << "\"outQueueBytes\":" << state_.outQueueBytes << ',';
    os << "\"outEndpointMaxPacketSize\":" << state_.outEndpointMaxPacketSize << ',';
    os << "\"outEndpointInterval\":" << state_.outEndpointInterval << ',';
    os << "\"outIntervalsPerSecond\":" << state_.outIntervalsPerSecond << ',';
    os << "\"outProbeAttempted\":" << ((state_.outProbeAttempted || liveOut.attempted) ? "true" : "false") << ',';
    os << "\"outProbeSubmitted\":" << ((state_.outProbeSubmitted || liveOut.submitted) ? "true" : "false") << ',';
    os << "\"outProbeActive\":" << (liveOut.active ? "true" : "false") << ',';
    os << "\"outProbeCancelled\":" << ((state_.outProbeCancelled || liveOut.cancelled) ? "true" : "false") << ',';
    os << "\"outProbeSubmitResult\":" << (hasLiveOut ? liveOut.submitResult : state_.outProbeSubmitResult) << ',';
    os << "\"outProbeCancelResult\":" << (hasLiveOut ? liveOut.cancelResult : state_.outProbeCancelResult) << ',';
    os << "\"outProbeTransferStatus\":" << (hasLiveOut ? liveOut.transferStatus : state_.outProbeTransferStatus) << ',';
    os << "\"outProbeSubmittedTransfers\":" << (hasLiveOut ? liveOut.submittedTransferCount : state_.outProbeSubmittedTransfers) << ',';
    os << "\"outProbeActiveTransfers\":" << (hasLiveOut ? liveOut.activeTransferCount : state_.outProbeActiveTransfers) << ',';
    os << "\"outProbeCompleteCount\":" << (hasLiveOut ? liveOut.completeCount : state_.outProbeCompleteCount) << ',';
    os << "\"outProbeResubmitCount\":" << (hasLiveOut ? liveOut.resubmitCount : state_.outProbeResubmitCount) << ',';
    os << "\"outProbeErrorCount\":" << (hasLiveOut ? liveOut.errorCount : state_.outProbeErrorCount) << ',';
    os << "\"outProbeSubmitErrorCount\":" << (hasLiveOut ? liveOut.submitErrorCount : state_.outProbeSubmitErrorCount) << ',';
    os << "\"outProbeScheduledBytes\":" << (hasLiveOut ? liveOut.scheduledBytes : state_.outProbeScheduledBytes) << ',';
    os << "\"outProbeCompletedBytes\":" << (hasLiveOut ? liveOut.completedBytes : state_.outProbeCompletedBytes) << ',';
    os << "\"outProbeCompletedBytesPerSecond\":" << (hasLiveOut ? liveOut.completedBytesPerSecond : state_.outProbeCompletedBytesPerSecond) << ',';
    os << "\"outProbeExpectedBytesPerSecond\":" << (hasLiveOut ? liveOut.expectedBytesPerSecond : state_.outProbeExpectedBytesPerSecond) << ',';
    os << "\"outProbeElapsedMs\":" << (hasLiveOut ? liveOut.elapsedMs : state_.outProbeElapsedMs) << ',';
    os << "\"outProbeCompletionRatio\":" << (hasLiveOut ? liveOut.completionRatio : state_.outProbeCompletionRatio) << ',';
    os << "\"writeRingInitialized\":" << ((hasLiveWrite && liveWrite.initialized) ? "true" : "false") << ',';
    os << "\"writeRingShadowMode\":" << ((hasLiveWrite ? liveWrite.shadowMode : state_.writeRingShadowMode) ? "true" : "false") << ',';
    os << "\"writeRingFrameBytes\":" << (hasLiveWrite ? liveWrite.frameBytes : state_.writeRingFrameBytes) << ',';
    os << "\"writeRingCapacityBytes\":" << (hasLiveWrite ? liveWrite.capacityBytes : state_.writeRingCapacityBytes) << ',';
    os << "\"writeRingLevelBytes\":" << (hasLiveWrite ? liveWrite.levelBytes : state_.writeRingLevelBytes) << ',';
    os << "\"writeRingMaxLevelBytes\":" << (hasLiveWrite ? liveWrite.maxLevelBytes : state_.writeRingMaxLevelBytes) << ',';
    os << "\"writeRingAppInBytesPerSecond\":" << (hasLiveWrite ? liveWrite.appInBytesPerSecond : state_.writeRingAppInBytesPerSecond) << ',';
    os << "\"writeRingLastWriteBytes\":" << (hasLiveWrite ? liveWrite.lastWriteBytes : state_.writeRingLastWriteBytes) << ',';
    os << "\"writeRingLastAcceptedBytes\":" << (hasLiveWrite ? liveWrite.lastAcceptedBytes : state_.writeRingLastAcceptedBytes) << ',';
    os << "\"writeRingLastDroppedBytes\":" << (hasLiveWrite ? liveWrite.lastDroppedBytes : state_.writeRingLastDroppedBytes) << ',';
    os << "\"writeRingLastAlignmentRemainder\":" << (hasLiveWrite ? liveWrite.lastAlignmentRemainder : state_.writeRingLastAlignmentRemainder) << ',';
    os << "\"writeRingTotalInputBytes\":" << (hasLiveWrite ? liveWrite.totalInputBytes : state_.writeRingTotalInputBytes) << ',';
    os << "\"writeRingTotalAcceptedBytes\":" << (hasLiveWrite ? liveWrite.totalAcceptedBytes : state_.writeRingTotalAcceptedBytes) << ',';
    os << "\"writeRingTotalDroppedBytes\":" << (hasLiveWrite ? liveWrite.totalDroppedBytes : state_.writeRingTotalDroppedBytes) << ',';
    os << "\"writeRingWriteCalls\":" << (hasLiveWrite ? liveWrite.totalWriteCalls : state_.writeRingWriteCalls) << ',';
    os << "\"writeRingUnalignedWriteCalls\":" << (hasLiveWrite ? liveWrite.unalignedWriteCalls : state_.writeRingUnalignedWriteCalls) << ',';
    os << "\"writeRingSummary\":\"" << jsonEscape(hasLiveWrite ? describeUac20WriteRingStats(liveWrite) : state_.writeRingSummary) << "\",";
    os << "\"pcmAdapterConfigured\":" << (state_.pcmAdapterConfigured ? "true" : "false") << ',';
    os << "\"pcmAdapterMode\":" << state_.pcmAdapterMode << ',';
    os << "\"pcmAdapterSourceFrameBytes\":" << state_.pcmAdapterSourceFrameBytes << ',';
    os << "\"pcmAdapterDeviceFrameBytes\":" << state_.pcmAdapterDeviceFrameBytes << ',';
    os << "\"pcmAdapterLastInputBytes\":" << state_.pcmAdapterLastInputBytes << ',';
    os << "\"pcmAdapterLastOutputBytes\":" << state_.pcmAdapterLastOutputBytes << ',';
    os << "\"pcmAdapterLastRemainderBytes\":" << state_.pcmAdapterLastRemainderBytes << ',';
    os << "\"pcmAdapterTotalInputBytes\":" << state_.pcmAdapterTotalInputBytes << ',';
    os << "\"pcmAdapterTotalOutputBytes\":" << state_.pcmAdapterTotalOutputBytes << ',';
    os << "\"pcmAdapterConvertCalls\":" << state_.pcmAdapterConvertCalls << ',';
    os << "\"pcmAdapterUnalignedCalls\":" << state_.pcmAdapterUnalignedCalls << ',';
    os << "\"pcmAdapterSummary\":\"" << jsonEscape(describeUac20PcmAdapterStats(pcmStats)) << "\",";
    os << "\"pcmPipelineConfigured\":" << (state_.pcmPipelineConfigured ? "true" : "false") << ',';
    os << "\"pcmPipelineResamplerRequired\":" << (state_.pcmPipelineResamplerRequired ? "true" : "false") << ',';
    os << "\"pcmPipelineResamplerReady\":" << (state_.pcmPipelineResamplerReady ? "true" : "false") << ',';
    os << "\"pcmPipelineSourceFrameBytes\":" << state_.pcmPipelineSourceFrameBytes << ',';
    os << "\"pcmPipelineSWRFrameBytes\":" << state_.pcmPipelineSWRFrameBytes << ',';
    os << "\"pcmPipelineDeviceFrameBytes\":" << state_.pcmPipelineDeviceFrameBytes << ',';
    os << "\"pcmPipelineTotalInputBytes\":" << state_.pcmPipelineTotalInputBytes << ',';
    os << "\"pcmPipelineTotalConsumedBytes\":" << state_.pcmPipelineTotalConsumedBytes << ',';
    os << "\"pcmPipelineTotalProducedBytes\":" << state_.pcmPipelineTotalProducedBytes << ',';
    os << "\"pcmPipelineProcessCalls\":" << state_.pcmPipelineProcessCalls << ',';
    os << "\"pcmPipelineUnalignedCalls\":" << state_.pcmPipelineUnalignedCalls << ',';
    os << "\"pcmPipelineZeroOutputCalls\":" << state_.pcmPipelineZeroOutputCalls << ',';
    os << "\"pcmPipelineLastInputBytes\":" << state_.pcmPipelineLastInputBytes << ',';
    os << "\"pcmPipelineLastConsumedBytes\":" << state_.pcmPipelineLastConsumedBytes << ',';
    os << "\"pcmPipelineLastOutputBytes\":" << state_.pcmPipelineLastOutputBytes << ',';
    os << "\"pcmPipelineLastRemainderBytes\":" << state_.pcmPipelineLastRemainderBytes << ',';
    os << "\"pcmPipelineLastErrorCode\":" << state_.pcmPipelineLastErrorCode << ',';
    os << "\"pcmPipelineSummary\":\"" << jsonEscape(state_.pcmPipelineSummary) << "\",";
    os << "\"rawStreamConfigBuilt\":" << (state_.rawStreamConfigBuilt ? "true" : "false") << ',';
    os << "\"rawStreamConfigDryRunOnly\":" << (state_.rawStreamConfigDryRunOnly ? "true" : "false") << ',';
    os << "\"rawStreamAllowOutSubmit\":" << (state_.rawStreamAllowOutSubmit ? "true" : "false") << ',';
    os << "\"rawStreamExplicitFeedback\":" << (state_.rawStreamExplicitFeedback ? "true" : "false") << ',';
    os << "\"rawStreamDynamicPacketSizing\":" << (state_.rawStreamDynamicPacketSizing ? "true" : "false") << ',';
    os << "\"rawStreamSampleRate\":" << state_.rawStreamSampleRate << ',';
    os << "\"rawStreamChannels\":" << state_.rawStreamChannels << ',';
    os << "\"rawStreamValidBits\":" << state_.rawStreamValidBits << ',';
    os << "\"rawStreamSubslotBytes\":" << state_.rawStreamSubslotBytes << ',';
    os << "\"rawStreamFrameBytes\":" << state_.rawStreamFrameBytes << ',';
    os << "\"rawStreamBytesPerSecond\":" << state_.rawStreamBytesPerSecond << ',';
    os << "\"rawStreamOutEndpoint\":" << state_.rawStreamOutEndpoint << ',';
    os << "\"rawStreamFeedbackEndpoint\":" << state_.rawStreamFeedbackEndpoint << ',';
    os << "\"rawStreamEndpointMaxPacketSize\":" << state_.rawStreamEndpointMaxPacketSize << ',';
    os << "\"rawStreamPacketsPerTransfer\":" << state_.rawStreamPacketsPerTransfer << ',';
    os << "\"rawStreamTransferCount\":" << state_.rawStreamTransferCount << ',';
    os << "\"rawStreamTransferBytes\":" << state_.rawStreamTransferBytes << ',';
    os << "\"rawStreamQueueBytes\":" << state_.rawStreamQueueBytes << ',';
    os << "\"rawStreamStartupPrebufferBytes\":" << state_.rawStreamStartupPrebufferBytes << ',';
    os << "\"rawStreamIntervalsPerSecond\":" << state_.rawStreamIntervalsPerSecond << ',';
    os << "\"rawStreamBuildReason\":\"" << jsonEscape(state_.rawStreamBuildReason) << "\",";
    os << "\"rawStreamBuildSummary\":\"" << jsonEscape(state_.rawStreamBuildSummary) << "\",";
    os << "\"rawStreamConfigSummary\":\"" << jsonEscape(state_.rawStreamConfigSummary) << "\",";
    os << "\"rawStreamDryRunConfigured\":" << (state_.rawStreamDryRunConfigured ? "true" : "false") << ',';
    os << "\"rawStreamDryRunAttached\":" << (state_.rawStreamDryRunAttached ? "true" : "false") << ',';
    os << "\"rawStreamDryRunPrepared\":" << (state_.rawStreamDryRunPrepared ? "true" : "false") << ',';
    os << "\"rawStreamDryRunStreaming\":" << (state_.rawStreamDryRunStreaming ? "true" : "false") << ',';
    os << "\"rawStreamDryRunReadyForOutStart\":" << (state_.rawStreamDryRunReadyForOutStart ? "true" : "false") << ',';
    os << "\"rawStreamDryRunOutSubmitted\":" << (state_.rawStreamDryRunOutSubmitted ? "true" : "false") << ',';
    os << "\"rawStreamDryRunWriteRingCapacityBytes\":" << state_.rawStreamDryRunWriteRingCapacityBytes << ',';
    os << "\"rawStreamDryRunWriteRingLevelBytes\":" << state_.rawStreamDryRunWriteRingLevelBytes << ',';
    os << "\"rawStreamDryRunRequiredPrebufferBytes\":" << state_.rawStreamDryRunRequiredPrebufferBytes << ',';
    os << "\"rawStreamDryRunOutPrepared\":" << state_.rawStreamDryRunOutPrepared << ',';
    os << "\"rawStreamDryRunStateName\":\"" << jsonEscape(state_.rawStreamDryRunStateName) << "\",";
    os << "\"rawStreamDryRunLastError\":\"" << jsonEscape(state_.rawStreamDryRunLastError) << "\",";
    os << "\"rawStreamDryRunSummary\":\"" << jsonEscape(state_.rawStreamDryRunSummary) << "\",";
    os << "\"rawStreamDryRunRuntimeJson\":\"" << jsonEscape(state_.rawStreamDryRunRuntimeJson) << "\",";
    os << "\"rawStreamShadowWriteEnabled\":" << (state_.rawStreamShadowWriteEnabled ? "true" : "false") << ',';
    os << "\"rawStreamShadowWriteAttempted\":" << (state_.rawStreamShadowWriteAttempted ? "true" : "false") << ',';
    os << "\"rawStreamShadowWriteReady\":" << (state_.rawStreamShadowWriteReady ? "true" : "false") << ',';
    os << "\"rawStreamShadowWriteBackpressure\":" << (state_.rawStreamShadowWriteBackpressure ? "true" : "false") << ',';
    os << "\"rawStreamShadowWriteMismatch\":" << (state_.rawStreamShadowWriteMismatch ? "true" : "false") << ',';
    os << "\"rawStreamShadowWriteLastInputBytes\":" << state_.rawStreamShadowWriteLastInputBytes << ',';
    os << "\"rawStreamShadowWriteLastAcceptedBytes\":" << state_.rawStreamShadowWriteLastAcceptedBytes << ',';
    os << "\"rawStreamShadowWriteLastLegacyAcceptedBytes\":" << state_.rawStreamShadowWriteLastLegacyAcceptedBytes << ',';
    os << "\"rawStreamShadowWriteLastDroppedBytes\":" << state_.rawStreamShadowWriteLastDroppedBytes << ',';
    os << "\"rawStreamShadowWriteLastMismatchBytes\":" << state_.rawStreamShadowWriteLastMismatchBytes << ',';
    os << "\"rawStreamShadowWriteCalls\":" << state_.rawStreamShadowWriteCalls << ',';
    os << "\"rawStreamShadowWriteInputBytes\":" << state_.rawStreamShadowWriteInputBytes << ',';
    os << "\"rawStreamShadowWriteAcceptedBytes\":" << state_.rawStreamShadowWriteAcceptedBytes << ',';
    os << "\"rawStreamShadowWriteDroppedBytes\":" << state_.rawStreamShadowWriteDroppedBytes << ',';
    os << "\"rawStreamShadowWriteSummary\":\"" << jsonEscape(state_.rawStreamShadowWriteSummary) << "\",";
    os << "\"rawStreamRealOutTakeoverEnabled\":" << (state_.rawStreamRealOutTakeoverEnabled ? "true" : "false") << ',';
    os << "\"rawStreamRealOutTakeoverAttempted\":" << (state_.rawStreamRealOutTakeoverAttempted ? "true" : "false") << ',';
    os << "\"rawStreamRealOutTakeoverPrepared\":" << (state_.rawStreamRealOutTakeoverPrepared ? "true" : "false") << ',';
    os << "\"rawStreamRealOutTakeoverStartAttempted\":" << (state_.rawStreamRealOutTakeoverStartAttempted ? "true" : "false") << ',';
    os << "\"rawStreamRealOutTakeoverStarted\":" << (state_.rawStreamRealOutTakeoverStarted ? "true" : "false") << ',';
    os << "\"rawStreamRealOutTakeoverActive\":" << (state_.rawStreamRealOutTakeoverActive ? "true" : "false") << ',';
    os << "\"rawStreamRealOutTakeoverFallbackUsed\":" << (state_.rawStreamRealOutTakeoverFallbackUsed ? "true" : "false") << ',';
    os << "\"rawStreamRealOutTakeoverLegacySuppressed\":" << (state_.rawStreamRealOutTakeoverLegacySuppressed ? "true" : "false") << ',';
    os << "\"rawStreamRealOutTakeoverLastWriteBytes\":" << state_.rawStreamRealOutTakeoverLastWriteBytes << ',';
    os << "\"rawStreamRealOutTakeoverLastAcceptedBytes\":" << state_.rawStreamRealOutTakeoverLastAcceptedBytes << ',';
    os << "\"rawStreamRealOutTakeoverLastDroppedBytes\":" << state_.rawStreamRealOutTakeoverLastDroppedBytes << ',';
    os << "\"rawStreamRealOutTakeoverWriteCalls\":" << state_.rawStreamRealOutTakeoverWriteCalls << ',';
    os << "\"rawStreamRealOutTakeoverInputBytes\":" << state_.rawStreamRealOutTakeoverInputBytes << ',';
    os << "\"rawStreamRealOutTakeoverAcceptedBytes\":" << state_.rawStreamRealOutTakeoverAcceptedBytes << ',';
    os << "\"rawStreamRealOutTakeoverDroppedBytes\":" << state_.rawStreamRealOutTakeoverDroppedBytes << ',';
    os << "\"rawStreamRealOutTakeoverReason\":\"" << jsonEscape(state_.rawStreamRealOutTakeoverReason) << "\",";
    os << "\"rawStreamRealOutTakeoverFallbackReason\":\"" << jsonEscape(state_.rawStreamRealOutTakeoverFallbackReason) << "\",";
    os << "\"rawStreamRealOutTakeoverSummary\":\"" << jsonEscape(state_.rawStreamRealOutTakeoverSummary) << "\",";
    os << "\"rawStreamDryRunSyntheticConsumeEnabled\":" << (state_.rawStreamDryRunSyntheticConsumeEnabled ? "true" : "false") << ',';
    os << "\"rawStreamDryRunSyntheticConsumeActive\":" << (state_.rawStreamDryRunSyntheticConsumeActive ? "true" : "false") << ',';
    os << "\"rawStreamDryRunSyntheticConsumeCalls\":" << state_.rawStreamDryRunSyntheticConsumeCalls << ',';
    os << "\"rawStreamDryRunSyntheticConsumeTargetBytes\":" << state_.rawStreamDryRunSyntheticConsumeTargetBytes << ',';
    os << "\"rawStreamDryRunSyntheticConsumeBytes\":" << state_.rawStreamDryRunSyntheticConsumeBytes << ',';
    os << "\"rawStreamDryRunSyntheticConsumeUnderrunCalls\":" << state_.rawStreamDryRunSyntheticConsumeUnderrunCalls << ',';
    os << "\"rawStreamDryRunSyntheticConsumeLastTargetBytes\":" << state_.rawStreamDryRunSyntheticConsumeLastTargetBytes << ',';
    os << "\"rawStreamDryRunSyntheticConsumeLastBytes\":" << state_.rawStreamDryRunSyntheticConsumeLastBytes << ',';
    os << "\"rawStreamDryRunSyntheticConsumeLastMissingBytes\":" << state_.rawStreamDryRunSyntheticConsumeLastMissingBytes << ',';
    os << "\"rawStreamDryRunSyntheticConsumeBytesPerSecond\":" << state_.rawStreamDryRunSyntheticConsumeBytesPerSecond << ',';
    os << "\"rawStreamDryRunSyntheticConsumeSummary\":\"" << jsonEscape(state_.rawStreamDryRunSyntheticConsumeSummary) << "\",";
    os << "\"rawStreamDryRunShadowPacerEnabled\":" << (state_.rawStreamDryRunShadowPacerEnabled ? "true" : "false") << ',';
    os << "\"rawStreamDryRunShadowPacerActive\":" << (state_.rawStreamDryRunShadowPacerActive ? "true" : "false") << ',';
    os << "\"rawStreamDryRunShadowPacerCalls\":" << state_.rawStreamDryRunShadowPacerCalls << ',';
    os << "\"rawStreamDryRunShadowPacerIntervals\":" << state_.rawStreamDryRunShadowPacerIntervals << ',';
    os << "\"rawStreamDryRunShadowPacerTransfers\":" << state_.rawStreamDryRunShadowPacerTransfers << ',';
    os << "\"rawStreamDryRunShadowPacerTargetBytes\":" << state_.rawStreamDryRunShadowPacerTargetBytes << ',';
    os << "\"rawStreamDryRunShadowPacerConsumedBytes\":" << state_.rawStreamDryRunShadowPacerConsumedBytes << ',';
    os << "\"rawStreamDryRunShadowPacerUnderrunPackets\":" << state_.rawStreamDryRunShadowPacerUnderrunPackets << ',';
    os << "\"rawStreamDryRunShadowPacerLastIntervalTargetBytes\":" << state_.rawStreamDryRunShadowPacerLastIntervalTargetBytes << ',';
    os << "\"rawStreamDryRunShadowPacerLastIntervalConsumedBytes\":" << state_.rawStreamDryRunShadowPacerLastIntervalConsumedBytes << ',';
    os << "\"rawStreamDryRunShadowPacerLastIntervalMissingBytes\":" << state_.rawStreamDryRunShadowPacerLastIntervalMissingBytes << ',';
    os << "\"rawStreamDryRunShadowPacerLastTransferTargetBytes\":" << state_.rawStreamDryRunShadowPacerLastTransferTargetBytes << ',';
    os << "\"rawStreamDryRunShadowPacerLastTransferConsumedBytes\":" << state_.rawStreamDryRunShadowPacerLastTransferConsumedBytes << ',';
    os << "\"rawStreamDryRunShadowPacerLastTransferMissingBytes\":" << state_.rawStreamDryRunShadowPacerLastTransferMissingBytes << ',';
    os << "\"rawStreamDryRunShadowPacerPacketLengthMin\":" << state_.rawStreamDryRunShadowPacerPacketLengthMin << ',';
    os << "\"rawStreamDryRunShadowPacerPacketLengthMax\":" << state_.rawStreamDryRunShadowPacerPacketLengthMax << ',';
    os << "\"rawStreamDryRunShadowPacerPacketLengthTotal\":" << state_.rawStreamDryRunShadowPacerPacketLengthTotal << ',';
    os << "\"rawStreamDryRunShadowPacerBytesPerSecond\":" << state_.rawStreamDryRunShadowPacerBytesPerSecond << ',';
    os << "\"rawStreamDryRunShadowPacerPacketPatternSummary\":\"" << jsonEscape(state_.rawStreamDryRunShadowPacerPacketPatternSummary) << "\",";
    os << "\"rawStreamDryRunShadowPacerSummary\":\"" << jsonEscape(state_.rawStreamDryRunShadowPacerSummary) << "\",";
    os << "\"descriptorSummary\":\"" << jsonEscape(state_.descriptorSummary) << "\",";
    os << "\"formatMatchSummary\":\"" << jsonEscape(state_.formatMatchSummary) << "\",";
    os << "\"policySummary\":\"" << jsonEscape(state_.policySummary) << "\",";
    os << "\"feedbackSummary\":\"" << jsonEscape(hasLiveFeedback ? describeUac20FeedbackRuntime(liveFeedback) : state_.feedbackSummary) << "\",";
    os << "\"eventLoopSummary\":\"" << jsonEscape(eventLoop_.summary()) << "\",";
    os << "\"outTransferSummary\":\"" << jsonEscape(hasLiveOut ? describeUac20OutProbeStats(liveOut) : state_.outTransferSummary) << "\",";
    os << "\"lastError\":\"" << jsonEscape(state_.lastError) << "\"";
    os << "}";
    return os.str();
}

bool Uac20Session::claimInterfacesLocked() {
    if (state_.audioControlInterface >= 0) {
        if (!claimUac20Interface(deviceHandle_, state_.audioControlInterface, "AC", &state_.lastError)) {
            return false;
        }
    }
    if (state_.audioStreamingInterface >= 0) {
        if (!claimUac20Interface(deviceHandle_, state_.audioStreamingInterface, "AS", &state_.lastError)) {
            releaseInterfacesLocked();
            return false;
        }
    }
    state_.interfacesClaimed = true;
    return true;
}

bool Uac20Session::resetAltLocked() {
    if (!state_.interfacesClaimed) {
        setError("resetAlt called before claim");
        return false;
    }
    Uac20ClockPrepareResult clockResult;
    const bool ok = resetUac20StreamingAlt0(deviceHandle_, state_.audioStreamingInterface, &clockResult);
    state_.altReset = clockResult.resetAlt0;
    if (!ok) setError(clockResult.lastError.c_str());
    return ok;
}

bool Uac20Session::configureClockLocked(const Uac20Params&) {
    Uac20ClockPrepareResult clockResult;
    const bool ok = prepareUac20ClockBestEffort(
            deviceHandle_, descriptorSnapshot_, selectedAlt_, state_.sampleRate, &clockResult);

    state_.selectedClockSource = clockResult.selectedClockSource;
    state_.clockSetResult = clockResult.setSampleRateResult;
    state_.clockGetResult = clockResult.getSampleRateResult;
    state_.deviceSampleRate = clockResult.verifiedSampleRate;
    state_.clockConfigured = clockResult.sampleRateSet;
    state_.clockVerified = clockResult.sampleRateVerified;

    if (!ok) {
        LOGW("clock prepare best-effort failed: %s", clockResult.lastError.c_str());
        // Clock failure is not fatal for the v2 skeleton: we still proceed to
        // set playback alt so runtimeJson can report how far we got.
    }
    return true;
}

bool Uac20Session::setPlaybackAltLocked() {
    if (!state_.interfacesClaimed) {
        setError("setPlaybackAlt called before claim");
        return false;
    }
    Uac20ClockPrepareResult clockResult;
    const bool ok = setUac20PlaybackAlt(deviceHandle_, state_.audioStreamingInterface, state_.altSetting, &clockResult);
    state_.playbackAltSet = clockResult.playbackAltSet;
    if (!ok) setError(clockResult.lastError.c_str());
    return ok;
}

void Uac20Session::releaseInterfacesLocked() {
    if (deviceHandle_ == nullptr) return;
    if (state_.audioStreamingInterface >= 0) {
        releaseUac20Interface(deviceHandle_, state_.audioStreamingInterface, "AS");
    }
    if (state_.audioControlInterface >= 0) {
        releaseUac20Interface(deviceHandle_, state_.audioControlInterface, "AC");
    }
    state_.interfacesClaimed = false;
}

bool Uac20Session::parseDescriptors() {
    if (!parseUac20DescriptorSnapshot(deviceHandle_, &descriptorSnapshot_)) {
        setError(descriptorSnapshot_.lastError.c_str());
        LOGE("parseDescriptors failed: %s", descriptorSnapshot_.lastError.c_str());
        return false;
    }

    state_.audioControlInterface = descriptorSnapshot_.audioControlInterface;
    state_.descriptorSummary = describeUac20DescriptorSnapshot(descriptorSnapshot_);
    LOGI("descriptor snapshot: %s", state_.descriptorSummary.c_str());
    return true;
}

bool Uac20Session::selectStreamLocked(const Uac20Params& params) {
    RawUac20FormatMatchRequest request{};
    request.sourceSampleRate = params.sourceSampleRate;
    request.sourceChannels = params.sourceChannels;
    request.sourceBits = params.sourceBits;
    // Mirror the original engine: >16-bit FFmpeg PCM often arrives as S32LE.
    request.sourceBytesPerSample = params.sourceBits > 16 ? 4 : std::max(1, (params.sourceBits + 7) / 8);
    request.targetSampleRate = params.requestedSampleRate > 0 ? params.requestedSampleRate : params.sourceSampleRate;
    request.targetChannels = params.sourceChannels;
    request.targetBits = params.requestedBits > 0 ? params.requestedBits : params.sourceBits;
    request.targetSubslotBytes = params.requestedSubslotBytes > 0
            ? params.requestedSubslotBytes
            : uac20DefaultSubslotBytes(request.targetBits, params.prefer24In32);
    request.transport = RawAudioTransportKind::Pcm;
    request.bitPerfect = true;
    request.prefer24In32 = params.prefer24In32;
    request.preferExplicitFeedback = params.preferExplicitFeedback;

    formatMatch_ = matchRawUac20Format(descriptorSnapshot_, request);
    state_.formatMatchSummary = formatMatch_.summary;
    if (!formatMatch_.matched) {
        setError("no matching UAC2 streaming alt");
        LOGE("matchRawUac20Format failed request sr=%d ch=%d bits=%d subslot=%d snapshot=%s match=%s",
             request.targetSampleRate, request.targetChannels, request.targetBits, request.targetSubslotBytes,
             state_.descriptorSummary.c_str(), state_.formatMatchSummary.c_str());
        return false;
    }

    selectedAlt_ = formatMatch_.selectedAlt;
    state_.sampleRate = formatMatch_.sampleRate;
    state_.channels = formatMatch_.channels;
    state_.validBits = formatMatch_.validBits;
    state_.subslotBytes = formatMatch_.subslotBytes;

    state_.audioControlInterface = descriptorSnapshot_.audioControlInterface;
    state_.audioStreamingInterface = selectedAlt_.interfaceNumber;
    state_.altSetting = selectedAlt_.altSetting;
    state_.outEndpoint = selectedAlt_.outEndpoint.address;
    state_.feedbackEndpoint = selectedAlt_.hasFeedbackEndpoint ? selectedAlt_.feedbackEndpoint.address : 0;
    state_.frameBytes = formatMatch_.frameBytes;
    state_.bytesPerSecond = formatMatch_.bytesPerSecond;
    state_.expectedBytesPerSecond = state_.bytesPerSecond;

    LOGI("selected stream via RawUac20FormatMatcher sr=%d ch=%d bits=%d subslot=%d iface=%d alt=%d out=0x%x fb=0x%x match=%s",
         state_.sampleRate, state_.channels, state_.validBits, state_.subslotBytes,
         state_.audioStreamingInterface, state_.altSetting, state_.outEndpoint, state_.feedbackEndpoint,
         state_.formatMatchSummary.c_str());
    return true;
}

bool Uac20Session::prepareFeedbackLocked(const Uac20Params& params) {
    // Migrate feedback endpoint selection and transfer allocation here.
    const bool hasFeedbackEndpoint = state_.feedbackEndpoint != 0;
    state_.policySummary = uac20PolicySummary(params, hasFeedbackEndpoint);
    state_.pendingRecovery = uac20UnderOutputRecoveryAction(params, hasFeedbackEndpoint);

    Uac20RecoveryPolicyInput recoveryInput;
    recoveryInput.signal = Uac20RecoverySignal::None;
    recoveryInput.descriptorHasExplicitFeedback = hasFeedbackEndpoint;
    recoveryInput.explicitFeedbackSelected = uac20ShouldUseExplicitFeedback(params, hasFeedbackEndpoint);
    recoveryInput.fullReopenAllowed = params.fullReopenOnNotOutputting;
    recoveryInput.resetAltAllowed = params.resetAltBeforeStart;
    recoveryInput.androidHalFallbackAllowed = true;
    const Uac20RecoveryPolicyDecision recoveryDecision = decideUac20Recovery(recoveryInput);
    state_.recoveryPolicySignal = static_cast<int>(recoveryInput.signal);
    state_.recoveryPolicyDecision = static_cast<int>(recoveryDecision.action);
    state_.recoveryPolicyDisableFeedback = recoveryDecision.disableFeedback;
    state_.recoveryPolicyKeepExplicitFeedback = recoveryDecision.keepExplicitFeedback;
    state_.recoveryPolicyFullReopen = recoveryDecision.requireFullReopen;
    state_.recoveryPolicyInputSummary = describeUac20RecoveryPolicyInput(recoveryInput);
    state_.recoveryPolicyDecisionSummary = recoveryDecision.summary;
    state_.policySummary += "; recovery-policy=native-v2";

    if (uac20ShouldUseExplicitFeedback(params, hasFeedbackEndpoint)) {
        state_.pacingMode = UacPacingMode::ExplicitFeedback;
        state_.feedbackState = UacFeedbackState::Discovered;
        return true;
    }

    state_.pacingMode = UacPacingMode::NoFeedbackFixed;
    state_.feedbackState = hasFeedbackEndpoint ? UacFeedbackState::Suspect : UacFeedbackState::None;
    return true;
}

bool Uac20Session::prepareOutTransfersLocked() {
    const bool explicitFeedback = state_.pacingMode == UacPacingMode::ExplicitFeedback;
    const auto mode = Uac20PackageAdjustMode::Conservative;
    const bool ok = prepareUac20OutTransferPlan(
            selectedAlt_,
            state_.sampleRate,
            state_.frameBytes,
            explicitFeedback,
            mode,
            0.0,
            &outTransferPlan_);

    captureOutTransferPlanLocked();

    Uac20PcmAdapterConfig adapterConfig;
    adapterConfig.channels = state_.channels;
    adapterConfig.source.validBits = std::max(0, params_.sourceBits);
    adapterConfig.source.subslotBytes = defaultSourceSubslotBytes(params_.sourceBits);
    adapterConfig.device.validBits = state_.validBits;
    adapterConfig.device.subslotBytes = state_.subslotBytes;
    adapterConfig.source24In32ShiftBits = 8;
    const bool adapterOk = pcmAdapter_.configure(adapterConfig);
    capturePcmAdapterStats(state_, pcmAdapter_.snapshot());
    if (!adapterOk) {
        LOGW("PCM adapter configure failed: %s", state_.pcmAdapterSummary.c_str());
    }

    // 0091: configure the split RawPcmPipeline. This handles swr (if needed)
    // and the device container adapter (e.g. FFmpeg S32LE -> packed S24 for
    // 24bit FLAC). The pipeline output is device-format PCM that feeds the
    // write ring and then ISO OUT.
    RawPcmPipelineConfig pipelineConfig;
    pipelineConfig.source.sampleRate = std::max(0, params_.sourceSampleRate > 0 ? params_.sourceSampleRate : state_.sampleRate);
    pipelineConfig.source.channels = std::max(0, params_.sourceChannels > 0 ? params_.sourceChannels : state_.channels);
    pipelineConfig.source.validBits = std::max(0, params_.sourceBits);
    pipelineConfig.source.containerBytesPerSample = std::max(0, defaultSourceSubslotBytes(params_.sourceBits));
    pipelineConfig.device.sampleRate = state_.sampleRate;
    pipelineConfig.device.channels = state_.channels;
    pipelineConfig.device.validBits = state_.validBits;
    pipelineConfig.device.containerBytesPerSample = state_.subslotBytes;
    pipelineConfig.transport = RawAudioTransportKind::Pcm;
    pipelineConfig.bitPerfect = true;
    pipelineConfig.source24In32ShiftBits = 8;
    pipelineConfig.swrOutputsSourceContainer = true;
    const bool pipelineOk = pcmPipeline_.configure(pipelineConfig);
    capturePcmPipelineStats(state_, pcmPipeline_.snapshot());
    if (!pipelineOk) {
        LOGW("PCM pipeline configure failed: %s", state_.pcmPipelineSummary.c_str());
    }

    // Step 93: build RawUac20StreamConfig from the selected
    // stream facts, but keep it dry-run only. The real playback path still uses
    // the existing Uac20Session submitter until the later explicit migration.
    buildRawStreamBridgeLocked("prepareOutTransfers");

    writeRing_.configure(state_.frameBytes, state_.expectedBytesPerSecond, 500);
    const auto writeStats = writeRing_.snapshot();
    state_.writeRingInitialized = writeStats.initialized;
    state_.writeRingShadowMode = writeStats.shadowMode;
    state_.writeRingFrameBytes = writeStats.frameBytes;
    state_.writeRingCapacityBytes = writeStats.capacityBytes;
    state_.writeRingLevelBytes = writeStats.levelBytes;
    state_.writeRingSummary = describeUac20WriteRingStats(writeStats);

    // 0031: Configure real OUT ring skeleton (dry-run, no real transfers)
    Uac20RealOutRingConfig ringConfig;
    ringConfig.endpoint = state_.outEndpoint;
    ringConfig.transferCount = 4;
    ringConfig.packetsPerTransfer = 8;
    ringConfig.transferBytes = state_.frameBytes * 48; // ~1ms at 192k/24/2ch
    ringConfig.queueBytes = ringConfig.transferCount * ringConfig.transferBytes;
    ringConfig.preallocatedBytes = ringConfig.queueBytes;
    ringConfig.frameBytes = state_.frameBytes;
    realOutRing_.configure(ringConfig);
    {
        const auto rs = realOutRing_.snapshot();
        state_.realOutRingInitialized = rs.initialized;
        state_.realOutRingDryRunOnly = rs.dryRunOnly;
        state_.realOutRingReadyForFeeder = rs.readyForFeeder;
        state_.realOutRingEndpoint = rs.endpoint;
        state_.realOutRingTransferCount = rs.transferCount;
        state_.realOutRingPacketsPerTransfer = rs.packetsPerTransfer;
        state_.realOutRingTransferBytes = rs.transferBytes;
        state_.realOutRingQueueBytes = rs.queueBytes;
        state_.realOutRingPreallocatedBytes = rs.preallocatedBytes;
        state_.realOutRingFrameBytes = rs.frameBytes;
        state_.realOutRingSummary = rs.summary;
    }
    phaseTracker_.transitionTo(Uac20SessionPhase::RealOutRingReady);
    capturePhaseStats(state_, phaseTracker_.snapshot());

    // 0032: Configure shadow-to-real OUT feeder (dry-run)
    Uac20OutFeederConfig feederConfig;
    feederConfig.transferBytes = ringConfig.transferBytes;
    feederConfig.maxConcurrentTransfers = ringConfig.transferCount;
    feederConfig.frameBytes = state_.frameBytes;
    outFeeder_.configure(feederConfig);
    {
        const auto fs = outFeeder_.evaluate(writeStats);
        state_.outFeederInitialized = fs.initialized;
        state_.outFeederDryRunOnly = fs.dryRunOnly;
        state_.outFeederReady = fs.ready;
        state_.outFeederUnderflowRisk = fs.underflowRisk;
        state_.outFeederRingLevelBytes = fs.ringLevelBytes;
        state_.outFeederTransferBudgetBytes = fs.transferBudgetBytes;
        state_.outFeederWouldSubmitTransfers = fs.wouldSubmitTransfers;
        state_.outFeederScheduledBytes = fs.scheduledBytes;
        state_.outFeederScheduledFrames = fs.scheduledFrames;
        state_.outFeederAlignmentRemainder = fs.alignmentRemainder;
        state_.outFeederSummary = fs.summary;
    }
    phaseTracker_.transitionTo(Uac20SessionPhase::OutFeederDryRun);
    capturePhaseStats(state_, phaseTracker_.snapshot());

    // 0033: Configure explicit-feedback-guided packet scheduler
    Uac20PacketSchedulerConfig schedConfig;
    schedConfig.mode = Uac20PacketSchedulerMode::ExplicitFeedbackGuided;
    schedConfig.packetCount = 8;
    schedConfig.nominalPacketBytes = state_.frameBytes * 48;
    schedConfig.explicitFeedback = state_.feedbackEndpoint != 0;
    schedConfig.feedbackLocked = state_.feedbackPersistentStarted;
    schedConfig.microframesPerPacket = 1;
    packetScheduler_.configure(schedConfig);
    {
        const auto ps = packetScheduler_.snapshot();
        state_.packetSchedulerInitialized = ps.initialized;
        state_.packetSchedulerMode = static_cast<int>(ps.mode);
        state_.packetSchedulerModeName = ps.modeName;
        state_.packetSchedulerExplicitFeedback = ps.explicitFeedback;
        state_.packetSchedulerFeedbackLocked = ps.feedbackLocked;
        state_.packetSchedulerPacketCount = ps.packetCount;
        state_.packetSchedulerNominalPacketBytes = ps.nominalPacketBytes;
        state_.packetSchedulerPatternSummary = ps.patternSummary;
        state_.packetSchedulerSummary = ps.summary;
    }

    // 0040-0043: Configure real OUT submitter via debug-flag-aware helper.
    // 0076: Do not re-prepare if the submitter already owns active transfers.
    {
        const auto activeSubmitter = realOutSubmitter_.snapshot();
        const bool realOutAlreadySubmitted = params_.enableDebugRealOutSubmitter &&
                (activeSubmitter.submitted || activeSubmitter.active || activeSubmitter.activeTransferCount > 0);
        if (!realOutAlreadySubmitted) {
            prepareDebugRealOutSubmitterLocked("prepareOutTransfers");
        } else {
            captureRealOutSubmitterLocked();
        }
    }

    if (!ok) {
        LOGW("OUT transfer plan failed: %s", state_.outTransferSummary.c_str());
        // Diagnostic v2 stage: surface the error but do not fail prepare yet.
        // Production playback is still routed through the legacy engine, and the
        // next patch can use this to choose another alt/format before submit.
        return true;
    }
    return true;
}

bool Uac20Session::submitFeedbackFirstLocked() {
    if (state_.feedbackEndpoint == 0 || state_.pacingMode != UacPacingMode::ExplicitFeedback) {
        return true;
    }

    const bool ok = feedbackTransfer_.start(
            deviceHandle_,
            state_.feedbackEndpoint,
            8 /* feedback packet probe size */);

    const auto stats = feedbackTransfer_.snapshot();
    state_.feedbackProbeAttempted = stats.attempted;
    state_.feedbackProbeSubmitted = stats.submitted;
    state_.feedbackProbeCompleted = stats.completeCount > 0;
    state_.feedbackProbeTimedOut = false;
    state_.feedbackPersistentStarted = stats.submitted;
    state_.feedbackPersistentActive = stats.active;
    state_.feedbackCancelled = stats.cancelled;
    state_.feedbackSubmitResult = stats.submitResult;
    state_.feedbackCancelResult = stats.cancelResult;
    state_.feedbackTransferStatus = stats.transferStatus;
    state_.feedbackFirstPacketBytes = stats.actualLength;
    state_.feedbackCompleteCount = stats.completeCount;
    state_.feedbackResubmitCount = stats.resubmitCount;
    state_.feedbackErrorCount = stats.errorCount;
    state_.feedbackRawValue = stats.rawValue;
    state_.feedbackFramesPerMicroframe = stats.feedbackFramesPerMicroframe;
    state_.feedbackSummary = describeUac20FeedbackRuntime(stats);

    if (ok) {
        state_.feedbackState = UacFeedbackState::Validating;
        LOGI("persistent feedback started: %s", state_.feedbackSummary.c_str());
        return true;
    }

    state_.feedbackState = stats.submitted ? UacFeedbackState::Suspect : UacFeedbackState::Failed;
    LOGW("persistent feedback start failed: %s", state_.feedbackSummary.c_str());

    // 0022: classify persistent feedback start failure
    if (!ok && state_.pacingMode == UacPacingMode::ExplicitFeedback) {
        runRecoveryPolicy(state_, params_, Uac20RecoverySignal::FeedbackTransferError, "feedback-start", recoveryAttemptTracker_, recoveryCandidatePlanner_, phaseTracker_);
        LOGW("recovery policy triggered signal=FeedbackTransferError decision=%s",
             state_.recoveryPolicyDecisionSummary.c_str());
    }

    // v2 diagnostic path: surface the failure but keep start() non-fatal while
    // production playback still uses the legacy engine. Later patches will make
    // this drive LowerFormat / FullReopen decisions before OUT is enabled.
    return true;
}

bool Uac20Session::startEventLoopLocked() {
    if (usbContext_ == nullptr) {
        setError("event loop start called before libusb init");
        return false;
    }

    const bool ok = eventLoop_.start(usbContext_, "uac20_v2_start");
    state_.eventThreadStarted = ok;
    state_.eventLoopTicks = eventLoop_.tickCount();
    state_.eventLoopOkCount = eventLoop_.okCount();
    state_.eventLoopTimeoutCount = eventLoop_.timeoutCount();
    state_.eventLoopWakeCount = eventLoop_.wakeCount();
    state_.eventLoopErrors = eventLoop_.errorCount();
    state_.eventLoopLastError = eventLoop_.lastError();
    state_.eventLoopSummary = eventLoop_.summary();

    if (!ok) {
        setError("event loop start failed");
        LOGW("event loop start failed: %s", state_.eventLoopSummary.c_str());
        // 0022: classify event loop failure as StartFailure
        runRecoveryPolicy(state_, params_, Uac20RecoverySignal::StartFailure, "event-loop-start", recoveryAttemptTracker_, recoveryCandidatePlanner_, phaseTracker_);
        LOGW("recovery policy triggered signal=StartFailure decision=%s",
             state_.recoveryPolicyDecisionSummary.c_str());
        // Diagnostic v2 stage: keep this non-fatal until real OUT/feedback rings
        // are moved to the new path. The failure is surfaced in runtimeJson.
        return true;
    }

    LOGI("event loop start ok: %s", state_.eventLoopSummary.c_str());
    return true;
}

bool Uac20Session::submitOutTransfersLocked() {
    if (!outTransferPlan_.prepared) {
        LOGW("silent OUT probe skipped: OUT transfer plan not prepared");
        return true;
    }

    // If persistent feedback has already delivered frames per microframe,
    // rebuild the OUT plan in feedback-guided mode before probing. This is the
    // Native package-adjust alignment: OUT packet sizes track
    // the real feedback rate instead of a fixed nominal value.
    const auto fbStats = feedbackTransfer_.snapshot();
    if (fbStats.completeCount > 0 && fbStats.feedbackFramesPerMicroframe > 0.0) {
        const auto guidedMode = Uac20PackageAdjustMode::FeedbackGuided;
        Uac20OutTransferPlan guidedPlan{};
        if (prepareUac20OutTransferPlan(
                    selectedAlt_,
                    state_.sampleRate,
                    state_.frameBytes,
                    state_.pacingMode == UacPacingMode::ExplicitFeedback,
                    guidedMode,
                    fbStats.feedbackFramesPerMicroframe,
                    &guidedPlan) && guidedPlan.prepared) {
            outTransferPlan_ = guidedPlan;
            captureOutTransferPlanLocked();
            LOGI("OUT plan rebuilt as FeedbackGuided fpmf=%.4f pattern=%s",
                 fbStats.feedbackFramesPerMicroframe,
                 outTransferPlan_.packetPatternSummary.c_str());
        } else {
            LOGW("FeedbackGuided plan rebuild failed, keeping Conservative: %s",
                 guidedPlan.lastError.c_str());
        }
    }

    // 0070-0071: once the debug-real-OUT feeder is explicitly enabled, it must
    // be the only owner of the OUT endpoint. The earlier silent OUT probe is a
    // bounded diagnostic transfer; running it immediately before the real feeder
    // consumes a probe-sized burst and leaves the debug feeder with no useful
    // completion signal. This is exactly what the 2026-06-30 MOONDROP log showed:
    // outProbe complete~420 / ratio~0.077 while realOutSubmitter had no healthy
    // completion. Skip the silent probe in debug playback mode and let
    // Uac20RealOutSubmitter own all OUT submissions.
    const bool debugRealOutOwnsEndpoint = params_.enableDebugRealOutSubmitter &&
            params_.debugRealOutFeedFromWriteRing;
    if (debugRealOutOwnsEndpoint) {
        LOGI("debug-real-out-skip-silent-probe");
        state_.outProbeAttempted = false;
        state_.outProbeSubmitted = false;
        state_.outProbeActive = false;
        state_.outProbeCancelled = false;
        state_.outProbeSubmitResult = 0;
        state_.outProbeCancelResult = 0;
        state_.outProbeTransferStatus = 0;
        state_.outProbeSubmittedTransfers = 0;
        state_.outProbeActiveTransfers = 0;
        state_.outProbeCompleteCount = 0;
        state_.outProbeResubmitCount = 0;
        state_.outProbeErrorCount = 0;
        state_.outProbeSubmitErrorCount = 0;
        state_.outProbeScheduledBytes = 0;
        state_.outProbeCompletedBytes = 0;
        state_.outProbeCompletedBytesPerSecond = 0;
        state_.outProbeExpectedBytesPerSecond = state_.expectedBytesPerSecond;
        state_.outProbeElapsedMs = 0;
        state_.outProbeCompletionRatio = 0.0;
        state_.outTransferSummary = "silent OUT probe skipped: debug real OUT feeder owns endpoint";
        LOGI("silent OUT probe skipped for debug real OUT feeder: %s", state_.outTransferSummary.c_str());
        return true;
    }

    const bool ok = outProbe_.run(
            deviceHandle_,
            outTransferPlan_,
            state_.expectedBytesPerSecond,
            420 /* bounded diagnostic window */);
    const auto stats = outProbe_.snapshot();

    state_.outProbeAttempted = stats.attempted;
    state_.outProbeSubmitted = stats.submitted;
    state_.outProbeActive = stats.active;
    state_.outProbeCancelled = stats.cancelled;
    state_.outProbeSubmitResult = stats.submitResult;
    state_.outProbeCancelResult = stats.cancelResult;
    state_.outProbeTransferStatus = stats.transferStatus;
    state_.outProbeSubmittedTransfers = stats.submittedTransferCount;
    state_.outProbeActiveTransfers = stats.activeTransferCount;
    state_.outProbeCompleteCount = stats.completeCount;
    state_.outProbeResubmitCount = stats.resubmitCount;
    state_.outProbeErrorCount = stats.errorCount;
    state_.outProbeSubmitErrorCount = stats.submitErrorCount;
    state_.outProbeScheduledBytes = stats.scheduledBytes;
    state_.outProbeCompletedBytes = stats.completedBytes;
    state_.outProbeCompletedBytesPerSecond = stats.completedBytesPerSecond;
    state_.outProbeExpectedBytesPerSecond = stats.expectedBytesPerSecond;
    state_.outProbeElapsedMs = stats.elapsedMs;
    state_.outProbeCompletionRatio = stats.completionRatio;
    state_.completedBytesPerSecond = stats.completedBytesPerSecond;
    state_.transferErrorCount = stats.errorCount;
    state_.submitErrorCount = stats.submitErrorCount;
    state_.outTransferSummary = describeUac20OutProbeStats(stats);

    if (ok) {
        LOGI("silent OUT probe ok: %s", state_.outTransferSummary.c_str());
    } else {
        LOGW("silent OUT probe did not complete cleanly: %s", state_.outTransferSummary.c_str());
    }

    // 0022: classify OUT probe health into a recovery signal and run the
    // native recovery policy. This replaces ad-hoc legacy recovery flags with
    // an architectural signal/decision model.
    Uac20RecoverySignal recoverySignal = Uac20RecoverySignal::None;
    const char* recoverySource = "silent-out-probe";
    if (stats.submitErrorCount > 0 && stats.completeCount == 0) {
        recoverySignal = Uac20RecoverySignal::OutSubmitError;
    } else if (stats.errorCount > 0 && stats.completeCount == 0) {
        recoverySignal = Uac20RecoverySignal::OutTransferError;
    } else if (stats.completionRatio < 0.75 && stats.expectedBytesPerSecond > 0) {
        recoverySignal = Uac20RecoverySignal::UsbNotOutputting;
    }
    if (recoverySignal != Uac20RecoverySignal::None) {
        runRecoveryPolicy(state_, params_, recoverySignal, recoverySource, recoveryAttemptTracker_, recoveryCandidatePlanner_, phaseTracker_);
        LOGW("recovery policy triggered signal=%s decision=%s source=%s",
             uac20RecoverySignalName(recoverySignal),
             state_.recoveryPolicyDecisionSummary.c_str(),
             recoverySource);
    }

    // Diagnostic v2 stage: never fail nativeUac20Start() yet. Production audio
    // is still on the legacy engine; this only surfaces OUT completion health in
    // runtimeJson so TP55/HyperOS can be compared against other ROM behavior.
    return true;
}

void Uac20Session::captureOutTransferPlanLocked() {
    state_.outTransferPlanPrepared = outTransferPlan_.prepared;
    state_.outTransferCount = outTransferPlan_.transferCount;
    state_.outPacketsPerTransfer = outTransferPlan_.packetsPerTransfer;
    state_.outPacketBytes = outTransferPlan_.nominalPacketBytes;
    state_.outMinPacketBytes = outTransferPlan_.minPacketBytes;
    state_.outMaxPacketBytesInPattern = outTransferPlan_.maxPacketBytesInPattern;
    state_.outTransferBytes = outTransferPlan_.transferBytes;
    state_.outQueueBytes = outTransferPlan_.queueBytes;
    state_.outEndpointMaxPacketSize = outTransferPlan_.endpointMaxPacketSize;
    state_.outEndpointInterval = outTransferPlan_.endpointInterval;
    state_.outIntervalsPerSecond = outTransferPlan_.intervalsPerSecond;
    state_.outPackageAdjustMode = static_cast<int>(outTransferPlan_.packageAdjustMode);
    state_.outTargetFramesPerPacket = outTransferPlan_.targetFramesPerPacket;
    state_.outFeedbackFramesPerMicroframe = outTransferPlan_.feedbackFramesPerMicroframe;
    state_.outPacketPatternSummary = outTransferPlan_.packetPatternSummary;
    state_.outTransferSummary = outTransferPlan_.summary.empty()
            ? describeUac20OutTransferPlan(outTransferPlan_)
            : outTransferPlan_.summary;
}

bool Uac20Session::buildRawStreamBridgeLocked(const char* source) {
    RawUac20StreamBuildInput input;
    input.selectedAlt = selectedAlt_;
    input.formatMatch = formatMatch_;
    input.outTransferPlan = outTransferPlan_;
    input.sampleRate = state_.sampleRate;
    input.channels = state_.channels;
    input.validBits = state_.validBits;
    input.subslotBytes = state_.subslotBytes;
    input.frameBytes = state_.frameBytes;
    input.bytesPerSecond = state_.bytesPerSecond;
    input.outEndpoint = state_.outEndpoint;
    input.feedbackEndpoint = state_.feedbackEndpoint;
    input.explicitFeedback = state_.pacingMode == UacPacingMode::ExplicitFeedback;
    input.feedbackRequired = input.explicitFeedback;
    input.allowFeedback = true;
    // Step 98: allow RawUac20Stream to own real OUT only behind the explicit
    // gray/debug takeover gate. Otherwise this remains a dry-run bridge.
    input.allowOutSubmit = rawStreamTakeoverEnabledLocked();
    input.allowZeroFill = true;
    input.dynamicPacketSizing = state_.pacingMode != UacPacingMode::ExplicitFeedback;
    input.autoResubmit = params_.debugRealOutAutoResubmit;
    input.debugSmokeTest = true;
    input.timeoutMs = 1000;
    input.cancelWaitMs = 1000;
    input.safetyPolicy = defaultRawAudioSafetyPolicy();

    rawStreamBuild_ = buildRawUac20StreamConfig(input);
    // Step 98: when takeover is enabled, the config already has allowOutSubmit=true
    // from the input bridge. For dry-run, keep shadow packet pacer enabled.
    if (rawStreamBuild_.built && !rawStreamTakeoverEnabledLocked()) {
        rawStreamBuild_.config.shadowPacketPacer = true;
        rawStreamBuild_.config.syntheticConsume = false;
    }
    captureRawStreamBuildLocked();

    // Step 94: configure RawUac20Stream from the bridge result
    // and prepare its URBs. The stream stays dry-run: allowOutSubmit=false so
    // it will not submit real ISO OUT transfers yet. Current playback continues
    // through the existing Uac20Session / RealOutSubmitter path.
    if (rawStreamBuild_.built) {
        configureRawStreamDryRunLocked(source);
    }

    LOGI("raw stream bridge build source=%s result={%s}",
         source ? source : "unknown", state_.rawStreamBuildSummary.c_str());
    return rawStreamBuild_.built;
}

void Uac20Session::captureRawStreamBuildLocked() {
    const RawUac20StreamConfig& c = rawStreamBuild_.config;
    state_.rawStreamConfigBuilt = rawStreamBuild_.built;
    state_.rawStreamConfigDryRunOnly = rawStreamBuild_.dryRunOnly;
    state_.rawStreamAllowOutSubmit = c.allowOutSubmit;
    state_.rawStreamExplicitFeedback = c.explicitFeedback;
    state_.rawStreamDynamicPacketSizing = c.dynamicPacketSizing;
    state_.rawStreamSampleRate = c.sampleRate;
    state_.rawStreamChannels = c.channels;
    state_.rawStreamValidBits = c.validBits;
    state_.rawStreamSubslotBytes = c.subslotBytes;
    state_.rawStreamFrameBytes = c.frameBytes;
    state_.rawStreamBytesPerSecond = c.bytesPerSecond;
    state_.rawStreamOutEndpoint = c.outEndpoint;
    state_.rawStreamFeedbackEndpoint = c.feedbackEndpoint;
    state_.rawStreamEndpointMaxPacketSize = c.endpointMaxPacketSize;
    state_.rawStreamPacketsPerTransfer = c.packetsPerTransfer;
    state_.rawStreamTransferCount = c.transferCount;
    state_.rawStreamTransferBytes = c.transferBytes;
    state_.rawStreamQueueBytes = c.queueBytes;
    state_.rawStreamStartupPrebufferBytes = c.startupPrebufferBytes;
    state_.rawStreamIntervalsPerSecond = c.intervalsPerSecond;
    state_.rawStreamBuildReason = rawStreamBuild_.reason;
    state_.rawStreamBuildSummary = rawStreamBuild_.summary;
    state_.rawStreamConfigSummary = describeRawUac20StreamConfig(c);
}

bool Uac20Session::configureRawStreamDryRunLocked(const char* source) {
    if (!rawStreamBuild_.built) {
        state_.rawStreamDryRunLastError = "bridge-not-built";
        resetRawStreamShadowWriteLocked();
        captureRawStreamDryRunLocked();
        return false;
    }

    const RawUac20StreamConfig config = rawStreamBuild_.config;
    const bool takeover = rawStreamTakeoverEnabledLocked();
    const bool configured = rawStreamDryRun_.configure([&] {
        auto c = config;
        if (takeover) {
            c.useExternalEventLoop = true;
            c.allowFeedback = false;
            c.syntheticConsume = false;
            c.shadowPacketPacer = false;
        }
        return c;
    }());
    if (!configured) {
        state_.rawStreamDryRunLastError = "raw-stream-configure-failed";
        captureRawStreamDryRunLocked();
        LOGW("raw stream dry-run configure failed: source=%s lastError=%s",
             source ? source : "unknown", state_.rawStreamDryRunLastError.c_str());
        return false;
    }
    const bool attached = configured && rawStreamDryRun_.attach(usbContext_, deviceHandle_);
    const bool prepared = attached && rawStreamDryRun_.prepareUrbs();
    if (!prepared) {
        state_.rawStreamDryRunLastError = "raw-stream-prepare-urbs-failed";
        captureRawStreamDryRunLocked();
        LOGW("raw stream dry-run prepareUrbs failed: source=%s", source ? source : "unknown");
        return false;
    }

    resetRawStreamShadowWriteLocked();
    state_.rawStreamShadowWriteEnabled = prepared && !takeover;
    state_.rawStreamRealOutTakeoverEnabled = takeover;
    state_.rawStreamRealOutTakeoverAttempted = takeover;
    state_.rawStreamRealOutTakeoverPrepared = takeover && prepared;
    state_.rawStreamRealOutTakeoverLegacySuppressed = takeover;
    state_.rawStreamRealOutTakeoverReason = takeover ? "configured-real-out-takeover" : "dry-run";
    captureRawStreamDryRunLocked();
    LOGI("raw stream dry-run prepared: source=%s state=%s",
         source ? source : "unknown", rawUac20StreamStateName(rawStreamDryRun_.status().state));
    return true;
}

void Uac20Session::captureRawStreamDryRunLocked() {
    const auto dryStats = rawStreamDryRun_.status();
    state_.rawStreamDryRunConfigured = dryStats.configured;
    state_.rawStreamDryRunAttached = dryStats.attached;
    state_.rawStreamDryRunPrepared = dryStats.prepared;
    state_.rawStreamDryRunStreaming = dryStats.streaming;
    state_.rawStreamDryRunReadyForOutStart = rawStreamDryRun_.readyForOutStart();
    state_.rawStreamDryRunOutSubmitted = dryStats.outSubmitted;
    state_.rawStreamDryRunWriteRingCapacityBytes = dryStats.writeRingCapacityBytes;
    state_.rawStreamDryRunWriteRingLevelBytes = dryStats.writeRingLevelBytes;
    state_.rawStreamDryRunRequiredPrebufferBytes = dryStats.startupPrebufferBytes;
    state_.rawStreamDryRunOutPrepared = dryStats.outPrepared ? 1 : 0;
    state_.rawStreamDryRunStateName = rawUac20StreamStateName(dryStats.state);
    state_.rawStreamDryRunLastError = dryStats.lastError;
    state_.rawStreamDryRunSummary = dryStats.summary;
    state_.rawStreamDryRunRuntimeJson = rawStreamDryRun_.runtimeJson();
    if (state_.rawStreamRealOutTakeoverEnabled) {
        state_.rawStreamRealOutTakeoverPrepared = dryStats.prepared;
        state_.rawStreamRealOutTakeoverStarted = dryStats.streaming;
        state_.rawStreamRealOutTakeoverActive = dryStats.outSubmitted || dryStats.outActive;
    }
    state_.rawStreamDryRunSyntheticConsumeEnabled = dryStats.syntheticConsumeEnabled;
    state_.rawStreamDryRunSyntheticConsumeActive = dryStats.syntheticConsumeActive;
    state_.rawStreamDryRunSyntheticConsumeCalls = dryStats.syntheticConsumeCalls;
    state_.rawStreamDryRunSyntheticConsumeTargetBytes = dryStats.syntheticConsumeTotalTargetBytes;
    state_.rawStreamDryRunSyntheticConsumeBytes = dryStats.syntheticConsumeTotalConsumedBytes;
    state_.rawStreamDryRunSyntheticConsumeUnderrunCalls = dryStats.syntheticConsumeUnderrunCalls;
    state_.rawStreamDryRunSyntheticConsumeLastTargetBytes = dryStats.syntheticConsumeLastTargetBytes;
    state_.rawStreamDryRunSyntheticConsumeLastBytes = dryStats.syntheticConsumeLastConsumedBytes;
    state_.rawStreamDryRunSyntheticConsumeLastMissingBytes = dryStats.syntheticConsumeLastMissingBytes;
    state_.rawStreamDryRunSyntheticConsumeBytesPerSecond = dryStats.syntheticConsumeBytesPerSecond;
    state_.rawStreamDryRunSyntheticConsumeSummary = dryStats.syntheticConsumeSummary;
    state_.rawStreamDryRunShadowPacerEnabled = dryStats.shadowPacketPacerEnabled;
    state_.rawStreamDryRunShadowPacerActive = dryStats.shadowPacketPacerActive;
    state_.rawStreamDryRunShadowPacerCalls = dryStats.shadowPacketPacerCalls;
    state_.rawStreamDryRunShadowPacerIntervals = dryStats.shadowPacketPacerIntervals;
    state_.rawStreamDryRunShadowPacerTransfers = dryStats.shadowPacketPacerTransfers;
    state_.rawStreamDryRunShadowPacerTargetBytes = dryStats.shadowPacketPacerTargetBytes;
    state_.rawStreamDryRunShadowPacerConsumedBytes = dryStats.shadowPacketPacerConsumedBytes;
    state_.rawStreamDryRunShadowPacerUnderrunPackets = dryStats.shadowPacketPacerUnderrunPackets;
    state_.rawStreamDryRunShadowPacerLastIntervalTargetBytes = dryStats.shadowPacketPacerLastIntervalTargetBytes;
    state_.rawStreamDryRunShadowPacerLastIntervalConsumedBytes = dryStats.shadowPacketPacerLastIntervalConsumedBytes;
    state_.rawStreamDryRunShadowPacerLastIntervalMissingBytes = dryStats.shadowPacketPacerLastIntervalMissingBytes;
    state_.rawStreamDryRunShadowPacerLastTransferTargetBytes = dryStats.shadowPacketPacerLastTransferTargetBytes;
    state_.rawStreamDryRunShadowPacerLastTransferConsumedBytes = dryStats.shadowPacketPacerLastTransferConsumedBytes;
    state_.rawStreamDryRunShadowPacerLastTransferMissingBytes = dryStats.shadowPacketPacerLastTransferMissingBytes;
    state_.rawStreamDryRunShadowPacerPacketLengthMin = dryStats.shadowPacketPacerLastPacketLengthMin;
    state_.rawStreamDryRunShadowPacerPacketLengthMax = dryStats.shadowPacketPacerLastPacketLengthMax;
    state_.rawStreamDryRunShadowPacerPacketLengthTotal = dryStats.shadowPacketPacerLastPacketLengthTotal;
    state_.rawStreamDryRunShadowPacerBytesPerSecond = dryStats.shadowPacketPacerBytesPerSecond;
    state_.rawStreamDryRunShadowPacerPacketPatternSummary = dryStats.shadowPacketPacerPacketPatternSummary;
    state_.rawStreamDryRunShadowPacerSummary = dryStats.shadowPacketPacerSummary;
}

int Uac20Session::shadowWriteRawStreamDryRunLocked(
        const uint8_t* data,
        int bytes,
        int legacyAcceptedBytes) {
    state_.rawStreamShadowWriteEnabled = state_.rawStreamDryRunPrepared &&
            state_.rawStreamConfigBuilt &&
            !state_.rawStreamAllowOutSubmit;
    state_.rawStreamShadowWriteReady = state_.rawStreamShadowWriteEnabled &&
            data != nullptr && bytes > 0;

    if (!state_.rawStreamShadowWriteReady) {
        state_.rawStreamShadowWriteSummary = "enabled=" +
                std::string(state_.rawStreamShadowWriteEnabled ? "yes" : "no") +
                " ready=no calls=" + std::to_string(state_.rawStreamShadowWriteCalls);
        return 0;
    }

    const int accepted = rawStreamDryRun_.writeDevicePcm(data, bytes);
    const int safeAccepted = std::max(0, accepted);
    const int safeLegacyAccepted = std::max(0, legacyAcceptedBytes);
    const int dropped = std::max(0, bytes - safeAccepted);
    const int mismatch = safeAccepted - safeLegacyAccepted;

    state_.rawStreamShadowWriteAttempted = true;
    state_.rawStreamShadowWriteLastInputBytes = bytes;
    state_.rawStreamShadowWriteLastAcceptedBytes = safeAccepted;
    state_.rawStreamShadowWriteLastLegacyAcceptedBytes = safeLegacyAccepted;
    state_.rawStreamShadowWriteLastDroppedBytes = dropped;
    state_.rawStreamShadowWriteLastMismatchBytes = mismatch;
    state_.rawStreamShadowWriteCalls += 1;
    state_.rawStreamShadowWriteInputBytes += bytes;
    state_.rawStreamShadowWriteAcceptedBytes += safeAccepted;
    state_.rawStreamShadowWriteDroppedBytes += dropped;
    state_.rawStreamShadowWriteBackpressure = dropped > 0;
    state_.rawStreamShadowWriteMismatch = mismatch != 0;

    captureRawStreamDryRunLocked();

    std::ostringstream os;
    os << "enabled=yes ready=yes calls=" << state_.rawStreamShadowWriteCalls
       << " in=" << state_.rawStreamShadowWriteInputBytes
       << " accepted=" << state_.rawStreamShadowWriteAcceptedBytes
       << " dropped=" << state_.rawStreamShadowWriteDroppedBytes
       << " last=" << bytes << "/" << safeAccepted
       << " legacyLast=" << safeLegacyAccepted
       << " mismatchLast=" << mismatch
       << " fifo=" << state_.rawStreamDryRunWriteRingLevelBytes
       << "/" << state_.rawStreamDryRunWriteRingCapacityBytes
       << " readyForOut=" << (state_.rawStreamDryRunReadyForOutStart ? "yes" : "no");
    if (accepted < 0) {
        os << " error=" << state_.rawStreamDryRunLastError;
    }
    state_.rawStreamShadowWriteSummary = os.str();
    return accepted;
}

bool Uac20Session::rawStreamTakeoverEnabledLocked() const {
    return params_.enableRawStreamRealOutTakeover &&
            params_.enableDebugRealOutSubmitter &&
            params_.debugRealOutFeedFromWriteRing;
}

bool Uac20Session::rawStreamTakeoverSinkReadyLocked() const {
    return rawStreamTakeoverEnabledLocked() &&
            state_.rawStreamRealOutTakeoverPrepared &&
            !state_.rawStreamRealOutTakeoverFallbackUsed &&
            state_.rawStreamDryRunPrepared;
}

bool Uac20Session::startRawStreamRealOutTakeoverLocked(const char* source) {
    state_.rawStreamRealOutTakeoverEnabled = rawStreamTakeoverEnabledLocked();
    state_.rawStreamRealOutTakeoverStartAttempted = true;
    state_.rawStreamRealOutTakeoverReason = source ? source : "raw-stream-real-out-start";

    if (!rawStreamTakeoverEnabledLocked()) {
        state_.rawStreamRealOutTakeoverFallbackReason = "takeover-not-enabled";
        captureRawStreamDryRunLocked();
        return false;
    }
    if (state_.rawStreamRealOutTakeoverFallbackUsed) {
        captureRawStreamDryRunLocked();
        return false;
    }

    const bool ok = rawStreamDryRun_.start(source ? source : "raw_stream_real_out_takeover");
    captureRawStreamDryRunLocked();
    state_.rawStreamRealOutTakeoverStarted = ok && state_.rawStreamDryRunStreaming;
    state_.rawStreamRealOutTakeoverActive = ok && state_.rawStreamDryRunOutSubmitted;
    if (!ok || !state_.rawStreamRealOutTakeoverActive) {
        fallbackRawStreamRealOutTakeoverLocked(
                state_.rawStreamDryRunLastError.empty()
                        ? "raw-stream-start-did-not-submit"
                        : state_.rawStreamDryRunLastError.c_str());
        return false;
    }

    phaseTracker_.transitionTo(Uac20SessionPhase::DebugRealOutRunning);
    capturePhaseStats(state_, phaseTracker_.snapshot());

    std::ostringstream os;
    os << "enabled=yes attempted=yes prepared="
       << (state_.rawStreamRealOutTakeoverPrepared ? "yes" : "no")
       << " started=yes active=" << (state_.rawStreamRealOutTakeoverActive ? "yes" : "no")
       << " legacySuppressed=" << (state_.rawStreamRealOutTakeoverLegacySuppressed ? "yes" : "no")
       << " fifo=" << state_.rawStreamDryRunWriteRingLevelBytes
       << "/" << state_.rawStreamDryRunWriteRingCapacityBytes
       << " out=" << state_.rawStreamDryRunSummary;
    state_.rawStreamRealOutTakeoverSummary = os.str();
    LOGI("raw stream real OUT takeover started source=%s status={%s}",
         source ? source : "unknown",
         state_.rawStreamRealOutTakeoverSummary.c_str());
    return true;
}

int Uac20Session::writeRawStreamRealOutTakeoverLocked(const uint8_t* data, int bytes) {
    state_.rawStreamRealOutTakeoverEnabled = rawStreamTakeoverEnabledLocked();
    state_.rawStreamRealOutTakeoverAttempted = true;
    state_.rawStreamRealOutTakeoverLegacySuppressed = true;
    state_.rawStreamRealOutTakeoverLastWriteBytes = bytes;

    const int accepted = rawStreamDryRun_.writeDevicePcm(data, bytes);
    const int safeAccepted = std::max(0, accepted);
    const int dropped = std::max(0, bytes - safeAccepted);

    state_.rawStreamRealOutTakeoverLastAcceptedBytes = safeAccepted;
    state_.rawStreamRealOutTakeoverLastDroppedBytes = dropped;
    state_.rawStreamRealOutTakeoverWriteCalls += 1;
    state_.rawStreamRealOutTakeoverInputBytes += bytes;
    state_.rawStreamRealOutTakeoverAcceptedBytes += safeAccepted;
    state_.rawStreamRealOutTakeoverDroppedBytes += dropped;
    captureRawStreamDryRunLocked();

    if (accepted < 0) {
        fallbackRawStreamRealOutTakeoverLocked(
                state_.rawStreamDryRunLastError.empty()
                        ? "raw-stream-write-failed"
                        : state_.rawStreamDryRunLastError.c_str());
        return accepted;
    }

    if (state_.running && !state_.rawStreamRealOutTakeoverStarted &&
        rawStreamDryRun_.readyForOutStart()) {
        startRawStreamRealOutTakeoverLocked("first_raw_stream_pcm_write");
    } else {
        state_.rawStreamRealOutTakeoverActive = state_.rawStreamDryRunStreaming &&
                state_.rawStreamDryRunOutSubmitted;
    }

    std::ostringstream os;
    os << "enabled=yes attempted=yes prepared="
       << (state_.rawStreamRealOutTakeoverPrepared ? "yes" : "no")
       << " startAttempted=" << (state_.rawStreamRealOutTakeoverStartAttempted ? "yes" : "no")
       << " started=" << (state_.rawStreamRealOutTakeoverStarted ? "yes" : "no")
       << " active=" << (state_.rawStreamRealOutTakeoverActive ? "yes" : "no")
       << " fallback=" << (state_.rawStreamRealOutTakeoverFallbackUsed ? "yes" : "no")
       << " calls=" << state_.rawStreamRealOutTakeoverWriteCalls
       << " in=" << state_.rawStreamRealOutTakeoverInputBytes
       << " accepted=" << state_.rawStreamRealOutTakeoverAcceptedBytes
       << " dropped=" << state_.rawStreamRealOutTakeoverDroppedBytes
       << " last=" << bytes << "/" << safeAccepted
       << " fifo=" << state_.rawStreamDryRunWriteRingLevelBytes
       << "/" << state_.rawStreamDryRunWriteRingCapacityBytes
       << " readyForOut=" << (state_.rawStreamDryRunReadyForOutStart ? "yes" : "no")
       << " outSubmitted=" << (state_.rawStreamDryRunOutSubmitted ? "yes" : "no");
    state_.rawStreamRealOutTakeoverSummary = os.str();
    return accepted;
}

void Uac20Session::fallbackRawStreamRealOutTakeoverLocked(const char* reason) {
    if (state_.rawStreamRealOutTakeoverFallbackUsed) return;
    state_.rawStreamRealOutTakeoverFallbackUsed = true;
    state_.rawStreamRealOutTakeoverFallbackReason = reason ? reason : "raw-stream-fallback";
    state_.rawStreamRealOutTakeoverLegacySuppressed = false;
    rawStreamDryRun_.stop(reason ? reason : "raw_stream_takeover_fallback");
    captureRawStreamDryRunLocked();
    prepareDebugRealOutSubmitterLocked("raw_stream_takeover_fallback_arm_legacy");
    LOGW("raw stream real OUT takeover fallback: %s", state_.rawStreamRealOutTakeoverFallbackReason.c_str());
}

void Uac20Session::resetRawStreamShadowWriteLocked() {
    state_.rawStreamShadowWriteEnabled = false;
    state_.rawStreamShadowWriteAttempted = false;
    state_.rawStreamShadowWriteReady = false;
    state_.rawStreamShadowWriteBackpressure = false;
    state_.rawStreamShadowWriteMismatch = false;
    state_.rawStreamShadowWriteLastInputBytes = 0;
    state_.rawStreamShadowWriteLastAcceptedBytes = 0;
    state_.rawStreamShadowWriteLastLegacyAcceptedBytes = 0;
    state_.rawStreamShadowWriteLastDroppedBytes = 0;
    state_.rawStreamShadowWriteLastMismatchBytes = 0;
    state_.rawStreamShadowWriteCalls = 0;
    state_.rawStreamShadowWriteInputBytes = 0;
    state_.rawStreamShadowWriteAcceptedBytes = 0;
    state_.rawStreamShadowWriteDroppedBytes = 0;
    state_.rawStreamShadowWriteSummary = "enabled=no calls=0";
}

void Uac20Session::captureRealOutSubmitterLocked() {
    const auto ss = realOutSubmitter_.snapshot();
    state_.realOutSubmitterInitialized = ss.initialized;
    state_.realOutSubmitterAllocated = ss.allocated;
    state_.realOutSubmitterAttempted = ss.attempted;
    state_.realOutSubmitterSubmitted = ss.submitted;
    state_.realOutSubmitterActive = ss.active;
    state_.realOutSubmitterSubmissionEnabled = ss.submissionEnabled;
    state_.realOutSubmitterDryRunBlocked = ss.dryRunBlockedSubmit;
    state_.realOutSubmitterFeedFromWriteRing = ss.feedFromWriteRing;
    state_.realOutSubmitterAutoResubmit = ss.autoResubmit;
    state_.realOutSubmitterBudgetExpired = ss.budgetExpired;
    state_.realOutSubmitterTransferCount = ss.transferCount;
    state_.realOutSubmitterAllocatedTransfers = ss.allocatedTransferCount;
    state_.realOutSubmitterSubmittedTransfers = ss.submittedTransferCount;
    state_.realOutSubmitterPacketsPerTransfer = ss.packetsPerTransfer;
    state_.realOutSubmitterTransferBytes = ss.transferBytes;
    state_.realOutSubmitterEndpointMaxPacketSize = ss.endpointMaxPacketSize;
    state_.realOutSubmitterPacketLengthTotal = ss.packetLengthTotal;
    state_.realOutSubmitterPacketLengthMin = ss.packetLengthMin;
    state_.realOutSubmitterPacketLengthMax = ss.packetLengthMax;
    state_.realOutSubmitterZeroLengthPacketCount = ss.zeroLengthPacketCount;
    state_.realOutSubmitterLayoutValid = ss.layoutValid;
    state_.realOutSubmitterLayoutMismatch = ss.layoutMismatch;
    state_.realOutSubmitterLayoutError = ss.layoutError;
    state_.realOutSubmitterQueueBytes = ss.queueBytes;
    state_.realOutSubmitterCallbackCount = ss.callbackCount;
    state_.realOutSubmitterCompleteCount = ss.completeCount;
    state_.realOutSubmitterResubmitCount = ss.resubmitCount;
    state_.realOutSubmitterSubmitErrorCount = ss.submitErrorCount;
    state_.realOutSubmitterSubmitOkCount = ss.submitOkCount;
    state_.realOutSubmitterSubmitFailCount = ss.submitFailCount;
    state_.realOutSubmitterTransferErrorCount = ss.transferErrorCount;
    state_.realOutSubmitterFeederUnderrunCount = ss.feederUnderrunCount;
    state_.realOutSubmitterCancelledCount = ss.cancelledCount;
    state_.realOutSubmitterSubmittedBytes = ss.submittedBytes;
    state_.realOutSubmitterCompletedBytes = ss.completedBytes;
    state_.realOutSubmitterCompletedBytesPerSecond = ss.completedBytesPerSecond;
    state_.realOutSubmitterExpectedBytesPerSecond = ss.expectedBytesPerSecond;
    state_.realOutSubmitterCompletionRatio = ss.completionRatio;
    state_.realOutSubmitterFedBytes = ss.fedBytes;
    state_.realOutSubmitterZeroFilledBytes = ss.zeroFilledBytes;
    state_.realOutSubmitterElapsedMs = ss.elapsedMs;
    state_.realOutSubmitterLastSubmitResult = ss.lastSubmitResult;
    state_.realOutSubmitterLastTransferStatus = ss.lastTransferStatus;
    state_.realOutSubmitterLastIsoPacketStatus = ss.lastIsoPacketStatus;
    state_.realOutSubmitterLastIsoActualLength = ss.lastIsoActualLength;
    state_.realOutSubmitterLastIsoPacketLength = ss.lastIsoPacketLength;
    state_.realOutSubmitterFirstSubmitMs = ss.firstSubmitMs;
    state_.realOutSubmitterLastSubmitMs = ss.lastSubmitMs;
    state_.realOutSubmitterFirstCallbackMs = ss.firstCallbackMs;
    state_.realOutSubmitterLastCallbackMs = ss.lastCallbackMs;
    state_.realOutSubmitterNoCompletionMs = ss.noCompletionMs;
    state_.realOutSubmitterSummary = describeUac20RealOutSubmitterStats(ss);
    state_.realOutSubmitterCleanStopRequested = ss.cleanStopRequested;
    state_.realOutSubmitterReleaseComplete = ss.releaseComplete;
    state_.realOutSubmitterReleaseDeferred = ss.releaseDeferred;
    state_.realOutSubmitterActiveTransferCount = ss.activeTransferCount;
    state_.realOutSubmitterPendingAfterCancel = ss.pendingAfterCancel;
    state_.realOutSubmitterCancelWaitMs = ss.cancelWaitMs;
    state_.realOutSubmitterCancelCalls = ss.cancelCalls;
    state_.realOutSubmitterLastStopReason = ss.lastStopReason;
    state_.realOutSubmitterSummary = describeUac20RealOutSubmitterStats(ss);
}

bool Uac20Session::prepareDebugRealOutSubmitterLocked(const char* source) {
    Uac20RealOutSubmitterConfig config;
    config.endpointAddress = state_.outEndpoint;
    config.transferCount = outTransferPlan_.transferCount > 0 ? outTransferPlan_.transferCount : 4;
    config.packetsPerTransfer = outTransferPlan_.packetsPerTransfer > 0 ? outTransferPlan_.packetsPerTransfer : 8;
    config.frameBytes = state_.frameBytes;
    config.sampleRate = state_.sampleRate;
    config.intervalsPerSecond = outTransferPlan_.intervalsPerSecond;
    config.dynamicPacketSizing = state_.pacingMode != UacPacingMode::ExplicitFeedback &&
            config.sampleRate > 0 && config.frameBytes > 0 && config.intervalsPerSecond > 0;
    const int maxFramesPerPacket = config.dynamicPacketSizing
            ? std::max(1, (config.sampleRate + config.intervalsPerSecond - 1) /
                    std::max(1, config.intervalsPerSecond)) + 1
            : 0;
    int dynamicTransferBytes = config.dynamicPacketSizing
            ? maxFramesPerPacket * config.frameBytes * config.packetsPerTransfer
            : 0;
    // Step 88: clamp dynamic transfer buffer to the endpoint's frame-aligned
    // max payload, matching the original engine's nominalCeilPacketBytes logic.
    if (config.dynamicPacketSizing && outTransferPlan_.endpointMaxPacketSize > 0 && config.frameBytes > 0) {
        const int frameAlignedEndpointMax =
                (outTransferPlan_.endpointMaxPacketSize / config.frameBytes) * config.frameBytes;
        if (frameAlignedEndpointMax > 0) {
            dynamicTransferBytes = std::min(
                    dynamicTransferBytes,
                    frameAlignedEndpointMax * config.packetsPerTransfer);
        }
    }
    config.transferBytes = std::max(
            outTransferPlan_.transferBytes > 0 ? outTransferPlan_.transferBytes : state_.frameBytes * 48,
            dynamicTransferBytes);
    config.packetBytes = config.dynamicPacketSizing ? std::vector<int>{} : outTransferPlan_.packetBytes;
    config.timeoutMs = 1000;
    config.cancelWaitMs = 1000;
    config.endpointMaxPacketSize = outTransferPlan_.endpointMaxPacketSize;
    config.expectedBytesPerSecond = state_.expectedBytesPerSecond;
    config.submissionEnabled = params_.enableDebugRealOutSubmitter;
    config.zeroFill = true;
    config.debugSmokeTest = !params_.debugRealOutFeedFromWriteRing;
    config.feedFromWriteRing = params_.debugRealOutFeedFromWriteRing;
    config.autoResubmit = params_.debugRealOutAutoResubmit;
    config.maxCallbacks = params_.debugRealOutMaxCallbacks;
    config.maxRunMs = params_.debugRealOutMaxRunMs;

    const bool ok = realOutSubmitter_.prepare(config);
    captureRealOutSubmitterLocked();
    LOGI("debug real OUT submitter prepare source=%s ok=%d stats={%s}",
         source ? source : "unknown", ok ? 1 : 0, state_.realOutSubmitterSummary.c_str());
    return ok;
}

bool Uac20Session::startDebugRealOutSubmitterLocked(const char* source) {
    prepareDebugRealOutSubmitterLocked(source ? source : "startDebugRealOut");
    const auto before = realOutSubmitter_.snapshot();
    if (!before.initialized) {
        captureRealOutSubmitterLocked();
        return true;
    }
    if (!before.submissionEnabled) {
        // Keep this visible in runtimeJson by attempting through the gated path.
        if (params_.debugRealOutFeedFromWriteRing) {
            realOutSubmitter_.startDebugFeeder(deviceHandle_, &writeRing_);
        } else {
            realOutSubmitter_.startZeroSubmit(deviceHandle_);
        }
        captureRealOutSubmitterLocked();
        return true;
    }

    const bool ok = params_.debugRealOutFeedFromWriteRing
            ? realOutSubmitter_.startDebugFeeder(deviceHandle_, &writeRing_)
            : realOutSubmitter_.startZeroSubmit(deviceHandle_);
    captureRealOutSubmitterLocked();
    if (state_.realOutSubmitterSubmitted || state_.realOutSubmitterActive) {
        phaseTracker_.transitionTo(Uac20SessionPhase::DebugRealOutRunning);
        capturePhaseStats(state_, phaseTracker_.snapshot());
    }
    if (!ok) {
        LOGW("debug real OUT submitter start failed: %s", state_.realOutSubmitterSummary.c_str());
        return true; // non-fatal in diagnostic stage
    }
    LOGI("debug real OUT submitter started: %s", state_.realOutSubmitterSummary.c_str());
    return true;
}

void Uac20Session::setError(const char* message) {
    state_.lastError = message ? message : "";
}

} // namespace rawsmusic::usb
