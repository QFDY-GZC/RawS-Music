#include "raw_sacd_iso.h"
#include <algorithm>
#include <cctype>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <limits>
extern "C" {
#include <libsacd/sacd.h>
#include <libdst/decoder.h>
}

namespace rawsmusic::sacd_iso {
namespace {
struct DiscDeleter { void operator()(sacd_t* disc) const { sacd_destroy(disc); } };
using Disc = std::unique_ptr<sacd_t, DiscDeleter>;
Disc open_disc(const std::string& source, std::string& error) {
    Disc disc(sacd_create());
    if (!disc) { error = "SACD reader allocation failed"; return {}; }
    const int result = sacd_init(disc.get(), source.c_str(), 1, 1);
    if (result != SACD_OK) { error = "SACD TOC could not be read: " + std::to_string(result); return {}; }
    return disc;
}
int default_area(sacd_t* disc) {
    channel_t areas[2]{};
    uint16_t count = 2;
    if (sacd_get_available_channel_types(disc, areas, &count) != SACD_OK) return -1;
    for (unsigned i = 0; i < std::min<unsigned>(count, 2); ++i)
        if (areas[i] == TWO_CHANNEL) return 0;
    return count > 0 ? static_cast<int>(areas[0]) : -1;
}
std::string album_text(sacd_t* disc, album_text_type_t kind) {
    const char* value = nullptr;
    if (sacd_get_disc_text(disc, 1, kind, &value) == SACD_OK && value && *value) return value;
    value = nullptr;
    return sacd_get_album_text(disc, 1, kind, &value) == SACD_OK && value ? value : "";
}
std::string track_text(sacd_t* disc, int number, track_type_t kind) {
    const char* value = nullptr;
    return sacd_get_track_text(disc, static_cast<uint8_t>(number), 1, kind, &value) == SACD_OK && value ? value : "";
}
bool read_track(sacd_t* disc, int area, int number, Track& track) {
    uint8_t count = 0;
    uint16_t channels = 0;
    frame_format_t encoding = FRAME_FORMAT_UNKNOWN;
    uint32_t area_frames = 0;
    if (number < 1 || number > MAX_TRACK_COUNT ||
        sacd_select_channel_type(disc, static_cast<channel_t>(area)) != SACD_OK ||
        sacd_get_track_count(disc, &count) != SACD_OK || number > count ||
        sacd_get_area_channel_count(disc, &channels) != SACD_OK || channels < 1 || channels > 6 ||
        sacd_get_area_frame_format_enum(disc, &encoding) != SACD_OK ||
        sacd_get_track_index_start(disc, static_cast<uint8_t>(number), 1, &track.start_frame) != SACD_OK ||
        sacd_get_track_frame_length(disc, static_cast<uint8_t>(number), &track.frames) != SACD_OK ||
        sacd_get_total_area_play_time(disc, &area_frames) != SACD_OK ||
        track.frames == 0 || track.start_frame >= area_frames || track.frames > area_frames - track.start_frame)
        return false;
    if (encoding != FRAME_FORMAT_DST && encoding != FRAME_FORMAT_DSD_3_IN_14 &&
        encoding != FRAME_FORMAT_DSD_3_IN_16) return false;
    // Fixed SACD DSD layouts carry stereo; multichannel SACDs use DST.
    if (encoding != FRAME_FORMAT_DST && channels != 2) return false;
    track.area = area;
    track.number = number;
    track.channels = channels;
    track.dst = encoding == FRAME_FORMAT_DST;
    track.title = track_text(disc, number, TRACK_TYPE_TITLE);
    if (track.title.empty()) track.title = "Track " + std::to_string(number);
    track.album = album_text(disc, ALBUM_TEXT_TYPE_TITLE);
    track.album_artist = album_text(disc, ALBUM_TEXT_TYPE_ARTIST);
    track.artist = track_text(disc, number, TRACK_TYPE_PERFORMER);
    if (track.artist.empty()) track.artist = track.album_artist;
    track.composer = track_text(disc, number, TRACK_TYPE_COMPOSER);
    uint16_t year = 0, disc_number = 0;
    uint8_t month = 0, day = 0;
    sacd_get_disc_date(disc, &year, &month, &day);
    sacd_get_disc_sequence_num(disc, &disc_number);
    track.year = year;
    track.disc = disc_number;
    return true;
}
void be(std::vector<uint8_t>& target, uint64_t value, int bytes) {
    for (int i = bytes - 1; i >= 0; --i) target.push_back(static_cast<uint8_t>(value >> (i * 8)));
}
void id(std::vector<uint8_t>& target, const char* text) { target.insert(target.end(), text, text + 4); }
int hex(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}
}

bool is_source(const char* path) {
    if (!path) return false;
    if (std::strncmp(path, "sacd://", 7) == 0) return true;
    std::string value(path);
    if (value.size() < 4) return false;
    std::string extension = value.substr(value.size() - 4);
    std::transform(extension.begin(), extension.end(), extension.begin(),
        [](unsigned char ch) { return static_cast<char>(std::tolower(ch)); });
    return extension == ".iso";
}
bool parse_address(const std::string& path, TrackAddress& result) {
    if (path.compare(0, 7, "sacd://") != 0) {
        if (!is_source(path.c_str()) || path.empty()) return false;
        result = {path, -1, 0};
        return true;
    }
    if (path.size() < 12 || (path[7] != '0' && path[7] != '1') || path[8] != '/') return false;
    const size_t split = path.find('/', 9);
    if (split == std::string::npos || split == 9 || split > 12) return false;
    unsigned track_number = 0;
    for (size_t i = 9; i < split; ++i) {
        if (path[i] < '0' || path[i] > '9') return false;
        track_number = track_number * 10 + unsigned(path[i] - '0');
    }
    if (track_number > 255) return false;
    std::string source;
    for (size_t i = split + 1; i < path.size(); ++i) {
        unsigned char byte = path[i];
        if (byte == '%') {
            if (i + 2 >= path.size() || hex(path[i + 1]) < 0 || hex(path[i + 2]) < 0) return false;
            byte = static_cast<unsigned char>((hex(path[i + 1]) << 4) | hex(path[i + 2]));
            i += 2;
        }
        if (!byte) return false;
        source.push_back(static_cast<char>(byte));
    }
    if (source.empty()) return false;
    result = {source, path[7] - '0', static_cast<int>(track_number)};
    return true;
}
std::vector<Track> scan(const std::string& source, std::string& error) {
    auto disc = open_disc(source, error);
    if (!disc) return {};
    std::vector<Track> tracks;
    for (int area = 0; area <= 1; ++area) {
        if (sacd_select_channel_type(disc.get(), static_cast<channel_t>(area)) != SACD_OK) continue;
        uint8_t count = 0;
        if (sacd_get_track_count(disc.get(), &count) != SACD_OK) continue;
        for (int number = 1; number <= count; ++number) {
            Track track;
            if (read_track(disc.get(), area, number, track)) tracks.push_back(std::move(track));
        }
    }
    if (tracks.empty()) error = "SACD image contains no valid audio tracks";
    return tracks;
}
std::vector<uint8_t> dff_header(int channels, uint64_t audio_size) {
    if (channels < 1 || channels > 6 || audio_size > uint64_t(INT64_MAX) - 256) return {};
    std::vector<uint8_t> property;
    id(property, "SND ");
    id(property, "FS  "); be(property, 4, 8); be(property, SACD_SAMPLING_FREQUENCY, 4);
    id(property, "CHNL"); be(property, 2 + 4 * channels, 8); be(property, channels, 2);
    const char* stereo[] = {"SLFT", "SRGT"};
    const char* surround_five[] = {"MLFT", "MRGT", "C   ", "LS  ", "RS  "};
    const char* surround[] = {"MLFT", "MRGT", "C   ", "LFE ", "LS  ", "RS  "};
    for (int i = 0; i < channels; ++i)
        id(property, channels == 2 ? stereo[i] : channels == 5 ? surround_five[i] : surround[i]);
    id(property, "CMPR"); be(property, 8, 8); id(property, "DSD ");
    property.insert(property.end(), {3, 'D', 'S', 'D'});
    std::vector<uint8_t> header;
    id(header, "FRM8"); be(header, 4 + 16 + 12 + property.size() + 12 + audio_size, 8);
    id(header, "DSD "); id(header, "FVER"); be(header, 4, 8); be(header, 0x01050000, 4);
    id(header, "PROP"); be(header, property.size(), 8);
    header.insert(header.end(), property.begin(), property.end());
    id(header, "DSD "); be(header, audio_size, 8);
    return header;
}

struct Reader::Impl {
    Disc disc;
    dst_decoder_t* dst = nullptr;
    Track info;
    std::vector<uint8_t> header, frame, compressed;
    int64_t position = 0, total = 0;
    int64_t cached_frame = -1;
    ~Impl() { if (dst) dst_decoder_close(dst); }
    bool load_frame(int64_t relative) {
        if (relative == cached_frame) return true;
        if (relative < 0 || relative >= info.frames) return false;
        uint32_t count = 1;
        uint16_t bytes = 0;
        auto& buffer = info.dst ? compressed : frame;
        if (sacd_get_sound_data(disc.get(), buffer.data(),
                info.start_frame + static_cast<uint32_t>(relative), &count, &bytes) != SACD_OK || count != 1)
            return false;
        if (info.dst) {
            int output_bytes = static_cast<int>(frame.size());
            if (!bytes || dst_decoder_decode(dst, compressed.data(), bytes, frame.data(), &output_bytes) != 0 ||
                output_bytes != static_cast<int>(frame.size())) return false;
        } else if (bytes != frame.size()) return false;
        cached_frame = relative;
        return true;
    }
};
Reader::Reader() : impl_(new Impl) {}
Reader::~Reader() = default;
bool Reader::open(const TrackAddress& address, std::string& error) {
    auto next = std::make_unique<Impl>();
    next->disc = open_disc(address.source, error);
    if (!next->disc) return false;
    int area = address.area < 0 ? default_area(next->disc.get()) : address.area;
    if (area < 0 || area > 1 || !read_track(next->disc.get(), area,
            address.track == 0 ? 1 : address.track, next->info)) {
        error = "Invalid SACD area or track"; return false;
    }
    if (address.track == 0) {
        uint32_t total_frames = 0;
        if (sacd_get_total_area_play_time(next->disc.get(), &total_frames) != SACD_OK || !total_frames) {
            error = "Invalid SACD area duration"; return false;
        }
        next->info.start_frame = 0;
        next->info.frames = total_frames;
        next->info.number = 0;
        next->info.title = next->info.album;
    }
    next->frame.resize(SACD_FRAME_SIZE_64 * next->info.channels);
    if (next->info.dst) {
        next->compressed.resize(65536);
        if (dst_decoder_init(&next->dst, next->info.channels, SACD_SAMPLING_FREQUENCY) != 0) {
            error = "DST decoder initialization failed"; return false;
        }
    }
    uint64_t data_size = uint64_t(next->info.frames) * next->frame.size();
    next->header = dff_header(next->info.channels, data_size);
    next->total = static_cast<int64_t>(next->header.size() + data_size);
    impl_ = std::move(next);
    return true;
}
int Reader::read(uint8_t* target, size_t length) {
    if (!target || !impl_->disc || length > INT32_MAX) return -1;
    size_t copied = 0;
    while (copied < length && impl_->position < impl_->total) {
        if (impl_->position < static_cast<int64_t>(impl_->header.size())) {
            size_t available = impl_->header.size() - static_cast<size_t>(impl_->position);
            size_t count = std::min(length - copied, available);
            std::memcpy(target + copied, impl_->header.data() + impl_->position, count);
            copied += count; impl_->position += count;
        } else {
            int64_t audio_position = impl_->position - static_cast<int64_t>(impl_->header.size());
            int64_t frame_index = audio_position / static_cast<int64_t>(impl_->frame.size());
            if (!impl_->load_frame(frame_index)) return copied ? static_cast<int>(copied) : -1;
            size_t offset = static_cast<size_t>(audio_position % impl_->frame.size());
            size_t count = std::min(length - copied, impl_->frame.size() - offset);
            std::memcpy(target + copied, impl_->frame.data() + offset, count);
            copied += count; impl_->position += count;
        }
    }
    return static_cast<int>(copied);
}
int64_t Reader::seek(int64_t offset, int whence) {
    if (!impl_->disc) return -1;
    int64_t base = whence == SEEK_SET ? 0 : whence == SEEK_CUR ? impl_->position :
        whence == SEEK_END ? impl_->total : -1;
    if (base < 0 || offset < -base || offset > impl_->total - base) return -1;
    impl_->position = base + offset;
    return impl_->position;
}
int64_t Reader::size() const { return impl_->total; }
const Track& Reader::track() const { return impl_->info; }
} // namespace rawsmusic::sacd_iso
