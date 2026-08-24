#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <atomic>
#include <thread>
#include <mutex>
#include <shared_mutex>
#include <condition_variable>
#include <vector>
#include <set>
#include <string>
#include <cerrno>
#include <unistd.h>
#include <fcntl.h>
#include <cerrno>
#include <cmath>
#include <sys/resource.h>
#include <pthread.h>
#include <sched.h>
#include <sys/syscall.h>
#include <unordered_set>
#include <unordered_map>
#include <new>
#include "raw_usb_crash_guard.h"
#include <chrono>
#include <algorithm>  // for std::clamp
#include <climits>
#include <sys/epoll.h>
#include <poll.h>
#include <sys/prctl.h>
#include <memory>
#include <limits>
#include <sstream>
#include <queue>

#include "libusb.h"
#include "pcm_to_dsd_converter.h"
#include "raw_usb_background_scheduler.h"
#include "raw_usb_audio_schedule_bootstrap.h"
#include "raw_usb_native_guardian.h"
#include "raw_usb_submit_scheduler.h"
#include "raw_usb_session_state.h"
#include "raw_usb_session_lifecycle.h"
#include "raw_usb_session_request.h"
#include "raw_usb_event_owner.h"
#include "raw_usb_submit_owner.h"
#include "raw_usb_feedback.h"
#include "raw_usb_clock_model.h"
#include "raw_usb_feature_unit_model.h"
#include "raw_usb_dsd_model.h"
#include "raw_usb_pcm_adapter_model.h"
#include "raw_usb_pcm_transport_codec.h"
#include "raw_usb_format_trace.h"
#include "raw_usb_pcm_ring_writer.h"
#include "raw_usb_handle_token_table.h"
#include "raw_usb_handle_registry_state.h"
#include "raw_usb_quarantine_reaper.h"
#include "raw_usb_stats_formatter.h"
#include "raw_usb_audible_state_formatter.h"
#include "raw_usb_volume_policy_formatter.h"
#include "raw_usb_device_policy.h"
#include "raw_usb_protocol_utils.h"
#include "raw_usb_profile_risk.h"
#include "raw_usb_audio_stream_candidate.h"
#include "raw_usb_service_interval_math.h"
#include "raw_usb_dsd_math.h"
#include "raw_usb_dsd_diagnostics.h"
#include "raw_usb_dsd_session.h"
#include "raw_usb_dsd_route.h"
#include "raw_usb_dsd_worker.h"
#include "raw_usb_dsd_pcm_writer.h"
#include "raw_usb_dsd_transport_codec.h"
#include "raw_usb_dsd_raw_writer.h"
#include "raw_usb_dsd_transport_policy.h"
#include "raw_usb_ac_topology.h"
#include "raw_usb_standard_control_probe.h"
#include "raw_usb_standard_control_write.h"
#include "raw_usb_vendor_control_inventory.h"
#include "raw_usb_vendor_control_transport.h"
#include "raw_usb_pcm_sample_codec.h"
#include "raw_usb_feature_unit_control.h"
#include "raw_usb_transfer_status.h"
#include "raw_usb_transport_model.h"
#include "raw_usb_audio_gain.h"
#include "raw_audio_safety.h"
#include "raw_usb_spsc_ring.h"
#include "raw_usb_transfer_geometry.h"
#include "raw_usb_transfer_pool_policy.h"
#include "raw_usb_session_volume_envelope.h"
#include "raw_usb_transition_gain_owner.h"
#include "raw_usb_swr_context.h"
#include "raw_usb_iso_packet_policy.h"
#include "raw_usb_iso_pacer_policy.h"
#include "raw_usb_iso_runtime_model.h"
#include "raw_usb_runtime_format_model.h"
#include "raw_usb_pcm_output_mode.h"
#include "raw_usb_dsd_write_plan.h"
#include "raw_usb_hardware_volume_policy.h"
#include "raw_usb_capabilities_formatter.h"
#include "raw_usb_stop_fade.h"
#include "raw_usb_playback_mode.h"
#include "raw_usb_stats_counter.h"
#include "raw_usb_pending_counter.h"
#include "raw_usb_submit_policy.h"
#include "raw_usb_progressive_pool_policy.h"

using rawsmusic::usb::UsbDevicePolicy;
using rawsmusic::usb::getPolicyForDevice;
using rawsmusic::usb::kDefaultDevicePolicy;
using rawsmusic::usb::bitDepthToAvFormat;
using rawsmusic::usb::DsdTransportPlan;
using rawsmusic::usb::dsdTransportKindName;
using rawsmusic::usb::dsdTransportLocksPcmSampleRate;
using rawsmusic::usb::makeDsdTransportPlan;
using rawsmusic::usb::RawUsbDsdWorkerCallbacks;
using rawsmusic::usb::RawUsbDsdWorkerConfig;
using rawsmusic::usb::clearDsdPreferenceDiagnostics;
using rawsmusic::usb::consumeFirstDsdWriteDump;
using rawsmusic::usb::nextDopHandleLogCount;
using rawsmusic::usb::nextDsdWriteCount;
using rawsmusic::usb::resetDsdDiagnostics;
using rawsmusic::usb::resetRawUsbDsdConverter;
using rawsmusic::usb::RawUsbDsdRouteConfig;
using rawsmusic::usb::configureRawUsbDsdRoute;
using rawsmusic::usb::ceilDivU64;
using rawsmusic::usb::fallbackSubslotBytesForBits;
using rawsmusic::usb::computeUsbBytesPerSecond;
using rawsmusic::usb::readS24LE;
using rawsmusic::usb::writeS24LE;
using rawsmusic::usb::getFeatureUnitVolume;
using rawsmusic::usb::isUsbTransferCancelled;
using rawsmusic::usb::isUsbTransferCompleted;
using rawsmusic::usb::isFeedbackTransferFailureStatus;
using rawsmusic::usb::isIsoTransportLossStatus;
using rawsmusic::usb::isIsoPacketSuccessfulStatus;
using rawsmusic::usb::isIsoPacketFailureStatus;
using rawsmusic::usb::advanceIsoPacerPacketBytes;
using rawsmusic::usb::applyVolumeS16LE;
using rawsmusic::usb::applyVolumeS16LEFloat;
using rawsmusic::usb::applyVolumeS24LE;
using rawsmusic::usb::applyVolumeS32LE;
using rawsmusic::usb::applyFadeInS16LE;
using rawsmusic::usb::applyFadeInS24LE;
using rawsmusic::usb::applyFadeInS32LE;
using rawsmusic::usb::spscRingAvailable;
using rawsmusic::usb::spscRingRead;
using rawsmusic::usb::UsbTransferGeometryInput;
using rawsmusic::usb::computeUsbTransferGeometry;
using rawsmusic::usb::UsbTransferPoolPolicyInput;
using rawsmusic::usb::computeTransferPoolCap;
using rawsmusic::usb::SessionVolumeEnvelopeState;
using rawsmusic::usb::advanceSessionVolumeEnvelope;
using rawsmusic::usb::UsbTransitionGainOwner;
using rawsmusic::usb::sanitizeTransitionGainOwner;
using rawsmusic::usb::transitionGainOwnerName;
using rawsmusic::usb::transitionOwnerUsesLegacyStartupFade;
using rawsmusic::usb::transitionOwnerUsesSessionPcm;
using rawsmusic::usb::UsbSwrContextSpec;
using rawsmusic::usb::createUsbSwrContext;
using rawsmusic::usb::makeIsoPacketPolicyInput;
using rawsmusic::usb::computeDescriptorIsoServiceIntervals;
using rawsmusic::usb::computeNominalIsoPacketCeilBytes;
using rawsmusic::usb::UsbIsoPacerPacketInput;
using rawsmusic::usb::normalizeIsoPacerPacket;
using rawsmusic::usb::UsbIsoRuntimeInput;
using rawsmusic::usb::buildIsoRuntimeSnapshot;
using rawsmusic::usb::synchronizeRuntimeFormat;
using rawsmusic::usb::UsbPcmOutputMode;
using rawsmusic::usb::sanitizeUsbPcmOutputMode;
using rawsmusic::usb::isExplicitPcmMode;
using rawsmusic::usb::usbPcmOutputModeName;
using rawsmusic::usb::candidateMatchesUserPcmMode;
using rawsmusic::usb::PcmToDsdWritePlan;
using rawsmusic::usb::makePcmToDsdWritePlan;
using rawsmusic::usb::kHardwareVolumeMinDb;
using rawsmusic::usb::kHardwareVolumeMaxDb;
using rawsmusic::usb::kHardwareVolumeStepDb;
using rawsmusic::usb::kHardwareVolumeMinRaw;
using rawsmusic::usb::kHardwareVolumeMaxRaw;
using rawsmusic::usb::kHardwareVolumeStepRaw;
using rawsmusic::usb::sanitizeVolumeMinRaw;
using rawsmusic::usb::sanitizeVolumeMaxRaw;
using rawsmusic::usb::hardwareVolumeDbToRaw;
using rawsmusic::usb::shouldPreserveFeatureUnitControllerOnDisable;
using rawsmusic::usb::UsbCapabilitiesDevice;
using rawsmusic::usb::UsbCapabilitiesActiveFormat;
using rawsmusic::usb::buildUsbCapabilitiesJson;
using rawsmusic::usb::buildUsbCapabilitiesJsonForActiveFormat;
using rawsmusic::usb::UsbStopFadeState;
using rawsmusic::usb::applyUsbStopFade;
using rawsmusic::usb::knownDeviceClockRate;
using rawsmusic::usb::UsbPlaybackMode;
using rawsmusic::usb::UsbPlaybackPathInput;
using rawsmusic::usb::recordIsoSubmitStats;
using rawsmusic::usb::decrementPendingCounter;
using rawsmusic::usb::UsbSubmitEligibilityInput;
using rawsmusic::usb::canSubmitUsbJob;
using rawsmusic::usb::UsbProgressivePoolInput;
using rawsmusic::usb::UsbSessionRequest;
using rawsmusic::usb::parseUsbSessionRequest;
using rawsmusic::usb::shouldEnqueueProgressiveTransfer;
using rawsmusic::usb::updateAtomicMax;
using rawsmusic::usb::sanitizeHardwareVolumeRange;
using rawsmusic::usb::readU16Le;
using rawsmusic::usb::readU24Le;
using rawsmusic::usb::syncTypeName;
using rawsmusic::usb::usageTypeName;
using rawsmusic::usb::PROFILE_RISK_ASYNC_WITHOUT_FEEDBACK;
using rawsmusic::usb::PROFILE_RISK_CLOCK_UNVERIFIED;
using rawsmusic::usb::PROFILE_RISK_FEEDBACK_NONSTANDARD;
using rawsmusic::usb::PROFILE_RISK_LOW_CAPACITY;
using rawsmusic::usb::PROFILE_RISK_NONE;
using rawsmusic::usb::PROFILE_RISK_RATE_NOT_DECLARED;
using rawsmusic::usb::PROFILE_RISK_UNKNOWN_SYNC;
using rawsmusic::usb::streamProfileRiskToString;
#include "usb_hid.h"
#include "usb_hardware_volume.h"

extern "C" {
#include <libswresample/swresample.h>
#include <libavutil/opt.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
}

#define TAG "UsbAudioEngine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

// 前向声明
struct UsbAudioContext;

using rawsmusic::usb::UsbFeedbackState;
using rawsmusic::usb::feedbackStateName;
using rawsmusic::usb::readLittleEndianFeedbackRaw;
using rawsmusic::usb::kFeedbackEmptyThreshold;
using rawsmusic::usb::kFeedbackTransferBufferBytes;
using rawsmusic::usb::kFeedbackMinPacketBytes;
using rawsmusic::usb::kFeedbackStartupGraceMs;
using rawsmusic::usb::feedbackInvalidCountShouldDegrade;
using rawsmusic::usb::feedbackValidCountCanLock;
using rawsmusic::usb::feedbackRateWithinTolerance;
using rawsmusic::usb::smoothFeedbackBytesPerSecond;
using rawsmusic::usb::feedbackSampleRateWithinNominal;
using rawsmusic::usb::feedbackStartupGraceActive;
using rawsmusic::usb::decideUsbPlaybackMode;
using rawsmusic::usb::RawUsbClockRuntime;
using rawsmusic::usb::uac2ControlPresent;
using rawsmusic::usb::uac2ControlReadable;
using rawsmusic::usb::uac2ControlWritable;
using rawsmusic::usb::almostSameRate;
using rawsmusic::usb::RawUsbFeatureUnitRuntime;
using rawsmusic::usb::FeatureUnitPolicyState;
using rawsmusic::usb::featureUnitPolicyStateName;
using rawsmusic::usb::RawUsbDsdRuntime;
using rawsmusic::usb::PcmFormatAdapter;
using rawsmusic::usb::choosePcmAdapter;
using rawsmusic::usb::pcmAdapterName;
using rawsmusic::usb::PcmDecoderFormat;
using rawsmusic::usb::decoderPcmFormatForTargetBitDepth;
using rawsmusic::usb::isSupportedPcmAdapter;
using rawsmusic::usb::scorePcmAdapter;
using rawsmusic::usb::convertPcmToUsbDeviceFormat;
using rawsmusic::usb::writePcmFramesToRing;
using rawsmusic::usb::PCM_ADAPTER_NONE;
using rawsmusic::usb::PCM_ADAPTER_S16_TO_S24;
using rawsmusic::usb::PCM_ADAPTER_S16_TO_S32;
using rawsmusic::usb::PCM_ADAPTER_S24_TO_S32;
using rawsmusic::usb::PCM_ADAPTER_S32_TO_S24;
using rawsmusic::usb::PCM_ADAPTER_S32_TO_S24_IN_S32;
using rawsmusic::usb::PCM_ADAPTER_UNSUPPORTED;
using rawsmusic::usb::RawUsbHandleTokenTable;
using rawsmusic::usb::RawUsbHandleRegistryState;
using rawsmusic::usb::IsoPacer;
using rawsmusic::usb::UsbEndpointRuntime;
using rawsmusic::usb::UsbRuntimeFormat;
using rawsmusic::usb::UsbTransportRuntime;
using rawsmusic::usb::UsbPacingMode;
using rawsmusic::usb::pacingModeName;

// ==========================
// Handle registry to prevent double-free
// ==========================
static RawUsbHandleRegistryState gHandleRegistry;
static auto& gRegistryMtx = gHandleRegistry.mutex;
static std::shared_mutex gUsbLifecycleMtx;

// Last completed first-PCM container diagnostic. It intentionally survives
// native handle close so an in-app USB DAC report exported after stopping
// playback can still contain the evidence from the just-finished session.
// A fresh nativeInitUsbDevice clears it before a new session is attempted.
static std::mutex gLastPcmInputDiagMtx;
static bool gLastPcmInputDiagReady = false;
static rawsmusic::usb::RawUsbStatsSnapshot gLastPcmInputDiagSnapshot;
// A quarantined context still owns kernel-visible USB resources or a worker.
// It intentionally blocks in-process reopen until process restart or a future
// reaper proves all callbacks have returned.
// Java/Kotlin sees only monotonically increasing opaque tokens. Never expose a
// native pointer as jlong: a stale pointer can pass validation after allocator
// address reuse and then operate on a different USB session (ABA).
static auto& gLiveHandles = gHandleRegistry.liveHandles;

// JNI entry points that only need the current session use the registry
// selector instead of depending on the underlying container type.
static UsbAudioContext* firstLiveHandleNoLock();

static jlong registerHandle(UsbAudioContext* h) {
    if (!h) return 0;
    const uint64_t token = gHandleRegistry.nextToken();
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    gHandleRegistry.insertLiveHandleNoLock(h);
    gHandleRegistry.bindTokenNoLock(token, h);
    LOGI("registerHandle: token=%llu ctx=%p live count=%zu",
         static_cast<unsigned long long>(token), h, gHandleRegistry.liveHandleCountNoLock());
    return static_cast<jlong>(token);
}

static UsbAudioContext* resolveLiveHandle(jlong handleToken) {
    if (handleToken <= 0) return nullptr;
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    return gHandleRegistry.resolveTokenNoLock(static_cast<uint64_t>(handleToken));
}

static bool isLiveHandle(UsbAudioContext* h) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    return gHandleRegistry.containsLiveHandleNoLock(h);
}

static bool isQuarantinedToken(jlong handleToken) {
    if (handleToken <= 0) return false;
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    return gHandleRegistry.isQuarantinedTokenNoLock(static_cast<uint64_t>(handleToken));
}

static void invalidatePublicTokenForContextLocked(UsbAudioContext* h) {
    gHandleRegistry.invalidatePublicTokenForContextNoLock(h);
}

static void removeTokenForContextLocked(UsbAudioContext* h, bool quarantined) {
    gHandleRegistry.removeTokenForContextNoLock(h, quarantined);
}

static void quarantineHandle(UsbAudioContext* h, const char* reason) {
    if (!h) return;
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    gHandleRegistry.eraseLiveHandleNoLock(h);
    removeTokenForContextLocked(h, true);
    gHandleRegistry.insertQuarantinedHandleNoLock(h);
    LOGE("USB session quarantined: ctx=%p reason=%s quarantinedCount=%zu",
         h, reason ? reason : "unknown", gHandleRegistry.quarantinedHandleCountNoLock());
}

static bool hasQuarantinedHandles() {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    return gHandleRegistry.quarantinedHandleCountNoLock() != 0;
}

// A quarantine flag is a safety state, not proof that a kernel callback is
// still alive. Once all transfer counters are zero, cleanupUsbHandle() can
// safely stop/join the event owner and release the context. Keep contexts with
// outstanding callbacks quarantined; only reap the drained ones.
static size_t reapSafeQuarantinedHandles();

// 稳定优先：低延迟 ISO transfer 池
static constexpr int NUM_TRANSFERS = 256;            // UAC20-tested upper bound; time-sized pool picks the live subset.
static constexpr int USB_STARTUP_FADE_MS = 30;        // longer ramp prevents click/pop on fast track cutover
// Hardware Feature Unit volume is initialized before ISO starts and remains
// unchanged across track/seek/pause/resume boundaries. Startup protection after
// that point is software/session-envelope only.
static constexpr int STARVATION_EMPTY_XFER_THRESHOLD = 3;
static constexpr int STARVATION_RECOVERY_MS = 25;

// 启动音量保护窗口


static constexpr int64_t USB_STARTUP_GUARD_MS = 350;
static constexpr float USB_STARTUP_GUARD_CAP = 0.25f;

// 默认使用轻量安全核心：USB 独占路径保持精简、可预测。
// 默认关闭 HID、DSD 与设备 VID 特调；软件音量使用
// native 会话音量包络，而不是 Kotlin 侧分步 sleep/ramp。
static constexpr bool RAWS_USB_SAFE_CORE = true;
static constexpr bool RAWS_USB_HID_ENABLED_DEFAULT = false;
static constexpr bool RAWS_USB_DSD_ENABLED_DEFAULT = true;
static constexpr int USB_SESSION_DEFAULT_FADE_MS = 80;

static int64_t nowSteadyMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

static int64_t nowSteadyNs() {
    using namespace std::chrono;
    return duration_cast<nanoseconds>(steady_clock::now().time_since_epoch()).count();
}
// Native ring stores final USB device-format bytes after resampling / DoP / format adaptation.
// Keep the hard writer backpressure close to the Kotlin 160ms high-water target so seek/cutover
// cannot accumulate seconds of stale audio even when the source and device byte rates differ.
static constexpr int USB_WRITE_SOFT_LIMIT_MS = 760;
static constexpr int DSD_WRITE_SOFT_LIMIT_MS = 900;
// Keep a deep decoder/output buffer ahead of the small kernel URB pool.
// In background mode keep a bounded 2.5 s native PCM bridge. The current 760 ms
// ring plus ~0.5 s kernel queue explains why a 1.5 s scheduler gap can recover
// once while longer gaps become silence. RT event ownership remains the primary
// fix; this bridge covers short OEM thaw/freeze edges without filling the whole
// 8 s ring or pushing UI position several seconds ahead.
static constexpr int USB_BACKGROUND_WRITE_SOFT_LIMIT_MS = 2500;
static constexpr int USB_TRANSITION_SILENCE_MS = 160;
// Manual same-profile track changes need only a short transport-domain guard. Unlike
// seek, the next decoder is already READY; keeping this separate avoids turning an
// inaudible anti-pop boundary into a noticeable 160 ms track-change gap.
static constexpr int USB_TRACK_SWITCH_SILENCE_MS = 24;
#define ERR_NOT_INITIALIZED   -1001
#define ERR_NOT_RUNNING       -1003
#define ERR_TRANSPORT_LOST    -1004
#define ERR_USB_IO            -1005
#define ERR_START_FAILED      -1010

// ==========================
// USB Audio Protocol
// ==========================
// ==========================
// USB 播放模式
// ==========================

static UsbStreamState getUsbStreamState(UsbAudioContext* ctx);
static void setUsbStreamState(UsbAudioContext* ctx, UsbStreamState state, const char* reason);

static std::atomic<int> g_usbPcmOutputMode{0};

static UsbPcmOutputMode currentUsbPcmOutputMode() {
    return sanitizeUsbPcmOutputMode(
            g_usbPcmOutputMode.load(std::memory_order_acquire));
}

struct UsbAudioContext : RawUsbSessionLifecycle, RawUsbFeatureUnitRuntime, RawUsbDsdRuntime {
    std::mutex handleMutex;  // per-handle lock: protect close/start/stop from concurrent access
    libusb_context *libusbCtx = nullptr;
    libusb_device_handle *devHandle = nullptr;

    int interfaceNumber = 1;
    int altSetting = 1;
    uint8_t epAddress = 0x01;

    uint16_t vendorId = 0;    // USB VID，用于已知设备时快速查找
    std::string deviceName;   // 产品名称
    std::string capabilitiesJson; // 设备能力 JSON，供 UI 动态显示
    uint16_t productId = 0;   // USB PID

    int sampleRate = 44100;
    int channels = 2;
    int bitDepth = 16;
    int bytesPerSample = 2;
    int bytesPerFrame = 4;

    int maxPacketSize = 0;
    int uFrameSize = 0;
    bool isFullSpeed = true;

    // ISO 端点 bInterval


    int endpointInterval = 1;           // 默认 1ms (Full-Speed) / 125us (High-Speed bInterval=1)

    int serviceIntervalsPerSecond = 1000; // 每秒服务次数
    int bytesPerServiceInterval = 0;  // 每次服务应传输的字节数

    // ISO Pacer: frame accumulator for dynamic packet length
    IsoPacer isoPacer;

    // ISO 包大小不超过 maxPacketSize


    int bytesPerPacket = 0;      // 每个微帧平均字节数 (e.g. 24 for 48kHz/stereo/16bit)
    int transferSize = 0;        // 单次 transfer 的最大字节数（按 maxPktPerPacket 计算）

    // Bytes-per-second tracking (for feedback and stats)
    uint64_t bytes_per_second = 0;      // sampleRate * frameSize (e.g. 192000 for 48k/16bit/stereo)
    // Feedback smoothed rate (updated by feedbackCallback, read by stats)
    std::atomic<uint64_t> bytes_per_second_smoothed{0};
    // feedback 为空时按固定 pacer 继续运行

    std::atomic<int> feedbackEmptyCount{0};
    std::atomic<int64_t> feedbackStartupGraceUntilMs{0};
    std::atomic<bool> feedbackAudioGateHolding{false};
    std::atomic<bool> feedbackAudioGateReleaseLogged{false};

    // 异步传输
    struct libusb_transfer *transfers[NUM_TRANSFERS] = {};
    uint8_t *transferBuffers[NUM_TRANSFERS] = {};
    int numIsoPackets = 8;

    // Session gates are owned by RawUsbSessionLifecycle.
    bool asInterfaceClaimed = false;
    int claimedAsInterface = -1;
    int selectedAsInterface = -1;
    int selectedAltSetting = 0;
    int selectedOutEndpoint = 0;
    int selectedFeedbackEndpoint = 0;
    int currentSampleRate = 0;
    int currentChannels = 0;
    int currentBits = 0;
    int currentSubslotSize = 0;
    // acceptingWrites and sessionBroken are part of the lifecycle owner.
    std::atomic<int> pendingTransfers{0};   // submit++ / done--  (ISO OUT)
    std::atomic<int> activeTransferCount{0}; // submitted transfer pool size
    std::atomic<int> nextPoolIndex{0};       // retained for diagnostics/compatibility
    int transferPoolTarget = 0;              // fixed for the live stream; computed from queue time
    std::atomic<int> pendingFeedbackTransfers{0}; // feedback transfer pending count
    std::atomic<float> softwareVolume{1.0f};  // per-handle nativeSetVolume 音量


    // 每个会话独立维护 native 音量包络。等价于会话级
    // setSessionVolumeScale(linear, fadeMs)：切换边界淡入/淡出在
    // audio thread 内完成，不依赖 Kotlin 侧 sleep 循环。
    std::atomic<float> sessionVolumeCurrent{1.0f};
    std::atomic<float> sessionVolumeTarget{1.0f};
    std::atomic<int> sessionVolumeFadeRemainingFrames{0};
    std::atomic<int> sessionVolumeFadeTotalFrames{0};
    // One and only one transition-gain owner per live USB session. Legacy is retained only
    // for old callers; the current Kotlin controller configures this before nativeStart.
    std::atomic<int> transitionGainOwner{
            static_cast<int>(UsbTransitionGainOwner::Legacy)};
    std::atomic<int64_t> startupVolumeGuardUntilMs{0}; // 启动音量保护截止时间


    rawsmusic::usb::RawUsbEventOwner eventOwner;

    // Phase 9A32 transport ownership: the libusb event callback owns steady-state
    // ISO refill + resubmit, matching the UAC20 v2 path that sustained full-rate
    // playback on the same devices. RawUsbSubmitOwner remains as a lifecycle gate
    // and short mutex for stop/close + callback-owned resubmit serialization; warm
    // track boundaries are now requested by the feeder but applied by isoCallback.
    // No dedicated submit worker runs in the production steady state.
    rawsmusic::usb::RawUsbSubmitOwner submitOwner;
    std::atomic<int> eventEpollFd{-1};
    std::unique_ptr<rawsmusic::usb::UsbNativeBackgroundGuardian> backgroundGuardian;

    // ISO transfer user data（在 callback 中使用自己的 ctx，避免切栈时误用 g_usbCtx）
    struct IsoUserData {
        UsbAudioContext* ctx = nullptr;
        int index = 0;
    };
    IsoUserData isoUserData[NUM_TRANSFERS];

    // 环形缓冲区位置

    std::vector<uint8_t> pcmRingBuffer;
    std::atomic<size_t> pcmWritePos{0};
    std::atomic<size_t> pcmReadPos{0};

    // UAPP-style warm track boundary. A feeder may request the cut, but only the
    // libusb ISO completion owner is allowed to mutate the live ring/pacer boundary.
    // This prevents a Kotlin/nativeWrite thread from resetting transport-visible
    // state while a completion callback is simultaneously refilling/resubmitting an URB.
    std::atomic<uint64_t> trackBoundaryRequestedSeq{0};
    std::atomic<uint64_t> trackBoundaryAppliedSeq{0};
    std::mutex trackBoundaryMutex;
    std::condition_variable trackBoundaryCV;

    //

    struct libusb_transfer *feedbackTransfer = nullptr;
    uint8_t *feedbackBuffer = nullptr;
    uint8_t feedbackEpAddress = 0;
    std::atomic<int> dacSampleRate{48000};
    std::atomic<double> feedbackRate{1.0};

    // PID 由 Java 层传入/匹配


    std::atomic<int> writeThrottleUs{5000}; // 默认 5ms


    // 停止同步
    std::mutex stopMutex;
    std::condition_variable stopCV;

    // 等待 pending transfer callback


    std::mutex closeMutex;
    std::condition_variable closeCV;

    // ringMutex 已移除，SPSC 场景使用原子读写位置

    // 生产者(nativeWrite) → pcmWritePos (release)
    // 消费者(isoCallback/fillIsoTransfer) → pcmWritePos (acquire)
    // 写指针与读指针通过 acquire/release 同步



    // 统计口径：App 输入字节、已提交 ISO 字节、已完成 ISO 字节三者分开记录。
    std::atomic<int64_t> statsAppBytes{0};
    std::atomic<int64_t> statsScheduledUsbBytes{0};   // bytes placed into submitted ISO transfers
    std::atomic<int64_t> statsCompletedUsbBytes{0};   // bytes reported completed by libusb callbacks
    std::atomic<int64_t> statsUsbBytes{0};            // legacy alias; keep completed bytes for old readers
    // 每个 handle 的单调完成字节计数，用于 pause/seek/next 的安全边界判断。
    // Window stats are reset once per second; this counter lets pause/seek/next
    // wait until at least one ISO completion has crossed the final safe output.
    std::atomic<int64_t> statsTotalCompletedUsbBytes{0};
    std::atomic<int> statsUnderrun{0};
    std::atomic<int> statsCallbackCount{0};
    std::atomic<int> statsPacketCount{0};             // completed ISO packets in the stats window
    std::atomic<int> statsSubmitError{0};
    std::atomic<int> statsPacketError{0};
    std::atomic<int> statsXferError{0};

    // Last elapsed-time-normalized stats snapshot for Kotlin self-test/reporting.
    std::atomic<int64_t> statsWindowStartMs{0};
    std::atomic<int64_t> lastAppBytesPerSec{0};
    std::atomic<int64_t> lastScheduledUsbBytesPerSec{0};
    std::atomic<int64_t> lastCompletedUsbBytesPerSec{0};
    std::atomic<int> lastUnderrun{0};
    std::atomic<int> lastSubmitError{0};
    std::atomic<int> lastPacketError{0};
    std::atomic<int> lastXferError{0};
    std::atomic<int> lastPacketCount{0};
    std::atomic<int> lastCallbackCount{0};

    // ISO black-box diagnostics. These distinguish "app did not feed",
    // "libusb submit succeeded but completions stopped", and "callbacks return
    // zero/errored packets" on ROMs with different USB host scheduling.
    std::atomic<int64_t> isoSubmittedTransfers{0};
    std::atomic<int64_t> isoCompletedTransfers{0};
    std::atomic<int64_t> isoSubmittedBytes{0};
    std::atomic<int64_t> isoActualLengthBytes{0};
    std::atomic<int> isoMaxInFlightTransfers{0};
    std::atomic<int> isoZeroActualPackets{0};
    std::atomic<int> isoCompletedStatusPackets{0};
    std::atomic<int> isoErroredStatusPackets{0};
    std::atomic<int> isoCancelledStatusPackets{0};
    std::atomic<int> isoOtherStatusPackets{0};
    std::atomic<int64_t> isoLastCallbackMs{0};
    std::atomic<int> isoMaxCallbackGapMs{0};
    std::atomic<int64_t> isoCallbackGapTotalMs{0};
    std::atomic<int> isoCallbackGapCount{0};
    std::atomic<int> resetAltAttempts{0};
    std::atomic<int> resetAltLastResult{0};
    std::atomic<int> resetAltSelectedLastResult{0};
    std::atomic<int> silentProbeAttempted{0};
    std::atomic<int> silentProbeSubmitResult{0};
    std::atomic<int> silentProbeTransferStatus{0};
    std::atomic<int> silentProbeCompleted{0};
    std::atomic<int> silentProbeActualLength{0};
    std::atomic<int> silentProbeScheduledLength{0};
    std::atomic<int> silentProbeZeroActualPackets{0};
    std::atomic<int> silentProbePacketErrors{0};

    // 自适应 service interval 修复：部分 UAC 设备虽然声明为
    // high-speed async OUT endpoint but complete transfers as if the endpoint
    // were effectively serviced at a slower interval. When feedback is absent
    // or degraded, fixed 125us pacing can under-fill every microframe and make
    // playback crawl while the DAC LED still follows clock changes. This flag
    // limits automatic repair to one measured correction per stream start.
    std::atomic<bool> serviceIntervalAutoRepairDone{false};
    std::atomic<bool> serviceIntervalMeasuredRepairActive{false};
    // Foreground/background transitions on some Android builds can stall the
    // libusb event thread for several seconds.  Treat the following stats window
    // as a scheduler gap, not as evidence that the USB endpoint physically
    // switched from 125us microframes to 1ms frames.
    std::atomic<int64_t> lastEventLoopGapMs{0};
    std::atomic<int64_t> lastEventLoopGapDurationMs{0};

    std::atomic<uint64_t> streamSessionId{0};

    // 可听启动门控：生命周期上区分 nativeStart 与
    // "the DAC has consumed bytes and the volume route is restored".
    std::atomic<int64_t> audibleStartMs{0};
    std::atomic<int64_t> audibleFirstCompletionMs{0};
    std::atomic<int64_t> audibleAcceptedMs{0};
    std::atomic<int64_t> audibleAcceptedSessionId{0};
    std::atomic<int64_t> audibleAcceptedCompletedBytes{0};
    std::atomic<bool> audibleAccepted{false};

    // Starvation recovery
    bool starved = false;
    int starvedRecoveryBytes = 0; // starvation recovery buffer bytes


    int consecutiveEmptyTransfers = 0;
    bool dopOutputMarkerStart = true;
    // 启动淡入状态


    int fadeSamplesRemaining = 0;   // 淡入剩余样本数


    int fadeTotalSamples = 0;       // 本次淡入总样本数（用于计算渐入比例）
    bool startupSilenceDone = false; // 启动静音是否已完成



    // flush 时补 0 PCM


    std::atomic<bool> stopFadeActive{false};
    std::atomic<int> stopFadeSamplesRemaining{0};
    std::atomic<int> stopFadeTotalSamples{0};

    // Ownership tracking
    bool claimedInJava = false;
    bool claimDoneByNative = false;
    bool acInterfaceClaimed = false;
    int acInterfaceNumber = -1;
    int javaFd = -1;
    int dupFd = -1;

    // USB Audio protocol & topology info (set during nativeInit from bestCandidate)
    UsbAudioProtocol protocol = USB_AUDIO_UNKNOWN;
    uint8_t terminalLink = 0;
    // Read-only Hardware Device Control probe owns a stable copy of the parsed
    // AudioControl graph for the lifetime of this native USB session.
    AcTopology controlTopology;
    RawUsbClockRuntime clock;
    uint8_t playbackFeatureUnitId = 0;
    uint8_t playbackFeatureAcInterface = 0;

    // 播放策略快照。Transactional init supplies one immutable request; runtime USB code
    // must not re-read process-global next-session flags.
    UsbSessionRequest sessionRequest{};
    bool usbExclusiveActive = false;
    bool bitPerfectEnabled = false;
    bool hardwareFeatureUnitRequested = false;  // 用户请求使用硬件 Feature Unit


    // 最终计算出的播放模式
    UsbPlaybackMode playbackMode = UsbPlaybackMode::SafeSoftwareVolume;
    // 老字段兼容（部分代码仍引用）
    bool exclusiveActive = false;               // = usbExclusiveActive
    bool hardwareFeatureUnitEnabled = false;    // = hardwareVolumeEnabled
    bool featureUnitAvailable = false;          // = featureUnitPresent && hardwareVolumeCapable
    bool featureUnitHasMasterVolume = false;    // = hasMasterVolume
    bool masterChannelExists = false;           // master 通道物理存在 (GET_RANGE 成功)，但可能不可控
    float volumeMinDb = 0.0f;
    float volumeMaxDb = 0.0f;

    // Feature Unit writes are never part of startup or transport-boundary
    // state. The one device-scoped initialization write is owned by Kotlin
    // before nativeStart, and later changes come only from explicit user input.
    // During soft seek / warm next-track switch we keep ISO streaming and
    // intentionally feed a short silence window while the decoder/ring catches
    // up.  These zeros are not underruns and must not arm starvation clicks.
    std::atomic<int> transitionSilenceBytesRemaining{0};
    // UsbDevicePolicy 在 nativeInit 时拷贝


    bool policyForceNoControlIface = false;  // 跳过 AC interface claim
    bool policyForceSoftwareVolume = false;  // 强制软件音量

    bool policySkipClockConfig = false;      // 跳过 UAC2 SET_CUR 时钟配置


    bool policyIgnoreClockControl = false;   // 忽略 SET_CUR/GET_CUR/GET_RANGE 时钟控制


    bool policyIgnoreFeedbackEndpoint = false;
    // 运行时模型：设备格式只以 runtimeFormat 为单一事实来源
    UsbRuntimeFormat runtimeFormat;
    UsbTransportRuntime transportRuntime;
    bool feedbackDegraded = false;           // feedback endpoint has been dropped; fixed pacer remains active
    std::atomic<int> feedbackState{static_cast<int>(UsbFeedbackState::NONE)};
    std::atomic<int> pacingMode{static_cast<int>(UsbPacingMode::NoFeedbackFixed)};
    std::atomic<int> feedbackValidCount{0};
    std::atomic<int> feedbackInvalidCount{0};
    std::atomic<int> feedbackLastReason{0};
    std::atomic<int> feedbackSampleRateMilli{0};

    // Java 输入 PCM 格式
    int sourceSampleRate = 0;
    int sourceChannels = 0;
    int sourceBitDepth = 0;
    int sourceBytesPerSample = 0;
    int sourceBytesPerFrame = 0;
    // One-shot per-session PCM container diagnostic. Unlike the legacy global
    // DSD dump flag this is armed for every fresh USB handle, so UAC1 S32->S24
    // alignment can be verified from a normal PCM playback report.
    std::atomic<bool> firstPcmContainerDiagPending{true};
    // Cached copy of the first normal PCM container diagnostic. The logcat line is
    // still useful while developing, but these fields are persisted on the live
    // USB handle so the in-app USB DAC report can export the same evidence without ADB.
    // Non-atomic payload fields are published once via pcmInputDiagReady release/acquire.
    std::atomic<bool> pcmInputDiagReady{false};
    int pcmInputDiagProtocol = 0;
    int pcmInputDiagSourceFrame = 0;
    int pcmInputDiagDeviceFrame = 0;
    int pcmInputDiagAdapter = 0;
    bool pcmInputDiagNeedsResample = false;
    int pcmInputDiagSamples = 0;
    int pcmInputDiagNonSilent = 0;
    int pcmInputDiagLowZero = 0;
    int pcmInputDiagSignExtendedTop = 0;
    char pcmInputDiagFirst16Hex[33] = {0};

    // USB 设备输出格式


    int deviceChannels = 0;
    int deviceBitDepth = 0;
    int deviceSubslotSize = 0;
    int deviceBytesPerSample = 0;
    int deviceBytesPerFrame = 0;
    // 当前 PCM 适配方式
    PcmFormatAdapter pcmAdapter = PCM_ADAPTER_NONE;

    // 重采样上下文 (libswresample)
    SwrContext *swrCtx = nullptr;           // 重采样上下文，nullptr 表示不需要重采样

    bool needsResample = false;             // 源采样率 != 设备采样率

    // 重采样输出缓冲区

    std::vector<uint8_t> swrOutBuffer;      // 重采样输出临时缓冲区

    size_t swrOutBufferSize = 0;            // 当前分配的缓冲区大小（字节）


    // USB transport fatal fast-fail (NO_DEVICE / ERROR_IO / etc.)
    // quarantine 标志：context 因 pending transfer 未回调而被隔离
    // 隔离后不 free / 不 close / 不 delete，泄漏一个 context 避免 UAF

    // 预缓冲阈值（字节）

    size_t prebufferBytes = 0;

    // ===== PI 自适应速率控制 =====

    // 当 feedback 端点不可用时，基于缓冲区水位趋势自动微调发送速率

    // 核心原理：缓冲区在缩小（消费 > 发送）时，增加发送速率（反之亦然）

    struct AdaptiveRateController {
        bool active = false;            // feedback 失效后自动启动

        double targetFillRatio = 0.85;  // 目标缓冲区填充率 85%（增大以减少抖动）

        double Kp = 0.0004;             // 比例增益：原 0.0002 太保守，修正量不足

        double Ki = 0.00004;            // 积分增益：原 0.00002 太保守，无法累积足够修正

        double deadZone = 0.08;        // 死区：填充率偏差 < 2% 时不修正

        double integralError = 0.0;    // 累积误差（积分项）

        double maxCorrection = 0.0005;  // 最大修正幅度 ±0.5%（原 0.1% 不足以驱动缓冲区）

        double correction = 0.0;       // 当前修正因子（供 log 用）

        size_t prevBufUsed = 0;        // 上一秒的缓冲区水位

        int stableCount = 0;           // 连续稳定计数（水位变化 < 1%）

    } adaptiveRate;

    // 标称采样率（整数，由 nativeInit 设置，PI 控制器不修改此值）

    uint32_t nominalSampleRate = 44100;

    // 热插拔回调句柄

    libusb_hotplug_callback_handle hotplugHandle = 0;
    bool hotplugRegistered = false;

    // ===== USB HID Remote Control =====
    bool hidEnabled = false;                    // HID support enabled for this device
    bool hidListening = false;                  // Currently listening for HID events
    uint8_t hidInterfaceNumber = 0;             // HID interface number
    uint8_t hidEndpointAddress = 0;             // HID endpoint address
    uint16_t hidMaxPacketSize = 0;              // HID max packet size
    uint8_t hidInterval = 0;                    // HID polling interval
    std::thread hidReadThread;                  // HID read thread
    std::atomic<bool> hidShouldStop{false};     // Signal to stop HID thread
    std::mutex hidCallbackMutex;                // Protect HID callback
    bool hidInterfaceClaimed = false;           // HID interface claimed state
    std::unique_ptr<rawsmusic::UsbHidManager> hidManager;
};

// ==========================
// UsbStreamState helpers (implementation after struct)
// ==========================
static UsbStreamState getUsbStreamState(UsbAudioContext* ctx) {
    if (!ctx) return UsbStreamState::CLOSED;
    return ctx->sessionState.load(std::memory_order_acquire);
}

static void setUsbStreamState(UsbAudioContext* ctx, UsbStreamState state, const char* reason) {
    if (!ctx) return;
    ctx->sessionState.store(state, std::memory_order_release);
    LOGI("USB stream state -> %s, reason=%s", usbStreamStateName(state), reason ? reason : "");
}

// g_usbCtx 已移除：所有访问都通过 handle + gLiveHandles registry


// ==========================
// 软件音量控制 (fixed-point Q15)

// ==========================
static std::atomic<float> gSoftwareVolume{1.0f};

// Serializes next-session policy mutation with native USB init. Transactional init holds this
// recursive mutex while committing one immutable request and creating the session.
static std::recursive_mutex gNextSessionPolicyMtx;

// 最后一次请求的硬件 raw 音量，nativeStart 用于恢复


static std::atomic<int16_t> g_lastRequestedHardwareVolumeRaw{0};
static std::atomic<bool> g_hasLastRequestedHardwareVolumeRaw{false};

// ==========================
// 全局撤销策略开关（用户策略，非运行时实例状态）

// ==========================
// Bit-perfect 模式

static std::atomic<bool> g_bitPerfectEnabled{false};
// 当前 App 是否已经独占 USB 设备。没有独占时，不允许多种完美比特

static std::atomic<bool> g_usbExclusiveActive{false};
static std::atomic<bool> g_usbBackgroundPlaybackActive{false};
    // Android fast-mixer profile supplied by AudioManager. The probe passes the system
// output sample rate and frames-per-buffer into its short-lived OpenSL probe;
// using the USB stream rate (for example 384 kHz) does not reliably create a
// FIFO callback on OEM devices.
static std::atomic<int> g_androidSchedulerSampleRate{48000};
static std::atomic<int> g_androidSchedulerFramesPerBuffer{192};
static std::atomic<bool> g_keepAliveEventPumpBusy{false};
static std::atomic<int64_t> g_lastKeepAliveEventPumpLogMs{0};
// 软件音量控制（Feature Unit）请求：

// 默认 false。表示用户策略请求软件音量，不代表设备真的安全

// 实际软件生效取决于 init 时的验证结果

static std::atomic<bool> g_hardwareFeatureUnitRequested{false};
// 运行时切换策略后，请求上层重 init

static std::atomic<bool> g_requiresReinit{false};
// bit-perfect 固定音量确认标志

static std::atomic<bool> g_bitPerfectFixedVolumeAcknowledged{false};

// ==========================
// USB DAC 高级设置（通用）

// ==========================
// 跳过 AudioControl interface（不操作 Feature Unit）

static std::atomic<bool> g_usbNoControlInterface{false};
// 强制 UAC1 协议（绕过 UAC2 时钟控制问题）

static std::atomic<bool> g_usbForceUac1{false};
// 线性音量曲线（避免对数曲线的精度损失）

static std::atomic<bool> g_usbLinearVolume{false};
// 用硬件音量替代软件音量

static std::atomic<bool> g_usbReplaceVolume{false};
// 强制 1ms 包间隔

static std::atomic<bool> g_usbForce1MsPacket{false};

// ==========================
// PCM→DSD 转换（基于本地转换流程）

// ==========================
static std::atomic<bool> g_dsdConversionEnabled{false};
static std::atomic<int> g_dsdRate{64};           // 64/128/256/512
static std::atomic<int> g_dsdConversionType{0};  // 0=Standard, 1=HighQuality, 2=LowLatency
static std::atomic<bool> g_dsdDitherEnabled{false};
static std::atomic<bool> g_dsdDopEnabled{false};
// Live PCM->DSD converter state is intentionally not global.  These atomics are
// preferences for the next USB session only; UsbAudioContext owns the active
// stereo converter, FIR/CIFB history, source queue and transport scratch.


static inline bool isRawDsdInputActive(const UsbAudioContext* ctx) {
    return ctx != nullptr &&
           ctx->sourceDsdSession &&
           ctx->sourceBitDepth == 1 &&
           ctx->sourceChannels > 0;
}

static JavaVM* g_hidJavaVm = nullptr;
static jobject g_hidCallback = nullptr;
static std::mutex g_hidCallbackMutex;

static void dispatchHidKeyEventToJava(const rawsmusic::HidKeyEvent& event) {
    JavaVM* vm = g_hidJavaVm;
    if (!vm) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    jint getEnv = vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (getEnv == JNI_EDETACHED) {
        if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK || !env) {
            LOGW("HID callback: AttachCurrentThread failed");
            return;
        }
        attached = true;
    } else if (getEnv != JNI_OK || !env) {
        return;
    }

    jobject callback = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_hidCallbackMutex);
        if (g_hidCallback) {
            callback = env->NewLocalRef(g_hidCallback);
        }
    }
    if (!callback) {
        if (attached) vm->DetachCurrentThread();
        return;
    }

    jclass callbackClass = env->GetObjectClass(callback);
    if (callbackClass) {
        jmethodID method = env->GetMethodID(callbackClass, "onHidKeyEvent", "(IZ)V");
        if (method) {
            env->CallVoidMethod(
                    callback,
                    method,
                    static_cast<jint>(static_cast<uint8_t>(event.key)),
                    event.pressed ? JNI_TRUE : JNI_FALSE
            );
            if (env->ExceptionCheck()) {
                env->ExceptionDescribe();
                env->ExceptionClear();
            }
        } else {
            LOGW("HID callback: onHidKeyEvent(IZ)V not found");
        }
        env->DeleteLocalRef(callbackClass);
    }
    env->DeleteLocalRef(callback);

    if (attached) {
        vm->DetachCurrentThread();
    }
}

static void stopHidLocked(UsbAudioContext* ctx) {
    if (!ctx || !ctx->hidManager) return;
    ctx->hidManager->stopListening();
    ctx->hidListening = false;
}

static void releaseAudioControlInterface(UsbAudioContext* ctx) {
    if (!ctx || !ctx->devHandle || !ctx->acInterfaceClaimed) return;
    if (ctx->acInterfaceNumber < 0) {
        LOGW("release AC interface skipped: unknown iface");
        ctx->acInterfaceClaimed = false;
        return;
    }
    int r = libusb_release_interface(ctx->devHandle, ctx->acInterfaceNumber);
    if (r == LIBUSB_SUCCESS) {
        LOGI("release AC interface ok: iface=%d", ctx->acInterfaceNumber);
    } else {
        LOGW("release AC interface failed: iface=%d err=%s",
             ctx->acInterfaceNumber, libusb_error_name(r));
    }
    ctx->acInterfaceClaimed = false;
}

static void detachAllExistingInterfaces(UsbAudioContext* ctx, libusb_device* dev) {
    if (!ctx || !ctx->devHandle || !dev) return;
    LOGI("detachAllExistingInterfaces skipped: Android UsbDeviceConnection owns kernel driver state");
    return;

    libusb_config_descriptor* activeConfig = nullptr;
    int r = libusb_get_active_config_descriptor(dev, &activeConfig);
    if (r != LIBUSB_SUCCESS || !activeConfig) {
        LOGW("detachAllExistingInterfaces: get_active_config_descriptor failed: %s",
             libusb_error_name(r));
        return;
    }

    std::unordered_set<int> visitedIfaces;
    for (int i = 0; i < activeConfig->bNumInterfaces; ++i) {
        const libusb_interface& ifaceGroup = activeConfig->interface[i];
        for (int j = 0; j < ifaceGroup.num_altsetting; ++j) {
            const libusb_interface_descriptor& alt = ifaceGroup.altsetting[j];
            const int ifaceNo = alt.bInterfaceNumber;
            if (!visitedIfaces.insert(ifaceNo).second) continue;

            int active = libusb_kernel_driver_active(ctx->devHandle, ifaceNo);
            if (active == 1) {
                int dr = libusb_detach_kernel_driver(ctx->devHandle, ifaceNo);
                if (dr == LIBUSB_SUCCESS) {
                    LOGI("detachAllExistingInterfaces: detached kernel driver iface=%d", ifaceNo);
                } else if (dr == LIBUSB_ERROR_NOT_FOUND) {
                    LOGI("detachAllExistingInterfaces: iface=%d had no attached kernel driver", ifaceNo);
                } else if (dr == LIBUSB_ERROR_NOT_SUPPORTED) {
                    LOGI("detachAllExistingInterfaces: detach not supported for iface=%d", ifaceNo);
                } else {
                    LOGW("detachAllExistingInterfaces: detach iface=%d failed: %s",
                         ifaceNo, libusb_error_name(dr));
                }
            } else if (active == 0) {
                LOGI("detachAllExistingInterfaces: iface=%d already detached", ifaceNo);
            } else if (active == LIBUSB_ERROR_NOT_SUPPORTED) {
                LOGI("detachAllExistingInterfaces: kernel_driver_active not supported for iface=%d", ifaceNo);
            } else {
                LOGW("detachAllExistingInterfaces: kernel_driver_active iface=%d => %s",
                     ifaceNo, libusb_error_name(active));
            }
        }
    }

    libusb_free_config_descriptor(activeConfig);
}

static constexpr uint8_t DOP_MARKER_A = 0x05;
static constexpr uint8_t DOP_MARKER_B = 0xFA;
static constexpr uint32_t UAC2_FORMAT_TYPE_I_RAW_DATA = 0x80000000u;

// 软件音量安全验证结果缓存

static void syncUsbRuntimeModel(UsbAudioContext* ctx);

static bool applyMeasuredServiceIntervalRepair(UsbAudioContext* ctx,
                                               int repairedIps,
                                               const char* reason) {
    if (!ctx || repairedIps <= 0) return false;
    repairedIps = std::clamp(repairedIps, 1000, 8000);
    if (ctx->serviceIntervalsPerSecond == repairedIps) return false;

    const int oldIps = ctx->serviceIntervalsPerSecond;
    const int frameSize = std::max(1, ctx->bytesPerFrame);
    const int maxPayload = ctx->maxPacketSize > 0
                           ? (ctx->maxPacketSize / frameSize) * frameSize
                           : INT32_MAX;
    int repairedBytesPerPacket = (int)(ctx->bytes_per_second / std::max(1, repairedIps));
    if (repairedBytesPerPacket <= 0) repairedBytesPerPacket = frameSize;
    repairedBytesPerPacket = ((repairedBytesPerPacket + frameSize - 1) / frameSize) * frameSize;
    if (repairedBytesPerPacket > maxPayload) {
        LOGW("USB service interval auto-repair skipped: reason=%s ips %d->%d wouldNeed=%d maxPkt=%d expectedBps=%llu",
             reason ? reason : "unknown",
             oldIps, repairedIps, repairedBytesPerPacket, ctx->maxPacketSize,
             (unsigned long long)ctx->bytes_per_second);
        return false;
    }
    ctx->serviceIntervalsPerSecond = repairedIps;
    ctx->isoPacer.reset(
            (double)ctx->nominalSampleRate,
            (uint32_t)repairedIps,
            (uint32_t)frameSize,
            ctx->maxPacketSize
    );
    ctx->bytesPerPacket = (int)(ctx->bytes_per_second / std::max(1u, ctx->isoPacer.intervalsPerSec));
    if (ctx->bytesPerPacket <= 0) ctx->bytesPerPacket = frameSize;
    ctx->bytesPerServiceInterval = ctx->bytesPerPacket;
    ctx->serviceIntervalMeasuredRepairActive.store(true, std::memory_order_release);
    syncUsbRuntimeModel(ctx);

    LOGW("USB service interval auto-repair: reason=%s ips %d->%d bytesPerPacket=%d expectedBps=%llu",
         reason ? reason : "unknown",
         oldIps, repairedIps, ctx->bytesPerPacket,
         (unsigned long long)ctx->bytes_per_second);
    return true;
}

static bool suppressMeasuredServiceIntervalRepair(UsbAudioContext* ctx,
                                                  int64_t nowMs,
                                                  int64_t statsElapsedMs,
                                                  double appInBytesPerSec,
                                                  uint64_t expectedBps,
                                                  const char** outReason) {
    if (outReason) *outReason = nullptr;
    if (!ctx) return true;

    if (statsElapsedMs > 1500) {
        if (outReason) *outReason = "stats_window_gap";
        return true;
    }

    const int64_t lastGapMs = ctx->lastEventLoopGapMs.load(std::memory_order_acquire);
    if (lastGapMs > 0 && nowMs >= lastGapMs && nowMs - lastGapMs < 5000) {
        if (outReason) *outReason = "recent_event_loop_gap";
        return true;
    }

    if (ctx->starved || ctx->starvedRecoveryBytes > 0 || ctx->fadeSamplesRemaining > 0) {
        if (outReason) *outReason = "starvation_recovery_active";
        return true;
    }

    // Under-fill while the app/decoder thread itself is paused is not a USB
    // service-interval signal. This is common when bringing the app foreground
    // on MIUI/HyperOS: the event thread resumes with a large elapsed window,
    // then stats report a low packets/sec value. Do not rewrite the ISO pacer.
    if (expectedBps > 0 && appInBytesPerSec < (double)expectedBps * 0.75) {
        if (outReason) *outReason = "app_input_under_rate";
        return true;
    }

    // The endpoint descriptor is the physical cadence source of truth. Callback
    // batching can make packets/sec look like 1000/s after a scheduler gap even
    // though libusb still schedules HS bInterval=1 packet descriptors at 8000/s.
    // Keep measured repair disabled unless a future device-specific quirk proves
    // that a descriptor is wrong.
    if (outReason) *outReason = "descriptor_cadence_locked";
    return true;
}

static void noteSuppressedMeasuredServiceIntervalRepair(UsbAudioContext* ctx,
                                                        const char* phase,
                                                        const char* suppressReason,
                                                        const char* repairReason,
                                                        int currentIps,
                                                        int repairedIps,
                                                        int64_t elapsedMs,
                                                        double appBps,
                                                        double completedBps,
                                                        int packetsPerSec,
                                                        uint64_t expectedBps) {
    if (!ctx || repairedIps <= 0) return;
    LOGW("USB service interval auto-repair suppressed: phase=%s suppress=%s measured=%s ips %d->%d elapsed=%lldms app=%.0f completed=%.0f packets=%d expected=%llu; keep descriptor cadence",
         phase ? phase : "stats",
         suppressReason ? suppressReason : "unknown",
         repairReason ? repairReason : "unknown",
         currentIps, repairedIps, (long long)elapsedMs, appBps, completedBps,
         packetsPerSec, (unsigned long long)expectedBps);
    ctx->serviceIntervalAutoRepairDone.store(true, std::memory_order_release);
}

static std::atomic<bool> g_hardwareVolumeValidated{false};
static std::atomic<bool> g_policyNoClockSet{false};
static std::atomic<bool> g_policyNoFeedback{false};
static std::atomic<bool> g_policyNoFeatureUnit{false};
static std::atomic<bool> g_policyPreferSafeAlt{false};
static std::atomic<bool> g_policySafeMode{false};
static std::atomic<int> g_policyLastGoodAlt{0};
static std::atomic<int> g_policyLastGoodSampleRate{0};
static std::atomic<int> g_policyLastGoodValidBits{0};
static std::atomic<int> g_policyLastGoodSubslot{0};
static std::atomic<int> g_policyLastGoodFeedbackEp{0};


static void applyUsbSessionRequestToGlobals(const UsbSessionRequest& request) {
    const bool ex = request.exclusive;
    const bool bp = request.bitPerfect && ex;
    const bool hw = request.hardwareVolumeRequested && ex;
    const int pcmMode = static_cast<int>(sanitizeUsbPcmOutputMode(request.pcmOutputMode));

    int dsdType = request.dsdConversionType;
    bool dsdDither = request.dsdConversionEnabled && request.dsdDitherEnabled;
    if (request.dsdConversionEnabled) {
        // Keep identical runtime constraints to nativeSetDsdConversion().
        dsdType = static_cast<int>(rawsmusic::DsdConversionType::LowLatency);
        dsdDither = false;
    }

    g_usbExclusiveActive.store(ex, std::memory_order_release);
    g_bitPerfectEnabled.store(bp, std::memory_order_release);
    g_hardwareFeatureUnitRequested.store(hw, std::memory_order_release);
    g_usbPcmOutputMode.store(pcmMode, std::memory_order_release);

    g_dsdConversionEnabled.store(request.dsdConversionEnabled, std::memory_order_release);
    g_dsdRate.store(request.dsdRate, std::memory_order_release);
    g_dsdConversionType.store(dsdType, std::memory_order_release);
    g_dsdDitherEnabled.store(dsdDither, std::memory_order_release);
    g_dsdDopEnabled.store(request.dsdConversionEnabled && request.dsdDoPEnabled, std::memory_order_release);

    g_usbNoControlInterface.store(request.noControlInterface, std::memory_order_release);
    g_usbForceUac1.store(request.forceUac1, std::memory_order_release);
    g_usbLinearVolume.store(request.linearVolume, std::memory_order_release);
    g_usbReplaceVolume.store(request.replaceVolume, std::memory_order_release);
    g_usbForce1MsPacket.store(request.force1msPacket, std::memory_order_release);

    g_policyNoClockSet.store(request.noClockSet, std::memory_order_release);
    g_policyNoFeedback.store(request.noFeedback, std::memory_order_release);
    g_policyNoFeatureUnit.store(request.noFeatureUnit, std::memory_order_release);
    g_policyPreferSafeAlt.store(request.preferSafeAlt, std::memory_order_release);
    g_policySafeMode.store(request.safeMode, std::memory_order_release);
    g_policyLastGoodAlt.store(request.lastGoodAlt, std::memory_order_release);
    g_policyLastGoodSampleRate.store(request.lastGoodSampleRate, std::memory_order_release);
    g_policyLastGoodValidBits.store(request.lastGoodValidBits, std::memory_order_release);
    g_policyLastGoodSubslot.store(request.lastGoodSubslotBytes, std::memory_order_release);
    g_policyLastGoodFeedbackEp.store(request.lastGoodFeedbackEndpoint, std::memory_order_release);

    // Stays true until nativeInitUsbDevice reaches a successful commit. A failed transaction must
    // never look reusable to Kotlin.
    g_requiresReinit.store(true, std::memory_order_release);

    LOGI("USB_SESSION_TXN_COMMIT v=1 ex=%d bp=%d hw=%d pcm=%s dsd=%d/DSD%d/type%d/dop%d "
         "compat=noClock:%d noFb:%d noFU:%d safeAlt:%d safe:%d force1ms:%d "
         "lastGood=%d/%d/%d/%d/0x%02X",
         ex ? 1 : 0, bp ? 1 : 0, hw ? 1 : 0, usbPcmOutputModeName(static_cast<UsbPcmOutputMode>(pcmMode)),
         request.dsdConversionEnabled ? 1 : 0, request.dsdRate, dsdType,
         (request.dsdConversionEnabled && request.dsdDoPEnabled) ? 1 : 0,
         request.noClockSet ? 1 : 0, request.noFeedback ? 1 : 0, request.noFeatureUnit ? 1 : 0,
         request.preferSafeAlt ? 1 : 0, request.safeMode ? 1 : 0, request.force1msPacket ? 1 : 0,
         request.lastGoodAlt, request.lastGoodSampleRate, request.lastGoodValidBits,
         request.lastGoodSubslotBytes, request.lastGoodFeedbackEndpoint);
}
static UsbSessionRequest snapshotUsbSessionRequestFromGlobals() {
    UsbSessionRequest request{};
    request.exclusive = g_usbExclusiveActive.load(std::memory_order_acquire);
    request.bitPerfect = g_bitPerfectEnabled.load(std::memory_order_acquire) && request.exclusive;
    request.hardwareVolumeRequested =
            g_hardwareFeatureUnitRequested.load(std::memory_order_acquire) && request.exclusive;
    request.pcmOutputMode = static_cast<int>(currentUsbPcmOutputMode());
    request.dsdConversionEnabled = g_dsdConversionEnabled.load(std::memory_order_acquire);
    request.dsdRate = g_dsdRate.load(std::memory_order_acquire);
    request.dsdConversionType = g_dsdConversionType.load(std::memory_order_acquire);
    request.dsdDitherEnabled = g_dsdDitherEnabled.load(std::memory_order_acquire);
    request.dsdDoPEnabled = g_dsdDopEnabled.load(std::memory_order_acquire);
    request.noControlInterface = g_usbNoControlInterface.load(std::memory_order_acquire);
    request.forceUac1 = g_usbForceUac1.load(std::memory_order_acquire);
    request.linearVolume = g_usbLinearVolume.load(std::memory_order_acquire);
    request.replaceVolume = g_usbReplaceVolume.load(std::memory_order_acquire);
    request.force1msPacket = g_usbForce1MsPacket.load(std::memory_order_acquire);
    request.noClockSet = g_policyNoClockSet.load(std::memory_order_acquire);
    request.noFeedback = g_policyNoFeedback.load(std::memory_order_acquire);
    request.noFeatureUnit = g_policyNoFeatureUnit.load(std::memory_order_acquire);
    request.preferSafeAlt = g_policyPreferSafeAlt.load(std::memory_order_acquire);
    request.safeMode = g_policySafeMode.load(std::memory_order_acquire);
    request.lastGoodAlt = g_policyLastGoodAlt.load(std::memory_order_acquire);
    request.lastGoodSampleRate = g_policyLastGoodSampleRate.load(std::memory_order_acquire);
    request.lastGoodValidBits = g_policyLastGoodValidBits.load(std::memory_order_acquire);
    request.lastGoodSubslotBytes = g_policyLastGoodSubslot.load(std::memory_order_acquire);
    request.lastGoodFeedbackEndpoint = g_policyLastGoodFeedbackEp.load(std::memory_order_acquire);
    return request;
}

static std::atomic<bool> g_hardwareVolumeSafe{false};

static bool isStrictBitPerfectPcmPath(const UsbAudioContext* ctx) {
    if (!ctx) return false;
    return rawsmusic::usb::isStrictBitPerfectPcmPath(UsbPlaybackPathInput{
            ctx->playbackMode,
            ctx->usbExclusiveActive,
            ctx->bitPerfectEnabled,
            ctx->hardwareFeatureUnitRequested,
            ctx->hardwareVolumeEnabled,
            ctx->hardwareVolumeSafe,
    });
}

static bool isHardwareVolumePcmUnityPath(const UsbAudioContext* ctx) {
    if (!ctx) return false;
    return rawsmusic::usb::isHardwareVolumePcmUnityPath(UsbPlaybackPathInput{
            ctx->playbackMode,
            ctx->usbExclusiveActive,
            ctx->bitPerfectEnabled,
            ctx->hardwareFeatureUnitRequested,
            ctx->hardwareVolumeEnabled,
            ctx->hardwareVolumeSafe,
    });
}

static UsbTransitionGainOwner getTransitionGainOwner(const UsbAudioContext* ctx) {
    if (!ctx) return UsbTransitionGainOwner::Legacy;
    return sanitizeTransitionGainOwner(
            ctx->transitionGainOwner.load(std::memory_order_acquire));
}

static bool usesSessionPcmTransitionEnvelope(const UsbAudioContext* ctx) {
    if (!ctx) return false;
    return transitionOwnerUsesSessionPcm(
            getTransitionGainOwner(ctx),
            isStrictBitPerfectPcmPath(ctx),
            isHardwareVolumePcmUnityPath(ctx),
            ctx->dsdSession);
}

static void forceSessionEnvelopeUnity(UsbAudioContext* ctx) {
    if (!ctx) return;
    ctx->sessionVolumeTarget.store(1.0f, std::memory_order_release);
    ctx->sessionVolumeCurrent.store(1.0f, std::memory_order_release);
    ctx->sessionVolumeFadeRemainingFrames.store(0, std::memory_order_release);
    ctx->sessionVolumeFadeTotalFrames.store(0, std::memory_order_release);
}

static void armSessionEnvelopeInternal(UsbAudioContext* ctx, float target, int fadeMs) {
    if (!ctx) return;
    const float safeTarget = std::clamp(
            std::isfinite(target) ? target : 1.0f,
            0.0f,
            1.0f);
    if (!usesSessionPcmTransitionEnvelope(ctx)) {
        forceSessionEnvelopeUnity(ctx);
        return;
    }
    const int sr = ctx->sampleRate > 0 ? ctx->sampleRate : 44100;
    if (fadeMs <= 0) {
        ctx->sessionVolumeTarget.store(safeTarget, std::memory_order_release);
        ctx->sessionVolumeCurrent.store(safeTarget, std::memory_order_release);
        ctx->sessionVolumeFadeRemainingFrames.store(0, std::memory_order_release);
        ctx->sessionVolumeFadeTotalFrames.store(0, std::memory_order_release);
        return;
    }
    int fadeFrames = sr * fadeMs / 1000;
    if (fadeFrames < 1) fadeFrames = 1;
    ctx->sessionVolumeTarget.store(safeTarget, std::memory_order_release);
    ctx->sessionVolumeFadeRemainingFrames.store(fadeFrames, std::memory_order_release);
    ctx->sessionVolumeFadeTotalFrames.store(fadeFrames, std::memory_order_release);
}

static bool readHardwareCurrentRawForPath(UsbAudioContext* ctx, int16_t* outRaw);
static bool isAudibleVolumeRouteReady(const UsbAudioContext* ctx);
static void maybeMarkUsbAudibleAccepted(UsbAudioContext* ctx, const char* reason);

// 原生 session volume envelope: transitions happen in the audio
// thread so pause/resume/seek/track changes don't need Kotlin sleep loops.
static float advanceSessionEnvelope(UsbAudioContext* ctx, int framesHint) {
    if (!ctx) return 1.0f;
    if (!usesSessionPcmTransitionEnvelope(ctx)) {
        forceSessionEnvelopeUnity(ctx);
        return 1.0f;
    }

    const SessionVolumeEnvelopeState next = advanceSessionVolumeEnvelope(
            ctx->sessionVolumeCurrent.load(std::memory_order_relaxed),
            ctx->sessionVolumeTarget.load(std::memory_order_acquire),
            ctx->sessionVolumeFadeRemainingFrames.load(std::memory_order_acquire),
            framesHint);
    ctx->sessionVolumeFadeRemainingFrames.store(
            next.remainingFrames,
            std::memory_order_release);
    ctx->sessionVolumeCurrent.store(next.current, std::memory_order_release);
    return next.current;
}

// Startup guard only caps output; it does not overwrite the user's stored volume.
static float getEffectiveSoftwareVolume(UsbAudioContext* ctx, int framesHint = 0) {
    if (!ctx) return 1.0f;
    if (isStrictBitPerfectPcmPath(ctx)) {
        advanceSessionEnvelope(ctx, framesHint > 0 ? framesHint : 1);
        return 1.0f;
    }
    float vol = ctx->softwareVolume.load(std::memory_order_relaxed);
    vol = std::clamp(vol, 0.0f, 1.0f);
    const float session = advanceSessionEnvelope(ctx, framesHint > 0 ? framesHint : 1);
    vol *= session;
    const int64_t guardUntil = ctx->startupVolumeGuardUntilMs.load(std::memory_order_acquire);
    if (transitionOwnerUsesLegacyStartupFade(getTransitionGainOwner(ctx)) &&
        nowSteadyMs() < guardUntil) {
        return std::min(vol, USB_STARTUP_GUARD_CAP);
    }
    return vol;
}

// Forward declaration: applyStopFade (defined after fillIsoTransfer, used in it)
static void applyStopFade(UsbAudioContext* ctx, uint8_t* data, int bytes);

// ==========================
// 标准 USB 传输致命错误（统一入口）

// ==========================
static void markUsbTransportLost(
        UsbAudioContext *ctx,
        const char* where,
        int index,
        int code
) {
    if (!ctx) return;
    bool first = !ctx->transportLost.exchange(true, std::memory_order_acq_rel);
    ctx->streaming.store(false, std::memory_order_release);
    ctx->acceptingWrites.store(false, std::memory_order_release);
    ctx->stopRequested.store(true, std::memory_order_release);
    ctx->sessionBroken.store(true, std::memory_order_release);
    ctx->fatalError.store(ERR_TRANSPORT_LOST, std::memory_order_release);
    setUsbStreamState(ctx, UsbStreamState::BROKEN, "transport_lost");
    // 唤醒 write 等待（如果有）

    {
        std::lock_guard<std::mutex> lock(ctx->stopMutex);
        ctx->stopCV.notify_all();
    }
    if (first) {
        LOGE("USB TRANSPORT LOST at %s index=%d code=%d (%s)",
             where, index, code, libusb_error_name(code));
    }
}


// ==========================
// 环形缓冲区操作

// ==========================
static size_t ringAvailable(UsbAudioContext *ctx) {
    if (!ctx) return 0;
    return spscRingAvailable(ctx->pcmRingBuffer, ctx->pcmWritePos, ctx->pcmReadPos);
}

static int pumpUsbEventsFromAuxThread(
        UsbAudioContext* ctx,
        const char* reason,
        bool* obtainedEventLock = nullptr) {
    if (obtainedEventLock) *obtainedEventLock = false;
    if (!ctx ||
        !ctx->libusbCtx ||
        !ctx->streaming.load(std::memory_order_acquire) ||
        ctx->closing.load(std::memory_order_acquire) ||
        ctx->stopping.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire)) {
        return LIBUSB_ERROR_NO_DEVICE;
    }

    const int lockRc = libusb_try_lock_events(ctx->libusbCtx);
    if (lockRc != 0) {
        return LIBUSB_ERROR_BUSY;
    }

    int rc = LIBUSB_ERROR_BUSY;
    timeval tv{};
    tv.tv_sec = 0;
    tv.tv_usec = 0;
    if (libusb_event_handling_ok(ctx->libusbCtx)) {
        if (obtainedEventLock) *obtainedEventLock = true;
        rc = libusb_handle_events_locked(ctx->libusbCtx, &tv);
    } else {
        LOGW("USB aux event pump interrupted: reason=%s", reason ? reason : "unknown");
    }
    libusb_unlock_events(ctx->libusbCtx);
    return rc;
}

static bool backgroundGuardianSnapshot(
        void* opaque,
        rawsmusic::usb::UsbGuardianRuntimeSnapshot* out) {
    auto* ctx = reinterpret_cast<UsbAudioContext*>(opaque);
    if (!ctx || !out) return false;
    out->streamActive = ctx->streaming.load(std::memory_order_acquire);
    out->backgroundActive = g_usbBackgroundPlaybackActive.load(std::memory_order_acquire);
    out->exclusiveActive = g_usbExclusiveActive.load(std::memory_order_acquire);
    out->eventThreadRunning = ctx->eventThreadRunning.load(std::memory_order_acquire);
    out->transportLost = ctx->transportLost.load(std::memory_order_acquire);
    out->pendingTransfers = ctx->pendingTransfers.load(std::memory_order_acquire);
    out->streamSessionId = ctx->streamSessionId.load(std::memory_order_acquire);
    out->lastEventLoopGapMs = ctx->lastEventLoopGapMs.load(std::memory_order_acquire);
    out->lastEventLoopGapDurationMs = ctx->lastEventLoopGapDurationMs.load(std::memory_order_acquire);
    out->lastIsoCallbackMs = ctx->isoLastCallbackMs.load(std::memory_order_acquire);
    out->totalCompletedUsbBytes = ctx->statsTotalCompletedUsbBytes.load(std::memory_order_acquire);
    out->ringUsedBytes = ringAvailable(ctx);
    out->ringCapacityBytes = ctx->pcmRingBuffer.size();
    return ctx->libusbCtx != nullptr && !ctx->closing.load(std::memory_order_acquire);
}

static int backgroundGuardianPumpEvents(
        void* opaque,
        const char* reason,
        bool* obtainedEventLock) {
    auto* ctx = reinterpret_cast<UsbAudioContext*>(opaque);
    return pumpUsbEventsFromAuxThread(ctx, reason, obtainedEventLock);
}

static int backgroundGuardianRecoverTransfers(void* opaque, const char* reason);
static void LIBUSB_CALL isoCallback(struct libusb_transfer *xfer);
static bool resubmitIsoDirect(UsbAudioContext* ctx, struct libusb_transfer* xfer, int index);
static bool resubmitFeedbackDirect(UsbAudioContext* ctx);

static void recordIsoSubmitDiagnostics(UsbAudioContext* ctx, const libusb_transfer* xfer);

static size_t ringRead(UsbAudioContext *ctx, uint8_t *dst, size_t len) {
    if (!ctx) return 0;
    return spscRingRead(
            ctx->pcmRingBuffer,
            ctx->pcmReadPos,
            ctx->pcmWritePos,
            dst,
            len);
}

// ==========================
// fillIsoTransfer helper (forward declaration)
// ==========================
static bool isPcmToDsdDemandActive(const UsbAudioContext* ctx);
static void requestPcmToDsdDemand(UsbAudioContext* ctx, const char* reason);
static size_t enqueuePcmForDsdWorker(UsbAudioContext* ctx, const uint8_t* src, size_t bytes);
static void clearDsdPcmQueue(UsbAudioContext* ctx);
static void startDsdWorkerIfNeeded(UsbAudioContext* ctx, const char* reason);

static int descriptorIsoServiceIntervalsPerSecond(const UsbAudioContext* ctx) {
    if (!ctx) return 8000;
    const auto input = makeIsoPacketPolicyInput(
            ctx->isFullSpeed,
            ctx->endpointInterval,
            g_usbForce1MsPacket.load(std::memory_order_relaxed),
            ctx->deviceBytesPerFrame,
            ctx->bytesPerFrame,
            static_cast<int>(ctx->nominalSampleRate),
            ctx->sampleRate,
            ctx->maxPacketSize
    );
    return computeDescriptorIsoServiceIntervals(input);
}

static int nominalIsoPacketCeilBytesForIps(const UsbAudioContext* ctx, int ips) {
    if (!ctx) return 0;
    const auto input = makeIsoPacketPolicyInput(
            ctx->isFullSpeed,
            ctx->endpointInterval,
            g_usbForce1MsPacket.load(std::memory_order_relaxed),
            ctx->deviceBytesPerFrame,
            ctx->bytesPerFrame,
            static_cast<int>(ctx->nominalSampleRate),
            ctx->sampleRate,
            ctx->maxPacketSize
    );
    return computeNominalIsoPacketCeilBytes(input, ips);
}

static int nominalIsoPacketCeilBytes(const UsbAudioContext* ctx) {
    if (!ctx) return 0;
    // Default clamp uses the descriptor-derived service interval, not the mutable
    // runtime pacer. This protects against corrupted runtime state producing
    // endpoint-full packets after pause/resume.
    return nominalIsoPacketCeilBytesForIps(ctx, descriptorIsoServiceIntervalsPerSecond(ctx));
}

static int nextIsoPacketBytesForContext(UsbAudioContext* ctx, const char* reason) {
    if (!ctx) return 0;
    int bytes = advanceIsoPacerPacketBytes(&ctx->isoPacer);
    const int frameBytes = std::max(1, ctx->deviceBytesPerFrame > 0 ? ctx->deviceBytesPerFrame : ctx->bytesPerFrame);
    const int descriptorIps = descriptorIsoServiceIntervalsPerSecond(ctx);
    const int runtimeIps = std::max(1, ctx->serviceIntervalsPerSecond);
    int maxNominal = nominalIsoPacketCeilBytes(ctx);
    if (ctx->serviceIntervalMeasuredRepairActive.load(std::memory_order_acquire) &&
        runtimeIps < descriptorIps) {
        maxNominal = nominalIsoPacketCeilBytesForIps(ctx, runtimeIps);
    }
    const auto packetPolicy = normalizeIsoPacerPacket(UsbIsoPacerPacketInput{
            bytes,
            frameBytes,
            maxNominal,
    });
    if (packetPolicy.wasClamped) {
        static std::atomic<int> sClampLogBudget{24};
        int budget = sClampLogBudget.load(std::memory_order_relaxed);
        if (budget > 0 && sClampLogBudget.compare_exchange_strong(budget, budget - 1)) {
            LOGW("ISO pacer clamp: reason=%s pkt=%d -> %d sr=%u runtimeIps=%d descriptorIps=%d frame=%d maxPkt=%d expectedBps=%llu repaired=%d",
                 reason ? reason : "unknown", bytes, packetPolicy.packetBytes,
                 ctx->nominalSampleRate, runtimeIps, descriptorIps, frameBytes, ctx->maxPacketSize,
                 (unsigned long long)ctx->bytes_per_second,
                 ctx->serviceIntervalMeasuredRepairActive.load(std::memory_order_acquire) ? 1 : 0);
        }
        // Reset the accumulator so one corrupted/stale state cannot keep producing endpoint-full packets.
        ctx->isoPacer.accumulatorQ32 = 0;
    }
    return packetPolicy.packetBytes;
}

static void resetUsbIsoPacerToRuntime(UsbAudioContext* ctx, const char* reason) {
    if (!ctx) return;
    const auto runtime = buildIsoRuntimeSnapshot(UsbIsoRuntimeInput{
            ctx->isFullSpeed,
            static_cast<std::uint8_t>(ctx->endpointInterval),
            g_usbForce1MsPacket.load(std::memory_order_relaxed),
            ctx->deviceBytesPerFrame,
            ctx->bytesPerFrame,
            static_cast<int>(ctx->nominalSampleRate),
            ctx->sampleRate,
            ctx->maxPacketSize,
            ctx->clock.deviceSampleRate,
    });
    const int frameBytes = runtime.frameBytes;
    const int rate = runtime.sampleRate;
    const int ips = runtime.serviceIntervalsPerSecond;
    ctx->serviceIntervalsPerSecond = ips;
    ctx->bytesPerFrame = frameBytes;
    ctx->bytes_per_second = runtime.bytesPerSecond;
    ctx->clock.deviceBytesPerSecond = (int)ctx->bytes_per_second;
    ctx->serviceIntervalMeasuredRepairActive.store(false, std::memory_order_release);
    ctx->isoPacer.reset((double)rate, (uint32_t)ips, (uint32_t)frameBytes, ctx->maxPacketSize);
    ctx->nominalSampleRate = (uint32_t)rate;
    ctx->bytesPerPacket = runtime.bytesPerPacket;
    ctx->bytesPerServiceInterval = ctx->bytesPerPacket;
    syncUsbRuntimeModel(ctx);
    LOGI("USB ISO pacer reset: reason=%s sr=%d ips=%d frame=%d bytesPerPacket=%d expectedBps=%llu",
         reason ? reason : "unknown", rate, ips, frameBytes, ctx->bytesPerPacket,
         (unsigned long long)ctx->bytes_per_second);
}

static rawsmusic::usb::RawUac20QueueSizing computeCurrentUac20QueueSizing(
        const UsbAudioContext* ctx) {
    if (!ctx) return {};
    const int ips = std::max(1, ctx->serviceIntervalsPerSecond);
    const int packets = std::max(1, ctx->numIsoPackets);
    const int frameBytes = std::max(1, ctx->bytesPerFrame);
    const int64_t nominalNumerator =
            static_cast<int64_t>(std::max<uint64_t>(1, ctx->bytes_per_second)) * packets;
    int nominalTransferBytes = static_cast<int>((nominalNumerator + ips - 1) / ips);
    nominalTransferBytes = rawsmusic::usb::rawFrameAlignUp(nominalTransferBytes, frameBytes);
    auto policy = rawsmusic::usb::defaultRawAudioSafetyPolicy();
    policy.isoPacketsPerTransfer = packets;
    policy.maxTransfers = NUM_TRANSFERS;
    // A degraded/absent feedback endpoint uses the deeper no-feedback queue.
    const bool explicitFeedback = ctx->feedbackEpAddress != 0 && !ctx->feedbackDegraded;
    return rawsmusic::usb::rawComputeUac20QueueSizing(
            static_cast<int>(std::min<uint64_t>(ctx->bytes_per_second, INT_MAX)),
            nominalTransferBytes,
            frameBytes,
            explicitFeedback,
            policy);
}

static int currentTransferPoolCap(const UsbAudioContext* ctx) {
    if (!ctx) return NUM_TRANSFERS;
    if (ctx->transferPoolTarget > 0) {
        return std::clamp(ctx->transferPoolTarget, 1, NUM_TRANSFERS);
    }
    const auto sizing = computeCurrentUac20QueueSizing(ctx);
    return std::clamp(sizing.transferCount, 1, NUM_TRANSFERS);
}

static bool shouldHoldAudioForFeedback(const UsbAudioContext* ctx) {
    if (!ctx || ctx->feedbackEpAddress == 0 || ctx->feedbackDegraded) return false;
    const int state = ctx->feedbackState.load(std::memory_order_acquire);
    if (state == static_cast<int>(UsbFeedbackState::LOCKED) ||
        state == static_cast<int>(UsbFeedbackState::DEGRADED) ||
        state == static_cast<int>(UsbFeedbackState::FAILED)) {
        return false;
    }
    const int64_t untilMs = ctx->feedbackStartupGraceUntilMs.load(std::memory_order_acquire);
    return feedbackStartupGraceActive(nowSteadyMs(), untilMs);
}

// ==========================
// 填充 ISO 传输缓冲区并设置包长度

// ==========================
// 核心原则：

// - 每个 ISO packet 长度由相位累加器（帧级）动态计算（如 44.1kHz 的 5/6 帧交替）

// - buffer 按 i * maxPossiblePkt 排列（预留最大空间），但 iso_packet_desc[i].length = 实际长度

// - 从 ring buffer 读取实际长度的数据，不足补静音

// ==========================
// ==========================
// DoP 静默帧填充辅助函数

// ==========================
// 当 DoP 模式激活时，DAC 需要从第一帧就看到有效的 DoP marker 字节

// 才能识别流为 DoP 并切换到 DSD 模式。原始全零数据会让 DAC 认为是 PCM 静音

//
// DoP 静默帧格式（立体声，bytesPerFrame=6）：

//   [0x69, 0x69, marker, 0x69, 0x69, marker]
// marker 按帧位置交替：dopMarkerA / dopMarkerB

// DoP marker is always 0x05 / 0xFA; the carrier sample rate selects DSD64/128/256.
//
// markerStart 参数控制第一帧使用哪个 marker，后续交替取反

// 返回值：更新后的 markerStart（供下一次连续使用）

static void fillIsoTransfer(UsbAudioContext *ctx, struct libusb_transfer *xfer, int index) {
    xfer->buffer = ctx->transferBuffers[index];
    uint8_t *buf = xfer->buffer;
    const int numPkts = ctx->numIsoPackets;
    bool underrunThisRound = false;
    int totalRead = 0;
    int totalLen = 0;

    // One owner for the outgoing DoP marker phase. Ring writers never mutate
    // this state; this function advances it according to the exact number of
    // USB frames that will be submitted.
    const bool transferMarkerStart = ctx->dopOutputMarkerStart;
    bool outputMarkerCursor = transferMarkerStart;

    // DoP 模式检测：用于 starvation/underrun 时生成带有效 marker 的静默帧

    bool fillDopSilence = false;
    bool fillNativeDsdSilenceFrames = false;
    uint8_t fillMarkerA = DOP_MARKER_A, fillMarkerB = DOP_MARKER_B;

    {
        // SPSC 无锁：ringRead 内部用 release/acquire 保证数据可见


        // 检查 DoP 模式

        const bool dsdTransportActive = ctx->dsdSession;
        const bool pcmToDsdAsync = isPcmToDsdDemandActive(ctx);
        const bool dopActive = dsdTransportActive && ctx->dsdDopTransport;
        if (dopActive) {
            fillDopSilence = true;
            fillMarkerA = DOP_MARKER_A;
            fillMarkerB = DOP_MARKER_B;
            static std::atomic<bool> s_markerOwnerLogged{false};
            bool expected = false;
            if (s_markerOwnerLogged.compare_exchange_strong(
                    expected, true, std::memory_order_acq_rel)) {
                LOGI("DoP marker ownership: ISO output only; ring producers write payload placeholders");
            }
        } else if (dsdTransportActive) {
            fillNativeDsdSilenceFrames = true;
        }

        // Async DACs need OUT traffic before their feedback endpoint can lock,
        // but consuming song data during that validation window queues audio at
        // a pacing rate which may still be wrong. Keep the transport alive with
        // format-correct silence and leave the ring untouched until feedback is
        // locked or its bounded startup grace expires.
        if (shouldHoldAudioForFeedback(ctx)) {
            if (!ctx->feedbackAudioGateHolding.exchange(true, std::memory_order_acq_rel)) {
                ctx->feedbackAudioGateReleaseLogged.store(false, std::memory_order_release);
                LOGI("USB_FEEDBACK_AUDIO_GATE hold state=%s graceUntil=%lld ring=%zu/%zu",
                     feedbackStateName(ctx->feedbackState.load(std::memory_order_acquire)),
                     static_cast<long long>(ctx->feedbackStartupGraceUntilMs.load(std::memory_order_acquire)),
                     ringAvailable(ctx), ctx->pcmRingBuffer.size());
            }
            int offset = 0;
            for (int i = 0; i < numPkts; ++i) {
                const int pktBytes = nextIsoPacketBytesForContext(ctx, "feedback_startup_silence");
                if (fillDopSilence) {
                    outputMarkerCursor = fillDoPSilence(
                            buf + offset, pktBytes, ctx->bytesPerFrame,
                            ctx->deviceChannels, fillMarkerA, fillMarkerB,
                            outputMarkerCursor);
                } else if (fillNativeDsdSilenceFrames) {
                    outputMarkerCursor = fillNativeDsdSilence(
                            buf + offset, pktBytes, ctx->bytesPerFrame,
                            ctx->deviceChannels, ctx->deviceSubslotSize,
                            outputMarkerCursor);
                } else {
                    memset(buf + offset, 0, pktBytes);
                }
                xfer->iso_packet_desc[i].length = pktBytes;
                offset += pktBytes;
                totalLen += pktBytes;
            }
            xfer->length = totalLen;
            ctx->dopOutputMarkerStart = outputMarkerCursor;
            ctx->statsScheduledUsbBytes.fetch_add(totalLen, std::memory_order_relaxed);
            return;
        }
        if (ctx->feedbackAudioGateHolding.exchange(false, std::memory_order_acq_rel) &&
            !ctx->feedbackAudioGateReleaseLogged.exchange(true, std::memory_order_acq_rel)) {
            LOGI("USB_FEEDBACK_AUDIO_GATE release state=%s degraded=%d ring=%zu/%zu",
                 feedbackStateName(ctx->feedbackState.load(std::memory_order_acquire)),
                 ctx->feedbackDegraded ? 1 : 0,
                 ringAvailable(ctx), ctx->pcmRingBuffer.size());
        }

        // Warm seek / same-profile track switch transport barrier.
        //
        // The old code treated transitionSilenceBytesRemaining only as an underrun budget:
        // it generated silence *only if ringRead() had no data*. A READY next decoder can refill
        // the native ring immediately, so the intended guard was bypassed and old/new PCM met at
        // an arbitrary live ISO boundary. That is exactly the short click/electrical burst heard
        // during fast manual switching.
        //
        // Make the barrier authoritative for a whole USB transfer. While it is active we do not
        // consume the new PCM ring and we return before any PCM/session envelope is advanced.
        // Thus queued old audio drains in order, a format-correct silence transfer follows, and
        // only the next transfer may consume new-track PCM and start its fade-in.
        const int transitionRemain =
                ctx->transitionSilenceBytesRemaining.load(std::memory_order_acquire);
        if (transitionRemain > 0) {
            int offset = 0;
            for (int i = 0; i < numPkts; ++i) {
                const int pktBytes = nextIsoPacketBytesForContext(ctx, "transition_barrier_silence");
                if (fillDopSilence) {
                    outputMarkerCursor = fillDoPSilence(
                            buf + offset, pktBytes, ctx->bytesPerFrame,
                            ctx->deviceChannels, fillMarkerA, fillMarkerB,
                            outputMarkerCursor);
                } else if (fillNativeDsdSilenceFrames) {
                    outputMarkerCursor = fillNativeDsdSilence(
                            buf + offset, pktBytes, ctx->bytesPerFrame,
                            ctx->deviceChannels, ctx->deviceSubslotSize,
                            outputMarkerCursor);
                } else {
                    memset(buf + offset, 0, pktBytes);
                }
                xfer->iso_packet_desc[i].length = pktBytes;
                offset += pktBytes;
                totalLen += pktBytes;
            }
            xfer->length = totalLen;
            ctx->dopOutputMarkerStart = outputMarkerCursor;
            const int consume = std::min(totalLen, transitionRemain);
            ctx->transitionSilenceBytesRemaining.fetch_sub(consume, std::memory_order_acq_rel);
            ctx->consecutiveEmptyTransfers = 0;
            ctx->statsScheduledUsbBytes.fetch_add(totalLen, std::memory_order_relaxed);
            return;
        }

        // ===== STARVATION RECOVERY =====
        // PCM->DSD already has a valid transport-domain silence filler. Do not let the generic
        // PCM starvation latch turn a sub-millisecond producer miss into a forced 25 ms mute.
        // Continue consuming real DSD as soon as it returns and fill only the missing tail.
        const bool directDsdAsync = ctx->sourceDsdSession;
        if (ctx->starved && (pcmToDsdAsync || directDsdAsync)) {
            LOGW("DSD_TRANSPORT clearing generic starvation latch: direct=%d ring=%zu/%zu emptyTransfers=%d",
                 directDsdAsync ? 1 : 0,
                 ringAvailable(ctx), ctx->pcmRingBuffer.size(), ctx->consecutiveEmptyTransfers);
            ctx->starved = false;
            ctx->starvedRecoveryBytes = 0;
        }
        if (ctx->starved) {
            size_t bufUsed = ringAvailable(ctx);
            if (bufUsed >= (size_t)ctx->starvedRecoveryBytes) {
                ctx->starved = false;
                // 应用淡入，避免从静音突然跳到音量产生爆音

                // 淡入时长 = 5ms 的样本数（足够平滑，人耳不可感知）

                const UsbTransitionGainOwner gainOwner = getTransitionGainOwner(ctx);
                if (usesSessionPcmTransitionEnvelope(ctx)) {
                    ctx->sessionVolumeCurrent.store(0.0f, std::memory_order_release);
                    armSessionEnvelopeInternal(ctx, 1.0f, USB_STARTUP_FADE_MS);
                    ctx->fadeSamplesRemaining = 0;
                    ctx->fadeTotalSamples = 0;
                    LOGI("Recovered from starvation: bufUsed=%zu/%zu owner=SessionPcm fadeMs=%d",
                         bufUsed, ctx->pcmRingBuffer.size(), USB_STARTUP_FADE_MS);
                } else if (transitionOwnerUsesLegacyStartupFade(gainOwner) &&
                           !isHardwareVolumePcmUnityPath(ctx) &&
                           !isStrictBitPerfectPcmPath(ctx) &&
                           !ctx->dsdSession) {
                    int fadeSamples = (int)(ctx->sampleRate * USB_STARTUP_FADE_MS / 1000) * ctx->deviceChannels;
                    if (fadeSamples < 1) fadeSamples = 1;
                    ctx->fadeSamplesRemaining = fadeSamples;
                    ctx->fadeTotalSamples = fadeSamples;
                    LOGI("Recovered from starvation: bufUsed=%zu/%zu owner=Legacy fade-in %d samples",
                         bufUsed, ctx->pcmRingBuffer.size(), fadeSamples);
                } else {
                    forceSessionEnvelopeUnity(ctx);
                    ctx->fadeSamplesRemaining = 0;
                    ctx->fadeTotalSamples = 0;
                    LOGI("Recovered from starvation: bufUsed=%zu/%zu fade bypassed owner=%s",
                         bufUsed, ctx->pcmRingBuffer.size(), transitionGainOwnerName(gainOwner));
                }
            } else {
                // buffer 还没恢复，输出 DoP 静默帧（带有有效 marker）或者静音

                int offset = 0;
                for (int i = 0; i < numPkts; i++) {
                    int pktBytes = nextIsoPacketBytesForContext(ctx, "starvation_silence");
                    if (fillDopSilence) {
                        outputMarkerCursor = fillDoPSilence(
                                buf + offset, pktBytes, ctx->bytesPerFrame,
                                ctx->deviceChannels, fillMarkerA, fillMarkerB,
                                outputMarkerCursor);
                    } else if (fillNativeDsdSilenceFrames) {
                        outputMarkerCursor = fillNativeDsdSilence(
                                buf + offset, pktBytes, ctx->bytesPerFrame,
                                ctx->deviceChannels, ctx->deviceSubslotSize,
                                outputMarkerCursor);
                    } else {
                        memset(buf + offset, 0, pktBytes);
                    }
                    xfer->iso_packet_desc[i].length = pktBytes;
                    offset += pktBytes;
                    totalLen += pktBytes;
                }
                xfer->length = totalLen;
                ctx->dopOutputMarkerStart = outputMarkerCursor;
                ctx->statsScheduledUsbBytes.fetch_add(totalLen, std::memory_order_relaxed);
                return;
            }
        }

        // 每个 packet: 动态计算长度，从 ring buffer 读取，不足补静音

        // Ring buffer 存储的是 USB device 格式原始 PCM（不重量）

        int offset = 0;
        for (int i = 0; i < numPkts; i++) {
            int pktBytes = nextIsoPacketBytesForContext(ctx, "normal_pcm");
            uint8_t *pkt = buf + offset;
            bool packetDopMarkerStart = outputMarkerCursor;

            int got = (int)ringRead(ctx, pkt, pktBytes);
            if (got < pktBytes) {
                const int missing = pktBytes - got;
                // DoP 模式：用有效 marker 的静默帧填充 underrun 部分

                if (fillDopSilence) {
                    size_t gotAlignedDown = ((size_t)got / (size_t)ctx->bytesPerFrame) *
                                            (size_t)ctx->bytesPerFrame;
                    size_t remain = (size_t)pktBytes - gotAlignedDown;
                    fillDoPSilence(
                            pkt + gotAlignedDown, remain, ctx->bytesPerFrame,
                            ctx->deviceChannels, fillMarkerA, fillMarkerB,
                            packetDopMarkerStart);
                } else if (fillNativeDsdSilenceFrames) {
                    size_t gotAlignedDown = ((size_t)got / (size_t)ctx->bytesPerFrame) *
                                            (size_t)ctx->bytesPerFrame;
                    size_t remain = (size_t)pktBytes - gotAlignedDown;
                    fillNativeDsdSilence(
                            pkt + gotAlignedDown, remain, ctx->bytesPerFrame,
                            ctx->deviceChannels, ctx->deviceSubslotSize,
                            packetDopMarkerStart);
                } else {
                    memset(pkt + got, 0, missing);
                }
                underrunThisRound = true;
                ctx->statsUnderrun.fetch_add(1, std::memory_order_relaxed);
            }
            if (fillDopSilence) {
                outputMarkerCursor = stampDoPMarkers(
                        pkt, pktBytes, ctx->bytesPerFrame, ctx->deviceChannels,
                        fillMarkerA, fillMarkerB, packetDopMarkerStart);
            }

            xfer->iso_packet_desc[i].length = pktBytes;
            offset += pktBytes;
            totalLen += pktBytes;
            totalRead += got;
        }
    }

    xfer->length = totalLen;

    // Hardware volume safeStartup must be brief.  A valid stream may begin
    // with digital silence, so the non-zero PCM detector below is not enough
    // to guarantee restoration to the user's requested Feature Unit level.

    // ===== 淡入处理 =====

    // 首次写入：如果还没标记过 startupSilenceDone 且遇到了真实音量数据，启动淡入

    if (!ctx->startupSilenceDone && totalRead > 0 && bufferHasNonZero(buf, totalLen)) {
        ctx->startupSilenceDone = true;
        const UsbTransitionGainOwner gainOwner = getTransitionGainOwner(ctx);
        if (transitionOwnerUsesLegacyStartupFade(gainOwner) &&
            !isHardwareVolumePcmUnityPath(ctx) &&
            !isStrictBitPerfectPcmPath(ctx) &&
            !ctx->dsdSession) {
            int fadeSamples = (int)(ctx->sampleRate * USB_STARTUP_FADE_MS / 1000) * ctx->deviceChannels;
            if (fadeSamples < 1) fadeSamples = 1;
            ctx->fadeSamplesRemaining = fadeSamples;
            ctx->fadeTotalSamples = fadeSamples;
            LOGI("First audio data received, legacy startup fade-in: %d samples", fadeSamples);
        } else {
            // Explicit policy has exactly one owner. SessionPcm is already armed by Kotlin;
            // UnityPcm must stay untouched; DSD/DoP uses transport-correct silence.
            ctx->fadeSamplesRemaining = 0;
            ctx->fadeTotalSamples = 0;
            LOGI("First audio data received, legacy startup fade bypassed owner=%s",
                 transitionGainOwnerName(gainOwner));
        }
    }

    // 淡入处理：DoP 模式下跳过（淡入会破坏 DoP marker 字节）

    if (totalLen > 0 && ctx->fadeSamplesRemaining > 0 && !fillDopSilence &&
        !fillNativeDsdSilenceFrames &&
        !isStrictBitPerfectPcmPath(ctx) && !isHardwareVolumePcmUnityPath(ctx)) {
        int samplesInBuf = totalLen / ctx->deviceBytesPerFrame;
        int fadePos = ctx->fadeTotalSamples - ctx->fadeSamplesRemaining;
        if (ctx->deviceSubslotSize == 2 && ctx->deviceBitDepth == 16) {
            applyFadeInS16LE(buf, totalLen, fadePos, ctx->fadeTotalSamples);
        } else if (ctx->deviceSubslotSize == 3 && ctx->deviceBitDepth == 24) {
            applyFadeInS24LE(buf, totalLen, fadePos, ctx->fadeTotalSamples);
        } else if (ctx->deviceSubslotSize == 4 && ctx->deviceBitDepth == 32) {
            applyFadeInS32LE(buf, totalLen, fadePos, ctx->fadeTotalSamples);
        }
        ctx->fadeSamplesRemaining -= samplesInBuf;
        if (ctx->fadeSamplesRemaining <= 0) {
            ctx->fadeSamplesRemaining = 0;
        }
    }

    // L/R volume 不一致时回退到 PCM 软件音量


    // DoP ܰ PCM ƻ marker/payloadֱϷ DoP

    if (totalLen > 0 && ctx->stopFadeActive.load(std::memory_order_acquire) &&
        !isStrictBitPerfectPcmPath(ctx) && !isHardwareVolumePcmUnityPath(ctx)) {
        if (fillDopSilence) {
            // The whole transfer is replaced, so regenerate markers from the
            // phase that belonged to its first frame, not from the already
            // advanced next-transfer cursor.
            outputMarkerCursor = fillDoPSilence(
                    buf, totalLen, ctx->bytesPerFrame, ctx->deviceChannels,
                    fillMarkerA, fillMarkerB, transferMarkerStart);
            ctx->stopFadeSamplesRemaining.store(0, std::memory_order_release);
            ctx->stopFadeActive.store(false, std::memory_order_release);
        } else if (fillNativeDsdSilenceFrames) {
            outputMarkerCursor = fillNativeDsdSilence(
                    buf, totalLen, ctx->bytesPerFrame, ctx->deviceChannels,
                    ctx->deviceSubslotSize, transferMarkerStart);
            ctx->stopFadeSamplesRemaining.store(0, std::memory_order_release);
            ctx->stopFadeActive.store(false, std::memory_order_release);
        } else {
            applyStopFade(ctx, buf, totalLen);
        }
    }

    // 在发送到 USB 之前应用软件音量（基于当前 volume 值）

    // 这样音量变化最多一个 transfer 周期后生效，不会延迟到 ring buffer 旧数据

    // DoP 模式下跳过：PCM 域音量会破坏 DoP marker 字节，DoP 应使用硬件音量

    if (totalLen > 0 && !fillDopSilence && !fillNativeDsdSilenceFrames) {
        float vol = getEffectiveSoftwareVolume(ctx, totalLen / std::max(1, ctx->deviceBytesPerFrame));
        if (vol < 0.999f) { // 当需要调节时进入

            if (ctx->deviceSubslotSize == 2 && ctx->deviceBitDepth == 16) {
                if (g_usbLinearVolume.load(std::memory_order_relaxed)) {
                    applyVolumeS16LEFloat(buf, totalLen, vol);
                } else {
                    applyVolumeS16LE(buf, totalLen, vol);
                }
            } else if (ctx->deviceSubslotSize == 3 && ctx->deviceBitDepth == 24) {
                applyVolumeS24LE(buf, totalLen, vol);
            } else if (ctx->deviceSubslotSize == 4 && ctx->deviceBitDepth == 32) {
                applyVolumeS32LE(buf, totalLen, vol);
            }
        }
    }

    // Commit the next outgoing transport phase exactly once, after every
    // operation that may have replaced the transfer payload.
    if (fillDopSilence || fillNativeDsdSilenceFrames) {
        ctx->dopOutputMarkerStart = outputMarkerCursor;
    }

    // Scheduled-byte stats: this is what we asked libusb to submit, not what the DAC actually consumed.
    ctx->statsScheduledUsbBytes.fetch_add(totalLen, std::memory_order_relaxed);
    if (isPcmToDsdDemandActive(ctx)) {
        requestPcmToDsdDemand(ctx, underrunThisRound ? "iso_underrun" : "iso_consume");
    }

    if (underrunThisRound && totalRead == 0) {
        if (ctx->transitionSilenceBytesRemaining.load(std::memory_order_acquire) > 0) {
            ctx->consecutiveEmptyTransfers = 0;
        } else {
            ctx->consecutiveEmptyTransfers++;
            if (ctx->consecutiveEmptyTransfers >= STARVATION_EMPTY_XFER_THRESHOLD) {
                const bool pcmToDsdAsync = isPcmToDsdDemandActive(ctx);
                const bool directDsdAsync = ctx->sourceDsdSession;
                if (pcmToDsdAsync || directDsdAsync) {
                    // The packet tail was already filled with valid DoP/native-DSD silence.
                    // Keep the stream live and recover on the very next real converter block.
                    // The old 25 ms recovery watermark amplified brief converter jitter into
                    // repeated audible dropouts, especially at DSD64 and DSD256.
                    if (ctx->consecutiveEmptyTransfers == STARVATION_EMPTY_XFER_THRESHOLD ||
                        ctx->consecutiveEmptyTransfers % 100 == 0) {
                        LOGW("DSD_TRANSPORT transient underrun without starvation latch: direct=%d emptyTransfers=%d ring=%zu/%zu",
                             directDsdAsync ? 1 : 0,
                             ctx->consecutiveEmptyTransfers, ringAvailable(ctx), ctx->pcmRingBuffer.size());
                    }
                    ctx->starved = false;
                    ctx->starvedRecoveryBytes = 0;
                } else {
                    if (ctx->starvedRecoveryBytes <= 0) {
                        int recoveryBytes = ctx->clock.deviceBytesPerSecond * STARVATION_RECOVERY_MS / 1000;
                        const int minRecovery = std::max(ctx->deviceBytesPerFrame * 256, ctx->clock.deviceBytesPerSecond / 20);
                        if (recoveryBytes < minRecovery) recoveryBytes = minRecovery;
                        ctx->starvedRecoveryBytes = recoveryBytes;
                    }
                    ctx->starved = true;
                    LOGW("Entering starvation: emptyTransfers=%d, recoveryBytes=%d, ring=%zu/%zu",
                         ctx->consecutiveEmptyTransfers,
                         ctx->starvedRecoveryBytes,
                         ringAvailable(ctx),
                         ctx->pcmRingBuffer.size());
                }
            }
        }
    } else if (totalRead > 0) {
        ctx->consecutiveEmptyTransfers = 0;
    }
}

static int backgroundGuardianRecoverTransfers(void* opaque, const char* reason) {
    // Recovery watchdog intentionally disabled. The guardian remains only as the
    // existing libusb event-pump companion; it must never mark a live session
    // BROKEN, stop writes, or rebuild transfers from a periodic observation.
    (void)opaque;
    (void)reason;
    return 0;
}

// ==========================
// Pending counters represent actual kernel-owned submissions. Never allow an
// unexpected duplicate/late callback to underflow them: a negative count could
// make teardown believe every URB was reaped and free callback-owned memory.
// ==========================
static int decrementPendingCounterSafely(
        std::atomic<int>& counter,
        UsbAudioContext* ctx,
        const char* name) {
    const auto result = decrementPendingCounter(counter);
    if (result.underflowPrevented) {
        LOGE("Pending counter underflow prevented: ctx=%p counter=%s current=%d",
             ctx, name ? name : "unknown", counter.load(std::memory_order_acquire));
        if (ctx) {
            ctx->sessionBroken.store(true, std::memory_order_release);
            ctx->quarantined.store(true, std::memory_order_release);
            ctx->acceptingWrites.store(false, std::memory_order_release);
            ctx->stopRequested.store(true, std::memory_order_release);
            setUsbStreamState(ctx, UsbStreamState::BROKEN, "pending_counter_underflow");
        }
    }
    return result.remaining;
}

static void decrementPendingAndNotifyStop(UsbAudioContext* ctx) {
    if (!ctx) return;
    const int left = decrementPendingCounterSafely(
            ctx->pendingTransfers, ctx, "iso");
    if (left <= 0) {
        std::lock_guard<std::mutex> lk(ctx->stopMutex);
        ctx->stopCV.notify_all();
    }
}

// Backing allocation upper bound. The submitted ISO packet length must still
// match the descriptor-advertised feedback endpoint max packet size (normally
// 3 bytes for FS UAC1 or 4 bytes for HS UAC2). Submitting all 8 backing bytes
// to a 4-byte endpoint can produce empty/short packet observations and force a
// valid async DAC into fixed pacing.

static int feedbackTransferPacketBytes(const UsbAudioContext* ctx) {
    if (!ctx) return 0;
    return rawsmusic::usb::feedbackTransferPacketBytes(
            ctx->runtimeFormat.feedbackEndpoint.maxPacketSize,
            ctx->isFullSpeed);
}

static void LIBUSB_CALL feedbackCallback(struct libusb_transfer *xfer);

static void armFeedbackStartupGrace(UsbAudioContext* ctx, const char* reason) {
    if (!ctx) return;
    const int64_t untilMs = nowSteadyMs() + kFeedbackStartupGraceMs;
    ctx->feedbackStartupGraceUntilMs.store(untilMs, std::memory_order_release);
    LOGI("Feedback startup grace armed: until=%lld reason=%s ep=0x%02X",
         static_cast<long long>(untilMs),
         reason ? reason : "unknown",
         ctx->feedbackEpAddress);
}

static bool inFeedbackStartupGrace(const UsbAudioContext* ctx) {
    if (!ctx) return false;
    const int64_t untilMs = ctx->feedbackStartupGraceUntilMs.load(std::memory_order_acquire);
    return feedbackStartupGraceActive(nowSteadyMs(), untilMs);
}

static double decodeFeedbackFramesPerServiceInterval(
        const UsbAudioContext* ctx,
        uint32_t raw,
        int actualLength,
        const char** outEncoding) {
    const auto decoded = rawsmusic::usb::decodeFeedbackFramesPerServiceInterval(
            ctx && ctx->isFullSpeed,
            ctx ? ctx->serviceIntervalsPerSecond : 0,
            ctx ? ctx->nominalSampleRate : 0,
            raw,
            actualLength);
    if (outEncoding) *outEncoding = decoded.encoding;
    return decoded.framesPerServiceInterval;
}

static void setFeedbackState(UsbAudioContext* ctx, UsbFeedbackState state, int reasonCode) {
    if (!ctx) return;
    int old = ctx->feedbackState.exchange(static_cast<int>(state), std::memory_order_acq_rel);
    ctx->feedbackLastReason.store(reasonCode, std::memory_order_relaxed);
    if (old != static_cast<int>(state)) {
        LOGI("Feedback state: %s -> %s reason=%d ep=0x%02X",
             feedbackStateName(old), feedbackStateName(static_cast<int>(state)),
             reasonCode, ctx->feedbackEpAddress);
    }
}

static void setPacingMode(UsbAudioContext* ctx, UsbPacingMode mode, const char* reason) {
    if (!ctx) return;
    int old = ctx->pacingMode.exchange(static_cast<int>(mode), std::memory_order_acq_rel);
    if (old != static_cast<int>(mode)) {
        LOGI("USB pacing mode: %s -> %s reason=%s ep=0x%02X fbEp=0x%02X ips=%d sr=%u frame=%d",
             pacingModeName(old), pacingModeName(static_cast<int>(mode)),
             reason ? reason : "unknown",
             ctx->epAddress, ctx->feedbackEpAddress,
             ctx->serviceIntervalsPerSecond, ctx->nominalSampleRate, ctx->bytesPerFrame);
    }
}

static bool feedbackIsLockedForPacing(const UsbAudioContext* ctx) {
    if (!ctx || ctx->feedbackEpAddress == 0 || ctx->feedbackDegraded) return false;
    const int state = ctx->feedbackState.load(std::memory_order_acquire);
    return state == static_cast<int>(UsbFeedbackState::LOCKED);
}

static void keepFixedPacerWhileFeedbackValidates(
        UsbAudioContext* ctx,
        UsbFeedbackState state,
        const char* reason,
        int reasonCode) {
    if (!ctx) return;
    const int oldState = ctx->feedbackState.load(std::memory_order_acquire);
    const int oldPacing = ctx->pacingMode.load(std::memory_order_acquire);
    const bool alreadyFixed =
            oldPacing == static_cast<int>(UsbPacingMode::NoFeedbackFixed) &&
            oldState == static_cast<int>(state);
    setFeedbackState(ctx, state, reasonCode);
    setPacingMode(ctx, UsbPacingMode::NoFeedbackFixed, reason ? reason : "feedback validating");
    if (alreadyFixed) return;
    ctx->adaptiveRate.active = false;
    ctx->adaptiveRate.stableCount = 0;
    ctx->adaptiveRate.integralError = 0.0;
    ctx->adaptiveRate.correction = 0.0;
    ctx->bytes_per_second_smoothed.store(0, std::memory_order_relaxed);
    ctx->feedbackSampleRateMilli.store(0, std::memory_order_relaxed);
    resetUsbIsoPacerToRuntime(ctx, reason ? reason : "feedback_validating_fixed_pacer");
}

static void markFeedbackSuspect(UsbAudioContext* ctx, int reasonCode) {
    if (!ctx) return;
    int invalid = ctx->feedbackInvalidCount.fetch_add(1, std::memory_order_relaxed) + 1;
    setFeedbackState(ctx, UsbFeedbackState::SUSPECT, reasonCode);
    if (feedbackInvalidCountShouldDegrade(invalid)) {
        // Degradation is handled by the caller so it can include a descriptive log reason.
        return;
    }
}

static void degradeFeedbackToFixedPacer(UsbAudioContext* ctx, const char* reason, int code) {
    if (!ctx) return;

    bool first = !ctx->feedbackDegraded;
    ctx->feedbackDegraded = true;
    setFeedbackState(ctx, UsbFeedbackState::DEGRADED, code);
    setPacingMode(ctx, UsbPacingMode::FeedbackDegradedFixed, reason ? reason : "feedback degraded");
    ctx->feedbackInvalidCount.fetch_add(1, std::memory_order_relaxed);

    // feedback 策略：不可靠的 feedback 会从 pacing 路径移除，但
    // it does not make the stream adaptive or broken. The fixed fractional pacer
    // already preserves exact long-term sample rate for no-feedback async DACs.
    ctx->adaptiveRate.active = false;
    ctx->adaptiveRate.stableCount = 0;
    ctx->adaptiveRate.integralError = 0.0;
    ctx->adaptiveRate.correction = 0.0;
    ctx->bytes_per_second_smoothed.store(0, std::memory_order_relaxed);
    ctx->feedbackEmptyCount.store(kFeedbackEmptyThreshold, std::memory_order_relaxed);
    ctx->feedbackSampleRateMilli.store(0, std::memory_order_relaxed);
    resetUsbIsoPacerToRuntime(ctx, reason ? reason : "feedback_degraded_fixed_pacer");

    if (first) {
        LOGW("Feedback endpoint 0x%02X disabled at %s code=%d; continuing with fixed fractional ISO pacer. buf=%zu/%zu expectedBps=%llu",
             ctx->feedbackEpAddress,
             reason ? reason : "unknown",
             code,
             ringAvailable(ctx),
             ctx->pcmRingBuffer.size(),
             (unsigned long long)ctx->bytes_per_second);
    }
}

static bool startPersistentFeedbackTransfer(UsbAudioContext* ctx, const char* reason, const char* tag) {
    if (!ctx || ctx->feedbackEpAddress == 0 || !ctx->feedbackBuffer || ctx->feedbackDegraded) return false;
    if (ctx->feedbackTransfer != nullptr ||
        ctx->pendingFeedbackTransfers.load(std::memory_order_acquire) != 0) {
        return false;
    }

    const int packetBytes = feedbackTransferPacketBytes(ctx);
    if (packetBytes < kFeedbackMinPacketBytes) {
        LOGW("%s invalid feedback packet size: descriptor=%d ep=0x%02X",
             tag ? tag : "feedback",
             ctx->runtimeFormat.feedbackEndpoint.maxPacketSize,
             ctx->feedbackEpAddress);
        degradeFeedbackToFixedPacer(ctx, "feedback descriptor packet size", LIBUSB_ERROR_INVALID_PARAM);
        return false;
    }

    ctx->feedbackTransfer = libusb_alloc_transfer(1);
    if (!ctx->feedbackTransfer) {
        LOGW("%s alloc failed", tag ? tag : "feedback");
        return false;
    }

    memset(ctx->feedbackBuffer, 0, kFeedbackTransferBufferBytes);
    libusb_fill_iso_transfer(
            ctx->feedbackTransfer,
            ctx->devHandle,
            ctx->feedbackEpAddress,
            ctx->feedbackBuffer,
            packetBytes,
            1,
            feedbackCallback,
            ctx,
            0);
    libusb_set_iso_packet_lengths(
            ctx->feedbackTransfer, static_cast<unsigned int>(packetBytes));
    setFeedbackState(ctx, UsbFeedbackState::VALIDATING, 0);

    // The event loop can complete a transfer immediately. Count ownership
    // before submit so the callback can never observe a zero counter.
    ctx->pendingFeedbackTransfers.fetch_add(1, std::memory_order_acq_rel);
    const int fbRet = libusb_submit_transfer(ctx->feedbackTransfer);
    if (fbRet < 0) {
        ctx->pendingFeedbackTransfers.fetch_sub(1, std::memory_order_acq_rel);
        LOGW("%s submit failed: %s; disabling feedback and using fixed pacer",
             tag ? tag : "feedback",
             libusb_strerror(fbRet));
        libusb_free_transfer(ctx->feedbackTransfer);
        ctx->feedbackTransfer = nullptr;
        degradeFeedbackToFixedPacer(ctx, reason ? reason : "feedback submit", fbRet);
        return false;
    }

    armFeedbackStartupGrace(ctx, reason ? reason : "feedback_submit");
    LOGI("%s transfer submitted: ep=0x%02X packetBytes=%d descriptorMax=%d bInterval=%d",
         tag ? tag : "feedback",
         ctx->feedbackEpAddress,
         packetBytes,
         ctx->runtimeFormat.feedbackEndpoint.maxPacketSize,
         ctx->runtimeFormat.feedbackEndpoint.bInterval);
    return true;
}

// ==========================
// 反馈端点回调（UAC2 同步信号读取与解析）

// ==========================
static void LIBUSB_CALL feedbackCallback(struct libusb_transfer *xfer) {
    UsbAudioContext *ctx = reinterpret_cast<UsbAudioContext*>(xfer->user_data);
    if (!ctx) return;

    // feedback transfer 完成，pendingFeedback--

    const int left = decrementPendingCounterSafely(
            ctx->pendingFeedbackTransfers, ctx, "feedback");

    // 通知 stopCV 和 closeCV

    if (left <= 0) {
        {
            std::lock_guard<std::mutex> lk(ctx->stopMutex);
            ctx->stopCV.notify_all();
        }
        {
            std::lock_guard<std::mutex> lk(ctx->closeMutex);
            ctx->closeCV.notify_all();
        }
    }

    // closing 分支：不 resubmit

    if (ctx->closing.load(std::memory_order_acquire)) {
        return;
    }

    if (isUsbTransferCancelled(xfer->status)) {
        LOGD("Feedback transfer cancelled");
        return;
    }

    if (isFeedbackTransferFailureStatus(xfer->status)) {
        // Feedback endpoints are optional for playback stability. Some DACs expose
        // a feedback endpoint but STALL/ERROR/empty it. Do not kill the OUT stream;
        // keep ISO OUT alive and fall back to the fixed fractional pacer.
        degradeFeedbackToFixedPacer(ctx, "feedback status", xfer->status);
        return;
    }

    if (isUsbTransferCompleted(xfer->status)) {
        const bool startupGrace = inFeedbackStartupGrace(ctx);

        // libusb's transfer-level actual_length is not the authoritative length
        // for isochronous transfers. Read the packet descriptor; otherwise a
        // valid 3/4-byte feedback report is repeatedly misclassified as empty.
        int feedbackPacketStatus = LIBUSB_TRANSFER_COMPLETED;
        int feedbackActualLength = xfer->actual_length;
        if (xfer->num_iso_packets > 0) {
            feedbackPacketStatus = xfer->iso_packet_desc[0].status;
            feedbackActualLength = static_cast<int>(xfer->iso_packet_desc[0].actual_length);
        }

        if (feedbackPacketStatus != LIBUSB_TRANSFER_COMPLETED) {
            if (startupGrace) {
                setFeedbackState(ctx, UsbFeedbackState::VALIDATING, feedbackPacketStatus);
                goto feedback_resubmit;
            }
            markFeedbackSuspect(ctx, feedbackPacketStatus);
            if (feedbackInvalidCountShouldDegrade(
                    ctx->feedbackInvalidCount.load(std::memory_order_relaxed))) {
                degradeFeedbackToFixedPacer(ctx, "feedback ISO packet status", feedbackPacketStatus);
                return;
            }
            goto feedback_resubmit;
        }

        if (!xfer->buffer || feedbackActualLength <= 0) {
            if (startupGrace) {
                setFeedbackState(ctx, UsbFeedbackState::VALIDATING, 0);
                goto feedback_resubmit;
            }
            int empty = ctx->feedbackEmptyCount.fetch_add(1, std::memory_order_relaxed) + 1;
            if (empty >= kFeedbackEmptyThreshold) {
                keepFixedPacerWhileFeedbackValidates(
                        ctx,
                        UsbFeedbackState::SUSPECT,
                        "feedback empty",
                        2);
            } else {
                setFeedbackState(ctx, UsbFeedbackState::VALIDATING, 0);
            }
            goto feedback_resubmit;
        }
        const int minLen = kFeedbackMinPacketBytes;
        if (feedbackActualLength < minLen) {
            LOGW("Feedback short ISO packet: packetActual=%d transferActual=%d need=%d",
                 feedbackActualLength, xfer->actual_length, minLen);
            if (startupGrace) {
                setFeedbackState(ctx, UsbFeedbackState::VALIDATING, 0);
                goto feedback_resubmit;
            }
            markFeedbackSuspect(ctx, 3);
            if (feedbackInvalidCountShouldDegrade(
                    ctx->feedbackInvalidCount.load(std::memory_order_relaxed))) {
                degradeFeedbackToFixedPacer(ctx, "feedback short ISO packet", feedbackActualLength);
                return;
            }
            goto feedback_resubmit;
        }
        // 有数据了，复位计数器

        ctx->feedbackEmptyCount.store(0, std::memory_order_relaxed);
        // 解析 UAC2 反馈数据

        uint8_t *fb = xfer->buffer;
        const uint32_t feedbackRaw = readLittleEndianFeedbackRaw(fb, feedbackActualLength);
        const char* feedbackEncoding = "unknown";
        const double framesPerService = decodeFeedbackFramesPerServiceInterval(
                ctx, feedbackRaw, feedbackActualLength, &feedbackEncoding);

        static std::atomic<int> feedbackPacketTraceCount{0};
        const int traceCount = feedbackPacketTraceCount.fetch_add(1, std::memory_order_relaxed) + 1;
        if (traceCount <= 8) {
            LOGI("Feedback ISO packet #%d: requested=%u packetActual=%d transferActual=%d "
                 "packetStatus=%d raw=0x%08X encoding=%s",
                 traceCount,
                 xfer->num_iso_packets > 0 ? xfer->iso_packet_desc[0].length : 0u,
                 feedbackActualLength,
                 xfer->actual_length,
                 feedbackPacketStatus,
                 feedbackRaw,
                 feedbackEncoding);
        }
        ctx->feedbackRate.store(framesPerService, std::memory_order_relaxed);

        // 计算 feedback 推导的实际采样率

        // Prefer the descriptor-derived service interval instead of assuming all
        // high-speed endpoints use a 4-byte Q16.16 report. TP55/HyperOS matches
        // the more permissive UAC20 diagnostic parser here.
        const double serviceIntervalsPerSecond = static_cast<double>(
                std::max(1, ctx->serviceIntervalsPerSecond));
        const double feedbackSampleRate = framesPerService * serviceIntervalsPerSecond;
        ctx->feedbackSampleRateMilli.store((int)llround(feedbackSampleRate * 1000.0), std::memory_order_relaxed);
        if (ctx->feedbackState.load(std::memory_order_relaxed) == static_cast<int>(UsbFeedbackState::DISCOVERED)) {
            setFeedbackState(ctx, UsbFeedbackState::VALIDATING, 0);
        }

        // 平滑反馈到 packet scheduler

        // feedbackSampleRate 脳 frameSize = bytes_per_second
        {
            double bytes_per_sec_d = feedbackSampleRate * (double)ctx->bytesPerFrame;
            uint64_t new_bps = (uint64_t)(bytes_per_sec_d + 0.5);

            // 合法性检查 + 容许 ppm 级微调（不超过 requested rate 的 ±1%）

            uint64_t baseBps = ctx->bytes_per_second;
            if (feedbackRateWithinTolerance(new_bps, baseBps)) {
                int valid = ctx->feedbackValidCount.fetch_add(1, std::memory_order_relaxed) + 1;
                ctx->feedbackInvalidCount.store(0, std::memory_order_relaxed);
                uint64_t smoothed = ctx->bytes_per_second_smoothed.load(std::memory_order_relaxed);
                smoothed = smoothFeedbackBytesPerSecond(smoothed, ctx->bytes_per_second, new_bps);
                ctx->bytes_per_second_smoothed.store(smoothed, std::memory_order_relaxed);

                double smoothedSampleRate =
                        (double)smoothed / (double)ctx->bytesPerFrame;
                if (feedbackValidCountCanLock(valid)) {
                    ctx->feedbackStartupGraceUntilMs.store(0, std::memory_order_release);
                    setFeedbackState(ctx, UsbFeedbackState::LOCKED, 0);
                    setPacingMode(ctx, UsbPacingMode::ExplicitFeedback, "feedback locked");
                    if (feedbackSampleRateWithinNominal(
                            smoothedSampleRate,
                            static_cast<double>(ctx->nominalSampleRate))) {
                        ctx->isoPacer.setSampleRate(smoothedSampleRate);
                    }
                } else {
                    setFeedbackState(ctx, UsbFeedbackState::VALIDATING, 0);
                }
            } else {
                // Feedback claims a rate far away from the committed stream rate.
                // Treat it as suspect and stop letting it drive the pacer after a few samples.
                if (startupGrace) {
                    setFeedbackState(ctx, UsbFeedbackState::VALIDATING, 0);
                    goto feedback_resubmit;
                }
                markFeedbackSuspect(ctx, 4);
                int invalid = ctx->feedbackInvalidCount.load(std::memory_order_relaxed);
                if (feedbackInvalidCountShouldDegrade(invalid)) {
                    LOGW("Feedback implausible: sampleRate=%.3f bps=%llu expected=%llu invalid=%d encoding=%s; degrading",
                         feedbackSampleRate, (unsigned long long)new_bps,
                         (unsigned long long)baseBps, invalid, feedbackEncoding);
                    keepFixedPacerWhileFeedbackValidates(
                            ctx,
                            UsbFeedbackState::SUSPECT,
                            "feedback implausible",
                            (int)feedbackSampleRate);
                }
            }
        }

        static std::atomic<int> fbCount{0};
        if (++fbCount >= 1000) {
            fbCount = 0;
            double bytes_per_sec_d = feedbackSampleRate * (double)ctx->bytesPerFrame;
            LOGI("Feedback: raw=0x%08X framesPerInterval=%.6f serviceIntervals=%.0f "
                 "selectedRate=%.3f bytes_per_sec=%.0f (%s)",
                 feedbackRaw, framesPerService,
                 serviceIntervalsPerSecond,
                 feedbackSampleRate, bytes_per_sec_d,
                 feedbackEncoding);
        }
    } else {
        LOGW("Feedback non-completed: status=%d", xfer->status);
    }

    feedback_resubmit:
    if (!ctx->streaming.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire) ||
        ctx->feedbackDegraded) {
        return;
    }

    if (!resubmitFeedbackDirect(ctx)) {
        if (ctx->streaming.load(std::memory_order_acquire) &&
            !ctx->stopping.load(std::memory_order_acquire) &&
            !ctx->transportLost.load(std::memory_order_acquire)) {
            LOGW("Feedback direct resubmit failed; degrading feedback and keeping ISO OUT alive");
            degradeFeedbackToFixedPacer(ctx, "feedback direct resubmit failed", LIBUSB_ERROR_BUSY);
        }
    }
}

// Apply a warm-track generation cut only from the libusb completion owner.
// The caller has already disabled acceptingWrites, so after this reset the next
// producer write belongs to the new decoder generation. Do not memset the multi-
// second ring here: resetting the SPSC indices makes old bytes unreachable and
// keeps the real-time callback bounded.
static uint64_t applyPendingTrackBoundaryFromIsoCallback(UsbAudioContext* ctx) {
    if (!ctx) return 0;
    const uint64_t requested =
            ctx->trackBoundaryRequestedSeq.load(std::memory_order_acquire);
    const uint64_t applied =
            ctx->trackBoundaryAppliedSeq.load(std::memory_order_relaxed);
    if (requested == 0 || requested <= applied) return 0;

    ctx->pcmWritePos.store(0, std::memory_order_release);
    ctx->pcmReadPos.store(0, std::memory_order_release);
    ctx->transitionSilenceBytesRemaining.store(
            std::max(ctx->deviceBytesPerFrame * 256,
                     ctx->clock.deviceBytesPerSecond * USB_TRACK_SWITCH_SILENCE_MS / 1000),
            std::memory_order_release);
    ctx->starved = false;
    ctx->starvedRecoveryBytes = 0;
    ctx->consecutiveEmptyTransfers = 0;
    ctx->isoPacer.accumulatorQ32 = 0;
    ctx->statsUnderrun.store(0, std::memory_order_relaxed);
    ctx->fadeSamplesRemaining = 0;
    ctx->fadeTotalSamples = 0;
    ctx->stopFadeSamplesRemaining.store(0, std::memory_order_release);
    ctx->stopFadeTotalSamples.store(0, std::memory_order_release);
    ctx->stopFadeActive.store(false, std::memory_order_release);
    ctx->startupSilenceDone = false;
    // Do not clear stopRequested here. It is a lifecycle poison/stop signal that may
    // be raised concurrently by a timeout/detach path after this callback began.

    // ACK is intentionally delayed until the boundary transfer has been refilled
    // and successfully submitted. Otherwise the feeder could commit a decoder after
    // the cut but before discovering that the first new-generation URB could not arm.
    return requested;
}

// ==========================
// ISO 传输回调（极端：不 malloc、不大量 log、不 JNI 回调）

// ==========================
static void LIBUSB_CALL isoCallback(struct libusb_transfer *xfer) {
    auto* ud = reinterpret_cast<UsbAudioContext::IsoUserData*>(xfer->user_data);
    if (!ud || !ud->ctx) return;
    UsbAudioContext *ctx = ud->ctx;
    int index = ud->index;
    const int64_t callbackNowMs = nowSteadyMs();
    const int64_t previousCallbackMs = ctx->isoLastCallbackMs.exchange(callbackNowMs, std::memory_order_relaxed);
    if (previousCallbackMs > 0) {
        const int gapMs = (int)std::max<int64_t>(0, callbackNowMs - previousCallbackMs);
        updateAtomicMax(ctx->isoMaxCallbackGapMs, gapMs);
        ctx->isoCallbackGapTotalMs.fetch_add(gapMs, std::memory_order_relaxed);
        ctx->isoCallbackGapCount.fetch_add(1, std::memory_order_relaxed);
    }
    ctx->isoCompletedTransfers.fetch_add(1, std::memory_order_relaxed);

    // 该 transfer 已经完成，pending--

    const int left = decrementPendingCounterSafely(
            ctx->pendingTransfers, ctx, "iso");

    // 通知 stopCV（stopStreamingLocked 等待）和 closeCV（nativeClose 等待）

    if (left <= 0) {
        {
            std::lock_guard<std::mutex> lk(ctx->stopMutex);
            ctx->stopCV.notify_all();
        }
        {
            std::lock_guard<std::mutex> lk(ctx->closeMutex);
            ctx->closeCV.notify_all();
        }
    }

    // closing 分支：不 resubmit

    if (ctx->closing.load(std::memory_order_acquire)) {
        return;
    }

    if (isUsbTransferCancelled(xfer->status)) {
        return;  // stopping 状态，避免刷 log

    }

    if (isIsoTransportLossStatus(xfer->status)) {
        markUsbTransportLost(ctx, "isoCallback status", index, xfer->status);
        return;
    }

    if (xfer->status == LIBUSB_TRANSFER_ERROR) {
        ctx->statsXferError.fetch_add(1, std::memory_order_relaxed);
    }

    if (!ctx->streaming.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire)) {
        return;
    }

    // Decoder handoff ownership matches UAPP's callback-side DoubleBuffer cut:
    // the feeder requests a boundary, but this completion owner performs it after
    // the previous URB is known complete and before that URB is refilled/resubmitted.
    const uint64_t pendingTrackBoundarySeq =
            applyPendingTrackBoundaryFromIsoCallback(ctx);

    // Match the lighter UAC20 real-OUT accounting on the steady-state success
    // path. HyperOS TP55 logs show the transport itself can sustain full rate
    // with the simpler submitter, so avoid per-packet atomic churn here unless
    // the transfer actually reports an error.
    int completedBytesThisTransfer = 0;
    int completedPacketsThisTransfer = 0;
    int actualBytesThisTransfer = 0;
    int packetErrorsThisTransfer = 0;
    int zeroActualPacketsThisTransfer = 0;
    int completedStatusPacketsThisTransfer = 0;
    int cancelledStatusPacketsThisTransfer = 0;
    int erroredStatusPacketsThisTransfer = 0;
    int otherStatusPacketsThisTransfer = 0;
    if (isUsbTransferCompleted(xfer->status)) {
        actualBytesThisTransfer = std::max(0, xfer->actual_length);
        completedBytesThisTransfer = actualBytesThisTransfer > 0
                                     ? actualBytesThisTransfer
                                     : std::max(0, xfer->length);
        completedPacketsThisTransfer = std::max(0, xfer->num_iso_packets);
        completedStatusPacketsThisTransfer = completedPacketsThisTransfer;
        if (actualBytesThisTransfer <= 0) {
            zeroActualPacketsThisTransfer = completedPacketsThisTransfer;
        }
    } else {
        for (int i = 0; i < xfer->num_iso_packets; i++) {
            const auto& pkt = xfer->iso_packet_desc[i];
            const bool packetOk = isIsoPacketSuccessfulStatus(pkt.status);
            actualBytesThisTransfer += std::max(0, static_cast<int>(pkt.actual_length));
            if (!packetOk) {
                packetErrorsThisTransfer++;
                if (isUsbTransferCancelled(pkt.status)) {
                    cancelledStatusPacketsThisTransfer++;
                } else if (isIsoPacketFailureStatus(pkt.status)) {
                    erroredStatusPacketsThisTransfer++;
                } else {
                    otherStatusPacketsThisTransfer++;
                }
                continue;
            }
            completedStatusPacketsThisTransfer++;
            if (pkt.actual_length <= 0) {
                zeroActualPacketsThisTransfer++;
            }
            if (pkt.actual_length > 0) {
                completedBytesThisTransfer += pkt.actual_length;
                completedPacketsThisTransfer++;
            }
        }
        if (actualBytesThisTransfer <= 0 && xfer->actual_length > 0) {
            actualBytesThisTransfer = xfer->actual_length;
        }
        if (completedBytesThisTransfer <= 0 && completedStatusPacketsThisTransfer > 0) {
            completedBytesThisTransfer = std::max(0, xfer->length);
            completedPacketsThisTransfer = completedStatusPacketsThisTransfer;
        }
    }
    if (packetErrorsThisTransfer > 0) {
        ctx->statsPacketError.fetch_add(packetErrorsThisTransfer, std::memory_order_relaxed);
    }
    if (completedStatusPacketsThisTransfer > 0) {
        ctx->isoCompletedStatusPackets.fetch_add(
                completedStatusPacketsThisTransfer, std::memory_order_relaxed);
    }
    if (cancelledStatusPacketsThisTransfer > 0) {
        ctx->isoCancelledStatusPackets.fetch_add(
                cancelledStatusPacketsThisTransfer, std::memory_order_relaxed);
    }
    if (erroredStatusPacketsThisTransfer > 0) {
        ctx->isoErroredStatusPackets.fetch_add(
                erroredStatusPacketsThisTransfer, std::memory_order_relaxed);
    }
    if (otherStatusPacketsThisTransfer > 0) {
        ctx->isoOtherStatusPackets.fetch_add(
                otherStatusPacketsThisTransfer, std::memory_order_relaxed);
    }
    if (zeroActualPacketsThisTransfer > 0) {
        ctx->isoZeroActualPackets.fetch_add(
                zeroActualPacketsThisTransfer, std::memory_order_relaxed);
    }
    if (actualBytesThisTransfer > 0) {
        ctx->isoActualLengthBytes.fetch_add(actualBytesThisTransfer, std::memory_order_relaxed);
    }
    ctx->statsCallbackCount.fetch_add(1, std::memory_order_relaxed);
    if (completedBytesThisTransfer > 0) {
        ctx->statsCompletedUsbBytes.fetch_add(completedBytesThisTransfer, std::memory_order_relaxed);
        ctx->statsTotalCompletedUsbBytes.fetch_add(completedBytesThisTransfer, std::memory_order_release);
        ctx->statsUsbBytes.fetch_add(completedBytesThisTransfer, std::memory_order_relaxed);
        ctx->statsPacketCount.fetch_add(completedPacketsThisTransfer, std::memory_order_relaxed);

        const int64_t nowMs = nowSteadyMs();
        int64_t firstCompletionMs = ctx->audibleFirstCompletionMs.load(std::memory_order_acquire);
        if (firstCompletionMs == 0) {
            ctx->audibleFirstCompletionMs.compare_exchange_strong(
                    firstCompletionMs, nowMs, std::memory_order_acq_rel, std::memory_order_acquire);
        }
        maybeMarkUsbAudibleAccepted(ctx, "iso_completion");
    }

    // Elapsed-time stats window. Do not assume 1000 callbacks == 1 second; HS/FS/bInterval vary.
    {
        const int64_t nowMs = nowSteadyMs();
        int64_t windowStart = ctx->statsWindowStartMs.load(std::memory_order_relaxed);
        if (windowStart <= 0) {
            ctx->statsWindowStartMs.store(nowMs, std::memory_order_relaxed);
            windowStart = nowMs;
        }
        const int64_t elapsedMs = nowMs - windowStart;
        if (elapsedMs >= 220 &&
            !ctx->serviceIntervalAutoRepairDone.load(std::memory_order_acquire) &&
            ctx->feedbackEpAddress == 0 &&
            ctx->serviceIntervalsPerSecond > 1000) {
            const uint64_t expectedBpsEarly = ctx->bytes_per_second;
            const int64_t completedBytesSnap = ctx->statsCompletedUsbBytes.load(std::memory_order_relaxed);
            const int packetCountSnap = ctx->statsPacketCount.load(std::memory_order_relaxed);
            const int submitErrsSnap = ctx->statsSubmitError.load(std::memory_order_relaxed);
            const int pktErrsSnap = ctx->statsPacketError.load(std::memory_order_relaxed);
            const int xferErrsSnap = ctx->statsXferError.load(std::memory_order_relaxed);
            if (completedBytesSnap > 0 && submitErrsSnap == 0 && pktErrsSnap == 0 && xferErrsSnap == 0) {
                const double scaleEarly = 1000.0 / (double)std::max<int64_t>(elapsedMs, 1);
                const double usbOutBytesPerSecEarly = (double)completedBytesSnap * scaleEarly;
                const int packetsPerSecEarly = (int)std::llround((double)packetCountSnap * scaleEarly);
                const char* repairReasonEarly = nullptr;
                const int repairedIpsEarly = chooseMeasuredRepairIps(
                        ctx->serviceIntervalsPerSecond,
                        expectedBpsEarly,
                        usbOutBytesPerSecEarly,
                        packetsPerSecEarly,
                        &repairReasonEarly);
                if (repairedIpsEarly > 0) {
                    const char* suppressReason = nullptr;
                    const bool suppress = suppressMeasuredServiceIntervalRepair(
                            ctx, nowMs, elapsedMs,
                            // Early windows do not have a clean app-input snapshot yet;
                            // require the regular 1s stats window before considering repair.
                            0.0, expectedBpsEarly, &suppressReason);
                    if (suppress) {
                        noteSuppressedMeasuredServiceIntervalRepair(
                                ctx, "early", suppressReason, repairReasonEarly,
                                ctx->serviceIntervalsPerSecond, repairedIpsEarly, elapsedMs,
                                0.0, usbOutBytesPerSecEarly, packetsPerSecEarly, expectedBpsEarly);
                    } else if (applyMeasuredServiceIntervalRepair(ctx, repairedIpsEarly, repairReasonEarly)) {
                        ctx->serviceIntervalAutoRepairDone.store(true, std::memory_order_release);
                    }
                }
            }
        }

        if (elapsedMs >= 1000) {
            ctx->statsWindowStartMs.store(nowMs, std::memory_order_relaxed);
            int cbCount = ctx->statsCallbackCount.exchange(0, std::memory_order_relaxed);
            int64_t appBytes = ctx->statsAppBytes.exchange(0, std::memory_order_relaxed);
            int64_t scheduledBytes = ctx->statsScheduledUsbBytes.exchange(0, std::memory_order_relaxed);
            int64_t completedBytes = ctx->statsCompletedUsbBytes.exchange(0, std::memory_order_relaxed);
            ctx->statsUsbBytes.store(0, std::memory_order_relaxed);
            int underruns = ctx->statsUnderrun.exchange(0, std::memory_order_relaxed);
            int submitErrs = ctx->statsSubmitError.exchange(0, std::memory_order_relaxed);
            int pktErrs = ctx->statsPacketError.exchange(0, std::memory_order_relaxed);
            int xferErrs = ctx->statsXferError.exchange(0, std::memory_order_relaxed);
            int pktCount = ctx->statsPacketCount.exchange(0, std::memory_order_relaxed);
            size_t bufUsed = ringAvailable(ctx);
            double fbRate = ctx->feedbackRate.load(std::memory_order_relaxed);
            uint64_t smoothBps = ctx->bytes_per_second_smoothed.load(std::memory_order_relaxed);
            uint64_t expectedBps = ctx->bytes_per_second;
            double scale = 1000.0 / (double)std::max<int64_t>(elapsedMs, 1);
            double avgPacketBytes = (pktCount > 0) ? (double)completedBytes / (double)pktCount : 0.0;
            double usbOutBytesPerSec = (double)completedBytes * scale;
            double scheduledUsbBytesPerSec = (double)scheduledBytes * scale;
            double appInBytesPerSec = (double)appBytes * scale;
            int packetsPerSec = (int)llround((double)pktCount * scale);
            int callbacksPerSec = (int)llround((double)cbCount * scale);

            ctx->lastAppBytesPerSec.store((int64_t)llround(appInBytesPerSec), std::memory_order_relaxed);
            ctx->lastScheduledUsbBytesPerSec.store((int64_t)llround(scheduledUsbBytesPerSec), std::memory_order_relaxed);
            ctx->lastCompletedUsbBytesPerSec.store((int64_t)llround(usbOutBytesPerSec), std::memory_order_relaxed);
            ctx->lastUnderrun.store(underruns, std::memory_order_relaxed);
            ctx->lastSubmitError.store(submitErrs, std::memory_order_relaxed);
            ctx->lastPacketError.store(pktErrs, std::memory_order_relaxed);
            ctx->lastXferError.store(xferErrs, std::memory_order_relaxed);
            ctx->lastPacketCount.store(packetsPerSec, std::memory_order_relaxed);
            ctx->lastCallbackCount.store(callbacksPerSec, std::memory_order_relaxed);

            maybeMarkUsbAudibleAccepted(ctx, "stats_completion");

            // If a previous resume/repair path corrupted pacing into endpoint-full packets,
            // repair it immediately against the descriptor model.  This is the signature of
            // the "resume plays too fast until next track" bug: expectedBps stays correct
            // but scheduled/completed jump several times above expected and avg packet becomes
            // maxPacket-sized.
            const int descriptorIps = descriptorIsoServiceIntervalsPerSecond(ctx);
            const int nominalCeil = nominalIsoPacketCeilBytes(ctx);
            if (expectedBps > 0 &&
                descriptorIps > 0 &&
                avgPacketBytes > (double)nominalCeil * 2.0 &&
                usbOutBytesPerSec > (double)expectedBps * 1.8) {
                LOGW("USB pacer runtime repair: reason=stats_guard ips=%d descriptorIps=%d avgPkt=%.1f nominalMax=%d completed=%.0f expected=%llu",
                     ctx->serviceIntervalsPerSecond, descriptorIps, avgPacketBytes, nominalCeil,
                     usbOutBytesPerSec, (unsigned long long)expectedBps);
                resetUsbIsoPacerToRuntime(ctx, "stats_guard_descriptor_reset");
                ctx->serviceIntervalAutoRepairDone.store(true, std::memory_order_release);
            }

            // 描述符 cadence 锁定：实测 callback rate 不作为
            // a physical endpoint cadence. Android may batch libusb completions
            // after app foreground/background scheduler gaps, so a 125us HS
            // endpoint can temporarily look like 1ms. Keep this as diagnostics
            // only unless a future explicit quirk enables measured repair.
            if (!ctx->serviceIntervalAutoRepairDone.load(std::memory_order_acquire) &&
                ctx->serviceIntervalsPerSecond > 1000 &&
                submitErrs == 0 && pktErrs == 0 && xferErrs == 0) {
                const char* repairReason = nullptr;
                const int repairedIps = chooseMeasuredRepairIps(
                        ctx->serviceIntervalsPerSecond,
                        expectedBps,
                        usbOutBytesPerSec,
                        packetsPerSec,
                        &repairReason);
                if (repairedIps > 0) {
                    const char* suppressReason = nullptr;
                    const bool suppress = suppressMeasuredServiceIntervalRepair(
                            ctx, nowMs, elapsedMs, appInBytesPerSec, expectedBps, &suppressReason);
                    if (suppress) {
                        noteSuppressedMeasuredServiceIntervalRepair(
                                ctx, "stats", suppressReason, repairReason,
                                ctx->serviceIntervalsPerSecond, repairedIps, elapsedMs,
                                appInBytesPerSec, usbOutBytesPerSec, packetsPerSec, expectedBps);
                    } else if (applyMeasuredServiceIntervalRepair(ctx, repairedIps, repairReason)) {
                        ctx->serviceIntervalAutoRepairDone.store(true, std::memory_order_release);
                    }
                }
            }

            // ===== PI 自适应速率控制（feedback 失效时激活）=====

            // 核心策略：单向限速

            //   - 缓冲区高 → 允许降速（correction > 0），让 DAC 慢慢消化

            //   - 缓冲区低 → 禁止提速（correction = 0），维持标称速率，靠补静音解决

            //   - Android App 供给极不稳定，提速会导致 DAC 饥饿爆音

            if (ctx->adaptiveRate.active && !ctx->sourceDsdSession) {
                size_t bufSize = ctx->pcmRingBuffer.size();
                double fillRatio = (bufSize > 0) ? (double)bufUsed / (double)bufSize : 0.0;
                double error = fillRatio - ctx->adaptiveRate.targetFillRatio;

                // 死区：偏差过小时不修正，避免不必要的抖动

                if (std::fabs(error) < ctx->adaptiveRate.deadZone) {
                    error = 0.0;
                    ctx->adaptiveRate.integralError *= 0.99;  // 缓慢衰减
                } else if (error < 0.0) {
                    // 缓冲区低：清空负向积分，防止负积累导致后续提速

                    ctx->adaptiveRate.integralError = 0.0;
                } else {
                    // 缓冲区高：正常累积积分（带 anti-windup 限幅）

                    ctx->adaptiveRate.integralError += error;
                    ctx->adaptiveRate.integralError = std::min(1.0, ctx->adaptiveRate.integralError);
                }

                // PI 输出

                double correction = ctx->adaptiveRate.Kp * error
                                    + ctx->adaptiveRate.Ki * ctx->adaptiveRate.integralError;

                // 单向限制：只允许降速（correction > 0），禁止提速（correction < 0）

                if (correction < 0.0) {
                    correction = 0.0;
                }

                // 限制最大修正幅度（防止过调）

                correction = std::min(correction, ctx->adaptiveRate.maxCorrection);
                ctx->adaptiveRate.correction = correction;

                // 应用到 Pacer：只允许降低发送速率（让 DAC 消化缓冲区）

                ctx->isoPacer.setSampleRate((double)ctx->nominalSampleRate * (1.0 + correction));

                double pacerRate = (double)(ctx->isoPacer.sampleRateQ32 / 4294967296.0);
                LOGI("AdaptiveRate: target=%.1f%% actual=%.1f%% err=%.4f integral=%.4f corr=%.6f%% rate=%.2f",
                     ctx->adaptiveRate.targetFillRatio * 100.0, fillRatio * 100.0,
                     error, ctx->adaptiveRate.integralError,
                     correction * 100.0, pacerRate);
            }

            int fbState = ctx->feedbackState.load(std::memory_order_relaxed);
            int fbSampleRateMilli = ctx->feedbackSampleRateMilli.load(std::memory_order_relaxed);
            LOGI("Stats: appInBytesPerSec=%.0f completedUsbBytesPerSec=%.0f scheduledUsbBytesPerSec=%.0f "
                 "expectedBytesPerSec=%llu schedulerBytesPerSec=%llu avgCompletedPacketBytes=%.3f "
                 "packetsPerSec=%d callbacksPerSec=%d buf=%zu/%zu underrun=%d submitErr=%d pktErr=%d xferErr=%d "
                 "fb=%.3f fbState=%s fbSampleRate=%.3f pacing=%s",
                 appInBytesPerSec, usbOutBytesPerSec, scheduledUsbBytesPerSec,
                 (unsigned long long)expectedBps, (unsigned long long)smoothBps,
                 avgPacketBytes, packetsPerSec, callbacksPerSec,
                 bufUsed, ctx->pcmRingBuffer.size(),
                 underruns, submitErrs, pktErrs, xferErrs,
                 fbRate, feedbackStateName(fbState), (double)fbSampleRateMilli / 1000.0,
                 pacingModeName(ctx->pacingMode.load(std::memory_order_relaxed)));
        }
    }

    // UAC20-tested steady-state ownership: refill and re-arm the completed URB
    // immediately from the libusb completion owner.  Keeping the endpoint queue
    // continuously populated is more important than moving this small amount of
    // work to another userspace thread; the old worker hop allowed scheduledBps
    // itself to collapse while PCM buffers remained full.
    if (!resubmitIsoDirect(ctx, xfer, index)) {
        if (ctx->streaming.load(std::memory_order_acquire) &&
            !ctx->stopping.load(std::memory_order_acquire) &&
            !ctx->transportLost.load(std::memory_order_acquire)) {
            ctx->statsSubmitError.fetch_add(1, std::memory_order_relaxed);
            ctx->sessionBroken.store(true, std::memory_order_release);
            ctx->stopRequested.store(true, std::memory_order_release);
            ctx->acceptingWrites.store(false, std::memory_order_release);
            setUsbStreamState(ctx, UsbStreamState::BROKEN, "iso_direct_resubmit_failed");
            LOGE("ISO direct resubmit failed while streaming: index=%d", index);
        }
        if (pendingTrackBoundarySeq != 0) {
            std::lock_guard<std::mutex> lock(ctx->trackBoundaryMutex);
            ctx->trackBoundaryCV.notify_all();
        }
        return;
    }
    if (pendingTrackBoundarySeq != 0) {
        ctx->trackBoundaryAppliedSeq.store(
                pendingTrackBoundarySeq, std::memory_order_release);
        std::lock_guard<std::mutex> lock(ctx->trackBoundaryMutex);
        ctx->trackBoundaryCV.notify_all();
    }
}

static bool resubmitIsoDirect(
        UsbAudioContext* ctx,
        struct libusb_transfer* xfer,
        int index) {
    if (!ctx || !xfer || index < 0 || index >= NUM_TRANSFERS) return false;

    const UsbSubmitEligibilityInput submitEligibility{
            ctx->submitOwner.accepting.load(std::memory_order_acquire),
            ctx->streaming.load(std::memory_order_acquire),
            ctx->stopping.load(std::memory_order_acquire),
            ctx->closing.load(std::memory_order_acquire),
            ctx->transportLost.load(std::memory_order_acquire),
            ctx->sessionBroken.load(std::memory_order_acquire),
    };
    if (!canSubmitUsbJob(submitEligibility)) return false;

    // This mutex is normally uncontended because libusb invokes the OUT callbacks
    // from the single event owner. Warm-track ring/pacer cuts are callback-owned now;
    // this mutex remains the stop/close vs direct-resubmit ownership boundary.
    std::lock_guard<std::mutex> submitLock(ctx->submitOwner.mutex());
    if (!ctx->submitOwner.accepting.load(std::memory_order_relaxed) ||
        !ctx->streaming.load(std::memory_order_relaxed) ||
        ctx->stopping.load(std::memory_order_relaxed) ||
        ctx->closing.load(std::memory_order_relaxed) ||
        ctx->transportLost.load(std::memory_order_relaxed) ||
        ctx->sessionBroken.load(std::memory_order_relaxed)) {
        return false;
    }

    fillIsoTransfer(ctx, xfer, index);
    // Publish ownership before submit; an immediately serviced event callback
    // must never observe zero pending ownership for a live transfer.
    ctx->pendingTransfers.fetch_add(1, std::memory_order_acq_rel);
    const int rc = libusb_submit_transfer(xfer);
    if (rc < 0) {
        ctx->pendingTransfers.fetch_sub(1, std::memory_order_acq_rel);
        ctx->statsSubmitError.fetch_add(1, std::memory_order_relaxed);
        LOGE("direct ISO resubmit failed: index=%d rc=%d(%s)",
             index, rc, libusb_error_name(rc));
        if (rc == LIBUSB_ERROR_IO ||
            rc == LIBUSB_ERROR_NO_DEVICE ||
            rc == LIBUSB_ERROR_NOT_FOUND ||
            rc == LIBUSB_ERROR_OTHER) {
            markUsbTransportLost(ctx, "direct ISO resubmit", index, rc);
        }
        return false;
    }
    recordIsoSubmitDiagnostics(ctx, xfer);
    return true;
}

static bool resubmitFeedbackDirect(UsbAudioContext* ctx) {
    if (!ctx || !ctx->feedbackTransfer || ctx->feedbackEpAddress == 0 || ctx->feedbackDegraded) {
        return false;
    }
    if (!ctx->submitOwner.accepting.load(std::memory_order_acquire) ||
        !ctx->streaming.load(std::memory_order_acquire) ||
        ctx->stopping.load(std::memory_order_acquire) ||
        ctx->closing.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire)) {
        return false;
    }

    ctx->pendingFeedbackTransfers.fetch_add(1, std::memory_order_acq_rel);
    const int rc = libusb_submit_transfer(ctx->feedbackTransfer);
    if (rc < 0) {
        ctx->pendingFeedbackTransfers.fetch_sub(1, std::memory_order_acq_rel);
        LOGW("direct feedback resubmit failed: rc=%d(%s)", rc, libusb_error_name(rc));
        if (rc == LIBUSB_ERROR_NO_DEVICE) {
            markUsbTransportLost(ctx, "direct feedback resubmit", -1, rc);
        }
        return false;
    }
    return true;
}

// ==========================
// USB event owner: normal scheduler with best-effort nice/affinity; no direct FIFO request

// ==========================
static void setRealtimePriority(int prio) {
    (void) prio;
    // Clean-room alignment with the observed scheduling behaviour:
    // the ordinary pthread event owner never promotes itself to SCHED_FIFO.
    // The native layer only *observes* a realtime policy after Android/OpenSL created the
    // callback thread. Requesting FIFO priority 30 from an app-owned pthread can
    // starve SurfaceFlinger/InputDispatcher on permissive OEM kernels and was
    // observed as a device-wide UI freeze immediately after USB permission.
    // Keep only best-effort nice/affinity/timer-slack tuning here. The optional
    // OpenSL bootstrap has its own build flag and remains disabled by default.
    const auto schedule = rawsmusic::usb::applyUsbThreadScheduling(
            "USB event", -20, false);
    int actualPolicy = SCHED_OTHER;
    sched_param actual{};
    const int queryRc = pthread_getschedparam(pthread_self(), &actualPolicy, &actual);
    LOGI("USB_EVENT_THREAD_SCHED %s directRtRequest=0 queryRc=%d actualPolicy=%s(%d) actualPrio=%d",
         rawsmusic::usb::formatUsbThreadScheduleSnapshot(schedule).c_str(),
         queryRc,
         rawsmusic::usb::linuxSchedulerPolicyName(actualPolicy),
         actualPolicy,
         actual.sched_priority);
}

static void LIBUSB_CALL usbPollFdAdded(int fd, short events, void* userData) {
    auto* ctx = reinterpret_cast<UsbAudioContext*>(userData);
    if (!ctx || fd < 0) return;
    const int epollFd = ctx->eventEpollFd.load(std::memory_order_acquire);
    if (epollFd < 0) return;
    epoll_event event{};
    event.events = rawsmusic::usb::mapUsbPollEventsToEpoll(events);
    event.data.fd = fd;
    if (epoll_ctl(epollFd, EPOLL_CTL_ADD, fd, &event) != 0 && errno != EEXIST) {
        LOGW("USB_EPOLL add failed fd=%d errno=%d(%s)", fd, errno, strerror(errno));
    }
}

static void LIBUSB_CALL usbPollFdRemoved(int fd, void* userData) {
    auto* ctx = reinterpret_cast<UsbAudioContext*>(userData);
    if (!ctx || fd < 0) return;
    const int epollFd = ctx->eventEpollFd.load(std::memory_order_acquire);
    if (epollFd >= 0 && epoll_ctl(epollFd, EPOLL_CTL_DEL, fd, nullptr) != 0 &&
        errno != ENOENT && errno != EBADF) {
        LOGW("USB_EPOLL remove failed fd=%d errno=%d(%s)", fd, errno, strerror(errno));
    }
}

static void eventLoop(UsbAudioContext *ctx) {
    setRealtimePriority(30);
    ctx->eventEpollFd.store(-1, std::memory_order_release);

    // The event owner calls libusb directly and uses
    // libusb_handle_events_timeout() with a one-millisecond timeout.  Do not
    // put an additional epoll_wait(1000 ms) in front of libusb.  On ColorOS 16
    // that outer wait can stop delivering the usbfs pollfd while the activity
    // is backgrounded even though the process remains PROC_STATE_FGS and in
    // /cpu/foreground.  Periodic libusb servicing also matches the measured
    // The target scheduler profile is roughly 1,000+ switches/s versus the older ~35/s.
    LOGI("libusb event loop started (mode=uapp_1ms_timeout nice=-20 owner=libusb-callback transfers=%d pktsPerXfer=%d pool=%d kernelBufCap=%.1fs)",
         NUM_TRANSFERS, ctx->numIsoPackets, currentTransferPoolCap(ctx),
         (double)currentTransferPoolCap(ctx) * ctx->numIsoPackets /
         (double)std::max(1, ctx->serviceIntervalsPerSecond));

    auto lastLoopTime = std::chrono::steady_clock::now();
    int consecutiveEventErrors = 0;
    while (ctx->eventThreadRunning.load(std::memory_order_acquire)) {
        timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = 1000;

        const int r = libusb_handle_events_timeout(ctx->libusbCtx, &tv);
        if (r == LIBUSB_ERROR_INTERRUPTED) continue;
        if (r == LIBUSB_ERROR_NO_DEVICE) break;
        if (r < 0) {
            ++consecutiveEventErrors;
            if (consecutiveEventErrors == 1 || (consecutiveEventErrors % 100) == 0) {
                LOGW("UAPP 1ms libusb event error: r=%d consecutive=%d",
                     r, consecutiveEventErrors);
            }
        } else {
            consecutiveEventErrors = 0;
        }

        // Scheduling gap detector. Large foreground/background transitions can
        // delay this event thread; the next completion batch must not rewrite
        // USB service interval pacing.
        auto now = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - lastLoopTime).count();
        if (elapsed > 100 && ctx->streaming.load(std::memory_order_relaxed)) {
            ctx->lastEventLoopGapMs.store(nowSteadyMs(), std::memory_order_release);
            ctx->lastEventLoopGapDurationMs.store((int64_t)elapsed, std::memory_order_release);
            // A large scheduler gap invalidates the current stats window.  The
            // next callback batch may look like a 1000/s endpoint even though it
            // is only Android delivering several seconds of completions late.
            ctx->serviceIntervalAutoRepairDone.store(true, std::memory_order_release);
            size_t bufUsed = ringAvailable(ctx);
            int pendingXfers = ctx->pendingTransfers.load(std::memory_order_relaxed);
            LOGW("EVENT LOOP GAP: %lld ms! bufUsed=%zu/%zu pendingXfers=%d kernelBufMs=%.0f",
                 (long long)elapsed, bufUsed, ctx->pcmRingBuffer.size(),
                 pendingXfers, (double)pendingXfers * ctx->numIsoPackets / 8.0);

            if (elapsed > 250) {
                const int recoveryMs = 120;
                int recoveryBytes = ctx->clock.deviceBytesPerSecond * recoveryMs / 1000;
                if (recoveryBytes < ctx->deviceBytesPerFrame * 256) {
                    recoveryBytes = ctx->deviceBytesPerFrame * 256;
                }
                ctx->starved = true;
                ctx->starvedRecoveryBytes = recoveryBytes;
                ctx->consecutiveEmptyTransfers = 0;

                int fadeSamples = 0;
                const UsbTransitionGainOwner gainOwner = getTransitionGainOwner(ctx);
                if (usesSessionPcmTransitionEnvelope(ctx)) {
                    ctx->sessionVolumeCurrent.store(0.0f, std::memory_order_release);
                    armSessionEnvelopeInternal(ctx, 1.0f, 30);
                    ctx->fadeSamplesRemaining = 0;
                    ctx->fadeTotalSamples = 0;
                    LOGW("USB starvation recovery armed: reason=event_loop_gap recoveryBytes=%d owner=SessionPcm fadeMs=30 bufUsed=%zu",
                         recoveryBytes, bufUsed);
                } else if (transitionOwnerUsesLegacyStartupFade(gainOwner) &&
                           !isHardwareVolumePcmUnityPath(ctx) &&
                           !isStrictBitPerfectPcmPath(ctx) &&
                           !ctx->dsdSession) {
                    fadeSamples = ctx->sampleRate * 30 / 1000 * ctx->deviceChannels;
                    if (fadeSamples < 64) fadeSamples = 64;
                    ctx->fadeSamplesRemaining = fadeSamples;
                    ctx->fadeTotalSamples = fadeSamples;
                    LOGW("USB starvation recovery armed: reason=event_loop_gap recoveryBytes=%d owner=Legacy fadeSamples=%d bufUsed=%zu",
                         recoveryBytes, fadeSamples, bufUsed);
                } else {
                    forceSessionEnvelopeUnity(ctx);
                    ctx->fadeSamplesRemaining = 0;
                    ctx->fadeTotalSamples = 0;
                    LOGW("USB starvation recovery armed: reason=event_loop_gap recoveryBytes=%d fade bypassed owner=%s bufUsed=%zu",
                         recoveryBytes, transitionGainOwnerName(gainOwner), bufUsed);
                }
            }
        }
        lastLoopTime = now;
    }

    LOGI("libusb event loop exited (mode=uapp_1ms_timeout errors=%d)",
         consecutiveEventErrors);
}
static void eventLoopFromAudioCarrier(void* context) {
    eventLoop(static_cast<UsbAudioContext*>(context));
}

// 初始化重采样上下文：返回 0 成功，负值失败

static int initSwrContext(UsbAudioContext *ctx) {
    if (!ctx) return -1;

    // 先清理旧上下文

    if (ctx->swrCtx) {
        swr_free(&ctx->swrCtx);
        ctx->swrCtx = nullptr;
    }
    ctx->needsResample = false;

    // 判断是否需要重采样

    // 注意：位深转换由 PCM adapter 处理，swresample 负责采样率和声道转换

    bool rateChange = (ctx->sourceSampleRate != ctx->sampleRate);
    bool formatChange = (ctx->sourceChannels != ctx->deviceChannels);

    if (!rateChange && !formatChange) {
        LOGI("initSwrContext: no resampling needed (rate=%d, format matches)", ctx->sourceSampleRate);
        return 0;
    }

    UsbSwrContextSpec spec{
        ctx->sourceSampleRate,
        ctx->sourceChannels,
        ctx->sourceBitDepth,
        ctx->sourceBytesPerSample,
        ctx->sampleRate,
        ctx->deviceChannels,
    };
    std::size_t outputBufferSize = 0;
    const int ret = createUsbSwrContext(spec, &ctx->swrCtx, &outputBufferSize);
    if (ret == -2) {
        LOGE("initSwrContext: swr_alloc_set_opts failed");
        return ret;
    }
    if (ret < 0) {
        LOGE("initSwrContext: swr_init failed: %d", ret);
        return ret;
    }

    ctx->needsResample = true;

    // Pre-allocate 4 seconds of device-rate data in the swr output container,
    // not in the final USB device container.
    ctx->swrOutBufferSize = outputBufferSize;
    ctx->swrOutBuffer.resize(ctx->swrOutBufferSize);

    LOGI("initSwrContext: enabled resampling %dHz/%dch/%dbit/%dB -> "
         "%dHz/%dch source-container=%dbit/%dB, device=%dbit/subslot%d",
         ctx->sourceSampleRate, ctx->sourceChannels, ctx->sourceBitDepth, ctx->sourceBytesPerSample,
         ctx->sampleRate, ctx->deviceChannels, ctx->sourceBitDepth, ctx->sourceBytesPerSample,
         ctx->deviceBitDepth, ctx->deviceSubslotSize);
    return 0;
}

static void closeSwrContext(UsbAudioContext *ctx) {
    if (ctx && ctx->swrCtx) {
        swr_free(&ctx->swrCtx);
        ctx->swrCtx = nullptr;
        ctx->needsResample = false;
        ctx->swrOutBuffer.clear();
        ctx->swrOutBufferSize = 0;
        LOGI("closeSwrContext: resampler released");
    }
}

// ==========================
// 从配置描述符解析音频接口候选并评分

// ==========================
static bool parseAudioInterfaceFromConfig(
        const uint8_t *configDesc,
        int configLen,
        int targetSampleRate,
        int targetChannels,
        int targetBitDepth,
        int targetSubslotSize,
        int javaHintIface,
        int javaHintAlt,
        bool bitPerfect,
        bool isFullSpeed,
        bool force1MsPacket,
        const UsbSessionRequest& sessionRequest,
        AudioStreamCandidate &outBest,
        std::vector<AudioStreamCandidate>* outAllCandidates = nullptr,
        bool forceDop24bit = false,
        int dopSampleRate = 0,
        bool forceNativeDsdRaw = false,
        int nativeDsdSampleRate = 0
) {
    std::vector<AudioStreamCandidate> candidates;
    std::set<uint32_t> scannedIfaceAlts;  // 跟踪所有已扫描的 iface+alt（包括被拒绝的）
    int pos = 0;
    while (pos + 2 < configLen) {
        uint8_t bLength = configDesc[pos];
        uint8_t bDescriptorType = configDesc[pos + 1];
        if (bLength < 2 || pos + bLength > configLen) {
            break;
        }
        if (bDescriptorType == LIBUSB_DT_INTERFACE &&
            bLength >= LIBUSB_DT_INTERFACE_SIZE) {
            uint8_t bInterfaceNumber = configDesc[pos + 2];
            uint8_t bAlternateSetting = configDesc[pos + 3];
            uint8_t bNumEndpoints = configDesc[pos + 4];
            uint8_t bInterfaceClass = configDesc[pos + 5];
            uint8_t bInterfaceSubClass = configDesc[pos + 6];
            if (bInterfaceClass == LIBUSB_CLASS_AUDIO &&
                bInterfaceSubClass == 0x02) { // AUDIOSTREAMING
                if (bAlternateSetting == 0 || bNumEndpoints == 0) {
                    pos += bLength;
                    continue;
                }
                // 去重：跳过已经扫描过的相同 iface+alt（设备描述符可能有重复条目）
                uint32_t key = ((uint32_t)bInterfaceNumber << 8) | bAlternateSetting;
                if (scannedIfaceAlts.count(key)) {
                    pos += bLength;
                    continue;
                }
                scannedIfaceAlts.insert(key);
                LOGI("Found Audio Streaming: iface=%d alt=%d endpoints=%d",
                     bInterfaceNumber, bAlternateSetting, bNumEndpoints);
                int nextIfacePos = pos + bLength;
                int endPos = configLen;
                for (int scan = nextIfacePos; scan + 2 < configLen; ) {
                    uint8_t sLen = configDesc[scan];
                    uint8_t sType = configDesc[scan + 1];
                    if (sLen < 2 || scan + sLen > configLen) break;
                    if (sType == LIBUSB_DT_INTERFACE) {
                        endPos = scan;
                        break;
                    }
                    scan += sLen;
                }
                AudioStreamCandidate c;
                c.iface = bInterfaceNumber;
                c.alt = bAlternateSetting;
                // ---------- 扫描 CS_INTERFACE / CS_ENDPOINT / ENDPOINT ----------
                for (int scan = nextIfacePos; scan + 2 < endPos; ) {
                    uint8_t dLen = configDesc[scan];
                    uint8_t dType = configDesc[scan + 1];
                    if (dLen < 2 || scan + dLen > endPos) break;
                    // CS_INTERFACE
                    if (dType == 0x24 && dLen >= 3) {
                        uint8_t subtype = configDesc[scan + 2];
                        // AS_GENERAL
                        if (subtype == 0x01) {
                            if (dLen >= 4) {
                                c.terminalLink = configDesc[scan + 3];
                            }
                            if (dLen >= 16) {
                                c.protocol = USB_AUDIO_UAC2;
                                uint8_t formatType = configDesc[scan + 5];
                                uint32_t bmFormats =
                                        ((uint32_t)configDesc[scan + 6]) |
                                        ((uint32_t)configDesc[scan + 7] << 8) |
                                        ((uint32_t)configDesc[scan + 8] << 16) |
                                        ((uint32_t)configDesc[scan + 9] << 24);
                                c.isPCM = (bmFormats & 0x00000001) != 0;
                                c.isRawData = (bmFormats & UAC2_FORMAT_TYPE_I_RAW_DATA) != 0;
                                c.channels = configDesc[scan + 10];
                                LOGI("  Alt%d AS_GENERAL(UAC2): termLink=%d formatType=%d bmFormats=0x%08X channels=%d PCM=%d RAW=%d",
                                     c.alt, c.terminalLink, formatType, bmFormats, c.channels,
                                     c.isPCM ? 1 : 0, c.isRawData ? 1 : 0);
                            } else if (dLen >= 7) {
                                c.protocol = USB_AUDIO_UAC1;
                                uint16_t wFormatTag = readU16Le(&configDesc[scan + 5]);
                                c.isPCM = (wFormatTag == 0x0001);
                                LOGI("  Alt%d AS_GENERAL(UAC1): termLink=%d formatTag=0x%04X PCM=%d",
                                     c.alt, c.terminalLink, wFormatTag, c.isPCM ? 1 : 0);
                            }
                        }
                            // FORMAT_TYPE
                        else if (subtype == 0x02) {
                            uint8_t formatType = dLen >= 4 ? configDesc[scan + 3] : 0;
                            if (c.protocol == USB_AUDIO_UAC1) {
                                if (dLen >= 8) {
                                    const uint8_t samFreqType = configDesc[scan + 7];
                                    const bool parsedTypeI = formatType == 0x01 &&
                                            parseUac1TypeIFormatDescriptor(&configDesc[scan], dLen, c);
                                    if (!parsedTypeI) {
                                        // Keep geometry visibility for non-Type-I descriptors, but do
                                        // not manufacture a rate declaration from a layout we did not parse.
                                        c.channels = configDesc[scan + 4];
                                        c.subslotSize = configDesc[scan + 5];
                                        c.bitResolution = configDesc[scan + 6];
                                    }
                                    LOGI("  Alt%d FORMAT_TYPE(UAC1): len=%d type=%d channels=%d subframe=%d bits=%d "
                                         "bSamFreqType=%u rates=%s",
                                         c.alt,
                                         dLen,
                                         formatType,
                                         c.channels,
                                         c.subslotSize,
                                         c.bitResolution,
                                         samFreqType,
                                         rateListToString(c).c_str());
                                }
                            } else {
                                // 默认UAC2 解析
                                c.protocol = USB_AUDIO_UAC2;
                                if (dLen >= 6) {
                                    c.subslotSize = configDesc[scan + 4];
                                    c.bitResolution = configDesc[scan + 5];
                                    LOGI("  Alt%d FORMAT_TYPE(UAC2): type=%d subslot=%d bits=%d",
                                         c.alt, formatType, c.subslotSize, c.bitResolution);
                                }
                            }
                        }
                    }
                        // Standard Endpoint
                    else if (dType == LIBUSB_DT_ENDPOINT && dLen >= 7) {
                        uint8_t epAddr = configDesc[scan + 2];
                        uint8_t bmAttr = configDesc[scan + 3];
                        uint16_t wMaxPkt = readU16Le(&configDesc[scan + 4]);
                        uint8_t bInterval = configDesc[scan + 6];
                        bool isIso = (bmAttr & 0x03) == LIBUSB_TRANSFER_TYPE_ISOCHRONOUS;
                        bool isOut = (epAddr & LIBUSB_ENDPOINT_IN) == 0;
                        bool isIn = (epAddr & LIBUSB_ENDPOINT_IN) != 0;
                        uint8_t transferType = bmAttr & 0x03;
                        uint8_t syncType = (bmAttr >> 2) & 0x03;
                        uint8_t usageType = (bmAttr >> 4) & 0x03;
                        int maxPacketBase = wMaxPkt & 0x07FF;
                        int transactions = ((wMaxPkt >> 11) & 0x03) + 1;
                        int pktSize = maxPacketBase * transactions;
                        LOGI("  Endpoint 0x%02X: bmAttr=0x%02X transfer=%u sync=%u usage=%u maxPktRaw=0x%04X base=%d transactions=%d bInterval=%u",
                             epAddr,
                             bmAttr,
                             transferType,
                             syncType,
                             usageType,
                             wMaxPkt,
                             maxPacketBase,
                             transactions,
                             bInterval);
                        if (isIso && isOut) {
                            c.epAddress = epAddr;
                            c.maxPacketSize = pktSize;
                            c.bInterval = bInterval;
                            c.outSyncType = syncType;
                            c.outUsageType = usageType;
                            LOGI("    Candidate OUT: iface=%d alt=%d ep=0x%02X pkt=%d bInterval=%d sync=%s usage=%s",
                                 c.iface, c.alt, c.epAddress, c.maxPacketSize, c.bInterval,
                                 syncTypeName(c.outSyncType), usageTypeName(c.outUsageType));
                        }
                        if (isIso && isIn) {
                            // explicit feedback endpoint
                            // usage type 1 = feedback endpoint; some devices advertise a tiny IN EP without usage=feedback.
                            if (usageType == 1 || maxPacketBase <= 4) {
                                c.feedbackEpAddress = epAddr;
                                c.fbUsageType = usageType;
                                c.feedbackMaxPacketSize = maxPacketBase;
                                c.feedbackBInterval = bInterval;
                                LOGI("    Feedback endpoint detected: ep=0x%02X usage=%s maxPkt=%d bInterval=%u",
                                     epAddr, usageTypeName(usageType), maxPacketBase, bInterval);
                            } else {
                                LOGI("    Skip capture IN endpoint for playback: iface=%d alt=%d termLink=0x%02X ep=0x%02X",
                                     c.iface, c.alt, c.terminalLink, epAddr);
                            }
                        }
                    }
                        // CS_ENDPOINT
                    else if (dType == 0x25 && dLen >= 4) {
                        uint8_t epSubtype = configDesc[scan + 2];
                        if (epSubtype == 0x01) { // EP_GENERAL
                            uint8_t bmAttributes = configDesc[scan + 3];
                            // UAC1: bit0 = Sampling Frequency Control
                            if (c.protocol == USB_AUDIO_UAC1) {
                                c.uac1EpHasSamplingFreqControl = (bmAttributes & 0x01) != 0;
                                LOGI("  CS_ENDPOINT(UAC1): bmAttributes=0x%02X sampleFreqControl=%d",
                                     bmAttributes, c.uac1EpHasSamplingFreqControl ? 1 : 0);
                            }
                        }
                    }
                    scan += dLen;
                }
                const bool nativeDsdRequested = forceNativeDsdRaw && nativeDsdSampleRate > 0;
                const bool candidateTransportOk =
                        c.isPCM || (nativeDsdRequested && c.isRawData);
                if (c.epAddress != 0 && c.maxPacketSize > 0 && candidateTransportOk) {
                    // Bit-perfect 模式下：允许无损整数扩展（低位补0），不允许降采样/浮点/声道变化
                    if (nativeDsdRequested) {
                        const bool rateOk = streamSupportsRate(
                                c,
                                nativeDsdSampleRate > 0 ? nativeDsdSampleRate : targetSampleRate
                        );
                        const bool formatOk =
                                c.channels == targetChannels &&
                                c.bitResolution == targetBitDepth &&
                                c.subslotSize == targetSubslotSize;
                        if (!c.isRawData || !formatOk || !rateOk) {
                            LOGD("    Reject native DSD candidate: iface=%d alt=%d raw=%d ch=%d bits=%d subslot=%d rates=%s "
                                 "target=%dch/%dbit/subslot%d rate=%d formatOk=%d rateOk=%d",
                                 c.iface,
                                 c.alt,
                                 c.isRawData ? 1 : 0,
                                 c.channels,
                                 c.bitResolution,
                                 c.subslotSize,
                                 rateListToString(c).c_str(),
                                 targetChannels,
                                 targetBitDepth,
                                 targetSubslotSize,
                                 nativeDsdSampleRate > 0 ? nativeDsdSampleRate : targetSampleRate,
                                 formatOk ? 1 : 0,
                                 rateOk ? 1 : 0);
                            continue;
                        }
                        LOGI("    Accept native DSD candidate: iface=%d alt=%d device=%dch/%dbit/subslot%d rate=%d RAW=%d",
                             c.iface,
                             c.alt,
                             c.channels,
                             c.bitResolution,
                             c.subslotSize,
                             nativeDsdSampleRate > 0 ? nativeDsdSampleRate : targetSampleRate,
                             c.isRawData ? 1 : 0);
                    } else if (bitPerfect) {
                        // FFmpeg 解码器对 24bit/32bit 统一输出 S32LE (4B/sample)
                        // 必须用解码器实际输出格式来评估候选，而非源文件格式
                        const PcmDecoderFormat decoderFormat =
                                decoderPcmFormatForTargetBitDepth(targetBitDepth);
                        PcmFormatAdapter adapter = choosePcmAdapter(
                                targetChannels,
                                decoderFormat.bitDepth,
                                decoderFormat.bytesPerSample,
                                c.channels,
                                c.bitResolution,
                                c.subslotSize,
                                true
                        );
                        bool rateOk = streamCanProveExactRate(c, targetSampleRate);
                        if (adapter == PCM_ADAPTER_UNSUPPORTED || !rateOk) {
                            LOGD("    Reject by bit-perfect: iface=%d alt=%d ch=%d bits=%d subslot=%d rates=%s "
                                 "target=%dch/%dbit/%dHz adapter=%s rateOk=%d",
                                 c.iface,
                                 c.alt,
                                 c.channels,
                                 c.bitResolution,
                                 c.subslotSize,
                                 rateListToString(c).c_str(),
                                 targetChannels,
                                 targetBitDepth,
                                 targetSampleRate,
                                 pcmAdapterName(adapter),
                                 rateOk ? 1 : 0);
                            continue;
                        }
                        LOGI("    Accept by bit-perfect: iface=%d alt=%d device=%dch/%dbit/subslot%d "
                             "target=%dch/%dbit adapter=%s",
                             c.iface,
                             c.alt,
                             c.channels,
                             c.bitResolution,
                             c.subslotSize,
                             targetChannels,
                             targetBitDepth,
                             pcmAdapterName(adapter));
                    }
                    // stream profile timing：选择候选时必须使用真实 USB service
                    // interval (FS frame or HS/SS microframe group), not a hard-coded /1000 value.
                    const int requestedProfileRate =
                            nativeDsdRequested && nativeDsdSampleRate > 0
                            ? nativeDsdSampleRate
                            : ((forceDop24bit && dopSampleRate > 0) ? dopSampleRate : targetSampleRate);
                    int profileRate = requestedProfileRate;
                    c.selectedSampleRate = requestedProfileRate;
                    if (c.protocol == USB_AUDIO_UAC1 && !streamCanSafelyUseRate(c, requestedProfileRate)) {
                        const bool exactTransport = bitPerfect || nativeDsdRequested || forceDop24bit;
                        const bool allowUnknownFixedCompat =
                                streamCanUseUnverifiedUac1FixedRateCompat(c, exactTransport);
                        if (allowUnknownFixedCompat) {
                            // UAPP's UAC1 endpoint requestSampleRate() explicitly returns success
                            // when a fixed endpoint has no Sampling Frequency Control. Some older
                            // devices also omit the rate list, so ordinary PCM must be allowed to
                            // try the endpoint instead of failing exclusive mode at descriptor time.
                            // STRICT/DoP/Native-DSD remain proof-only and never take this path.
                            profileRate = requestedProfileRate;
                            c.selectedSampleRate = requestedProfileRate;
                            c.riskFlags |= PROFILE_RISK_CLOCK_UNVERIFIED;
                            LOGW("    UAC1_UNVERIFIED_FIXED_COMPAT iface=%d alt=%d requested=%d "
                                 "rates=%s samplingFreqControl=0 exactTransport=0",
                                 c.iface, c.alt, requestedProfileRate, rateListToString(c).c_str());
                        } else {
                            const bool mayResampleToAdvertisedPcmRate =
                                    !exactTransport && streamHasDeclaredRate(c);
                            const int fallbackRate = mayResampleToAdvertisedPcmRate
                                    ? nearestDeclaredStreamRate(c, requestedProfileRate)
                                    : 0;
                            if (fallbackRate <= 0) {
                                LOGW("    Reject unsafe UAC1 rate: iface=%d alt=%d target=%d rates=%s "
                                     "samplingFreqControl=%d bitPerfect=%d",
                                     c.iface, c.alt, requestedProfileRate, rateListToString(c).c_str(),
                                     c.uac1EpHasSamplingFreqControl ? 1 : 0, bitPerfect ? 1 : 0);
                                continue;
                            }
                            profileRate = fallbackRate;
                            c.selectedSampleRate = fallbackRate;
                            LOGW("    UAC1 candidate rate fallback: iface=%d alt=%d requested=%d -> device=%d "
                                 "rates=%s; PCM resampler will bridge source/device clocks",
                                 c.iface, c.alt, requestedProfileRate, fallbackRate,
                                 rateListToString(c).c_str());
                        }
                    }
                    const int frameSize = c.channels * c.subslotSize;  // device format
                    const int intervalsPerSecond = computeIsoServiceIntervalsPerSecond(
                            isFullSpeed,
                            c.bInterval,
                            force1MsPacket
                    );
                    const uint64_t requiredBps64 = (uint64_t)profileRate * (uint64_t)frameSize;
                    const int expectedAvgBytesPerInterval = ceilDivU64(requiredBps64, (uint64_t)intervalsPerSecond);
                    c.frameBytes = frameSize;
                    c.serviceIntervalsPerSecond = intervalsPerSecond;
                    c.requiredBytesPerSecond = requiredBps64 > (uint64_t)INT_MAX ? INT_MAX : (int)requiredBps64;
                    c.nominalBytesPerInterval = expectedAvgBytesPerInterval;
                    const auto candidateGeometry = computeUsbTransferGeometry({
                            intervalsPerSecond,
                            c.feedbackEpAddress != 0 && c.fbUsageType == 1,
                    });
                    c.nominalBytesPerTransfer =
                            expectedAvgBytesPerInterval * candidateGeometry.isoPacketsPerTransfer;
                    c.capacityOk = c.maxPacketSize >= expectedAvgBytesPerInterval;
                    c.capacityRatioPermille = expectedAvgBytesPerInterval > 0
                                              ? (int)((int64_t)c.maxPacketSize * 1000LL / expectedAvgBytesPerInterval)
                                              : 0;
                    c.explicitFeedbackEligible = c.feedbackEpAddress != 0 && c.fbUsageType == 1;
                    if (!c.capacityOk) c.riskFlags |= PROFILE_RISK_LOW_CAPACITY;
                    if (c.outSyncType == 1 && c.feedbackEpAddress == 0) c.riskFlags |= PROFILE_RISK_ASYNC_WITHOUT_FEEDBACK;
                    if (c.feedbackEpAddress != 0 && !c.explicitFeedbackEligible) c.riskFlags |= PROFILE_RISK_FEEDBACK_NONSTANDARD;
                    if (!c.hasSampleRateList && !c.hasContinuousRate && c.protocol != USB_AUDIO_UAC2) c.riskFlags |= PROFILE_RISK_RATE_NOT_DECLARED;
                    if (c.protocol == USB_AUDIO_UAC2 && c.terminalLink != 0) c.riskFlags |= PROFILE_RISK_CLOCK_UNVERIFIED;
                    if (c.outSyncType == 0) c.riskFlags |= PROFILE_RISK_UNKNOWN_SYNC;

                    // ---------- 评分 ----------
                    int score = 0;
                    const UsbPcmOutputMode userMode = sanitizeUsbPcmOutputMode(sessionRequest.pcmOutputMode);
                    // FFmpeg/Android decoder carries >16-bit PCM as S32LE (4 bytes/sample).
                    // In AUTO, the USB device profile must follow the decoder container rather
                    // than a Java-side valid-bit hint such as 24bit/subslot3.  Packed24 is only
                    // preferred for an explicit PCM_24_PACKED request or DoP.
                    const bool decoderUsesS32Container =
                            !nativeDsdRequested &&
                            !forceDop24bit &&
                            targetBitDepth >= 24;
                    const bool autoPreferS32Container =
                            decoderUsesS32Container &&
                            !bitPerfect &&
                            userMode == UsbPcmOutputMode::AUTO;
                    const bool explicitPacked24Request =
                            forceDop24bit ||
                            userMode == UsbPcmOutputMode::PCM_24_PACKED;
                    const int autoSubslotHint =
                            autoPreferS32Container
                            ? 4
                            : ((targetSubslotSize > 0) ? targetSubslotSize : ((targetBitDepth > 16) ? 4 : 2));
                    // 适配权重：完全匹配 > 无损升位 > 不支持
                    if (nativeDsdRequested) {
                        if (c.isRawData) score += 6000;
                        else score -= 8000;
                    } else {
                        // FFmpeg 解码器对 24bit/32bit 统一输出 S32LE (4B/sample)
                        const PcmDecoderFormat decoderFormat =
                                decoderPcmFormatForTargetBitDepth(targetBitDepth);
                        PcmFormatAdapter scoreAdapter = choosePcmAdapter(
                                targetChannels,
                                decoderFormat.bitDepth,
                                decoderFormat.bytesPerSample,
                                c.channels,
                                c.bitResolution,
                                c.subslotSize,
                                false   // 评分不考虑 bitPerfect，所有候选用同一标准
                        );
                        score += scorePcmAdapter(scoreAdapter);
                    }
                    if (!nativeDsdRequested && c.isPCM) score += 1000;
                    if (streamSupportsRate(c, profileRate)) {
                        score += 1000;
                    } else {
                        score -= 2000;
                    }
                    if (c.channels == targetChannels) {
                        score += 800;
                    } else if (c.channels > targetChannels) {
                        score += 100;
                    } else {
                        score -= 500;
                    }
                    if (!nativeDsdRequested) {
                        // FFmpeg 解码器对 24bit/32bit 统一输出 S32LE (4B/sample)
                        const PcmDecoderFormat decoderFormat =
                                decoderPcmFormatForTargetBitDepth(targetBitDepth);
                        PcmFormatAdapter bitAdapter = choosePcmAdapter(
                                targetChannels,
                                decoderFormat.bitDepth,
                                decoderFormat.bytesPerSample,
                                c.channels,
                                c.bitResolution,
                                c.subslotSize,
                                false
                        );
                        if (c.bitResolution == targetBitDepth) {
                            // Bit-perfect 的目标是保持线上 valid bits，而不是让
                            // FFmpeg 的 S32LE 容器决定 USB 端点。只有在设备没有
                            // 精确端点时，才允许无损扩展到 32-bit container。
                            score += bitPerfect ? 2600 : 800;
                        } else if (c.bitResolution > targetBitDepth &&
                                   isSupportedPcmAdapter(bitAdapter)) {
                            score += 400; // 无损升位可接受
                        } else if (c.bitResolution > 0) {
                            score -= 800;
                        }
                    } else {
                        if (c.bitResolution == targetBitDepth) score += 1800;
                        else score -= 2500;
                    }
                    // 解码器输出 S32LE (4B/sample) for >16bit，所以 subslot=4 更匹配
                    if (c.subslotSize == autoSubslotHint) {
                        // Strict bit-perfect must prefer the requested wire
                        // container too (24-bit packed is 3 bytes/sample).
                        score += bitPerfect ? 900 : 300;
                    } else if (autoPreferS32Container && c.bitResolution == 24 && c.subslotSize == 3) {
                        score -= 300;
                        LOGI("    AUTO S32 source: subslot hint suppresses packed24 -300");
                    }
                    // 用户明确选择 USB 24bit 时，packed 24-bit alt 应优先于 32-bit container。
                    // FFmpeg 的 24bit 解码常以 S32LE 承载，native 会安全转成设备的 3-byte subslot。
                    // Packed 24-bit is a transport choice, not the AUTO default for 24-bit
                    // files decoded as S32LE.  Keep the old bias only for explicit packed24
                    // user mode or DoP; otherwise let the S32 container scorer below win.
                    if (targetBitDepth == 24 && targetSubslotSize == 3) {
                        if (explicitPacked24Request) {
                            if (c.bitResolution == 24 && c.subslotSize == 3) {
                                score += 1200;
                            } else if (c.subslotSize == 4) {
                                score -= 400;
                            }
                        } else if (autoPreferS32Container && c.bitResolution == 24 && c.subslotSize == 3) {
                            LOGI("    AUTO S32 source: ignore Java packed24 hint for iface=%d alt=%d",
                                 c.iface, c.alt);
                        }
                    }
                    if (c.capacityOk) {
                        score += 600;
                        if (c.capacityRatioPermille >= 2000) score += 150;
                    } else {
                        score -= 4000;
                        LOGW("    Stream profile capacity risk: iface=%d alt=%d maxPkt=%d nominalInterval=%d ips=%d rate=%d frame=%d",
                             c.iface, c.alt, c.maxPacketSize, c.nominalBytesPerInterval,
                             c.serviceIntervalsPerSecond, profileRate, frameSize);
                    }

                    // endpoint/sync/feedback 评分：feedback 是 profile 的
                    // capability, not a requirement.  Many UAC2 DACs expose async OUT
                    // without an explicit feedback endpoint and are perfectly stable when
                    // driven by a high-accuracy fractional packet scheduler.  Prefer
                    // proven explicit feedback, but do not push no-feedback devices into
                    // safe-alt/reopen fallbacks just because feedback is absent.
                    switch (c.outSyncType) {
                        case 1: // async OUT
                            score += c.explicitFeedbackEligible ? 520 : 160;
                            break;
                        case 2: // adaptive OUT: viable without explicit feedback.
                            score += 240;
                            break;
                        case 3: // synchronous OUT: acceptable, less flexible.
                            score += 100;
                            break;
                        default:
                            score -= 120;
                            break;
                    }
                    if (c.explicitFeedbackEligible) {
                        score += 320;
                    } else if (c.feedbackEpAddress != 0) {
                        // Non-standard feedback descriptors are merely less useful;
                        // they should not outweigh rate/format/capacity correctness.
                        score -= 120;
                    }
                    if (sessionRequest.noFeedback && c.feedbackEpAddress != 0) {
                        score -= 900;
                    }
                    if (sessionRequest.preferSafeAlt || sessionRequest.safeMode) {
                        // Safe mode should prefer bandwidth headroom and simple PCM, not
                        // blindly punish async no-feedback.  no-feedback is now a normal
                        // transport model handled by IsoPacer.
                        if (c.capacityRatioPermille >= 1400) score += 260;
                        if (c.riskFlags == PROFILE_RISK_NONE || c.riskFlags == PROFILE_RISK_CLOCK_UNVERIFIED) score += 220;
                        if (c.riskFlags & PROFILE_RISK_FEEDBACK_NONSTANDARD) score -= 250;
                    }
                    // Java 传下来的作为 hint，不再强约束
                    if (javaHintIface >= 0 && c.iface == javaHintIface) {
                        score += 100;
                    }
                    if (javaHintAlt > 0 && c.alt == javaHintAlt) {
                        score += 100;
                    }

                    // Phase 8: LastGoodProfile is a soft hint, not a VID/PID quirk.
                    // A previously healthy profile gets priority before we walk the
                    // retry ladder into more destructive fallbacks.
                    const int lastGoodAlt = sessionRequest.lastGoodAlt;
                    const int lastGoodRate = sessionRequest.lastGoodSampleRate;
                    const int lastGoodBits = sessionRequest.lastGoodValidBits;
                    const int lastGoodSubslot = sessionRequest.lastGoodSubslotBytes;
                    const int lastGoodFb = sessionRequest.lastGoodFeedbackEndpoint;
                    const bool suppressPacked24LastGood =
                            autoPreferS32Container &&
                            lastGoodBits == 24 &&
                            lastGoodSubslot == 3;
                    if (!suppressPacked24LastGood) {
                        if (lastGoodAlt > 0 && c.alt == lastGoodAlt) score += 1600;
                        if (lastGoodRate > 0 && streamSupportsRate(c, lastGoodRate)) score += 800;
                        if (lastGoodBits > 0 && c.bitResolution == lastGoodBits) score += 500;
                        if (lastGoodSubslot > 0 && c.subslotSize == lastGoodSubslot) score += 500;
                        if (lastGoodFb > 0 && c.feedbackEpAddress == lastGoodFb) score += 250;
                    } else if (c.bitResolution == 24 && c.subslotSize == 3) {
                        score -= 900;
                        LOGW("    AUTO S32 source: suppress polluted LastGood packed24 profile "
                             "lastGoodAlt=%d sr=%d bits=%d subslot=%d fb=0x%02X -900",
                             lastGoodAlt, lastGoodRate, lastGoodBits, lastGoodSubslot, lastGoodFb);
                    }
                    // DoP 模式：强制选择 24-bit subslot (3 bytes)
                    // DoP = 2 DSD bytes + 1 marker byte = 3 bytes = 24-bit
                    if (nativeDsdRequested) {
                        if (c.isRawData) {
                            score += 3200;
                        } else {
                            score -= 6000;
                        }
                        if (c.subslotSize == targetSubslotSize) score += 2400;
                        else score -= 3000;
                        if (c.bitResolution == targetBitDepth) score += 2400;
                        else score -= 3000;
                        if (nativeDsdSampleRate > 0 && streamSupportsRate(c, nativeDsdSampleRate)) {
                            score += 2200;
                        } else if (nativeDsdSampleRate > 0) {
                            score -= 3500;
                        }
                        LOGI("    Native DSD scoring: raw=%d subslot=%d bits=%d rate=%d",
                             c.isRawData ? 1 : 0, c.subslotSize, c.bitResolution, nativeDsdSampleRate);
                    } else if (forceDop24bit) {
                        if (c.subslotSize == 3) {
                            score += 5000;  // 强烈优先 24-bit subslot
                        } else {
                            score -= 5000;  // 惩罚非 24-bit subslot
                        }
                        // DoP 模式下用 DoP 设备采样率检查速率支持
                        if (dopSampleRate > 0 && streamSupportsRate(c, dopSampleRate)) {
                            score += 2000;  // 支持 DoP 采样率
                        }
                        LOGI("    DoP scoring: subslotSize=%d dopRate=%d force24=%d",
                             c.subslotSize, dopSampleRate, forceDop24bit ? 1 : 0);
                    }

                    if (!nativeDsdRequested && !bitPerfect && !forceDop24bit) {
                        const bool preferSafe =
                                sessionRequest.preferSafeAlt ||
                                sessionRequest.safeMode;
                        const bool preferContainerInSafeMode =
                                autoPreferS32Container &&
                                (sessionRequest.noFeedback ||
                                 c.feedbackEpAddress == 0);

                        if (c.channels == 2) score += 450;
                        else if (c.channels > 2) score -= 250;

                        if (preferSafe) {
                            // Safe mode: prefer stable 16/24-bit, avoid 32-bit containers
                            if (c.bitResolution == 16 && c.subslotSize == 2) score += 3000;
                            else if (preferContainerInSafeMode && c.subslotSize == 4 && c.bitResolution >= 24) {
                                score += 2000;
                                LOGI("    SAFE + AUTO-S32 + NOFB: prefer 32-bit container +2000");
                            } else if (c.bitResolution == 24 && c.subslotSize == 3) {
                                score += preferContainerInSafeMode ? 500 : 2200;
                                if (preferContainerInSafeMode) {
                                    LOGI("    SAFE + AUTO-S32 + NOFB: reduce packed 24-bit bias +500");
                                }
                            }
                            else if (c.bitResolution >= 32 || c.subslotSize >= 4) score -= 600;

                            if (streamSupportsRate(c, 48000)) score += 1800;
                            if (streamSupportsRate(c, 44100)) score += 1600;
                            if (streamSupportsRate(c, 96000)) score += 800;
                            if (streamSupportsRate(c, 192000)) score -= 300;
                            if (streamSupportsRate(c, 384000)) score -= 900;
                        } else {
                            if (c.bitResolution == 16 && c.subslotSize == 2) {
                                score += 900;
                            } else if (c.bitResolution == 24 && c.subslotSize == 3) {
                                score += 550;
                            } else if (c.bitResolution >= 32 || c.subslotSize >= 4) {
                                score -= 700;
                            }
                        }

                        if (c.explicitFeedbackEligible) score += 200;
                    }

                    if (autoPreferS32Container) {
                        if (c.bitResolution == 32 && c.subslotSize == 4) {
                            score += 4200;
                            LOGI("    AUTO S32 source: prefer native 32-bit/subslot4 +4200");
                        } else if (c.subslotSize == 4 && c.bitResolution >= 24) {
                            score += 2200;
                            LOGI("    AUTO S32 source: prefer 32-bit container fallback +2200");
                        } else if (c.bitResolution == 24 && c.subslotSize == 3) {
                            score -= 1400;
                            LOGI("    AUTO S32 source: de-prioritize packed 24-bit alt -1400");
                        }
                    }

                    // PCM output mode scoring
                    if (!nativeDsdRequested && !bitPerfect && !forceDop24bit) {
                        const bool explicitMatch = candidateMatchesUserPcmMode(
                                c.bitResolution, c.subslotSize, userMode);

                        if (isExplicitPcmMode(userMode)) {
                            if (explicitMatch) {
                                score += 3000;
                                LOGI("    PCM mode match: mode=%s bits=%d subslot=%d +3000",
                                     usbPcmOutputModeName(userMode), c.bitResolution, c.subslotSize);
                            } else {
                                score -= 1500;
                                LOGI("    PCM mode mismatch: mode=%s bits=%d subslot=%d -1500",
                                     usbPcmOutputModeName(userMode), c.bitResolution, c.subslotSize);
                            }
                        }
                    }

                    // 优先较低 alt，避免未知设备误选高格式/DSD/32-bit alt
                    score -= c.alt * (bitPerfect || forceDop24bit || nativeDsdRequested ? 2 : 40);
                    c.score = score;
                    LOGI("    Profile score=%d iface=%d alt=%d ep=0x%02X proto=UAC%d term=0x%02X ch=%d bits=%d subslot=%d pcm=%d raw=%d rates=%s "
                         "sync=%s usage=%s fb=0x%02X fbUsage=%s ips=%d nominal=%dB/interval %dB/xfer cap=%dB ratio=%d.%03d risks=%s",
                         c.score,
                         c.iface,
                         c.alt,
                         c.epAddress,
                         c.protocol,
                         c.terminalLink,
                         c.channels,
                         c.bitResolution,
                         c.subslotSize,
                         c.isPCM ? 1 : 0,
                         c.isRawData ? 1 : 0,
                         rateListToString(c).c_str(),
                         syncTypeName(c.outSyncType),
                         usageTypeName(c.outUsageType),
                         c.feedbackEpAddress,
                         usageTypeName(c.fbUsageType),
                         c.serviceIntervalsPerSecond,
                         c.nominalBytesPerInterval,
                         c.nominalBytesPerTransfer,
                         c.maxPacketSize,
                         c.capacityRatioPermille / 1000,
                         c.capacityRatioPermille % 1000,
                         streamProfileRiskToString(c.riskFlags).c_str());
                    candidates.push_back(c);
                }
            }
        }
        pos += bLength;
    }
    if (candidates.empty()) {
        LOGE("No usable USB Audio playback stream candidate found");
        return false;
    }

    // Bit-perfect format selection must be decided after all transport and
    // last-good-profile hints have been accumulated.  Those hints are useful
    // for choosing between equivalent endpoints, but they must not promote a
    // wider container (for example 32-bit/4-byte) over an available exact
    // valid-bit endpoint (for example 24-bit/3-byte).
    const bool nativeDsdMode = forceNativeDsdRaw && nativeDsdSampleRate > 0;
    if (bitPerfect && !nativeDsdMode && targetBitDepth > 0) {
        bool exactWireFormatAvailable = false;
        for (const auto& candidate : candidates) {
            if (candidate.isPCM &&
                candidate.channels == targetChannels &&
                candidate.bitResolution == targetBitDepth &&
                candidate.capacityOk) {
                exactWireFormatAvailable = true;
                break;
            }
        }
        if (exactWireFormatAvailable) {
            for (auto& candidate : candidates) {
                if (!candidate.isPCM || candidate.channels != targetChannels) {
                    continue;
                }
                if (candidate.bitResolution == targetBitDepth) {
                    candidate.score += 3000;
                } else if (candidate.bitResolution > targetBitDepth) {
                    candidate.score -= 3000;
                }
            }
            LOGI("Bit-perfect format guard: exact %d-bit PCM endpoint available; "
                 "wider containers demoted", targetBitDepth);
        }
    }

    int bestIndex = 0;
    for (int i = 1; i < (int)candidates.size(); i++) {
        if (candidates[i].score > candidates[bestIndex].score) {
            bestIndex = i;
        }
    }
    outBest = candidates[bestIndex];
    LOGI("Selected stream profile: iface=%d alt=%d ep=0x%02X proto=UAC%d termLink=0x%02X pkt=%d bInterval=%d ips=%d ch=%d bits=%d subslot=%d frameBytes=%d "
         "rateSupport=%s feedback=0x%02X sync=%s usage=%s nominal=%dB/interval %dB/xfer capRatio=%d.%03d risks=%s score=%d",
         outBest.iface,
         outBest.alt,
         outBest.epAddress,
         outBest.protocol,
         outBest.terminalLink,
         outBest.maxPacketSize,
         outBest.bInterval,
         outBest.serviceIntervalsPerSecond,
         outBest.channels,
         outBest.bitResolution,
         outBest.subslotSize,
         outBest.frameBytes,
         rateListToString(outBest).c_str(),
         outBest.feedbackEpAddress,
         syncTypeName(outBest.outSyncType),
         usageTypeName(outBest.outUsageType),
         outBest.nominalBytesPerInterval,
         outBest.nominalBytesPerTransfer,
         outBest.capacityRatioPermille / 1000,
         outBest.capacityRatioPermille % 1000,
         streamProfileRiskToString(outBest.riskFlags).c_str(),
         outBest.score);
    // 输出所有候选格式，供 capabilities JSON 生成
    if (outAllCandidates) {
        *outAllCandidates = candidates;
    }
    return true;
}

// ==========================
// Terminal Type 可读名称（USB Audio Terminal Types）
// 参考：USB Audio Terminal Types spec, Table 2-1
// ==========================
struct PlaybackFeatureUnitChoice {
    const AcEntity* fu = nullptr;
    const AcEntity* outputTerminal = nullptr;
    int score = INT_MIN;
    int depth = INT_MAX;
};

static const AcEntity* choosePlaybackFeatureUnitForTerminal(AcTopology& topo, uint8_t terminalLink) {
    if (terminalLink == 0) return nullptr;

    struct TraversalNode {
        uint8_t entityId = 0;
        uint8_t lastFeatureUnitId = 0;
        int depth = 0;
    };

    std::queue<TraversalNode> queue;
    std::unordered_set<uint32_t> visited;
    queue.push({terminalLink, 0, 0});
    visited.insert((uint32_t)terminalLink);

    PlaybackFeatureUnitChoice best;
    const AcEntity* directFallback = nullptr;

    while (!queue.empty()) {
        TraversalNode node = queue.front();
        queue.pop();

        for (const auto& entity : topo.entities) {
            if (!acEntityConsumesSource(entity, node.entityId)) {
                continue;
            }

            uint8_t candidateFuId = node.lastFeatureUnitId;
            if (entity.subtype == AC_ENTITY_FEATURE_UNIT) {
                candidateFuId = entity.id;
                if (!directFallback) {
                    directFallback = &entity;
                }
            }

            if (entity.subtype == AC_ENTITY_OUTPUT_TERMINAL) {
                const AcEntity* fu = candidateFuId != 0 ? topo.findById(candidateFuId) : nullptr;
                if (!fu || fu->subtype != AC_ENTITY_FEATURE_UNIT) {
                    continue;
                }

                int score = playbackOutputTerminalRank(entity.terminalType);
                if (isPreferredPlaybackOutputTerminalType(entity.terminalType)) {
                    score += 2000;
                }
                if (featureUnitHasPlaybackVolumeCapability(*fu)) {
                    score += 240;
                }
                score -= node.depth * 8;
                score -= std::abs((int)fu->acInterface - (int)entity.acInterface) * 24;

                LOGI("Playback FeatureUnit candidate: terminalLink=0x%02X FU=0x%02X OT=0x%02X type=%s depth=%d score=%d",
                     terminalLink,
                     fu->id,
                     entity.id,
                     terminalTypeToString(entity.terminalType),
                     node.depth,
                     score);

                if (!best.fu ||
                    score > best.score ||
                    (score == best.score && node.depth < best.depth)) {
                    best.fu = fu;
                    best.outputTerminal = &entity;
                    best.score = score;
                    best.depth = node.depth;
                }
                continue;
            }

            if (!isPlaybackSignalEntity(entity) || node.depth >= 12) {
                continue;
            }

            const uint32_t visitKey =
                    (uint32_t)entity.id |
                    ((uint32_t)candidateFuId << 8);
            if (!visited.insert(visitKey).second) {
                continue;
            }
            queue.push({entity.id, candidateFuId, node.depth + 1});
        }
    }

    if (best.fu) {
        LOGI("Playback FeatureUnit path-selected: terminalLink=0x%02X FU=0x%02X OT=0x%02X type=%s depth=%d score=%d",
             terminalLink,
             best.fu->id,
             best.outputTerminal ? best.outputTerminal->id : 0,
             best.outputTerminal ? terminalTypeToString(best.outputTerminal->terminalType) : "None",
             best.depth,
             best.score);
        return best.fu;
    }

    if (directFallback) {
        LOGW("Playback FeatureUnit path-select fallback: terminalLink=0x%02X FU=0x%02X subtype=%s",
             terminalLink,
             directFallback->id,
             acEntitySubtypeToString(directFallback->subtype));
    }
    return directFallback;
}

// ==========================
// AC Topology: 解析所有entity
// ==========================
// Clock Selector: GET_CUR (读取当前选中pin)
// ==========================
#define UAC2_CS_CONTROL_SELECTOR 0x01
#define UAC2_CS_SAM_FREQ_CONTROL 0x01
// UAC2: SET_CUR GET_CUR bRequest 都是 0x01（方向由 bmRequestType 决定
// UAC1: SET_CUR=0x01, GET_CUR=0x81
#define UAC1_SET_CUR 0x01
#define UAC1_GET_CUR 0x81
#define UAC1_GET_MIN 0x82
#define UAC1_GET_MAX 0x83
#define UAC1_GET_RES 0x84
#define UAC2_REQ_CUR   0x01
#define UAC2_REQ_RANGE 0x02

// UAC2 Class-Specific Request Constants
static constexpr uint8_t USB_REQ_TYPE_CLASS_INTERFACE_OUT = 0x21;
static constexpr uint8_t USB_REQ_TYPE_CLASS_INTERFACE_IN  = 0xA1;
static constexpr uint8_t UAC2_CS_CUR   = 0x01;
static constexpr uint8_t UAC2_CS_RANGE = 0x02;
static constexpr uint8_t UAC2_CS_CLOCK_VALID_CONTROL = 0x02;

// UAC2 controls are encoded as two-bit capability fields per control selector.
// Many devices are not perfectly spec-compliant, so treat any non-zero field as
// readable, and fields with bit1 set as host-writable.  This keeps the model
// permissive while still exposing the clock path explicitly.
static int uac2_clock_selector_get_cur(
        libusb_device_handle *devh,
        uint8_t acInterface,
        uint8_t selectorId,
        uint8_t *outPin
) {
    uint8_t data[1] = {0};
    int r = libusb_control_transfer(
            devh,
            0xA1,  // Device-to-host | Class | Interface
            UAC2_REQ_CUR,
            UAC2_CS_CONTROL_SELECTOR << 8,
            ((uint16_t)selectorId << 8) | acInterface,
            data,
            1,
            300
    );
    if (r == 1) {
        *outPin = data[0];
        LOGI("ClockSelector 0x%02X GET_CUR: currentPin=%d", selectorId, data[0]);
        return 0;
    }
    LOGE("ClockSelector 0x%02X GET_CUR failed: r=%d %s", selectorId, r, (r < 0) ? libusb_error_name(r) : "wrong_len");
    return r < 0 ? r : -1;
}

// ==========================
// 根据 terminalLink 查找播放路径上的 clock entity（支持 Clock Selector 解析）
// ==========================
// 返回：最终 Clock Source ID；0 表示失败
// outAcInterface: AC interface number
// outClockSupportsRead: bmControls 表明是否支持 GET_CUR (Freq Read Control)
// outIsClockSelector: 中间是否经过Clock Selector
// outResolvedClockSourceId：最终解析到的 Clock Source ID
// ==========================
static uint8_t findClockForStreamTerminal(
        const std::vector<AcEntity> &entities,
        uint8_t terminalLink,
        uint8_t &outAcInterface,
        bool &outClockSupportsRead,
        bool &outIsClockSelector,
        uint8_t &outResolvedClockSourceId
) {
    outAcInterface = 0;
    outClockSupportsRead = false;
    outIsClockSelector = false;
    outResolvedClockSourceId = 0;

    // 1. 查找 terminalLink 对应Terminal entity
    const AcEntity *term = findEntityById(entities, terminalLink);
    if (!term) {
        LOGE("TerminalLink 0x%02X not found in AC topology", terminalLink);
        return 0;
    }

    const char *termSubtypeName = "Unknown";
    switch (term->subtype) {
        case AC_ENTITY_INPUT_TERMINAL:  termSubtypeName = "InputTerminal"; break;
        case AC_ENTITY_OUTPUT_TERMINAL: termSubtypeName = "OutputTerminal"; break;
        default: break;
    }
    LOGI("TerminalLink 0x%02X found: subtype=%s cSourceId=0x%02X sourceId=0x%02X",
         terminalLink, termSubtypeName, term->cSourceId, term->sourceId);

    uint8_t clockId = term->cSourceId;
    outAcInterface = term->acInterface;

    if (clockId == 0) {
        LOGE("Terminal 0x%02X has cSourceId=0, no clock reference", terminalLink);
        return 0;
    }

    // 2. 查找 clockId 对应entity
    const AcEntity *clockEntity = findEntityById(entities, clockId);
    if (!clockEntity) {
        LOGE("Clock entity 0x%02X not found in AC topology", clockId);
        return 0;
    }

    // 3. 如果Clock Source，直接返
    if (clockEntity->subtype == AC_ENTITY_CLOCK_SOURCE) {
        outClockSupportsRead = (clockEntity->bmControls & 0x02) != 0; // bit1 = Frequency Read Control
        outIsClockSelector = false;
        outResolvedClockSourceId = clockId;
        LOGI("Clock for stream: terminalLink=0x%02X clockEntity=0x%02X type=ClockSource bmControls=0x%02X supportsRead=%d",
             terminalLink, clockId, clockEntity->bmControls, outClockSupportsRead);
        return clockId;
    }

    // 4. 如果Clock Selector，需resolve
    if (clockEntity->subtype == AC_ENTITY_CLOCK_SELECTOR) {
        outIsClockSelector = true;
        LOGI("Clock for stream: terminalLink=0x%02X clockEntity=0x%02X type=ClockSelector nrPins=%d 锟?need resolve",
             terminalLink, clockId, clockEntity->nrPins);
        // Clock Selector 需要运行时 GET_CUR 才能解析，这里只返回 selector ID
        // 调用者需要进一步调用 resolveClockSelector()
        return clockId;
    }

    LOGE("Entity 0x%02X is not Clock Source or Clock Selector, subtype=0x%02X", clockId, clockEntity->subtype);
    return 0;
}

// ==========================
// Resolve Clock Selector Clock Source（运行时 GET_CUR 获取当前选中 pin
// ==========================
static uint8_t resolveClockSelector(
        libusb_device_handle *devh,
        const std::vector<AcEntity> &entities,
        uint8_t selectorId,
        uint8_t acInterface,
        bool &outClockSupportsRead
) {
    outClockSupportsRead = false;

    const AcEntity *sel = findEntityById(entities, selectorId);
    if (!sel || sel->subtype != AC_ENTITY_CLOCK_SELECTOR) {
        LOGE("Entity 0x%02X is not ClockSelector", selectorId);
        return 0;
    }

    // GET_CUR: 读取当前选中pin-based
    uint8_t currentPin = 0;
    int ret = uac2_clock_selector_get_cur(devh, acInterface, selectorId, &currentPin);
    if (ret != 0) {
        LOGE("Cannot read ClockSelector 0x%02X current pin, cannot resolve", selectorId);
        return 0;
    }

    // pin 为 1-based，sourceIds 为 0-based
    if (currentPin < 1 || currentPin > sel->numSourceIds) {
        LOGE("ClockSelector 0x%02X currentPin=%d out of range (1..%d)", selectorId, currentPin, sel->numSourceIds);
        return 0;
    }

    uint8_t selectedClockSourceId = sel->sourceIds[currentPin - 1];
    LOGI("ClockSelector 0x%02X currentPin=%d selectedClockSource=0x%02X",
         selectorId, currentPin, selectedClockSourceId);

    // 验证选中entity 确实Clock Source
    const AcEntity *cs = findEntityById(entities, selectedClockSourceId);
    if (!cs) {
        LOGE("Selected clock source 0x%02X not found in AC topology", selectedClockSourceId);
        return 0;
    }
    if (cs->subtype != AC_ENTITY_CLOCK_SOURCE) {
        LOGE("Selected entity 0x%02X is not ClockSource (subtype=0x%02X)", selectedClockSourceId, cs->subtype);
        return 0;
    }

    outClockSupportsRead = (cs->bmControls & 0x02) != 0;
    LOGI("Resolved: ClockSelector 0x%02X pin=%d -> ClockSource 0x%02X bmControls=0x%02X supportsRead=%d",
         selectorId, currentPin, selectedClockSourceId, cs->bmControls, outClockSupportsRead);
    return selectedClockSourceId;
}

// ==========================
// UAC2 Clock Validity GET_CUR (Clock Source Entity)
// ==========================
static int uac2GetClockValidity(
        libusb_device_handle* devh,
        uint8_t acInterface,
        uint8_t clockEntityId,
        bool* outValid
) {
    if (!outValid) return LIBUSB_ERROR_INVALID_PARAM;
    uint8_t data[1] = {0};
    const uint16_t wValue = static_cast<uint16_t>(UAC2_CS_CLOCK_VALID_CONTROL << 8);
    const uint16_t wIndex = static_cast<uint16_t>((clockEntityId << 8) | acInterface);
    int r = libusb_control_transfer(
            devh,
            USB_REQ_TYPE_CLASS_INTERFACE_IN,
            UAC2_CS_CUR,
            wValue,
            wIndex,
            data,
            sizeof(data),
            500
    );
    if (r < 0) {
        LOGW("UAC2 GET_CUR CLOCK_VALID failed: clock=0x%02X iface=%u err=%s",
             clockEntityId, acInterface, libusb_error_name(r));
        return r;
    }
    if (r != 1) {
        LOGW("UAC2 GET_CUR CLOCK_VALID short read: clock=0x%02X iface=%u len=%d",
             clockEntityId, acInterface, r);
        return LIBUSB_ERROR_IO;
    }
    *outValid = (data[0] != 0);
    LOGI("UAC2 GET_CUR CLOCK_VALID: clock=0x%02X iface=%u valid=%d",
         clockEntityId, acInterface, *outValid ? 1 : 0);
    return LIBUSB_SUCCESS;
}

// Bind the selected AudioStreaming terminalLink to its playback Clock Source.
// This mirrors the topology-first native USB audio model: AS terminal -> Clock Selector/Source
// -> final Clock Source.  The result is cached in ctx and then used by commit.
static bool bindPlaybackClockForTerminal(
        UsbAudioContext* ctx,
        const std::vector<AcEntity>& entities,
        uint8_t terminalLink
) {
    if (!ctx || terminalLink == 0) return false;
    ctx->clock.reset();

    uint8_t acIface = 0;
    bool legacySupportsRead = false;
    bool isSelector = false;
    uint8_t resolvedClockSourceId = 0;
    uint8_t clockEntity = findClockForStreamTerminal(
            entities,
            terminalLink,
            acIface,
            legacySupportsRead,
            isSelector,
            resolvedClockSourceId
    );
    if (clockEntity == 0) {
        LOGW("Playback clock bind failed: no clock for terminalLink=0x%02X", terminalLink);
        return false;
    }

    uint8_t finalClockSource = clockEntity;
    uint8_t selectorId = 0;
    if (isSelector) {
        selectorId = clockEntity;
        finalClockSource = resolveClockSelector(
                ctx->devHandle,
                entities,
                selectorId,
                acIface,
                legacySupportsRead
        );
        if (finalClockSource == 0) {
            LOGW("Playback clock bind: selector 0x%02X could not be resolved", selectorId);
            return false;
        }
    }

    const AcEntity* cs = findEntityById(entities, finalClockSource);
    if (!cs || cs->subtype != AC_ENTITY_CLOCK_SOURCE) {
        LOGW("Playback clock bind: final entity 0x%02X is not a ClockSource", finalClockSource);
        return false;
    }

    ctx->clock.clockEntityId = finalClockSource;
    ctx->clock.clockAcInterface = acIface;
    ctx->clock.clockSelectorId = selectorId;
    ctx->clock.clockPathIsSelector = isSelector;
    ctx->clock.clockFrequencyReadable = uac2ControlReadable(cs->bmControls, UAC2_CS_SAM_FREQ_CONTROL);
    ctx->clock.clockFrequencyWritable = uac2ControlWritable(cs->bmControls, UAC2_CS_SAM_FREQ_CONTROL);
    ctx->clock.clockValidityReadable = uac2ControlReadable(cs->bmControls, UAC2_CS_CLOCK_VALID_CONTROL);

    LOGI("Playback clock bound: terminalLink=0x%02X acIface=%u selector=0x%02X finalClock=0x%02X bmControls=0x%02X freqR=%d freqW=%d validityR=%d",
         terminalLink,
         ctx->clock.clockAcInterface,
         ctx->clock.clockSelectorId,
         ctx->clock.clockEntityId,
         cs->bmControls,
         ctx->clock.clockFrequencyReadable ? 1 : 0,
         ctx->clock.clockFrequencyWritable ? 1 : 0,
         ctx->clock.clockValidityReadable ? 1 : 0);
    return true;
}

// ==========================
// UAC2 SET_CUR sample rate (Clock Source Entity)
// ==========================

static int uac2SetCurSampleRate(
        libusb_device_handle* devh,
        uint8_t acInterface,
        uint8_t clockEntityId,
        uint32_t sampleRate
) {
    uint8_t data[4];
    data[0] = static_cast<uint8_t>(sampleRate & 0xFF);
    data[1] = static_cast<uint8_t>((sampleRate >> 8) & 0xFF);
    data[2] = static_cast<uint8_t>((sampleRate >> 16) & 0xFF);
    data[3] = static_cast<uint8_t>((sampleRate >> 24) & 0xFF);
    const uint16_t wValue =
            static_cast<uint16_t>(UAC2_CS_SAM_FREQ_CONTROL << 8);
    const uint16_t wIndex =
            static_cast<uint16_t>((clockEntityId << 8) | acInterface);
    LOGI(
            "UAC2 SET_CUR SAM_FREQ: rate=%u clock=0x%02X acIface=%u wValue=0x%04X wIndex=0x%04X data=%02X %02X %02X %02X",
            sampleRate,
            clockEntityId,
            acInterface,
            wValue,
            wIndex,
            data[0],
            data[1],
            data[2],
            data[3]
    );
    int r = libusb_control_transfer(
            devh,
            USB_REQ_TYPE_CLASS_INTERFACE_OUT,
            UAC2_CS_CUR,
            wValue,
            wIndex,
            data,
            sizeof(data),
            1000
    );
    if (r < 0) {
        LOGW("UAC2 SET_CUR SAM_FREQ failed: %s", libusb_error_name(r));
        return r;
    }
    if (r != 4) {
        LOGW("UAC2 SET_CUR SAM_FREQ short write: %d", r);
        return LIBUSB_ERROR_IO;
    }
    LOGI("UAC2 SET_CUR SAM_FREQ ok: %u Hz", sampleRate);
    return LIBUSB_SUCCESS;
}

// ==========================
// UAC2 GET_CUR sample rate (Clock Source Entity)
// ==========================
static int uac2GetCurSampleRate(
        libusb_device_handle* devh,
        uint8_t acInterface,
        uint8_t clockEntityId,
        uint32_t* outRate
) {
    if (!outRate) return LIBUSB_ERROR_INVALID_PARAM;
    uint8_t data[4] = {0};
    const uint16_t wValue =
            static_cast<uint16_t>(UAC2_CS_SAM_FREQ_CONTROL << 8);
    const uint16_t wIndex =
            static_cast<uint16_t>((clockEntityId << 8) | acInterface);
    int r = libusb_control_transfer(
            devh,
            USB_REQ_TYPE_CLASS_INTERFACE_IN,
            UAC2_CS_CUR,
            wValue,
            wIndex,
            data,
            sizeof(data),
            1000
    );
    if (r < 0) {
        LOGW("UAC2 GET_CUR SAM_FREQ failed: %s", libusb_error_name(r));
        return r;
    }
    if (r != 4) {
        LOGW("UAC2 GET_CUR SAM_FREQ short read: %d", r);
        return LIBUSB_ERROR_IO;
    }
    uint32_t rate =
            static_cast<uint32_t>(data[0]) |
            (static_cast<uint32_t>(data[1]) << 8) |
            (static_cast<uint32_t>(data[2]) << 16) |
            (static_cast<uint32_t>(data[3]) << 24);
    *outRate = rate;
    LOGI("UAC2 GET_CUR SAM_FREQ ok: %u Hz", rate);
    return LIBUSB_SUCCESS;
}

// ==========================
// UAC2 GET_RANGE sample rates (Clock Source Entity)
// UAC2 spec 3.2: returns list of supported discrete rates or continuous ranges.
// Response format: wNumSubRanges(2) + [dwMIN(4) + dwMAX(4) + dwRES(4)] * n
// ==========================
static int uac2GetRangeSampleRates(
        libusb_device_handle* devh,
        uint8_t acInterface,
        uint8_t clockEntityId,
        std::vector<uint32_t>& outRates
) {
    uint8_t rangeBuf[256] = {0};
    const uint16_t wValue =
            static_cast<uint16_t>(UAC2_CS_SAM_FREQ_CONTROL << 8);
    const uint16_t wIndex =
            static_cast<uint16_t>((clockEntityId << 8) | acInterface);
    int r = libusb_control_transfer(
            devh,
            USB_REQ_TYPE_CLASS_INTERFACE_IN,
            UAC2_CS_RANGE,
            wValue,
            wIndex,
            rangeBuf,
            sizeof(rangeBuf),
            1000
    );
    if (r < 2) {
        LOGW("UAC2 GET_RANGE SAM_FREQ failed: %s (r=%d)", libusb_error_name(r), r);
        return r < 0 ? r : LIBUSB_ERROR_IO;
    }
    uint16_t numSubranges = rangeBuf[0] | (rangeBuf[1] << 8);
    LOGI("UAC2 GET_RANGE SAM_FREQ: numSubranges=%u, totalBytes=%d", numSubranges, r);
    outRates.clear();
    size_t offset = 2;
    for (uint16_t i = 0; i < numSubranges && offset + 12 <= static_cast<size_t>(r); i++) {
        uint32_t minRate =
                static_cast<uint32_t>(rangeBuf[offset]) |
                (static_cast<uint32_t>(rangeBuf[offset + 1]) << 8) |
                (static_cast<uint32_t>(rangeBuf[offset + 2]) << 16) |
                (static_cast<uint32_t>(rangeBuf[offset + 3]) << 24);
        uint32_t maxRate =
                static_cast<uint32_t>(rangeBuf[offset + 4]) |
                (static_cast<uint32_t>(rangeBuf[offset + 5]) << 8) |
                (static_cast<uint32_t>(rangeBuf[offset + 6]) << 16) |
                (static_cast<uint32_t>(rangeBuf[offset + 7]) << 24);
        uint32_t res =
                static_cast<uint32_t>(rangeBuf[offset + 8]) |
                (static_cast<uint32_t>(rangeBuf[offset + 9]) << 8) |
                (static_cast<uint32_t>(rangeBuf[offset + 10]) << 16) |
                (static_cast<uint32_t>(rangeBuf[offset + 11]) << 24);
        LOGI("  SubRange[%u]: MIN=%u MAX=%u RES=%u", i, minRate, maxRate, res);
        if (minRate == maxRate) {
            outRates.push_back(minRate);
        } else {
            static const uint32_t stdRates[] = {
                    32000, 44100, 48000, 88200, 96000,
                    176400, 192000, 352800, 384000,
                    705600, 768000
            };
            for (uint32_t sr : stdRates) {
                if (sr >= minRate && sr <= maxRate) {
                    if (res == 0 || (sr - minRate) % res == 0) {
                        outRates.push_back(sr);
                    }
                }
            }
        }
        offset += 12;
    }
    LOGI("UAC2 GET_RANGE parsed %zu supported rates", outRates.size());
    return LIBUSB_SUCCESS;
}

// ==========================
// UAC1 Endpoint SET_CUR / GET_CUR sample rate
// ==========================
#define UAC_EP_SAMPLING_FREQ_CONTROL 0x01

static int uac1_set_endpoint_sample_rate(
        libusb_device_handle *devh,
        uint8_t epAddress,
        uint32_t sampleRate
) {
    uint8_t data[3];
    data[0] = sampleRate & 0xff;
    data[1] = (sampleRate >> 8) & 0xff;
    data[2] = (sampleRate >> 16) & 0xff;
    int r = libusb_control_transfer(
            devh,
            LIBUSB_ENDPOINT_OUT |
            LIBUSB_REQUEST_TYPE_CLASS |
            LIBUSB_RECIPIENT_ENDPOINT,
            UAC1_SET_CUR,
            UAC_EP_SAMPLING_FREQ_CONTROL << 8,
            epAddress,
            data,
            3,
            1000
    );
    if (r == 3) {
        LOGI("UAC1 endpoint SET_CUR sample rate OK: ep=0x%02X rate=%u",
             epAddress, sampleRate);
        return 0;
    }
    LOGW("UAC1 endpoint SET_CUR sample rate failed/ignored: ep=0x%02X rate=%u r=%d %s",
         epAddress,
         sampleRate,
         r,
         r < 0 ? libusb_error_name(r) : "short");
    return r < 0 ? r : -1;
}

static int uac1_get_endpoint_sample_rate(
        libusb_device_handle *devh,
        uint8_t epAddress,
        uint32_t *outRate
) {
    uint8_t data[3] = {0};
    int r = libusb_control_transfer(
            devh,
            LIBUSB_ENDPOINT_IN |
            LIBUSB_REQUEST_TYPE_CLASS |
            LIBUSB_RECIPIENT_ENDPOINT,
            UAC1_GET_CUR,
            UAC_EP_SAMPLING_FREQ_CONTROL << 8,
            epAddress,
            data,
            3,
            1000
    );
    if (r == 3) {
        *outRate = readU24Le(data);
        LOGI("UAC1 endpoint GET_CUR sample rate OK: ep=0x%02X actual=%u",
             epAddress, *outRate);
        return 0;
    }
    LOGW("UAC1 endpoint GET_CUR sample rate failed: ep=0x%02X r=%d %s",
         epAddress,
         r,
         r < 0 ? libusb_error_name(r) : "short");
    return r < 0 ? r : -1;
}

// ==========================
// Wrapper: get/set UAC2 current sample rate via ctx (uses clockEntityId)
// ==========================
static int getUac2CurrentSampleRate(UsbAudioContext* ctx, uint8_t clockEntityId) {
    if (!ctx || !ctx->devHandle || clockEntityId == 0) return 0;
    uint8_t acIface = ctx->clock.clockAcInterface != 0
                      ? ctx->clock.clockAcInterface
                      : static_cast<uint8_t>(std::max(ctx->acInterfaceNumber, 0));
    uint32_t rate = 0;
    int ret = uac2GetCurSampleRate(ctx->devHandle, acIface, clockEntityId, &rate);
    if (ret != LIBUSB_SUCCESS) return 0;
    return (int)rate;
}

static int setUac2CurrentSampleRate(UsbAudioContext* ctx, uint8_t clockEntityId, int rate) {
    if (!ctx || !ctx->devHandle || clockEntityId == 0) return -1;
    uint8_t acIface = ctx->clock.clockAcInterface != 0
                      ? ctx->clock.clockAcInterface
                      : static_cast<uint8_t>(std::max(ctx->acInterfaceNumber, 0));
    return uac2SetCurSampleRate(ctx->devHandle, acIface, clockEntityId, (uint32_t)rate);
}

// ==========================
// UAC2 sample rate configuration (best-effort, non-fatal)
// SET_CUR EIO is NOT treated as hard failure.
// Returns LIBUSB_SUCCESS if rate is configured/verified,
// otherwise returns the error code but caller should NOT abort init.
// ==========================
static bool configureAndVerifyPlaybackClock(
        UsbAudioContext* ctx,
        int requestedRate
) {
    if (!ctx || ctx->clock.clockEntityId == 0) {
        LOGE("configureAndVerifyPlaybackClock: no playback clock selected");
        return false;
    }
    const uint8_t clock = ctx->clock.clockEntityId;
    const uint8_t acIface = ctx->clock.clockAcInterface != 0
                            ? ctx->clock.clockAcInterface
                            : static_cast<uint8_t>(std::max(ctx->acInterfaceNumber, 0));
    ctx->clock.clockCommitTargetRate = requestedRate;
    ctx->clock.clockCommitVerifiedRate = 0;

    LOGI("Configure PLAYBACK clock commit: terminalLink=0x%02X selector=0x%02X clock=0x%02X acIface=%u requested=%d freqR=%d freqW=%d validityR=%d",
         ctx->terminalLink,
         ctx->clock.clockSelectorId,
         clock,
         acIface,
         requestedRate,
         ctx->clock.clockFrequencyReadable ? 1 : 0,
         ctx->clock.clockFrequencyWritable ? 1 : 0,
         ctx->clock.clockValidityReadable ? 1 : 0);

    int before = 0;
    if (ctx->clock.clockFrequencyReadable) {
        before = getUac2CurrentSampleRate(ctx, clock);
        LOGI("Playback clock before SET_CUR: clock=0x%02X rate=%d", clock, before);
    } else {
        LOGI("Playback clock frequency read not advertised; skipping pre-GET_CUR");
    }

    int setRet = LIBUSB_SUCCESS;
    if (ctx->clock.clockFrequencyWritable) {
        setRet = setUac2CurrentSampleRate(ctx, clock, requestedRate);
        if (setRet != LIBUSB_SUCCESS) {
            LOGW("Playback clock SET_CUR failed: clock=0x%02X requested=%d ret=%d %s",
                 clock, requestedRate, setRet, libusb_error_name(setRet));
        }
        usleep(20000);
    } else {
        LOGW("Playback clock frequency write not advertised; treating stream as fixed-rate unless GET_CUR proves otherwise");
    }

    int after = 0;
    if (ctx->clock.clockFrequencyReadable) {
        after = getUac2CurrentSampleRate(ctx, clock);
        LOGI("Playback clock after SET_CUR: clock=0x%02X requested=%d actual=%d",
             clock, requestedRate, after);
        if (after > 0) {
            ctx->clock.clockCommitVerifiedRate = after;
            ctx->clock.deviceSampleRate = after;
            if (!almostSameRate(after, requestedRate)) {
                if (ctx->dsdSession) {
                    // Native DSD/DoP uses the requested carrier as its transport
                    // clock. Never turn a clock readback mismatch into a PCM
                    // resampling request for an active DSD session.
                    ctx->clock.deviceSampleRate = requestedRate;
                    LOGE("DSD_TRANSPORT_CLOCK_MISMATCH keepCarrier=1 requested=%d "
                         "reported=%d mode=%s dsdHz=%u carrierHz=%u",
                         requestedRate,
                         after,
                         ctx->dsdDopTransport ? "DoP" : "NativeRAW",
                         ctx->dsdRateHz,
                         ctx->dsdCarrierRateHz);
                    return false;
                }
                ctx->sampleRate = after;
                LOGW("PLAYBACK CLOCK MISMATCH: requested=%d actual=%d clock=0x%02X terminalLink=0x%02X",
                     requestedRate, after, clock, ctx->terminalLink);
                // Do not hard-fail generic PCM playback.  The higher layer can resample or fallback.
                return false;
            }
            ctx->sampleRate = after;
        }
    } else if (setRet == LIBUSB_SUCCESS && ctx->clock.clockFrequencyWritable) {
        ctx->clock.clockCommitVerifiedRate = requestedRate;
        ctx->clock.deviceSampleRate = requestedRate;
        ctx->sampleRate = requestedRate;
    }

    if (ctx->clock.clockValidityReadable) {
        bool valid = false;
        int vRet = uac2GetClockValidity(ctx->devHandle, acIface, clock, &valid);
        if (vRet == LIBUSB_SUCCESS) {
            ctx->clock.clockValidityKnown = true;
            ctx->clock.clockValid = valid;
            if (!valid) {
                LOGW("Playback clock reports invalid after commit: clock=0x%02X requested=%d verified=%d",
                     clock, requestedRate, ctx->clock.clockCommitVerifiedRate);
                return false;
            }
        }
    }

    if (ctx->clock.clockCommitVerifiedRate <= 0) {
        ctx->clock.clockCommitVerifiedRate = requestedRate;
        ctx->clock.deviceSampleRate = requestedRate;
        ctx->sampleRate = requestedRate;
        LOGW("Playback clock could not be verified; assuming requested rate=%d for runtime model", requestedRate);
    }
    return setRet == LIBUSB_SUCCESS || almostSameRate(ctx->clock.clockCommitVerifiedRate, requestedRate);
}

static int configureUac2SampleRateBestEffort(
        libusb_device_handle* devh,
        uint8_t acInterface,
        uint8_t clockEntityId,
        uint32_t requestedRate
) {
    LOGI(
            "configureUac2SampleRateBestEffort: clock=0x%02X acIface=%u requested=%u",
            clockEntityId,
            acInterface,
            requestedRate
    );

    // 0. Query supported rates from device via GET_RANGE
    std::vector<uint32_t> supportedRates;
    int rangeRet = uac2GetRangeSampleRates(devh, acInterface, clockEntityId, supportedRates);
    if (rangeRet == LIBUSB_SUCCESS && !supportedRates.empty()) {
        bool found = false;
        for (uint32_t sr : supportedRates) {
            if (sr == requestedRate) {
                found = true;
                break;
            }
        }
        if (!found) {
            LOGW("Requested rate %u not in device GET_RANGE list, attempting SET_CUR anyway", requestedRate);
        } else {
            LOGI("Requested rate %u confirmed in device GET_RANGE supported list", requestedRate);
        }
    }

    // 1. Try GET_CUR before SET_CUR
    uint32_t beforeRate = 0;
    int getBefore = uac2GetCurSampleRate(
            devh,
            acInterface,
            clockEntityId,
            &beforeRate
    );
    if (getBefore == LIBUSB_SUCCESS) {
        LOGI("Current clock rate before SET_CUR: %u", beforeRate);
        if (beforeRate == requestedRate) {
            LOGI("Clock already at requested rate: %u", requestedRate);
            return LIBUSB_SUCCESS;
        }
    } else {
        LOGW("GET_CUR before SET_CUR failed: %s", libusb_error_name(getBefore));
    }

    // 2. SET_CUR
    int setResult = uac2SetCurSampleRate(
            devh,
            acInterface,
            clockEntityId,
            requestedRate
    );
    if (setResult == LIBUSB_SUCCESS) {
        usleep(20 * 1000);
        // 3. Verify with GET_CUR after SET_CUR
        uint32_t afterRate = 0;
        int getAfter = uac2GetCurSampleRate(
                devh,
                acInterface,
                clockEntityId,
                &afterRate
        );
        if (getAfter == LIBUSB_SUCCESS) {
            if (afterRate == requestedRate) {
                LOGI("Clock verified after SET_CUR: %u", afterRate);
                return LIBUSB_SUCCESS;
            }
            LOGW(
                    "Clock SET_CUR returned ok but GET_CUR mismatch: requested=%u actual=%u",
                    requestedRate,
                    afterRate
            );
            return LIBUSB_ERROR_OTHER;
        }
        // Some devices SET_CUR ok but GET_CUR fails. Don't hard fail.
        LOGW(
                "SET_CUR succeeded but GET_CUR after failed: %s, continue",
                libusb_error_name(getAfter)
        );
        return LIBUSB_SUCCESS;
    }

    // 4. SET_CUR failed try GET_CUR to see current state
    LOGW(
            "SET_CUR sample rate failed: requested=%u err=%s",
            requestedRate,
            libusb_error_name(setResult)
    );
    uint32_t afterFailedRate = 0;
    int getAfterFailed = uac2GetCurSampleRate(
            devh,
            acInterface,
            clockEntityId,
            &afterFailedRate
    );
    if (getAfterFailed == LIBUSB_SUCCESS) {
        LOGW(
                "Clock rate after failed SET_CUR: requested=%u current=%u",
                requestedRate,
                afterFailedRate
        );
        if (afterFailedRate == requestedRate) {
            LOGI("Despite SET_CUR failure, clock is already at requested rate");
            return LIBUSB_SUCCESS;
        }
    }

    // Return error for caller to log, but caller should NOT abort init
    return setResult;
}

// ==========================
// ISO transfer fill logic
// ==========================

// ==========================
// Dynamic sample rate configuration (UAC1 / UAC2)
// UAC2 path: best-effort, never hard-fail on SET_CUR EIO
// ==========================
static int configureSampleRateDynamic(
        UsbAudioContext *ctx,
        const std::vector<AcEntity> &acEntities,
        uint32_t sampleRate
) {
    if (!ctx) return -1;
    if (ctx->protocol == USB_AUDIO_UAC1) {
        LOGI("Configure sample rate: UAC1 path");
        if (ctx->clock.uac1EndpointHasSamplingFreqControl) {
            int setRet = uac1_set_endpoint_sample_rate(
                    ctx->devHandle,
                    ctx->epAddress,
                    sampleRate
            );
            if (setRet == 0) {
                usleep(30000);
                uint32_t actual = 0;
                int getRet = uac1_get_endpoint_sample_rate(
                        ctx->devHandle,
                        ctx->epAddress,
                        &actual
                );
                if (getRet == 0) {
                    if (actual != sampleRate) {
                        if (ctx->dsdSession) {
                            ctx->clock.deviceSampleRate = (int)sampleRate;
                            LOGE("DSD_TRANSPORT_CLOCK_MISMATCH keepCarrier=1 UAC1 "
                                 "requested=%u reported=%u mode=%s dsdHz=%u carrierHz=%u",
                                 sampleRate,
                                 actual,
                                 ctx->dsdDopTransport ? "DoP" : "NativeRAW",
                                 ctx->dsdRateHz,
                                 ctx->dsdCarrierRateHz);
                            return -1;
                        }
                        LOGE("UAC1 endpoint rate mismatch: requested=%u actual=%u; refusing unsafe stream",
                             sampleRate, actual);
                        return LIBUSB_ERROR_OTHER;
                    } else {
                        LOGI("UAC1 sample rate verified OK: %u", actual);
                        ctx->clock.deviceSampleRate = (int)actual;
                        ctx->sampleRate = (int)actual;
                    }
                } else {
                    ctx->clock.deviceSampleRate = (int)sampleRate;
                }
                return 0;
            }
            uint32_t actual = 0;
            const int getRet = uac1_get_endpoint_sample_rate(
                    ctx->devHandle, ctx->epAddress, &actual);
            if (getRet == 0 && actual == sampleRate) {
                LOGW("UAC1 endpoint SET failed but GET_CUR already matches requested rate=%u",
                     sampleRate);
                ctx->clock.deviceSampleRate = (int)actual;
                ctx->sampleRate = (int)actual;
                return 0;
            }
            LOGE("UAC1 endpoint sample-rate commit failed: requested=%u actual=%u set=%d get=%d",
                 sampleRate, actual, setRet, getRet);
            return setRet < 0 ? setRet : LIBUSB_ERROR_OTHER;
        }
        if (!ctx->clock.uac1RateDescriptorKnown ||
            ctx->clock.uac1DescriptorRate <= 0 ||
            ctx->clock.uac1DescriptorRate != static_cast<int>(sampleRate)) {
            const bool exactTransport = ctx->bitPerfectEnabled || ctx->dsdSession;
            AudioStreamCandidate fixedProbe;
            fixedProbe.protocol = USB_AUDIO_UAC1;
            fixedProbe.uac1EpHasSamplingFreqControl = ctx->clock.uac1EndpointHasSamplingFreqControl;
            fixedProbe.hasSampleRateList = ctx->clock.uac1RateDescriptorKnown;
            if (streamCanUseUnverifiedUac1FixedRateCompat(fixedProbe, exactTransport)) {
                // Compatibility lane matching UAPP: no endpoint frequency control means there is
                // nothing to SET. For ordinary PCM, keep the requested host clock and let the
                // transport run; diagnostics remain explicitly unverified. Exact transports still
                // fail above/below because they cannot prove bit-perfect timing.
                ctx->clock.deviceSampleRate = static_cast<int>(sampleRate);
                ctx->sampleRate = static_cast<int>(sampleRate);
                LOGW("UAC1_UNVERIFIED_FIXED_COMPAT commit skipped: requested=%u "
                     "descriptorKnown=%d descriptorRate=%d exactTransport=0",
                     sampleRate,
                     ctx->clock.uac1RateDescriptorKnown ? 1 : 0,
                     ctx->clock.uac1DescriptorRate);
                return 0;
            }
            LOGE("UAC1 fixed-rate stream refused: requested=%u descriptorKnown=%d descriptorRate=%d exactTransport=%d",
                 sampleRate,
                 ctx->clock.uac1RateDescriptorKnown ? 1 : 0,
                 ctx->clock.uac1DescriptorRate,
                 exactTransport ? 1 : 0);
            return LIBUSB_ERROR_NOT_SUPPORTED;
        }
        LOGI("UAC1 fixed-rate stream bound to descriptor rate=%d; no endpoint SET_CUR required",
             ctx->clock.uac1DescriptorRate);
        ctx->clock.deviceSampleRate = ctx->clock.uac1DescriptorRate;
        ctx->sampleRate = ctx->clock.uac1DescriptorRate;
        return 0;
    }
    if (ctx->protocol == USB_AUDIO_UAC2) {
        LOGI("Configure sample rate: UAC2 topology clock commit path");
        if (ctx->clock.clockEntityId == 0) {
            bindPlaybackClockForTerminal(ctx, acEntities, ctx->terminalLink);
        }
        if (ctx->clock.clockEntityId == 0) {
            LOGW("UAC2: Continuing without clock configuration; no bound playback clock for terminalLink=0x%02X",
                 ctx->terminalLink);
            ctx->clock.deviceSampleRate = (int)sampleRate;
            return 0;
        }
        if (!configureAndVerifyPlaybackClock(ctx, (int)sampleRate)) {
            LOGW("UAC2 playback clock commit not fully verified; requested=%u verified=%d validityKnown=%d valid=%d",
                 sampleRate,
                 ctx->clock.clockCommitVerifiedRate,
                 ctx->clock.clockValidityKnown ? 1 : 0,
                 ctx->clock.clockValid ? 1 : 0);
            // Keep best-effort semantics for generic PCM.  Bit-perfect policy can reject later.
            return -1;
        }
        LOGI("Playback clock committed: requested=%u verified=%d validKnown=%d valid=%d",
             sampleRate,
             ctx->clock.clockCommitVerifiedRate,
             ctx->clock.clockValidityKnown ? 1 : 0,
             ctx->clock.clockValid ? 1 : 0);
        return 0;
    }
    LOGW("Unknown USB Audio protocol, skip sample rate control");
    ctx->clock.deviceSampleRate = (int)sampleRate;
    return 0;
}

// ==========================
// findPlaybackFeatureUnit
// ==========================
static bool findPlaybackFeatureUnit(
        const std::vector<AcEntity> &entities,
        uint8_t terminalLink,
        uint8_t &outAcInterface,
        uint8_t &outFuId
) {
    outAcInterface = 0;
    outFuId = 0;

    if (terminalLink == 0) {
        return false;
    }

    /*
     * Playback path 常见拓扑：
     *
     * AS terminalLink -> InputTerminal(type USB Streaming)
     * InputTerminal id=X
     * FeatureUnit sourceId=X
     * OutputTerminal sourceId=FeatureUnit
     */
    for (const auto &e : entities) {
        if (e.subtype == AC_ENTITY_FEATURE_UNIT &&
            e.fuSourceId == terminalLink) {
            outAcInterface = e.acInterface;
            outFuId = e.id;
            LOGI("Playback FeatureUnit found directly: terminalLink=0x%02X FU=0x%02X acIface=%d",
                 terminalLink, outFuId, outAcInterface);
            return true;
        }
    }

    // 再尝试：OutputTerminal sourceId 指向 FeatureUnit，FeatureUnit sourceId 指向 terminalLink
    for (const auto &fu : entities) {
        if (fu.subtype != AC_ENTITY_FEATURE_UNIT) continue;
        for (const auto &ot : entities) {
            if (ot.subtype == AC_ENTITY_OUTPUT_TERMINAL &&
                ot.sourceId == fu.id &&
                fu.fuSourceId == terminalLink) {
                outAcInterface = fu.acInterface;
                outFuId = fu.id;
                LOGI("Playback FeatureUnit found via OutputTerminal: terminalLink=0x%02X FU=0x%02X OT=0x%02X acIface=%d",
                     terminalLink, outFuId, ot.id, outAcInterface);
                return true;
            }
        }
    }

    LOGW("No playback FeatureUnit found for terminalLink=0x%02X", terminalLink);
    return false;
}

// ==========================
// 为当前播放流选择 Feature Unit（基于 AcTopology）
// 永远解析，不bit-perfect 等策略影
// ==========================
static bool selectPlaybackFeatureUnit(UsbAudioContext* ctx, AcTopology& topo) {
    if (!ctx) return false;
    uint8_t terminalLink = ctx->terminalLink;
    LOGI("Selecting playback Feature Unit: terminalLink=0x%02X", terminalLink);

    auto applyFeatureUnitControlHints = [&](const AcEntity& fu) {
        constexpr int kFeatureUnitVolumeSelectorIndex = 1;
        auto hasVolumeControl = [&](size_t index) -> bool {
            return index < fu.controlsByChannel.size() &&
                   acControlIsPresent(fu.controlsByChannel[index], kFeatureUnitVolumeSelectorIndex);
        };
        ctx->descriptorHasMasterVolume = hasVolumeControl(0);
        ctx->descriptorHasLeftVolume = hasVolumeControl(1);
        ctx->descriptorHasRightVolume = hasVolumeControl(2);

        const uint32_t ch0 = fu.controlsByChannel.size() > 0 ? fu.controlsByChannel[0] : 0;
        const uint32_t ch1 = fu.controlsByChannel.size() > 1 ? fu.controlsByChannel[1] : 0;
        const uint32_t ch2 = fu.controlsByChannel.size() > 2 ? fu.controlsByChannel[2] : 0;
        LOGI("Playback FeatureUnit control hints: fu=0x%02X volume(master=%d left=%d right=%d) raw[ch0=0x%08X ch1=0x%08X ch2=0x%08X]",
             fu.id,
             ctx->descriptorHasMasterVolume ? 1 : 0,
             ctx->descriptorHasLeftVolume ? 1 : 0,
             ctx->descriptorHasRightVolume ? 1 : 0,
             ch0, ch1, ch2);
    };

    if (const AcEntity* chosenFu = choosePlaybackFeatureUnitForTerminal(topo, terminalLink)) {
        ctx->featureUnitPresent = true;
        ctx->playbackFeatureUnitId = chosenFu->id;
        ctx->playbackFeatureAcInterface = chosenFu->acInterface;
        applyFeatureUnitControlHints(*chosenFu);
        LOGI("Playback FeatureUnit selected: fu=0x%02X sourceId=0x%02X terminalLink=0x%02X acIface=%u",
             chosenFu->id, chosenFu->fuSourceId, terminalLink, chosenFu->acInterface);
        return true;
    }

    ctx->featureUnitPresent = false;
    ctx->playbackFeatureUnitId = 0;
    ctx->playbackFeatureAcInterface = 0;
    ctx->descriptorHasMasterVolume = false;
    ctx->descriptorHasLeftVolume = false;
    ctx->descriptorHasRightVolume = false;
    LOGW("No playback FeatureUnit found for terminalLink=0x%02X", terminalLink);
    return false;
}

// ==========================
// UAC1/UAC2 Feature Unit: GET_CUR volume
// ==========================
#define UAC_FU_VOLUME  0x02

static UsbHardwareVolumeView hardwareVolumeView(UsbAudioContext* ctx) {
    UsbHardwareVolumeView view;
    if (!ctx) return view;
    view.devHandle = ctx->devHandle;
    view.protocol = ctx->protocol;
    view.featureUnitId = ctx->playbackFeatureUnitId;
    view.acInterface = ctx->playbackFeatureAcInterface;
    view.path = ctx->featureUnitVolumePath;
    view.singleChannel = ctx->featureUnitSingleVolumeChannel;
    view.deviceMinRaw = ctx->deviceVolMinRaw;
    view.deviceMaxRaw = ctx->deviceVolMaxRaw;
    view.deviceResRaw = ctx->deviceVolResRaw;
    view.appMinRaw = ctx->volMinRaw;
    view.appMaxRaw = ctx->volMaxRaw;
    view.controlMutex = &ctx->featureUnitControlMutex;
    view.cachedRaw = &ctx->lastHardwareVolumeRaw;
    view.hasCachedRaw = &ctx->hasLastHardwareVolumeRaw;
    return view;
}

static int uac2GetCurVolume(UsbAudioContext* ctx, uint8_t channel, int16_t* outRaw) {
    return usbHardwareVolumeGetCur(hardwareVolumeView(ctx), channel, outRaw);
}

// ==========================
// UAC1/UAC2 Feature Unit: SET_CUR volume
// ==========================
static int uac2SetCurVolume(UsbAudioContext* ctx, uint8_t channel, int16_t raw) {
    return usbHardwareVolumeSetCur(hardwareVolumeView(ctx), channel, raw);
}

// ==========================
// UAC2 Feature Unit: GET_RANGE volume
// 两步查询法（参考两段式查询流程）：
// 1. 先发小请求获取 numSubranges
// 2. 根据 numSubranges 计算真实缓冲区大小，再发第二次请求
// 返回 wNumSubRanges + 每个 subrange (min, max, res)，各 2 bytes
// ==========================
static int uac2GetRangeVolume(
        UsbAudioContext* ctx,
        uint8_t channel,
        int16_t* outMin,
        int16_t* outMax,
        int16_t* outRes
) {
    return usbHardwareVolumeGetRange(
            hardwareVolumeView(ctx), channel, outMin, outMax, outRes);
#if 0
    if (!ctx || !outMin || !outMax || !outRes) return -1;
    uint16_t wValue = (UAC_FU_VOLUME << 8) | channel;
    uint16_t wIndex = (ctx->playbackFeatureUnitId << 8) | ctx->playbackFeatureAcInterface;

    if (ctx->protocol == USB_AUDIO_UAC1) {
        auto getLegacyRangeValue = [&](uint8_t request, int16_t* outValue) -> int {
            uint8_t data[2] = {0};
            const int result = libusb_control_transfer(
                    ctx->devHandle,
                    LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
                    request,
                    wValue,
                    wIndex,
                    data,
                    sizeof(data),
                    500);
            if (result != 2) return result < 0 ? result : -2;
            *outValue = static_cast<int16_t>(data[0] | (data[1] << 8));
            return 0;
        };
        const int minResult = getLegacyRangeValue(UAC1_GET_MIN, outMin);
        const int maxResult = getLegacyRangeValue(UAC1_GET_MAX, outMax);
        const int resResult = getLegacyRangeValue(UAC1_GET_RES, outRes);
        if (minResult != 0 || maxResult != 0 || resResult != 0) {
            LOGW("UAC1 GET_RANGE volume failed: fu=0x%02X ch=%u min=%d max=%d res=%d",
                 ctx->playbackFeatureUnitId, channel, minResult, maxResult, resResult);
            return minResult != 0 ? minResult : (maxResult != 0 ? maxResult : resResult);
        }
        LOGI("UAC1 GET_RANGE volume ok: fu=0x%02X ch=%u min=%.2f max=%.2f res=%.2f",
             ctx->playbackFeatureUnitId, channel,
             *outMin / 256.0f, *outMax / 256.0f, *outRes / 256.0f);
        return 0;
    }

    // 第一步：只请求 2 字节，获取 numSubranges
    uint8_t header[2] = {0};
    int r = libusb_control_transfer(
            ctx->devHandle,
            LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
            UAC2_REQ_RANGE,
            wValue,
            wIndex,
            header,
            sizeof(header),
            500
    );
    if (r < 2) {
        LOGW("UAC2 GET_RANGE volume header failed: fu=0x%02X ch=%u r=%d",
             ctx->playbackFeatureUnitId, channel, r);
        return r < 0 ? r : -2;
    }

    uint16_t numSubranges = header[0] | (header[1] << 8);
    if (numSubranges < 1) {
        LOGW("UAC2 GET_RANGE volume invalid numSubranges=0 ch=%u", channel);
        return -3;
    }

    // 第二步：UAC2 规范请求 32-bit 大小，然后根据实际返回字节数判断格式
    // UAC2 规范: wNumSubRanges(2) + [dwMIN(4) + dwMAX(4) + dwRES(4)] * n = 2 + n*12
    // 但很多设备返16-bit: wNumSubRanges(2) + [wMIN(2) + wMAX(2) + wRES(2)] * n = 2 + n*6
    size_t size32 = 2 + (numSubranges * 12);
    size_t size16 = 2 + (numSubranges * 6);
    size_t requestSize = std::min(size32, (size_t)256);  // 限制256 字节
    if (requestSize < 8) requestSize = 8;  // 至少请求 8 字节（header + 1 subrange

    std::vector<uint8_t> data(requestSize, 0);
    r = libusb_control_transfer(
            ctx->devHandle,
            LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
            UAC2_REQ_RANGE,
            wValue,
            wIndex,
            data.data(),
            static_cast<uint16_t>(requestSize),
            500
    );
    if (r < 8) {
        LOGW("UAC2 GET_RANGE volume full query failed: fu=0x%02X ch=%u r=%d",
             ctx->playbackFeatureUnitId, channel, r);
        return r < 0 ? r : -2;
    }

    // 第三步：根据实际返回字节数判断是 16-bit 还是 32-bit
    // UAC2 规范要求 32-bit，但很多设备返回 16-bit
    // 特殊情况：r=8 且 numSubranges=1 时，可能是 32-bit（只有 MIN+MAX，无 RES），也可能是 16-bit（MIN+MAX+RES+padding）
    int actualBytesPerSubrange = (r - 2) / numSubranges;

    // 先尝试按 16-bit 解析
    int16_t min16 = (int16_t)(data[2] | (data[3] << 8));
    int16_t max16 = (int16_t)(data[4] | (data[5] << 8));
    int16_t res16 = (r >= 8) ? (int16_t)(data[6] | (data[7] << 8)) : 0;

    // 再尝试按 32-bit 解析（至少需要 10 字节）
    int32_t min32 = 0, max32 = 0, res32 = 0;
    if (r >= 10) {
        min32 = (int32_t)(data[2] | (data[3] << 8) | (data[4] << 16) | (data[5] << 24));
        max32 = (int32_t)(data[6] | (data[7] << 8) | (data[8] << 16) | (data[9] << 24));
    }
    if (r >= 14) {
        res32 = (int32_t)(data[10] | (data[11] << 8) | (data[12] << 16) | (data[13] << 24));
    }

    // 判断使用哪种解析结果
    // 启发式规则：如果 16-bit 结果 min==max res==0，但 32-bit 结果更合理，则用 32-bit
    bool use32bit = false;
    if (actualBytesPerSubrange >= 12) {
        // 明确32-bit
        use32bit = true;
    } else if (r >= 10 && min16 == max16 && res16 == 0 && min32 != max32) {
        // 16-bit 结果不合理（min==max），尝试 32-bit
        use32bit = true;
        LOGW("UAC2 GET_RANGE: 16-bit parse gives min==max==%d, retrying as 32-bit", min16);
    }

    int32_t minRaw = 0, maxRaw = 0, resRaw = 0;
    if (use32bit) {
        minRaw = min32;
        maxRaw = max32;
        resRaw = res32;

        // 关键修复：检测设备返回的“零扩展 16 位”
        // 很多USB DAC6位音量零扩展2位字段中，导致负值变
        // 例如 0x0000C080 应解释为 int16_t 0xC080 = -16256 (-63.5dB)
        // 而不是 uint32_t 49280（192.5dB，物理上不可能）
        // 判断条件：高16位全6位的bit15（负数区域）
        if ((min32 & 0xFFFF0000) == 0 && (uint16_t)(min32 & 0xFFFF) >= 0x8000) {
            int32_t reinterpreted = (int16_t)(min32 & 0xFFFF);
            LOGI("UAC2 GET_RANGE: min32=%d is zero-extended 16-bit, reinterpreted as %d (%.2fdB)",
                 min32, reinterpreted, reinterpreted / 256.0f);
            minRaw = reinterpreted;
        }
        if ((max32 & 0xFFFF0000) == 0 && (uint16_t)(max32 & 0xFFFF) >= 0x8000) {
            int32_t reinterpreted = (int16_t)(max32 & 0xFFFF);
            LOGI("UAC2 GET_RANGE: max32=%d is zero-extended 16-bit, reinterpreted as %d (%.2fdB)",
                 max32, reinterpreted, reinterpreted / 256.0f);
            maxRaw = reinterpreted;
        }
        if ((res32 & 0xFFFF0000) == 0 && (uint16_t)(res32 & 0xFFFF) >= 0x8000) {
            int32_t reinterpreted = (int16_t)(res32 & 0xFFFF);
            resRaw = reinterpreted;
        }

        LOGI("UAC2 GET_RANGE volume 32-bit: fu=0x%02X ch=%u min=%d max=%d res=%d numSubranges=%u",
             ctx->playbackFeatureUnitId, channel, minRaw, maxRaw, resRaw, numSubranges);
    } else {
        minRaw = min16;
        maxRaw = max16;
        resRaw = res16;
        LOGI("UAC2 GET_RANGE volume 16-bit: fu=0x%02X ch=%u min=%.2f max=%.2f res=%.2f numSubranges=%u",
             ctx->playbackFeatureUnitId, channel, minRaw / 256.0f, maxRaw / 256.0f, resRaw / 256.0f, numSubranges);
    }

    // 输出结果（截断到 int16_t 范围，保持函数签名不变）
    *outMin = static_cast<int16_t>(std::clamp(minRaw, (int32_t)INT16_MIN, (int32_t)INT16_MAX));
    *outMax = static_cast<int16_t>(std::clamp(maxRaw, (int32_t)INT16_MIN, (int32_t)INT16_MAX));
    *outRes = static_cast<int16_t>(std::clamp(resRaw, (int32_t)INT16_MIN, (int32_t)INT16_MAX));
    return 0;
#endif
}

// ==========================
// 硬件音量能力验证
// 1. 必须playback Feature Unit
// 2. 尝试 ch0 master / ch1 left / ch2 right GET_RANGE
// 3. 判断支持 master-only 还是 stereo-pair
// 4. 如果只支持 left 而不支持 right，则禁用硬件音量
// 5. 只读当前值；真正的设备级初始写入由 Kotlin 在 ISO 启动前执行一次
// ==========================

// ========================== Volume path selection ==========================


static FeatureUnitVolumePath chooseFeatureUnitVolumePath(UsbAudioContext* ctx) {
    if (!ctx) return FeatureUnitVolumePath::None;

    const FeatureUnitVolumePath path = rawsmusic::usb::chooseFeatureUnitVolumePath(
            ctx->hasMasterVolume,
            ctx->hasLeftVolume,
            ctx->hasRightVolume);
    if (path == FeatureUnitVolumePath::Master) {
        ctx->masterVolumeWritable = true;
        ctx->featureUnitVolumePathName = "master";
        return FeatureUnitVolumePath::Master;
    }

    if (path == FeatureUnitVolumePath::LinkedChannels) {
        ctx->leftVolumeWritable = true;
        ctx->rightVolumeWritable = true;
        ctx->featureUnitVolumePathName = "linked-channels";
        return FeatureUnitVolumePath::LinkedChannels;
    }

    ctx->featureUnitVolumePathName = "none";
    return FeatureUnitVolumePath::None;
}

// ========================== Volume range sanitization ==========================

static int16_t quantizeVolumeRawToDeviceRes(UsbAudioContext* ctx, int raw) {
    return usbHardwareVolumeQuantize(hardwareVolumeView(ctx), raw);
}

static int16_t hardwareDbToRaw1DbStep(UsbAudioContext* ctx, int db) {
    return quantizeVolumeRawToDeviceRes(ctx, hardwareVolumeDbToRaw(db));
}

static void sanitizeHardwareVolumeRange(
        UsbAudioContext* ctx,
        int16_t deviceMinRaw,
        int16_t deviceMaxRaw,
        int16_t deviceResRaw
) {
    ctx->deviceVolMinRaw = deviceMinRaw;
    ctx->deviceVolMaxRaw = deviceMaxRaw;
    ctx->deviceVolResRaw = deviceResRaw;
    const auto appRange = rawsmusic::usb::sanitizeHardwareVolumeRange(
            deviceMinRaw, deviceMaxRaw, deviceResRaw);
    ctx->volMinRaw = appRange.minRaw;
    ctx->volMaxRaw = appRange.maxRaw;
    ctx->volResRaw = appRange.stepRaw;
    LOGI("Hardware volume range: device=[%.2f..%.2f] res=%.2f, app=[%.2f..%.2f] step=1dB",
         deviceMinRaw / 256.0, deviceMaxRaw / 256.0, deviceResRaw / 256.0,
         ctx->volMinRaw / 256.0, ctx->volMaxRaw / 256.0);
}

// ========================== Linked channel validation ==========================

static bool readHardwareCurrentRawForPath(UsbAudioContext* ctx, int16_t* outRaw);
static bool isAudibleVolumeRouteReady(const UsbAudioContext* ctx);
static void maybeMarkUsbAudibleAccepted(UsbAudioContext* ctx, const char* reason);
static int bestEffortRestoreFeatureUnitUnityNoCache(UsbAudioContext* ctx, const char* reason);

static void syncUsbRuntimeModel(UsbAudioContext* ctx) {
    if (!ctx) return;
    ctx->runtimeFormat = synchronizeRuntimeFormat(
            ctx->runtimeFormat,
            ctx->clock.deviceSampleRate,
            ctx->sampleRate,
            ctx->deviceChannels,
            ctx->channels,
            ctx->deviceBitDepth,
            ctx->sourceBitDepth,
            ctx->bitDepth,
            ctx->deviceSubslotSize,
            ctx->bytesPerSample,
            ctx->interfaceNumber,
            ctx->altSetting,
            ctx->epAddress,
            ctx->maxPacketSize,
            ctx->endpointInterval,
            ctx->feedbackEpAddress);
}

static void recordIsoSubmitDiagnostics(UsbAudioContext* ctx, const libusb_transfer* xfer) {
    if (!ctx || !xfer) return;
    recordIsoSubmitStats(
            ctx->isoSubmittedTransfers,
            ctx->isoSubmittedBytes,
            ctx->isoMaxInFlightTransfers,
            xfer->length,
            ctx->pendingTransfers.load(std::memory_order_relaxed));
}

static void resetUsbRuntimeStats(UsbAudioContext* ctx) {
    if (!ctx) return;
    ctx->statsAppBytes.store(0, std::memory_order_relaxed);
    ctx->statsScheduledUsbBytes.store(0, std::memory_order_relaxed);
    ctx->serviceIntervalAutoRepairDone.store(false, std::memory_order_release);
    ctx->serviceIntervalMeasuredRepairActive.store(false, std::memory_order_release);
    ctx->lastEventLoopGapMs.store(0, std::memory_order_release);
    ctx->lastEventLoopGapDurationMs.store(0, std::memory_order_release);
    ctx->statsCompletedUsbBytes.store(0, std::memory_order_relaxed);
    ctx->statsTotalCompletedUsbBytes.store(0, std::memory_order_relaxed);
    ctx->statsUsbBytes.store(0, std::memory_order_relaxed);
    ctx->statsUnderrun.store(0, std::memory_order_relaxed);
    ctx->statsCallbackCount.store(0, std::memory_order_relaxed);
    ctx->statsPacketCount.store(0, std::memory_order_relaxed);
    ctx->statsSubmitError.store(0, std::memory_order_relaxed);
    ctx->statsPacketError.store(0, std::memory_order_relaxed);
    ctx->statsXferError.store(0, std::memory_order_relaxed);
    ctx->statsWindowStartMs.store(nowSteadyMs(), std::memory_order_relaxed);
    ctx->lastAppBytesPerSec.store(0, std::memory_order_relaxed);
    ctx->lastScheduledUsbBytesPerSec.store(0, std::memory_order_relaxed);
    ctx->lastCompletedUsbBytesPerSec.store(0, std::memory_order_relaxed);
    ctx->lastUnderrun.store(0, std::memory_order_relaxed);
    ctx->lastSubmitError.store(0, std::memory_order_relaxed);
    ctx->lastPacketError.store(0, std::memory_order_relaxed);
    ctx->lastXferError.store(0, std::memory_order_relaxed);
    ctx->lastPacketCount.store(0, std::memory_order_relaxed);
    ctx->lastCallbackCount.store(0, std::memory_order_relaxed);
    ctx->isoSubmittedTransfers.store(0, std::memory_order_relaxed);
    ctx->isoCompletedTransfers.store(0, std::memory_order_relaxed);
    ctx->isoSubmittedBytes.store(0, std::memory_order_relaxed);
    ctx->isoActualLengthBytes.store(0, std::memory_order_relaxed);
    ctx->isoMaxInFlightTransfers.store(0, std::memory_order_relaxed);
    ctx->isoZeroActualPackets.store(0, std::memory_order_relaxed);
    ctx->isoCompletedStatusPackets.store(0, std::memory_order_relaxed);
    ctx->isoErroredStatusPackets.store(0, std::memory_order_relaxed);
    ctx->isoCancelledStatusPackets.store(0, std::memory_order_relaxed);
    ctx->isoOtherStatusPackets.store(0, std::memory_order_relaxed);
    ctx->isoLastCallbackMs.store(0, std::memory_order_relaxed);
    ctx->isoMaxCallbackGapMs.store(0, std::memory_order_relaxed);
    ctx->isoCallbackGapTotalMs.store(0, std::memory_order_relaxed);
    ctx->isoCallbackGapCount.store(0, std::memory_order_relaxed);
    ctx->resetAltAttempts.store(0, std::memory_order_relaxed);
    ctx->resetAltLastResult.store(0, std::memory_order_relaxed);
    ctx->resetAltSelectedLastResult.store(0, std::memory_order_relaxed);
    ctx->silentProbeAttempted.store(0, std::memory_order_relaxed);
    ctx->silentProbeSubmitResult.store(0, std::memory_order_relaxed);
    ctx->silentProbeTransferStatus.store(0, std::memory_order_relaxed);
    ctx->silentProbeCompleted.store(0, std::memory_order_relaxed);
    ctx->silentProbeActualLength.store(0, std::memory_order_relaxed);
    ctx->silentProbeScheduledLength.store(0, std::memory_order_relaxed);
    ctx->silentProbeZeroActualPackets.store(0, std::memory_order_relaxed);
    ctx->silentProbePacketErrors.store(0, std::memory_order_relaxed);

    const int64_t sessionStartMs = nowSteadyMs();
    ctx->audibleStartMs.store(sessionStartMs, std::memory_order_release);
    ctx->audibleFirstCompletionMs.store(0, std::memory_order_release);
    ctx->audibleAcceptedMs.store(0, std::memory_order_release);
    ctx->audibleAcceptedSessionId.store(0, std::memory_order_release);
    ctx->audibleAcceptedCompletedBytes.store(0, std::memory_order_release);
    ctx->audibleAccepted.store(false, std::memory_order_release);
}

static void markFeatureUnitPolicy(
        UsbAudioContext* ctx,
        FeatureUnitPolicyState state,
        const char* reason,
        int result
) {
    if (!ctx) return;
    ctx->featureUnitPolicyState = state;
    ctx->featureUnitPolicyReason = reason ? reason : "unknown";
    ctx->featureUnitValidationResult = result;
    LOGI("FeatureUnit policy: state=%s reason=%s result=%d fu=0x%02X path=%s",
         featureUnitPolicyStateName(state),
         ctx->featureUnitPolicyReason,
         result,
         ctx->playbackFeatureUnitId,
         ctx->featureUnitVolumePathName ? ctx->featureUnitVolumePathName : "none");
}

static void setHardwareVolumeState(
        UsbAudioContext* ctx,
        bool enabled,
        bool safe,
        const char* reason
) {
    if (!ctx) return;
    const bool preserveController = !enabled && shouldPreserveFeatureUnitControllerOnDisable(reason);
    ctx->hardwareVolumeEnabled = enabled;
    ctx->hardwareVolumeSafe = safe;
    ctx->hardwareFeatureUnitEnabled = enabled && safe;
    ctx->hardwareVolumeCapable =
            ctx->featureUnitPresent &&
            (ctx->hasMasterVolume || (ctx->hasLeftVolume && ctx->hasRightVolume) ||
             ctx->featureUnitVolumePath == FeatureUnitVolumePath::SingleChannel);

    // 路径隔离：禁用当前音量路径时，不能清掉已经验证过的
    // Feature Unit controller.  The stream profile can keep running in software
    // volume mode, and a later user toggle can re-enable the same master/L/R
    // controller without a full USB reinit.  Only real safety failures should
    // discard the cached controller path.
    if (!enabled || !safe) {
        if (!preserveController) {
            ctx->featureUnitVolumePath = FeatureUnitVolumePath::None;
            ctx->featureUnitVolumePathName = "none";
            ctx->featureUnitRangeVerified = false;
            ctx->featureUnitReadbackVerified = false;
        }
        if (ctx->featureUnitPolicyState != FeatureUnitPolicyState::Unsafe &&
            ctx->featureUnitPolicyState != FeatureUnitPolicyState::NotPresent) {
            markFeatureUnitPolicy(ctx, FeatureUnitPolicyState::DisabledByPolicy, reason, 0);
        }
    }
    LOGI("Hardware volume state: enabled=%d safe=%d mode=%d preserveController=%d reason=%s",
         enabled ? 1 : 0, safe ? 1 : 0, (int)ctx->playbackMode,
         preserveController ? 1 : 0,
         reason ? reason : "unknown");
}

static bool hasCachedFeatureUnitController(const UsbAudioContext* ctx) {
    if (!ctx) return false;
    if (!ctx->featureUnitPresent || ctx->playbackFeatureUnitId == 0) return false;
    if (!ctx->featureUnitRangeVerified && !ctx->hardwareVolumeCapable) return false;
    return ctx->featureUnitVolumePath == FeatureUnitVolumePath::Master ||
           ctx->featureUnitVolumePath == FeatureUnitVolumePath::LinkedChannels ||
           ctx->featureUnitVolumePath == FeatureUnitVolumePath::SingleChannel;
}

static bool enableCachedFeatureUnitController(UsbAudioContext* ctx, const char* reason) {
    if (!hasCachedFeatureUnitController(ctx)) return false;
    ctx->hardwareVolumeCapable = true;
    ctx->hardwareVolumeSafe = true;
    ctx->hardwareVolumeEnabled = true;
    ctx->hardwareFeatureUnitEnabled = true;
    if (ctx->featureUnitVolumePath == FeatureUnitVolumePath::Master) {
        markFeatureUnitPolicy(ctx, FeatureUnitPolicyState::SafeMaster, reason, 0);
    } else if (ctx->featureUnitVolumePath == FeatureUnitVolumePath::LinkedChannels) {
        markFeatureUnitPolicy(ctx, FeatureUnitPolicyState::SafeLinkedChannels, reason, 0);
    } else {
        markFeatureUnitPolicy(ctx, FeatureUnitPolicyState::SafeSingleChannel, reason, 0);
    }
    LOGI("Hardware volume controller re-enabled from cached FeatureUnitPolicy: fu=0x%02X path=%s reason=%s",
         ctx->playbackFeatureUnitId,
         ctx->featureUnitVolumePathName ? ctx->featureUnitVolumePathName : "none",
         reason ? reason : "unknown");
    return true;
}

// Helper to write raw volume by path
// Live hardware-volume entry points. The USB engine keeps policy, lifecycle and
// safety decisions here, while UAC control transfers and channel stepping live
// in usb_hardware_volume.cpp.
static int setHardwareUserVolumeRaw(UsbAudioContext* ctx, int16_t raw) {
    if (!ctx || !ctx->devHandle) return -1;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    const auto view = hardwareVolumeView(ctx);
    raw = usbHardwareVolumeQuantize(view, raw);

    if (ctx->hasLastHardwareVolumeRaw.load(std::memory_order_acquire) &&
        ctx->lastHardwareVolumeRaw.load(std::memory_order_acquire) == raw) {
        LOGI("Feature Unit SET_CUR dedup: raw=%d db=%.2f path=%s",
             raw, raw / 256.0f,
             ctx->featureUnitVolumePathName ? ctx->featureUnitVolumePathName : "none");
        return 0;
    }

    const int result = usbHardwareVolumeSetPath(view, raw);
    if (result == 0) {
        g_lastRequestedHardwareVolumeRaw.store(raw, std::memory_order_release);
        g_hasLastRequestedHardwareVolumeRaw.store(true, std::memory_order_release);
        ctx->lastHardwareVolumeRaw.store(raw, std::memory_order_release);
        ctx->hasLastHardwareVolumeRaw.store(true, std::memory_order_release);
    }
    return result;
}

static int setHardwareTransientVolumeRawNoCache(UsbAudioContext* ctx, int16_t raw) {
    if (!ctx || !ctx->devHandle) return -1;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    const auto view = hardwareVolumeView(ctx);
    raw = usbHardwareVolumeQuantize(view, raw);
    const int result = usbHardwareVolumeSetPath(view, raw);
    if (result == 0) {
        ctx->lastHardwareVolumeRaw.store(raw, std::memory_order_release);
        ctx->hasLastHardwareVolumeRaw.store(true, std::memory_order_release);
    }
    return result;
}

static bool readHardwareCurrentRawForPath(UsbAudioContext* ctx, int16_t* outRaw) {
    if (!ctx || !outRaw || !ctx->devHandle) return false;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    return usbHardwareVolumeReadPath(hardwareVolumeView(ctx), outRaw);
}

static int adjustHardwareUserVolumeRaw(UsbAudioContext* ctx, int direction, int16_t* outRaw) {
    if (!ctx || !ctx->devHandle || !outRaw || direction == 0) return -1;
    const int result = usbHardwareVolumeAdjust(hardwareVolumeView(ctx), direction, outRaw);
    if (result == 0) {
        g_lastRequestedHardwareVolumeRaw.store(*outRaw, std::memory_order_release);
        g_hasLastRequestedHardwareVolumeRaw.store(true, std::memory_order_release);
    }
    return result;
}

static bool isAudibleVolumeRouteReady(const UsbAudioContext* ctx) {
    // Hardware volume is initialized and read back before nativeStart submits ISO. There is no
    // callback-time safe->restore phase, so every valid playback route is ready here.
    return ctx != nullptr;
}

static void maybeMarkUsbAudibleAccepted(UsbAudioContext* ctx, const char* reason) {
    if (!ctx) return;
    if (ctx->audibleAccepted.load(std::memory_order_acquire)) return;
    if (!ctx->streaming.load(std::memory_order_acquire)) return;
    if (ctx->statsCompletedUsbBytes.load(std::memory_order_acquire) <= 0) return;
    if (!isAudibleVolumeRouteReady(ctx)) return;

    bool expected = false;
    if (!ctx->audibleAccepted.compare_exchange_strong(
            expected, true, std::memory_order_acq_rel, std::memory_order_acquire)) {
        return;
    }
    const int64_t nowMs = nowSteadyMs();
    const int64_t session = ctx->streamSessionId.load(std::memory_order_acquire);
    const int64_t completed = ctx->statsCompletedUsbBytes.load(std::memory_order_acquire);
    ctx->audibleAcceptedMs.store(nowMs, std::memory_order_release);
    ctx->audibleAcceptedSessionId.store(session, std::memory_order_release);
    ctx->audibleAcceptedCompletedBytes.store(completed, std::memory_order_release);
    LOGI("USB audible accepted: reason=%s session=%lld completedBytes=%lld firstCompletionMs=%lld volumeRouteReady=%d",
         reason ? reason : "unknown",
         (long long)session,
         (long long)completed,
         (long long)ctx->audibleFirstCompletionMs.load(std::memory_order_acquire),
         isAudibleVolumeRouteReady(ctx) ? 1 : 0);
}

static int uac2SetCurMuteNoCache(UsbAudioContext* ctx, uint8_t channel, bool mute) {
    if (!ctx || !ctx->devHandle || ctx->playbackFeatureUnitId == 0) return -1;
    uint16_t wValue = (0x01 << 8) | channel;
    uint16_t wIndex = (ctx->playbackFeatureUnitId << 8) | ctx->playbackFeatureAcInterface;
    uint8_t data[1] = { static_cast<uint8_t>(mute ? 1 : 0) };
    int r = libusb_control_transfer(
            ctx->devHandle,
            LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
            UAC2_REQ_CUR,
            wValue,
            wIndex,
            data,
            sizeof(data),
            500
    );
    if (r != 1) {
        LOGW("UAC2 SET_CUR mute failed: fu=0x%02X ch=%u mute=%d r=%d",
             ctx->playbackFeatureUnitId, channel, mute ? 1 : 0, r);
        return r < 0 ? r : -2;
    }
    LOGI("UAC2 SET_CUR mute ok: fu=0x%02X ch=%u mute=%d",
         ctx->playbackFeatureUnitId, channel, mute ? 1 : 0);
    return 0;
}

static int bestEffortRestoreFeatureUnitUnityNoCache(UsbAudioContext* ctx, const char* reason) {
    if (!ctx || !ctx->devHandle || ctx->playbackFeatureUnitId == 0) return -1;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);

    // Do not mark hardware volume as enabled and do not update the user-volume
    // cache.  This is only a repair for DACs that persist a previous -32 dB
    // emergency value across sessions.  Try master and stereo channels because
    // many UAC2 devices advertise no ch0 volume but do expose ch1/ch2.
    int ok = 0;
    int fail = 0;

    for (uint8_t ch : { (uint8_t)0, (uint8_t)1, (uint8_t)2 }) {
        int mr = uac2SetCurMuteNoCache(ctx, ch, false);
        if (mr == 0) ok++; else fail++;
    }
    for (uint8_t ch : { (uint8_t)0, (uint8_t)1, (uint8_t)2 }) {
        int vr = uac2SetCurVolume(ctx, ch, 0);
        if (vr == 0) ok++; else fail++;
    }

    LOGI("Feature Unit unity repair: reason=%s ok=%d fail=%d fu=0x%02X acIface=%d",
         reason ? reason : "unknown", ok, fail,
         ctx->playbackFeatureUnitId, ctx->playbackFeatureAcInterface);
    return ok > 0 ? 0 : -2;
}

static int validateHardwareVolume(UsbAudioContext* ctx) {
    if (!ctx) return -1;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);

    ctx->featureUnitValidationAttempted = true;
    ctx->featureUnitRangeVerified = false;
    ctx->featureUnitReadbackVerified = false;
    ctx->hardwareVolumeCapable = false;
    ctx->hardwareVolumeSafe = false;
    ctx->hardwareVolumeEnabled = false;
    ctx->hardwareFeatureUnitEnabled = false;
    ctx->featureUnitAvailable = false;
    ctx->featureUnitHasMasterVolume = false;
    ctx->masterVolumeWritable = false;
    ctx->leftVolumeWritable = false;
    ctx->rightVolumeWritable = false;
    ctx->featureUnitVolumePath = FeatureUnitVolumePath::None;
    ctx->featureUnitVolumePathName = "none";
    ctx->featureUnitSingleVolumeChannel = 0;
    markFeatureUnitPolicy(ctx, FeatureUnitPolicyState::Present, "probing", 0);

    if (!ctx->featureUnitPresent || ctx->playbackFeatureUnitId == 0) {
        LOGW("validateHardwareVolume: no playback Feature Unit present");
        markFeatureUnitPolicy(ctx, FeatureUnitPolicyState::NotPresent, "no-playback-feature-unit", -2);
        return -2;
    }

    int16_t min0 = 0, max0 = 0, res0 = 0;
    int16_t min1 = 0, max1 = 0, res1 = 0;
    int16_t min2 = 0, max2 = 0, res2 = 0;
    const int r0 = uac2GetRangeVolume(ctx, 0, &min0, &max0, &res0);
    const int r1 = uac2GetRangeVolume(ctx, 1, &min1, &max1, &res1);
    const int r2 = uac2GetRangeVolume(ctx, 2, &min2, &max2, &res2);

    int16_t probeCur0 = 0, probeCur1 = 0, probeCur2 = 0;
    const int g0 = uac2GetCurVolume(ctx, 0, &probeCur0);
    const int g1 = uac2GetCurVolume(ctx, 1, &probeCur1);
    const int g2 = uac2GetCurVolume(ctx, 2, &probeCur2);

    const bool masterRange = (r0 == 0 && min0 < max0);
    const bool leftRange = (r1 == 0 && min1 < max1);
    const bool rightRange = (r2 == 0 && min2 < max2);
    const bool masterCur = (g0 == 0);
    const bool leftCur = (g1 == 0);
    const bool rightCur = (g2 == 0);
    const bool descriptorAnyVolume =
            ctx->descriptorHasMasterVolume ||
            ctx->descriptorHasLeftVolume ||
            ctx->descriptorHasRightVolume;
    // Feature Unit 控制可抽象为一组音量控制器。GET_CUR may be missing or
    // flaky, so read-only discovery accepts a sane range as a candidate. Actual
    // writability is proved only by the one pre-ISO initialization SET_CUR owned
    // by PlayerController; a failed write keeps the hardware route disabled.
    const bool masterCandidate = masterRange &&
                                 (!descriptorAnyVolume || ctx->descriptorHasMasterVolume);
    const bool leftCandidate = leftRange &&
                               (!descriptorAnyVolume || ctx->descriptorHasLeftVolume);
    const bool rightCandidate = rightRange &&
                                (!descriptorAnyVolume || ctx->descriptorHasRightVolume);
    const bool stereoCandidate = leftCandidate && rightCandidate;
    const bool singleCandidate = !masterCandidate && !stereoCandidate && (leftCandidate || rightCandidate);

    ctx->masterChannelExists = masterRange;

    LOGI("FeatureUnit policy probe: fu=0x%02X descVol(m=%d L=%d R=%d) range(m=%d L=%d R=%d) cur(m=%d L=%d R=%d)",
         ctx->playbackFeatureUnitId,
         ctx->descriptorHasMasterVolume ? 1 : 0,
         ctx->descriptorHasLeftVolume ? 1 : 0,
         ctx->descriptorHasRightVolume ? 1 : 0,
         masterRange ? 1 : 0, leftRange ? 1 : 0, rightRange ? 1 : 0,
         masterCur ? 1 : 0, leftCur ? 1 : 0, rightCur ? 1 : 0);
    LOGI("    ch0 RANGE r=%d min=%d max=%d res=%d CUR r=%d cur=%d", r0, min0, max0, res0, g0, probeCur0);
    LOGI("    ch1 RANGE r=%d min=%d max=%d res=%d CUR r=%d cur=%d", r1, min1, max1, res1, g1, probeCur1);
    LOGI("    ch2 RANGE r=%d min=%d max=%d res=%d CUR r=%d cur=%d", r2, min2, max2, res2, g2, probeCur2);

    if (!masterCandidate && !stereoCandidate && !singleCandidate) {
        ctx->hasMasterVolume = false;
        ctx->hasLeftVolume = leftCandidate;
        ctx->hasRightVolume = rightCandidate;
        ctx->hardwareVolumeCapable = false;
        markFeatureUnitPolicy(ctx, FeatureUnitPolicyState::Unsafe,
                              "requires-ranged-volume-controller", -3);
        LOGW("validateHardwareVolume: no ranged volume path. master=%d stereo=%d single=%d",
             masterCandidate ? 1 : 0, stereoCandidate ? 1 : 0, singleCandidate ? 1 : 0);
        return -3;
    }

    ctx->featureUnitRangeVerified = true;
    ctx->hasMasterVolume = masterCandidate;
    ctx->hasLeftVolume = leftCandidate;
    ctx->hasRightVolume = rightCandidate;
    ctx->featureUnitHasMasterVolume = masterCandidate;
    ctx->hardwareVolumeCapable = true;

    if (masterCandidate) {
        ctx->volMinRaw = min0;
        ctx->volMaxRaw = max0;
        ctx->volumeMinDb = (float)min0 / 256.0f;
        ctx->volumeMaxDb = (float)max0 / 256.0f;
        sanitizeHardwareVolumeRange(ctx, min0, max0, res0 > 0 ? res0 : 128);
        ctx->featureUnitVolumePath = FeatureUnitVolumePath::Master;
        ctx->featureUnitVolumePathName = "master";
        ctx->masterVolumeWritable = true;
    } else if (stereoCandidate) {
        ctx->volMinRaw = std::max(min1, min2);
        ctx->volMaxRaw = std::min(max1, max2);
        ctx->volumeMinDb = (float)ctx->volMinRaw / 256.0f;
        ctx->volumeMaxDb = (float)ctx->volMaxRaw / 256.0f;
        const int16_t res = (res1 > 0) ? res1 : ((res2 > 0) ? res2 : 128);
        sanitizeHardwareVolumeRange(ctx, ctx->volMinRaw, ctx->volMaxRaw, res);
        ctx->featureUnitVolumePath = FeatureUnitVolumePath::LinkedChannels;
        ctx->featureUnitVolumePathName = "linked-channels";
        ctx->leftVolumeWritable = true;
        ctx->rightVolumeWritable = true;
    } else {
        const bool useLeft = leftCandidate;
        ctx->featureUnitSingleVolumeChannel = useLeft ? 1 : 2;
        ctx->volMinRaw = useLeft ? min1 : min2;
        ctx->volMaxRaw = useLeft ? max1 : max2;
        ctx->volumeMinDb = (float)ctx->volMinRaw / 256.0f;
        ctx->volumeMaxDb = (float)ctx->volMaxRaw / 256.0f;
        const int16_t res = (useLeft ? res1 : res2);
        sanitizeHardwareVolumeRange(ctx, ctx->volMinRaw, ctx->volMaxRaw, res > 0 ? res : 128);
        ctx->featureUnitVolumePath = FeatureUnitVolumePath::SingleChannel;
        ctx->featureUnitVolumePathName = useLeft ? "single-channel-left" : "single-channel-right";
        ctx->leftVolumeWritable = useLeft;
        ctx->rightVolumeWritable = !useLeft;
    }

    if (ctx->volMinRaw >= ctx->volMaxRaw) {
        markFeatureUnitPolicy(ctx, FeatureUnitPolicyState::Unsafe, "invalid-volume-range", -7);
        return -7;
    }

    // Discovery is intentionally read-only. At this stage we only
    // select a ranged Feature Unit path and observe the current value.  The one
    // device-scoped initial SET_CUR (stored raw or conservative -32 dB) is owned
    // by PlayerController before nativeStart submits any ISO transfers.  A new
    // native handle for the same attached DAC therefore performs zero volume
    // writes during format switches, recovery, seek, pause or track changes.
    bool hasObservedRaw = false;
    int16_t observedRaw = 0;
    if (ctx->featureUnitVolumePath == FeatureUnitVolumePath::Master) {
        hasObservedRaw = masterCur;
        observedRaw = probeCur0;
        ctx->featureUnitReadbackVerified = masterCur;
        markFeatureUnitPolicy(
                ctx,
                FeatureUnitPolicyState::SafeMaster,
                masterCur ? "master-range-cur-read-only" : "master-range-read-only",
                masterCur ? 0 : 1);
    } else if (ctx->featureUnitVolumePath == FeatureUnitVolumePath::LinkedChannels) {
        if (leftCur && rightCur) {
            const int lrDiff = std::abs((int)probeCur1 - (int)probeCur2);
            const int allowed = std::max<int>(ctx->deviceVolResRaw, ctx->maxAllowedLrDeltaRaw);
            if (lrDiff > allowed) {
                LOGW("validateHardwareVolume read-only: linked current mismatch L=%d R=%d delta=%d allowed=%d; "
                     "the pre-ISO device initialization write will align both channels",
                     probeCur1, probeCur2, lrDiff, allowed);
            }
            observedRaw = (int16_t)(((int)probeCur1 + (int)probeCur2) / 2);
            hasObservedRaw = true;
        } else if (leftCur) {
            observedRaw = probeCur1;
            hasObservedRaw = true;
        } else if (rightCur) {
            observedRaw = probeCur2;
            hasObservedRaw = true;
        }
        ctx->featureUnitReadbackVerified = leftCur && rightCur;
        markFeatureUnitPolicy(
                ctx,
                FeatureUnitPolicyState::SafeLinkedChannels,
                (leftCur && rightCur) ? "linked-range-cur-read-only" : "linked-range-read-only",
                (leftCur && rightCur) ? 0 : 1);
    } else if (ctx->featureUnitVolumePath == FeatureUnitVolumePath::SingleChannel) {
        const uint8_t ch = ctx->featureUnitSingleVolumeChannel;
        if (ch == 1 && leftCur) {
            observedRaw = probeCur1;
            hasObservedRaw = true;
        } else if (ch == 2 && rightCur) {
            observedRaw = probeCur2;
            hasObservedRaw = true;
        }
        ctx->featureUnitReadbackVerified = hasObservedRaw;
        markFeatureUnitPolicy(
                ctx,
                FeatureUnitPolicyState::SafeSingleChannel,
                hasObservedRaw ? "single-channel-range-cur-read-only" : "single-channel-range-read-only",
                hasObservedRaw ? 0 : 1);
    } else {
        markFeatureUnitPolicy(ctx, FeatureUnitPolicyState::Unsafe, "no-volume-path", -12);
        return -12;
    }

    if (hasObservedRaw) {
        observedRaw = std::clamp<int16_t>(
                observedRaw, ctx->deviceVolMinRaw, ctx->deviceVolMaxRaw);
        ctx->lastHardwareVolumeRaw.store(observedRaw, std::memory_order_release);
        ctx->hasLastHardwareVolumeRaw.store(true, std::memory_order_release);
    } else {
        ctx->hasLastHardwareVolumeRaw.store(false, std::memory_order_release);
    }
    ctx->hardwareVolumeCapable = true;
    ctx->hardwareVolumeSafe = true;
    ctx->hardwareVolumeEnabled = true;
    ctx->featureUnitAvailable = true;
    ctx->hardwareFeatureUnitEnabled = true; // compat
    LOGI("Hardware volume controller discovered read-only: policy=%s path=%s descHint(m=%d L=%d R=%d) "
         "effective(m=%d L=%d R=%d singleCh=%u) min=%.2fdB max=%.2fdB appStep=%.2fdB "
         "currentKnown=%d current=%.2fdB",
         featureUnitPolicyStateName(ctx->featureUnitPolicyState),
         ctx->featureUnitVolumePathName,
         ctx->descriptorHasMasterVolume ? 1 : 0,
         ctx->descriptorHasLeftVolume ? 1 : 0,
         ctx->descriptorHasRightVolume ? 1 : 0,
         ctx->hasMasterVolume ? 1 : 0,
         ctx->hasLeftVolume ? 1 : 0,
         ctx->hasRightVolume ? 1 : 0,
         ctx->featureUnitSingleVolumeChannel,
         ctx->volMinRaw / 256.0, ctx->volMaxRaw / 256.0,
         ctx->volResRaw / 256.0,
         hasObservedRaw ? 1 : 0,
         hasObservedRaw ? observedRaw / 256.0 : 0.0);
    return 0;
}
// ==========================
// forceCleanupTransfers：已禁用 — callback 可能仍持有 buffer 指针
// 改为 no-op + breadcrumb，宁可泄漏旧 session 也不 UAF
// 旧逻辑在 transportLost 时直接 free transfer buffer + 清零 pending，
// 但 libusb callback / event loop 可能还没退出，此时 free = UAF → kernel 崩溃。
// ==========================
static void forceCleanupTransfers(UsbAudioContext* h) {
    if (!h) return;
    int pending = h->pendingTransfers.load(std::memory_order_acquire);
    int fbPending = h->pendingFeedbackTransfers.load(std::memory_order_acquire);
    LOGW("forceCleanupTransfers: DISABLED (no-op) — pending=%d fbPending=%d "
         "buffers NOT freed to avoid UAF; context will be quarantined", pending, fbPending);
}

// ==========================
// 停止传输（内部辅助）
// ==========================
// ==========================
// stopUsbAudioInternal：三阶段停止 ISO 传输，并等待 callback 完成
// 三阶段防御式停止策略
// 阶段1：短暂等待 80ms，让已提交的 transfer 自然完成
// 阶段2：逐个 cancel；每次间隔 100us，给 libusb 事件循环处理时间
// 阶段3：如果设备已拔出，放弃等待并直接强制清理
// ==========================
static void stopUsbAudioInternal(UsbAudioContext* h) {
    if (!h) return;
    h->acceptingWrites.store(false, std::memory_order_release);
    bool wasStreaming = h->streaming.exchange(false, std::memory_order_acq_rel);
    bool transportLost = h->transportLost.load(std::memory_order_acquire);
    LOGI("Stopping USB audio... streaming=%d acceptingWrites=%d closing=%d pending=%d fbPending=%d transportLost=%d",
         wasStreaming ? 1 : 0,
         h->acceptingWrites.load() ? 1 : 0,
         h->closing.load(std::memory_order_acquire) ? 1 : 0,
         h->pendingTransfers.load(std::memory_order_acquire),
         h->pendingFeedbackTransfers.load(std::memory_order_acquire),
         transportLost ? 1 : 0);

    // 阶段3（提前）：如果设备已物理拔出，cancel 后不 free buffer
    // callback 可能还在持有指针，free = UAF；context 交给 nativeClose quarantine
    if (transportLost) {
        LOGW("Fast stop due to USB transport lost, skipping graceful shutdown");
        // 尝试 cancel 所有未完成的 transfer（失败也可以忽略）
        for (int i = 0; i < NUM_TRANSFERS; i++) {
            if (h->transfers[i]) {
                libusb_cancel_transfer(h->transfers[i]);
            }
        }
        if (h->feedbackTransfer) {
            libusb_cancel_transfer(h->feedbackTransfer);
        }
        // 不再调用 forceCleanupTransfers — buffer 保留，由 nativeClose quarantine 处理
        LOGI("USB audio fast-stopped (transport lost): pending=%d fbPending=%d "
             "buffers retained for quarantine",
             h->pendingTransfers.load(), h->pendingFeedbackTransfers.load());
        raw_usb_crash_guard_begin("stopUsbAudioInternal_lost");
        raw_usb_crash_guard_end("stopUsbAudioInternal_lost");
        return;
    }

    // 阶段1：短暂等待 80ms，让已提交的 transfer 在 callback 中自然结束
    {
        std::unique_lock<std::mutex> lock(h->stopMutex);
        bool allDone = h->stopCV.wait_for(lock, std::chrono::milliseconds(80), [&]() {
            return h->pendingTransfers.load(std::memory_order_acquire) <= 0 &&
                   h->pendingFeedbackTransfers.load(std::memory_order_acquire) <= 0;
        });
        if (allDone) {
            LOGI("USB audio stopped gracefully in phase 1");
            return;
        }
        LOGI("Phase 1 timeout: pending transfers still alive (ISO=%d FB=%d), entering phase 2",
             h->pendingTransfers.load(), h->pendingFeedbackTransfers.load());
    }

    // 阶段2：逐个 cancel；每个 transfer 之间等待 100us，给 libusb 事件循环处理时间
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        if (h->transfers[i]) {
            int r = libusb_cancel_transfer(h->transfers[i]);
            if (r != LIBUSB_SUCCESS && r != LIBUSB_ERROR_NOT_FOUND) {
                LOGW("cancel iso transfer %d failed: %s", i, libusb_error_name(r));
            }
            usleep(100); // 100us 间隔
        }
    }

    // cancel feedback transfer
    if (h->feedbackTransfer) {
        int r = libusb_cancel_transfer(h->feedbackTransfer);
        if (r != LIBUSB_SUCCESS && r != LIBUSB_ERROR_NOT_FOUND) {
            LOGW("cancel feedback transfer failed: %s", libusb_error_name(r));
        }
    }

    // 再次等待 80ms，让 cancel 后的 callback 返回
    {
        std::unique_lock<std::mutex> lock(h->stopMutex);
        bool allDone = h->stopCV.wait_for(lock, std::chrono::milliseconds(80), [&]() {
            return h->pendingTransfers.load(std::memory_order_acquire) <= 0 &&
                   h->pendingFeedbackTransfers.load(std::memory_order_acquire) <= 0;
        });
        if (!allDone) {
            LOGE("stop timeout after phase 2: pending transfers still alive (ISO=%d FB=%d)",
                 h->pendingTransfers.load(), h->pendingFeedbackTransfers.load());
        }
    }

    LOGI("USB audio stopped: pending=%d fbPending=%d",
         h->pendingTransfers.load(), h->pendingFeedbackTransfers.load());
}

static void armTrackStopFadeInternal(UsbAudioContext* ctx, int fadeMs, const char* reason) {
    if (!ctx) return;
    if (fadeMs < 3) fadeMs = 3;
    if (fadeMs > 80) fadeMs = 80;
    int sr = ctx->sampleRate > 0 ? ctx->sampleRate : 44100;
    int samples = sr * fadeMs / 1000;
    if (samples < 64) samples = 64;

    ctx->stopFadeTotalSamples.store(samples, std::memory_order_release);
    ctx->stopFadeSamplesRemaining.store(samples, std::memory_order_release);
    ctx->stopFadeActive.store(true, std::memory_order_release);

    LOGI("armTrackStopFadeInternal: fadeMs=%d samples=%d reason=%s",
         fadeMs, samples, reason ? reason : "unknown");
}

static bool waitForUsbSafeBoundaryCompletion(UsbAudioContext* ctx,
                                             int64_t beforeCompletedBytes,
                                             int timeoutMs,
                                             const char* reason,
                                             int minSettleMs = 0) {
    if (!ctx || timeoutMs <= 0) return false;
    const int64_t start = nowSteadyMs();
    bool sawCompletion = false;
    while (ctx->streaming.load(std::memory_order_acquire) &&
           !ctx->transportLost.load(std::memory_order_acquire) &&
           nowSteadyMs() - start < timeoutMs) {
        const int64_t now = nowSteadyMs();
        int64_t completed = ctx->statsTotalCompletedUsbBytes.load(std::memory_order_acquire);
        if (completed > beforeCompletedBytes) {
            sawCompletion = true;
            // A completion observed immediately after a boundary request may belong to the
            // previous packet batch. Keep the endpoint alive for the requested settle window
            // before stopping/cancelling ISO.
            if (minSettleMs <= 0 || now - start >= minSettleMs) {
                LOGI("Final safe USB boundary completed: reason=%s advancedBytes=%lld elapsed=%lldms minSettle=%dms",
                     reason ? reason : "unknown",
                     (long long)(completed - beforeCompletedBytes),
                     (long long)(now - start), minSettleMs);
                return true;
            }
        }
        usleep(1000);
    }

    LOGW("Final safe USB boundary wait timeout: reason=%s sawCompletion=%d completedBefore=%lld completedNow=%lld timeoutMs=%d minSettle=%d streaming=%d",
         reason ? reason : "unknown",
         sawCompletion ? 1 : 0,
         (long long)beforeCompletedBytes,
         (long long)ctx->statsTotalCompletedUsbBytes.load(std::memory_order_acquire),
         timeoutMs, minSettleMs,
         ctx->streaming.load(std::memory_order_acquire) ? 1 : 0);
    return false;
}

static void requestOutputFadeOutAndWait(UsbAudioContext* ctx, int fadeMs, const char* reason) {
    if (!ctx || !ctx->streaming.load(std::memory_order_acquire)) return;
    if (ctx->transportLost.load(std::memory_order_acquire)) return;

    // Stop/seek boundaries never touch Feature Unit volume. First make the next USB
    // completion cross the active PCM/transition-silence boundary before teardown.
    const int64_t beforeCompleted = ctx->statsTotalCompletedUsbBytes.load(std::memory_order_acquire);

    if (usesSessionPcmTransitionEnvelope(ctx)) {
        const float current = ctx->sessionVolumeCurrent.load(std::memory_order_acquire);
        armSessionEnvelopeInternal(ctx, 0.0f, current <= 0.0005f ? 0 : fadeMs);
        const int64_t start = nowSteadyMs();
        const int timeoutMs = std::max(18, std::min(15050, fadeMs + 50));
        bool fadeDone = false;
        bool boundaryDone = false;
        while (ctx->streaming.load(std::memory_order_acquire) &&
               !ctx->transportLost.load(std::memory_order_acquire) &&
               nowSteadyMs() - start < timeoutMs) {
            fadeDone = ctx->sessionVolumeFadeRemainingFrames.load(std::memory_order_acquire) <= 0;
            boundaryDone = ctx->statsTotalCompletedUsbBytes.load(std::memory_order_acquire) > beforeCompleted;
            if (fadeDone && boundaryDone) break;
            usleep(1000);
        }
        fadeDone = ctx->sessionVolumeFadeRemainingFrames.load(std::memory_order_acquire) <= 0;
        boundaryDone = ctx->statsTotalCompletedUsbBytes.load(std::memory_order_acquire) > beforeCompleted;
        LOGI("requestOutputFadeOutAndWait: owner=SessionPcm reason=%s fadeDone=%d boundaryDone=%d elapsed=%lldms",
             reason ? reason : "unknown",
             fadeDone ? 1 : 0,
             boundaryDone ? 1 : 0,
             (long long)(nowSteadyMs() - start));
        return;
    }

    // Do not overwrite ctx->softwareVolume here.  The real software gain belongs
    // to the active volume route and must not be silently changed by pause/stop.
    // Hardware-volume and strict bit-perfect paths keep PCM at unity. Their transient
    // boundary is valid USB silence/packet completion, never a Feature Unit write.
    if (getTransitionGainOwner(ctx) == UsbTransitionGainOwner::UnityPcm ||
        isHardwareVolumePcmUnityPath(ctx) || isStrictBitPerfectPcmPath(ctx)) {
        // Hardware Feature Unit stays at the user value. Only PCM/session or
        // transition-silence boundaries may change during pause/stop/seek.
        ctx->stopFadeSamplesRemaining.store(0, std::memory_order_release);
        ctx->stopFadeTotalSamples.store(0, std::memory_order_release);
        ctx->stopFadeActive.store(false, std::memory_order_release);

        const int minSettleMs = 0;
        const int timeoutMs = std::max(18, std::min(90, fadeMs + 45));
        const bool crossed = waitForUsbSafeBoundaryCompletion(
                ctx, beforeCompleted, timeoutMs,
                reason ? reason : "hardware_or_bitperfect_boundary", minSettleMs);
        LOGI("requestOutputFadeOutAndWait: PCM fade bypassed for hardware/bit-perfect route reason=%s boundary=%d minSettle=%dms",
             reason ? reason : "unknown", crossed ? 1 : 0, minSettleMs);
        return;
    }

    armTrackStopFadeInternal(ctx, fadeMs, reason);

    const int64_t start = nowSteadyMs();
    const int timeoutMs = std::max(12, fadeMs + 45);
    bool fadeDone = false;
    bool boundaryDone = false;
    while (ctx->streaming.load(std::memory_order_acquire) &&
           nowSteadyMs() - start < timeoutMs) {
        fadeDone = !ctx->stopFadeActive.load(std::memory_order_acquire);
        boundaryDone = ctx->statsTotalCompletedUsbBytes.load(std::memory_order_acquire) > beforeCompleted;
        if (fadeDone && boundaryDone) break;
        usleep(1000);
    }

    fadeDone = !ctx->stopFadeActive.load(std::memory_order_acquire);
    boundaryDone = ctx->statsTotalCompletedUsbBytes.load(std::memory_order_acquire) > beforeCompleted;
    if (!fadeDone || !boundaryDone) {
        LOGW("requestOutputFadeOutAndWait timeout: reason=%s fadeDone=%d boundaryDone=%d remaining=%d/%d completedBefore=%lld completedNow=%lld",
             reason ? reason : "unknown",
             fadeDone ? 1 : 0,
             boundaryDone ? 1 : 0,
             ctx->stopFadeSamplesRemaining.load(std::memory_order_acquire),
             ctx->stopFadeTotalSamples.load(std::memory_order_acquire),
             (long long)beforeCompleted,
             (long long)ctx->statsTotalCompletedUsbBytes.load(std::memory_order_acquire));
    } else {
        LOGI("requestOutputFadeOutAndWait done: reason=%s elapsed=%lldms finalSafeBoundary=1",
             reason ? reason : "unknown", (long long)(nowSteadyMs() - start));
    }
}


static bool stopUsbEventOwnerLocked(UsbAudioContext* ctx, const char* reason) {
    if (!ctx) return true;
    if (!ctx->eventOwner.stop(ctx->eventThreadRunning, 5000)) {
        LOGE("USB event carrier did not exit: handle=%p reason=%s; retaining carrier/context",
             ctx, reason ? reason : "unknown");
        ctx->sessionBroken.store(true, std::memory_order_release);
        ctx->quarantined.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "event_carrier_exit_timeout");
        return false;
    }
    return true;
}

// Reusable lifecycle transitions (standby/reconfigure/next-track) are legal
// only after every kernel-owned transfer has completed and every event owner is
// gone. A poisoned session can still be closed, but it must never release and
// reclaim interfaces on the same native handle.
static bool requireReusableSessionDrainedLocked(
        UsbAudioContext* ctx,
        const char* reason) {
    if (!ctx) return false;
    const int pending = ctx->pendingTransfers.load(std::memory_order_acquire);
    const int feedbackPending =
            ctx->pendingFeedbackTransfers.load(std::memory_order_acquire);
    const bool carrierAlive = ctx->eventOwner.carrier() != nullptr;
    const bool threadAlive = ctx->eventOwner.threadJoinable();
    const bool poisoned =
            ctx->transportLost.load(std::memory_order_acquire) ||
            ctx->quarantined.load(std::memory_order_acquire) ||
            ctx->sessionBroken.load(std::memory_order_acquire);
    if (pending <= 0 && feedbackPending <= 0 &&
        !carrierAlive && !threadAlive && !poisoned) {
        return true;
    }

    LOGE("USB reusable transition rejected: reason=%s ISO=%d FB=%d carrier=%d threadJoinable=%d lost=%d quarantined=%d broken=%d",
         reason ? reason : "unknown",
         pending,
         feedbackPending,
         carrierAlive ? 1 : 0,
         threadAlive ? 1 : 0,
         ctx->transportLost.load() ? 1 : 0,
         ctx->quarantined.load() ? 1 : 0,
         ctx->sessionBroken.load() ? 1 : 0);
    ctx->sessionBroken.store(true, std::memory_order_release);
    ctx->quarantined.store(true, std::memory_order_release);
    ctx->acceptingWrites.store(false, std::memory_order_release);
    ctx->stopRequested.store(true, std::memory_order_release);
    setUsbStreamState(ctx, UsbStreamState::BROKEN, reason ? reason : "reusable_transition_not_drained");
    return false;
}


// Standby/reconfigure keep the same libusb device handle alive. Therefore an
// alt-setting or interface-release failure cannot be ignored: ownership is now
// uncertain and reclaiming on the same native session could overlap the old
// kernel/interface state. Poison the handle and require a fresh process/session.
static bool releaseAudioStreamingInterfaceForReuseLocked(
        UsbAudioContext* ctx,
        const char* reason) {
    if (!ctx) return false;
    if (!ctx->devHandle || !ctx->asInterfaceClaimed ||
        ctx->claimedAsInterface < 0) {
        return true;
    }

    const int iface = ctx->claimedAsInterface;
    const int altRc =
            libusb_set_interface_alt_setting(ctx->devHandle, iface, 0);
    LOGI("%s set alt0 iface=%d result=%d",
         reason ? reason : "reuse_release", iface, altRc);
    if (altRc != LIBUSB_SUCCESS) {
        if (altRc == LIBUSB_ERROR_NO_DEVICE) {
            markUsbTransportLost(ctx, "reuse_set_alt0_no_device", iface, altRc);
        }
        ctx->sessionBroken.store(true, std::memory_order_release);
        ctx->quarantined.store(true, std::memory_order_release);
        ctx->acceptingWrites.store(false, std::memory_order_release);
        ctx->stopRequested.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN,
                          "reuse_set_alt0_failed");
        LOGE("%s rejected: failed to set alt0 iface=%d rc=%d; interface ownership retained/unknown",
             reason ? reason : "reuse_release", iface, altRc);
        return false;
    }

    const int releaseRc = libusb_release_interface(ctx->devHandle, iface);
    LOGI("%s release AS iface=%d result=%d",
         reason ? reason : "reuse_release", iface, releaseRc);
    if (releaseRc != LIBUSB_SUCCESS) {
        if (releaseRc == LIBUSB_ERROR_NO_DEVICE) {
            markUsbTransportLost(ctx, "reuse_release_no_device", iface,
                                 releaseRc);
        }
        ctx->sessionBroken.store(true, std::memory_order_release);
        ctx->quarantined.store(true, std::memory_order_release);
        ctx->acceptingWrites.store(false, std::memory_order_release);
        ctx->stopRequested.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN,
                          "reuse_release_interface_failed");
        LOGE("%s rejected: failed to release iface=%d rc=%d; session cannot be reused",
             reason ? reason : "reuse_release", iface, releaseRc);
        return false;
    }

    ctx->asInterfaceClaimed = false;
    ctx->claimedAsInterface = -1;
    return true;
}

static void stopStreamingLocked(UsbAudioContext *ctx) {
    if (!ctx) return;
    if (ctx->backgroundGuardian) {
        ctx->backgroundGuardian->stop("stop_streaming_locked");
    }

    const bool lost = ctx->transportLost.load(std::memory_order_acquire);
    const bool wasStreaming = ctx->streaming.exchange(false, std::memory_order_acq_rel);
    ctx->stopping.store(true, std::memory_order_release);
    ctx->acceptingWrites.store(false, std::memory_order_release);

    // Close the callback-owned resubmit gate before cancellation. From this
    // point completion callbacks may only decrement ownership counters; they
    // cannot re-arm a transfer while stop/close is collecting URBs.
    ctx->submitOwner.stop();

    LOGI("Stopping USB audio... streaming=%d acceptingWrites=%d closing=%d pending=%d fbPending=%d transportLost=%d",
         wasStreaming ? 1 : 0,
         ctx->acceptingWrites.load() ? 1 : 0,
         ctx->closing.load(std::memory_order_acquire) ? 1 : 0,
         ctx->pendingTransfers.load(),
         ctx->pendingFeedbackTransfers.load(),
         lost ? 1 : 0);

    // Even when streaming was already cleared by transport-loss handling, the
    // event owner and every submitted URB still have to be stopped and reaped.
    // Never use streaming=false as proof that callbacks no longer own ctx.
    int pending = ctx->pendingTransfers.load(std::memory_order_acquire);
    int pendingFb = ctx->pendingFeedbackTransfers.load(std::memory_order_acquire);
    if (pending > 0 || pendingFb > 0) {
        for (int i = 0; i < NUM_TRANSFERS; i++) {
            if (!ctx->transfers[i]) continue;
            const int ret = libusb_cancel_transfer(ctx->transfers[i]);
            if (ret < 0 && ret != LIBUSB_ERROR_NOT_FOUND &&
                ret != LIBUSB_ERROR_NO_DEVICE) {
                LOGW("cancel ISO %d failed: %s", i, libusb_error_name(ret));
            }
        }
        if (ctx->feedbackTransfer) {
            const int ret = libusb_cancel_transfer(ctx->feedbackTransfer);
            if (ret < 0 && ret != LIBUSB_ERROR_NOT_FOUND &&
                ret != LIBUSB_ERROR_NO_DEVICE) {
                LOGW("cancel feedback failed: %s", libusb_error_name(ret));
            }
        }

        std::unique_lock<std::mutex> lk(ctx->stopMutex);
        const bool allDone = ctx->stopCV.wait_for(
                lk, std::chrono::milliseconds(1500),
                [&]() {
                    return ctx->pendingTransfers.load(std::memory_order_acquire) <= 0 &&
                           ctx->pendingFeedbackTransfers.load(std::memory_order_acquire) <= 0;
                });
        if (!allDone) {
            LOGE("Timeout waiting transfers cancelled, pendingISO=%d pendingFB=%d",
                 ctx->pendingTransfers.load(), ctx->pendingFeedbackTransfers.load());
        }
    }

    if (!stopUsbEventOwnerLocked(ctx, "stop_streaming_locked")) {
        return;
    }

    const int left = ctx->pendingTransfers.load(std::memory_order_acquire);
    const int leftFb = ctx->pendingFeedbackTransfers.load(std::memory_order_acquire);
    if (left > 0 || leftFb > 0) {
        LOGE("Still pending transfers after event owner exit: ISO=%d FB=%d. "
             "Poisoning session; transfers and buffers remain owned by quarantine.",
             left, leftFb);
        ctx->sessionBroken.store(true, std::memory_order_release);
        ctx->stopRequested.store(true, std::memory_order_release);
        ctx->acceptingWrites.store(false, std::memory_order_release);
        ctx->quarantined.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "stop_pending_transfers");
        return;
    }

    for (int i = 0; i < NUM_TRANSFERS; i++) {
        if (ctx->transfers[i]) {
            libusb_free_transfer(ctx->transfers[i]);
            ctx->transfers[i] = nullptr;
        }
    }
    if (ctx->feedbackTransfer) {
        libusb_free_transfer(ctx->feedbackTransfer);
        ctx->feedbackTransfer = nullptr;
    }

    if (!ctx->closing.load(std::memory_order_acquire) &&
        !ctx->sessionBroken.load(std::memory_order_acquire)) {
        ctx->stopping.store(false, std::memory_order_release);
    }
    LOGI("USB audio stopped: pending=%d fbPending=%d lost=%d",
         ctx->pendingTransfers.load(), ctx->pendingFeedbackTransfers.load(),
         lost ? 1 : 0);
}

static void stopDsdWorker(UsbAudioContext* ctx, const char* reason);

// ==========================
// cleanupUsbHandle: 释放 USB 资源（不delete
// 调用者需持有 handleMutex
// ==========================
static bool cleanupUsbHandle(UsbAudioContext *ctx) {
    if (!ctx) return true;
    LOGI("cleanupUsbHandle begin: handle=%p", ctx);
    ctx->closing.store(true, std::memory_order_release);
    ctx->stopping.store(true, std::memory_order_release);
    ctx->acceptingWrites.store(false, std::memory_order_release);
    stopDsdWorker(ctx, "cleanup_usb_handle");
    stopHidLocked(ctx);
    if (ctx->backgroundGuardian) {
        ctx->backgroundGuardian->stop("cleanup_usb_handle");
    }

    requestOutputFadeOutAndWait(ctx, 25, "cleanup_usb_handle");
    stopStreamingLocked(ctx);
    if (!stopUsbEventOwnerLocked(ctx, "cleanup_usb_handle")) {
        ctx->quarantined.store(true, std::memory_order_release);
        quarantineHandle(ctx, "cleanup_event_owner_timeout");
        return false;
    }

    const int pending = ctx->pendingTransfers.load(std::memory_order_acquire);
    const int feedbackPending =
            ctx->pendingFeedbackTransfers.load(std::memory_order_acquire);
    if (pending > 0 || feedbackPending > 0 ||
        ctx->eventOwner.carrier() != nullptr || ctx->eventOwner.threadJoinable()) {
        LOGE("cleanupUsbHandle refused destructive cleanup: handle=%p ISO=%d FB=%d carrier=%p threadJoinable=%d quarantined=%d",
             ctx, pending, feedbackPending, ctx->eventOwner.carrier(),
             ctx->eventOwner.threadJoinable() ? 1 : 0,
             ctx->quarantined.load() ? 1 : 0);
        ctx->quarantined.store(true, std::memory_order_release);
        quarantineHandle(ctx, "cleanup_not_drained");
        return false;
    }

#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000105)
    if (ctx->hotplugRegistered && ctx->libusbCtx) {
        libusb_hotplug_deregister_callback(ctx->libusbCtx, ctx->hotplugHandle);
        ctx->hotplugRegistered = false;
    }
#endif

    // libusb_transfer owns callback metadata and points at transferBuffers.
    // It must be freed before buffers, but only after pending counters and the
    // event owner prove that the kernel can no longer callback into it.
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        if (ctx->transfers[i]) {
            libusb_free_transfer(ctx->transfers[i]);
            ctx->transfers[i] = nullptr;
        }
    }
    if (ctx->feedbackTransfer) {
        libusb_free_transfer(ctx->feedbackTransfer);
        ctx->feedbackTransfer = nullptr;
    }

    // 释放 USB 资源
    if (ctx->devHandle) {
        if (ctx->claimDoneByNative && ctx->interfaceNumber >= 0) {
            int r = libusb_set_interface_alt_setting(ctx->devHandle, ctx->interfaceNumber, 0);
            if (r == LIBUSB_SUCCESS || r == LIBUSB_ERROR_NO_DEVICE) {
                LOGI("set alt0 result: iface=%d err=%s", ctx->interfaceNumber,
                     libusb_error_name(r));
            } else {
                LOGW("set alt0 failed during final cleanup: iface=%d err=%s",
                     ctx->interfaceNumber, libusb_error_name(r));
            }
            r = libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
            if (r == LIBUSB_SUCCESS || r == LIBUSB_ERROR_NO_DEVICE) {
                LOGI("release_interface result: iface=%d err=%s",
                     ctx->interfaceNumber, libusb_error_name(r));
            } else {
                LOGW("release_interface failed during final cleanup: iface=%d err=%s",
                     ctx->interfaceNumber, libusb_error_name(r));
            }
        }
        releaseAudioControlInterface(ctx);
        libusb_close(ctx->devHandle);
        ctx->devHandle = nullptr;
    }
    if (ctx->libusbCtx) {
        libusb_exit(ctx->libusbCtx);
        ctx->libusbCtx = nullptr;
    }

    if (ctx->dupFd >= 0) {
        if (fcntl(ctx->dupFd, F_GETFD) >= 0) {
            close(ctx->dupFd);
            LOGI("closed dupFd=%d", ctx->dupFd);
        }
        ctx->dupFd = -1;
    }

    for (int i = 0; i < NUM_TRANSFERS; i++) {
        delete[] ctx->transferBuffers[i];
        ctx->transferBuffers[i] = nullptr;
    }
    delete[] ctx->feedbackBuffer;
    ctx->feedbackBuffer = nullptr;
    closeSwrContext(ctx);

    LOGI("cleanupUsbHandle end");
    return true;
}

static bool tryCleanupQuarantinedHandle(UsbAudioContext* ctx) {
    if (!ctx) return false;
    // A callback-owned transfer is the one condition under which freeing this
    // context is unsafe. The reaper retries it on the next reopen attempt.
    if (ctx->pendingTransfers.load(std::memory_order_acquire) > 0 ||
        ctx->pendingFeedbackTransfers.load(std::memory_order_acquire) > 0) {
        return false;
    }

    std::lock_guard<std::mutex> handleLock(ctx->handleMutex);
    ctx->quarantined.store(false, std::memory_order_release);
    LOGW("USB quarantine reaper attempting drained context: ctx=%p", ctx);
    // cleanupUsbHandle restores quarantine when a late callback proves that
    // the context is not actually drained.
    return cleanupUsbHandle(ctx);
}

static void onDeferredQuarantinedHandle(UsbAudioContext* ctx) {
    if (!ctx) return;
    LOGW("USB quarantine reaper deferred: ctx=%p ISO=%d FB=%d",
         ctx, ctx->pendingTransfers.load(), ctx->pendingFeedbackTransfers.load());
}

static void onFinalizedQuarantinedHandle(UsbAudioContext* ctx) {
    LOGW("USB quarantine reaper finalized context: ctx=%p", ctx);
    delete ctx;
}

static size_t reapSafeQuarantinedHandles() {
    const rawsmusic::usb::RawUsbQuarantineReaperCallbacks callbacks{
        tryCleanupQuarantinedHandle,
        onDeferredQuarantinedHandle,
        onFinalizedQuarantinedHandle,
    };
    return rawsmusic::usb::reapSafeQuarantinedHandles(gHandleRegistry, callbacks);
}

// ==========================
// PCM 格式转换：source USB device 格式
// ==========================
static size_t convertPcmToUsbFormat(
        UsbAudioContext *ctx,
        const uint8_t *src,
        size_t srcBytes,
        uint8_t *dst,
        size_t dstCapacity
) {
    if (!ctx || !src || !dst) return 0;
    const int srcFrame = ctx->sourceBytesPerFrame;
    const int dstFrame = ctx->bytesPerFrame;
    if (srcFrame <= 0 || dstFrame <= 0) return 0;
    const size_t converted = convertPcmToUsbDeviceFormat(
            ctx->pcmAdapter,
            src,
            srcBytes,
            srcFrame,
            dstFrame,
            ctx->sourceChannels,
            dst,
            dstCapacity);
    if (converted == 0 && ctx->pcmAdapter != PCM_ADAPTER_NONE) {
        LOGW("convertPcmToUsbFormat: adapter=%d produced no bytes", ctx->pcmAdapter);
    }
    return converted;
}

// Write decoder-container PCM frames into the USB ring, converting to the
// selected device format at the last step.  This is required after swr_convert()
// because swr outputs S16LE/S32LE containers; it does not output packed S24.
static size_t writeSourcePcmFramesToUsbRing(
        UsbAudioContext* ctx,
        const uint8_t* src,
        size_t frames,
        size_t bufSize
) {
    if (!ctx || !src || frames == 0 || bufSize == 0) return 0;
    const int srcFrame = ctx->sourceBytesPerFrame;
    const int dstFrame = ctx->bytesPerFrame;
    if (srcFrame <= 0 || dstFrame <= 0) return 0;

    const size_t writePosition = ctx->pcmWritePos.load(std::memory_order_acquire);
    size_t nextWritePosition = writePosition;
    const size_t outWritten = writePcmFramesToRing(
            ctx->pcmAdapter,
            src,
            frames,
            ctx->pcmRingBuffer.data(),
            bufSize,
            writePosition,
            srcFrame,
            dstFrame,
            ctx->sourceChannels,
            &nextWritePosition);
    ctx->pcmWritePos.store(nextWritePosition, std::memory_order_release);
    return outWritten;
}

// ==========================
// DSD 转换辅助：将 PCM 设备格式数据转换DSD 并写ring buffer
// 输入: pcmData (设备格式 PCM, 已经过重采样/适配), pcmBytes (字节
// 输出: 写入 ctx->pcmRingBuffer DSD 数据
// 返回: 写入 ring buffer 的字节数
// ==========================
static size_t convertAndWriteDsdToRing(
        UsbAudioContext* ctx,
        const uint8_t* pcmData, size_t pcmBytes,
        size_t bufSize, size_t wPos, size_t freeSpace,
        int srcChannels = 0, int srcBitDepth = 0, int srcSubslotSize = 0) {
    if (!ctx) return 0;
    (void)wPos;
    return rawsmusic::usb::convertPcmToDsdAndWriteToRing(
            *ctx,
            ctx,
            ctx->pcmRingBuffer.data(),
            ctx->pcmWritePos,
            bufSize,
            ringAvailable(ctx),
            freeSpace,
            pcmData,
            pcmBytes,
            srcChannels,
            srcBitDepth,
            srcSubslotSize,
            ctx->sourceSampleRate,
            ctx->deviceSubslotSize);
}

static size_t writeRawDsdToRing(
        UsbAudioContext* ctx,
        const uint8_t* rawDsdInterleaved,
        size_t rawBytes,
        size_t freeSpace,
        size_t bufSize,
        size_t wPos) {
    if (!ctx) return 0;
    (void)wPos;
    const size_t written = rawsmusic::usb::writeRawDsdToRing(
            *ctx,
            ctx->pcmRingBuffer.data(),
            bufSize,
            ctx->pcmWritePos,
            rawDsdInterleaved,
            rawBytes,
            freeSpace,
            ctx->sourceChannels,
            ctx->deviceChannels,
            ctx->deviceSubslotSize,
            ctx->bytesPerFrame,
            ctx->dsdDopTransport,
            ctx->sourceSampleRate,
            ctx->sampleRate);
    return written;
}


// ==========================
// PCM->DSD output-demand producer
// ==========================
static RawUsbDsdWorkerConfig makeDsdWorkerConfig(const UsbAudioContext* ctx) {
    if (!ctx) return {};
    return RawUsbDsdWorkerConfig{
            ctx->sourceSampleRate,
            ctx->sourceBytesPerFrame,
            ctx->sourceChannels,
            ctx->sourceBitDepth,
            ctx->sourceBytesPerSample,
            ctx->sampleRate,
            ctx->bytesPerFrame,
            ctx->bytes_per_second,
            ctx->transferSize,
            ctx->dsdRateMultiplier,
            ctx->pcmRingBuffer.size(),
    };
}

static bool dsdWorkerIsActive(void* owner) {
    auto* ctx = reinterpret_cast<UsbAudioContext*>(owner);
    return ctx != nullptr &&
           ctx->pcmToDsdSession &&
           ctx->dsdConverterInitialized.load(std::memory_order_acquire) &&
           !ctx->closing.load(std::memory_order_acquire);
}

static size_t dsdWorkerRingAvailable(void* owner) {
    return ringAvailable(reinterpret_cast<UsbAudioContext*>(owner));
}

static PcmToDsdWritePlan dsdWorkerMakeWritePlan(
        void* owner, size_t availableInputFrames, size_t freeOutputBytes) {
    auto* ctx = reinterpret_cast<UsbAudioContext*>(owner);
    if (!ctx) return PcmToDsdWritePlan{};
    const int channels = ctx->sourceChannels > 0 ? ctx->sourceChannels : ctx->deviceChannels;
    const int fallbackRate = ctx->sourceSampleRate > 0 ? ctx->sourceSampleRate : ctx->sampleRate;
    return makePcmToDsdWritePlan(
            *ctx,
            availableInputFrames,
            freeOutputBytes,
            channels,
            fallbackRate,
            ctx->deviceSubslotSize,
            ctx->sourceBytesPerFrame,
            ctx->serviceIntervalsPerSecond,
            ctx->numIsoPackets);
}

static size_t dsdWorkerConvertAndWrite(
        void* owner,
        const uint8_t* pcm,
        size_t inputBytes,
        size_t ringCapacity,
        size_t freeRingBytes) {
    auto* ctx = reinterpret_cast<UsbAudioContext*>(owner);
    if (!ctx) return 0;
    return convertAndWriteDsdToRing(
            ctx,
            pcm,
            inputBytes,
            ringCapacity,
            ctx->pcmWritePos.load(std::memory_order_acquire),
            freeRingBytes,
            ctx->sourceChannels,
            ctx->sourceBitDepth,
            ctx->sourceBytesPerSample);
}

static void dsdWorkerTransactionFailure(void* owner, const char* reason) {
    auto* ctx = reinterpret_cast<UsbAudioContext*>(owner);
    if (!ctx) return;
    ctx->sessionBroken.store(true, std::memory_order_release);
    ctx->acceptingWrites.store(false, std::memory_order_release);
    setUsbStreamState(ctx, UsbStreamState::BROKEN, reason ? reason : "p2d_demand_contract_failed");
}

static RawUsbDsdWorkerCallbacks makeDsdWorkerCallbacks(UsbAudioContext* ctx) {
    return RawUsbDsdWorkerCallbacks{
            ctx,
            dsdWorkerIsActive,
            dsdWorkerRingAvailable,
            dsdWorkerMakeWritePlan,
            dsdWorkerConvertAndWrite,
            dsdWorkerTransactionFailure,
    };
}

static bool isPcmToDsdDemandActive(const UsbAudioContext* ctx) {
    if (!ctx) return false;
    return rawsmusic::usb::isPcmToDsdDemandActive(
            *ctx,
            makeDsdWorkerCallbacks(const_cast<UsbAudioContext*>(ctx)));
}

static void requestPcmToDsdDemand(UsbAudioContext* ctx, const char* reason) {
    if (!ctx) return;
    rawsmusic::usb::requestPcmToDsdDemand(
            *ctx, makeDsdWorkerConfig(ctx), makeDsdWorkerCallbacks(ctx), reason);
}

static size_t enqueuePcmForDsdWorker(UsbAudioContext* ctx, const uint8_t* src, size_t bytes) {
    if (!ctx) return 0;
    return rawsmusic::usb::enqueuePcmForDsdWorker(
            *ctx,
            makeDsdWorkerConfig(ctx),
            makeDsdWorkerCallbacks(ctx),
            src,
            bytes,
            ctx->streaming.load(std::memory_order_acquire));
}

static void clearDsdPcmQueue(UsbAudioContext* ctx) {
    if (ctx) rawsmusic::usb::clearDsdPcmQueue(*ctx);
}

static void startDsdWorkerIfNeeded(UsbAudioContext* ctx, const char* reason) {
    if (!ctx) return;
    rawsmusic::usb::startDsdWorkerIfNeeded(
            *ctx, makeDsdWorkerConfig(ctx), makeDsdWorkerCallbacks(ctx), reason);
}

static void stopDsdWorker(UsbAudioContext* ctx, const char* reason) {
    if (ctx) rawsmusic::usb::stopDsdWorker(*ctx, reason);
}

static void destroyPcmToDsdSessionState(UsbAudioContext* ctx, const char* reason) {
    if (!ctx || !ctx->pcmToDsdSession) return;
    stopDsdWorker(ctx, reason);
    resetRawUsbDsdConverter(*ctx, reason);
    LOGW("PCM_TO_DSD session state destroyed: ctx=%p reason=%s",
         ctx, reason ? reason : "unknown");
}

// ==========================
// JNI: nativeWrite - 基于水位线的流量控制
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeWrite(
        JNIEnv *env, jobject thiz, jbyteArray data, jint offset, jint length) {

    if (!data || length <= 0) return 0;
    jsize arrayLen = env->GetArrayLength(data);
    if (offset < 0 || length < 0 || offset > arrayLen || length > arrayLen - offset) {
        LOGE("nativeWrite invalid range: offset=%d length=%d arrayLen=%d",
             offset, length, arrayLen);
        return ERR_NOT_INITIALIZED;
    }

    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    UsbAudioContext *ctx = nullptr;
    {
        std::lock_guard<std::mutex> registryLock(gRegistryMtx);
        ctx = firstLiveHandleNoLock();
        if (!ctx) {
            LOGE("nativeWrite: no live handle -> ERR_NOT_INITIALIZED");
            return ERR_NOT_INITIALIZED;
        }
    }
    if (!ctx) {
        LOGE("nativeWrite: ctx null -> ERR_NOT_INITIALIZED");
        return ERR_NOT_INITIALIZED;
    }
    if (ctx->closing.load(std::memory_order_acquire)) {
        LOGW("nativeWrite ignored: closing handle=%p", ctx);
        return ERR_NOT_RUNNING;
    }
    if (!ctx->acceptingWrites.load(std::memory_order_acquire)) {
        LOGW("nativeWrite ignored: not accepting writes, handle=%p", ctx);
        return -EPIPE;
    }
    if (!ctx->initialized.load(std::memory_order_acquire)) {
        int fatal = ctx->fatalError.load(std::memory_order_acquire);
        if (fatal == ERR_TRANSPORT_LOST) {
            LOGE("nativeWrite: transportLost (fatal) -> ERR_TRANSPORT_LOST");
            return ERR_TRANSPORT_LOST;
        }
        if (fatal == ERR_USB_IO) {
            LOGE("nativeWrite: usbIO (fatal) -> ERR_USB_IO");
            return ERR_USB_IO;
        }
        LOGE("nativeWrite: !initialized and no fatal -> ERR_NOT_INITIALIZED");
        return ERR_NOT_INITIALIZED;
    }
    if (ctx->stopping.load(std::memory_order_acquire)) {
        LOGE("nativeWrite: stopping -> ERR_NOT_RUNNING");
        return ERR_NOT_RUNNING;
    }
    if (ctx->transportLost.load(std::memory_order_acquire)) {
        LOGE("nativeWrite: transportLost -> ERR_TRANSPORT_LOST");
        return ERR_TRANSPORT_LOST;
    }
    if (ctx->pcmRingBuffer.size() == 0) {
        LOGE("nativeWrite: ring buffer not allocated -> ERR_NOT_INITIALIZED");
        return ERR_NOT_INITIALIZED;
    }

    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -2;

    // 数据校验：首次写入打印前 32 字节（检查 WAV 头是否被误送），之后每 10000 次打印前 4 字节
    static int logCounter = 0;
    if (logCounter == 0 && length >= 32 && offset + 31 < arrayLen) {
        // 首次写入：打印前 32 字节，用于检测 RIFF/WAV 头是否被误送进 USB
        LOGI("nativeWrite FIRST32: offset=%d len=%d data=%02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X %02X",
             offset, length,
             (unsigned char)bytes[offset+0], (unsigned char)bytes[offset+1],
             (unsigned char)bytes[offset+2], (unsigned char)bytes[offset+3],
             (unsigned char)bytes[offset+4], (unsigned char)bytes[offset+5],
             (unsigned char)bytes[offset+6], (unsigned char)bytes[offset+7],
             (unsigned char)bytes[offset+8], (unsigned char)bytes[offset+9],
             (unsigned char)bytes[offset+10], (unsigned char)bytes[offset+11],
             (unsigned char)bytes[offset+12], (unsigned char)bytes[offset+13],
             (unsigned char)bytes[offset+14], (unsigned char)bytes[offset+15],
             (unsigned char)bytes[offset+16], (unsigned char)bytes[offset+17],
             (unsigned char)bytes[offset+18], (unsigned char)bytes[offset+19],
             (unsigned char)bytes[offset+20], (unsigned char)bytes[offset+21],
             (unsigned char)bytes[offset+22], (unsigned char)bytes[offset+23],
             (unsigned char)bytes[offset+24], (unsigned char)bytes[offset+25],
             (unsigned char)bytes[offset+26], (unsigned char)bytes[offset+27],
             (unsigned char)bytes[offset+28], (unsigned char)bytes[offset+29],
             (unsigned char)bytes[offset+30], (unsigned char)bytes[offset+31]);
        // 检查是否是 RIFF
        if (bytes[offset] == 0x52 && bytes[offset+1] == 0x49 &&
            bytes[offset+2] == 0x46 && bytes[offset+3] == 0x46) {
            LOGE("!!! WAV HEADER DETECTED IN PCM DATA 锟?RIFF header sent to USB DAC! This means WAV header is NOT being skipped! !!!");
        }
        logCounter = 1;
    } else if (logCounter > 0 && logCounter++ % 10000 == 0 && length >= 4) {
        LOGI("nativeWrite sample: offset=%d len=%d data=%02X %02X %02X %02X",
             offset, length,
             (unsigned char)bytes[offset],
             (unsigned char)bytes[offset + 1],
             (unsigned char)bytes[offset + 2],
             (unsigned char)bytes[offset + 3]);
    }

    size_t sourceBytesConsumed = 0;
    size_t usbBytesWritten = 0;
    {
        size_t bufSize = ctx->pcmRingBuffer.size();
        size_t used = ringAvailable(ctx);
        float usageRatio = (float)used / (float)bufSize;
        // soft limit USB 输出字节率计
        const bool rawDsdDirect = isRawDsdInputActive(ctx);
        bool dsdActiveForLimit = ctx->dsdSession;
        const bool backgroundPlayback =
                g_usbBackgroundPlaybackActive.load(std::memory_order_acquire) &&
                g_usbExclusiveActive.load(std::memory_order_acquire);
        int softLimitMs = backgroundPlayback
                          ? USB_BACKGROUND_WRITE_SOFT_LIMIT_MS
                          : (dsdActiveForLimit ? DSD_WRITE_SOFT_LIMIT_MS : USB_WRITE_SOFT_LIMIT_MS);
        size_t softLimitBytes =
                (size_t)ctx->sampleRate * ctx->bytesPerFrame * softLimitMs / 1000;
        if (softLimitBytes >= bufSize) {
            softLimitBytes = bufSize - 1;
        }
        if (used >= softLimitBytes) {
            ctx->writeThrottleUs.store(4000, std::memory_order_relaxed);
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return 0;
        }
        int delayUs = 0;
        if (usageRatio < 0.60f) {
            delayUs = 0;
        } else if (usageRatio < 0.75f) {
            delayUs = 2000;
        } else if (usageRatio < 0.90f) {
            delayUs = 4000;
        } else {
            delayUs = 6000;
        }
        ctx->writeThrottleUs.store(delayUs, std::memory_order_relaxed);
        size_t freeSpace = (bufSize - 1) - used;
        // 输入source frame 对齐
        size_t srcAvailable = (size_t)length;
        srcAvailable = (srcAvailable / ctx->sourceBytesPerFrame) *
                       ctx->sourceBytesPerFrame;
        // freeSpace USB frame 对齐
        freeSpace = (freeSpace / ctx->bytesPerFrame) *
                    ctx->bytesPerFrame;
        size_t maxOutputFrames = freeSpace / ctx->bytesPerFrame;
        size_t inputFrames = srcAvailable / ctx->sourceBytesPerFrame;

        if (maxOutputFrames == 0 || inputFrames == 0) {
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return 0;
        }

        const uint8_t *src =
                reinterpret_cast<const uint8_t *>(bytes + offset);

        // 检查 DSD+DoP 是否启用
        bool dsdActive = ctx->dsdSession;
        bool dopActive = dsdActive && ctx->dsdDopTransport;

        // DoP 路径诊断：每 5000 次调用打印一次状
        if (dopActive) {
            static int dopStateLogCount = 0;
            if (++dopStateLogCount % 5000 == 1) {
                LOGI("DoP nativeWrite: needsResample=%d swrCtx=%p srcAvail=%zu "
                     "freeSpace=%zu/%zu srcBPF=%d rateMult=%u",
                     ctx->needsResample ? 1 : 0, ctx->swrCtx,
                     srcAvailable, freeSpace, bufSize, ctx->sourceBytesPerFrame,
                     static_cast<uint32_t>(ctx->dsdRateMultiplier));
            }
        }

        // DSD/DoP 转换路径必须跳过 PCM 重采样器：转换器根据原始源采样率
        // 直接生成目标 DSD bitstream。Native DSD 的 USB carrier rate
        // (DSD64=88.2kHz, DSD128=176.4kHz...) 不是 PCM 重采样目标。
        // 如果先把 44.1kHz PCM 重采样到 carrier rate，再按 44.1kHz 输入率
        // 做 PCM→DSD，会把输出数据量放大 2x/4x/8x/16x，造成 ring buffer
        // 快速塞满、nativeWrite 长时间返回 0，听感就是“播很短、卡很久”。
        if (rawDsdDirect) {
            size_t w = ctx->pcmWritePos.load(std::memory_order_acquire);
            sourceBytesConsumed = writeRawDsdToRing(
                    ctx,
                    src,
                    srcAvailable,
                    freeSpace,
                    bufSize,
                    w);
            usbBytesWritten = sourceBytesConsumed > 0 ? sourceBytesConsumed : 0;
        } else if (dsdActive) {
            // Source pushes PCM into a passive queue; final USB consumption raises
            // demand. The converter worker has no periodic clock and restores only
            // the requested final-ring target.
            sourceBytesConsumed = enqueuePcmForDsdWorker(ctx, src, srcAvailable);
            usbBytesWritten = sourceBytesConsumed;
            if (sourceBytesConsumed == 0) {
                ctx->writeThrottleUs.store(1000, std::memory_order_relaxed);
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }

        } else if (ctx->needsResample && ctx->swrCtx) {
            // ===== Resample branch =====
            // swr output remains in decoder source container (S16LE/S32LE).
            // Convert to the USB device container after resampling.
            const int inFrameSize = ctx->sourceBytesPerFrame;
            const int swrFrameSize = ctx->deviceChannels * ctx->sourceBytesPerSample;
            const int dstFrameSize = ctx->bytesPerFrame;
            if (inFrameSize <= 0 || swrFrameSize <= 0 || dstFrameSize <= 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }

            size_t devAlignedFree = (freeSpace / dstFrameSize) * dstFrameSize;
            size_t maxDevFrames = devAlignedFree / dstFrameSize;
            size_t maxSwrFrames = ctx->swrOutBufferSize / swrFrameSize;
            maxDevFrames = std::min(maxDevFrames, maxSwrFrames);
            if (maxDevFrames == 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }

            int64_t delayInSrcRate = swr_get_delay(ctx->swrCtx, ctx->sourceSampleRate);
            int64_t estimatedOut = av_rescale_rnd(
                    delayInSrcRate + (int64_t)inputFrames,
                    ctx->sampleRate,
                    ctx->sourceSampleRate,
                    AV_ROUND_UP
            );

            if (estimatedOut > (int64_t)maxDevFrames) {
                int64_t allowedInput = av_rescale_rnd(
                        (int64_t)maxDevFrames,
                        ctx->sourceSampleRate,
                        ctx->sampleRate,
                        AV_ROUND_DOWN
                );
                if (allowedInput <= 0) {
                    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                    return 0;
                }
                inputFrames = (size_t)std::min<int64_t>(allowedInput, (int64_t)inputFrames);
            }
            sourceBytesConsumed = inputFrames * (size_t)ctx->sourceBytesPerFrame;

            const uint8_t *inBuf[1] = { src };
            uint8_t *outBuf[1] = { ctx->swrOutBuffer.data() };
            int outSamples = swr_convert(
                    ctx->swrCtx, outBuf, (int)maxDevFrames,
                    inBuf, (int)inputFrames);
            if (outSamples <= 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return (jint)sourceBytesConsumed;
            }

            usbBytesWritten = writeSourcePcmFramesToUsbRing(
                    ctx, ctx->swrOutBuffer.data(), (size_t)outSamples, bufSize);
        } else {
            // ===== 非重采样路径 =====
            if (dsdActive) {
                sourceBytesConsumed = enqueuePcmForDsdWorker(ctx, src, srcAvailable);
                usbBytesWritten = sourceBytesConsumed;
                if (sourceBytesConsumed == 0) {
                    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                    return 0;
                }
            } else {
                // ===== 普PCM 路径：原convertPcmToUsbFormat =====
                size_t framesToWrite = inputFrames;
                if (framesToWrite > maxOutputFrames) {
                    framesToWrite = maxOutputFrames;
                }
                sourceBytesConsumed = framesToWrite * ctx->sourceBytesPerFrame;
                size_t outputBytesNeeded = framesToWrite * ctx->bytesPerFrame;

                size_t w = ctx->pcmWritePos.load(std::memory_order_acquire);
                size_t firstPartCapacity = bufSize - w;
                firstPartCapacity = (firstPartCapacity / ctx->bytesPerFrame) *
                                    ctx->bytesPerFrame;
                size_t firstPartOut = outputBytesNeeded;
                if (firstPartOut > firstPartCapacity) {
                    firstPartOut = firstPartCapacity;
                }
                size_t firstFrames = firstPartOut / ctx->bytesPerFrame;
                size_t firstSrcBytes = firstFrames * ctx->sourceBytesPerFrame;
                // 第一段转换写
                size_t wrote1 = convertPcmToUsbFormat(
                        ctx,
                        src,
                        firstSrcBytes,
                        ctx->pcmRingBuffer.data() + w,
                        firstPartOut
                );
                usbBytesWritten += wrote1;
                // 软件音量已移到 fillIsoTransfer，在提交 USB transfer 前应用，不再在 nativeWrite 中处理
                // 这样音量变化最多一个 transfer 周期后生效，不受 ring buffer 延迟影响
                size_t remainingOut = outputBytesNeeded - wrote1;
                if (remainingOut > 0) {
                    size_t consumedFrames1 = wrote1 / ctx->bytesPerFrame;
                    const uint8_t *src2 =
                            src + consumedFrames1 * ctx->sourceBytesPerFrame;
                    size_t remainingSrcBytes =
                            (framesToWrite - consumedFrames1) *
                            ctx->sourceBytesPerFrame;
                    size_t wrote2 = convertPcmToUsbFormat(
                            ctx,
                            src2,
                            remainingSrcBytes,
                            ctx->pcmRingBuffer.data(),
                            remainingOut
                    );
                    usbBytesWritten += wrote2;
                }
                ctx->pcmWritePos.store(
                        (w + usbBytesWritten) % bufSize,
                        std::memory_order_release
                );
            }
        }
    }
    if (sourceBytesConsumed > 0) {
        // appInBytes 统计 Java 输入字节
        ctx->statsAppBytes.fetch_add(
                sourceBytesConsumed,
                std::memory_order_relaxed
        );
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    // 返回 Java 实际消费的源 PCM 字节数，不是 USB 输出字节
    return (jint)sourceBytesConsumed;
}

// ==========================
// JNI: nativeIsActive
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeIsActive(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return JNI_FALSE;
    return ctx->streaming.load() ? JNI_TRUE : JNI_FALSE;
}

// ==========================
// JNI: nativeIsInitialized
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeIsInitialized(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return JNI_FALSE;
    return (ctx->initialized.load(std::memory_order_acquire) &&
            !ctx->transportLost.load(std::memory_order_acquire))
           ? JNI_TRUE : JNI_FALSE;
}

// ==========================
// JNI: nativeResetBuffer (handle-based)
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeResetBuffer(
        JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx)) return;
    // SPSC lock-free: no mutex needed caller is sole writer of positions
    ctx->pcmWritePos.store(0, std::memory_order_release);
    ctx->pcmReadPos.store(0, std::memory_order_release);
    ctx->starved = false;
    ctx->consecutiveEmptyTransfers = 0;
    if (!ctx->streaming.load(std::memory_order_acquire)) {
        ctx->dopOutputMarkerStart = true;
    } else if (ctx->dsdDopTransport && isRawDsdInputActive(ctx)) {
        LOGW("nativeResetBuffer: preserving live DoP marker phase during raw DSD stream");
    }
    ctx->rawDsdCarry.clear();
    clearDsdPcmQueue(ctx);
}

// ==========================
// JNI: nativeGetRecommendedDelayUs
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetRecommendedDelayUs(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 5000;
    return ctx->writeThrottleUs.load();
}

// ==========================
// JNI: nativeGetBufferUsedBytes
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetBufferUsedBytes(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    return (jint)ringAvailable(ctx);
}

// ==========================
// JNI: nativeGetOutputBytesPerSecond
// Returns the device output byte rate (sampleRate 脳 bytesPerFrame).
// Used by Kotlin to compute correct water mark thresholds for the native ring buffer,
// which stores format-converted data (e.g. DoP) at a different rate than the source PCM.
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetOutputBytesPerSecond(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    // 优先使用 runtime model 的 B/s（sampleRate * channels * subslotBytes）
    syncUsbRuntimeModel(ctx);
    int runtimeBps = ctx->runtimeFormat.bytesPerSecond;
    if (runtimeBps > 0) return (jint)runtimeBps;
    return (jint)ctx->bytes_per_second;
}

// ==========================
// JNI: nativeGetCurrentFrameBytes
// 返回 channels * selectedSubslotBytes
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetCurrentFrameBytes(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    syncUsbRuntimeModel(ctx);
    return (jint)ctx->runtimeFormat.frameBytes;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetOutputSampleRate(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    return (jint)ctx->sampleRate;
}

// ==========================
// JNI: runtime route/format snapshot
// Expose native-selected interface/alt/endpoint and actual device format back
// to Kotlin so the status page does not show Java-side init hints forever.
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetCurrentInterfaceNumber(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return -1;
    return ctx->selectedAsInterface;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetCurrentAltSetting(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    return ctx->selectedAltSetting;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetCurrentOutEndpoint(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    return ctx->selectedOutEndpoint;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetCurrentFeedbackEndpoint(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    return ctx->selectedFeedbackEndpoint;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetCurrentChannelCount(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    return ctx->currentChannels;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetCurrentBitDepth(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    return ctx->currentBits;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetCurrentSubslotSize(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    return ctx->currentSubslotSize;
}

static UsbAudioContext* firstLiveHandleNoLock() {
    return gHandleRegistry.firstLiveHandleNoLock();
}

// ==========================
// JNI: nativeGetPacketSize
// Legacy name kept for ABI compatibility. This returns transfer capacity bytes,
// not the nominal USB audio packet payload. Kotlin should use the explicit
// 以下 getter 供调度和水位判断使用。
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetPacketSize(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    return ctx->transferSize;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetTransferCapacityBytes(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    return ctx ? (jint)ctx->transferSize : 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetMaxPacketBytes(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    return ctx ? (jint)ctx->maxPacketSize : 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetServiceIntervalsPerSecond(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    return ctx ? (jint)ctx->serviceIntervalsPerSecond : 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetNominalBytesPerInterval(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    return ctx ? (jint)ctx->bytesPerServiceInterval : 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetNominalBytesPerTransfer(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx || ctx->serviceIntervalsPerSecond <= 0) return 0;
    uint64_t bytes = (ctx->bytes_per_second * (uint64_t)ctx->numIsoPackets) /
                     (uint64_t)ctx->serviceIntervalsPerSecond;
    return (jint)std::min<uint64_t>(bytes, INT_MAX);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetCompletedUsbBytesPerSecond(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    const int64_t last = ctx->lastCompletedUsbBytesPerSec.load(std::memory_order_relaxed);
    if (last > 0) return (jlong)last;
    const int64_t windowStart = ctx->statsWindowStartMs.load(std::memory_order_relaxed);
    const int64_t elapsedMs = std::max<int64_t>(1, nowSteadyMs() - windowStart);
    const int64_t current = ctx->statsCompletedUsbBytes.load(std::memory_order_relaxed);
    return current > 0 ? (jlong)((current * 1000LL) / elapsedMs) : 0;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetScheduledUsbBytesPerSecond(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return 0;
    const int64_t last = ctx->lastScheduledUsbBytesPerSec.load(std::memory_order_relaxed);
    if (last > 0) return (jlong)last;
    const int64_t windowStart = ctx->statsWindowStartMs.load(std::memory_order_relaxed);
    const int64_t elapsedMs = std::max<int64_t>(1, nowSteadyMs() - windowStart);
    const int64_t current = ctx->statsScheduledUsbBytes.load(std::memory_order_relaxed);
    return current > 0 ? (jlong)((current * 1000LL) / elapsedMs) : 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetFeedbackState(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    return ctx ? (jint)ctx->feedbackState.load(std::memory_order_relaxed)
               : static_cast<jint>(UsbFeedbackState::NONE);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetFeedbackSampleRateMilli(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    return ctx ? (jint)ctx->feedbackSampleRateMilli.load(std::memory_order_relaxed) : 0;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetPacingMode(
        JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    return ctx ? (jint)ctx->pacingMode.load(std::memory_order_relaxed)
               : static_cast<jint>(-1);
}

// ========================== nativeGetAudibleStateString ==========================
extern "C"
JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetAudibleStateString(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) thiz;
    if (handle == 0) return env->NewStringUTF("");
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx)) return env->NewStringUTF("");

    const int64_t completedNow = ctx->statsCompletedUsbBytes.load(std::memory_order_acquire);
    const int64_t lastCompletedBps = ctx->lastCompletedUsbBytesPerSec.load(std::memory_order_acquire);
    const bool sawUsbPayload = completedNow > 0 || lastCompletedBps > 0;

    // Let a query repair/observe a just-restored route even if no later stats log ran.
    maybeMarkUsbAudibleAccepted(ctx, "query");

    const int64_t completed = ctx->statsCompletedUsbBytes.load(std::memory_order_acquire);
    const bool volReady = isAudibleVolumeRouteReady(ctx);
    const bool accepted = ctx->audibleAccepted.load(std::memory_order_acquire);
    const int64_t startMs = ctx->audibleStartMs.load(std::memory_order_acquire);
    const int64_t firstMs = ctx->audibleFirstCompletionMs.load(std::memory_order_acquire);
    const int64_t acceptedMs = ctx->audibleAcceptedMs.load(std::memory_order_acquire);
    const int64_t nowMs = nowSteadyMs();
    rawsmusic::usb::RawUsbAudibleStateSnapshot snapshot;
    snapshot.sessionId = ctx->streamSessionId.load(std::memory_order_acquire);
    snapshot.streamState = static_cast<int>(ctx->sessionState.load(std::memory_order_acquire));
    snapshot.initialized = ctx->initialized.load(std::memory_order_acquire);
    snapshot.streaming = ctx->streaming.load(std::memory_order_acquire);
    snapshot.acceptingWrites = ctx->acceptingWrites.load(std::memory_order_acquire);
    snapshot.audible = accepted;
    snapshot.completedBytes = completed;
    snapshot.expectedBytesPerSecond = ctx->bytes_per_second;
    snapshot.firstCompletionMs = firstMs;
    snapshot.acceptedMs = acceptedMs;
    snapshot.ageMs = startMs > 0 ? nowMs - startMs : 0;
    snapshot.volumeReady = volReady;
    snapshot.hardwareVolumeEnabled = ctx->hardwareVolumeEnabled;
    snapshot.hardwareVolumeSafe = ctx->hardwareVolumeSafe;
    snapshot.playbackMode = static_cast<int>(ctx->playbackMode);
    const std::string state = rawsmusic::usb::formatRawUsbAudibleState(snapshot);
    return env->NewStringUTF(state.c_str());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetStreamSessionId(
        JNIEnv*, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return 0;
    return (jlong)ctx->streamSessionId.load(std::memory_order_acquire);
}

// ==========================
// JNI: nativeSetSampleRate
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetSampleRate(
        JNIEnv *env, jobject thiz, jint sampleRate) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return -1;
    if (!ctx->devHandle) return -1;
    if (sampleRate <= 0) return -1;
    if (ctx->dsdSession && ctx->dsdPcmRateLocked) {
        LOGW("DSD_TRANSPORT_LOCK nativeSetSampleRate ignored requested=%d carrier=%u "
             "dsdHz=%u DSD%d mode=%s swr=%p",
             sampleRate,
             ctx->dsdCarrierRateHz,
             ctx->dsdRateHz,
             ctx->dsdRateMultiplier,
             ctx->dsdDopTransport ? "DoP" : "NativeRAW",
             static_cast<void*>(ctx->swrCtx));
        return 0;
    }
    ctx->sampleRate = sampleRate;
    // 设备采样率变重建重采样上下文
    int swrRet = initSwrContext(ctx);
    if (swrRet != 0) {
        LOGW("nativeSetSampleRate: re-init swr failed (%d), resampling may be broken", swrRet);
    }
    // 根据重采格式适配状更新帧大小
    // ring buffer 存储设备格式数据时（重采样或PCM适配），使用设备帧大
    if (ctx->needsResample || ctx->pcmAdapter != PCM_ADAPTER_NONE || isRawDsdInputActive(ctx)) {
        ctx->bytesPerFrame = ctx->deviceBytesPerFrame;
    } else {
        ctx->bytesPerFrame = ctx->sourceBytesPerFrame;
    }
    ctx->bytes_per_second = sampleRate * ctx->bytesPerFrame;
    ctx->bytesPerPacket = (sampleRate * ctx->bytesPerFrame) / ctx->serviceIntervalsPerSecond;
    if (ctx->bytesPerPacket == 0) ctx->bytesPerPacket = 1;
    // 重建 IsoPacer（帧大小可能已变
    ctx->isoPacer.reset(
            (double)ctx->sampleRate,
            (uint32_t)ctx->serviceIntervalsPerSecond,
            (uint32_t)ctx->bytesPerFrame,
            ctx->maxPacketSize
    );
    ctx->nominalSampleRate = (uint32_t)ctx->sampleRate;
    syncUsbRuntimeModel(ctx);
    LOGI("nativeSetSampleRate: %d -> bytesPerFrame=%d bytesPerPacket=%d resample=%d runtimeBps=%d",
         sampleRate, ctx->bytesPerFrame, ctx->bytesPerPacket, ctx->needsResample ? 1 : 0,
         ctx->runtimeFormat.bytesPerSecond);
    return 0;
}

// Forward declaration: checkUsbWriteAllowed (defined later, used by nativeWriteHandle)
static int checkUsbWriteAllowed(UsbAudioContext* ctx);

// ==========================
// JNI: nativeWriteHandle - write PCM data to specific handle's ring buffer
// Used by prepareAndStartForTrack for prebuffering before start
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeWriteHandle(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray data, jint offset, jint length) {

    if (handle == 0 || !data || length <= 0) return 0;
    jsize arrayLen = env->GetArrayLength(data);
    if (offset < 0 || length < 0 || offset > arrayLen || length > arrayLen - offset) {
        LOGE("nativeWriteHandle invalid range: offset=%d length=%d arrayLen=%d",
             offset, length, arrayLen);
        return ERR_NOT_INITIALIZED;
    }
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);

    if (!isLiveHandle(ctx)) {
        LOGW("nativeWriteHandle ignored: dead handle=%p", ctx);
        return ERR_NOT_INITIALIZED;
    }
    if (ctx->closing.load(std::memory_order_acquire)) {
        LOGW("nativeWriteHandle ignored: closing handle=%p", ctx);
        return -EPIPE;
    }
    if (!ctx->initialized.load(std::memory_order_acquire)) {
        LOGW("nativeWriteHandle ignored: not initialized handle=%p", ctx);
        return ERR_NOT_INITIALIZED;
    }
    if (ctx->pcmRingBuffer.size() == 0) {
        LOGW("nativeWriteHandle ignored: ring buffer not allocated");
        return ERR_NOT_INITIALIZED;
    }

    // 状态机检查：sessionBroken / fatalError / standby / acceptingWrites
    int allowed = checkUsbWriteAllowed(ctx);
    if (allowed <= 0) {
        return allowed;
    }

    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -2;

    // One-shot diagnostics: keep the existing DSD trigger, but also dump the
    // first normal PCM write for every fresh USB context. The latter is critical
    // for UAC1 packed-24 debugging because the Android/FFmpeg bridge carries
    // >16-bit PCM in a 4-byte S32 container. The first PCM result is also cached
    // on the handle and exported through nativeGetStatsString(), so the app's
    // USB DAC report contains the same evidence without requiring logcat.
    {
        const bool pcmFirstWrite =
                ctx->firstPcmContainerDiagPending.exchange(false, std::memory_order_acq_rel);
        const bool dsdFirstWrite = consumeFirstDsdWriteDump();
        if (dsdFirstWrite || pcmFirstWrite) {
            int dumpLen = (length < 16) ? length : 16;
            char hex[80] = {0};
            char compactHex[33] = {0};
            int pos = 0;
            int compactPos = 0;
            for (int i = 0; i < dumpLen && pos < 72; i++) {
                const uint8_t value = static_cast<uint8_t>(bytes[offset + i]);
                pos += snprintf(hex + pos, 72 - pos, "%02X ", value);
                if (compactPos <= 30) {
                    compactPos += snprintf(compactHex + compactPos,
                                           sizeof(compactHex) - compactPos,
                                           "%02X", value);
                }
            }
            LOGI("nativeWriteHandle FIRST WRITE: pcmFirst=%d proto=UAC%d length=%d srcFrame=%d dstFrame=%d "
                 "adapter=%d needsResample=%d first16=[%s]",
                 pcmFirstWrite ? 1 : 0, (int)ctx->protocol, length,
                 ctx->sourceBytesPerFrame, ctx->bytesPerFrame,
                 (int)ctx->pcmAdapter, (int)ctx->needsResample, hex);

            int samplesToInspect = 0;
            int lowZero = 0;
            int signExtendedTop = 0;
            int nonSilent = 0;
            if ((ctx->pcmAdapter == PCM_ADAPTER_S32_TO_S24 ||
                 ctx->pcmAdapter == PCM_ADAPTER_S32_TO_S24_IN_S32) &&
                ctx->sourceBytesPerSample == 4 && ctx->sourceChannels > 0) {
                const int sampleStride = 4;
                const int availableSamples = length / sampleStride;
                samplesToInspect = availableSamples < 256 ? availableSamples : 256;
                for (int i = 0; i < samplesToInspect; ++i) {
                    const uint8_t *sample =
                            reinterpret_cast<const uint8_t*>(bytes + offset + i * sampleStride);
                    if (sample[0] == 0x00) {
                        lowZero++;
                    }
                    const bool negative24 = (sample[2] & 0x80) != 0;
                    const bool topLooksSign = negative24 ? (sample[3] == 0xFF) : (sample[3] == 0x00);
                    if (topLooksSign) {
                        signExtendedTop++;
                    }
                    if (sample[0] != 0 || sample[1] != 0 || sample[2] != 0 || sample[3] != 0) {
                        nonSilent++;
                    }
                }
                LOGI("nativeWriteHandle S32 diag: samples=%d nonSilent=%d lowZero=%d signExtendedTop=%d "
                     "adapter=%s",
                     samplesToInspect, nonSilent, lowZero, signExtendedTop,
                     pcmAdapterName(ctx->pcmAdapter));
            }

            if (pcmFirstWrite) {
                ctx->pcmInputDiagProtocol = static_cast<int>(ctx->protocol);
                ctx->pcmInputDiagSourceFrame = ctx->sourceBytesPerFrame;
                ctx->pcmInputDiagDeviceFrame = ctx->bytesPerFrame;
                ctx->pcmInputDiagAdapter = static_cast<int>(ctx->pcmAdapter);
                ctx->pcmInputDiagNeedsResample = ctx->needsResample;
                ctx->pcmInputDiagSamples = samplesToInspect;
                ctx->pcmInputDiagNonSilent = nonSilent;
                ctx->pcmInputDiagLowZero = lowZero;
                ctx->pcmInputDiagSignExtendedTop = signExtendedTop;
                snprintf(ctx->pcmInputDiagFirst16Hex,
                         sizeof(ctx->pcmInputDiagFirst16Hex),
                         "%s", compactHex[0] != '\0' ? compactHex : "none");
                ctx->pcmInputDiagReady.store(true, std::memory_order_release);
                {
                    std::lock_guard<std::mutex> lastDiagLock(gLastPcmInputDiagMtx);
                    rawsmusic::usb::RawUsbStatsSnapshot last;
                    last.pcmInputDiagReady = true;
                    last.pcmProtocol = ctx->pcmInputDiagProtocol;
                    last.pcmSourceFrameBytes = ctx->pcmInputDiagSourceFrame;
                    last.pcmDeviceFrameBytes = ctx->pcmInputDiagDeviceFrame;
                    last.pcmAdapter = pcmAdapterName(
                            static_cast<PcmFormatAdapter>(ctx->pcmInputDiagAdapter));
                    last.pcmNeedsResample = ctx->pcmInputDiagNeedsResample;
                    last.pcmSamples = ctx->pcmInputDiagSamples;
                    last.pcmNonSilent = ctx->pcmInputDiagNonSilent;
                    last.pcmLowZero = ctx->pcmInputDiagLowZero;
                    last.pcmSignExtendedTop = ctx->pcmInputDiagSignExtendedTop;
                    last.pcmFirst16Hex = ctx->pcmInputDiagFirst16Hex;
                    gLastPcmInputDiagSnapshot = std::move(last);
                    gLastPcmInputDiagReady = true;
                }
            }
        }
    }

    int written = 0;  // Java-side source bytes consumed
    {
        // SPSC lock-free: nativeWrite is sole producer, fillIsoTransfer is sole consumer
        size_t bufSize = ctx->pcmRingBuffer.size();
        size_t used = ringAvailable(ctx);
        size_t freeSpace = (bufSize - 1) - used;
        float usageRatio = (float)used / (float)bufSize;
        const bool rawDsdDirect = isRawDsdInputActive(ctx);
        bool dsdActiveForLimit = ctx->dsdSession;
        const bool backgroundPlayback =
                g_usbBackgroundPlaybackActive.load(std::memory_order_acquire) &&
                g_usbExclusiveActive.load(std::memory_order_acquire);
        int softLimitMs = backgroundPlayback
                          ? USB_BACKGROUND_WRITE_SOFT_LIMIT_MS
                          : (dsdActiveForLimit ? DSD_WRITE_SOFT_LIMIT_MS : USB_WRITE_SOFT_LIMIT_MS);
        size_t softLimitBytes =
                (size_t)ctx->sampleRate * ctx->bytesPerFrame * softLimitMs / 1000;
        if (softLimitBytes >= bufSize) {
            softLimitBytes = bufSize - 1;
        }

        if (used >= softLimitBytes) {
            ctx->writeThrottleUs.store(4000, std::memory_order_relaxed);
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return 0;
        }

        int delayUs = 0;
        if (usageRatio < 0.60f) {
            delayUs = 0;
        } else if (usageRatio < 0.75f) {
            delayUs = 2000;
        } else if (usageRatio < 0.90f) {
            delayUs = 4000;
        } else {
            delayUs = 6000;
        }
        ctx->writeThrottleUs.store(delayUs, std::memory_order_relaxed);

        if (freeSpace == 0) {
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return 0;
        }

        // 检查 DSD+DoP 是否启用
        bool dsdActive = ctx->dsdSession;
        bool dopActive = dsdActive && ctx->dsdDopTransport;

        // DoP 路径诊断：前 50 + 500 次调用打印状态（reinit 后重置）
        {
            int cnt = nextDopHandleLogCount();
            if (cnt < 3 || cnt % 2000 == 0) {
                LOGI("nativeWriteHandle #%d: dopActive=%d dsdActive=%d needsResample=%d swrCtx=%p "
                     "srcBPF=%d devBPF=%d freeSpace=%zu/%zu length=%d "
                     "convEnabled=%d convInit=%d rawDsd=%d dopEnabled=%d",
                     cnt, dopActive ? 1 : 0, dsdActive ? 1 : 0,
                     ctx->needsResample ? 1 : 0, ctx->swrCtx,
                     ctx->sourceBytesPerFrame, ctx->bytesPerFrame,
                     freeSpace, bufSize, length,
                     ctx->dsdSession ? 1 : 0,
                     ctx->dsdConverterInitialized.load(std::memory_order_relaxed) ? 1 : 0,
                     rawDsdDirect ? 1 : 0,
                     ctx->dsdDopTransport ? 1 : 0);
            }
        }

        if (rawDsdDirect) {
            size_t w = ctx->pcmWritePos.load(std::memory_order_acquire);
            written = (int)writeRawDsdToRing(
                    ctx,
                    reinterpret_cast<const uint8_t*>(bytes + offset),
                    (size_t)length,
                    freeSpace,
                    bufSize,
                    w);
        } else if (dsdActive) {
            const size_t queued = enqueuePcmForDsdWorker(
                    ctx, reinterpret_cast<const uint8_t*>(bytes + offset),
                    static_cast<size_t>(length));
            if (queued > 0) {
                written = static_cast<int>(queued);
            } else {
                ctx->writeThrottleUs.store(1000, std::memory_order_relaxed);
            }

        } else if (ctx->needsResample && ctx->swrCtx) {
            // ===== Resample branch =====
            // swr output remains in decoder source container (S16LE/S32LE).
            // Convert to the USB device container after resampling.
            const int srcFrameSize = ctx->sourceBytesPerFrame;
            const int swrFrameSize = ctx->deviceChannels * ctx->sourceBytesPerSample;
            const int dstFrameSize = ctx->bytesPerFrame;
            if (srcFrameSize <= 0 || swrFrameSize <= 0 || dstFrameSize <= 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }

            size_t devAlignedFree = (freeSpace / dstFrameSize) * dstFrameSize;
            size_t maxOutputFrames = devAlignedFree / dstFrameSize;
            size_t maxSwrFrames = ctx->swrOutBufferSize / swrFrameSize;
            maxOutputFrames = std::min(maxOutputFrames, maxSwrFrames);
            if (maxOutputFrames == 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }

            size_t srcFrames = (size_t)length / srcFrameSize;
            if (srcFrames == 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }

            int64_t delayInSrcRate = swr_get_delay(ctx->swrCtx, ctx->sourceSampleRate);
            int64_t estOut = av_rescale_rnd(
                    delayInSrcRate + (int64_t)srcFrames,
                    ctx->sampleRate,
                    ctx->sourceSampleRate,
                    AV_ROUND_UP
            );
            if (estOut > (int64_t)maxOutputFrames) {
                int64_t allowedSrc = av_rescale_rnd(
                        (int64_t)maxOutputFrames,
                        ctx->sourceSampleRate,
                        ctx->sampleRate,
                        AV_ROUND_DOWN
                );
                if (allowedSrc <= 0) {
                    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                    return 0;
                }
                srcFrames = (size_t)std::min<int64_t>(allowedSrc, (int64_t)srcFrames);
                if (srcFrames == 0) srcFrames = 1;
            }

            const uint8_t *inBuf[1] = { reinterpret_cast<const uint8_t*>(bytes + offset) };
            uint8_t *outBuf[1] = { ctx->swrOutBuffer.data() };
            int outSamples = swr_convert(
                    ctx->swrCtx, outBuf, (int)maxOutputFrames,
                    inBuf, (int)srcFrames);
            if (outSamples <= 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }

            const size_t srcConsumed = srcFrames * (size_t)srcFrameSize;
            size_t outWritten = writeSourcePcmFramesToUsbRing(
                    ctx, ctx->swrOutBuffer.data(), (size_t)outSamples, bufSize);
            if (outWritten > 0) {
                written = (int)srcConsumed;
            }
        } else {
            // ===== 非重采样路径 =====
            // 关键：freeSpace 必须按帧对齐，防止写入非整帧数据导致爆音
            const int srcFrameSize = ctx->sourceBytesPerFrame;
            const int dstFrameSize = ctx->bytesPerFrame;
            size_t alignedFree = (freeSpace / dstFrameSize) * dstFrameSize;
            if (alignedFree == 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }

            if (dsdActive) {
                const size_t queued = enqueuePcmForDsdWorker(
                        ctx, reinterpret_cast<const uint8_t*>(bytes + offset),
                        static_cast<size_t>(length));
                if (queued > 0) {
                    written = static_cast<int>(queued);
                }
            } else if (ctx->pcmAdapter != PCM_ADAPTER_NONE) {
                // 有格式配（如 S16→S24），使用 convertPcmToUsbFormat
                size_t srcBytes = (size_t)length;
                size_t maxSrcFrames = alignedFree / dstFrameSize;
                size_t srcFrames = srcBytes / srcFrameSize;
                if (srcFrames > maxSrcFrames) srcFrames = maxSrcFrames;
                if (srcFrames == 0) {
                    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                    return 0;
                }
                size_t srcConsumed = srcFrames * srcFrameSize;
                size_t outNeeded = srcFrames * dstFrameSize;

                size_t w = ctx->pcmWritePos.load(std::memory_order_acquire);
                size_t firstPartCap = bufSize - w;
                firstPartCap = (firstPartCap / dstFrameSize) * dstFrameSize;
                size_t firstPartOut = (outNeeded <= firstPartCap) ? outNeeded : firstPartCap;
                size_t firstFrames = firstPartOut / dstFrameSize;
                size_t firstSrcBytes = firstFrames * srcFrameSize;

                size_t wrote1 = convertPcmToUsbFormat(
                        ctx, reinterpret_cast<const uint8_t*>(bytes + offset),
                        firstSrcBytes, ctx->pcmRingBuffer.data() + w, firstPartOut);
                size_t outWritten = wrote1;
                size_t remainingOut = outNeeded - wrote1;
                if (remainingOut > 0) {
                    size_t consumedFrames1 = wrote1 / dstFrameSize;
                    const uint8_t *src2 = reinterpret_cast<const uint8_t*>(bytes + offset)
                                          + consumedFrames1 * srcFrameSize;
                    size_t remainingSrc = (srcFrames - consumedFrames1) * srcFrameSize;
                    size_t wrote2 = convertPcmToUsbFormat(
                            ctx, src2, remainingSrc,
                            ctx->pcmRingBuffer.data(), remainingOut);
                    outWritten += wrote2;
                }
                ctx->pcmWritePos.store((w + outWritten) % bufSize, std::memory_order_release);
                written = (int)((outWritten / dstFrameSize) * srcFrameSize);
            } else {
                // 无格式配，直memcpy
                size_t toWrite = ((size_t)length > alignedFree) ? alignedFree : (size_t)length;
                // 二次对齐（确toWrite 帧对齐）
                toWrite = (toWrite / dstFrameSize) * dstFrameSize;
                if (toWrite > 0) {
                    size_t w = ctx->pcmWritePos.load(std::memory_order_relaxed);
                    size_t firstPart = bufSize - w;
                    if (toWrite <= firstPart) {
                        memcpy(ctx->pcmRingBuffer.data() + w, bytes + offset, toWrite);
                    } else {
                        memcpy(ctx->pcmRingBuffer.data() + w, bytes + offset, firstPart);
                        memcpy(ctx->pcmRingBuffer.data(), bytes + offset + firstPart, toWrite - firstPart);
                    }
                    ctx->pcmWritePos.store((w + toWrite) % bufSize, std::memory_order_release);
                    written = (int)toWrite;
                }
            }
        }
    }

    if (written > 0) {
        ctx->statsAppBytes.fetch_add(written, std::memory_order_relaxed);
    }
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return written;
}

// ==========================
// JNI: nativeSetVolume (handle-based)
// Software/fixed-volume API only. Hardware Feature Unit writes are owned by the
// explicit, serialized hardware-volume command path.
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetVolume(
        JNIEnv* env, jobject thiz, jlong handle, jfloat linear) {
    if (handle == 0) return -1;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return -1;

    float vol = linear;
    if (vol < 0.0f) vol = 0.0f;
    if (vol > 1.0f) vol = 1.0f;

    LOGI("nativeSetVolume(handle=0x%llx) linear=%.3f mode=%d",
         (unsigned long long)handle, vol, (int)ctx->playbackMode);

    // Block legacy linear API in hardware volume mode
    if (ctx->playbackMode == UsbPlaybackMode::ExclusiveProcessedHwVol ||
        ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectHwVol) {
        LOGW("nativeSetVolume ignored in hardware-volume mode: linear=%.4f. Use nativeSetHardwareVolumeDb.",
             vol);
        return 0;
    }

    // startup guard: output cap is transient, save real volume
    {
        int64_t guardUntil = ctx->startupVolumeGuardUntilMs.load(std::memory_order_acquire);
        if (transitionOwnerUsesLegacyStartupFade(getTransitionGainOwner(ctx)) &&
            nowSteadyMs() < guardUntil && vol > USB_STARTUP_GUARD_CAP) {
            LOGI("nativeSetVolume: startup guard active, requested=%.3f output will be capped to %.3f temporarily",
                 vol, USB_STARTUP_GUARD_CAP);
        }
    }
    // Save real requested volume, not the capped value
    gSoftwareVolume.store(vol, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lk(gRegistryMtx);
        for (auto* h : gLiveHandles) {
            if (h) h->softwareVolume.store(vol, std::memory_order_relaxed);
        }
    }
    LOGI("nativeSetVolume: software volume updated to %.3f", vol);
    return 0;
}

// ========================== nativeSetPcmSoftwareGain ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetPcmSoftwareGain(
        JNIEnv* env, jobject thiz, jlong handle, jfloat gain) {
    if (handle == 0) return -1;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return -1;
    float g = std::clamp((float)gain, 0.0f, 1.0f);
    ctx->softwareVolume.store(g, std::memory_order_release);
    gSoftwareVolume.store(g, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lk(gRegistryMtx);
        for (auto* h : gLiveHandles) {
            if (h) h->softwareVolume.store(g, std::memory_order_relaxed);
        }
    }
    LOGI("nativeSetPcmSoftwareGain(handle=0x%llx) gain=%.4f mode=%d",
         (unsigned long long)handle, g, (int)ctx->playbackMode);
    return 0;
}

// ========================== nativePrepareForSeek ==========================
// seek 前软停止：停止 ISO 传输 + 清 ring，但不标 BROKEN，不关闭 handle。
// 设置短淡入防止 seek 后刺音。
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativePrepareForSeek(
        JNIEnv* env, jobject, jlong handle, jint rampMs, jstring reason) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx) { LOGW("nativePrepareForSeek: null ctx"); return; }
    if (!isLiveHandle(ctx)) { LOGW("nativePrepareForSeek ignored: dead handle %p", ctx); return; }

    const char* reasonChars = reason ? env->GetStringUTFChars(reason, nullptr) : nullptr;
    LOGI("nativePrepareForSeek ENTER: state=%s streaming=%d reason=%s",
         usbStreamStateName(getUsbStreamState(ctx)),
         ctx->streaming.load(std::memory_order_acquire) ? 1 : 0,
         reasonChars ? reasonChars : "null");
    if (reason && reasonChars) env->ReleaseStringUTFChars(reason, reasonChars);

    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (ctx->closing.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire) ||
        ctx->quarantined.load(std::memory_order_acquire) ||
        ctx->sessionBroken.load(std::memory_order_acquire)) {
        LOGW("nativePrepareForSeek ignored: poisoned session closing=%d lost=%d quarantined=%d broken=%d",
             ctx->closing.load() ? 1 : 0,
             ctx->transportLost.load() ? 1 : 0,
             ctx->quarantined.load() ? 1 : 0,
             ctx->sessionBroken.load() ? 1 : 0);
        return;
    }

    const UsbPacingMode currentPacingMode = static_cast<UsbPacingMode>(
            ctx->pacingMode.load(std::memory_order_acquire));
    const UsbFeedbackState currentFeedbackState = static_cast<UsbFeedbackState>(
            ctx->feedbackState.load(std::memory_order_acquire));
    const bool fixedPacerStream =
            currentPacingMode == UsbPacingMode::NoFeedbackFixed ||
            currentPacingMode == UsbPacingMode::FeedbackDegradedFixed;
    const bool sameModeledStream =
            ctx->runtimeFormat.isValid() &&
            ctx->deviceBytesPerFrame > 0 &&
            ctx->clock.deviceBytesPerSecond > 0 &&
            ctx->selectedOutEndpoint != 0;
    const bool keepUsbStreamingForSeek =
            ctx->streaming.load(std::memory_order_acquire) &&
            sameModeledStream &&
            !ctx->transportLost.load(std::memory_order_acquire) &&
            ctx->fatalError.load(std::memory_order_acquire) == 0;

    if (keepUsbStreamingForSeek) {
        // 原生 USB 音频模型 soft seek: do not tear down ISO for same-route PCM seek.
        // Stopping/cancelling transfers is the main source of seek pop/current
        // noise and, on Android 16, can briefly starve a newly restarted libusb
        // event loop. Keep the USB clock/alt/endpoint alive, clear old PCM, feed
        // a short transition-silence window, and let fresh decoder data resume
        // the existing StreamConfig. This applies to fixed/no-feedback software
        // volume streams too; feedback is not required for a warm seek.
        ctx->acceptingWrites.store(false, std::memory_order_release);
        if (usesSessionPcmTransitionEnvelope(ctx)) {
            const int safeRampMs = std::clamp((int)rampMs, 20, 200);
            ctx->sessionVolumeCurrent.store(0.0f, std::memory_order_release);
            armSessionEnvelopeInternal(ctx, 1.0f, safeRampMs);
            ctx->startupVolumeGuardUntilMs.store(nowSteadyMs() + USB_STARTUP_GUARD_MS,
                                                 std::memory_order_release);
            LOGI("nativePrepareForSeek: owner=SessionPcm warm seek fade-in=%dms", safeRampMs);
        } else {
            // UnityPcm and hardware/bit-perfect routes never alter PCM. TransportSilence
            // routes (DoP/native DSD/PCM-to-DSD) rely on the legal silence window below.
            forceSessionEnvelopeUnity(ctx);
            LOGI("nativePrepareForSeek: warm seek PCM envelope bypassed owner=%s",
                 transitionGainOwnerName(getTransitionGainOwner(ctx)));
        }
        ctx->pcmWritePos.store(0, std::memory_order_release);
        ctx->pcmReadPos.store(0, std::memory_order_release);
        if (!ctx->pcmRingBuffer.empty()) {
            memset(ctx->pcmRingBuffer.data(), 0, ctx->pcmRingBuffer.size());
        }
        ctx->starved = false;
        ctx->consecutiveEmptyTransfers = 0;
        ctx->transitionSilenceBytesRemaining.store(
                std::max(ctx->deviceBytesPerFrame * 256, ctx->clock.deviceBytesPerSecond * USB_TRANSITION_SILENCE_MS / 1000),
                std::memory_order_release);
        ctx->starvedRecoveryBytes =
                ctx->sampleRate * ctx->bytesPerFrame * STARVATION_RECOVERY_MS / 1000;
        if (ctx->starvedRecoveryBytes < ctx->bytesPerFrame * 64) {
            ctx->starvedRecoveryBytes = ctx->bytesPerFrame * 64;
        }
        ctx->isoPacer.accumulatorQ32 = 0;
        ctx->statsUnderrun.store(0, std::memory_order_relaxed);
        ctx->startupSilenceDone = false;
        ctx->fadeSamplesRemaining = 0;
        ctx->fadeTotalSamples = 0;
        ctx->stopFadeSamplesRemaining.store(0, std::memory_order_release);
        ctx->stopFadeTotalSamples.store(0, std::memory_order_release);
        ctx->stopFadeActive.store(false, std::memory_order_release);
        ctx->stopping.store(false, std::memory_order_release);
        // sessionBroken is monotonic for this native handle. Once poisoned, only
    // a fresh nativeInitUsbDevice may create a healthy session.
        ctx->stopRequested.store(false, std::memory_order_release);
        ctx->acceptingWrites.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::STREAMING, "prepare_for_seek_soft_keep_usb");
        LOGI("nativePrepareForSeek: USB warm seek kept USB streaming, PCM ring cleared; "
             "pacing=%s feedback=%s fixedPacer=%d sameStream=%d hwUnity=%d strictBitPerfect=%d pending=%d transitionSilence=%d",
             pacingModeName(static_cast<int>(currentPacingMode)),
             feedbackStateName(static_cast<int>(currentFeedbackState)),
             fixedPacerStream ? 1 : 0,
             sameModeledStream ? 1 : 0,
             isHardwareVolumePcmUnityPath(ctx) ? 1 : 0,
             isStrictBitPerfectPcmPath(ctx) ? 1 : 0,
             ctx->pendingTransfers.load(std::memory_order_acquire),
             ctx->transitionSilenceBytesRemaining.load(std::memory_order_acquire));
        return;
    }

    // Fallback path for software-volume routes or non-streaming handles.
    requestOutputFadeOutAndWait(ctx, std::max(8, (int) rampMs), "prepare_for_seek");
    stopStreamingLocked(ctx);

    // 重置状态：允许后续 nativeStart
    ctx->stopping.store(false, std::memory_order_release);
    ctx->acceptingWrites.store(true, std::memory_order_release);
    // sessionBroken is monotonic for this native handle. Once poisoned, only
    // a fresh nativeInitUsbDevice may create a healthy session.
    ctx->stopRequested.store(false, std::memory_order_release);

    // 清空 ring buffer（SPSC 无锁，直接重置读写位置）
    ctx->pcmWritePos.store(0, std::memory_order_release);
    ctx->pcmReadPos.store(0, std::memory_order_release);
    if (!ctx->pcmRingBuffer.empty()) {
        memset(ctx->pcmRingBuffer.data(), 0, ctx->pcmRingBuffer.size());
    }
    ctx->starved = false;
    ctx->consecutiveEmptyTransfers = 0;
    ctx->starvedRecoveryBytes = 0;
    ctx->isoPacer.accumulatorQ32 = 0;
    ctx->statsUnderrun.store(0, std::memory_order_relaxed);

    // Seek fade-in follows the configured single owner. Explicit owners never fall back to
    // the legacy startup multiplier, which would otherwise multiply the session envelope.
    int fadeSamples = 0;
    const int safeRampMs = std::clamp((int)rampMs, 20, 200);
    const UsbTransitionGainOwner gainOwner = getTransitionGainOwner(ctx);
    if (usesSessionPcmTransitionEnvelope(ctx)) {
        ctx->sessionVolumeCurrent.store(0.0f, std::memory_order_release);
        armSessionEnvelopeInternal(ctx, 1.0f, safeRampMs);
        ctx->fadeSamplesRemaining = 0;
        ctx->fadeTotalSamples = 0;
    } else if (transitionOwnerUsesLegacyStartupFade(gainOwner) &&
               !isHardwareVolumePcmUnityPath(ctx) &&
               !isStrictBitPerfectPcmPath(ctx) &&
               !ctx->dsdSession) {
        fadeSamples = std::max(64, ctx->sampleRate * safeRampMs / 1000);
        ctx->fadeSamplesRemaining = fadeSamples;
        ctx->fadeTotalSamples = fadeSamples;
    } else {
        forceSessionEnvelopeUnity(ctx);
        ctx->fadeSamplesRemaining = 0;
        ctx->fadeTotalSamples = 0;
    }

    setUsbStreamState(ctx, UsbStreamState::PREPARED, "prepare_for_seek");
    LOGI("nativePrepareForSeek DONE: acceptingWrites=1 owner=%s fadeSamples=%d sessionRemaining=%d",
         transitionGainOwnerName(gainOwner),
         fadeSamples,
         ctx->sessionVolumeFadeRemainingFrames.load(std::memory_order_acquire));
}

// ========================== nativeSetUsbSoftwareGain ==========================
// 全局软件增益，不需要 handle。用于路由层在 handle 就绪前预设增益。
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetUsbSoftwareGain(
        JNIEnv*, jobject, jfloat linear) {
    float v = linear;
    if (!std::isfinite(v)) v = 1.0f;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;

    gSoftwareVolume.store(v, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lk(gRegistryMtx);
        for (auto* h : gLiveHandles) {
            if (!h) continue;
            if (isStrictBitPerfectPcmPath(h)) {
                h->softwareVolume.store(1.0f, std::memory_order_relaxed);
            } else {
                h->softwareVolume.store(v, std::memory_order_relaxed);
            }
        }
    }
    LOGI("nativeSetUsbSoftwareGain: linear=%.4f", v);
}

// ========================== nativeSetTransitionGainOwner ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetTransitionGainOwner(
        JNIEnv*, jobject, jlong handle, jint ownerId) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) {
        LOGW("nativeSetTransitionGainOwner: invalid handle");
        return;
    }
    const UsbTransitionGainOwner owner = sanitizeTransitionGainOwner(ownerId);
    ctx->transitionGainOwner.store(static_cast<int>(owner), std::memory_order_release);

    // Explicit ownership replaces the legacy startup/stop PCM fade path. Clear any stale
    // state left by a previous generation before nativeStart or same-profile reuse.
    if (owner != UsbTransitionGainOwner::Legacy) {
        ctx->startupVolumeGuardUntilMs.store(0, std::memory_order_release);
        ctx->fadeSamplesRemaining = 0;
        ctx->fadeTotalSamples = 0;
        ctx->stopFadeSamplesRemaining.store(0, std::memory_order_release);
        ctx->stopFadeTotalSamples.store(0, std::memory_order_release);
        ctx->stopFadeActive.store(false, std::memory_order_release);
    }
    if (owner != UsbTransitionGainOwner::SessionPcm) {
        forceSessionEnvelopeUnity(ctx);
    }
    LOGI("nativeSetTransitionGainOwner: owner=%s(%d) dsd=%d mode=%d",
         transitionGainOwnerName(owner),
         static_cast<int>(owner),
         ctx->dsdSession ? 1 : 0,
         static_cast<int>(ctx->playbackMode));
}

// ========================== nativeSetSessionVolumeScale ==========================
// Native session PCM envelope. Commands are accepted only when SessionPcm is the selected
// transition owner; all other routes stay at unity and use transport-correct boundaries.
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetSessionVolumeScale(
        JNIEnv*, jobject, jlong handle, jfloat linear, jint fadeMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) {
        LOGW("nativeSetSessionVolumeScale: invalid handle");
        return;
    }
    if (!usesSessionPcmTransitionEnvelope(ctx)) {
        const UsbTransitionGainOwner owner = getTransitionGainOwner(ctx);
        forceSessionEnvelopeUnity(ctx);
        LOGI("nativeSetSessionVolumeScale ignored owner=%s strict=%d hwUnity=%d dsd=%d",
             transitionGainOwnerName(owner),
             isStrictBitPerfectPcmPath(ctx) ? 1 : 0,
             isHardwareVolumePcmUnityPath(ctx) ? 1 : 0,
             ctx->dsdSession ? 1 : 0);
        return;
    }

    float target = linear;
    if (!std::isfinite(target)) target = 1.0f;
    target = std::clamp(target, 0.0f, 1.0f);
    const int safeFadeMs = std::max(0, static_cast<int>(fadeMs));
    armSessionEnvelopeInternal(ctx, target, safeFadeMs);
    LOGI("nativeSetSessionVolumeScale: owner=SessionPcm target=%.4f fadeMs=%d remainingFrames=%d sr=%d",
         target,
         safeFadeMs,
         ctx->sessionVolumeFadeRemainingFrames.load(std::memory_order_acquire),
         ctx->sampleRate > 0 ? ctx->sampleRate : 44100);
}

// ========================== nativeSetHardwareVolumeDbNoCache ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetHardwareVolumeDbNoCache(
        JNIEnv* env, jobject thiz, jlong handle, jint db, jstring reason) {
    (void) thiz;
    if (handle == 0) return -1;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return -1;
    if (!(ctx->playbackMode == UsbPlaybackMode::ExclusiveProcessedHwVol ||
          ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectHwVol)) {
        LOGW("nativeSetHardwareVolumeDbNoCache rejected: db=%d mode=%d", (int)db, (int)ctx->playbackMode);
        return -2;
    }
    const int safeDb = std::clamp<int>((int)db, -60, 0);
    const int16_t raw = hardwareDbToRaw1DbStep(ctx, safeDb);
    const char* reasonChars = reason ? env->GetStringUTFChars(reason, nullptr) : nullptr;
    const int r = setHardwareTransientVolumeRawNoCache(ctx, raw);
    LOGI("nativeSetHardwareVolumeDbNoCache: reason=%s db=%d raw=%d %.2fdB r=%d path=%s cachedRaw=%d",
         reasonChars ? reasonChars : "unknown", safeDb, raw, raw / 256.0, r,
         ctx->featureUnitVolumePathName,
         ctx->lastHardwareVolumeRaw.load(std::memory_order_acquire));
    if (reason && reasonChars) env->ReleaseStringUTFChars(reason, reasonChars);
    return r;
}

// ========================== nativeSetHardwareVolumeRawNoCache ==========================
// Reattach recovery must not trust the old per-handle dedup value: the DAC can reset its
// Feature Unit to 0 dB while the native handle still remembers the last safe value.
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetHardwareVolumeRawNoCache(
        JNIEnv* env, jobject thiz, jlong handle, jint raw, jstring reason) {
    (void) thiz;
    if (handle == 0) return -1;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return -1;
    if (!(ctx->playbackMode == UsbPlaybackMode::ExclusiveProcessedHwVol ||
          ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectHwVol)) {
        LOGW("nativeSetHardwareVolumeRawNoCache rejected: raw=%d mode=%d",
             (int)raw, (int)ctx->playbackMode);
        return -2;
    }
    const int16_t target = quantizeVolumeRawToDeviceRes(ctx, static_cast<int>(raw));
    const char* reasonChars = reason ? env->GetStringUTFChars(reason, nullptr) : nullptr;
    const int result = setHardwareTransientVolumeRawNoCache(ctx, target);
    LOGI("nativeSetHardwareVolumeRawNoCache: reason=%s raw=%d db=%.2f result=%d path=%s",
         reasonChars ? reasonChars : "unknown",
         target,
         target / 256.0f,
         result,
         ctx->featureUnitVolumePathName ? ctx->featureUnitVolumePathName : "none");
    if (reason && reasonChars) env->ReleaseStringUTFChars(reason, reasonChars);
    return result;
}

// ========================== nativeSetHardwareVolumeRaw ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetHardwareVolumeRaw(
        JNIEnv* env, jobject thiz, jlong handle, jint raw, jstring reason) {
    (void) thiz;
    if (handle == 0) return -1;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return -1;
    if (!(ctx->playbackMode == UsbPlaybackMode::ExclusiveProcessedHwVol ||
          ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectHwVol)) {
        LOGW("nativeSetHardwareVolumeRaw rejected: raw=%d mode=%d", (int)raw, (int)ctx->playbackMode);
        return -2;
    }
    const char* reasonChars = reason ? env->GetStringUTFChars(reason, nullptr) : nullptr;
    const int16_t target = quantizeVolumeRawToDeviceRes(ctx, static_cast<int>(raw));
    const int result = setHardwareUserVolumeRaw(ctx, target);
    LOGI("nativeSetHardwareVolumeRaw: reason=%s raw=%d db=%.2f result=%d path=%s",
         reasonChars ? reasonChars : "unknown",
         target,
         target / 256.0f,
         result,
         ctx->featureUnitVolumePathName ? ctx->featureUnitVolumePathName : "none");
    if (reason && reasonChars) env->ReleaseStringUTFChars(reason, reasonChars);
    return result;
}

// ========================== nativeAdjustHardwareVolume ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeAdjustHardwareVolume(
        JNIEnv* env, jobject thiz, jlong handle, jint direction, jstring reason) {
    (void) thiz;
    if (handle == 0 || direction == 0) return -1;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return -1;
    if (!(ctx->playbackMode == UsbPlaybackMode::ExclusiveProcessedHwVol ||
          ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectHwVol) ||
        !ctx->hardwareVolumeEnabled || !ctx->hardwareVolumeSafe) {
        LOGW("nativeAdjustHardwareVolume rejected: direction=%d mode=%d enabled=%d safe=%d",
             (int)direction, (int)ctx->playbackMode,
             ctx->hardwareVolumeEnabled ? 1 : 0, ctx->hardwareVolumeSafe ? 1 : 0);
        return -2;
    }
    const char* reasonChars = reason ? env->GetStringUTFChars(reason, nullptr) : nullptr;
    int16_t target = 0;
    const int result = adjustHardwareUserVolumeRaw(ctx, direction, &target);
    LOGI("nativeAdjustHardwareVolume: reason=%s direction=%d target=%d db=%.2f result=%d",
         reasonChars ? reasonChars : "unknown", (int)direction, target,
         target / 256.0f, result);
    if (reason && reasonChars) env->ReleaseStringUTFChars(reason, reasonChars);
    return result;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetHardwareVolumeCurrentRaw(
        JNIEnv*, jobject, jlong handle) {
    if (handle == 0) return INT_MIN;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle || !ctx->hardwareVolumeSafe) return INT_MIN;
    int16_t current = 0;
    if (readHardwareCurrentRawForPath(ctx, &current)) {
        ctx->lastHardwareVolumeRaw.store(current, std::memory_order_release);
        ctx->hasLastHardwareVolumeRaw.store(true, std::memory_order_release);
        return current;
    }
    if (ctx->hasLastHardwareVolumeRaw.load(std::memory_order_acquire)) {
        return ctx->lastHardwareVolumeRaw.load(std::memory_order_acquire);
    }
    return INT_MIN;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetHardwareVolumeMinRaw(
        JNIEnv*, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    return isLiveHandle(ctx) ? static_cast<jint>(ctx->deviceVolMinRaw) : INT_MIN;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetHardwareVolumeMaxRaw(
        JNIEnv*, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    return isLiveHandle(ctx) ? static_cast<jint>(ctx->deviceVolMaxRaw) : INT_MAX;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetHardwareVolumeResRaw(
        JNIEnv*, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    return isLiveHandle(ctx) ? static_cast<jint>(std::max<int16_t>(ctx->deviceVolResRaw, 1)) : 1;
}

// ==========================
// JNI: nativeRequiresReinit
// UI 层判断是否需要重init（策略在运行中被切换
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeRequiresReinit(
        JNIEnv *env, jobject thiz) {
    return g_requiresReinit.load(std::memory_order_acquire)
           ? JNI_TRUE
           : JNI_FALSE;
}

// ==========================
// JNI: nativeOnUsbDetached
// USB 拔出时必须强制关闭完美比
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeOnUsbDetached(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::recursive_mutex> sessionPolicyLock(gNextSessionPolicyMtx);
    LOGW("USB detached: reset exclusive and bit-perfect state");
    g_usbExclusiveActive.store(false, std::memory_order_release);
    g_bitPerfectEnabled.store(false, std::memory_order_release);
    g_hardwareFeatureUnitRequested.store(false, std::memory_order_release);
    g_requiresReinit.store(false, std::memory_order_release);
    g_bitPerfectFixedVolumeAcknowledged.store(false, std::memory_order_release);
    g_hardwareVolumeValidated.store(false, std::memory_order_release);
    g_hardwareVolumeSafe.store(false, std::memory_order_release);
    g_hasLastRequestedHardwareVolumeRaw.store(false, std::memory_order_release);
    g_lastRequestedHardwareVolumeRaw.store(0, std::memory_order_release);
    // 恢复默认音量
    gSoftwareVolume.store(kDefaultDevicePolicy.safeInitialVolumeLinear, std::memory_order_release);
    // 同步到所live handle
    {
        std::lock_guard<std::mutex> lock(gRegistryMtx);
        for (auto* h : gLiveHandles) {
            if (h) h->softwareVolume.store(kDefaultDevicePolicy.safeInitialVolumeLinear, std::memory_order_relaxed);
        }
    }
}

// ==========================
// JNI: nativeResetUsbPolicyForNewDevice
// USB 插入新设备时重置策略（不触发 detach 的副作用
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeResetUsbPolicyForNewDevice(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::recursive_mutex> sessionPolicyLock(gNextSessionPolicyMtx);
    g_usbExclusiveActive.store(false, std::memory_order_release);
    g_bitPerfectEnabled.store(false, std::memory_order_release);
    g_hardwareFeatureUnitRequested.store(false, std::memory_order_release);
    g_requiresReinit.store(false, std::memory_order_release);
    gSoftwareVolume.store(kDefaultDevicePolicy.safeInitialVolumeLinear, std::memory_order_release);
    {
        std::lock_guard<std::mutex> lock(gRegistryMtx);
        for (auto* h : gLiveHandles) {
            if (h) h->softwareVolume.store(kDefaultDevicePolicy.safeInitialVolumeLinear, std::memory_order_relaxed);
        }
    }
    g_hardwareVolumeValidated.store(false, std::memory_order_release);
    g_hardwareVolumeSafe.store(false, std::memory_order_release);
    g_hasLastRequestedHardwareVolumeRaw.store(false, std::memory_order_release);
    g_lastRequestedHardwareVolumeRaw.store(0, std::memory_order_release);
    LOGI("USB policy reset for new device: exclusive=0 bitPerfect=0 hwFeatureUnitRequested=0 volume=%.2f",
         kDefaultDevicePolicy.safeInitialVolumeLinear);
}

// ==========================
// JNI: nativeSetUsbExclusiveActive
// 只有独占模式开启后才允许 bit-perfect
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetUsbExclusiveActive(
        JNIEnv *env, jobject thiz, jboolean active) {
    std::lock_guard<std::recursive_mutex> sessionPolicyLock(gNextSessionPolicyMtx);
    bool v = active == JNI_TRUE;
    bool old = g_usbExclusiveActive.exchange(v, std::memory_order_acq_rel);
    LOGI("nativeSetUsbExclusiveActive: %d", v ? 1 : 0);
    {
        std::lock_guard<std::mutex> lk(gRegistryMtx);
        for (auto* h : gLiveHandles) {
            if (h && h->backgroundGuardian) {
                h->backgroundGuardian->notifyStateChanged("exclusive_flag_changed");
            }
        }
    }
    if (!v) {
        // 一旦退出独占，立刻关闭 bit-perfect
        g_bitPerfectEnabled.store(false, std::memory_order_release);
        g_usbBackgroundPlaybackActive.store(false, std::memory_order_release);
        g_requiresReinit.store(true, std::memory_order_release);
        LOGW("USB exclusive disabled: bit-perfect forced OFF");
    } else if (old != v) {
        g_requiresReinit.store(true, std::memory_order_release);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetAndroidAudioSchedulerProfile(
        JNIEnv*, jobject, jint sampleRate, jint framesPerBuffer) {
    const int sr = std::clamp(static_cast<int>(sampleRate), 44100, 192000);
    const int frames = std::clamp(static_cast<int>(framesPerBuffer), 64, 4096);
    g_androidSchedulerSampleRate.store(sr, std::memory_order_release);
    g_androidSchedulerFramesPerBuffer.store(frames, std::memory_order_release);
    LOGI("nativeSetAndroidAudioSchedulerProfile sr=%d frames=%d", sr, frames);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetBackgroundPlaybackActive(
        JNIEnv*, jobject, jboolean active) {
    const bool v = active == JNI_TRUE;
    const bool old = g_usbBackgroundPlaybackActive.exchange(v, std::memory_order_acq_rel);
    if (old != v) {
        LOGI("nativeSetBackgroundPlaybackActive: %d maxTransfers=%d",
             v ? 1 : 0, NUM_TRANSFERS);
        std::lock_guard<std::mutex> lk(gRegistryMtx);
        for (auto* h : gLiveHandles) {
            if (h && h->backgroundGuardian) {
                h->backgroundGuardian->notifyStateChanged(
                        v ? "background_playback_enabled" : "background_playback_disabled");
            }
        }
    }
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativePumpUsbEventsFromKeepAlive(
        JNIEnv*, jobject) {
    // The libusb event loop is deliberately single-owner. Java keepalive keeps
    // the service and wake locks alive but must never compete for libusb's lock.
    return 0;
}

// ==========================
// JNI: nativeCanControlVolume (handle-based)
// UI 用于判断是否需要显示“拦截硬件音量键”
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeCanControlVolume(
        JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return JNI_FALSE;
    // Allow both ExclusiveProcessedHwVol and ExclusiveBitPerfectHwVol
    const bool can =
            (ctx->playbackMode == UsbPlaybackMode::ExclusiveProcessedHwVol ||
             ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectHwVol) &&
            ctx->hardwareVolumeEnabled &&
            ctx->hardwareVolumeSafe;
    LOGI("nativeCanControlVolume(handle=0x%llx) => %d mode=%d hwEnabled=%d hwSafe=%d",
         (unsigned long long)handle, can ? 1 : 0, (int)ctx->playbackMode,
         ctx->hardwareVolumeEnabled ? 1 : 0, ctx->hardwareVolumeSafe ? 1 : 0);
    return can ? JNI_TRUE : JNI_FALSE;
}

// ==========================
// JNI: nativeGetVolumeDb (handle-based)
// 返回当前硬件音量的分贝（GET_CUR 读取
// ==========================
extern "C"
JNIEXPORT jfloat JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetVolumeDb(
        JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return 0.0f;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return 0.0f;
    if (!ctx->hardwareVolumeSafe || !ctx->hardwareVolumeEnabled) return 0.0f;

    int16_t cur = 0;
    if (readHardwareCurrentRawForPath(ctx, &cur)) {
        ctx->lastHardwareVolumeRaw.store(cur, std::memory_order_release);
        return cur / 256.0f;
    }

    bool hasCached = false;
    int16_t fallbackRaw = 0;
    if (g_hasLastRequestedHardwareVolumeRaw.load(std::memory_order_acquire)) {
        fallbackRaw = g_lastRequestedHardwareVolumeRaw.load(std::memory_order_acquire);
        hasCached = true;
    } else {
        const int16_t cachedRaw = ctx->lastHardwareVolumeRaw.load(std::memory_order_acquire);
        if (cachedRaw != 0) {
            fallbackRaw = cachedRaw;
            hasCached = true;
        }
    }

    if (hasCached) {
        fallbackRaw = std::clamp<int16_t>(
                fallbackRaw, ctx->deviceVolMinRaw, ctx->deviceVolMaxRaw);
        LOGW("nativeGetVolumeDb fallback: GET_CUR unavailable, using cached raw=%d db=%.2f",
             fallbackRaw, fallbackRaw / 256.0f);
        return fallbackRaw / 256.0f;
    }

    return 0.0f;
}

// ==========================
// JNI: nativeGetHardwareVolumePolicyString
// Compact diagnostic string for UI/reporting.
// ==========================
extern "C"
JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetHardwareVolumePolicyString(
        JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return env->NewStringUTF("no-handle");
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return env->NewStringUTF("invalid-handle");
    rawsmusic::usb::RawUsbVolumePolicySnapshot snapshot;
    snapshot.state = featureUnitPolicyStateName(ctx->featureUnitPolicyState);
    snapshot.path = ctx->featureUnitVolumePathName
                    ? ctx->featureUnitVolumePathName
                    : "none";
    snapshot.reason = ctx->featureUnitPolicyReason
                      ? ctx->featureUnitPolicyReason
                      : "unknown";
    snapshot.result = ctx->featureUnitValidationResult;
    snapshot.requested = ctx->hardwareFeatureUnitRequested;
    snapshot.present = ctx->featureUnitPresent;
    snapshot.capable = ctx->hardwareVolumeCapable;
    snapshot.safe = ctx->hardwareVolumeSafe;
    snapshot.enabled = ctx->hardwareVolumeEnabled;
    snapshot.rangeVerified = ctx->featureUnitRangeVerified;
    snapshot.readbackVerified = ctx->featureUnitReadbackVerified;
    snapshot.playbackMode = static_cast<int>(ctx->playbackMode);
    snapshot.descriptorMaster = ctx->descriptorHasMasterVolume;
    snapshot.descriptorLeft = ctx->descriptorHasLeftVolume;
    snapshot.descriptorRight = ctx->descriptorHasRightVolume;
    snapshot.effectiveMaster = ctx->hasMasterVolume;
    snapshot.effectiveLeft = ctx->hasLeftVolume;
    snapshot.effectiveRight = ctx->hasRightVolume;
    snapshot.singleChannel = ctx->featureUnitSingleVolumeChannel;
    const std::string policy = rawsmusic::usb::formatRawUsbVolumePolicy(snapshot);
    return env->NewStringUTF(policy.c_str());
}

// ==========================
// JNI: nativeValidateHardwareVolume (handle-based)
// 用于“手动打开硬件音量”的按钮，或在策略切换时自动触发
// 它只执行 GET_RANGE/GET_CUR 的只读发现；返回 0 表示控制器路径可用。
// 初始 SET_CUR 由 Kotlin 在 ISO 启动前按物理 DAC 会话只执行一次。
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeValidateHardwareVolume(
        JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return -1;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return -1;
    // Read-only controller discovery; safe to repeat without changing DAC volume.
    int rc = validateHardwareVolume(ctx);
    if (rc == 0) {
        ctx->hardwareFeatureUnitEnabled = true;
        ctx->hardwareVolumeEnabled      = true;
        ctx->hardwareVolumeSafe        = true;
        LOGI("Hardware volume validated OK (FU=0x%02X policy=%s path=%s)",
             ctx->playbackFeatureUnitId,
             featureUnitPolicyStateName(ctx->featureUnitPolicyState),
             ctx->featureUnitVolumePathName ? ctx->featureUnitVolumePathName : "none");
    } else {
        ctx->hardwareFeatureUnitEnabled = false;
        ctx->hardwareVolumeEnabled      = false;
        ctx->hardwareVolumeSafe         = false;
        LOGW("Hardware volume validation failed -> rc=%d", rc);
    }
    return rc;
}

// ==========================
// JNI: nativeIsHardwareVolumeSafe
// 查询上次验证结果
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeIsHardwareVolumeSafe(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) return JNI_FALSE;
    return ctx->hardwareVolumeSafe ? JNI_TRUE : JNI_FALSE;
}

// ==========================
// JNI: nativeRepairHardwareVolumeBalance
// 修复被写乱的 DAC 左右音量：先临时验证能力，再把安全音量写到 master/L/R，并读回验证
// 返回值：0=修复成功，-1=ctx 无效，-2=Feature Unit 不可用，-3=L/R 仍不一致，-4=无可用通道
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeRepairHardwareVolumeBalance(
        JNIEnv *env, jobject thiz, jfloat safeVolume) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) {
        LOGW("repairHardwareVolumeBalance: no live handle");
        return -1;
    }
    if (!ctx || !ctx->devHandle) {
        LOGW("repairHardwareVolumeBalance: no ctx");
        return -1;
    }
    if (!ctx->featureUnitPresent) {
        LOGW("repairHardwareVolumeBalance: no Feature Unit");
        return -2;
    }

    // 临时验证能力
    int r = validateHardwareVolume(ctx);
    if (r != 0) {
        LOGW("repairHardwareVolumeBalance: validate failed r=%d", r);
        return r;
    }

    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);

    float v = safeVolume;
    if (v < 0.05f) v = 0.05f;
    if (v > 0.30f) v = 0.30f;
    int16_t raw = usbHardwareVolumeLinearToRaw(hardwareVolumeView(ctx), v);

    int ok = 0;
    if (ctx->hasMasterVolume) {
        if (uac2SetCurVolume(ctx, 0, raw) == 0) ok++;
    }
    if (ctx->hasLeftVolume && ctx->hasRightVolume) {
        int l = uac2SetCurVolume(ctx, 1, raw);
        int rr = uac2SetCurVolume(ctx, 2, raw);
        if (l == 0 && rr == 0) ok++;
    }

    usleep(30000); // DAC 处理

    int16_t curL = 0, curR = 0;
    int gl = ctx->hasLeftVolume ? uac2GetCurVolume(ctx, 1, &curL) : -1;
    int gr = ctx->hasRightVolume ? uac2GetCurVolume(ctx, 2, &curR) : -1;
    if (gl == 0 && gr == 0) {
        int diff = std::abs((int)curL - (int)curR);
        if (diff > 2) {
            LOGE("repairHardwareVolumeBalance failed: L/R mismatch curL=%d curR=%d", curL, curR);
            return -3;
        }
    }

    LOGW("repairHardwareVolumeBalance done: volume=%.3f raw=%d ok=%d",
         v, raw, ok);
    return ok > 0 ? 0 : -4;
}

// ==========================
// JNI: nativeGetPlaybackMode
// 返回当前 UsbPlaybackMode 枚举
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetPlaybackMode(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    UsbAudioContext *ctx = firstLiveHandleNoLock();
    if (!ctx) {
        return (jint)UsbPlaybackMode::SafeSoftwareVolume;
    }
    return (jint)ctx->playbackMode;
}

// ==========================
// JNI: nativeInitUsbDevice（新架构
// Java openDevice fd，native 统一 claim + set_alt
// ==========================

static bool shouldProbeOrWriteFeatureUnit(UsbAudioContext* ctx) {
    if (!ctx) return false;
    if (ctx->policyForceNoControlIface) return false;
    if (!ctx->usbExclusiveActive) return false;
    // Hardware volume is allowed in both processed-exclusive and bit-perfect.
    // policyForceSoftwareVolume is only the default-safe path; it must not block
    // an explicit user hardware-volume request. Only forceDisableFeatureUnit does.
    if (!ctx->hardwareFeatureUnitRequested) return false;
    return true;
}

static UsbPlaybackMode decidePlaybackMode(UsbAudioContext* ctx) {
    if (!ctx) {
        return UsbPlaybackMode::SafeSoftwareVolume;
    }
    return decideUsbPlaybackMode({
            .usbExclusiveActive = ctx->usbExclusiveActive,
            .bitPerfectEnabled = ctx->bitPerfectEnabled,
            .dsdTransportRequested = ctx->dsdSession,
            .hardwareFeatureUnitRequested = ctx->hardwareFeatureUnitRequested,
            .hardwareVolumeEnabled = ctx->hardwareVolumeEnabled,
            .hardwareVolumeSafe = ctx->hardwareVolumeSafe,
    });
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeInitUsbDevice(
        JNIEnv *env, jobject thiz,
        jint fd, jint sampleRate, jint sourceSampleRate, jint sourceBitsPerSample,
        jint channels, jint bitsPerSample,
        jint iface, jint alt, jint outEndpoint, jint feedbackEndpoint,
        jint subslotSize

) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::recursive_mutex> sessionPolicyLock(gNextSessionPolicyMtx);
    std::unique_lock<std::shared_mutex> lifecycleWriteLock(gUsbLifecycleMtx);
    const UsbSessionRequest sessionRequest = snapshotUsbSessionRequestFromGlobals();
    {
        std::lock_guard<std::mutex> lastDiagLock(gLastPcmInputDiagMtx);
        gLastPcmInputDiagReady = false;
        gLastPcmInputDiagSnapshot = rawsmusic::usb::RawUsbStatsSnapshot{};
    }
    LOGI("nativeInitUsbDevice: fd=%d deviceSr=%d sourceSr=%d sourceBits=%d ch=%d deviceBits=%d iface=%d alt=%d outEp=0x%02X fbEp=0x%02X subslot=%d",
         fd, sampleRate, sourceSampleRate, sourceBitsPerSample, channels, bitsPerSample, iface, alt,
         outEndpoint, feedbackEndpoint, subslotSize);

    raw_usb_crash_guard_begin("nativeInitUsbDevice");

    // 重置 DSD/DoP 诊断计数器，确保 reinit 后诊断日志可
    resetDsdDiagnostics();

    if (fd < 0) {
        LOGE("nativeInitUsbDevice failed: invalid fd=%d", fd);
        return 0;
    }

    // 关键检查：fcntl 验证 fd 是否有效
    int flags = fcntl(fd, F_GETFD);
    if (flags < 0) {
        LOGE("nativeInitUsbDevice failed: fd=%d is invalid, errno=%d %s", fd, errno, strerror(errno));
        return 0;
    }

    if (iface < 0 || alt < 0) {
        LOGE("nativeInitUsbDevice failed: invalid iface/alt iface=%d alt=%d", iface, alt);
        return 0;
    }

    if (sampleRate <= 0 || sourceSampleRate <= 0 || sourceBitsPerSample <= 0 ||
        channels <= 0 || bitsPerSample <= 0 || subslotSize <= 0) {
        LOGE("nativeInitUsbDevice failed: invalid format deviceSr=%d sourceSr=%d sourceBits=%d ch=%d deviceBits=%d subslot=%d",
             sampleRate, sourceSampleRate, sourceBitsPerSample, channels, bitsPerSample, subslotSize);
        return 0;
    }

    const size_t reapedQuarantineCount = reapSafeQuarantinedHandles();
    if (reapedQuarantineCount > 0) {
        LOGW("nativeInitUsbDevice: safely reaped %zu drained quarantined USB session(s)",
             reapedQuarantineCount);
    }
    if (hasQuarantinedHandles()) {
        LOGE("nativeInitUsbDevice rejected: a quarantined USB session still has pending kernel callbacks; "
             "keeping the process safe until those callbacks drain");
        raw_usb_crash_guard_quarantine(
                "nativeInitUsbDevice", "quarantined predecessor blocks reopen");
        return 0;
    }

    std::vector<UsbAudioContext*> oldHandles;
    {
        std::lock_guard<std::mutex> lk(gRegistryMtx);
        oldHandles = gHandleRegistry.snapshotLiveHandlesNoLock();
        for (auto* oldCtx : oldHandles) {
            if (!oldCtx) continue;
            oldCtx->beginClosing();
            invalidatePublicTokenForContextLocked(oldCtx);
        }
        gHandleRegistry.clearLiveHandlesNoLock();
    }

    for (auto* oldCtx : oldHandles) {
        if (!oldCtx) continue;
        LOGW("nativeInitUsbDevice: safely closing predecessor context %p", oldCtx);
        std::lock_guard<std::mutex> oldHandleLock(oldCtx->handleMutex);
        if (!cleanupUsbHandle(oldCtx)) {
            raw_usb_crash_guard_quarantine(
                    "nativeInitUsbDevice", "predecessor not fully drained");
            return 0;
        }
        {
            std::lock_guard<std::mutex> registryLock(gRegistryMtx);
            removeTokenForContextLocked(oldCtx, false);
            gHandleRegistry.eraseQuarantinedHandleNoLock(oldCtx);
        }
        delete oldCtx;
    }

    auto *ctx = new UsbAudioContext();
    ctx->javaFd = fd;
    ctx->claimedInJava = false;  // 新架构：native 统一管理
    ctx->claimDoneByNative = false;
    rawsmusic::usb::UsbGuardianHooks guardianHooks{};
    guardianHooks.snapshot = backgroundGuardianSnapshot;
    guardianHooks.pumpEventsOnce = backgroundGuardianPumpEvents;
    guardianHooks.recoverTransfers = backgroundGuardianRecoverTransfers;
    ctx->backgroundGuardian =
        std::make_unique<rawsmusic::usb::UsbNativeBackgroundGuardian>(ctx, guardianHooks);

    // Commit the immutable transaction into the live context. From this point onward the session
    // owns its transport policy; process-global next-session flags are not authoritative.
    ctx->sessionRequest = sessionRequest;
    ctx->usbExclusiveActive = sessionRequest.exclusive;
    ctx->bitPerfectEnabled = sessionRequest.bitPerfect;
    ctx->hardwareFeatureUnitRequested = sessionRequest.hardwareVolumeRequested;
    ctx->exclusiveActive = ctx->usbExclusiveActive;

    if (ctx->bitPerfectEnabled && !ctx->usbExclusiveActive) {
        LOGE("nativeInitUsbDevice rejected: bit-perfect requires USB exclusive mode");
        delete ctx;
        return 0;
    }

    LOGI("Playback policy snapshot: exclusive=%d bitPerfect=%d hwFeatureUnitRequested=%d",
         ctx->usbExclusiveActive ? 1 : 0,
         ctx->bitPerfectEnabled ? 1 : 0,
         ctx->hardwareFeatureUnitRequested ? 1 : 0);

    // Java decoder input format. USB PCM transport is capped at 32-bit S32LE.
    // Files reported as 64-bit must be decoded/down-converted before nativeWrite.
    const bool rawDsdSourceInput = sourceBitsPerSample == 1;
    const DsdTransportPlan dsdPlan = makeDsdTransportPlan(
            sessionRequest.dsdConversionEnabled,
            rawDsdSourceInput,
            sessionRequest.dsdDoPEnabled,
            sessionRequest.dsdRate,
            sourceSampleRate);
    const int normalizedSourceBits = rawDsdSourceInput ? 1 : (sourceBitsPerSample > 32 ? 32 : sourceBitsPerSample);
    if (sourceBitsPerSample > 32) {
        LOGW("nativeInitUsbDevice: sourceBits=%d is not a USB PCM transport format; "
             "treat decoder input as S32LE and require upstream conversion",
             sourceBitsPerSample);
    }
    if (bitsPerSample > 32 || subslotSize > 4) {
        LOGW("nativeInitUsbDevice: requested device format %dbit/subslot%d exceeds PCM32; clamp to 32bit/subslot4",
             bitsPerSample, subslotSize);
        bitsPerSample = 32;
        subslotSize = 4;
    }

    // DSD transport format is selected by the native engine, not by the Java
    // PCM hint. Native DSD uses UAC2 RAW_DATA in a 32-bit container at
    // DSD_rate/32. DoP, when explicitly enabled in the future, uses a 24-bit
    // PCM wrapper at DSD_rate/16. For realtime conversion we force Native DSD,
    // so make the requested stream profile match the actual transport before
    // descriptor scoring.
    if (dsdPlan.active) {
        const int oldSampleRate = sampleRate;
        const int oldBits = bitsPerSample;
        const int oldSubslot = subslotSize;
        sampleRate = static_cast<int>(dsdPlan.carrierRateHz);
        bitsPerSample = dsdPlan.transportBits;
        subslotSize = dsdPlan.subslotSize;
        LOGI("DSD_TRANSPORT_PLAN kind=%s sourceRaw=%d rate=DSD%d dsdHz=%u carrierHz=%u "
             "bits=%d subslot=%d bypassPcmResampler=%d",
             dsdTransportKindName(dsdPlan.kind), dsdPlan.sourceRaw ? 1 : 0,
             dsdPlan.rateMultiplier, dsdPlan.dsdRateHz, dsdPlan.carrierRateHz,
             dsdPlan.transportBits, dsdPlan.subslotSize,
             dsdTransportLocksPcmSampleRate(dsdPlan) ? 1 : 0);
        LOGI("nativeInitUsbDevice: DSD transport overrides Java format %dHz/%dbit/subslot%d -> %s carrier %dHz/%dbit/subslot%d (DSD%d)",
             oldSampleRate, oldBits, oldSubslot,
             dsdTransportKindName(dsdPlan.kind),
             sampleRate, bitsPerSample, subslotSize, dsdPlan.rateMultiplier);
    }

    ctx->sampleRate = sampleRate;
    ctx->clock.requestedSampleRate = sampleRate;  // 记录 Java 层请求的设备采样率
    ctx->sourceSampleRate = sourceSampleRate;
    ctx->sourceChannels = channels;
    ctx->sourceBitDepth = rawDsdSourceInput ? 1 : ((normalizedSourceBits > 16) ? 32 : normalizedSourceBits);
    ctx->sourceBytesPerSample = rawDsdSourceInput ? 1 : ((normalizedSourceBits > 16) ? 4 : 2);
    ctx->sourceBytesPerFrame = channels * ctx->sourceBytesPerSample;
    ctx->channels = channels;
    ctx->bitDepth = bitsPerSample;
    ctx->bytesPerSample = ctx->sourceBytesPerSample;
    ctx->bytesPerFrame = ctx->sourceBytesPerFrame;
    ctx->interfaceNumber = iface;
    ctx->altSetting = alt;
    ctx->epAddress = static_cast<uint8_t>(outEndpoint & 0xFF);
    ctx->feedbackEpAddress = static_cast<uint8_t>(feedbackEndpoint & 0xFF);

    int frameSize = channels * subslotSize;
    LOGI("nativeInitUsbDevice input calc: frameSize=%d requestedSr=%d sourceSr=%d",
         frameSize, sampleRate, sourceSampleRate);

    // dup fd，native 使用自己fd 副本
    ctx->dupFd = dup(fd);
    if (ctx->dupFd < 0) {
        LOGE("dup(fd=%d) failed: errno=%d %s", fd, errno, strerror(errno));
        delete ctx;
        return 0;
    }
    LOGI("dup fd ok: javaFd=%d dupFd=%d", fd, ctx->dupFd);

    // Android 推荐：禁用 libusb 自己扫描 /dev/bus/usb
#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000106)
    int opt = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY, nullptr);
    if (opt != LIBUSB_SUCCESS) {
        LOGW("libusb_set_option(NO_DEVICE_DISCOVERY) before init failed: %s", libusb_strerror(opt));
    } else {
        LOGI("libusb option NO_DEVICE_DISCOVERY set");
    }
#endif

    int r = libusb_init(&ctx->libusbCtx);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_init failed: %s", libusb_strerror(r));
        close(ctx->dupFd);
        delete ctx;
        return 0;
    }
    libusb_set_option(ctx->libusbCtx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_INFO);

    // 注册热插拔回调（主动监听设备状态）
#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000105)
    if (libusb_has_capability(LIBUSB_CAP_HAS_HOTPLUG)) {
        int hotplugRet = libusb_hotplug_register_callback(
                ctx->libusbCtx,
                static_cast<libusb_hotplug_event>(LIBUSB_HOTPLUG_EVENT_DEVICE_LEFT),
                LIBUSB_HOTPLUG_ENUMERATE,
                LIBUSB_HOTPLUG_MATCH_ANY,
                LIBUSB_HOTPLUG_MATCH_ANY,
                LIBUSB_HOTPLUG_MATCH_ANY,
                [](libusb_context *ctx, libusb_device *device, libusb_hotplug_event event, void *user_data) -> int {
                    auto *usbCtx = static_cast<UsbAudioContext*>(user_data);
                    LOGW("HOTPLUG: Device removed! Marking transport lost.");
                    markUsbTransportLost(usbCtx, "hotplug device removed", -1, LIBUSB_ERROR_NO_DEVICE);
                    return 0;
                },
                ctx,
                &ctx->hotplugHandle
        );
        if (hotplugRet == LIBUSB_SUCCESS) {
            ctx->hotplugRegistered = true;
            LOGI("Hotplug callback registered successfully");
        } else {
            LOGW("Hotplug callback registration failed: %s", libusb_strerror(hotplugRet));
        }
    } else {
        LOGI("Hotplug not supported by this libusb build");
    }
#endif

    // 使用 dupFd 包装设备
    r = libusb_wrap_sys_device(ctx->libusbCtx, static_cast<intptr_t>(ctx->dupFd), &ctx->devHandle);
    if (r != LIBUSB_SUCCESS || ctx->devHandle == nullptr) {
        LOGE("libusb_wrap_sys_device failed: r=%d %s, javaFd=%d dupFd=%d",
             r, libusb_strerror(r), ctx->javaFd, ctx->dupFd);
        libusb_exit(ctx->libusbCtx);
        close(ctx->dupFd);
        delete ctx;
        return 0;
    }
    LOGI("libusb_wrap_sys_device ok: devHandle=%p dupFd=%d", ctx->devHandle, ctx->dupFd);

    // 获取设备描述
    libusb_device *dev = libusb_get_device(ctx->devHandle);
    struct libusb_device_descriptor devDesc;
    r = libusb_get_device_descriptor(dev, &devDesc);
    if (r < 0) {
        LOGE("libusb_get_device_descriptor failed: %s", libusb_strerror(r));
        libusb_close(ctx->devHandle);
        libusb_exit(ctx->libusbCtx);
        close(ctx->dupFd);
        delete ctx;
        return 0;
    }
    LOGI("USB device: VID=%04X PID=%04X bcdUSB=0x%04X", devDesc.idVendor, devDesc.idProduct, devDesc.bcdUSB);
    ctx->vendorId = devDesc.idVendor;
    ctx->productId = devDesc.idProduct;

    // 应用设备策略
    UsbDevicePolicy policy = getPolicyForDevice(devDesc.idVendor, devDesc.idProduct);
    LOGI("Device policy: VID=%04X PID=%04X reason=%s forceSw=%d noCI=%d skipClock=%d ignoreClock=%d noFU=%d noFb=%d",
         devDesc.idVendor, devDesc.idProduct,
         policy.reason ? policy.reason : "none",
         policy.forceSoftwareVolume ? 1 : 0,
         policy.forceNoControlIface ? 1 : 0,
         policy.skipClockConfig ? 1 : 0,
         policy.ignoreClockControl ? 1 : 0,
         policy.forceDisableFeatureUnit ? 1 : 0,
         policy.ignoreFeedbackEndpoint ? 1 : 0);
    const bool compatNoFeatureUnit = sessionRequest.noFeatureUnit;
    const bool forceDisableFeatureUnit = policy.forceDisableFeatureUnit || compatNoFeatureUnit;
    if (forceDisableFeatureUnit) {
        ctx->hardwareFeatureUnitRequested = false;
        ctx->hardwareVolumeEnabled = false;
        ctx->hardwareVolumeSafe = false;
        LOGW("Feature Unit runtime volume disabled: policy=%d compat=%d VID=%04X PID=%04X",
             policy.forceDisableFeatureUnit ? 1 : 0,
             compatNoFeatureUnit ? 1 : 0,
             devDesc.idVendor, devDesc.idProduct);
    }
    // 策略驱动特调：复制标志到 context
    ctx->policyForceNoControlIface = policy.forceNoControlIface;
    ctx->policyForceSoftwareVolume = policy.forceSoftwareVolume;
    ctx->policySkipClockConfig = policy.skipClockConfig;
    ctx->policyIgnoreClockControl = policy.ignoreClockControl;
    ctx->policyIgnoreFeedbackEndpoint = policy.ignoreFeedbackEndpoint;
    if (forceDisableFeatureUnit) {
        ctx->hardwareFeatureUnitRequested = false;
        ctx->hardwareVolumeEnabled = false;
        ctx->hardwareVolumeSafe = false;
        ctx->hardwareVolumeCapable = false;
        ctx->featureUnitAvailable = false;
        LOGW("Feature Unit runtime volume disabled before validation");
    } else if (policy.forceSoftwareVolume) {
        const bool userRequestedHwVol =
                ctx->usbExclusiveActive &&
                ctx->hardwareFeatureUnitRequested;
        if (!userRequestedHwVol) {
            ctx->hardwareVolumeEnabled = false;
            ctx->hardwareVolumeSafe = false;
            LOGI("Device policy: default software volume, Feature Unit not probed");
        } else {
            LOGW("Default software policy, but user requested hardware volume; validating Feature Unit");
        }
    }
    if (policy.skipClockConfig) {
        LOGI("Device policy: skipClockConfig=1 锟?will skip UAC2 SET_CUR clock configuration");
    }
    if (policy.ignoreClockControl) {
        LOGI("Device policy: ignoreClockControl=1 锟?will skip all clock control (SET_CUR/GET_CUR/GET_RANGE)");
    }
    if (policy.forceNoControlIface) {
        LOGI("Device policy: forceNoControlIface=1 锟?will skip AC interface claim entirely");
    }

    // 主动尝试从内核驱动分离所有 interface
    // instead of relying solely on libusb auto-detach.
    detachAllExistingInterfaces(ctx, dev);

    // 自动分离内核驱动
#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000102)
    libusb_set_auto_detach_kernel_driver(ctx->devHandle, 1);
#endif

    // 新架构：native 统一 claim + set_alt
    // AudioControl interface is not always 0. Read descriptors first, then claim the real AC iface.
    int acIface = -1;
    int asIface = ctx->interfaceNumber;
    ctx->acInterfaceNumber = -1;
    ctx->acInterfaceClaimed = false;

    // 不要直接 claim Java hint 指定的 AudioStreaming interface
    // before descriptor scan.  On Xiaomi/HyperOS, Android may still be unwinding
    // its shared USB audio route during attach/permission cutover; claiming the
    // hinted AS interface too early makes us bounce alt settings and ownership
    // twice before we even know the final selected stream profile.
    LOGI("Deferring AS interface claim until after descriptor scan and final stream selection (hint iface=%d alt=%d ep=0x%02X)",
         asIface, ctx->altSetting, ctx->epAddress);

    // 检查 USB 速度
    int speed = libusb_get_device_speed(dev);
    switch (speed) {
        case LIBUSB_SPEED_LOW:
        case LIBUSB_SPEED_FULL:
            ctx->isFullSpeed = true;
            LOGI("USB speed: Full-Speed (speed=%d)", speed);
            break;
        case LIBUSB_SPEED_HIGH:
        case LIBUSB_SPEED_SUPER:
        case LIBUSB_SPEED_SUPER_PLUS:
            ctx->isFullSpeed = false;
            LOGI("USB speed: High/Super Speed (speed=%d)", speed);
            break;
        default:
            ctx->isFullSpeed = (devDesc.bcdUSB < 0x0200);
            LOGW("libusb_get_device_speed unknown (%d), guessing from bcdUSB: %s",
                 speed, ctx->isFullSpeed ? "Full-Speed" : "High-Speed");
            break;
    }

    // 读取配置描述符，查找最佳 AudioStreaming altsetting
    uint8_t cfgHeader[9];
    r = libusb_get_descriptor(ctx->devHandle, LIBUSB_DT_CONFIG, 0, cfgHeader, sizeof(cfgHeader));
    if (r < 0) {
        LOGE("libusb_get_descriptor(header) failed: %s", libusb_strerror(r));
        if (ctx->interfaceNumber != 0) libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
        libusb_close(ctx->devHandle);
        libusb_exit(ctx->libusbCtx);
        close(ctx->dupFd);
        delete ctx;
        return 0;
    }
    uint16_t totalLen = cfgHeader[2] | (cfgHeader[3] << 8);
    if (totalLen > 4096) totalLen = 4096;
    std::vector<uint8_t> configDesc(totalLen);
    r = libusb_get_descriptor(ctx->devHandle, LIBUSB_DT_CONFIG, 0, configDesc.data(), totalLen);
    if (r < 0) {
        LOGE("libusb_get_descriptor(full) failed: %s", libusb_strerror(r));
        if (ctx->interfaceNumber != 0) libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
        libusb_close(ctx->devHandle);
        libusb_exit(ctx->libusbCtx);
        close(ctx->dupFd);
        delete ctx;
        return 0;
    }
    totalLen = r;

    // 先解AC Topology，后面配置采样率Feature Unit 都要
    AcTopology acTopo = parseACTopology(configDesc.data(), totalLen);
    ctx->controlTopology = acTopo;
    acIface = findAudioControlInterfaceNumber(configDesc.data(), totalLen);
    ctx->acInterfaceNumber = acIface;
    if (policy.forceNoControlIface) {
        LOGI("AC interface claim skipped by device policy (forceNoControlIface), detected iface=%d", acIface);
    } else if (acIface >= 0) {
        int rAc = libusb_claim_interface(ctx->devHandle, acIface);
        if (rAc == LIBUSB_SUCCESS) {
            ctx->acInterfaceClaimed = true;
            LOGI("libusb_claim_interface(AC iface=%d) ok", acIface);
        } else {
            ctx->acInterfaceClaimed = false;
            LOGW("libusb_claim_interface(AC iface=%d) failed: %s, continue",
                 acIface, libusb_error_name(rAc));
        }
    } else {
        LOGW("No AudioControl interface found in descriptors");
    }

    // 用评分系统查找最佳 AudioStreaming altsetting
    // DSD 输出有两条链路：
    // - DoP: 24-bit / 3-byte subslot，设备采样率 = DSD_rate / 16
    // - Native DSD: UAC2 RAW_DATA + 32-bit / 4-byte subslot，设备采样率 = DSD_rate / 32
    if (!configureRawUsbDsdRoute(
            *ctx,
            RawUsbDsdRouteConfig{
                    dsdPlan,
                    rawDsdSourceInput,
                    sourceSampleRate,
                    channels,
                    sessionRequest.dsdConversionType,
                    sessionRequest.dsdDitherEnabled,
            },
            ctx)) {
        if (ctx->interfaceNumber != 0) {
            libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
        }
        releaseAudioControlInterface(ctx);
        libusb_close(ctx->devHandle);
        libusb_exit(ctx->libusbCtx);
        close(ctx->dupFd);
        delete ctx;
        return 0;
    }
    const bool dsdActive = ctx->dsdSession;
    const bool dopActive = ctx->dsdDopTransport;
    const bool nativeDsdActive = dsdPlan.nativeRaw;
    const int dsdRate = dsdPlan.rateMultiplier;
    const uint32_t dsdRateHz = dsdPlan.dsdRateHz;
    int dopDeviceRate = 0;
    int nativeDsdDeviceRate = 0;
    if (dopActive) {
        dopDeviceRate = (int)(dsdRateHz / 16);  // DoP: 16 DSD bits per PCM frame
        LOGI("DoP requested: DSD%d requires device rate %d Hz", dsdRate, dopDeviceRate);
#if 0
        constexpr int MAX_DEVICE_RATE = 2000000;
        if (dopDeviceRate > MAX_DEVICE_RATE) {
            // 尝试 DSD64 降级: DSD64 DoP = 2822400 / 16 = 176400 Hz
            int dopDeviceRate64 = 2822400 / 16;  // = 176400
            if (dopDeviceRate64 <= MAX_DEVICE_RATE) {
                LOGW("DoP: DSD%d requires %d Hz > max %d, downgrading to DSD64 (%d Hz)",
                     dsdRate, dopDeviceRate, MAX_DEVICE_RATE, dopDeviceRate64);
                dopDeviceRate = dopDeviceRate64;
                g_dsdRate.store(64, std::memory_order_release);
            } else {
                LOGW("DoP: DSD%d requires %d Hz > max %d, DSD64 also %d Hz > max, disabling DoP",
                     dsdRate, dopDeviceRate, MAX_DEVICE_RATE, dopDeviceRate64);
                dopDeviceRate = 0;
            }
        }
#endif
        LOGI("DoP active: DSD%d, dopDeviceRate=%d Hz", sessionRequest.dsdRate, dopDeviceRate);
    } else if (nativeDsdActive) {
        nativeDsdDeviceRate = (int)(dsdRateHz / 32);
        LOGI("Native DSD requested: DSD%d requires device rate %d Hz (RAW_DATA, 32-bit container)",
             dsdRate, nativeDsdDeviceRate);
    }

    AudioStreamCandidate selected;
    std::vector<AudioStreamCandidate> allCandidates;
    bool found = parseAudioInterfaceFromConfig(
            configDesc.data(), totalLen,
            sampleRate, channels, bitsPerSample, subslotSize,
            iface, alt,
            ctx->bitPerfectEnabled,
            ctx->isFullSpeed,
            sessionRequest.force1msPacket,
            sessionRequest,
            selected,
            &allCandidates,
            dopActive,
            dopDeviceRate,
            nativeDsdActive,
            nativeDsdDeviceRate
    );
    if (found) {
        if (selected.protocol == USB_AUDIO_UAC1 &&
            selected.selectedSampleRate > 0 &&
            selected.selectedSampleRate != sampleRate) {
            if (ctx->bitPerfectEnabled || dopActive || nativeDsdActive) {
                LOGE("UAC1 selected rate fallback forbidden for exact transport: requested=%d selected=%d",
                     sampleRate, selected.selectedSampleRate);
                found = false;
            } else {
                LOGW("UAC1_RATE_FALLBACK requestedDeviceSr=%d selectedDeviceSr=%d sourceSr=%d "
                     "iface=%d alt=%d rates=%s",
                     sampleRate, selected.selectedSampleRate, sourceSampleRate,
                     selected.iface, selected.alt, rateListToString(selected).c_str());
                sampleRate = selected.selectedSampleRate;
                ctx->sampleRate = sampleRate;
            }
        }
        if (found) {
            ctx->capabilitiesJson = buildUsbCapabilitiesJson(
                    UsbCapabilitiesDevice{ctx->deviceName, ctx->vendorId, ctx->productId},
                    allCandidates);
        }
    }
    if (!found) {
        LOGE("No compatible USB Audio stream found");
        // 只释AS 接口（iface != 0），iface=0 Audio Control，下面单独释
        if (ctx->interfaceNumber != 0) {
            libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
        }
        releaseAudioControlInterface(ctx);
        libusb_close(ctx->devHandle);
        libusb_exit(ctx->libusbCtx);
        close(ctx->dupFd);
        delete ctx;
        return 0;
    }

    // UAC2: query real supported sample rates from Clock Source via GET_RANGE
    // and backfill into selected so that streamSupportsRate uses real data.
    // 策略驱动：skipClockConfig ignoreClockControl 时跳GET_RANGE
    if (selected.protocol == USB_AUDIO_UAC2 && selected.terminalLink != 0
        && !ctx->policySkipClockConfig && !ctx->policyIgnoreClockControl) {
        uint8_t acIface = 0;
        bool clockSupportsRead = false;
        bool isClockSelector = false;
        uint8_t resolvedClockSourceId = 0;
        uint8_t clockId = findClockForStreamTerminal(
                acTopo.entities,
                selected.terminalLink,
                acIface,
                clockSupportsRead,
                isClockSelector,
                resolvedClockSourceId
        );
        if (clockId != 0) {
            uint8_t finalClockSourceId = clockId;
            if (isClockSelector) {
                finalClockSourceId = resolveClockSelector(
                        ctx->devHandle,
                        acTopo.entities,
                        clockId,
                        acIface,
                        clockSupportsRead
                );
            }
            if (finalClockSourceId != 0) {
                std::vector<uint32_t> rangeRates;
                int rangeRet = uac2GetRangeSampleRates(
                        ctx->devHandle, acIface, finalClockSourceId, rangeRates
                );
                if (rangeRet == LIBUSB_SUCCESS && !rangeRates.empty()) {
                    selected.hasSampleRateList = true;
                    selected.sampleRates.clear();
                    for (uint32_t rr : rangeRates) {
                        selected.sampleRates.push_back(static_cast<int>(rr));
                    }
                    LOGI("GET_RANGE backfilled %zu real rates into selected (clock 0x%02X)",
                         selected.sampleRates.size(), finalClockSourceId);

                    // 回填所有 playback candidates 并重建 capabilities JSON
                    for (auto& c : allCandidates) {
                        if ((!c.isPCM && !c.isRawData) || c.epAddress == 0) continue;
                        if (c.channels != selected.channels) continue;
                        c.hasSampleRateList = true;
                        c.sampleRates.clear();
                        for (uint32_t rr : rangeRates) {
                            c.sampleRates.push_back(static_cast<int>(rr));
                        }
                    }
                    ctx->capabilitiesJson = buildUsbCapabilitiesJson(
                            UsbCapabilitiesDevice{ctx->deviceName, ctx->vendorId, ctx->productId},
                            allCandidates);
                    LOGI("Capabilities rebuilt after GET_RANGE: %zu rates", rangeRates.size());
                }
            }
        }
        // Re-evaluate rate support with real device data
        bool rateOk = streamSupportsRate(selected, sampleRate);
        if (ctx->bitPerfectEnabled && !rateOk) {
            LOGE("Bit-perfect rejected: selected alt does not support target rate %dHz via GET_RANGE", sampleRate);
            if (ctx->interfaceNumber != 0) libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
            releaseAudioControlInterface(ctx);
            libusb_close(ctx->devHandle);
            libusb_exit(ctx->libusbCtx);
            close(ctx->dupFd);
            delete ctx;
            return 0;
        }
    }

    // Descriptor selection may override Java hints, but keep the AS interface at alt0
    // 直到 clock commit 后再 claim。顺序是：claim 最终选中的 AS interface
    // only once -> alt0 -> bind/commit clock -> then set playback alt.  Setting the
    // non-zero alt before SET_CUR can make some DACs latch the old clock or start
    // feedback before the device clock is ready.
    const bool needSelectedAsClaim = !ctx->claimDoneByNative;
    if (needSelectedAsClaim || selected.iface != ctx->interfaceNumber || selected.alt != ctx->altSetting ||
        selected.epAddress != ctx->epAddress) {
        LOGI("Descriptor selected stream profile: iface=%d->%d alt=%d->%d ep=0x%02X->0x%02X",
             ctx->interfaceNumber, selected.iface, ctx->altSetting, selected.alt,
             ctx->epAddress, selected.epAddress);

        if (needSelectedAsClaim || selected.iface != ctx->interfaceNumber) {
            if (ctx->claimDoneByNative && ctx->interfaceNumber != 0) {
                libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
            }
            ctx->interfaceNumber = selected.iface;
            r = libusb_claim_interface(ctx->devHandle, ctx->interfaceNumber);
            if (r != LIBUSB_SUCCESS) {
                LOGE("libusb_claim_interface(iface=%d) failed on final selected claim: %s", ctx->interfaceNumber, libusb_strerror(r));
                releaseAudioControlInterface(ctx);
                libusb_close(ctx->devHandle);
                libusb_exit(ctx->libusbCtx);
                close(ctx->dupFd);
                delete ctx;
                return 0;
            }
            ctx->claimDoneByNative = true;
            LOGI("Claimed final selected AS iface=%d ok", ctx->interfaceNumber);
        }

        r = libusb_set_interface_alt_setting(ctx->devHandle, ctx->interfaceNumber, 0);
        if (r != LIBUSB_SUCCESS) {
            LOGE("Set selected AS iface=%d to alt0 before clock commit failed: %s",
                 ctx->interfaceNumber, libusb_error_name(r));
            if (ctx->claimDoneByNative && ctx->interfaceNumber >= 0) {
                libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
            }
            releaseAudioControlInterface(ctx);
            libusb_close(ctx->devHandle);
            ctx->devHandle = nullptr;
            libusb_exit(ctx->libusbCtx);
            ctx->libusbCtx = nullptr;
            close(ctx->dupFd);
            ctx->dupFd = -1;
            delete ctx;
            return 0;
        }
        LOGI("Set selected AS iface=%d to alt0 before clock commit", ctx->interfaceNumber);

        ctx->altSetting = selected.alt;
        ctx->epAddress = selected.epAddress;
    }

    ctx->protocol = selected.protocol;
    ctx->maxPacketSize = selected.maxPacketSize;
    ctx->endpointInterval = selected.bInterval;
    ctx->feedbackEpAddress = selected.feedbackEpAddress;
    ctx->feedbackDegraded = false;
    ctx->feedbackValidCount.store(0, std::memory_order_relaxed);
    ctx->feedbackInvalidCount.store(0, std::memory_order_relaxed);
    ctx->feedbackEmptyCount.store(0, std::memory_order_relaxed);
    ctx->feedbackStartupGraceUntilMs.store(0, std::memory_order_relaxed);
    ctx->feedbackAudioGateHolding.store(false, std::memory_order_relaxed);
    ctx->feedbackAudioGateReleaseLogged.store(false, std::memory_order_relaxed);
    ctx->feedbackSampleRateMilli.store(0, std::memory_order_relaxed);
    if (ctx->feedbackEpAddress != 0 &&
        (ctx->policyIgnoreFeedbackEndpoint || sessionRequest.noFeedback)) {
        LOGW("Feedback endpoint 0x%02X ignored by policy; using fixed no-feedback pacing from start",
             ctx->feedbackEpAddress);
        // This is not a runtime feedback failure. It is a fresh StreamConfig
        // decision equivalent to selecting a no-feedback alt in the runtime model. Do not
        // carry FeedbackDegradedFixed across the reprepare: the runtime would
        // report fbEp=0 while pacing still says DEGRADED, causing repeated
        // RetryWithoutFeedback loops and intermittent output.
        ctx->feedbackEpAddress = 0;
        ctx->feedbackDegraded = false;
        setFeedbackState(ctx, UsbFeedbackState::NONE, 0);
        setPacingMode(ctx, UsbPacingMode::NoFeedbackFixed, "feedback endpoint ignored by policy");
    } else {
        setFeedbackState(ctx, ctx->feedbackEpAddress != 0 ? UsbFeedbackState::DISCOVERED : UsbFeedbackState::NONE, 0);
        setPacingMode(ctx,
                      ctx->feedbackEpAddress != 0 ? UsbPacingMode::NoFeedbackFixed : UsbPacingMode::NoFeedbackFixed,
                      ctx->feedbackEpAddress != 0 ? "feedback endpoint discovered, awaiting lock" : "no feedback endpoint");
    }
    ctx->clock.uac1EndpointHasSamplingFreqControl = selected.uac1EpHasSamplingFreqControl;
    ctx->clock.uac1RateDescriptorKnown = streamHasDeclaredRate(selected);
    ctx->clock.uac1DescriptorRate = 0;
    ctx->terminalLink = selected.terminalLink;
    ctx->deviceChannels = selected.channels;
    ctx->deviceBitDepth = selected.bitResolution;
    ctx->deviceSubslotSize = selected.subslotSize;
    ctx->deviceBytesPerSample = selected.subslotSize;
    ctx->deviceBytesPerFrame = selected.channels * selected.subslotSize;
    // 选择 stream 后立即同步 runtime model
    ctx->runtimeFormat.iface = selected.iface;
    ctx->runtimeFormat.alt = selected.alt;
    ctx->runtimeFormat.channels = selected.channels;
    ctx->runtimeFormat.validBits = selected.bitResolution;
    ctx->runtimeFormat.subslotBytes = selected.subslotSize;
    ctx->runtimeFormat.frameBytes = selected.channels * selected.subslotSize;
    ctx->runtimeFormat.outEndpoint.epAddress = selected.epAddress;
    ctx->runtimeFormat.outEndpoint.maxPacketSize = selected.maxPacketSize;
    ctx->runtimeFormat.outEndpoint.bInterval = selected.bInterval;
    ctx->runtimeFormat.outEndpoint.syncType = selected.outSyncType;
    ctx->runtimeFormat.outEndpoint.usageType = selected.outUsageType;
    ctx->runtimeFormat.feedbackEndpoint.epAddress = ctx->feedbackEpAddress;
    ctx->runtimeFormat.feedbackEndpoint.maxPacketSize = ctx->feedbackEpAddress != 0 ? selected.feedbackMaxPacketSize : 0;
    ctx->runtimeFormat.feedbackEndpoint.bInterval = ctx->feedbackEpAddress != 0 ? selected.feedbackBInterval : 0;
    ctx->runtimeFormat.feedbackEndpoint.usageType = ctx->feedbackEpAddress != 0 ? selected.fbUsageType : 0;
    syncUsbRuntimeModel(ctx);
    LOGI("Runtime format synced: sr=%d ch=%d validBits=%d subslot=%d frameBytes=%d bps=%d iface=%d alt=%d",
         ctx->runtimeFormat.sampleRate, ctx->runtimeFormat.channels,
         ctx->runtimeFormat.validBits, ctx->runtimeFormat.subslotBytes,
         ctx->runtimeFormat.frameBytes, ctx->runtimeFormat.bytesPerSecond,
         ctx->runtimeFormat.iface, ctx->runtimeFormat.alt);
    if (ctx->pcmToDsdSession) {
        std::lock_guard<std::mutex> lk(ctx->dsdConverterMutex);
        LOGI("PCM_TO_DSD transport plan: ctx=%p DSD%d source=%dHz work=%uHz p2d=R%u upsample=%ux "
             "transport=%s targetSr=%d iface=%d alt=%d validBits=%d subslot=%d outEp=0x%02X fbEp=0x%02X",
             ctx, dsdRate, sourceSampleRate,
             ctx->dsdConverter ? ctx->dsdConverter->getP2dWorkRateHz() : 0u,
             ctx->dsdConverter ? ctx->dsdConverter->getP2dRatio() : 0u,
             ctx->dsdConverter ? ctx->dsdConverter->getWorkUpsampleFactor() : 0u,
             dopActive ? "DoP" : "NativeDSD32", sampleRate,
             ctx->runtimeFormat.iface, ctx->runtimeFormat.alt,
             ctx->runtimeFormat.validBits, ctx->runtimeFormat.subslotBytes,
             ctx->runtimeFormat.outEndpoint.epAddress,
             ctx->runtimeFormat.feedbackEndpoint.epAddress);
    } else if (ctx->sourceDsdSession) {
        LOGI("SOURCE_DSD transport plan: ctx=%p DSD%d transport=%s targetSr=%d iface=%d alt=%d subslot=%d",
             ctx, dsdRate, dopActive ? "DoP" : "NativeDSD32", sampleRate,
             ctx->runtimeFormat.iface, ctx->runtimeFormat.alt, ctx->runtimeFormat.subslotBytes);
    }

    // Bind playback clock to the selected terminalLink (not global first clock).
    // This ensures multi-clock / bidirectional devices use the correct clock source.
    bindPlaybackClockForTerminal(ctx, acTopo.entities, ctx->terminalLink);
    LOGI("Selected playback route: iface=%d alt=%d termLink=0x%02X outEp=0x%02X clock=0x%02X acIface=%u selector=0x%02X",
         ctx->interfaceNumber,
         ctx->altSetting,
         ctx->terminalLink,
         ctx->epAddress,
         ctx->clock.clockEntityId,
         ctx->clock.clockAcInterface,
         ctx->clock.clockSelectorId);

    // 8. 配置采样率（AC Topology 已在前面解析
    //    策略驱动
    //    - ignoreClockControl: 完全忽略时钟控制（不SET_CUR/GET_CUR/GET_RANGE），假定设备运行在请求采样率
    //    - skipClockConfig: 跳过 SET_CUR，但尝试 GET_CUR 检测实际采样率
    {
        LOGI("=== AC Topology Dump ===");
        LOGI("=== AC Topology End (%zu entities) ===", acTopo.entities.size());
        LOGI("AS playback: protocol=UAC%d asInterface=%d alt=%d terminalLink=0x%02X ep=0x%02X",
             ctx->protocol, ctx->interfaceNumber, ctx->altSetting, ctx->terminalLink, ctx->epAddress);
        if (ctx->policyIgnoreClockControl) {
            // 完全忽略时钟控制：不发送任何时钟相关 USB 请求
            // 直接假定设备运行在请求的采样率（该类设备 SET_CUR/GET_CUR/GET_RANGE 均返回 EIO）
            LOGI("Clock control completely ignored by device policy (ignoreClockControl=1), "
                 "assuming device runs at requested rate %d Hz", sampleRate);
        } else if (ctx->policySkipClockConfig) {
            // 跳过 SET_CUR，但仍要检测设备实际采样率，以便启用重采样
            // 先尝GET_CUR 读取设备当前时钟
            uint32_t deviceRate = 0;
            if (ctx->protocol == USB_AUDIO_UAC2) {
                if (ctx->clock.clockEntityId == 0) {
                    bindPlaybackClockForTerminal(ctx, acTopo.entities, ctx->terminalLink);
                }
                if (ctx->clock.clockEntityId != 0 && ctx->clock.clockFrequencyReadable) {
                    int getRet = uac2GetCurSampleRate(
                            ctx->devHandle, ctx->clock.clockAcInterface, ctx->clock.clockEntityId, &deviceRate);
                    if (getRet == LIBUSB_SUCCESS && deviceRate > 0) {
                        LOGI("GET_CUR succeeded: device clock = %u Hz", deviceRate);
                        ctx->clock.clockCommitVerifiedRate = (int)deviceRate;
                    } else {
                        LOGW("GET_CUR failed in skipClockConfig path: %s",
                             libusb_error_name(getRet));
                        deviceRate = 0;
                    }
                } else {
                    LOGW("skipClockConfig path: playback clock is not readable or not bound");
                }
            }
            // GET_CUR 失败时，查表获取已知设备时钟
            if (deviceRate == 0) {
                deviceRate = knownDeviceClockRate(ctx->vendorId, ctx->productId);
                if (deviceRate > 0) {
                    LOGI("Using known device clock rate: %u Hz (VID=%04X PID=%04X)",
                         deviceRate, ctx->vendorId, ctx->productId);
                } else {
                    LOGW("Unknown device clock rate, using requested rate %d Hz (may cause issues)",
                         sampleRate);
                    deviceRate = (uint32_t)sampleRate;
                }
            }
            // 更新采样率：如果设备实际运行在不同率，需要启用重采样
            if (deviceRate != (uint32_t)sampleRate) {
                if (ctx->dsdSession) {
                    ctx->clock.deviceSampleRate = sampleRate;
                    LOGE("DSD_TRANSPORT_CLOCK_MISMATCH keepCarrier=1 skipClockConfig "
                         "requested=%d reported=%u mode=%s dsdHz=%u carrierHz=%u",
                         sampleRate,
                         deviceRate,
                         ctx->dsdDopTransport ? "DoP" : "NativeRAW",
                         ctx->dsdRateHz,
                         ctx->dsdCarrierRateHz);
                } else {
                    LOGI("Device clock mismatch: requested=%d actual=%u, enabling resampling",
                         sampleRate, deviceRate);
                    ctx->sampleRate = (int)deviceRate;
                    // 重采样将在后initSwrContext 中自动启
                }
            } else {
                ctx->clock.deviceSampleRate = sampleRate;
                LOGI("Device clock matches requested rate: %u Hz", deviceRate);
            }
            LOGI("Clock configuration skipped by device policy (skipClockConfig=1), "
                 "device rate=%u Hz", deviceRate);
        } else {
            // DoP 模式下需要调整设备采样率
            // DoP DSD 数据打包24-bit PCM 帧中，设备需要以更高采样率运
            uint32_t targetRate = (uint32_t)sampleRate;
            const bool dopEnabled = ctx->dsdDopTransport;
            const bool dsdEnabled = ctx->dsdSession;

            if (dsdEnabled) {
                const int dsdRate = ctx->dsdRateMultiplier;
                // DSD carrier follows the PCM family: 44.1k multiples use 44.1k base,
                // 48k multiples use 48k base. This keeps DSD64 at 3.072 MHz for
                // 48/96/192k sources instead of forcing the 2.8224 MHz family.
                const uint32_t dsdDeviceRate = ctx->dsdCarrierRateHz;

                // Descriptor/alt selection already validated the exact transport rate.
                // Never mutate the requested DSD multiplier or silently downgrade the
                // active converter after the handle has been created.
                targetRate = dsdDeviceRate;
                LOGI("%s mode: configuring exact device rate from %d to %u Hz for DSD%d",
                     dopEnabled ? "DoP" : "Native DSD",
                     sampleRate, targetRate, dsdRate);
            }

            // UAC1 controls the sampling frequency on the streaming endpoint.
            // That endpoint does not exist while the interface is on alt 0, so
            // expose the selected alt before SET_CUR/GET_CUR. UAC2 keeps its
            // clock-first ordering because its clock entity lives on AC.
            int cfgRet = LIBUSB_SUCCESS;
            if (ctx->protocol == USB_AUDIO_UAC1 &&
                ctx->clock.uac1EndpointHasSamplingFreqControl) {
                cfgRet = libusb_set_interface_alt_setting(
                        ctx->devHandle, ctx->interfaceNumber, ctx->altSetting);
                if (cfgRet == LIBUSB_SUCCESS) {
                    LOGI("UAC1 rate commit exposed streaming endpoint: iface=%d alt=%d ep=0x%02X",
                         ctx->interfaceNumber, ctx->altSetting, ctx->epAddress);
                } else {
                    LOGE("UAC1 rate commit could not expose streaming endpoint: iface=%d alt=%d err=%s",
                         ctx->interfaceNumber, ctx->altSetting, libusb_error_name(cfgRet));
                }
            }
            if (ctx->protocol == USB_AUDIO_UAC1) {
                ctx->clock.uac1DescriptorRate =
                        ctx->clock.uac1RateDescriptorKnown && streamSupportsRate(selected, static_cast<int>(targetRate))
                        ? static_cast<int>(targetRate)
                        : 0;
                LOGI("UAC1_RATE_PLAN requested=%u descriptorRates=%s descriptorKnown=%d "
                     "samplingFreqControl=%d descriptorMatch=%d",
                     targetRate,
                     rateListToString(selected).c_str(),
                     ctx->clock.uac1RateDescriptorKnown ? 1 : 0,
                     ctx->clock.uac1EndpointHasSamplingFreqControl ? 1 : 0,
                     ctx->clock.uac1DescriptorRate);
            }
            if (cfgRet == LIBUSB_SUCCESS) {
                cfgRet = configureSampleRateDynamic(
                        ctx, acTopo.entities, targetRate
                );
            }
            if (cfgRet < 0) {
                if (ctx->protocol == USB_AUDIO_UAC1) {
                    LOGE("UAC1 clock commit failed: result=%d requested=%u; aborting before ISO",
                         cfgRet, targetRate);
                    if (ctx->claimDoneByNative && ctx->interfaceNumber >= 0) {
                        libusb_set_interface_alt_setting(
                                ctx->devHandle, ctx->interfaceNumber, 0);
                        libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
                    }
                    releaseAudioControlInterface(ctx);
                    libusb_close(ctx->devHandle);
                    ctx->devHandle = nullptr;
                    libusb_exit(ctx->libusbCtx);
                    ctx->libusbCtx = nullptr;
                    close(ctx->dupFd);
                    ctx->dupFd = -1;
                    delete ctx;
                    return 0;
                }
                LOGW("configureSampleRateDynamic returned: %d, continue UAC2 best-effort", cfgRet);
            } else {
                // 更新 ctx->sampleRate 以反映实际设备采样率
                // DoP 模式下，设备运行在更高的采样
                if (dsdEnabled && targetRate != (uint32_t)sampleRate) {
                    ctx->sampleRate = (int)targetRate;
                    LOGI("%s mode: ctx->sampleRate updated to %d Hz",
                         dopEnabled ? "DoP" : "Native DSD",
                         ctx->sampleRate);
                }
            }
            usleep(2000);
            // Post-commit verification uses the bound playback clock path.
            if (ctx->clock.clockEntityId != 0 && ctx->clock.clockFrequencyReadable) {
                uint32_t verifyRate = 0;
                int vRet = uac2GetCurSampleRate(
                        ctx->devHandle, ctx->clock.clockAcInterface, ctx->clock.clockEntityId, &verifyRate);
                if (vRet == LIBUSB_SUCCESS) {
                    ctx->clock.clockCommitVerifiedRate = (int)verifyRate;
                    LOGI("Post-config playback clock verify: device=%uHz target=%uHz match=%d clock=0x%02X acIface=%u",
                         verifyRate, targetRate, almostSameRate((int)verifyRate, (int)targetRate) ? 1 : 0,
                         ctx->clock.clockEntityId, ctx->clock.clockAcInterface);
                    if (dsdEnabled && !almostSameRate((int)verifyRate, (int)targetRate)) {
                        LOGW("CRITICAL: %s clock mismatch! device=%uHz expected=%uHz; DAC may not recognize the DSD transport",
                             dopEnabled ? "DoP" : "Native DSD",
                             verifyRate, targetRate);
                    }
                } else {
                    LOGW("Post-config playback clock verify GET_CUR failed: %s", libusb_error_name(vRet));
                }
            }
        }
    }

    // 9. Feature Unit 解析（仅解析，不unmute，因playbackMode 还未确定
    LOGI("AS playback: terminalLink=0x%02X", ctx->terminalLink);
    if (sessionRequest.noControlInterface) {
        LOGI("USBNoCIface enabled: skipping Feature Unit entirely");
    } else {
        selectPlaybackFeatureUnit(ctx, acTopo);
        if (ctx->featureUnitPresent) {
            LOGI("Playback FeatureUnit found: fu=0x%02X sourceId=0x%02X",
                 ctx->playbackFeatureUnitId, ctx->terminalLink);
        }
    }

    // 10. 现在设置 streaming altsetting（clock 已设置完成）
    r = libusb_set_interface_alt_setting(ctx->devHandle, ctx->interfaceNumber, ctx->altSetting);
    if (r != LIBUSB_SUCCESS) {
        LOGE("libusb_set_interface_alt_setting(%d,%d) failed: %s",
             ctx->interfaceNumber, ctx->altSetting, libusb_error_name(r));
        if (ctx->claimDoneByNative && ctx->interfaceNumber >= 0) {
            libusb_set_interface_alt_setting(ctx->devHandle, ctx->interfaceNumber, 0);
            libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
        }
        releaseAudioControlInterface(ctx);
        libusb_close(ctx->devHandle);
        ctx->devHandle = nullptr;
        libusb_exit(ctx->libusbCtx);
        ctx->libusbCtx = nullptr;
        close(ctx->dupFd);
        ctx->dupFd = -1;
        delete ctx;
        return 0;
    }
    LOGI("Set streaming alt: iface=%d alt=%d", ctx->interfaceNumber, ctx->altSetting);

    // Endpoint service interval must honor bInterval. Treating every HS DAC as
    // 8000 intervals/sec breaks devices whose endpoint is bInterval=2/3/4.
    if (ctx->endpointInterval <= 0) {
        ctx->endpointInterval = 1;
    }
    bool force1Ms = sessionRequest.force1msPacket;
    ctx->serviceIntervalsPerSecond = selected.serviceIntervalsPerSecond > 0
                                     ? selected.serviceIntervalsPerSecond
                                     : computeIsoServiceIntervalsPerSecond(
                    ctx->isFullSpeed,
                    ctx->endpointInterval,
                    force1Ms
            );
    const int physicalIntervalsPerSecond = physicalIsoServiceIntervalsPerSecond(
            ctx->isFullSpeed,
            ctx->endpointInterval
    );
    if (force1Ms && physicalIntervalsPerSecond != 1000) {
        LOGW("Force1ms ignored for runtime ISO pacing: speed=%s bInterval=%d physicalIps=%d; "
             "using descriptor cadence to avoid accelerated playback",
             ctx->isFullSpeed ? "FS" : "HS/SS",
             ctx->endpointInterval,
             physicalIntervalsPerSecond);
    }
    LOGI("ISO service interval: speed=%s bInterval=%d force1ms=%d intervalsPerSec=%d physicalIps=%d",
         ctx->isFullSpeed ? "FS" : "HS/SS",
         ctx->endpointInterval,
         force1Ms ? 1 : 0,
         ctx->serviceIntervalsPerSecond,
         physicalIntervalsPerSecond);

    // Calculate ISO transfer parameters using verified device sample rate
    const int deviceRate =
            ctx->clock.deviceSampleRate > 0
            ? ctx->clock.deviceSampleRate
            : ctx->sampleRate;
    ctx->bytesPerFrame = ctx->channels * ctx->bytesPerSample;
    ctx->bytes_per_second = (uint64_t)deviceRate * ctx->bytesPerFrame;
    ctx->clock.deviceBytesPerSecond = (int)ctx->bytes_per_second;
    syncUsbRuntimeModel(ctx);

    // Initialize ISO Pacer using verified device sample rate
    ctx->isoPacer.reset(
            (double)deviceRate,
            (uint32_t)ctx->serviceIntervalsPerSecond,
            (uint32_t)ctx->bytesPerFrame,
            ctx->maxPacketSize
    );
    if (ctx->protocol == USB_AUDIO_UAC1) {
        rawsmusic::usb::IsoPacer preview = ctx->isoPacer;
        std::string packetPreview;
        for (int i = 0; i < 16; ++i) {
            if (!packetPreview.empty()) packetPreview += ",";
            packetPreview += std::to_string(rawsmusic::usb::advanceIsoPacerPacketBytes(&preview));
        }
        LOGI("UAC1_PACKET_PREVIEW rate=%d frame=%d ips=%d maxPacket=%d first16=[%s]",
             deviceRate, ctx->bytesPerFrame, ctx->serviceIntervalsPerSecond,
             ctx->maxPacketSize, packetPreview.c_str());
    }
    ctx->nominalSampleRate = (uint32_t)deviceRate;
    ctx->bytesPerPacket = (int)(ctx->bytes_per_second / ctx->isoPacer.intervalsPerSec);
    ctx->bytesPerServiceInterval = ctx->bytesPerPacket;
    LOGI("USB timing locked: sourceSr=%d requestedSr=%d verifiedDeviceSr=%d frame=%d bps=%d",
         ctx->sourceSampleRate, ctx->clock.requestedSampleRate, deviceRate,
         ctx->bytesPerFrame, ctx->clock.deviceBytesPerSecond);

    // PCM 格式适配
    ctx->pcmAdapter = choosePcmAdapter(
            ctx->sourceChannels,
            ctx->sourceBitDepth,
            ctx->sourceBytesPerSample,
            ctx->deviceChannels,
            ctx->deviceBitDepth,
            ctx->deviceSubslotSize,
            ctx->bitPerfectEnabled
    );
    LOGI("PCM format adapter: source=%dch/%dbit/%dBps device=%dch/%dbit/subslot%d adapter=%s",
         ctx->sourceChannels, ctx->sourceBitDepth, ctx->sourceBytesPerSample,
         ctx->deviceChannels, ctx->deviceBitDepth, ctx->deviceSubslotSize,
         pcmAdapterName(ctx->pcmAdapter));

    // 初始化重采样器（源采样率 != 设备采样格式不同时启用）
    // DoP 模式下跳过：DSD 转换器内部完成上采样，不需要重采样器
    bool dsdActiveForInit = ctx->dsdSession;
    bool dopActiveForInit = dsdActiveForInit && ctx->dsdDopTransport;
    if (dsdActiveForInit) {
        LOGI("Skipping resampler init: %s active (DSD converter handles transport rate, "
             "source=%dHz -> device=%dHz)",
             dopActiveForInit ? "DoP" : "Native DSD",
             ctx->sourceSampleRate, ctx->sampleRate);
        ctx->needsResample = false;
        // DoP 诊断：打印转换器状
        {
            const bool convInit = ctx->dsdConverterInitialized.load(std::memory_order_acquire);
            const uint32_t rateMult = convInit ? static_cast<uint32_t>(ctx->dsdRateMultiplier) : 0u;
            LOGI("DSD init diag: ctx=%p mode=%s sourceDirect=%d converterInit=%d rateMult=%u "
                 "session=%d dop=%d",
                 ctx, dopActiveForInit ? "DoP" : "Native",
                 ctx->sourceDsdSession ? 1 : 0, convInit ? 1 : 0, rateMult,
                 ctx->dsdSession ? 1 : 0, ctx->dsdDopTransport ? 1 : 0);
        }
    } else {
        int swrRet = initSwrContext(ctx);
        if (swrRet < 0) {
            LOGW("initSwrContext failed: %d, resampling disabled", swrRet);
        }
    }
    const auto formatTrace = rawsmusic::usb::buildUsbFormatTrace(
            rawsmusic::usb::UsbFormatTraceInput{
                    sourceSampleRate,
                    sourceBitsPerSample,
                    channels,
                    ctx->sourceSampleRate,
                    ctx->sourceBitDepth,
                    ctx->sourceBytesPerSample,
                    ctx->clock.requestedSampleRate,
                    ctx->bitDepth,
                    ctx->deviceSubslotSize,
                    ctx->runtimeFormat.sampleRate,
                    ctx->runtimeFormat.validBits,
                    ctx->runtimeFormat.subslotBytes,
                    pcmAdapterName(ctx->pcmAdapter),
                    ctx->swrCtx != nullptr,
                    ctx->needsResample,
                    ctx->bitPerfectEnabled,
            });
    LOGI("%s", formatTrace.c_str());
    // 当重采样启用或存在 PCM 格式适配时，ring buffer 存储设备格式数据
    // 需要更新 bytesPerFrame 并重置 IsoPacer
    if (ctx->needsResample || ctx->pcmAdapter != PCM_ADAPTER_NONE || ctx->dsdSession) {
        // Every DSD route stores final USB transport frames in the ring. Source
        // DSF/DFF input is byte-interleaved 1-bit payload and must never leave
        // bytesPerFrame at the decoder's 2-byte stereo source frame.
        ctx->bytesPerFrame = ctx->deviceBytesPerFrame;
        ctx->bytes_per_second = (uint64_t)ctx->sampleRate * ctx->bytesPerFrame;
        ctx->bytesPerPacket = (int)(ctx->bytes_per_second / ctx->isoPacer.intervalsPerSec);
        ctx->bytesPerServiceInterval = ctx->bytesPerPacket;
        // 用正确的设备帧大小重IsoPacer
        ctx->isoPacer.reset(
                (double)ctx->sampleRate,
                (uint32_t)ctx->serviceIntervalsPerSecond,
                (uint32_t)ctx->bytesPerFrame,
                ctx->maxPacketSize
        );
        ctx->nominalSampleRate = (uint32_t)ctx->sampleRate;
        LOGI("Ring buffer uses device format: bytesPerFrame=%d (resample=%d adapter=%d)",
             ctx->bytesPerFrame, ctx->needsResample ? 1 : 0, (int)ctx->pcmAdapter);
        syncUsbRuntimeModel(ctx);
    }

    // Transfer buffer size: use actual packet size + margin (not USB endpoint max 776 bytes)
    // This saves massive memory: e.g. 44 bytes vs 776 bytes per packet for 44.1kHz
    int maxPktSize = ctx->maxPacketSize;
    if (maxPktSize <= 0) maxPktSize = ctx->bytesPerPacket;
    const auto transferGeometry = computeUsbTransferGeometry({
            ctx->serviceIntervalsPerSecond,
            ctx->feedbackEpAddress != 0,
    });
    ctx->numIsoPackets = transferGeometry.isoPacketsPerTransfer;
    ctx->transferSize = maxPktSize * ctx->numIsoPackets;
    const auto uac20QueueSizing = computeCurrentUac20QueueSizing(ctx);
    ctx->transferPoolTarget = std::clamp(uac20QueueSizing.transferCount, 1, NUM_TRANSFERS);
    LOGI("USB_TRANSFER_GEOMETRY ips=%d packets=%d durationUs=%d feedback=%d pool=%d queueMs=%d queueBytes=%d nominalTransferBytes=%d owner=libusb-callback",
         ctx->serviceIntervalsPerSecond,
         ctx->numIsoPackets,
         transferGeometry.transferDurationUs,
         ctx->feedbackEpAddress != 0 ? 1 : 0,
         ctx->transferPoolTarget,
         uac20QueueSizing.queueMs,
         uac20QueueSizing.queueBytes,
         uac20QueueSizing.transferBytes);

    // 分配传输缓冲
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        int bufSize = ctx->transferSize;
        ctx->transferBuffers[i] = new(std::nothrow) uint8_t[bufSize];
        if (!ctx->transferBuffers[i]) {
            LOGE("Failed to allocate transfer buffer %d (size=%d); closing partially initialized USB session",
                 i, bufSize);
            if (cleanupUsbHandle(ctx)) {
                delete ctx;
            } else {
                raw_usb_crash_guard_quarantine(
                        "nativeInitUsbDevice", "partial init cleanup not drained");
            }
            return 0;
        }
        memset(ctx->transferBuffers[i], 0, bufSize);
    }

    // 环形缓冲区（4 秒）
    size_t ringSize = (size_t)ctx->sampleRate * ctx->bytesPerFrame * 8; // 8 seconds to match increased USB buffering
    ctx->pcmRingBuffer.resize(ringSize, 0);
    ctx->pcmWritePos.store(0, std::memory_order_release);
    ctx->pcmReadPos.store(0, std::memory_order_release);

    // ===== DoP 管道状转=====
    {
        const bool dsdEnabled = ctx->dsdSession;
        const bool dopEnabled = ctx->dsdDopTransport;
        const int dsdRate = ctx->dsdRateMultiplier;
        const bool convInit = ctx->dsdConverterInitialized.load(std::memory_order_acquire);
        uint32_t rateMult = convInit ? static_cast<uint32_t>(ctx->dsdRateMultiplier) : 0u;
        uint32_t actualFactor = 0u;
        if (convInit) {
            std::lock_guard<std::mutex> lk(ctx->dsdConverterMutex);
            if (ctx->dsdConverter) actualFactor = ctx->dsdConverter->getActualUpsamplingFactor();
        }
        if (actualFactor == 0) actualFactor = rateMult;
        // 计算预期DoP marker
        uint8_t expectedMarkerA = 0, expectedMarkerB = 0;
        if (dopEnabled && convInit && rateMult > 0) {
            expectedMarkerA = DOP_MARKER_A;
            expectedMarkerB = DOP_MARKER_B;
        }
        // 计算预期DoP 设备采样
        // DSD 速率始终基于 44.1kHz，与输入采样率无
        uint32_t expectedDopRate = 0;
        if (dopEnabled && dsdEnabled && dsdRate > 0) {
            const uint32_t dsdRateHz =
                    dsdRateHzForRateAndInputRate(dsdRate, ctx->sourceSampleRate);
            expectedDopRate = dsdRateHz / 16u;
        }
        LOGI("===== DoP Pipeline State Dump =====");
        LOGI("  sourceRate=%dHz deviceRate=%dHz sourceBPF=%d deviceBPF=%d",
             ctx->sourceSampleRate, ctx->sampleRate, ctx->sourceBytesPerFrame, ctx->deviceBytesPerFrame);
        LOGI("  srcCh=%d srcBits=%d srcBPS=%d | devCh=%d devBits=%d devSubslot=%d devBPF=%d",
             ctx->sourceChannels, ctx->sourceBitDepth, ctx->sourceBytesPerSample,
             ctx->deviceChannels, ctx->deviceBitDepth, ctx->deviceSubslotSize, ctx->deviceBytesPerFrame);
        LOGI("  dsdEnabled=%d dopEnabled=%d dsdRate=%d nominal=%u actual=%u convInit=%d needsResample=%d",
             dsdEnabled ? 1 : 0, dopEnabled ? 1 : 0, dsdRate, rateMult, actualFactor, convInit ? 1 : 0,
             ctx->needsResample ? 1 : 0);
        LOGI("  expectedDopRate=%uHz vs actualDeviceRate=%dHz match=%d",
             expectedDopRate, ctx->sampleRate, (expectedDopRate == (uint32_t)ctx->sampleRate) ? 1 : 0);
        LOGI("  expectedDoPMarker: A=0x%02X B=0x%02X (DSD%d)", expectedMarkerA, expectedMarkerB, dsdRate);
        LOGI("  maxPktSize=%d bytesPerPacket=%d bytesPerFrame=%d bytesPerSec=%llu",
             ctx->maxPacketSize, ctx->bytesPerPacket, ctx->bytesPerFrame, (unsigned long long)ctx->bytes_per_second);
        {
            double pacerSr = (double)(ctx->isoPacer.sampleRateQ32 / 4294967296.0);
            LOGI("  isoPacer: frameSize=%u sampleRate=%.1f intervalsPerSec=%u",
                 ctx->isoPacer.frameSize, pacerSr, ctx->isoPacer.intervalsPerSec);
        }
        LOGI("  nominalSR=%u ringSize=%zu transferSize=%d numIsoPkts=%d",
             ctx->nominalSampleRate, ringSize, ctx->transferSize, ctx->numIsoPackets);
        LOGI("  feedbackEp=0x%02X epAddress=0x%02X altSetting=%d",
             ctx->feedbackEpAddress, ctx->epAddress, ctx->altSetting);
        // DoP 编码验证：每个源帧产生多DoP 字节（使actualFactor 而非 rateMult
        if (dopEnabled && convInit && actualFactor > 0) {
            size_t doPairs = (size_t)((actualFactor + 15) / 16);  // ceil division
            if (doPairs < 1) doPairs = 1;
            size_t dopBytesPerSrcFrame = (size_t)ctx->sourceChannels * doPairs * 3;
            size_t doFramesPerSec = (size_t)ctx->sourceSampleRate * dopBytesPerSrcFrame;
            const uint32_t dsdRateHz =
                    dsdRateHzForRateAndInputRate(dsdRate, ctx->sourceSampleRate);
            const uint32_t expectedDeviceRate = dsdRateHz / 16u;
            size_t expectedBytesPerSec = (size_t)expectedDeviceRate * ctx->deviceBytesPerFrame;
            LOGI("  DoP encoding: actualFactor=%u, doPairs=%zu, dopBytesPerSrcFrame=%zu, doFramesPerSec=%zu",
                 actualFactor, doPairs, dopBytesPerSrcFrame, doFramesPerSec);
            LOGI("  DoP verification: expected=%zu bytes/sec vs device=%llu bytes/sec (DSD%d 锟?%uHz DoP)",
                 doFramesPerSec, (unsigned long long)ctx->bytes_per_second, dsdRate, expectedDeviceRate);
        }
        LOGI("===== End DoP Pipeline State Dump =====");
    }

    // Feature Unit / hardware-volume policy is validated once below, after all
    // descriptor-derived state has been committed.  Descriptor presence alone
    // is not enough to enable hardware volume.
    ctx->hardwareVolumeSafe = false;
    ctx->hardwareVolumeEnabled = false;
    ctx->hardwareFeatureUnitEnabled = false;
    ctx->hardwareVolumeCapable = false;
    ctx->featureUnitAvailable = false;
    ctx->featureUnitHasMasterVolume = false;

    // Unified Feature Unit validation + playback mode decision.
    bool hwValidationAttempted = false;
    bool hwValidationOk = false;

    if (ctx->hardwareFeatureUnitRequested &&
        ctx->featureUnitPresent &&
        !forceDisableFeatureUnit &&
        !sessionRequest.noControlInterface) {

        hwValidationAttempted = true;
        const int volRet = validateHardwareVolume(ctx);

        if (volRet == 0) {
            setHardwareVolumeState(ctx, true, true, "validateHardwareVolume OK");
            hwValidationOk = true;
        } else {
            setHardwareVolumeState(ctx, false, false, "validateHardwareVolume failed");
        }
    } else {
        setHardwareVolumeState(ctx, false, false, "not requested or no FU or disabled");
    }

    ctx->playbackMode = decidePlaybackMode(ctx);

    LOGI(
            "Playback mode after FU validation: %d "
            "(0=SafeSw 1=ExcSw 2=ExcProcHwVol 3=ExcBPHwVol 4=ExcBPFixed) "
            "hwReq=%d hwAttempt=%d hwOk=%d hwEn=%d hwSafe=%d bp=%d",
            (int)ctx->playbackMode,
            ctx->hardwareFeatureUnitRequested ? 1 : 0,
            hwValidationAttempted ? 1 : 0,
            hwValidationOk ? 1 : 0,
            ctx->hardwareVolumeEnabled ? 1 : 0,
            ctx->hardwareVolumeSafe ? 1 : 0,
            ctx->bitPerfectEnabled ? 1 : 0
    );

    // Feature Unit repair for mute/0dB on known devices.
    // Never repair when hardware volume is requested or active.
    if (ctx->featureUnitPresent &&
        !sessionRequest.noControlInterface) {

        const bool inHwMode =
                ctx->playbackMode == UsbPlaybackMode::ExclusiveProcessedHwVol ||
                ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectHwVol;

        const bool shouldRepair =
                policy.requiresVolumeRepairOnAttach &&
                !ctx->hardwareFeatureUnitRequested &&
                !ctx->hardwareVolumeEnabled &&
                !inHwMode;

        if (!shouldRepair) {
            LOGI(
                    "Feature Unit present but left untouched: "
                    "req=%d en=%d safe=%d repair=%d hwMode=%d mode=%d",
                    ctx->hardwareFeatureUnitRequested ? 1 : 0,
                    ctx->hardwareVolumeEnabled ? 1 : 0,
                    ctx->hardwareVolumeSafe ? 1 : 0,
                    policy.requiresVolumeRepairOnAttach ? 1 : 0,
                    inHwMode ? 1 : 0,
                    (int)ctx->playbackMode
            );
        } else {
            int repairRet = bestEffortRestoreFeatureUnitUnityNoCache(
                    ctx, "attach-repair-restore-0db");
            if (repairRet < 0) {
                LOGW("Feature Unit unity repair failed: r=%d", repairRet);
            } else {
                LOGI("Feature Unit repair applied: best-effort unmute + 0dB on master/L/R");
            }
        }
    }

    // Pre-buffer
    int prebufferMs = (ctx->feedbackEpAddress != 0) ? 200 : 500;
    ctx->prebufferBytes = ctx->bytes_per_second * prebufferMs / 1000;
    LOGI("nativeInitUsbDevice: prebufferBytes=%zu (%dms) feedbackEp=0x%02X",
         ctx->prebufferBytes, prebufferMs, ctx->feedbackEpAddress);

    // 反馈端点缓冲
    ctx->feedbackBuffer = new(std::nothrow) uint8_t[kFeedbackTransferBufferBytes];
    if (!ctx->feedbackBuffer) {
        LOGE("Failed to allocate feedback buffer; closing partially initialized USB session");
        if (cleanupUsbHandle(ctx)) {
            delete ctx;
        } else {
            raw_usb_crash_guard_quarantine(
                    "nativeInitUsbDevice", "partial init cleanup not drained");
        }
        return 0;
    }
    memset(ctx->feedbackBuffer, 0, kFeedbackTransferBufferBytes);

    ctx->initialized.store(true, std::memory_order_release);
    ctx->streaming.store(false, std::memory_order_release);
    ctx->stopping.store(false, std::memory_order_release);
    ctx->stopRequested.store(false, std::memory_order_release);
    ctx->acceptingWrites.store(true, std::memory_order_release);
    g_requiresReinit.store(false, std::memory_order_release);

    // 安全核心: HID remote control disabled by default to avoid
    // EventHub conflicts and consumer-control interference on some devices.
    if (RAWS_USB_HID_ENABLED_DEFAULT) {
        ctx->hidManager = std::make_unique<rawsmusic::UsbHidManager>();
        ctx->hidEnabled = ctx->hidManager->init(ctx->libusbCtx, ctx->devHandle);
        if (ctx->hidEnabled) {
            const auto &hidEp = ctx->hidManager->getEndpointInfo();
            ctx->hidInterfaceNumber = hidEp.interfaceNumber;
            ctx->hidEndpointAddress = hidEp.endpointAddress;
            ctx->hidMaxPacketSize = hidEp.maxPacketSize;
            ctx->hidInterval = hidEp.interval;
            LOGI("USB HID ready: iface=%d ep=0x%02X maxPacket=%d interval=%d type=%d",
                 ctx->hidInterfaceNumber, ctx->hidEndpointAddress,
                 ctx->hidMaxPacketSize, ctx->hidInterval, hidEp.transferType);
        } else {
            ctx->hidManager.reset();
            LOGI("USB HID not available on this device");
        }
    } else {
        ctx->hidEnabled = false;
        LOGI("USB HID disabled (safe core default)");
    }

    const jlong publicHandleToken = registerHandle(ctx);
    if (publicHandleToken == 0) {
        LOGE("nativeInitUsbDevice failed: could not allocate public handle token");
        if (cleanupUsbHandle(ctx)) {
            delete ctx;
        } else {
            raw_usb_crash_guard_quarantine(
                    "nativeInitUsbDevice", "partial init cleanup not drained");
        }
        return 0;
    }
    startDsdWorkerIfNeeded(ctx, "native_init_ready");
    ctx->softwareVolume.store(gSoftwareVolume.load(std::memory_order_relaxed),
                              std::memory_order_relaxed);

    // ========== 设置 streamState + 赋值关键字段 ==========
    // 让写入检查在 nativeStart 之前就能通过（prefill 阶段需要）
    ctx->selectedAsInterface = ctx->interfaceNumber;
    ctx->selectedAltSetting = ctx->altSetting;
    ctx->selectedOutEndpoint = ctx->epAddress;
    ctx->selectedFeedbackEndpoint = ctx->feedbackEpAddress;

    ctx->currentSampleRate = ctx->sampleRate;
    ctx->currentChannels = ctx->deviceChannels;
    ctx->currentBits = ctx->deviceBitDepth;
    ctx->currentSubslotSize = ctx->deviceSubslotSize;

    if (ctx->claimDoneByNative) {
        ctx->asInterfaceClaimed = true;
        ctx->claimedAsInterface = ctx->interfaceNumber;
    }

    ctx->stopRequested.store(false, std::memory_order_release);
    // sessionBroken is monotonic for this native handle. Once poisoned, only
    // a fresh nativeInitUsbDevice may create a healthy session.
    ctx->fatalError.store(0, std::memory_order_release);
    ctx->inStandby.store(false, std::memory_order_release);
    ctx->acceptingWrites.store(true, std::memory_order_release);

    setUsbStreamState(ctx, UsbStreamState::PREPARED, "native_init_ready");

    LOGI("USB native ready: sr=%d ch=%d bits=%d subslot=%d frameSize=%d iface=%d alt=%d outEp=0x%02X fbEp=0x%02X",
         ctx->sampleRate, ctx->channels, ctx->bitDepth, ctx->deviceSubslotSize,
         ctx->bytesPerFrame, ctx->interfaceNumber, ctx->altSetting,
         ctx->epAddress, ctx->feedbackEpAddress);

    raw_usb_crash_guard_end("nativeInitUsbDevice");
    return publicHandleToken;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeInitHid(
        JNIEnv *env, jobject thiz
) {
    (void) thiz;
    if (!g_hidJavaVm) {
        env->GetJavaVM(&g_hidJavaVm);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetHidCallback(
        JNIEnv *env, jobject thiz, jobject callback
) {
    (void) thiz;
    if (!g_hidJavaVm) {
        env->GetJavaVM(&g_hidJavaVm);
    }

    std::lock_guard<std::mutex> lk(g_hidCallbackMutex);
    if (g_hidCallback) {
        env->DeleteGlobalRef(g_hidCallback);
        g_hidCallback = nullptr;
    }
    if (callback) {
        g_hidCallback = env->NewGlobalRef(callback);
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeStartHidListening(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env;
    (void) thiz;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx) || ctx->closing.load(std::memory_order_acquire)) {
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (!ctx->hidManager || !ctx->hidEnabled) {
        return JNI_FALSE;
    }
    if (ctx->hidManager->isListening()) {
        ctx->hidListening = true;
        return JNI_TRUE;
    }

    bool ok = ctx->hidManager->startListening([](const rawsmusic::HidKeyEvent &event) {
        dispatchHidKeyEventToJava(event);
    });
    ctx->hidListening = ok;
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeStopHidListening(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env;
    (void) thiz;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return;

    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    stopHidLocked(ctx);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeIsHidListening(
        JNIEnv *env, jobject thiz
) {
    (void) env;
    (void) thiz;
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    for (auto *ctx: gLiveHandles) {
        if (ctx && ctx->hidManager && ctx->hidManager->isListening()) {
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeHasHidInterface(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env;
    (void) thiz;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return JNI_FALSE;
    return (ctx->hidManager && ctx->hidManager->hasHidInterface()) ? JNI_TRUE : JNI_FALSE;
}

// ==========================
static void resetUsbSessionForPlayback(UsbAudioContext* ctx, bool clearRing = false, const char* reason = nullptr);

// Silent OUT preflight was removed. It submitted an asynchronous transfer backed
// by function-local state and could not prove that the cancel callback had
// returned before teardown. The committed streaming alt is now validated by
// normal ISO submission and runtime health checks only.

// JNI: nativeStart(handle)（新架构
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeStart__J(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env;
    (void) thiz;
    LOGI("nativeStart(handle) called: handle=0x%llx", (unsigned long long) handle);
    if (handle == 0) return JNI_FALSE;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);

    if (!isLiveHandle(ctx)) {
        LOGE("nativeStart(handle) failed: dead handle %p", ctx);
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lk(ctx->handleMutex);

    if (ctx->closing.load(std::memory_order_acquire)) {
        LOGE("nativeStart(handle) rejected: handle is closing %p", ctx);
        return JNI_FALSE;
    }

    if (ctx->stopRequested.load(std::memory_order_acquire)) {
        LOGW("nativeStart(handle) rejected: stopRequested, handle=%p", ctx);
        return JNI_FALSE;
    }

    if (ctx->sessionBroken.load(std::memory_order_acquire)) {
        LOGE("nativeStart(handle) denied: sessionBroken=1, handle=%p", ctx);
        return JNI_FALSE;
    }
    if (ctx->transportLost.load(std::memory_order_acquire) ||
        ctx->quarantined.load(std::memory_order_acquire)) {
        LOGE("nativeStart(handle) denied: poisoned session transportLost=%d quarantined=%d handle=%p",
             ctx->transportLost.load() ? 1 : 0,
             ctx->quarantined.load() ? 1 : 0,
             ctx);
        return JNI_FALSE;
    }
    if (ctx->pendingTransfers.load(std::memory_order_acquire) != 0 ||
        ctx->pendingFeedbackTransfers.load(std::memory_order_acquire) != 0 ||
        ctx->eventOwner.carrier() != nullptr ||
        ctx->eventOwner.threadJoinable()) {
        LOGE("nativeStart(handle) denied: stale transfer/event owner state pending=%d fb=%d carrier=%p threadJoinable=%d",
             ctx->pendingTransfers.load(),
             ctx->pendingFeedbackTransfers.load(),
             ctx->eventOwner.carrier(),
             ctx->eventOwner.threadJoinable() ? 1 : 0);
        ctx->sessionBroken.store(true, std::memory_order_release);
        return JNI_FALSE;
    }

    if (!ctx || !ctx->devHandle) {
        LOGE("nativeStart(handle) failed: invalid handle or devHandle");
        return JNI_FALSE;
    }
    if (g_requiresReinit.load(std::memory_order_acquire)) {
        LOGE("nativeStart(handle) rejected: playback policy changed, reinit required");
        return JNI_FALSE;
    }
    if (ctx->streaming.load()) {
        LOGI("Streaming already active");
        return JNI_TRUE;
    }
    if (!ctx->initialized.load()) {
        LOGE("nativeStart(handle) failed: not initialized");
        return JNI_FALSE;
    }

    // New stream session: reset stats and create a fresh self-test boundary.
    resetUsbSessionForPlayback(ctx);
    uint64_t newSessionId = ctx->streamSessionId.fetch_add(1, std::memory_order_acq_rel) + 1;
    LOGI("nativeStart: streamSessionId=%llu", (unsigned long long)newSessionId);

    ctx->stopping.store(false, std::memory_order_release);
    // pending counters are callback ownership counters. They must already be
    // zero before start and must never be reset while an old URB may exist.
    ctx->isoPacer.accumulatorQ32 = 0;
    ctx->isoPacer.setSampleRate((double)ctx->nominalSampleRate);
    ctx->fatalError.store(0, std::memory_order_relaxed);
    ctx->starved = false;
    ctx->starvedRecoveryBytes =
            ctx->sampleRate * ctx->bytesPerFrame * STARVATION_RECOVERY_MS / 1000;
    if (ctx->starvedRecoveryBytes < ctx->bytesPerFrame * 64) {
        ctx->starvedRecoveryBytes = ctx->bytesPerFrame * 64;
    }
    ctx->consecutiveEmptyTransfers = 0;
    ctx->dopOutputMarkerStart = true;
    ctx->rawDsdCarry.clear();

    // 重置淡入状
    ctx->fadeSamplesRemaining = 0;
    ctx->fadeTotalSamples = 0;
    ctx->startupSilenceDone = false;
    ctx->feedbackAudioGateHolding.store(false, std::memory_order_release);
    ctx->feedbackAudioGateReleaseLogged.store(false, std::memory_order_release);

    // 重置 PI 自适应速率控制器（feedback 失效后会在 feedbackCallback 中重新激活）
    ctx->adaptiveRate.active = false;
    ctx->adaptiveRate.integralError = 0.0;
    ctx->adaptiveRate.correction = 0.0;
    ctx->adaptiveRate.stableCount = 0;
    {
        ctx->adaptiveRate.prevBufUsed = ringAvailable(ctx);
    }
    setPacingMode(ctx,
                  ctx->feedbackEpAddress != 0
                  ? (ctx->feedbackDegraded ? UsbPacingMode::FeedbackDegradedFixed : UsbPacingMode::NoFeedbackFixed)
                  : UsbPacingMode::NoFeedbackFixed,
                  "nativeStart");
    // The playback alt-setting is committed exactly once by nativeInitUsbDevice
    // (or by the explicit standby-resume transition). Toggling alt0/selected on
    // every start can race kernel URB retirement and causes controller reset
    // storms on fragile OEM hosts. Start only submits a fresh, verified-empty
    // transfer pool; it never reconfigures the interface or runs an async probe.
    LOGI("nativeStart: reuse committed USB route without alt toggle/probe iface=%d alt=%d out=0x%02X fb=0x%02X",
         ctx->interfaceNumber, ctx->altSetting, ctx->epAddress, ctx->feedbackEpAddress);

    // 确保环形缓冲区有足够静音预填"
    {
        // SPSC lock-free: prefill before streaming starts, no concurrent reader yet
        size_t bufSize = ctx->pcmRingBuffer.size();
        size_t used = ringAvailable(ctx);
        size_t need = ctx->bytes_per_second * 80 / 1000; // low-latency startup safety fill
        if (ctx->pcmToDsdSession && used < need) {
            requestPcmToDsdDemand(ctx, "native_start_prefill");
            const int64_t deadlineMs = nowSteadyMs() + 45;
            while (used < need && nowSteadyMs() < deadlineMs &&
                   !ctx->sessionBroken.load(std::memory_order_acquire)) {
                {
                    if (rawsmusic::usb::dsdPcmQueueUsed(*ctx) == 0) break;
                    rawsmusic::usb::waitForDsdPcmQueueProgress(*ctx, 2);
                }
                used = ringAvailable(ctx);
            }
            size_t queuedAtStart = 0;
            queuedAtStart = rawsmusic::usb::dsdPcmQueueUsed(*ctx);
            LOGI("PCM_TO_DSD nativeStart demand prefill: ring=%zu target=%zu queue=%zu",
                 used, need, queuedAtStart);
        }
        if (used < need) {
            size_t fill = std::min(need - used, (bufSize - 1) - used);
            fill = (fill / (size_t)std::max(1, ctx->bytesPerFrame)) *
                   (size_t)std::max(1, ctx->bytesPerFrame);

            // DoP/native DSD must begin with valid transport silence, but real converted
            // bytes may already be in the ring before nativeStart. Appending silence after
            // those bytes produces an audible real->silence->real gap. With no reader active
            // yet, prepend the silence by moving the ring read position backwards instead.
            const bool prefillDop = ctx->dsdSession && ctx->dsdDopTransport;
            const bool prefillNativeDsd = ctx->dsdSession && !ctx->dsdDopTransport;

            if ((prefillDop || prefillNativeDsd) && fill > 0) {
                const bool prependBeforeReal = used > 0;
                const size_t pos = prependBeforeReal
                        ? (ctx->pcmReadPos.load(std::memory_order_acquire) + bufSize - fill) % bufSize
                        : ctx->pcmWritePos.load(std::memory_order_acquire);
                const size_t first = std::min(fill, bufSize - pos);

                if (prefillDop) {
                    bool markerStart = true;
                    markerStart = fillDoPSilence(
                            ctx->pcmRingBuffer.data() + pos, first,
                            ctx->bytesPerFrame, ctx->deviceChannels,
                            DOP_MARKER_A, DOP_MARKER_B, markerStart);
                    if (fill > first) {
                        fillDoPSilence(
                                ctx->pcmRingBuffer.data(), fill - first,
                                ctx->bytesPerFrame, ctx->deviceChannels,
                                DOP_MARKER_A, DOP_MARKER_B, markerStart);
                    }
                } else {
                    bool patternStart = true;
                    patternStart = fillNativeDsdSilence(
                            ctx->pcmRingBuffer.data() + pos, first,
                            ctx->bytesPerFrame, ctx->deviceChannels,
                            ctx->deviceSubslotSize, patternStart);
                    if (fill > first) {
                        fillNativeDsdSilence(
                                ctx->pcmRingBuffer.data(), fill - first,
                                ctx->bytesPerFrame, ctx->deviceChannels,
                                ctx->deviceSubslotSize, patternStart);
                    }
                }

                if (prependBeforeReal) {
                    ctx->pcmReadPos.store(pos, std::memory_order_release);
                    LOGI("PCM_TO_DSD startup: prepended %zu transport-silence bytes before %zu real bytes",
                         fill, used);
                } else {
                    ctx->pcmWritePos.store((pos + fill) % bufSize, std::memory_order_release);
                    LOGI("PCM_TO_DSD startup: pre-buffered %zu transport-silence bytes", fill);
                }
                // fillIsoTransfer owns the outgoing marker/pattern phase from the first packet.
                ctx->dopOutputMarkerStart = true;
            } else {
                // PCM path: do not synthesize startup silence.
                LOGI("Start: PCM ring below startup target, not padding silence: used=%zu target=%zu",
                     used, need);
            }
        }
    }

    // Isochronous endpoints do not use bulk/interrupt HALT recovery. Avoid
    // unnecessary usbfs endpoint operations on fragile OEM host controllers.

    for (int i = 0; i < NUM_TRANSFERS; i++) {
        ctx->transfers[i] = nullptr;
    }

    // Hardware volume: the Feature Unit is initialized once after
    // native prepare and before ISO starts. nativeStart, track changes and first
    // completions never write it. PCM/session gain stays at unity.
    const UsbTransitionGainOwner startGainOwner = getTransitionGainOwner(ctx);
    if (ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectHwVol ||
        ctx->playbackMode == UsbPlaybackMode::ExclusiveProcessedHwVol) {
        LOGI("Start: hardware Feature Unit unchanged path=%s rawCached=%d",
             ctx->featureUnitVolumePathName ? ctx->featureUnitVolumePathName : "none",
             ctx->lastHardwareVolumeRaw.load(std::memory_order_acquire));
        ctx->startupVolumeGuardUntilMs.store(0, std::memory_order_release);
        ctx->softwareVolume.store(1.0f, std::memory_order_release);
        forceSessionEnvelopeUnity(ctx);
    } else if (ctx->playbackMode == UsbPlaybackMode::ExclusiveSoftwareVolume) {
        const float curVol = ctx->softwareVolume.load(std::memory_order_relaxed);
        if (transitionOwnerUsesLegacyStartupFade(startGainOwner)) {
            // Old APK/native combinations retain the historical cap. Explicit Phase 7 owners
            // must never multiply the session envelope with another startup gain owner.
            ctx->startupVolumeGuardUntilMs.store(nowSteadyMs() + USB_STARTUP_GUARD_MS,
                                                 std::memory_order_release);
            LOGI("Start: legacy software volume %.3f guard=%lldms cap=%.3f",
                 curVol, (long long)USB_STARTUP_GUARD_MS, USB_STARTUP_GUARD_CAP);
        } else {
            ctx->startupVolumeGuardUntilMs.store(0, std::memory_order_release);
            LOGI("Start: explicit transition owner=%s; legacy startup guard disabled volume=%.3f",
                 transitionGainOwnerName(startGainOwner), curVol);
        }
    } else if (ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectFixed) {
        ctx->startupVolumeGuardUntilMs.store(0, std::memory_order_release);
        ctx->softwareVolume.store(1.0f, std::memory_order_release);
        forceSessionEnvelopeUnity(ctx);
        LOGI("Start: fixed/DSD route owner=%s; PCM gain/fade/startup guard bypassed",
             transitionGainOwnerName(startGainOwner));
    }

    ctx->streaming.store(true, std::memory_order_release);
    ctx->acceptingWrites.store(true, std::memory_order_release);
    setUsbStreamState(ctx, UsbStreamState::STREAMING, "nativeStart");

    // No dedicated send thread in the steady-state path.  The libusb event
    // callback immediately refills and resubmits completed ISO transfers.
    ctx->submitOwner.startDirect();

    // Match the UAC20 diagnostic startup order more closely: the libusb event
    // loop must already be live before persistent feedback and OUT transfers are
    // armed, otherwise HyperOS can delay the earliest callbacks and falsely push
    // the stream into the no-feedback recovery path.
    resetUsbIsoPacerToRuntime(ctx, "nativeStart_before_submit");
    ctx->activeTransferCount.store(0, std::memory_order_release);
    ctx->nextPoolIndex.store(0, std::memory_order_release);
    // pendingTransfers is callback-owned and was verified zero before reuse.
    ctx->eventThreadRunning.store(true, std::memory_order_release);
    const int schedulerSampleRate =
            g_androidSchedulerSampleRate.load(std::memory_order_acquire);
    const int schedulerFrames =
            g_androidSchedulerFramesPerBuffer.load(std::memory_order_acquire);
    LOGI("USB_OPENSL_EVENT_CARRIER attempt usbSr=%d schedulerSr=%d schedulerFrames=%d exclusiveSnapshot=%d",
         ctx->sampleRate,
         schedulerSampleRate,
         schedulerFrames,
         g_usbExclusiveActive.load(std::memory_order_acquire) ? 1 : 0);
    const bool eventOwnerStarted = ctx->eventOwner.start(
            schedulerSampleRate,
            schedulerFrames,
            eventLoopFromAudioCarrier,
            ctx);
    if (ctx->eventOwner.carrier() &&
        !rawsmusic::usb::isUsbAudioScheduleCarrierActive(ctx->eventOwner.carrier())) {
        LOGE("nativeStart: OpenSL RT callback failed to become active; session quarantined");
        ctx->eventThreadRunning.store(false, std::memory_order_release);
        ctx->acceptingWrites.store(false, std::memory_order_release);
        ctx->streaming.store(false, std::memory_order_release);
        ctx->sessionBroken.store(true, std::memory_order_release);
        ctx->quarantined.store(true, std::memory_order_release);
        ctx->submitOwner.stop();
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "opensl_rt_bootstrap_failed");
        return JNI_FALSE;
    }
    if (!eventOwnerStarted) {
        LOGE("nativeStart: eventThread creation failed");
        ctx->eventThreadRunning.store(false, std::memory_order_release);
        ctx->acceptingWrites.store(false, std::memory_order_release);
        ctx->streaming.store(false, std::memory_order_release);
        ctx->submitOwner.stop();
        return JNI_FALSE;
    }
    if (!ctx->eventOwner.carrier()) {
        LOGW("nativeStart: OpenSL event carrier unavailable before callback registration, using std::thread fallback");
    }

    // Explicit-feedback-first: arm feedback before OUT so async UAC2 devices
    // have a live feedback endpoint by the time the first audio URBs complete.
    startPersistentFeedbackTransfer(ctx, "feedback-first submit", "Explicit-feedback-first");

    // UAC20-style startup: pre-submit the entire time-sized pool before normal
    // playback.  This gives the host controller ~180ms of explicit-feedback
    // queue or ~320ms when feedback is absent/degraded instead of relying on
    // userspace progressive growth after the stream has already started.
    const int initialTransferBudget = currentTransferPoolCap(ctx);
    int submitted = 0;
    for (int i = 0; i < initialTransferBudget; i++) {
        if (!ctx->transfers[i]) {
            ctx->transfers[i] = libusb_alloc_transfer(ctx->numIsoPackets);
            if (!ctx->transfers[i]) {
                LOGE("libusb_alloc_transfer(%d) failed", ctx->numIsoPackets);
                continue;
            }
        }
        ctx->isoUserData[i].ctx = ctx;
        ctx->isoUserData[i].index = i;

        libusb_fill_iso_transfer(ctx->transfers[i], ctx->devHandle, ctx->epAddress,
                                 ctx->transferBuffers[i], ctx->transferSize,
                                 ctx->numIsoPackets,
                                 isoCallback, &ctx->isoUserData[i], 0);
        for (int p = 0; p < ctx->numIsoPackets; p++) {
            ctx->transfers[i]->iso_packet_desc[p].length = ctx->bytesPerPacket;
        }

        fillIsoTransfer(ctx, ctx->transfers[i], i);

        ctx->pendingTransfers.fetch_add(1, std::memory_order_acq_rel);
        int ret = libusb_submit_transfer(ctx->transfers[i]);
        if (ret < 0) {
            ctx->pendingTransfers.fetch_sub(1, std::memory_order_acq_rel);
            LOGE("libusb_submit_transfer(%d) failed: %s", i, libusb_strerror(ret));
            if (ret == LIBUSB_ERROR_IO || ret == LIBUSB_ERROR_NO_DEVICE ||
                ret == LIBUSB_ERROR_NOT_FOUND || ret == LIBUSB_ERROR_OTHER) {
                markUsbTransportLost(ctx, "initial submit", i, ret);
                break;
            }
            continue;
        }
        submitted++;
        recordIsoSubmitDiagnostics(ctx, ctx->transfers[i]);
    }

    ctx->activeTransferCount.store(submitted, std::memory_order_release);
    ctx->nextPoolIndex.store(submitted, std::memory_order_release);
    ctx->submitOwner.initialSubmissionComplete.store(true, std::memory_order_release);

    if (ctx->transportLost.load(std::memory_order_acquire) ||
        ctx->sessionBroken.load(std::memory_order_acquire)) {
        LOGE("nativeStart aborted after initial submit: transportLost=%d broken=%d submitted=%d; draining submitted URBs",
             ctx->transportLost.load() ? 1 : 0,
             ctx->sessionBroken.load() ? 1 : 0,
             submitted);
        stopStreamingLocked(ctx);
        return JNI_FALSE;
    }

    if (submitted <= 0) {
        LOGE("nativeStart: no transfers submitted");
        // Keep the event owner alive while cancellation callbacks are reaped.
        // Stopping the owner first strands pending feedback ownership forever.
        stopStreamingLocked(ctx);
        ctx->sessionBroken.store(true, std::memory_order_release);
        ctx->stopRequested.store(true, std::memory_order_release);
        ctx->acceptingWrites.store(false, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "native_start_no_iso_submitted");
        return JNI_FALSE;
    }

    if (submitted > 0 && ctx->transfers[0]) {
        struct libusb_transfer *xfer0 = ctx->transfers[0];
        LOGI("UAC20-sized submission: %d/%d transfers active, fixed pool target=%d owner=libusb-callback",
             submitted, NUM_TRANSFERS, currentTransferPoolCap(ctx));
        LOGI("ISO transfer[0] diag: numPkts=%d bufCapacity=%d",
             xfer0->num_iso_packets, xfer0->length);
        int pktDump = std::min((int) xfer0->num_iso_packets, 8);
        for (int p = 0; p < pktDump; p++) {
            LOGI("  iso_pkt[%d].length=%d", p, xfer0->iso_packet_desc[p].length);
        }
        if (xfer0->buffer && xfer0->length > 0) {
            size_t dumpLen = std::min((size_t) xfer0->length, (size_t) 48);
            char hex[200] = {0};
            int pos = 0;
            for (size_t i = 0; i < dumpLen && pos < 190; i++) {
                pos += snprintf(hex + pos, 190 - pos, "%02X ", xfer0->buffer[i]);
            }
            LOGI("ISO transfer[0] first %zu bytes: %s", dumpLen, hex);
            // 如果是 DoP 模式，检查 marker
            bool dsdOn = ctx->dsdSession && ctx->dsdDopTransport;
            if (dsdOn && dumpLen >= 6) {
                // DoP marker is always 0x05/0xFA.
                const int dsdRate = ctx->dsdRateMultiplier;
                (void)dsdRate;
                uint8_t expectedMA = DOP_MARKER_A;
                uint8_t expectedMB = DOP_MARKER_B;
                // 检查每个第 3 字节（marker 位置，假设 stereo 6 字节帧）
                bool markersOk = true;
                for (size_t fi = 0; (fi * 6 + 5) < dumpLen; fi++) {
                    uint8_t lm = xfer0->buffer[fi * 6 + 2];
                    uint8_t rm = xfer0->buffer[fi * 6 + 5];
                    if ((lm != expectedMA && lm != expectedMB) ||
                        (rm != expectedMA && rm != expectedMB)) {
                        markersOk = false;
                        break;
                    }
                }
                LOGI("ISO DoP marker check: expectedA=0x%02X expectedB=0x%02X markersOK=%d",
                     expectedMA, expectedMB, markersOk ? 1 : 0);
            }
        }
    }

    if (submitted == 0) {
        LOGE("nativeStart(handle) failed: no ISO transfer submitted");
        ctx->streaming.store(false, std::memory_order_release);
        if (!stopUsbEventOwnerLocked(ctx, "native_start_zero_submitted")) {
            ctx->quarantined.store(true, std::memory_order_release);
        }
        return JNI_FALSE;
    }

    // 提交 feedback transfer
    startPersistentFeedbackTransfer(ctx, "feedback submit", "Feedback");

    LOGI("nativeStart(handle) ok: %d/%d ISO transfers submitted (uac20QueueTarget=%d callbackResubmit=1)",
         submitted, NUM_TRANSFERS, initialTransferBudget);
    // DoP 流状态摘要
    {
        const bool dsdOn = ctx->dsdSession;
        const bool dopOn = ctx->dsdDopTransport;
        if (dsdOn && dopOn) {
            int dsdRate = ctx->dsdRateMultiplier;
            LOGI("DoP STREAM ACTIVE: DSD%d over PCM24 @ %dHz, bytesPerFrame=%d, "
                 "bytesPerPacket=%d, maxPktSize=%d, feedbackEp=0x%02X",
                 dsdRate, ctx->sampleRate, ctx->bytesPerFrame,
                 ctx->bytesPerPacket, ctx->maxPacketSize, ctx->feedbackEpAddress);
        } else if (dsdOn) {
            int dsdRate = ctx->dsdRateMultiplier;
            LOGI("Native DSD STREAM ACTIVE: DSD%d RAW_DATA @ %dHz, bytesPerFrame=%d, "
                 "bytesPerPacket=%d, maxPktSize=%d, feedbackEp=0x%02X",
                 dsdRate, ctx->sampleRate, ctx->bytesPerFrame,
                 ctx->bytesPerPacket, ctx->maxPacketSize, ctx->feedbackEpAddress);
        }
    }
    if (ctx->backgroundGuardian) {
        ctx->backgroundGuardian->start("native_start");
        ctx->backgroundGuardian->notifyStateChanged("native_start_ready");
    }
    return JNI_TRUE;
}

// ==========================
// JNI: nativeStop(handle)（新架构
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeStop__J(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env;
    (void) thiz;
    LOGI("nativeStop(handle) called: handle=0x%llx", (unsigned long long) handle);
    if (handle == 0) return;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);

    if (!isLiveHandle(ctx)) {
        LOGW("nativeStop ignored: dead handle %p", ctx);
        return;
    }

    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (ctx->closing.load(std::memory_order_acquire)) {
        LOGW("nativeStop ignored: handle is closing %p", ctx);
        return;
    }
    requestOutputFadeOutAndWait(ctx, 35, "native_stop");
    stopStreamingLocked(ctx);
    if (ctx->dsdSession) {
        destroyPcmToDsdSessionState(ctx, "native_stop_dsd_destroy");
        ctx->acceptingWrites.store(false, std::memory_order_release);
        ctx->stopRequested.store(true, std::memory_order_release);
        ctx->sessionBroken.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "native_stop_dsd_requires_reopen");
        LOGW("nativeStop: DSD session destroyed; fresh handle required");
    }
}

// ==========================
// JNI: nativePause(handle) - stop USB but keep buffer
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativePause(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env;
    (void) thiz;
    LOGI("nativePause(handle) called: handle=0x%llx", (unsigned long long) handle);
    if (handle == 0) return;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);

    if (!isLiveHandle(ctx)) {
        LOGW("nativePause ignored: dead handle %p", ctx);
        return;
    }

    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (ctx->closing.load(std::memory_order_acquire)) {
        LOGW("nativePause ignored: handle is closing %p", ctx);
        return;
    }

    // Keep the native USB callback chain armed while exclusive playback owns the
    // device in background. The producer may stop writing during an Android
    // lifecycle/focus pause; fillIsoTransfer() then supplies transport-correct
    // PCM/DoP/native-DSD silence and isoCallback() immediately resubmits the same
    // URB. Tearing the stream down here leaves resumption dependent on a
    // background Java thread and is the source of the apparent "fake pause".
    if (!ctx->dsdSession &&
        g_usbExclusiveActive.load(std::memory_order_acquire) &&
        g_usbBackgroundPlaybackActive.load(std::memory_order_acquire) &&
        ctx->streaming.load(std::memory_order_acquire) &&
        !ctx->transportLost.load(std::memory_order_acquire)) {
        // This call can be caused by a lifecycle/focus edge while playback is
        // still expected to continue. Do not fade or consume a synthetic pause
        // here: keep draining real ring data, with fillIsoTransfer() naturally
        // falling back to legal silence only if the producer truly stops.
        ctx->acceptingWrites.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::STREAMING, "native_pause_keep_transport");
        if (ctx->backgroundGuardian) {
            ctx->backgroundGuardian->start("native_pause_keep_transport");
            ctx->backgroundGuardian->notifyStateChanged("native_pause_keep_transport");
        }
        LOGI("nativePause: ignored lifecycle pause; keeping native ISO callback loop alive "
             "pending=%d session=%llu",
             ctx->pendingTransfers.load(std::memory_order_acquire),
             (unsigned long long)ctx->streamSessionId.load(std::memory_order_acquire));
        return;
    }

    requestOutputFadeOutAndWait(ctx, 35, "native_pause");
    stopStreamingLocked(ctx);
    if (ctx->dsdSession) {
        destroyPcmToDsdSessionState(ctx, "native_pause_dsd_destroy");
        ctx->acceptingWrites.store(false, std::memory_order_release);
        ctx->stopRequested.store(true, std::memory_order_release);
        ctx->sessionBroken.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "native_pause_dsd_requires_reopen");
        LOGW("nativePause: DSD session destroyed; fresh handle required");
    } else {
        LOGI("nativePause: USB stopped, buffer preserved");
    }
}

// User pause keeps the claimed interface and ISO callback chain alive, but old
// decoded PCM must not drain for the full ring-buffer duration. Once writes are
// gated, an empty ring makes fillIsoTransfer() emit format-correct silence.
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativePauseToSilence(
        JNIEnv* env, jobject, jlong handle, jstring reason) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return JNI_FALSE;

    const char* reasonChars = reason ? env->GetStringUTFChars(reason, nullptr) : nullptr;
    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (ctx->closing.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire) ||
        ctx->quarantined.load(std::memory_order_acquire) ||
        ctx->sessionBroken.load(std::memory_order_acquire) ||
        ctx->dsdSession ||
        !ctx->streaming.load(std::memory_order_acquire)) {
        LOGW("nativePauseToSilence rejected: reason=%s state=%s streaming=%d dsd=%d",
             reasonChars ? reasonChars : "unknown",
             usbStreamStateName(getUsbStreamState(ctx)),
             ctx->streaming.load(std::memory_order_acquire) ? 1 : 0,
             ctx->dsdSession ? 1 : 0);
        if (reason && reasonChars) env->ReleaseStringUTFChars(reason, reasonChars);
        return JNI_FALSE;
    }

    ctx->acceptingWrites.store(false, std::memory_order_release);
    const size_t discardedBytes = ringAvailable(ctx);
    ctx->pcmWritePos.store(0, std::memory_order_release);
    ctx->pcmReadPos.store(0, std::memory_order_release);
    clearDsdPcmQueue(ctx);
    ctx->transitionSilenceBytesRemaining.store(0, std::memory_order_release);
    ctx->starved = false;
    ctx->consecutiveEmptyTransfers = 0;
    ctx->starvedRecoveryBytes = 0;
    ctx->stopFadeSamplesRemaining.store(0, std::memory_order_release);
    ctx->stopFadeTotalSamples.store(0, std::memory_order_release);
    ctx->stopFadeActive.store(false, std::memory_order_release);
    setUsbStreamState(ctx, UsbStreamState::STREAMING, "user_pause_silence");
    if (ctx->backgroundGuardian) {
        ctx->backgroundGuardian->notifyStateChanged("user_pause_silence");
    }
    LOGI("USB_PAUSE_TRACE native_silence reason=%s discarded=%zu pending=%d session=%llu",
         reasonChars ? reasonChars : "unknown", discardedBytes,
         ctx->pendingTransfers.load(std::memory_order_acquire),
         (unsigned long long)ctx->streamSessionId.load(std::memory_order_acquire));
    if (reason && reasonChars) env->ReleaseStringUTFChars(reason, reasonChars);
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeResumeWritesAfterPause(
        JNIEnv* env, jobject, jlong handle, jstring reason) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return JNI_FALSE;

    const char* reasonChars = reason ? env->GetStringUTFChars(reason, nullptr) : nullptr;
    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    const bool healthy = !ctx->closing.load(std::memory_order_acquire) &&
            !ctx->transportLost.load(std::memory_order_acquire) &&
            !ctx->quarantined.load(std::memory_order_acquire) &&
            !ctx->sessionBroken.load(std::memory_order_acquire) &&
            !ctx->dsdSession &&
            ctx->streaming.load(std::memory_order_acquire);
    if (healthy) {
        // Clear once more so a producer call already inside JNI when pause
        // began cannot leak stale samples into the resumed timeline.
        ctx->pcmWritePos.store(0, std::memory_order_release);
        ctx->pcmReadPos.store(0, std::memory_order_release);
        ctx->acceptingWrites.store(true, std::memory_order_release);
        ctx->starved = false;
        ctx->consecutiveEmptyTransfers = 0;
        ctx->starvedRecoveryBytes = 0;
        setUsbStreamState(ctx, UsbStreamState::STREAMING, "user_resume_writes");
        LOGI("USB_PAUSE_TRACE native_resume reason=%s session=%llu",
             reasonChars ? reasonChars : "unknown",
             (unsigned long long)ctx->streamSessionId.load(std::memory_order_acquire));
    } else {
        LOGW("nativeResumeWritesAfterPause rejected: reason=%s state=%s",
             reasonChars ? reasonChars : "unknown",
             usbStreamStateName(getUsbStreamState(ctx)));
    }
    if (reason && reasonChars) env->ReleaseStringUTFChars(reason, reasonChars);
    return healthy ? JNI_TRUE : JNI_FALSE;
}

// ==========================
// JNI: nativeStopAndFlush(handle) - HARD STOP: session 不可复用
// ==========================
// 注意：普通切歌、暂停、后台、焦点丢失 绝对不能调用此函数！
// 只在设备断开、致命错误、用户主动关闭时才使用。
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeStopAndFlush(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env;
    (void) thiz;
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx) return;

    if (!isLiveHandle(ctx)) {
        LOGW("nativeStopAndFlush ignored: dead handle %p", ctx);
        return;
    }

    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (ctx->closing.load(std::memory_order_acquire)) {
        LOGW("nativeStopAndFlush ignored: handle is closing %p", ctx);
        return;
    }

    LOGW("nativeStopAndFlush HARD STOP called: handle=%p state=%s",
         ctx, usbStreamStateName(getUsbStreamState(ctx)));

    if (ctx->streaming.load(std::memory_order_acquire)) {
        requestOutputFadeOutAndWait(ctx, 35, "hard_stop_and_flush");
        stopStreamingLocked(ctx);
    }
    destroyPcmToDsdSessionState(ctx, "hard_stop_and_flush");

    // Clear ring buffer
    {
        ctx->pcmWritePos.store(0, std::memory_order_release);
        ctx->pcmReadPos.store(0, std::memory_order_release);
        if (!ctx->pcmRingBuffer.empty()) {
            memset(ctx->pcmRingBuffer.data(), 0, ctx->pcmRingBuffer.size());
        }
        clearDsdPcmQueue(ctx);
        ctx->starved = false;
        ctx->isoPacer.accumulatorQ32 = 0;
    }
    ctx->statsUnderrun.store(0, std::memory_order_relaxed);

    // hard stop: session 不可复用，需要 fresh init
    ctx->acceptingWrites.store(false, std::memory_order_release);
    ctx->stopRequested.store(true, std::memory_order_release);
    ctx->sessionBroken.store(true, std::memory_order_release);
    ctx->fatalError.store(0, std::memory_order_release);
    ctx->inStandby.store(false, std::memory_order_release);

    setUsbStreamState(ctx, UsbStreamState::BROKEN, "hard_stop_and_flush");

    LOGW("nativeStopAndFlush HARD STOP done: acceptingWrites=0 stopRequested=1 sessionBroken=1");
}


// ==========================
// nativeRestartIsoTransfersSameProfile is intentionally disabled.
// Reusing transfer objects on the same native session after a stall/loss made
// it impossible to prove that every prior usbfs URB callback had returned.
// Recovery must go through the serialized close/drain/new-token lifecycle.
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeRestartIsoTransfersSameProfile(
        JNIEnv*, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    LOGW("nativeRestartIsoTransfersSameProfile disabled: token=%lld ctx=%p",
         static_cast<long long>(handle), ctx);
    return JNI_FALSE;
}

// ==========================
// nativeFlushForNextTrack: request a UAPP-style callback-owned warm track boundary.
// Returns true only after the live ISO completion owner has applied/acknowledged the cut.
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeFlushForNextTrack(
        JNIEnv*,
        jobject,
        jlong handle
) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return JNI_FALSE;

    LOGI("nativeFlushForNextTrack request: handle=%p state=%s pending=%d",
         ctx, usbStreamStateName(getUsbStreamState(ctx)),
         ctx->pendingTransfers.load(std::memory_order_acquire));

    bool streaming = false;
    uint64_t requestSeq = 0;
    {
        std::lock_guard<std::mutex> lk(ctx->handleMutex);
        if (ctx->closing.load(std::memory_order_acquire) ||
            ctx->transportLost.load(std::memory_order_acquire) ||
            ctx->quarantined.load(std::memory_order_acquire) ||
            ctx->sessionBroken.load(std::memory_order_acquire) ||
            ctx->fatalError.load(std::memory_order_acquire) != 0) {
            LOGE("nativeFlushForNextTrack rejected: poisoned session");
            return JNI_FALSE;
        }
        // Native DSD/DoP generation changes retain additional packer/marker state;
        // do not mutate those sessions in-place. The serialized cold path owns them.
        if (ctx->dsdSession) {
            LOGW("nativeFlushForNextTrack rejected: DSD session requires cold reconfigure");
            return JNI_FALSE;
        }
        const bool modeledPcmStream =
                ctx->runtimeFormat.isValid() &&
                ctx->deviceBytesPerFrame > 0 &&
                ctx->clock.deviceBytesPerSecond > 0 &&
                ctx->selectedOutEndpoint != 0;
        if (!modeledPcmStream) {
            LOGW("nativeFlushForNextTrack rejected: no reusable modeled PCM stream");
            return JNI_FALSE;
        }

        streaming = ctx->streaming.load(std::memory_order_acquire);
        if (!streaming) {
            // No live URB owner exists, so a direct ring reset is safe. This path is
            // used when a retained same-config handle is PREPARED between tracks.
            if (ctx->pendingTransfers.load(std::memory_order_acquire) > 0 ||
                ctx->pendingFeedbackTransfers.load(std::memory_order_acquire) > 0) {
                LOGE("nativeFlushForNextTrack PREPARED rejected: pending ISO=%d FB=%d",
                     ctx->pendingTransfers.load(std::memory_order_acquire),
                     ctx->pendingFeedbackTransfers.load(std::memory_order_acquire));
                return JNI_FALSE;
            }
            ctx->pcmWritePos.store(0, std::memory_order_release);
            ctx->pcmReadPos.store(0, std::memory_order_release);
            ctx->transitionSilenceBytesRemaining.store(0, std::memory_order_release);
            ctx->starved = false;
            ctx->starvedRecoveryBytes = 0;
            ctx->consecutiveEmptyTransfers = 0;
            ctx->isoPacer.accumulatorQ32 = 0;
            ctx->statsUnderrun.store(0, std::memory_order_relaxed);
            ctx->fadeSamplesRemaining = 0;
            ctx->fadeTotalSamples = 0;
            ctx->stopFadeSamplesRemaining.store(0, std::memory_order_release);
            ctx->stopFadeTotalSamples.store(0, std::memory_order_release);
            ctx->stopFadeActive.store(false, std::memory_order_release);
            ctx->startupSilenceDone = false;
            ctx->stopRequested.store(false, std::memory_order_release);
            ctx->inStandby.store(false, std::memory_order_release);
            ctx->acceptingWrites.store(true, std::memory_order_release);
            setUsbStreamState(ctx, UsbStreamState::PREPARED, "track_boundary_prepared_direct");
            LOGI("nativeFlushForNextTrack PREPARED direct boundary applied");
            return JNI_TRUE;
        }

        // Stop producer writes first. The libusb completion callback remains the only
        // steady-state transfer owner and will apply the generation cut before resubmit.
        ctx->acceptingWrites.store(false, std::memory_order_release);
        requestSeq = ctx->trackBoundaryRequestedSeq.fetch_add(
                1, std::memory_order_acq_rel) + 1;
    }
    rawsmusic::UsbCrashGuard::instance().breadcrumb(
            "track_boundary", "REQUEST seq=%llu pending=%d mode=%d",
            static_cast<unsigned long long>(requestSeq),
            ctx->pendingTransfers.load(std::memory_order_acquire),
            static_cast<int>(ctx->playbackMode));

    constexpr int kTrackBoundaryAckTimeoutMs = 350;
    bool acknowledged = false;
    {
        std::unique_lock<std::mutex> waitLock(ctx->trackBoundaryMutex);
        acknowledged = ctx->trackBoundaryCV.wait_for(
                waitLock,
                std::chrono::milliseconds(kTrackBoundaryAckTimeoutMs),
                [ctx, requestSeq] {
                    return ctx->trackBoundaryAppliedSeq.load(std::memory_order_acquire) >= requestSeq ||
                           ctx->closing.load(std::memory_order_acquire) ||
                           ctx->transportLost.load(std::memory_order_acquire) ||
                           ctx->sessionBroken.load(std::memory_order_acquire) ||
                           !ctx->streaming.load(std::memory_order_acquire);
                });
    }

    const bool applied =
            ctx->trackBoundaryAppliedSeq.load(std::memory_order_acquire) >= requestSeq;
    if (!acknowledged || !applied) {
        // A live USB stream that cannot produce one completion boundary in 350 ms is
        // not safe to mutate/reuse. Fail closed and let the existing drain/quarantine
        // lifecycle decide whether a later cold reopen is legal.
        ctx->acceptingWrites.store(false, std::memory_order_release);
        ctx->stopRequested.store(true, std::memory_order_release);
        ctx->sessionBroken.store(true, std::memory_order_release);
        ctx->streaming.store(false, std::memory_order_release);
        ctx->fatalError.store(ERR_USB_IO, std::memory_order_release);
        ctx->submitOwner.stop();
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "track_boundary_ack_timeout");
        const uint64_t appliedSeq =
                ctx->trackBoundaryAppliedSeq.load(std::memory_order_acquire);
        rawsmusic::UsbCrashGuard::instance().breadcrumb(
                "track_boundary", "TIMEOUT seq=%llu applied=%llu pending=%d",
                static_cast<unsigned long long>(requestSeq),
                static_cast<unsigned long long>(appliedSeq),
                ctx->pendingTransfers.load(std::memory_order_acquire));
        LOGE("nativeFlushForNextTrack boundary timeout: seq=%llu applied=%llu pending=%d",
             static_cast<unsigned long long>(requestSeq),
             static_cast<unsigned long long>(appliedSeq),
             ctx->pendingTransfers.load(std::memory_order_acquire));
        return JNI_FALSE;
    }

    if (ctx->closing.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire) ||
        ctx->sessionBroken.load(std::memory_order_acquire) ||
        !ctx->streaming.load(std::memory_order_acquire)) {
        ctx->acceptingWrites.store(false, std::memory_order_release);
        LOGE("nativeFlushForNextTrack boundary applied but session no longer reusable");
        return JNI_FALSE;
    }

    ctx->fatalError.store(0, std::memory_order_release);
    ctx->inStandby.store(false, std::memory_order_release);
    ctx->acceptingWrites.store(true, std::memory_order_release);
    setUsbStreamState(ctx, UsbStreamState::STREAMING, "track_boundary_callback_ack");
    rawsmusic::UsbCrashGuard::instance().breadcrumb(
            "track_boundary", "ACK seq=%llu pending=%d",
            static_cast<unsigned long long>(requestSeq),
            ctx->pendingTransfers.load(std::memory_order_acquire));
    LOGI("nativeFlushForNextTrack callback boundary ack: seq=%llu transitionSilenceMs=%d "
         "hwUnity=%d strictBitPerfect=%d",
         static_cast<unsigned long long>(requestSeq),
         USB_TRACK_SWITCH_SILENCE_MS,
         isHardwareVolumePcmUnityPath(ctx) ? 1 : 0,
         isStrictBitPerfectPcmPath(ctx) ? 1 : 0);
    return JNI_TRUE;
}

// ==========================
// nativeIsSessionBroken: 检查 session 是否处于 broken 状态
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeIsSessionBroken(
        JNIEnv*,
        jobject,
        jlong handle
) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return JNI_TRUE;
    return ctx->sessionBroken.load(std::memory_order_acquire) ? JNI_TRUE : JNI_FALSE;
}

// ==========================
// nativeGetStreamState: 获取当前 USB 流状态
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetStreamState(
        JNIEnv*,
        jobject,
        jlong handle
) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return static_cast<jint>(UsbStreamState::CLOSED);
    return static_cast<jint>(getUsbStreamState(ctx));
}

// ==========================
// resetUsbSessionForPlayback: 新播放开始时重置 session 状态
// ==========================
// Reset session flags only — don't clear ring buffer here.
// nativeFlushForNextTrack() already cleared the ring; nativeStart() is called
// AFTER prefill, so the ring already has real PCM data.
static void resetUsbSessionForPlayback(UsbAudioContext* ctx, bool clearRing, const char* reason) {
    if (!ctx) return;
    if (ctx->closing.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire) ||
        ctx->quarantined.load(std::memory_order_acquire) ||
        ctx->sessionBroken.load(std::memory_order_acquire)) {
        LOGE("resetUsbSessionForPlayback rejected: closing=%d lost=%d quarantined=%d broken=%d reason=%s",
             ctx->closing.load() ? 1 : 0,
             ctx->transportLost.load() ? 1 : 0,
             ctx->quarantined.load() ? 1 : 0,
             ctx->sessionBroken.load() ? 1 : 0,
             reason ? reason : "unknown");
        return;
    }
    if (ctx->streaming.load(std::memory_order_acquire)) {
        // Warm reuse already has a live submit/event owner. Never reset its pacer/statistics or
        // publish PREPARED while URBs are still active; nativeFlushForNextTrack owns that boundary.
        ctx->acceptingWrites.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::STREAMING,
                          reason ? reason : "reset_session_live_noop");
        LOGI("resetUsbSessionForPlayback live no-op: clearRing=%d reason=%s",
             clearRing ? 1 : 0, reason ? reason : "");
        return;
    }
    if (clearRing) {
        // SPSC lock-free: no concurrent reader during reset
        ctx->pcmWritePos.store(0, std::memory_order_release);
        ctx->pcmReadPos.store(0, std::memory_order_release);
        if (!ctx->pcmRingBuffer.empty()) {
            memset(ctx->pcmRingBuffer.data(), 0, ctx->pcmRingBuffer.size());
        }
        clearDsdPcmQueue(ctx);
    }

    // Pause/resume and foreground recovery can happen after the event loop was frozen
    // for many seconds.  Always clear starvation bookkeeping and rebuild the pacer
    // from the runtime device format so old endpoint-full packet state cannot make
    // resume play at 5x speed until the next track.
    ctx->starved = false;
    ctx->consecutiveEmptyTransfers = 0;
    ctx->starvedRecoveryBytes = 0;
    ctx->transitionSilenceBytesRemaining.store(0, std::memory_order_release);
    ctx->statsUnderrun.store(0, std::memory_order_relaxed);
    ctx->feedbackStartupGraceUntilMs.store(0, std::memory_order_release);
    resetUsbIsoPacerToRuntime(ctx, reason ? reason : "resetUsbSessionForPlayback");

    ctx->stopRequested.store(false, std::memory_order_release);
    // sessionBroken is monotonic for this native handle. Once poisoned, only
    // a fresh nativeInitUsbDevice may create a healthy session.
    ctx->fatalError.store(0, std::memory_order_release);
    ctx->inStandby.store(false, std::memory_order_release);
    ctx->acceptingWrites.store(true, std::memory_order_release);
    resetUsbRuntimeStats(ctx);

    setUsbStreamState(ctx, UsbStreamState::PREPARED, reason ? reason : "resetUsbSessionForPlayback");

    LOGI("resetUsbSessionForPlayback: clearRing=%d acceptingWrites=1 stopRequested=0 sessionBroken=0 reason=%s",
         clearRing ? 1 : 0, reason ? reason : "");
}

// ==========================
// checkUsbWriteAllowed: 写入前状态检查
// ==========================
// 返回: 1=允许写入, 0=静默忽略(standby/非接收态), <0=fatal error
static int checkUsbWriteAllowed(UsbAudioContext* ctx) {
    if (!ctx) return -32;

    if (ctx->sessionBroken.load(std::memory_order_acquire)) {
        LOGW("write denied: native session broken");
        return -32;
    }

    if (ctx->fatalError.load(std::memory_order_acquire) != 0) {
        LOGW("write denied: fatalError=%d", ctx->fatalError.load(std::memory_order_acquire));
        return -32;
    }

    if (ctx->inStandby.load(std::memory_order_acquire)) {
        // standby 态静默忽略，不报错
        return 0;
    }

    UsbStreamState state = getUsbStreamState(ctx);
    if (state != UsbStreamState::PREPARED && state != UsbStreamState::STREAMING) {
        LOGW("write ignored: invalid streamState=%s", usbStreamStateName(state));
        return 0;
    }

    if (!ctx->acceptingWrites.load(std::memory_order_acquire)) {
        LOGW("write ignored: acceptingWrites=0 state=%s", usbStreamStateName(state));
        return 0;
    }

    return 1;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeResetSessionForPlayback(
        JNIEnv*, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return;
    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    resetUsbSessionForPlayback(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeCloseStreamForReconfigure(
        JNIEnv*, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return;
    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (ctx->closing.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire) ||
        ctx->quarantined.load(std::memory_order_acquire) ||
        ctx->sessionBroken.load(std::memory_order_acquire)) {
        LOGE("USB lifecycle transition rejected for poisoned session");
        return;
    }

    LOGI("nativeCloseStreamForReconfigure called: handle=%p state=%s iface=%d alt=%d",
         ctx, usbStreamStateName(getUsbStreamState(ctx)),
         ctx->claimedAsInterface, ctx->selectedAltSetting);
    if (ctx->dsdSession) {
        LOGE("nativeCloseStreamForReconfigure rejected for DSD; full close required");
        ctx->sessionBroken.store(true, std::memory_order_release);
        ctx->acceptingWrites.store(false, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "dsd_reconfigure_requires_full_close");
        return;
    }

    if (ctx->streaming.load(std::memory_order_acquire)) {
        requestOutputFadeOutAndWait(ctx, 25, "close_stream_for_reconfigure");
        stopStreamingLocked(ctx);
    }

    if (!requireReusableSessionDrainedLocked(
            ctx, "close_stream_for_reconfigure_not_drained")) {
        return;
    }

    // Clear ring buffer
    {
        ctx->pcmWritePos.store(0, std::memory_order_release);
        ctx->pcmReadPos.store(0, std::memory_order_release);
        if (!ctx->pcmRingBuffer.empty()) {
            memset(ctx->pcmRingBuffer.data(), 0, ctx->pcmRingBuffer.size());
        }
        clearDsdPcmQueue(ctx);
        ctx->starved = false;
        ctx->isoPacer.accumulatorQ32 = 0;
    }

    // 释放 AS interface，但保留 fd / libusb handle。任何失败都会 poison
    // 当前 handle，避免在 ownership 不确定时原地 reclaim。
    if (!releaseAudioStreamingInterfaceForReuseLocked(
            ctx, "reconfigure")) {
        return;
    }

    ctx->acceptingWrites.store(false, std::memory_order_release);
    ctx->stopRequested.store(false, std::memory_order_release);
    // sessionBroken is monotonic for this native handle. Once poisoned, only
    // a fresh nativeInitUsbDevice may create a healthy session.
    ctx->fatalError.store(0, std::memory_order_release);
    ctx->inStandby.store(false, std::memory_order_release);

    setUsbStreamState(ctx, UsbStreamState::OPEN, "close_stream_for_reconfigure");

    LOGI("nativeCloseStreamForReconfigure done: sessionBroken=0");
}

// ==========================
// nativeEnterStandby: 暂停/后台/焦点丢失走 standby
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeEnterStandby(
        JNIEnv*, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return;
    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (ctx->closing.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire) ||
        ctx->quarantined.load(std::memory_order_acquire) ||
        ctx->sessionBroken.load(std::memory_order_acquire)) {
        LOGE("USB lifecycle transition rejected for poisoned session");
        return;
    }

    LOGI("nativeEnterStandby called: handle=%p state=%s streaming=%d iface=%d",
         ctx, usbStreamStateName(getUsbStreamState(ctx)),
         ctx->streaming.load() ? 1 : 0, ctx->claimedAsInterface);
    if (ctx->dsdSession) {
        LOGE("nativeEnterStandby rejected for DSD; full close required");
        if (ctx->streaming.load(std::memory_order_acquire)) {
            requestOutputFadeOutAndWait(ctx, 25, "dsd_standby_destroy");
            stopStreamingLocked(ctx);
        }
        destroyPcmToDsdSessionState(ctx, "dsd_standby_destroy");
        ctx->sessionBroken.store(true, std::memory_order_release);
        ctx->acceptingWrites.store(false, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "dsd_standby_requires_full_close");
        return;
    }

    if (getUsbStreamState(ctx) == UsbStreamState::STANDBY) {
        LOGI("nativeEnterStandby ignored: already STANDBY");
        return;
    }

    if (ctx->streaming.load(std::memory_order_acquire)) {
        requestOutputFadeOutAndWait(ctx, 35, "enter_standby");
        stopStreamingLocked(ctx);
    }

    if (!requireReusableSessionDrainedLocked(ctx, "enter_standby_not_drained")) {
        return;
    }

    // Clear ring buffer
    {
        ctx->pcmWritePos.store(0, std::memory_order_release);
        ctx->pcmReadPos.store(0, std::memory_order_release);
        if (!ctx->pcmRingBuffer.empty()) {
            memset(ctx->pcmRingBuffer.data(), 0, ctx->pcmRingBuffer.size());
        }
        clearDsdPcmQueue(ctx);
        ctx->starved = false;
        ctx->isoPacer.accumulatorQ32 = 0;
    }

    // 释放 AS interface。失败时禁止复用同一 native handle。
    if (!releaseAudioStreamingInterfaceForReuseLocked(ctx, "standby")) {
        return;
    }

    ctx->acceptingWrites.store(false, std::memory_order_release);
    ctx->stopRequested.store(false, std::memory_order_release);
    // sessionBroken is monotonic for this native handle. Once poisoned, only
    // a fresh nativeInitUsbDevice may create a healthy session.
    ctx->fatalError.store(0, std::memory_order_release);
    ctx->inStandby.store(true, std::memory_order_release);

    setUsbStreamState(ctx, UsbStreamState::STANDBY, "enter_standby");

    LOGI("USB standby done: acceptingWrites=0 stopRequested=0 sessionBroken=0");
}

// ==========================
// nativeResumeFromStandby: 从 standby 恢复，重新 claim AS interface
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeResumeFromStandby(
        JNIEnv*, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return JNI_FALSE;
    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (ctx->closing.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire) ||
        ctx->quarantined.load(std::memory_order_acquire) ||
        ctx->sessionBroken.load(std::memory_order_acquire)) {
        LOGE("resumeFromStandby rejected: closing=%d lost=%d quarantined=%d broken=%d",
             ctx->closing.load() ? 1 : 0,
             ctx->transportLost.load() ? 1 : 0,
             ctx->quarantined.load() ? 1 : 0,
             ctx->sessionBroken.load() ? 1 : 0);
        return JNI_FALSE;
    }

    LOGI("nativeResumeFromStandby called: handle=%p state=%s iface=%d alt=%d ep=0x%02X",
         ctx, usbStreamStateName(getUsbStreamState(ctx)),
         ctx->selectedAsInterface, ctx->selectedAltSetting, ctx->selectedOutEndpoint);
    if (ctx->dsdSession) {
        LOGE("nativeResumeFromStandby rejected for DSD; fresh nativeInit required");
        ctx->sessionBroken.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "dsd_resume_requires_fresh_init");
        return JNI_FALSE;
    }

    if (ctx->pendingTransfers.load(std::memory_order_acquire) != 0 ||
        ctx->pendingFeedbackTransfers.load(std::memory_order_acquire) != 0 ||
        ctx->eventOwner.carrier() != nullptr || ctx->eventOwner.threadJoinable()) {
        LOGE("resumeFromStandby rejected: stale transfer/event owner ISO=%d FB=%d carrier=%p threadJoinable=%d",
             ctx->pendingTransfers.load(), ctx->pendingFeedbackTransfers.load(),
             ctx->eventOwner.carrier(), ctx->eventOwner.threadJoinable() ? 1 : 0);
        ctx->sessionBroken.store(true, std::memory_order_release);
        ctx->quarantined.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "resume_stale_owner");
        return JNI_FALSE;
    }

    if (!ctx->devHandle) {
        LOGE("resumeFromStandby failed: devHandle=null");
        ctx->sessionBroken.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "resume_no_dev_handle");
        return JNI_FALSE;
    }

    if (ctx->selectedAsInterface < 0 || ctx->selectedAltSetting <= 0) {
        LOGE("resumeFromStandby failed: invalid selected route iface=%d alt=%d",
             ctx->selectedAsInterface, ctx->selectedAltSetting);
        ctx->sessionBroken.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "resume_invalid_route");
        return JNI_FALSE;
    }

    // 重新 claim AS interface
    int r = libusb_claim_interface(ctx->devHandle, ctx->selectedAsInterface);
    if (r != LIBUSB_SUCCESS) {
        LOGE("resume claim AS iface=%d failed: r=%d (%s)",
             ctx->selectedAsInterface, r, libusb_error_name(r));
        ctx->sessionBroken.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "resume_claim_failed");
        return JNI_FALSE;
    }
    ctx->asInterfaceClaimed = true;
    ctx->claimedAsInterface = ctx->selectedAsInterface;

    r = libusb_set_interface_alt_setting(
            ctx->devHandle, ctx->selectedAsInterface, ctx->selectedAltSetting);
    if (r != 0) {
        LOGE("resume set alt failed: iface=%d alt=%d r=%d",
             ctx->selectedAsInterface, ctx->selectedAltSetting, r);
        const int releaseResult = libusb_release_interface(
                ctx->devHandle, ctx->selectedAsInterface);
        LOGW("resume cleanup release iface=%d result=%d (%s)",
             ctx->selectedAsInterface, releaseResult,
             libusb_error_name(releaseResult));
        ctx->asInterfaceClaimed = false;
        ctx->claimedAsInterface = -1;
        ctx->sessionBroken.store(true, std::memory_order_release);
        setUsbStreamState(ctx, UsbStreamState::BROKEN, "resume_set_alt_failed");
        return JNI_FALSE;
    }

    // Clear ring buffer
    {
        ctx->pcmWritePos.store(0, std::memory_order_release);
        ctx->pcmReadPos.store(0, std::memory_order_release);
        if (!ctx->pcmRingBuffer.empty()) {
            memset(ctx->pcmRingBuffer.data(), 0, ctx->pcmRingBuffer.size());
        }
        clearDsdPcmQueue(ctx);
        ctx->starved = false;
        ctx->isoPacer.accumulatorQ32 = 0;
    }

    startDsdWorkerIfNeeded(ctx, "resume_from_standby");
    ctx->stopRequested.store(false, std::memory_order_release);
    // sessionBroken is monotonic for this native handle. Once poisoned, only
    // a fresh nativeInitUsbDevice may create a healthy session.
    ctx->fatalError.store(0, std::memory_order_release);
    ctx->inStandby.store(false, std::memory_order_release);
    ctx->acceptingWrites.store(true, std::memory_order_release);

    setUsbStreamState(ctx, UsbStreamState::PREPARED, "resume_from_standby");

    LOGI("resumeFromStandby prepared: acceptingWrites=1 sessionBroken=0");
    return JNI_TRUE;
}

// ==========================
// JNI: nativeClose(handle)
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeClose(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env;
    (void) thiz;
    if (handle == 0) {
        LOGW("nativeClose ignored: handle=0");
        return;
    }
    std::unique_lock<std::shared_mutex> lifecycleWriteLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx) {
        LOGW("nativeClose ignored: stale/double-close token=%lld quarantined=%d",
             static_cast<long long>(handle), isQuarantinedToken(handle) ? 1 : 0);
        return;
    }

    // Atomically poison and remove the public token while the exclusive
    // lifecycle lock prevents every handle-based JNI call from running.
    {
        std::lock_guard<std::mutex> registryLock(gRegistryMtx);
        const uint64_t token = static_cast<uint64_t>(handle);
        if (!gHandleRegistry.pointsToTokenNoLock(token, ctx) ||
            !gHandleRegistry.containsLiveHandleNoLock(ctx)) {
            LOGW("nativeClose ignored: token mapping changed token=%llu ctx=%p",
                 static_cast<unsigned long long>(token), ctx);
            return;
        }
        ctx->beginClosing();
        gHandleRegistry.erasePublicTokenNoLock(token);
        gHandleRegistry.eraseLiveHandleNoLock(ctx);
    }
    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    LOGI("nativeClose: handle=%p", ctx);

    // The guardian owns a raw ctx pointer. Stop and join it before any USB
    // resource or context member can be destroyed or quarantined.
    if (ctx->backgroundGuardian) {
        ctx->backgroundGuardian->stop("native_close");
    }
    stopDsdWorker(ctx, "native_close");
    stopHidLocked(ctx);

    raw_usb_crash_guard_begin("nativeClose");

    // 2. stop submission, cancel every URB, reap callbacks and stop the
    // event owner. This function is required even when streaming was already
    // cleared by transport-loss handling.
    stopStreamingLocked(ctx);

    // 3. stop event owner. If it cannot be joined, the context remains
    // quarantined because the worker still owns ctx.
    if (!stopUsbEventOwnerLocked(ctx, "native_close")) {
        quarantineHandle(ctx, "native_close_event_owner_timeout");
        raw_usb_crash_guard_quarantine("nativeClose", "event owner exit timeout");
        return;
    }

    // 3.5. 注销热插拔回
#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000105)
    if (ctx->hotplugRegistered && ctx->libusbCtx) {
        libusb_hotplug_deregister_callback(ctx->libusbCtx, ctx->hotplugHandle);
        ctx->hotplugRegistered = false;
        LOGI("Hotplug callback deregistered");
    }
#endif

    // 3.6. quarantine 检查：join 后仍有 pending transfer → 不 free / 不 close / 不 exit
    // callback 可能还没回来，此时 free transfer / close devHandle / libusb_exit = UAF
    // 宁可泄漏整个旧 context，也不在 callback 持有指针时释放
    {
        const int pending = ctx->pendingTransfers.load(std::memory_order_acquire);
        const int fbPending = ctx->pendingFeedbackTransfers.load(std::memory_order_acquire);
        if (pending > 0 || fbPending > 0 ||
            ctx->eventOwner.carrier() != nullptr ||
            ctx->eventOwner.threadJoinable() ||
            ctx->quarantined.load(std::memory_order_acquire)) {
            LOGE("nativeClose QUARANTINE: handle=%p ISO=%d FB=%d carrier=%p threadJoinable=%d poisoned=%d; retaining all USB-owned memory",
                 ctx, pending, fbPending, ctx->eventOwner.carrier(),
                 ctx->eventOwner.threadJoinable() ? 1 : 0,
                 ctx->quarantined.load() ? 1 : 0);
            ctx->quarantined.store(true, std::memory_order_release);
            quarantineHandle(ctx, "native_close_not_drained");
            raw_usb_crash_guard_quarantine("nativeClose", "session not fully drained");
            return;
        }
    }

    // 4. now safe to free transfers
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        if (ctx->transfers[i]) {
            libusb_free_transfer(ctx->transfers[i]);
            ctx->transfers[i] = nullptr;
        }
    }
    if (ctx->feedbackTransfer) {
        libusb_free_transfer(ctx->feedbackTransfer);
        ctx->feedbackTransfer = nullptr;
    }

    // 5. release USB interfaces
    if (ctx->devHandle) {
        if (ctx->claimDoneByNative && ctx->interfaceNumber >= 0) {
            int r = libusb_set_interface_alt_setting(ctx->devHandle, ctx->interfaceNumber,
                                                     0);
            LOGI("set alt0 result: %s", libusb_error_name(r));
            r = libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
            LOGI("release AS iface result: %s", libusb_error_name(r));
            ctx->interfaceNumber = -1;
        }
        releaseAudioControlInterface(ctx);
        libusb_close(ctx->devHandle);
        ctx->devHandle = nullptr;
    }
    if (ctx->libusbCtx) {
        libusb_exit(ctx->libusbCtx);
        ctx->libusbCtx = nullptr;
    }

    // 6. 关闭 dupFd
    if (ctx->dupFd >= 0) {
        if (fcntl(ctx->dupFd, F_GETFD) >= 0) {
            close(ctx->dupFd);
            LOGI("nativeClose: closed dupFd=%d", ctx->dupFd);
        }
        ctx->dupFd = -1;
    }

    // 7. 释放缓冲
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        delete[] ctx->transferBuffers[i];
        ctx->transferBuffers[i] = nullptr;
    }
    delete[] ctx->feedbackBuffer;
    ctx->feedbackBuffer = nullptr;

    // 8. 释放重采样上下文
    closeSwrContext(ctx);

    LOGI("nativeClose done: token=%lld ctx=%p", static_cast<long long>(handle), ctx);
    raw_usb_crash_guard_end("nativeClose");
    {
        std::lock_guard<std::mutex> registryLock(gRegistryMtx);
        removeTokenForContextLocked(ctx, false);
        gHandleRegistry.eraseQuarantinedHandleNoLock(ctx);
    }
    delete ctx;
}

// ====================== Atomic USB session transaction ======================
extern "C"
JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeInitUsbDeviceTransactional(
        JNIEnv* env, jobject thiz,
        jint fd, jint sampleRate, jint sourceSampleRate, jint sourceBitsPerSample,
        jint channels, jint bitsPerSample,
        jint iface, jint alt, jint outEndpoint, jint feedbackEndpoint,
        jint subslotSize, jintArray sessionPolicy) {
    if (!sessionPolicy) {
        LOGE("USB_SESSION_TXN rejected: null policy array");
        return 0;
    }
    const jsize length = env->GetArrayLength(sessionPolicy);
    std::vector<jint> raw(static_cast<size_t>(length));
    if (length > 0) {
        env->GetIntArrayRegion(sessionPolicy, 0, length, raw.data());
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            LOGE("USB_SESSION_TXN rejected: GetIntArrayRegion failed");
            return 0;
        }
    }
    std::vector<int32_t> fields(raw.begin(), raw.end());
    UsbSessionRequest request{};
    std::string error;
    if (!parseUsbSessionRequest(fields.data(), fields.size(), &request, &error)) {
        LOGE("USB_SESSION_TXN rejected: %s", error.c_str());
        return 0;
    }

    // Recursive because nativeInitUsbDevice also takes the same lock. Legacy setters take it too,
    // so no policy field can change between commit and the context snapshot/descriptor scoring.
    std::lock_guard<std::recursive_mutex> sessionPolicyLock(gNextSessionPolicyMtx);
    applyUsbSessionRequestToGlobals(request);
    return Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeInitUsbDevice(
            env, thiz,
            fd, sampleRate, sourceSampleRate, sourceBitsPerSample,
            channels, bitsPerSample,
            iface, alt, outEndpoint, feedbackEndpoint, subslotSize);
}

// ====================== Breadcrumb Path Config ======================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetBreadcrumbPath(
        JNIEnv* env, jobject thiz, jstring path) {
    (void) thiz;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath) {
        raw_usb_crash_guard_set_path(cpath);
        env->ReleaseStringUTFChars(path, cpath);
    }
}

// ====================== Unified Policy API ======================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetPolicy(
        JNIEnv *env, jobject thiz,
        jboolean exclusive,
        jboolean bitPerfect,
        jboolean hwVol) {
    std::lock_guard<std::recursive_mutex> sessionPolicyLock(gNextSessionPolicyMtx);
    const bool ex = exclusive == JNI_TRUE;
    const bool bp = bitPerfect == JNI_TRUE && ex;
    // 硬件音量只要求 USB 独占 + 用户请求，是否安全由 Feature Unit validation 决定
    const bool hv = hwVol == JNI_TRUE && ex;

    const bool oldEx = g_usbExclusiveActive.exchange(ex, std::memory_order_acq_rel);
    const bool oldBp = g_bitPerfectEnabled.exchange(bp, std::memory_order_acq_rel);
    const bool oldHv = g_hardwareFeatureUnitRequested.exchange(hv,
                                                               std::memory_order_acq_rel);

    LOGI("nativeSetPolicy: exclusive=%d bitPerfect=%d hwVolRequested=%d",
         ex ? 1 : 0, bp ? 1 : 0, hv ? 1 : 0);

    // Keep live handles in sync immediately.  Otherwise, after the UI turns
    // hardware volume off, the old handle can stay in a hardware-volume mode
    // until reinit and later safety/pause paths may still write Feature Unit
    // volume while Java has already switched to software gain.
    {
        std::lock_guard<std::mutex> lk(gRegistryMtx);
        for (auto* h : gLiveHandles) {
            if (!h) continue;
            h->usbExclusiveActive = ex;
            h->exclusiveActive = ex;
            h->bitPerfectEnabled = bp;
            h->hardwareFeatureUnitRequested = hv;
            if (!hv) {
                // Route changes must not emit an implicit master/L/R control-transfer burst.
                // Kotlin performs an explicit muted handoff when the user selects software volume.
                setHardwareVolumeState(h, false, false, "nativeSetPolicy hwVol off");
                LOGI("nativeSetPolicy hwVol off: Feature Unit left unchanged handle=%p", h);
            } else if (!h->hardwareVolumeEnabled || !h->hardwareVolumeSafe) {
                enableCachedFeatureUnitController(h, "nativeSetPolicy hwVol on cached");
            }
            h->playbackMode = decidePlaybackMode(h);
            LOGI("nativeSetPolicy applied to live handle=%p mode=%d hwReq=%d hwEn=%d",
                 h,
                 (int)h->playbackMode,
                 h->hardwareFeatureUnitRequested ? 1 : 0,
                 h->hardwareVolumeEnabled ? 1 : 0);
        }
    }

    if (oldEx != ex || oldBp != bp) {
        g_requiresReinit.store(true, std::memory_order_release);
        LOGI("Policy changed -> requiresReinit (exclusive/bitPerfect)");
    } else if (oldHv != hv) {
        // Hardware volume is a controller route, not a USB stream profile.
        // Do not force a reinit when toggling it on/off while the current
        // handle has already validated a Feature Unit.  Reinit remains useful
        // only when enabling hardware volume and no live handle can expose a
        // cached/validated controller yet.
        bool anyLiveController = false;
        {
            std::lock_guard<std::mutex> lk(gRegistryMtx);
            for (auto* h : gLiveHandles) {
                if (!h) continue;
                if (h->hardwareVolumeEnabled && h->hardwareVolumeSafe) {
                    anyLiveController = true;
                    break;
                }
                if (hv && enableCachedFeatureUnitController(h, "nativeSetPolicy hwVol on cached")) {
                    h->playbackMode = decidePlaybackMode(h);
                    anyLiveController = true;
                    break;
                }
                if (!hv && hasCachedFeatureUnitController(h)) {
                    anyLiveController = true;
                    break;
                }
            }
        }
        if (hv && !anyLiveController) {
            g_requiresReinit.store(true, std::memory_order_release);
            LOGI("Policy changed -> requiresReinit (hardware volume needs FeatureUnit probe)");
        } else {
            LOGI("Policy changed -> live hardware-volume route switch without USB reinit: hwVolRequested=%d cachedController=%d",
                 hv ? 1 : 0, anyLiveController ? 1 : 0);
        }
    }
}

// ====================== USB DAC 高级设置======================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetUsbDacSettings(
        JNIEnv *env, jobject thiz,
        jboolean noControlIface,
        jboolean forceUac1,
        jboolean linearVolume,
        jboolean replaceVolume,
        jboolean force1ms) {
    std::lock_guard<std::recursive_mutex> sessionPolicyLock(gNextSessionPolicyMtx);
    bool newNoCI = noControlIface == JNI_TRUE;
    bool newFU1 = forceUac1 == JNI_TRUE;
    bool newLV = linearVolume == JNI_TRUE;
    bool newRV = replaceVolume == JNI_TRUE;
    bool newF1 = force1ms == JNI_TRUE;

    bool oldNoCI = g_usbNoControlInterface.load(std::memory_order_relaxed);
    bool oldFU1 = g_usbForceUac1.load(std::memory_order_relaxed);
    bool oldLV = g_usbLinearVolume.load(std::memory_order_relaxed);
    bool oldRV = g_usbReplaceVolume.load(std::memory_order_relaxed);
    bool oldF1 = g_usbForce1MsPacket.load(std::memory_order_relaxed);

    // 检测是否实际变更：只在值变化时才触发 reinit，避免播放中无意义的 reinit 循环
    bool changed = (newNoCI != oldNoCI) || (newFU1 != oldFU1) || (newLV != oldLV)
                   || (newRV != oldRV) || (newF1 != oldF1);

    g_usbNoControlInterface.store(newNoCI, std::memory_order_release);
    g_usbForceUac1.store(newFU1, std::memory_order_release);
    g_usbLinearVolume.store(newLV, std::memory_order_release);
    g_usbReplaceVolume.store(newRV, std::memory_order_release);
    g_usbForce1MsPacket.store(newF1, std::memory_order_release);
    LOGI("nativeSetUsbDacSettings: NoCIface=%d ForceUac1=%d LinearVol=%d ReplaceVol=%d Force1ms=%d changed=%d",
         newNoCI ? 1 : 0, newFU1 ? 1 : 0, newLV ? 1 : 0,
         newRV ? 1 : 0, newF1 ? 1 : 0, changed ? 1 : 0);

    // 只在实际变更时才需要重新初始化
    if (changed) {
        g_requiresReinit.store(true, std::memory_order_release);
        LOGI("Settings changed -> requiresReinit");
    }
}

// ====================== PCM→DSD 转换设置 ======================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetDsdConversion(
        JNIEnv *env, jobject thiz,
        jboolean enabled,
        jint rate,
        jint type,
        jboolean dither,
        jboolean dop) {
    std::lock_guard<std::recursive_mutex> sessionPolicyLock(gNextSessionPolicyMtx);
    bool newEnabled = enabled == JNI_TRUE;
    int newRate = static_cast<int>(rate);
    int newType = static_cast<int>(type);
    bool newDither = dither == JNI_TRUE;
    const bool requestedDop = dop == JNI_TRUE;
    // DoP / Native DSD are explicit transport choices. Do not silently force
    // realtime PCM->DSD back to Native RAW; the converter and endpoint scorer
    // both support DoP and the USB settings UI exposes it as a compatibility
    // option for DACs that do not accept RAW_DATA.
    bool newDop = newEnabled && requestedDop;

    // 校验 DSD 倍率
    if (newRate != 64 && newRate != 128 && newRate != 256 && newRate != 512 && newRate != 1024) {
        LOGE("nativeSetDsdConversion: invalid rate %d, must be 64/128/256/512/1024", newRate);
        return;
    }

    // 校验转换类型
    if (newType < 0 || newType > 2) {
        LOGE("nativeSetDsdConversion: invalid type %d, must be 0/1/2", newType);
        return;
    }

    if (newEnabled) {
        if (newType != (int) rawsmusic::DsdConversionType::LowLatency) {
            LOGW("nativeSetDsdConversion: realtime PCM->DSD forces LowLatency mode (requested type=%d)",
                 newType);
            newType = (int) rawsmusic::DsdConversionType::LowLatency;
        }
        if (newDither) {
            LOGW("nativeSetDsdConversion: disabling dither for realtime PCM->DSD throughput");
            newDither = false;
        }
    }

    bool changed = (newEnabled != g_dsdConversionEnabled.load(std::memory_order_relaxed)) ||
                   (newRate != g_dsdRate.load(std::memory_order_relaxed)) ||
                   (newType != g_dsdConversionType.load(std::memory_order_relaxed)) ||
                   (newDither != g_dsdDitherEnabled.load(std::memory_order_relaxed)) ||
                   (newDop != g_dsdDopEnabled.load(std::memory_order_relaxed));

    // DSD transport is process-global, while USB contexts and writer calls are handle-scoped.
    // Never change/clear the global converter while a live handle may still be using the old
    // DSD altsetting.  The Kotlin transport transaction must first drain the writer and close
    // the old handle, then call this JNI method before the next nativeInitUsbDevice().
    std::unique_lock<std::shared_mutex> lifecycleWriteLock(gUsbLifecycleMtx);
    if (changed) {
        size_t liveHandleCount = 0;
        size_t quarantinedHandleCount = 0;
        {
            std::lock_guard<std::mutex> registryLock(gRegistryMtx);
            liveHandleCount = gHandleRegistry.liveHandleCountNoLock();
            quarantinedHandleCount = gHandleRegistry.quarantinedHandleCountNoLock();
        }
        if (liveHandleCount != 0 || quarantinedHandleCount != 0) {
            g_requiresReinit.store(true, std::memory_order_release);
            LOGE("nativeSetDsdConversion rejected unsafe session mutation: liveHandles=%zu "
                 "quarantinedHandles=%zu requested enabled=%d rate=DSD%d dop=%d. "
                 "Drain/close USB first; restart process if a session is quarantined.",
                 liveHandleCount, quarantinedHandleCount,
                 newEnabled ? 1 : 0, newRate, newDop ? 1 : 0);
            return;
        }
    }

    g_dsdConversionEnabled.store(newEnabled, std::memory_order_release);
    g_dsdRate.store(newRate, std::memory_order_release);
    g_dsdConversionType.store(newType, std::memory_order_release);
    g_dsdDitherEnabled.store(newDither, std::memory_order_release);
    g_dsdDopEnabled.store(newDop, std::memory_order_release);

    if (changed) {
        clearDsdPreferenceDiagnostics(newEnabled
                ? "nativeSetDsdConversion next session"
                : "nativeSetDsdConversion disabled after USB close");
    }

    LOGI("nativeSetDsdConversion: nextSession enabled=%d rate=DSD%d type=%d dither=%d dop=%d "
         "requestedDop=%d changed=%d liveConverter=none",
         newEnabled ? 1 : 0, newRate, newType, newDither ? 1 : 0, newDop ? 1 : 0,
         requestedDop ? 1 : 0, changed ? 1 : 0);

    if (changed) {
        g_requiresReinit.store(true, std::memory_order_release);
    }
}

// ========================== nativeSetLastGoodProfile ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetLastGoodProfile(
        JNIEnv *env, jobject thiz,
        jint alt, jint sampleRate, jint validBits, jint subslotBytes, jint feedbackEndpoint
) {
    std::lock_guard<std::recursive_mutex> sessionPolicyLock(gNextSessionPolicyMtx);
    g_policyLastGoodAlt.store((int)alt, std::memory_order_release);
    g_policyLastGoodSampleRate.store((int)sampleRate, std::memory_order_release);
    g_policyLastGoodValidBits.store((int)validBits, std::memory_order_release);
    g_policyLastGoodSubslot.store((int)subslotBytes, std::memory_order_release);
    g_policyLastGoodFeedbackEp.store((int)feedbackEndpoint, std::memory_order_release);
    LOGI("nativeSetLastGoodProfile: alt=%d sr=%d bits=%d subslot=%d fb=0x%02X",
         (int)alt, (int)sampleRate, (int)validBits, (int)subslotBytes, (int)feedbackEndpoint);
}

// ========================== nativeSetCompatFlags ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetCompatFlags(
        JNIEnv *env, jobject thiz,
        jboolean noClockSet, jboolean noFeedback, jboolean noFeatureUnit,
        jboolean preferSafeAlt, jboolean safeMode
) {
    std::lock_guard<std::recursive_mutex> sessionPolicyLock(gNextSessionPolicyMtx);
    g_policyNoClockSet.store(noClockSet == JNI_TRUE, std::memory_order_release);
    g_policyNoFeedback.store(noFeedback == JNI_TRUE, std::memory_order_release);
    g_policyNoFeatureUnit.store(noFeatureUnit == JNI_TRUE, std::memory_order_release);
    g_policyPreferSafeAlt.store(preferSafeAlt == JNI_TRUE, std::memory_order_release);
    g_policySafeMode.store(safeMode == JNI_TRUE, std::memory_order_release);
    LOGI("nativeSetCompatFlags: noClock=%d noFeedback=%d noFU=%d safeAlt=%d safeMode=%d",
         noClockSet ? 1 : 0, noFeedback ? 1 : 0, noFeatureUnit ? 1 : 0,
         preferSafeAlt ? 1 : 0, safeMode ? 1 : 0);
    g_requiresReinit.store(true, std::memory_order_release);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetLastPcmInputDiagnosticsString(
        JNIEnv *env, jobject thiz
) {
    (void)thiz;
    std::lock_guard<std::mutex> lastDiagLock(gLastPcmInputDiagMtx);
    if (!gLastPcmInputDiagReady) return env->NewStringUTF("");
    const std::string stats =
            rawsmusic::usb::formatRawUsbStats(gLastPcmInputDiagSnapshot);
    return env->NewStringUTF(stats.c_str());
}

// ========================== nativeGetStatsString ==========================
extern "C"
JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetStatsString(
        JNIEnv *env, jobject thiz, jlong handle
) {
    if (handle == 0) return env->NewStringUTF("");
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!isLiveHandle(ctx)) return env->NewStringUTF("");
    const int64_t nowMs = nowSteadyMs();
    const int64_t windowStart = ctx->statsWindowStartMs.load(std::memory_order_relaxed);
    const int64_t elapsedMs = std::max<int64_t>(1, nowMs - windowStart);
    auto rateOrWindow = [&](int64_t last, int64_t current) -> int64_t {
        if (last > 0) return last;
        return (current * 1000LL) / elapsedMs;
    };

    const int64_t appBps = rateOrWindow(
            ctx->lastAppBytesPerSec.load(std::memory_order_relaxed),
            ctx->statsAppBytes.load(std::memory_order_relaxed));
    const int64_t completedBps = rateOrWindow(
            ctx->lastCompletedUsbBytesPerSec.load(std::memory_order_relaxed),
            ctx->statsCompletedUsbBytes.load(std::memory_order_relaxed));
    const int64_t scheduledBps = rateOrWindow(
            ctx->lastScheduledUsbBytesPerSec.load(std::memory_order_relaxed),
            ctx->statsScheduledUsbBytes.load(std::memory_order_relaxed));

    const int underrun = std::max(
            ctx->lastUnderrun.load(std::memory_order_relaxed),
            ctx->statsUnderrun.load(std::memory_order_relaxed));
    const int submitErr = std::max(
            ctx->lastSubmitError.load(std::memory_order_relaxed),
            ctx->statsSubmitError.load(std::memory_order_relaxed));
    const int pktErr = std::max(
            ctx->lastPacketError.load(std::memory_order_relaxed),
            ctx->statsPacketError.load(std::memory_order_relaxed));
    const int xferErr = std::max(
            ctx->lastXferError.load(std::memory_order_relaxed),
            ctx->statsXferError.load(std::memory_order_relaxed));

    const int fbState = ctx->feedbackState.load(std::memory_order_relaxed);
    const int pacingMode = ctx->pacingMode.load(std::memory_order_relaxed);
    const bool feedbackDrivingPacer = feedbackIsLockedForPacing(ctx) &&
                                      pacingMode == static_cast<int>(UsbPacingMode::ExplicitFeedback);
    const bool audibleAccepted = ctx->audibleAccepted.load(std::memory_order_acquire);
    const bool volumeRouteReady = isAudibleVolumeRouteReady(ctx);
    const int64_t audibleStartMs = ctx->audibleStartMs.load(std::memory_order_acquire);
    const int64_t audibleFirstMs = ctx->audibleFirstCompletionMs.load(std::memory_order_acquire);
    const int64_t audibleAcceptedMs = ctx->audibleAcceptedMs.load(std::memory_order_acquire);
    const int statsClockRate = ctx->clock.clockCommitVerifiedRate > 0
                               ? ctx->clock.clockCommitVerifiedRate
                               : (ctx->clock.deviceSampleRate > 0
                                  ? ctx->clock.deviceSampleRate
                                  : ctx->dacSampleRate.load(std::memory_order_relaxed));
    rawsmusic::usb::RawUsbStatsSnapshot snapshot;
    snapshot.appBps = appBps;
    snapshot.completedBps = completedBps;
    snapshot.scheduledBps = scheduledBps;
    snapshot.expectedBytesPerSecond = ctx->bytes_per_second;
    snapshot.bufferUsed = ringAvailable(ctx);
    snapshot.bufferCapacity = ctx->pcmRingBuffer.size();
    snapshot.underrun = underrun;
    snapshot.submitError = submitErr;
    snapshot.packetError = pktErr;
    snapshot.transferError = xferErr;
    snapshot.clockRate = statsClockRate;
    snapshot.targetRate = ctx->sampleRate;
    snapshot.softwareVolume = ctx->softwareVolume.load(std::memory_order_relaxed);
    snapshot.feedbackDrivingPacer = feedbackDrivingPacer;
    snapshot.sessionId = ctx->streamSessionId.load(std::memory_order_acquire);
    snapshot.feedbackState = fbState;
    snapshot.feedbackValid = ctx->feedbackValidCount.load(std::memory_order_relaxed);
    snapshot.feedbackInvalid = ctx->feedbackInvalidCount.load(std::memory_order_relaxed);
    snapshot.feedbackEmpty = ctx->feedbackEmptyCount.load(std::memory_order_relaxed);
    snapshot.feedbackRateMilli = ctx->feedbackSampleRateMilli.load(std::memory_order_relaxed);
    snapshot.pacingMode = pacingModeName(pacingMode);
    snapshot.pacingModeId = pacingMode;
    snapshot.clockSourceId = ctx->clock.clockEntityId;
    snapshot.clockSelectorId = ctx->clock.clockSelectorId;
    snapshot.clockInterface = ctx->clock.clockAcInterface;
    snapshot.clockVerifiedRate = ctx->clock.clockCommitVerifiedRate;
    snapshot.clockValidityKnown = ctx->clock.clockValidityKnown;
    snapshot.clockValid = ctx->clock.clockValid;
    snapshot.uac1SamplingFrequencyControl = ctx->clock.uac1EndpointHasSamplingFreqControl;
    snapshot.uac1RateDescriptorKnown = ctx->clock.uac1RateDescriptorKnown;
    snapshot.uac1DescriptorRate = ctx->clock.uac1DescriptorRate;
    snapshot.pcmInputDiagReady = ctx->pcmInputDiagReady.load(std::memory_order_acquire);
    if (snapshot.pcmInputDiagReady) {
        snapshot.pcmProtocol = ctx->pcmInputDiagProtocol;
        snapshot.pcmSourceFrameBytes = ctx->pcmInputDiagSourceFrame;
        snapshot.pcmDeviceFrameBytes = ctx->pcmInputDiagDeviceFrame;
        snapshot.pcmAdapter = pcmAdapterName(static_cast<PcmFormatAdapter>(ctx->pcmInputDiagAdapter));
        snapshot.pcmNeedsResample = ctx->pcmInputDiagNeedsResample;
        snapshot.pcmSamples = ctx->pcmInputDiagSamples;
        snapshot.pcmNonSilent = ctx->pcmInputDiagNonSilent;
        snapshot.pcmLowZero = ctx->pcmInputDiagLowZero;
        snapshot.pcmSignExtendedTop = ctx->pcmInputDiagSignExtendedTop;
        snapshot.pcmFirst16Hex = ctx->pcmInputDiagFirst16Hex;
    }
    snapshot.featureUnitPolicy = featureUnitPolicyStateName(ctx->featureUnitPolicyState);
    snapshot.featureUnitPath = ctx->featureUnitVolumePathName
                              ? ctx->featureUnitVolumePathName
                              : "none";
    snapshot.featureUnitResult = ctx->featureUnitValidationResult;
    snapshot.featureUnitRangeVerified = ctx->featureUnitRangeVerified;
    snapshot.featureUnitReadbackVerified = ctx->featureUnitReadbackVerified;
    snapshot.featureUnitReason = ctx->featureUnitPolicyReason
                                 ? ctx->featureUnitPolicyReason
                                 : "unknown";
    snapshot.descriptorMasterVolume = ctx->descriptorHasMasterVolume;
    snapshot.descriptorLeftVolume = ctx->descriptorHasLeftVolume;
    snapshot.descriptorRightVolume = ctx->descriptorHasRightVolume;
    snapshot.effectiveMasterVolume = ctx->hasMasterVolume;
    snapshot.effectiveLeftVolume = ctx->hasLeftVolume;
    snapshot.effectiveRightVolume = ctx->hasRightVolume;
    snapshot.featureUnitSingleVolumeChannel = ctx->featureUnitSingleVolumeChannel;
    snapshot.audibleAccepted = audibleAccepted;
    snapshot.audibleVolumeRouteReady = volumeRouteReady;
    snapshot.audibleStartMs = audibleStartMs;
    snapshot.audibleFirstCompletionMs = audibleFirstMs;
    snapshot.audibleAcceptedMs = audibleAcceptedMs;
    snapshot.audibleAcceptedSessionId = ctx->audibleAcceptedSessionId.load(std::memory_order_acquire);
    snapshot.audibleAcceptedCompletedBytes = ctx->audibleAcceptedCompletedBytes.load(std::memory_order_acquire);
    snapshot.isoSubmittedTransfers = ctx->isoSubmittedTransfers.load(std::memory_order_relaxed);
    snapshot.isoCompletedTransfers = ctx->isoCompletedTransfers.load(std::memory_order_relaxed);
    snapshot.isoMaxInFlightTransfers = ctx->isoMaxInFlightTransfers.load(std::memory_order_relaxed);
    snapshot.isoSubmittedBytes = ctx->isoSubmittedBytes.load(std::memory_order_relaxed);
    snapshot.isoActualLengthBytes = ctx->isoActualLengthBytes.load(std::memory_order_relaxed);
    snapshot.isoZeroActualPackets = ctx->isoZeroActualPackets.load(std::memory_order_relaxed);
    snapshot.isoCompletedStatusPackets = ctx->isoCompletedStatusPackets.load(std::memory_order_relaxed);
    snapshot.isoErroredStatusPackets = ctx->isoErroredStatusPackets.load(std::memory_order_relaxed);
    snapshot.isoCancelledStatusPackets = ctx->isoCancelledStatusPackets.load(std::memory_order_relaxed);
    snapshot.isoOtherStatusPackets = ctx->isoOtherStatusPackets.load(std::memory_order_relaxed);
    snapshot.isoMaxCallbackGapMs = ctx->isoMaxCallbackGapMs.load(std::memory_order_relaxed);
    const int gapCount = ctx->isoCallbackGapCount.load(std::memory_order_relaxed);
    snapshot.isoAverageCallbackGapMs = gapCount > 0
                                       ? static_cast<int>(
                                               ctx->isoCallbackGapTotalMs.load(std::memory_order_relaxed) /
                                               std::max(1, gapCount))
                                       : 0;
    snapshot.resetAltAttempts = ctx->resetAltAttempts.load(std::memory_order_relaxed);
    snapshot.resetAltLastResult = ctx->resetAltLastResult.load(std::memory_order_relaxed);
    snapshot.resetAltSelectedLastResult = ctx->resetAltSelectedLastResult.load(std::memory_order_relaxed);
    snapshot.silentProbeAttempted = ctx->silentProbeAttempted.load(std::memory_order_relaxed);
    snapshot.silentProbeSubmitResult = ctx->silentProbeSubmitResult.load(std::memory_order_relaxed);
    snapshot.silentProbeCompleted = ctx->silentProbeCompleted.load(std::memory_order_relaxed);
    snapshot.silentProbeTransferStatus = ctx->silentProbeTransferStatus.load(std::memory_order_relaxed);
    snapshot.silentProbeScheduledLength = ctx->silentProbeScheduledLength.load(std::memory_order_relaxed);
    snapshot.silentProbeActualLength = ctx->silentProbeActualLength.load(std::memory_order_relaxed);
    snapshot.silentProbeZeroActualPackets = ctx->silentProbeZeroActualPackets.load(std::memory_order_relaxed);
    snapshot.silentProbePacketErrors = ctx->silentProbePacketErrors.load(std::memory_order_relaxed);
    const std::string stats = rawsmusic::usb::formatRawUsbStats(snapshot);
    return env->NewStringUTF(stats.c_str());
}


// ========================== isHardwareVolumeValidated ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_isHardwareVolumeValidated(
        JNIEnv *env, jobject thiz
) {
    // Check if any live handle has validated hardware volume
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    for (auto *h: gLiveHandles) {
        if (h && h->hardwareVolumeEnabled && h->hardwareVolumeSafe) {
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}


// ========================== nativeSetPcmOutputMode ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetPcmOutputMode(
        JNIEnv* env, jobject thiz, jint mode) {
    std::lock_guard<std::recursive_mutex> sessionPolicyLock(gNextSessionPolicyMtx);
    const UsbPcmOutputMode sanitized = sanitizeUsbPcmOutputMode(static_cast<int>(mode));
    const int m = static_cast<int>(sanitized);
    g_usbPcmOutputMode.store(m, std::memory_order_release);
    LOGI("nativeSetPcmOutputMode: mode=%s(%d)",
         usbPcmOutputModeName(sanitized), m);
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetDeviceCapabilitiesJson(
        JNIEnv* env, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return env->NewStringUTF("");
    if (ctx->capabilitiesJson.empty()) {
        const UsbCapabilitiesActiveFormat format{
                ctx->sampleRate,
                ctx->channels,
                ctx->bitDepth,
                ctx->bytesPerSample,
                ctx->interfaceNumber,
                ctx->altSetting,
                static_cast<int>(ctx->epAddress),
                static_cast<int>(ctx->feedbackEpAddress),
                true,
                false,
                static_cast<int>(ctx->protocol),
                ctx->clock.uac1EndpointHasSamplingFreqControl,
                ctx->protocol != USB_AUDIO_UAC1 ||
                    ctx->clock.uac1EndpointHasSamplingFreqControl,
        };
        const std::string json = buildUsbCapabilitiesJsonForActiveFormat(
                UsbCapabilitiesDevice{ctx->deviceName, ctx->vendorId, ctx->productId},
                format);
        return env->NewStringUTF(json.c_str());
    }
    return env->NewStringUTF(ctx->capabilitiesJson.c_str());
}

// ========================== nativeProbeStandardHardwareControlsJson ==========================
// Phase 2 read-only Hardware Device Control probe. The transport callback only
// emits class/interface IN requests; no SET_CUR/write API is reachable here.
// Serialize it with the existing Feature Unit control lane so an explicit UI
// probe cannot race hardware-volume EP0 traffic.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeProbeStandardHardwareControlsJson(
        JNIEnv* env, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle) return env->NewStringUTF("");
    if (ctx->protocol != USB_AUDIO_UAC1 && ctx->protocol != USB_AUDIO_UAC2) {
        return env->NewStringUTF("");
    }

    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    const std::string json = rawsmusic::usb::probeStandardUsbControlsJson(
            ctx->devHandle,
            ctx->controlTopology,
            ctx->terminalLink,
            ctx->protocol,
            ctx->vendorId,
            ctx->productId,
            ctx->deviceName,
            300);
    return env->NewStringUTF(json.c_str());
}



// ========================== nativeProbeVendorControlInventoryJson ==========================
// Safe vendor-control inventory. UAC/XU/interface/endpoint discovery is descriptor-only.
// HID interfaces additionally receive the standard read-only GET_DESCRIPTOR(Report) request
// so Report IDs/payload sizes can be fingerprinted. No class/vendor SET or bulk I/O is issued.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeProbeVendorControlInventoryJson(
        JNIEnv* env, jobject, jlong handle) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle) return env->NewStringUTF("");

    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    const std::string json = rawsmusic::usb::probeUsbVendorControlInventoryJson(
            ctx->devHandle,
            ctx->controlTopology,
            ctx->vendorId,
            ctx->productId,
            ctx->deviceName);
    return env->NewStringUTF(json.c_str());
}



// ========================== Phase 4B bounded USB vendor transport ==========================
// These JNI entry points are reachable only through a selected UsbVendorDeviceAdapter. Native
// still validates every target against the current live device descriptors before issuing I/O.
static jbyteArray rawUsbVendorIoDataToJni(JNIEnv* env, const rawsmusic::usb::RawUsbVendorIoResult& result) {
    if (result.code < 0) return nullptr;
    jbyteArray array = env->NewByteArray(static_cast<jsize>(result.data.size()));
    if (!array) return nullptr;
    if (!result.data.empty()) {
        env->SetByteArrayRegion(
                array,
                0,
                static_cast<jsize>(result.data.size()),
                reinterpret_cast<const jbyte*>(result.data.data()));
    }
    return array;
}

static std::vector<uint8_t> rawUsbVendorJniBytes(JNIEnv* env, jbyteArray data) {
    if (!data) return {};
    const jsize length = env->GetArrayLength(data);
    if (length <= 0 || length > 65536) return {};
    std::vector<uint8_t> bytes(static_cast<size_t>(length));
    env->GetByteArrayRegion(data, 0, length, reinterpret_cast<jbyte*>(bytes.data()));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return {};
    }
    return bytes;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeVendorExtensionUnitRead(
        JNIEnv* env, jobject, jlong handle,
        jint interfaceNumber, jint entityId, jint selector, jint channel,
        jint length, jint timeoutMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle ||
        interfaceNumber < 0 || interfaceNumber > 255 || entityId <= 0 || entityId > 255 ||
        selector <= 0 || selector > 255 || channel < 0 || channel > 255 ||
        length <= 0 || length > 4096) return nullptr;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    const auto result = rawsmusic::usb::vendorExtensionUnitRead(
            ctx->devHandle, ctx->controlTopology, ctx->protocol,
            static_cast<uint8_t>(interfaceNumber), static_cast<uint8_t>(entityId),
            static_cast<uint8_t>(selector), static_cast<uint8_t>(channel),
            static_cast<uint16_t>(length), static_cast<unsigned>(std::clamp(timeoutMs, 50, 5000)));
    return rawUsbVendorIoDataToJni(env, result);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeVendorExtensionUnitWrite(
        JNIEnv* env, jobject, jlong handle,
        jint interfaceNumber, jint entityId, jint selector, jint channel,
        jbyteArray data, jint timeoutMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    const auto bytes = rawUsbVendorJniBytes(env, data);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle || bytes.empty() || bytes.size() > 4096 ||
        interfaceNumber < 0 || interfaceNumber > 255 || entityId <= 0 || entityId > 255 ||
        selector <= 0 || selector > 255 || channel < 0 || channel > 255) return LIBUSB_ERROR_INVALID_PARAM;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    return rawsmusic::usb::vendorExtensionUnitWrite(
            ctx->devHandle, ctx->controlTopology, ctx->protocol,
            static_cast<uint8_t>(interfaceNumber), static_cast<uint8_t>(entityId),
            static_cast<uint8_t>(selector), static_cast<uint8_t>(channel),
            bytes.data(), static_cast<uint16_t>(bytes.size()),
            static_cast<unsigned>(std::clamp(timeoutMs, 50, 5000))).code;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeVendorHidGetReport(
        JNIEnv* env, jobject, jlong handle,
        jint interfaceNumber, jint reportType, jint reportId, jint length, jint timeoutMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle ||
        interfaceNumber < 0 || interfaceNumber > 255 || reportType < 0 || reportType > 255 ||
        reportId < 0 || reportId > 255 || length <= 0 || length > 4096) return nullptr;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    const auto result = rawsmusic::usb::vendorHidGetReport(
            ctx->devHandle, static_cast<uint8_t>(interfaceNumber), static_cast<uint8_t>(reportType),
            static_cast<uint8_t>(reportId), static_cast<uint16_t>(length),
            static_cast<unsigned>(std::clamp(timeoutMs, 50, 5000)));
    return rawUsbVendorIoDataToJni(env, result);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeVendorHidSetReport(
        JNIEnv* env, jobject, jlong handle,
        jint interfaceNumber, jint reportType, jint reportId, jbyteArray data, jint timeoutMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    const auto bytes = rawUsbVendorJniBytes(env, data);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle || bytes.empty() || bytes.size() > 4096 ||
        interfaceNumber < 0 || interfaceNumber > 255 || reportType < 0 || reportType > 255 ||
        reportId < 0 || reportId > 255) return LIBUSB_ERROR_INVALID_PARAM;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    return rawsmusic::usb::vendorHidSetReport(
            ctx->devHandle, static_cast<uint8_t>(interfaceNumber), static_cast<uint8_t>(reportType),
            static_cast<uint8_t>(reportId), bytes.data(), static_cast<uint16_t>(bytes.size()),
            static_cast<unsigned>(std::clamp(timeoutMs, 50, 5000))).code;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeVendorControlIn(
        JNIEnv* env, jobject, jlong handle,
        jboolean deviceRecipient, jint interfaceNumber, jint request,
        jint value, jint index, jint length, jint timeoutMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle || interfaceNumber < 0 || interfaceNumber > 255 ||
        request < 0 || request > 255 || value < 0 || value > 65535 || index < 0 || index > 65535 ||
        length <= 0 || length > 4096) return nullptr;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    const auto result = rawsmusic::usb::vendorControlTransfer(
            ctx->devHandle, true, deviceRecipient == JNI_TRUE,
            static_cast<uint8_t>(interfaceNumber), static_cast<uint8_t>(request),
            static_cast<uint16_t>(value), static_cast<uint16_t>(index), nullptr,
            static_cast<uint16_t>(length), static_cast<unsigned>(std::clamp(timeoutMs, 50, 5000)));
    return rawUsbVendorIoDataToJni(env, result);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeVendorControlOut(
        JNIEnv* env, jobject, jlong handle,
        jboolean deviceRecipient, jint interfaceNumber, jint request,
        jint value, jint index, jbyteArray data, jint timeoutMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    const auto bytes = rawUsbVendorJniBytes(env, data);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle || bytes.size() > 4096 ||
        interfaceNumber < 0 || interfaceNumber > 255 || request < 0 || request > 255 ||
        value < 0 || value > 65535 || index < 0 || index > 65535) return LIBUSB_ERROR_INVALID_PARAM;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    return rawsmusic::usb::vendorControlTransfer(
            ctx->devHandle, false, deviceRecipient == JNI_TRUE,
            static_cast<uint8_t>(interfaceNumber), static_cast<uint8_t>(request),
            static_cast<uint16_t>(value), static_cast<uint16_t>(index),
            bytes.empty() ? nullptr : bytes.data(), static_cast<uint16_t>(bytes.size()),
            static_cast<unsigned>(std::clamp(timeoutMs, 50, 5000))).code;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeVendorBulkIn(
        JNIEnv* env, jobject, jlong handle,
        jint interfaceNumber, jint endpointAddress, jint length, jint timeoutMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle || interfaceNumber < 0 || interfaceNumber > 255 ||
        endpointAddress < 0 || endpointAddress > 255 || length <= 0 || length > 65536) return nullptr;
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    const auto result = rawsmusic::usb::vendorBulkTransfer(
            ctx->devHandle, static_cast<uint8_t>(interfaceNumber), static_cast<uint8_t>(endpointAddress),
            nullptr, length, static_cast<unsigned>(std::clamp(timeoutMs, 50, 5000)));
    return rawUsbVendorIoDataToJni(env, result);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeVendorBulkOut(
        JNIEnv* env, jobject, jlong handle,
        jint interfaceNumber, jint endpointAddress, jbyteArray data, jint timeoutMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    const auto bytes = rawUsbVendorJniBytes(env, data);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle || bytes.empty() || bytes.size() > 65536 ||
        interfaceNumber < 0 || interfaceNumber > 255 || endpointAddress < 0 || endpointAddress > 255) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    return rawsmusic::usb::vendorBulkTransfer(
            ctx->devHandle, static_cast<uint8_t>(interfaceNumber), static_cast<uint8_t>(endpointAddress),
            bytes.data(), static_cast<int>(bytes.size()),
            static_cast<unsigned>(std::clamp(timeoutMs, 50, 5000))).code;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeVendorEndpointWrite(
        JNIEnv* env, jobject, jlong handle,
        jint interfaceNumber, jint outEndpointAddress, jint inEndpointAddress,
        jbyteArray request, jint timeoutMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    const auto bytes = rawUsbVendorJniBytes(env, request);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle || bytes.empty() || bytes.size() > 65536 ||
        interfaceNumber < 0 || interfaceNumber > 255 || outEndpointAddress < 0 || outEndpointAddress > 255 ||
        inEndpointAddress < 0 || inEndpointAddress > 255) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    return rawsmusic::usb::vendorEndpointWrite(
            ctx->devHandle, static_cast<uint8_t>(interfaceNumber),
            static_cast<uint8_t>(outEndpointAddress), static_cast<uint8_t>(inEndpointAddress),
            bytes.data(), static_cast<int>(bytes.size()),
            static_cast<unsigned>(std::clamp(timeoutMs, 50, 5000))).code;
}

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeVendorEndpointExchange(
        JNIEnv* env, jobject, jlong handle,
        jint interfaceNumber, jint outEndpointAddress, jint inEndpointAddress,
        jbyteArray request, jint responseLength, jint turnaroundDelayMs, jint timeoutMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    const auto bytes = rawUsbVendorJniBytes(env, request);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle || bytes.empty() || bytes.size() > 65536 ||
        responseLength <= 0 || responseLength > 65536 || turnaroundDelayMs < 0 || turnaroundDelayMs > 1000 ||
        interfaceNumber < 0 || interfaceNumber > 255 || outEndpointAddress < 0 || outEndpointAddress > 255 ||
        inEndpointAddress < 0 || inEndpointAddress > 255) {
        return nullptr;
    }
    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    const auto result = rawsmusic::usb::vendorEndpointExchange(
            ctx->devHandle, static_cast<uint8_t>(interfaceNumber),
            static_cast<uint8_t>(outEndpointAddress), static_cast<uint8_t>(inEndpointAddress),
            bytes.data(), static_cast<int>(bytes.size()), responseLength,
            static_cast<unsigned>(turnaroundDelayMs),
            static_cast<unsigned>(std::clamp(timeoutMs, 50, 5000)));
    return rawUsbVendorIoDataToJni(env, result);
}

// ========================== nativeWriteStandardHardwareControlJson ==========================
// Phase 3 Hardware Device Control write lane. Standard UAC SET_CUR is kept
// behind the same EP0 mutex as Feature Unit volume/probe traffic. The helper
// validates descriptor access, performs one write, then a CUR readback.
extern "C"
JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeWriteStandardHardwareControlJson(
        JNIEnv* env, jobject, jlong handle,
        jint interfaceNumber, jint entityId, jint selector, jint channel,
        jint elementIndex, jdouble requestedValue) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx) || !ctx->devHandle) return env->NewStringUTF("");
    if (ctx->protocol != USB_AUDIO_UAC1 && ctx->protocol != USB_AUDIO_UAC2) {
        return env->NewStringUTF("");
    }

    if (interfaceNumber < 0 || interfaceNumber > 255 || entityId <= 0 || entityId > 255 ||
        selector <= 0 || selector > 255 || channel < 0 || channel > 255) {
        rawsmusic::usb::RawUsbStandardWriteResult invalid;
        invalid.status = rawsmusic::usb::RawUsbStandardWriteStatus::InvalidAddress;
        invalid.reason = "jni_address_out_of_range";
        const std::string json = rawsmusic::usb::formatStandardUsbWriteResultJson(invalid);
        return env->NewStringUTF(json.c_str());
    }

    std::lock_guard<std::mutex> controlLock(ctx->featureUnitControlMutex);
    const auto result = rawsmusic::usb::writeStandardUsbControl(
            ctx->devHandle,
            ctx->controlTopology,
            ctx->protocol,
            static_cast<uint8_t>(interfaceNumber),
            static_cast<uint8_t>(entityId),
            static_cast<uint8_t>(selector),
            static_cast<uint8_t>(channel),
            static_cast<int>(elementIndex),
            static_cast<double>(requestedValue),
            350);
    const std::string json = rawsmusic::usb::formatStandardUsbWriteResultJson(result);
    return env->NewStringUTF(json.c_str());
}

// ========================== nativeArmStopFade ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeArmStopFade(
        JNIEnv*, jobject, jlong handle, jint fadeMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return;
    const int safeFadeMs = std::clamp((int)fadeMs, 3, 50);
    const UsbTransitionGainOwner owner = getTransitionGainOwner(ctx);
    if (usesSessionPcmTransitionEnvelope(ctx)) {
        armSessionEnvelopeInternal(ctx, 0.0f, safeFadeMs);
        LOGI("nativeArmStopFade: owner=SessionPcm fadeOut=%dms", safeFadeMs);
        return;
    }
    if (owner == UsbTransitionGainOwner::TransportSilence) {
        armTrackStopFadeInternal(ctx, safeFadeMs, "jni_arm_stop_transport_silence");
        LOGI("nativeArmStopFade: owner=TransportSilence fadeOut=%dms", safeFadeMs);
        return;
    }
    if (owner == UsbTransitionGainOwner::UnityPcm) {
        LOGI("nativeArmStopFade: owner=UnityPcm bypass");
        return;
    }
    int sr = ctx->sampleRate > 0 ? ctx->sampleRate : 44100;
    int samples = sr * safeFadeMs / 1000;
    ctx->fadeSamplesRemaining = samples;
    ctx->fadeTotalSamples = -samples;
    LOGI("nativeArmStopFade: owner=Legacy fadeOut=%dms samples=%d", safeFadeMs, samples);
}

// ========================== nativeArmTrackStopFade ==========================
// 手动切歌专用淡出：在 flush 前先将输出淡到 0，避免 PCM 断点爆音
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeArmTrackStopFade(
        JNIEnv*, jobject, jlong handle, jint fadeMs) {
    std::shared_lock<std::shared_mutex> lifecycleReadLock(gUsbLifecycleMtx);
    auto* ctx = resolveLiveHandle(handle);
    if (!ctx || !isLiveHandle(ctx)) return;
    const int safeFadeMs = std::clamp((int)fadeMs, 3, 80);
    const UsbTransitionGainOwner owner = getTransitionGainOwner(ctx);
    if (usesSessionPcmTransitionEnvelope(ctx)) {
        armSessionEnvelopeInternal(ctx, 0.0f, safeFadeMs);
        LOGI("nativeArmTrackStopFade: owner=SessionPcm fadeOut=%dms", safeFadeMs);
    } else if (owner == UsbTransitionGainOwner::UnityPcm) {
        LOGI("nativeArmTrackStopFade: owner=UnityPcm bypass");
    } else {
        armTrackStopFadeInternal(ctx, safeFadeMs, "jni_arm_track_stop_fade");
    }
}

// ========================== applyStopFade ==========================
// 在 fillIsoTransfer 输出路径中调用：将 PCM 递减到 0
static void applyStopFade(UsbAudioContext* ctx, uint8_t* data, int bytes) {
    if (!ctx || !ctx->stopFadeActive.load(std::memory_order_acquire)) return;
    UsbStopFadeState state{
            ctx->stopFadeSamplesRemaining.load(std::memory_order_acquire),
            ctx->stopFadeTotalSamples.load(std::memory_order_acquire),
            ctx->stopFadeActive.load(std::memory_order_acquire),
    };
    applyUsbStopFade(
            state,
            data,
            bytes,
            ctx->deviceChannels,
            ctx->deviceBitDepth,
            ctx->deviceSubslotSize);
    ctx->stopFadeSamplesRemaining.store(
            std::max(0, state.samplesRemaining), std::memory_order_release);
    ctx->stopFadeActive.store(state.active, std::memory_order_release);
}

static void resetStreamingStateForFreshTrack(UsbAudioContext* ctx) {
    if (!ctx) return;
    ctx->pcmWritePos.store(0, std::memory_order_release);
    ctx->pcmReadPos.store(0, std::memory_order_release);
    resetUsbRuntimeStats(ctx);
    ctx->adaptiveRate.integralError = 0.0;
    ctx->adaptiveRate.correction = 0.0;
    if (ctx->swrCtx) { swr_close(ctx->swrCtx); swr_init(ctx->swrCtx); }
    ctx->fadeSamplesRemaining = 0;
    ctx->fadeTotalSamples = 0;
    LOGI("resetStreamingStateForFreshTrack: cleared");
}
