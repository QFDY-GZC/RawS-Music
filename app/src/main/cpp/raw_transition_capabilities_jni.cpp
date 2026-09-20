#include <jni.h>

#include "raw_transition_capabilities.h"

extern "C" JNIEXPORT jlong JNICALL
Java_com_rawsmusic_module_player_transition_NativeTransitionCapabilities_nativeCompiledFeatureMask(
        JNIEnv*, jclass) {
    return static_cast<jlong>(
            rawsmusic::transition_capabilities::compiledFeatureMask());
}
