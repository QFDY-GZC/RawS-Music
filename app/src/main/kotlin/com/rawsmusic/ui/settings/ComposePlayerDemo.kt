package com.rawsmusic.ui.settings

import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.*
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.liquidGlass
import com.kyant.backdrop.effects.vibrancy
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.*

// ============================================================================
// 1. Scene Enum
// ============================================================================

enum class DemoScene(val label: String) {
    MAIN("首页"), PLAYER("播放器"), LYRIC("歌词"), QUEUE("队列")
}

// ============================================================================
// 2. Display Mode — 6 levels matching PowerListDisplayMode
// ============================================================================

enum class DemoDisplayMode(
    val columns: Int,
    val isGrid: Boolean,
    val label: String,
    val params: DemoZoomParams
) {
    LIST_SMALL(1, false, "小", DemoZoomParams(32f, 55f, 8f, 0.85f, line2Visible = true, metaInlineFraction = 1f)),
    LIST_NORMAL(1, false, "标准", DemoZoomParams(80f, 96f, 18f, 0.9f, line2Visible = false, metaInlineFraction = 0f)),
    LIST_ZOOMED(1, false, "大", DemoZoomParams(120f, 137f, 24f, 1.0f, line2Visible = true, metaInlineFraction = 0f)),
    GRID_4(4, true, "4列", DemoZoomParams(-1f, 0f, 16f, 0.85f, line2Visible = true, metaInlineFraction = 0f)),
    GRID_3(3, true, "3列", DemoZoomParams(-1f, 0f, 16f, 0.85f, line2Visible = true, metaInlineFraction = 0f)),
    GRID_2(2, true, "2列", DemoZoomParams(-1f, 0f, 16f, 0.85f, line2Visible = true, metaInlineFraction = 0f));

    companion object {
        val velocityThreshold = 500f   // dp/s
        val positionThreshold = 0.3f
        val snapDurationMs = 500L
        val boundaryElasticDurationMs = 350L
    }
}

// ============================================================================
// 3. ZoomParams data class (matching ListZoomParams)
// ============================================================================

data class DemoZoomParams(
    val coverSizeDp: Float,
    val rowHeightDp: Float,
    val cornerRadiusDp: Float,
    val textScale: Float,
    val line2Visible: Boolean,
    val metaInlineFraction: Float // 1f=内联, 0f=堆叠
)

// ============================================================================
// 4. Gesture State
// ============================================================================

enum class GestureState { IDLE, PINCHING, PENDING_GRID, GRID_PINCHING }

// ============================================================================
// 5. Track Data
// ============================================================================

data class DemoTrack(val title: String, val artist: String, val duration: String)

private val sampleTracks = listOf(
    DemoTrack("夜曲", "周杰伦", "4:23"),
    DemoTrack("晴天", "周杰伦", "4:29"),
    DemoTrack("稻香", "周杰伦", "3:43"),
    DemoTrack("青花瓷", "周杰伦", "3:59"),
    DemoTrack("七里香", "周杰伦", "4:59"),
    DemoTrack("以父之名", "周杰伦", "5:41"),
    DemoTrack("简单爱", "周杰伦", "4:30"),
    DemoTrack("双截棍", "周杰伦", "3:13"),
    DemoTrack("告白气球", "周杰伦", "3:35"),
    DemoTrack("等你下课", "周杰伦", "3:55"),
    DemoTrack("起风了", "买辣椒也用券", "5:12"),
    DemoTrack("光年之外", "邓紫棋", "3:56"),
    DemoTrack("泡沫", "邓紫棋", "4:15"),
    DemoTrack("倒数", "邓紫棋", "3:47"),
    DemoTrack("句号", "邓紫棋", "3:42"),
    DemoTrack("平凡之路", "朴树", "4:46"),
    DemoTrack("那些花儿", "朴树", "3:53"),
    DemoTrack("红色高跟鞋", "蔡健雅", "4:01"),
    DemoTrack("达尔文", "蔡健雅", "4:20"),
    DemoTrack("Let It Go", "Idina Menzel", "3:44")
)

// ============================================================================
// 6. Poweramp Easing Functions — exact port from ListZoomManager
// ============================================================================

private fun powerampEasing(d: Float): Float {
    return when {
        d > 0f -> {
            val clamped = (d.toDouble().coerceAtMost(3.0) / 3.0).coerceIn(0.0, 1.0)
            (sqrt(clamped * 0.2) * 0.2).toFloat()
        }
        d < 0f -> {
            val mapped = (1.0 - (d.toDouble() + 1.0).coerceIn(0.1, 1.0)) / 0.9
            -(sqrt(mapped.coerceIn(0.0, 1.0) * 0.2) * 0.2).toFloat()
        }
        else -> 0f
    }
}

/** 过渡中的弹性缩放因子，匹配原版 powerampElasticScale */
private fun powerampElasticScale(signedDelta: Float, isZoomIn: Boolean): Float {
    if (signedDelta in 0f..1f) return 1f
    val beyond = if (signedDelta > 1f) signedDelta - 1f else -signedDelta
    val eased = abs(powerampEasing(beyond))
    return (if ((signedDelta > 1f) == isZoomIn) 1f + eased else 1f - eased).coerceIn(0.85f, 1.15f)
}

/** 边界弹性缩放因子，匹配原版 boundaryElasticScale */
private fun boundaryElasticScale(overPull: Float, expands: Boolean): Float {
    val eased = abs(powerampEasing(overPull))
    return (if (expands) 1f + eased else 1f - eased).coerceIn(0.85f, 1.15f)
}

/** computeBoundaryElasticScale — same as boundaryElasticScale with coerceIn(0.85, 1.15) */
private fun computeBoundaryElasticScale(overPull: Float, expands: Boolean): Float =
    boundaryElasticScale(overPull, expands)

