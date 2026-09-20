#include "raw_auto_crossfade.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <limits>

namespace rawsmusic::auto_crossfade {
namespace {

constexpr float kUnity = 1.0f;
constexpr float kSilence = 0.0f;
constexpr float kMinRms = 0.0000316227766f;

inline float clamp_gain(float gain) noexcept {
    return std::max(kSilence, std::min(kUnity, gain));
}

inline uint32_t float_bits(float value) noexcept {
    uint32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

inline float bits_float(uint32_t bits) noexcept {
    float value = 0.0f;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

inline float lerp(float start, float end, float progress) noexcept {
    const float p = std::max(0.0f, std::min(1.0f, progress));
    return start + (end - start) * p;
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

inline double normalized_sample(const uint8_t* src, SampleFormat format) noexcept {
    switch (format) {
        case SampleFormat::PcmS16:
            return static_cast<double>(load_unaligned<int16_t>(src)) / 32768.0;
        case SampleFormat::PcmS24Packed:
            return static_cast<double>(read_s24_le(src)) / 8388608.0;
        case SampleFormat::PcmS32:
            return static_cast<double>(load_unaligned<int32_t>(src)) / 2147483648.0;
        case SampleFormat::PcmFloat: {
            const float value = load_unaligned<float>(src);
            return std::isfinite(value) ? static_cast<double>(value) : 0.0;
        }
    }
    return 0.0;
}

float rms_db(const uint8_t* data, size_t length, SampleFormat format) noexcept {
    const int bytes = sample_bytes(format);
    if (!data || bytes <= 0 || length < static_cast<size_t>(bytes)) return -90.0f;
    const size_t samples = length / static_cast<size_t>(bytes);
    const size_t stride = std::max<size_t>(1, samples / 2048U);
    long double sum = 0.0L;
    size_t count = 0;
    for (size_t sample = 0; sample < samples; sample += stride) {
        const double value = normalized_sample(data + sample * static_cast<size_t>(bytes), format);
        if (std::isfinite(value)) {
            sum += static_cast<long double>(value) * static_cast<long double>(value);
            ++count;
        }
    }
    if (count == 0) return -90.0f;
    const double rms = std::max(
        static_cast<double>(kMinRms),
        std::sqrt(static_cast<double>(sum / static_cast<long double>(count)))
    );
    return static_cast<float>(std::max(-90.0, std::min(0.0, 20.0 * std::log10(rms))));
}

}  // namespace

uint64_t Processor::pack_command(uint32_t serial, CommandType type, int duration_ms) noexcept {
    const uint64_t safe_duration = static_cast<uint64_t>(
        std::max(0, std::min(duration_ms, static_cast<int>(kDurationMask)))
    );
    return (static_cast<uint64_t>(serial) << 32) |
        ((safe_duration & kDurationMask) << 2) |
        static_cast<uint64_t>(type);
}

uint32_t Processor::command_serial(uint64_t word) noexcept {
    return static_cast<uint32_t>(word >> 32);
}

Processor::CommandType Processor::command_type(uint64_t word) noexcept {
    return static_cast<CommandType>(word & 0x3ULL);
}

int Processor::command_duration_ms(uint64_t word) noexcept {
    return static_cast<int>((word >> 2) & kDurationMask);
}

void Processor::publish_command(CommandType type, int duration_ms) noexcept {
    const uint32_t serial = next_serial_.fetch_add(1, std::memory_order_relaxed) + 1U;
    const uint64_t desired = pack_command(serial, type, duration_ms);
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

void Processor::arm(
    int duration_ms,
    int pivot_ms
) noexcept {
    pivot_ms_.store(std::max(1, pivot_ms), std::memory_order_relaxed);
    publish_command(CommandType::Arm, std::max(1, duration_ms));
}

void Processor::retime(int duration_ms) noexcept {
    publish_command(CommandType::Retime, std::max(1, duration_ms));
}

void Processor::clear() noexcept {
    baseline_generation_.fetch_add(1, std::memory_order_acq_rel);
    active_snapshot_.store(false, std::memory_order_release);
    processed_frames_snapshot_.store(0, std::memory_order_release);
    total_frames_snapshot_.store(0, std::memory_order_release);
    publish_command(CommandType::Clear, 0);
}

bool Processor::is_active_or_pending() const noexcept {
    const uint64_t word = command_word_.load(std::memory_order_acquire);
    return active_snapshot_.load(std::memory_order_acquire) ||
        command_serial(word) != applied_serial_snapshot_.load(std::memory_order_acquire);
}

int64_t Processor::consumed_frames() const noexcept {
    return consumed_frames_snapshot_.load(std::memory_order_acquire);
}

int64_t Processor::local_processed_frames() const noexcept {
    return processed_frames_snapshot_.load(std::memory_order_acquire);
}

int64_t Processor::local_total_frames() const noexcept {
    return total_frames_snapshot_.load(std::memory_order_acquire);
}

float Processor::outgoing_gain() const noexcept {
    return bits_float(outgoing_gain_bits_.load(std::memory_order_acquire));
}

float Processor::incoming_gain() const noexcept {
    return bits_float(incoming_gain_bits_.load(std::memory_order_acquire));
}

uint64_t Processor::completion_serial() const noexcept {
    return completion_serial_.load(std::memory_order_acquire);
}

uint64_t Processor::retime_serial() const noexcept {
    return retime_serial_.load(std::memory_order_acquire);
}

void Processor::consume_command(uint64_t word, int sample_rate) noexcept {
    const uint32_t serial = command_serial(word);
    if (serial == applied_serial_) return;
    applied_serial_ = serial;
    applied_serial_snapshot_.store(serial, std::memory_order_release);
    const CommandType type = command_type(word);

    if (type == CommandType::Clear) {
        active_ = false;
        sample_rate_ = 0;
        duration_ms_ = 0;
        total_frames_ = 0;
        processed_frames_ = 0;
        consumed_frames_ = 0;
        start_outgoing_gain_ = kUnity;
        start_incoming_gain_ = kSilence;
        current_outgoing_gain_ = kUnity;
        current_incoming_gain_ = kSilence;
        entry_gain_initialized_ = false;
        active_snapshot_.store(false, std::memory_order_release);
        consumed_frames_snapshot_.store(0, std::memory_order_release);
        processed_frames_snapshot_.store(0, std::memory_order_release);
        total_frames_snapshot_.store(0, std::memory_order_release);
        outgoing_gain_bits_.store(float_bits(kUnity), std::memory_order_release);
        incoming_gain_bits_.store(float_bits(kSilence), std::memory_order_release);
        completion_serial_.store(serial, std::memory_order_release);
        return;
    }

    sample_rate_ = std::max(1, sample_rate);
    duration_ms_ = std::max(1, command_duration_ms(word));
    total_frames_ = std::max<int64_t>(
        1,
        static_cast<int64_t>(duration_ms_) * static_cast<int64_t>(sample_rate_) / 1000
    );
    processed_frames_ = 0;

    if (type == CommandType::Arm) {
        consumed_frames_ = 0;
        start_outgoing_gain_ = kUnity;
        start_incoming_gain_ = kSilence;
        current_outgoing_gain_ = kUnity;
        current_incoming_gain_ = kSilence;
        pivot_fraction_ = std::max(
            0.35f,
            std::min(
                0.75f,
                static_cast<float>(pivot_ms_.load(std::memory_order_relaxed)) /
                    static_cast<float>(std::max(1, duration_ms_))
            )
        );
        entry_gain_initialized_ = false;
    } else {
        // Quiet-tail retiming begins from the real render gains and never reopens
        // the follow track at its initial floor.
        start_outgoing_gain_ = current_outgoing_gain_;
        start_incoming_gain_ = current_incoming_gain_;
        entry_gain_initialized_ = true;
        retime_serial_.store(serial, std::memory_order_release);
    }

    active_ = true;
    active_snapshot_.store(true, std::memory_order_release);
    consumed_frames_snapshot_.store(consumed_frames_, std::memory_order_release);
    processed_frames_snapshot_.store(0, std::memory_order_release);
    total_frames_snapshot_.store(total_frames_, std::memory_order_release);
}

void Processor::initialize_entry_gain(
    const uint8_t* current,
    const uint8_t* pending,
    size_t length,
    SampleFormat format
) noexcept {
    if (entry_gain_initialized_) return;
    const float outgoing_db = rms_db(current, length, format);
    const float incoming_db = rms_db(pending, length, format);
    const float loudness_delta = std::max(-12.0f, std::min(12.0f, incoming_db - outgoing_db));
    pivot_fraction_ = std::max(0.35f, std::min(0.75f, pivot_fraction_ + loudness_delta / 80.0f));
    // Independent volume ramps begin at 1/0. Per-item loudness normalization belongs to
    // the playback volume path, not the transition envelope.
    start_incoming_gain_ = kSilence;
    current_incoming_gain_ = kSilence;
    entry_gain_initialized_ = true;
}

void Processor::gains_for_frame(int64_t local_frame, float& outgoing, float& incoming) const noexcept {
    const int64_t duration = std::max<int64_t>(1, total_frames_);
    const int64_t local = std::max<int64_t>(0, std::min<int64_t>(duration, local_frame));
    float progress = 0.0f;
    if (local >= duration) {
        progress = 1.0f;
    } else if (local > 0) {
        const int64_t pivot = std::max<int64_t>(
            1,
            std::min<int64_t>(duration - 1, static_cast<int64_t>(duration * pivot_fraction_))
        );
        progress = local <= pivot
            ? 0.5f * static_cast<float>(local) / static_cast<float>(pivot)
            : 0.5f + 0.5f * static_cast<float>(local - pivot) /
                static_cast<float>(duration - pivot);
    }
    outgoing = lerp(start_outgoing_gain_, kSilence, progress);
    incoming = lerp(start_incoming_gain_, kUnity, progress);
    outgoing = clamp_gain(outgoing);
    incoming = clamp_gain(incoming);
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

    const uint32_t baseline = baseline_generation_.load(std::memory_order_acquire);
    if (baseline != applied_baseline_generation_) {
        applied_baseline_generation_ = baseline;
        active_ = false;
        total_frames_ = 0;
        processed_frames_ = 0;
        consumed_frames_ = 0;
        start_outgoing_gain_ = kUnity;
        start_incoming_gain_ = kSilence;
        current_outgoing_gain_ = kUnity;
        current_incoming_gain_ = kSilence;
        entry_gain_initialized_ = false;
    }

    const uint64_t word = command_word_.load(std::memory_order_acquire);
    if (command_serial(word) != applied_serial_) consume_command(word, sample_rate);
    if (!active_) return 0;

    const bool first_audible_block = consumed_frames_ == 0 && !entry_gain_initialized_;
    initialize_entry_gain(current, pending, length, format);
    const size_t frame_count = length / static_cast<size_t>(frame_size);
    for (size_t frame_index = 0; frame_index < frame_count; ++frame_index) {
        const int64_t absolute = processed_frames_ + static_cast<int64_t>(frame_index);
        float outgoing = kUnity;
        float incoming = kSilence;
        gains_for_frame(absolute, outgoing, incoming);
        if (first_audible_block && frame_count > 0) {
            const float entry_progress = static_cast<float>(frame_index + 1U) /
                static_cast<float>(frame_count);
            incoming *= entry_progress;
        }
        mix_frame(
            current + frame_index * static_cast<size_t>(frame_size),
            pending + frame_index * static_cast<size_t>(frame_size),
            frame_size,
            format,
            outgoing,
            incoming
        );
        current_outgoing_gain_ = outgoing;
        current_incoming_gain_ = incoming;
    }

    processed_frames_ += static_cast<int64_t>(frame_count);
    consumed_frames_ += static_cast<int64_t>(frame_count);
    if (processed_frames_ >= total_frames_) {
        processed_frames_ = total_frames_;
        current_outgoing_gain_ = kSilence;
        current_incoming_gain_ = kUnity;
        active_ = false;
        completion_serial_.store(applied_serial_, std::memory_order_release);
    }

    active_snapshot_.store(active_, std::memory_order_release);
    consumed_frames_snapshot_.store(consumed_frames_, std::memory_order_release);
    processed_frames_snapshot_.store(processed_frames_, std::memory_order_release);
    total_frames_snapshot_.store(total_frames_, std::memory_order_release);
    outgoing_gain_bits_.store(float_bits(current_outgoing_gain_), std::memory_order_release);
    incoming_gain_bits_.store(float_bits(current_incoming_gain_), std::memory_order_release);
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
        case SampleFormat::PcmS16:
            for (int offset = 0; offset + 2 <= frame_size; offset += 2) {
                const int16_t out = load_unaligned<int16_t>(current + offset);
                const int16_t in = load_unaligned<int16_t>(pending + offset);
                const double mixed = static_cast<double>(out) * outgoing_gain +
                    static_cast<double>(in) * incoming_gain;
                const int32_t clipped = static_cast<int32_t>(std::llround(
                    std::max(-32768.0, std::min(32767.0, mixed))
                ));
                store_unaligned<int16_t>(current + offset, static_cast<int16_t>(clipped));
            }
            break;
        case SampleFormat::PcmS24Packed:
            for (int offset = 0; offset + 3 <= frame_size; offset += 3) {
                const int32_t out = read_s24_le(current + offset);
                const int32_t in = read_s24_le(pending + offset);
                const double mixed = static_cast<double>(out) * outgoing_gain +
                    static_cast<double>(in) * incoming_gain;
                write_s24_le(current + offset, static_cast<int32_t>(std::llround(mixed)));
            }
            break;
        case SampleFormat::PcmS32:
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
        case SampleFormat::PcmFloat:
            for (int offset = 0; offset + 4 <= frame_size; offset += 4) {
                const float out = load_unaligned<float>(current + offset);
                const float in = load_unaligned<float>(pending + offset);
                const float mixed = std::max(
                    -1.0f,
                    std::min(1.0f, out * outgoing_gain + in * incoming_gain)
                );
                store_unaligned<float>(current + offset, mixed);
            }
            break;
    }
}

}  // namespace rawsmusic::auto_crossfade
