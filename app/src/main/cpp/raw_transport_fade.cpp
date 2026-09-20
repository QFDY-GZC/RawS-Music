#include "raw_transport_fade.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace rawsmusic::transport_fade {
namespace {

constexpr float kUnity = 1.0f;
constexpr float kSilence = 0.0f;

inline float clamp_gain(float gain) noexcept {
    return std::max(kSilence, std::min(kUnity, gain));
}

template <typename T>
inline T load_unaligned(const uint8_t* src) noexcept {
    T value{};
    std::memcpy(&value, src, sizeof(T));
    return value;
}

template <typename T>
inline void store_unaligned(uint8_t* dst, T value) noexcept {
    std::memcpy(dst, &value, sizeof(T));
}

inline int32_t read_s24_le(const uint8_t* src) noexcept {
    int32_t value = static_cast<int32_t>(src[0]) |
        (static_cast<int32_t>(src[1]) << 8) |
        (static_cast<int32_t>(src[2]) << 16);
    if ((value & 0x00800000) != 0) value |= static_cast<int32_t>(0xff000000);
    return value;
}

inline void write_s24_le(uint8_t* dst, int32_t value) noexcept {
    value = std::max(-8388608, std::min(8388607, value));
    dst[0] = static_cast<uint8_t>(value & 0xff);
    dst[1] = static_cast<uint8_t>((value >> 8) & 0xff);
    dst[2] = static_cast<uint8_t>((value >> 16) & 0xff);
}

}  // namespace

uint64_t Processor::pack_command(
    uint32_t serial,
    Direction direction,
    int duration_ms
) noexcept {
    const uint64_t safe_duration = static_cast<uint64_t>(
        std::max(0, std::min(duration_ms, static_cast<int>(kDurationMask)))
    );
    return (static_cast<uint64_t>(serial) << 32) |
        ((safe_duration & kDurationMask) << 8) |
        (static_cast<uint64_t>(direction) & kDirectionMask);
}

uint32_t Processor::command_serial(uint64_t word) noexcept {
    return static_cast<uint32_t>(word >> 32);
}

Direction Processor::command_direction(uint64_t word) noexcept {
    switch (static_cast<uint8_t>(word & kDirectionMask)) {
        case static_cast<uint8_t>(Direction::FadeIn): return Direction::FadeIn;
        case static_cast<uint8_t>(Direction::FadeOut): return Direction::FadeOut;
        default: return Direction::Clear;
    }
}

int Processor::command_duration_ms(uint64_t word) noexcept {
    return static_cast<int>((word >> 8) & kDurationMask);
}

void Processor::publish_command(Direction direction, int duration_ms) noexcept {
    const uint32_t serial = next_serial_.fetch_add(1, std::memory_order_relaxed) + 1U;
    const uint64_t desired = pack_command(serial, direction, duration_ms);

    // Multiple control callers are rare, but a late lower-serial store must never
    // overwrite a newer transport command. Publish only while our serial is newer.
    uint64_t current = command_word_.load(std::memory_order_acquire);
    while (serial > command_serial(current)) {
        if (command_word_.compare_exchange_weak(
                current,
                desired,
                std::memory_order_release,
                std::memory_order_acquire)) {
            return;
        }
    }
}

void Processor::arm(Direction direction, int duration_ms) noexcept {
    if (direction == Direction::Clear) {
        clear();
        return;
    }
    publish_command(direction, std::max(1, duration_ms));
}

void Processor::clear() noexcept {
    baseline_generation_.fetch_add(1, std::memory_order_acq_rel);
    active_snapshot_.store(false, std::memory_order_release);
    gain_snapshot_.store(kUnity, std::memory_order_release);
    publish_command(Direction::Clear, 0);
}

bool Processor::is_active_or_pending() const noexcept {
    const uint64_t word = command_word_.load(std::memory_order_acquire);
    return active_snapshot_.load(std::memory_order_acquire) ||
        command_serial(word) != applied_serial_snapshot_.load(std::memory_order_acquire);
}

float Processor::current_gain() const noexcept {
    return gain_snapshot_.load(std::memory_order_acquire);
}

uint64_t Processor::completion_serial() const noexcept {
    return completion_serial_.load(std::memory_order_acquire);
}

void Processor::consume_command(uint64_t word, int sample_rate) noexcept {
    const uint32_t serial = command_serial(word);
    if (serial == applied_serial_) return;

    applied_serial_ = serial;
    applied_serial_snapshot_.store(serial, std::memory_order_release);
    direction_ = command_direction(word);
    duration_ms_ = command_duration_ms(word);
    sample_rate_ = std::max(1, sample_rate);
    processed_frames_ = 0;

    if (direction_ == Direction::Clear) {
        active_ = false;
        start_gain_ = kUnity;
        target_gain_ = kUnity;
        current_gain_ = kUnity;
        total_frames_ = 0;
        active_snapshot_.store(false, std::memory_order_release);
        gain_snapshot_.store(current_gain_, std::memory_order_release);
        completion_serial_.store(serial, std::memory_order_release);
        return;
    }

    total_frames_ = std::max<int64_t>(
        1,
        (static_cast<int64_t>(duration_ms_) * static_cast<int64_t>(sample_rate_)) / 1000
    );

    if (direction_ == Direction::FadeIn) {
        // New play/resume always begins at silence. A reverse transition should be
        // published as a new explicit profile in a later phase rather than leaking
        // an old transport envelope into a fresh session.
        start_gain_ = kSilence;
        target_gain_ = kUnity;
        current_gain_ = start_gain_;
    } else {
        start_gain_ = active_ ? current_gain_ : kUnity;
        target_gain_ = kSilence;
        current_gain_ = start_gain_;
    }

    active_ = true;
    active_snapshot_.store(true, std::memory_order_release);
    gain_snapshot_.store(current_gain_, std::memory_order_release);
}

