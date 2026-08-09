package com.rawsmusic.module.data.source.playback

import android.content.Context
import com.rawsmusic.core.common.source.RawSourceLyric
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.module.data.source.LxSourcePluginStore
import com.rawsmusic.module.data.source.MusicSourcePluginStore
import com.rawsmusic.module.data.source.runtime.LxCatalogLyricService
import com.rawsmusic.module.data.source.runtime.MusicSourceRuntimeClient
import java.util.LinkedHashMap
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class MusicSourceLyricStatus {
    Idle,
    Loading,
    Ready,
    Empty,
    Error,
}

data class MusicSourceLyricLine(
    val timestampMs: Long,
    val text: String,
    val translation: String = "",
    val romanization: String = "",
)

data class MusicSourceLyricSnapshot(
    val itemIdentity: String = "",
    val item: RawSourceMediaItem? = null,
    val status: MusicSourceLyricStatus = MusicSourceLyricStatus.Idle,
    val lines: List<MusicSourceLyricLine> = emptyList(),
    val isTimed: Boolean = false,
    val providerLabel: String = "",
    val error: String = "",
) {
    fun currentLineIndex(positionMs: Long): Int {
        if (!isTimed || lines.isEmpty()) return -1
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (lines[middle].timestampMs <= positionMs) {
                result = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return result
    }
}

/** Lyric state owned only by the independent online-source player. */
object MusicSourceLyricController {
    private const val MAX_CACHE_ENTRIES = 24
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val generations = AtomicLong(0L)
    private val mutableSnapshot = MutableStateFlow(MusicSourceLyricSnapshot())
    val snapshot = mutableSnapshot.asStateFlow()

    private val cache = object : LinkedHashMap<String, CachedLyric>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedLyric>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }
    private var loadJob: Job? = null

    fun load(
        context: Context,
        item: RawSourceMediaItem?,
        force: Boolean = false,
    ) {
        if (item == null) {
            loadJob?.cancel()
            mutableSnapshot.value = MusicSourceLyricSnapshot()
            return
        }
        val identity = item.stableIdentity
        if (!force) {
            cache[identity]?.let { cached ->
                mutableSnapshot.value = cached.toSnapshot(item)
                return
            }
            val current = mutableSnapshot.value
            if (current.itemIdentity == identity && current.status in setOf(
                    MusicSourceLyricStatus.Loading,
                    MusicSourceLyricStatus.Ready,
                    MusicSourceLyricStatus.Empty,
                )
            ) return
        }

        val musicFreeSource = MusicSourcePluginStore.sources.value.firstOrNull { it.id == item.sourceId && it.enabled }
        val lxSource = LxSourcePluginStore.sources.value.firstOrNull { it.id == item.sourceId && it.enabled }
        if (musicFreeSource == null && lxSource == null) {
            mutableSnapshot.value = MusicSourceLyricSnapshot(
                itemIdentity = identity,
                item = item,
                status = MusicSourceLyricStatus.Error,
                error = "对应音源不存在或已停用",
            )
            return
        }

        val generation = generations.incrementAndGet()
        loadJob?.cancel()
        mutableSnapshot.value = MusicSourceLyricSnapshot(
            itemIdentity = identity,
            item = item,
            status = MusicSourceLyricStatus.Loading,
        )
        loadJob = scope.launch {
            try {
                val loaded = when {
                    musicFreeSource != null -> {
                        val raw = MusicSourceRuntimeClient.getLyric(
                            context = context.applicationContext,
                            source = musicFreeSource,
                            item = item,
                        )
                        LoadedLyric(raw, "MusicFree · ${musicFreeSource.name}")
                    }
                    lxSource != null -> loadLxLyric(context.applicationContext, lxSource, item)
                    else -> error("对应音源不存在或已停用")
                }
                val parsed = withContext(Dispatchers.Default) { parse(loaded.raw) }
                if (generation != generations.get()) return@launch
                val cached = CachedLyric(
                    lines = parsed.lines,
                    isTimed = parsed.isTimed,
                    empty = parsed.lines.isEmpty(),
                    providerLabel = loaded.providerLabel,
                )
                cache[identity] = cached
                mutableSnapshot.value = cached.toSnapshot(item)
                musicFreeSource?.let { MusicSourcePluginStore.setLastError(it.id, "") }
                lxSource?.let { LxSourcePluginStore.setLastError(it.id, "") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (generation != generations.get()) return@launch
                val message = error.message.orEmpty().ifBlank { "歌词获取失败" }.take(1_024)
                mutableSnapshot.value = MusicSourceLyricSnapshot(
                    itemIdentity = identity,
                    item = item,
                    status = MusicSourceLyricStatus.Error,
                    error = message,
                )
                musicFreeSource?.let { MusicSourcePluginStore.setLastError(it.id, message) }
                lxSource?.let { LxSourcePluginStore.setLastError(it.id, message) }
            }
        }
    }

    private suspend fun loadLxLyric(
        context: Context,
        source: com.rawsmusic.module.data.source.InstalledLxSource,
        item: RawSourceMediaItem,
    ): LoadedLyric {
        val sourceErrors = mutableListOf<String>()
        val sourceSupportsLyric = !source.format.equals("renderApi", ignoreCase = true) &&
            source.actions.any { it.equals("lyric", ignoreCase = true) }
        if (sourceSupportsLyric) {
            runCatching {
                MusicSourceRuntimeClient.getLxLyric(context, source, item)
            }.onSuccess { raw ->
                val parsed = withContext(Dispatchers.Default) { parse(raw) }
                if (parsed.lines.isNotEmpty()) return LoadedLyric(raw, "LX 音源 · ${source.name}")
                sourceErrors += "导入的 LX 音源返回空歌词"
            }.onFailure { error ->
                sourceErrors += "导入的 LX 音源：${error.message.orEmpty().ifBlank { "调用失败" }}"
            }
        } else {
            sourceErrors += if (source.format.equals("renderApi", ignoreCase = true)) {
                "LX Render API 仅提供播放地址"
            } else {
                "导入的 LX 音源未声明歌词能力"
            }
        }

        return runCatching { LxCatalogLyricService.getLyric(item) }
            .fold(
                onSuccess = { result -> LoadedLyric(result.lyric, result.providerLabel) },
                onFailure = { error ->
                    val details = (sourceErrors + "目录歌词：${error.message.orEmpty().ifBlank { "获取失败" }}")
                        .joinToString("；")
                    throw IllegalStateException(details, error)
                },
            )
    }

    fun retry(context: Context) {
        mutableSnapshot.value.item?.let { load(context, it, force = true) }
    }

    fun clear() {
        generations.incrementAndGet()
        loadJob?.cancel()
        loadJob = null
        mutableSnapshot.value = MusicSourceLyricSnapshot()
    }

    private fun parse(lyric: RawSourceLyric): ParsedLyric {
        val normalizedOriginal = normalizeLyricPayload(lyric.original)
        val normalizedWordByWord = normalizeLyricPayload(lyric.wordByWord)
        val originalText = normalizedOriginal.ifBlank { normalizedWordByWord }
        val original = parseTrack(originalText)
        val translated = parseTrack(normalizeLyricPayload(lyric.translation))
        val romanized = parseTrack(normalizeLyricPayload(lyric.romanization))

        if (original.timed.isNotEmpty()) {
            val lines = original.timed.entries
                .sortedBy(Map.Entry<Long, String>::key)
                .map { (timestamp, text) ->
                    MusicSourceLyricLine(
                        timestampMs = timestamp,
                        text = text,
                        translation = translated.timed[timestamp].orEmpty(),
                        romanization = romanized.timed[timestamp].orEmpty(),
                    )
                }
                .filter { line ->
                    line.text.isNotBlank() || line.translation.isNotBlank() || line.romanization.isNotBlank()
                }
            return ParsedLyric(lines = lines, isTimed = lines.isNotEmpty())
        }

        val plainOriginal = original.plain.ifEmpty {
            originalText.lineSequence()
                .map(String::trim)
                .filter(String::isNotBlank)
                .filterNot(::isMetadataLine)
                .toList()
        }
        val lines = plainOriginal.mapIndexed { index, text ->
            MusicSourceLyricLine(
                timestampMs = -1L,
                text = text,
                translation = translated.plain.getOrNull(index).orEmpty(),
                romanization = romanized.plain.getOrNull(index).orEmpty(),
            )
        }
        return ParsedLyric(lines = lines, isTimed = false)
    }

    private fun parseTrack(raw: String): ParsedTrack {
        if (raw.isBlank()) return ParsedTrack()
        val timed = linkedMapOf<Long, String>()
        val plain = mutableListOf<String>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim().removePrefix("\uFEFF")
            if (line.isBlank()) return@forEach
            if (OFFSET_REGEX.containsMatchIn(line)) return@forEach
            val qrcLine = QRC_LINE_REGEX.find(line)
            if (qrcLine != null) {
                val timestamp = qrcLine.groupValues[1].toLongOrNull()?.coerceAtLeast(0L)
                val text = line.substring(qrcLine.range.last + 1)
                    .replace(QRC_WORD_REGEX, "")
                    .trim()
                if (timestamp != null) timed[timestamp] = text
                return@forEach
            }
            val matches = TIME_REGEX.findAll(line).toList()
            if (matches.isNotEmpty()) {
                val text = TIME_REGEX.replace(line, "").trim()
                for (match in matches) {
                    val minute = match.groupValues[1].toLongOrNull() ?: continue
                    val second = match.groupValues[2].toLongOrNull() ?: continue
                    val fraction = match.groupValues.getOrNull(3).orEmpty()
                    val fractionMs = when (fraction.length) {
                        1 -> fraction.toLongOrNull()?.times(100L) ?: 0L
                        2 -> fraction.toLongOrNull()?.times(10L) ?: 0L
                        else -> fraction.take(3).padEnd(3, '0').toLongOrNull() ?: 0L
                    }
                    val timestamp = (minute * 60_000L + second * 1_000L + fractionMs).coerceAtLeast(0L)
                    timed[timestamp] = text
                }
            } else if (!isMetadataLine(line)) {
                plain += line
            }
        }
        return ParsedTrack(timed = timed, plain = plain)
    }

    private fun normalizeLyricPayload(raw: String): String {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isBlank()) return ""
        if (!HEX_PAYLOAD_REGEX.matches(text)) return text
        val compactHex = text.filterNot(Char::isWhitespace)
        if (compactHex.length < 64 || compactHex.length % 2 != 0) return text
        val encrypted = runCatching { compactHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
            ?: return ""
        decodeReadableUtf8(encrypted)?.let { return it }
        val decrypted = runCatching {
            val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(QRC_KEY, "DESede"))
            cipher.doFinal(encrypted)
        }.getOrNull() ?: return ""
        val candidates = listOfNotNull(
            inflate(decrypted, nowrap = false),
            inflate(decrypted, nowrap = true),
            gunzip(decrypted),
            decodeReadableUtf8(decrypted),
        )
        return candidates.firstOrNull(::looksLikeLyricText).orEmpty()
    }

    private fun inflate(bytes: ByteArray, nowrap: Boolean): String? = runCatching {
        InflaterInputStream(ByteArrayInputStream(bytes), Inflater(nowrap)).use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output, 16 * 1024)
            require(output.size() <= 4 * 1024 * 1024)
            output.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()

    private fun gunzip(bytes: ByteArray): String? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output, 16 * 1024)
            require(output.size() <= 4 * 1024 * 1024)
            output.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()

    private fun decodeReadableUtf8(bytes: ByteArray): String? {
        val text = bytes.toString(Charsets.UTF_8).trim('\u0000', ' ', '\r', '\n', '\t')
        return text.takeIf(::looksLikeLyricText)
    }

    private fun looksLikeLyricText(text: String): Boolean {
        if (text.isBlank()) return false
        val printable = text.count { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() }
        val ratio = printable.toFloat() / text.length.coerceAtLeast(1)
        return ratio > 0.92f && (text.contains('[') || text.contains('<') || text.any { it.code > 0x7f })
    }

    private fun isMetadataLine(line: String): Boolean = METADATA_REGEX.matches(line.trim())

    private data class ParsedTrack(
        val timed: Map<Long, String> = emptyMap(),
        val plain: List<String> = emptyList(),
    )

    private data class ParsedLyric(
        val lines: List<MusicSourceLyricLine>,
        val isTimed: Boolean,
    )

    private data class LoadedLyric(
        val raw: RawSourceLyric,
        val providerLabel: String,
    )

    private data class CachedLyric(
        val lines: List<MusicSourceLyricLine>,
        val isTimed: Boolean,
        val empty: Boolean,
        val providerLabel: String,
    ) {
        fun toSnapshot(item: RawSourceMediaItem): MusicSourceLyricSnapshot = MusicSourceLyricSnapshot(
            itemIdentity = item.stableIdentity,
            item = item,
            status = if (empty) MusicSourceLyricStatus.Empty else MusicSourceLyricStatus.Ready,
            lines = lines,
            isTimed = isTimed,
            providerLabel = providerLabel,
        )
    }

    private val TIME_REGEX = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val QRC_LINE_REGEX = Regex("""^\[(\d+),(\d+)]""")
    private val QRC_WORD_REGEX = Regex("""\(\d+,\d+\)""")
    private val HEX_PAYLOAD_REGEX = Regex("""^[0-9a-fA-F\s]+$""")
    private val QRC_KEY = "!@#)(*$%123ZXC!@!@#)(NHL".toByteArray(Charsets.UTF_8)
    private val OFFSET_REGEX = Regex("""^\[offset:([+-]?\d+)]$""", RegexOption.IGNORE_CASE)
    private val METADATA_REGEX = Regex("""^\[(ar|ti|al|by|re|ve|length|offset):.*]$""", RegexOption.IGNORE_CASE)
}
