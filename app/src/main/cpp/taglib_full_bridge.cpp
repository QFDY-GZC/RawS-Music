/**
 * TagLib 全格式元数据读取桥接（优化版）。
 *
 * 优化策略：
 * 1. 根据文件扩展名预分发到专用 reader，避免 FileRef 格式检测开销
 * 2. 只读取必要标签字段 + 基础音频属性
 * 3. JNI 异常安全检查
 */

#include <jni.h>
#include <android/log.h>
#include <array>
#include <cerrno>
#include <cstdint>
#include <string>
#include <map>
#include <cstring>
#include <cctype>
#include <fstream>
#include <limits>
#include <memory>
#include <cstdio>
#include <algorithm>
#include <sstream>
#include <vector>
#include <unistd.h>

// TagLib headers - 按格式专用
#include "mpegfile.h"
#include "mpeg/id3v2/id3v2tag.h"
#include "mpeg/id3v2/frames/attachedpictureframe.h"
#include "flacfile.h"
#include "flacpicture.h"
#include "ogg/flac/oggflacfile.h"
#include "ogg/vorbis/vorbisfile.h"
#include "ogg/opus/opusfile.h"
#include "mp4file.h"
#include "mp4tag.h"
#include "mp4item.h"
#include "mp4coverart.h"
#include "asffile.h"
#include "apefile.h"
#include "apetag.h"
#include "apeitem.h"
#include "riff/wav/wavfile.h"
#include "riff/aiff/aifffile.h"
#include "dsffile.h"
#include "dsdifffile.h"
#include "wavpackfile.h"
#include "trueaudiofile.h"
#include "mpcfile.h"
#include "tag.h"
#include "audioproperties.h"
#include "tstring.h"
#include "tpropertymap.h"

#define LOG_TAG "TagLibFull"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * 获取文件扩展名（小写，安全处理）
 */
static std::string getExtension(const char *path) {
    const char *dot = strrchr(path, '.');
    if (!dot) return "";
    std::string ext = dot + 1;
    for (auto &c : ext) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return ext;
}

/**
 * 从 Tag 对象读取必要标签字段
 */
static void readTagsFromTag(TagLib::Tag *tag, std::map<std::string, std::string> &result) {
    if (!tag) return;

    TagLib::String title = tag->title();
    TagLib::String artist = tag->artist();
    TagLib::String album = tag->album();
    TagLib::String genre = tag->genre();
    TagLib::String comment = tag->comment();
    unsigned int year = tag->year();
    unsigned int track = tag->track();

    if (!title.isEmpty()) result["title"] = title.to8Bit(true);
    if (!artist.isEmpty()) result["artist"] = artist.to8Bit(true);
    if (!album.isEmpty()) result["album"] = album.to8Bit(true);
    if (!genre.isEmpty()) result["genre"] = genre.to8Bit(true);
    if (!comment.isEmpty()) result["comment"] = comment.to8Bit(true);
    if (year > 0) result["year"] = std::to_string(year);
    if (track > 0) result["track"] = std::to_string(track);

    // The base Tag API above exposes only seven legacy fields. DSF/DFF use ID3v2 and would
    // otherwise lose album artist, composer, disc number, BPM, encoder, ISRC, lyrics and custom
    // PropertyMap-backed text when the scanner prefers TagLib. Export the canonical TagLib keys as
    // well; readAllTextProperties remains the lossless multi-value API used by the transcoder.
    const TagLib::PropertyMap properties = tag->properties();
    for (const auto &[key, values] : properties) {
        if (values.isEmpty()) continue;
        const std::string keyUtf8 = key.to8Bit(true);
        const std::string valueUtf8 = values.front().to8Bit(true);
        if (keyUtf8.empty() || valueUtf8.empty()) continue;
        result.emplace(keyUtf8, valueUtf8);
    }
}

/** MP4/M4A keeps free-form lyrics in the ©lyr item, which is not exposed by TagLib::Tag. */
static void readMp4Lyrics(TagLib::MP4::Tag *tag, std::map<std::string, std::string> &result) {
    if (!tag) return;
    const auto item = tag->item("\251lyr");
    if (!item.isValid()) return;

    const auto values = item.toStringList();
    if (!values.isEmpty()) {
        const auto lyrics = values.toString("\n");
        if (!lyrics.isEmpty()) result["lyrics"] = lyrics.to8Bit(true);
    }
}

/**
 * 从 AudioProperties 读取基础音频属性（仅基类共有字段）
 */
static void readAudioProps(TagLib::AudioProperties *props, std::map<std::string, std::string> &result) {
    if (!props) return;
    if (props->sampleRate() > 0) result["sample_rate"] = std::to_string(props->sampleRate());
    if (props->channels() > 0) result["channels"] = std::to_string(props->channels());
    if (props->bitrate() > 0) result["bit_rate"] = std::to_string(props->bitrate());
    if (props->lengthInMilliseconds() > 0) result["duration_ms"] = std::to_string(props->lengthInMilliseconds());
}

static void replaceProperty(TagLib::PropertyMap &properties, const char *name,
                            const std::map<std::string, std::string> &metadata,
                            const char *sourceName) {
    const auto value = metadata.find(sourceName);
    if (value == metadata.end()) return;
    const TagLib::String key(name, TagLib::String::UTF8);
    if (value->second.empty()) {
        properties.erase(key);
    } else {
        properties.replace(key, TagLib::StringList(
            TagLib::String(value->second, TagLib::String::UTF8)));
    }
}

static bool writeDsfId3v24Tag(const char *targetPath, const TagLib::ID3v2::Tag &tag);

static bool writeDsfMetadata(
    const char *filePath,
    const std::map<std::string, std::string> &metadata
) {
    TagLib::DSF::File file(filePath, false);
    if (!file.isValid() || !file.tag()) return false;
    TagLib::PropertyMap properties = file.properties();
    replaceProperty(properties, "TITLE", metadata, "title");
    replaceProperty(properties, "ARTIST", metadata, "artist");
    replaceProperty(properties, "ALBUM", metadata, "album");
    replaceProperty(properties, "ALBUMARTIST", metadata, "album_artist");
    replaceProperty(properties, "GENRE", metadata, "genre");
    replaceProperty(properties, "COMPOSER", metadata, "composer");
    replaceProperty(properties, "DATE", metadata, "date");
    replaceProperty(properties, "TRACKNUMBER", metadata, "track");
    replaceProperty(properties, "DISCNUMBER", metadata, "disc");
    replaceProperty(properties, "BPM", metadata, "bpm");
    const TagLib::PropertyMap rejected = file.tag()->setProperties(properties);
    if (!rejected.isEmpty()) return false;
    return writeDsfId3v24Tag(filePath, *file.tag());
}

template <typename FileType>
static bool writeMetadataToFile(FileType &file,
                                const std::map<std::string, std::string> &metadata) {
    if (!file.isValid()) return false;
    TagLib::PropertyMap properties = file.properties();
    replaceProperty(properties, "TITLE", metadata, "title");
    replaceProperty(properties, "ARTIST", metadata, "artist");
    replaceProperty(properties, "ALBUM", metadata, "album");
    replaceProperty(properties, "ALBUMARTIST", metadata, "album_artist");
    replaceProperty(properties, "GENRE", metadata, "genre");
    replaceProperty(properties, "COMPOSER", metadata, "composer");
    replaceProperty(properties, "DATE", metadata, "date");
    replaceProperty(properties, "TRACKNUMBER", metadata, "track");
    replaceProperty(properties, "DISCNUMBER", metadata, "disc");
    replaceProperty(properties, "BPM", metadata, "bpm");
    file.setProperties(properties);
    return file.save();
}

