package com.rawsmusic

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import java.io.File
import java.io.FileOutputStream
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import androidx.activity.viewModels
import com.rawsmusic.core.common.ext.isDarkMode
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.toLyriconSong
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.UiUtils
import com.rawsmusic.core.ui.R as UiR
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.theme.RawSMusicTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.widget.DynamicCoverBackgroundState
import com.rawsmusic.core.ui.widget.ImmersiveBackgroundState
import com.rawsmusic.core.ui.widget.PlayerSceneController
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.FontManager
import com.rawsmusic.module.data.prefs.PlaybackStatsStore
import com.rawsmusic.module.player.GlobalSettingsViewModel
import com.rawsmusic.module.player.AudioOutputManager
import com.rawsmusic.module.player.LyriconProviderManager
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerEventBus
import com.rawsmusic.module.player.PlayerService
import com.rawsmusic.module.player.lyrics.BluetoothLyricBridge
import com.rawsmusic.module.player.lyrics.LyricGetterBridge
import com.rawsmusic.module.player.lyrics.TickerBridge
import com.rawsmusic.module.scanner.LyricReader
import com.rawsmusic.ui.songs.PlayerHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rawsmusic.helper.AlbumInfoNavigator
import com.rawsmusic.helper.AudioCapsuleUiHelper
import com.rawsmusic.helper.AudioPermissionHelper
import com.rawsmusic.helper.AudioInfoCapsuleHelper
import com.rawsmusic.helper.AudioInfoCapsuleOverlay
import com.rawsmusic.helper.BatteryOptimizationHelper
import com.rawsmusic.helper.BatteryOptimizationOverlay
import com.rawsmusic.helper.CoverBackgroundLayerState
import com.rawsmusic.helper.CoverCoordinator
import com.rawsmusic.helper.DialogHelper
import com.rawsmusic.helper.DialogOverlay
import com.rawsmusic.helper.LastPlayingStateHelper
import com.rawsmusic.helper.LogExportHelper
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
import com.rawsmusic.helper.SongActionSheetOverlay
import com.rawsmusic.helper.StartupPermissionFlowHelper
import com.rawsmusic.helper.StartupScanHelper
import com.rawsmusic.helper.ThemeCoordinator
import com.rawsmusic.helper.ScannerCoordinator
import com.rawsmusic.helper.SystemBarsHelper
import com.rawsmusic.helper.UsbVolumeKeyHandler

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
class MainActivity : ComponentActivity() {

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var startupWorkScheduled = false

    private var currentLyricData by mutableStateOf(LyricData())
    private var composeLyricSong by mutableStateOf<Song?>(null)
    private var composeLyricPositionMs by mutableLongStateOf(0L)
    private var composeDisplayTranslation by mutableStateOf(AppPreferences.Lyricon.displayTranslation)
    private var composeDisplayRoma by mutableStateOf(AppPreferences.Lyricon.displayRoma)
    private var composeLyricIsLight by mutableStateOf(false)

    @Volatile
    private var activityForegroundForPower = false
    private var composeActivityForeground by mutableStateOf(false)
    @Volatile
    private var lastVisualizerUiFrameMs = 0L

    private var legacyDestinationId: Int = R.id.nav_songs
    private lateinit var playerSceneController: PlayerSceneController
    internal var playerController: PlayerController? = null
    private val globalSettingsVM: GlobalSettingsViewModel by viewModels()

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
    private var playingCoverBoundsForTransition by mutableStateOf<android.graphics.RectF?>(null)
    private var miniPlayerCoverBoundsForTransition by mutableStateOf<android.graphics.RectF?>(null)
    private var coverTargetForTransition by mutableStateOf<CoverTransitionTarget?>(null)
    private var lockedPlayerCoverBoundsForTransition by mutableStateOf<android.graphics.RectF?>(null)
    private var lockedPlayerCoverPathForTransition by mutableStateOf<String?>(null)
    private var playerReturnRevealIndex by mutableIntStateOf(-1)
    private var acceptingReturnCoverBounds = false
    private var returnCoverBoundsResolved = false

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

    internal fun ensureRuntimeController(reason: String): PlayerController {
        val existing = playerController
            ?: PlayerHolder.controller
            ?: PlayerService.currentRuntimeController()
            ?: PlayerController.getInstanceOrNull()
        if (existing != null) {
            playerController = existing
            PlayerHolder.controller = existing
            return existing
        }
        return PlayerService.obtainRuntimeController(this, reason).also { controller ->
            playerController = controller
            PlayerHolder.controller = controller
        }
    }

    private var isSideMenuOpen by mutableStateOf(false)

    /** 进入播放器前的 Fragment 导航目标，用于返回时恢复正确的页面 */
    private var prePlayerFragmentDest: Int? = null
    /** 进入播放器前是否从独立页面入口进入（历史字段，Compose 迁移期间保留恢复语义） */
    private var prePlayerWasInFragmentMode: Boolean = false
    /** 进入播放器前主 Compose 导航的当前场景 */
    private var prePlayerContainerScene: com.rawsmusic.core.ui.scene.NavScene? = null
    /** 播放器关闭后需要进入的 Compose 设置场景。 */
    private var pendingSettingsSceneAfterPlayerClose: com.rawsmusic.core.ui.scene.NavScene? = null
    private var settingsActivityLaunched = false

    private fun launchSettingsActivity(activityClass: Class<*>) {
        settingsActivityLaunched = true
        startActivity(android.content.Intent(this, activityClass))
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
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
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
        AppLogger.w("SceneTransition", "=== MainActivity.finish() CALLED ===", Exception("finish() stacktrace"))
        super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 初始化 PlayerController，如果当前没有 controller 则创建
        if (playerController == null) {
            ensureRuntimeController("main_activity_on_create")
        }
        // USB 独占激活后引导用户加入电池优化白名单
        playerController?.onUsbExclusiveActivated = {
            batteryOptimizationHelper.promptWhitelistForUsbExclusive()
        }
        ThemeManager.applyTheme(ThemeManager.getCurrentTheme())
        super.onCreate(savedInstanceState)

        // 恢复导航状态（划掉后台重开时回到之前的页面）
        com.rawsmusic.core.ui.scene.NavigationPersistence.restore(this, mainNavState)

        playerSceneController = PlayerSceneController()
        setContent { RootContent() }

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
        handleUsbAttachIntent(intent, reason = "activity_on_create")
        setUsbAttachAliasEnabled(true, "on_create_restore")
        scheduleDeferredStartupWork()
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
        handleUsbAttachIntent(intent, reason = "activity_on_new_intent")
    }

    private fun handleUsbAttachIntent(intent: Intent?, reason: String) {
        if (intent?.action != android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(
                android.hardware.usb.UsbManager.EXTRA_DEVICE,
                android.hardware.usb.UsbDevice::class.java
            )
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(android.hardware.usb.UsbManager.EXTRA_DEVICE)
        } ?: return
        AppLogger.i("MainActivity", "USB attach intent received: ${device.deviceName} reason=$reason")
        val handled = PlayerService.dispatchUsbAttachIntent(this, device, reason)
        if (!handled) {
            playerController?.handleUsbDeviceAttachIntent(device, reason)
        }
    }

