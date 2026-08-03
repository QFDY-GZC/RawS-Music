#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace rawsmusic::usb {

struct Uac20WriteRingStats {
    bool initialized = false;
    bool shadowMode = true;

    int frameBytes = 0;
    int capacityBytes = 0;
    int levelBytes = 0;
    int maxLevelBytes = 0;

    int64_t totalInputBytes = 0;
    int64_t totalAcceptedBytes = 0;
    int64_t totalDroppedBytes = 0;
    int64_t totalWriteCalls = 0;
    int64_t unalignedWriteCalls = 0;

    int lastWriteBytes = 0;
    int lastAcceptedBytes = 0;
    int lastDroppedBytes = 0;
    int lastAlignmentRemainder = 0;
    int appInBytesPerSecond = 0;

    int64_t firstWriteTimeMs = 0;
    int64_t lastWriteTimeMs = 0;

    int64_t totalConsumedBytes = 0;
    int64_t totalReadCalls = 0;
    int64_t underrunReadCalls = 0;
    int lastReadBytes = 0;
    int lastReadRequestedBytes = 0;
    int lastReadMissingBytes = 0;
    int appConsumedBytesPerSecond = 0;
    int64_t firstReadTimeMs = 0;
    int64_t lastReadTimeMs = 0;

    std::string lastError;
};

// Shadow-mode write ring for the v2 UAC20 bring-up path. It accepts/copies the
// Kotlin write stream, tracks frame alignment and app input rate, but does not
// feed the real ISO OUT transfer ring yet. This keeps production playback on the
// legacy engine while migrating write-side diagnostics out of usb_audio_engine.cpp.
// A read() API drains the buffered PCM in arrival order for the upcoming UAC20
// ISO OUT feed and accounts consumed bytes / app drain rate alongside the write
// side diagnostics.
class Uac20WriteRing {
public:
    Uac20WriteRing();

    void configure(int frameBytes, int bytesPerSecond, int targetBufferMs = 500);
    void reset(const char* reason);

    int write(const uint8_t* data, int length);
    int read(uint8_t* dst, int maxBytes);

    Uac20WriteRingStats snapshot() const;
    std::string summary() const;

private:
    static int64_t nowMs();
    void pushOverwriteLocked(const uint8_t* data, int length);
    void updateWriteRateLocked(int64_t now);
    void updateReadRateLocked(int64_t now);
    void advanceReadOffsetLocked(int bytes);

private:
    mutable std::mutex mutex_;
    std::vector<uint8_t> buffer_;
    int writeOffset_ = 0;
    int readOffset_ = 0;
    int levelBytes_ = 0;
    int frameBytes_ = 0;
    int bytesPerSecond_ = 0;
    Uac20WriteRingStats stats_{};
};

std::string describeUac20WriteRingStats(const Uac20WriteRingStats& stats);

} // namespace rawsmusic::usb
