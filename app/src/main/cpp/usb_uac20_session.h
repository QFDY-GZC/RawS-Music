#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "usb_uac20_descriptors.h"
#include "raw_uac20_format_matcher.h"
#include "raw_pcm_pipeline.h"
#include "raw_uac20_stream_builder.h"
#include "usb_uac20_clock.h"
#include "usb_uac20_event_loop.h"
#include "usb_uac20_feedback.h"
#include "usb_uac20_out_feeder.h"
#include "usb_uac20_playback_guard.h"
#include "usb_uac20_recovery_executor.h"
#include "usb_uac20_format_fallback.h"
#include "usb_uac20_packet_scheduler.h"
#include "usb_uac20_pcm_adapter.h"
#include "usb_uac20_phase.h"
#include "usb_uac20_real_out_ring.h"
#include "usb_uac20_real_out_submitter.h"
#include "usb_uac20_recovery_attempt.h"
#include "usb_uac20_recovery_candidates.h"
#include "usb_uac20_recovery_execution.h"
#include "usb_uac20_transfers.h"
#include "usb_uac20_write_ring.h"

struct libusb_context;
struct libusb_device_handle;
struct libusb_transfer;

namespace rawsmusic::usb {

enum class UacPacingMode : int {
    NoFeedbackFixed = 0,
    ExplicitFeedback = 1,
    FeedbackDegradedFixed = 2,
};

enum class UacFeedbackState : int {
    None = 0,
    Discovered = 1,
    Validating = 2,
    Locked = 3,
    Suspect = 4,
    Degraded = 5,
    Failed = 6,
};

enum class UacRecoveryAction : int {
    None = 0,
    FullReopen = 1,
    ResetAltAndRestart = 2,
    LowerFormat = 3,
    AndroidHalFallback = 4,
};

struct Uac20Params {
    int sourceSampleRate = 0;
    int sourceBits = 0;
    int sourceChannels = 0;

    int requestedSampleRate = 0;
    int requestedBits = 0;
    int requestedSubslotBytes = 0;

    bool resetAltBeforeStart = true;
    bool preferExplicitFeedback = true;
    bool forbidLearnedNoFeedback = true;
    bool minimalMixerControl = true;
    bool prefer24In32 = true;
    bool fullReopenOnNotOutputting = true;

    // 0040-0043 debug-only playback gate. These flags never enable production
    // playback; they only allow an explicitly requested v2 preview session to
    // start the real OUT submitter after feedback/event-loop ownership is ready.
    bool enableDebugRealOutSubmitter = false;
    bool debugRealOutFeedFromWriteRing = false;
    bool debugRealOutAutoResubmit = false;
    bool enableRawStreamRealOutTakeover = false;
    bool enableDebugPlaybackRuntimeGuard = false;
    bool enableDebugRecoveryExecutor = false;
    bool enableDebugFormatFallbackExecutor = false;
    int debugRealOutMaxCallbacks = 0; // unlimited during bounded debug playback; maxRunMs/session stop owns the budget.
    int debugRealOutMaxRunMs = 0;
    int debugRealOutPrebufferMs = 20;
};

struct Uac20RuntimeState {
    bool opened = false;
    bool prepared = false;
    bool running = false;

    int sampleRate = 0;
    int channels = 0;
    int validBits = 0;
    int subslotBytes = 0;
    int frameBytes = 0;
    int bytesPerSecond = 0;

    int audioControlInterface = -1;
    int audioStreamingInterface = -1;
    int altSetting = 0;
    int outEndpoint = 0;
    int feedbackEndpoint = 0;

    UacPacingMode pacingMode = UacPacingMode::NoFeedbackFixed;
    UacFeedbackState feedbackState = UacFeedbackState::None;
    UacRecoveryAction pendingRecovery = UacRecoveryAction::None;

    int completedBytesPerSecond = 0;
    int expectedBytesPerSecond = 0;
    int underrunCount = 0;
    int submitErrorCount = 0;
    int transferErrorCount = 0;

    bool interfacesClaimed = false;
    bool altReset = false;
    bool playbackAltSet = false;
    bool clockConfigured = false;
    bool clockVerified = false;
    int selectedClockSource = 0;
    int deviceSampleRate = 0;
    int clockSetResult = 0;
    int clockGetResult = 0;

