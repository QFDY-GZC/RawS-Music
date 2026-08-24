#include <jni.h>
#include <algorithm>
#include <android/log.h>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <limits>
#include <type_traits>
#include <vector>

#include "raw_transition_trace.h"

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <SLES/OpenSLES_AndroidConfiguration.h>

#include <aaudio/AAudio.h>

#define LOG_TAG "NativeAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int MODE_OPENSL_ES = 0;
constexpr int MODE_AAUDIO = 1;
constexpr int MODE_DIRECT = 2;

// API 32 values from aaudio/AAudio.h. Keep them local so this file still builds
// against older NDK headers while resolving the API 32 symbols dynamically.
constexpr int32_t AAUDIO_USAGE_MEDIA_VALUE = 1;
constexpr int32_t AAUDIO_CONTENT_TYPE_MUSIC_VALUE = 2;
constexpr int32_t AAUDIO_SPATIALIZATION_AUTO_VALUE = 1;
constexpr int32_t AAUDIO_SPATIALIZATION_NEVER_VALUE = 2;

constexpr int FORMAT_PCM_I16 = 1;
constexpr int FORMAT_PCM_FLOAT = 2;
// RawSMusic decoder outputs 24/32-bit PCM as S32LE.  Direct/AAudio must
// request an integer 32-bit stream when AudioTrack probing returned
// ENCODING_PCM_32BIT; treating it as I16 or FLOAT causes full-scale noise.
constexpr int FORMAT_PCM_I32 = 3;

// Native write result used only when a lifecycle pause wins before a PCM block can be queued.
// Kotlin parks and retries the exact same PCM block after the backend has been restarted.
constexpr int WRITE_RESULT_PAUSED = -2;

#ifndef AAUDIO_FORMAT_PCM_I32
#define AAUDIO_FORMAT_PCM_I32 ((aaudio_format_t)4)
#endif

class NativeOutput {
public:
    virtual ~NativeOutput() = default;
    virtual bool start() = 0;
    virtual void pause() = 0;
    virtual void stop() = 0;
    virtual void flush() = 0;
    virtual int write(const uint8_t* data, int length) = 0;
    virtual void setVolume(float volume) = 0;
    virtual bool setOutputDevice(int32_t deviceId) = 0;
    virtual int64_t framesWritten() const = 0;
};

static inline int bytesPerSample(int format) {
    return (format == FORMAT_PCM_FLOAT || format == FORMAT_PCM_I32) ? 4 : 2;
}

static inline aaudio_format_t aaudioFormatForNativeFormat(int format) {
    switch (format) {
        case FORMAT_PCM_FLOAT: return AAUDIO_FORMAT_PCM_FLOAT;
        case FORMAT_PCM_I32:   return AAUDIO_FORMAT_PCM_I32;
        case FORMAT_PCM_I16:
        default:               return AAUDIO_FORMAT_PCM_I16;
    }
}

struct AAudioApi {
    void* library = nullptr;
    aaudio_result_t (*createStreamBuilder)(AAudioStreamBuilder**) = nullptr;
    void (*builderSetDirection)(AAudioStreamBuilder*, aaudio_direction_t) = nullptr;
    void (*builderSetSampleRate)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*builderSetChannelCount)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*builderSetFormat)(AAudioStreamBuilder*, aaudio_format_t) = nullptr;
    void (*builderSetPerformanceMode)(AAudioStreamBuilder*, aaudio_performance_mode_t) = nullptr;
    void (*builderSetSharingMode)(AAudioStreamBuilder*, aaudio_sharing_mode_t) = nullptr;
    void (*builderSetUsage)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*builderSetContentType)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*builderSetSpatializationBehavior)(AAudioStreamBuilder*, int32_t) = nullptr;
    void (*builderSetIsContentSpatialized)(AAudioStreamBuilder*, bool) = nullptr;
    void (*builderSetDeviceId)(AAudioStreamBuilder*, int32_t) = nullptr;
    aaudio_result_t (*builderOpenStream)(AAudioStreamBuilder*, AAudioStream**) = nullptr;
    aaudio_result_t (*builderDelete)(AAudioStreamBuilder*) = nullptr;
    aaudio_result_t (*streamRequestStart)(AAudioStream*) = nullptr;
    aaudio_result_t (*streamRequestPause)(AAudioStream*) = nullptr;
    aaudio_result_t (*streamRequestStop)(AAudioStream*) = nullptr;
    aaudio_result_t (*streamRequestFlush)(AAudioStream*) = nullptr;
    aaudio_result_t (*streamClose)(AAudioStream*) = nullptr;
    aaudio_result_t (*streamWrite)(AAudioStream*, const void*, int32_t, int64_t) = nullptr;
    int32_t (*streamGetBufferCapacityInFrames)(AAudioStream*) = nullptr;
    int32_t (*streamGetBufferSizeInFrames)(AAudioStream*) = nullptr;
    aaudio_result_t (*streamSetBufferSizeInFrames)(AAudioStream*, int32_t) = nullptr;
    aaudio_sharing_mode_t (*streamGetSharingMode)(AAudioStream*) = nullptr;
    int32_t (*streamGetSampleRate)(AAudioStream*) = nullptr;
    int32_t (*streamGetChannelCount)(AAudioStream*) = nullptr;
    aaudio_format_t (*streamGetFormat)(AAudioStream*) = nullptr;
    int32_t (*streamGetSpatializationBehavior)(AAudioStream*) = nullptr;
    bool (*streamIsContentSpatialized)(AAudioStream*) = nullptr;
    aaudio_result_t (*streamSetVolume)(AAudioStream*, float) = nullptr;
    int32_t (*streamGetDeviceId)(AAudioStream*) = nullptr;
    const char* (*convertResultToText)(aaudio_result_t) = nullptr;

    const char* text(aaudio_result_t result) const {
        return convertResultToText ? convertResultToText(result) : "unknown";
    }

    bool ready() const {
        return library &&
            createStreamBuilder &&
            builderSetDirection &&
            builderSetSampleRate &&
            builderSetChannelCount &&
            builderSetFormat &&
            builderSetPerformanceMode &&
            builderSetSharingMode &&
            builderOpenStream &&
            builderDelete &&
            streamRequestStart &&
            streamRequestPause &&
            streamRequestStop &&
            streamRequestFlush &&
            streamClose &&
            streamWrite &&
            streamGetBufferCapacityInFrames &&
            streamGetBufferSizeInFrames &&
            streamSetBufferSizeInFrames &&
            streamGetSharingMode &&
            streamGetSampleRate &&
            streamGetChannelCount &&
            streamGetFormat &&
            streamSetVolume;
    }
};

