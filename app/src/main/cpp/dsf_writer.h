#pragma once

#include <array>
#include <cstdint>
#include <fstream>
#include <string>

namespace rawsmusic::transcode {

struct DsfProbeResult {
    int sample_rate_hz = 0;
    int channels = 0;
    int bits_per_sample = 0;
    uint64_t sample_count = 0;
    int block_size_per_channel = 0;
    int64_t duration_ms = 0;
    uint64_t file_size = 0;
    uint64_t metadata_offset = 0;
    uint64_t data_chunk_size = 0;
};

/**
 * Minimal streaming DSF v1.01 writer.
 *
 * The realtime PCM->DSD core emits bytes with the first generated DSD bit in
 * the most-significant bit. DSF represents that ordering with bits-per-sample
 * = 8. Audio is buffered into the format-mandated 4096-byte planar blocks per
 * channel and the final unused area is zero-filled without increasing the
 * logical sample count.
 */
class DsfWriter {
public:
    DsfWriter() = default;
    ~DsfWriter();

    DsfWriter(const DsfWriter&) = delete;
    DsfWriter& operator=(const DsfWriter&) = delete;

    bool open(const char* path, int dsd_sample_rate_hz, int channels);
    bool writeChannelData(
        const uint8_t* channel0,
        const uint8_t* channel1,
        uint32_t bytes_per_channel
    );
    bool finalize();
    void close();

    uint64_t logicalSampleCount() const { return logical_bytes_per_channel_ * 8ULL; }
    uint64_t paddedAudioBytes() const { return padded_audio_bytes_; }
    int sampleRateHz() const { return sample_rate_hz_; }
    int channels() const { return channels_; }

private:
    static constexpr size_t kBlockSize = 4096;

    bool writeInitialHeader();
    bool flushBlockGroup(bool pad_partial);
    bool patchU64(std::streamoff offset, uint64_t value);

    std::fstream file_;
    std::string path_;
    int sample_rate_hz_ = 0;
    int channels_ = 0;
    size_t block_fill_ = 0;
    uint64_t logical_bytes_per_channel_ = 0;
    uint64_t padded_audio_bytes_ = 0;
    std::array<std::array<uint8_t, kBlockSize>, 2> blocks_{};
    bool opened_ = false;
    bool finalized_ = false;
};

bool probeDsfFile(const char* path, DsfProbeResult& result);

}  // namespace rawsmusic::transcode
