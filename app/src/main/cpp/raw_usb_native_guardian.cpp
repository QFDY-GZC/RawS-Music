#include "raw_usb_native_guardian.h"

#include "raw_usb_background_scheduler.h"
#include "libusb.h"

#include <android/log.h>
#include <algorithm>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <exception>
#include <fcntl.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

#define TAG "UsbBgGuardian"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace rawsmusic::usb {
namespace {

constexpr uint8_t kCommandWake = 1;
constexpr uint8_t kCommandStop = 2;

struct GuardianCommandFrame {
    uint8_t type = 0;
    uint8_t reserved[7]{};
};

int64_t nowSteadyMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

bool setFdFlags(int fd) {
    if (fd < 0) return false;
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) {
        (void)fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    }
    int cloexec = fcntl(fd, F_GETFD, 0);
    if (cloexec >= 0) {
        (void)fcntl(fd, F_SETFD, cloexec | FD_CLOEXEC);
    }
    return true;
}

void closeFd(int* fd) {
    if (!fd || *fd < 0) return;
    close(*fd);
    *fd = -1;
}

} // namespace

UsbNativeBackgroundGuardian::UsbNativeBackgroundGuardian(void* opaque, UsbGuardianHooks hooks)
    : opaque_(opaque), hooks_(hooks) {
    ensureControlSocket();
}

UsbNativeBackgroundGuardian::~UsbNativeBackgroundGuardian() {
    stop("destructor");
    closeControlSocket();
}

bool UsbNativeBackgroundGuardian::running() const {
    return thread_.joinable() && !stopRequested_.load(std::memory_order_acquire);
}

bool UsbNativeBackgroundGuardian::ensureControlSocket() {
    if (controlReadFd_ >= 0 && controlWriteFd_ >= 0) {
        return true;
    }
    int fds[2] = {-1, -1};
    if (socketpair(AF_UNIX, SOCK_DGRAM, 0, fds) != 0) {
        LOGE("USB_BG_GUARD socketpair failed errno=%d(%s)", errno, strerror(errno));
        closeFd(&fds[0]);
        closeFd(&fds[1]);
        return false;
    }
    setFdFlags(fds[0]);
    setFdFlags(fds[1]);
    controlReadFd_ = fds[0];
    controlWriteFd_ = fds[1];
    return true;
}

void UsbNativeBackgroundGuardian::closeControlSocket() {
    closeFd(&controlReadFd_);
    closeFd(&controlWriteFd_);
}

void UsbNativeBackgroundGuardian::sendWakeCommand() {
    if (controlWriteFd_ < 0) return;
    GuardianCommandFrame cmd{};
    cmd.type = kCommandWake;
    const ssize_t written = send(controlWriteFd_, &cmd, sizeof(cmd), MSG_DONTWAIT | MSG_NOSIGNAL);
    if (written < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
        LOGW("USB_BG_GUARD wake send failed errno=%d(%s)", errno, strerror(errno));
    }
}

void UsbNativeBackgroundGuardian::sendStopCommand() {
    if (controlWriteFd_ < 0) return;
    GuardianCommandFrame cmd{};
    cmd.type = kCommandStop;
    const ssize_t written = send(controlWriteFd_, &cmd, sizeof(cmd), MSG_DONTWAIT | MSG_NOSIGNAL);
    if (written < 0 && errno != EAGAIN && errno != EWOULDBLOCK) {
        LOGW("USB_BG_GUARD stop send failed errno=%d(%s)", errno, strerror(errno));
    }
}

void UsbNativeBackgroundGuardian::drainControlSocket(bool* stopRequested) {
    if (stopRequested) *stopRequested = false;
    if (controlReadFd_ < 0) return;
    GuardianCommandFrame cmd{};
    while (true) {
        const ssize_t n = recv(controlReadFd_, &cmd, sizeof(cmd), MSG_DONTWAIT);
        if (n == (ssize_t)sizeof(cmd)) {
            if (cmd.type == kCommandStop && stopRequested) {
                *stopRequested = true;
            }
            continue;
        }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            return;
        }
        return;
    }
}