static AAudioApi& aaudioApi() {
    static AAudioApi api;
    static std::once_flag once;
    std::call_once(once, [] {
        api.library = dlopen("libaaudio.so", RTLD_NOW);
        if (!api.library) {
            LOGW("libaaudio.so not available: %s", dlerror());
            return;
        }

        auto load = [&](auto& dst, const char* name) {
            dst = reinterpret_cast<std::remove_reference_t<decltype(dst)>>(dlsym(api.library, name));
            if (!dst) LOGW("AAudio symbol missing: %s", name);
        };

        load(api.createStreamBuilder, "AAudio_createStreamBuilder");
        load(api.builderSetDirection, "AAudioStreamBuilder_setDirection");
        load(api.builderSetSampleRate, "AAudioStreamBuilder_setSampleRate");
        load(api.builderSetChannelCount, "AAudioStreamBuilder_setChannelCount");
        load(api.builderSetFormat, "AAudioStreamBuilder_setFormat");
        load(api.builderSetPerformanceMode, "AAudioStreamBuilder_setPerformanceMode");
        load(api.builderSetSharingMode, "AAudioStreamBuilder_setSharingMode");
        load(api.builderSetUsage, "AAudioStreamBuilder_setUsage");
        load(api.builderSetContentType, "AAudioStreamBuilder_setContentType");
        load(api.builderSetSpatializationBehavior, "AAudioStreamBuilder_setSpatializationBehavior");
        load(api.builderSetIsContentSpatialized, "AAudioStreamBuilder_setIsContentSpatialized");
        load(api.builderSetDeviceId, "AAudioStreamBuilder_setDeviceId");
        load(api.builderOpenStream, "AAudioStreamBuilder_openStream");
        load(api.builderDelete, "AAudioStreamBuilder_delete");
        load(api.streamRequestStart, "AAudioStream_requestStart");
        load(api.streamRequestPause, "AAudioStream_requestPause");
        load(api.streamRequestStop, "AAudioStream_requestStop");
        load(api.streamRequestFlush, "AAudioStream_requestFlush");
        load(api.streamClose, "AAudioStream_close");
        load(api.streamWrite, "AAudioStream_write");
        load(api.streamGetBufferCapacityInFrames, "AAudioStream_getBufferCapacityInFrames");
        load(api.streamGetBufferSizeInFrames, "AAudioStream_getBufferSizeInFrames");
        load(api.streamSetBufferSizeInFrames, "AAudioStream_setBufferSizeInFrames");
        load(api.streamGetSharingMode, "AAudioStream_getSharingMode");
        load(api.streamGetSampleRate, "AAudioStream_getSampleRate");
        load(api.streamGetChannelCount, "AAudioStream_getChannelCount");
        load(api.streamGetFormat, "AAudioStream_getFormat");
        load(api.streamGetSpatializationBehavior, "AAudioStream_getSpatializationBehavior");
        load(api.streamIsContentSpatialized, "AAudioStream_isContentSpatialized");
        load(api.streamSetVolume, "AAudioStream_setVolume");
        load(api.streamGetDeviceId, "AAudioStream_getDeviceId");
        load(api.convertResultToText, "AAudio_convertResultToText");
    });
    return api;
}

class AAudioOutput final : public NativeOutput {
public:
    AAudioOutput(
        int mode,
        int sampleRate,
        int channels,
        int format,
        int bufferFrames,
        int32_t preferredDeviceId,
        int spatializationBehavior,
        bool contentSpatialized
    )
        : mode_(mode),
          sampleRate_(sampleRate),
          channels_(channels),
          format_(format),
          frameBytes_(channels * bytesPerSample(format)),
          bufferFrames_(bufferFrames),
          preferredDeviceId_(std::max<int32_t>(0, preferredDeviceId)),
          spatializationBehavior_(
              spatializationBehavior == AAUDIO_SPATIALIZATION_AUTO_VALUE
                  ? AAUDIO_SPATIALIZATION_AUTO_VALUE
                  : AAUDIO_SPATIALIZATION_NEVER_VALUE
          ),
          contentSpatialized_(contentSpatialized) {}

    ~AAudioOutput() override {
        closeStream();
    }

    bool open() {
        std::lock_guard<std::mutex> lock(mutex_);
        return openLocked();
    }

    bool start() override {
        auto& api = aaudioApi();
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return false;
        const aaudio_result_t result = api.streamRequestStart(stream_);
        if (result != AAUDIO_OK) {
            LOGE("AAudio requestStart failed: %s", api.text(result));
            return false;
        }
        running_.store(true);
        return true;
    }

