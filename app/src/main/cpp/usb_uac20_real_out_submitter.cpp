#include "usb_uac20_real_out_submitter.h"

#include <algorithm>
#include <chrono>
#include <climits>
#include <cmath>
#include <cstring>
#include <sstream>

namespace rawsmusic::usb {
namespace {
constexpr int kDefaultTimeoutMs = 1000;
constexpr double kQ32Scale = 4294967296.0;

int frameAlignedMaxPayload(int endpointMaxPacketSize, int frameBytes) {
    const int frame = std::max(1, frameBytes);
    if (endpointMaxPacketSize <= 0) return INT_MAX;
    const int aligned = (endpointMaxPacketSize / frame) * frame;
    return aligned > 0 ? aligned : frame;
}

int nominalCeilPacketBytes(int sampleRate, int intervalsPerSecond, int frameBytes, int endpointMaxPacketSize) {
    const int sr = std::max(1, sampleRate);
    const int ips = std::max(1, intervalsPerSecond);
    const int frame = std::max(1, frameBytes);
    // Step 88: original engine keeps one extra frame of headroom for fractional
    // 44.1k / 88.2k accumulator rounding, then clamps to the endpoint's
    // frame-aligned max.
    int frames = (sr + ips - 1) / ips;
    frames += 1;
    int bytes = std::max(frame, frames * frame);
    bytes = std::min(bytes, frameAlignedMaxPayload(endpointMaxPacketSize, frame));
    return std::max(frame, (bytes / frame) * frame);
}
}

Uac20RealOutSubmitter::Uac20RealOutSubmitter() = default;

Uac20RealOutSubmitter::~Uac20RealOutSubmitter() {
    cancelAndRelease("destructor");
}

int64_t Uac20RealOutSubmitter::nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

bool Uac20RealOutSubmitter::prepare(const Uac20RealOutSubmitterConfig& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    freeTransfersLocked(true);
    submittedInFlightCount_ = 0;
    dynamicAccumulatorQ32_ = 0;
    dynamicSampleRateQ32_ = 0;
    dynamicMaxPacketBytes_ = 0;
    config_ = config;
    stats_ = Uac20RealOutSubmitterStats{};
    stats_.initialized = config.endpointAddress > 0 && config.transferCount > 0 &&
            config.packetsPerTransfer > 0 && config.transferBytes > 0;
    stats_.submissionEnabled = config.submissionEnabled;
    stats_.zeroFill = config.zeroFill;
    stats_.debugSmokeTest = config.debugSmokeTest;
    stats_.autoResubmit = config.autoResubmit;
    stats_.feedFromWriteRing = config.feedFromWriteRing;
    stats_.endpointAddress = config.endpointAddress;
    stats_.transferCount = config.transferCount;
    stats_.packetsPerTransfer = config.packetsPerTransfer;
    stats_.transferBytes = config.transferBytes;
    stats_.endpointMaxPacketSize = config.endpointMaxPacketSize;
    stats_.queueBytes = config.transferBytes * config.transferCount;
    stats_.maxCallbacks = config.maxCallbacks;
    stats_.maxRunMs = config.maxRunMs;
    stats_.cancelWaitMs = config.cancelWaitMs;
    stats_.expectedBytesPerSecond = config.expectedBytesPerSecond;
    stats_.layoutValid = false;
    stats_.layoutMismatch = false;
    stats_.packetLengthTotal = 0;
    stats_.packetLengthMin = 0;
    stats_.packetLengthMax = 0;
    stats_.zeroLengthPacketCount = 0;
    stats_.releaseComplete = false;
    stats_.releaseDeferred = false;
    stats_.lastError.clear();
    if (!stats_.initialized) {
        stats_.lastError = "invalid-submit-config";
        stats_.layoutError = "endpoint/transfer/packet config missing";
        stats_.summary = describeUac20RealOutSubmitterStats(stats_);
        return false;
    }
    if (config.dynamicPacketSizing && config.sampleRate > 0 && config.frameBytes > 0 &&
            config.intervalsPerSecond > 0) {
        const int frame = std::max(1, config.frameBytes);
        const int ips = std::max(1, config.intervalsPerSecond);
        const int minFrames = std::max(1, config.sampleRate / ips);
        const int minBytes = minFrames * frame;
        const int maxBytes = nominalCeilPacketBytes(
                config.sampleRate,
                config.intervalsPerSecond,
                config.frameBytes,
                config.endpointMaxPacketSize);
        const int maxTransferBytes = maxBytes * config.packetsPerTransfer;
        dynamicSampleRateQ32_ = static_cast<uint64_t>(std::llround(
                static_cast<double>(config.sampleRate) * kQ32Scale));
        dynamicAccumulatorQ32_ = 0;
        dynamicMaxPacketBytes_ = maxBytes;
        stats_.packetLengthTotal = 0; // dynamic per submit; see last transfer->length
        stats_.packetLengthMin = minBytes;
        stats_.packetLengthMax = maxBytes;
        stats_.zeroLengthPacketCount = 0;
        stats_.layoutMismatch = config.transferBytes < maxTransferBytes;
        if (minBytes <= 0 || maxBytes <= 0) stats_.layoutError = "zero-dynamic-iso-packet";
        else if ((maxBytes % frame) != 0) stats_.layoutError = "dynamic-packet-not-frame-aligned";
        else if (config.endpointMaxPacketSize > 0 && maxBytes > frameAlignedMaxPayload(config.endpointMaxPacketSize, frame)) stats_.layoutError = "dynamic-packet-exceeds-endpoint-max";
        else if (config.transferBytes < maxTransferBytes) stats_.layoutError = "dynamic-transfer-buffer-too-small";
        else stats_.layoutValid = true;
    } else if (!config.packetBytes.empty()) {
        int total = 0;
        int minBytes = 0;
        int maxBytes = 0;
        int zeros = 0;
        int unaligned = 0;
        for (int p = 0; p < config.packetsPerTransfer; ++p) {
            const int bytes = std::max(0, config.packetBytes[static_cast<size_t>(p % config.packetBytes.size())]);
            total += bytes;
            if (p == 0 || bytes < minBytes) minBytes = bytes;
            if (bytes > maxBytes) maxBytes = bytes;
            if (bytes <= 0) ++zeros;
            if (config.frameBytes > 0 && (bytes % config.frameBytes) != 0) ++unaligned;
        }
        stats_.packetLengthTotal = total;
        stats_.packetLengthMin = minBytes;
        stats_.packetLengthMax = maxBytes;
        stats_.zeroLengthPacketCount = zeros;
        stats_.layoutMismatch = total > config.transferBytes;
        if (zeros > 0) stats_.layoutError = "zero-length-iso-packet";
        else if (unaligned > 0) stats_.layoutError = "iso-packet-not-frame-aligned";
        else if (config.endpointMaxPacketSize > 0 && maxBytes > config.endpointMaxPacketSize) stats_.layoutError = "packet-exceeds-endpoint-max";
        else if (total <= 0) stats_.layoutError = "zero-transfer-length";
        else if (total > config.transferBytes) stats_.layoutError = "packet-pattern-exceeds-transfer-buffer";
        else stats_.layoutValid = true;
    } else {
        const int perPacket = config.packetsPerTransfer > 0 ? config.transferBytes / config.packetsPerTransfer : 0;
        stats_.packetLengthTotal = config.transferBytes;
        stats_.packetLengthMin = perPacket;
        stats_.packetLengthMax = perPacket;
        stats_.zeroLengthPacketCount = perPacket <= 0 ? config.packetsPerTransfer : 0;
        stats_.layoutMismatch = false;
        if (perPacket <= 0) stats_.layoutError = "zero-default-iso-packet";
        else if (config.frameBytes > 0 && (perPacket % config.frameBytes) != 0) stats_.layoutError = "default-iso-packet-not-frame-aligned";
        else if (config.endpointMaxPacketSize > 0 && perPacket > config.endpointMaxPacketSize) stats_.layoutError = "default-packet-exceeds-endpoint-max";
        else stats_.layoutValid = true;
    }
    if (!stats_.layoutValid) {
        stats_.lastError = stats_.layoutError.empty() ? "invalid-transfer-layout" : stats_.layoutError;
        stats_.summary = describeUac20RealOutSubmitterStats(stats_);
        return false;
    }
    transferBuffers_.resize(static_cast<size_t>(stats_.queueBytes));
    std::fill(transferBuffers_.begin(), transferBuffers_.end(), 0);
    stats_.allocated = true;
    stats_.summary = describeUac20RealOutSubmitterStats(stats_);
    return true;
}

bool Uac20RealOutSubmitter::allocateTransfersLocked(libusb_device_handle* handle) {
    if (handle == nullptr) {
        stats_.lastError = "null-handle";
        return false;
    }
    // 0086: allocateTransfersLocked() is called from startDebugFeeder() after
    // writeRing_ has been bound. The old freeTransfersLocked() always cleared
    // writeRing_, so refillTransferLocked() silently fell back to zero-fill even
    // while the native write ring was full and dropping decoded PCM. Preserve the
    // feeder binding across allocation/reallocation, and only clear it on close
    // or explicit zero-submit.
    freeTransfersLocked(false);
    transferBuffers_.resize(static_cast<size_t>(stats_.queueBytes));
    transfers_.reserve(static_cast<size_t>(config_.transferCount));
    transferActive_.assign(static_cast<size_t>(config_.transferCount), false);
    const int timeout = config_.timeoutMs > 0 ? config_.timeoutMs : kDefaultTimeoutMs;
    for (int i = 0; i < config_.transferCount; ++i) {
        libusb_transfer* transfer = libusb_alloc_transfer(config_.packetsPerTransfer);
        if (transfer == nullptr) {
            stats_.allocationErrorCount++;
            stats_.lastError = "alloc-failed";
            freeTransfersLocked(false);
            return false;
        }
        const int offset = i * config_.transferBytes;
        auto* buffer = transferBuffers_.data() + offset;
        std::memset(buffer, 0, static_cast<size_t>(config_.transferBytes));
        libusb_fill_iso_transfer(
                transfer,
                handle,
                static_cast<uint8_t>(config_.endpointAddress),
                buffer,
                config_.transferBytes,
                config_.packetsPerTransfer,
                &Uac20RealOutSubmitter::callback,
                this,
                static_cast<unsigned int>(timeout));
        if (config_.dynamicPacketSizing || !config_.packetBytes.empty()) {
            applyPacketLengthsLocked(transfer);
        } else {
            libusb_set_iso_packet_lengths(transfer, config_.transferBytes / config_.packetsPerTransfer);
        }
        if (!refillTransferLocked(transfer)) {
            libusb_free_transfer(transfer);
            freeTransfersLocked(false);
            return false;
        }
        transfers_.push_back(transfer);
    }
    stats_.allocated = true;
    stats_.allocatedTransferCount = static_cast<int>(transfers_.size());
    return true;
}

bool Uac20RealOutSubmitter::refillTransferLocked(libusb_transfer* transfer) {
    if (transfer == nullptr || transfer->buffer == nullptr || transfer->length <= 0) return false;
    stats_.lastFedBytes = 0;
    stats_.lastZeroFilledBytes = 0;

    int filled = 0;
    if (config_.feedFromWriteRing && writeRing_ != nullptr) {
        const int read = writeRing_->read(transfer->buffer, transfer->length);
        if (read < 0) {
            stats_.lastError = std::string("write-ring-read=") + std::to_string(read);
            return false;
        }
        filled = read;
        stats_.fedBytes += read;
        stats_.lastFedBytes = read;
        if (read < transfer->length) stats_.feederUnderrunCount++;
    }

    const int missing = std::max(0, transfer->length - filled);
    if (missing > 0) {
        if (!config_.zeroFill) {
            stats_.lastError = "real-out-feeder-underrun-without-zero-fill";
            return false;
        }
        std::memset(transfer->buffer + filled, 0, static_cast<size_t>(missing));
        stats_.zeroFilledBytes += missing;
        stats_.lastZeroFilledBytes = missing;
    }
    return true;
}

bool Uac20RealOutSubmitter::startZeroSubmit(libusb_device_handle* handle) {
    std::lock_guard<std::mutex> lock(mutex_);
    writeRing_ = nullptr;
    return startLocked(handle, nullptr);
}

bool Uac20RealOutSubmitter::startDebugFeeder(libusb_device_handle* handle, Uac20WriteRing* writeRing) {
    std::lock_guard<std::mutex> lock(mutex_);
    writeRing_ = writeRing;
    return startLocked(handle, writeRing);
}

bool Uac20RealOutSubmitter::startLocked(libusb_device_handle* handle, Uac20WriteRing*) {
    stats_.attempted = true;
    startTimeMs_ = nowMs();
    if (!stats_.initialized) {
        stats_.lastError = "start before prepare";
        stats_.summary = describeUac20RealOutSubmitterStats(stats_);
        return false;
    }
    if (!stats_.submissionEnabled) {
        stats_.dryRunBlockedSubmit = true;
        stats_.summary = describeUac20RealOutSubmitterStats(stats_);
        return false;
    }
    if (config_.feedFromWriteRing && writeRing_ == nullptr) {
        stats_.lastError = "feedFromWriteRing without ring";
        stats_.summary = describeUac20RealOutSubmitterStats(stats_);
        return false;
    }
    if (!allocateTransfersLocked(handle)) {
        stats_.summary = describeUac20RealOutSubmitterStats(stats_);
        return false;
    }

    int submittedCount = 0;
    for (size_t i = 0; i < transfers_.size(); ++i) {
        const int rc = libusb_submit_transfer(transfers_[i]);
        stats_.lastSubmitResult = rc;
        if (rc == LIBUSB_SUCCESS) {
            submittedCount++;
            stats_.submitOkCount++;
            const int now = startTimeMs_ > 0 ? std::max(1, static_cast<int>(nowMs() - startTimeMs_)) : 0;
            if (stats_.firstSubmitMs <= 0) stats_.firstSubmitMs = now;
            stats_.lastSubmitMs = now;
            if (i < transferActive_.size()) transferActive_[i] = true;
            submittedInFlightCount_++;
            stats_.submittedBytes += transfers_[i]->length;
        } else {
            stats_.submitErrorCount++;
            stats_.submitFailCount++;
            stats_.lastError = std::string("submit=") + std::to_string(rc);
        }
    }
    stats_.submitted = submittedCount > 0;
    stats_.submittedTransferCount = submittedCount;
    stats_.active = submittedCount > 0;
    stats_.summary = describeUac20RealOutSubmitterStats(stats_);
    return stats_.submitted && stats_.submitErrorCount == 0;
}

void Uac20RealOutSubmitter::applyPacketLengthsLocked(libusb_transfer* transfer) {
    if (transfer == nullptr) return;
    if (config_.dynamicPacketSizing && config_.sampleRate > 0 && config_.frameBytes > 0 &&
            config_.intervalsPerSecond > 0) {
        int total = 0;
        for (int p = 0; p < config_.packetsPerTransfer; ++p) {
            const int bytes = nextDynamicPacketBytesLocked();
            transfer->iso_packet_desc[p].length = static_cast<unsigned int>(bytes);
            total += bytes;
        }
        transfer->length = total > 0 ? total : config_.transferBytes;
        stats_.packetLengthTotal = transfer->length;
        return;
    }
    if (!config_.packetBytes.empty()) {
        int total = 0;
        for (int p = 0; p < config_.packetsPerTransfer; ++p) {
            const int bytes = config_.packetBytes[static_cast<size_t>(p % config_.packetBytes.size())];
            const int clamped = std::max(0, bytes);
            transfer->iso_packet_desc[p].length = clamped;
            total += clamped;
        }
        transfer->length = total > 0 ? total : config_.transferBytes;
    } else {
        transfer->length = config_.transferBytes;
        libusb_set_iso_packet_lengths(transfer, config_.transferBytes / config_.packetsPerTransfer);
    }
}

int Uac20RealOutSubmitter::nextDynamicPacketBytesLocked() {
    const int frame = std::max(1, config_.frameBytes);
    const uint64_t rateQ32 = dynamicSampleRateQ32_ != 0
            ? dynamicSampleRateQ32_
            : static_cast<uint64_t>(std::llround(
                    static_cast<double>(std::max(1, config_.sampleRate)) * kQ32Scale));
    const uint64_t intervalQ32 = static_cast<uint64_t>(std::max(1, config_.intervalsPerSecond)) << 32;

    dynamicAccumulatorQ32_ += rateQ32;
    uint64_t framesThisInterval = dynamicAccumulatorQ32_ / intervalQ32;
    dynamicAccumulatorQ32_ -= framesThisInterval * intervalQ32;

    if (framesThisInterval == 0) framesThisInterval = 1;
    int bytes = static_cast<int>(framesThisInterval * static_cast<uint64_t>(frame));

    const int maxPayload = frameAlignedMaxPayload(config_.endpointMaxPacketSize, frame);
    const int maxNominal = dynamicMaxPacketBytes_ > 0
            ? dynamicMaxPacketBytes_
            : nominalCeilPacketBytes(config_.sampleRate, config_.intervalsPerSecond, frame, config_.endpointMaxPacketSize);
    const int limit = std::min(maxPayload, maxNominal);
    if (limit > 0 && bytes > limit) {
        bytes = limit;
        // Same defensive reset as the original engine: one corrupted/stale
        // accumulator state must not keep producing endpoint-full packets.
        dynamicAccumulatorQ32_ = 0;
    }
    bytes = (bytes / frame) * frame;
    return std::max(frame, bytes);
}

int Uac20RealOutSubmitter::completedLengthLocked(const libusb_transfer* transfer) const {
    if (transfer == nullptr) return 0;

    // For ISO OUT, many Android/libusb controller paths report actual_length==0
    // even when the transfer and all packets complete successfully. Completion
    // means the scheduled OUT payload was accepted by the host controller, so
    // throughput diagnostics must fall back to the scheduled packet length.
    int actualTotal = 0;
    int scheduledCompletedTotal = 0;
    for (int p = 0; p < transfer->num_iso_packets; ++p) {
        const auto& packet = transfer->iso_packet_desc[p];
        if (packet.status == LIBUSB_TRANSFER_COMPLETED) {
            actualTotal += std::max(0, static_cast<int>(packet.actual_length));
            scheduledCompletedTotal += std::max(0, static_cast<int>(packet.length));
        }
    }
    if (actualTotal > 0) return actualTotal;
    if (scheduledCompletedTotal > 0) return scheduledCompletedTotal;
    if (transfer->actual_length > 0) return std::max(0, transfer->actual_length);
    return std::max(0, transfer->length);
}

bool Uac20RealOutSubmitter::shouldResubmitLocked() const {
    if (!config_.autoResubmit || stats_.cancelRequested || !stats_.active) return false;
    if (config_.maxCallbacks > 0 && stats_.callbackCount >= config_.maxCallbacks) return false;
    if (config_.maxRunMs > 0 && startTimeMs_ > 0) {
        const int elapsed = static_cast<int>(nowMs() - startTimeMs_);
        if (elapsed >= config_.maxRunMs) return false;
    }
    return true;
}

void Uac20RealOutSubmitter::cancelAndRelease(const char* reason) {
    std::lock_guard<std::mutex> lock(mutex_);
    stats_.cancelRequested = true;
    stats_.cleanStopRequested = true;
    stats_.cancelCalls++;
    if (reason) stats_.lastStopReason = reason;

    // Count active transfers before cancel
    stats_.activeTransferCount = 0;
    for (size_t i = 0; i < transfers_.size(); ++i) {
        if (transfers_[i] != nullptr && submittedInFlightCount_ > 0) stats_.activeTransferCount++;
    }
    if (stats_.activeTransferCount == 0 && submittedInFlightCount_ > 0) {
        stats_.activeTransferCount = std::min(static_cast<int>(transfers_.size()), submittedInFlightCount_);
    }

    if (stats_.active && stats_.submitted) {
        const int64_t cancelStart = nowMs();
        for (size_t i = 0; i < transfers_.size(); ++i) {
            if (transfers_[i] != nullptr) {
                const bool bitmapActive = i < transferActive_.size() && transferActive_[i];
                const bool inFlight = submittedInFlightCount_ > 0;
                if (!bitmapActive && !inFlight) continue;
                const int rc = libusb_cancel_transfer(transfers_[i]);
                if (rc == LIBUSB_SUCCESS) {
                    stats_.cancelSubmitCount++;
                }
            }
        }
        // Brief wait for cancel callbacks (non-blocking, just measure)
        stats_.cancelWaitMs = static_cast<int>(nowMs() - cancelStart);

        // Count pending transfers after cancel
        stats_.pendingAfterCancel = 0;
        for (size_t i = 0; i < transfers_.size(); ++i) {
            if (transfers_[i] != nullptr) stats_.pendingAfterCancel++;
        }
    }

    if (stats_.pendingAfterCancel == 0 && (!stats_.active || !stats_.submitted)) {
        freeTransfersLocked(true);
        stats_.releaseComplete = true;
        stats_.releaseDeferred = false;
    } else if (stats_.pendingAfterCancel > 0) {
        // Defer release to avoid use-after-free; caller must retry
        stats_.releaseDeferred = true;
        stats_.releaseComplete = false;
        stats_.active = false;
    } else {
        freeTransfersLocked(true);
        stats_.releaseComplete = true;
        stats_.releaseDeferred = false;
    }
    stats_.summary = describeUac20RealOutSubmitterStats(stats_);
}

Uac20RealOutSubmitterStats Uac20RealOutSubmitter::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    Uac20RealOutSubmitterStats copy = stats_;
    const int64_t now = nowMs();
    if (startTimeMs_ > 0) copy.elapsedMs = static_cast<int>(now - startTimeMs_);

