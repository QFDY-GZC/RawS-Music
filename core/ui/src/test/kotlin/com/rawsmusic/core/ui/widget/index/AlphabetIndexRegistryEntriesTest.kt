package com.rawsmusic.core.ui.widget.index

import org.junit.Assert.*
import org.junit.Test

class AlphabetIndexRegistryEntriesTest {
    private data class Entry(val owner: Int, val data: String)
    @Test fun unchangedPublicationKeepsSnapshotIdentity() {
        val entries = listOf(Entry(1, "artists"))
        assertSame(entries, upsertAlphabetIndexEntry(entries, Entry(1, "artists")) { a, b -> a.owner == b.owner })
    }
    @Test fun updatedOwnerDoesNotDuplicateOrRemoveOtherScene() {
        val entries = listOf(Entry(1, "artists"), Entry(2, "albums"))
        assertEquals(listOf(Entry(1, "new artists"), Entry(2, "albums")),
            upsertAlphabetIndexEntry(entries, Entry(1, "new artists")) { a, b -> a.owner == b.owner })
    }
    @Test fun newOwnerIsAppended() {
        assertEquals(listOf(Entry(1, "artists"), Entry(2, "albums")),
            upsertAlphabetIndexEntry(listOf(Entry(1, "artists")), Entry(2, "albums")) { a, b -> a.owner == b.owner })
    }
}
