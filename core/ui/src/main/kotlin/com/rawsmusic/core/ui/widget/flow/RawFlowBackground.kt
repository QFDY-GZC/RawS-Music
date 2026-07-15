package com.rawsmusic.core.ui.widget.flow

import android.content.Context
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Color as AndroidColor
import android.util.LruCache
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.palette.graphics.Palette
import com.rawsmusic.core.common.utils.PowerTraceLogger
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.theme.RawThemeRuntimeState
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlin.math.sin
import top.yukonga.miuix.kmp.basic.RadioButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private const val FLOW_PREFS = "raw_flow_background"
private const val FLOW_MODE_KEY = "mode"
private const val FLOW_MAX_COLOR_COUNT = 5
private const val FLOW_FRAME_INTERVAL_MS = 25L
private const val FLOW_DISABLED_FRAME_INTERVAL_MS = 0L
private const val FLOW_EXTRACT_SIZE = 96
private const val FLOW_SAMPLE_GRID = 18
private val FLOW_PALETTE_RETRY_DELAYS_MS = longArrayOf(0L, 420L, 1400L)
private val flowPaletteCache = LruCache<String, List<Color>>(48)

/**
 * 主界面/列表页使用的流光背景模式。
 *
 * DARK/LIGHT 会从当前播放封面中提取 2~4 个主题感知颜色；UNIVERSAL 使用固定通用配色。
 */
enum class RawFlowMode(val prefValue: String) {
    DARK("dark"),
    LIGHT("light"),
    UNIVERSAL("universal"),
    OFF("off");

    companion object {
        fun fromPref(value: String?, fallback: RawFlowMode): RawFlowMode {
            return values().firstOrNull { it.prefValue == value } ?: fallback
        }
    }
}

val LocalRawFlowMode = staticCompositionLocalOf { RawFlowMode.UNIVERSAL }
val LocalRawFlowModeSetter = staticCompositionLocalOf<(RawFlowMode) -> Unit> { {} }

/**
 * 全局运行态。播放器页面可能不在主界面的 CompositionLocal 范围内，
 * 所以这里额外维护一份可观察状态，保证流光模式修改后不用重启应用。
 */
object RawFlowRuntimeState {
    var mode by mutableStateOf<RawFlowMode?>(null)
        private set
    var revision by mutableIntStateOf(0)
        private set

    fun update(mode: RawFlowMode) {
        if (this.mode != mode) {
            this.mode = mode
            revision++
        } else {
            // Some callers re-apply the same mode after editing prefs/theme. Bump a revision so
            // main/list/player backgrounds re-read the persisted mode immediately.
            revision++
        }
    }

