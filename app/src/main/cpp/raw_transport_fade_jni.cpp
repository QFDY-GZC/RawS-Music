#include <jni.h>

#include <cstdint>

#include "raw_transport_fade.h"
#include "raw_transition_trace.h"

namespace {

using rawsmusic::transport_fade::Direction;
using rawsmusic::transport_fade::Processor;
using rawsmusic::transport_fade::SampleFormat;

Processor* from_handle(jlong handle) noexcept {
    return reinterpret_cast<Processor*>(handle);
}

Direction direction_from_int(jint direction) noexcept {
    switch (direction) {
        case 1: return Direction::FadeIn;
        case 2: return Direction::FadeOut;
        default: return Direction::Clear;
    }
}

SampleFormat format_from_int(jint format) noexcept {
    switch (format) {
        case 2: return SampleFormat::PcmS32;
        case 3: return SampleFormat::PcmFloat;
        case 4: return SampleFormat::PcmS24Packed;
        default: return SampleFormat::PcmS16;
    }
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_NativeTransportFadeProcessor_nativeCreate(
    JNIEnv*,
    jclass
) {
    return reinterpret_cast<jlong>(new Processor());
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_NativeTransportFadeProcessor_nativeArm(
    JNIEnv*,
    jclass,
    jlong handle,
    jint direction,
    jint duration_ms
) {
    if (auto* processor = from_handle(handle)) {
        processor->arm(direction_from_int(direction), duration_ms);
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::TransportFadeArm,
            static_cast<int64_t>(direction),
            static_cast<int64_t>(duration_ms)
        );
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_NativeTransportFadeProcessor_nativeClear(
    JNIEnv*,
    jclass,
    jlong handle
) {
    if (auto* processor = from_handle(handle)) {
        processor->clear();
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::TransportFadeClear,
            static_cast<int64_t>(handle),
            0
        );
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_NativeTransportFadeProcessor_nativeIsActive(
    JNIEnv*,
    jclass,
    jlong handle
) {
    const auto* processor = from_handle(handle);
    return processor && processor->is_active_or_pending() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_rawsmusic_module_player_NativeTransportFadeProcessor_nativeCurrentGain(
    JNIEnv*,
    jclass,
    jlong handle
) {
    const auto* processor = from_handle(handle);
    return processor ? processor->current_gain() : 1.0f;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_NativeTransportFadeProcessor_nativeCompletionSerial(
    JNIEnv*,
    jclass,
    jlong handle
) {
    const auto* processor = from_handle(handle);
    return processor ? static_cast<jlong>(processor->completion_serial()) : 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_NativeTransportFadeProcessor_nativeProcess(
    JNIEnv* env,
    jclass,
    jlong handle,
    jbyteArray buffer,
    jint offset,
    jint length,
    jint sample_rate,
    jint frame_size,
    jint format
) {
    auto* processor = from_handle(handle);
    if (!processor || !buffer || offset < 0 || length <= 0 || frame_size <= 0) return JNI_FALSE;
    const jsize array_length = env->GetArrayLength(buffer);
    if (offset > array_length || length > array_length - offset) return JNI_FALSE;

    auto* bytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(buffer, nullptr));
    if (!bytes) return JNI_FALSE;
    const uint64_t completion_before = processor->completion_serial();
    processor->process(
        reinterpret_cast<uint8_t*>(bytes) + offset,
        static_cast<size_t>(length),
        sample_rate,
        frame_size,
        format_from_int(format)
    );
    env->ReleasePrimitiveArrayCritical(buffer, bytes, 0);
    const uint64_t completion_after = processor->completion_serial();
    if (completion_after != completion_before) {
        rawsmusic::transition_trace::record(
            rawsmusic::transition_trace::EventCode::TransportFadeComplete,
            static_cast<int64_t>(completion_after),
            static_cast<int64_t>(processor->current_gain() * 1000000.0f)
        );
    }
    return processor->is_active_or_pending() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_NativeTransportFadeProcessor_nativeClose(
    JNIEnv*,
    jclass,
    jlong handle
) {
    delete from_handle(handle);
}
