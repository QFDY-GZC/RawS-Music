package com.rawsmusic.core.common.model

import androidx.compose.runtime.mutableStateMapOf

/**
 * Heuristic Explicit / E label for *local* lyrics.
 *
 * We inspect the actual lyric body, never the track title, artist, translated-language metadata
 * or a remote source response. A complete content-rating decision cannot be inferred from text,
 * so use a conservative profanity/slur vocabulary and whole-word matching for Latin words.
 * This is an in-memory presentation index, not an edit to the track's audio metadata.
 */
object LocalLyricExplicit {
    private val english = Regex(
        pattern = "(?i)(?<![a-z])(?:" +
            "fuck(?:ing|ed|er|ers|s)?|motherfuck(?:er|ers|ing)?|" +
            "shit(?:ty|ting|ted|s)?|bullshit|" +
            "bitch(?:es|y)?|cunt(?:s)?|nigg(?:a|as|er|ers)|" +
            "pussy|pussies|dick(?:s)?|cock(?:s)?|" +
            "asshole(?:s)?|whore(?:s)?|slut(?:s|ty)?|" +
            "blow[ -]?job(?:s)?" +
            ")(?![a-z])",
    )
    // Avoid short innocuous fragments such as 单独的“草”“妈”“逼” or words within place names.
    private val chinese = Regex(
        "操你|操他|操她|我操|卧槽|我艹|草泥马|他妈的|你妈的|去你妈|" +
            "妈了个逼|傻逼|傻屄|牛逼|鸡巴|鸡八|屌你|狗日的|日你妈|" +
            "臭婊子|贱婊子",
    )
    private const val MAX_INDEX_ENTRIES = 512
    private val explicitSongs = mutableStateMapOf<String, Boolean>()
    private val recentKeys = LinkedHashSet<String>()

    /** A playback or downloaded sidecar lyric publication can change this result immediately. */
    fun publish(song: AudioFile, explicitLyrics: Boolean) {
        if (!isLocalSong(song)) return
        val key = keyOf(song)
        // All writes are made by LyricsCoordinator on the Android main thread.
        if (explicitLyrics) explicitSongs[key] = true else explicitSongs.remove(key)
        recentKeys.remove(key)
        recentKeys.add(key)
        while (recentKeys.size > MAX_INDEX_ENTRIES) {
            val oldest = recentKeys.first()
            recentKeys.remove(oldest)
            explicitSongs.remove(oldest)
        }
    }

    /** Snapshot-observable, cheap enough for a title holder's normal Compose binding. */
    fun isExplicit(song: AudioFile?): Boolean =
        song != null && isLocalSong(song) && explicitSongs[keyOf(song)] == true

    fun detect(lyrics: LyricData): Boolean = lyrics.lines.any { line ->
        containsExplicit(line.text) ||
            containsExplicit(line.backgroundText.orEmpty()) ||
            containsExplicit(line.translation) ||
            containsExplicit(line.backgroundTranslation.orEmpty())
    }

    private fun containsExplicit(text: String): Boolean =
        text.isNotBlank() && (english.containsMatchIn(text) || chinese.containsMatchIn(text))

    private fun isLocalSong(song: AudioFile): Boolean {
        val path = song.path.trim()
        return path.isNotBlank() &&
            !path.startsWith("http://", ignoreCase = true) &&
            !path.startsWith("https://", ignoreCase = true) &&
            // Other URI schemes may be online-source handles. Never classify those implicitly.
            (!path.contains("://") ||
                path.startsWith("file://", ignoreCase = true) ||
                path.startsWith("content://", ignoreCase = true))
    }

    private fun keyOf(song: AudioFile): String = buildString(song.path.length + 40) {
        append(song.path)
        append('|')
        append(song.cueOffsetMs)
        append('|')
        append(song.cueEndMs)
        append('|')
        append(song.cueTrackIndex)
    }
}
