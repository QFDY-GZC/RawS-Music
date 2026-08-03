#include "usb_uac20_feedback.h"

#include <algorithm>
#include <android/log.h>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstdio>
#include <memory>
#include <sstream>
#include <thread>
#include <sys/time.h>

#include "libusb.h"

#define TAG "RawUac20Feedback"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace rawsmusic::usb {
namespace {

struct FeedbackProbeContext {
    Uac20FeedbackProbeResult* result = nullptr;
    bool done = false;
    std::array<uint8_t, 8> buffer{};
};

uint32_t readLittleEndianFeedback(const uint8_t* data, int length) {
    const int n = std::max(0, std::min(length, 4));
    uint32_t raw = 0;
    for (int i = 0; i < n; ++i) {
        raw |= static_cast<uint32_t>(data[i]) << (8 * i);
    }
    return raw;
}

// UAC2 explicit feedback commonly uses a 10.14 value for high-speed endpoints,
// representing samples per microframe. Some devices return fewer bytes. Keep the
// parser permissive because this probe is diagnostic, not the final PLL.
double decodeFeedbackFramesPerMicroframe(uint32_t raw, int actualLength) {
    if (actualLength <= 0 || raw == 0) return 0.0;
    if (actualLength >= 3) {
        return static_cast<double>(raw) / static_cast<double>(1u << 14);
    }
    if (actualLength == 2) {
        return static_cast<double>(raw) / static_cast<double>(1u << 10);
    }
    return static_cast<double>(raw);
}

void LIBUSB_CALL feedbackProbeCallback(libusb_transfer* transfer) {
    auto* ctx = static_cast<FeedbackProbeContext*>(transfer ? transfer->user_data : nullptr);
    if (ctx == nullptr || ctx->result == nullptr || transfer == nullptr) return;

    auto& result = *ctx->result;
    result.transferStatus = static_cast<int>(transfer->status);
    result.actualLength = transfer->actual_length;

    if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        result.completed = true;
        result.completeCount += 1;
        result.rawValue = readLittleEndianFeedback(ctx->buffer.data(), transfer->actual_length);
        result.feedbackFramesPerMicroframe = decodeFeedbackFramesPerMicroframe(
                result.rawValue, transfer->actual_length);
        LOGI("feedback probe completed ep=0x%x len=%d raw=0x%x framesPerMicroframe=%.6f",
             transfer->endpoint, transfer->actual_length, result.rawValue,
             result.feedbackFramesPerMicroframe);
    } else if (transfer->status == LIBUSB_TRANSFER_CANCELLED) {
        result.cancelled = true;
        LOGI("feedback probe cancelled ep=0x%x", transfer->endpoint);
    } else {
        result.errorCount += 1;
        std::ostringstream os;
        os << "feedback transfer status=" << static_cast<int>(transfer->status);
        result.lastError = os.str();
        LOGW("feedback probe status ep=0x%x status=%d len=%d",
             transfer->endpoint, static_cast<int>(transfer->status), transfer->actual_length);
    }

    ctx->done = true;
}

} // namespace

bool probeUac20FeedbackEndpoint(
        libusb_context* context,
        libusb_device_handle* handle,
        int endpointAddress,
        int timeoutMs,
        Uac20FeedbackProbeResult* outResult) {
    if (outResult == nullptr) return false;
    *outResult = Uac20FeedbackProbeResult{};
    outResult->attempted = true;

    if (context == nullptr || handle == nullptr) {
        outResult->lastError = "feedback probe called with null context/handle";
        return false;
    }
    if ((endpointAddress & LIBUSB_ENDPOINT_IN) == 0) {
        outResult->lastError = "feedback endpoint is not IN";
        return false;
    }

    auto ctx = std::make_unique<FeedbackProbeContext>();
    ctx->result = outResult;

    libusb_transfer* transfer = libusb_alloc_transfer(1);
    if (transfer == nullptr) {
        outResult->lastError = "libusb_alloc_transfer failed";
        return false;
    }

    const unsigned int timeout = static_cast<unsigned int>(std::max(16, timeoutMs));
    libusb_fill_iso_transfer(
            transfer,
            handle,
            static_cast<unsigned char>(endpointAddress),
            ctx->buffer.data(),
            static_cast<int>(ctx->buffer.size()),
            1,
            feedbackProbeCallback,
            ctx.get(),
            timeout);
    libusb_set_iso_packet_lengths(transfer, static_cast<unsigned int>(ctx->buffer.size()));

    const int submitRc = libusb_submit_transfer(transfer);
    outResult->submitResult = submitRc;
    if (submitRc != 0) {
        outResult->lastError = std::string("libusb_submit_transfer failed rc=") + std::to_string(submitRc);
        LOGW("feedback probe submit failed ep=0x%x rc=%d", endpointAddress, submitRc);
        libusb_free_transfer(transfer);
        return false;
    }
    outResult->submitted = true;
    LOGI("feedback probe submitted ep=0x%x timeoutMs=%d", endpointAddress, timeoutMs);

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(std::max(16, timeoutMs));
    while (!ctx->done && std::chrono::steady_clock::now() < deadline) {
        timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = 5 * 1000;
        const int rc = libusb_handle_events_timeout(context, &tv);
        if (rc != 0 && rc != LIBUSB_ERROR_INTERRUPTED) {
            outResult->errorCount += 1;
            outResult->lastError = std::string("libusb_handle_events_timeout rc=") + std::to_string(rc);
            LOGW("feedback probe handle_events rc=%d", rc);
            break;
        }
    }

    if (!ctx->done) {
        outResult->timedOut = true;
        outResult->cancelResult = libusb_cancel_transfer(transfer);
        LOGW("feedback probe timed out ep=0x%x cancelRc=%d", endpointAddress, outResult->cancelResult);

        // Drain the cancellation callback briefly so the transfer can be freed safely.
        const auto cancelDeadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(40);
        while (!ctx->done && std::chrono::steady_clock::now() < cancelDeadline) {
            timeval tv{};
            tv.tv_sec = 0;
            tv.tv_usec = 5 * 1000;
            const int rc = libusb_handle_events_timeout(context, &tv);
            if (rc != 0 && rc != LIBUSB_ERROR_INTERRUPTED) break;
        }
    }

    if (ctx->done) {
        libusb_free_transfer(transfer);
    } else {
        // Avoid use-after-free if a device/host stack never delivers the cancel
        // callback. This is a diagnostic-only path, so leaking this tiny probe is
        // safer than freeing an active libusb transfer.
        outResult->lastError = outResult->lastError.empty()
                ? "feedback cancel callback did not arrive"
                : outResult->lastError + "; feedback cancel callback did not arrive";
        (void) ctx.release();
    }
    return outResult->submitted && outResult->completed;
}

