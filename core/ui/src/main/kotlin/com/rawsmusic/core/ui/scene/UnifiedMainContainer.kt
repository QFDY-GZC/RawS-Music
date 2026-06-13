package com.rawsmusic.core.ui.scene

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.rawsmusic.core.common.ext.fadeIn
import com.rawsmusic.core.common.ext.fadeOut
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rawsmusic.core.ui.decoration.GridSpacingItemDecoration
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.Folder
import com.rawsmusic.core.common.model.Playlist
import com.rawsmusic.core.common.model.SortOrder as SongSortOrder
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.adapter.SongDataProvider
import com.rawsmusic.core.ui.widget.AlphabetIndexView
import com.rawsmusic.core.ui.widget.powerlist.PowerListView
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.PlaylistStore
import com.rawsmusic.module.data.repository.MusicRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.consumeAllChanges
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage

class UnifiedMainContainer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    init {
        // 根据当前主题初始化颜色常量
        val isDark = com.rawsmusic.core.ui.theme.ThemeManager.isDarkMode(context)
        C.updateForTheme(isDark)
        UC.updateForTheme(isDark)
    }

    private val pageFactories = mutableMapOf<NavScene, () -> FrameLayout>()
    private val pageCache = mutableMapOf<NavScene, FrameLayout>()
    private val homeTileViews = mutableMapOf<NavScene, View>()
    private var isInitialized = false
    private var navigationLock = false

    // ==================== Compose 导航状态 ====================
    /** Compose 版本的导航栈 */
    private val composeBackStack = mutableListOf(NavScene.HOME)

    /** Compose 版本的场景过渡动画时长 */
    private val composeTransitionDuration = 400L

    // ==================== Compose 状态属性 ====================
    /** Compose 可观察的当前场景 */
    var composeCurrentScene by mutableStateOf(NavScene.HOME)
        private set

    /** Compose 可观察的是否正在过渡 */
    var composeIsTransitioning by mutableStateOf(false)
        private set

    /** Compose 可观察的过渡进度 (0..1) */
    var composeTransitionProgress by mutableFloatStateOf(0f)
        private set

    /** Compose 可观察的是否正在拖拽返回 */
    var composeIsDraggingBack by mutableStateOf(false)
        private set

    /** Compose 可观察的拖拽返回进度 (0..1) */
    var composeDragBackProgress by mutableFloatStateOf(0f)
        private set
    private var songsPowerListReturnActive = false
    private var songsPowerListReturnTargetScene: NavScene = NavScene.HOME

    // 艺术家页面 (Compose) 数据源
    private val artistDataSource = ArtistComposeDataSource()
    private val artistDetailDataSource = ArtistDetailComposeDataSource()

    // 排序状态
    enum class SortType { TITLE, ARTIST, ALBUM, DURATION, DATE_ADDED, FILENAME }
    enum class SortOrder { ASC, DESC }
    private var currentSortType = SortType.TITLE
    private var currentSortOrder = SortOrder.ASC
    var onSortChanged: ((SortType, SortOrder) -> Unit)? = null

    var onSongClick: ((AudioFile, Int) -> Unit)? = null
    var onAlbumClick: ((Album) -> Unit)? = null
    var onAlbumItemClick: ((Album) -> Unit)? = null
    var onArtistClick: ((Artist) -> Unit)? = null

    /** 播放队列 (从外部注入, 避免 core/ui 依赖 app 模块) */
    var onPlayQueue: ((List<AudioFile>, Int) -> Unit)? = null
    var onPlaylistClick: ((Playlist) -> Unit)? = null
    var onFolderClick: ((Folder) -> Unit)? = null
    var onFolderHierarchyClick: ((Folder) -> Unit)? = null
    var onQueueSongClick: ((AudioFile, Int) -> Unit)? = null
    var onRecentlyAddedClick: ((AudioFile, Int) -> Unit)? = null
    var onWebDavClick: (() -> Unit)? = null
    var onPlayAll: ((List<AudioFile>) -> Unit)? = null
    var onShuffleAll: ((List<AudioFile>) -> Unit)? = null
    var onSearchClick: (() -> Unit)? = null
    var onAddToQueue: ((AudioFile) -> Unit)? = null
    var onAddToPlaylist: ((AudioFile) -> Unit)? = null
    var onDeleteSong: ((AudioFile) -> Unit)? = null
    var onNavigateToPlayer: (() -> Unit)? = null
    /** 点击 HOME 卡片时，如果有对应 Fragment 则通过此回调导航，而不是创建内部页面 */
    var onNavigateToFragment: ((NavScene) -> Unit)? = null

    // ===== Songs page fields (migrated from SongsFragment) =====
    private lateinit var songDataProvider: SongDataProvider
    private var songsAllItems: List<AudioFile> = emptyList()
    private var currentPlayingSongId: Long = -1L
    var isSongsSearchMode = false
        private set
    private var activeSongPopup: PopupWindow? = null
    var onOpenFolderPicker: (() -> Unit)? = null
    var onNavigateToPlayerFromSong: (() -> Unit)? = null
    var onSongsRefresh: (() -> Unit)? = null
    var onSongsSortChanged: (() -> Unit)? = null

    fun initialize() {
        if (isInitialized) return
        isInitialized = true

        registerPageFactories()
        setupNavController()
        setupSceneController()

        // Compose 版本：直接创建主页并显示
        getOrCreatePage(NavScene.HOME)
        composeSwitchToSceneSilent(NavScene.HOME)
    }

    private fun registerPageFactories() {
        pageFactories[NavScene.HOME] = { createHomePage() }
        pageFactories[NavScene.SONGS] = { createSongsPage() }
        pageFactories[NavScene.FOLDERS] = { createFoldersPage() }
        pageFactories[NavScene.ALBUMS] = { createAlbumsPage() }
        pageFactories[NavScene.ARTISTS] = { createArtistsPage() }
        pageFactories[NavScene.PLAYLISTS] = { createPlaylistsPage() }
        pageFactories[NavScene.QUEUE] = { createQueuePage() }
        pageFactories[NavScene.RECENTLY_ADDED] = { createRecentlyAddedPage() }
        pageFactories[NavScene.WEBDAV] = { createWebDavPage() }
    }

    private fun setupNavController() {
        // Compose 版本：导航由 Compose 状态驱动
        // navController 仅用于兼容旧代码，不再主动触发场景切换
    }

    private fun setupSceneController() {
        // Compose 版本不需要 View 场景控制器
        // 场景切换由 Compose 状态自动管理
    }

    private fun saveCurrentPageState(scene: NavScene) {
        // Compose 版本不需要保存页面状态
        // 页面状态由 Compose 状态自动管理
    }

    private fun restorePageState(scene: NavScene) {
        // Compose 版本不需要恢复页面状态
        // 页面状态由 Compose 状态自动管理
    }

    private fun getOrCreatePage(scene: NavScene): FrameLayout {
        return pageCache.getOrPut(scene) {
            val page = createComposePage(scene)
            addView(page)
            page
        }
    }

    private fun getPage(scene: NavScene): FrameLayout? {
        return pageCache[scene]
    }

    private fun findRecyclerView(page: FrameLayout): RecyclerView? {
        // Compose 版本不需要 RecyclerView
        return null
    }

    fun navigateTo(scene: NavScene, argument: String = "") {
        if (composeIsTransitioning) return
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        composeNavigateToScene(scene, scope = scope)
    }

    /**
     * 靻默切换场景，不修改 backStack，不触发动画。
     * 用于从播放器返回时恢复之前的场景状态。
     */
    fun switchToScene(scene: NavScene) {
        if (composeIsTransitioning) return
        composeSwitchToSceneSilent(scene)
    }

    /**
     * 隐藏除指定场景外的所有页面。
     * 用于从播放器返回前准备容器可见性，避免所有页面闪现。
     */
    fun hideAllPagesExcept(exceptScene: NavScene) {
        // Compose 版本不需要手动隐藏页面
        // 场景切换由 Compose 状态自动管理
    }

    fun navigateBack(): Boolean {
        if (composeIsTransitioning) return false
        val previousScene = getPreviousScene()
        if (previousScene != null) {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
            composeNavigateToScene(previousScene, scope = scope)
            return true
        }
        return false
    }

    fun navigateHome() {
        if (composeIsTransitioning) return
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        composeBackToHome(scope)
        navigateHomeImmediate()
    }

    private fun navigateHomeImmediate() {
        // Compose 版本：直接切换到主页
        composeSwitchToSceneSilent(NavScene.HOME)
    }

    fun isAtHome(): Boolean = composeCurrentScene == NavScene.HOME

    fun getCurrentScene(): NavScene = composeCurrentScene

    fun canNavigateBack(): Boolean = composeBackStack.size > 1

    private fun shouldUseSongsPowerListReturn(current: NavScene, target: NavScene): Boolean {
        // Compose 版本：不再使用 PowerList 场景返回
        return false
    }

    private fun consumeSongsTransientBackState(): Boolean {
        if (activeSongPopup != null) {
            dismissSongPopup()
            return true
        }
        if (::songDataProvider.isInitialized && songDataProvider.isSelectMode) {
            songDataProvider.exitSelectMode()
            updateSongsEditBar()
            return true
        }
        if (isSongsSearchMode) {
            exitSongsSearch()
            return true
        }
        return false
    }

    private fun getHomeTileRectInWindow(scene: NavScene): RectF? {
        val tile = homeTileViews[scene]
        if (tile != null && tile.width > 0 && tile.height > 0 && tile.isShown) {
            val loc = IntArray(2)
            tile.getLocationInWindow(loc)
            return RectF(loc[0].toFloat(), loc[1].toFloat(), (loc[0] + tile.width).toFloat(), (loc[1] + tile.height).toFloat())
        }
        val homePage = getOrCreatePage(NavScene.HOME)
        val loc = IntArray(2)
        homePage.getLocationInWindow(loc)
        val w = if (homePage.width > 0) homePage.width.toFloat() else width.toFloat().coerceAtLeast(1f)
        val d = density
        return RectF(
            loc[0] + 16f * d,
            loc[1] + 180f * d,
            loc[0] + w - 16f * d,
            loc[1] + 270f * d
        )
    }

    private fun getSongsReturnTargetRectInWindow(): RectF? {
        return getHomeTileRectInWindow(NavScene.SONGS)
    }

    private fun viewRectInWindow(view: View): RectF {
        val rect = android.graphics.Rect()
        if (view.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0) {
            return RectF(rect)
        }
        val loc = IntArray(2)
        view.getLocationInWindow(loc)
        return RectF(
            loc[0].toFloat(),
            loc[1].toFloat(),
            (loc[0] + view.width).toFloat(),
            (loc[1] + view.height).toFloat()
        )
    }

    private fun prepareSongsPowerListReturn(targetScene: NavScene, swipeRight: Boolean, initialTouchX: Float, initialTouchY: Float, currentScene: NavScene = composeCurrentScene): Boolean {
        // Compose 版本：不再使用 PowerList 场景返回
        return false
    }

    private fun returnToHomeFromCurrentScene(): Boolean {
        if (consumeSongsTransientBackState()) return true
        // Compose 版本：直接导航回主页
        navigateHome()
        return true
    }

    // ==================== 手势拖拽返回 API ====================

    /**
     * 开始手势拖拽返回：准备当前页面和目标页面
     * 对标 Poweramp P.onBackStarted
     *
     * @param swipeRight true=右滑返回，false=左滑返回
     * @param initialTouchX 初始触摸 X 坐标（像素）
     * @param initialTouchY 初始触摸 Y 坐标（像素）
     * @return true 如果可以拖拽返回（有上一级页面）
     */
    fun startDragBack(
        swipeRight: Boolean = true,
        initialTouchX: Float = 0f,
        initialTouchY: Float = 0f
    ): Boolean {
        if (!canNavigateBack()) return false
        if (composeIsTransitioning || composeIsDraggingBack) return false
        if (consumeSongsTransientBackState()) return false

        // Compose 版本：使用 composeStartDragBack
        composeStartDragBack()
        return true
    }

    /**
     * 手势拖拽中：更新触摸坐标（跟手）
     * 对标 Poweramp P.onBackProgressed
     */
    fun updateDragBack(currentTouchX: Float, currentTouchY: Float) {
        // Compose 版本：拖拽进度由 Compose 手势容器管理
    }

    /**
     * Predictive Back API: 通过 ratio (0~1) 直接驱动拖拽动画
     */
    fun updateDragBackProgress(ratio: Float) {
        composeUpdateDragBack(ratio)
    }

    /**
     * 手势拖拽结束
     * @param shouldGoBack true=完成返回，false=取消
     * @param velocity 释放速度
     */
    fun endDragBack(shouldGoBack: Boolean, velocity: Float = 0f) {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        composeEndDragBack(shouldGoBack, scope = scope)
    }

    /** 获取当前拖拽进度 (0~1) */
    fun getDragBackRatio(): Float = composeDragBackProgress

    /** 是否正在拖拽返回 */
    fun isDraggingBack(): Boolean = composeIsDraggingBack

    /** 是否正在过渡动画中 */
    fun isSceneTransitioning(): Boolean = composeIsTransitioning

    val currentSceneFlow: kotlinx.coroutines.flow.StateFlow<NavScene> get() = _currentSceneFlow
    private val _currentSceneFlow = kotlinx.coroutines.flow.MutableStateFlow(NavScene.HOME)

    fun onBackPressed(): Boolean {
        if (composeIsTransitioning || composeIsDraggingBack) return true
        if (!isAtHome()) {
            navigateBack()
            return true
        }
        return false
    }

    fun releasePageMemory(scene: NavScene) {
        val page = pageCache.remove(scene) ?: return
        removeView(page)
    }

    fun releaseAllPageMemoryExcept(except: NavScene) {
        val toRemove = pageCache.keys.filter { it != except && it != NavScene.HOME }.toList()
        for (scene in toRemove) {
            val page = pageCache.remove(scene) ?: continue
            removeView(page)
        }
    }

    private val tileColors = intArrayOf(
        0xFF5E7CE2.toInt(),
        0xFFE25E86.toInt(),
        0xFF42A5A1.toInt(),
        0xFFF2A65A.toInt(),
        0xFF7E57C2.toInt(),
        0xFF66A85F.toInt(),
        0xFFE07A5F.toInt(),
        0xFF4A90D9.toInt(),
        0xFFD4B896.toInt(),
        0xFF8E6C88.toInt()
    )

    private var homeScaleFactor = 1.0f
    private val MIN_SCALE = 0.5f
    private val MAX_SCALE = 1.5f

    private fun createHomePage(): FrameLayout {
        return createComposeHomePage()
    }

    /**
     * Compose 版本的主页
     * 纯 Compose 实现，替代原 View 版本
     */
    private fun createComposeHomePage(): FrameLayout {
        val page = createPageContainer(NavScene.HOME)
        val composeView = ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setContent {
                ComposeHomePageContent()
            }
        }
        page.addView(composeView)
        return page
    }

    @Composable
    private fun ComposeHomePageContent() {
        val scope = rememberCoroutineScope()
        val entries = NavScene.homeEntries

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor(C.PAGE_BG))
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题
            item {
                Text(
                    text = "音乐库",
                    color = ComposeColor(C.TEXT_PRIMARY),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            // 搜索栏
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(ComposeColor(C.CARD_BG))
                        .clickable { onSearchClick?.invoke() }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔍", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "搜索歌曲、专辑、艺术家...",
                            color = ComposeColor(C.TEXT_META),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // 导航磁贴网格
            items(entries.chunked(2)) { rowEntries ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowEntries.forEach { entry ->
                        ComposeHomeTile(
                            scene = entry,
                            onClick = {
                                if (entry.hasFragment()) {
                                    onNavigateToFragment?.invoke(entry)
                                } else {
                                    navigateTo(entry)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // 如果是奇数个，添加占位
                    if (rowEntries.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // 底部间距
            item {
                Spacer(modifier = Modifier.height(180.dp))
            }
        }
    }

    @Composable
    private fun ComposeHomeTile(
        scene: NavScene,
        onClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        val tileColors = listOf(
            listOf(ComposeColor(0xFF4A90D9), ComposeColor(0xFF357ABD)),
            listOf(ComposeColor(0xFFE67E22), ComposeColor(0xFFD35400)),
            listOf(ComposeColor(0xFF2ECC71), ComposeColor(0xFF27AE60)),
            listOf(ComposeColor(0xFF9B59B6), ComposeColor(0xFF8E44AD)),
            listOf(ComposeColor(0xFFE74C3C), ComposeColor(0xFFC0392B)),
            listOf(ComposeColor(0xFF1ABC9C), ComposeColor(0xFF16A085)),
            listOf(ComposeColor(0xFFF39C12), ComposeColor(0xFFE67E22)),
            listOf(ComposeColor(0xFF3498DB), ComposeColor(0xFF2980B9)),
            listOf(ComposeColor(0xFFE91E63), ComposeColor(0xFFC2185B))
        )
        val colorIndex = NavScene.homeEntries.indexOf(scene) % tileColors.size
        val colors = tileColors[colorIndex]

        Box(
            modifier = modifier
                .height(90.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.verticalGradient(colors))
                .clickable { onClick() }
                .padding(16.dp),
            contentAlignment = Alignment.BottomStart
        ) {
            Column {
                Text(
                    text = scene.icon,
                    fontSize = 24.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = scene.label,
                    color = ComposeColor.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    // Compose 版本：旧 View 方法已删除

    private fun adjustAlpha(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt()
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun updateHomeCounts(songs: Int, albums: Int, artists: Int, folders: Int, playlists: Int) {
        // Compose 版本：数据由 Compose 状态自动管理
    }

    private fun createSongsPage(): FrameLayout {
        return createComposeSongsPage()
    }

    /**
     * Compose 版本的歌曲列表页
     * 纯 Compose 实现，替代原 View 版本
     */
    private fun createComposeSongsPage(): FrameLayout {
        val page = createPageContainer(NavScene.SONGS)
        val composeView = ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setContent {
                ComposeSongsPageContent()
            }
        }
        page.addView(composeView)
        return page
    }

    @Composable
    private fun ComposeSongsPageContent() {
        val scope = rememberCoroutineScope()
        var searchQuery by remember { mutableStateOf("") }
        var isSearchActive by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor(C.PAGE_BG))
        ) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回按钮
                IconButton(
                    onClick = { navigateHome() },
                    modifier = Modifier.size(44.dp)
                ) {
                    Text("←", fontSize = 20.sp, color = ComposeColor(C.TEXT_SECONDARY))
                }

                // 标题
                Text(
                    text = "歌曲列表",
                    color = ComposeColor(C.TEXT_PRIMARY),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )

                // 文件夹按钮
                IconButton(
                    onClick = { onOpenFolderPicker?.invoke() },
                    modifier = Modifier.size(44.dp)
                ) {
                    Text("📁", fontSize = 18.sp)
                }

                // 排序按钮
                IconButton(
                    onClick = { showSongsSortDialog() },
                    modifier = Modifier.size(44.dp)
                ) {
                    Text("⋮", fontSize = 18.sp, color = ComposeColor(C.TEXT_PRIMARY))
                }

                // 搜索按钮
                IconButton(
                    onClick = { isSearchActive = !isSearchActive },
                    modifier = Modifier.size(44.dp)
                ) {
                    Text("🔍", fontSize = 18.sp)
                }
            }

            // 搜索栏 (可折叠)
            if (isSearchActive) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(ComposeColor(C.CARD_BG))
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(
                            color = ComposeColor(C.TEXT_PRIMARY),
                            fontSize = 14.sp
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(ComposeColor(C.ACCENT))
                    )
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "搜索歌曲...",
                            color = ComposeColor(C.TEXT_META),
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // 歌曲列表
            // 注意：这里需要实际的数据源，目前先显示占位
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 180.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "歌曲列表\n(需要集成 SongDataProvider)",
                    color = ComposeColor(C.TEXT_META),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    // ===== Songs page methods (migrated from SongsFragment) =====

    fun getSongsPowerListView(): PowerListView? = null

    fun updatePlayingPosition(position: Int, songId: Long = -1L) {
        if (songId > 0) currentPlayingSongId = songId
        if (!::songDataProvider.isInitialized) return
        songDataProvider.playingPosition = position
        if (songId <= 0 && position >= 0 && position < songsAllItems.size) {
            currentPlayingSongId = songsAllItems[position].id
        }
        // Compose 版本不需要手动刷新
    }

    private fun updateSongsEditBar() {
        // Compose 版本：编辑状态由 Compose 状态自动管理
    }

    private fun updateSongsEditCount(count: Int) {
        // Compose 版本：编辑计数由 Compose 状态自动管理
    }

    private fun toggleSongsSearch() {
        // Compose 版本：搜索由 Compose 状态自动管理
    }

    fun enterSongsSearch() {
        // Compose 版本：搜索由 Compose 状态自动管理
        isSongsSearchMode = true
    }

    fun exitSongsSearch() {
        // Compose 版本：搜索由 Compose 状态自动管理
        isSongsSearchMode = false
    }

    private fun filterSongs(query: String) {
        // Compose 版本：过滤由 Compose 状态自动管理
    }

    private fun showSongsSortDialog() {
        // Compose 版本：排序对话框由 Compose 状态自动管理
    }

    @Suppress("unused")
    private fun showSongsSortDialogLegacy() {
        activeSongPopup?.dismiss()
        val d = density
        val isDark = com.rawsmusic.core.ui.theme.ThemeManager.isDarkMode(context)

        // 根据主题选择颜色
        val bgColor = if (isDark) 0xFF353130.toInt() else 0xFFF5DED0.toInt()
        val textColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1B1B1B.toInt()
        val dividerColor = if (isDark) 0x1AFFFFFF else 0x1A000000
        val rippleColor = if (isDark) 0x33FFFFFF else 0x33000000
        val switchThumbColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()
        val switchTrackColor = if (isDark) 0x66FFFFFF else 0x66000000

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = (16 * d).toFloat()
            }
            elevation = (8 * d).toFloat()
        }

        container.addView(TextView(context).apply {
            text = "排序方式"
            textSize = 18f
            setTextColor(textColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val pad = (20 * d).toInt()
            setPadding(pad, pad, pad, (12 * d).toInt())
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        container.addView(View(context).apply {
            setBackgroundColor(dividerColor)
        }, LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt())

        data class SongsSortOption(val label: String, val order: SongSortOrder)

        val currentOrder = AppPreferences.Sort.songSortOrder
        val options = listOf(
            SongsSortOption("标题", SongSortOrder.TITLE_ASC),
            SongsSortOption("艺术家", SongSortOrder.ARTIST_ASC),
            SongsSortOption("专辑", SongSortOrder.ALBUM_ASC),
            SongsSortOption("时长", SongSortOrder.DURATION_ASC),
            SongsSortOption("添加时间", SongSortOrder.DATE_ADDED_ASC),
            SongsSortOption("年份", SongSortOrder.YEAR_ASC)
        )

        val currentBase = when (currentOrder) {
            SongSortOrder.TITLE_ASC, SongSortOrder.TITLE_DESC -> SongSortOrder.TITLE_ASC
            SongSortOrder.ARTIST_ASC, SongSortOrder.ARTIST_DESC -> SongSortOrder.ARTIST_ASC
            SongSortOrder.ALBUM_ASC, SongSortOrder.ALBUM_DESC -> SongSortOrder.ALBUM_ASC
            SongSortOrder.DURATION_ASC, SongSortOrder.DURATION_DESC -> SongSortOrder.DURATION_ASC
            SongSortOrder.DATE_ADDED_ASC, SongSortOrder.DATE_ADDED_DESC -> SongSortOrder.DATE_ADDED_ASC
            SongSortOrder.YEAR_ASC, SongSortOrder.YEAR_DESC -> SongSortOrder.YEAR_ASC
            else -> SongSortOrder.TITLE_ASC
        }
        val isDesc = currentOrder.name.endsWith("_DESC")

        val radioButtons = mutableListOf<android.widget.RadioButton>()

        val orderSwitch = android.widget.Switch(context).apply {
            isChecked = isDesc
            thumbTintList = android.content.res.ColorStateList.valueOf(switchThumbColor)
            trackTintList = android.content.res.ColorStateList.valueOf(switchTrackColor)
        }

        options.forEach { option ->
            val itemView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = (20 * d).toInt()
                val padV = (14 * d).toInt()
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    null, null
                )
            }

            val radioButton = android.widget.RadioButton(context).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(if (isDark) 0xFFD4B896.toInt() else 0xFFC4956A.toInt())
                isChecked = currentBase == option.order
            }
            radioButtons.add(radioButton)

            itemView.addView(radioButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            itemView.addView(TextView(context).apply {
                text = option.label
                textSize = 16f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (12 * d).toInt()
                }
            })

            itemView.setOnClickListener {
                radioButtons.forEach { it.isChecked = false }
                radioButton.isChecked = true
                val targetOrder = if (orderSwitch.isChecked) {
                    SongSortOrder.entries[option.order.ordinal + 1]
                } else {
                    option.order
                }
                AppPreferences.Sort.songSortOrder = targetOrder
                onSongsSortChanged?.invoke()
                dismissSongPopup()
            }

            container.addView(itemView, LinearLayout.LayoutParams.MATCH_PARENT, (48 * d).toInt())
        }

        container.addView(View(context).apply {
            setBackgroundColor(dividerColor)
            val m = (8 * d).toInt()
            setPadding(m, 0, m, 0)
        }, LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt())

        val orderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (20 * d).toInt()
            val padV = (14 * d).toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(rippleColor),
                null, null
            )
        }

        orderRow.addView(TextView(context).apply {
            text = "降序排列"
            textSize = 16f
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        orderRow.addView(orderSwitch)

        orderRow.setOnClickListener {
            orderSwitch.isChecked = !orderSwitch.isChecked
            val selected = radioButtons.indexOfFirst { it.isChecked }
            if (selected >= 0) {
                val baseOrder = options[selected].order
                val targetOrder = if (orderSwitch.isChecked) {
                    SongSortOrder.entries[baseOrder.ordinal + 1]
                } else {
                    baseOrder
                }
                AppPreferences.Sort.songSortOrder = targetOrder
                onSongsSortChanged?.invoke()
                dismissSongPopup()
            }
        }

        container.addView(orderRow, LinearLayout.LayoutParams.MATCH_PARENT, (48 * d).toInt())

        val width = (280 * d).toInt()
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        val popup = PopupWindow(container, width, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(null)
            elevation = (8 * d).toFloat()
            animationStyle = 0
            setOnDismissListener { activeSongPopup = null }
        }

        container.alpha = 0f
        popup.showAtLocation(this, Gravity.CENTER, 0, 0)
        container.pivotX = container.width.toFloat()
        container.pivotY = 0f
        container.scaleX = 0.3f
        container.scaleY = 0.3f
        container.alpha = 0f

        android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(container, "scaleX", 0.3f, 1f),
                android.animation.ObjectAnimator.ofFloat(container, "scaleY", 0.3f, 1f),
                android.animation.ObjectAnimator.ofFloat(container, "alpha", 0f, 1f)
            )
            duration = 250
            interpolator = android.view.animation.DecelerateInterpolator(2f)
            start()
        }

        activeSongPopup = popup
    }

    private fun dismissSongPopup() {
        val popup = activeSongPopup ?: return
        val container = popup.contentView as? LinearLayout ?: run {
            popup.dismiss()
            activeSongPopup = null
            return
        }

        android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(container, "scaleX", container.scaleX, 0.3f),
                android.animation.ObjectAnimator.ofFloat(container, "scaleY", container.scaleY, 0.3f),
                android.animation.ObjectAnimator.ofFloat(container, "alpha", container.alpha, 0f)
            )
            duration = 167
            interpolator = android.view.animation.DecelerateInterpolator(2f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    popup.dismiss()
                    activeSongPopup = null
                }
            })
            start()
        }
    }

    private fun deleteSelectedSongs() {
        val selectedSongs = songDataProvider.getSelectedFiles()
        if (selectedSongs.isEmpty()) {
            Toast.makeText(context, "未选择歌曲", Toast.LENGTH_SHORT).show()
            return
        }
        var deletedCount = 0
        selectedSongs.forEach { song ->
            if (MusicRepository.deleteSongFromDevice(context, song)) {
                deletedCount++
            }
        }
        songDataProvider.exitSelectMode()
        updateSongsEditBar()
        onSongsSortChanged?.invoke() // This triggers reload
        Toast.makeText(context, "已删除 $deletedCount 首", Toast.LENGTH_SHORT).show()
    }

    private fun showSongActionPopup(song: AudioFile, position: Int) {
        activeSongPopup?.dismiss()
        val d = density

        val popupView = android.view.LayoutInflater.from(context).inflate(com.rawsmusic.core.ui.R.layout.popup_song_action, null)
        val popup = PopupWindow(popupView,
            (200 * d).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true).apply {
            elevation = 8f * d
            isOutsideTouchable = true
            setOnDismissListener { activeSongPopup = null }
        }

        popupView.findViewById<View>(com.rawsmusic.core.ui.R.id.tvPlayNext)?.setOnClickListener {
            popup.dismiss()
            onAddToQueue?.invoke(song)
            Toast.makeText(context, "已添加到下一首播放", Toast.LENGTH_SHORT).show()
        }

        popupView.findViewById<View>(com.rawsmusic.core.ui.R.id.tvAddToPlaylist)?.setOnClickListener {
            popup.dismiss()
            showAddToPlaylistDialog(song)
        }

        popupView.findViewById<View>(com.rawsmusic.core.ui.R.id.tvDelete)?.setOnClickListener {
            popup.dismiss()
            confirmDeleteSong(song)
        }

        val anchorX = (resources.displayMetrics.widthPixels - (200 * d).toInt()) / 2
        val anchorY = resources.displayMetrics.heightPixels / 3
        popup.showAtLocation(this, Gravity.NO_GRAVITY, anchorX, anchorY)
        activeSongPopup = popup
    }

    private fun confirmDeleteSong(song: AudioFile) {
        android.app.AlertDialog.Builder(context)
            .setTitle("删除歌曲")
            .setMessage("确定要删除「${song.title}」吗？此操作不可撤销。")
            .setPositiveButton("删除") { _, _ ->
                val deleted = MusicRepository.deleteSongFromDevice(context, song)
                onSongsSortChanged?.invoke() // Reload
                Toast.makeText(context, if (deleted) "已删除" else "删除失败", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showAddToPlaylistDialog(song: AudioFile) {
        val playlistStore = PlaylistStore.getInstance(context)
        val playlists = playlistStore.playlists.value
        if (playlists.isEmpty()) {
            Toast.makeText(context, "暂无歌单", Toast.LENGTH_SHORT).show()
            return
        }
        val names = playlists.map { it.name }.toTypedArray()
        android.app.AlertDialog.Builder(context)
            .setTitle("添加到歌单")
            .setItems(names) { _, which ->
                val playlist = playlists[which]
                GlobalScope.launch(Dispatchers.IO) {
                    playlistStore.addSongToPlaylist(playlist.id, song)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "已添加到「${playlist.name}」", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun categorizeForIndex(c: Char): String {
        return when {
            c in 'A'..'Z' -> c.toString()
            c in 'a'..'z' -> c.uppercaseChar().toString()
            c in '0'..'9' -> "0-9"
            c in '\u3040'..'\u309F' -> categorizeHiragana(c)
            c in '\u30A0'..'\u30FF' -> categorizeKatakana(c)
            c in '\u4E00'..'\u9FFF' -> com.rawsmusic.core.common.utils.CjkSortUtils.getPinyinInitial(c)
            else -> "#"
        }
    }

    private fun categorizeHiragana(c: Char): String {
        val groups = listOf(
            "あ" to listOf('あ','い','う','え','お'),
            "か" to listOf('か','き','く','け','こ','が','ぎ','ぐ','げ','ご'),
            "さ" to listOf('さ','し','す','せ','そ','ざ','じ','ず','ぜ','ぞ'),
            "た" to listOf('た','ち','つ','て','と','だ','ぢ','づ','で','ど'),
            "な" to listOf('な','に','ぬ','ね','の'),
            "は" to listOf('は','ひ','ふ','へ','ほ','ば','び','ぶ','べ','ぼ','ぱ','ぴ','ぷ','ぺ','ぽ'),
            "ま" to listOf('ま','み','む','め','も'),
            "や" to listOf('や','ゆ','よ'),
            "ら" to listOf('ら','り','る','れ','ろ'),
            "わ" to listOf('わ','を','ん')
        )
        for ((label, chars) in groups) { if (c in chars) return label }
        return "あ"
    }

    private fun categorizeKatakana(c: Char): String {
        val groups = listOf(
            "ア" to listOf('ア','イ','ウ','エ','オ'),
            "カ" to listOf('カ','キ','ク','ケ','コ','ガ','ギ','グ','ゲ','ゴ'),
            "サ" to listOf('サ','シ','ス','セ','ソ','ザ','ジ','ズ','ゼ','ゾ'),
            "タ" to listOf('タ','チ','ツ','テ','ト','ダ','ヂ','ヅ','デ','ド'),
            "ナ" to listOf('ナ','ニ','ヌ','ネ','ノ'),
            "ハ" to listOf('ハ','ヒ','フ','ヘ','ホ','バ','ビ','ブ','ベ','ボ','パ','ピ','プ','ペ','ポ'),
            "マ" to listOf('マ','ミ','ム','メ','モ'),
            "ヤ" to listOf('ヤ','ユ','ヨ'),
            "ラ" to listOf('ラ','リ','ル','レ','ロ'),
            "ワ" to listOf('ワ','ヲ','ン')
        )
        for ((label, chars) in groups) { if (c in chars) return label }
        return "ア"
    }

    private fun createFoldersPage(): FrameLayout {
        return createComposeFoldersPage()
    }

    /**
     * Compose 版本的文件夹页
     */
    private fun createComposeFoldersPage(): FrameLayout {
        val page = createPageContainer(NavScene.FOLDERS)
        val composeView = ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setContent {
                ComposeFoldersPageContent()
            }
        }
        page.addView(composeView)
        return page
    }

    @Composable
    private fun ComposeFoldersPageContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor(C.PAGE_BG))
        ) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { navigateHome() },
                    modifier = Modifier.size(44.dp)
                ) {
                    Text("←", fontSize = 20.sp, color = ComposeColor(C.TEXT_SECONDARY))
                }
                Text(
                    text = "文件夹",
                    color = ComposeColor(C.TEXT_PRIMARY),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.size(44.dp))
            }

            // 占位内容
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "文件夹列表\n(待集成 FolderRvAdapter)",
                    color = ComposeColor(C.TEXT_META),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    private fun showSortDialog() {
        val d = resources.displayMetrics.density
        val cr = (16 * d).toFloat()
        val isDark = com.rawsmusic.core.ui.theme.ThemeManager.isDarkMode(context)

        // 根据主题选择颜色
        val bgColor = if (isDark) 0xFF353130.toInt() else 0xFFF5DED0.toInt()
        val textColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1B1B1B.toInt()
        val dividerColor = if (isDark) 0x1AFFFFFF else 0x1A000000
        val rippleColor = if (isDark) 0x33FFFFFF else 0x33000000
        val switchThumbColor = if (isDark) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()
        val switchTrackColor = if (isDark) 0x66FFFFFF else 0x66000000

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadius = cr
            }
            elevation = (8 * d).toFloat()
        }

        // 标题
        container.addView(TextView(context).apply {
            text = "排序方式"
            textSize = 18f
            setTextColor(textColor)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val pad = (20 * d).toInt()
            setPadding(pad, pad, pad, (12 * d).toInt())
        }, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        // 分隔线
        container.addView(View(context).apply {
            setBackgroundColor(dividerColor)
        }, LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt())

        // 排序选项
        data class SortOption(val label: String, val type: SortType)

        val options = listOf(
            SortOption("标题", SortType.TITLE),
            SortOption("艺术家", SortType.ARTIST),
            SortOption("专辑", SortType.ALBUM),
            SortOption("时长", SortType.DURATION),
            SortOption("添加时间", SortType.DATE_ADDED),
            SortOption("文件名", SortType.FILENAME)
        )

        val radioButtons = mutableListOf<android.widget.RadioButton>()

        options.forEach { option ->
            val itemView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val padH = (20 * d).toInt()
                val padV = (14 * d).toInt()
                setPadding(padH, padV, padH, padV)
                isClickable = true
                isFocusable = true
                background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(rippleColor),
                    null, null
                )
            }

            val radioButton = android.widget.RadioButton(context).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(if (isDark) 0xFFD4B896.toInt() else 0xFFC4956A.toInt())
                isChecked = currentSortType == option.type
            }
            radioButtons.add(radioButton)

            itemView.addView(radioButton, LinearLayout.LayoutParams(
                (24 * d).toInt(), (24 * d).toInt()
            ))

            itemView.addView(TextView(context).apply {
                text = option.label
                textSize = 16f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (16 * d).toInt()
                }
            })

            itemView.setOnClickListener {
                radioButtons.forEach { it.isChecked = false }
                radioButton.isChecked = true
                currentSortType = option.type
                onSortChanged?.invoke(currentSortType, currentSortOrder)
                dismissPopup()
            }

            container.addView(itemView, LinearLayout.LayoutParams.MATCH_PARENT, (48 * d).toInt())
        }

        // 分隔线
        container.addView(View(context).apply {
            setBackgroundColor(dividerColor)
            val m = (8 * d).toInt()
            setPadding(m, 0, m, 0)
        }, LinearLayout.LayoutParams.MATCH_PARENT, (1 * d).toInt())

        // 升序/降序切换
        val orderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val padH = (20 * d).toInt()
            val padV = (14 * d).toInt()
            setPadding(padH, padV, padH, padV)
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(rippleColor),
                null, null
            )
        }

        orderRow.addView(TextView(context).apply {
            text = "降序排列"
            textSize = 16f
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })

        val orderSwitch = android.widget.Switch(context).apply {
            isChecked = currentSortOrder == SortOrder.DESC
            thumbTintList = android.content.res.ColorStateList.valueOf(switchThumbColor)
            trackTintList = android.content.res.ColorStateList.valueOf(switchTrackColor)
        }
        orderRow.addView(orderSwitch)

        orderRow.setOnClickListener {
            orderSwitch.isChecked = !orderSwitch.isChecked
            currentSortOrder = if (orderSwitch.isChecked) SortOrder.DESC else SortOrder.ASC
            onSortChanged?.invoke(currentSortType, currentSortOrder)
        }

        container.addView(orderRow, LinearLayout.LayoutParams.MATCH_PARENT, (48 * d).toInt())

        // 测量并显示
        val width = (260 * d).toInt()
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )

        currentPopup = android.widget.PopupWindow(container, width, LinearLayout.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(null)
            elevation = (8 * d).toFloat()
            animationStyle = 0
        }

        // 从标题栏的排序按钮位置弹出
        val page = getOrCreatePage(NavScene.SONGS)
        val header = (page as? FrameLayout)?.getChildAt(0) as? LinearLayout
        val titleRow = (header as? LinearLayout)?.getChildAt(0) as? LinearLayout
        val sortBtn = titleRow?.let { row ->
            (0 until row.childCount).map { row.getChildAt(it) }
                .filterIsInstance<ImageView>()
                .lastOrNull()
        }

        if (sortBtn != null) {
            container.alpha = 0f
            currentPopup?.showAsDropDown(sortBtn, 0, 0)
            container.pivotX = container.width.toFloat()
            container.pivotY = 0f
            container.scaleX = 0.3f
            container.scaleY = 0.3f
            container.alpha = 0f

            android.animation.AnimatorSet().apply {
                playTogether(
                    android.animation.ObjectAnimator.ofFloat(container, "scaleX", 0.3f, 1f),
                    android.animation.ObjectAnimator.ofFloat(container, "scaleY", 0.3f, 1f),
                    android.animation.ObjectAnimator.ofFloat(container, "alpha", 0f, 1f)
                )
                duration = 250
                interpolator = android.view.animation.DecelerateInterpolator(2f)
                start()
            }
        }
    }

    private var currentPopup: android.widget.PopupWindow? = null

    private fun dismissPopup() {
        val popup = currentPopup ?: return
        val container = popup.contentView as? LinearLayout ?: run {
            popup.dismiss()
            currentPopup = null
            return
        }

        android.animation.AnimatorSet().apply {
            playTogether(
                android.animation.ObjectAnimator.ofFloat(container, "scaleX", container.scaleX, 0.3f),
                android.animation.ObjectAnimator.ofFloat(container, "scaleY", container.scaleY, 0.3f),
                android.animation.ObjectAnimator.ofFloat(container, "alpha", container.alpha, 0f)
            )
            duration = 167
            interpolator = android.view.animation.DecelerateInterpolator(2f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    popup.dismiss()
                    currentPopup = null
                }
            })
            start()
        }
    }

    private fun createAlbumsPage(): FrameLayout {
        return createComposeAlbumsPage()
    }

    /**
     * Compose 版本的专辑页
     */
    private fun createComposeAlbumsPage(): FrameLayout {
        val page = createPageContainer(NavScene.ALBUMS)
        val composeView = ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setContent {
                ComposeAlbumsPageContent()
            }
        }
        page.addView(composeView)
        return page
    }

    @Composable
    private fun ComposeAlbumsPageContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor(C.PAGE_BG))
        ) {
            // 标题栏
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .padding(start = 16.dp, top = 20.dp, end = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "专辑界面",
                    color = ComposeColor(C.TEXT_PRIMARY),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // 占位内容
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "专辑列表\n(待集成 AlbumGridAdapter)",
                    color = ComposeColor(C.TEXT_META),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    private fun createArtistsPage(): FrameLayout {
        // 用 Compose 实现的艺术家列表页面 (替代旧版 RecyclerView + ArtistRvAdapter)
        return createComposeArtistsPage()
    }

    /**
     * 用 ComposeView 渲染艺术家列表
     * 数据通过 artistDataSource (含 mutableStateOf) 驱动响应式更新
     */
    private fun createComposeArtistsPage(): FrameLayout {
        val context = context
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ArtistsScreen(
                    dataSource = artistDataSource,
                    onArtistClick = { artist ->
                        navigateToArtistDetail(artist.name)
                    }
                )
            }
        }
        return FrameLayout(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(composeView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
    }

    /**
     * 用 ComposeView 渲染艺术家详情页
     * 数据通过 artistDetailDataSource 提供
     */
    private fun createArtistDetailPage(): FrameLayout {
        val context = context
        val composeView = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                ArtistDetailScreenEmbedded(
                    dataSource = artistDetailDataSource,
                    onBack = { navigateHome() },
                    onAlbumClick = { album ->
                        // 跳到专辑详情: 调用 onAlbumItemClick 走 fragment 模式
                        onAlbumItemClick?.invoke(album)
                    },
                    onPlayAll = { songs ->
                        if (songs.isEmpty()) return@ArtistDetailScreenEmbedded
                        try { onPlayQueue?.invoke(songs, 0) } catch (_: Exception) {}
                    },
                    onPlaySong = { song, songs ->
                        if (song.path.isBlank()) return@ArtistDetailScreenEmbedded
                        val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                        try { onPlayQueue?.invoke(songs, index) } catch (_: Exception) {}
                    }
                )
            }
        }
        return FrameLayout(context).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            addView(composeView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
    }

    private fun createPlaylistsPage(): FrameLayout {
        return createComposeSimplePage(NavScene.PLAYLISTS, "歌单", "歌单列表")
    }

    private fun createQueuePage(): FrameLayout {
        return createComposeSimplePage(NavScene.QUEUE, "播放队列", "当前播放队列")
    }

    private fun createRecentlyAddedPage(): FrameLayout {
        return createComposeSimplePage(NavScene.RECENTLY_ADDED, "最近添加", "最近添加的歌曲")
    }

    /**
     * Compose 版本的简单列表页 (通用)
     */
    private fun createComposeSimplePage(scene: NavScene, title: String, placeholder: String): FrameLayout {
        val page = createPageContainer(scene)
        val composeView = ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setContent {
                ComposeSimplePageContent(title, placeholder)
            }
        }
        page.addView(composeView)
        return page
    }

    @Composable
    private fun ComposeSimplePageContent(title: String, placeholder: String) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ComposeColor(C.PAGE_BG))
        ) {
            // 标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { navigateHome() },
                    modifier = Modifier.size(44.dp)
                ) {
                    Text("←", fontSize = 20.sp, color = ComposeColor(C.TEXT_SECONDARY))
                }
                Text(
                    text = title,
                    color = ComposeColor(C.TEXT_PRIMARY),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.size(44.dp))
            }

            // 占位内容
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = placeholder,
                    color = ComposeColor(C.TEXT_META),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }

    private fun createWebDavPage(): FrameLayout {
        return createComposeSimplePage(NavScene.WEBDAV, "WebDAV", "云端音乐")
    }

    private fun createPlaceholderPage(scene: NavScene): FrameLayout {
        return createComposeSimplePage(scene, scene.label, scene.label)
    }

    private fun createPageContainer(scene: NavScene): FrameLayout {
        return FrameLayout(context).apply {
            id = View.generateViewId()
            layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)
            tag = scene.tag
        }
    }

    /**
     * Compose 版本的页面创建
     * 返回一个包含 ComposeView 的 FrameLayout
     */
    private fun createComposePage(scene: NavScene): FrameLayout {
        val page = createPageContainer(scene)
        val composeView = ComposeView(context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setContent {
                ComposePageContent(scene)
            }
        }
        page.addView(composeView)
        return page
    }

    @Composable
    private fun ComposePageContent(scene: NavScene) {
        when (scene) {
            NavScene.HOME -> ComposeHomePageContent()
            NavScene.SONGS -> ComposeSongsPageContent()
            NavScene.FOLDERS -> ComposeFoldersPageContent()
            NavScene.ALBUMS -> ComposeAlbumsPageContent()
            NavScene.ARTISTS -> ArtistsScreen(
                dataSource = artistDataSource,
                onArtistClick = { artist -> navigateToArtistDetail(artist.name) }
            )
            NavScene.PLAYLISTS -> ComposeSimplePageContent("歌单", "歌单列表")
            NavScene.QUEUE -> ComposeSimplePageContent("播放队列", "当前播放队列")
            NavScene.RECENTLY_ADDED -> ComposeSimplePageContent("最近添加", "最近添加的歌曲")
            NavScene.WEBDAV -> ComposeSimplePageContent("WebDAV", "云端音乐")
            else -> ComposeSimplePageContent(scene.label, scene.label)
        }
    }

    private fun getIconRes(scene: NavScene): Int {
        return when (scene) {
            NavScene.SONGS -> com.rawsmusic.core.ui.R.drawable.ic_music_note
            NavScene.FOLDERS -> com.rawsmusic.core.ui.R.drawable.ic_folder
            NavScene.FOLDER_HIERARCHY -> com.rawsmusic.core.ui.R.drawable.ic_folder_open
            NavScene.ALBUMS -> com.rawsmusic.core.ui.R.drawable.ic_album
            NavScene.PLAYLISTS -> com.rawsmusic.core.ui.R.drawable.ic_queue_music
            NavScene.ARTISTS -> com.rawsmusic.core.ui.R.drawable.ic_person
            NavScene.QUEUE -> com.rawsmusic.core.ui.R.drawable.ic_playlist_play
            NavScene.RECENTLY_ADDED -> com.rawsmusic.core.ui.R.drawable.ic_schedule
            NavScene.WEBDAV -> com.rawsmusic.core.ui.R.drawable.ic_cloud
            else -> com.rawsmusic.core.ui.R.drawable.ic_music_note
        }
    }

    /**
     * 更新主题颜色（默认背景模式切换时调用）
     */
    fun updateThemeColors(isDark: Boolean, useDefaultBackground: Boolean = false) {
        C.updateForTheme(isDark)
        if (!useDefaultBackground) {
            C.PAGE_BG = 0x00000000
        }
        UC.updateForTheme(isDark)
    }

    /**
     * 刷新所有已创建页面的背景色（updateThemeColors 后调用）
     */
    fun refreshPageBackgrounds() {
        for ((_, page) in pageCache) {
            page.setBackgroundColor(C.PAGE_BG)
        }
    }

    fun submitSongs(songs: List<AudioFile>) {
        AppLogger.d("UnifiedMainContainer", "submitSongs: ${songs.size} songs")
        songsAllItems = songs
        // Compose 版本：数据由 Compose 状态自动管理
    }

    fun submitFolders(folders: List<Folder>) {
        // Compose 版本：数据由 Compose 状态自动管理
    }

    fun submitAlbums(albums: List<Album>) {
        // Compose 版本：数据由 Compose 状态自动管理
    }

    fun submitArtists(artists: List<Artist>) {
        // 用 Compose 页面: 触发 ARTISTS 页面的 ComposeView 重新加载数据
        val page = getOrCreatePage(NavScene.ARTISTS)
        artistDataSource.setData(artists)
    }

    /**
     * 提交艺术家详情数据并跳转到详情页
     * 通过名字统一识别艺术家
     */
    fun navigateToArtistDetail(artistName: String) {
        // 加载详情数据
        val allSongs = MusicRepository.getAllSongs()
        val songs = allSongs.filter { it.artist == artistName }
        val albums = MusicRepository.albums.value.filter { it.artist == artistName }
        artistDetailDataSource.setData(artistName, songs, albums)
        // 注册 ARTIST_DETAIL 页面 factory (首次跳转时注册)
        if (pageFactories[NavScene.ARTIST_DETAIL] == null) {
            pageFactories[NavScene.ARTIST_DETAIL] = { createArtistDetailPage() }
        }
        navigateTo(NavScene.ARTIST_DETAIL, artistName)
    }

    fun submitPlaylists(playlists: List<Playlist>) {
        // Compose 版本：数据由 Compose 状态自动管理
    }

    fun submitQueueSongs(songs: List<AudioFile>) {
        // Compose 版本：数据由 Compose 状态自动管理
    }

    fun submitRecentlyAdded(songs: List<AudioFile>) {
        // Compose 版本：数据由 Compose 状态自动管理
    }

    private val density: Float get() = resources.displayMetrics.density

    // ==================== Compose 场景过渡方法 ====================

    /**
     * Compose 版本：导航到指定场景 (带动画)
     * 使用 Compose 的 Animatable 驱动过渡动画
     */
    fun composeNavigateToScene(
        targetScene: NavScene,
        duration: Long = 400L,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        if (composeIsTransitioning) return
        if (targetScene == composeCurrentScene) return

        composeIsTransitioning = true
        composeTransitionProgress = 0f

        // 同步到 View 系统的 sceneController
        val fromPage = getPage(composeCurrentScene)
        val toPage = getOrCreatePage(targetScene)
        val direction = when {
            targetScene == NavScene.HOME -> SceneController.TransitionDirection.BACKWARD
            composeCurrentScene == NavScene.HOME -> SceneController.TransitionDirection.FORWARD
            targetScene.ordinal > composeCurrentScene.ordinal -> SceneController.TransitionDirection.FORWARD
            else -> SceneController.TransitionDirection.BACKWARD
        }

        // 使用 Animatable 驱动进度
        val animatable = Animatable(0f)
        scope.launch {
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = duration.toInt(),
                    easing = LinearEasing
                )
            ) {
                composeTransitionProgress = value
            }

            // 动画完成
            composeCurrentScene = targetScene
            _currentSceneFlow.value = targetScene
            composeIsTransitioning = false
            composeTransitionProgress = 0f
        }
    }

    /**
     * Compose 版本：静默切换场景 (无动画)
     */
    fun composeSwitchToSceneSilent(targetScene: NavScene) {
        composeCurrentScene = targetScene
        _currentSceneFlow.value = targetScene
        composeIsTransitioning = false
        composeTransitionProgress = 0f
    }

    /**
     * Compose 版本：开始拖拽返回手势
     */
    fun composeStartDragBack() {
        composeIsDraggingBack = true
        composeDragBackProgress = 0f
    }

    /**
     * Compose 版本：更新拖拽返回进度
     * @param ratio 0..1 的进度值
     */
    fun composeUpdateDragBack(ratio: Float) {
        composeDragBackProgress = ratio.coerceIn(0f, 1f)
    }

    /**
     * Compose 版本：结束拖拽返回手势
     * @param shouldCommit 是否应该执行返回
     * @param scope 协程作用域
     */
    fun composeEndDragBack(
        shouldCommit: Boolean,
        duration: Long = 300L,
        scope: kotlinx.coroutines.CoroutineScope
    ) {
        if (!composeIsDraggingBack) return

        val targetRatio = if (shouldCommit) 1f else 0f
        val animatable = Animatable(composeDragBackProgress)

        scope.launch {
            animatable.animateTo(
                targetValue = targetRatio,
                animationSpec = tween(
                    durationMillis = duration.toInt(),
                    easing = LinearEasing
                )
            ) {
                composeDragBackProgress = value
            }

            composeIsDraggingBack = false

            if (shouldCommit) {
                // 执行返回操作
                val previousScene = getPreviousScene()
                if (previousScene != null) {
                    composeSwitchToSceneSilent(previousScene)
                }
            }
            composeDragBackProgress = 0f
        }
    }

    /**
     * 获取上一个场景 (用于返回手势)
     */
    private fun getPreviousScene(): NavScene? {
        return when (composeCurrentScene) {
            NavScene.HOME -> null
            NavScene.SONGS -> NavScene.HOME
            NavScene.FOLDERS -> NavScene.HOME
            NavScene.ALBUMS -> NavScene.HOME
            NavScene.ARTISTS -> NavScene.HOME
            NavScene.PLAYLISTS -> NavScene.HOME
            NavScene.QUEUE -> NavScene.HOME
            NavScene.RECENTLY_ADDED -> NavScene.HOME
            NavScene.WEBDAV -> NavScene.HOME
            else -> NavScene.HOME
        }
    }

    // ==================== Compose StateAnim 系统 ====================

    /** 存储正在运行的 Compose 状态动画 Job */
    private val composeStateAnimMap = mutableMapOf<String, kotlinx.coroutines.Job>()

    /**
     * Compose 版本：启动状态动画
     * 用于按钮点击、弹性回弹等动画效果
     */
    fun composeStartStateAnim(
        key: String,
        initialValue: Float = 0f,
        targetValue: Float = 1f,
        durationMs: Long = 300L,
        easing: Easing = LinearEasing,
        onCancel: (() -> Unit)? = null,
        onEnd: (() -> Unit)? = null,
        onUpdate: (Float) -> Unit
    ) {
        composeCancelStateAnim(key)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        val job = scope.launch {
            val animatable = Animatable(initialValue)
            animatable.animateTo(
                targetValue = targetValue,
                animationSpec = tween(
                    durationMillis = durationMs.toInt(),
                    easing = easing
                )
            ) {
                onUpdate(value)
            }
            onEnd?.invoke()
        }
        job.invokeOnCompletion {
            if (job.isCancelled) onCancel?.invoke()
        }
        composeStateAnimMap[key] = job
    }

    /**
     * Compose 版本：取消指定状态动画
     */
    fun composeCancelStateAnim(key: String) {
        composeStateAnimMap[key]?.cancel()
        composeStateAnimMap.remove(key)
    }

    /**
     * Compose 版本：取消所有状态动画
     */
    fun composeCancelAllStateAnims() {
        composeStateAnimMap.values.forEach { it.cancel() }
        composeStateAnimMap.clear()
    }

    // ==================== Compose 便捷导航方法 ====================

    /**
     * Compose 版本：导航到歌曲列表
     */
    fun composeNavigateToSongs(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.SONGS, scope = scope)
    }

    /**
     * Compose 版本：导航到文件夹
     */
    fun composeNavigateToFolders(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.FOLDERS, scope = scope)
    }

    /**
     * Compose 版本：导航到专辑
     */
    fun composeNavigateToAlbums(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.ALBUMS, scope = scope)
    }

    /**
     * Compose 版本：导航到艺术家
     */
    fun composeNavigateToArtists(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.ARTISTS, scope = scope)
    }

    /**
     * Compose 版本：导航到歌单
     */
    fun composeNavigateToPlaylists(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.PLAYLISTS, scope = scope)
    }

    /**
     * Compose 版本：导航到播放队列
     */
    fun composeNavigateToQueue(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.QUEUE, scope = scope)
    }

    /**
     * Compose 版本：导航到最近添加
     */
    fun composeNavigateToRecentlyAdded(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.RECENTLY_ADDED, scope = scope)
    }

    /**
     * Compose 版本：导航到 WebDAV
     */
    fun composeNavigateToWebDav(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.WEBDAV, scope = scope)
    }

    /**
     * Compose 版本：导航到专辑详情
     */
    fun composeNavigateToAlbumDetail(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.ALBUM_DETAIL, scope = scope)
    }

    /**
     * Compose 版本：导航到艺术家详情
     */
    fun composeNavigateToArtistDetail(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.ARTIST_DETAIL, scope = scope)
    }

    /**
     * Compose 版本：导航到歌单详情
     */
    fun composeNavigateToPlaylistDetail(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.PLAYLIST_DETAIL, scope = scope)
    }

    /**
     * Compose 版本：返回主页
     */
    fun composeBackToHome(scope: kotlinx.coroutines.CoroutineScope) {
        composeNavigateToScene(NavScene.HOME, scope = scope)
    }

    /**
     * Compose 版本：返回上一页
     */
    fun composeGoBack(scope: kotlinx.coroutines.CoroutineScope) {
        val previousScene = getPreviousScene()
        if (previousScene != null) {
            composeNavigateToScene(previousScene, scope = scope)
        }
    }

    // ==================== Compose UI 组件 ====================

    /**
     * Compose 版本的场景导航宿主
     * 可在 Compose 环境中使用 UnifiedMainContainer 的场景管理
     */
    @Composable
    fun ComposeNavHost(
        modifier: Modifier = Modifier,
        sceneContent: @Composable (NavScene) -> Unit
    ) {
        val currentScene = composeCurrentScene
        val isTransitioning = composeIsTransitioning
        val transitionProgress = composeTransitionProgress

        Box(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 过渡时的透明度变化
                    alpha = if (isTransitioning) {
                        1f - transitionProgress * 0.3f
                    } else {
                        1f
                    }
                }
        ) {
            sceneContent(currentScene)
        }
    }

    /**
     * Compose 版本的拖拽返回手势容器
     */
    @Composable
    fun ComposeDragBackContainer(
        modifier: Modifier = Modifier,
        content: @Composable () -> Unit
    ) {
        val scope = rememberCoroutineScope()
        val isDraggingBack = composeIsDraggingBack
        val dragBackProgress = composeDragBackProgress

        Box(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    if (isDraggingBack) {
                        val scale = 1f - (dragBackProgress * 0.1f)
                        scaleX = scale
                        scaleY = scale
                    }
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            composeStartDragBack()
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val progress = (dragAmount / size.width).coerceIn(0f, 1f)
                            composeUpdateDragBack(progress)
                        },
                        onDragEnd = {
                            val shouldCommit = composeDragBackProgress > 0.3f
                            composeEndDragBack(shouldCommit, scope = scope)
                        }
                    )
                }
        ) {
            content()
        }
    }

    companion object {
        private const val MATCH_PARENT = LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = LayoutParams.WRAP_CONTENT
        private const val SONGS_RETURN_TARGET_START_SCALE = 0.92f
    }

    private object C {
        var PAGE_BG = 0x00000000 // 透明背景，让底层模糊背景可见
        var NAV_BG = 0xE8343434.toInt()
        var CARD_BG = 0xFF353130.toInt()
        var TEXT_PRIMARY = 0xFFFFFFFF.toInt()
        var TEXT_SECONDARY = 0xCCFFFFFF.toInt()
        var TEXT_META = 0xFF928A86.toInt()
        var ACCENT = 0xFFD4B896.toInt()
        var DIVIDER = 0x1AFFFFFF

        fun updateForTheme(isDark: Boolean) {
            PAGE_BG = if (isDark) 0x00000000 else 0xFFFFFFFF.toInt()
            NAV_BG = if (isDark) 0xE8393331.toInt() else 0xFFF0EEEC.toInt()
            CARD_BG = if (isDark) 0xFF353130.toInt() else 0xFFF5DED0.toInt()
            TEXT_PRIMARY = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1B1B1B.toInt()
            TEXT_SECONDARY = if (isDark) 0xCCFFFFFF.toInt() else 0xCC1B1B1B.toInt()
            TEXT_META = if (isDark) 0xFF928A86.toInt() else 0xFF857367.toInt()
            ACCENT = if (isDark) 0xFFD4B896.toInt() else 0xFFC4956A.toInt()
            DIVIDER = if (isDark) 0x1AFFFFFF else 0x1A000000
        }
    }
}

