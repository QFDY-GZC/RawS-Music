#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <atomic>
#include <thread>
#include <mutex>
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

#include "libusb.h"

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

// ==========================
// Handle registry to prevent double-free
// ==========================
static std::mutex gRegistryMtx;
static std::unordered_set<UsbAudioContext*> gLiveHandles;

static void registerHandle(UsbAudioContext* h) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    gLiveHandles.insert(h);
    LOGI("registerHandle: %p live count=%zu", h, gLiveHandles.size());
}

static bool unregisterHandle(UsbAudioContext* h) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    auto it = gLiveHandles.find(h);
    if (it == gLiveHandles.end()) {
        LOGW("unregisterHandle: %p not in live set", h);
        return false;
    }
    gLiveHandles.erase(it);
    LOGI("unregisterHandle: %p live count=%zu", h, gLiveHandles.size());
    return true;
}

static bool isLiveHandle(UsbAudioContext* h) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    return gLiveHandles.find(h) != gLiveHandles.end();
}

// 稳定优先：16 transfers × 8 packets = 128 microframes in flight
static constexpr int NUM_TRANSFERS = 16;
static constexpr int ISO_PACKETS_PER_XFER = 8;

#define ERR_NOT_INITIALIZED   -1001
#define ERR_NOT_RUNNING       -1003
#define ERR_TRANSPORT_LOST    -1004
#define ERR_USB_IO            -1005
#define ERR_START_FAILED      -1010

// ==========================
// USB Audio Protocol
// ==========================
enum UsbAudioProtocol {
    USB_AUDIO_UNKNOWN = 0,
    USB_AUDIO_UAC1 = 1,
    USB_AUDIO_UAC2 = 2,
};

// ==========================
// USB 播放模式
// ==========================
enum class UsbPlaybackMode {
    SafeSoftwareVolume = 0,        // 默认安全模式：软件音量，不碰 Feature Unit
    ExclusiveSoftwareVolume = 1,   // USB 独占，但非完美比特，软件音量
    ExclusiveBitPerfectHwVol = 2,  // USB 独占 + 完美比特 + 硬件音量（Feature Unit 已验证安全）
    ExclusiveBitPerfectFixed = 3   // USB 独占 + 完美比特 + 固定音量（无 FU 或 FU 不安全）
};

// ==========================
// PCM 格式无损适配
// ==========================
enum PcmFormatAdapter {
    PCM_ADAPTER_NONE = 0,
    // 无损整数扩展，低位补 0
    PCM_ADAPTER_S16_TO_S24,
    PCM_ADAPTER_S16_TO_S32,
    PCM_ADAPTER_S24_TO_S32,
    // 无损截断，丢弃低位
    PCM_ADAPTER_S32_TO_S24,
    // 不支持
    PCM_ADAPTER_UNSUPPORTED
};

// ==========================
// ISO Pacer: frame accumulator for dynamic ISO packet length
// ==========================
// 44100Hz/16bit/stereo (HS): 5 or 6 frames per microframe → 20 or 24 bytes, avg 22.05
// 48000Hz/16bit/stereo (HS): 6 frames per microframe → 24 bytes, stable
// 96000Hz/24bit/stereo (HS): 12 frames per microframe → 72 bytes, stable
// ==========================
struct IsoPacer {
    double frameAccumulator = 0.0;
    double sampleRate = 44100.0;    // 标称采样率，可被 PI 控制器微调
    uint32_t intervalsPerSec = 8000;
    uint32_t frameSize = 4;         // channels * bytesPerSample
    int maxPacketSize = 512;        // endpoint max packet size (byte limit)

    void reset(uint32_t sr, uint32_t ips, uint32_t fs, int maxPkt) {
        frameAccumulator = 0.0;
        sampleRate = (double)sr;
        intervalsPerSec = ips;
        frameSize = fs;
        maxPacketSize = maxPkt;
    }
};

// ==========================
// USB Audio 上下文
// ==========================
struct UsbAudioContext {
    std::mutex handleMutex;  // per-handle lock: protect close/start/stop from concurrent access
    libusb_context *libusbCtx = nullptr;
    libusb_device_handle *devHandle = nullptr;

    int interfaceNumber = 1;
    int altSetting = 1;
    uint8_t epAddress = 0x01;

    uint16_t vendorId = 0;    // USB VID，用于已知设备时钟查询
    uint16_t productId = 0;   // USB PID

    int sampleRate = 44100;
    int channels = 2;
    int bitDepth = 16;
    int bytesPerSample = 2;
    int bytesPerFrame = 4;

    int maxPacketSize = 0;
    int uFrameSize = 0;
    bool isFullSpeed = true;

    // ISO 端点服务间隔（来自 bInterval）
    int endpointInterval = 1;           // 默认 1ms (Full-Speed) 或 125μs (High-Speed bInterval=1)
    int serviceIntervalsPerSecond = 1000; // 每秒服务次数
    int bytesPerServiceInterval = 0;  // 每次服务应传输的字节数

    // ISO Pacer: frame accumulator for dynamic packet length
    IsoPacer isoPacer;

    // 实际 ISO 传输参数（基于采样率计算，非端点 maxPacketSize）
    int bytesPerPacket = 0;      // 每个微帧平均字节数 (e.g. 24 for 48kHz/stereo/16bit)
    int transferSize = 0;        // 单次 transfer 的最大字节数（按 maxPktPerPacket 计算）

    // Bytes-per-second tracking (for feedback and stats)
    uint64_t bytes_per_second = 0;      // sampleRate * frameSize (e.g. 192000 for 48k/16bit/stereo)
    // Feedback smoothed rate (updated by feedbackCallback, read by stats)
    std::atomic<uint64_t> bytes_per_second_smoothed{0};
    // 连续 feedback 空包计数，超过阈值自动禁用 feedback
    std::atomic<int> feedbackEmptyCount{0};

    // 异步传输
    struct libusb_transfer *transfers[NUM_TRANSFERS] = {};
    uint8_t *transferBuffers[NUM_TRANSFERS] = {};
    int numIsoPackets = 8;

    std::atomic<bool> initialized{false};  // nativeInit 成功完成，ring buffer 可写
    std::atomic<bool> streaming{false};     // USB iso transfer 已开始，callback 开始消费 ring buffer
    std::atomic<bool> stopping{false};      // 正在停止
    std::atomic<bool> closing{false};       // 正在关闭，拒绝 nativeWrite/nativeStart
    std::atomic<bool> acceptingWrites{false}; // 是否接受 nativeWrite 调用
    std::atomic<int> pendingTransfers{0};   // submit++ / done--  (ISO OUT)
    std::atomic<int> pendingFeedbackTransfers{0}; // feedback transfer pending count
    std::atomic<float> softwareVolume{1.0f};  // per-handle 软件音量，nativeSetVolume 时同步
    std::thread eventThread;
    std::atomic<bool> eventThreadRunning{false};

    // ISO transfer user data（在 callback 中使用自己的 ctx，避免切歌时误用 g_usbCtx）
    struct IsoUserData {
        UsbAudioContext* ctx = nullptr;
        int index = 0;
    };
    IsoUserData isoUserData[NUM_TRANSFERS];

    // 环形缓冲区
    std::vector<uint8_t> pcmRingBuffer;
    std::atomic<size_t> pcmWritePos{0};
    std::atomic<size_t> pcmReadPos{0};

    // 反馈
    struct libusb_transfer *feedbackTransfer = nullptr;
    uint8_t *feedbackBuffer = nullptr;
    uint8_t feedbackEpAddress = 0;
    std::atomic<int> dacSampleRate{48000};
    std::atomic<double> feedbackRate{1.0};

    // PID 流量控制：建议 Java 层写入延迟（微秒）
    std::atomic<int> writeThrottleUs{5000}; // 默认 5ms

    // 停止同步
    std::mutex stopMutex;
    std::condition_variable stopCV;

    // 关闭同步：等待所有 pending transfer callback 完成
    std::mutex closeMutex;
    std::condition_variable closeCV;

    // 环形缓冲区锁（保护 pcmRingBuffer、pcmWritePos、pcmReadPos）
    std::mutex ringMutex;

    // Statistics (atomic counters, logged periodically — never per-packet in callback)
    std::atomic<int64_t> statsAppBytes{0};
    std::atomic<int64_t> statsUsbBytes{0};
    std::atomic<int> statsUnderrun{0};
    std::atomic<int> statsCallbackCount{0};
    std::atomic<int> statsPacketCount{0};   // 总 ISO packet 数，用于计算 avgPacketBytes 和 packetsPerSec
    std::atomic<int> statsSubmitError{0};
    std::atomic<int> statsPacketError{0};
    std::atomic<int> statsXferError{0};

    // Starvation recovery
    bool starved = false;
    int starvedRecoveryBytes = 0; // 恢复时需要的 buffer 水位（字节）

    // 淡入恢复：从静音过渡到音频时的渐进式音量恢复
    int fadeSamplesRemaining = 0;   // 剩余需要淡入的样本数
    int fadeTotalSamples = 0;       // 本次淡入总样本数（用于计算渐进比例）
    bool startupSilenceDone = false; // 预缓冲静音是否已被消费过

    // Ownership tracking
    bool claimedInJava = false;
    bool claimDoneByNative = false;
    bool acInterfaceClaimed = false;
    int javaFd = -1;
    int dupFd = -1;

    // USB Audio protocol & topology info (set during nativeInit from bestCandidate)
    UsbAudioProtocol protocol = USB_AUDIO_UNKNOWN;
    uint8_t terminalLink = 0;
    bool uac1EpHasSamplingFreqControl = false;
    uint8_t playbackFeatureUnitId = 0;
    uint8_t playbackFeatureAcInterface = 0;

    // 播放策略快照（从全局 g_* 原子变量在 nativeInit 时拷贝）
    bool usbExclusiveActive = false;
    bool bitPerfectEnabled = false;
    bool hardwareFeatureUnitRequested = false;  // 用户请求启用硬件音量
    // Feature Unit 实际状态（init 时探测+验证）
    bool featureUnitPresent = false;            // 描述符中有 Feature Unit
    bool hardwareVolumeCapable = false;         // FU 有 volume control
    bool hardwareVolumeSafe = false;            // L/R 验证一致
    bool hardwareVolumeEnabled = false;         // 最终决定：硬件音量是否生效
    bool hasMasterVolume = false;
    bool hasLeftVolume = false;
    bool hasRightVolume = false;
    int16_t volMinRaw = 0;
    int16_t volMaxRaw = 0;
    int16_t volResRaw = 0;
    // 最终计算出的播放模式
    UsbPlaybackMode playbackMode = UsbPlaybackMode::SafeSoftwareVolume;
    // 旧字段兼容（部分代码仍引用）
    bool exclusiveActive = false;               // = usbExclusiveActive
    bool hardwareFeatureUnitEnabled = false;    // = hardwareVolumeEnabled
    bool featureUnitAvailable = false;          // = featureUnitPresent && hardwareVolumeCapable
    bool featureUnitHasMasterVolume = false;    // = hasMasterVolume
    bool masterChannelExists = false;           // master 通道物理存在 (GET_RANGE 成功)，但可能不可靠
    float volumeMinDb = 0.0f;
    float volumeMaxDb = 0.0f;

    // 策略驱动设备特调标志（从 UsbDevicePolicy 在 nativeInit 时拷贝）
    bool policyForceNoControlIface = false;  // 跳过 AC interface claim
    bool policyForceSoftwareVolume = false;  // 强制软件音量
    bool policySkipClockConfig = false;      // 跳过 UAC2 SET_CUR 时钟配置
    bool policyIgnoreClockControl = false;   // 完全忽略时钟控制（SET_CUR/GET_CUR/GET_RANGE 均跳过）
    bool feedbackDegraded = false;           // feedback 端点已降级到 PI 控制器

    // Java 输入 PCM 格式
    int sourceSampleRate = 0;
    int sourceChannels = 0;
    int sourceBitDepth = 0;
    int sourceBytesPerSample = 0;
    int sourceBytesPerFrame = 0;
    // USB 端实际选择的设备格式
    int deviceChannels = 0;
    int deviceBitDepth = 0;
    int deviceSubslotSize = 0;
    int deviceBytesPerSample = 0;
    int deviceBytesPerFrame = 0;
    // 当前 PCM 适配方式
    PcmFormatAdapter pcmAdapter = PCM_ADAPTER_NONE;

    // 重采样状态 (libswresample)
    SwrContext *swrCtx = nullptr;           // 重采样上下文，nullptr 表示不需要重采样
    bool needsResample = false;             // 源采样率 != 设备采样率
    // 重采样输出缓冲区
    std::vector<uint8_t> swrOutBuffer;      // 重采样输出临时缓冲区
    size_t swrOutBufferSize = 0;            // 当前分配的缓冲区大小（字节）

    // USB transport fatal fast-fail (NO_DEVICE / ERROR_IO / etc.)
    std::atomic<bool> transportLost{false};
    std::atomic<int> fatalError{0};   // 0=none, ERR_TRANSPORT_LOST / ERR_USB_IO

    // 预缓冲阈值（字节）
    size_t prebufferBytes = 0;

    // ===== PI 自适应速率控制器 =====
    // 当 feedback 端点不可用时，基于缓冲区水位趋势自动微调发送速率
    // 核心原理：缓冲区在缩小 → DAC 消费比发送快 → 增加发送速率（反之亦然）
    struct AdaptiveRateController {
        bool active = false;            // feedback 失效后自动启用
        double targetFillRatio = 0.40;  // 目标缓冲区填充率 40%（增大以减少抖动）
        double Kp = 0.002;             // 比例增益：原 0.0002 太保守，修正量不足
        double Ki = 0.0002;            // 积分增益：原 0.00002 太保守，无法累积足够修正
        double deadZone = 0.02;        // 死区：填充率偏差 < 2% 时不修正
        double integralError = 0.0;    // 累积误差（积分项）
        double maxCorrection = 0.005;  // 最大修正幅度 ±0.5%（原 0.1% 不足以驱动缓冲区）
        double correction = 0.0;       // 当前修正因子（供 log 用）
        size_t prevBufUsed = 0;        // 上一秒的缓冲区水位
        int stableCount = 0;           // 连续稳定计数（水位变化 < 1%）
    } adaptiveRate;

    // 标称采样率（整数，由 nativeInit 设置，PI 控制器不修改此值）
    uint32_t nominalSampleRate = 44100;
};

// g_usbCtx removed – all access goes through handles + gLiveHandles registry

// ==========================
// 软件音量控制 (fixed-point Q15)
// ==========================
static std::atomic<float> gSoftwareVolume{1.0f};

// ==========================
// 全局播放策略开关（用户策略，非运行时实例状态）
// ==========================
// Bit-perfect 模式：
static std::atomic<bool> g_bitPerfectEnabled{false};
// 当前 App 是否已经独占 USB 设备。没有独占时，不允许开启完美比特。
static std::atomic<bool> g_usbExclusiveActive{false};
// 硬件音量控制（Feature Unit）请求：
// 默认 false。表示用户/策略请求启用硬件音量，不代表设备真的安全。
// 实际是否生效取决于 init 时的验证结果。
static std::atomic<bool> g_hardwareFeatureUnitRequested{false};
// 运行中切换策略后，要求上层重新 init
static std::atomic<bool> g_requiresReinit{false};
// bit-perfect 固定音量确认标记
static std::atomic<bool> g_bitPerfectFixedVolumeAcknowledged{false};

// ==========================
// USB DAC 高级设置（参考 Neutron Player）
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
// USB 设备策略表
// ==========================
struct UsbDevicePolicy {
    uint16_t vid;
    uint16_t pid;
    bool allowExclusive;
    bool allowBitPerfect;
    bool allowHardwareVolume;        // 是否允许硬件音量控制
    bool forceDisableFeatureUnit;    // 强制禁用 Feature Unit（已知问题设备）
    bool requiresVolumeRepairOnAttach; // 插入时是否需要修复音量
    bool preferMasterVolume;         // 优先使用 master channel 音量
    bool preferPerChannelVolume;     // 使用逐通道音量
    float safeInitialVolumeLinear;   // 安全初始音量 (0.0~1.0)
    // 策略驱动设备特调（参考 Neutron Player）
    bool forceNoControlIface;        // 完全跳过 AC interface claim（不做 unmute/0dB）
    bool forceSoftwareVolume;        // 强制软件音量（禁用硬件音量路径）
    bool skipClockConfig;            // 跳过 UAC2 SET_CUR 时钟配置（已知返回 EIO 的设备）
    bool ignoreClockControl;         // 完全忽略时钟控制（不发 SET_CUR/GET_CUR/GET_RANGE）
};

static const UsbDevicePolicy kDefaultDevicePolicy = {
    .vid = 0,
    .pid = 0,
    .allowExclusive = true,
    .allowBitPerfect = true,
    .allowHardwareVolume = true,
    .forceDisableFeatureUnit = false,
    .requiresVolumeRepairOnAttach = false,
    .preferMasterVolume = true,
    .preferPerChannelVolume = false,
    .safeInitialVolumeLinear = 0.25f,
    .forceNoControlIface = false,
    .forceSoftwareVolume = false,
    .skipClockConfig = false,
    .ignoreClockControl = false,
};

static UsbDevicePolicy getPolicyForDevice(uint16_t vid, uint16_t pid) {
    UsbDevicePolicy p = kDefaultDevicePolicy;
    p.vid = vid;
    p.pid = pid;

    // FiiO DAC 系列：UAC2 时钟控制返回 EIO，硬件音量不适用
    // Feature Unit 有 Bass/Treble 控制，需要解除静音+设 0dB
    // 策略驱动特调：完全忽略时钟控制，保留 AC 接口用于 Feature Unit unmute+0dB
    if (vid == 0x2972) { // FiiO
        p.allowExclusive = true;
        p.allowBitPerfect = true;
        p.allowHardwareVolume = false;      // 不使用硬件音量，用软件音量
        p.forceDisableFeatureUnit = false;  // 不禁用 FU，启动时会 unmute + 0dB
        p.requiresVolumeRepairOnAttach = false;
        p.preferMasterVolume = true;
        p.preferPerChannelVolume = false;
        p.safeInitialVolumeLinear = 0.25f;
        // 策略驱动特调
        p.forceNoControlIface = false;     // 保留 AC 接口（Feature Unit unmute 需要）
        p.forceSoftwareVolume = true;      // 强制软件音量（FiiO 硬件音量不可靠）
        p.ignoreClockControl = true;       // 完全忽略时钟控制（SET_CUR/GET_CUR/GET_RANGE 均返回 EIO）
        LOGI("Device policy: FiiO VID=%04X PID=%04X → allowExclusive=1 hwVol=0 ignoreClock=1 forceSwVol=1",
             vid, pid);
    }

    return p;
}

// 硬件音量安全验证结果缓存
static std::atomic<bool> g_hardwareVolumeValidated{false};
static std::atomic<bool> g_hardwareVolumeSafe{false};

static inline int16_t clamp_s16(int32_t v) {
    if (v > 32767) return 32767;
    if (v < -32768) return -32768;
    return (int16_t)v;
}

// S16LE in-place volume: Q15 fixed-point, 0..32768 maps to 0.0..1.0
static void apply_volume_s16le(uint8_t *buf, int len, float volume) {
    if (volume >= 0.999f) return;  // 几乎满量程，跳过
    if (volume <= 0.001f) {        // 静音
        memset(buf, 0, len);
        return;
    }
    int volumeQ15 = (int)(volume * 32768.0f);
    int samples = len / 2;
    for (int i = 0; i < samples; i++) {
        int16_t s = (int16_t)((uint16_t)buf[i * 2] | ((uint16_t)buf[i * 2 + 1] << 8));
        int32_t v = ((int32_t)s * volumeQ15) >> 15;
        int16_t out = clamp_s16(v);
        buf[i * 2]     = (uint8_t)(out & 0xff);
        buf[i * 2 + 1] = (uint8_t)((out >> 8) & 0xff);
    }
}

// S16LE in-place volume: 浮点运算，保留完整 16-bit 精度（USBLinearVolume 模式）
static void apply_volume_s16le_float(uint8_t *buf, int len, float volume) {
    if (volume >= 0.999f) return;
    if (volume <= 0.001f) {
        memset(buf, 0, len);
        return;
    }
    int samples = len / 2;
    for (int i = 0; i < samples; i++) {
        int16_t s = (int16_t)((uint16_t)buf[i * 2] | ((uint16_t)buf[i * 2 + 1] << 8));
        // 浮点运算：保留完整精度，避免 Q15 量化噪声
        float v = (float)s * volume;
        int16_t out;
        if (v > 32767.0f) out = 32767;
        else if (v < -32768.0f) out = -32768;
        else out = (int16_t)v;
        buf[i * 2]     = (uint8_t)(out & 0xff);
        buf[i * 2 + 1] = (uint8_t)((out >> 8) & 0xff);
    }
}

// ==========================
// 24-bit packed LE 软件音量
// ==========================
static inline int32_t readS24LE(const uint8_t* p) {
    int32_t v =
            static_cast<int32_t>(p[0]) |
            (static_cast<int32_t>(p[1]) << 8) |
            (static_cast<int32_t>(p[2]) << 16);
    // sign extend 24bit -> 32bit
    if (v & 0x00800000) {
        v |= 0xFF000000;
    }
    return v;
}

