package com.rawsmusic.core.ui.widget.virtuallist

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.Choreographer
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.rawsmusic.core.ui.perf.TransitionPerfEvent
import com.rawsmusic.core.ui.perf.TransitionPerfStage
import com.rawsmusic.core.ui.perf.TransitionPerfTrace
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkAspectPolicy
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkHandle
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkTier
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.ReferenceArtworkAlphaByte
import com.rawsmusic.core.ui.widget.bitmaps.ReferenceInterruptedPreviousAlpha
import com.rawsmusic.core.ui.widget.bitmaps.Reference_AA_REPLACEMENT_MS
import com.rawsmusic.core.ui.widget.bitmaps.RawArtworkPolicy
import com.rawsmusic.core.ui.widget.bitmaps.decodeDefaultAlbumArtwork
import kotlin.math.min

/**
 * Reference ArtworkImageNode-equivalent artwork actor for a retained Compose VirtualList holder.
 *
 * The physical Compose slot owns this Modifier.Node. Binding, provider requests, wrapper lifetime,
 * stale-callback rejection, BitmapShader/matrix reuse and the tiny artwork fade all live outside
 * Snapshot state. Provider callbacks invalidate only this draw node; they never recompose the row.
 */
private enum class ArtworkFadeMode {
    None,
    Reveal,
    Replacement,
    FallbackReveal,
}

