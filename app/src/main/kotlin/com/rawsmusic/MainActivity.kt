package com.rawsmusic

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.Toast
import io.github.proify.lyricon.lyric.view.LyricPlayerView
import io.github.proify.lyricon.lyric.view.RichLyricLineConfig
import io.github.proify.lyricon.lyric.view.PlaceholderFormat
import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.viewModels
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import coil.Coil
import coil.load
import com.rawsmusic.core.ui.util.SquarePadTransformation
import com.rawsmusic.core.common.base.BaseActivity
import com.rawsmusic.core.common.ext.isDarkMode
import com.rawsmusic.core.common.ext.visible
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.toLyriconSong
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.common.utils.UiUtils
import com.rawsmusic.core.ui.R as UiR
import com.rawsmusic.core.ui.adapter.SongAdapter
import com.rawsmusic.core.ui.animation.ButtonAnimHelper
import com.rawsmusic.core.ui.theme.CoverColorExtractor
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.AnimatedMoreButton
import com.rawsmusic.core.ui.widget.CoverGradientDrawable
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.rawsmusic.core.ui.widget.MetadataCardView
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
import com.rawsmusic.module.scanner.LyricReader
import com.rawsmusic.module.scanner.MediaStoreScanner
import com.rawsmusic.module.scanner.ScanProgress
import com.rawsmusic.ui.songs.PlayerHolder
import com.rawsmusic.ui.songs.SongsFragment
import com.rawsmusic.ui.widget.CapsuleProgressSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs

class MainActivity : BaseActivity<ActivityMainBinding>() {

    override val bindingInflater = { ActivityMainBinding.inflate(layoutInflater) }

    private val lyricPlayerView: LyricPlayerView get() = binding.lyricView as LyricPlayerView
    private val capsuleView: com.rawsmusic.ui.widget.RawSMusicCapsuleView get() = binding.miniPlayerBar as com.rawsmusic.ui.widget.RawSMusicCapsuleView
    private var currentLyricData: LyricData = LyricData()

    private lateinit var navController: NavController
    private lateinit var unifiedContainer: UnifiedPlayerContainer
    internal var playerController: PlayerController? = null
    private val globalSettingsVM: GlobalSettingsViewModel by viewModels()

    /** 是否正在拖动进度条 */
    private var isSeeking = false

    private var playAreaSwipeStartX = 0f
    private var playAreaSwipeStartY = 0f
    private var isPlayAreaSwipeUp = false
    private var isPlayAreaSwipeRight = false
    private var isPlayAreaTracking = false
    private val playAreaCoverLoc = IntArray(2)

    /** 是否手动拖拽侧边栏 */
    private var isManualDrawerDrag = false
    private var isSideMenuOpen = false
    private var isCoverAnimActive = false
    private var frozenContainerLoc: IntArray? = null

    /*观察播放器动作（通过 PlayerEventBus）*/
    private fun observePlayerActions() {
        lifecycleScope.launch {
            PlayerEventBus.events.collect { event ->
                when (event.action) {
                    PlayerService.ACTION_PLAY -> playerController?.resume()
                    PlayerService.ACTION_PAUSE -> playerController?.pause()
                    PlayerService.ACTION_NEXT -> playerController?.next()
                    PlayerService.ACTION_PREVIOUS -> playerController?.previous()
                    PlayerService.ACTION_STOP -> playerController?.stop()
                    "com.rawsmusic.action.SEEK" -> playerController?.seekTo(event.position)
                }
            }
        }
    }

    private var coverColors = CoverColorExtractor.CoverColors()
    private val lyricHeaderGradient = CoverGradientDrawable()

