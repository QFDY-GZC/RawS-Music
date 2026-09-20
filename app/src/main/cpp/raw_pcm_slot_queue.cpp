#include "raw_pcm_slot_queue.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>

namespace rawsmusic::pcm_slot_queue {
namespace {

constexpr int32_t kQueueCount = 2;
constexpr int32_t kMaximumSlotBytes = 2 * 1024 * 1024;

struct FixedQueue {
    alignas(64) std::array<uint8_t, static_cast<size_t>(kMaximumSlotBytes)> bytes{};
    // Even: stable ownership. Odd: control thread is resetting/reconfiguring.
    std::atomic<uint64_t> epoch{0};
    std::atomic<int32_t> activeOperations{0};
    std::atomic<int64_t> decoderSerial{0};
    std::atomic<int32_t> generation{0};
    std::atomic<int32_t> frameBytes{0};
    std::atomic<int32_t> capacityBytes{0};
    // Monotonic byte cursors. Array access wraps by capacityBytes.
    std::atomic<int64_t> writeCursor{0};
    std::atomic<int64_t> readCursor{0};
    std::atomic<int32_t> role{static_cast<int32_t>(QueueRole::Empty)};
};

std::array<FixedQueue, kQueueCount> gQueues{};
std::mutex gControlMutex;
std::atomic<uint64_t> gRevision{0};

class ActiveOperation final {
public:
    explicit ActiveOperation(FixedQueue* queue) noexcept : queue_(queue) {
        if (queue_ == nullptr) return;
        epoch_ = queue_->epoch.load(std::memory_order_acquire);
        if ((epoch_ & 1U) != 0U) return;

        queue_->activeOperations.fetch_add(1, std::memory_order_acq_rel);
        if (queue_->epoch.load(std::memory_order_acquire) != epoch_) {
            queue_->activeOperations.fetch_sub(1, std::memory_order_acq_rel);
            return;
        }
        acquired_ = true;
    }

    ~ActiveOperation() {
        if (acquired_) {
            queue_->activeOperations.fetch_sub(1, std::memory_order_acq_rel);
        }
    }

    ActiveOperation(const ActiveOperation&) = delete;
    ActiveOperation& operator=(const ActiveOperation&) = delete;

    bool acquired() const noexcept { return acquired_; }

