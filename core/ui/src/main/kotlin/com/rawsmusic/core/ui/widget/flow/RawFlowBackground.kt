package com.rawsmusic.core.ui.widget.flow

import android.content.Context
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Color as AndroidColor
import android.util.LruCache
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.style.TextAlign
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
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog

enum class RawBackgroundSurface {
    SCENE,
    PLAYER
}

private const val FLOW_PREFS = "raw_flow_background"
private const val FLOW_MODE_KEY = "mode"
private const val FLOW_STYLE_KEY = "background_style"
private const val FLOW_SPEED_KEY = "motion_speed"
private const val FLOW_SATURATION_KEY = "color_saturation"
private const val FLOW_BRIGHTNESS_KEY = "color_brightness"
private const val STATIC_GRADIENT_KEY = "static_gradient"
private const val STATIC_BLUR_KEY = "static_blur"
private const val STATIC_DETAIL_KEY = "static_detail"
private const val STATIC_BRIGHTNESS_KEY = "static_brightness"
private const val STATIC_SATURATION_KEY = "static_saturation"
private const val FLOW_MAX_COLOR_COUNT = 5
private const val FLOW_FRAME_INTERVAL_MS = 16L
private const val FLOW_DISABLED_FRAME_INTERVAL_MS = 0L
private const val FLOW_EXTRACT_SIZE = 96
private val FLOW_GLOBAL_EPOCH_NS = System.nanoTime()
private val FLOW_PALETTE_RETRY_DELAYS_MS = longArrayOf(0L, 420L, 1400L)
private val flowPaletteCache = LruCache<String, List<Color>>(48)

fun clearRawFlowMemoryCache() {
    flowPaletteCache.evictAll()
}

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

enum class RawBackgroundStyle(val prefValue: String) {
    FLOW("flow"),
    STATIC("static"),
    SIMPLE("simple")
}

object RawFlowTuningState {
    var style by mutableStateOf(RawBackgroundStyle.FLOW)
        private set
    var speed by mutableFloatStateOf(2f)
        private set
    var saturation by mutableFloatStateOf(1f)
        private set
    var brightness by mutableFloatStateOf(1f)
        private set
    var staticGradient by mutableFloatStateOf(4f)
        private set
    var staticBlur by mutableFloatStateOf(5f)
        private set
    var staticDetail by mutableFloatStateOf(5f)
        private set
    var staticBrightness by mutableFloatStateOf(1f)
        private set
    var staticSaturation by mutableFloatStateOf(1.5f)
        private set
    var revision by mutableIntStateOf(0)
        private set

    private var initialized = false

    fun ensureInitialized(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(FLOW_PREFS, Context.MODE_PRIVATE)
        style = RawBackgroundStyle.values().firstOrNull {
            it.prefValue == prefs.getString(FLOW_STYLE_KEY, RawBackgroundStyle.FLOW.prefValue)
        } ?: RawBackgroundStyle.FLOW
        speed = prefs.getFloat(FLOW_SPEED_KEY, 2f).coerceIn(0.5f, 4f)
        saturation = prefs.getFloat(FLOW_SATURATION_KEY, 1f).coerceIn(0.5f, 1.6f)
        brightness = prefs.getFloat(FLOW_BRIGHTNESS_KEY, 1f).coerceIn(0.65f, 1.35f)
        staticGradient = prefs.getFloat(STATIC_GRADIENT_KEY, 4f).coerceIn(0f, 10f)
        staticBlur = prefs.getFloat(STATIC_BLUR_KEY, 5f).coerceIn(0f, 15f)
        staticDetail = prefs.getFloat(STATIC_DETAIL_KEY, 5f).coerceIn(0f, 10f)
        staticBrightness = prefs.getFloat(STATIC_BRIGHTNESS_KEY, 1f).coerceIn(0f, 2.5f)
        staticSaturation = prefs.getFloat(STATIC_SATURATION_KEY, 1.5f).coerceIn(0f, 3f)
        initialized = true
    }

    fun setStyle(context: Context, value: RawBackgroundStyle) {
        ensureInitialized(context)
        style = value
        persist(context)
    }

    fun setSpeed(context: Context, value: Float) {
        ensureInitialized(context)
        speed = value.coerceIn(0.5f, 4f)
        persist(context)
    }