/** AccelerateDecelerateInterpolator equivalent (cosine easing) */
private val AccelerateDecelerateEasing = Easing { fraction ->
    ((cos((fraction.toDouble() + 1.0) * PI) / 2.0 + 0.5)).toFloat()
}

// ============================================================================
// 7. Zoom Params Interpolation
// ============================================================================

private fun lerpZoomParams(from: DemoZoomParams, to: DemoZoomParams, fraction: Float): DemoZoomParams {
    val t = fraction.coerceIn(0f, 1f)
    return DemoZoomParams(
        coverSizeDp = from.coverSizeDp + (to.coverSizeDp - from.coverSizeDp) * t,
        rowHeightDp = from.rowHeightDp + (to.rowHeightDp - from.rowHeightDp) * t,
        cornerRadiusDp = from.cornerRadiusDp + (to.cornerRadiusDp - from.cornerRadiusDp) * t,
        textScale = from.textScale + (to.textScale - from.textScale) * t,
        line2Visible = if (t < 0.5f) from.line2Visible else to.line2Visible,
        metaInlineFraction = from.metaInlineFraction + (to.metaInlineFraction - from.metaInlineFraction) * t
    )
}

// ============================================================================
// 8. Grid / Transition Helpers
// ============================================================================

/** adjacentLevel for list pinch — SMALL ↔ NORMAL ↔ ZOOMED */
private fun adjacentLevel(current: DemoDisplayMode, isZoomIn: Boolean): DemoDisplayMode? {
    val listModes = listOf(DemoDisplayMode.LIST_SMALL, DemoDisplayMode.LIST_NORMAL, DemoDisplayMode.LIST_ZOOMED)
    val idx = listModes.indexOf(current)
    if (idx < 0) return null
    val targetIdx = if (isZoomIn) idx + 1 else idx - 1
    return listModes.getOrNull(targetIdx)
}

/** nextModeForGridPinch — GRID_4→GRID_3→GRID_2, boundary at GRID_2+zoomIn, GRID_4+zoomOut→LIST_ZOOMED */
private fun nextModeForGridPinch(current: DemoDisplayMode, isZoomIn: Boolean): DemoDisplayMode? {
    return when {
        current == DemoDisplayMode.GRID_4 && isZoomIn -> DemoDisplayMode.GRID_3
        current == DemoDisplayMode.GRID_3 && isZoomIn -> DemoDisplayMode.GRID_2
        current == DemoDisplayMode.GRID_2 && isZoomIn -> null
        current == DemoDisplayMode.GRID_2 && !isZoomIn -> DemoDisplayMode.GRID_3
        current == DemoDisplayMode.GRID_3 && !isZoomIn -> DemoDisplayMode.GRID_4
        current == DemoDisplayMode.GRID_4 && !isZoomIn -> DemoDisplayMode.LIST_ZOOMED
        else -> null
    }
}

/** computeTransitionTransform MODE_ZOOM — returns (scaleX, scaleY, alpha) */
private fun computeTransitionTransform(
    progress: Float,
    isZoomIn: Boolean,
    isSource: Boolean,
    transitionScaleFactor: Float
): Triple<Float, Float, Float> {
    val scaleBaseY = when {
        isSource -> if (isZoomIn) 0.5f else 1.5f
        else -> if (isZoomIn) 1.5f else 0.5f
    }
    val fA = 1f + (scaleBaseY - 1f) * progress
    return Triple(
        fA * transitionScaleFactor,
        fA * transitionScaleFactor,
        1f - progress
    )
}

// ============================================================================
// 9. Velocity / Snap Helpers
// ============================================================================

/** releaseProgressVelocity — if progress>0.8 and velocity toward end, or progress<0.2 and velocity away */
private fun releaseProgressVelocity(progress: Float, velocity: Float, isZoomIn: Boolean): Boolean {
    val velocityTowardEnd = (velocity > 0f) == isZoomIn
    return (progress > 0.8f && velocityTowardEnd) || (progress < 0.2f && !velocityTowardEnd)
}

/** computeVelocityHandoffDurationMs — distance/velocity*1000, clamped */
private fun computeVelocityHandoffDurationMs(distance: Float, velocity: Float): Long {
    if (velocity == 0f) return 300L
    val durationMs = (abs(distance / velocity) * 1000f).toLong()
    return durationMs.coerceIn(50L, 500L)
}

/** computeSnapDurationMs — 500*distance, min 100ms for confirm, 500/3 for rollback, max 500ms */
private fun computeSnapDurationMs(distance: Float, confirm: Boolean): Long {
    val base = (500f * abs(distance)).toLong()
    val minDur = if (confirm) 100L else (500f / 3f).toLong()
    return base.coerceIn(minDur, 500L)
}

// ============================================================================
// 10. Main Entry
// ============================================================================

@Composable
fun ComposePlayerDemoScreen(onBack: () -> Unit) {
    val backdrop = rememberLayerBackdrop()

    Box(
        Modifier.fillMaxSize().background(Color(0xFF121010))
    ) {
        BackgroundDecoration(backdrop)
        Scaffold(
            containerColor = Color.Transparent,
            topBar = { DemoTopBar(onBack) }
        ) { pv ->
            DemoContent(backdrop, Modifier.padding(pv).padding(bottom = 76.dp))
        }
        // ---- Bottom Player Bar ----
        BottomPlayerBar(
            backdrop,
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        )
    }
}

// ============================================================================
// 11. BackgroundDecoration with LayerBackdrop
// ============================================================================