    void pause() override {
        auto& api = aaudioApi();
        std::lock_guard<std::mutex> lock(mutex_);
        if (stream_) api.streamRequestPause(stream_);
        running_.store(false);
    }

    void stop() override {
        auto& api = aaudioApi();
        std::lock_guard<std::mutex> lock(mutex_);
        if (stream_) api.streamRequestStop(stream_);
        running_.store(false);
    }

    void flush() override {
        auto& api = aaudioApi();
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stream_) return;
        api.streamRequestPause(stream_);
        api.streamRequestFlush(stream_);
        framesWritten_.store(0);
        if (running_.load()) {
            api.streamRequestStart(stream_);
        }
    }

    int write(const uint8_t* data, int length) override {
        auto& api = aaudioApi();
        std::lock_guard<std::mutex> lock(mutex_);
        AAudioStream* stream = stream_;
        if (!stream || !data || length <= 0 || frameBytes_ <= 0) return -1;

        const int alignedLength = (length / frameBytes_) * frameBytes_;
        if (alignedLength <= 0) return 0;
        if (alignedLength != length) {
            LOGW("AAudio write: dropping unaligned tail length=%d frameBytes=%d aligned=%d",
                 length, frameBytes_, alignedLength);
        }

        const int framesTotal = alignedLength / frameBytes_;
        int framesDone = 0;
        while (framesDone < framesTotal) {
            const int framesToWrite = framesTotal - framesDone;
            const uint8_t* src = data + framesDone * frameBytes_;
            const aaudio_result_t result = api.streamWrite(stream, src, framesToWrite, 20'000'000);
            if (result > 0) {
                framesDone += result;
                framesWritten_.fetch_add(result);
                continue;
            }
            if (result == 0) {
                LOGW("AAudio write timeout/no progress");
                return framesDone > 0 ? framesDone * frameBytes_ : 0;
            }
            if (result == AAUDIO_ERROR_DISCONNECTED) {
                LOGE("AAudio write disconnected; stream will be reopened by nativeSetOutputDevice");
                closeStreamLocked();
                return -2;
            }
            if (result < 0) {
                LOGE("AAudio write failed: %s", api.text(result));
                return -1;
            }
        }
        return framesDone * frameBytes_;
    }

    void setVolume(float volume) override {
        auto& api = aaudioApi();
        std::lock_guard<std::mutex> lock(mutex_);
        volume_ = std::max(0.0f, std::min(1.0f, volume));
        if (stream_) api.streamSetVolume(stream_, volume_);
    }

    bool setOutputDevice(int32_t deviceId) override {
        auto& api = aaudioApi();
        if (!api.ready() || !api.builderSetDeviceId) {
            LOGW("AAudio setOutputDevice unsupported on this platform");
            return false;
        }

        std::lock_guard<std::mutex> lock(mutex_);
        const int32_t targetDeviceId = std::max<int32_t>(0, deviceId);
        if (targetDeviceId == preferredDeviceId_ && stream_ != nullptr) {
            LOGI("AAudio setOutputDevice ignored: already deviceId=%d", targetDeviceId);
            return true;
        }

        const int32_t oldDeviceId = preferredDeviceId_;
        const bool wasRunning = running_.load(std::memory_order_acquire);

        closeStreamLocked();
        preferredDeviceId_ = targetDeviceId;
        framesWritten_.store(0, std::memory_order_release);

        if (openLocked()) {
            if (stream_) api.streamSetVolume(stream_, volume_);
            if (wasRunning && stream_) {
                const aaudio_result_t startResult = api.streamRequestStart(stream_);
                if (startResult != AAUDIO_OK) {
                    LOGE("AAudio setOutputDevice restart failed deviceId=%d: %s",
                         targetDeviceId, api.text(startResult));
                    running_.store(false, std::memory_order_release);
                    return false;
                }
                running_.store(true, std::memory_order_release);
            }
            LOGI("AAudio output device switched in-place: old=%d new=%d running=%d",
                 oldDeviceId, targetDeviceId, wasRunning ? 1 : 0);
            return true;
        }

        LOGW("AAudio setOutputDevice failed for deviceId=%d; trying rollback to %d",
             targetDeviceId, oldDeviceId);
        preferredDeviceId_ = oldDeviceId;
        const bool rollbackOk = openLocked();
        if (rollbackOk && stream_) {
            api.streamSetVolume(stream_, volume_);
            if (wasRunning) {
                const aaudio_result_t startResult = api.streamRequestStart(stream_);
                running_.store(startResult == AAUDIO_OK, std::memory_order_release);
                if (startResult != AAUDIO_OK) {
                    LOGE("AAudio setOutputDevice rollback restart failed: %s", api.text(startResult));
                }
            }
        } else {
            running_.store(false, std::memory_order_release);
        }
        return false;
    }

    int64_t framesWritten() const override {
        return framesWritten_.load();
    }

