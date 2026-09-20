#include "raw_flac_encoder.h"

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <string>
#include <vector>

namespace {

constexpr int kFlacBlockSize = 4096;
constexpr int kOutputBits = 16;
constexpr int kOutputSampleRate = 44100;

struct WavInfo {
    uint16_t format = 0;
    uint16_t channels = 0;
    uint32_t sampleRate = 0;
    uint16_t blockAlign = 0;
    uint16_t bitsPerSample = 0;
    uint64_t dataOffset = 0;
    uint64_t dataSize = 0;
};

uint16_t readLe16(const unsigned char* bytes) {
    return static_cast<uint16_t>(bytes[0]) |
        (static_cast<uint16_t>(bytes[1]) << 8u);
}

uint32_t readLe32(const unsigned char* bytes) {
    return static_cast<uint32_t>(bytes[0]) |
        (static_cast<uint32_t>(bytes[1]) << 8u) |
        (static_cast<uint32_t>(bytes[2]) << 16u) |
        (static_cast<uint32_t>(bytes[3]) << 24u);
}

void writeBe16(std::vector<uint8_t>& bytes, uint16_t value) {
    bytes.push_back(static_cast<uint8_t>((value >> 8u) & 0xffu));
    bytes.push_back(static_cast<uint8_t>(value & 0xffu));
}

void writeBe24(std::vector<uint8_t>& bytes, uint32_t value) {
    bytes.push_back(static_cast<uint8_t>((value >> 16u) & 0xffu));
    bytes.push_back(static_cast<uint8_t>((value >> 8u) & 0xffu));
    bytes.push_back(static_cast<uint8_t>(value & 0xffu));
}

void writeBe64(std::vector<uint8_t>& bytes, uint64_t value) {
    for (int shift = 56; shift >= 0; shift -= 8) {
        bytes.push_back(static_cast<uint8_t>((value >> shift) & 0xffu));
    }
}

bool readBytes(FILE* file, void* target, size_t size) {
    return file != nullptr && std::fread(target, 1, size, file) == size;
}

bool skipBytes(FILE* file, uint64_t size) {
    while (size > 0) {
        const long chunk = static_cast<long>(std::min<uint64_t>(
            size,
            static_cast<uint64_t>(std::numeric_limits<long>::max())
        ));
        if (std::fseek(file, chunk, SEEK_CUR) != 0) return false;
        size -= static_cast<uint64_t>(chunk);
    }
    return true;
}

bool readWavInfo(FILE* file, WavInfo& info) {
    unsigned char riff[12] = {};
    if (!readBytes(file, riff, sizeof(riff)) ||
        std::memcmp(riff, "RIFF", 4) != 0 ||
        std::memcmp(riff + 8, "WAVE", 4) != 0) {
        return false;
    }

    bool foundFormat = false;
    bool foundData = false;
    while (!foundFormat || !foundData) {
        unsigned char chunkHeader[8] = {};
        if (!readBytes(file, chunkHeader, sizeof(chunkHeader))) break;
        const uint32_t chunkSize = readLe32(chunkHeader + 4);
        const long chunkStart = std::ftell(file);
        if (chunkStart < 0) return false;

        if (std::memcmp(chunkHeader, "fmt ", 4) == 0) {
            if (chunkSize < 16 || chunkSize > 4096) return false;
            std::vector<unsigned char> format(chunkSize);
            if (!readBytes(file, format.data(), format.size())) return false;
            info.format = readLe16(format.data());
            info.channels = readLe16(format.data() + 2);
            info.sampleRate = readLe32(format.data() + 4);
            info.blockAlign = readLe16(format.data() + 12);
            info.bitsPerSample = readLe16(format.data() + 14);
            foundFormat = true;
        } else if (std::memcmp(chunkHeader, "data", 4) == 0) {
            info.dataOffset = static_cast<uint64_t>(chunkStart);
            info.dataSize = chunkSize;
            if (!skipBytes(file, chunkSize)) return false;
            foundData = true;
        } else if (!skipBytes(file, chunkSize)) {
            return false;
        }
        if ((chunkSize & 1u) != 0u && !skipBytes(file, 1)) return false;
    }

    if (!foundFormat || !foundData || info.channels == 0 ||
        info.channels > 2 || info.sampleRate == 0 || info.blockAlign == 0 ||
        info.bitsPerSample == 0 || info.dataSize < info.blockAlign) {
        return false;
    }
    if ((info.format != 1 && info.format != 3) ||
        (info.bitsPerSample != 8 && info.bitsPerSample != 16 &&
         info.bitsPerSample != 24 && info.bitsPerSample != 32)) {
        return false;
    }
    if (info.sampleRate != kOutputSampleRate) return false;
    return true;
}

int16_t clampToS16(int64_t value) {
    return static_cast<int16_t>(std::clamp<int64_t>(
        value,
        std::numeric_limits<int16_t>::min(),
        std::numeric_limits<int16_t>::max()
    ));
}

int16_t decodeSample(const unsigned char* bytes, uint16_t format, uint16_t bits) {
    if (format == 3 && bits == 32) {
        float value = 0.0f;
        std::memcpy(&value, bytes, sizeof(value));
        if (!std::isfinite(value)) value = 0.0f;
        value = std::clamp(value, -1.0f, 1.0f);
        return clampToS16(static_cast<int64_t>(std::lrint(value * 32767.0f)));
    }
    if (bits == 8) {
        return clampToS16((static_cast<int>(bytes[0]) - 128) << 8);
    }
    if (bits == 16) {
        return static_cast<int16_t>(readLe16(bytes));
    }
    if (bits == 24) {
        int32_t value = static_cast<int32_t>(bytes[0]) |
            (static_cast<int32_t>(bytes[1]) << 8) |
            (static_cast<int32_t>(bytes[2]) << 16);
        if ((value & 0x00800000) != 0) value |= ~0x00ffffff;
        return clampToS16(value >> 8);
    }
    const int32_t value = static_cast<int32_t>(readLe32(bytes));
    return clampToS16(static_cast<int64_t>(value) >> 16);
}

class BitWriter {
public:
    explicit BitWriter(std::vector<uint8_t>& bytes) : bytes_(bytes) {}

