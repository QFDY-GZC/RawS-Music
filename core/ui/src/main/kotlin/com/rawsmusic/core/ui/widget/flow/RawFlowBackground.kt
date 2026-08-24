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
import androidx.compose.runtime.produceState
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
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.CoilArtworkRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
    sourceCoverKey: String?,
    backgroundStyle: RawBackgroundStyle = RawBackgroundStyle.FLOW,
): String = "${backgroundStyle.prefValue}:${mode.prefValue}:$isSystemDark:${sourceCoverKey.orEmpty()}"

private fun resolveFlowColorsFromMemory(
    context: Context,
    sourceCoverKey: String?,
    mode: RawFlowMode,
    isSystemDark: Boolean,
    fallbackColors: List<Color>,
    backgroundStyle: RawBackgroundStyle = RawBackgroundStyle.FLOW,
): List<Color>? {
    @Suppress("UNUSED_PARAMETER") val ignoredContext = context
    @Suppress("UNUSED_PARAMETER") val ignoredFallback = fallbackColors
    if (
        sourceCoverKey.isNullOrBlank() ||
        mode == RawFlowMode.OFF ||
        (backgroundStyle != RawBackgroundStyle.STATIC && mode == RawFlowMode.UNIVERSAL)
    ) {
        return null
    }
    // Composition may only peek an already prepared palette. Palette extraction/bitmap work is
    // intentionally kept out of the UI phase; programmatic track changes used to do this exactly
    // when the first AA frame started, producing the visible start hitch.
    return flowPaletteCache.get(
        flowPaletteCacheKey(mode, isSystemDark, sourceCoverKey, backgroundStyle)
    )
}


private suspend fun loadRawFlowEndpointColors(
    context: Context,
    sourceCoverKey: String?,
    sourceArtwork: AndroidBitmap?,
    mode: RawFlowMode,
    isSystemDark: Boolean,
    fallbackColors: List<Color>,
    backgroundStyle: RawBackgroundStyle,
): List<Color> {
    if (
        sourceCoverKey.isNullOrBlank() ||
        mode == RawFlowMode.OFF ||
        (backgroundStyle != RawBackgroundStyle.STATIC && mode == RawFlowMode.UNIVERSAL)
    ) {
        return fallbackColors
    }
    val cacheKey = flowPaletteCacheKey(mode, isSystemDark, sourceCoverKey, backgroundStyle)
    flowPaletteCache.get(cacheKey)?.let { return it }

    val bitmap = sourceArtwork?.takeUnless { it.isRecycled }
        ?: CoilArtworkRuntime.executeBitmap(
            context = context,
            key = sourceCoverKey,
            width = FLOW_EXTRACT_SIZE,
            height = FLOW_EXTRACT_SIZE,
            surface = ArtworkSurface.Playback,
        )
    val completed = bitmap?.takeUnless { it.isRecycled }?.let { artwork ->
        withContext(Dispatchers.Default) {
            extractRawBackgroundColors(
                bitmap = artwork,
                backgroundStyle = backgroundStyle,
                mode = mode,
                isSystemDark = isSystemDark,
            ).takeIf { it.isNotEmpty() }?.let { extracted ->
                finalizeExtractedBackgroundColors(extracted, fallbackColors, backgroundStyle)
            }
        }
    }
    if (completed != null) {
        flowPaletteCache.put(cacheKey, completed)
        return completed
    }
    // Do not cache fallback as the artwork's palette. A prefetched neighbour may still be a
    // synthetic holder on the first frame and upgrade to the exact bitmap later.
    return fallbackColors
}

internal class RawFlowAaLiveSlots {
    var currentKey: String? = null
        private set
    var currentEndpoint: List<Color> = emptyList()
        private set
    var secondaryKey: String? = null
        private set
    var secondaryEndpoint: List<Color> = emptyList()
        private set
    var secondaryEndpointReady: Boolean = false
        private set
    var secondaryReadyAtProgress: Float = 0f
        private set
    private var currentEndpointLocked: Boolean = false
    var revision by mutableIntStateOf(0)
        private set