private:
    bool openLocked() {
        auto& api = aaudioApi();
        if (!api.ready()) {
            LOGW("AAudio API is not ready");
            return false;
        }
        AAudioStreamBuilder* builder = nullptr;
        aaudio_result_t result = api.createStreamBuilder(&builder);
        if (result != AAUDIO_OK || builder == nullptr) {
            LOGE("AAudio_createStreamBuilder failed: %s", api.text(result));
            return false;
        }

        api.builderSetDirection(builder, AAUDIO_DIRECTION_OUTPUT);
        api.builderSetSampleRate(builder, sampleRate_);
        api.builderSetChannelCount(builder, channels_);
        const aaudio_format_t requestedAaudioFormat = aaudioFormatForNativeFormat(format_);
        api.builderSetFormat(builder, requestedAaudioFormat);
        // Music playback is not an interactive synthesizer. The device report shows
        // the keyboard/touch loudness regression only on native backends, whose common
        // path was the explicit low-latency request. Keep the AAudio protocol but use
        // its balanced basic path so ordinary system interaction sounds stay isolated.
        api.builderSetPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_NONE);
        api.builderSetSharingMode(
            builder,
            mode_ == MODE_DIRECT ? AAUDIO_SHARING_MODE_EXCLUSIVE : AAUDIO_SHARING_MODE_SHARED
        );
        if (api.builderSetUsage) {
            api.builderSetUsage(builder, AAUDIO_USAGE_MEDIA_VALUE);
        }
        if (api.builderSetContentType) {
            api.builderSetContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC_VALUE);
        }
        if (api.builderSetSpatializationBehavior) {
            const int32_t behavior =
                mode_ == MODE_DIRECT
                    ? AAUDIO_SPATIALIZATION_NEVER_VALUE
                    : spatializationBehavior_;
            api.builderSetSpatializationBehavior(builder, behavior);
        }
        if (api.builderSetIsContentSpatialized) {
            api.builderSetIsContentSpatialized(builder, contentSpatialized_);
        }
        if (preferredDeviceId_ > 0) {
            if (api.builderSetDeviceId) {
                api.builderSetDeviceId(builder, preferredDeviceId_);
            } else {
                LOGW("AAudio builder deviceId requested=%d but API symbol is unavailable",
                     preferredDeviceId_);
            }
        }

        result = api.builderOpenStream(builder, &stream_);
        api.builderDelete(builder);

        if (result != AAUDIO_OK || stream_ == nullptr) {
            LOGE("AAudio open failed mode=%d rate=%d ch=%d fmt=%d deviceId=%d: %s",
                 mode_, sampleRate_, channels_, format_, preferredDeviceId_, api.text(result));
            stream_ = nullptr;
            return false;
        }

        const int actualRate = api.streamGetSampleRate(stream_);
        const int actualChannels = api.streamGetChannelCount(stream_);
        const aaudio_format_t actualFormat = api.streamGetFormat(stream_);
        const aaudio_sharing_mode_t actualSharing = api.streamGetSharingMode(stream_);
        const int32_t actualDeviceId = api.streamGetDeviceId ? api.streamGetDeviceId(stream_) : 0;
        const int actualSpatializationBehavior = api.streamGetSpatializationBehavior
            ? api.streamGetSpatializationBehavior(stream_)
            : -1;
        const int actualContentSpatialized = api.streamIsContentSpatialized
            ? (api.streamIsContentSpatialized(stream_) ? 1 : 0)
            : -1;

        // DIRECT means an exclusive AAudio stream with the exact probed format.
        // If the vendor silently gives us shared mode or a different format, do
        // not keep writing S32 data into an I16/float stream.  Reject it and let
        // Kotlin fall back to the AudioTrack direct route.
        if (mode_ == MODE_DIRECT &&
            (actualSharing != AAUDIO_SHARING_MODE_EXCLUSIVE ||
             (actualRate > 0 && actualRate != sampleRate_) ||
             (actualChannels > 0 && actualChannels != channels_) ||
             actualFormat != requestedAaudioFormat)) {
            LOGW("AAudio DIRECT rejected: requested rate=%d ch=%d fmt=%d exclusive=1 deviceId=%d, actual rate=%d ch=%d fmt=%d sharing=%d actualDeviceId=%d",
                 sampleRate_, channels_, requestedAaudioFormat, preferredDeviceId_,
                 actualRate, actualChannels, actualFormat, actualSharing, actualDeviceId);
            api.streamRequestStop(stream_);
            api.streamClose(stream_);
            stream_ = nullptr;
            return false;
        }

        const int capacity = api.streamGetBufferCapacityInFrames(stream_);
        if (bufferFrames_ > 0 && capacity > 0) {
            api.streamSetBufferSizeInFrames(stream_, std::min(bufferFrames_, capacity));
        }
        if (stream_) api.streamSetVolume(stream_, volume_);
        LOGI("AAudio opened mode=%d sharing=%d actualRate=%d ch=%d fmt=%d requestedFmt=%d requestedDeviceId=%d actualDeviceId=%d spatialBehavior=%d contentSpatialized=%d buffer=%d/%d",
             mode_,
             actualSharing,
             actualRate,
             actualChannels,
             actualFormat,
             requestedAaudioFormat,
             preferredDeviceId_,
             actualDeviceId,
             actualSpatializationBehavior,
             actualContentSpatialized,
             api.streamGetBufferSizeInFrames(stream_),
             api.streamGetBufferCapacityInFrames(stream_));
        return true;
    }

    void closeStreamLocked() {
        auto& api = aaudioApi();
        AAudioStream* stream = stream_;
        stream_ = nullptr;
        if (stream) {
            api.streamRequestStop(stream);
            api.streamClose(stream);
        }
    }

    void closeStream() {
        std::lock_guard<std::mutex> lock(mutex_);
        closeStreamLocked();
    }

    const int mode_;
    const int sampleRate_;
    const int channels_;
    const int format_;
    const int frameBytes_;
    const int bufferFrames_;
    mutable std::mutex mutex_;
    std::atomic<bool> running_{false};
    std::atomic<int64_t> framesWritten_{0};
    int32_t preferredDeviceId_ = 0;
    int32_t spatializationBehavior_ = AAUDIO_SPATIALIZATION_NEVER_VALUE;
    bool contentSpatialized_ = false;
    float volume_ = 1.0f;
    AAudioStream* stream_ = nullptr;
};