    // 0084: make no-completion diagnostics reliable. 0076/0080 added the
    // fields, but the live snapshot in some trees never recomputed them, which
    // left realOutSubmitterNoCompletionMs stuck at 0 even after submitOkCount>0
    // and callbackCount==0 for five seconds. Kotlin fast-fail depends on these
    // live values, so derive them from the logical in-flight counter every time.
    const int logicalInFlight = std::max(0, submittedInFlightCount_);
    if (logicalInFlight > copy.activeTransferCount) {
        copy.activeTransferCount = logicalInFlight;
    }
    if (copy.submitted && copy.submitOkCount > 0 && copy.callbackCount <= 0 && !copy.cancelRequested) {
        copy.activeTransferCount = std::max(copy.activeTransferCount, copy.submitOkCount);
        copy.active = true;
    }
    if (copy.elapsedMs > 0) {
        copy.completedBytesPerSecond = static_cast<int>(
                (copy.completedBytes * 1000LL) / std::max(1, copy.elapsedMs));
    }
    if (copy.expectedBytesPerSecond > 0) {
        copy.completionRatio = static_cast<double>(copy.completedBytesPerSecond) /
                static_cast<double>(copy.expectedBytesPerSecond);
    }
    if (copy.submitted && copy.completeCount <= 0 && copy.firstSubmitMs > 0 && copy.elapsedMs > copy.firstSubmitMs) {
        copy.noCompletionMs = copy.elapsedMs - copy.firstSubmitMs;
    } else if (copy.submitOkCount > 0 && copy.callbackCount <= 0 && copy.elapsedMs > 0) {
        copy.noCompletionMs = copy.elapsedMs;
    }
    copy.summary = describeUac20RealOutSubmitterStats(copy);
    return copy;
}