    fun syncCurrent(key: String?, fallback: List<Color>) {
        if (currentKey == key) {
            // A same-identity recomposition is not a new AA prepare. In particular, after commit the
            // promoted secondary endpoint must remain byte-for-byte the visible endpoint that reached
            // ratio=1. A newer cached palette may be kept for a future prepare, but it must not replace
            // the live current slot and make the whole FLOW scene repaint after the transition ended.
            return
        }
        if (key != null && key == secondaryKey) {
            // Reference a1.m3064()/MilkRenderer.native_commit_aa() swaps/promotes prepared AA slots;
            // commit does not rebuild the just-visible slot. Lock the promoted endpoint until this
            // identity leaves the current slot so post-commit artwork/palette upgrades stay invisible.
            currentKey = secondaryKey
            currentEndpoint = secondaryEndpoint.ifEmpty { fallback }
            currentEndpointLocked = true
            secondaryKey = null
            secondaryEndpoint = emptyList()
            secondaryEndpointReady = false
            secondaryReadyAtProgress = 0f
            return
        }
        currentKey = key
        currentEndpoint = fallback
        currentEndpointLocked = false
        secondaryKey = null
        secondaryEndpoint = emptyList()
        secondaryEndpointReady = false
        secondaryReadyAtProgress = 0f
    }

    fun prepareSecondary(key: String?, preparedEndpoint: List<Color>?, fallback: List<Color>) {
        if (key.isNullOrBlank() || key == currentKey) {
            if (secondaryKey != null || secondaryEndpoint.isNotEmpty()) {
                secondaryKey = null
                secondaryEndpoint = emptyList()
                secondaryEndpointReady = false
                secondaryReadyAtProgress = 0f
            }
            return
        }
        if (secondaryKey == key) return
        secondaryKey = key
        secondaryEndpoint = preparedEndpoint?.takeIf { it.isNotEmpty() }
            ?: currentEndpoint.ifEmpty { fallback }
        secondaryEndpointReady = !preparedEndpoint.isNullOrEmpty()
        secondaryReadyAtProgress = 0f
    }

    fun updateCurrentIfIdle(key: String?, endpoint: List<Color>) {
        if (key != currentKey ||
            secondaryKey != null ||
            currentEndpointLocked ||
            endpoint.isEmpty() ||
            endpoint == currentEndpoint
        ) return
        // A cold/current slot may resolve from fallback to a real palette while it is not the result
        // of an AA commit. The promoted-slot lock above is deliberately narrower: it prevents the
        // post-commit repaint without blocking legitimate cold-start/configuration resolution.
        currentEndpoint = endpoint
        revision += 1
    }

    /**
     * Reference keeps feeding the live AA ratio while the secondary Milk AA state is being resolved.
     * A target that becomes available after motion started must therefore join the same transition,
     * not be deferred until commit.  Remember the ratio at which it became real so the visible
     * palette is continuous on that frame, then consume the remaining ratio budget to reach the
     * exact secondary endpoint at ratio=1.
     */
    fun updateSecondaryDuringMotion(key: String?, endpoint: List<Color>, progress: Float) {
        if (key != secondaryKey || endpoint.isEmpty()) return
        val clampedProgress = progress.coerceIn(0f, 1f)
        if (!secondaryEndpointReady) {
            secondaryEndpoint = endpoint
            secondaryEndpointReady = true
            secondaryReadyAtProgress = clampedProgress.coerceAtMost(0.999f)
            revision += 1
            return
        }
        if (endpoint != secondaryEndpoint && clampedProgress <= 0.001f) {
            // Before motion begins the slot may still be refreshed from an exact cached result. Once
            // the slot is visibly participating, however, keep that real endpoint stable. Reference
            // changes the native AA ratio against prepared slot identities; it does not restart the
            // endpoint every time the same artwork wrapper upgrades. A later same-key palette result
            // is cached for future use instead of restarting the visible color handoff mid-flight.
            secondaryEndpoint = endpoint
            secondaryReadyAtProgress = 0f
            revision += 1
        }
    }

    fun visibleMixProgress(rawProgress: Float): Float {
        if (secondaryKey == null || !secondaryEndpointReady) return 0f
        return ReferenceFlowAaMixProgress(rawProgress, secondaryReadyAtProgress)
    }
}

/**
 * Ratio remapping used only when a real secondary palette arrives after AA motion has already begun.
 * If the slot was prepared before motion, readyAt=0 and this is exactly the native AA ratio.  A late
 * slot starts at zero contribution on its admission frame and still reaches the exact target at 1.
 */
internal fun ReferenceFlowAaMixProgress(rawProgress: Float, readyAtProgress: Float): Float {
    val progress = rawProgress.coerceIn(0f, 1f)
    val readyAt = readyAtProgress.coerceIn(0f, 0.999f)
    if (progress <= readyAt) return 0f
    return ((progress - readyAt) / (1f - readyAt)).coerceIn(0f, 1f)
}


