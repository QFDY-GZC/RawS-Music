package com.rawsmusic.module.player

/** Compatibility fallback used by JVM tests and APKs with an older native library. */
internal class KotlinAutomaticCrossfadeProcessor : AutomaticCrossfadeProcessor {
    override val isNativeBacked: Boolean = false

    private var active = false
    private var sampleRate = 0
    private var localTotalFrames = 0L
    private var gentleFrames = 0L
    private var gentleIncomingGain = AutomaticCrossfadeEnvelope.GENTLE_GAIN
    private var localProcessedFrames = 0L
    private var consumedFrames = 0L
    private var startOutgoingGain = 1f
    private var startIncomingGain = 0f
    private var currentOutgoingGain = 1f
    override val outgoingGain: Float get() = currentOutgoingGain
    override val incomingGain: Float get() = currentIncomingGain
    private var currentIncomingGain = 0f
    private var pivotFraction = AutoTransitionPolicy.SMART_PIVOT_PERCENT / 100f
    private var basePivotFraction = pivotFraction
    private var levelsInitialized = false
    private var smoothedOutgoingDb = -18f
    private var smoothedIncomingDb = -18f
    private var entryInitialized = false
    private var plan: AutoTransitionStartPlan? = null
    private var retimedSinceLastProcess = false

    override fun start(plan: AutoTransitionStartPlan, sampleRate: Int): Boolean {
        if (sampleRate <= 0 || plan.handoverMs <= 0) return false
        this.plan = plan
        this.sampleRate = sampleRate
        localTotalFrames = ((plan.preRollMs + plan.handoverMs).toLong() * sampleRate / 1000L)
            .coerceAtLeast(1L)
        localProcessedFrames = 0L
        gentleFrames = plan.preRollMs.toLong() * sampleRate / 1000L
        gentleIncomingGain = AutomaticCrossfadeEnvelope.GENTLE_GAIN
        consumedFrames = 0L
        startOutgoingGain = 1f
        startIncomingGain = 0f
        currentOutgoingGain = 1f
        currentIncomingGain = 0f
        pivotFraction = (plan.pivotMs.toFloat() / plan.handoverMs.toFloat()).coerceIn(0.35f, 0.75f)
        basePivotFraction = pivotFraction
        levelsInitialized = false
        entryInitialized = false
        retimedSinceLastProcess = false
        active = true
        return true
    }

    override fun retime(durationMs: Int): Boolean {
        if (!active || durationMs <= 0 || sampleRate <= 0) return false
        startOutgoingGain = currentOutgoingGain
        gentleFrames = 0L
        startIncomingGain = currentIncomingGain
        localProcessedFrames = 0L
        localTotalFrames = (durationMs.toLong() * sampleRate / 1000L).coerceAtLeast(1L)
        entryInitialized = true
        retimedSinceLastProcess = true
        return true
    }

    override fun reset(reason: String) {
        active = false
        localTotalFrames = 0L
        gentleFrames = 0L
        localProcessedFrames = 0L
        consumedFrames = 0L
        startOutgoingGain = 1f
        startIncomingGain = 0f
        currentOutgoingGain = 1f
        currentIncomingGain = 0f
        pivotFraction = AutoTransitionPolicy.SMART_PIVOT_PERCENT / 100f
        entryInitialized = false
        retimedSinceLastProcess = false
        plan = null
        levelsInitialized = false
    }

    override fun mixInPlace(
        currentBuffer: ByteArray,
        pendingBuffer: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
    ): AutomaticCrossfadeProcessor.Result {
        if (!active || frameSize <= 0 || length <= 0) {
            return AutomaticCrossfadeProcessor.Result(consumedFrames, false, active, false)
        }
        val aligned = PcmFrameAligner.alignDown(length, frameSize)
        if (aligned <= 0) return AutomaticCrossfadeProcessor.Result(consumedFrames, false, active, false)
        if (plan == null) return AutomaticCrossfadeProcessor.Result(consumedFrames, false, false, false)
        run {
            val outgoingDb = PcmLevelMeter.rmsDb(
                currentBuffer,
                aligned,
                outputIsFloat,
                outputIsPacked24,
                bitsPerSample,
                startOffset = offset,
            )
            val incomingDb = PcmLevelMeter.rmsDb(
                pendingBuffer,
                aligned,
                outputIsFloat,
                outputIsPacked24,
                bitsPerSample,
                startOffset = offset,
            )
            val seconds = aligned.toFloat() / frameSize / sampleRate.coerceAtLeast(1)
            if (gentleFrames > 0L && !entryInitialized) {
                gentleIncomingGain = AutomaticCrossfadeEnvelope.gentleEntryGain(outgoingDb, incomingDb)
            }
            // Do not freeze the whole transition around an intro's initial silence.
            if (outgoingDb > -60f && incomingDb > -60f) {
                val alpha = seconds / (0.5f + seconds)
                if (!levelsInitialized) {
                    smoothedOutgoingDb = outgoingDb
                    smoothedIncomingDb = incomingDb
                    levelsInitialized = true
                } else {
                    smoothedOutgoingDb += alpha * (outgoingDb - smoothedOutgoingDb)
                    smoothedIncomingDb += alpha * (incomingDb - smoothedIncomingDb)
                }
                val loudTailDelay = ((smoothedOutgoingDb + 17f) / 17f).coerceIn(0f, 1f) * 0.05f
                val delta = (smoothedIncomingDb - smoothedOutgoingDb).coerceIn(-12f, 12f)
                val desired = (basePivotFraction + delta / 80f + loudTailDelay).coerceIn(0.35f, 0.75f)
                val maximumStep = seconds * 0.1f
                pivotFraction += (desired - pivotFraction).coerceIn(-maximumStep, maximumStep)
            }
            entryInitialized = true
        }

        val frameCount = aligned / frameSize
        val config = AutomaticCrossfadeEnvelope.Config(
            totalFrames = localTotalFrames,
            sampleRate = sampleRate,
            pivotFrames = (localTotalFrames * pivotFraction).toLong(),
            gentleFrames = gentleFrames,
            gentleIncomingGain = gentleIncomingGain,
        )
        val start = currentOutgoingGain to currentIncomingGain
        val desiredEnd = AutomaticCrossfadeEnvelope.gains(
            (localProcessedFrames + frameCount).coerceAtMost(localTotalFrames),
            config,
            startOutgoingGain,
            startIncomingGain,
        )
        // A newly measured pivot cannot rewind either ramp or step at a block boundary.
        val end = minOf(currentOutgoingGain, desiredEnd.first) to maxOf(currentIncomingGain, desiredEnd.second)
        PcmCrossfadeMixer.mixInPlace(
            currentBuf = currentBuffer,
            currentLen = offset + aligned,
            nextBuf = pendingBuffer,
            nextLen = offset + aligned,
            gainOut = start.first,
            gainIn = start.second,
            gainOutEnd = end.first,
            gainInEnd = end.second,
            frameSize = frameSize,
            outputIsFloat = outputIsFloat,
            bitsPerSample = bitsPerSample,
            outputIsPacked24 = outputIsPacked24,
            currentOffset = offset,
            nextOffset = offset,
        )
        localProcessedFrames += frameCount
        consumedFrames += frameCount
        currentOutgoingGain = end.first
        currentIncomingGain = end.second
        val completed = localProcessedFrames >= localTotalFrames
        if (completed) active = false
        val retimed = retimedSinceLastProcess
        retimedSinceLastProcess = false
        return AutomaticCrossfadeProcessor.Result(consumedFrames, completed, active, retimed)
    }

    override fun close() = Unit
}