std::string Uac20RealOutSubmitter::summary() const {
    return describeUac20RealOutSubmitterStats(snapshot());
}

void LIBUSB_CALL Uac20RealOutSubmitter::callback(libusb_transfer* transfer) {
    if (transfer == nullptr || transfer->user_data == nullptr) return;
    auto* self = static_cast<Uac20RealOutSubmitter*>(transfer->user_data);
    self->onCallback(transfer);
}

void Uac20RealOutSubmitter::onCallback(libusb_transfer* transfer) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (submittedInFlightCount_ > 0) submittedInFlightCount_--;
    stats_.callbackCount++;
    size_t transferIndex = SIZE_MAX;
    for (size_t i = 0; i < transfers_.size(); ++i) {
        if (transfers_[i] == transfer) { transferIndex = i; break; }
    }
    if (transferIndex < transferActive_.size()) transferActive_[transferIndex] = false;
    const int callbackMs = startTimeMs_ > 0 ? std::max(1, static_cast<int>(nowMs() - startTimeMs_)) : 0;
    if (stats_.firstCallbackMs <= 0) stats_.firstCallbackMs = callbackMs;
    stats_.lastCallbackMs = callbackMs;
    stats_.lastTransferStatus = transfer->status;
    if (transfer->num_iso_packets > 0) {
        stats_.lastIsoPacketStatus = transfer->iso_packet_desc[0].status;
        stats_.lastIsoActualLength = transfer->iso_packet_desc[0].actual_length;
        stats_.lastIsoPacketLength = transfer->iso_packet_desc[0].length;
    }
    if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        stats_.completeCount++;
        stats_.completedBytes += completedLengthLocked(transfer);
    } else if (transfer->status == LIBUSB_TRANSFER_CANCELLED) {
        stats_.cancelledCount++;
    } else {
        stats_.transferErrorCount++;
        stats_.lastError = std::string("transfer-status=") + std::to_string(static_cast<int>(transfer->status));
    }

    if (transfer->status == LIBUSB_TRANSFER_COMPLETED && shouldResubmitLocked()) {
        applyPacketLengthsLocked(transfer);
        if (refillTransferLocked(transfer)) {
            const int r = libusb_submit_transfer(transfer);
            stats_.lastSubmitResult = r;
            if (r == LIBUSB_SUCCESS) {
                if (transferIndex < transferActive_.size()) transferActive_[transferIndex] = true;
                submittedInFlightCount_++;
                stats_.submitOkCount++;
                stats_.lastSubmitMs = startTimeMs_ > 0 ? std::max(1, static_cast<int>(nowMs() - startTimeMs_)) : 0;
                stats_.resubmitCount++;
                stats_.submittedBytes += transfer->length;
                stats_.summary = describeUac20RealOutSubmitterStats(stats_);
                return;
            }
            stats_.submitErrorCount++;
            stats_.submitFailCount++;
            stats_.lastError = std::string("resubmit=") + std::to_string(r);
        } else {
            stats_.submitErrorCount++;
        }
    }

    if (!shouldResubmitLocked()) stats_.budgetExpired = config_.autoResubmit && !stats_.cancelRequested;
    if (stats_.cancelRequested || transfer->status == LIBUSB_TRANSFER_CANCELLED || !config_.autoResubmit) {
        stats_.active = false;
    }
    stats_.summary = describeUac20RealOutSubmitterStats(stats_);
}

