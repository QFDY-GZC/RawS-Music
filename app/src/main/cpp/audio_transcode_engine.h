#pragma once

#include <atomic>
#include <cstdint>
#include <string>

namespace rawsmusic::transcode {

constexpr int kTranscodeOk = 0;
constexpr int kTranscodeCancelled = -70001;
constexpr int kTranscodeInvalidArgument = -70002;
constexpr int kTranscodeUnsupportedSource = -70003;
constexpr int kTranscodeUnsupportedTarget = -70004;
constexpr int kTranscodeIoError = -70005;

struct AudioProbeResult {
    int sample_rate_hz = 0;
    int bit_depth = 0;
    int channels = 0;
    int64_t duration_ms = 0;
    int64_t bit_rate = 0;
    int codec_id = 0;
    bool is_dsd = false;
    int dsd_sample_rate_hz = 0;
    bool bit_depth_meaningful = true;
    bool has_video_stream = false;
};

class AudioTranscodeSession {
public:
    void cancel() { cancelled_.store(true, std::memory_order_release); }
    bool cancelled() const { return cancelled_.load(std::memory_order_acquire); }

    void setProgressPermille(int progress) {
        if (progress < 0) progress = 0;
        if (progress > 1000) progress = 1000;
        progress_permille_.store(progress, std::memory_order_release);
    }

    int progressPermille() const {
        return progress_permille_.load(std::memory_order_acquire);
    }

    void clearLastErrorDetail() { last_error_detail_.clear(); }
    void setLastErrorDetail(const std::string& detail) { last_error_detail_ = detail; }
    const std::string& lastErrorDetail() const { return last_error_detail_; }

private:
    std::atomic<bool> cancelled_{false};
    std::atomic<int> progress_permille_{0};
    std::string last_error_detail_;
};

int probeAudioFile(const char* input_path, AudioProbeResult& result);

int transcodeLosslessPcm(
    AudioTranscodeSession* session,
    const char* input_path,
    const char* output_path,
    const char* target_id,
    int requested_sample_rate_hz,
    int requested_bit_depth,
    int flac_compression_level,
    bool enable_auto_dither
);

int transcodeLossyPcm(
    AudioTranscodeSession* session,
    const char* input_path,
    const char* output_path,
    const char* target_id,
    int requested_sample_rate_hz,
    int bit_rate_kbps
);

struct PcmDigestResult {
    std::string sha256;
    int64_t frames = 0;
};

int computeCanonicalPcmDigest(
    AudioTranscodeSession* session,
    const char* input_path,
    int sample_rate_hz,
    int bit_depth,
    PcmDigestResult& result
);

int transcodePcmToDsf(
    AudioTranscodeSession* session,
    const char* input_path,
    const char* output_path,
    int dsd_multiplier
);

/**
 * Repackages an existing raw DSD elementary stream into DSF without decoding/remodulation.
 * The source DSD clock must already match the requested DSD multiplier.
 */
int remuxDsdToDsf(
    AudioTranscodeSession* session,
    const char* input_path,
    const char* output_path,
    int dsd_multiplier
);

}  // namespace rawsmusic::transcode
