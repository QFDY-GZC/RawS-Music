package com.rawsmusic.module.player.lyrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.rawsmusic.core.common.model.AudioFile
import org.json.JSONObject
import java.io.File

/**
 * Optional ColorOS module transport for devices that do not expose lyricInfo from MediaSession.
 * The app identifies itself honestly; the bridge module must register this source/package pair.
 */
internal object ColorOsDirectLyricBridge {
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val ACTION_DIRECT_LYRIC_CAPTURED =
        "io.github.andrealtb.lockscreenlyrics.action.EXTERNAL_LYRIC_DIRECT_V4"
    private const val PROTOCOL_VERSION = 4
    private const val SOURCE = "lyricprovider/raws-music"
    private const val EVENT_LYRIC_READY = "lyricReady"
    private const val SENDER_KIND_PROVIDER = "provider"
    private const val BASE_CAPABILITIES = "trackGeneration,currentTrackAuthority"
    private const val MATCH_POLICY = "titleOnly"
    private const val IDENTITY_CONFIDENCE = "currentTrack"
    private const val MAX_PAYLOAD_CHARS = 1_500_000

    fun publish(
        context: Context,
        song: AudioFile,
        lyricInfo: String,
        trackGeneration: Long,
    ): Boolean {
        if (lyricInfo.isBlank() || lyricInfo.length > MAX_PAYLOAD_CHARS) return false

        return try {
            val payload = JSONObject(lyricInfo)
            val lyric = payload.optString("lyric").takeIf { it.isNotBlank() } ?: return false
            val translationLyric = payload.optString("translationLyric")
                .takeIf { it.isNotBlank() }
            val capabilities = buildString {
                append(BASE_CAPABILITIES)
                if (translationLyric != null) append(",translationToggle")
            }
            val songId = payload.optString("songId").ifBlank { stableTrackKey(song) }
            val mediaUri = song.path
                .takeIf { it.isNotBlank() }
                ?.let { Uri.fromFile(File(it)).toString() }
                .orEmpty()
            val trackKey = stableTrackKey(song)
            val intent = Intent(ACTION_DIRECT_LYRIC_CAPTURED).apply {
                setPackage(SYSTEM_UI_PACKAGE)
                putExtra("protocolVersion", PROTOCOL_VERSION)
                putExtra("source", SOURCE)
                putExtra("playerPackage", context.packageName)
                putExtra("senderPackage", context.packageName)
                putExtra("senderKind", SENDER_KIND_PROVIDER)
                putExtra("capabilities", capabilities)
                putExtra("matchPolicy", MATCH_POLICY)
                putExtra("identityConfidence", IDENTITY_CONFIDENCE)
                putExtra("eventType", EVENT_LYRIC_READY)
                putExtra("trackGeneration", trackGeneration)
                putExtra(
                    "requestId",
                    "rawsmusic-${trackGeneration}-${SystemClock.elapsedRealtimeNanos()}"
                )
                putExtra("mediaId", songId)
                putExtra("mediaUri", mediaUri)
                putExtra("trackKey", trackKey)
                putExtra("songName", payload.optString("songName", song.title))
                putExtra("artist", payload.optString("artist", song.artist))
                putExtra("duration", song.duration)
                putExtra("lyric", lyric)
                payload.optString("rawLyric")
                    .takeIf { it.isNotBlank() }
                    ?.let { putExtra("rawLyric", it) }
                translationLyric?.let { putExtra("translationLyric", it) }
                putExtra("capturedAt", System.currentTimeMillis())
            }
            context.sendBroadcast(intent)
            true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun stableTrackKey(song: AudioFile): String = buildString {
        append(song.path)
        append('|').append(song.cueTrackIndex)
        append('|').append(song.fileSize)
        append('|').append(song.dateModified)
    }
}
