package com.rawsmusic.core.ui.widget.flow

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkPerfStage
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkPerfTrace
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.math.max

private const val STATIC_AA_CACHE_KB = 28 * 1024

private val staticAaCache = object : LruCache<String, Bitmap>(STATIC_AA_CACHE_KB) {
    override fun sizeOf(key: String, value: Bitmap): Int =
        (value.allocationByteCount / 1024).coerceAtLeast(1)
}

/**
 * Reference baseline implementation keeps artwork preparation on its dedicated "milk loader" HandlerThread while the
 * renderer consumes already-prepared states.  Keep Raw's native preparation off Dispatchers.Default
 * as well so a 30-60ms artwork build cannot occupy the shared coroutine CPU pool during the 550ms page
 * motion.  The Mutex preserves one loader queue even when source acquisition/effects overlap.
 */
private val staticAaLoaderExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(
        {
            // Native getPixels/blur/detail preparation is intentionally lower priority than the
            // 120Hz UI/RenderThread. The live slot keeps the previous endpoint visible until this
            // worker finishes, so latency here is preferable to a 30-60ms animation-frame stall.
            runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) }
            runnable.run()
        },
        "RawStaticArtworkLoader",
    )
}
private val staticAaLoaderDispatcher = staticAaLoaderExecutor.asCoroutineDispatcher()
private val staticAaLoaderGate = Mutex()

internal fun clearRawStaticArtworkCache() {
    staticAaCache.evictAll()
}

private data class StaticAaConfig(
    val saturation: Float,
    val brightness: Float,
    val gradient: Float,
    val gradientColor: Int,
    val blur: Float,
    val detail: Float,
) {
    fun cacheKey(identity: String, sourceClass: Int): String = buildString {
        append(identity)
        append("|src=").append(sourceClass)
        append('|').append(saturation.toBits())
        append('|').append(brightness.toBits())
        append('|').append(gradient.toBits())
        append('|').append(gradientColor and 0x00ffffff)
        append('|').append(blur.toBits())
        append('|').append(detail.toBits())
    }
}

/**
 * Reference baseline implementation StaticArtworkLoader uses 1 << aa_blur_scale as the artwork source edge limit.
 * Scale 10 is the sentinel for no artwork-side downsample.  Raw's static loader consumes the existing
 * playback/provider wrapper and lets NativeStaticBackground apply the same detail limit.
 */
internal fun referenceAaDetailLimit(detail: Float): Int? {
    val step = detail.toInt().coerceIn(0, 10)
    return if (step >= 10) null else 1 shl step
}

private fun staticAaIdentity(key: String?, artwork: Bitmap?): String =
    key?.takeIf { it.isNotBlank() }
        ?: artwork?.let { "bitmap:${System.identityHashCode(it)}:${it.width}x${it.height}" }
        ?: "none"

private fun staticAaSourceClass(artwork: Bitmap?, detail: Float): Int {
    val bitmap = artwork?.takeUnless { it.isRecycled } ?: return 0
    val longEdge = max(bitmap.width, bitmap.height)
    val finiteLimit = referenceAaDetailLimit(detail)
    if (finiteLimit != null) {
        return if (longEdge >= finiteLimit.coerceAtLeast(1)) 1 else 0
    }
    // Detail=10 is Reference's no-artwork-downsample sentinel.  A low wrapper may still be admitted so
    // the background never blanks; a later high wrapper is the only source upgrade that is allowed
    // to restart native preparation for this explicit maximum-detail mode.
    return if (longEdge >= 1024) 2 else 1
}

private fun peekStaticAaEndpoint(
    key: String?,
    artwork: Bitmap?,
    config: StaticAaConfig,
): Bitmap? {
    val sourceClass = staticAaSourceClass(artwork, config.detail)
    return staticAaCache.get(config.cacheKey(staticAaIdentity(key, artwork), sourceClass))
}

