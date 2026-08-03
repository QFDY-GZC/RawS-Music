#include "usb_uac20_transfers.h"

#include "raw_audio_safety.h"

#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <numeric>
#include <sstream>
#include <thread>

#define TAG "RawUac20Transfers"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace rawsmusic::usb {
namespace {

// Step 88: numeric port from the original monolithic C++ engine.
// These constants mirror ISO_PACKETS_PER_XFER=16, MAX_OUT_TRANSFERS=256,
// and the 320ms/180ms queue depth targets that the original engine used.
constexpr int kMaxOutTransfers = 256;
constexpr int kIsoPacketsPerTransfer = 16;
constexpr int kNoFeedbackTargetQueueMs = 320;
constexpr int kFeedbackTargetQueueMs = 180;


const char* packageAdjustModeNameInternal(Uac20PackageAdjustMode mode) {
    switch (mode) {
        case Uac20PackageAdjustMode::Nominal: return "Nominal";
        case Uac20PackageAdjustMode::Conservative: return "Conservative";
        case Uac20PackageAdjustMode::FeedbackGuided: return "FeedbackGuided";
    }
    return "Unknown";
}

std::vector<int> buildPacketPattern(
        int sampleRate,
        int intervalsPerSecond,
        int frameBytes,
        int packetsPerTransfer,
        int endpointMax,
        Uac20PackageAdjustMode mode,
        double feedbackFramesPerMicroframe) {
    std::vector<int> pattern;
    if (sampleRate <= 0 || intervalsPerSecond <= 0 || frameBytes <= 0 || packetsPerTransfer <= 0) {
        return pattern;
    }

    const double nominalFrames = static_cast<double>(sampleRate) /
            static_cast<double>(std::max(1, intervalsPerSecond));
    const double guidedFrames = feedbackFramesPerMicroframe > 0.0
            ? feedbackFramesPerMicroframe
            : nominalFrames;
    const double targetFrames = mode == Uac20PackageAdjustMode::FeedbackGuided
            ? guidedFrames
            : nominalFrames;

    pattern.reserve(static_cast<size_t>(packetsPerTransfer));

    if (mode == Uac20PackageAdjustMode::Nominal) {
        const int frames = std::max(1, static_cast<int>(std::ceil(targetFrames)));
        const int bytes = frames * frameBytes;
        pattern.assign(static_cast<size_t>(packetsPerTransfer), bytes);
        return pattern;
    }

    // Conservative and feedback-guided both use an error-accumulator pattern so
    // fractional rates such as 44.1k avoid the old "always ceil" overfeed.
    // For TP55/192k the pattern remains a stable 24 frames per microframe, but
    // the code path is now ready for feedback-driven packet adjust.
    double accumulator = 0.0;
    int emittedFrames = 0;
    for (int i = 0; i < packetsPerTransfer; ++i) {
        accumulator += targetFrames;
        int frames = static_cast<int>(std::floor(accumulator)) - emittedFrames;
        if (frames <= 0) frames = 1;
        emittedFrames += frames;
        const int bytes = frames * frameBytes;
        pattern.push_back(bytes);
    }
    return pattern;
}

std::string summarizePacketPattern(const std::vector<int>& pattern) {
    if (pattern.empty()) return "[]";
    std::ostringstream os;
    os << "[";
    const size_t limit = std::min<size_t>(pattern.size(), 16);
    for (size_t i = 0; i < limit; ++i) {
        if (i > 0) os << ",";
        os << pattern[i];
    }
    if (pattern.size() > limit) os << ",...";
    os << "]";
    return os.str();
}

int64_t nowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}

int highSpeedIntervalsPerSecond(int interval) {
    // For high-speed USB isochronous endpoints, bInterval is expressed as
    // 2^(bInterval-1) microframes. Most UAC2 DACs use interval=1 -> 8000/s.
    // Keep this conservative for diagnostics; production scheduling can later
    // replace this with bus-speed-aware logic.
    if (interval <= 1) return 8000;
    if (interval > 16) return 1000;
    const int divisor = 1 << std::min(12, interval - 1);
    return std::max(1, 8000 / divisor);
}

