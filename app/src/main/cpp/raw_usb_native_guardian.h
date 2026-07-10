#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <thread>

namespace rawsmusic::usb {

struct UsbGuardianRuntimeSnapshot {
    bool streamActive = false;
    bool backgroundActive = false;
    bool exclusiveActive = false;
    bool eventThreadRunning = false;
    bool transportLost = false;
    int pendingTransfers = 0;
    uint64_t streamSessionId = 0;
    int64_t lastEventLoopGapMs = 0;
    int64_t lastEventLoopGapDurationMs = 0;
    int64_t lastIsoCallbackMs = 0;
    int64_t totalCompletedUsbBytes = 0;
    size_t ringUsedBytes = 0;
    size_t ringCapacityBytes = 0;
};

struct UsbGuardianHooks {
    bool (*snapshot)(void* opaque, UsbGuardianRuntimeSnapshot* out) = nullptr;
    int (*pumpEventsOnce)(void* opaque, const char* reason, bool* obtainedEventLock) = nullptr;
};

class UsbNativeBackgroundGuardian {
public:
    UsbNativeBackgroundGuardian(void* opaque, UsbGuardianHooks hooks);
    ~UsbNativeBackgroundGuardian();

    UsbNativeBackgroundGuardian(const UsbNativeBackgroundGuardian&) = delete;
    UsbNativeBackgroundGuardian& operator=(const UsbNativeBackgroundGuardian&) = delete;

    void start(const char* reason);
    void stop(const char* reason);
    void notifyStateChanged(const char* reason);
    void notifyKeepAlivePulse(const char* reason);
    bool running() const;

private:
    void threadMain();
    bool ensureControlSocket();
    void closeControlSocket();
    void sendWakeCommand();
    void sendStopCommand();
    void drainControlSocket(bool* stopRequested);

private:
    void* opaque_ = nullptr;
    UsbGuardianHooks hooks_{};
    int controlReadFd_ = -1;
    int controlWriteFd_ = -1;
    std::atomic<bool> stopRequested_{false};
    std::thread thread_{};
    std::mutex threadMutex_{};
};

} // namespace rawsmusic::usb