static inline void writeS24LE(uint8_t* p, int32_t v) {
    if (v > 8388607) v = 8388607;
    if (v < -8388608) v = -8388608;
    p[0] = static_cast<uint8_t>(v & 0xFF);
    p[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
    p[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
}

static void apply_volume_s24le(uint8_t *buf, int len, float volume) {
    if (volume >= 0.999f) return;
    if (volume <= 0.001f) {
        memset(buf, 0, len);
        return;
    }
    int samples = len / 3;
    for (int i = 0; i < samples; i++) {
        uint8_t* p = buf + i * 3;
        int32_t s = readS24LE(p);
        float scaled = static_cast<float>(s) * volume;
        int32_t out;
        if (scaled > 8388607.0f) {
            out = 8388607;
        } else if (scaled < -8388608.0f) {
            out = -8388608;
        } else {
            out = static_cast<int32_t>(scaled);
        }
        writeS24LE(p, out);
    }
}

// S32LE in-place volume
static void apply_volume_s32le(uint8_t *buf, int len, float volume) {
    if (volume >= 0.999f) return;
    if (volume <= 0.001f) {
        memset(buf, 0, len);
        return;
    }
    int samples = len / 4;
    for (int i = 0; i < samples; i++) {
        int32_t s = (int32_t)((uint32_t)buf[i * 4] |
                              ((uint32_t)buf[i * 4 + 1] << 8) |
                              ((uint32_t)buf[i * 4 + 2] << 16) |
                              ((uint32_t)buf[i * 4 + 3] << 24));
        int64_t v = (int64_t)s * (int64_t)(volume * 65536.0f);
        int32_t out = (int32_t)(v >> 16);
        if (out > 2147483647) out = 2147483647;
        if (out < -2147483647 - 1) out = -2147483647 - 1;
        buf[i * 4]     = (uint8_t)(out & 0xff);
        buf[i * 4 + 1] = (uint8_t)((out >> 8) & 0xff);
        buf[i * 4 + 2] = (uint8_t)((out >> 16) & 0xff);
        buf[i * 4 + 3] = (uint8_t)((out >> 24) & 0xff);
    }
}

// ==========================
// 淡入函数：从静音渐进到满幅，避免突然跳变产生爆音
// fadePos: 当前淡入位置 (0 = 静音, fadeTotal = 满幅)
// fadeTotal: 淡入总样本数
// ==========================
static void apply_fadein_s16le(uint8_t *buf, int len, int fadePos, int fadeTotal) {
    int samples = len / 2;
    for (int i = 0; i < samples; i++) {
        int curPos = fadePos + i;
        float gain = (fadeTotal > 0 && curPos < fadeTotal)
                     ? (float)curPos / (float)fadeTotal : 1.0f;
        int16_t s = (int16_t)((uint16_t)buf[i * 2] | ((uint16_t)buf[i * 2 + 1] << 8));
        int32_t v = (int32_t)((float)s * gain);
        int16_t out = clamp_s16(v);
        buf[i * 2]     = (uint8_t)(out & 0xff);
        buf[i * 2 + 1] = (uint8_t)((out >> 8) & 0xff);
    }
}

static void apply_fadein_s24le(uint8_t *buf, int len, int fadePos, int fadeTotal) {
    int samples = len / 3;
    for (int i = 0; i < samples; i++) {
        int curPos = fadePos + i;
        float gain = (fadeTotal > 0 && curPos < fadeTotal)
                     ? (float)curPos / (float)fadeTotal : 1.0f;
        uint8_t* p = buf + i * 3;
        int32_t s = readS24LE(p);
        float scaled = static_cast<float>(s) * gain;
        int32_t out;
        if (scaled > 8388607.0f) out = 8388607;
        else if (scaled < -8388608.0f) out = -8388608;
        else out = static_cast<int32_t>(scaled);
        writeS24LE(p, out);
    }
}

static void apply_fadein_s32le(uint8_t *buf, int len, int fadePos, int fadeTotal) {
    int samples = len / 4;
    for (int i = 0; i < samples; i++) {
        int curPos = fadePos + i;
        float gain = (fadeTotal > 0 && curPos < fadeTotal)
                     ? (float)curPos / (float)fadeTotal : 1.0f;
        int32_t s = (int32_t)((uint32_t)buf[i * 4] |
                              ((uint32_t)buf[i * 4 + 1] << 8) |
                              ((uint32_t)buf[i * 4 + 2] << 16) |
                              ((uint32_t)buf[i * 4 + 3] << 24));
        int64_t v = (int64_t)((float)s * gain);
        int32_t out = (int32_t)(v);
        if (out > 2147483647) out = 2147483647;
        if (out < -2147483647 - 1) out = -2147483647 - 1;
        buf[i * 4]     = (uint8_t)(out & 0xff);
        buf[i * 4 + 1] = (uint8_t)((out >> 8) & 0xff);
        buf[i * 4 + 2] = (uint8_t)((out >> 16) & 0xff);
        buf[i * 4 + 3] = (uint8_t)((out >> 24) & 0xff);
    }
}

// ==========================
// 标记 USB 传输致命错误（统一入口）
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
    ctx->fatalError.store(ERR_TRANSPORT_LOST, std::memory_order_release);
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
// 调用者必须持有 ctx->ringMutex
static size_t ringAvailable(UsbAudioContext *ctx) {
    size_t w = ctx->pcmWritePos.load(std::memory_order_relaxed);
    size_t r = ctx->pcmReadPos.load(std::memory_order_relaxed);
    if (w >= r) return w - r;
    return ctx->pcmRingBuffer.size() - r + w;
}

// 调用者必须持有 ctx->ringMutex
static size_t ringFreeSpace(UsbAudioContext *ctx) {
    size_t used = ringAvailable(ctx);
    size_t bufSize = ctx->pcmRingBuffer.size();
    return bufSize - used - 1;
}

// 调用者必须持有 ctx->ringMutex
static size_t ringRead(UsbAudioContext *ctx, uint8_t *dst, size_t len) {
    size_t avail = ringAvailable(ctx);
    size_t toRead = (len > avail) ? avail : len;
    if (toRead == 0) return 0;

    size_t r = ctx->pcmReadPos.load(std::memory_order_relaxed);
    size_t bufSize = ctx->pcmRingBuffer.size();

    size_t firstPart = bufSize - r;
    if (toRead <= firstPart) {
        memcpy(dst, ctx->pcmRingBuffer.data() + r, toRead);
    } else {
        memcpy(dst, ctx->pcmRingBuffer.data() + r, firstPart);
        memcpy(dst + firstPart, ctx->pcmRingBuffer.data(), toRead - firstPart);
    }
    ctx->pcmReadPos.store((r + toRead) % bufSize, std::memory_order_relaxed);
    return toRead;
}

// ==========================
// fillIsoTransfer helper (forward declaration)
// ==========================

static int nextIsoPacketBytes(IsoPacer* p) {
    /**
     * Each interval, accumulate sampleRate.
     * Whenever it exceeds intervalsPerSec, emit that many frames.
     * 44100/8000: alternates 5,6,5,6,5,5,6,5,6,5,5,6,...
     * 96000/8000: steady 12 every interval
     *
     * sampleRate is double to support PI adaptive rate controller.
     */
    p->frameAccumulator += p->sampleRate;
    uint32_t framesThisInterval =
            static_cast<uint32_t>(p->frameAccumulator / p->intervalsPerSec);
    p->frameAccumulator -= (double)framesThisInterval * (double)p->intervalsPerSec;
    int bytes = static_cast<int>(framesThisInterval * p->frameSize);
    // Protect: never exceed endpoint max packet size, aligned to frame boundary
    if (bytes > p->maxPacketSize) {
        bytes = (p->maxPacketSize / (int)p->frameSize) * (int)p->frameSize;
    }
    return bytes;
}

// ==========================
// 填充 ISO 传输缓冲区并设置包长度
// ==========================
// 核心原则：
// - 每个 ISO packet 长度由相位累加器（帧级）动态计算（支持 44.1kHz 的 5/6 帧交替）
// - buffer 按 i * maxPossiblePkt 排列（预留最大空间），但 iso_packet_desc[i].length = 实际长度
// - 从 ring buffer 读取实际长度的数据，不足补静音
// ==========================
static void fillIsoTransfer(UsbAudioContext *ctx, struct libusb_transfer *xfer, int index) {
    xfer->buffer = ctx->transferBuffers[index];
    uint8_t *buf = xfer->buffer;
    const int numPkts = ctx->numIsoPackets;
    bool underrunThisRound = false;
    int totalRead = 0;
    int totalLen = 0;

    {
        std::lock_guard<std::mutex> lock(ctx->ringMutex);

        // ===== STARVATION RECOVERY =====
        if (ctx->starved) {
            size_t bufUsed = ringAvailable(ctx);
            if (bufUsed >= (size_t)ctx->starvedRecoveryBytes) {
                ctx->starved = false;
                // 启动淡入，避免从静音突然跳到音频产生爆音
                // 淡入时长 = 5ms 的样本数（足够平滑，人耳不可感知）
                int fadeSamples = (int)(ctx->sampleRate * 5 / 1000) * ctx->deviceChannels;
                if (fadeSamples < 1) fadeSamples = 1;
                ctx->fadeSamplesRemaining = fadeSamples;
                ctx->fadeTotalSamples = fadeSamples;
                LOGI("Recovered from starvation: bufUsed=%zu/%zu, fade-in %d samples",
                     bufUsed, ctx->pcmRingBuffer.size(), fadeSamples);
            } else {
                // buffer 还没恢复，输出静音，不读取
                int offset = 0;
                for (int i = 0; i < numPkts; i++) {
                    int pktBytes = nextIsoPacketBytes(&ctx->isoPacer);
                    memset(buf + offset, 0, pktBytes);
                    xfer->iso_packet_desc[i].length = pktBytes;
                    offset += pktBytes;
                    totalLen += pktBytes;
                }
                xfer->length = totalLen;
                ctx->statsUsbBytes.fetch_add(totalLen, std::memory_order_relaxed);
                ctx->statsCallbackCount.fetch_add(1, std::memory_order_relaxed);
                ctx->statsPacketCount.fetch_add(numPkts, std::memory_order_relaxed);
                return;
            }
        }

        // 每个 packet: 动态计算长度，从 ring buffer 读取，不足补静音
        // Ring buffer 存储的是 USB device 格式原始 PCM（不含音量）
        int offset = 0;
        for (int i = 0; i < numPkts; i++) {
            int pktBytes = nextIsoPacketBytes(&ctx->isoPacer);
            uint8_t *pkt = buf + offset;

            int got = (int)ringRead(ctx, pkt, pktBytes);
            if (got < pktBytes) {
                memset(pkt + got, 0, pktBytes - got);
                underrunThisRound = true;
                ctx->statsUnderrun.fetch_add(1, std::memory_order_relaxed);
            }

            xfer->iso_packet_desc[i].length = pktBytes;
            offset += pktBytes;
            totalLen += pktBytes;
            totalRead += got;
        }
    }

    xfer->length = totalLen;

    // ===== 淡入处理 =====
    // 首次启动：如果还没标记过 startupSilenceDone 且读到了真实音频数据，启动淡入
    if (!ctx->startupSilenceDone && totalRead > 0) {
        ctx->startupSilenceDone = true;
        int fadeSamples = (int)(ctx->sampleRate * 5 / 1000) * ctx->deviceChannels;
        if (fadeSamples < 1) fadeSamples = 1;
        ctx->fadeSamplesRemaining = fadeSamples;
        ctx->fadeTotalSamples = fadeSamples;
        LOGI("First audio data received, starting fade-in: %d samples", fadeSamples);
    }

    if (totalLen > 0 && ctx->fadeSamplesRemaining > 0) {
        int samplesInBuf = totalLen / ctx->deviceBytesPerFrame;
        int fadePos = ctx->fadeTotalSamples - ctx->fadeSamplesRemaining;
        if (ctx->deviceSubslotSize == 2 && ctx->deviceBitDepth == 16) {
            apply_fadein_s16le(buf, totalLen, fadePos, ctx->fadeTotalSamples);
        } else if (ctx->deviceSubslotSize == 3 && ctx->deviceBitDepth == 24) {
            apply_fadein_s24le(buf, totalLen, fadePos, ctx->fadeTotalSamples);
        } else if (ctx->deviceSubslotSize == 4 && ctx->deviceBitDepth == 32) {
            apply_fadein_s32le(buf, totalLen, fadePos, ctx->fadeTotalSamples);
        }
        ctx->fadeSamplesRemaining -= samplesInBuf;
        if (ctx->fadeSamplesRemaining <= 0) {
            ctx->fadeSamplesRemaining = 0;
        }
    }

    // 在发送到 USB 之前应用软件音量（基于当前 volume 快照）
    // 这样音量变化最多一个 transfer 周期后生效，不会延迟到 ring buffer 旧数据播完
    // 软音量始终执行
    if (totalLen > 0) {
        float vol = ctx->softwareVolume.load(std::memory_order_relaxed);
        if (vol < 0.999f) { // 只在需要调节时进入
            if (ctx->deviceSubslotSize == 2 && ctx->deviceBitDepth == 16) {
                if (g_usbLinearVolume.load(std::memory_order_relaxed)) {
                    apply_volume_s16le_float(buf, totalLen, vol);
                } else {
                    apply_volume_s16le(buf, totalLen, vol);
                }
            } else if (ctx->deviceSubslotSize == 3 && ctx->deviceBitDepth == 24) {
                apply_volume_s24le(buf, totalLen, vol);
            } else if (ctx->deviceSubslotSize == 4 && ctx->deviceBitDepth == 32) {
                apply_volume_s32le(buf, totalLen, vol);
            }
        }
    }

    // 统计
    ctx->statsUsbBytes.fetch_add(totalLen, std::memory_order_relaxed);
    ctx->statsCallbackCount.fetch_add(1, std::memory_order_relaxed);
    ctx->statsPacketCount.fetch_add(numPkts, std::memory_order_relaxed);

    if (underrunThisRound && totalRead == 0) {
        ctx->starved = true;
        // 只在首次进入 starvation 时打印
    }
}

// ==========================
// 减少 pending 计数，归零时唤醒 stop 等待
// ==========================
static void decrementPendingAndNotifyStop(UsbAudioContext* ctx) {
    if (!ctx) return;
    int left = ctx->pendingTransfers.fetch_sub(1, std::memory_order_acq_rel) - 1;
    if (left <= 0) {
        std::lock_guard<std::mutex> lk(ctx->stopMutex);
        ctx->stopCV.notify_all();
    }
}

static constexpr int FEEDBACK_EMPTY_THRESHOLD = 10;

// ==========================
// 反馈端点回调（UAC2 同步信号读取与解析）
// ==========================
static void LIBUSB_CALL feedbackCallback(struct libusb_transfer *xfer) {
    UsbAudioContext *ctx = reinterpret_cast<UsbAudioContext*>(xfer->user_data);
    if (!ctx) return;

    // feedback transfer 完成，pendingFeedback--
    int left = ctx->pendingFeedbackTransfers.fetch_sub(1, std::memory_order_acq_rel) - 1;

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

    // closing 路径：不再 resubmit
    if (ctx->closing.load(std::memory_order_acquire)) {
        return;
    }

    if (xfer->status == LIBUSB_TRANSFER_CANCELLED) {
        LOGD("Feedback transfer cancelled");
        return;
    }

    if (xfer->status == LIBUSB_TRANSFER_NO_DEVICE ||
        xfer->status == LIBUSB_TRANSFER_ERROR ||
        xfer->status == LIBUSB_TRANSFER_STALL) {
        markUsbTransportLost(ctx, "feedback status", -1, xfer->status);
        return;
    }

    if (xfer->status == LIBUSB_TRANSFER_COMPLETED) {
        if (!xfer->buffer || xfer->actual_length <= 0) {
            int empty = ctx->feedbackEmptyCount.fetch_add(1, std::memory_order_relaxed) + 1;
            if (empty == FEEDBACK_EMPTY_THRESHOLD) {
                // Feedback 降级：保守地切换到 PI 自适应速率控制器
                // 不立即死锁到标称速率，而是让 PI 控制器从当前状态平滑接管
                ctx->feedbackDegraded = true;
                ctx->adaptiveRate.active = true;
                ctx->adaptiveRate.stableCount = 0;
                {
                    std::lock_guard<std::mutex> lock(ctx->ringMutex);
                    ctx->adaptiveRate.prevBufUsed = ringAvailable(ctx);
                    size_t ringTotal = ctx->pcmRingBuffer.size();
                    double currentFillRatio = (ringTotal > 0)
                        ? (double)ctx->adaptiveRate.prevBufUsed / (double)ringTotal
                        : 0.0;
                    // 积分误差初始化：基于当前缓冲区填充率与目标的偏差
                    // 这样 PI 控制器从一个合理的初始状态开始，避免从零开始的大幅振荡
                    double fillError = currentFillRatio - ctx->adaptiveRate.targetFillRatio;
                    ctx->adaptiveRate.integralError = fillError * 0.5; // 50% 的初始积分
                    LOGW("Feedback endpoint 0x%02X degraded (empty %d times), "
                         "switching to PI adaptive rate controller. "
                         "bufUsed=%zu/%zu fillRatio=%.1f%% integralInit=%.6f nominalRate=%u",
                         ctx->feedbackEpAddress, empty,
                         ctx->adaptiveRate.prevBufUsed, ringTotal,
                         currentFillRatio * 100.0,
                         ctx->adaptiveRate.integralError,
                         ctx->nominalSampleRate);
                }
                // 不立即重置 pacer 到标称速率，保持当前速率让 PI 平滑过渡
                // 只复位 feedback 相关的平滑值
                ctx->bytes_per_second_smoothed.store(0, std::memory_order_relaxed);
                return;
            }
            if (empty > FEEDBACK_EMPTY_THRESHOLD) {
                return;
            }
            // 前几次空包静默，不打印警告
            goto feedback_resubmit;
        }
        int minLen = ctx->isFullSpeed ? 3 : 4;
        if (xfer->actual_length < minLen) {
            LOGW("Feedback short packet: actual_length=%d need=%d",
                 xfer->actual_length, minLen);
            goto feedback_resubmit;
        }
        // 有数据了，复位计数器
        ctx->feedbackEmptyCount.store(0, std::memory_order_relaxed);
        // 解析 UAC2 反馈数据
        uint8_t *fb = xfer->buffer;
        uint32_t feedbackRaw;
        double fb_value;  // Q-format 原始值

        if (!ctx->isFullSpeed) {
            // High-Speed: Q16.16 format, value = samples per microframe
            feedbackRaw = fb[0] | (fb[1] << 8) | (fb[2] << 16) | (fb[3] << 24);
            fb_value = feedbackRaw / 65536.0;
        } else {
            // Full-Speed: Q10.14 format, value = samples per frame (1ms)
            int32_t raw = fb[0] | (fb[1] << 8) | (fb[2] << 16);
            if (raw & 0x800000) raw |= 0xFF000000;
            feedbackRaw = (uint32_t)raw;
            fb_value = raw / 16384.0;
        }
        ctx->feedbackRate.store(fb_value, std::memory_order_relaxed);

        // 计算 feedback 推导的实际采样率
        // High-Speed: feedbackRate * 8000 (microframes/sec)
        // Full-Speed: feedbackRate * 1000 (frames/sec)
        double feedbackSampleRate = ctx->isFullSpeed
            ? (fb_value * 1000.0)
            : (fb_value * 8000.0);

        // 平滑反馈到 packet scheduler
        // feedbackSampleRate × frameSize = bytes_per_second
        {
            double bytes_per_sec_d = feedbackSampleRate * (double)ctx->bytesPerFrame;
            uint64_t new_bps = (uint64_t)(bytes_per_sec_d + 0.5);

            // 合法性检查 + 只允许 ppm 级微调（不超过 requested rate 的 ±1%）
            uint64_t baseBps = ctx->bytes_per_second;
            uint64_t minBps = baseBps - baseBps / 100;  // -1%
            uint64_t maxBps = baseBps + baseBps / 100;  // +1%
            if (new_bps >= minBps && new_bps <= maxBps) {
                uint64_t smoothed = ctx->bytes_per_second_smoothed.load(std::memory_order_relaxed);
                if (smoothed == 0) smoothed = ctx->bytes_per_second;
                smoothed = (smoothed * 127 + new_bps) / 128;
                ctx->bytes_per_second_smoothed.store(smoothed, std::memory_order_relaxed);
            } else {
                // feedback 严重偏离请求采样率，忽略（可能是设备实际采样率不对）
                // 不 log 避免刷屏，首次会在这里被吃掉
            }
        }

        static std::atomic<int> fbCount{0};
        if (++fbCount >= 1000) {
            fbCount = 0;
            double bytes_per_sec_d = feedbackSampleRate * (double)ctx->bytesPerFrame;
            LOGI("Feedback: raw=0x%08X fb_value=%.6f rateByMs=%.3f rateByMicroframe=%.3f "
                 "selectedRate=%.3f bytes_per_sec=%.0f (%s)",
                 feedbackRaw, fb_value,
                 fb_value * 1000.0,
                 fb_value * 8000.0,
                 feedbackSampleRate, bytes_per_sec_d,
                 !ctx->isFullSpeed ? "Q16.16" : "Q10.14");
        }
    } else {
        LOGW("Feedback non-completed: status=%d", xfer->status);
    }

feedback_resubmit:
    if (!ctx->streaming.load(std::memory_order_acquire) ||
        ctx->transportLost.load(std::memory_order_acquire)) {
        return;
    }

    int ret = libusb_submit_transfer(xfer);
    if (ret < 0) {
        LOGE("Feedback resubmit failed: %s", libusb_error_name(ret));
        markUsbTransportLost(ctx, "feedback resubmit", -1, ret);
        return;
    }
    // resubmit 成功，pending 重新++
    ctx->pendingFeedbackTransfers.fetch_add(1, std::memory_order_relaxed);
}

// ==========================
// ISO 传输回调（极简：不 malloc、不大量 log、不 JNI 回调）
// ==========================
static void LIBUSB_CALL isoCallback(struct libusb_transfer *xfer) {
    auto* ud = reinterpret_cast<UsbAudioContext::IsoUserData*>(xfer->user_data);
    if (!ud || !ud->ctx) return;
    UsbAudioContext *ctx = ud->ctx;
    int index = ud->index;

    // 旧 transfer 已经完成，pending--
    int left = ctx->pendingTransfers.fetch_sub(1, std::memory_order_acq_rel) - 1;

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

    // closing 路径：不再 resubmit
    if (ctx->closing.load(std::memory_order_acquire)) {
        return;
    }

    if (xfer->status == LIBUSB_TRANSFER_CANCELLED) {
        return;  // stopping, 不 log
    }

    if (xfer->status == LIBUSB_TRANSFER_NO_DEVICE ||
        xfer->status == LIBUSB_TRANSFER_STALL) {
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

    // 统计 packet 级错误（只计数，不 log）
    for (int i = 0; i < xfer->num_iso_packets; i++) {
        if (xfer->iso_packet_desc[i].status != 0 &&
            xfer->iso_packet_desc[i].status != LIBUSB_TRANSFER_COMPLETED) {
            ctx->statsPacketError.fetch_add(1, std::memory_order_relaxed);
        }
    }

    // 每秒统计（只在 callback 线程中做一次轻量检查）
    {
        int cbCount = ctx->statsCallbackCount.load(std::memory_order_relaxed);
        if (cbCount >= 1000) {
            ctx->statsCallbackCount.store(0, std::memory_order_relaxed);
            int64_t appBytes = ctx->statsAppBytes.exchange(0, std::memory_order_relaxed);
            int64_t usbBytes = ctx->statsUsbBytes.exchange(0, std::memory_order_relaxed);
            int underruns = ctx->statsUnderrun.exchange(0, std::memory_order_relaxed);
            int submitErrs = ctx->statsSubmitError.exchange(0, std::memory_order_relaxed);
            int pktErrs = ctx->statsPacketError.exchange(0, std::memory_order_relaxed);
            int xferErrs = ctx->statsXferError.exchange(0, std::memory_order_relaxed);
            int pktCount = ctx->statsPacketCount.exchange(0, std::memory_order_relaxed);
            size_t bufUsed;
            {
                std::lock_guard<std::mutex> lock(ctx->ringMutex);
                bufUsed = ringAvailable(ctx);
            }
            double fbRate = ctx->feedbackRate.load(std::memory_order_relaxed);
            uint64_t smoothBps = ctx->bytes_per_second_smoothed.load(std::memory_order_relaxed);
            uint64_t expectedBps = ctx->bytes_per_second;
            double avgPacketBytes = (pktCount > 0) ? (double)usbBytes / (double)pktCount : 0.0;
            double usbOutBytesPerSec = (double)usbBytes;
            double appInBytesPerSec = (double)appBytes;
            int packetsPerSec = pktCount;
            int callbacksPerSec = cbCount;  // 这就是 1000 左右

            // ===== PI 自适应速率控制器（feedback 失效时激活）=====
            // 核心策略：单向限速
            //   - 缓冲区高 → 允许降速（correction > 0），让 DAC 慢慢消化
            //   - 缓冲区低 → 禁止提速（correction = 0），维持标称速率，靠补静音解决
            //   - Android App 供给极不稳定，提速会导致 DAC 饥饿爆音
            if (ctx->adaptiveRate.active) {
                size_t bufSize = ctx->pcmRingBuffer.size();
                double fillRatio = (bufSize > 0) ? (double)bufUsed / (double)bufSize : 0.0;
                double error = fillRatio - ctx->adaptiveRate.targetFillRatio;

                // 死区：偏差太小时不修正，避免不必要的抖动
                if (std::fabs(error) < ctx->adaptiveRate.deadZone) {
                    error = 0.0;
                    ctx->adaptiveRate.integralError *= 0.99;  // 缓慢衰减
                } else if (error < 0.0) {
                    // 缓冲区低：清空负向积分，防止累积导致后续误判
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

                // 应用到 Pacer：正修正 → 降低发送速率（让 DAC 消化缓冲区）
                ctx->isoPacer.sampleRate = (double)ctx->nominalSampleRate * (1.0 + correction);

                LOGI("AdaptiveRate: target=%.1f%% actual=%.1f%% err=%.4f integral=%.4f corr=%.6f%% rate=%.2f",
                     ctx->adaptiveRate.targetFillRatio * 100.0, fillRatio * 100.0,
                     error, ctx->adaptiveRate.integralError,
                     correction * 100.0, ctx->isoPacer.sampleRate);
            }

            LOGI("Stats: appInBytesPerSec=%.0f usbOutBytesPerSec=%.0f expectedBytesPerSec=%llu schedulerBytesPerSec=%llu "
                 "avgPacketBytes=%.3f packetsPerSec=%d callbacksPerSec=%d "
                 "buf=%zu/%zu underrun=%d submitErr=%d pktErr=%d xferErr=%d fb=%.3f",
                 appInBytesPerSec, usbOutBytesPerSec,
                 (unsigned long long)expectedBps, (unsigned long long)smoothBps,
                 avgPacketBytes, packetsPerSec, callbacksPerSec,
                 bufUsed, ctx->pcmRingBuffer.size(),
                 underruns, submitErrs, pktErrs, xferErrs,
                 fbRate);
        }
    }

    // 重提交
    fillIsoTransfer(ctx, xfer, index);
    int ret = libusb_submit_transfer(xfer);
    if (ret < 0) {
        ctx->statsSubmitError.fetch_add(1, std::memory_order_relaxed);
        if (ret == LIBUSB_ERROR_IO ||
            ret == LIBUSB_ERROR_NO_DEVICE ||
            ret == LIBUSB_ERROR_NOT_FOUND ||
            ret == LIBUSB_ERROR_OTHER) {
            markUsbTransportLost(ctx, "iso resubmit", index, ret);
        }
        return;
    }
    // resubmit 成功，pending 重新++
    ctx->pendingTransfers.fetch_add(1, std::memory_order_relaxed);
}

// ==========================
// 事件处理线程（SCHED_FIFO 实时优先级，1ms 轮询）
// ==========================
static void setRealtimePriority(int prio) {
    struct sched_param sp;
    memset(&sp, 0, sizeof(sp));
    sp.sched_priority = prio;
    if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp) != 0) {
        // 没有 SCHED_FIFO 权限，退化为 nice -16
        setpriority(PRIO_PROCESS, syscall(SYS_gettid), -16);
        LOGI("SCHED_FIFO denied, fallback to nice -16");
    } else {
        LOGI("SCHED_FIFO priority=%d set", prio);
    }
}

static void eventLoop(UsbAudioContext *ctx) {
    setRealtimePriority(30);
    LOGI("libusb event loop started (SCHED_FIFO prio=30, timeout=1ms)");
    struct timeval tv;
    while (ctx->eventThreadRunning.load()) {
        tv.tv_sec = 0;
        tv.tv_usec = 1000; // 1ms timeout — 必须，否则 callback 延迟大
        int r = libusb_handle_events_timeout(ctx->libusbCtx, &tv);
        if (r == LIBUSB_ERROR_INTERRUPTED) continue;
        if (r == LIBUSB_ERROR_NO_DEVICE) break;
    }
    LOGI("libusb event loop exited");
}

// ==========================
// Audio Stream Candidate
// ==========================
struct AudioStreamCandidate {
    int iface = -1;
    int alt = -1;
    UsbAudioProtocol protocol = USB_AUDIO_UNKNOWN;
    uint8_t terminalLink = 0;
    uint8_t epAddress = 0;
    uint8_t feedbackEpAddress = 0;
    int maxPacketSize = 0;
    int bInterval = 1;
    int channels = 0;
    int subslotSize = 0;
    int bitResolution = 0;
    bool isPCM = false;
    bool hasSampleRateList = false;
    std::vector<int> sampleRates;
    bool hasContinuousRate = false;
    int minRate = 0;
    int maxRate = 0;
    bool uac1EpHasSamplingFreqControl = false;
    int score = 0;
};

static uint32_t read_u24_le(const uint8_t *p) {
    return ((uint32_t)p[0]) |
           ((uint32_t)p[1] << 8) |
           ((uint32_t)p[2] << 16);
}

static uint16_t read_u16_le(const uint8_t *p) {
    return ((uint16_t)p[0]) | ((uint16_t)p[1] << 8);
}

static bool streamSupportsRate(const AudioStreamCandidate &c, int rate) {
    if (c.hasSampleRateList) {
        for (int r : c.sampleRates) {
            if (r == rate) return true;
        }
        return false;
    }
    if (c.hasContinuousRate) {
        return rate >= c.minRate && rate <= c.maxRate;
    }
    // UAC2 常常不在 FORMAT_TYPE 中列采样率，而是通过 Clock Source RANGE/CUR 控制
    if (c.protocol == USB_AUDIO_UAC2) {
        return true;
    }
    // 没声明时保守放行，后续靠传输是否成功
    return true;
}

static std::string rateListToString(const AudioStreamCandidate &c) {
    if (c.hasSampleRateList) {
        std::string s;
        for (size_t i = 0; i < c.sampleRates.size(); i++) {
            if (i) s += ",";
            s += std::to_string(c.sampleRates[i]);
        }
        return s;
    }
    if (c.hasContinuousRate) {
        return std::to_string(c.minRate) + ".." + std::to_string(c.maxRate);
    }
    return "unknown";
}

static PcmFormatAdapter choosePcmAdapter(
        int sourceChannels,
        int sourceBitDepth,
        int sourceBytesPerSample,
        int deviceChannels,
        int deviceBitDepth,
        int deviceSubslotSize,
        bool bitPerfect
) {
    if (sourceChannels != deviceChannels) {
        return PCM_ADAPTER_UNSUPPORTED;
    }
    // 完全一致
    if (sourceBitDepth == deviceBitDepth &&
        sourceBytesPerSample == deviceSubslotSize) {
        return PCM_ADAPTER_NONE;
    }
    // Bit-perfect 模式下，只允许无损整数扩展，不允许降位、不允许浮点、不允许声道变化
    if (sourceBitDepth == 16 &&
        sourceBytesPerSample == 2 &&
        deviceBitDepth == 24 &&
        deviceSubslotSize == 3) {
        return PCM_ADAPTER_S16_TO_S24;
    }
    if (sourceBitDepth == 16 &&
        sourceBytesPerSample == 2 &&
        deviceBitDepth == 32 &&
        deviceSubslotSize == 4) {
        return PCM_ADAPTER_S16_TO_S32;
    }
    if (sourceBitDepth == 24 &&
        sourceBytesPerSample == 3 &&
        deviceBitDepth == 32 &&
        deviceSubslotSize == 4) {
        return PCM_ADAPTER_S24_TO_S32;
    }
    // S32LE (4B/sample) → S24 (3B/sample)：丢弃低 8 位
    if (sourceBitDepth == 32 &&
        sourceBytesPerSample == 4 &&
        deviceBitDepth == 24 &&
        deviceSubslotSize == 3) {
        return PCM_ADAPTER_S32_TO_S24;
    }
    return PCM_ADAPTER_UNSUPPORTED;
}

static const char* pcmAdapterName(PcmFormatAdapter a) {
    switch (a) {
        case PCM_ADAPTER_NONE: return "NONE";
        case PCM_ADAPTER_S16_TO_S24: return "S16_TO_S24_ZERO_PAD";
        case PCM_ADAPTER_S16_TO_S32: return "S16_TO_S32_ZERO_PAD";
        case PCM_ADAPTER_S24_TO_S32: return "S24_TO_S32_ZERO_PAD";
        case PCM_ADAPTER_S32_TO_S24: return "S32_TO_S24_TRUNCATE";
        default: return "UNSUPPORTED";
    }
}

// ==========================
// libswresample 重采样支持
// ==========================
static AVSampleFormat bitDepthToAvFormat(int bitDepth, int bytesPerSample) {
    switch (bitDepth) {
        case 8:  return AV_SAMPLE_FMT_U8;
        case 16: return AV_SAMPLE_FMT_S16;
        case 24: return (bytesPerSample == 4) ? AV_SAMPLE_FMT_S32 : AV_SAMPLE_FMT_S32; // 24bit 用 S32 打包
        case 32: return AV_SAMPLE_FMT_S32;
        default: return AV_SAMPLE_FMT_S16;
    }
}

// 初始化重采样上下文。返回 0 成功，负值失败。
static int initSwrContext(UsbAudioContext *ctx) {
    if (!ctx) return -1;

    // 先清理旧的
    if (ctx->swrCtx) {
        swr_free(&ctx->swrCtx);
        ctx->swrCtx = nullptr;
    }
    ctx->needsResample = false;

    // 判断是否需要重采样
    // 注意：位深转换由 PCM adapter 处理，swresample 只负责采样率和声道转换
    bool rateChange = (ctx->sourceSampleRate != ctx->sampleRate);
    bool formatChange = (ctx->sourceChannels != ctx->deviceChannels);

    if (!rateChange && !formatChange) {
        LOGI("initSwrContext: no resampling needed (rate=%d, format matches)", ctx->sourceSampleRate);
        return 0;
    }

    AVSampleFormat inFmt = bitDepthToAvFormat(ctx->sourceBitDepth, ctx->sourceBytesPerSample);
    AVSampleFormat outFmt = bitDepthToAvFormat(ctx->deviceBitDepth, ctx->deviceSubslotSize);

    // 通道布局
    int64_t inChLayout = av_get_default_channel_layout(ctx->sourceChannels);
    int64_t outChLayout = av_get_default_channel_layout(ctx->deviceChannels);

    ctx->swrCtx = swr_alloc_set_opts(
        nullptr,
        outChLayout, outFmt, ctx->sampleRate,      // 输出：设备格式
        inChLayout, inFmt, ctx->sourceSampleRate,   // 输入：源格式
        0, nullptr
    );
    if (!ctx->swrCtx) {
        LOGE("initSwrContext: swr_alloc_set_opts failed");
        return -2;
    }

    // 高质量重采样设置：增加滤波器阶数和相位数，减少混叠噪声
    av_opt_set_int(ctx->swrCtx, "filter_size", 32, 0);       // 默认 16 → 32 taps
    av_opt_set_int(ctx->swrCtx, "phase_shift", 12, 0);       // 默认 10 → 4096 phases
    av_opt_set_int(ctx->swrCtx, "linear_interp", 0, 0);      // 关闭线性插值，用更高精度
    av_opt_set_double(ctx->swrCtx, "cutoff", 0.99, 0);       // 默认 0.97 → 0.99

    int ret = swr_init(ctx->swrCtx);
    if (ret < 0) {
        LOGE("initSwrContext: swr_init failed: %d", ret);
        swr_free(&ctx->swrCtx);
        ctx->swrCtx = nullptr;
        return -3;
    }

    ctx->needsResample = true;

    // 预分配输出缓冲区（4 秒 worth of device-rate data）
    size_t outBytesPerSec = ctx->sampleRate * ctx->deviceChannels * ctx->deviceSubslotSize;
    ctx->swrOutBufferSize = outBytesPerSec * 4;
    ctx->swrOutBuffer.resize(ctx->swrOutBufferSize);

    LOGI("initSwrContext: enabled resampling %dHz/%dch/%dbit -> %dHz/%dch/%dbit",
         ctx->sourceSampleRate, ctx->sourceChannels, ctx->sourceBitDepth,
         ctx->sampleRate, ctx->deviceChannels, ctx->deviceBitDepth);
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
// 从配置描述符中解析音频接口（候选评分版）
// ==========================
static bool parseAudioInterfaceFromConfig(
        const uint8_t *configDesc,
        int configLen,
        int targetSampleRate,
        int targetChannels,
        int targetBitDepth,
        int javaHintIface,
        int javaHintAlt,
        bool bitPerfect,
        AudioStreamCandidate &outBest
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
                                c.channels = configDesc[scan + 10];
                                LOGI("  Alt%d AS_GENERAL(UAC2): termLink=%d formatType=%d bmFormats=0x%08X channels=%d PCM=%d",
                                     c.alt, c.terminalLink, formatType, bmFormats, c.channels, c.isPCM ? 1 : 0);
                            } else if (dLen >= 7) {
                                c.protocol = USB_AUDIO_UAC1;
                                uint16_t wFormatTag = read_u16_le(&configDesc[scan + 5]);
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
                                    c.channels = configDesc[scan + 4];
                                    c.subslotSize = configDesc[scan + 5];
                                    c.bitResolution = configDesc[scan + 6];
                                    uint8_t samFreqType = configDesc[scan + 7];
                                    if (samFreqType > 0) {
                                        c.hasSampleRateList = true;
                                        for (int f = 0; f < samFreqType; f++) {
                                            int off = scan + 8 + f * 3;
                                            if (off + 2 < scan + dLen) {
                                                c.sampleRates.push_back((int)read_u24_le(&configDesc[off]));
                                            }
                                        }
                                    } else {
                                        // continuous range: min/max/res, 3 bytes each
                                        if (dLen >= 17) {
                                            c.hasContinuousRate = true;
                                            c.minRate = (int)read_u24_le(&configDesc[scan + 8]);
                                            c.maxRate = (int)read_u24_le(&configDesc[scan + 11]);
                                        }
                                    }
                                    LOGI("  Alt%d FORMAT_TYPE(UAC1): type=%d channels=%d subframe=%d bits=%d rates=%s",
                                         c.alt,
                                         formatType,
                                         c.channels,
                                         c.subslotSize,
                                         c.bitResolution,
                                         rateListToString(c).c_str());
                                }
                            } else {
                                // 默认按 UAC2 解析
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
                        uint16_t wMaxPkt = read_u16_le(&configDesc[scan + 4]);
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
                            LOGI("    Candidate OUT: iface=%d alt=%d ep=0x%02X pkt=%d bInterval=%d",
                                 c.iface, c.alt, c.epAddress, c.maxPacketSize, c.bInterval);
                        }
                        if (isIso && isIn) {
                            // explicit feedback endpoint
                            // usage type 1 = feedback endpoint
                            if (usageType == 1 || maxPacketBase <= 4) {
                                c.feedbackEpAddress = epAddr;
                                LOGI("    Feedback endpoint detected: ep=0x%02X usage=%d maxPkt=%d",
                                     epAddr, usageType, maxPacketBase);
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
                if (c.epAddress != 0 && c.maxPacketSize > 0 && c.isPCM) {
                    // Bit-perfect 模式下：允许无损整数扩展（低位补0），不允许降位/浮点/声道变化
                    if (bitPerfect) {
                        // FFmpeg 解码器对 24bit/32bit 统一输出 S32LE (4B/sample)
                        // 必须用解码器实际输出格式来评估候选，而非源文件格式
                        int sourceBitDepth = (targetBitDepth > 16) ? 32 : targetBitDepth;
                        int sourceBytesPerSample = (targetBitDepth > 16) ? 4 : 2;
                        PcmFormatAdapter adapter = choosePcmAdapter(
                                targetChannels,
                                sourceBitDepth,
                                sourceBytesPerSample,
                                c.channels,
                                c.bitResolution,
                                c.subslotSize,
                                true
                        );
                        bool rateOk = streamSupportsRate(c, targetSampleRate);
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
                    int expectedAvgBytesPerInterval;
                    // 临时按速度估计，真正速度 nativeInit 后会重新算
                    // FS 约 1000 interval/s，HS 约 8000 interval/s
                    // 当有适配时，ISO packet 按 device 格式算
                    {
                        int frameSize = c.channels * c.subslotSize;  // device format
                        expectedAvgBytesPerInterval =
                                (targetSampleRate * frameSize) / 1000;
                    }
                    // ---------- 评分 ----------
                    int score = 0;
                    // 适配权重：完全匹配 > 无损升位 > 不支持
                    {
                        // FFmpeg 解码器对 24bit/32bit 统一输出 S32LE (4B/sample)
                        int srcBitDepth = (targetBitDepth > 16) ? 32 : targetBitDepth;
                        int srcBps = (targetBitDepth > 16) ? 4 : 2;
                        PcmFormatAdapter scoreAdapter = choosePcmAdapter(
                                targetChannels,
                                srcBitDepth,
                                srcBps,
                                c.channels,
                                c.bitResolution,
                                c.subslotSize,
                                false   // 评分不考虑 bitPerfect，所有候选用同一标准
                        );
                        if (scoreAdapter == PCM_ADAPTER_NONE) {
                            score += 2000; // 最优：完全格式一致
                        } else if (scoreAdapter == PCM_ADAPTER_S16_TO_S24 ||
                                   scoreAdapter == PCM_ADAPTER_S16_TO_S32 ||
                                   scoreAdapter == PCM_ADAPTER_S24_TO_S32) {
                            score += 1200; // 次优：无损升位
                        } else {
                            score -= 5000;
                        }
                    }
                    if (c.isPCM) score += 1000;
                    if (streamSupportsRate(c, targetSampleRate)) {
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
                    {
                        // FFmpeg 解码器对 24bit/32bit 统一输出 S32LE (4B/sample)
                        int srcBitDepth2 = (targetBitDepth > 16) ? 32 : targetBitDepth;
                        int srcBps2 = (targetBitDepth > 16) ? 4 : 2;
                        PcmFormatAdapter bitAdapter = choosePcmAdapter(
                                targetChannels,
                                srcBitDepth2,
                                srcBps2,
                                c.channels,
                                c.bitResolution,
                                c.subslotSize,
                                false
                        );
                        if (c.bitResolution == targetBitDepth) {
                            score += 800;
                        } else if (c.bitResolution > targetBitDepth &&
                                   bitAdapter != PCM_ADAPTER_UNSUPPORTED) {
                            score += 400; // 无损升位可接受
                        } else if (c.bitResolution > 0) {
                            score -= 800;
                        }
                    }
                    // 解码器输出 S32LE (4B/sample) for >16bit，所以 subslot=4 更匹配
                    if (c.subslotSize == ((targetBitDepth > 16) ? 4 : 2)) {
                        score += 300;
                    }
                    if (c.maxPacketSize >= expectedAvgBytesPerInterval) {
                        score += 300;
                    } else {
                        score -= 1000;
                    }
                    // Java 传下来的作为 hint，不再强制
                    if (javaHintIface >= 0 && c.iface == javaHintIface) {
                        score += 100;
                    }
                    if (javaHintAlt > 0 && c.alt == javaHintAlt) {
                        score += 100;
                    }
                    // 优先较低 alt，避免误选高格式
                    score -= c.alt * 2;
                    c.score = score;
                    LOGI("    Score=%d iface=%d alt=%d ep=0x%02X proto=UAC%d ch=%d bits=%d subslot=%d rates=%s fb=0x%02X",
                         c.score,
                         c.iface,
                         c.alt,
                         c.epAddress,
                         c.protocol,
                         c.channels,
                         c.bitResolution,
                         c.subslotSize,
                         rateListToString(c).c_str(),
                         c.feedbackEpAddress);
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
    int bestIndex = 0;
    for (int i = 1; i < (int)candidates.size(); i++) {
        if (candidates[i].score > candidates[bestIndex].score) {
            bestIndex = i;
        }
    }
    outBest = candidates[bestIndex];
    LOGI("Selected stream: iface=%d alt=%d ep=0x%02X proto=UAC%d termLink=0x%02X pkt=%d interval=%d ch=%d bits=%d subslot=%d rateSupport=%s feedback=0x%02X score=%d",
         outBest.iface,
         outBest.alt,
         outBest.epAddress,
         outBest.protocol,
         outBest.terminalLink,
         outBest.maxPacketSize,
         outBest.bInterval,
         outBest.channels,
         outBest.bitResolution,
         outBest.subslotSize,
         rateListToString(outBest).c_str(),
         outBest.feedbackEpAddress,
         outBest.score);
    return true;
}

// ==========================
// AC Topology 数据结构
// ==========================
enum AcEntityType {
    AC_ENTITY_INPUT_TERMINAL   = 0x02,
    AC_ENTITY_OUTPUT_TERMINAL  = 0x03,
    AC_ENTITY_MIXER_UNIT       = 0x04,
    AC_ENTITY_SELECTOR_UNIT    = 0x05,
    AC_ENTITY_FEATURE_UNIT     = 0x06,
    AC_ENTITY_EFFECT_UNIT      = 0x07,
    AC_ENTITY_PROCESSING_UNIT  = 0x08,
    AC_ENTITY_EXTENSION_UNIT   = 0x09,
    AC_ENTITY_CLOCK_SOURCE     = 0x0A,
    AC_ENTITY_CLOCK_SELECTOR   = 0x0B,
    AC_ENTITY_CLOCK_MULTIPLIER = 0x0C,
};

struct AcEntity {
    uint8_t id = 0;           // bTerminalID / bUnitID / bClockID
    uint8_t subtype = 0;      // CS_INTERFACE subtype
    uint8_t acInterface = 0;  // which AudioControl interface

    // Terminal fields
    uint16_t terminalType = 0;  // wTerminalType (only for Input/Output Terminal)
    uint8_t cSourceId = 0;      // bCSourceID (Terminal / Clock Source / Clock Selector)
    uint8_t sourceId = 0;       // bSourceID (Output Terminal / Feature Unit / etc.)
    uint8_t assocTerminal = 0;  // bAssocTerminal
    uint8_t nrPins = 0;         // bNrInPins (Selector Unit, Clock Selector)

    // Clock Source fields
    uint8_t bmAttributes = 0;   // bmAttributes (Clock Source)
    uint8_t bmControls = 0;     // bmControls (Clock Source / Clock Selector)

    // Selector / Clock Selector source IDs
    uint8_t sourceIds[16] = {};  // baCSourceID / baSourceID (up to 16 pins)
    int numSourceIds = 0;

    // Feature Unit
    uint8_t fuSourceId = 0;     // bSourceID
    std::vector<uint32_t> controlsByChannel; // bmControls per channel (UAC2: 4 bytes per channel)
};

// ==========================
// AC Topology: 查找辅助
// ==========================
struct AcTopology {
    std::vector<AcEntity> entities;

    AcEntity* findById(uint8_t id) {
        for (auto& e : entities) {
            if (e.id == id) return &e;
        }
        return nullptr;
    }

    AcEntity* findFeatureUnitBySource(uint8_t srcId) {
        for (auto& e : entities) {
            if (e.subtype == AC_ENTITY_FEATURE_UNIT && e.fuSourceId == srcId) {
                return &e;
            }
        }
        return nullptr;
    }
};

// ==========================
// AC Topology: 解析所有 entity
// ==========================
static AcTopology parseACTopology(const uint8_t *configDesc, int configLen) {
    AcTopology topo;
    int pos = 0;

    while (pos + 2 < configLen) {
        uint8_t bLength = configDesc[pos];
        uint8_t bDescriptorType = configDesc[pos + 1];
        if (bLength < 2 || pos + bLength > configLen) break;

        if (bDescriptorType == LIBUSB_DT_INTERFACE && bLength >= LIBUSB_DT_INTERFACE_SIZE) {
            uint8_t bInterfaceClass = configDesc[pos + 5];
            uint8_t bInterfaceSubClass = configDesc[pos + 6];

            if (bInterfaceClass == LIBUSB_CLASS_AUDIO && bInterfaceSubClass == 0x01) {
                uint8_t acIface = configDesc[pos + 2];
                int csPos = pos + bLength;

                while (csPos + 3 < configLen) {
                    uint8_t csLen = configDesc[csPos];
                    uint8_t csType = configDesc[csPos + 1];
                    if (csLen < 2 || csPos + csLen > configLen) break;
                    if (csType == LIBUSB_DT_INTERFACE) break; // next interface

                    if (csType == 0x24 && csLen >= 4) {
                        uint8_t subtype = configDesc[csPos + 2];

                        AcEntity e = {};
                        e.id = configDesc[csPos + 3];
                        e.subtype = subtype;
                        e.acInterface = acIface;
                        e.numSourceIds = 0;

                        switch (subtype) {
                            case AC_ENTITY_INPUT_TERMINAL: // 0x02
                                if (csLen >= 8) {
                                    e.terminalType = configDesc[csPos + 4] | (configDesc[csPos + 5] << 8);
                                    e.assocTerminal = configDesc[csPos + 6];
                                    e.cSourceId = configDesc[csPos + 7]; // bCSourceID
                                }
                                if (csLen >= 9) {
                                    // bNrChannels at csPos+8
                                }
                                LOGI("AC InputTerminal: id=0x%02X type=0x%04X assocTerminal=0x%02X cSourceId=0x%02X",
                                     e.id, e.terminalType, e.assocTerminal, e.cSourceId);
                                break;

                            case AC_ENTITY_OUTPUT_TERMINAL: // 0x03
                                if (csLen >= 8) {
                                    e.terminalType = configDesc[csPos + 4] | (configDesc[csPos + 5] << 8);
                                    e.assocTerminal = configDesc[csPos + 6];
                                    e.sourceId = configDesc[csPos + 7]; // bSourceID
                                }
                                if (csLen >= 9) {
                                    e.cSourceId = configDesc[csPos + 8]; // bCSourceID
                                }
                                LOGI("AC OutputTerminal: id=0x%02X type=0x%04X assocTerminal=0x%02X sourceId=0x%02X cSourceId=0x%02X",
                                     e.id, e.terminalType, e.assocTerminal, e.sourceId, e.cSourceId);
                                break;

                            case AC_ENTITY_FEATURE_UNIT: // 0x06
                                if (csLen >= 5) {
                                    e.fuSourceId = configDesc[csPos + 4]; // bSourceID
                                }
                                // UAC2 bmControls: 4 bytes per channel, starting at csPos+5
                                // bControlSize is at csPos+5 in UAC1, but in UAC2 it's 4 bytes per channel
                                // Format: bLength bDescriptorType bDescriptorSubtype bUnitID bSourceID bmaControls(ch0) bmaControls(ch1) ...
                                if (csLen >= 7) {
                                    // bControlSize for UAC1 (csLen - 6) / bControlSize = number of channels + 1
                                    // UAC2: each channel has 4 bytes of bmControls
                                    int controlDataStart = 5; // offset from csPos
                                    int remaining = csLen - controlDataStart;
                                    int chIdx = 0;
                                    while (remaining >= 4 && chIdx < 32) {
                                        uint32_t ctrl = (uint32_t)configDesc[csPos + controlDataStart]
                                                      | ((uint32_t)configDesc[csPos + controlDataStart + 1] << 8)
                                                      | ((uint32_t)configDesc[csPos + controlDataStart + 2] << 16)
                                                      | ((uint32_t)configDesc[csPos + controlDataStart + 3] << 24);
                                        e.controlsByChannel.push_back(ctrl);
                                        controlDataStart += 4;
                                        remaining -= 4;
                                        chIdx++;
                                    }
                                }
                                {
                                    char ctrlBuf[256] = {};
                                    int off = 0;
                                    for (size_t ci = 0; ci < e.controlsByChannel.size() && off < 200; ci++) {
                                        off += snprintf(ctrlBuf + off, sizeof(ctrlBuf) - off, "%sch%zu=0x%08X",
                                                        ci > 0 ? " " : "", ci, e.controlsByChannel[ci]);
                                    }
                                    LOGI("AC FeatureUnit: id=0x%02X sourceId=0x%02X controls=[%s]", e.id, e.fuSourceId, ctrlBuf);
                                }
                                break;

                            case AC_ENTITY_CLOCK_SOURCE: // 0x0A
                                if (csLen >= 5) {
                                    e.bmAttributes = configDesc[csPos + 4]; // bmAttributes
                                }
                                if (csLen >= 6) {
                                    e.bmControls = configDesc[csPos + 5]; // bmControls
                                }
                                LOGI("AC ClockSource: id=0x%02X bmAttributes=0x%02X bmControls=0x%02X",
                                     e.id, e.bmAttributes, e.bmControls);
                                break;

                            case AC_ENTITY_CLOCK_SELECTOR: // 0x0B
                                if (csLen >= 5) {
                                    e.nrPins = configDesc[csPos + 4]; // bNrInPins
                                    e.numSourceIds = 0;
                                    for (int p = 0; p < e.nrPins && p < 16 && (5 + p) < csLen; p++) {
                                        e.sourceIds[p] = configDesc[csPos + 5 + p]; // baCSourceID
                                        e.numSourceIds++;
                                    }
                                }
                                {
                                    char srcBuf[128] = {};
                                    int off = 0;
                                    for (int p = 0; p < e.numSourceIds && off < 100; p++) {
                                        off += snprintf(srcBuf + off, sizeof(srcBuf) - off, "%s0x%02X",
                                                        p > 0 ? "," : "", e.sourceIds[p]);
                                    }
                                    LOGI("AC ClockSelector: id=0x%02X nrPins=%d sources=[%s]",
                                         e.id, e.nrPins, srcBuf);
                                }
                                break;

                            case AC_ENTITY_CLOCK_MULTIPLIER: // 0x0C
                                if (csLen >= 6) {
                                    e.cSourceId = configDesc[csPos + 4]; // bCSourceID
                                    e.bmControls = configDesc[csPos + 5]; // bmControls
                                }
                                LOGI("AC ClockMultiplier: id=0x%02X cSourceId=0x%02X bmControls=0x%02X",
                                     e.id, e.cSourceId, e.bmControls);
                                break;

                            case AC_ENTITY_MIXER_UNIT: // 0x04
                                LOGI("AC MixerUnit: id=0x%02X", e.id);
                                break;

                            case AC_ENTITY_SELECTOR_UNIT: // 0x05
                                if (csLen >= 5) {
                                    e.nrPins = configDesc[csPos + 4];
                                    e.numSourceIds = 0;
                                    for (int p = 0; p < e.nrPins && p < 16 && (5 + p) < csLen; p++) {
                                        e.sourceIds[p] = configDesc[csPos + 5 + p];
                                        e.numSourceIds++;
                                    }
                                }
                                LOGI("AC SelectorUnit: id=0x%02X nrPins=%d", e.id, e.nrPins);
                                break;

                            default:
                                LOGI("AC Entity: id=0x%02X subtype=0x%02X (unknown)", e.id, subtype);
                                break;
                        }
                        topo.entities.push_back(e);
                    }
                    csPos += csLen;
                }
            }
        }
        pos += bLength;
    }

    LOGI("AC topology: %zu entities parsed", topo.entities.size());
    return topo;
}

// ==========================
// AC Topology: 查找 entity by ID
// ==========================
static const AcEntity* findEntityById(const std::vector<AcEntity> &entities, uint8_t id) {
    for (const auto &e : entities) {
        if (e.id == id) return &e;
    }
    return nullptr;
}

// ==========================
// Clock Selector: GET_CUR (读取当前选中的 pin)
// ==========================
#define UAC2_CS_CONTROL_SELECTOR 0x01
#define UAC2_CS_SAM_FREQ_CONTROL 0x01
// UAC2: SET_CUR 和 GET_CUR 的 bRequest 都是 0x01（方向由 bmRequestType 决定）
// UAC1: SET_CUR=0x01, GET_CUR=0x81
#define UAC1_SET_CUR 0x01
#define UAC1_GET_CUR 0x81
#define UAC2_REQ_CUR   0x01
#define UAC2_REQ_RANGE 0x02

// UAC2 Class-Specific Request Constants
static constexpr uint8_t USB_REQ_TYPE_CLASS_INTERFACE_OUT = 0x21;
static constexpr uint8_t USB_REQ_TYPE_CLASS_INTERFACE_IN  = 0xA1;
static constexpr uint8_t UAC2_CS_CUR   = 0x01;
static constexpr uint8_t UAC2_CS_RANGE = 0x02;

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
// 从 terminalLink 查找 clock entity（支持 Clock Selector resolve）
// ==========================
// 返回: 最终的 Clock Source ID（0 = 失败）
// outAcInterface: AC interface number
// outClockSupportsRead: bmControls 表明是否支持 GET_CUR (Freq Read Control)
// outIsClockSelector: 中间是否经过了 Clock Selector
// outResolvedClockSourceId: 最终 resolve 到的 Clock Source ID
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

    // 1. 查找 terminalLink 对应的 Terminal entity
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

    // 2. 查找 clockId 对应的 entity
    const AcEntity *clockEntity = findEntityById(entities, clockId);
    if (!clockEntity) {
        LOGE("Clock entity 0x%02X not found in AC topology", clockId);
        return 0;
    }

    // 3. 如果是 Clock Source，直接返回
    if (clockEntity->subtype == AC_ENTITY_CLOCK_SOURCE) {
        outClockSupportsRead = (clockEntity->bmControls & 0x02) != 0; // bit1 = Frequency Read Control
        outIsClockSelector = false;
        outResolvedClockSourceId = clockId;
        LOGI("Clock for stream: terminalLink=0x%02X clockEntity=0x%02X type=ClockSource bmControls=0x%02X supportsRead=%d",
             terminalLink, clockId, clockEntity->bmControls, outClockSupportsRead);
        return clockId;
    }

    // 4. 如果是 Clock Selector，需要 resolve
    if (clockEntity->subtype == AC_ENTITY_CLOCK_SELECTOR) {
        outIsClockSelector = true;
        LOGI("Clock for stream: terminalLink=0x%02X clockEntity=0x%02X type=ClockSelector nrPins=%d — need resolve",
             terminalLink, clockId, clockEntity->nrPins);
        // Clock Selector 的 resolve 需要运行时 GET_CUR，这里只返回 selector ID
        // 调用者需要进一步调用 resolveClockSelector()
        return clockId;
    }

    LOGE("Entity 0x%02X is not Clock Source or Clock Selector, subtype=0x%02X", clockId, clockEntity->subtype);
    return 0;
}

// ==========================
// Resolve Clock Selector → Clock Source（运行时 GET_CUR 获取当前选中 pin）
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

    // GET_CUR: 读取当前选中的 pin（1-based）
    uint8_t currentPin = 0;
    int ret = uac2_clock_selector_get_cur(devh, acInterface, selectorId, &currentPin);
    if (ret != 0) {
        LOGE("Cannot read ClockSelector 0x%02X current pin, cannot resolve", selectorId);
        return 0;
    }

    // pin 是 1-based，sourceIds 是 0-based
    if (currentPin < 1 || currentPin > sel->numSourceIds) {
        LOGE("ClockSelector 0x%02X currentPin=%d out of range (1..%d)", selectorId, currentPin, sel->numSourceIds);
        return 0;
    }

    uint8_t selectedClockSourceId = sel->sourceIds[currentPin - 1];
    LOGI("ClockSelector 0x%02X currentPin=%d selectedClockSource=0x%02X",
         selectorId, currentPin, selectedClockSourceId);

    // 验证选中的 entity 确实是 Clock Source
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
                176400, 192000, 352800, 384000
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
        *outRate = read_u24_le(data);
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
// UAC2 sample rate configuration (best-effort, non-fatal)
// SET_CUR EIO is NOT treated as hard failure.
// Returns LIBUSB_SUCCESS if rate is configured/verified,
// otherwise returns the error code but caller should NOT abort init.
// ==========================
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

    // 4. SET_CUR failed — try GET_CUR to see current state
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
// 已知设备时钟速率查表
// 当 UAC2 时钟控制完全失败时，通过 VID/PID 查询设备实际时钟速率
// 返回 0 表示未知设备
// ==========================
static uint32_t getKnownDeviceClockRate(uint16_t vid, uint16_t pid) {
    // FiiO 系列 DAC：内部时钟固定 96kHz，UAC2 时钟控制全部返回 LIBUSB_ERROR_IO
    if (vid == 0x2972) { // FiiO
        // 已知型号：
        //   0x0062 = FiiO BTR series / KA series / etc.
        //   其他型号也多为 96kHz 固定时钟
        return 96000;
    }
    // 可以在这里添加更多已知设备
    // if (vid == 0xXXXX && pid == 0xYYYY) return 48000;
    return 0; // 未知设备
}

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
        if (ctx->uac1EpHasSamplingFreqControl) {
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
                        LOGW("UAC1 endpoint rate mismatch: requested=%u actual=%u, continuing cautiously",
                             sampleRate, actual);
                    } else {
                        LOGI("UAC1 sample rate verified OK: %u", actual);
                    }
                }
                return 0;
            }
            LOGW("UAC1 endpoint sample rate SET failed. If descriptor has fixed rate, continuing.");
            return 0;
        }
        LOGI("UAC1 stream has no endpoint sampling frequency control. Treat as fixed-rate stream.");
        return 0;
    }
    if (ctx->protocol == USB_AUDIO_UAC2) {
        LOGI("Configure sample rate: UAC2 clock path (best-effort)");
        uint8_t acIface = 0;
        bool clockSupportsRead = false;
        bool isClockSelector = false;
        uint8_t resolvedClockSourceId = 0;
        uint8_t clockId = findClockForStreamTerminal(
                acEntities,
                ctx->terminalLink,
                acIface,
                clockSupportsRead,
                isClockSelector,
                resolvedClockSourceId
        );
        if (clockId == 0) {
            LOGE("UAC2: Cannot find clock for terminalLink=0x%02X",
                 ctx->terminalLink);
            // Don't hard fail — some devices work without explicit clock config
            LOGW("UAC2: Continuing without clock configuration");
            return 0;
        }
        uint8_t finalClockSourceId = clockId;
        if (isClockSelector) {
            LOGI("UAC2: Clock entity is selector 0x%02X, resolving", clockId);
            finalClockSourceId = resolveClockSelector(
                    ctx->devHandle,
                    acEntities,
                    clockId,
                    acIface,
                    clockSupportsRead
            );
            if (finalClockSourceId == 0) {
                LOGW("UAC2: Failed to resolve ClockSelector 0x%02X, continuing", clockId);
                return 0;
            }
        }
        int srConfigResult = configureUac2SampleRateBestEffort(
                ctx->devHandle,
                acIface,
                finalClockSourceId,
                sampleRate
        );
        if (srConfigResult == LIBUSB_SUCCESS) {
            LOGI("Sample rate configured/verified successfully");
        } else {
            LOGW(
                "Sample rate configure failed: %s. Probing actual device clock rate...",
                libusb_error_name(srConfigResult)
            );
            // ===== 时钟控制失败：探测设备实际采样率 =====
            // 重试 GET_CUR 多次（某些设备需要时间初始化时钟）
            uint32_t probedRate = 0;
            for (int retry = 0; retry < 3 && probedRate == 0; retry++) {
                usleep(50000); // 50ms 间隔
                int getRet = uac2GetCurSampleRate(
                        ctx->devHandle, acIface, finalClockSourceId, &probedRate);
                if (getRet == LIBUSB_SUCCESS && probedRate > 0) {
                    LOGI("GET_CUR retry %d succeeded: actual rate = %u", retry + 1, probedRate);
                }
            }
            // GET_CUR 仍失败，通过 VID/PID 查表
            if (probedRate == 0) {
                probedRate = getKnownDeviceClockRate(ctx->vendorId, ctx->productId);
                if (probedRate > 0) {
                    LOGI("Using known device clock rate for VID=%04X PID=%04X: %u Hz",
                         ctx->vendorId, ctx->productId, probedRate);
                }
            }
            // 探测到实际速率与请求速率不同 → 更新 sampleRate
            if (probedRate > 0 && probedRate != sampleRate) {
                LOGW("Device actual clock rate (%u Hz) differs from requested (%u Hz). "
                     "Switching to device rate for correct playback.", probedRate, sampleRate);
                ctx->sampleRate = (int)probedRate;
                // 重采样将在后续 initSwrContext 中自动启用
            } else if (probedRate == 0) {
                LOGW("Cannot determine device actual clock rate. "
                     "Using requested rate %u Hz (may cause issues).", sampleRate);
            }
        }
        // Never hard-fail on sample rate config for UAC2
        return 0;
    }
    LOGW("Unknown USB Audio protocol, skip sample rate control");
    return 0;
}

