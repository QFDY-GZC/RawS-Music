package com.rawsmusic.core.ui.widget.background

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.player.FfmpegVideoCover

enum class CustomBackgroundKind(val prefValue: String) {
    IMAGE("image"),
    VIDEO("video");

    companion object {
        fun fromPref(value: String?): CustomBackgroundKind =
            entries.firstOrNull { it.prefValue == value } ?: IMAGE
    }
}

object CustomMediaBackgroundState {
    private const val PREFS = "custom_media_background"
    private const val KEY_URI = "uri"
    private const val KEY_KIND = "kind"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SHOW_PLAYER = "show_player"
    private const val KEY_SHOW_SETTINGS = "show_settings"

    var sourceUri by mutableStateOf("")
        private set
    var sourceKind by mutableStateOf(CustomBackgroundKind.IMAGE)
        private set
    var enabled by mutableStateOf(false)
        private set
    var showOnPlayer by mutableStateOf(false)
        private set
    var showOnSettings by mutableStateOf(true)
        private set
    var revision by mutableIntStateOf(0)
        private set

    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sourceUri = prefs.getString(KEY_URI, "").orEmpty()
        sourceKind = CustomBackgroundKind.fromPref(prefs.getString(KEY_KIND, null))
        enabled = prefs.getBoolean(KEY_ENABLED, sourceUri.isNotBlank()) && sourceUri.isNotBlank()
        showOnPlayer = prefs.getBoolean(KEY_SHOW_PLAYER, false)
        showOnSettings = prefs.getBoolean(KEY_SHOW_SETTINGS, true)
        initialized = true
    }

    fun setSource(context: Context, uri: Uri, mimeType: String?) {
        ensureInitialized(context)
        sourceUri = uri.toString()
        sourceKind = if (mimeType?.startsWith("video/", ignoreCase = true) == true) {
            CustomBackgroundKind.VIDEO
        } else {
            CustomBackgroundKind.IMAGE
        }
        enabled = true
        persist(context)
    }

    fun setEnabled(context: Context, value: Boolean) {
        ensureInitialized(context)
        enabled = value && sourceUri.isNotBlank()
        persist(context)
    }

    fun setShowOnPlayer(context: Context, value: Boolean) {
        ensureInitialized(context)
        showOnPlayer = value
        persist(context)
    }

    fun setShowOnSettings(context: Context, value: Boolean) {
        ensureInitialized(context)
        showOnSettings = value
        persist(context)
    }

    fun clear(context: Context) {
        ensureInitialized(context)
        sourceUri = ""
        enabled = false
        persist(context)
    }

    private fun persist(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_URI, sourceUri)
            .putString(KEY_KIND, sourceKind.prefValue)
            .putBoolean(KEY_ENABLED, enabled)
            .putBoolean(KEY_SHOW_PLAYER, showOnPlayer)
            .putBoolean(KEY_SHOW_SETTINGS, showOnSettings)
            .apply()
        revision++
    }
}

@Composable
fun CustomMediaBackground(
    active: Boolean,
    playbackActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    CustomMediaBackgroundState.ensureInitialized(context)
    @Suppress("UNUSED_VARIABLE")
    val stateRevision = CustomMediaBackgroundState.revision
    val uri = CustomMediaBackgroundState.sourceUri
    if (!active || !CustomMediaBackgroundState.enabled || uri.isBlank()) return

    when (CustomMediaBackgroundState.sourceKind) {
        CustomBackgroundKind.VIDEO -> FfmpegVideoCover(
            uri = uri,
            active = active && playbackActive,
            cornerRadiusDp = 0f,
            modifier = modifier.fillMaxSize(),
        )
        CustomBackgroundKind.IMAGE -> BitmapImage(
            key = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.fillMaxSize(),
            targetWidth = 1536,
            targetHeight = 1536,
            priority = BitmapRequest.Priority.LOADING_WIDGET,
            surface = ArtworkSurface.Fullscreen,
            showDefaultArtwork = false,
        )
    }
}
