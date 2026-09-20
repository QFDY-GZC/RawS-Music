#include "dsf_writer.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <limits>

namespace rawsmusic::transcode {
namespace {

constexpr uint64_t kDsdChunkSize = 28;
constexpr uint64_t kFmtChunkSize = 52;
constexpr uint64_t kDataHeaderSize = 12;
constexpr uint64_t kSampleDataOffset = 92;
constexpr uint32_t kDsfFormatVersion = 1;
constexpr uint32_t kDsfFormatIdRaw = 0;
constexpr uint32_t kDsfBitsPerSampleMsbFirst = 8;
constexpr uint32_t kDsfBlockSizePerChannel = 4096;

void writeU32(std::ostream& stream, uint32_t value) {
    const std::array<char, 4> bytes{
        static_cast<char>(value & 0xFFu),
        static_cast<char>((value >> 8u) & 0xFFu),
        static_cast<char>((value >> 16u) & 0xFFu),
        static_cast<char>((value >> 24u) & 0xFFu),
    };
    stream.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
}

void writeU64(std::ostream& stream, uint64_t value) {
    std::array<char, 8> bytes{};
    for (size_t index = 0; index < bytes.size(); ++index) {
        bytes[index] = static_cast<char>((value >> (index * 8u)) & 0xFFu);
    }
    stream.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
}

bool readExact(std::istream& stream, char* data, size_t size) {
    stream.read(data, static_cast<std::streamsize>(size));
    return stream.good() || stream.gcount() == static_cast<std::streamsize>(size);
}

bool readU32(std::istream& stream, uint32_t& value) {
    std::array<unsigned char, 4> bytes{};
    if (!readExact(stream, reinterpret_cast<char*>(bytes.data()), bytes.size())) return false;
    value = static_cast<uint32_t>(bytes[0]) |
        (static_cast<uint32_t>(bytes[1]) << 8u) |
        (static_cast<uint32_t>(bytes[2]) << 16u) |
        (static_cast<uint32_t>(bytes[3]) << 24u);
    return true;
}

bool readU64(std::istream& stream, uint64_t& value) {
    std::array<unsigned char, 8> bytes{};
    if (!readExact(stream, reinterpret_cast<char*>(bytes.data()), bytes.size())) return false;
    value = 0;
    for (size_t index = 0; index < bytes.size(); ++index) {
        value |= static_cast<uint64_t>(bytes[index]) << (index * 8u);
    }
    return true;
}

bool readFourCc(std::istream& stream, const char* expected) {
    std::array<char, 4> value{};
    return readExact(stream, value.data(), value.size()) &&
        std::memcmp(value.data(), expected, value.size()) == 0;
}

}  // namespace

DsfWriter::~DsfWriter() {
    close();
}

bool DsfWriter::open(const char* path, int dsd_sample_rate_hz, int channels) {
    close();
    if (!path || !*path || dsd_sample_rate_hz <= 0 || channels <= 0 || channels > 2) {
        return false;
    }

    path_ = path;
    sample_rate_hz_ = dsd_sample_rate_hz;
    channels_ = channels;
    block_fill_ = 0;
    logical_bytes_per_channel_ = 0;
    padded_audio_bytes_ = 0;
    for (auto& block : blocks_) block.fill(0u);

    file_.open(path_, std::ios::binary | std::ios::in | std::ios::out | std::ios::trunc);
    if (!file_.is_open()) {
        close();
        return false;
    }
    opened_ = true;
    finalized_ = false;
    if (!writeInitialHeader()) {
        close();
        return false;
    }
    return true;
}

bool DsfWriter::writeInitialHeader() {
    if (!opened_ || !file_.is_open()) return false;
    file_.seekp(0, std::ios::beg);
    file_.write("DSD ", 4);
    writeU64(file_, kDsdChunkSize);
    writeU64(file_, 0);  // total file size, patched on finalize
    writeU64(file_, 0);  // metadata offset, TagLib patches this after ID3v2 migration

    file_.write("fmt ", 4);
    writeU64(file_, kFmtChunkSize);
    writeU32(file_, kDsfFormatVersion);
    writeU32(file_, kDsfFormatIdRaw);
    writeU32(file_, channels_ == 1 ? 1u : 2u);  // DSF channel type: mono/stereo
    writeU32(file_, static_cast<uint32_t>(channels_));
    writeU32(file_, static_cast<uint32_t>(sample_rate_hz_));
    writeU32(file_, kDsfBitsPerSampleMsbFirst);
    writeU64(file_, 0);  // logical one-bit samples/channel, patched on finalize
    writeU32(file_, kDsfBlockSizePerChannel);
    writeU32(file_, 0);  // reserved

    file_.write("data", 4);
    writeU64(file_, kDataHeaderSize);  // patched with padded sample-data bytes
    return file_.good() && file_.tellp() == static_cast<std::streampos>(kSampleDataOffset);
}

bool DsfWriter::flushBlockGroup(bool pad_partial) {
    if (!opened_ || block_fill_ == 0) return true;
    if (block_fill_ < kBlockSize && !pad_partial) return true;

    if (block_fill_ < kBlockSize) {
        for (int channel = 0; channel < channels_; ++channel) {
            std::fill(
                blocks_[static_cast<size_t>(channel)].begin() + static_cast<std::ptrdiff_t>(block_fill_),
                blocks_[static_cast<size_t>(channel)].end(),
                0u
            );
        }
    }
    for (int channel = 0; channel < channels_; ++channel) {
        const auto& block = blocks_[static_cast<size_t>(channel)];
        file_.write(
            reinterpret_cast<const char*>(block.data()),
            static_cast<std::streamsize>(block.size())
        );
        if (!file_.good()) return false;
    }
    padded_audio_bytes_ += static_cast<uint64_t>(kBlockSize) * static_cast<uint64_t>(channels_);
    block_fill_ = 0;
    for (int channel = 0; channel < channels_; ++channel) {
        blocks_[static_cast<size_t>(channel)].fill(0u);
    }
    return true;
}

bool DsfWriter::writeChannelData(
    const uint8_t* channel0,
    const uint8_t* channel1,
    uint32_t bytes_per_channel
) {
    if (!opened_ || finalized_ || !channel0 || bytes_per_channel == 0u) return false;
    if (channels_ == 2 && !channel1) return false;

    uint32_t offset = 0u;
    while (offset < bytes_per_channel) {
        const size_t available = kBlockSize - block_fill_;
        const size_t copyBytes = std::min<size_t>(
            available,
            static_cast<size_t>(bytes_per_channel - offset)
        );
        std::memcpy(
            blocks_[0].data() + block_fill_,
            channel0 + offset,
            copyBytes
        );
        if (channels_ == 2) {
            std::memcpy(
                blocks_[1].data() + block_fill_,
                channel1 + offset,
                copyBytes
            );
        }
        block_fill_ += copyBytes;
        offset += static_cast<uint32_t>(copyBytes);
        logical_bytes_per_channel_ += static_cast<uint64_t>(copyBytes);
        if (block_fill_ == kBlockSize && !flushBlockGroup(false)) return false;
    }
    return true;
}

bool DsfWriter::patchU64(std::streamoff offset, uint64_t value) {
    file_.seekp(offset, std::ios::beg);
    if (!file_.good()) return false;
    writeU64(file_, value);
    return file_.good();
}

bool DsfWriter::finalize() {
    if (!opened_ || finalized_ || logical_bytes_per_channel_ == 0u) return false;
    if (!flushBlockGroup(true)) return false;

    const uint64_t sampleCount = logicalSampleCount();
    const uint64_t dataChunkSize = kDataHeaderSize + padded_audio_bytes_;
    const uint64_t fileSize = kSampleDataOffset + padded_audio_bytes_;
    if (!patchU64(12, fileSize) ||
        !patchU64(64, sampleCount) ||
        !patchU64(84, dataChunkSize)) {
        return false;
    }

    file_.seekp(0, std::ios::end);
    file_.flush();
    if (!file_.good()) return false;
    finalized_ = true;
    file_.close();
    opened_ = false;
    return true;
}

void DsfWriter::close() {
    if (file_.is_open()) file_.close();
    path_.clear();
    sample_rate_hz_ = 0;
    channels_ = 0;
    block_fill_ = 0;
    logical_bytes_per_channel_ = 0;
    padded_audio_bytes_ = 0;
    opened_ = false;
    finalized_ = false;
    for (auto& block : blocks_) block.fill(0u);
}

bool probeDsfFile(const char* path, DsfProbeResult& result) {
    result = {};
    if (!path || !*path) return false;
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) return false;