int conservativeTransferCount(bool explicitFeedback, int intervalsPerSecond, int packetsPerTransfer) {
    // Step 88: time-based pool sizing from the original engine. 320ms queue for
    // no-feedback / fixed pacer, 180ms for explicit feedback, clamped to 24..256.
    const int ips = std::max(1, intervalsPerSecond);
    const int packets = std::max(1, packetsPerTransfer);
    const int targetQueueMs = explicitFeedback ? kFeedbackTargetQueueMs : kNoFeedbackTargetQueueMs;
    int count = (ips * targetQueueMs + (1000 * packets) - 1) / (1000 * packets);
    return std::clamp(count, 24, kMaxOutTransfers);
}

int conservativePacketsPerTransfer(int intervalsPerSecond) {
    (void) intervalsPerSecond;
    // Step 88: match the original monolithic USB engine: 16 ISO packet
    // descriptors per transfer keeps HS bInterval=1 scheduling accurate while
    // halving callback pressure versus the early v2 smoke path.
    return kIsoPacketsPerTransfer;
}

int sumCompletedIsoBytes(const libusb_transfer* transfer) {
    if (transfer == nullptr) return 0;

    // Step 88: ISO OUT on Android/libusb commonly reports actual_length==0
    // even for a fully completed OUT packet. The original engine treats
    // completion as the scheduled payload being accepted by the host
    // controller; mirror that so diagnostics do not falsely report zero
    // throughput.
    int actualTotal = 0;
    int scheduledCompletedTotal = 0;
    for (int i = 0; i < transfer->num_iso_packets; ++i) {
        const auto& packet = transfer->iso_packet_desc[i];
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

} // namespace

struct Uac20SilentOutSubmitProbe::TransferSlot {
    Uac20SilentOutSubmitProbe* owner = nullptr;
    libusb_transfer* transfer = nullptr;
    std::vector<uint8_t> buffer;
    bool active = false;
    bool submitted = false;
};

const char* uac20PackageAdjustModeName(Uac20PackageAdjustMode mode) {
    return packageAdjustModeNameInternal(mode);
}

bool prepareUac20OutTransferPlan(
        const Uac20AltSnapshot& alt,
        int sampleRate,
        int frameBytes,
        bool explicitFeedback,
        Uac20PackageAdjustMode packageAdjustMode,
        double feedbackFramesPerMicroframe,
        Uac20OutTransferPlan* outPlan) {
    if (outPlan == nullptr) return false;
    *outPlan = Uac20OutTransferPlan{};

    outPlan->endpointAddress = alt.outEndpoint.address;
    outPlan->endpointMaxPacketSize = alt.outEndpoint.maxPacketSize;
    outPlan->endpointInterval = alt.outEndpoint.interval;
    outPlan->sampleRate = sampleRate;
    outPlan->frameBytes = frameBytes;
    outPlan->packageAdjustMode = packageAdjustMode;
    outPlan->feedbackFramesPerMicroframe = feedbackFramesPerMicroframe;

    if (!alt.hasOutEndpoint || alt.outEndpoint.address == 0) {
        outPlan->lastError = "selected alt has no OUT endpoint";
        return false;
    }
    if (!alt.outEndpoint.isIsochronous) {
        outPlan->lastError = "selected OUT endpoint is not isochronous";
        return false;
    }
    if (sampleRate <= 0 || frameBytes <= 0) {
        outPlan->lastError = "invalid sampleRate/frameBytes for OUT plan";
        return false;
    }

    const int intervalsPerSecond = highSpeedIntervalsPerSecond(alt.outEndpoint.interval);
    outPlan->intervalsPerSecond = intervalsPerSecond;

    const double framesPerPacketExact = packageAdjustMode == Uac20PackageAdjustMode::FeedbackGuided
            && feedbackFramesPerMicroframe > 0.0
            ? feedbackFramesPerMicroframe
            : static_cast<double>(sampleRate) / static_cast<double>(std::max(1, intervalsPerSecond));
    const int nominalFrames = std::max(1, static_cast<int>(std::ceil(framesPerPacketExact)));
    const int nominalBytes = nominalFrames * frameBytes;
    const int endpointMax = alt.outEndpoint.maxPacketSize > 0
            ? alt.outEndpoint.maxPacketSize
            : nominalBytes;

    outPlan->targetFramesPerPacket = framesPerPacketExact;
    outPlan->nominalFramesPerPacket = nominalFrames;
    outPlan->nominalPacketBytes = nominalBytes;
    outPlan->maxPacketBytes = endpointMax;
    outPlan->packetsPerTransfer = conservativePacketsPerTransfer(intervalsPerSecond);
    outPlan->transferCount = conservativeTransferCount(
            explicitFeedback,
            intervalsPerSecond,
            outPlan->packetsPerTransfer);

    outPlan->packetBytes = buildPacketPattern(
            sampleRate,
            intervalsPerSecond,
            frameBytes,
            outPlan->packetsPerTransfer,
            endpointMax,
            packageAdjustMode,
            feedbackFramesPerMicroframe);
    if (outPlan->packetBytes.empty()) {
        outPlan->lastError = "computed empty packet pattern";
        return false;
    }
    auto minmax = std::minmax_element(outPlan->packetBytes.begin(), outPlan->packetBytes.end());
    outPlan->minPacketBytes = *minmax.first;
    outPlan->maxPacketBytesInPattern = *minmax.second;
    outPlan->packetPatternSummary = summarizePacketPattern(outPlan->packetBytes);
    if (outPlan->minPacketBytes <= 0) {
        outPlan->lastError = "computed packet bytes <= 0";
        return false;
    }
    if (outPlan->maxPacketBytesInPattern > endpointMax) {
        std::ostringstream os;
        os << "packet pattern max " << outPlan->maxPacketBytesInPattern << " exceeds endpoint max " << endpointMax;
        outPlan->lastError = os.str();
        LOGW("%s", outPlan->lastError.c_str());
        return false;
    }

    outPlan->transferBytes = rawFrameAlignDown(
            std::accumulate(outPlan->packetBytes.begin(), outPlan->packetBytes.end(), 0),
            frameBytes);
    if (outPlan->transferBytes <= 0) {
        outPlan->lastError = "computed transfer bytes <= 0";
        return false;
    }
    outPlan->queueBytes = outPlan->transferBytes * outPlan->transferCount;
    outPlan->prepared = true;
    outPlan->summary = describeUac20OutTransferPlan(*outPlan);

    LOGI("OUT transfer plan: %s", outPlan->summary.c_str());
    return true;
}

std::string describeUac20OutTransferPlan(const Uac20OutTransferPlan& plan) {
    std::ostringstream os;
    os << "prepared=" << (plan.prepared ? 1 : 0)
       << " ep=0x" << std::hex << plan.endpointAddress << std::dec
       << " epMax=" << plan.endpointMaxPacketSize
       << " interval=" << plan.endpointInterval
       << " intervalsPerSec=" << plan.intervalsPerSecond
       << " mode=" << uac20PackageAdjustModeName(plan.packageAdjustMode)
       << " feedbackFpm=" << plan.feedbackFramesPerMicroframe
       << " targetFpp=" << plan.targetFramesPerPacket
       << " sr=" << plan.sampleRate
       << " frameBytes=" << plan.frameBytes
       << " framesPerPacket=" << plan.nominalFramesPerPacket
       << " nominalPacketBytes=" << plan.nominalPacketBytes
       << " minPacketBytes=" << plan.minPacketBytes
       << " maxPatternPacketBytes=" << plan.maxPacketBytesInPattern
       << " pattern=" << plan.packetPatternSummary
       << " packetsPerTransfer=" << plan.packetsPerTransfer
       << " transferCount=" << plan.transferCount
       << " transferBytes=" << plan.transferBytes
       << " queueBytes=" << plan.queueBytes;
    if (!plan.lastError.empty()) {
        os << " error=" << plan.lastError;
    }
    return os.str();
}

Uac20SilentOutSubmitProbe::Uac20SilentOutSubmitProbe() = default;

Uac20SilentOutSubmitProbe::~Uac20SilentOutSubmitProbe() {
    stop("destructor");
}

bool Uac20SilentOutSubmitProbe::run(
        libusb_device_handle* handle,
        const Uac20OutTransferPlan& plan,
        int expectedBytesPerSecond,
        int durationMs) {
    stop("restart");

    std::unique_lock<std::mutex> lock(mutex_);
    stats_ = Uac20OutProbeStats{};
    stats_.attempted = true;
    expectedBytesPerSecond_ = expectedBytesPerSecond;
    stats_.expectedBytesPerSecond = expectedBytesPerSecond;
    handle_ = handle;
    plan_ = plan;
    stopping_ = false;
    active_ = false;
    startTimeMs_ = nowMs();

    if (handle_ == nullptr) {
        stats_.lastError = "silent OUT probe called with null handle";
        return false;
    }
    if (!plan_.prepared) {
        stats_.lastError = "silent OUT probe called without prepared OUT plan";
        return false;
    }
    if (plan_.packetsPerTransfer <= 0 || plan_.transferCount <= 0 || plan_.transferBytes <= 0) {
        stats_.lastError = "silent OUT probe called with invalid transfer plan";
        return false;
    }

    slots_.reserve(static_cast<size_t>(plan_.transferCount));
    for (int i = 0; i < plan_.transferCount; ++i) {
        auto slot = std::make_unique<TransferSlot>();
        slot->owner = this;
        slot->buffer.assign(static_cast<size_t>(plan_.transferBytes), 0);
        slot->transfer = libusb_alloc_transfer(plan_.packetsPerTransfer);
        if (slot->transfer == nullptr) {
            stats_.submitErrorCount += 1;
            stats_.lastError = "libusb_alloc_transfer failed";
            continue;
        }

        libusb_fill_iso_transfer(
                slot->transfer,
                handle_,
                static_cast<unsigned char>(plan_.endpointAddress),
                slot->buffer.data(),
                static_cast<int>(slot->buffer.size()),
                plan_.packetsPerTransfer,
                Uac20SilentOutSubmitProbe::callback,
                slot.get(),
                1000);
        for (int p = 0; p < plan_.packetsPerTransfer; ++p) {
            const int packetBytes = p < static_cast<int>(plan_.packetBytes.size())
                    ? plan_.packetBytes[static_cast<size_t>(p)]
                    : plan_.nominalPacketBytes;
            slot->transfer->iso_packet_desc[p].length = static_cast<unsigned int>(packetBytes);
        }

        const int rc = libusb_submit_transfer(slot->transfer);
        stats_.submitResult = rc;
        if (rc != 0) {
            stats_.submitErrorCount += 1;
            stats_.lastError = std::string("libusb_submit_transfer failed rc=") + std::to_string(rc);
            LOGW("silent OUT submit failed ep=0x%x rc=%d", plan_.endpointAddress, rc);
            libusb_free_transfer(slot->transfer);
            slot->transfer = nullptr;
            continue;
        }

        slot->active = true;
        slot->submitted = true;
        stats_.submitted = true;
        stats_.submittedTransferCount += 1;
        stats_.activeTransferCount += 1;
        stats_.scheduledBytes += plan_.transferBytes;
        slots_.push_back(std::move(slot));
    }

    active_ = stats_.submittedTransferCount > 0;
    if (!active_) {
        if (stats_.lastError.empty()) stats_.lastError = "no OUT transfer submitted";
        return false;
    }

    LOGI("silent OUT probe submitted: %s", describeUac20OutProbeStats(stats_).c_str());

    // Let the session's UAC20 event thread drive completions for a bounded
    // window. This intentionally blocks nativeUac20Start() briefly because v2 is
    // still a diagnostic path and production audio remains on the legacy engine.
    const int boundedDurationMs = std::max(80, std::min(durationMs, 900));
    lock.unlock();
    std::this_thread::sleep_for(std::chrono::milliseconds(boundedDurationMs));
    lock.lock();
    updateRatesLocked();
    lock.unlock();
    stop("silent_out_probe_done");
    lock.lock();
    return stats_.submitted && stats_.completeCount > 0;
}

void Uac20SilentOutSubmitProbe::stop(const char* reason) {
    std::unique_lock<std::mutex> lock(mutex_);
    if (!active_ && slots_.empty()) return;

    stopping_ = true;
    for (const auto& slot : slots_) {
        if (slot && slot->transfer != nullptr && slot->active) {
            stats_.cancelResult = libusb_cancel_transfer(slot->transfer);
            if (stats_.cancelResult == 0) stats_.cancelled = true;
        }
    }
    lock.unlock();

    const int64_t deadline = nowMs() + 160;
    while (nowMs() < deadline) {
        {
            std::lock_guard<std::mutex> checkLock(mutex_);
            bool anyActive = false;
            for (const auto& slot : slots_) {
                anyActive = anyActive || (slot && slot->active);
            }
            if (!anyActive) break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }

    lock.lock();
    updateRatesLocked();
    freeCompletedSlotsLocked();

    // If a vendor USB stack never delivers cancellation callbacks, avoid freeing
    // active transfers. The leaked transfers are tiny and diagnostic-only; this
    // is safer than use-after-free while chasing host-controller issues.
    for (auto& slot : slots_) {
        if (slot && slot->active) {
            slot->owner = nullptr;
            stats_.unreleasedTransferCount += 1;
            (void) slot.release();
        }
    }
    slots_.clear();
    active_ = false;
    handle_ = nullptr;
    LOGI("silent OUT probe stopped reason=%s stats=%s",
         reason ? reason : "null", describeUac20OutProbeStats(stats_).c_str());
}

bool Uac20SilentOutSubmitProbe::active() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return active_;
}

Uac20OutProbeStats Uac20SilentOutSubmitProbe::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    Uac20OutProbeStats copy = stats_;
    const int64_t elapsed = startTimeMs_ > 0 ? std::max<int64_t>(1, nowMs() - startTimeMs_) : 0;
    copy.elapsedMs = static_cast<int>(elapsed);
    if (elapsed > 0) {
        copy.completedBytesPerSecond = static_cast<int>((static_cast<int64_t>(copy.completedBytes) * 1000) / elapsed);
    }
    if (copy.expectedBytesPerSecond > 0) {
        copy.completionRatio = static_cast<double>(copy.completedBytesPerSecond) /
                static_cast<double>(copy.expectedBytesPerSecond);
    }
    copy.active = active_;
    return copy;
}

