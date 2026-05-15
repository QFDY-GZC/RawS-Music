#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <string>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/dict.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

#define LOG_TAG "FFmpegBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==========================
// Helper functions for PCM output
// ==========================
static int normalize_bits_per_sample(int bits) {
    if (bits <= 16) return 16;
    if (bits <= 24) return 24;
    return 32;
}
static int bytes_per_sample_for_bits(int bits) {
    if (bits <= 16) return 2;
    // 24bit 和 32bit 都用 S32LE (4 bytes/sample)，与 USB 引擎格式统一
    return 4;
}
static AVSampleFormat swr_output_format_for_bits(int bits) {
    if (bits <= 16) return AV_SAMPLE_FMT_S16;
    // 24bit 和 32bit 都输出 S32LE，避免 packed s24le 和 float 的格式不匹配
    return AV_SAMPLE_FMT_S32;
}
static const char *pcm_format_name_for_bits(int bits) {
    if (bits <= 16) return "s16le";
    return "s32le";  // 24bit/32bit 都用 S32LE
}
static int sample_format_bits(AVSampleFormat fmt) {
    switch (fmt) {
        case AV_SAMPLE_FMT_U8:
        case AV_SAMPLE_FMT_U8P:
            return 8;
        case AV_SAMPLE_FMT_S16:
        case AV_SAMPLE_FMT_S16P:
            return 16;
        case AV_SAMPLE_FMT_S32:
        case AV_SAMPLE_FMT_S32P:
        case AV_SAMPLE_FMT_FLT:
        case AV_SAMPLE_FMT_FLTP:
            return 32;
        case AV_SAMPLE_FMT_DBL:
        case AV_SAMPLE_FMT_DBLP:
            return 64;
        default:
            return 0;
    }
}
// 对于有损压缩格式，根据解码器默认输出格式推断位深
static int lossy_codec_default_bits(enum AVCodecID codec_id) {
    // MP3 解码器默认输出 S16P
    if (codec_id == AV_CODEC_ID_MP3) return 16;
    // AAC 解码器默认输出 FLTP (float planar)
    if (codec_id == AV_CODEC_ID_AAC) return 32;
    // Vorbis/Opus 解码器默认输出 FLTP
    if (codec_id == AV_CODEC_ID_VORBIS || codec_id == AV_CODEC_ID_OPUS) return 32;
    // WMA 解码器默认输出 S16
    if (codec_id == AV_CODEC_ID_WMAV1 || codec_id == AV_CODEC_ID_WMAV2) return 16;
    // 其他有损格式默认 16 位
    return 16;
}

static int detect_bits_per_sample(const AVCodecParameters *codecpar) {
    if (!codecpar) return 0;
    if (codecpar->bits_per_raw_sample > 0) {
        return codecpar->bits_per_raw_sample;
    }
    if (codecpar->bits_per_coded_sample > 0) {
        return codecpar->bits_per_coded_sample;
    }
    int codec_bits = av_get_bits_per_sample(codecpar->codec_id);
    if (codec_bits > 0) {
        return codec_bits;
    }
    // 对于有损压缩格式（av_get_bits_per_sample 返回 0），
    // 直接根据 codec_id 推断源文件位深。
    // 注意：不要从解码器的输出格式推断，因为解码器可能输出 float 用于
    // 内部处理精度（如 mp3float 输出 FLTP=32bit），但这不代表源文件实际位深。
    if (codecpar->codec_id != AV_CODEC_ID_NONE) {
        return lossy_codec_default_bits(codecpar->codec_id);
    }
    return sample_format_bits((AVSampleFormat)codecpar->format);
}
static int detect_channel_count(const AVCodecParameters *codecpar) {
    if (!codecpar) return 0;
    if (codecpar->channels > 0) return codecpar->channels;
    return 0;
}
static int find_audio_stream(AVFormatContext *fmt_ctx) {
    if (!fmt_ctx) return -1;
    for (unsigned int i = 0; i < fmt_ctx->nb_streams; i++) {
        if (fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            return (int)i;
        }
    }
    return -1;
}
static int write_pcm_samples(
    FILE *out_fp,
    const uint8_t *out_buf,
    int out_samples,
    int out_channels,
    int bits_per_sample
) {
    if (!out_fp || !out_buf || out_samples <= 0 || out_channels <= 0) {
        return 0;
    }
    const int normalized_bits = normalize_bits_per_sample(bits_per_sample);
    if (normalized_bits == 16) {
        const int size = out_samples * out_channels * 2;
        fwrite(out_buf, 1, size, out_fp);
        return size;
    }
    // 24bit 和 32bit 都直接写 S32LE (4 bytes/sample)
    {
        const int size = out_samples * out_channels * 4;
        fwrite(out_buf, 1, size, out_fp);
        return size;
    }
}

/**
 * 将音频转为 WAV 文件，支持可变比特深度（16/24/32bit）和高质量重采样。
 *
 * bits_per_sample: 16 -> s16le, 24/32 -> s32le
 * channels: 输出声道数，0 或负值默认为 2（立体声）
 */
