#include "raw_sacd_avio.h"
#include "raw_sacd_iso.h"
#include <cerrno>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <android/log.h>
extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/error.h>
#include <libavutil/mem.h>
}

namespace {
struct Input {
    rawsmusic::sacd_iso::Reader reader;
    AVIOContext* avio = nullptr;
    ~Input() {
        if (avio) {
            av_freep(&avio->buffer);
            avio_context_free(&avio);
        }
    }
};
std::mutex registry_mutex;
std::unordered_map<AVFormatContext*, std::unique_ptr<Input>> registry;
int read_packet(void* opaque, uint8_t* buffer, int size) {
    if (!opaque || size <= 0) return AVERROR(EINVAL);
    int result = static_cast<Input*>(opaque)->reader.read(buffer, static_cast<size_t>(size));
    return result > 0 ? result : result == 0 ? AVERROR_EOF : AVERROR_INVALIDDATA;
}
int64_t seek_packet(void* opaque, int64_t position, int whence) {
    if (!opaque) return AVERROR(EINVAL);
    auto& reader = static_cast<Input*>(opaque)->reader;
    if (whence & AVSEEK_SIZE) return reader.size();
    int64_t result = reader.seek(position, whence & ~AVSEEK_FORCE);
    return result < 0 ? AVERROR(EINVAL) : result;
}
}

int raw_sacd_avio_open(AVFormatContext** format, const char* path, AVDictionary** options) {
    if (!format || !path) return AVERROR(EINVAL);
    rawsmusic::sacd_iso::TrackAddress address;
    if (!rawsmusic::sacd_iso::parse_address(path, address)) return AVERROR_INVALIDDATA;
    auto input = std::make_unique<Input>();
    std::string error;
    if (!input->reader.open(address, error)) {
        __android_log_print(ANDROID_LOG_ERROR, "SacdIso", "OPEN_FAIL %s", error.c_str());
        return AVERROR_INVALIDDATA;
    }
    uint8_t* buffer = static_cast<uint8_t*>(av_malloc(64 * 1024));
    if (!buffer) return AVERROR(ENOMEM);
    input->avio = avio_alloc_context(buffer, 64 * 1024, 0, input.get(), read_packet, nullptr, seek_packet);
    if (!input->avio) { av_free(buffer); return AVERROR(ENOMEM); }
    input->avio->seekable = AVIO_SEEKABLE_NORMAL;
    AVFormatContext* context = *format ? *format : avformat_alloc_context();
    if (!context) return AVERROR(ENOMEM);
    context->pb = input->avio;
    context->flags |= AVFMT_FLAG_CUSTOM_IO;
    int result = avformat_open_input(&context, nullptr, nullptr, options);
    if (result < 0) {
        if (context) avformat_free_context(context);
        *format = nullptr;
        return result;
    }
    const auto& track = input->reader.track();
    __android_log_print(ANDROID_LOG_INFO, "SacdIso",
        "OPEN_OK area=%d track=%d channels=%d frames=%u dst=%d virtualBytes=%lld",
        track.area, track.number, track.channels, track.frames, track.dst ? 1 : 0,
        static_cast<long long>(input->reader.size()));
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        registry.emplace(context, std::move(input));
    }
    *format = context;
    return 0;
}
bool raw_sacd_avio_close(AVFormatContext** format) {
    if (!format || !*format) return false;
    std::unique_ptr<Input> input;
    {
        std::lock_guard<std::mutex> lock(registry_mutex);
        auto found = registry.find(*format);
        if (found == registry.end()) return false;
        input = std::move(found->second);
        registry.erase(found);
    }
    avformat_close_input(format);
    return true;
}
int raw_audio_format_open(AVFormatContext** format, const char* path,
        const AVInputFormat* input_format, AVDictionary** options) {
    if (rawsmusic::sacd_iso::is_source(path)) return raw_sacd_avio_open(format, path, options);
    return avformat_open_input(format, path, input_format, options);
}
void raw_audio_format_close(AVFormatContext** format) {
    if (!raw_sacd_avio_close(format)) avformat_close_input(format);
}
