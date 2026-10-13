#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace rawsmusic::sacd_iso {
struct TrackAddress {
    std::string source;
    int area = -1; // -1 selects stereo, otherwise the explicit SACD area (0/1).
    int track = 0; // 0 exposes the entire selected area for existing CUE-style playback.
};
struct Track {
    int area = 0;
    int number = 0;
    int channels = 0;
    int year = 0;
    int disc = 0;
    uint32_t start_frame = 0;
    uint32_t frames = 0;
    bool dst = false;
    std::string title, artist, album, album_artist, composer;
    int64_t duration_ms() const { return int64_t(frames) * 1000 / 75; }
};
bool parse_address(const std::string& path, TrackAddress& result);
bool is_source(const char* path);
std::vector<Track> scan(const std::string& source, std::string& error);
std::vector<uint8_t> dff_header(int channels, uint64_t audio_size);

/** Fixed-memory, read-only virtual DSDIFF track. DST is decoded a frame at a time. */
class Reader {
public:
    Reader();
    ~Reader();
    Reader(const Reader&) = delete;
    Reader& operator=(const Reader&) = delete;
    bool open(const TrackAddress& address, std::string& error);
    int read(uint8_t* target, size_t length);
    int64_t seek(int64_t offset, int whence);
    int64_t size() const;
    const Track& track() const;
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
} // namespace rawsmusic::sacd_iso
