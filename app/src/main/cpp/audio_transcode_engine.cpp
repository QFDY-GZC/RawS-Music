#include "audio_transcode_engine.h"
#include "audio_transcode_policy.h"
#include "dsf_writer.h"
#include "offline_dsd_encoder.h"

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <new>
#include <sstream>
#include <string>
#include <tuple>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/channel_layout.h>
#include <libavutil/error.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libavutil/sha.h>
#include <libswresample/swresample.h>
}

#define LOG_TAG "AudioTranscode"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace rawsmusic::transcode {
namespace {

struct OpenAudioInput {
    AVFormatContext* format = nullptr;
    AVCodecContext* decoder = nullptr;
    int stream_index = -1;

    ~OpenAudioInput() {
        avcodec_free_context(&decoder);
        if (format) avformat_close_input(&format);
    }
};

bool isRecoverableAudioDecodeError(int code) {
    // Keep recovery deliberately narrow: FFmpeg uses AVERROR_INVALIDDATA for a damaged audio
    // packet/frame that can be skipped while decoding continues. Allocation, I/O, decoder-state
    // and transport failures remain fatal. The digest path uses the same rule so lossless
    // verification does not silently compare different decode policies.
    return code == AVERROR_INVALIDDATA;
}

void logRecoverableAudioDecodeError(const char* stage, int code) {
    LOGW(
        "%s recoverable code=%d; skipping damaged audio packet/frame",
        stage,
        code
    );
}

bool codecSupportsSampleFormat(const AVCodec* codec, AVSampleFormat format) {
    if (!codec || !codec->sample_fmts) return false;
    for (const AVSampleFormat* it = codec->sample_fmts;
         *it != AV_SAMPLE_FMT_NONE;
         ++it) {
        if (*it == format) return true;
    }
    return false;
}

bool codecSupportsSampleRate(const AVCodec* codec, int sampleRate) {
    if (!codec || sampleRate <= 0) return false;
    if (!codec->supported_samplerates) return true;
    for (const int* it = codec->supported_samplerates; *it != 0; ++it) {
        if (*it == sampleRate) return true;
    }
    return false;
}

int normalizedBitDepth(const AVCodecParameters* parameters, const AVCodecContext* decoder) {
    int bits = 0;
    if (decoder) {
        bits = decoder->bits_per_raw_sample;
        if (bits <= 0) bits = decoder->bits_per_coded_sample;
    }
    if (parameters && bits <= 0) {
        bits = parameters->bits_per_raw_sample;
        if (bits <= 0) bits = parameters->bits_per_coded_sample;
    }
    if (bits <= 0 && decoder && decoder->sample_fmt != AV_SAMPLE_FMT_NONE) {
        const int bytes = av_get_bytes_per_sample(decoder->sample_fmt);
        if (bytes == 2) bits = 16;
        else if (bytes == 4) bits = 32;
    }
    return normalizeSourceBitDepth(bits);
}

bool isDsdCodecId(AVCodecID codecId) {
    switch (codecId) {
        case AV_CODEC_ID_DSD_LSBF:
        case AV_CODEC_ID_DSD_MSBF:
        case AV_CODEC_ID_DSD_LSBF_PLANAR:
        case AV_CODEC_ID_DSD_MSBF_PLANAR:
            return true;
        default:
            return false;
    }
}

bool isPlanarDsdCodecId(AVCodecID codecId) {
    return codecId == AV_CODEC_ID_DSD_LSBF_PLANAR ||
        codecId == AV_CODEC_ID_DSD_MSBF_PLANAR;
}

bool isLsbfDsdCodecId(AVCodecID codecId) {
    return codecId == AV_CODEC_ID_DSD_LSBF ||
        codecId == AV_CODEC_ID_DSD_LSBF_PLANAR;
}

uint8_t reverseDsdBits(uint8_t value) {
    value = static_cast<uint8_t>(((value & 0x55u) << 1u) | ((value & 0xAAu) >> 1u));
    value = static_cast<uint8_t>(((value & 0x33u) << 2u) | ((value & 0xCCu) >> 2u));
    return static_cast<uint8_t>((value << 4u) | (value >> 4u));
}

int logicalDsdRateHz(const AVCodecParameters* parameters) {
    if (!parameters || !isDsdCodecId(parameters->codec_id) || parameters->sample_rate <= 0) {
        return 0;
    }
    return logicalDsdRateFromCodecByteRate(parameters->sample_rate);
}

bool dsdRateMatchesMultiplier(int logicalRateHz, int multiplier) {
    if (!isSelectableDsdMultiplier(multiplier) || logicalRateHz <= 0) return false;
    return logicalRateHz == 44'100 * multiplier || logicalRateHz == 48'000 * multiplier;
}

int reportedSourceBitDepth(const AVCodecParameters* parameters, const AVCodecContext* decoder) {
    if (!parameters) return 0;
    if (isDsdCodecId(parameters->codec_id)) return 1;

    int bits = 0;
    if (decoder) {
        bits = decoder->bits_per_raw_sample;
        if (bits <= 0) bits = decoder->bits_per_coded_sample;
    }
    if (bits <= 0) {
        bits = parameters->bits_per_raw_sample;
        if (bits <= 0) bits = parameters->bits_per_coded_sample;
    }
    if (bits <= 0) bits = av_get_exact_bits_per_sample(parameters->codec_id);
    return normalizeSourceBitDepth(bits);
}

int64_t durationMs(const AVFormatContext* format, const AVStream* stream) {
    // ASF/WMA stream duration is expressed on the packet timeline and can retain the container
    // preroll. AVFormatContext::duration is the presentation duration with that preroll removed.
    // Comparing the stream value with a source FLAC therefore produces a false multi-second
    // mismatch even when all encoded audio is present.
    if (format && format->iformat && format->iformat->name &&
        std::strcmp(format->iformat->name, "asf") == 0 &&
        format->duration != AV_NOPTS_VALUE && format->duration > 0) {
        return format->duration / (AV_TIME_BASE / 1000);
    }
    if (stream && stream->duration != AV_NOPTS_VALUE && stream->duration > 0) {
        return av_rescale_q(stream->duration, stream->time_base, AVRational{1, 1000});
    }
    if (format && format->duration != AV_NOPTS_VALUE && format->duration > 0) {
        return format->duration / (AV_TIME_BASE / 1000);
    }
    return 0;
}

int openAudioInput(const char* path, OpenAudioInput& opened) {
    if (!path || !*path) return kTranscodeInvalidArgument;

    int result = avformat_open_input(&opened.format, path, nullptr, nullptr);
    if (result < 0) {
        LOGE("open input failed path=%s code=%d", path, result);
        return result;
    }
    result = avformat_find_stream_info(opened.format, nullptr);
    if (result < 0) {
        LOGE("stream info failed path=%s code=%d", path, result);
        return result;
    }

    for (unsigned int index = 0; index < opened.format->nb_streams; ++index) {
        if (opened.format->streams[index]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            opened.stream_index = static_cast<int>(index);
            break;
        }
    }
    if (opened.stream_index < 0) {
        LOGE("no audio stream path=%s streams=%u", path, opened.format->nb_streams);
        return AVERROR_STREAM_NOT_FOUND;
    }

    AVCodecParameters* parameters = opened.format->streams[opened.stream_index]->codecpar;
    const AVCodec* codec = avcodec_find_decoder(parameters->codec_id);
    if (!codec) {
        LOGE("decoder unavailable path=%s codecId=%d", path, static_cast<int>(parameters->codec_id));
        return AVERROR_DECODER_NOT_FOUND;
    }

    opened.decoder = avcodec_alloc_context3(codec);
    if (!opened.decoder) {
        LOGE("decoder allocation failed path=%s codecId=%d", path, static_cast<int>(parameters->codec_id));
        return AVERROR(ENOMEM);
    }
    result = avcodec_parameters_to_context(opened.decoder, parameters);
    if (result < 0) {
        LOGE("decoder parameters failed path=%s codecId=%d code=%d", path, static_cast<int>(parameters->codec_id), result);
        return result;
    }
    result = avcodec_open2(opened.decoder, codec, nullptr);
    if (result < 0) {
        LOGE("decoder open failed path=%s codecId=%d code=%d", path, static_cast<int>(parameters->codec_id), result);
        return result;
    }

    if (opened.decoder->sample_rate <= 0) {
        LOGE("decoder sample rate unavailable path=%s codecId=%d", path, static_cast<int>(parameters->codec_id));
        return kTranscodeUnsupportedSource;
    }
    if (opened.decoder->ch_layout.nb_channels <= 0) {
        const int fallbackChannels = parameters->ch_layout.nb_channels;
        if (fallbackChannels <= 0) return kTranscodeUnsupportedSource;
        av_channel_layout_default(&opened.decoder->ch_layout, fallbackChannels);
    }
    return 0;
}

bool isStandardMonoStereoLayout(const AVChannelLayout& layout) {
    if (layout.nb_channels <= 0 || layout.nb_channels > 2) return false;
    AVChannelLayout expected{};
    av_channel_layout_default(&expected, layout.nb_channels);
    const bool matches = av_channel_layout_compare(&layout, &expected) == 0;
    av_channel_layout_uninit(&expected);
    return matches;
}

struct PcmTargetConfig {
    const char* id;
    const char* muxer_name;
    const char* container_name;
    AVCodecID fixed_codec_id;
    bool lossless;
    bool flac_compression;
    int default_bit_rate_kbps;
};

constexpr std::array<PcmTargetConfig, 13> kPcmTargets{{
    {"flac", "flac", "flac", AV_CODEC_ID_FLAC, true, true, 0},
    {"ogg_flac", "ogg", "oga", AV_CODEC_ID_FLAC, true, true, 0},
    {"wav", "wav", "wav", AV_CODEC_ID_NONE, true, false, 0},
    {"aiff", "aiff", "aiff", AV_CODEC_ID_NONE, true, false, 0},
    {"alac", "ipod", "m4a", AV_CODEC_ID_ALAC, true, false, 0},
    {"wavpack", "wv", "wv", AV_CODEC_ID_WAVPACK, true, false, 0},
    {"tta", "tta", "tta", AV_CODEC_ID_TTA, true, false, 0},
    {"aac", "ipod", "m4a", AV_CODEC_ID_AAC, false, false, 256},
    {"mp2", "mp2", "mp2", AV_CODEC_ID_MP2, false, false, 256},
    {"mp3", "mp3", "mp3", AV_CODEC_ID_MP3, false, false, 256},
    {"opus", "ogg", "opus", AV_CODEC_ID_OPUS, false, false, 160},
    {"vorbis", "ogg", "ogg", AV_CODEC_ID_VORBIS, false, false, 192},
    {"wma", "asf", "wma", AV_CODEC_ID_WMAV2, false, false, 192},
}};

constexpr std::array<int, 6> kAacBitRatesKbps{{96, 128, 160, 192, 256, 320}};
constexpr std::array<int, 8> kMp2BitRatesKbps{{96, 128, 160, 192, 224, 256, 320, 384}};
constexpr std::array<int, 6> kMp3BitRatesKbps{{96, 128, 160, 192, 256, 320}};
constexpr std::array<int, 7> kOpusBitRatesKbps{{64, 96, 128, 160, 192, 256, 320}};
constexpr std::array<int, 6> kVorbisBitRatesKbps{{96, 128, 160, 192, 256, 320}};
constexpr std::array<int, 6> kWmaBitRatesKbps{{96, 128, 160, 192, 256, 320}};

const PcmTargetConfig* findTargetConfig(const char* id) {
    if (!id || !*id) return nullptr;
    for (const auto& target : kPcmTargets) {
        if (std::strcmp(target.id, id) == 0) return &target;
    }
    return nullptr;
}

AVCodecID codecIdForTarget(const PcmTargetConfig& target, int bitDepth) {
    if (target.fixed_codec_id != AV_CODEC_ID_NONE) return target.fixed_codec_id;
    if (std::strcmp(target.id, "wav") == 0) {
        if (bitDepth == 16) return AV_CODEC_ID_PCM_S16LE;
        if (bitDepth == 24) return AV_CODEC_ID_PCM_S24LE;
        if (bitDepth == 32) return AV_CODEC_ID_PCM_S32LE;
    }
    if (std::strcmp(target.id, "aiff") == 0) {
        if (bitDepth == 16) return AV_CODEC_ID_PCM_S16BE;
        if (bitDepth == 24) return AV_CODEC_ID_PCM_S24BE;
        if (bitDepth == 32) return AV_CODEC_ID_PCM_S32BE;
    }
    return AV_CODEC_ID_NONE;
}

std::vector<int> bitRatePresetsForTarget(const PcmTargetConfig& target) {
    if (std::strcmp(target.id, "aac") == 0) {
        return {kAacBitRatesKbps.begin(), kAacBitRatesKbps.end()};
    }
    if (std::strcmp(target.id, "mp2") == 0) {
        return {kMp2BitRatesKbps.begin(), kMp2BitRatesKbps.end()};
    }
    if (std::strcmp(target.id, "mp3") == 0) {
        return {kMp3BitRatesKbps.begin(), kMp3BitRatesKbps.end()};
    }
    if (std::strcmp(target.id, "opus") == 0) {
        return {kOpusBitRatesKbps.begin(), kOpusBitRatesKbps.end()};
    }
    if (std::strcmp(target.id, "vorbis") == 0) {
        return {kVorbisBitRatesKbps.begin(), kVorbisBitRatesKbps.end()};
    }
    if (std::strcmp(target.id, "wma") == 0) {
        return {kWmaBitRatesKbps.begin(), kWmaBitRatesKbps.end()};
    }
    return {};
}

AVSampleFormat selectIntegerSampleFormat(const AVCodec* codec, int targetBitDepth) {
    if (!codec) return AV_SAMPLE_FMT_NONE;
    if (targetBitDepth <= 16) {
        if (codecSupportsSampleFormat(codec, AV_SAMPLE_FMT_S16)) return AV_SAMPLE_FMT_S16;
        if (codecSupportsSampleFormat(codec, AV_SAMPLE_FMT_S16P)) return AV_SAMPLE_FMT_S16P;
    }
    if (targetBitDepth >= 24) {
        if (codecSupportsSampleFormat(codec, AV_SAMPLE_FMT_S32)) return AV_SAMPLE_FMT_S32;
        if (codecSupportsSampleFormat(codec, AV_SAMPLE_FMT_S32P)) return AV_SAMPLE_FMT_S32P;
    }
    return AV_SAMPLE_FMT_NONE;
}

AVSampleFormat selectLossySampleFormat(const AVCodec* codec) {
    if (!codec) return AV_SAMPLE_FMT_NONE;
    constexpr std::array<AVSampleFormat, 6> kPreferred{{
        AV_SAMPLE_FMT_FLTP,
        AV_SAMPLE_FMT_FLT,
        AV_SAMPLE_FMT_S16P,
        AV_SAMPLE_FMT_S16,
        AV_SAMPLE_FMT_S32P,
        AV_SAMPLE_FMT_S32,
    }};
    for (const auto format : kPreferred) {
        if (codecSupportsSampleFormat(codec, format)) return format;
    }
    return AV_SAMPLE_FMT_NONE;
}

bool pcmProfileCanOpenForChannels(
    const PcmTargetConfig& target,
    int sampleRateHz,
    int bitDepth,
    int bitRateKbps,
    int channels,
    AVCodecID* openedCodecId = nullptr
) {
    if (channels <= 0 || channels > 32) return false;
    const AVCodecID codecId = codecIdForTarget(target, target.lossless ? bitDepth : 0);
    if (codecId == AV_CODEC_ID_NONE) return false;
    const AVCodec* codec = avcodec_find_encoder(codecId);
    const AVOutputFormat* outputFormat = av_guess_format(target.muxer_name, nullptr, nullptr);
    if (!codec || !outputFormat || !codecSupportsSampleRate(codec, sampleRateHz)) return false;
    const int query = avformat_query_codec(outputFormat, codecId, FF_COMPLIANCE_NORMAL);
    if (query == 0) return false;

    const AVSampleFormat sampleFormat = target.lossless
        ? selectIntegerSampleFormat(codec, bitDepth)
        : selectLossySampleFormat(codec);
    if (sampleFormat == AV_SAMPLE_FMT_NONE) return false;
    if (!target.lossless && bitRateKbps <= 0) return false;

    AVCodecContext* context = avcodec_alloc_context3(codec);
    if (!context) return false;
    context->sample_rate = sampleRateHz;
    context->sample_fmt = sampleFormat;
    context->time_base = AVRational{1, sampleRateHz};
    if (target.lossless) context->bits_per_raw_sample = bitDepth;
    else {
        context->bit_rate = static_cast<int64_t>(bitRateKbps) * 1000LL;
        context->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
    }
    if (target.flac_compression) context->compression_level = 8;
    av_channel_layout_default(&context->ch_layout, channels);
    const int result = avcodec_open2(context, codec, nullptr);
    avcodec_free_context(&context);
    if (result < 0) return false;
    if (openedCodecId) *openedCodecId = codecId;
    return true;
}

bool pcmProfileCanOpen(
    const PcmTargetConfig& target,
    int sampleRateHz,
    int bitDepth,
    int bitRateKbps,
    AVCodecID* openedCodecId = nullptr
) {
    // Runtime capability descriptors remain a compact stereo baseline. Before a real task starts,
    // Kotlin performs a second exact probe with the source channel count so multichannel is never
    // advertised solely because the stereo profile opened successfully.
    return pcmProfileCanOpenForChannels(
        target,
        sampleRateHz,
        bitDepth,
        bitRateKbps,
        2,
        openedCodecId
    );
}

uint32_t xorshift32(uint32_t& state) {
    uint32_t value = state ? state : 0x6D2B79F5u;
    value ^= value << 13;
    value ^= value >> 17;
    value ^= value << 5;
    state = value ? value : 0xA341316Cu;
    return state;
}

double unitRandom(uint32_t& state) {
    return (static_cast<double>(xorshift32(state)) + 1.0) / 4294967297.0;
}

void applyTpdfS32To24(
    uint8_t** data,
    int samples,
    int channels,
    AVSampleFormat format,
    std::vector<uint32_t>& rng
) {
    if (!data || samples <= 0 || channels <= 0) return;
    const bool planar = av_sample_fmt_is_planar(format) != 0;
    if (rng.size() < static_cast<size_t>(channels)) {
        rng.resize(static_cast<size_t>(channels), 0x7F4A7C15u);
        for (int channel = 0; channel < channels; ++channel) {
            rng[static_cast<size_t>(channel)] ^= static_cast<uint32_t>(channel * 0x9E3779B9u);
        }
    }

    auto quantize = [&](int32_t value, int channel) -> int32_t {
        auto& state = rng[static_cast<size_t>(channel)];
        const double tpdf = (unitRandom(state) - unitRandom(state)) * 256.0;
        const double scaled = (static_cast<double>(value) + tpdf) / 256.0;
        const int64_t q24 = static_cast<int64_t>(std::nearbyint(scaled));
        const int64_t clipped = std::clamp<int64_t>(q24, -8388608LL, 8388607LL);
        return static_cast<int32_t>(clipped * 256LL);
    };

    if (planar) {
        for (int channel = 0; channel < channels; ++channel) {
            auto* plane = reinterpret_cast<int32_t*>(data[channel]);
            if (!plane) continue;
            for (int sample = 0; sample < samples; ++sample) {
                plane[sample] = quantize(plane[sample], channel);
            }
        }
    } else {
        auto* interleaved = reinterpret_cast<int32_t*>(data[0]);
        if (!interleaved) return;
        for (int sample = 0; sample < samples; ++sample) {
            for (int channel = 0; channel < channels; ++channel) {
                const int index = sample * channels + channel;
                interleaved[index] = quantize(interleaved[index], channel);
            }
        }
    }
}

std::string ffmpegErrorText(int code) {
    char text[AV_ERROR_MAX_STRING_SIZE] = {};
    if (code < 0 && av_strerror(code, text, sizeof(text)) == 0) return text;
    return "unknown";
}

}  // namespace

