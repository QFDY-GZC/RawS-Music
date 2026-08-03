#include <jni.h>
#include <dlfcn.h>
#include <sys/stat.h>

#include <algorithm>
#include <cctype>
#include <string>

namespace {
constexpr int kBridgeAbiVersion = 1;
constexpr const char* kOrtLibrary = "libonnxruntime.so";
constexpr const char* kOrtEntryPoint = "OrtGetApiBase";

struct OrtProbe {
    void* handle = nullptr;
    bool symbolPresent = false;
    std::string error;
};

OrtProbe probeOrt() {
    OrtProbe result;
    dlerror();
    result.handle = dlopen(kOrtLibrary, RTLD_NOW | RTLD_LOCAL);
    if (result.handle == nullptr) {
        const char* message = dlerror();
        result.error = message != nullptr ? message : "libonnxruntime.so not found";
        return result;
    }
    dlerror();
    result.symbolPresent = dlsym(result.handle, kOrtEntryPoint) != nullptr;
    if (!result.symbolPresent) {
        const char* message = dlerror();
        result.error = message != nullptr ? message : "OrtGetApiBase not found";
    }
    return result;
}

void closeProbe(OrtProbe& probe) {
    if (probe.handle != nullptr) {
        dlclose(probe.handle);
        probe.handle = nullptr;
    }
}

std::string jstringToUtf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) return {};
    std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    return result;
}

jstring toJString(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

bool hasSupportedExtension(const std::string& path) {
    const auto dot = path.find_last_of('.');
    if (dot == std::string::npos) return false;
    std::string extension = path.substr(dot + 1);
    std::transform(extension.begin(), extension.end(), extension.begin(), [](unsigned char ch) {
        return static_cast<char>(std::tolower(ch));
    });
    return extension == "ort" || extension == "onnx";
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_separation_AiSeparationRuntimeBridge_nativeBridgeAbiVersion(
    JNIEnv*, jclass) {
    return kBridgeAbiVersion;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_separation_AiSeparationRuntimeBridge_nativeRuntimePresent(
    JNIEnv*, jclass) {
    OrtProbe probe = probeOrt();
    const bool available = probe.handle != nullptr && probe.symbolPresent;
    closeProbe(probe);
    return available ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_separation_AiSeparationRuntimeBridge_nativeRuntimeDetails(
    JNIEnv* env, jclass) {
    OrtProbe probe = probeOrt();
    std::string details;
    if (probe.handle != nullptr && probe.symbolPresent) {
        details = "libonnxruntime.so + OrtGetApiBase detected";
    } else {
        details = probe.error.empty() ? "ONNX Runtime unavailable" : probe.error;
    }
    closeProbe(probe);
    return toJString(env, details);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_separation_AiSeparationRuntimeBridge_nativeProbeModel(
    JNIEnv* env, jclass, jstring pathValue) {
    const std::string path = jstringToUtf8(env, pathValue);
    if (path.empty()) return toJString(env, "ERROR:model path is empty");
    if (!hasSupportedExtension(path)) {
        return toJString(env, "ERROR:model extension must be .ort or .onnx");
    }

    struct stat info {};
    if (stat(path.c_str(), &info) != 0 || !S_ISREG(info.st_mode) || info.st_size <= 0) {
        return toJString(env, "ERROR:model file is not readable");
    }

    OrtProbe probe = probeOrt();
    if (probe.handle == nullptr || !probe.symbolPresent) {
        const std::string message = probe.error.empty() ? "ONNX Runtime unavailable" : probe.error;
        closeProbe(probe);
        return toJString(env, "ERROR:" + message);
    }
    closeProbe(probe);

    // This bridge intentionally performs only a loader/file contract probe. Session creation and
    // tensor execution remain in the future model-specific engine so model preprocessing never
    // leaks into the USB, DSP, or realtime playback paths.
    return toJString(env, "OK:runtime symbol and model file contract are available");
}
