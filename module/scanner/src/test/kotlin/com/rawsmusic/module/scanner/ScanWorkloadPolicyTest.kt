package com.rawsmusic.module.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanWorkloadPolicyTest {
    @Test fun restoresOriginalPriorityAfterSuccess() {
        var priority = 0
        val policy = ScanWorkloadPolicy({ priority }, { priority = it })
        val value = policy.execute { assertEquals(1, priority); 42 }
        assertEquals(42, value)
        assertEquals(0, priority)
    }
    @Test fun restoresOriginalPriorityAfterException() {
        var priority = 3
        val policy = ScanWorkloadPolicy({ priority }, { priority = it })
        try {
            policy.execute { assertEquals(1, priority); throw IllegalStateException("fixture") }
            fail("Expected failure")
        } catch (_: IllegalStateException) { }
        assertEquals(3, priority)
    }
}
