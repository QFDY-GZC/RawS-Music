#include "ai_separation_wav.h"

#include <cerrno>
#include <cstring>
#include <limits>

namespace {
bool writeBytes(FILE* file, const void* data, size_t size) {
    return file != nullptr && std::fwrite(data, 1, size, file) == size;
}

bool writeU16(FILE* file, uint16_t value) {
    const unsigned char bytes[2] = {
        static_cast<unsigned char>(value & 0xffu),
        static_cast<unsigned char>((value >> 8u) & 0xffu),
    };
    return writeBytes(file, bytes, sizeof(bytes));
}

bool writeU32(FILE* file, uint32_t value) {
    const unsigned char bytes[4] = {
        static_cast<unsigned char>(value & 0xffu),
        static_cast<unsigned char>((value >> 8u) & 0xffu),
        static_cast<unsigned char>((value >> 16u) & 0xffu),
        static_cast<unsigned char>((value >> 24u) & 0xffu),
    };
    return writeBytes(file, bytes, sizeof(bytes));
}
}  // namespace

AiFloatWavWriter::~AiFloatWavWriter() {
    if (file_ != nullptr) std::fclose(file_);
}

bool AiFloatWavWriter::open(const std::string& path, int sampleRate, std::string& error) {
    if (file_ != nullptr) {
        error = "WAV writer already open";
        return false;
    }
    file_ = std::fopen(path.c_str(), "wb+");
    if (file_ == nullptr) {
        error = std::string("Unable to create WAV: ") + std::strerror(errno);
        return false;
    }
    sampleRate_ = sampleRate;
    buffer_.reserve(kBufferedFrames * 2u);
    if (!writeHeader(0, error)) return false;
    return true;
}

bool AiFloatWavWriter::writeStereo(float left, float right, std::string& error) {
    if (file_ == nullptr || finalized_) {
        error = "WAV writer is not writable";
        return false;
    }
    if (framesWritten_ >= (static_cast<int64_t>(std::numeric_limits<uint32_t>::max()) - 44) / 8) {
        error = "32-bit RIFF WAV exceeds 4 GiB";
        return false;
    }
    buffer_.push_back(left);
    buffer_.push_back(right);
    ++framesWritten_;
    return buffer_.size() < kBufferedFrames * 2u || flushBuffer(error);
}

bool AiFloatWavWriter::flushBuffer(std::string& error) {
    if (buffer_.empty()) return true;
    if (std::fwrite(buffer_.data(), sizeof(float), buffer_.size(), file_) != buffer_.size()) {
        error = std::string("Unable to write WAV: ") + std::strerror(errno);
        return false;
    }
    buffer_.clear();
    return true;
}

bool AiFloatWavWriter::flushProgress(std::string& error) {
    if (file_ == nullptr || finalized_) {
        error = "WAV writer is not flushable";
        return false;
    }
    if (!flushBuffer(error)) return false;
    if (std::fflush(file_) != 0) {
        error = std::string("Unable to publish WAV progress: ") + std::strerror(errno);
        return false;
    }
    return true;
}

bool AiFloatWavWriter::finalize(std::string& error) {
    if (file_ == nullptr) {
        error = "WAV writer is not open";
        return false;
    }
    if (finalized_) return true;
    if (!flushBuffer(error)) return false;
    const uint64_t dataBytes64 = static_cast<uint64_t>(framesWritten_) * 8u;
    if (dataBytes64 > std::numeric_limits<uint32_t>::max() - 36u) {
        error = "32-bit RIFF WAV exceeds 4 GiB";
        return false;
    }
    if (std::fflush(file_) != 0 || std::fseek(file_, 0, SEEK_SET) != 0) {
        error = std::string("Unable to finalize WAV: ") + std::strerror(errno);
        return false;
    }
    if (!writeHeader(static_cast<uint32_t>(dataBytes64), error)) return false;
    if (std::fflush(file_) != 0) {
        error = std::string("Unable to flush WAV: ") + std::strerror(errno);
        return false;
    }
    finalized_ = true;
    return true;
}

bool AiFloatWavWriter::writeHeader(uint32_t dataBytes, std::string& error) {
    const uint32_t riffSize = 36u + dataBytes;
    const uint16_t formatTag = 3; // IEEE float
    const uint16_t channels = 2;
    const uint16_t bitsPerSample = 32;
    const uint16_t blockAlign = channels * bitsPerSample / 8;
    const uint32_t byteRate = static_cast<uint32_t>(sampleRate_) * blockAlign;
    if (!writeBytes(file_, "RIFF", 4) || !writeU32(file_, riffSize) ||
        !writeBytes(file_, "WAVE", 4) || !writeBytes(file_, "fmt ", 4) ||
        !writeU32(file_, 16) || !writeU16(file_, formatTag) ||
        !writeU16(file_, channels) || !writeU32(file_, static_cast<uint32_t>(sampleRate_)) ||
        !writeU32(file_, byteRate) || !writeU16(file_, blockAlign) ||
        !writeU16(file_, bitsPerSample) || !writeBytes(file_, "data", 4) ||
        !writeU32(file_, dataBytes)) {
        error = std::string("Unable to write WAV header: ") + std::strerror(errno);
        return false;
    }
    return true;
}
