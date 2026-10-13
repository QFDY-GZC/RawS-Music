package com.rawsmusic.module.data.repository

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class LibraryWriteFailurePolicyTest {
    @Test fun scanCommitPropagatesFailure() {
        val error = IllegalStateException("database failed")
        try { LibraryWriteFailurePolicy.check(error, true); fail("Write failure swallowed") }
        catch (actual: IllegalStateException) { assertSame(error, actual) }
    }
    @Test fun cancellationNeverBecomesSuccessfulCommit() {
        val error = CancellationException("cancelled")
        try { LibraryWriteFailurePolicy.check(error, false); fail("Cancellation swallowed") }
        catch (actual: CancellationException) { assertSame(error, actual) }
    }
    @Test fun nonScanCallKeepsLegacyFailureHandling() {
        LibraryWriteFailurePolicy.check(IllegalStateException("legacy"), false)
    }
}