int64_t Processor::process(
    uint8_t* data,
    size_t length,
    int sample_rate,
    int frame_size,
    SampleFormat format
) noexcept {
    if (!data || length == 0 || sample_rate <= 0 || frame_size <= 0) return 0;

    const uint32_t baseline_generation = baseline_generation_.load(std::memory_order_acquire);
    if (baseline_generation != applied_baseline_generation_) {
        applied_baseline_generation_ = baseline_generation;
        active_ = false;
        direction_ = Direction::Clear;
        duration_ms_ = 0;
        total_frames_ = 0;
        processed_frames_ = 0;
        start_gain_ = kUnity;
        target_gain_ = kUnity;
        current_gain_ = kUnity;
    }

    const uint64_t word = command_word_.load(std::memory_order_acquire);
    if (command_serial(word) != applied_serial_) consume_command(word, sample_rate);
    if (!active_) return static_cast<int64_t>(length / static_cast<size_t>(frame_size));

    const size_t frame_count = length / static_cast<size_t>(frame_size);
    for (size_t frame_index = 0; frame_index < frame_count; ++frame_index) {
        const int64_t absolute_frame = processed_frames_ + static_cast<int64_t>(frame_index);
        const double progress = std::min(
            1.0,
            static_cast<double>(absolute_frame) / static_cast<double>(total_frames_)
        );
        const float gain = clamp_gain(
            start_gain_ + static_cast<float>((target_gain_ - start_gain_) * progress)
        );
        apply_frame(
            data + frame_index * static_cast<size_t>(frame_size),
            frame_size,
            format,
            gain
        );
        current_gain_ = gain;
    }

    processed_frames_ += static_cast<int64_t>(frame_count);
    if (processed_frames_ >= total_frames_) {
        current_gain_ = target_gain_;
        if (frame_count > 0) {
            apply_frame(
                data + (frame_count - 1U) * static_cast<size_t>(frame_size),
                frame_size,
                format,
                current_gain_
            );
        }
        active_ = false;
        active_snapshot_.store(false, std::memory_order_release);
        completion_serial_.store(applied_serial_, std::memory_order_release);
    }
    gain_snapshot_.store(current_gain_, std::memory_order_release);
    return static_cast<int64_t>(frame_count);
}

void Processor::apply_frame(
    uint8_t* frame,
    int frame_size,
    SampleFormat format,
    float gain
) noexcept {
    if (!frame || frame_size <= 0) return;
    const float safe_gain = clamp_gain(gain);

    switch (format) {
        case SampleFormat::PcmFloat: {
            for (int offset = 0; offset + 4 <= frame_size; offset += 4) {
                const float sample = load_unaligned<float>(frame + offset);
                store_unaligned<float>(frame + offset, sample * safe_gain);
            }
            break;
        }
        case SampleFormat::PcmS32: {
            for (int offset = 0; offset + 4 <= frame_size; offset += 4) {
                const int32_t sample = load_unaligned<int32_t>(frame + offset);
                const double scaled = static_cast<double>(sample) * static_cast<double>(safe_gain);
                const auto value = static_cast<int64_t>(std::llround(scaled));
                const int32_t clamped = static_cast<int32_t>(std::max<int64_t>(
                    std::numeric_limits<int32_t>::min(),
                    std::min<int64_t>(std::numeric_limits<int32_t>::max(), value)
                ));
                store_unaligned<int32_t>(frame + offset, clamped);
            }
            break;
        }
        case SampleFormat::PcmS24Packed: {
            for (int offset = 0; offset + 3 <= frame_size; offset += 3) {
                const int32_t sample = read_s24_le(frame + offset);
                const int32_t scaled = static_cast<int32_t>(std::llround(
                    static_cast<double>(sample) * static_cast<double>(safe_gain)
                ));
                write_s24_le(frame + offset, scaled);
            }
            break;
        }
        case SampleFormat::PcmS16:
        default: {
            for (int offset = 0; offset + 2 <= frame_size; offset += 2) {
                const int16_t sample = load_unaligned<int16_t>(frame + offset);
                const int32_t scaled = static_cast<int32_t>(std::lround(
                    static_cast<double>(sample) * static_cast<double>(safe_gain)
                ));
                const int16_t clamped = static_cast<int16_t>(std::max<int32_t>(
                    std::numeric_limits<int16_t>::min(),
                    std::min<int32_t>(std::numeric_limits<int16_t>::max(), scaled)
                ));
                store_unaligned<int16_t>(frame + offset, clamped);
            }
            break;
        }
    }
}

}  // namespace rawsmusic::transport_fade