    bool feedbackProbeAttempted = false;
    bool feedbackProbeSubmitted = false;
    bool feedbackProbeCompleted = false;
    bool feedbackProbeTimedOut = false;
    bool feedbackPersistentStarted = false;
    bool feedbackPersistentActive = false;
    bool feedbackCancelled = false;
    int feedbackSubmitResult = 0;
    int feedbackCancelResult = 0;
    int feedbackTransferStatus = 0;
    int feedbackFirstPacketBytes = 0;
    int feedbackCompleteCount = 0;
    int feedbackResubmitCount = 0;
    int feedbackErrorCount = 0;
    uint32_t feedbackRawValue = 0;
    double feedbackFramesPerMicroframe = 0.0;

    bool eventThreadStarted = false;
    int64_t eventLoopTicks = 0;
    int64_t eventLoopOkCount = 0;
    int64_t eventLoopTimeoutCount = 0;
    int64_t eventLoopWakeCount = 0;
    int eventLoopErrors = 0;
    int eventLoopLastError = 0;

    bool outTransferPlanPrepared = false;
    int outTransferCount = 0;
    int outPacketsPerTransfer = 0;
    int outPacketBytes = 0;
    int outMinPacketBytes = 0;
    int outMaxPacketBytesInPattern = 0;
    int outTransferBytes = 0;
    int outQueueBytes = 0;
    int outEndpointMaxPacketSize = 0;
    int outEndpointInterval = 0;
    int outIntervalsPerSecond = 0;
    int outPackageAdjustMode = 0;
    double outTargetFramesPerPacket = 0.0;
    double outFeedbackFramesPerMicroframe = 0.0;
    std::string outPacketPatternSummary;

    bool outProbeAttempted = false;
    bool outProbeSubmitted = false;
    bool outProbeActive = false;
    bool outProbeCancelled = false;
    int outProbeSubmitResult = 0;
    int outProbeCancelResult = 0;
    int outProbeTransferStatus = 0;
    int outProbeSubmittedTransfers = 0;
    int outProbeActiveTransfers = 0;
    int outProbeCompleteCount = 0;
    int outProbeResubmitCount = 0;
    int outProbeErrorCount = 0;
    int outProbeSubmitErrorCount = 0;
    int outProbeScheduledBytes = 0;
    int outProbeCompletedBytes = 0;
    int outProbeCompletedBytesPerSecond = 0;
    int outProbeExpectedBytesPerSecond = 0;
    int outProbeElapsedMs = 0;
    double outProbeCompletionRatio = 0.0;

    bool writeRingInitialized = false;
    bool writeRingShadowMode = true;
    int writeRingFrameBytes = 0;
    int writeRingCapacityBytes = 0;
    int writeRingLevelBytes = 0;
    int writeRingMaxLevelBytes = 0;
    int writeRingAppInBytesPerSecond = 0;
    int writeRingLastWriteBytes = 0;
    int writeRingLastAcceptedBytes = 0;
    int writeRingLastDroppedBytes = 0;
    int writeRingLastAlignmentRemainder = 0;
    int64_t writeRingTotalInputBytes = 0;
    int64_t writeRingTotalAcceptedBytes = 0;
    int64_t writeRingTotalDroppedBytes = 0;
    int64_t writeRingWriteCalls = 0;
    int64_t writeRingUnalignedWriteCalls = 0;
    std::string writeRingSummary;

    bool pcmAdapterConfigured = false;
    int pcmAdapterMode = 0;
    int pcmAdapterSourceFrameBytes = 0;
    int pcmAdapterDeviceFrameBytes = 0;
    int pcmAdapterLastInputBytes = 0;
    int pcmAdapterLastOutputBytes = 0;
    int pcmAdapterLastRemainderBytes = 0;
    int64_t pcmAdapterTotalInputBytes = 0;
    int64_t pcmAdapterTotalOutputBytes = 0;
    int64_t pcmAdapterConvertCalls = 0;
    int64_t pcmAdapterUnalignedCalls = 0;
    std::string pcmAdapterSummary;

