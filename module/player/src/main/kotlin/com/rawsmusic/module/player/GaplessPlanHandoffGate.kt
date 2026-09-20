package com.rawsmusic.module.player

/**
 * Serializes queue-plan publication around a renderer-owned native gapless boundary.
 *
 * The pending track can become audible before [FfmpegGaplessTrackSwitchCoordinator] has consumed
 * the prepared decoder and committed Kotlin/native ownership. PlayerController deliberately
 * publishes that early TRACK_STARTED event so UI/queue state can advance without waiting for the
 * bookkeeping commit. A resulting next-after-current plan must therefore wait: preparing it in the
 * same window would clear/replace the decoder that is already audible but still owns the pending
 * slot identity.
 */
internal class GaplessPlanHandoffGate {
    data class DeferredPlan(
        val nextPath: String?,
        val automaticCrossfadeEnabled: Boolean,
        val reason: String,
    )

    private data class Active(
        val path: String,
        val decoderSerial: Long,
        val generation: Int,
    )

    private var active: Active? = null
    private var deferred: DeferredPlan? = null

    @Synchronized
    fun armRenderedPending(
        path: String,
        decoderSerial: Long,
        generation: Int,
    ) {
        if (path.isBlank() || decoderSerial <= 0L || generation < 0) return
        val previous = active
        if (previous != null &&
            previous.path == path &&
            previous.decoderSerial == decoderSerial &&
            previous.generation == generation
        ) {
            return
        }
        active = Active(path, decoderSerial, generation)
        deferred = null
    }

    /**
     * Returns true when this plan was captured behind the active renderer handoff.
     * The caller must not mutate nextSongPath/prepare epoch when true.
     */
    @Synchronized
    fun deferPlanIfCommitting(
        generation: Int,
        nextPath: String?,
        automaticCrossfadeEnabled: Boolean,
        reason: String,
    ): Boolean {
        val current = active ?: return false
        if (current.generation != generation) return false
        deferred = DeferredPlan(nextPath, automaticCrossfadeEnabled, reason)
        return true
    }

    @Synchronized
    fun commit(
        path: String?,
        generation: Int,
        expectedDecoderSerial: Long?,
    ): DeferredPlan? {
        val current = active ?: return null
        if (current.generation != generation) return null
        if (path != null && current.path != path) return null
        if (expectedDecoderSerial != null && current.decoderSerial != expectedDecoderSerial) return null
        active = null
        return deferred.also { deferred = null }
    }

    @Synchronized
    fun abort(
        generation: Int,
        expectedDecoderSerial: Long?,
    ) {
        val current = active ?: return
        if (current.generation != generation) return
        if (expectedDecoderSerial != null && current.decoderSerial != expectedDecoderSerial) return
        active = null
        deferred = null
    }

    @Synchronized
    fun snapshot(): String {
        val current = active
        return if (current == null) {
            "idle"
        } else {
            "committing=${current.decoderSerial}/${current.generation}:${current.path.substringAfterLast('/')} " +
                "deferred=${deferred?.nextPath?.substringAfterLast('/') ?: "<none>"}"
        }
    }
}