// ==========================
// 查找 playback path 上的 Feature Unit
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
     * Playback path 常见：
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
// 永远解析，不受 bit-perfect 等策略影响
// ==========================
static bool selectPlaybackFeatureUnit(UsbAudioContext* ctx, AcTopology& topo) {
    if (!ctx) return false;
    uint8_t terminalLink = ctx->terminalLink;
    LOGI("Selecting playback Feature Unit: terminalLink=0x%02X", terminalLink);

    // 最直接路径：FeatureUnit.fuSourceId == AS terminalLink
    if (AcEntity* fu = topo.findFeatureUnitBySource(terminalLink)) {
        ctx->featureUnitPresent = true;
        ctx->playbackFeatureUnitId = fu->id;
        ctx->playbackFeatureAcInterface = fu->acInterface;
        LOGI("Playback FeatureUnit selected direct: fu=0x%02X sourceId=0x%02X terminalLink=0x%02X",
             fu->id, fu->fuSourceId, terminalLink);
        return true;
    }

    // 备用：沿拓扑往下找一层（OutputTerminal.sourceId -> FeatureUnit, FeatureUnit.fuSourceId -> terminalLink）
    for (auto& e : topo.entities) {
        if (e.subtype == AC_ENTITY_FEATURE_UNIT && e.fuSourceId == terminalLink) {
            ctx->featureUnitPresent = true;
            ctx->playbackFeatureUnitId = e.id;
            ctx->playbackFeatureAcInterface = e.acInterface;
            LOGI("Playback FeatureUnit selected fallback: fu=0x%02X sourceId=0x%02X",
                 e.id, e.fuSourceId);
            return true;
        }
    }

    ctx->featureUnitPresent = false;
    ctx->playbackFeatureUnitId = 0;
    ctx->playbackFeatureAcInterface = 0;
    LOGW("No playback FeatureUnit found for terminalLink=0x%02X", terminalLink);
    return false;
}

