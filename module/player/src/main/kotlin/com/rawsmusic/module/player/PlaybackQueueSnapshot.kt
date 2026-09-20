package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue

/**
 * UI projection of the queue used by the transport layer.
 *
 * Priority items are consumed immediately after the current item. They must therefore be
 * inserted after the current index, not prepended to the list (which makes every preview show a
 * different song from the one the transport will actually play).
 */
fun PlayQueue.withPriorityQueue(prioritySongs: List<AudioFile>): PlayQueue {
    if (prioritySongs.isEmpty()) return this
    val sourceEntryIds = QueueEntryIdGenerator.normalize(songs.size, entryIds)
    if (songs.isEmpty()) {
        return copy(
            songs = prioritySongs,
            currentIndex = 0,
            entryIds = QueueEntryIdGenerator.normalize(prioritySongs.size, emptyList()),
        )
    }
    val currentIdentity = songs.getOrNull(currentIndex)?.queueIdentity()
    // A renderer acknowledgement may update the base queue before the priority deque is
    // consumed. Treat the newly-current priority item as already consumed for this snapshot;
    // otherwise the combine(queue, priority) projection briefly renders it twice.
    val effectivePrioritySongs = if (currentIdentity == null) {
        prioritySongs
    } else {
        prioritySongs.filterNot { it.queueIdentity() == currentIdentity }
    }
    if (effectivePrioritySongs.isEmpty()) return this
    val priorityIdentities = effectivePrioritySongs.mapTo(HashSet()) { it.queueIdentity() }
    // A queued "play next" item may already exist later in the base queue. Move it instead of
    // rendering a duplicate: the transport consumes the priority item once, so the UI snapshot
    // must also contain it once.
    val base: List<Pair<AudioFile, Long?>> = songs.mapIndexed { index, song ->
        song to sourceEntryIds[index]
    }
        .filterIndexed { index, pair ->
            index == currentIndex || pair.first.queueIdentity() !in priorityIdentities
        }
    if (base.isEmpty()) {
        return copy(
            songs = prioritySongs,
            currentIndex = 0,
            entryIds = QueueEntryIdGenerator.normalize(prioritySongs.size, emptyList()),
        )
    }
    val current = base.indexOfFirst { it.first.queueIdentity() == currentIdentity }
        .takeIf { it >= 0 }
        ?: currentIndex.coerceIn(0, base.lastIndex)
    val priorityEntries = effectivePrioritySongs.map { prioritySong ->
        val existingIndex = songs.indexOfFirst { it.queueIdentity() == prioritySong.queueIdentity() }
        prioritySong to sourceEntryIds.getOrNull(existingIndex)
    }
    val merged = buildList<Pair<AudioFile, Long?>>(base.size + priorityEntries.size) {
        addAll(base.subList(0, current + 1))
        addAll(priorityEntries)
        addAll(base.subList(current + 1, base.size))
    }
    return copy(
        songs = merged.map { it.first },
        currentIndex = current,
        entryIds = QueueEntryIdGenerator.normalize(
            merged.size,
            merged.map { it.second ?: 0L },
        ),
    )
}

private data class QueueIdentity(
    val path: String,
    val cueOffsetMs: Long,
    val cueTrackIndex: Int,
)

private fun AudioFile.queueIdentity(): QueueIdentity =
    QueueIdentity(path, cueOffsetMs, cueTrackIndex)
