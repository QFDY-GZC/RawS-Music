#pragma once

#include <atomic>
#include <cstdint>
#include <string>

namespace rawsmusic::track_timeline {

enum class Event : uint8_t {
    None = 0,
    Started = 1,
    Committed = 2,
    Retired = 3,
    Cancelled = 4,
};

struct Update {
    Event event = Event::None;
    int64_t position_frames = 0;
};

/**
 * Render-clock track identity and position owner.
 *
 * A pending track becomes the presentation current track only after at least one
 * of its PCM frames has been accepted into the DSP/render timeline. Control-thread arm,
 * commit, and cancel operations publish through atomics; the render-thread
 * onPendingFramesRendered() path is lock-free and allocation-free.
 */
class Timeline final {
public:
    Timeline() = default;
    Timeline(const Timeline&) = delete;
    Timeline& operator=(const Timeline&) = delete;

    bool installCurrent(
        int64_t decoder_serial,
        int32_t generation,
        int32_t sample_rate,
        int64_t position_frames = 0
    ) noexcept;
    bool armPending(int64_t decoder_serial, int32_t generation, int32_t sample_rate) noexcept;
    Update onPendingFramesRendered(
        int64_t decoder_serial,
        int32_t generation,
        int64_t rendered_frames
    ) noexcept;
    Update commitPending(int64_t decoder_serial, int32_t generation) noexcept;
    Update retirePrevious(int64_t decoder_serial, int32_t generation) noexcept;
    Update cancelPending(int64_t decoder_serial, int32_t generation) noexcept;
    void reset() noexcept;

    int64_t currentSerial() const noexcept;
    int32_t currentGeneration() const noexcept;
    int32_t currentSampleRate() const noexcept;
    int64_t currentPositionFrames() const noexcept;
    bool pendingStarted(int64_t decoder_serial, int32_t generation) const noexcept;
    std::string snapshot() const;

private:
    std::atomic<int64_t> pending_serial_{0};
    std::atomic<int32_t> pending_generation_{0};
    std::atomic<int32_t> pending_sample_rate_{0};
    std::atomic<int64_t> pending_position_frames_{0};
    std::atomic<bool> pending_started_{false};
    std::atomic<bool> pending_committed_{false};

    std::atomic<int64_t> current_serial_{0};
    std::atomic<int32_t> current_generation_{0};
    std::atomic<int32_t> current_sample_rate_{0};
    std::atomic<int64_t> current_position_frames_{0};

    std::atomic<int64_t> retiring_serial_{0};
    std::atomic<int32_t> retiring_generation_{0};
};

}  // namespace rawsmusic::track_timeline