// ==========================
// UAC2 Feature Unit: GET_CUR volume
// ==========================
#define UAC_FU_VOLUME  0x02

static int uac2GetCurVolume(UsbAudioContext* ctx, uint8_t channel, int16_t* outRaw) {
    if (!ctx || !outRaw) return -1;
    uint16_t wValue = (UAC_FU_VOLUME << 8) | channel;
    uint16_t wIndex = (ctx->playbackFeatureUnitId << 8) | ctx->playbackFeatureAcInterface;
    uint8_t data[2] = {0};
    int r = libusb_control_transfer(
            ctx->devHandle,
            LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
            UAC2_REQ_CUR,
            wValue,
            wIndex,
            data,
            sizeof(data),
            500
    );
    if (r != 2) {
        LOGW("UAC2 GET_CUR volume failed: fu=0x%02X ch=%u r=%d",
             ctx->playbackFeatureUnitId, channel, r);
        return r < 0 ? r : -2;
    }
    *outRaw = (int16_t)(data[0] | (data[1] << 8));
    LOGI("UAC2 GET_CUR volume ok: fu=0x%02X ch=%u raw=%d db=%.2f",
         ctx->playbackFeatureUnitId, channel, *outRaw, *outRaw / 256.0f);
    return 0;
}

// ==========================
// UAC2 Feature Unit: SET_CUR volume
// ==========================
static int uac2SetCurVolume(UsbAudioContext* ctx, uint8_t channel, int16_t raw) {
    if (!ctx) return -1;
    uint16_t wValue = (UAC_FU_VOLUME << 8) | channel;
    uint16_t wIndex = (ctx->playbackFeatureUnitId << 8) | ctx->playbackFeatureAcInterface;
    uint8_t data[2] = {
            (uint8_t)(raw & 0xFF),
            (uint8_t)((raw >> 8) & 0xFF)
    };
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
    if (r != 2) {
        LOGW("UAC2 SET_CUR volume failed: fu=0x%02X ch=%u raw=%d db=%.2f r=%d",
             ctx->playbackFeatureUnitId, channel, raw, raw / 256.0f, r);
        return r < 0 ? r : -2;
    }
    LOGI("UAC2 SET_CUR volume ok: fu=0x%02X ch=%u raw=%d db=%.2f",
         ctx->playbackFeatureUnitId, channel, raw, raw / 256.0f);
    return 0;
}

// ==========================
// UAC2 Feature Unit: GET_RANGE volume
// 返回 wNumSubRanges + 每个 subrange (min, max, res)，各 2 bytes
// ==========================
static int uac2GetRangeVolume(
        UsbAudioContext* ctx,
        uint8_t channel,
        int16_t* outMin,
        int16_t* outMax,
        int16_t* outRes
) {
    if (!ctx || !outMin || !outMax || !outRes) return -1;
    uint16_t wValue = (UAC_FU_VOLUME << 8) | channel;
    uint16_t wIndex = (ctx->playbackFeatureUnitId << 8) | ctx->playbackFeatureAcInterface;
    uint8_t data[64] = {0};
    int r = libusb_control_transfer(
            ctx->devHandle,
            LIBUSB_ENDPOINT_IN | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
            UAC2_REQ_RANGE,
            wValue,
            wIndex,
            data,
            sizeof(data),
            500
    );
    if (r < 8) {
        LOGW("UAC2 GET_RANGE volume failed: fu=0x%02X ch=%u r=%d",
             ctx->playbackFeatureUnitId, channel, r);
        return r < 0 ? r : -2;
    }
    uint16_t num = data[0] | (data[1] << 8);
    if (num < 1) {
        LOGW("UAC2 GET_RANGE volume invalid num=0 ch=%u", channel);
        return -3;
    }
    int16_t minRaw = (int16_t)(data[2] | (data[3] << 8));
    int16_t maxRaw = (int16_t)(data[4] | (data[5] << 8));
    int16_t resRaw = (int16_t)(data[6] | (data[7] << 8));
    *outMin = minRaw;
    *outMax = maxRaw;
    *outRes = resRaw;
    LOGI("UAC2 GET_RANGE volume ok: fu=0x%02X ch=%u min=%.2f max=%.2f res=%.2f num=%u",
         ctx->playbackFeatureUnitId,
         channel,
         minRaw / 256.0f,
         maxRaw / 256.0f,
         resRaw / 256.0f,
         num);
    return 0;
}

