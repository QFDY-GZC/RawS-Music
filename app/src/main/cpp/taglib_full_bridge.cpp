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
#include <string>
#include <map>
#include <cstring>
#include <cctype>
#include <fstream>
#include <cstdio>

// TagLib headers - 按格式专用
#include "mpegfile.h"
#include "mpeg/id3v2/id3v2tag.h"
#include "mpeg/id3v2/frames/attachedpictureframe.h"
#include "flacfile.h"
#include "flacpicture.h"
#include "ogg/vorbis/vorbisfile.h"
#include "ogg/opus/opusfile.h"
#include "mp4file.h"
#include "mp4tag.h"
#include "mp4item.h"
#include "mp4coverart.h"
#include "asffile.h"
#include "apefile.h"
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


static bool writeByteVectorToFile(const TagLib::ByteVector &data, const char *outputPath) {
    if (outputPath == nullptr || data.size() <= 1024) return false;

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

static TagLib::ByteVector readAttachedPicture(TagLib::ID3v2::Tag *tag) {
    if (!tag) return TagLib::ByteVector();

    const TagLib::ID3v2::FrameList frames = tag->frameList(TagLib::ByteVector("APIC"));
    const TagLib::ID3v2::AttachedPictureFrame *best = nullptr;
    const TagLib::ID3v2::AttachedPictureFrame *fallback = nullptr;

    for (auto *frame : frames) {
        auto *picture = dynamic_cast<TagLib::ID3v2::AttachedPictureFrame *>(frame);
        if (!picture) continue;
        const auto bytes = picture->picture();
        if (bytes.size() <= 1024) continue;
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

    if (ext == "mp3" || ext == "mp2" || ext == "mpga") {
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
            if (!picture || picture->data().size() <= 1024) continue;
            if (!fallback) fallback = picture;
            if (picture->type() == TagLib::FLAC::Picture::FrontCover) {
                best = picture;
                break;
            }
        }
        auto *selected = best ? best : fallback;
        return selected ? selected->data() : TagLib::ByteVector();
    }

    if (ext == "m4a" || ext == "m4b" || ext == "m4p" || ext == "mp4") {
        TagLib::MP4::File file(filePath, false);
        if (!file.isValid()) return TagLib::ByteVector();
        auto *tag = file.tag();
        if (!tag || !tag->contains("covr")) return TagLib::ByteVector();
        const auto covers = tag->item("covr").toCoverArtList();
        for (const auto &cover : covers) {
            const auto data = cover.data();
            if (data.size() > 1024) return data;
        }
        return TagLib::ByteVector();
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
    if (ext == "mp3" || ext == "mp2" || ext == "mpga") {
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
        readTagsFromTag(file.tag(), result);
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
Java_com_rawsmusic_core_common_taglib_TagLibBridge_nativeIsSupported(
    JNIEnv *env, jobject, jstring path) {

    const char *filePath = env->GetStringUTFChars(path, nullptr);
    if (!filePath) return JNI_FALSE;

    std::string ext = getExtension(filePath);
    bool supported = (ext == "mp3" || ext == "mp2" || ext == "mpga" ||
                      ext == "flac" || ext == "ogg" || ext == "opus" ||
                      ext == "m4a" || ext == "m4b" || ext == "m4p" || ext == "mp4" ||
                      ext == "wma" || ext == "asf" || ext == "ape" ||
                      ext == "wav" || ext == "aiff" || ext == "aif" ||
                      ext == "dsf" || ext == "dff" || ext == "dsdiff" ||
                      ext == "wv" || ext == "tta" || ext == "mpc" || ext == "mp+");

    env->ReleaseStringUTFChars(path, filePath);
    return supported ? JNI_TRUE : JNI_FALSE;
}