@Composable
private fun BackgroundDecoration(backdrop: LayerBackdrop) {
    val inf = rememberInfiniteTransition(label = "bg")
    val angle by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(25000, easing = LinearEasing), RepeatMode.Restart),
        label = "a"
    )
    Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
        Box(
            Modifier.size(350.dp).offset { IntOffset(50, 150) }
                .graphicsLayer { rotationZ = angle }
                .background(
                    Brush.sweepGradient(
                        listOf(
                            Color(0xFF8B5E3C).copy(.5f), Color(0xFF3C5E8B).copy(.5f),
                            Color(0xFF5E3C8B).copy(.5f), Color(0xFF8B5E3C).copy(.5f)
                        )
                    ), CircleShape
                ).blur(100.dp)
        )
        Box(
            Modifier.size(300.dp).align(Alignment.BottomEnd).offset { IntOffset(-80, -150) }
                .graphicsLayer { rotationZ = -angle * .6f }
                .background(
                    Brush.sweepGradient(
                        listOf(
                            Color(0xFF3C8B5E).copy(.45f), Color(0xFF8B3C5E).copy(.45f),
                            Color(0xFF5E8B3C).copy(.45f), Color(0xFF3C8B5E).copy(.45f)
                        )
                    ), CircleShape
                ).blur(80.dp)
        )
        Box(
            Modifier.size(200.dp).align(Alignment.TopCenter).offset { IntOffset(100, -50) }
                .graphicsLayer { rotationZ = angle * .4f }
                .background(
                    Brush.radialGradient(listOf(Color(0xFFC4956A).copy(.3f), Color.Transparent)),
                    CircleShape
                ).blur(60.dp)
        )
    }
}

// ============================================================================
// 12. DemoTopBar
// ============================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = {
            Text("Compose Player Demo", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            titleContentColor = Color.White
        )
    )
}

// ============================================================================
// 12b. Bottom Player Bar — Liquid Glass
// ============================================================================

@Composable
private fun BottomPlayerBar(backdrop: Backdrop?, modifier: Modifier = Modifier) {
    var isPlaying by remember { mutableStateOf(true) }
    var progress by remember { mutableFloatStateOf(0.35f) }

    // Auto-progress animation
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            kotlinx.coroutines.delay(50)
            progress = (progress + 0.001f).coerceAtMost(1f)
            if (progress >= 1f) progress = 0f
        }
    }

    Box(modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Column(
            Modifier
                .clip(RoundedCornerShape(20.dp))
                .glassEffectWithBackdrop(backdrop, 20.dp)
                .clickable { }
        ) {
            // Progress indicator
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Color.White.copy(.8f),
                trackColor = Color.White.copy(.1f),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album art
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF8B5E3C), Color(0xFF3C5E8B))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("\u266A", color = Color.White.copy(.7f), fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                // Track info
                Column(Modifier.weight(1f)) {
                    Text(
                        "夜曲", color = Color.White,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                    Text(
                        "周杰伦", color = Color.White.copy(.6f),
                        fontSize = 12.sp, maxLines = 1
                    )
                }
                // Controls
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "\u23EE", color = Color.White.copy(.7f), fontSize = 20.sp,
                        modifier = Modifier.clickable { }
                    )
                    Box(
                        Modifier.size(36.dp).background(Color.White.copy(.15f), CircleShape)
                            .clickable { isPlaying = !isPlaying },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (isPlaying) "\u23F8" else "\u25B6",
                            color = Color.White, fontSize = 16.sp
                        )
                    }
                    Text(
                        "\u23ED", color = Color.White.copy(.7f), fontSize = 20.sp,
                        modifier = Modifier.clickable { }
                    )
                }
            }
        }
    }
}

// ============================================================================
// 13. DemoContent
// ============================================================================

