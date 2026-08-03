package com.rawsmusic.module.player.lyrics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.rawsmusic.module.data.prefs.AppPreferences

object LyricGetterBridge {

    private const val TAG = "LyricGetterBridge"

    private const val AUTHORITY = "com.mirai.lyricgetter.provider"
    private const val PATH_LYRIC = "lyric"
    private const val PATH_STATE = "state"

    private var lastText = ""
    private var lastTranslation = ""

    fun init(context: Context) {
        if (!AppPreferences.Lyrics.lyricGetterEnabled) return
        Log.d(TAG, "LyricGetterBridge initialized")
    }

    fun destroy() {
        lastText = ""
        lastTranslation = ""
    }

    fun updateLyric(context: Context, text: String, translation: String = "") {
        if (!AppPreferences.Lyrics.lyricGetterEnabled) return
        if (text.isBlank()) {
            clearLyric(context)
            return
        }
        if (text == lastText && translation == lastTranslation) return
        lastText = text
        lastTranslation = translation

        try {
            val uri = Uri.parse("content://$AUTHORITY/$PATH_LYRIC")
            val values = android.content.ContentValues().apply {
                put("lyric", text)
                put("translation", translation)
                put("package", "com.rawsmusic")
            }
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update lyric via ContentProvider, trying broadcast", e)
            sendFallbackBroadcast(context, text, translation)
        }
    }

    fun updatePlaybackState(context: Context, isPlaying: Boolean) {
        if (!AppPreferences.Lyrics.lyricGetterEnabled) return
        try {
            val uri = Uri.parse("content://$AUTHORITY/$PATH_STATE")
            val values = android.content.ContentValues().apply {
                put("playing", if (isPlaying) 1 else 0)
                put("package", "com.rawsmusic")
            }
            context.contentResolver.update(uri, values, null, null)
        } catch (_: Exception) {
            sendFallbackStateBroadcast(context, isPlaying)
        }
    }

    fun clearLyric(context: Context) {
        lastText = ""
        lastTranslation = ""
        try {
            val uri = Uri.parse("content://$AUTHORITY/$PATH_LYRIC")
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
            sendFallbackBroadcast(context, "", "")
        }
    }

    fun isEnabled(): Boolean = AppPreferences.Lyrics.lyricGetterEnabled

    fun setEnabled(enabled: Boolean) {
        AppPreferences.Lyrics.lyricGetterEnabled = enabled
    }

    private fun sendFallbackBroadcast(context: Context, text: String, translation: String) {
        try {
            val intent = Intent("com.mirai.lyricgetter.action.UPDATE_LYRIC").apply {
                putExtra("lyric", text)
                putExtra("translation", translation)
                putExtra("package", "com.rawsmusic")
                setPackage("com.mirai.lyricgetter")
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }

    private fun sendFallbackStateBroadcast(context: Context, isPlaying: Boolean) {
        try {
            val intent = Intent("com.mirai.lyricgetter.action.UPDATE_STATE").apply {
                putExtra("playing", isPlaying)
                putExtra("package", "com.rawsmusic")
                setPackage("com.mirai.lyricgetter")
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {}
    }
}
