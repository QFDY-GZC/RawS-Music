#pragma once

#include <atomic>
#include <cstdint>

namespace rawsmusic::seek_barrier {

enum class Phase : uint8_t {
    Idle = 0,
    FadeOutPending = 1,
    DecoderPending = 2,
    OutputPending = 3,
    PausedCommitted = 4,
    Completed = 5,
    Cancelled = 6,
    Failed = 7,
};

struct Snapshot {
    uint64_t serial = 0;
    int64_t target_ms = -1;
    Phase phase = Phase::Idle;
    bool keep_paused = false;
};

/**
 * Lock-free phase barrier for one latest seek request.
 *
 * The packed state makes serial ownership and phase transition atomic. A newer
 * arm immediately invalidates every transition from an older serial.
 */
class Barrier final {
public:
    Barrier() = default;
    Barrier(const Barrier&) = delete;
    Barrier& operator=(const Barrier&) = delete;

    bool arm(
        uint64_t serial,
        int64_t target_ms,
        bool keep_paused,
        bool require_fade_out
    ) noexcept;

    bool mark_fade_out_complete(uint64_t serial) noexcept;
    bool is_current(uint64_t serial) const noexcept;
    bool can_start_decoder(uint64_t serial) const noexcept;
    bool mark_decoder_committed(uint64_t serial) noexcept;
    bool mark_output_committed(uint64_t serial) noexcept;
    bool release_paused(uint64_t serial) noexcept;
    bool cancel(uint64_t serial) noexcept;
    bool fail(uint64_t serial) noexcept;

    Snapshot snapshot() const noexcept;

private:
    static constexpr uint64_t kPhaseMask = 0xffULL;
    static constexpr uint64_t kSerialMask = 0x00ffffffffffffffULL;

    static uint64_t pack(uint64_t serial, Phase phase) noexcept;
    static uint64_t serial_of(uint64_t word) noexcept;
    static Phase phase_of(uint64_t word) noexcept;

    bool transition(uint64_t serial, Phase expected, Phase next) noexcept;
    bool transition_any_active(uint64_t serial, Phase next) noexcept;

    std::atomic<uint64_t> state_{pack(0, Phase::Idle)};
    std::atomic<int64_t> target_ms_{-1};
    std::atomic<bool> keep_paused_{false};
};

}  // namespace rawsmusic::seek_barrier
