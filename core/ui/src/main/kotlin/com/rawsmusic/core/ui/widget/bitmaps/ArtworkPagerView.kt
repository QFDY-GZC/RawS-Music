package com.rawsmusic.core.ui.widget.bitmaps

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.rawsmusic.module.data.prefs.PlayerHiResBadgeCorner
import com.rawsmusic.module.data.prefs.PlayerHiResBadgeSettings
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * View-backed player artwork lane aligned with Reference ArtworkPager + ArtworkImageNode.
 *
 * Reference keeps three physical artwork items attached, while ArtworkImageNode itself draws the accepted
 * bitmap through a BitmapShader/local matrix. Keep the same broad shape here: one host plus three
 * persistent self-drawing holders. There is no ImageView/BitmapDrawable/clipToOutline subtree in
 * the animation hot path.
 *
 * Each physical holder owns its provider request and ref-counted ArtworkHandle, matching
 * Reference ArtworkImageNode.holder bind callback -> image provider callback -> callback ownership. The transition state supplies only the
 * logical current/target/previous/next identities and page position; it never supplies foreground
 * pixels through a central token registry. A role rotation therefore cannot erase artwork that the
 * holder already owns.
 */
internal class ArtworkPagerView(context: Context) : FrameLayout(context) {
    private class Holder(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val previousPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val badgeBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        private val badgeClipPath = Path()
        private val badgeBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xA8000000.toInt() }
        private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        private val shaderMatrix = Matrix()
        private val previousShaderMatrix = Matrix()
        private val contentRect = RectF()
        private val artworkRect = RectF()
        private val previousArtworkRect = RectF()
        private var shader: BitmapShader? = null
        private var previousShader: BitmapShader? = null
        private var previousBitmap: Bitmap? = null
        private var replacementProgress = 1f
        private var previousStartAlpha = 1f
        private var replacementAnimator: ValueAnimator? = null
        private var boundKey: String = ""
        private var bindGeneration: Long = 0L
        private var request: BitmapRequest? = null
        private var transientRetryRunnable: Runnable? = null
        private var transientRetryAttempt: Int = 0
        private var currentHandle: ArtworkHandle? = null
        private var acceptedArtworkKey: String = ""
        private var previousHandle: ArtworkHandle? = null
        private var sourceDecodeAllowed: Boolean = false
        var onArtworkAccepted: ((String, Bitmap, Int) -> Unit)? = null
        var onTerminalNoArtwork: ((String) -> Unit)? = null

        var token: Int = 0
        var bitmap: Bitmap? = null
        var quality: Int = 0
        var role: PlayerArtworkItemRole = PlayerArtworkItemRole.Target
        var parkedDirection: PlayerArtworkDirection? = null
        var active: Boolean = false

        private var contentInsetPx: Int = 0
        private var contentScaleFactor: Float = 1f
        private var cornerRadiusPx: Float = 0f
        private var drawnCornerRadiusPx: Float = 0f
        private var badgeVisible: Boolean = false
        private var badgeCorner: PlayerHiResBadgeCorner = PlayerHiResBadgeCorner.TOP_LEFT
        private var badgeCustomBitmap: Bitmap? = null
        private var badgeDensity: Float = 1f

