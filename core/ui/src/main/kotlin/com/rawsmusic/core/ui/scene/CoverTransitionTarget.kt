package com.rawsmusic.core.ui.scene

import android.graphics.RectF

data class CoverTransitionTarget(
    val bounds: RectF,
    val radiusDp: Float,
    val source: Source,
    val songId: Long = -1L,
    val coverKey: String = "",
    val index: Int = -1,
    val generation: Long = 0L
) {
    enum class Source {
        ListCover,
        MiniPlayer
    }

    fun copyBounds(): CoverTransitionTarget {
        return copy(bounds = RectF(bounds))
    }

    fun isForSong(
        expectedSongId: Long,
        expectedCoverKey: String
    ): Boolean {
        if (expectedSongId > 0L && songId == expectedSongId) return true
        if (expectedCoverKey.isNotBlank() && coverKey == expectedCoverKey) return true
        return false
    }
}
