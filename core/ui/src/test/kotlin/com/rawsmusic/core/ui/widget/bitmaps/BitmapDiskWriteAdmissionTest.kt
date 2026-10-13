package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.*
import org.junit.Test

class BitmapDiskWriteAdmissionTest {
    @Test fun taskLimitRejectsDistinctPicturesAndReleaseAllowsNewWork() {
        val admission = BitmapDiskWriteAdmission(maxTasks = 2, maxBytes = 100)
        assertTrue(admission.tryAcquire("a", 10))
        assertFalse(admission.tryAcquire("a", 10))
        assertTrue(admission.tryAcquire("b", 10))
        assertFalse(admission.tryAcquire("c", 10))
        admission.release("a")
        admission.release("a")
        assertTrue(admission.tryAcquire("c", 10))
        assertFalse(admission.tryAcquire("d", 10))
    }
    @Test fun byteLimitIncludesRunningAndPendingBitmaps() {
        val admission = BitmapDiskWriteAdmission(maxTasks = 10, maxBytes = 100)
        assertFalse(admission.tryAcquire("oversize", 101))
        assertFalse(admission.tryAcquire("empty", 0))
        assertTrue(admission.tryAcquire("a", 60))
        assertFalse(admission.tryAcquire("b", 41))
        assertTrue(admission.tryAcquire("b", 40))
        admission.release("a")
        assertTrue(admission.tryAcquire("c", 60))
    }
}