internal class VirtualListArtworkNode(
    private var binding: VirtualListArtworkBinding,
) : Modifier.Node(), DrawModifierNode, Choreographer.FrameCallback {
    override val shouldAutoInvalidate: Boolean
        get() = false

    private var boundGeneration = 0L
    // ArtworkImageNode-equivalent presentation state. Every displayed bitmap is owned by exactly one
    // provider ArtworkHandle lease; the holder returns that wrapper on identity change/detach.
    private var currentHandle: ArtworkHandle? = null
    private var currentBitmap: Bitmap? = null
    private var currentTier: ArtworkTier = ArtworkTier.Any
    private var previousHandle: ArtworkHandle? = null
    private var previousBitmap: Bitmap? = null
    private var currentShader: BitmapShader? = null
    private var previousShader: BitmapShader? = null
    private val currentMatrix = Matrix()
    private val previousMatrix = Matrix()
    private val currentRect = RectF()
    private val previousRect = RectF()
    private val fallbackRect = RectF()
    private var currentMatrixWidth = -1f
    private var currentMatrixHeight = -1f
    private var currentMatrixBitmapWidth = -1
    private var currentMatrixBitmapHeight = -1
    private var previousMatrixWidth = -1f
    private var previousMatrixHeight = -1f
    private var previousMatrixBitmapWidth = -1
    private var previousMatrixBitmapHeight = -1
    private var fallbackBitmap: Bitmap? = null
    private var fallbackShader: BitmapShader? = null
    private val fallbackMatrix = Matrix()
    private var fallbackMatrixWidth = -1f
    private var fallbackMatrixHeight = -1f
    private var fallbackMatrixBitmapWidth = -1
    private var fallbackMatrixBitmapHeight = -1

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var request: BitmapRequest? = null
    private var requestGeneration = 0L
    private var requestKey = ""
    private var requestSide = 0
    private var synchronousMissGeneration = Long.MIN_VALUE
    private var synchronousMissSide = 0

    private var terminalNoArt = false

    private var choreographer: Choreographer? = null
    private var framePosted = false
    private var fadeStartNs = 0L
    private var fadeDurationNs = 0L
    private var fadeProgress = 1f
    private var previousStartAlpha = 1f
    private var fadeMode = ArtworkFadeMode.None

    override fun onAttach() {
        super.onAttach()
        rebind(binding, force = true)
    }

    override fun onDetach() {
        detachRequest()
        stopFade(releasePrevious = true)
        releaseCurrent()
        super.onDetach()
    }

    internal fun update(next: VirtualListArtworkBinding) {
        val perfStartedNs = if (TransitionPerfTrace.isActive()) System.nanoTime() else 0L
        val previousKey = binding.key
        val previousExternalArtworkPath = binding.externalArtworkPath
        // ArtworkImageNode's visual identity is its provider type/id key. An external fallback path can
        // change underneath the same key without invalidating a wrapper that is already on screen.
        val identityChanged = next.key != previousKey
        val externalPathChanged = next.externalArtworkPath != previousExternalArtworkPath
        val sourceGeometryChanged = next.decodeSide != binding.decodeSide
        val deferEngaged = !binding.deferLoad && next.deferLoad
        val deferWasReleased = shouldRefreshVirtualListArtworkOnVisualReentry(
            wasDeferred = binding.deferLoad,
            isDeferred = next.deferLoad,
        )
        val visibilityChanged = next.hidden != binding.hidden
        val defaultPolicyChanged = next.defaultArtworkEnabled != binding.defaultArtworkEnabled
        val fadePolicyChanged = next.animateChanges != binding.animateChanges
        val radiusChanged = next.cornerRadiusPx != binding.cornerRadiusPx
        val resourcesChanged = next.resources !== binding.resources
        val priorityChanged = next.priority != binding.priority

        binding = next

        when {
            identityChanged -> rebind(next, force = true)
            visibilityChanged && next.hidden -> {
                detachRequest()
                stopFade(releasePrevious = true)
            }
            visibilityChanged -> {
                ensureBestCachedHandle(allowAnimation = false)
                if (!syncTerminalNoArtFromProvider(allowAnimation = false)) {
                    markSynchronousMissChecked()
                    ensureRequest()
                }
            }
            deferEngaged -> {
                // baseline implementation onStartTemporaryDetach preserves the current wrapper AND any J/O
                // provider request. Defer prevents a new request from starting, but an already
                // registered callback remains live and may install its wrapper while offscreen.
                stopFade(releasePrevious = true)
                invalidateMatrices()
            }
            sourceGeometryChanged -> {
                // Preserve the currently owned wrapper as the low-quality presentation, but move
                // the provider request to the new target bucket. Reference likewise keeps the bound
                // ArtworkImageNode drawable while a larger wrapper is being resolved.
                invalidateMatrices()
                if (!next.deferLoad) {
                    ensureBestCachedHandle(allowAnimation = false)
                    if (!syncTerminalNoArtFromProvider(allowAnimation = false)) {
                        markSynchronousMissChecked()
                        restartRequestForTargetIfNeeded()
                    }
                }
            }
            deferWasReleased -> {
                // The holder has re-entered the exact visual window. Reattach any wrapper already
                // present in provider/cache first, then start source work only if it is still missing.
                ensureBestCachedHandle(allowAnimation = false)
                if (!syncTerminalNoArtFromProvider(allowAnimation = false)) {
                    markSynchronousMissChecked()
                    ensureRequest()
                }
            }
            externalPathChanged -> {
                terminalNoArt = false
                // Same key, different fallback source metadata. Do not clear the current wrapper;
                // only restart a still-missing/undersized request so it can observe the new path.
                val targetSide = next.decodeSide.coerceAtLeast(1)
                if (!next.deferLoad && currentBitmap?.let { isAcceptable(it, targetSide) } != true) {
                    detachRequest()
                    ensureRequest()
                }
            }
            priorityChanged && request == null -> ensureRequest()
        }

        if (fadePolicyChanged && !next.animateChanges) {
            // Geometry ownership may suppress image-local fades. Do not clear the accepted
            // wrapper; simply finish the local artwork clock so movement never becomes a second fade clock.
            stopFade(releasePrevious = true)
        } else if (fadePolicyChanged && next.animateChanges && !next.deferLoad) {
            // Geometry ownership may temporarily suppress the image-local clock while provider callbacks
            // still install valid pixels. When that ownership ends, only recover from cache if this holder
            // still has no current bitmap. A genuinely never-presented key may reveal; a known key is opaque.
            val allowSettledReveal = currentBitmap?.isRecycled != false
            ensureBestCachedHandle(allowAnimation = allowSettledReveal)
            if (!syncTerminalNoArtFromProvider(allowAnimation = allowSettledReveal)) {
                markSynchronousMissChecked()
                ensureRequest()
            }
        }
        if (defaultPolicyChanged || resourcesChanged || sourceGeometryChanged) {
            // Resizing/toggling an already-terminal fallback is not a new provider acceptance.
            // Keep it opaque; only the first terminal admission gets the image-local reveal.
            refreshFallback()
        }
        if (
            visibilityChanged || deferWasReleased || sourceGeometryChanged || defaultPolicyChanged ||
            fadePolicyChanged || radiusChanged || resourcesChanged
        ) {
            // ArtworkImageNode is redrawn when it returns from temporary detach even if the provider
            // wrapper and dimensions are unchanged. Raw's retained Modifier.Node must explicitly
            // invalidate on defer -> visible; otherwise a previously empty draw record can survive
            // until pinch changes the measured pixel size.
            invalidateDraw()
        }
        if (perfStartedNs != 0L) {
            TransitionPerfTrace.recordDuration(
                TransitionPerfStage.ARTWORK_NODE_UPDATE,
                System.nanoTime() - perfStartedNs,
            )
        }
    }

    override fun ContentDrawScope.draw() {
        if (!binding.hidden) {
            val canvas = drawContext.canvas.nativeCanvas
            val width = size.width
            val height = size.height
            if (width > 0f && height > 0f) {
                val radius = binding.cornerRadiusPx.coerceAtLeast(0f)
                val previous = previousBitmap?.takeIf { !it.isRecycled }
                val current = currentBitmap?.takeIf { !it.isRecycled }
                if (current != null) {
                        }

                if (previous != null && fadeMode == ArtworkFadeMode.Replacement && fadeProgress < 1f) {
                    val shader = previousShader ?: shaderFor(previous, previous = true)
                    if (shader != null) {
                        ensureMatrix(shader, previous, width, height, previous = true)
                        paint.shader = shader
                        paint.alpha = ReferenceArtworkAlphaByte(
                            ReferenceInterruptedPreviousAlpha(
                                previousStartAlpha = previousStartAlpha,
                                replacementProgress = fadeProgress,
                            )
                        )
                        val previousRadius = min(radius, min(previousRect.width(), previousRect.height()) * 0.5f)
                        canvas.drawRoundRect(previousRect, previousRadius, previousRadius, paint)
                    }
                }

                if (current != null) {
                    val shader = currentShader ?: shaderFor(current, previous = false)
                    if (shader != null) {
                        ensureMatrix(shader, current, width, height, previous = false)
                        paint.shader = shader
                        paint.alpha = when (fadeMode) {
                            ArtworkFadeMode.Reveal,
                            ArtworkFadeMode.Replacement -> ReferenceArtworkAlphaByte(fadeProgress)
                            ArtworkFadeMode.FallbackReveal,
                            ArtworkFadeMode.None -> 255
                        }
                        val currentRadius = min(radius, min(currentRect.width(), currentRect.height()) * 0.5f)
                        canvas.drawRoundRect(currentRect, currentRadius, currentRadius, paint)
                    }
                } else {
                    val fallback = fallbackBitmap?.takeIf { !it.isRecycled }
                    if (fallback != null) {
                        val shader = fallbackShader ?: BitmapShader(
                            fallback,
                            Shader.TileMode.MIRROR,
                            Shader.TileMode.MIRROR
                        ).also { fallbackShader = it }
                        ensureFallbackMatrix(shader, fallback, width, height)
                        paint.shader = shader
                        paint.alpha = if (fadeMode == ArtworkFadeMode.FallbackReveal) {
                            ReferenceArtworkAlphaByte(fadeProgress)
                        } else {
                            255
                        }
                        val fallbackRadius = min(radius, min(fallbackRect.width(), fallbackRect.height()) * 0.5f)
                        canvas.drawRoundRect(fallbackRect, fallbackRadius, fallbackRadius, paint)
                    }
                }

                paint.shader = null
                paint.alpha = 255
            }
        }
        drawContent()
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        if (!isAttached || fadeDurationNs <= 0L) return
        if (fadeStartNs == 0L) fadeStartNs = frameTimeNanos
        val elapsed = (frameTimeNanos - fadeStartNs).coerceAtLeast(0L)
        fadeProgress = (elapsed.toDouble() / fadeDurationNs.toDouble()).toFloat().coerceIn(0f, 1f)
        invalidateDraw()
        if (fadeProgress >= 1f) {
            stopFade(releasePrevious = true)
        } else {
            postFrame()
        }
    }

    private fun rebind(next: VirtualListArtworkBinding, force: Boolean) {
        if (!force && next.key == binding.key) return
        boundGeneration += 1L
        detachRequest()
        stopFade(releasePrevious = true)
        releaseCurrent()
        terminalNoArt = false
        invalidateMatrices()
        fallbackBitmap = null
        fallbackShader = null

        if (next.key.isBlank()) {
            terminalNoArt = true
            refreshFallback(allowAnimation = !next.deferLoad && next.animateChanges)
            invalidateDraw()
            return
        }

        ensureBestCachedHandle(allowAnimation = false)
        if (!syncTerminalNoArtFromProvider(allowAnimation = false)) {
            markSynchronousMissChecked()
            ensureRequest()
        }
        invalidateDraw()
    }

    /** Attach the best provider-owned wrapper without starting source work. */
    private fun ensureBestCachedHandle(allowAnimation: Boolean) {
        val key = binding.key
        if (key.isBlank()) return

        val targetSide = binding.decodeSide.coerceAtLeast(1)
        val lookupStartedNs = if (TransitionPerfTrace.isActive()) System.nanoTime() else 0L
        val candidate = BitmapProvider.acquireBestThumbnail(
            key = key,
            targetWidth = targetSide,
            targetHeight = targetSide,
            surface = ArtworkSurface.List,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        )
        if (lookupStartedNs != 0L) {
            TransitionPerfTrace.recordDuration(
                TransitionPerfStage.BITMAP_SYNC_LOOKUP,
                System.nanoTime() - lookupStartedNs,
            )
        }
        if (candidate != null) acceptHandle(candidate, allowAnimation = allowAnimation)
    }


    private fun syncTerminalNoArtFromProvider(allowAnimation: Boolean): Boolean {
        // ArtworkSurface.List explicitly ignores provider-wide failure sentinels. Playback/Indexer
        // may have observed a transient source failure earlier, but that must never suppress a
        // visible list holder for five minutes. A VirtualList holder becomes terminal only when its
        // own completed source request reports terminalNoArt=true.
        @Suppress("UNUSED_VARIABLE")
        val ignored = allowAnimation
        return false
    }

    private fun restartRequestForTargetIfNeeded() {
        val active = request
        if (active != null && requestKey == binding.key && requestSide == binding.decodeSide) return
        detachRequest()
        ensureRequest()
    }

    private fun markSynchronousMissChecked() {
        synchronousMissGeneration = boundGeneration
        synchronousMissSide = binding.decodeSide
    }

    private fun ensureRequest() {
        if (!isAttached || binding.hidden || binding.deferLoad || binding.key.isBlank() || terminalNoArt) return
        val targetSide = binding.decodeSide.coerceAtLeast(1)
        if (isVirtualListProviderArtworkSatisfied(
                hasUsableBitmap = currentBitmap?.isRecycled == false,
                coversTarget = currentBitmap?.takeIf { !it.isRecycled }?.let { isAcceptable(it, targetSide) } == true,
                hasValidProviderHandle = currentHandle?.isValid == true,
            )) return
        request?.takeIf { it.isCancelled }?.let {
            request = null
            requestKey = ""
            requestSide = 0
            requestGeneration += 1L
        }
        if (request != null && requestKey == binding.key && requestSide == targetSide) return

        detachRequest()
        val generation = ++requestGeneration
        val bound = boundGeneration
        val key = binding.key
        requestKey = key
        requestSide = targetSide
        val synchronousMissConfirmed =
            synchronousMissGeneration == boundGeneration && synchronousMissSide == binding.decodeSide
        synchronousMissGeneration = Long.MIN_VALUE
        synchronousMissSide = 0

        var localRequest: BitmapRequest? = null
        var callbackDeliveredSynchronously = false
        TransitionPerfTrace.count(TransitionPerfEvent.ARTWORK_REQUEST)
        localRequest = BitmapProvider.loadViewportThumbnail(
            key = key,
            targetWidth = targetSide,
            targetHeight = targetSide,
            priority = binding.priority,
            externalArtworkPath = binding.externalArtworkPath,
            // rebind() already performed the one synchronous wrapper decision and no-art lookup.
            // Avoid repeating both under provider locks before queue admission.
            synchronousMissConfirmed = synchronousMissConfirmed,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        ) { loaded: ArtworkHandle? ->
            if (localRequest == null) callbackDeliveredSynchronously = true
            if (
                !isAttached ||
                generation != requestGeneration ||
                bound != boundGeneration ||
                key != binding.key
            ) {
                loaded?.release()
                return@loadViewportThumbnail
            }

            // The callback belongs to this generation now. Clear the active listener before
            // accepting so a low-quality callback can immediately schedule the requested target.
            if (request === localRequest || requestKey == key) {
                request = null
                requestKey = ""
                requestSide = 0
            }

            if (loaded != null && loaded.isValid) {
                terminalNoArt = false
                fallbackBitmap = null
                fallbackShader = null

                val allowFreshReveal = shouldAnimateVirtualListProviderAdmission(
                    callbackDeliveredSynchronously = callbackDeliveredSynchronously,
                    isAttached = isAttached,
                    deferLoad = binding.deferLoad,
                    animateChanges = binding.animateChanges,
                )
                acceptHandle(loaded, allowAnimation = allowFreshReveal)
            } else {
                loaded?.release()
                val currentUsable = currentBitmap?.isRecycled == false
                val terminal = shouldAcceptVirtualListTerminalNoArt(
                    callbackTerminalNoArt = localRequest?.terminalNoArt == true,
                    hasUsableBitmap = currentUsable,
                    hasValidProviderHandle = currentHandle?.isValid == true,
                )
                terminalNoArt = terminal
                if (terminal) {
                    stopFade(releasePrevious = true)
                    releaseCurrent()
                    refreshFallback(allowAnimation = binding.animateChanges && isAttached)
                }
                // A null/terminal result from a later target request never tears down an already
                // accepted provider wrapper. baseline implementation keeps the current A/B wrapper pair until a
                // valid replacement is installed or the ArtworkImageNode is genuinely rebound/detached.
                if (isAttached) invalidateDraw()
            }
        }
        if (!callbackDeliveredSynchronously) {
            request = localRequest
        }
    }


    private fun acceptHandle(next: ArtworkHandle, allowAnimation: Boolean) {
        acceptBitmap(
            bitmap = next.bitmap,
            handle = next,
            tier = next.tier,
            allowAnimation = allowAnimation,
        )
    }

    /** Bind one provider-owned wrapper to the retained artwork holder. */
    private fun acceptBitmap(
        bitmap: Bitmap,
        handle: ArtworkHandle,
        tier: ArtworkTier,
        allowAnimation: Boolean,
    ) {
        if (bitmap.isRecycled || !handle.isValid) {
            handle.release()
            return
        }

        terminalNoArt = false
        fallbackBitmap = null
        fallbackShader = null
        val current = currentBitmap?.takeIf { !it.isRecycled }
        if (current === bitmap) {
            // ArtworkImageNode.same-bitmap update helper: same physical bitmap is a visual no-op. Wrapper ownership is a
            // separate invariant: if the old lease is already closed, a newly acquired lease for the
            // same pixels must replace it rather than being released as a duplicate.
            val existingHandle = currentHandle
            if (existingHandle == null || !existingHandle.isValid) {
                existingHandle?.release()
                currentHandle = handle
                currentTier = tier
            } else {
                handle.release()
            }
            return
        }

        // If a same-holder replacement is interrupted, preserve the alpha that the current
        // wrapper actually had on the last presented frame before cancelling the old clock.
        val currentVisibleAlpha = if (
            fadeDurationNs > 0L &&
            (fadeMode == ArtworkFadeMode.Reveal || fadeMode == ArtworkFadeMode.Replacement)
        ) {
            fadeProgress.coerceIn(0f, 1f)
        } else {
            1f
        }
        stopFade(releasePrevious = true)

        val oldBitmap = currentBitmap?.takeIf { !it.isRecycled }
        val oldHandle = currentHandle
        val oldTier = currentTier
        val oldShader = currentShader

        // Loading placeholder -> first accepted real wrapper is the normal 200ms artwork reveal when
        // this holder is actually visible/stable. Real low -> high -> full upgrades are sharpness
        // upgrades, not a second dissolve. Same-tier pixel rewrites keep the local replacement fade.
        val admissionAnimation = if (allowAnimation) {
            resolveVirtualListArtworkAdmissionAnimation(
                hadRealBitmap = oldBitmap != null,
                sameTier = oldBitmap != null && oldTier == tier,
                deferLoad = binding.deferLoad,
                animateChanges = binding.animateChanges,
            )
        } else {
            VirtualListArtworkAdmissionAnimation.None
        }
        val shouldFade = admissionAnimation == VirtualListArtworkAdmissionAnimation.Replacement

        if (shouldFade) {
            previousBitmap = oldBitmap
            previousHandle = oldHandle
            previousShader = oldShader
            previousStartAlpha = currentVisibleAlpha
        } else {
            oldHandle?.release()
            previousBitmap = null
            previousHandle = null
            previousShader = null
            previousStartAlpha = 1f
        }

        currentBitmap = bitmap
        currentHandle = handle
        currentTier = tier
        currentShader = null
        invalidateMatrices()

        when (admissionAnimation) {
            VirtualListArtworkAdmissionAnimation.Replacement ->
                startFade(ArtworkFadeMode.Replacement, Reference_AA_REPLACEMENT_MS)
            VirtualListArtworkAdmissionAnimation.Reveal ->
                startFade(ArtworkFadeMode.Reveal, RawArtworkPolicy.LIST_FIRST_REVEAL_MS.toLong())
            VirtualListArtworkAdmissionAnimation.None -> {
                fadeMode = ArtworkFadeMode.None
                fadeProgress = 1f
            }
        }
        if (isAttached) invalidateDraw()
    }

    private fun startFade(mode: ArtworkFadeMode, durationMs: Long) {
        fadeMode = mode
        fadeStartNs = 0L
        fadeDurationNs = durationMs.coerceAtLeast(1L) * 1_000_000L
        fadeProgress = 0f
        postFrame()
    }

    private fun stopFade(releasePrevious: Boolean) {
        if (framePosted) {
            choreographer?.removeFrameCallback(this)
            framePosted = false
        }
        fadeStartNs = 0L
        fadeDurationNs = 0L
        fadeProgress = 1f
        fadeMode = ArtworkFadeMode.None
        previousStartAlpha = 1f
        if (releasePrevious) {
            previousHandle?.release()
            previousHandle = null
            previousBitmap = null
            previousShader = null
        }
    }

    private fun postFrame() {
        if (!isAttached || framePosted || fadeDurationNs <= 0L) return
        val scheduler = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        framePosted = true
        scheduler.postFrameCallback(this)
    }

    private fun detachRequest() {
        requestGeneration += 1L
        val active = request
        val cancelledKey = requestKey
        val cancelledSide = requestSide
        request = null
        requestKey = ""
        requestSide = 0
        if (active != null) {
            TransitionPerfTrace.count(TransitionPerfEvent.ARTWORK_CANCELLED)
            TransitionPerfTrace.mark(
                "artwork_cancel",
                "key=${Integer.toHexString(cancelledKey.hashCode())} side=$cancelledSide attached=$isAttached defer=${binding.deferLoad}",
            )
            BitmapProvider.cancel(active)
        }
    }

    private fun releaseCurrent() {
        currentHandle?.release()
        currentHandle = null
        currentBitmap = null
        currentTier = ArtworkTier.Any
        currentShader = null
    }

    private fun shaderFor(bitmap: Bitmap, previous: Boolean): BitmapShader? {
        if (bitmap.isRecycled) return null
        return BitmapShader(bitmap, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).also {
            if (previous) previousShader = it else currentShader = it
        }
    }

    private fun ensureMatrix(shader: BitmapShader, bitmap: Bitmap, width: Float, height: Float, previous: Boolean) {
        if (previous) {
            if (
                previousMatrixWidth == width && previousMatrixHeight == height &&
                previousMatrixBitmapWidth == bitmap.width && previousMatrixBitmapHeight == bitmap.height
            ) return
            configureFitCenter(previousMatrix, bitmap, width, height, previousRect)
            shader.setLocalMatrix(previousMatrix)
            previousMatrixWidth = width
            previousMatrixHeight = height
            previousMatrixBitmapWidth = bitmap.width
            previousMatrixBitmapHeight = bitmap.height
        } else {
            if (
                currentMatrixWidth == width && currentMatrixHeight == height &&
                currentMatrixBitmapWidth == bitmap.width && currentMatrixBitmapHeight == bitmap.height
            ) return
            configureFitCenter(currentMatrix, bitmap, width, height, currentRect)
            shader.setLocalMatrix(currentMatrix)
            currentMatrixWidth = width
            currentMatrixHeight = height
            currentMatrixBitmapWidth = bitmap.width
            currentMatrixBitmapHeight = bitmap.height
        }
    }

    private fun invalidateMatrices() {
        currentMatrixWidth = -1f
        currentMatrixHeight = -1f
        currentMatrixBitmapWidth = -1
        currentMatrixBitmapHeight = -1
        previousMatrixWidth = -1f
        previousMatrixHeight = -1f
        previousMatrixBitmapWidth = -1
        previousMatrixBitmapHeight = -1
        fallbackMatrixWidth = -1f
        fallbackMatrixHeight = -1f
        fallbackMatrixBitmapWidth = -1
        fallbackMatrixBitmapHeight = -1
    }

    private fun refreshFallback(allowAnimation: Boolean = false) {
        val previousFallback = fallbackBitmap?.takeIf { !it.isRecycled }
        val placeholder = resolveVirtualListArtworkPlaceholder(
            terminalNoArt = terminalNoArt || binding.key.isBlank(),
            defaultArtworkEnabled = binding.defaultArtworkEnabled,
        )
        val nextFallback = if (placeholder == VirtualListArtworkPlaceholder.TerminalDefaultArtwork) {
            VirtualListDefaultArtworkCache.get(binding.resources, binding.decodeSide)
        } else {
            null
        }
        fallbackBitmap = nextFallback
        fallbackShader = null
        fallbackMatrixWidth = -1f
        fallbackMatrixHeight = -1f
        fallbackMatrixBitmapWidth = -1
        fallbackMatrixBitmapHeight = -1

        if (
            currentBitmap == null &&
            nextFallback != null &&
            nextFallback !== previousFallback &&
            allowAnimation &&
            !binding.deferLoad &&
            binding.animateChanges
        ) {
            startFade(ArtworkFadeMode.FallbackReveal, RawArtworkPolicy.LIST_FIRST_REVEAL_MS.toLong())
        } else if (fadeMode == ArtworkFadeMode.FallbackReveal && nextFallback == null) {
            stopFade(releasePrevious = true)
        }
    }

    private fun ensureFallbackMatrix(shader: BitmapShader, bitmap: Bitmap, width: Float, height: Float) {
        if (
            fallbackMatrixWidth == width && fallbackMatrixHeight == height &&
            fallbackMatrixBitmapWidth == bitmap.width && fallbackMatrixBitmapHeight == bitmap.height
        ) return
        configureFitCenter(fallbackMatrix, bitmap, width, height, fallbackRect)
        shader.setLocalMatrix(fallbackMatrix)
        fallbackMatrixWidth = width
        fallbackMatrixHeight = height
        fallbackMatrixBitmapWidth = bitmap.width
        fallbackMatrixBitmapHeight = bitmap.height
    }
    private fun isAcceptable(bitmap: Bitmap, requestedSide: Int): Boolean {
        return !bitmap.isRecycled && maxOf(bitmap.width, bitmap.height) >= requestedSide.coerceAtLeast(1)
    }

    private fun configureFitCenter(
        matrix: Matrix,
        bitmap: Bitmap,
        width: Float,
        height: Float,
        outRect: RectF,
    ) {
        matrix.reset()
        outRect.set(0f, 0f, 0f, 0f)
        if (bitmap.width <= 0 || bitmap.height <= 0 || width <= 0f || height <= 0f) return
        val scale = min(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val dx = (width - drawnWidth) * 0.5f
        val dy = (height - drawnHeight) * 0.5f
        outRect.set(dx, dy, dx + drawnWidth, dy + drawnHeight)
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
    }
}


internal data class VirtualListArtworkBinding(
    val key: String,
    val externalArtworkPath: String,
    val decodeSide: Int,
    val priority: BitmapRequest.Priority,
    val deferLoad: Boolean,
    val hidden: Boolean,
    val cornerRadiusPx: Float,
    val animateChanges: Boolean,
    val defaultArtworkEnabled: Boolean,
    val resources: Resources,
)

private data class VirtualListArtworkElement(
    val binding: VirtualListArtworkBinding,
) : ModifierNodeElement<VirtualListArtworkNode>() {
    override fun create(): VirtualListArtworkNode = VirtualListArtworkNode(binding)
    override fun update(node: VirtualListArtworkNode) = node.update(binding)
    override fun InspectorInfo.inspectableProperties() {
        name = "virtualListArtwork"
        properties["key"] = binding.key
        properties["decodeSide"] = binding.decodeSide
    }
}

internal fun Modifier.virtualListArtwork(
    key: String,
    externalArtworkPath: String,
    decodeSide: Int,
    priority: BitmapRequest.Priority,
    deferLoad: Boolean,
    hidden: Boolean,
    cornerRadiusPx: Float,
    animateChanges: Boolean,
    defaultArtworkEnabled: Boolean,
    resources: Resources,
): Modifier = this then VirtualListArtworkElement(
    VirtualListArtworkBinding(
        key = key.trim(),
        externalArtworkPath = externalArtworkPath,
        decodeSide = decodeSide.coerceAtLeast(1),
        priority = priority,
        deferLoad = deferLoad,
        hidden = hidden,
        cornerRadiusPx = cornerRadiusPx,
        animateChanges = animateChanges,
        defaultArtworkEnabled = defaultArtworkEnabled,
        resources = resources,
    )
)

private object VirtualListDefaultArtworkCache {
    private val lock = Any()
    private val cache = LinkedHashMap<Int, Bitmap>()

    fun get(resources: Resources, requestedSide: Int): Bitmap? {
        val side = requestedSide.coerceIn(1, 1024)
        synchronized(lock) {
            cache[side]?.takeIf { !it.isRecycled }?.let { return it }
        }
        val decoded = decodeDefaultAlbumArtwork(resources, side) ?: return null
        synchronized(lock) {
            val existing = cache[side]?.takeIf { !it.isRecycled }
            if (existing != null) {
                if (decoded !== existing && !decoded.isRecycled) decoded.recycle()
                return existing
            }
            cache[side] = decoded
            return decoded
        }
    }
}
