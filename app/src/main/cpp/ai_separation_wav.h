#pragma once

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

class AiFloatWavWriter {
public:
    AiFloatWavWriter() = default;
    ~AiFloatWavWriter();

    bool open(const std::string& path, int sampleRate, std::string& error);
    bool writeStereo(float left, float right, std::string& error);
    bool flushProgress(std::string& error);
    bool finalize(std::string& error);
    int64_t framesWritten() const { return framesWritten_; }

private:
    bool flushBuffer(std::string& error);
    bool writeHeader(uint32_t dataBytes, std::string& error);
    static constexpr size_t kBufferedFrames = 32u * 1024u;
    FILE* file_ = nullptr;
    int sampleRate_ = 0;
    int64_t framesWritten_ = 0;
    bool finalized_ = false;
    std::vector<float> buffer_;
};