    bool unchanged() const noexcept {
        return acquired_ && queue_->epoch.load(std::memory_order_acquire) == epoch_;
    }

private:
    FixedQueue* queue_ = nullptr;
    uint64_t epoch_ = 0;
    bool acquired_ = false;
};

QueueRole loadRole(const FixedQueue& queue) noexcept {
    return static_cast<QueueRole>(queue.role.load(std::memory_order_acquire));
}

bool validIdentity(int64_t decoderSerial, int32_t generation) noexcept {
    return decoderSerial != 0 && generation >= 0;
}

bool identityMatches(
    const FixedQueue& queue,
    int64_t decoderSerial,
    int32_t generation
) noexcept {
    return queue.decoderSerial.load(std::memory_order_acquire) == decoderSerial &&
        queue.generation.load(std::memory_order_acquire) == generation;
}

FixedQueue* findQueue(
    int64_t decoderSerial,
    int32_t generation,
    bool allowPending,
    bool allowCurrent
) noexcept {
    if (!validIdentity(decoderSerial, generation)) return nullptr;
    for (FixedQueue& queue : gQueues) {
        const QueueRole role = loadRole(queue);
        if ((role == QueueRole::Pending && allowPending) ||
            (role == QueueRole::Current && allowCurrent)) {
            if (identityMatches(queue, decoderSerial, generation)) return &queue;
        }
    }
    return nullptr;
}

void beginMutationLocked(FixedQueue& queue) noexcept {
    queue.epoch.fetch_add(1, std::memory_order_acq_rel);
    queue.role.store(static_cast<int32_t>(QueueRole::Empty), std::memory_order_release);
    while (queue.activeOperations.load(std::memory_order_acquire) != 0) {
        std::this_thread::yield();
    }
}

void endMutationLocked(FixedQueue& queue) noexcept {
    queue.epoch.fetch_add(1, std::memory_order_release);
}

void resetQueueLocked(FixedQueue& queue) noexcept {
    beginMutationLocked(queue);
    queue.writeCursor.store(0, std::memory_order_relaxed);
    queue.readCursor.store(0, std::memory_order_relaxed);
    queue.capacityBytes.store(0, std::memory_order_relaxed);
    queue.frameBytes.store(0, std::memory_order_relaxed);
    queue.generation.store(0, std::memory_order_relaxed);
    queue.decoderSerial.store(0, std::memory_order_relaxed);
    endMutationLocked(queue);
}

bool configureQueueLocked(
    FixedQueue& queue,
    QueueRole role,
    int64_t decoderSerial,
    int32_t generation,
    int32_t frameSizeValue,
    int64_t targetFrames
) noexcept {
    if (!validIdentity(decoderSerial, generation) || frameSizeValue <= 0 || targetFrames < 0) {
        resetQueueLocked(queue);
        return false;
    }
    if (targetFrames > kMaximumSlotBytes / static_cast<int64_t>(frameSizeValue)) {
        resetQueueLocked(queue);
        return false;
    }
    const int64_t requestedBytes = targetFrames * static_cast<int64_t>(frameSizeValue);

    beginMutationLocked(queue);
    queue.writeCursor.store(0, std::memory_order_relaxed);
    queue.readCursor.store(0, std::memory_order_relaxed);
    queue.capacityBytes.store(static_cast<int32_t>(requestedBytes), std::memory_order_relaxed);
    queue.frameBytes.store(frameSizeValue, std::memory_order_relaxed);
    queue.generation.store(generation, std::memory_order_relaxed);
    queue.decoderSerial.store(decoderSerial, std::memory_order_relaxed);
    queue.role.store(static_cast<int32_t>(role), std::memory_order_release);
    endMutationLocked(queue);
    return true;
}

void copyIntoRing(
    FixedQueue& queue,
    int32_t capacity,
    int64_t writeCursor,
    const uint8_t* source,
    int32_t count
) noexcept {
    const int32_t index = static_cast<int32_t>(writeCursor % capacity);
    const int32_t first = std::min(count, capacity - index);
    std::memcpy(queue.bytes.data() + index, source, static_cast<size_t>(first));
    if (count > first) {
        std::memcpy(queue.bytes.data(), source + first, static_cast<size_t>(count - first));
    }
}

void copyFromRing(
    const FixedQueue& queue,
    int32_t capacity,
    int64_t readCursor,
    uint8_t* destination,
    int32_t count
) noexcept {
    const int32_t index = static_cast<int32_t>(readCursor % capacity);
    const int32_t first = std::min(count, capacity - index);
    std::memcpy(destination, queue.bytes.data() + index, static_cast<size_t>(first));
    if (count > first) {
        std::memcpy(destination + first, queue.bytes.data(), static_cast<size_t>(count - first));
    }
}

const char* roleName(QueueRole role) noexcept {
    switch (role) {
        case QueueRole::Empty: return "EMPTY";
        case QueueRole::Current: return "CURRENT";
        case QueueRole::Pending: return "PENDING";
    }
    return "UNKNOWN";
}

}  // namespace

int32_t maximumSlotBytes() noexcept {
    return kMaximumSlotBytes;
}

void resetAll() noexcept {
    std::lock_guard<std::mutex> lock(gControlMutex);
    for (FixedQueue& queue : gQueues) resetQueueLocked(queue);
    gRevision.fetch_add(1, std::memory_order_relaxed);
}

bool installCurrent(
    int64_t decoderSerial,
    int32_t generation,
    int32_t frameSizeValue
) noexcept {
    std::lock_guard<std::mutex> lock(gControlMutex);
    for (FixedQueue& queue : gQueues) resetQueueLocked(queue);
    const bool configured = configureQueueLocked(
        gQueues[0], QueueRole::Current, decoderSerial, generation, frameSizeValue, 0
    );
    gRevision.fetch_add(1, std::memory_order_relaxed);
    return configured;
}

bool beginPending(
    int64_t decoderSerial,
    int32_t generation,
    int32_t frameSizeValue,
    int64_t targetFrames
) noexcept {
    std::lock_guard<std::mutex> lock(gControlMutex);
    FixedQueue* selected = nullptr;
    for (FixedQueue& queue : gQueues) {
        if (loadRole(queue) != QueueRole::Current) {
            selected = &queue;
            break;
        }
    }
    if (selected == nullptr) return false;
    resetQueueLocked(*selected);
    const bool configured = configureQueueLocked(
        *selected, QueueRole::Pending, decoderSerial, generation, frameSizeValue, targetFrames
    );
    gRevision.fetch_add(1, std::memory_order_relaxed);
    return configured;
}

int32_t appendPending(
    int64_t decoderSerial,
    int32_t generation,
    const uint8_t* source,
    int32_t length
) noexcept {
    if (source == nullptr || length <= 0) return 0;
    FixedQueue* queue = findQueue(decoderSerial, generation, true, false);
    if (queue == nullptr) return -1;

    ActiveOperation operation(queue);
    if (!operation.acquired() || !identityMatches(*queue, decoderSerial, generation) ||
        loadRole(*queue) != QueueRole::Pending) {
        return -1;
    }

    const int32_t frameBytesValue = queue->frameBytes.load(std::memory_order_relaxed);
    const int32_t capacity = queue->capacityBytes.load(std::memory_order_relaxed);
    const int64_t write = queue->writeCursor.load(std::memory_order_relaxed);
    const int64_t readPosition = queue->readCursor.load(std::memory_order_acquire);
    if (frameBytesValue <= 0 || capacity <= 0 || write < readPosition) return -1;

    const int64_t used = write - readPosition;
    if (used < 0 || used > capacity) return -1;
    const int32_t alignedLength = length - (length % frameBytesValue);
    const int32_t writable = capacity - static_cast<int32_t>(used);
    const int32_t count = std::min(alignedLength, writable - (writable % frameBytesValue));
    if (count <= 0) return 0;

    copyIntoRing(*queue, capacity, write, source, count);
    if (!operation.unchanged() || !identityMatches(*queue, decoderSerial, generation) ||
        loadRole(*queue) != QueueRole::Pending) {
        return -1;
    }
    queue->writeCursor.store(write + count, std::memory_order_release);
    return operation.unchanged() ? count : -1;
}

int32_t read(
    int64_t decoderSerial,
    int32_t generation,
    uint8_t* destination,
    int32_t maxLength
) noexcept {
    if (destination == nullptr || maxLength <= 0) return 0;
    FixedQueue* queue = findQueue(decoderSerial, generation, true, true);
    if (queue == nullptr) return -1;

    ActiveOperation operation(queue);
    if (!operation.acquired() || !identityMatches(*queue, decoderSerial, generation)) return -1;
    const QueueRole role = loadRole(*queue);
    if (role != QueueRole::Pending && role != QueueRole::Current) return -1;

    const int32_t frameBytesValue = queue->frameBytes.load(std::memory_order_relaxed);
    const int32_t capacity = queue->capacityBytes.load(std::memory_order_relaxed);
    const int64_t readPosition = queue->readCursor.load(std::memory_order_relaxed);
    const int64_t writePosition = queue->writeCursor.load(std::memory_order_acquire);
    if (frameBytesValue <= 0 || capacity <= 0 || writePosition < readPosition) return -1;

    const int64_t availableLong = writePosition - readPosition;
    if (availableLong < 0 || availableLong > capacity) return -1;
    const int32_t alignedMaximum = maxLength - (maxLength % frameBytesValue);
    const int32_t available = static_cast<int32_t>(availableLong);
    const int32_t count = std::min(alignedMaximum, available - (available % frameBytesValue));
    if (count <= 0) return 0;

    copyFromRing(*queue, capacity, readPosition, destination, count);
    if (!operation.unchanged() || !identityMatches(*queue, decoderSerial, generation)) return -1;
    queue->readCursor.store(readPosition + count, std::memory_order_release);
    return operation.unchanged() ? count : -1;
}

int32_t availableBytes(int64_t decoderSerial, int32_t generation) noexcept {
    FixedQueue* queue = findQueue(decoderSerial, generation, true, true);
    if (queue == nullptr) return 0;
    const int32_t capacity = queue->capacityBytes.load(std::memory_order_acquire);
    const int64_t write = queue->writeCursor.load(std::memory_order_acquire);
    const int64_t readPosition = queue->readCursor.load(std::memory_order_acquire);
    const int64_t available = write - readPosition;
    if (capacity <= 0 || available <= 0) return 0;
    return static_cast<int32_t>(std::min<int64_t>(available, capacity));
}

int64_t availableFrames(int64_t decoderSerial, int32_t generation) noexcept {
    FixedQueue* queue = findQueue(decoderSerial, generation, true, true);
    if (queue == nullptr) return 0;
    const int32_t frameBytesValue = queue->frameBytes.load(std::memory_order_acquire);
    return frameBytesValue > 0
        ? static_cast<int64_t>(availableBytes(decoderSerial, generation) / frameBytesValue)
        : 0;
}

int64_t totalWrittenFrames(int64_t decoderSerial, int32_t generation) noexcept {
    FixedQueue* queue = findQueue(decoderSerial, generation, true, true);
    if (queue == nullptr) return 0;
    const int32_t frameBytesValue = queue->frameBytes.load(std::memory_order_acquire);
    const int64_t write = queue->writeCursor.load(std::memory_order_acquire);
    return frameBytesValue > 0 ? write / frameBytesValue : 0;
}

int32_t frameSize(int64_t decoderSerial, int32_t generation) noexcept {
    FixedQueue* queue = findQueue(decoderSerial, generation, true, true);
    return queue != nullptr ? queue->frameBytes.load(std::memory_order_acquire) : 0;
}

bool matches(
    int64_t decoderSerial,
    int32_t generation,
    QueueRole expectedRole
) noexcept {
    FixedQueue* queue = findQueue(
        decoderSerial,
        generation,
        expectedRole == QueueRole::Pending,
        expectedRole == QueueRole::Current
    );
    return queue != nullptr && loadRole(*queue) == expectedRole;
}

bool isFrameAligned(int64_t decoderSerial, int32_t generation) noexcept {
    FixedQueue* queue = findQueue(decoderSerial, generation, true, true);
    if (queue == nullptr) return false;
    const int32_t frameBytesValue = queue->frameBytes.load(std::memory_order_acquire);
    const int64_t write = queue->writeCursor.load(std::memory_order_acquire);
    const int64_t readPosition = queue->readCursor.load(std::memory_order_acquire);
    return frameBytesValue > 0 && write >= readPosition &&
        (write % frameBytesValue) == 0 && (readPosition % frameBytesValue) == 0;
}

bool activatePending(int64_t decoderSerial, int32_t generation) noexcept {
    std::lock_guard<std::mutex> lock(gControlMutex);
    FixedQueue* pending = nullptr;
    FixedQueue* current = nullptr;
    for (FixedQueue& queue : gQueues) {
        const QueueRole role = loadRole(queue);
        if (role == QueueRole::Pending && identityMatches(queue, decoderSerial, generation)) {
            pending = &queue;
        } else if (role == QueueRole::Current) {
            current = &queue;
        }
    }
    if (pending == nullptr || !isFrameAligned(decoderSerial, generation)) return false;

    // Freeze the pending queue before changing its role. This prevents a late
    // priming append from racing the first current-slot read after activation.
    beginMutationLocked(*pending);
    if (!identityMatches(*pending, decoderSerial, generation)) {
        endMutationLocked(*pending);
        return false;
    }
    if (current != nullptr && current != pending) resetQueueLocked(*current);
    pending->role.store(static_cast<int32_t>(QueueRole::Current), std::memory_order_release);
    endMutationLocked(*pending);
    gRevision.fetch_add(1, std::memory_order_relaxed);
    return true;
}

void retirePending(int64_t decoderSerial, int32_t generation) noexcept {
    std::lock_guard<std::mutex> lock(gControlMutex);
    for (FixedQueue& queue : gQueues) {
        if (loadRole(queue) == QueueRole::Pending &&
            (decoderSerial == 0 || identityMatches(queue, decoderSerial, generation))) {
            resetQueueLocked(queue);
        }
    }
    gRevision.fetch_add(1, std::memory_order_relaxed);
}

void retireCurrent(int64_t decoderSerial, int32_t generation) noexcept {
    std::lock_guard<std::mutex> lock(gControlMutex);
    for (FixedQueue& queue : gQueues) {
        if (loadRole(queue) == QueueRole::Current &&
            (decoderSerial == 0 || identityMatches(queue, decoderSerial, generation))) {
            resetQueueLocked(queue);
        }
    }
    gRevision.fetch_add(1, std::memory_order_relaxed);
}

std::string snapshot() {
    std::lock_guard<std::mutex> lock(gControlMutex);
    char text[1024];
    int offset = std::snprintf(
        text,
        sizeof(text),
        "revision=%llu",
        static_cast<unsigned long long>(gRevision.load(std::memory_order_relaxed))
    );
    if (offset < 0) return {};
    for (int32_t index = 0; index < kQueueCount && offset < static_cast<int>(sizeof(text)); ++index) {
        const FixedQueue& queue = gQueues[static_cast<size_t>(index)];
        const QueueRole role = loadRole(queue);
        const int64_t written = queue.writeCursor.load(std::memory_order_acquire);
        const int64_t readPosition = queue.readCursor.load(std::memory_order_acquire);
        const int64_t available = std::max<int64_t>(0, written - readPosition);
        const int count = std::snprintf(
            text + offset,
            sizeof(text) - static_cast<size_t>(offset),
            " q%d{role=%s serial=%lld gen=%d frame=%d cap=%d write=%lld read=%lld avail=%lld active=%d}",
            index,
            roleName(role),
            static_cast<long long>(queue.decoderSerial.load(std::memory_order_acquire)),
            queue.generation.load(std::memory_order_acquire),
            queue.frameBytes.load(std::memory_order_acquire),
            queue.capacityBytes.load(std::memory_order_acquire),
            static_cast<long long>(written),
            static_cast<long long>(readPosition),
            static_cast<long long>(available),
            queue.activeOperations.load(std::memory_order_acquire)
        );
        if (count <= 0) break;
        offset += count;
    }
    const size_t length = std::min(static_cast<size_t>(std::max(offset, 0)), sizeof(text) - 1U);
    return std::string(text, length);
}

}  // namespace rawsmusic::pcm_slot_queue
