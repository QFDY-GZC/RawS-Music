#include "../../main/cpp/audio_transcode_engine.h"
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

using namespace rawsmusic::transcode;
static void le(std::ofstream& out, uint32_t n, int bytes) {
    for (int i = 0; i < bytes; ++i) out.put(static_cast<char>(n >> (8 * i)));
}
int main(int argc, char** argv) {
    if (argc < 3) return 2;
    const std::string root = argv[1], mode = argv[2];
    const std::string wav = root + "/regression.wav", clean = root + "/regression-clean.flac";
    {
        std::ofstream out(wav, std::ios::binary);
        out.write("RIFF", 4); le(out, 36 + 44100 * 4, 4); out.write("WAVEfmt ", 8);
        le(out, 16, 4); le(out, 1, 2); le(out, 2, 2); le(out, 44100, 4);
        le(out, 176400, 4); le(out, 4, 2); le(out, 16, 2);
        out.write("data", 4); le(out, 44100 * 4, 4);
        for (int i = 0; i < 44100; ++i) {
            int16_t value = static_cast<int16_t>(std::sin(i * 0.062) * 26000);
            le(out, static_cast<uint16_t>(value), 2); le(out, static_cast<uint16_t>(value), 2);
        }
    }
    AudioTranscodeSession session;
    if (mode == "real" && argc == 4) {
        const std::string output = root + "/regression-real-384k32.flac";
        AudioProbeResult source, probe;
        if (probeAudioFile(argv[3], source) != 0) return 3;
        const int code = transcodeLosslessPcm(&session, argv[3], output.c_str(), "flac", 384000, 32, 8, false);
        const int result = probeAudioFile(output.c_str(), probe);
        std::printf("REAL_FLAC code=%d probe=%d rate=%d bits=%d duration=%lld sourceDuration=%lld\n",
            code, result, probe.sample_rate_hz, probe.bit_depth, (long long)probe.duration_ms, (long long)source.duration_ms);
        return code == 0 && result == 0 && probe.sample_rate_hz == 384000 && probe.bit_depth == 32 &&
            std::abs(probe.duration_ms - source.duration_ms) <= 100 ? 0 : 1;
    }
    if (mode == "depth") {
        const int code = transcodeLosslessPcm(&session, wav.c_str(), clean.c_str(), "flac", 44100, 32, 8, false);
        AudioProbeResult probe;
        const int result = probeAudioFile(clean.c_str(), probe);
        PcmDigestResult a, b; AudioTranscodeSession digestSession;
        const int da = computeCanonicalPcmDigest(&digestSession, wav.c_str(), 44100, 32, a);
        const int db = computeCanonicalPcmDigest(&digestSession, clean.c_str(), 44100, 32, b);
        std::printf("FLAC32 code=%d probe=%d bits=%d duration=%lld\n", code, result, probe.bit_depth, (long long)probe.duration_ms);
        std::printf("FLAC32_HASH equal=%d\n", da == 0 && db == 0 && a.sha256 == b.sha256);
        return code == 0 && result == 0 && probe.bit_depth == 32 && da == 0 && db == 0 && a.sha256 == b.sha256 ? 0 : 1;
    }
    if (transcodeLosslessPcm(&session, wav.c_str(), clean.c_str(), "flac", 44100, 16, 8, false) != 0) return 3;
    std::ifstream in(clean, std::ios::binary);
    std::vector<char> bytes((std::istreambuf_iterator<char>(in)), {});
    size_t offset = 4;
    bool last = false;
    do {
        last = (static_cast<unsigned char>(bytes[offset]) & 0x80) != 0;
        const size_t length = (static_cast<unsigned char>(bytes[offset+1]) << 16) |
            (static_cast<unsigned char>(bytes[offset+2]) << 8) | static_cast<unsigned char>(bytes[offset+3]);
        offset += 4 + length;
    } while (!last && offset < bytes.size());
    const std::string trailing = root + "/regression-trailing.flac", output = root + "/regression-output.flac";
    {
        std::ofstream out(trailing, std::ios::binary); out.write(bytes.data(), bytes.size());
        out.write(bytes.data() + offset, bytes.size() - offset);
    }
    const int code = transcodeLosslessPcm(&session, trailing.c_str(), output.c_str(), "flac", 48000, 24, 8, true);
    AudioProbeResult probe; const int result = probeAudioFile(output.c_str(), probe);
    std::printf("FLAC_TAIL code=%d probe=%d duration=%lld expected=1000\n", code, result, (long long)probe.duration_ms);
    PcmDigestResult a, b; AudioTranscodeSession digestSession;
    const int da = computeCanonicalPcmDigest(&digestSession, clean.c_str(), 44100, 16, a);
    const int db = computeCanonicalPcmDigest(&digestSession, trailing.c_str(), 44100, 16, b);
    std::printf("FLAC_TAIL_DIGEST clean=%lld trailing=%lld equal=%d\n", (long long)a.frames, (long long)b.frames,
        da == 0 && db == 0 && a.sha256 == b.sha256);
    return code == 0 && result == 0 && probe.duration_ms == 1000 && da == 0 && db == 0 && a.sha256 == b.sha256 ? 0 : 1;
}