std::string Uac20SilentOutSubmitProbe::summary() const {
    return describeUac20OutProbeStats(snapshot());
}

void LIBUSB_CALL Uac20SilentOutSubmitProbe::callback(libusb_transfer* transfer) {
    auto* slot = static_cast<TransferSlot*>(transfer ? transfer->user_data : nullptr);
    if (slot == nullptr || slot->owner == nullptr) return;
    slot->owner->onCallback(slot, transfer);
}

void Uac20SilentOutSubmitProbe::onCallback(TransferSlot* slot, libusb_transfer* transfer) {
    if (slot == nullptr || transfer == nullptr) return;

    std::lock_guard<std::mutex> lock(mutex_);
    stats_.transferStatus = static_cast<int>(transfer->status);

    if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        const int completed = sumCompletedIsoBytes(transfer);
        stats_.completeCount += 1;
        stats_.completedBytes += completed;
        updateRatesLocked();

        if (!stopping_) {
            for (int p = 0; p < plan_.packetsPerTransfer; ++p) {
                const int packetBytes = p < static_cast<int>(plan_.packetBytes.size())
                        ? plan_.packetBytes[static_cast<size_t>(p)]
                        : plan_.nominalPacketBytes;
                transfer->iso_packet_desc[p].length = static_cast<unsigned int>(packetBytes);
            }
            const int rc = libusb_submit_transfer(transfer);
            if (rc == 0) {
                stats_.resubmitCount += 1;
                stats_.scheduledBytes += plan_.transferBytes;
                slot->active = true;
                return;
            }
            stats_.submitErrorCount += 1;
            stats_.lastError = std::string("OUT resubmit failed rc=") + std::to_string(rc);
            LOGW("silent OUT resubmit failed ep=0x%x rc=%d", plan_.endpointAddress, rc);
        }
        slot->active = false;
    } else if (transfer->status == LIBUSB_TRANSFER_CANCELLED) {
        stats_.cancelled = true;
        slot->active = false;
    } else {
        stats_.errorCount += 1;
        slot->active = false;
        std::ostringstream os;
        os << "OUT transfer status=" << static_cast<int>(transfer->status);
        stats_.lastError = os.str();
        LOGW("silent OUT callback status ep=0x%x status=%d actual=%d",
             transfer->endpoint, static_cast<int>(transfer->status), transfer->actual_length);
    }

    int activeCount = 0;
    for (const auto& s : slots_) {
        if (s && s->active) activeCount += 1;
    }
    stats_.activeTransferCount = activeCount;
    active_ = activeCount > 0;
}

