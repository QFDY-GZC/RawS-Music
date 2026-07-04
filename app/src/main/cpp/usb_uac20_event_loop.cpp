#include "usb_uac20_event_loop.h"

#include <android/log.h>
#include <cstdio>
#include <sstream>
#include <sys/time.h>

#include "libusb.h"

#define TAG "RawUac20EventLoop"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace rawsmusic::usb {

Uac20EventLoop::Uac20EventLoop() = default;

Uac20EventLoop::~Uac20EventLoop() {
    stop("destructor");
}

bool Uac20EventLoop::start(libusb_context* context, const char* reason) {
    if (context == nullptr) {
        lastError_.store(LIBUSB_ERROR_INVALID_PARAM);
        errorCount_.fetch_add(1);
        LOGE("start failed: null context reason=%s", reason ? reason : "null");
        return false;
    }
    if (running_.load()) {
        return true;
    }

    context_ = context;
    stopRequested_.store(false);
    tickCount_.store(0);
    timeoutCount_.store(0);
    okCount_.store(0);
    wakeCount_.store(0);
    errorCount_.store(0);
    lastError_.store(0);

    try {
        thread_ = std::thread(&Uac20EventLoop::loop, this);
    } catch (...) {
        stopRequested_.store(true);
        context_ = nullptr;
        lastError_.store(-10001);
        errorCount_.fetch_add(1);
        LOGE("start failed: thread create exception reason=%s", reason ? reason : "null");
        return false;
    }

    LOGI("event loop started reason=%s", reason ? reason : "null");
    return true;
}

void Uac20EventLoop::stop(const char* reason) {
    stopRequested_.store(true);
    if (thread_.joinable()) {
        thread_.join();
    }
    if (context_ != nullptr || running_.load()) {
        LOGI("event loop stopped reason=%s ticks=%lld errors=%d last=%d",
             reason ? reason : "null",
             static_cast<long long>(tickCount_.load()),
             errorCount_.load(),
             lastError_.load());
    }
    running_.store(false);
    context_ = nullptr;
}

bool Uac20EventLoop::running() const {
    return running_.load();
}

int64_t Uac20EventLoop::tickCount() const {
    return tickCount_.load();
}

int64_t Uac20EventLoop::timeoutCount() const {
    return timeoutCount_.load();
}

int64_t Uac20EventLoop::okCount() const {
    return okCount_.load();
}

int64_t Uac20EventLoop::wakeCount() const {
    return wakeCount_.load();
}

int Uac20EventLoop::errorCount() const {
    return errorCount_.load();
}

int Uac20EventLoop::lastError() const {
    return lastError_.load();
}

std::string Uac20EventLoop::summary() const {
    std::ostringstream os;
    os << "running=" << (running_.load() ? 1 : 0)
       << " ticks=" << tickCount_.load()
       << " ok=" << okCount_.load()
       << " timeouts=" << timeoutCount_.load()
       << " wakes=" << wakeCount_.load()
       << " errors=" << errorCount_.load()
       << " last=" << lastError_.load();
    return os.str();
}

void Uac20EventLoop::loop() {
    running_.store(true);
    while (!stopRequested_.load()) {
        timeval tv{};
        tv.tv_sec = 0;
        tv.tv_usec = 5000; // 5 ms: responsive stop without busy spinning.

        int completed = 0;
        const int rc = libusb_handle_events_timeout_completed(context_, &tv, &completed);
        tickCount_.fetch_add(1);
        if (rc == LIBUSB_SUCCESS) {
            okCount_.fetch_add(1);
        } else if (rc == LIBUSB_ERROR_TIMEOUT) {
            timeoutCount_.fetch_add(1);
        } else if (rc == LIBUSB_ERROR_INTERRUPTED) {
            wakeCount_.fetch_add(1);
        } else {
            lastError_.store(rc);
            errorCount_.fetch_add(1);
            LOGW("libusb_handle_events_timeout_completed rc=%d", rc);
        }
    }
    running_.store(false);
}

} // namespace rawsmusic::usb