    void write(uint64_t value, int bitCount) {
        for (int shift = bitCount - 1; shift >= 0; --shift) {
            if (bitOffset_ == 0) bytes_.push_back(0);
            if (((value >> shift) & 1u) != 0u) {
                bytes_.back() |= static_cast<uint8_t>(1u << (7 - bitOffset_));
            }
            bitOffset_ = (bitOffset_ + 1) & 7;
        }
    }

    void writeSigned(int64_t value, int bitCount) {
        const uint64_t mask = bitCount == 64
            ? std::numeric_limits<uint64_t>::max()
            : ((uint64_t{1} << bitCount) - 1u);
        write(static_cast<uint64_t>(value) & mask, bitCount);
    }

    void align() {
        if (bitOffset_ != 0) write(0, 8 - bitOffset_);
    }

private:
    std::vector<uint8_t>& bytes_;
    int bitOffset_ = 0;
};

uint8_t crc8(const std::vector<uint8_t>& bytes) {
    uint8_t crc = 0;
    for (uint8_t value : bytes) {
        crc ^= value;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x80u) != 0u
                ? static_cast<uint8_t>((crc << 1u) ^ 0x07u)
                : static_cast<uint8_t>(crc << 1u);
        }
    }
    return crc;
}

uint16_t crc16(const std::vector<uint8_t>& bytes) {
    uint16_t crc = 0;
    for (uint8_t value : bytes) {
        crc ^= static_cast<uint16_t>(value) << 8u;
        for (int bit = 0; bit < 8; ++bit) {
            crc = (crc & 0x8000u) != 0u
                ? static_cast<uint16_t>((crc << 1u) ^ 0x8005u)
                : static_cast<uint16_t>(crc << 1u);
        }
    }
    return crc;
}