    fun setSaturation(context: Context, value: Float) {
        ensureInitialized(context)
        saturation = value.coerceIn(0.5f, 1.6f)
        persist(context)
    }

    fun setBrightness(context: Context, value: Float) {
        ensureInitialized(context)
        brightness = value.coerceIn(0.65f, 1.35f)
        persist(context)
    }

    fun setStaticGradient(context: Context, value: Float) {
        ensureInitialized(context)
        staticGradient = value.coerceIn(0f, 10f)
        persist(context)
    }

    fun setStaticBlur(context: Context, value: Float) {
        ensureInitialized(context)
        staticBlur = value.coerceIn(0f, 15f)
        persist(context)
    }

    fun setStaticDetail(context: Context, value: Float) {
        ensureInitialized(context)
        staticDetail = value.coerceIn(0f, 10f)
        persist(context)
    }

    fun setStaticBrightness(context: Context, value: Float) {
        ensureInitialized(context)
        staticBrightness = value.coerceIn(0f, 2.5f)
        persist(context)
    }

    fun setStaticSaturation(context: Context, value: Float) {
        ensureInitialized(context)
        staticSaturation = value.coerceIn(0f, 3f)
        persist(context)
    }

    fun resetStatic(context: Context) {
        ensureInitialized(context)
        staticGradient = 4f
        staticBlur = 5f
        staticDetail = 5f
        staticBrightness = 1f
        staticSaturation = 1.5f
        persist(context)
    }