int probeAudioFile(const char* input_path, AudioProbeResult& result) {
    result = {};
    OpenAudioInput input;
    const int openResult = openAudioInput(input_path, input);
    if (openResult < 0) return openResult;

    const AVStream* stream = input.format->streams[input.stream_index];
    const AVCodecParameters* parameters = stream->codecpar;
    result.sample_rate_hz = input.decoder->sample_rate;
    result.bit_depth = reportedSourceBitDepth(parameters, input.decoder);
    result.channels = input.decoder->ch_layout.nb_channels;
    result.duration_ms = durationMs(input.format, stream);
    result.bit_rate = parameters->bit_rate > 0 ? parameters->bit_rate : input.format->bit_rate;
    result.codec_id = static_cast<int>(parameters->codec_id);
    result.is_dsd = isDsdCodecId(parameters->codec_id);
    result.bit_depth_meaningful = result.is_dsd || result.bit_depth > 0;
    for (unsigned int index = 0; index < input.format->nb_streams; ++index) {
        const AVStream* candidate = input.format->streams[index];
        if (!candidate || !candidate->codecpar ||
            candidate->codecpar->codec_type != AVMEDIA_TYPE_VIDEO) {
            continue;
        }

        // Audio containers such as FLAC/MP3/M4A may expose embedded album art as a
        // VIDEO stream with AV_DISPOSITION_ATTACHED_PIC. That stream is metadata,
        // not a real time-based video track, and must not make a normal music file
        // fail the "pure audio" transcode preflight.
        if ((candidate->disposition & AV_DISPOSITION_ATTACHED_PIC) != 0) {
            continue;
        }

        result.has_video_stream = true;
        break;
    }
    LOGI(
        "probe path=%s codecId=%d rate=%d channels=%d durationMs=%lld streamDuration=%lld "
        "streamTimeBase=%d/%d formatDurationUs=%lld formatStartUs=%lld",
        input_path,
        result.codec_id,
        result.sample_rate_hz,
        result.channels,
        static_cast<long long>(result.duration_ms),
        static_cast<long long>(stream->duration),
        stream->time_base.num,
        stream->time_base.den,
        static_cast<long long>(input.format->duration),
        static_cast<long long>(input.format->start_time)
    );
    if (result.is_dsd && result.sample_rate_hz > 0 &&
        result.sample_rate_hz <= std::numeric_limits<int>::max() / 8) {
        result.dsd_sample_rate_hz = result.sample_rate_hz * 8;
    }
    return 0;
}

/**
 * Pull-based PCM16 decoder used by Android hardware encoders.
 *
 * FFmpeg remains the source decoder/resampler so every source format accepted by the software
 * transcoder can feed MediaCodec without materialising a whole-track WAV/PCM temporary file.
 * The reader owns a small FIFO only; JNI copies directly into MediaCodec's direct input buffer.
 */
struct Pcm16ReaderContext {
    AudioTranscodeSession* session = nullptr;
    OpenAudioInput input;
    SwrContext* resampler = nullptr;
    AVAudioFifo* fifo = nullptr;
    AVPacket* packet = nullptr;
    AVFrame* frame = nullptr;
    int output_rate_hz = 0;
    int channels = 0;
    int64_t decoded_source_samples = 0;
    int64_t expected_source_samples = 0;
    bool input_eof = false;
    bool decoder_flush_sent = false;
    bool decoder_eof = false;
    bool resampler_flushed = false;

    ~Pcm16ReaderContext() {
        av_packet_free(&packet);
        av_frame_free(&frame);
        av_audio_fifo_free(fifo);
        fifo = nullptr;
        swr_free(&resampler);
    }
};