static int convert_to_wav(const char *input_path, const char *output_path,
                          int target_sample_rate, int bits_per_sample, int channels) {
    AVFormatContext *fmt_ctx = nullptr;
    AVCodecContext *codec_ctx = nullptr;
    SwrContext *swr_ctx = nullptr;
    FILE *out_fp = nullptr;
    uint8_t *out_buf = nullptr;
    int ret = -1;
    int audio_stream_idx = -1;

    if (avformat_open_input(&fmt_ctx, input_path, nullptr, nullptr) < 0) {
        LOGE("Could not open input: %s", input_path);
        return -1;
    }

    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        LOGE("Could not find stream info");
        goto cleanup;
    }

    for (unsigned int i = 0; i < fmt_ctx->nb_streams; i++) {
        if (fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            audio_stream_idx = i;
            break;
        }
    }
    if (audio_stream_idx < 0) {
        LOGE("No audio stream found");
        goto cleanup;
    }

    {
        const AVCodec *codec = avcodec_find_decoder(fmt_ctx->streams[audio_stream_idx]->codecpar->codec_id);
        if (!codec) {
            LOGE("Unsupported codec");
            goto cleanup;
        }

        codec_ctx = avcodec_alloc_context3(codec);
        if (!codec_ctx) {
            LOGE("Could not allocate codec context");
            goto cleanup;
        }

        if (avcodec_parameters_to_context(codec_ctx, fmt_ctx->streams[audio_stream_idx]->codecpar) < 0) {
            LOGE("Could not copy codec params");
            goto cleanup;
        }

        if (avcodec_open2(codec_ctx, codec, nullptr) < 0) {
            LOGE("Could not open codec");
            goto cleanup;
        }

        int out_sample_rate = target_sample_rate > 0 ? target_sample_rate : codec_ctx->sample_rate;
        if (out_sample_rate <= 0) out_sample_rate = 44100;
        const int out_channels = channels > 0 ? channels : 2;
        const int out_bits = normalize_bits_per_sample(bits_per_sample);
        const int out_file_bytes_per_sample = bytes_per_sample_for_bits(out_bits);
        const int out_swr_bytes_per_sample = out_bits == 16 ? 2 : 4;
        const AVSampleFormat out_fmt = swr_output_format_for_bits(out_bits);

        // 获取输入声道布局，兼容 channel_layout=0 的情况
        int64_t in_ch_layout = codec_ctx->channel_layout;
        if (in_ch_layout == 0) {
            in_ch_layout = av_get_default_channel_layout(codec_ctx->channels);
        }
        if (in_ch_layout == 0) {
            in_ch_layout = AV_CH_LAYOUT_STEREO;
        }

        int64_t out_ch_layout = av_get_default_channel_layout(out_channels);

        LOGI("FFmpeg convert_to_wav: target_rate=%d out_channels=%d out_bits=%d out_fmt=%s",
             out_sample_rate, out_channels, out_bits, av_get_sample_fmt_name(out_fmt));
        LOGI("swr setup: in_fmt=%d(%s) in_rate=%d in_ch=%d in_layout=%lld",
             codec_ctx->sample_fmt,
             av_get_sample_fmt_name(codec_ctx->sample_fmt),
             codec_ctx->sample_rate,
             codec_ctx->channels,
             (long long)in_ch_layout);

        swr_ctx = swr_alloc_set_opts(nullptr,
            out_ch_layout, out_fmt, out_sample_rate,
            in_ch_layout, codec_ctx->sample_fmt, codec_ctx->sample_rate,
            0, nullptr);
        if (!swr_ctx) {
            LOGE("Could not allocate SwrContext");
            goto cleanup;
        }

        // 高质量重采样参数
        // filter_size: FIR 滤波器大小（默认16，增大可提高质量）
        // phase_shift: 相位精度（默认10）
        // cutoff: 截止频率比例（0=自动，约0.97）
        av_opt_set_int(swr_ctx, "filter_size", 32, 0);
        av_opt_set_int(swr_ctx, "phase_shift", 10, 0);

        if (swr_init(swr_ctx) < 0) {
            LOGE("Could not init SwrContext (in_fmt=%d, in_rate=%d, in_ch=%d, in_layout=%lld)",
                 codec_ctx->sample_fmt, codec_ctx->sample_rate, codec_ctx->channels, (long long)in_ch_layout);
            goto cleanup;
        }

        out_fp = fopen(output_path, "wb");
        if (!out_fp) {
            LOGE("Could not open output: %s", output_path);
            goto cleanup;
        }

        uint32_t total_data_size = 0;
        uint16_t block_align = out_channels * out_file_bytes_per_sample;
        uint32_t byte_rate = out_sample_rate * block_align;

        // 写 WAV 头
        fwrite("RIFF", 1, 4, out_fp);
        uint32_t riff_size = 0;
        fwrite(&riff_size, 4, 1, out_fp);
        fwrite("WAVE", 1, 4, out_fp);
        fwrite("fmt ", 1, 4, out_fp);
        uint32_t fmt_size = 16;
        fwrite(&fmt_size, 4, 1, out_fp);
        // 24bit/32bit 都使用 S32LE PCM 格式 (wFormatTag=1)
        uint16_t audio_fmt = 1;  // 1=PCM
        fwrite(&audio_fmt, 2, 1, out_fp);
        uint16_t ch = out_channels;
        fwrite(&ch, 2, 1, out_fp);
        uint32_t sr = out_sample_rate;
        fwrite(&sr, 4, 1, out_fp);
        fwrite(&byte_rate, 4, 1, out_fp);
        fwrite(&block_align, 2, 1, out_fp);
        uint16_t wav_bits = out_bits;
        fwrite(&wav_bits, 2, 1, out_fp);
        fwrite("data", 1, 4, out_fp);
        uint32_t data_size_placeholder = 0;
        fwrite(&data_size_placeholder, 4, 1, out_fp);

        // 预分配输出缓冲区
        int max_out_samples = out_sample_rate / 50 + 8192;
        out_buf = (uint8_t *)av_malloc(max_out_samples * out_channels * out_swr_bytes_per_sample);
        if (!out_buf) {
            LOGE("Could not allocate output buffer");
            goto cleanup;
        }

        AVPacket *pkt = av_packet_alloc();
        AVFrame *frame = av_frame_alloc();

        while (av_read_frame(fmt_ctx, pkt) >= 0) {
            if (pkt->stream_index == audio_stream_idx) {
                ret = avcodec_send_packet(codec_ctx, pkt);
                while (ret >= 0) {
                    ret = avcodec_receive_frame(codec_ctx, frame);
                    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                    if (ret < 0) break;

                    // 确保 out_buf 足够大
                    int needed_samples = frame->nb_samples * 4;
                    if (needed_samples > max_out_samples) {
                        int needed_size = needed_samples * out_channels * out_swr_bytes_per_sample;
                        uint8_t *new_buf = (uint8_t *)av_realloc(out_buf, needed_size);
                        if (new_buf) {
                            out_buf = new_buf;
                            max_out_samples = needed_samples;
                        }
                    }

                    // 使用 extended_data 兼容多声道/平面格式
                    int out_samples = swr_convert(swr_ctx, &out_buf, max_out_samples,
                        (const uint8_t **)frame->extended_data, frame->nb_samples);
                    if (out_samples > 0) {
                        int written = write_pcm_samples(out_fp, out_buf, out_samples, out_channels, out_bits);
                        total_data_size += written;
                    }
                }
            }
            av_packet_unref(pkt);
        }

        // 刷出解码器中剩余帧
        {
            ret = avcodec_send_packet(codec_ctx, nullptr);
            while (ret >= 0) {
                ret = avcodec_receive_frame(codec_ctx, frame);
                if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                if (ret < 0) break;

                int needed_samples = frame->nb_samples * 4;
                if (needed_samples > max_out_samples) {
                    int needed_size = needed_samples * out_channels * out_swr_bytes_per_sample;
                    uint8_t *new_buf = (uint8_t *)av_realloc(out_buf, needed_size);
                    if (new_buf) {
                        out_buf = new_buf;
                        max_out_samples = needed_samples;
                    }
                }

                int out_samples = swr_convert(swr_ctx, &out_buf, max_out_samples,
                    (const uint8_t **)frame->extended_data, frame->nb_samples);
                if (out_samples > 0) {
                    int written = write_pcm_samples(out_fp, out_buf, out_samples, out_channels, out_bits);
                    total_data_size += written;
                }
            }
        }

        // 刷出 swr 缓冲区中剩余数据
        {
            int out_samples = swr_convert(swr_ctx, &out_buf, max_out_samples, nullptr, 0);
            while (out_samples > 0) {
                int written = write_pcm_samples(out_fp, out_buf, out_samples, out_channels, out_bits);
                total_data_size += written;
                out_samples = swr_convert(swr_ctx, &out_buf, max_out_samples, nullptr, 0);
            }
        }

        av_frame_free(&frame);
        av_packet_free(&pkt);

        // 回填 RIFF 和 data chunk 大小
        riff_size = 36 + total_data_size;
        fseek(out_fp, 4, SEEK_SET);
        fwrite(&riff_size, 4, 1, out_fp);
        fseek(out_fp, 40, SEEK_SET);
        fwrite(&total_data_size, 4, 1, out_fp);

        LOGI("FFmpeg convert_to_wav done: %u bytes PCM, %d Hz %dch %dbit",
             total_data_size, out_sample_rate, out_channels, out_bits);

        ret = total_data_size > 0 ? 0 : -1;
    }

