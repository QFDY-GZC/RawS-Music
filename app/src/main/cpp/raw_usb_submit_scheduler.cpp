#include "raw_usb_submit_scheduler.h"

#include <android/log.h>
#include <utility>
#include <system_error>

#include "raw_usb_background_scheduler.h"

#define TAG "UsbAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace rawsmusic::usb {

RawUsbSubmitScheduler::~RawUsbSubmitScheduler() {
    stop();
}

bool RawUsbSubmitScheduler::start(const char* threadName, SubmitFn submitFn) {
    if (!submitFn) {
        LOGE("USB submit scheduler start failed: submitFn is empty");
        return false;
    }

    stop();

    {
        std::lock_guard<std::mutex> lk(mtx_);
        queue_.clear();
        submitFn_ = std::move(submitFn);
        running_.store(true, std::memory_order_release);
    }

    const std::string name = threadName && *threadName ? threadName : "RawS-USB-Send";
    try {
        worker_ = std::thread(&RawUsbSubmitScheduler::runLoop, this, name);
    } catch (const std::system_error& e) {
        running_.store(false, std::memory_order_release);
        std::lock_guard<std::mutex> lk(mtx_);
        submitFn_ = nullptr;
        LOGE("USB submit scheduler thread creation failed: %s", e.what());
        return false;
    }
    return true;
}

void RawUsbSubmitScheduler::stop() {
    const bool wasRunning = running_.exchange(false, std::memory_order_acq_rel);
    if (wasRunning) {
        cv_.notify_all();
    }
    if (worker_.joinable()) {
        worker_.join();
    }
    {
        std::lock_guard<std::mutex> lk(mtx_);
        if (!queue_.empty()) {
            droppedJobs_.fetch_add(static_cast<int64_t>(queue_.size()), std::memory_order_relaxed);
        }
        queue_.clear();
        submitFn_ = nullptr;
    }
}

bool RawUsbSubmitScheduler::enqueue(int index, JobReason reason) {
    if (!running_.load(std::memory_order_acquire)) {
        droppedJobs_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    {
        std::lock_guard<std::mutex> lk(mtx_);
        if (!running_.load(std::memory_order_relaxed) || !submitFn_) {
            droppedJobs_.fetch_add(1, std::memory_order_relaxed);
            return false;
        }
        queue_.push_back(Job{index, reason});
        enqueuedJobs_.fetch_add(1, std::memory_order_relaxed);
    }
    cv_.notify_one();
    return true;
}

bool RawUsbSubmitScheduler::enqueueProgressive() {
    return enqueue(-1, JobReason::Progressive);
}

bool RawUsbSubmitScheduler::isRunning() const {
    return running_.load(std::memory_order_acquire);
}

int64_t RawUsbSubmitScheduler::enqueuedJobs() const {
    return enqueuedJobs_.load(std::memory_order_relaxed);
}

int64_t RawUsbSubmitScheduler::processedJobs() const {
    return processedJobs_.load(std::memory_order_relaxed);
}

int64_t RawUsbSubmitScheduler::droppedJobs() const {
    return droppedJobs_.load(std::memory_order_relaxed);
}

size_t RawUsbSubmitScheduler::pendingJobs() const {
    std::lock_guard<std::mutex> lk(mtx_);
    return queue_.size();
}

void RawUsbSubmitScheduler::runLoop(std::string threadName) {
    const auto sched = applyUsbThreadScheduling(threadName.c_str(), -18, true);
    LOGI("USB_SUBMIT_THREAD_SCHED %s", formatUsbThreadScheduleSnapshot(sched).c_str());

    while (running_.load(std::memory_order_acquire)) {
        Job job;
        SubmitFn fn;
        {
            std::unique_lock<std::mutex> lk(mtx_);
            cv_.wait(lk, [&]() {
                return !running_.load(std::memory_order_acquire) || !queue_.empty();
            });
            if (!running_.load(std::memory_order_acquire) && queue_.empty()) {
                break;
            }
            if (queue_.empty()) {
                continue;
            }
            job = queue_.front();
            queue_.pop_front();
            fn = submitFn_;
        }

        if (!fn) {
            droppedJobs_.fetch_add(1, std::memory_order_relaxed);
            continue;
        }

        fn(job.index, job.reason);
        processedJobs_.fetch_add(1, std::memory_order_relaxed);
    }

    LOGI("USB submit scheduler thread exit: enqueued=%lld processed=%lld dropped=%lld pending=%zu",
         static_cast<long long>(enqueuedJobs_.load(std::memory_order_relaxed)),
         static_cast<long long>(processedJobs_.load(std::memory_order_relaxed)),
         static_cast<long long>(droppedJobs_.load(std::memory_order_relaxed)),
         pendingJobs());
}

} // namespace rawsmusic::usb
