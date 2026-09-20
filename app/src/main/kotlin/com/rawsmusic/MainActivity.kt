package com.rawsmusic

import android.Manifest
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.net.Uri
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.OrientationEventListener
import android.widget.Toast
import io.github.proify.lyricon.lyric.model.Song
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.ext.isDarkMode
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricTimingEditorTarget
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.toLyriconSong
import com.rawsmusic.core.common.ui.AppNoticeBus
import com.rawsmusic.core.common.ui.AppNoticeIcon
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.UiUtils
import com.rawsmusic.core.ui.R as UiR
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.theme.RawSMusicTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.resolveHomeFullCoverActivityHostPolicy
import com.rawsmusic.core.ui.widget.DynamicCoverBackgroundState
import com.rawsmusic.core.ui.widget.ImmersiveBackgroundState
import com.rawsmusic.core.ui.widget.PlayerLyricsTransitionCoordinator
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.core.ui.widget.effectiveMiniPlayerArtworkSizeDp
import com.rawsmusic.core.ui.widget.miniPlayerArtworkSourceRadiusDp
import com.rawsmusic.core.ui.widget.readStoredMiniPlayerArtworkMode
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkKeyContinuity
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkDirection
import com.rawsmusic.core.ui.widget.bitmaps.rememberPlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackQueueBindingIndex
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.FontManager
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.PlaybackStatsStore
import com.rawsmusic.module.player.AudioOutputManager
import com.rawsmusic.module.player.LyriconProviderManager
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerEventBus
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.player.UsbStatusNoticeBus
import com.rawsmusic.module.player.lyrics.BluetoothLyricBridge
import com.rawsmusic.module.player.lyrics.LyricGetterBridge
import com.rawsmusic.module.player.lyrics.TickerBridge
import com.rawsmusic.module.scanner.LyricReader
import com.rawsmusic.ui.songs.PlayerHolder
import com.rawsmusic.ui.player.LandscapePlayerActivity
import com.rawsmusic.ui.update.UpdateNotesDialog
import com.rawsmusic.ui.player.AiLyricTimingPreviewDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rawsmusic.helper.AudioCapsuleUiHelper
import com.rawsmusic.helper.AudioPermissionHelper
import com.rawsmusic.helper.AudioInfoCapsuleHelper
import com.rawsmusic.helper.AudioInfoCapsuleOverlay
import com.rawsmusic.helper.AudioInfoLink
import com.rawsmusic.helper.LyricoIntegration
import com.rawsmusic.helper.CoverBackgroundLayerState
import com.rawsmusic.helper.CoverCoordinator
import com.rawsmusic.helper.DialogHelper
import com.rawsmusic.helper.DialogOverlay
import com.rawsmusic.helper.LastPlayingStateHelper
import com.rawsmusic.helper.GestureLockCoordinator
import com.rawsmusic.helper.GestureLockReason
import com.rawsmusic.helper.GestureLock
import com.rawsmusic.helper.LyricLoadHelper
import com.rawsmusic.helper.MainPlaybackQueueHelper
import com.rawsmusic.helper.LyricsPublisher
import com.rawsmusic.helper.LyricsCoordinator
import com.rawsmusic.helper.MiniPlayerCoordinator
import com.rawsmusic.helper.OverlayCoordinator
import com.rawsmusic.helper.PlaybackCoordinator
import com.rawsmusic.helper.LyricStyleHelper
import com.rawsmusic.helper.MetadataCardPopupHelper
import com.rawsmusic.helper.MetadataCardPopupOverlay
import com.rawsmusic.helper.MetadataDetailHelper
import com.rawsmusic.helper.MetadataDetailOverlay
import com.rawsmusic.helper.MetadataEditorHelper
import com.rawsmusic.helper.MetadataEditorOverlay
import com.rawsmusic.helper.PlaybackStatsHelper
import com.rawsmusic.helper.PlayerActionObserverHelper
import com.rawsmusic.helper.PlayerControllerBindingHelper
import com.rawsmusic.helper.PlayModePopupHelper
import com.rawsmusic.helper.PlayModePopupOverlay
import com.rawsmusic.helper.PlayerServiceBridgeHelper
import com.rawsmusic.helper.SearchStateHelper
import com.rawsmusic.helper.SongActionSheetHelper
import com.rawsmusic.helper.SongDeletionCoordinator
import com.rawsmusic.lyrico.LyricoPluginStore
import com.rawsmusic.metadata.LibraryMetadataMatchContract
import com.rawsmusic.metadata.LibraryMetadataMatchMode
import com.rawsmusic.metadata.LibraryMetadataMatchPhase
import com.rawsmusic.metadata.LibraryMetadataMatchProgressBus
import com.rawsmusic.helper.SongActionSheetOverlay
import com.rawsmusic.helper.StartupPermissionFlowHelper
import com.rawsmusic.helper.StartupScanHelper
import com.rawsmusic.helper.ThemeCoordinator
import com.rawsmusic.helper.ScannerCoordinator
import com.rawsmusic.helper.SystemBarsHelper
import com.rawsmusic.helper.UsbVolumeKeyHandler

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

private fun lyricSongKey(song: AudioFile): String = buildString {
    append(song.path)
    append('|')
    append(song.cueOffsetMs)
    append('|')
    append(song.cueEndMs)
    append('|')
    append(song.cueTrackIndex)
}