class OpenSLOutput final : public NativeOutput {
public:
    OpenSLOutput(int sampleRate, int channels, int format, int bufferFrames)
        : sampleRate_(sampleRate),
          channels_(channels),
          format_(format),
          frameBytes_(channels * 2),
          bufferFrames_(bufferFrames) {}

    ~OpenSLOutput() override {
        destroy();
    }

    bool open() {
        if (format_ != FORMAT_PCM_I16) {
            LOGW("OpenSL requested non-I16 format=%d; rejected", format_);
            return false;
        }

        SLresult result = slCreateEngine(&engineObject_, 0, nullptr, 0, nullptr, nullptr);
        if (result != SL_RESULT_SUCCESS || engineObject_ == nullptr) return false;
        result = (*engineObject_)->Realize(engineObject_, SL_BOOLEAN_FALSE);
        if (result != SL_RESULT_SUCCESS) return false;
        result = (*engineObject_)->GetInterface(engineObject_, SL_IID_ENGINE, &engine_);
        if (result != SL_RESULT_SUCCESS || engine_ == nullptr) return false;

        result = (*engine_)->CreateOutputMix(engine_, &outputMixObject_, 0, nullptr, nullptr);
        if (result != SL_RESULT_SUCCESS || outputMixObject_ == nullptr) return false;
        result = (*outputMixObject_)->Realize(outputMixObject_, SL_BOOLEAN_FALSE);
        if (result != SL_RESULT_SUCCESS) return false;

        SLDataLocator_AndroidSimpleBufferQueue locatorQueue = {
            SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,
            static_cast<SLuint32>(kQueueSlots)
        };
        SLDataFormat_PCM formatPcm = {};
        formatPcm.formatType = SL_DATAFORMAT_PCM;
        formatPcm.numChannels = static_cast<SLuint32>(channels_);
        formatPcm.samplesPerSec = static_cast<SLuint32>(sampleRate_ * 1000);
        formatPcm.bitsPerSample = SL_PCMSAMPLEFORMAT_FIXED_16;
        formatPcm.containerSize = SL_PCMSAMPLEFORMAT_FIXED_16;
        formatPcm.channelMask = channels_ == 1 ? SL_SPEAKER_FRONT_CENTER
                                                : (SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT);
        formatPcm.endianness = SL_BYTEORDER_LITTLEENDIAN;
        SLDataSource source = { &locatorQueue, &formatPcm };

        SLDataLocator_OutputMix locatorOutputMix = {
            SL_DATALOCATOR_OUTPUTMIX,
            outputMixObject_
        };
        SLDataSink sink = { &locatorOutputMix, nullptr };

        const SLInterfaceID ids[] = {
            SL_IID_BUFFERQUEUE,
            SL_IID_VOLUME,
            SL_IID_ANDROIDCONFIGURATION
        };
        const SLboolean req[] = {
            SL_BOOLEAN_TRUE,
            SL_BOOLEAN_FALSE,
            SL_BOOLEAN_FALSE
        };
        result = (*engine_)->CreateAudioPlayer(engine_, &playerObject_, &source, &sink, 3, ids, req);
        if (result != SL_RESULT_SUCCESS || playerObject_ == nullptr) return false;

        // Android defaults OpenSL ES players to the latency performance mode. The
        // device report shows the keyboard/touch loudness regression only on native
        // backends, so explicitly request the normal media path before Realize while
        // keeping OpenSL ES as the selected protocol.
        SLAndroidConfigurationItf androidConfig = nullptr;
        const SLresult configInterfaceResult =
            (*playerObject_)->GetInterface(playerObject_, SL_IID_ANDROIDCONFIGURATION, &androidConfig);
        if (configInterfaceResult == SL_RESULT_SUCCESS && androidConfig != nullptr) {
            const SLint32 streamType = SL_ANDROID_STREAM_MEDIA;
            const SLresult streamTypeResult = (*androidConfig)->SetConfiguration(
                androidConfig,
                SL_ANDROID_KEY_STREAM_TYPE,
                &streamType,
                sizeof(streamType)
            );
            const SLuint32 performanceMode = SL_ANDROID_PERFORMANCE_NONE;
            const SLresult performanceResult = (*androidConfig)->SetConfiguration(
                androidConfig,
                SL_ANDROID_KEY_PERFORMANCE_MODE,
                &performanceMode,
                sizeof(performanceMode)
            );
            LOGI(
                "OpenSL Android config: streamTypeResult=%u performanceResult=%u performanceMode=%u",
                streamTypeResult,
                performanceResult,
                performanceMode
            );
        } else {
            LOGW("OpenSL Android configuration interface unavailable: result=%u", configInterfaceResult);
        }

        result = (*playerObject_)->Realize(playerObject_, SL_BOOLEAN_FALSE);
        if (result != SL_RESULT_SUCCESS) return false;
        result = (*playerObject_)->GetInterface(playerObject_, SL_IID_PLAY, &play_);
        if (result != SL_RESULT_SUCCESS || play_ == nullptr) return false;
        result = (*playerObject_)->GetInterface(playerObject_, SL_IID_BUFFERQUEUE, &bufferQueue_);
        if (result != SL_RESULT_SUCCESS || bufferQueue_ == nullptr) return false;
        (*bufferQueue_)->RegisterCallback(bufferQueue_, &OpenSLOutput::bufferQueueCallback, this);
        (*playerObject_)->GetInterface(playerObject_, SL_IID_VOLUME, &volume_);

        const int targetFrames = std::max(bufferFrames_, 512);
        bufferBytes_ = std::max(targetFrames * frameBytes_ / 2, frameBytes_ * 256);
        for (int i = 0; i < kQueueSlots; ++i) {
            buffers_[i].resize(bufferBytes_);
        }
        LOGI(
            "OpenSL opened rate=%d ch=%d bufferBytes=%d queueSlots=%d",
            sampleRate_,
            channels_,
            bufferBytes_,
            kQueueSlots
        );
        return true;
    }