static bool writeMetadata(const char *filePath,
                          const std::map<std::string, std::string> &metadata) {
    const std::string ext = getExtension(filePath);
    if (ext == "mp3" || ext == "mp2" || ext == "mpga" || ext == "aac") {
        TagLib::MPEG::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "flac") {
        TagLib::FLAC::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "oga") {
        TagLib::Ogg::FLAC::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "ogg") {
        TagLib::Ogg::Vorbis::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "opus") {
        TagLib::Ogg::Opus::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "m4a" || ext == "m4b" || ext == "m4p" || ext == "mp4") {
        TagLib::MP4::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "wma" || ext == "asf") {
        TagLib::ASF::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "ape") {
        TagLib::APE::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "wav") {
        TagLib::RIFF::WAV::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "aiff" || ext == "aif") {
        TagLib::RIFF::AIFF::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "dsf") {
        return writeDsfMetadata(filePath, metadata);
    }
    if (ext == "dff" || ext == "dsdiff") {
        TagLib::DSDIFF::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "wv") {
        TagLib::WavPack::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "tta") {
        TagLib::TrueAudio::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    if (ext == "mpc" || ext == "mp+") {
        TagLib::MPC::File file(filePath, false); return writeMetadataToFile(file, metadata);
    }
    return false;
}


static TagLib::PropertyMap readAllTextProperties(const char *filePath, bool *validOut = nullptr) {
    if (validOut) *validOut = false;
    const std::string ext = getExtension(filePath);

    if (ext == "mp3" || ext == "mp2" || ext == "mpga" || ext == "aac") {
        TagLib::MPEG::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "flac") {
        TagLib::FLAC::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "oga") {
        TagLib::Ogg::FLAC::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "ogg") {
        TagLib::Ogg::Vorbis::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "opus") {
        TagLib::Ogg::Opus::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "m4a" || ext == "m4b" || ext == "m4p" || ext == "mp4") {
        TagLib::MP4::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "wma" || ext == "asf") {
        TagLib::ASF::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "ape") {
        TagLib::APE::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "wav") {
        TagLib::RIFF::WAV::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "aiff" || ext == "aif") {
        TagLib::RIFF::AIFF::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "dsf") {
        TagLib::DSF::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "dff" || ext == "dsdiff") {
        TagLib::DSDIFF::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "wv") {
        TagLib::WavPack::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "tta") {
        TagLib::TrueAudio::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    if (ext == "mpc" || ext == "mp+") {
        TagLib::MPC::File file(filePath, false);
        if (!file.isValid()) return {};
        if (validOut) *validOut = true;
        return file.properties();
    }
    return {};
}

static int propertyValueCount(const TagLib::PropertyMap &properties) {
    int count = 0;
    for (const auto &[key, values] : properties) {
        (void) key;
        count += static_cast<int>(values.size());
    }
    return count;
}

static jstring newJString(JNIEnv *env, const TagLib::String &value) {
    if (!env) return nullptr;
    const TagLib::ByteVector utf16 = value.data(TagLib::String::UTF16LE);
    const unsigned int codeUnits = utf16.size() / 2u;
    std::vector<jchar> chars(codeUnits);
    for (unsigned int index = 0; index < codeUnits; ++index) {
        const auto low = static_cast<unsigned char>(utf16[index * 2u]);
        const auto high = static_cast<unsigned char>(utf16[index * 2u + 1u]);
        chars[index] = static_cast<jchar>(
            static_cast<unsigned int>(low) | (static_cast<unsigned int>(high) << 8u)
        );
    }
    return env->NewString(
        chars.empty() ? nullptr : chars.data(),
        static_cast<jsize>(chars.size())
    );
}

static TagLib::StringList mergedPropertyValues(
    const TagLib::PropertyMap &source,
    const char *preservedKey,
    const char *sourceKey
) {
    TagLib::StringList values;
    const auto append = [&](const char *key) {
        const auto it = source.find(TagLib::String(key, TagLib::String::UTF8));
        if (it != source.end()) values.append(it->second);
    };
    append(preservedKey);
    append(sourceKey);
    return values;
}

static TagLib::PropertyMap desiredTranscodeProperties(
    const TagLib::PropertyMap &source,
    const char *encoderLabel
) {
    TagLib::PropertyMap desired(source);
    const TagLib::String encoderKey("ENCODER", TagLib::String::UTF8);
    const TagLib::String encodedByKey("ENCODEDBY", TagLib::String::UTF8);
    const TagLib::String originalEncoderKey("ORIGINAL_ENCODER", TagLib::String::UTF8);
    const TagLib::String originalEncodedByKey("ORIGINAL_ENCODEDBY", TagLib::String::UTF8);
    const auto originalEncoderValues = mergedPropertyValues(source, "ORIGINAL_ENCODER", "ENCODER");
    const auto originalEncodedByValues = mergedPropertyValues(source, "ORIGINAL_ENCODEDBY", "ENCODEDBY");

    // Encoder identity is technical metadata generated by the new file. Preserve the source
    // values under explicit ORIGINAL_* keys instead of falsely attributing the converted file to
    // the old codec/tool, while keeping every source value available after migration.
    desired.erase(encoderKey);
    desired.erase(encodedByKey);
    desired.erase(originalEncoderKey);
    desired.erase(originalEncodedByKey);
    if (!originalEncoderValues.isEmpty()) desired.replace(originalEncoderKey, originalEncoderValues);
    if (!originalEncodedByValues.isEmpty()) desired.replace(originalEncodedByKey, originalEncodedByValues);
    desired.replace(
        encoderKey,
        TagLib::StringList(TagLib::String(
            encoderLabel ? encoderLabel : "RawSMusic / FFmpeg",
            TagLib::String::UTF8
        ))
    );
    return desired;
}

/**
 * APEv2 exposes a handful of keys through the unified PropertyMap under names used by
 * FLAC/Xiph, then maps those names back to one APE key on write.  A source can contain
 * both spellings (for example TRACK and TRACKNUMBER), so a byte-for-byte PropertyMap
 * comparison after reopening an APE target would report a false loss.  Apply the same
 * merge order as APE::Tag::setProperties(), then expose the merged APE key through the
 * unified name used by APE::Tag::properties().
 */
static TagLib::PropertyMap normalizeApeRoundTripProperties(
    const TagLib::PropertyMap &source
) {
    TagLib::PropertyMap apeProperties(source);
    constexpr std::array aliases {
        std::pair("TRACKNUMBER", "TRACK"),
        std::pair("DATE", "YEAR"),
        std::pair("ALBUMARTIST", "ALBUM ARTIST"),
        std::pair("DISCNUMBER", "DISC"),
        std::pair("REMIXER", "MIXARTIST"),
        std::pair("RELEASESTATUS", "MUSICBRAINZ_ALBUMSTATUS"),
        std::pair("RELEASETYPE", "MUSICBRAINZ_ALBUMTYPE"),
    };

    // This is intentionally a two-pass operation. APE::Tag::setProperties() first
    // moves the canonical key into the raw APE spelling, appending to an existing raw
    // spelling, and only then does APE::Tag::properties() map that raw spelling back.
    for (const auto &[canonical, raw] : aliases) {
        const auto canonicalIt = apeProperties.find(canonical);
        if (canonicalIt == apeProperties.end()) continue;
        if (apeProperties.contains(raw)) {
            apeProperties[raw].append(canonicalIt->second);
        } else {
            apeProperties.insert(raw, canonicalIt->second);
        }
        apeProperties.erase(canonical);
    }

    TagLib::PropertyMap normalized;
    for (const auto &[key, values] : apeProperties) {
        bool converted = false;
        for (const auto &[canonical, raw] : aliases) {
            if (key == raw) {
                normalized.insert(canonical, values);
                converted = true;
                break;
            }
        }
        if (!converted) normalized.insert(key, values);
    }
    return normalized;
}

static std::string trimAsciiWhitespace(const std::string &value) {
    size_t begin = 0;
    while (begin < value.size() &&
           std::isspace(static_cast<unsigned char>(value[begin]))) {
        ++begin;
    }
    size_t end = value.size();
    while (end > begin &&
           std::isspace(static_cast<unsigned char>(value[end - 1]))) {
        --end;
    }
    return value.substr(begin, end - begin);
}

static bool parseMp4TrackNumberPart(const std::string &raw, uint32_t &value) {
    const std::string text = trimAsciiWhitespace(raw);
    if (text.empty()) return false;

    uint64_t parsed = 0;
    for (const char character : text) {
        if (character < '0' || character > '9') return false;
        parsed = parsed * 10u + static_cast<uint64_t>(character - '0');
        // MP4 trkn stores both values in a signed 16-bit slot in this TagLib
        // implementation. Refuse to rewrite an out-of-range value so strict
        // verification still reports malformed metadata instead of hiding it.
        if (parsed > 32767u) return false;
    }
    value = static_cast<uint32_t>(parsed);
    return true;
}

/**
 * MP4 stores TRACKNUMBER as an integer pair rather than a text field. A
 * source such as FLAC/APE may legally expose "01" or "01/10", while MP4
 * reopens it as "1" or "1/10". Canonicalize only this target-specific
 * representation before writing so strict verification compares the same
 * semantic value without relaxing validation for any other property.
 */
static bool canonicalizeMp4TrackNumber(TagLib::PropertyMap &properties) {
    const TagLib::String key("TRACKNUMBER", TagLib::String::UTF8);
    const auto it = properties.find(key);
    if (it == properties.end()) return true;
    if (it->second.size() != 1u) return false;

    const std::string raw = it->second.front().to8Bit(true);
    const size_t separator = raw.find('/');
    if (separator != std::string::npos && raw.find('/', separator + 1) != std::string::npos) {
        return false;
    }

    uint32_t track = 0;
    if (!parseMp4TrackNumberPart(
            separator == std::string::npos ? raw : raw.substr(0, separator),
            track
        )) {
        return false;
    }

    uint32_t total = 0;
    bool hasTotal = false;
    if (separator != std::string::npos) {
        const std::string totalText = trimAsciiWhitespace(raw.substr(separator + 1));
        if (!totalText.empty() && !parseMp4TrackNumberPart(totalText, total)) return false;
        hasTotal = total > 0u;
    }

    std::string canonical = std::to_string(track);
    if (hasTotal) canonical += '/' + std::to_string(total);
    it->second = TagLib::StringList(
        TagLib::String(canonical, TagLib::String::UTF8)
    );
    return true;
}

static bool startsWithAscii(const std::string &value, const char *prefix) {
    const std::string p(prefix ? prefix : "");
    return value.size() >= p.size() && std::equal(p.begin(), p.end(), value.begin());
}

static std::string lowerAscii(std::string value) {
    for (auto &c : value) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return value;
}

static bool endsWithAscii(const std::string &value, const char *suffix) {
    const std::string s(suffix ? suffix : "");
    return value.size() >= s.size() &&
        std::equal(s.rbegin(), s.rend(), value.rbegin());
}

static std::string imageMimeType(const TagLib::ByteVector &data, const std::string &fileName) {
    const auto byteAt = [&](unsigned int index) -> unsigned char {
        return index < data.size()
            ? static_cast<unsigned char>(data[index])
            : 0u;
    };
    if (data.size() >= 8 && byteAt(0) == 0x89 && byteAt(1) == 'P' && byteAt(2) == 'N' && byteAt(3) == 'G') {
        return "image/png";
    }
    if (data.size() >= 3 && byteAt(0) == 0xFF && byteAt(1) == 0xD8 && byteAt(2) == 0xFF) {
        return "image/jpeg";
    }
    if (data.size() >= 6 && byteAt(0) == 'G' && byteAt(1) == 'I' && byteAt(2) == 'F') {
        return "image/gif";
    }
    if (data.size() >= 2 && byteAt(0) == 'B' && byteAt(1) == 'M') {
        return "image/bmp";
    }
    if (data.size() >= 12 && byteAt(0) == 'R' && byteAt(1) == 'I' && byteAt(2) == 'F' && byteAt(3) == 'F' &&
        byteAt(8) == 'W' && byteAt(9) == 'E' && byteAt(10) == 'B' && byteAt(11) == 'P') {
        return "image/webp";
    }
    const auto lower = lowerAscii(fileName);
    if (endsWithAscii(lower, ".jpg") || endsWithAscii(lower, ".jpeg")) return "image/jpeg";
    if (endsWithAscii(lower, ".png")) return "image/png";
    if (endsWithAscii(lower, ".gif")) return "image/gif";
    if (endsWithAscii(lower, ".bmp")) return "image/bmp";
    if (endsWithAscii(lower, ".webp")) return "image/webp";
    return "application/octet-stream";
}

struct MigratedPicture {
    TagLib::FLAC::Picture::Type type = TagLib::FLAC::Picture::Other;
    std::string description;
    std::string mime;
    TagLib::ByteVector data;
};

static bool appendMp4PictureSemanticProperties(
    const std::vector<MigratedPicture> &pictures,
    TagLib::PropertyMap &properties
) {
    bool needsSidecar = false;
    for (const auto &picture : pictures) {
        if (picture.type != TagLib::FLAC::Picture::FrontCover || !picture.description.empty()) {
            needsSidecar = true;
            break;
        }
    }
    if (!needsSidecar) return false;

    TagLib::StringList types;
    TagLib::StringList descriptions;
    for (const auto &picture : pictures) {
        types.append(TagLib::FLAC::Picture::typeToString(picture.type));
        descriptions.append(TagLib::String(picture.description, TagLib::String::UTF8));
    }

    // MP4 covr has no role/description fields. Keep an ordered sidecar in free-form iTunes
    // atoms so RawSMusic (and other tag readers that expose free-form properties) can recover
    // the source picture semantics without changing the standard covr payload.
    properties.replace(
        TagLib::String("RAWSMUSIC_PICTURE_TYPES", TagLib::String::UTF8),
        types
    );
    properties.replace(
        TagLib::String("RAWSMUSIC_PICTURE_DESCRIPTIONS", TagLib::String::UTF8),
        descriptions
    );
    return true;
}

static TagLib::FLAC::Picture::Type apeCoverType(const std::string &keyUpper) {
    if (keyUpper == "COVER ART (FRONT)") return TagLib::FLAC::Picture::FrontCover;
    if (keyUpper == "COVER ART (BACK)") return TagLib::FLAC::Picture::BackCover;
    if (keyUpper == "COVER ART (LEAFLET)") return TagLib::FLAC::Picture::LeafletPage;
    if (keyUpper == "COVER ART (MEDIA)") return TagLib::FLAC::Picture::Media;
    if (keyUpper == "COVER ART (LEAD ARTIST)") return TagLib::FLAC::Picture::LeadArtist;
    if (keyUpper == "COVER ART (ARTIST)") return TagLib::FLAC::Picture::Artist;
    if (keyUpper == "COVER ART (CONDUCTOR)") return TagLib::FLAC::Picture::Conductor;
    if (keyUpper == "COVER ART (BAND)") return TagLib::FLAC::Picture::Band;
    if (keyUpper == "COVER ART (COMPOSER)") return TagLib::FLAC::Picture::Composer;
    if (keyUpper == "COVER ART (LYRICIST)") return TagLib::FLAC::Picture::Lyricist;
    if (keyUpper == "COVER ART (ILLUSTRATION)") return TagLib::FLAC::Picture::Illustration;
    if (keyUpper == "COVER ART (BAND LOGO)") return TagLib::FLAC::Picture::BandLogo;
    if (keyUpper == "COVER ART (PUBLISHER LOGO)") return TagLib::FLAC::Picture::PublisherLogo;
    return TagLib::FLAC::Picture::Other;
}

static bool decodeApeCoverItem(
    const TagLib::String &key,
    const TagLib::APE::Item &item,
    MigratedPicture &picture
) {
    if (item.type() != TagLib::APE::Item::Binary) return false;
    const std::string keyUpper = key.upper().to8Bit(true);
    if (!startsWithAscii(keyUpper, "COVER ART (")) return false;
    const auto raw = item.binaryData();
    if (raw.isEmpty()) return false;

    int separator = -1;
    // APEv2 cover items normally start with a UTF-8 file name followed by NUL, but
    // a number of writers omit that name and store the image bytes directly. TagLib's
    // own APE complex-property reader accepts that form for JPEG/PNG; keep the same
    // tolerance here so strict migration does not turn a valid cover into an
    // unsupported binary item.
    const bool startsWithJpeg = raw.size() >= 3 &&
        static_cast<unsigned char>(raw[0]) == 0xFF &&
        static_cast<unsigned char>(raw[1]) == 0xD8 &&
        static_cast<unsigned char>(raw[2]) == 0xFF;
    const bool startsWithPng = raw.size() >= 8 &&
        static_cast<unsigned char>(raw[0]) == 0x89 &&
        raw[1] == 'P' && raw[2] == 'N' && raw[3] == 'G';
    if (!startsWithJpeg && !startsWithPng) {
        for (unsigned int index = 0; index < raw.size(); ++index) {
            if (raw[index] == '\0') {
                separator = static_cast<int>(index);
                break;
            }
        }
    }
    const TagLib::ByteVector fileNameBytes = separator >= 0
        ? raw.mid(0, separator)
        : TagLib::ByteVector();
    const TagLib::ByteVector imageData = separator >= 0
        ? raw.mid(separator + 1)
        : raw;
    if (imageData.isEmpty()) return false;

    picture.type = apeCoverType(keyUpper);
    picture.description = TagLib::String(fileNameBytes, TagLib::String::UTF8).to8Bit(true);
    if (picture.description.empty()) picture.description = keyUpper;
    picture.mime = imageMimeType(imageData, picture.description);
    picture.data = imageData;
    return true;
}

static TagLib::VariantMap apePictureToVariant(const MigratedPicture &picture) {
    TagLib::VariantMap property;
    property.insert("data", picture.data);
    property.insert("mimeType", TagLib::String(picture.mime, TagLib::String::UTF8));
    property.insert("description", TagLib::String(picture.description, TagLib::String::UTF8));
    property.insert("pictureType", TagLib::FLAC::Picture::typeToString(picture.type));
    return property;
}

static TagLib::List<TagLib::VariantMap> readApePictureProperties(
    const TagLib::APE::Tag *tag
) {
    TagLib::List<TagLib::VariantMap> pictures;
    if (!tag) return pictures;
    for (const auto &[key, item] : tag->itemListMap()) {
        MigratedPicture picture;
        if (decodeApeCoverItem(key, item, picture)) {
            pictures.append(apePictureToVariant(picture));
        }
    }
    return pictures;
}

struct MetadataMigrationReportNative {
    bool supported = false;
    bool save_ok = false;
    bool verify_ok = false;
    int source_text_fields = 0;
    int source_text_values = 0;
    int target_text_fields = 0;
    int target_text_values = 0;
    int rejected_fields = 0;
    int text_mismatches = 0;
    int pictures_copied = 0;
    int picture_mismatches = 0;
    int unsupported_binary_items = 0;
    std::vector<std::string> mismatch_keys;

    bool strictOk() const {
        return supported && save_ok && verify_ok && rejected_fields == 0 &&
            text_mismatches == 0 && picture_mismatches == 0 &&
            unsupported_binary_items == 0;
    }
};

static void appendMismatchKey(MetadataMigrationReportNative &report, const TagLib::String &key) {
    const auto value = key.to8Bit(true);
    if (!value.empty() && report.mismatch_keys.size() < 32) report.mismatch_keys.push_back(value);
}

// TagLib exposes complex properties on the Tag object. Most File implementations forward that
// API, but WavPack::File deliberately exposes the APE tag through APETag(); its inherited
// File::complexProperties/setComplexProperties are the no-op base implementation.
template <typename FileType>
static TagLib::List<TagLib::VariantMap> complexPicturesForFile(FileType &file);

static TagLib::List<TagLib::VariantMap> complexPicturesForFile(TagLib::WavPack::File &file);

template <typename FileType>
static bool setComplexPicturesForFile(
    FileType &file,
    const TagLib::List<TagLib::VariantMap> &pictures
);

static bool setComplexPicturesForFile(
    TagLib::WavPack::File &file,
    const TagLib::List<TagLib::VariantMap> &pictures
);

static TagLib::List<TagLib::VariantMap> picturePropertiesForTarget(
    const std::vector<MigratedPicture> &pictures
) {
    TagLib::List<TagLib::VariantMap> result;
    for (const auto &picture : pictures) {
        TagLib::VariantMap property;
        property.insert("data", picture.data);
        property.insert("mimeType", TagLib::String(picture.mime, TagLib::String::UTF8));
        property.insert("description", TagLib::String(picture.description, TagLib::String::UTF8));
        property.insert("pictureType", TagLib::FLAC::Picture::typeToString(picture.type));
        result.append(property);
    }
    return result;
}

static std::vector<MigratedPicture> readApePicturesAndUnsupported(
    TagLib::APE::File &source,
    MetadataMigrationReportNative &report
) {
    std::vector<MigratedPicture> pictures;
    auto *apeTag = source.APETag(false);
    if (!apeTag) return pictures;
    for (const auto &[key, item] : apeTag->itemListMap()) {
        if (item.type() == TagLib::APE::Item::Text) continue;
        MigratedPicture picture;
        if (decodeApeCoverItem(key, item, picture)) {
            pictures.push_back(std::move(picture));
        } else {
            ++report.unsupported_binary_items;
            appendMismatchKey(report, key);
        }
    }
    return pictures;
}

static int verifyPicturePayloads(
    const std::vector<MigratedPicture> &sourcePictures,
    const TagLib::List<TagLib::VariantMap> &targetPictures
) {
    std::vector<TagLib::VariantMap> candidates;
    candidates.reserve(targetPictures.size());
    for (const auto &picture : targetPictures) candidates.push_back(picture);
    std::vector<bool> matched(candidates.size(), false);
    int mismatches = 0;
    for (const auto &sourcePicture : sourcePictures) {
        bool found = false;
        for (size_t index = 0; index < candidates.size(); ++index) {
            if (matched[index]) continue;
            const auto targetData = candidates[index].value("data").value<TagLib::ByteVector>();
            if (targetData != sourcePicture.data) continue;
            matched[index] = true;
            found = true;
            break;
        }
        if (!found) ++mismatches;
    }
    if (candidates.size() != sourcePictures.size()) {
        mismatches += std::abs(
            static_cast<int>(candidates.size()) - static_cast<int>(sourcePictures.size())
        );
    }
    return mismatches;
}

static uint64_t readLittleEndianU64(const unsigned char *bytes) {
    uint64_t value = 0;
    for (unsigned int index = 0; index < 8u; ++index) {
        value |= static_cast<uint64_t>(bytes[index]) << (index * 8u);
    }
    return value;
}

static std::array<unsigned char, 8> littleEndianU64(uint64_t value) {
    std::array<unsigned char, 8> bytes{};
    for (unsigned int index = 0; index < bytes.size(); ++index) {
        bytes[index] = static_cast<unsigned char>((value >> (index * 8u)) & 0xFFu);
    }
    return bytes;
}

static bool readDsfMetadataBounds(
    const char *path,
    uint64_t &headerFileSize,
    uint64_t &metadataOffset
) {
    if (!path || !*path) return false;
    std::ifstream file(path, std::ios::binary);
    if (!file.is_open()) return false;

    std::array<unsigned char, 28> header{};
    file.read(reinterpret_cast<char *>(header.data()), static_cast<std::streamsize>(header.size()));
    if (file.gcount() != static_cast<std::streamsize>(header.size())) return false;
    if (std::memcmp(header.data(), "DSD ", 4) != 0) return false;
    if (readLittleEndianU64(header.data() + 4) != 28u) return false;

    headerFileSize = readLittleEndianU64(header.data() + 12);
    metadataOffset = readLittleEndianU64(header.data() + 20);
    file.seekg(0, std::ios::end);
    const std::streamoff physicalLength = file.tellg();
    if (physicalLength < 0) return false;
    const uint64_t physicalSize = static_cast<uint64_t>(physicalLength);
    if (headerFileSize < 92u || headerFileSize > physicalSize) return false;
    if (metadataOffset > headerFileSize) return false;
    if (metadataOffset != 0u && metadataOffset < 92u) return false;
    return true;
}

static bool writeDsfId3v24Tag(const char *targetPath, const TagLib::ID3v2::Tag &tag) {
    uint64_t headerFileSize = 0;
    uint64_t metadataOffset = 0;
    if (!readDsfMetadataBounds(targetPath, headerFileSize, metadataOffset)) return false;

    const TagLib::ByteVector tagData = tag.render(TagLib::ID3v2::v4);
    if (tagData.isEmpty()) return false;

    const uint64_t metadataStart = metadataOffset != 0u ? metadataOffset : headerFileSize;
    if (metadataStart > static_cast<uint64_t>(std::numeric_limits<off_t>::max())) return false;
    if (tagData.size() > std::numeric_limits<uint64_t>::max() - metadataStart) return false;

    // DSF stores its ID3v2 tag at EOF and points to it from bytes 20..27 of the DSD chunk.
    // The converter writes to a hidden partial file, so replacing the tag tail in-place is still
    // transaction-safe: any failure makes AudioTranscodeManager delete the partial file.
    if (::truncate(targetPath, static_cast<off_t>(metadataStart)) != 0) {
        LOGE("writeDsfId3v24Tag truncate failed errno=%d path=%s", errno, targetPath);
        return false;
    }

    std::fstream file(targetPath, std::ios::binary | std::ios::in | std::ios::out);
    if (!file.is_open()) return false;
    file.seekp(static_cast<std::streamoff>(metadataStart), std::ios::beg);
    file.write(tagData.data(), static_cast<std::streamsize>(tagData.size()));
    if (!file.good()) return false;

    const uint64_t newFileSize = metadataStart + static_cast<uint64_t>(tagData.size());
    const auto fileSizeBytes = littleEndianU64(newFileSize);
    const auto metadataOffsetBytes = littleEndianU64(metadataStart);
    file.seekp(12, std::ios::beg);
    file.write(
        reinterpret_cast<const char *>(fileSizeBytes.data()),
        static_cast<std::streamsize>(fileSizeBytes.size())
    );
    file.seekp(20, std::ios::beg);
    file.write(
        reinterpret_cast<const char *>(metadataOffsetBytes.data()),
        static_cast<std::streamsize>(metadataOffsetBytes.size())
    );
    file.flush();
    if (!file.good()) return false;
    file.close();

    std::ifstream verify(targetPath, std::ios::binary | std::ios::ate);
    if (!verify.is_open()) return false;
    const std::streamoff physicalLength = verify.tellg();
    return physicalLength >= 0 && static_cast<uint64_t>(physicalLength) == newFileSize;
}

template <typename TargetFile>
static MetadataMigrationReportNative migrateApeToTagLibTarget(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel,
    bool targetPreservesPictureRoleAndDescription,
    bool useMp4TargetNormalization = false
) {
    MetadataMigrationReportNative report;
    report.supported = true;

    TagLib::APE::File source(sourcePath, false);
    auto target = std::make_unique<TargetFile>(targetPath, false);
    if (!source.isValid() || !target->isValid()) return report;

    const TagLib::PropertyMap sourceProperties = source.properties();
    TagLib::PropertyMap desired = desiredTranscodeProperties(sourceProperties, encoderLabel);
    if (useMp4TargetNormalization) {
        canonicalizeMp4TrackNumber(desired);
    }
    report.source_text_fields = static_cast<int>(sourceProperties.size());
    report.source_text_values = propertyValueCount(sourceProperties);

    const auto sourcePictures = readApePicturesAndUnsupported(source, report);
    const bool pictureSemanticsStored = useMp4TargetNormalization &&
        appendMp4PictureSemanticProperties(sourcePictures, desired);

    const TagLib::PropertyMap rejected = target->setProperties(desired);
    report.rejected_fields = static_cast<int>(rejected.size());
    for (const auto &[key, values] : rejected) {
        (void) values;
        appendMismatchKey(report, key);
    }

    const auto pictureProperties = picturePropertiesForTarget(sourcePictures);
    if (!sourcePictures.empty()) {
        if (!setComplexPicturesForFile(*target, pictureProperties)) {
            report.picture_mismatches += static_cast<int>(sourcePictures.size());
            appendMismatchKey(report, TagLib::String("PICTURE", TagLib::String::UTF8));
        } else {
            report.pictures_copied = static_cast<int>(sourcePictures.size());
        }
    }

    // MP4/M4A covr preserves image payload but not the APE/ID3 picture role or description. When
    // the target-specific free-form sidecar was written above, those semantics are retained;
    // otherwise record the loss so STRICT can reject it.
    if (!targetPreservesPictureRoleAndDescription && !pictureSemanticsStored) {
        for (const auto &picture : sourcePictures) {
            if (picture.type != TagLib::FLAC::Picture::FrontCover) {
                ++report.unsupported_binary_items;
                appendMismatchKey(report, TagLib::String("PICTURE_TYPE", TagLib::String::UTF8));
            }
            if (!picture.description.empty()) {
                ++report.unsupported_binary_items;
                appendMismatchKey(report, TagLib::String("PICTURE_DESCRIPTION", TagLib::String::UTF8));
            }
        }
    }

    report.save_ok = target->save();
    if (!report.save_ok) return report;

    target.reset();

    TargetFile verifyTarget(targetPath, false);
    if (!verifyTarget.isValid()) return report;
    const TagLib::PropertyMap targetProperties = verifyTarget.properties();
    report.target_text_fields = static_cast<int>(targetProperties.size());
    report.target_text_values = propertyValueCount(targetProperties);

    for (const auto &[key, values] : desired) {
        const auto it = targetProperties.find(key);
        if (it == targetProperties.end() || it->second != values) {
            ++report.text_mismatches;
            appendMismatchKey(report, key);
        }
    }

    report.picture_mismatches += verifyPicturePayloads(
        sourcePictures,
        complexPicturesForFile(verifyTarget)
    );
    if (report.picture_mismatches > 0) {
        appendMismatchKey(report, TagLib::String("PICTURE_PAYLOAD", TagLib::String::UTF8));
    }

    report.verify_ok = report.text_mismatches == 0 && report.picture_mismatches == 0;
    return report;
}

static const char *effectiveEncoderLabel(const char *overrideLabel, const char *fallbackLabel) {
    return overrideLabel && *overrideLabel ? overrideLabel : fallbackLabel;
}

static MetadataMigrationReportNative migrateApeToFlacMetadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::FLAC::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg FLAC"),
        true
    );
}

