#pragma once
#include <cstdint>
#include <cstdio>
#include <cstring>

namespace rawmedia {
inline int64_t leadingId3Extent(const uint8_t* header, size_t size, int64_t fileSize) {
    if (!header || size < 10 || std::memcmp(header, "ID3", 3) != 0 ||
        header[3] < 2 || header[3] > 4) return 0;
    for (int i = 6; i < 10; ++i) if (header[i] & 0x80) return 0;
    const int64_t payload = (int64_t(header[6]) << 21) | (int64_t(header[7]) << 14) |
        (int64_t(header[8]) << 7) | header[9];
    const int64_t extent = 10 + payload + ((header[3] == 4 && (header[5] & 0x10)) ? 10 : 0);
    return extent <= fileSize - 16 ? extent : 0;
}
inline bool isMp4Ftyp(const uint8_t* header, size_t size, int64_t remaining) {
    if (!header || size < 12 || std::memcmp(header + 4, "ftyp", 4) != 0) return false;
    const uint32_t extent = (uint32_t(header[0]) << 24) | (uint32_t(header[1]) << 16) |
        (uint32_t(header[2]) << 8) | header[3];
    return extent >= 16 && extent <= remaining;
}
/** 0=ordinary file, positive=MP4 behind a foreign ID3 prefix, -1=plain MP4. */
inline int64_t mp4InputPrefix(const char* path) {
    if (!path) return 0;
    FILE* file = std::fopen(path, "rb");
    if (!file) return 0;
    int64_t result = 0;
    if (fseeko(file, 0, SEEK_END) == 0) {
        const int64_t length = ftello(file);
        uint8_t header[12] = {};
        if (fseeko(file, 0, SEEK_SET) == 0 && std::fread(header, 1, sizeof(header), file) == sizeof(header)) {
            if (isMp4Ftyp(header, sizeof(header), length)) result = -1;
            else {
                const int64_t prefix = leadingId3Extent(header, sizeof(header), length);
                if (prefix > 0 && fseeko(file, prefix, SEEK_SET) == 0 &&
                    std::fread(header, 1, sizeof(header), file) == sizeof(header) &&
                    isMp4Ftyp(header, sizeof(header), length - prefix)) result = prefix;
            }
        }
    }
    std::fclose(file);
    return result;
}
}
