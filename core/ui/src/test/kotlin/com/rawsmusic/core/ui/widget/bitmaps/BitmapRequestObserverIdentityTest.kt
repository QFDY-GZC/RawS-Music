package com.rawsmusic.core.ui.widget.bitmaps

import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test

class BitmapRequestObserverIdentityTest {
    private fun request(onResult: () -> Unit = {}): BitmapRequest {
        val token = ArtworkAcceptToken("album", "album_128", 128,
            ArtworkRecord("album", 0, 0), 0)
        return BitmapRequest(key = "album", targetWidth = 128, targetHeight = 128,
            artworkToken = token, callback = { onResult() })
    }

    @Test fun sameAlbumColdRequestsShareDecodeButEachReceiveResult() {
        var firstCalls = 0
        var secondCalls = 0
        val first = request { firstCalls++ }
        val second = request { secondCalls++ }
        assertEquals(first.inFlightKey, second.inFlightKey)
        val waiters = CopyOnWriteArrayList<BitmapRequest>()
        waiters.addIfAbsent(first)
        waiters.addIfAbsent(second)
        waiters.addIfAbsent(first)
        assertEquals(2, waiters.size)
        waiters.forEach { it.callback?.invoke(null) }
        assertEquals(1, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test fun recyclingSecondHolderCannotRemoveFirstHolder() {
        val first = request()
        val second = request()
        val waiters = CopyOnWriteArrayList(listOf(first, second))
        second.cancel()
        waiters.remove(second)
        assertEquals(1, waiters.size)
        assertSame(first, waiters.single())
        assertFalse(first.isCancelled)
    }

    @Test fun sourceRevisionRequeuePreservesEveryLiveObserver() {
        val first = request()
        val second = request()
        val live = linkedSetOf(first, second, first)
        assertEquals(2, live.size)
        val nextToken = ArtworkAcceptToken("album", "album_128", 128,
            ArtworkRecord("album", 1, 0), 0)
        live.forEach { assertTrue(it.moveToArtworkRecord(nextToken)) }
        assertEquals(first.inFlightKey, second.inFlightKey)
        val waiters = CopyOnWriteArrayList<BitmapRequest>()
        live.forEach(waiters::addIfAbsent)
        assertEquals(2, waiters.size)
    }
}