static MetadataMigrationReportNative migrateApeToOggFlacMetadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::Ogg::FLAC::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg Ogg FLAC"),
        true
    );
}

static MetadataMigrationReportNative migrateApeToWavMetadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::RIFF::WAV::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg PCM WAV"),
        true
    );
}

static MetadataMigrationReportNative migrateApeToAiffMetadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::RIFF::AIFF::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg PCM AIFF"),
        true
    );
}

static MetadataMigrationReportNative migrateApeToMp4Metadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::MP4::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg M4A"),
        false,
        true
    );
}

static MetadataMigrationReportNative migrateApeToWavPackMetadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::WavPack::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg WavPack"),
        true
    );
}

static MetadataMigrationReportNative migrateApeToTtaMetadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::TrueAudio::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg TTA"),
        true
    );
}

static MetadataMigrationReportNative migrateApeToMp2Metadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::MPEG::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg MP2"),
        true
    );
}

static MetadataMigrationReportNative migrateApeToWmaMetadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::ASF::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg WMA"),
        true
    );
}

static MetadataMigrationReportNative migrateApeToMp3Metadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::MPEG::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg MP3"),
        true
    );
}

static MetadataMigrationReportNative migrateApeToVorbisMetadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::Ogg::Vorbis::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg Vorbis"),
        true
    );
}