    bool pcmPipelineConfigured = false;
    bool pcmPipelineResamplerRequired = false;
    bool pcmPipelineResamplerReady = false;
    int pcmPipelineSourceFrameBytes = 0;
    int pcmPipelineSWRFrameBytes = 0;
    int pcmPipelineDeviceFrameBytes = 0;
    int64_t pcmPipelineTotalInputBytes = 0;
    int64_t pcmPipelineTotalConsumedBytes = 0;
    int64_t pcmPipelineTotalProducedBytes = 0;
    int64_t pcmPipelineProcessCalls = 0;
    int64_t pcmPipelineUnalignedCalls = 0;
    int64_t pcmPipelineZeroOutputCalls = 0;
    int pcmPipelineLastInputBytes = 0;
    int pcmPipelineLastConsumedBytes = 0;
    int pcmPipelineLastOutputBytes = 0;
    int pcmPipelineLastRemainderBytes = 0;
    int pcmPipelineLastErrorCode = 0;
    std::string pcmPipelineSummary;

    bool rawStreamConfigBuilt = false;
    bool rawStreamConfigDryRunOnly = true;
    bool rawStreamAllowOutSubmit = false;
    bool rawStreamExplicitFeedback = false;
    bool rawStreamDynamicPacketSizing = true;
    int rawStreamSampleRate = 0;
    int rawStreamChannels = 0;
    int rawStreamValidBits = 0;
    int rawStreamSubslotBytes = 0;
    int rawStreamFrameBytes = 0;
    int rawStreamBytesPerSecond = 0;
    int rawStreamOutEndpoint = 0;
    int rawStreamFeedbackEndpoint = 0;
    int rawStreamEndpointMaxPacketSize = 0;
    int rawStreamPacketsPerTransfer = 0;
    int rawStreamTransferCount = 0;
    int rawStreamTransferBytes = 0;
    int rawStreamQueueBytes = 0;
    int rawStreamStartupPrebufferBytes = 0;
    int rawStreamIntervalsPerSecond = 0;
    std::string rawStreamBuildReason;
    std::string rawStreamBuildSummary;
    std::string rawStreamConfigSummary;

    bool rawStreamDryRunConfigured = false;
    bool rawStreamDryRunAttached = false;
    bool rawStreamDryRunPrepared = false;
    bool rawStreamDryRunStreaming = false;
    bool rawStreamDryRunReadyForOutStart = false;
    bool rawStreamDryRunOutSubmitted = false;
    int rawStreamDryRunWriteRingCapacityBytes = 0;
    int rawStreamDryRunWriteRingLevelBytes = 0;
    int rawStreamDryRunRequiredPrebufferBytes = 0;
    int rawStreamDryRunOutPrepared = 0;
    std::string rawStreamDryRunStateName;
    std::string rawStreamDryRunLastError;
    std::string rawStreamDryRunSummary;
    std::string rawStreamDryRunRuntimeJson;

    // Step 95: shadow-write the already-converted device PCM into RawUac20Stream
    // dry-run FIFO while the legacy writeRing/real OUT path remains authoritative.
    bool rawStreamShadowWriteEnabled = false;
    bool rawStreamShadowWriteAttempted = false;
    bool rawStreamShadowWriteReady = false;
    bool rawStreamShadowWriteBackpressure = false;
    bool rawStreamShadowWriteMismatch = false;
    int rawStreamShadowWriteLastInputBytes = 0;
    int rawStreamShadowWriteLastAcceptedBytes = 0;
    int rawStreamShadowWriteLastLegacyAcceptedBytes = 0;
    int rawStreamShadowWriteLastDroppedBytes = 0;
    int rawStreamShadowWriteLastMismatchBytes = 0;
    int64_t rawStreamShadowWriteCalls = 0;
    int64_t rawStreamShadowWriteInputBytes = 0;
    int64_t rawStreamShadowWriteAcceptedBytes = 0;
    int64_t rawStreamShadowWriteDroppedBytes = 0;
    std::string rawStreamShadowWriteSummary;