    file.seekg(0, std::ios::end);
    const std::streamoff physicalLength = file.tellg();
    if (physicalLength < static_cast<std::streamoff>(kSampleDataOffset)) return false;
    file.seekg(0, std::ios::beg);

    uint64_t dsdChunkSize = 0;
    uint64_t headerFileSize = 0;
    uint64_t metadataOffset = 0;
    if (!readFourCc(file, "DSD ") ||
        !readU64(file, dsdChunkSize) ||
        !readU64(file, headerFileSize) ||
        !readU64(file, metadataOffset)) {
        return false;
    }
    if (dsdChunkSize != kDsdChunkSize ||
        headerFileSize < kSampleDataOffset ||
        headerFileSize > static_cast<uint64_t>(physicalLength) ||
        metadataOffset > headerFileSize) {
        return false;
    }

    uint64_t fmtChunkSize = 0;
    uint32_t formatVersion = 0;
    uint32_t formatId = 0;
    uint32_t channelType = 0;
    uint32_t channels = 0;
    uint32_t sampleRate = 0;
    uint32_t bitsPerSample = 0;
    uint64_t sampleCount = 0;
    uint32_t blockSize = 0;
    uint32_t reserved = 0;
    if (!readFourCc(file, "fmt ") ||
        !readU64(file, fmtChunkSize) ||
        !readU32(file, formatVersion) ||
        !readU32(file, formatId) ||
        !readU32(file, channelType) ||
        !readU32(file, channels) ||
        !readU32(file, sampleRate) ||
        !readU32(file, bitsPerSample) ||
        !readU64(file, sampleCount) ||
        !readU32(file, blockSize) ||
        !readU32(file, reserved)) {
        return false;
    }
    if (fmtChunkSize != kFmtChunkSize ||
        formatVersion != kDsfFormatVersion ||
        formatId != kDsfFormatIdRaw ||
        channels == 0u || channels > 2u ||
        channelType != (channels == 1u ? 1u : 2u) ||
        sampleRate == 0u ||
        bitsPerSample != kDsfBitsPerSampleMsbFirst ||
        sampleCount == 0u ||
        blockSize != kDsfBlockSizePerChannel ||
        reserved != 0u) {
        return false;
    }