        init {
            // One shader draw never overlaps itself, so parent alpha/3D transforms do not require an
            // intermediate saveLayer. This keeps the artwork RenderNode as cheap as possible.
            setWillNotDraw(false)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    if (artworkRect.width() <= 0f || artworkRect.height() <= 0f) return
                    outline.setRoundRect(
                        floor(artworkRect.left).toInt(),
                        floor(artworkRect.top).toInt(),
                        ceil(artworkRect.right).toInt(),
                        ceil(artworkRect.bottom).toInt(),
                        drawnCornerRadiusPx,
                    )
                }
            }
        }

        override fun hasOverlappingRendering(): Boolean = false

        fun configureImageGeometry(
            insetPx: Int,
            localScale: Float,
            radiusPx: Float,
            elevationPx: Float,
        ) {
            val safeInset = insetPx.coerceAtLeast(0)
            val safeScale = localScale.coerceIn(0.01f, 1f)
            val safeRadius = radiusPx.coerceAtLeast(0f)
            val safeElevation = elevationPx.coerceAtLeast(0f)
            if (
                contentInsetPx == safeInset &&
                contentScaleFactor == safeScale &&
                cornerRadiusPx == safeRadius &&
                elevation == safeElevation
            ) return
            contentInsetPx = safeInset
            contentScaleFactor = safeScale
            cornerRadiusPx = safeRadius
            elevation = safeElevation
            updateArtworkGeometry()
        }

        fun configureHiResBadge(
            visible: Boolean,
            corner: PlayerHiResBadgeCorner,
            customBitmap: Bitmap?,
            density: Float,
        ) {
            val safeCustom = customBitmap?.takeUnless(Bitmap::isRecycled)
            if (
                badgeVisible == visible &&
                badgeCorner == corner &&
                badgeCustomBitmap === safeCustom &&
                badgeDensity == density
            ) return
            badgeVisible = visible
            badgeCorner = corner
            badgeCustomBitmap = safeCustom
            badgeDensity = density.coerceAtLeast(0.1f)
            invalidate()
        }

        val key: String
            get() = boundKey

        /**
         * Reference ArtworkImageNode.holder bind callback: bind identity first. Different identity releases the old
         * wrapper/request; the same identity keeps the physical wrapper while its role changes.
         */
        fun bindIdentity(newKey: String, newToken: Int, allowSourceDecode: Boolean) {
            token = newToken
            if (boundKey == newKey) {
                val sourceDecodePromoted = !sourceDecodeAllowed && allowSourceDecode
                sourceDecodeAllowed = allowSourceDecode
                if (newKey.isNotBlank() && request == null &&
                    (currentHandle?.isValid != true || acceptedArtworkKey != newKey || sourceDecodePromoted)
                ) {
                    cancelTransientRetry(resetAttempt = false)
                    attachCachedOrRequest(newKey)
                }
                return
            }

            PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.HOLDER_BIND)
            bindGeneration += 1L
            cancelTransientRetry()
            cancelRequest()
            cancelReplacementFade()
            clearPreviousVisual(releaseHandle = true)
            // ArtworkImageNode keeps its last drawable while a new source identity is resolving.
            // Clearing the current wrapper here created the cold-start "lost cover" frame: the
            // state had already advanced to the new song, but the preferred wrapper had not arrived
            // yet. Retain the old physical pixels in this holder and let installHandle() perform the
            // normal in-place replacement fade when the new wrapper is delivered.
            val retainedHandle = currentHandle
            val retainedBitmap = bitmap
            val retainedShader = shader
            val retainedQuality = quality
            val retainPreviousPixels = allowSourceDecode && boundKey.isNotBlank() &&
                retainedHandle?.isValid == true &&
                retainedBitmap != null &&
                !retainedBitmap.isRecycled
            boundKey = newKey
            sourceDecodeAllowed = allowSourceDecode
            if (retainPreviousPixels) {
                currentHandle = retainedHandle
                bitmap = retainedBitmap
                quality = retainedQuality
                shader = retainedShader ?: BitmapShader(
                    retainedBitmap,
                    Shader.TileMode.MIRROR,
                    Shader.TileMode.MIRROR,
                )
                paint.shader = shader
                // Holder alpha is owned by the outer artwork transform. A previously parked holder
                // may have paint alpha=0, so restore the local drawable to a neutral full-pixel state.
                paint.alpha = 255
                updateArtworkGeometry()
            } else {
                retainedHandle?.release()
                currentHandle = null
                acceptedArtworkKey = ""
                bitmap = null
                quality = 0
                shader = null
                paint.shader = null
            }
            paint.alpha = 255
            previousPaint.alpha = 255
            invalidate()

            if (newKey.isNotBlank()) attachCachedOrRequest(newKey)
        }

        /** ArtworkImageNode.image provider callback: every bound physical holder owns its provider flight, including parked holders. */
        private fun attachCachedOrRequest(key: String) {
            if (key.isBlank() || key != boundKey) return
            val preferredSide = BitmapProvider.preferredPlaybackTargetSide()
            // reference player's provider record keeps a low/high wrapper pair. A parked holder requests
            // only the low wrapper; the full playback tier is promoted after that holder becomes
            // the settled current item. This keeps source decode/allocation off the artwork-motion
            // timeline while still ensuring every previous/current/next physical holder owns real
            // pixels before it is promoted.
            val targetSide = if (sourceDecodeAllowed) {
                preferredSide
            } else {
                AlbumArtTiers.LOW_RES_NORMAL_CAP
            }
            val preferred = BitmapProvider.acquirePreferredPlayback(
                key = key,
                surface = ArtworkSurface.Playback,
                aspectPolicy = ArtworkAspectPolicy.KeepAspect,
            )
            if (preferred != null && preferred.isValid) {
                // This is the exact selected playback cache bucket. The source artwork itself may
                // legitimately be smaller than targetSide (for example a real 600x600 embedded
                // cover requested for a 1536px playback tier). Pixel dimensions therefore cannot
                // be used as proof that the preferred wrapper is "too small": doing that caused
                // the holder to release a perfectly valid exact-tier wrapper, request the same
                // source again and remain visually empty while background consumers still drew it.
                cancelTransientRetry()
                installHandle(
                    preferred,
                    ArtworkDisplayResolver.qualityForPlaybackBitmap(preferred.bitmap),
                )
                return
            } else {
                preferred?.release()
            }

            // reference player ArtworkProvider returns its already-published low/high wrapper immediately
            // before scheduling a missing higher-quality request. Keep that lifecycle here too:
            // a retained holder must never go blank merely because only a list/mini/lower playback
            // wrapper is resident. Bind the best same-source pixels now, then upgrade in-place when
            // the exact playback tier arrives. Different identities still rebind atomically in
            // bindIdentity(), so this cannot leak an old song's bitmap into the new holder.
            if (currentHandle?.isValid != true) {
                val fallback = BitmapProvider.acquireAny(
                    key = key,
                    surface = ArtworkSurface.Playback,
                    minimumSide = 1,
                    aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                )
                if (fallback != null && fallback.isValid) {
                    installHandle(
                        fallback,
                        ArtworkDisplayResolver.qualityForPlaybackBitmap(fallback.bitmap),
                    )
                } else {
                    fallback?.release()
                }
            }

            if (request != null) return
            val residentSide = currentHandle
                ?.takeIf { it.isValid }
                ?.bitmap
                ?.let { maxOf(it.width, it.height) }
                ?: 0
            if (!sourceDecodeAllowed && residentSide >= AlbumArtTiers.LOW_RES_NORMAL_CAP) {
                return
            }

            val generationAtRequest = bindGeneration
            if (PlaybackArtworkPerfTrace.isActive()) {
                PlaybackArtworkPerfTrace.mark(
                    "holder_art_request",
                    "key=${PlaybackArtworkPerfTrace.keyTag(key)} generation=$generationAtRequest " +
                        "tier=${if (sourceDecodeAllowed) "high" else "low"} side=$targetSide resident=$residentSide",
                )
            }
            val callback: (Bitmap?) -> Unit = { loadedBitmap ->
                // terminalNoArt is set on the completed request before this callback is dispatched.
                // Capture it now because the posted UI admission may run after another bind.
                val terminalNoArt = request?.terminalNoArt == true
                val loadedHandle = BitmapProvider.acquireLoaded(
                    key = key,
                    bitmap = loadedBitmap,
                    targetWidth = targetSide,
                    targetHeight = targetSide,
                    surface = ArtworkSurface.Playback,
                    aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                )
                post {
                    if (!shouldAcceptArtworkProviderResult(
                            boundKey = boundKey,
                            currentGeneration = bindGeneration,
                            callbackKey = key,
                            callbackGeneration = generationAtRequest,
                        )
                    ) {
                        loadedHandle?.release()
                        return@post
                    }
                    request = null
                    if (loadedHandle != null && loadedHandle.isValid) {
                        cancelTransientRetry()
                        installHandle(
                            loadedHandle,
                            ArtworkDisplayResolver.qualityForPlaybackBitmap(loadedHandle.bitmap),
                        )
                    } else {
                        loadedHandle?.release()
                        if (terminalNoArt) {
                            cancelTransientRetry()
                            installTerminalArtwork(key)
                        } else {
                            // A non-terminal null is a transient provider/source failure, not an
                            // artwork verdict. Keep this physical holder bound to the same identity
                            // and retry at a low frequency until the provider publishes a wrapper.
                            // The generation check below guarantees that a late retry can never
                            // repopulate a holder that has since rebound to another song.
                            scheduleTransientRetry(key, generationAtRequest)
                        }
                    }
                }
            }
            request = if (sourceDecodeAllowed) {
                BitmapProvider.load(
                    key = key,
                    targetWidth = targetSide,
                    targetHeight = targetSide,
                    priority = AlbumArtTiers.PLAYBACK_PROVIDER_PRIORITY,
                    surface = ArtworkSurface.Playback,
                    aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                    callback = callback,
                )
            } else {
                BitmapProvider.loadThumbnail(
                    key = key,
                    targetWidth = targetSide,
                    targetHeight = targetSide,
                    priority = AlbumArtTiers.PLAYBACK_PROVIDER_PRIORITY,
                    surface = ArtworkSurface.Playback,
                    aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                    callback = callback,
                )
            }
        }

        private fun scheduleTransientRetry(expectedKey: String, expectedGeneration: Long) {
            if (expectedKey.isBlank() || boundKey != expectedKey || bindGeneration != expectedGeneration) return
            if (!sourceDecodeAllowed) return
            if (transientRetryRunnable != null) return

            val delayMs = when (transientRetryAttempt) {
                0 -> 80L
                1 -> 160L
                2 -> 320L
                3 -> 640L
                4 -> 1_280L
                5 -> 2_500L
                else -> 5_000L
            }
            transientRetryAttempt = (transientRetryAttempt + 1).coerceAtMost(6)

            lateinit var retry: Runnable
            retry = Runnable {
                if (transientRetryRunnable !== retry) return@Runnable
                transientRetryRunnable = null
                if (!isAttachedToWindow || boundKey != expectedKey || bindGeneration != expectedGeneration) {
                    return@Runnable
                }
                if (request != null) return@Runnable
                PlaybackArtworkPerfTrace.mark(
                    "holder_art_retry",
                    "key=${PlaybackArtworkPerfTrace.keyTag(expectedKey)} generation=$expectedGeneration attempt=$transientRetryAttempt",
                )
                attachCachedOrRequest(expectedKey)
            }
            transientRetryRunnable = retry
            postDelayed(retry, delayMs)
        }

        private fun cancelTransientRetry(resetAttempt: Boolean = true) {
            transientRetryRunnable?.let(::removeCallbacks)
            transientRetryRunnable = null
            if (resetAttempt) transientRetryAttempt = 0
        }

        /**
         * Reference artwork fallback resolver treats its skin/default drawable as a drawable fallback, not as
         * a provider wrapper for the bound song id. Keep that ownership boundary here: terminal
         * no-art is drawn by the same holder/shader/RectF pipeline, but it must never be inserted
         * into BitmapProvider under the real song key.
         */
        private fun installTerminalArtwork(key: String) {
            if (key != boundKey || key.isBlank()) return
            cancelTransientRetry()
            val side = 768
            val fallback = if (DefaultAlbumArtworkPolicy.enabled) {
                decodeDefaultAlbumArtwork(resources, side)
            } else {
                null
            } ?: Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply {
                eraseColor(0xFF2B2B2B.toInt())
            }
            val fallbackHandle = ArtworkHandle(
                sourceKey = "default-art:$key",
                tier = ArtworkTier.Any,
                surface = ArtworkSurface.Playback,
                bitmap = fallback,
                onRelease = {
                    if (!fallback.isRecycled) fallback.recycle()
                },
            )
            installHandle(
                newHandle = fallbackHandle,
                newQuality = ArtworkDisplayResolver.QUALITY_ANY,
                publishToTransition = false,
            )
            post {
                if (isAttachedToWindow && boundKey == key && bitmap === fallback && !fallback.isRecycled) {
                    onTerminalNoArtwork?.invoke(key)
                }
            }
        }

        /** ArtworkImageNode callback: install only if this holder still owns the same identity. */
        private fun installHandle(
            newHandle: ArtworkHandle,
            newQuality: Int,
            publishToTransition: Boolean = true,
        ) {
            if (!newHandle.isValid || newHandle.bitmap.isRecycled || boundKey.isBlank()) {
                newHandle.release()
                return
            }
            val perfStartedNs = if (PlaybackArtworkPerfTrace.isActive()) System.nanoTime() else 0L
            val oldHandle = currentHandle
            acceptedArtworkKey = boundKey
            val oldBitmap = oldHandle?.bitmap
            if (oldBitmap === newHandle.bitmap) {
                oldHandle.release()
                currentHandle = newHandle
                bitmap = newHandle.bitmap
                quality = maxOf(quality, newQuality)
                if (publishToTransition) publishAcceptedWrapper(boundKey, newHandle.bitmap, quality)
                if (perfStartedNs != 0L) {
                    PlaybackArtworkPerfTrace.recordDuration(
                        PlaybackArtworkPerfStage.HOLDER_BIND,
                        System.nanoTime() - perfStartedNs,
                    )
                }
                return
            }

            val oldQuality = quality
            val oldShader = shader
            val oldWasFallback = oldHandle?.sourceKey?.let(::isFallbackArtworkSourceKey) == true
            val newIsFallback = isFallbackArtworkSourceKey(newHandle.sourceKey)
            val oldCurrentVisibleAlpha = (paint.alpha / 255f).coerceIn(0f, 1f)
            cancelReplacementFade()
            // An interrupted local replacement has now been superseded by the pixels currently
            // drawn as the holder's main wrapper. Release the older previous wrapper first.
            clearPreviousVisual(releaseHandle = true)

            currentHandle = newHandle
            bitmap = newHandle.bitmap
            quality = newQuality
            shader = BitmapShader(newHandle.bitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
            paint.shader = shader
            updateArtworkGeometry()
            if (publishToTransition) publishAcceptedWrapper(boundKey, newHandle.bitmap, newQuality)

            val animateReplacement = alpha > 0.01f && shouldAnimateArtworkReplacement(
                previousWasFallback = oldWasFallback,
                nextIsFallback = newIsFallback,
            ) && oldBitmap != null && !oldBitmap.isRecycled && oldShader != null
            if (animateReplacement) {
                PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.HOLDER_REPLACEMENT_START)
                PlaybackArtworkPerfTrace.mark(
                    "replacement_start",
                    "key=${PlaybackArtworkPerfTrace.keyTag(boundKey)} q=$oldQuality->$newQuality fallback=$oldWasFallback->$newIsFallback",
                )
                previousHandle = oldHandle
                previousBitmap = oldBitmap
                previousShader = oldShader
                previousPaint.shader = oldShader
                previousStartAlpha = oldCurrentVisibleAlpha
                previousPaint.alpha = ReferenceArtworkAlphaByte(previousStartAlpha)
                paint.alpha = 0
                updatePreviousShaderMatrix(oldBitmap, oldShader)
                startReplacementFade()
            } else {
                oldHandle?.release()
                clearPreviousVisual(releaseHandle = true)
            }
            invalidate()
            if (perfStartedNs != 0L) {
                PlaybackArtworkPerfTrace.recordDuration(
                    PlaybackArtworkPerfStage.HOLDER_BIND,
                    System.nanoTime() - perfStartedNs,
                )
            }
        }

        private fun publishAcceptedWrapper(expectedKey: String, expectedBitmap: Bitmap, acceptedQuality: Int) {
            // Do not re-enter the transition observer while installHandle() is still mutating the
            // ArtworkImageNode shader/wrapper. Reference's provider callback installs the View wrapper
            // first; the static-artwork/background consumer observes that accepted wrapper afterwards.
            post {
                if (isAttachedToWindow && boundKey == expectedKey && bitmap === expectedBitmap && !expectedBitmap.isRecycled) {
                    onArtworkAccepted?.invoke(expectedKey, expectedBitmap, acceptedQuality)
                }
            }
        }

        private fun cancelRequest() {
            request?.let { BitmapProvider.cancel(it) }
            request = null
        }

        fun clearBinding() {
            bindGeneration += 1L
            cancelTransientRetry()
            cancelRequest()
            boundKey = ""
            sourceDecodeAllowed = false
            token = 0
            active = false
            cancelReplacementFade()
            clearPreviousVisual(releaseHandle = true)
            currentHandle?.release()
            currentHandle = null
            acceptedArtworkKey = ""
            bitmap = null
            quality = 0
            shader = null
            paint.shader = null
            invalidate()
        }

        /**
         * Temporarily leave the window without destroying the ArtworkImageNode equivalent.
         *
         * Compose may detach an AndroidView while the player/category scene is being moved. The
         * old implementation treated every detach as final and released the current wrapper, so
         * the next attach had to decode again and exposed a blank frame. Keep the accepted pixels
         * and logical identity; only cancel work whose callback cannot safely target a detached
         * View. A real release still uses clearBinding().
         */
        fun suspendBinding() {
            // A window detach is not a source change. Keep the provider flight alive just as the
            // reference ArtworkImageNode keeps its request/accepted wrapper alive while its scene
            // is parked. Cancelling here leaves a retained old bitmap with no request to replace
            // it after re-attach, which is the exact "loads once, disappears after off-screen" bug.
            cancelReplacementFade()
            clearPreviousVisual(releaseHandle = true)
            paint.alpha = 255
            previousPaint.alpha = 255
            invalidate()
        }

        fun republishAcceptedWrapper() {
            val acceptedBitmap = bitmap
            val acceptedQuality = quality
            val acceptedKey = acceptedArtworkKey
            if (acceptedKey.isBlank() || acceptedBitmap == null || acceptedBitmap.isRecycled) return
            if (acceptedKey != boundKey) return
            post {
                if (boundKey == acceptedKey && bitmap === acceptedBitmap && !acceptedBitmap.isRecycled) {
                    if (currentHandle?.sourceKey?.let(::isFallbackArtworkSourceKey) == true) {
                        onTerminalNoArtwork?.invoke(acceptedKey)
                    } else {
                        onArtworkAccepted?.invoke(acceptedKey, acceptedBitmap, acceptedQuality)
                    }
                }
            }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            updateArtworkGeometry()
        }

        private fun updateArtworkGeometry() {
            if (width <= 0 || height <= 0) return
            val left = contentInsetPx.toFloat()
            val top = contentInsetPx.toFloat()
            val right = (width - contentInsetPx).toFloat().coerceAtLeast(left + 1f)
            val bottom = (height - contentInsetPx).toFloat().coerceAtLeast(top + 1f)
            val centerX = (left + right) * 0.5f
            val centerY = (top + bottom) * 0.5f
            val halfWidth = (right - left) * 0.5f * contentScaleFactor
            val halfHeight = (bottom - top) * 0.5f * contentScaleFactor
            contentRect.set(
                centerX - halfWidth,
                centerY - halfHeight,
                centerX + halfWidth,
                centerY + halfHeight,
            )
            fitArtworkRect(bitmap, contentRect, artworkRect)
            fitArtworkRect(previousBitmap, contentRect, previousArtworkRect)
            // The resource corner belongs to the inner ArtworkImageNode. Keep the same nominal
            // radius, but never let a narrow portrait cover turn it into a capsule.
            drawnCornerRadiusPx = min(
                cornerRadiusPx * contentScaleFactor,
                min(artworkRect.width(), artworkRect.height()).coerceAtLeast(0f) * 0.5f,
            )
            updateShaderMatrix()
            val oldBitmap = previousBitmap
            val oldShader = previousShader
            if (oldBitmap != null && oldShader != null && !oldBitmap.isRecycled) {
                updatePreviousShaderMatrix(oldBitmap, oldShader)
            }
            invalidateOutline()
            invalidate()
        }

        private fun fitArtworkRect(source: Bitmap?, envelope: RectF, outRect: RectF) {
            if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) {
                outRect.set(envelope)
                return
            }
            val scale = min(
                envelope.width() / source.width.toFloat().coerceAtLeast(1f),
                envelope.height() / source.height.toFloat().coerceAtLeast(1f),
            )
            val drawnWidth = source.width * scale
            val drawnHeight = source.height * scale
            val left = envelope.centerX() - drawnWidth * 0.5f
            val top = envelope.centerY() - drawnHeight * 0.5f
            outRect.set(left, top, left + drawnWidth, top + drawnHeight)
        }

        private fun updateShaderMatrix() {
            val source = bitmap ?: return
            val bitmapShader = shader ?: return
            if (source.isRecycled || artworkRect.width() <= 0f || artworkRect.height() <= 0f) return
            val bw = source.width.toFloat().coerceAtLeast(1f)
            val bh = source.height.toFloat().coerceAtLeast(1f)
            val scale = min(artworkRect.width() / bw, artworkRect.height() / bh)
            val dx = artworkRect.centerX() - bw * scale * 0.5f
            val dy = artworkRect.centerY() - bh * scale * 0.5f
            shaderMatrix.reset()
            shaderMatrix.setScale(scale, scale)
            shaderMatrix.postTranslate(dx, dy)
            bitmapShader.setLocalMatrix(shaderMatrix)
        }

        private fun updatePreviousShaderMatrix(source: Bitmap, bitmapShader: BitmapShader) {
            fitArtworkRect(source, contentRect, previousArtworkRect)
            if (source.isRecycled || previousArtworkRect.width() <= 0f || previousArtworkRect.height() <= 0f) return
            val bw = source.width.toFloat().coerceAtLeast(1f)
            val bh = source.height.toFloat().coerceAtLeast(1f)
            val scale = min(previousArtworkRect.width() / bw, previousArtworkRect.height() / bh)
            val dx = previousArtworkRect.centerX() - bw * scale * 0.5f
            val dy = previousArtworkRect.centerY() - bh * scale * 0.5f
            previousShaderMatrix.reset()
            previousShaderMatrix.setScale(scale, scale)
            previousShaderMatrix.postTranslate(dx, dy)
            bitmapShader.setLocalMatrix(previousShaderMatrix)
        }

        private fun startReplacementFade() {
            replacementProgress = 0f
            applyReplacementPaintAlphas()
            replacementAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                // Current baseline implementation ArtworkImageNode uses a 200 ms local wrapper replacement for the
                // normal player artwork lane. Outer ArtworkPager page motion keeps its own independent clock.
                duration = Reference_AA_REPLACEMENT_MS
                addUpdateListener { animator ->
                    replacementProgress = (animator.animatedValue as Float).coerceIn(0f, 1f)
                    applyReplacementPaintAlphas()
                    invalidate()
                }
                doOnEndCompat {
                    replacementProgress = 1f
                    clearPreviousVisual(cancelAnimator = false, releaseHandle = true)
                    replacementAnimator = null
                    PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.HOLDER_REPLACEMENT_END)
                    PlaybackArtworkPerfTrace.mark("replacement_end", "token=$token q=$quality")
                    invalidate()
                }
                start()
            }
        }

        private fun applyReplacementPaintAlphas() {
            val progress = replacementProgress.coerceIn(0f, 1f)
            paint.alpha = ReferenceArtworkAlphaByte(progress)
            previousPaint.alpha = ReferenceArtworkAlphaByte(
                ReferenceInterruptedPreviousAlpha(
                    previousStartAlpha = previousStartAlpha,
                    replacementProgress = progress,
                )
            )
        }

        private fun clearPreviousVisual(
            cancelAnimator: Boolean = true,
            releaseHandle: Boolean = true,
        ) {
            if (cancelAnimator) {
                cancelReplacementFade()
            }
            if (releaseHandle) {
                previousHandle?.release()
                previousHandle = null
            }
            replacementProgress = 1f
            previousStartAlpha = 1f
            previousBitmap = null
            previousShader = null
            previousPaint.shader = null
            previousPaint.alpha = 255
            paint.alpha = 255
        }

        private fun cancelReplacementFade() {
            val animator = replacementAnimator ?: return
            replacementAnimator = null
            animator.removeAllUpdateListeners()
            animator.removeAllListeners()
            animator.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val current = bitmap ?: return
            if (current.isRecycled || paint.shader == null) return
            val old = previousBitmap
            if (old != null && !old.isRecycled && previousPaint.shader != null && replacementProgress < 1f) {
                val previousRadius = min(
                    cornerRadiusPx * contentScaleFactor,
                    min(previousArtworkRect.width(), previousArtworkRect.height()).coerceAtLeast(0f) * 0.5f,
                )
                canvas.drawRoundRect(previousArtworkRect, previousRadius, previousRadius, previousPaint)
            } else if (replacementAnimator == null) {
                paint.alpha = 255
            }
            canvas.drawRoundRect(artworkRect, drawnCornerRadiusPx, drawnCornerRadiusPx, paint)
            drawHiResBadge(canvas)
        }

        private fun drawHiResBadge(canvas: Canvas) {
            if (!badgeVisible || artworkRect.width() <= 0f || artworkRect.height() <= 0f) return
            val margin = 12f * badgeDensity
            val custom = badgeCustomBitmap?.takeUnless(Bitmap::isRecycled)
            val badgeSide = min(
                54f * badgeDensity,
                min(artworkRect.width(), artworkRect.height()) * 0.22f,
            ).coerceAtLeast(18f * badgeDensity)
            val badgeWidth = if (custom != null) badgeSide else min(58f * badgeDensity, artworkRect.width() * 0.30f)
            val badgeHeight = if (custom != null) badgeSide else min(28f * badgeDensity, artworkRect.height() * 0.16f)
            val left = when (badgeCorner) {
                PlayerHiResBadgeCorner.TOP_LEFT,
                PlayerHiResBadgeCorner.BOTTOM_LEFT -> artworkRect.left + margin
                PlayerHiResBadgeCorner.TOP_RIGHT,
                PlayerHiResBadgeCorner.BOTTOM_RIGHT -> artworkRect.right - margin - badgeWidth
            }
            val top = when (badgeCorner) {
                PlayerHiResBadgeCorner.TOP_LEFT,
                PlayerHiResBadgeCorner.TOP_RIGHT -> artworkRect.top + margin
                PlayerHiResBadgeCorner.BOTTOM_LEFT,
                PlayerHiResBadgeCorner.BOTTOM_RIGHT -> artworkRect.bottom - margin - badgeHeight
            }
            val destination = RectF(left, top, left + badgeWidth, top + badgeHeight)
            val save = canvas.save()
            badgeClipPath.reset()
            badgeClipPath.addRoundRect(
                artworkRect,
                drawnCornerRadiusPx,
                drawnCornerRadiusPx,
                Path.Direction.CW,
            )
            canvas.clipPath(badgeClipPath)
            if (custom != null) {
                canvas.drawBitmap(custom, null, destination, badgeBitmapPaint)
            } else {
                val radius = 7f * badgeDensity
                canvas.drawRoundRect(destination, radius, radius, badgeBackgroundPaint)
                badgeTextPaint.textSize = 10f * badgeDensity
                val baseline = destination.centerY() -
                    (badgeTextPaint.ascent() + badgeTextPaint.descent()) * 0.5f
                canvas.drawText("HI-RES", destination.centerX(), baseline, badgeTextPaint)
            }
            canvas.restoreToCount(save)
        }

        private inline fun ValueAnimator.doOnEndCompat(crossinline action: () -> Unit) {
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = action()
            })
        }
    }

    private data class Slot(
        val key: String,
        val token: Int,
        val role: PlayerArtworkItemRole,
        val parkedDirection: PlayerArtworkDirection? = null,
    )

    private val holders = Array(3) { Holder(context) }
    // Physical children never move in the ViewGroup array. Reference updates retained holder scene
    // properties/dirty bits instead of reparenting during artwork changes. Custom drawing order keeps
    // z-order changes layout-free; bringToFront() would requestLayout() exactly at target admission.
    private var holderDrawingOrder = IntArray(holders.size) { it }
    private var state: PlaybackArtworkTransitionState? = null
    // A player/scene handoff can temporarily keep the normal artwork view and the shared-element
    // artwork view alive together. Track this view's provider lease independently so one view's
    // detach/release cannot deactivate the state-side owner for the other view.
    private var providerOwnerState: PlaybackArtworkTransitionState? = null
    private var providerOwnerRegistered = false
    private var observerRegistered = false
    private val presentationObserver = PlaybackArtworkPresentationObserver { topologyChanged, pixelsChangedToken ->
        PlaybackArtworkPerfTrace.measure(PlaybackArtworkPerfStage.OBSERVER_DISPATCH) {
            if (topologyChanged) syncTopology()
            // Foreground pixels are delivered directly to the bound physical holder by its own
            // provider callback. State pixel notifications belong to background/legacy consumers.
            syncTransforms()
        }
    }

    private var contentInsetPx = 0
    private var contentScaleFactor = 1f
    private var cornerRadiusPx = 0f
    private var elevationPx = 0f
    private var itemSizePx = 1f
    private var hiResBadgeSettings = PlayerHiResBadgeSettings()
    private var hiResArtworkKeys: Set<String> = emptySet()
    private var hiResCustomBadgeBitmap: Bitmap? = null

    init {
        clipChildren = false
        clipToPadding = false
        setChildrenDrawingOrderEnabled(true)
        holders.forEach { holder ->
            addView(holder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
    }

    override fun getChildDrawingOrder(childCount: Int, drawingPosition: Int): Int {
        if (childCount == holders.size && drawingPosition in holderDrawingOrder.indices) {
            return holderDrawingOrder[drawingPosition]
        }
        return super.getChildDrawingOrder(childCount, drawingPosition)
    }

    fun bind(
        newState: PlaybackArtworkTransitionState,
        insetPx: Int,
        localScale: Float,
        radiusPx: Float,
        elevationPx: Float,
        hiResBadgeSettings: PlayerHiResBadgeSettings = PlayerHiResBadgeSettings(),
        hiResArtworkKeys: Set<String> = emptySet(),
    ) {
        val stateChanged = state !== newState
        if (stateChanged) {
            releaseProviderOwner()
            unregisterObserver()
            state = newState
            holders.forEach { holder ->
                holder.onArtworkAccepted = { key, bitmap, quality ->
                    state?.acceptArtworkImage(key, bitmap, quality)
                }
                holder.onTerminalNoArtwork = { key ->
                    state?.acceptArtworkImageTerminalNoArt(key)
                }
            }
            acquireProviderOwner(newState)
            registerObserver()
        }
        val safeInset = insetPx.coerceAtLeast(0)
        val safeScale = localScale.coerceIn(0.01f, 1f)
        val safeRadius = radiusPx.coerceAtLeast(0f)
        val safeElevation = elevationPx.coerceAtLeast(0f)
        val geometryChanged =
            contentInsetPx != safeInset ||
                contentScaleFactor != safeScale ||
                cornerRadiusPx != safeRadius ||
                this.elevationPx != safeElevation
        if (geometryChanged) {
            contentInsetPx = safeInset
            contentScaleFactor = safeScale
            cornerRadiusPx = safeRadius
            this.elevationPx = safeElevation
            applyImageGeometry()
        }

        val badgePathChanged = this.hiResBadgeSettings.customPath != hiResBadgeSettings.customPath
        val badgeConfigurationChanged = this.hiResBadgeSettings != hiResBadgeSettings ||
            this.hiResArtworkKeys != hiResArtworkKeys
        if (badgePathChanged) {
            hiResCustomBadgeBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
            hiResCustomBadgeBitmap = decodePlayerHiResBadgeBitmap(hiResBadgeSettings.customPath)
        }
        this.hiResBadgeSettings = hiResBadgeSettings
        this.hiResArtworkKeys = hiResArtworkKeys
        if (badgeConfigurationChanged || badgePathChanged) applyBadgeConfiguration()

        // The View already owns a direct PlaybackArtworkPresentationObserver. Ratio/topology ticks
        // arrive there and mutate the three retained holder RenderNodes directly. AndroidView's
        // update callback may be invoked by surrounding Compose state in the same frame; replaying
        // syncTopology()+syncTransforms() here creates a second presentation owner and doubles the
        // hot-path work. Only a new state needs an explicit initial sync. Geometry changes are
        // applied to the holders above and size changes have their own onSizeChanged() path.
        if (!stateChanged) return

        updateItemSize()
        syncTopology()
        syncTransforms()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateItemSize()
        syncTransforms()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        state?.let(::acquireProviderOwner)
        PlaybackArtworkPerfTrace.attach(this)
        registerObserver()
        syncTopology()
        syncTransforms()
        holders.forEach(Holder::republishAcceptedWrapper)
    }

    override fun onDetachedFromWindow() {
        releaseProviderOwner()
        unregisterObserver()
        PlaybackArtworkPerfTrace.detach(this)
        // Detach is a temporary scene-window event, not destruction. Keep the accepted wrapper and
        // pixels so a PLAYER/category re-attach is a zero-decode handoff. Only in-flight callbacks
        // and interrupted replacement state are cancelled; release() remains the destructive path.
        holders.forEach { it.suspendBinding() }
        super.onDetachedFromWindow()
    }

    fun release() {
        releaseProviderOwner()
        unregisterObserver()
        state = null
        holders.forEach { holder ->
            holder.onArtworkAccepted = null
            holder.clearBinding()
        }
        hiResCustomBadgeBitmap?.takeUnless(Bitmap::isRecycled)?.recycle()
        hiResCustomBadgeBitmap = null
    }

    private fun acquireProviderOwner(ownerState: PlaybackArtworkTransitionState) {
        if (providerOwnerRegistered && providerOwnerState === ownerState) return
        releaseProviderOwner()
        ownerState.acquireArtworkImageProviderOwner()
        providerOwnerState = ownerState
        providerOwnerRegistered = true
    }

    private fun releaseProviderOwner() {
        if (!providerOwnerRegistered) return
        providerOwnerState?.releaseArtworkImageProviderOwner()
        providerOwnerState = null
        providerOwnerRegistered = false
    }

    private fun registerObserver() {
        val currentState = state ?: return
        if (observerRegistered) return
        currentState.addPresentationObserver(presentationObserver)
        observerRegistered = true
    }

    private fun unregisterObserver() {
        val currentState = state ?: return
        if (!observerRegistered) return
        currentState.removePresentationObserver(presentationObserver)
        observerRegistered = false
    }

    private fun updateItemSize() {
        if (width <= 0 || height <= 0) return
        itemSizePx = min(width, height).toFloat().coerceAtLeast(1f)
        state?.updateForegroundItemExtentPx(itemSizePx)
    }

    private fun applyImageGeometry() {
        holders.forEach { holder ->
            holder.configureImageGeometry(
                insetPx = contentInsetPx,
                localScale = contentScaleFactor,
                radiusPx = cornerRadiusPx,
                elevationPx = elevationPx,
            )
        }
    }

    private fun applyBadgeConfiguration() {
        val density = resources.displayMetrics.density
        holders.forEach { holder ->
            holder.configureHiResBadge(
                visible = hiResBadgeSettings.enabled && holder.key in hiResArtworkKeys,
                corner = hiResBadgeSettings.corner,
                customBitmap = hiResCustomBadgeBitmap,
                density = density,
            )
        }
    }

    private fun slot(
        key: String,
        token: Int,
        role: PlayerArtworkItemRole,
        parkedDirection: PlayerArtworkDirection? = null,
    ): Slot? {
        if (key.isBlank()) return null
        return Slot(
            key = key,
            token = token,
            role = role,
            parkedDirection = parkedDirection,
        )
    }

    private fun syncTopology() {
        val startedNs = if (PlaybackArtworkPerfTrace.isActive()) System.nanoTime() else 0L
        val currentState = state ?: return
        PlaybackArtworkPerfTrace.count(PlaybackArtworkPerfEvent.HOLDER_TOPOLOGY_SYNC)
        val current = slot(
            currentState.foregroundCurrentKey(),
            currentState.foregroundCurrentToken(),
            PlayerArtworkItemRole.Current,
        )
        val target = slot(
            currentState.foregroundTargetKey(),
            currentState.foregroundTargetToken(),
            PlayerArtworkItemRole.Target,
        )
        val previous = slot(
            currentState.foregroundPreviousParkedKey(),
            currentState.foregroundPreviousParkedToken(),
            PlayerArtworkItemRole.Target,
            PlayerArtworkDirection.Previous,
        )
        val next = slot(
            currentState.foregroundNextParkedKey(),
            currentState.foregroundNextParkedToken(),
            PlayerArtworkItemRole.Target,
            PlayerArtworkDirection.Next,
        )

        val selected = ArrayList<Slot>(3)
        fun selectDistinct(value: Slot?) {
            if (value == null || selected.any { it.key == value.key }) return
            if (selected.size < holders.size) selected.add(value)
        }

        selectDistinct(current)
        selectDistinct(target)
        if (target != null) {
            val continuationParkedHolder = currentState.preferForwardParkedHolderDuringTarget()
            if (currentState.direction == PlayerArtworkDirection.Next) {
                selectDistinct(if (continuationParkedHolder) next else previous)
            } else {
                selectDistinct(if (continuationParkedHolder) previous else next)
            }
        } else {
            selectDistinct(previous)
            selectDistinct(next)
        }

        val ordered = ArrayList<Slot>(3)
        fun order(value: Slot?) {
            // Selection gives Current/Target priority over parked aliases. Sorting must retain
            // that exact slot (role + token), not resurrect a parked slot with the same key.
            if (value != null && selected.any { it == value } &&
                ordered.none { it.key == value.key }) {
                ordered.add(value)
            }
        }
        order(previous)
        order(next)
        if (currentState.direction == PlayerArtworkDirection.Next) {
            order(current)
            order(target)
        } else {
            order(target)
            order(current)
        }

        // ArtworkItemNode/ArtworkImageNode physical children never lose their wrapper simply because the
        // logical layout role changes. Reuse a holder by bound identity first; only a genuinely new
        // identity rebinds that physical holder and starts its one provider request.
        holders.forEach { it.active = false }
        ordered.forEach { desired ->
            val holder = holders.firstOrNull { it.key == desired.key }
                ?: holders.firstOrNull { candidate ->
                    !candidate.active && ordered.none { slot -> slot.key == candidate.key }
                }
                ?: holders.firstOrNull { !it.active }
                ?: holders[0]
            holder.role = desired.role
            holder.parkedDirection = desired.parkedDirection
            // Reference ArtworkItemNode.holder bind/reuse callbacks calls ArtworkImageNode.image provider callback after every bound/reused holder,
            // including the parked third holder. Do not create a navigation-time cold window by
            // suppressing that holder's provider request while another page is moving.
            holder.bindIdentity(
                newKey = desired.key,
                newToken = desired.token,
                // The moving pager consumes already-owned low/high wrappers only. Do not begin a
                // full-size source decode when a target is promoted into motion; that allocation
                // can trigger a stop-the-world GC/texture upload in the middle of the 550 ms page
                // animation. A near-complete settle can briefly promote target -> current and make
                // target null before the settle owner itself has finished. Treat that as motion,
                // not idle: starting a 1536px upgrade in that transient window made the high decode
                // land inside the very next rapid-Next animation. Only a genuinely idle current
                // holder may upgrade its low wrapper to the selected playback tier in-place.
                allowSourceDecode = target == null &&
                    !currentState.isSettling &&
                    !currentState.isGestureActive &&
                    desired.key == current?.key,
            )
            holder.configureHiResBadge(
                visible = hiResBadgeSettings.enabled && desired.key in hiResArtworkKeys,
                corner = hiResBadgeSettings.corner,
                customBitmap = hiResCustomBadgeBitmap,
                density = resources.displayMetrics.density,
            )
            holder.active = true
        }
        holders.forEach { holder -> if (!holder.active) holder.alpha = 0f }
        updateHolderDrawingOrder(ordered)
        if (startedNs != 0L) {
            PlaybackArtworkPerfTrace.recordDuration(
                PlaybackArtworkPerfStage.HOLDER_TOPOLOGY_SYNC,
                System.nanoTime() - startedNs,
            )
            PlaybackArtworkPerfTrace.mark(
                "holder_topology",
                "current=${PlaybackArtworkPerfTrace.keyTag(current?.key.orEmpty())}/${current?.token ?: 0} " +
                    "target=${PlaybackArtworkPerfTrace.keyTag(target?.key.orEmpty())}/${target?.token ?: 0} " +
                    "prev=${PlaybackArtworkPerfTrace.keyTag(previous?.key.orEmpty())}/${previous?.token ?: 0} " +
                    "next=${PlaybackArtworkPerfTrace.keyTag(next?.key.orEmpty())}/${next?.token ?: 0}",
            )
        }
    }

    private fun updateHolderDrawingOrder(ordered: List<Slot>) {
        val nextOrder = IntArray(holders.size)
        var cursor = 0
        fun alreadyAdded(index: Int): Boolean {
            for (position in 0 until cursor) {
                if (nextOrder[position] == index) return true
            }
            return false
        }

        // Inactive children are transparent and stay behind all active artwork holders.
        for (index in holders.indices) {
            if (!holders[index].active) nextOrder[cursor++] = index
        }
        for (desired in ordered) {
            val index = holders.indexOfFirst { it.active && it.key == desired.key }
            if (index >= 0 && !alreadyAdded(index)) nextOrder[cursor++] = index
        }
        // Defensive fill for a partially-bound topology; every physical child must occur once.
        for (index in holders.indices) {
            if (!alreadyAdded(index)) nextOrder[cursor++] = index
        }
        if (!holderDrawingOrder.contentEquals(nextOrder)) {
            holderDrawingOrder = nextOrder
            invalidate()
        }
    }

    private fun syncTransforms() {
        val startedNs = if (PlaybackArtworkPerfTrace.isActive()) System.nanoTime() else 0L
        val currentState = state ?: return
        if (width <= 0 || height <= 0) return
        val progress = currentState.foregroundPresentationRatio().coerceIn(0f, 1f)
        val stableCurrentOnly =
            !currentState.isGestureActive &&
                !currentState.isSettling &&
                currentState.foregroundTargetKey().isBlank()
        holders.forEach { holder ->
            if (!holder.active) return@forEach

            // Reference ArtworkPager's retained current item is always neutral once no target or
            // gesture owns the page. Treat that as a View-side invariant instead of trusting a
            // historical ratio value: transport/provider acknowledgement can race the final settle
            // callback, and a stale ratio must never hide an otherwise valid current bitmap.
            if (stableCurrentOnly && holder.parkedDirection == null &&
                holder.role == PlayerArtworkItemRole.Current
            ) {
                setNeutralTransform(holder, 1f)
                return@forEach
            }

            if (
                holder.role == PlayerArtworkItemRole.Current &&
                currentState.isAutomaticOutgoingSuppressed(holder.token)
            ) {
                setNeutralTransform(holder, 0f)
                return@forEach
            }

            if (currentState.automaticFadeOnly && holder.parkedDirection == null) {
                val alpha = when (holder.role) {
                    PlayerArtworkItemRole.Current -> if (currentState.automaticCrossfadeArtworkFade) {
                        automaticCrossfadeArtworkOutAlpha(progress)
                    } else 1f - progress
                    PlayerArtworkItemRole.Target -> if (currentState.automaticCrossfadeArtworkFade) {
                        automaticCrossfadeArtworkInAlpha(progress)
                    } else progress
                }
                setNeutralTransform(holder, alpha)
                return@forEach
            }

            val signedDistance = when (holder.parkedDirection) {
                PlayerArtworkDirection.Previous -> PlayerArtworkDirection.Previous.sign.toFloat()
                PlayerArtworkDirection.Next -> PlayerArtworkDirection.Next.sign.toFloat()
                null -> when (holder.role) {
                    PlayerArtworkItemRole.Current -> -currentState.direction.sign.toFloat() * progress
                    PlayerArtworkItemRole.Target -> currentState.direction.sign.toFloat() * (1f - progress)
                }
            }.coerceIn(-1f, 1f)
            setArtworkPagerTransform(holder, signedDistance)
        }
        if (startedNs != 0L) {
            PlaybackArtworkPerfTrace.recordDuration(
                PlaybackArtworkPerfStage.HOLDER_TRANSFORM_SYNC,
                System.nanoTime() - startedNs,
            )
        }
    }

    private fun setArtworkPagerTransform(holder: Holder, signedDistance: Float) {
        val absoluteDistance = abs(signedDistance)
        // Use the same derived artwork pager transform geometry functions as the Compose/title lane. Keeping
        // one source of truth matters at 60/90/120 Hz: translation and top-art scale cannot drift
        // because one path rounded/reinterpreted artworkDenseFactor or artworkMaxScale independently.
        val scale = ReferenceNormalHolderScale(signedDistance)
        setIfChangedTranslation(
            holder,
            ReferenceNormalHolderTranslationXPx(signedDistance, itemSizePx),
        )
        if (holder.scaleX != scale) holder.scaleX = scale
        if (holder.scaleY != scale) holder.scaleY = scale
        val rotationZ = ReferenceArtworkRotationZ(signedDistance)
        val rotationX = ReferenceArtworkRotationX(signedDistance)
        val rotationY = ReferenceArtworkRotationY(signedDistance)
        if (holder.rotation != rotationZ) holder.rotation = rotationZ
        if (holder.rotationX != rotationX) holder.rotationX = rotationX
        if (holder.rotationY != rotationY) holder.rotationY = rotationY
        val alpha = ReferenceNormalHolderAlpha(absoluteDistance)
        if (holder.alpha != alpha) holder.alpha = alpha
    }

    private fun setNeutralTransform(holder: Holder, alpha: Float) {
        setIfChangedTranslation(holder, 0f)
        if (holder.scaleX != 1f) holder.scaleX = 1f
        if (holder.scaleY != 1f) holder.scaleY = 1f
        if (holder.rotation != 0f) holder.rotation = 0f
        if (holder.rotationX != 0f) holder.rotationX = 0f
        if (holder.rotationY != 0f) holder.rotationY = 0f
        val safeAlpha = alpha.coerceIn(0f, 1f)
        if (holder.alpha != safeAlpha) holder.alpha = safeAlpha
    }

    private fun setIfChangedTranslation(holder: Holder, value: Float) {
        if (holder.translationX != value) holder.translationX = value
    }
}