    private fun persist(context: Context) {
        context.applicationContext.getSharedPreferences(FLOW_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(FLOW_STYLE_KEY, style.prefValue)
            .putFloat(FLOW_SPEED_KEY, speed)
            .putFloat(FLOW_SATURATION_KEY, saturation)
            .putFloat(FLOW_BRIGHTNESS_KEY, brightness)
            .putFloat(STATIC_GRADIENT_KEY, staticGradient)
            .putFloat(STATIC_BLUR_KEY, staticBlur)
            .putFloat(STATIC_DETAIL_KEY, staticDetail)
            .putFloat(STATIC_BRIGHTNESS_KEY, staticBrightness)
            .putFloat(STATIC_SATURATION_KEY, staticSaturation)
            .apply()
        revision++
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

private fun flowPaletteCacheKey(
    mode: RawFlowMode,
    isSystemDark: Boolean,
    sourceCoverKey: String?
): String = "${mode.prefValue}:$isSystemDark:${sourceCoverKey.orEmpty()}"

private fun resolveFlowColorsFromMemory(
    sourceCoverKey: String?,
    mode: RawFlowMode,
    isSystemDark: Boolean,
    fallbackColors: List<Color>
): List<Color>? {
    if (sourceCoverKey.isNullOrBlank() || mode == RawFlowMode.UNIVERSAL || mode == RawFlowMode.OFF) {
        return null
    }
    val cacheKey = flowPaletteCacheKey(mode, isSystemDark, sourceCoverKey)
    flowPaletteCache.get(cacheKey)?.let { return it }
    val bitmap = BitmapProvider.peekThumbnail(
        sourceCoverKey,
        FLOW_EXTRACT_SIZE,
        FLOW_EXTRACT_SIZE
    ) ?: BitmapProvider.peekAny(sourceCoverKey)
    if (bitmap == null || bitmap.isRecycled) return null
    val extracted = RawFlowPaletteExtractor.extract(bitmap, mode, isSystemDark)
    if (extracted.isEmpty()) return null
    return completeExtractedFlowColors(extracted, fallbackColors).also {
        flowPaletteCache.put(cacheKey, it)
    }
}

@Composable
fun RawFlowBackground(
    mode: RawFlowMode,
    sourceCoverKey: String?,
    fallbackSourceCoverKey: String? = null,
    sourceArtwork: AndroidBitmap? = null,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    motionEnabled: Boolean = true,
    frameIntervalMs: Long = FLOW_FRAME_INTERVAL_MS,
    surface: RawBackgroundSurface = RawBackgroundSurface.SCENE
) {
    val context = LocalContext.current.applicationContext
    RawFlowTuningState.ensureInitialized(context)
    val tuningRevision = RawFlowTuningState.revision
    val backgroundStyle = RawFlowTuningState.style
    val motionSpeed = RawFlowTuningState.speed
    val saturationScale = RawFlowTuningState.saturation
    val brightnessScale = RawFlowTuningState.brightness
    val staticGradient = RawFlowTuningState.staticGradient
    val staticBlur = RawFlowTuningState.staticBlur
    val staticDetail = RawFlowTuningState.staticDetail
    val staticBrightness = RawFlowTuningState.staticBrightness
    val staticSaturation = RawFlowTuningState.staticSaturation
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
    if (backgroundStyle == RawBackgroundStyle.SIMPLE) {
        Box(modifier = modifier.fillMaxSize().background(scheme.background))
        return
    }

    val fallbackColors = remember(flowMode, isSystemDark) { defaultFlowColors(flowMode, isSystemDark) }
    val paletteCacheKey = remember(flowMode, isSystemDark, sourceCoverKey) {
        flowPaletteCacheKey(flowMode, isSystemDark, sourceCoverKey)
    }
    val inheritedColors = remember(
        flowMode,
        isSystemDark,
        fallbackSourceCoverKey,
        fallbackColors
    ) {
        resolveFlowColorsFromMemory(
            sourceCoverKey = fallbackSourceCoverKey,
            mode = flowMode,
            isSystemDark = isSystemDark,
            fallbackColors = fallbackColors
        ) ?: fallbackColors
    }
    val initialColors = remember(
        flowMode,
        isSystemDark,
        sourceCoverKey,
        sourceArtwork,
        inheritedColors,
        fallbackColors
    ) {
        if (flowMode == RawFlowMode.UNIVERSAL || sourceCoverKey.isNullOrBlank()) {
            fallbackColors
        } else {
            sourceArtwork
                ?.takeUnless { it.isRecycled }
                ?.let { RawFlowPaletteExtractor.extract(it, flowMode, isSystemDark) }
                ?.takeIf { it.isNotEmpty() }
                ?.let { completeExtractedFlowColors(it, fallbackColors) }
                ?: resolveFlowColorsFromMemory(
                    sourceCoverKey = sourceCoverKey,
                    mode = flowMode,
                    isSystemDark = isSystemDark,
                    fallbackColors = fallbackColors
                )
                ?: inheritedColors
        }
    }
    var targetColors by remember(flowMode, isSystemDark, sourceCoverKey) {
        mutableStateOf(initialColors)
    }

    LaunchedEffect(
        flowMode,
        runtimeRevision,
        sourceCoverKey,
        sourceArtwork,
        fallbackColors,
        isSystemDark
    ) {
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

        sourceArtwork
            ?.takeUnless { it.isRecycled }
            ?.let { RawFlowPaletteExtractor.extract(it, flowMode, isSystemDark) }
            ?.takeIf { it.isNotEmpty() }
            ?.let { extracted ->
                val completed = completeExtractedFlowColors(extracted, fallbackColors)
                flowPaletteCache.put(paletteCacheKey, completed)
                targetColors = completed
                PowerTraceLogger.flowPalette(
                    stage = "transition_artwork",
                    mode = flowMode.prefValue,
                    source = "sourceArtwork",
                    colorCount = completed.size,
                    elapsedMs = SystemClock.elapsedRealtime() - paletteStartMs,
                    coverKey = sourceCoverKey
                )
                return@LaunchedEffect
            }

        flowPaletteCache.get(paletteCacheKey)?.let { cached ->
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
            flowPaletteCache.put(paletteCacheKey, completed)
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
            PowerTraceLogger.flowPalette(
                stage = "load_miss_inherited",
                mode = flowMode.prefValue,
                source = fallbackSourceCoverKey ?: "default",
                colorCount = targetColors.size,
                elapsedMs = SystemClock.elapsedRealtime() - paletteStartMs,
                coverKey = sourceCoverKey
            )
        }
    }

    val resolvedColors = remember(
        targetColors,
        fallbackColors,
        saturationScale,
        brightnessScale,
        tuningRevision
    ) {
        targetColors.ifEmpty { fallbackColors }
            .take(FLOW_MAX_COLOR_COUNT)
            .map { tuneFlowColor(it, saturationScale, brightnessScale) }
    }
    val animatedColorSlots = List(FLOW_MAX_COLOR_COUNT) { index ->
        val slotTarget = resolvedColors.getOrElse(index) { resolvedColors.last() }
        animateColorAsState(
            targetValue = slotTarget,
            animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
            label = "raw-flow-palette-$index"
        ).value
    }
    val colors = animatedColorSlots.take(resolvedColors.size)

    val targetBaseColor = remember(flowMode, isSystemDark, resolvedColors) {
        baseFlowColor(flowMode, isSystemDark, resolvedColors.firstOrNull())
    }
    val baseColor by animateColorAsState(
        targetValue = targetBaseColor,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "raw-flow-base"
    )
    if (backgroundStyle == RawBackgroundStyle.STATIC) {
        val staticArtwork = remember(
            sourceCoverKey,
            fallbackSourceCoverKey,
            sourceArtwork,
            surface
        ) {
            if (surface != RawBackgroundSurface.PLAYER) {
                null
            } else {
                sourceArtwork?.takeUnless { it.isRecycled }
                    ?: listOfNotNull(sourceCoverKey, fallbackSourceCoverKey)
                        .firstNotNullOfOrNull { key ->
                            BitmapProvider.peekThumbnail(key, FLOW_EXTRACT_SIZE, FLOW_EXTRACT_SIZE)
                                ?: BitmapProvider.peekAny(key)
                        }
                        ?.takeUnless { it.isRecycled }
            }
        }
        val nativeColors = remember(targetColors, fallbackColors) {
            targetColors.ifEmpty { fallbackColors }
                .take(FLOW_MAX_COLOR_COUNT)
                .map { it.toAndroidArgb(alphaOverride = 1f) }
                .toIntArray()
        }
        val staticBitmap = remember(
            nativeColors.contentHashCode(),
            staticArtwork,
            surface,
            staticGradient,
            staticBlur,
            staticDetail,
            staticBrightness,
            staticSaturation,
            tuningRevision
        ) {
            if (surface == RawBackgroundSurface.PLAYER && staticArtwork != null) {
                NativeStaticBackground.createPlayer(
                    artwork = staticArtwork,
                    saturation = staticSaturation,
                    brightness = staticBrightness,
                    gradient = staticGradient,
                    blur = staticBlur,
                    detail = staticDetail
                )
            } else {
                NativeStaticBackground.create(
                    colors = nativeColors,
                    saturation = saturationScale,
                    brightness = brightnessScale
                )
            }
        }
        if (staticBitmap != null) {
            Image(
                bitmap = staticBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = modifier.fillMaxSize().background(baseColor)
            )
            return
        }
    }
    val canMove = true
    val timeSeconds = rememberRawFlowTimeSeconds(
        enabled = motionEnabled && canMove && backgroundStyle == RawBackgroundStyle.FLOW,
        modeName = flowMode.prefValue,
        frameIntervalMs = frameIntervalMs.coerceAtLeast(FLOW_FRAME_INTERVAL_MS)
    )
    Canvas(modifier = modifier.fillMaxSize().clipToBounds().background(baseColor)) {
        if (backgroundStyle == RawBackgroundStyle.STATIC) {
            val matrixColors = colors.ifEmpty { listOf(baseColor) }
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        matrixColors[0].copy(alpha = 0.90f),
                        matrixColors.getOrElse(1) { matrixColors[0] }.copy(alpha = 0.72f),
                        matrixColors.getOrElse(2) { matrixColors.last() }.copy(alpha = 0.82f)
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height)
                )
            )
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        matrixColors.getOrElse(3) { matrixColors.last() }.copy(alpha = 0.48f),
                        Color.Transparent
                    ),
                    start = Offset(size.width, 0f),
                    end = Offset(0f, size.height)
                )
            )
            return@Canvas
        }
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
            val animatedTime = timeSeconds * motionSpeed
            val x = size.width * seed.baseX +
                size.width * seed.amplitudeX * sin(animatedTime * seed.speedX + phase) * motion
            val y = size.height * seed.baseY +
                size.height * seed.amplitudeY * cos(animatedTime * seed.speedY + phase * 1.21f) * motion
            val radiusPulse = 1f + 0.11f * sin(animatedTime * seed.radiusSpeed + phase)
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
        var lastUpdateNs = 0L
        val requestedIntervalNs = frameIntervalMs.coerceAtLeast(1L) * 1_000_000L
        while (true) {
            val frameNs = withFrameNanos { it }
            // A 16 ms preference means "every display frame", not a fixed 60 Hz timer. This lets
            // 90/120 Hz devices animate on their own vsync while retaining throttling for slower
            // background modes that explicitly request a larger interval.
            if (
                frameIntervalMs <= FLOW_FRAME_INTERVAL_MS ||
                lastUpdateNs == 0L ||
                frameNs - lastUpdateNs >= requestedIntervalNs
            ) {
                // Every scene/player instance samples one process-wide clock. Opening the player
                // therefore reveals the same flow phase instead of restarting its blobs at t=0.
                timeSeconds =
                    ((frameNs - FLOW_GLOBAL_EPOCH_NS).coerceAtLeast(0L) / 1_000_000_000f)
                lastUpdateNs = frameNs
            }
            PowerTraceLogger.flowFrame(
                mode = modeName,
                enabled = true,
                frameIntervalMs = frameIntervalMs
            )
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
    var showBackgroundStyleDialog by remember { mutableStateOf(false) }
    var adjustingParameters by remember { mutableStateOf(false) }
    RawFlowTuningState.ensureInitialized(context)
    val isSystemDark = rememberRawFlowIsDarkTheme()
    val displayMode = RawFlowRuntimeState.mode ?: selectedMode
    val backgroundStyle = RawFlowTuningState.style
    val dialogTitle = when (backgroundStyle) {
        RawBackgroundStyle.FLOW -> stringResource(R.string.flow_background_dialog_title)
        RawBackgroundStyle.STATIC -> stringResource(R.string.static_background_dialog_title)
        RawBackgroundStyle.SIMPLE -> stringResource(R.string.simple_background_dialog_title)
    }
    val dialogSummary = when (backgroundStyle) {
        RawBackgroundStyle.FLOW -> stringResource(R.string.flow_background_dialog_summary)
        RawBackgroundStyle.STATIC -> stringResource(R.string.static_background_dialog_summary)
        RawBackgroundStyle.SIMPLE -> stringResource(R.string.simple_background_dialog_summary)
    }
    fun selectMode(mode: RawFlowMode) {
        RawFlowRuntimeState.persistAndUpdate(context, mode)
        onSelectMode(mode)
    }

    RawMiuixOverlayDialog(
        show = show && !showBackgroundStyleDialog,
        title = dialogTitle,
        summary = dialogSummary,
        onDismissRequest = {
            if (adjustingParameters) adjustingParameters = false else onDismissRequest()
        },
        renderInRootScaffold = true
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (backgroundStyle != RawBackgroundStyle.SIMPLE) {
                    Text(
                        text = if (adjustingParameters) {
                            stringResource(R.string.flow_background_cancel_adjustment)
                        } else {
                            stringResource(R.string.flow_background_adjust_parameters)
                        },
                        color = MiuixTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { adjustingParameters = !adjustingParameters }
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                } else {
                    Spacer(modifier = Modifier)
                }
                IconButton(onClick = { showBackgroundStyleDialog = true }) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Settings,
                        contentDescription = stringResource(R.string.flow_background_style_settings)
                    )
                }
            }
            if (adjustingParameters && backgroundStyle != RawBackgroundStyle.SIMPLE) {
                RawFlowParameterControls(context = context, backgroundStyle = backgroundStyle)
            } else when (backgroundStyle) {
                RawBackgroundStyle.FLOW -> {
                    RawFlowModeRow(
                        title = stringResource(R.string.flow_background_mode_dark_title),
                        summary = stringResource(R.string.flow_background_mode_dark_summary),
                        colors = defaultFlowColors(RawFlowMode.DARK, isSystemDark = true),
                        selected = displayMode == RawFlowMode.DARK,
                        onClick = { selectMode(RawFlowMode.DARK) }
                    )
                    RawFlowModeRow(
                        title = stringResource(R.string.flow_background_mode_light_title),
                        summary = stringResource(R.string.flow_background_mode_light_summary),
                        colors = defaultFlowColors(RawFlowMode.LIGHT, isSystemDark = false),
                        selected = displayMode == RawFlowMode.LIGHT,
                        onClick = { selectMode(RawFlowMode.LIGHT) }
                    )
                    RawFlowModeRow(
                        title = stringResource(R.string.flow_background_mode_universal_title),
                        summary = stringResource(R.string.flow_background_mode_universal_summary),
                        colors = defaultFlowColors(RawFlowMode.UNIVERSAL, isSystemDark = false),
                        selected = displayMode == RawFlowMode.UNIVERSAL,
                        onClick = { selectMode(RawFlowMode.UNIVERSAL) }
                    )
                }
                RawBackgroundStyle.STATIC -> RawBackgroundStyleSummary(
                    title = stringResource(R.string.static_background_follow_cover_title),
                    summary = stringResource(R.string.static_background_follow_cover_summary)
                )
                RawBackgroundStyle.SIMPLE -> RawBackgroundStyleSummary(
                    title = stringResource(R.string.simple_background_active_title),
                    summary = stringResource(R.string.simple_background_active_summary)
                )
            }
        }
    }
    RawBackgroundStyleDialog(
        show = show && showBackgroundStyleDialog,
        onDismissRequest = { showBackgroundStyleDialog = false }
    )
}