Pcm16ReaderContext* createPcm16Reader(
    AudioTranscodeSession* session,
    const char* input_path,
    int output_rate_hz,
    int channels,
    int* error_code
) {
    if (error_code) *error_code = kTranscodeInvalidArgument;
    if (!session || !input_path || !*input_path || output_rate_hz <= 0 || channels <= 0) {
        return nullptr;
    }
    if (session->cancelled()) {
        if (error_code) *error_code = kTranscodeCancelled;
        return nullptr;
    }

    auto* reader = new (std::nothrow) Pcm16ReaderContext();
    if (!reader) {
        if (error_code) *error_code = AVERROR(ENOMEM);
        return nullptr;
    }
    reader->session = session;
    reader->output_rate_hz = output_rate_hz;
    reader->channels = channels;

    int result = openAudioInput(input_path, reader->input);
    if (result < 0) {
        if (error_code) *error_code = result;
        delete reader;
        return nullptr;
    }
    if (reader->input.decoder->ch_layout.nb_channels != channels) {
        if (error_code) *error_code = kTranscodeUnsupportedSource;
        delete reader;
        return nullptr;
    }

    AVChannelLayout outputLayout{};
    result = av_channel_layout_copy(&outputLayout, &reader->input.decoder->ch_layout);
    if (result >= 0) {
        AVChannelLayout inputLayout{};
        result = av_channel_layout_copy(&inputLayout, &reader->input.decoder->ch_layout);
        if (result >= 0) {
            result = swr_alloc_set_opts2(
                &reader->resampler,
                &outputLayout,
                AV_SAMPLE_FMT_S16,
                output_rate_hz,
                &inputLayout,
                reader->input.decoder->sample_fmt,
                reader->input.decoder->sample_rate,
                0,
                nullptr
            );
        }
        av_channel_layout_uninit(&inputLayout);
    }
    av_channel_layout_uninit(&outputLayout);
    if (result < 0 || !reader->resampler) {
        if (error_code) *error_code = result < 0 ? result : AVERROR(ENOMEM);
        delete reader;
        return nullptr;
    }

    const AVStream* stream = reader->input.format->streams[reader->input.stream_index];
    const AVCodecParameters* parameters = stream->codecpar;
    if (parameters && isDsdCodecId(parameters->codec_id) &&
        output_rate_hz < reader->input.decoder->sample_rate) {
        // Same quality policy as the file encoder: dsd2pcm already low-passes, then swresample
        // performs the anti-aliasing decimation into the hardware encoder's PCM clock.
        av_opt_set_int(reader->resampler, "filter_size", 64, 0);
        av_opt_set_double(reader->resampler, "cutoff", 0.97, 0);
    }
    result = swr_init(reader->resampler);
    if (result < 0) {
        if (error_code) *error_code = result;
        delete reader;
        return nullptr;
    }

    reader->fifo = av_audio_fifo_alloc(AV_SAMPLE_FMT_S16, channels, 1);
    reader->packet = av_packet_alloc();
    reader->frame = av_frame_alloc();
    if (!reader->fifo || !reader->packet || !reader->frame) {
        if (error_code) *error_code = AVERROR(ENOMEM);
        delete reader;
        return nullptr;
    }

    const int64_t sourceDurationMs = durationMs(reader->input.format, stream);
    if (sourceDurationMs > 0 && reader->input.decoder->sample_rate > 0) {
        reader->expected_source_samples =
            sourceDurationMs * static_cast<int64_t>(reader->input.decoder->sample_rate) / 1000LL;
    }
    session->setProgressPermille(0);
    if (error_code) *error_code = kTranscodeOk;
    return reader;
}

namespace {

int pcm16ReaderAppendConverted(Pcm16ReaderContext* reader, AVFrame* frame) {
    if (!reader || !frame || !reader->resampler || !reader->fifo) return kTranscodeInvalidArgument;
    const int sourceRate = reader->input.decoder->sample_rate;
    const int capacity = static_cast<int>(av_rescale_rnd(
        swr_get_delay(reader->resampler, sourceRate) + frame->nb_samples,
        reader->output_rate_hz,
        sourceRate,
        AV_ROUND_UP
    ));
    if (capacity <= 0) return 0;

    uint8_t** converted = nullptr;
    int lineSize = 0;
    int result = av_samples_alloc_array_and_samples(
        &converted,
        &lineSize,
        reader->channels,
        capacity,
        AV_SAMPLE_FMT_S16,
        0
    );
    if (result < 0) return result;
    const int convertedFrames = swr_convert(
        reader->resampler,
        converted,
        capacity,
        const_cast<const uint8_t**>(frame->extended_data),
        frame->nb_samples
    );
    if (convertedFrames < 0) {
        result = convertedFrames;
    } else if (convertedFrames > 0) {
        result = av_audio_fifo_realloc(
            reader->fifo,
            av_audio_fifo_size(reader->fifo) + convertedFrames
        );
        if (result >= 0) {
            const int written = av_audio_fifo_write(
                reader->fifo,
                reinterpret_cast<void**>(converted),
                convertedFrames
            );
            if (written != convertedFrames) result = AVERROR(EIO);
        }
    }
    if (converted) {
        av_freep(&converted[0]);
        av_freep(&converted);
    }

    reader->decoded_source_samples += frame->nb_samples;
    if (reader->expected_source_samples > 0) {
        reader->session->setProgressPermille(static_cast<int>(std::clamp<int64_t>(
            reader->decoded_source_samples * 990LL / reader->expected_source_samples,
            0LL,
            990LL
        )));
    }
    return result;
}

int pcm16ReaderFlushResampler(Pcm16ReaderContext* reader) {
    if (!reader || reader->resampler_flushed) return 0;
    const int sourceRate = reader->input.decoder->sample_rate;
    while (true) {
        if (reader->session->cancelled()) return kTranscodeCancelled;
        const int64_t delay = swr_get_delay(reader->resampler, sourceRate);
        if (delay <= 0) break;
        const int capacity = static_cast<int>(av_rescale_rnd(
            delay,
            reader->output_rate_hz,
            sourceRate,
            AV_ROUND_UP
        ));
        if (capacity <= 0) break;
        uint8_t** converted = nullptr;
        int lineSize = 0;
        int result = av_samples_alloc_array_and_samples(
            &converted,
            &lineSize,
            reader->channels,
            capacity,
            AV_SAMPLE_FMT_S16,
            0
        );
        if (result < 0) return result;
        const int convertedFrames = swr_convert(reader->resampler, converted, capacity, nullptr, 0);
        if (convertedFrames < 0) {
            result = convertedFrames;
        } else if (convertedFrames > 0) {
            result = av_audio_fifo_realloc(
                reader->fifo,
                av_audio_fifo_size(reader->fifo) + convertedFrames
            );
            if (result >= 0) {
                const int written = av_audio_fifo_write(
                    reader->fifo,
                    reinterpret_cast<void**>(converted),
                    convertedFrames
                );
                if (written != convertedFrames) result = AVERROR(EIO);
            }
        }
        if (converted) {
            av_freep(&converted[0]);
            av_freep(&converted);
        }
        if (result < 0 || convertedFrames <= 0) {
            if (result < 0) return result;
            break;
        }
    }
    reader->resampler_flushed = true;
    return 0;
}

int pcm16ReaderPump(Pcm16ReaderContext* reader) {
    if (!reader) return kTranscodeInvalidArgument;
    while (av_audio_fifo_size(reader->fifo) <= 0) {
        if (reader->session->cancelled()) return kTranscodeCancelled;

        int receive = avcodec_receive_frame(reader->input.decoder, reader->frame);
        if (receive >= 0) {
            const int convert = pcm16ReaderAppendConverted(reader, reader->frame);
            av_frame_unref(reader->frame);
            if (convert < 0) return convert;
            continue;
        }
        if (receive == AVERROR_EOF) {
            reader->decoder_eof = true;
        } else if (isRecoverableAudioDecodeError(receive)) {
            logRecoverableAudioDecodeError("PCM16 reader decoder receive", receive);
            av_frame_unref(reader->frame);
        } else if (receive != AVERROR(EAGAIN)) {
            return receive;
        }

        if (reader->decoder_eof) {
            const int flush = pcm16ReaderFlushResampler(reader);
            if (flush < 0) return flush;
            return av_audio_fifo_size(reader->fifo) > 0 ? 1 : 0;
        }

        if (!reader->input_eof) {
            int readResult = 0;
            do {
                readResult = av_read_frame(reader->input.format, reader->packet);
                if (readResult < 0) break;
                if (reader->packet->stream_index != reader->input.stream_index) {
                    av_packet_unref(reader->packet);
                    continue;
                }
                const int send = avcodec_send_packet(reader->input.decoder, reader->packet);
                av_packet_unref(reader->packet);
                if (send < 0 && send != AVERROR(EAGAIN)) {
                    if (isRecoverableAudioDecodeError(send)) {
                        logRecoverableAudioDecodeError("PCM16 reader decoder send", send);
                        continue;
                    }
                    return send;
                }
                break;
            } while (true);
            if (readResult == AVERROR_EOF) reader->input_eof = true;
            else if (readResult < 0) return readResult;
            continue;
        }

        if (!reader->decoder_flush_sent) {
            const int send = avcodec_send_packet(reader->input.decoder, nullptr);
            if (send < 0 && send != AVERROR_EOF) return send;
            reader->decoder_flush_sent = true;
            continue;
        }

        // A flushed decoder that still returns EAGAIN cannot produce more input-dependent data.
        reader->decoder_eof = true;
    }
    return 1;
}

}  // namespace

int readPcm16Reader(Pcm16ReaderContext* reader, int16_t* output, int max_frames) {
    if (!reader || !output || max_frames <= 0) return kTranscodeInvalidArgument;
    if (reader->session->cancelled()) return kTranscodeCancelled;

    if (av_audio_fifo_size(reader->fifo) <= 0) {
        const int pump = pcm16ReaderPump(reader);
        if (pump < 0) return pump;
        if (pump == 0 && av_audio_fifo_size(reader->fifo) <= 0) {
            reader->session->setProgressPermille(1000);
            return 0;
        }
    }

    const int frames = std::min(max_frames, av_audio_fifo_size(reader->fifo));
    void* planes[1] = {output};
    const int read = av_audio_fifo_read(reader->fifo, planes, frames);
    if (read < 0) return read;
    if (read != frames) return AVERROR(EIO);
    return frames;
}

void destroyPcm16Reader(Pcm16ReaderContext* reader) {
    delete reader;
}