    fun persistAndUpdate(context: Context, mode: RawFlowMode) {
        context.applicationContext
            .getSharedPreferences(FLOW_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(FLOW_MODE_KEY, mode.prefValue)
            .apply()
        update(mode)
    }

    fun readPersisted(context: Context, fallback: RawFlowMode): RawFlowMode {
        val prefs = context.applicationContext.getSharedPreferences(FLOW_PREFS, Context.MODE_PRIVATE)
        return RawFlowMode.fromPref(prefs.getString(FLOW_MODE_KEY, null), fallback)
    }
}

@Composable
fun rememberCurrentRawFlowMode(): RawFlowMode {
    val context = LocalContext.current.applicationContext
    val isDark = rememberRawFlowIsDarkTheme()
    val fallback = if (isDark) RawFlowMode.DARK else RawFlowMode.LIGHT
    val prefs = remember(context) { context.getSharedPreferences(FLOW_PREFS, Context.MODE_PRIVATE) }
    val runtimeVersion = RawThemeRuntimeState.version
    val runtimeRevision = RawFlowRuntimeState.revision
    val prefMode = remember(prefs, fallback, runtimeVersion, runtimeRevision) {
        RawFlowMode.fromPref(prefs.getString(FLOW_MODE_KEY, null), fallback)
    }
    val runtimeMode = RawFlowRuntimeState.mode
    val effectiveMode = runtimeMode ?: prefMode

    LaunchedEffect(effectiveMode, runtimeRevision) {
        if (RawFlowRuntimeState.mode != effectiveMode) {
            RawFlowRuntimeState.update(effectiveMode)
        }
    }

    return effectiveMode
}

@Composable
fun rememberRawFlowModeState(): MutableState<RawFlowMode> {
    val context = LocalContext.current.applicationContext
    val isDark = rememberRawFlowIsDarkTheme()
    val fallback = if (isDark) RawFlowMode.DARK else RawFlowMode.LIGHT
    val prefs = remember(context) { context.getSharedPreferences(FLOW_PREFS, Context.MODE_PRIVATE) }
    val runtimeVersion = RawThemeRuntimeState.version
    val initial = remember(prefs, fallback, runtimeVersion) {
        RawFlowRuntimeState.readPersisted(context, fallback)
    }
    val state = remember { mutableStateOf(initial) }
    val runtimeMode = RawFlowRuntimeState.mode
    val runtimeRevision = RawFlowRuntimeState.revision

    LaunchedEffect(runtimeRevision, runtimeMode) {
        val mode = runtimeMode ?: return@LaunchedEffect
        if (state.value != mode) {
            state.value = mode
        }
    }

    // 亮/暗流光跟随应用主题切换；通用流光和关闭状态保持用户选择.
    LaunchedEffect(isDark) {
        val next = when (state.value) {
            RawFlowMode.LIGHT, RawFlowMode.DARK -> fallback
            RawFlowMode.UNIVERSAL, RawFlowMode.OFF -> state.value
        }
        if (state.value != next) {
            state.value = next
        }
        RawFlowRuntimeState.persistAndUpdate(context, next)
    }

    LaunchedEffect(state.value) {
        RawFlowRuntimeState.persistAndUpdate(context, state.value)
    }

    return state
}

@Composable
fun ProvideRawFlowMode(
    state: MutableState<RawFlowMode>,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current.applicationContext
    CompositionLocalProvider(
        LocalRawFlowMode provides state.value,
        LocalRawFlowModeSetter provides { mode ->
            RawFlowRuntimeState.persistAndUpdate(context, mode)
            state.value = mode
        },
        content = content
    )
}

@Composable
private fun rememberRawFlowIsDarkTheme(): Boolean {
    val systemDark = isSystemInDarkTheme()
    val runtimeVersion = RawThemeRuntimeState.version
    val themeMode = remember(runtimeVersion) { ThemeManager.getCurrentTheme() }
    return when (themeMode) {
        ThemeManager.ThemeMode.DARK -> true
        ThemeManager.ThemeMode.LIGHT -> false
        ThemeManager.ThemeMode.SYSTEM -> systemDark
    }
}

@Composable
fun RawFlowBackground(
    mode: RawFlowMode,
    sourceCoverKey: String?,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    motionEnabled: Boolean = true,
    frameIntervalMs: Long = FLOW_FRAME_INTERVAL_MS
) {
    val isSystemDark = rememberRawFlowIsDarkTheme()
    val scheme = MiuixTheme.colorScheme
    val runtimeRevision = RawFlowRuntimeState.revision
    val flowMode = RawFlowRuntimeState.mode ?: mode

    LaunchedEffect(flowMode, runtimeRevision, sourceCoverKey, isSystemDark, active, motionEnabled) {
        PowerTraceLogger.flowMode(
            mode = flowMode.prefValue,
            isDark = isSystemDark,
            coverKey = sourceCoverKey
        )
        if (!active || !motionEnabled) {
            PowerTraceLogger.flowFrame(
                mode = flowMode.prefValue,
                enabled = false,
                frameIntervalMs = if (active) frameIntervalMs else FLOW_DISABLED_FRAME_INTERVAL_MS
            )
        }
    }

    if (flowMode == RawFlowMode.OFF || !active) {
        Box(modifier = modifier.fillMaxSize().background(scheme.background))
        return
    }

    val fallbackColors = remember(flowMode, isSystemDark) { defaultFlowColors(flowMode, isSystemDark) }
    var targetColors by remember(flowMode, isSystemDark) { mutableStateOf(fallbackColors) }

    LaunchedEffect(flowMode, runtimeRevision, sourceCoverKey, fallbackColors, isSystemDark) {
        val paletteStartMs = SystemClock.elapsedRealtime()
        if (flowMode == RawFlowMode.UNIVERSAL || sourceCoverKey.isNullOrBlank()) {
            targetColors = fallbackColors
            PowerTraceLogger.flowPalette(
                stage = "fallback_static",
                mode = flowMode.prefValue,
                source = "default",
                colorCount = fallbackColors.size,
                elapsedMs = SystemClock.elapsedRealtime() - paletteStartMs,
                coverKey = sourceCoverKey
            )
            return@LaunchedEffect
        }

        val cacheKey = "${flowMode.prefValue}:$isSystemDark:$sourceCoverKey"
        flowPaletteCache.get(cacheKey)?.let { cached ->
            targetColors = cached
            PowerTraceLogger.flowPalette(
                stage = "cache_hit",
                mode = flowMode.prefValue,
                source = "flowPaletteCache",
                colorCount = cached.size,
                elapsedMs = SystemClock.elapsedRealtime() - paletteStartMs,
                coverKey = sourceCoverKey
            )
            return@LaunchedEffect
        }

        // Project-style：流光只复用已经在内存里的当前封面，不主动排队解码整首歌。
        // 这样主界面/列表页不会因为后台动态取色而把大量音频文件重新读一遍。
        var appliedAlbumPalette = false
        for (waitMs in FLOW_PALETTE_RETRY_DELAYS_MS) {
            if (waitMs > 0L) delay(waitMs)
            val bitmap = BitmapProvider.peekThumbnail(sourceCoverKey, FLOW_EXTRACT_SIZE, FLOW_EXTRACT_SIZE)
                ?: BitmapProvider.peekAny(sourceCoverKey)
                ?: continue
            if (bitmap.isRecycled) continue

            val extracted = RawFlowPaletteExtractor.extract(bitmap, flowMode, isSystemDark)
            if (extracted.isEmpty()) continue

            val completed = completeExtractedFlowColors(extracted, fallbackColors)
            flowPaletteCache.put(cacheKey, completed)
            targetColors = completed
            appliedAlbumPalette = true
            PowerTraceLogger.flowPalette(
                stage = "memory_hit",
                mode = flowMode.prefValue,
                source = "BitmapProvider.peek",
                colorCount = completed.size,
                elapsedMs = SystemClock.elapsedRealtime() - paletteStartMs,
                coverKey = sourceCoverKey
            )
            break
        }

        if (!appliedAlbumPalette) {
            targetColors = fallbackColors
            PowerTraceLogger.flowPalette(
                stage = "load_miss",
                mode = flowMode.prefValue,
                source = "none",
                colorCount = targetColors.size,
                elapsedMs = SystemClock.elapsedRealtime() - paletteStartMs,
                coverKey = sourceCoverKey
            )
        }
    }

    val colors = remember(targetColors, fallbackColors) {
        targetColors.ifEmpty { fallbackColors }.take(FLOW_MAX_COLOR_COUNT)
    }

    val baseColor = remember(flowMode, isSystemDark, colors) {
        baseFlowColor(flowMode, isSystemDark, colors.firstOrNull())
    }
    val canMove = flowMode != RawFlowMode.UNIVERSAL
    val timeSeconds = rememberRawFlowTimeSeconds(
        enabled = motionEnabled && canMove,
        modeName = flowMode.prefValue,
        frameIntervalMs = frameIntervalMs.coerceAtLeast(FLOW_FRAME_INTERVAL_MS)
    )
    Canvas(modifier = modifier.fillMaxSize().clipToBounds().background(baseColor)) {
        val maxDimension = max(size.width, size.height)
        val countScale = when (colors.size) {
            1 -> 1.18f
            2 -> 1.05f
            else -> 0.92f
        }
        colors.forEachIndexed { index, color ->
            val seed = flowBlobSeeds()[index % flowBlobSeeds().size]
            val phase = seed.phase + index * 0.83f
            val motion = if (canMove) 1f else 0f
            val x = size.width * seed.baseX +
                size.width * seed.amplitudeX * sin(timeSeconds * seed.speedX + phase) * motion
            val y = size.height * seed.baseY +
                size.height * seed.amplitudeY * cos(timeSeconds * seed.speedY + phase * 1.21f) * motion
            val radiusPulse = 1f + 0.11f * sin(timeSeconds * seed.radiusSpeed + phase)
            val radius = maxDimension * seed.radius * countScale * radiusPulse
            val coreAlpha = if (isSystemDark) 0.88f else 0.82f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = coreAlpha),
                    0.38f to color.copy(alpha = coreAlpha * 0.72f),
                    0.72f to color.copy(alpha = coreAlpha * 0.28f),
                    1f to Color.Transparent,
                    center = Offset(x, y),
                    radius = radius
                ),
                radius = radius,
                center = Offset(x, y)
            )
        }
    }
}

