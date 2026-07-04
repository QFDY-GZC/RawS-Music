#pragma once

#include <array>
#include <cstdint>
#include <mutex>
#include <string>

#include "libusb.h"

struct libusb_context;
struct libusb_device_handle;
struct libusb_transfer;

namespace rawsmusic::usb {

struct Uac20FeedbackProbeResult {
    bool attempted = false;
    bool submitted = false;
    bool completed = false;
    bool timedOut = false;
    bool cancelled = false;

    int submitResult = 0;
    int cancelResult = 0;
    int transferStatus = 0;
    int actualLength = 0;
    int completeCount = 0;
    int errorCount = 0;

    uint32_t rawValue = 0;
    double feedbackFramesPerMicroframe = 0.0;
    std::string lastError;
};

struct Uac20FeedbackRuntimeStats {
    bool attempted = false;
    bool submitted = false;
    bool active = false;
    bool cancelled = false;

    int submitResult = 0;
    int cancelResult = 0;
    int transferStatus = 0;
    int actualLength = 0;
    int completeCount = 0;
    int errorCount = 0;
    int resubmitCount = 0;

    uint32_t rawValue = 0;
    double feedbackFramesPerMicroframe = 0.0;
    std::string lastError;
};

// Low-risk smoke test used by the v2 UAC20 bring-up path. It submits a single
// explicit feedback IN transfer, pumps libusb events for a short window, records
// the first packet, then tears the transfer down. It intentionally does not
// submit audio OUT transfers or start a persistent event thread.
bool probeUac20FeedbackEndpoint(
        libusb_context* context,
        libusb_device_handle* handle,
        int endpointAddress,
        int timeoutMs,
        Uac20FeedbackProbeResult* outResult);

std::string describeUac20FeedbackProbe(const Uac20FeedbackProbeResult& result);

// Persistent feedback transfer used after the v2 event loop is running. This is
// the new path: enqueue feedback once, keep resubmitting in callback,
// and let the libusb event thread drive completion. Patch 0011 still does not
// submit any OUT audio transfer; this only validates long-lived feedback flow.
class Uac20PersistentFeedbackTransfer {
public:
    Uac20PersistentFeedbackTransfer();
    ~Uac20PersistentFeedbackTransfer();

    Uac20PersistentFeedbackTransfer(const Uac20PersistentFeedbackTransfer&) = delete;
    Uac20PersistentFeedbackTransfer& operator=(const Uac20PersistentFeedbackTransfer&) = delete;

    bool start(libusb_device_handle* handle, int endpointAddress, int packetBytes = 8);
    void stop(const char* reason);

    bool active() const;
    Uac20FeedbackRuntimeStats snapshot() const;
    std::string summary() const;

private:
    static void LIBUSB_CALL callback(libusb_transfer* transfer);
    void onCallback(libusb_transfer* transfer);
    bool freeInactiveTransferLocked();

private:
    mutable std::mutex mutex_;
    libusb_device_handle* handle_ = nullptr; // non-owning; owned by session
    libusb_transfer* transfer_ = nullptr;
    int endpointAddress_ = 0;
    std::array<uint8_t, 8> buffer_{};
    bool stopping_ = false;
    bool active_ = false;
    Uac20FeedbackRuntimeStats stats_{};
};

std::string describeUac20FeedbackRuntime(const Uac20FeedbackRuntimeStats& result);

} // namespace rawsmusic::usb
