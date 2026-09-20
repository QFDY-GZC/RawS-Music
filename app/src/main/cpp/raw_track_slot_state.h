#pragma once

#include <cstdint>
#include <string>

namespace rawsmusic::track_slot {

enum class SlotState : int32_t {
    Empty = 0,
    Opening = 1,
    Priming = 2,
    Ready = 3,
    Active = 4,
    Draining = 5,
    Retired = 6,
    Failed = 7,
};

struct AudioFormat {
    int32_t sampleRate = 0;
    int32_t channels = 0;
    int32_t bitsPerSample = 0;
};

void resetAll() noexcept;

void installCurrent(
    int64_t decoderSerial,
    int32_t generation,
    AudioFormat format
) noexcept;

enum class BeginPendingStatus : int32_t {
    Configured = 1,
    InvalidIdentity = -1,
    InvalidFormat = -2,
    InvalidWatermark = -3,
    QueueRejected = -4,
};

/**
 * Starts a pending slot and its fixed native PCM queue atomically.
 * targetReadyFrames controls queue capacity; minimumReadyFrames controls READY.
 * The returned status is the authoritative proof that the native queue owns the
 * decoder identity; Kotlin must not re-probe the queue through a second JNI call.
 */
BeginPendingStatus beginPending(
    int64_t decoderSerial,
    int32_t generation,
    AudioFormat format,
    int64_t minimumReadyFrames,
    int64_t targetReadyFrames
) noexcept;

/**
 * Publishes READY from the actual native queue watermark. readyFramesReported
 * is retained only as a diagnostic consistency check.
 */
bool updatePendingReady(
    int64_t decoderSerial,
    int32_t generation,
    int64_t readyFramesReported,
    bool eofDuringPrime
) noexcept;

bool isPendingReady(int64_t decoderSerial, int32_t generation) noexcept;

/** Atomically promotes the matching READY pending slot and its PCM queue. */
bool commitPending(int64_t decoderSerial, int32_t generation) noexcept;

void retirePending(
    int64_t decoderSerial,
    int32_t generation,
    int32_t reasonCode
) noexcept;

void retireCurrent(
    int64_t decoderSerial,
    int32_t generation,
    int32_t reasonCode
) noexcept;

std::string snapshot();

}  // namespace rawsmusic::track_slot
