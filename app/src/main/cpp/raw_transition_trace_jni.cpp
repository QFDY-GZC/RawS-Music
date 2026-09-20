#include <jni.h>

#include "raw_transition_trace.h"

extern "C" JNIEXPORT void JNICALL
Java_com_rawsmusic_module_player_transition_NativeTransitionTrace_nativeSetContext(
    JNIEnv*,
    jclass,
    jlong sessionId,
    jlong transitionId,
    jint generation,
    jlong outputGeneration,
    jint reason
) {
    rawsmusic::transition_trace::setContext(
        static_cast<int64_t>(sessionId),
        static_cast<int64_t>(transitionId),
        static_cast<int32_t>(generation),
        static_cast<int64_t>(outputGeneration),
        static_cast<int32_t>(reason)
    );
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_rawsmusic_module_player_transition_NativeTransitionTrace_nativeSnapshot(
    JNIEnv* env,
    jclass
) {
    const std::string snapshot = rawsmusic::transition_trace::snapshot();
    return env->NewStringUTF(snapshot.c_str());
}