void UsbNativeBackgroundGuardian::start(const char* reason) {
    std::lock_guard<std::mutex> lock(threadMutex_);
    if (thread_.joinable()) {
        sendWakeCommand();
        return;
    }
    if (!ensureControlSocket()) {
        LOGW("USB_BG_GUARD start skipped: no control socket reason=%s", reason ? reason : "unknown");
        return;
    }
    stopRequested_.store(false, std::memory_order_release);
    try {
        thread_ = std::thread(&UsbNativeBackgroundGuardian::threadMain, this);
        LOGI("USB_BG_GUARD start reason=%s", reason ? reason : "unknown");
        sendWakeCommand();
    } catch (const std::exception& e) {
        LOGE("USB_BG_GUARD thread create failed reason=%s err=%s",
             reason ? reason : "unknown", e.what());
        stopRequested_.store(true, std::memory_order_release);
    } catch (...) {
        LOGE("USB_BG_GUARD thread create failed reason=%s", reason ? reason : "unknown");
        stopRequested_.store(true, std::memory_order_release);
    }
}

void UsbNativeBackgroundGuardian::stop(const char* reason) {
    std::lock_guard<std::mutex> lock(threadMutex_);
    if (!thread_.joinable()) {
        return;
    }
    LOGI("USB_BG_GUARD stop requested reason=%s", reason ? reason : "unknown");
    stopRequested_.store(true, std::memory_order_release);
    sendStopCommand();
    thread_.join();
}

void UsbNativeBackgroundGuardian::notifyStateChanged(const char* reason) {
    if (!thread_.joinable()) return;
    LOGI("USB_BG_GUARD state wake reason=%s", reason ? reason : "unknown");
    sendWakeCommand();
}

void UsbNativeBackgroundGuardian::notifyKeepAlivePulse(const char* reason) {
    if (!thread_.joinable()) return;
    const int64_t nowMs = nowSteadyMs();
    static std::atomic<int64_t> lastLogMs{0};
    int64_t lastLog = lastLogMs.load(std::memory_order_relaxed);
    if (nowMs - lastLog >= 10'000 &&
        lastLogMs.compare_exchange_strong(
            lastLog, nowMs, std::memory_order_acq_rel, std::memory_order_relaxed)) {
        LOGI("USB_BG_GUARD keepalive wake reason=%s", reason ? reason : "unknown");
    }
    sendWakeCommand();
}