std::string describeUac20FeedbackProbe(const Uac20FeedbackProbeResult& result) {
    std::ostringstream os;
    os << "attempted=" << (result.attempted ? 1 : 0)
       << " submitted=" << (result.submitted ? 1 : 0)
       << " completed=" << (result.completed ? 1 : 0)
       << " timeout=" << (result.timedOut ? 1 : 0)
       << " cancelled=" << (result.cancelled ? 1 : 0)
       << " submitRc=" << result.submitResult
       << " status=" << result.transferStatus
       << " len=" << result.actualLength
       << " raw=0x" << std::hex << result.rawValue << std::dec
       << " fpmf=" << result.feedbackFramesPerMicroframe
       << " completeCount=" << result.completeCount
       << " errors=" << result.errorCount;
    if (!result.lastError.empty()) {
        os << " error=" << result.lastError;
    }
    return os.str();
}

Uac20PersistentFeedbackTransfer::Uac20PersistentFeedbackTransfer() = default;

Uac20PersistentFeedbackTransfer::~Uac20PersistentFeedbackTransfer() {
    stop("destructor");
}

bool Uac20PersistentFeedbackTransfer::start(
        libusb_device_handle* handle,
        int endpointAddress,
        int packetBytes) {
    stop("restart");

    std::lock_guard<std::mutex> lock(mutex_);
    stats_ = Uac20FeedbackRuntimeStats{};
    stats_.attempted = true;

    if (handle == nullptr) {
        stats_.lastError = "persistent feedback called with null handle";
        return false;
    }
    if ((endpointAddress & LIBUSB_ENDPOINT_IN) == 0) {
        stats_.lastError = "persistent feedback endpoint is not IN";
        return false;
    }

    const int clampedPacketBytes = std::max(3, std::min(packetBytes, static_cast<int>(buffer_.size())));
    handle_ = handle;
    endpointAddress_ = endpointAddress;
    stopping_ = false;
    active_ = false;
    buffer_.fill(0);

    transfer_ = libusb_alloc_transfer(1);
    if (transfer_ == nullptr) {
        stats_.lastError = "libusb_alloc_transfer failed";
        return false;
    }

    libusb_fill_iso_transfer(
            transfer_,
            handle_,
            static_cast<unsigned char>(endpointAddress_),
            buffer_.data(),
            clampedPacketBytes,
            1,
            Uac20PersistentFeedbackTransfer::callback,
            this,
            0 /* no timeout; event thread owns lifetime */);
    libusb_set_iso_packet_lengths(transfer_, static_cast<unsigned int>(clampedPacketBytes));

    const int rc = libusb_submit_transfer(transfer_);
    stats_.submitResult = rc;
    if (rc != 0) {
        stats_.lastError = std::string("persistent feedback submit failed rc=") + std::to_string(rc);
        LOGW("persistent feedback submit failed ep=0x%x rc=%d", endpointAddress_, rc);
        libusb_free_transfer(transfer_);
        transfer_ = nullptr;
        return false;
    }

    active_ = true;
    stats_.submitted = true;
    stats_.active = true;
    LOGI("persistent feedback started ep=0x%x packetBytes=%d", endpointAddress_, clampedPacketBytes);
    return true;
}

