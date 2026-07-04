#include "raw_audio_safety.h"

#include <algorithm>
#include <cmath>
#include <sstream>

namespace rawsmusic::usb {

RawAudioSafetyPolicy defaultRawAudioSafetyPolicy() {
    return RawAudioSafetyPolicy{};
}

int rawFrameAlignDown(int bytes, int frameBytes) {
    if (bytes <= 0 || frameBytes <= 0) return 0;
    return (bytes / frameBytes) * frameBytes;
}

int rawFrameAlignUp(int bytes, int frameBytes) {
    if (bytes <= 0 || frameBytes <= 0) return 0;
    return ((bytes + frameBytes - 1) / frameBytes) * frameBytes;
}

bool rawIsFrameAligned(int bytes, int frameBytes) {
    return frameBytes > 0 && bytes >= 0 && (bytes % frameBytes) == 0;
}

RawFrameAlignmentResult rawValidateFrameAlignedBytes(int bytes, int frameBytes, const char* source) {
    RawFrameAlignmentResult result{};
    result.frameBytes = frameBytes;
    result.inputBytes = bytes;
    if (frameBytes <= 0) {
        result.reason = "invalid-frame-bytes";
        return result;
    }
    if (bytes < 0) {
        result.reason = "negative-input-bytes";
        return result;
    }
    result.valid = true;
    result.alignedBytes = rawFrameAlignDown(bytes, frameBytes);
    result.droppedTailBytes = bytes - result.alignedBytes;
    result.aligned = result.droppedTailBytes == 0;
    if (!result.aligned) {
        std::ostringstream os;
        os << (source ? source : "buffer") << "-not-frame-aligned:bytes=" << bytes
           << ";frame=" << frameBytes << ";dropTail=" << result.droppedTailBytes;
        result.reason = os.str();
    } else {
        result.reason = "ok";
    }
    return result;
}

RawUac20QueueSizing rawComputeUac20QueueSizing(
        int bytesPerSecond,
        int transferBytes,
        int frameBytes,
        bool explicitFeedback,
        const RawAudioSafetyPolicy& policy) {
    RawUac20QueueSizing sizing{};
    sizing.packetsPerTransfer = std::max(1, policy.isoPacketsPerTransfer);
    sizing.explicitFeedback = explicitFeedback;
    sizing.transferBytes = rawFrameAlignDown(std::max(0, transferBytes), std::max(1, frameBytes));
    if (sizing.transferBytes <= 0) {
        sizing.transferBytes = std::max(1, frameBytes);
    }

    const int queueMs = explicitFeedback ? policy.queueDepthMsFeedback : policy.queueDepthMsNoFeedback;
    sizing.queueMs = std::max(1, queueMs);
    const int64_t targetBytes64 = (static_cast<int64_t>(std::max(1, bytesPerSecond)) * sizing.queueMs + 999) / 1000;
    int transfers = static_cast<int>((targetBytes64 + sizing.transferBytes - 1) / sizing.transferBytes);
    transfers = std::clamp(transfers, std::max(1, policy.minTransferPool), std::max(1, policy.maxTransfers));
    sizing.transferCount = transfers;
    sizing.queueBytes = rawFrameAlignUp(transfers * sizing.transferBytes, std::max(1, frameBytes));
    sizing.prebufferBytes = sizing.queueBytes;

    std::ostringstream os;
    os << "packetsPerTransfer=" << sizing.packetsPerTransfer
       << " transfers=" << sizing.transferCount
       << " transferBytes=" << sizing.transferBytes
       << " queueBytes=" << sizing.queueBytes
       << " queueMs=" << sizing.queueMs
       << " mode=" << (explicitFeedback ? "feedback" : "no-feedback");
    sizing.summary = os.str();
    return sizing;
}

int rawStartupPrebufferBytes(
        int bytesPerSecond,
        int transferBytes,
        int frameBytes,
        bool explicitFeedback,
        const RawAudioSafetyPolicy& policy) {
    const auto sizing = rawComputeUac20QueueSizing(bytesPerSecond, transferBytes, frameBytes, explicitFeedback, policy);
    return std::max(std::max(1, frameBytes), sizing.prebufferBytes);
}

bool rawShouldRestoreHardwareVolumeAfterData(
        int64_t nowMs,
        int64_t firstDataMs,
        int64_t startMs,
        bool hasIsoCompletion,
        const RawAudioSafetyPolicy& policy) {
    if (nowMs <= 0 || startMs <= 0) return false;
    if (hasIsoCompletion && firstDataMs > 0 && nowMs - firstDataMs >= policy.hardwareVolumeRestoreAfterDataMs) {
        return true;
    }
    return nowMs - startMs >= policy.hardwareVolumeRestoreMaxDelayMs;
}

std::string describeRawAudioSafetyPolicy(const RawAudioSafetyPolicy& policy) {
    std::ostringstream os;
    os << "isoPacketsPerTransfer=" << policy.isoPacketsPerTransfer
       << " maxTransfers=" << policy.maxTransfers
       << " initialTransfers=" << policy.initialTransfers
       << " startupFadeMs=" << policy.startupFadeMs
       << " sessionDefaultFadeMs=" << policy.sessionDefaultFadeMs
       << " startupGuardMs=" << policy.startupGuardMs
       << " hwRestoreAfterDataMs=" << policy.hardwareVolumeRestoreAfterDataMs
       << " hwRestoreMaxDelayMs=" << policy.hardwareVolumeRestoreMaxDelayMs
       << " safeStartupDb=" << policy.safeStartupDb
       << " starvationEmptyTransferThreshold=" << policy.starvationEmptyTransferThreshold
       << " starvationRecoveryMs=" << policy.starvationRecoveryMs
       << " pcmWriteSoftLimitMs=" << policy.pcmWriteSoftLimitMs
       << " dsdWriteSoftLimitMs=" << policy.dsdWriteSoftLimitMs
       << " queueDepthMsNoFeedback=" << policy.queueDepthMsNoFeedback
       << " queueDepthMsFeedback=" << policy.queueDepthMsFeedback
       << " minTransferPool=" << policy.minTransferPool;
    return os.str();
}

std::string describeRawUac20QueueSizing(const RawUac20QueueSizing& sizing) {
    if (!sizing.summary.empty()) return sizing.summary;
    std::ostringstream os;
    os << "packetsPerTransfer=" << sizing.packetsPerTransfer
       << " transfers=" << sizing.transferCount
       << " transferBytes=" << sizing.transferBytes
       << " queueBytes=" << sizing.queueBytes
       << " queueMs=" << sizing.queueMs
       << " mode=" << (sizing.explicitFeedback ? "feedback" : "no-feedback");
    return os.str();
}

} // namespace rawsmusic::usb