static MetadataMigrationReportNative migrateApeToOpusMetadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    return migrateApeToTagLibTarget<TagLib::Ogg::Opus::File>(
        sourcePath,
        targetPath,
        effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg Opus"),
        true
    );
}

static MetadataMigrationReportNative migrateApeToDsfMetadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel = nullptr
) {
    MetadataMigrationReportNative report;
    TagLib::APE::File source(sourcePath, false);
    TagLib::DSF::File targetProbe(targetPath, false);
    if (!source.isValid() || !targetProbe.isValid()) return report;
    report.supported = true;

    const TagLib::PropertyMap sourceProperties = source.properties();
    const TagLib::PropertyMap desired = desiredTranscodeProperties(
        sourceProperties,
        effectiveEncoderLabel(encoderLabel, "RawSMusic PCMToDSD")
    );
    report.source_text_fields = static_cast<int>(sourceProperties.size());
    report.source_text_values = propertyValueCount(sourceProperties);

    TagLib::ID3v2::Tag targetTag;
    const TagLib::PropertyMap rejected = targetTag.setProperties(desired);
    report.rejected_fields = static_cast<int>(rejected.size());
    for (const auto &[key, values] : rejected) {
        (void) values;
        appendMismatchKey(report, key);
    }

    const auto sourcePictures = readApePicturesAndUnsupported(source, report);
    const auto pictureProperties = picturePropertiesForTarget(sourcePictures);
    if (!sourcePictures.empty()) {
        if (!targetTag.setComplexProperties("PICTURE", pictureProperties)) {
            report.picture_mismatches += static_cast<int>(sourcePictures.size());
            appendMismatchKey(report, TagLib::String("PICTURE", TagLib::String::UTF8));
        } else {
            report.pictures_copied = static_cast<int>(sourcePictures.size());
        }
    }

    report.save_ok = writeDsfId3v24Tag(targetPath, targetTag);
    if (!report.save_ok) {
        appendMismatchKey(report, TagLib::String("DSF_ID3_WRITE", TagLib::String::UTF8));
        return report;
    }

    TagLib::DSF::File verifyTarget(targetPath, false);
    if (!verifyTarget.isValid()) {
        appendMismatchKey(report, TagLib::String("DSF_REOPEN", TagLib::String::UTF8));
        return report;
    }
    const TagLib::PropertyMap targetProperties = verifyTarget.properties();
    report.target_text_fields = static_cast<int>(targetProperties.size());
    report.target_text_values = propertyValueCount(targetProperties);
    for (const auto &[key, values] : desired) {
        const auto it = targetProperties.find(key);
        if (it == targetProperties.end() || it->second != values) {
            ++report.text_mismatches;
            appendMismatchKey(report, key);
        }
    }

    report.picture_mismatches += verifyPicturePayloads(
        sourcePictures,
        verifyTarget.complexProperties("PICTURE")
    );
    if (report.picture_mismatches > 0) {
        appendMismatchKey(report, TagLib::String("PICTURE_PAYLOAD", TagLib::String::UTF8));
    }
    report.verify_ok = report.text_mismatches == 0 && report.picture_mismatches == 0;
    return report;
}


struct MetadataSourceInfoNative {
    bool supported = false;
    int text_fields = 0;
    int text_values = 0;
    int picture_count = 0;
    int unsupported_opaque_items = 0;
    std::vector<std::string> unsupported_keys;
};

static void appendUnsupportedKey(MetadataSourceInfoNative &info, const TagLib::String &key) {
    const auto value = key.to8Bit(true);
    if (!value.empty() && info.unsupported_keys.size() < 32) info.unsupported_keys.push_back(value);
}

template <typename SourceFile>
static MetadataSourceInfoNative inspectTypedMetadataSource(const char *sourcePath) {
    MetadataSourceInfoNative info;
    SourceFile source(sourcePath, false);
    if (!source.isValid()) return info;
    const auto properties = source.properties();
    info.supported = true;
    info.text_fields = static_cast<int>(properties.size());
    info.text_values = propertyValueCount(properties);
    info.picture_count = static_cast<int>(complexPicturesForFile(source).size());
    for (const auto &key : properties.unsupportedData()) {
        ++info.unsupported_opaque_items;
        appendUnsupportedKey(info, key);
    }
    return info;
}