int transcodePcmTarget(
    AudioTranscodeSession* session,
    const char* input_path,
    const char* output_path,
    const char* target_id,
    int requested_sample_rate_hz,
    int requested_bit_depth,
    int bit_rate_kbps,
    int flac_compression_level,
    bool enable_auto_dither
) {
    if (!session || !input_path || !*input_path || !output_path || !*output_path) {
        return kTranscodeInvalidArgument;
    }
    const PcmTargetConfig* target = findTargetConfig(target_id);
    if (!target) return kTranscodeUnsupportedTarget;
    session->setProgressPermille(0);
    session->clearLastErrorDetail();
    if (session->cancelled()) return kTranscodeCancelled;

    OpenAudioInput input;
    int result = openAudioInput(input_path, input);
    if (result < 0) return result;

    AVStream* inputStream = input.format->streams[input.stream_index];
    AVCodecParameters* inputParameters = inputStream->codecpar;
    const int sourceRate = input.decoder->sample_rate;
    const bool sourceIsDsd = isDsdCodecId(inputParameters->codec_id);
    // The DSD decoder yields floating-point PCM after dsd2pcm filtering. Treat that decoded
    // domain as 32-bit precision for quantization/dither policy; the container's one-bit source
    // depth is reported separately by probeAudioFile().
    const int sourceBits = sourceIsDsd ? 32 : normalizedBitDepth(inputParameters, input.decoder);
    const int targetRate = resolveTargetSampleRate(sourceRate, requested_sample_rate_hz);
    const int targetBits = target->lossless
        ? resolveTargetBitDepth(sourceBits, requested_bit_depth)
        : 0;
    const int targetBitRateKbps = target->lossless ? 0 : bit_rate_kbps;
    if (targetRate <= 0 || sourceBits <= 0 ||
        (target->lossless && targetBits <= 0) ||
        (!target->lossless && targetBitRateKbps <= 0)) {
        LOGE(
            "unsupported source/target rate=%d->%d bits=%d->%d bitrate=%d input=%s",
            sourceRate,
            targetRate,
            sourceBits,
            targetBits,
            targetBitRateKbps,
            input_path
        );
        return kTranscodeUnsupportedSource;
    }

    AVCodecID targetCodecId = AV_CODEC_ID_NONE;
    if (!pcmProfileCanOpen(
            *target,
            targetRate,
            targetBits,
            targetBitRateKbps,
            &targetCodecId)) {
        return kTranscodeUnsupportedTarget;
    }
    const AVCodec* encoderCodec = avcodec_find_encoder(targetCodecId);
    if (!encoderCodec) return kTranscodeUnsupportedTarget;
    const AVSampleFormat targetSampleFormat = target->lossless
        ? selectIntegerSampleFormat(encoderCodec, targetBits)
        : selectLossySampleFormat(encoderCodec);
    if (targetSampleFormat == AV_SAMPLE_FMT_NONE) return kTranscodeUnsupportedTarget;

    AVFormatContext* output = nullptr;
    AVCodecContext* encoder = nullptr;
    SwrContext* resampler = nullptr;
    AVAudioFifo* fifo = nullptr;
    AVPacket* inputPacket = nullptr;
    AVPacket* outputPacket = nullptr;
    AVFrame* decodedFrame = nullptr;
    AVStream* outputStream = nullptr;
    bool outputOpened = false;
    bool headerWritten = false;
    int64_t nextPts = 0;
    int64_t decodedSourceSamples = 0;
    const int64_t sourceDurationMs = durationMs(input.format, inputStream);
    const int64_t expectedSourceSamples = sourceDurationMs > 0
        ? (sourceDurationMs * static_cast<int64_t>(sourceRate)) / 1000LL
        : 0LL;
    const int compression = std::clamp(flac_compression_level, 0, 12);
    const bool autoDither = target->lossless && enable_auto_dither &&
        shouldAutoDither(sourceRate, targetRate, sourceBits, targetBits);
    std::vector<uint32_t> ditherRng;

    auto cleanup = [&](bool finalizeTrailer) {
        if (finalizeTrailer && headerWritten && output) {
            av_write_trailer(output);
        }
        headerWritten = false;
        if (outputOpened && output && !(output->oformat->flags & AVFMT_NOFILE)) {
            avio_closep(&output->pb);
        }
        outputOpened = false;
        av_packet_free(&inputPacket);
        av_packet_free(&outputPacket);
        av_frame_free(&decodedFrame);
        av_audio_fifo_free(fifo);
        fifo = nullptr;
        swr_free(&resampler);
        avcodec_free_context(&encoder);
        if (output) avformat_free_context(output);
        output = nullptr;
    };

    auto fail = [&](const char* stage, int code) -> int {
        session->setLastErrorDetail(
            std::string(stage) + ": " + ffmpegErrorText(code) + " (" + std::to_string(code) + ")"
        );
        LOGE(
            "%s code=%d reason=%s input=%s output=%s",
            stage,
            code,
            ffmpegErrorText(code).c_str(),
            input_path,
            output_path
        );
        cleanup(false);
        return code < 0 ? code : kTranscodeIoError;
    };

    auto cancelled = [&]() {
        return session->cancelled();
    };

    result = avformat_alloc_output_context2(&output, nullptr, target->muxer_name, output_path);
    if (result < 0 || !output) {
        return fail("output context", result < 0 ? result : AVERROR(ENOMEM));
    }

    encoder = avcodec_alloc_context3(encoderCodec);
    if (!encoder) return fail("encoder allocation", AVERROR(ENOMEM));
    encoder->sample_rate = targetRate;
    encoder->sample_fmt = targetSampleFormat;
    encoder->time_base = AVRational{1, targetRate};
    if (target->lossless) {
        encoder->bits_per_raw_sample = targetBits;
    } else {
        encoder->bit_rate = static_cast<int64_t>(targetBitRateKbps) * 1000LL;
        encoder->strict_std_compliance = FF_COMPLIANCE_EXPERIMENTAL;
    }
    result = av_channel_layout_copy(&encoder->ch_layout, &input.decoder->ch_layout);
    if (result < 0) return fail("channel layout", result);
    if (target->flac_compression) {
        encoder->compression_level = compression;
        if (encoder->priv_data) {
            av_opt_set_int(encoder->priv_data, "compression_level", compression, 0);
        }
    }
    if (output->oformat->flags & AVFMT_GLOBALHEADER) {
        encoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
    }
    result = avcodec_open2(encoder, encoderCodec, nullptr);
    if (result < 0) return fail("encoder open", result);

    outputStream = avformat_new_stream(output, nullptr);
    if (!outputStream) return fail("output stream", AVERROR(ENOMEM));
    outputStream->time_base = encoder->time_base;
    result = avcodec_parameters_from_context(outputStream->codecpar, encoder);
    if (result < 0) return fail("output parameters", result);
    if (target->lossless) outputStream->codecpar->bits_per_raw_sample = targetBits;

    if (!(output->oformat->flags & AVFMT_NOFILE)) {
        result = avio_open(&output->pb, output_path, AVIO_FLAG_WRITE);
        if (result < 0) return fail("output open", result);
        outputOpened = true;
    }
    result = avformat_write_header(output, nullptr);
    if (result < 0) return fail("output header", result);
    headerWritten = true;

    AVChannelLayout inputLayout{};
    result = av_channel_layout_copy(&inputLayout, &input.decoder->ch_layout);
    if (result < 0) return fail("input channel layout", result);
    result = swr_alloc_set_opts2(
        &resampler,
        &encoder->ch_layout,
        encoder->sample_fmt,
        encoder->sample_rate,
        &inputLayout,
        input.decoder->sample_fmt,
        input.decoder->sample_rate,
        0,
        nullptr
    );
    av_channel_layout_uninit(&inputLayout);
    if (result < 0 || !resampler) return fail("resampler allocation", result < 0 ? result : AVERROR(ENOMEM));

    if (sourceIsDsd && targetRate < sourceRate) {
        // FFmpeg's DSD decoder already performs its 96-tap dsd2pcm low-pass. The second
        // stage here is a high-quality anti-aliasing decimator into the requested PCM rate;
        // do not stack an unrelated custom DSD FIR on top of the decoder.
        const int filterResult = av_opt_set_int(resampler, "filter_size", 64, 0);
        const int cutoffResult = av_opt_set_double(resampler, "cutoff", 0.97, 0);
        if (filterResult < 0 || cutoffResult < 0) {
            LOGW("DSD resampler quality options unavailable filter=%d cutoff=%d", filterResult, cutoffResult);
        }
    }
    if (autoDither && targetBits == 16) {
        av_opt_set_int(resampler, "dither_method", SWR_DITHER_TRIANGULAR_HIGHPASS, 0);
    }
    result = swr_init(resampler);
    if (result < 0) return fail("resampler init", result);

    fifo = av_audio_fifo_alloc(encoder->sample_fmt, encoder->ch_layout.nb_channels, 1);
    inputPacket = av_packet_alloc();
    outputPacket = av_packet_alloc();
    decodedFrame = av_frame_alloc();
    if (!fifo || !inputPacket || !outputPacket || !decodedFrame) {
        return fail("buffer allocation", AVERROR(ENOMEM));
    }

    auto updateProgress = [&]() {
        if (expectedSourceSamples <= 0) return;
        const int progress = static_cast<int>(std::clamp<int64_t>(
            decodedSourceSamples * 990LL / expectedSourceSamples,
            0LL,
            990LL
        ));
        session->setProgressPermille(progress);
    };

    auto writeEncodedPackets = [&]() -> int {
        while (true) {
            if (cancelled()) return kTranscodeCancelled;
            int receive = avcodec_receive_packet(encoder, outputPacket);
            if (receive == AVERROR(EAGAIN) || receive == AVERROR_EOF) return 0;
            if (receive < 0) return receive;
            av_packet_rescale_ts(outputPacket, encoder->time_base, outputStream->time_base);
            outputPacket->stream_index = outputStream->index;
            receive = av_interleaved_write_frame(output, outputPacket);
            av_packet_unref(outputPacket);
            if (receive < 0) return receive;
        }
    };

    auto encodeAvailable = [&](bool flushAll) -> int {
        const int nominalFrameSize = encoder->frame_size;
        while (true) {
            if (cancelled()) return kTranscodeCancelled;
            const int available = av_audio_fifo_size(fifo);
            if (available <= 0) return 0;
            if (!flushAll && nominalFrameSize > 0 && available < nominalFrameSize) return 0;

            const int samples = nominalFrameSize > 0
                ? std::min(available, nominalFrameSize)
                : available;
            AVFrame* frame = av_frame_alloc();
            if (!frame) return AVERROR(ENOMEM);
            frame->nb_samples = samples;
            frame->format = encoder->sample_fmt;
            frame->sample_rate = encoder->sample_rate;
            int localResult = av_channel_layout_copy(&frame->ch_layout, &encoder->ch_layout);
            if (localResult >= 0) localResult = av_frame_get_buffer(frame, 0);
            if (localResult >= 0) {
                const int readSamples = av_audio_fifo_read(
                    fifo,
                    reinterpret_cast<void**>(frame->data),
                    samples
                );
                if (readSamples != samples) localResult = AVERROR(EIO);
            }
            if (localResult >= 0) {
                frame->pts = nextPts;
                nextPts += samples;
                localResult = avcodec_send_frame(encoder, frame);
            }
            av_frame_free(&frame);
            if (localResult < 0) return localResult;
            localResult = writeEncodedPackets();
            if (localResult < 0) return localResult;
        }
    };

    auto appendConverted = [&](uint8_t** converted, int convertedSamples) -> int {
        if (convertedSamples <= 0) return 0;
        if (autoDither && targetBits == 24 &&
            (encoder->sample_fmt == AV_SAMPLE_FMT_S32 || encoder->sample_fmt == AV_SAMPLE_FMT_S32P)) {
            applyTpdfS32To24(
                converted,
                convertedSamples,
                encoder->ch_layout.nb_channels,
                encoder->sample_fmt,
                ditherRng
            );
        }
        int localResult = av_audio_fifo_realloc(fifo, av_audio_fifo_size(fifo) + convertedSamples);
        if (localResult < 0) return localResult;
        const int written = av_audio_fifo_write(
            fifo,
            reinterpret_cast<void**>(converted),
            convertedSamples
        );
        if (written != convertedSamples) return AVERROR(EIO);
        return encodeAvailable(false);
    };

    auto convertDecodedFrame = [&]() -> int {
        if (cancelled()) return kTranscodeCancelled;
        const int capacity = static_cast<int>(av_rescale_rnd(
            swr_get_delay(resampler, input.decoder->sample_rate) + decodedFrame->nb_samples,
            encoder->sample_rate,
            input.decoder->sample_rate,
            AV_ROUND_UP
        ));
        if (capacity <= 0) return 0;

        uint8_t** converted = nullptr;
        int lineSize = 0;
        int localResult = av_samples_alloc_array_and_samples(
            &converted,
            &lineSize,
            encoder->ch_layout.nb_channels,
            capacity,
            encoder->sample_fmt,
            0
        );
        if (localResult < 0) return localResult;
        const int convertedSamples = swr_convert(
            resampler,
            converted,
            capacity,
            const_cast<const uint8_t**>(decodedFrame->extended_data),
            decodedFrame->nb_samples
        );
        if (convertedSamples < 0) {
            localResult = convertedSamples;
        } else {
            localResult = appendConverted(converted, convertedSamples);
        }
        if (converted) {
            av_freep(&converted[0]);
            av_freep(&converted);
        }
        decodedSourceSamples += decodedFrame->nb_samples;
        updateProgress();
        return localResult;
    };

    auto flushResampler = [&]() -> int {
        while (true) {
            if (cancelled()) return kTranscodeCancelled;
            const int64_t delay = swr_get_delay(resampler, input.decoder->sample_rate);
            if (delay <= 0) return 0;
            const int capacity = static_cast<int>(av_rescale_rnd(
                delay,
                encoder->sample_rate,
                input.decoder->sample_rate,
                AV_ROUND_UP
            ));
            if (capacity <= 0) return 0;
            uint8_t** converted = nullptr;
            int lineSize = 0;
            int localResult = av_samples_alloc_array_and_samples(
                &converted,
                &lineSize,
                encoder->ch_layout.nb_channels,
                capacity,
                encoder->sample_fmt,
                0
            );
            if (localResult < 0) return localResult;
            const int convertedSamples = swr_convert(resampler, converted, capacity, nullptr, 0);
            if (convertedSamples < 0) localResult = convertedSamples;
            else if (convertedSamples == 0) localResult = 0;
            else localResult = appendConverted(converted, convertedSamples);
            if (converted) {
                av_freep(&converted[0]);
                av_freep(&converted);
            }
            if (localResult < 0 || convertedSamples <= 0) return localResult;
        }
    };

    int readResult = 0;
    while ((readResult = av_read_frame(input.format, inputPacket)) >= 0) {
        if (cancelled()) {
            cleanup(false);
            return kTranscodeCancelled;
        }
        if (inputPacket->stream_index != input.stream_index) {
            av_packet_unref(inputPacket);
            continue;
        }
        result = avcodec_send_packet(input.decoder, inputPacket);
        av_packet_unref(inputPacket);
        if (result < 0) {
            if (isRecoverableAudioDecodeError(result)) {
                logRecoverableAudioDecodeError("decoder send", result);
                continue;
            }
            return fail("decoder send", result);
        }

        while ((result = avcodec_receive_frame(input.decoder, decodedFrame)) >= 0) {
            const int convertResult = convertDecodedFrame();
            av_frame_unref(decodedFrame);
            if (convertResult == kTranscodeCancelled) {
                cleanup(false);
                return kTranscodeCancelled;
            }
            if (convertResult < 0) return fail("audio conversion", convertResult);
        }
        if (isRecoverableAudioDecodeError(result)) {
            logRecoverableAudioDecodeError("decoder receive", result);
            av_frame_unref(decodedFrame);
            continue;
        }
        if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
            return fail("decoder receive", result);
        }
    }
    if (readResult != AVERROR_EOF) return fail("input read", readResult);

    result = avcodec_send_packet(input.decoder, nullptr);
    if (result < 0 && result != AVERROR_EOF) return fail("decoder flush send", result);
    while ((result = avcodec_receive_frame(input.decoder, decodedFrame)) >= 0) {
        const int convertResult = convertDecodedFrame();
        av_frame_unref(decodedFrame);
        if (convertResult == kTranscodeCancelled) {
            cleanup(false);
            return kTranscodeCancelled;
        }
        if (convertResult < 0) return fail("decoder flush conversion", convertResult);
    }
    if (isRecoverableAudioDecodeError(result)) {
        logRecoverableAudioDecodeError("decoder flush receive", result);
        av_frame_unref(decodedFrame);
    } else if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
        return fail("decoder flush receive", result);
    }

    result = flushResampler();
    if (result == kTranscodeCancelled) {
        cleanup(false);
        return kTranscodeCancelled;
    }
    if (result < 0) return fail("resampler flush", result);

    result = encodeAvailable(true);
    if (result == kTranscodeCancelled) {
        cleanup(false);
        return kTranscodeCancelled;
    }
    if (result < 0) return fail("encoder fifo flush", result);

    result = avcodec_send_frame(encoder, nullptr);
    if (result < 0 && result != AVERROR_EOF) return fail("encoder flush send", result);
    result = writeEncodedPackets();
    if (result == kTranscodeCancelled) {
        cleanup(false);
        return kTranscodeCancelled;
    }
    if (result < 0) return fail("encoder packet flush", result);

    result = av_write_trailer(output);
    if (result < 0) return fail("output trailer", result);
    headerWritten = false;
    session->setProgressPermille(1000);

    LOGI(
        "complete target=%s rate=%d bits=%d bitrateKbps=%d channels=%d compression=%d dither=%d input=%s output=%s",
        target->id,
        targetRate,
        targetBits,
        targetBitRateKbps,
        encoder->ch_layout.nb_channels,
        compression,
        autoDither ? 1 : 0,
        input_path,
        output_path
    );
    cleanup(false);
    return kTranscodeOk;
}

