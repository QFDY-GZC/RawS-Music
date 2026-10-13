package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.module.data.prefs.AppPreferences

/** Persistence boundary; traversal decisions do not depend on the Android storage runtime. */
internal interface ShuffleQueuePreferences {
    var order: String
    var cursor: Int
    val playMode: PlayMode
    val randomFactor: Float
    val categoryMode: Int
    val noReshuffle: Boolean
    val noReshuffleForLargeLists: Boolean
    fun clearOriginalQueue()
}

internal object PersistedShuffleQueuePreferences : ShuffleQueuePreferences {
    override var order: String
        get() = AppPreferences.Player.shuffleTraversalOrder
        set(value) { AppPreferences.Player.shuffleTraversalOrder = value }
    override var cursor: Int
        get() = AppPreferences.Player.shuffleTraversalCursor
        set(value) { AppPreferences.Player.shuffleTraversalCursor = value }
    override val playMode get() = AppPreferences.Player.playMode
    override val randomFactor get() = AppPreferences.Player.shuffleRandomFactor
    override val categoryMode get() = AppPreferences.Player.categoryShuffleMode
    override val noReshuffle get() = AppPreferences.Player.noReshuffle
    override val noReshuffleForLargeLists get() = AppPreferences.Player.noReshuffleForLargeLists
    override fun clearOriginalQueue() { AppPreferences.Player.originalQueueSongsJson = "" }
}