@Composable
private fun rememberRawFlowTimeSeconds(
    enabled: Boolean,
    modeName: String,
    frameIntervalMs: Long
): Float {
    var timeSeconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled, modeName, frameIntervalMs) {
        if (!enabled) {
            // Pause on the current visual phase instead of snapping back to the canonical texture.
            // Non-flow pages can therefore stop the animation, and flow pages resume without a
            // one-frame non-flow/static-background flash.
            PowerTraceLogger.flowFrame(
                mode = modeName,
                enabled = false,
                frameIntervalMs = frameIntervalMs
            )
            return@LaunchedEffect
        }
        val startMs = SystemClock.uptimeMillis() - (timeSeconds * 1000f).roundToInt()
        while (true) {
            timeSeconds = ((SystemClock.uptimeMillis() - startMs).coerceAtLeast(0L) / 1000f)
            PowerTraceLogger.flowFrame(
                mode = modeName,
                enabled = true,
                frameIntervalMs = frameIntervalMs
            )
            delay(frameIntervalMs)
        }
    }
    return timeSeconds
}

@Composable
fun RawFlowModeDialog(
    show: Boolean,
    selectedMode: RawFlowMode,
    onSelectMode: (RawFlowMode) -> Unit,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val isSystemDark = rememberRawFlowIsDarkTheme()
    val fallbackMode = if (isSystemDark) RawFlowMode.DARK else RawFlowMode.LIGHT
    val displayMode = RawFlowRuntimeState.mode ?: selectedMode
    val flowEnabled = displayMode != RawFlowMode.OFF
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var predictiveBackActive by remember { mutableStateOf(false) }
    val visualBackProgress by animateFloatAsState(
        targetValue = predictiveBackProgress,
        animationSpec = if (predictiveBackActive || predictiveBackProgress == 0f) {
            snap()
        } else {
            tween(durationMillis = 150)
        },
        label = "raw-flow-dialog-predictive-back"
    )

    LaunchedEffect(show) {
        predictiveBackActive = false
        predictiveBackProgress = 0f
    }

    fun selectMode(mode: RawFlowMode) {
        RawFlowRuntimeState.persistAndUpdate(context, mode)
        onSelectMode(mode)
    }

    WindowDialog(
        show = show,
        modifier = Modifier.graphicsLayer {
            val progress = visualBackProgress.coerceIn(0f, 1f)
            translationY = progress * 72.dp.toPx()
            val scale = 1f - progress * 0.08f
            scaleX = scale
            scaleY = scale
            alpha = 1f - progress * 0.14f
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
        title = stringResource(R.string.flow_background_dialog_title),
        summary = stringResource(R.string.flow_background_dialog_summary),
        onDismissRequest = onDismissRequest
    ) {
        PredictiveBackHandler(enabled = show) { events ->
            predictiveBackActive = true
            try {
                events.collect { event ->
                    predictiveBackProgress = event.progress.coerceIn(0f, 1f)
                }
                predictiveBackActive = false
                predictiveBackProgress = 1f
                onDismissRequest()
            } catch (_: kotlinx.coroutines.CancellationException) {
                predictiveBackActive = false
                predictiveBackProgress = 0f
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RawFlowEnableRow(
                checked = flowEnabled,
                onCheckedChange = { enabled ->
                    selectMode(if (enabled) fallbackMode else RawFlowMode.OFF)
                }
            )
            RawFlowModeRow(
                title = stringResource(R.string.flow_background_mode_dark_title),
                summary = stringResource(R.string.flow_background_mode_dark_summary),
                colors = defaultFlowColors(RawFlowMode.DARK, isSystemDark = true),
                selected = displayMode == RawFlowMode.DARK,
                onClick = {
                    selectMode(RawFlowMode.DARK)
                    onDismissRequest()
                }
            )
            RawFlowModeRow(
                title = stringResource(R.string.flow_background_mode_light_title),
                summary = stringResource(R.string.flow_background_mode_light_summary),
                colors = defaultFlowColors(RawFlowMode.LIGHT, isSystemDark = false),
                selected = displayMode == RawFlowMode.LIGHT,
                onClick = {
                    selectMode(RawFlowMode.LIGHT)
                    onDismissRequest()
                }
            )
            RawFlowModeRow(
                title = stringResource(R.string.flow_background_mode_universal_title),
                summary = stringResource(R.string.flow_background_mode_universal_summary),
                colors = defaultFlowColors(RawFlowMode.UNIVERSAL, isSystemDark = false),
                selected = displayMode == RawFlowMode.UNIVERSAL,
                onClick = {
                    selectMode(RawFlowMode.UNIVERSAL)
                    onDismissRequest()
                }
            )
        }
    }
}

@Composable
private fun RawFlowEnableRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.45f))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = stringResource(R.string.flow_background_enable_title),
                color = scheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.flow_background_enable_summary),
                color = scheme.onSurfaceVariantSummary,
                fontSize = 13.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun RawFlowModeRow(
    title: String,
    summary: String,
    colors: List<Color>,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val rowColor = if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(rowColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        FlowColorPreview(colors = colors)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(
                text = title,
                color = scheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = summary,
                color = scheme.onSurfaceVariantSummary,
                fontSize = 13.sp
            )
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun FlowColorPreview(colors: List<Color>) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(15.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                Brush.linearGradient(
                    colors = colors.take(FLOW_MAX_COLOR_COUNT),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            colors.take(FLOW_MAX_COLOR_COUNT).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(color)
                )
            }
        }
    }
}