// ==========================
// 硬件音量能力验证
// 1. 必须有 playback Feature Unit
// 2. 尝试 ch0 master / ch1 left / ch2 right GET_RANGE
// 3. 判断支持 master-only 还是 stereo-pair
// 4. 如果只支持 left 不支持 right，禁用
// 5. 写入安全音量
// 6. 读回确认一致
// ==========================
static int validateHardwareVolume(UsbAudioContext* ctx) {
    if (!ctx) return -1;
    ctx->hardwareVolumeCapable = false;
    ctx->hardwareVolumeSafe = false;
    ctx->hardwareVolumeEnabled = false;

    if (!ctx->featureUnitPresent || ctx->playbackFeatureUnitId == 0) {
        LOGW("validateHardwareVolume: no playback Feature Unit present");
        return -2;
    }

    int16_t min0 = 0, max0 = 0, res0 = 0;
    int16_t min1 = 0, max1 = 0, res1 = 0;
    int16_t min2 = 0, max2 = 0, res2 = 0;
    int r0 = uac2GetRangeVolume(ctx, 0, &min0, &max0, &res0);
    int r1 = uac2GetRangeVolume(ctx, 1, &min1, &max1, &res1);
    int r2 = uac2GetRangeVolume(ctx, 2, &min2, &max2, &res2);

    ctx->hasMasterVolume = (r0 == 0);
    ctx->hasLeftVolume = (r1 == 0);
    ctx->hasRightVolume = (r2 == 0);
    ctx->masterChannelExists = (r0 == 0);  // 记录 master 通道物理存在

    LOGI("FeatureUnit capability:");
    LOGI("    ch0 GET_RANGE result=%d min=%d max=%d res=%d",
         r0, min0, max0, res0);
    LOGI("    ch1 GET_RANGE result=%d min=%d max=%d res=%d",
         r1, min1, max1, res1);
    LOGI("    ch2 GET_RANGE result=%d min=%d max=%d res=%d",
         r2, min2, max2, res2);

    // 读当前音量（同时用于 fallback 探测）
    int16_t probeCur0 = 0, probeCur1 = 0, probeCur2 = 0;
    int g0 = uac2GetCurVolume(ctx, 0, &probeCur0);
    int g1 = uac2GetCurVolume(ctx, 1, &probeCur1);
    int g2 = uac2GetCurVolume(ctx, 2, &probeCur2);
    LOGI("    ch0 GET_CUR probe result=%d cur=%d", g0, probeCur0);
    LOGI("    ch1 GET_CUR probe result=%d cur=%d", g1, probeCur1);
    LOGI("    ch2 GET_CUR probe result=%d cur=%d", g2, probeCur2);

    // GET_CUR 降级: 如果 GET_RANGE 成功但 GET_CUR 失败，说明该通道不可靠
    // 某些 USB DAC 的 master 通道能报告范围但无法读取/正确设置当前值，
    // 导致 SET_CUR master 只影响左声道，右声道不变，产生 L/R 不平衡
    if (ctx->hasMasterVolume && g0 != 0) {
        ctx->hasMasterVolume = false;
        LOGW("Master GET_RANGE ok but GET_CUR failed (r=%d), downgrading to stereo control", g0);
    }
    if (ctx->hasLeftVolume && g1 != 0) {
        ctx->hasLeftVolume = false;
        LOGW("Left GET_RANGE ok but GET_CUR failed (r=%d), disabling left volume", g1);
    }
    if (ctx->hasRightVolume && g2 != 0) {
        ctx->hasRightVolume = false;
        LOGW("Right GET_RANGE ok but GET_CUR failed (r=%d), disabling right volume", g2);
    }

    // GET_RANGE fallback: 如果 GET_RANGE 失败但 GET_CUR 成功，也认为该通道可用
    if (!ctx->hasMasterVolume && g0 == 0) {
        ctx->hasMasterVolume = true;
        LOGW("Master GET_RANGE unavailable, but GET_CUR probe succeeded, using fallback range -60..0 dB");
    }
    if (!ctx->hasLeftVolume && g1 == 0) {
        ctx->hasLeftVolume = true;
        LOGW("Left GET_RANGE unavailable, but GET_CUR probe succeeded");
    }
    if (!ctx->hasRightVolume && g2 == 0) {
        ctx->hasRightVolume = true;
        LOGW("Right GET_RANGE unavailable, but GET_CUR probe succeeded");
    }
    LOGI("FeatureUnit fallback probe: master=%d left=%d right=%d",
         ctx->hasMasterVolume ? 1 : 0, ctx->hasLeftVolume ? 1 : 0, ctx->hasRightVolume ? 1 : 0);

    ctx->featureUnitHasMasterVolume = ctx->hasMasterVolume;

    LOGI("Hardware volume support: fu=0x%02X master=%d left=%d right=%d",
         ctx->playbackFeatureUnitId,
         ctx->hasMasterVolume ? 1 : 0,
         ctx->hasLeftVolume ? 1 : 0,
         ctx->hasRightVolume ? 1 : 0);

    // 必须有 master 或同时有 L+R
    if (!ctx->hasMasterVolume && !(ctx->hasLeftVolume && ctx->hasRightVolume)) {
        LOGW("validateHardwareVolume: no safe volume path (master=%d L=%d R=%d)",
             ctx->hasMasterVolume ? 1 : 0, ctx->hasLeftVolume ? 1 : 0, ctx->hasRightVolume ? 1 : 0);
        return -3;
    }

    // 更新 volume range (含 GET_RANGE fallback 保守范围)
    if (ctx->hasMasterVolume) {
        if (r0 == 0) {
            ctx->volMinRaw = min0;
            ctx->volMaxRaw = max0;
            ctx->volumeMinDb = (float)min0 / 256.0f;
            ctx->volumeMaxDb = (float)max0 / 256.0f;
        } else {
            // fallback 保守范围：-60dB ~ 0dB
            ctx->volMinRaw = (int16_t)lrintf(-60.0f * 256.0f);
            ctx->volMaxRaw = 0;
            ctx->volumeMinDb = -60.0f;
            ctx->volumeMaxDb = 0.0f;
            LOGW("Master GET_RANGE unavailable, using fallback range -60..0 dB");
        }
    } else {
        if (r1 == 0 && r2 == 0) {
            ctx->volMinRaw = std::max(min1, min2);
            ctx->volMaxRaw = std::min(max1, max2);
            ctx->volumeMinDb = (float)ctx->volMinRaw / 256.0f;
            ctx->volumeMaxDb = (float)ctx->volMaxRaw / 256.0f;
        } else {
            // fallback 保守范围：-60dB ~ 0dB
            ctx->volMinRaw = (int16_t)lrintf(-60.0f * 256.0f);
            ctx->volMaxRaw = 0;
            ctx->volumeMinDb = -60.0f;
            ctx->volumeMaxDb = 0.0f;
            LOGW("Stereo GET_RANGE unavailable, using fallback range -60..0 dB");
        }
    }
    // 强制分辨率 1/256 dB（忽略设备报告的分辨率，避免粗步进导致跳变）
    ctx->volResRaw = 1;

    // 安全初始音量：-20dB，如果设备范围不允许则 clamp
    int16_t safeRaw = (int16_t)lrintf(-20.0f * 256.0f);
    if (safeRaw < ctx->volMinRaw) safeRaw = ctx->volMinRaw;
    if (safeRaw > ctx->volMaxRaw) safeRaw = ctx->volMaxRaw;

    // 写入安全音量
    int setResult = 0;
    if (ctx->hasMasterVolume) {
        setResult = uac2SetCurVolume(ctx, 0, safeRaw);
        if (setResult != 0) {
            LOGW("validateHardwareVolume: master SET_CUR failed r=%d", setResult);
            return -4;
        }
    } else {
        // 非 master 模式：将 master 设为与 L/R 相同的安全音量，
        // 避免 master 0dB 导致瞬间大声
        if (ctx->masterChannelExists) {
            int rm = uac2SetCurVolume(ctx, 0, safeRaw);
            if (rm != 0) {
                LOGW("validateHardwareVolume: master set to safe vol failed r=%d (non-fatal)", rm);
            } else {
                LOGI("validateHardwareVolume: set master to safe vol (%d / %.2fdB)", safeRaw, safeRaw / 256.0f);
            }
        }
        int sl = uac2SetCurVolume(ctx, 1, safeRaw);
        int sr = uac2SetCurVolume(ctx, 2, safeRaw);
        if (sl != 0 || sr != 0) {
            LOGW("validateHardwareVolume: stereo SET_CUR failed sl=%d sr=%d", sl, sr);
            return -5;
        }
    }

    // 读回验证
    int16_t cur0 = 0, cur1 = 0, cur2 = 0;
    int c0 = ctx->hasMasterVolume ? uac2GetCurVolume(ctx, 0, &cur0) : -1;
    int c1 = ctx->hasLeftVolume ? uac2GetCurVolume(ctx, 1, &cur1) : -1;
    int c2 = ctx->hasRightVolume ? uac2GetCurVolume(ctx, 2, &cur2) : -1;

    if (ctx->hasLeftVolume && ctx->hasRightVolume && c1 == 0 && c2 == 0) {
        int diff = std::abs((int)cur1 - (int)cur2);
        if (diff > 2) {
            LOGE("validateHardwareVolume: L/R mismatch cur1=%d cur2=%d diff=%d",
                 cur1, cur2, diff);
            return -6;
        }
    }

    ctx->hardwareVolumeCapable = true;
    ctx->hardwareVolumeSafe = true;
    ctx->hardwareVolumeEnabled = true;
    ctx->featureUnitAvailable = true;
    ctx->hardwareFeatureUnitEnabled = true; // compat
    LOGI("Hardware volume validation result=0 (OK)");
    LOGI("validateHardwareVolume OK: fu=0x%02X min=%.2f max=%.2f res=%.2f",
         ctx->playbackFeatureUnitId,
         ctx->volMinRaw / 256.0f,
         ctx->volMaxRaw / 256.0f,
         ctx->volResRaw / 256.0f);
    return 0;
}

// ==========================
// 线性音量转 UAC raw 值
// linear 0.0~1.0 → dB → 1/256 dB raw
// 量化到 volResRaw 步进
// ==========================
static int16_t linearToUacRaw(UsbAudioContext* ctx, float linear) {
    if (!ctx) return 0;
    if (linear < 0.0001f) linear = 0.0001f;
    if (linear > 1.0f) linear = 1.0f;
    float db = 20.0f * log10f(linear);
    int16_t raw = (int16_t)lrintf(db * 256.0f);
    if (raw < ctx->volMinRaw) raw = ctx->volMinRaw;
    if (raw > ctx->volMaxRaw) raw = ctx->volMaxRaw;
    // 量化到分辨率步进
    if (ctx->volResRaw > 0) {
        int base = ctx->volMinRaw;
        int step = ctx->volResRaw;
        raw = (int16_t)(base + ((raw - base) / step) * step);
    }
    return raw;
}

// ==========================
// 安全设置 USB 硬件音量
// 保证：写 left 必须写 right，写后读回验证
// ==========================
static int setUsbHardwareVolumeSafe(UsbAudioContext* ctx, float linear) {
    if (!ctx) return -1;
    if (!ctx->hardwareVolumeSafe || !ctx->hardwareVolumeEnabled) {
        LOGW("setUsbHardwareVolumeSafe rejected: hardware volume not safe/enabled");
        return -2;
    }
    int16_t raw = linearToUacRaw(ctx, linear);

    if (ctx->hasMasterVolume) {
        int r = uac2SetCurVolume(ctx, 0, raw);
        if (r != 0) {
            ctx->hardwareVolumeSafe = false;
            ctx->hardwareVolumeEnabled = false;
            ctx->hardwareFeatureUnitEnabled = false;
            LOGE("setUsbHardwareVolumeSafe: master failed, disabling hardware volume");
            return r;
        }
    } else if (ctx->hasLeftVolume && ctx->hasRightVolume) {
        // 非 master 模式：将 master 设为与 L/R 相同值，避免 0dB 瞬间大声
        if (ctx->masterChannelExists) {
            uac2SetCurVolume(ctx, 0, raw);
        }
        int l = uac2SetCurVolume(ctx, 1, raw);
        int r = uac2SetCurVolume(ctx, 2, raw);
        if (l != 0 || r != 0) {
            ctx->hardwareVolumeSafe = false;
            ctx->hardwareVolumeEnabled = false;
            ctx->hardwareFeatureUnitEnabled = false;
            LOGE("setUsbHardwareVolumeSafe: stereo failed l=%d r=%d, disabling hardware volume", l, r);
            return -3;
        }
        // 读回验证 L/R 一致
        int16_t curL = 0, curR = 0;
        int gl = uac2GetCurVolume(ctx, 1, &curL);
        int gr = uac2GetCurVolume(ctx, 2, &curR);
        if (gl == 0 && gr == 0) {
            int diff = std::abs((int)curL - (int)curR);
            if (diff > 2) {
                ctx->hardwareVolumeSafe = false;
                ctx->hardwareVolumeEnabled = false;
                ctx->hardwareFeatureUnitEnabled = false;
                LOGE("setUsbHardwareVolumeSafe: L/R mismatch after set curL=%d curR=%d", curL, curR);
                return -4;
            }
        }
    } else {
        LOGW("setUsbHardwareVolumeSafe: no usable volume path");
        return -5;
    }

    gSoftwareVolume.store(linear, std::memory_order_release);
    LOGI("Hardware volume set: linear=%.3f raw=%d db=%.2f",
         linear, raw, raw / 256.0f);
    return 0;
}

// ==========================
// Feature Unit: 设置 Mute
// ==========================
static int setFeatureUnitMute(libusb_device_handle *handle, uint8_t acInterface,
                               uint8_t featureUnitId, uint8_t mute) {
    // UAC2 Entity 请求的 wIndex = (entityId << 8) | interfaceNumber
    uint16_t wIndex = ((featureUnitId & 0xFF) << 8) | (acInterface & 0xFF);
    uint16_t wValue = (0x01 << 8) | 0x00; // Mute control, master channel
    int ret = libusb_control_transfer(handle,
                                      LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
                                      0x01, // SET_CUR
                                      wValue,
                                      wIndex,
                                      &mute, 1, 1000);
    if (ret >= 0) {
        LOGI("Feature Unit 0x%02X MUTE set to %d (wIndex=0x%04X)", featureUnitId, mute, wIndex);
    } else {
        LOGE("Feature Unit 0x%02X MUTE failed: %s (wIndex=0x%04X)", featureUnitId, libusb_strerror(ret), wIndex);
    }
    return ret;
}

// ==========================
// Feature Unit: 设置 Volume (1/256 dB)
// ==========================
static int setFeatureUnitVolume(libusb_device_handle *handle, uint8_t acInterface,
                                 uint8_t featureUnitId, int16_t volume) {
    // UAC2 Entity 请求的 wIndex = (entityId << 8) | interfaceNumber
    uint16_t wIndex = ((featureUnitId & 0xFF) << 8) | (acInterface & 0xFF);
    uint16_t wValue = (0x02 << 8) | 0x00; // Volume control, master channel
    uint8_t data[2] = { (uint8_t)(volume & 0xFF), (uint8_t)((volume >> 8) & 0xFF) };
    int ret = libusb_control_transfer(handle,
                                      LIBUSB_ENDPOINT_OUT | LIBUSB_REQUEST_TYPE_CLASS | LIBUSB_RECIPIENT_INTERFACE,
                                      0x01, // SET_CUR
                                      wValue,
                                      wIndex,
                                      data, 2, 1000);
    if (ret >= 0) {
        LOGI("Feature Unit 0x%02X VOLUME set to %d (wIndex=0x%04X)", featureUnitId, volume, wIndex);
    } else {
        LOGE("Feature Unit 0x%02X VOLUME failed: %s (wIndex=0x%04X)", featureUnitId, libusb_strerror(ret), wIndex);
    }
    return ret;
}

// ==========================
// Feature Unit: 读取 Volume (1/256 dB) per channel
// ==========================
#define FU_CONTROL_MUTE   0x01
#define FU_CONTROL_VOLUME 0x02

static int getFeatureUnitVolume(
        libusb_device_handle *handle,
        uint8_t acInterface,
        uint8_t featureUnitId,
        uint8_t channel,
        int16_t *outVolume
) {
    uint16_t wIndex = ((uint16_t)featureUnitId << 8) | acInterface;
    uint16_t wValue = (FU_CONTROL_VOLUME << 8) | channel;
    uint8_t data[2] = {0};
    int ret = libusb_control_transfer(
            handle,
            LIBUSB_ENDPOINT_IN |
            LIBUSB_REQUEST_TYPE_CLASS |
            LIBUSB_RECIPIENT_INTERFACE,
            UAC2_REQ_CUR,
            wValue,
            wIndex,
            data,
            2,
            1000
    );
    if (ret == 2) {
        *outVolume = (int16_t)((uint16_t)data[0] | ((uint16_t)data[1] << 8));
        LOGI("Feature Unit 0x%02X GET_VOLUME ch=%d volume=%d (%.2f dB)",
             featureUnitId,
             channel,
             *outVolume,
             (double)(*outVolume) / 256.0);
        return 0;
    }
    LOGW("Feature Unit 0x%02X GET_VOLUME ch=%d failed: r=%d %s",
         featureUnitId,
         channel,
         ret,
         ret < 0 ? libusb_error_name(ret) : "short");
    return ret < 0 ? ret : -1;
}

// ==========================
// 设置 USB 硬件音量（linear 0.0~1.0 → dB → UAC 1/256 dB）
// 写入后验证 L/R 一致性，不一致则标记 hardwareVolumeSafe=false
// ==========================
static int setUsbHardwareVolume(UsbAudioContext *ctx, float linear) {
    if (!ctx || !ctx->devHandle) return -1;
    if (!ctx->featureUnitAvailable) return -2;
    if (linear < 0.0001f) {
        linear = 0.0001f;
    }
    // linear -> dB
    float db = 20.0f * log10f(linear);
    if (db < ctx->volumeMinDb) db = ctx->volumeMinDb;
    if (db > ctx->volumeMaxDb) db = ctx->volumeMaxDb;
    int16_t uacVol = (int16_t)lrintf(db * 256.0f);
    uint8_t data[2];
    data[0] = (uint8_t)(uacVol & 0xff);
    data[1] = (uint8_t)((uacVol >> 8) & 0xff);

    int reqType =
            LIBUSB_ENDPOINT_OUT |
            LIBUSB_REQUEST_TYPE_CLASS |
            LIBUSB_RECIPIENT_INTERFACE;
    int request = 0x01; // SET_CUR
    int controlSelector = 0x02; // VOLUME_CONTROL
    uint8_t acIface = ctx->playbackFeatureAcInterface;
    uint8_t fuId = ctx->playbackFeatureUnitId;
    int timeout = 1000;

    if (ctx->featureUnitHasMasterVolume) {
        uint16_t wValue = (controlSelector << 8) | 0; // channel 0 master
        uint16_t wIndex = (fuId << 8) | acIface;
        int r = libusb_control_transfer(
                ctx->devHandle,
                reqType,
                request,
                wValue,
                wIndex,
                data,
                2,
                timeout
        );
        if (r == 2) {
            ctx->hardwareVolumeSafe = true;
            return 0;
        }
        ctx->hardwareVolumeSafe = false;
        return r < 0 ? r : -10;
    }
    // 没有 master，则同时设置 L/R，避免左右不一致
    bool ok = true;
    for (int ch = 1; ch <= ctx->channels; ch++) {
        uint16_t wValue = (controlSelector << 8) | ch;
        uint16_t wIndex = (fuId << 8) | acIface;
        int r = libusb_control_transfer(
                ctx->devHandle,
                reqType,
                request,
                wValue,
                wIndex,
                data,
                2,
                timeout
        );
        if (r != 2) {
            LOGW("SET_CUR hardware volume failed: ch=%d r=%d", ch, r);
            ok = false;
        }
    }

    if (!ok) {
        ctx->hardwareVolumeSafe = false;
        return -11;
    }

    // 写后验证：读回 L/R 通道音量，确认一致
    if (ctx->channels >= 2) {
        usleep(5000); // 等待 DAC 处理
        int16_t leftVol = 0, rightVol = 0;
        int rlRet = getFeatureUnitVolume(ctx->devHandle, acIface, fuId, 1, &leftVol);
        int rrRet = getFeatureUnitVolume(ctx->devHandle, acIface, fuId, 2, &rightVol);
        if (rlRet == 0 && rrRet == 0) {
            if (leftVol != rightVol) {
                LOGE("Hardware volume L/R MISMATCH after write! L=%d (%.2f dB) R=%d (%.2f dB) - disabling hardware volume",
                     leftVol, (double)leftVol / 256.0, rightVol, (double)rightVol / 256.0);
                ctx->hardwareVolumeSafe = false;
                g_hardwareVolumeSafe.store(false, std::memory_order_release);
                return -12;
            }
        } else {
            LOGW("Hardware volume readback failed (L=%d R=%d), cannot verify L/R balance", rlRet, rrRet);
            // 读回失败不算致命，可能是设备不支持 per-channel 读
        }
    }

    ctx->hardwareVolumeSafe = true;
    return 0;
}

// ==========================
// 停止传输（内部辅助）
// ==========================
// ==========================
// stopUsbAudioInternal: 停止 ISO 传输并等待 callback 完成
// 不释放资源，不 join event thread，只确保所有 in-flight transfer 回来
// ==========================
static void stopUsbAudioInternal(UsbAudioContext* h) {
    if (!h) return;
    h->acceptingWrites.store(false, std::memory_order_release);
    bool wasStreaming = h->streaming.exchange(false, std::memory_order_acq_rel);
    LOGI("Stopping USB audio... streaming=%d acceptingWrites=%d closing=%d pending=%d fbPending=%d",
         wasStreaming ? 1 : 0,
         h->acceptingWrites.load() ? 1 : 0,
         h->closing.load(std::memory_order_acquire) ? 1 : 0,
         h->pendingTransfers.load(std::memory_order_acquire),
         h->pendingFeedbackTransfers.load(std::memory_order_acquire));

    // cancel ISO transfers
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        if (h->transfers[i]) {
            int r = libusb_cancel_transfer(h->transfers[i]);
            if (r != LIBUSB_SUCCESS && r != LIBUSB_ERROR_NOT_FOUND) {
                LOGW("cancel iso transfer %d failed: %s", i, libusb_error_name(r));
            }
        }
    }

    // cancel feedback transfer
    if (h->feedbackTransfer) {
        int r = libusb_cancel_transfer(h->feedbackTransfer);
        if (r != LIBUSB_SUCCESS && r != LIBUSB_ERROR_NOT_FOUND) {
            LOGW("cancel feedback transfer failed: %s", libusb_error_name(r));
        }
    }

    // 等待 callback 回来
    {
        std::unique_lock<std::mutex> lock(h->stopMutex);
        bool allDone = h->stopCV.wait_for(lock, std::chrono::milliseconds(1500), [&]() {
            return h->pendingTransfers.load(std::memory_order_acquire) <= 0 &&
                   h->pendingFeedbackTransfers.load(std::memory_order_acquire) <= 0;
        });
        if (!allDone) {
            LOGE("stop timeout: pending transfers still alive (ISO=%d FB=%d)",
                 h->pendingTransfers.load(), h->pendingFeedbackTransfers.load());
        }
    }

    LOGI("USB audio stopped: pending=%d fbPending=%d",
         h->pendingTransfers.load(), h->pendingFeedbackTransfers.load());
}

