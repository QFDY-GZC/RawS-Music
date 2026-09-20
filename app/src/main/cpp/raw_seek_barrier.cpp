#include "raw_seek_barrier.h"

#include <algorithm>

namespace rawsmusic::seek_barrier {

uint64_t Barrier::pack(uint64_t serial, Phase phase) noexcept {
    return ((serial & kSerialMask) << 8U) |
        (static_cast<uint64_t>(phase) & kPhaseMask);
}

uint64_t Barrier::serial_of(uint64_t word) noexcept {
    return (word >> 8U) & kSerialMask;
}

Phase Barrier::phase_of(uint64_t word) noexcept {
    switch (static_cast<uint8_t>(word & kPhaseMask)) {
        case static_cast<uint8_t>(Phase::FadeOutPending): return Phase::FadeOutPending;
        case static_cast<uint8_t>(Phase::DecoderPending): return Phase::DecoderPending;
        case static_cast<uint8_t>(Phase::OutputPending): return Phase::OutputPending;
        case static_cast<uint8_t>(Phase::PausedCommitted): return Phase::PausedCommitted;
        case static_cast<uint8_t>(Phase::Completed): return Phase::Completed;
        case static_cast<uint8_t>(Phase::Cancelled): return Phase::Cancelled;
        case static_cast<uint8_t>(Phase::Failed): return Phase::Failed;
        default: return Phase::Idle;
    }
}

bool Barrier::arm(
    uint64_t serial,
    int64_t target_ms,
    bool keep_paused,
    bool require_fade_out
) noexcept {
    if (serial == 0 || serial > kSerialMask) return false;
    target_ms_.store(std::max<int64_t>(0, target_ms), std::memory_order_relaxed);
    keep_paused_.store(keep_paused, std::memory_order_relaxed);
    const Phase initial = require_fade_out && !keep_paused
        ? Phase::FadeOutPending
        : Phase::DecoderPending;
    state_.store(pack(serial, initial), std::memory_order_release);
    return true;
}

bool Barrier::transition(uint64_t serial, Phase expected, Phase next) noexcept {
    uint64_t expected_word = pack(serial, expected);
    return state_.compare_exchange_strong(
        expected_word,
        pack(serial, next),
        std::memory_order_acq_rel,
        std::memory_order_acquire
    );
}

bool Barrier::mark_fade_out_complete(uint64_t serial) noexcept {
    return transition(serial, Phase::FadeOutPending, Phase::DecoderPending);
}

bool Barrier::is_current(uint64_t serial) const noexcept {
    const uint64_t word = state_.load(std::memory_order_acquire);
    if (serial_of(word) != serial) return false;
    switch (phase_of(word)) {
        case Phase::FadeOutPending:
        case Phase::DecoderPending:
        case Phase::OutputPending:
        case Phase::PausedCommitted:
            return true;
        default:
            return false;
    }
}

bool Barrier::can_start_decoder(uint64_t serial) const noexcept {
    return state_.load(std::memory_order_acquire) == pack(serial, Phase::DecoderPending);
}

bool Barrier::mark_decoder_committed(uint64_t serial) noexcept {
    const Phase next = keep_paused_.load(std::memory_order_acquire)
        ? Phase::PausedCommitted
        : Phase::OutputPending;
    return transition(serial, Phase::DecoderPending, next);
}

bool Barrier::mark_output_committed(uint64_t serial) noexcept {
    return transition(serial, Phase::OutputPending, Phase::Completed);
}

bool Barrier::release_paused(uint64_t serial) noexcept {
    return transition(serial, Phase::PausedCommitted, Phase::Completed);
}

bool Barrier::transition_any_active(uint64_t serial, Phase next) noexcept {
    uint64_t current = state_.load(std::memory_order_acquire);
    while (serial_of(current) == serial) {
        const Phase phase = phase_of(current);
        if (phase == Phase::Idle || phase == Phase::Completed ||
            phase == Phase::Cancelled || phase == Phase::Failed) {
            return false;
        }
        if (state_.compare_exchange_weak(
                current,
                pack(serial, next),
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            return true;
        }
    }
    return false;
}

bool Barrier::cancel(uint64_t serial) noexcept {
    return transition_any_active(serial, Phase::Cancelled);
}

bool Barrier::fail(uint64_t serial) noexcept {
    return transition_any_active(serial, Phase::Failed);
}

Snapshot Barrier::snapshot() const noexcept {
    const uint64_t word = state_.load(std::memory_order_acquire);
    Snapshot result{};
    result.serial = serial_of(word);
    result.phase = phase_of(word);
    result.target_ms = target_ms_.load(std::memory_order_acquire);
    result.keep_paused = keep_paused_.load(std::memory_order_acquire);
    return result;
}

}  // namespace rawsmusic::seek_barrier
