/*
 * RawS Music PCM output dither.
 *
 * The noise-shaping coefficient tables are derived from FFmpeg
 * libswresample/noise_shaping_data.c (LGPL-2.1-or-later). The processing
 * state is kept per player so chunk boundaries do not restart the filter.
 */

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>

namespace {

constexpr const char* kTag = "PcmDither";
constexpr int kMaxChannels = 8;
constexpr int kMaxTaps = 20;

enum Mode {
    kOff = 0,
    kRpdf = 1,
    kTpdf = 2,
    kTpdfHighPass = 3,
    kGaussian = 4,
    kFWeighted = 5,
    kModifiedE = 6,
    kShibata = 7,
    kLowShibata = 8,
    kHighShibata = 9,
};

struct Filter {
    const double* coefficients;
    int taps;
    int rate;
};

// FFmpeg's 44.1/48 kHz coefficient sets. The mode remains available at
// other rates; just as libswresample does, unsupported noise-shaping rates
// fall back to high-pass triangular dither.
constexpr double kFWeighted44[] = {
    2.412, -3.370, 3.937, -4.174, 3.353, -2.205, 1.281, -0.569, 0.0847
};
constexpr double kModifiedE44[] = {
    1.662, -1.263, 0.4827, -0.2913, 0.1268, -0.1124, 0.03252, -0.01265, -0.03524
};
constexpr double kShibata44[] = {
    2.6773197651, -4.8308925629, 6.5701103210, -7.4572014809,
    6.7263274193, -4.8481650352, 2.0412089825, 0.7006359100,
    -2.9537565708, 4.0800385475, -4.1845216751, 3.3311812878,
    -2.1179926395, 0.8793029785, -0.0317591466, -0.4238278866,
    0.4788210392, -0.3549081385, 0.1749683917, -0.0609081686
};
constexpr double kShibata48[] = {
    2.8720729351, -5.0413231850, 6.2442994118, -5.8483986855,
    3.7067542076, -1.0495119095, -1.1830236912, 2.1126792431,
    -1.9094531530, 0.9991308451, -0.1709080637, -0.3261560202,
    0.3912764490, -0.2687646151, 0.0976761058, -0.0234738458
};
constexpr double kLowShibata44[] = {
    2.0833916664, -3.0418450832, 3.2047898769, -2.7571926117,
    1.4978630543, -0.3427594602, -0.7173374891, 1.0737057924,
    -1.0225815770, 0.5664999485, -0.2096869200, -0.0653785318,
    0.1032243818, -0.0674420223, -0.0049519734
};
constexpr double kLowShibata48[] = {
    2.3925774097, -3.4350297451, 3.1853709224, -1.8117271662,
    -0.2012477070, 1.4759907722, -1.7210904360, 0.9774670005,
    -0.1379013807, -0.3818590343, 0.2742124200, 0.0665842146,
    -0.3522330225, 0.3767234385, -0.2396427691, 0.0686748251
};
constexpr double kHighShibata44[] = {
    3.0259189606, -6.0268716812, 9.1950035095, -11.8249292374,
    12.7671422958, -11.9179468155, 9.1739168168, -5.3712320328,
    1.1393624544, 2.4484779835, -4.9719839096, 6.0392003059,
    -5.9359521866, 4.9032783508, -3.5527443886, 2.1909697056,
    -1.1672389507, 0.4903914332, -0.1651979089, 0.0232178587
};

Filter selectFilter(int mode, int sampleRate) {
    const auto closeTo = [sampleRate](int rate) {
        return std::abs(sampleRate - rate) * 20 <= rate;
    };
    switch (mode) {
        case kFWeighted:
            return closeTo(46000) ? Filter{kFWeighted44, 9, 46000} : Filter{nullptr, 0, 0};
        case kModifiedE:
            return closeTo(46000) ? Filter{kModifiedE44, 9, 46000} : Filter{nullptr, 0, 0};
        case kShibata:
            if (closeTo(44100) && (!closeTo(48000) || std::abs(sampleRate - 44100) <= std::abs(sampleRate - 48000))) {
                return Filter{kShibata44, 20, 44100};
            }
            return closeTo(48000) ? Filter{kShibata48, 16, 48000} : Filter{nullptr, 0, 0};
        case kLowShibata:
            if (closeTo(44100) && (!closeTo(48000) || std::abs(sampleRate - 44100) <= std::abs(sampleRate - 48000))) {
                return Filter{kLowShibata44, 15, 44100};
            }
            return closeTo(48000) ? Filter{kLowShibata48, 16, 48000} : Filter{nullptr, 0, 0};
        case kHighShibata:
            return closeTo(44100) ? Filter{kHighShibata44, 20, 44100} : Filter{nullptr, 0, 0};
        default:
            return Filter{nullptr, 0, 0};
    }
}

struct DitherState {
    int sampleRate = 44100;
    int channels = 2;
    int mode = kOff;
    Filter filter{nullptr, 0, 0};
    std::array<std::array<double, kMaxTaps>, kMaxChannels> errors{};
    std::array<int, kMaxChannels> positions{};
    uint32_t seed = 0x7f4a7c15u;

