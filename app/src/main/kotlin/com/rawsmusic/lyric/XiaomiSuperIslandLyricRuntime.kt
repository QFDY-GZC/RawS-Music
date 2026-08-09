package com.rawsmusic.lyric

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicLong

/** Converts the bounded player event into the app-owned vendor bridge. */
internal object XiaomiSuperIslandLyricRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var bridge: XiaomiSuperIslandLyricBridge? = null
    private var artworkPath: String = ""
    private var artworkBitmap: Bitmap? = null
    private val eventGeneration = AtomicLong(0L)

    fun handle(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val currentBridge = bridge ?: XiaomiSuperIslandLyricBridge(appContext, scope).also {
            bridge = it
        }
        if (intent.action == "com.rawsmusic.action.SUPER_ISLAND_LYRIC_CLEAR") {
            eventGeneration.incrementAndGet()
            currentBridge.onPlaybackPaused()
            return
        }
        if (intent.action != "com.rawsmusic.action.SUPER_ISLAND_LYRIC") return

        // Invalidate artwork/lyric work from the previous player event before reading the new one.
        val generation = eventGeneration.incrementAndGet()

        val settings = AppPreferences.Lyrics.xiaomiSuperIslandSettings
        if (!AppPreferences.Lyrics.xiaomiSuperIslandLyricEnabled) {
            currentBridge.setEnabled(false)
            return
        }
        currentBridge.setSettings(settings)
        currentBridge.setEnabled(true)

        val path = intent.getStringExtra("path").orEmpty()
        val artworkKey = intent.getStringExtra("albumArtPath").orEmpty().ifBlank { path }
        val song = AudioFile(
            title = intent.getStringExtra("title").orEmpty(),
            artist = intent.getStringExtra("artist").orEmpty(),
            album = intent.getStringExtra("album").orEmpty(),
            path = path,
            albumArtPath = intent.getStringExtra("albumArtPath").orEmpty(),
            duration = intent.getLongExtra("duration", 0L)
        )
        val line = LyricLine(
            timeStamp = intent.getLongExtra("lineTime", 0L),
            endTime = intent.getLongExtra("lineEnd", 0L),
            text = intent.getStringExtra("lineText").orEmpty(),
            translation = intent.getStringExtra("lineTranslation").orEmpty(),
            romanization = intent.getStringExtra("lineRomanization").orEmpty(),
            words = decodeWords(intent.getStringExtra("lineWords")),
            pronunciationWords = decodeWords(intent.getStringExtra("linePronunciationWords")),
            backgroundText = intent.getStringExtra("lineBackgroundText").orEmpty().ifBlank { null },
            backgroundTranslation = intent.getStringExtra("lineBackgroundTranslation").orEmpty().ifBlank { null },
            backgroundWords = decodeWords(intent.getStringExtra("lineBackgroundWords")),
            backgroundStartTime = intent.getLongExtra("lineBackgroundStart", 0L).takeIf { it > 0L },
            backgroundEndTime = intent.getLongExtra("lineBackgroundEnd", 0L).takeIf { it > 0L },
            agent = intent.getStringExtra("lineAgent").orEmpty().ifBlank { null },
            agentName = intent.getStringExtra("lineAgentName").orEmpty().ifBlank { null },
            isTtml = intent.getBooleanExtra("lineIsTtml", false)
        )
        val position = intent.getLongExtra("position", 0L)
        scope.launch(Dispatchers.IO) {
            val artwork = loadArtwork(artworkKey)
            if (generation != eventGeneration.get()) return@launch
            currentBridge.sendLyric(song, line, position, song.duration, artwork)
        }
    }

    private fun decodeWords(value: String?): List<LyricWord> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(value)
            buildList(minOf(array.length(), 64)) {
                for (index in 0 until minOf(array.length(), 64)) {
                    val item = array.optJSONObject(index) ?: continue
                    val text = item.optString("text").takeIf { it.isNotBlank() } ?: continue
                    val begin = item.optLong("begin", 0L)
                    val end = item.optLong("end", begin)
                    if (end >= begin) add(LyricWord(text = text, begin = begin, end = end))
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun loadArtwork(path: String): Bitmap? {
        if (path.isBlank()) return null
        if (path == artworkPath) return artworkBitmap
        val bitmap = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                retriever.embeddedPicture?.let { bytes ->
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } finally {
                retriever.release()
            }
        }.getOrNull() ?: BitmapFactory.decodeFile(path)
        val scaled = bitmap?.let { source ->
            val size = maxOf(source.width, source.height)
            if (size <= 480) source else {
                val ratio = 480f / size.toFloat()
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * ratio).toInt().coerceAtLeast(1),
                    (source.height * ratio).toInt().coerceAtLeast(1),
                    true
                )
            }
        }
        artworkPath = path
        artworkBitmap = scaled
        return scaled
    }
}