    // Step 98: real OUT takeover diagnostics.
    bool rawStreamRealOutTakeoverEnabled = false;
    bool rawStreamRealOutTakeoverAttempted = false;
    bool rawStreamRealOutTakeoverPrepared = false;
    bool rawStreamRealOutTakeoverStartAttempted = false;
    bool rawStreamRealOutTakeoverStarted = false;
    bool rawStreamRealOutTakeoverActive = false;
    bool rawStreamRealOutTakeoverFallbackUsed = false;
    bool rawStreamRealOutTakeoverLegacySuppressed = false;
    int rawStreamRealOutTakeoverLastWriteBytes = 0;
    int rawStreamRealOutTakeoverLastAcceptedBytes = 0;
    int rawStreamRealOutTakeoverLastDroppedBytes = 0;
    int64_t rawStreamRealOutTakeoverWriteCalls = 0;
    int64_t rawStreamRealOutTakeoverInputBytes = 0;
    int64_t rawStreamRealOutTakeoverAcceptedBytes = 0;
    int64_t rawStreamRealOutTakeoverDroppedBytes = 0;
    std::string rawStreamRealOutTakeoverReason;
    std::string rawStreamRealOutTakeoverFallbackReason;
    std::string rawStreamRealOutTakeoverSummary;

    bool rawStreamDryRunSyntheticConsumeEnabled = false;
    bool rawStreamDryRunSyntheticConsumeActive = false;
    int64_t rawStreamDryRunSyntheticConsumeCalls = 0;
    int64_t rawStreamDryRunSyntheticConsumeTargetBytes = 0;
    int64_t rawStreamDryRunSyntheticConsumeBytes = 0;
    int64_t rawStreamDryRunSyntheticConsumeUnderrunCalls = 0;
    int rawStreamDryRunSyntheticConsumeLastTargetBytes = 0;
    int rawStreamDryRunSyntheticConsumeLastBytes = 0;
    int rawStreamDryRunSyntheticConsumeLastMissingBytes = 0;
    int rawStreamDryRunSyntheticConsumeBytesPerSecond = 0;
    std::string rawStreamDryRunSyntheticConsumeSummary;

    bool rawStreamDryRunShadowPacerEnabled = false;
    bool rawStreamDryRunShadowPacerActive = false;
    int64_t rawStreamDryRunShadowPacerCalls = 0;
    int64_t rawStreamDryRunShadowPacerIntervals = 0;
    int64_t rawStreamDryRunShadowPacerTransfers = 0;
    int64_t rawStreamDryRunShadowPacerTargetBytes = 0;
    int64_t rawStreamDryRunShadowPacerConsumedBytes = 0;
    int64_t rawStreamDryRunShadowPacerUnderrunPackets = 0;
    int rawStreamDryRunShadowPacerLastIntervalTargetBytes = 0;
    int rawStreamDryRunShadowPacerLastIntervalConsumedBytes = 0;
    int rawStreamDryRunShadowPacerLastIntervalMissingBytes = 0;
    int rawStreamDryRunShadowPacerLastTransferTargetBytes = 0;
    int rawStreamDryRunShadowPacerLastTransferConsumedBytes = 0;
    int rawStreamDryRunShadowPacerLastTransferMissingBytes = 0;
    int rawStreamDryRunShadowPacerPacketLengthMin = 0;
    int rawStreamDryRunShadowPacerPacketLengthMax = 0;
    int rawStreamDryRunShadowPacerPacketLengthTotal = 0;
    int rawStreamDryRunShadowPacerBytesPerSecond = 0;
    std::string rawStreamDryRunShadowPacerPacketPatternSummary;
    std::string rawStreamDryRunShadowPacerSummary;

