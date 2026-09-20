package com.rawsmusic.module.player.lyrics

import android.content.Context
import android.content.Intent
import com.rawsmusic.core.common.model.AudioFile

/**
 * Optional test hand-off for devices whose system lyric surface only accepts a media identity.
 * The standard MediaSession remains the primary non-module path; this broadcast is ignored when
 * the small compatibility shell is not installed.
 */
internal object ColorOsNonModuleLyricBridge {
    const val ACTION_LYRIC_METADATA = "com.rawsmusic.compat.qmusic.action.LYRIC_METADATA"
    const val COMPAT_PACKAGE = "com.rawsmusic.compat.qmusic"

    fun publish(context: Context, song: AudioFile, lyricInfo: String): Boolean {
        if (lyricInfo.isBlank()) return false
        val installed = runCatching {
            context.packageManager.getPackageInfo(COMPAT_PACKAGE, 0)
            true
        }.getOrDefault(false)
        if (!installed) return false

        return runCatching {
            context.sendBroadcast(
                Intent(ACTION_LYRIC_METADATA).apply {
                    setPackage(COMPAT_PACKAGE)
                    putExtra("songName", song.title)
                    putExtra("artist", song.artist)
                    putExtra("album", song.album)
                    putExtra("duration", song.duration)
                    putExtra("mediaId", song.path)
                    putExtra("lyricInfo", lyricInfo)
                }
            )
            true
        }.getOrDefault(false)
    }
}