void Uac20PersistentFeedbackTransfer::stop(const char* reason) {
    libusb_transfer* transferToCancel = nullptr;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        stopping_ = true;
        if (transfer_ != nullptr && active_) {
            transferToCancel = transfer_;
            stats_.cancelResult = libusb_cancel_transfer(transfer_);
            LOGI("persistent feedback cancel reason=%s ep=0x%x rc=%d",
                 reason ? reason : "null", endpointAddress_, stats_.cancelResult);
        }
    }

    if (transferToCancel != nullptr) {
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(80);
        while (std::chrono::steady_clock::now() < deadline) {
            {
                std::lock_guard<std::mutex> lock(mutex_);
                if (!active_) break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(4));
        }
    }

    std::lock_guard<std::mutex> lock(mutex_);
    if (active_) {
        // If the event thread did not deliver the cancellation callback, keep the
        // libusb transfer allocated instead of freeing an active transfer. The
        // session will close the device immediately after this in diagnostic mode.
        stats_.lastError = stats_.lastError.empty()
                ? "persistent feedback cancel callback did not arrive"
                : stats_.lastError + "; persistent feedback cancel callback did not arrive";
        LOGW("persistent feedback cancel callback did not arrive ep=0x%x", endpointAddress_);
        return;
    }
    freeInactiveTransferLocked();
    handle_ = nullptr;
    endpointAddress_ = 0;
}

bool Uac20PersistentFeedbackTransfer::active() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return active_;
}

Uac20FeedbackRuntimeStats Uac20PersistentFeedbackTransfer::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    Uac20FeedbackRuntimeStats copy = stats_;
    copy.active = active_;
    return copy;
}

std::string Uac20PersistentFeedbackTransfer::summary() const {
    return describeUac20FeedbackRuntime(snapshot());
}

void LIBUSB_CALL Uac20PersistentFeedbackTransfer::callback(libusb_transfer* transfer) {
    auto* self = static_cast<Uac20PersistentFeedbackTransfer*>(transfer ? transfer->user_data : nullptr);
    if (self != nullptr) {
        self->onCallback(transfer);
    }
}

void Uac20PersistentFeedbackTransfer::onCallback(libusb_transfer* transfer) {
    if (transfer == nullptr) return;

    std::lock_guard<std::mutex> lock(mutex_);
    stats_.transferStatus = static_cast<int>(transfer->status);
    stats_.actualLength = transfer->actual_length;

    if (transfer->status == LIBUSB_TRANSFER_COMPLETED) {
        stats_.completeCount += 1;
        stats_.rawValue = readLittleEndianFeedback(buffer_.data(), transfer->actual_length);
        stats_.feedbackFramesPerMicroframe = decodeFeedbackFramesPerMicroframe(
                stats_.rawValue, transfer->actual_length);

        if (!stopping_) {
            const int rc = libusb_submit_transfer(transfer_);
            if (rc == 0) {
                stats_.resubmitCount += 1;
                active_ = true;
                stats_.active = true;
            } else {
                stats_.errorCount += 1;
                stats_.submitResult = rc;
                stats_.lastError = std::string("persistent feedback resubmit failed rc=") + std::to_string(rc);
                active_ = false;
                stats_.active = false;
                LOGW("persistent feedback resubmit failed ep=0x%x rc=%d", endpointAddress_, rc);
            }
        } else {
            active_ = false;
            stats_.active = false;
        }
        return;
    }

    if (transfer->status == LIBUSB_TRANSFER_CANCELLED) {
        stats_.cancelled = true;
        active_ = false;
        stats_.active = false;
        LOGI("persistent feedback cancelled ep=0x%x completes=%d errors=%d",
             endpointAddress_, stats_.completeCount, stats_.errorCount);
        return;
    }

    stats_.errorCount += 1;
    stats_.lastError = std::string("persistent feedback transfer status=")
            + std::to_string(static_cast<int>(transfer->status));
    active_ = false;
    stats_.active = false;
    LOGW("persistent feedback status ep=0x%x status=%d len=%d",
         endpointAddress_, static_cast<int>(transfer->status), transfer->actual_length);
}

bool Uac20PersistentFeedbackTransfer::freeInactiveTransferLocked() {
    if (transfer_ == nullptr) return true;
    if (active_) return false;
    libusb_free_transfer(transfer_);
    transfer_ = nullptr;
    return true;
}

std::string describeUac20FeedbackRuntime(const Uac20FeedbackRuntimeStats& result) {
    std::ostringstream os;
    os << "attempted=" << (result.attempted ? 1 : 0)
       << " submitted=" << (result.submitted ? 1 : 0)
       << " active=" << (result.active ? 1 : 0)
       << " cancelled=" << (result.cancelled ? 1 : 0)
       << " submitRc=" << result.submitResult
       << " cancelRc=" << result.cancelResult
       << " status=" << result.transferStatus
       << " len=" << result.actualLength
       << " raw=0x" << std::hex << result.rawValue << std::dec
       << " fpmf=" << result.feedbackFramesPerMicroframe
       << " completeCount=" << result.completeCount
       << " resubmitCount=" << result.resubmitCount
       << " errors=" << result.errorCount;
    if (!result.lastError.empty()) {
        os << " error=" << result.lastError;
    }
    return os.str();
}

} // namespace rawsmusic::usb
