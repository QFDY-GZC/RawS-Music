#include "ai_vocal_activity.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <limits>
#include <numeric>
#include <sstream>
#include <utility>

namespace {
constexpr int kPcmFormat = 1;
constexpr int kFloatFormat = 3;
constexpr float kMinRms = 1.0e-7f;

uint16_t readLe16(const unsigned char* bytes) {
    return static_cast<uint16_t>(bytes[0]) |
        static_cast<uint16_t>(bytes[1]) << 8u;
}

uint32_t readLe32(const unsigned char* bytes) {
    return static_cast<uint32_t>(bytes[0]) |
        static_cast<uint32_t>(bytes[1]) << 8u |
        static_cast<uint32_t>(bytes[2]) << 16u |
        static_cast<uint32_t>(bytes[3]) << 24u;
}

bool hasTag(const unsigned char* bytes, const char* tag) {
    return std::memcmp(bytes, tag, 4) == 0;
}

float clamp01(float value) {
    return std::max(0.0f, std::min(1.0f, value));
}

float sampleFromBytes(
    const unsigned char* bytes,
    int formatTag,
    int bitsPerSample) {
    if (formatTag == kFloatFormat && bitsPerSample == 32) {
        float value = 0.0f;
        std::memcpy(&value, bytes, sizeof(value));
        return std::isfinite(value) ? std::max(-1.0f, std::min(1.0f, value)) : 0.0f;
    }
    if (formatTag != kPcmFormat) return 0.0f;
    if (bitsPerSample == 16) {
        const int16_t value = static_cast<int16_t>(readLe16(bytes));
        return static_cast<float>(value) / 32768.0f;
    }
    if (bitsPerSample == 24) {
        int32_t value = static_cast<int32_t>(bytes[0]) |
            static_cast<int32_t>(bytes[1]) << 8u |
            static_cast<int32_t>(bytes[2]) << 16u;
        if ((value & 0x00800000) != 0) value |= static_cast<int32_t>(0xff000000u);
        return static_cast<float>(value) / 8388608.0f;
    }
    if (bitsPerSample == 32) {
        const int32_t value = static_cast<int32_t>(readLe32(bytes));
        return static_cast<float>(value) / 2147483648.0f;
    }
    return 0.0f;
}

struct WavInfo {
    int formatTag = 0;
    int channels = 0;
    int sampleRate = 0;
    int bitsPerSample = 0;
    int blockAlign = 0;
    uint64_t dataOffset = 0;
    uint64_t dataBytes = 0;
};

bool readWavInfo(std::ifstream& file, WavInfo& info, std::string& error) {
    unsigned char header[12] = {};
    file.read(reinterpret_cast<char*>(header), sizeof(header));
    if (file.gcount() != static_cast<std::streamsize>(sizeof(header)) ||
        !hasTag(header, "RIFF") || !hasTag(header + 8, "WAVE")) {
        error = "Unsupported WAV container";
        return false;
    }

    bool foundFormat = false;
    bool foundData = false;
    while (file.good()) {
        unsigned char chunkHeader[8] = {};
        file.read(reinterpret_cast<char*>(chunkHeader), sizeof(chunkHeader));
        if (file.gcount() != static_cast<std::streamsize>(sizeof(chunkHeader))) break;
        const uint32_t chunkBytes = readLe32(chunkHeader + 4);
        const std::streampos chunkDataOffset = file.tellg();
        if (hasTag(chunkHeader, "fmt ")) {
            if (chunkBytes < 16u || chunkBytes > 4096u) {
                error = "Invalid WAV fmt chunk";
                return false;
            }
            std::vector<unsigned char> fmt(chunkBytes);
            file.read(reinterpret_cast<char*>(fmt.data()), static_cast<std::streamsize>(fmt.size()));
            if (file.gcount() != static_cast<std::streamsize>(fmt.size())) {
                error = "Truncated WAV fmt chunk";
                return false;
            }
            info.formatTag = static_cast<int>(readLe16(fmt.data()));
            info.channels = static_cast<int>(readLe16(fmt.data() + 2));
            info.sampleRate = static_cast<int>(readLe32(fmt.data() + 4));
            info.blockAlign = static_cast<int>(readLe16(fmt.data() + 12));
            info.bitsPerSample = static_cast<int>(readLe16(fmt.data() + 14));
            foundFormat = true;
        } else if (hasTag(chunkHeader, "data")) {
            info.dataOffset = static_cast<uint64_t>(chunkDataOffset);
            info.dataBytes = chunkBytes;
            foundData = true;
            file.seekg(static_cast<std::streamoff>(chunkBytes), std::ios::cur);
        } else {
            file.seekg(static_cast<std::streamoff>(chunkBytes), std::ios::cur);
        }
        if ((chunkBytes & 1u) != 0u) file.seekg(1, std::ios::cur);
        if (foundFormat && foundData) break;
    }

    if (!foundFormat || !foundData || info.channels <= 0 || info.channels > 8 ||
        info.sampleRate <= 0 || info.blockAlign <= 0 || info.dataBytes == 0) {
        error = "WAV is missing a usable fmt or data chunk";
        return false;
    }
    if ((info.formatTag != kPcmFormat && info.formatTag != kFloatFormat) ||
        (info.formatTag == kFloatFormat && info.bitsPerSample != 32) ||
        (info.formatTag == kPcmFormat &&
            info.bitsPerSample != 16 && info.bitsPerSample != 24 && info.bitsPerSample != 32)) {
        error = "Unsupported WAV sample format";
        return false;
    }
    const int expectedAlign = info.channels * info.bitsPerSample / 8;
    if (expectedAlign != info.blockAlign) {
        error = "WAV block alignment is invalid";
        return false;
    }
    return true;
}

class WavFrameReader {
public:
    WavFrameReader(std::ifstream& file, const WavInfo& info)
        : file_(file), info_(info), cursor_(0) {
        file_.clear();
        file_.seekg(static_cast<std::streamoff>(info_.dataOffset), std::ios::beg);
    }