void Uac20RealOutSubmitter::freeTransfersLocked(bool clearFeeder) {
    for (size_t i = 0; i < transfers_.size(); ++i) {
        if (transfers_[i] != nullptr) {
            libusb_free_transfer(transfers_[i]);
        }
    }
    transfers_.clear();
    transferBuffers_.clear();
    transferActive_.clear();
    transferActive_.assign(static_cast<size_t>(config_.transferCount), false);
    stats_.allocated = false;
    stats_.allocatedTransferCount = 0;
    if (clearFeeder) {
        writeRing_ = nullptr;
    }
}

std::string describeUac20RealOutSubmitterStats(const Uac20RealOutSubmitterStats& s) {
    std::ostringstream os;
    os << "initialized=" << (s.initialized ? "yes" : "no")
       << " allocated=" << (s.allocated ? "yes" : "no")
       << " submitGate=" << (s.submissionEnabled ? "enabled" : "disabled")
       << " dryRunBlocked=" << (s.dryRunBlockedSubmit ? "yes" : "no")
       << " zeroFill=" << (s.zeroFill ? "yes" : "no")
       << " debugSmoke=" << (s.debugSmokeTest ? "yes" : "no")
       << " autoResubmit=" << (s.autoResubmit ? "yes" : "no")
       << " feedRing=" << (s.feedFromWriteRing ? "yes" : "no")
       << " budgetExpired=" << (s.budgetExpired ? "yes" : "no")
       << " layoutValid=" << (s.layoutValid ? "yes" : "no")
       << " layoutMismatch=" << (s.layoutMismatch ? "yes" : "no")
       << " ep=0x" << std::hex << s.endpointAddress << std::dec
       << " transfers=" << s.transferCount
       << " allocatedTransfers=" << s.allocatedTransferCount
       << " submittedTransfers=" << s.submittedTransferCount
       << " packetsPerTransfer=" << s.packetsPerTransfer
       << " transferBytes=" << s.transferBytes
       << " queueBytes=" << s.queueBytes
       << " callbacks=" << s.callbackCount
       << " complete=" << s.completeCount
       << " resubmit=" << s.resubmitCount
       << " errors=" << s.transferErrorCount
       << " submitErrors=" << s.submitErrorCount
       << " cancelSubmit=" << s.cancelSubmitCount
       << " cancelled=" << s.cancelledCount
       << " feederUnderruns=" << s.feederUnderrunCount
       << " submittedBytes=" << s.submittedBytes
       << " completedBytes=" << s.completedBytes
       << " fedBytes=" << s.fedBytes
       << " zeroFilledBytes=" << s.zeroFilledBytes
       << " lastFed=" << s.lastFedBytes
       << " lastZero=" << s.lastZeroFilledBytes
       << " lastSubmit=" << s.lastSubmitResult
       << " lastCancel=" << s.lastCancelResult
       << " lastStatus=" << s.lastTransferStatus
       << " firstSubmitMs=" << s.firstSubmitMs
       << " firstCallbackMs=" << s.firstCallbackMs
       << " noCompletionMs=" << s.noCompletionMs
       << " completedBps=" << s.completedBytesPerSecond
       << " expectedBps=" << s.expectedBytesPerSecond
       << " ratio=" << s.completionRatio
       << " elapsedMs=" << s.elapsedMs
       << " maxCallbacks=" << s.maxCallbacks
       << " maxRunMs=" << s.maxRunMs
       << " cleanStop=" << (s.cleanStopRequested ? "yes" : "no")
       << " releaseComplete=" << (s.releaseComplete ? "yes" : "no")
       << " releaseDeferred=" << (s.releaseDeferred ? "yes" : "no")
       << " activeTransfers=" << s.activeTransferCount
       << " pendingAfterCancel=" << s.pendingAfterCancel
       << " cancelWaitMs=" << s.cancelWaitMs
       << " cancelCalls=" << s.cancelCalls;
    if (!s.lastError.empty()) os << " error=" << s.lastError;
    if (!s.lastStopReason.empty()) os << " stopReason=" << s.lastStopReason;
    return os.str();
}

} // namespace rawsmusic::usb