internal fun expandReferenceFlowDrawSlots(colors: List<Color>): List<Color> {
    if (colors.isEmpty()) return emptyList()
    val source = colors.take(FLOW_MAX_COLOR_COUNT)
    if (source.size == FLOW_MAX_COLOR_COUNT) return source
    val last = source.last()
    return List(FLOW_MAX_COLOR_COUNT) { index -> source.getOrElse(index) { last } }
}

private fun lerpFlowColor(start: Color, end: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * t,
        green = start.green + (end.green - start.green) * t,
        blue = start.blue + (end.blue - start.blue) * t,
        alpha = start.alpha + (end.alpha - start.alpha) * t,
    )
}

/**
 * Single-renderer playback handoff for FLOW backgrounds.
 *
 * Before Phase 9A14 a track switch kept two full-screen RawFlowBackground canvases alive and
 * alpha-blended them. Each canvas drew up to five large radial gradients, so the exact moment the
 * AA cards moved could require ten full-screen gradient passes. Reference's player transition keeps
 * one existing property scene; mirror that budget here by keeping one blob geometry/time owner and
 * interpolating only its palette from the outgoing artwork to the incoming artwork.
 *
 * [progressProvider] is intentionally read from Canvas draw phase. AA ratio updates therefore
 * invalidate only this draw node and the card RenderNodes, not composition/layout.
 */
