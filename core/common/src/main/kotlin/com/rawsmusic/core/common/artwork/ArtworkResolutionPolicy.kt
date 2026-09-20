package com.rawsmusic.core.common.artwork

/**
 * Central album-art resolution policy.
 *
 * The experimental resolution preference selects a larger high tier rather than multiplying every
 * request. High-resolution and 24-bit decode options are disabled on very small heap classes.
 */
object ArtworkResolutionPolicy {
    const val LARGE_DISPLAY_MIN_SIDE_PX = 1000
    const val QUALITY_MIN_HEAP_MIB = 128

    const val SMALL_DISPLAY_LOW_SIDE = 256
    const val LARGE_DISPLAY_LOW_SIDE = 512

    const val SMALL_DISPLAY_NORMAL_HIGH_SIDE = 512
    const val SMALL_DISPLAY_INCREASED_HIGH_SIDE = 1024
    const val LARGE_DISPLAY_NORMAL_HIGH_SIDE = 1024
    const val LARGE_DISPLAY_INCREASED_HIGH_SIDE = 1536

    fun isLargeDisplay(displayShortSidePx: Int): Boolean =
        displayShortSidePx >= LARGE_DISPLAY_MIN_SIDE_PX

    fun qualityFeatureSupported(maxMemoryBytes: Long): Boolean =
        maxMemoryBytes / MIB >= QUALITY_MIN_HEAP_MIB

    /** Low provider wrapper selected when request flag bit 0 is clear. */
    fun lowTargetSide(displayShortSidePx: Int): Int =
        if (isLargeDisplay(displayShortSidePx)) LARGE_DISPLAY_LOW_SIDE else SMALL_DISPLAY_LOW_SIDE

    /** High provider wrapper selected when request flag bit 0 is set. */
    fun highTargetSide(
        increaseResolution: Boolean,
        displayShortSidePx: Int,
        maxMemoryBytes: Long,
    ): Int {
        val increased = increaseResolution && qualityFeatureSupported(maxMemoryBytes)
        return if (isLargeDisplay(displayShortSidePx)) {
            if (increased) LARGE_DISPLAY_INCREASED_HIGH_SIDE else LARGE_DISPLAY_NORMAL_HIGH_SIDE
        } else {
            if (increased) SMALL_DISPLAY_INCREASED_HIGH_SIDE else SMALL_DISPLAY_NORMAL_HIGH_SIDE
        }
    }

    /** Higher color precision is ignored below the same minimum heap gate. */
    fun use24BitRgbEffective(requested: Boolean, maxMemoryBytes: Long): Boolean =
        requested && qualityFeatureSupported(maxMemoryBytes)

    private const val MIB = 1024L * 1024L
}