private data class FlowBlobSeed(
    val baseX: Float,
    val baseY: Float,
    val amplitudeX: Float,
    val amplitudeY: Float,
    val radius: Float,
    val alpha: Float,
    val phase: Float,
    val speedX: Float,
    val speedY: Float,
    val secondarySpeedX: Float,
    val secondarySpeedY: Float,
    val radiusSpeed: Float,
    val alphaSpeed: Float
)

private fun flowBlobSeeds(): List<FlowBlobSeed> {
    return listOf(
        FlowBlobSeed(0.08f, 0.17f, 0.38f, 0.30f, 0.45f, 0.54f, 0.20f, 0.91f, 0.63f, 0.37f, 0.49f, 0.43f, 0.61f),
        FlowBlobSeed(0.86f, 0.14f, 0.31f, 0.33f, 0.40f, 0.48f, 1.70f, 0.57f, 0.83f, 0.41f, 0.31f, 0.55f, 0.47f),
        FlowBlobSeed(0.28f, 0.63f, 0.34f, 0.35f, 0.50f, 0.50f, 3.10f, 0.77f, 0.45f, 0.29f, 0.58f, 0.38f, 0.53f),
        FlowBlobSeed(0.88f, 0.78f, 0.30f, 0.29f, 0.43f, 0.46f, 4.40f, 0.43f, 0.73f, 0.54f, 0.36f, 0.49f, 0.67f),
        FlowBlobSeed(0.48f, 0.42f, 0.29f, 0.38f, 0.39f, 0.44f, 5.35f, 0.69f, 0.57f, 0.47f, 0.39f, 0.52f, 0.59f)
    )
}

