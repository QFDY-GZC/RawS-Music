package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import kotlin.math.roundToInt
import kotlin.random.Random

/** Queue planning rules derived from Reference baseline implementation. */
internal object ReferenceShufflePlanner {
    const val LARGE_LIST_THRESHOLD = 10_000
    const val SONGS_ONLY = 2
    const val CATEGORIES_ONLY = 3
    const val SONGS_AND_CATEGORIES = 4

    fun <T> sampled(
        items: List<T>,
        factor: Float,
        lastPlayedAt: (T) -> Long,
        random: Random = Random.Default,
    ): List<T> {
        if (items.size <= 1) return items.toList()
        val normalized = factor.coerceIn(0f, 1f)
        if (normalized >= 1f) return items.shuffled(random)

        // Reference gives the older/never-played sample negative random order values and the
        // remainder positive values. This equivalent two-bucket form preserves that priority.
        val sorted = items.sortedBy(lastPlayedAt)
        val sampleSize = (items.size * normalized).roundToInt().coerceIn(1, items.size)
        return sorted.take(sampleSize).shuffled(random) + sorted.drop(sampleSize).shuffled(random)
    }

    fun songs(
        songs: List<AudioFile>,
        factor: Float,
        playedAt: Map<Long, Long>,
        random: Random = Random.Default,
    ): List<AudioFile> = sampled(songs, factor, { playedAt[it.id] ?: 0L }, random)

    fun categories(
        groups: List<List<AudioFile>>,
        mode: Int,
        factor: Float,
        playedAt: Map<Long, Long>,
        random: Random = Random.Default,
    ): List<AudioFile> {
        val validGroups = groups.filter { it.isNotEmpty() }
        val orderedGroups = if (mode == CATEGORIES_ONLY || mode == SONGS_AND_CATEGORIES) {
            sampled(validGroups, factor, { group -> group.maxOf { playedAt[it.id] ?: 0L } }, random)
        } else {
            validGroups
        }
        return orderedGroups.flatMap { group ->
            if (mode == SONGS_ONLY || mode == SONGS_AND_CATEGORIES) {
                songs(group, factor, playedAt, random)
            } else {
                group
            }
        }
    }

    fun preserveOnManualSelection(itemCount: Int, noReshuffle: Boolean, noReshuffleLarge: Boolean): Boolean =
        noReshuffle || (noReshuffleLarge && itemCount >= LARGE_LIST_THRESHOLD)
}