    bool start() override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!play_ || !bufferQueue_) return false;

        // start() is also the resume entry point. Allow the writer to prime the queue before the
        // AudioTrack hidden under Android OpenSL is put into PLAYING. Starting an empty queue lets
        // the platform immediately underrun/stop; if resume happens with count=0 that can leave the
        // UI in PLAYING while no buffer ever gets a stable drain window.
        lifecyclePaused_ = false;
        acceptWrites_ = true;
        syncQueueStateLocked("start_before_play");
        const int queued = inFlight_.load(std::memory_order_acquire);
        if (queued <= 0) {
            running_.store(false, std::memory_order_release);
            startPending_ = true;
            canWrite_.notify_all();
            LOGI("OpenSL start pending PCM prime: queued=0");
            return true;
        }

        startPending_ = false;
        const bool started = setPlayingLocked("start_with_queue");
        canWrite_.notify_all();
        return started;
    }

    void pause() override {
        std::lock_guard<std::mutex> lock(mutex_);
        lifecyclePaused_ = true;
        acceptWrites_ = false;
        startPending_ = false;
        running_.store(false, std::memory_order_release);
        if (play_) {
            const SLresult result = (*play_)->SetPlayState(play_, SL_PLAYSTATE_PAUSED);
            if (result != SL_RESULT_SUCCESS) {
                LOGE("OpenSL SetPlayState(PAUSED) failed: %u", result);
            } else {
                SLuint32 state = SL_PLAYSTATE_STOPPED;
                const SLresult stateResult = (*play_)->GetPlayState(play_, &state);
                if (stateResult == SL_RESULT_SUCCESS && state != SL_PLAYSTATE_PAUSED) {
                    LOGW("OpenSL pause validation unexpected state=%u", state);
                }
            }
        }
        syncQueueStateLocked("pause_after_state");
        canWrite_.notify_all();
    }

    void stop() override {
        std::lock_guard<std::mutex> lock(mutex_);
        lifecyclePaused_ = false;
        acceptWrites_ = false;
        startPending_ = false;
        running_.store(false, std::memory_order_release);
        if (play_) (*play_)->SetPlayState(play_, SL_PLAYSTATE_STOPPED);
        if (bufferQueue_) (*bufferQueue_)->Clear(bufferQueue_);
        inFlight_.store(0, std::memory_order_release);
        writeIndex_ = 0;
        canWrite_.notify_all();
    }

    void flush() override {
        std::lock_guard<std::mutex> lock(mutex_);
        if (bufferQueue_) (*bufferQueue_)->Clear(bufferQueue_);
        inFlight_.store(0, std::memory_order_release);
        writeIndex_ = 0;
        framesWritten_.store(0);
        canWrite_.notify_all();
    }

    int write(const uint8_t* data, int length) override {
        if (!data || length <= 0 || frameBytes_ <= 0) return -1;
        int accepted = 0;
        while (accepted < length) {
            std::unique_lock<std::mutex> lock(mutex_);
            canWrite_.wait(lock, [&] {
                return inFlight_.load(std::memory_order_acquire) < kQueueSlots || !acceptWrites_;
            });
            if (!bufferQueue_) return -1;

            // Initial startup is intentionally writable before PLAYING so FfmpegAudioPlayer can
            // prefill the OpenSL queue. A real lifecycle PAUSE is different: preserve the exact PCM
            // block and let Kotlin retry it after resume. STOP/CLOSE is a terminal write failure.
            if (!acceptWrites_) {
                if (lifecyclePaused_) {
                    return accepted > 0 ? accepted : WRITE_RESULT_PAUSED;
                }
                return accepted > 0 ? accepted : -1;
            }

            const int bytes = std::min(bufferBytes_, length - accepted);
            auto& dst = buffers_[writeIndex_];
            std::memcpy(dst.data(), data + accepted, bytes);
            writeIndex_ = (writeIndex_ + 1) % kQueueSlots;
            const SLresult result = (*bufferQueue_)->Enqueue(bufferQueue_, dst.data(), bytes);
            if (result != SL_RESULT_SUCCESS) {
                LOGE("OpenSL Enqueue failed: %u", result);
                return accepted > 0 ? accepted : -1;
            }
            // Publish queue ownership only after Enqueue succeeds. A completion callback for the
            // previous buffer may race this call; re-reading GetState makes either ordering converge
            // to the same authoritative count instead of incrementing/decrementing around a race.
            if (!refreshInFlightFromQueue(bufferQueue_)) {
                inFlight_.fetch_add(1, std::memory_order_acq_rel);
            }
            accepted += bytes;
            framesWritten_.fetch_add(bytes / frameBytes_);

            // Resume from a completely drained queue without ever entering PLAYING with zero PCM.
            // start() marks startPending_, wakes the writer, and the first retained/new PCM buffer
            // atomically becomes the prime that restarts SLPlayItf.
            if (startPending_ && inFlight_.load(std::memory_order_acquire) > 0) {
                startPending_ = false;
                if (!setPlayingLocked("prime_after_enqueue")) {
                    acceptWrites_ = false;
                    return -1;
                }
            }
        }
        return accepted;
    }

    void setVolume(float volume) override {
        if (!volume_) return;
        const float clamped = std::max(0.0f, std::min(1.0f, volume));
        if (clamped <= 0.0001f) {
            (*volume_)->SetMute(volume_, SL_BOOLEAN_TRUE);
            return;
        }
        (*volume_)->SetMute(volume_, SL_BOOLEAN_FALSE);
        const float mb = 2000.0f * std::log10(clamped);
        (*volume_)->SetVolumeLevel(volume_, static_cast<SLmillibel>(std::max(-9600.0f, mb)));
    }

    bool setOutputDevice(int32_t deviceId) override {
        LOGW("OpenSL setOutputDevice unsupported: requested deviceId=%d", deviceId);
        return false;
    }

    int64_t framesWritten() const override {
        return framesWritten_.load();
    }