class SongRvAdapter(
    private val onClick: ((AudioFile, Int) -> Unit)?
) : RecyclerView.Adapter<SongRvAdapter.ViewHolder>() {

    private var items: List<AudioFile> = emptyList()
    private var allItems: List<AudioFile> = emptyList()

    fun submitList(newItems: List<AudioFile>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun getAllItems(): List<AudioFile> = allItems

    fun setAllItems(allItems: List<AudioFile>) {
        this.allItems = allItems
        submitList(allItems)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = SongItemView(parent.context)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        (holder.itemView as SongItemView).bind(item, position)
        holder.itemView.setOnClickListener { onClick?.invoke(item, position) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}

class FolderRvAdapter(
    private val onClick: ((Folder) -> Unit)?
) : RecyclerView.Adapter<FolderRvAdapter.ViewHolder>() {

    private var items: List<Folder> = emptyList()

    fun submitList(newItems: List<Folder>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = FolderItemView(parent.context)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        (holder.itemView as FolderItemView).bind(item) { onClick?.invoke(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}

class AlbumGridAdapter(
    private val onClick: ((Album) -> Unit)?
) : RecyclerView.Adapter<AlbumGridAdapter.ViewHolder>() {

    private var items: List<Album> = emptyList()
    private var allItems: List<Album> = emptyList()

    fun submitList(newItems: List<Album>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun getAllItems(): List<Album> = allItems

    fun setAllItems(allItems: List<Album>) {
        this.allItems = allItems
        submitList(allItems)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = AlbumGridItemView(parent.context)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        (holder.itemView as AlbumGridItemView).bind(item) { onClick?.invoke(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}

class PlaylistRvAdapter(
    private val onClick: ((Playlist) -> Unit)?
) : RecyclerView.Adapter<PlaylistRvAdapter.ViewHolder>() {

    private var items: List<Playlist> = emptyList()
    private var allItems: List<Playlist> = emptyList()

    fun submitList(newItems: List<Playlist>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun getAllItems(): List<Playlist> = allItems

    fun setAllItems(allItems: List<Playlist>) {
        this.allItems = allItems
        submitList(allItems)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = PlaylistItemView(parent.context)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        (holder.itemView as PlaylistItemView).bind(item) { onClick?.invoke(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}

internal class SongItemView(context: Context) : LinearLayout(context) {

    private val coverView: ImageView
    private val titleView: TextView
    private val subtitleView: TextView
    private val metaView: TextView

    init {
        orientation = HORIZONTAL
        val d = resources.displayMetrics.density
        val pad = (12 * d).toInt()
        setPadding(pad, (pad * 0.6).toInt(), pad, (pad * 0.6).toInt())

        coverView = ImageView(context).apply {
            layoutParams = LayoutParams((48 * d).toInt(), (48 * d).toInt()).apply {
                marginEnd = (12 * d).toInt()
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        addView(coverView)

        val textContainer = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.CENTER_VERTICAL
        }

        titleView = TextView(context).apply { textSize = 15f; setTextColor(UC.TEXT_PRIMARY); maxLines = 1 }
        textContainer.addView(titleView)

        subtitleView = TextView(context).apply { textSize = 13f; setTextColor(UC.TEXT_SECONDARY); maxLines = 1 }
        textContainer.addView(subtitleView)

        metaView = TextView(context).apply { textSize = 11f; setTextColor(UC.TEXT_META); maxLines = 1 }
        textContainer.addView(metaView)

        addView(textContainer)
    }

    fun bind(song: AudioFile, position: Int) {
        titleView.text = song.displayName
        subtitleView.text = song.artist
        metaView.text = AudioUtils.formatDuration(song.duration) + " · " + song.format + " · " + (song.bitRate / 1000) + "kbps"
        if (song.albumArtPath.isNotBlank()) {
            BitmapProvider.load(
                key = song.albumArtPath,
                imageView = coverView,
                targetWidth = coverView.width.coerceAtLeast(512),
                targetHeight = coverView.height.coerceAtLeast(512)
            )
        } else {
            coverView.setImageResource(android.R.drawable.ic_menu_gallery)
        }
    }
}

// ============================================================================
// 艺术家 Compose 页面 (替代旧版 Fragment + RecyclerView 实现)
// ============================================================================

/** 艺术家列表数据源 (含 mutableStateOf, 跨类传递 Compose 状态) */
class ArtistComposeDataSource {
    var artists: List<Artist> by mutableStateOf(emptyList())
        private set

    fun setData(list: List<Artist>) {
        artists = list
    }
}

/** 艺术家详情数据源 */
class ArtistDetailComposeDataSource {
    var artistName: String by mutableStateOf("")
        private set
    var songs: List<AudioFile> by mutableStateOf(emptyList())
        private set
    var albums: List<Album> by mutableStateOf(emptyList())
        private set

    fun setData(name: String, songs: List<AudioFile>, albums: List<Album>) {
        this.artistName = name
        this.songs = songs
        this.albums = albums
    }

    fun clear() {
        artistName = ""
        songs = emptyList()
        albums = emptyList()
    }
}

/** 简化的主题色 (独立于 app 模块的 ThemeColors) */
internal data class ArtistThemeColors(
    val background: ComposeColor,
    val surface: ComposeColor,
    val onSurface: ComposeColor,
    val primary: ComposeColor,
    val secondaryText: ComposeColor,
    val outline: ComposeColor
)

@Composable
internal fun artistThemeColors(): ArtistThemeColors {
    val context = LocalContext.current
    val isDark = com.rawsmusic.core.ui.theme.ThemeManager.isDarkMode(context)
    return if (isDark) {
        ArtistThemeColors(
            background = ComposeColor(0xFF2A2624),
            surface = ComposeColor(0xFF353130),
            onSurface = ComposeColor(0xFFEFEFEF),
            primary = ComposeColor(0xFFD4B896),
            secondaryText = ComposeColor(0xFF928A86),
            outline = ComposeColor(0xFF9F8D80)
        )
    } else {
        ArtistThemeColors(
            background = ComposeColor(0xFFF7F6F4),
            surface = ComposeColor(0xFFF5DED0),
            onSurface = ComposeColor(0xFF1F1F1F),
            primary = ComposeColor(0xFFC4956A),
            secondaryText = ComposeColor(0xFF857367),
            outline = ComposeColor(0xFFBAB3AF)
        )
    }
}

/** 艺术家列表 Composable (移植自 ArtistsFragment, 移除 triggerSearch) */
@Composable
fun ArtistsScreen(
    dataSource: ArtistComposeDataSource,
    onArtistClick: (Artist) -> Unit
) {
    val colors = artistThemeColors()
    val artists = dataSource.artists
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    val filteredArtists = if (searchQuery.isBlank()) {
        artists
    } else {
        val lowerQuery = searchQuery.lowercase()
        artists.filter { artist -> artist.name.lowercase().contains(lowerQuery) }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // 标题栏
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSearchActive) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .background(colors.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(fontSize = 15.sp, color = colors.onSurface),
                            cursorBrush = SolidColor(colors.primary),
                            decorationBox = { innerTextField ->
                                Box {
                                    if (searchQuery.isEmpty()) {
                                        Text("搜索艺术家", fontSize = 15.sp, color = colors.outline)
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "取消", fontSize = 14.sp, color = colors.primary,
                            modifier = Modifier.clickable {
                                isSearchActive = false
                                searchQuery = ""
                            }
                        )
                    }
                }
            } else {
                Text("艺术家", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                Text(
                    "搜索", fontSize = 14.sp, color = colors.primary,
                    modifier = Modifier.clickable { isSearchActive = true }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 艺术家列表
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            if (filteredArtists.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (searchQuery.isBlank()) "暂无艺术家" else "未找到匹配的艺术家",
                            fontSize = 14.sp, color = colors.secondaryText
                        )
                    }
                }
            } else {
                items(filteredArtists, key = { it.name }) { artist ->
                    ArtistRow(artist, colors, onArtistClick)
                }
            }
            item { Spacer(Modifier.height(160.dp)) }
        }
    }
}

@Composable
private fun ArtistRow(
    artist: Artist,
    colors: ArtistThemeColors,
    onClick: (Artist) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick(artist) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (artist.coverPath.isNotBlank()) {
            BitmapImage(
                key = artist.coverPath,
                contentDescription = artist.name,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                targetWidth = 256,
                targetHeight = 256
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                artist.name.ifBlank { "未知艺术家" },
                fontSize = 16.sp, fontWeight = FontWeight.Medium,
                color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "${artist.songCount} 首歌曲 · ${artist.albumCount} 张专辑",
                fontSize = 13.sp, color = colors.secondaryText
            )
        }
    }
}

/** 艺术家详情 Composable */
@Composable
fun ArtistDetailScreenEmbedded(
    dataSource: ArtistDetailComposeDataSource,
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlayAll: (List<AudioFile>) -> Unit,
    onPlaySong: (AudioFile, List<AudioFile>) -> Unit
) {
    val context = LocalContext.current
    val colors = artistThemeColors()
    val isDark = com.rawsmusic.core.ui.theme.ThemeManager.isDarkMode(context)
    val name = dataSource.artistName
    val songs = dataSource.songs
    val albums = dataSource.albums

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = if (isDark) listOf(
                            ComposeColor(0xFF2A2624), ComposeColor(0xFF353130)
                        ) else listOf(
                            ComposeColor(0xFFF7F6F4), ComposeColor(0xFFF5DED0),
                            ComposeColor(0xFFFFDCC4).copy(alpha = 0.28f),
                            ComposeColor(0xFFC4956A).copy(alpha = 0.10f)
                        )
                    )
                )
        )

        LazyColumn(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(16.dp)) }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onBack) {
                        Text("← 返回", color = colors.primary, fontSize = 16.sp)
                    }
                    Text(
                        name, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                        color = colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 标题卡片 + 播放全部
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.surface, RoundedCornerShape(16.dp))
                        .padding(16.dp)
                ) {
                    Text(name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${songs.size} 首歌曲 · ${albums.size} 张专辑",
                        fontSize = 14.sp, color = colors.secondaryText
                    )
                    if (songs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick = { onPlayAll(songs) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("▶ 播放全部", color = colors.primary, fontSize = 15.sp)
                        }
                    }
                }
            }

            // 专辑横排
            if (albums.isNotEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text("专辑", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                        Spacer(Modifier.height(12.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(albums) { album ->
                                AlbumItem(album, colors, onClick = { onAlbumClick(album) })
                            }
                        }
                    }
                }
            }

            // 歌曲列表 (完整, 不再 take(20))
            if (songs.isNotEmpty()) {
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.surface, RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text("歌曲", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
                        Spacer(Modifier.height(8.dp))
                        for (song in songs) {
                            TextButton(
                                onClick = { onPlaySong(song, songs) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        song.displayName, fontSize = 14.sp, color = colors.onSurface,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        song.album.ifBlank { "未知专辑" },
                                        fontSize = 12.sp, color = colors.secondaryText,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AlbumItem(
    album: Album,
    colors: ArtistThemeColors,
    onClick: () -> Unit
) {
    val coverSize = 120.dp
    Column(
        modifier = Modifier
            .width(coverSize)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (album.coverPath.isNotBlank()) {
            BitmapImage(
                key = album.coverPath,
                contentDescription = album.name,
                modifier = Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop,
                targetWidth = 512,
                targetHeight = 512
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            album.name.ifBlank { "未知专辑" },
            fontSize = 12.sp, color = colors.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
        )
        if (album.year > 0) {
            Text("${album.year}", fontSize = 11.sp, color = colors.secondaryText)
        }
    }
}

internal class FolderItemView(context: Context) : LinearLayout(context) {

    private val nameView: TextView
    private val infoView: TextView

    init {
        orientation = VERTICAL
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        setPadding(pad, pad, pad, pad)
        nameView = TextView(context).apply { textSize = 16f; setTextColor(UC.TEXT_PRIMARY); maxLines = 1 }
        addView(nameView)
        infoView = TextView(context).apply { textSize = 13f; setTextColor(UC.TEXT_META); maxLines = 2 }
        addView(infoView)
    }

    fun bind(folder: Folder, onClick: () -> Unit) {
        nameView.text = folder.name
        infoView.text = folder.path + " · " + folder.songCount + "首"
        setOnClickListener { onClick() }
    }
}

internal class AlbumGridItemView(context: Context) : LinearLayout(context) {

    private val coverView: ImageView
    private val hiresBadge: ImageView
    private val nameView: TextView
    private val artistView: TextView

    init {
        orientation = VERTICAL
        val d = resources.displayMetrics.density
        val pad = (8 * d).toInt()
        setPadding(pad, pad, pad, pad)

        // 封面容器
        val coverContainer = FrameLayout(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, (142 * d).toInt())
        }

        coverView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        coverContainer.addView(coverView)

        // HiRes 徽章
        hiresBadge = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, (22 * d).toInt()
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                marginEnd = (8 * d).toInt()
                bottomMargin = (4 * d).toInt()
            }
            adjustViewBounds = true
            visibility = GONE
            try {
                setImageResource(context.resources.getIdentifier("ic_hires_small", "drawable", context.packageName))
            } catch (_: Exception) {}
        }
        coverContainer.addView(hiresBadge)
        addView(coverContainer)

        // 专辑名
        nameView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (6 * d).toInt()
            }
            textSize = 13f
            setTextColor(UC.TEXT_PRIMARY)
            maxLines = 1
            isBold()
        }
        addView(nameView)

        // 艺术家
        artistView = TextView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            textSize = 11f
            setTextColor(UC.TEXT_META)
            maxLines = 1
        }
        addView(artistView)
    }

    fun bind(album: Album, onClick: () -> Unit) {
        nameView.text = album.name
        artistView.text = album.artist.ifBlank { "Unknown Artist" }
        if (album.coverPath.isNotBlank()) {
            val sizePx = (142 * resources.displayMetrics.density).toInt()
            BitmapProvider.load(album.coverPath, coverView, sizePx, sizePx)
        } else {
            coverView.setImageResource(android.R.drawable.ic_menu_gallery)
        }
        hiresBadge.visibility = if (album.hasHiRes) VISIBLE else GONE
        setOnClickListener { onClick() }
    }

    private fun TextView.isBold() {
        paint.isFakeBoldText = true
    }
}

internal class PlaylistItemView(context: Context) : LinearLayout(context) {

    private val nameView: TextView
    private val infoView: TextView

    init {
        orientation = VERTICAL
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        setPadding(pad, pad, pad, pad)
        nameView = TextView(context).apply { textSize = 16f; setTextColor(UC.TEXT_PRIMARY); maxLines = 1 }
        addView(nameView)
        infoView = TextView(context).apply { textSize = 13f; setTextColor(UC.TEXT_META); maxLines = 1 }
        addView(infoView)
    }

    fun bind(playlist: Playlist, onClick: () -> Unit) {
        nameView.text = playlist.name
        infoView.text = playlist.songCount.toString() + "首"
        setOnClickListener { onClick() }
    }
}

private object UC {
    var PAGE_BG = 0xFF2A2624.toInt()
    var CARD_BG = 0xFF353130.toInt()
    var TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    var TEXT_SECONDARY = 0xCCFFFFFF.toInt()
    var TEXT_META = 0xFF928A86.toInt()
    var ACCENT = 0xFFD4B896.toInt()

    fun updateForTheme(isDark: Boolean) {
        PAGE_BG = if (isDark) 0xFF2A2624.toInt() else 0xFFF7F6F4.toInt()
        CARD_BG = if (isDark) 0xFF353130.toInt() else 0xFFF5DED0.toInt()
        TEXT_PRIMARY = if (isDark) 0xFFFFFFFF.toInt() else 0xFF1B1B1B.toInt()
        TEXT_SECONDARY = if (isDark) 0xCCFFFFFF.toInt() else 0xCC1B1B1B.toInt()
        TEXT_META = if (isDark) 0xFF928A86.toInt() else 0xFF857367.toInt()
        ACCENT = if (isDark) 0xFFD4B896.toInt() else 0xFFC4956A.toInt()
    }
}

