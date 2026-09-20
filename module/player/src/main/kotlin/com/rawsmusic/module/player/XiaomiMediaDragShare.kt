package com.rawsmusic.module.player

import android.app.Notification
import android.os.Build
import com.rawsmusic.core.common.model.AudioFile
import org.json.JSONObject

/** Adds HyperOS media-island drag sharing without creating a second notification. */
internal object XiaomiMediaDragShare {
    private const val EXTRA_MEDIA_PARAMS = "miui.focus.param.media"

    fun attach(notification: Notification, song: AudioFile?) {
        if (song == null || !isXiaomiDevice()) return
        notification.extras.putString(EXTRA_MEDIA_PARAMS, buildPayload(song))
    }

    internal fun buildPayload(song: AudioFile): String {
        val title = song.displayName.ifBlank { "RawSMusic" }
        val artist = song.artist.trim()
        val album = song.album.trim()
        val content = listOf(artist, album)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" · ")
            .ifBlank { title }
        val shareContent = song.path.trim()
            .takeIf { it.startsWith("https://", true) || it.startsWith("http://", true) }
            ?: listOf(title, artist)
                .filter { it.isNotBlank() }
                .joinToString(" - ")

        val shareData = JSONObject()
            .put("title", title)
            .put("content", content)
            .put("shareContent", shareContent)
        val islandParams = JSONObject()
            .put("shareData", shareData)
        val paramsV2 = JSONObject()
            .put("param_island", islandParams)
        return JSONObject()
            .put("param_v2", paramsV2)
            .toString()
    }

    private fun isXiaomiDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val brand = Build.BRAND.orEmpty()
        return manufacturer.equals("xiaomi", true) ||
            brand.equals("xiaomi", true) ||
            brand.equals("redmi", true) ||
            brand.equals("poco", true)
    }
}
