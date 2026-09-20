#include "raw_track_render_timeline.h"

#include <algorithm>
#include <sstream>

namespace rawsmusic::track_timeline {

bool Timeline::installCurrent(
    int64_t decoder_serial,
    int32_t generation,
    int32_t sample_rate,
    int64_t position_frames
) noexcept {
    if (decoder_serial <= 0 || generation < 0 || sample_rate <= 0) return false;
    current_generation_.store(generation, std::memory_order_relaxed);
    current_sample_rate_.store(sample_rate, std::memory_order_relaxed);
    current_position_frames_.store(std::max<int64_t>(0, position_frames), std::memory_order_relaxed);
    current_serial_.store(decoder_serial, std::memory_order_release);
    retiring_serial_.store(0, std::memory_order_release);
    retiring_generation_.store(0, std::memory_order_relaxed);
    return true;
}

bool Timeline::armPending(
    int64_t decoder_serial,
    int32_t generation,
    int32_t sample_rate
) noexcept {
    if (decoder_serial <= 0 || generation < 0 || sample_rate <= 0) return false;

    // Invalidate the old pending identity before publishing the replacement.
    pending_serial_.store(0, std::memory_order_release);
    pending_started_.store(false, std::memory_order_relaxed);
    pending_committed_.store(false, std::memory_order_relaxed);
    pending_position_frames_.store(0, std::memory_order_relaxed);
    pending_generation_.store(generation, std::memory_order_relaxed);
    pending_sample_rate_.store(sample_rate, std::memory_order_relaxed);
    pending_serial_.store(decoder_serial, std::memory_order_release);
    return true;
}

Update Timeline::onPendingFramesRendered(
    int64_t decoder_serial,
    int32_t generation,
    int64_t rendered_frames
) noexcept {
    if (decoder_serial <= 0 || rendered_frames <= 0) return {};
    if (pending_serial_.load(std::memory_order_acquire) != decoder_serial ||
        pending_generation_.load(std::memory_order_relaxed) != generation) {
        return {};
    }

    const int64_t safe_frames = std::max<int64_t>(0, rendered_frames);
    const int64_t position = pending_position_frames_.fetch_add(
        safe_frames,
        std::memory_order_acq_rel
    ) + safe_frames;

    bool expected = false;
    Event event = Event::None;
    if (pending_started_.compare_exchange_strong(
            expected,
            true,
            std::memory_order_acq_rel,
            std::memory_order_acquire)) {
        const int64_t previous_serial = current_serial_.load(std::memory_order_acquire);
        const int32_t previous_generation = current_generation_.load(std::memory_order_relaxed);
        if (previous_serial > 0 && previous_serial != decoder_serial) {
            retiring_generation_.store(previous_generation, std::memory_order_relaxed);
            retiring_serial_.store(previous_serial, std::memory_order_release);
        }
        current_generation_.store(generation, std::memory_order_relaxed);
        current_sample_rate_.store(
            pending_sample_rate_.load(std::memory_order_relaxed),
            std::memory_order_relaxed
        );
        current_position_frames_.store(position, std::memory_order_relaxed);
        current_serial_.store(decoder_serial, std::memory_order_release);
        event = Event::Started;
    } else if (current_serial_.load(std::memory_order_acquire) == decoder_serial &&
               current_generation_.load(std::memory_order_relaxed) == generation) {
        current_position_frames_.store(position, std::memory_order_release);
    }
    return Update{event, position};
}

Update Timeline::commitPending(int64_t decoder_serial, int32_t generation) noexcept {
    if (pending_serial_.load(std::memory_order_acquire) != decoder_serial ||
        pending_generation_.load(std::memory_order_relaxed) != generation ||
        !pending_started_.load(std::memory_order_acquire) ||
        current_serial_.load(std::memory_order_acquire) != decoder_serial) {
        return {};
    }
    pending_committed_.store(true, std::memory_order_release);
    const int64_t position = current_position_frames_.load(std::memory_order_acquire);
    pending_serial_.store(0, std::memory_order_release);
    return Update{Event::Committed, position};
}

Update Timeline::retirePrevious(int64_t decoder_serial, int32_t generation) noexcept {
    if (retiring_serial_.load(std::memory_order_acquire) != decoder_serial ||
        retiring_generation_.load(std::memory_order_relaxed) != generation) {
        return {};
    }
    retiring_serial_.store(0, std::memory_order_release);
    return Update{Event::Retired, current_position_frames_.load(std::memory_order_acquire)};
}

Update Timeline::cancelPending(int64_t decoder_serial, int32_t generation) noexcept {
    if (pending_serial_.load(std::memory_order_acquire) != decoder_serial ||
        pending_generation_.load(std::memory_order_relaxed) != generation) {
        return {};
    }
    const int64_t position = pending_position_frames_.load(std::memory_order_acquire);
    pending_serial_.store(0, std::memory_order_release);
    pending_started_.store(false, std::memory_order_release);
    pending_committed_.store(false, std::memory_order_release);
    pending_position_frames_.store(0, std::memory_order_release);
    return Update{Event::Cancelled, position};
}

void Timeline::reset() noexcept {
    pending_serial_.store(0, std::memory_order_release);
    pending_generation_.store(0, std::memory_order_relaxed);
    pending_sample_rate_.store(0, std::memory_order_relaxed);
    pending_position_frames_.store(0, std::memory_order_relaxed);
    pending_started_.store(false, std::memory_order_relaxed);
    pending_committed_.store(false, std::memory_order_relaxed);
    current_serial_.store(0, std::memory_order_release);
    current_generation_.store(0, std::memory_order_relaxed);
    current_sample_rate_.store(0, std::memory_order_relaxed);
    current_position_frames_.store(0, std::memory_order_relaxed);
    retiring_serial_.store(0, std::memory_order_release);
    retiring_generation_.store(0, std::memory_order_relaxed);
}

int64_t Timeline::currentSerial() const noexcept {
    return current_serial_.load(std::memory_order_acquire);
}

int32_t Timeline::currentGeneration() const noexcept {
    return current_generation_.load(std::memory_order_acquire);
}

int32_t Timeline::currentSampleRate() const noexcept {
    return current_sample_rate_.load(std::memory_order_acquire);
}

int64_t Timeline::currentPositionFrames() const noexcept {
    return current_position_frames_.load(std::memory_order_acquire);
}

bool Timeline::pendingStarted(int64_t decoder_serial, int32_t generation) const noexcept {
    return pending_serial_.load(std::memory_order_acquire) == decoder_serial &&
        pending_generation_.load(std::memory_order_relaxed) == generation &&
        pending_started_.load(std::memory_order_acquire);
}

std::string Timeline::snapshot() const {
    std::ostringstream out;
    out << "pending=" << pending_serial_.load(std::memory_order_acquire)
        << "/" << pending_generation_.load(std::memory_order_relaxed)
        << " sr=" << pending_sample_rate_.load(std::memory_order_relaxed)
        << " frames=" << pending_position_frames_.load(std::memory_order_relaxed)
        << " started=" << pending_started_.load(std::memory_order_relaxed)
        << " committed=" << pending_committed_.load(std::memory_order_relaxed)
        << " current=" << current_serial_.load(std::memory_order_acquire)
        << "/" << current_generation_.load(std::memory_order_relaxed)
        << " currentFrames=" << current_position_frames_.load(std::memory_order_relaxed)
        << " retiring=" << retiring_serial_.load(std::memory_order_acquire)
        << "/" << retiring_generation_.load(std::memory_order_relaxed);
    return out.str();
}

}  // namespace rawsmusic::track_timeline
