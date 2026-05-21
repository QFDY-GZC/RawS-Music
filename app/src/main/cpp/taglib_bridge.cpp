/**
 * TagLib-inspired WAV metadata reader for Android.
 * Reads RIFF INFO chunks and ID3v2 tags from WAV files.
 * 
 * Based on TagLib's RIFF::WAV::File and RIFF::Info::Tag implementations.
 * Provides comprehensive WAV metadata parsing without requiring the full TagLib build.
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <string>
#include <vector>
#include <map>
#include <algorithm>

#define LOG_TAG "TagLibBridge"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ========================== RIFF chunk parsing ==========================

/**
 * Read a 32-bit little-endian unsigned integer from buffer.
 */
static uint32_t read_le32(const uint8_t *p) {
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | 
           ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

/**
 * Read a 16-bit little-endian unsigned integer from buffer.
 */
static uint16_t read_le16(const uint8_t *p) {
    return (uint16_t)p[0] | ((uint16_t)p[1] << 8);
}

/**
 * Read a 32-bit big-endian unsigned integer from buffer (for ID3v2).
 */
static uint32_t read_be32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | 
           ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

/**
 * Read a 16-bit big-endian unsigned integer from buffer (for ID3v2).
 */
static uint16_t read_be16(const uint8_t *p) {
    return ((uint16_t)p[0] << 8) | (uint16_t)p[1];
}

/**
 * Read a 28-bit syncsafe integer (ID3v2 size encoding).
 */
static uint32_t read_syncsafe(const uint8_t *p) {
    return ((uint32_t)(p[0] & 0x7F) << 21) | ((uint32_t)(p[1] & 0x7F) << 14) |
           ((uint32_t)(p[2] & 0x7F) << 7) | (uint32_t)(p[3] & 0x7F);
}

/**
 * Check if a 4-byte chunk name is valid ASCII (printable characters).
 */
static bool isValidChunkName(const uint8_t *name) {
    for (int i = 0; i < 4; i++) {
        if (name[i] < 0x20 || name[i] > 0x7E) return false;
    }
    return true;
}

/**
 * Trim trailing whitespace and null bytes from a string.
 */
static std::string trimString(const std::string &s) {
    size_t end = s.find_last_not_of(" \t\r\n\0");
    return (end == std::string::npos) ? "" : s.substr(0, end + 1);
}

/**
 * Append a codepoint as UTF-8 to result string.
 */
static void appendUtf8(std::string &result, unsigned int cp) {
    if (cp < 0x80) {
        result += (char)cp;
    } else if (cp < 0x800) {
        result += (char)(0xC0 | (cp >> 6));
        result += (char)(0x80 | (cp & 0x3F));
    } else {
        result += (char)(0xE0 | (cp >> 12));
        result += (char)(0x80 | ((cp >> 6) & 0x3F));
        result += (char)(0x80 | (cp & 0x3F));
    }
}

/**
 * Sanitize a byte buffer to valid UTF-8 string.
 * Valid UTF-8 sequences are preserved as-is.
 * Invalid bytes are treated as ISO-8859-1 and converted to UTF-8 (preserves characters).
 * This prevents JNI NewStringUTF crashes on non-UTF-8 metadata.
 */
static std::string sanitizeToUtf8(const std::string &input) {
    std::string result;
    result.reserve(input.size() + input.size() / 4); // slightly larger for multi-byte expansion
    size_t i = 0;
    while (i < input.size()) {
        unsigned char c = (unsigned char)input[i];
        if (c < 0x80) {
            // ASCII
            result += (char)c;
            i++;
        } else if ((c & 0xE0) == 0xC0) {
            // Possible 2-byte UTF-8
            if (i + 1 < input.size() && ((unsigned char)input[i + 1] & 0xC0) == 0x80) {
                unsigned int cp = ((c & 0x1F) << 6) | ((unsigned char)input[i + 1] & 0x3F);
                if (cp >= 0x80) {
                    // Valid 2-byte UTF-8
                    result += input[i];
                    result += input[i + 1];
                    i += 2;
                } else {
                    // Overlong encoding, treat first byte as ISO-8859-1
                    appendUtf8(result, c);
                    i++;
                }
            } else {
                // Incomplete sequence, treat as ISO-8859-1
                appendUtf8(result, c);
                i++;
            }
        } else if ((c & 0xF0) == 0xE0) {
            // Possible 3-byte UTF-8
            if (i + 2 < input.size() && ((unsigned char)input[i + 1] & 0xC0) == 0x80 &&
                ((unsigned char)input[i + 2] & 0xC0) == 0x80) {
                unsigned int cp = ((c & 0x0F) << 12) |
                                  (((unsigned char)input[i + 1] & 0x3F) << 6) |
                                  ((unsigned char)input[i + 2] & 0x3F);
                if (cp >= 0x800 && !(cp >= 0xD800 && cp <= 0xDFFF)) {
                    // Valid 3-byte UTF-8
                    result += input[i];
                    result += input[i + 1];
                    result += input[i + 2];
                    i += 3;
                } else {
                    // Invalid, treat first byte as ISO-8859-1
                    appendUtf8(result, c);
                    i++;
                }
            } else {
                // Incomplete sequence, treat as ISO-8859-1
                appendUtf8(result, c);
                i++;
            }
        } else if ((c & 0xF8) == 0xF0) {
            // Possible 4-byte UTF-8
            if (i + 3 < input.size() && ((unsigned char)input[i + 1] & 0xC0) == 0x80 &&
                ((unsigned char)input[i + 2] & 0xC0) == 0x80 &&
                ((unsigned char)input[i + 3] & 0xC0) == 0x80) {
                unsigned int cp = ((c & 0x07) << 18) |
                                  (((unsigned char)input[i + 1] & 0x3F) << 12) |
                                  (((unsigned char)input[i + 2] & 0x3F) << 6) |
                                  ((unsigned char)input[i + 3] & 0x3F);
                if (cp >= 0x10000 && cp <= 0x10FFFF) {
                    // Valid 4-byte UTF-8
                    result += input[i];
                    result += input[i + 1];
                    result += input[i + 2];
                    result += input[i + 3];
                    i += 4;
                } else {
                    // Invalid, treat first byte as ISO-8859-1
                    appendUtf8(result, c);
                    i++;
                }
            } else {
                // Incomplete sequence, treat as ISO-8859-1
                appendUtf8(result, c);
                i++;
            }
        } else {
            // Invalid start byte (0x80-0xBF or 0xF8+), treat as ISO-8859-1
            appendUtf8(result, c);
            i++;
        }
    }
    return result;
}

// ========================== ID3v2 tag parsing ==========================

/**
 * ID3v2 text frame parser.
 * Handles ISO-8859-1, UTF-16, UTF-16BE, and UTF-8 encodings.
 */
static std::string parseID3v2TextFrame(const uint8_t *data, size_t size) {
    if (size < 1) return "";
    
    uint8_t encoding = data[0];
    const uint8_t *textStart = data + 1;
    size_t textSize = size - 1;
    
    switch (encoding) {
        case 0: { // ISO-8859-1
            std::string result;
            result.reserve(textSize);
            for (size_t i = 0; i < textSize; i++) {
                if (textStart[i] == 0) break;
                if (textStart[i] < 0x80) {
                    result += (char)textStart[i];
                } else {
                    // Convert ISO-8859-1 (U+0080..U+00FF) to UTF-8
                    result += (char)(0xC0 | (textStart[i] >> 6));
                    result += (char)(0x80 | (textStart[i] & 0x3F));
                }
            }
            return trimString(result);
        }
        case 1: { // UTF-16 with BOM
            if (textSize < 2) return "";
            bool bigEndian = (textStart[0] == 0xFE && textStart[1] == 0xFF);
            std::string result;
            size_t start = 2; // Skip BOM
            for (size_t i = start; i + 1 < textSize; i += 2) {
                uint16_t ch;
                if (bigEndian) {
                    ch = ((uint16_t)textStart[i] << 8) | textStart[i + 1];
                } else {
                    ch = ((uint16_t)textStart[i + 1] << 8) | textStart[i];
                }
                if (ch == 0) break;
                if (ch < 0x80) {
                    result += (char)ch;
                } else if (ch < 0x800) {
                    result += (char)(0xC0 | (ch >> 6));
                    result += (char)(0x80 | (ch & 0x3F));
                } else {
                    result += (char)(0xE0 | (ch >> 12));
                    result += (char)(0x80 | ((ch >> 6) & 0x3F));
                    result += (char)(0x80 | (ch & 0x3F));
                }
            }
            return trimString(result);
        }
        case 2: { // UTF-16BE without BOM
            std::string result;
            for (size_t i = 0; i + 1 < textSize; i += 2) {
                uint16_t ch = ((uint16_t)textStart[i] << 8) | textStart[i + 1];
                if (ch == 0) break;
                if (ch < 0x80) {
                    result += (char)ch;
                } else if (ch < 0x800) {
                    result += (char)(0xC0 | (ch >> 6));
                    result += (char)(0x80 | (ch & 0x3F));
                } else {
                    result += (char)(0xE0 | (ch >> 12));
                    result += (char)(0x80 | ((ch >> 6) & 0x3F));
                    result += (char)(0x80 | (ch & 0x3F));
                }
            }
            return trimString(result);
        }
        case 3: { // UTF-8
            std::string result;
            result.reserve(textSize);
            for (size_t i = 0; i < textSize; i++) {
                if (textStart[i] == 0) break;
                result += (char)textStart[i];
            }
            return trimString(result);
        }
        default:
            return "";
    }
}

/**
 * Parse ID3v2 tag from buffer.
 * Supports ID3v2.3 and ID3v2.4.
 */
static void parseID3v2Tag(const uint8_t *data, size_t size, 
                          std::map<std::string, std::string> &tags) {
    if (size < 10) return;
    
    // Verify ID3v2 header
    if (data[0] != 'I' || data[1] != 'D' || data[2] != '3') return;
    
    uint8_t versionMajor = data[3];
    uint8_t versionMinor = data[4];
    
    if (versionMajor < 3 || versionMajor > 4) {
        LOGD("parseID3v2Tag: Unsupported ID3v2 version: 2.%d.%d", versionMajor, versionMinor);
        return;
    }
    
    uint8_t flags = data[5];
    uint32_t tagSize = read_syncsafe(data + 6);
    
    LOGD("parseID3v2Tag: ID3v2.%d.%d, size=%u, flags=0x%02X", 
         versionMajor, versionMinor, tagSize, flags);
    
    // Skip extended header if present
    size_t pos = 10;
    if (flags & 0x40) { // Extended header flag
        if (pos + 4 > size) return;
        uint32_t extSize;
        if (versionMajor == 4) {
            extSize = read_syncsafe(data + pos);
        } else {
            extSize = read_be32(data + pos);
        }
        pos += extSize;
    }
    
    // Parse frames
    size_t endPos = std::min((size_t)(10 + tagSize), size);
    while (pos + 10 <= endPos) {
        // Read frame header
        char frameId[5] = {0};
        memcpy(frameId, data + pos, 4);
        
        // Check for padding
        if (frameId[0] == 0) break;
        
        uint32_t frameSize;
        if (versionMajor == 4) {
            frameSize = read_syncsafe(data + pos + 4);
        } else {
            frameSize = read_be32(data + pos + 4);
        }
        
        uint16_t frameFlags = read_be16(data + pos + 8);
        
        pos += 10;
        
        if (frameSize == 0 || pos + frameSize > endPos) break;
        
        // Parse text frames
        if (frameId[0] == 'T' && strcmp(frameId, "TXXX") != 0) {
            std::string value = parseID3v2TextFrame(data + pos, frameSize);
            if (!value.empty()) {
                tags[frameId] = value;
                LOGD("parseID3v2Tag: %s = '%s'", frameId, value.c_str());
            }
        }
        // Parse comment frames (COMM)
        else if (strcmp(frameId, "COMM") == 0) {
            if (frameSize > 4) {
                std::string comment = parseID3v2TextFrame(data + pos + 4, frameSize - 4);
                if (!comment.empty()) {
                    tags["COMM"] = comment;
                    LOGD("parseID3v2Tag: COMM = '%s'", comment.c_str());
                }
            }
        }
        // Parse unsynchronized lyrics (USLT)
        else if (strcmp(frameId, "USLT") == 0) {
            if (frameSize > 4) {
                std::string lyrics = parseID3v2TextFrame(data + pos + 4, frameSize - 4);
                if (!lyrics.empty()) {
                    tags["USLT"] = lyrics;
                    LOGD("parseID3v2Tag: USLT = '%s'", lyrics.c_str());
                }
            }
        }
        
        pos += frameSize;
    }
}

// ========================== RIFF INFO chunk parsing ==========================

/**
 * RIFF INFO field mapping (TagLib-compatible).
 */
static const std::map<std::string, std::string> infoFieldMap = {
    {"INAM", "title"},      // Title
    {"IART", "artist"},     // Artist
    {"IPRD", "album"},      // Product/Album
    {"IGNR", "genre"},      // Genre
    {"ICRD", "year"},       // Creation date
    {"IPRT", "track"},      // Track number
    {"ISFT", "encoder"},    // Software/Encoder
    {"IENG", "engineer"},   // Engineer
    {"IMUS", "composer"},   // Music by
    {"ICMT", "comment"},    // Comment
    {"ICOP", "copyright"},  // Copyright
    {"ISRC", "isrc"},       // Source
    {"ITCH", "encoded_by"}, // Encoded by
    {"IWRI", "writer"},     // Writer
    {"IBPM", "bpm"},        // BPM
    {"ILNG", "language"},   // Language
    {"IMED", "media"},      // Media type
    {"ISTR", "performer"},  // Performer
    {"IPUB", "label"},      // Label/Publisher
    {"ICNT", "country"},    // Country
    {"IBSU", "website"},    // Website
    {"IEDT", "remixer"},    // Remixed by
};

/**
 * Parse RIFF INFO chunk from LIST INFO data.
 */
static void parseRiffInfoChunk(const uint8_t *data, size_t size,
                               std::map<std::string, std::string> &tags) {
    // data should start with "INFO"
    if (size < 4 || data[0] != 'I' || data[1] != 'N' || 
        data[2] != 'F' || data[3] != 'O') {
        return;
    }
    
    size_t pos = 4;
    while (pos + 8 <= size) {
        // Read chunk ID (4 bytes)
        char chunkId[5] = {0};
        memcpy(chunkId, data + pos, 4);
        
        // Read chunk size (4 bytes, little-endian)
        uint32_t chunkSize = read_le32(data + pos + 4);
        pos += 8;
        
        if (chunkSize == 0 || pos + chunkSize > size) break;
        
        // Extract text value (null-terminated)
        std::string value;
        value.reserve(chunkSize);
        for (uint32_t i = 0; i < chunkSize; i++) {
            if (data[pos + i] == 0) break;
            value += (char)data[pos + i];
        }
        value = trimString(value);
        
        if (!value.empty()) {
            // Map to standard field name
            auto it = infoFieldMap.find(chunkId);
            if (it != infoFieldMap.end()) {
                tags[it->second] = value;
                LOGD("parseRiffInfoChunk: %s (%s) = '%s'", 
                     chunkId, it->second.c_str(), value.c_str());
            } else {
                // Store with original chunk ID
                tags[chunkId] = value;
                LOGD("parseRiffInfoChunk: %s = '%s'", chunkId, value.c_str());
            }
        }
        
        // Advance to next chunk (word-aligned)
        pos += (chunkSize + 1) & ~1;
    }
}

// ========================== WAV audio properties ==========================

struct WavAudioProperties {
    int sampleRate;
    int channels;
    int bitsPerSample;
    int bitRate;
    int64_t durationMs;
    int format; // 1=PCM, 3=IEEE float, etc.
    uint64_t sampleFrames;
};

/**
 * Read WAV audio properties from fmt chunk data.
 */
static bool readWavAudioProperties(const uint8_t *fmtData, size_t fmtSize,
                                   uint64_t dataSize,
                                   WavAudioProperties &props) {
    if (fmtSize < 16) return false;
    
    props.format = read_le16(fmtData);           // Audio format (1=PCM, 3=IEEE float)
    props.channels = read_le16(fmtData + 2);     // Number of channels
    props.sampleRate = read_le32(fmtData + 4);   // Sample rate
    // uint32_t byteRate = read_le32(fmtData + 8); // Byte rate
    // uint16_t blockAlign = read_le16(fmtData + 12); // Block align
    
    if (fmtSize >= 18) {
        props.bitsPerSample = read_le16(fmtData + 14); // Bits per sample
    } else {
        props.bitsPerSample = 16; // Default for PCM
    }
    
    // Calculate bit rate and duration
    if (props.sampleRate > 0 && props.channels > 0 && props.bitsPerSample > 0) {
        props.bitRate = props.sampleRate * props.channels * props.bitsPerSample; // bps (not kbps)
        
        uint64_t bytesPerSample = props.bitsPerSample / 8;
        if (bytesPerSample > 0 && props.channels > 0) {
            uint64_t bytesPerFrame = bytesPerSample * props.channels;
            if (bytesPerFrame > 0) {
                props.sampleFrames = dataSize / bytesPerFrame;
                props.durationMs = (int64_t)(props.sampleFrames * 1000 / props.sampleRate);
            }
        }
    }
    
    LOGD("readWavAudioProperties: format=%d, sr=%d, ch=%d, bps=%d, br=%d, dur=%lldms",
         props.format, props.sampleRate, props.channels, props.bitsPerSample,
         props.bitRate, props.durationMs);
    
    return true;
}

// ========================== Main WAV metadata reader ==========================

/**
 * Read all metadata from a WAV file.
 * Returns a map of tag names to values.
 */
static std::map<std::string, std::string> readWavMetadata(const char *filePath) {
    std::map<std::string, std::string> result;
    
    FILE *fp = fopen(filePath, "rb");
    if (!fp) {
        LOGE("readWavMetadata: Cannot open file: %s", filePath);
        return result;
    }
    
    // Read RIFF header
    uint8_t header[12];
    if (fread(header, 1, 12, fp) != 12) {
        LOGE("readWavMetadata: Cannot read RIFF header");
        fclose(fp);
        return result;
    }
    
    // Verify RIFF/WAVE signature
    if (header[0] != 'R' || header[1] != 'I' || header[2] != 'F' || header[3] != 'F') {
        LOGE("readWavMetadata: Not a RIFF file");
        fclose(fp);
        return result;
    }
    if (header[8] != 'W' || header[9] != 'A' || header[10] != 'V' || header[11] != 'E') {
        LOGE("readWavMetadata: Not a WAVE file");
        fclose(fp);
        return result;
    }
    
    LOGI("readWavMetadata: Processing WAV file: %s", filePath);
    
    // Read all chunks
    uint8_t fmtData[64] = {0};
    size_t fmtSize = 0;
    uint64_t dataSize = 0;
    bool hasFmt = false;
    
    while (true) {
        uint8_t chunkHeader[8];
        if (fread(chunkHeader, 1, 8, fp) != 8) break;
        
        char chunkId[5] = {0};
        memcpy(chunkId, chunkHeader, 4);
        uint32_t chunkSize = read_le32(chunkHeader + 4);
        
        LOGD("readWavMetadata: Chunk '%s', size=%u", chunkId, chunkSize);
        
        if (chunkSize == 0) {
            // Skip zero-size chunks
            continue;
        }
        
        // Handle known chunk types
        if (strcmp(chunkId, "fmt ") == 0) {
            // fmt chunk - audio format info
            size_t readSize = std::min((size_t)chunkSize, sizeof(fmtData));
            if (fread(fmtData, 1, readSize, fp) == readSize) {
                fmtSize = readSize;
                hasFmt = true;
            }
            // Skip any remaining bytes
            if (chunkSize > readSize) {
                fseek(fp, chunkSize - readSize, SEEK_CUR);
            }
        }
        else if (strcmp(chunkId, "data") == 0) {
            // data chunk - audio data
            dataSize = chunkSize;
            // Don't read the actual audio data, just skip it
            fseek(fp, chunkSize, SEEK_CUR);
        }
        else if (strcmp(chunkId, "LIST") == 0) {
            // LIST chunk - may contain INFO
            std::vector<uint8_t> listData(chunkSize);
            if (fread(listData.data(), 1, chunkSize, fp) == chunkSize) {
                // Check if this is a LIST INFO chunk
                if (chunkSize >= 4 && listData[0] == 'I' && listData[1] == 'N' && 
                    listData[2] == 'F' && listData[3] == 'O') {
                    parseRiffInfoChunk(listData.data(), chunkSize, result);
                }
            }
        }
        else if (strcmp(chunkId, "ID3 ") == 0 || strcmp(chunkId, "id3 ") == 0) {
            // ID3v2 chunk
            std::vector<uint8_t> id3Data(chunkSize);
            if (fread(id3Data.data(), 1, chunkSize, fp) == chunkSize) {
                parseID3v2Tag(id3Data.data(), chunkSize, result);
            }
        }
        else if (strcmp(chunkId, "iXML") == 0) {
            // iXML chunk - store as raw data
            std::vector<uint8_t> ixmlData(chunkSize);
            if (fread(ixmlData.data(), 1, chunkSize, fp) == chunkSize) {
                std::string ixml(ixmlData.begin(), ixmlData.end());
                result["iXML"] = ixml;
                LOGD("readWavMetadata: iXML chunk found (%u bytes)", chunkSize);
            }
        }
        else if (strcmp(chunkId, "bext") == 0) {
            // Broadcast Audio Extension chunk
            std::vector<uint8_t> bextData(chunkSize);
            if (fread(bextData.data(), 1, chunkSize, fp) == chunkSize) {
                // Store description (first 256 bytes)
                std::string desc;
                for (int i = 0; i < 256 && i < (int)chunkSize; i++) {
                    if (bextData[i] == 0) break;
                    desc += (char)bextData[i];
                }
                if (!desc.empty()) {
                    result["bext_description"] = trimString(desc);
                }
                LOGD("readWavMetadata: bext chunk found (%u bytes)", chunkSize);
            }
        }
        else {
            // Unknown chunk - skip
            fseek(fp, chunkSize, SEEK_CUR);
        }
        
        // Chunks are word-aligned (even size)
        if (chunkSize & 1) {
            fseek(fp, 1, SEEK_CUR);
        }
    }
    
    fclose(fp);
    
    // Add audio properties to result
    if (hasFmt) {
        WavAudioProperties props = {0};
        if (readWavAudioProperties(fmtData, fmtSize, dataSize, props)) {
            char buf[64];
            
            snprintf(buf, sizeof(buf), "%d", props.sampleRate);
            result["sample_rate"] = buf;
            
            snprintf(buf, sizeof(buf), "%d", props.channels);
            result["channels"] = buf;
            
            snprintf(buf, sizeof(buf), "%d", props.bitsPerSample);
            result["bits_per_sample"] = buf;
            
            snprintf(buf, sizeof(buf), "%d", props.bitRate);
            result["bit_rate"] = buf;
            
            snprintf(buf, sizeof(buf), "%lld", props.durationMs);
            result["duration_ms"] = buf;
            
            snprintf(buf, sizeof(buf), "%.6f", props.durationMs / 1000.0);
            result["duration"] = buf;
            
            snprintf(buf, sizeof(buf), "%llu", props.sampleFrames);
            result["sample_frames"] = buf;
            
            snprintf(buf, sizeof(buf), "%d", props.format);
            result["format_code"] = buf;
            
            result["format_name"] = "wav";
            result["codec_name"] = (props.format == 3) ? "pcm_f32le" : 
                                   (props.bitsPerSample <= 16) ? "pcm_s16le" : "pcm_s32le";
        }
    }
    
    // When ID3v2 tags exist, remove RIFF INFO keys that would be garbled
    // (ID3v2 has proper encoding declarations; RIFF INFO may be EUC-JIS/Shift-JIS)
    static const std::vector<std::pair<std::string, std::string>> id3v2Overrides = {
        {"TALB", "album"}, {"TIT2", "title"}, {"TPE1", "artist"},
        {"TCON", "genre"}, {"TCOM", "composer"}, {"TPE2", "albumArtist"},
        {"TRCK", "track"}, {"TYER", "year"}, {"TDRC", "year"},
        {"TBPM", "bpm"}, {"TSRC", "isrc"}, {"TIT1", "grouping"},
        {"TPOS", "discNumber"}, {"TSSE", "encoder"}
    };
    for (const auto &ov : id3v2Overrides) {
        if (result.count(ov.first) && result.count(ov.second)) {
            result.erase(ov.second);
        }
    }
    // Also remove RIFF INFO keys that map to same fields
    static const std::vector<std::string> riffInfoKeys = {
        "INAM", "IART", "IPRD", "IGNR", "ICRD", "IPRT", "ISFT", "IENG", "IMUS", "IBPM"
    };
    for (const auto &k : riffInfoKeys) {
        if (result.count(k)) {
            // Only keep if no ID3v2 equivalent exists
            bool hasId3v2 = false;
            for (const auto &ov : id3v2Overrides) {
                if (ov.second == infoFieldMap.at(k) && result.count(ov.first)) {
                    hasId3v2 = true;
                    break;
                }
            }
            if (hasId3v2) {
                result.erase(k);
            }
        }
    }
    
    // Log all found tags
    LOGI("readWavMetadata: Found %zu tags", result.size());
    for (const auto &pair : result) {
        LOGD("  %s = '%s'", pair.first.c_str(), pair.second.c_str());
    }
    
    return result;
}

// ========================== JNI bridge ==========================

extern "C" JNIEXPORT jobject JNICALL
Java_com_rawsmusic_core_common_taglib_TagLibBridge_nativeReadWavMetadata(
    JNIEnv *env, jobject, jstring path) {
    
    const char *filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath) {
        LOGE("nativeReadWavMetadata: null path");
        return nullptr;
    }
    
    LOGI("nativeReadWavMetadata: %s", filePath);
    
    auto metadata = readWavMetadata(filePath);
    
    env->ReleaseStringUTFChars(path, filePath);
    
    // Create Java HashMap
    jclass mapClass = env->FindClass("java/util/HashMap");
    jmethodID mapInit = env->GetMethodID(mapClass, "<init>", "(I)V");
    jmethodID mapPut = env->GetMethodID(mapClass, "put", 
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
    
    jobject map = env->NewObject(mapClass, mapInit, (jint)metadata.size());
    
    for (const auto &pair : metadata) {
        std::string safeKey = sanitizeToUtf8(pair.first);
        std::string safeVal = sanitizeToUtf8(pair.second);
        jstring jkey = env->NewStringUTF(safeKey.c_str());
        jstring jval = env->NewStringUTF(safeVal.c_str());
        env->CallObjectMethod(map, mapPut, jkey, jval);
        env->DeleteLocalRef(jkey);
        env->DeleteLocalRef(jval);
    }
    
    return map;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_core_common_taglib_TagLibBridge_nativeIsWavFile(
    JNIEnv *env, jobject, jstring path) {
    
    const char *filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath) return JNI_FALSE;
    
    FILE *fp = fopen(filePath, "rb");
    if (!fp) {
        env->ReleaseStringUTFChars(path, filePath);
        return JNI_FALSE;
    }
    
    uint8_t header[12];
    bool isWav = false;
    if (fread(header, 1, 12, fp) == 12) {
        isWav = (header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F' &&
                 header[8] == 'W' && header[9] == 'A' && header[10] == 'V' && header[11] == 'E');
    }
    
    fclose(fp);
    env->ReleaseStringUTFChars(path, filePath);
    
    return isWav ? JNI_TRUE : JNI_FALSE;
}
