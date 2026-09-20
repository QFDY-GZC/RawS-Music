#pragma once

#include <cstdint>
#include <string>

namespace rawsmusic::pcm_slot_queue {

enum class QueueRole : int32_t {
    Empty = 0,
    Current = 1,
    Pending = 2,
};

/**
 * Resets both fixed PCM slot queues.
 *
 * Queue storage is statically allocated. Resetting changes ownership metadata
 * and read/write positions but never frees memory on an audio path.
 */
void resetAll() noexcept;

/** Fixed byte capacity available to each current/pending queue. */
int32_t maximumSlotBytes() noexcept;

/** Installs the active decoder identity. Its queue starts empty. */
bool installCurrent(
    int64_t decoderSerial,
    int32_t generation,
    int32_t frameSize
) noexcept;

/**
 * Configures the inactive fixed queue as the next pending PCM prefix.
 * targetFrames is capped by the native fixed storage capacity.
 */
bool beginPending(
    int64_t decoderSerial,
    int32_t generation,
    int32_t frameSize,
    int64_t targetFrames
) noexcept;

/** Lock-free single-producer append used while priming a pending decoder. */
int32_t appendPending(
    int64_t decoderSerial,
    int32_t generation,
    const uint8_t* source,
    int32_t length
) noexcept;

/**
 * Lock-free single-consumer read. The matching queue may be PENDING or CURRENT,
 * allowing an activated decoder to drain any prefix that remained at commit.
 */
int32_t read(
    int64_t decoderSerial,
    int32_t generation,
    uint8_t* destination,
    int32_t maxLength
) noexcept;

int32_t availableBytes(int64_t decoderSerial, int32_t generation) noexcept;
int64_t availableFrames(int64_t decoderSerial, int32_t generation) noexcept;
int64_t totalWrittenFrames(int64_t decoderSerial, int32_t generation) noexcept;
int32_t frameSize(int64_t decoderSerial, int32_t generation) noexcept;

bool matches(
    int64_t decoderSerial,
    int32_t generation,
    QueueRole role
) noexcept;

bool isFrameAligned(int64_t decoderSerial, int32_t generation) noexcept;

/** Promotes the matching pending queue and retires the previous current queue. */
bool activatePending(int64_t decoderSerial, int32_t generation) noexcept;

void retirePending(int64_t decoderSerial, int32_t generation) noexcept;
void retireCurrent(int64_t decoderSerial, int32_t generation) noexcept;

std::string snapshot();

}  // namespace rawsmusic::pcm_slot_queue