    std::string descriptorSummary;
    std::string formatMatchSummary;
    std::string policySummary;
    std::string recoveryPolicyInputSummary;
    std::string recoveryPolicyDecisionSummary;
    int recoveryPolicySignal = 0;
    int recoveryPolicyDecision = 0;
    bool recoveryPolicyDisableFeedback = false;
    bool recoveryPolicyKeepExplicitFeedback = true;
    bool recoveryPolicyFullReopen = false;
    std::string recoveryPolicySource;
    bool recoveryPolicyResetAlt = false;
    bool recoveryPolicyLowerFormat = false;
    bool recoveryPolicyAndroidFallback = false;
    bool recoveryPolicyTransportLost = false;
    bool recoveryAttemptInitialized = false;
    bool recoveryAttemptHasDecision = false;
    bool recoveryAttemptBudgetExhausted = false;
    bool recoveryAttemptFallbackSuggested = false;
    int recoveryAttemptIndex = 0;
    int recoveryAttemptTotal = 0;
    int recoveryAttemptNextAction = 0;
    std::string recoveryAttemptNextActionName;
    int recoveryAttemptFullReopenCount = 0;
    int recoveryAttemptResetAltCount = 0;
    int recoveryAttemptLowerFormatCount = 0;
    int recoveryAttemptBudgetRemaining = 0;
    std::string recoveryAttemptReport;
    std::string recoveryAttemptHistory;
    bool recoveryCandidatePlanInitialized = false;
    bool recoveryCandidatePlanHasCandidates = false;
    bool recoveryCandidatePlanHasSelected = false;
    int recoveryCandidateCount = 0;
    int recoveryCandidateSelectedIndex = -1;
    int recoveryCandidateSelectedAction = 0;
    int recoveryCandidateSelectedSampleRate = 0;
    int recoveryCandidateSelectedBits = 0;
    int recoveryCandidateSelectedSubslotBytes = 0;
    bool recoveryCandidateSelectedKeepExplicitFeedback = true;
    bool recoveryCandidateSelectedDisableFeedback = false;
    bool recoveryCandidateSelectedFullReopen = false;
    bool recoveryCandidateSelectedResetAlt = false;
    bool recoveryCandidateSelectedLowerFormat = false;
    bool recoveryCandidateSelectedAndroidFallback = false;
    std::string recoveryCandidatePlanReport;
    std::string recoveryCandidateList;
    bool recoveryExecutionPlanInitialized = false;
    bool recoveryExecutionDryRunOnly = true;
    bool recoveryExecutionHasSelectedCandidate = false;
    bool recoveryExecutionTerminal = false;
    bool recoveryExecutionBlocked = false;
    int recoveryExecutionCandidateIndex = -1;
    int recoveryExecutionCandidateAction = 0;
    std::string recoveryExecutionCandidateActionName;
    std::string recoveryExecutionCandidateLabel;
    int recoveryExecutionTargetSampleRate = 0;
    int recoveryExecutionTargetBits = 0;
    int recoveryExecutionTargetSubslotBytes = 0;
    int recoveryExecutionTargetChannels = 0;
    int recoveryExecutionStepCount = 0;
    bool recoveryExecutionRequiresStop = false;
    bool recoveryExecutionRequiresClose = false;
    bool recoveryExecutionRequiresReopen = false;
    bool recoveryExecutionRequiresClaimInterfaces = false;
    bool recoveryExecutionRequiresAltReset = false;
    bool recoveryExecutionRequiresClockSet = false;
    bool recoveryExecutionRequiresPlaybackAlt = false;
    bool recoveryExecutionRequiresFeedbackRestart = false;
    bool recoveryExecutionRequiresOutRestart = false;
    bool recoveryExecutionRequiresFormatChange = false;
    bool recoveryExecutionRequiresAndroidFallback = false;
    bool recoveryExecutionMarksTransportLost = false;
    std::string recoveryExecutionBlockingReason;
    std::string recoveryExecutionPlanSummary;
    std::string recoveryExecutionStepsSummary;
    int sessionPhase = 0;
    std::string sessionPhaseName;
    int sessionPreviousPhase = 0;
    std::string sessionPreviousPhaseName;
    int sessionPhaseRank = 0;
    int sessionPhaseTransitionCount = 0;
    int sessionPhaseNonMonotonicCount = 0;
    int sessionPhaseErrorCount = 0;
    std::string sessionPhaseSummary;
    std::string sessionPhaseHistory;
    bool realOutRingInitialized = false;
    bool realOutRingDryRunOnly = true;
    bool realOutRingReadyForFeeder = false;
    int realOutRingEndpoint = 0;
    int realOutRingTransferCount = 0;
    int realOutRingPacketsPerTransfer = 0;
    int realOutRingTransferBytes = 0;
    int realOutRingQueueBytes = 0;
    int realOutRingPreallocatedBytes = 0;
    int realOutRingFrameBytes = 0;
    std::string realOutRingSummary;

    bool playbackGuardInitialized = false;
    bool playbackGuardPassed = false;
    bool playbackGuardBlocksPromotion = true;
    std::string playbackGuardReason;
    std::string playbackGuardSummary;

    bool recoveryExecutorInitialized = false;
    bool recoveryExecutorEnabled = false;
    bool recoveryExecutorAttempted = false;
    bool recoveryExecutorExecuted = false;
    bool recoveryExecutorBlocked = true;
    std::string recoveryExecutorSummary;