@Composable
private fun RawBackgroundStyleDialog(
    show: Boolean,
    onDismissRequest: () -> Unit
) {
    val context = LocalContext.current.applicationContext
    RawFlowTuningState.ensureInitialized(context)
    val selectedStyle = RawFlowTuningState.style
    RawMiuixOverlayDialog(
        show = show,
        title = stringResource(R.string.flow_background_style_title),
        summary = stringResource(R.string.flow_background_style_summary),
        onDismissRequest = onDismissRequest,
        renderInRootScaffold = true
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                Triple(
                    RawBackgroundStyle.FLOW,
                    stringResource(R.string.flow_background_style_flow),
                    stringResource(R.string.flow_background_style_flow_summary)
                ),
                Triple(
                    RawBackgroundStyle.STATIC,
                    stringResource(R.string.flow_background_style_static),
                    stringResource(R.string.flow_background_style_static_summary)
                ),
                Triple(
                    RawBackgroundStyle.SIMPLE,
                    stringResource(R.string.flow_background_style_simple),
                    stringResource(R.string.flow_background_style_simple_summary)
                )
            ).forEach { (style, title, summary) ->
                RawFlowModeRow(
                    title = title,
                    summary = summary,
                    colors = defaultFlowColors(RawFlowMode.UNIVERSAL, isSystemDark = false),
                    selected = selectedStyle == style,
                    onClick = {
                        RawFlowTuningState.setStyle(context, style)
                    }
                )
            }
        }
    }
}

