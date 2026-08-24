#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace rawsmusic::usb {

// Dedicated USB submit worker used by the legacy libusb engine.
//
// The libusb event thread should remain a completion pump.  It enqueues completed
// ISO transfer indices here; this worker performs fill+libusb_submit from a
// separate "send USB" thread, keeping event handling and packet submission isolated.
class RawUsbSubmitScheduler {
public:
    enum class JobReason : int {
        Initial = 0,
        Resubmit = 1,
        Progressive = 2,
        Feedback = 3,
    };

    using SubmitFn = std::function<void(int index, JobReason reason)>;

    RawUsbSubmitScheduler() = default;
    ~RawUsbSubmitScheduler();

    RawUsbSubmitScheduler(const RawUsbSubmitScheduler&) = delete;
    RawUsbSubmitScheduler& operator=(const RawUsbSubmitScheduler&) = delete;

    bool start(const char* threadName, SubmitFn submitFn);
    void stop();

    bool enqueue(int index, JobReason reason);
    bool enqueueInitial(int index);
    bool enqueueResubmit(int index);
    bool enqueueProgressive();
    bool enqueueFeedback();

    bool isRunning() const;
    int64_t enqueuedJobs() const;
    int64_t processedJobs() const;
    int64_t droppedJobs() const;
    size_t pendingJobs() const;

private:
    struct Job {
        int index = -1;
        JobReason reason = JobReason::Resubmit;
    };

    void runLoop(std::string threadName);

    mutable std::mutex mtx_;
    std::condition_variable cv_;
    std::deque<Job> queue_;
    std::thread worker_;
    SubmitFn submitFn_;
    std::atomic<bool> running_{false};
    std::atomic<int64_t> enqueuedJobs_{0};
    std::atomic<int64_t> processedJobs_{0};
    std::atomic<int64_t> droppedJobs_{0};
};

} // namespace rawsmusic::usb
