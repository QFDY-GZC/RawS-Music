package com.rawsmusic.module.player.lyrics

import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.core.common.utils.AppLogger

object BluetoothLyricBridge {

    private var lastText = ""
    private var lastTranslation = ""
    private var lastNextLine = ""

    fun updateLyric(
        text: String,
        translation: String = "",
        notify: Boolean = true,
        songIdentity: String? = null,
        nextLine: String = "",
    ) {
        if (!AppPreferences.Lyrics.bluetoothLyricEnabled) return
        if (text.isBlank()) {
            clearLyric(notify)
            return
        }
        if (text == lastText && translation == lastTranslation && nextLine == lastNextLine &&
            BluetoothLyricState.current()?.songIdentity == songIdentity) return
        lastText = text
        lastTranslation = translation
        lastNextLine = nextLine
        BluetoothLyricState.update(text, translation, songIdentity, nextLine)
        if (notify) PlayerServiceProxy.requestMetadataUpdate()
    }

    fun clearLyric(notify: Boolean = true) {
        if (lastText.isEmpty() && lastTranslation.isEmpty() && lastNextLine.isEmpty() &&
            BluetoothLyricState.current() == null) return
        lastText = ""
        lastTranslation = ""
        lastNextLine = ""
        BluetoothLyricState.clear()
        if (notify) PlayerServiceProxy.requestMetadataUpdate()
    }

    fun isEnabled(): Boolean = AppPreferences.Lyrics.bluetoothLyricEnabled

    fun setEnabled(enabled: Boolean) {
        if (AppPreferences.Lyrics.bluetoothLyricEnabled != enabled) {
            AppLogger.i("BluetoothLyricDiag", "CONFIG enabled=$enabled")
        }
        AppPreferences.Lyrics.bluetoothLyricEnabled = enabled
        // Selection/clear belongs to the service's publication looper, including while paused.
        PlayerServiceProxy.requestMetadataUpdate()
    }

    fun setTranslationEnabled(enabled: Boolean) {
        if (AppPreferences.Lyrics.bluetoothLyricTranslation != enabled) {
            AppLogger.i("BluetoothLyricDiag", "CONFIG translation=$enabled")
        }
        AppPreferences.Lyrics.bluetoothLyricTranslation = enabled
        PlayerServiceProxy.requestMetadataUpdate()
    }

    fun setMetadataField(field: BluetoothLyricMetadataField) {
        val old = BluetoothLyricMetadataField.fromId(AppPreferences.Lyrics.bluetoothLyricMetadataField)
        if (old == field) return
        AppPreferences.Lyrics.bluetoothLyricMetadataField = field.id
        AppLogger.i("BluetoothLyricDiag", "CONFIG metadataField=${field.id}")
        // Rebuild even when the lyric line remains unchanged so the previous field is restored.
        PlayerServiceProxy.requestMetadataUpdate()
    }

    fun destroy() {
        lastText = ""
        lastTranslation = ""
        lastNextLine = ""
        BluetoothLyricState.clear()
    }

    fun currentDisplayArtist(songIdentity: String?): String? {
        val state = BluetoothLyricState.current() ?: return null
        return bluetoothLyricDisplayArtist(
            AppPreferences.Lyrics.bluetoothLyricEnabled, songIdentity, state.songIdentity,
            state.text, state.translation, AppPreferences.Lyrics.bluetoothLyricTranslation,
        )
    }

    internal fun currentMetadataText(songIdentity: String?, title: String, artist: String): BluetoothLyricMetadataText {
        val state = BluetoothLyricState.current() ?: return BluetoothLyricMetadataText(title, artist)
        val valid = AppPreferences.Lyrics.bluetoothLyricEnabled &&
            songIdentity != null && songIdentity == state.songIdentity
        val field = BluetoothLyricMetadataField.fromId(AppPreferences.Lyrics.bluetoothLyricMetadataField)
        if (!valid) return BluetoothLyricMetadataText(title, artist)
        val translated = AppPreferences.Lyrics.bluetoothLyricTranslation && state.translation.isNotBlank()
        val primary = if (field == BluetoothLyricMetadataField.DUAL) state.text else {
            if (translated) "${state.text} / ${state.translation}" else state.text
        }
        val secondary = bluetoothLyricSecondaryLine(
            AppPreferences.Lyrics.bluetoothLyricTranslation,
            state.translation,
            state.nextLine,
        )
        return bluetoothLyricMetadataText(title, artist, primary, field, secondary)
    }
}

object BluetoothLyricState {
    data class Payload(
        val text: String,
        val translation: String,
        val songIdentity: String? = null,
        val nextLine: String = "",
    )

    @Volatile
    private var payload: Payload? = null

    fun current(): Payload? = payload

    fun update(
        text: String,
        translation: String = "",
        songIdentity: String? = null,
        nextLine: String = "",
    ) {
        payload = if (text.isNotBlank()) Payload(text, translation, songIdentity, nextLine) else null
    }

    fun clear() {
        payload = null
    }
}

object PlayerServiceProxy {
    @Volatile
    private var updateCallback: (() -> Unit)? = null

    fun setUpdateCallback(callback: (() -> Unit)?) {
        updateCallback = callback
    }

    fun requestMetadataUpdate() {
        updateCallback?.invoke()
    }
}
