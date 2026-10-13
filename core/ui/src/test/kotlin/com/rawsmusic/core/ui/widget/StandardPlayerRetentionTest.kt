package com.rawsmusic.core.ui.widget

import org.junit.Assert.*
import org.junit.Test

class StandardPlayerRetentionTest {
    @Test fun repeatedEntryKeepsOneMountedHostButOnlyFreezesHiddenEndpoint() {
        for (visible in listOf(false, true, false, true, false)) {
            val hidden = retainStandardPlayerOffstage(false, true, visible, false)
            assertTrue(visible || hidden)
            assertEquals(!visible, hidden)
        }
    }

    @Test fun auxiliaryPagesAreNotFrozenAsHiddenMain() {
        assertFalse(retainStandardPlayerOffstage(false, true, false, true))
    }

    @Test fun immersiveAndEmptyQueueDoNotRetainAStandardPlayer() {
        assertFalse(retainStandardPlayerOffstage(true, true, false, false))
        assertFalse(retainStandardPlayerOffstage(false, false, false, false))
    }
}