private:
    // OpenSL callbacks run on an internal real-time thread. Android's NDK guidance explicitly
    // requires non-blocking synchronization here. Never take mutex_: start/pause/write may be in a
    // blocking OpenSL call while the implementation needs this callback thread to make progress.
    static void bufferQueueCallback(SLAndroidSimpleBufferQueueItf caller, void* context) {
        auto* self = static_cast<OpenSLOutput*>(context);
        if (!self) return;

        // GetState is a small Get-family call and keeps the software count authoritative even if a
        // completion callback is delivered late across PAUSED -> PLAYING. Blindly decrementing a
        // counter here can double-consume a completion that start() already reconciled from queue
        // state, leaving tracked=0 while the OpenSL queue still owns one buffer.
        if (caller && self->refreshInFlightFromQueue(caller)) {
            self->canWrite_.notify_one();
            return;
        }
        self->decrementInFlightNonBlocking();
        self->canWrite_.notify_one();
    }

    bool setPlayingLocked(const char* reason) {
        if (!play_) return false;
        const SLresult result = (*play_)->SetPlayState(play_, SL_PLAYSTATE_PLAYING);
        if (result != SL_RESULT_SUCCESS) {
            running_.store(false, std::memory_order_release);
            LOGE("OpenSL SetPlayState(PLAYING) failed: reason=%s result=%u", reason, result);
            return false;
        }
        SLuint32 state = SL_PLAYSTATE_STOPPED;
        const SLresult stateResult = (*play_)->GetPlayState(play_, &state);
        if (stateResult == SL_RESULT_SUCCESS && state != SL_PLAYSTATE_PLAYING) {
            running_.store(false, std::memory_order_release);
            LOGE("OpenSL PLAYING validation failed: reason=%s state=%u", reason, state);
            return false;
        }
        if (stateResult != SL_RESULT_SUCCESS) {
            LOGW("OpenSL GetPlayState after PLAYING failed: reason=%s result=%u", reason, stateResult);
        }
        running_.store(true, std::memory_order_release);
        LOGI(
            "OpenSL PLAYING: reason=%s queued=%d slots=%d",
            reason,
            inFlight_.load(std::memory_order_acquire),
            kQueueSlots
        );
        return true;
    }

    bool refreshInFlightFromQueue(
        SLAndroidSimpleBufferQueueItf queue,
        int* actualOut = nullptr,
        SLuint32* indexOut = nullptr
    ) {
        if (!queue) return false;
        SLAndroidSimpleBufferQueueState state = {};
        const SLresult result = (*queue)->GetState(queue, &state);
        if (result != SL_RESULT_SUCCESS) return false;
        const int actual = std::min<int>(static_cast<int>(state.count), kQueueSlots);
        inFlight_.store(actual, std::memory_order_release);
        if (actualOut) *actualOut = actual;
        if (indexOut) *indexOut = state.index;
        return true;
    }

    void decrementInFlightNonBlocking() {
        int current = inFlight_.load(std::memory_order_acquire);
        while (current > 0 &&
               !inFlight_.compare_exchange_weak(
                   current,
                   current - 1,
                   std::memory_order_acq_rel,
                   std::memory_order_acquire)) {
        }
    }

    void syncQueueStateLocked(const char* reason) {
        if (!bufferQueue_) return;
        const int tracked = inFlight_.load(std::memory_order_acquire);
        int actual = tracked;
        SLuint32 index = 0;
        if (!refreshInFlightFromQueue(bufferQueue_, &actual, &index)) {
            LOGW("OpenSL queue GetState failed: reason=%s tracked=%d", reason, tracked);
            return;
        }
        if (tracked != actual) {
            LOGW("OpenSL queue ownership reconciled: reason=%s tracked=%d actual=%d index=%u",
                 reason,
                 tracked,
                 actual,
                 index);
        } else {
            LOGI("OpenSL queue state: reason=%s count=%d index=%u", reason, actual, index);
        }
    }

    void destroy() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            lifecyclePaused_ = false;
            acceptWrites_ = false;
            startPending_ = false;
            running_.store(false, std::memory_order_release);
            canWrite_.notify_all();
        }
        if (playerObject_) {
            (*playerObject_)->Destroy(playerObject_);
            playerObject_ = nullptr;
        }
        play_ = nullptr;
        bufferQueue_ = nullptr;
        volume_ = nullptr;
        if (outputMixObject_) {
            (*outputMixObject_)->Destroy(outputMixObject_);
            outputMixObject_ = nullptr;
        }
        if (engineObject_) {
            (*engineObject_)->Destroy(engineObject_);
            engineObject_ = nullptr;
        }
        engine_ = nullptr;
    }

    const int sampleRate_;
    const int channels_;
    const int format_;
    const int frameBytes_;
    const int bufferFrames_;

    static constexpr int kQueueSlots = 4;

    mutable std::mutex mutex_;
    std::condition_variable canWrite_;
    std::atomic<bool> running_{false};
    std::atomic<int> inFlight_{0};
    bool lifecyclePaused_ = false;
    bool acceptWrites_ = true;
    bool startPending_ = false;
    int writeIndex_ = 0;
    int bufferBytes_ = 0;
    std::vector<uint8_t> buffers_[kQueueSlots];
    std::atomic<int64_t> framesWritten_{0};

    SLObjectItf engineObject_ = nullptr;
    SLEngineItf engine_ = nullptr;
    SLObjectItf outputMixObject_ = nullptr;
    SLObjectItf playerObject_ = nullptr;
    SLPlayItf play_ = nullptr;
    SLAndroidSimpleBufferQueueItf bufferQueue_ = nullptr;
    SLVolumeItf volume_ = nullptr;
};

