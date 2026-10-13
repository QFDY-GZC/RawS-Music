package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile

/** Called under the persistence lock; only successful writes become the comparison baseline. */
internal class QueueSnapshotWritePolicy {
    data class Changes(val queue: Boolean, val priority: Boolean, val traversal: Boolean)
    private var songs: List<AudioFile>? = null
    private var entryIds: List<Long>? = null
    private var priority: List<AudioFile>? = null
    private var traversal: String? = null

    fun changes(songs: List<AudioFile>, entryIds: List<Long>, priority: List<AudioFile>, traversal: String) =
        Changes(this.songs !== songs || this.entryIds !== entryIds,
            this.priority != priority, this.traversal != traversal)

    fun committed(songs: List<AudioFile>, entryIds: List<Long>, priority: List<AudioFile>, traversal: String) {
        this.songs = songs
        this.entryIds = entryIds
        this.priority = priority
        this.traversal = traversal
    }

    fun invalidatePriority() { priority = null }
}
