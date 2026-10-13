package com.rawsmusic.module.player

/** A one-track override; never writes the user's automatic-transition preference. */
internal class AutoTransitionCancellation {
    private data class Track(val generation: Int, val source: String?)
    @Volatile private var cancelled: Track? = null

    fun cancel(generation: Int, source: String?) {
        cancelled = Track(generation, source)
    }

    fun onTrackChanged(previous: String?, current: String?) {
        if (previous != current) cancelled = null
    }

    fun isCancelled(generation: Int, source: String?): Boolean =
        cancelled?.let { it.generation == generation && it.source == source } == true
}
