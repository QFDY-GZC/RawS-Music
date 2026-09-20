package com.rawsmusic.module.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryScanSummaryTest {
    @Test
    fun `classifies an empty scan`() {
        assertEquals(
            LibraryScanSummary.Outcome.EMPTY,
            LibraryScanSummary(0, 0, 0, 0, 400).outcome
        )
    }

    @Test
    fun `added songs take precedence over metadata changes`() {
        assertEquals(
            LibraryScanSummary.Outcome.ADDED,
            LibraryScanSummary(40, 3, 2, 1, 1_500).outcome
        )
    }

    @Test
    fun `detects updates and removals without additions`() {
        assertEquals(
            LibraryScanSummary.Outcome.CHANGED,
            LibraryScanSummary(40, 0, 2, 1, 1_500).outcome
        )
    }

    @Test
    fun `detects a completed scan without changes`() {
        assertEquals(
            LibraryScanSummary.Outcome.UNCHANGED,
            LibraryScanSummary(40, 0, 0, 0, 1_500).outcome
        )
    }
}