    uint64_t dataChunkSize = 0;
    if (!readFourCc(file, "data") || !readU64(file, dataChunkSize)) return false;
    if (dataChunkSize < kDataHeaderSize) return false;
    const uint64_t paddedDataBytes = dataChunkSize - kDataHeaderSize;
    const uint64_t blockGroupBytes = static_cast<uint64_t>(kDsfBlockSizePerChannel) * channels;
    if (blockGroupBytes == 0u || paddedDataBytes % blockGroupBytes != 0u) return false;
    if (kSampleDataOffset + paddedDataBytes > headerFileSize) return false;
    if (metadataOffset != 0u && metadataOffset < kSampleDataOffset + paddedDataBytes) return false;

    const uint64_t logicalBytesPerChannel = (sampleCount + 7u) / 8u;
    const uint64_t paddedBytesPerChannel = paddedDataBytes / channels;
    if (logicalBytesPerChannel > paddedBytesPerChannel) return false;

    result.sample_rate_hz = static_cast<int>(sampleRate);
    result.channels = static_cast<int>(channels);
    result.bits_per_sample = static_cast<int>(bitsPerSample);
    result.sample_count = sampleCount;
    result.block_size_per_channel = static_cast<int>(blockSize);
    result.duration_ms = static_cast<int64_t>(
        (sampleCount * 1000ULL + sampleRate / 2u) / sampleRate
    );
    result.file_size = headerFileSize;
    result.metadata_offset = metadataOffset;
    result.data_chunk_size = dataChunkSize;
    return true;
}

}  // namespace rawsmusic::transcode
