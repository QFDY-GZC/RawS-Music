#include "../../main/cpp/raw_sacd_iso.h"
#include <cassert>
#include <cstring>
#include <cstdio>
#include "raw_sacd_iso_fixture.h"

int main(int argc, char** argv) {
    using namespace rawsmusic::sacd_iso;
    TrackAddress address;
    assert(parse_address("sacd://0/2/%2FMusic%2Ftest%20disc.iso", address));
    assert(address.source == "/Music/test disc.iso" && address.area == 0 && address.track == 2);
    assert(!parse_address("sacd://2/1/%2Fa.iso", address));
    assert(parse_address("sacd://0/0/%2Fa.iso", address) && address.track == 0);
    assert(!parse_address("sacd://0/256/%2Fa.iso", address));
    assert(!parse_address("sacd://0/1/%00bad.iso", address));
    assert(!parse_address("sacd://0/1/%QZ", address));
    assert(is_source("album.ISO"));
    assert(!is_source("song.dsf"));
    auto header = dff_header(2, 9408);
    assert(header.size() >= 80 && std::memcmp(header.data(), "FRM8", 4) == 0);
    assert(std::memcmp(header.data() + header.size() - 12, "DSD ", 4) == 0);
    assert(dff_header(0, 9408).empty());
    assert(dff_header(7, 9408).empty());
    if (argc > 1) {
        std::string path = argv[1];
        write_sacd_fixture(path);
        std::string error;
        auto tracks = scan(path, error);
        if (tracks.size() != 2) std::fprintf(stderr, "SACD fixture scan returned %zu tracks: %s\n", tracks.size(), error.c_str());
        assert(tracks.size() == 2);
        std::fprintf(stderr, "fixture album='%s' artist='%s' frames=%u start2=%u\n",
            tracks[0].album.c_str(), tracks[0].album_artist.c_str(), tracks[0].frames, tracks[1].start_frame);
        assert(tracks[0].album == "SACD fixture");
        assert(tracks[0].title == "Fixture track 1" && tracks[1].title == "Fixture track 2");
        assert(tracks[0].artist == "Track artist 1");
        assert(tracks[0].frames == 75 && tracks[1].start_frame == 75);
        Reader reader;
        assert(reader.open({path, 0, 0}, error));
        size_t header_bytes = dff_header(2, 150 * 9408).size();
        assert(reader.size() == static_cast<int64_t>(header_bytes + 150 * 9408));
        uint8_t bytes[333];
        assert(reader.seek(header_bytes + 17, SEEK_SET) > 0);
        assert(reader.read(bytes, sizeof(bytes)) == sizeof(bytes));
        for (auto byte : bytes) assert(byte == 0x69);
        assert(reader.seek(header_bytes + 75 * 9408 + 17, SEEK_SET) > 0);
        assert(reader.read(bytes, sizeof(bytes)) == sizeof(bytes));
        for (auto byte : bytes) assert(byte == 0x96);
        assert(reader.seek(-17, SEEK_END) >= 0);
        assert(reader.read(bytes, sizeof(bytes)) == 17);
        assert(reader.read(bytes, sizeof(bytes)) == 0);
        assert(reader.seek(-1, SEEK_SET) < 0);
        assert(reader.open({path, 0, 2}, error));
        assert(reader.size() == static_cast<int64_t>(header_bytes + 75 * 9408));
        assert(reader.seek(header_bytes, SEEK_SET) > 0);
        assert(reader.read(bytes, sizeof(bytes)) == sizeof(bytes));
        for (auto byte : bytes) assert(byte == 0x96);
        write_sacd_fixture(path + ".bad.iso", 1);
        Reader bad;
        assert(!bad.open({path + ".bad.iso", -1, 0}, error));
        assert(scan(path + ".bad.iso", error).empty());
        std::puts("SACD fixture TOC, tracks, sequential/random DSD reads, seek and malformed TOC passed");
        const std::string dst_path = path + ".dst.iso";
        write_sacd_fixture(dst_path, 5, true);
        auto dst_tracks = scan(dst_path, error);
        assert(dst_tracks.size() == 2 && dst_tracks[0].dst);
        Reader dst_reader;
        assert(dst_reader.open({dst_path, 0, 0}, error));
        assert(dst_reader.seek(header_bytes, SEEK_SET) > 0);
        assert(dst_reader.read(bytes, sizeof(bytes)) == sizeof(bytes));
        for (auto byte : bytes) assert(byte == 0x69);
        assert(dst_reader.seek(header_bytes + 75 * 9408 + 17, SEEK_SET) > 0);
        assert(dst_reader.read(bytes, sizeof(bytes)) == sizeof(bytes));
        for (auto byte : bytes) assert(byte == 0x96);
        std::puts("SACD DST packet read, lossless decode and random seek passed");
    }
    std::puts("SACD address and virtual DSDIFF header checks passed");
}