    bool formatFallbackPlanInitialized = false;
    bool formatFallbackExecutionEnabled = false;
    bool formatFallbackHasCandidates = false;
    int formatFallbackCandidateCount = 0;
    int formatFallbackSelectedIndex = -1;
    std::string formatFallbackSummary;
    std::string formatFallbackCandidateSummary;

    bool outFeederInitialized = false;
    bool outFeederDryRunOnly = true;
    bool outFeederReady = false;
    bool outFeederUnderflowRisk = false;
    int outFeederRingLevelBytes = 0;
    int outFeederTransferBudgetBytes = 0;
    int outFeederWouldSubmitTransfers = 0;
    int outFeederScheduledBytes = 0;
    int outFeederScheduledFrames = 0;
    int outFeederAlignmentRemainder = 0;
    std::string outFeederSummary;
    bool packetSchedulerInitialized = false;
    int packetSchedulerMode = 0;
    std::string packetSchedulerModeName;
    bool packetSchedulerExplicitFeedback = false;
    bool packetSchedulerFeedbackLocked = false;
    int packetSchedulerPacketCount = 0;
    int packetSchedulerNominalPacketBytes = 0;
    std::string packetSchedulerPatternSummary;
    std::string packetSchedulerSummary;
    bool realOutSubmitterInitialized = false;
    bool realOutSubmitterAllocated = false;
    bool realOutSubmitterAttempted = false;
    bool realOutSubmitterSubmitted = false;
    bool realOutSubmitterActive = false;
    bool realOutSubmitterSubmissionEnabled = false;
    bool realOutSubmitterDryRunBlocked = false;
    bool realOutSubmitterFeedFromWriteRing = false;
    bool realOutSubmitterAutoResubmit = false;
    bool realOutSubmitterBudgetExpired = false;
    int realOutSubmitterTransferCount = 0;
    int realOutSubmitterAllocatedTransfers = 0;
    int realOutSubmitterSubmittedTransfers = 0;
    int realOutSubmitterPacketsPerTransfer = 0;
    int realOutSubmitterTransferBytes = 0;
    int realOutSubmitterEndpointMaxPacketSize = 0;
    int realOutSubmitterPacketLengthTotal = 0;
    int realOutSubmitterPacketLengthMin = 0;
    int realOutSubmitterPacketLengthMax = 0;
    int realOutSubmitterZeroLengthPacketCount = 0;
    bool realOutSubmitterLayoutValid = false;
    bool realOutSubmitterLayoutMismatch = false;
    std::string realOutSubmitterLayoutError;
    int realOutSubmitterQueueBytes = 0;
    int realOutSubmitterCallbackCount = 0;
    int realOutSubmitterCompleteCount = 0;
    int realOutSubmitterResubmitCount = 0;
    int realOutSubmitterSubmitErrorCount = 0;
    int realOutSubmitterSubmitOkCount = 0;
    int realOutSubmitterSubmitFailCount = 0;
    int realOutSubmitterTransferErrorCount = 0;
    int realOutSubmitterFeederUnderrunCount = 0;
    int realOutSubmitterCancelledCount = 0;
    int64_t realOutSubmitterSubmittedBytes = 0;
    int64_t realOutSubmitterCompletedBytes = 0;
    int realOutSubmitterFedBytes = 0;
    int realOutSubmitterZeroFilledBytes = 0;
    int realOutSubmitterElapsedMs = 0;
    int realOutSubmitterLastSubmitResult = 0;
    int realOutSubmitterLastTransferStatus = 0;
    int realOutSubmitterLastIsoPacketStatus = 0;
    int realOutSubmitterLastIsoActualLength = 0;
    int realOutSubmitterLastIsoPacketLength = 0;
    int realOutSubmitterFirstSubmitMs = 0;
    int realOutSubmitterLastSubmitMs = 0;
    int realOutSubmitterFirstCallbackMs = 0;
    int realOutSubmitterLastCallbackMs = 0;
    int realOutSubmitterNoCompletionMs = 0;
    bool realOutSubmitterDebugSmoke = true;
    bool realOutSubmitterCleanStopRequested = false;
    bool realOutSubmitterReleaseComplete = false;
    bool realOutSubmitterReleaseDeferred = false;
    int realOutSubmitterActiveTransferCount = 0;
    int realOutSubmitterPendingAfterCancel = 0;
    int realOutSubmitterCancelWaitMs = 0;
    int realOutSubmitterCancelCalls = 0;
    std::string realOutSubmitterLastStopReason;
    std::string realOutSubmitterSummary;
    int realOutSubmitterCompletedBytesPerSecond = 0;
    int realOutSubmitterExpectedBytesPerSecond = 0;
    double realOutSubmitterCompletionRatio = 0.0;
    std::string feedbackSummary;
    std::string eventLoopSummary;
    std::string outTransferSummary;
    std::string lastError;
};

class Uac20Session {
public:
    Uac20Session();
    ~Uac20Session();