@Composable
fun RawFlowTransitionBackground(
    mode: RawFlowMode,
    currentCoverKey: String?,
    targetCoverKey: String?,
    currentArtwork: AndroidBitmap?,
    targetArtwork: AndroidBitmap?,
    progressProvider: () -> Float,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    motionEnabled: Boolean = true,
    frameIntervalMs: Long = FLOW_FRAME_INTERVAL_MS,
    prefetchPreviousCoverKey: String? = null,
    prefetchPreviousArtwork: AndroidBitmap? = null,
    prefetchNextCoverKey: String? = null,
    prefetchNextArtwork: AndroidBitmap? = null,
) {
    val context = LocalContext.current.applicationContext
    RawFlowTuningState.ensureInitialized(context)
    val backgroundStyle = RawFlowTuningState.style
    val runtimeRevision = RawFlowRuntimeState.revision
    val flowMode = RawFlowRuntimeState.mode ?: mode
    val isSystemDark = rememberRawFlowIsDarkTheme()
    val scheme = MiuixTheme.colorScheme

    if (!active || flowMode == RawFlowMode.OFF || backgroundStyle == RawBackgroundStyle.SIMPLE) {
        Box(modifier = modifier.fillMaxSize().background(scheme.background))
        return
    }
    if (backgroundStyle != RawBackgroundStyle.FLOW) {
        RawFlowBackground(
            mode = flowMode,
            sourceCoverKey = targetCoverKey ?: currentCoverKey,
            fallbackSourceCoverKey = currentCoverKey,
            sourceArtwork = targetArtwork ?: currentArtwork,
            modifier = modifier,
            active = active,
            motionEnabled = motionEnabled,
            frameIntervalMs = frameIntervalMs,
            surface = RawBackgroundSurface.PLAYER,
        )
        return
    }

    val tuningRevision = RawFlowTuningState.revision
    val motionSpeed = RawFlowTuningState.speed
    val saturationScale = RawFlowTuningState.saturation
    val brightnessScale = RawFlowTuningState.brightness
    val fallbackColors = remember(flowMode, isSystemDark) { defaultFlowColors(flowMode, isSystemDark) }

    /*
     * Reference's player background is a native Milk scene with two prepared AA states. Java changes
     * native_set_aa_ratio() while moving and native_commit_aa() swaps the slots. The endpoint itself
     * is never a second animation clock. Mirror that ownership here: current/secondary are exact AA
     * slots and the visible colors are mixed by the same live AA ratio. If the secondary palette is
     * resolved after motion begins, admit it into that live ratio timeline rather than deferring it
     * until commit; commit remains only an ownership swap.
     */
    val scene = remember(flowMode, isSystemDark) { RawFlowAaLiveSlots() }
    val currentMemoryEndpoint = remember(flowMode, isSystemDark, currentCoverKey, runtimeRevision) {
        resolveFlowColorsFromMemory(
            context = context,
            sourceCoverKey = currentCoverKey,
            mode = flowMode,
            isSystemDark = isSystemDark,
            fallbackColors = fallbackColors,
            backgroundStyle = RawBackgroundStyle.FLOW,
        ) ?: fallbackColors
    }
    scene.syncCurrent(currentCoverKey, currentMemoryEndpoint)

    val hasSecondaryIdentity = !targetCoverKey.isNullOrBlank() && targetCoverKey != currentCoverKey
    val secondaryMemoryEndpoint = remember(
        flowMode,
        isSystemDark,
        targetCoverKey,
        currentCoverKey,
        runtimeRevision,
        scene.revision,
    ) {
        if (!hasSecondaryIdentity) {
            null
        } else {
            resolveFlowColorsFromMemory(
                context = context,
                sourceCoverKey = targetCoverKey,
                mode = flowMode,
                isSystemDark = isSystemDark,
                fallbackColors = scene.currentEndpoint.ifEmpty { fallbackColors },
                backgroundStyle = RawBackgroundStyle.FLOW,
            )
        }
    }
    scene.prepareSecondary(
        key = targetCoverKey.takeIf { hasSecondaryIdentity },
        preparedEndpoint = secondaryMemoryEndpoint,
        fallback = fallbackColors,
    )
    @Suppress("UNUSED_VARIABLE") val sceneRevision = scene.revision

    // Keep Reference-like neighbour AA states warm while idle. Normal Next/Previous therefore admits a
    // fully prepared secondary palette synchronously instead of letting Palette/Coil completion alter
    // a visible transition halfway through.
    LaunchedEffect(
        prefetchPreviousCoverKey,
        prefetchPreviousArtwork,
        flowMode,
        isSystemDark,
        runtimeRevision,
    ) {
        val key = prefetchPreviousCoverKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        loadRawFlowEndpointColors(
            context = context,
            sourceCoverKey = key,
            sourceArtwork = prefetchPreviousArtwork,
            mode = flowMode,
            isSystemDark = isSystemDark,
            fallbackColors = scene.currentEndpoint.ifEmpty { fallbackColors },
            backgroundStyle = RawBackgroundStyle.FLOW,
        )
    }
    LaunchedEffect(
        prefetchNextCoverKey,
        prefetchNextArtwork,
        flowMode,
        isSystemDark,
        runtimeRevision,
    ) {
        val key = prefetchNextCoverKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        loadRawFlowEndpointColors(
            context = context,
            sourceCoverKey = key,
            sourceArtwork = prefetchNextArtwork,
            mode = flowMode,
            isSystemDark = isSystemDark,
            fallbackColors = scene.currentEndpoint.ifEmpty { fallbackColors },
            backgroundStyle = RawBackgroundStyle.FLOW,
        )
    }

    LaunchedEffect(currentCoverKey, currentArtwork, flowMode, isSystemDark, runtimeRevision) {
        val requestedKey = currentCoverKey
        val loaded = loadRawFlowEndpointColors(
            context = context,
            sourceCoverKey = requestedKey,
            sourceArtwork = currentArtwork,
            mode = flowMode,
            isSystemDark = isSystemDark,
            fallbackColors = scene.currentEndpoint.ifEmpty { currentMemoryEndpoint },
            backgroundStyle = RawBackgroundStyle.FLOW,
        )
        scene.updateCurrentIfIdle(requestedKey, loaded)
    }
    LaunchedEffect(targetCoverKey, targetArtwork, flowMode, isSystemDark, runtimeRevision) {
        val requestedKey = targetCoverKey?.takeIf { hasSecondaryIdentity } ?: return@LaunchedEffect
        val loaded = loadRawFlowEndpointColors(
            context = context,
            sourceCoverKey = requestedKey,
            sourceArtwork = targetArtwork,
            mode = flowMode,
            isSystemDark = isSystemDark,
            fallbackColors = scene.secondaryEndpoint.ifEmpty { scene.currentEndpoint.ifEmpty { fallbackColors } },
            backgroundStyle = RawBackgroundStyle.FLOW,
        )
        // Reference does not freeze the secondary AA identity merely because ratio has left zero:
        // a resolved secondary state joins the still-running native_set_aa_ratio timeline.  Only
        // accept a real extracted/cache endpoint here (loadRawFlowEndpointColors intentionally does
        // not cache synthetic fallback), then preserve continuity from the current visible ratio.
        val resolved = resolveFlowColorsFromMemory(
            context = context,
            sourceCoverKey = requestedKey,
            mode = flowMode,
            isSystemDark = isSystemDark,
            fallbackColors = scene.currentEndpoint.ifEmpty { fallbackColors },
            backgroundStyle = RawBackgroundStyle.FLOW,
        )
        if (resolved != null) {
            scene.updateSecondaryDuringMotion(
                requestedKey,
                resolved.ifEmpty { loaded },
                progressProvider().coerceIn(0f, 1f),
            )
        }
    }

    val currentResolved = remember(
        scene.revision, currentCoverKey, targetCoverKey, saturationScale, brightnessScale, tuningRevision
    ) {
        expandReferenceFlowDrawSlots(
            scene.currentEndpoint.ifEmpty { fallbackColors }
                .take(FLOW_MAX_COLOR_COUNT)
                .map { tuneFlowColor(it, saturationScale, brightnessScale) }
        )
    }
    val secondaryResolved = remember(
        scene.revision, currentCoverKey, targetCoverKey, saturationScale, brightnessScale, tuningRevision
    ) {
        expandReferenceFlowDrawSlots(
            scene.secondaryEndpoint.ifEmpty { scene.currentEndpoint.ifEmpty { fallbackColors } }
                .take(FLOW_MAX_COLOR_COUNT)
                .map { tuneFlowColor(it, saturationScale, brightnessScale) }
        )
    }
    val currentBase = remember(currentResolved, flowMode, isSystemDark) {
        baseFlowColor(flowMode, isSystemDark, currentResolved.firstOrNull())
    }
    val secondaryBase = remember(secondaryResolved, flowMode, isSystemDark) {
        baseFlowColor(flowMode, isSystemDark, secondaryResolved.firstOrNull())
    }
    val timeSecondsState = rememberRawFlowTimeState(
        enabled = motionEnabled,
        modeName = flowMode.prefValue,
        frameIntervalMs = frameIntervalMs.coerceAtLeast(FLOW_FRAME_INTERVAL_MS),
    )
    val seeds = remember { flowBlobSeeds() }

    Canvas(modifier = modifier.fillMaxSize().clipToBounds()) {
        val rawProgress = if (scene.secondaryKey == null) 0f else progressProvider().coerceIn(0f, 1f)
        val progress = scene.visibleMixProgress(rawProgress)
        val base = lerpFlowColor(currentBase, secondaryBase, progress)
        drawRect(base)

        // Keep the FLOW renderer topology stable across AA ownership changes. Palette extraction is
        // adaptive (one artwork may yield 5 useful colors while the next yields only 1 or 2), but
        // Reference's Milk renderer does not rebuild its property scene when native_commit_aa swaps
        // AA slots. Repeating the last endpoint color into the fixed five draw slots makes ratio=1
        // and the first post-commit frame pixel-continuous: no blobs disappear and countScale never
        // changes merely because the incoming artwork exposed fewer distinct palette colors.
        val colorCount = FLOW_MAX_COLOR_COUNT
        val countScale = 0.92f
        val maxDimension = max(size.width, size.height)
        val animatedTime = timeSecondsState.value * motionSpeed
        repeat(colorCount) { index ->
            val currentColor = currentResolved.getOrElse(index) { currentResolved.last() }
            val secondaryColor = secondaryResolved.getOrElse(index) { secondaryResolved.last() }
            val color = lerpFlowColor(currentColor, secondaryColor, progress)
            val seed = seeds[index % seeds.size]
            val phase = seed.phase + index * 0.83f
            val x = size.width * seed.baseX +
                size.width * seed.amplitudeX * sin(animatedTime * seed.speedX + phase)
            val y = size.height * seed.baseY +
                size.height * seed.amplitudeY * cos(animatedTime * seed.speedY + phase * 1.21f)
            val radiusPulse = 1f + 0.11f * sin(animatedTime * seed.radiusSpeed + phase)
            val radius = maxDimension * seed.radius * countScale * radiusPulse
            val coreAlpha = if (isSystemDark) 0.88f else 0.82f
            drawCircle(
                brush = Brush.radialGradient(
                    0f to color.copy(alpha = coreAlpha),
                    0.58f to color.copy(alpha = coreAlpha * 0.42f),
                    1f to color.copy(alpha = 0f),
                    center = Offset(x, y),
                    radius = radius,
                ),
                radius = radius,
                center = Offset(x, y),
            )
        }
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
    paletteAnimationEnabled: Boolean = true,
    frameIntervalMs: Long = FLOW_FRAME_INTERVAL_MS,
    surface: RawBackgroundSurface = RawBackgroundSurface.SCENE
) {
    val context = LocalContext.current.applicationContext
    RawFlowTuningState.ensureInitialized(context)
    val backgroundStyle = RawFlowTuningState.style
    val runtimeRevision = RawFlowRuntimeState.revision
    val flowMode = RawFlowRuntimeState.mode ?: mode
    val coilArtwork by produceState<AndroidBitmap?>(
        initialValue = sourceArtwork?.takeUnless { it.isRecycled },
        sourceCoverKey,
        sourceArtwork,
        active,
        flowMode,
        backgroundStyle,
    ) {
        val providedArtwork = sourceArtwork?.takeUnless { it.isRecycled }
        value = providedArtwork
        if (
            providedArtwork != null ||
            !active ||
            flowMode == RawFlowMode.OFF ||
            backgroundStyle == RawBackgroundStyle.SIMPLE
        ) {
            // The playback AA holder already owns the exact bitmap. Do not launch a second 96px
            // Coil request for the same source while a track-switch frame is being composed.
            return@produceState
        }
        value = sourceCoverKey?.takeIf { it.isNotBlank() }?.let { key ->
            CoilArtworkRuntime.executeBitmap(
                context = context,
                key = key,
                width = FLOW_EXTRACT_SIZE,
                height = FLOW_EXTRACT_SIZE,
                surface = ArtworkSurface.Playback
            )
        }
    }
    val tuningRevision = RawFlowTuningState.revision
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

    val fallbackColors = remember(flowMode, isSystemDark, backgroundStyle) {
        if (backgroundStyle == RawBackgroundStyle.STATIC) {
            darkAlbumGradient(STATIC_ALBUM_FALLBACK_ACCENT)
        } else {
            defaultFlowColors(flowMode, isSystemDark)
        }
    }
    val paletteCacheKey = remember(flowMode, isSystemDark, sourceCoverKey, backgroundStyle) {
        flowPaletteCacheKey(flowMode, isSystemDark, sourceCoverKey, backgroundStyle)
    }
    val inheritedColors = remember(
        flowMode,
        isSystemDark,
        fallbackSourceCoverKey,
        fallbackColors,
        backgroundStyle,
    ) {
        resolveFlowColorsFromMemory(
            context = context,
            sourceCoverKey = fallbackSourceCoverKey,
            mode = flowMode,
            isSystemDark = isSystemDark,
            fallbackColors = fallbackColors,
            backgroundStyle = backgroundStyle,
        ) ?: fallbackColors
    }
    val initialColors = remember(
        flowMode,
        isSystemDark,
        sourceCoverKey,
        inheritedColors,
        fallbackColors,
        backgroundStyle,
    ) {
        if (
            sourceCoverKey.isNullOrBlank() ||
            (backgroundStyle != RawBackgroundStyle.STATIC && flowMode == RawFlowMode.UNIVERSAL)
        ) {
            fallbackColors
        } else {
            // Never run Palette extraction synchronously from composition. Reuse an already cached
            // target palette when available; otherwise start from the outgoing/inherited palette
            // while the LaunchedEffect below resolves the exact target off the main thread.
            resolveFlowColorsFromMemory(
                context = context,
                sourceCoverKey = sourceCoverKey,
                mode = flowMode,
                isSystemDark = isSystemDark,
                fallbackColors = fallbackColors,
                backgroundStyle = backgroundStyle,
            ) ?: inheritedColors
        }
    }
    var targetColors by remember(flowMode, isSystemDark, sourceCoverKey, backgroundStyle) {
        mutableStateOf(initialColors)
    }

    LaunchedEffect(
        flowMode,
        runtimeRevision,
        sourceCoverKey,
        coilArtwork,
        fallbackColors,
        isSystemDark,
        backgroundStyle,
    ) {
        val paletteStartMs = SystemClock.elapsedRealtime()
        if (
            sourceCoverKey.isNullOrBlank() ||
            (backgroundStyle != RawBackgroundStyle.STATIC && flowMode == RawFlowMode.UNIVERSAL)
        ) {
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

        coilArtwork
            ?.takeUnless { it.isRecycled }
            ?.let { artwork ->
                withContext(Dispatchers.Default) {
                    extractRawBackgroundColors(
                        bitmap = artwork,
                        backgroundStyle = backgroundStyle,
                        mode = flowMode,
                        isSystemDark = isSystemDark,
                    ).takeIf { it.isNotEmpty() }?.let { extracted ->
                        finalizeExtractedBackgroundColors(
                            extracted,
                            fallbackColors,
                            backgroundStyle,
                        )
                    }
                }
            }
            ?.let { completed ->
                flowPaletteCache.put(paletteCacheKey, completed)
                targetColors = completed
                PowerTraceLogger.flowPalette(
                    stage = "transition_artwork",
                    mode = flowMode.prefValue,
                    source = "AA-holder",
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

        var appliedAlbumPalette = false
        val bitmap = coilArtwork?.takeUnless { it.isRecycled }
            ?: CoilArtworkRuntime.executeBitmap(
                context = context,
                key = sourceCoverKey,
                width = FLOW_EXTRACT_SIZE,
                height = FLOW_EXTRACT_SIZE,
                surface = ArtworkSurface.Playback
            )
        if (bitmap != null && !bitmap.isRecycled) {
            val completed = withContext(Dispatchers.Default) {
                extractRawBackgroundColors(
                    bitmap = bitmap,
                    backgroundStyle = backgroundStyle,
                    mode = flowMode,
                    isSystemDark = isSystemDark,
                ).takeIf { it.isNotEmpty() }?.let { extracted ->
                    finalizeExtractedBackgroundColors(
                        extracted,
                        fallbackColors,
                        backgroundStyle,
                    )
                }
            }
            if (completed != null) {
                flowPaletteCache.put(paletteCacheKey, completed)
                targetColors = completed
                appliedAlbumPalette = true
                PowerTraceLogger.flowPalette(
                    stage = "memory_hit",
                    mode = flowMode.prefValue,
                    source = "Coil",
                    colorCount = completed.size,
                    elapsedMs = SystemClock.elapsedRealtime() - paletteStartMs,
                    coverKey = sourceCoverKey
                )
            }
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
        tuningRevision,
        backgroundStyle,
    ) {
        val raw = targetColors.ifEmpty { fallbackColors }.take(FLOW_MAX_COLOR_COUNT)
        if (backgroundStyle == RawBackgroundStyle.STATIC) {
            // Static endpoints already include their HSV saturation/value transform.
            // Applying RawFlow's second saturation/brightness normalization here is what made
            // the non-bottom background drift away from the MiniPlayer for the same cover.
            raw
        } else {
            raw.map { tuneFlowColor(it, saturationScale, brightnessScale) }
        }
    }
    // Keep animated color State objects without reading .value in composition. During a transition
    // two full-screen backgrounds may be alive; reading six animated values per layer here used to
    // recompose both trees every frame. FLOW reads them from Canvas draw phase below instead.
    val animatedColorSlots = List(FLOW_MAX_COLOR_COUNT) { index ->
        val slotTarget = resolvedColors.getOrElse(index) { resolvedColors.last() }
        animateColorAsState(
            targetValue = slotTarget,
            animationSpec = if (paletteAnimationEnabled) {
                tween(durationMillis = 360, easing = FastOutSlowInEasing)
            } else {
                snap()
            },
            label = "raw-flow-palette-$index"
        )
    }

    val targetBaseColor = remember(flowMode, isSystemDark, resolvedColors) {
        baseFlowColor(flowMode, isSystemDark, resolvedColors.firstOrNull())
    }
    val baseColorState = animateColorAsState(
        targetValue = targetBaseColor,
        animationSpec = if (paletteAnimationEnabled) {
            tween(durationMillis = 360, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "raw-flow-base"
    )
    // STATIC does not have the 60/90/120 Hz blob clock, so reading its small color transition here
    // is acceptable and keeps the existing native/static fallback path unchanged.
    val staticColors = if (backgroundStyle == RawBackgroundStyle.STATIC) {
        animatedColorSlots.take(resolvedColors.size).map { it.value }
    } else {
        emptyList()
    }
    val staticBaseColor = if (backgroundStyle == RawBackgroundStyle.STATIC) {
        baseColorState.value
    } else {
        Color.Transparent
    }
    if (backgroundStyle == RawBackgroundStyle.STATIC) {
        val staticArtwork = remember(
            sourceCoverKey,
            fallbackSourceCoverKey,
            coilArtwork,
            surface
        ) {
            if (surface != RawBackgroundSurface.PLAYER) {
                null
            } else {
                coilArtwork?.takeUnless { it.isRecycled }
                    ?: listOfNotNull(sourceCoverKey, fallbackSourceCoverKey)
                        .firstNotNullOfOrNull { key ->
                            CoilArtworkRuntime.peekBitmap(
                                context = context,
                                key = key,
                                width = FLOW_EXTRACT_SIZE,
                                height = FLOW_EXTRACT_SIZE,
                                surface = ArtworkSurface.Playback
                            )
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
                    colors = nativeColors,
                    // Static endpoints already contain the brightness/saturation policy. The native
                    // player renderer contributes only texture/blur/detail; do not recolor twice.
                    saturation = 1f,
                    brightness = 1f,
                    gradient = staticGradient,
                    blur = staticBlur,
                    detail = staticDetail
                )
            } else {
                NativeStaticBackground.create(
                    colors = nativeColors,
                    saturation = if (backgroundStyle == RawBackgroundStyle.STATIC) 1f else saturationScale,
                    brightness = if (backgroundStyle == RawBackgroundStyle.STATIC) 1f else brightnessScale,
                )
            }
        }
        if (staticBitmap != null) {
            Image(
                bitmap = staticBitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = modifier.fillMaxSize().background(staticBaseColor)
            )
            return
        }
    }
    val canMove = true
    // Keep the flow clock as a State object and read it from Canvas draw phase. Returning the
    // Float value from a composable made every flow tick recompose the complete RawFlowBackground;
    // during a song switch two prepared backgrounds doubled that work.
    val timeSecondsState = rememberRawFlowTimeState(
        enabled = motionEnabled && canMove && backgroundStyle == RawBackgroundStyle.FLOW,
        modeName = flowMode.prefValue,
        frameIntervalMs = frameIntervalMs.coerceAtLeast(FLOW_FRAME_INTERVAL_MS)
    )
    val blobSeeds = remember { flowBlobSeeds() }
    Canvas(modifier = modifier.fillMaxSize().clipToBounds()) {
        val drawBaseColor = if (backgroundStyle == RawBackgroundStyle.STATIC) {
            staticBaseColor
        } else {
            baseColorState.value
        }
        drawRect(drawBaseColor)
        if (backgroundStyle == RawBackgroundStyle.STATIC) {
            val matrixColors = staticColors.ifEmpty { listOf(drawBaseColor) }
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
        val colorCount = resolvedColors.size.coerceIn(1, FLOW_MAX_COLOR_COUNT)
        val countScale = when (colorCount) {
            1 -> 1.18f
            2 -> 1.05f
            else -> 0.92f
        }
        repeat(colorCount) { index ->
            val color = animatedColorSlots[index].value
            val seed = blobSeeds[index % blobSeeds.size]
            val phase = seed.phase + index * 0.83f
            val motion = if (canMove) 1f else 0f
            val animatedTime = timeSecondsState.value * motionSpeed
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
private fun rememberRawFlowTimeState(
    enabled: Boolean,
    modeName: String,
    frameIntervalMs: Long
): androidx.compose.runtime.State<Float> {
    fun globalPhaseSeconds(nowNs: Long = System.nanoTime()): Float =
        ((nowNs - FLOW_GLOBAL_EPOCH_NS).coerceAtLeast(0L) / 1_000_000_000f)

    val timeSeconds = remember { mutableFloatStateOf(globalPhaseSeconds()) }
    LaunchedEffect(enabled, modeName, frameIntervalMs) {
        if (!enabled) {
            PowerTraceLogger.flowFrame(
                mode = modeName,
                enabled = false,
                frameIntervalMs = frameIntervalMs
            )
            return@LaunchedEffect
        }
        var firstFrameNs = 0L
        var lastUpdateNs = 0L
        var lastTraceNs = 0L
        val startVisualSeconds = timeSeconds.floatValue
        val requestedIntervalNs = frameIntervalMs.coerceAtLeast(1L) * 1_000_000L
        while (isActive) {
            val frameNs = withFrameNanos { it }
            if (firstFrameNs == 0L) firstFrameNs = frameNs
            if (
                frameIntervalMs <= FLOW_FRAME_INTERVAL_MS ||
                lastUpdateNs == 0L ||
                frameNs - lastUpdateNs >= requestedIntervalNs
            ) {
                timeSeconds.floatValue = startVisualSeconds +
                    ((frameNs - firstFrameNs).coerceAtLeast(0L) / 1_000_000_000f)
                lastUpdateNs = frameNs
            }
            if (lastTraceNs == 0L || frameNs - lastTraceNs >= 1_000_000_000L) {
                PowerTraceLogger.flowFrame(
                    mode = modeName,
                    enabled = true,
                    frameIntervalMs = frameIntervalMs
                )
                lastTraceNs = frameNs
            }
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

private fun extractRawBackgroundColors(
    bitmap: android.graphics.Bitmap,
    backgroundStyle: RawBackgroundStyle,
    mode: RawFlowMode,
    isSystemDark: Boolean,
): List<Color> {
    if (backgroundStyle == RawBackgroundStyle.STATIC) {
        val accent = staticAlbumAccent(bitmap) ?: return emptyList()
        return darkAlbumGradient(accent)
    }
    return RawFlowPaletteExtractor.extract(bitmap, mode, isSystemDark)
}

private fun finalizeExtractedBackgroundColors(
    extracted: List<Color>,
    fallbackColors: List<Color>,
    backgroundStyle: RawBackgroundStyle,
): List<Color> = if (backgroundStyle == RawBackgroundStyle.STATIC) {
    extracted.ifEmpty { fallbackColors }.take(2)
} else {
    completeExtractedFlowColors(extracted, fallbackColors)
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