void appendUtf8(std::vector<uint8_t>& bytes, uint64_t value) {
    if (value < 0x80u) {
        bytes.push_back(static_cast<uint8_t>(value));
    } else if (value < 0x800u) {
        bytes.push_back(static_cast<uint8_t>(0xc0u | (value >> 6u)));
        bytes.push_back(static_cast<uint8_t>(0x80u | (value & 0x3fu)));
    } else if (value < 0x10000u) {
        bytes.push_back(static_cast<uint8_t>(0xe0u | (value >> 12u)));
        bytes.push_back(static_cast<uint8_t>(0x80u | ((value >> 6u) & 0x3fu)));
        bytes.push_back(static_cast<uint8_t>(0x80u | (value & 0x3fu)));
    } else if (value < 0x200000u) {
        bytes.push_back(static_cast<uint8_t>(0xf0u | (value >> 18u)));
        bytes.push_back(static_cast<uint8_t>(0x80u | ((value >> 12u) & 0x3fu)));
        bytes.push_back(static_cast<uint8_t>(0x80u | ((value >> 6u) & 0x3fu)));
        bytes.push_back(static_cast<uint8_t>(0x80u | (value & 0x3fu)));
    } else if (value < 0x4000000u) {
        bytes.push_back(static_cast<uint8_t>(0xf8u | (value >> 24u)));
        bytes.push_back(static_cast<uint8_t>(0x80u | ((value >> 18u) & 0x3fu)));
        bytes.push_back(static_cast<uint8_t>(0x80u | ((value >> 12u) & 0x3fu)));
        bytes.push_back(static_cast<uint8_t>(0x80u | ((value >> 6u) & 0x3fu)));
        bytes.push_back(static_cast<uint8_t>(0x80u | (value & 0x3fu)));
    } else {
        bytes.push_back(static_cast<uint8_t>(0xfcu | (value >> 30u)));
        bytes.push_back(static_cast<uint8_t>(0x80u | ((value >> 24u) & 0x3fu)));
        bytes.push_back(static_cast<uint8_t>(0x80u | ((value >> 18u) & 0x3fu)));
        bytes.push_back(static_cast<uint8_t>(0x80u | ((value >> 12u) & 0x3fu)));
        bytes.push_back(static_cast<uint8_t>(0x80u | ((value >> 6u) & 0x3fu)));
        bytes.push_back(static_cast<uint8_t>(0x80u | (value & 0x3fu)));
    }
}

int signedBitWidth(int64_t value) {
    for (int bits = 1; bits < 63; ++bits) {
        const int64_t minValue = -(int64_t{1} << (bits - 1));
        const int64_t maxValue = (int64_t{1} << (bits - 1)) - 1;
        if (value >= minValue && value <= maxValue) return bits;
    }
    return 63;
}

int riceBits(int64_t value, int parameter) {
    const uint64_t mapped = value < 0
        ? static_cast<uint64_t>(-value * 2 - 1)
        : static_cast<uint64_t>(value * 2);
    const uint64_t quotient = mapped >> parameter;
    if (quotient > 1'000'000u) return std::numeric_limits<int>::max();
    const uint64_t bits = quotient + 1u + static_cast<uint64_t>(parameter);
    return bits > static_cast<uint64_t>(std::numeric_limits<int>::max())
        ? std::numeric_limits<int>::max()
        : static_cast<int>(bits);
}

struct FixedChoice {
    int order = 0;
    int rice = 0;
    int64_t bits = std::numeric_limits<int64_t>::max();
    std::vector<int64_t> residuals;
};