private suspend fun prepareStaticAaEndpoint(
    key: String?,
    artwork: Bitmap?,
    config: StaticAaConfig,
    traceLane: String,
): Bitmap? {
    val identity = staticAaIdentity(key, artwork)
    val sourceClass = staticAaSourceClass(artwork, config.detail)
    val cacheKey = config.cacheKey(identity, sourceClass)
    staticAaCache.get(cacheKey)?.let { cached ->
        if (!cached.isRecycled) {
            PlaybackArtworkPerfTrace.mark(
                "background_static_cache_hit",
                "lane=$traceLane key=${PlaybackArtworkPerfTrace.keyTag(key.orEmpty())} bitmap=${cached.width}x${cached.height}",
            )
            return cached
        }
        staticAaCache.remove(cacheKey)
    }

    val prepareStartNs = if (PlaybackArtworkPerfTrace.isActive()) System.nanoTime() else 0L
    return try {
        staticAaLoaderGate.withLock {
        staticAaCache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return@withLock cached
            staticAaCache.remove(cacheKey)
        }

        // The player/provider owns artwork loading.  StaticArtworkLoader consumes an already-resolved artwork
        // wrapper; it does not start a second independent full-art pipeline every time the same key
        // upgrades from low to high.  Using only the supplied wrapper also makes cancellation of the
        // Compose effect cheap: there is no hidden Coil decode that survives a source-tier rebind.
        val source = artwork?.takeUnless { it.isRecycled } ?: return@withLock null

        PlaybackArtworkPerfTrace.mark(
            "background_static_source",
            "lane=$traceLane key=${PlaybackArtworkPerfTrace.keyTag(key.orEmpty())} supplied=true " +
                "source=${source.width}x${source.height} sourceClass=${staticAaSourceClass(source, config.detail)}",
        )
        val nativeStartNs = if (PlaybackArtworkPerfTrace.isActive()) System.nanoTime() else 0L
        val result = try {
            withContext(staticAaLoaderDispatcher) {
                NativeStaticBackground.createPlayer(
                    artwork = source,
                    saturation = config.saturation,
                    brightness = config.brightness,
                    gradient = config.gradient,
                    gradientColor = config.gradientColor,
                    blur = config.blur,
                    detail = config.detail,
                )
            }
        } finally {
            if (nativeStartNs != 0L) {
                PlaybackArtworkPerfTrace.recordDuration(
                    PlaybackArtworkPerfStage.BACKGROUND_STATIC_NATIVE,
                    System.nanoTime() - nativeStartNs,
                )
            }
        }
        if (result != null && !result.isRecycled) {
            staticAaCache.put(cacheKey, result)
        }
        if (prepareStartNs != 0L) {
            PlaybackArtworkPerfTrace.mark(
                "background_static_ready",
                "lane=$traceLane key=${PlaybackArtworkPerfTrace.keyTag(key.orEmpty())} " +
                    "duration=${"%.2f".format((System.nanoTime() - prepareStartNs) / 1_000_000.0)}ms " +
                    "result=${result?.let { "${it.width}x${it.height}" } ?: "-"}",
            )
        }
        result
        }
    } finally {
        if (prepareStartNs != 0L) {
            PlaybackArtworkPerfTrace.recordDuration(
                PlaybackArtworkPerfStage.BACKGROUND_STATIC_PREPARE,
                System.nanoTime() - prepareStartNs,
            )
        }
    }
}

internal class RawStaticArtworkLiveSlots {
    var currentKey: String? = null
        private set
    var currentBitmap: Bitmap? = null
        private set
    var secondaryKey: String? = null
        private set
    var secondaryBitmap: Bitmap? = null
        private set
    private var secondaryReadyAtProgress: Float = 0f
    var revision by mutableIntStateOf(0)
        private set

    fun syncCurrent(key: String?, prepared: Bitmap?) {
        if (currentKey == key) return
        if (key != null && key == secondaryKey) {
            currentKey = secondaryKey
            if (secondaryBitmap != null) {
                currentBitmap = secondaryBitmap
                PlaybackArtworkPerfTrace.backgroundCurrentCommit(
                    lane = "static",
                    key = currentKey,
                    detail = "bitmap=${currentBitmap?.width}x${currentBitmap?.height}",
                )
            }
            secondaryKey = null
            secondaryBitmap = null
            secondaryReadyAtProgress = 0f
            return
        }
        currentKey = key
        currentBitmap = prepared ?: currentBitmap
        secondaryKey = null
        secondaryBitmap = null
        secondaryReadyAtProgress = 0f
    }

    fun prepareSecondary(key: String?, prepared: Bitmap?) {
        if (key.isNullOrBlank() || key == currentKey) {
            if (secondaryKey != null || secondaryBitmap != null) {
                secondaryKey = null
                secondaryBitmap = null
                secondaryReadyAtProgress = 0f
            }
            return
        }
        if (secondaryKey == key) return
        secondaryKey = key
        secondaryBitmap = prepared
        secondaryReadyAtProgress = 0f
    }