    void reset(int rate, int channelCount) {
        sampleRate = std::max(rate, 1);
        channels = std::clamp(channelCount, 1, kMaxChannels);
        errors = {};
        positions = {};
        seed = 0x7f4a7c15u ^ static_cast<uint32_t>(sampleRate * 2654435761u);
        filter = selectFilter(mode, sampleRate);
        if (mode >= kFWeighted && filter.coefficients == nullptr) {
            __android_log_print(ANDROID_LOG_INFO, kTag,
                "mode=%d rate=%d unsupported for noise shaping; using TPDF high-pass",
                mode, sampleRate);
        }
    }

    void setMode(int newMode) {
        mode = std::clamp(newMode, static_cast<int>(kOff), static_cast<int>(kHighShibata));
        reset(sampleRate, channels);
    }

    uint32_t nextRandom() {
        // Xorshift is deterministic, cheap, and independent for each player.
        seed ^= seed << 13;
        seed ^= seed >> 17;
        seed ^= seed << 5;
        return seed;
    }

    double unitRandom() {
        return (static_cast<double>(nextRandom()) + 1.0) / 4294967297.0;
    }

    double tpdf() {
        return unitRandom() - unitRandom();
    }

    double noise() {
        switch (mode) {
            case kRpdf:
                return unitRandom() - 0.5;
            case kTpdf:
                return tpdf();
            case kTpdfHighPass:
                return (-tpdf() + 2.0 * tpdf() - tpdf()) / std::sqrt(6.0);
            case kGaussian: {
                // Sum-of-uniforms Gaussian approximation; it avoids log/sqrt
                // in the hot path while retaining a bell-shaped PDF.
                double sum = 0.0;
                for (int i = 0; i < 12; ++i) sum += unitRandom();
                return (sum - 6.0) / std::sqrt(6.0);
            }
            default:
                // Noise shaping is only available for the coefficient/sample
                // rate pairs above. Keep the selected shaped mode audible and
                // safe at other rates by using HP-TPDF, matching FFmpeg's
                // documented fallback instead of silently using plain TPDF.
                return mode >= kFWeighted
                    ? (-tpdf() + 2.0 * tpdf() - tpdf()) / std::sqrt(6.0)
                    : tpdf();
        }
    }

    double quantize(double input, int channel) {
        const bool shaped = mode >= kFWeighted && filter.coefficients != nullptr;
        if (!shaped) return std::nearbyint(input + noise());

        const int ch = std::clamp(channel, 0, channels - 1);
        const int taps = filter.taps;
        int position = positions[ch];
        double shapedInput = input;
        for (int i = 0; i < taps; ++i) {
            shapedInput -= filter.coefficients[i] * errors[ch][(position + i) % taps];
        }
        const double quantized = std::nearbyint(shapedInput + tpdf());
        errors[ch][position] = quantized - shapedInput;
        positions[ch] = (position + 1) % taps;
        return quantized;
    }

    int processS32ToS16(const uint8_t* src, int length, uint8_t* dst, int dstLength) {
        const int samples = std::min(length / 4, dstLength / 2);
        for (int i = 0; i < samples; ++i) {
            const int32_t value = static_cast<int32_t>(
                static_cast<uint32_t>(src[i * 4]) |
                (static_cast<uint32_t>(src[i * 4 + 1]) << 8) |
                (static_cast<uint32_t>(src[i * 4 + 2]) << 16) |
                (static_cast<uint32_t>(src[i * 4 + 3]) << 24));
            const int channel = i % channels;
            const int32_t quantized = static_cast<int32_t>(std::clamp(
                quantize(static_cast<double>(value) / 65536.0, channel),
                -32768.0, 32767.0));
            dst[i * 2] = static_cast<uint8_t>(quantized & 0xff);
            dst[i * 2 + 1] = static_cast<uint8_t>((quantized >> 8) & 0xff);
        }
        return samples * 2;
    }

