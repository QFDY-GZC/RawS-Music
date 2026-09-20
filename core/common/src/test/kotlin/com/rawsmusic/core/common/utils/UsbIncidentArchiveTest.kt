package com.rawsmusic.core.common.utils

import org.junit.Assert.*
import org.junit.Test

class UsbIncidentArchiveTest {
    @Test fun intactEvidenceIsUnchanged() {
        assertEquals("BEGIN\nEND\n", UsbIncidentArchive.describePersistedLog("BEGIN\nEND\n"))
    }

    @Test fun lostBytesAreExplicitInsteadOfExportingInvisiblePadding() {
        val result = UsbIncidentArchive.describePersistedLog("\u0000\u0000last operation\n")
        assertTrue(result.contains("2 NUL bytes"))
        assertTrue(result.endsWith("last operation\n"))
        assertFalse(result.contains('\u0000'))
    }

    @Test fun entirelyLostLogIsNotReportedAsEmptySuccessfulLog() {
        assertTrue(UsbIncidentArchive.describePersistedLog("\u0000").contains("content lost/unavailable"))
    }
}