int transcodeLosslessPcm(
    AudioTranscodeSession* session,
    const char* input_path,
    const char* output_path,
    const char* target_id,
    int requested_sample_rate_hz,
    int requested_bit_depth,
    int flac_compression_level,
    bool enable_auto_dither
) {
    const PcmTargetConfig* target = findTargetConfig(target_id);
    if (!target || !target->lossless) return kTranscodeUnsupportedTarget;
    return transcodePcmTarget(
        session,
        input_path,
        output_path,
        target_id,
        requested_sample_rate_hz,
        requested_bit_depth,
        0,
        flac_compression_level,
        enable_auto_dither
    );
}

int transcodeLossyPcm(
    AudioTranscodeSession* session,
    const char* input_path,
    const char* output_path,
    const char* target_id,
    int requested_sample_rate_hz,
    int bit_rate_kbps
) {
    const PcmTargetConfig* target = findTargetConfig(target_id);
    if (!target || target->lossless) return kTranscodeUnsupportedTarget;
    return transcodePcmTarget(
        session,
        input_path,
        output_path,
        target_id,
        requested_sample_rate_hz,
        0,
        bit_rate_kbps,
        0,
        false
    );
}

int remuxDsdToDsf(
    AudioTranscodeSession* session,
    const char* input_path,
    const char* output_path,
    int dsd_multiplier
) {
    if (!session || !input_path || !*input_path || !output_path || !*output_path ||
        !isSelectableDsdMultiplier(dsd_multiplier)) {
        return kTranscodeInvalidArgument;
    }
    session->setProgressPermille(0);
    if (session->cancelled()) return kTranscodeCancelled;

    AVFormatContext* format = nullptr;
    AVPacket* packet = nullptr;
    DsfWriter writer;

    auto cleanup = [&]() {
        av_packet_free(&packet);
        if (format) avformat_close_input(&format);
        writer.close();
    };
    auto fail = [&](const char* stage, int code) -> int {
        LOGE(
            "DSD direct remux %s code=%d reason=%s input=%s output=%s",
            stage,
            code,
            ffmpegErrorText(code).c_str(),
            input_path,
            output_path
        );
        cleanup();
        return code < 0 ? code : kTranscodeIoError;
    };

    int result = avformat_open_input(&format, input_path, nullptr, nullptr);
    if (result < 0 || !format) return fail("input open", result < 0 ? result : AVERROR(EIO));
    result = avformat_find_stream_info(format, nullptr);
    if (result < 0) return fail("stream info", result);

    // DSF already has the target container's 4096-byte channel-block padding. Re-reading those
    // padded packets as logical DSD bytes would extend the stream, so same-container DSF->DSF is
    // deliberately not handled here. The public manager routes only DFF/DSDIFF sources here.
    const char* inputFormatName = format->iformat ? format->iformat->name : nullptr;
    if (inputFormatName && std::strcmp(inputFormatName, "dsf") == 0) {
        cleanup();
        return kTranscodeUnsupportedSource;
    }

    int streamIndex = -1;
    for (unsigned int index = 0; index < format->nb_streams; ++index) {
        const AVStream* candidate = format->streams[index];
        if (candidate && candidate->codecpar &&
            candidate->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) {
            streamIndex = static_cast<int>(index);
            break;
        }
    }
    if (streamIndex < 0) return fail("audio stream", AVERROR_STREAM_NOT_FOUND);

    AVStream* stream = format->streams[streamIndex];
    AVCodecParameters* parameters = stream->codecpar;
    if (!parameters || !isDsdCodecId(parameters->codec_id)) {
        cleanup();
        return kTranscodeUnsupportedSource;
    }
    const int channels = parameters->ch_layout.nb_channels;
    const int dsdRateHz = logicalDsdRateHz(parameters);
    if (channels <= 0 || channels > 2 || dsdRateHz <= 0 ||
        !dsdRateMatchesMultiplier(dsdRateHz, dsd_multiplier)) {
        LOGE(
            "DSD direct remux unsupported rate=%d channels=%d multiplier=%d codec=%d input=%s",
            dsdRateHz,
            channels,
            dsd_multiplier,
            static_cast<int>(parameters->codec_id),
            input_path
        );
        cleanup();
        return kTranscodeUnsupportedSource;
    }
    if (!writer.open(output_path, dsdRateHz, channels)) {
        cleanup();
        return kTranscodeIoError;
    }

    packet = av_packet_alloc();
    if (!packet) return fail("packet allocation", AVERROR(ENOMEM));

    const bool planar = isPlanarDsdCodecId(parameters->codec_id);
    const bool reverseBits = isLsbfDsdCodecId(parameters->codec_id);
    const int64_t sourceDurationMs = durationMs(format, stream);
    const int64_t expectedBytesPerChannel = sourceDurationMs > 0
        ? (sourceDurationMs * static_cast<int64_t>(dsdRateHz)) / 8000LL
        : 0LL;
    uint64_t logicalBytesPerChannel = 0u;
    std::vector<uint8_t> channel0;
    std::vector<uint8_t> channel1;

    auto updateProgress = [&]() {
        if (expectedBytesPerChannel <= 0) return;
        const int progress = static_cast<int>(std::clamp<int64_t>(
            static_cast<int64_t>(std::min<uint64_t>(
                logicalBytesPerChannel,
                static_cast<uint64_t>(std::numeric_limits<int64_t>::max())
            )) * 995LL / expectedBytesPerChannel,
            0LL,
            995LL
        ));
        session->setProgressPermille(progress);
    };

    int readResult = 0;
    while ((readResult = av_read_frame(format, packet)) >= 0) {
        if (session->cancelled()) {
            cleanup();
            return kTranscodeCancelled;
        }
        if (packet->stream_index != streamIndex) {
            av_packet_unref(packet);
            continue;
        }
        if (!packet->data || packet->size <= 0 || packet->size % channels != 0) {
            av_packet_unref(packet);
            return fail("packet geometry", AVERROR_INVALIDDATA);
        }

        const int bytesPerChannel = packet->size / channels;
        channel0.resize(static_cast<size_t>(bytesPerChannel));
        if (channels == 2) channel1.resize(static_cast<size_t>(bytesPerChannel));

        if (planar) {
            for (int index = 0; index < bytesPerChannel; ++index) {
                uint8_t value0 = packet->data[index];
                channel0[static_cast<size_t>(index)] = reverseBits ? reverseDsdBits(value0) : value0;
                if (channels == 2) {
                    uint8_t value1 = packet->data[bytesPerChannel + index];
                    channel1[static_cast<size_t>(index)] = reverseBits ? reverseDsdBits(value1) : value1;
                }
            }
        } else {
            for (int index = 0; index < bytesPerChannel; ++index) {
                const size_t base = static_cast<size_t>(index) * static_cast<size_t>(channels);
                uint8_t value0 = packet->data[base];
                channel0[static_cast<size_t>(index)] = reverseBits ? reverseDsdBits(value0) : value0;
                if (channels == 2) {
                    uint8_t value1 = packet->data[base + 1u];
                    channel1[static_cast<size_t>(index)] = reverseBits ? reverseDsdBits(value1) : value1;
                }
            }
        }

        const bool wrote = writer.writeChannelData(
            channel0.data(),
            channels == 2 ? channel1.data() : nullptr,
            static_cast<uint32_t>(bytesPerChannel)
        );
        av_packet_unref(packet);
        if (!wrote) return fail("DSF write", AVERROR(EIO));
        logicalBytesPerChannel += static_cast<uint64_t>(bytesPerChannel);
        updateProgress();
    }
    if (readResult != AVERROR_EOF) return fail("input read", readResult);
    if (logicalBytesPerChannel == 0u ||
        logicalBytesPerChannel > std::numeric_limits<uint64_t>::max() / 8u) {
        cleanup();
        return kTranscodeUnsupportedSource;
    }
    if (!writer.finalize()) return fail("DSF finalize", AVERROR(EIO));

    DsfProbeResult verify{};
    if (!probeDsfFile(output_path, verify) ||
        verify.sample_rate_hz != dsdRateHz ||
        verify.channels != channels ||
        verify.sample_count != logicalBytesPerChannel * 8u) {
        LOGE(
            "DSD direct remux verify failed rate=%d/%d ch=%d/%d samples=%llu/%llu",
            verify.sample_rate_hz,
            dsdRateHz,
            verify.channels,
            channels,
            static_cast<unsigned long long>(verify.sample_count),
            static_cast<unsigned long long>(logicalBytesPerChannel * 8u)
        );
        cleanup();
        return kTranscodeIoError;
    }

    session->setProgressPermille(1000);
    LOGI(
        "DSD direct remux complete rate=%d channels=%d bytesPerChannel=%llu input=%s output=%s",
        dsdRateHz,
        channels,
        static_cast<unsigned long long>(logicalBytesPerChannel),
        input_path,
        output_path
    );
    cleanup();
    return kTranscodeOk;
}

