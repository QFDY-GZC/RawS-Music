#include "raw_render_handoff_barrier.h"

#include "raw_pcm_slot_queue.h"
#include "raw_track_slot_state.h"

#include <algorithm>
#include <cstdio>
#include <mutex>

namespace rawsmusic::render_handoff {
namespace {

struct BarrierState {
    int64_t decoderSerial = 0;
    int32_t generation = 0;
    HandoffMode mode = HandoffMode::Gapless;
    int32_t frameSize = 0;
    int64_t completedBlockFrames = 0;
    int32_t cancelReason = 0;
    uint64_t boundarySequence = 0;
    bool armed = false;
    bool committed = false;
};

std::mutex gMutex;
BarrierState gState{};
uint64_t gRevision = 0;

bool matches(int64_t decoderSerial, int32_t generation) noexcept {
    return gState.armed && gState.decoderSerial == decoderSerial &&
        gState.generation == generation;
}

const char* modeName(HandoffMode mode) noexcept {
    switch (mode) {
        case HandoffMode::Gapless: return "GAPLESS";
        case HandoffMode::Crossfade: return "CROSSFADE";
    }
    return "UNKNOWN";
}

}  // namespace

void reset() noexcept {
    std::lock_guard<std::mutex> lock(gMutex);
    gState = BarrierState{};
    ++gRevision;
}

bool arm(
    int64_t decoderSerial,
    int32_t generation,
    HandoffMode mode,
    int32_t frameSizeValue
) noexcept {
    if (decoderSerial == 0 || generation < 0 || frameSizeValue <= 0) return false;
    if (!track_slot::isPendingReady(decoderSerial, generation)) return false;
    if (!pcm_slot_queue::matches(
            decoderSerial,
            generation,
            pcm_slot_queue::QueueRole::Pending
        ) ||
        pcm_slot_queue::frameSize(decoderSerial, generation) != frameSizeValue ||
        !pcm_slot_queue::isFrameAligned(decoderSerial, generation)) {
        return false;
    }

    std::lock_guard<std::mutex> lock(gMutex);
    const uint64_t nextSequence = gState.boundarySequence + 1U;
    gState = BarrierState{};
    gState.decoderSerial = decoderSerial;
    gState.generation = generation;
    gState.mode = mode;
    gState.frameSize = frameSizeValue;
    gState.boundarySequence = nextSequence;
    gState.armed = true;
    ++gRevision;
    return true;
}

bool commitAtBlockBoundary(
    int64_t decoderSerial,
    int32_t generation,
    int64_t completedBlockFrames
) noexcept {
    HandoffMode mode;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        if (!matches(decoderSerial, generation) || gState.committed) return false;
        mode = gState.mode;
        if (completedBlockFrames < 0 ||
            (mode == HandoffMode::Crossfade && completedBlockFrames <= 0)) {
            return false;
        }
        gState.completedBlockFrames = completedBlockFrames;
    }

    if (!track_slot::isPendingReady(decoderSerial, generation) ||
        !pcm_slot_queue::isFrameAligned(decoderSerial, generation)) {
        return false;
    }
    const bool committed = track_slot::commitPending(decoderSerial, generation);
    if (!committed) return false;

    std::lock_guard<std::mutex> lock(gMutex);
    if (!matches(decoderSerial, generation)) return false;
    gState.committed = true;
    gState.armed = false;
    ++gRevision;
    return true;
}

void cancel(int64_t decoderSerial, int32_t generation, int32_t reasonCode) noexcept {
    std::lock_guard<std::mutex> lock(gMutex);
    if (decoderSerial != 0 && !matches(decoderSerial, generation)) return;
    gState.cancelReason = reasonCode;
    gState.armed = false;
    ++gRevision;
}

std::string snapshot() {
    std::lock_guard<std::mutex> lock(gMutex);
    char text[512];
    const int length = std::snprintf(
        text,
        sizeof(text),
        "revision=%llu serial=%lld gen=%d mode=%s frameSize=%d completedBlockFrames=%lld "
        "sequence=%llu armed=%d committed=%d cancelReason=%d",
        static_cast<unsigned long long>(gRevision),
        static_cast<long long>(gState.decoderSerial),
        gState.generation,
        modeName(gState.mode),
        gState.frameSize,
        static_cast<long long>(gState.completedBlockFrames),
        static_cast<unsigned long long>(gState.boundarySequence),
        gState.armed ? 1 : 0,
        gState.committed ? 1 : 0,
        gState.cancelReason
    );
    if (length <= 0) return {};
    const size_t safeLength = std::min(
        static_cast<size_t>(length),
        sizeof(text) - 1U
    );
    return std::string(text, safeLength);
}

}  // namespace rawsmusic::render_handoff
