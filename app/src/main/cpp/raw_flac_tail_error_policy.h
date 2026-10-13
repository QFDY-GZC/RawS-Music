#pragma once
#include <cstdint>

inline bool canSkipFlacTailDamage(bool isFlac, bool invalidData, int64_t durationMs,
        int64_t lastDecodedEndMs, unsigned consecutiveErrors) {
    return isFlac && invalidData && durationMs > 0 && lastDecodedEndMs > 0 &&
        lastDecodedEndMs >= durationMs - 2000 && lastDecodedEndMs <= durationMs + 2000 &&
        consecutiveErrors < 16;
}
