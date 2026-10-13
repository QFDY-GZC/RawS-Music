package com.rawsmusic.lyric

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.XiaomiSuperIslandSettings
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
    private data class ArtworkEntry(val key: String, val bitmap: Bitmap)
    @Volatile private var artworkEntry: ArtworkEntry? = null
    private val eventGeneration = AtomicLong(0L)
    private var enabled = false
    private var playbackCleared = true
    private var appliedSettings: XiaomiSuperIslandSettings? = null

    fun handle(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val currentBridge = bridge ?: XiaomiSuperIslandLyricBridge(appContext, scope).also {
            bridge = it
        }
        if (intent.action == "com.rawsmusic.action.SUPER_ISLAND_LYRIC_CLEAR") {
            if (playbackCleared) return
            playbackCleared = true
            eventGeneration.incrementAndGet()
            currentBridge.onPlaybackPaused()
            return
        }
        if (intent.action != "com.rawsmusic.action.SUPER_ISLAND_LYRIC") return

        // Invalidate artwork/lyric work from the previous player event before reading the new one.
        val generation = eventGeneration.incrementAndGet()

        val settings = AppPreferences.Lyrics.xiaomiSuperIslandSettings
        if (!AppPreferences.Lyrics.xiaomiSuperIslandLyricEnabled) {
            if (enabled) {
                currentBridge.setEnabled(false)
                enabled = false
            }
            playbackCleared = true
            return
        }
        if (appliedSettings != settings) {
            currentBridge.setSettings(settings)
            appliedSettings = settings
        }
        if (!enabled) {
            currentBridge.setEnabled(true)
            enabled = true
        }
        playbackCleared = false

        val path = intent.getStringExtra("path").orEmpty()
        val song = AudioFile(
            title = intent.getStringExtra("title").orEmpty(),
            artist = intent.getStringExtra("artist").orEmpty(),
            album = intent.getStringExtra("album").orEmpty(),
            path = path,
            albumArtPath = intent.getStringExtra("albumArtPath").orEmpty(),
            fileSize = intent.getLongExtra("fileSize", 0L),
            dateModified = intent.getLongExtra("dateModified", 0L),
            duration = intent.getLongExtra("duration", 0L)
        )
        val artworkKey = song.resolvePlaybackArtworkKey().orEmpty()
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
            XiaomiSuperIslandCapabilities.logOnce(appContext)
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
        artworkEntry?.takeIf { it.key == path && !it.bitmap.isRecycled }?.let { return it.bitmap }
        // Share the player's embedded/external/content/remote artwork resolver and file-version key.
        // Do not memoize null: a cover arriving after the lyric event must be able to appear later.
        val scaled = runCatching {
            // The island also runs CPU Palette and creates RemoteViews bitmaps. Decode its
            // small raster from the shared encoded source, rather than reading a HARDWARE bitmap.
            BitmapProvider.executePixelAnalysis(path, 256)
        }.getOrNull()?.takeUnless { it.isRecycled } ?: return null
        // IO events can overlap; publish identity and pixels together, never as two assignments.
        artworkEntry = ArtworkEntry(path, scaled)
        return scaled
    }
}
