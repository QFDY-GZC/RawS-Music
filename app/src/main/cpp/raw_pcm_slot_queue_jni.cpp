#include <jni.h>

#include "raw_pcm_slot_queue.h"

namespace {

bool validRange(JNIEnv* env, jbyteArray array, jint offset, jint length) noexcept {
    if (array == nullptr || offset < 0 || length < 0) return false;
    const jsize size = env->GetArrayLength(array);
    return offset <= size && length <= size - offset;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_transition_NativePcmSlotQueue_nativeMaximumSlotBytes(
    JNIEnv*,
    jclass
) {
    return static_cast<jint>(rawsmusic::pcm_slot_queue::maximumSlotBytes());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_rawsmusic_module_player_transition_NativePcmSlotQueue_nativeIsConfigured(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation
) {
    return rawsmusic::pcm_slot_queue::matches(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        rawsmusic::pcm_slot_queue::QueueRole::Pending
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_transition_NativePcmSlotQueue_nativeAppendPending(
    JNIEnv* env,
    jclass,
    jlong decoderSerial,
    jint generation,
    jbyteArray source,
    jint offset,
    jint length
) {
    if (!validRange(env, source, offset, length)) return -1;
    jboolean isCopy = JNI_FALSE;
    auto* bytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(source, &isCopy));
    if (bytes == nullptr) return -1;
    const int32_t result = rawsmusic::pcm_slot_queue::appendPending(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        reinterpret_cast<const uint8_t*>(bytes + offset),
        static_cast<int32_t>(length)
    );
    env->ReleasePrimitiveArrayCritical(source, bytes, JNI_ABORT);
    return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_transition_NativePcmSlotQueue_nativeRead(
    JNIEnv* env,
    jclass,
    jlong decoderSerial,
    jint generation,
    jbyteArray destination,
    jint offset,
    jint maxLength
) {
    if (!validRange(env, destination, offset, maxLength)) return -1;
    jboolean isCopy = JNI_FALSE;
    auto* bytes = static_cast<jbyte*>(env->GetPrimitiveArrayCritical(destination, &isCopy));
    if (bytes == nullptr) return -1;
    const int32_t result = rawsmusic::pcm_slot_queue::read(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation),
        reinterpret_cast<uint8_t*>(bytes + offset),
        static_cast<int32_t>(maxLength)
    );
    env->ReleasePrimitiveArrayCritical(destination, bytes, 0);
    return static_cast<jint>(result);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_rawsmusic_module_player_transition_NativePcmSlotQueue_nativeAvailableBytes(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation
) {
    return static_cast<jint>(rawsmusic::pcm_slot_queue::availableBytes(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation)
    ));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_transition_NativePcmSlotQueue_nativeAvailableFrames(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation
) {
    return static_cast<jlong>(rawsmusic::pcm_slot_queue::availableFrames(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation)
    ));
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_transition_NativePcmSlotQueue_nativeTotalWrittenFrames(
    JNIEnv*,
    jclass,
    jlong decoderSerial,
    jint generation
) {
    return static_cast<jlong>(rawsmusic::pcm_slot_queue::totalWrittenFrames(
        static_cast<int64_t>(decoderSerial),
        static_cast<int32_t>(generation)
    ));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_transition_NativePcmSlotQueue_nativeSnapshot(
    JNIEnv* env,
    jclass
) {
    const std::string value = rawsmusic::pcm_slot_queue::snapshot();
    return env->NewStringUTF(value.c_str());
}