@Composable
private fun RawFlowParameterControls(
    context: Context,
    backgroundStyle: RawBackgroundStyle
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (backgroundStyle == RawBackgroundStyle.STATIC) {
            RawFlowParameterSlider(
                title = stringResource(R.string.static_background_gradient_parameter),
                value = RawFlowTuningState.staticGradient,
                valueRange = 0f..10f,
                valueText = RawFlowTuningState.staticGradient.roundToInt().toString(),
                onValueChange = {
                    RawFlowTuningState.setStaticGradient(context, it.roundToInt().toFloat())
                }
            )
            RawFlowParameterSlider(
                title = stringResource(R.string.static_background_blur_parameter),
                value = RawFlowTuningState.staticBlur,
                valueRange = 0f..15f,
                valueText = RawFlowTuningState.staticBlur.roundToInt().toString(),
                onValueChange = {
                    RawFlowTuningState.setStaticBlur(context, it.roundToInt().toFloat())
                }
            )
            RawFlowParameterSlider(
                title = stringResource(R.string.static_background_detail_parameter),
                value = RawFlowTuningState.staticDetail,
                valueRange = 0f..10f,
                valueText = RawFlowTuningState.staticDetail.roundToInt().toString(),
                onValueChange = {
                    RawFlowTuningState.setStaticDetail(context, it.roundToInt().toFloat())
                }
            )
            RawFlowParameterSlider(
                title = stringResource(R.string.static_background_brightness_parameter),
                value = RawFlowTuningState.staticBrightness,
                valueRange = 0f..2.5f,
                valueText = "${(RawFlowTuningState.staticBrightness * 100).roundToInt()}%",
                onValueChange = { RawFlowTuningState.setStaticBrightness(context, it) }
            )
            RawFlowParameterSlider(
                title = stringResource(R.string.static_background_saturation_parameter),
                value = RawFlowTuningState.staticSaturation,
                valueRange = 0f..3f,
                valueText = "${(RawFlowTuningState.staticSaturation * 100).roundToInt()}%",
                onValueChange = { RawFlowTuningState.setStaticSaturation(context, it) }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
                    .clickable { RawFlowTuningState.resetStatic(context) }
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.static_background_restore_defaults),
                    color = MiuixTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
            }
            return@Column
        }
        if (backgroundStyle == RawBackgroundStyle.FLOW) {
            RawFlowParameterSlider(
                title = stringResource(R.string.flow_background_speed_parameter),
                value = RawFlowTuningState.speed,
                valueRange = 0.5f..4f,
                valueText = "${"%.1f".format(RawFlowTuningState.speed)}×",
                onValueChange = { RawFlowTuningState.setSpeed(context, it) }
            )
        }
        RawFlowParameterSlider(
            title = stringResource(R.string.flow_background_saturation_parameter),
            value = RawFlowTuningState.saturation,
            valueRange = 0.5f..1.6f,
            valueText = "${(RawFlowTuningState.saturation * 100).roundToInt()}%",
            onValueChange = { RawFlowTuningState.setSaturation(context, it) }
        )
        RawFlowParameterSlider(
            title = stringResource(R.string.flow_background_brightness_parameter),
            value = RawFlowTuningState.brightness,
            valueRange = 0.65f..1.35f,
            valueText = "${(RawFlowTuningState.brightness * 100).roundToInt()}%",
            onValueChange = { RawFlowTuningState.setBrightness(context, it) }
        )
    }
}

