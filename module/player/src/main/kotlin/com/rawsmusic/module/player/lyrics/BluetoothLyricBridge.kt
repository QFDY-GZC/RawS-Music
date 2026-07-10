package com.rawsmusic.module.player.lyrics

import com.rawsmusic.module.data.prefs.AppPreferences

object BluetoothLyricBridge {

    private var lastText = ""
    private var lastTranslation = ""

    fun updateLyric(text: String, translation: String = "") {
        if (!AppPreferences.Lyrics.bluetoothLyricEnabled) return
        if (text.isBlank()) {
            clearLyric()
            return
        }
        if (text == lastText && translation == lastTranslation) return
        lastText = text
        lastTranslation = translation
        BluetoothLyricState.update(text, translation)
        PlayerServiceProxy.requestMetadataUpdate()
    }

    fun clearLyric() {
        lastText = ""
        lastTranslation = ""
        BluetoothLyricState.clear()
        PlayerServiceProxy.requestMetadataUpdate()
    }

    fun isEnabled(): Boolean = AppPreferences.Lyrics.bluetoothLyricEnabled

    fun setEnabled(enabled: Boolean) {
        AppPreferences.Lyrics.bluetoothLyricEnabled = enabled
        if (!enabled) {
            clearLyric()
        }
    }

    fun destroy() {
        lastText = ""
        lastTranslation = ""
        BluetoothLyricState.clear()
    }

    fun currentDisplayArtist(): String? {
        if (!AppPreferences.Lyrics.bluetoothLyricEnabled) return null
        val state = BluetoothLyricState.current() ?: return null
        val showTranslation = AppPreferences.Lyrics.bluetoothLyricTranslation && state.translation.isNotBlank()
        return if (showTranslation) "${state.text} / ${state.translation}" else state.text
    }
}

object BluetoothLyricState {
    data class Payload(
        val text: String,
        val translation: String
    )

    @Volatile
    private var payload: Payload? = null

    fun current(): Payload? = payload

    fun update(text: String, translation: String = "") {
        payload = if (text.isNotBlank()) Payload(text, translation) else null
    }

    fun clear() {
        payload = null
    }
}

object PlayerServiceProxy {
    private var updateCallback: (() -> Unit)? = null

    fun setUpdateCallback(callback: (() -> Unit)?) {
        updateCallback = callback
    }

    fun requestMetadataUpdate() {
        updateCallback?.invoke()
    }
}
