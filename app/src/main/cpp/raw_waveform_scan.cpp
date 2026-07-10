#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <csignal>
#include <csetjmp>
#include <ctime>
#include <vector>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#define RAW_WAVE_LOG_TAG "RawWaveScan"
#define WLOGE(...) __android_log_print(ANDROID_LOG_ERROR, RAW_WAVE_LOG_TAG, __VA_ARGS__)

namespace {

static thread_local sigjmp_buf g_abort_jmp_buf;
static thread_local volatile sig_atomic_t g_abort_caught = 0;

static void abort_signal_handler(int) {
    g_abort_caught = 1;
    siglongjmp(g_abort_jmp_buf, 1);
}

static int send_packet_safe(AVCodecContext *ctx, AVPacket *pkt) {
    struct sigaction old_action{};
    struct sigaction new_action{};
    new_action.sa_handler = abort_signal_handler;
    sigemptyset(&new_action.sa_mask);
    new_action.sa_flags = 0;
    g_abort_caught = 0;

    sigaction(SIGABRT, &new_action, &old_action);
    if (sigsetjmp(g_abort_jmp_buf, 1) == 0) {
        int ret = avcodec_send_packet(ctx, pkt);
        sigaction(SIGABRT, &old_action, nullptr);
        return ret;
    }

    sigaction(SIGABRT, &old_action, nullptr);
    WLOGE("caught SIGABRT from avcodec_send_packet");
    return -100;
}

static int find_audio_stream(AVFormatContext *fmt) {
    if (!fmt) return -1;
    for (unsigned int i = 0; i < fmt->nb_streams; ++i) {
        if (fmt->streams[i] &&
            fmt->streams[i]->codecpar &&
            fmt->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            return static_cast<int>(i);
        }
    }
    return -1;
}

static int64_t monotonic_elapsed_ns(const timespec &start) {
    timespec now{};
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (static_cast<int64_t>(now.tv_sec - start.tv_sec) * 1000000000LL) +
           static_cast<int64_t>(now.tv_nsec - start.tv_nsec);
}

static int64_t probe_duration_ms(AVFormatContext *fmt, AVStream *stream, AVCodecContext *codec) {
    if (fmt && fmt->duration > 0) {
        return fmt->duration / 1000;
    }
    if (stream && stream->duration > 0) {
        return av_rescale_q(stream->duration, stream->time_base, AVRational{1, 1000});
    }
    if (stream && codec && codec->sample_rate > 0 && stream->nb_frames > 0) {
        return static_cast<int64_t>(static_cast<double>(stream->nb_frames) * 1000.0 /
                                    static_cast<double>(codec->sample_rate));
    }
    return 0;
}

static int64_t channel_layout_for(const AVCodecContext *codec) {
#if LIBAVUTIL_VERSION_MAJOR >= 57
    if (codec && codec->ch_layout.nb_channels > 0) {
        int64_t mask = codec->ch_layout.u.mask;
        if (mask != 0) return mask;
        return av_get_default_channel_layout(codec->ch_layout.nb_channels);
    }
#endif
    if (codec && codec->channel_layout != 0) return codec->channel_layout;
    if (codec && codec->channels > 0) return av_get_default_channel_layout(codec->channels);
    return AV_CH_LAYOUT_STEREO;
}

static bool ensure_output_buffer(uint8_t **out_buf, int *out_buf_samples, int wanted_samples) {
    if (wanted_samples <= *out_buf_samples && *out_buf) return true;
    if (*out_buf) av_freep(out_buf);
    *out_buf_samples = 0;
    if (av_samples_alloc(out_buf, nullptr, 1, std::max(1, wanted_samples), AV_SAMPLE_FMT_FLT, 0) < 0) {
        *out_buf = nullptr;
        return false;
    }
    *out_buf_samples = std::max(1, wanted_samples);
    return true;
}

struct WindowRmsResult {
    bool ok = false;
    float mean_square = 0.0f;
};

static WindowRmsResult decode_window_mean_square(
        AVFormatContext *fmt,
        AVStream *stream,
        AVCodecContext *codec,
        SwrContext *swr,
        AVPacket *pkt,
        AVFrame *frame,
        uint8_t **out_buf,
        int *out_buf_samples,
        int audio_stream_idx,
        int out_rate,
        int64_t window_start_ms,
        int64_t window_end_ms,
        const timespec &scan_started,
        int64_t max_scan_ns) {
    WindowRmsResult result{};
    if (!fmt || !stream || !codec || !swr || !pkt || !frame || window_end_ms <= window_start_ms) {
        return result;
    }

    const int64_t seek_ts = av_rescale_q(window_start_ms * 1000, AV_TIME_BASE_Q, stream->time_base);
    if (av_seek_frame(fmt, audio_stream_idx, seek_ts, AVSEEK_FLAG_BACKWARD) < 0) {
        const int64_t global_ts = (window_start_ms * AV_TIME_BASE) / 1000;
        if (av_seek_frame(fmt, -1, global_ts, AVSEEK_FLAG_BACKWARD) < 0) {
            return result;
        }
    }
    avcodec_flush_buffers(codec);
    swr_close(swr);
    swr_init(swr);

    double sum_square = 0.0;
    int64_t count = 0;
    bool done = false;

    auto process_frame = [&](AVFrame *frm) -> bool {
        int wanted = static_cast<int>(av_rescale_rnd(
            swr_get_delay(swr, codec->sample_rate) + frm->nb_samples,
            out_rate,
            codec->sample_rate,
            AV_ROUND_UP));
        wanted = std::max(1, wanted);
        if (!ensure_output_buffer(out_buf, out_buf_samples, wanted)) return false;

        int out_samples = swr_convert(
            swr,
            out_buf,
            *out_buf_samples,
            (const uint8_t **)frm->extended_data,
            frm->nb_samples);
        if (out_samples <= 0 || !*out_buf) return true;

        int64_t frame_ms = window_start_ms;
        int64_t ts = frm->best_effort_timestamp;
        if (ts != AV_NOPTS_VALUE) {
            frame_ms = av_rescale_q(ts, stream->time_base, AVRational{1, 1000});
        }

        const float *samples = reinterpret_cast<const float *>(*out_buf);
        for (int i = 0; i < out_samples; ++i) {
            int64_t sample_ms = frame_ms +
                static_cast<int64_t>(static_cast<double>(i) * 1000.0 / static_cast<double>(out_rate));
            if (sample_ms < window_start_ms) continue;
            if (sample_ms >= window_end_ms) {
                done = true;
                return false;
            }
            float amp = samples[i];
            if (!std::isfinite(amp)) amp = 0.0f;
            amp = std::fabs(amp);
            if (amp > 1.0f) amp = 1.0f;
            sum_square += static_cast<double>(amp) * static_cast<double>(amp);
            ++count;
        }
        return true;
    };

    while (!done && av_read_frame(fmt, pkt) >= 0) {
        if (monotonic_elapsed_ns(scan_started) >= max_scan_ns) {
            av_packet_unref(pkt);
            return result;
        }
        if (pkt->stream_index == audio_stream_idx) {
            int ret = send_packet_safe(codec, pkt);
            if (ret < 0) {
                av_packet_unref(pkt);
                return result;
            }
            while (ret >= 0) {
                ret = avcodec_receive_frame(codec, frame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                if (ret < 0) break;
                if (!process_frame(frame)) {
                    av_frame_unref(frame);
                    break;
                }
                av_frame_unref(frame);
            }
        }
        av_packet_unref(pkt);
    }

    if (count > 0) {
        result.ok = true;
        result.mean_square = static_cast<float>(sum_square / static_cast<double>(count));
    }
    return result;
}

static std::vector<float> resample_and_normalize_poweramp_like(const std::vector<float> &raw, int target_count) {
    std::vector<float> empty;
    const int raw_count = static_cast<int>(raw.size());
    if (raw_count <= 0 || target_count <= 0) return empty;

    std::vector<float> result(target_count, 0.0f);
    float max_value = 0.0f;

    if (raw_count < target_count) {
        float pos = 0.0f;
        const float step = static_cast<float>(raw_count) / static_cast<float>(target_count);
        for (int i = 0; i < target_count; ++i) {
            int idx = static_cast<int>(pos + 0.5f);
            if (idx >= raw_count) idx = raw_count - 1;
            const float value = std::sqrt(std::max(0.0f, raw[idx]));
            result[i] = value;
            max_value = std::max(max_value, value);
            pos += step;
        }
    } else {
        for (int i = 0; i < target_count; ++i) {
            int begin = static_cast<int>(std::floor(static_cast<double>(i) * raw_count / target_count));
            int end = static_cast<int>(std::floor(static_cast<double>(i + 1) * raw_count / target_count));
            if (end <= begin) end = begin + 1;
            begin = std::max(0, std::min(begin, raw_count - 1));
            end = std::max(begin + 1, std::min(end, raw_count));
            float sum = 0.0f;
            int count = 0;
            for (int j = begin; j < end; ++j) {
                sum += raw[j];
                ++count;
            }
            const float value = count > 0
                ? std::sqrt(std::max(0.0f, sum / static_cast<float>(count)))
                : 0.0f;
            result[i] = value;
            max_value = std::max(max_value, value);
        }
    }

    if (max_value <= 0.000001f) return empty;
    const float gain = 1.0f / max_value;
    for (float &value : result) {
        value = std::max(0.0f, std::min(value * gain, 1.0f));
    }
    return result;
}

} // namespace

std::vector<float> rawsmusic_scan_waveform_poweramp_seek(
        const char *input_path,
        int64_t start_ms,
        int64_t end_ms,
        int sample_count) {
    std::vector<float> empty;
    if (!input_path || !*input_path) return empty;

    const int target_count = std::max(32, std::min(sample_count > 0 ? sample_count : 100, 100));
    const int64_t max_scan_ns = 2000000000LL;
    const int64_t window_ms = 500;

    AVFormatContext *fmt = nullptr;
    AVCodecContext *codec = nullptr;
    SwrContext *swr = nullptr;
    AVPacket *pkt = nullptr;
    AVFrame *frame = nullptr;
    uint8_t *out_buf = nullptr;
    int out_buf_samples = 0;

    int audio_stream_idx = -1;
    AVStream *stream = nullptr;
    const AVCodec *decoder = nullptr;
    int out_rate = 44100;
    int64_t full_duration_ms = 0;
    int64_t segment_ms = 0;

    timespec scan_started{};
    clock_gettime(CLOCK_MONOTONIC, &scan_started);

    if (avformat_open_input(&fmt, input_path, nullptr, nullptr) < 0) {
        WLOGE("open failed: %s", input_path);
        goto cleanup;
    }
    if (avformat_find_stream_info(fmt, nullptr) < 0) {
        WLOGE("stream info failed: %s", input_path);
        goto cleanup;
    }

    audio_stream_idx = find_audio_stream(fmt);
    if (audio_stream_idx < 0) {
        WLOGE("no audio stream: %s", input_path);
        goto cleanup;
    }

    stream = fmt->streams[audio_stream_idx];
    decoder = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!decoder) {
        WLOGE("decoder not found: %s", input_path);
        goto cleanup;
    }