cleanup:
    if (out_buf) { av_free(out_buf); out_buf = nullptr; }
    if (out_fp) fclose(out_fp);
    if (swr_ctx) swr_free(&swr_ctx);
    if (codec_ctx) avcodec_free_context(&codec_ctx);
    if (fmt_ctx) avformat_close_input(&fmt_ctx);
    return ret;
}

/**
 * 将音频转为裸 PCM，不写 WAV 头。
 *
 * bits_per_sample:
 * 16 -> s16le
 * 24 -> s32le (4 bytes/sample,与 USB 引擎格式统一)
 * 32 -> s32le
 */
static int convert_to_raw_pcm(
    const char *input_path,
    const char *output_path,
    int target_sample_rate,
    int bits_per_sample,
    int channels
) {
    AVFormatContext *fmt_ctx = nullptr;
    AVCodecContext *codec_ctx = nullptr;
    SwrContext *swr_ctx = nullptr;
    FILE *out_fp = nullptr;
    uint8_t *out_buf = nullptr;
    AVPacket *pkt = nullptr;
    AVFrame *frame = nullptr;
    int ret = -1;
    int audio_stream_idx = -1;
    const int out_sample_rate = target_sample_rate > 0 ? target_sample_rate : 48000;
    const int out_channels = channels > 0 ? channels : 2;
    const int out_bits = normalize_bits_per_sample(bits_per_sample);
    const int out_file_bytes_per_sample = bytes_per_sample_for_bits(out_bits);
    const int out_swr_bytes_per_sample = out_bits == 16 ? 2 : 4;
    const AVSampleFormat out_fmt = swr_output_format_for_bits(out_bits);

    if (avformat_open_input(&fmt_ctx, input_path, nullptr, nullptr) < 0) {
        LOGE("convert_to_raw_pcm: Could not open input: %s", input_path);
        return -1;
    }

    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        LOGE("convert_to_raw_pcm: Could not find stream info");
        goto cleanup_pcm;
    }

    audio_stream_idx = find_audio_stream(fmt_ctx);
    if (audio_stream_idx < 0) {
        LOGE("convert_to_raw_pcm: No audio stream found");
        goto cleanup_pcm;
    }

    {
        AVCodecParameters *codecpar = fmt_ctx->streams[audio_stream_idx]->codecpar;
        const AVCodec *codec = avcodec_find_decoder(codecpar->codec_id);
        if (!codec) {
            LOGE("convert_to_raw_pcm: Unsupported codec");
            goto cleanup_pcm;
        }

        codec_ctx = avcodec_alloc_context3(codec);
        if (!codec_ctx) {
            LOGE("convert_to_raw_pcm: Could not allocate codec context");
            goto cleanup_pcm;
        }

        if (avcodec_parameters_to_context(codec_ctx, codecpar) < 0) {
            LOGE("convert_to_raw_pcm: Could not copy codec params");
            goto cleanup_pcm;
        }

        if (avcodec_open2(codec_ctx, codec, nullptr) < 0) {
            LOGE("convert_to_raw_pcm: Could not open codec");
            goto cleanup_pcm;
        }

        int64_t in_ch_layout = codec_ctx->channel_layout;
        if (in_ch_layout == 0) {
            in_ch_layout = av_get_default_channel_layout(codec_ctx->channels);
        }
        if (in_ch_layout == 0) {
            in_ch_layout = AV_CH_LAYOUT_STEREO;
        }
        int64_t out_ch_layout = av_get_default_channel_layout(out_channels);

        LOGI(
            "FFmpeg convert_to_raw_pcm: input=%s output=%s target=%dHz %dch %dbit %s",
            input_path,
            output_path,
            out_sample_rate,
            out_channels,
            out_bits,
            pcm_format_name_for_bits(out_bits)
        );
        LOGI(
            "swr setup: in_fmt=%d(%s) in_rate=%d in_ch=%d in_layout=%lld out_fmt=%d(%s)",
            codec_ctx->sample_fmt,
            av_get_sample_fmt_name(codec_ctx->sample_fmt),
            codec_ctx->sample_rate,
            codec_ctx->channels,
            (long long)in_ch_layout,
            out_fmt,
            av_get_sample_fmt_name(out_fmt)
        );

        swr_ctx = swr_alloc_set_opts(
            nullptr,
            out_ch_layout,
            out_fmt,
            out_sample_rate,
            in_ch_layout,
            codec_ctx->sample_fmt,
            codec_ctx->sample_rate,
            0,
            nullptr
        );
        if (!swr_ctx || swr_init(swr_ctx) < 0) {
            LOGE("convert_to_raw_pcm: Could not init SwrContext");
            goto cleanup_pcm;
        }

        out_fp = fopen(output_path, "wb");
        if (!out_fp) {
            LOGE("convert_to_raw_pcm: Could not open output: %s", output_path);
            goto cleanup_pcm;
        }

        int max_out_samples = out_sample_rate / 50 + 8192;
        out_buf = (uint8_t *)av_malloc(max_out_samples * out_channels * out_swr_bytes_per_sample);
        if (!out_buf) {
            LOGE("convert_to_raw_pcm: Could not allocate output buffer");
            goto cleanup_pcm;
        }

        pkt = av_packet_alloc();
        frame = av_frame_alloc();
        if (!pkt || !frame) {
            LOGE("convert_to_raw_pcm: Could not allocate packet/frame");
            goto cleanup_pcm;
        }

        uint64_t total_data_size = 0;
        while (av_read_frame(fmt_ctx, pkt) >= 0) {
            if (pkt->stream_index == audio_stream_idx) {
                ret = avcodec_send_packet(codec_ctx, pkt);
                while (ret >= 0) {
                    ret = avcodec_receive_frame(codec_ctx, frame);
                    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
                    if (ret < 0) break;

                    int needed_samples = frame->nb_samples * 4;
                    if (needed_samples > max_out_samples) {
                        int needed_size = needed_samples * out_channels * out_swr_bytes_per_sample;
                        uint8_t *new_buf = (uint8_t *)av_realloc(out_buf, needed_size);
                        if (!new_buf) {
                            LOGE("convert_to_raw_pcm: Could not grow output buffer");
                            goto cleanup_pcm;
                        }
                        out_buf = new_buf;
                        max_out_samples = needed_samples;
                    }

                    int out_samples = swr_convert(
                        swr_ctx,
                        &out_buf,
                        max_out_samples,
                        (const uint8_t **)frame->extended_data,
                        frame->nb_samples
                    );
                    if (out_samples > 0) {
                        int written = write_pcm_samples(
                            out_fp,
                            out_buf,
                            out_samples,
                            out_channels,
                            out_bits
                        );
                        total_data_size += written;
                    }
                }
            }
            av_packet_unref(pkt);
        }

        // 刷出解码器剩余帧
        ret = avcodec_send_packet(codec_ctx, nullptr);
        while (ret >= 0) {
            ret = avcodec_receive_frame(codec_ctx, frame);
            if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
            if (ret < 0) break;

            int needed_samples = frame->nb_samples * 4;
            if (needed_samples > max_out_samples) {
                int needed_size = needed_samples * out_channels * out_swr_bytes_per_sample;
                uint8_t *new_buf = (uint8_t *)av_realloc(out_buf, needed_size);
                if (!new_buf) {
                    LOGE("convert_to_raw_pcm: Could not grow output buffer while flushing");
                    goto cleanup_pcm;
                }
                out_buf = new_buf;
                max_out_samples = needed_samples;
            }

            int out_samples = swr_convert(
                swr_ctx,
                &out_buf,
                max_out_samples,
                (const uint8_t **)frame->extended_data,
                frame->nb_samples
            );
            if (out_samples > 0) {
                int written = write_pcm_samples(
                    out_fp,
                    out_buf,
                    out_samples,
                    out_channels,
                    out_bits
                );
                total_data_size += written;
            }
        }

        // 刷出 swr 缓冲区
        while (true) {
            int out_samples = swr_convert(swr_ctx, &out_buf, max_out_samples, nullptr, 0);
            if (out_samples <= 0) break;
            int written = write_pcm_samples(
                out_fp,
                out_buf,
                out_samples,
                out_channels,
                out_bits
            );
            total_data_size += written;
        }

        double approx_sec = total_data_size / (double)(
            out_sample_rate * out_channels * out_file_bytes_per_sample
        );
        LOGI(
            "FFmpeg convert_to_raw_pcm done: %llu bytes, %dHz %dch %dbit %s, approx %.1f sec",
            (unsigned long long)total_data_size,
            out_sample_rate,
            out_channels,
            out_bits,
            pcm_format_name_for_bits(out_bits),
            approx_sec
        );
        ret = total_data_size > 0 ? 0 : -1;
    }

