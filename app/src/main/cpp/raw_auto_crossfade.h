#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>

namespace rawsmusic::auto_crossfade {

enum class SampleFormat : uint8_t {
    PcmS16 = 1,
    PcmS32 = 2,
    PcmFloat = 3,
    PcmS24Packed = 4,
};

/**
 * Render-clock-owned automatic lead/follow envelope.
 *
 * Kotlin publishes an immutable recipe (duration and policy constants). The
 * audio thread resolves the first audible incoming level from the real PCM
 * blocks, advances both gains by rendered frames, and may retime a quiet tail
 * without resetting either track's current gain.
 */
class Processor final {
public:
    Processor() = default;
    Processor(const Processor&) = delete;
    Processor& operator=(const Processor&) = delete;

    void arm(
        int duration_ms,
        int pivot_ms
    ) noexcept;
    void retime(int duration_ms) noexcept;
    void clear() noexcept;

    bool is_active_or_pending() const noexcept;
    int64_t consumed_frames() const noexcept;
    int64_t local_processed_frames() const noexcept;
    int64_t local_total_frames() const noexcept;
    float outgoing_gain() const noexcept;
    float incoming_gain() const noexcept;
    uint64_t completion_serial() const noexcept;
    uint64_t retime_serial() const noexcept;

    int64_t process(
        uint8_t* current,
        const uint8_t* pending,
        size_t length,
        int sample_rate,
        int frame_size,
        SampleFormat format
    ) noexcept;

private:
    enum class CommandType : uint8_t { Clear = 0, Arm = 1, Retime = 2 };

    static constexpr uint64_t kDurationMask = 0x00ffffffULL;
    static uint64_t pack_command(uint32_t serial, CommandType type, int duration_ms) noexcept;
    static uint32_t command_serial(uint64_t word) noexcept;
    static CommandType command_type(uint64_t word) noexcept;
    static int command_duration_ms(uint64_t word) noexcept;

    void publish_command(CommandType type, int duration_ms) noexcept;
    void consume_command(uint64_t word, int sample_rate) noexcept;
    void initialize_entry_gain(
        const uint8_t* current,
        const uint8_t* pending,
        size_t length,
        SampleFormat format
    ) noexcept;
    void gains_for_frame(int64_t local_frame, float& outgoing, float& incoming) const noexcept;
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
    std::atomic<int64_t> consumed_frames_snapshot_{0};
    std::atomic<int64_t> processed_frames_snapshot_{0};
    std::atomic<int64_t> total_frames_snapshot_{0};
    std::atomic<uint32_t> outgoing_gain_bits_{0x3f800000U};
    std::atomic<uint32_t> incoming_gain_bits_{0U};
    std::atomic<uint64_t> completion_serial_{0};
    std::atomic<uint64_t> retime_serial_{0};

    // Recipe fields are published before the Arm command's release-store.
    std::atomic<int> pivot_ms_{6600};

    // Render-thread-owned state.
    uint32_t applied_serial_ = 0;
    uint32_t applied_baseline_generation_ = 0;
    int sample_rate_ = 0;
    int duration_ms_ = 0;
    int64_t total_frames_ = 0;
    int64_t processed_frames_ = 0;
    int64_t consumed_frames_ = 0;
    float start_outgoing_gain_ = 1.0f;
    float start_incoming_gain_ = 0.0f;
    float current_outgoing_gain_ = 1.0f;
    float current_incoming_gain_ = 0.0f;
    float pivot_fraction_ = 0.55f;
    bool entry_gain_initialized_ = false;
    bool active_ = false;
};

}  // namespace rawsmusic::auto_crossfade
