#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <string>
#include <vector>
#include <algorithm>
#include <cmath>
#include <signal.h>
#include <setjmp.h>
#include <time.h>

extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/avutil.h>
#include <libavutil/dict.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
}

// ==========================
// SIGABRT protection for FFmpeg 6.0+ assertion failures
// ==========================
#define LOG_TAG "FFmpegBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static thread_local sigjmp_buf s_abort_jmp_buf;
static thread_local volatile sig_atomic_t s_abort_caught = 0;

static jstring newJStringFromUtf8Lenient(JNIEnv *env, const char *text) {
    if (!text) {
        return env->NewString(nullptr, 0);
    }
    const unsigned char *p = reinterpret_cast<const unsigned char *>(text);
    std::vector<jchar> out;

    auto appendReplacement = [&]() {
        out.push_back(static_cast<jchar>(0xfffd));
    };
    auto appendCodePoint = [&](uint32_t cp) {
        if (cp <= 0xffff) {
            if (cp >= 0xd800 && cp <= 0xdfff) {
                appendReplacement();
            } else {
                out.push_back(static_cast<jchar>(cp));
            }
        } else if (cp <= 0x10ffff) {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xd800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xdc00 + (cp & 0x3ff)));
        } else {
            appendReplacement();
        }
    };

    while (*p) {
        uint32_t cp = 0;
        unsigned char c = *p;
        if (c < 0x80) {
            cp = c;
            p += 1;
        } else if ((c & 0xe0) == 0xc0) {
            if ((p[1] & 0xc0) == 0x80 && c >= 0xc2) {
                cp = ((c & 0x1f) << 6) | (p[1] & 0x3f);
                p += 2;
            } else {
                appendReplacement();
                p += 1;
                continue;
            }
        } else if ((c & 0xf0) == 0xe0) {
            if ((p[1] & 0xc0) == 0x80 && (p[2] & 0xc0) == 0x80 &&
                !(c == 0xe0 && p[1] < 0xa0) &&
                !(c == 0xed && p[1] >= 0xa0)) {
                cp = ((c & 0x0f) << 12) | ((p[1] & 0x3f) << 6) | (p[2] & 0x3f);
                p += 3;
            } else {
                appendReplacement();
                p += 1;
                continue;
            }
        } else if ((c & 0xf8) == 0xf0) {
            if ((p[1] & 0xc0) == 0x80 && (p[2] & 0xc0) == 0x80 && (p[3] & 0xc0) == 0x80 &&
                !(c == 0xf0 && p[1] < 0x90) &&
                !(c == 0xf4 && p[1] >= 0x90) && c <= 0xf4) {
                cp = ((c & 0x07) << 18) | ((p[1] & 0x3f) << 12) | ((p[2] & 0x3f) << 6) | (p[3] & 0x3f);
                p += 4;
            } else {
                appendReplacement();
                p += 1;
                continue;
            }
        } else {
            appendReplacement();
            p += 1;
            continue;
        }
        appendCodePoint(cp);
    }
    return env->NewString(out.data(), static_cast<jsize>(out.size()));
}

static void abort_signal_handler(int sig) {
    s_abort_caught = 1;
    siglongjmp(s_abort_jmp_buf, 1);
}

/**
 * Safe wrapper for avcodec_send_packet that catches SIGABRT from FFmpeg 6.0+ assertion failures.
 * Returns the normal avcodec_send_packet result, or -100 if SIGABRT was caught.
 */
static int sendPacketSafe(AVCodecContext *ctx, AVPacket *pkt) {
    struct sigaction sa_old, sa_new;
    sa_new.sa_handler = abort_signal_handler;
    sigemptyset(&sa_new.sa_mask);
    sa_new.sa_flags = 0;
    s_abort_caught = 0;

    sigaction(SIGABRT, &sa_new, &sa_old);
    if (sigsetjmp(s_abort_jmp_buf, 1) == 0) {
        int ret = avcodec_send_packet(ctx, pkt);
        sigaction(SIGABRT, &sa_old, nullptr);
        return ret;
    } else {
        // SIGABRT was caught — restore old handler
        sigaction(SIGABRT, &sa_old, nullptr);
        LOGE("sendPacketSafe: caught SIGABRT from avcodec_send_packet, returning error");
        return -100;
    }
}

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
// 对于有损压缩格式，不再假装有源位深，返回 0 让上层按有损显示
static int lossy_codec_default_bits(enum AVCodecID codec_id) {
    // MP3 / AAC / Vorbis / Opus / WMA 等有损格式没有 PCM bit depth 概念
    return 0;
}

// 从 ALAC extradata 解析真实 bitDepth
static int detect_alac_bit_depth_from_extradata(const uint8_t *extradata, int extradata_size) {
    // ALAC magic cookie structure (24+ bytes):
    // offset 0: 'frma' atom (4 bytes) + 'alac' (4 bytes)
    // offset 8: size (4 bytes, big-endian)
    // offset 12: 'alac' (4 bytes)
    // offset 16: version (1 byte)
    // offset 17: flags (1 byte)
    // offset 20-21: frameLength (4 bytes)
    // offset 21-24: compatibleVersion(1), sampleRate(4), ...
    // Actually ALAC specific box starts after 'alac' marker:
    // Standard layout after 'alac' marker at offset 12:
    //   version(1) flags(1) ?(1) ?(1) frameLength(4)
    //   compatibleVersion(1) bitDepth(1) ...
    // The bitDepth is at offset 21 from start of extradata (after frma+alac header)
    if (!extradata || extradata_size < 24) return 0;
    // Verify 'frma' + 'alac' signature
    if (extradata[0] != 'f' || extradata[1] != 'r' ||
        extradata[2] != 'm' || extradata[3] != 'a') return 0;
    // Find 'alac' marker
    int alac_offset = -1;
    for (int i = 4; i + 4 <= extradata_size; i++) {
        if (extradata[i] == 'a' && extradata[i + 1] == 'l' &&
            extradata[i + 2] == 'a' && extradata[i + 3] == 'c') {
            alac_offset = i + 4; // skip 'alac' marker
            break;
        }
    }
    if (alac_offset < 0) return 0;
    // ALAC specific config:
    // offset 0: version (1 byte)
    // offset 1: flags (1 byte)
    // offset 2-3: ?(2 bytes)
    // offset 4-7: frameLength (4 bytes)
    // offset 8: compatibleVersion (1 byte)
    // offset 9: bitDepth (1 byte)
    int bitdepth_offset = alac_offset + 9;
    if (bitdepth_offset >= extradata_size) return 0;
    int bit_depth = extradata[bitdepth_offset];
    if (bit_depth == 16 || bit_depth == 20 || bit_depth == 24 || bit_depth == 32) {
        return bit_depth;
    }
    return 0;
}

