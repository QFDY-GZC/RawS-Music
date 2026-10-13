#include "raw_sacd_iso_jni.h"
#include "raw_sacd_iso.h"
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <array>
#include <android/log.h>
extern "C" void raw_sacd_set_charset_converter(char* (*callback)(const char*, uint8_t));
namespace {
std::atomic<JavaVM*> vm{nullptr};
char* convert_charset(const char* text, uint8_t codepage) {
    JavaVM* machine = vm.load(std::memory_order_acquire);
    if (!machine || !text) return nullptr;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (machine->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (machine->AttachCurrentThread(&env, nullptr) != JNI_OK) return nullptr;
        attached = true;
    }
    const char* encodings[] = {"US-ASCII", "US-ASCII", "ISO-8859-1", "Shift_JIS",
        "EUC-KR", "GB2312", "Big5", "ISO-8859-1"};
    char* result = nullptr;
    if (env->PushLocalFrame(16) == JNI_OK) {
        jclass string = env->FindClass("java/lang/String");
        jmethodID constructor = env->GetMethodID(string, "<init>", "([BLjava/lang/String;)V");
        jmethodID getBytes = env->GetMethodID(string, "getBytes", "(Ljava/lang/String;)[B");
        size_t count = strnlen(text, 2047);
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(count));
        if (bytes) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(count), reinterpret_cast<const jbyte*>(text));
        jstring encoding = env->NewStringUTF(encodings[codepage & 7]);
        jobject decoded = bytes && encoding ? env->NewObject(string, constructor, bytes, encoding) : nullptr;
        jstring utf8 = env->NewStringUTF("UTF-8");
        auto encoded = decoded && utf8 && !env->ExceptionCheck()
            ? static_cast<jbyteArray>(env->CallObjectMethod(decoded, getBytes, utf8)) : nullptr;
        if (encoded && !env->ExceptionCheck()) {
            jsize size = env->GetArrayLength(encoded);
            result = static_cast<char*>(std::malloc(static_cast<size_t>(size) + 1));
            if (result) {
                env->GetByteArrayRegion(encoded, 0, size, reinterpret_cast<jbyte*>(result));
                result[size] = 0;
            }
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
        env->PopLocalFrame(nullptr);
    }
    if (attached) machine->DetachCurrentThread();
    return result;
}
}
void raw_sacd_install_java_charset(JNIEnv* env) {
    JavaVM* machine = nullptr;
    if (env && env->GetJavaVM(&machine) == JNI_OK) {
        vm.store(machine, std::memory_order_release);
        raw_sacd_set_charset_converter(convert_charset);
    }
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_rawsmusic_core_common_sacd_SacdIsoBridge_nativeReadDisc(JNIEnv* env, jobject, jstring source) {
    raw_sacd_install_java_charset(env);
    const char* path = env->GetStringUTFChars(source, nullptr);
    if (!path) return nullptr;
    std::string error;
    auto tracks = rawsmusic::sacd_iso::scan(path, error);
    env->ReleaseStringUTFChars(source, path);
    if (!error.empty()) __android_log_print(ANDROID_LOG_WARN, "SacdIso", "SCAN %s", error.c_str());
    jclass stringClass = env->FindClass("java/lang/String");
    jclass rowClass = env->FindClass("[Ljava/lang/String;");
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(tracks.size()), rowClass, nullptr);
    if (!result) return nullptr;
    for (size_t i = 0; i < tracks.size(); ++i) {
        const auto& track = tracks[i];
        std::array<std::string, 13> fields = {std::to_string(track.area), std::to_string(track.number),
            std::to_string(track.channels), std::to_string(track.start_frame), std::to_string(track.frames),
            track.dst ? "1" : "0", track.title, track.artist, track.album, std::to_string(track.year),
            std::to_string(track.disc), track.composer, track.album_artist};
        jobjectArray row = env->NewObjectArray(13, stringClass, nullptr);
        if (!row) return nullptr;
        for (int j = 0; j < 13; ++j) {
            jstring text = env->NewStringUTF(fields[j].c_str());
            if (!text) return nullptr;
            env->SetObjectArrayElement(row, j, text);
            env->DeleteLocalRef(text);
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(i), row);
        env->DeleteLocalRef(row);
    }
    return result;
}