    int processS32ToS24(const uint8_t* src, int length, uint8_t* dst, int dstLength) {
        const int samples = std::min(length / 4, dstLength / 3);
        for (int i = 0; i < samples; ++i) {
            const int32_t value = static_cast<int32_t>(
                static_cast<uint32_t>(src[i * 4]) |
                (static_cast<uint32_t>(src[i * 4 + 1]) << 8) |
                (static_cast<uint32_t>(src[i * 4 + 2]) << 16) |
                (static_cast<uint32_t>(src[i * 4 + 3]) << 24));
            const int channel = i % channels;
            const int32_t quantized = static_cast<int32_t>(std::clamp(
                quantize(static_cast<double>(value) / 256.0, channel),
                -8388608.0, 8388607.0));
            dst[i * 3] = static_cast<uint8_t>(quantized & 0xff);
            dst[i * 3 + 1] = static_cast<uint8_t>((quantized >> 8) & 0xff);
            dst[i * 3 + 2] = static_cast<uint8_t>((quantized >> 16) & 0xff);
        }
        return samples * 3;
    }
};

inline DitherState* stateFromHandle(jlong handle) {
    return reinterpret_cast<DitherState*>(handle);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_PcmDitherEngine_nativeCreate(
    JNIEnv*, jclass, jint sampleRate, jint channels) {
    auto* state = new DitherState();
    state->reset(sampleRate, channels);
    return reinterpret_cast<jlong>(state);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_PcmDitherEngine_nativeReset(
    JNIEnv*, jclass, jlong handle, jint sampleRate, jint channels) {
    if (auto* state = stateFromHandle(handle)) state->reset(sampleRate, channels);
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_PcmDitherEngine_nativeSetMode(
    JNIEnv*, jclass, jlong handle, jint mode) {
    if (auto* state = stateFromHandle(handle)) state->setMode(mode);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_PcmDitherEngine_nativeProcessS32ToS16(
    JNIEnv* env, jclass, jlong handle, jbyteArray source, jint sourceLength,
    jbyteArray destination, jint destinationLength) {
    auto* state = stateFromHandle(handle);
    if (!state || !source || !destination || sourceLength <= 0 || destinationLength <= 0) return 0;
    jbyte* src = env->GetByteArrayElements(source, nullptr);
    jbyte* dst = env->GetByteArrayElements(destination, nullptr);
    if (!src || !dst) {
        if (src) env->ReleaseByteArrayElements(source, src, JNI_ABORT);
        if (dst) env->ReleaseByteArrayElements(destination, dst, 0);
        return 0;
    }
    const int result = state->processS32ToS16(
        reinterpret_cast<const uint8_t*>(src), sourceLength,
        reinterpret_cast<uint8_t*>(dst), destinationLength);
    env->ReleaseByteArrayElements(source, src, JNI_ABORT);
    env->ReleaseByteArrayElements(destination, dst, 0);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_PcmDitherEngine_nativeProcessS32ToS24(
    JNIEnv* env, jclass, jlong handle, jbyteArray source, jint sourceLength,
    jbyteArray destination, jint destinationLength) {
    auto* state = stateFromHandle(handle);
    if (!state || !source || !destination || sourceLength <= 0 || destinationLength <= 0) return 0;
    jbyte* src = env->GetByteArrayElements(source, nullptr);
    jbyte* dst = env->GetByteArrayElements(destination, nullptr);
    if (!src || !dst) {
        if (src) env->ReleaseByteArrayElements(source, src, JNI_ABORT);
        if (dst) env->ReleaseByteArrayElements(destination, dst, 0);
        return 0;
    }
    const int result = state->processS32ToS24(
        reinterpret_cast<const uint8_t*>(src), sourceLength,
        reinterpret_cast<uint8_t*>(dst), destinationLength);
    env->ReleaseByteArrayElements(source, src, JNI_ABORT);
    env->ReleaseByteArrayElements(destination, dst, 0);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_PcmDitherEngine_nativeRelease(
    JNIEnv*, jclass, jlong handle) {
    delete stateFromHandle(handle);
}