FixedChoice chooseFixed(const std::vector<int16_t>& samples) {
    FixedChoice best;
    const int sampleCount = static_cast<int>(samples.size());
    for (int order = 0; order <= 4 && sampleCount > order; ++order) {
        FixedChoice candidate;
        candidate.order = order;
        candidate.residuals.reserve(sampleCount - order);
        for (int index = order; index < sampleCount; ++index) {
            const int64_t current = samples[index];
            const int64_t previous = index > 0 ? samples[index - 1] : 0;
            const int64_t previous2 = index > 1 ? samples[index - 2] : 0;
            const int64_t previous3 = index > 2 ? samples[index - 3] : 0;
            const int64_t previous4 = index > 3 ? samples[index - 4] : 0;
            int64_t residual = current;
            if (order == 1) residual = current - previous;
            if (order == 2) residual = current - 2 * previous + previous2;
            if (order == 3) residual = current - 3 * previous + 3 * previous2 - previous3;
            if (order == 4) {
                residual = current - 4 * previous + 6 * previous2 -
                    4 * previous3 + previous4;
            }
            candidate.residuals.push_back(residual);
        }

        for (int rice = 0; rice <= 14; ++rice) {
            int64_t bits = 2 + 4 + 4 + static_cast<int64_t>(order) * 16;
            for (int64_t residual : candidate.residuals) {
                const int valueBits = riceBits(residual, rice);
                if (valueBits == std::numeric_limits<int>::max()) {
                    bits = std::numeric_limits<int64_t>::max();
                    break;
                }
                bits += valueBits;
                if (bits >= best.bits) break;
            }
            if (bits < best.bits) {
                candidate.rice = rice;
                candidate.bits = bits;
                best = candidate;
            }
        }
    }
    return best;
}

void writeRice(BitWriter& writer, int64_t value, int parameter) {
    const uint64_t mapped = value < 0
        ? static_cast<uint64_t>(-value * 2 - 1)
        : static_cast<uint64_t>(value * 2);
    const uint64_t quotient = mapped >> parameter;
    for (uint64_t zero = 0; zero < quotient; ++zero) writer.write(0, 1);
    writer.write(1, 1);
    if (parameter > 0) writer.write(mapped & ((uint64_t{1} << parameter) - 1u), parameter);
}

void writeSubframe(
    BitWriter& writer,
    const std::vector<int16_t>& samples,
    int compressionLevel
) {
    bool constant = !samples.empty();
    for (size_t index = 1; index < samples.size() && constant; ++index) {
        constant = samples[index] == samples[0];
    }
    if (constant) {
        writer.write(0, 8);
        writer.writeSigned(samples[0], kOutputBits);
        return;
    }

    FixedChoice fixed = chooseFixed(samples);
    const int64_t verbatimBits = 8 + static_cast<int64_t>(samples.size()) * kOutputBits;
    const int64_t fixedBits = 8 + fixed.bits;
    const bool useFixed = !fixed.residuals.empty() &&
        (compressionLevel > 0 ? fixedBits < verbatimBits : false);
    if (!useFixed) {
        writer.write(0x02, 8); // verbatim subframe, no wasted bits
        for (int16_t sample : samples) writer.writeSigned(sample, kOutputBits);
        return;
    }

    writer.write(static_cast<uint64_t>(0x08 + fixed.order) << 1u, 8);
    for (int index = 0; index < fixed.order; ++index) {
        writer.writeSigned(samples[index], kOutputBits);
    }
    writer.write(0, 2); // Rice coding method, partition order 0
    writer.write(0, 4);
    writer.write(fixed.rice, 4);
    for (int64_t residual : fixed.residuals) writeRice(writer, residual, fixed.rice);
}

bool writeStreamInfo(FILE* output, const WavInfo& wav, uint64_t totalSamples) {
    std::vector<uint8_t> header;
    header.reserve(42);
    header.insert(header.end(), {'f', 'L', 'a', 'C'});
    header.push_back(0x80); // last metadata block, STREAMINFO
    header.push_back(0);
    header.push_back(0);
    header.push_back(34);
    writeBe16(header, kFlacBlockSize);
    writeBe16(header, kFlacBlockSize);
    writeBe24(header, 0); // frame sizes are unknown until the stream is written
    writeBe24(header, 0);
    const uint64_t packed = (static_cast<uint64_t>(kOutputSampleRate) << 44u) |
        (static_cast<uint64_t>(wav.channels - 1u) << 41u) |
        (static_cast<uint64_t>(kOutputBits - 1u) << 36u) |
        (totalSamples & 0x0000000fffffffffu);
    writeBe64(header, packed);
    header.insert(header.end(), 16, 0); // MD5 is intentionally left unknown
    return std::fwrite(header.data(), 1, header.size(), output) == header.size();
}

