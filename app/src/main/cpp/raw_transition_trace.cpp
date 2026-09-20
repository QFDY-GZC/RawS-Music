#include "raw_transition_trace.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdio>

namespace rawsmusic::transition_trace {
namespace {

constexpr size_t kCapacity = 64;

struct Context {
    int64_t sessionId = 0;
    int64_t transitionId = 0;
    int32_t generation = 0;
    int64_t outputGeneration = 0;
    int32_t reason = -1;
};

struct EventSlot {
    std::atomic<uint64_t> publishedSequence{0};
    std::atomic<int64_t> timestampNanos{0};
    std::atomic<int64_t> sessionId{0};
    std::atomic<int64_t> transitionId{0};
    std::atomic<int32_t> generation{0};
    std::atomic<int64_t> outputGeneration{0};
    std::atomic<int32_t> reason{-1};
    std::atomic<int32_t> code{0};
    std::atomic<int64_t> arg0{0};
    std::atomic<int64_t> arg1{0};
};

// A tiny seqlock keeps a transition context coherent without making an audio
// callback wait for a control-thread mutex.
std::atomic_flag gContextWriter = ATOMIC_FLAG_INIT;
std::atomic<uint64_t> gContextVersion{0};
std::atomic<int64_t> gSessionId{0};
std::atomic<int64_t> gTransitionId{0};
std::atomic<int32_t> gGeneration{0};
std::atomic<int64_t> gOutputGeneration{0};
std::atomic<int32_t> gReason{-1};

std::array<EventSlot, kCapacity> gEvents{};
std::atomic<uint64_t> gSequence{0};

int64_t monotonicNanos() noexcept {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

Context loadContext() noexcept {
    Context result{};
    for (int attempt = 0; attempt < 3; ++attempt) {
        const uint64_t before = gContextVersion.load(std::memory_order_acquire);
        if ((before & 1U) != 0U) continue;
        result.sessionId = gSessionId.load(std::memory_order_relaxed);
        result.transitionId = gTransitionId.load(std::memory_order_relaxed);
        result.generation = gGeneration.load(std::memory_order_relaxed);
        result.outputGeneration = gOutputGeneration.load(std::memory_order_relaxed);
        result.reason = gReason.load(std::memory_order_relaxed);
        const uint64_t after = gContextVersion.load(std::memory_order_acquire);
        if (before == after && (after & 1U) == 0U) return result;
    }
    // A mixed diagnostic context is preferable to blocking the render thread.
    return result;
}

}  // namespace

void setContext(
    int64_t sessionId,
    int64_t transitionId,
    int32_t generation,
    int64_t outputGeneration,
    int32_t reason
) noexcept {
    while (gContextWriter.test_and_set(std::memory_order_acquire)) {}
    gContextVersion.fetch_add(1, std::memory_order_acq_rel);  // odd: writer active
    gSessionId.store(sessionId, std::memory_order_relaxed);
    gTransitionId.store(transitionId, std::memory_order_relaxed);
    gGeneration.store(generation, std::memory_order_relaxed);
    gOutputGeneration.store(outputGeneration, std::memory_order_relaxed);
    gReason.store(reason, std::memory_order_relaxed);
    gContextVersion.fetch_add(1, std::memory_order_release);  // even: stable
    gContextWriter.clear(std::memory_order_release);
}

void record(EventCode code, int64_t arg0, int64_t arg1) noexcept {
    const uint64_t sequence = gSequence.fetch_add(1, std::memory_order_relaxed) + 1U;
    EventSlot& slot = gEvents[(sequence - 1U) % kCapacity];

    // Hide the slot while it is overwritten. The final release store publishes
    // all fields atomically from the snapshot reader's point of view.
    slot.publishedSequence.store(0, std::memory_order_relaxed);
    const Context context = loadContext();
    slot.timestampNanos.store(monotonicNanos(), std::memory_order_relaxed);
    slot.sessionId.store(context.sessionId, std::memory_order_relaxed);
    slot.transitionId.store(context.transitionId, std::memory_order_relaxed);
    slot.generation.store(context.generation, std::memory_order_relaxed);
    slot.outputGeneration.store(context.outputGeneration, std::memory_order_relaxed);
    slot.reason.store(context.reason, std::memory_order_relaxed);
    slot.code.store(static_cast<int32_t>(code), std::memory_order_relaxed);
    slot.arg0.store(arg0, std::memory_order_relaxed);
    slot.arg1.store(arg1, std::memory_order_relaxed);
    slot.publishedSequence.store(sequence, std::memory_order_release);
}

std::string snapshot() {
    const uint64_t newest = gSequence.load(std::memory_order_acquire);
    if (newest == 0) return {};
    const uint64_t oldest = newest > kCapacity ? newest - kCapacity + 1U : 1U;

    std::string result;
    result.reserve(static_cast<size_t>(newest - oldest + 1U) * 112U);
    for (uint64_t expected = oldest; expected <= newest; ++expected) {
        const EventSlot& slot = gEvents[(expected - 1U) % kCapacity];
        const uint64_t before = slot.publishedSequence.load(std::memory_order_acquire);
        if (before != expected) continue;

        const int64_t timestamp = slot.timestampNanos.load(std::memory_order_relaxed);
        Context context{};
        context.sessionId = slot.sessionId.load(std::memory_order_relaxed);
        context.transitionId = slot.transitionId.load(std::memory_order_relaxed);
        context.generation = slot.generation.load(std::memory_order_relaxed);
        context.outputGeneration = slot.outputGeneration.load(std::memory_order_relaxed);
        context.reason = slot.reason.load(std::memory_order_relaxed);
        const int32_t code = slot.code.load(std::memory_order_relaxed);
        const int64_t arg0 = slot.arg0.load(std::memory_order_relaxed);
        const int64_t arg1 = slot.arg1.load(std::memory_order_relaxed);

        const uint64_t after = slot.publishedSequence.load(std::memory_order_acquire);
        if (after != expected) continue;

        char line[256];
        const int length = std::snprintf(
            line,
            sizeof(line),
            "seq=%llu t=%lld session=%lld transition=%lld generation=%d outputGen=%lld reason=%d code=%d arg0=%lld arg1=%lld\n",
            static_cast<unsigned long long>(expected),
            static_cast<long long>(timestamp),
            static_cast<long long>(context.sessionId),
            static_cast<long long>(context.transitionId),
            context.generation,
            static_cast<long long>(context.outputGeneration),
            context.reason,
            code,
            static_cast<long long>(arg0),
            static_cast<long long>(arg1)
        );
        if (length > 0) result.append(line, static_cast<size_t>(length));
    }
    return result;
}

}  // namespace rawsmusic::transition_trace