cleanup_pcm:
    if (frame) av_frame_free(&frame);
    if (pkt) av_packet_free(&pkt);
    if (out_buf) {
        av_free(out_buf);
        out_buf = nullptr;
    }
    if (out_fp) fclose(out_fp);
    if (swr_ctx) swr_free(&swr_ctx);
    if (codec_ctx) avcodec_free_context(&codec_ctx);
    if (fmt_ctx) avformat_close_input(&fmt_ctx);
    return ret;
}

static jlong probe_duration(const char *path) {
    AVFormatContext *fmt_ctx = nullptr;
    if (avformat_open_input(&fmt_ctx, path, nullptr, nullptr) < 0) return 0;
    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        avformat_close_input(&fmt_ctx);
        return 0;
    }
    int64_t dur = fmt_ctx->duration;
    avformat_close_input(&fmt_ctx);
    return dur / 1000;
}

static jint probe_sample_rate(const char *path) {
    AVFormatContext *fmt_ctx = nullptr;
    if (avformat_open_input(&fmt_ctx, path, nullptr, nullptr) < 0) return 0;
    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        avformat_close_input(&fmt_ctx);
        return 0;
    }
    int sr = 0;
    for (unsigned int i = 0; i < fmt_ctx->nb_streams; i++) {
        if (fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            sr = fmt_ctx->streams[i]->codecpar->sample_rate;
            break;
        }
    }
    avformat_close_input(&fmt_ctx);
    return sr;
}

static jint probe_bits_per_sample(const char *path) {
    AVFormatContext *fmt_ctx = nullptr;
    if (avformat_open_input(&fmt_ctx, path, nullptr, nullptr) < 0) {
        LOGE("probe_bits_per_sample: failed to open %s", path);
        return 0;
    }
    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        LOGE("probe_bits_per_sample: failed to find stream info");
        avformat_close_input(&fmt_ctx);
        return 0;
    }
    int bits = 0;
    int audio_stream_idx = find_audio_stream(fmt_ctx);
    if (audio_stream_idx >= 0) {
        AVCodecParameters *codecpar = fmt_ctx->streams[audio_stream_idx]->codecpar;
        bits = detect_bits_per_sample(codecpar);
        LOGI("probe_bits_per_sample: path=%s codec_id=%d codec_name=%s bits_per_raw=%d bits_per_coded=%d av_get_bits=%d -> detected=%d",
             path, codecpar->codec_id,
             avcodec_get_name(codecpar->codec_id),
             codecpar->bits_per_raw_sample,
             codecpar->bits_per_coded_sample,
             av_get_bits_per_sample(codecpar->codec_id),
             bits);
    } else {
        LOGE("probe_bits_per_sample: no audio stream found in %s", path);
    }
    avformat_close_input(&fmt_ctx);
    return bits;
}

