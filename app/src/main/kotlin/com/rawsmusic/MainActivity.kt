package com.rawsmusic

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.File
import java.io.FileOutputStream
import android.graphics.RectF
import android.util.Log
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import io.github.proify.lyricon.lyric.view.RawsLyricView
import io.github.proify.lyricon.lyric.view.PlaceholderFormat
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.viewModels
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.common.base.BaseActivity
import com.rawsmusic.core.common.ext.isDarkMode
import com.rawsmusic.core.common.ext.visible
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.toLyriconSong
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.common.utils.UiUtils
import com.rawsmusic.core.ui.R as UiR
import com.rawsmusic.core.ui.adapter.SongDataProvider
import com.rawsmusic.core.ui.animation.ButtonAnimHelper
import com.rawsmusic.core.ui.theme.CoverColorExtractor
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.AnimatedMoreButton
import com.rawsmusic.core.ui.widget.CoverGradientDrawable
import com.rawsmusic.core.ui.widget.scene.AAItemView
import androidx.compose.foundation.layout.fillMaxWidth
import com.rawsmusic.core.ui.widget.SideMenuView
import com.rawsmusic.core.ui.widget.UnifiedPlayerContainer
import com.rawsmusic.databinding.ActivityMainBinding
import com.rawsmusic.module.data.repository.MusicRepository
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.FontManager
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
import com.rawsmusic.core.ui.util.AdaptivePadTransformation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import com.rawsmusic.gesture.CoverAnimState
import com.rawsmusic.gesture.CoverGestureHandler
import com.rawsmusic.gesture.DrawerState
import com.rawsmusic.gesture.FullCoverViewerHelper
import com.rawsmusic.gesture.LyricSwipeState
import com.rawsmusic.gesture.PlayAreaSwipeState
import com.rawsmusic.helper.AlbumDetailHelper
import com.rawsmusic.helper.AlbumInfoNavigator
import com.rawsmusic.helper.AudioCapsuleUiHelper
import com.rawsmusic.helper.AudioPermissionHelper
import com.rawsmusic.helper.AudioInfoCapsuleHelper
import com.rawsmusic.helper.AudioVisualizerHelper
import com.rawsmusic.helper.BatteryOptimizationHelper
import com.rawsmusic.helper.DialogHelper
import com.rawsmusic.helper.DrawerMotionHelper
import com.rawsmusic.helper.EffectsPanelHelper
import com.rawsmusic.helper.LastPlayingStateHelper
import com.rawsmusic.helper.LogExportHelper
import com.rawsmusic.helper.LyricHeaderHelper
import com.rawsmusic.helper.LyricLoadHelper
import com.rawsmusic.helper.LyricStyleHelper
import com.rawsmusic.helper.MetadataCardPopupHelper
import com.rawsmusic.helper.MetadataDetailHelper
import com.rawsmusic.helper.MetadataEditorHelper
import com.rawsmusic.helper.PlaybackStatsHelper
import com.rawsmusic.helper.PlayerActionObserverHelper
import com.rawsmusic.helper.PlayerControllerBindingHelper
import com.rawsmusic.helper.PlayModePopupHelper
import com.rawsmusic.helper.Quad
import com.rawsmusic.helper.PlayerServiceBridgeHelper
import com.rawsmusic.helper.PlayerSubPageHelper
import com.rawsmusic.helper.QueueListHelper
import com.rawsmusic.helper.SearchStateHelper
import com.rawsmusic.helper.SongActionSheetHelper
import com.rawsmusic.helper.StartupPermissionFlowHelper
import com.rawsmusic.helper.StartupScanHelper
import com.rawsmusic.helper.SystemBarsHelper
import com.rawsmusic.helper.TextMarqueeHelper
import com.rawsmusic.helper.UsbVolumeKeyHandler


import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
class MainActivity : BaseActivity<ActivityMainBinding>(), CoverGestureHandler.CoverGestureCallbacks {

    override val bindingInflater = { ActivityMainBinding.inflate(layoutInflater) }

    private val lyricPlayerView: RawsLyricView get() = binding.lyricView as RawsLyricView
    private val playCoverView: com.rawsmusic.core.ui.widget.CoverImageView get() = binding.ivPlayCover as com.rawsmusic.core.ui.widget.CoverImageView
    private var currentLyricData: LyricData = LyricData()

    private lateinit var navController: NavController
    private lateinit var unifiedContainer: UnifiedPlayerContainer
    internal var playerController: PlayerController? = null
    private val globalSettingsVM: GlobalSettingsViewModel by viewModels()

    // 封面手势处理器
    private lateinit var coverGestureHandler: CoverGestureHandler

    // 封面 URI 解析器
    private lateinit var coverUriResolver: com.rawsmusic.helper.CoverUriResolver

    // 封面背景管理器
    private lateinit var coverBackgroundManager: com.rawsmusic.helper.CoverBackgroundManager

    // 场景参数注册中心
    private lateinit var sceneRegistry: com.rawsmusic.helper.SceneRegistry

    // 场景参数注册函数族
    private lateinit var sceneParamsHelper: com.rawsmusic.helper.SceneParamsHelper

    // 封面布局参数计算与横竖屏切换
    private lateinit var coverLayoutHelper: com.rawsmusic.helper.CoverLayoutHelper

    /** 是否正在拖动进度条 */
    private var isSeeking = false
    // seek 目标位置，用于延迟清除 seeking 状态避免 UI 回跳
    private var seekTargetMs: Long = -1L
    private var seekFinishTimeMs: Long = 0L
    // seek 后歌词需要执行一次 seekTo（而非 setPosition）以重置内部时钟
    private var lyricsNeedSeekTo = false

    private val playAreaSwipe = PlayAreaSwipeState()
    private val playAreaCoverLoc = IntArray(2)

    private val lyricSwipe = LyricSwipeState()
    private val lyricHSwipeThreshold by lazy { resources.getDimension(R.dimen.lyric_h_swipe_threshold) }

    /** 是否手动拖拽侧边栏 */
    private val drawerState = DrawerState()
    private val coverAnimState = CoverAnimState()
    private val drawerMotionHelper by lazy {
        DrawerMotionHelper(
            binding,
            unifiedContainer,
            drawerState,
            ::applyDrawerColorSync
        )
    }

    /** 已加载封面图片的实际尺寸，用于动态计算容器宽高比 */
    private var loadedCoverImageWidth = 0
    private var loadedCoverImageHeight = 0

    /** 进入播放器前的 Fragment 导航目标，用于返回时恢复正确的页面 */
    private var prePlayerFragmentDest: Int? = null
    /** 进入播放器前是否在 Fragment 模式（而非 UnifiedMainContainer 内部页面模式） */
    private var prePlayerWasInFragmentMode: Boolean = false
    /** 进入播放器前 UnifiedMainContainer 的当前场景（容器模式下使用） */
    private var prePlayerContainerScene: com.rawsmusic.core.ui.scene.NavScene? = null
    /** 从专辑详情页进入播放器时，保存专辑封面的屏幕坐标，用于返回时动画对齐 */
    private var savedAlbumDetailCoverRect: android.graphics.RectF? = null
    /** 从歌曲列表进入播放器时，保存列表封面的屏幕坐标，用于返回时与进入动画保持同一落点 */
    private var savedListCoverRect: android.graphics.RectF? = null

    // ==================== Compose 状态属性 ====================
    /** Compose 可观察的播放状态 */
    var composeIsPlaying by mutableStateOf(false)
        private set

    /** Compose 可观察的当前播放进度 (0..1) */
    var composePlayProgress by mutableFloatStateOf(0f)
        private set

    /** Compose 可观察的总时长 (ms) */
    var composeTotalDurationMs by mutableLongStateOf(0L)
        private set

    /** Compose 可观察的当前播放位置 (ms) */
    var composeCurrentPositionMs by mutableLongStateOf(0L)
        private set

    /** Compose 可观察的播放模式 */
    var composePlayMode by mutableStateOf(PlayMode.SEQUENTIAL)
        private set

    /*观察播放器动作（通过 PlayerEventBus）*/
    private fun observePlayerActions() {
        playerActionObserverHelper.observe()
    }