static bool is_dsd_codec(enum AVCodecID codec_id) {
    switch (codec_id) {
        case AV_CODEC_ID_DSD_LSBF:
        case AV_CODEC_ID_DSD_MSBF:
        case AV_CODEC_ID_DSD_LSBF_PLANAR:
        case AV_CODEC_ID_DSD_MSBF_PLANAR:
            return true;
        default:
            return false;
    }
}

static inline uint8_t reverse_bits_u8(uint8_t v) {
    v = (uint8_t)(((v & 0xF0u) >> 4) | ((v & 0x0Fu) << 4));
    v = (uint8_t)(((v & 0xCCu) >> 2) | ((v & 0x33u) << 2));
    v = (uint8_t)(((v & 0xAAu) >> 1) | ((v & 0x55u) << 1));
    return v;
}

static int detect_bits_per_sample(const AVCodecParameters *codecpar) {
    if (!codecpar) return 0;
    if (is_dsd_codec(codecpar->codec_id)) {
        return 1;
    }
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
    // ALAC: av_get_bits_per_sample returns 0; try to parse from extradata
    if (codecpar->codec_id == AV_CODEC_ID_ALAC) {
        int alac_bits = detect_alac_bit_depth_from_extradata(
            codecpar->extradata, codecpar->extradata_size);
        if (alac_bits > 0) return alac_bits;
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
                ret = sendPacketSafe(codec_ctx, pkt);
                if (ret == -100) { LOGE("convert_to_wav: SIGABRT caught, aborting"); goto cleanup; }
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
            ret = sendPacketSafe(codec_ctx, nullptr);
            if (ret == -100) { LOGE("convert_to_wav: SIGABRT during flush, aborting"); goto cleanup; }
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
                ret = sendPacketSafe(codec_ctx, pkt);
                if (ret == -100) { LOGE("convert_to_raw_pcm: SIGABRT caught, aborting"); goto cleanup_pcm; }
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
        ret = sendPacketSafe(codec_ctx, nullptr);
        if (ret == -100) { LOGE("convert_to_raw_pcm: SIGABRT during flush, aborting"); goto cleanup_pcm; }
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
    // AV_NOPTS_VALUE is INT64_MIN, dividing by 1000 gives huge negative
    if (dur == AV_NOPTS_VALUE || dur < 0) return 0;
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
        AVStream *stream = fmt_ctx->streams[i];
        if ((stream->disposition & AV_DISPOSITION_ATTACHED_PIC) &&
            stream->attached_pic.data != nullptr &&
            stream->attached_pic.size > 1024) {
            FILE *fp = fopen(output_path, "wb");
            if (fp) {
                fwrite(stream->attached_pic.data, 1, stream->attached_pic.size, fp);
                fclose(fp);
                avformat_close_input(&fmt_ctx);
                return 0;
            }
            avformat_close_input(&fmt_ctx);
            return -1;
        }
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

    // 限制扫描帧数，避免对大文件（如1小时以上音频）扫描整个文件导致主线程长时间阻塞
    AVPacket *pkt = av_packet_alloc();
    int frames_scanned = 0;
    const int MAX_FRAMES_TO_SCAN = 64;
    while (av_read_frame(fmt_ctx, pkt) >= 0 && frames_scanned < MAX_FRAMES_TO_SCAN) {
        frames_scanned++;
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


// Poweramp-like offline Waveseek scanner lives in raw_waveform_scan.cpp.
// Keep the JNI bridge small; do not pile scan/decode policy into this file.
std::vector<float> rawsmusic_scan_waveform_poweramp_seek(
    const char *input_path,
    int64_t start_ms,
    int64_t end_ms,
    int sample_count
);

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

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeScanWaveform(
    JNIEnv *env, jobject, jstring path, jlong startMs, jlong endMs, jint sampleCount) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    std::vector<float> result = rawsmusic_scan_waveform_poweramp_seek(p, (int64_t)startMs, (int64_t)endMs, (int)sampleCount);
    env->ReleaseStringUTFChars(path, p);
    jfloatArray array = env->NewFloatArray((jsize)result.size());
    if (!array) return nullptr;
    if (!result.empty()) {
        env->SetFloatArrayRegion(array, 0, (jsize)result.size(), result.data());
    }
    return array;
}

// ==========================
// Streaming Decoder for zero-disk playback
// ==========================

// Patch: allow WAV PCM float64/float32 to play even when libavcodec has no PCM decoder.
static bool streamRawPcmPassthroughFormat(
        AVCodecID codecId,
        AVSampleFormat* outFmt,
        int* outBytesPerSample,
        const char** outName
) {
    if (!outFmt || !outBytesPerSample || !outName) return false;

    // Android/ARM64 is little-endian.  These WAV PCM codecs are byte-for-byte
    // packet payloads from the demuxer, so we can feed them directly into swr
    // without avcodec when the trimmed FFmpeg build omitted the PCM decoder.
    switch (codecId) {
        case AV_CODEC_ID_PCM_F64LE:
            *outFmt = AV_SAMPLE_FMT_DBL;
            *outBytesPerSample = 8;
            *outName = "pcm_f64le(raw)";
            return true;
        case AV_CODEC_ID_PCM_F32LE:
            *outFmt = AV_SAMPLE_FMT_FLT;
            *outBytesPerSample = 4;
            *outName = "pcm_f32le(raw)";
            return true;
        case AV_CODEC_ID_PCM_S32LE:
            *outFmt = AV_SAMPLE_FMT_S32;
            *outBytesPerSample = 4;
            *outName = "pcm_s32le(raw)";
            return true;
        case AV_CODEC_ID_PCM_S16LE:
            *outFmt = AV_SAMPLE_FMT_S16;
            *outBytesPerSample = 2;
            *outName = "pcm_s16le(raw)";
            return true;
        default:
            return false;
    }
}

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

    // Raw PCM fallback for codecs that may be omitted from trimmed libavcodec builds.
    // Example: WAV pcm_f64le. avformat can demux packets, then swr converts raw doubles
    // directly to S32LE/S16LE output requested by Kotlin/USB.
    bool raw_pcm_passthrough;
    AVSampleFormat raw_pcm_fmt;
    int raw_pcm_bytes_per_sample;
    int raw_pcm_frame_size;
    const char* raw_pcm_name;

    // Raw DSD passthrough mode. The decoder returns normalized, interleaved,
    // MSB-first DSD bytes instead of PCM.
    bool raw_dsd_passthrough;
    bool raw_dsd_lsbf;
    bool raw_dsd_planar;
    int raw_dsd_bytes_per_channel_frame;

    // State
    bool eof_reached;
    bool flushed_decoder;
    bool flushed_swr;
};

static int streamCopyResidual(StreamDecoder* sd, uint8_t* out_buf, int out_max_bytes, int* bytes_written) {
    if (!sd || !out_buf || !bytes_written) return 0;
    int residual_available = sd->residual_buf_size - sd->residual_buf_pos;
    if (residual_available <= 0) return 0;

    int to_copy = residual_available;
    const int remaining = out_max_bytes - *bytes_written;
    if (to_copy > remaining) to_copy = remaining;
    if (to_copy <= 0) return 0;

    memcpy(out_buf + *bytes_written, sd->residual_buf + sd->residual_buf_pos, to_copy);
    sd->residual_buf_pos += to_copy;
    *bytes_written += to_copy;

    if (sd->residual_buf_pos >= sd->residual_buf_size) {
        sd->residual_buf_size = 0;
        sd->residual_buf_pos = 0;
    }
    return to_copy;
}

static bool streamEnsureResidualCapacity(StreamDecoder* sd, int required_bytes) {
    if (!sd || required_bytes <= 0) return false;
    if (sd->residual_buf_capacity >= required_bytes && sd->residual_buf) return true;

    uint8_t* new_buf = (uint8_t*)av_realloc(sd->residual_buf, required_bytes);
    if (!new_buf) {
        LOGE("streamEnsureResidualCapacity: realloc failed required=%d", required_bytes);
        return false;
    }
    sd->residual_buf = new_buf;
    sd->residual_buf_capacity = required_bytes;
    return true;
}

static int normalizeRawDsdPacket(StreamDecoder* sd, const AVPacket* pkt) {
    if (!sd || !pkt || !pkt->data || pkt->size <= 0 || sd->src_channels <= 0) return 0;
    if (!streamEnsureResidualCapacity(sd, pkt->size)) return -1;

    const bool reverseBits = sd->raw_dsd_lsbf;
    const int channels = sd->src_channels;
    uint8_t* dst = sd->residual_buf;

    if (sd->raw_dsd_planar) {
        if (pkt->size % channels != 0) {
            LOGI("normalizeRawDsdPacket: planar packet size %d not divisible by channels=%d",
                 pkt->size, channels);
        }
        const int bytesPerChannel = pkt->size / channels;
        int out = 0;
        for (int i = 0; i < bytesPerChannel; ++i) {
            for (int ch = 0; ch < channels; ++ch) {
                const uint8_t v = pkt->data[ch * bytesPerChannel + i];
                dst[out++] = reverseBits ? reverse_bits_u8(v) : v;
            }
        }
        return bytesPerChannel * channels;
    }

    for (int i = 0; i < pkt->size; ++i) {
        const uint8_t v = pkt->data[i];
        dst[i] = reverseBits ? reverse_bits_u8(v) : v;
    }
    return pkt->size;
}

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
    sd->raw_pcm_passthrough = false;
    sd->raw_pcm_fmt = AV_SAMPLE_FMT_NONE;
    sd->raw_pcm_bytes_per_sample = 0;
    sd->raw_pcm_frame_size = 0;
    sd->raw_pcm_name = "";
    sd->raw_dsd_passthrough = false;
    sd->raw_dsd_lsbf = false;
    sd->raw_dsd_planar = false;
    sd->raw_dsd_bytes_per_channel_frame = 0;

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
        const bool requestRawDsd = is_dsd_codec(codecpar->codec_id) && bits_per_sample <= 1;
        if (requestRawDsd) {
            sd->raw_dsd_passthrough = true;
            sd->raw_dsd_lsbf =
                codecpar->codec_id == AV_CODEC_ID_DSD_LSBF ||
                codecpar->codec_id == AV_CODEC_ID_DSD_LSBF_PLANAR;
            sd->raw_dsd_planar =
                codecpar->codec_id == AV_CODEC_ID_DSD_LSBF_PLANAR ||
                codecpar->codec_id == AV_CODEC_ID_DSD_MSBF_PLANAR;
            const int codecRate = codecpar->sample_rate > 0 ? codecpar->sample_rate : 2822400;
            const int rawByteRatePerChannel = codecRate >= 2822400 ? (codecRate / 8) : codecRate;
            sd->src_sample_rate = codecRate >= 2822400 ? codecRate : (codecRate * 8);
            sd->src_channels = codecpar->channels > 0 ? codecpar->channels : (channels > 0 ? channels : 2);
            sd->raw_dsd_bytes_per_channel_frame = 1;
            sd->duration_us = (sd->fmt_ctx->duration == AV_NOPTS_VALUE || sd->fmt_ctx->duration < 0)
                ? 0 : sd->fmt_ctx->duration;

            sd->out_sample_rate = rawByteRatePerChannel;
            if (sd->out_sample_rate <= 0) {
                sd->out_sample_rate = target_sample_rate > 0 ? target_sample_rate : 352800;
            }
            sd->out_channels = sd->src_channels;
            sd->out_bits = 1;
            sd->out_bytes_per_sample = 1;
            sd->file_bytes_per_sample = 1;
            sd->out_fmt = AV_SAMPLE_FMT_NONE;

            sd->residual_buf_capacity = sd->out_sample_rate * sd->out_channels;
            if (sd->residual_buf_capacity < 32768) sd->residual_buf_capacity = 32768;
            sd->residual_buf = (uint8_t*)av_malloc(sd->residual_buf_capacity);
            if (!sd->residual_buf) {
                LOGE("stream_decoder_open: Could not allocate raw DSD residual buffer");
                goto fail;
            }
            sd->residual_buf_size = 0;
            sd->residual_buf_pos = 0;

            sd->pkt = av_packet_alloc();
            if (!sd->pkt) {
                LOGE("stream_decoder_open: Could not allocate raw DSD packet");
                goto fail;
            }

            sd->eof_reached = false;
            sd->flushed_decoder = false;
            sd->flushed_swr = false;

            LOGI("stream_decoder_open: raw DSD passthrough %s sr=%d rawByteRate=%d ch=%d codec=%s",
                 path,
                 sd->src_sample_rate,
                 sd->out_sample_rate,
                 sd->out_channels,
                 avcodec_get_name(codecpar->codec_id));
            return sd;
        }

        const AVCodec *codec = avcodec_find_decoder(codecpar->codec_id);
        if (!codec) {
            AVSampleFormat rawFmt = AV_SAMPLE_FMT_NONE;
            int rawBps = 0;
            const char* rawName = "";
            if (!streamRawPcmPassthroughFormat(codecpar->codec_id, &rawFmt, &rawBps, &rawName)) {
                LOGE("stream_decoder_open: Unsupported codec id=%d name=%s",
                     codecpar->codec_id, avcodec_get_name(codecpar->codec_id));
                goto fail;
            }

            sd->raw_pcm_passthrough = true;
            sd->raw_pcm_fmt = rawFmt;
            sd->raw_pcm_bytes_per_sample = rawBps;
            sd->src_sample_rate = codecpar->sample_rate > 0 ? codecpar->sample_rate : target_sample_rate;
            if (sd->src_sample_rate <= 0) sd->src_sample_rate = 44100;
            sd->src_channels = codecpar->channels > 0 ? codecpar->channels : 2;
            sd->raw_pcm_frame_size = sd->src_channels * sd->raw_pcm_bytes_per_sample;
            sd->raw_pcm_name = rawName;
            LOGI("stream_decoder_open: using raw PCM fallback for codec id=%d name=%s sr=%d ch=%d bps=%d",
                 codecpar->codec_id, rawName, sd->src_sample_rate, sd->src_channels,
                 sd->raw_pcm_bytes_per_sample);
        } else {
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
        }
        sd->duration_us = (sd->fmt_ctx->duration == AV_NOPTS_VALUE || sd->fmt_ctx->duration < 0) 
                          ? 0 : sd->fmt_ctx->duration;

        // Output format
        sd->out_sample_rate = target_sample_rate > 0 ? target_sample_rate : sd->src_sample_rate;
        if (sd->out_sample_rate <= 0) sd->out_sample_rate = 44100;
        sd->out_channels = channels > 0 ? channels : 2;
        sd->out_bits = normalize_bits_per_sample(bits_per_sample);
        sd->out_bytes_per_sample = (sd->out_bits == 16) ? 2 : 4; // swr output: s16->2B, float/s32->4B
        sd->file_bytes_per_sample = bytes_per_sample_for_bits(sd->out_bits);
        sd->out_fmt = swr_output_format_for_bits(sd->out_bits);

        // Channel layout
        int64_t in_ch_layout = sd->raw_pcm_passthrough ? codecpar->channel_layout : sd->codec_ctx->channel_layout;
        if (in_ch_layout == 0) in_ch_layout = av_get_default_channel_layout(sd->src_channels);
        if (in_ch_layout == 0) in_ch_layout = AV_CH_LAYOUT_STEREO;
        int64_t out_ch_layout = av_get_default_channel_layout(sd->out_channels);

        AVSampleFormat in_fmt = sd->raw_pcm_passthrough ? sd->raw_pcm_fmt : sd->codec_ctx->sample_fmt;

        sd->swr_ctx = swr_alloc_set_opts(nullptr,
            out_ch_layout, sd->out_fmt, sd->out_sample_rate,
            in_ch_layout, in_fmt, sd->src_sample_rate,
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
        if (!sd->pkt || (!sd->raw_pcm_passthrough && !sd->frame)) {
            LOGE("stream_decoder_open: Could not allocate packet/frame");
            goto fail;
        }

        sd->eof_reached = false;
        sd->flushed_decoder = false;
        sd->flushed_swr = false;

        LOGI("stream_decoder_open: OK, %s -> %dHz %dch %dbit, duration=%lldus%s%s",
             path, sd->out_sample_rate, sd->out_channels, sd->out_bits,
             (long long)sd->duration_us,
             sd->raw_pcm_passthrough ? ", rawFallback=" : "",
             sd->raw_pcm_passthrough ? sd->raw_pcm_name : "");
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
    streamCopyResidual(sd, out_buf, out_max_bytes, &bytes_written);

    if (sd->raw_dsd_passthrough) {
        while (bytes_written < out_max_bytes && !sd->eof_reached) {
            int ret = av_read_frame(sd->fmt_ctx, sd->pkt);
            if (ret < 0) {
                sd->eof_reached = true;
                break;
            }
            if (sd->pkt->stream_index != sd->audio_stream_idx) {
                av_packet_unref(sd->pkt);
                continue;
            }

            const int normalizedBytes = normalizeRawDsdPacket(sd, sd->pkt);
            av_packet_unref(sd->pkt);
            if (normalizedBytes < 0) {
                LOGE("stream_decoder_read: normalizeRawDsdPacket failed");
                sd->eof_reached = true;
                break;
            }
            if (normalizedBytes == 0) {
                continue;
            }

            sd->residual_buf_size = normalizedBytes;
            sd->residual_buf_pos = 0;
            streamCopyResidual(sd, out_buf, out_max_bytes, &bytes_written);
        }
        return bytes_written > 0 ? bytes_written : -1;
    }

    // Raw PCM fallback path: avformat gives us payload packets directly.
    // Feed packet data into swr as packed float/double/S16/S32 and output S32LE/S16LE.
    if (sd->raw_pcm_passthrough) {
        while (bytes_written < out_max_bytes && !sd->eof_reached) {
            int ret = av_read_frame(sd->fmt_ctx, sd->pkt);
            if (ret < 0) {
                sd->eof_reached = true;
                break;
            }
            if (sd->pkt->stream_index != sd->audio_stream_idx) {
                av_packet_unref(sd->pkt);
                continue;
            }

            if (sd->raw_pcm_frame_size <= 0) {
                av_packet_unref(sd->pkt);
                sd->eof_reached = true;
                break;
            }

            const int in_samples = sd->pkt->size / sd->raw_pcm_frame_size;
            if (in_samples <= 0) {
                av_packet_unref(sd->pkt);
                continue;
            }

            const uint8_t* in_data[1] = { sd->pkt->data };
            int out_samples = swr_convert(
                    sd->swr_ctx,
                    &sd->residual_buf,
                    sd->residual_buf_capacity / sd->out_bytes_per_sample / sd->out_channels,
                    in_data,
                    in_samples
            );
            av_packet_unref(sd->pkt);

            if (out_samples < 0) {
                LOGE("stream_decoder_read: raw PCM swr_convert failed: %d", out_samples);
                sd->eof_reached = true;
                break;
            }
            if (out_samples == 0) continue;

            int resampled_bytes = out_samples * sd->out_channels * sd->out_bytes_per_sample;
            if (resampled_bytes > sd->residual_buf_capacity) {
                LOGE("stream_decoder_read: raw PCM overflow! resampled=%d > capacity=%d, clamping",
                     resampled_bytes, sd->residual_buf_capacity);
                resampled_bytes = sd->residual_buf_capacity;
            }
            sd->residual_buf_size = resampled_bytes;
            sd->residual_buf_pos = 0;
            streamCopyResidual(sd, out_buf, out_max_bytes, &bytes_written);
        }
    } else {

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
                        int send_ret = sendPacketSafe(sd->codec_ctx, nullptr);
                        if (send_ret == -100) {
                            LOGE("stream_decoder_read: SIGABRT caught during flush, aborting");
                            sd->eof_reached = true;
                            break;
                        }
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
                ret = sendPacketSafe(sd->codec_ctx, sd->pkt);
                av_packet_unref(sd->pkt);
                if (ret == -100) {
                    LOGE("stream_decoder_read: SIGABRT caught in avcodec_send_packet, aborting");
                    sd->eof_reached = true;
                    break;
                }
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
    if (sd->codec_ctx) {
        avcodec_flush_buffers(sd->codec_ctx);
    }

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
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(sd));
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


static bool is_lossy_audio_codec(enum AVCodecID codec_id) {
    switch (codec_id) {
        case AV_CODEC_ID_AAC:
        case AV_CODEC_ID_MP3:
        case AV_CODEC_ID_VORBIS:
        case AV_CODEC_ID_OPUS:
        case AV_CODEC_ID_WMAV1:
        case AV_CODEC_ID_WMAV2:
        case AV_CODEC_ID_AMR_NB:
        case AV_CODEC_ID_AMR_WB:
            return true;
        default:
            return false;
    }
}

static int probe_effective_stream_sample_rate(
    AVFormatContext *fmt_ctx,
    int stream_index,
    const AVCodecParameters *codecpar
) {
    if (!fmt_ctx || !codecpar) return 0;
    const int fallback = codecpar->sample_rate;
    if (!is_lossy_audio_codec(codecpar->codec_id)) return fallback;

    const AVCodec *decoder = avcodec_find_decoder(codecpar->codec_id);
    if (!decoder) return fallback;

    AVCodecContext *codec_ctx = avcodec_alloc_context3(decoder);
    if (!codec_ctx) return fallback;
    if (avcodec_parameters_to_context(codec_ctx, codecpar) < 0) {
        avcodec_free_context(&codec_ctx);
        return fallback;
    }
    if (avcodec_open2(codec_ctx, decoder, nullptr) < 0) {
        int rate = codec_ctx->sample_rate > 0 ? codec_ctx->sample_rate : fallback;
        avcodec_free_context(&codec_ctx);
        return rate;
    }

    int effective = codec_ctx->sample_rate > 0 ? codec_ctx->sample_rate : fallback;

    // AAC SBR/HE-AAC can expose a 22.05/24 kHz core rate in codecpar while the
    // decoder output is 44.1/48 kHz. Decode a tiny prefix to let FFmpeg update
    // AVFrame/AVCodecContext.sample_rate, then seek back for callers that keep
    // using the same AVFormatContext.
    const bool worth_decoding = codecpar->codec_id == AV_CODEC_ID_AAC ||
        (fallback > 0 && fallback <= 24000);
    if (worth_decoding) {
        av_seek_frame(fmt_ctx, stream_index, 0, AVSEEK_FLAG_BACKWARD);
        avcodec_flush_buffers(codec_ctx);
        AVPacket *pkt = av_packet_alloc();
        AVFrame *frame = av_frame_alloc();
        if (pkt && frame) {
            int packets = 0;
            while (packets < 48 && av_read_frame(fmt_ctx, pkt) >= 0) {
                if (pkt->stream_index == stream_index) {
                    packets++;
                    if (avcodec_send_packet(codec_ctx, pkt) == 0) {
                        while (avcodec_receive_frame(codec_ctx, frame) == 0) {
                            if (frame->sample_rate > effective) effective = frame->sample_rate;
                            if (codec_ctx->sample_rate > effective) effective = codec_ctx->sample_rate;
                            av_frame_unref(frame);
                            if (fallback > 0 && effective > fallback) break;
                        }
                    }
                }
                av_packet_unref(pkt);
                if (fallback > 0 && effective > fallback) break;
            }
        }
        if (frame) av_frame_free(&frame);
        if (pkt) av_packet_free(&pkt);
        av_seek_frame(fmt_ctx, -1, 0, AVSEEK_FLAG_BACKWARD);
    }

    avcodec_free_context(&codec_ctx);
    return effective > 0 ? effective : fallback;
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
        jstring jkey = newJStringFromUtf8Lenient(env, key);
        jstring jval = newJStringFromUtf8Lenient(env, value);
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

            {
                int effective_sr = probe_effective_stream_sample_rate(fmt_ctx, (int)i, stream->codecpar);
                if (effective_sr > 0) {
                    snprintf(buf, sizeof(buf), "%d", effective_sr);
                    putStr((std::string(prefix) + "effective_sample_rate").c_str(), buf);
                }
            }

            snprintf(buf, sizeof(buf), "%d", stream->codecpar->channels);
            putStr((std::string(prefix) + "channels").c_str(), buf);

            snprintf(buf, sizeof(buf), "%d", stream->codecpar->bits_per_raw_sample);
            putStr((std::string(prefix) + "bits_per_raw_sample").c_str(), buf);

            snprintf(buf, sizeof(buf), "%d", stream->codecpar->bits_per_coded_sample);
            putStr((std::string(prefix) + "bits_per_coded_sample").c_str(), buf);

            {
                int bps = detect_bits_per_sample(stream->codecpar);
                snprintf(buf, sizeof(buf), "%d", bps);
                putStr((std::string(prefix) + "bits_per_sample").c_str(), buf);
            }

            // 输出 sample_fmt 供 Kotlin 侧 fallback 推断位深
            if (stream->codecpar->format != AV_SAMPLE_FMT_NONE) {
                putStr((std::string(prefix) + "sample_fmt").c_str(), av_get_sample_fmt_name((AVSampleFormat)stream->codecpar->format));
            }

            const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
            if (codec) {
                putStr((std::string(prefix) + "codec_name").c_str(), codec->name);
                if (codec->long_name)
                    putStr((std::string(prefix) + "codec_long_name").c_str(), codec->long_name);
            }
            if (stream->codecpar->profile != FF_PROFILE_UNKNOWN) {
                const char *profile = avcodec_profile_name(stream->codecpar->codec_id, stream->codecpar->profile);
                if (profile) putStr((std::string(prefix) + "codec_profile").c_str(), profile);
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

// ========================== Write Metadata directly ==========================

// Helper: read little-endian 32-bit unsigned
static uint32_t read_le32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

// Helper: write little-endian 32-bit unsigned
static void write_le32(uint8_t *p, uint32_t v) {
    p[0] = v & 0xFF;
    p[1] = (v >> 8) & 0xFF;
    p[2] = (v >> 16) & 0xFF;
    p[3] = (v >> 24) & 0xFF;
}

// Helper: read big-endian 24-bit unsigned
static uint32_t read_be24(const uint8_t *p) {
    return ((uint32_t)p[0] << 16) | ((uint32_t)p[1] << 8) | (uint32_t)p[2];
}

// Helper: write big-endian 24-bit unsigned
static void write_be24(uint8_t *p, uint32_t v) {
    p[0] = (v >> 16) & 0xFF;
    p[1] = (v >> 8) & 0xFF;
    p[2] = v & 0xFF;
}

/**
 * Write metadata to FLAC file by modifying VORBIS_COMMENT block.
 * Preserves all other metadata blocks (STREAMINFO, PICTURE, etc.) exactly.
 * Returns 0 on success, negative on error.
 */
static int writeFlacMetadata(const char *filePath, const char *tmpPath,
                              const char **keys, const char **values, int tagCount) {
    FILE *fin = fopen(filePath, "rb");
    if (!fin) { LOGE("writeFlac: cannot open input %s", filePath); return -10; }

    // Get file size
    fseek(fin, 0, SEEK_END);
    long fileSize = ftell(fin);
    fseek(fin, 0, SEEK_SET);

    if (fileSize < 4) { fclose(fin); return -11; }

    // Read entire file
    uint8_t *data = (uint8_t *)malloc(fileSize);
    if (!data) { fclose(fin); return -12; }
    size_t readCount = fread(data, 1, fileSize, fin);
    fclose(fin);
    if ((long)readCount != fileSize) { free(data); return -13; }

    // Verify FLAC marker
    if (memcmp(data, "fLaC", 4) != 0) {
        LOGE("writeFlac: not a FLAC file");
        free(data);
        return -14;
    }

    // Parse metadata blocks to find VORBIS_COMMENT and the end of all metadata
    size_t pos = 4;
    size_t vorbisBlockStart = 0;
    size_t vorbisBlockEnd = 0;
    size_t metadataEnd = 0; // end of ALL metadata blocks (start of audio data)
    bool foundVorbis = false;

    while (pos + 4 <= (size_t)fileSize) {
        uint8_t headerByte = data[pos];
        bool isLast = (headerByte & 0x80) != 0;
        uint8_t blockType = headerByte & 0x7F;
        uint32_t blockLen = read_be24(data + pos + 1);

        size_t blockDataStart = pos + 4;
        size_t blockDataEnd = blockDataStart + blockLen;

        if (blockDataEnd > (size_t)fileSize) {
            LOGE("writeFlac: block extends beyond file at pos=%zu", pos);
            break;
        }

        if (blockType == 4) { // VORBIS_COMMENT
            foundVorbis = true;
            vorbisBlockStart = pos;
            vorbisBlockEnd = blockDataEnd;
            LOGI("writeFlac: found VORBIS_COMMENT at %zu, len=%u", pos, blockLen);
        }

        metadataEnd = blockDataEnd;
        if (isLast) break;
        pos = blockDataEnd;
    }

    if (!foundVorbis) {
        LOGE("writeFlac: VORBIS_COMMENT block not found");
        free(data);
        return -15;
    }

    // Build new VORBIS_COMMENT block
    const char *vendor = "RawSMusic";
    uint32_t vendorLen = (uint32_t)strlen(vendor);

    // Read existing comments to preserve ones we're not changing
    std::vector<std::pair<std::string, std::string>> existingComments;
    if (vorbisBlockStart > 0) {
        size_t cp = vorbisBlockStart + 4; // skip block header
        if (cp + 4 <= vorbisBlockEnd) {
            uint32_t oldVendorLen = read_le32(data + cp);
            cp += 4 + oldVendorLen;
            if (cp + 4 <= vorbisBlockEnd) {
                uint32_t oldCommentCount = read_le32(data + cp);
                cp += 4;
                for (uint32_t i = 0; i < oldCommentCount && cp + 4 <= vorbisBlockEnd; i++) {
                    uint32_t cLen = read_le32(data + cp);
                    cp += 4;
                    if (cp + cLen <= vorbisBlockEnd) {
                        std::string comment((const char *)(data + cp), cLen);
                        size_t eqPos = comment.find('=');
                        if (eqPos != std::string::npos) {
                            std::string cKey = comment.substr(0, eqPos);
                            std::string cKeyUpper = cKey;
                            for (auto &ch : cKeyUpper) ch = toupper(ch);
                            existingComments.push_back({cKeyUpper, comment.substr(eqPos + 1)});
                        }
                    }
                    cp += cLen;
                }
            }
        }
    }

    // Merge: new values override existing
    std::vector<std::pair<std::string, std::string>> finalComments;

    for (auto &ec : existingComments) {
        bool overwritten = false;
        for (int i = 0; i < tagCount; i++) {
            std::string keyUpper = keys[i];
            for (auto &ch : keyUpper) ch = toupper(ch);
            if (ec.first == keyUpper) { overwritten = true; break; }
        }
        if (!overwritten) {
            finalComments.push_back(ec);
        }
    }

    for (int i = 0; i < tagCount; i++) {
        if (values[i] && strlen(values[i]) > 0) {
            std::string keyUpper = keys[i];
            for (auto &ch : keyUpper) ch = toupper(ch);
            finalComments.push_back({keyUpper, values[i]});
        }
    }

    // Calculate new VORBIS_COMMENT block size
    uint32_t commentDataSize = 4 + vendorLen + 4; // vendor_len + vendor + comment_count
    for (auto &fc : finalComments) {
        commentDataSize += 4 + (uint32_t)(fc.first.length() + 1 + fc.second.length());
    }
    uint32_t newVorbisBlockLen = commentDataSize;
    size_t newVorbisBlockSize = 4 + newVorbisBlockLen;

    // Build new file:
    // [metadata blocks before VORBIS] + [new VORBIS_COMMENT] + [metadata blocks after VORBIS] + [audio data]
    size_t beforeVorbis = vorbisBlockStart;
    size_t afterVorbis = vorbisBlockEnd;
    size_t audioStart = metadataEnd; // audio data starts after ALL metadata blocks
    size_t audioDataSize = fileSize - audioStart;
    size_t newFileSize = beforeVorbis + newVorbisBlockSize + (metadataEnd - afterVorbis) + audioDataSize;

    uint8_t *newData = (uint8_t *)malloc(newFileSize);
    if (!newData) { free(data); return -16; }

    // 1. Copy metadata blocks before VORBIS_COMMENT, clearing is_last flags
    memcpy(newData, data, beforeVorbis);
    size_t clearPos = 4;
    while (clearPos + 4 <= beforeVorbis) {
        newData[clearPos] &= 0x7F; // clear is_last bit
        uint32_t bLen = read_be24(newData + clearPos + 1);
        clearPos += 4 + bLen;
    }

    // 2. Write new VORBIS_COMMENT block (NOT last yet)
    size_t wp = beforeVorbis;
    newData[wp] = 0x04; // type 4, is_last=0
    write_be24(newData + wp + 1, newVorbisBlockLen);
    wp += 4;
    write_le32(newData + wp, vendorLen);
    wp += 4;
    memcpy(newData + wp, vendor, vendorLen);
    wp += vendorLen;
    write_le32(newData + wp, (uint32_t)finalComments.size());
    wp += 4;
    for (auto &fc : finalComments) {
        std::string entry = fc.first + "=" + fc.second;
        uint32_t entryLen = (uint32_t)entry.length();
        write_le32(newData + wp, entryLen);
        wp += 4;
        memcpy(newData + wp, entry.c_str(), entryLen);
        wp += entryLen;
    }

    // 3. Copy metadata blocks AFTER VORBIS_COMMENT (e.g. PICTURE), clearing is_last flags
    size_t afterVorbisBlocks = afterVorbis;
    if (afterVorbis < metadataEnd) {
        memcpy(newData + wp, data + afterVorbis, metadataEnd - afterVorbis);
        // Clear is_last flags on these copied blocks
        size_t cp2 = wp;
        while (cp2 + 4 <= wp + (metadataEnd - afterVorbis)) {
            newData[cp2] &= 0x7F; // clear is_last bit
            uint32_t bLen = read_be24(newData + cp2 + 1);
            cp2 += 4 + bLen;
        }
        wp += (metadataEnd - afterVorbis);
    }

    // 4. Set is_last flag on the LAST metadata block in the new data
    size_t mp = 4;
    while (mp + 4 <= wp) {
        uint32_t bLen = read_be24(newData + mp + 1);
        size_t bEnd = mp + 4 + bLen;
        if (bEnd >= wp) {
            newData[mp] |= 0x80; // set is_last flag
            break;
        }
        mp = bEnd;
    }

    // 5. Copy audio data
    memcpy(newData + wp, data + audioStart, audioDataSize);
    wp += audioDataSize;

    free(data);

    // Write to temp file
    FILE *fout = fopen(tmpPath, "wb");
    if (!fout) { free(newData); return -17; }
    fwrite(newData, 1, newFileSize, fout);
    fclose(fout);
    free(newData);

    LOGI("writeFlac: success, wrote %zu bytes to %s (original=%ld)", newFileSize, tmpPath, fileSize);
    return 0;
}

/**
 * Write metadata to any audio format using FFmpeg remuxing.
 * Works for MP3, M4A/AAC, OGG/Opus, WAV, AIFF, WMA, APE, etc.
 * Preserves all audio streams and existing data; only updates metadata tags.
 * Returns 0 on success, negative on error.
 */
static int writeMetadataGeneric(const char *inputPath, const char *tmpPath,
                                 const char **keys, const char **values, int tagCount) {
    AVFormatContext *ifmt_ctx = nullptr;
    AVFormatContext *ofmt_ctx = nullptr;
    int ret = 0;

    // Open input
    ret = avformat_open_input(&ifmt_ctx, inputPath, nullptr, nullptr);
    if (ret < 0) {
        LOGE("writeMetaGeneric: avformat_open_input failed: %d", ret);
        return -30;
    }

    ret = avformat_find_stream_info(ifmt_ctx, nullptr);
    if (ret < 0) {
        LOGE("writeMetaGeneric: avformat_find_stream_info failed: %d", ret);
        avformat_close_input(&ifmt_ctx);
        return -31;
    }

    // Create output context (guess format from tmpPath extension)
    ret = avformat_alloc_output_context2(&ofmt_ctx, nullptr, nullptr, tmpPath);
    if (ret < 0 || !ofmt_ctx) {
        LOGE("writeMetaGeneric: avformat_alloc_output_context2 failed: %d", ret);
        avformat_close_input(&ifmt_ctx);
        return -32;
    }

    // Copy streams
    for (unsigned int i = 0; i < ifmt_ctx->nb_streams; i++) {
        AVStream *in_stream = ifmt_ctx->streams[i];
        AVStream *out_stream = avformat_new_stream(ofmt_ctx, nullptr);
        if (!out_stream) {
            LOGE("writeMetaGeneric: avformat_new_stream failed for stream %u", i);
            ret = -33;
            goto cleanup;
        }

        ret = avcodec_parameters_copy(out_stream->codecpar, in_stream->codecpar);
        if (ret < 0) {
            LOGE("writeMetaGeneric: avcodec_parameters_copy failed: %d", ret);
            goto cleanup;
        }
        out_stream->codecpar->codec_tag = 0;

        // Copy time base
        out_stream->time_base = in_stream->time_base;
    }

    // Set new metadata on output context
    // First copy existing metadata
    if (ifmt_ctx->metadata) {
        av_dict_copy(&ofmt_ctx->metadata, ifmt_ctx->metadata, 0);
    }
    // Then overwrite with new values
    for (int i = 0; i < tagCount; i++) {
        if (values[i] && strlen(values[i]) > 0) {
            av_dict_set(&ofmt_ctx->metadata, keys[i], values[i], 0);
            LOGI("writeMetaGeneric: set %s=%s", keys[i], values[i]);
        }
    }

    // Open output file
    if (!(ofmt_ctx->oformat->flags & AVFMT_NOFILE)) {
        ret = avio_open(&ofmt_ctx->pb, tmpPath, AVIO_FLAG_WRITE);
        if (ret < 0) {
            LOGE("writeMetaGeneric: avio_open failed: %d", ret);
            goto cleanup;
        }
    }

    // Write header
    ret = avformat_write_header(ofmt_ctx, nullptr);
    if (ret < 0) {
        LOGE("writeMetaGeneric: avformat_write_header failed: %d", ret);
        goto cleanup;
    }

    // Remux packets
    {
        AVPacket *pkt = av_packet_alloc();
        if (!pkt) {
            ret = -34;
            goto cleanup;
        }

        while (true) {
            ret = av_read_frame(ifmt_ctx, pkt);
            if (ret < 0) break; // EOF or error

            AVStream *in_stream = ifmt_ctx->streams[pkt->stream_index];
            AVStream *out_stream = ofmt_ctx->streams[pkt->stream_index];

            // Rescale timestamps
            pkt->pts = av_rescale_q_rnd(pkt->pts, in_stream->time_base, out_stream->time_base,
                                         (AVRounding)(AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
            pkt->dts = av_rescale_q_rnd(pkt->dts, in_stream->time_base, out_stream->time_base,
                                         (AVRounding)(AV_ROUND_NEAR_INF | AV_ROUND_PASS_MINMAX));
            pkt->duration = av_rescale_q(pkt->duration, in_stream->time_base, out_stream->time_base);
            pkt->pos = -1;

            ret = av_interleaved_write_frame(ofmt_ctx, pkt);
            if (ret < 0) {
                LOGE("writeMetaGeneric: av_interleaved_write_frame failed: %d", ret);
                av_packet_free(&pkt);
                goto cleanup;
            }
            av_packet_unref(pkt);
        }
        av_packet_free(&pkt);
    }

    // Write trailer
    ret = av_write_trailer(ofmt_ctx);
    if (ret < 0) {
        LOGE("writeMetaGeneric: av_write_trailer failed: %d", ret);
        goto cleanup;
    }

    ret = 0;
    LOGI("writeMetaGeneric: success, wrote to %s", tmpPath);

cleanup:
    if (ofmt_ctx) {
        if (!(ofmt_ctx->oformat->flags & AVFMT_NOFILE) && ofmt_ctx->pb) {
            avio_closep(&ofmt_ctx->pb);
        }
        avformat_free_context(ofmt_ctx);
    }
    avformat_close_input(&ifmt_ctx);
    return ret;
}

/**
 * Write metadata to audio file.
 * FLAC: direct binary VORBIS_COMMENT editing (fast, lossless).
 * Other formats (MP3, M4A, OGG, WAV, AIFF, WMA, APE, etc.): FFmpeg remuxing.
 * Returns 0 on success, negative on error.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_core_common_ffmpeg_FFmpegBridge_nativeWriteMetadata(
    JNIEnv *env, jobject, jstring jpath, jobject metadataMap, jstring jcacheDir) {

    const char *inputPath = env->GetStringUTFChars(jpath, nullptr);
    if (!inputPath) return -1;

    const char *cacheDir = env->GetStringUTFChars(jcacheDir, nullptr);

    // Get file extension
    std::string inputPathStr(inputPath);
    std::string ext = "";
    size_t dotPos = inputPathStr.rfind('.');
    if (dotPos != std::string::npos) {
        ext = inputPathStr.substr(dotPos);
        for (auto &c : ext) c = tolower(c);
    }
    std::string tmpPath = std::string(cacheDir ? cacheDir : "/data/local/tmp") + "/rawsmeta_tmp" + ext;

    LOGI("writeMetadata: input=%s, tmp=%s, ext=%s", inputPath, tmpPath.c_str(), ext.c_str());

    // Read Java HashMap into arrays
    jclass mapClass = env->GetObjectClass(metadataMap);
    jmethodID entrySetMethod = env->GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;");
    jobject entrySet = env->CallObjectMethod(metadataMap, entrySetMethod);

    jclass setClass = env->GetObjectClass(entrySet);
    jmethodID iteratorMethod = env->GetMethodID(setClass, "iterator", "()Ljava/util/Iterator;");
    jobject iterator = env->CallObjectMethod(entrySet, iteratorMethod);

    jclass iterClass = env->GetObjectClass(iterator);
    jmethodID hasNextMethod = env->GetMethodID(iterClass, "hasNext", "()Z");
    jmethodID nextMethod = env->GetMethodID(iterClass, "next", "()Ljava/lang/Object;");

    jclass entryClass = env->FindClass("java/util/Map$Entry");
    jmethodID getKeyMethod = env->GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;");
    jmethodID getValueMethod = env->GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;");

    // Collect tags (max 32)
    const char *keys[32];
    const char *values[32];
    jstring jkeys[32];
    jstring jvals[32];
    int tagCount = 0;

    while (env->CallBooleanMethod(iterator, hasNextMethod) && tagCount < 32) {
        jobject entry = env->CallObjectMethod(iterator, nextMethod);
        jstring jkey = (jstring) env->CallObjectMethod(entry, getKeyMethod);
        jstring jval = (jstring) env->CallObjectMethod(entry, getValueMethod);

        keys[tagCount] = env->GetStringUTFChars(jkey, nullptr);
        values[tagCount] = env->GetStringUTFChars(jval, nullptr);
        jkeys[tagCount] = jkey;
        jvals[tagCount] = jval;
        tagCount++;

        env->DeleteLocalRef(entry);
    }

    int result = -99;

    if (ext == ".flac") {
        // Fast path: direct binary VORBIS_COMMENT editing for FLAC (preserves audio data exactly)
        result = writeFlacMetadata(inputPath, tmpPath.c_str(), keys, values, tagCount);
        if (result != 0) {
            LOGI("writeMetadata: FLAC binary edit failed (%d), trying generic remux", result);
            remove(tmpPath.c_str());
            result = writeMetadataGeneric(inputPath, tmpPath.c_str(), keys, values, tagCount);
        }
    } else {
        // Generic path: FFmpeg remuxing for MP3, M4A, OGG, WAV, AIFF, WMA, APE, etc.
        result = writeMetadataGeneric(inputPath, tmpPath.c_str(), keys, values, tagCount);
    }

    // Release strings
    for (int i = 0; i < tagCount; i++) {
        env->ReleaseStringUTFChars(jkeys[i], keys[i]);
        env->ReleaseStringUTFChars(jvals[i], values[i]);
    }

    if (result == 0) {
        // Verify temp file exists and has reasonable size
        FILE *check = fopen(tmpPath.c_str(), "rb");
        if (check) {
            fseek(check, 0, SEEK_END);
            long tmpSize = ftell(check);
            fclose(check);
            LOGI("writeMetadata: temp file ready at %s (size=%ld bytes)", tmpPath.c_str(), tmpSize);
        } else {
            LOGE("writeMetadata: temp file missing after successful write!");
            result = -99;
        }
    } else {
        remove(tmpPath.c_str());
    }

    env->ReleaseStringUTFChars(jpath, inputPath);
    if (cacheDir) env->ReleaseStringUTFChars(jcacheDir, cacheDir);
    return result;
}
