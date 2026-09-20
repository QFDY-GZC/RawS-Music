package com.rawsmusic.module.player

/** A reopened renderer has no pending slot to emit its track-start event. */
internal fun shouldPublishReopenedTrackStart(previous: String, current: String): Boolean =
    previous == "PREPARING" && current == "PLAYING"
