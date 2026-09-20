#include "raw_track_slot_state.h"

#include "raw_pcm_slot_queue.h"

#include <algorithm>
#include <cstdio>
#include <mutex>

namespace rawsmusic::track_slot {
namespace {

struct Slot {
    int64_t decoderSerial = 0;
    int32_t generation = 0;
    AudioFormat format{};
    SlotState state = SlotState::Empty;
    int64_t readyFrames = 0;
    int64_t minimumReadyFrames = 0;
    int64_t targetReadyFrames = 0;
    int64_t reportedReadyFrames = 0;
    bool eofDuringPrime = false;
    int32_t retireReason = 0;
};

std::mutex gMutex;
Slot gCurrent{};
Slot gPending{};
uint64_t gRevision = 0;

void clearSlot(Slot& slot) noexcept {
    slot = Slot{};
}

bool validFormat(const AudioFormat& format) noexcept {
    return format.sampleRate > 0 && format.channels > 0 && format.bitsPerSample > 0;
}

int32_t decoderBytesPerSample(int32_t bitsPerSample) noexcept {
    if (bitsPerSample <= 1) return 1;
    if (bitsPerSample <= 16) return 2;
    return 4;
}

int32_t frameSize(const AudioFormat& format) noexcept {
    if (!validFormat(format)) return 0;
    return format.channels * decoderBytesPerSample(format.bitsPerSample);
}

bool matches(const Slot& slot, int64_t decoderSerial, int32_t generation) noexcept {
    return decoderSerial != 0 && slot.decoderSerial == decoderSerial &&
        slot.generation == generation;
}

const char* stateName(SlotState state) noexcept {
    switch (state) {
        case SlotState::Empty: return "EMPTY";
        case SlotState::Opening: return "OPENING";
        case SlotState::Priming: return "PRIMING";
        case SlotState::Ready: return "READY";
        case SlotState::Active: return "ACTIVE";
        case SlotState::Draining: return "DRAINING";
        case SlotState::Retired: return "RETIRED";
        case SlotState::Failed: return "FAILED";
    }
    return "UNKNOWN";
}

}  // namespace

void resetAll() noexcept {
    std::lock_guard<std::mutex> lock(gMutex);
    clearSlot(gCurrent);
    clearSlot(gPending);
    pcm_slot_queue::resetAll();
    ++gRevision;
}

void installCurrent(
    int64_t decoderSerial,
    int32_t generation,
    AudioFormat format
) noexcept {
    std::lock_guard<std::mutex> lock(gMutex);
    clearSlot(gCurrent);
    gCurrent.decoderSerial = decoderSerial;
    gCurrent.generation = generation;
    gCurrent.format = format;
    const int32_t bytesPerFrame = frameSize(format);
    const bool queueInstalled = decoderSerial != 0 && bytesPerFrame > 0 &&
        pcm_slot_queue::installCurrent(decoderSerial, generation, bytesPerFrame);
    gCurrent.state = queueInstalled ? SlotState::Active : SlotState::Failed;
    ++gRevision;
}

BeginPendingStatus beginPending(
    int64_t decoderSerial,
    int32_t generation,
    AudioFormat format,
    int64_t minimumReadyFrames,
    int64_t targetReadyFrames
) noexcept {
    std::lock_guard<std::mutex> lock(gMutex);
    clearSlot(gPending);
    gPending.decoderSerial = decoderSerial;
    gPending.generation = generation;
    gPending.format = format;

    if (decoderSerial == 0 || generation < 0) {
        gPending.state = SlotState::Failed;
        ++gRevision;
        return BeginPendingStatus::InvalidIdentity;
    }
    const int32_t bytesPerFrame = frameSize(format);
    if (bytesPerFrame <= 0) {
        gPending.state = SlotState::Failed;
        ++gRevision;
        return BeginPendingStatus::InvalidFormat;
    }
    if (minimumReadyFrames <= 0 || targetReadyFrames < minimumReadyFrames) {
        gPending.state = SlotState::Failed;
        ++gRevision;
        return BeginPendingStatus::InvalidWatermark;
    }
    const int64_t maximumFrames =
        static_cast<int64_t>(pcm_slot_queue::maximumSlotBytes()) / bytesPerFrame;
    if (targetReadyFrames > maximumFrames) {
        gPending.state = SlotState::Failed;
        ++gRevision;
        return BeginPendingStatus::InvalidWatermark;
    }

    gPending.minimumReadyFrames = minimumReadyFrames;
    gPending.targetReadyFrames = targetReadyFrames;
    const bool queueConfigured = pcm_slot_queue::beginPending(
        decoderSerial,
        generation,
        bytesPerFrame,
        targetReadyFrames
    );
    gPending.state = queueConfigured ? SlotState::Priming : SlotState::Failed;
    ++gRevision;
    return queueConfigured
        ? BeginPendingStatus::Configured
        : BeginPendingStatus::QueueRejected;
}

bool updatePendingReady(
    int64_t decoderSerial,
    int32_t generation,
    int64_t readyFramesReported,
    bool eofDuringPrime
) noexcept {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!matches(gPending, decoderSerial, generation)) return false;
    if (gPending.state != SlotState::Priming && gPending.state != SlotState::Ready) return false;