    Uac20Session(const Uac20Session&) = delete;
    Uac20Session& operator=(const Uac20Session&) = delete;

    bool openFromFd(int fd);
    bool prepare(const Uac20Params& params);
    bool start();
    void stop(const char* reason);
    void close(const char* reason);

    int write(const uint8_t* data, int length);

    Uac20RuntimeState runtimeState() const;
    std::string runtimeJson() const;

private:
    bool parseDescriptors();
    bool selectStreamLocked(const Uac20Params& params);
    bool claimInterfacesLocked();
    bool resetAltLocked();
    bool configureClockLocked(const Uac20Params& params);
    bool setPlaybackAltLocked();
    void releaseInterfacesLocked();
    bool prepareFeedbackLocked(const Uac20Params& params);
    bool prepareOutTransfersLocked();
    bool submitFeedbackFirstLocked();
    bool startEventLoopLocked();
    bool submitOutTransfersLocked();
    void captureOutTransferPlanLocked();
    bool prepareDebugRealOutSubmitterLocked(const char* source);
    bool startDebugRealOutSubmitterLocked(const char* source);
    void captureRealOutSubmitterLocked();
    bool buildRawStreamBridgeLocked(const char* source);
    bool configureRawStreamDryRunLocked(const char* source);
    int shadowWriteRawStreamDryRunLocked(const uint8_t* data, int bytes, int legacyAcceptedBytes);
    void captureRawStreamBuildLocked();
    void captureRawStreamDryRunLocked();
    void resetRawStreamShadowWriteLocked();
    bool rawStreamTakeoverEnabledLocked() const;
    bool rawStreamTakeoverSinkReadyLocked() const;
    bool startRawStreamRealOutTakeoverLocked(const char* source);
    int writeRawStreamRealOutTakeoverLocked(const uint8_t* data, int bytes);
    void fallbackRawStreamRealOutTakeoverLocked(const char* reason);
    void setError(const char* message);

private:
    int fd_ = -1;
    libusb_context* usbContext_ = nullptr;
    libusb_device_handle* deviceHandle_ = nullptr;
    Uac20DescriptorSnapshot descriptorSnapshot_{};
    Uac20AltSnapshot selectedAlt_{};
    RawUac20FormatMatchResult formatMatch_{};
    Uac20OutTransferPlan outTransferPlan_{};
    Uac20EventLoop eventLoop_{};
    Uac20PersistentFeedbackTransfer feedbackTransfer_{};
    Uac20SilentOutSubmitProbe outProbe_{};
    Uac20PcmAdapter pcmAdapter_{};
    RawPcmPipeline pcmPipeline_{};
    Uac20RecoveryAttemptTracker recoveryAttemptTracker_{};
    Uac20RecoveryCandidatePlanner recoveryCandidatePlanner_{};
    Uac20RecoveryExecutionPlanSnapshot recoveryExecutionPlan_{};
    Uac20SessionPhaseTracker phaseTracker_{};
    Uac20RealOutRing realOutRing_{};
    Uac20ShadowToRealOutFeeder outFeeder_{};
    Uac20PacketScheduler packetScheduler_{};
    Uac20RealOutSubmitter realOutSubmitter_{};
    RawUac20StreamBuildResult rawStreamBuild_{};
    RawUac20Stream rawStreamDryRun_{};
    std::vector<uint8_t> pcmAdapterBuffer_{};
    std::vector<uint8_t> pcmPipelineBuffer_{};
    Uac20WriteRing writeRing_{};
    Uac20Params params_{};
    Uac20RuntimeState state_{};
};

} // namespace rawsmusic::usb
