// Exercises the exact decoder source and shipped codec library without starting an audio device.
#include "../../main/cpp/ffmpeg_bridge.cpp"
#include "raw_sacd_iso_fixture.h"
#include <cassert>
#include <fcntl.h>
#include <fstream>
#include <dirent.h>

struct Decoded {
    uint64_t bytes = 0;
    uint64_t hash = 1469598103934665603ULL;
};
static Decoded decode(StreamDecoder* decoder) {
    assert(decoder);
    uint8_t buffer[16384];
    Decoded result;
    int count = 0;
    int iterations = 0;
    while ((count = stream_decoder_read(decoder, buffer, sizeof(buffer))) > 0) {
        assert(++iterations < 10000);
        result.bytes += count;
        for (int i = 0; i < count; ++i) { result.hash ^= buffer[i]; result.hash *= 1099511628211ULL; }
    }
    assert(count == -1 && result.bytes > 0);
    stream_decoder_close(decoder);
    return result;
}
static Decoded decode(const std::string& path, int bits = 32) {
    return decode(stream_decoder_open(path.c_str(), bits == 1 ? 352800 : 44100,
        bits, 2, nullptr, nullptr, 1.f));
}
static int fd_count() {
    DIR* dir = opendir("/proc/self/fd");
    assert(dir);
    int count = 0;
    while (auto* entry = readdir(dir)) if (entry->d_name[0] != '.') ++count;
    closedir(dir);
    return count;
}
int main(int argc, char** argv) {
    if (argc != 2) return 2;
    const std::string root = argv[1];
    const std::string iso = root + "/decode-dsd.iso", dst_iso = root + "/decode-dst.iso";
    const std::string reference = root + "/reference.dff";
    write_sacd_fixture(iso);
    write_sacd_fixture(dst_iso, 5, true);
    auto header = rawsmusic::sacd_iso::dff_header(2, 150 * 9408);
    {
        std::ofstream file(reference, std::ios::binary);
        file.write(reinterpret_cast<const char*>(header.data()), header.size());
        std::vector<char> bytes(75 * 9408, static_cast<char>(0x69));
        file.write(bytes.data(), bytes.size());
        std::fill(bytes.begin(), bytes.end(), static_cast<char>(0x96));
        file.write(bytes.data(), bytes.size());
    }
    const int initial_fds = fd_count();
    const auto expected = decode(reference);
    const auto actual = decode(iso), dst = decode(dst_iso);
    assert(actual.bytes == expected.bytes && actual.hash == expected.hash);
    assert(dst.bytes == expected.bytes && dst.hash == expected.hash);
    std::printf("SACD_DSD_AND_DST_PCM_EQUAL=true bytes=%llu hash=%llu\n",
        static_cast<unsigned long long>(actual.bytes), static_cast<unsigned long long>(actual.hash));
    const auto raw_expected = decode(reference, 1), raw_actual = decode(iso, 1), raw_dst = decode(dst_iso, 1);
    assert(raw_actual.bytes == raw_expected.bytes && raw_actual.hash == raw_expected.hash);
    assert(raw_dst.bytes == raw_expected.bytes && raw_dst.hash == raw_expected.hash);
    std::puts("SACD_RAW_DSD_EQUAL=true");
    {
        int fd = open(iso.c_str(), O_RDONLY);
        assert(fd >= 0);
        std::string address = "sacd://0/0/%2Fproc%2Fself%2Ffd%2F" + std::to_string(fd);
        auto* decoder = stream_decoder_open(address.c_str(), 44100, 32, 2, nullptr, nullptr, 1.f);
        close(fd); // SAF's original descriptor may close after native open.
        const auto copied = decode(decoder);
        assert(copied.bytes == expected.bytes && copied.hash == expected.hash);
    }
    auto* seek_decoder = stream_decoder_open(dst_iso.c_str(), 44100, 32, 2, nullptr, nullptr, 1.f);
    assert(seek_decoder && stream_decoder_seek(seek_decoder, 1000000));
    assert(decode(seek_decoder).bytes > 0);
    for (int i = 0; i < 10; ++i) assert(decode(i % 2 ? iso : dst_iso).bytes == expected.bytes);
    assert(fd_count() == initial_fds);
    std::puts("SACD_SEEK_FD_OWNERSHIP_AND_REPEATED_CLOSE=true");
}
