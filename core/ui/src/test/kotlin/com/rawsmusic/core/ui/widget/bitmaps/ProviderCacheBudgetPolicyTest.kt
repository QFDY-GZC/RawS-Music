package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderCacheBudgetPolicyTest {
    @Test
    fun heapFractionBoundariesArePreservedOnModernAndroid() {
        assertEquals(25, ProviderCacheBudgetPolicy.heapFraction(63, 26).numerator)
        assertEquals(30, ProviderCacheBudgetPolicy.heapFraction(64, 26).numerator)
        assertEquals(30, ProviderCacheBudgetPolicy.heapFraction(191, 26).numerator)
        assertEquals(80, ProviderCacheBudgetPolicy.heapFraction(192, 26).numerator)
        assertEquals(80, ProviderCacheBudgetPolicy.heapFraction(255, 26).numerator)
        assertEquals(100, ProviderCacheBudgetPolicy.heapFraction(256, 26).numerator)
    }

    @Test
    fun preOBranchesKeepSmallerLargeHeapFractions() {
        assertEquals(35, ProviderCacheBudgetPolicy.heapFraction(192, 25).numerator)
        assertEquals(35, ProviderCacheBudgetPolicy.heapFraction(255, 25).numerator)
        assertEquals(60, ProviderCacheBudgetPolicy.heapFraction(256, 25).numerator)
    }

    @Test
    fun byteBudgetUsesWholeMiBLikeProviderConstructor() {
        val mib = 1024L * 1024L
        assertEquals(8 * 1024 * 1024, ProviderCacheBudgetPolicy.byteBudget(32 * mib + 999_999L, 35))
        assertEquals(153 * 1024 * 1024 + 629_145, ProviderCacheBudgetPolicy.byteBudget(192 * mib, 35))
        assertEquals(256 * 1024 * 1024, ProviderCacheBudgetPolicy.byteBudget(256 * mib, 35))
    }
}
