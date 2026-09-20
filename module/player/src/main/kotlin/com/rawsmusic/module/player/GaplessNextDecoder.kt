package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.transition.NativePcmSlotQueue
import com.rawsmusic.module.player.transition.NativeTrackSlotState
import com.rawsmusic.module.player.transition.NativeTransitionCapabilities

/**
 * Manages the pre-opened "next song" FFmpeg decoder used by gapless / crossfade transitions.
 *
 * Owns the decoder handle and the format snapshot (sample rate / channels / bits) so the
 * player loop does not scatter these across multiple volatile fields. The player still
 * decides when to open, transfer, and close; this class guarantees resource safety.
 */
internal class GaplessNextDecoder(
    private val tag: String,
    private val resolvePath: (String) -> String,
    private val isGenerationCurrent: (Int) -> Boolean,
    private val onPrepareStarted: (path: String, generation: Int) -> Unit = { _, _ -> },
    private val onPrepared: (prepared: Prepared, elapsedMs: Double) -> Unit = { _, _ -> },
    private val onPrepareFailed: (path: String, generation: Int, reason: String) -> Unit = { _, _, _ -> },
    private val onCleared: (prepared: Prepared, reason: String) -> Unit = { _, _ -> },
    private val gaplessAuditRegistry: DecoderGaplessAuditRegistry,
    private val isStrictBitPerfectPath: () -> Boolean = { false },
) {
    class Prepared(
        val path: String,
        val handle: Long,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val ownerGeneration: Int,
        val decoderSerial: Long,
        val minimumReadyFrames: Long,
        val readyFrames: Long,
        val eofDuringPrime: Boolean,
        val gaplessMetadata: EncodedGaplessMetadata?,
        private val primedPcm: PreparedPcmBuffer,
        private val producer: PendingDecoderPcmProducer,
        private val gaplessAuditRegistry: DecoderGaplessAuditRegistry,
    ) {
        val primedBytes: Int get() = primedPcm.availableBytes
        val primedFrames: Long get() = primedPcm.availableFrames
        val frameSize: Int get() = primedPcm.frameSize
        val nativePrimedQueue: Boolean get() = primedPcm.isNative
        val gaplessTrimTrusted: Boolean
            get() = gaplessAuditRegistry.isTrusted(gaplessMetadata?.codec)
        val gaplessTrustState: String
            get() = gaplessAuditRegistry.describe(gaplessMetadata?.codec)

        /**
         * Non-blocking renderer read. The pending decoder is continuously pumped
         * by [PendingDecoderPcmProducer]; this method never calls FFmpeg.
         */
        @Synchronized
        fun readPcm(destination: ByteArray, offset: Int, maxBytes: Int): Int {
            if (maxBytes <= 0) return 0
            val queued = primedPcm.read(destination, offset, maxBytes)
            if (queued > 0) return queued
            return when {
                producer.isEof -> -1
                producer.isFailed -> -2
                else -> 0
            }
        }

        internal fun requestProducerFreezeForHandoff() {
            producer.requestFreezeForHandoff()
        }

        internal fun freezeProducerForHandoff(timeoutMs: Long = 40L): Boolean =
            producer.freezeForHandoff(timeoutMs)

        internal fun resumeProducerAfterRejectedHandoff() {
            producer.resumeAfterRejectedHandoff()
        }

        /**
         * Completes the pending-worker transfer, then seeds the active RingBuffer.
         * No FFmpeg call can overlap the newly started active decoder loop.
         */
        @Synchronized
        fun drainPrimedTo(target: RingBuffer, writeChunk: ((ByteArray, Int) -> Int)? = null): Int =
            producer.completeHandoff(target, writeChunk)

        internal fun cancelProducer(reason: String) {
            producer.cancelAndClose(reason)
        }

        override fun toString(): String =
            "Prepared(path=$path, handle=0x${handle.toString(16)}, format=$sampleRate/$bitsPerSample/$channels, " +
                "generation=$ownerGeneration, readyFrames=$readyFrames, primedFrames=$primedFrames, eof=$eofDuringPrime)"
    }


    @Volatile
    private var prepared: Prepared? = null

    @get:Synchronized
    val handle: Long get() = prepared?.handle ?: 0L
    @get:Synchronized
    val sampleRate: Int get() = prepared?.sampleRate ?: 0
    @get:Synchronized
    val channels: Int get() = prepared?.channels ?: 0
    @get:Synchronized
    val bitsPerSample: Int get() = prepared?.bitsPerSample ?: 0
    @get:Synchronized
    val path: String? get() = prepared?.path
    @get:Synchronized
    val isPrepared: Boolean get() = prepared != null

    /** Returns the current prepared state without consuming it, or null if none. */
    @get:Synchronized
    val snapshot: Prepared? get() = prepared

    @Synchronized
    fun snapshotFor(ownerGeneration: Int): Prepared? =
        prepared?.takeIf { it.ownerGeneration == ownerGeneration }

    @Synchronized
    fun pathFor(ownerGeneration: Int): String? =
        snapshotFor(ownerGeneration)?.path

    @Synchronized
    fun isPreparedFor(ownerGeneration: Int): Boolean =
        snapshotFor(ownerGeneration) != null

    /** Atomically consumes and returns the prepared state, or null if none. */
    @Synchronized
    fun takePrepared(): Prepared? {
        val p = prepared
        prepared = null
        return p
    }

    @Synchronized
    fun prepare(
        path: String,
        wavSampleRate: Int,
        wavBitsPerSample: Int,
        wavChannels: Int,
        ownerGeneration: Int
    ): Boolean {
        if (!isGenerationCurrent(ownerGeneration)) {
            AppLogger.w(tag, "Gapless: skip prepare for stale generation path=$path gen=$ownerGeneration")
            return false
        }
        clear("prepare_next")
        onPrepareStarted(path, ownerGeneration)
        AppLogger.d(tag, "Gapless: prepareNextDecoder START path=$path gen=$ownerGeneration")
        val prepStart = System.nanoTime()
        return try {
            val resolvedPath = resolvePath(path)
            val openStart = System.nanoTime()
            var handle = 0L
            try {
                val strictUsbBitPerfect = isStrictBitPerfectPath()
                val playbackSpeed = if (strictUsbBitPerfect) {
                    1f
                } else {
                    AppPreferences.Player.playbackSpeed
                }
                val changePitch = AppPreferences.Player.playbackSpeedChangesPitch
                val remoteOptions = RemotePlaybackOptionsResolver.lookup(path, resolvedPath)
                handle = if (remoteOptions != null) {
                    PlaybackDecoderBridge.openDecoder(
                        path = resolvedPath,
                        targetSampleRate = wavSampleRate,
                        bitsPerSample = wavBitsPerSample,
                        channels = wavChannels,
                        headers = remoteOptions.headers,
                        userAgent = remoteOptions.userAgent,
                        playbackSpeed = playbackSpeed,
                        changePitch = changePitch,
                        strictUsbBitPerfect = strictUsbBitPerfect,
                    )
                } else {
                    PlaybackDecoderBridge.openDecoder(
                        path = resolvedPath,
                        targetSampleRate = wavSampleRate,
                        bitsPerSample = wavBitsPerSample,
                        channels = wavChannels,
                        playbackSpeed = playbackSpeed,
                        changePitch = changePitch,
                        strictUsbBitPerfect = strictUsbBitPerfect,
                    )
                }
            } catch (t: Throwable) {
                if (handle != 0L) {
                    try { PlaybackDecoderBridge.closeDecoder(handle) } catch (_: Throwable) {}
                }
                AppLogger.e(tag, "Gapless: openDecoder threw for $path", t)
                onPrepareFailed(path, ownerGeneration, "open_exception:${t.javaClass.simpleName}")
                return false
            }
            val openMs = (System.nanoTime() - openStart) / 1_000_000.0
            AppLogger.d(tag, "Gapless: prepareNextDecoder PlaybackDecoderBridge.openDecoder took ${"%.1f".format(openMs)}ms (handle=$handle)")
            if (handle == 0L) {
                AppLogger.w(tag, "Gapless: failed to open next decoder for $path")
                onPrepareFailed(path, ownerGeneration, "open_returned_zero")
                return false
            }
            var producerForCleanup: PendingDecoderPcmProducer? = null
            try {
                val sr = PlaybackDecoderBridge.getDecoderSampleRate(handle)
                val ch = PlaybackDecoderBridge.getDecoderChannels(handle)
                val bits = PlaybackDecoderBridge.getDecoderBitsPerSample(handle)
                if (!isGenerationCurrent(ownerGeneration)) {
                    try { PlaybackDecoderBridge.closeDecoder(handle) } catch (_: Throwable) {}
                    AppLogger.w(tag, "Gapless: prepared decoder discarded because generation is obsolete path=$path gen=$ownerGeneration")
                    onPrepareFailed(path, ownerGeneration, "obsolete_generation")
                    return false
                }
                val decoderBytesPerSample = when {
                    bits <= 1 -> 1
                    bits <= 16 -> 2
                    else -> 4
                }
                val frameSize = (ch * decoderBytesPerSample).coerceAtLeast(1)
                val gaplessMetadata = gaplessAuditRegistry.ensure(
                    decoderSerial = handle,
                    sourcePath = path,
                    outputSampleRate = sr,
                    outputFrameSize = frameSize,
                    alreadyResolvedPath = resolvedPath,
                )
                val maximumQueueBytes = if (NativeTransitionCapabilities.complete) {
                    NativePcmSlotQueue.maximumSlotBytes()
                } else {
                    LEGACY_PENDING_QUEUE_BYTES
                }
                val preload = PendingDecoderPreloadPolicy.plan(
                    sampleRate = sr,
                    frameSize = frameSize,
                    maximumQueueBytes = maximumQueueBytes,
                )

                val queueBind = NativeTrackSlotState.beginPending(
                    decoderSerial = handle,
                    generation = ownerGeneration,
                    sampleRate = sr,
                    channels = ch,
                    bitsPerSample = bits,
                    minimumReadyFrames = preload.minimumReadyFrames,
                    targetReadyFrames = preload.targetReadyFrames,
                )
                if (NativeTransitionCapabilities.complete && !queueBind.configured) {
                    val reason = "native_pending_bind_failed:${queueBind.detail}:${queueBind.statusCode}"
                    AppLogger.e(
                        tag,
                        "PENDING_QUEUE_BIND result=FAILED path=${path.substringAfterLast('/')} " +
                            "serial=$handle gen=$ownerGeneration format=$sr/$ch/$bits " +
                            "minimum=${preload.minimumReadyFrames} target=${preload.targetReadyFrames} " +
                            "detail=${queueBind.detail} slots=${NativeTrackSlotState.snapshot()} " +
                            "queues=${NativePcmSlotQueue.snapshot()}",
                    )
                    NativeTrackSlotState.retirePending(handle, ownerGeneration, REASON_PRIME_FAILED)
                    gaplessAuditRegistry.discard(handle, reason)
                    try { PlaybackDecoderBridge.closeDecoder(handle) } catch (_: Throwable) {}
                    onPrepareFailed(path, ownerGeneration, reason)
                    return false
                }
                val primeBuffer = PreparedPcmBuffer(
                    capacityBytes = preload.capacityBytes,
                    frameSize = frameSize,
                    decoderSerial = handle,
                    ownerGeneration = ownerGeneration,
                    nativeQueueConfigured = queueBind.configured,
                )
                AppLogger.i(
                    tag,
                    "PENDING_QUEUE_BIND result=${if (primeBuffer.isNative) "NATIVE" else "KOTLIN_COMPAT"} " +
                        "path=${path.substringAfterLast('/')} serial=$handle gen=$ownerGeneration " +
                        "status=${queueBind.statusCode} detail=${queueBind.detail} " +
                        "minimum=${preload.minimumReadyFrames} target=${preload.targetReadyFrames}",
                )
                val producer = PendingDecoderPcmProducer(
                    tag = tag,
                    handle = handle,
                    path = path,
                    generation = ownerGeneration,
                    frameSize = frameSize,
                    minimumReadyFrames = preload.minimumReadyFrames,
                    handoffReadyFrames = preload.handoffReadyFrames,
                    pcm = primeBuffer,
                    gaplessAuditRegistry = gaplessAuditRegistry,
                    isGenerationCurrent = isGenerationCurrent,
                )
                producerForCleanup = producer
                producer.start()
                val readiness = producer.awaitReady(PENDING_READY_TIMEOUT_MS)
                val readyFrames = readiness.readyFrames
                val eofDuringPrime = readiness.eof
                val queueReady = readiness.ready && readyFrames > 0L
                // The native track-slot state derives readiness from the native PCM queue.
                // When PreparedPcmBuffer had to fall back to its Kotlin fixed queue (stale or
                // partially upgraded native library), asking the native slot to validate bytes
                // it does not own always returns false and used to close a perfectly usable
                // prepared decoder. Keep the two authorities explicit.
                val slotReady = if (primeBuffer.isNative) {
                    queueReady && NativeTrackSlotState.updatePendingReady(
                        decoderSerial = handle,
                        generation = ownerGeneration,
                        readyFrames = readyFrames,
                        eofDuringPrime = eofDuringPrime,
                    )
                } else {
                    queueReady
                }
                if (!slotReady) {
                    NativeTrackSlotState.retirePending(handle, ownerGeneration, REASON_PRIME_FAILED)
                    gaplessAuditRegistry.discard(handle, "prime_failed")
                    producer.cancelAndClose(readiness.failureReason ?: "watermark_not_reached")
                    prepared = null
                    val reason = readiness.failureReason ?: when {
                        !queueReady -> "watermark_not_reached:$readyFrames/${preload.minimumReadyFrames} eof=$eofDuringPrime"
                        primeBuffer.isNative -> "native_slot_rejected_ready:$readyFrames/${preload.minimumReadyFrames}"
                        else -> "kotlin_queue_not_ready:$readyFrames/${preload.minimumReadyFrames}"
                    }
                    AppLogger.w(tag, "Gapless: pending decoder failed to reach READY path=$path reason=$reason")
                    onPrepareFailed(path, ownerGeneration, reason)
                    return false
                }

                val elapsedMs = (System.nanoTime() - prepStart) / 1_000_000.0
                val result = Prepared(
                    path = path,
                    handle = handle,
                    sampleRate = sr,
                    channels = ch,
                    bitsPerSample = bits,
                    ownerGeneration = ownerGeneration,
                    decoderSerial = handle,
                    minimumReadyFrames = preload.minimumReadyFrames,
                    readyFrames = readyFrames,
                    eofDuringPrime = eofDuringPrime,
                    gaplessMetadata = gaplessMetadata,
                    primedPcm = primeBuffer,
                    producer = producer,
                    gaplessAuditRegistry = gaplessAuditRegistry,
                )
                prepared = result
                onPrepared(result, elapsedMs)
                AppLogger.d(tag, "Gapless: next decoder READY: $path " +
                    "(sr=$sr, ch=$ch, bits=$bits, gen=$ownerGeneration, " +
                    "readyFrames=$readyFrames minFrames=${preload.minimumReadyFrames} targetFrames=${preload.targetReadyFrames} " +
                    "primedBytes=${result.primedBytes} " +
                    "nativeQueue=${result.nativePrimedQueue} eof=$eofDuringPrime " +
                    "gaplessCodec=${gaplessMetadata?.codec} trimTrust=${result.gaplessTrustState}) " +
                    "TOTAL=${"%.1f".format(elapsedMs)}ms")
                true
            } catch (t: Throwable) {
                // Read format failed — close the just-opened handle to avoid leak.
                NativeTrackSlotState.retirePending(handle, ownerGeneration, REASON_FORMAT_FAILED)
                gaplessAuditRegistry.discard(handle, "format_failed")
                if (producerForCleanup != null) {
                    producerForCleanup.cancelAndClose("format_read:${t.javaClass.simpleName}")
                } else {
                    try { PlaybackDecoderBridge.closeDecoder(handle) } catch (_: Throwable) {}
                }
                prepared = null
                AppLogger.e(tag, "Gapless: prepareNextDecoder format read failed for $path", t)
                onPrepareFailed(path, ownerGeneration, "format_read:${t.javaClass.simpleName}")
                false
            }
        } catch (t: Throwable) {
            AppLogger.e(tag, "Gapless: prepareNextDecoder failed", t)
            onPrepareFailed(path, ownerGeneration, "prepare_exception:${t.javaClass.simpleName}")
            false
        }
    }

    /**
     * Returns the prepared state only when both path and playback generation match.
     * Old streaming loops can race with a new play() request; generation ownership
     * prevents an obsolete loop from consuming or closing the new session decoder.
     */
    @Synchronized
    fun consumeIfPathMatches(
        expectedPath: String,
        ownerGeneration: Int,
        expectedDecoderSerial: Long? = null,
    ): Prepared? {
        val existing = prepared ?: return null
        if (expectedDecoderSerial != null && existing.decoderSerial != expectedDecoderSerial) {
            AppLogger.w(
                tag,
                "Gapless: refusing decoder serial mismatch prepared=${existing.decoderSerial} " +
                    "expected=$expectedDecoderSerial path=${existing.path}",
            )
            return null
        }
        if (existing.ownerGeneration != ownerGeneration) {
            if (existing.ownerGeneration < ownerGeneration) {
                clear("stale_generation_${existing.ownerGeneration}_expected_$ownerGeneration")
            } else {
                AppLogger.w(
                    tag,
                    "Gapless: refusing to consume decoder from newer generation " +
                        "preparedGen=${existing.ownerGeneration} expectedGen=$ownerGeneration path=${existing.path}"
                )
            }
            return null
        }
        if (existing.path != expectedPath) {
            clear("next_path_changed")
            return null
        }
        prepared = null
        return existing
    }

    /**
     * Atomically validates, obtains native renderer ownership, and only then removes
     * the pending decoder from this manager. A rejected block-boundary commit leaves
     * the prepared decoder intact for cancellation or a later safe retry.
     */
    @Synchronized
    fun consumeIfPathMatchesAfterApproval(
        expectedPath: String,
        ownerGeneration: Int,
        expectedDecoderSerial: Long? = null,
        approve: (Prepared) -> Boolean,
    ): Prepared? {
        val existing = prepared ?: return null
        if (expectedDecoderSerial != null && existing.decoderSerial != expectedDecoderSerial) {
            AppLogger.w(
                tag,
                "Gapless: refusing approved-consume serial mismatch prepared=${existing.decoderSerial} " +
                    "expected=$expectedDecoderSerial path=${existing.path}",
            )
            return null
        }
        if (existing.ownerGeneration != ownerGeneration || existing.path != expectedPath) return null
        if (!existing.freezeProducerForHandoff()) {
            AppLogger.w(
                tag,
                "Gapless: pending producer did not reach a safe handoff boundary " +
                    "serial=${existing.decoderSerial} path=${existing.path}",
            )
            return null
        }
        if (!approve(existing)) {
            existing.resumeProducerAfterRejectedHandoff()
            return null
        }
        prepared = null
        return existing
    }

    /** Clears the prepared state, closing the decoder handle if one is held. */
    @Synchronized
    fun clear(reason: String) {
        val existing = prepared
        prepared = null
        if (existing != null && existing.handle != 0L) {
            NativeTrackSlotState.retirePending(existing.decoderSerial, existing.ownerGeneration, reason.hashCode())
            gaplessAuditRegistry.discard(existing.decoderSerial, reason)
            existing.cancelProducer(reason)
            onCleared(existing, reason)
            AppLogger.d(tag, "Gapless: cleared next decoder (reason=$reason, path=${existing.path})")
        }
    }
    private companion object {
        const val PENDING_READY_TIMEOUT_MS = 8_000L
        const val LEGACY_PENDING_QUEUE_BYTES = 256 * 1024
        const val REASON_PRIME_FAILED = 1
        const val REASON_FORMAT_FAILED = 2
    }

}
