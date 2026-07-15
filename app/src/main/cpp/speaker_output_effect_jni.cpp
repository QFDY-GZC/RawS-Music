#include "speaker_output_effect.h"

/*
 * JNI entry points are intentionally implemented in speaker_output_effect.cpp.
 * Keeping this translation unit empty avoids a second native API surface and
 * duplicate JNI symbols if it is added to CMake by mistake in the future.
 */