static MetadataSourceInfoNative inspectMetadataSource(const char *sourcePath) {
    MetadataSourceInfoNative info;
    if (!sourcePath || !*sourcePath) return info;

    const std::string ext = getExtension(sourcePath);
    if (ext == "ape") {
        TagLib::APE::File source(sourcePath, false);
        if (!source.isValid()) return info;
        info.supported = true;
        const auto properties = source.properties();
        info.text_fields = static_cast<int>(properties.size());
        info.text_values = propertyValueCount(properties);
        MetadataMigrationReportNative scratch;
        const auto pictures = readApePicturesAndUnsupported(source, scratch);
        info.picture_count = static_cast<int>(pictures.size());
        info.unsupported_opaque_items = scratch.unsupported_binary_items;
        info.unsupported_keys = scratch.mismatch_keys;
        return info;
    }
    if (ext == "mp3" || ext == "mp2" || ext == "mpga" || ext == "aac") {
        return inspectTypedMetadataSource<TagLib::MPEG::File>(sourcePath);
    }
    if (ext == "flac") return inspectTypedMetadataSource<TagLib::FLAC::File>(sourcePath);
    if (ext == "oga") return inspectTypedMetadataSource<TagLib::Ogg::FLAC::File>(sourcePath);
    if (ext == "ogg") return inspectTypedMetadataSource<TagLib::Ogg::Vorbis::File>(sourcePath);
    if (ext == "opus") return inspectTypedMetadataSource<TagLib::Ogg::Opus::File>(sourcePath);
    if (ext == "m4a" || ext == "m4b" || ext == "m4p" || ext == "mp4") {
        return inspectTypedMetadataSource<TagLib::MP4::File>(sourcePath);
    }
    if (ext == "wma" || ext == "asf") {
        return inspectTypedMetadataSource<TagLib::ASF::File>(sourcePath);
    }
    if (ext == "wav") return inspectTypedMetadataSource<TagLib::RIFF::WAV::File>(sourcePath);
    if (ext == "aiff" || ext == "aif") {
        return inspectTypedMetadataSource<TagLib::RIFF::AIFF::File>(sourcePath);
    }
    if (ext == "dsf") return inspectTypedMetadataSource<TagLib::DSF::File>(sourcePath);
    if (ext == "dff" || ext == "dsdiff") {
        return inspectTypedMetadataSource<TagLib::DSDIFF::File>(sourcePath);
    }
    if (ext == "wv") return inspectTypedMetadataSource<TagLib::WavPack::File>(sourcePath);
    if (ext == "tta") return inspectTypedMetadataSource<TagLib::TrueAudio::File>(sourcePath);
    if (ext == "mpc" || ext == "mp+") {
        return inspectTypedMetadataSource<TagLib::MPC::File>(sourcePath);
    }
    return info;
}

static std::string serializeMetadataSourceInfo(const MetadataSourceInfoNative &info) {
    std::ostringstream output;
    output << "supported=" << (info.supported ? 1 : 0) << '\n';
    output << "textFields=" << info.text_fields << '\n';
    output << "textValues=" << info.text_values << '\n';
    output << "pictureCount=" << info.picture_count << '\n';
    output << "unsupportedOpaqueItems=" << info.unsupported_opaque_items << '\n';
    output << "unsupportedKeys=";
    for (size_t index = 0; index < info.unsupported_keys.size(); ++index) {
        if (index) output << ',';
        output << info.unsupported_keys[index];
    }
    return output.str();
}

static int verifyVariantPicturePayloads(
    const TagLib::List<TagLib::VariantMap> &sourcePictures,
    const TagLib::List<TagLib::VariantMap> &targetPictures
) {
    std::vector<TagLib::VariantMap> candidates;
    candidates.reserve(targetPictures.size());
    for (const auto &picture : targetPictures) candidates.push_back(picture);
    std::vector<bool> matched(candidates.size(), false);
    int mismatches = 0;
    for (const auto &sourcePicture : sourcePictures) {
        const auto sourceData = sourcePicture.value("data").value<TagLib::ByteVector>();
        bool found = false;
        for (size_t index = 0; index < candidates.size(); ++index) {
            if (matched[index]) continue;
            if (candidates[index].value("data").value<TagLib::ByteVector>() != sourceData) continue;
            matched[index] = true;
            found = true;
            break;
        }
        if (!found) ++mismatches;
    }
    if (candidates.size() != sourcePictures.size()) {
        mismatches += std::abs(
            static_cast<int>(candidates.size()) - static_cast<int>(sourcePictures.size())
        );
    }
    return mismatches;
}

template <typename FileType>
static TagLib::List<TagLib::VariantMap> complexPicturesForFile(FileType &file) {
    return file.complexProperties("PICTURE");
}

static TagLib::List<TagLib::VariantMap> complexPicturesForFile(TagLib::WavPack::File &file) {
    auto *tag = file.APETag(false);
    // APE::Tag::complexProperties() intentionally exposes only one FRONT and one
    // BACK item. WavPack can legally carry additional COVER ART (...) binary items,
    // and strict migration must verify every payload rather than silently dropping
    // duplicate/other-role images.
    return readApePictureProperties(tag);
}

template <typename FileType>
static bool setComplexPicturesForFile(
    FileType &file,
    const TagLib::List<TagLib::VariantMap> &pictures
) {
    return file.setComplexProperties("PICTURE", pictures);
}

static bool setComplexPicturesForFile(
    TagLib::WavPack::File &file,
    const TagLib::List<TagLib::VariantMap> &pictures
) {
    auto *tag = file.APETag(true);
    if (!tag) return false;

    // Do not use APE::Tag::setComplexProperties() here: it collapses all non-front/back
    // pictures into FRONT and keeps only one item per role. Give every source picture a
    // stable, valid APE key. Standard FRONT/BACK names remain compatible with normal
    // WavPack readers; suffixes are used only for duplicate/other-role payloads and are
    // recovered by readApePictureProperties() above.
    std::vector<TagLib::String> oldPictureKeys;
    for (const auto &[key, item] : tag->itemListMap()) {
        (void) item;
        const std::string keyUpper = key.upper().to8Bit(true);
        if (startsWithAscii(keyUpper, "COVER ART (")) oldPictureKeys.push_back(key);
    }
    for (const auto &key : oldPictureKeys) tag->removeItem(key);

    std::map<std::string, int> roleCounts;
    int written = 0;
    for (const auto &picture : pictures) {
        const auto data = picture.value("data").value<TagLib::ByteVector>();
        if (data.isEmpty()) continue;

        const std::string pictureType = picture.value("pictureType")
            .value<TagLib::String>().to8Bit(true);
        std::string role;
        if (pictureType == "Front Cover") role = "FRONT";
        else if (pictureType == "Back Cover") role = "BACK";
        else role = "OTHER";

        const int roleIndex = ++roleCounts[role];
        std::string key = "COVER ART (" + role;
        if (roleIndex > 1 || role == "OTHER") key += " " + std::to_string(roleIndex);
        key += ")";
        const TagLib::String description = picture.value("description")
            .value<TagLib::String>();
        auto encodedDescription = description.data(TagLib::String::UTF8);
        const TagLib::ByteVector payload = encodedDescription
            .append('\0')
            .append(data);
        tag->setItem(
            TagLib::String(key, TagLib::String::UTF8),
            TagLib::APE::Item(
                TagLib::String(key, TagLib::String::UTF8),
                payload,
                true
            )
        );
        ++written;
    }
    return written == static_cast<int>(pictures.size());
}

static void recordGenericPictureSemanticLoss(
    const TagLib::List<TagLib::VariantMap> &pictures,
    MetadataMigrationReportNative &report,
    bool targetPreservesPictureRoleAndDescription,
    bool pictureSemanticsStoredInAuxiliaryProperties = false
) {
    if (targetPreservesPictureRoleAndDescription || pictureSemanticsStoredInAuxiliaryProperties) return;
    for (const auto &picture : pictures) {
        const auto type = picture.value("pictureType").value<TagLib::String>();
        const auto description = picture.value("description").value<TagLib::String>();
        if (!type.isEmpty() && type != "Front Cover") {
            ++report.unsupported_binary_items;
            appendMismatchKey(report, TagLib::String("PICTURE_TYPE", TagLib::String::UTF8));
        }
        if (!description.isEmpty()) {
            ++report.unsupported_binary_items;
            appendMismatchKey(report, TagLib::String("PICTURE_DESCRIPTION", TagLib::String::UTF8));
        }
    }
}

static bool appendMp4PictureSemanticPropertiesFromVariant(
    const TagLib::List<TagLib::VariantMap> &pictures,
    TagLib::PropertyMap &properties
) {
    bool needsSidecar = false;
    for (const auto &picture : pictures) {
        const auto type = picture.value("pictureType").value<TagLib::String>();
        const auto description = picture.value("description").value<TagLib::String>();
        if ((!type.isEmpty() && type != "Front Cover") || !description.isEmpty()) {
            needsSidecar = true;
            break;
        }
    }
    if (!needsSidecar) return false;

    TagLib::StringList types;
    TagLib::StringList descriptions;
    for (const auto &picture : pictures) {
        types.append(picture.value("pictureType").value<TagLib::String>());
        descriptions.append(picture.value("description").value<TagLib::String>());
    }

    properties.replace(
        TagLib::String("RAWSMUSIC_PICTURE_TYPES", TagLib::String::UTF8),
        types
    );
    properties.replace(
        TagLib::String("RAWSMUSIC_PICTURE_DESCRIPTIONS", TagLib::String::UTF8),
        descriptions
    );
    return true;
}

template <typename SourceFile>
static MetadataMigrationReportNative migrateTypedSourceToDsfMetadata(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel
) {
    MetadataMigrationReportNative report;
    SourceFile source(sourcePath, false);
    TagLib::DSF::File targetProbe(targetPath, false);
    if (!source.isValid() || !targetProbe.isValid()) return report;
    report.supported = true;

    const TagLib::PropertyMap sourceProperties = source.properties();
    const TagLib::PropertyMap desired = desiredTranscodeProperties(sourceProperties, encoderLabel);
    report.source_text_fields = static_cast<int>(sourceProperties.size());
    report.source_text_values = propertyValueCount(sourceProperties);
    for (const auto &key : sourceProperties.unsupportedData()) {
        ++report.unsupported_binary_items;
        appendMismatchKey(report, key);
    }

    TagLib::ID3v2::Tag targetTag;
    const TagLib::PropertyMap rejected = targetTag.setProperties(desired);
    report.rejected_fields = static_cast<int>(rejected.size());
    for (const auto &[key, values] : rejected) {
        (void) values;
        appendMismatchKey(report, key);
    }

    const auto sourcePictures = complexPicturesForFile(source);
    if (!sourcePictures.isEmpty()) {
        if (!targetTag.setComplexProperties("PICTURE", sourcePictures)) {
            report.picture_mismatches += static_cast<int>(sourcePictures.size());
            appendMismatchKey(report, TagLib::String("PICTURE", TagLib::String::UTF8));
        } else {
            report.pictures_copied = static_cast<int>(sourcePictures.size());
        }
    }

    report.save_ok = writeDsfId3v24Tag(targetPath, targetTag);
    if (!report.save_ok) {
        appendMismatchKey(report, TagLib::String("DSF_ID3_WRITE", TagLib::String::UTF8));
        return report;
    }

    TagLib::DSF::File verifyTarget(targetPath, false);
    if (!verifyTarget.isValid()) {
        appendMismatchKey(report, TagLib::String("DSF_REOPEN", TagLib::String::UTF8));
        return report;
    }
    const TagLib::PropertyMap targetProperties = verifyTarget.properties();
    report.target_text_fields = static_cast<int>(targetProperties.size());
    report.target_text_values = propertyValueCount(targetProperties);
    for (const auto &[key, values] : desired) {
        const auto it = targetProperties.find(key);
        if (it == targetProperties.end() || it->second != values) {
            ++report.text_mismatches;
            appendMismatchKey(report, key);
        }
    }

    report.picture_mismatches += verifyVariantPicturePayloads(
        sourcePictures,
        complexPicturesForFile(verifyTarget)
    );
    if (report.picture_mismatches > 0) {
        appendMismatchKey(report, TagLib::String("PICTURE_PAYLOAD", TagLib::String::UTF8));
    }
    report.verify_ok = report.text_mismatches == 0 && report.picture_mismatches == 0;
    return report;
}