    uint64_t totalFrames() const {
        return info_.dataBytes / static_cast<uint64_t>(info_.blockAlign);
    }

    uint64_t cursor() const { return cursor_; }

    size_t readFrames(size_t requested, std::vector<float>& destination) {
        const uint64_t remaining = totalFrames() > cursor_ ? totalFrames() - cursor_ : 0;
        const size_t frames = static_cast<size_t>(std::min<uint64_t>(remaining, requested));
        destination.assign(frames * static_cast<size_t>(info_.channels), 0.0f);
        if (frames == 0) return 0;
        const size_t bytes = frames * static_cast<size_t>(info_.blockAlign);
        raw_.resize(bytes);
        file_.read(reinterpret_cast<char*>(raw_.data()), static_cast<std::streamsize>(bytes));
        const size_t actualBytes = static_cast<size_t>(file_.gcount());
        const size_t actualFrames = actualBytes / static_cast<size_t>(info_.blockAlign);
        for (size_t frame = 0; frame < actualFrames; ++frame) {
            for (int channel = 0; channel < info_.channels; ++channel) {
                const size_t offset = frame * static_cast<size_t>(info_.blockAlign) +
                    static_cast<size_t>(channel) * static_cast<size_t>(info_.bitsPerSample / 8);
                destination[frame * static_cast<size_t>(info_.channels) +
                    static_cast<size_t>(channel)] = sampleFromBytes(
                        raw_.data() + offset, info_.formatTag, info_.bitsPerSample);
            }
        }
        cursor_ += actualFrames;
        return actualFrames;
    }

private:
    std::ifstream& file_;
    const WavInfo& info_;
    uint64_t cursor_;
    std::vector<unsigned char> raw_;
};

struct FrameFeature {
    int64_t timeMs = 0;
    float db = -120.0f;
    float diffDb = -120.0f;
};

float percentile(std::vector<float> values, float fraction) {
    if (values.empty()) return -120.0f;
    const size_t index = static_cast<size_t>(fraction * static_cast<float>(values.size() - 1));
    std::nth_element(values.begin(), values.begin() + index, values.end());
    return values[index];
}

void appendSpan(
    std::vector<AiVocalActivitySpan>& spans,
    int64_t startMs,
    int64_t endMs,
    float confidence,
    int mergeGapMs) {
    if (endMs <= startMs) return;
    if (!spans.empty() && startMs <= spans.back().endMs + mergeGapMs) {
        const int64_t previousDuration = spans.back().endMs - spans.back().startMs;
        const int64_t currentDuration = endMs - startMs;
        const float weighted = (spans.back().confidence * static_cast<float>(previousDuration) +
            confidence * static_cast<float>(currentDuration)) /
            static_cast<float>(previousDuration + currentDuration);
        spans.back().endMs = std::max(spans.back().endMs, endMs);
        spans.back().confidence = clamp01(weighted);
        return;
    }
    spans.push_back({startMs, endMs, clamp01(confidence)});
}

std::string jsonEscape(const std::string& value) {
    std::string result;
    result.reserve(value.size() + 8);
    for (const char character : value) {
        switch (character) {
            case '\\': result += "\\\\"; break;
            case '"': result += "\\\""; break;
            case '\n': result += "\\n"; break;
            case '\r': result += "\\r"; break;
            case '\t': result += "\\t"; break;
            default: result += character; break;
        }
    }
    return result;
}
}  // namespace

