package com.rawsmusic.core.ui.scene

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SceneBackgroundIdentityTest {
    @Test fun returningBehindPlayerUsesNewTrackWithoutWaitingForLibrarySnapshot() {
        val old = AudioFile(path = "/old.flac", title = "old")
        val new = AudioFile(path = "/new.flac", title = "new")
        assertEquals(new, resolveSceneBackgroundSong(old, new))
    }

    @Test fun restoredSongRemainsAvailableBeforeLiveIdentityArrives() {
        val restored = AudioFile(path = "/restored.flac", title = "restored")
        assertEquals(restored, resolveSceneBackgroundSong(restored, null))
        assertNull(resolveSceneBackgroundSong(null, null))
    }
}
