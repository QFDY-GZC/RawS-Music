#include "raw_manual_crossfade.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace rawsmusic::manual_crossfade {
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

inline int sample_bytes(SampleFormat format) noexcept {
    switch (format) {
        case SampleFormat::PcmS16: return 2;
        case SampleFormat::PcmS24Packed: return 3;
        case SampleFormat::PcmS32:
        case SampleFormat::PcmFloat: return 4;
    }
    return 0;
}

}  // namespace

uint64_t Processor::pack_command(uint32_t serial, bool armed, int duration_ms) noexcept {
    const uint64_t safe_duration = static_cast<uint64_t>(
        std::max(0, std::min(duration_ms, static_cast<int>(kDurationMask)))
    );
    return (static_cast<uint64_t>(serial) << 32) |
        ((safe_duration & kDurationMask) << 1) |
        (armed ? 1ULL : 0ULL);
}

uint32_t Processor::command_serial(uint64_t word) noexcept {
    return static_cast<uint32_t>(word >> 32);
}

bool Processor::command_armed(uint64_t word) noexcept {
    return (word & 1ULL) != 0;
}

int Processor::command_duration_ms(uint64_t word) noexcept {
    return static_cast<int>((word >> 1) & kDurationMask);
}

void Processor::publish_command(bool armed, int duration_ms) noexcept {
    const uint32_t serial = next_serial_.fetch_add(1, std::memory_order_relaxed) + 1U;
    const uint64_t desired = pack_command(serial, armed, duration_ms);
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

void Processor::arm(int duration_ms) noexcept {
    publish_command(true, std::max(1, duration_ms));
}

void Processor::clear() noexcept {
    baseline_generation_.fetch_add(1, std::memory_order_acq_rel);
    active_snapshot_.store(false, std::memory_order_release);
    processed_frames_snapshot_.store(0, std::memory_order_release);
    total_frames_snapshot_.store(0, std::memory_order_release);
    publish_command(false, 0);
}

bool Processor::is_active_or_pending() const noexcept {
    const uint64_t word = command_word_.load(std::memory_order_acquire);
    return active_snapshot_.load(std::memory_order_acquire) ||
        command_serial(word) != applied_serial_snapshot_.load(std::memory_order_acquire);
}

int64_t Processor::processed_frames() const noexcept {
    return processed_frames_snapshot_.load(std::memory_order_acquire);
}

int64_t Processor::total_frames() const noexcept {
    return total_frames_snapshot_.load(std::memory_order_acquire);
}

uint64_t Processor::completion_serial() const noexcept {
    return completion_serial_.load(std::memory_order_acquire);
}

void Processor::consume_command(uint64_t word, int sample_rate) noexcept {
    const uint32_t serial = command_serial(word);
    if (serial == applied_serial_) return;

    applied_serial_ = serial;
    applied_serial_snapshot_.store(serial, std::memory_order_release);
    duration_ms_ = command_duration_ms(word);
    processed_frames_ = 0;

    if (!command_armed(word)) {
        active_ = false;
        total_frames_ = 0;
        start_outgoing_gain_ = kUnity;
        start_incoming_gain_ = kSilence;
        current_outgoing_gain_ = kUnity;
        current_incoming_gain_ = kSilence;
        active_snapshot_.store(false, std::memory_order_release);
        processed_frames_snapshot_.store(0, std::memory_order_release);
        total_frames_snapshot_.store(0, std::memory_order_release);
        completion_serial_.store(serial, std::memory_order_release);
        return;
    }

    total_frames_ = std::max<int64_t>(
        1,
        (static_cast<int64_t>(duration_ms_) * static_cast<int64_t>(std::max(1, sample_rate))) / 1000
    );

    // A replacement command continues the outgoing slot from its real current
    // gain, while every new pending slot begins at silence. This prevents an
    // audible unity jump if a command supersedes an in-flight short fade.
    start_outgoing_gain_ = active_ ? current_outgoing_gain_ : kUnity;
    start_incoming_gain_ = kSilence;
    current_incoming_gain_ = start_incoming_gain_;
    active_ = true;
    active_snapshot_.store(true, std::memory_order_release);
    processed_frames_snapshot_.store(0, std::memory_order_release);
    total_frames_snapshot_.store(total_frames_, std::memory_order_release);
}

int64_t Processor::process(
    uint8_t* current,
    const uint8_t* pending,
    size_t length,
    int sample_rate,
    int frame_size,
    SampleFormat format
) noexcept {
    if (!current || !pending || length == 0 || sample_rate <= 0 || frame_size <= 0) return 0;
    const int bytes_per_sample = sample_bytes(format);
    if (bytes_per_sample <= 0 || frame_size % bytes_per_sample != 0) return 0;

    const uint32_t baseline_generation = baseline_generation_.load(std::memory_order_acquire);
    if (baseline_generation != applied_baseline_generation_) {
        applied_baseline_generation_ = baseline_generation;
        active_ = false;
        duration_ms_ = 0;
        total_frames_ = 0;
        processed_frames_ = 0;
        start_outgoing_gain_ = kUnity;
        start_incoming_gain_ = kSilence;
        current_outgoing_gain_ = kUnity;
        current_incoming_gain_ = kSilence;
    }

    const uint64_t word = command_word_.load(std::memory_order_acquire);
    if (command_serial(word) != applied_serial_) consume_command(word, sample_rate);
    if (!active_) return 0;

    const size_t frame_count = length / static_cast<size_t>(frame_size);
    for (size_t frame_index = 0; frame_index < frame_count; ++frame_index) {
        const int64_t absolute_frame = processed_frames_ + static_cast<int64_t>(frame_index);
        const double progress = std::min(
            1.0,
            static_cast<double>(absolute_frame) / static_cast<double>(std::max<int64_t>(1, total_frames_))
        );
        const float outgoing_gain = clamp_gain(
            start_outgoing_gain_ + static_cast<float>((kSilence - start_outgoing_gain_) * progress)
        );
        const float incoming_gain = clamp_gain(
            start_incoming_gain_ + static_cast<float>((kUnity - start_incoming_gain_) * progress)
        );
        mix_frame(
            current + frame_index * static_cast<size_t>(frame_size),
            pending + frame_index * static_cast<size_t>(frame_size),
            frame_size,
            format,
            outgoing_gain,
            incoming_gain
        );
        current_outgoing_gain_ = outgoing_gain;
        current_incoming_gain_ = incoming_gain;
    }

    processed_frames_ += static_cast<int64_t>(frame_count);
    if (processed_frames_ >= total_frames_) {
        processed_frames_ = total_frames_;
        current_outgoing_gain_ = kSilence;
        current_incoming_gain_ = kUnity;
        active_ = false;
        completion_serial_.store(applied_serial_, std::memory_order_release);
    }
    active_snapshot_.store(active_, std::memory_order_release);
    processed_frames_snapshot_.store(processed_frames_, std::memory_order_release);
    total_frames_snapshot_.store(total_frames_, std::memory_order_release);
    return static_cast<int64_t>(frame_count);
}

void Processor::mix_frame(
    uint8_t* current,
    const uint8_t* pending,
    int frame_size,
    SampleFormat format,
    float outgoing_gain,
    float incoming_gain
) noexcept {
    switch (format) {
        case SampleFormat::PcmS16: {
            for (int offset = 0; offset + 2 <= frame_size; offset += 2) {
                const int16_t out = load_unaligned<int16_t>(current + offset);
                const int16_t in = load_unaligned<int16_t>(pending + offset);
                const double mixed = static_cast<double>(out) * outgoing_gain +
                    static_cast<double>(in) * incoming_gain;
                const int32_t clipped = static_cast<int32_t>(std::llround(std::max(-32768.0, std::min(32767.0, mixed))));
                store_unaligned<int16_t>(current + offset, static_cast<int16_t>(clipped));
            }
            break;
        }
        case SampleFormat::PcmS24Packed: {
            for (int offset = 0; offset + 3 <= frame_size; offset += 3) {
                const int32_t out = read_s24_le(current + offset);
                const int32_t in = read_s24_le(pending + offset);
                const double mixed = static_cast<double>(out) * outgoing_gain +
                    static_cast<double>(in) * incoming_gain;
                write_s24_le(current + offset, static_cast<int32_t>(std::llround(mixed)));
            }
            break;
        }
        case SampleFormat::PcmS32: {
            for (int offset = 0; offset + 4 <= frame_size; offset += 4) {
                const int32_t out = load_unaligned<int32_t>(current + offset);
                const int32_t in = load_unaligned<int32_t>(pending + offset);
                const long double mixed = static_cast<long double>(out) * outgoing_gain +
                    static_cast<long double>(in) * incoming_gain;
                const long double clipped = std::max(
                    static_cast<long double>(std::numeric_limits<int32_t>::min()),
                    std::min(static_cast<long double>(std::numeric_limits<int32_t>::max()), mixed)
                );
                store_unaligned<int32_t>(current + offset, static_cast<int32_t>(std::llround(clipped)));
            }
            break;
        }
        case SampleFormat::PcmFloat: {
            for (int offset = 0; offset + 4 <= frame_size; offset += 4) {
                const float out = load_unaligned<float>(current + offset);
                const float in = load_unaligned<float>(pending + offset);
                const float mixed = std::max(-1.0f, std::min(1.0f, out * outgoing_gain + in * incoming_gain));
                store_unaligned<float>(current + offset, mixed);
            }
            break;
        }
    }
}

}  // namespace rawsmusic::manual_crossfade