    private val settingsChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                "com.rawsmusic.action.IMMERSIVE_SETTING_CHANGED" -> {
                    if (::unifiedContainer.isInitialized) {
                        // 先应用默认背景设置，确保 isDefaultBackgroundEnabled 在 refreshImmersiveState 之前更新
                        applyDefaultBackground()
                        unifiedContainer.refreshImmersiveState(com.rawsmusic.module.data.prefs.AppPreferences.UI.isImmersiveEnabled)
                        playerController?.currentSong?.value?.let { song ->
                            val coverUri = coverUriResolver.resolveCoverUri(song)
                            val playCoverUri = coverUri.ifBlank { song.albumArtPath }
                            unifiedContainer.updateImmersiveCover(playCoverUri)
                        }
                        unifiedContainer.post {
                            setupCoverLayoutParams()
                            updateHiresBadge()
                        }
                    }
                }
                "com.rawsmusic.action.DEFAULT_BACKGROUND_SETTING_CHANGED" -> {
                    applyDefaultBackground()
                }
                "com.rawsmusic.action.MINI_COVER_SETTING_CHANGED" -> {
                    if (::unifiedContainer.isInitialized) {
                        unifiedContainer.updateMiniCoverEnabled(com.rawsmusic.module.data.prefs.AppPreferences.UI.isMiniCoverEnabled)
                        playerController?.currentSong?.value?.let { song ->
                            val coverUri = coverUriResolver.resolveCoverUri(song)
                            val playCoverUri = coverUri.ifBlank { song.albumArtPath }
                            unifiedContainer.updateImmersiveCover(playCoverUri)
                        }
                        unifiedContainer.post {
                            setupCoverLayoutParams()
                            updateHiresBadge()
                        }
                    }
                }
                "com.rawsmusic.action.FLOWING_LIGHT_SETTING_CHANGED" -> {
                    if (::unifiedContainer.isInitialized) {
                        unifiedContainer.post {
                            unifiedContainer.forceReapplyCurrentScene()
                        }
                    }
                }
            }
        }
    }

    /** 上次同步播放位置的时间*/
    private var lastSyncPositionTime = 0L

    /** 全屏封面查看器辅助类 */
    private lateinit var fullCoverViewerHelper: FullCoverViewerHelper

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startupScanHelper.start()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun finish() {
        AppLogger.w("SceneTransition", "=== MainActivity.finish() CALLED ===", Exception("finish() stacktrace"))
        super.finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 初始化 PlayerController，如果当前没有 controller 则创建
        if (playerController == null) {
            playerController = PlayerController.getInstance(this)
            PlayerHolder.controller = playerController
        }
        // USB 独占激活后引导用户加入电池优化白名单
        playerController?.onUsbExclusiveActivated = {
            batteryOptimizationHelper.promptWhitelistForUsbExclusive()
        }
        ThemeManager.applyTheme(ThemeManager.getCurrentTheme())
        super.onCreate(savedInstanceState)

        // 默认背景关闭时，动态封面背景使用深色兜底，避免亮色主题的白底残留。
        val isLightTheme = !ThemeManager.isDarkMode(this)
        binding.backgroundView.setThemeLightMode(
            isLightTheme && com.rawsmusic.module.data.prefs.AppPreferences.UI.isDefaultBackgroundEnabled
        )

        // 默认背景模式：启动时立即应用纯色背景
        if (com.rawsmusic.module.data.prefs.AppPreferences.UI.isDefaultBackgroundEnabled) {
            binding.backgroundView.visibility = View.GONE
            binding.drawerLayout.setBackgroundColor(if (isLightTheme) Color.WHITE else Color.BLACK)
            ThemeManager.isLightBackground = isLightTheme
        } else {
            binding.drawerLayout.setBackgroundColor(Color.TRANSPARENT)
            ThemeManager.isLightBackground = false
        }

        prePlayerWasInFragmentMode = savedInstanceState?.getBoolean("prePlayerWasInFragmentMode", false)
            ?: com.rawsmusic.module.data.prefs.AppPreferences.UI.wasInFragmentMode
        val savedDest = savedInstanceState?.getInt("prePlayerFragmentDest", -1)
            ?: com.rawsmusic.module.data.prefs.AppPreferences.UI.lastFragmentDest
        if (savedDest != -1) prePlayerFragmentDest = savedDest
        savedInstanceState?.getString("prePlayerContainerScene")?.let {
            prePlayerContainerScene = com.rawsmusic.core.ui.scene.NavScene.entries.find { s -> s.name == it }
        }
        FontManager.init(this)
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                FontManager.applyRecursive(binding.root)
            }
        })
        requestAudioPermission()

        metadataEditorHelper

        val filter = android.content.IntentFilter().apply {
            addAction("com.rawsmusic.action.IMMERSIVE_SETTING_CHANGED")
            addAction("com.rawsmusic.action.DEFAULT_BACKGROUND_SETTING_CHANGED")
            addAction("com.rawsmusic.action.MINI_COVER_SETTING_CHANGED")
            addAction("com.rawsmusic.action.FLOWING_LIGHT_SETTING_CHANGED")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsChangeReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(settingsChangeReceiver, filter)
        }
    }




    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::unifiedContainer.isInitialized) return
        val currentScene = unifiedContainer.currentScene
        val isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        adjustLayoutForOrientation(newConfig.orientation)
        if (currentScene == UnifiedPlayerContainer.Scene.PLAYER ||
            currentScene == UnifiedPlayerContainer.Scene.LYRIC) {
            unifiedContainer.post {
                setupCoverLayoutParams()
                setupSceneParams()
                updateHiresBadge()
                if (currentScene == UnifiedPlayerContainer.Scene.LYRIC) {
                    registerCoverLyricParams()
                }
                unifiedContainer.switchToSceneSilent(currentScene)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("prePlayerWasInFragmentMode", prePlayerWasInFragmentMode)
        prePlayerFragmentDest?.let { outState.putInt("prePlayerFragmentDest", it) }
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
            this,
            resources,
            { playerController },
            { window.decorView as? android.view.ViewGroup }
        )
    }
    private val batteryOptimizationHelper by lazy { BatteryOptimizationHelper(this) }
    private val dialogHelper by lazy { DialogHelper(this) }
    private val logExportHelper by lazy { LogExportHelper(this) }
    private val metadataCardPopupHelper by lazy { MetadataCardPopupHelper(binding, resources) }
    private val startupScanHelper by lazy { StartupScanHelper(this, lifecycleScope) }
    private val systemBarsHelper by lazy { SystemBarsHelper(this) }
    private val effectsPanelHelper by lazy { EffectsPanelHelper(binding) { playerController } }
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
    private val playerSubPageHelper by lazy {
        PlayerSubPageHelper(
            unifiedContainer,
            { playerController?.currentSong?.value },
            { refreshQueueList() },
            { song -> loadAlbumDetail(song) },
            { syncEffectsPanelState() }
        )
    }
    private val albumDetailHelper by lazy {
        AlbumDetailHelper(
            this,
            binding,
            lifecycleScope,
            { playerController },
            { song: AudioFile -> coverUriResolver.resolveCoverUri(song) },
            { setupAlbumDetailEffectCard() }
        )
    }
    private val lyricHeaderHelper by lazy {
        LyricHeaderHelper(
            binding,
            { playerController?.currentSong?.value },
            { song: AudioFile -> coverUriResolver.resolveCoverUri(song) }
        )
    }
    private val lyricStyleHelper by lazy {
        LyricStyleHelper(binding, lyricPlayerView) { playerController }
    }
    private val lyricLoadHelper by lazy {
        LyricLoadHelper(
            this,
            lifecycleScope,
            unifiedContainer,
            lyricPlayerView,
            { playerController?.currentSong?.value },
            { data -> currentLyricData = data },
            { _ -> /* mini lyric removed */ },
            { currentLyricText = "" },
            { updateLyricAnchor() },
            { applyLyricColors() },
            { playerServiceBridgeHelper.pushLyricsUpdate() }
        )
    }
    private val searchStateHelper by lazy {
        SearchStateHelper(unifiedMainContainer)
    }
    private val audioVisualizerHelper by lazy {
        AudioVisualizerHelper(this) { playerController }
    }
    private val albumInfoNavigator by lazy {
        AlbumInfoNavigator(navController)
    }
    private val lastPlayingStateHelper by lazy {
        LastPlayingStateHelper(
            binding,
            playCoverView,
            { playerController },
            coverUriResolver::resolveCoverUri,
            ::syncMirrorCover,
            ::loadCoverBackground
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
    private val queueListHelper by lazy {
        QueueListHelper(this, binding, { playerController }) { refreshQueueList() }
    }
    private val audioInfoCapsuleHelper by lazy { AudioInfoCapsuleHelper(this, binding) { playerController } }
    private val audioCapsuleUiHelper by lazy {
        AudioCapsuleUiHelper(
            unifiedContainer,
            audioInfoCapsuleHelper,
            { currentLyricText }
        )
    }
    private val metadataEditorHelper: MetadataEditorHelper by lazy { MetadataEditorHelper(
        this, { binding }, { playerController },
        { s: AudioFile -> coverUriResolver.resolveCoverUri(s) }, { uri -> syncMirrorCover(uri) },
        { songActionSheetHelper.hide() },
        { v -> songActionSheetHelper.hasCustomCover = v },
        { songActionSheetHelper.updateCoverRestoreButton() }
    ) }
    private val songActionSheetHelper: SongActionSheetHelper by lazy {
        SongActionSheetHelper(this, binding, { playerController }, unifiedContainer, { navController }, { s: AudioFile -> coverUriResolver.resolveCoverUri(s) }, { lifecycleScope }).apply {
            onEditMetadata = { metadataEditorHelper.editMetadata() }
            onOpenMetadataDetail = { metadataDetailHelper.open() }
            onShowSleepTimer = { dialogHelper.showSleepTimer(playerController) }
            onDeleteCurrentSong = { metadataEditorHelper.deleteCurrentSong() }
            onPickCoverImage = { metadataEditorHelper.pickCoverImage() }
            onRestoreCover = { metadataEditorHelper.restoreOriginalCover() }
        }
    }
    private val metadataDetailHelper by lazy { MetadataDetailHelper(this, binding, { playerController }, unifiedContainer) }
    private val playModePopupHelper by lazy { PlayModePopupHelper(this, { binding }, { playerController }) { window.decorView as? android.view.ViewGroup } }
    private fun adjustLayoutForOrientation(orientation: Int) {
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        coverLayoutHelper.adjustLayoutForOrientation(orientation, rotation)
    }

    private fun applyLandscapeLyricConstraints(isReverse: Boolean) {
        coverLayoutHelper.applyLandscapeLyricConstraints(isReverse)
    }

    private fun restorePortraitLyricLayout() {
        coverLayoutHelper.restorePortraitLyricLayout()
    }

    private fun updateLyricSongInfo() {
        lyricHeaderHelper.updateSongInfo()
    }

    override fun initView() {
        val tInitStart = System.currentTimeMillis()
        AppLogger.d("Startup", "initView: start")

        // 在onCreate 中初始化 PlayerController 相关组件
        if (playerController == null) {
            playerController = PlayerController.getInstance(this)
        }
        PlayerHolder.controller = playerController

        // 初始化封面 URI 解析器
        coverUriResolver = com.rawsmusic.helper.CoverUriResolver(this)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // 统一为所有设置 Fragment 的根 View 设置不透明背景
        // 解决 Jetpack Navigation 中新旧 Fragment 视图叠加导致的重叠问题
        navHostFragment.childFragmentManager.registerFragmentLifecycleCallbacks(
            object : androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewCreated(fm: androidx.fragment.app.FragmentManager, f: androidx.fragment.app.Fragment, v: View, savedInstanceState: Bundle?) {
                    if (v.background == null) {
                        val isDark = com.rawsmusic.core.ui.theme.ThemeManager.isDarkMode(this@MainActivity)
                        v.setBackgroundColor(if (isDark) 0xFF2A2624.toInt() else 0xFFF7F6F4.toInt())
                    }
                }
            }, false
        )

        // 关键修复：在 UI 收集 StateFlow 之前，先从 MMKV 加载缓存数据到内存
        // 这样 observeMainContainerFlows() 收集时就能立即拿到数据，不用等扫描完成
        val tPreload = System.currentTimeMillis()
        MusicRepository.refreshAll()
        AppLogger.d("Startup", "initView: MusicRepository.refreshAll() preload done in ${System.currentTimeMillis() - tPreload}ms")

        setupUnifiedMainContainer()
        AppLogger.d("Startup", "initView: setupUnifiedMainContainer done in ${System.currentTimeMillis() - tInitStart}ms total")
        setupUnifiedContainer()

        // 初始化封面背景管理器
        coverBackgroundManager = com.rawsmusic.helper.CoverBackgroundManager(
            lifecycleOwner = this,
            playBgView = binding.playBgView,
            lyricBgView = binding.lyricBgView,
            backgroundView = binding.backgroundView,
            immersiveBackground = binding.immersiveBackground,
            mainPersistentCover = binding.mainPersistentCover,
            mainBgScrim = binding.mainBgScrim,
            playBgScrim = binding.playBgScrim,
            drawerLayout = binding.drawerLayout,
            sideMenu = binding.sideMenu,
            unifiedMainContainer = unifiedMainContainer,
            unifiedContainer = unifiedContainer,
            coverUriResolver = coverUriResolver,
            applyLyricColors = { lyricStyleHelper.applyLyricColors() }
        )

        // 恢复上次播放状态（需要在 coverBackgroundManager 初始化之后）
        restoreLastPlayingState()

        // 在 unifiedContainer 初始化后同步默认背景状态；关闭状态也要走恢复分支，避免冷启动亮色主题白底残留。
        applyDefaultBackground()

        setupDrawerLayout()
        setupSideMenu()
        playerController?.setEqualizerController { newSessionId ->
            binding.audioVisualizer?.bindAudioSession(newSessionId)
        }
        playerController?.onPcmWaveformFrame = { buffer, read, channels, sampleRate, bitsPerSample ->
            binding.audioVisualizer?.post {
                binding.audioVisualizer?.updatePcmWaveform(buffer, read, channels, sampleRate, bitsPerSample)
            }
        }
        setupPlayPageListeners()
        setupLyricPageListeners()
        metadataDetailHelper.setup()
        setupEdgeToEdge()
        setupPredictiveBack()

        // 绑定PlayerService服务连接
        playerServiceBridgeHelper.startForegroundServiceIfNeeded()

        // 使用StateFlow和SharedFlow来管理状态       observePlayerActions()

        LyriconProviderManager.init(this, R.mipmap.ic_launcher)
        playerController?.let { LyriconProviderManager.startPositionSync(it) }

        LyricGetterBridge.init(this)

        LyriconProviderManager.onProviderConnected = {
            val currentSong = playerController?.currentSong?.value
            val isPlaying = playerController?.playState?.value == PlayState.PLAYING
            LyriconProviderManager.setSong(currentSong, if (currentLyricData.isEmpty) null else currentLyricData)
            LyriconProviderManager.setPlaybackState(isPlaying)
        }
    }

    /**
     * DDrawerLayout 侧边栏设置...HOME场景时允许滑动手动打开侧边栏...深层页面时禁用侧边栏...    */
    private fun setupDrawerLayout() {
        drawerMotionHelper.setupLayout(
            screenWidth = resources.displayMetrics.widthPixels,
            menuWidthRatio = resources.getFloat(R.dimen.side_menu_width_ratio)
        )
    }

    private fun openSideMenu() {
        drawerMotionHelper.open()
    }

    /**
     * 从 UnifiedMainContainer 模式切换到 Fragment 模式
     * 注册场景参数、切换可见性
     */
    private fun switchToFragmentMode() {
        // 切换到 Fragment 模式：显示 Fragment，隐藏容器
        binding.navHostFragment.visibility = View.VISIBLE
        unifiedMainContainer?.visibility = View.GONE
    }

    /**
     * 播放页弹窗里的可点击音频信息入口。
     * 如果当前在播放器/歌词页，复用播放页返回 MAIN 的恢复链路，让目标设置页可见。
     */
    fun openDestinationFromPlayerPopup(destinationId: Int) {
        try {
            registerCoverCollapseParams()
        } catch (_: Exception) {}

        prePlayerWasInFragmentMode = true
        prePlayerFragmentDest = destinationId

        val scene = try {
            unifiedContainer.currentScene
        } catch (_: Exception) {
            null
        }
        if (scene == UnifiedPlayerContainer.Scene.LYRIC) {
            unifiedContainer.closeLyricPage(true)
            unifiedContainer.postDelayed({
                if (unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.PLAYER) {
                    unifiedContainer.closePlayPageWithCoverAlign(true)
                }
            }, 180L)
            return
        }
        if (scene == UnifiedPlayerContainer.Scene.PLAYER) {
            unifiedContainer.closePlayPageWithCoverAlign(true)
            return
        }

        // 设置页面现在使用独立 Activity，无需 fragment 模式切换
        startActivity(android.content.Intent(this, com.rawsmusic.ui.settings.SettingsActivity::class.java))
    }

    fun navigateSettingsForward(destinationId: Int) {
        startActivity(android.content.Intent(this, com.rawsmusic.ui.settings.SettingsActivity::class.java))
    }

    fun navigateSettingsBack() { finish() }

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
            R.id.nav_global_font_settings, R.id.nav_webdav_backup, R.id.nav_log_viewer
        )
    }

    private fun switchToContainerMode(targetScene: com.rawsmusic.core.ui.scene.NavScene? = null) {
        if (unifiedMainContainer == null) return
        // 先隐藏 Fragment
        binding.navHostFragment.visibility = View.GONE
        // 先静默切换场景（内部会隐藏其他页面），再显示容器，避免闪现所有内容
        if (targetScene != null) {
            unifiedMainContainer?.switchToScene(targetScene)
        }
        unifiedMainContainer?.visibility = View.VISIBLE
    }

    private fun closeSideMenu() {
        drawerMotionHelper.close()
    }

    private fun setupSideMenu() {
        val menuGroups = listOf(
            "音乐" to listOf(
                SideMenuView.MenuItem(R.id.nav_songs, "歌曲", R.drawable.ic_music_note_dark),
                SideMenuView.MenuItem(R.id.nav_albums, "专辑", R.drawable.ic_album),
                SideMenuView.MenuItem(R.id.nav_artists, "艺术家", R.drawable.ic_person),
                SideMenuView.MenuItem(R.id.nav_song_stats, "听歌统计", UiR.drawable.ic_bar_chart),
                SideMenuView.MenuItem(R.id.nav_webdav, "WebDAV", R.drawable.ic_folder_2_fill),
                SideMenuView.MenuItem(R.id.nav_playlist, "歌单", R.drawable.ic_heart_fill)
            ),
            "系统" to listOf(
                SideMenuView.MenuItem(R.id.nav_settings, "设置", R.drawable.ic_settings),
                SideMenuView.MenuItem(R.id.nav_about, "关于", UiR.drawable.ic_info),
                SideMenuView.MenuItem(R.id.nav_qq_group, "QQ群", UiR.drawable.ic_info),
                SideMenuView.MenuItem(R.id.nav_log_export, "日志导出", R.drawable.ic_log),
                SideMenuView.MenuItem(R.id.nav_log_viewer, "日志分析", R.drawable.ic_log)
            )
        )
        binding.sideMenu.setMenuItems(menuGroups, R.id.nav_songs)

        binding.sideMenu.onMenuItemClick = { itemId ->
            if (itemId == R.id.nav_qq_group) {
                dialogHelper.showQqGroupInfo()
            } else if (itemId == R.id.nav_log_export) {
                exportLogWithSaf()
            } else if (itemId == R.id.nav_songs || itemId == R.id.nav_albums) {
                // 歌曲列表和专辑列表已在 UnifiedMainContainer 中管理，切换到容器模式
                if (unifiedMainContainer != null) {
                    val scene = if (itemId == R.id.nav_songs) com.rawsmusic.core.ui.scene.NavScene.SONGS
                    else com.rawsmusic.core.ui.scene.NavScene.ALBUMS
                    switchToContainerMode(scene)
                }
            } else if (itemId == R.id.nav_settings) {
                startActivity(android.content.Intent(this, com.rawsmusic.ui.settings.SettingsActivity::class.java))
                @Suppress("DEPRECATION")
                overridePendingTransition(android.R.anim.fade_in, 0)
            } else {
                // 侧边菜单导航到 Fragment 页面（与 HOME 卡片点击一致）
                val fragmentId = when (itemId) {
                    R.id.nav_artists -> R.id.nav_artists
                    R.id.nav_playlist -> R.id.nav_playlist
                    R.id.nav_webdav -> R.id.nav_webdav
                    else -> itemId
                }
                if (unifiedMainContainer != null) {
                    switchToFragmentMode()
                }
                // 清除起始目的地（nav_songs），避免返回时跳到从未访问的歌曲列表
                val sideMenuNavOptions = NavOptions.Builder()
                    .setPopUpTo(navController.graph.startDestinationId, true)
                    .build()
                try {
                    navController.navigate(fragmentId, null, sideMenuNavOptions)
                } catch (_: Exception) {}
            }
            closeSideMenu()
        }

        binding.sideMenu.onMenuToggle = { isOpen ->
            if (isOpen) {
                openSideMenu()
            } else {
                closeSideMenu()
            }
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val menuId = when (destination.id) {
                R.id.nav_songs -> R.id.nav_songs
                R.id.nav_albums -> R.id.nav_albums
                R.id.nav_artists -> R.id.nav_artists
                R.id.nav_song_stats -> R.id.nav_song_stats
                R.id.nav_webdav -> R.id.nav_webdav
                R.id.nav_playlist -> R.id.nav_playlist
                R.id.nav_settings -> R.id.nav_settings
                R.id.nav_about -> R.id.nav_about
                R.id.nav_log_viewer -> R.id.nav_log_viewer
                else -> null
            }
            if (menuId != null) binding.sideMenu.setSelectedMenuId(menuId)
            updateDrawerLockMode()
            if (binding.navHostFragment.visibility == View.VISIBLE) {
                // 从专辑详情页返回到歌曲页面时，切换到专辑容器页面
                if (destination.id == R.id.nav_songs &&
                    com.rawsmusic.module.data.prefs.AppPreferences.UI.lastFragmentDest == R.id.nav_album_detail) {
                    switchToContainerMode(com.rawsmusic.core.ui.scene.NavScene.ALBUMS)
                }
                com.rawsmusic.module.data.prefs.AppPreferences.UI.lastFragmentDest = destination.id
            }
        }
    }

    private fun updateDrawerLockMode() {
        if (!::unifiedContainer.isInitialized) return
        val isHomeLevel = unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.MAIN
        val isDeepPage = if (unifiedMainContainer != null) {
            unifiedMainContainer?.isAtHome() != true
        } else {
            navController.previousBackStackEntry != null
        }
        val inFragmentMode = binding.navHostFragment.visibility == View.VISIBLE
        unifiedContainer.disableDeepPageSwipe = inFragmentMode
        unifiedContainer.isDeepHomePage = isHomeLevel && isDeepPage && !inFragmentMode
        android.util.Log.d("GestureDebug", "updateDrawerLockMode: isHomeLevel=$isHomeLevel, isDeepPage=$isDeepPage, inFragmentMode=$inFragmentMode, isDeepHomePage=${unifiedContainer.isDeepHomePage}, canNavigateBack=${unifiedMainContainer?.canNavigateBack()}")
    }

    private var unifiedMainContainer: com.rawsmusic.core.ui.scene.UnifiedMainContainer? = null

    private fun setupUnifiedMainContainer() {
        val container = findViewById<com.rawsmusic.core.ui.scene.UnifiedMainContainer>(R.id.unifiedMainContainer)
        if (container == null) {
            AppLogger.w("MainActivity", "setupUnifiedMainContainer: unifiedMainContainer view missing in current layout")
            return
        }
        unifiedMainContainer = container
        unifiedMainContainer?.initialize()

        binding.navHostFragment.visibility = View.GONE
        unifiedMainContainer?.visibility = View.VISIBLE

        observeMainContainerFlows()

        unifiedMainContainer?.onNavigateToPlayer = {
            if (playerController?.currentSong?.value != null) {
                openPlayPageWithSharedElement()
            } else {
                moveTaskToBack(true)
            }
        }

        // HOME 卡片点击 → 导航到已有 Fragment 或容器内页面
        unifiedMainContainer?.onNavigateToFragment = { scene ->
            if (scene == com.rawsmusic.core.ui.scene.NavScene.SONGS ||
                scene == com.rawsmusic.core.ui.scene.NavScene.ALBUMS ||
                scene == com.rawsmusic.core.ui.scene.NavScene.ARTISTS) {
                // 歌曲/专辑/艺术家列表已在容器内，直接导航
                unifiedMainContainer?.navigateTo(scene)
            } else {
                val fragmentId = when (scene) {
                    com.rawsmusic.core.ui.scene.NavScene.PLAYLISTS -> R.id.nav_playlist
                    com.rawsmusic.core.ui.scene.NavScene.WEBDAV -> R.id.nav_webdav
                    else -> null
                }
                if (fragmentId != null) {
                    switchToFragmentMode()
                    // 清除起始目的地（nav_songs），避免返回时跳到从未访问的歌曲列表
                    val navOptions = NavOptions.Builder()
                        .setPopUpTo(navController.graph.startDestinationId, true)
                        .build()
                    try { navController.navigate(fragmentId, null, navOptions) } catch (_: Exception) {}
                }
            }
        }

        unifiedMainContainer?.onSongClick = { song, pos ->
            val currentPlayingId = playerController?.currentSong?.value?.id
            if (song.id == currentPlayingId) {
                // 点击当前播放的歌曲 → 打开播放界面（共享元素动画）
                openPlayPageFromSongClick()
            } else {
                playSongFromList(song, pos, unifiedMainContainer?.getCurrentScene() ?: com.rawsmusic.core.ui.scene.NavScene.SONGS)
            }
        }
        unifiedMainContainer?.onAlbumClick = { album ->
            unifiedMainContainer?.navigateTo(com.rawsmusic.core.ui.scene.NavScene.ALBUMS)
        }
        unifiedMainContainer?.onAlbumItemClick = { album ->
            val bundle = android.os.Bundle().apply {
                putString(com.rawsmusic.ui.albums.AlbumDetailFragment.ARG_ALBUM_NAME, album.name)
                putString(com.rawsmusic.ui.albums.AlbumDetailFragment.ARG_ALBUM_ARTIST, album.artist)
                putString(com.rawsmusic.ui.albums.AlbumDetailFragment.ARG_COVER_PATH, album.coverPath)
            }
            switchToFragmentMode()
            try { navController.navigate(R.id.nav_album_detail, bundle) } catch (_: Exception) {}
        }
        unifiedMainContainer?.onArtistClick = { artist ->
            // 跳到容器内的艺术家详情页 (Compose 渲染)
            unifiedMainContainer?.navigateToArtistDetail(artist.name)
        }
        // 注入播放队列回调 (从外部获取 PlayerHolder, 避免 core/ui 依赖 app 模块)
        unifiedMainContainer?.onPlayQueue = playQueueLambda@{ songs, index ->
            val controller = com.rawsmusic.ui.songs.PlayerHolder.controller
            if (controller == null) return@playQueueLambda
            try {
                controller.playQueue(songs, index)
            } catch (_: Exception) {
                try { songs.getOrNull(index)?.let { controller.play(it) } } catch (_: Exception) {}
            }
        }
        unifiedMainContainer?.onPlaylistClick = { playlist ->
            unifiedMainContainer?.navigateTo(
                com.rawsmusic.core.ui.scene.NavScene.PLAYLIST_DETAIL,
                playlist.id.toString()
            )
        }
        unifiedMainContainer?.onFolderClick = { folder ->
            unifiedMainContainer?.navigateTo(
                com.rawsmusic.core.ui.scene.NavScene.FOLDER_HIERARCHY,
                folder.path
            )
        }
        unifiedMainContainer?.onFolderHierarchyClick = { folder ->
            unifiedMainContainer?.navigateTo(
                com.rawsmusic.core.ui.scene.NavScene.FOLDER_HIERARCHY,
                folder.path
            )
        }
        unifiedMainContainer?.onQueueSongClick = { song, pos ->
            lyricsNeedSeekTo = true
            playerController?.seekTo(pos.toLong())
        }
        unifiedMainContainer?.onRecentlyAddedClick = { song, pos ->
            playSongFromList(song, pos, com.rawsmusic.core.ui.scene.NavScene.RECENTLY_ADDED)
        }
        unifiedMainContainer?.onPlayAll = { songs ->
            playerController?.setPlayQueue(songs, 0)
        }
        unifiedMainContainer?.onShuffleAll = { songs ->
            playerController?.setPlayQueue(songs.shuffled(), 0)
        }
        unifiedMainContainer?.onSearchClick = {
            try { navController.navigate(R.id.nav_search) } catch (_: Exception) {}
        }
        unifiedMainContainer?.onSongsRefresh = {
            MusicRepository.refreshAll()
        }
        unifiedMainContainer?.onSongsSortChanged = {
            MusicRepository.refreshAll()
        }
        unifiedMainContainer?.onOpenFolderPicker = {
            val dialog = com.rawsmusic.ui.folderfilter.MusicFoldersDialog(
                this,
                onFolderPickerLauncher = {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
                        startActivityForResult(intent, 1001)
                    } catch (_: Exception) {}
                },
                onScanStarted = {
                    MusicRepository.refreshAll()
                }
            )
            dialog.show()
        }

        unifiedMainContainer?.submitPlaylists(com.rawsmusic.module.data.db.PlaylistDao.getAll())
    }

    private fun observeMainContainerFlows() {
        lifecycleScope.launch {
            unifiedMainContainer?.currentSceneFlow?.collect { scene ->
                updateDrawerLockMode()
            }
        }
        lifecycleScope.launch {
            MusicRepository.songs.collect { songs ->
                AppLogger.d("Startup", "observeFlows: songs emitted, count=${songs.size}")
                unifiedMainContainer?.submitSongs(songs)
                val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                val recent = songs.filter { it.dateAdded * 1000 > sevenDaysAgo || it.dateModified * 1000 > sevenDaysAgo }
                unifiedMainContainer?.submitRecentlyAdded(recent)
                unifiedMainContainer?.updateHomeCounts(
                    songs = songs.size,
                    albums = MusicRepository.albums.value.size,
                    artists = MusicRepository.artists.value.size,
                    folders = MusicRepository.folders.value.size,
                    playlists = com.rawsmusic.module.data.db.PlaylistDao.getAll().size
                )
            }
        }
        lifecycleScope.launch {
            MusicRepository.albums.collect { albums ->
                unifiedMainContainer?.submitAlbums(albums)
            }
        }
        lifecycleScope.launch {
            MusicRepository.artists.collect { artists ->
                unifiedMainContainer?.submitArtists(artists)
            }
        }
        lifecycleScope.launch {
            MusicRepository.folders.collect { folders ->
                unifiedMainContainer?.submitFolders(folders)
            }
        }
        lifecycleScope.launch {
            playerController?.queue?.collect { playQueue ->
                unifiedMainContainer?.submitQueueSongs(playQueue.songs)
            }
        }
    }

    private fun playSongFromList(song: AudioFile, position: Int, scene: com.rawsmusic.core.ui.scene.NavScene) {
        val songs = when (scene) {
            com.rawsmusic.core.ui.scene.NavScene.SONGS -> MusicRepository.songs.value
            com.rawsmusic.core.ui.scene.NavScene.RECENTLY_ADDED -> {
                val sevenDaysAgo = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
                MusicRepository.songs.value.filter { it.dateAdded * 1000 > sevenDaysAgo || it.dateModified * 1000 > sevenDaysAgo }
            }
            else -> listOf(song)
        }
        val idx = songs.indexOf(song).coerceAtLeast(0)
        playerController?.setPlayQueue(songs, idx)
    }

    private fun setupUnifiedContainer() {
        unifiedContainer = binding.unifiedContainer

        // 修复横屏 layout 缺少 immersiveBackground 时 NPE: 跳过 initImmersiveViews 但不 return
        // 后续的 lambda 注册/事件回调/OnResume 重应用都依赖此函数完整执行
        val immersiveBg = binding.immersiveBackground
        if (immersiveBg != null) {
            unifiedContainer.initImmersiveViews(
                immersiveBg = immersiveBg,
                coverImg = binding.ivPlayCover,
                playScrim = binding.playBgScrim,
                miniCover = binding.mainPersistentCover,
                isImmersiveEnabled = com.rawsmusic.module.data.prefs.AppPreferences.UI.isImmersiveEnabled,
                isMiniCoverEnabled = com.rawsmusic.module.data.prefs.AppPreferences.UI.isMiniCoverEnabled
            )
        } else {
            AppLogger.w("MainActivity", "setupUnifiedContainer: immersiveBackground view missing in current layout, skip initImmersiveViews only")
        }

        binding.immersiveBackground?.onImmersiveDrawingChanged = { drawing ->
            // 非沉浸模式下保持 playBgView 隐藏，避免与 ivPlayCover 重叠
            val isImmersive = unifiedContainer.isImmersiveEnabled
            binding.playBgView.alpha = if (drawing || !isImmersive) 0f else 1f
        }

        unifiedContainer.bindViews(
            navHostFragment = binding.navHostFragment,
            playBgView = binding.playBgView,
            miniPlayerBar = binding.miniPlayerBar,
            lyricContentContainer = binding.lyricContentContainer,
            lyricBgView = binding.lyricBgView,
            lyricMainLayer = binding.lyricMainLayer
        )

        setupMiniPlayerBar()

        sceneRegistry = com.rawsmusic.helper.SceneRegistry(unifiedContainer, resources)
        sceneParamsHelper = com.rawsmusic.helper.SceneParamsHelper(
            unifiedContainer, binding, resources, navController,
            { getPlayCoverTargetRect() },
            { getListCoverPosition() },
            { getAlbumDetailCoverRect() },
            { findSongsPowerListView() }
        )
        coverLayoutHelper = com.rawsmusic.helper.CoverLayoutHelper(
            unifiedContainer, binding, resources, coverAnimState,
            { loadedCoverImageWidth to loadedCoverImageHeight },
            { updateLyricSongInfo() }
        )
        setupSceneParams()

        // 设置主界面容器引用，用于手势拖拽返回动画
        unifiedContainer.mainContainer = unifiedMainContainer

        unifiedContainer.applyImmersiveSceneParams()

        unifiedContainer.post {
            setupCoverLayoutParams()
            if (prePlayerWasInFragmentMode && com.rawsmusic.module.data.prefs.AppPreferences.UI.lastScene == "MAIN") {
                val savedDest = prePlayerFragmentDest
                if (savedDest != null && savedDest != -1 && savedDest != R.id.nav_songs) {
                    switchToFragmentMode()
                    try { navController.navigate(savedDest) } catch (_: Exception) {}
                } else {
                    // 歌曲列表已迁移到容器模式，无需切换到 Fragment 模式
                    switchToContainerMode(com.rawsmusic.core.ui.scene.NavScene.SONGS)
                }
                prePlayerWasInFragmentMode = false
            }
        }

        binding.playBgView.setDimAmount(0f)
        binding.playBgView.setAppleMusicStyle(true)

        // 沉浸模式...
        binding.lyricBgView.setDimAmount(0f)
        binding.lyricBgView.setAppleMusicStyle(true)

        // 非沉浸模式
        binding.backgroundView.setDimAmount(0f)
        binding.backgroundView.setAppleMusicStyle(true)

        unifiedContainer.onTransitionProgress = { targetScene, ratio ->
            // 导航栏始终保持可见，不做任何 alpha/visibility 变化，避免"先淡出再显示"的闪烁
            // 场景切换的状态由 onSceneChanged 统一管理
        }

        unifiedContainer.onSceneChanged = { newScene, oldScene ->
            val playState = playerController?.playState?.value
            val ffmpegState = playerController?.ffmpegPlayerRef?.state
            AppLogger.w("SceneTransition", "=== onSceneChanged: $oldScene -> $newScene, isRealTransition=${oldScene != newScene}, playState=$playState, ffmpegState=$ffmpegState, prePlayerWasInFragmentMode=$prePlayerWasInFragmentMode, prePlayerFragmentDest=$prePlayerFragmentDest ===")
            val isRealTransition = oldScene != newScene
            com.rawsmusic.module.data.prefs.AppPreferences.UI.lastScene = newScene.name
            if (newScene == UnifiedPlayerContainer.Scene.MAIN) {
                val inFragmentMode = binding.navHostFragment.visibility == View.VISIBLE
                com.rawsmusic.module.data.prefs.AppPreferences.UI.wasInFragmentMode = inFragmentMode
                if (inFragmentMode) {
                    navController.currentDestination?.id?.let { destId ->
                        com.rawsmusic.module.data.prefs.AppPreferences.UI.lastFragmentDest = destId
                    }
                }
            }
            // 只在状态栏设置实际会改变时才更新，避免 PLAYER↔LYRIC 等切换时触发 insets 重算导致导航栏闪烁
            val needsUpdate = (oldScene == UnifiedPlayerContainer.Scene.MAIN) != (newScene == UnifiedPlayerContainer.Scene.MAIN)
            if (needsUpdate) {
                updateStatusBarForLevel(newScene)
            }
            when (newScene) {
                UnifiedPlayerContainer.Scene.MAIN -> {
                    val decorView = window.decorView as? android.view.ViewGroup
                    decorView?.let { dv ->
                        for (i in dv.childCount - 1 downTo 0) {
                            val child = dv.getChildAt(i)
                            if (child.tag == "play_mode_overlay") {
                                try { dv.removeView(child) } catch (_: Exception) {}
                            }
                        }
                    }
                    // 先切换容器内场景（隐藏其他页面），再恢复默认参数（设容器为 VISIBLE）
                    // 顺序不能反：如果先 restoreDefaultSceneParams 设容器 VISIBLE，所有页面会闪现
                    if (isRealTransition && (oldScene == UnifiedPlayerContainer.Scene.PLAYER || oldScene == UnifiedPlayerContainer.Scene.LYRIC)) {
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
                            if (navController.currentDestination?.id == savedDest) {
                                AppLogger.w("SceneTransition", "=== dest $savedDest still on top, skip pop/navigate ===")
                            } else if (savedDest != R.id.nav_songs) {
                                // 页面已不在栈顶，先回到歌曲列表，再重新导航
                                try { navController.popBackStack(R.id.nav_songs, false) } catch (_: Exception) {}
                                try { navController.navigate(savedDest) } catch (_: Exception) {}
                            } else {
                                try { navController.popBackStack(R.id.nav_songs, false) } catch (_: Exception) {}
                            }
                        }
                        // 在容器模式下不需要导航，UnifiedMainContainer 会保持当前页面
                        prePlayerFragmentDest = null
                        prePlayerWasInFragmentMode = false
                    }
                    getListCoverView()?.visibility = View.VISIBLE
                    binding.ivPlayCover.alpha = 1f
                    // 从专辑详情页返回时，恢复专辑封面并隐藏播放页封面
                    if (navController.currentDestination?.id == R.id.nav_album_detail) {
                        getAlbumDetailCoverView()?.visibility = View.VISIBLE
                        binding.ivPlayCover.apply {
                            alpha = 0f
                            visibility = View.GONE
                            translationX = 0f
                            translationY = 0f
                            scaleX = 1f
                            scaleY = 1f
                        }
                    }
                    savedAlbumDetailCoverRect = null
                    savedListCoverRect = null
                    updateDrawerLockMode()
                    // 注意：以下 view 的 visibility/alpha 由 sceneRegistry 动画引擎管理，
                    // onSceneChanged 中不再重复设置，避免与动画最终状态冲突
                    binding.playMetadataCard.collapse()
                    binding.lyricMetadataCard.collapse()
                    coverAnimState.reset()
                    // 恢复流动光效果
                    binding.playBgView.setDynamic(true)
                    binding.playBgView.setAllowDynamicRunning(true)
                }
                UnifiedPlayerContainer.Scene.PLAYER -> {
                    binding.navHostFragment.alpha = 0f
                    unifiedContainer.syncRotationState(unifiedContainer.isCurrentlyPlaying)
                    binding.ivPlayCover.pivotX = binding.ivPlayCover.width / 2f
                    binding.ivPlayCover.pivotY = binding.ivPlayCover.height / 2f
                    binding.playTitleGroup.pivotX = binding.playTitleGroup.width / 2f
                    binding.playTitleGroup.pivotY = binding.playTitleGroup.height / 2f
                    val density = resources.displayMetrics.density
                    val isImmersive = unifiedContainer.isImmersiveEnabled
                    val isFlowingLightOff = com.rawsmusic.module.data.prefs.AppPreferences.UI.isFlowingLightDisabled
                    // 封面：沉浸模式隐藏
                    val coverAlpha = if (isImmersive) 0f else 1f
                    val coverVisibility = if (isImmersive) View.INVISIBLE else View.VISIBLE
                    unifiedContainer.registerSceneParams(
                        R.id.ivPlayCover,
                        UnifiedPlayerContainer.Scene.PLAYER,
                        UnifiedPlayerContainer.SceneParams(
                            scene = UnifiedPlayerContainer.Scene.PLAYER,
                            alpha = coverAlpha,
                            visibility = coverVisibility,
                            translationX = 0f,
                            translationY = 0f,
                            scaleX = 1f,
                            scaleY = 1f,
                            cornerRadius = (findSongsPowerListView()?.currentCoverCornerRadiusDp ?: 18f) * resources.displayMetrics.density
                        )
                    )
                    if (isFlowingLightOff) {
                        binding.playBgView.setDynamic(false)
                        binding.playBgView.setAllowDynamicRunning(false)
                        binding.playBgView.pauseAnimations()
                    } else {
                        binding.playBgView.setDynamic(true)
                        binding.playBgView.setAllowDynamicRunning(true)
                        binding.playBgView.resumeAnimations()
                    }
                    // 普通模式
                    binding.audioVisualizer?.visibility = View.GONE
                    binding.playBottomPanel?.apply {
                        visibility = View.VISIBLE
                        alpha = 1f
                    }
                    binding.playTitleGroup.apply {
                        visibility = View.VISIBLE
                        alpha = 1f
                        scaleX = 1f
                        scaleY = 1f
                        translationX = 0f
                        translationY = 0f
                    }
                    binding.btnPlayMode.visibility = View.VISIBLE
                    updateHiresBadge()
                    coverAnimState.reset()
                    val isLandscapePlayer = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val screenHeight = resources.displayMetrics.heightPixels
                    if (isLandscapePlayer) {
                        // 使用屏幕高度的 8% 和 12% 作为歌词容器 padding
                        val lyricTopRatio = resources.getFloat(R.dimen.lyric_top_padding_ratio_landscape)
                        val lyricBottomRatio = resources.getFloat(R.dimen.lyric_bottom_padding_ratio_landscape)
                        val lyricTopPad = (screenHeight * lyricTopRatio).toInt()
                        val lyricBottomPad = (screenHeight * lyricBottomRatio).toInt()
                        binding.lyricContentContainer.setPadding(0, lyricTopPad, 0, lyricBottomPad)
                    } else {
                        // 使用屏幕高度的 20% 和 15% 作为歌词容器 padding
                        val lyricTopRatio = resources.getFloat(R.dimen.lyric_top_padding_ratio_portrait)
                        val lyricBottomRatio = resources.getFloat(R.dimen.lyric_bottom_padding_ratio_portrait)
                        val lyricTopPad = (screenHeight * lyricTopRatio).toInt()
                        val lyricBottomPad = (screenHeight * lyricBottomRatio).toInt()
                        binding.lyricContentContainer.setPadding(0, lyricTopPad, 0, lyricBottomPad)
                    }
                    setupCoverLayoutParams()
                    binding.queuePageContainer?.visibility = View.GONE
                    binding.albumDetailContainer?.visibility = View.GONE
                    binding.mainBgScrim?.visibility = View.GONE
                    binding.mainBgScrim?.alpha = 0f
                }
                UnifiedPlayerContainer.Scene.LYRIC -> {
                    binding.navHostFragment.alpha = 0f
                    binding.audioVisualizer?.visibility = View.GONE
                    val isLandscapeScene = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    if (isLandscapeScene) {
                        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            display?.rotation ?: Surface.ROTATION_0
                        } else {
                            @Suppress("DEPRECATION")
                            windowManager.defaultDisplay.rotation
                        }
                        applyLandscapeLyricConstraints(rotation == Surface.ROTATION_270)
                        updateLyricSongInfo()
                    }
                    val density = resources.displayMetrics.density
                    val coverBottom = getCoverBottomInContainer()
                    // 使用屏幕高度的百分比作为歌词容器 margin
                    val screenHeight = resources.displayMetrics.heightPixels
                    val lyricTopRatio = resources.getFloat(R.dimen.lyric_top_padding_ratio_lyric_scene)
                    val lyricBottomRatio = resources.getFloat(R.dimen.lyric_bottom_padding_ratio_lyric_scene)
                    val lyricTopPad = (screenHeight * lyricTopRatio).toInt()
                    val lyricBottomPad = (screenHeight * lyricBottomRatio).toInt()

                    val lyricLp = binding.lyricContentContainer.layoutParams as? android.widget.FrameLayout.LayoutParams
                    if (lyricLp != null) {
                        lyricLp.topMargin = lyricTopPad
                        lyricLp.bottomMargin = lyricBottomPad
                        lyricLp.gravity = android.view.Gravity.TOP
                        binding.lyricContentContainer.layoutParams = lyricLp
                    }
                    binding.lyricContentContainer.setPadding(0, 0, 0, 0)

                    binding.lyricControls.visibility = View.VISIBLE
                    binding.btnMoreLyric.visibility = View.GONE
                    binding.lyricMainLayer.visibility = View.GONE
                    binding.playTitleGroup.bringToFront()
                    binding.playTitleGroup.pivotX = binding.playTitleGroup.width / 2f
                    binding.playTitleGroup.pivotY = binding.playTitleGroup.height / 2f
                    updateLyricAnchor()
                    val topPadForLyrics = maxOf(0f, coverBottom.toFloat() - lyricTopPad + 60f * density)
                    lyricPlayerView.setTopContentPadding(topPadForLyrics)

                    val pos = playerController?.position?.value ?: 0L
                    playerController?.currentSong?.value?.let { song ->
                        val lyricData = currentLyricData
                        if (!lyricData.isEmpty) {
                            val lyriconSong = lyricData.toLyriconSong(
                                name = song.title,
                                artist = song.artist
                            )
                            lyricPlayerView.song = lyriconSong
                            lyricPlayerView.setPosition(pos)
                            lyricPlayerView.onLineClickListener = object : RawsLyricView.OnLineClickListener {
                                override fun onLineClick(beginMs: Long) {
                                    lyricsNeedSeekTo = true
                                    playerController?.seekTo(beginMs)
                                }
                            }
                            val displayTrans = com.rawsmusic.module.data.prefs.AppPreferences.Lyricon.displayTranslation
                            lyricPlayerView.updateDisplayTranslation(
                                displayTranslation = displayTrans,
                                displayRoma = displayTrans
                            )
                        }
                    }

                    binding.lyricBgView.resumeAnimations()
                    binding.ivHiresSmall.visibility = View.GONE
                    binding.ivHiresSmall.alpha = 0f
                    binding.mainBgScrim?.visibility = View.GONE
                    binding.mainBgScrim?.alpha = 0f
                }
                UnifiedPlayerContainer.Scene.QUEUE -> {
                    binding.playBgScrim?.visibility = View.GONE
                    binding.playBgScrim?.alpha = 0f
                    binding.ivPlayCoverMirror?.visibility = View.GONE
                    binding.queuePageContainer?.visibility = View.VISIBLE
                    binding.queuePageContainer?.alpha = 1f
                    binding.queuePageContainer?.translationY = 0f
                    refreshQueueList()
                    binding.mainBgScrim?.visibility = View.GONE
                    binding.mainBgScrim?.alpha = 0f
                }
                UnifiedPlayerContainer.Scene.ALBUM_DETAIL -> {
                    binding.playBgScrim?.visibility = View.GONE
                    binding.playBgScrim?.alpha = 0f
                    binding.ivPlayCoverMirror?.visibility = View.GONE
                    binding.albumDetailContainer?.visibility = View.VISIBLE
                    binding.albumDetailContainer?.alpha = 1f
                    binding.albumDetailContainer?.translationX = 0f
                    binding.mainBgScrim?.visibility = View.GONE
                    binding.mainBgScrim?.alpha = 0f
                }
                UnifiedPlayerContainer.Scene.EFFECTS -> {
                    syncEffectsPanelState()
                }
            }
            // 场景变化后更新预测性返回回调注册状态
            updatePredictiveBackRegistration()
        }

        // 根据当前PLAY/LYRIC 场景更新参数
        unifiedContainer.onLeftEdgeSwipe = {
            if (unifiedContainer.currentScene != UnifiedPlayerContainer.Scene.MAIN) {
                navController.navigateUp()
            }
        }

        // 返回上一级，使用 navigateUp() 返回主界面
        unifiedContainer.onSwipeBack = {
            if (unifiedMainContainer != null && unifiedMainContainer?.isAtHome() != true) {
                unifiedMainContainer?.navigateHome()
            } else {
                navController.navigateUp()
            }
        }

        // 沉浸模式左滑：启动独立歌词界面
        unifiedContainer.onImmersiveSwipeLeft = {
            launchImmersiveLyric()
        }

        unifiedContainer.onPlayerSwipeToMain = {
            // 保存当前 Fragment 目标，用于返回时恢复（如果尚未保存）
            if (prePlayerFragmentDest == null) {
                prePlayerFragmentDest = navController.currentDestination?.id
            }
            if (!prePlayerWasInFragmentMode) {
                prePlayerWasInFragmentMode = binding.navHostFragment.visibility == View.VISIBLE
            }
            if (prePlayerContainerScene == null && unifiedMainContainer != null) {
                prePlayerContainerScene = unifiedMainContainer?.getCurrentScene()
            }
            unifiedContainer.closePlayPageWithCoverAlign(true)
        }

        unifiedContainer.onPreparePlayerToMain = { onReady ->
            binding.ivHiresSmall.visibility = View.GONE
            binding.ivHiresSmall.alpha = 0f
            binding.root.post {
                // 判断是否返回专辑详情页
                val returningToAlbumDetail = prePlayerWasInFragmentMode &&
                        prePlayerFragmentDest == R.id.nav_album_detail

                fun registerMainSceneContainers() {
                    unifiedContainer.registerSceneParams(
                        R.id.unifiedMainContainer,
                        UnifiedPlayerContainer.Scene.MAIN,
                        UnifiedPlayerContainer.SceneParams(
                            scene = UnifiedPlayerContainer.Scene.MAIN,
                            alpha = if (prePlayerWasInFragmentMode) 0f else 1f,
                            translationY = 0f,
                            scaleX = 1f,
                            scaleY = 1f,
                            visibility = if (prePlayerWasInFragmentMode) View.GONE else View.VISIBLE
                        )
                    )
                    // nav_host_fragment 在 MAIN 场景的可见性取决于进入播放器前的状态
                    unifiedContainer.registerSceneParams(
                        R.id.nav_host_fragment,
                        UnifiedPlayerContainer.Scene.MAIN,
                        UnifiedPlayerContainer.SceneParams(
                            scene = UnifiedPlayerContainer.Scene.MAIN,
                            alpha = if (prePlayerWasInFragmentMode) 1f else 0f,
                            translationY = 0f,
                            scaleX = 1f,
                            scaleY = 1f,
                            visibility = if (prePlayerWasInFragmentMode) View.VISIBLE else View.GONE
                        )
                    )
                }

                // 先获取专辑封面位置，因为 registerCoverCollapseParams 会把 nav_host_fragment 设为 GONE
                // 导致后续 getLocationOnScreen 返回错误坐标
                val albumCoverQuad: Quad<Float, Float, Float, Float>? = if (returningToAlbumDetail) {
                    // 专辑详情页的封面需要先显示出来（动画目标）
                    binding.navHostFragment.visibility = View.VISIBLE
                    binding.navHostFragment.alpha = 0f
                    getAlbumDetailCoverView()?.visibility = View.VISIBLE
                    getAlbumDetailCoverRect()?.let { rect ->
                        Quad(rect.left, rect.top, rect.width(), rect.height())
                    } ?: savedAlbumDetailCoverRect?.let { rect ->
                        // 回退：使用进入播放器前保存的封面坐标
                        Quad(rect.left, rect.top, rect.width(), rect.height())
                    }
                } else null

                fun finishPrepare(targetCoverRect: Quad<Float, Float, Float, Float>?) {
                    registerCoverCollapseParams()
                    registerMainSceneContainers()
                    if (targetCoverRect == null) {
                        // Step 10: 共享 View 不可见 → 淡出（对标 Poweramp: 无目标 item 时不执行共享动画）
                        unifiedContainer.registerSceneParams(
                            R.id.ivPlayCover,
                            UnifiedPlayerContainer.Scene.MAIN,
                            UnifiedPlayerContainer.SceneParams(
                                scene = UnifiedPlayerContainer.Scene.MAIN,
                                alpha = 0f,
                                visibility = View.GONE
                            )
                        )
                    } else {
                        // Step 8: 始终从当前布局计算目标位置（对标 Poweramp LayoutEngine 实时计算，不使用缓存）
                        val targetRect = getPlayCoverTargetRect()
                        val targetW = if (targetRect.width() > 0f) targetRect.width() else binding.ivPlayCover.width.toFloat()
                        val targetH = if (targetRect.height() > 0f) targetRect.height() else binding.ivPlayCover.height.toFloat()
                        val sourceCornerDp = if (returningToAlbumDetail) {
                            22f
                        } else {
                            findSongsPowerListView()?.currentCoverCornerRadiusDp ?: 18f
                        }
                        sceneParamsHelper.registerCoverCollapseParamsWithSourcePos(
                            targetCoverRect,
                            targetRect,
                            targetW,
                            targetH,
                            binding.ivPlayCover,
                            resources.displayMetrics.density,
                            sourceCornerDp
                        )
                    }
                    // 动画开始前隐藏容器内非目标页面，避免返回时闪现所有内容
                    if (unifiedMainContainer != null && !prePlayerWasInFragmentMode) {
                        val targetScene = prePlayerContainerScene ?: com.rawsmusic.core.ui.scene.NavScene.HOME
                        unifiedMainContainer?.hideAllPagesExcept(targetScene)
                    }
                    onReady()
                }

                if (returningToAlbumDetail) {
                    finishPrepare(albumCoverQuad)
                } else {
                    // Step 7: 先让目标列表参与布局但保持透明，等一帧取真实 aa_image 坐标
                    // 对标 Poweramp: sE.p(true, false) 准备布局 → 重新获取 oE
                    binding.navHostFragment.visibility = View.GONE
                    unifiedMainContainer?.apply {
                        visibility = View.VISIBLE
                        alpha = 0f
                        translationX = 0f
                        translationY = 0f
                        scaleX = 1f
                        scaleY = 1f
                        requestLayout()
                    }
                    binding.root.post {
                        // Step 2-4: 解析目标位置（对标 Poweramp: 通过 wV.A 获取 View → getLocationOnScreen）
                        getCurrentSongCoverRectWhenReady { rect ->
                            val stableRect = savedListCoverRect ?: rect
                            val targetCoverQuad = stableRect?.let {
                                Quad(it.left, it.top, it.width(), it.height())
                            }
                            // Step 9-10: 隐藏源 View（对标 Poweramp: field P = source view → setVisibility(GONE)）
                            // 不创建 overlay，由 scene transition 引擎统一驱动动画（对标 Poweramp: z1 单一动画器）
                            getListCoverView()?.visibility = View.INVISIBLE
                            // Step 5/8: 注册 SceneParams 并触发 transition
                            finishPrepare(targetCoverQuad)
                        }
                    }
                }
            }
        }

        unifiedContainer.onPreparePlayerToLyric = {
            binding.ivHiresSmall.visibility = View.GONE
            binding.ivHiresSmall.alpha = 0f
            binding.ivPlayCover.pivotX = 0f
            binding.ivPlayCover.pivotY = 0f
            if (!binding.lyricBgView.syncFrom(binding.playBgView)) {
                binding.lyricBgView.syncFrom(binding.backgroundView)
            }
            binding.lyricBgView.resumeAnimations()
            registerCoverLyricParams()
        }

        unifiedContainer.onPrepareMainToPlayer = {
            loadedCoverImageWidth = 0
            loadedCoverImageHeight = 0
            val isImmersive = unifiedContainer.isImmersiveEnabled
            // 非沉浸模式下检查封面是否可用，无封面则隐藏避免透明矩形
            val currentSong = playerController?.currentSong?.value
            val coverUri = currentSong?.let { coverUriResolver.resolveCoverUri(it) } ?: ""
            val playCoverUri = coverUri.ifBlank { currentSong?.albumArtPath ?: "" }
            val hasCover = playCoverUri.isNotBlank()
            playCoverView.apply {
                visibility = View.INVISIBLE
                alpha = if (isImmersive) 0f else 1f
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
                pivotX = width / 2f
                pivotY = height / 2f
                cornerRadius = (findSongsPowerListView()?.currentCoverCornerRadiusDp ?: 18f) * resources.displayMetrics.density
            }
            // 从主界面进入播放界面时，确保封面图片已加载
            if (hasCover && !isImmersive) {
                BitmapProvider.load(
                    key = playCoverUri,
                    imageView = null,
                    targetWidth = 1080,
                    targetHeight = 1080,
                    callback = { bitmap ->
                        AppLogger.d("CoverAdjust", "onPrepareMainToPlayer target onSuccess")
                        if (bitmap != null && !bitmap.isRecycled) {
                            AppLogger.d("CoverAdjust", "bitmap: ${bitmap.width}x${bitmap.height}")
                            loadedCoverImageWidth = bitmap.width
                            loadedCoverImageHeight = bitmap.height
                        }
                        // 先同步更新 LayoutParams，确保 setCoverBitmap 触发 requestLayout 时参数正确
                        setupCoverLayoutParams()
                        if (bitmap != null) {
                            playCoverView.setCoverBitmap(bitmap)
                        }
                        playCoverView.visibility = View.VISIBLE
                        unifiedContainer.post { setupCoverLayoutParams() }
                    }
                )
            }
        }

        // HOME场景：恢复默认布局参数
        unifiedContainer.onHomeSwipeRightDrag = { offset ->
            setDrawerDragOffset(offset)
        }
        // HHOME场景：恢复默认布局参数
        unifiedContainer.onHomeSwipeRightRelease = { shouldOpen ->
            finishDrawerDrag(shouldOpen)
        }
        // 重置 ViewModel 相关状态
    }

    /**
     * 设置 ivPlayCover 在playLayer 中的布局参数...竖屏: 宽度为容器宽度* 0.93...
     */
    private fun setupCoverLayoutParams() {
        coverLayoutHelper.setupCoverLayoutParams()
    }



    /**
     */
    private fun setupSceneParams() {
        sceneRegistry.registerAll()
    }

    private fun getCoverBottomInContainer(): Int {
        val density = resources.displayMetrics.density
        val containerWidth = unifiedContainer.width
        val containerHeight = unifiedContainer.height
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            val coverSize = minOf(
                (containerWidth * 0.35f).toInt(),
                containerHeight - resources.getDimensionPixelSize(R.dimen.cover_min_top_offset)
            )
            val coverTop = ((containerHeight - coverSize) / 2f).toInt()
            val lyricScale = 0.3f
            return (coverTop + coverSize * lyricScale).toInt()
        }

        val coverHeight: Int
        if (containerWidth == 0) {
            coverHeight = resources.getDimensionPixelSize(R.dimen.cover_height_default)
        } else {
            val hPad = resources.getDimension(R.dimen.spacing_md)
            val availW = containerWidth - 2 * hPad
            coverHeight = (availW * 0.93f).toInt()
        }
        val coverTop = resources.getDimensionPixelSize(R.dimen.cover_top_margin)
        val lyricScale = 0.3f
        return (coverTop + coverHeight * lyricScale).toInt()
    }

    private fun triggerCoverBreathingIfNeeded() {
        if (!::unifiedContainer.isInitialized) return
        if (unifiedContainer.isTransitioning || unifiedContainer.currentScene != UnifiedPlayerContainer.Scene.PLAYER) return
        val isPlaying = playerController?.playState?.value == PlayState.PLAYING
        animateCoverBreathing(isPlaying)
    }

    private fun animateCoverBreathing(isPlaying: Boolean) {
        coverLayoutHelper.animateCoverBreathing(isPlaying)
    }

    private fun updateLyricAnchor() {
        val density = resources.displayMetrics.density
        val coverBottom = getCoverBottomInContainer()
        val lyricBottomPad = resources.getDimensionPixelSize(R.dimen.lyric_bottom_pad)
        val availableHeight = unifiedContainer.height.toFloat() - coverBottom - lyricBottomPad
        val anchorOffset = availableHeight * 0.5f
        lyricPlayerView.updateAnchorOffset(anchorOffset)
    }

    private fun setupMiniPlayerBar() {
        binding.miniPlayerBar.apply {
            visibility = if (unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.MAIN) View.VISIBLE else View.GONE
            alpha = if (visibility == View.VISIBLE) 1f else 0f
            isClickable = true
            isFocusable = true
            onBarClick = { openPlayPageWithSharedElement() }
            onPlayPauseClick = { playerController?.playPause() }
            onPreviousClick = { playerController?.previous() }
            onNextClick = { playerController?.next() }
        }
        updateMiniPlayerBarSong()
        updateMiniPlayerBarPlayback()
        updateMiniPlayerBarProgress()
    }

    private fun updateMiniPlayerBarSong() {
        val song = playerController?.currentSong?.value
        val coverUri = song?.let { coverUriResolver.resolveCoverUri(it).ifBlank { it.albumArtPath ?: "" } }
        binding.miniPlayerBar.setSongInfo(
            song?.title ?: getString(R.string.no_music_playing),
            song?.artist.orEmpty()
        )
        binding.miniPlayerBar.setCoverImage(coverUri)
    }

    private fun updateMiniPlayerBarPlayback() {
        val isPlaying = playerController?.playState?.value == PlayState.PLAYING
        binding.miniPlayerBar.setPlaying(isPlaying)
    }

    private fun updateMiniPlayerBarProgress() {
        val pos = playerController?.position?.value ?: 0L
        val duration = playerController?.duration?.value ?: 0L
        binding.miniPlayerBar.updateRemainingTime((duration - pos).coerceAtLeast(0L))
    }

    override fun initObserver() {
        observePlaybackState()
        observeCurrentSong()
        observeCoverExtracted()
        observeUsbSampleRate()
        observePosition()
        observePlayMode()
    }

    /**
     * 监听异步封面提取完成事件，刷新封面和背景取色
     */
    private fun observeCoverExtracted() {
        coverUriResolver.coverExtractedEvent.observe(this) { event ->
            val (songPath, coverUri) = event ?: return@observe
            if (coverUri.isBlank()) return@observe

            // 更新封面 URI 缓存
            coverUriResolver.updateCache(songPath, coverUri)

            // 如果是当前歌曲，更新封面图和背景取色
            val currentSong = playerController?.currentSong?.value
            if (currentSong != null && songPath == currentSong.path) {
                AppLogger.d("CoverDebug", "coverExtractedEvent: path=$coverUri for ${currentSong.title}")
                playCoverView.loadCover(coverUri)
                loadCoverBackground(coverUri)
                updateMiniPlayerBarSong()
            }
        }
    }

    private fun observePlaybackState() {
        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.playState?.collect { state ->
                val isPlaying = state == PlayState.PLAYING
                val song = playerController?.currentSong?.value
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause
                    else R.drawable.ic_play
                )
                unifiedContainer.syncRotationState(isPlaying)
                unifiedContainer.isCurrentlyPlaying = isPlaying
                updateMiniPlayerBarPlayback()

                if (isPlaying) {
                    binding.btnPlayPause.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.white_70))
                } else {
                    binding.btnPlayPause.imageTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.white_full))
                }
                binding.btnPlayPause.alpha = 1f

                LyriconProviderManager.setPlaybackState(isPlaying)
                LyricGetterBridge.updatePlaybackState(this@MainActivity, isPlaying)

                if (!isSeeking && !unifiedContainer.isTransitioning && unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.PLAYER) {
                    animateCoverBreathing(isPlaying)
                }

                if (isPlaying) {
                    binding.seekBar.stopBreathing()
                } else {
                    binding.seekBar.startBreathing()
                }

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

                    // 更新 UnifiedMainContainer 中歌曲列表的播放位置
                    val songs = MusicRepository.songs.value
                    val index = songs.indexOfFirst { s -> s.id == it.id }
                    unifiedMainContainer?.updatePlayingPosition(index, it.id)

                    val coverUri = coverUriResolver.resolveCoverUri(it)
                    val playCoverUri = coverUri.ifBlank { it.albumArtPath }
                    binding.tvTitle.text = it.title
                    binding.tvArtist.text = it.artist
                    binding.tvAlbum.text = it.album
                    updateMiniPlayerBarSong()
                    updateLyricSongInfo()

                    if (unifiedContainer.currentScene != UnifiedPlayerContainer.Scene.MAIN) {
                        binding.ivPlayCover.animate().cancel()
                        val isImmersive = unifiedContainer.isImmersiveEnabled
                        val hasCover = playCoverUri.isNotBlank()
                        binding.ivPlayCover.alpha = if (isImmersive) 0f else 1f
                        binding.ivPlayCover.visibility = View.INVISIBLE
                        binding.ivPlayCover.translationX = 0f
                        binding.ivPlayCover.translationY = 0f
                        binding.ivPlayCover.rotationY = 0f
                        when {
                            unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.LYRIC -> {
                                binding.ivPlayCover.scaleX = 0.3f
                                binding.ivPlayCover.scaleY = 0.3f
                            }
                            else -> {
                                binding.ivPlayCover.scaleX = 1f
                                binding.ivPlayCover.scaleY = 1f
                            }
                        }
                        if (hasCover) {
                            BitmapProvider.load(
                                key = playCoverUri,
                                imageView = null,
                                targetWidth = 1080,
                                targetHeight = 1080,
                                callback = { bitmap ->
                                    AppLogger.d("CoverAdjust", "songObserver target onSuccess")
                                    if (bitmap != null && !bitmap.isRecycled) {
                                        loadedCoverImageWidth = bitmap.width
                                        loadedCoverImageHeight = bitmap.height
                                    }
                                    setupCoverLayoutParams()
                                    if (bitmap != null) {
                                        playCoverView.setCoverBitmap(bitmap)
                                    }
                                    val isImmers = unifiedContainer.isImmersiveEnabled
                                    if (!isImmers) {
                                        binding.ivPlayCover.visibility = View.VISIBLE
                                    }
                                    unifiedContainer.post { setupCoverLayoutParams() }
                                }
                            )
                        } else if (playCoverUri.isNotBlank()) {
                            BitmapProvider.load(
                                key = playCoverUri,
                                imageView = null,
                                targetWidth = 1024,
                                targetHeight = 1024,
                                callback = { bitmap ->
                                    if (bitmap != null && !bitmap.isRecycled) {
                                        loadedCoverImageWidth = bitmap.width
                                        loadedCoverImageHeight = bitmap.height
                                    }
                                }
                            )
                        }
                    }
                    // 取色由 loadCoverBackground → applyCoverColors 统一处理，不再重复提取

                    unifiedContainer.updateImmersiveCover(playCoverUri.ifBlank { null })
                    syncMirrorCover(playCoverUri.ifBlank { null })

                    loadLyrics(it.path)
                    updateCapsuleText()
                    updateHiresBadge()

                    loadCoverBackground(playCoverUri)

                    playerServiceBridgeHelper.pushSongUpdate(it)

                    startMarqueeIfNeeded(binding.tvTitle)
                    startMarqueeIfNeeded(binding.tvArtist)
                    startMarqueeIfNeeded(binding.tvAlbum)
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

                // seek 目标检测：位置接近目标后清除 seeking 状态
                if (isSeeking && seekTargetMs >= 0) {
                    val tolerance = (duration * 0.02f).toLong().coerceIn(300L, 2000L)
                    val elapsed = System.currentTimeMillis() - seekFinishTimeMs
                    if (kotlin.math.abs(pos - seekTargetMs) < tolerance || elapsed > 2000L) {
                        isSeeking = false
                        binding.seekBar.isSeekingByUser = false
                        seekTargetMs = -1L
                    }
                }

                binding.seekBar.setProgress(pos, duration)
                binding.tvCurrentTime.text = AudioUtils.formatDuration(pos)
                binding.tvTotalTime.text = AudioUtils.formatDuration(duration)
                val remaining = (duration - pos).coerceAtLeast(0L)
                binding.miniPlayerBar.updateRemainingTime(remaining)
                // setPosition 现在对 scroll-only 行也能正确更新 lastPosition（已修复 LyricLineView）
                lyricPlayerView.setPosition(lyricPos)
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
    private fun startMarqueeIfNeeded(tv: android.widget.TextView) {
        TextMarqueeHelper.startIfNeeded(tv)
    }

    override fun initListener() {}

    /**
     * 获取 PowerList 当前播放封面的真实屏幕矩形。
     *
     * PowerList item 自身通过 translationX/Y 定位，aa_image 的 left/top 只是 item-local
     * 坐标。直接对 aa_image 调 getLocationOnScreen 在场景返回/双 slot 布局刚稳定时容易混入
     * 子 View 的旧 matrix 或上一帧位置。这里对标 Poweramp：先拿 item 的屏幕位置，再用
     * AAItemView.getCoverBounds() 的 item-local bounds 合成最终 rect。
     */
    private fun getListCoverScreenRect(): RectF? {
        val powerListView = findSongsPowerListView() ?: return null
        val provider = powerListView.getDataProvider() as? SongDataProvider ?: return null
        val playingPos = provider.playingPosition
        if (playingPos < 0) return null
        if (playingPos < powerListView.firstVisiblePosition || playingPos > powerListView.lastVisiblePosition) return null
        val itemView = powerListView.layoutState.viewPool[playingPos]?.view as? AAItemView ?: return null
        if (itemView.width <= 0 || itemView.height <= 0) return null

        val coverBounds = itemView.getCoverBounds()
        if (coverBounds.width() <= 0 || coverBounds.height() <= 0) return null

        val mapped = RectF(coverBounds)
        val itemLoc = IntArray(2)
        itemView.getLocationOnScreen(itemLoc)
        mapped.offset(itemLoc[0].toFloat(), itemLoc[1].toFloat())
        return mapped
    }

    /**
     * Gets the screen-space bounding rect of the currently playing song's cover art
     * in the PowerListView, if it's visible.
     */
    private fun getCurrentSongCoverRectWhenReady(attempt: Int = 0, onReady: (RectF?) -> Unit) {
        val coverRect = getListCoverScreenRect()
        if (coverRect == null) {
            if (attempt < 6) {
                binding.root.post { getCurrentSongCoverRectWhenReady(attempt + 1, onReady) }
                return
            }
            onReady(null)
            return
        }
        onReady(coverRect)
    }

    /**
     * 获取专辑详情页的封面 View（ivAlbumCover）
     */
    private fun getAlbumDetailCoverView(): View? {
        if (navController.currentDestination?.id != R.id.nav_album_detail) return null
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as? NavHostFragment
        val currentFragment = navHostFragment?.childFragmentManager?.fragments?.firstOrNull()
        if (currentFragment !is com.rawsmusic.ui.albums.AlbumDetailFragment) return null
        return currentFragment.view?.findViewById(R.id.ivAlbumCover)
    }

    /**
     * 获取专辑详情页封面的屏幕空间矩形
     */
    private fun getAlbumDetailCoverRect(): RectF? {
        val coverView = getAlbumDetailCoverView() ?: return null
        val loc = IntArray(2)
        coverView.getLocationOnScreen(loc)
        return RectF(
            loc[0].toFloat(),
            loc[1].toFloat(),
            (loc[0] + coverView.width).toFloat(),
            (loc[1] + coverView.height).toFloat()
        )
    }

    private fun findSongsPowerListView(): com.rawsmusic.core.ui.widget.powerlist.PowerListView? {
        // 防御: 某些 layout 配置下 unifiedMainContainer 可能未初始化, 安全返回 null
        return try {
            unifiedMainContainer?.getSongsPowerListView()
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Finds the cover ImageView (aa_image) of the currently playing song
     * from the visible items in the PowerListView.
     */
    private fun getListCoverView(): View? {
        val powerListView = findSongsPowerListView() ?: return null
        val provider = powerListView.getDataProvider() as? com.rawsmusic.core.ui.adapter.SongDataProvider ?: return null
        val playingPos = provider.playingPosition
        if (playingPos < 0) return null
        if (playingPos < powerListView.firstVisiblePosition || playingPos > powerListView.lastVisiblePosition) return null
        val holder = powerListView.layoutState.viewPool[playingPos] ?: return null
        val itemView = holder.view ?: return null
        return itemView.findViewById(com.rawsmusic.core.ui.R.id.aa_image)
    }

    /**
     */
    private fun getListCoverPosition(): Quad<Float, Float, Float, Float>? {
        val rect = getListCoverScreenRect() ?: return null
        return Quad(
            rect.left,
            rect.top,
            rect.width(),
            rect.height()
        )
    }

    private fun getViewScreenRect(view: View): RectF {
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return RectF(
            loc[0].toFloat(),
            loc[1].toFloat(),
            (loc[0] + view.width).toFloat(),
            (loc[1] + view.height).toFloat()
        )
    }

    /** 获取播放页封面目标矩形位置 */
    /**
     */
    /**
     * 计算播放页封面目标矩形，宽度和高度与 setupCoverLayoutParams 保持一致
     */
    private fun getPlayCoverTargetRect(): android.graphics.RectF {
        val density = resources.displayMetrics.density
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            val containerW = unifiedContainer.width.toFloat()
            val containerH = unifiedContainer.height.toFloat()
            val coverSize = minOf(containerW * 0.35f, containerH - resources.getDimension(R.dimen.cover_min_top_offset))
            val coverLeft = (containerW * 0.5f - coverSize) / 2f
            val coverTop = (containerH - coverSize) / 2f
            return android.graphics.RectF(coverLeft, coverTop, coverLeft + coverSize, coverTop + coverSize)
        } else {
            val containerW = unifiedContainer.width.toFloat()
            val containerH = unifiedContainer.height.toFloat()
            val hPad = resources.getDimension(R.dimen.spacing_md)
            val availW = containerW - 2f * hPad
            val coverWidth = (availW * 0.97f).toInt()
            val maxHeightPx = (containerH * 0.5f).toInt()

            var desiredWidth = coverWidth
            if (loadedCoverImageWidth > 0 && loadedCoverImageHeight > 0) {
                val aspectRatio = loadedCoverImageHeight.toFloat() / loadedCoverImageWidth.toFloat()
                val desiredHeight = (coverWidth * aspectRatio).toInt()
                if (desiredHeight > maxHeightPx) {
                    desiredWidth = (maxHeightPx / aspectRatio).toInt()
                    val minWidth = resources.getDimensionPixelSize(R.dimen.min_width)
                    desiredWidth = desiredWidth.coerceIn(minWidth, coverWidth)
                }
            }

            val w = desiredWidth.toFloat()
            val h = if (binding.ivPlayCover.height > 0) {
                binding.ivPlayCover.height.toFloat()
            } else if (loadedCoverImageWidth > 0 && loadedCoverImageHeight > 0) {
                val aspectRatio = loadedCoverImageHeight.toFloat() / loadedCoverImageWidth.toFloat()
                (w * aspectRatio).coerceAtMost(maxHeightPx.toFloat())
            } else {
                (w * 1.2f).coerceAtMost(maxHeightPx.toFloat())
            }

            val left = (containerW - w) / 2f
            val top = resources.displayMetrics.heightPixels * 0.06f
            return android.graphics.RectF(left, top, left + w, top + h)
        }
    }

    fun openPlayPageFromSongClick() {
        if (unifiedContainer.currentScene != UnifiedPlayerContainer.Scene.MAIN) return
        openPlayPageWithSharedElement()
    }

    fun navigateToFolderFromSearch(folderPath: String) {
        try {
            binding.navHostFragment.visibility = View.GONE
            unifiedMainContainer?.visibility = View.VISIBLE
            unifiedMainContainer?.navigateTo(
                com.rawsmusic.core.ui.scene.NavScene.FOLDER_HIERARCHY,
                folderPath
            )
        } catch (_: Exception) {}
    }

    private fun openPlayPageWithSharedElement() {
        if (unifiedContainer.currentScene != UnifiedPlayerContainer.Scene.MAIN) return

        // 保存进入播放器前的 Fragment 导航目标，用于返回时恢复
        prePlayerFragmentDest = navController.currentDestination?.id
        prePlayerWasInFragmentMode = binding.navHostFragment.visibility == View.VISIBLE
        prePlayerContainerScene = if (unifiedMainContainer != null) unifiedMainContainer?.getCurrentScene() else null

        if (drawerState.isOpen) closeSideMenu()

        val currentSong = playerController?.currentSong?.value ?: run {
            unifiedContainer.openPlayPage(true)
            return
        }

        // 无专辑图时直接淡入，不参与共享元素动画
        val coverUri = coverUriResolver.resolveCoverUri(currentSong)
        val playCoverUri = coverUri.ifBlank { currentSong.albumArtPath ?: "" }
        if (playCoverUri.isBlank()) {
            unifiedContainer.openPlayPage(true)
            return
        }

        unifiedContainer.isTransitioning = true
        coverAnimState.isActive = true

        loadedCoverImageWidth = 0
        loadedCoverImageHeight = 0

        unifiedContainer.postDelayed({
            if (unifiedContainer.isTransitioning && unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.MAIN) {
                AppLogger.w("SceneTransition", "=== openPlayPageWithSharedElement safety timeout, resetting isTransitioning ===")
                unifiedContainer.isTransitioning = false
                coverAnimState.reset()
                unifiedContainer.openPlayPage(true)
            }
        }, 2000)

        // 根据当前页面选择封面来源：专辑详情页用自己的封面，歌曲列表用列表中的封面
        val isFromAlbumDetail = navController.currentDestination?.id == R.id.nav_album_detail
        val sourceCoverRect = if (isFromAlbumDetail) getAlbumDetailCoverRect() else null
        // 保存进入播放器时的源封面坐标，用于返回时与进入动画对齐
        savedAlbumDetailCoverRect = if (isFromAlbumDetail) sourceCoverRect else null
        savedListCoverRect = if (isFromAlbumDetail) null else null

        // 根据当前页面选择封面来源：专辑详情页用自己的封面，歌曲列表用列表中的封面
        fun startCoverAnimation(coverRect: RectF?) {
            // 封面不可见，跳过共享元素动画，直接淡入
            if (coverRect == null) {
                coverAnimState.reset()
                unifiedContainer.isTransitioning = false
                unifiedContainer.openPlayPage(true)
                return
            }

            val containerLoc = IntArray(2)
            unifiedContainer.getLocationOnScreen(containerLoc)
            coverAnimState.freezeLocation(containerLoc)

            val startRect = coverRect
            if (!isFromAlbumDetail) {
                savedListCoverRect = RectF(startRect)
            }

            // 先用旧布局获取目标宽高（封面在 MAIN 场景的布局尺寸）
            val targetRect = getPlayCoverTargetRect()
            if (targetRect.width() <= 0f || targetRect.height() <= 0f) {
                coverAnimState.reset()
                unifiedContainer.isTransitioning = false
                unifiedContainer.openPlayPage(true)
                return
            }

            val targetW = targetRect.width()
            val targetH = targetRect.height()

            binding.playBottomPanel?.visibility = View.VISIBLE
            binding.playBottomPanel?.alpha = 0f
            binding.playTitleGroup.apply {
                pivotX = width / 2f
                pivotY = height / 2f
                translationX = 0f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
                visibility = View.VISIBLE
                alpha = 0f
            }
            binding.btnMore?.visibility = View.GONE
            binding.btnMore?.alpha = 0f
            binding.playBgView.visibility = View.VISIBLE
            binding.playBgView.alpha = 0f

            // 先改布局参数（Poweramp 方式：先布局，再计算目标位置）
            val marginXxl = resources.getDimensionPixelSize(R.dimen.spacing_xxl)
            playCoverView.apply {
                val lp = layoutParams as? android.widget.FrameLayout.LayoutParams
                if (lp != null) {
                    lp.width = targetW.toInt()
                    lp.height = targetH.toInt()
                    lp.gravity = android.view.Gravity.CENTER_HORIZONTAL
                    lp.marginStart = marginXxl
                    lp.marginEnd = marginXxl
                    layoutParams = lp
                }
            }

            // 从布局参数直接计算目标中心（Poweramp 方式：用 Rect 而非 getLocationOnScreen）
            // 先改布局参数后，用 containerW/2f 作为水平中心（FrameLayout CENTER_HORIZONTAL）
            val cl = coverAnimState.frozenLocation!!
            val containerW = unifiedContainer.width.toFloat()
            val targetCenterX = containerW / 2f
            val targetCenterY = targetRect.centerY()

            val startW = startRect.width()
            val startH = startRect.height()

            val startScaleX = startW / targetW
            val startScaleY = startH / targetH

            // 与返回动画使用一致的坐标系：屏幕坐标 + 容器 scale 补偿
            // 两个动画必须用相同的公式，否则封面飞到不同位置
            val startCenterScreenX = startRect.left + startW / 2f
            val startCenterScreenY = startRect.top + startH / 2f
            val cLoc = IntArray(2)
            unifiedContainer.getLocationOnScreen(cLoc)
            val mv = FloatArray(9)
            unifiedContainer.matrix.getValues(mv)
            val cScaleX = mv[android.graphics.Matrix.MSCALE_X]
            val cScaleY = mv[android.graphics.Matrix.MSCALE_Y]
            val cVisualLeft = cLoc[0] + unifiedContainer.pivotX * (1f - cScaleX)
            val cVisualTop = cLoc[1] + unifiedContainer.pivotY * (1f - cScaleY)
            val translatedX = (startCenterScreenX - cVisualLeft) / cScaleX - targetCenterX
            val translatedY = (startCenterScreenY - cVisualTop) / cScaleY - targetCenterY

            playCoverView.apply {
                // 圆角：歌曲列表使用 zoom 状态的圆角，专辑详情页使用 22dp
                val cornerDp = if (isFromAlbumDetail) 22f
                else (findSongsPowerListView()?.currentCoverCornerRadiusDp ?: 18f)
                cornerRadius = cornerDp * resources.displayMetrics.density
                translationX = translatedX
                translationY = translatedY
                scaleX = startScaleX
                scaleY = startScaleY
                alpha = 1f
                visibility = View.VISIBLE
            }

            // 隐藏源封面（歌曲列表或专辑详情页）
            if (isFromAlbumDetail) {
                getAlbumDetailCoverView()?.visibility = View.INVISIBLE
            } else {
                getListCoverView()?.visibility = View.INVISIBLE
            }

            // 圆角：歌曲列表使用 zoom 状态的圆角，专辑详情页使用 22dp
            val sourceCornerDp = if (isFromAlbumDetail) 22f
            else (findSongsPowerListView()?.currentCoverCornerRadiusDp ?: 18f)
            val baseCornerPx = sourceCornerDp * resources.displayMetrics.density
            val playerCornerPx = baseCornerPx
            val mainCornerPx = if (startScaleX > 0.01f) playerCornerPx / startScaleX else playerCornerPx
            val mainCoverParams = UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                scaleX = startScaleX,
                scaleY = startScaleY,
                translationX = translatedX,
                translationY = translatedY,
                visibility = View.VISIBLE,
                cornerRadius = mainCornerPx
            )
            unifiedContainer.registerSceneParams(
                R.id.ivPlayCover,
                UnifiedPlayerContainer.Scene.MAIN,
                mainCoverParams
            )

            listOf(R.id.playBottomPanel, R.id.btnMore).forEach { viewId ->
                unifiedContainer.registerSceneParams(
                    viewId,
                    UnifiedPlayerContainer.Scene.MAIN,
                    UnifiedPlayerContainer.SceneParams(
                        scene = UnifiedPlayerContainer.Scene.MAIN,
                        alpha = 0f,
                        visibility = View.GONE
                    )
                )
            }
            unifiedContainer.registerSceneParams(
                R.id.playTitleGroup,
                UnifiedPlayerContainer.Scene.MAIN,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.MAIN,
                    alpha = 0f,
                    visibility = View.GONE
                )
            )
            unifiedContainer.registerSceneParams(
                R.id.playBgView,
                UnifiedPlayerContainer.Scene.MAIN,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.MAIN,
                    alpha = 0f,
                    visibility = View.VISIBLE
                )
            )

            unifiedContainer.transitionToScene(
                UnifiedPlayerContainer.Scene.PLAYER,
                duration = 500
            )
        }

        // 专辑详情页直接使用已获取的封面矩形，歌曲列表异步获取
        if (isFromAlbumDetail) {
            startCoverAnimation(sourceCoverRect)
        } else {
            getCurrentSongCoverRectWhenReady { rect -> startCoverAnimation(rect) }
        }
    }

    // ==================== 封面手势处理（参考 Poweramp 的 r + c0） ====================

    private var isSwitchingSong = false
    private var currentLyricText = ""

    private fun setupAudioInfoCapsule() {
        audioInfoCapsuleHelper.setup()
    }

    private fun updateHiresBadge() {
        if (!::unifiedContainer.isInitialized) return
        audioCapsuleUiHelper.updateHiresBadge()
    }

    private fun isMusicSymbolOnly(text: String): Boolean {
        return audioCapsuleUiHelper.isMusicSymbolOnly(text)
    }

    private fun updateCapsuleText() {
        audioCapsuleUiHelper.updateText()
    }

    private fun setupCoverGesture() {
        // 初始化封面手势处理器
        coverGestureHandler = CoverGestureHandler(this, unifiedContainer, this)

        // 创建封面触摸处理器
        val coverTouchHandler = coverGestureHandler.createCoverTouchHandler()

        binding.ivPlayCover.setOnTouchListener(coverTouchHandler)
        binding.playTitleGroup.setOnTouchListener(coverTouchHandler)

        // 沉浸模式播放界面：在沉浸背景的封面区域上滑进入歌词页
        binding.immersiveBackground?.setOnTouchListener(coverGestureHandler.createImmersiveBackgroundTouchHandler())

        // 歌词内容容器触摸处理器
        binding.lyricContentContainer.setOnTouchListener(coverGestureHandler.createLyricContentTouchHandler(coverTouchHandler))
    }

    private fun openQueuePage() {
        playerSubPageHelper.openQueuePage()
    }

    private fun closeQueuePage() {
        playerSubPageHelper.closeQueuePage()
    }

    private fun openAlbumDetailPage() {
        playerSubPageHelper.openAlbumDetailPage()
    }

    private fun closeAlbumDetailPage() {
        playerSubPageHelper.closeAlbumDetailPage()
    }

    private fun openEffectsPage() {
        playerSubPageHelper.openEffectsPage()
    }

    private fun closeEffectsPage() {
        playerSubPageHelper.closeEffectsPage()
    }

    /**
     */
    private fun performSwipeToChangeSong(direction: Int) {
        if (direction > 0) {
            playerController?.next()
        } else {
            playerController?.previous()
        }
        binding.ivPlayCover.animate().cancel()
        binding.ivPlayCover.translationX = 0f
        binding.ivPlayCover.rotationY = 0f
        // 沉浸模式下封面由 ImmersiveBackgroundView 渲染，ivPlayCover 保持隐藏
        val isImmersive = unifiedContainer.isImmersiveEnabled
        binding.ivPlayCover.alpha = if (isImmersive) 0f else 1f
        if (isImmersive) {
            binding.ivPlayCover.visibility = View.INVISIBLE
        }
        isSwitchingSong = false
    }

    // CoverGestureCallbacks 接口实现
    override fun isSwitchingSong(): Boolean = isSwitchingSong

    override fun getCoverView(): View = binding.ivPlayCover

    override fun onCoverLongPress() {
        showFullCoverViewer()
    }

    override fun onCoverDragStart() {
        // 保存当前 Fragment 目标，用于返回时恢复（如果尚未保存）
        if (prePlayerFragmentDest == null) {
            prePlayerFragmentDest = navController.currentDestination?.id
        }
        if (!prePlayerWasInFragmentMode) {
            prePlayerWasInFragmentMode = binding.navHostFragment.visibility == View.VISIBLE
        }
        // PLAYER 场景下 navHostFragment 为 GONE，临时设为 INVISIBLE 使其参与布局
        if (prePlayerWasInFragmentMode) {
            binding.navHostFragment.visibility = View.INVISIBLE
        }
        // 对标 onPreparePlayerToMain：先让列表参与布局，等一帧取真实封面坐标
        binding.navHostFragment.visibility = View.GONE
        unifiedMainContainer?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            translationX = 0f
            translationY = 0f
            scaleX = 1f
            scaleY = 1f
            requestLayout()
        }
        binding.root.post {
            getCurrentSongCoverRectWhenReady { rect ->
                val stableRect = savedListCoverRect ?: rect
                val targetCoverQuad = stableRect?.let {
                    Quad(it.left, it.top, it.width(), it.height())
                }
                // 注册正确的 MAIN 参数（对标 Poweramp: 在拖拽前设置目标位置）
                if (targetCoverQuad != null) {
                    val targetRect = getPlayCoverTargetRect()
                    val targetW = if (targetRect.width() > 0f) targetRect.width() else binding.ivPlayCover.width.toFloat()
                    val targetH = if (targetRect.height() > 0f) targetRect.height() else binding.ivPlayCover.height.toFloat()
                    val sourceCornerDp = findSongsPowerListView()?.currentCoverCornerRadiusDp ?: 18f
                    sceneParamsHelper.registerCoverCollapseParamsWithSourcePos(
                        targetCoverQuad, targetRect, targetW, targetH,
                        binding.ivPlayCover, resources.displayMetrics.density, sourceCornerDp
                    )
                } else {
                    registerCoverCollapseParams()
                }
                unifiedContainer.startCoverDrag()
            }
        }
    }

    override fun onCoverDragUpdate(ratio: Float) {
        unifiedContainer.updateCoverDrag(ratio)
    }

    override fun onCoverDragEnd(shouldClose: Boolean) {
        unifiedContainer.endCoverDrag(shouldClose)
    }

    override fun onCoverSwipeUpStart() {
        registerCoverLyricParams()
        unifiedContainer.startCoverSwipeUpDrag()
    }

    override fun onCoverSwipeUpUpdate(ratio: Float) {
        unifiedContainer.updateCoverSwipeUpDrag(ratio)
    }

    override fun onCoverSwipeUpEnd(shouldOpen: Boolean) {
        unifiedContainer.endCoverSwipeUpDrag(shouldOpen)
    }

    override fun onSwipeToChangeSong(direction: Int) {
        performSwipeToChangeSong(direction)
    }

    override fun onSwipeCancel() {
        binding.ivPlayCover.animate().translationX(0f).rotationY(0f).setDuration(resources.getInteger(R.integer.animation_duration_normal).toLong()).start()
    }

    override fun onLyricTapToPlayer() {
        binding.playBgView.syncFrom(binding.lyricBgView)
        binding.playBgView.resumeAnimations()
        unifiedContainer.startCoverSwipeUpDrag()
        unifiedContainer.endCoverSwipeUpDrag(shouldOpen = true)
    }

    override fun onImmersiveSwipeLeft() {
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

    override fun onImmersiveSwipeDown() {
        if (prePlayerWasInFragmentMode) {
            unifiedContainer.registerSceneParams(
                R.id.ivPlayCover,
                UnifiedPlayerContainer.Scene.MAIN,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.MAIN,
                    alpha = 0f,
                    visibility = View.GONE
                )
            )
        }
        unifiedContainer.closePlayPage(false)
    }

    private fun getMiniPlayerCoverPosition(): Quad<Float, Float, Float, Float>? {
        val coverSize = resources.getDimension(R.dimen.mini_player_cover_size)
        val padding = resources.getDimension(R.dimen.spacing_lg)
        val marginStart = resources.getDimension(R.dimen.spacing_xl)
        val marginBottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val windowInsets = windowManager.currentWindowMetrics.windowInsets
            val navigationBars = windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
            navigationBars.bottom.toFloat()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.heightPixels - window.decorView.height
        }.toFloat()

        val barHeight = resources.getDimension(R.dimen.mini_player_bar_height)
        val containerHeight = unifiedContainer.height.toFloat()

        val x = marginStart + padding
        val y = containerHeight - marginBottom - barHeight + (barHeight - coverSize) / 2f

        return Quad(x, y, coverSize, coverSize)
    }

    private fun registerCoverCollapseParams() {
        sceneParamsHelper.registerCoverCollapseParams(prePlayerWasInFragmentMode, prePlayerFragmentDest, savedAlbumDetailCoverRect)
    }

    private fun registerImmersiveCoverCollapseParams() {
        sceneParamsHelper.registerImmersiveCoverCollapseParams()
    }

    /**
     * 沉浸模式下，上滑封面会触发淡入淡出效果，封面缩小到左上角。     */
    private fun registerCoverLyricParams() {
        sceneParamsHelper.registerCoverLyricParams()
    }

    /**
     * 在 openPlayPageWithSharedElement 之后恢复默认场景参数
     * 防止动画参数残留导致后续转场异常
     */
    private fun restoreDefaultSceneParams() {
        sceneParamsHelper.restoreDefaultSceneParams(
            prePlayerWasInFragmentMode,
            if (unifiedMainContainer != null) unifiedMainContainer else null
        )
    }

    private fun setupPlayPageListeners() {
        // 播放控制按钮 - 点击事件绑定到容器
        binding.btnPlayPauseContainer.setOnClickListener {
            ButtonAnimHelper.playPauseAnim(it, true) {
                playerController?.playPause()
            }
        }
        binding.btnNextContainer.setOnClickListener {
            ButtonAnimHelper.pressReleaseAnim(it)
            ButtonAnimHelper.coverSwitchAnim(binding.ivPlayCover, true)
            playerController?.next()
        }
        binding.btnPreviousContainer.setOnClickListener {
            ButtonAnimHelper.pressReleaseAnim(it)
            ButtonAnimHelper.coverSwitchAnim(binding.ivPlayCover, false)
            playerController?.previous()
        }

        // 播放模式按钮 - 点击事件绑定到容器
        binding.btnPlayModeContainer.setOnClickListener {
            ButtonAnimHelper.secondaryPressAnim(it)
            playerController?.let { ctrl ->
                ctrl.cyclePlayMode()
                playModePopupHelper.updatePlayModeIcon(ctrl.playMode.value)
            }
        }
        binding.btnPlayModeContainer.setOnLongClickListener {
            playModePopupHelper.show()
            true
        }

        // 更多按钮 - 点击事件绑定到容器
        binding.btnMoreActionContainer.setOnClickListener {
            ButtonAnimHelper.secondaryPressAnim(it)
            songActionSheetHelper.show()
        }



        binding.btnMore?.setOnClickListener {
            ButtonAnimHelper.secondaryPressAnim(it)
            (it as? AnimatedMoreButton)?.toggleExpanded()
            metadataCardPopupHelper.togglePlayCard()
        }
        // 根据播放模式更新图标和文字
        binding.playMetadataCard.onMetadataClick = {
            metadataDetailHelper.open()
        }
        setupCoverGesture()
        setupAudioInfoCapsule()
        binding.btnFullCoverBack.setOnClickListener {
            hideFullCoverViewer()
        }
        fullCoverViewerHelper = FullCoverViewerHelper(this, binding, resources)
        setupFullCoverViewerGestures()
        binding.seekBar.onSeekStartListener = {
            isSeeking = true
            binding.seekBar.isSeekingByUser = true
            unifiedContainer.disableGestureIntercept = true
        }
        binding.seekBar.onSeekStopListener = { fraction ->
            val duration = playerController?.duration?.value ?: 0L
            val seekPos = (fraction * duration).toLong()
            // 不立刻清除 isSeeking/isSeekingByUser，等 observePosition 检测到位置接近目标后再清除
            seekTargetMs = seekPos
            seekFinishTimeMs = System.currentTimeMillis()
            lyricsNeedSeekTo = true  // 下一帧歌词更新使用 seekTo 重置时钟
            playerController?.seekTo(seekPos)
            unifiedContainer.disableGestureIntercept = false
            triggerCoverBreathingIfNeeded()
        }
        // 计算进度条位置
        playerController?.let { ctrl ->
            playModePopupHelper.updatePlayModeIcon(ctrl.playMode.value)
        }

        binding.playBgView.setOnClickListener { /* 防止点击穿透 */ }

        binding.btnAudioQuality.setOnClickListener {
            registerCoverCollapseParams()
            AppLogger.w("SceneTransition", "=== btnAudioQuality clicked: setting prePlayerWasInFragmentMode=true, prePlayerFragmentDest=nav_audio_settings ===")
            prePlayerWasInFragmentMode = true
            prePlayerFragmentDest = R.id.nav_audio_settings
            unifiedContainer.closePlayPage(false)
        }

        binding.btnQueueBack?.setOnClickListener {
            closeQueuePage()
        }
        binding.btnQueueClear?.setOnClickListener {
            playerController?.clearPriorityQueue()
            refreshQueueList()
        }

        binding.btnAlbumDetailBack?.setOnClickListener {
            closeAlbumDetailPage()
        }

        songActionSheetHelper.setup()
    }

    /**
     * 启动音频可视化
     */
    private fun updateLetterModeContent() {
        audioVisualizerHelper.update(binding.audioVisualizer)
    }

    private fun showAlbumInfo() {
        albumInfoNavigator.open(playerController?.currentSong?.value)
    }

    private fun refreshQueueList() {
        queueListHelper.refresh()
    }

    private fun loadAlbumDetail(song: com.rawsmusic.core.common.model.AudioFile) {
        albumDetailHelper.load(song)
    }

    private fun setupAlbumDetailEffectCard() {
        effectsPanelHelper.setupAlbumDetailEffectCard()
    }

    private fun syncEffectsPanelState() {
        effectsPanelHelper.syncEffectsPanelState()
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

    private fun setupLyricPageListeners() {
        // Lyric page: only translation toggle, no play controls
        // More button in lyric header
        binding.btnMoreLyric.setOnClickListener {
            ButtonAnimHelper.secondaryPressAnim(it)
            (it as? AnimatedMoreButton)?.toggleExpanded()
            metadataCardPopupHelper.toggleLyricCard()
        }
        binding.lyricMetadataCard.onMetadataClick = {
            metadataDetailHelper.open()
        }
        binding.btnTranslationToggle?.setOnClickListener {
            val prefs = com.rawsmusic.module.data.prefs.AppPreferences.Lyricon
            val current = prefs.displayTranslation
            val newState = !current
            prefs.displayTranslation = newState
            lyricPlayerView.updateDisplayTranslation(displayTranslation = newState, displayRoma = !newState)
            binding.btnTranslationToggle?.alpha = if (newState) 1f else 0.4f
        }
        binding.btnTranslationToggle?.alpha = if (com.rawsmusic.module.data.prefs.AppPreferences.Lyricon.displayTranslation) 1f else 0.4f
        // Lyric scroll boundary callback - handled by LyricPlayerView internally
        lyricPlayerView.lyricCountChangeListeners.add(object : RawsLyricView.LyricCountChangeListener {
            override fun onLyricTextChanged(old: String, new: String) {}
            override fun onLyricChanged(news: List<IRichLyricLine>, removes: List<IRichLyricLine>) {
            }
        })
    }



    fun setPlayerController(controller: PlayerController) {
        playerControllerBindingHelper.bind(controller)
    }

    fun toggleSideMenu() {
        drawerMotionHelper.toggle()
    }

    private fun applyDrawerColorSync(@Suppress("UNUSED_PARAMETER") drawerOpen: Boolean) {
    }




    private fun setDrawerDragOffset(offset: Float) {
        drawerMotionHelper.setDragOffset(offset)
    }

    /** 完成抽屉拖拽（根据速度和位置决定是否打开） */
    private fun finishDrawerDrag(shouldOpen: Boolean) {
        drawerMotionHelper.finishDrag(shouldOpen)
    }

    private fun loadLyrics(songPath: String) {
        lyricLoadHelper.load(songPath)
    }

    private fun requestAudioPermission() {
        startupPermissionFlowHelper.request()
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
     *   1. 播放界面/歌词界面 → 封面拖拽返回（对标 Poweramp c0 手势）
     *   2. 主界面子页面 → 容器拖拽返回上级（对标 Poweramp SceneController 拖拽）
     */
    private var predictiveBackCallback: android.window.OnBackAnimationCallback? = null
    private var isPredictiveBackRegistered = false

    @android.annotation.SuppressLint("NewApi")
    private fun setupPredictiveBack() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return

        predictiveBackCallback = object : android.window.OnBackAnimationCallback {
            private var dragType = BackDragType.NONE

            override fun onBackStarted(backEvent: android.window.BackEvent) {
                val currentScene = unifiedContainer.currentScene
                val swipeRight = backEvent.swipeEdge == android.window.BackEvent.EDGE_LEFT

                when {
                    // 播放界面 → 封面拖拽返回主界面
                    currentScene == UnifiedPlayerContainer.Scene.PLAYER -> {
                        onCoverDragStart()
                        unifiedContainer.startCoverDrag(swipeRight, UnifiedPlayerContainer.Scene.MAIN)
                        dragType = BackDragType.COVER
                    }
                    // 歌词界面 → 封面拖拽返回播放界面
                    currentScene == UnifiedPlayerContainer.Scene.LYRIC -> {
                        unifiedContainer.startCoverDrag(swipeRight, UnifiedPlayerContainer.Scene.PLAYER)
                        dragType = BackDragType.COVER
                    }
                    // 主界面 + 容器模式 + 不在HOME → 容器拖拽返回上级
                    currentScene == UnifiedPlayerContainer.Scene.MAIN &&
                            unifiedMainContainer != null &&
                            !unifiedMainContainer!!.isAtHome() -> {
                        val started = unifiedMainContainer?.startDragBack(
                            swipeRight = swipeRight,
                            initialTouchX = backEvent.touchX,
                            initialTouchY = backEvent.touchY
                        )
                        if (started == true) {
                            dragType = BackDragType.CONTAINER
                        }
                    }
                }
            }

            override fun onBackProgressed(backEvent: android.window.BackEvent) {
                when (dragType) {
                    BackDragType.COVER -> unifiedContainer.updateCoverDragProgress(backEvent.progress)
                    BackDragType.CONTAINER -> unifiedMainContainer?.updateDragBackProgress(backEvent.progress)
                    BackDragType.NONE -> {}
                }
            }

            override fun onBackInvoked() {
                when (dragType) {
                    BackDragType.COVER -> {
                        onCoverDragEnd(true)
                        unifiedContainer.releaseCoverDrag(true, 0f)
                        dragType = BackDragType.NONE
                    }
                    BackDragType.CONTAINER -> {
                        unifiedMainContainer?.endDragBack(true)
                        dragType = BackDragType.NONE
                    }
                    BackDragType.NONE -> {
                        @Suppress("DEPRECATION")
                        onBackPressed()
                    }
                }
            }

            override fun onBackCancelled() {
                when (dragType) {
                    BackDragType.COVER -> {
                        onCoverDragEnd(false)
                        unifiedContainer.releaseCoverDrag(false, 0f)
                        dragType = BackDragType.NONE
                    }
                    BackDragType.CONTAINER -> {
                        unifiedMainContainer?.endDragBack(false)
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

    private fun updateStatusBarForLevel(level: UnifiedPlayerContainer.Scene) {
        systemBarsHelper.updateForScene(level, isDarkMode)
    }

    /**
     * 同步加载镜像封面（沉浸式播放模式下的底部倒影）
     */
    private fun syncMirrorCover(coverUri: String?) {
        return
    }

    /**
     * DynamicCoverBackgroundView 加载封面背景并提取主色调 + 暗色调 + 歌词背景色 + 渐变叠加 + 模糊效果
     */
    private fun loadCoverBackground(albumArtPath: String) {
        coverBackgroundManager.loadCoverBackground(albumArtPath)
    }

    private fun applyDefaultColors() {
        coverBackgroundManager.applyDefaultColors()
    }

    /**
     * 应用默认背景：亮色模式白底黑字，暗色模式纯黑底白字
     * 强制覆盖所有沉浸/封面背景层
     */
    private fun applyDefaultBackground() {
        coverBackgroundManager.applyDefaultBackground()

        // ivPlayCover 可见性由 MainActivity 控制
        val isDefaultBg = com.rawsmusic.module.data.prefs.AppPreferences.UI.isDefaultBackgroundEnabled
        if (isDefaultBg) {
            val isOnMainScene = ::unifiedContainer.isInitialized && unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.MAIN
            binding.ivPlayCover.visibility = if (isOnMainScene) View.INVISIBLE else View.VISIBLE
            binding.ivPlayCover.alpha = if (isOnMainScene) 0f else 1f
        } else {
            // 重新加载封面背景（异步更新封面数据）
            playerController?.currentSong?.value?.let { song ->
                val coverUri = coverUriResolver.resolveCoverUri(song).ifBlank { song.albumArtPath }
                if (coverUri.isNotBlank()) {
                    coverBackgroundManager.loadCoverBackground(coverUri)
                } else {
                    coverBackgroundManager.applyDefaultColors()
                }
            } ?: run {
                coverBackgroundManager.applyDefaultColors()
            }
        }
    }

    private fun resetDynamicBackgroundFallback() {
        // 已移至 CoverBackgroundManager
    }

    /**
     * 应用封面提取的颜色到各个背景视图
     * 同时更新侧边菜单 DynamicCoverBackgroundView 的颜色
     */
    private fun applyCoverColors() {
        coverBackgroundManager.applyCoverColors()
    }

    private fun applyLyricColors() {
        lyricStyleHelper.applyLyricColors()
    }

    /** 显示全屏封面查看器 */
    private fun showFullCoverViewer() {
        val song = playerController?.currentSong?.value ?: return
        val coverUri = coverUriResolver.resolveCoverUri(song)
        fullCoverViewerHelper.show(coverUri)
    }

    private fun setupFullCoverViewerGestures() {
        fullCoverViewerHelper.setupGestures()
    }

    /** 隐藏全屏封面查看器 */
    private fun hideFullCoverViewer() {
        fullCoverViewerHelper.hide()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!::navController.isInitialized) return super.onSupportNavigateUp()
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    /**
     * 处理返回键事件，根据当前页面层级决定是否退出应用
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::unifiedContainer.isInitialized && unifiedContainer.isTransitioning) {
            return
        }
        if (metadataDetailHelper.isVisible) {
            metadataDetailHelper.close()
            return
        }
        if (binding.playMetadataCard.isExpanded) {
            binding.playMetadataCard.collapse()
            return
        }
        if (binding.lyricMetadataCard.isExpanded) {
            binding.lyricMetadataCard.collapse()
            return
        }
        if (::fullCoverViewerHelper.isInitialized && fullCoverViewerHelper.isVisible()) {
            hideFullCoverViewer()
            return
        }
        if (drawerState.isOpen) {
            closeSideMenu()
            return
        }
        if (isSearchActive()) {
            closeSearch()
            return
        }
        if (!::unifiedContainer.isInitialized) { super.onBackPressed(); return }
        when (unifiedContainer.currentScene) {
            UnifiedPlayerContainer.Scene.LYRIC -> {
                unifiedContainer.closeLyricPage(true)
                return
            }
            UnifiedPlayerContainer.Scene.QUEUE -> {
                closeQueuePage()
                return
            }
            UnifiedPlayerContainer.Scene.ALBUM_DETAIL -> {
                closeAlbumDetailPage()
                return
            }
            UnifiedPlayerContainer.Scene.EFFECTS -> {
                closeEffectsPage()
                return
            }
            UnifiedPlayerContainer.Scene.PLAYER -> {
                // 与上滑手势保持一致：仅保存状态（如果尚未保存），然后执行带封面对齐的关闭动画
                // 不要提前调用 navigateHome()，否则会先闪一下主界面
                if (prePlayerFragmentDest == null) {
                    prePlayerFragmentDest = navController.currentDestination?.id
                }
                if (!prePlayerWasInFragmentMode) {
                    prePlayerWasInFragmentMode = binding.navHostFragment.visibility == View.VISIBLE
                }
                if (prePlayerContainerScene == null && unifiedMainContainer != null) {
                    prePlayerContainerScene = unifiedMainContainer?.getCurrentScene()
                }
                unifiedContainer.closePlayPageWithCoverAlign(true)
                return
            }
            UnifiedPlayerContainer.Scene.MAIN -> {
                if (binding.navHostFragment.visibility == View.VISIBLE) {
                    val destId = navController.currentDestination?.id
                    AppLogger.w("SceneTransition", "=== onBackPressed MAIN+Fragment: destId=$destId ===")
                    // 从专辑详情页返回时，切换到专辑容器页面
                    if (destId == R.id.nav_album_detail) {
                        switchToContainerMode(com.rawsmusic.core.ui.scene.NavScene.ALBUMS)
                        return
                    }
                    if (isSettingsDestination(destId)) {
                        navigateSettingsBack()
                        return
                    }
                    val popped = navController.popBackStack()
                    AppLogger.w("SceneTransition", "=== onBackPressed: popBackStack=$popped, newDest=${navController.currentDestination?.id} ===")
                    if (!popped || navController.currentDestination?.id == R.id.nav_songs) {
                        AppLogger.w("SceneTransition", "=== onBackPressed: no more back stack, switching to container mode ===")
                        switchToContainerMode()
                    }
                    return
                }
                // 容器模式：如果不是 HOME，返回 HOME
                if (unifiedMainContainer != null && !unifiedMainContainer!!.isAtHome()) {
                    unifiedMainContainer?.navigateHome()
                    return
                }
            }
        }
        if (unifiedMainContainer != null) {
            if (unifiedMainContainer?.onBackPressed() == true) return
            moveTaskToBack(true)
            return
        }
        if (navController.currentDestination?.id != R.id.nav_songs) {
            navController.navigateUp()
            return
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
        if (unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.PLAYER && !unifiedContainer.isTransitioning
            && !metadataDetailHelper.isVisible) {
            val coverView = binding.ivPlayCover
            coverView.getLocationOnScreen(playAreaCoverLoc)
            val coverRight = playAreaCoverLoc[0] + coverView.width
            val coverBottom = playAreaCoverLoc[1] + coverView.height
            val shrink = resources.getDimension(R.dimen.cover_shrink_touch_zone)
            val touchOnCover = ev.rawX >= playAreaCoverLoc[0] + shrink && ev.rawX <= coverRight - shrink &&
                    ev.rawY >= playAreaCoverLoc[1] + shrink && ev.rawY <= coverBottom - shrink

            val titleGroup = binding.playTitleGroup
            val titleGroupLoc = IntArray(2)
            titleGroup.getLocationOnScreen(titleGroupLoc)
            val titleGroupRight = titleGroupLoc[0] + titleGroup.width
            val titleGroupBottom = titleGroupLoc[1] + titleGroup.height
            val touchOnTitleGroup = ev.rawX >= titleGroupLoc[0] && ev.rawX <= titleGroupRight &&
                    ev.rawY >= titleGroupLoc[1] && ev.rawY <= titleGroupBottom

            if (!touchOnCover || touchOnTitleGroup) {
                val leftEdgeZone = resources.getDimension(R.dimen.left_edge_touch_zone)
                val touchOnLeftEdge = ev.rawX < leftEdgeZone

                val seekBar = binding.seekBar
                val seekBarLoc = IntArray(2)
                seekBar.getLocationOnScreen(seekBarLoc)
                val touchOnSeekBar = ev.rawX >= seekBarLoc[0] && ev.rawX <= seekBarLoc[0] + seekBar.width &&
                        ev.rawY >= seekBarLoc[1] && ev.rawY <= seekBarLoc[1] + seekBar.height

                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        if (!touchOnLeftEdge && !touchOnSeekBar) {
                            playAreaSwipe.startTracking(ev.rawX, ev.rawY)
                        }
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (playAreaSwipe.isTracking && !playAreaSwipe.isSwipeUp && !playAreaSwipe.isSwipeRight) {
                            val dx = ev.rawX - playAreaSwipe.startX
                            val dy = ev.rawY - playAreaSwipe.startY
                            val slop = ViewConfiguration.get(this).scaledTouchSlop * 1.5f
                            if (abs(dy) > slop && abs(dy) > abs(dx) * 0.7f && dy < 0) {
                                playAreaSwipe.isSwipeUp = true
                            } else if (dx > slop && abs(dx) > abs(dy) * 0.7f) {
                                playAreaSwipe.isSwipeRight = true
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (playAreaSwipe.isTracking) {
                            val threshold = resources.getDimension(R.dimen.swipe_distance_threshold)
                            if (playAreaSwipe.isSwipeUp) {
                                val dy = ev.rawY - playAreaSwipe.startY
                                if (dy < -threshold) {
                                    openQueuePage()
                                }
                            } else if (playAreaSwipe.isSwipeRight) {
                                val dx = ev.rawX - playAreaSwipe.startX
                                if (dx > threshold) {
                                    openAlbumDetailPage()
                                }
                            }
                            playAreaSwipe.stopTracking()
                        }
                    }
                }
            } else {
                playAreaSwipe.isTracking = false
            }
        }
        if (unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.LYRIC &&
            (!unifiedContainer.isTransitioning || lyricSwipe.dragStarted)) {
            // 歌词页横向滑动返回播放界面（跟手）
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    lyricSwipe.startTracking(ev.rawX, ev.rawY)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (lyricSwipe.isTracking && !lyricSwipe.isActive) {
                        val dx = ev.rawX - lyricSwipe.startX
                        val dy = ev.rawY - lyricSwipe.startY
                        val slop = ViewConfiguration.get(this).scaledTouchSlop * 1.5f
                        if (abs(dx) > slop && abs(dx) > abs(dy) * 0.7f) {
                            lyricSwipe.isActive = true
                            // 开始跟手拖拽
                            binding.playBgView.syncFrom(binding.lyricBgView)
                            binding.playBgView.resumeAnimations()
                            unifiedContainer.startCoverSwipeUpDrag()
                            lyricSwipe.dragStarted = true
                        }
                    }
                    if (lyricSwipe.isActive && lyricSwipe.dragStarted) {
                        val dx = ev.rawX - lyricSwipe.startX
                        val ratio = (abs(dx) / lyricHSwipeThreshold).coerceIn(0f, 1f)
                        unifiedContainer.updateCoverSwipeUpDrag(ratio)
                        return true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (lyricSwipe.isTracking && lyricSwipe.isActive && lyricSwipe.dragStarted) {
                        val dx = ev.rawX - lyricSwipe.startX
                        val ratio = (abs(dx) / lyricHSwipeThreshold).coerceIn(0f, 1f)
                        val shouldOpen = ratio > 0.4f
                        unifiedContainer.endCoverSwipeUpDrag(shouldOpen)
                        lyricSwipe.stopTracking()
                        return true
                    }
                    lyricSwipe.stopTracking()
                }
            }
            // 歌词页底部区域下滑打开队列（仅在横向滑动未激活时）
            val screenHeight = resources.displayMetrics.heightPixels
            val bottomZone = screenHeight * 0.66f
            if (ev.rawY > bottomZone && !lyricSwipe.isActive && !lyricSwipe.dragStarted) {
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        playAreaSwipe.startTracking(ev.rawX, ev.rawY)
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (playAreaSwipe.isTracking) {
                            val dy = ev.rawY - playAreaSwipe.startY
                            val dx = ev.rawX - playAreaSwipe.startX
                            val slop = ViewConfiguration.get(this).scaledTouchSlop * 1.5f
                            if (dy > slop && abs(dy) > abs(dx) * 0.7f) {
                                playAreaSwipe.isSwipeUp = true
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (playAreaSwipe.isTracking && playAreaSwipe.isSwipeUp) {
                            val dy = ev.rawY - playAreaSwipe.startY
                            val threshold = resources.getDimension(R.dimen.swipe_distance_threshold)
                            if (dy > threshold) {
                                openQueuePage()
                            }
                        }
                        playAreaSwipe.stopTracking()
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private var hasRestoredScene = false

    private val playbackStatsHelper by lazy {
        PlaybackStatsHelper(this) { playerController }
    }

    override fun onResume() {
        super.onResume()
        if (!::unifiedContainer.isInitialized) return

        playbackStatsHelper.start()

        unifiedContainer.resetInteractionState()

        unifiedContainer.refreshImmersiveState(com.rawsmusic.module.data.prefs.AppPreferences.UI.isImmersiveEnabled)
        unifiedContainer.updateMiniCoverEnabled(com.rawsmusic.module.data.prefs.AppPreferences.UI.isMiniCoverEnabled)

        // 保存当前是否在 Fragment 模式（设置/关于等页面），防止 setupSceneParams 重置后丢失
        val wasInFragmentMode = binding.navHostFragment.visibility == View.VISIBLE
                || com.rawsmusic.module.data.prefs.AppPreferences.UI.wasInFragmentMode
        val currentScene = unifiedContainer.currentScene

        // 只在非 PLAYER/LYRIC 场景下才重新设置场景参数，避免从设置返回时重置播放器视图
        if (currentScene != UnifiedPlayerContainer.Scene.PLAYER && currentScene != UnifiedPlayerContainer.Scene.LYRIC) {
            setupSceneParams()
            unifiedContainer.forceReapplyCurrentScene()

            // 如果之前在 Fragment 模式，恢复正确的模式，避免设置/关于页面被重置回主界面
            if (wasInFragmentMode) {
                switchToFragmentMode()
            }
        } else {
            // PLAYER/LYRIC 场景下，只刷新封面布局，不重置场景参数
            setupCoverLayoutParams()
        }

        playerController?.currentSong?.value?.let { song ->
            val coverUri = coverUriResolver.resolveCoverUri(song)
            if (coverUri.isNotBlank()) {
                loadCoverBackground(coverUri)
            }
        }

        if (!hasRestoredScene && com.rawsmusic.module.data.prefs.AppPreferences.UI.isPlayPageMemoryEnabled) {
            hasRestoredScene = true
            val savedScene = com.rawsmusic.module.data.prefs.AppPreferences.UI.lastScene
            val currentScene = unifiedContainer.currentScene
            if (currentScene == UnifiedPlayerContainer.Scene.MAIN && savedScene != "MAIN") {
                val song = playerController?.currentSong?.value
                if (song != null) {
                    val targetScene = try {
                        UnifiedPlayerContainer.Scene.valueOf(savedScene)
                    } catch (_: Exception) {
                        null
                    }
                    if (targetScene != null) {
                        unifiedContainer.post {
                            loadedCoverImageWidth = 0
                            loadedCoverImageHeight = 0
                            val isImmersive = unifiedContainer.isImmersiveEnabled
                            val coverUri = coverUriResolver.resolveCoverUri(song)
                            val playCoverUri = coverUri.ifBlank { song.albumArtPath ?: "" }
                            val hasCover = playCoverUri.isNotBlank()
                            if (hasCover && !isImmersive) {
                                BitmapProvider.load(
                                    key = playCoverUri,
                                    imageView = null,
                                    targetWidth = 1080,
                                    targetHeight = 1080,
                                    callback = { bitmap ->
                                        if (bitmap != null && !bitmap.isRecycled) {
                                            loadedCoverImageWidth = bitmap.width
                                            loadedCoverImageHeight = bitmap.height
                                        }
                                        setupCoverLayoutParams()
                                        if (bitmap != null) {
                                            playCoverView.setCoverBitmap(bitmap)
                                        }
                                        binding.ivPlayCover.visibility = View.VISIBLE
                                        unifiedContainer.post {
                                            setupCoverLayoutParams()
                                            unifiedContainer.switchToSceneSilent(targetScene)
                                            if (targetScene == UnifiedPlayerContainer.Scene.LYRIC) {
                                                binding.ivPlayCover.pivotX = 0f
                                                binding.ivPlayCover.pivotY = 0f
                                                registerCoverLyricParams()
                                            } else {
                                                registerCoverCollapseParams()
                                            }
                                            unifiedContainer.forceReapplyCurrentScene()
                                            setupCoverLayoutParams()
                                            updateHiresBadge()
                                        }
                                    }
                                )
                            } else {
                                unifiedContainer.switchToSceneSilent(targetScene)
                                if (targetScene == UnifiedPlayerContainer.Scene.LYRIC) {
                                    registerCoverLyricParams()
                                } else {
                                    registerCoverCollapseParams()
                                }
                                unifiedContainer.forceReapplyCurrentScene()
                                setupCoverLayoutParams()
                                updateHiresBadge()
                            }
                        }
                    }
                }
            }
        }

        unifiedContainer.post {
            setupCoverLayoutParams()
            updateHiresBadge()

            val currentScene = unifiedContainer.currentScene
            if (currentScene == UnifiedPlayerContainer.Scene.LYRIC) {
                binding.ivPlayCover.pivotX = 0f
                binding.ivPlayCover.pivotY = 0f
                registerCoverLyricParams()
                unifiedContainer.forceReapplyCurrentScene()
            }
        }

        val song = playerController?.currentSong?.value

        if (LyriconProviderManager.isEnabled() && LyriconProviderManager.isConnected()) {
            val currentSong = playerController?.currentSong?.value
            val isPlaying = playerController?.playState?.value == PlayState.PLAYING
            LyriconProviderManager.setSong(currentSong, if (currentLyricData.isEmpty) null else currentLyricData)
            LyriconProviderManager.setPlaybackState(isPlaying)
        }

        // 后台恢复：确保 WakeLock 持有
        playerController?.onAppForegroundResumed()

        // USB 独占模式播放中，跳过 USB 重新扫描
        if (playerController?.playState?.value == PlayState.PLAYING &&
            playerController?.isUsbExclusiveActive() == true) {
            return
        }

        // 延迟处理 USB 权限，确保Activity 完全显示
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val usbMgr = playerController?.usbExclusiveManager ?: return@postDelayed
            val device = usbMgr.findUsbAudioDevice()
            if (device != null) {
                usbMgr.requestPermissionSafely(device)
            }
        }, 500)
    }

    // ==================== Compose 播放控制方法 ====================

    /**
     * Compose 版本：切换播放/暂停
     */
    fun composeTogglePlayPause() {
        playerController?.playPause()
    }

    /**
     * Compose 版本：播放上一首
     */
    fun composePlayPrevious() {
        playerController?.previous()
    }

    /**
     * Compose 版本：播放下一首
     */
    fun composePlayNext() {
        playerController?.next()
    }

    /**
     * Compose 版本：跳转到指定进度
     * @param progress 0..1 的进度值
     */
    fun composeSeekTo(progress: Float) {
        val durationMs = playerController?.duration?.value ?: 0L
        if (durationMs > 0) {
            val targetMs = (progress * durationMs).toLong()
            playerController?.seekTo(targetMs)
        }
    }

    /**
     * Compose 版本：切换重复模式
     */
    fun composeToggleRepeatMode() {
        playerController?.toggleRepeatMode()
    }

    /**
     * Compose 版本：切换随机播放
     */
    fun composeToggleShuffle() {
        playerController?.toggleShuffle()
    }

    /**
     * Compose 版本：同步播放状态到 Compose
     * 在 onResume 或播放状态变化时调用
     */
    fun syncPlayStateToCompose() {
        playerController?.let { controller ->
            composeIsPlaying = controller.playState.value == PlayState.PLAYING
            composeTotalDurationMs = controller.duration.value ?: 0L
            composeCurrentPositionMs = controller.position.value ?: 0L
            composePlayMode = controller.playMode.value ?: PlayMode.SEQUENTIAL

            val duration = composeTotalDurationMs
            if (duration > 0) {
                composePlayProgress = composeCurrentPositionMs.toFloat() / duration
            } else {
                composePlayProgress = 0f
            }
        }
    }

    /**
     * Compose 版本：启动播放进度同步协程
     * 在 Composable 的 LaunchedEffect 中调用
     */
    fun startComposeProgressSync(scope: kotlinx.coroutines.CoroutineScope) {
        scope.launch {
            while (true) {
                syncPlayStateToCompose()
                delay(100) // 每 100ms 更新一次
            }
        }
    }

    override fun onDestroy() {
        val finishing = isFinishing
        val changingConfig = isChangingConfigurations
        AppLogger.w("SceneTransition", "=== MainActivity.onDestroy CALLED, isFinishing=$finishing, isChangingConfigurations=$changingConfig ===")
        super.onDestroy()
        playbackStatsHelper.stop()
        try { unregisterReceiver(settingsChangeReceiver) } catch (_: Exception) {}
        LyriconProviderManager.stopPositionSync()
        LyriconProviderManager.destroy()
        TickerBridge.destroy(this)
        LyricGetterBridge.destroy()
        BluetoothLyricBridge.destroy()
        if (finishing) {
            playerController?.release()
            playerController = null
            PlayerHolder.controller = null
        } else {
            AppLogger.w("SceneTransition", "=== onDestroy: NOT finishing, keeping PlayerController alive ===")
        }
    }

}