    private fun setUsbAttachAliasEnabled(enabled: Boolean, reason: String) {
        val component = android.content.ComponentName(packageName, "$packageName.UsbAttachActivityAlias")
        val state = if (enabled) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            packageManager.setComponentEnabledSetting(
                component,
                state,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
            AppLogger.i("MainActivity", "USB attach alias enabled=$enabled reason=$reason")
        } catch (e: Exception) {
            AppLogger.w("MainActivity", "USB attach alias toggle failed enabled=$enabled reason=$reason: ${e.message}")
        }
    }




    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::playerSceneController.isInitialized) return
        val currentScene = playerSceneController.currentScene
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

    /**
     * 将实体音量键映射到USB DAC 硬件音量控制
     * 当USB 设备已连接且支持硬件音量时，直接硬件控制硬件音量，防止系统干扰   */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (usbVolumeKeyHandler.handleKeyDown(keyCode)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private val usbVolumeKeyHandler by lazy {
        UsbVolumeKeyHandler(
            { playerController },
            { text -> showUsbVolumeOverlay(text) }
        )
    }
    private val batteryOptimizationHelper by lazy {
        BatteryOptimizationHelper(this) { visible -> updateComposeRootVisibility(visible) }
    }
    private val dialogHelper by lazy {
        DialogHelper { visible -> updateComposeRootVisibility(visible) }
    }
    private val logExportHelper by lazy { LogExportHelper(this) }
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
    private val startupPermissionFlowHelper by lazy {
        StartupPermissionFlowHelper(
            audioPermissionHelper,
            { permissions -> permissionLauncher.launch(permissions) },
            { startupScanHelper.start() }
        )
    }
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
            getLyricOffsetMs = { playerController?.lyricManualOffsetMs?.toLong() ?: 0L },
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
            { data -> setCurrentLyricDataForCompose(data) },
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
            sceneController = { if (::playerSceneController.isInitialized) playerSceneController else null },
            miniPlayer = miniPlayerCoordinator,
            lyrics = lyricsCoordinator,
            playerServiceBridgeHelper = playerServiceBridgeHelper,
            onCurrentSongChangedExtra = { song ->
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
                // seek 后 UI 防回跳
                if (isSeekUiHolding && seekTargetMs >= 0L) {
                    val tolerance = (duration * 0.02f).toLong().coerceIn(300L, 2000L)
                    val elapsed = System.currentTimeMillis() - seekFinishTimeMs
                    if (kotlin.math.abs(pos - seekTargetMs) < tolerance || elapsed > 2000L) {
                        stopSeekUiHold()
                    }
                }
            },
            isPlayerUiVisible = { isPlayerUiVisibleForPower() },
            context = this
        )
    }
    private val searchStateHelper by lazy {
        SearchStateHelper()
    }
    private val albumInfoNavigator by lazy {
        AlbumInfoNavigator(
            { song -> coverUriResolver.resolveCoverUri(song) },
            { albumName, albumArtist, coverPath ->
                mainNavState.navigateTo(
                    com.rawsmusic.core.ui.scene.NavScene.ALBUM_DETAIL,
                    "$albumName|$albumArtist|$coverPath"
                )
            }
        )
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
        }
    }
    private val playbackQueueHelper by lazy { MainPlaybackQueueHelper { playerController } }
    private val audioInfoCapsuleHelper by lazy {
        AudioInfoCapsuleHelper(
            this,
            { playerController },
            { visible -> updateComposeRootVisibility(visible) },
            { destinationId -> openDestinationFromPlayerPopup(destinationId) }
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
        { visible -> updateComposeRootVisibility(visible) }
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
            onEditMetadata = { metadataEditorHelper.editMetadata() }
            onOpenMetadataDetail = { metadataDetailHelper.open() }
            onShowSleepTimer = { dialogHelper.showSleepTimer(playerController) }
            onDeleteCurrentSong = { metadataEditorHelper.deleteCurrentSong() }
            onPickCoverImage = { metadataEditorHelper.pickCoverImage() }
            onRestoreCover = { metadataEditorHelper.restoreOriginalCover() }
        }
    }
    private val metadataDetailHelper by lazy {
        MetadataDetailHelper(
            this,
            { playerController },
            { disabled -> if (::playerSceneController.isInitialized) playerSceneController.disableGestureIntercept = disabled },
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
        playerController?.onPcmWaveformFrame = waveform@{ buffer, read, channels, sampleRate, bitsPerSample ->
            if (!activityForegroundForPower || !AppPreferences.UI.isAudioVisualizerEnabled) {
                return@waveform
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastVisualizerUiFrameMs < VISUALIZER_UI_FRAME_INTERVAL_MS) {
                return@waveform
            }
            lastVisualizerUiFrameMs = now
            runOnUiThread {
                if (!isAudioVisualizerUiActiveForPower()) {
                    if (visualizerLevels.any { it > 0f }) {
                        visualizerLevels = FloatArray(80)
                    }
                    return@runOnUiThread
                }
                visualizerLevels = com.rawsmusic.core.ui.widget.player.pcmWaveformLevels(
                    buffer = buffer,
                    read = read,
                    channels = channels,
                    bitsPerSample = bitsPerSample
                )
            }
        }
        metadataDetailHelper.setup()
        metadataCardPopupHelper.onMetadataClick = {
            metadataDetailHelper.open()
        }
        songActionSheetHelper.setup()
        setupEdgeToEdge()
        setupPredictiveBack()

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
        try {
            registerCoverCollapseParams()
        } catch (_: Exception) {}

        val targetScene = settingsSceneForDestination(destinationId)

        val scene = try {
            playerSceneController.currentScene
        } catch (_: Exception) {
            null
        }
        if (scene == PlayerSceneController.Scene.LYRIC) {
            pendingSettingsSceneAfterPlayerClose = targetScene
            playerSceneController.closeLyricPage(true)
            mainHandler.postDelayed({
                if (playerSceneController.currentScene == PlayerSceneController.Scene.PLAYER) {
                    playerSceneController.closePlayPageWithCoverAlign(true)
                }
            }, 180L)
            return
        }
        if (scene == PlayerSceneController.Scene.PLAYER) {
            pendingSettingsSceneAfterPlayerClose = targetScene
            playerSceneController.closePlayPageWithCoverAlign(true)
            return
        }

        navigateToSettingsScene(targetScene)
    }

    fun navigateSettingsForward(destinationId: Int) {
        navigateToSettingsScene(settingsSceneForDestination(destinationId))
    }

    fun navigateSettingsBack() {
        if (!mainNavState.navigateBackAnimated()) {
            mainNavState.navigateHome()
        }
    }

    private fun navigateToSettingsScene(scene: com.rawsmusic.core.ui.scene.NavScene) {
        updateComposeRootVisibility(true)
        if (::playerSceneController.isInitialized &&
            playerSceneController.currentScene != PlayerSceneController.Scene.MAIN
        ) {
            pendingSettingsSceneAfterPlayerClose = scene
            playerSceneController.closePlayPageWithCoverAlign(true)
            return
        }
        // 设置页由独立 Activity 承载，主界面保持 HOME，避免返回后底部栏残留设置选中态。
        val activityClass = SETTINGS_ACTIVITY_MAP[scene]
        if (activityClass != null) {
            mainNavState.navigateHome()
            launchSettingsActivity(activityClass)
        } else if (mainNavState.currentScene != scene) {
            mainNavState.navigateToSettings(scene)
        }
        updateDrawerLockMode()
    }

    private fun settingsSceneForDestination(destinationId: Int): com.rawsmusic.core.ui.scene.NavScene {
        return when (destinationId) {
            R.id.nav_lyric_management -> com.rawsmusic.core.ui.scene.NavScene.LYRIC_MANAGEMENT
            R.id.nav_status_bar_lyric -> com.rawsmusic.core.ui.scene.NavScene.STATUS_BAR_LYRIC
            R.id.nav_appearance -> com.rawsmusic.core.ui.scene.NavScene.APPEARANCE
            R.id.nav_audio_settings -> com.rawsmusic.core.ui.scene.NavScene.AUDIO_SETTINGS
            R.id.nav_audio_effects -> com.rawsmusic.core.ui.scene.NavScene.AUDIO_EFFECTS
            R.id.nav_player_interface -> com.rawsmusic.core.ui.scene.NavScene.PLAYER_INTERFACE
            R.id.nav_usb_dac_settings -> com.rawsmusic.core.ui.scene.NavScene.USB_DAC_SETTINGS
            R.id.nav_peq -> com.rawsmusic.core.ui.scene.NavScene.PEQ
            R.id.nav_compressor -> com.rawsmusic.core.ui.scene.NavScene.COMPRESSOR
            R.id.nav_bass_treble_boost -> com.rawsmusic.core.ui.scene.NavScene.BASS_TREBLE_BOOST
            R.id.nav_spatial_sound -> com.rawsmusic.core.ui.scene.NavScene.SPATIAL_SOUND
            R.id.nav_surround_360 -> com.rawsmusic.core.ui.scene.NavScene.SURROUND_360
            R.id.nav_panoramic_360 -> com.rawsmusic.core.ui.scene.NavScene.PANORAMIC_360
            R.id.nav_lyric_font_settings -> com.rawsmusic.core.ui.scene.NavScene.LYRIC_FONT_SETTINGS
            R.id.nav_global_font_settings -> com.rawsmusic.core.ui.scene.NavScene.GLOBAL_FONT_SETTINGS
            R.id.nav_webdav_backup -> com.rawsmusic.core.ui.scene.NavScene.WEBDAV_BACKUP
            R.id.nav_log_viewer -> com.rawsmusic.core.ui.scene.NavScene.LOG_VIEWER
            R.id.nav_album_art_settings -> com.rawsmusic.core.ui.scene.NavScene.ALBUM_ART_SETTINGS
            else -> com.rawsmusic.core.ui.scene.NavScene.SETTINGS
        }
    }

    private fun startSettingsBackDrag() {}
    private fun updateSettingsBackDrag(progress: Float) {}
    private fun finishSettingsBackDrag(xVelocity: Float = 0f) {}
    private fun navigateSettingsBackWithoutOverlay() {}
    private fun cancelSettingsBackDrag() {}
    private fun prepareAudioInfoPopupReturnTarget() {}
    private fun resetSettingsPageTransform() {}

    private fun isSettingsDestination(destinationId: Int?): Boolean {
        return destinationId in setOf(
            R.id.nav_settings, R.id.nav_lyric_management, R.id.nav_status_bar_lyric,
            R.id.nav_appearance, R.id.nav_audio_settings, R.id.nav_audio_effects,
            R.id.nav_player_interface, R.id.nav_usb_dac_settings, R.id.nav_peq,
            R.id.nav_compressor, R.id.nav_bass_treble_boost, R.id.nav_spatial_sound,
            R.id.nav_surround_360, R.id.nav_panoramic_360, R.id.nav_lyric_font_settings,
            R.id.nav_global_font_settings, R.id.nav_webdav_backup, R.id.nav_log_viewer,
            R.id.nav_album_art_settings, R.id.nav_scan_settings
        )
    }

    private fun legacyNavigateTo(destinationId: Int) {
        legacyDestinationId = destinationId
    }

    private fun legacyNavigateUp(): Boolean {
        if (legacyDestinationId == R.id.nav_songs) return false
        legacyDestinationId = R.id.nav_songs
        return true
    }

    private fun legacyPopToSongs(): Boolean {
        val changed = legacyDestinationId != R.id.nav_songs
        legacyDestinationId = R.id.nav_songs
        return changed
    }

    private fun switchToContainerMode(targetScene: com.rawsmusic.core.ui.scene.NavScene? = null) {
        if (false) return
        // 先静默切换场景（内部会隐藏其他页面），再显示容器，避免闪现所有内容
        if (targetScene != null) {
            mainNavState.switchToSilent(targetScene)
        }
        updateComposeRootVisibility(true)
    }

    private fun prepareContainerForPlayerReturn() {
        val songsDest = prePlayerWasInFragmentMode &&
            (prePlayerFragmentDest == null || prePlayerFragmentDest == R.id.nav_songs)
        when {
            songsDest -> switchToContainerMode(com.rawsmusic.core.ui.scene.NavScene.SONGS)
            !prePlayerWasInFragmentMode -> switchToContainerMode(prePlayerContainerScene)
            else -> updateComposeRootVisibility(true)
        }
    }

    private fun closeSideMenu() {
        isSideMenuOpen = false
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
        android.util.Log.d("GestureDebug", "updateDrawerLockMode: isHomeLevel=$isHomeLevel, isDeepPage=$isDeepPage, inFragmentMode=$inFragmentMode, isDeepHomePage=${playerSceneController.isDeepHomePage}, canNavigateBack=${mainNavState.canNavigateBack()}")
    }

    /** 纯 Compose 主界面导航状态 */
    private val mainNavState = com.rawsmusic.core.ui.scene.NavigationState()

    private fun setupMainComposeView() {
        observeMainContainerFlows()
    }

    @Composable
    private fun RootContent() {
        val themeKey = com.rawsmusic.core.ui.theme.RawThemeRuntimeState.version
        val navEventOwner = rememberNavigationEventDispatcherOwner(true, null)
        RawSMusicTheme(key = themeKey) {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides navEventOwner) {
                val rootColor = MiuixTheme.colorScheme.background
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(rootColor)
                        .sideMenuDismissInput()
                        .sceneGestureInput()
                ) {
                    BackgroundLayers()
                    MainComposeContent()
                    PlayerOverlayContent()
                }
            }
        }
    }

    private fun Modifier.sideMenuDismissInput(): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitPointerEvent(PointerEventPass.Final)
                .changes
                .firstOrNull { it.changedToDownIgnoreConsumed() }
                ?: return@awaitEachGesture
            var moved = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull() ?: return@awaitEachGesture
                if (change.positionChange().getDistance() > viewConfiguration.touchSlop) {
                    moved = true
                }
                if (change.changedToUpIgnoreConsumed()) {
                    if (!moved && isSideMenuOpen) closeSideMenu()
                    return@awaitEachGesture
                }
            }
        }
    }

    private fun Modifier.sceneGestureInput(): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitPointerEvent(PointerEventPass.Main)
                .changes
                .firstOrNull { it.changedToDownIgnoreConsumed() }
                ?: return@awaitEachGesture
            if (progressSeekActive || gestureLockCoordinator.isBlocked || playerSceneController.disableGestureIntercept || playerSceneController.isTransitioning) return@awaitEachGesture
            if (playerSceneController.currentScene == PlayerSceneController.Scene.MAIN && mainNavState.canNavigateBack()) {
                return@awaitEachGesture
            }

            val start = down.position
            val pointerId = down.id
            var last = start
            var dragging = false
            var totalDx = 0f
            val touchSlop = viewConfiguration.touchSlop
            val widthPx = size.width.toFloat().coerceAtLeast(1f)
            val edgeBackWidthPx = viewConfiguration.touchSlop * 4f
            var velocityX = 0f
            var lastTime = down.uptimeMillis

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                val change = event.changes.firstOrNull { it.id == pointerId }
                    ?: event.changes.firstOrNull()
                    ?: return@awaitEachGesture

                // 进度条拖动期间，持续退出根部手势
                if (progressSeekActive || gestureLockCoordinator.isBlocked || playerSceneController.disableGestureIntercept) {
                    return@awaitEachGesture
                }

                if (change.changedToUpIgnoreConsumed()) {
                    if (dragging) {
                        playerSceneController.releaseGestureDrag(totalDx, velocityX, widthPx)
                        change.consume()
                    }
                    return@awaitEachGesture
                }

                val dxFromStart = change.position.x - start.x
                val dyFromStart = change.position.y - start.y
                val dxFromLast = change.position.x - last.x
                val dyFromLast = change.position.y - last.y
                if (!dragging && dxFromLast == 0f && dyFromLast == 0f) continue
                if (!dragging && kotlin.math.abs(dxFromStart) > touchSlop && kotlin.math.abs(dxFromStart) > kotlin.math.abs(dyFromStart) * 1.25f) {
                    if (playerSceneController.currentScene == PlayerSceneController.Scene.MAIN && !playerSceneController.isDeepHomePage) {
                        return@awaitEachGesture
                    }
                    // 普通播放界面只保留系统式侧滑返回，不再把内容区左滑解释为进入歌词。
                    // 歌词页自己的手势不在这里处理，保持原逻辑。
                    val forceBackToMain = playerSceneController.currentScene == PlayerSceneController.Scene.PLAYER &&
                            dxFromStart > 0f &&
                            start.x <= edgeBackWidthPx
                    if (playerSceneController.currentScene == PlayerSceneController.Scene.PLAYER &&
                        !playerSceneController.isImmersiveEnabled &&
                        !forceBackToMain
                    ) {
                        return@awaitEachGesture
                    }

                    dragging = true
                    playerSceneController.onDragStart(dxFromStart < 0f, forceBackToMain)
                }
                if (dragging) {
                    val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                    velocityX = dxFromLast / dt * 1000f
                    totalDx = dxFromStart
                    last = change.position
                    lastTime = change.uptimeMillis
                    playerSceneController.updateGestureDrag(totalDx, widthPx)
                    change.consume()
                }
            }
        }
    }

    @Composable
    private fun BackgroundLayers() {
        MainBackgroundLayers()
    }

    @Composable
    private fun MainComposeContent() {
        val songs by MusicRepository.songs.collectAsState()
        val playbackStats by PlaybackStatsStore.getInstance(this).stats.collectAsState()
        val currentSong by playerController?.currentSong?.collectAsState()
            ?: androidx.compose.runtime.mutableStateOf(null)

        // 监听扫描状态（首次运行不再自动弹文件夹选择器，直接进入歌曲列表触发 MediaStore 扫描）
        val scanStatus by com.rawsmusic.module.scanner.ScanStateBus.status.collectAsState()
        androidx.compose.runtime.LaunchedEffect(scanStatus.state, scanStatus.timeMs, scanStatus.progress) {
            when (scanStatus.state) {
                com.rawsmusic.module.scanner.ScanStateBus.ScanState.FOLDER_SELECTION_NEEDED -> {
                    overlayCoordinator.setFolderDialogVisible(true)
                }
                com.rawsmusic.module.scanner.ScanStateBus.ScanState.COMPLETED -> {
                    com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider.notifyLibraryArtworkChanged("scan_completed")
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

        val callbacks = com.rawsmusic.core.ui.scene.NavCallbacks(
            onSongClick = { song, _ ->
                lockedPlayerCoverPathForTransition = resolveSongCoverForCompose(song)
                playbackQueueHelper.playSongFromScene(song, mainNavState.currentScene)
                mainHandler.post { openPlayPageWithSharedElement() }
            },
            onSongLongClick = { _, _ -> },
            onAlbumClick = { mainNavState.navigateTo(com.rawsmusic.core.ui.scene.NavScene.ALBUMS) },
            onAlbumItemClick = { album ->
                val arg = "${album.name}|${album.artist}|${album.coverPath}"
                mainNavState.navigateTo(com.rawsmusic.core.ui.scene.NavScene.ALBUM_DETAIL, arg)
            },
            onArtistClick = { artist -> mainNavState.navigateTo(com.rawsmusic.core.ui.scene.NavScene.ARTISTS) },
            onPlayQueue = { songs, idx ->
                lockedPlayerCoverPathForTransition = resolveSongCoverForCompose(songs[idx])
                playbackQueueHelper.playQueue(songs, idx)
                mainHandler.post { openPlayPageWithSharedElement() }
            },
            onPlaylistClick = {},
            onFolderClick = {},
            onFolderHierarchyClick = {},
            onQueueSongClick = { song, _ ->
                lockedPlayerCoverPathForTransition = resolveSongCoverForCompose(song)
                playbackQueueHelper.playSongFromScene(song, mainNavState.currentScene)
                mainHandler.post { openPlayPageWithSharedElement() }
            },
            onRecentlyAddedClick = { song, _ ->
                lockedPlayerCoverPathForTransition = resolveSongCoverForCompose(song)
                playbackQueueHelper.playSongFromScene(song, mainNavState.currentScene)
                mainHandler.post { openPlayPageWithSharedElement() }
            },
            onPlayAll = { songs ->
                songs.firstOrNull()?.let { first ->
                    lockedPlayerCoverPathForTransition = resolveSongCoverForCompose(first)
                    playbackQueueHelper.playQueue(songs, 0)
                    mainHandler.post { openPlayPageWithSharedElement() }
                }
            },
            onShuffleAll = { songs ->
                songs.firstOrNull()?.let { first ->
                    lockedPlayerCoverPathForTransition = resolveSongCoverForCompose(first)
                    playbackQueueHelper.playQueue(songs, 0)
                    mainHandler.post { openPlayPageWithSharedElement() }
                }
            },
            onSearchClick = { mainNavState.navigateTo(com.rawsmusic.core.ui.scene.NavScene.SEARCH) },
            onNavigateToPlayer = {
                if (playerController?.currentSong?.value != null) openPlayPageWithSharedElement()
                else moveTaskToBack(true)
            },
            onMiniPlayerPlayPause = { playerController?.playPause() },
            onMiniPlayerPrevious = { playerController?.previous() },
            onMiniPlayerNext = { playerController?.next() },
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
                Toast.makeText(this@MainActivity, "已添加到播放队列", Toast.LENGTH_SHORT).show()
            },
            onSelectionDelete = { selected ->
                lifecycleScope.launch(Dispatchers.IO) {
                    var deletedCount = 0
                    selected.forEach { song ->
                        if (MusicRepository.deleteSongFromDevice(this@MainActivity, song)) deletedCount++
                    }
                    MusicRepository.refreshSongsOnlySuspend(invalidate = true)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "已删除 $deletedCount 首", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSelectionPlayNext = { selected ->
                selected.asReversed().forEach { song -> playerController?.playNext(song) }
                Toast.makeText(this@MainActivity, "已加入下一首播放", Toast.LENGTH_SHORT).show()
            },
            onSongsSelectionModeChanged = { active ->
                songsSelectionMode = active
            },
            onSongsRefresh = {},
            onPlayingCoverBoundsChanged = { rect ->
                rect?.let {
                    val copy = android.graphics.RectF(it)
                    playingCoverBoundsForTransition = copy
                    if (playerSceneController.currentScene == PlayerSceneController.Scene.MAIN && !acceptingReturnCoverBounds) {
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
                    !acceptingReturnCoverBounds
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
                    acceptingReturnCoverBounds &&
                    target != null &&
                    target.isForSong(currentId, currentCover)
                ) {
                    val bounds = android.graphics.RectF(target.bounds)
                    playingCoverBoundsForTransition = bounds
                    lockedPlayerCoverBoundsForTransition = android.graphics.RectF(bounds)
                    coverTargetForTransition = target.copyBounds()
                    returnCoverBoundsResolved = true
                }
            },
            onMiniPlayerCoverBoundsChanged = { rect ->
                rect?.let {
                    miniPlayerCoverBoundsForTransition = android.graphics.RectF(it)
                }
            }
        )

        val hidePlayingCoverForReturn = false

        val data = com.rawsmusic.core.ui.scene.NavData(
            songs = songs,
            currentPlayingIndex = songs.indexOfFirst { it.id == (currentSong?.id ?: -1L) },
            currentSong = currentSong,
            miniPlayerTitle = miniPlayerCoordinator.title,
            miniPlayerArtist = miniPlayerCoordinator.artist,
            miniPlayerIsPlaying = miniPlayerCoordinator.isPlaying,
            miniPlayerProgress = miniPlayerCoordinator.progress,
            miniPlayerCoverPath = miniPlayerCoordinator.coverPath,
            playerReturnRevealIndex = playerReturnRevealIndex,
            hidePlayingCover = hidePlayingCoverForReturn,
            currentSortOrder = AppPreferences.Sort.songSortOrder,
            artistDataSource = null,
            playCounts = playbackStats.associate { it.songId to it.playCount },
            bottomChromeHidden = songsSelectionMode || songActionSheetHelper.isPlaylistPickerShowing,
            uiForeground = composeActivityForeground
        )

        com.rawsmusic.core.ui.scene.AppMainLayout(
            navState = mainNavState,
            navCallbacks = callbacks,
            navData = data,
            externalPageRenderer = AppPageRendererImpl(mainNavState),
            onNavigateToPlayer = {
                if (playerController?.currentSong?.value != null) openPlayPageWithSharedElement()
                else moveTaskToBack(true)
            },
            onSettingsClick = {
                launchSettingsActivity(com.rawsmusic.ui.settings.SettingsActivity::class.java)
            }
        )
    }

    internal fun playSongFromSearch(song: AudioFile) {
        val songs = MusicRepository.songs.value
        val index = songs.indexOfFirst { it.id == song.id || it.path == song.path }.coerceAtLeast(0)
        val queue = songs.ifEmpty { listOf(song) }
        val targetIndex = if (index in queue.indices) index else 0
        lockedPlayerCoverPathForTransition = resolveSongCoverForCompose(queue[targetIndex])
        playbackQueueHelper.playQueue(queue, targetIndex)
        mainHandler.post { openPlayPageWithSharedElement() }
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
            com.rawsmusic.core.ui.scene.NavScene.FOLDER_HIERARCHY,
            android.net.Uri.encode(folder.path)
        )
    }

    private fun observeMainContainerFlows() {
        // Compose 版本：数据通过 collectAsState 在 Composable 中自动同步
        // 只保留场景变化监听
        lifecycleScope.launch {
            snapshotFlow { mainNavState.currentScene }.collect { scene ->
                updateDrawerLockMode()
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
                    "deferred MusicRepository snapshot size=${snapshotSongs.size} in ${System.currentTimeMillis() - start}ms"
                )
                if (snapshotSongs.isNotEmpty()) {
                    kotlinx.coroutines.delay(5_500L)
                    val indexStart = System.currentTimeMillis()
                    MusicRepository.refreshLibraryIndexes(snapshotSongs)
                    AppLogger.d(
                        "Startup",
                        "deferred snapshot indexes ${snapshotSongs.size} songs done in ${System.currentTimeMillis() - indexStart}ms"
                    )
                }
            } catch (e: Exception) {
                AppLogger.e("Startup", "deferred MusicRepository startup load failed", e)
            }
        }

        mainHandler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            restoreLastPlayingState()
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
        }

        playerSceneController.onSceneChanged = { newScene, oldScene ->
            val playState = playerController?.playState?.value
            val ffmpegState = playerController?.ffmpegPlayerRef?.state
            AppLogger.w("SceneTransition", "=== onSceneChanged: $oldScene -> $newScene, isRealTransition=${oldScene != newScene}, playState=$playState, ffmpegState=$ffmpegState, prePlayerWasInFragmentMode=$prePlayerWasInFragmentMode, prePlayerFragmentDest=$prePlayerFragmentDest ===")
            syncComposePlayerScene(newScene)
            val isRealTransition = oldScene != newScene
            com.rawsmusic.module.data.prefs.AppPreferences.UI.lastScene = newScene.name
            if (newScene == PlayerSceneController.Scene.MAIN) {
                com.rawsmusic.module.data.prefs.AppPreferences.UI.wasInFragmentMode = false
            }
            // 只在状态栏设置实际会改变时才更新，避免 PLAYER↔LYRIC 等切换时触发 insets 重算导致导航栏闪烁
            val needsUpdate = (oldScene == PlayerSceneController.Scene.MAIN) != (newScene == PlayerSceneController.Scene.MAIN)
            if (needsUpdate) {
                updateStatusBarForLevel(newScene)
            }
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
                        AppLogger.w("SceneTransition", "=== Restoring UI mode: prePlayerWasInFragmentMode=$prePlayerWasInFragmentMode ===")
                        val songsDest = prePlayerWasInFragmentMode && (prePlayerFragmentDest == null || prePlayerFragmentDest == R.id.nav_songs)
                        if (songsDest) {
                            // 歌曲列表已迁移到容器模式
                            AppLogger.w("SceneTransition", "=== switchToContainerMode() for songs ===")
                            switchToContainerMode(com.rawsmusic.core.ui.scene.NavScene.SONGS)
                        } else if (prePlayerWasInFragmentMode) {
                            AppLogger.w("SceneTransition", "=== switchToFragmentMode() ===")
                            switchToFragmentMode()
                        } else {
                            val restoreScene = prePlayerContainerScene
                            AppLogger.w("SceneTransition", "=== switchToContainerMode() restoreScene=$restoreScene ===")
                            switchToContainerMode(restoreScene)
                        }
                    }
                    restoreDefaultSceneParams()
                    // 仅在场景实际从 PLAYER/LYRIC 切换回 MAIN 时才处理导航栈，
                    // 避免 forceReapplyCurrentScene()（oldScene==newScene）误杀二级页面（如 PEQ）
                    if (isRealTransition) {
                        if (prePlayerWasInFragmentMode && (prePlayerFragmentDest != null && prePlayerFragmentDest != R.id.nav_songs)) {
                            val savedDest = prePlayerFragmentDest!!
                            // 若页面仍在导航栈顶（如专辑详情页），直接复用，不 pop 不重新导航
                            if (legacyDestinationId == savedDest) {
                                AppLogger.w("SceneTransition", "=== dest $savedDest still on top, skip pop/navigate ===")
                            } else if (savedDest != R.id.nav_songs) {
                                // 页面已不在栈顶，先回到歌曲列表，再重新导航
                                legacyPopToSongs()
                                legacyNavigateTo(savedDest)
                            } else {
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
                    composeLyricPositionMs = pos
                    playerController?.currentSong?.value?.let { song ->
                        val lyricData = currentLyricData
                        if (!lyricData.isEmpty) {
                            composeLyricSong = lyricData.toLyriconSong(
                                name = song.title,
                                artist = song.artist
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

        playerSceneController.onPreparePlayerToMain = { onReady ->
            registerCoverCollapseParams()
            updateComposeRootVisibility(true)
            prepareContainerForPlayerReturn()
            val currentId = playerController?.currentSong?.value?.id ?: -1L
            playerReturnRevealIndex = MusicRepository.songs.value.indexOfFirst { it.id == currentId }
            acceptingReturnCoverBounds = playerReturnRevealIndex >= 0
            returnCoverBoundsResolved = false
            coverTargetForTransition = null

            // 只有返回 SONGS 场景时，列表才有封面元素可以做共享过渡；
            // 返回 HOME 等其他场景时，封面坐标全部是过期的，直接跳过。
            val returningToSongList = prePlayerContainerScene == com.rawsmusic.core.ui.scene.NavScene.SONGS

            if (!returningToSongList) {
                acceptingReturnCoverBounds = false
                playerReturnRevealIndex = -1
                playingCoverBoundsForTransition = null
                lockedPlayerCoverBoundsForTransition = null
                onReady()
            } else {
                val current = playerController?.currentSong?.value
                val fallbackTarget = miniPlayerCoverBoundsForTransition?.let {
                    val source = coverTargetForTransition?.source ?: CoverTransitionTarget.Source.MiniPlayer
                    val radius = coverTargetForTransition?.radiusDp
                        ?: if (source == CoverTransitionTarget.Source.MiniPlayer) 22f else 24f
                    CoverTransitionTarget(
                        bounds = android.graphics.RectF(it),
                        radiusDp = radius,
                        source = source,
                        songId = current?.id ?: -1L,
                        coverKey = current?.let { s -> resolveSongCoverForCompose(s) }.orEmpty()
                    )
                } ?: playingCoverBoundsForTransition?.let {
                    val radius = coverTargetForTransition?.radiusDp ?: 24f
                    CoverTransitionTarget(
                        bounds = android.graphics.RectF(it),
                        radiusDp = radius,
                        source = CoverTransitionTarget.Source.ListCover,
                        songId = current?.id ?: -1L,
                        coverKey = current?.let { s -> resolveSongCoverForCompose(s) }.orEmpty()
                    )
                } ?: lockedPlayerCoverBoundsForTransition?.let {
                    val radius = coverTargetForTransition?.radiusDp ?: 24f
                    CoverTransitionTarget(
                        bounds = android.graphics.RectF(it),
                        radiusDp = radius,
                        source = CoverTransitionTarget.Source.ListCover,
                        songId = current?.id ?: -1L,
                        coverKey = current?.let { s -> resolveSongCoverForCompose(s) }.orEmpty()
                    )
                }
                playingCoverBoundsForTransition = null
                lockedPlayerCoverBoundsForTransition = null
                if (playerReturnRevealIndex < 0) {
                    acceptingReturnCoverBounds = false
                    coverTargetForTransition = fallbackTarget
                    lockedPlayerCoverBoundsForTransition = fallbackTarget?.bounds?.let { android.graphics.RectF(it) }
                    onReady()
                } else {
                    val startedAt = System.currentTimeMillis()
                    fun waitForReturnBounds() {
                        if (!acceptingReturnCoverBounds) return
                        val hasTarget = returnCoverBoundsResolved && lockedPlayerCoverBoundsForTransition != null
                        val timedOut = System.currentTimeMillis() - startedAt >= 700L
                        if (hasTarget || timedOut || playerReturnRevealIndex < 0) {
                            acceptingReturnCoverBounds = false
                            if (!hasTarget && fallbackTarget != null) {
                                coverTargetForTransition = fallbackTarget
                                lockedPlayerCoverBoundsForTransition = android.graphics.RectF(fallbackTarget.bounds)
                            }
                            onReady()
                        } else {
                            mainHandler.postDelayed({ waitForReturnBounds() }, 16L)
                        }
                    }
                    waitForReturnBounds()
                }
            }
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
    private var visualizerLevels by mutableStateOf(FloatArray(80))
    private var playerAlbumSongs by mutableStateOf<List<AudioFile>>(emptyList())
    private var playerAlbumCoverPath by mutableStateOf<String?>(null)
    private val overlayCoordinator by lazy {
        OverlayCoordinator(
            isPlayerPageVisible = {
                playerSceneState.currentScene != com.rawsmusic.core.ui.widget.PlayerScene.MAIN
            }
        )
    }
    private val usbVolumeHideRunnable = Runnable { overlayCoordinator.hideUsbVolume() }
    private var folderPickerResultUri by mutableStateOf<android.net.Uri?>(null)

    private fun updateMiniPlayerBarSong() {
        miniPlayerCoordinator.updateSong(playerController?.currentSong?.value)
    }

    private fun updateMiniPlayerBarPlayback() {
        miniPlayerCoordinator.updatePlaybackState(
            playerController?.playState?.value == PlayState.PLAYING
        )
    }

    private fun resolveSongCoverForCompose(song: AudioFile): String {
        return if (::coverUriResolver.isInitialized) {
            coverUriResolver.resolveCoverUri(song).ifBlank { song.albumArtPath ?: "" }
        } else {
            song.albumArtPath ?: ""
        }
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
                dialogHelper.isShowing ||
                batteryOptimizationHelper.isShowing
        )
    }

    @Composable
    private fun PlayerOverlayContent() {
        if (!overlayCoordinator.composeOverlayContentVisible) return
        Box(Modifier.fillMaxSize()) {
            val visualizerPlayState by playerController?.playState?.collectAsState()
                ?: androidx.compose.runtime.mutableStateOf(PlayState.IDLE)
            val currentScene = playerSceneState.currentScene
            val controllerPlayerVisible = ::playerSceneController.isInitialized &&
                playerSceneController.composeIsTransitioning &&
                (
                    playerSceneController.composeFromScene != PlayerSceneController.Scene.MAIN ||
                        playerSceneController.composeToScene != PlayerSceneController.Scene.MAIN
                    )
            if (currentScene != com.rawsmusic.core.ui.widget.PlayerScene.MAIN || controllerPlayerVisible) {
                com.rawsmusic.core.ui.widget.PlayerDismissMotionHost(
                    openToken = currentScene.hashCode(),
                    onDismissProgressChange = { /* progress reporting if needed */ },
                    onDismiss = {
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.closeCurrentPlayerStackToMain(true)
                        }
                    },
                    // 禁用 PlayerDismissMotionHost 的 BackHandler，避免触发 LocalNavigationEventDispatcherOwner 崩溃
                    backEnabled = false,
                    gestureEnabled = !gestureLockCoordinator.isBlocked
                ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                )
                val currentSong by playerController?.currentSong?.collectAsState() ?: androidx.compose.runtime.mutableStateOf(null)
                val playState by playerController?.playState?.collectAsState()
                    ?: androidx.compose.runtime.mutableStateOf(PlayState.IDLE)
                val positionMs by playerController?.position?.collectAsState()
                    ?: androidx.compose.runtime.mutableStateOf(0L)
                val durationMs by playerController?.duration?.collectAsState()
                    ?: androidx.compose.runtime.mutableStateOf(0L)
                val playMode by playerController?.playMode?.collectAsState()
                    ?: androidx.compose.runtime.mutableStateOf(PlayMode.SEQUENTIAL)
                val queue by playerController?.queue?.collectAsState()
                    ?: androidx.compose.runtime.mutableStateOf(com.rawsmusic.core.common.model.PlayQueue())
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

                val displayPositionMs = if (isSeekUiHolding && seekTargetMs >= 0L) {
                    seekTargetMs
                } else {
                    positionMs
                }
                val displayLyricPositionMs = if (isSeekUiHolding && seekTargetMs >= 0L) {
                    (seekTargetMs - (playerController?.lyricManualOffsetMs?.toLong() ?: 0L))
                        .coerceAtLeast(0L)
                } else {
                    lyricsCoordinator.lyricPositionMs
                }
                val isPlayerReturningToMain =
                    playerSceneController.composeIsTransitioning &&
                        playerSceneController.composeFromScene == PlayerSceneController.Scene.PLAYER &&
                        playerSceneController.composeToScene == PlayerSceneController.Scene.MAIN
                val playerSharedSourceBounds = if (isPlayerReturningToMain) {
                    lockedPlayerCoverBoundsForTransition
                } else {
                    lockedPlayerCoverBoundsForTransition
                        ?: if (mainNavState.currentScene == com.rawsmusic.core.ui.scene.NavScene.SONGS) playingCoverBoundsForTransition else null
                }
                val playerSharedSourceTarget = if (isPlayerReturningToMain) {
                    coverTargetForTransition
                } else {
                    coverTargetForTransition ?: playerSharedSourceBounds?.let { bounds ->
                        val source = coverTargetForTransition?.source ?: CoverTransitionTarget.Source.ListCover
                        val radius = coverTargetForTransition?.radiusDp
                            ?: if (source == CoverTransitionTarget.Source.MiniPlayer) 22f else 24f
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
                val lyricSong = lyricsCoordinator.lyricSong
                val displayTranslation = lyricsCoordinator.displayTranslation
                val displayRoma = lyricsCoordinator.displayRoma
                val prioritySongs = playerController?.getPriorityQueue().orEmpty()
                val queueSongs = prioritySongs + queue.songs
                val queueCurrentIndex = queue.currentIndex + prioritySongs.size
                com.rawsmusic.core.ui.widget.ComposePlayerContainer(
                    sceneState = playerSceneState,
                    currentSong = currentSong,
                    coverPath = transitionCoverPath,
                    isPlaying = playState == PlayState.PLAYING,
                    currentPositionMs = displayPositionMs,
                    totalDurationMs = durationMs,
                    previousIconRes = R.drawable.ic_rewind_fill,
                    playIconRes = R.drawable.ic_play,
                    pauseIconRes = R.drawable.ic_pause,
                    nextIconRes = R.drawable.ic_speed_fill,
                    playModeIconRes = playModeIconRes(playMode),
                    moreIconRes = R.drawable.ic_more_vert,
                    audioQualityIconRes = R.drawable.ic_equalizer_bars,
                    audioInfoText = audioInfoCapsuleHelper.capsuleText,
                    onSeekStart = {
                        beginProgressSeek()
                    },
                    onSeekStop = { fraction ->
                        val seekPos = (fraction * durationMs).toLong()
                        startSeekUiHold(seekPos)
                        lyricsNeedSeekTo = true
                        playerController?.seekTo(seekPos)
                        endProgressSeek()
                    },
                    onPrevious = { playerController?.previous() },
                    onPlayPause = { playerController?.playPause() },
                    onNext = { playerController?.next() },
                    onPlayMode = {
                        playerController?.let { ctrl ->
                            ctrl.cyclePlayMode()
                            playModePopupHelper.updatePlayModeIcon(ctrl.playMode.value)
                        }
                    },
                    onPlayModeLongPress = { playModePopupHelper.show() },
                    onMore = { songActionSheetHelper.show() },
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
                    onOpenQueue = { openQueuePage() },
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
                            registerCoverCollapseParams()
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
                        if (index < prioritySongs.size) {
                            playerController?.play(song, queueSongs, index)
                        } else {
                            val adjustedIndex = index - prioritySongs.size
                            playerController?.play(song, queue.songs, adjustedIndex)
                        }
                    },
                    onClearPriorityQueue = { playerController?.clearPriorityQueue() },
                    albumSongs = playerAlbumSongs,
                    albumCoverPath = playerAlbumCoverPath,
                    onAlbumSongClick = { song, index -> playerController?.play(song, playerAlbumSongs, index) },
                    lyricSong = lyricSong,
                    lyricPositionMs = displayLyricPositionMs,
                    displayTranslation = displayTranslation,
                    displayRoma = displayRoma,
                    onLyricSeek = { ms ->
                        lyricsNeedSeekTo = true
                        playerController?.seekTo(ms)
                    },
                    onLyricTranslationToggle = {
                        lyricsCoordinator.toggleTranslation()
                    },
                    isImmersiveEnabled = composeImmersiveEnabled,
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
                        if (::playerSceneController.isInitialized) {
                            playerSceneController.disableGestureIntercept = visible
                        }
                    },
                    controllerScene = playerSceneController.composeCurrentScene,
                    controllerFromScene = playerSceneController.composeFromScene,
                    controllerToScene = playerSceneController.composeToScene,
                    controllerProgress = playerSceneController.composeTransitionProgress,
                    controllerIsTransitioning = playerSceneController.composeIsTransitioning,
                    sourceCoverTarget = playerSharedSourceTarget,
                    modifier = Modifier.fillMaxSize()
                )
                } // PlayerDismissMotionHost
            }
            if (AppPreferences.UI.isAudioVisualizerEnabled &&
                (currentScene != com.rawsmusic.core.ui.widget.PlayerScene.MAIN || controllerPlayerVisible) &&
                currentScene != com.rawsmusic.core.ui.widget.PlayerScene.QUEUE &&
                visualizerLevels.any { it > 0f }
            ) {
                com.rawsmusic.core.ui.widget.player.ComposeAudioVisualizer(
                    levels = visualizerLevels.toList(),
                    isActive = visualizerPlayState == PlayState.PLAYING,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(72.dp)
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            SongActionSheetOverlay(
                helper = songActionSheetHelper,
                isImmersiveEnabled = composeImmersiveEnabled
            )
            MetadataDetailOverlay(helper = metadataDetailHelper)
            MetadataCardPopupOverlay(helper = metadataCardPopupHelper)
            PlayModePopupOverlay(helper = playModePopupHelper)
            DialogOverlay(helper = dialogHelper)
            BatteryOptimizationOverlay(helper = batteryOptimizationHelper)
            MetadataEditorOverlay(helper = metadataEditorHelper)
            AudioInfoCapsuleOverlay(helper = audioInfoCapsuleHelper)
            UsbVolumeOverlay(
                visible = overlayCoordinator.isUsbVolumeOverlayVisible,
                text = overlayCoordinator.usbVolumeOverlayText,
                modifier = Modifier.align(Alignment.TopCenter)
            )
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

    private fun isAudioVisualizerUiActiveForPower(): Boolean {
        if (!AppPreferences.UI.isAudioVisualizerEnabled || !isPlayerUiVisibleForPower()) return false
        if (!::playerSceneController.isInitialized) return false
        return playerSceneController.currentScene != PlayerSceneController.Scene.QUEUE
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
            dialogVisible = dialogHelper.isShowing,
            batteryVisible = batteryOptimizationHelper.isShowing
        )
    }

    private fun showUsbVolumeOverlay(text: String) {
        overlayCoordinator.showUsbVolume(text)
        mainHandler.removeCallbacks(usbVolumeHideRunnable)
        mainHandler.postDelayed(usbVolumeHideRunnable, 1500L)
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
        PlayMode.SHUFFLE_ONCE -> R.drawable.ic_shuffle_fill
        PlayMode.REPEAT_ONE -> R.drawable.ic_repeat_one_fill
    }

    private fun initObserver() {
        coverCoordinator.start()
        observePlayerThroughCoordinator()
        observeUsbSampleRate()
        observePlayMode()
    }

    private fun observePlayerThroughCoordinator() {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            playerController?.playState?.collect { state ->
                playbackCoordinator.onPlaybackStateChanged(state)
            }
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            playerController?.currentSong?.collect { song ->
                if (song != null) {
                    playbackCoordinator.onCurrentSongChanged(song)
                }
            }
        }
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            playerController?.position?.collect { pos ->
                val duration = playerController?.duration?.value ?: 0L
                if (isSeekUiHolding && seekTargetMs >= 0L) {
                    val tolerance = (duration * 0.02f).toLong().coerceIn(300L, 2000L)
                    val elapsed = System.currentTimeMillis() - seekFinishTimeMs
                    if (kotlin.math.abs(pos - seekTargetMs) < tolerance || elapsed > 2000L) {
                        stopSeekUiHold()
                    }
                }
                playbackCoordinator.onPositionChanged(pos, duration)
            }
        }
    }

    /**
     * 监听异步封面提取完成事件，刷新封面和背景取色
     */
    private fun observeCoverExtracted() {
        coverUriResolver.coverExtractedEvent.observe(this) { event ->
            val (songPath, coverUri) = event ?: return@observe
            if (coverUri.isBlank()) return@observe

            // 更新封面 URI 缓存
            val currentSong = playerController?.currentSong?.value
            if (currentSong != null && songPath == currentSong.path) {
                coverUriResolver.updateCache(currentSong, coverUri)
                AppLogger.d("CoverDebug", "coverExtractedEvent: path=$coverUri for ${currentSong.title}")
                updateMiniPlayerBarSong()
            }
        }
    }

    private fun observePlaybackState() {
        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.playState?.collect { state ->
                val isPlaying = state == PlayState.PLAYING
                val song = playerController?.currentSong?.value
                playerSceneController.syncRotationState(isPlaying)
                playerSceneController.isCurrentlyPlaying = isPlaying
                updateMiniPlayerBarPlayback()

                LyriconProviderManager.setPlaybackState(isPlaying)
                LyricGetterBridge.updatePlaybackState(this@MainActivity, isPlaying)

                playerController?.currentSong?.value?.let { song ->
                    playerServiceBridgeHelper.pushSongUpdate(song)
                }
            }
        }
    }

    private fun observeCurrentSong() {
        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.currentSong?.collect { song ->
                song?.let {
                    AppLogger.d("MetaObserver", "song changed: ${it.title}, sr=${it.sampleRate}, br=${it.bitRate}, " +
                            "bps=${it.bitsPerSample}, ch=${it.channelCount}, isHiRes=${it.isHiRes}")

                    val coverUri = coverUriResolver.resolveCoverUri(it)
                    val playCoverUri = coverUri.ifBlank { it.albumArtPath }
                    if (!playerSceneController.composeIsTransitioning) {
                        lockedPlayerCoverPathForTransition = null
                        lockedPlayerCoverBoundsForTransition = null
                        coverTargetForTransition = null
                    }
                    playerReturnRevealIndex = MusicRepository.songs.value.indexOfFirst { song -> song.id == it.id }
                    updateMiniPlayerBarSong()
                    syncMirrorCover(playCoverUri.ifBlank { null })

                    loadLyrics(it.path)
                    updateCapsuleText()
                    updateHiresBadge()

                    playerServiceBridgeHelper.pushSongUpdate(it)
                }
            }
        }
    }

    private fun observeUsbSampleRate() {
        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.usbOutputSampleRate?.collect { sr ->
                if (sr > 0) {
                    updateCapsuleText()
                }
            }
        }
    }

    private fun observePosition() {
        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.position?.collect { pos ->
                val duration = playerController?.duration?.value ?: 0L
                val lyricOffset = playerController?.lyricManualOffsetMs?.toLong() ?: 0L
                val lyricPos = (pos - lyricOffset).coerceAtLeast(0L)

                // seek 目标检测：位置接近目标后清除 UI hold 状态
                if (isSeekUiHolding && seekTargetMs >= 0L) {
                    val tolerance = (duration * 0.02f).toLong().coerceIn(300L, 2000L)
                    val elapsed = System.currentTimeMillis() - seekFinishTimeMs
                    if (kotlin.math.abs(pos - seekTargetMs) < tolerance || elapsed > 2000L) {
                        stopSeekUiHold()
                    }
                }

                // 旧 observePosition 已停用，进度由 playbackCoordinator 管理
                // miniPlayerProgress = if (duration > 0) pos.toFloat() / duration else 0f
                composeLyricPositionMs = lyricPos
                val needSeekTo = lyricsNeedSeekTo
                if (needSeekTo) lyricsNeedSeekTo = false
                if (!currentLyricData.isEmpty) {
                    val lineIdx = currentLyricData.findCurrentLine(lyricPos)
                    if (lineIdx >= 0) {
                        val line = currentLyricData.getLine(lineIdx)
                        if (line != null) {
                            val lineTranslation = line.translation
                            val lineText = line.text
                            if (lineText != currentLyricText) {
                                currentLyricText = lineText
                                if (audioInfoCapsuleHelper.capsuleState == 4) updateCapsuleText()
                                if (currentLyricText.isNotBlank() && !isMusicSymbolOnly(currentLyricText)) {
                                    TickerBridge.updateLyric(this@MainActivity, currentLyricText, lineTranslation ?: "")
                                    LyricGetterBridge.updateLyric(this@MainActivity, currentLyricText, lineTranslation ?: "")
                                    BluetoothLyricBridge.updateLyric(currentLyricText, lineTranslation ?: "")
                                }
                            }
                        }
                    }
                } else if (currentLyricText.isNotEmpty()) {
                    currentLyricText = ""
                    TickerBridge.clearLyric(this@MainActivity)
                    LyricGetterBridge.clearLyric(this@MainActivity)
                    BluetoothLyricBridge.clearLyric()
                }
                val now = System.currentTimeMillis()
                if (now - lastSyncPositionTime >= 1000 && PlayerService.isRunning) {
                    lastSyncPositionTime = now
                    playerServiceBridgeHelper.syncPosition(pos)
                }
            }
        }
    }

    private fun observePlayMode() {
        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.playMode?.collect { mode ->
                playModePopupHelper.updatePlayModeIcon(mode)
            }
        }
    }

    /** 启动跑马灯效果（focusable + requestFocus） */
    private fun initListener() {}

    fun openPlayPageFromSongClick() {
        if (playerSceneController.currentScene != PlayerSceneController.Scene.MAIN) return
        openPlayPageWithSharedElement()
    }

    fun navigateToFolderFromSearch(folderPath: String) {
        try {
            updateComposeRootVisibility(true)
            mainNavState.navigateTo(
                com.rawsmusic.core.ui.scene.NavScene.FOLDER_HIERARCHY,
                folderPath
            )
        } catch (_: Exception) {}
    }


    private fun openPlayPageWithSharedElement() {
        if (playerSceneController.currentScene != PlayerSceneController.Scene.MAIN) return

        prePlayerContainerScene = mainNavState.currentScene
        if (isSideMenuOpen) closeSideMenu()

        updateComposeRootVisibility(true)
        registerCoverCollapseParams()
        playerController?.currentSong?.value?.let { song ->
            lockedPlayerCoverPathForTransition = lockedPlayerCoverPathForTransition ?: resolveSongCoverForCompose(song)
        }
        // playingCoverBounds 和 miniPlayerCoverBounds 都由 SongsPage 的回调设置，
        // 只有当前场景是 SONGS 时才有效；其他场景（HOME 等）用的是过期坐标，会导致
        // 返回时封面飞到不存在的位置。
        val hasCoverBounds = mainNavState.currentScene == com.rawsmusic.core.ui.scene.NavScene.SONGS
        val entryBounds = if (hasCoverBounds) {
            playingCoverBoundsForTransition?.let { android.graphics.RectF(it) }
                ?: miniPlayerCoverBoundsForTransition?.let { android.graphics.RectF(it) }
        } else {
            null
        }
        lockedPlayerCoverBoundsForTransition = entryBounds?.let { android.graphics.RectF(it) }
        coverTargetForTransition = if (entryBounds != null) {
            coverTargetForTransition
                ?.takeIf { target -> target.bounds.nearlyEquals(entryBounds, 2f) }
                ?.copy(bounds = android.graphics.RectF(entryBounds))
                ?: CoverTransitionTarget(
                    bounds = android.graphics.RectF(entryBounds),
                    radiusDp = if (playingCoverBoundsForTransition != null) 24f else 22f,
                    source = if (playingCoverBoundsForTransition != null) {
                        CoverTransitionTarget.Source.ListCover
                    } else {
                        CoverTransitionTarget.Source.MiniPlayer
                    }
                )
        } else {
            null
        }
        playerSceneController.openPlayPage(true)
    }

    // ==================== 封面手势处理 ====================

    private var isSwitchingSong = false
    private var currentLyricText = ""

    private fun setupAudioInfoCapsule() {
        audioInfoCapsuleHelper.setup()
    }

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

    private fun onLyricTapToPlayer() {
        playBackgroundState.syncFrom(lyricBackgroundState)
        playBackgroundState.resumeAnimations()
        playerSceneController.startLyricToPlayerDrag()
        playerSceneController.endLyricToPlayerDrag(shouldReturnToPlayer = true)
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

    private fun onImmersiveSwipeDown() {
        playerSceneController.closePlayPage(false)
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

    private fun showAlbumInfo() {
        albumInfoNavigator.open(playerController?.currentSong?.value)
    }

    private fun refreshQueueList() {
        // Queue is rendered by ComposePlayerContainer from PlayerController.queue.
    }

    private fun loadAlbumDetail(song: com.rawsmusic.core.common.model.AudioFile) {
        val queueSongs = playerController?.queue?.value?.songs.orEmpty()
        val albumSongs = queueSongs.filter { it.albumId == song.albumId && it.albumId > 0 }
            .ifEmpty { queueSongs.filter { it.album == song.album && song.album.isNotBlank() } }
            .ifEmpty { listOf(song) }
        playerAlbumSongs = albumSongs
        playerAlbumCoverPath = coverUriResolver.resolveCoverUri(song).ifBlank { song.albumArtPath }
    }

    private val logExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        logExportHelper.exportTo(uri)
    }

    private fun exportLogWithSaf() {
        logExportLauncher.launch(logExportHelper.createExportFileName())
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
        currentLyricData = data
        val song = playerController?.currentSong?.value
        composeLyricSong = if (!data.isEmpty && song != null) {
            data.toLyriconSong(
                name = song.title,
                artist = song.artist,
                durationMs = song.duration
            )
        } else {
            null
        }
        composeDisplayTranslation = com.rawsmusic.module.data.prefs.AppPreferences.Lyricon.displayTranslation
        composeDisplayRoma = com.rawsmusic.module.data.prefs.AppPreferences.Lyricon.displayRoma
        composeLyricPositionMs = playerController?.position?.value ?: 0L
    }

    private fun requestAudioPermission() {
        val permissions = audioPermissionHelper.requiredStartupPermissions()
        if (audioPermissionHelper.areGranted(permissions)) {
            scannerCoordinator.scheduleStartupScan()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private enum class BackDragType { NONE, COVER, CONTAINER }

    private fun setupEdgeToEdge() {
        systemBarsHelper.setupEdgeToEdge(isDarkMode)
    }

    /**
     * Android 14+ Predictive Back：侧边滑手势实时驱动场景动画
     *
     * 动态注册策略：
     *   - 有自定义动画时（播放/歌词/子页面）注册回调
     *   - 主界面无子页面时注销回调，让系统显示默认关闭动画
     *
     * 支持两种预测性返回：
     *   1. 播放界面/歌词界面 → 封面拖拽返回
     *   2. 主界面子页面 → 容器拖拽返回上级
     */
    private var predictiveBackCallback: android.window.OnBackAnimationCallback? = null
    private var isPredictiveBackRegistered = false

    @android.annotation.SuppressLint("NewApi")
    private fun setupPredictiveBack() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        predictiveBackCallback = object : android.window.OnBackAnimationCallback {
            private var dragType = BackDragType.NONE

            override fun onBackStarted(backEvent: android.window.BackEvent) {
                // 弹窗/浮层显示时不启动封面拖拽，交给 onBackInvoked → onBackPressed 关闭弹窗
                if (audioInfoCapsuleHelper.isPopupShowing ||
                    metadataEditorHelper.isMetadataEditorShowing ||
                    metadataEditorHelper.isDeleteConfirmShowing ||
                    metadataDetailHelper.isVisible ||
                    songActionSheetHelper.isSongActionSheetShowing ||
                    songActionSheetHelper.isPlaylistPickerShowing ||
                    playModePopupHelper.isShowing ||
                    metadataCardPopupHelper.isShowing
                ) {
                    dragType = BackDragType.NONE
                    return
                }

                val currentScene = playerSceneController.currentScene
                val swipeRight = backEvent.swipeEdge == android.window.BackEvent.EDGE_LEFT

                when {
                    // 播放界面 → 封面拖拽返回主界面
                    currentScene == PlayerSceneController.Scene.PLAYER -> {
                        playerSceneController.startCoverDrag(swipeRight, PlayerSceneController.Scene.MAIN)
                        dragType = BackDragType.COVER
                    }
                    // 子播放页的系统侧滑先回到播放页，保持播放器内部层级一致。
                    currentScene == PlayerSceneController.Scene.LYRIC -> {
                        playerSceneController.startLyricToPlayerDrag()
                        dragType = BackDragType.COVER
                    }
                    currentScene == PlayerSceneController.Scene.QUEUE -> {
                        playerSceneController.startCoverDrag(swipeRight, PlayerSceneController.Scene.MAIN)
                        dragType = BackDragType.COVER
                    }
                    currentScene == PlayerSceneController.Scene.ALBUM_DETAIL -> {
                        playerSceneController.startCoverDrag(swipeRight, PlayerSceneController.Scene.PLAYER)
                        dragType = BackDragType.COVER
                    }
                    // 主界面 + 不在HOME → Compose 导航返回
                    currentScene == PlayerSceneController.Scene.MAIN &&
                            !mainNavState.isAtHome() -> {
                        val direction = if (swipeRight) 1f else -1f
                        dragType = if (mainNavState.startBackDrag(direction)) {
                            BackDragType.CONTAINER
                        } else {
                            BackDragType.NONE
                        }
                    }
                }
            }

            override fun onBackProgressed(backEvent: android.window.BackEvent) {
                when (dragType) {
                    BackDragType.COVER -> playerSceneController.updateCoverDragProgress(backEvent.progress)
                    BackDragType.CONTAINER -> mainNavState.updateBackDrag(backEvent.progress)
                    BackDragType.NONE -> {}
                }
            }

            override fun onBackInvoked() {
                when (dragType) {
                    BackDragType.COVER -> {
                        playerSceneController.releaseCoverDrag(true, 0f)
                        dragType = BackDragType.NONE
                    }
                    BackDragType.CONTAINER -> {
                        mainNavState.releaseBackDrag(commit = true)
                        dragType = BackDragType.NONE
                    }
                    BackDragType.NONE -> {
                        // 弹窗/浮层显示时优先关闭弹窗
                        if (audioInfoCapsuleHelper.isPopupShowing) {
                            audioInfoCapsuleHelper.dismissPopup()
                        } else if (metadataDetailHelper.isVisible) {
                            metadataDetailHelper.close()
                        } else if (playModePopupHelper.isShowing) {
                            playModePopupHelper.hide()
                        } else {
                            val scene = playerSceneController.currentScene
                            if (scene != PlayerSceneController.Scene.MAIN) {
                                playerSceneController.closeCurrentPlayerStackToMain(true)
                            } else {
                                @Suppress("DEPRECATION")
                                onBackPressed()
                            }
                        }
                        dragType = BackDragType.NONE
                    }
                }
            }

            override fun onBackCancelled() {
                when (dragType) {
                    BackDragType.COVER -> {
                        playerSceneController.releaseCoverDrag(false, 0f)
                        dragType = BackDragType.NONE
                    }
                    BackDragType.CONTAINER -> {
                        mainNavState.releaseBackDrag(commit = false)
                        dragType = BackDragType.NONE
                    }
                    BackDragType.NONE -> {}
                }
            }
        }

        updatePredictiveBackRegistration()
    }

    /**
     * 动态注册/注销预测性返回回调。
     * 有自定义动画时注册，主界面无子页面时注销（让系统显示默认关闭动画）。
     */
    @android.annotation.SuppressLint("NewApi")
    private fun updatePredictiveBackRegistration() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val callback = predictiveBackCallback ?: return

        // 始终注册回调，避免 enableOnBackInvokedCallback=true 时系统默认 finish()
        try {
            if (!isPredictiveBackRegistered) {
                onBackInvokedDispatcher.registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
                )
                isPredictiveBackRegistered = true
            }
        } catch (_: Exception) {}
    }

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

    /** 显示全屏封面查看器 */
    private fun showFullCoverViewer() {
        if (playerController?.currentSong?.value == null) return
        playerSceneState.openFullCover()
        updateComposeRootVisibility(true)
    }

    /**
     * 处理返回键事件，根据当前页面层级决定是否退出应用
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 纯 Compose 播放器返回处理
        // Compose 播放器辅助页面（歌词、队列等）的返回处理
        if (playerSceneState.currentScene != com.rawsmusic.core.ui.widget.PlayerScene.MAIN &&
            playerSceneState.currentScene != com.rawsmusic.core.ui.widget.PlayerScene.PLAYER
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
        if (batteryOptimizationHelper.isShowing) {
            batteryOptimizationHelper.dismiss()
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
                // Compose 模式：如果不是 HOME，返回 HOME
                if (!mainNavState.isAtHome()) {
                    mainNavState.navigateHome()
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

    private companion object {
        const val VISUALIZER_UI_FRAME_INTERVAL_MS = 100L
    }

    override fun onResume() {
        super.onResume()
        activityForegroundForPower = true
        composeActivityForeground = true
        setUsbAttachAliasEnabled(true, "on_resume_restore")
        if (!::playerSceneController.isInitialized) return

        // 从 SettingsActivity 返回，保持之前的导航栈
        if (settingsActivityLaunched) {
            settingsActivityLaunched = false
            android.util.Log.d("SettingsReturn", "returned from settings: currentScene=${mainNavState.currentScene}, backStack=${mainNavState.backStack}")
        }

        playbackStatsHelper.start()

        playerSceneController.resetInteractionState()

        playerSceneController.refreshImmersiveState(com.rawsmusic.module.data.prefs.AppPreferences.UI.isImmersiveEnabled)
        playerSceneController.updateMiniCoverEnabled(com.rawsmusic.module.data.prefs.AppPreferences.UI.isMiniCoverEnabled)

        val currentScene = playerSceneController.currentScene

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
                            playerSceneController.switchToSceneSilent(targetScene)
                            if (targetScene == PlayerSceneController.Scene.LYRIC) {
                                registerCoverLyricParams()
                            } else {
                                registerCoverCollapseParams()
                            }
                            playerSceneController.forceReapplyCurrentScene()
                            updateHiresBadge()
                        }
                    }
                }
            }
        }

        mainHandler.post {
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

    override fun onStop() {
        activityForegroundForPower = false
        composeActivityForeground = false
        visualizerLevels = FloatArray(80)
        super.onStop()
    }

    override fun onDestroy() {
        val finishing = isFinishing
        val changingConfig = isChangingConfigurations
        AppLogger.w("SceneTransition", "=== MainActivity.onDestroy CALLED, isFinishing=$finishing, isChangingConfigurations=$changingConfig ===")
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
            // 配置变化（主题切换、旋转等）：只暂停位置同步，保留 provider
            LyriconProviderManager.stopPositionSync()
            AppLogger.w("SceneTransition", "=== onDestroy: NOT finishing, keeping PlayerController + Lyricon alive ===")
        }
    }

}