    codec = avcodec_alloc_context3(decoder);
    if (!codec) goto cleanup;
    if (avcodec_parameters_to_context(codec, stream->codecpar) < 0) goto cleanup;
    if (avcodec_open2(codec, decoder, nullptr) < 0) {
        WLOGE("decoder open failed: %s", input_path);
        goto cleanup;
    }

    if (codec->sample_rate <= 0) codec->sample_rate = 44100;
    out_rate = codec->sample_rate;

    full_duration_ms = probe_duration_ms(fmt, stream, codec);
    start_ms = std::max<int64_t>(0, start_ms);
    if (end_ms <= start_ms || (full_duration_ms > 0 && end_ms > full_duration_ms + 1000)) {
        end_ms = full_duration_ms;
    }
    if (end_ms <= start_ms) {
        WLOGE("invalid segment start=%lld end=%lld full=%lld path=%s",
              static_cast<long long>(start_ms),
              static_cast<long long>(end_ms),
              static_cast<long long>(full_duration_ms),
              input_path);
        goto cleanup;
    }

    segment_ms = end_ms - start_ms;
    if (segment_ms <= 2000) {
        WLOGE("segment too short: %lldms path=%s", static_cast<long long>(segment_ms), input_path);
        goto cleanup;
    }

    {
        const int64_t stride_ms = segment_ms >= 50000
            ? std::max<int64_t>(1, segment_ms / 200)
            : 250;
        const int raw_count = segment_ms >= 50000
            ? 200
            : std::max(1, static_cast<int>(segment_ms / 250));
        const int64_t first_window_offset_ms = stride_ms + (stride_ms >> 2);

        const int64_t in_layout = channel_layout_for(codec);
        const int64_t out_layout = AV_CH_LAYOUT_MONO;
        AVSampleFormat in_fmt = codec->sample_fmt == AV_SAMPLE_FMT_NONE
            ? AV_SAMPLE_FMT_FLTP
            : codec->sample_fmt;

        swr = swr_alloc_set_opts(nullptr,
                                 out_layout, AV_SAMPLE_FMT_FLT, out_rate,
                                 in_layout, in_fmt, codec->sample_rate,
                                 0, nullptr);
        if (!swr || swr_init(swr) < 0) {
            WLOGE("swr init failed: %s", input_path);
            goto cleanup;
        }

        pkt = av_packet_alloc();
        frame = av_frame_alloc();
        if (!pkt || !frame) goto cleanup;

        std::vector<float> raw;
        raw.reserve(raw_count);
        int populated = 0;

        for (int i = 0; i < raw_count; ++i) {
            if (monotonic_elapsed_ns(scan_started) >= max_scan_ns) {
                WLOGE("MAX_TIME_TO_SCAN_NS, skipping %s", input_path);
                goto cleanup;
            }

            const int64_t window_start_ms = start_ms + first_window_offset_ms + static_cast<int64_t>(i) * stride_ms;
            if (window_start_ms >= end_ms) break;
            const int64_t window_end_ms = std::min<int64_t>(end_ms, window_start_ms + window_ms);

            WindowRmsResult window = decode_window_mean_square(
                fmt,
                stream,
                codec,
                swr,
                pkt,
                frame,
                &out_buf,
                &out_buf_samples,
                audio_stream_idx,
                out_rate,
                window_start_ms,
                window_end_ms,
                scan_started,
                max_scan_ns);

            raw.push_back(window.ok ? window.mean_square : 0.0f);
            if (window.ok && window.mean_square > 0.0f) ++populated;
        }

        if (populated <= 0) goto cleanup;
        empty = resample_and_normalize_poweramp_like(raw, target_count);
    }

cleanup:
    if (out_buf) av_freep(&out_buf);
    if (pkt) av_packet_free(&pkt);
    if (frame) av_frame_free(&frame);
    if (swr) swr_free(&swr);
    if (codec) avcodec_free_context(&codec);
    if (fmt) avformat_close_input(&fmt);
    return empty;
}