template <typename SourceFile, typename TargetFile>
static MetadataMigrationReportNative migrateTypedSourceToTarget(
    const char *sourcePath,
    const char *targetPath,
    const char *encoderLabel,
    bool targetPreservesPictureRoleAndDescription,
    bool useMp4TargetNormalization = false,
    bool useApeTargetNormalization = false
) {
    MetadataMigrationReportNative report;
    SourceFile source(sourcePath, false);
    auto target = std::make_unique<TargetFile>(targetPath, false);
    if (!source.isValid() || !target->isValid()) return report;
    report.supported = true;

    const TagLib::PropertyMap sourceProperties = source.properties();
    TagLib::PropertyMap desired = desiredTranscodeProperties(sourceProperties, encoderLabel);
    if (useMp4TargetNormalization) {
        canonicalizeMp4TrackNumber(desired);
    }
    report.source_text_fields = static_cast<int>(sourceProperties.size());
    report.source_text_values = propertyValueCount(sourceProperties);
    for (const auto &key : sourceProperties.unsupportedData()) {
        ++report.unsupported_binary_items;
        appendMismatchKey(report, key);
    }

    const auto sourcePictures = complexPicturesForFile(source);
    const bool pictureSemanticsStored = useMp4TargetNormalization &&
        appendMp4PictureSemanticPropertiesFromVariant(sourcePictures, desired);

    const TagLib::PropertyMap rejected = target->setProperties(desired);
    report.rejected_fields = static_cast<int>(rejected.size());
    for (const auto &[key, values] : rejected) {
        (void) values;
        appendMismatchKey(report, key);
    }

    if (!sourcePictures.isEmpty()) {
        if (!setComplexPicturesForFile(*target, sourcePictures)) {
            report.picture_mismatches += static_cast<int>(sourcePictures.size());
            appendMismatchKey(report, TagLib::String("PICTURE", TagLib::String::UTF8));
        } else {
            report.pictures_copied = static_cast<int>(sourcePictures.size());
        }
    }
    recordGenericPictureSemanticLoss(
        sourcePictures,
        report,
        targetPreservesPictureRoleAndDescription,
        pictureSemanticsStored
    );

    report.save_ok = target->save();
    if (!report.save_ok) return report;

    // FileStream writes are buffered on Android. Reopening the same path while the writer is
    // still alive can observe the pre-tag EOF and makes strict verification report every text
    // field and picture as missing even though save() returned true. The reference tag pipeline
    // closes the writer before constructing its verification reader; make that boundary explicit.
    target.reset();

    TargetFile verifyTarget(targetPath, false);
    if (!verifyTarget.isValid()) return report;
    const TagLib::PropertyMap targetProperties = verifyTarget.properties();
    report.target_text_fields = static_cast<int>(targetProperties.size());
    report.target_text_values = propertyValueCount(targetProperties);
    const TagLib::PropertyMap expectedProperties = useApeTargetNormalization
        ? normalizeApeRoundTripProperties(desired)
        : desired;
    for (const auto &[key, values] : expectedProperties) {
        const auto it = targetProperties.find(key);
        if (it == targetProperties.end() || it->second != values) {
            ++report.text_mismatches;
            appendMismatchKey(report, key);
        }
    }
    report.picture_mismatches += verifyVariantPicturePayloads(
        sourcePictures,
        complexPicturesForFile(verifyTarget)
    );
    if (report.picture_mismatches > 0) {
        appendMismatchKey(report, TagLib::String("PICTURE_PAYLOAD", TagLib::String::UTF8));
    }
    report.verify_ok = report.text_mismatches == 0 && report.picture_mismatches == 0;
    return report;
}

template <typename SourceFile>
static MetadataMigrationReportNative migrateTypedSourceByTarget(
    const char *sourcePath,
    const char *targetPath,
    const std::string &targetExt,
    const char *encoderLabel = nullptr
) {
    if (targetExt == "flac") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::FLAC::File>(
            sourcePath, targetPath, effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg FLAC"), true);
    }
    if (targetExt == "oga") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::Ogg::FLAC::File>(
            sourcePath, targetPath, effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg Ogg FLAC"), true);
    }
    if (targetExt == "wav") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::RIFF::WAV::File>(
            sourcePath, targetPath, effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg PCM WAV"), true);
    }
    if (targetExt == "aiff" || targetExt == "aif") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::RIFF::AIFF::File>(
            sourcePath, targetPath, effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg PCM AIFF"), true);
    }
    if (targetExt == "m4a") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::MP4::File>(
            sourcePath,
            targetPath,
            effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg M4A"),
            false,
            true
        );
    }
    if (targetExt == "wv") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::WavPack::File>(
            sourcePath,
            targetPath,
            effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg WavPack"),
            true,
            false,
            true
        );
    }
    if (targetExt == "tta") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::TrueAudio::File>(
            sourcePath, targetPath, effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg TTA"), true);
    }
    if (targetExt == "mp2") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::MPEG::File>(
            sourcePath, targetPath, effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg MP2"), true);
    }
    if (targetExt == "mp3") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::MPEG::File>(
            sourcePath, targetPath, effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg MP3"), true);
    }
    if (targetExt == "ogg") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::Ogg::Vorbis::File>(
            sourcePath, targetPath, effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg Vorbis"), true);
    }
    if (targetExt == "opus") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::Ogg::Opus::File>(
            sourcePath, targetPath, effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg Opus"), true);
    }
    if (targetExt == "wma" || targetExt == "asf") {
        return migrateTypedSourceToTarget<SourceFile, TagLib::ASF::File>(
            sourcePath, targetPath, effectiveEncoderLabel(encoderLabel, "RawSMusic / FFmpeg WMA"), true);
    }
    if (targetExt == "dsf") {
        return migrateTypedSourceToDsfMetadata<SourceFile>(
            sourcePath,
            targetPath,
            effectiveEncoderLabel(encoderLabel, "RawSMusic PCMToDSD")
        );
    }
    return {};
}

