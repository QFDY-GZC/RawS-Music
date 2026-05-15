package com.rawsmusic.core.common.player

interface PlayerProvider {
    fun isPlaying(): Boolean
    fun playPause()
    fun next()
    fun previous()
}