    const int64_t actualReadyFrames = pcm_slot_queue::availableFrames(decoderSerial, generation);
    gPending.reportedReadyFrames = readyFramesReported > 0 ? readyFramesReported : 0;
    gPending.readyFrames = actualReadyFrames > 0 ? actualReadyFrames : 0;
    gPending.eofDuringPrime = eofDuringPrime;
    const bool queueAligned = pcm_slot_queue::isFrameAligned(decoderSerial, generation);
    const bool ready = queueAligned && (
        gPending.readyFrames >= gPending.minimumReadyFrames ||
        (gPending.eofDuringPrime && gPending.readyFrames > 0)
    );
    gPending.state = ready ? SlotState::Ready : SlotState::Priming;
    ++gRevision;
    return ready;
}

bool isPendingReady(int64_t decoderSerial, int32_t generation) noexcept {
    std::lock_guard<std::mutex> lock(gMutex);
    return matches(gPending, decoderSerial, generation) &&
        gPending.state == SlotState::Ready &&
        pcm_slot_queue::matches(
            decoderSerial,
            generation,
            pcm_slot_queue::QueueRole::Pending
        ) &&
        pcm_slot_queue::isFrameAligned(decoderSerial, generation);
}

bool commitPending(int64_t decoderSerial, int32_t generation) noexcept {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!matches(gPending, decoderSerial, generation) ||
        gPending.state != SlotState::Ready) {
        return false;
    }
    if (!pcm_slot_queue::activatePending(decoderSerial, generation)) return false;

    if (gCurrent.state == SlotState::Active) gCurrent.state = SlotState::Retired;
    gCurrent = gPending;
    gCurrent.state = SlotState::Active;
    clearSlot(gPending);
    ++gRevision;
    return true;
}

void retirePending(
    int64_t decoderSerial,
    int32_t generation,
    int32_t reasonCode
) noexcept {
    std::lock_guard<std::mutex> lock(gMutex);
    if (decoderSerial != 0 && !matches(gPending, decoderSerial, generation)) return;
    gPending.state = SlotState::Retired;
    gPending.retireReason = reasonCode;
    pcm_slot_queue::retirePending(decoderSerial, generation);
    clearSlot(gPending);
    ++gRevision;
}

void retireCurrent(
    int64_t decoderSerial,
    int32_t generation,
    int32_t reasonCode
) noexcept {
    std::lock_guard<std::mutex> lock(gMutex);
    if (decoderSerial != 0 && !matches(gCurrent, decoderSerial, generation)) return;
    gCurrent.state = SlotState::Retired;
    gCurrent.retireReason = reasonCode;
    pcm_slot_queue::retireCurrent(decoderSerial, generation);
    clearSlot(gCurrent);
    ++gRevision;
}

std::string snapshot() {
    std::lock_guard<std::mutex> lock(gMutex);
    char text[1024];
    const int length = std::snprintf(
        text,
        sizeof(text),
        "revision=%llu current{serial=%lld gen=%d state=%s format=%d/%d/%d ready=%lld min=%lld target=%lld reported=%lld eof=%d} "
        "pending{serial=%lld gen=%d state=%s format=%d/%d/%d ready=%lld min=%lld target=%lld reported=%lld eof=%d} pcm{%s}",
        static_cast<unsigned long long>(gRevision),
        static_cast<long long>(gCurrent.decoderSerial),
        gCurrent.generation,
        stateName(gCurrent.state),
        gCurrent.format.sampleRate,
        gCurrent.format.bitsPerSample,
        gCurrent.format.channels,
        static_cast<long long>(gCurrent.readyFrames),
        static_cast<long long>(gCurrent.minimumReadyFrames),
        static_cast<long long>(gCurrent.targetReadyFrames),
        static_cast<long long>(gCurrent.reportedReadyFrames),
        gCurrent.eofDuringPrime ? 1 : 0,
        static_cast<long long>(gPending.decoderSerial),
        gPending.generation,
        stateName(gPending.state),
        gPending.format.sampleRate,
        gPending.format.bitsPerSample,
        gPending.format.channels,
        static_cast<long long>(gPending.readyFrames),
        static_cast<long long>(gPending.minimumReadyFrames),
        static_cast<long long>(gPending.targetReadyFrames),
        static_cast<long long>(gPending.reportedReadyFrames),
        gPending.eofDuringPrime ? 1 : 0,
        pcm_slot_queue::snapshot().c_str()
    );
    if (length <= 0) return {};
    const size_t safeLength = std::min(
        static_cast<size_t>(length),
        sizeof(text) - 1U
    );
    return std::string(text, safeLength);
}

}  // namespace rawsmusic::track_slot