@Composable
private fun DemoContent(backdrop: Backdrop?, modifier: Modifier = Modifier) {
    var currentScene by remember { mutableStateOf(DemoScene.MAIN) }
    var sceneProgress by remember { mutableFloatStateOf(1f) }
    var currentMode by remember { mutableStateOf(DemoDisplayMode.LIST_NORMAL) }
    var snapProgress by remember { mutableFloatStateOf(1f) }
    var elasticScale by remember { mutableFloatStateOf(1f) }
    var gestureState by remember { mutableStateOf(GestureState.IDLE) }
    var velocity by remember { mutableFloatStateOf(0f) }
    var pendingGrid by remember { mutableStateOf(false) }
    var gridProgress by remember { mutableFloatStateOf(0f) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item("scene_preview") {
            ScenePreview(
                currentScene,
                { currentScene = it; sceneProgress = 1f },
                { sceneProgress = it },
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
        }
        item("scene_sel") {
            SceneSelector(currentScene, { currentScene = it }, Modifier.padding(horizontal = 16.dp))
        }
        item("zoom_hdr") {
            Text(
                "捏合缩放演示 (Pinch-to-Zoom)",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                modifier = Modifier.padding(start = 20.dp, top = 8.dp)
            )
        }
        item("zoom_demo") {
            ZoomDemoSection(
                currentMode, { currentMode = it },
                { snapProgress = it }, { elasticScale = it },
                { gestureState = it }, { velocity = it },
                { pendingGrid = it }, { gridProgress = it },
                backdrop,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
        }
        item("debug") {
            DebugPanel(
                currentScene, sceneProgress, currentMode, snapProgress,
                gestureState, elasticScale, velocity, pendingGrid, gridProgress,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            )
        }
    }
}

// ============================================================================
// 14. Scene Preview
// ============================================================================

@Composable
private fun ScenePreview(
    currentScene: DemoScene,
    onChanged: (DemoScene) -> Unit,
    onProgress: (Float) -> Unit,
    modifier: Modifier
) {
    var target by remember { mutableStateOf(currentScene) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(currentScene) { target = currentScene }

    Box(
        modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(20.dp)).glassEffect(20.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true; dragX = 0f },
                    onHorizontalDrag = { _, a ->
                        dragX += a
                        onProgress((abs(dragX) / size.width).coerceIn(0f, 1f))
                    },
                    onDragEnd = {
                        val th = size.width * .3f
                        val sc = DemoScene.entries
                        val i = sc.indexOf(target)
                        if (abs(dragX) > th) {
                            val ni = if (dragX < 0) (i + 1).coerceAtMost(sc.size - 1)
                            else (i - 1).coerceAtLeast(0)
                            target = sc[ni]; onChanged(sc[ni])
                        }
                        dragging = false; dragX = 0f; onProgress(1f)
                    },
                    onDragCancel = { dragging = false; dragX = 0f; onProgress(1f) }
                )
            }
    ) {
        AnimatedContent(
            target,
            transitionSpec = {
                val d = if (target.ordinal > initialState.ordinal) 1 else -1
                val dc = CubicBezierEasing(0f, 0f, .2f, 1f)
                val sp = tween<IntOffset>(300, easing = dc)
                slideInHorizontally(sp) { w -> d * w } + fadeIn(tween(200)) togetherWith
                        slideOutHorizontally(sp) { w -> -d * w } + fadeOut(tween(150))
            },
            label = "sc"
        ) { scene ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (scene) {
                    DemoScene.MAIN -> MainContent()
                    DemoScene.PLAYER -> PlayerContent()
                    DemoScene.LYRIC -> LyricContent()
                    DemoScene.QUEUE -> QueueContent()
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopStart).padding(12.dp)
                .background(Color.Black.copy(.5f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(target.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        if (!dragging) {
            val sc = DemoScene.entries
            val i = sc.indexOf(target)
            if (i > 0) Text(
                "\u2039",
                Modifier.align(Alignment.CenterStart).padding(start = 8.dp),
                color = Color.White.copy(.4f), fontSize = 28.sp
            )
            if (i < sc.size - 1) Text(
                "\u203A",
                Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
                color = Color.White.copy(.4f), fontSize = 28.sp
            )
        }
    }
}

// ---- Scene Content ----

@Composable
private fun MainContent() {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("歌曲列表", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        for (r in 0 until 2) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in 0 until 3) {
                val i = r * 3 + c
                val h = (i * 60f) % 360f
                Box(
                    Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                        .background(Brush.linearGradient(listOf(hsl(h, .6f, .3f), hsl(h + 40f, .7f, .2f)))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(sampleTracks[i].title.take(1), color = Color.White.copy(.7f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PlayerContent() {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(120.dp).clip(RoundedCornerShape(16.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8B5E3C), Color(0xFF3C5E8B)))),
            contentAlignment = Alignment.Center
        ) { Text("\u266B", fontSize = 48.sp, color = Color.White.copy(.8f)) }
        Spacer(Modifier.height(16.dp))
        Text("夜曲", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("周杰伦", color = Color.White.copy(.7f), fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            CBtn("\u23EE"); CBtn("\u25B6", 44.dp); CBtn("\u23ED")
        }
    }
}

@Composable
private fun CBtn(s: String, z: Dp = 36.dp) {
    Box(
        Modifier.size(z).background(Color.White.copy(.15f), CircleShape),
        contentAlignment = Alignment.Center
    ) { Text(s, color = Color.White, fontSize = (z.value / 3).sp) }
}

@Composable
private fun LyricContent() {
    val ls = listOf(
        "一盏黄黄旧旧的灯", "时间在旁闷不吭声", "寂寞下手毫无分寸",
        "不懂得轻重之分", "沉默支撑跃过陌生", "静静看着凌晨黄昏",
        "你的身影", "失去平衡", "慢慢下沉"
    )
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ls.forEachIndexed { i, l ->
            val a = if (i == 3) 1f else .4f
            val f = if (i == 3) 18.sp else 14.sp
            val w = if (i == 3) FontWeight.Bold else FontWeight.Normal
            Text(l, color = Color.White.copy(a), fontSize = f, fontWeight = w, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun QueueContent() {
    Column(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "播放队列", color = Color.White, fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 4.dp)
        )
        sampleTracks.take(5).forEachIndexed { i, t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = Color.White.copy(.5f), fontSize = 12.sp, modifier = Modifier.width(20.dp))
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(hsl((i * 72f) % 360f, .5f, .3f), hsl((i * 72f + 40f) % 360f, .6f, .2f))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) { Text("\u266A", color = Color.White.copy(.6f), fontSize = 14.sp) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(t.artist, color = Color.White.copy(.5f), fontSize = 10.sp, maxLines = 1)
                }
                Text(t.duration, color = Color.White.copy(.4f), fontSize = 10.sp)
            }
        }
    }
}

// ============================================================================
// 15. Scene Selector
// ============================================================================

@Composable
private fun SceneSelector(cur: DemoScene, sel: (DemoScene) -> Unit, mod: Modifier) {
    Row(mod.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DemoScene.entries.forEach { s ->
            val on = s == cur
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                    .then(if (on) Modifier.glassEffect(12.dp) else Modifier.background(Color.White.copy(.06f)))
                    .clickable { sel(s) }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    s.label,
                    color = if (on) Color.White else Color.White.copy(.5f),
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}

// ============================================================================
// 16. ZoomDemoSection — full port of ListZoomManager + grid pinch
// ============================================================================

@Composable
private fun ZoomDemoSection(
    currentMode: DemoDisplayMode,
    onModeChanged: (DemoDisplayMode) -> Unit,
    onSnapProgress: (Float) -> Unit,
    onElasticScale: (Float) -> Unit,
    onGestureStateChanged: (GestureState) -> Unit,
    onVelocityChanged: (Float) -> Unit,
    onPendingGridChanged: (Boolean) -> Unit,
    onGridProgressChanged: (Float) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier
) {
    val density = LocalDensity.current
    val touchSlopPx = LocalViewConfiguration.current.touchSlop

    // ---- Transition state ----
    var committedMode by remember { mutableStateOf(currentMode) }
    var srcMode by remember { mutableStateOf(currentMode) }
    var tgtMode by remember { mutableStateOf(currentMode) }
    var transitionProgress by remember { mutableFloatStateOf(1f) }
    var transitionIsZoomIn by remember { mutableStateOf(true) }
    var elasticFactor by remember { mutableFloatStateOf(1f) }

    // ---- Gesture state ----
    var gestureState by remember { mutableStateOf(GestureState.IDLE) }
    var pendingGridZoom by remember { mutableStateOf(false) }
    var gridZoomProgress by remember { mutableFloatStateOf(0f) }
    var pinchVelocityDp by remember { mutableFloatStateOf(0f) }
    var isPinching by remember { mutableStateOf(false) }
    var transitionStarted by remember { mutableStateOf(false) }
    var boundaryElasticActive by remember { mutableStateOf(false) }
    var snapAnimRunning by remember { mutableStateOf(false) }

    // ---- Pinch tracking ----
    var pinchBaseDistance by remember { mutableFloatStateOf(1f) }
    var lastRatio by remember { mutableFloatStateOf(1f) }
    var lastTimeMs by remember { mutableLongStateOf(0L) }
    var prevP1X by remember { mutableFloatStateOf(0f) }
    var prevP1Y by remember { mutableFloatStateOf(0f) }
    var prevP2X by remember { mutableFloatStateOf(0f) }
    var prevP2Y by remember { mutableFloatStateOf(0f) }

    // ---- Animation ----
    val snapAnimatable = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // Sync external committed mode
    LaunchedEffect(currentMode) {
        if (!isPinching && !snapAnimRunning) {
            committedMode = currentMode
            srcMode = currentMode; tgtMode = currentMode
            transitionProgress = 1f; elasticFactor = 1f
        }
    }

    fun commitMode(mode: DemoDisplayMode) {
        srcMode = mode; tgtMode = mode
        transitionProgress = 1f; elasticFactor = 1f
        committedMode = mode
        onModeChanged(mode)
        onSnapProgress(1f); onElasticScale(1f)
    }

    // Snap animation — matching computeSnapDurationMs + AccelerateDecelerateInterpolator
    suspend fun animateSnap(confirm: Boolean) {
        val start = transitionProgress
        val end = if (confirm) 1f else 0f
        val distance = abs(end - start).coerceAtLeast(0.001f)

        val useVelocityHandoff = releaseProgressVelocity(start, pinchVelocityDp, transitionIsZoomIn)
        val duration = if (useVelocityHandoff) {
            computeVelocityHandoffDurationMs(distance, pinchVelocityDp)
        } else {
            computeSnapDurationMs(distance, confirm)
        }
        val easing = if (useVelocityHandoff) LinearEasing else AccelerateDecelerateEasing

        val elasticDuration = if (abs(elasticFactor - 1f) > 0.001f) DemoDisplayMode.boundaryElasticDurationMs else 0L
        val totalDuration = maxOf(duration, elasticDuration)

        snapAnimRunning = true
        val startElastic = elasticFactor
        try {
            snapAnimatable.snapTo(0f)
            snapAnimatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(totalDuration.toInt(), easing = easing)
            ) {
                val p = value
                transitionProgress = start + (end - start) * p
                elasticFactor = startElastic + (1f - startElastic) * p
                onSnapProgress(transitionProgress); onElasticScale(elasticFactor)
            }
        } finally {
            transitionProgress = end; elasticFactor = 1f; snapAnimRunning = false
            onSnapProgress(transitionProgress); onElasticScale(1f)
            if (confirm) commitMode(tgtMode) else commitMode(srcMode)
        }
    }

    Column(modifier) {
        // ---- Level selector buttons (6 modes) ----
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DemoDisplayMode.entries.forEach { mode ->
                val on = mode == committedMode
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                        .then(if (on) Modifier.glassEffect(10.dp) else Modifier.background(Color.White.copy(.06f)))
                        .clickable(enabled = !isPinching && !snapAnimRunning) {
                            if (mode != committedMode) {
                                srcMode = committedMode; tgtMode = mode
                                transitionIsZoomIn = mode.ordinal > committedMode.ordinal
                                transitionProgress = 0f; onSnapProgress(0f)
                                scope.launch { animateSnap(true) }
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        mode.label,
                        color = if (on) Color.White else Color.White.copy(.5f),
                        fontSize = 11.sp,
                        fontWeight = if (on) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Container with glass + pinch gesture ----
        val animScope = rememberCoroutineScope()
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                .glassEffectWithBackdrop(backdrop, 16.dp)
                .pointerInput(touchSlopPx, committedMode) {
                    awaitEachGesture {
                                // Wait for second finger
                                val down = awaitFirstRequirement(requireUnconsumed = false) { e ->
                                    e.changes.size >= 2 && e.type == PointerEventType.Press
                                } ?: return@awaitEachGesture

                                val p1 = down.changes[0]; val p2 = down.changes[1]
                                val initDist = sqrt(
                                    (p2.position.x - p1.position.x).let { it * it } +
                                            (p2.position.y - p1.position.y).let { it * it }
                                ).coerceAtLeast(1f)

                                isPinching = true
                                pinchBaseDistance = initDist
                                lastRatio = 1f
                                lastTimeMs = System.currentTimeMillis()
                                prevP1X = p1.position.x; prevP1Y = p1.position.y
                                prevP2X = p2.position.x; prevP2Y = p2.position.y
                                pinchVelocityDp = 0f
                                transitionStarted = false
                                boundaryElasticActive = false
                                pendingGridZoom = false
                                gridZoomProgress = 0f
                                elasticFactor = 1f

                                val isGridGesture = committedMode.isGrid
                                gestureState = if (isGridGesture) GestureState.GRID_PINCHING else GestureState.PINCHING
                                onGestureStateChanged(gestureState)

                                var pastSlop = false
                                try {
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        if (ev.changes.size < 2) break

                                        val cp1 = ev.changes[0]; val cp2 = ev.changes[1]
                                        val dist = sqrt(
                                            (cp2.position.x - cp1.position.x).let { it * it } +
                                                    (cp2.position.y - cp1.position.y).let { it * it }
                                        )

                                        // Touch slop filter
                                        if (!pastSlop) {
                                            if (abs(dist - pinchBaseDistance) >= touchSlopPx) pastSlop = true
                                            else {
                                                ev.changes.forEach { it.consume() }; continue
                                            }
                                        }

                                        val now = System.currentTimeMillis()
                                        val dtSec = ((now - lastTimeMs).coerceAtLeast(1L)) / 1000f

                                        // Radial velocity — Poweramp two-pointer formula
                                        val dx = cp2.position.x - cp1.position.x
                                        val dy = cp2.position.y - cp1.position.y
                                        val currentDistance = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)

                                        val vx1 = (cp1.position.x - prevP1X) / dtSec
                                        val vy1 = (cp1.position.y - prevP1Y) / dtSec
                                        val vx2 = (cp2.position.x - prevP2X) / dtSec
                                        val vy2 = (cp2.position.y - prevP2Y) / dtSec

                                        val projectedDx = (vx2 - vx1) * 0.01f + dx
                                        val projectedDy = (vy2 - vy1) * 0.01f + dy
                                        val projectedDistance = sqrt(projectedDx * projectedDx + projectedDy * projectedDy)
                                        val radialPxPerSec = (projectedDistance - currentDistance) / 0.01f
                                        pinchVelocityDp = radialPxPerSec / density.density

                                        prevP1X = cp1.position.x; prevP1Y = cp1.position.y
                                        prevP2X = cp2.position.x; prevP2Y = cp2.position.y
                                        lastTimeMs = now

                                        lastRatio = dist / pinchBaseDistance
                                        val rawDelta = (lastRatio - 1f) * 0.45f
                                        val isZoomIn = rawDelta > 0f

                                        if (isGridGesture) {
                                            // ---- Grid pinch logic ----
                                            if (!transitionStarted && !boundaryElasticActive) {
                                                val nextMode = nextModeForGridPinch(committedMode, isZoomIn)
                                                if (nextMode == null) {
                                                    boundaryElasticActive = true; elasticFactor = 1f
                                                } else {
                                                    srcMode = committedMode; tgtMode = nextMode
                                                    transitionIsZoomIn = isZoomIn
                                                    transitionStarted = true; transitionProgress = 0f
                                                }
                                            }

                                            if (boundaryElasticActive) {
                                                val overPull = abs(rawDelta)
                                                elasticFactor = computeBoundaryElasticScale(overPull, isZoomIn)
                                                onElasticScale(elasticFactor)
                                            } else if (transitionStarted) {
                                                val signedDelta = if (transitionIsZoomIn) rawDelta else -rawDelta
                                                transitionProgress = signedDelta.coerceIn(0f, 1f)
                                                elasticFactor = powerampElasticScale(signedDelta, transitionIsZoomIn)
                                                onSnapProgress(transitionProgress); onElasticScale(elasticFactor)
                                            }
                                        } else {
                                            // ---- List pinch logic ----
                                            if (committedMode == DemoDisplayMode.LIST_ZOOMED && isZoomIn) {
                                                // Pending grid zoom
                                                if (!pendingGridZoom) {
                                                    pendingGridZoom = true
                                                    gestureState = GestureState.PENDING_GRID
                                                    onGestureStateChanged(gestureState)
                                                }
                                                gridZoomProgress = (rawDelta - 1f).coerceIn(0f, 1f)
                                                elasticFactor = powerampElasticScale(rawDelta, true)
                                                onElasticScale(elasticFactor)
                                                onGridProgressChanged(gridZoomProgress)
                                                onPendingGridChanged(true)
                                            } else {
                                                if (!transitionStarted && !boundaryElasticActive) {
                                                    val adj = adjacentLevel(committedMode, isZoomIn)
                                                    if (adj == null) {
                                                        boundaryElasticActive = true; elasticFactor = 1f
                                                    } else {
                                                        srcMode = committedMode; tgtMode = adj
                                                        transitionIsZoomIn = isZoomIn
                                                        transitionStarted = true; transitionProgress = 0f
                                                    }
                                                }

                                                if (boundaryElasticActive) {
                                                    val overPull = abs(rawDelta)
                                                    elasticFactor = computeBoundaryElasticScale(overPull, isZoomIn)
                                                    onElasticScale(elasticFactor)
                                                } else if (transitionStarted) {
                                                    val signedDelta = if (transitionIsZoomIn) rawDelta else -rawDelta
                                                    transitionProgress = signedDelta.coerceIn(0f, 1f)
                                                    elasticFactor = powerampElasticScale(signedDelta, transitionIsZoomIn)
                                                    onSnapProgress(transitionProgress); onElasticScale(elasticFactor)
                                                }
                                            }
                                        }

                                        onVelocityChanged(pinchVelocityDp)
                                        ev.changes.forEach { it.consume() }
                                    }
                                } finally {
                                    // ---- finishPinch logic ----
                                    if (isPinching) {
                                        if (boundaryElasticActive) {
                                            // Boundary elastic back 350ms
                                            boundaryElasticActive = false
                                            animScope.launch {
                                                snapAnimRunning = true
                                                val startE = elasticFactor
                                                try {
                                                    snapAnimatable.snapTo(0f)
                                                    snapAnimatable.animateTo(
                                                        1f,
                                                        tween(
                                                            DemoDisplayMode.boundaryElasticDurationMs.toInt(),
                                                            easing = AccelerateDecelerateEasing
                                                        )
                                                    ) {
                                                        elasticFactor = startE + (1f - startE) * value
                                                        onElasticScale(elasticFactor)
                                                    }
                                                } finally {
                                                    elasticFactor = 1f; snapAnimRunning = false; onElasticScale(1f)
                                                }
                                            }
                                        } else if (pendingGridZoom) {
                                            // Pending grid zoom decision
                                            val shouldConfirm = if (abs(pinchVelocityDp) >= DemoDisplayMode.velocityThreshold) {
                                                pinchVelocityDp > 0f
                                            } else {
                                                gridZoomProgress > DemoDisplayMode.positionThreshold
                                            }
                                            pendingGridZoom = false; onPendingGridChanged(false)
                                            if (shouldConfirm) {
                                                srcMode = DemoDisplayMode.LIST_ZOOMED
                                                tgtMode = DemoDisplayMode.GRID_4
                                                transitionIsZoomIn = true; transitionProgress = 0f
                                                onSnapProgress(0f)
                                                animScope.launch { animateSnap(true) }
                                            }
                                            gridZoomProgress = 0f; onGridProgressChanged(0f)
                                        } else if (transitionStarted) {
                                            // Normal snap decision
                                            val shouldConfirm = if (abs(pinchVelocityDp) >= DemoDisplayMode.velocityThreshold) {
                                                (pinchVelocityDp > 0f) == transitionIsZoomIn
                                            } else {
                                                transitionProgress > DemoDisplayMode.positionThreshold
                                            }
                                            animScope.launch { animateSnap(shouldConfirm) }
                                        }
                                        isPinching = false; transitionStarted = false
                                        gestureState = GestureState.IDLE
                                        onGestureStateChanged(GestureState.IDLE)
                                    }
                                }
                            }
                            }
                .padding(12.dp)
        ) {
            // ---- Content rendering (scrollable) ----
            val isTransitioning = transitionProgress < 1f && srcMode != tgtMode
            val needsCrossfade = isTransitioning &&
                    (srcMode.isGrid != tgtMode.isGrid || (srcMode.isGrid && tgtMode.isGrid && srcMode.columns != tgtMode.columns))
            val isListTransition = isTransitioning && !srcMode.isGrid && !tgtMode.isGrid

            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                when {
                    isListTransition -> {
                        val params = lerpZoomParams(srcMode.params, tgtMode.params, transitionProgress)
                        ListContent(params)
                    }
                    needsCrossfade -> {
                        Box(Modifier.fillMaxWidth()) {
                            // Source (fading out + shrinking)
                            Box(Modifier.fillMaxWidth().graphicsLayer {
                                val t = computeTransitionTransform(
                                    transitionProgress, transitionIsZoomIn, true, 1f
                                )
                                scaleX = t.first; scaleY = t.second; alpha = t.third
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }) {
                                if (srcMode.isGrid) GridContent(srcMode.columns, srcMode.params)
                                else ListContent(srcMode.params)
                            }
                            // Target (fading in + shrinking from large)
                            Box(Modifier.fillMaxWidth().graphicsLayer {
                                val t = computeTransitionTransform(
                                    1f - transitionProgress, transitionIsZoomIn, false, 1f
                                )
                                scaleX = t.first; scaleY = t.second; alpha = t.third
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }) {
                                if (tgtMode.isGrid) GridContent(tgtMode.columns, tgtMode.params)
                                else ListContent(tgtMode.params)
                            }
                        }
                    }
                    else -> {
                        val params = committedMode.params
                        if (committedMode.isGrid) {
                            GridContent(committedMode.columns, params)
                        } else {
                            ListContent(params)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val displayParams = if (srcMode == tgtMode || transitionProgress >= 1f) {
            committedMode.params
        } else {
            lerpZoomParams(srcMode.params, tgtMode.params, transitionProgress)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Chip("封面", if (displayParams.coverSizeDp < 0) "填充" else "${displayParams.coverSizeDp.toInt()}dp")
            Chip("行高", "${displayParams.rowHeightDp.toInt()}dp")
            Chip("文字", "%.2fx".format(displayParams.textScale))
            Chip("圆角", "${displayParams.cornerRadiusDp.toInt()}dp")
        }
    }
}

// ============================================================================
// 17. List/Grid Content Rendering
// ============================================================================

@Composable
private fun ListContent(params: DemoZoomParams) {
    val coverSize = params.coverSizeDp.dp
    val rowHeight = params.rowHeightDp.dp
    val cr = params.cornerRadiusDp.dp
    val ts = params.textScale

    sampleTracks.forEachIndexed { i, track ->
        Row(
            Modifier.fillMaxWidth().height(rowHeight),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val hue = (i * 55f) % 360f
            Box(
                Modifier.size(coverSize).clip(RoundedCornerShape(cr))
                    .background(Brush.linearGradient(listOf(hsl(hue, .6f, .3f), hsl(hue + 40f, .7f, .2f))))
                    .border(1.dp, Color.White.copy(.15f), RoundedCornerShape(cr)),
                contentAlignment = Alignment.Center
            ) {
                if (coverSize > 28.dp) Text(
                    "\u266A", color = Color.White.copy(.5f),
                    fontSize = (coverSize.value * .3f).sp
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, color = Color.White, fontSize = (14 * ts).sp, fontWeight = FontWeight.Medium, maxLines = 1)
                if (params.line2Visible) Text(
                    track.artist, color = Color.White.copy(.6f),
                    fontSize = (12 * ts).sp, maxLines = 1
                )
            }
            Text(track.duration, color = Color.White.copy(.4f), fontSize = (11 * ts).sp)
        }
        if (i < sampleTracks.size - 1) HorizontalDivider(
            modifier = Modifier.padding(start = coverSize + 12.dp),
            color = Color.White.copy(.06f), thickness = .5.dp
        )
    }
}

@Composable
private fun GridContent(columns: Int, params: DemoZoomParams) {
    val cr = params.cornerRadiusDp.dp
    val ts = params.textScale
    val sp = 8.dp

    sampleTracks.chunked(columns).forEachIndexed { ri, row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sp)) {
            row.forEachIndexed { ci, track ->
                val idx = ri * columns + ci
                val hue = (idx * 55f) % 360f
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(cr))
                            .background(
                                Brush.linearGradient(
                                    listOf(hsl(hue, .6f, .3f), hsl(hue + 40f, .7f, .2f))
                                )
                            )
                            .border(1.dp, Color.White.copy(.15f), RoundedCornerShape(cr)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("\u266A", color = Color.White.copy(.5f), fontSize = 20.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        track.title, color = Color.White,
                        fontSize = (11 * ts).sp, fontWeight = FontWeight.Medium, maxLines = 1
                    )
                    if (params.line2Visible) Text(
                        track.artist, color = Color.White.copy(.6f),
                        fontSize = (9 * ts).sp, maxLines = 1
                    )
                }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(sp))
    }
}

// ============================================================================
// 18. Debug Panel
// ============================================================================

@Composable
private fun DebugPanel(
    curScene: DemoScene, sceneProg: Float,
    curMode: DemoDisplayMode, snapProg: Float,
    gs: GestureState, es: Float,
    vel: Float, pendingGrid: Boolean, gridProg: Float,
    mod: Modifier
) {
    Column(mod.clip(RoundedCornerShape(24.dp)).glassEffect(24.dp).padding(16.dp)) {
        Text("调试面板 (Debug Panel)", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        DRow("场景", curScene.label)
        DRow("场景过渡", "%.1f%%".format(sceneProg * 100f))
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = Color.White.copy(.1f))
        DRow("显示模式", curMode.label)
        DRow("Snap 进度", "%.1f%%".format(snapProg * 100f))
        DRow("弹性缩放", "%.3f".format(es))
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp), color = Color.White.copy(.1f))
        DRow("手势状态", when (gs) {
            GestureState.IDLE -> "空闲"
            GestureState.PINCHING -> "捏合中"
            GestureState.PENDING_GRID -> "待确认网格"
            GestureState.GRID_PINCHING -> "网格捏合中"
        })
        DRow("速度", "%.1f dp/s".format(vel))
        DRow("待确认网格", if (pendingGrid) "是" else "否")
        DRow("网格进度", "%.1f%%".format(gridProg * 100f))
        Spacer(Modifier.height(12.dp))
        Text("Poweramp Easing 曲线", color = Color.White.copy(.7f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        EasingVis()
    }
}

// ============================================================================
// 19. Utilities
// ============================================================================

private fun hsl(h: Float, s: Float, l: Float): Color {
    val hn = ((h % 360f) + 360f) % 360f
    val c = (1f - abs(2f * l - 1f)) * s
    val x = c * (1f - abs((hn / 60f) % 2f - 1f))
    val m = l - c / 2f
    val (r, g, b) = when {
        hn < 60f -> Triple(c, x, 0f)
        hn < 120f -> Triple(x, c, 0f)
        hn < 180f -> Triple(0f, c, x)
        hn < 240f -> Triple(0f, x, c)
        hn < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}

@Composable
private fun Chip(label: String, value: String) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(.08f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, color = Color.White.copy(.5f), fontSize = 11.sp)
        Text(value, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DRow(l: String, v: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(l, color = Color.White.copy(.6f), fontSize = 12.sp)
        Text(v, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun EasingVis() {
    val cc = Color(0xFF8AB4F8)
    val gc = Color.White.copy(.1f)
    Canvas(
        Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(.04f))
    ) {
        val w = size.width; val h = size.height
        val p = 8.dp.toPx(); val pw = w - p * 2; val ph = h - p * 2
        for (i in 0..4) {
            drawLine(gc, Offset(p, p + ph * i / 4f), Offset(w - p, p + ph * i / 4f), .5f)
            drawLine(gc, Offset(p + pw * i / 4f, p), Offset(p + pw * i / 4f, h - p), .5f)
        }
        drawLine(Color.White.copy(.2f), Offset(p, p + ph * .5f), Offset(w - p, p + ph * .5f), 1f)
        val path = Path(); var f = true
        for (i in 0..100) {
            val t = i / 100f; val x = p + t * pw
            val d = t * 2f - 1f; val e = powerampEasing(d * 3f)
            val y = p + ph * (.5f - e * 2.5f).coerceIn(0f, 1f)
            if (f) { path.moveTo(x, y); f = false } else path.lineTo(x, y)
        }
        drawPath(path, cc, style = Stroke(2f))
    }
}

// ---- Glass Effect Helpers ----

@Composable
private fun Modifier.glassEffect(corner: Dp = 16.dp): Modifier {
    return this
        .background(
            Brush.verticalGradient(
                listOf(Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.05f))
            ),
            RoundedCornerShape(corner)
        )
        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(corner))
}

@Composable
private fun Modifier.glassEffectWithBackdrop(backdrop: Backdrop?, corner: Dp = 16.dp): Modifier {
    if (backdrop != null) {
        return try {
            this.drawPlainBackdrop(
                backdrop = backdrop,
                shape = { RoundedCornerShape(corner) },
                effects = {
                    try {
                        blur(40f)
                        vibrancy()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            liquidGlass(cornerRadius = corner.toPx())
                        }
                    } catch (_: Throwable) { }
                }
            ).border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(corner))
        } catch (_: Throwable) {
            this.glassEffect(corner)
        }
    }
    return this.glassEffect(corner)
}

// ---- Gesture Helper ----

private suspend fun AwaitPointerEventScope.awaitFirstRequirement(
    requireUnconsumed: Boolean = false,
    condition: (PointerEvent) -> Boolean
): PointerEvent? {
    while (true) {
        val event = awaitPointerEvent()
        if (condition(event)) return event
        if (requireUnconsumed) event.changes.forEach { if (!it.isConsumed) it.consume() }
    }
}