static MetadataMigrationReportNative migrateGenericSourceByTarget(
    const char *sourcePath,
    const char *targetPath,
    const std::string &targetExt,
    const char *encoderLabel = nullptr
) {
    const std::string sourceExt = getExtension(sourcePath);
    if (sourceExt == "mp3" || sourceExt == "mp2" || sourceExt == "mpga" || sourceExt == "aac") {
        return migrateTypedSourceByTarget<TagLib::MPEG::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "flac") {
        return migrateTypedSourceByTarget<TagLib::FLAC::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "oga") {
        return migrateTypedSourceByTarget<TagLib::Ogg::FLAC::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "ogg") {
        return migrateTypedSourceByTarget<TagLib::Ogg::Vorbis::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "opus") {
        return migrateTypedSourceByTarget<TagLib::Ogg::Opus::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "m4a" || sourceExt == "m4b" || sourceExt == "m4p" || sourceExt == "mp4") {
        return migrateTypedSourceByTarget<TagLib::MP4::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "wma" || sourceExt == "asf") {
        return migrateTypedSourceByTarget<TagLib::ASF::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "wav") {
        return migrateTypedSourceByTarget<TagLib::RIFF::WAV::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "aiff" || sourceExt == "aif") {
        return migrateTypedSourceByTarget<TagLib::RIFF::AIFF::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "dsf") {
        return migrateTypedSourceByTarget<TagLib::DSF::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "dff" || sourceExt == "dsdiff") {
        return migrateTypedSourceByTarget<TagLib::DSDIFF::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "wv") {
        return migrateTypedSourceByTarget<TagLib::WavPack::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "tta") {
        return migrateTypedSourceByTarget<TagLib::TrueAudio::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    if (sourceExt == "mpc" || sourceExt == "mp+") {
        return migrateTypedSourceByTarget<TagLib::MPC::File>(sourcePath, targetPath, targetExt, encoderLabel);
    }
    return {};
}

static std::string serializeMetadataMigrationReport(const MetadataMigrationReportNative &report) {
    std::ostringstream output;
    output << "supported=" << (report.supported ? 1 : 0) << '\n';
    output << "saveOk=" << (report.save_ok ? 1 : 0) << '\n';
    output << "verifyOk=" << (report.verify_ok ? 1 : 0) << '\n';
    output << "strictOk=" << (report.strictOk() ? 1 : 0) << '\n';
    output << "sourceTextFields=" << report.source_text_fields << '\n';
    output << "sourceTextValues=" << report.source_text_values << '\n';
    output << "targetTextFields=" << report.target_text_fields << '\n';
    output << "targetTextValues=" << report.target_text_values << '\n';
    output << "rejectedFields=" << report.rejected_fields << '\n';
    output << "textMismatches=" << report.text_mismatches << '\n';
    output << "picturesCopied=" << report.pictures_copied << '\n';
    output << "pictureMismatches=" << report.picture_mismatches << '\n';
    output << "unsupportedBinaryItems=" << report.unsupported_binary_items << '\n';
    output << "mismatchKeys=";
    for (size_t index = 0; index < report.mismatch_keys.size(); ++index) {
        if (index) output << ',';
        output << report.mismatch_keys[index];
    }
    return output.str();
}


static bool writeByteVectorToFile(const TagLib::ByteVector &data, const char *outputPath) {
    // Artwork existence is not a size policy. Tiny but valid JPEG/PNG covers are legal and the
    // Android bitmap decoder is the authority on whether the payload is actually an image.
    if (outputPath == nullptr || data.isEmpty()) return false;

    const std::string tmpPath = std::string(outputPath) + ".tmp";
    {
        std::ofstream out(tmpPath, std::ios::binary | std::ios::trunc);
        if (!out.good()) return false;
        out.write(data.data(), data.size());
        if (!out.good()) {
            out.close();
            std::remove(tmpPath.c_str());
            return false;
        }
        out.flush();
        out.close();
    }

    std::remove(outputPath);
    if (std::rename(tmpPath.c_str(), outputPath) != 0) {
        std::remove(tmpPath.c_str());
        return false;
    }
    return true;
}

static TagLib::ByteVector readByteVectorFromFile(const char *path) {
    if (path == nullptr) return TagLib::ByteVector();
    std::ifstream input(path, std::ios::binary | std::ios::ate);
    if (!input.good()) return TagLib::ByteVector();
    const auto size = input.tellg();
    if (size <= 1024 || size > 20 * 1024 * 1024) return TagLib::ByteVector();
    input.seekg(0, std::ios::beg);
    TagLib::ByteVector data(static_cast<unsigned int>(size), 0);
    input.read(data.data(), size);
    return input.good() || input.eof() ? data : TagLib::ByteVector();
}

static bool replaceId3FrontCover(
    TagLib::ID3v2::Tag *tag,
    const TagLib::ByteVector &data,
    const char *mimeType
) {
    if (!tag || data.isEmpty()) return false;
    tag->removeFrames("APIC");
    auto *picture = new TagLib::ID3v2::AttachedPictureFrame();
    picture->setType(TagLib::ID3v2::AttachedPictureFrame::FrontCover);
    picture->setMimeType(TagLib::String(mimeType, TagLib::String::UTF8));
    picture->setDescription(TagLib::String("Front cover", TagLib::String::UTF8));
    picture->setPicture(data);
    tag->addFrame(picture);
    return true;
}

static bool writeEmbeddedArtwork(
    const char *filePath,
    const char *artworkPath,
    const char *mimeType
) {
    const auto data = readByteVectorFromFile(artworkPath);
    if (data.isEmpty()) return false;
    const std::string ext = getExtension(filePath);

    if (ext == "mp3" || ext == "mp2" || ext == "mpga" || ext == "aac") {
        TagLib::MPEG::File file(filePath, false);
        if (!file.isValid() || !replaceId3FrontCover(file.ID3v2Tag(true), data, mimeType)) return false;
        return file.save();
    }

    if (ext == "flac") {
        TagLib::FLAC::File file(filePath, false);
        if (!file.isValid()) return false;
        file.removePictures();
        auto *picture = new TagLib::FLAC::Picture();
        picture->setType(TagLib::FLAC::Picture::FrontCover);
        picture->setMimeType(TagLib::String(mimeType, TagLib::String::UTF8));
        picture->setDescription(TagLib::String("Front cover", TagLib::String::UTF8));
        picture->setData(data);
        file.addPicture(picture);
        return file.save();
    }

    if (ext == "wav") {
        TagLib::RIFF::WAV::File file(filePath, false);
        if (!file.isValid() || !replaceId3FrontCover(file.ID3v2Tag(), data, mimeType)) return false;
        return file.save();
    }

    if (ext == "m4a" || ext == "m4b" || ext == "m4p" || ext == "mp4") {
        TagLib::MP4::File file(filePath, false);
        auto *tag = file.tag();
        if (!file.isValid() || !tag) return false;
        TagLib::MP4::CoverArt::Format format = TagLib::MP4::CoverArt::Unknown;
        const std::string mime(mimeType ? mimeType : "");
        if (mime == "image/jpeg") format = TagLib::MP4::CoverArt::JPEG;
        else if (mime == "image/png") format = TagLib::MP4::CoverArt::PNG;
        else if (mime == "image/bmp") format = TagLib::MP4::CoverArt::BMP;
        else if (mime == "image/gif") format = TagLib::MP4::CoverArt::GIF;
        TagLib::MP4::CoverArtList covers;
        covers.append(TagLib::MP4::CoverArt(format, data));
        tag->setItem("covr", TagLib::MP4::Item(covers));
        return file.save();
    }

    if (ext == "dsf") {
        TagLib::DSF::File file(filePath, false);
        if (!file.isValid() || !replaceId3FrontCover(file.tag(), data, mimeType)) return false;
        return writeDsfId3v24Tag(filePath, *file.tag());
    }

    if (ext == "dff" || ext == "dsdiff") {
        TagLib::DSDIFF::File file(filePath, false);
        if (!file.isValid() || !replaceId3FrontCover(file.ID3v2Tag(true), data, mimeType)) return false;
        return file.save();
    }

    return false;
}

static TagLib::ByteVector readAttachedPicture(TagLib::ID3v2::Tag *tag) {
    if (!tag) return TagLib::ByteVector();

    const TagLib::ID3v2::FrameList frames = tag->frameList(TagLib::ByteVector("APIC"));
    const TagLib::ID3v2::AttachedPictureFrame *best = nullptr;
    const TagLib::ID3v2::AttachedPictureFrame *fallback = nullptr;

    for (auto *frame : frames) {
        auto *picture = dynamic_cast<TagLib::ID3v2::AttachedPictureFrame *>(frame);
        if (!picture) continue;
        const auto bytes = picture->picture();
        if (bytes.isEmpty()) continue;
        if (!fallback) fallback = picture;
        if (picture->type() == TagLib::ID3v2::AttachedPictureFrame::FrontCover) {
            best = picture;
            break;
        }
    }

    const auto *selected = best ? best : fallback;
    return selected ? selected->picture() : TagLib::ByteVector();
}

static TagLib::ByteVector extractArtworkBytes(const char *filePath) {
    std::string ext = getExtension(filePath);

    if (ext == "mp3" || ext == "mp2" || ext == "mpga" || ext == "aac") {
        TagLib::MPEG::File file(filePath, false);
        if (!file.isValid()) return TagLib::ByteVector();
        return readAttachedPicture(file.ID3v2Tag(false));
    }

    if (ext == "flac") {
        TagLib::FLAC::File file(filePath, false);
        if (!file.isValid()) return TagLib::ByteVector();
        auto pictures = file.pictureList();
        TagLib::FLAC::Picture *best = nullptr;
        TagLib::FLAC::Picture *fallback = nullptr;
        for (auto *picture : pictures) {
            if (!picture || picture->data().isEmpty()) continue;
            if (!fallback) fallback = picture;
            if (picture->type() == TagLib::FLAC::Picture::FrontCover) {
                best = picture;
                break;
            }
        }
        auto *selected = best ? best : fallback;
        return selected ? selected->data() : TagLib::ByteVector();
    }

    if (ext == "wav") {
        TagLib::RIFF::WAV::File file(filePath, false);
        if (!file.isValid()) return TagLib::ByteVector();
        return readAttachedPicture(file.ID3v2Tag());
    }

    if (ext == "m4a" || ext == "m4b" || ext == "m4p" || ext == "mp4") {
        TagLib::MP4::File file(filePath, false);
        if (!file.isValid()) return TagLib::ByteVector();
        auto *tag = file.tag();
        if (!tag || !tag->contains("covr")) return TagLib::ByteVector();
        const auto covers = tag->item("covr").toCoverArtList();
        for (const auto &cover : covers) {
            const auto data = cover.data();
            if (!data.isEmpty()) return data;
        }
        return TagLib::ByteVector();
    }

    if (ext == "ape") {
        TagLib::APE::File file(filePath, false);
        if (!file.isValid()) return TagLib::ByteVector();
        auto *tag = file.APETag(false);
        if (!tag) return TagLib::ByteVector();

        TagLib::ByteVector fallback;
        for (const auto &[key, item] : tag->itemListMap()) {
            MigratedPicture picture;
            if (!decodeApeCoverItem(key, item, picture) || picture.data.isEmpty()) continue;
            if (picture.type == TagLib::FLAC::Picture::FrontCover) {
                return picture.data;
            }
            if (fallback.isEmpty()) fallback = picture.data;
        }
        return fallback;
    }

    if (ext == "dsf") {
        TagLib::DSF::File file(filePath, false);
        if (!file.isValid()) return TagLib::ByteVector();
        return readAttachedPicture(file.tag());
    }

    if (ext == "dff" || ext == "dsdiff") {
        TagLib::DSDIFF::File file(filePath, false);
        if (!file.isValid()) return TagLib::ByteVector();
        return readAttachedPicture(file.ID3v2Tag(false));
    }

    // Other formats remain on FFmpeg/MMR fallback until their native picture APIs are verified.
    return TagLib::ByteVector();
}

/**
 * 根据扩展名使用专用 reader 读取标签，避免通用探测带来的额外开销。
 */
static std::map<std::string, std::string> readMetadata(const char *filePath) {
    std::map<std::string, std::string> result;
    std::string ext = getExtension(filePath);

    LOGI("readMetadata: %s (ext=%s)", filePath, ext.c_str());

    // 根据扩展名分发到专用 reader（使用 Average 级别音频属性读取）
    if (ext == "mp3" || ext == "mp2" || ext == "mpga" || ext == "aac") {
        TagLib::MPEG::File file(filePath, true, TagLib::MPEG::Properties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "flac") {
        TagLib::FLAC::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "oga") {
        TagLib::Ogg::FLAC::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "ogg") {
        TagLib::Ogg::Vorbis::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "opus") {
        TagLib::Ogg::Opus::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "m4a" || ext == "m4b" || ext == "m4p" || ext == "mp4") {
        TagLib::MP4::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        auto *tag = file.tag();
        readTagsFromTag(tag, result);
        readMp4Lyrics(tag, result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "wma" || ext == "asf") {
        TagLib::ASF::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "ape") {
        TagLib::APE::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "wav") {
        TagLib::RIFF::WAV::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "aiff" || ext == "aif") {
        TagLib::RIFF::AIFF::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "dsf") {
        TagLib::DSF::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "dff" || ext == "dsdiff") {
        TagLib::DSDIFF::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "wv") {
        TagLib::WavPack::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "tta") {
        TagLib::TrueAudio::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else if (ext == "mpc" || ext == "mp+") {
        TagLib::MPC::File file(filePath, true, TagLib::AudioProperties::Average);
        if (!file.isValid()) return result;
        readTagsFromTag(file.tag(), result);
        readAudioProps(file.audioProperties(), result);
    }
    else {
        LOGD("readMetadata: unsupported format: %s", ext.c_str());
        return result;
    }

    LOGI("readMetadata: Found %zu tags for %s", result.size(), filePath);
    return result;
}

// ========================== JNI bridge ==========================

extern "C" JNIEXPORT jobject JNICALL
Java_com_rawsmusic_core_common_taglib_TagLibBridge_nativeReadMetadata(
    JNIEnv *env, jobject, jstring path) {

    const char *filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath) {
        LOGE("nativeReadMetadata: null path");
        return nullptr;
    }

    LOGI("nativeReadMetadata: %s", filePath);

    auto metadata = readMetadata(filePath);

    env->ReleaseStringUTFChars(path, filePath);

    // 创建 Java HashMap（带异常检查）
    jclass mapClass = env->FindClass("java/util/HashMap");
    if (!mapClass || env->ExceptionCheck()) return nullptr;

    jmethodID mapInit = env->GetMethodID(mapClass, "<init>", "(I)V");
    jmethodID mapPut = env->GetMethodID(mapClass, "put",
        "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    if (!mapInit || !mapPut || env->ExceptionCheck()) {
        env->DeleteLocalRef(mapClass);
        return nullptr;
    }

    jobject map = env->NewObject(mapClass, mapInit, (jint)metadata.size());
    if (!map || env->ExceptionCheck()) {
        env->DeleteLocalRef(mapClass);
        return nullptr;
    }

    for (const auto &pair : metadata) {
        jstring jkey = env->NewStringUTF(pair.first.c_str());
        jstring jval = env->NewStringUTF(pair.second.c_str());

        if (!jkey || !jval || env->ExceptionCheck()) {
            env->ExceptionClear();
            if (jkey) env->DeleteLocalRef(jkey);
            if (jval) env->DeleteLocalRef(jval);
            continue;
        }

        env->CallObjectMethod(map, mapPut, jkey, jval);
        env->DeleteLocalRef(jkey);
        env->DeleteLocalRef(jval);
    }

    env->DeleteLocalRef(mapClass);
    return map;
}



extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rawsmusic_core_common_taglib_TagLibBridge_nativeReadAllTextProperties(
    JNIEnv *env, jobject, jstring path) {
    if (!path) return nullptr;
    const char *filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath) return nullptr;
    bool valid = false;
    const auto properties = readAllTextProperties(filePath, &valid);
    env->ReleaseStringUTFChars(path, filePath);
    if (!valid) return nullptr;

    const int pairCount = propertyValueCount(properties);
    jclass stringClass = env->FindClass("java/lang/String");
    if (!stringClass) return nullptr;
    jobjectArray result = env->NewObjectArray(pairCount * 2, stringClass, nullptr);
    if (!result) {
        env->DeleteLocalRef(stringClass);
        return nullptr;
    }

    int offset = 0;
    for (const auto &[key, values] : properties) {
        for (const auto &value : values) {
            jstring jkey = newJString(env, key);
            jstring jvalue = newJString(env, value);
            if (!jkey || !jvalue || env->ExceptionCheck()) {
                env->ExceptionClear();
                if (jkey) env->DeleteLocalRef(jkey);
                if (jvalue) env->DeleteLocalRef(jvalue);
                continue;
            }
            env->SetObjectArrayElement(result, offset++, jkey);
            env->SetObjectArrayElement(result, offset++, jvalue);
            env->DeleteLocalRef(jkey);
            env->DeleteLocalRef(jvalue);
        }
    }
    env->DeleteLocalRef(stringClass);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_core_common_taglib_TagLibBridge_nativeInspectMetadataForTranscode(
    JNIEnv *env, jobject, jstring sourcePath) {
    if (!sourcePath) return nullptr;
    const char *source = env->GetStringUTFChars(sourcePath, nullptr);
    if (!source) return nullptr;
    std::string serialized;
    try {
        serialized = serializeMetadataSourceInfo(inspectMetadataSource(source));
    } catch (const std::exception &error) {
        LOGE("nativeInspectMetadataForTranscode failed: %s", error.what());
        serialized = serializeMetadataSourceInfo({});
    } catch (...) {
        LOGE("nativeInspectMetadataForTranscode failed: unknown error");
        serialized = serializeMetadataSourceInfo({});
    }
    env->ReleaseStringUTFChars(sourcePath, source);
    return newJString(env, TagLib::String(serialized, TagLib::String::UTF8));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_core_common_taglib_TagLibBridge_nativeMigrateMetadataForTranscode(
    JNIEnv *env, jobject, jstring sourcePath, jstring targetPath, jstring encoderLabel) {
    if (!sourcePath || !targetPath) return nullptr;
    const char *source = env->GetStringUTFChars(sourcePath, nullptr);
    const char *target = env->GetStringUTFChars(targetPath, nullptr);
    const char *encoder = encoderLabel ? env->GetStringUTFChars(encoderLabel, nullptr) : nullptr;
    if (!source || !target) {
        if (source) env->ReleaseStringUTFChars(sourcePath, source);
        if (target) env->ReleaseStringUTFChars(targetPath, target);
        if (encoder) env->ReleaseStringUTFChars(encoderLabel, encoder);
        return nullptr;
    }

    std::string serialized;
    try {
        const std::string sourceExt = getExtension(source);
        const std::string targetExt = getExtension(target);
        if (sourceExt == "ape" && targetExt == "flac") {
            serialized = serializeMetadataMigrationReport(migrateApeToFlacMetadata(source, target, encoder));
        } else if (sourceExt == "ape" && targetExt == "oga") {
            serialized = serializeMetadataMigrationReport(migrateApeToOggFlacMetadata(source, target, encoder));
        } else if (sourceExt == "ape" && targetExt == "wav") {
            serialized = serializeMetadataMigrationReport(migrateApeToWavMetadata(source, target, encoder));
        } else if (sourceExt == "ape" && (targetExt == "aiff" || targetExt == "aif")) {
            serialized = serializeMetadataMigrationReport(migrateApeToAiffMetadata(source, target, encoder));
        } else if (sourceExt == "ape" && targetExt == "m4a") {
            serialized = serializeMetadataMigrationReport(migrateApeToMp4Metadata(source, target, encoder));
        } else if (sourceExt == "ape" && targetExt == "wv") {
            serialized = serializeMetadataMigrationReport(migrateApeToWavPackMetadata(source, target, encoder));
        } else if (sourceExt == "ape" && targetExt == "tta") {
            serialized = serializeMetadataMigrationReport(migrateApeToTtaMetadata(source, target, encoder));
        } else if (sourceExt == "ape" && targetExt == "mp2") {
            serialized = serializeMetadataMigrationReport(migrateApeToMp2Metadata(source, target, encoder));
        } else if (sourceExt == "ape" && targetExt == "mp3") {
            serialized = serializeMetadataMigrationReport(migrateApeToMp3Metadata(source, target, encoder));
        } else if (sourceExt == "ape" && targetExt == "ogg") {
            serialized = serializeMetadataMigrationReport(migrateApeToVorbisMetadata(source, target, encoder));
        } else if (sourceExt == "ape" && targetExt == "opus") {
            serialized = serializeMetadataMigrationReport(migrateApeToOpusMetadata(source, target, encoder));
        } else if (sourceExt == "ape" && (targetExt == "wma" || targetExt == "asf")) {
            serialized = serializeMetadataMigrationReport(migrateApeToWmaMetadata(source, target, encoder));
        } else if (sourceExt == "ape" && targetExt == "dsf") {
            serialized = serializeMetadataMigrationReport(migrateApeToDsfMetadata(source, target, encoder));
        } else {
            serialized = serializeMetadataMigrationReport(
                migrateGenericSourceByTarget(source, target, targetExt, encoder)
            );
        }
    } catch (const std::exception &error) {
        LOGE("nativeMigrateMetadataForTranscode failed: %s", error.what());
        MetadataMigrationReportNative failed;
        serialized = serializeMetadataMigrationReport(failed);
    } catch (...) {
        LOGE("nativeMigrateMetadataForTranscode failed: unknown error");
        MetadataMigrationReportNative failed;
        serialized = serializeMetadataMigrationReport(failed);
    }

    env->ReleaseStringUTFChars(sourcePath, source);
    env->ReleaseStringUTFChars(targetPath, target);
    if (encoder) env->ReleaseStringUTFChars(encoderLabel, encoder);
    return newJString(env, TagLib::String(serialized, TagLib::String::UTF8));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_core_common_taglib_TagLibBridge_nativeExtractEmbeddedArtworkToFile(
    JNIEnv *env, jobject, jstring path, jstring outputPath) {

    const char *filePath = env->GetStringUTFChars(path, nullptr);
    const char *outPath = env->GetStringUTFChars(outputPath, nullptr);
    if (!filePath || !outPath) {
        if (filePath) env->ReleaseStringUTFChars(path, filePath);
        if (outPath) env->ReleaseStringUTFChars(outputPath, outPath);
        return JNI_FALSE;
    }

    bool ok = false;
    try {
        const auto data = extractArtworkBytes(filePath);
        ok = writeByteVectorToFile(data, outPath);
        LOGI("nativeExtractEmbeddedArtworkToFile: %s result=%d bytes=%d", filePath, ok ? 1 : 0, data.size());
    } catch (const std::exception &e) {
        LOGE("nativeExtractEmbeddedArtworkToFile failed: %s", e.what());
        ok = false;
    } catch (...) {
        LOGE("nativeExtractEmbeddedArtworkToFile failed: unknown error");
        ok = false;
    }

    env->ReleaseStringUTFChars(path, filePath);
    env->ReleaseStringUTFChars(outputPath, outPath);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_core_common_taglib_TagLibBridge_nativeWriteEmbeddedArtwork(
    JNIEnv *env, jobject, jstring path, jstring artworkPath, jstring mimeType) {

    const char *filePath = env->GetStringUTFChars(path, nullptr);
    const char *coverPath = env->GetStringUTFChars(artworkPath, nullptr);
    const char *mime = env->GetStringUTFChars(mimeType, nullptr);
    if (!filePath || !coverPath || !mime) {
        if (filePath) env->ReleaseStringUTFChars(path, filePath);
        if (coverPath) env->ReleaseStringUTFChars(artworkPath, coverPath);
        if (mime) env->ReleaseStringUTFChars(mimeType, mime);
        return JNI_FALSE;
    }

    bool ok = false;
    try {
        ok = writeEmbeddedArtwork(filePath, coverPath, mime);
        LOGI("nativeWriteEmbeddedArtwork: %s result=%d", filePath, ok ? 1 : 0);
    } catch (const std::exception &error) {
        LOGE("nativeWriteEmbeddedArtwork failed: %s", error.what());
    } catch (...) {
        LOGE("nativeWriteEmbeddedArtwork failed: unknown error");
    }

    env->ReleaseStringUTFChars(path, filePath);
    env->ReleaseStringUTFChars(artworkPath, coverPath);
    env->ReleaseStringUTFChars(mimeType, mime);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_core_common_taglib_TagLibBridge_nativeIsSupported(
    JNIEnv *env, jobject, jstring path) {

    const char *filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath) return JNI_FALSE;

    std::string ext = getExtension(filePath);
    bool supported = (ext == "mp3" || ext == "mp2" || ext == "mpga" || ext == "aac" ||
                      ext == "flac" || ext == "oga" || ext == "ogg" || ext == "opus" ||
                      ext == "m4a" || ext == "m4b" || ext == "m4p" || ext == "mp4" ||
                      ext == "wma" || ext == "asf" || ext == "ape" ||
                      ext == "wav" || ext == "aiff" || ext == "aif" ||
                      ext == "dsf" || ext == "dff" || ext == "dsdiff" ||
                      ext == "wv" || ext == "tta" || ext == "mpc" || ext == "mp+");

    env->ReleaseStringUTFChars(path, filePath);
    return supported ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_core_common_taglib_TagLibBridge_nativeWriteMetadata(
    JNIEnv *env, jobject, jstring path, jobjectArray keys, jobjectArray values) {
    const char *filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath || !keys || !values) {
        if (filePath) env->ReleaseStringUTFChars(path, filePath);
        return JNI_FALSE;
    }

    std::map<std::string, std::string> metadata;
    const jsize count = std::min(env->GetArrayLength(keys), env->GetArrayLength(values));
    for (jsize index = 0; index < count; ++index) {
        auto key = static_cast<jstring>(env->GetObjectArrayElement(keys, index));
        auto value = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        if (key && value) {
            const char *keyChars = env->GetStringUTFChars(key, nullptr);
            const char *valueChars = env->GetStringUTFChars(value, nullptr);
            if (keyChars && valueChars) metadata[keyChars] = valueChars;
            if (keyChars) env->ReleaseStringUTFChars(key, keyChars);
            if (valueChars) env->ReleaseStringUTFChars(value, valueChars);
        }
        if (key) env->DeleteLocalRef(key);
        if (value) env->DeleteLocalRef(value);
    }

    bool ok = false;
    try {
        ok = writeMetadata(filePath, metadata);
        LOGI("nativeWriteMetadata: %s tags=%zu result=%d", filePath, metadata.size(), ok ? 1 : 0);
    } catch (const std::exception &error) {
        LOGE("nativeWriteMetadata failed: %s", error.what());
    } catch (...) {
        LOGE("nativeWriteMetadata failed: unknown error");
    }
    env->ReleaseStringUTFChars(path, filePath);
    return ok ? JNI_TRUE : JNI_FALSE;
}
