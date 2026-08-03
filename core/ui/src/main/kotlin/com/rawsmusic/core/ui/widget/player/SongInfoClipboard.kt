package com.rawsmusic.core.ui.widget.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R

private const val COPY_DEBOUNCE_MS = 1_500L
private val copyLock = Any()
private var lastCopiedText: String = ""
private var lastCopyElapsedMs: Long = Long.MIN_VALUE

fun copySongInfoToClipboard(context: Context, song: AudioFile?): Boolean {
    song ?: return false
    val text = listOf(song.displayName, song.artist, song.album)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
        .joinToString("\n")
    if (text.isBlank()) return false

    val now = SystemClock.elapsedRealtime()
    synchronized(copyLock) {
        // Compose may temporarily keep both current/target title nodes during a player
        // transition. Never publish the same clipboard payload more than once for one gesture.
        if (text == lastCopiedText && now - lastCopyElapsedMs < COPY_DEBOUNCE_MS) {
            return true
        }
        lastCopiedText = text
        lastCopyElapsedMs = now
    }

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false
    clipboard.setPrimaryClip(
        ClipData.newPlainText(context.getString(R.string.song_info_clipboard_label), text)
    )
    Toast.makeText(context, R.string.song_info_copied, Toast.LENGTH_SHORT).show()
    return true
}