private object RawFlowPaletteExtractor {
    fun extract(bitmap: android.graphics.Bitmap, mode: RawFlowMode, isSystemDark: Boolean): List<Color> {
        if (bitmap.isRecycled) return emptyList()

        val paletteCandidates = Palette.from(bitmap)
            .maximumColorCount(24)
            .clearFilters()
            .generate()
            .swatches
            .mapNotNull { swatch ->
                normalizeColor(swatch.rgb, mode, isSystemDark)?.let { color ->
                    ScoredFlowColor(
                        color = color,
                        score = swatch.population * colorVisualWeight(color)
                    )
                }
            }

        val sampledCandidates = sampleBitmapColors(bitmap, mode, isSystemDark)
        val candidates = (paletteCandidates + sampledCandidates)
            .sortedByDescending { it.score }

        return selectAdaptiveColors(candidates)
    }

    private fun sampleBitmapColors(
        bitmap: android.graphics.Bitmap,
        mode: RawFlowMode,
        isSystemDark: Boolean
    ): List<ScoredFlowColor> {
        val width = bitmap.width.coerceAtLeast(1)
        val height = bitmap.height.coerceAtLeast(1)
        val stepX = (width / FLOW_SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (height / FLOW_SAMPLE_GRID).coerceAtLeast(1)
        val buckets = LinkedHashMap<Int, MutableColorBucket>()
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val pixel = bitmap.getPixel(x, y)
                val alpha = AndroidColor.alpha(pixel)
                if (alpha >= 180) {
                    val hsv = FloatArray(3)
                    AndroidColor.colorToHSV(pixel, hsv)
                    val hueBucket = (hsv[0] / 18f).roundToInt()
                    val satBucket = (hsv[1] * 4f).roundToInt()
                    val valueBucket = (hsv[2] * 4f).roundToInt()
                    val key = hueBucket * 100 + satBucket * 10 + valueBucket
                    val bucket = buckets.getOrPut(key) { MutableColorBucket() }
                    bucket.add(pixel)
                }
                x += stepX
            }
            y += stepY
        }
        return buckets.values.mapNotNull { bucket ->
            normalizeColor(bucket.rgb(), mode, isSystemDark)?.let { color ->
                ScoredFlowColor(
                    color = color,
                    score = bucket.count * 24f * colorVisualWeight(color)
                )
            }
        }
    }

    private fun normalizeColor(rgb: Int, mode: RawFlowMode, isSystemDark: Boolean): Color? {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(rgb, hsv)
        val rawColor = colorFromArgb(AndroidColor.rgb(AndroidColor.red(rgb), AndroidColor.green(rgb), AndroidColor.blue(rgb)))
        val luminance = rawColor.luminance()
        if (luminance < 0.015f || luminance > 0.985f) return null

        val valueRange = when (mode) {
            RawFlowMode.LIGHT -> if (isSystemDark) 0.34f..0.88f else 0.38f..0.94f
            RawFlowMode.DARK -> if (isSystemDark) 0.20f..0.76f else 0.30f..0.82f
            RawFlowMode.UNIVERSAL,
            RawFlowMode.OFF -> return null
        }
        if (hsv[1] >= 0.10f) {
            hsv[1] = (hsv[1] * 1.06f + 0.02f).coerceIn(0.14f, 0.96f)
        }
        hsv[2] = hsv[2].coerceIn(valueRange.start, valueRange.endInclusive)
        return colorFromArgb(AndroidColor.HSVToColor(0xFF, hsv))
    }

    private fun colorVisualWeight(color: Color): Float {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(color.toArgbNoAlpha(), hsv)
        return 0.82f + hsv[1] * 0.48f
    }

    private fun selectAdaptiveColors(candidates: List<ScoredFlowColor>): List<Color> {
        val strongest = candidates.firstOrNull()?.score ?: return emptyList()
        val selected = mutableListOf<Color>()
        candidates.forEach { candidate ->
            if (selected.size >= FLOW_MAX_COLOR_COUNT) return@forEach
            if (candidate.score < strongest * 0.045f) return@forEach
            if (selected.none { existing -> perceptualColorDistance(existing, candidate.color) >= 0.17f }) {
                selected += candidate.color
            }
        }
        return selected.ifEmpty { listOf(candidates.first().color) }
    }
}

