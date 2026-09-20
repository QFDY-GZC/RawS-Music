package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Reference baseline implementation provider-cache heap budget translated to Raw's byte-accounted cache.
 *
 * baseline implementation stores capacity as a count of low-resolution wrapper units. A low wrapper costs 1;
 * a high wrapper costs `highBytes / lowBytes`, and the constructor computes the capacity as:
 *
 *     round(maxMemoryMiB * heapFraction / lowWrapperMiB)
 *
 * Therefore the low/high dimensions and Bitmap.Config cancel when the same policy is expressed as
 * a byte budget. Raw counts the wrappers by their real allocationByteCount, so keep the derived
 * heap fraction and use it as the byte-equivalent limit instead of inventing an entry count.
 *
 * The SDK split is also direct baseline implementation constructor behaviour. The 128 MiB preference/config
 * gates affect decode dimensions/config and the size of one weighted unit, but not the equivalent
 * heap fraction itself.
 */
internal object ProviderCacheBudgetPolicy {
    private const val MIB = 1024L * 1024L

    data class Fraction(val numerator: Int, val denominator: Int = 100) {
        init {
            require(numerator >= 0)
            require(denominator > 0)
        }
    }

    fun heapFraction(maxMemoryMiB: Int, sdkInt: Int): Fraction = when {
        maxMemoryMiB < 64 -> Fraction(25)
        maxMemoryMiB < 192 -> Fraction(30)
        maxMemoryMiB < 256 -> if (sdkInt >= 26) Fraction(80) else Fraction(35)
        else -> if (sdkInt >= 26) Fraction(100) else Fraction(60)
    }

    fun byteBudget(maxMemoryBytes: Long, sdkInt: Int): Int {
        if (maxMemoryBytes <= 0L) return 0

        // baseline implementation first truncates Runtime.maxMemory() to whole MiB before choosing/scaling the
        // provider capacity. Preserve that boundary instead of deriving the fraction from raw bytes.
        val maxMemoryMiB = (maxMemoryBytes / MIB)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
            .coerceAtLeast(1)
        val fraction = heapFraction(maxMemoryMiB, sdkInt)
        val budget = maxMemoryMiB.toLong() * MIB * fraction.numerator / fraction.denominator
        return budget.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }
}
