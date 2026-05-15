package com.rawsmusic.module.player

import android.content.Context
import android.util.Log
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.toLyriconSong
import com.rawsmusic.module.data.prefs.AppPreferences
import io.github.proify.lyricon.provider.ConnectionStatus
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.service.addConnectionListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object LyriconProviderManager {

    private const val TAG = "LyriconProvider"

    private var provider: LyriconProvider? = null
    private var positionJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isInitialized = false

    var connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED
        private set

    var onConnectionStatusChanged: ((ConnectionStatus) -> Unit)? = null

    fun init(context: Context, appIconResId: Int = 0) {
        if (!AppPreferences.Lyricon.enabled) {
            Log.d(TAG, "Lyricon provider disabled")
            return
        }

        if (isInitialized && provider != null) {
            Log.d(TAG, "Provider already initialized")
            return
        }

        try {
            val logo = if (appIconResId != 0) {
                ProviderLogo.fromDrawable(context, appIconResId)
            } else null

            provider = LyriconFactory.createProvider(
                context = context,
                providerPackageName = context.packageName,
                playerPackageName = context.packageName,
                logo = logo
            )

            provider?.service?.addConnectionListener {
                onConnected { _ ->
                    Log.d(TAG, "Connected to Lyricon")
                    connectionStatus = ConnectionStatus.CONNECTED
                    onConnectionStatusChanged?.invoke(connectionStatus)
                }
                onReconnected { _ ->
                    Log.d(TAG, "Reconnected to Lyricon")
                    connectionStatus = ConnectionStatus.CONNECTED
                    onConnectionStatusChanged?.invoke(connectionStatus)
                }
                onDisconnected { _ ->
                    Log.d(TAG, "Disconnected from Lyricon")
                    connectionStatus = ConnectionStatus.DISCONNECTED
                    onConnectionStatusChanged?.invoke(connectionStatus)
                }
                onConnectTimeout { _ ->
                    Log.d(TAG, "Connection timeout")
                    connectionStatus = ConnectionStatus.DISCONNECTED
                    onConnectionStatusChanged?.invoke(connectionStatus)
                }
            }

            provider?.register()
            connectionStatus = ConnectionStatus.CONNECTING
            onConnectionStatusChanged?.invoke(connectionStatus)
            isInitialized = true
            Log.d(TAG, "Provider registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init provider", e)
        }
    }

    fun destroy() {
        try {
            stopPositionSync()
            provider?.destroy()
            provider = null
            isInitialized = false
            connectionStatus = ConnectionStatus.DISCONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "Failed to destroy provider", e)
        }
    }

    fun setSong(
        song: com.rawsmusic.core.common.model.AudioFile?,
        lyricData: com.rawsmusic.core.common.model.LyricData?
    ) {
        val player = provider?.player ?: return
        if (song == null) {
            player.setSong(null)
            return
        }

        val lyriconSong = lyricData?.toLyriconSong(
            name = song.title,
            artist = song.artist
        )
        player.setSong(lyriconSong)

        player.setDisplayTranslation(AppPreferences.Lyricon.displayTranslation)
        player.setDisplayRoma(AppPreferences.Lyricon.displayRoma)
    }

    fun setPlaybackState(isPlaying: Boolean) {
        provider?.player?.setPlaybackState(isPlaying)
    }

    fun setPosition(positionMs: Long) {
        provider?.player?.setPosition(positionMs)
    }

    fun seekTo(positionMs: Long) {
        provider?.player?.seekTo(positionMs)
    }

    fun setDisplayTranslation(display: Boolean) {
        AppPreferences.Lyricon.displayTranslation = display
        provider?.player?.setDisplayTranslation(display)
    }

    fun setDisplayRoma(display: Boolean) {
        AppPreferences.Lyricon.displayRoma = display
        provider?.player?.setDisplayRoma(display)
    }

    fun startPositionSync(playerController: PlayerController) {
        stopPositionSync()
        positionJob = scope.launch {
            while (isActive) {
                try {
                    val pos = playerController.position.value
                    val state = playerController.playState.value
                    if (state == PlayState.PLAYING) {
                        setPosition(pos)
                    }
                } catch (_: Exception) {}
                delay(200)
            }
        }
    }

    fun stopPositionSync() {
        positionJob?.cancel()
        positionJob = null
    }

    fun isConnected(): Boolean = connectionStatus == ConnectionStatus.CONNECTED

    fun isEnabled(): Boolean = AppPreferences.Lyricon.enabled

    fun setEnabled(enabled: Boolean) {
        AppPreferences.Lyricon.enabled = enabled
    }
}