private data class ScoredFlowColor(
    val color: Color,
    val score: Float
)

private class MutableColorBucket {
    var r: Long = 0L
    var g: Long = 0L
    var b: Long = 0L
    var count: Int = 0
        private set

    fun add(argb: Int) {
        r += AndroidColor.red(argb).toLong()
        g += AndroidColor.green(argb).toLong()
        b += AndroidColor.blue(argb).toLong()
        count++
    }

    fun rgb(): Int {
        if (count <= 0) return AndroidColor.rgb(0, 0, 0)
        return AndroidColor.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
    }
}

private fun completeExtractedFlowColors(extracted: List<Color>, fallback: List<Color>): List<Color> {
    if (extracted.isEmpty()) return fallback
    return extracted.take(FLOW_MAX_COLOR_COUNT)
}

private fun perceptualColorDistance(a: Color, b: Color): Float {
    val hsvA = FloatArray(3)
    val hsvB = FloatArray(3)
    AndroidColor.colorToHSV(a.toArgbNoAlpha(), hsvA)
    AndroidColor.colorToHSV(b.toArgbNoAlpha(), hsvB)
    val rawHueDistance = abs(hsvA[0] - hsvB[0])
    val hueDistance = minOf(rawHueDistance, 360f - rawHueDistance) / 180f
    val chromaWeight = ((hsvA[1] + hsvB[1]) * 0.75f).coerceIn(0f, 1f)
    val hue = hueDistance * 0.68f * chromaWeight
    val saturation = abs(hsvA[1] - hsvB[1]) * 0.46f
    val value = abs(hsvA[2] - hsvB[2]) * 0.58f
    return kotlin.math.sqrt(hue * hue + saturation * saturation + value * value)
}

