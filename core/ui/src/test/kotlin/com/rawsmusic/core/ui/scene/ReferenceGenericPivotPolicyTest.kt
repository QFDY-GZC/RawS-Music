package com.rawsmusic.core.ui.scene

import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceGenericPivotPolicyTest {
    @Test
    fun forwardExpandsCurrentAndSettlesHalfScaleDestination() {
        assertEquals(1f, resolveReferenceGenericPivotScale(false, true, 0f), 0f)
        assertEquals(1.5f, resolveReferenceGenericPivotScale(false, true, 1f), 0f)
        assertEquals(0.5f, resolveReferenceGenericPivotScale(false, false, 0f), 0f)
        assertEquals(1f, resolveReferenceGenericPivotScale(false, false, 1f), 0f)
    }

    @Test
    fun backIsExactRetainedCurrentRoleReverse() {
        assertEquals(1.5f, resolveReferenceGenericPivotScale(true, true, 0f), 0f)
        assertEquals(1f, resolveReferenceGenericPivotScale(true, true, 1f), 0f)
        assertEquals(1f, resolveReferenceGenericPivotScale(true, false, 0f), 0f)
        assertEquals(0.5f, resolveReferenceGenericPivotScale(true, false, 1f), 0f)
    }
}