int encodeWav(FILE* input, FILE* output, const WavInfo& wav, int compressionLevel) {
    const uint64_t totalSamples = wav.dataSize / wav.blockAlign;
    if (totalSamples == 0 || totalSamples > 0x0000000fffffffffu) return -EINVAL;
    if (!writeStreamInfo(output, wav, totalSamples)) return -EIO;
    if (std::fseek(input, static_cast<long>(wav.dataOffset), SEEK_SET) != 0) return -EIO;

    const size_t bytesPerSample = wav.bitsPerSample / 8u;
    const size_t frameBytes = static_cast<size_t>(wav.blockAlign);
    std::vector<unsigned char> raw(static_cast<size_t>(kFlacBlockSize) * frameBytes);
    std::vector<int16_t> channel[2];
    channel[0].resize(kFlacBlockSize);
    if (wav.channels == 2) channel[1].resize(kFlacBlockSize);

    uint64_t samplesWritten = 0;
    uint64_t frameNumber = 0;
    while (samplesWritten < totalSamples) {
        const int frameCount = static_cast<int>(std::min<uint64_t>(
            kFlacBlockSize,
            totalSamples - samplesWritten
        ));
        const size_t bytesToRead = static_cast<size_t>(frameCount) * frameBytes;
        if (std::fread(raw.data(), 1, bytesToRead, input) != bytesToRead) return -EIO;
        for (int frame = 0; frame < frameCount; ++frame) {
            const unsigned char* source = raw.data() + static_cast<size_t>(frame) * frameBytes;
            for (uint16_t ch = 0; ch < wav.channels; ++ch) {
                channel[ch][frame] = decodeSample(
                    source + static_cast<size_t>(ch) * bytesPerSample,
                    wav.format,
                    wav.bitsPerSample
                );
            }
        }

        std::vector<uint8_t> frame;
        frame.reserve(static_cast<size_t>(frameCount) * wav.channels * 2u + 32u);
        frame.push_back(0xff);
        frame.push_back(0xf8); // fixed blocking strategy, UTF-8 frame number
        frame.push_back(0x79); // blocksize code 7, 44.1 kHz
        frame.push_back(static_cast<uint8_t>(((wav.channels - 1u) << 4u) | 0x08u));
        appendUtf8(frame, frameNumber++);
        frame.push_back(static_cast<uint8_t>((frameCount - 1) >> 8));
        frame.push_back(static_cast<uint8_t>((frameCount - 1) & 0xff));
        frame.push_back(crc8(frame));

        BitWriter writer(frame);
        for (uint16_t ch = 0; ch < wav.channels; ++ch) {
            std::vector<int16_t> samples(channel[ch].begin(), channel[ch].begin() + frameCount);
            writeSubframe(writer, samples, compressionLevel);
        }
        writer.align();
        const uint16_t checksum = crc16(frame);
        frame.push_back(static_cast<uint8_t>(checksum >> 8u));
        frame.push_back(static_cast<uint8_t>(checksum & 0xffu));
        if (std::fwrite(frame.data(), 1, frame.size(), output) != frame.size()) return -EIO;
        samplesWritten += static_cast<uint64_t>(frameCount);
    }
    return 0;
}

} // namespace

int raw_encode_wav_to_flac(const char* inputPath, const char* outputPath, int compressionLevel) {
    if (inputPath == nullptr || outputPath == nullptr) return -EINVAL;
    FILE* input = std::fopen(inputPath, "rb");
    if (input == nullptr) return -errno;
    WavInfo wav;
    const bool validWav = readWavInfo(input, wav);
    if (!validWav) {
        std::fclose(input);
        return -EINVAL;
    }

    std::remove(outputPath);
    FILE* output = std::fopen(outputPath, "wb");
    if (output == nullptr) {
        const int error = -errno;
        std::fclose(input);
        return error;
    }
    const int result = encodeWav(input, output, wav, compressionLevel);
    if (std::fflush(output) != 0 && result == 0) {
        std::fclose(output);
        std::remove(outputPath);
        std::fclose(input);
        return -EIO;
    }
    std::fclose(output);
    std::fclose(input);
    if (result != 0) std::remove(outputPath);
    return result;
}