static void stopStreamingLocked(UsbAudioContext *ctx) {
    if (!ctx) return;

    bool lost = ctx->transportLost.load(std::memory_order_acquire);
    bool wasStreaming = ctx->streaming.exchange(false, std::memory_order_acq_rel);
    if (!wasStreaming) {
        LOGI("stopStreamingLocked ignored: already stopped");
        return;
    }
    ctx->stopping.store(true, std::memory_order_release);
    ctx->acceptingWrites.store(false, std::memory_order_release);
    LOGI("Stopping USB audio... streaming=%d acceptingWrites=%d closing=%d pending=%d fbPending=%d transportLost=%d",
         wasStreaming ? 1 : 0,
         ctx->acceptingWrites.load() ? 1 : 0,
         ctx->closing.load(std::memory_order_acquire) ? 1 : 0,
         ctx->pendingTransfers.load(),
         ctx->pendingFeedbackTransfers.load(),
         lost ? 1 : 0);

    // transportLost 路径：快速切断，不执着 cancel。底层已经坏了。
    if (lost) {
        LOGW("Fast stop due to USB transport lost");
        ctx->eventThreadRunning.store(false, std::memory_order_release);
        if (ctx->eventThread.joinable()) {
            ctx->eventThread.join();
        }
        ctx->pendingTransfers.store(0, std::memory_order_release);
        ctx->pendingFeedbackTransfers.store(0, std::memory_order_release);
        LOGW("Fast stop finished, old native USB session must be released/reopened");
        return;
    }

    int pending = ctx->pendingTransfers.load(std::memory_order_acquire);
    int pendingFb = ctx->pendingFeedbackTransfers.load(std::memory_order_acquire);
    if (pending > 0 || pendingFb > 0) {
        for (int i = 0; i < NUM_TRANSFERS; i++) {
            if (ctx->transfers[i]) {
                int ret = libusb_cancel_transfer(ctx->transfers[i]);
                if (ret < 0 && ret != LIBUSB_ERROR_NOT_FOUND) {
                    LOGW("cancel ISO %d failed: %s", i, libusb_error_name(ret));
                }
            }
        }
        if (ctx->feedbackTransfer) {
            int ret = libusb_cancel_transfer(ctx->feedbackTransfer);
            if (ret < 0 && ret != LIBUSB_ERROR_NOT_FOUND) {
                LOGW("cancel feedback failed: %s", libusb_error_name(ret));
            }
        }

        {
            std::unique_lock<std::mutex> lk(ctx->stopMutex);
            bool allDone = ctx->stopCV.wait_for(
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
    }

    ctx->eventThreadRunning.store(false, std::memory_order_release);
    if (ctx->eventThread.joinable()) {
        ctx->eventThread.join();
    }

    int left = ctx->pendingTransfers.load(std::memory_order_acquire);
    int leftFb = ctx->pendingFeedbackTransfers.load(std::memory_order_acquire);
    if (left > 0 || leftFb > 0) {
        LOGE("Still pending transfers: ISO=%d FB=%d after event thread stopped. "
             "Skip freeing to avoid crash.", left, leftFb);
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

    // 正常 stop 完成后清除 stopping 标志，允许后续 nativeWrite（如 prefill）
    ctx->stopping.store(false, std::memory_order_release);
    LOGI("USB audio stopped: pending=%d fbPending=%d",
         ctx->pendingTransfers.load(), ctx->pendingFeedbackTransfers.load());
}

// ==========================
// cleanupUsbHandle: 释放 USB 资源（不含 delete）
// 调用者需持有 handleMutex
// ==========================
static void cleanupUsbHandle(UsbAudioContext *ctx) {
    if (!ctx) return;
    LOGI("cleanupUsbHandle begin: handle=%p", ctx);

    // 停止流
    stopStreamingLocked(ctx);

    // 释放 USB 资源
    if (ctx->devHandle) {
        if (ctx->claimDoneByNative && ctx->interfaceNumber >= 0) {
            int r = libusb_set_interface_alt_setting(ctx->devHandle, ctx->interfaceNumber, 0);
            if (r == LIBUSB_SUCCESS) {
                LOGI("set alt0 ok: iface=%d", ctx->interfaceNumber);
            } else {
                LOGW("set alt0 failed: iface=%d err=%s", ctx->interfaceNumber, libusb_strerror(r));
            }
            r = libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
            if (r == LIBUSB_SUCCESS) {
                LOGI("release_interface ok: iface=%d", ctx->interfaceNumber);
            } else {
                LOGW("release_interface failed: iface=%d err=%s", ctx->interfaceNumber, libusb_strerror(r));
            }
        }
        if (ctx->acInterfaceClaimed) {
            int rAc = libusb_release_interface(ctx->devHandle, 0);
            if (rAc == LIBUSB_SUCCESS) {
                LOGI("release AC interface ok: iface=0");
            } else {
                LOGW("release AC interface failed: %s", libusb_error_name(rAc));
            }
            ctx->acInterfaceClaimed = false;
        }
        libusb_close(ctx->devHandle);
        ctx->devHandle = nullptr;
    }
    if (ctx->libusbCtx) {
        libusb_exit(ctx->libusbCtx);
        ctx->libusbCtx = nullptr;
    }

    // 安全关闭 dupFd
    if (ctx->dupFd >= 0) {
        if (fcntl(ctx->dupFd, F_GETFD) >= 0) {
            close(ctx->dupFd);
            LOGI("closed dupFd=%d", ctx->dupFd);
        }
        ctx->dupFd = -1;
    }

    // 释放缓冲区
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        delete[] ctx->transferBuffers[i];
        ctx->transferBuffers[i] = nullptr;
    }
    delete[] ctx->feedbackBuffer;
    ctx->feedbackBuffer = nullptr;

    LOGI("cleanupUsbHandle end");
}

// ==========================
// PCM 格式转换：source → USB device 格式
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
    size_t frames = srcBytes / srcFrame;
    size_t maxFrames = dstCapacity / dstFrame;
    if (frames > maxFrames) {
        frames = maxFrames;
    }
    switch (ctx->pcmAdapter) {
        case PCM_ADAPTER_NONE: {
            size_t bytes = frames * srcFrame;
            memcpy(dst, src, bytes);
            return bytes;
        }
        case PCM_ADAPTER_S16_TO_S24: {
            for (size_t f = 0; f < frames; f++) {
                const uint8_t *inFrame = src + f * srcFrame;
                uint8_t *outFrame = dst + f * dstFrame;
                for (int ch = 0; ch < ctx->sourceChannels; ch++) {
                    const uint8_t *s = inFrame + ch * 2;
                    uint8_t *d = outFrame + ch * 3;
                    d[0] = 0x00;
                    d[1] = s[0];
                    d[2] = s[1];
                }
            }
            return frames * dstFrame;
        }
        case PCM_ADAPTER_S16_TO_S32: {
            for (size_t f = 0; f < frames; f++) {
                const uint8_t *inFrame = src + f * srcFrame;
                uint8_t *outFrame = dst + f * dstFrame;
                for (int ch = 0; ch < ctx->sourceChannels; ch++) {
                    const uint8_t *s = inFrame + ch * 2;
                    uint8_t *d = outFrame + ch * 4;
                    d[0] = 0x00;
                    d[1] = 0x00;
                    d[2] = s[0];
                    d[3] = s[1];
                }
            }
            return frames * dstFrame;
        }
        case PCM_ADAPTER_S24_TO_S32: {
            for (size_t f = 0; f < frames; f++) {
                const uint8_t *inFrame = src + f * srcFrame;
                uint8_t *outFrame = dst + f * dstFrame;
                for (int ch = 0; ch < ctx->sourceChannels; ch++) {
                    const uint8_t *s = inFrame + ch * 3;
                    uint8_t *d = outFrame + ch * 4;
                    d[0] = 0x00;
                    d[1] = s[0];
                    d[2] = s[1];
                    d[3] = s[2];
                }
            }
            return frames * dstFrame;
        }
        case PCM_ADAPTER_S32_TO_S24: {
            // S32LE (4B) → S24 (3B)：丢弃低 8 位，保留高 24 位
            // little-endian: [b0=LSB, b1, b2, b3=MSB] → [b1, b2, b3]
            for (size_t f = 0; f < frames; f++) {
                const uint8_t *inFrame = src + f * srcFrame;
                uint8_t *outFrame = dst + f * dstFrame;
                for (int ch = 0; ch < ctx->sourceChannels; ch++) {
                    const uint8_t *s = inFrame + ch * 4;
                    uint8_t *d = outFrame + ch * 3;
                    d[0] = s[1];
                    d[1] = s[2];
                    d[2] = s[3];
                }
            }
            return frames * dstFrame;
        }
        default:
            LOGW("convertPcmToUsbFormat: unknown adapter=%d, returning 0", ctx->pcmAdapter);
            return 0;
    }
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

    std::lock_guard<std::mutex> lk(gRegistryMtx);
    auto it = gLiveHandles.begin();
    if (it == gLiveHandles.end()) {
        LOGE("nativeWrite: no live handle -> ERR_NOT_INITIALIZED");
        return ERR_NOT_INITIALIZED;
    }
    UsbAudioContext *ctx = *it;
    if (!ctx) {
        LOGE("nativeWrite: ctx null -> ERR_NOT_INITIALIZED");
        return ERR_NOT_INITIALIZED;
    }
    if (!isLiveHandle(ctx)) {
        LOGW("nativeWrite ignored: dead handle=%p", ctx);
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

    // 数据校验：首次写入打印前 32 字节（检测 WAV 头误送），之后每 10000 次打印前 4 字节
    static int logCounter = 0;
    if (logCounter == 0 && length >= 32 && offset + 31 < arrayLen) {
        // 首次写入：打印前 32 字节，用于检测是否把 RIFF/WAV 头送进了 USB
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
        // 检查是否是 RIFF 头
        if (bytes[offset] == 0x52 && bytes[offset+1] == 0x49 &&
            bytes[offset+2] == 0x46 && bytes[offset+3] == 0x46) {
            LOGE("!!! WAV HEADER DETECTED IN PCM DATA — RIFF header sent to USB DAC! This means WAV header is NOT being skipped! !!!");
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
        std::lock_guard<std::mutex> lock(ctx->ringMutex);
        size_t bufSize = ctx->pcmRingBuffer.size();
        size_t used = ringAvailable(ctx);
        float usageRatio = (float)used / (float)bufSize;
        // soft limit 按 USB 输出字节率计算
        size_t softLimitBytes =
                (size_t)ctx->sampleRate * ctx->bytesPerFrame * 3 / 2;
        if (used >= softLimitBytes) {
            ctx->writeThrottleUs.store(15000, std::memory_order_relaxed);
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return 0;
        }
        int delayUs = 0;
        if (usageRatio < 0.45f) {
            delayUs = 0;
        } else if (usageRatio < 0.55f) {
            delayUs = 2000;
        } else if (usageRatio < 0.70f) {
            delayUs = 8000;
        } else {
            delayUs = 15000;
        }
        ctx->writeThrottleUs.store(delayUs, std::memory_order_relaxed);
        size_t freeSpace = (bufSize - 1) - used;
        // 输入按 source frame 对齐
        size_t srcAvailable = (size_t)length;
        srcAvailable = (srcAvailable / ctx->sourceBytesPerFrame) *
                       ctx->sourceBytesPerFrame;
        // freeSpace 按 USB frame 对齐
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

        if (ctx->needsResample && ctx->swrCtx) {
            // ===== 重采样路径：swr_convert 同时处理采样率+格式转换 =====
            // 输出帧大小 = 设备格式（swr 输出是 device format）
            const int outFrameSize = ctx->deviceChannels * ctx->deviceSubslotSize;
            // 重新计算可容纳的输出帧数（按设备帧对齐）
            size_t devAlignedFree = (freeSpace / outFrameSize) * outFrameSize;
            size_t maxDevFrames = devAlignedFree / outFrameSize;
            if (maxDevFrames == 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }
            // 计算 swr 内部缓冲的延迟样本
            int delayed = (int)swr_get_delay(ctx->swrCtx, ctx->sourceSampleRate);
            // 估算给定 inputFrames 会产生多少 output frames
            int64_t estimatedOut = av_rescale(
                    (int64_t)inputFrames + delayed,
                    ctx->sampleRate, ctx->sourceSampleRate) - delayed;
            if (estimatedOut > (int64_t)maxDevFrames) {
                // 缩减输入以适配 ring buffer 空间
                inputFrames = (size_t)av_rescale(
                        (int64_t)maxDevFrames, ctx->sourceSampleRate, ctx->sampleRate);
                if (inputFrames == 0) {
                    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                    return 0;
                }
            }
            sourceBytesConsumed = inputFrames * ctx->sourceBytesPerFrame;

            const uint8_t *inBuf[1] = { src };
            uint8_t *outBuf[1] = { ctx->swrOutBuffer.data() };
            int outSamples = swr_convert(
                    ctx->swrCtx, outBuf, (int)maxDevFrames,
                    inBuf, (int)inputFrames);
            if (outSamples <= 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return (jint)sourceBytesConsumed;
            }
            size_t outBytes = (size_t)outSamples * outFrameSize;

            // 写入 ring buffer（可能回绕）
            size_t w = ctx->pcmWritePos.load(std::memory_order_relaxed);
            size_t firstPart = bufSize - w;
            if (outBytes <= firstPart) {
                memcpy(ctx->pcmRingBuffer.data() + w, ctx->swrOutBuffer.data(), outBytes);
            } else {
                memcpy(ctx->pcmRingBuffer.data() + w, ctx->swrOutBuffer.data(), firstPart);
                memcpy(ctx->pcmRingBuffer.data(),
                       ctx->swrOutBuffer.data() + firstPart, outBytes - firstPart);
            }
            ctx->pcmWritePos.store((w + outBytes) % bufSize, std::memory_order_relaxed);
            usbBytesWritten = outBytes;
        } else {
            // ===== 非重采样路径：原有 convertPcmToUsbFormat =====
            size_t framesToWrite = inputFrames;
            if (framesToWrite > maxOutputFrames) {
                framesToWrite = maxOutputFrames;
            }
            sourceBytesConsumed = framesToWrite * ctx->sourceBytesPerFrame;
            size_t outputBytesNeeded = framesToWrite * ctx->bytesPerFrame;

            size_t w = ctx->pcmWritePos.load(std::memory_order_relaxed);
            size_t firstPartCapacity = bufSize - w;
            firstPartCapacity = (firstPartCapacity / ctx->bytesPerFrame) *
                                ctx->bytesPerFrame;
            size_t firstPartOut = outputBytesNeeded;
            if (firstPartOut > firstPartCapacity) {
                firstPartOut = firstPartCapacity;
            }
            size_t firstFrames = firstPartOut / ctx->bytesPerFrame;
            size_t firstSrcBytes = firstFrames * ctx->sourceBytesPerFrame;
            // 第一段转换写入
            size_t wrote1 = convertPcmToUsbFormat(
                    ctx,
                    src,
                    firstSrcBytes,
                    ctx->pcmRingBuffer.data() + w,
                    firstPartOut
            );
            usbBytesWritten += wrote1;
            // 软件音量已移到 fillIsoTransfer（发送 USB transfer 时应用），不再在 nativeWrite 时应用
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
                    std::memory_order_relaxed
            );
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
    // 返回 Java 实际消费的源 PCM 字节数，不是 USB 输出字节数
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
    auto it = gLiveHandles.begin();
    if (it == gLiveHandles.end()) return JNI_FALSE;
    UsbAudioContext *ctx = *it;
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
    auto it = gLiveHandles.begin();
    if (it == gLiveHandles.end()) return JNI_FALSE;
    UsbAudioContext *ctx = *it;
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
    auto* ctx = reinterpret_cast<UsbAudioContext*>(handle);
    if (!isLiveHandle(ctx)) return;
    std::lock_guard<std::mutex> lock(ctx->ringMutex);
    ctx->pcmWritePos.store(0, std::memory_order_relaxed);
    ctx->pcmReadPos.store(0, std::memory_order_relaxed);
    ctx->starved = false;
}

// ==========================
// JNI: nativeGetRecommendedDelayUs
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetRecommendedDelayUs(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    auto it = gLiveHandles.begin();
    if (it == gLiveHandles.end()) return 5000;
    UsbAudioContext *ctx = *it;
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
    auto it = gLiveHandles.begin();
    if (it == gLiveHandles.end()) return 0;
    UsbAudioContext *ctx = *it;
    if (!ctx) return 0;
    std::lock_guard<std::mutex> lock(ctx->ringMutex);
    return (jint)ringAvailable(ctx);
}

// ==========================
// JNI: nativeGetPacketSize
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetPacketSize(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    auto it = gLiveHandles.begin();
    if (it == gLiveHandles.end()) return 0;
    UsbAudioContext *ctx = *it;
    if (!ctx) return 0;
    return ctx->transferSize;
}

// ==========================
// JNI: nativeSetSampleRate
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetSampleRate(
        JNIEnv *env, jobject thiz, jint sampleRate) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    auto it = gLiveHandles.begin();
    if (it == gLiveHandles.end()) return -1;
    UsbAudioContext *ctx = *it;
    if (!ctx) return -1;
    if (!ctx->devHandle) return -1;
    if (sampleRate <= 0) return -1;
    ctx->sampleRate = sampleRate;
    // 设备采样率变更 → 重建重采样上下文
    int swrRet = initSwrContext(ctx);
    if (swrRet != 0) {
        LOGW("nativeSetSampleRate: re-init swr failed (%d), resampling may be broken", swrRet);
    }
    // 根据重采样/格式适配状态更新帧大小
    // ring buffer 存储设备格式数据时（重采样或PCM适配），使用设备帧大小
    if (ctx->needsResample || ctx->pcmAdapter != PCM_ADAPTER_NONE) {
        ctx->bytesPerFrame = ctx->deviceBytesPerFrame;
    } else {
        ctx->bytesPerFrame = ctx->sourceBytesPerFrame;
    }
    ctx->bytes_per_second = sampleRate * ctx->bytesPerFrame;
    ctx->bytesPerPacket = (sampleRate * ctx->bytesPerFrame) / ctx->serviceIntervalsPerSecond;
    if (ctx->bytesPerPacket == 0) ctx->bytesPerPacket = 1;
    // 重建 IsoPacer（帧大小可能已变）
    ctx->isoPacer.reset(
            (uint32_t)ctx->sampleRate,
            (uint32_t)ctx->serviceIntervalsPerSecond,
            (uint32_t)ctx->bytesPerFrame,
            ctx->maxPacketSize
    );
    ctx->nominalSampleRate = (uint32_t)ctx->sampleRate;
    LOGI("nativeSetSampleRate: %d -> bytesPerFrame=%d bytesPerPacket=%d resample=%d",
         sampleRate, ctx->bytesPerFrame, ctx->bytesPerPacket, ctx->needsResample ? 1 : 0);
    return 0;
}

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
    auto *ctx = reinterpret_cast<UsbAudioContext*>(handle);

    if (!isLiveHandle(ctx)) {
        LOGW("nativeWriteHandle ignored: dead handle=%p", ctx);
        return ERR_NOT_INITIALIZED;
    }
    if (ctx->closing.load(std::memory_order_acquire)) {
        LOGW("nativeWriteHandle ignored: closing handle=%p", ctx);
        return -EPIPE;
    }
    if (!ctx->acceptingWrites.load(std::memory_order_acquire)) {
        LOGW("nativeWriteHandle ignored: not accepting writes, handle=%p", ctx);
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

    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return -2;

    // 一次性诊断：打印首次写入的 PCM 前 16 字节
    {
        static std::atomic<int> firstWriteDump{0};
        if (firstWriteDump.fetch_add(1) == 0) {
            int dumpLen = (length < 16) ? length : 16;
            char hex[80] = {0};
            int pos = 0;
            for (int i = 0; i < dumpLen && pos < 72; i++) {
                pos += snprintf(hex + pos, 72 - pos, "%02X ",
                                (uint8_t)bytes[offset + i]);
            }
            LOGI("nativeWriteHandle FIRST WRITE: length=%d srcFrame=%d dstFrame=%d "
                 "adapter=%d needsResample=%d first16=[%s]",
                 length, ctx->sourceBytesPerFrame, ctx->bytesPerFrame,
                 (int)ctx->pcmAdapter, (int)ctx->needsResample, hex);
        }
    }

    int written = 0;
    {
        std::lock_guard<std::mutex> lock(ctx->ringMutex);
        size_t bufSize = ctx->pcmRingBuffer.size();
        size_t used = ringAvailable(ctx);
        size_t freeSpace = (bufSize - 1) - used;

        if (freeSpace == 0) {
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return 0;
        }

        if (ctx->needsResample && ctx->swrCtx) {
            // ===== 重采样路径：source → device（采样率+格式） =====
            // 输出帧大小 = 设备格式
            const int outFrameSize = ctx->deviceChannels * ctx->deviceSubslotSize;
            size_t devAlignedFree = (freeSpace / outFrameSize) * outFrameSize;
            size_t maxOutputFrames = devAlignedFree / outFrameSize;
            if (maxOutputFrames == 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }
            size_t srcFrames = (size_t)length / ctx->sourceBytesPerFrame;
            if (srcFrames == 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }
            // 估算输出帧数，若超出则缩减输入
            int delayed = (int)swr_get_delay(ctx->swrCtx, ctx->sourceSampleRate);
            int64_t estOut = av_rescale(
                    (int64_t)srcFrames + delayed,
                    ctx->sampleRate, ctx->sourceSampleRate) - delayed;
            if (estOut > (int64_t)maxOutputFrames) {
                srcFrames = (size_t)av_rescale(
                        (int64_t)maxOutputFrames, ctx->sourceSampleRate, ctx->sampleRate);
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
            size_t outBytes = (size_t)outSamples * outFrameSize;
            size_t w = ctx->pcmWritePos.load(std::memory_order_relaxed);
            size_t firstPart = bufSize - w;
            if (outBytes <= firstPart) {
                memcpy(ctx->pcmRingBuffer.data() + w, ctx->swrOutBuffer.data(), outBytes);
            } else {
                memcpy(ctx->pcmRingBuffer.data() + w, ctx->swrOutBuffer.data(), firstPart);
                memcpy(ctx->pcmRingBuffer.data(),
                       ctx->swrOutBuffer.data() + firstPart, outBytes - firstPart);
            }
            ctx->pcmWritePos.store((w + outBytes) % bufSize, std::memory_order_relaxed);
            written = (int)outBytes;
        } else {
            // ===== 非重采样路径 =====
            // 关键：必须帧对齐 freeSpace，防止写入非整帧数据导致炸音
            const int srcFrameSize = ctx->sourceBytesPerFrame;
            const int dstFrameSize = ctx->bytesPerFrame;
            size_t alignedFree = (freeSpace / dstFrameSize) * dstFrameSize;
            if (alignedFree == 0) {
                env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
                return 0;
            }

            if (ctx->pcmAdapter != PCM_ADAPTER_NONE) {
                // 有格式适配（如 S16→S24），使用 convertPcmToUsbFormat
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

                size_t w = ctx->pcmWritePos.load(std::memory_order_relaxed);
                size_t firstPartCap = bufSize - w;
                firstPartCap = (firstPartCap / dstFrameSize) * dstFrameSize;
                size_t firstPartOut = (outNeeded <= firstPartCap) ? outNeeded : firstPartCap;
                size_t firstFrames = firstPartOut / dstFrameSize;
                size_t firstSrcBytes = firstFrames * srcFrameSize;

                size_t wrote1 = convertPcmToUsbFormat(
                        ctx, reinterpret_cast<const uint8_t*>(bytes + offset),
                        firstSrcBytes, ctx->pcmRingBuffer.data() + w, firstPartOut);
                written = (int)wrote1;
                size_t remainingOut = outNeeded - wrote1;
                if (remainingOut > 0) {
                    size_t consumedFrames1 = wrote1 / dstFrameSize;
                    const uint8_t *src2 = reinterpret_cast<const uint8_t*>(bytes + offset)
                                          + consumedFrames1 * srcFrameSize;
                    size_t remainingSrc = (srcFrames - consumedFrames1) * srcFrameSize;
                    size_t wrote2 = convertPcmToUsbFormat(
                            ctx, src2, remainingSrc,
                            ctx->pcmRingBuffer.data(), remainingOut);
                    written += (int)wrote2;
                }
                ctx->pcmWritePos.store((w + written) % bufSize, std::memory_order_relaxed);
            } else {
                // 无格式适配，直接 memcpy
                size_t toWrite = ((size_t)length > alignedFree) ? alignedFree : (size_t)length;
                // 二次对齐（确保 toWrite 帧对齐）
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
                    ctx->pcmWritePos.store((w + toWrite) % bufSize, std::memory_order_relaxed);
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
// bit-perfect + 硬件音量安全 → setUsbHardwareVolumeSafe
// bit-perfect + 无硬件音量 → 拒绝
// 非 bit-perfect → 软件音量（写入全局 + 所有 live handle）
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetVolume(
        JNIEnv* env, jobject thiz, jlong handle, jfloat linear) {
    if (handle == 0) return -1;
    auto* ctx = reinterpret_cast<UsbAudioContext*>(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return -1;

    float vol = linear;
    if (vol < 0.0f) vol = 0.0f;
    if (vol > 1.0f) vol = 1.0f;

    LOGI("nativeSetVolume(handle=0x%llx) linear=%.3f mode=%d",
         (unsigned long long)handle, vol, (int)ctx->playbackMode);

    // 位完美 + 硬件音量路径
    if (ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectHwVol ||
        (ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectFixed &&
         ctx->hardwareFeatureUnitRequested && ctx->hardwareVolumeEnabled)) {
        int r = setUsbHardwareVolumeSafe(ctx, vol);
        if (r != 0) {
            LOGW("setUsbHardwareVolumeSafe failed (r=%d) -> downgrade to Fixed mode", r);
            // 自动回退到 Fixed（软音量）
            ctx->playbackMode = UsbPlaybackMode::ExclusiveBitPerfectFixed;
            // 软音量仍然需要写到全局，以免 UI 失去音量反馈
            gSoftwareVolume.store(vol, std::memory_order_release);
            ctx->softwareVolume.store(vol, std::memory_order_relaxed);
        }
        return r;
    }

    // ---- 软音量路径（所有非位完美模式） ----
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

// ==========================
// JNI: nativeRequiresReinit
// UI 层判断是否需要重新 init（策略在运行中被切换）
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
// USB 拔出时必须强制关闭完美比特
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeOnUsbDetached(
        JNIEnv *env, jobject thiz) {
    LOGW("USB detached: reset exclusive and bit-perfect state");
    g_usbExclusiveActive.store(false, std::memory_order_release);
    g_bitPerfectEnabled.store(false, std::memory_order_release);
    g_hardwareFeatureUnitRequested.store(false, std::memory_order_release);
    g_requiresReinit.store(false, std::memory_order_release);
    g_bitPerfectFixedVolumeAcknowledged.store(false, std::memory_order_release);
    g_hardwareVolumeValidated.store(false, std::memory_order_release);
    g_hardwareVolumeSafe.store(false, std::memory_order_release);
    // 恢复默认音量
    gSoftwareVolume.store(kDefaultDevicePolicy.safeInitialVolumeLinear, std::memory_order_release);
    // 同步到所有 live handle
    {
        std::lock_guard<std::mutex> lock(gRegistryMtx);
        for (auto* h : gLiveHandles) {
            if (h) h->softwareVolume.store(kDefaultDevicePolicy.safeInitialVolumeLinear, std::memory_order_relaxed);
        }
    }
}

// ==========================
// JNI: nativeResetUsbPolicyForNewDevice
// USB 插入新设备时重置策略（不触发 detach 的副作用）
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeResetUsbPolicyForNewDevice(
        JNIEnv *env, jobject thiz) {
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
    LOGI("USB policy reset for new device: exclusive=0 bitPerfect=0 hwFeatureUnitRequested=0 volume=%.2f",
         kDefaultDevicePolicy.safeInitialVolumeLinear);
}

// ==========================
// JNI: nativeSetUsbExclusiveActive
// 独占模式开启后才允许 bit-perfect
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetUsbExclusiveActive(
        JNIEnv *env, jobject thiz, jboolean active) {
    bool v = active == JNI_TRUE;
    bool old = g_usbExclusiveActive.exchange(v, std::memory_order_acq_rel);
    LOGI("nativeSetUsbExclusiveActive: %d", v ? 1 : 0);
    if (!v) {
        // 一旦退出独占，立刻关闭完美比特
        g_bitPerfectEnabled.store(false, std::memory_order_release);
        g_requiresReinit.store(true, std::memory_order_release);
        LOGW("USB exclusive disabled: bit-perfect forced OFF");
    } else if (old != v) {
        g_requiresReinit.store(true, std::memory_order_release);
    }
}

// ==========================
// JNI: nativeCanControlVolume (handle-based)
// 给 UI 判断是否需要显示/拦截硬件音量键。
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeCanControlVolume(
        JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return JNI_FALSE;
    auto* ctx = reinterpret_cast<UsbAudioContext*>(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return JNI_FALSE;
    // 只有在以下两种模式下硬件音量才可用
    // 1) 位完美 + 已经成功验证 Feature Unit（hardwareVolumeEnabled）
    // 2) 非位完美情况下，用户打开了 “软硬件混合” 功能（旧的 SafeSoftwareVolume）
    bool can = (ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectHwVol) ||
               (ctx->playbackMode == UsbPlaybackMode::ExclusiveSoftwareVolume &&
                ctx->hardwareFeatureUnitRequested);
    LOGI("nativeCanControlVolume(handle=0x%llx) => %d (mode=%d)",
         (unsigned long long)handle, can, (int)ctx->playbackMode);
    return can ? JNI_TRUE : JNI_FALSE;
}

// ==========================
// JNI: nativeGetVolumeDb (handle-based)
// 返回当前硬件音量的分贝值（从 GET_CUR 读取）
// ==========================
extern "C"
JNIEXPORT jfloat JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetVolumeDb(
        JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return 0.0f;
    auto* ctx = reinterpret_cast<UsbAudioContext*>(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return 0.0f;
    if (!ctx->hardwareVolumeSafe || !ctx->hardwareVolumeEnabled) return 0.0f;

    int16_t cur = 0;
    int ch = ctx->hasMasterVolume ? 0 : 1;  // master 优先，否则用 left
    if (uac2GetCurVolume(ctx, ch, &cur) == 0) {
        return cur / 256.0f;
    }
    return 0.0f;
}

// ==========================
// JNI: nativeValidateHardwareVolume (handle-based)
// 用于 “手动打开硬件音量” 的按钮（或在策略切换时自动触发），
// 它会执行 GET_RANGE -> SET_CUR -> GET_CUR 检查并返回 0 表示安全。
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeValidateHardwareVolume(
        JNIEnv* env, jobject thiz, jlong handle) {
    if (handle == 0) return -1;
    auto* ctx = reinterpret_cast<UsbAudioContext*>(handle);
    if (!isLiveHandle(ctx) || !ctx->devHandle) return -1;
    // 这里调用一次完整的验证流程，**不影响正在播放的流**（因为我们只读写 Feature Unit）
    int rc = validateHardwareVolume(ctx);
    if (rc == 0) {
        ctx->hardwareFeatureUnitEnabled = true;
        ctx->hardwareVolumeEnabled      = true;
        ctx->hardwareVolumeSafe        = true;
        LOGI("Hardware volume validated OK (FU=0x%02X)", ctx->playbackFeatureUnitId);
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
    auto it = gLiveHandles.begin();
    if (it == gLiveHandles.end()) return JNI_FALSE;
    UsbAudioContext *ctx = *it;
    if (!ctx) return JNI_FALSE;
    return ctx->hardwareVolumeSafe ? JNI_TRUE : JNI_FALSE;
}

// ==========================
// JNI: nativeRepairHardwareVolumeBalance
// 修复被写乱的 DAC 左右音量：先临时验证能力，再写安全音量到 master/L/R，读回验证
// 返回值：0=修复成功，-1=无 ctx，-2=无 Feature Unit，-3=L/R 仍不一致，-4=无可用通道
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeRepairHardwareVolumeBalance(
        JNIEnv *env, jobject thiz, jfloat safeVolume) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    auto it = gLiveHandles.begin();
    if (it == gLiveHandles.end()) {
        LOGW("repairHardwareVolumeBalance: no live handle");
        return -1;
    }
    UsbAudioContext *ctx = *it;
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

    float v = safeVolume;
    if (v < 0.05f) v = 0.05f;
    if (v > 0.30f) v = 0.30f;
    int16_t raw = linearToUacRaw(ctx, v);

    int ok = 0;
    if (ctx->hasMasterVolume) {
        if (uac2SetCurVolume(ctx, 0, raw) == 0) ok++;
    }
    if (ctx->hasLeftVolume && ctx->hasRightVolume) {
        int l = uac2SetCurVolume(ctx, 1, raw);
        int rr = uac2SetCurVolume(ctx, 2, raw);
        if (l == 0 && rr == 0) ok++;
    }

    usleep(30000); // 等 DAC 处理

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
// 返回当前 UsbPlaybackMode 枚举值
// ==========================
extern "C"
JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeGetPlaybackMode(
        JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lk(gRegistryMtx);
    auto it = gLiveHandles.begin();
    if (it == gLiveHandles.end()) {
        return (jint)UsbPlaybackMode::SafeSoftwareVolume;
    }
    UsbAudioContext *ctx = *it;
    if (!ctx) return (jint)UsbPlaybackMode::SafeSoftwareVolume;
    return (jint)ctx->playbackMode;
}

// ==========================
// JNI: nativeInitUsbDevice（新架构）
// Java 只 openDevice → 拿 fd，native 统一 claim + set_alt
// ==========================
extern "C"
JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeInitUsbDevice(
        JNIEnv *env, jobject thiz,
        jint fd, jint sampleRate, jint channels, jint bitsPerSample,
        jint iface, jint alt, jint outEndpoint, jint feedbackEndpoint,
        jint subslotSize
) {
    (void) env;
    (void) thiz;
    LOGI("nativeInitUsbDevice: fd=%d sr=%d ch=%d bits=%d iface=%d alt=%d outEp=0x%02X fbEp=0x%02X subslot=%d",
         fd, sampleRate, channels, bitsPerSample, iface, alt,
         outEndpoint, feedbackEndpoint, subslotSize);

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

    if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0 || subslotSize <= 0) {
        LOGE("nativeInitUsbDevice failed: invalid format sr=%d ch=%d bits=%d subslot=%d",
             sampleRate, channels, bitsPerSample, subslotSize);
        return 0;
    }

    {
        std::lock_guard<std::mutex> lk(gRegistryMtx);
        // 释放所有旧上下文（当前只支持单 DAC，所以释放全部）
        for (auto* oldCtx : gLiveHandles) {
            if (!oldCtx) continue;
            LOGD("Releasing previous USB context %p", oldCtx);
            stopStreamingLocked(oldCtx);
            if (oldCtx->devHandle) {
                if (oldCtx->claimDoneByNative) {
                    libusb_set_interface_alt_setting(oldCtx->devHandle, oldCtx->interfaceNumber, 0);
                    libusb_release_interface(oldCtx->devHandle, oldCtx->interfaceNumber);
                }
                if (oldCtx->acInterfaceClaimed) {
                    libusb_release_interface(oldCtx->devHandle, 0);
                }
                libusb_close(oldCtx->devHandle);
            }
            if (oldCtx->libusbCtx) {
                libusb_exit(oldCtx->libusbCtx);
            }
            for (int i = 0; i < NUM_TRANSFERS; i++) {
                delete[] oldCtx->transferBuffers[i];
                oldCtx->transferBuffers[i] = nullptr;
            }
            delete[] oldCtx->feedbackBuffer;
            oldCtx->feedbackBuffer = nullptr;
            unregisterHandle(oldCtx);
            delete oldCtx;
        }
    }

    auto *ctx = new UsbAudioContext();
    ctx->javaFd = fd;
    ctx->claimedInJava = false;  // 新架构：native 统一管理
    ctx->claimDoneByNative = false;

    // 快照全局用户策略到 ctx 实例
    ctx->usbExclusiveActive = g_usbExclusiveActive.load(std::memory_order_acquire);
    ctx->bitPerfectEnabled = g_bitPerfectEnabled.load(std::memory_order_acquire);
    ctx->hardwareFeatureUnitRequested = g_hardwareFeatureUnitRequested.load(std::memory_order_acquire);
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

    // Java 输入源格式
    // 解码器对 24bit/32bit 统一输出 S32LE (4B/sample, 32bit)
    ctx->sampleRate = sampleRate;
    ctx->sourceSampleRate = sampleRate;
    ctx->sourceChannels = channels;
    ctx->sourceBitDepth = (bitsPerSample > 16) ? 32 : bitsPerSample;
    ctx->sourceBytesPerSample = (bitsPerSample > 16) ? 4 : 2;
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
    LOGI("nativeInitUsbDevice calc: frameSize=%d intervalsPerSec=8000 bytesPerServiceInterval=%d",
         frameSize, (sampleRate * frameSize + 7999) / 8000);

    // dup fd，native 使用自己的 fd 副本
    ctx->dupFd = dup(fd);
    if (ctx->dupFd < 0) {
        LOGE("dup(fd=%d) failed: errno=%d %s", fd, errno, strerror(errno));
        delete ctx;
        return 0;
    }
    LOGI("dup fd ok: javaFd=%d dupFd=%d", fd, ctx->dupFd);

    // Android 推荐：禁止 libusb 自己扫描 /dev/bus/usb
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
    libusb_set_option(ctx->libusbCtx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);

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

    // 获取设备描述符
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
    if (policy.forceDisableFeatureUnit) {
        ctx->hardwareFeatureUnitRequested = false;
        ctx->hardwareVolumeEnabled = false;
        ctx->hardwareVolumeSafe = false;
        LOGW("Feature Unit disabled by device policy: known unsafe device VID=%04X PID=%04X",
             devDesc.idVendor, devDesc.idProduct);
    }
    // 策略驱动特调：复制标志到 context
    ctx->policyForceNoControlIface = policy.forceNoControlIface;
    ctx->policyForceSoftwareVolume = policy.forceSoftwareVolume;
    ctx->policySkipClockConfig = policy.skipClockConfig;
    ctx->policyIgnoreClockControl = policy.ignoreClockControl;
    if (policy.forceSoftwareVolume) {
        // 强制软件音量：禁用硬件音量路径
        ctx->hardwareFeatureUnitRequested = false;
        ctx->hardwareVolumeEnabled = false;
        ctx->hardwareVolumeSafe = false;
        ctx->hardwareVolumeCapable = false;
        ctx->featureUnitAvailable = false;
        LOGI("Device policy: forceSoftwareVolume=1 → hardware volume disabled");
    }
    if (policy.skipClockConfig) {
        LOGI("Device policy: skipClockConfig=1 → will skip UAC2 SET_CUR clock configuration");
    }
    if (policy.ignoreClockControl) {
        LOGI("Device policy: ignoreClockControl=1 → will skip all clock control (SET_CUR/GET_CUR/GET_RANGE)");
    }
    if (policy.forceNoControlIface) {
        LOGI("Device policy: forceNoControlIface=1 → will skip AC interface claim entirely");
    }

    // 自动分离内核驱动
#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000102)
    libusb_set_auto_detach_kernel_driver(ctx->devHandle, 1);
#endif

    // 新架构：native 统一 claim + set_alt
    // 1. Claim AudioControl interface (iface=0) — failure is not necessarily fatal
    //    策略驱动：forceNoControlIface 时跳过 AC claim（不做 Feature Unit unmute/0dB）
    int acIface = 0;
    int asIface = ctx->interfaceNumber;
    if (policy.forceNoControlIface) {
        ctx->acInterfaceClaimed = false;
        LOGI("AC interface claim skipped by device policy (forceNoControlIface)");
    } else {
        int rAc = libusb_claim_interface(ctx->devHandle, acIface);
        if (rAc == LIBUSB_SUCCESS) {
            ctx->acInterfaceClaimed = true;
            LOGI("libusb_claim_interface(AC iface=%d) ok", acIface);
        } else {
            ctx->acInterfaceClaimed = false;
            LOGW("libusb_claim_interface(AC iface=%d) failed: %s, continue",
                 acIface, libusb_error_name(rAc));
        }
    }

    // 2. Claim AudioStreaming interface
    //    当 asIface=0 表示"自动选择"，跳过初始 claim，等描述符扫描后再 claim
    if (asIface != 0) {
        r = libusb_claim_interface(ctx->devHandle, asIface);
        if (r != LIBUSB_SUCCESS) {
            LOGE("libusb_claim_interface(AS iface=%d) failed: %s",
                 asIface, libusb_strerror(r));
            if (ctx->acInterfaceClaimed) {
                libusb_release_interface(ctx->devHandle, acIface);
            }
            libusb_close(ctx->devHandle);
            libusb_exit(ctx->libusbCtx);
            close(ctx->dupFd);
            delete ctx;
            return 0;
        }
        LOGI("libusb_claim_interface(AS iface=%d) ok", asIface);
        ctx->claimDoneByNative = true;
    } else {
        LOGI("asIface=0 (auto-select), skip initial AS claim, will claim after descriptor scan");
    }

    // 3. Force AS to alt0 (idle) before clock config
    //    当 asIface=0（自动选择）时跳过，等描述符扫描后再设置
    if (asIface != 0) {
        r = libusb_set_interface_alt_setting(ctx->devHandle, asIface, 0);
        if (r == LIBUSB_SUCCESS) {
            LOGI("set AS iface=%d alt=0 before clock config ok", asIface);
        } else {
            LOGW("set AS iface=%d alt=0 before clock config failed: %s, continue",
                 asIface, libusb_error_name(r));
        }
    } else {
        LOGI("asIface=0 (auto-select), skip alt=0 set before clock config");
    }
    usleep(10000);

    // 检测 USB 速度
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

    // 读取配置描述符，查找最佳 Audio Stream altsetting
    uint8_t cfgHeader[9];
    r = libusb_get_descriptor(ctx->devHandle, LIBUSB_DT_CONFIG, 0, cfgHeader, sizeof(cfgHeader));
    if (r < 0) {
        LOGE("libusb_get_descriptor(header) failed: %s", libusb_strerror(r));
        if (ctx->interfaceNumber != 0) libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
        if (ctx->acInterfaceClaimed) libusb_release_interface(ctx->devHandle, 0);
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
        if (ctx->acInterfaceClaimed) libusb_release_interface(ctx->devHandle, 0);
        libusb_close(ctx->devHandle);
        libusb_exit(ctx->libusbCtx);
        close(ctx->dupFd);
        delete ctx;
        return 0;
    }
    totalLen = r;

    // 先解析 AC Topology，后面配置采样率和 Feature Unit 都要用
    AcTopology acTopo = parseACTopology(configDesc.data(), totalLen);

    // 用候选评分系统查找最佳 Audio Stream altsetting
    AudioStreamCandidate selected;
    bool found = parseAudioInterfaceFromConfig(
            configDesc.data(), totalLen,
            sampleRate, channels, bitsPerSample,
            iface, alt,
            ctx->bitPerfectEnabled,
            selected
    );
    if (!found) {
        LOGE("No compatible USB Audio stream found");
        // 只释放 AS 接口（iface != 0），iface=0 是 Audio Control，下面单独释放
        if (ctx->interfaceNumber != 0) {
            libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
        }
        if (ctx->acInterfaceClaimed) libusb_release_interface(ctx->devHandle, 0);
        libusb_close(ctx->devHandle);
        libusb_exit(ctx->libusbCtx);
        close(ctx->dupFd);
        delete ctx;
        return 0;
    }

    // UAC2: query real supported sample rates from Clock Source via GET_RANGE
    // and backfill into selected so that streamSupportsRate uses real data.
    // 策略驱动：skipClockConfig 或 ignoreClockControl 时跳过 GET_RANGE
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
                }
            }
        }
        // Re-evaluate rate support with real device data
        bool rateOk = streamSupportsRate(selected, sampleRate);
        if (ctx->bitPerfectEnabled && !rateOk) {
            LOGE("Bit-perfect rejected: selected alt does not support target rate %dHz via GET_RANGE", sampleRate);
            if (ctx->interfaceNumber != 0) libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
            if (ctx->acInterfaceClaimed) libusb_release_interface(ctx->devHandle, 0);
            libusb_close(ctx->devHandle);
            libusb_exit(ctx->libusbCtx);
            close(ctx->dupFd);
            delete ctx;
            return 0;
        }
    }

    // 如果 parseAudioInterfaceFromConfig 选择了不同的 iface/alt/ep，需要重新 set_alt
    if (selected.iface != ctx->interfaceNumber || selected.alt != ctx->altSetting ||
        selected.epAddress != ctx->epAddress) {
        LOGI("Descriptor suggested different settings: iface=%d->%d alt=%d->%d ep=0x%02X->0x%02X",
             ctx->interfaceNumber, selected.iface, ctx->altSetting, selected.alt,
             ctx->epAddress, selected.epAddress);

        // 如果 iface 不同，需要先释放再重新 claim
        if (selected.iface != ctx->interfaceNumber) {
            // 当 ctx->interfaceNumber != 0 时才释放（0 是 Audio Control，不能释放）
            if (ctx->interfaceNumber != 0) {
                libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
            }
            ctx->interfaceNumber = selected.iface;
            r = libusb_claim_interface(ctx->devHandle, ctx->interfaceNumber);
            if (r != LIBUSB_SUCCESS) {
                LOGE("libusb_claim_interface(iface=%d) failed on re-claim: %s", ctx->interfaceNumber, libusb_strerror(r));
                if (ctx->acInterfaceClaimed) libusb_release_interface(ctx->devHandle, 0);
                libusb_close(ctx->devHandle);
                libusb_exit(ctx->libusbCtx);
                close(ctx->dupFd);
                delete ctx;
                return 0;
            }
            ctx->claimDoneByNative = true;
            LOGI("Re-claimed AS iface=%d ok", ctx->interfaceNumber);
        }

        ctx->altSetting = selected.alt;
        ctx->epAddress = selected.epAddress;
        r = libusb_set_interface_alt_setting(ctx->devHandle, ctx->interfaceNumber, ctx->altSetting);
        if (r != LIBUSB_SUCCESS) {
            LOGE("libusb_set_interface_alt_setting(iface=%d alt=%d) failed on re-set: %s",
                 ctx->interfaceNumber, ctx->altSetting, libusb_strerror(r));
            libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
            if (ctx->acInterfaceClaimed) libusb_release_interface(ctx->devHandle, 0);
            libusb_close(ctx->devHandle);
            libusb_exit(ctx->libusbCtx);
            close(ctx->dupFd);
            delete ctx;
            return 0;
        }
    }

    ctx->protocol = selected.protocol;
    ctx->maxPacketSize = selected.maxPacketSize;
    ctx->endpointInterval = selected.bInterval;
    ctx->feedbackEpAddress = selected.feedbackEpAddress;
    ctx->uac1EpHasSamplingFreqControl = selected.uac1EpHasSamplingFreqControl;
    ctx->terminalLink = selected.terminalLink;
    ctx->deviceChannels = selected.channels;
    ctx->deviceBitDepth = selected.bitResolution;
    ctx->deviceSubslotSize = selected.subslotSize;
    ctx->deviceBytesPerSample = selected.subslotSize;
    ctx->deviceBytesPerFrame = selected.channels * selected.subslotSize;

    // 8. 配置采样率（AC Topology 已在前面解析）
    //    策略驱动：
    //    - ignoreClockControl: 完全忽略时钟控制（不发 SET_CUR/GET_CUR/GET_RANGE），假定设备运行在请求采样率
    //    - skipClockConfig: 跳过 SET_CUR，但尝试 GET_CUR 检测实际采样率
    {
        LOGI("=== AC Topology Dump ===");
        LOGI("=== AC Topology End (%zu entities) ===", acTopo.entities.size());
        LOGI("AS playback: protocol=UAC%d asInterface=%d alt=%d terminalLink=0x%02X ep=0x%02X",
             ctx->protocol, ctx->interfaceNumber, ctx->altSetting, ctx->terminalLink, ctx->epAddress);
        if (ctx->policyIgnoreClockControl) {
            // 完全忽略时钟控制：不发任何时钟相关 USB 请求
            // 直接假定设备运行在请求的采样率（FiiO 的 SET_CUR/GET_CUR/GET_RANGE 均返回 EIO）
            LOGI("Clock control completely ignored by device policy (ignoreClockControl=1), "
                 "assuming device runs at requested rate %d Hz", sampleRate);
        } else if (ctx->policySkipClockConfig) {
            // 跳过 SET_CUR，但需要检测设备实际采样率以启用重采样
            // 先尝试 GET_CUR 读取设备当前时钟
            uint32_t deviceRate = 0;
            if (ctx->protocol == USB_AUDIO_UAC2) {
                uint8_t acIface = 0;
                bool clockSupportsRead = false;
                bool isClockSelector = false;
                uint8_t resolvedClockSourceId = 0;
                uint8_t clockId = findClockForStreamTerminal(
                        acTopo.entities, ctx->terminalLink,
                        acIface, clockSupportsRead, isClockSelector, resolvedClockSourceId
                );
                if (clockId != 0 && clockSupportsRead) {
                    uint8_t finalClockId = clockId;
                    if (isClockSelector) {
                        finalClockId = resolveClockSelector(
                                ctx->devHandle, acTopo.entities, clockId, acIface, clockSupportsRead);
                    }
                    if (finalClockId != 0) {
                        int getRet = uac2GetCurSampleRate(
                                ctx->devHandle, acIface, finalClockId, &deviceRate);
                        if (getRet == LIBUSB_SUCCESS && deviceRate > 0) {
                            LOGI("GET_CUR succeeded: device clock = %u Hz", deviceRate);
                        } else {
                            LOGW("GET_CUR failed: %s, trying known device table",
                                 libusb_error_name(getRet));
                            deviceRate = 0;
                        }
                    }
                }
            }
            // GET_CUR 失败时，查表获取已知设备时钟
            if (deviceRate == 0) {
                deviceRate = getKnownDeviceClockRate(ctx->vendorId, ctx->productId);
                if (deviceRate > 0) {
                    LOGI("Using known device clock rate: %u Hz (VID=%04X PID=%04X)",
                         deviceRate, ctx->vendorId, ctx->productId);
                } else {
                    LOGW("Unknown device clock rate, using requested rate %d Hz (may cause issues)",
                         sampleRate);
                    deviceRate = (uint32_t)sampleRate;
                }
            }
            // 更新采样率：如果设备实际运行在不同速率，需要启用重采样
            if (deviceRate != (uint32_t)sampleRate) {
                LOGI("Device clock mismatch: requested=%d actual=%u, enabling resampling",
                     sampleRate, deviceRate);
                ctx->sampleRate = (int)deviceRate;
                // 重采样将在后续 initSwrContext 中自动启用
            } else {
                LOGI("Device clock matches requested rate: %u Hz", deviceRate);
            }
            LOGI("Clock configuration skipped by device policy (skipClockConfig=1), "
                 "device rate=%u Hz", deviceRate);
        } else {
            int cfgRet = configureSampleRateDynamic(
                    ctx, acTopo.entities, (uint32_t)sampleRate
            );
            if (cfgRet < 0) {
                LOGW("configureSampleRateDynamic returned: %d, continue anyway", cfgRet);
            }
            usleep(50000);
        }
    }

    // 9. Feature Unit 解析（仅解析，不做 unmute，因为 playbackMode 还未确定）
    LOGI("AS playback: terminalLink=0x%02X", ctx->terminalLink);
    if (g_usbNoControlInterface.load(std::memory_order_relaxed)) {
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
    if (r < 0) {
        LOGW("libusb_set_interface_alt_setting(%d,%d) failed: %s",
             ctx->interfaceNumber, ctx->altSetting, libusb_error_name(r));
    } else {
        LOGI("Set streaming alt: iface=%d alt=%d", ctx->interfaceNumber, ctx->altSetting);
    }

    // USB 速度决定服务间隔
    if (ctx->isFullSpeed) {
        ctx->serviceIntervalsPerSecond = 1000;
    } else {
        ctx->serviceIntervalsPerSecond = 8000;
    }

    // 端点 bInterval
    if (ctx->endpointInterval <= 0) {
        ctx->endpointInterval = ctx->isFullSpeed ? 1 : 4;
    }

    // 计算 ISO 传输参数
    ctx->bytesPerFrame = ctx->channels * ctx->bytesPerSample;
    ctx->bytes_per_second = (uint64_t)ctx->sampleRate * ctx->bytesPerFrame;

    // Initialize ISO Pacer
    ctx->isoPacer.reset(
            (uint32_t)ctx->sampleRate,
            (uint32_t)ctx->serviceIntervalsPerSecond,
            (uint32_t)ctx->bytesPerFrame,
            ctx->maxPacketSize
    );
    ctx->nominalSampleRate = (uint32_t)ctx->sampleRate;
    ctx->bytesPerPacket = (int)(ctx->bytes_per_second / ctx->isoPacer.intervalsPerSec);
    ctx->bytesPerServiceInterval = ctx->bytesPerPacket;

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

    // 初始化重采样器（源采样率 != 设备采样率 或 格式不同时启用）
    int swrRet = initSwrContext(ctx);
    if (swrRet < 0) {
        LOGW("initSwrContext failed: %d, resampling disabled", swrRet);
    }
    // 当重采样启用 或 有PCM格式适配时，ring buffer 存储设备格式数据，
    // 需更新 bytesPerFrame 并重建 IsoPacer
    if (ctx->needsResample || ctx->pcmAdapter != PCM_ADAPTER_NONE) {
        ctx->bytesPerFrame = ctx->deviceBytesPerFrame;
        ctx->bytes_per_second = (uint64_t)ctx->sampleRate * ctx->bytesPerFrame;
        ctx->bytesPerPacket = (int)(ctx->bytes_per_second / ctx->isoPacer.intervalsPerSec);
        ctx->bytesPerServiceInterval = ctx->bytesPerPacket;
        // 用正确的设备帧大小重建 IsoPacer
        ctx->isoPacer.reset(
                (uint32_t)ctx->sampleRate,
                (uint32_t)ctx->serviceIntervalsPerSecond,
                (uint32_t)ctx->bytesPerFrame,
                ctx->maxPacketSize
        );
        ctx->nominalSampleRate = (uint32_t)ctx->sampleRate;
        LOGI("Ring buffer uses device format: bytesPerFrame=%d (resample=%d adapter=%d)",
             ctx->bytesPerFrame, ctx->needsResample ? 1 : 0, (int)ctx->pcmAdapter);
    }

    // transfer 大小
    int maxPktSize = ctx->maxPacketSize;
    if (maxPktSize <= 0) {
        maxPktSize = ctx->bytesPerPacket;
    }
    ctx->numIsoPackets = ISO_PACKETS_PER_XFER;
    ctx->transferSize = maxPktSize * ctx->numIsoPackets;

    // 分配传输缓冲区
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        int bufSize = ctx->transferSize;
        ctx->transferBuffers[i] = new uint8_t[bufSize];
        memset(ctx->transferBuffers[i], 0, bufSize);
    }

    // 环形缓冲区（4 秒）
    size_t ringSize = (size_t)ctx->sampleRate * ctx->bytesPerFrame * 4;
    ctx->pcmRingBuffer.resize(ringSize, 0);
    ctx->pcmWritePos.store(0);
    ctx->pcmReadPos.store(0);

    // Feature Unit / 硬件音量验证（在 Feature Unit 解析之后，此时 featureUnitPresent 已设置）
    ctx->hardwareVolumeSafe = false;
    ctx->hardwareVolumeEnabled = false;
    ctx->hardwareFeatureUnitEnabled = false;

    if (ctx->hardwareFeatureUnitRequested && ctx->featureUnitPresent) {
        int volRet = validateHardwareVolume(ctx);
        ctx->hardwareVolumeCapable = ctx->hasMasterVolume || (ctx->hasLeftVolume && ctx->hasRightVolume);
        ctx->featureUnitAvailable = ctx->featureUnitPresent && ctx->hasMasterVolume;
        ctx->featureUnitHasMasterVolume = ctx->hasMasterVolume;
        if (volRet == 0) {
            ctx->hardwareVolumeSafe = true;
            ctx->hardwareVolumeEnabled = true;
            ctx->hardwareFeatureUnitEnabled = true;
            LOGI("Hardware volume validated and enabled");
        } else {
            LOGW("Hardware volume validation failed: %d, disabling", volRet);
        }
    } else {
        ctx->hardwareVolumeCapable = false;
        ctx->featureUnitAvailable = false;
        ctx->featureUnitHasMasterVolume = false;
    }

    // 计算播放模式（现在 hardwareVolumeEnabled 已正确设置）
    if (!ctx->usbExclusiveActive) {
        ctx->playbackMode = UsbPlaybackMode::SafeSoftwareVolume;
    } else if (!ctx->bitPerfectEnabled) {
        ctx->playbackMode = UsbPlaybackMode::ExclusiveSoftwareVolume;
    } else if (ctx->hardwareVolumeEnabled) {
        ctx->playbackMode = UsbPlaybackMode::ExclusiveBitPerfectHwVol;
    } else {
        ctx->playbackMode = UsbPlaybackMode::ExclusiveBitPerfectFixed;
    }
    LOGI("Playback mode: %d (0=SafeSw 1=ExcSw 2=ExcBPHwVol 3=ExcBPFixed)", (int)ctx->playbackMode);

    // Feature Unit unmute/0dB（在 playbackMode 确定之后）
    if (ctx->featureUnitPresent && !g_usbNoControlInterface.load(std::memory_order_relaxed)) {
        if (ctx->playbackMode == UsbPlaybackMode::SafeSoftwareVolume) {
            LOGI("SafeSoftwareVolume mode: not touching Feature Unit");
        } else {
            // ExclusiveSoftwareVolume / ExclusiveBitPerfectHwVol / ExclusiveBitPerfectFixed
            // 都需要解除静音并设置 0dB，确保 Feature Unit 处于透明状态
            int muteRet = setFeatureUnitMute(
                    ctx->devHandle, ctx->playbackFeatureAcInterface,
                    ctx->playbackFeatureUnitId, 0); // 0 = unmute
            if (muteRet < 0) {
                LOGW("Playback Feature Unit unmute failed");
            }
            // 设置 0dB（unity gain）：UAC2 volume 单位是 1/256 dB，0 = 0dB
            int volRet = setFeatureUnitVolume(
                    ctx->devHandle, ctx->playbackFeatureAcInterface,
                    ctx->playbackFeatureUnitId, 0); // 0 = 0dB
            if (volRet < 0) {
                LOGW("Playback Feature Unit set 0dB failed (non-fatal)");
            } else {
                LOGI("Playback Feature Unit: unmuted + set to 0dB");
            }
        }
    }

    // 预缓冲阈值：有 feedback 时 200ms，无 feedback 时 500ms（为 PI 控制器提供缓冲余量）
    int prebufferMs = (ctx->feedbackEpAddress != 0) ? 200 : 500;
    ctx->prebufferBytes = ctx->bytes_per_second * prebufferMs / 1000;
    LOGI("nativeInitUsbDevice: prebufferBytes=%zu (%dms) feedbackEp=0x%02X",
         ctx->prebufferBytes, prebufferMs, ctx->feedbackEpAddress);

    // 反馈端点缓冲区
    ctx->feedbackBuffer = new uint8_t[4];
    memset(ctx->feedbackBuffer, 0, 4);

    ctx->initialized.store(true, std::memory_order_release);
    ctx->streaming.store(false, std::memory_order_release);
    ctx->stopping.store(false, std::memory_order_release);
    ctx->acceptingWrites.store(true, std::memory_order_release); // pre-fill 需要写入
    g_requiresReinit.store(false, std::memory_order_release);

    registerHandle(ctx);
    ctx->softwareVolume.store(gSoftwareVolume.load(std::memory_order_relaxed), std::memory_order_relaxed);

    LOGI("USB native ready: sr=%d ch=%d bits=%d subslot=%d frameSize=%d iface=%d alt=%d outEp=0x%02X fbEp=0x%02X",
         ctx->sampleRate, ctx->channels, ctx->bitDepth, ctx->deviceSubslotSize,
         ctx->bytesPerFrame, ctx->interfaceNumber, ctx->altSetting,
         ctx->epAddress, ctx->feedbackEpAddress);

    return reinterpret_cast<jlong>(ctx);
}

// ==========================
// JNI: nativeStart(handle)（新架构）
// ==========================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeStart__J(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env;
    (void) thiz;
    LOGI("nativeStart(handle) called: handle=0x%llx", (unsigned long long)handle);
    if (handle == 0) return JNI_FALSE;
    auto *ctx = reinterpret_cast<UsbAudioContext*>(handle);

    if (!isLiveHandle(ctx)) {
        LOGE("nativeStart(handle) failed: dead handle %p", ctx);
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lk(ctx->handleMutex);

    if (ctx->closing.load(std::memory_order_acquire)) {
        LOGE("nativeStart(handle) rejected: handle is closing %p", ctx);
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

    ctx->stopping.store(false, std::memory_order_release);
    ctx->pendingTransfers.store(0);
    ctx->pendingFeedbackTransfers.store(0);
    ctx->isoPacer.frameAccumulator = 0.0;
    ctx->isoPacer.sampleRate = (double)ctx->nominalSampleRate;
    ctx->transportLost.store(false, std::memory_order_relaxed);
    ctx->fatalError.store(0, std::memory_order_relaxed);
    ctx->starved = false;
    ctx->starvedRecoveryBytes = ctx->sampleRate * ctx->bytesPerFrame * 3 / 10;

    // 重置淡入状态
    ctx->fadeSamplesRemaining = 0;
    ctx->fadeTotalSamples = 0;
    ctx->startupSilenceDone = false;

    // 重置 PI 自适应速率控制器（feedback 失效后会在 feedbackCallback 中重新激活）
    ctx->adaptiveRate.integralError = 0.0;
    ctx->adaptiveRate.correction = 0.0;
    ctx->adaptiveRate.stableCount = 0;
    {
        std::lock_guard<std::mutex> lock(ctx->ringMutex);
        ctx->adaptiveRate.prevBufUsed = ringAvailable(ctx);
    }

    // ① 确保环形缓冲区有足够的"静音预填"
    {
        std::lock_guard<std::mutex> lock(ctx->ringMutex);
        size_t bufSize = ctx->pcmRingBuffer.size();
        size_t used    = ringAvailable(ctx);
        size_t need    = ctx->bytes_per_second / 10; // 100 ms 静音
        if (used < need) {
            size_t fill = std::min(need - used, (bufSize - 1) - used);
            size_t w = ctx->pcmWritePos.load(std::memory_order_relaxed);
            // 静音填充
            size_t first = std::min(fill, bufSize - w);
            memset(ctx->pcmRingBuffer.data() + w, 0, first);
            if (fill > first) memset(ctx->pcmRingBuffer.data(), 0, fill - first);
            ctx->pcmWritePos.store((w + fill) % bufSize, std::memory_order_relaxed);
            LOGI("Start: pre-buffered %zu silent bytes (target %zu)", fill, need);
        }
    }

    // ② 清除端点 HALT、给 DAC 反应时间
    libusb_clear_halt(ctx->devHandle, ctx->epAddress);
    usleep(50000);

    for (int i = 0; i < NUM_TRANSFERS; i++) {
        ctx->transfers[i] = nullptr;
    }

    // ② 安全初始音量：所有使用软件音量的模式都需要设置
    if (ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectHwVol) {
        float safeVol = std::min(ctx->softwareVolume.load(std::memory_order_relaxed),
                                 0.25f); // 约 -12 dB，防止突增满音量
        int r = setUsbHardwareVolumeSafe(ctx, safeVol);
        if (r != 0) {
            LOGW("Start: hardware volume write failed (r=%d) -> fallback to Fixed", r);
            ctx->playbackMode = UsbPlaybackMode::ExclusiveBitPerfectFixed;
        } else {
            LOGI("Start: hardware volume synced to %.3f (safe)", safeVol);
        }
    } else if (ctx->playbackMode == UsbPlaybackMode::ExclusiveSoftwareVolume ||
               ctx->playbackMode == UsbPlaybackMode::ExclusiveBitPerfectFixed) {
        // 软件音量模式：将初始音量钳制到安全值，防止满音量冲击
        float curVol = ctx->softwareVolume.load(std::memory_order_relaxed);
        float safeVol = std::min(curVol, 0.06f); // 约 -24dB，足够安全
        ctx->softwareVolume.store(safeVol, std::memory_order_relaxed);
        gSoftwareVolume.store(safeVol, std::memory_order_release);
        LOGI("Start: software volume clamped to %.3f (was %.3f) for safe startup", safeVol, curVol);
    }

    ctx->streaming.store(true, std::memory_order_release);
    ctx->acceptingWrites.store(true, std::memory_order_release);
    ctx->eventThreadRunning.store(true, std::memory_order_release);
    ctx->eventThread = std::thread(eventLoop, ctx);

    int submitted = 0;
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        ctx->transfers[i] = libusb_alloc_transfer(ctx->numIsoPackets);
        if (!ctx->transfers[i]) {
            LOGE("libusb_alloc_transfer(%d) failed", ctx->numIsoPackets);
            continue;
        }
        ctx->isoUserData[i].ctx = ctx;
        ctx->isoUserData[i].index = i;

        int bufCapacity = ctx->transferSize;
        libusb_fill_iso_transfer(ctx->transfers[i], ctx->devHandle, ctx->epAddress,
                                 ctx->transferBuffers[i], bufCapacity,
                                 ctx->numIsoPackets,
                                 isoCallback, &ctx->isoUserData[i], 0);

        for (int p = 0; p < ctx->numIsoPackets; p++) {
            ctx->transfers[i]->iso_packet_desc[p].length = ctx->bytesPerPacket;
        }

        fillIsoTransfer(ctx, ctx->transfers[i], i);

        int ret = libusb_submit_transfer(ctx->transfers[i]);
        if (ret < 0) {
            LOGE("libusb_submit_transfer(%d) failed: %s", i, libusb_strerror(ret));
            if (ret == LIBUSB_ERROR_IO || ret == LIBUSB_ERROR_NO_DEVICE ||
                ret == LIBUSB_ERROR_NOT_FOUND || ret == LIBUSB_ERROR_OTHER) {
                markUsbTransportLost(ctx, "initial submit", i, ret);
                break;
            }
            continue;
        }
        submitted++;
        ctx->pendingTransfers.fetch_add(1, std::memory_order_relaxed);
    }

    if (submitted == 0) {
        LOGE("nativeStart(handle) failed: no ISO transfer submitted");
        ctx->streaming.store(false, std::memory_order_release);
        ctx->eventThreadRunning.store(false, std::memory_order_release);
        if (ctx->eventThread.joinable()) ctx->eventThread.join();
        return JNI_FALSE;
    }

    // 提交 feedback transfer
    if (ctx->feedbackEpAddress != 0 && ctx->feedbackBuffer) {
        // 必须分配 1 个 iso packet descriptor
        ctx->feedbackTransfer = libusb_alloc_transfer(1);
        if (ctx->feedbackTransfer) {
            memset(ctx->feedbackBuffer, 0, 4);
            libusb_fill_iso_transfer(
                    ctx->feedbackTransfer,
                    ctx->devHandle,
                    ctx->feedbackEpAddress,
                    ctx->feedbackBuffer,
                    4,
                    1,
                    feedbackCallback,
                    ctx,
                    0
            );
            libusb_set_iso_packet_lengths(ctx->feedbackTransfer, 4);
            int fbRet = libusb_submit_transfer(ctx->feedbackTransfer);
            if (fbRet < 0) {
                LOGW("Feedback transfer submit failed: %s", libusb_strerror(fbRet));
                libusb_free_transfer(ctx->feedbackTransfer);
                ctx->feedbackTransfer = nullptr;
            } else {
                ctx->pendingFeedbackTransfers.fetch_add(1, std::memory_order_relaxed);
                LOGI("Feedback transfer submitted: ep=0x%02X", ctx->feedbackEpAddress);
            }
        } else {
            LOGW("libusb_alloc_transfer(1) for feedback failed");
        }
    }

    LOGI("nativeStart(handle) ok: %d/%d ISO transfers submitted", submitted, NUM_TRANSFERS);
    return JNI_TRUE;
}

// ==========================
// JNI: nativeStop(handle)（新架构）
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeStop__J(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env;
    (void) thiz;
    LOGI("nativeStop(handle) called: handle=0x%llx", (unsigned long long)handle);
    if (handle == 0) return;
    auto *ctx = reinterpret_cast<UsbAudioContext*>(handle);

    if (!isLiveHandle(ctx)) {
        LOGW("nativeStop ignored: dead handle %p", ctx);
        return;
    }

    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (ctx->closing.load(std::memory_order_acquire)) {
        LOGW("nativeStop ignored: handle is closing %p", ctx);
        return;
    }
    stopStreamingLocked(ctx);
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
    LOGI("nativePause(handle) called: handle=0x%llx", (unsigned long long)handle);
    if (handle == 0) return;
    auto *ctx = reinterpret_cast<UsbAudioContext*>(handle);

    if (!isLiveHandle(ctx)) {
        LOGW("nativePause ignored: dead handle %p", ctx);
        return;
    }

    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (ctx->closing.load(std::memory_order_acquire)) {
        LOGW("nativePause ignored: handle is closing %p", ctx);
        return;
    }
    stopStreamingLocked(ctx);
    LOGI("nativePause: USB stopped, buffer preserved");
}

// ==========================
// JNI: nativeStopAndFlush(handle) - stop USB and clear buffer
// ==========================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeStopAndFlush(
        JNIEnv *env, jobject thiz, jlong handle
) {
    (void) env;
    (void) thiz;
    LOGI("nativeStopAndFlush(handle) called: handle=0x%llx", (unsigned long long)handle);
    if (handle == 0) return;
    auto *ctx = reinterpret_cast<UsbAudioContext*>(handle);

    if (!isLiveHandle(ctx)) {
        LOGW("nativeStopAndFlush ignored: dead handle %p", ctx);
        return;
    }

    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    if (ctx->closing.load(std::memory_order_acquire)) {
        LOGW("nativeStopAndFlush ignored: handle is closing %p", ctx);
        return;
    }
    stopStreamingLocked(ctx);

    // Clear ring buffer
    {
        std::lock_guard<std::mutex> lock(ctx->ringMutex);
        ctx->pcmWritePos.store(0, std::memory_order_relaxed);
        ctx->pcmReadPos.store(0, std::memory_order_relaxed);
        memset(ctx->pcmRingBuffer.data(), 0, ctx->pcmRingBuffer.size());
        ctx->starved = false;
        ctx->isoPacer.frameAccumulator = 0.0;
    }
    ctx->statsUnderrun.store(0, std::memory_order_relaxed);
    LOGI("nativeStopAndFlush: USB stopped, buffer cleared");
}

// ==========================
// JNI: nativeClose(handle)（新架构）
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
    auto *ctx = reinterpret_cast<UsbAudioContext*>(handle);

    // 防止并发 close：先从 live set 移除，避免 double-close
    if (!unregisterHandle(ctx)) {
        LOGW("nativeClose ignored: not live/double close handle=%p", ctx);
        return;
    }
    // 现在 ctx 仍然由本次 close 拥有，可以加锁等待 start/stop 结束
    std::lock_guard<std::mutex> lk(ctx->handleMutex);
    ctx->closing.store(true, std::memory_order_release);
    ctx->stopping.store(true, std::memory_order_release);
    LOGI("nativeClose: handle=%p", ctx);

    // 2. stop transfers and wait callbacks
    stopUsbAudioInternal(ctx);

    // 3. stop event loop
    ctx->eventThreadRunning.store(false, std::memory_order_release);
    if (ctx->eventThread.joinable()) {
        ctx->eventThread.join();
        LOGI("event thread joined");
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
            int r = libusb_set_interface_alt_setting(ctx->devHandle, ctx->interfaceNumber, 0);
            LOGI("set alt0 result: %s", libusb_error_name(r));
            r = libusb_release_interface(ctx->devHandle, ctx->interfaceNumber);
            LOGI("release AS iface result: %s", libusb_error_name(r));
            ctx->interfaceNumber = -1;
        }
        if (ctx->acInterfaceClaimed) {
            int r = libusb_release_interface(ctx->devHandle, 0);
            LOGI("release AC iface=0 result: %s", libusb_error_name(r));
            ctx->acInterfaceClaimed = false;
        }
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

    // 7. 释放缓冲区
    for (int i = 0; i < NUM_TRANSFERS; i++) {
        delete[] ctx->transferBuffers[i];
        ctx->transferBuffers[i] = nullptr;
    }
    delete[] ctx->feedbackBuffer;
    ctx->feedbackBuffer = nullptr;

    // 8. 释放重采样上下文
    closeSwrContext(ctx);

    LOGI("nativeClose done: handle=%p", ctx);
    delete ctx;
}

// ====================== Unified Policy API ======================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetPolicy(
        JNIEnv* env, jobject thiz,
        jboolean exclusive,
        jboolean bitPerfect,
        jboolean hwVol) {
    bool ex = exclusive == JNI_TRUE;
    bool bp = bitPerfect == JNI_TRUE && ex;        // bit‑perfect 必须 exclusive
    bool hv = hwVol == JNI_TRUE && bp && ex;      // 硬件音量必须在 bit‑perfect+exclusive 环境
    bool oldEx = g_usbExclusiveActive.exchange(ex, std::memory_order_acq_rel);
    bool oldBp = g_bitPerfectEnabled.exchange(bp, std::memory_order_acq_rel);
    bool oldHv = g_hardwareFeatureUnitRequested.exchange(hv, std::memory_order_acq_rel);
    LOGI("nativeSetPolicy: exclusive=%d bitPerfect=%d hwVol=%d",
         ex ? 1 : 0, bp ? 1 : 0, hv ? 1 : 0);
    if (oldEx != ex || oldBp != bp || oldHv != hv) {
        // 任意变更都要求重新 init
        g_requiresReinit.store(true, std::memory_order_release);
        LOGI("Policy changed -> requiresReinit");
    }
}

// ====================== USB DAC 高级设置（参考 Neutron Player） ======================
extern "C"
JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_usb_UsbAudioEngine_nativeSetUsbDacSettings(
        JNIEnv* env, jobject thiz,
        jboolean noControlIface,
        jboolean forceUac1,
        jboolean linearVolume,
        jboolean replaceVolume,
        jboolean force1ms) {
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