int transcodePcmToDsf(
    AudioTranscodeSession* session,
    const char* input_path,
    const char* output_path,
    int dsd_multiplier
) {
    if (!session || !input_path || !*input_path || !output_path || !*output_path ||
        !isSelectableDsdMultiplier(dsd_multiplier)) {
        return kTranscodeInvalidArgument;
    }
    session->setProgressPermille(0);
    if (session->cancelled()) return kTranscodeCancelled;

    OpenAudioInput input;
    int result = openAudioInput(input_path, input);
    if (result < 0) return result;

    AVStream* inputStream = input.format->streams[input.stream_index];
    AVCodecParameters* inputParameters = inputStream->codecpar;
    const int sourceRate = input.decoder->sample_rate;
    const int sourceBits = normalizedBitDepth(inputParameters, input.decoder);
    const int sourceChannels = input.decoder->ch_layout.nb_channels;
    if (isDsdCodecId(inputParameters->codec_id)) {
        LOGE("DSD->DSF remodulation is intentionally disabled input=%s", input_path);
        return kTranscodeUnsupportedSource;
    }
    const int targetDsdRate = resolveDsdSampleRate(sourceRate, dsd_multiplier);
    if (!isSelectablePcmSampleRate(sourceRate) ||
        !isSelectablePcmBitDepth(sourceBits) ||
        targetDsdRate <= 0 ||
        sourceChannels <= 0 || sourceChannels > 2 ||
        !isStandardMonoStereoLayout(input.decoder->ch_layout)) {
        LOGE(
            "unsupported PCM->DSF source rate=%d bits=%d channels=%d multiplier=%d input=%s",
            sourceRate,
            sourceBits,
            sourceChannels,
            dsd_multiplier,
            input_path
        );
        return kTranscodeUnsupportedSource;
    }

    OfflineDsdEncoder dsdEncoder;
    if (!dsdEncoder.init(sourceRate, sourceChannels, dsd_multiplier) ||
        static_cast<int>(dsdEncoder.dsdSampleRateHz()) != targetDsdRate) {
        return kTranscodeUnsupportedTarget;
    }

    DsfWriter writer;
    if (!writer.open(output_path, targetDsdRate, sourceChannels)) {
        return kTranscodeIoError;
    }

    SwrContext* resampler = nullptr;
    AVPacket* inputPacket = nullptr;
    AVFrame* decodedFrame = nullptr;
    const int64_t sourceDurationMs = durationMs(input.format, inputStream);
    const int64_t expectedSourceSamples = sourceDurationMs > 0
        ? (sourceDurationMs * static_cast<int64_t>(sourceRate)) / 1000LL
        : 0LL;
    int64_t decodedSourceSamples = 0;

    auto cleanup = [&]() {
        av_packet_free(&inputPacket);
        av_frame_free(&decodedFrame);
        swr_free(&resampler);
        writer.close();
    };

    auto fail = [&](const char* stage, int code) -> int {
        LOGE(
            "%s code=%d reason=%s input=%s output=%s",
            stage,
            code,
            ffmpegErrorText(code).c_str(),
            input_path,
            output_path
        );
        cleanup();
        return code < 0 ? code : kTranscodeIoError;
    };

    AVChannelLayout outputLayout{};
    result = av_channel_layout_copy(&outputLayout, &input.decoder->ch_layout);
    if (result < 0) return fail("DSF channel layout", result);
    result = swr_alloc_set_opts2(
        &resampler,
        &outputLayout,
        AV_SAMPLE_FMT_S32,
        sourceRate,
        &input.decoder->ch_layout,
        input.decoder->sample_fmt,
        sourceRate,
        0,
        nullptr
    );
    av_channel_layout_uninit(&outputLayout);
    if (result < 0 || !resampler) {
        return fail("DSF converter allocation", result < 0 ? result : AVERROR(ENOMEM));
    }
    result = swr_init(resampler);
    if (result < 0) return fail("DSF converter init", result);

    inputPacket = av_packet_alloc();
    decodedFrame = av_frame_alloc();
    if (!inputPacket || !decodedFrame) return fail("DSF buffer allocation", AVERROR(ENOMEM));

    auto updateProgress = [&]() {
        if (expectedSourceSamples <= 0) return;
        const int progress = static_cast<int>(std::clamp<int64_t>(
            decodedSourceSamples * 995LL / expectedSourceSamples,
            0LL,
            995LL
        ));
        session->setProgressPermille(progress);
    };

    auto processInterleavedS32 = [&](const int32_t* pcm, int samples) -> int {
        if (!pcm || samples <= 0) return 0;
        constexpr uint32_t kPcmFramesPerDsdTransaction = 1024u;
        int offset = 0;
        std::array<std::vector<uint8_t>, 2> dsdOutput;
        while (offset < samples) {
            if (session->cancelled()) return kTranscodeCancelled;
            const uint32_t frames = static_cast<uint32_t>(std::min<int>(
                samples - offset,
                static_cast<int>(kPcmFramesPerDsdTransaction)
            ));
            const int32_t* chunk = pcm +
                static_cast<size_t>(offset) * static_cast<size_t>(sourceChannels);
            if (!dsdEncoder.convertInterleavedS32(chunk, frames, dsdOutput)) {
                return kTranscodeIoError;
            }
            const auto bytesPerChannel = dsdOutput[0].size();
            if (bytesPerChannel == 0u ||
                bytesPerChannel > static_cast<size_t>(std::numeric_limits<uint32_t>::max()) ||
                (sourceChannels == 2 && dsdOutput[1].size() != bytesPerChannel)) {
                return kTranscodeIoError;
            }
            if (!writer.writeChannelData(
                    dsdOutput[0].data(),
                    sourceChannels == 2 ? dsdOutput[1].data() : nullptr,
                    static_cast<uint32_t>(bytesPerChannel))) {
                return kTranscodeIoError;
            }
            offset += static_cast<int>(frames);
        }
        return 0;
    };

    auto convertDecodedFrame = [&]() -> int {
        if (session->cancelled()) return kTranscodeCancelled;
        const int capacity = static_cast<int>(av_rescale_rnd(
            swr_get_delay(resampler, sourceRate) + decodedFrame->nb_samples,
            sourceRate,
            sourceRate,
            AV_ROUND_UP
        ));
        if (capacity <= 0) return 0;

        uint8_t** converted = nullptr;
        int lineSize = 0;
        int localResult = av_samples_alloc_array_and_samples(
            &converted,
            &lineSize,
            sourceChannels,
            capacity,
            AV_SAMPLE_FMT_S32,
            0
        );
        if (localResult < 0) return localResult;
        const int convertedSamples = swr_convert(
            resampler,
            converted,
            capacity,
            const_cast<const uint8_t**>(decodedFrame->extended_data),
            decodedFrame->nb_samples
        );
        if (convertedSamples < 0) {
            localResult = convertedSamples;
        } else if (convertedSamples > 0) {
            localResult = processInterleavedS32(
                reinterpret_cast<const int32_t*>(converted[0]),
                convertedSamples
            );
        }
        if (converted) {
            av_freep(&converted[0]);
            av_freep(&converted);
        }
        if (localResult >= 0) {
            decodedSourceSamples += decodedFrame->nb_samples;
            updateProgress();
        }
        return localResult;
    };

    auto flushResampler = [&]() -> int {
        while (true) {
            if (session->cancelled()) return kTranscodeCancelled;
            const int64_t delay = swr_get_delay(resampler, sourceRate);
            if (delay <= 0) return 0;
            const int capacity = static_cast<int>(delay);
            uint8_t** converted = nullptr;
            int lineSize = 0;
            int localResult = av_samples_alloc_array_and_samples(
                &converted,
                &lineSize,
                sourceChannels,
                capacity,
                AV_SAMPLE_FMT_S32,
                0
            );
            if (localResult < 0) return localResult;
            const int convertedSamples = swr_convert(resampler, converted, capacity, nullptr, 0);
            if (convertedSamples < 0) localResult = convertedSamples;
            else if (convertedSamples > 0) {
                localResult = processInterleavedS32(
                    reinterpret_cast<const int32_t*>(converted[0]),
                    convertedSamples
                );
            }
            if (converted) {
                av_freep(&converted[0]);
                av_freep(&converted);
            }
            if (localResult < 0 || convertedSamples <= 0) return localResult;
        }
    };

    int readResult = 0;
    while ((readResult = av_read_frame(input.format, inputPacket)) >= 0) {
        if (session->cancelled()) {
            cleanup();
            return kTranscodeCancelled;
        }
        if (inputPacket->stream_index != input.stream_index) {
            av_packet_unref(inputPacket);
            continue;
        }
        result = avcodec_send_packet(input.decoder, inputPacket);
        av_packet_unref(inputPacket);
        if (result < 0) {
            if (isRecoverableAudioDecodeError(result)) {
                logRecoverableAudioDecodeError("DSF decoder send", result);
                continue;
            }
            return fail("DSF decoder send", result);
        }

        while ((result = avcodec_receive_frame(input.decoder, decodedFrame)) >= 0) {
            const int convertResult = convertDecodedFrame();
            av_frame_unref(decodedFrame);
            if (convertResult == kTranscodeCancelled) {
                cleanup();
                return kTranscodeCancelled;
            }
            if (convertResult < 0) return fail("PCM->DSD conversion", convertResult);
        }
        if (isRecoverableAudioDecodeError(result)) {
            logRecoverableAudioDecodeError("DSF decoder receive", result);
            av_frame_unref(decodedFrame);
            continue;
        }
        if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
            return fail("DSF decoder receive", result);
        }
    }
    if (readResult != AVERROR_EOF) return fail("DSF input read", readResult);

    result = avcodec_send_packet(input.decoder, nullptr);
    if (result < 0 && result != AVERROR_EOF) return fail("DSF decoder flush send", result);
    while ((result = avcodec_receive_frame(input.decoder, decodedFrame)) >= 0) {
        const int convertResult = convertDecodedFrame();
        av_frame_unref(decodedFrame);
        if (convertResult == kTranscodeCancelled) {
            cleanup();
            return kTranscodeCancelled;
        }
        if (convertResult < 0) return fail("DSF decoder flush conversion", convertResult);
    }
    if (isRecoverableAudioDecodeError(result)) {
        logRecoverableAudioDecodeError("DSF decoder flush receive", result);
        av_frame_unref(decodedFrame);
    } else if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
        return fail("DSF decoder flush receive", result);
    }

    result = flushResampler();
    if (result == kTranscodeCancelled) {
        cleanup();
        return kTranscodeCancelled;
    }
    if (result < 0) return fail("DSF converter flush", result);
    if (session->cancelled()) {
        cleanup();
        return kTranscodeCancelled;
    }
    if (!writer.finalize()) return fail("DSF finalize", kTranscodeIoError);

    session->setProgressPermille(1000);
    LOGI(
        "complete target=dsf sourceRate=%d dsdRate=%d multiplier=%d channels=%d input=%s output=%s",
        sourceRate,
        targetDsdRate,
        dsd_multiplier,
        sourceChannels,
        input_path,
        output_path
    );
    av_packet_free(&inputPacket);
    av_frame_free(&decodedFrame);
    swr_free(&resampler);
    return kTranscodeOk;
}

