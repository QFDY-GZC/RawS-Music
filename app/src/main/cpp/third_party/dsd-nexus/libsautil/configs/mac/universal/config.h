/*
 * Universal binary config wrapper for macOS
 * Dispatches to architecture-specific config based on compilation target
 */
#ifdef __aarch64__
#include "../arm64/config.h"
#elif defined(__x86_64__)
#include "../x64/config.h"
#else
#error "Unsupported architecture for macOS universal build"
#endif
