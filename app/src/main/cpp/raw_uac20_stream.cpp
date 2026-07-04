#include "raw_uac20_stream.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <iomanip>
#include <limits>
#include <sstream>

namespace rawsmusic::usb {
namespace {

int64_t streamNowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

int positiveOr(int value, int fallback) {
    return value > 0 ? value : fallback;
}

int computeIntervalsPerSecond(int interval) {
    // Full-speed bInterval is 1ms, high-speed bInterval is 2^(bInterval-1)
    // microframes.  The existing descriptor parser does not carry bus speed yet,
    // so keep the conservative UAC2/high-speed default when interval is unknown.
    if (interval <= 0) return 1000;
    if (interval == 1) return 1000;
    const int microframes = 1 << std::min(interval - 1, 7);
    return std::max(1, 8000 / microframes);
}

int clampPacketBytesToEndpoint(int packetBytes, int endpointMaxPacketSize, int frameBytes) {
    if (frameBytes <= 0) return 0;
    int out = rawFrameAlignDown(std::max(0, packetBytes), frameBytes);
    if (endpointMaxPacketSize > 0) {
        out = std::min(out, rawFrameAlignDown(endpointMaxPacketSize, frameBytes));
    }
    return std::max(frameBytes, out);
}

int computeDynamicMaxPacketBytes(int sampleRate, int intervalsPerSecond, int frameBytes, int endpointMaxPacketSize) {
    const int sr = std::max(1, sampleRate);
    const int ips = std::max(1, intervalsPerSecond);
    const int frame = std::max(1, frameBytes);
    // Match Uac20RealOutSubmitter/original-engine headroom: ceil(frames per
    // service interval) plus one extra frame for 44.1k/88.2k fractional Q32
    // accumulator rounding, then clamp to the endpoint's frame-aligned payload.
    int frames = (sr + ips - 1) / ips;
    frames += 1;
    int bytes = std::max(frame, frames * frame);
    if (endpointMaxPacketSize > 0) {
        bytes = std::min(bytes, rawFrameAlignDown(endpointMaxPacketSize, frame));
    }
    return std::max(frame, rawFrameAlignDown(bytes, frame));
}

int computeDynamicTransferBytes(const RawUac20StreamConfig& c) {
    if (!c.dynamicPacketSizing || c.sampleRate <= 0 || c.intervalsPerSecond <= 0 ||
        c.frameBytes <= 0 || c.packetsPerTransfer <= 0) {
        return 0;
    }
    const int maxPacketBytes = computeDynamicMaxPacketBytes(
            c.sampleRate,
            c.intervalsPerSecond,
            c.frameBytes,
            c.endpointMaxPacketSize);
    return rawFrameAlignUp(maxPacketBytes * std::max(1, c.packetsPerTransfer), c.frameBytes);
}

std::vector<int> buildOneTransferPacketPattern(const RawUac20StreamConfig& c, int transferBytes) {
    const int packets = std::max(1, c.packetsPerTransfer);
    const int frameBytes = std::max(1, c.frameBytes);
    std::vector<int> packetBytes;
    packetBytes.reserve(static_cast<size_t>(packets));

    if (!c.packetBytes.empty()) {
        for (int i = 0; i < packets; ++i) {
            const int src = c.packetBytes[static_cast<size_t>(i % c.packetBytes.size())];
            packetBytes.push_back(clampPacketBytesToEndpoint(src, c.endpointMaxPacketSize, frameBytes));
        }
        return packetBytes;
    }

    const int intervals = std::max(1, c.intervalsPerSecond);
    const int nominal = static_cast<int>(std::ceil(static_cast<double>(std::max(1, c.bytesPerSecond)) / intervals));
    int perPacket = clampPacketBytesToEndpoint(rawFrameAlignUp(nominal, frameBytes), c.endpointMaxPacketSize, frameBytes);
    if (transferBytes > 0) {
        perPacket = std::max(frameBytes, rawFrameAlignDown(transferBytes / packets, frameBytes));
    }
    for (int i = 0; i < packets; ++i) packetBytes.push_back(perPacket);
    return packetBytes;
}

} // namespace

const char* rawUac20StreamStateName(RawUac20StreamState state) {
    switch (state) {
        case RawUac20StreamState::Closed: return "Closed";
        case RawUac20StreamState::Configured: return "Configured";
        case RawUac20StreamState::Prepared: return "Prepared";
        case RawUac20StreamState::Streaming: return "Streaming";
        case RawUac20StreamState::Draining: return "Draining";
        case RawUac20StreamState::Standby: return "Standby";
        case RawUac20StreamState::Recovering: return "Recovering";
        case RawUac20StreamState::Detached: return "Detached";
        case RawUac20StreamState::Dead: return "Dead";
        default: return "Unknown";
    }
}

RawUac20Stream::RawUac20Stream() = default;

RawUac20Stream::~RawUac20Stream() {
    close("raw_uac20_stream_destructor");
}

bool RawUac20Stream::configure(const RawUac20StreamConfig& config) {
    RawUac20StreamConfig normalized = config;
    std::string error;
    if (!normalizeConfigLocked(&normalized, &error)) {
        status_.lastError = error;
        transitionTo(RawUac20StreamState::Closed, "configure-failed");
        return false;
    }

    stop("raw_uac20_stream_reconfigure");
    config_ = normalized;
    resetSyntheticConsumeLocked();
    resetShadowPacketPacerLocked();
    shadowPacketPacerEnabled_ = config_.shadowPacketPacer;
    syntheticConsumeEnabled_ = config_.syntheticConsume && !shadowPacketPacerEnabled_;

    // Step 103: keep RawUac20Stream's URB buffer sizing identical to the
    // legacy RealOutSubmitter/original engine.  Dynamic packet sizing may emit
    // one extra frame in a service interval (44.1k/88.2k fractional cadence),
    // so the transfer buffer must be large enough for maxPacketBytes * packets,
    // not just the current packet pattern's nominal total.  Without this,
    // dry-run configure fails with dynamic-transfer-buffer-too-small while the
    // legacy path correctly uses 672 bytes for 44.1k/24/2ch packed24.
    const int dynamicTransferBytes = computeDynamicTransferBytes(config_);
    if (dynamicTransferBytes > config_.transferBytes) {
        config_.transferBytes = dynamicTransferBytes;
    }

    queueSizing_ = rawComputeUac20QueueSizing(
            config_.bytesPerSecond,
            config_.transferBytes,
            config_.frameBytes,
            config_.explicitFeedback,
            config_.safetyPolicy);
    config_.packetsPerTransfer = queueSizing_.packetsPerTransfer;
    config_.transferCount = queueSizing_.transferCount;
    config_.transferBytes = queueSizing_.transferBytes;
    config_.queueBytes = queueSizing_.queueBytes;
    config_.startupPrebufferBytes = config_.startupPrebufferBytes > 0
            ? rawFrameAlignUp(config_.startupPrebufferBytes, config_.frameBytes)
            : queueSizing_.prebufferBytes;

    // Keep a little extra headroom beyond the initial URB pool, like the original
    // engine's FIFO before prepare_out_urbs.  This is not yet production-bound.
    const int targetBufferMs = std::max(config_.explicitFeedback
            ? config_.safetyPolicy.queueDepthMsFeedback
            : config_.safetyPolicy.queueDepthMsNoFeedback, 500);
    fifo_.configure(config_.frameBytes, config_.bytesPerSecond, targetBufferMs);

    const auto outConfig = buildOutSubmitterConfigLocked();
    if (!outQueue_.prepare(outConfig)) {
        status_.lastError = std::string("out-submit-config-failed:") + outQueue_.snapshot().lastError;
        transitionTo(RawUac20StreamState::Closed, "out-prepare-failed");
        return false;
    }

    status_.lastError.clear();
    transitionTo(RawUac20StreamState::Configured, "configure-ok");
    return true;
}

bool RawUac20Stream::attach(libusb_context* context, libusb_device_handle* handle) {
    context_ = context;
    handle_ = handle;
    if (status_.state == RawUac20StreamState::Closed) {
        status_.lastError = "attach-before-configure";
        return false;
    }
    refreshStatusLocked(&status_);
    return true;
}

bool RawUac20Stream::prepareUrbs() {
    if (status_.state == RawUac20StreamState::Closed) {
        status_.lastError = "prepare-before-configure";
        return false;
    }
    const auto outConfig = buildOutSubmitterConfigLocked();
    if (!outQueue_.prepare(outConfig)) {
        status_.lastError = std::string("out-submit-config-failed:") + outQueue_.snapshot().lastError;
        return false;
    }
    status_.lastError.clear();
    transitionTo(RawUac20StreamState::Prepared, "prepare-urbs-ok");
    return true;
}

bool RawUac20Stream::start(const char* reason) {
    if (status_.state != RawUac20StreamState::Prepared &&
        status_.state != RawUac20StreamState::Standby &&
        status_.state != RawUac20StreamState::Configured) {
        status_.lastError = "start-before-prepare";
        return false;
    }
    if (context_ == nullptr || handle_ == nullptr) {
        status_.lastError = "start-before-attach";
        return false;
    }
    if (!readyForOutStart()) {
        status_.lastError = "start-before-prebuffer";
        return false;
    }

    if (!config_.useExternalEventLoop &&
        !eventLoop_.running() &&
        !eventLoop_.start(context_, reason ? reason : "raw_uac20_stream_start")) {
        status_.lastError = "event-loop-start-failed";
        return false;
    }
    if (config_.allowFeedback && config_.feedbackEndpoint != 0) {
        feedback_.start(handle_, config_.feedbackEndpoint);
    }
    if (config_.allowOutSubmit) {
        if (!outQueue_.startDebugFeeder(handle_, &fifo_)) {
            status_.lastError = "out-start-failed";
            return false;
        }
    }
    status_.lastError.clear();
    transitionTo(RawUac20StreamState::Streaming, reason ? reason : "start-ok");
    return true;
}

void RawUac20Stream::stop(const char* reason) {
    outQueue_.cancelAndRelease(reason ? reason : "raw_uac20_stream_stop");
    feedback_.stop(reason ? reason : "raw_uac20_stream_stop");
    eventLoop_.stop(reason ? reason : "raw_uac20_stream_stop");
    if (status_.state == RawUac20StreamState::Streaming ||
        status_.state == RawUac20StreamState::Draining) {
        transitionTo(RawUac20StreamState::Prepared, reason ? reason : "stop");
    } else {
        refreshStatusLocked(&status_);
    }
}

void RawUac20Stream::standby(const char* reason) {
    stop(reason ? reason : "raw_uac20_stream_standby");
    if (status_.state != RawUac20StreamState::Closed) {
        transitionTo(RawUac20StreamState::Standby, reason ? reason : "standby");
    }
}

void RawUac20Stream::close(const char* reason) {
    outQueue_.cancelAndRelease(reason ? reason : "raw_uac20_stream_close");
    feedback_.stop(reason ? reason : "raw_uac20_stream_close");
    eventLoop_.stop(reason ? reason : "raw_uac20_stream_close");
    fifo_.reset(reason ? reason : "raw_uac20_stream_close");
    resetSyntheticConsumeLocked();
    resetShadowPacketPacerLocked();
    context_ = nullptr;
    handle_ = nullptr;
    transitionTo(RawUac20StreamState::Closed, reason ? reason : "close");
}

void RawUac20Stream::reset(const char* reason) {
    outQueue_.cancelAndRelease(reason ? reason : "raw_uac20_stream_reset");
    feedback_.stop(reason ? reason : "raw_uac20_stream_reset");
    fifo_.reset(reason ? reason : "raw_uac20_stream_reset");
    resetSyntheticConsumeLocked();
    resetShadowPacketPacerLocked();
    shadowPacketPacerEnabled_ = config_.shadowPacketPacer;
    syntheticConsumeEnabled_ = config_.syntheticConsume && !shadowPacketPacerEnabled_;
    if (status_.state != RawUac20StreamState::Closed) {
        transitionTo(RawUac20StreamState::Configured, reason ? reason : "reset");
    }
}

int RawUac20Stream::writeDevicePcm(const uint8_t* data, int bytes) {
    if (shadowPacketPacerEnabled_) {
        shadowPacketPacerConsumeForNowLocked("before-shadow-write");
    } else {
        syntheticConsumeForNowLocked("before-shadow-write");
    }

    const auto alignment = rawValidateFrameAlignedBytes(bytes, config_.frameBytes, "RawUac20Stream::writeDevicePcm");
    if (!alignment.valid) {
        status_.lastError = alignment.reason;
        return -1;
    }
    if (alignment.alignedBytes <= 0) {
        refreshStatusLocked(&status_);
        return 0;
    }
    const int written = fifo_.write(data, alignment.alignedBytes);

    if (shadowPacketPacerEnabled_) {
        shadowPacketPacerConsumeForNowLocked("after-shadow-write");
    } else {
        syntheticConsumeForNowLocked("after-shadow-write");
    }
    refreshStatusLocked(&status_);
    return written;
}

int RawUac20Stream::syntheticConsumeForNowLocked(const char* source) {
    if (!syntheticConsumeEnabled_ || config_.bytesPerSecond <= 0 || config_.frameBytes <= 0) {
        return 0;
    }

    const int64_t now = streamNowMs();
    if (syntheticConsumeLastMs_ == 0) {
        syntheticConsumeStartMs_ = now;
        syntheticConsumeLastMs_ = now;
        syntheticConsumeBytesPerSecond_ = 0;
        return 0;
    }

    const int64_t elapsedMs = std::max<int64_t>(0, now - syntheticConsumeLastMs_);
    if (elapsedMs <= 0) return 0;
    syntheticConsumeLastMs_ = now;

    const double rawTarget = syntheticConsumeCarryBytes_ +
            (static_cast<double>(config_.bytesPerSecond) * static_cast<double>(elapsedMs) / 1000.0);
    int target = rawFrameAlignDown(static_cast<int>(rawTarget), config_.frameBytes);
    syntheticConsumeCarryBytes_ = rawTarget - static_cast<double>(target);
    if (target <= 0) return 0;

    syntheticConsumeScratch_.resize(static_cast<size_t>(std::min(target, 64 * 1024)));
    int remaining = target;
    int consumed = 0;
    while (remaining > 0) {
        const int chunk = std::min(remaining, static_cast<int>(syntheticConsumeScratch_.size()));
        const int got = fifo_.read(syntheticConsumeScratch_.data(), chunk);
        if (got <= 0) break;
        consumed += got;
        remaining -= got;
        if (got < chunk) break;
    }

    const int missing = std::max(0, target - consumed);
    syntheticConsumeCalls_ += 1;
    syntheticConsumeTotalTargetBytes_ += target;
    syntheticConsumeTotalConsumedBytes_ += consumed;
    if (missing > 0) syntheticConsumeUnderrunCalls_ += 1;
    syntheticConsumeLastTargetBytes_ = target;
    syntheticConsumeLastConsumedBytes_ = consumed;
    syntheticConsumeLastMissingBytes_ = missing;

    const int64_t totalElapsed = std::max<int64_t>(1, now - syntheticConsumeStartMs_);
    syntheticConsumeBytesPerSecond_ = static_cast<int>(
            (syntheticConsumeTotalConsumedBytes_ * 1000LL) / totalElapsed);
    if (missing > 0) {
        status_.lastError = std::string("synthetic-consume-underrun:") +
                (source ? source : "unknown");
    }
    return consumed;
}

void RawUac20Stream::resetSyntheticConsumeLocked() {
    syntheticConsumeStartMs_ = 0;
    syntheticConsumeLastMs_ = 0;
    syntheticConsumeCarryBytes_ = 0.0;
    syntheticConsumeCalls_ = 0;
    syntheticConsumeTotalTargetBytes_ = 0;
    syntheticConsumeTotalConsumedBytes_ = 0;
    syntheticConsumeUnderrunCalls_ = 0;
    syntheticConsumeLastTargetBytes_ = 0;
    syntheticConsumeLastConsumedBytes_ = 0;
    syntheticConsumeLastMissingBytes_ = 0;
    syntheticConsumeBytesPerSecond_ = 0;
    syntheticConsumeScratch_.clear();
}

int RawUac20Stream::readFifoForShadowLocked(int targetBytes) {
    if (targetBytes <= 0) return 0;
    shadowPacketPacerScratch_.resize(static_cast<size_t>(std::min(targetBytes, 64 * 1024)));
    int remaining = targetBytes;
    int consumed = 0;
    while (remaining > 0) {
        const int chunk = std::min(remaining, static_cast<int>(shadowPacketPacerScratch_.size()));
        const int got = fifo_.read(shadowPacketPacerScratch_.data(), chunk);
        if (got <= 0) break;
        consumed += got;
        remaining -= got;
        if (got < chunk) break;
    }
    return consumed;
}

int RawUac20Stream::shadowPacketSizeForNextIntervalLocked() {
    if (config_.sampleRate <= 0 || config_.intervalsPerSecond <= 0 || config_.frameBytes <= 0) {
        return 0;
    }
    if (shadowPacketPacerFrameStepQ32_ == 0) {
        shadowPacketPacerFrameStepQ32_ =
                (static_cast<uint64_t>(config_.sampleRate) << 32) /
                static_cast<uint64_t>(std::max(1, config_.intervalsPerSecond));
    }

    shadowPacketPacerFramesQ32_ += shadowPacketPacerFrameStepQ32_;
    int frames = static_cast<int>(shadowPacketPacerFramesQ32_ >> 32);
    shadowPacketPacerFramesQ32_ &= 0xffffffffULL;
    if (frames <= 0) return 0;

    int packetBytes = frames * config_.frameBytes;
    if (config_.endpointMaxPacketSize > 0) {
        packetBytes = std::min(packetBytes,
                rawFrameAlignDown(config_.endpointMaxPacketSize, config_.frameBytes));
    }
    packetBytes = rawFrameAlignDown(packetBytes, config_.frameBytes);
    return std::max(0, packetBytes);
}

int RawUac20Stream::shadowPacketPacerConsumeForNowLocked(const char* source) {
    if (!shadowPacketPacerEnabled_ || config_.bytesPerSecond <= 0 ||
        config_.frameBytes <= 0 || config_.intervalsPerSecond <= 0) {
        return 0;
    }

    const int64_t now = streamNowMs();
    if (shadowPacketPacerLastMs_ == 0) {
        shadowPacketPacerStartMs_ = now;
        shadowPacketPacerLastMs_ = now;
        shadowPacketPacerFrameStepQ32_ =
                (static_cast<uint64_t>(config_.sampleRate) << 32) /
                static_cast<uint64_t>(std::max(1, config_.intervalsPerSecond));
        return 0;
    }

    const int64_t elapsedMs = std::max<int64_t>(0, now - shadowPacketPacerLastMs_);
    if (elapsedMs <= 0) return 0;
    shadowPacketPacerLastMs_ = now;

    const double rawIntervals = shadowPacketPacerCarryIntervals_ +
            (static_cast<double>(config_.intervalsPerSecond) * static_cast<double>(elapsedMs) / 1000.0);
    int intervals = static_cast<int>(std::floor(rawIntervals));
    shadowPacketPacerCarryIntervals_ = rawIntervals - static_cast<double>(intervals);
    if (intervals <= 0) return 0;

    const int maxIntervalsPerCall = std::max(config_.packetsPerTransfer,
            std::min(std::max(1, config_.intervalsPerSecond / 2), 512));
    if (intervals > maxIntervalsPerCall) {
        shadowPacketPacerCarryIntervals_ = 0.0;
        intervals = maxIntervalsPerCall;
    }

    shadowPacketPacerCalls_ += 1;
    int consumedTotal = 0;
    int targetTotal = 0;
    int packetMin = std::numeric_limits<int>::max();
    int packetMax = 0;
    int packetTotal = 0;
    std::ostringstream pattern;
    pattern << "[";

    for (int i = 0; i < intervals; ++i) {
        const int target = shadowPacketSizeForNextIntervalLocked();
        if (i) pattern << ",";
        pattern << target;
        if (target <= 0) continue;

        const int consumed = readFifoForShadowLocked(target);
        const int missing = std::max(0, target - consumed);
        targetTotal += target;
        consumedTotal += consumed;
        packetTotal += target;
        packetMin = std::min(packetMin, target);
        packetMax = std::max(packetMax, target);

        shadowPacketPacerLastIntervalTargetBytes_ = target;
        shadowPacketPacerLastIntervalConsumedBytes_ = consumed;
        shadowPacketPacerLastIntervalMissingBytes_ = missing;
        if (missing > 0) shadowPacketPacerUnderrunPackets_ += 1;

        shadowPacketPacerCurrentTransferPacketCount_ += 1;
        shadowPacketPacerCurrentTransferTargetBytes_ += target;
        shadowPacketPacerCurrentTransferConsumedBytes_ += consumed;
        shadowPacketPacerCurrentTransferMissingBytes_ += missing;
        if (shadowPacketPacerCurrentTransferPacketCount_ >= std::max(1, config_.packetsPerTransfer)) {
            shadowPacketPacerTransfers_ += 1;
            shadowPacketPacerLastTransferPacketCount_ = shadowPacketPacerCurrentTransferPacketCount_;
            shadowPacketPacerLastTransferTargetBytes_ = shadowPacketPacerCurrentTransferTargetBytes_;
            shadowPacketPacerLastTransferConsumedBytes_ = shadowPacketPacerCurrentTransferConsumedBytes_;
            shadowPacketPacerLastTransferMissingBytes_ = shadowPacketPacerCurrentTransferMissingBytes_;
            shadowPacketPacerCurrentTransferPacketCount_ = 0;
            shadowPacketPacerCurrentTransferTargetBytes_ = 0;
            shadowPacketPacerCurrentTransferConsumedBytes_ = 0;
            shadowPacketPacerCurrentTransferMissingBytes_ = 0;
        }
    }
    pattern << "]";

    shadowPacketPacerIntervals_ += intervals;
    shadowPacketPacerTargetBytes_ += targetTotal;
    shadowPacketPacerConsumedBytes_ += consumedTotal;
    if (packetMin == std::numeric_limits<int>::max()) packetMin = 0;
    shadowPacketPacerLastPacketLengthMin_ = packetMin;
    shadowPacketPacerLastPacketLengthMax_ = packetMax;
    shadowPacketPacerLastPacketLengthTotal_ = packetTotal;
    shadowPacketPacerPacketPatternSummary_ = pattern.str();

    const int64_t totalElapsed = std::max<int64_t>(1, now - shadowPacketPacerStartMs_);
    shadowPacketPacerBytesPerSecond_ = static_cast<int>(
            (shadowPacketPacerConsumedBytes_ * 1000LL) / totalElapsed);
    if (shadowPacketPacerLastIntervalMissingBytes_ > 0) {
        status_.lastError = std::string("shadow-packet-pacer-underrun:") +
                (source ? source : "unknown");
    }
    return consumedTotal;
}

void RawUac20Stream::resetShadowPacketPacerLocked() {
    shadowPacketPacerStartMs_ = 0;
    shadowPacketPacerLastMs_ = 0;
    shadowPacketPacerCarryIntervals_ = 0.0;
    shadowPacketPacerFramesQ32_ = 0;
    shadowPacketPacerFrameStepQ32_ = 0;
    shadowPacketPacerCurrentTransferPacketCount_ = 0;
    shadowPacketPacerCurrentTransferTargetBytes_ = 0;
    shadowPacketPacerCurrentTransferConsumedBytes_ = 0;
    shadowPacketPacerCurrentTransferMissingBytes_ = 0;
    shadowPacketPacerCalls_ = 0;
    shadowPacketPacerIntervals_ = 0;
    shadowPacketPacerTransfers_ = 0;
    shadowPacketPacerTargetBytes_ = 0;
    shadowPacketPacerConsumedBytes_ = 0;
    shadowPacketPacerUnderrunPackets_ = 0;
    shadowPacketPacerLastIntervalTargetBytes_ = 0;
    shadowPacketPacerLastIntervalConsumedBytes_ = 0;
    shadowPacketPacerLastIntervalMissingBytes_ = 0;
    shadowPacketPacerLastTransferTargetBytes_ = 0;
    shadowPacketPacerLastTransferConsumedBytes_ = 0;
    shadowPacketPacerLastTransferMissingBytes_ = 0;
    shadowPacketPacerLastPacketLengthMin_ = 0;
    shadowPacketPacerLastPacketLengthMax_ = 0;
    shadowPacketPacerLastPacketLengthTotal_ = 0;
    shadowPacketPacerLastTransferPacketCount_ = 0;
    shadowPacketPacerBytesPerSecond_ = 0;
    shadowPacketPacerPacketPatternSummary_.clear();
    shadowPacketPacerScratch_.clear();
}

bool RawUac20Stream::readyForOutStart() const {
    const auto stats = fifo_.snapshot();
    return stats.initialized && stats.levelBytes >= requiredPrebufferBytes();
}

int RawUac20Stream::requiredPrebufferBytes() const {
    if (config_.startupPrebufferBytes > 0) return config_.startupPrebufferBytes;
    if (queueSizing_.prebufferBytes > 0) return queueSizing_.prebufferBytes;
    return rawStartupPrebufferBytes(
            config_.bytesPerSecond,
            config_.transferBytes,
            config_.frameBytes,
            config_.explicitFeedback,
            config_.safetyPolicy);
}

RawUac20StreamStatus RawUac20Stream::status() const {
    RawUac20StreamStatus s = status_;
    refreshStatusLocked(&s);
    return s;
}

std::string RawUac20Stream::runtimeJson() const {
    const auto s = status();
    std::ostringstream os;
    os << "{\"rawUac20StreamState\":\"" << rawUac20StreamStateName(s.state) << "\",";
    os << "\"configured\":" << (s.configured ? "true" : "false") << ",";
    os << "\"attached\":" << (s.attached ? "true" : "false") << ",";
    os << "\"prepared\":" << (s.prepared ? "true" : "false") << ",";
    os << "\"streaming\":" << (s.streaming ? "true" : "false") << ",";
    os << "\"sampleRate\":" << s.sampleRate << ",";
    os << "\"channels\":" << s.channels << ",";
    os << "\"validBits\":" << s.validBits << ",";
    os << "\"subslotBytes\":" << s.subslotBytes << ",";
    os << "\"frameBytes\":" << s.frameBytes << ",";
    os << "\"outEndpoint\":" << s.outEndpoint << ",";
    os << "\"feedbackEndpoint\":" << s.feedbackEndpoint << ",";
    os << "\"explicitFeedback\":" << (s.explicitFeedback ? "true" : "false") << ",";
    os << "\"allowOutSubmit\":" << (s.allowOutSubmit ? "true" : "false") << ",";
    os << "\"packetsPerTransfer\":" << s.packetsPerTransfer << ",";
    os << "\"transferCount\":" << s.transferCount << ",";
    os << "\"transferBytes\":" << s.transferBytes << ",";
    os << "\"queueBytes\":" << s.queueBytes << ",";
    os << "\"startupPrebufferBytes\":" << s.startupPrebufferBytes << ",";
    os << "\"writeRingLevelBytes\":" << s.writeRingLevelBytes << ",";
    os << "\"writeRingTotalDroppedBytes\":" << s.writeRingTotalDroppedBytes << ",";
    os << "\"eventLoopRunning\":" << (s.eventLoopRunning ? "true" : "false") << ",";
    os << "\"feedbackActive\":" << (s.feedbackActive ? "true" : "false") << ",";
    os << "\"outSubmitted\":" << (s.outSubmitted ? "true" : "false") << ",";
    os << "\"outCompletedBytesPerSecond\":" << s.outCompletedBytesPerSecond << ",";
    os << "\"outCompletionRatio\":" << s.outCompletionRatio << ",";
    os << "\"queueSizingSummary\":\"" << jsonEscape(s.queueSizingSummary) << "\",";
    os << "\"formatMatchSummary\":\"" << jsonEscape(s.formatMatchSummary) << "\",";
    os << "\"writeRingSummary\":\"" << jsonEscape(s.writeRingSummary) << "\",";
    os << "\"feedbackSummary\":\"" << jsonEscape(s.feedbackSummary) << "\",";
    os << "\"outSummary\":\"" << jsonEscape(s.outSummary) << "\",";
    os << "\"eventLoopSummary\":\"" << jsonEscape(s.eventLoopSummary) << "\",";
    os << "\"rawStreamDryRunSyntheticConsumeEnabled\":" << (s.syntheticConsumeEnabled ? "true" : "false") << ',';
    os << "\"rawStreamDryRunSyntheticConsumeActive\":" << (s.syntheticConsumeActive ? "true" : "false") << ',';
    os << "\"rawStreamDryRunSyntheticConsumeCalls\":" << s.syntheticConsumeCalls << ',';
    os << "\"rawStreamDryRunSyntheticConsumeTargetBytes\":" << s.syntheticConsumeTotalTargetBytes << ',';
    os << "\"rawStreamDryRunSyntheticConsumeBytes\":" << s.syntheticConsumeTotalConsumedBytes << ',';
    os << "\"rawStreamDryRunSyntheticConsumeUnderrunCalls\":" << s.syntheticConsumeUnderrunCalls << ',';
    os << "\"rawStreamDryRunSyntheticConsumeLastTargetBytes\":" << s.syntheticConsumeLastTargetBytes << ',';
    os << "\"rawStreamDryRunSyntheticConsumeLastBytes\":" << s.syntheticConsumeLastConsumedBytes << ',';
    os << "\"rawStreamDryRunSyntheticConsumeLastMissingBytes\":" << s.syntheticConsumeLastMissingBytes << ',';
    os << "\"rawStreamDryRunSyntheticConsumeBytesPerSecond\":" << s.syntheticConsumeBytesPerSecond << ',';
    os << "\"rawStreamDryRunSyntheticConsumeSummary\":\"" << jsonEscape(s.syntheticConsumeSummary) << "\",";
    os << "\"rawStreamDryRunShadowPacerEnabled\":" << (s.shadowPacketPacerEnabled ? "true" : "false") << ',';
    os << "\"rawStreamDryRunShadowPacerActive\":" << (s.shadowPacketPacerActive ? "true" : "false") << ',';
    os << "\"rawStreamDryRunShadowPacerCalls\":" << s.shadowPacketPacerCalls << ',';
    os << "\"rawStreamDryRunShadowPacerIntervals\":" << s.shadowPacketPacerIntervals << ',';
    os << "\"rawStreamDryRunShadowPacerTransfers\":" << s.shadowPacketPacerTransfers << ',';
    os << "\"rawStreamDryRunShadowPacerTargetBytes\":" << s.shadowPacketPacerTargetBytes << ',';
    os << "\"rawStreamDryRunShadowPacerConsumedBytes\":" << s.shadowPacketPacerConsumedBytes << ',';
    os << "\"rawStreamDryRunShadowPacerUnderrunPackets\":" << s.shadowPacketPacerUnderrunPackets << ',';
    os << "\"rawStreamDryRunShadowPacerLastIntervalTargetBytes\":" << s.shadowPacketPacerLastIntervalTargetBytes << ',';
    os << "\"rawStreamDryRunShadowPacerLastIntervalConsumedBytes\":" << s.shadowPacketPacerLastIntervalConsumedBytes << ',';
    os << "\"rawStreamDryRunShadowPacerLastIntervalMissingBytes\":" << s.shadowPacketPacerLastIntervalMissingBytes << ',';
    os << "\"rawStreamDryRunShadowPacerLastTransferTargetBytes\":" << s.shadowPacketPacerLastTransferTargetBytes << ',';
    os << "\"rawStreamDryRunShadowPacerLastTransferConsumedBytes\":" << s.shadowPacketPacerLastTransferConsumedBytes << ',';
    os << "\"rawStreamDryRunShadowPacerLastTransferMissingBytes\":" << s.shadowPacketPacerLastTransferMissingBytes << ',';
    os << "\"rawStreamDryRunShadowPacerPacketLengthMin\":" << s.shadowPacketPacerLastPacketLengthMin << ',';
    os << "\"rawStreamDryRunShadowPacerPacketLengthMax\":" << s.shadowPacketPacerLastPacketLengthMax << ',';
    os << "\"rawStreamDryRunShadowPacerPacketLengthTotal\":" << s.shadowPacketPacerLastPacketLengthTotal << ',';
    os << "\"rawStreamDryRunShadowPacerBytesPerSecond\":" << s.shadowPacketPacerBytesPerSecond << ',';
    os << "\"rawStreamDryRunShadowPacerPacketPatternSummary\":\"" << jsonEscape(s.shadowPacketPacerPacketPatternSummary) << "\",";
    os << "\"rawStreamDryRunShadowPacerSummary\":\"" << jsonEscape(s.shadowPacketPacerSummary) << "\",";
    os << "\"lastError\":\"" << jsonEscape(s.lastError) << "\"}";
    return os.str();
}

bool RawUac20Stream::normalizeConfigLocked(RawUac20StreamConfig* config, std::string* error) const {
    if (config == nullptr) return false;
    if (config->sampleRate <= 0) { if (error) *error = "invalid-sample-rate"; return false; }
    if (config->channels <= 0) { if (error) *error = "invalid-channels"; return false; }
    if (config->validBits <= 0) { if (error) *error = "invalid-valid-bits"; return false; }
    if (config->subslotBytes <= 0) config->subslotBytes = std::max(1, (config->validBits + 7) / 8);
    if (config->frameBytes <= 0) config->frameBytes = config->channels * config->subslotBytes;
    if (config->frameBytes != config->channels * config->subslotBytes) {
        if (error) *error = "frame-bytes-mismatch";
        return false;
    }
    if (config->bytesPerSecond <= 0) config->bytesPerSecond = config->sampleRate * config->frameBytes;
    if (config->outEndpoint <= 0) { if (error) *error = "missing-out-endpoint"; return false; }
    config->intervalsPerSecond = positiveOr(config->intervalsPerSecond,
            computeIntervalsPerSecond(config->endpointInterval));
    config->packetsPerTransfer = positiveOr(config->packetsPerTransfer,
            std::max(1, config->safetyPolicy.isoPacketsPerTransfer));
    if (config->transferBytes <= 0) {
        const int nominalPacketBytes = rawFrameAlignUp(
                static_cast<int>(std::ceil(static_cast<double>(config->bytesPerSecond) /
                                           std::max(1, config->intervalsPerSecond))),
                config->frameBytes);
        const int clampedPacketBytes = clampPacketBytesToEndpoint(
                nominalPacketBytes,
                config->endpointMaxPacketSize,
                config->frameBytes);
        config->transferBytes = clampedPacketBytes * config->packetsPerTransfer;
    }
    config->transferBytes = rawFrameAlignUp(config->transferBytes, config->frameBytes);
    config->explicitFeedback = config->feedbackEndpoint != 0 && config->explicitFeedback;
    config->feedbackRequired = config->feedbackEndpoint != 0 && config->feedbackRequired;
    return true;
}

Uac20RealOutSubmitterConfig RawUac20Stream::buildOutSubmitterConfigLocked() const {
    Uac20RealOutSubmitterConfig out{};
    out.endpointAddress = config_.outEndpoint;
    out.transferCount = std::max(1, config_.transferCount);
    out.packetsPerTransfer = std::max(1, config_.packetsPerTransfer);
    out.transferBytes = std::max(config_.frameBytes, config_.transferBytes);
    out.endpointMaxPacketSize = config_.endpointMaxPacketSize;
    out.timeoutMs = config_.timeoutMs;
    out.cancelWaitMs = config_.cancelWaitMs;
    out.expectedBytesPerSecond = config_.bytesPerSecond;
    out.submissionEnabled = config_.allowOutSubmit;
    out.zeroFill = config_.allowZeroFill;
    out.debugSmokeTest = config_.debugSmokeTest;
    out.autoResubmit = config_.autoResubmit;
    out.feedFromWriteRing = true;
    out.maxCallbacks = 0;
    out.maxRunMs = 0;
    out.dynamicPacketSizing = config_.dynamicPacketSizing;
    out.sampleRate = config_.sampleRate;
    out.frameBytes = config_.frameBytes;
    out.intervalsPerSecond = config_.intervalsPerSecond;
    // Dynamic packet sizing uses the Q32 accumulator in Uac20RealOutSubmitter;
    // packetBytes is only useful for non-dynamic/static patterns.  Passing both
    // is harmless today because the submitter checks dynamic first, but keeping
    // this explicit avoids misleading dry-run diagnostics.
    out.packetBytes = config_.dynamicPacketSizing
            ? std::vector<int>{}
            : buildOneTransferPacketPattern(config_, out.transferBytes);
    return out;
}

void RawUac20Stream::refreshStatusLocked(RawUac20StreamStatus* status) const {
    if (status == nullptr) return;
    const auto fifoStats = fifo_.snapshot();
    const auto feedbackStats = feedback_.snapshot();
    const auto outStats = outQueue_.snapshot();

    status->configured = status->state != RawUac20StreamState::Closed;
    status->attached = context_ != nullptr && handle_ != nullptr;
    status->prepared = status->state == RawUac20StreamState::Prepared ||
            status->state == RawUac20StreamState::Streaming ||
            status->state == RawUac20StreamState::Standby;
    status->streaming = status->state == RawUac20StreamState::Streaming;
    status->standby = status->state == RawUac20StreamState::Standby;

    status->sampleRate = config_.sampleRate;
    status->channels = config_.channels;
    status->validBits = config_.validBits;
    status->subslotBytes = config_.subslotBytes;
    status->frameBytes = config_.frameBytes;
    status->bytesPerSecond = config_.bytesPerSecond;
    status->outEndpoint = config_.outEndpoint;
    status->feedbackEndpoint = config_.feedbackEndpoint;
    status->explicitFeedback = config_.explicitFeedback;
    status->feedbackRequired = config_.feedbackRequired;
    status->allowOutSubmit = config_.allowOutSubmit;
    status->useExternalEventLoop = config_.useExternalEventLoop;
    status->packetsPerTransfer = config_.packetsPerTransfer;
    status->transferCount = config_.transferCount;
    status->transferBytes = config_.transferBytes;
    status->queueBytes = config_.queueBytes;
    status->startupPrebufferBytes = requiredPrebufferBytes();
    status->queueSizingSummary = describeRawUac20QueueSizing(queueSizing_);
    status->formatMatchSummary = describeRawUac20FormatMatchResult(config_.formatMatch);

    status->writeRingLevelBytes = fifoStats.levelBytes;
    status->writeRingCapacityBytes = fifoStats.capacityBytes;
    status->writeRingTotalInputBytes = fifoStats.totalInputBytes;
    status->writeRingTotalAcceptedBytes = fifoStats.totalAcceptedBytes;
    status->writeRingTotalDroppedBytes = fifoStats.totalDroppedBytes;
    status->writeRingSummary = describeUac20WriteRingStats(fifoStats);

    status->syntheticConsumeEnabled = syntheticConsumeEnabled_;
    status->syntheticConsumeActive = syntheticConsumeEnabled_ && syntheticConsumeLastMs_ > 0;
    status->syntheticConsumeCalls = syntheticConsumeCalls_;
    status->syntheticConsumeTotalTargetBytes = syntheticConsumeTotalTargetBytes_;
    status->syntheticConsumeTotalConsumedBytes = syntheticConsumeTotalConsumedBytes_;
    status->syntheticConsumeUnderrunCalls = syntheticConsumeUnderrunCalls_;
    status->syntheticConsumeLastTargetBytes = syntheticConsumeLastTargetBytes_;
    status->syntheticConsumeLastConsumedBytes = syntheticConsumeLastConsumedBytes_;
    status->syntheticConsumeLastMissingBytes = syntheticConsumeLastMissingBytes_;
    status->syntheticConsumeBytesPerSecond = syntheticConsumeBytesPerSecond_;
    {
        std::ostringstream os;
        os << "enabled=" << (status->syntheticConsumeEnabled ? "yes" : "no")
           << " active=" << (status->syntheticConsumeActive ? "yes" : "no")
           << " calls=" << status->syntheticConsumeCalls
           << " target=" << status->syntheticConsumeTotalTargetBytes
           << " consumed=" << status->syntheticConsumeTotalConsumedBytes
           << " underrun=" << status->syntheticConsumeUnderrunCalls
           << " bps=" << status->syntheticConsumeBytesPerSecond
           << " last=" << status->syntheticConsumeLastConsumedBytes << "/" << status->syntheticConsumeLastTargetBytes
           << " missing=" << status->syntheticConsumeLastMissingBytes;
        status->syntheticConsumeSummary = os.str();
    }

    status->shadowPacketPacerEnabled = shadowPacketPacerEnabled_;
    status->shadowPacketPacerActive = shadowPacketPacerEnabled_ && shadowPacketPacerLastMs_ > 0;
    status->shadowPacketPacerCalls = shadowPacketPacerCalls_;
    status->shadowPacketPacerIntervals = shadowPacketPacerIntervals_;
    status->shadowPacketPacerTransfers = shadowPacketPacerTransfers_;
    status->shadowPacketPacerTargetBytes = shadowPacketPacerTargetBytes_;
    status->shadowPacketPacerConsumedBytes = shadowPacketPacerConsumedBytes_;
    status->shadowPacketPacerUnderrunPackets = shadowPacketPacerUnderrunPackets_;
    status->shadowPacketPacerLastIntervalTargetBytes = shadowPacketPacerLastIntervalTargetBytes_;
    status->shadowPacketPacerLastIntervalConsumedBytes = shadowPacketPacerLastIntervalConsumedBytes_;
    status->shadowPacketPacerLastIntervalMissingBytes = shadowPacketPacerLastIntervalMissingBytes_;
    status->shadowPacketPacerLastTransferTargetBytes = shadowPacketPacerLastTransferTargetBytes_;
    status->shadowPacketPacerLastTransferConsumedBytes = shadowPacketPacerLastTransferConsumedBytes_;
    status->shadowPacketPacerLastTransferMissingBytes = shadowPacketPacerLastTransferMissingBytes_;
    status->shadowPacketPacerLastPacketLengthMin = shadowPacketPacerLastPacketLengthMin_;
    status->shadowPacketPacerLastPacketLengthMax = shadowPacketPacerLastPacketLengthMax_;
    status->shadowPacketPacerLastPacketLengthTotal = shadowPacketPacerLastPacketLengthTotal_;
    status->shadowPacketPacerLastTransferPacketCount = shadowPacketPacerLastTransferPacketCount_;
    status->shadowPacketPacerBytesPerSecond = shadowPacketPacerBytesPerSecond_;
    status->shadowPacketPacerPacketPatternSummary = shadowPacketPacerPacketPatternSummary_;
    {
        std::ostringstream os;
        os << "enabled=" << (status->shadowPacketPacerEnabled ? "yes" : "no")
           << " active=" << (status->shadowPacketPacerActive ? "yes" : "no")
           << " calls=" << status->shadowPacketPacerCalls
           << " intervals=" << status->shadowPacketPacerIntervals
           << " transfers=" << status->shadowPacketPacerTransfers
           << " target=" << status->shadowPacketPacerTargetBytes
           << " consumed=" << status->shadowPacketPacerConsumedBytes
           << " underrun=" << status->shadowPacketPacerUnderrunPackets
           << " bps=" << status->shadowPacketPacerBytesPerSecond
           << " pktLen=" << status->shadowPacketPacerLastPacketLengthMin << ".." << status->shadowPacketPacerLastPacketLengthMax
           << " pktLenTotal=" << status->shadowPacketPacerLastPacketLengthTotal
           << " pattern=" << status->shadowPacketPacerPacketPatternSummary
           << " lastInterval=" << status->shadowPacketPacerLastIntervalConsumedBytes << "/" << status->shadowPacketPacerLastIntervalTargetBytes
           << " lastTransfer=" << status->shadowPacketPacerLastTransferConsumedBytes << "/" << status->shadowPacketPacerLastTransferTargetBytes;
        status->shadowPacketPacerSummary = os.str();
    }

    status->eventLoopRunning = config_.useExternalEventLoop || eventLoop_.running();
    status->eventLoopTicks = eventLoop_.tickCount();
    status->eventLoopSummary = eventLoop_.summary();

    status->feedbackActive = feedbackStats.active;
    status->feedbackCompleteCount = feedbackStats.completeCount;
    status->feedbackErrorCount = feedbackStats.errorCount;
    status->feedbackFramesPerMicroframe = feedbackStats.feedbackFramesPerMicroframe;
    status->feedbackSummary = describeUac20FeedbackRuntime(feedbackStats);

    status->outPrepared = outStats.initialized;
    status->outSubmitted = outStats.submitted;
    status->outActive = outStats.active;
    status->outCallbackCount = outStats.callbackCount;
    status->outCompleteCount = outStats.completeCount;
    status->outSubmitOkCount = outStats.submitOkCount;
    status->outSubmitFailCount = outStats.submitFailCount;
    status->outCompletedBytesPerSecond = outStats.completedBytesPerSecond;
    status->outExpectedBytesPerSecond = outStats.expectedBytesPerSecond;
    status->outCompletionRatio = outStats.completionRatio;
    status->outZeroFilledBytes = outStats.zeroFilledBytes;
    status->outFedBytes = outStats.fedBytes;
    status->outFeederUnderrunCount = outStats.feederUnderrunCount;
    status->outSummary = describeUac20RealOutSubmitterStats(outStats);
    status->summary = describeRawUac20StreamStatus(*status);
}

void RawUac20Stream::transitionTo(RawUac20StreamState state, const char*) {
    status_.state = state;
    refreshStatusLocked(&status_);
}

std::string RawUac20Stream::jsonEscape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (char c : in) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out;
}

