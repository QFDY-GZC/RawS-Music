package com.rawsmusic.core.ui.widget.index

import com.rawsmusic.core.common.model.AudioFile
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Project-style side-index cache.
 *
 * The alphabet rail is a derived table over the current ordered song snapshot. Building it is not
 * expensive for small lists, but for 1k+ CJK/Japanese-heavy libraries it is visible when done on
 * every page entry. Keep a small process-wide LRU keyed by the ordered library fingerprint so the
 * rail appears immediately after the first build, just like the song list itself.
 */
object RawAlphabetIndexCache {
    private const val MAX_ENTRIES = 8
    private val lock = Any()
    private val lru = object : LinkedHashMap<Long, RawAlphabetIndexData>(MAX_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, RawAlphabetIndexData>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    fun keyForSongs(
        songs: List<AudioFile>,
        query: String,
        mode: RawIndexMode = RawIndexMode.AUTO,
        locale: Locale = Locale.getDefault()
    ): Long {
        var h = -3750763034362895579L
        fun mix(v: Long) {
            h = h xor v
            h *= 1099511628211L
        }
        mix(songs.size.toLong())
        mix(query.hashCode().toLong())
        mix(mode.ordinal.toLong())
        mix(locale.language.hashCode().toLong())
        // The target table depends on order. A cheap rolling fingerprint over ordered ids plus
        // stable name/path fields avoids rebuilding on every navigation while still changing when
        // sorting/filtering or the library content changes.
        for (song in songs) {
            mix(song.id)
            mix(song.displayName.hashCode().toLong())
            mix(song.path.hashCode().toLong())
            mix(song.dateModified)
        }
        return h
    }

    fun get(key: Long): RawAlphabetIndexData? = synchronized(lock) { lru[key] }

    fun put(key: Long, data: RawAlphabetIndexData) {
        synchronized(lock) { lru[key] = data }
    }

    fun getOrBuild(
        key: Long,
        songs: List<AudioFile>,
        mode: RawIndexMode = RawIndexMode.AUTO,
        locale: Locale = Locale.getDefault()
    ): RawAlphabetIndexData {
        get(key)?.let { return it }
        val built = buildAdaptiveAlphabetIndexData(
            items = songs,
            requestedMode = mode,
            locale = locale
        ) { it.displayName }
        put(key, built)
        return built
    }

    /**
     * Cheap first-frame rail. It intentionally avoids full script detection / pinyin normalization
     * so the side index can expand as soon as the song snapshot appears. The exact table is built
     * on Dispatchers.Default and replaces this approximate table a moment later.
     */
    fun quickBuild(songs: List<AudioFile>, query: String = ""): RawAlphabetIndexData {
        if (songs.isEmpty()) return RawAlphabetIndexData(emptyList(), emptyMap(), RawIndexMode.AUTO)

        val targets = linkedMapOf<String, Int>()
        songs.forEachIndexed { index, song ->
            val key = quickIndexKey(song.displayName)
            if (!targets.containsKey(key)) {
                targets[key] = index
            }
        }

        val labels = buildList {
            QuickMixedLabels.forEach { label ->
                if (targets.containsKey(label)) add(label)
            }
        }

        return RawAlphabetIndexData(
            labels = labels,
            targets = targets,
            mode = RawIndexMode.MIXED
        )
    }

    private fun quickIndexKey(raw: String?): String {
        val first = raw
            ?.trim()
            ?.firstOrNull { it.isLetterOrDigit() }
            ?: return "#"

        if (first.isDigit()) return "#"
        if (first in 'A'..'Z' || first in 'a'..'z') return first.uppercaseChar().toString()

        return when {
            first.code in 0x3040..0x30FF -> quickKanaRow(first)
            first.code in 0xAC00..0xD7AF -> quickHangulRow(first)
            first in 'А'..'я' || first == 'Ё' || first == 'ё' -> first.uppercaseChar().let { if (it == 'Ё') "Е" else it.toString() }
            first in 'Α'..'ω' -> first.uppercaseChar().toString()
            else -> "#"
        }
    }

    private fun quickKanaRow(c: Char): String {
        val h = if (c.code in 0x30A1..0x30F6) (c.code - 0x60).toChar() else c
        return when (h) {
            in "あいうえおぁぃぅぇぉ" -> "あ"
            in "かきくけこがぎぐげご" -> "か"
            in "さしすせそざじずぜぞ" -> "さ"
            in "たちつてとだぢづでどっ" -> "た"
            in "なにぬねの" -> "な"
            in "はひふへほばびぶべぼぱぴぷぺぽ" -> "は"
            in "まみむめも" -> "ま"
            in "やゆよゃゅょ" -> "や"
            in "らりるれろ" -> "ら"
            in "わをん" -> "わ"
            else -> "#"
        }
    }

    private fun quickHangulRow(first: Char): String {
        val code = first.code - 0xAC00
        if (code !in 0 until 11172) return "#"
        return when (code / (21 * 28)) {
            0 -> "ㄱ"
            2 -> "ㄴ"
            3 -> "ㄷ"
            5 -> "ㄹ"
            6 -> "ㅁ"
            7 -> "ㅂ"
            9 -> "ㅅ"
            11 -> "ㅇ"
            12 -> "ㅈ"
            14 -> "ㅊ"
            15 -> "ㅋ"
            16 -> "ㅌ"
            17 -> "ㅍ"
            18 -> "ㅎ"
            else -> "#"
        }
    }

    private val QuickMixedLabels: List<String> =
        ('A'..'Z').map { it.toString() } +
            listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ") +
            listOf("ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ") +
            listOf(
                "А", "Б", "В", "Г", "Д", "Е", "Ж", "З", "И", "Й", "К", "Л", "М", "Н", "О", "П",
                "Р", "С", "Т", "У", "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Э", "Ю", "Я"
            ) +
            listOf("Α", "Β", "Γ", "Δ", "Ε", "Ζ", "Η", "Θ", "Ι", "Κ", "Λ", "Μ", "Ν", "Ξ", "Ο", "Π", "Ρ", "Σ", "Τ", "Υ", "Φ", "Χ", "Ψ", "Ω") +
            "#"


    fun clear() {
        synchronized(lock) { lru.clear() }
    }
}
