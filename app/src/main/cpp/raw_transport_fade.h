#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace rawsmusic::transport_fade {

enum class Direction : uint8_t {
    Clear = 0,
    FadeIn = 1,
    FadeOut = 2,
};

enum class SampleFormat : uint8_t {
    PcmS16 = 1,
    PcmS32 = 2,
    PcmFloat = 3,
    PcmS24Packed = 4,
};

/**
 * Render-thread-owned linear amplitude envelope.
 *
 * Control threads publish only a compact atomic command. The audio thread owns
 * all mutable envelope state and consumes the newest command at the next PCM
 * block boundary, so processing never acquires a mutex or allocates memory.
 */
class Processor final {
public:
    Processor() = default;
    Processor(const Processor&) = delete;
    Processor& operator=(const Processor&) = delete;

    void arm(Direction direction, int duration_ms) noexcept;
    void clear() noexcept;

    bool is_active_or_pending() const noexcept;
    float current_gain() const noexcept;
    uint64_t completion_serial() const noexcept;

    /**
     * Applies the currently armed envelope in-place.
     * Returns the number of complete PCM frames examined.
     */
    int64_t process(
        uint8_t* data,
        size_t length,
        int sample_rate,
        int frame_size,
        SampleFormat format
    ) noexcept;

private:
    static constexpr uint64_t kDirectionMask = 0xffULL;
    static constexpr uint64_t kDurationMask = 0x00ffffffULL;

    static uint64_t pack_command(uint32_t serial, Direction direction, int duration_ms) noexcept;
    static uint32_t command_serial(uint64_t word) noexcept;
    static Direction command_direction(uint64_t word) noexcept;
    static int command_duration_ms(uint64_t word) noexcept;

    void publish_command(Direction direction, int duration_ms) noexcept;
    void consume_command(uint64_t word, int sample_rate) noexcept;
    void apply_frame(
        uint8_t* frame,
        int frame_size,
        SampleFormat format,
        float gain
    ) noexcept;

    std::atomic<uint32_t> next_serial_{0};
    std::atomic<uint64_t> command_word_{0};
    std::atomic<uint32_t> baseline_generation_{0};
    std::atomic<uint32_t> applied_serial_snapshot_{0};
    std::atomic<bool> active_snapshot_{false};
    std::atomic<float> gain_snapshot_{1.0f};
    std::atomic<uint64_t> completion_serial_{0};

    // Render-thread-owned state. No control thread writes these fields.
    uint32_t applied_serial_ = 0;
    uint32_t applied_baseline_generation_ = 0;
    Direction direction_ = Direction::Clear;
    int duration_ms_ = 0;
    int sample_rate_ = 0;
    int64_t total_frames_ = 0;
    int64_t processed_frames_ = 0;
    float start_gain_ = 1.0f;
    float target_gain_ = 1.0f;
    float current_gain_ = 1.0f;
    bool active_ = false;
};

}  // namespace rawsmusic::transport_fade
