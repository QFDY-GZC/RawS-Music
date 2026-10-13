// Standalone Android fixture: exercise the exact live decoder with the shipped codec library.
#include "../../main/cpp/ffmpeg_bridge.cpp"

static bool decodeFixture(const char* path, uint64_t* bytes, uint64_t* hash) {
    StreamDecoder* decoder = stream_decoder_open(path, 44100, 32, 2, nullptr, nullptr, 1.0f);
    if (!decoder) return false;
    *bytes = 0;
    *hash = 1469598103934665603ULL;
    uint8_t pcm[16384];
    int result;
    while ((result = stream_decoder_read(decoder, pcm, sizeof(pcm))) > 0) {
        *bytes += result;
        for (int i = 0; i < result; ++i) { *hash ^= pcm[i]; *hash *= 1099511628211ULL; }
    }
    std::printf("DECODE bytes=%llu hash=%llu result=%d duration=%lld\n",
        (unsigned long long)*bytes, (unsigned long long)*hash, result,
        (long long)decoder->duration_us);
    stream_decoder_close(decoder);
    return result == -1 && *bytes > 0;
}
int main(int argc, char** argv) {
    if (argc != 3) return 2;
    uint64_t originalBytes, originalHash, repairedBytes, repairedHash;
    if (!decodeFixture(argv[1], &originalBytes, &originalHash)) return 3;
    if (!decodeFixture(argv[2], &repairedBytes, &repairedHash)) return 4;
    const bool same = originalBytes == repairedBytes && originalHash == repairedHash;
    std::printf("PREFIX_COMPAT_PCM_EQUAL=%s\n", same ? "true" : "false");
    return same ? 0 : 5;
}