    fun updateCurrentIfIdle(key: String?, bitmap: Bitmap?) {
        if (key != currentKey || secondaryKey != null || bitmap == null) return
        if (bitmap === currentBitmap) return
        // Effect ownership is source-class keyed. Finite-detail low->high wrapper churn cannot
        // reach this branch, while detail=10 intentionally permits one sharper current endpoint
        // after commit. A permanent current-slot lock defeated that policy and could leave STATIC
        // on the previous/low endpoint after a track switch.
        currentBitmap = bitmap
        revision += 1
    }

    fun updateSecondaryDuringMotion(key: String?, bitmap: Bitmap?, progress: Float) {
        if (key != secondaryKey || bitmap == null) return
        val clampedProgress = progress.coerceIn(0f, 1f)
        if (secondaryBitmap == null) {
            // The target artwork loader is asynchronous while the page/track ratio is already
            // moving. Rejecting every result after progress > 0.001 meant that a perfectly healthy
            // target prepared 5-15 ms after the command was silently discarded, so STATIC kept the
            // outgoing artwork for the whole 550 ms and appeared to snap only at commit.
            //
            // Keep one native/static renderer and admit the real secondary endpoint at the ratio at
            // which it becomes ready. visibleMixProgress() remaps that point to alpha=0 and the
            // remaining interval to alpha=1, so there is no discontinuity when the bitmap joins a
            // transition that is already in flight.
            secondaryBitmap = bitmap
            secondaryReadyAtProgress = clampedProgress
            revision += 1
            return
        }
        if (bitmap !== secondaryBitmap && clampedProgress <= 0.001f) {
            // Before visible motion the prepared endpoint may still be replaced by a sharper source.
            // Once it participates, keep that endpoint stable just like the FLOW lane; admitting a
            // second texture upload mid-transition would trade the old snap for a RenderThread hitch.
            secondaryBitmap = bitmap
            secondaryReadyAtProgress = 0f
            revision += 1
        }
    }