class MainActivity : ComponentActivity() {
    private val sleepTimerExitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == PlayerController.ACTION_SLEEP_TIMER_EXIT_APP) {
                finishAndRemoveTask()
            }
        }
    }

    private val songDeletionCoordinator = SongDeletionCoordinator(this)
    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    // Keep the foreground volume keys on the original in-app overlay path. The
    // MediaSession callback remains the background fallback when this Activity is stopped.
    private val usbVolumeKeyHandler by lazy {
        UsbVolumeKeyHandler({ playerController }, ::showUsbVolumeOverlay)
    }
    private var startupWorkScheduled = false

    private var currentLyricData by mutableStateOf(LyricData())
    private var currentLyricDataSongKey by mutableStateOf<String?>(null)
    private var aiTimingPreviewSong by mutableStateOf<AudioFile?>(null)
    private var showAiTimingPreview by mutableStateOf(false)
    private var composeLyricSong by mutableStateOf<Song?>(null)
    private var composeLyricPositionMs by mutableLongStateOf(0L)
    private var composeDisplayTranslation by mutableStateOf(AppPreferences.Lyricon.displayTranslation)
    private var composeDisplayRoma by mutableStateOf(AppPreferences.Lyricon.displayRoma)
    private var composeLyricIsLight by mutableStateOf(false)

    @Volatile
    private var activityForegroundForPower = false
    private var composeActivityForeground by mutableStateOf(false)
    @Volatile
    private var visualizerUiRequested = false
    private var composeAudioVisualizerEnabled by mutableStateOf(AppPreferences.UI.isAudioVisualizerEnabled)
    private var composeAudioVisualizerStyle by mutableStateOf(
        com.rawsmusic.core.ui.widget.player.AudioVisualizerStyle.from(
            AppPreferences.UI.audioVisualizerStyle
        )
    )
    private var composeAudioVisualizerHapticsEnabled by mutableStateOf(
        AppPreferences.UI.audioVisualizerHapticsEnabled
    )
    private var composeAudioVisualizerParticleColorMode by mutableStateOf(
        com.rawsmusic.core.ui.widget.player.AudioVisualizerParticleColorMode.from(
            AppPreferences.UI.audioVisualizerParticleColorMode
        )
    )
    private val audioVisualizerHapticController by lazy {
        AudioVisualizerHapticController(this)
    }
    private val realtimeSpectrumPipeline by lazy {
        com.rawsmusic.module.player.dsp.RealtimeSpectrumPipeline { spectrum ->
            runOnUiThread {
                if (visualizerUiRequested && activityForegroundForPower) {
                    if (composeAudioVisualizerEnabled) {
                        audioVisualizerHapticController.onSpectrum(
                            spectrum = spectrum,
                            enabled = composeAudioVisualizerHapticsEnabled,
                        )
                    }
                    visualizerSpectrum = spectrum
                }
            }
        }
    }
    private val visualizerCoordinator by lazy {
        MainActivityVisualizerCoordinator(
            pipeline = realtimeSpectrumPipeline,
            isActivityForeground = { activityForegroundForPower },
            isUiRequested = { visualizerUiRequested },
            setEnabled = { composeAudioVisualizerEnabled = it },
            hasPermission = { audioPermissionHelper.isVisualizerPermissionGranted() },
            isPlaying = { playerController?.playState?.value == PlayState.PLAYING },
            setSpectrum = { visualizerSpectrum = it },
        )
    }
    private val libraryMetadataCoordinator by lazy {
        MainActivityLibraryMetadataCoordinator(this)
    }
    private val lyricoCoordinator by lazy {
        MainActivityLyricoCoordinator(
            activity = this,
            currentSong = { playerController?.currentSong?.value },
            editorLauncher = lyricoEditorLauncher,
            searchLauncher = lyricoSearchLauncher,
            setPendingEditSong = { pendingLyricoEditSong = it },
            onEditApplied = { original, refreshed, mutation ->
                if (mutation.artworkChanged) {
                    val previousArtworkKey = original.resolvePlaybackArtworkKey()
                    val committedArtworkKey = refreshed.resolvePlaybackArtworkKey()
                    PlaybackArtworkKeyContinuity.markArtworkRewrite(
                        previousKey = previousArtworkKey,
                        committedKey = committedArtworkKey,
                    )
                    if (::coverUriResolver.isInitialized) {
                        coverUriResolver.invalidate(original)
                        coverUriResolver.invalidate(refreshed)
                    }
                    committedArtworkKey?.let(BitmapProvider::warmPlaybackArt)
                }
                if (refreshed !== original || mutation.artworkChanged) {
                    playerController?.updateCurrentSongIfSamePath(refreshed)
                }
                if (mutation.lyricsChanged) {
                    lyricsCoordinator.loadLyricsForSong(refreshed)
                }
            },
        )
    }
    private val lyricStateCoordinator by lazy {
        MainActivityLyricStateCoordinator(
            currentSong = { playerController?.currentSong?.value },
            currentPositionMs = { playerController?.position?.value ?: 0L },
            setLyricData = {
                currentLyricData = it
                currentLyricDataSongKey = playerController?.currentSong?.value?.let(::lyricSongKey)
            },
            setLyricSong = { composeLyricSong = it },
            setDisplayTranslation = { composeDisplayTranslation = it },
            setDisplayRoma = { composeDisplayRoma = it },
            setPositionMs = { composeLyricPositionMs = it },
        )
    }
    private var audioVisualizerReceiverRegistered = false
    private val audioVisualizerSettingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_AUDIO_VISUALIZER_SETTING_CHANGED) {
                syncAudioVisualizerPreference("settings_broadcast")
            }
        }
    }
    private val rotationCoordinator by lazy {
        MainActivityRotationCoordinator(
            activity = this,
            currentScene = {
                if (::playerSceneController.isInitialized) {
                    playerSceneController.currentScene
                } else {
                    PlayerSceneController.Scene.MAIN
                }
            },
            homeFullCoverActive = { homeFullCoverOverlayActive },
            playerModalVisible = { composePlayerModalVisible },
            hasCurrentSong = { playerController?.currentSong?.value != null },
            onPortraitDetected = {
                if (::playerSceneController.isInitialized) {
                    syncMainActivityRotationPolicy(playerSceneController.currentScene)
                }
            },
            onLaunchLandscapePlayer = {
                startActivity(LandscapePlayerActivity.createIntent(this))
                overridePendingTransition(0, 0)
            },
        )
    }
    private var homeFullCoverOverlayActive by mutableStateOf(false)

    private var legacyDestinationId: Int = R.id.nav_songs
    private lateinit var playerSceneController: PlayerSceneController
    internal var playerController by mutableStateOf<PlayerController?>(null)

    // 封面 URI 解析器
    private lateinit var coverUriResolver: com.rawsmusic.helper.CoverUriResolver

    // 封面背景管理器
    private lateinit var coverBackgroundManager: com.rawsmusic.helper.CoverBackgroundManager
    private val backgroundState = DynamicCoverBackgroundState()
    private val playBackgroundState = DynamicCoverBackgroundState()
    private val lyricBackgroundState = DynamicCoverBackgroundState()
    private val immersiveBackgroundState = ImmersiveBackgroundState()
    private val mainPersistentCoverState = ImmersiveBackgroundState()
    private val backgroundLayerState = CoverBackgroundLayerState()
    private var composeImmersiveEnabled by mutableStateOf(AppPreferences.UI.isImmersiveEnabled)
    private var composeMiniCoverEnabled by mutableStateOf(AppPreferences.UI.isMiniCoverEnabled)
    private var composeDefaultBackgroundEnabled by mutableStateOf(AppPreferences.UI.isDefaultBackgroundEnabled)
    /** Compose 播放器内部是否有需要优先处理返回的菜单/队列等模态层。 */
    private var composePlayerModalVisible by mutableStateOf(false)
    /** Direct dismiss callback for the currently visible Compose player modal. */
    private var composePlayerModalDismissAction: (() -> Unit)? = null
    private var pendingLyricoEditSong: AudioFile? = null
    private val lyricoEditorLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.i(
            com.rawsmusic.helper.LyricoIntegration.LOG_TAG,
            "edit_result code=${result.resultCode} data=${result.data?.data} " +
                "pending=${pendingLyricoEditSong?.path}"
        )
        pendingLyricoEditSong?.let { editedSong ->
            refreshSongAfterLyricoEdit(editedSong, LyricoEditMutation.Unknown)
        }
        pendingLyricoEditSong = null
    }
    private val lyricoSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val editedPath = result.data?.getStringExtra(
            com.rawsmusic.ui.settings.LyricoSearchActivity.EXTRA_SONG_PATH
        )
        val current = playerController?.currentSong?.value
        if (current != null && current.path == editedPath) {
            val lyricsChanged = result.data?.getBooleanExtra(
                com.rawsmusic.ui.settings.LyricoSearchActivity.EXTRA_CHANGED_LYRICS,
                false,
            ) == true
            val artworkChanged = result.data?.getBooleanExtra(
                com.rawsmusic.ui.settings.LyricoSearchActivity.EXTRA_CHANGED_ARTWORK,
                false,
            ) == true
            refreshSongAfterLyricoEdit(
                current,
                LyricoEditMutation.fromFlags(lyricsChanged, artworkChanged),
            )
        }
    }
    private var playingCoverBoundsForTransition by mutableStateOf<android.graphics.RectF?>(null)
    private var miniPlayerCoverBoundsForTransition by mutableStateOf<android.graphics.RectF?>(null)
    private var miniPlayerCoverTargetForTransition by mutableStateOf<CoverTransitionTarget?>(null)
    // Stable collapsed MiniPlayer cover geometry retained while PLAYER covers MAIN. Return must
    // never depend on a transient/null MiniPlayer callback or on the ListCover used for entry.
    private var stableMiniPlayerCoverGeometryForTransition by mutableStateOf<CoverTransitionTarget?>(null)
    private var miniPlayerGestureBoundsForSceneGesture by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    private var stableMiniPlayerBoundsForSceneGesture by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    private var lockedMiniPlayerBoundsForTransition by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    private var bottomNavigationGestureBoundsForSceneGesture by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    // FLOATING MiniPlayer is a sibling of the player sheet rather than an in-sheet child.
    // Keep one stable MAIN-layout top and freeze it for MAIN <-> PLAYER handoffs; feeding the
    // live AnimatedVisibility/offset position back into the sheet travel makes the collapsed
    // anchor move while the sheet itself is settling, producing whole-screen shake.
    private var stableFloatingMiniPlayerTopPx by mutableStateOf<Float?>(null)
    private var lockedFloatingMiniPlayerTopPxForTransition by mutableStateOf<Float?>(null)
    private var floatingReturnOwnsMiniPlayerTarget by mutableStateOf(false)
    private var coverTargetForTransition by mutableStateOf<CoverTransitionTarget?>(null)
    private var lockedPlayerCoverBoundsForTransition by mutableStateOf<android.graphics.RectF?>(null)
    private var lockedPlayerCoverPathForTransition by mutableStateOf<String?>(null)
    private var playerReturnRevealIndex by mutableIntStateOf(-1)
    private var coldStartRevealPending = true
    private var coldStartRevealAwaitingResolve = false
    private var coldStartRevealJob: kotlinx.coroutines.Job? = null

    /** 手势锁协调器：统一管理"谁正在禁止父级手势" */
    private val gestureLockCoordinator by lazy {
        GestureLockCoordinator { blocked ->
            if (::playerSceneController.isInitialized) {
                playerSceneController.disableGestureIntercept = blocked
            }
        }
    }

    /** 进度条拖动锁 */
    private var progressSeekLock: GestureLock? = null
    private var progressSeekActive = false

    /** seek 后 UI 防回跳状态 */
    private var isSeekUiHolding by mutableStateOf(false)
    private var seekTargetMs: Long = -1L
    private var seekFinishTimeMs: Long = 0L
    private var lyricsNeedSeekTo = false

    private fun beginProgressSeek() {
        progressSeekActive = true
        progressSeekLock?.release()
        progressSeekLock = null
    }

    private fun endProgressSeek() {
        progressSeekActive = false
        progressSeekLock?.release()
        progressSeekLock = null
    }

    private fun startSeekUiHold(targetMs: Long) {
        seekTargetMs = targetMs
        seekFinishTimeMs = System.currentTimeMillis()
        isSeekUiHolding = true
    }

    private fun stopSeekUiHold() {
        isSeekUiHolding = false
        seekTargetMs = -1L
    }

    private fun bindAudioVisualizerCallback(controller: PlayerController) {
        visualizerCoordinator.bind(controller)
    }

    internal fun ensureRuntimeController(reason: String): PlayerController {
        val staleUiController = playerController?.takeUnless { it.isOperational() }
        if (staleUiController != null) {
            AppLogger.w(
                "PlayerTransport",
                "discard released UI controller reason=$reason controller=${System.identityHashCode(staleUiController)}"
            )
            playerController = null
        }
        val existing = playerController?.takeIf { it.isOperational() }
            ?: PlayerHolder.controller?.takeIf { it.isOperational() }
            ?: PlayerService.currentRuntimeController()?.takeIf { it.isOperational() }
            ?: PlayerController.getInstanceOrNull()?.takeIf { it.isOperational() }
        if (existing != null) {
            playerController = existing
            PlayerHolder.controller = existing
            bindAudioVisualizerCallback(existing)
            playbackObserverCoordinator.start(existing)
            return existing
        }
        return PlayerService.obtainRuntimeController(this, reason).also { controller ->
            playerController = controller
            PlayerHolder.controller = controller
            bindAudioVisualizerCallback(controller)
            playbackObserverCoordinator.start(controller)
        }
    }

    private fun <T> dispatchPlayerTransportAction(
        action: String,
        traceDispatch: Boolean = false,
        command: (PlayerController) -> T
    ): T? {
        val switchAction = action.contains("next", ignoreCase = true) ||
            action.contains("previous", ignoreCase = true) ||
            action.contains("carousel_navigate", ignoreCase = true)
        if (switchAction && !com.rawsmusic.core.common.utils.PlayerSwitchTrace.isActive()) {
            com.rawsmusic.core.common.utils.PlayerSwitchTrace.begin(
                kind = "transport_$action",
                detail = "scene=${if (::playerSceneController.isInitialized) playerSceneController.currentScene else "uninitialized"}",
            )
        }
        if (switchAction) {
            com.rawsmusic.core.common.utils.PlayerSwitchTrace.mark(
                "transport_dispatch",
                "action=$action thread=${Thread.currentThread().name}",
            )
        }
        val previous = playerController
        val controller = ensureRuntimeController("ui_transport_$action")
        if (traceDispatch) {
            AppLogger.i(
                "PlayerTransport",
                "dispatch action=$action controller=${System.identityHashCode(controller)} " +
                    "rebound=${previous !== controller} operational=${controller.isOperational()} " +
                    "playState=${controller.playState.value} ffmpegState=${controller.ffmpegPlayerRef.state}"
            )
        }
        return runCatching { command(controller) }
            .onSuccess { result ->
                if (switchAction) {
                    val song = result as? AudioFile
                    com.rawsmusic.core.common.utils.PlayerSwitchTrace.mark(
                        "transport_return",
                        "action=$action result=${song?.let { "songId=${it.id} title=${it.title}" } ?: (result?.let { it::class.java.simpleName } ?: "null")}",
                    )
                }
            }
            .onFailure {
                if (switchAction) {
                    com.rawsmusic.core.common.utils.PlayerSwitchTrace.mark(
                        "transport_failure",
                        "action=$action type=${it::class.java.simpleName} message=${it.message.orEmpty()}",
                    )
                    com.rawsmusic.core.common.utils.PlayerSwitchTrace.finishLater("transport_failure")
                }
                AppLogger.e("PlayerTransport", "dispatch failed action=$action", it)
            }
            .getOrNull()
    }

    private var isSideMenuOpen by mutableStateOf(false)
    private var sideRailOpenRequestToken by mutableLongStateOf(0L)
    private var sideRailCloseRequestToken by mutableLongStateOf(0L)

    /** 进入播放器前的 Fragment 导航目标，用于返回时恢复正确的页面 */
    private var prePlayerFragmentDest: Int? = null
    /** 进入播放器前是否从独立页面入口进入（历史字段，Compose 迁移期间保留恢复语义） */
    private var prePlayerWasInFragmentMode: Boolean = false
    /** 进入播放器前主 Compose 导航的当前场景 */
    private var prePlayerContainerScene: com.rawsmusic.core.ui.scene.NavScene? = null
    /** 播放器关闭后需要进入的 Compose 设置场景。 */
    private var pendingSettingsSceneAfterPlayerClose: com.rawsmusic.core.ui.scene.NavScene? = null
    /**
     * Audio-info links use a shared transient window: the player remains mounted underneath
     * while the linked library page is temporarily promoted above it.
     */
    private var audioInfoSharedWindowActive by mutableStateOf(false)
    private var audioInfoSharedWindowOrigin: com.rawsmusic.core.ui.scene.NavScene? = null
    private var settingsActivityLaunched = false
    private var predictiveBackMainPlayerSheetVisible = false

    private fun launchSettingsActivity(activityClass: Class<*>) {
        settingsActivityLaunched = true
        startActivity(android.content.Intent(this, activityClass))
    }

    private val sceneNavigationCoordinator by lazy {
        MainActivitySceneNavigationCoordinator(
            navigationState = mainNavState,
            currentPlayerScene = {
                runCatching { playerSceneController.currentScene }.getOrNull()
            },
            registerCoverCollapseParams = ::registerCoverCollapseParams,
            closeLyricPage = { force -> playerSceneController.closeLyricPage(force) },
            closePlayPage = { force -> playerSceneController.closePlayPageWithCoverAlign(force) },
            postDelayed = { delayMs, action -> mainHandler.postDelayed(action, delayMs) },
            setPendingSettingsScene = { scene -> pendingSettingsSceneAfterPlayerClose = scene },
            updateComposeRootVisibility = ::updateComposeRootVisibility,
            launchSettingsActivity = ::launchSettingsActivity,
            updateDrawerLockMode = ::updateDrawerLockMode,
            prePlayerFragmentDestination = { prePlayerFragmentDest },
            prePlayerWasInFragmentMode = { prePlayerWasInFragmentMode },
            prePlayerContainerScene = { prePlayerContainerScene },
            songsDestinationId = R.id.nav_songs,
            setLegacyDestination = { destinationId -> legacyDestinationId = destinationId },
            legacyDestination = { legacyDestinationId },
        )
    }

    private val audioInfoLinkCoordinator by lazy {
        MainActivityAudioInfoLinkCoordinator(
            activity = this,
            navigationState = mainNavState,
            currentPlayerScene = {
                runCatching { playerSceneController.currentScene }.getOrNull()
            },
            openPlayerDestination = ::openDestinationFromPlayerPopup,
            sharedWindowActive = { audioInfoSharedWindowActive },
            setSharedWindowActive = { active -> audioInfoSharedWindowActive = active },
            sharedWindowOrigin = { audioInfoSharedWindowOrigin },
            setSharedWindowOrigin = { origin -> audioInfoSharedWindowOrigin = origin },
            updateDrawerLockMode = ::updateDrawerLockMode,
            updatePredictiveBackRegistration = ::updatePredictiveBackRegistration,
        )
    }
    private val runtimeLifecycleCoordinator by lazy {
        MainActivityRuntimeLifecycleCoordinator(
            mainNavState = mainNavState,
            isSceneControllerInitialized = { ::playerSceneController.isInitialized },
            resetPredictiveBackGestureOwnership = predictiveBackCoordinator::resetGestureOwnership,
            updatePredictiveBackRegistration = ::updatePredictiveBackRegistration,
            disablePredictiveBack = { predictiveBackCoordinator.disable() },
            removePredictiveBackHandoff = {
                predictiveBackCoordinator.removeHandoffRelease()
            },
            postToWindow = { action -> window.decorView.post(action) },
            postToWindowDelayed = { delayMs, action ->
                window.decorView.postDelayed(action, delayMs)
            },
            detachUsbStatusNotice = {
                UsbStatusNoticeBus.detach(usbStatusNoticeListener)
            },
            stopRotation = rotationCoordinator::onStop,
            setActivityForeground = { foreground ->
                activityForegroundForPower = foreground
                composeActivityForeground = foreground
            },
            stopRealtimeSpectrum = { visualizerCoordinator.stopAndReset() },
            resetVisualizerSpectrum = { visualizerCoordinator.stopAndReset() },
        )
    }

    /*观察播放器动作（通过 PlayerEventBus）*/
    private fun observePlayerActions() {
        playerActionObserverHelper.observe()
    }

    /** 上次同步播放位置的时间*/
    private var lastSyncPositionTime = 0L

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            scannerCoordinator.onPermissionGranted()
        } else {
            AppNoticeBus.error(getString(R.string.permission_denied))
        }
    }

    private var pendingVisualizerEnableReason: String? = null
    private val visualizerPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val reason = pendingVisualizerEnableReason ?: "permission_result"
        pendingVisualizerEnableReason = null
        if (granted) {
            applyAudioVisualizerEnabled(true, reason)
        } else {
            applyAudioVisualizerEnabled(false, "${reason}_denied")
            AppNoticeBus.error(getString(R.string.permission_denied))
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            folderPickerResultUri = uri
        }
    }

    override fun finish() {
        super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Warm the diagnostic worker before any player gesture. Track-switch tracing only queues
        // in-memory events on hot paths; all Logcat I/O happens later on this background worker.
        com.rawsmusic.core.common.utils.PlayerSwitchTrace.prepare()
        // 初始化 PlayerController，如果当前没有 controller 则创建
        if (playerController == null) {
            ensureRuntimeController("main_activity_on_create")
        }
        ThemeManager.applyTheme(ThemeManager.getCurrentTheme())
        super.onCreate(savedInstanceState)
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            sleepTimerExitReceiver,
            IntentFilter(PlayerController.ACTION_SLEEP_TIMER_EXIT_APP),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Hydrate the durable queue/current selection before the first Compose player tree is
        // mounted. The deferred startup restore remains as an idempotent fallback, but it must not
        // be the first owner: opening PLAYER during that gap otherwise creates an artwork pager
        // with no previous/next topology, so the cover disappears and horizontal switching cannot
        // begin until a later transport command republishes the queue.
        restorePlayerUiStateForNavigation("activity_pre_compose")
        if (AppPreferences.UI.isAudioVisualizerEnabled &&
            !audioPermissionHelper.isVisualizerPermissionGranted()
        ) {
            AppPreferences.UI.isAudioVisualizerEnabled = false
            composeAudioVisualizerEnabled = false
        }
        if (!audioVisualizerReceiverRegistered) {
            val visualizerFilter = IntentFilter(ACTION_AUDIO_VISUALIZER_SETTING_CHANGED)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    audioVisualizerSettingReceiver,
                    visualizerFilter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(audioVisualizerSettingReceiver, visualizerFilter)
            }
            audioVisualizerReceiverRegistered = true
        }

        // 恢复导航状态（划掉后台重开时回到之前的页面）
        com.rawsmusic.core.ui.scene.NavigationPersistence.restore(this, mainNavState)

        playerSceneController = PlayerSceneController()
        // Register before Compose so dialog/sheet handlers added by setContent remain above the
        // scene fallback in OnBackPressedDispatcher's LIFO order, including on a cold launch.
        setupPredictiveBack()
        // Reference provider/index ownership starts with the library snapshot, not with the first
        // destination page composition. Kick the serialized metadata owner before Compose so the
        // first HOME -> category pivot never becomes the trigger for O(N) provider construction.
        com.rawsmusic.core.ui.scene.pages.LibrarySceneGroupingWarmup.onLibrarySnapshot(
            MusicRepository.songs.value
        )
        lifecycleScope.launch {
            MusicRepository.songs.collectLatest { snapshot ->
                // Build root provider metadata on the dedicated low-priority metadata lane while
                // the library snapshot is stable. GenericPivot should only bind LayoutRes/holders
                // on navigation; ALBUMS/FOLDERS/ARTISTS grouping must not first happen on click.
                com.rawsmusic.core.ui.scene.pages.LibrarySceneGroupingWarmup.prewarmRootProviders(snapshot)
            }
        }
        setContent { RootContent() }
        setupLandscapePlayerEntry()

        val isLightTheme = !ThemeManager.isDarkMode(this)
        backgroundState.setThemeLightMode(isLightTheme)

        backgroundLayerState.backgroundVisible = false
        ThemeManager.isLightBackground = isLightTheme

        prePlayerWasInFragmentMode = savedInstanceState?.getBoolean("prePlayerWasInFragmentMode", false)
            ?: com.rawsmusic.module.data.prefs.AppPreferences.UI.wasInFragmentMode
        val savedDest = savedInstanceState?.getInt("prePlayerFragmentDest", -1)
            ?: com.rawsmusic.module.data.prefs.AppPreferences.UI.lastFragmentDest
        if (savedDest != -1) prePlayerFragmentDest = savedDest
        legacyDestinationId = savedInstanceState?.getInt("legacyDestinationId", R.id.nav_songs) ?: R.id.nav_songs
        savedInstanceState?.getString("prePlayerContainerScene")?.let {
            prePlayerContainerScene = com.rawsmusic.core.ui.scene.NavScene.entries.find { s -> s.name == it }
        }
        FontManager.init(this)
        metadataEditorHelper

        themeCoordinator.register()
        initView()
        initData()
        initObserver()
        initListener()
        usbIntentCoordinator.handleAttachIntent(intent, reason = "activity_on_create")
        usbIntentCoordinator.setAttachAliasEnabled(true, "on_create_restore")
        scheduleDeferredStartupWork()
        intentCoordinator.handlePlaybackWidgetIntent(intent, delayMs = 420L)
        intentCoordinator.handleLauncherShortcutIntent(intent, delayMs = 520L)
        handleExternalAudioIntent(intent, delayMs = 700L)
        window.decorView.postDelayed({
            if (!isFinishing && !isDestroyed) {
                val handled = PlayerService.dispatchAppProcessForeground(
                    this,
                    "main_activity_on_create_posted"
                )
                if (!handled) {
                    playerController?.onAppForegroundResumed()
                }
            }
        }, 360)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        usbIntentCoordinator.handleAttachIntent(intent, reason = "activity_on_new_intent")
        intentCoordinator.handlePlaybackWidgetIntent(intent, delayMs = 80L)
        intentCoordinator.handleLauncherShortcutIntent(intent, delayMs = 80L)
        handleExternalAudioIntent(intent, delayMs = 120L)
    }

    /** Receives audio files opened by file managers and other media applications. */
    private fun handleExternalAudioIntent(intent: Intent, delayMs: Long) {
        if (intent.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val mimeType = intent.type.orEmpty()
        if (mimeType.isNotBlank() &&
            !mimeType.startsWith("audio/", ignoreCase = true) &&
            mimeType != "application/octet-stream"
        ) {
            return
        }

        AppLogger.i("ExternalAudioIntent", "received uri=$uri mime=$mimeType")
        lifecycleScope.launch {
            delay(delayMs)
            val song = withContext(Dispatchers.IO) {
                stageExternalAudio(uri, mimeType)
            }
            if (song == null) {
                AppLogger.e("ExternalAudioIntent", "stage_failed uri=$uri")
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            AppLogger.i(
                "ExternalAudioIntent",
                "play path=${song.path} title=${song.title} format=${song.format}",
            )
            primePlayerUi(song)
            playbackQueueHelper.playQueue(listOf(song), 0)
            openPlayPageWithSharedElement()
        }
    }

    private fun stageExternalAudio(uri: Uri, mimeType: String): AudioFile? {
        val displayName = queryExternalAudioName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "external_audio"
        val directPath = uri.path?.takeIf { uri.scheme.equals("file", ignoreCase = true) }
        val sourcePath = directPath?.takeIf { File(it).isFile }
        val extensionFromName = displayName.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it.length <= 8 }
        val extension = extensionFromName
            ?: mimeType.substringAfterLast('/').substringBefore(';')
                .replace("mpeg", "mp3", ignoreCase = true)
                .takeIf { it.isNotBlank() }
            ?: "audio"
        val stagedPath = sourcePath ?: run {
            val directory = File(cacheDir, "external_audio").apply { mkdirs() }
            val target = File(directory, "${Integer.toHexString(uri.toString().hashCode())}.$extension")
            if (!target.isFile || target.length() == 0L) {
                val temporary = File(target.parentFile, "${target.name}.part")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temporary).use { output -> input.copyTo(output) }
                } ?: return null
                if (!temporary.renameTo(target)) {
                    temporary.delete()
                    return null
                }
            }
            target.absolutePath
        }
        val title = displayName.substringBeforeLast('.', displayName).ifBlank { displayName }
        return AudioFile(
            path = stagedPath,
            title = title,
            format = extension.uppercase(Locale.ROOT),
        )
    }

    private fun queryExternalAudioName(uri: Uri): String? {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
    }

    private fun openPlaylistPickerFromWidget(attempt: Int = 0) {
        if (isFinishing || isDestroyed) return
        if (playerController?.currentSong?.value != null) {
            songActionSheetHelper.addToPlaylist()
            return
        }
        if (attempt < 12) {
            mainHandler.postDelayed(
                { openPlaylistPickerFromWidget(attempt + 1) },
                150L
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::playerSceneController.isInitialized) return
        val currentScene = playerSceneController.currentScene
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            currentScene == PlayerSceneController.Scene.PLAYER
        ) {
            mainHandler.post { launchLandscapePlayerFromSystemRotation() }
            return
        }
        if (currentScene == PlayerSceneController.Scene.PLAYER ||
            currentScene == PlayerSceneController.Scene.LYRIC) {
            mainHandler.post {
                setupSceneParams()
                updateHiresBadge()
                if (currentScene == PlayerSceneController.Scene.LYRIC) {
                    registerCoverLyricParams()
                }
                playerSceneController.switchToSceneSilent(currentScene)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("prePlayerWasInFragmentMode", prePlayerWasInFragmentMode)
        prePlayerFragmentDest?.let { outState.putInt("prePlayerFragmentDest", it) }
        outState.putInt("legacyDestinationId", legacyDestinationId)
        prePlayerContainerScene?.let { outState.putString("prePlayerContainerScene", it.name) }
    }

    private val dialogHelper by lazy {
        DialogHelper { visible -> updateComposeRootVisibility(visible) }
    }
    private val metadataCardPopupHelper by lazy {
        MetadataCardPopupHelper(
            resources = resources,
            onVisibilityChanged = { visible -> updateComposeRootVisibility(visible) }
        )
    }
    private val startupScanHelper by lazy { StartupScanHelper(this) }
    private val themeCoordinator: ThemeCoordinator by lazy {
        ThemeCoordinator(
            context = this,
            applyDefaultBackground = { applyDefaultBackground() },
            syncImmersiveBackgroundSettings = { syncImmersiveBackgroundSettings() },
            refreshImmersiveState = {
                if (::playerSceneController.isInitialized) {
                    playerSceneController.refreshImmersiveState(com.rawsmusic.module.data.prefs.AppPreferences.UI.isImmersiveEnabled)
                }
            },
            updateHiresBadge = { updateHiresBadge() },
            reapplyScene = {
                if (::playerSceneController.isInitialized) {
                    playerSceneController.forceReapplyCurrentScene()
                }
            }
        )
    }
    private val scannerCoordinator: ScannerCoordinator by lazy {
        ScannerCoordinator(
            context = this,
            isActivityAlive = { !isFinishing && !isDestroyed },
            startupScanHelper = startupScanHelper
        )
    }
    private val systemBarsHelper by lazy { SystemBarsHelper(this) }
    private val audioPermissionHelper by lazy { AudioPermissionHelper(this) }
    private val playerServiceBridgeHelper by lazy {
        PlayerServiceBridgeHelper(
            this,
            { playerController },
            { song: AudioFile -> coverUriResolver.resolveCoverUri(song) }
        )
    }
    private val lyricStyleHelper by lazy {
        LyricStyleHelper({ lyricBackgroundState.isLightBackground }) { playerController }
    }
    private val lyricsPublisher by lazy {
        LyricsPublisher(
            getCurrentPositionMs = { playerController?.position?.value ?: 0L },
            isPlaying = { playerController?.playState?.value == PlayState.PLAYING },
            pushServiceLyrics = { playerServiceBridgeHelper.pushLyricsUpdate() }
        )
    }
    private val lyricLoadHelper by lazy {
        LyricLoadHelper(
            this,
            lifecycleScope,
            { enabled -> if (::playerSceneController.isInitialized) playerSceneController.lyricEnabled = enabled },
            { playerController?.currentSong?.value },
            { song, data ->
                currentLyricDataSongKey = lyricSongKey(song)
                setCurrentLyricDataForCompose(data)
            },
            { _ -> /* mini lyric removed */ },
            { currentLyricText = "" },
            { },
            { applyLyricColors() },
            lyricsPublisher
        )
    }

    private val miniPlayerCoordinator by lazy {
        MiniPlayerCoordinator(
            resolveCover = { song -> coverCoordinator.resolve(song) },
            noMusicText = { getString(R.string.no_music_playing) }
        )
    }

    private val lyricsCoordinator by lazy {
        LyricsCoordinator(
            context = this,
            lifecycleScope = lifecycleScope,
            getController = { playerController },
            onLyricEnabledChanged = { enabled ->
                if (::playerSceneController.isInitialized) playerSceneController.lyricEnabled = enabled
            },
            onApplyLyricColors = { applyLyricColors() },
            onCapsuleTextNeedRefresh = {
                mainHandler.post {
                    audioInfoCapsuleHelper.updateText()
                    updateHiresBadge()
                }
            },
            onLyricsPrepared = { song, data, preparedSong ->
                val playingSong = playerController?.currentSong?.value
                if (playingSong != null && lyricSongKey(playingSong) == lyricSongKey(song)) {
                    currentLyricData = data
                    currentLyricDataSongKey = lyricSongKey(song)
                    composeLyricSong = preparedSong
                }
            },
            onLyricsInvalidated = { song ->
                currentLyricData = LyricData()
                currentLyricDataSongKey = lyricSongKey(song)
                composeLyricSong = null
                composeLyricPositionMs = 0L
            },
            serviceBridge = playerServiceBridgeHelper
        )
    }

    private val coverCoordinator by lazy {
        CoverCoordinator(
            context = this,
            lifecycleOwner = this,
            getCurrentSong = { playerController?.currentSong?.value },
            onMiniPlayerCoverNeedRefresh = { updateMiniPlayerBarSong() },
            onMirrorCoverChanged = { uri -> syncMirrorCover(uri) }
        )
    }

    private val playbackCoordinator by lazy {
        PlaybackCoordinator(
            scope = lifecycleScope,
            sceneController = { if (::playerSceneController.isInitialized) playerSceneController else null },
            miniPlayer = miniPlayerCoordinator,
            lyrics = lyricsCoordinator,
            playerServiceBridgeHelper = playerServiceBridgeHelper,
            onCurrentSongChangedExtra = { song ->
                // LyricsCoordinator keeps the previous immutable lyric tree attached/frozen until
                // the replacement generation is fully prepared, then updates this legacy mirror
                // atomically from onLyricsPrepared. Clearing it here caused a second Compose tree
                // teardown at the tail of the artwork settle.
                coverCoordinator.onCurrentSongChanged(song)
                coverBackgroundManager.loadCoverBackground(song.albumArtPath)
                audioInfoCapsuleHelper.updateText()
                audioInfoCapsuleHelper.updateHiresBadge(
                    isTransitioning = ::playerSceneController.isInitialized && playerSceneController.isTransitioning,
                    isPlayerScene = ::playerSceneController.isInitialized && playerSceneController.currentScene == com.rawsmusic.core.ui.widget.PlayerSceneController.Scene.PLAYER
                )
                if (::playerSceneController.isInitialized) {
                    playerSceneController.syncRotationState(
                        playerController?.playState?.value == PlayState.PLAYING
                    )
                }
            },
            onPositionChangedExtra = { pos, duration ->
                // Runtime backend can change after route/native fallback without a song change.
                // This callback is deliberately low-rate in PlaybackCoordinator; playback-chain
                // chrome does not need to run on the 20 Hz timeline clock.
                audioInfoCapsuleHelper.updatePlaybackChainText()
            },
            isPlayerUiVisible = { isPlayerUiVisibleForPower() },
            context = this
        )
    }
    private val playbackObserverCoordinator by lazy {
        MainActivityPlaybackObserverCoordinator(
            scope = lifecycleScope,
            controller = { playerController },
            onPlaybackState = playbackCoordinator::onPlaybackStateChanged,
            onCurrentSong = playbackCoordinator::onCurrentSongChanged,
            onPosition = ::handleObservedPlaybackPosition,
            onSampleRateChanged = { sampleRate ->
                if (sampleRate > 0) updateCapsuleText()
            },
            onPlayModeChanged = playModePopupHelper::updatePlayModeIcon,
        )
    }
    private val intentCoordinator by lazy {
        MainActivityIntentCoordinator(
            scope = lifecycleScope,
            mainHandler = mainHandler,
            isFinishing = { isFinishing },
            isDestroyed = { isDestroyed },
            sceneController = {
                if (::playerSceneController.isInitialized) playerSceneController else null
            },
            mainNavigation = { mainNavState },
            ensureController = ::ensureRuntimeController,
            openPlayerPage = { openPlayPageWithSharedElement() },
            updateRootVisibility = ::updateComposeRootVisibility,
            primePlayerUi = ::primePlayerUi,
            openQueuePage = ::openQueuePage,
            openPlaylistPicker = { openPlaylistPickerFromWidget() },
            showNoSongs = {
                AppNoticeBus.error(getString(R.string.no_songs_found))
            },
        )
    }
    private val usbIntentCoordinator by lazy {
        MainActivityUsbIntentCoordinator(
            packageName = packageName,
            packageManager = packageManager,
            activity = this,
            controller = { playerController },
        )
    }
    private val searchStateHelper by lazy {
        SearchStateHelper()
    }
    private val lastPlayingStateHelper by lazy {
        LastPlayingStateHelper(
            { playerController },
            coverUriResolver::resolveCoverUri,
            ::syncMirrorCover,
            {}
        )
    }
    private val playerActionObserverHelper by lazy {
        PlayerActionObserverHelper(
            lifecycleScope,
            { playerController },
            { lyricsNeedSeekTo = true }
        )
    }
    private val playerControllerBindingHelper by lazy {
        PlayerControllerBindingHelper { controller ->
            playerController = controller
            playbackObserverCoordinator.start(controller)
        }
    }
    private val playbackQueueHelper by lazy {
        MainPlaybackQueueHelper { ensureRuntimeController("ui_play_queue") }
    }
    private val audioInfoCapsuleHelper by lazy {
        AudioInfoCapsuleHelper(
            this,
            { playerController },
            { visible -> updateComposeRootVisibility(visible) },
            { destinationId -> openDestinationFromPlayerPopup(destinationId) },
            { link -> openAudioInfoLink(link) }
        )
    }
    private val audioCapsuleUiHelper by lazy {
        AudioCapsuleUiHelper(
            { ::playerSceneController.isInitialized && playerSceneController.isTransitioning },
            { ::playerSceneController.isInitialized && playerSceneController.currentScene == PlayerSceneController.Scene.PLAYER },
            audioInfoCapsuleHelper,
            { lyricsCoordinator.currentLyricText }
        )
    }
    private val metadataEditorHelper: MetadataEditorHelper by lazy { MetadataEditorHelper(
        this, { playerController },
        { s: AudioFile -> coverUriResolver.resolveCoverUri(s) }, { uri -> syncMirrorCover(uri) },
        { songActionSheetHelper.hide() },
        { v -> songActionSheetHelper.hasCustomCover = v },
        { songActionSheetHelper.updateCoverRestoreButton() },
        { visible -> updateComposeRootVisibility(visible) },
        { song ->
            songDeletionCoordinator.delete(listOf(song)) { result ->
                if (result.deletedSongs.isNotEmpty()) {
                    playerController?.removeSongsFromQueue(result.deletedSongs)
                }
                if (result.deleted > 0) {
                    AppNoticeBus.post(
                        message = getString(R.string.song_delete_complete),
                        icon = AppNoticeIcon.AUDIO,
                    )
                } else if (result.cancelled) {
                    Toast.makeText(
                        this,
                        getString(R.string.song_delete_cancelled),
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    AppNoticeBus.error(getString(R.string.song_delete_failed))
                }
            }
        }
    ) }
    private val songActionSheetHelper: SongActionSheetHelper by lazy {
        SongActionSheetHelper(
            this,
            { playerController },
            { disabled -> if (::playerSceneController.isInitialized) playerSceneController.disableGestureIntercept = disabled },
            { if (::playerSceneController.isInitialized) playerSceneController.closePlayPage(false) },
            { s: AudioFile -> coverUriResolver.resolveCoverUri(s) },
            { lifecycleScope },
            { albumName, albumArtist, coverPath ->
                mainNavState.navigateTo(
                    com.rawsmusic.core.ui.scene.NavScene.ALBUM_DETAIL,
                    "$albumName|$albumArtist|$coverPath"
                )
            },
            { visible -> updateComposeRootVisibility(visible) }
        ).apply {
            onEditMetadata = { metadataDetailHelper.open() }
            onOpenMetadataDetail = { metadataDetailHelper.open() }
            onDeleteCurrentSong = { metadataEditorHelper.deleteCurrentSong() }
            onPickCoverImage = { metadataEditorHelper.pickCoverImage() }
            onRestoreCover = { metadataEditorHelper.restoreOriginalCover() }
        }
    }
    private val metadataDetailHelper by lazy {
        MetadataDetailHelper(
            this,
            { playerController },
            { lyricsCoordinator.lyricSong },
            { disabled -> if (::playerSceneController.isInitialized) playerSceneController.disableGestureIntercept = disabled },
            { metadataEditorHelper },
            { visible -> updateComposeRootVisibility(visible) }
        )
    }
    private val playModePopupHelper by lazy {
        PlayModePopupHelper(
            this,
            { playerController },
            { visible -> updateComposeRootVisibility(visible) }
        )
    }
    private fun initView() {
        val tInitStart = System.currentTimeMillis()
        AppLogger.d("Startup", "initView: start")

        // 在onCreate 中初始化 PlayerController 相关组件
        if (playerController == null) {
            ensureRuntimeController("main_activity_init_view")
        } else {
            PlayerHolder.controller = playerController
        }

        // 初始化封面 URI 解析器
        coverUriResolver = coverCoordinator.resolver

        setupMainComposeView()
        AppLogger.d("Startup", "initView: setupMainComposeView done in ${System.currentTimeMillis() - tInitStart}ms total")
        setupplayerSceneController()

        // 初始化封面背景管理器
        coverBackgroundManager = com.rawsmusic.helper.CoverBackgroundManager(
            lifecycleOwner = this,
            playBgView = playBackgroundState,
            lyricBgView = lyricBackgroundState,
            backgroundView = backgroundState,
            immersiveBackground = immersiveBackgroundState,
            mainPersistentCover = mainPersistentCoverState,
            layerState = backgroundLayerState,
            updateDefaultBackgroundEnabled = { enabled ->
                composeDefaultBackgroundEnabled = enabled
                if (::playerSceneController.isInitialized) playerSceneController.updateDefaultBackgroundEnabled(enabled)
                syncImmersiveBackgroundSettings()
            },
            updateImmersiveCover = {
                updateImmersiveCoverState(null)
            },
            applyLyricColors = { applyLyricColors() }
        )

        // 在 playerSceneController 初始化后同步默认背景状态；关闭状态也要走恢复分支，避免冷启动亮色主题白底残留。
        applyDefaultBackground()

        setupDrawerLayout()
        setupSideMenu()
        playerController?.setEqualizerController { }
        playerController?.let(::bindAudioVisualizerCallback)
        metadataDetailHelper.setup()
        metadataCardPopupHelper.onMetadataClick = {
            metadataDetailHelper.open()
        }
        songActionSheetHelper.setup()
        setupEdgeToEdge()

        // 使用StateFlow和SharedFlow来管理状态       observePlayerActions()

        LyriconProviderManager.onProviderConnected = {
            lyricsCoordinator.resendToLyricon()
        }
    }

    private fun initData() = Unit

    /**
     * DDrawerLayout 侧边栏设置...HOME场景时允许滑动手动打开侧边栏...深层页面时禁用侧边栏...    */
    private fun setupDrawerLayout() {
        // 侧边栏点击关闭已迁移到 RootContent 的 Compose pointer input。
    }

    private fun openSideMenu() {
        isSideMenuOpen = true
        sideRailOpenRequestToken++
    }

    /**
     * 历史 Fragment 模式已由 Compose 导航替代，保留空实现兼容旧恢复链路。
     */
    private fun switchToFragmentMode() {
        updateComposeRootVisibility(true)
    }

    /**
     * 播放页弹窗里的可点击音频信息入口。
     * 如果当前在播放器/歌词页，复用播放页返回 MAIN 的恢复链路，让目标设置页可见。
     */
    fun openDestinationFromPlayerPopup(destinationId: Int) {
        sceneNavigationCoordinator.openDestinationFromPlayerPopup(destinationId)
    }

    /**
     * Handles links rendered inside the audio information popup.
     *
     * The info window collapses independently and dispatches navigation through its message
     * bus. The player Activity remains alive underneath. Our equivalent is a shared window:
     * keep the player scene mounted, promote the Compose library stack above it, and hand the
     * window back to the player when the linked page returns to its origin.
     */
    private fun openAudioInfoLink(link: AudioInfoLink) {
        audioInfoLinkCoordinator.open(link)
    }

    private fun executeAudioInfoLink(link: AudioInfoLink) {
        audioInfoLinkCoordinator.open(link)
    }

    private fun openAudioInfoFile(path: String) {
        audioInfoLinkCoordinator.open(AudioInfoLink.OpenFile(path))
    }

    fun navigateSettingsForward(destinationId: Int) {
        sceneNavigationCoordinator.navigateSettingsForward(destinationId)
    }

    fun navigateSettingsBack() {
        sceneNavigationCoordinator.navigateSettingsBack()
    }

    private fun navigateToSettingsScene(scene: com.rawsmusic.core.ui.scene.NavScene) {
        sceneNavigationCoordinator.navigateToSettingsScene(scene)
    }

    /** Open audio effects through the same independent settings Activity as SettingsActivity. */
    private fun openAudioEffectsFromPlayer() {
        composePlayerModalDismissAction?.invoke()
        composePlayerModalDismissAction = null
        composePlayerModalVisible = false
        gestureLockCoordinator.set(GestureLockReason.PlayerModal, false)
        if (::playerSceneController.isInitialized) {
            // Audio effects is an Activity displayed above the player. Preserve the PLAYER scene
            // underneath so returning and then closing the player performs a real PLAYER -> MAIN
            // transition and restores bottom chrome normally.
            playerSceneController.disableGestureIntercept = false
        }
        launchSettingsActivity(com.rawsmusic.ui.settings.AudioEffectsActivity::class.java)
    }

    private fun settingsSceneForDestination(destinationId: Int): com.rawsmusic.core.ui.scene.NavScene {
        return MainActivityNavigationPolicy.settingsSceneForDestination(destinationId)
    }

    private fun legacyNavigateTo(destinationId: Int) {
        sceneNavigationCoordinator.legacyNavigateTo(destinationId)
    }

    private fun legacyNavigateUp(): Boolean {
        return sceneNavigationCoordinator.legacyNavigateUp()
    }

    private fun legacyPopToSongs(): Boolean {
        return sceneNavigationCoordinator.legacyPopToSongs()
    }

    private fun switchToContainerMode(targetScene: com.rawsmusic.core.ui.scene.NavScene? = null) {
        sceneNavigationCoordinator.switchToContainerMode(targetScene)
    }

    private fun prepareContainerForPlayerReturn() {
        sceneNavigationCoordinator.prepareContainerForPlayerReturn()
    }

    private fun closeSideMenu() {
        isSideMenuOpen = false
        sideRailCloseRequestToken++
    }

    private fun setupSideMenu() {
        // 已由 Compose SideMenuDrawer 替代，此函数保留为空
    }

    private fun updateDrawerLockMode() {
        if (!::playerSceneController.isInitialized) return
        val isHomeLevel = playerSceneController.currentScene == PlayerSceneController.Scene.MAIN
        val isDeepPage = mainNavState.isAtHome() != true
        val inFragmentMode = false
        playerSceneController.disableDeepPageSwipe = inFragmentMode
        playerSceneController.isDeepHomePage = isHomeLevel && isDeepPage && !inFragmentMode
    }

    /** 纯 Compose 主界面导航状态 */
    private val mainNavState = com.rawsmusic.core.ui.scene.NavigationState()
    private val predictiveBackCoordinator by lazy {
        MainActivityPredictiveBackCoordinator(
            activity = this,
            mainHandler = mainHandler,
            mainNavigation = mainNavState,
            playerSceneController = { if (::playerSceneController.isInitialized) playerSceneController else null },
            homeFullCoverOverlayActive = { homeFullCoverOverlayActive },
            audioInfoSharedWindowActive = { audioInfoSharedWindowActive },
            audioInfoPopupShowing = { audioInfoCapsuleHelper.isPopupShowing },
            metadataEditorShowing = { metadataEditorHelper.isMetadataEditorShowing },
            metadataDeleteConfirmShowing = { metadataEditorHelper.isDeleteConfirmShowing },
            metadataDetailVisible = { metadataDetailHelper.isVisible },
            songActionSheetShowing = { songActionSheetHelper.isSongActionSheetShowing },
            playlistPickerShowing = { songActionSheetHelper.isPlaylistPickerShowing },
            playModePopupShowing = { playModePopupHelper.isShowing },
            metadataCardPopupShowing = { metadataCardPopupHelper.isShowing },
            composePlayerModalVisible = { composePlayerModalVisible },
            composePlayerModalDismissAction = { composePlayerModalDismissAction },
            dismissAudioInfoPopup = { audioInfoCapsuleHelper.dismissPopup() },
            closeMetadataDetail = { metadataDetailHelper.close() },
            hidePlayModePopup = { playModePopupHelper.hide() },
            onActivityBackFallback = {
                @Suppress("DEPRECATION")
                onBackPressed()
            },
        )
    }
    private val sceneGestureCoordinator by lazy {
        MainActivitySceneGestureCoordinator(
            mainNavigation = mainNavState,
            playerScene = playerSceneController,
            isProgressSeekActive = { progressSeekActive },
            isGestureBlocked = { gestureLockCoordinator.isBlocked },
            isAudioInfoSharedWindowActive = { audioInfoSharedWindowActive },
            isHorizontalGestureExcluded = { point ->
                miniPlayerGestureBoundsForSceneGesture?.contains(point) == true ||
                    bottomNavigationGestureBoundsForSceneGesture?.contains(point) == true
            },
        )
    }

    private fun setupMainComposeView() {
        observeMainContainerFlows()
    }

    @Composable
    private fun RootContent() {
        val libraryStartupState by MusicRepository.startupState.collectAsState()
        var startupRevealed by remember(libraryStartupState.generation) {
            mutableStateOf(false)
        }
        LaunchedEffect(libraryStartupState.generation, libraryStartupState.phase) {
            if (libraryStartupState.isReady) {
                // Reference keeps its startup scene visible for a short, stable window even when
                // Room returns immediately. This prevents a fast snapshot/Room handoff from
                // exposing a partially measured list or a second artwork placement.
                val elapsed = SystemClock.elapsedRealtime() - libraryStartupState.startedAtElapsedMs
                delay((STARTUP_SCENE_MIN_MS - elapsed).coerceAtLeast(0L))
                AppLogger.i(
                    "Startup",
                    "library reveal phase=${libraryStartupState.phase} songs=${libraryStartupState.songs} elapsed=${SystemClock.elapsedRealtime() - libraryStartupState.startedAtElapsedMs}ms"
                )
                startupRevealed = true
            } else {
                startupRevealed = false
            }
        }
        val startupSpinnerAlpha by animateFloatAsState(
            targetValue = if (startupRevealed) 0f else 1f,
            animationSpec = tween(
                durationMillis = STARTUP_REVEAL_MS,
                easing = CubicBezierEasing(0f, 0f, 0.2f, 1f),
            ),
            label = "library_startup_spinner",
        )
        val themeKey = com.rawsmusic.core.ui.theme.RawThemeRuntimeState.version
        val packageInfo = remember { packageManager.getPackageInfo(packageName, 0) }
        val installedVersionCode = remember(packageInfo) {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
        }
        val installedVersionName = remember(packageInfo) { packageInfo.versionName.orEmpty() }
        var showUpdateNotes by remember(installedVersionCode, installedVersionName) {
            mutableStateOf(
                AppPreferences.UI.lastUpdateNotesVersionCode != installedVersionCode ||
                    AppPreferences.UI.lastUpdateNotesVersionName != installedVersionName
            )
        }
        // Do not override LocalNavigationEventDispatcherOwner here. Activity content and each
        // platform Dialog must resolve the owner from their own ViewTree so the topmost window
        // receives predictive-back progress on its own dispatcher.
        // Read the registration inputs in composition, then apply the callback state immediately
        // after the successful frame. A MIUIX popup must disable the scene callback before the next
        // edge gesture so its own NavigationBackHandler receives the full gesture.
        val activeMiuixOverlayCount = com.rawsmusic.core.ui.widget.MiuixOverlayBackRuntime.activeCount
        val activeSourcePortalBackCount = com.rawsmusic.core.ui.scene.pages.SourcePortalBackRuntime.activeCount
        SideEffect {
            updatePredictiveBackRegistration(
                activeMiuixOverlayCount = activeMiuixOverlayCount,
                activeSourcePortalBackCount = activeSourcePortalBackCount,
            )
        }
        LaunchedEffect(
            audioInfoSharedWindowActive,
            mainNavState.currentScene,
            mainNavState.isTransitioning,
            mainNavState.isDraggingBack,
            mainNavState.isAnimatingBack,
        ) {
            val origin = audioInfoSharedWindowOrigin
            if (audioInfoSharedWindowActive &&
                origin != null &&
                mainNavState.currentScene == origin &&
                !mainNavState.isTransitioning &&
                !mainNavState.isDraggingBack &&
                !mainNavState.isAnimatingBack
            ) {
                AppLogger.i("AudioInfoLink", "close_shared_window origin=$origin")
                audioInfoSharedWindowActive = false
                audioInfoSharedWindowOrigin = null
                updatePredictiveBackRegistration()
            }
        }
        RawSMusicTheme(key = themeKey) {
            val rootColor = MiuixTheme.colorScheme.background
            top.yukonga.miuix.kmp.basic.Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = rootColor,
                contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0)
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(rootColor)
                        .sceneGestureInput()
                ) {
                    // Keep the real app scene mounted while the library is warming up. Reference
                    // owns its scan indicator inside the list scene; it does not replace the
                    // activity with an opaque loading page. This also keeps the background,
                    // header, navigation and their measured anchors stable for the first frame.
                    BackgroundLayers()
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (audioInfoSharedWindowActive) 2f else 0f)
                    ) {
                        MainComposeContent(
                            libraryStartupLoading = !startupRevealed || startupSpinnerAlpha > 0.001f,
                            libraryStartupLoadingAlpha = startupSpinnerAlpha,
                        )
                    }
                    val homeFullCoverHostPolicy =
                        resolveHomeFullCoverActivityHostPolicy(homeFullCoverOverlayActive)
                    val normalPlayerSheetDormant = usesNormalPlayerSheet() &&
                        ::playerSceneController.isInitialized &&
                        playerSceneController.composeCurrentScene == PlayerSceneController.Scene.MAIN &&
                        !playerSceneController.composeIsTransitioning
                    // Keep one stable player-overlay composition across the complete portrait-dial
                    // round trip. Removing this subtree on open and recreating it on close changes
                    // the Activity root scene in the same frames that own the shared artwork,
                    // allowing stale player transition state and window-layer placement to leak
                    // into the home/full-cover geometry. Only visual ownership changes here.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(
                                if (normalPlayerSheetDormant) -1f
                                else homeFullCoverHostPolicy.playerOverlayZIndex
                            )
                            .graphicsLayer {
                                alpha = homeFullCoverHostPolicy.playerOverlayAlpha
                            },
                    ) {
                        PlayerOverlayContent()
                    }
                    // DAC volume feedback belongs to the Activity, including when the
                    // player sheet is parked behind HOME or the full-cover scene.
                    UsbVolumeOverlay(
                        visible = overlayCoordinator.isUsbVolumeOverlayVisible,
                        text = overlayCoordinator.usbVolumeOverlayText,
                        modifier = Modifier.align(Alignment.TopCenter).zIndex(100f),
                    )
                    // Playlist picking is an Activity-level modal, not part of the visual player
                    // sheet. Keeping it inside PlayerOverlayContent made it inherit the dormant
                    // MAIN player host's zIndex=-1 / alpha policy, so the state changed while the
                    // picker itself was rendered behind the library.
                    SongActionSheetOverlay(
                        helper = songActionSheetHelper,
                        isImmersiveEnabled = composeImmersiveEnabled,
                        modifier = Modifier.fillMaxSize(),
                    )
                    AiLyricTimingPreviewDialog(
                        show = showAiTimingPreview,
                        song = aiTimingPreviewSong,
                        lyrics = currentLyricData,
                        lyricsSongKey = currentLyricDataSongKey,
                        onDismiss = {
                            showAiTimingPreview = false
                            aiTimingPreviewSong = null
                        },
                        onApplied = { corrected ->
                            setCurrentLyricDataForCompose(corrected)
                            val appliedSong = aiTimingPreviewSong
                            val playingSong = playerController?.currentSong?.value
                            if (
                                appliedSong != null &&
                                playingSong != null &&
                                lyricSongKey(appliedSong) == lyricSongKey(playingSong)
                            ) {
                                // The player and lyric page render LyricsCoordinator.lyricSong,
                                // not only MainActivity's legacy mirror above. Re-read the accepted
                                // sidecar so every lyric surface switches to the persisted timeline.
                                lyricsCoordinator.loadLyricsForSong(appliedSong)
                            }
                        }
                    )
                    if (showUpdateNotes) {
                        UpdateNotesDialog(versionName = installedVersionName) {
                            AppPreferences.UI.lastUpdateNotesVersionCode = installedVersionCode
                            AppPreferences.UI.lastUpdateNotesVersionName = installedVersionName
                            showUpdateNotes = false
                        }
                    }
                }
            }
        }
    }

    private fun Modifier.sceneGestureInput(): Modifier = sceneGestureCoordinator.install(this)

    @Composable
    private fun BackgroundLayers() {
        MainBackgroundLayers()
    }

    private fun startLibraryMetadataMatch(
        songs: List<AudioFile>,
        mode: LibraryMetadataMatchMode,
    ) = libraryMetadataCoordinator.start(songs, mode)

    /**
     * Keeps the library scene on its last settled playback snapshot while the player owns the
     * foreground. The player overlay has its own live queue collector; recomposing the complete
     * library, carousel and collapsed bar behind it during a 550 ms artwork settle only competes
     * for the same Main/RenderThread frame and cannot produce a visible result.
     */
    @Composable
    private fun rememberMainSurfacePlaybackQueue(): com.rawsmusic.core.common.model.PlayQueue {
        val controller = playerController
        val initial = controller?.visibleQueue?.value
            ?: com.rawsmusic.core.common.model.PlayQueue()
        val queue by produceState(initialValue = initial, controller) {
            val boundController = controller ?: return@produceState
            var latest = boundController.visibleQueue.value
            var playerIdleCatchupJob: kotlinx.coroutines.Job? = null

            fun mainSurfaceCanPublishImmediately(): Boolean =
                playerSceneState.currentScene == com.rawsmusic.core.ui.widget.PlayerScene.MAIN &&
                    (!::playerSceneController.isInitialized ||
                        !playerSceneController.composeIsTransitioning)

            fun playerWasOpenedFromHome(): Boolean =
                prePlayerContainerScene == com.rawsmusic.core.ui.scene.NavScene.HOME

            fun playerSurfaceCanCatchUpWhileHidden(): Boolean =
                playerWasOpenedFromHome() &&
                    playerSceneState.currentScene == com.rawsmusic.core.ui.widget.PlayerScene.PLAYER &&
                    ::playerSceneController.isInitialized &&
                    !playerSceneController.composeIsTransitioning

            fun publishLatest() {
                if (value != latest) value = latest
            }

            fun scheduleHiddenPlayerCatchup() {
                playerIdleCatchupJob?.cancel()
                if (!playerSurfaceCanCatchUpWhileHidden()) return
                playerIdleCatchupJob = launch {
                    kotlinx.coroutines.delay(620L)
                    if (playerSurfaceCanCatchUpWhileHidden()) {
                        // The foreground artwork settle has had time to finish. Pre-commit the
                        // retained MAIN playback identity now so HOME carousel/current-row state
                        // is already correct before a later PLAYER -> MAIN collapse begins.
                        publishLatest()
                    }
                }
            }

            val queueJob = launch {
                boundController.visibleQueue.collect { next ->
                    latest = next
                    when {
                        mainSurfaceCanPublishImmediately() -> {
                            playerIdleCatchupJob?.cancel()
                            publishLatest()
                        }
                        playerSurfaceCanCatchUpWhileHidden() -> scheduleHiddenPlayerCatchup()
                    }
                }
            }
            try {
                snapshotFlow {
                    Triple(
                        playerSceneState.currentScene,
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.composeIsTransitioning
                        } else {
                            false
                        },
                        latest,
                    )
                }.collect { (scene, transitioning, _) ->
                    when {
                        scene == com.rawsmusic.core.ui.widget.PlayerScene.MAIN && !transitioning -> {
                            playerIdleCatchupJob?.cancel()
                            publishLatest()
                        }
                        scene == com.rawsmusic.core.ui.widget.PlayerScene.PLAYER && !transitioning -> {
                            scheduleHiddenPlayerCatchup()
                        }
                        scene == com.rawsmusic.core.ui.widget.PlayerScene.PLAYER &&
                            transitioning &&
                            playerWasOpenedFromHome() -> {
                            // During a return gesture the committed scene remains PLAYER until the
                            // sheet reaches MAIN. If a user returns immediately after a switch,
                            // publish the newest hidden MAIN snapshot at the *start* of that
                            // transition rather than after the player is fully closed.
                            playerIdleCatchupJob?.cancel()
                            publishLatest()
                        }
                    }
                }
            } finally {
                playerIdleCatchupJob?.cancel()
                queueJob.cancel()
            }
        }
        return queue
    }

    @Composable
    private fun MainComposeContent(
        libraryStartupLoading: Boolean,
        libraryStartupLoadingAlpha: Float,
    ) {
        val songs by MusicRepository.songs.collectAsState()
        val folderHierarchy by MusicRepository.folderHierarchy.collectAsState()
        val playbackStats by PlaybackStatsStore.getInstance(this).stats.collectAsState()
        val playbackQueue = rememberMainSurfacePlaybackQueue()
        // MiniPlayerCoordinator publishes position and duration as the same throttled timeline
        // snapshot. Reading controller.position.value beside a separately collected duration can
        // combine two songs during a switch and render a mathematically invalid progress value.
        val playbackPositionMs = miniPlayerCoordinator.positionMs
        val playbackDurationMs = miniPlayerCoordinator.durationMs
        // Use the controller's real next-track resolver. It includes the priority queue,
        // shuffle reservation, and repeat-one behavior used by the actual transition.
        val miniPlayerPreviousSong = playerController?.previewPreviousSong(playbackQueue)
        val miniPlayerNextSong = playerController?.previewNextSong(playbackQueue)
        val carouselRelativeSongs = (-3..3).map { offset ->
            playerController?.previewRelativeSong(playbackQueue, offset)
        }
        val nextSongTitle = miniPlayerNextSong?.displayName.orEmpty()
        // visibleQueue already folds an in-flight transport request into this immutable snapshot.
        // Re-applying requestedSongForUi here creates a second independently published cursor and
        // lets the retiring song win one composition between two target frames.
        // The queue snapshot is the atomic UI owner for song identity. Reading currentSong as a
        // second observable made one renderer commit invalidate this large library composition
        // twice. The direct value is only a cold-start fallback until the restored queue arrives.
        val canonicalCurrentSong = playbackQueue.currentSong ?: playerController?.currentSong?.value
        val homeCarouselQueue = remember(
            playbackQueue,
            canonicalCurrentSong,
            miniPlayerPreviousSong,
            miniPlayerNextSong,
            carouselRelativeSongs,
        ) {
            buildHomeCarouselQueueProjection(
                queue = playbackQueue,
                currentSong = canonicalCurrentSong,
                previousSong = miniPlayerPreviousSong,
                nextSong = miniPlayerNextSong,
                relativeSongs = carouselRelativeSongs,
            )
        }
        // 监听扫描状态（首次运行不再自动弹文件夹选择器，直接进入歌曲列表触发 MediaStore 扫描）
        val scanStatus by com.rawsmusic.module.scanner.ScanStateBus.status.collectAsState()
        androidx.compose.runtime.LaunchedEffect(scanStatus.state, scanStatus.timeMs, scanStatus.progress) {
            when (scanStatus.state) {
                com.rawsmusic.module.scanner.ScanStateBus.ScanState.FOLDER_SELECTION_NEEDED -> {
                    overlayCoordinator.setFolderDialogVisible(true)
                }
                com.rawsmusic.module.scanner.ScanStateBus.ScanState.COMPLETED -> {
                    com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider.notifyLibraryArtworkChanged("scan_complete")
                }
                else -> Unit
            }
        }
        // 首次启动检查：首次授权后如果仍是空库且没有扫描目录，直接弹出文件夹过滤弹窗。
        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(900) // 等待权限回调 / Compose overlay 初始化
            val scanPaths = com.rawsmusic.module.data.prefs.AppPreferences.UI.scanPaths
            val dbEmpty = withContext(Dispatchers.IO) { MusicRepository.getAllSongsSuspend().isEmpty() }
            if (scanPaths.isEmpty() && dbEmpty && !overlayCoordinator.showFolderDialog) {
                AppLogger.i("Startup", "Empty library on first launch — showing folder filter dialog")
                overlayCoordinator.setFolderDialogVisible(true)
            }
        }

        var songsSelectionMode by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        val metadataMatchProgress by LibraryMetadataMatchProgressBus.state.collectAsState()
        var lastMetadataMatchCompletionToken by rememberSaveable { mutableLongStateOf(0L) }
        LaunchedEffect(metadataMatchProgress.completionToken, metadataMatchProgress.phase) {
            if (metadataMatchProgress.phase == LibraryMetadataMatchPhase.COMPLETED &&
                metadataMatchProgress.completionToken > lastMetadataMatchCompletionToken
            ) {
                lastMetadataMatchCompletionToken = metadataMatchProgress.completionToken
                val message = getString(
                    R.string.metadata_match_result,
                    metadataMatchProgress.total,
                    metadataMatchProgress.succeeded,
                    metadataMatchProgress.failed
                )
                if (metadataMatchProgress.failed > 0) {
                    AppNoticeBus.error(message)
                } else {
                    AppNoticeBus.post(
                        message = message,
                        icon = AppNoticeIcon.METADATA,
                    )
                }
            }
        }
        val metadataMatchProgressText = if (metadataMatchProgress.isRunning) {
            stringResource(
                R.string.metadata_match_running,
                metadataMatchProgress.processed,
                metadataMatchProgress.remaining
            )
        } else {
            ""
        }
        var metadataMatchSources by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<List<com.rawsmusic.core.ui.scene.pages.MetadataMatchSourceUi>>(emptyList())
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            metadataMatchSources = withContext(Dispatchers.IO) {
                LyricoPluginStore.get(this@MainActivity).enabledInPreferredOrder().map { plugin ->
                    com.rawsmusic.core.ui.scene.pages.MetadataMatchSourceUi(
                        id = plugin.manifest.id,
                        name = plugin.manifest.name.ifBlank { plugin.manifest.id },
                    )
                }
            }
        }

        val callbacks = com.rawsmusic.core.ui.scene.NavCallbacks(
            onSongClick = { song, _ ->
                primePlayerUi(song)
                playbackQueueHelper.playSongFromScene(song, mainNavState.currentScene)
            },
            // Generic song lists do not own SONGS' local multi-select sheet. Give them one
            // canonical path into PlaylistStore instead of leaving long-press as a dead callback.
            onSongLongClick = { song, _ ->
                songActionSheetHelper.showPlaylistPickerForSongs(listOf(song))
            },
            onAlbumClick = { mainNavState.navigateTo(com.rawsmusic.core.ui.scene.NavScene.ALBUMS) },
            onAlbumItemClick = { album ->
                val arg = "${album.name}|${album.artist}|${album.coverPath}"
                mainNavState.navigateTo(com.rawsmusic.core.ui.scene.NavScene.ALBUM_DETAIL, arg)
            },
            onArtistClick = { artist -> mainNavState.navigateTo(com.rawsmusic.core.ui.scene.NavScene.ARTISTS) },
            onPlayQueue = { songs, idx ->
                primePlayerUi(songs[idx])
                playbackQueueHelper.playQueue(songs, idx)
            },
            onPlaylistClick = {},
            onFolderClick = {},
            onFolderHierarchyClick = {},
            onHomeCarouselSongClick = { carouselSongs, song, index ->
                if (carouselSongs.isNotEmpty()) {
                    val resolvedIndex = carouselSongs.indexOfFirst { candidate ->
                        candidate.path == song.path &&
                            candidate.cueOffsetMs == song.cueOffsetMs &&
                            candidate.cueTrackIndex == song.cueTrackIndex
                    }.takeIf { it >= 0 }
                        ?: index.coerceIn(0, carouselSongs.lastIndex)
                    AppLogger.i(
                        "HOME_CAROUSEL_TRACE",
                        "dispatch requested=$index resolved=$resolvedIndex " +
                            "title=${song.title} path=${song.path} queueSize=${carouselSongs.size}"
                    )
                    dispatchPlayerTransportAction<Unit>("home_carousel_select") { controller ->
                        val activeQueue = controller.visibleQueue.value
                        val activeIndex = activeQueue.songs.indexOfFirst { candidate ->
                            candidate.path == song.path &&
                                candidate.cueOffsetMs == song.cueOffsetMs &&
                                candidate.cueTrackIndex == song.cueTrackIndex
                        }
                        if (activeIndex >= 0) {
                            controller.selectVisibleQueueIndex(
                                activeIndex,
                                reason = "home_carousel_select",
                            )
                        } else {
                            // Never rebuild the transport queue from a stale carousel callback.
                            // The next state emission will rebind the carousel to the canonical
                            // queue; rebuilding here is what caused the previous-cover flash.
                            AppLogger.w(
                                "HOME_CAROUSEL_TRACE",
                                "ignored_stale_selection resolved=$resolvedIndex " +
                                    "activeSize=${activeQueue.songs.size}",
                            )
                        }
                        Unit
                    }
                }
            },
            onHomeCarouselNavigate = { direction ->
                dispatchPlayerTransportAction<Unit>("home_carousel_navigate") { controller ->
                    when {
                        direction > 0 -> controller.next()
                        direction < 0 -> controller.previousTrackWithoutRestart()
                    }
                    Unit
                }
            },
            onQueueSongClick = { song, _ ->
                primePlayerUi(song)
                playbackQueueHelper.playSongFromScene(song, mainNavState.currentScene)
            },
            onPlayerSeek = { positionMs ->
                playerController?.seekTo(positionMs)
            },
            onRecentlyAddedClick = { song, _ ->
                primePlayerUi(song)
                playbackQueueHelper.playSongFromScene(song, mainNavState.currentScene)
            },
            onPlayAll = { songs ->
                songs.firstOrNull()?.let { first ->
                    primePlayerUi(first)
                    playbackQueueHelper.playQueue(songs, 0)
                    openPlayPageWithSharedElement()
                }
            },
            onShuffleAll = { songs ->
                val controller = playerController
                val shuffledSongs = controller?.planShuffleSession(
                    songs,
                    shuffleCategoryGroups(mainNavState.currentScene, songs),
                ).orEmpty()
                shuffledSongs.firstOrNull()?.let { first ->
                    primePlayerUi(first)
                    controller?.playPlannedShuffleQueue(shuffledSongs)
                    openPlayPageWithSharedElement()
                }
            },
            onSearchClick = { scope ->
                mainNavState.navigateTo(
                    com.rawsmusic.core.ui.scene.NavScene.SEARCH,
                    scope?.token.orEmpty()
                )
            },
            onNavigateToPlayer = {
                if (playerController?.currentOrRequestedSongForUi() != null) {
                    // This action originates from the MiniPlayer. Always interpolate the
                    // player artwork from the live MiniPlayer CardView, even with stacked bottom
                    // navigation present; never borrow a list-cell/fixed region for this entry.
                    openPlayPageWithSharedElement(preferMiniPlayerSource = true)
                } else moveTaskToBack(true)
            },
            onMiniPlayerPlayPause = {
                dispatchPlayerTransportAction("mini_play_pause") { it.playPause() }
            },
            onMiniPlayerPrevious = {
                dispatchPlayerTransportAction("mini_previous") { it.previous() }
            },
            onMiniPlayerNext = {
                dispatchPlayerTransportAction("mini_next") { it.next() }
            },
            onMiniPlayerExpandDragStart = {
                preparePlayPageForBottomSheetDrag()
                playerSceneController.startMainToPlayerDrag()
                updatePredictiveBackRegistration()
            },
            onMiniPlayerExpandDragProgress = { ratio ->
                playerSceneController.updateMainToPlayerDrag(ratio)
            },
            onMiniPlayerExpandDragEnd = { shouldOpen, velocity ->
                playerSceneController.endMainToPlayerDrag(shouldOpen, velocity)
            },
            onOpenFolderPicker = { overlayCoordinator.showFolderDialog = true },
            onSortClick = {},
            onSongSortSelected = { order ->
                AppPreferences.Sort.songSortOrder = order
                lifecycleScope.launch(Dispatchers.IO) {
                    MusicRepository.refreshSongsOnlySuspend(invalidate = true)
                }
            },
            onSelectionAddToPlaylist = { selected ->
                songActionSheetHelper.showPlaylistPickerForSongs(selected)
            },
            onSelectionAddToQueue = { selected ->
                selected.forEach { song -> playerController?.addToQueue(song) }
                AppNoticeBus.post(
                    message = getString(R.string.ui_queue_added),
                    icon = AppNoticeIcon.QUEUE,
                )
            },
            onSelectionDelete = { selected ->
                songDeletionCoordinator.delete(selected) { result ->
                    if (result.deletedSongs.isNotEmpty()) {
                        playerController?.removeSongsFromQueue(result.deletedSongs)
                    }
                    val message = when {
                        result.cancelled && result.deleted == 0 -> getString(R.string.ui_delete_cancelled)
                        result.failed > 0 -> getString(R.string.ui_delete_result_failed, result.deleted, result.failed)
                        else -> getString(R.string.ui_delete_result, result.deleted)
                    }
                    if (result.failed > 0) {
                        AppNoticeBus.error(message)
                    } else if (result.cancelled) {
                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                    } else {
                        AppNoticeBus.post(
                            message = message,
                            icon = AppNoticeIcon.AUDIO,
                        )
                    }
                }
            },
            onSelectionPlayNext = { selected ->
                selected.asReversed().forEach { song -> playerController?.playNext(song) }
                AppNoticeBus.post(
                    message = getString(R.string.ui_play_next_added),
                    icon = AppNoticeIcon.PLAY_NEXT,
                )
            },
            onSelectionTranscode = { selected ->
                startActivity(
                    com.rawsmusic.ui.transcode.AudioTranscodeActivity.createIntent(
                        this@MainActivity,
                        selected,
                    )
                )
            },
            onSelectionBatchMatchLyrics = { selected ->
                startLibraryMetadataMatch(selected, LibraryMetadataMatchMode.LYRICS_ONLY)
            },
            onSelectionAutoMatch = { selected ->
                startLibraryMetadataMatch(selected, LibraryMetadataMatchMode.FILL_MISSING)
            },
            onSelectionClearQueue = {
                val queueSongs = playerController?.visibleQueue?.value?.songs.orEmpty()
                if (queueSongs.isNotEmpty()) {
                    playerController?.removeSongsFromQueue(queueSongs)
                }
            },
            onMoveMetadataSource = { sourceId, direction ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val store = LyricoPluginStore.get(this@MainActivity)
                    store.movePreferred(sourceId, direction)
                    val updated = store.enabledInPreferredOrder().map { plugin ->
                        com.rawsmusic.core.ui.scene.pages.MetadataMatchSourceUi(
                            id = plugin.manifest.id,
                            name = plugin.manifest.name.ifBlank { plugin.manifest.id },
                        )
                    }
                    withContext(Dispatchers.Main) { metadataMatchSources = updated }
                }
            },
            onAutoMatchCurrent = {
                val song = canonicalCurrentSong
                if (song == null) {
                    AppNoticeBus.error(getString(R.string.ui_no_current_song))
                } else {
                    startLibraryMetadataMatch(listOf(song), LibraryMetadataMatchMode.MATCH_CURRENT)
                }
            },
            onAutoRematchAll = {
                startLibraryMetadataMatch(songs, LibraryMetadataMatchMode.REMATCH_ALL)
            },
            onSongsSelectionModeChanged = { active ->
                songsSelectionMode = active
            },
            // Scan settings (including artist/genre split rules) live in this host as well as the
            // standalone settings Activity. Re-run the scanner here so the persisted rule is
            // followed by a fresh repository/index publication instead of becoming a no-op.
            onSongsRefresh = { startupScanHelper.scanLibrary() },
            onPlayingCoverBoundsChanged = { rect ->
                rect?.let {
                    val copy = android.graphics.RectF(it)
                    playingCoverBoundsForTransition = copy
                    if (
                        playerSceneController.currentScene == PlayerSceneController.Scene.MAIN &&
                        !floatingReturnOwnsMiniPlayerTarget &&
                        !usesNormalPlayerSheet() &&
                        !usesFloatingPlayerBar()
                    ) {
                        // ListCover shared geometry is only retained for legacy/non-floating
                        // routes. Floating and NORMAL MAIN<->PLAYER transitions are MiniPlayer-only.
                        lockedPlayerCoverBoundsForTransition = android.graphics.RectF(copy)
                    }
                }
            },
            onPlayingCoverTargetChanged = { target ->
                val current = playerController?.currentSong?.value
                val currentId = current?.id ?: -1L
                val currentCover = current?.let { resolveSongCoverForCompose(it) }.orEmpty()

                if (
                    target != null &&
                    target.isForSong(currentId, currentCover) &&
                    playerSceneController.currentScene == PlayerSceneController.Scene.MAIN &&
                    !floatingReturnOwnsMiniPlayerTarget &&
                    !usesNormalPlayerSheet() &&
                    !usesFloatingPlayerBar()
                ) {
                    val bounds = android.graphics.RectF(target.bounds)
                    playingCoverBoundsForTransition = bounds
                    lockedPlayerCoverBoundsForTransition = android.graphics.RectF(bounds)
                    coverTargetForTransition = target.copyBounds()
                }
            },
            onRevealCoverTargetResolved = { target ->
                val current = playerController?.currentSong?.value
                val currentId = current?.id ?: -1L
                val currentCover = current?.let { resolveSongCoverForCompose(it) }.orEmpty()

                if (
                    coldStartRevealAwaitingResolve &&
                    target != null &&
                    (target.songId == currentId || target.isForSong(currentId, currentCover))
                ) {
                    coldStartRevealAwaitingResolve = false
                    playerReturnRevealIndex = -1
                    AppLogger.d("Startup", "cold-start current-song reveal resolved")
                }

            },
            onMiniPlayerCoverBoundsChanged = { rect ->
                rect?.let {
                    miniPlayerCoverBoundsForTransition = android.graphics.RectF(it)
                }
            },
            onMiniPlayerCoverTargetChanged = { target ->
                miniPlayerCoverTargetForTransition = target?.copyBounds()
                target?.bounds?.let { bounds ->
                    miniPlayerCoverBoundsForTransition = android.graphics.RectF(bounds)
                }
                if (
                    target != null &&
                    playerSceneController.currentScene == PlayerSceneController.Scene.MAIN &&
                    !playerSceneController.composeIsTransitioning
                ) {
                    stableMiniPlayerCoverGeometryForTransition = target.copyBounds().copy(
                        source = CoverTransitionTarget.Source.MiniPlayer
                    )
                }
            }
        )

        val currentPlayingIndex = remember(
            songs,
            canonicalCurrentSong?.path,
            canonicalCurrentSong?.cueOffsetMs,
            canonicalCurrentSong?.cueTrackIndex,
        ) {
            songs.indexOfFirst {
                it.path == canonicalCurrentSong?.path &&
                    it.cueOffsetMs == canonicalCurrentSong.cueOffsetMs &&
                    it.cueTrackIndex == canonicalCurrentSong.cueTrackIndex
            }
        }
        val data = com.rawsmusic.core.ui.scene.NavData(
            songs = songs,
            folderHierarchy = folderHierarchy,
            currentPlayingIndex = currentPlayingIndex,
            currentSong = canonicalCurrentSong,
            miniPlayerSong = miniPlayerCoordinator.currentSong ?: canonicalCurrentSong,
            queueSongs = playbackQueue.songs,
            queueCurrentIndex = playbackQueue.currentIndex,
            homeCarouselSongs = homeCarouselQueue.songs,
            homeCarouselCurrentIndex = homeCarouselQueue.currentIndex,
            miniPlayerTitle = miniPlayerCoordinator.title,
            miniPlayerArtist = miniPlayerCoordinator.artist,
            miniPlayerLyric = lyricsCoordinator.currentLyricText,
            miniPlayerLyricTranslation = lyricsCoordinator.currentLyricTranslation,
            lyricSong = lyricsCoordinator.lyricSong,
            miniPlayerIsPlaying = miniPlayerCoordinator.isPlaying,
            miniPlayerProgress = miniPlayerCoordinator.progress,
            miniPlayerPreviousSong = miniPlayerPreviousSong,
            miniPlayerNextSong = miniPlayerNextSong,
            playbackPositionMs = playbackPositionMs,
            playbackDurationMs = playbackDurationMs,
            nextSongTitle = nextSongTitle,
            miniPlayerCoverPath = miniPlayerCoordinator.coverPath,
            playerReturnRevealIndex = playerReturnRevealIndex,
            // List artwork remains ordinary MAIN content throughout collapse. PLAYER -> MAIN
            // is already hard-wired to the MiniPlayer endpoint, so hiding the current list cover
            // only creates a visible hole/recompose pulse and does not provide ownership safety.
            hidePlayingCover = false,
            currentSortOrder = AppPreferences.Sort.songSortOrder,
            artistDataSource = null,
            playCounts = playbackStats.associate { it.songId to it.playCount },
            metadataMatchSources = metadataMatchSources,
            metadataMatchProgressText = metadataMatchProgressText,
            bottomChromeHidden = songsSelectionMode || songActionSheetHelper.isPlaylistPickerShowing,
            uiForeground = composeActivityForeground,
            libraryStartupLoading = libraryStartupLoading,
            libraryStartupLoadingAlpha = libraryStartupLoadingAlpha,
        )

        com.rawsmusic.core.ui.scene.AppMainLayout(
            navState = mainNavState,
            navCallbacks = callbacks,
            navData = data,
            externalPageRenderer = AppPageRendererImpl(mainNavState),
            onNavigateToPlayer = {
                if (playerController?.currentOrRequestedSongForUi() != null) {
                    // This action originates from the MiniPlayer. Always interpolate the
                    // player artwork from the live MiniPlayer CardView, even with stacked bottom
                    // navigation present; never borrow a list-cell/fixed region for this entry.
                    openPlayPageWithSharedElement(preferMiniPlayerSource = true)
                } else moveTaskToBack(true)
            },
            onSettingsClick = {
                launchSettingsActivity(com.rawsmusic.ui.settings.SettingsActivity::class.java)
            },
            onAudioEffectsClick = {
                launchSettingsActivity(com.rawsmusic.ui.settings.AudioEffectsActivity::class.java)
            },
            onOpenSideRail = { openSideMenu() },
            sideRailOpenRequestToken = sideRailOpenRequestToken,
            sideRailCloseRequestToken = sideRailCloseRequestToken,
            onSideRailExpandedChanged = { expanded ->
                isSideMenuOpen = expanded
            },
            onMiniPlayerGestureBoundsChanged = { bounds ->
                miniPlayerGestureBoundsForSceneGesture = bounds
                // Only learn the collapsed anchor from a fully settled MAIN layout. During a
                // player transition the floating bar may be entering, changing its scroll offset
                // or moving above stacked navigation; those intermediate positions must never
                // become the sheet anchor. Preserve the last stable top even if the bar is
                // temporarily unmounted while PLAYER is visible.
                if (
                    bounds != null &&
                    playerSceneController.currentScene == PlayerSceneController.Scene.MAIN &&
                    !playerSceneController.composeIsTransitioning
                ) {
                    stableMiniPlayerBoundsForSceneGesture = bounds
                    stableFloatingMiniPlayerTopPx = bounds.top
                }
            },
            onBottomNavigationGestureBoundsChanged = { bounds ->
                bottomNavigationGestureBoundsForSceneGesture = bounds
            },
            // Audio-info library links deliberately keep PLAYER alive beneath a foreground
            // library window. Its sheet expansion must not hide/scale that foreground list.
            // Returning to the origin restores the real sheet state without moving PLAYER.
            playerSceneProgressState = if (audioInfoSharedWindowActive) {
                null
            } else {
                playerSceneController.mainPlayerSheetExpansionState
            },
            suppressMiniPlayerArtwork =
                playerSceneController.currentScene != PlayerSceneController.Scene.MAIN,
            onHomeFullCoverActiveChange = { active ->
                // This is an in-window portrait scene. Do not mutate requestedOrientation while
                // its shared artwork is moving: on vendor builds that can relayout the decor view
                // or refresh system-bar insets between the source and target frames. Rotation
                // policy is already PORTRAIT at the HOME scene and will be refreshed by the next
                // real player-scene/orientation event.
                val hostPolicy = resolveHomeFullCoverActivityHostPolicy(active)
                homeFullCoverOverlayActive = active
                gestureLockCoordinator.set(
                    GestureLockReason.SceneTransition,
                    hostPolicy.blockRootSceneGesture,
                )
                rotationCoordinator.setHomeFullCoverPolicy(
                    launchArmed = hostPolicy.landscapeLaunchArmed,
                    clearPendingLaunch = hostPolicy.clearPendingLandscapeLaunch,
                )
                updatePredictiveBackRegistration()
            },
            onSideRailDestination = { destination ->
                when (destination) {
                    com.rawsmusic.core.ui.scene.AppSideRailDestination.MUSIC_LIBRARY -> {
                        mainNavState.navigateTo(com.rawsmusic.core.ui.scene.NavScene.SONGS)
                    }
                    com.rawsmusic.core.ui.scene.AppSideRailDestination.PLAYLISTS -> {
                        mainNavState.navigateTo(com.rawsmusic.core.ui.scene.NavScene.PLAYLISTS)
                    }
                    com.rawsmusic.core.ui.scene.AppSideRailDestination.APPEARANCE -> {
                        launchSettingsActivity(com.rawsmusic.ui.settings.AppearanceActivity::class.java)
                    }
                    com.rawsmusic.core.ui.scene.AppSideRailDestination.LYRICS -> {
                        launchSettingsActivity(com.rawsmusic.ui.settings.LyricSettingsActivity::class.java)
                    }
                    com.rawsmusic.core.ui.scene.AppSideRailDestination.AI_MODELS -> {
                        launchSettingsActivity(com.rawsmusic.ui.settings.AiSeparationActivity::class.java)
                    }
                    com.rawsmusic.core.ui.scene.AppSideRailDestination.SOURCE_IMPORT -> {
                        mainNavState.navigateTo(com.rawsmusic.core.ui.scene.NavScene.SOURCE_IMPORT)
                    }
                    com.rawsmusic.core.ui.scene.AppSideRailDestination.LOG_ANALYSIS -> {
                        launchSettingsActivity(com.rawsmusic.ui.settings.LogViewerActivity::class.java)
                    }
                }
            }
        )
    }

    internal fun playSongFromSearch(song: AudioFile) {
        val songs = MusicRepository.songs.value
        val index = songs.indexOfFirst { it.id == song.id || it.path == song.path }.coerceAtLeast(0)
        val queue = songs.ifEmpty { listOf(song) }
        val targetIndex = if (index in queue.indices) index else 0
        primePlayerUi(queue[targetIndex])
        playbackQueueHelper.playQueue(queue, targetIndex)
        openPlayPageWithSharedElement()
    }

    internal fun playShuffledSearchResults(songs: List<AudioFile>) {
        val controller = playerController ?: return
        val queue = controller.planShuffleSession(songs.ifEmpty { return })
        val first = queue.first()
        primePlayerUi(first)
        controller.playPlannedShuffleQueue(queue)
        openPlayPageWithSharedElement()
    }

    private fun shuffleCategoryGroups(
        scene: com.rawsmusic.core.ui.scene.NavScene,
        songs: List<AudioFile>,
    ): List<List<AudioFile>>? {
        val keyOf: ((AudioFile) -> String)? = when (scene) {
            com.rawsmusic.core.ui.scene.NavScene.ALBUMS -> { song ->
                val artist = song.albumArtist.ifBlank { song.artist }.ifBlank { "未知艺术家" }
                "$artist\u001f${song.album.ifBlank { "未知专辑" }}"
            }
            com.rawsmusic.core.ui.scene.NavScene.ARTISTS -> { song ->
                song.artist.ifBlank { song.albumArtist }.ifBlank { "未知艺术家" }
            }
            com.rawsmusic.core.ui.scene.NavScene.GENRE -> { song -> song.genre.trim().ifBlank { "未知流派" } }
            com.rawsmusic.core.ui.scene.NavScene.YEAR -> { song ->
                song.year.takeIf { it > 0 }?.toString() ?: "未知年份"
            }
            com.rawsmusic.core.ui.scene.NavScene.COMPOSER -> { song -> song.composer.trim().ifBlank { "未知作曲家" } }
            com.rawsmusic.core.ui.scene.NavScene.FOLDERS -> { song ->
                java.io.File(song.path).parentFile?.absolutePath.orEmpty()
            }
            else -> null
        }
        return keyOf?.let { selector -> songs.groupBy(selector).values.toList() }
    }

    internal fun openAlbumFromSearch(album: com.rawsmusic.core.common.model.Album) {
        val arg = "${album.name}|${album.artist}|${album.coverPath}"
        mainNavState.navigateTo(com.rawsmusic.core.ui.scene.NavScene.ALBUM_DETAIL, arg)
    }

    internal fun openArtistFromSearch(artist: com.rawsmusic.core.common.model.Artist) {
        mainNavState.navigateTo(
            com.rawsmusic.core.ui.scene.NavScene.ARTIST_DETAIL,
            android.net.Uri.encode(artist.name)
        )
    }

    internal fun openFolderFromSearch(folder: com.rawsmusic.core.common.model.Folder) {
        mainNavState.navigateTo(
            com.rawsmusic.core.ui.scene.NavScene.FOLDER_DETAIL,
            android.net.Uri.encode(folder.path)
        )
    }

    private fun observeMainContainerFlows() {
        // Compose 版本：数据通过 collectAsState 在 Composable 中自动同步
        // 只保留场景变化监听
        lifecycleScope.launch {
            snapshotFlow { mainNavState.currentScene }.collect { scene ->
                updateDrawerLockMode()
                // Scene navigation happens inside this Activity. Re-arm its callback as soon as
                // the destination changes so the first back gesture cannot escape to task-level
                // predictive back before Compose has had another interaction.
                updatePredictiveBackRegistration()
                mainHandler.post {
                    updatePredictiveBackRegistration()
                }
            }
        }
    }

    private fun scheduleDeferredStartupWork() {
        if (startupWorkScheduled) return
        startupWorkScheduled = true

        ioScope.launch {
            val start = System.currentTimeMillis()
            try {
                MusicRepository.warmStartCacheAsync("main_activity_deferred")
                val snapshotSongs = MusicRepository.songs.value
                AppLogger.d(
                    "Startup",
                    "deferred MusicRepository warm-start request size=${snapshotSongs.size} in ${System.currentTimeMillis() - start}ms"
                )
            } catch (e: Exception) {
                AppLogger.e("Startup", "deferred MusicRepository startup load failed", e)
            }
        }

        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            restoreLastPlayingState()
            scheduleColdStartCurrentSongReveal()
            playerServiceBridgeHelper.startForegroundServiceIfNeeded()
            // Lyricon 已在 PlayerService.onCreate() 初始化，此处只设回调
            LyriconProviderManager.onProviderConnected = {
                lyricsCoordinator.resendToLyricon()
            }
            LyricGetterBridge.init(this)
        }, 60L)

        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                requestAudioPermission()
            }
        }, 750L)
    }

    /**
     * 冷启动时 controller 会先从轻量偏好恢复歌曲，而曲库 StateFlow 往往稍后才填充。
     * 只监听到当前歌曲事件会得到 index=-1，之后曲库就绪也不会再次触发歌曲事件。
     * 这里以 id/path 为稳定身份等待真实列表出现，并只发出一次定位请求。
     */
    private fun scheduleColdStartCurrentSongReveal() {
        if (!coldStartRevealPending || coldStartRevealJob?.isActive == true) return

        val restoredNow = playerController?.currentSong?.value
        val rememberedId = restoredNow?.id?.takeIf { it >= 0L } ?: AppPreferences.Player.lastSongId
        val rememberedPath = restoredNow?.path?.takeIf { it.isNotBlank() }
            ?: AppPreferences.Player.lastSongPath
        if (rememberedId < 0L && rememberedPath.isBlank()) {
            coldStartRevealPending = false
            return
        }

        coldStartRevealJob = lifecycleScope.launch(Dispatchers.Main) {
            MusicRepository.songs.collect { songs ->
                if (!coldStartRevealPending || songs.isEmpty()) return@collect

                val restored = playerController?.currentSong?.value
                val targetId = restored?.id?.takeIf { it >= 0L } ?: rememberedId
                val targetPath = restored?.path?.takeIf { it.isNotBlank() } ?: rememberedPath
                val index = songs.indexOfFirst { candidate ->
                    (targetId >= 0L && candidate.id == targetId) ||
                        (targetPath.isNotBlank() && candidate.path == targetPath)
                }
                if (index < 0) return@collect

                // The first restore can contain only the persisted identity (its file-size and
                // modified-time fields are intentionally lightweight). Replace that seed with
                // the Room-authoritative AudioFile before the player page resolves artwork. This
                // keeps the audio:// cover key identical to list/notification requests and lets
                // the provider warm the correct source instead of decoding a second empty-key
                // record after the page is already visible.
                val authoritativeSong = songs[index]
                playerController?.updateCurrentSongIfSamePath(authoritativeSong)
                BitmapProvider.warmPlaybackArt(authoritativeSong.coverKey)

                playerReturnRevealIndex = index
                coldStartRevealPending = false
                coldStartRevealAwaitingResolve = true
                AppLogger.d(
                    "Startup",
                    "cold-start locate current song index=$index id=$targetId path=$targetPath"
                )
                this.cancel()
            }
        }
    }

    private fun setupplayerSceneController() {
        composeImmersiveEnabled = com.rawsmusic.module.data.prefs.AppPreferences.UI.isImmersiveEnabled
        composeMiniCoverEnabled = com.rawsmusic.module.data.prefs.AppPreferences.UI.isMiniCoverEnabled
        composeDefaultBackgroundEnabled = com.rawsmusic.module.data.prefs.AppPreferences.UI.isDefaultBackgroundEnabled
        playerSceneController.refreshImmersiveState(composeImmersiveEnabled)
        playerSceneController.updateMiniCoverEnabled(composeMiniCoverEnabled)
        syncImmersiveBackgroundSettings()

        immersiveBackgroundState.onImmersiveDrawingChanged = { drawing ->
            // 非沉浸模式下保持 playBgView 隐藏，避免背景层叠加过亮。
            val isImmersive = composeImmersiveEnabled
            backgroundLayerState.playBackgroundAlpha = if (drawing || !isImmersive) 0f else 1f
        }

        setupComposeLayer()

        setupSceneParams()

        syncImmersiveBackgroundSettings()

        mainHandler.post {
            if (prePlayerWasInFragmentMode && com.rawsmusic.module.data.prefs.AppPreferences.UI.lastScene == "MAIN") {
                val savedDest = prePlayerFragmentDest
                if (savedDest != null && savedDest != -1 && savedDest != R.id.nav_songs) {
                    switchToFragmentMode()
                    legacyNavigateTo(savedDest)
                } else {
                    // 歌曲列表已迁移到容器模式，无需切换到 Fragment 模式
                    switchToContainerMode(com.rawsmusic.core.ui.scene.NavScene.SONGS)
                }
                prePlayerWasInFragmentMode = false
            }
        }

        playBackgroundState.setDimAmount(0f)
        playBackgroundState.setBlurredCoverStyle(true)

        // 沉浸模式...
        lyricBackgroundState.setDimAmount(0f)
        lyricBackgroundState.setBlurredCoverStyle(true)

        // 非沉浸模式
        backgroundState.setDimAmount(0f)
        backgroundState.setBlurredCoverStyle(true)

        playerSceneController.onTransitionProgress = { targetScene, ratio ->
            // 导航栏始终保持可见，不做任何 alpha/visibility 变化，避免"先淡出再显示"的闪烁
            // 场景切换的状态由 onSceneChanged 统一管理
            val sheetVisible = targetScene == PlayerSceneController.Scene.PLAYER && ratio > 0f
            if (predictiveBackMainPlayerSheetVisible != sheetVisible) {
                predictiveBackMainPlayerSheetVisible = sheetVisible
                updatePredictiveBackRegistration()
            }
        }

        playerSceneController.onSceneChanged = { newScene, oldScene ->
            predictiveBackMainPlayerSheetVisible = false
            // Commit the visual owner first. Insets, navigation containers and persistence are
            // settled after this frame so they cannot steal the final animation deadline.
            syncComposePlayerScene(newScene)
            window.decorView.postOnAnimation settledScene@{
                if (playerSceneController.currentScene != newScene ||
                    playerSceneController.composeIsTransitioning
                ) return@settledScene

                syncMainActivityRotationPolicy(newScene)
                val isRealTransition = oldScene != newScene
                com.rawsmusic.module.data.prefs.AppPreferences.UI.lastScene = newScene.name
                if (newScene == PlayerSceneController.Scene.MAIN) {
                    com.rawsmusic.module.data.prefs.AppPreferences.UI.wasInFragmentMode = false
                }
                // Insets changes are deliberately outside the animator completion frame.
                val needsUpdate = (oldScene == PlayerSceneController.Scene.MAIN) !=
                    (newScene == PlayerSceneController.Scene.MAIN)
                if (needsUpdate) updateStatusBarForLevel(newScene)

                when (newScene) {
                PlayerSceneController.Scene.MAIN -> {
                    // 返回主界面：隐藏播放器容器，显示 Compose 主界面
                    updateComposeRootVisibility(true)

                    // 先切换容器内场景（隐藏其他页面），再恢复默认参数（设容器为 VISIBLE）
                    // 顺序不能反：如果先 restoreDefaultSceneParams 设容器 VISIBLE，所有页面会闪现
                    if (isRealTransition && (
                            oldScene == PlayerSceneController.Scene.PLAYER ||
                                oldScene == PlayerSceneController.Scene.LYRIC ||
                                oldScene == PlayerSceneController.Scene.ALBUM_DETAIL
                            )
                    ) {
                        val songsDest = prePlayerWasInFragmentMode && (prePlayerFragmentDest == null || prePlayerFragmentDest == R.id.nav_songs)
                        if (songsDest) {
                            // preparePlayerToMainMiniPlayerEndpoint() already restored the Compose
                            // container before the first return frame. Re-applying switchToSilent
                            // here, one frame after the sheet reached MAIN, resets the visible
                            // provider/layout owner and produces the characteristic one-frame
                            // "jump to top" after an otherwise-complete gesture.
                        } else if (prePlayerWasInFragmentMode) {
                            switchToFragmentMode()
                        } else {
                            // Compose container mode was prepared before PLAYER -> MAIN motion and
                            // has remained mounted underneath the sheet for the entire gesture.
                            // Do not submit the same navigation scene again at the endpoint.
                        }
                    }
                    restoreDefaultSceneParams()
                    // 仅在场景实际从 PLAYER/LYRIC 切换回 MAIN 时才处理导航栈，
                    // 避免 forceReapplyCurrentScene()（oldScene==newScene）误杀二级页面（如 PEQ）
                    if (isRealTransition) {
                        if (prePlayerWasInFragmentMode && (prePlayerFragmentDest != null && prePlayerFragmentDest != R.id.nav_songs)) {
                            val savedDest = prePlayerFragmentDest!!
                            // 若页面仍在导航栈顶（如专辑详情页），直接复用，不 pop 不重新导航
                            if (legacyDestinationId != savedDest && savedDest != R.id.nav_songs) {
                                // 页面已不在栈顶，先回到歌曲列表，再重新导航
                                legacyPopToSongs()
                                legacyNavigateTo(savedDest)
                            } else if (legacyDestinationId != savedDest) {
                                legacyPopToSongs()
                            }
                        }
                        // Compose 主界面会保持当前页面
                        prePlayerFragmentDest = null
                        prePlayerWasInFragmentMode = false
                    }
                    pendingSettingsSceneAfterPlayerClose?.let { pendingScene ->
                        pendingSettingsSceneAfterPlayerClose = null
                        val activityClass = SETTINGS_ACTIVITY_MAP[pendingScene]
                        if (activityClass != null) {
                            mainNavState.navigateHome()
                            launchSettingsActivity(activityClass)
                        } else if (mainNavState.currentScene != pendingScene) {
                            mainNavState.navigateToSettings(pendingScene)
                        }
                    }
                    updateDrawerLockMode()
                    // 注意：以下 view 的 visibility/alpha 由 sceneRegistry 动画引擎管理，
                    // onSceneChanged 中不再重复设置，避免与动画最终状态冲突
                    metadataCardPopupHelper.hide()
                    // 恢复流动光效果
                    playBackgroundState.setDynamic(true)
                    playBackgroundState.setAllowDynamicRunning(true)
                    // Keep the floating return owner through the first committed MAIN frame.
                    // Compose can still be retiring the shared overlay while list cells publish
                    // fresh bounds; releasing in the same animator callback lets that final frame
                    // retarget to ListCover. Hand ownership back only after the collapsed
                    // MiniPlayer is already the visible endpoint.
                    if (floatingReturnOwnsMiniPlayerTarget) {
                        window.decorView.postOnAnimation {
                            if (playerSceneController.currentScene == PlayerSceneController.Scene.MAIN &&
                                !playerSceneController.composeIsTransitioning
                            ) {
                                floatingReturnOwnsMiniPlayerTarget = false
                            }
                        }
                    }
                }
                PlayerSceneController.Scene.PLAYER -> {
                    playerSceneController.syncRotationState(playerSceneController.isCurrentlyPlaying)
                    val isImmersive = composeImmersiveEnabled
                    val isFlowingLightOff = com.rawsmusic.module.data.prefs.AppPreferences.UI.isFlowingLightDisabled
                    if (isFlowingLightOff) {
                        playBackgroundState.setDynamic(false)
                        playBackgroundState.setAllowDynamicRunning(false)
                        playBackgroundState.pauseAnimations()
                    } else {
                        playBackgroundState.setDynamic(true)
                        playBackgroundState.setAllowDynamicRunning(true)
                        playBackgroundState.resumeAnimations()
                    }
                    backgroundLayerState.mainScrimVisible = false
                    backgroundLayerState.mainScrimAlpha = 0f
                }
                PlayerSceneController.Scene.LYRIC -> {
                    val pos = playerController?.position?.value ?: 0L
                    composeLyricPositionMs = lyricsCoordinator.playbackToLyricPosition(pos)
                    playerController?.currentSong?.value?.let { song ->
                        val lyricData = currentLyricData
                        if (!lyricData.isEmpty) {
                            composeLyricSong = lyricData.toLyriconSong(
                                name = song.title,
                                artist = song.artist,
                                durationMs = song.duration
                            )
                            val displayTrans = com.rawsmusic.module.data.prefs.AppPreferences.Lyricon.displayTranslation
                            composeDisplayTranslation = displayTrans
                            composeDisplayRoma = com.rawsmusic.module.data.prefs.AppPreferences.Lyricon.displayRoma
                        }
                    }

                    lyricBackgroundState.resumeAnimations()
                    backgroundLayerState.mainScrimVisible = false
                    backgroundLayerState.mainScrimAlpha = 0f
                }
                PlayerSceneController.Scene.QUEUE -> {
                    backgroundLayerState.playScrimVisible = false
                    backgroundLayerState.playScrimAlpha = 0f
                    backgroundLayerState.mainScrimVisible = false
                    backgroundLayerState.mainScrimAlpha = 0f
                }
                PlayerSceneController.Scene.ALBUM_DETAIL -> {
                    backgroundLayerState.playScrimVisible = false
                    backgroundLayerState.playScrimAlpha = 0f
                    backgroundLayerState.mainScrimVisible = false
                    backgroundLayerState.mainScrimAlpha = 0f
                }
            }
                // 场景变化后更新预测性返回回调注册状态
                updatePredictiveBackRegistration()
            }
        }

        // 根据当前PLAY/LYRIC 场景更新参数
        playerSceneController.onLeftEdgeSwipe = {
            if (playerSceneController.currentScene != PlayerSceneController.Scene.MAIN) {
                legacyNavigateUp()
            }
        }

        // 返回上一级，使用 navigateUp() 返回主界面
        playerSceneController.onSwipeBack = {
            if (mainNavState.canNavigateBack()) {
                mainNavState.navigateBackAnimated()
            } else if (true && mainNavState.isAtHome() != true) {
                mainNavState.navigateHome()
            } else {
                legacyNavigateUp()
            }
        }

        playerSceneController.onImmersiveSwipeLeft = null

        playerSceneController.onPlayerSwipeToMain = {
            // 保存当前 Fragment 目标，用于返回时恢复（如果尚未保存）
            if (prePlayerFragmentDest == null) {
                prePlayerFragmentDest = legacyDestinationId
            }
            if (prePlayerContainerScene == null && true) {
                prePlayerContainerScene = mainNavState.currentScene
            }
            playerSceneController.closePlayPageWithCoverAlign(true)
        }

        playerSceneController.onPreparePlayerToMain = prepare@{ onReady ->
            preparePlayerToMainMiniPlayerEndpoint()
            onReady()
        }

        playerSceneController.onPreparePlayerToLyric = {
            if (!lyricBackgroundState.syncFrom(playBackgroundState)) {
                lyricBackgroundState.syncFrom(backgroundState)
            }
            lyricBackgroundState.resumeAnimations()
            registerCoverLyricParams()
        }

        playerSceneController.onPrepareMainToPlayer = {
            registerCoverCollapseParams()
        }

        // HOME场景：恢复默认布局参数
        playerSceneController.onHomeSwipeRightDrag = { }
        playerSceneController.onHomeSwipeRightRelease = { shouldOpen ->
            if (shouldOpen) openSideMenu()
        }
        // 重置 ViewModel 相关状态
    }

    /**
     */
    private fun setupSceneParams() {
        syncImmersiveBackgroundSettings()
    }

    private fun syncImmersiveBackgroundSettings() {
        composeImmersiveEnabled = AppPreferences.UI.isImmersiveEnabled
        composeMiniCoverEnabled = AppPreferences.UI.isMiniCoverEnabled
        composeDefaultBackgroundEnabled = true
        immersiveBackgroundState.isImmersiveEnabled = false
        immersiveBackgroundState.isMiniCoverEnabled = false
        immersiveBackgroundState.isDarkMode = isDarkMode
        mainPersistentCoverState.isImmersiveEnabled = false
        mainPersistentCoverState.isMiniCoverEnabled = false
        mainPersistentCoverState.isDarkMode = isDarkMode
        mainPersistentCoverState.coverAlpha = 0f
    }

    private fun updateImmersiveCoverState(path: String?) {
        immersiveBackgroundState.clear()
        mainPersistentCoverState.clear()
    }

    // Compose 播放栏状态
    // 旧字段代理到 miniPlayerCoordinator，保留兼容
    private val miniPlayerTitle get() = miniPlayerCoordinator.title
    private val miniPlayerArtist get() = miniPlayerCoordinator.artist
    private val miniPlayerIsPlaying get() = miniPlayerCoordinator.isPlaying
    private val miniPlayerProgress get() = miniPlayerCoordinator.progress
    private val miniPlayerCoverPath get() = miniPlayerCoordinator.coverPath
    private var visualizerSpectrum by mutableStateOf(
        FloatArray(com.rawsmusic.module.player.dsp.NativeStereoSpectrumAnalyzer.OUTPUT_SIZE)
    )
    private var playerAlbumSongs by mutableStateOf<List<AudioFile>>(emptyList())
    private var playerAlbumCoverPath by mutableStateOf<String?>(null)
    private val overlayCoordinator by lazy {
        OverlayCoordinator(
            isPlayerPageVisible = {
                if (usesNormalPlayerSheet()) {
                    true
                } else if (::playerSceneController.isInitialized) {
                    playerSceneController.currentScene != PlayerSceneController.Scene.MAIN ||
                        playerSceneController.isTransitioning
                } else {
                    playerSceneState.currentScene != com.rawsmusic.core.ui.widget.PlayerScene.MAIN
                }
            }
        )
    }
    private val usbVolumeHideRunnable = Runnable { overlayCoordinator.hideUsbVolume() }
    private var folderPickerResultUri by mutableStateOf<android.net.Uri?>(null)

    private fun updateMiniPlayerBarSong() {
        miniPlayerCoordinator.updateSong(playerController?.currentOrRequestedSongForUi())
    }

    private fun updateMiniPlayerBarPlayback() {
        val playing = playerController?.playState?.value == PlayState.PLAYING
        miniPlayerCoordinator.updatePlaybackState(playing)
        realtimeSpectrumPipeline.setPlaying(playing)
    }

    private fun resolveSongCoverForCompose(song: AudioFile): String {
        return if (::coverUriResolver.isInitialized) {
            coverUriResolver.resolveCoverUri(song).ifBlank { song.coverKey }
        } else {
            song.coverKey
        }
    }

    private fun isSamePlayerUiItem(first: AudioFile?, second: AudioFile?): Boolean {
        if (first == null || second == null) return false
        return first.path == second.path &&
            first.cueOffsetMs == second.cueOffsetMs &&
            first.cueTrackIndex == second.cueTrackIndex
    }

    private fun primePlayerUi(song: AudioFile) {
        playerController?.primeSongSelectionForUi(song)
        lockedPlayerCoverPathForTransition = resolveSongCoverForCompose(song)
        updateComposeRootVisibility(true)
    }

    private fun launchCurrentSongInLyrico() = lyricoCoordinator.launchEditor()

    private fun launchLyricoOnlineSearch() = lyricoCoordinator.launchOnlineSearch()

    private fun launchExternalTimingEditor(target: LyricTimingEditorTarget) {
        val song = playerController?.currentSong?.value
        if (song == null) {
            AppNoticeBus.error(getString(UiR.string.lyric_timing_external_unavailable))
            return
        }
        val intent = LyricoIntegration.buildExternalTimingIntent(this, song, target)
        if (intent == null) {
            AppNoticeBus.error(getString(UiR.string.lyric_timing_external_unavailable))
            return
        }
        runCatching {
            startActivity(intent)
        }.onFailure { error ->
            LyricoIntegration.traceLaunchFailure("timing_external target=$target", error)
            AppNoticeBus.error(getString(UiR.string.lyric_timing_external_failed))
        }
    }

    private fun refreshSongAfterLyricoEdit(
        song: AudioFile,
        mutation: LyricoEditMutation = LyricoEditMutation.Unknown,
    ) {
        lyricoCoordinator.refreshAfterEdit(song, mutation)
    }


    /**
     * 设置 Compose 层
     * 渲染主 Activity 的纯 Compose 内容
     * 液态玻璃效果在 Compose 树内生效
     */
    // 播放器场景状态（纯 Compose）
    private val playerSceneState = com.rawsmusic.core.ui.widget.PlayerSceneState()

    private fun setupComposeLayer() {
        updateComposeRootVisibility(
            songActionSheetHelper.isSongActionSheetShowing ||
                songActionSheetHelper.isPlaylistPickerShowing ||
                metadataDetailHelper.isVisible ||
                metadataEditorHelper.isMetadataEditorShowing ||
                metadataEditorHelper.isDeleteConfirmShowing ||
                audioInfoCapsuleHelper.isPopupShowing ||
                metadataCardPopupHelper.isShowing ||
                playModePopupHelper.isShowing ||
                dialogHelper.isShowing
        )
    }

    @Composable
    private fun PlayerOverlayContent() {
        // Keep artwork lanes warm while the library is visible. Creating them only after the
        // MAIN -> PLAYER transition starts leaves an empty frame before the complete player pops in.
        val prewarmQueue by playerController?.visibleQueue?.collectAsState()
            ?: androidx.compose.runtime.mutableStateOf(com.rawsmusic.core.common.model.PlayQueue())
        val prewarmCombinedQueue = prewarmQueue.songs
        val prewarmReportedIndex = prewarmQueue.currentIndex
        // The retained artwork state receives request hints separately. Keep title, queue and the
        // rest of the player bound to one renderer-confirmed identity so a button press cannot
        // recompose the complete player tree before audio accepts the target.
        // Queue + cursor is the player presentation snapshot. Avoid a second independent
        // currentSong subscription on the same composition boundary; renderer commit publishes
        // both fields together, while the direct read only covers a restored one-song cold start.
        val playerUiSong = prewarmQueue.currentSong ?: playerController?.currentSong?.value
        val prewarmCommittedSong = playerUiSong
        var standardPlayerPrewarmConsumed by rememberSaveable { mutableStateOf(false) }
        val prewarmCommittedIndex = resolvePlaybackQueueBindingIndex(
            currentSong = prewarmCommittedSong,
            queueSongs = prewarmCombinedQueue,
            reportedIndex = prewarmReportedIndex,
        )
        val prewarmCommittedCoverPath = prewarmCommittedSong?.let { resolveSongCoverForCompose(it) }
        val prewarmedArtworkTransitionState = rememberPlaybackArtworkTransitionState(
            // currentSong and PlayQueue are separate StateFlows. Resolve the index from the exact
            // committed audio/CUE identity inside this same queue snapshot so a one-frame stale
            // queue.currentIndex can never turn a real track change into a same-position art reset.
            currentKey = prewarmCommittedSong.resolvePlaybackArtworkKey(prewarmCommittedCoverPath),
            queueCurrentIndex = prewarmCommittedIndex,
            queueSize = prewarmCombinedQueue.size,
            automaticCrossfadeEnabled = AppPreferences.Player.automaticCrossfadeEnabled,
        )
        val committedArtworkKey = prewarmCommittedSong.resolvePlaybackArtworkKey(
            prewarmCommittedCoverPath
        )
        LaunchedEffect(prewarmCommittedIndex, committedArtworkKey) {
            if (com.rawsmusic.core.common.utils.PlayerSwitchTrace.isActive()) {
                com.rawsmusic.core.common.utils.PlayerSwitchTrace.mark(
                    "compose_queue_snapshot",
                    "index=$prewarmCommittedIndex key=${committedArtworkKey?.hashCode()?.toString(16) ?: "-"} songId=${prewarmCommittedSong?.id ?: -1L}",
                )
            }
        }
        val prewarmPreviousArtworkKey = playerController
            ?.previewPreviousSong(prewarmQueue)
            .resolvePlaybackArtworkKey()
        val prewarmNextArtworkKey = playerController
            ?.previewNextSong(prewarmQueue)
            .resolvePlaybackArtworkKey()
        SideEffect {
            prewarmedArtworkTransitionState.updateNavigationNeighbourKeys(
                previousKey = prewarmPreviousArtworkKey,
                nextKey = prewarmNextArtworkKey,
            )
        }
        LaunchedEffect(prewarmPreviousArtworkKey, prewarmNextArtworkKey) {
            prewarmedArtworkTransitionState.prefetchNavigationNeighbours(
                previousKey = prewarmPreviousArtworkKey,
                nextKey = prewarmNextArtworkKey,
            )
        }

        var showSleepTimerSheet by rememberSaveable { mutableStateOf(false) }
        androidx.compose.runtime.DisposableEffect(showSleepTimerSheet) {
            gestureLockCoordinator.set(
                GestureLockReason.SleepTimer,
                showSleepTimerSheet,
            )
            onDispose {
                if (showSleepTimerSheet) {
                    gestureLockCoordinator.set(GestureLockReason.SleepTimer, false)
                }
            }
        }
        var sleepTimerUiMode by rememberSaveable {
            mutableIntStateOf(playerController?.getSleepTimerMode() ?: 0)
        }
        var sleepTimerUiSelection by rememberSaveable {
            mutableIntStateOf(
                when (sleepTimerUiMode) {
                    1 -> when (AppPreferences.Player.sleepTimerMinutes) {
                        10 -> 1
                        15 -> 2
                        20 -> 3
                        30 -> 4
                        45 -> 5
                        60 -> 6
                        90 -> 7
                        else -> 0
                    }
                    2 -> when (playerController?.getSleepTimerSongsRemaining()
                        ?.takeIf { it > 0 } ?: AppPreferences.Player.sleepTimerSongs) {
                        5 -> 10
                        else -> 9
                    }
                    3 -> 8
                    4 -> 11
                    else -> 0
                }
            )
        }
        var sleepTimerCustomDurationMs by rememberSaveable {
            mutableLongStateOf(AppPreferences.Player.sleepTimerCustomDurationMs)
        }
        var sleepTimerExitWhenFinished by rememberSaveable {
            mutableStateOf(AppPreferences.Player.sleepTimerExitPlayerWhenFinished)
        }
        val sleepTimerRemainingForPanel by playerController?.sleepTimerRemaining?.collectAsState()
            ?: androidx.compose.runtime.mutableStateOf(0L)

        if (!overlayCoordinator.composeOverlayContentVisible) return

        Box(Modifier.fillMaxSize()) {
            val currentScene = playerSceneState.currentScene
            val controllerTransitioning = ::playerSceneController.isInitialized &&
                playerSceneController.composeIsTransitioning
            val controllerScene = if (::playerSceneController.isInitialized) {
                playerSceneController.composeCurrentScene
            } else {
                PlayerSceneController.Scene.MAIN
            }
            val controllerFromScene = if (::playerSceneController.isInitialized) {
                playerSceneController.composeFromScene
            } else {
                PlayerSceneController.Scene.MAIN
            }
            val controllerToScene = if (::playerSceneController.isInitialized) {
                playerSceneController.composeToScene
            } else {
                PlayerSceneController.Scene.MAIN
            }
            val controllerVisualScene = if (controllerTransitioning) {
                if (controllerToScene != PlayerSceneController.Scene.MAIN) {
                    controllerToScene
                } else {
                    controllerFromScene
                }
            } else {
                controllerScene
            }
            val controllerPlayerVisible = controllerVisualScene != PlayerSceneController.Scene.MAIN
            LaunchedEffect(controllerPlayerVisible) {
                if (controllerPlayerVisible) {
                    // The full offstage PLAYER/LYRIC tree exists only to remove the first cold
                    // composition/measure spike. Once a real PLAYER scene has used that warm tree,
                    // do not keep it permanently attached behind MAIN: classes/resources/artwork
                    // are already hot, while the retained Compose tree would keep observing player
                    // state after return and compete with library transitions.
                    standardPlayerPrewarmConsumed = true
                }
            }
            val auxiliaryPlayerSceneVisible = currentScene == com.rawsmusic.core.ui.widget.PlayerScene.QUEUE ||
                currentScene == com.rawsmusic.core.ui.widget.PlayerScene.ALBUM_DETAIL ||
                currentScene == com.rawsmusic.core.ui.widget.PlayerScene.FULL_COVER
            val standardPlayerOwnsVerticalGesture = !composeImmersiveEnabled &&
                (controllerVisualScene == PlayerSceneController.Scene.PLAYER ||
                    controllerVisualScene == PlayerSceneController.Scene.LYRIC)
            val persistentNormalPlayerSheet = usesNormalPlayerSheet() && !composeImmersiveEnabled
            // Warm the complete PLAYER/LYRIC tree exactly once, including persistent NORMAL.
            // NORMAL keeps the outer player-overlay host permanently mounted for geometry/gesture
            // ownership, but the expensive player content itself must not remain composed behind
            // MAIN after the first real visit. The reference implementation retains player/list hosts and View
            // caches; it does not make an invisible full player hierarchy consume every playback
            // clock while the library is authoritative.
            val prewarmStandardPlayerSurface = !composeImmersiveEnabled &&
                !standardPlayerPrewarmConsumed &&
                prewarmCommittedSong != null
            // The Activity-level PlayerOverlayContent call remains permanently mounted, which is
            // the stable host needed by NORMAL shared-geometry ownership. The expensive inner
            // player host/state subscriptions exist only while actually visible/transitioning or
            // during the one-shot cold prewarm. Settled MAIN must not keep a hidden full player
            // observer graph alive merely because NORMAL uses a persistent collapsed MiniPlayer.
            if (controllerPlayerVisible || auxiliaryPlayerSceneVisible || prewarmStandardPlayerSurface) {
                com.rawsmusic.core.ui.widget.PlayerDismissMotionHost(
                    openToken = 31 * controllerVisualScene.hashCode() + currentScene.hashCode(),
                    onDismissProgressChange = { progress ->
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.updateExternalMainDismissProgress(
                                if (composeImmersiveEnabled) progress else 0f
                            )
                        }
                    },
                    onDismiss = {
                        if (::playerSceneController.isInitialized) {
                            // PlayerDismissMotionHost has already translated the immersive player
                            // completely off-screen before invoking this callback. Starting a
                            // second PLAYER -> MAIN animator keeps mainPlayerSheetExpansion at 1
                            // while MAIN is already visible underneath, which hides both MiniPlayer
                            // variants and can strand the user without a way back into PLAYER.
                            // Commit the controller anchor immediately; the host owned the motion.
                            playerSceneController.closeCurrentPlayerStackToMain(false)
                        }
                    },
                    // 禁用 PlayerDismissMotionHost 的 BackHandler，避免触发 LocalNavigationEventDispatcherOwner 崩溃
                    backEnabled = false,
                    // 普通播放页/歌词页已经由内部场景手势处理纵向拖动。外层再次接管同一
                    // 次下滑会同时驱动两套位移，形成一个固定、一个下滑的双层播放器。
                    // Keep the host composition tree stable while a player modal is shown.
                    // Switching gestureEnabled at runtime disposes and recreates the hosted player,
                    // which drops local sheet state and makes the immersive menu flash for one frame.
                    gestureEnabled = controllerPlayerVisible && !standardPlayerOwnsVerticalGesture,
                    gestureBlocked = gestureLockCoordinator.isBlocked || showSleepTimerSheet
                ) {
                if (controllerPlayerVisible || auxiliaryPlayerSceneVisible) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            )
                    )
                }
                // These values are deliberately the same snapshot used by the prewarmed artwork
                // state above. Collecting currentSong/queue/requestedSong again here allowed one
                // recomposition to pair a new title with an old artwork state (or vice versa).
                val queue = prewarmQueue
                val displayingRequestedSong = false
                // The renderer-confirmed song is the player payload immediately.  The retained
                // artwork pager already carries outgoing/current/target identities and the
                // backdrop reads that exact holder clock, so delaying currentSong until artwork
                // settles destroys the A -> B background transition and causes a hard palette
                // commit at the end of the page move.
                val currentSong = playerUiSong
                val playState by playerController?.playState?.collectAsState()
                    ?: androidx.compose.runtime.mutableStateOf(PlayState.IDLE)
                // Playback position can publish many times per second once the renderer starts
                // writing audio. Reading that StateFlow in this large overlay composition made
                // every PLAYER scene transition compete with unrelated timeline recompositions.
                // During MAIN idle/prewarm and during every scene settle, keep a plain snapshot
                // instead. Stable PLAYER/LYRIC resumes the live subscription immediately.
                val timelineSnapshot = remember(
                    currentSong?.path,
                    currentSong?.cueOffsetMs,
                    currentSong?.cueTrackIndex
                ) {
                    longArrayOf(
                        playerController?.position?.value ?: 0L,
                        playerController?.duration?.value ?: currentSong?.duration ?: 0L,
                        lyricsCoordinator.lyricPositionMs,
                    )
                }
                val timelineTransitionLatch = remember(
                    currentSong?.path,
                    currentSong?.cueOffsetMs,
                    currentSong?.cueTrackIndex
                ) { booleanArrayOf(false) }
                // NORMAL keeps the player sheet mounted while the controller remains anchored
                // at MAIN. Its expanded player is still visible, so it must keep consuming the
                // live renderer clock; otherwise a natural crossfade leaves the sheet on the
                // last (often full) frame while MiniPlayer continues to advance normally.
                val freezePlayerTimeline = controllerTransitioning ||
                    prewarmedArtworkTransitionState.isMotionActive ||
                    prewarmStandardPlayerSurface ||
                    controllerVisualScene == PlayerSceneController.Scene.MAIN
                val observedPositionState = playerController?.position?.collectAsState()
                    ?: androidx.compose.runtime.mutableStateOf(0L)
                val observedDurationState = playerController?.duration?.collectAsState()
                    ?: androidx.compose.runtime.mutableStateOf(0L)
                val localStandardTimelineOwner =
                    !freezePlayerTimeline &&
                        !composeImmersiveEnabled &&
                        controllerVisualScene == PlayerSceneController.Scene.PLAYER

                // The reference player clock is consumed by the progress/lyric views, not by the
                // whole player hierarchy. Latch on every freeze edge so the exact same visible
                // millisecond is retained while artwork/page motion owns the screen.
                if (freezePlayerTimeline && !timelineTransitionLatch[0]) {
                    val latchedPosition = playerController?.position?.value ?: timelineSnapshot[0]
                    timelineSnapshot[0] = latchedPosition
                    timelineSnapshot[1] = playerController?.duration?.value ?: timelineSnapshot[1]
                    timelineSnapshot[2] = lyricsCoordinator.playbackToLyricPosition(latchedPosition)
                }
                timelineTransitionLatch[0] = freezePlayerTimeline

                val positionMs: Long
                val durationMs: Long
                val liveLyricPositionMs: Long
                if (freezePlayerTimeline) {
                    positionMs = timelineSnapshot[0]
                    durationMs = timelineSnapshot[1]
                    liveLyricPositionMs = timelineSnapshot[2]
                } else if (localStandardTimelineOwner) {
                    // The live position stays behind a stable State object below. Keep only the
                    // low-frequency duration in this parent composition; raw position/lyric
                    // values are compatibility fallbacks and deliberately remain latched here.
                    positionMs = timelineSnapshot[0]
                    durationMs = observedDurationState.value
                    liveLyricPositionMs = timelineSnapshot[2]
                } else {
                    val observedPosition = observedPositionState.value
                    val observedDuration = observedDurationState.value
                    // PLAYER owns its own 20 Hz timeline. Do not route word-timed lyric motion
                    // through MainActivityPlaybackObserverCoordinator: that observer also drives
                    // mini/service/external-line work and should remain a low-frequency shell lane.
                    val observedLyricPosition = lyricsCoordinator.playbackToLyricPosition(observedPosition)
                    timelineSnapshot[0] = observedPosition
                    timelineSnapshot[1] = observedDuration
                    timelineSnapshot[2] = observedLyricPosition
                    positionMs = observedPosition
                    durationMs = observedDuration
                    liveLyricPositionMs = observedLyricPosition
                }
                val playMode by playerController?.playMode?.collectAsState()
                    ?: androidx.compose.runtime.mutableStateOf(PlayMode.SEQUENTIAL)
                val autoTransitionPresentation by playerController?.autoTransitionPresentation?.collectAsState()
                    ?: androidx.compose.runtime.mutableStateOf(
                        com.rawsmusic.module.player.AutoTransitionPresentation.Idle
                    )
                val sleepTimerRemaining = sleepTimerRemainingForPanel
                val controllerSleepTimerMode = playerController?.getSleepTimerMode() ?: 0
                LaunchedEffect(controllerSleepTimerMode, sleepTimerRemaining) {
                    sleepTimerUiMode = controllerSleepTimerMode
                    sleepTimerUiSelection = when (controllerSleepTimerMode) {
                        1 -> if (sleepTimerRemaining <= 0L) {
                            0
                        } else {
                            when (AppPreferences.Player.sleepTimerMinutes) {
                                10 -> 1
                                15 -> 2
                                20 -> 3
                                30 -> 4
                                45 -> 5
                                60 -> 6
                                90 -> 7
                                else -> 0
                            }
                        }
                        2 -> when (playerController?.getSleepTimerSongsRemaining()
                            ?.takeIf { it > 0 } ?: AppPreferences.Player.sleepTimerSongs) {
                            5 -> 10
                            else -> 9
                        }
                        3 -> 8
                        4 -> 11
                        else -> 0
                    }
                }
                val sleepTimerSelection = sleepTimerUiSelection
                val coverPath = currentSong?.let { song ->
                    resolveSongCoverForCompose(song)
                }
                val isMainPlayerSharedTransition =
                    playerSceneController.composeIsTransitioning &&
                        ((playerSceneController.composeFromScene == PlayerSceneController.Scene.MAIN &&
                            playerSceneController.composeToScene == PlayerSceneController.Scene.PLAYER) ||
                            (playerSceneController.composeFromScene == PlayerSceneController.Scene.PLAYER &&
                                playerSceneController.composeToScene == PlayerSceneController.Scene.MAIN))
                val transitionCoverPath = if (isMainPlayerSharedTransition) {
                    lockedPlayerCoverPathForTransition ?: coverPath
                } else {
                    coverPath
                }

                val effectiveDurationMs = if (displayingRequestedSong) {
                    currentSong?.duration?.coerceAtLeast(0L) ?: 0L
                } else {
                    durationMs
                }
                val displayPositionMs = if (displayingRequestedSong) {
                    0L
                } else if (isSeekUiHolding && seekTargetMs >= 0L) {
                    seekTargetMs
                } else {
                    positionMs
                }
                val displayLyricPositionMs = if (displayingRequestedSong) {
                    // The old lyric tree remains visible during the short outgoing fade. Keep its
                    // final timeline too; driving it with zero briefly flashes the first line.
                    liveLyricPositionMs
                } else if (isSeekUiHolding && seekTargetMs >= 0L) {
                    seekTargetMs.coerceAtLeast(0L)
                } else {
                    liveLyricPositionMs
                }
                val localStandardPositionState = if (localStandardTimelineOwner) {
                    remember(observedPositionState) {
                        androidx.compose.runtime.derivedStateOf {
                            if (isSeekUiHolding && seekTargetMs >= 0L) {
                                seekTargetMs
                            } else {
                                observedPositionState.value
                            }
                        }
                    }
                } else {
                    null
                }
                val localStandardLyricPositionState = if (localStandardTimelineOwner) {
                    remember(observedPositionState) {
                        androidx.compose.runtime.derivedStateOf {
                            if (isSeekUiHolding && seekTargetMs >= 0L) {
                                seekTargetMs.coerceAtLeast(0L)
                            } else {
                                lyricsCoordinator.playbackToLyricPosition(observedPositionState.value)
                            }
                        }
                    }
                } else {
                    null
                }
                val localStandardAudioSpectrumState = if (
                    !composeImmersiveEnabled &&
                    controllerVisualScene == PlayerSceneController.Scene.PLAYER
                ) {
                    remember {
                        androidx.compose.runtime.derivedStateOf { visualizerSpectrum }
                    }
                } else {
                    null
                }
                val playerAudioSpectrum = if (localStandardAudioSpectrumState != null) {
                    remember { FloatArray(0) }
                } else {
                    visualizerSpectrum
                }
                val isPlayerReturningToMain =
                    playerSceneController.composeIsTransitioning &&
                        (
                            (playerSceneController.composeFromScene == PlayerSceneController.Scene.PLAYER &&
                                playerSceneController.composeToScene == PlayerSceneController.Scene.MAIN) ||
                                // The persistent sheet stores absolute MAIN->PLAYER expansion even
                                // while a PLAYER-origin drag is collapsing. The MiniPlayer-owner
                                // latch is therefore the authoritative direction signal for the
                                // floating gesture route.
                                (floatingReturnOwnsMiniPlayerTarget && usesFloatingPlayerBar())
                            )
                val normalPlayerSheet = usesNormalPlayerSheet()
                // NORMAL is a persistent mini-player <-> player sheet. Its source endpoint is
                // always the live mini-player artwork, never a visible list cell. Resolve it again
                // at render time so a late list onGloballyPositioned/shared-element callback cannot
                // steal the return endpoint after the controller commits MAIN.
                val floatingPlayerReturn = isPlayerReturningToMain && usesFloatingPlayerBar()
                // Final render guard: PLAYER -> MAIN is MiniPlayer-only. The first composition
                // after the controller starts settling can still carry the ListCover used to
                // enter PLAYER, so never consult that target on a floating return.
                val frozenFloatingReturnTarget = if (floatingPlayerReturn) {
                    currentSong?.let { song ->
                        resolveStableMiniPlayerReturnTarget(song, transitionCoverPath.orEmpty())
                    }
                } else null
                val sharedUsesMiniPlayerSource = normalPlayerSheet ||
                    usesFloatingPlayerBar() ||
                    floatingPlayerReturn ||
                    floatingReturnOwnsMiniPlayerTarget ||
                    (isMainPlayerSharedTransition &&
                        coverTargetForTransition?.source == CoverTransitionTarget.Source.MiniPlayer)
                val liveMiniTarget = if (sharedUsesMiniPlayerSource) {
                    currentSong?.let { song ->
                        resolveCurrentMiniPlayerCoverTarget(song, transitionCoverPath.orEmpty())
                    }
                } else null
                val playerSharedSourceBounds = when {
                    floatingPlayerReturn -> frozenFloatingReturnTarget?.bounds?.let { android.graphics.RectF(it) }
                        ?: lockedPlayerCoverBoundsForTransition?.let { android.graphics.RectF(it) }
                    sharedUsesMiniPlayerSource -> liveMiniTarget?.bounds?.let { android.graphics.RectF(it) }
                        ?: miniPlayerCoverBoundsForTransition?.let { android.graphics.RectF(it) }
                        ?: lockedPlayerCoverBoundsForTransition
                    else -> lockedPlayerCoverBoundsForTransition
                        ?: if (mainNavState.currentScene == com.rawsmusic.core.ui.scene.NavScene.SONGS) playingCoverBoundsForTransition else null
                }
                val playerSharedSourceTarget = when {
                    floatingPlayerReturn -> frozenFloatingReturnTarget
                        ?: playerSharedSourceBounds?.let { bounds ->
                            CoverTransitionTarget(
                                bounds = android.graphics.RectF(bounds),
                                radiusDp = stableMiniPlayerCoverGeometryForTransition?.radiusDp
                                    ?: miniPlayerCoverTargetForTransition?.radiusDp
                                    ?: currentMiniPlayerArtworkRadiusDp(),
                                source = CoverTransitionTarget.Source.MiniPlayer,
                                songId = currentSong?.id ?: -1L,
                                coverKey = transitionCoverPath.orEmpty(),
                            )
                        }
                    sharedUsesMiniPlayerSource -> (if (!normalPlayerSheet) {
                        coverTargetForTransition
                            ?.takeIf { it.source == CoverTransitionTarget.Source.MiniPlayer }
                            ?.copyBounds()
                    } else null)
                        ?: liveMiniTarget
                        ?: playerSharedSourceBounds?.let { bounds ->
                            CoverTransitionTarget(
                                bounds = android.graphics.RectF(bounds),
                                radiusDp = miniPlayerCoverTargetForTransition?.radiusDp
                                    ?: currentMiniPlayerArtworkRadiusDp(),
                                source = CoverTransitionTarget.Source.MiniPlayer,
                                songId = currentSong?.id ?: -1L,
                                coverKey = transitionCoverPath.orEmpty(),
                            )
                        }
                    else -> coverTargetForTransition ?: playerSharedSourceBounds?.let { bounds ->
                        val source = coverTargetForTransition?.source ?: CoverTransitionTarget.Source.ListCover
                        val radius = coverTargetForTransition?.radiusDp
                            ?: if (source == CoverTransitionTarget.Source.MiniPlayer) {
                                miniPlayerCoverTargetForTransition?.radiusDp
                                    ?: currentMiniPlayerArtworkRadiusDp()
                            } else 24f
                        val current = playerController?.currentSong?.value
                        CoverTransitionTarget(
                            bounds = android.graphics.RectF(bounds),
                            radiusDp = radius,
                            source = source,
                            songId = current?.id ?: -1L,
                            coverKey = transitionCoverPath.orEmpty()
                        )
                    }
                }
                // Keep the committed lyric tree alive while a requested track is fading out.
                // Publishing null here disposed every mini-lyric row, producing a full-frame
                // flash before the target track's asynchronous lyrics arrived.
                val lyricSong = lyricsCoordinator.lyricSong
                val displayTranslation = lyricsCoordinator.displayTranslation
                val displayRoma = lyricsCoordinator.displayRoma
                val queueSongs = queue.songs
                val queueCurrentIndex = queue.currentIndex
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    com.rawsmusic.separation.AiRealtimeSeparationController.initialize(
                        this@MainActivity
                    )
                }
                val realtimeSeparationState by
                    com.rawsmusic.separation.AiRealtimeSeparationController.state.collectAsState()
                val aiPerformanceController = androidx.compose.runtime.remember {
                    com.rawsmusic.ai.variant.AiPerformanceFeatureController.get(this@MainActivity)
                }
                val aiPerformanceState by aiPerformanceController.state.collectAsState()
                androidx.compose.runtime.LaunchedEffect(
                    currentSong?.path,
                    currentSong?.cueOffsetMs,
                    currentSong?.cueTrackIndex,
                    committedArtworkKey,
                    prewarmedArtworkTransitionState,
                ) {
                    // AI analysis/binding is not part of the retained three-holder visual motion.
                    // Keep the real player/background identity immediate, but postpone this
                    // heavyweight side task until the exact committed artwork is the settled
                    // physical current holder so it cannot steal several UI frames from a page
                    // move.
                    if (currentSong != null && !committedArtworkKey.isNullOrBlank()) {
                        snapshotFlow {
                            !prewarmedArtworkTransitionState.isMotionActive &&
                                prewarmedArtworkTransitionState.foregroundCurrentKey() == committedArtworkKey
                        }.first { it }
                    }
                    aiPerformanceController.bindSong(currentSong)
                }
                com.rawsmusic.core.ui.widget.ComposePlayerContainer(
                    sceneState = playerSceneState,
                    currentSong = currentSong,
                    artworkTransitionState = prewarmedArtworkTransitionState,
                    coverPath = transitionCoverPath,
                    previousGestureArtworkKey = playerController?.previewPreviousSong(queue)?.let { song ->
                        song.resolvePlaybackArtworkKey(resolveSongCoverForCompose(song))
                    },
                    nextGestureArtworkKey = playerController?.previewNextSong(queue)?.let { song ->
                        song.resolvePlaybackArtworkKey(resolveSongCoverForCompose(song))
                    },
                    isPlaying = !displayingRequestedSong && playState == PlayState.PLAYING,
                    currentPositionMs = displayPositionMs,
                    currentPositionState = localStandardPositionState,
                    totalDurationMs = effectiveDurationMs,
                    audioVisualizerEnabled = composeAudioVisualizerEnabled,
                    audioVisualizerStyle = composeAudioVisualizerStyle,
                    audioVisualizerParticleColorMode = composeAudioVisualizerParticleColorMode,
                    audioSpectrum = playerAudioSpectrum,
                    audioSpectrumState = localStandardAudioSpectrumState,
                    onAudioVisualizerDismiss = {
                        setAudioVisualizerEnabled(false, "overlay_dismiss")
                    },
                    onAudioVisualizerEnabledChange = { enabled ->
                        setAudioVisualizerEnabled(enabled, "player_more_toggle")
                    },
                    onAudioVisualizerStyleChange = { style ->
                        AppPreferences.UI.audioVisualizerStyle = style.value
                        composeAudioVisualizerStyle = style
                    },
                    onAudioVisualizerRuntimeActiveChange = { active ->
                        visualizerUiRequested = active
                        if (!active) {
                            audioVisualizerHapticController.reset()
                        }
                        updateAudioVisualizerRuntimeState("compose_visibility")
                    },
                    previousIconRes = R.drawable.ic_rewind_fill,
                    playIconRes = R.drawable.ic_play,
                    pauseIconRes = R.drawable.ic_pause,
                    nextIconRes = R.drawable.ic_speed_fill,
                    playModeIconRes = playModeIconRes(playMode),
                    moreIconRes = R.drawable.ic_more_vert,
                    audioQualityIconRes = R.drawable.ic_equalizer_bars,
                    audioInfoText = audioInfoCapsuleHelper.capsuleText,
                    audioInfoPlaybackChainText = audioInfoCapsuleHelper.playbackChainText,
                    smartTransitionVisible = autoTransitionPresentation.visible,
                    onSeekStart = {
                        beginProgressSeek()
                    },
                    onSeekStop = { fraction ->
                        val seekPos = (fraction * effectiveDurationMs).toLong()
                        startSeekUiHold(seekPos)
                        lyricsNeedSeekTo = true
                        playerController?.seekTo(seekPos)
                        endProgressSeek()
                    },
                    onPrevious = {
                        // Transport-only callback. ComposePlayerContainer arms the exact manual
                        // identity here; renderer commitment later starts the retained three-holder
                        // programmatic motion, matching the retained pager ownership boundary.
                        if (currentSong == null) null
                        else dispatchPlayerTransportAction("player_previous") { it.previous() }
                    },
                    onPlayPause = {
                        dispatchPlayerTransportAction("player_play_pause") { it.playPause() }
                    },
                    onNext = {
                        if (currentSong == null) null
                        else dispatchPlayerTransportAction("player_next") { it.next() }
                    },
                    onArtworkGesturePrevious = {
                        if (currentSong == null) {
                            AppNoticeBus.error(getString(R.string.ui_no_current_song))
                            null
                        } else {
                            dispatchPlayerTransportAction("artwork_previous", traceDispatch = false) {
                                it.previousTrackFromArtworkGesture()
                            }.also { result ->
                                if (result == null) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.ui_playback_list_finished),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    },
                    onArtworkGestureNext = {
                        if (currentSong == null) {
                            AppNoticeBus.error(getString(R.string.ui_no_current_song))
                            null
                        } else {
                            dispatchPlayerTransportAction("artwork_next", traceDispatch = false) {
                                it.nextTrackFromArtworkGesture()
                            }.also { result ->
                                if (result == null) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        getString(R.string.ui_playback_list_finished),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            }
                        }
                    },
                    onPlayMode = {
                        playerController?.let { ctrl ->
                            ctrl.setPlayMode(PlayMode.quickCycle(ctrl.playMode.value))
                            playModePopupHelper.updatePlayModeIcon(ctrl.playMode.value)
                        }
                    },
                    onPlayModeLongPress = { playModePopupHelper.show() },
                    onMore = {},
                    onOpenMetadata = { metadataDetailHelper.open() },
                    onOpenAudioEffects = ::openAudioEffectsFromPlayer,
                    onOpenSpectrumAnalysis = {
                        composePlayerModalDismissAction?.invoke()
                        currentSong?.let { song ->
                            startActivity(
                                com.rawsmusic.ui.analysis.AudioSpectrumAnalysisActivity.createIntent(
                                    this@MainActivity,
                                    song
                                )
                            )
                        }
                    },
                    onPlaybackSpeedChange = { speed ->
                        playerController?.setPlaybackSpeed(speed)
                    },
                    realtimeSeparationEnabled = realtimeSeparationState.enabled,
                    realtimeSeparationPreparing = realtimeSeparationState.preparing,
                    realtimeSeparationStem =
                        if (
                            realtimeSeparationState.stem ==
                            com.rawsmusic.separation.AiSeparationStem.VOCALS
                        ) 0 else 1,
                    realtimeSeparationStrength = realtimeSeparationState.strength,
                    realtimeSeparationStatus =
                        realtimeSeparationState.error.ifBlank {
                            "phase:${realtimeSeparationState.phase.name}"
                        },
                    aiPerformanceState = aiPerformanceState,
                    onAiPerformanceModeChange = { mode ->
                        val song = currentSong
                        if (song != null) {
                            lifecycleScope.launch {
                                aiPerformanceController.selectMode(song, mode)
                            }
                        }
                    },
                    onAiPerformanceInstrumentChange = { instrumentKey ->
                        val song = currentSong
                        if (song != null) {
                            lifecycleScope.launch {
                                aiPerformanceController.selectInstrument(song, instrumentKey)
                            }
                        }
                    },
                    onAiPerformanceCancel = {
                        currentSong?.let(aiPerformanceController::cancel)
                    },
                    onAiPerformanceImportMelodyModel = { uriText ->
                        lifecycleScope.launch {
                            val result = aiPerformanceController.installRecommendedMelodyModel(
                                android.net.Uri.parse(uriText)
                            )
                            result.fold(
                                onSuccess = {
                                    AppNoticeBus.post(
                                        message = "RMVPE 旋律模型已安装",
                                        icon = AppNoticeIcon.AI,
                                    )
                                },
                                onFailure = {
                                    AppNoticeBus.error(it.message ?: "RMVPE 模型安装失败")
                                },
                            )
                            aiPerformanceController.bindSong(currentSong)
                        }
                    },
                    onAiPerformanceImportInstrumentPack = { uriText ->
                        lifecycleScope.launch {
                            val result = aiPerformanceController.installInstrumentPack(
                                android.net.Uri.parse(uriText)
                            )
                            result.fold(
                                onSuccess = {
                                    AppNoticeBus.post(
                                        message = "乐器采样包已安装",
                                        icon = AppNoticeIcon.AI,
                                    )
                                },
                                onFailure = {
                                    AppNoticeBus.error(it.message ?: "乐器包安装失败")
                                },
                            )
                            aiPerformanceController.bindSong(currentSong)
                        }
                    },
                    onRealtimeSeparationEnabledChange = { enabled ->
                        val result =
                            com.rawsmusic.separation.AiRealtimeSeparationController.setEnabled(
                                context = this@MainActivity,
                                enabled = enabled,
                            )
                        result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }?.let {
                            AppNoticeBus.error(it)
                        }
                    },
                    onRealtimeSeparationStemChange = { stem ->
                        com.rawsmusic.separation.AiRealtimeSeparationController.setStem(
                            if (stem == 0) {
                                com.rawsmusic.separation.AiSeparationStem.VOCALS
                            } else {
                                com.rawsmusic.separation.AiSeparationStem.INSTRUMENTAL
                            }
                        )
                    },
                    onRealtimeSeparationStrengthChange = { strength ->
                        com.rawsmusic.separation.AiRealtimeSeparationController.setStrength(
                            strength
                        )
                    },
                    onPlayerStyleChange = { immersive ->
                        if (composeImmersiveEnabled != immersive) {
                            composePlayerModalDismissAction?.invoke()
                            AppPreferences.UI.isImmersiveEnabled = immersive
                            composeImmersiveEnabled = immersive
                            playerSceneController.refreshImmersiveState(immersive)
                            syncImmersiveBackgroundSettings()
                        }
                    },
                    onOpenLandscapePlayer = {
                        composePlayerModalDismissAction?.invoke()
                        startActivity(LandscapePlayerActivity.createIntent(this@MainActivity))
                        overridePendingTransition(0, 0)
                    },
                    onLyricModifyAlbumArt = {
                        songActionSheetHelper.onPickCoverImage?.invoke()
                    },
                    sleepTimerSelection = sleepTimerSelection,
                    onSleepTimerSelectionChange = { index ->
                        if (index == com.rawsmusic.core.ui.widget.player.SLEEP_TIMER_OPEN_PANEL_REQUEST) {
                            // The More card is only the launcher. Dismiss it before mounting the
                            // bottom editor so there is never a dropdown/dialog stacked above it.
                            composePlayerModalDismissAction?.invoke()
                            sleepTimerUiMode = playerController?.getSleepTimerMode() ?: 0
                            sleepTimerCustomDurationMs = AppPreferences.Player.sleepTimerCustomDurationMs
                            sleepTimerExitWhenFinished =
                                AppPreferences.Player.sleepTimerExitPlayerWhenFinished
                            showSleepTimerSheet = true
                        } else {
                            playerController?.let { controller ->
                                when (index) {
                                    0 -> {
                                        controller.cancelSleepTimer()
                                        sleepTimerUiMode = 0
                                        sleepTimerUiSelection = 0
                                    }
                                    1 -> controller.startSleepTimer(10)
                                    2 -> controller.startSleepTimer(15)
                                    3 -> controller.startSleepTimer(20)
                                    4 -> controller.startSleepTimer(30)
                                    5 -> controller.startSleepTimer(45)
                                    6 -> controller.startSleepTimer(60)
                                    7 -> controller.startSleepTimer(90)
                                    8 -> controller.enableStopAfterCurrent()
                                    9 -> controller.startSleepTimerSongs(3)
                                    10 -> controller.startSleepTimerSongs(5)
                                }
                                if (index in 1..10) {
                                    sleepTimerUiMode = when (index) {
                                        in 1..7 -> 1
                                        8 -> 3
                                        else -> 2
                                    }
                                    sleepTimerUiSelection = index
                                }
                            }
                        }
                    },
                    onAudioQuality = {
                        audioInfoCapsuleHelper.cycleCapsule()
                    },
                    onAudioQualityLongPress = {
                        audioInfoCapsuleHelper.showInfoPopup()
                    },
                    onOpenLyric = {
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.openLyricPage(true)
                        }
                    },
                    onPlayerCoverSwipeUpStart = {
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.startCoverSwipeUpDrag(
                                PlayerSceneController.Scene.PLAYER,
                                PlayerSceneController.Scene.LYRIC
                            )
                        }
                    },
                    onPlayerCoverSwipeUpProgress = { ratio ->
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.updateCoverSwipeUpDrag(ratio)
                        }
                    },
                    onPlayerCoverSwipeUpEnd = { commit, velocity ->
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.endCoverSwipeUpDrag(commit, velocity = velocity)
                        }
                    },
                    onPlayerCoverSwipeDownStart = {
                        if (::playerSceneController.isInitialized) {
                            // Persistent-sheet drag does not pass through onPreparePlayerToMain.
                            // Prepare the exact same MiniPlayer-only endpoint synchronously before
                            // the first drag frame, otherwise the entry-time ListCover can survive
                            // into a floating PLAYER -> MAIN gesture.
                            preparePlayerToMainMiniPlayerEndpoint()
                            playerSceneController.startCoverDrag(PlayerSceneController.Scene.MAIN)
                        }
                    },
                    onPlayerCoverSwipeDownProgress = { ratio ->
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.updateCoverDrag(ratio)
                        }
                    },
                    onPlayerCoverSwipeDownEnd = { commit, velocity ->
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.endCoverDrag(commit, velocity = velocity)
                        }
                    },
                    onMainPlayerSheetGeometryChanged = { travelPx, parentWidthPx ->
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.updateMainPlayerSheetGeometry(
                                travelPx = travelPx,
                                parentWidthPx = parentWidthPx,
                            )
                        }
                    },
                    onLyricCoverSwipeDownStart = {
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.startLyricToPlayerDrag()
                        }
                    },
                    onLyricCoverSwipeDownProgress = { ratio ->
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.updateCoverSwipeUpDrag(ratio)
                        }
                    },
                    onLyricCoverSwipeDownEnd = { commit, velocity ->
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.endLyricToPlayerDrag(commit, velocity = velocity)
                        }
                    },
                    queueSongs = queueSongs,
                    queueCurrentIndex = queueCurrentIndex,
                    onQueueSongClick = { song, index ->
                        // The queue sheet renders one combined snapshot. Keep that exact identity/index
                        // pair for playback instead of translating it back through two mutable queues.
                        primePlayerUi(song)
                        playerController?.clearPriorityQueue()
                        playerController?.play(song, queueSongs, index)
                    },
                    onClearPriorityQueue = { playerController?.clearPriorityQueue() },
                    albumSongs = playerAlbumSongs,
                    albumCoverPath = playerAlbumCoverPath,
                    onAlbumSongClick = { song, index ->
                        primePlayerUi(song)
                        playerController?.play(song, playerAlbumSongs, index)
                    },
                    lyricSong = lyricSong,
                    lyricPositionMs = displayLyricPositionMs,
                    lyricPositionState = localStandardLyricPositionState,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    onLyricSeek = { ms ->
                        val targetMs = lyricsCoordinator.lyricToPlaybackPosition(ms)
                        lyricsNeedSeekTo = true
                        startSeekUiHold(targetMs)
                        playerController?.seekTo(targetMs)
                    },
                    onLyricTranslationToggle = {
                        lyricsCoordinator.toggleTranslation()
                    },
                    onLyricRomaToggle = {
                        lyricsCoordinator.toggleRoma()
                    },
                    onSearchLyrico = ::launchLyricoOnlineSearch,
                    onOpenInLyrico = ::launchCurrentSongInLyrico,
                    onAiTimingPreview = {
                        aiTimingPreviewSong = currentSong
                        showAiTimingPreview = true
                    },
                    onOpenExternalTimingEditor = ::launchExternalTimingEditor,
                    isImmersiveEnabled = composeImmersiveEnabled,
                    persistentBottomSheet = persistentNormalPlayerSheet,
                    prewarmStandardPlayerInMain = prewarmStandardPlayerSurface,
                    overlaySuspended = showSleepTimerSheet,
                    onClosePlayer = {
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.closeCurrentPlayerStackToMain(true)
                        }
                    },
                    onBackToPlayer = {
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.closeLyricPage(true)
                        }
                    },
                    onModalVisibleChange = { visible ->
                        composePlayerModalVisible = visible
                        gestureLockCoordinator.set(GestureLockReason.PlayerModal, visible)
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.disableGestureIntercept = visible
                        }
                    },
                    onModalDismissActionChange = { dismissAction ->
                        composePlayerModalDismissAction = dismissAction
                    },
                    controllerScene = playerSceneController.composeCurrentScene,
                    controllerFromScene = playerSceneController.composeFromScene,
                    controllerToScene = playerSceneController.composeToScene,
                    controllerProgress = if (
                        !composeImmersiveEnabled &&
                        playerSceneController.composeIsTransitioning &&
                        (
                            (playerSceneController.composeFromScene == PlayerSceneController.Scene.MAIN &&
                                playerSceneController.composeToScene == PlayerSceneController.Scene.PLAYER) ||
                                (playerSceneController.composeFromScene == PlayerSceneController.Scene.PLAYER &&
                                    playerSceneController.composeToScene == PlayerSceneController.Scene.MAIN) ||
                                PlayerLyricsTransitionCoordinator.isPlayerLyricsPair(
                                    playerSceneController.composeFromScene,
                                    playerSceneController.composeToScene,
                                )
                            )
                    ) {
                        // Standard MAIN<->PLAYER and PLAYER<->LYRIC both expose stable State<Float>
                        // owners downstream. Do not sample their per-frame progress in the Activity
                        // composition; RenderNode/draw callbacks consume the live state directly.
                        // Immersive pages still own a separate sampled horizontal-scene path.
                        0f
                    } else {
                        playerSceneController.composeTransitionProgress
                    },
                    controllerProgressState = playerSceneController.mainPlayerSheetExpansionState,
                    controllerIsTransitioning = playerSceneController.composeIsTransitioning,
                    controllerIsInteractiveGesture =
                        playerSceneController.composeIsInteractiveGesture,
                    playerLyricsTransitionCoordinator =
                        playerSceneController.playerLyricsTransitionCoordinator,
                    sourceCoverTarget = playerSharedSourceTarget,
                    miniPlayerBoundsInRoot = if (playerSceneController.composeIsTransitioning) {
                        lockedMiniPlayerBoundsForTransition
                            ?: stableMiniPlayerBoundsForSceneGesture
                            ?: miniPlayerGestureBoundsForSceneGesture
                    } else {
                        stableMiniPlayerBoundsForSceneGesture
                            ?: miniPlayerGestureBoundsForSceneGesture
                    },
                    floatingMiniPlayerTopPx = if (
                        !persistentNormalPlayerSheet && usesFloatingPlayerBar()
                    ) {
                        // Keep the floating sheet's physical collapsed anchor available even while
                        // PLAYER is stably expanded. The stationary parent recognizer needs this
                        // geometry *before* the first downward MOVE so it can capture the gesture;
                        // gating it on an already-started return leaves AlbumArtCard as the owner
                        // for the capture frame and reintroduces the moving-child coordinate loop.
                        // During a transition the locked/stable value remains immutable, matching
                        // Keep parity with draggable-sheet child geometry.
                        lockedFloatingMiniPlayerTopPxForTransition
                            ?: stableFloatingMiniPlayerTopPx
                            ?: miniPlayerGestureBoundsForSceneGesture?.top
                    } else null,
                    modifier = Modifier.fillMaxSize()
                )
                } // PlayerDismissMotionHost
            }
            com.rawsmusic.core.ui.widget.player.SleepTimerBottomSheet(
                show = showSleepTimerSheet,
                mode = sleepTimerUiMode,
                remainingMs = sleepTimerRemainingForPanel,
                fixedSelection = sleepTimerUiSelection,
                customDurationMs = sleepTimerCustomDurationMs,
                exitWhenFinished = sleepTimerExitWhenFinished,
                onDismiss = { showSleepTimerSheet = false },
                onCancelTimer = {
                    playerController?.cancelSleepTimer()
                    sleepTimerUiMode = 0
                    sleepTimerUiSelection = 0
                },
                onFixedSelection = { index ->
                    playerController?.let { controller ->
                        when (index) {
                            1 -> controller.startSleepTimer(10)
                            2 -> controller.startSleepTimer(15)
                            3 -> controller.startSleepTimer(20)
                            4 -> controller.startSleepTimer(30)
                            5 -> controller.startSleepTimer(45)
                            6 -> controller.startSleepTimer(60)
                            7 -> controller.startSleepTimer(90)
                            8 -> controller.enableStopAfterCurrent()
                            9 -> controller.startSleepTimerSongs(3)
                            10 -> controller.startSleepTimerSongs(5)
                        }
                        sleepTimerUiMode = when (index) {
                            in 1..7 -> 1
                            8 -> 3
                            in 9..10 -> 2
                            else -> sleepTimerUiMode
                        }
                        if (index in 1..10) sleepTimerUiSelection = index
                    }
                },
                onCustomDuration = { durationMs ->
                    val normalized = durationMs.coerceIn(1_000L, 600L * 60_000L)
                    sleepTimerCustomDurationMs = normalized
                    AppPreferences.Player.sleepTimerCustomDurationMs = normalized
                    playerController?.startCustomSleepTimer(normalized)
                    sleepTimerUiMode = 4
                    sleepTimerUiSelection = 11
                },
                onExitWhenFinishedChange = { enabled ->
                    sleepTimerExitWhenFinished = enabled
                    AppPreferences.Player.sleepTimerExitPlayerWhenFinished = enabled
                },
            )
            MetadataDetailOverlay(helper = metadataDetailHelper)
            MetadataCardPopupOverlay(helper = metadataCardPopupHelper)
            PlayModePopupOverlay(helper = playModePopupHelper)
            DialogOverlay(helper = dialogHelper)
            MetadataEditorOverlay(helper = metadataEditorHelper)
            AudioInfoCapsuleOverlay(helper = audioInfoCapsuleHelper)
            if (overlayCoordinator.showFolderDialog) {
                com.rawsmusic.ui.folderfilter.MusicFoldersDialog(
                    onDismiss = { overlayCoordinator.showFolderDialog = false },
                    onFolderPickerLauncher = {
                        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
                        folderPickerLauncher.launch(intent)
                    },
                    pendingFolderUri = folderPickerResultUri,
                    onFolderUriConsumed = { folderPickerResultUri = null }
                )
            }
        }
    }

    private fun isPlayerUiVisibleForPower(): Boolean {
        if (!activityForegroundForPower || !::playerSceneController.isInitialized) return false
        return playerSceneController.currentScene == PlayerSceneController.Scene.PLAYER ||
            playerSceneController.currentScene == PlayerSceneController.Scene.LYRIC ||
            playerSceneController.isTransitioning
    }

    private fun setAudioVisualizerEnabled(enabled: Boolean, reason: String) {
        if (enabled && !audioPermissionHelper.isVisualizerPermissionGranted()) {
            pendingVisualizerEnableReason = reason
            visualizerPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        applyAudioVisualizerEnabled(enabled, reason)
    }

    private fun applyAudioVisualizerEnabled(enabled: Boolean, reason: String) {
        visualizerCoordinator.applyEnabled(enabled, reason)
    }

    private fun syncAudioVisualizerPreference(reason: String) {
        composeAudioVisualizerStyle =
            com.rawsmusic.core.ui.widget.player.AudioVisualizerStyle.from(
                AppPreferences.UI.audioVisualizerStyle
            )
        composeAudioVisualizerHapticsEnabled =
            AppPreferences.UI.audioVisualizerHapticsEnabled
        composeAudioVisualizerParticleColorMode =
            com.rawsmusic.core.ui.widget.player.AudioVisualizerParticleColorMode.from(
                AppPreferences.UI.audioVisualizerParticleColorMode
            )
        visualizerCoordinator.syncPreference(reason)
    }

    private fun updateAudioVisualizerRuntimeState(reason: String) {
        visualizerCoordinator.updateRuntime(reason)
    }


    private fun updateComposeRootVisibility(forceVisible: Boolean = false) {
        overlayCoordinator.refresh(
            forceVisible = forceVisible,
            songActionSheetVisible = songActionSheetHelper.isSongActionSheetShowing,
            playlistPickerVisible = songActionSheetHelper.isPlaylistPickerShowing,
            metadataDetailVisible = metadataDetailHelper.isVisible,
            metadataEditorVisible = metadataEditorHelper.isMetadataEditorShowing,
            metadataDeleteConfirmVisible = metadataEditorHelper.isDeleteConfirmShowing,
            audioInfoVisible = audioInfoCapsuleHelper.isPopupShowing,
            metadataCardVisible = metadataCardPopupHelper.isShowing,
            playModeVisible = playModePopupHelper.isShowing,
            dialogVisible = dialogHelper.isShowing
        )
    }

    private fun showUsbVolumeOverlay(text: String) {
        overlayCoordinator.showUsbVolume(text)
        mainHandler.removeCallbacks(usbVolumeHideRunnable)
        mainHandler.postDelayed(usbVolumeHideRunnable, 1500L)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (usbVolumeKeyHandler.handleKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    @Composable
    private fun UsbVolumeOverlay(
        visible: Boolean,
        text: String,
        modifier: Modifier = Modifier
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(120)),
            exit = fadeOut(tween(250)),
            modifier = modifier.padding(top = 96.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(ComposeColor(0xAA000000), RoundedCornerShape(24.dp))
                    .padding(horizontal = 24.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = ComposeColor.White,
                    fontSize = 14.sp
                )
            }
        }
    }

    private fun syncComposePlayerScene(scene: PlayerSceneController.Scene) {
        when (scene) {
            PlayerSceneController.Scene.MAIN -> {
                playerSceneState.switchToSilent(com.rawsmusic.core.ui.widget.PlayerScene.MAIN)
            }
            PlayerSceneController.Scene.PLAYER -> {
                val current = playerSceneState.currentScene
                if (current == com.rawsmusic.core.ui.widget.PlayerScene.MAIN ||
                    current == com.rawsmusic.core.ui.widget.PlayerScene.LYRIC
                ) {
                    playerSceneState.switchToSilent(com.rawsmusic.core.ui.widget.PlayerScene.PLAYER)
                }
            }
            PlayerSceneController.Scene.LYRIC -> {
                playerSceneState.switchToSilent(com.rawsmusic.core.ui.widget.PlayerScene.LYRIC)
            }
            PlayerSceneController.Scene.QUEUE -> {
                playerSceneState.switchToSilent(com.rawsmusic.core.ui.widget.PlayerScene.QUEUE)
            }
            PlayerSceneController.Scene.ALBUM_DETAIL -> {
                playerSceneState.switchToSilent(com.rawsmusic.core.ui.widget.PlayerScene.ALBUM_DETAIL)
            }
        }
        updateComposeRootVisibility()
    }

    private fun playModeIconRes(playMode: PlayMode): Int = when (playMode) {
        PlayMode.SEQUENTIAL -> R.drawable.ic_order_play_fill
        PlayMode.SHUFFLE_ALL,
        PlayMode.SHUFFLE_ONCE -> com.rawsmusic.core.ui.R.drawable.ic_shuffle_custom
        PlayMode.REPEAT_ONE -> R.drawable.ic_repeat_one_fill
    }

    private fun initObserver() {
        coverCoordinator.start()
        observePlayerThroughCoordinator()
    }

    private fun observePlayerThroughCoordinator() {
        playbackObserverCoordinator.start()
    }

    private fun handleObservedPlaybackPosition(positionMs: Long, durationMs: Long) {
        if (isSeekUiHolding && seekTargetMs >= 0L) {
            val tolerance = (durationMs * 0.02f).toLong().coerceIn(300L, 2000L)
            val elapsed = System.currentTimeMillis() - seekFinishTimeMs
            if (kotlin.math.abs(positionMs - seekTargetMs) < tolerance || elapsed > 2000L) {
                stopSeekUiHold()
            }
        }
        playbackCoordinator.onPositionChanged(positionMs, durationMs)
    }

    /** 启动跑马灯效果（focusable + requestFocus） */
    private fun initListener() {}

    fun openPlayPageFromSongClick() {
        // List-row selection changes playback only. Opening PLAYER is owned by the MiniPlayer or
        // an explicit player navigation action, never by selecting a song row.
    }

    fun navigateToFolderFromSearch(folderPath: String) {
        try {
            updateComposeRootVisibility(true)
            mainNavState.navigateTo(
                com.rawsmusic.core.ui.scene.NavScene.FOLDER_DETAIL,
                folderPath
            )
        } catch (_: Exception) {}
    }



    /**
     * Idempotent cold-start barrier for every path that can mount or direct-manipulate PLAYER.
     * It restores queue topology as well as the selected song; returning only a previously seeded
     * currentSong is not sufficient because artwork/gesture neighbours come from visibleQueue.
     */
    private fun restorePlayerUiStateForNavigation(reason: String): AudioFile? {
        val controller = playerController ?: return null
        val started = SystemClock.uptimeMillis()
        val restored = controller.restoreLastSong()
        val resolved = controller.currentOrRequestedSongForUi() ?: restored
        val elapsed = SystemClock.uptimeMillis() - started
        if (elapsed > 8L) {
            AppLogger.d(
                "Startup",
                "player UI restore barrier reason=$reason elapsed=${elapsed}ms " +
                    "queue=${controller.visibleQueue.value.songs.size} song=${resolved?.title.orEmpty()}",
            )
        }
        return resolved
    }

    private fun openPlayPageWithSharedElement(preferMiniPlayerSource: Boolean = false) {
        if (playerSceneController.currentScene != PlayerSceneController.Scene.MAIN) return

        val uiSong = restorePlayerUiStateForNavigation("open_player") ?: return
        val uiCoverPath = resolveSongCoverForCompose(uiSong)

        prePlayerContainerScene = mainNavState.currentScene
        if (isSideMenuOpen) closeSideMenu()

        // Commit one immutable media identity before mounting the player. Reusing the previous
        // transition lock can briefly combine the new title with the old artwork/background.
        lockedPlayerCoverPathForTransition = uiCoverPath
        updateComposeRootVisibility(true)
        registerCoverCollapseParams()
        // Resolve MiniPlayer artwork from its live geometry on every sheet
        // slide. NORMAL always uses that source; floating MiniPlayer taps explicitly request the
        // same behavior so stacked navigation cannot make the transition originate from a list
        // cell or an old fixed region.
        // Floating and NORMAL modes both use the MiniPlayer as the sole MAIN<->PLAYER shared
        // artwork endpoint. ListCover remains a normal list cell only; it never participates in
        // this transition pipeline, so it cannot compete with the playback bar on either edge.
        val useMiniPlayerSource = usesNormalPlayerSheet() || usesFloatingPlayerBar() || preferMiniPlayerSource
        lockedMiniPlayerBoundsForTransition = stableMiniPlayerBoundsForSceneGesture
            ?: miniPlayerGestureBoundsForSceneGesture
        floatingReturnOwnsMiniPlayerTarget = false
        if (useMiniPlayerSource && !usesNormalPlayerSheet()) {
            lockedFloatingMiniPlayerTopPxForTransition = stableFloatingMiniPlayerTopPx
                ?: miniPlayerGestureBoundsForSceneGesture?.top
        }
        val exactMiniTarget = if (useMiniPlayerSource) {
            resolveCurrentMiniPlayerCoverTarget(uiSong, uiCoverPath)
        } else null
        val hasCoverBounds = useMiniPlayerSource ||
            mainNavState.currentScene == com.rawsmusic.core.ui.scene.NavScene.SONGS ||
            miniPlayerCoverBoundsForTransition != null
        val entryBounds = if (hasCoverBounds) {
            if (useMiniPlayerSource) {
                exactMiniTarget?.bounds?.let { android.graphics.RectF(it) }
                    ?: miniPlayerCoverBoundsForTransition?.let { android.graphics.RectF(it) }
            } else {
                playingCoverBoundsForTransition?.let { android.graphics.RectF(it) }
                    ?: miniPlayerCoverBoundsForTransition?.let { android.graphics.RectF(it) }
            }
        } else null
        lockedPlayerCoverBoundsForTransition = entryBounds?.let { android.graphics.RectF(it) }
        coverTargetForTransition = if (entryBounds != null) {
            if (useMiniPlayerSource) {
                exactMiniTarget
                    ?.copy(bounds = android.graphics.RectF(entryBounds))
                    ?: CoverTransitionTarget(
                        bounds = android.graphics.RectF(entryBounds),
                        radiusDp = miniPlayerCoverTargetForTransition?.radiusDp
                            ?: currentMiniPlayerArtworkRadiusDp(),
                        source = CoverTransitionTarget.Source.MiniPlayer,
                        songId = uiSong.id,
                        coverKey = uiCoverPath,
                    )
            } else coverTargetForTransition
                ?.takeIf { target ->
                    target.bounds.nearlyEquals(entryBounds, 2f) &&
                        (target.songId < 0L || target.songId == uiSong.id) &&
                        (target.coverKey.isBlank() || target.coverKey == uiCoverPath)
                }
                ?.copy(
                    bounds = android.graphics.RectF(entryBounds),
                    songId = uiSong.id,
                    coverKey = uiCoverPath
                )
                ?: CoverTransitionTarget(
                    bounds = android.graphics.RectF(entryBounds),
                    radiusDp = if (!usesNormalPlayerSheet() && playingCoverBoundsForTransition != null) {
                        24f
                    } else {
                        currentMiniPlayerArtworkRadiusDp()
                    },
                    source = if (!usesNormalPlayerSheet() && playingCoverBoundsForTransition != null) {
                        CoverTransitionTarget.Source.ListCover
                    } else {
                        CoverTransitionTarget.Source.MiniPlayer
                    },
                    songId = uiSong.id,
                    coverKey = uiCoverPath
                )
        } else {
            null
        }
        playerSceneController.openPlayPage(true)
        updatePredictiveBackRegistration()
    }

    /** Prepares the same shared-cover source as a tap, without committing the player scene. */
    private fun preparePlayPageForBottomSheetDrag() {
        if (playerSceneController.currentScene != PlayerSceneController.Scene.MAIN) return
        val uiSong = restorePlayerUiStateForNavigation("prepare_player_drag") ?: return
        val uiCoverPath = resolveSongCoverForCompose(uiSong)

        prePlayerContainerScene = mainNavState.currentScene
        if (isSideMenuOpen) closeSideMenu()
        lockedPlayerCoverPathForTransition = uiCoverPath
        updateComposeRootVisibility(true)
        registerCoverCollapseParams()
        lockedMiniPlayerBoundsForTransition = stableMiniPlayerBoundsForSceneGesture
            ?: miniPlayerGestureBoundsForSceneGesture
        floatingReturnOwnsMiniPlayerTarget = false
        if (!usesNormalPlayerSheet()) {
            lockedFloatingMiniPlayerTopPxForTransition = stableFloatingMiniPlayerTopPx
                ?: miniPlayerGestureBoundsForSceneGesture?.top
        }

        // Normal style has one persistent bottom sheet. Its mini artwork is the only valid
        // source; borrowing a list cell makes two independently measured covers fight for the
        // shared position while the sheet is dragged.
        val exactMiniTarget = resolveCurrentMiniPlayerCoverTarget(uiSong, uiCoverPath)
        val entryBounds = exactMiniTarget?.bounds?.let { android.graphics.RectF(it) }
            ?: miniPlayerCoverBoundsForTransition?.let { android.graphics.RectF(it) }
        lockedPlayerCoverBoundsForTransition = entryBounds
        coverTargetForTransition = exactMiniTarget
            ?: entryBounds?.let { bounds ->
                CoverTransitionTarget(
                    bounds = android.graphics.RectF(bounds),
                    // This fallback is only used before the exact geometry callback arrives.
                    radiusDp = currentMiniPlayerArtworkRadiusDp(),
                    source = CoverTransitionTarget.Source.MiniPlayer,
                    songId = uiSong.id,
                    coverKey = uiCoverPath,
                )
            }
    }

    /**
     * Locks the PLAYER -> MAIN endpoint to the floating/persistent MiniPlayer.
     *
     * Both programmatic close and the persistent-sheet downward gesture must call this before
     * their first transition frame. The sheet drag path intentionally does not run
     * onPreparePlayerToMain, so keeping this logic only in that callback leaves the entry-time
     * ListCover as the active shared target during a gesture collapse.
     */
    private fun preparePlayerToMainMiniPlayerEndpoint() {
        registerCoverCollapseParams()
        updateComposeRootVisibility(true)
        prepareContainerForPlayerReturn()

        val current = playerController?.currentOrRequestedSongForUi()
        val currentCoverKey = current?.let { resolveSongCoverForCompose(it) }.orEmpty()
        val miniTarget = current?.let { song ->
            resolveStableMiniPlayerReturnTarget(song, currentCoverKey)
        }

        playerReturnRevealIndex = -1
        playingCoverBoundsForTransition = null
        lockedMiniPlayerBoundsForTransition = stableMiniPlayerBoundsForSceneGesture
            ?: miniPlayerGestureBoundsForSceneGesture
        floatingReturnOwnsMiniPlayerTarget = usesFloatingPlayerBar()
        if (usesFloatingPlayerBar()) {
            lockedFloatingMiniPlayerTopPxForTransition = stableFloatingMiniPlayerTopPx
                ?: miniPlayerGestureBoundsForSceneGesture?.top
        }
        coverTargetForTransition = miniTarget
        lockedPlayerCoverBoundsForTransition = miniTarget?.bounds?.let { android.graphics.RectF(it) }
    }

    private fun resolveStableMiniPlayerReturnTarget(
        song: AudioFile,
        coverKey: String,
    ): CoverTransitionTarget? {
        stableMiniPlayerCoverGeometryForTransition?.let { stable ->
            return stable.copyBounds().copy(
                source = CoverTransitionTarget.Source.MiniPlayer,
                songId = song.id,
                coverKey = coverKey,
            )
        }
        return resolveCurrentMiniPlayerCoverTarget(song, coverKey)
    }

    private fun currentMiniPlayerArtworkRadiusDp(): Float {
        val style = PersonalizationPreferences.miniPlayerStyle.value
        val mode = readStoredMiniPlayerArtworkMode(applicationContext)
        val effectiveArtworkSizeDp = if (usesFloatingPlayerBar()) {
            effectiveMiniPlayerArtworkSizeDp(
                requestedArtworkSizeDp = style.artworkSizeDp,
                expandedHeightDp = style.expandedHeightDp,
                compactHeightDp = style.compactHeightDp,
                mode = mode,
            )
        } else {
            effectiveMiniPlayerArtworkSizeDp(
                requestedArtworkSizeDp = style.artworkSizeDp,
                expandedHeightDp = 66f,
                compactHeightDp = 66f,
                mode = mode,
            )
        }
        return miniPlayerArtworkSourceRadiusDp(
            mode = mode,
            effectiveArtworkSizeDp = effectiveArtworkSizeDp,
            originalArtworkCornerRadiusDp = style.originalArtworkCornerRadiusDp,
        )
    }

    private fun resolveCurrentMiniPlayerCoverTarget(
        song: AudioFile,
        coverKey: String,
    ): CoverTransitionTarget? {
        miniPlayerCoverTargetForTransition
            ?.takeIf { it.isForSong(song.id, coverKey) }
            ?.let { target ->
                return target.copyBounds().copy(
                    songId = song.id,
                    coverKey = coverKey,
                )
            }
        return miniPlayerCoverBoundsForTransition?.let { bounds ->
            CoverTransitionTarget(
                bounds = android.graphics.RectF(bounds),
                radiusDp = currentMiniPlayerArtworkRadiusDp(),
                source = CoverTransitionTarget.Source.MiniPlayer,
                songId = song.id,
                coverKey = coverKey,
            )
        }
    }

    // Bottom-navigation styles now share one persistent MiniPlayer <-> PLAYER sheet.
    // The style enum is material-only; it must never select a different geometry/lifecycle owner.
    private fun usesNormalPlayerSheet(): Boolean =
        PersonalizationPreferences.isBottomNavigationEnabled

    // With bottom navigation disabled the remaining MiniPlayer is the standalone accessory used
    // beside the side rail. Keep the legacy floating-return route only for that layout class.
    private fun usesFloatingPlayerBar(): Boolean =
        !PersonalizationPreferences.isBottomNavigationEnabled

    // ==================== 封面手势处理 ====================

    private var currentLyricText = ""

    private fun updateHiresBadge() {
        if (!::playerSceneController.isInitialized) return
        audioCapsuleUiHelper.updateHiresBadge()
    }

    private fun isMusicSymbolOnly(text: String): Boolean {
        return audioCapsuleUiHelper.isMusicSymbolOnly(text)
    }

    private fun updateCapsuleText() {
        audioCapsuleUiHelper.updateText()
    }

    private fun openQueuePage() {
        if (playerSceneController.currentScene == PlayerSceneController.Scene.LYRIC) {
            playerSceneController.switchToSceneSilent(PlayerSceneController.Scene.PLAYER)
        }
        playerSceneState.openQueue()
        updateComposeRootVisibility(true)
    }

    private fun closeQueuePage() {
        playerSceneState.closeQueueOverlay()
        playerSceneState.backToPlayer()
        updateComposeRootVisibility()
    }

    private fun openAlbumDetailPage() {
        val song = playerController?.currentSong?.value ?: return
        loadAlbumDetail(song)
        playerSceneState.openAlbumDetail()
        updateComposeRootVisibility(true)
    }

    private fun closeAlbumDetailPage() {
        playerSceneState.backToPlayer()
        updateComposeRootVisibility()
    }

    private fun onImmersiveSwipeLeft() {
        launchImmersiveLyric()
    }

    private fun launchImmersiveLyric() {
        val song = playerController?.currentSong?.value
        if (song != null) {
            val intent = android.content.Intent(this, com.rawsmusic.ui.lyric.ImmersiveLyricActivity::class.java).apply {
                putExtra("song_title", song.title)
                putExtra("song_artist", song.artist)
                putExtra("song_path", song.path)
                putExtra("song_id", song.id)
            }
            startActivity(intent)
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.slide_out_right)
        }
    }

    private fun registerCoverCollapseParams() {
        registerMainShellParams()
    }

    private fun registerCoverLyricParams() {
        lyricBackgroundState.resumeAnimations()
    }

    private fun restoreDefaultSceneParams() {
        registerMainShellParams()
        if (playerSceneController.currentScene == PlayerSceneController.Scene.MAIN) {
            backgroundLayerState.playBackgroundVisible = false
            backgroundLayerState.playBackgroundAlpha = 0f
            backgroundLayerState.mainScrimVisible = true
            backgroundLayerState.mainScrimAlpha = 1f
        }
        syncImmersiveBackgroundSettings()
    }

    private fun registerMainShellParams() {
        // Background scene params are rendered by Compose BackgroundLayers.
    }

    private fun android.graphics.RectF.nearlyEquals(other: android.graphics.RectF?, tolerance: Float): Boolean {
        other ?: return false
        return kotlin.math.abs(left - other.left) <= tolerance &&
            kotlin.math.abs(top - other.top) <= tolerance &&
            kotlin.math.abs(right - other.right) <= tolerance &&
            kotlin.math.abs(bottom - other.bottom) <= tolerance
    }

    private fun loadAlbumDetail(song: com.rawsmusic.core.common.model.AudioFile) {
        val queueSongs = playerController?.visibleQueue?.value?.songs.orEmpty()
        val albumSongs = queueSongs.filter { it.albumId == song.albumId && it.albumId > 0 }
            .ifEmpty { queueSongs.filter { it.album == song.album && song.album.isNotBlank() } }
            .ifEmpty { listOf(song) }
        playerAlbumSongs = albumSongs
        playerAlbumCoverPath = coverUriResolver.resolveCoverUri(song).ifBlank { song.coverKey }
    }

    fun setPlayerController(controller: PlayerController) {
        playerControllerBindingHelper.bind(controller)
        PlayerHolder.controller = controller
    }

    fun toggleSideMenu() {
        isSideMenuOpen = !isSideMenuOpen
    }

    private fun loadLyrics(songPath: String) {
        lyricLoadHelper.load(songPath)
    }

    private fun setCurrentLyricDataForCompose(data: LyricData) {
        lyricStateCoordinator.update(data)
    }

    private fun requestAudioPermission() {
        val permissions = audioPermissionHelper.requiredStartupPermissions()
        if (audioPermissionHelper.areGranted(permissions)) {
            scannerCoordinator.scheduleStartupScan()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun setupEdgeToEdge() {
        systemBarsHelper.setupEdgeToEdge(isDarkMode)
        systemBarsHelper.setStatusBarHidden(
            com.rawsmusic.module.data.prefs.AppPreferences.UI.isStatusBarHidden
        )
    }

    /** Delegates predictive-back ownership to the standalone scene coordinator. */
    private fun redispatchBackBelowSceneCallback() = predictiveBackCoordinator.redispatchBackBelowSceneCallback()

    private fun resetPredictiveBackGestureOwnership(reason: String) =
        predictiveBackCoordinator.resetGestureOwnership(reason)

    private fun setupPredictiveBack() = predictiveBackCoordinator.setup()

    private fun updatePredictiveBackRegistration(
        activeMiuixOverlayCount: Int = com.rawsmusic.core.ui.widget.MiuixOverlayBackRuntime.activeCount,
        activeSourcePortalBackCount: Int = com.rawsmusic.core.ui.scene.pages.SourcePortalBackRuntime.activeCount,
    ) = predictiveBackCoordinator.updateRegistration(activeMiuixOverlayCount, activeSourcePortalBackCount)


    /**
     */
    private fun restoreLastPlayingState() {
        lastPlayingStateHelper.restore()
    }

    private fun updateStatusBarForLevel(level: PlayerSceneController.Scene) {
        systemBarsHelper.updateForScene(level == PlayerSceneController.Scene.MAIN, isDarkMode)
    }

    /**
     * 同步加载镜像封面（沉浸式播放模式下的底部倒影）
     */
    private fun syncMirrorCover(coverUri: String?) {
        return
    }

    /**
     * 应用默认背景：亮色模式白底黑字，暗色模式纯黑底白字
     * 强制覆盖所有沉浸/封面背景层
     */
    private fun applyDefaultBackground() {
        coverBackgroundManager.applyDefaultBackground()

    }

    private fun applyLyricColors() {
        lyricStyleHelper.applyLyricColors()
        composeLyricIsLight = lyricBackgroundState.isLightBackground
    }

    /**
     * 处理返回键事件，根据当前页面层级决定是否退出应用
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // A MIUIX overlay owns this back event. This guard also makes the one-frame redispatch
        // fallback safe on library/device combinations where no lower callback is available.
        if (com.rawsmusic.core.ui.widget.MiuixOverlayBackRuntime.activeCount > 0) return
        if (com.rawsmusic.core.ui.scene.pages.SourcePortalBackRuntime.consumeSuppressedSceneBack()) return

        if (playerSceneState.isQueueOverlayVisible) {
            playerSceneState.closeQueueOverlay()
            if (::playerSceneController.isInitialized) {
                playerSceneController.disableGestureIntercept = false
            }
            return
        }
        // 纯 Compose 播放器返回处理
        // Compose 播放器辅助页面（歌词、队列等）的返回处理
        if (playerSceneState.currentScene != com.rawsmusic.core.ui.widget.PlayerScene.MAIN &&
            playerSceneState.currentScene != com.rawsmusic.core.ui.widget.PlayerScene.PLAYER &&
            playerSceneState.currentScene != com.rawsmusic.core.ui.widget.PlayerScene.LYRIC
        ) {
            playerSceneState.backToPlayer()
            updateComposeRootVisibility()
            return
        }
        if (::playerSceneController.isInitialized && playerSceneController.isTransitioning) {
            return
        }
        if (metadataDetailHelper.isVisible) {
            metadataDetailHelper.close()
            return
        }
        if (metadataEditorHelper.isMetadataEditorShowing) {
            metadataEditorHelper.dismissMetadataEditor()
            return
        }
        if (metadataEditorHelper.isDeleteConfirmShowing) {
            metadataEditorHelper.dismissDeleteConfirm()
            return
        }
        if (audioInfoCapsuleHelper.isPopupShowing) {
            audioInfoCapsuleHelper.dismissPopup()
            return
        }
        if (songActionSheetHelper.isPlaylistPickerShowing) {
            songActionSheetHelper.hidePlaylistPicker()
            return
        }
        if (playModePopupHelper.isShowing) {
            playModePopupHelper.hide()
            return
        }
        if (metadataCardPopupHelper.isShowing) {
            metadataCardPopupHelper.hide()
            return
        }
        if (isSideMenuOpen) {
            closeSideMenu()
            return
        }
        if (isSearchActive()) {
            closeSearch()
            return
        }
        if (audioInfoSharedWindowActive && mainNavState.canNavigateBack()) {
            mainNavState.navigateBackAnimated()
            return
        }
        if (!::playerSceneController.isInitialized) { super.onBackPressed(); return }
        when (playerSceneController.currentScene) {
            PlayerSceneController.Scene.LYRIC -> {
                playerSceneController.closeLyricPage(true)
                return
            }
            PlayerSceneController.Scene.QUEUE -> {
                closeQueuePage()
                return
            }
            PlayerSceneController.Scene.ALBUM_DETAIL -> {
                closeAlbumDetailPage()
                return
            }
            PlayerSceneController.Scene.PLAYER -> {
                // 与上滑手势保持一致：仅保存状态（如果尚未保存），然后执行带封面对齐的关闭动画
                // 不要提前调用 navigateHome()，否则会先闪一下主界面
                if (prePlayerFragmentDest == null) {
                    prePlayerFragmentDest = legacyDestinationId
                }
                if (prePlayerContainerScene == null && true) {
                    prePlayerContainerScene = mainNavState.currentScene
                }
                playerSceneController.closePlayPageWithCoverAlign(true)
                return
            }
            PlayerSceneController.Scene.MAIN -> {
                // Compose 子页面必须经过 SceneTransitionHost。直接 navigateHome() 会跳过
                // VirtualList 返场和共享元素插值，系统返回与页面返回按钮因此表现不一致。
                if (!mainNavState.isAtHome()) {
                    mainNavState.navigateBackAnimated()
                    return
                }
            }
        }
        moveTaskToBack(true)
    }

    private fun isSearchActive(): Boolean {
        return searchStateHelper.isActive()
    }

    private fun closeSearch() {
        searchStateHelper.close()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        return super.dispatchTouchEvent(ev)
    }

    private var hasRestoredScene = false

    private val playbackStatsHelper by lazy {
        PlaybackStatsHelper(this) { playerController }
    }

    private val usbStatusNoticeListener: (UsbStatusNoticeBus.Notice) -> Unit = { notice ->
        if (!isFinishing && !isDestroyed) {
            AppNoticeBus.post(
                message = notice.message,
                icon = notice.icon,
            )
            AppLogger.i("UsbStatusNotice", "displayed id=${notice.id} message=${notice.message}")
            UsbStatusNoticeBus.acknowledge(notice.id)
        }
    }

    companion object {
        // The reference startup scene uses a short minimum presentation window and a separate
        // reveal animation. Keeping these independent avoids a visible two-stage list rebuild.
        private const val STARTUP_SCENE_MIN_MS = 750L
        private const val STARTUP_REVEAL_MS = 200
        const val EXTRA_OPEN_PLAYER_FROM_WIDGET = "com.rawsmusic.extra.OPEN_PLAYER_FROM_WIDGET"
        const val EXTRA_OPEN_QUEUE_FROM_WIDGET = "com.rawsmusic.extra.OPEN_QUEUE_FROM_WIDGET"
        const val EXTRA_OPEN_PLAYLIST_PICKER_FROM_WIDGET =
            "com.rawsmusic.extra.OPEN_PLAYLIST_PICKER_FROM_WIDGET"
        const val ACTION_SHORTCUT_PLAY = "com.rawsmusic.shortcut.PLAY"
        const val ACTION_SHORTCUT_SEARCH = "com.rawsmusic.shortcut.SEARCH"
        const val ACTION_SHORTCUT_SHUFFLE = "com.rawsmusic.shortcut.SHUFFLE"
        const val ACTION_AUDIO_VISUALIZER_SETTING_CHANGED =
            "com.rawsmusic.action.AUDIO_VISUALIZER_SETTING_CHANGED"
    }

    override fun onResume() {
        super.onResume()
        systemBarsHelper.setStatusBarHidden(
            com.rawsmusic.module.data.prefs.AppPreferences.UI.isStatusBarHidden
        )
        UsbStatusNoticeBus.attach(usbStatusNoticeListener)
        activityForegroundForPower = true
        composeActivityForeground = true
        syncAudioVisualizerPreference("on_resume")
        rotationCoordinator.onResume()
        usbIntentCoordinator.setAttachAliasEnabled(true, "on_resume_restore")
        if (!::playerSceneController.isInitialized) return
        mainNavState.resetTransientBackState()
        updatePredictiveBackRegistration()

        // 从 SettingsActivity 返回，保持之前的导航栈
        if (settingsActivityLaunched) {
            settingsActivityLaunched = false
        }

        playbackStatsHelper.start()

        playerSceneController.resetInteractionState()

        playerSceneController.refreshImmersiveState(com.rawsmusic.module.data.prefs.AppPreferences.UI.isImmersiveEnabled)
        playerSceneController.updateMiniCoverEnabled(com.rawsmusic.module.data.prefs.AppPreferences.UI.isMiniCoverEnabled)

        val currentScene = playerSceneController.currentScene
        if (currentScene == PlayerSceneController.Scene.PLAYER &&
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        ) {
            // Returning from the dedicated landscape Activity while the phone is still held
            // sideways must restore the portrait player instead of immediately relaunching it.
            rotationCoordinator.setHomeFullCoverPolicy(
                launchArmed = false,
                clearPendingLaunch = false,
            )
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            syncMainActivityRotationPolicy(currentScene)
        }

        // 只在非 PLAYER/LYRIC 场景下才重新设置场景参数，避免从设置返回时重置播放器视图
        if (currentScene != PlayerSceneController.Scene.PLAYER && currentScene != PlayerSceneController.Scene.LYRIC) {
            setupSceneParams()
            playerSceneController.forceReapplyCurrentScene()
        }

        if (!hasRestoredScene && com.rawsmusic.module.data.prefs.AppPreferences.UI.isPlayPageMemoryEnabled) {
            hasRestoredScene = true
            val savedScene = com.rawsmusic.module.data.prefs.AppPreferences.UI.lastScene
            val currentScene = playerSceneController.currentScene
            if (currentScene == PlayerSceneController.Scene.MAIN && savedScene != "MAIN") {
                val song = playerController?.currentSong?.value
                if (song != null) {
                    val targetScene = try {
                        PlayerSceneController.Scene.valueOf(savedScene)
                    } catch (_: Exception) {
                        null
                    }
                    if (targetScene != null) {
                        mainHandler.post {
                            // A cold restore may mount PLAYER before MAIN's floating MiniPlayer ever
                            // measured a collapsed endpoint. Preserve the already-restored Compose
                            // library scene as the return destination now, before the silent PLAYER
                            // commit, so the first downward sheet gesture has the same target owner
                            // as a normally opened player.
                            if (targetScene != PlayerSceneController.Scene.MAIN && prePlayerContainerScene == null) {
                                prePlayerContainerScene = mainNavState.currentScene
                            }
                            // Re-seed the presentation generation at the same cold-restore boundary.
                            // The initial StateFlow collection and queue hydration can otherwise race:
                            // the first subsequent track switch may advance artwork while the lyric
                            // publisher still believes the restored song is its last completed owner.
                            playbackCoordinator.onCurrentSongChanged(song)
                            playerSceneController.switchToSceneSilent(targetScene)
                            if (targetScene == PlayerSceneController.Scene.LYRIC) {
                                registerCoverLyricParams()
                            } else {
                                registerCoverCollapseParams()
                            }
                            playerSceneController.forceReapplyCurrentScene()
                            updatePredictiveBackRegistration()
                            updateHiresBadge()
                        }
                    }
                }
            }
        }

        mainHandler.post {
            updatePredictiveBackRegistration()
            updateHiresBadge()

            val currentScene = playerSceneController.currentScene
            if (currentScene == PlayerSceneController.Scene.LYRIC) {
                registerCoverLyricParams()
                playerSceneController.forceReapplyCurrentScene()
            }
        }

        val song = playerController?.currentSong?.value

        if (LyriconProviderManager.isEnabled() && LyriconProviderManager.isConnected()) {
            lyricsCoordinator.resendToLyricon()
        }

        mainHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                val permissionHandled = PlayerService.dispatchUsbAttachPermissionScan(
                    this,
                    "activity_on_resume_scan"
                )
                if (!permissionHandled) {
                    playerController?.requestUsbAttachPermissionIfPresent("activity_on_resume_scan")
                }
                val handled = PlayerService.dispatchAppProcessForeground(
                    this,
                    "main_activity_on_resume_posted"
                )
                if (!handled) {
                    playerController?.onAppForegroundResumed()
                }
            }
        }, 180)

        // USB 独占模式播放中，跳过 USB 重新扫描
        if (playerController?.playState?.value == PlayState.PLAYING &&
            playerController?.isUsbExclusiveActive() == true) {
            return
        }

    }

    override fun onPostResume() {
        super.onPostResume()
        runtimeLifecycleCoordinator.onPostResume()
    }

    override fun onStop() {
        runtimeLifecycleCoordinator.onStop()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Pulling down the status/notification shade can restore the status bar without
            // pausing/resuming this Activity, so onResume() is not a reliable restoration point.
            // Re-assert the persisted policy when the app window owns focus again. The helper also
            // uses transient-bars-by-swipe, so a normal top-edge peek remains accessible.
            systemBarsHelper.setStatusBarHidden(
                com.rawsmusic.module.data.prefs.AppPreferences.UI.isStatusBarHidden
            )
        }
        runtimeLifecycleCoordinator.onWindowFocusChanged(hasFocus)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(sleepTimerExitReceiver) }
        val finishing = isFinishing
        val changingConfig = isChangingConfigurations
        rotationCoordinator.onDestroy()
        predictiveBackCoordinator.removeHandoffRelease()
        if (audioVisualizerReceiverRegistered) {
            runCatching { unregisterReceiver(audioVisualizerSettingReceiver) }
            audioVisualizerReceiverRegistered = false
        }
        if (finishing) playerController?.onPcmWaveformFrame = null
        realtimeSpectrumPipeline.close()
        super.onDestroy()
        playbackStatsHelper.stop()
        themeCoordinator.unregister()
        progressSeekActive = false
        progressSeekLock?.release()
        progressSeekLock = null
        gestureLockCoordinator.clear()

        if (finishing) {
            PlayerService.dispatchUiHostDestroyed(
                this,
                reason = "main_activity_on_destroy",
                finishing = true
            )
            LyricGetterBridge.destroy()
            playerController = null
        } else {
            // Lyricon 的位置同步由 PlayerService 持有，Activity 重建不能停止它。
        }
    }

    private fun setupLandscapePlayerEntry() {
        rotationCoordinator.setup()
    }

    /**
     * The normal player temporarily advertises USER orientation support. With auto-rotate on,
     * Android rotates immediately; with rotation lock on, SystemUI may show its standard rotation
     * suggestion. A forced PORTRAIT Activity cannot receive that system proposal.
     */
    private fun syncMainActivityRotationPolicy(
        scene: PlayerSceneController.Scene = playerSceneController.currentScene
    ) {
        rotationCoordinator.syncPolicy(scene)
    }

    private fun launchLandscapePlayerFromSystemRotation() {
        rotationCoordinator.launchFromSystemRotation()
    }

}
