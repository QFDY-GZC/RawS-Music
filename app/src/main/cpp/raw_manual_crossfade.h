#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace rawsmusic::manual_crossfade {

enum class SampleFormat : uint8_t {
    PcmS16 = 1,
    PcmS32 = 2,
    PcmFloat = 3,
    PcmS24Packed = 4,
};

/**
 * Render-thread-owned two-slot short-crossfade envelope.
 *
 * Control threads only publish arm/clear commands. The audio thread consumes
 * the newest command at a complete PCM block boundary and owns both outgoing
 * and incoming linear-amplitude envelopes. No mutex or allocation is used in
 * process().
 */
class Processor final {
public:
    Processor() = default;
    Processor(const Processor&) = delete;
    Processor& operator=(const Processor&) = delete;

    void arm(int duration_ms) noexcept;
    void clear() noexcept;

    bool is_active_or_pending() const noexcept;
    int64_t processed_frames() const noexcept;
    int64_t total_frames() const noexcept;
    uint64_t completion_serial() const noexcept;

    /**
     * Mixes pending PCM into current PCM in-place.
     * Returns the number of complete frames processed in this call.
     */
    int64_t process(
        uint8_t* current,
        const uint8_t* pending,
        size_t length,
        int sample_rate,
        int frame_size,
        SampleFormat format
    ) noexcept;

private:
    static constexpr uint64_t kDurationMask = 0x00ffffffULL;

    static uint64_t pack_command(uint32_t serial, bool armed, int duration_ms) noexcept;
    static uint32_t command_serial(uint64_t word) noexcept;
    static bool command_armed(uint64_t word) noexcept;
    static int command_duration_ms(uint64_t word) noexcept;

    void publish_command(bool armed, int duration_ms) noexcept;
    void consume_command(uint64_t word, int sample_rate) noexcept;
    void mix_frame(
        uint8_t* current,
        const uint8_t* pending,
        int frame_size,
        SampleFormat format,
        float outgoing_gain,
        float incoming_gain
    ) noexcept;

    std::atomic<uint32_t> next_serial_{0};
    std::atomic<uint64_t> command_word_{0};
    std::atomic<uint32_t> baseline_generation_{0};
    std::atomic<uint32_t> applied_serial_snapshot_{0};
    std::atomic<bool> active_snapshot_{false};
    std::atomic<int64_t> processed_frames_snapshot_{0};
    std::atomic<int64_t> total_frames_snapshot_{0};
    std::atomic<uint64_t> completion_serial_{0};

    // Render-thread-owned fields.
    uint32_t applied_serial_ = 0;
    uint32_t applied_baseline_generation_ = 0;
    int duration_ms_ = 0;
    int64_t total_frames_ = 0;
    int64_t processed_frames_ = 0;
    float start_outgoing_gain_ = 1.0f;
    float start_incoming_gain_ = 0.0f;
    float current_outgoing_gain_ = 1.0f;
    float current_incoming_gain_ = 0.0f;
    bool active_ = false;
};

}  // namespace rawsmusic::manual_crossfade
