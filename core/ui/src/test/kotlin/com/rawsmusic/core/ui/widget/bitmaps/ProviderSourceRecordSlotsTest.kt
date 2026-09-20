package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSourceRecordSlotsTest {
    @Test
    fun occupiedReferenceLaneDoesNotOverwrite() {
        val record = ProviderSourceRecordSlots<String>()

        assertTrue(record.installIfEmpty(highRes = false, value = "low-384"))
        assertFalse(record.installIfEmpty(highRes = false, value = "low-512"))
        assertEquals("low-384", record.low)

        assertTrue(record.installIfEmpty(highRes = true, value = "hi-1024"))
        assertFalse(record.installIfEmpty(highRes = true, value = "hi-1440"))
        assertEquals("hi-1024", record.high)
    }

    @Test
    fun rawVariableLaneUpgradeRequiresSameCurrentOwner() {
        val record = ProviderSourceRecordSlots<String>()
        assertTrue(record.installIfEmpty(highRes = false, value = "old"))

        assertFalse(record.replaceIfSame(highRes = false, expected = "stale", replacement = "new"))
        assertEquals("old", record.low)

        assertTrue(record.replaceIfSame(highRes = false, expected = "old", replacement = "new"))
        assertEquals("new", record.low)
    }

    @Test
    fun publicationNeverDowngradesOneRawLane() {
        assertEquals(
            ProviderSourceSlotPublication.INSTALL,
            resolveProviderSourceSlotPublication(existingCoverageSide = null, incomingCoverageSide = 384),
        )
        assertEquals(
            ProviderSourceSlotPublication.KEEP_EXISTING,
            resolveProviderSourceSlotPublication(existingCoverageSide = 512, incomingCoverageSide = 384),
        )
        assertEquals(
            ProviderSourceSlotPublication.KEEP_EXISTING,
            resolveProviderSourceSlotPublication(existingCoverageSide = 512, incomingCoverageSide = 512),
        )
        assertEquals(
            ProviderSourceSlotPublication.UPGRADE_LARGER,
            resolveProviderSourceSlotPublication(existingCoverageSide = 384, incomingCoverageSide = 512),
        )
    }

    @Test
    fun sourceRecordLivesUntilBothLanesAreEmpty() {
        val record = ProviderSourceRecordSlots<String>()
        record.installIfEmpty(highRes = false, value = "low")
        record.installIfEmpty(highRes = true, value = "high")

        record.removeIfSame(highRes = false, value = "low")
        assertNull(record.low)
        assertEquals("high", record.high)
        assertFalse(record.isEmpty())

        record.removeIfSame(highRes = true, value = "high")
        assertTrue(record.isEmpty())
    }

    @Test
    fun lowLaneIsPreferredWhenItSatisfiesRequest() {
        val record = ProviderSourceRecordSlots<String>()
        record.installIfEmpty(highRes = false, value = "low")
        record.installIfEmpty(highRes = true, value = "high")

        assertEquals("low", record.firstUsable { true })
        assertEquals("high", record.firstUsable { it == "high" })
    }

    @Test
    fun sourceRecordTracksIdentityAliasesButOnlyOneProviderSourceString() {
        val record = ProviderSourceRecordSlots<String>()

        record.registerIdentity("identity-a")
        record.registerIdentity("identity-b")
        assertTrue(record.registerSourceStringIfAbsent("/music/album/track.flac|123|456"))
        assertFalse(record.registerSourceStringIfAbsent("/music/album/folder.jpg|789|999"))

        assertEquals(listOf("identity-a", "identity-b"), record.identityAliasesSnapshot())
        assertEquals("/music/album/track.flac|123|456", record.sourceStringOrNull())

        record.unregisterIdentity("identity-a")
        assertEquals(listOf("identity-b"), record.identityAliasesSnapshot())

        record.clearIndexes()
        assertTrue(record.identityAliasesSnapshot().isEmpty())
        assertNull(record.sourceStringOrNull())
    }
}
