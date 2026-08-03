#include "usb_uac20_write_ring.h"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <sstream>

namespace rawsmusic::usb {
namespace {

int clampInt(int value, int lo, int hi) {
    if (value < lo) return lo;
    if (value > hi) return hi;
    return value;
}

} // namespace

Uac20WriteRing::Uac20WriteRing() = default;

int64_t Uac20WriteRing::nowMs() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

void Uac20WriteRing::configure(int frameBytes, int bytesPerSecond, int targetBufferMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    frameBytes_ = std::max(1, frameBytes);
    bytesPerSecond_ = std::max(0, bytesPerSecond);

    int capacity = 256 * 1024;
    if (bytesPerSecond_ > 0 && targetBufferMs > 0) {
        capacity = static_cast<int>((static_cast<int64_t>(bytesPerSecond_) * targetBufferMs) / 1000);
    }
    capacity = clampInt(capacity, 256 * 1024, 2 * 1024 * 1024);
    if (frameBytes_ > 1) {
        capacity -= capacity % frameBytes_;
        if (capacity <= 0) capacity = frameBytes_ * 1024;
    }

    buffer_.assign(static_cast<size_t>(capacity), 0);
    writeOffset_ = 0;
    readOffset_ = 0;
    levelBytes_ = 0;
    stats_ = Uac20WriteRingStats{};
    stats_.initialized = true;
    stats_.shadowMode = true;
    stats_.frameBytes = frameBytes_;
    stats_.capacityBytes = capacity;
}

void Uac20WriteRing::reset(const char*) {
    std::lock_guard<std::mutex> lock(mutex_);
    std::fill(buffer_.begin(), buffer_.end(), 0);
    writeOffset_ = 0;
    readOffset_ = 0;
    levelBytes_ = 0;
    const int frameBytes = stats_.frameBytes;
    const int capacity = stats_.capacityBytes;
    stats_ = Uac20WriteRingStats{};
    stats_.initialized = capacity > 0;
    stats_.shadowMode = true;
    stats_.frameBytes = frameBytes;
    stats_.capacityBytes = capacity;
}

int Uac20WriteRing::write(const uint8_t* data, int length) {
    if (data == nullptr || length <= 0) return 0;

    std::lock_guard<std::mutex> lock(mutex_);
    if (buffer_.empty()) {
        stats_.lastError = "write ring not configured";
        return -1007;
    }

    const int64_t now = nowMs();
    if (stats_.firstWriteTimeMs == 0) stats_.firstWriteTimeMs = now;
    stats_.lastWriteTimeMs = now;

    stats_.totalWriteCalls += 1;
    stats_.totalInputBytes += length;
    stats_.lastWriteBytes = length;
    stats_.lastAlignmentRemainder = frameBytes_ > 0 ? (length % frameBytes_) : 0;
    if (stats_.lastAlignmentRemainder != 0) {
        stats_.unalignedWriteCalls += 1;
    }

    const int beforeLevel = levelBytes_;
    pushOverwriteLocked(data, length);
    const int accepted = std::min(length, static_cast<int>(buffer_.size()));
    const int afterLevel = levelBytes_;
    const int dropped = std::max(0, beforeLevel + accepted - afterLevel);

    stats_.lastAcceptedBytes = accepted;
    stats_.lastDroppedBytes = dropped;
    stats_.totalAcceptedBytes += accepted;
    stats_.totalDroppedBytes += dropped;
    stats_.levelBytes = levelBytes_;
    stats_.maxLevelBytes = std::max(stats_.maxLevelBytes, levelBytes_);
    updateWriteRateLocked(now);
    return accepted;
}

int Uac20WriteRing::read(uint8_t* dst, int maxBytes) {
    if (dst == nullptr || maxBytes <= 0) return 0;

    std::lock_guard<std::mutex> lock(mutex_);
    if (buffer_.empty()) {
        stats_.lastError = "write ring not configured";
        return -1007;
    }

    const int64_t now = nowMs();
    if (stats_.firstReadTimeMs == 0) stats_.firstReadTimeMs = now;
    stats_.lastReadTimeMs = now;
    stats_.totalReadCalls += 1;
    stats_.lastReadRequestedBytes = maxBytes;

    const int capacity = static_cast<int>(buffer_.size());
    const int toCopy = std::min(maxBytes, levelBytes_);

    int copied = 0;
    if (toCopy > 0) {
        while (copied < toCopy) {
            const int chunk = std::min(toCopy - copied, capacity - readOffset_);
            std::memcpy(dst + copied, buffer_.data() + readOffset_, static_cast<size_t>(chunk));
            readOffset_ = (readOffset_ + chunk) % capacity;
            copied += chunk;
        }
        levelBytes_ -= toCopy;
    }

    stats_.lastReadBytes = copied;
    stats_.lastReadMissingBytes = maxBytes - copied;
    if (copied < maxBytes) {
        stats_.underrunReadCalls += 1;
    }
    stats_.totalConsumedBytes += copied;
    stats_.levelBytes = levelBytes_;
    updateReadRateLocked(now);
    return copied;
}

void Uac20WriteRing::pushOverwriteLocked(const uint8_t* data, int length) {
    const int capacity = static_cast<int>(buffer_.size());
    if (capacity <= 0) return;

    const uint8_t* src = data;
    int remaining = length;
    if (remaining >= capacity) {
        src = data + (remaining - capacity);
        remaining = capacity;
        levelBytes_ = 0;
        writeOffset_ = 0;
        readOffset_ = 0;
    }

    const int overflow = std::max(0, levelBytes_ + remaining - capacity);
    if (overflow > 0) {
        advanceReadOffsetLocked(overflow);
        levelBytes_ -= overflow;
        if (levelBytes_ < 0) levelBytes_ = 0;
    }

    int copied = 0;
    while (copied < remaining) {
        const int chunk = std::min(remaining - copied, capacity - writeOffset_);
        std::memcpy(buffer_.data() + writeOffset_, src + copied, static_cast<size_t>(chunk));
        writeOffset_ = (writeOffset_ + chunk) % capacity;
        copied += chunk;
    }
    levelBytes_ = std::min(capacity, levelBytes_ + remaining);
}

void Uac20WriteRing::advanceReadOffsetLocked(int bytes) {
    const int capacity = static_cast<int>(buffer_.size());
    if (capacity <= 0) return;
    readOffset_ = (readOffset_ + bytes) % capacity;
}

void Uac20WriteRing::updateWriteRateLocked(int64_t now) {
    const int64_t elapsed = now - stats_.firstWriteTimeMs;
    if (elapsed > 0) {
        stats_.appInBytesPerSecond = static_cast<int>(
                (stats_.totalInputBytes * 1000LL) / elapsed);
    }
}

void Uac20WriteRing::updateReadRateLocked(int64_t now) {
    const int64_t elapsed = now - stats_.firstReadTimeMs;
    if (elapsed > 0) {
        stats_.appConsumedBytesPerSecond = static_cast<int>(
                (stats_.totalConsumedBytes * 1000LL) / elapsed);
    }
}

Uac20WriteRingStats Uac20WriteRing::snapshot() const {
    std::lock_guard<std::mutex> lock(mutex_);
    Uac20WriteRingStats copy = stats_;
    copy.levelBytes = levelBytes_;
    if (copy.firstWriteTimeMs != 0) {
        const int64_t elapsed = nowMs() - copy.firstWriteTimeMs;
        if (elapsed > 0) {
            copy.appInBytesPerSecond = static_cast<int>((copy.totalInputBytes * 1000LL) / elapsed);
        }
    }
    if (copy.firstReadTimeMs != 0) {
        const int64_t elapsed = nowMs() - copy.firstReadTimeMs;
        if (elapsed > 0) {
            copy.appConsumedBytesPerSecond = static_cast<int>((copy.totalConsumedBytes * 1000LL) / elapsed);
        }
    }
    return copy;
}

std::string Uac20WriteRing::summary() const {
    return describeUac20WriteRingStats(snapshot());
}

std::string describeUac20WriteRingStats(const Uac20WriteRingStats& stats) {
    std::ostringstream os;
    os << "initialized=" << (stats.initialized ? 1 : 0)
       << " shadow=" << (stats.shadowMode ? 1 : 0)
       << " frameBytes=" << stats.frameBytes
       << " capacity=" << stats.capacityBytes
       << " level=" << stats.levelBytes
       << " maxLevel=" << stats.maxLevelBytes
       << " calls=" << stats.totalWriteCalls
       << " appIn=" << stats.appInBytesPerSecond
       << " totalIn=" << stats.totalInputBytes
       << " accepted=" << stats.totalAcceptedBytes
       << " dropped=" << stats.totalDroppedBytes
       << " unaligned=" << stats.unalignedWriteCalls
       << " lastWrite=" << stats.lastWriteBytes
       << " lastAccepted=" << stats.lastAcceptedBytes
       << " lastDropped=" << stats.lastDroppedBytes
       << " lastRem=" << stats.lastAlignmentRemainder
       << " readCalls=" << stats.totalReadCalls
       << " appConsumed=" << stats.appConsumedBytesPerSecond
       << " consumed=" << stats.totalConsumedBytes
       << " underrunReads=" << stats.underrunReadCalls
       << " lastRead=" << stats.lastReadBytes
       << " lastReadReq=" << stats.lastReadRequestedBytes
       << " lastReadMissing=" << stats.lastReadMissingBytes;
    if (!stats.lastError.empty()) {
        os << " error=" << stats.lastError;
    }
    return os.str();
}

} // namespace rawsmusic::usb