private fun defaultFlowColors(mode: RawFlowMode, isSystemDark: Boolean): List<Color> {
    val darkPalette = listOf(
        Color(0xFF67377B),
        Color(0xFF7B324E),
        Color(0xFFA27334),
        Color(0xFF28557D)
    )
    val lightPalette = listOf(
        Color(0xFFF5A9C1),
        Color(0xFFF2CF65),
        Color(0xFFC2ABF4),
        Color(0xFF91D7D8)
    )
    return when (mode) {
        RawFlowMode.DARK, RawFlowMode.LIGHT -> if (isSystemDark) darkPalette else lightPalette
        RawFlowMode.UNIVERSAL -> if (isSystemDark) {
            listOf(
                Color(0xFF684180),
                Color(0xFF914F68),
                Color(0xFFA67A3B),
                Color(0xFF376986)
            )
        } else {
            listOf(
                Color(0xFFF4A9C5),
                Color(0xFFF1CF62),
                Color(0xFFC5AEF2),
                Color(0xFFFFB19C)
            )
        }
        RawFlowMode.OFF -> if (isSystemDark) {
            listOf(Color(0xFF0B0911), Color(0xFF0B0911), Color(0xFF0B0911), Color(0xFF0B0911))
        } else {
            listOf(Color(0xFFFFFAF4), Color(0xFFFFFAF4), Color(0xFFFFFAF4), Color(0xFFFFFAF4))
        }
    }
}

private fun baseFlowColor(mode: RawFlowMode, isSystemDark: Boolean, dominant: Color?): Color {
    if (dominant == null || mode == RawFlowMode.UNIVERSAL || mode == RawFlowMode.OFF) {
        return if (isSystemDark) Color(0xFF0B0911) else Color(0xFFFFFAF4)
    }
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(dominant.toArgbNoAlpha(), hsv)
    if (isSystemDark) {
        hsv[1] = (hsv[1] * 0.58f).coerceIn(0.10f, 0.48f)
        hsv[2] = 0.13f
    } else {
        hsv[1] = (hsv[1] * 0.42f).coerceIn(0.08f, 0.34f)
        hsv[2] = 0.92f
    }
    return colorFromArgb(AndroidColor.HSVToColor(0xFF, hsv))
}

private fun androidColorIntToComposeColor(colorInt: Int): Color {
    return Color(
        red = AndroidColor.red(colorInt) / 255f,
        green = AndroidColor.green(colorInt) / 255f,
        blue = AndroidColor.blue(colorInt) / 255f,
        alpha = AndroidColor.alpha(colorInt) / 255f
    )
}

private fun Color.toAndroidArgb(alphaOverride: Float = alpha): Int {
    return AndroidColor.argb(
        (alphaOverride * 255f).roundToInt().coerceIn(0, 255),
        (red * 255f).roundToInt().coerceIn(0, 255),
        (green * 255f).roundToInt().coerceIn(0, 255),
        (blue * 255f).roundToInt().coerceIn(0, 255)
    )
}

private fun Color.toArgbNoAlpha(): Int {
    return AndroidColor.rgb(
        (red * 255f).toInt().coerceIn(0, 255),
        (green * 255f).toInt().coerceIn(0, 255),
        (blue * 255f).toInt().coerceIn(0, 255)
    )
}

private fun colorFromArgb(argb: Int): Color {
    return Color(
        red = AndroidColor.red(argb) / 255f,
        green = AndroidColor.green(argb) / 255f,
        blue = AndroidColor.blue(argb) / 255f,
        alpha = AndroidColor.alpha(argb) / 255f
    )
}