    private val settingsChangeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                "com.rawsmusic.action.IMMERSIVE_SETTING_CHANGED" -> {
                    if (::unifiedContainer.isInitialized) {
                        unifiedContainer.refreshImmersiveState(com.rawsmusic.module.data.prefs.AppPreferences.UI.isImmersiveEnabled)
                        playerController?.currentSong?.value?.let { song ->
                            val coverUri = resolveCoverUri(song)
                            val playCoverUri = coverUri.ifBlank { song.albumArtPath }
                            unifiedContainer.updateImmersiveCover(playCoverUri)
                        }
                        unifiedContainer.post {
                            setupCoverLayoutParams()
                            updateHiresBadge()
                        }
                    }
                }
                "com.rawsmusic.action.MINI_COVER_SETTING_CHANGED" -> {
                    if (::unifiedContainer.isInitialized) {
                        unifiedContainer.updateMiniCoverEnabled(com.rawsmusic.module.data.prefs.AppPreferences.UI.isMiniCoverEnabled)
                        playerController?.currentSong?.value?.let { song ->
                            val coverUri = resolveCoverUri(song)
                            val playCoverUri = coverUri.ifBlank { song.albumArtPath }
                            unifiedContainer.updateImmersiveCover(playCoverUri)
                        }
                        unifiedContainer.post {
                            setupCoverLayoutParams()
                            updateHiresBadge()
                        }
                    }
                }
            }
        }
    }

    /** 上次同步播放位置的时间*/
    private var lastSyncPositionTime = 0L

    /** 全屏封面查看器的缩放比例 */
    private var fullCoverScale = 1f
    private var fullCoverMinScale = 0.5f
    private var fullCoverMaxScale = 5f
    private val fullCoverScaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                fullCoverScale = (fullCoverScale * detector.scaleFactor).coerceIn(fullCoverMinScale, fullCoverMaxScale)
                binding.ivFullCover.scaleX = fullCoverScale
                binding.ivFullCover.scaleY = fullCoverScale
                return true
            }
        })
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startScan()
        } else {
            Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 初始化 PlayerController，如果当前没有 controller 则创建
        if (playerController == null) {
            playerController = PlayerController.getInstance(this)
            PlayerHolder.controller = playerController
        }
        ThemeManager.applyTheme(ThemeManager.getCurrentTheme())
        super.onCreate(savedInstanceState)
        FontManager.init(this)
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                FontManager.applyRecursive(binding.root)
            }
        })
        requestAudioPermission()

        val filter = android.content.IntentFilter().apply {
            addAction("com.rawsmusic.action.IMMERSIVE_SETTING_CHANGED")
            addAction("com.rawsmusic.action.MINI_COVER_SETTING_CHANGED")
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

    /**
     * 将实体音量键映射到USB DAC 硬件音量控制
     * 当USB 设备已连接且支持硬件音量时，直接硬件控制硬件音量，防止系统干扰   */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val ctrl = playerController
        if (ctrl?.isUsbExclusiveActive() == true && ctrl.canControlUsbVolume()) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    ctrl.stepUsbVolume(+0.04f)
                    showUsbVolumeToast(ctrl)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    ctrl.stepUsbVolume(-0.04f)
                    showUsbVolumeToast(ctrl)
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_MUTE -> {
                    ctrl.setUsbVolumeLinear(0f)
                    showUsbVolumeToast(ctrl)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private var usbVolumeOverlay: android.widget.TextView? = null
    private var usbVolumeHideRunnable: Runnable? = null
    private fun showUsbVolumeToast(ctrl: PlayerController) {
        val db = ctrl.getUsbVolumeDb()
        val text = "DAC 音量: %.1f dB".format(db)
        val decor = window.decorView as? android.view.ViewGroup ?: return

        // 移除旧的
        usbVolumeHideRunnable?.let { decor.removeCallbacks(it) }
        usbVolumeOverlay?.let { decor.removeView(it) }

        val dp = resources.displayMetrics.density
        val tv = android.widget.TextView(this).apply {
            this.text = text
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xCC000000.toInt())
                cornerRadius = 20 * dp
            }
        }
        val lp = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = (100 * dp).toInt()
        }
        decor.addView(tv, lp)
        usbVolumeOverlay = tv

        val hide = Runnable {
            tv.animate().alpha(0f).setDuration(200).withEndAction {
                decor.removeView(tv)
                if (usbVolumeOverlay === tv) usbVolumeOverlay = null
            }.start()
        }
        usbVolumeHideRunnable = hide
        decor.postDelayed(hide, 1500)
    }

    private fun adjustLayoutForOrientation(orientation: Int) {
        val isLandscape = orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val density = resources.displayMetrics.density
        val containerWidth = unifiedContainer.width
        if (containerWidth == 0) return

        if (isLandscape) {
            setupCoverLayoutParams()

            binding.lyricContentContainer.setPadding(0, (40 * density).toInt(), 0, (60 * density).toInt())
        } else {
            setupCoverLayoutParams()

            val titleParams = binding.playTitleGroup.layoutParams as android.widget.FrameLayout.LayoutParams
            titleParams.gravity = android.view.Gravity.START or android.view.Gravity.TOP
            titleParams.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            binding.playTitleGroup.layoutParams = titleParams

            binding.lyricContentContainer.setPadding(0, (140 * density).toInt(), 0, (120 * density).toInt())
        }
    }

    override fun initView() {
        // 在onCreate 中初始化 PlayerController 相关组件
        if (playerController == null) {
            playerController = PlayerController.getInstance(this)
        }
        PlayerHolder.controller = playerController

        restoreLastPlayingState()

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        setupUnifiedContainer()
        setupDrawerLayout()
        setupSideMenu()
        setupMiniPlayerListeners()
        CapsuleProgressSync.start(playerController, capsuleView)
        setupPlayPageListeners()
        setupLyricPageListeners()
        setupMetadataListeners()
        setupEdgeToEdge()

        // 绑定PlayerService服务连接
        startPlayerService()

        // 使用StateFlow和SharedFlow来管理状态       observePlayerActions()

        LyriconProviderManager.init(this, R.mipmap.ic_launcher)
        playerController?.let { LyriconProviderManager.startPositionSync(it) }
    }

    /**
     * DDrawerLayout 侧边栏设置...HOME场景时允许滑动手动打开侧边栏...深层页面时禁用侧边栏...    */
    private fun setupDrawerLayout() {
        val screenWidth = resources.displayMetrics.widthPixels
        val menuWidth = (screenWidth * 0.7).toInt()
        val params = binding.sideMenu.layoutParams
        params.width = menuWidth
        binding.sideMenu.layoutParams = params

        binding.sideMenu.post {
            binding.sideMenu.translationX = -binding.sideMenu.width.toFloat()
        }

        unifiedContainer.setOnClickListener {
            if (isSideMenuOpen) {
                closeSideMenu()
            }
        }
    }

    private fun openSideMenu() {
        if (isSideMenuOpen) return
        isSideMenuOpen = true
        val currentOffset = if (binding.sideMenu.width > 0) {
            (unifiedContainer.translationX / binding.sideMenu.width).coerceIn(0f, 1f)
        } else 0f
        animateDrawerToOffset(currentOffset, 1f, 250L) {
            binding.sideMenu.open()
            applyDrawerColorSync(true)
        }
    }

    private fun closeSideMenu() {
        if (!isSideMenuOpen && !isManualDrawerDrag) return
        val menuWidth = binding.sideMenu.width
        val currentOffset = if (menuWidth > 0) {
            (unifiedContainer.translationX / menuWidth).coerceIn(0f, 1f)
        } else 0f
        animateDrawerToOffset(currentOffset, 0f, 200L) {
            isSideMenuOpen = false
            isManualDrawerDrag = false
            unifiedContainer.translationX = 0f
            binding.sideMenu.translationX = -menuWidth.toFloat()
            binding.sideMenu.close()
            applyDrawerColorSync(false)
        }
    }

    private fun setupSideMenu() {
        val menuGroups = listOf(
            "音乐" to listOf(
                SideMenuView.MenuItem(R.id.nav_songs, "歌曲", R.drawable.ic_music_note_dark),
                SideMenuView.MenuItem(R.id.nav_albums, "专辑", R.drawable.ic_album),
                SideMenuView.MenuItem(R.id.nav_artists, "艺术家", R.drawable.ic_person),
                SideMenuView.MenuItem(R.id.nav_song_stats, "歌曲统计", UiR.drawable.ic_bar_chart)
            ),
            "系统" to listOf(
                SideMenuView.MenuItem(R.id.nav_settings, "设置", R.drawable.ic_settings),
                SideMenuView.MenuItem(R.id.nav_about, "关于", UiR.drawable.ic_info),
                SideMenuView.MenuItem(R.id.nav_qq_group, "QQ群", UiR.drawable.ic_info)
            )
        )
        binding.sideMenu.setMenuItems(menuGroups, R.id.nav_songs)

        binding.sideMenu.onMenuItemClick = { itemId ->
            if (itemId == R.id.nav_qq_group) {
                // 弹出白色弹窗显示QQ群号
                AlertDialog.Builder(this)
                    .setTitle("QQ群号")
                    .setMessage("QQ群号1093312333，欢迎大家进群讨论。")
                    .setPositiveButton("确定", null)
                    .show()
            } else {
                try {
                    navController.navigate(itemId)
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
                R.id.nav_settings -> R.id.nav_settings
                R.id.nav_about -> R.id.nav_about
                else -> null
            }
            if (menuId != null) binding.sideMenu.setSelectedMenuId(menuId)
            updateDrawerLockMode()
        }
    }

    private fun updateDrawerLockMode() {
        if (!::unifiedContainer.isInitialized) return
        val isHomeLevel = unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.MAIN
        val isDeepPage = navController.previousBackStackEntry != null
        unifiedContainer.isDeepHomePage = isHomeLevel && isDeepPage
        unifiedContainer.disableDeepPageSwipe = isHomeLevel && isDeepPage

        if (isHomeLevel) {
            val distance = 100f * resources.displayMetrics.density
            binding.miniPlayerBar.animate().cancel()
            val isAboutPage = navController.currentDestination?.id == R.id.nav_about
            if (isAboutPage) {
                capsuleView.hide()
            } else if (isDeepPage) {
                // 非主界面（二级页面）：折叠迷你播放器和进度条，仅显示导航栏
                capsuleView.show()
                capsuleView.fold()
            } else {
                capsuleView.show()
                capsuleView.unfold()
                binding.miniPlayerBar.visibility = View.VISIBLE
                binding.miniPlayerBar.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun setupUnifiedContainer() {
        unifiedContainer = binding.unifiedContainer

        unifiedContainer.initImmersiveViews(
            immersiveBg = binding.immersiveBackground!!,
            coverImg = binding.ivPlayCover,
            playScrim = binding.playBgScrim,
            miniCover = binding.mainPersistentCover,
            isImmersiveEnabled = com.rawsmusic.module.data.prefs.AppPreferences.UI.isImmersiveEnabled,
            isMiniCoverEnabled = com.rawsmusic.module.data.prefs.AppPreferences.UI.isMiniCoverEnabled
        )

        binding.miniPlayerBar.post {
            binding.immersiveBackground?.bottomPaddingHeight = binding.miniPlayerBar.height.toFloat()
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

        setupSceneParams()

        unifiedContainer.applyImmersiveSceneParams()

        // 设置 playLayer 和UnifiedPlayerContainer...使用 post 确保布局完成
        unifiedContainer.post { setupCoverLayoutParams() }

        binding.playBgView.setDimAmount(0f)

        // 沉浸模式...
        binding.lyricBgView.setDimAmount(0f)

        // 非沉浸模式...   binding.backgroundView.setDimAmount(0f)

        unifiedContainer.onSceneChanged = { newScene, oldScene ->
            android.util.Log.d("SceneTransition", "onSceneChanged: $oldScene -> $newScene")
            updateStatusBarForLevel(newScene)
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
                    restoreDefaultSceneParams()
                    // 沉浸模式下确保 ivPlayCover 隐藏（防止动画残留）
                    if (unifiedContainer.isImmersiveEnabled) {
                        binding.ivPlayCover.visibility = View.INVISIBLE
                        binding.ivPlayCover.alpha = 0f
                    }
                    try { navController.popBackStack(R.id.nav_songs, false) } catch (_: Exception) {}
                    getListCoverView()?.visibility = View.VISIBLE
                    // 延迟滚动到当前歌曲，确保 RecyclerView 已准备好
                    binding.root.post { scrollToCurrentSong() }
                    capsuleView.unfold()
                    binding.miniPlayerBar.visible()
                    binding.miniPlayerBar.alpha = 1f
                    playerController?.currentSong?.value?.let { song ->
                        capsuleView.updatePlaybackState(
                            playerController?.playState?.value == PlayState.PLAYING,
                            song.title,
                            song.artist,
                            song.albumArtPath
                        )
                    }
                    if (currentLyricText.isNotBlank()) {
                        val pos = playerController?.position?.value ?: 0L
                        val lineIdx = currentLyricData.findCurrentLine(pos)
                        val lineText = if (lineIdx >= 0) currentLyricData.getLine(lineIdx)?.text else null
                        val lineTranslation = if (lineIdx >= 0) currentLyricData.getLine(lineIdx)?.translation else null
                        capsuleView.updateLyric(lineText, lineTranslation, !lineTranslation.isNullOrBlank())
                    }
                    binding.miniPlayerBar.invalidate()
                    updateDrawerLockMode()
                    binding.playBgView.visibility = View.GONE
                    binding.playBgScrim?.visibility = View.GONE
                    binding.playBgScrim?.alpha = 0f
                    binding.backgroundView.alpha = 1f
                    binding.backgroundView.visibility = View.VISIBLE
                    binding.mainBgScrim?.alpha = 1f
                    binding.mainBgScrim?.visibility = View.VISIBLE
                    binding.playBottomPanel?.visibility = View.GONE
                    binding.playTitleGroup.visibility = View.GONE
                    binding.playTitleGroup.alpha = 0f
                    binding.btnPlayMode.visibility = View.GONE
                    binding.ivHiresSmall.visibility = View.GONE
                    binding.ivHiresSmall.alpha = 0f
                    binding.playMetadataCard.collapse()
                    binding.lyricMetadataCard.collapse()
                    isCoverAnimActive = false
                    frozenContainerLoc = null
                    binding.lyricContentContainer.visibility = View.GONE
                    binding.lyricContentContainer.alpha = 0f
                    binding.miniPlayerBar.post {
                        binding.miniPlayerBar.alpha = 1f
                        binding.miniPlayerBar.visible()
                    }
                }
                UnifiedPlayerContainer.Scene.PLAYER -> {
                    unifiedContainer.syncRotationState(unifiedContainer.isCurrentlyPlaying)
                    binding.ivPlayCover.pivotX = binding.ivPlayCover.width / 2f
                    binding.ivPlayCover.pivotY = binding.ivPlayCover.height / 2f
                    binding.playTitleGroup.pivotX = binding.playTitleGroup.width / 2f
                    binding.playTitleGroup.pivotY = binding.playTitleGroup.height / 2f
                    val density = resources.displayMetrics.density
                    val isImmersive = unifiedContainer.isImmersiveEnabled
                    unifiedContainer.registerSceneParams(
                        R.id.ivPlayCover,
                        UnifiedPlayerContainer.Scene.PLAYER,
                        UnifiedPlayerContainer.SceneParams(
                            scene = UnifiedPlayerContainer.Scene.PLAYER,
                            alpha = if (isImmersive) 0f else 1f,
                            visibility = if (isImmersive) View.INVISIBLE else View.VISIBLE,
                            translationX = 0f,
                            translationY = 0f,
                            scaleX = 1f,
                            scaleY = 1f,
                            cornerRadius = 12f * density
                        )
                    )
                    capsuleView.fold()
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
                    isCoverAnimActive = false
                    frozenContainerLoc = null
                    val isLandscapePlayer = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    if (isLandscapePlayer) {
                        binding.lyricContentContainer.setPadding(0, (40 * density).toInt(), 0, (60 * density).toInt())
                    } else {
                        binding.lyricContentContainer.setPadding(0, (140 * density).toInt(), 0, (120 * density).toInt())
                    }
                    setupCoverLayoutParams()
                    binding.playBgView.resumeAnimations()
                    binding.queuePageContainer?.visibility = View.GONE
                    binding.albumDetailContainer?.visibility = View.GONE
                    binding.mainBgScrim?.visibility = View.GONE
                    binding.mainBgScrim?.alpha = 0f
                }
                UnifiedPlayerContainer.Scene.LYRIC -> {
                    capsuleView.fold()
                    val density = resources.displayMetrics.density
                    val coverBottom = getCoverBottomInContainer()
                    val lyricTopPad = (coverBottom + 16 * density).toInt()
                    val lyricBottomPad = (56 * density).toInt()

                    val lyricLp = binding.lyricContentContainer.layoutParams as android.widget.FrameLayout.LayoutParams
                    lyricLp.topMargin = lyricTopPad
                    lyricLp.bottomMargin = lyricBottomPad
                    lyricLp.gravity = android.view.Gravity.TOP
                    binding.lyricContentContainer.layoutParams = lyricLp
                    binding.lyricContentContainer.setPadding(0, 0, 0, 0)

                    binding.lyricControls.visibility = View.VISIBLE
                    binding.btnMoreLyric.visibility = View.GONE
                    binding.lyricMainLayer.visibility = View.GONE
                    binding.playTitleGroup.bringToFront()
                    binding.playTitleGroup.pivotX = binding.playTitleGroup.width / 2f
                    binding.playTitleGroup.pivotY = binding.playTitleGroup.height / 2f
                    updateLyricAnchor()

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
                            val displayTrans = com.rawsmusic.module.data.prefs.AppPreferences.Lyricon.displayTranslation
                            lyricPlayerView.updateDisplayTranslation(
                                displayTranslation = displayTrans,
                                displayRoma = displayTrans
                            )
                        }
                    }

                    binding.lyricBgView.resumeAnimations()
                    binding.playBgScrim?.visibility = View.GONE
                    binding.playBgScrim?.alpha = 0f
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
            }
        }

        // 根据当前PLAY/LYRIC 场景更新参数
        unifiedContainer.onLeftEdgeSwipe = {
            if (unifiedContainer.currentScene != UnifiedPlayerContainer.Scene.MAIN) {
                navController.navigateUp()
            }
        }

        // 返回上一级，使用 navigateUp() 返回主界面
        unifiedContainer.onSwipeBack = {
            navController.navigateUp()
        }

        unifiedContainer.onPlayerSwipeToMain = {
            unifiedContainer.closePlayPageWithCoverAlign(true)
        }

        unifiedContainer.onPreparePlayerToMain = {
            binding.ivHiresSmall.visibility = View.GONE
            binding.ivHiresSmall.alpha = 0f
            getListCoverView()?.visibility = View.INVISIBLE
            registerCoverCollapseParams()
        }

        unifiedContainer.onPreparePlayerToLyric = {
            binding.ivHiresSmall.visibility = View.GONE
            binding.ivHiresSmall.alpha = 0f
            binding.ivPlayCover.pivotX = 0f
            binding.ivPlayCover.pivotY = 0f
            binding.lyricBgView.syncFrom(binding.playBgView)
            binding.lyricBgView.resumeAnimations()
            registerCoverLyricParams()
        }

        unifiedContainer.onPrepareMainToPlayer = {
            binding.ivPlayCover.apply {
                visibility = View.VISIBLE
                alpha = 1f
                scaleX = 1f
                scaleY = 1f
                translationX = 0f
                translationY = 0f
                pivotX = width / 2f
                pivotY = height / 2f
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(12f * resources.displayMetrics.density)
                    .build()
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
        if (isCoverAnimActive) return
        val density = resources.displayMetrics.density
        val containerWidth = unifiedContainer.width
        val containerHeight = unifiedContainer.height
        if (containerWidth == 0) return

        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            val coverSize = minOf(
                (containerWidth * 0.35f).toInt(),
                (containerHeight - 48 * density).toInt()
            )
            val coverLeft = ((containerWidth * 0.5f - coverSize) / 2f).toInt()
            val coverTop = ((containerHeight - coverSize) / 2f).toInt()

            val coverParams = android.widget.FrameLayout.LayoutParams(coverSize, coverSize)
            coverParams.marginStart = coverLeft
            coverParams.topMargin = coverTop
            binding.ivPlayCover.layoutParams = coverParams

            val hiresWidth = (48 * density).toInt()
            val hiresHeight = (24 * density).toInt()
            val hiresParams = android.widget.FrameLayout.LayoutParams(hiresWidth, hiresHeight)
            val isImmersive = unifiedContainer.isImmersiveEnabled
            if (isImmersive) {
                // 沉浸模式：封面占满屏幕宽度，高度为55%
                val splitY = (containerHeight * 0.55f).toInt()
                hiresParams.topMargin = splitY - hiresHeight - (8 * density).toInt()
                hiresParams.marginStart = containerWidth - hiresWidth - (8 * density).toInt()
            } else {
                hiresParams.topMargin = coverTop + coverSize - hiresHeight
                hiresParams.marginStart = coverLeft + coverSize - hiresWidth
            }
            binding.ivHiresSmall.layoutParams = hiresParams

            val playModeSize = (40 * density).toInt()
            val playModeParams = android.widget.FrameLayout.LayoutParams(playModeSize, playModeSize)
            if (isImmersive) {
                val splitY = (containerHeight * 0.55f).toInt()
                playModeParams.topMargin = splitY + (4 * density).toInt()
                playModeParams.marginStart = containerWidth - playModeSize - (8 * density).toInt()
            } else {
                playModeParams.topMargin = coverTop + coverSize + (4 * density).toInt()
                playModeParams.marginStart = coverLeft + coverSize - playModeSize - (4 * density).toInt()
            }
            binding.btnPlayMode.layoutParams = playModeParams

            val rightStart = (containerWidth * 0.5f).toInt() + (16 * density).toInt()
            val titleParams = binding.playTitleGroup.layoutParams as android.widget.FrameLayout.LayoutParams
            titleParams.width = containerWidth - rightStart - (16 * density).toInt()
            titleParams.topMargin = (28 * density).toInt()
            titleParams.marginStart = rightStart
            titleParams.marginEnd = (16 * density).toInt()
            binding.playTitleGroup.layoutParams = titleParams

            binding.playBottomPanel?.layoutParams = android.widget.FrameLayout.LayoutParams(
                (containerWidth * 0.5f - 32 * density).toInt(),
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
                marginStart = (containerWidth * 0.5f + 16 * density).toInt()
                marginEnd = (16 * density).toInt()
                bottomMargin = (16 * density).toInt()
            }
        } else {
            val horizontalPadding = (8 * density).toInt()
            val availableWidth = containerWidth - 2 * horizontalPadding
            val coverSize = (availableWidth * 0.93f).toInt()
            val coverMarginStart = horizontalPadding + (availableWidth - coverSize) / 2

            val params = android.widget.FrameLayout.LayoutParams(coverSize, coverSize)
            params.topMargin = (56 * density).toInt()
            params.marginStart = coverMarginStart
            binding.ivPlayCover.layoutParams = params
            binding.ivPlayCover.scaleType = ImageView.ScaleType.CENTER_CROP

            val hiresWidth = (48 * density).toInt()
            val hiresHeight = (24 * density).toInt()
            val hiresParams = android.widget.FrameLayout.LayoutParams(hiresWidth, hiresHeight)
            val isImmersive = unifiedContainer.isImmersiveEnabled
            if (isImmersive) {
                // 沉浸模式：封面占满屏幕宽度，高度为55%
                val splitY = (containerHeight * 0.55f).toInt()
                hiresParams.topMargin = splitY - hiresHeight - (8 * density).toInt()
                hiresParams.marginStart = containerWidth - hiresWidth - (8 * density).toInt()
            } else {
                hiresParams.topMargin = params.topMargin + coverSize - hiresHeight
                hiresParams.marginStart = coverMarginStart + coverSize - hiresWidth
            }
            binding.ivHiresSmall.layoutParams = hiresParams

            val playModeSize = (40 * density).toInt()
            val playModeParams = android.widget.FrameLayout.LayoutParams(playModeSize, playModeSize)
            if (isImmersive) {
                val splitY = (containerHeight * 0.55f).toInt()
                playModeParams.topMargin = splitY + (4 * density).toInt()
                playModeParams.marginStart = containerWidth - playModeSize - (8 * density).toInt()
            } else {
                playModeParams.topMargin = params.topMargin + coverSize + (4 * density).toInt()
                playModeParams.marginStart = coverMarginStart + coverSize - playModeSize - (4 * density).toInt()
            }
            binding.btnPlayMode.layoutParams = playModeParams

            val titleParams = binding.playTitleGroup.layoutParams as android.widget.FrameLayout.LayoutParams
            titleParams.width = android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            titleParams.topMargin = params.topMargin + coverSize + (12 * density).toInt()
            titleParams.marginStart = (20 * density).toInt()
            titleParams.marginEnd = (72 * density).toInt()
            binding.playTitleGroup.layoutParams = titleParams

            binding.playBottomPanel?.layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = android.view.Gravity.BOTTOM
                marginStart = (20 * density).toInt()
                marginEnd = (20 * density).toInt()
                bottomMargin = (56 * density).toInt()
            }
        }
    }

    /**
     */
    private fun setupSceneParams() {
        val density = resources.displayMetrics.density

        unifiedContainer.registerViewScenes(
            R.id.nav_host_fragment,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                translationY = -80f * density,
                scaleX = 0.92f,
                scaleY = 0.92f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        val isImmersive = unifiedContainer.isImmersiveEnabled
        unifiedContainer.registerViewScenes(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.INVISIBLE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = if (isImmersive) 0f else 1f,
                visibility = if (isImmersive) View.INVISIBLE else View.VISIBLE,
                translationX = 0f,
                translationY = 0f,
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = if (isImmersive) 0f else 1f,
                visibility = if (isImmersive) View.INVISIBLE else View.VISIBLE,
                scaleX = 0.3f,
                scaleY = 0.3f,
                translationX = 0f,
                translationY = 0f,
            )
        )

        unifiedContainer.registerViewScenes(
            R.id.lyricContentContainer,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 1f,
                visibility = View.VISIBLE
            )
        )

        unifiedContainer.registerViewScenes(
            R.id.btnPlayMode,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        unifiedContainer.registerViewScenes(
            R.id.ivHiresSmall,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        unifiedContainer.registerViewScenes(
            R.id.playTitleGroup,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE,
                scaleX = 1f,
                scaleY = 1f,
                translationX = 0f,
                translationY = 0f
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 1f,
                visibility = View.VISIBLE,
                scaleX = 0.98f,
                scaleY = 0.98f
            )
        )

        // ===== audioInfoCapsule =====
        unifiedContainer.registerViewScenes(
            R.id.audioInfoCapsule,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 1f,
                visibility = View.VISIBLE
            )
        )

        // ===== 沉浸式封面设置=====
        unifiedContainer.registerViewScenes(
            R.id.ivPlayCoverMirror,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        // ===== 场景过渡动画 =====
        // 非沉浸模式下隐藏 playBgView，避免与 ivPlayCover 同时显示专辑图
        unifiedContainer.registerViewScenes(
            R.id.playBgView,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = if (isImmersive) 1f else 0f,
                visibility = if (isImmersive) View.VISIBLE else View.INVISIBLE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = if (isImmersive) 1f else 0f,
                visibility = if (isImmersive) View.VISIBLE else View.INVISIBLE
            ),
            UnifiedPlayerContainer.Scene.QUEUE to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.QUEUE,
                alpha = if (isImmersive) 1f else 0f,
                visibility = if (isImmersive) View.VISIBLE else View.INVISIBLE
            ),
            UnifiedPlayerContainer.Scene.ALBUM_DETAIL to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.ALBUM_DETAIL,
                alpha = if (isImmersive) 1f else 0f,
                visibility = if (isImmersive) View.VISIBLE else View.INVISIBLE
            )
        )

        // ===== 播放页手势处理=====
        val scrimAlpha = if (isImmersive) 0f else 1f
        val scrimVisibility = if (isImmersive) View.INVISIBLE else View.VISIBLE
        unifiedContainer.registerViewScenes(
            R.id.playBgScrim,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = scrimAlpha,
                visibility = scrimVisibility
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = scrimAlpha,
                visibility = scrimVisibility
            ),
            UnifiedPlayerContainer.Scene.QUEUE to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.QUEUE,
                alpha = scrimAlpha,
                visibility = scrimVisibility
            ),
            UnifiedPlayerContainer.Scene.ALBUM_DETAIL to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.ALBUM_DETAIL,
                alpha = scrimAlpha,
                visibility = scrimVisibility
            )
        )

        unifiedContainer.registerViewScenes(
            R.id.playBottomPanel,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        // ===== 胶囊播放栏场景参数=====
        unifiedContainer.registerViewScenes(
            R.id.playMetadataCard,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        // ===== 导航栏主场景参数 =====
        unifiedContainer.registerViewScenes(
            R.id.miniPlayerBar,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                translationY = 0f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                translationY = 0f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 1f,
                visibility = View.VISIBLE
            )
        )

        // ===== 播放背景场景参数=====
        unifiedContainer.registerViewScenes(
            R.id.lyricBgView,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.QUEUE to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.QUEUE,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.ALBUM_DETAIL to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        unifiedContainer.registerViewScenes(
            R.id.lyricMainLayer,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        unifiedContainer.registerViewScenes(
            R.id.backgroundView,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.QUEUE to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.QUEUE,
                alpha = 0f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.ALBUM_DETAIL to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.VISIBLE
            )
        )

        // ===== 播放页背景设置=====
        unifiedContainer.registerViewScenes(
            R.id.mainBgScrim,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.QUEUE to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.QUEUE,
                alpha = 0f,
                visibility = View.VISIBLE
            ),
            UnifiedPlayerContainer.Scene.ALBUM_DETAIL to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.ALBUM_DETAIL,
                alpha = 0f,
                visibility = View.VISIBLE
            )
        )

        // ===== Lyric metadata card =====
        unifiedContainer.registerViewScenes(
            R.id.lyricMetadataCard,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        unifiedContainer.registerViewScenes(
            R.id.queuePageContainer,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN, alpha = 0f, visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER, alpha = 0f, visibility = View.VISIBLE, translationY = unifiedContainer.height.toFloat()
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC, alpha = 0f, visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.QUEUE to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.QUEUE, alpha = 1f, visibility = View.VISIBLE, translationY = 0f
            ),
            UnifiedPlayerContainer.Scene.ALBUM_DETAIL to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.ALBUM_DETAIL, alpha = 0f, visibility = View.GONE
            )
        )

        unifiedContainer.registerViewScenes(
            R.id.albumDetailContainer,
            UnifiedPlayerContainer.Scene.MAIN to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN, alpha = 0f, visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.PLAYER to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER, alpha = 0f, visibility = View.VISIBLE, translationX = unifiedContainer.width.toFloat()
            ),
            UnifiedPlayerContainer.Scene.LYRIC to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC, alpha = 0f, visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.QUEUE to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.QUEUE, alpha = 0f, visibility = View.GONE
            ),
            UnifiedPlayerContainer.Scene.ALBUM_DETAIL to UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.ALBUM_DETAIL, alpha = 1f, visibility = View.VISIBLE, translationX = 0f
            )
        )

        val playViewIds = listOf(
            R.id.ivPlayCover,
            R.id.playTitleGroup,
            R.id.playBottomPanel,
            R.id.btnPlayMode,
            R.id.audioInfoCapsule,
            R.id.playBgView,
            R.id.playBgScrim,
            R.id.ivPlayCoverMirror
        )

        val ch = unifiedContainer.height.toFloat()
        val cw = unifiedContainer.width.toFloat()
        for (vid in playViewIds) {
            unifiedContainer.registerSceneParams(vid, UnifiedPlayerContainer.Scene.QUEUE,
                UnifiedPlayerContainer.SceneParams(scene = UnifiedPlayerContainer.Scene.QUEUE, alpha = 0f, translationY = -ch, visibility = View.GONE))
            unifiedContainer.registerSceneParams(vid, UnifiedPlayerContainer.Scene.ALBUM_DETAIL,
                UnifiedPlayerContainer.SceneParams(scene = UnifiedPlayerContainer.Scene.ALBUM_DETAIL, alpha = 0f, translationX = cw, visibility = View.GONE))
        }

        unifiedContainer.applyImmersiveSceneParams()
    }

    private fun getCoverBottomInContainer(): Int {
        val density = resources.displayMetrics.density
        val containerWidth = unifiedContainer.width
        val containerHeight = unifiedContainer.height
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            val coverSize = minOf(
                (containerWidth * 0.35f).toInt(),
                (containerHeight - 48 * density).toInt()
            )
            val coverTop = ((containerHeight - coverSize) / 2f).toInt()
            val lyricScale = 0.3f
            return (coverTop + coverSize * lyricScale).toInt()
        }

        val coverHeight: Int
        if (containerWidth == 0) {
            coverHeight = (200 * density).toInt()
        } else {
            val hPad = 8f * density
            val availW = containerWidth - 2 * hPad
            coverHeight = (availW * 0.93f).toInt()
        }
        val coverTop = (56 * density).toInt()
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
        binding.ivPlayCover.animate().cancel()
        binding.ivHiresSmall.animate().cancel()
        val coverScale = if (isPlaying) 1.03f else 0.94f
        val coverEndScale = if (isPlaying) 1.0f else 0.97f
        if (isPlaying) {
            binding.ivPlayCover.scaleX = 1.0f
            binding.ivPlayCover.scaleY = 1.0f
            binding.ivHiresSmall.scaleX = 1.0f
            binding.ivHiresSmall.scaleY = 1.0f
        }
        binding.ivPlayCover.animate()
            .scaleX(coverScale)
            .scaleY(coverScale)
            .setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                binding.ivPlayCover.animate()
                    .scaleX(coverEndScale)
                    .scaleY(coverEndScale)
                    .setDuration(100)
                    .start()
            }
            .start()
        if (binding.ivHiresSmall.visibility == View.VISIBLE) {
            binding.ivHiresSmall.animate()
                .scaleX(coverScale)
                .scaleY(coverScale)
                .setDuration(150)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction {
                    binding.ivHiresSmall.animate()
                        .scaleX(coverEndScale)
                        .scaleY(coverEndScale)
                        .setDuration(100)
                        .start()
                }
                .start()
        }
    }

    private fun updateLyricAnchor() {
        val density = resources.displayMetrics.density
        val coverBottom = getCoverBottomInContainer()
        val lyricBottomPad = (56 * density).toInt()
        val availableHeight = unifiedContainer.height.toFloat() - coverBottom - lyricBottomPad
        val anchorOffset = availableHeight * 0.5f
        lyricPlayerView.updateAnchorOffset(anchorOffset)
    }

    override fun initObserver() {
        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.playState?.collect { state ->
                val isPlaying = state == PlayState.PLAYING
                val song = playerController?.currentSong?.value
                if (song != null) {
                    capsuleView.updatePlaybackState(isPlaying, song.title, song.artist, song.albumArtPath)
                }
                binding.btnPlayPause.setImageResource(
                    if (isPlaying) R.drawable.ic_pause
                    else R.drawable.ic_play
                )
                unifiedContainer.syncRotationState(isPlaying)
                unifiedContainer.isCurrentlyPlaying = isPlaying

        // 监听播放状态变化，更新胶囊播放栏的播放按钮状态
                if (isPlaying) {
                    binding.btnPlayPause.imageTintList = android.content.res.ColorStateList.valueOf(0xB0FFFFFF.toInt())
                } else {
                    binding.btnPlayPause.imageTintList = android.content.res.ColorStateList.valueOf(0xFFFFFFFF.toInt())
                }
                binding.btnPlayPause.alpha = 1f

                LyriconProviderManager.setPlaybackState(isPlaying)

                if (!isSeeking && !unifiedContainer.isTransitioning && unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.PLAYER) {
                    animateCoverBreathing(isPlaying)
                }

                if (isPlaying) {
                    binding.seekBar.stopBreathing()
                } else {
                    binding.seekBar.startBreathing()
                }

        // 监听歌曲变化，更新胶囊播放栏的歌曲信息
                playerController?.currentSong?.value?.let { song ->
                    pushUpdateToService(song)
                }
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.currentSong?.collect { song ->
                song?.let {
                    Log.d("MetaObserver", "song changed: ${it.title}, sr=${it.sampleRate}, br=${it.bitRate}, " +
                            "bps=${it.bitsPerSample}, ch=${it.channelCount}, isHiRes=${it.isHiRes}")

                    val coverUri = resolveCoverUri(it)
                    val playCoverUri = coverUri.ifBlank { it.albumArtPath }
                    capsuleView.updatePlaybackState(
                        playerController?.playState?.value == PlayState.PLAYING,
                        it.title,
                        it.artist,
                        playCoverUri
                    )
                    binding.tvTitle.text = it.title
                    binding.tvArtist.text = it.artist
                    binding.tvAlbum.text = it.album

                    if (unifiedContainer.currentScene != UnifiedPlayerContainer.Scene.MAIN) {
                        binding.ivPlayCover.animate().cancel()
                        // 沉浸模式下封面由 ImmersiveBackgroundView 渲染，ivPlayCover 保持隐藏
                        val isImmersive = unifiedContainer.isImmersiveEnabled
                        binding.ivPlayCover.alpha = if (isImmersive) 0f else 1f
                        if (isImmersive) {
                            binding.ivPlayCover.visibility = View.INVISIBLE
                        }
                        binding.ivPlayCover.translationX = 0f
                        binding.ivPlayCover.translationY = 0f
                        binding.ivPlayCover.rotationY = 0f
                        when (unifiedContainer.currentScene) {
                            UnifiedPlayerContainer.Scene.LYRIC -> {
                                binding.ivPlayCover.scaleX = 0.3f
                                binding.ivPlayCover.scaleY = 0.3f
                            }
                            else -> {
                                binding.ivPlayCover.scaleX = 1f
                                binding.ivPlayCover.scaleY = 1f
                            }
                        }
                        binding.ivPlayCover.load(playCoverUri) {
                            crossfade(true)
                            size(3000)
                            allowHardware(false)
                            transformations(SquarePadTransformation())
                        }
                    }
                    unifiedContainer.updateImmersiveCover(playCoverUri.ifBlank { null })
                    syncMirrorCover(playCoverUri.ifBlank { null })

                    loadLyrics(it.path)
                    updateCapsuleText()
                    updateHiresBadge()

                    loadCoverBackground(playCoverUri)

                    pushUpdateToService(it)

                    startMarqueeIfNeeded(binding.tvTitle)
                    startMarqueeIfNeeded(binding.tvArtist)
                    startMarqueeIfNeeded(binding.tvAlbum)
                }
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.usbOutputSampleRate?.collect { sr ->
                if (sr > 0) {
                    updateCapsuleText()
                }
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.position?.collect { pos ->
                val duration = playerController?.duration?.value ?: 0L
                binding.seekBar.setProgress(pos, duration)
                binding.tvCurrentTime.text = AudioUtils.formatDuration(pos)
                binding.tvTotalTime.text = AudioUtils.formatDuration(duration)
        // 更新歌词显示和胶囊播放栏
                val remaining = (duration - pos).coerceAtLeast(0L)
                // Update lyric position for auto-scroll
                lyricPlayerView.setPosition(pos)
                if (!currentLyricData.isEmpty) {
                    val lineIdx = currentLyricData.findCurrentLine(pos)
                    val lineText = if (lineIdx >= 0) currentLyricData.getLine(lineIdx)?.text else null
                    val lineTranslation = if (lineIdx >= 0) currentLyricData.getLine(lineIdx)?.translation else null
                    if (lineText != currentLyricText) {
                        currentLyricText = lineText ?: ""
                        if (audioCapsuleState == 4) updateCapsuleText()
                        val song = playerController?.currentSong?.value
                        val displayTitle = song?.title ?: ""
                        val displayArtist = song?.artist ?: ""
                        if (currentLyricText.isNotBlank()) {
                            capsuleView.updateLyric(currentLyricText, lineTranslation ?: "", !lineTranslation.isNullOrBlank())
                        } else {
                            capsuleView.updateLyric(null, null, false)
                        }
                    }
                }
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.position?.collect { pos ->
                val now = System.currentTimeMillis()
                if (now - lastSyncPositionTime >= 3000 && PlayerService.isRunning) {
                    lastSyncPositionTime = now
                    try {
                        val intent = Intent(this@MainActivity, PlayerService::class.java).apply {
                            action = "com.rawsmusic.action.SYNC_POSITION"
                            putExtra("position", pos)
                        }
                        startService(intent)
                    } catch (_: Exception) {}
                }
            }
        }

        lifecycleScope.launch(Dispatchers.Main) {
            playerController?.playMode?.collect { mode ->
                updatePlayModeIcon(mode)
            }
        }
    }

    /** 启动跑马灯效果（focusable + requestFocus） */
    private fun startMarqueeIfNeeded(tv: android.widget.TextView) {
        tv.isSelected = true
    }

    override fun initListener() {}

    private fun setupMiniPlayerListeners() {
        val capsule = binding.miniPlayerBar as? com.rawsmusic.ui.widget.RawSMusicCapsuleView ?: return
        capsule.onBarClick = {
            openPlayPageWithSharedElement()
        }
        capsule.onPlayPauseClick = {
            playerController?.playPause()
        }
        capsule.onSeekTo = { positionMs ->
            playerController?.seekTo(positionMs)
        }
        capsule.onNavClick = { navId ->
            handleCapsuleNavClick(navId)
        }
        capsule.onAlbumsClick = {
            try { navController.navigate(R.id.nav_albums) } catch (_: Exception) {}
        }
        capsule.onArtistsClick = {
            try { navController.navigate(R.id.nav_artists) } catch (_: Exception) {}
        }
        capsule.onHomeClick = {
            if (navController.currentDestination?.id != R.id.nav_songs) {
                try { navController.popBackStack(R.id.nav_songs, false) } catch (_: Exception) {}
            }
        }
        capsule.onAudioQualityClick = {
            try { navController.navigate(R.id.action_global_audio_settings) } catch (_: Exception) {}
        }
        capsule.onSoundEffectClick = {
            try { navController.navigate(R.id.nav_spatial_sound) } catch (_: Exception) {}
        }
        capsule.onUsbDacClick = {
            try { navController.navigate(R.id.nav_usb_dac_settings) } catch (_: Exception) {}
        }
        capsule.onSettingsClick = {
            try { navController.navigate(R.id.action_global_settings) } catch (_: Exception) {}
        }
        capsule.onAboutClick = {
            try { navController.navigate(R.id.action_global_about) } catch (_: Exception) {}
        }

    }

    private fun handleCapsuleNavClick(navId: Int) {
        if (!::navController.isInitialized) return
        when (navId) {
            R.id.nav_search -> {
                capsuleView.collapseAllCards()
                val currentId = navController.currentDestination?.id
                val searchablePages = listOf(R.id.nav_songs, R.id.nav_albums, R.id.nav_artists)
                if (currentId !in searchablePages) {
                    return
                }
                try {
                    val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                        ?.childFragmentManager?.fragments?.firstOrNull()
                    when (currentId) {
                        R.id.nav_songs -> (navHostFragment as? com.rawsmusic.ui.songs.SongsFragment)?.enterSearch()
                        R.id.nav_albums -> (navHostFragment as? com.rawsmusic.ui.albums.AlbumsFragment)?.enterSearch()
                        R.id.nav_artists -> (navHostFragment as? com.rawsmusic.ui.artists.ArtistsFragment)?.enterSearch()
                    }
                } catch (_: Exception) {}
            }
        }
    }

        // ==================== 胶囊播放栏设置 ====================

    /**
     */
    private fun getCurrentSongCoverRectWhenReady(onReady: (RectF?) -> Unit) {
        val recyclerView = findSongsRecyclerView()
        if (recyclerView == null) { onReady(null); return }
        val adapter = recyclerView.adapter as? SongAdapter
        if (adapter == null) { onReady(null); return }

        val targetPosition = adapter.currentPlayingPosition
        if (targetPosition < 0) { onReady(null); return }

        (recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
            ?.scrollToPositionWithOffset(targetPosition, recyclerView.height / 3)

        recyclerView.post {
            val coverView = getListCoverView()
            if (coverView == null) { onReady(null); return@post }
            val loc = IntArray(2)
            coverView.getLocationOnScreen(loc)
            onReady(RectF(
                loc[0].toFloat(), loc[1].toFloat(),
                loc[0] + coverView.width.toFloat(), loc[1] + coverView.height.toFloat()
            ))
        }
    }

    private fun findSongsRecyclerView(): androidx.recyclerview.widget.RecyclerView? {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
                as? androidx.navigation.fragment.NavHostFragment ?: return null
        val songsFragment = navHostFragment.childFragmentManager.fragments.firstOrNull()
                as? SongsFragment ?: return null
        return songsFragment.getRecyclerView()
    }

    private fun scrollToCurrentSong() {
        val recyclerView = findSongsRecyclerView() ?: return
        val adapter = recyclerView.adapter as? SongAdapter ?: return
        val pos = adapter.currentPlayingPosition
        if (pos < 0) return
        (recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
            ?.scrollToPositionWithOffset(pos, recyclerView.height / 3)
    }

    /**
     */
    private fun getListCoverView(): View? {
        val recyclerView = findSongsRecyclerView() ?: return null
        val adapter = recyclerView.adapter as? SongAdapter ?: return null
        val pos = adapter.currentPlayingPosition
        if (pos < 0) return null
        val viewHolder = recyclerView.findViewHolderForAdapterPosition(pos) ?: return null
        return viewHolder.itemView.findViewById<View>(com.rawsmusic.core.ui.R.id.ivCover)
    }

    /**
     */
    private fun getListCoverPosition(): Quad<Float, Float, Float, Float>? {
        val listCoverView = getListCoverView() ?: return null
        var x = 0f
        var y = 0f
        var v: View = listCoverView
        while (v != binding.unifiedContainer) {
            x += v.left.toFloat()
            y += v.top.toFloat()
            val parent = v.parent
            if (parent is View) v = parent else break
        }
        val density = resources.displayMetrics.density
        val fixedSize = 72f * density
        return Quad(x, y, fixedSize, fixedSize)
    }

    /** 获取播放页封面目标矩形位置 */
    /**
     */
    private fun getPlayCoverTargetRect(): android.graphics.RectF {
        val density = resources.displayMetrics.density
        val containerW = unifiedContainer.width.toFloat()
        val containerH = unifiedContainer.height.toFloat()
        val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        if (isLandscape) {
            val coverSize = minOf(containerW * 0.35f, containerH - 48f * density)
            val coverLeft = (containerW * 0.5f - coverSize) / 2f
            val coverTop = (containerH - coverSize) / 2f
            return android.graphics.RectF(coverLeft, coverTop, coverLeft + coverSize, coverTop + coverSize)
        } else {
            val hPad = 8f * density
            val availW = containerW - 2f * hPad
            val w = availW * 0.93f
            val h = w
            val left = hPad + (availW - w) / 2f
            val top = 56f * density
            return android.graphics.RectF(left, top, left + w, top + h)
        }
    }

    fun openPlayPageFromSongClick() {
        if (unifiedContainer.currentScene != UnifiedPlayerContainer.Scene.MAIN) return
        openPlayPageWithSharedElement()
    }

    private fun openPlayPageWithSharedElement() {
        if (unifiedContainer.currentScene != UnifiedPlayerContainer.Scene.MAIN) return

        if (isSideMenuOpen) closeSideMenu()

        playerController?.currentSong?.value ?: run {
            unifiedContainer.openPlayPage(true)
            return
        }

        unifiedContainer.isTransitioning = true
        isCoverAnimActive = true

        getCurrentSongCoverRectWhenReady { listCoverRect ->
            val containerLoc = IntArray(2)
            unifiedContainer.getLocationOnScreen(containerLoc)
            frozenContainerLoc = containerLoc.copyOf()

            val startRect = listCoverRect ?: run {
                val barLoc = IntArray(2)
                binding.miniPlayerBar.getLocationOnScreen(barLoc)
                val barW = binding.miniPlayerBar.width.toFloat()
                val barH = binding.miniPlayerBar.height.toFloat()
                RectF(barLoc[0].toFloat(), barLoc[1].toFloat(), barLoc[0] + barW, barLoc[1] + barH)
            }

            val targetRect = getPlayCoverTargetRect()
            if (targetRect.width() <= 0f || targetRect.height() <= 0f) {
                isCoverAnimActive = false
                frozenContainerLoc = null
                unifiedContainer.isTransitioning = false
                unifiedContainer.openPlayPage(true)
                return@getCurrentSongCoverRectWhenReady
            }

            val cl = frozenContainerLoc!!
            val targetW = targetRect.width()
            val targetH = targetRect.height()
            val targetCenterX = targetRect.centerX()
            val targetCenterY = targetRect.centerY()

            val startX = startRect.left - cl[0]
            val startY = startRect.top - cl[1]
            val startW = startRect.width()
            val startH = startRect.height()

            val startScaleX = startW / targetW
            val startScaleY = startH / targetH

            val translatedX = (startX + startW / 2f) - targetCenterX
            val translatedY = (startY + startH / 2f) - targetCenterY

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

            binding.ivPlayCover.apply {
                val lp = layoutParams
                lp.width = targetW.toInt()
                lp.height = targetH.toInt()
                layoutParams = lp
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCornerSizes(12f * resources.displayMetrics.density)
                    .build()
                translationX = translatedX
                translationY = translatedY
                scaleX = startScaleX
                scaleY = startScaleY
                alpha = 1f
                visibility = View.VISIBLE
            }

            if (listCoverRect != null) {
                getListCoverView()?.visibility = View.INVISIBLE
            } else {
                binding.miniPlayerBar.alpha = 0f
            }

            val baseCornerPx = 12f * resources.displayMetrics.density
            val playerCornerPx = baseCornerPx
            val mainCornerPx = if (startScaleX > 0.01f) playerCornerPx / startScaleX else playerCornerPx
            unifiedContainer.registerSceneParams(
                R.id.ivPlayCover,
                UnifiedPlayerContainer.Scene.MAIN,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.MAIN,
                    alpha = 1f,
                    scaleX = startScaleX,
                    scaleY = startScaleY,
                    translationX = translatedX,
                    translationY = translatedY,
                    visibility = View.VISIBLE,
                    cornerRadius = mainCornerPx
                )
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
        capsuleView.fold()
    }

        // ==================== 封面手势处理（参考 Poweramp 的 r + c0） ====================

    private var coverDragStartY = 0f
    private var coverDragStartX = 0f
    private var isCoverDragActive = false
    private var isCoverSwipeUpActive = false
    private var isHorizontalSwipe = false
    private var isSwitchingSong = false
    private var immersiveSwipeStartY = 0f
    private var immersiveSwipeActive = false
    private var immersiveSwipeDirection = 0
    private var audioCapsuleState = 0
    private var currentLyricText = ""
    private var coverLongPressTriggered = false
    private val coverDragThreshold by lazy { 240f * resources.displayMetrics.density }
    private val coverSwipeUpThreshold by lazy { 160f * resources.displayMetrics.density }
    private val coverSwipeSongThreshold by lazy { 80f * resources.displayMetrics.density }
    private val coverLongPressRunnable = Runnable {
        coverLongPressTriggered = true
        showFullCoverViewer()
    }

    private fun setupAudioInfoCapsule() {
        val capsule = binding.audioInfoCapsule ?: return
        updateCapsuleText()
        capsule.setOnClickListener {
            capsule.animate()
                .scaleX(0.85f).scaleY(0.85f)
                .alpha(0.4f)
                .setDuration(80)
                .withEndAction {
                    audioCapsuleState = (audioCapsuleState + 1) % 5
                    updateCapsuleText()
                    capsule.animate()
                        .scaleX(1f).scaleY(1f)
                        .alpha(1f)
                        .setDuration(120)
                        .start()
                }
                .start()
        }
        capsule.setOnLongClickListener {
            showAudioInfoPopup()
            true
        }
    }

    private var pendingHiresShow = false

    private fun updateHiresBadge() {
        if (!::unifiedContainer.isInitialized) return
        val song = playerController?.currentSong?.value
        val isPlayerScene = unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.PLAYER
        val isHires = song?.isHiRes == true
        val shouldShow = isPlayerScene && isHires
        pendingHiresShow = shouldShow

        // 由于在场景处理中，不能直接修改属性，否则会与动画系统冲突
        if (unifiedContainer.isTransitioning) {
            return
        }

        if (shouldShow) {
            binding.ivHiresSmall.visibility = View.VISIBLE
            binding.ivHiresSmall.alpha = 1f
        } else {
            binding.ivHiresSmall.animate().cancel()
            binding.ivHiresSmall.visibility = View.GONE
            binding.ivHiresSmall.alpha = 0f
        }
    }

    private fun updateCapsuleText() {
        val capsule = binding.audioInfoCapsule ?: return
        val song = playerController?.currentSong?.value
        val queue = playerController?.queue?.value
        capsule.isSelected = true
        when (audioCapsuleState) {
            0 -> {
                capsule.setCompoundDrawables(null, null, null, null)
                if (song != null) {
                    val usbSr = playerController?.getUsbOutputSampleRate() ?: 0
                    val srValue = if (usbSr > 0) usbSr else song.sampleRate
                    val sr = if (srValue > 0) {
                        val srKhz = srValue / 1000.0
                        if (srKhz == srKhz.toLong().toDouble()) "${srKhz.toLong()}kHz"
                        else "${"%.1f".format(srKhz)}kHz"
                    } else ""
                    val br = if (song.bitRate > 0) "${song.bitRate / 1000}kbps" else ""
                    val fmt = song.encodingFormat.ifBlank { song.format.ifBlank { song.extension } }.uppercase()
                    val bps = if (song.bitsPerSample > 0) "${song.bitsPerSample}bit" else ""
                    val hiResTag = if (song.isHiRes) "Hi-Res " else ""
                    capsule.text = listOf(hiResTag + fmt, sr, bps, br).filter { it.isNotBlank() }.joinToString(" ")
                } else {
                    capsule.text = ""
                }
            }
            1 -> {
                val deviceInfo = getOutputDeviceInfo()
                capsule.text = deviceInfo
                // 根据设备类型设置左侧图标
                val iconRes = getDeviceIconRes()
                if (iconRes != 0) {
                    val icon = androidx.core.content.ContextCompat.getDrawable(this, iconRes)
                    icon?.setTint(0xB0FFFFFF.toInt())
                    val iconSize = (12 * resources.displayMetrics.density).toInt()
                    icon?.setBounds(0, 0, iconSize, iconSize)
                    capsule.setCompoundDrawables(icon, null, null, null)
                    capsule.compoundDrawablePadding = (4 * resources.displayMetrics.density).toInt()
                } else {
                    capsule.setCompoundDrawables(null, null, null, null)
                }
            }
            2 -> {
                capsule.setCompoundDrawables(null, null, null, null)
                val usbSr = playerController?.getUsbOutputSampleRate() ?: 0
                val ffmpegPlayer = playerController?.ffmpegPlayerRef
                val outSr = if (usbSr > 0) usbSr else (ffmpegPlayer?.wavSampleRate ?: 0)
                val outBd = ffmpegPlayer?.wavBitsPerSample ?: 0
                val srText = if (outSr > 0) {
                    val srKhz = outSr / 1000.0
                    if (srKhz == srKhz.toLong().toDouble()) "${srKhz.toLong()}kHz"
                    else "${"%.1f".format(srKhz)}kHz"
                } else ""
                val bdText = if (outBd > 0) "${outBd}bit" else ""
                capsule.text = "$srText $bdText".trim()
            }
            3 -> {
                capsule.setCompoundDrawables(null, null, null, null)
                if (queue != null && queue.songs.isNotEmpty()) {
                    capsule.text = "${queue.currentIndex + 1}/${queue.songs.size}"
                } else {
                    capsule.text = ""
                }
            }
            4 -> {
                capsule.setCompoundDrawables(null, null, null, null)
                capsule.text = currentLyricText.ifBlank { "" }
            }
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private var cachedBluetoothCodec: String? = null
    private var bluetoothCodecFetching = false

    private fun getOutputDeviceInfo(): String {
        val isUsbExclusive = playerController?.isUsbExclusiveActive() == true
        val usbDeviceName = playerController?.getUsbDeviceName()

        if (isUsbExclusive && usbDeviceName != null) {
            return "USB DAC ($usbDeviceName)"
        }

        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val activeDevice = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        } ?: devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        } ?: devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        val result = when (activeDevice?.type) {
            android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> {
                val name = activeDevice.productName?.toString() ?: "Bluetooth"
                val codec = cachedBluetoothCodec ?: ""
                if (codec.isNotBlank()) "$name $codec" else name
            }
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
            android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
            android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> {
                val name = activeDevice.productName?.toString() ?: "USB DAC"
                "USB DAC ($name)"
            }
            else -> "内置扬声器"
        }
        if (activeDevice?.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP && cachedBluetoothCodec == null && !bluetoothCodecFetching) {
            fetchBluetoothCodecAsync()
        }
        return result
    }

    /**
     * 根据当前输出设备类型返回对应的图标资源ID
     */
    private fun getDeviceIconRes(): Int {
        val isUsbExclusive = playerController?.isUsbExclusiveActive() == true
        if (isUsbExclusive) return R.drawable.ic_usb

        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val btDevice = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (btDevice != null) return R.drawable.ic_bluetooth_connect

        val wiredDevice = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        if (wiredDevice != null) return R.drawable.ic_headphone

        val usbHeadset = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET }
        if (usbHeadset != null) return R.drawable.ic_usb

        // 默认扬声器
        return R.drawable.ic_volume_up
    }

    @Suppress("MissingPermission")
    private fun fetchBluetoothCodecAsync() {
        bluetoothCodecFetching = true
        Thread {
            val codec = detectBluetoothCodecSync()
            cachedBluetoothCodec = codec
            bluetoothCodecFetching = false
            runOnUiThread {
                updateCapsuleText()
            }
        }.start()
    }

    @Suppress("MissingPermission")
    private fun detectBluetoothCodecSync(): String {
        return try {
            val adapter = android.bluetooth.BluetoothAdapter.getDefaultAdapter() ?: return ""
            val profileProxy = arrayOfNulls<android.bluetooth.BluetoothProfile>(1)
            val lock = java.util.concurrent.CountDownLatch(1)

            adapter.getProfileProxy(this, object : android.bluetooth.BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                    profileProxy[0] = proxy
                    lock.countDown()
                }
                override fun onServiceDisconnected(profile: Int) {
                    lock.countDown()
                }
            }, android.bluetooth.BluetoothProfile.A2DP)

            if (!lock.await(3, java.util.concurrent.TimeUnit.SECONDS)) return ""

            val a2dp = profileProxy[0] ?: return ""
            val connectedDevices = a2dp.connectedDevices
            if (connectedDevices.isNullOrEmpty()) {
                try { adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, a2dp) } catch (_: Exception) {}
                return ""
            }

            val codecStatus = try {
                val method = a2dp.javaClass.getMethod("getCodecStatus", android.bluetooth.BluetoothDevice::class.java)
                method.invoke(a2dp, connectedDevices[0])
            } catch (_: Exception) { null }

            try { adapter.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, a2dp) } catch (_: Exception) {}

            if (codecStatus == null) return ""

            val codecConfig = try {
                val method = codecStatus.javaClass.getMethod("getCodecConfig")
                method.invoke(codecStatus)
            } catch (_: Exception) { null } ?: return ""

            val codecType = try {
                val method = codecConfig.javaClass.getMethod("getCodecType")
                method.invoke(codecConfig) as? Int ?: -1
            } catch (_: Exception) { -1 }

            when (codecType) {
                0 -> "SBC"
                1 -> "AAC"
                2 -> "aptX"
                3 -> "aptX HD"
                4 -> "LDAC"
                5 -> try {
                    val method = codecConfig.javaClass.getMethod("getCodecSpecific1")
                    val val1 = method.invoke(codecConfig) as? Long ?: 0L
                    if (val1 == 0L) "LHDC" else "LHDC V${val1 / 1000}"
                } catch (_: Exception) { "LHDC" }
                6 -> "LC3"
                7 -> "aptX Adaptive"
                8 -> "LHDC V5"
                1000 -> "LHDC"
                else -> "BT($codecType)"
            }
        } catch (_: Exception) { "" }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun showAudioInfoPopup() {
        val song = playerController?.currentSong?.value ?: return
        val queue = playerController?.queue?.value
        val density = resources.displayMetrics.density

        val dialog = android.app.Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        val container = android.widget.FrameLayout(this)

        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(0xF01A1A2E.toInt())
            val lp = android.widget.FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.7f).toInt(),
                (resources.displayMetrics.heightPixels * 0.5f).toInt()
            )
            lp.gravity = android.view.Gravity.CENTER
            layoutParams = lp
            setPadding((24 * density).toInt(), (20 * density).toInt(), (24 * density).toInt(), (20 * density).toInt())
            clipToOutline = true
            setOnClickListener { }
        }

        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun addSectionTitle(text: String) {
            content.addView(android.widget.TextView(this@MainActivity).apply {
                this.text = text
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 14f
                setPadding(0, (12 * density).toInt(), 0, (4 * density).toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
        }

        fun addItem(label: String, value: String) {
            content.addView(android.widget.TextView(this@MainActivity).apply {
                this.text = "$label: $value"
                setTextColor(0xB0FFFFFF.toInt())
                textSize = 12f
                setPadding(0, (2 * density).toInt(), 0, (2 * density).toInt())
            })
        }

        fun addArrow() {
            content.addView(android.widget.TextView(this@MainActivity).apply {
                text = "↓"
                setTextColor(0x40FFFFFF.toInt())
                textSize = 10f
                gravity = android.view.Gravity.CENTER
                setPadding(0, (6 * density).toInt(), 0, (2 * density).toInt())
            })
        }

        addSectionTitle("音频信息")
        val queuePos = if (queue != null && queue.songs.isNotEmpty()) "${queue.currentIndex + 1} / ${queue.songs.size}" else "未知"
        addItem("媒体", queuePos)

        addArrow()

        addSectionTitle("文件信息")
        val fmt = song.encodingFormat.ifBlank { song.format.ifBlank { song.extension } }.uppercase()
        addItem("格式", fmt.ifBlank { "未知" })
        addItem("位深", if (song.bitsPerSample > 0) "${song.bitsPerSample} bit" else "未知")
        addItem("采样率", if (song.sampleRate > 0) "${song.sampleRate} Hz" else "未知֪")
        addItem("码率", if (song.bitRate > 0) "${song.bitRate / 1000} kbps" else "未知֪")
        addItem("声道", if (song.channelCount > 0) "${song.channelCount}ch" else "未知֪")

        addArrow()

        addSectionTitle("音频信息")
        addItem("解码引擎", "FFmpeg")
        addItem("编码格式", song.encodingFormat.ifBlank { fmt.ifBlank { "未知" } })

        addArrow()

        addSectionTitle("重采样")
        val srcSr = if (song.sampleRate > 0) song.sampleRate else 0
        val srcBd = if (song.bitsPerSample > 0) song.bitsPerSample else 0
        val usbSr = playerController?.getUsbOutputSampleRate() ?: 0
        val ffmpegOutputSr = if (usbSr > 0) usbSr else (playerController?.ffmpegPlayerRef?.wavSampleRate ?: 0)
        val ffmpegOutputBd = playerController?.ffmpegPlayerRef?.wavBitsPerSample ?: 0
        val srChanged = ffmpegOutputSr > 0 && srcSr > 0 && srcSr != ffmpegOutputSr
        val bdChanged = ffmpegOutputBd > 0 && srcBd > 0 && srcBd != ffmpegOutputBd
        val srText = when {
            srChanged -> "${srcSr} Hz →${ffmpegOutputSr} Hz"
            srcSr > 0 -> "${srcSr} Hz (直通)"
            else -> "未知"
        }
        val bdText = when {
            bdChanged -> "${srcBd} bit → ${ffmpegOutputBd} bit"
            srcBd > 0 -> "${srcBd} bit (直通)"
            else -> "未知"
        }
        addItem("采样率", srText)
        addItem("位深", bdText)
        Log.d("AudioOutput", "resample: srcSr=$srcSr, srcBd=$srcBd, outSr=$ffmpegOutputSr, outBd=$ffmpegOutputBd, srChanged=$srChanged, bdChanged=$bdChanged, usbSr=$usbSr")

        addArrow()

        addSectionTitle("输出")
        val actualOutputMode = com.rawsmusic.module.player.AudioOutputManager.getCurrentOutputMode(this)
        val isUsbExclusive = playerController?.isUsbExclusiveActive() == true
        val outputApi = if (isUsbExclusive) "USB DAC (独占)" else com.rawsmusic.module.player.AudioOutputManager.getOutputModeLabel(actualOutputMode)
        val ffmpegPlayer = playerController?.ffmpegPlayerRef
        val actualLatencyMs = ffmpegPlayer?.latencyMs?.toFloat() ?: 0f
        val actualBufFrames = ffmpegPlayer?.bufferSizeInFrames ?: 0
        val estimatedLatencyMs = if (actualLatencyMs > 0f) actualLatencyMs else {
            val bufFrames = try {
                val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                am.getProperty(android.media.AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 256
            } catch (_: Exception) { 256 }
            bufFrames * 2 * 1000f / (if (ffmpegOutputSr > 0) ffmpegOutputSr.toFloat() else 48000f)
        }
        addItem("输出API", outputApi)
        addItem("实际输出", "${ffmpegOutputBd}bit / ${if (ffmpegOutputSr > 0) "${ffmpegOutputSr}Hz" else "未知"}")
        addItem("延迟", String.format("%.1f ms (%d frames)", estimatedLatencyMs, actualBufFrames))
        Log.d("AudioOutput", "popup: outputMode=$actualOutputMode, outputSr=$ffmpegOutputSr, outputBd=$ffmpegOutputBd, latencyMs=$actualLatencyMs, bufFrames=$actualBufFrames, usbExclusive=$isUsbExclusive")

        addArrow()

        addSectionTitle("设备信息")
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val devices = am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
        val btDevice = devices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val wiredDevice = devices.firstOrNull {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET
        }
        val usbDeviceName = playerController?.getUsbDeviceName()
        val deviceName: String
        val deviceBdSr: String
        when {
            isUsbExclusive && usbDeviceName != null -> {
                deviceName = "$usbDeviceName (DAC)"
                val srText = if (ffmpegOutputSr > 0) {
                    val srKhz = ffmpegOutputSr / 1000.0
                    if (srKhz == srKhz.toLong().toDouble()) "${srKhz.toLong()}kHz"
                    else "${"%.1f".format(srKhz)}kHz"
                } else "未知"
                deviceBdSr = "${ffmpegOutputBd}bit / $srText"
            }
            btDevice != null -> {
                deviceName = btDevice.productName?.toString() ?: "蓝牙设备"
                deviceBdSr = "24bit / 48kHz"
            }
            wiredDevice != null -> {
                deviceName = if (wiredDevice.type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET) {
                    val name = wiredDevice.productName?.toString() ?: "USB DAC"
                    "$name (DAC)"
                } else {
                    "有线耳机"
                }
                val srText = if (ffmpegOutputSr > 0) {
                    val srKhz = ffmpegOutputSr / 1000.0
                    if (srKhz == srKhz.toLong().toDouble()) "${srKhz.toLong()}kHz"
                    else "${"%.1f".format(srKhz)}kHz"
                } else "48kHz"
                deviceBdSr = "${ffmpegOutputBd}bit / $srText"
            }
            else -> {
                deviceName = "内置扬声器"
                deviceBdSr = "16bit / 48kHz"
            }
        }
        addItem("设备", deviceName)
        addItem("设备参数", deviceBdSr)

        scrollView.addView(content)
        card.addView(scrollView)
        container.addView(card)

        card.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, 16 * density)
            }
        }
        card.clipToOutline = true

        container.setOnClickListener {
            card.animate()
                .scaleX(0.3f).scaleY(0.3f)
                .alpha(0f)
                .setDuration(150)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction { dialog.dismiss() }
                .start()
        }

        dialog.setContentView(container)

        card.scaleX = 0.3f
        card.scaleY = 0.3f
        card.alpha = 0f
        dialog.show()
        card.animate()
            .scaleX(1f).scaleY(1f)
            .alpha(1f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun setupCoverGesture() {
        val coverTouchHandler = android.view.View.OnTouchListener { v, event ->
            if (isSwitchingSong) return@OnTouchListener true
            val scene = unifiedContainer.currentScene
            if (scene != UnifiedPlayerContainer.Scene.PLAYER && scene != UnifiedPlayerContainer.Scene.LYRIC) {
                return@OnTouchListener false
            }

            if (scene == UnifiedPlayerContainer.Scene.LYRIC && event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                val cover = binding.ivPlayCover
                val loc = IntArray(2)
                cover.getLocationOnScreen(loc)
                val sw = cover.width * cover.scaleX
                val sh = cover.height * cover.scaleY
                val cx = loc[0] + cover.translationX + cover.pivotX * (1f - cover.scaleX)
                val cy = loc[1] + cover.translationY + cover.pivotY * (1f - cover.scaleY)
                if (event.rawX < cx || event.rawX > cx + sw || event.rawY < cy || event.rawY > cy + sh) {
                    return@OnTouchListener false
                }
            }

            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    coverDragStartY = event.rawY
                    coverDragStartX = event.rawX
                    isCoverDragActive = false
                    isCoverSwipeUpActive = false
                    isHorizontalSwipe = false
                    coverLongPressTriggered = false
                    v.postDelayed(coverLongPressRunnable, 500)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - coverDragStartY
                    val dx = event.rawX - coverDragStartX
                    val touchSlop = ViewConfiguration.get(this).scaledTouchSlop

                    if (!isCoverDragActive && !isCoverSwipeUpActive && !isHorizontalSwipe && !coverLongPressTriggered) {
                        if (unifiedContainer.isTransitioning) return@OnTouchListener true
                        if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                            v.removeCallbacks(coverLongPressRunnable)
                            isHorizontalSwipe = true
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        } else if (kotlin.math.abs(dy) > touchSlop) {
                            // Vertical swipe
                            v.removeCallbacks(coverLongPressRunnable)
                            if (scene == UnifiedPlayerContainer.Scene.PLAYER) {
                                if (dy > 0) {
                                    isCoverDragActive = true
                                    registerCoverCollapseParams()
                                    unifiedContainer.startCoverDrag()
                                } else {
                                    isCoverSwipeUpActive = true
                                    registerCoverLyricParams()
                                    unifiedContainer.startCoverSwipeUpDrag()
                                }
                            } else if (scene == UnifiedPlayerContainer.Scene.LYRIC && dy > 0) {
                                isCoverSwipeUpActive = true
                                binding.playBgView.syncFrom(binding.lyricBgView)
                                binding.playBgView.resumeAnimations()
                                unifiedContainer.startCoverSwipeUpDrag()
                            }
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                    }

                    if (isHorizontalSwipe) {
                        // No visual following, just track the gesture
                    }
                    if (isCoverDragActive) {
                        val ratio = (dy / coverDragThreshold).coerceIn(0f, 1f)
                        unifiedContainer.updateCoverDrag(ratio)
                    }
                    if (isCoverSwipeUpActive) {
                        val absRatio = (kotlin.math.abs(dy) / coverSwipeUpThreshold).coerceIn(0f, 1f)
                        unifiedContainer.updateCoverSwipeUpDrag(absRatio)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(coverLongPressRunnable)

                    if (isHorizontalSwipe) {
                        val dx = event.rawX - coverDragStartX
                        if (kotlin.math.abs(dx) > coverSwipeSongThreshold) {
                            val direction = if (dx > 0) 1 else -1
                            performSwipeToChangeSong(direction)
                        } else {
                            binding.ivPlayCover.animate().translationX(0f).rotationY(0f).setDuration(150).start()
                        }
                        isHorizontalSwipe = false
                    } else if (isCoverDragActive) {
                        val dy = event.rawY - coverDragStartY
                        val ratio = (dy / coverDragThreshold).coerceIn(0f, 1f)
                        val shouldClose = ratio > 0.4f
                        unifiedContainer.endCoverDrag(shouldClose)
                        isCoverDragActive = false
                    } else if (isCoverSwipeUpActive) {
                        val dy = event.rawY - coverDragStartY
                        val absRatio = (kotlin.math.abs(dy) / coverSwipeUpThreshold).coerceIn(0f, 1f)
                        val shouldOpen = absRatio > 0.4f
                        unifiedContainer.endCoverSwipeUpDrag(shouldOpen)
                        isCoverSwipeUpActive = false
                    } else if (event.actionMasked == android.view.MotionEvent.ACTION_UP
                        && unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.LYRIC
                        && !unifiedContainer.isTransitioning) {
                        binding.playBgView.syncFrom(binding.lyricBgView)
                        binding.playBgView.resumeAnimations()
                        unifiedContainer.startCoverSwipeUpDrag()
                        unifiedContainer.endCoverSwipeUpDrag(shouldOpen = true)
                    }
                    coverLongPressTriggered = false
                }
            }
            true
        }

        binding.ivPlayCover.setOnTouchListener(coverTouchHandler)

        // 沉浸模式播放界面：在沉浸背景的封面区域上滑进入歌词页
        binding.immersiveBackground?.setOnTouchListener { v, event ->
            val isImmersive = unifiedContainer.isImmersiveEnabled
            val scene = unifiedContainer.currentScene
            if (!isImmersive || scene != UnifiedPlayerContainer.Scene.PLAYER) {
                return@setOnTouchListener false
            }
            // 只在封面区域（上部55%）响应
            val splitY = v.height * 0.55f
            if (event.y > splitY) return@setOnTouchListener false

            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    immersiveSwipeStartY = event.rawY
                    immersiveSwipeActive = false
                    immersiveSwipeDirection = 0 // 0=未确定, 1=上滑, -1=下滑
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - immersiveSwipeStartY
                    if (!immersiveSwipeActive && kotlin.math.abs(dy) > ViewConfiguration.get(this).scaledTouchSlop) {
                        immersiveSwipeActive = true
                        immersiveSwipeDirection = if (dy < 0) 1 else -1
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        if (immersiveSwipeDirection == 1) {
                            // 上滑：进入歌词
                            binding.ivHiresSmall.visibility = View.GONE
                            binding.ivHiresSmall.alpha = 0f
                            binding.ivPlayCover.pivotX = 0f
                            binding.ivPlayCover.pivotY = 0f
                            binding.lyricBgView.syncFrom(binding.playBgView)
                            binding.lyricBgView.resumeAnimations()
                            registerCoverLyricParams()
                            unifiedContainer.startCoverSwipeUpDrag()
                        } else {
                            // 下滑：直接返回主界面
                            unifiedContainer.transitionToScene(UnifiedPlayerContainer.Scene.MAIN)
                        }
                    }
                    if (immersiveSwipeActive && immersiveSwipeDirection == 1) {
                        val absRatio = (kotlin.math.abs(dy) / (v.height * 0.3f)).coerceIn(0f, 1f)
                        unifiedContainer.updateCoverSwipeUpDrag(absRatio)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (immersiveSwipeActive && immersiveSwipeDirection == 1) {
                        val dy = event.rawY - immersiveSwipeStartY
                        val absRatio = (kotlin.math.abs(dy) / (v.height * 0.3f)).coerceIn(0f, 1f)
                        unifiedContainer.endCoverSwipeUpDrag(absRatio > 0.3f)
                    }
                    immersiveSwipeActive = false
                }
            }
            true
        }

        var lyricCoverGestureActive = false
        binding.lyricContentContainer.setOnTouchListener { v, event ->
            if (unifiedContainer.currentScene != UnifiedPlayerContainer.Scene.LYRIC) {
                lyricCoverGestureActive = false
                return@setOnTouchListener false
            }
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val cover = binding.ivPlayCover
                    val loc = IntArray(2)
                    cover.getLocationOnScreen(loc)
                    val coverW = cover.width * cover.scaleX
                    val coverH = cover.height * cover.scaleY
                    val coverLeft = loc[0] + cover.translationX + cover.pivotX * (1f - cover.scaleX)
                    val coverTop = loc[1] + cover.translationY + cover.pivotY * (1f - cover.scaleY)
                    lyricCoverGestureActive = event.rawX >= coverLeft && event.rawX <= coverLeft + coverW &&
                            event.rawY >= coverTop && event.rawY <= coverTop + coverH
                    if (lyricCoverGestureActive) {
                        coverTouchHandler.onTouch(v, event)
                    }
                    lyricCoverGestureActive
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (lyricCoverGestureActive) {
                        coverTouchHandler.onTouch(v, event)
                        lyricCoverGestureActive = false
                        true
                    } else false
                }
                else -> {
                    if (lyricCoverGestureActive) {
                        coverTouchHandler.onTouch(v, event)
                        true
                    } else false
                }
            }
        }
    }

    private fun openQueuePage() {
        unifiedContainer.onPreparePlayerToQueue = {
            refreshQueueList()
        }
        if (unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.LYRIC) {
            unifiedContainer.switchToSceneSilent(UnifiedPlayerContainer.Scene.PLAYER)
        }
        unifiedContainer.openQueuePage()
    }

    private fun closeQueuePage() {
        unifiedContainer.closeQueuePage()
    }

    private fun openAlbumDetailPage() {
        val song = playerController?.currentSong?.value ?: return
        unifiedContainer.onPreparePlayerToAlbumDetail = {
            loadAlbumDetail(song)
        }
        unifiedContainer.openAlbumDetailPage()
    }

    private fun closeAlbumDetailPage() {
        unifiedContainer.closeAlbumDetailPage()
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

    private fun getMiniPlayerCoverPosition(): Quad<Float, Float, Float, Float>? {
        val miniBar = binding.miniPlayerBar
        val coverSize = 52f * resources.displayMetrics.density
        val padding = 10f * resources.displayMetrics.density
        val marginStart = 12f * resources.displayMetrics.density
        val marginBottom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val windowInsets = windowManager.currentWindowMetrics.windowInsets
            val navigationBars = windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars())
            navigationBars.bottom.toFloat()
        } else {
            @Suppress("DEPRECATION")
            resources.displayMetrics.heightPixels - window.decorView.height
        }.toFloat()

        val barHeight = 64f * resources.displayMetrics.density
        val containerHeight = unifiedContainer.height.toFloat()

        val x = marginStart + padding
        val y = containerHeight - marginBottom - barHeight + (barHeight - coverSize) / 2f

        return Quad(x, y, coverSize, coverSize)
    }

    private fun registerCoverCollapseParams() {
        val density = resources.displayMetrics.density
        val cover = binding.ivPlayCover

        cover.pivotX = cover.width / 2f
        cover.pivotY = cover.height / 2f

        binding.playTitleGroup.pivotX = binding.playTitleGroup.width / 2f
        binding.playTitleGroup.pivotY = binding.playTitleGroup.height / 2f

        val targetRect = getPlayCoverTargetRect()
        val targetW = if (targetRect.width() > 0f) targetRect.width() else cover.width.toFloat()
        val targetH = if (targetRect.height() > 0f) targetRect.height() else cover.height.toFloat()

        val listPos = getListCoverPosition()
        if (listPos != null) {
            val (listX, listY, listW, listH) = listPos

            val targetScaleX = (listW / targetW).coerceIn(0.1f, 1f)
            val targetScaleY = (listH / targetH).coerceIn(0.1f, 1f)

            val targetCenterX = targetRect.centerX()
            val targetCenterY = targetRect.centerY()
            val listCenterX = listX + listW / 2f
            val listCenterY = listY + listH / 2f

            val translatedX = listCenterX - targetCenterX
            val translatedY = listCenterY - targetCenterY

            val baseCornerRadius = 12f * density
            val mainCornerRadius = if (targetScaleX > 0.01f) baseCornerRadius / targetScaleX else baseCornerRadius

            unifiedContainer.registerSceneParams(
                R.id.ivPlayCover,
                UnifiedPlayerContainer.Scene.MAIN,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.MAIN,
                    alpha = 1f,
                    scaleX = targetScaleX,
                    scaleY = targetScaleY,
                    translationX = translatedX,
                    translationY = translatedY,
                    visibility = View.VISIBLE,
                    cornerRadius = mainCornerRadius
                )
            )

            unifiedContainer.registerSceneParams(
                R.id.ivPlayCover,
                UnifiedPlayerContainer.Scene.PLAYER,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.PLAYER,
                    alpha = 1f,
                    scaleX = cover.scaleX,
                    scaleY = cover.scaleY,
                    translationX = cover.translationX,
                    translationY = cover.translationY,
                    visibility = View.VISIBLE,
                    cornerRadius = baseCornerRadius
                )
            )
        } else {
            unifiedContainer.registerSceneParams(
                R.id.ivPlayCover,
                UnifiedPlayerContainer.Scene.MAIN,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.MAIN,
                    alpha = 1f,
                    scaleX = 0.5f,
                    scaleY = 0.5f,
                    translationX = 0f,
                    translationY = 300f * density,
                    visibility = View.VISIBLE
                )
            )
        }

        // navHostFragment 在 MAIN 场景下alpha 为 0 否则为 1
        unifiedContainer.registerSceneParams(
            R.id.nav_host_fragment,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                visibility = View.VISIBLE
            )
        )

        // 其他 View / playBgView 在 MAIN 时 alpha=0，其他场景为1
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
                scaleX = 0.5f,
                scaleY = 0.5f,
                translationY = 100f * density,
                visibility = View.GONE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.playTitleGroup,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                scaleX = 1f,
                scaleY = 1f,
                translationX = 0f,
                translationY = 0f,
                visibility = View.VISIBLE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.playBgView,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        binding.playBgScrim?.let { scrim ->
            if (scrim.id != 0) {
                unifiedContainer.registerSceneParams(
                    scrim.id,
                    UnifiedPlayerContainer.Scene.MAIN,
                    UnifiedPlayerContainer.SceneParams(
                        scene = UnifiedPlayerContainer.Scene.MAIN,
                        alpha = 0f,
                        visibility = View.GONE
                    )
                )
                unifiedContainer.registerSceneParams(
                    scrim.id,
                    UnifiedPlayerContainer.Scene.PLAYER,
                    UnifiedPlayerContainer.SceneParams(
                        scene = UnifiedPlayerContainer.Scene.PLAYER,
                        alpha = 1f,
                        visibility = View.VISIBLE
                    )
                )
            }
        }

        unifiedContainer.registerSceneParams(
            R.id.miniPlayerBar,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                translationY = 0f,
                visibility = View.VISIBLE
            )
        )

        unifiedContainer.registerSceneParams(
            R.id.miniPlayerBar,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                translationY = 0f,
                visibility = View.VISIBLE
            )
        )

        unifiedContainer.registerSceneParams(
            R.id.miniPlayerBar,
            UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 1f,
                translationY = 0f,
                visibility = View.VISIBLE
            )
        )
    }

    private fun registerImmersiveCoverCollapseParams() {
        val density = resources.displayMetrics.density
        val containerW = unifiedContainer.width.toFloat()
        val containerH = unifiedContainer.height.toFloat()
        if (containerW <= 0f || containerH <= 0f) return

        // 沉浸模式下 ivPlayCover 始终不可见（封面由 ImmersiveBackgroundView 渲染）
        // 不注册 ivPlayCover，让 applyImmersiveSceneParams 控制其状态

        val nonCoverFadeIds = listOf(
            R.id.ivPlayCoverMirror,
            R.id.playTitleGroup,
            R.id.playBottomPanel,
            R.id.btnPlayMode,
            R.id.audioInfoCapsule,
            R.id.playBgView,
            R.id.playBgScrim
        )
        nonCoverFadeIds.forEach { viewId ->
            unifiedContainer.registerSceneParams(
                viewId,
                UnifiedPlayerContainer.Scene.MAIN,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.MAIN,
                    alpha = 0f,
                    visibility = View.GONE
                )
            )
            unifiedContainer.registerSceneParams(
                viewId,
                UnifiedPlayerContainer.Scene.PLAYER,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.PLAYER,
                    alpha = if (viewId == R.id.ivPlayCoverMirror) 0.55f else 1f,
                    visibility = View.VISIBLE
                )
            )
        }

        unifiedContainer.registerSceneParams(
            R.id.miniPlayerBar,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                translationY = 0f,
                visibility = View.VISIBLE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.miniPlayerBar,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                translationY = 0f,
                visibility = View.VISIBLE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.nav_host_fragment,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                visibility = View.VISIBLE
            )
        )
    }

    /**
     * 沉浸模式下，上滑封面会触发淡入淡出效果，封面缩小到左上角。     */
    private fun registerCoverLyricParams() {
        val density = resources.displayMetrics.density
        val baseCornerRadius = 12f * density
        val isImmersive = unifiedContainer.isImmersiveEnabled

        unifiedContainer.registerSceneParams(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = if (isImmersive) 0f else 1f,
                scaleX = 0.3f,
                scaleY = 0.3f,
                translationX = 0f,
                translationY = 0f,
                visibility = if (isImmersive) View.INVISIBLE else View.VISIBLE,
                cornerRadius = baseCornerRadius
            )
        )

        unifiedContainer.registerSceneParams(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = if (isImmersive) 0f else 1f,
                scaleX = 1f,
                scaleY = 1f,
                translationX = 0f,
                translationY = 0f,
                visibility = if (isImmersive) View.INVISIBLE else View.VISIBLE,
                cornerRadius = baseCornerRadius
            )
        )

        listOf(
            R.id.ivPlayCoverMirror
        ).forEach { viewId ->
            unifiedContainer.registerSceneParams(
                viewId,
                UnifiedPlayerContainer.Scene.LYRIC,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.LYRIC,
                    alpha = 0f,
                    visibility = View.GONE
                )
            )
        }

        unifiedContainer.registerSceneParams(
            R.id.playBottomPanel,
            UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.playBottomPanel,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE
            )
        )

        unifiedContainer.registerSceneParams(
            R.id.btnMore,
            UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 0f,
                visibility = View.GONE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.btnMore,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 0f,
                visibility = View.GONE
            )
        )

        val coverLayoutParams = binding.ivPlayCover.layoutParams as android.widget.FrameLayout.LayoutParams
        val coverMarginStart = coverLayoutParams.marginStart
        val coverWidth = binding.ivPlayCover.width.toFloat()
        val titleWidth = binding.playTitleGroup.width.toFloat()

        if (coverWidth <= 0f || titleWidth <= 0f) {
            binding.playTitleGroup.pivotX = 0f
            binding.playTitleGroup.pivotY = 0f
            unifiedContainer.registerSceneParams(
                R.id.playTitleGroup,
                UnifiedPlayerContainer.Scene.LYRIC,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.LYRIC,
                    alpha = 1f,
                    visibility = View.VISIBLE,
                    scaleX = 0.98f,
                    scaleY = 0.98f,
                    translationX = 0f,
                    translationY = 0f
                )
            )
            unifiedContainer.registerSceneParams(
                R.id.playTitleGroup,
                UnifiedPlayerContainer.Scene.PLAYER,
                UnifiedPlayerContainer.SceneParams(
                    scene = UnifiedPlayerContainer.Scene.PLAYER,
                    alpha = 1f,
                    visibility = View.VISIBLE,
                    scaleX = 1f,
                    scaleY = 1f,
                    translationX = 0f,
                    translationY = 0f
                )
            )
            return
        }

        val smallCoverRight = coverMarginStart + coverWidth * 0.3f
        val titleLayoutParams = binding.playTitleGroup.layoutParams as android.widget.FrameLayout.LayoutParams
        val titleCurrentLeft = titleLayoutParams.marginStart.toFloat()
        val titleCurrentTop = titleLayoutParams.topMargin.toFloat()
        val targetX = smallCoverRight + 8 * density
        val isLandscapeLyric = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val targetY = if (isLandscapeLyric) {
            val containerH = unifiedContainer.height.toFloat()
            val coverSize = minOf(unifiedContainer.width * 0.35f, containerH - 48f * density)
            ((containerH - coverSize) / 2f)
        } else {
            (56 * density).toFloat()
        }
        binding.playTitleGroup.pivotX = binding.playTitleGroup.width / 2f
        binding.playTitleGroup.pivotY = binding.playTitleGroup.height / 2f
        unifiedContainer.registerSceneParams(
            R.id.playTitleGroup,
            UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = 1f,
                visibility = View.VISIBLE,
                scaleX = 0.98f,
                scaleY = 0.98f,
                translationX = targetX - titleCurrentLeft,
                translationY = targetY - titleCurrentTop
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.playTitleGroup,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = 1f,
                visibility = View.VISIBLE,
                scaleX = 1f,
                scaleY = 1f,
                translationX = 0f,
                translationY = 0f
            )
        )
    }

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /**
     * 在 openPlayPageWithSharedElement 之后恢复默认场景参数
     * 防止动画参数残留导致后续转场异常
     */
    private fun restoreDefaultSceneParams() {
        val density = resources.displayMetrics.density
        val isImmersive = unifiedContainer.isImmersiveEnabled
        unifiedContainer.registerSceneParams(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.PLAYER,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.PLAYER,
                alpha = if (isImmersive) 0f else 1f,
                visibility = if (isImmersive) View.INVISIBLE else View.VISIBLE,
                translationX = 0f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                cornerRadius = 12f * density
            )
        )
        unifiedContainer.registerSceneParams(
            R.id.ivPlayCover,
            UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.LYRIC,
                alpha = if (isImmersive) 0f else 1f,
                visibility = if (isImmersive) View.INVISIBLE else View.VISIBLE,
                scaleX = 0.3f,
                scaleY = 0.3f,
                translationX = 0f,
                translationY = 0f,
                cornerRadius = 12f * density
            )
        )
        // navHostFragment: 非MAIN 场景隐藏
        unifiedContainer.registerSceneParams(
            R.id.nav_host_fragment,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 1f,
                translationY = 0f,
                scaleX = 1f,
                scaleY = 1f,
                visibility = View.VISIBLE
            )
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
        // playBgView: MAIN 时 GONE
        unifiedContainer.registerSceneParams(
            R.id.playBgView,
            UnifiedPlayerContainer.Scene.MAIN,
            UnifiedPlayerContainer.SceneParams(
                scene = UnifiedPlayerContainer.Scene.MAIN,
                alpha = 0f,
                visibility = View.GONE
            )
        )
        // playBgScrim: MAIN 时 GONE（防止残留 VISIBLE 阻断触摸）
        binding.playBgScrim?.let { scrim ->
            if (scrim.id != 0) {
                unifiedContainer.registerSceneParams(
                    scrim.id,
                    UnifiedPlayerContainer.Scene.MAIN,
                    UnifiedPlayerContainer.SceneParams(
                        scene = UnifiedPlayerContainer.Scene.MAIN,
                        alpha = 0f,
                        visibility = View.GONE
                    )
                )
            }
        }

        // 胶囊播放栏 MAIN 场景下显示，其他场景根据页面类型决定 view 是否显示
        if (unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.MAIN) {
            binding.navHostFragment.apply {
                alpha = 1f
                translationY = 0f
                scaleX = 1f
                scaleY = 1f
                visibility = View.VISIBLE
            }
            binding.playBottomPanel?.apply {
                visibility = View.GONE
                alpha = 0f
            }
            binding.playTitleGroup.apply {
                visibility = View.GONE
                alpha = 0f
            }
            binding.btnMore?.apply {
                visibility = View.GONE
                alpha = 0f
            }
            binding.playMetadataCard.apply {
                visibility = View.GONE
                alpha = 0f
            }
            binding.playBgView.apply {
                visibility = View.GONE
                alpha = 0f
            }
            binding.mainBgScrim?.apply {
                visibility = View.VISIBLE
                alpha = 1f
            }
        }

        unifiedContainer.applyImmersiveSceneParams()
    }

    private fun setupPlayPageListeners() {
        binding.btnPlayPause.setOnClickListener {
            ButtonAnimHelper.playPauseAnim(it, true) {
                playerController?.playPause()
            }
        }
        binding.btnNext.setOnClickListener {
            ButtonAnimHelper.pressReleaseAnim(it)
            ButtonAnimHelper.coverSwitchAnim(binding.ivPlayCover, true)
            playerController?.next()
        }
        binding.btnPrevious.setOnClickListener {
            ButtonAnimHelper.pressReleaseAnim(it)
            ButtonAnimHelper.coverSwitchAnim(binding.ivPlayCover, false)
            playerController?.previous()
        }
        binding.btnPlayMode.setOnClickListener {
            ButtonAnimHelper.secondaryPressAnim(it)
            playerController?.let { ctrl ->
                ctrl.cyclePlayMode()
                updatePlayModeIcon(ctrl.playMode.value)
            }
        }
        binding.btnPlayMode.setOnLongClickListener {
            showPlayModePopup()
            true
        }
        binding.btnMore?.setOnClickListener {
            ButtonAnimHelper.secondaryPressAnim(it)
            (it as? AnimatedMoreButton)?.toggleExpanded()
            togglePlayMetadataCard()
        }
        // 根据播放模式更新图标和文字
        binding.playMetadataCard.onMetadataClick = {
            openMetadataDetail()
        }
        setupCoverGesture()
        setupAudioInfoCapsule()
        binding.btnFullCoverBack.setOnClickListener {
            hideFullCoverViewer()
        }
        setupFullCoverViewerGestures()
        binding.seekBar.onSeekStartListener = {
            isSeeking = true
            unifiedContainer.disableGestureIntercept = true
        }
        binding.seekBar.onSeekStopListener = { fraction ->
            val duration = playerController?.duration?.value ?: 0L
            playerController?.seekTo((fraction * duration).toLong())
            isSeeking = false
            unifiedContainer.disableGestureIntercept = false
            triggerCoverBreathingIfNeeded()
        }
        // 计算进度条位置
        playerController?.let { ctrl ->
            updatePlayModeIcon(ctrl.playMode.value)
        }

        binding.playBgView.setOnClickListener { /* 防止点击穿透 */ }

        binding.btnAudioQuality.setOnClickListener {
            // 在关闭播放页前先更新封面参数，确保转场时参数正确
            registerCoverCollapseParams()
            unifiedContainer.closePlayPage(false)
            try {
                navController.popBackStack(R.id.nav_songs, false)
                navController.navigate(R.id.nav_audio_settings)
            } catch (_: Exception) {}
        }

        binding.btnMoreAction.setOnClickListener {
            showSongActionSheet()
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

        setupSongActionSheet()
    }

    /**
     * 根据播放模式更新图标（2种shuffle模式+单曲/顺序循环）
     */
    private fun updatePlayModeIcon(playMode: PlayMode) {
        val highlightColor = 0xFFFFFFFF.toInt()
        val dimColor = 0x80787470.toInt()
        val density = resources.displayMetrics.density
        val size = (24 * density).toInt()

        when (playMode) {
            PlayMode.SHUFFLE_OFF -> {
                // 顺序播放
                binding.btnPlayMode.setImageResource(R.drawable.ic_order_play_fill)
                binding.btnPlayMode.imageTintList = android.content.res.ColorStateList.valueOf(dimColor)
                return
            }
            PlayMode.SHUFFLE_ALL -> {
                // 全部随机
                binding.btnPlayMode.setImageResource(R.drawable.ic_shuffle_fill)
                binding.btnPlayMode.imageTintList = android.content.res.ColorStateList.valueOf(highlightColor)
            }
            PlayMode.SHUFFLE_SONG -> {
                // 歌曲随机
                binding.btnPlayMode.setImageResource(R.drawable.ic_shuffle_fill)
                binding.btnPlayMode.imageTintList = android.content.res.ColorStateList.valueOf(highlightColor)
            }
            PlayMode.SHUFFLE_BOTH -> {
                // 单曲循环
                binding.btnPlayMode.setImageResource(R.drawable.ic_repeat_one_fill)
                binding.btnPlayMode.imageTintList = android.content.res.ColorStateList.valueOf(highlightColor)
            }
        }
    }

    /** 创建带点的shuffle图标（在 ic_shuffle 基础上添加点） */
    private fun createShuffleIconWithDots(
        size: Int,
        showVerticalDots: Boolean,
        showHorizontalDots: Boolean,
        density: Float
    ): android.graphics.drawable.BitmapDrawable {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

        // 进度条高度 65% 增加到 80%
        val iconSize = (size * 0.8f).toInt()
        val shuffleDrawable = ContextCompat.getDrawable(this, R.drawable.ic_shuffle)!!
        shuffleDrawable.setTint(0xFFFFFFFF.toInt())

        val offsetX = if (showVerticalDots) size * 0.06f else 0f
        val offsetY = if (showHorizontalDots) -size * 0.03f else 0f

        val left = (size - iconSize) / 2f + offsetX
        val top = (size - iconSize) / 2f + offsetY
        shuffleDrawable.setBounds(left.toInt(), top.toInt(), (left + iconSize).toInt(), (top + iconSize).toInt())
        shuffleDrawable.draw(canvas)

        // 进度条滑块半径从5dp减少到2dp
        if (showVerticalDots) {
            paint.style = Paint.Style.FILL
            val dotRadius = 2f * density
            val dotSpacing = 5f * density
            val dotStartY = size / 2f - dotSpacing
        val dotX = 1f * density + dotRadius  // 进度条起始位置1dp
            for (i in 0..2) {
                canvas.drawCircle(dotX, dotStartY + i * dotSpacing, dotRadius, paint)
            }
        }

        // 进度条轨道高度从5dp减少到2dp
        if (showHorizontalDots) {
            paint.style = Paint.Style.FILL
            val dotRadius = 2f * density
            val dotSpacing = 5f * density
            val dotStartX = size / 2f - dotSpacing
            val dotY = size - 1f * density - dotRadius
            for (i in 0..2) {
                canvas.drawCircle(dotStartX + i * dotSpacing, dotY, dotRadius, paint)
            }
        }

        return android.graphics.drawable.BitmapDrawable(resources, bitmap)
    }

    private var songActionSheetView: View? = null
    private var isSongActionSheetShowing = false
    private var hasCustomCover = false

    private fun setupSongActionSheet() {
        songActionSheetView = binding.songActionSheetContainer.root
        val sheet = songActionSheetView ?: return

        sheet.setOnClickListener {
            hideSongActionSheet()
        }

        val sheetContent = sheet.findViewById<android.widget.LinearLayout>(R.id.songActionSheet)
        sheetContent?.setOnClickListener { /* consume click to prevent dismiss */ }

        sheet.findViewById<android.widget.TextView>(R.id.actionAddToPlaylist)?.setOnClickListener {
            hideSongActionSheet()
            addToPlaylist()
        }
        sheet.findViewById<android.widget.TextView>(R.id.actionAddToQueue)?.setOnClickListener {
            hideSongActionSheet()
            addToQueue()
        }
        sheet.findViewById<android.widget.TextView>(R.id.actionEditMetadata)?.setOnClickListener {
            hideSongActionSheet()
            editMetadata()
        }
        sheet.findViewById<android.widget.TextView>(R.id.actionAlbumInfo)?.setOnClickListener {
            hideSongActionSheet()
            showAlbumList()
        }
        sheet.findViewById<android.widget.TextView>(R.id.actionMetadata)?.setOnClickListener {
            hideSongActionSheet()
            openMetadataDetail()
        }
        sheet.findViewById<android.widget.TextView>(R.id.actionDelete)?.setOnClickListener {
            hideSongActionSheet()
            deleteCurrentSong()
        }
        sheet.findViewById<android.widget.TextView>(R.id.btnCoverModify)?.setOnClickListener {
            pickCoverImage()
        }
        sheet.findViewById<android.widget.TextView>(R.id.btnCoverRestore)?.setOnClickListener {
            restoreOriginalCover()
        }
    }

    private fun showSongActionSheet() {
        val sheet = songActionSheetView ?: return
        val sheetContent = sheet.findViewById<android.widget.LinearLayout>(R.id.songActionSheet) ?: return
        sheet.visibility = View.VISIBLE
        sheet.alpha = 0f
        sheetContent.translationY = sheetContent.height.toFloat()
        sheet.animate().alpha(1f).setDuration(200).start()
        sheetContent.animate().translationY(0f).setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()
        isSongActionSheetShowing = true
        unifiedContainer.disableGestureIntercept = true
        updateCoverRestoreButton()
    }

    private fun hideSongActionSheet() {
        val sheet = songActionSheetView ?: return
        val sheetContent = sheet.findViewById<android.widget.LinearLayout>(R.id.songActionSheet) ?: return
        sheet.animate().alpha(0f).setDuration(200).start()
        sheetContent.animate().translationY(sheetContent.height.toFloat())
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator(1.5f))
            .withEndAction {
                sheet.visibility = View.GONE
            }
            .start()
        isSongActionSheetShowing = false
        unifiedContainer.disableGestureIntercept = false
    }

    private fun updateCoverRestoreButton() {
        val btnRestore = songActionSheetView?.findViewById<android.widget.TextView>(R.id.btnCoverRestore) ?: return
        if (hasCustomCover) {
            btnRestore.setTextColor(0xB0FFFFFF.toInt())
            btnRestore.isClickable = true
            btnRestore.isEnabled = true
        } else {
            btnRestore.setTextColor(0x66FFFFFF.toInt())
            btnRestore.isClickable = false
            btnRestore.isEnabled = false
        }
    }

    private fun addToPlaylist() {
        val song = playerController?.currentSong?.value ?: return
        try {
            val bundle = android.os.Bundle().apply {
                putLong("songId", song.id)
                putString("action", "addToPlaylist")
            }
            navController.navigate(R.id.nav_songs, bundle)
        } catch (_: Exception) {}
    }

    private fun joinPlaylist() {
        val song = playerController?.currentSong?.value ?: return
        try {
            val bundle = android.os.Bundle().apply {
                putLong("songId", song.id)
                putString("action", "joinPlaylist")
            }
            navController.navigate(R.id.nav_songs, bundle)
        } catch (_: Exception) {}
    }

    private fun addToQueue() {
        val song = playerController?.currentSong?.value ?: return
        playerController?.addToQueue(song)
        android.widget.Toast.makeText(this, "已添加到播放队列", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showAlbumList() {
        try {
            val song = playerController?.currentSong?.value ?: return
            val album = song.album?.trim()
            if (album.isNullOrBlank()) {
                Toast.makeText(this, "未知专辑", Toast.LENGTH_SHORT).show()
                return
            }
            unifiedContainer.closePlayPage(false)
            val bundle = android.os.Bundle().apply {
                putString(com.rawsmusic.ui.albums.AlbumDetailFragment.ARG_ALBUM_NAME, song.album)
                putString(com.rawsmusic.ui.albums.AlbumDetailFragment.ARG_ALBUM_ARTIST, song.artist)
                putString(com.rawsmusic.ui.albums.AlbumDetailFragment.ARG_COVER_PATH, resolveCoverUri(song))
            }
            navController.navigate(R.id.action_global_album_detail, bundle)
        } catch (_: Exception) {}
    }

    private fun editMetadata() {
        val song = playerController?.currentSong?.value ?: return
        val density = resources.displayMetrics.density

        val scrollView = android.widget.ScrollView(this).apply {
            setPadding((24 * density).toInt(), (16 * density).toInt(), (24 * density).toInt(), 0)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }

        data class FieldDef(val label: String, val key: String, val value: String)
        val fields = listOf(
            FieldDef("标题", "title", song.title),
            FieldDef("艺术家", "artist", song.artist),
            FieldDef("专辑", "album", song.album),
            FieldDef("流派", "genre", song.genre),
            FieldDef("年份", "year", if (song.year > 0) song.year.toString() else ""),
            FieldDef("音轨", "track", if (song.trackNumber > 0) song.trackNumber.toString() else "")
        )

        val editTexts = mutableListOf<com.google.android.material.textfield.TextInputEditText>()
        val textInputLayouts = mutableListOf<com.google.android.material.textfield.TextInputLayout>()

        for (field in fields) {
            val til = com.google.android.material.textfield.TextInputLayout(this).apply {
                hint = field.label
                boxBackgroundMode = com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE
                val r = 8f * density
                setBoxCornerRadii(r, r, r, r)
                boxStrokeColor = Color.parseColor("#6750A4")
                setHintTextColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#6750A4")))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() }
            }
            val et = com.google.android.material.textfield.TextInputEditText(til.context).apply {
                setText(field.value)
                setTextColor(Color.parseColor("#1C1B1F"))
                textSize = 15f
            }
            til.addView(et)
            container.addView(til)
            editTexts.add(et)
            textInputLayouts.add(til)
        }

        scrollView.addView(container)

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("编辑元数据")
            .setView(scrollView)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()

        dialog.show()

        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val newTitle = editTexts[0].text?.toString()?.trim() ?: ""
            val newArtist = editTexts[1].text?.toString()?.trim() ?: ""
            val newAlbum = editTexts[2].text?.toString()?.trim() ?: ""
            val newGenre = editTexts[3].text?.toString()?.trim() ?: ""
            val newYear = editTexts[4].text?.toString()?.trim()?.toIntOrNull() ?: 0
            val newTrack = editTexts[5].text?.toString()?.trim()?.toIntOrNull() ?: 0

            var updated = false
            try {
                val uri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, song.id
                )
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Audio.Media.TITLE, newTitle)
                    put(android.provider.MediaStore.Audio.Media.ARTIST, newArtist)
                    put(android.provider.MediaStore.Audio.Media.ALBUM, newAlbum)
                    put(android.provider.MediaStore.Audio.Media.GENRE, newGenre)
                    if (newYear > 0) put(android.provider.MediaStore.Audio.Media.YEAR, newYear)
                    if (newTrack > 0) put(android.provider.MediaStore.Audio.Media.TRACK, newTrack)
                }
                val rows = contentResolver.update(uri, values, null, null)
                updated = rows > 0
            } catch (_: Exception) {}

            if (updated) {
                binding.tvTitle.text = newTitle.ifBlank { song.displayName }
                binding.tvArtist.text = newArtist
                binding.tvAlbum.text = newAlbum
                capsuleView.updatePlaybackState(
                    playerController?.playState?.value == PlayState.PLAYING,
                    newTitle.ifBlank { song.displayName },
                    newArtist,
                    song.albumArtPath
                )
                updateCapsuleText()
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "保存失败，文件可能不支持", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
    }

    private fun showAlbumInfo() {
        val song = playerController?.currentSong?.value ?: return
        try {
            val bundle = android.os.Bundle().apply {
                putLong("albumId", song.albumId)
            }
            navController.navigate(R.id.nav_album_detail, bundle)
        } catch (_: Exception) {}
    }

    private fun refreshQueueList() {
        val queue = playerController?.queue?.value ?: return
        val prioritySongs = playerController?.getPriorityQueue() ?: emptyList()
        val allSongs = prioritySongs + queue.songs
        val currentIndex = queue.currentIndex + prioritySongs.size
        val rvQueue = binding.rvQueueList ?: return
        val emptyView = binding.queueEmptyView ?: return
        val tvCount = binding.tvQueueCount ?: return

        if (allSongs.isEmpty()) {
            rvQueue.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            tvCount.text = ""
            return
        }

        rvQueue.visibility = View.VISIBLE
        emptyView.visibility = View.GONE
        tvCount.text = "${allSongs.size} 首"

        if (rvQueue.adapter == null) {
            rvQueue.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
            rvQueue.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                    val view = android.widget.LinearLayout(parent.context).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(16, 12, 16, 12)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            setColor(0x40FFFFFF)
                            cornerRadius = 16f * resources.displayMetrics.density
                        }
                        clipToOutline = true
                        val tv = android.widget.TextView(context).apply {
                            id = android.view.View.generateViewId()
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 14f
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setSingleLine(true)
                        }
                        val tvSub = android.widget.TextView(context).apply {
                            id = android.view.View.generateViewId()
                            setTextColor(0xCCFFFFFF.toInt())
                            textSize = 12f
                            ellipsize = android.text.TextUtils.TruncateAt.END
                            setSingleLine(true)
                        }
                        val textContainer = android.widget.LinearLayout(context).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                            lp.marginStart = 12
                            layoutParams = lp
                            addView(tv)
                            addView(tvSub)
                        }
                        addView(textContainer)
                    }
                    return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {}
                }

                override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                    val song = allSongs[position]
                    val isPriority = position < prioritySongs.size
                    val container = holder.itemView as android.widget.LinearLayout
                    val textContainer = container.getChildAt(0) as android.widget.LinearLayout
                    val tv = textContainer.getChildAt(0) as android.widget.TextView
                    val tvSub = textContainer.getChildAt(1) as android.widget.TextView
                    val prefix = if (isPriority) "▶ " else if (position == currentIndex) "♪" else ""
                    tv.text = "$prefix${song.title}"
                    tvSub.text = if (isPriority) "优先 · \${song.artist}" else song.artist
                    holder.itemView.setOnClickListener {
                        if (isPriority) {
                            playerController?.play(song, allSongs, position)
                        } else {
                            val adjustedIndex = position - prioritySongs.size
                            playerController?.play(song, queue.songs, adjustedIndex)
                        }
                    }
                    holder.itemView.setOnLongClickListener {
                        if (isPriority) {
                            android.app.AlertDialog.Builder(this@MainActivity)
                                .setTitle("移除")
                                .setMessage("确定要从播放列表移除\"${song.title}\"吗？")
                                .setPositiveButton("移除") { _, _ ->
                                    playerController?.removeFromQueue(position)
                                    refreshQueueList()
                                }
                                .setNegativeButton("取消", null)
                                .show()
                        } else {
                            val adjustedIndex = position - prioritySongs.size
                            playerController?.removeFromQueue(adjustedIndex)
                            refreshQueueList()
                        }
                        true
                    }
                }

                override fun getItemCount() = allSongs.size
            }
        }
        rvQueue.adapter?.notifyDataSetChanged()
    }

    private fun loadAlbumDetail(song: com.rawsmusic.core.common.model.AudioFile) {
            binding.tvAlbumDetailTitle?.text = song.album.ifBlank { "未知专辑" }
            binding.tvAlbumDetailAlbum?.text = song.album.ifBlank { "未知专辑" }
            binding.tvAlbumDetailArtist?.text = song.artist.ifBlank { "未知艺术家" }
            binding.tvAlbumDetailInfo?.text = "${song.album.ifBlank { "未知专辑" }} · ${if (song.year > 0) song.year else ""}"

        val coverUri = resolveCoverUri(song)
        binding.ivAlbumDetailCover?.load(coverUri.ifBlank { null }) {
            crossfade(true)
            size(320)

        }

        val albumSongs = mutableListOf<com.rawsmusic.core.common.model.AudioFile>()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val allSongs = playerController?.queue?.value?.songs ?: emptyList()
                albumSongs.clear()
                albumSongs.addAll(allSongs.filter { it.albumId == song.albumId && it.albumId > 0 })
                if (albumSongs.isEmpty()) {
                    albumSongs.addAll(allSongs.filter { it.album == song.album && song.album.isNotBlank() })
                }
            } catch (_: Exception) {}

            launch(Dispatchers.Main) {
                if (albumSongs.isEmpty()) {
                    albumSongs.add(song)
                }
            binding.tvAlbumDetailInfo?.text = "${song.album.ifBlank { "未知专辑" }} · ${albumSongs.size}首"

                val rvSongs = binding.rvAlbumDetailSongs ?: return@launch
                rvSongs.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity)
                rvSongs.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
                    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                        val view = android.widget.LinearLayout(parent.context).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(16, 10, 16, 10)
                            background = android.graphics.drawable.GradientDrawable().apply {
                                setColor(0x40FFFFFF)
                                cornerRadius = 16f * resources.displayMetrics.density
                            }
                            clipToOutline = true
                            val tv = android.widget.TextView(context).apply {
                                setTextColor(android.graphics.Color.WHITE)
                                textSize = 14f
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                setSingleLine(true)
                            }
                            val tvSub = android.widget.TextView(context).apply {
                                setTextColor(0xCCFFFFFF.toInt())
                                textSize = 12f
                                ellipsize = android.text.TextUtils.TruncateAt.END
                                setSingleLine(true)
                            }
                            val textContainer = android.widget.LinearLayout(context).apply {
                                orientation = android.widget.LinearLayout.VERTICAL
                                val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                                lp.marginStart = 12
                                layoutParams = lp
                                addView(tv)
                                addView(tvSub)
                            }
                            addView(textContainer)
                        }
                        return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {}
                    }

                    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                        val s = albumSongs[position]
                        val container = holder.itemView as android.widget.LinearLayout
                        val textContainer = container.getChildAt(0) as android.widget.LinearLayout
                        val tv = textContainer.getChildAt(0) as android.widget.TextView
                        val tvSub = textContainer.getChildAt(1) as android.widget.TextView
                        tv.text = s.title
                        tvSub.text = s.artist
                        holder.itemView.setOnClickListener {
                            playerController?.play(s, albumSongs, position)
                        }
                    }

                    override fun getItemCount() = albumSongs.size
                }

                setupAlbumDetailEffectCard()
            }
        }
    }

    private fun setupAlbumDetailEffectCard() {
        binding.albumDetailEffectCard?.visibility = View.VISIBLE
        val virtualizer = com.rawsmusic.module.data.prefs.AppPreferences.Equalizer.virtualizer
        val bassBoost = com.rawsmusic.module.data.prefs.AppPreferences.Equalizer.bassBoost

        binding.switchAlbumSpatialEnabled?.isChecked = virtualizer > 0
        binding.sliderAlbumSpatialStrength?.value = virtualizer.toFloat()
        binding.sliderAlbumBassBoost?.value = bassBoost.toFloat()

        binding.switchAlbumSpatialEnabled?.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                val saved = com.rawsmusic.module.data.prefs.AppPreferences.Equalizer.virtualizer
                val value = if (saved <= 0) 500f else saved.toFloat()
                binding.sliderAlbumSpatialStrength?.value = value
                playerController?.setStereoWidenFactor(value / 1000f)
            } else {
                binding.sliderAlbumSpatialStrength?.value = 0f
                playerController?.setStereoWidenFactor(0f)
            }
        }

        binding.sliderAlbumSpatialStrength?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                playerController?.setStereoWidenFactor(value / 1000f)
                binding.switchAlbumSpatialEnabled?.isChecked = value > 0
            }
        }

        binding.sliderAlbumBassBoost?.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                com.rawsmusic.module.data.prefs.AppPreferences.Equalizer.bassBoost = value.toInt()
            }
        }
    }

    private fun deleteCurrentSong() {
        val song = playerController?.currentSong?.value ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("删除歌曲")
            .setMessage("确定要删除\"\${song.title}\"吗？")
            .setPositiveButton("删除") { _, _ ->
                playerController?.next()
                val file = java.io.File(song.path)
                if (file.exists()) file.delete()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private val COVER_PICK_REQUEST = 10001

    private fun pickCoverImage() {
        val intent = android.content.Intent(android.content.Intent.ACTION_PICK)
        intent.type = "image/*"
        startActivityForResult(intent, COVER_PICK_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == COVER_PICK_REQUEST && resultCode == android.app.Activity.RESULT_OK) {
            val uri = data?.data ?: return
            val song = playerController?.currentSong?.value ?: return
            val cacheDir = java.io.File(cacheDir, "custom_covers")
            cacheDir.mkdirs()
            val destFile = java.io.File(cacheDir, "${song.id}.jpg")
            try {
                contentResolver.openInputStream(uri)?.use { input ->
                    java.io.FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                hasCustomCover = true
                binding.ivPlayCover.load(destFile) {
                    crossfade(true)
                    size(3000)
                    transformations(SquarePadTransformation())
                }
                syncMirrorCover(destFile.absolutePath)
                val song = playerController?.currentSong?.value
                if (song != null) {
                    capsuleView.updatePlaybackState(
                        playerController?.playState?.value == PlayState.PLAYING,
                        song.title,
                        song.artist,
                        destFile.absolutePath
                    )
                }
                updateCoverRestoreButton()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun restoreOriginalCover() {
        val song = playerController?.currentSong?.value ?: return
        val cacheDir = java.io.File(cacheDir, "custom_covers")
        val cachedFile = java.io.File(cacheDir, "${song.id}.jpg")
        if (cachedFile.exists()) cachedFile.delete()
        hasCustomCover = false
        val coverUri = resolveCoverUri(song)
        binding.ivPlayCover.load(coverUri.ifBlank { null }) {
            crossfade(true)
            size(3000)
            transformations(SquarePadTransformation())
        }
        syncMirrorCover(coverUri.ifBlank { null })
        capsuleView.updatePlaybackState(
            playerController?.playState?.value == PlayState.PLAYING,
            song.title,
            song.artist,
            coverUri.ifBlank { null }
        )
        updateCoverRestoreButton()
    }

    /** 显示播放模式选择弹窗（包含4种模式，使用R数组定义标签） */
    private fun showPlayModePopup() {
        val density = resources.displayMetrics.density
        val modes = PlayMode.entries
        val labels = mapOf(
            PlayMode.SHUFFLE_OFF to "顺序播放",
            PlayMode.SHUFFLE_ALL to "全部随机",
            PlayMode.SHUFFLE_SONG to "歌曲随机",
            PlayMode.SHUFFLE_BOTH to "单曲循环"
        )

        val currentMode = playerController?.playMode?.value ?: return

        val iconLoc = IntArray(2)
        binding.btnPlayMode.getLocationOnScreen(iconLoc)
        val iconCenterX = iconLoc[0] + binding.btnPlayMode.width / 2f

        // 创建overlay覆盖层
        val overlay = android.widget.FrameLayout(this).apply {
            tag = "play_mode_overlay"
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 设置覆盖层属性
        var swipeStartX = 0f
        var swipeConfirmed = false
        val swipeSlop = 30f * density
        overlay.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    swipeStartX = event.rawX
                    swipeConfirmed = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!swipeConfirmed) {
                        val dx = event.rawX - swipeStartX
                        if (abs(dx) > swipeSlop) {
                            swipeConfirmed = true
                        }
                    }
                    if (swipeConfirmed) {
                        try { (overlay.parent as? android.view.ViewGroup)?.removeView(overlay) } catch (_: Exception) {}
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
        // 移除overlay覆盖层
                    if (!swipeConfirmed) {
                        try { (overlay.parent as? android.view.ViewGroup)?.removeView(overlay) } catch (_: Exception) {}
                    }
                }
            }
            true
        }

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.argb(235, 18, 16, 14))
        // 绘制R角矩形
            val cornerRadius = 14f * density
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                }
            }
            clipToOutline = true

            val screenWidth = resources.displayMetrics.widthPixels
            layoutParams = android.view.ViewGroup.LayoutParams(
                (screenWidth * 0.84).toInt(),
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(
                (8 * density).toInt(),
                (6 * density).toInt(),
                (8 * density).toInt(),
                (6 * density).toInt()
            )
        }

        for (mode in modes) {
            val isCurrent = mode == currentMode
            val textColor = if (isCurrent) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt()
        // 设置SHUFFLE_OFF模式为默认
            val iconColor = if (mode == PlayMode.SHUFFLE_OFF) {
                if (isCurrent) 0xFFFFFFFF.toInt() else 0x80FFFFFF.toInt()
            } else {
        0xFFFFFFFF.toInt()  // 默认shuffle模式颜色
            }

            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(
                    (10 * density).toInt(),
                    (8 * density).toInt(),
                    (10 * density).toInt(),
                    (8 * density).toInt()
                )

                val icon = android.widget.ImageView(this@MainActivity).apply {
                    // 根据当前播放模式更新图标和文字
                    val iconSize = (44 * density).toInt()
                    when (mode) {
                        PlayMode.SHUFFLE_OFF -> {
                            setImageResource(R.drawable.ic_order_play_fill)
                            setColorFilter(iconColor)
                        }
                        PlayMode.SHUFFLE_ALL -> {
                            setImageResource(R.drawable.ic_shuffle_fill)
                            setColorFilter(iconColor)
                        }
                        PlayMode.SHUFFLE_SONG -> {
                            setImageResource(R.drawable.ic_shuffle_fill)
                            setColorFilter(iconColor)
                        }
                        PlayMode.SHUFFLE_BOTH -> {
                            setImageResource(R.drawable.ic_repeat_one_fill)
                            setColorFilter(iconColor)
                        }
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (44 * density).toInt(),
                        (44 * density).toInt()
                    )
                }
                addView(icon)

                val text = android.widget.TextView(this@MainActivity).apply {
                    text = labels[mode] ?: mode.name
                    setTextColor(textColor)
                    textSize = 13f
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    ).apply { marginStart = (10 * density).toInt() }
                    if (isCurrent) typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                addView(text)

                if (isCurrent) {
                    val check = android.widget.TextView(this@MainActivity)
                    check.setText("✓")
                    check.setTextColor(0xFFFFFFFF.toInt())
                    check.textSize = 14f
                    addView(check)
                }

                setOnClickListener {
                    try {
                        playerController?.setPlayMode(mode)
                        updatePlayModeIcon(mode)
                        (overlay.parent as? android.view.ViewGroup)?.removeView(overlay)
                    } catch (_: Exception) {}
                }
            }
            container.addView(row)
        // 设置监听
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val containerWidth = (screenWidth * 0.84).toInt()
        val calculatedLeft = (iconCenterX - containerWidth / 2f).toInt()
            .coerceAtLeast((8 * density).toInt())
            .coerceAtMost((screenWidth - containerWidth - (8 * density).toInt()))
        val screenHeight = resources.displayMetrics.heightPixels
        val calculatedBottom = (screenHeight - iconLoc[1] + (8 * density).toInt()).coerceAtLeast(0)

        val containerLp = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            leftMargin = calculatedLeft
            bottomMargin = calculatedBottom
        }

        // 获取 decorView，设置系统UI可见性和状态栏颜色
        val decorView = window.decorView as? android.view.ViewGroup ?: return
        overlay.addView(container, containerLp)
        decorView.addView(overlay)
    }

    private fun setupLyricPageListeners() {
        // Lyric page: only translation toggle, no play controls
        // More button in lyric header
        binding.btnMoreLyric.setOnClickListener {
            ButtonAnimHelper.secondaryPressAnim(it)
            (it as? AnimatedMoreButton)?.toggleExpanded()
            toggleLyricMetadataCard()
        }
        binding.lyricMetadataCard.onMetadataClick = {
            openMetadataDetail()
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
        lyricPlayerView.lyricCountChangeListeners.add(object : LyricPlayerView.LyricCountChangeListener {
            override fun onLyricTextChanged(old: String, new: String) {}
            override fun onLyricChanged(news: List<IRichLyricLine>, removes: List<IRichLyricLine>) {
            }
        })
    }

    private fun setupMetadataListeners() {
        // 设置标题
        binding.btnMetadataBack.setOnClickListener {
            closeMetadataDetail()
        }
        // 设置滚动监听
        var metadataSwipeStartX = 0f
        var metadataSwipeStartY = 0f
        var metadataSwipeConfirmed = false
        val metadataSlop = 40f * resources.displayMetrics.density

        binding.metadataDetailLayer.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    metadataSwipeStartX = event.rawX
                    metadataSwipeStartY = event.rawY
                    metadataSwipeConfirmed = false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!metadataSwipeConfirmed) {
                        val dx = event.rawX - metadataSwipeStartX
                        val dy = event.rawY - metadataSwipeStartY
                        if (dx > metadataSlop && dx > abs(dy) * 1.5f) {
                            metadataSwipeConfirmed = true
                        }
                    }
                    if (metadataSwipeConfirmed) {
                        val dx = event.rawX - metadataSwipeStartX
                        binding.metadataDetailLayer.translationX = dx.coerceAtLeast(0f)
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (metadataSwipeConfirmed) {
                        val dx = event.rawX - metadataSwipeStartX
                        if (dx > binding.metadataDetailLayer.width * 0.3f) {
                            closeMetadataDetail()
                        } else {
        // 结束
                            binding.metadataDetailLayer.animate()
                                .translationX(0f)
                                .setDuration(200)
                                .setInterpolator(android.view.animation.DecelerateInterpolator())
                                .start()
                        }
                    }
                    metadataSwipeConfirmed = false
                }
            }
            metadataSwipeConfirmed || binding.metadataDetailLayer.visibility == View.VISIBLE
        }
    }

    fun setPlayerController(controller: PlayerController) {
        playerController = controller
    }

    fun toggleSideMenu() {
        if (isSideMenuOpen) {
            closeSideMenu()
        } else {
            openSideMenu()
        }
    }

    private fun applyDrawerColorSync(@Suppress("UNUSED_PARAMETER") drawerOpen: Boolean) {
    }




    private fun setDrawerDragOffset(offset: Float) {
        if (!isManualDrawerDrag) {
            isManualDrawerDrag = true
            applyDrawerColorSync(true)
        }
        val menuWidth = binding.sideMenu.width
        if (menuWidth <= 0) return

        val clampedOffset = offset.coerceIn(0f, 1f)

        // 检查是否需要更新当前播放的歌曲
        binding.sideMenu.translationX = -menuWidth * (1f - clampedOffset)
        unifiedContainer.translationX = menuWidth * clampedOffset
        binding.sideMenu.setSlideOffset(clampedOffset)
    }

    /** 完成抽屉拖拽（根据速度和位置决定是否打开） */
    private fun finishDrawerDrag(shouldOpen: Boolean) {
        if (!isManualDrawerDrag) return
        val menuWidth = binding.sideMenu.width
        if (menuWidth <= 0) {
            isManualDrawerDrag = false
            return
        }

        val currentTranslation = unifiedContainer.translationX
        val currentOffset = (currentTranslation / menuWidth).coerceIn(0f, 1f)

        val targetOffset = if (shouldOpen || currentOffset > 0.4f) 1f else 0f

        if (targetOffset == 1f) {
            animateDrawerToOffset(currentOffset, 1f, 250L) {
                isManualDrawerDrag = false
                isSideMenuOpen = true
                binding.sideMenu.open()
                applyDrawerColorSync(true)
            }
        } else {
            animateDrawerToOffset(currentOffset, 0f, 200L) {
                isManualDrawerDrag = false
                isSideMenuOpen = false
                unifiedContainer.translationX = 0f
                binding.sideMenu.translationX = -menuWidth.toFloat()
                binding.sideMenu.close()
                applyDrawerColorSync(false)
            }
        }
    }

    /** 动画移动抽屉到指定偏移量 */
    private fun animateDrawerToOffset(from: Float, to: Float, durationMs: Long, onEnd: () -> Unit) {
        val menuWidth = binding.sideMenu.width
        if (menuWidth <= 0) { onEnd(); return }

        android.animation.ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = android.view.animation.DecelerateInterpolator(2.0f)
            addUpdateListener { anim ->
                val current = anim.animatedValue as Float
                binding.sideMenu.translationX = -menuWidth * (1f - current)
                unifiedContainer.translationX = menuWidth * current
                binding.sideMenu.setSlideOffset(current)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onEnd()
                }
            })
            start()
        }
    }

    /** 隐藏迷你播放栏（向下滑出） */
    fun hideMiniPlayerBar() {
        binding.miniPlayerBar.animate()
            .translationY(binding.miniPlayerBar.height.toFloat() + 20f * resources.displayMetrics.density)
            .alpha(0f)
            .setDuration(200)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .start()
    }

    /** 显示迷你播放栏（从底部滑入） */
    fun showMiniPlayerBar() {
        binding.miniPlayerBar.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(250)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.5f))
            .start()
    }

    private fun loadLyrics(songPath: String) {
        if (songPath.isBlank()) {
            currentLyricData = com.rawsmusic.core.common.model.LyricData()
            lyricPlayerView.song = null
            unifiedContainer.lyricEnabled = false
            updateLyricAnchor()
            capsuleView.updateLyric(null, null, false)
            return
        }
        capsuleView.updateLyric(null, null, false)
        lifecycleScope.launch(Dispatchers.IO) {
            val lyricData = LyricReader.readLyrics(songPath)
            launch(Dispatchers.Main) {
                currentLyricData = lyricData
                if (!lyricData.isEmpty) {
                    val song = playerController?.currentSong?.value
                    val lyriconSong = lyricData.toLyriconSong(
                        name = song?.title,
                        artist = song?.artist
                    )
                    lyricPlayerView.song = lyriconSong

                    val density = resources.displayMetrics.density
                    val config = RichLyricLineConfig().apply {
                        primary.textSize = 24f * density
                        primary.textColor = intArrayOf(android.graphics.Color.WHITE)
                        primary.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        primary.enableRelativeProgress = true
                        primary.enableRelativeProgressHighlight = false
                        syllable.highlightColor = intArrayOf(android.graphics.Color.WHITE)
                        syllable.backgroundColor = intArrayOf(0x60FFFFFF.toInt())
                        syllable.enableSustainGlow = false
                        syllable.enableCharFloatAnimation = true
                        secondary.textSize = 16f * density
                        secondary.textColor = intArrayOf(0x70FFFFFF.toInt())
                        secondary.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        gradientProgressStyle = true
                        enableAnim = true
                        animId = "fade_out_left_fade_in_right"
                        scaleInMultiLine = 0.86f
                        fadingEdgeLength = (14 * density).toInt()
                        placeholderFormat = PlaceholderFormat.NAME_ARTIST
                    }
                    lyricPlayerView.setStyle(config)
                    val displayTrans2 = com.rawsmusic.module.data.prefs.AppPreferences.Lyricon.displayTranslation
                    lyricPlayerView.updateDisplayTranslation(displayTranslation = displayTrans2, displayRoma = displayTrans2)
                } else {
                    lyricPlayerView.song = null
                    currentLyricText = ""
                    capsuleView.updateLyric(null, null, false)
                }
                unifiedContainer.lyricEnabled = !lyricData.isEmpty
                updateLyricAnchor()

                LyriconProviderManager.setSong(
                    playerController?.currentSong?.value,
                    if (lyricData.isEmpty) null else lyricData
                )

                PlayerService.updateLyrics(lyricData)
                pushLyricsUpdateToService()
            }
        }
    }

    /**
     * 为什么需要 pushUpdateToService 这样单独的2个方法而不是直接传参？
     * 因为需要通过Intent Action来区分更新类型，MediaSession需要知道更新的是什么
     */
    private fun pushLyricsUpdateToService() {
        if (!PlayerService.isRunning) return
        try {
            val intent = Intent(this, PlayerService::class.java).apply {
                action = PlayerService.ACTION_UPDATE_LYRICS
            }
            startService(intent)
        } catch (_: Exception) {}
    }

    /**
     * 推送歌曲信息+播放状态到PlayerService，用于更新MediaSession
     */
    private fun pushUpdateToService(song: AudioFile) {
        if (!PlayerService.isRunning) return
        try {
            val intent = Intent(this, PlayerService::class.java).apply {
                action = PlayerService.ACTION_UPDATE
                putExtra("title", song.title)
                putExtra("artist", song.artist)
                putExtra("album", song.album)
                putExtra("albumArtPath", song.albumArtPath)
                putExtra("duration", song.duration)
                putExtra("playState", (playerController?.playState?.value ?: PlayState.IDLE).ordinal)
                putExtra("position", playerController?.position?.value ?: 0L)
                putExtra("sampleRate", song.sampleRate)
                putExtra("bitRate", song.bitRate)
            }
            startService(intent)
        } catch (_: Exception) {}
    }

    private fun requestAudioPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startScan()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun startScan() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val customPaths = com.rawsmusic.module.data.prefs.AppPreferences.UI.scanPaths
                MediaStoreScanner.scan(this@MainActivity, customPaths, quickScan = true).collect { progress ->
                    when (progress) {
                        is ScanProgress.Completed -> {
                            MusicRepository.insertSongs(progress.songs)
                        }
                        else -> {}
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun setupEdgeToEdge() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // 当 HOME 场景时，恢复默认 padding，让 DynamicCoverBackgroundView 正常显示
        // val statusBarHeight = UiUtils.getStatusBarHeight(this)
        // binding.navHostFragment.setPadding(0, statusBarHeight, 0, 0)

        // 根据当前场景调整封面布局参数，确保在不同场景下封面显示正确
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        UiUtils.setLightStatusBar(window.decorView, !isDarkMode)
        UiUtils.setLightNavigationBar(window.decorView, !isDarkMode)
    }

    /**
     */
    private fun restoreLastPlayingState() {
        val restoredSong = playerController?.restoreLastSong()
        restoredSong?.let { song ->
            val coverUri = resolveCoverUri(song)
            capsuleView.updatePlaybackState(
                playerController?.playState?.value == PlayState.PLAYING,
                song.title,
                song.artist,
                coverUri.ifBlank { song.albumArtPath }
            )
        // 延迟2秒后执行
            binding.tvTitle.text = song.title
            binding.tvArtist.text = song.artist
            binding.tvAlbum.text = song.album
            val playCoverUri = coverUri.ifBlank { song.albumArtPath }
            binding.ivPlayCover.load(playCoverUri) {
                crossfade(true)
                size(3000)
                allowHardware(false)
                transformations(SquarePadTransformation())
            }
            syncMirrorCover(playCoverUri.ifBlank { null })
            loadCoverBackground(playCoverUri)
        }
    }

    /**
     * 启动PlayerService（前台服务）
     */
    private fun startPlayerService() {
        val intent = Intent(this, PlayerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun updateStatusBarForLevel(level: UnifiedPlayerContainer.Scene) {
        when (level) {
            UnifiedPlayerContainer.Scene.MAIN -> {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                UiUtils.setLightStatusBar(window.decorView, !isDarkMode)
            }
            UnifiedPlayerContainer.Scene.PLAYER, UnifiedPlayerContainer.Scene.LYRIC,
            UnifiedPlayerContainer.Scene.QUEUE, UnifiedPlayerContainer.Scene.ALBUM_DETAIL -> {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                UiUtils.setLightStatusBar(window.decorView, false)
            }
        }
    }

    /**
     * API 31+使用RenderEffect实现模糊效果
     */
    /**
     * 解析封面 URI，对 WAV/DSF/DFF/AIFF 等 MediaStore 不支持的格式
     */
    private fun resolveCoverUri(song: AudioFile): String {
        if (song.albumArtPath.startsWith("file://")) {
            val filePath = song.albumArtPath.removePrefix("file://")
            if (java.io.File(filePath).exists()) return song.albumArtPath
        }
        val ext = song.path.substringAfterLast(".", "").uppercase()
        val isWavLike = ext in listOf("WAV", "DSF", "DFF", "AIFF", "AIF")
        // 对非 WAV 类格式优先尝试content:// URI，MediaStore 可能已有缩略图
        if (!isWavLike && song.albumArtPath.startsWith("content://")) {
            try {
                contentResolver.openInputStream(android.net.Uri.parse(song.albumArtPath))?.use { stream ->
                    if (stream.available() > 0) return song.albumArtPath
                }
            } catch (_: Exception) {}
        }
        // 对WAV/DSF/DFF/AIFF 等格式，使用FFmpeg 提取嵌入封面
        if (isWavLike) {
            try {
                val coverFile = java.io.File(cacheDir, "albumart/cover_${song.path.hashCode()}.jpg")
                val coverDir = coverFile.parentFile
                if (coverDir != null && !coverDir.exists()) coverDir.mkdirs()
                // 检查缓存
                if (coverFile.exists() && coverFile.length() > 1024) {
                    return "file://${coverFile.absolutePath}"
                }
                val ret = com.rawsmusic.core.common.ffmpeg.FFmpegBridge.extractCover(song.path, coverFile.absolutePath)
                if (ret == 0 && coverFile.exists() && coverFile.length() > 1024) {
                    return "file://${coverFile.absolutePath}"
                }
                if (coverFile.exists()) coverFile.delete()
            } catch (_: Exception) {}
        }
        // 如果所有路径都无效的格式，尝试从同目录查找封面文件
        val dir = java.io.File(song.path).parentFile ?: return song.albumArtPath.ifBlank { "" }
        val candidates = listOf("folder.jpg", "Folder.jpg", "cover.jpg", "Cover.jpg", "album.jpg", "Album.jpg",
            "folder.png", "Folder.png", "cover.png", "Cover.png", "album.png", "Album.png",
            "folder.webp", "Folder.webp", "cover.webp", "Cover.webp")
        for (name in candidates) {
            val file = java.io.File(dir, name)
            if (file.exists() && file.length() > 0) {
                return "file://${file.absolutePath}"
            }
        }
        // 尝试从同专辑其他格式文件中提取封面
        if (song.albumId > 0) {
            try {
                val projection = arrayOf(android.provider.MediaStore.Audio.Media.DATA)
                val selection = "${android.provider.MediaStore.Audio.Media.ALBUM_ID} = ?"
                val selectionArgs = arrayOf(song.albumId.toString())
                contentResolver.query(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, selectionArgs, null
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val p = cursor.getString(0) ?: continue
                        val otherExt = p.substringAfterLast(".", "").uppercase()
                        // 只处理内嵌封面的格式（FLAC/MP3/M4A/APE/OGG/OPUS等）
                        if (otherExt in listOf("FLAC", "MP3", "M4A", "MP4", "APE", "OGG", "OPUS")) {
                            try {
                                val retriever = android.media.MediaMetadataRetriever()
                                retriever.setDataSource(p)
                                val art = retriever.embeddedPicture
                                retriever.release()
                                if (art != null && art.size > 1024) {
                                    val cacheDir2 = java.io.File(cacheDir, "albumart")
                                    if (!cacheDir2.exists()) cacheDir2.mkdirs()
                                    val cachedCover = java.io.File(cacheDir2, "album_${song.albumId}.jpg")
                                    if (!cachedCover.exists()) {
                                        cachedCover.writeBytes(art)
                                    }
                                    return "file://${cachedCover.absolutePath}"
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        // WAV 类格式尝试content:// URI，MediaStore 可能有缩略图
        if (isWavLike && song.albumArtPath.startsWith("content://")) {
            try {
                contentResolver.openInputStream(android.net.Uri.parse(song.albumArtPath))?.use { stream ->
                    if (stream.available() > 0) return song.albumArtPath
                }
            } catch (_: Exception) {}
        }
        return song.albumArtPath.ifBlank { "" }
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
        android.util.Log.d("CoverDebug", "loadCoverBackground: path=$albumArtPath")
        val path = albumArtPath.ifBlank { null }
        if (path == null) {
            android.util.Log.d("CoverDebug", "loadCoverBackground: path is blank, using defaults")
            applyDefaultColors()
            binding.playBgView.clearArtwork()
            binding.lyricBgView.clearArtwork()
            binding.backgroundView.clearArtwork()
            binding.immersiveBackground?.clear()
            binding.mainPersistentCover?.clear()
            return
        }

        // 将解码后的 Bitmap 设置给 DynamicCoverBackgroundView
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = coil.request.ImageRequest.Builder(this@MainActivity)
                    .data(path)
                    .size(800)
                    .allowHardware(false)
                    .build()
                val loader = Coil.imageLoader(this@MainActivity)
                android.util.Log.d("CoverDebug", "loadCoverBackground: loader=$loader")
                val result = loader.execute(request)
                android.util.Log.d("CoverDebug", "loadCoverBackground: result=${result::class.simpleName}")
                if (result is coil.request.SuccessResult) {
                    val drawable = result.drawable
                    android.util.Log.d("CoverDebug", "loadCoverBackground: drawable=${drawable.intrinsicWidth}x${drawable.intrinsicHeight}")
                    val w = drawable.intrinsicWidth.coerceAtMost(800)
                    val h = drawable.intrinsicHeight.coerceAtMost(800)
                    if (w > 0 && h > 0) {
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        drawable.setBounds(0, 0, w, h)
                        drawable.draw(Canvas(bitmap))

        // 显示预览
                        val colors = CoverColorExtractor.extract(bitmap)

                        launch(Dispatchers.Main) {
                            coverColors = colors
                            binding.playBgView.setArtwork(bitmap)
                            binding.lyricBgView.setArtwork(bitmap)
                            binding.backgroundView.setArtwork(bitmap)
                            unifiedContainer.updateImmersiveCover(path)
                            // 更新主界面常驻封面
                            binding.mainPersistentCover?.setCover(path)
                            applyCoverColors()
                        }
                    } else {
                        launch(Dispatchers.Main) { applyDefaultColors() }
                    }
                } else {
                    android.util.Log.w("CoverDebug", "loadCoverBackground: not success, result=${result::class.simpleName}")
                    launch(Dispatchers.Main) { applyDefaultColors() }
                }
            } catch (e: Exception) {
                android.util.Log.e("CoverDebug", "loadCoverBackground: exception", e)
                launch(Dispatchers.Main) { applyDefaultColors() }
            }
        }
    }

    private fun applyDefaultColors() {
        coverColors = CoverColorExtractor.CoverColors()
        applyCoverColors()
    }

    /**
     * 应用封面提取的颜色到各个背景视图
     * 同时更新侧边菜单 DynamicCoverBackgroundView 的颜色
     */
    private fun applyCoverColors() {
        // 计算目标位置和大小，用于过渡动画
        lyricHeaderGradient.setColors(coverColors.lyricBg, Color.TRANSPARENT)

        // 使用属性动画实现平滑过渡，同时更新 View 的位置和大小
        val primaryOverlay = (coverColors.primary and 0x00FFFFFF)
        val darkOverlay = (coverColors.dark and 0x00FFFFFF)
        val lyricOverlay1 = (coverColors.dark and 0x00FFFFFF)
        val lyricOverlay2 = (coverColors.lyricBg and 0x00FFFFFF)
        binding.playBgView.setOverlayColors(intArrayOf(primaryOverlay, darkOverlay))
        binding.lyricBgView.setOverlayColors(intArrayOf(lyricOverlay1, lyricOverlay2))
        binding.backgroundView.setOverlayColors(intArrayOf(primaryOverlay, darkOverlay))

        // 隐藏胶囊播放栏，避免在预览时显示
        binding.sideMenu.setCoverColors(coverColors.primary, coverColors.dark)
    }

    /** 显示全屏封面查看器 */
    private fun showFullCoverViewer() {
        val song = playerController?.currentSong?.value ?: return
        val coverUri = resolveCoverUri(song)
        binding.ivFullCover.load(coverUri.ifBlank { null }) {
            crossfade(true)
            size(coil.size.Size.ORIGINAL)
            allowHardware(false)
        }
        fullCoverScale = 1f
        binding.ivFullCover.scaleX = 1f
        binding.ivFullCover.scaleY = 1f
        binding.fullCoverViewer.visibility = View.VISIBLE
        binding.fullCoverViewer.alpha = 0f
        binding.fullCoverViewer.animate()
            .alpha(1f)
            .setDuration(250)
            .start()
    }

    private var fullCoverLastTapTime = 0L
    private var fullCoverEdgeSwipeStartX = 0f
    private var fullCoverIsEdgeSwipe = false

    private fun setupFullCoverViewerGestures() {
        val edgeZone = (24 * resources.displayMetrics.density).toInt()
        binding.fullCoverViewer.setOnTouchListener { v, event ->
            fullCoverScaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    fullCoverEdgeSwipeStartX = event.rawX
                    fullCoverIsEdgeSwipe = event.rawX < edgeZone || event.rawX > (v.width - edgeZone)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (fullCoverIsEdgeSwipe) {
                        val dx = event.rawX - fullCoverEdgeSwipeStartX
                        if (kotlin.math.abs(dx) > ViewConfiguration.get(this@MainActivity).scaledTouchSlop * 2) {
                            hideFullCoverViewer()
                            return@setOnTouchListener true
                        }
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val now = System.currentTimeMillis()
                    if (now - fullCoverLastTapTime < 300) {
                        hideFullCoverViewer()
                        fullCoverLastTapTime = 0L
                        return@setOnTouchListener true
                    }
                    fullCoverLastTapTime = now
                    if (fullCoverScale < 1f && event.pointerCount <= 1) {
                        fullCoverScale = 1f
                        binding.ivFullCover.animate().scaleX(1f).scaleY(1f)
                            .setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    if (fullCoverScale < 1f) {
                        fullCoverScale = 1f
                        binding.ivFullCover.animate().scaleX(1f).scaleY(1f)
                            .setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    }
                }
            }
            true
        }
    }

    /** 隐藏全屏封面查看器 */
    private fun hideFullCoverViewer() {
        binding.ivFullCover.animate().scaleX(1f).scaleY(1f).setDuration(150)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                fullCoverScale = 1f
                binding.fullCoverViewer.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction {
                        binding.fullCoverViewer.visibility = View.GONE
                    }
                    .start()
            }.start()
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
        if (capsuleView.isCardExpanded()) {
            capsuleView.collapseAllCards()
            return
        }
        if (binding.metadataDetailLayer.visibility == View.VISIBLE) {
            closeMetadataDetail()
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
        if (binding.fullCoverViewer.visibility == View.VISIBLE) {
            hideFullCoverViewer()
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
            UnifiedPlayerContainer.Scene.PLAYER -> {
                if (navController.currentDestination?.id != R.id.nav_songs) {
                    navController.popBackStack(R.id.nav_songs, false)
                }
                unifiedContainer.closePlayPageWithCoverAlign(true)
                return
            }
            UnifiedPlayerContainer.Scene.MAIN -> {}
        }
        if (navController.currentDestination?.id != R.id.nav_songs) {
            navController.navigateUp()
            return
        }
        super.onBackPressed()
    }

    private fun isSearchActive(): Boolean {
        val fragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            ?.childFragmentManager?.fragments?.firstOrNull()
        return when (fragment) {
            is com.rawsmusic.ui.songs.SongsFragment -> fragment.isSearchMode
            is com.rawsmusic.ui.albums.AlbumsFragment -> fragment.isSearchMode
            is com.rawsmusic.ui.artists.ArtistsFragment -> false // ArtistsFragment 使用 Compose 内置搜索
            else -> false
        }
    }

    private fun closeSearch() {
        val fragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment)
            ?.childFragmentManager?.fragments?.firstOrNull()
        when (fragment) {
            is com.rawsmusic.ui.songs.SongsFragment -> fragment.exitSearch()
            is com.rawsmusic.ui.albums.AlbumsFragment -> fragment.exitSearch()
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // 点击非胶囊区域关闭展开选项
        if (ev.action == android.view.MotionEvent.ACTION_DOWN) {
            if (capsuleView.isCardExpanded()) {
                val capsuleRect = android.graphics.Rect()
                binding.miniPlayerBar.getGlobalVisibleRect(capsuleRect)
                if (!capsuleRect.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
                    capsuleView.collapseAllCards()
                }
            }
        }

        if (unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.PLAYER && !unifiedContainer.isTransitioning
            && binding.metadataDetailLayer.visibility != View.VISIBLE) {
            val coverView = binding.ivPlayCover
            coverView.getLocationOnScreen(playAreaCoverLoc)
            val coverRight = playAreaCoverLoc[0] + coverView.width
            val coverBottom = playAreaCoverLoc[1] + coverView.height
            val shrink = 16 * resources.displayMetrics.density
            val touchOnCover = ev.rawX >= playAreaCoverLoc[0] + shrink && ev.rawX <= coverRight - shrink &&
                    ev.rawY >= playAreaCoverLoc[1] + shrink && ev.rawY <= coverBottom - shrink

            if (!touchOnCover) {
                val leftEdgeZone = 24 * resources.displayMetrics.density
                val touchOnLeftEdge = ev.rawX < leftEdgeZone

                val seekBar = binding.seekBar
                val seekBarLoc = IntArray(2)
                seekBar.getLocationOnScreen(seekBarLoc)
                val touchOnSeekBar = ev.rawX >= seekBarLoc[0] && ev.rawX <= seekBarLoc[0] + seekBar.width &&
                        ev.rawY >= seekBarLoc[1] && ev.rawY <= seekBarLoc[1] + seekBar.height

                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        if (!touchOnLeftEdge && !touchOnSeekBar) {
                            playAreaSwipeStartX = ev.rawX
                            playAreaSwipeStartY = ev.rawY
                            isPlayAreaSwipeUp = false
                            isPlayAreaSwipeRight = false
                            isPlayAreaTracking = true
                        }
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (isPlayAreaTracking && !isPlayAreaSwipeUp && !isPlayAreaSwipeRight) {
                            val dx = ev.rawX - playAreaSwipeStartX
                            val dy = ev.rawY - playAreaSwipeStartY
                            val slop = ViewConfiguration.get(this).scaledTouchSlop * 1.5f
                            if (abs(dy) > slop && abs(dy) > abs(dx) * 0.7f && dy < 0) {
                                isPlayAreaSwipeUp = true
                            } else if (dx > slop && abs(dx) > abs(dy) * 0.7f) {
                                isPlayAreaSwipeRight = true
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (isPlayAreaTracking) {
                            val threshold = 60 * resources.displayMetrics.density
                            if (isPlayAreaSwipeUp) {
                                val dy = ev.rawY - playAreaSwipeStartY
                                if (dy < -threshold) {
                                    openQueuePage()
                                }
                            } else if (isPlayAreaSwipeRight) {
                                val dx = ev.rawX - playAreaSwipeStartX
                                if (dx > threshold) {
                                    openAlbumDetailPage()
                                }
                            }
                            isPlayAreaTracking = false
                            isPlayAreaSwipeUp = false
                            isPlayAreaSwipeRight = false
                        }
                    }
                }
            } else {
                isPlayAreaTracking = false
            }
        }
        if (unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.LYRIC && !unifiedContainer.isTransitioning) {
            val screenHeight = resources.displayMetrics.heightPixels
            val bottomZone = screenHeight * 0.66f
            if (ev.rawY > bottomZone) {
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        playAreaSwipeStartX = ev.rawX
                        playAreaSwipeStartY = ev.rawY
                        isPlayAreaSwipeUp = false
                        isPlayAreaSwipeRight = false
                        isPlayAreaTracking = true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (isPlayAreaTracking) {
                            val dy = ev.rawY - playAreaSwipeStartY
                            val dx = ev.rawX - playAreaSwipeStartX
                            val slop = ViewConfiguration.get(this).scaledTouchSlop * 1.5f
                            if (dy > slop && abs(dy) > abs(dx) * 0.7f) {
                                isPlayAreaSwipeUp = true
                            }
                        }
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        if (isPlayAreaTracking && isPlayAreaSwipeUp) {
                            val dy = ev.rawY - playAreaSwipeStartY
                            val threshold = 60 * resources.displayMetrics.density
                            if (dy > threshold) {
                                openQueuePage()
                            }
                        }
                        isPlayAreaTracking = false
                        isPlayAreaSwipeUp = false
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onResume() {
        super.onResume()
        if (!::unifiedContainer.isInitialized) return

        // 重置触摸和过渡状态，防止从后台恢复时交互卡住
        unifiedContainer.resetInteractionState()

        unifiedContainer.refreshImmersiveState(com.rawsmusic.module.data.prefs.AppPreferences.UI.isImmersiveEnabled)
        unifiedContainer.updateMiniCoverEnabled(com.rawsmusic.module.data.prefs.AppPreferences.UI.isMiniCoverEnabled)

        // 强制重新应用当前场景的所有视图参数（包括 onSceneChanged 回调），
        // 确保从后台恢复后所有视图状态一致
        unifiedContainer.forceReapplyCurrentScene()

        // 重新布局以响应设置变更
        unifiedContainer.post {
            setupCoverLayoutParams()
            updateHiresBadge()
        }

        val song = playerController?.currentSong?.value
        if (song != null && unifiedContainer.currentScene == UnifiedPlayerContainer.Scene.MAIN) {
            val coverUri = resolveCoverUri(song)
            capsuleView.updatePlaybackState(
                playerController?.playState?.value == PlayState.PLAYING,
                song.title,
                song.artist,
                coverUri.ifBlank { song.albumArtPath }
            )
        }

        // 如果正在播放，需要重创建，否则USB会话会被破坏
        val isPlaying = playerController?.playState?.value == PlayState.PLAYING
        if (isPlaying) {
            android.util.Log.i("MainActivity", "onResume: playback active, skip USB re-scan/open")
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

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(settingsChangeReceiver) } catch (_: Exception) {}
        CapsuleProgressSync.stop()
        LyriconProviderManager.stopPositionSync()
        LyriconProviderManager.destroy()
        playerController?.release()
        playerController = null
        PlayerHolder.controller = null
    }

        // ==================== 权限处理 ====================

    private fun togglePlayMetadataCard() {
        if (binding.lyricMetadataCard.isExpanded) {
            binding.lyricMetadataCard.collapse()
        }
        val anchor = binding.btnMore ?: binding.ivPlayCover
        positionMetadataCardAbove(binding.playMetadataCard, anchor)
        binding.playMetadataCard.toggle(0f, 0f)
    }

    private fun toggleLyricMetadataCard() {
        if (binding.playMetadataCard.isExpanded) {
            binding.playMetadataCard.collapse()
        }
        positionMetadataCardAbove(binding.lyricMetadataCard, binding.btnMoreLyric)
        binding.lyricMetadataCard.toggle(0f, 0f)
    }

    private fun positionMetadataCardAbove(card: MetadataCardView, anchor: View) {
        val containerLoc = IntArray(2)
        binding.unifiedContainer.getLocationOnScreen(containerLoc)
        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val anchorCenterX = anchorLoc[0] + anchor.width / 2f - containerLoc[0]
        val anchorTopY = anchorLoc[1] - containerLoc[1]
        val params = card.layoutParams as android.widget.FrameLayout.LayoutParams
        params.gravity = android.view.Gravity.NO_GRAVITY
        val cardWidth = (36 * resources.displayMetrics.density).toInt()
        params.width = cardWidth
        params.leftMargin = (anchorCenterX - cardWidth / 2f).toInt()
        params.topMargin = (anchorTopY - card.height.coerceAtLeast(60 * resources.displayMetrics.density.toInt())).toInt()
        params.rightMargin = 0
        card.layoutParams = params
    }

    /** 打开元数据详情页 */
    private fun openMetadataDetail() {
        binding.playMetadataCard.collapse()
        binding.lyricMetadataCard.collapse()
        // 显示提示
        fillMetadataDetail()
        // 结束
        binding.metadataDetailLayer.visibility = View.VISIBLE
        binding.metadataDetailLayer.alpha = 0f
        binding.metadataDetailLayer.translationX = binding.metadataDetailLayer.width.toFloat()
        binding.metadataDetailLayer.animate()
            .alpha(1f)
            .translationX(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.2f))
            .start()
        unifiedContainer.disableGestureIntercept = true
    }

    /** 关闭元数据详情页 */
    private fun closeMetadataDetail() {
        binding.metadataDetailLayer.animate()
            .alpha(0f)
            .translationX(binding.metadataDetailLayer.width.toFloat())
            .setDuration(250)
            .setInterpolator(android.view.animation.AccelerateInterpolator())
            .withEndAction {
                binding.metadataDetailLayer.visibility = View.GONE
                binding.metadataDetailList.removeAllViews()
            }
            .start()
        unifiedContainer.disableGestureIntercept = false
    }

    private fun fillMetadataDetail() {
        val song = playerController?.currentSong?.value ?: return
        val usbSr = playerController?.getUsbOutputSampleRate() ?: 0
        val srDisplay = when {
            usbSr > 0 && song.sampleRate != usbSr -> "${song.sampleRate} Hz → ${usbSr} Hz (DAC)"
            usbSr > 0 -> "${usbSr} Hz (DAC)"
            song.sampleRate > 0 -> "${song.sampleRate} Hz"
            else -> "未知"
        }
        val entries = listOf(
            "标题" to song.displayName,
            "艺术家" to song.artist,
            "专辑" to song.album,
            "流派" to song.genre,
            "作曲" to song.composer,
            "年份" to (if (song.year > 0) song.year.toString() else ""),
            "音轨" to (if (song.trackNumber > 0) song.trackNumber.toString() else ""),
            "时长" to AudioUtils.formatDuration(song.duration),
            "码率" to (if (song.bitRate > 0) "${song.bitRate / 1000} kbps" else "未知"),
            "采样率" to srDisplay,
            "位深" to (if (song.bitsPerSample > 0) "${song.bitsPerSample} bit" else "未知"),
            "格式" to (song.format.ifBlank { song.extension }),
            "文件大小" to formatFileSize(song.fileSize),
            "文件路径" to song.path
        )
        val density = resources.displayMetrics.density
        binding.metadataDetailList.removeAllViews()
        for ((label, value) in entries) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
            }
            val labelView = android.widget.TextView(this).apply {
                text = label
                setTextColor(android.graphics.Color.argb(140, 255, 255, 255))
                textSize = 12f
            }
            val valueView = android.widget.TextView(this).apply {
                text = value.ifBlank { "未知" }
                setTextColor(android.graphics.Color.argb(230, 240, 235, 232))
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            row.addView(labelView)
            row.addView(valueView)

        // 获取路径
            val divider = View(this).apply {
                setBackgroundColor(android.graphics.Color.argb(30, 255, 255, 255))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * density).toInt()
                )
            }
            row.addView(divider)

            binding.metadataDetailList.addView(row)
        }
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size >= 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
            size >= 1024 -> "%.1f KB".format(size / 1024.0)
            else -> "$size B"
        }
    }

}