    fun visibleMixProgress(rawProgress: Float): Float {
        if (secondaryKey == null || secondaryBitmap == null) return 0f
        return ReferenceFlowAaMixProgress(rawProgress, secondaryReadyAtProgress)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStaticArtworkCenterCrop(
    image: ImageBitmap,
    alpha: Float,
) {
    if (size.width <= 0f || size.height <= 0f || image.width <= 0 || image.height <= 0) return
    val targetAspect = size.width / size.height
    val sourceAspect = image.width.toFloat() / image.height.toFloat()
    val srcWidth: Int
    val srcHeight: Int
    val srcX: Int
    val srcY: Int
    if (sourceAspect > targetAspect) {
        srcHeight = image.height
        srcWidth = (srcHeight * targetAspect).roundToInt().coerceIn(1, image.width)
        srcX = ((image.width - srcWidth) / 2).coerceAtLeast(0)
        srcY = 0
    } else {
        srcWidth = image.width
        srcHeight = (srcWidth / targetAspect).roundToInt().coerceIn(1, image.height)
        srcX = 0
        srcY = ((image.height - srcHeight) / 2).coerceAtLeast(0)
    }
    drawImage(
        image = image,
        srcOffset = androidx.compose.ui.unit.IntOffset(srcX, srcY),
        srcSize = androidx.compose.ui.unit.IntSize(srcWidth, srcHeight),
        dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
        dstSize = androidx.compose.ui.unit.IntSize(
            size.width.roundToInt().coerceAtLeast(1),
            size.height.roundToInt().coerceAtLeast(1),
        ),
        alpha = alpha,
        filterQuality = FilterQuality.Low,
    )
}

@Composable
internal fun RawStaticArtworkTransitionBackground(
    currentCoverKey: String?,
    targetCoverKey: String?,
    currentArtwork: Bitmap?,
    targetArtwork: Bitmap?,
    progressProvider: () -> Float,
    modifier: Modifier = Modifier,
) {
    val tuningRevision = RawFlowTuningState.revision
    val config = remember(tuningRevision) {
        StaticAaConfig(
            saturation = RawFlowTuningState.staticSaturation,
            brightness = RawFlowTuningState.staticBrightness,
            gradient = RawFlowTuningState.staticGradient,
            gradientColor = RawFlowTuningState.staticGradientColor,
            blur = RawFlowTuningState.staticBlur,
            detail = RawFlowTuningState.staticDetail,
        )
    }
    val scene = remember(config) { RawStaticArtworkLiveSlots() }
    val currentIdentity = staticAaIdentity(currentCoverKey, currentArtwork)
    val currentSourceClass = staticAaSourceClass(currentArtwork, config.detail)
    val currentCached = remember(currentIdentity, config) {
        peekStaticAaEndpoint(currentCoverKey, currentArtwork, config)
    }
    scene.syncCurrent(currentCoverKey, currentCached)

    val hasSecondaryIdentity = !targetCoverKey.isNullOrBlank() && targetCoverKey != currentCoverKey
    val targetIdentity = staticAaIdentity(targetCoverKey, targetArtwork)
    val targetSourceClass = staticAaSourceClass(targetArtwork, config.detail)
    val secondaryCached = remember(targetIdentity, config, hasSecondaryIdentity) {
        if (hasSecondaryIdentity) peekStaticAaEndpoint(targetCoverKey, targetArtwork, config) else null
    }
    scene.prepareSecondary(targetCoverKey.takeIf { hasSecondaryIdentity }, secondaryCached)
    @Suppress("UNUSED_VARIABLE") val sceneRevision = scene.revision

    // Effect ownership is key/config/source-class based, not Bitmap-object based.  For finite artwork
    // detail, 512 -> 1024 provider upgrades stay in the same class and therefore cannot restart a
    // native prepare.  Only the explicit detail=10 unbounded lane may promote low -> high once.
    LaunchedEffect(currentIdentity, config, currentSourceClass) {
        if (currentSourceClass == 0) return@LaunchedEffect
        val bitmap = prepareStaticAaEndpoint(currentCoverKey, currentArtwork, config, traceLane = "current")
        scene.updateCurrentIfIdle(currentCoverKey, bitmap)
    }
    LaunchedEffect(targetIdentity, config, targetSourceClass, hasSecondaryIdentity) {
        val key = targetCoverKey?.takeIf { hasSecondaryIdentity } ?: return@LaunchedEffect
        if (targetSourceClass == 0) return@LaunchedEffect
        val bitmap = prepareStaticAaEndpoint(key, targetArtwork, config, traceLane = "target")
        val progress = progressProvider().coerceIn(0f, 1f)
        if (bitmap != null) {
            PlaybackArtworkPerfTrace.backgroundSecondaryAdmit(
                lane = "static",
                key = key,
                progress = progress,
                detail = "bitmap=${bitmap.width}x${bitmap.height}",
            )
        }
        scene.updateSecondaryDuringMotion(key, bitmap, progress)
    }

    // Reference StaticArtworkRenderer owns current + secondary artwork refs and one ratio. StaticArtworkRenderer.c()
    // removes obsolete loader messages before posting the next artwork load; there is no previous/next
    // four-endpoint artwork prefetch queue. Foreground ArtworkImageNode neighbours may be prefetched, but the
    // background loader must stay two-state so rapid Next cannot build throw-away native artwork textures
    // (and their Bitmap/JNI objects) during the active page session.

    val current = scene.currentBitmap?.takeUnless { it.isRecycled }
    val secondary = scene.secondaryBitmap?.takeUnless { it.isRecycled }
    // Reference baseline implementation artwork renderer draws a unit-square artwork quad through an aspect-correct
    // orthographic projection. The square therefore covers the surface uniformly and the excess
    // axis is clipped (portrait: left/right; landscape: top/bottom). Compose ContentScale.Crop is
    // the equivalent mapping; FillBounds would anisotropically stretch both artwork geometry and
    // the already-prepared blur field. The Canvas helper below applies the same center-crop mapping.
    val currentImage = remember(current) { current?.asImageBitmap() }
    val secondaryImage = remember(secondary) { secondary?.asImageBitmap() }
    // Reference StaticArtworkRenderer is one persistent renderer with two prepared artwork states and one ratio.
    // Mirror that draw ownership: a single Canvas emits current + secondary instead of two Compose
    // Image nodes (one of which previously owned a separate graphicsLayer).  The ratio is read in
    // draw phase so page motion invalidates only this renderer node.
    Canvas(modifier = modifier.fillMaxSize().background(Color.Black)) {
        currentImage?.let { drawStaticArtworkCenterCrop(it, alpha = 1f) }
        secondaryImage?.let {
            val alpha = scene.visibleMixProgress(progressProvider()).coerceIn(0f, 1f)
            if (alpha > 0.0001f) drawStaticArtworkCenterCrop(it, alpha = alpha)
        }
    }
}

@Composable
internal fun RawStaticArtworkSingleBackground(
    sourceCoverKey: String?,
    sourceArtwork: Bitmap?,
    modifier: Modifier = Modifier,
) {
    RawStaticArtworkTransitionBackground(
        currentCoverKey = sourceCoverKey,
        targetCoverKey = sourceCoverKey,
        currentArtwork = sourceArtwork,
        targetArtwork = sourceArtwork,
        progressProvider = { 0f },
        modifier = modifier,
    )
}