int computeCanonicalPcmDigest(
    AudioTranscodeSession* session,
    const char* input_path,
    int sample_rate_hz,
    int bit_depth,
    PcmDigestResult& digest_result
) {
    digest_result = {};
    if (!session || !input_path || !*input_path ||
        !isSelectablePcmSampleRate(sample_rate_hz) ||
        !isSelectablePcmBitDepth(bit_depth)) {
        return kTranscodeInvalidArgument;
    }
    session->setProgressPermille(0);
    if (session->cancelled()) return kTranscodeCancelled;

    OpenAudioInput input;
    int result = openAudioInput(input_path, input);
    if (result < 0) return result;
    AVStream* inputStream = input.format->streams[input.stream_index];
    const AVCodecParameters* parameters = inputStream->codecpar;
    if (!parameters || isDsdCodecId(parameters->codec_id) ||
        input.decoder->sample_rate != sample_rate_hz) {
        return kTranscodeUnsupportedSource;
    }

    SwrContext* resampler = nullptr;
    AVPacket* packet = av_packet_alloc();
    AVFrame* frame = av_frame_alloc();
    AVSHA* sha = av_sha_alloc();
    if (!packet || !frame || !sha) {
        av_packet_free(&packet);
        av_frame_free(&frame);
        av_free(sha);
        return AVERROR(ENOMEM);
    }
    if (av_sha_init(sha, 256) < 0) {
        av_packet_free(&packet);
        av_frame_free(&frame);
        av_free(sha);
        return kTranscodeIoError;
    }

    AVChannelLayout outputLayout{};
    result = av_channel_layout_copy(&outputLayout, &input.decoder->ch_layout);
    if (result >= 0) {
        result = swr_alloc_set_opts2(
            &resampler,
            &outputLayout,
            AV_SAMPLE_FMT_S32,
            sample_rate_hz,
            &input.decoder->ch_layout,
            input.decoder->sample_fmt,
            input.decoder->sample_rate,
            0,
            nullptr
        );
    }
    av_channel_layout_uninit(&outputLayout);
    if (result < 0 || !resampler) {
        av_packet_free(&packet);
        av_frame_free(&frame);
        swr_free(&resampler);
        av_free(sha);
        return result < 0 ? result : AVERROR(ENOMEM);
    }
    result = swr_init(resampler);
    if (result < 0) {
        av_packet_free(&packet);
        av_frame_free(&frame);
        swr_free(&resampler);
        av_free(sha);
        return result;
    }

    const int channels = input.decoder->ch_layout.nb_channels;
    const int64_t sourceDurationMs = durationMs(input.format, inputStream);
    const int64_t expectedFrames = sourceDurationMs > 0
        ? sourceDurationMs * static_cast<int64_t>(sample_rate_hz) / 1000LL
        : 0LL;
    int64_t hashedFrames = 0;

    auto cleanup = [&]() {
        av_packet_free(&packet);
        av_frame_free(&frame);
        swr_free(&resampler);
        av_free(sha);
        sha = nullptr;
    };

    auto appendCanonical = [&](const int32_t* pcm, int frames) -> int {
        if (!pcm || frames <= 0) return 0;
        const size_t samples = static_cast<size_t>(frames) * static_cast<size_t>(channels);
        if (samples > std::numeric_limits<size_t>::max() / 4u) return AVERROR(EOVERFLOW);
        std::vector<uint8_t> canonical(samples * 4u);
        for (size_t index = 0; index < samples; ++index) {
            uint32_t value = static_cast<uint32_t>(pcm[index]);
            if (bit_depth == 16) value &= 0xFFFF0000u;
            else if (bit_depth == 24) value &= 0xFFFFFF00u;
            const size_t offset = index * 4u;
            canonical[offset] = static_cast<uint8_t>(value & 0xFFu);
            canonical[offset + 1u] = static_cast<uint8_t>((value >> 8u) & 0xFFu);
            canonical[offset + 2u] = static_cast<uint8_t>((value >> 16u) & 0xFFu);
            canonical[offset + 3u] = static_cast<uint8_t>((value >> 24u) & 0xFFu);
        }
        av_sha_update(sha, canonical.data(), canonical.size());
        hashedFrames += frames;
        if (expectedFrames > 0) {
            session->setProgressPermille(static_cast<int>(std::clamp<int64_t>(
                hashedFrames * 990LL / expectedFrames,
                0LL,
                990LL
            )));
        }
        return 0;
    };

    auto convertFrame = [&]() -> int {
        if (session->cancelled()) return kTranscodeCancelled;
        const int capacity = static_cast<int>(av_rescale_rnd(
            swr_get_delay(resampler, input.decoder->sample_rate) + frame->nb_samples,
            sample_rate_hz,
            input.decoder->sample_rate,
            AV_ROUND_UP
        ));
        if (capacity <= 0) return 0;
        uint8_t** converted = nullptr;
        int lineSize = 0;
        int localResult = av_samples_alloc_array_and_samples(
            &converted,
            &lineSize,
            channels,
            capacity,
            AV_SAMPLE_FMT_S32,
            0
        );
        if (localResult < 0) return localResult;
        const int convertedFrames = swr_convert(
            resampler,
            converted,
            capacity,
            const_cast<const uint8_t**>(frame->extended_data),
            frame->nb_samples
        );
        if (convertedFrames < 0) localResult = convertedFrames;
        else localResult = appendCanonical(
            reinterpret_cast<const int32_t*>(converted[0]),
            convertedFrames
        );
        if (converted) {
            av_freep(&converted[0]);
            av_freep(&converted);
        }
        return localResult;
    };

    auto drainDecoder = [&]() -> int {
        while (true) {
            if (session->cancelled()) return kTranscodeCancelled;
            const int receive = avcodec_receive_frame(input.decoder, frame);
            if (receive == AVERROR(EAGAIN) || receive == AVERROR_EOF) return receive;
            if (isRecoverableAudioDecodeError(receive)) {
                logRecoverableAudioDecodeError("PCM digest decoder receive", receive);
                av_frame_unref(frame);
                // Packet-local corruption: ask the caller for the next packet. Returning EAGAIN
                // keeps the normal send/receive state machine intact without treating corruption
                // as a successful decoded frame.
                return AVERROR(EAGAIN);
            }
            if (receive < 0) return receive;
            const int convert = convertFrame();
            av_frame_unref(frame);
            if (convert < 0) return convert;
        }
    };

    int readResult = 0;
    while ((readResult = av_read_frame(input.format, packet)) >= 0) {
        if (session->cancelled()) {
            cleanup();
            return kTranscodeCancelled;
        }
        if (packet->stream_index != input.stream_index) {
            av_packet_unref(packet);
            continue;
        }
        result = avcodec_send_packet(input.decoder, packet);
        av_packet_unref(packet);
        if (result < 0) {
            if (isRecoverableAudioDecodeError(result)) {
                logRecoverableAudioDecodeError("PCM digest decoder send", result);
                continue;
            }
            cleanup();
            return result;
        }
        result = drainDecoder();
        if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
            cleanup();
            return result;
        }
    }
    if (readResult != AVERROR_EOF) {
        cleanup();
        return readResult;
    }

    result = avcodec_send_packet(input.decoder, nullptr);
    if (result < 0 && result != AVERROR_EOF) {
        cleanup();
        return result;
    }
    result = drainDecoder();
    if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) {
        cleanup();
        return result;
    }

    while (swr_get_delay(resampler, sample_rate_hz) > 0) {
        if (session->cancelled()) {
            cleanup();
            return kTranscodeCancelled;
        }
        const int capacity = static_cast<int>(swr_get_delay(resampler, sample_rate_hz));
        if (capacity <= 0) break;
        uint8_t** converted = nullptr;
        int lineSize = 0;
        result = av_samples_alloc_array_and_samples(
            &converted,
            &lineSize,
            channels,
            capacity,
            AV_SAMPLE_FMT_S32,
            0
        );
        if (result < 0) {
            cleanup();
            return result;
        }
        const int convertedFrames = swr_convert(resampler, converted, capacity, nullptr, 0);
        if (convertedFrames < 0) result = convertedFrames;
        else result = appendCanonical(
            reinterpret_cast<const int32_t*>(converted[0]),
            convertedFrames
        );
        if (converted) {
            av_freep(&converted[0]);
            av_freep(&converted);
        }
        if (result < 0) {
            cleanup();
            return result;
        }
        if (convertedFrames <= 0) break;
    }

    std::array<uint8_t, 32> digest{};
    av_sha_final(sha, digest.data());
    static constexpr char kHex[] = "0123456789abcdef";
    std::string hex;
    hex.resize(digest.size() * 2u);
    for (size_t index = 0; index < digest.size(); ++index) {
        hex[index * 2u] = kHex[(digest[index] >> 4u) & 0x0Fu];
        hex[index * 2u + 1u] = kHex[digest[index] & 0x0Fu];
    }
    digest_result.sha256 = std::move(hex);
    digest_result.frames = hashedFrames;
    session->setProgressPermille(1000);
    cleanup();
    return kTranscodeOk;
}

}  // namespace rawsmusic::transcode