std::string describeRawUac20StreamConfig(const RawUac20StreamConfig& config) {
    std::ostringstream os;
    os << "fmt=" << config.sampleRate << "Hz/" << config.validBits << "bit/"
       << config.channels << "ch/subslot=" << config.subslotBytes
       << " frame=" << config.frameBytes
       << " bps=" << config.bytesPerSecond
       << " iface=" << config.audioStreamingInterface
       << " alt=" << config.altSetting
       << " out=0x" << std::hex << config.outEndpoint << std::dec
       << " fb=0x" << std::hex << config.feedbackEndpoint << std::dec
       << " explicitFb=" << (config.explicitFeedback ? "yes" : "no")
       << " packets=" << config.packetsPerTransfer
       << " transfers=" << config.transferCount
       << " transferBytes=" << config.transferBytes
       << " queueBytes=" << config.queueBytes
       << " allowSubmit=" << (config.allowOutSubmit ? "yes" : "no")
       << " dynamicPackets=" << (config.dynamicPacketSizing ? "yes" : "no");
    return os.str();
}

std::string describeRawUac20StreamStatus(const RawUac20StreamStatus& status) {
    std::ostringstream os;
    os << "state=" << rawUac20StreamStateName(status.state)
       << " configured=" << (status.configured ? "yes" : "no")
       << " attached=" << (status.attached ? "yes" : "no")
       << " prepared=" << (status.prepared ? "yes" : "no")
       << " streaming=" << (status.streaming ? "yes" : "no")
       << " fmt=" << status.sampleRate << "Hz/" << status.validBits << "bit/"
       << status.channels << "ch/subslot=" << status.subslotBytes
       << " out=0x" << std::hex << status.outEndpoint << std::dec
       << " fb=0x" << std::hex << status.feedbackEndpoint << std::dec
       << " transfers=" << status.transferCount
       << " packets=" << status.packetsPerTransfer
       << " transferBytes=" << status.transferBytes
       << " queueBytes=" << status.queueBytes
       << " prebuffer=" << status.startupPrebufferBytes
       << " fifo=" << status.writeRingLevelBytes << "/" << status.writeRingCapacityBytes
       << " dropped=" << status.writeRingTotalDroppedBytes
       << " eventTicks=" << status.eventLoopTicks
       << " fbActive=" << (status.feedbackActive ? "yes" : "no")
       << " outSubmitted=" << (status.outSubmitted ? "yes" : "no")
       << " outBps=" << status.outCompletedBytesPerSecond << "/" << status.outExpectedBytesPerSecond
       << " ratio=" << status.outCompletionRatio
       << " lastError=" << status.lastError;
    return os.str();
}

} // namespace rawsmusic::usb