@Composable
private fun RawBackgroundStyleSummary(
    title: String,
    summary: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MiuixTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = title, color = MiuixTheme.colorScheme.onSurface, fontSize = 16.sp)
        Text(
            text = summary,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun RawFlowParameterSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueText: String,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = MiuixTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(64.dp)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
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

        // Use the classic palette pipeline: filtered median-cut
        // quantization capped at 16 colors, followed by the six standard saturation/lightness
        // targets. Keep that selection behavior while retaining up to five distinct colors for
        // RawS Music's multi-blob renderer.
        val palette = Palette.from(bitmap)
            .maximumColorCount(16)
            .generate()
        val targetSwatches = if (mode == RawFlowMode.DARK || isSystemDark) {
            listOf(
                palette.darkVibrantSwatch,
                palette.vibrantSwatch,
                palette.darkMutedSwatch,
                palette.mutedSwatch,
                palette.lightVibrantSwatch,
                palette.lightMutedSwatch
            )
        } else {
            listOf(
                palette.lightVibrantSwatch,
                palette.vibrantSwatch,
                palette.lightMutedSwatch,
                palette.mutedSwatch,
                palette.darkVibrantSwatch,
                palette.darkMutedSwatch
            )
        }
        val orderedSwatches = (targetSwatches + palette.swatches)
            .filterNotNull()
            .distinctBy { it.rgb }
        val paletteCandidates = orderedSwatches
            .mapIndexedNotNull { index, swatch ->
                normalizeColor(swatch.rgb, mode, isSystemDark)?.let { color ->
                    ScoredFlowColor(
                        color = color,
                        score = swatch.population *
                            colorVisualWeight(color) *
                            (1.35f - index.coerceAtMost(6) * 0.05f)
                    )
                }
            }
        return selectAdaptiveColors(paletteCandidates.sortedByDescending { it.score })
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

private fun tuneFlowColor(color: Color, saturationScale: Float, brightnessScale: Float): Color {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(color.toArgbNoAlpha(), hsv)
    hsv[1] = (hsv[1] * saturationScale).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * brightnessScale).coerceIn(0.08f, 1f)
    return colorFromArgb(AndroidColor.HSVToColor(0xFF, hsv))
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
