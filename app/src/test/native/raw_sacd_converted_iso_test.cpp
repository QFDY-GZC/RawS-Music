#include "../../main/cpp/ffmpeg_bridge.cpp"
#include "../../main/cpp/raw_sacd_iso.h"
#include <cassert>
#include <cstdio>

int main(int argc, char** argv) {
    assert(argc >= 2);
    std::string error;
    auto tracks = rawsmusic::sacd_iso::scan(argv[1], error);
    if (!error.empty()) std::fprintf(stderr, "%s\n", error.c_str());
    assert(tracks.size() == 1 && tracks[0].channels == 2 && !tracks[0].dst);
    rawsmusic::sacd_iso::Reader reader;
    assert(reader.open({argv[1], 0, 0}, error));
    const auto header = rawsmusic::sacd_iso::dff_header(2, uint64_t(tracks[0].frames) * 9408);
    assert(reader.seek(header.size(), SEEK_SET) >= 0);
    uint8_t raw[100];
    assert(reader.read(raw, sizeof(raw)) == sizeof(raw));
    if (argc == 2) {
        assert(tracks[0].title == "Test song" && tracks[0].artist == "Test artist" && tracks[0].album == "Test album");
        assert(tracks[0].frames == 76);
        for (int i = 0; i < 100; i += 2) assert(raw[i] == 0x12 && raw[i+1] == 0x34);
        assert(reader.seek(header.size() + 2 * (4704 * 75 + 19), SEEK_SET) >= 0);
        assert(reader.read(raw, sizeof(raw)) == sizeof(raw));
        for (auto value : raw) assert(value == 0x69);
    }
    auto digest = [](const char* path) {
        auto* decoder = stream_decoder_open(path, 44100, 32, 2, nullptr, nullptr, 1.f);
        assert(decoder);
        uint8_t pcm[16384]; uint64_t bytes=0, hash=1469598103934665603ULL; int count;
        while ((count=stream_decoder_read(decoder, pcm, sizeof(pcm))) > 0) {
            bytes += count;
            for (int i=0; i<count; ++i) { hash ^= pcm[i]; hash *= 1099511628211ULL; }
        }
        assert(count == -1 && bytes > 0);
        stream_decoder_close(decoder);
        return std::make_pair(bytes, hash);
    };
    auto result = digest(argv[1]);
    if (argc == 3) assert(result == digest(argv[2]));
    std::printf("CONVERTED_SACD_ISO_READ_DECODE=true tracks=1 frames=%u pcmBytes=%llu\n",tracks[0].frames,
        static_cast<unsigned long long>(result.first));
}
