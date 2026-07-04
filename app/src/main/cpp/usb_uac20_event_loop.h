#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>

struct libusb_context;

namespace rawsmusic::usb {

// Small non-owning libusb event loop used by the new UAC20 v2 path.
//
// This intentionally lives outside usb_audio_engine.cpp so the new
// UAC20 bring-up can migrate in small steps. Patch 0010 only runs the loop
// with no OUT audio transfers submitted; later patches will attach feedback
// and stream callbacks to this thread.
class Uac20EventLoop {
public:
    Uac20EventLoop();
    ~Uac20EventLoop();

    Uac20EventLoop(const Uac20EventLoop&) = delete;
    Uac20EventLoop& operator=(const Uac20EventLoop&) = delete;

    bool start(libusb_context* context, const char* reason);
    void stop(const char* reason);

    bool running() const;
    int64_t tickCount() const;
    int64_t timeoutCount() const;
    int64_t okCount() const;
    int64_t wakeCount() const;
    int errorCount() const;
    int lastError() const;
    std::string summary() const;

private:
    void loop();

private:
    libusb_context* context_ = nullptr; // non-owning; owned by Uac20Session
    std::atomic<bool> stopRequested_{true};
    std::atomic<bool> running_{false};
    std::atomic<int64_t> tickCount_{0};
    std::atomic<int64_t> timeoutCount_{0};
    std::atomic<int64_t> okCount_{0};
    std::atomic<int64_t> wakeCount_{0};
    std::atomic<int> errorCount_{0};
    std::atomic<int> lastError_{0};
    std::thread thread_;
};

} // namespace rawsmusic::usb