static jint probe_channel_count(const char *path) {
    AVFormatContext *fmt_ctx = nullptr;
    if (avformat_open_input(&fmt_ctx, path, nullptr, nullptr) < 0) return 0;
    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        avformat_close_input(&fmt_ctx);
        return 0;
    }
    int channels = 0;
    int audio_stream_idx = find_audio_stream(fmt_ctx);
    if (audio_stream_idx >= 0) {
        AVCodecParameters *codecpar = fmt_ctx->streams[audio_stream_idx]->codecpar;
        channels = detect_channel_count(codecpar);
    }
    avformat_close_input(&fmt_ctx);
    return channels;
}

static int extract_cover(const char *input_path, const char *output_path) {
    AVFormatContext *fmt_ctx = nullptr;
    if (avformat_open_input(&fmt_ctx, input_path, nullptr, nullptr) < 0) return -1;
    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        avformat_close_input(&fmt_ctx);
        return -1;
    }

    for (unsigned int i = 0; i < fmt_ctx->nb_streams; i++) {
        if (fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
            AVPacket *pkt = av_packet_alloc();
            int ret = av_read_frame(fmt_ctx, pkt);
            if (ret >= 0 && pkt->stream_index == (int)i && pkt->size > 1024) {
                FILE *fp = fopen(output_path, "wb");
                if (fp) {
                    fwrite(pkt->data, 1, pkt->size, fp);
                    fclose(fp);
                    av_packet_unref(pkt);
                    av_packet_free(&pkt);
                    avformat_close_input(&fmt_ctx);
                    return 0;
                }
            }
            av_packet_unref(pkt);
            av_packet_free(&pkt);
            break;
        }
    }

    AVPacket *pkt = av_packet_alloc();
    while (av_read_frame(fmt_ctx, pkt) >= 0) {
        if (pkt->flags & AV_PKT_FLAG_KEY && pkt->size > 1024) {
            for (unsigned int i = 0; i < fmt_ctx->nb_streams; i++) {
                if (pkt->stream_index == (int)i &&
                    fmt_ctx->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                    FILE *fp = fopen(output_path, "wb");
                    if (fp) {
                        fwrite(pkt->data, 1, pkt->size, fp);
                        fclose(fp);
                    }
                    av_packet_unref(pkt);
                    av_packet_free(&pkt);
                    avformat_close_input(&fmt_ctx);
                    return fp ? 0 : -1;
                }
            }
        }
        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);
    avformat_close_input(&fmt_ctx);
    return -1;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeConvertToWav(
    JNIEnv *env, jobject, jstring input, jstring output, jint sample_rate,
    jint bits_per_sample, jint channels) {
    const char *inp = env->GetStringUTFChars(input, nullptr);
    const char *out = env->GetStringUTFChars(output, nullptr);
    int ret = convert_to_wav(inp, out, sample_rate, bits_per_sample, channels);
    env->ReleaseStringUTFChars(input, inp);
    env->ReleaseStringUTFChars(output, out);
    return ret;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeConvertToRawPcm(
    JNIEnv *env, jobject, jstring input, jstring output, jint sample_rate, jint bits_per_sample, jint channels) {
    const char *inp = env->GetStringUTFChars(input, nullptr);
    const char *out = env->GetStringUTFChars(output, nullptr);
    int ret = convert_to_raw_pcm(inp, out, sample_rate, bits_per_sample, channels);
    env->ReleaseStringUTFChars(input, inp);
    env->ReleaseStringUTFChars(output, out);
    return ret;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeProbeDuration(
    JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    jlong dur = probe_duration(p);
    env->ReleaseStringUTFChars(path, p);
    return dur;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeProbeSampleRate(
    JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    jint sr = probe_sample_rate(p);
    env->ReleaseStringUTFChars(path, p);
    return sr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeProbeBitsPerSample(
    JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    jint bits = probe_bits_per_sample(p);
    env->ReleaseStringUTFChars(path, p);
    return bits;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeProbeChannelCount(
    JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    jint ch = probe_channel_count(p);
    env->ReleaseStringUTFChars(path, p);
    return ch;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeExtractCover(
    JNIEnv *env, jobject, jstring input, jstring output) {
    const char *inp = env->GetStringUTFChars(input, nullptr);
    const char *out = env->GetStringUTFChars(output, nullptr);
    int ret = extract_cover(inp, out);
    env->ReleaseStringUTFChars(input, inp);
    env->ReleaseStringUTFChars(output, out);
    return ret;
}

// ==========================
// Streaming Decoder for zero-disk playback
// ==========================

struct StreamDecoder {
    AVFormatContext *fmt_ctx;
    AVCodecContext *codec_ctx;
    SwrContext *swr_ctx;
    int audio_stream_idx;

    // Output format
    int out_sample_rate;
    int out_channels;
    int out_bits;
    int out_bytes_per_sample;  // bytes per sample per channel in swr output (2 for s16, 4 for float/s32)
    int file_bytes_per_sample; // bytes per sample per channel in output (now always == out_bytes_per_sample)
    AVSampleFormat out_fmt;

    // Internal residual buffer: holds unconsumed swr_convert output between calls
    uint8_t *residual_buf;
    int residual_buf_capacity; // total bytes capacity
    int residual_buf_size;     // bytes currently stored
    int residual_buf_pos;      // read cursor

    // FFmpeg packet/frame for decoding
    AVPacket *pkt;
    AVFrame *frame;

    // Source info
    int64_t duration_us;
    int src_sample_rate;
    int src_channels;

    // State
    bool eof_reached;
    bool flushed_decoder;
    bool flushed_swr;
};

static StreamDecoder* stream_decoder_open(
    const char *path,
    int target_sample_rate,
    int bits_per_sample,
    int channels
) {
    StreamDecoder *sd = (StreamDecoder *)calloc(1, sizeof(StreamDecoder));
    if (!sd) return nullptr;

    sd->fmt_ctx = nullptr;
    sd->codec_ctx = nullptr;
    sd->swr_ctx = nullptr;
    sd->pkt = nullptr;
    sd->frame = nullptr;
    sd->residual_buf = nullptr;
    sd->audio_stream_idx = -1;

    if (avformat_open_input(&sd->fmt_ctx, path, nullptr, nullptr) < 0) {
        LOGE("stream_decoder_open: Could not open input: %s", path);
        free(sd);
        return nullptr;
    }

    if (avformat_find_stream_info(sd->fmt_ctx, nullptr) < 0) {
        LOGE("stream_decoder_open: Could not find stream info");
        goto fail;
    }

    sd->audio_stream_idx = find_audio_stream(sd->fmt_ctx);
    if (sd->audio_stream_idx < 0) {
        LOGE("stream_decoder_open: No audio stream found");
        goto fail;
    }

    {
        AVCodecParameters *codecpar = sd->fmt_ctx->streams[sd->audio_stream_idx]->codecpar;
        const AVCodec *codec = avcodec_find_decoder(codecpar->codec_id);
        if (!codec) {
            LOGE("stream_decoder_open: Unsupported codec");
            goto fail;
        }

        sd->codec_ctx = avcodec_alloc_context3(codec);
        if (!sd->codec_ctx) {
            LOGE("stream_decoder_open: Could not allocate codec context");
            goto fail;
        }

        if (avcodec_parameters_to_context(sd->codec_ctx, codecpar) < 0) {
            LOGE("stream_decoder_open: Could not copy codec params");
            goto fail;
        }

        if (avcodec_open2(sd->codec_ctx, codec, nullptr) < 0) {
            LOGE("stream_decoder_open: Could not open codec");
            goto fail;
        }

        sd->src_sample_rate = sd->codec_ctx->sample_rate;
        sd->src_channels = sd->codec_ctx->channels;
        sd->duration_us = sd->fmt_ctx->duration;

        // Output format
        sd->out_sample_rate = target_sample_rate > 0 ? target_sample_rate : sd->src_sample_rate;
        if (sd->out_sample_rate <= 0) sd->out_sample_rate = 44100;
        sd->out_channels = channels > 0 ? channels : 2;
        sd->out_bits = normalize_bits_per_sample(bits_per_sample);
        sd->out_bytes_per_sample = (sd->out_bits == 16) ? 2 : 4; // swr output: s16->2B, float/s32->4B
        sd->file_bytes_per_sample = bytes_per_sample_for_bits(sd->out_bits);
        sd->out_fmt = swr_output_format_for_bits(sd->out_bits);

        // Channel layout
        int64_t in_ch_layout = sd->codec_ctx->channel_layout;
        if (in_ch_layout == 0) in_ch_layout = av_get_default_channel_layout(sd->codec_ctx->channels);
        if (in_ch_layout == 0) in_ch_layout = AV_CH_LAYOUT_STEREO;
        int64_t out_ch_layout = av_get_default_channel_layout(sd->out_channels);

        sd->swr_ctx = swr_alloc_set_opts(nullptr,
            out_ch_layout, sd->out_fmt, sd->out_sample_rate,
            in_ch_layout, sd->codec_ctx->sample_fmt, sd->src_sample_rate,
            0, nullptr);
        if (!sd->swr_ctx) {
            LOGE("stream_decoder_open: Could not allocate SwrContext");
            goto fail;
        }

        av_opt_set_int(sd->swr_ctx, "filter_size", 32, 0);
        av_opt_set_int(sd->swr_ctx, "phase_shift", 10, 0);

        if (swr_init(sd->swr_ctx) < 0) {
            LOGE("stream_decoder_open: Could not init SwrContext");
            goto fail;
        }

        // Allocate residual buffer: 1 second of audio worth
        sd->residual_buf_capacity = sd->out_sample_rate * sd->out_channels * sd->out_bytes_per_sample;
        sd->residual_buf = (uint8_t *)av_malloc(sd->residual_buf_capacity);
        if (!sd->residual_buf) {
            LOGE("stream_decoder_open: Could not allocate residual buffer");
            goto fail;
        }
        sd->residual_buf_size = 0;
        sd->residual_buf_pos = 0;

        sd->pkt = av_packet_alloc();
        sd->frame = av_frame_alloc();
        if (!sd->pkt || !sd->frame) {
            LOGE("stream_decoder_open: Could not allocate packet/frame");
            goto fail;
        }

        sd->eof_reached = false;
        sd->flushed_decoder = false;
        sd->flushed_swr = false;

        LOGI("stream_decoder_open: OK, %s -> %dHz %dch %dbit, duration=%lldus",
             path, sd->out_sample_rate, sd->out_channels, sd->out_bits, (long long)sd->duration_us);
        return sd;
    }

fail:
    if (sd->pkt) av_packet_free(&sd->pkt);
    if (sd->frame) av_frame_free(&sd->frame);
    if (sd->residual_buf) av_free(sd->residual_buf);
    if (sd->swr_ctx) swr_free(&sd->swr_ctx);
    if (sd->codec_ctx) avcodec_free_context(&sd->codec_ctx);
    if (sd->fmt_ctx) avformat_close_input(&sd->fmt_ctx);
    free(sd);
    return nullptr;
}

/**
 * Decode next chunk of PCM data into provided buffer.
 * Returns: bytes written to out_buf, or -1 for EOF, -2 for error.
 */
static int stream_decoder_read(StreamDecoder *sd, uint8_t *out_buf, int out_max_bytes) {
    if (!sd || !out_buf || out_max_bytes <= 0) return -2;
    if (sd->eof_reached && sd->residual_buf_pos >= sd->residual_buf_size) return -1;

    int bytes_written = 0;

    // Step 1: Copy residual data first
    int residual_available = sd->residual_buf_size - sd->residual_buf_pos;
    if (residual_available > 0) {
        int to_copy = residual_available;
        if (to_copy > out_max_bytes) to_copy = out_max_bytes;
        memcpy(out_buf, sd->residual_buf + sd->residual_buf_pos, to_copy);
        sd->residual_buf_pos += to_copy;
        bytes_written += to_copy;

        // Compact residual buffer
        if (sd->residual_buf_pos >= sd->residual_buf_size) {
            sd->residual_buf_size = 0;
            sd->residual_buf_pos = 0;
        }
    }

    // Step 2: If output still has space, decode more frames
    while (bytes_written < out_max_bytes && !sd->eof_reached) {
        // Try to receive more decoded frames
        int ret = avcodec_receive_frame(sd->codec_ctx, sd->frame);
        if (ret == AVERROR(EAGAIN)) {
            // Need more packets
            if (!sd->flushed_decoder) {
                ret = av_read_frame(sd->fmt_ctx, sd->pkt);
                if (ret < 0) {
                    if (ret == AVERROR_EOF) {
                        // Flush decoder
                        avcodec_send_packet(sd->codec_ctx, nullptr);
                        sd->flushed_decoder = true;
                        continue;
                    }
                    LOGE("stream_decoder_read: av_read_frame failed: %d", ret);
                    sd->eof_reached = true;
                    break;
                }
                if (sd->pkt->stream_index != sd->audio_stream_idx) {
                    av_packet_unref(sd->pkt);
                    continue;
                }
                ret = avcodec_send_packet(sd->codec_ctx, sd->pkt);
                av_packet_unref(sd->pkt);
                if (ret < 0) {
                    LOGE("stream_decoder_read: avcodec_send_packet failed: %d", ret);
                    sd->eof_reached = true;
                    break;
                }
                continue;
            } else {
                // Already flushed decoder, no more frames
                sd->eof_reached = true;
                break;
            }
        } else if (ret == AVERROR_EOF) {
            sd->eof_reached = true;
            break;
        } else if (ret < 0) {
            LOGE("stream_decoder_read: avcodec_receive_frame failed: %d", ret);
            sd->eof_reached = true;
            break;
        }

        // Got a decoded frame, resample it
        int out_samples = swr_convert(sd->swr_ctx, &sd->residual_buf, sd->residual_buf_capacity / sd->out_bytes_per_sample / sd->out_channels,
            (const uint8_t **)sd->frame->extended_data, sd->frame->nb_samples);
        av_frame_unref(sd->frame);

        if (out_samples > 0) {
            // 24bit/32bit 都直接用 S32LE (4B/sample)，无需格式转换
            int resampled_bytes = out_samples * sd->out_channels * sd->out_bytes_per_sample;
            // 安全检查：防止 swr_convert 返回超出预期的采样数
            if (resampled_bytes > sd->residual_buf_capacity) {
                LOGE("stream_decoder_read: overflow! resampled=%d > capacity=%d, clamping",
                     resampled_bytes, sd->residual_buf_capacity);
                resampled_bytes = sd->residual_buf_capacity;
            }

            sd->residual_buf_size = resampled_bytes;
            sd->residual_buf_pos = 0;

            // Copy to output
            int available = sd->residual_buf_size - sd->residual_buf_pos;
            int remaining = out_max_bytes - bytes_written;
            int to_copy = (available < remaining) ? available : remaining;
            memcpy(out_buf + bytes_written, sd->residual_buf + sd->residual_buf_pos, to_copy);
            sd->residual_buf_pos += to_copy;
            bytes_written += to_copy;

            if (bytes_written >= out_max_bytes) break;
        }
    }

    // If decoder EOF but swr has residual
    if (sd->eof_reached && !sd->flushed_swr && bytes_written < out_max_bytes) {
        int out_samples = swr_convert(sd->swr_ctx, &sd->residual_buf, sd->residual_buf_capacity / sd->out_bytes_per_sample / sd->out_channels, nullptr, 0);
        if (out_samples > 0) {
            // 24bit/32bit 都直接用 S32LE (4B/sample)，无需格式转换
            int resampled_bytes = out_samples * sd->out_channels * sd->out_bytes_per_sample;
            // 安全检查：防止 swr_convert flush 时返回超出预期的采样数
            if (resampled_bytes > sd->residual_buf_capacity) {
                LOGE("stream_decoder_read: swr flush overflow! resampled=%d > capacity=%d, clamping",
                     resampled_bytes, sd->residual_buf_capacity);
                resampled_bytes = sd->residual_buf_capacity;
            }
            sd->residual_buf_size = resampled_bytes;
            sd->residual_buf_pos = 0;
            int available = sd->residual_buf_size;
            int remaining = out_max_bytes - bytes_written;
            int to_copy = (available < remaining) ? available : remaining;
            memcpy(out_buf + bytes_written, sd->residual_buf, to_copy);
            sd->residual_buf_pos += to_copy;
            bytes_written += to_copy;
        }
        sd->flushed_swr = true;
    }

    return bytes_written > 0 ? bytes_written : -1;
}

/**
 * Seek decoder to position in microseconds.
 * Returns true on success.
 */
static bool stream_decoder_seek(StreamDecoder *sd, int64_t position_us) {
    if (!sd || !sd->fmt_ctx) return false;

    // Clear residual buffer
    sd->residual_buf_size = 0;
    sd->residual_buf_pos = 0;

    // Reset decoder state
    sd->eof_reached = false;
    sd->flushed_decoder = false;
    sd->flushed_swr = false;
    avcodec_flush_buffers(sd->codec_ctx);

    int ret = avformat_seek_file(sd->fmt_ctx, -1, INT64_MIN, position_us, INT64_MAX, 0);
    if (ret < 0) {
        LOGE("stream_decoder_seek: avformat_seek_file failed: %d", ret);
        return false;
    }

    LOGI("stream_decoder_seek: seeked to %lld us", (long long)position_us);
    return true;
}

static void stream_decoder_close(StreamDecoder *sd) {
    if (!sd) return;
    if (sd->pkt) av_packet_free(&sd->pkt);
    if (sd->frame) av_frame_free(&sd->frame);
    if (sd->residual_buf) av_free(sd->residual_buf);
    if (sd->swr_ctx) swr_free(&sd->swr_ctx);
    if (sd->codec_ctx) avcodec_free_context(&sd->codec_ctx);
    if (sd->fmt_ctx) avformat_close_input(&sd->fmt_ctx);
    free(sd);
}

// JNI bindings for streaming decoder

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeOpenDecoder(
    JNIEnv *env, jobject, jstring path, jint targetRate, jint targetBits, jint channels) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    StreamDecoder *sd = stream_decoder_open(p, targetRate, targetBits, channels);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(sd);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeDecodeChunk(
    JNIEnv *env, jobject, jlong handle, jbyteArray buffer, jint offset, jint maxBytes) {
    StreamDecoder *sd = reinterpret_cast<StreamDecoder *>(handle);
    if (!sd) return -2;

    // Get direct pointer to Java byte array
    jbyte *buf = env->GetByteArrayElements(buffer, nullptr);
    if (!buf) return -2;

    int ret = stream_decoder_read(sd, (uint8_t *)(buf + offset), maxBytes);

    // Release without copying back (JNI_ABORT) since we wrote to it
    env->ReleaseByteArrayElements(buffer, buf, 0);
    return ret;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeSeekDecoder(
    JNIEnv *env, jobject, jlong handle, jlong positionMs) {
    StreamDecoder *sd = reinterpret_cast<StreamDecoder *>(handle);
    if (!sd) return JNI_FALSE;
    int64_t position_us = positionMs * 1000;
    return stream_decoder_seek(sd, position_us) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeGetDecoderSampleRate(
    JNIEnv *, jobject, jlong handle) {
    StreamDecoder *sd = reinterpret_cast<StreamDecoder *>(handle);
    if (!sd) return 0;
    return sd->out_sample_rate;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeGetDecoderChannels(
    JNIEnv *, jobject, jlong handle) {
    StreamDecoder *sd = reinterpret_cast<StreamDecoder *>(handle);
    if (!sd) return 0;
    return sd->out_channels;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeGetDecoderBitsPerSample(
    JNIEnv *, jobject, jlong handle) {
    StreamDecoder *sd = reinterpret_cast<StreamDecoder *>(handle);
    if (!sd) return 0;
    return sd->out_bits;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeGetDecoderDuration(
    JNIEnv *, jobject, jlong handle) {
    StreamDecoder *sd = reinterpret_cast<StreamDecoder *>(handle);
    if (!sd) return 0;
    return sd->duration_us / 1000; // microseconds to milliseconds
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeCloseDecoder(
    JNIEnv *, jobject, jlong handle) {
    StreamDecoder *sd = reinterpret_cast<StreamDecoder *>(handle);
    if (sd) {
        stream_decoder_close(sd);
        LOGI("stream_decoder closed");
    }
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeGetMediaInfo(
    JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    AVFormatContext *fmt_ctx = nullptr;

    if (avformat_open_input(&fmt_ctx, p, nullptr, nullptr) < 0) {
        env->ReleaseStringUTFChars(path, p);
        return nullptr;
    }
    if (avformat_find_stream_info(fmt_ctx, nullptr) < 0) {
        avformat_close_input(&fmt_ctx);
        env->ReleaseStringUTFChars(path, p);
        return nullptr;
    }
    env->ReleaseStringUTFChars(path, p);

    jclass mapClass = env->FindClass("java/util/HashMap");
    jmethodID mapInit = env->GetMethodID(mapClass, "<init>", "()V");
    jmethodID mapPut = env->GetMethodID(mapClass, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    jobject map = env->NewObject(mapClass, mapInit);

    auto putStr = [&](const char *key, const char *value) {
        jstring jkey = env->NewStringUTF(key);
        jstring jval = env->NewStringUTF(value);
        env->CallObjectMethod(map, mapPut, jkey, jval);
        env->DeleteLocalRef(jkey);
        env->DeleteLocalRef(jval);
    };

    char buf[64];

    if (fmt_ctx->duration != AV_NOPTS_VALUE) {
        snprintf(buf, sizeof(buf), "%.6f", fmt_ctx->duration / (double)AV_TIME_BASE);
        putStr("duration", buf);
    }

    if (fmt_ctx->iformat) {
        putStr("format_name", fmt_ctx->iformat->name);
        if (fmt_ctx->iformat->long_name)
            putStr("format_long_name", fmt_ctx->iformat->long_name);
    }

    if (fmt_ctx->bit_rate > 0) {
        snprintf(buf, sizeof(buf), "%lld", (long long)fmt_ctx->bit_rate);
        putStr("bit_rate", buf);
    }

    if (fmt_ctx->metadata) {
        AVDictionaryEntry *tag = nullptr;
        while ((tag = av_dict_get(fmt_ctx->metadata, "", tag, AV_DICT_IGNORE_SUFFIX))) {
            if (tag->key && tag->value) {
                putStr(tag->key, tag->value);
            }
        }
    }

    for (unsigned int i = 0; i < fmt_ctx->nb_streams; i++) {
        AVStream *stream = fmt_ctx->streams[i];
        if (stream->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            char prefix[32];
            snprintf(prefix, sizeof(prefix), "stream_%u_", i);

            snprintf(buf, sizeof(buf), "%d", stream->codecpar->sample_rate);
            putStr((std::string(prefix) + "sample_rate").c_str(), buf);

            snprintf(buf, sizeof(buf), "%d", stream->codecpar->channels);
            putStr((std::string(prefix) + "channels").c_str(), buf);

            snprintf(buf, sizeof(buf), "%d", stream->codecpar->bits_per_raw_sample);
            putStr((std::string(prefix) + "bits_per_raw_sample").c_str(), buf);

            snprintf(buf, sizeof(buf), "%d", stream->codecpar->bits_per_coded_sample);
            putStr((std::string(prefix) + "bits_per_coded_sample").c_str(), buf);

            {
                int bps = stream->codecpar->bits_per_raw_sample;
                if (bps <= 0) bps = stream->codecpar->bits_per_coded_sample;
                if (bps <= 0) {
                    bps = av_get_bits_per_sample(stream->codecpar->codec_id);
                }
                snprintf(buf, sizeof(buf), "%d", bps);
                putStr((std::string(prefix) + "bits_per_sample").c_str(), buf);
            }

            const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
            if (codec) {
                putStr((std::string(prefix) + "codec_name").c_str(), codec->name);
                if (codec->long_name)
                    putStr((std::string(prefix) + "codec_long_name").c_str(), codec->long_name);
            }

            putStr((std::string(prefix) + "codec_type").c_str(), "audio");

            if (stream->codecpar->bit_rate > 0) {
                snprintf(buf, sizeof(buf), "%lld", (long long)stream->codecpar->bit_rate);
                putStr((std::string(prefix) + "bit_rate").c_str(), buf);
            }

            if (stream->duration > 0 && stream->time_base.den > 0) {
                snprintf(buf, sizeof(buf), "%.6f", stream->duration * av_q2d(stream->time_base));
                putStr((std::string(prefix) + "duration").c_str(), buf);
            }

            // 将 stream metadata 中的标签同时以顶级键形式写入
            // WAV/AIFF 等格式的标签可能只存在于 stream metadata 中
            if (stream->metadata) {
                AVDictionaryEntry *tag = nullptr;
                while ((tag = av_dict_get(stream->metadata, "", tag, AV_DICT_IGNORE_SUFFIX))) {
                    if (tag->key && tag->value) {
                        // 带前缀形式（用于流信息区分）
                        putStr((std::string(prefix) + "raw_tag_" + tag->key).c_str(), tag->value);
                        // 同时以顶级键形式写入（确保 WAV 等格式的标签能被解析）
                        // 仅在顶级 metadata 中不存在同名键、或顶级键值为空时写入
                        bool shouldWriteTopLevel = true;
                        if (fmt_ctx->metadata) {
                            AVDictionaryEntry *existing = av_dict_get(fmt_ctx->metadata, tag->key, nullptr, 0);
                            if (existing && existing->value && strlen(existing->value) > 0) {
                                shouldWriteTopLevel = false;
                            }
                        }
                        if (shouldWriteTopLevel) {
                            putStr(tag->key, tag->value);
                        }
                    }
                }
            }

            break;
        }
    }

    avformat_close_input(&fmt_ctx);
    return map;
}
