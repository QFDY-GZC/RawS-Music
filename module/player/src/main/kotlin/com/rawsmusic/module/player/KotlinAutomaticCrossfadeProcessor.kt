package com.rawsmusic.module.player

/** Compatibility fallback used by JVM tests and APKs with an older native library. */
internal class KotlinAutomaticCrossfadeProcessor : AutomaticCrossfadeProcessor {
    override val isNativeBacked: Boolean = false

    private var active = false
    private var sampleRate = 0
    private var localTotalFrames = 0L
    private var localProcessedFrames = 0L
    private var consumedFrames = 0L
    private var startOutgoingGain = 1f
    private var startIncomingGain = 0f
    private var currentOutgoingGain = 1f
    private var currentIncomingGain = 0f
    private var pivotFraction = AutoTransitionPolicy.SMART_PIVOT_PERCENT / 100f
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
        consumedFrames = 0L
        startOutgoingGain = 1f
        startIncomingGain = 0f
        currentOutgoingGain = 1f
        currentIncomingGain = 0f
        pivotFraction = (plan.pivotMs.toFloat() / plan.handoverMs.toFloat()).coerceIn(0.35f, 0.75f)
        entryInitialized = false
        retimedSinceLastProcess = false
        active = true
        return true
    }

    override fun retime(durationMs: Int): Boolean {
        if (!active || durationMs <= 0 || sampleRate <= 0) return false
        startOutgoingGain = currentOutgoingGain
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
        if (!entryInitialized) {
            val outgoingDb = PcmLevelMeter.rmsDb(
                currentBuffer.copyOfRange(offset, offset + aligned),
                aligned,
                outputIsFloat,
                outputIsPacked24,
                bitsPerSample,
            )
            val incomingDb = PcmLevelMeter.rmsDb(
                pendingBuffer.copyOfRange(offset, offset + aligned),
                aligned,
                outputIsFloat,
                outputIsPacked24,
                bitsPerSample,
            )
            pivotFraction = AutomaticCrossfadeEnvelope.loudnessAdaptivePivotFraction(
                basePivotFraction = pivotFraction,
                outgoingDb = outgoingDb,
                incomingDb = incomingDb,
            )
            // Transition volume ramps begin at 1/0. Track normalization remains in the
            // existing ReplayGain path and must not be duplicated inside this envelope.
            startIncomingGain = 0f
            currentIncomingGain = 0f
            entryInitialized = true
        }

        val frameCount = aligned / frameSize
        val config = AutomaticCrossfadeEnvelope.Config(
            totalFrames = localTotalFrames,
            sampleRate = sampleRate,
            pivotFrames = (localTotalFrames * pivotFraction).toLong(),
        )
        val firstAudibleBlock = consumedFrames == 0L
        val resolvedStart = AutomaticCrossfadeEnvelope.gains(
            localProcessedFrames,
            config,
            startOutgoingGain,
            startIncomingGain,
        )
        val start = if (firstAudibleBlock) resolvedStart.first to 0f else resolvedStart
        val end = AutomaticCrossfadeEnvelope.gains(
            (localProcessedFrames + frameCount).coerceAtMost(localTotalFrames),
            config,
            startOutgoingGain,
            startIncomingGain,
        )
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