static NativeOutput* fromHandle(jlong handle) {
    return reinterpret_cast<NativeOutput*>(handle);
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_NativeAudioEngine_nativeCreate(
    JNIEnv*,
    jclass,
    jint mode,
    jint sampleRate,
    jint channels,
    jint format,
    jint bufferFrames,
    jint preferredDeviceId,
    jint spatializationBehavior,
    jboolean contentSpatialized
) {
    if (sampleRate <= 0 || channels <= 0 || channels > 2) return 0;

    if (mode == MODE_OPENSL_ES) {
        auto* output = new OpenSLOutput(sampleRate, channels, format, bufferFrames);
        if (output->open()) {
            rawsmusic::transition_trace::record(
                rawsmusic::transition_trace::EventCode::OutputCreate,
                static_cast<int64_t>(mode),
                (static_cast<int64_t>(sampleRate) << 32) | (static_cast<uint32_t>(channels))
            );
            return reinterpret_cast<jlong>(output);
        }
        delete output;
        return 0;
    }

    auto* output = new AAudioOutput(
        mode,
        sampleRate,
        channels,
        format,
        bufferFrames,
        preferredDeviceId,
        spatializationBehavior,
        contentSpatialized == JNI_TRUE
    );
    if (output->open()) {
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::OutputCreate,
            static_cast<int64_t>(mode),
            (static_cast<int64_t>(sampleRate) << 32) | (static_cast<uint32_t>(channels))
        );
        return reinterpret_cast<jlong>(output);
    }
    delete output;
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_NativeAudioEngine_nativeStart(JNIEnv*, jclass, jlong handle) {
    auto* output = fromHandle(handle);
    const bool started = output && output->start();
    rawsmusic::transition_trace::record(
        rawsmusic::transition_trace::EventCode::OutputStart,
        static_cast<int64_t>(handle),
        started ? 1 : 0
    );
    return started ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_NativeAudioEngine_nativePause(JNIEnv*, jclass, jlong handle) {
    if (auto* output = fromHandle(handle)) {
        output->pause();
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::OutputPause,
            static_cast<int64_t>(handle),
            output->framesWritten()
        );
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_NativeAudioEngine_nativeStop(JNIEnv*, jclass, jlong handle) {
    if (auto* output = fromHandle(handle)) {
        output->stop();
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::OutputStop,
            static_cast<int64_t>(handle),
            output->framesWritten()
        );
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_NativeAudioEngine_nativeFlush(JNIEnv*, jclass, jlong handle) {
    if (auto* output = fromHandle(handle)) {
        output->flush();
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::OutputFlush,
            static_cast<int64_t>(handle),
            output->framesWritten()
        );
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_NativeAudioEngine_nativeWrite(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray buffer,
    jint offset,
    jint length
) {
    auto* output = fromHandle(handle);
    if (!output || !buffer || offset < 0 || length <= 0) return -1;
    const jsize size = env->GetArrayLength(buffer);
    if (offset > size || length > size - offset) return -1;
    jbyte* bytes = env->GetByteArrayElements(buffer, nullptr);
    if (!bytes) return -1;
    const int64_t framesBefore = output->framesWritten();
    const int result = output->write(reinterpret_cast<uint8_t*>(bytes) + offset, length);
    env->ReleaseByteArrayElements(buffer, bytes, JNI_ABORT);
    if (result > 0 && framesBefore == 0) {
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::OutputFirstWrite,
            static_cast<int64_t>(result),
            output->framesWritten()
        );
    } else if (result < 0) {
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::OutputWriteError,
            static_cast<int64_t>(result),
            output->framesWritten()
        );
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_NativeAudioEngine_nativeSetVolume(
    JNIEnv*,
    jclass,
    jlong handle,
    jfloat volume
) {
    if (auto* output = fromHandle(handle)) output->setVolume(volume);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_NativeAudioEngine_nativeSetOutputDevice(
    JNIEnv*,
    jclass,
    jlong handle,
    jint deviceId
) {
    auto* output = fromHandle(handle);
    const bool changed = output && output->setOutputDevice(deviceId);
    rawsmusic::transition_trace::record(
        rawsmusic::transition_trace::EventCode::OutputRouteChanged,
        static_cast<int64_t>(deviceId),
        changed ? 1 : 0
    );
    return changed ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_NativeAudioEngine_nativeGetFramesWritten(JNIEnv*, jclass, jlong handle) {
    auto* output = fromHandle(handle);
    return output ? static_cast<jlong>(output->framesWritten()) : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_NativeAudioEngine_nativeClose(JNIEnv*, jclass, jlong handle) {
    if (auto* output = fromHandle(handle)) {
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::OutputClose,
            static_cast<int64_t>(handle),
            output->framesWritten()
        );
        delete output;
    }
}
