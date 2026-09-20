package com.rawsmusic.module.scanner

import android.provider.MediaStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreScannerProjectionTest {

    @Test
    fun android9ProjectionDoesNotRequestApi30BitrateColumn() {
        val projection = MediaStoreScanner.projectionForSdk(28).toSet()

        assertFalse(MediaStore.Audio.Media.BITRATE in projection)
        assertTrue(MediaStore.Audio.Media._ID in projection)
        assertTrue(MediaStore.Audio.Media.DATA in projection)
    }

    @Test
    fun android11ProjectionIncludesBitrateColumn() {
        val projection = MediaStoreScanner.projectionForSdk(30).toSet()

        assertTrue(MediaStore.Audio.Media.BITRATE in projection)
    }
}