void UsbNativeBackgroundGuardian::threadMain() {
    // The monitor does not run a third high-priority event-pump owner. It only
    // observes state and wakes the real event owner, so keep it at normal
    // scheduling priority to avoid competing with decoder/USB send work.
    const auto schedule = applyUsbThreadScheduling("usb_bg_guard", 0, false);
    LOGI("USB_BG_GUARD thread online sched=%s", formatUsbThreadScheduleSnapshot(schedule).c_str());

    uint64_t lastSessionId = 0;
    int64_t lastPulseLogMs = 0;
    int64_t lastStallLogMs = 0;

    while (!stopRequested_.load(std::memory_order_acquire)) {
        UsbGuardianRuntimeSnapshot snap{};
        const bool haveSnapshot = hooks_.snapshot && hooks_.snapshot(opaque_, &snap);
        const bool guardActive =
            haveSnapshot &&
            snap.streamActive &&
            snap.backgroundActive &&
            snap.exclusiveActive &&
            !snap.transportLost;

        // A monitor does not need an 8 ms wake cadence. High-frequency wakeups at
        // audio priority can crowd out the Java decoder after the app moves to a
        // background cgroup. 100 ms is sufficient for diagnostics and terminal
        // zero-pending detection without becoming another transport owner.
        const int timeoutMs = guardActive ? 100 : 500;
        if (controlReadFd_ >= 0) {
            pollfd pfd{};
            pfd.fd = controlReadFd_;
            pfd.events = POLLIN;
            const int pollRc = poll(&pfd, 1, timeoutMs);
            if (pollRc > 0 && (pfd.revents & (POLLIN | POLLHUP | POLLERR))) {
                bool requestedStop = false;
                drainControlSocket(&requestedStop);
                if (requestedStop) {
                    stopRequested_.store(true, std::memory_order_release);
                    break;
                }
            }
        } else {
            std::this_thread::sleep_for(std::chrono::milliseconds(timeoutMs));
        }

        if (stopRequested_.load(std::memory_order_acquire) || !guardActive) {
            continue;
        }

        const int64_t nowMs = nowSteadyMs();
        if (snap.streamSessionId != lastSessionId) {
            lastSessionId = snap.streamSessionId;
            lastPulseLogMs = 0;
            lastStallLogMs = 0;
        }

        if (snap.pendingTransfers <= 0 && hooks_.recoverTransfers) {
            const int recovered = hooks_.recoverTransfers(opaque_, "usb_bg_guard_zero_pending");
            if (recovered > 0) {
                LOGW(
                    "USB_BG_GUARD rearmed transfer chain: session=%llu submitted=%d buf=%zu/%zu",
                    static_cast<unsigned long long>(snap.streamSessionId),
                    recovered,
                    snap.ringUsedBytes,
                    snap.ringCapacityBytes
                );
                continue;
            }
        }

        const bool callbackStale =
            snap.lastIsoCallbackMs > 0 &&
            nowMs - snap.lastIsoCallbackMs >= 24 &&
            snap.pendingTransfers > 0;
        const bool recentGap =
            snap.lastEventLoopGapMs > 0 &&
            nowMs - snap.lastEventLoopGapMs <= 2'000 &&
            snap.lastEventLoopGapDurationMs >= 100;

        // The epoll-driven USB event thread is the sole libusb event owner.
        // A second consumer can contend on libusb's event lock and delay the
        // completion thread it is intended to protect.
        const int pumpRc = 0;
        const int lockedCount = 0;
        const int busyCount = 0;
        const int errorCount = 0;

        if (callbackStale && nowMs - lastStallLogMs >= 1'500) {
            lastStallLogMs = nowMs;
            LOGW(
                "USB_BG_GUARD stall: session=%llu callbackAgeMs=%lld pending=%d buf=%zu/%zu "
                "gapMs=%lld gapDurMs=%lld locked=%d busy=%d rc=%d completed=%lld",
                static_cast<unsigned long long>(snap.streamSessionId),
                static_cast<long long>(nowMs - snap.lastIsoCallbackMs),
                snap.pendingTransfers,
                snap.ringUsedBytes,
                snap.ringCapacityBytes,
                static_cast<long long>(snap.lastEventLoopGapMs),
                static_cast<long long>(snap.lastEventLoopGapDurationMs),
                lockedCount,
                busyCount,
                pumpRc,
                static_cast<long long>(snap.totalCompletedUsbBytes)
            );
        } else if (errorCount > 0 || nowMs - lastPulseLogMs >= 10'000) {
            lastPulseLogMs = nowMs;
            LOGI(
                "USB_BG_GUARD pulse: session=%llu active=1 pending=%d buf=%zu/%zu "
                "stale=%d gapDurMs=%lld locked=%d busy=%d rc=%d completed=%lld",
                static_cast<unsigned long long>(snap.streamSessionId),
                snap.pendingTransfers,
                snap.ringUsedBytes,
                snap.ringCapacityBytes,
                callbackStale ? 1 : 0,
                static_cast<long long>(snap.lastEventLoopGapDurationMs),
                lockedCount,
                busyCount,
                pumpRc,
                static_cast<long long>(snap.totalCompletedUsbBytes)
            );
        }
    }

    LOGI("USB_BG_GUARD exit");
}

} // namespace rawsmusic::usb