bool analyzeAiVocalActivity(
    const std::string& wavPath,
    const std::string& sourceIdentity,
    const std::string& analyzerVersion,
    const AiVocalActivityOptions& options,
    AiVocalActivityResult& result,
    std::string& error) {
    if (sourceIdentity.empty() || analyzerVersion.empty()) {
        error = "Voice activity identity is required";
        return false;
    }
    if (options.windowMs <= 0 || options.hopMs <= 0 || options.hopMs > options.windowMs ||
        options.startHangMs < 0 || options.stopHangMs < 0 || options.mergeGapMs < 0 ||
        options.preRollMs < 0 || options.postRollMs < 0) {
        error = "Invalid voice activity options";
        return false;
    }

    std::ifstream file(wavPath, std::ios::binary);
    if (!file.is_open()) {
        error = "Unable to open vocal WAV";
        return false;
    }
    WavInfo info;
    if (!readWavInfo(file, info, error)) return false;
    const uint64_t totalFrames = info.dataBytes / static_cast<uint64_t>(info.blockAlign);
    if (totalFrames == 0) {
        error = "Vocal WAV contains no frames";
        return false;
    }
    const int windowSamples = std::max(1, info.sampleRate * options.windowMs / 1000);
    const int hopSamples = std::max(1, info.sampleRate * options.hopMs / 1000);
    const int startHangFrames = std::max(1, (options.startHangMs + options.hopMs - 1) / options.hopMs);
    const int stopHangFrames = std::max(1, (options.stopHangMs + options.hopMs - 1) / options.hopMs);

    WavFrameReader reader(file, info);
    std::vector<float> window;
    size_t validInWindow = reader.readFrames(static_cast<size_t>(windowSamples), window);
    std::vector<FrameFeature> features;
    features.reserve(static_cast<size_t>(totalFrames / hopSamples + 2));
    std::vector<float> next;
    while (validInWindow > 0) {
        double energy = 0.0;
        double diffEnergy = 0.0;
        float peak = 0.0f;
        float previous = 0.0f;
        for (size_t frame = 0; frame < validInWindow; ++frame) {
            float mono = 0.0f;
            for (int channel = 0; channel < info.channels; ++channel) {
                mono += window[frame * static_cast<size_t>(info.channels) +
                    static_cast<size_t>(channel)];
            }
            mono /= static_cast<float>(info.channels);
            const float absolute = std::fabs(mono);
            energy += static_cast<double>(mono) * static_cast<double>(mono);
            peak = std::max(peak, absolute);
            if (frame > 0) {
                const float difference = mono - previous;
                diffEnergy += static_cast<double>(difference) * static_cast<double>(difference);
            }
            previous = mono;
        }
        const float rms = static_cast<float>(std::sqrt(energy / static_cast<double>(validInWindow)));
        const float diffRms = static_cast<float>(std::sqrt(
            diffEnergy / static_cast<double>(std::max<size_t>(1, validInWindow - 1))));
        const int64_t timeMs = static_cast<int64_t>(features.size()) * options.hopMs;
        features.push_back({
            timeMs,
            20.0f * std::log10(std::max(rms, kMinRms)),
            20.0f * std::log10(std::max(diffRms, kMinRms)),
        });

        if (reader.cursor() >= totalFrames) break;
        const size_t shift = static_cast<size_t>(std::min(hopSamples, windowSamples));
        const size_t retained = static_cast<size_t>(windowSamples) - shift;
        if (retained > 0) {
            std::memmove(
                window.data(),
                window.data() + shift * static_cast<size_t>(info.channels),
                retained * static_cast<size_t>(info.channels) * sizeof(float));
        }
        next.clear();
        const size_t readCount = reader.readFrames(static_cast<size_t>(shift), next);
        if (readCount > 0) {
            std::copy(
                next.begin(), next.end(),
                window.begin() + retained * static_cast<size_t>(info.channels));
        }
        const size_t filled = retained + readCount;
        if (filled < static_cast<size_t>(windowSamples)) {
            std::fill(
                window.begin() + filled * static_cast<size_t>(info.channels),
                window.end(), 0.0f);
        }
        validInWindow = std::min<size_t>(windowSamples, totalFrames - reader.cursor() + retained);
    }

    result = {};
    result.sourceIdentity = sourceIdentity;
    result.analyzerVersion = analyzerVersion;
    result.sampleRate = info.sampleRate;
    result.hopMs = options.hopMs;
    result.durationMs = static_cast<int64_t>(totalFrames * 1000u / static_cast<uint64_t>(info.sampleRate));
    if (features.empty()) return true;

    std::vector<float> dbValues;
    dbValues.reserve(features.size());
    for (const FrameFeature& feature : features) dbValues.push_back(feature.db);
    const float noiseFloor = percentile(dbValues, 0.20f);
    const float startThreshold = std::max(noiseFloor + 9.0f, -54.0f);
    const float stopThreshold = std::max(noiseFloor + 5.0f, -60.0f);
    const int mergeGapMs = options.mergeGapMs;
    int candidateStart = -1;
    int activeStart = -1;
    int quietFrames = 0;
    for (int index = 0; index < static_cast<int>(features.size()); ++index) {
        const FrameFeature& feature = features[static_cast<size_t>(index)];
        if (activeStart < 0) {
            if (feature.db >= startThreshold) {
                if (candidateStart < 0) candidateStart = index;
                if (index - candidateStart + 1 >= startHangFrames) {
                    activeStart = candidateStart;
                    candidateStart = -1;
                }
            } else {
                candidateStart = -1;
            }
            continue;
        }
        if (feature.db < stopThreshold) {
            ++quietFrames;
            if (quietFrames >= stopHangFrames) {
                const int endIndex = std::max(activeStart + 1, index - stopHangFrames + 1);
                const float averageDb = std::accumulate(
                    features.begin() + activeStart,
                    features.begin() + endIndex,
                    0.0f,
                    [](float sum, const FrameFeature& value) { return sum + value.db; }) /
                    static_cast<float>(endIndex - activeStart);
                const float confidence = clamp01((averageDb - noiseFloor) / 24.0f);
                const int64_t startMs = std::max<int64_t>(
                    0, features[static_cast<size_t>(activeStart)].timeMs - options.preRollMs);
                const int64_t endMs = std::min<int64_t>(
                    result.durationMs,
                    features[static_cast<size_t>(endIndex - 1)].timeMs +
                        options.windowMs + options.postRollMs);
                appendSpan(result.spans, startMs, endMs, confidence, mergeGapMs);
                activeStart = -1;
                quietFrames = 0;
            }
        } else {
            quietFrames = 0;
        }
    }
    if (activeStart >= 0) {
        const int endIndex = static_cast<int>(features.size());
        const float averageDb = std::accumulate(
            features.begin() + activeStart,
            features.end(),
            0.0f,
            [](float sum, const FrameFeature& value) { return sum + value.db; }) /
            static_cast<float>(endIndex - activeStart);
        const float confidence = clamp01((averageDb - noiseFloor) / 24.0f);
        const int64_t startMs = std::max<int64_t>(
            0, features[static_cast<size_t>(activeStart)].timeMs - options.preRollMs);
        const int64_t endMs = std::min<int64_t>(
            result.durationMs,
            features.back().timeMs + options.windowMs + options.postRollMs);
        appendSpan(result.spans, startMs, endMs, confidence, mergeGapMs);
    }
    return true;
}

std::string aiVocalActivityResultJson(const AiVocalActivityResult& result) {
    std::ostringstream json;
    json << "{\"schemaVersion\":1"
         << ",\"sourceIdentity\":\"" << jsonEscape(result.sourceIdentity) << "\""
         << ",\"analyzerVersion\":\"" << jsonEscape(result.analyzerVersion) << "\""
         << ",\"sampleRate\":" << result.sampleRate
         << ",\"hopMs\":" << result.hopMs
         << ",\"durationMs\":" << result.durationMs
         << ",\"spans\":[";
    for (size_t index = 0; index < result.spans.size(); ++index) {
        if (index != 0) json << ',';
        const AiVocalActivitySpan& span = result.spans[index];
        json << "{\"startMs\":" << span.startMs
             << ",\"endMs\":" << span.endMs
             << ",\"confidence\":" << span.confidence << '}';
    }
    json << "]}";
    return json.str();
}
