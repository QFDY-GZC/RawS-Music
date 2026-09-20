#include "raw_pcm_neon_codec.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#if defined(__aarch64__) || defined(__ARM_NEON)
#include <arm_neon.h>
#endif

namespace rawsmusic::dsp {

int convertS32ToS16Rounded(
        const std::uint8_t* source,
        int sourceBytes,
        std::uint8_t* destination,
        int destinationCapacity) {
    if (!source || !destination || sourceBytes <= 0 || destinationCapacity <= 0) return 0;

    const int samples = std::min(sourceBytes / 4, destinationCapacity / 2);
    if (samples <= 0) return 0;

#if defined(__aarch64__) || defined(__ARM_NEON)
    int index = 0;
    for (; index + 8 <= samples; index += 8) {
        const auto* input = reinterpret_cast<const std::int32_t*>(source + index * 4);
        auto* output = reinterpret_cast<std::int16_t*>(destination + index * 2);
        const int32x4_t first = vld1q_s32(input);
        const int32x4_t second = vld1q_s32(input + 4);
        const int16x4_t first16 = vqrshrn_n_s32(first, 16);
        const int16x4_t second16 = vqrshrn_n_s32(second, 16);
        vst1q_s16(output, vcombine_s16(first16, second16));
    }
    for (; index + 4 <= samples; index += 4) {
        const auto* input = reinterpret_cast<const std::int32_t*>(source + index * 4);
        auto* output = reinterpret_cast<std::int16_t*>(destination + index * 2);
        vst1_s16(output, vqrshrn_n_s32(vld1q_s32(input), 16));
    }
#else
    int index = 0;
#endif

    // Keep a scalar tail for short or unaligned buffers. nearbyint matches the
    // rounded and saturating semantics of the NEON path.
    for (int i = index; i < samples; ++i) {
        std::int32_t value = 0;
        std::memcpy(&value, source + i * 4, sizeof(value));
        const auto rounded = static_cast<long>(std::nearbyint(
                static_cast<double>(value) / 65536.0));
        const auto clamped = std::clamp<long>(rounded, -32768L, 32767L);
        const auto sample = static_cast<std::int16_t>(clamped);
        std::memcpy(destination + i * 2, &sample, sizeof(sample));
    }
    return samples * 2;
}

}  // namespace rawsmusic::dsp