namespace {

rawsmusic::transcode::AudioTranscodeSession* sessionFromHandle(jlong handle) {
    return reinterpret_cast<rawsmusic::transcode::AudioTranscodeSession*>(handle);
}

std::string pcmCapabilityDescriptor(const rawsmusic::transcode::PcmTargetConfig& target) {
    std::vector<std::tuple<int, int, AVCodecID, int>> profiles;
    if (target.lossless) {
        for (const int rate : rawsmusic::transcode::kSelectablePcmSampleRatesHz) {
            for (const int depth : rawsmusic::transcode::kSelectablePcmBitDepths) {
                AVCodecID codecId = AV_CODEC_ID_NONE;
                if (rawsmusic::transcode::pcmProfileCanOpen(target, rate, depth, 0, &codecId)) {
                    profiles.emplace_back(rate, depth, codecId, 0);
                }
            }
        }
    } else {
        const auto bitRates = rawsmusic::transcode::bitRatePresetsForTarget(target);
        for (const int rate : rawsmusic::transcode::kSelectablePcmSampleRatesHz) {
            for (const int bitRateKbps : bitRates) {
                AVCodecID codecId = AV_CODEC_ID_NONE;
                if (rawsmusic::transcode::pcmProfileCanOpen(
                        target, rate, 0, bitRateKbps, &codecId)) {
                    profiles.emplace_back(rate, 0, codecId, bitRateKbps);
                }
            }
        }
    }
    if (profiles.empty()) return {};

    std::ostringstream profileText;
    for (size_t index = 0; index < profiles.size(); ++index) {
        if (index) profileText << ',';
        profileText << std::get<0>(profiles[index]) << '/'
                    << std::get<1>(profiles[index]) << '/'
                    << static_cast<int>(std::get<2>(profiles[index])) << '/'
                    << std::get<3>(profiles[index]);
    }

    std::ostringstream result;
    result << "id=" << target.id
           << ";container=" << target.container_name
           << ";lossless=" << (target.lossless ? 1 : 0)
           << ";profiles=" << profileText.str();
    if (target.flac_compression) {
        result << ";compressionMin=0;compressionMax=12";
    }
    if (!target.lossless && target.default_bit_rate_kbps > 0) {
        result << ";defaultBitRate=" << target.default_bit_rate_kbps;
    }
    return result.str();
}

std::string dsfCapabilityDescriptor() {
    std::ostringstream rates;
    for (size_t index = 0; index < rawsmusic::transcode::kSelectableDsdMultipliers.size(); ++index) {
        if (index) rates << ',';
        rates << rawsmusic::transcode::kSelectableDsdMultipliers[index];
    }
    std::ostringstream result;
    result << "id=dsf;container=dsf;lossless=1;profiles=;dsdRates=" << rates.str();
    return result.str();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeCreateSession(
    JNIEnv*, jobject
) {
    auto* session = new rawsmusic::transcode::AudioTranscodeSession();
    return reinterpret_cast<jlong>(session);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeCancelSession(
    JNIEnv*, jobject, jlong handle
) {
    if (auto* session = sessionFromHandle(handle)) session->cancel();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeProgressPermille(
    JNIEnv*, jobject, jlong handle
) {
    if (auto* session = sessionFromHandle(handle)) return session->progressPermille();
    return 0;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeLastErrorDetail(
    JNIEnv* env, jobject, jlong handle
) {
    auto* session = sessionFromHandle(handle);
    if (!session) return nullptr;
    const std::string& detail = session->lastErrorDetail();
    if (detail.empty()) return nullptr;
    return env->NewStringUTF(detail.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeDestroySession(
    JNIEnv*, jobject, jlong handle
) {
    delete sessionFromHandle(handle);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeTranscodeLossless(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring inputPath,
    jstring outputPath,
    jstring formatId,
    jint sampleRateHz,
    jint bitDepth,
    jint flacCompressionLevel,
    jboolean autoDither
) {
    auto* session = sessionFromHandle(handle);
    if (!session || !inputPath || !outputPath || !formatId) {
        return rawsmusic::transcode::kTranscodeInvalidArgument;
    }

    const char* input = env->GetStringUTFChars(inputPath, nullptr);
    const char* output = env->GetStringUTFChars(outputPath, nullptr);
    const char* target = env->GetStringUTFChars(formatId, nullptr);
    if (!input || !output || !target) {
        if (input) env->ReleaseStringUTFChars(inputPath, input);
        if (output) env->ReleaseStringUTFChars(outputPath, output);
        if (target) env->ReleaseStringUTFChars(formatId, target);
        return AVERROR(ENOMEM);
    }
    const int result = rawsmusic::transcode::transcodeLosslessPcm(
        session,
        input,
        output,
        target,
        sampleRateHz,
        bitDepth,
        flacCompressionLevel,
        autoDither == JNI_TRUE
    );
    env->ReleaseStringUTFChars(inputPath, input);
    env->ReleaseStringUTFChars(outputPath, output);
    env->ReleaseStringUTFChars(formatId, target);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeTranscodeLossy(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring inputPath,
    jstring outputPath,
    jstring formatId,
    jint sampleRateHz,
    jint bitRateKbps
) {
    auto* session = sessionFromHandle(handle);
    if (!session || !inputPath || !outputPath || !formatId) {
        return rawsmusic::transcode::kTranscodeInvalidArgument;
    }

    const char* input = env->GetStringUTFChars(inputPath, nullptr);
    const char* output = env->GetStringUTFChars(outputPath, nullptr);
    const char* target = env->GetStringUTFChars(formatId, nullptr);
    if (!input || !output || !target) {
        if (input) env->ReleaseStringUTFChars(inputPath, input);
        if (output) env->ReleaseStringUTFChars(outputPath, output);
        if (target) env->ReleaseStringUTFChars(formatId, target);
        return AVERROR(ENOMEM);
    }
    const int result = rawsmusic::transcode::transcodeLossyPcm(
        session,
        input,
        output,
        target,
        sampleRateHz,
        bitRateKbps
    );
    env->ReleaseStringUTFChars(inputPath, input);
    env->ReleaseStringUTFChars(outputPath, output);
    env->ReleaseStringUTFChars(formatId, target);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeTranscodeDsf(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring inputPath,
    jstring outputPath,
    jint dsdMultiplier
) {
    auto* session = sessionFromHandle(handle);
    if (!session || !inputPath || !outputPath) {
        return rawsmusic::transcode::kTranscodeInvalidArgument;
    }
    const char* input = env->GetStringUTFChars(inputPath, nullptr);
    const char* output = env->GetStringUTFChars(outputPath, nullptr);
    if (!input || !output) {
        if (input) env->ReleaseStringUTFChars(inputPath, input);
        if (output) env->ReleaseStringUTFChars(outputPath, output);
        return AVERROR(ENOMEM);
    }
    const int result = rawsmusic::transcode::transcodePcmToDsf(
        session,
        input,
        output,
        static_cast<int>(dsdMultiplier)
    );
    env->ReleaseStringUTFChars(inputPath, input);
    env->ReleaseStringUTFChars(outputPath, output);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeRemuxDsdToDsf(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring inputPath,
    jstring outputPath,
    jint dsdMultiplier
) {
    auto* session = sessionFromHandle(handle);
    if (!session || !inputPath || !outputPath) {
        return rawsmusic::transcode::kTranscodeInvalidArgument;
    }
    const char* input = env->GetStringUTFChars(inputPath, nullptr);
    const char* output = env->GetStringUTFChars(outputPath, nullptr);
    if (!input || !output) {
        if (input) env->ReleaseStringUTFChars(inputPath, input);
        if (output) env->ReleaseStringUTFChars(outputPath, output);
        return AVERROR(ENOMEM);
    }
    const int result = rawsmusic::transcode::remuxDsdToDsf(
        session,
        input,
        output,
        static_cast<int>(dsdMultiplier)
    );
    env->ReleaseStringUTFChars(inputPath, input);
    env->ReleaseStringUTFChars(outputPath, output);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeCreatePcm16Reader(
    JNIEnv* env,
    jobject,
    jlong sessionHandle,
    jstring inputPath,
    jint sampleRateHz,
    jint channels
) {
    auto* session = sessionFromHandle(sessionHandle);
    if (!session || !inputPath || sampleRateHz <= 0 || channels <= 0) return 0;
    const char* input = env->GetStringUTFChars(inputPath, nullptr);
    if (!input) return 0;
    int errorCode = rawsmusic::transcode::kTranscodeInvalidArgument;
    auto* reader = rawsmusic::transcode::createPcm16Reader(
        session,
        input,
        static_cast<int>(sampleRateHz),
        static_cast<int>(channels),
        &errorCode
    );
    env->ReleaseStringUTFChars(inputPath, input);
    if (!reader) {
        LOGE("pcm16 reader create failed code=%d", errorCode);
        return 0;
    }
    return reinterpret_cast<jlong>(reader);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeReadPcm16(
    JNIEnv* env,
    jobject,
    jlong readerHandle,
    jobject directBuffer,
    jint maxFrames
) {
    auto* reader = reinterpret_cast<rawsmusic::transcode::Pcm16ReaderContext*>(readerHandle);
    if (!reader || !directBuffer || maxFrames <= 0) {
        return rawsmusic::transcode::kTranscodeInvalidArgument;
    }
    auto* output = static_cast<int16_t*>(env->GetDirectBufferAddress(directBuffer));
    const jlong capacityBytes = env->GetDirectBufferCapacity(directBuffer);
    if (!output || capacityBytes <= 0 || reader->channels <= 0) {
        return rawsmusic::transcode::kTranscodeInvalidArgument;
    }
    const int64_t frameBytes = static_cast<int64_t>(reader->channels) * sizeof(int16_t);
    const int64_t capacityFrames = capacityBytes / frameBytes;
    if (capacityFrames <= 0) return rawsmusic::transcode::kTranscodeInvalidArgument;
    const int boundedFrames = static_cast<int>(std::min<int64_t>(
        static_cast<int64_t>(maxFrames),
        capacityFrames
    ));
    return rawsmusic::transcode::readPcm16Reader(reader, output, boundedFrames);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeDestroyPcm16Reader(
    JNIEnv*,
    jobject,
    jlong readerHandle
) {
    auto* reader = reinterpret_cast<rawsmusic::transcode::Pcm16ReaderContext*>(readerHandle);
    rawsmusic::transcode::destroyPcm16Reader(reader);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeProbeAudio(
    JNIEnv* env,
    jobject,
    jstring inputPath
) {
    if (!inputPath) return nullptr;
    const char* input = env->GetStringUTFChars(inputPath, nullptr);
    if (!input) return nullptr;
    rawsmusic::transcode::AudioProbeResult probe{};
    const int result = rawsmusic::transcode::probeAudioFile(input, probe);
    env->ReleaseStringUTFChars(inputPath, input);
    if (result < 0) return nullptr;

    const std::array<jlong, 10> values{
        static_cast<jlong>(probe.sample_rate_hz),
        static_cast<jlong>(probe.bit_depth),
        static_cast<jlong>(probe.channels),
        static_cast<jlong>(probe.duration_ms),
        static_cast<jlong>(probe.bit_rate),
        static_cast<jlong>(probe.codec_id),
        static_cast<jlong>(probe.is_dsd ? 1 : 0),
        static_cast<jlong>(probe.dsd_sample_rate_hz),
        static_cast<jlong>(probe.bit_depth_meaningful ? 1 : 0),
        static_cast<jlong>(probe.has_video_stream ? 1 : 0)
    };
    jlongArray array = env->NewLongArray(static_cast<jsize>(values.size()));
    if (!array) return nullptr;
    env->SetLongArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    return array;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeProbeDsf(
    JNIEnv* env,
    jobject,
    jstring inputPath
) {
    if (!inputPath) return nullptr;
    const char* input = env->GetStringUTFChars(inputPath, nullptr);
    if (!input) return nullptr;
    rawsmusic::transcode::DsfProbeResult probe{};
    const bool ok = rawsmusic::transcode::probeDsfFile(input, probe);
    env->ReleaseStringUTFChars(inputPath, input);
    if (!ok) return nullptr;

    const std::array<jlong, 9> values{
        static_cast<jlong>(probe.sample_rate_hz),
        static_cast<jlong>(probe.channels),
        static_cast<jlong>(probe.bits_per_sample),
        static_cast<jlong>(probe.sample_count),
        static_cast<jlong>(probe.block_size_per_channel),
        static_cast<jlong>(probe.duration_ms),
        static_cast<jlong>(probe.file_size),
        static_cast<jlong>(probe.metadata_offset),
        static_cast<jlong>(probe.data_chunk_size)
    };
    jlongArray array = env->NewLongArray(static_cast<jsize>(values.size()));
    if (!array) return nullptr;
    env->SetLongArrayRegion(array, 0, static_cast<jsize>(values.size()), values.data());
    return array;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeCanonicalPcmDigest(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring inputPath,
    jint sampleRateHz,
    jint bitDepth
) {
    auto* session = sessionFromHandle(handle);
    if (!session || !inputPath) return nullptr;
    const char* input = env->GetStringUTFChars(inputPath, nullptr);
    if (!input) return nullptr;
    rawsmusic::transcode::PcmDigestResult digest{};
    const int result = rawsmusic::transcode::computeCanonicalPcmDigest(
        session,
        input,
        sampleRateHz,
        bitDepth,
        digest
    );
    env->ReleaseStringUTFChars(inputPath, input);
    if (result < 0 || digest.sha256.size() != 64u) return nullptr;
    const std::string serialized = digest.sha256 + ";" + std::to_string(digest.frames);
    return env->NewStringUTF(serialized.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeCanOpenProfile(
    JNIEnv* env,
    jobject,
    jstring formatId,
    jint sampleRateHz,
    jint bitDepth,
    jint bitRateKbps,
    jint channels
) {
    if (!formatId || sampleRateHz <= 0 || channels <= 0) return JNI_FALSE;
    const char* targetId = env->GetStringUTFChars(formatId, nullptr);
    if (!targetId) return JNI_FALSE;
    const auto* target = rawsmusic::transcode::findTargetConfig(targetId);
    bool canOpen = false;
    if (target) {
        canOpen = rawsmusic::transcode::pcmProfileCanOpenForChannels(
            *target,
            static_cast<int>(sampleRateHz),
            static_cast<int>(bitDepth),
            static_cast<int>(bitRateKbps),
            static_cast<int>(channels),
            nullptr
        );
    }
    env->ReleaseStringUTFChars(formatId, targetId);
    return canOpen ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rawsmusic_transcode_NativeAudioTranscoder_nativeListCapabilities(
    JNIEnv* env,
    jobject
) {
    std::vector<std::string> descriptors;
    for (const auto& target : rawsmusic::transcode::kPcmTargets) {
        const std::string descriptor = pcmCapabilityDescriptor(target);
        if (!descriptor.empty()) descriptors.push_back(descriptor);
    }
    descriptors.push_back(dsfCapabilityDescriptor());

    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    jobjectArray array = env->NewObjectArray(
        static_cast<jsize>(descriptors.size()),
        stringClass,
        nullptr
    );
    if (!array) {
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }
    for (size_t index = 0; index < descriptors.size(); ++index) {
        jstring value = env->NewStringUTF(descriptors[index].c_str());
        if (value) {
            env->SetObjectArrayElement(array, static_cast<jsize>(index), value);
            env->DeleteLocalRef(value);
        }
    }
    env->DeleteLocalRef(stringClass);
    return array;
}