void Uac20SilentOutSubmitProbe::freeCompletedSlotsLocked() {
    for (auto& slot : slots_) {
        if (slot && !slot->active && slot->transfer != nullptr) {
            libusb_free_transfer(slot->transfer);
            slot->transfer = nullptr;
        }
    }
}

void Uac20SilentOutSubmitProbe::updateRatesLocked() {
    const int64_t elapsed = startTimeMs_ > 0 ? std::max<int64_t>(1, nowMs() - startTimeMs_) : 0;
    stats_.elapsedMs = static_cast<int>(elapsed);
    if (elapsed > 0) {
        stats_.completedBytesPerSecond = static_cast<int>((static_cast<int64_t>(stats_.completedBytes) * 1000) / elapsed);
    }
    if (stats_.expectedBytesPerSecond > 0) {
        stats_.completionRatio = static_cast<double>(stats_.completedBytesPerSecond) /
                static_cast<double>(stats_.expectedBytesPerSecond);
    }
}

std::string describeUac20OutProbeStats(const Uac20OutProbeStats& stats) {
    std::ostringstream os;
    os << "attempted=" << (stats.attempted ? 1 : 0)
       << " submitted=" << (stats.submitted ? 1 : 0)
       << " active=" << (stats.active ? 1 : 0)
       << " cancelled=" << (stats.cancelled ? 1 : 0)
       << " submitRc=" << stats.submitResult
       << " cancelRc=" << stats.cancelResult
       << " status=" << stats.transferStatus
       << " submittedTransfers=" << stats.submittedTransferCount
       << " activeTransfers=" << stats.activeTransferCount
       << " completeCount=" << stats.completeCount
       << " resubmitCount=" << stats.resubmitCount
       << " errors=" << stats.errorCount
       << " submitErrors=" << stats.submitErrorCount
       << " scheduled=" << stats.scheduledBytes
       << " completed=" << stats.completedBytes
       << " completedBps=" << stats.completedBytesPerSecond
       << " expectedBps=" << stats.expectedBytesPerSecond
       << " ratio=" << stats.completionRatio
       << " elapsedMs=" << stats.elapsedMs
       << " unreleased=" << stats.unreleasedTransferCount;
    if (!stats.lastError.empty()) {
        os << " error=" << stats.lastError;
    }
    return os.str();
}

} // namespace rawsmusic::usb
