package com.rawsmusic.core.ui.widget.bitmaps

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Project-style artwork identity registry.
 *
 * The UI must not accept an async bitmap just because a callback arrived.  The design keeps album-art
 * ownership behind artwork provider records; RawSMusic mirrors that with a lightweight token containing:
 * source-version key, exact bucket, per-source record revision, global artwork epoch and the visible
 * artworkRevision observed when the request was created.
 */
data class ArtworkRecord(
    val sourceVersionKey: String,
    val sourceRevision: Long,
    val globalRevision: Long
)

data class ArtworkAcceptToken(
    val sourceVersionKey: String,
    val cacheKey: String,
    val bucket: Int,
    val record: ArtworkRecord,
    val uiRevision: Long
) {
    /** Queue/in-flight key. Cache storage still uses cacheKey; decode sharing must include revision. */
    val flightKey: String = buildString {
        append(cacheKey)
        append('@')
        append(record.globalRevision)
        append(':')
        append(record.sourceRevision)
        append(':')
        append(uiRevision)
    }

    fun sameDecodeIdentity(other: ArtworkAcceptToken): Boolean {
        return sourceVersionKey == other.sourceVersionKey &&
            cacheKey == other.cacheKey &&
            bucket == other.bucket &&
            record.sourceRevision == other.record.sourceRevision &&
            record.globalRevision == other.record.globalRevision &&
            uiRevision == other.uiRevision
    }
}

internal object ArtworkRecordRegistry {
    private val globalRevision = AtomicLong(0L)
    private val sourceRevisions = ConcurrentHashMap<String, AtomicLong>()

    fun tokenFor(
        sourceVersionKey: String,
        cacheKey: String,
        bucket: Int,
        uiRevision: Long
    ): ArtworkAcceptToken {
        val revision = sourceRevisions[sourceVersionKey]?.get() ?: 0L
        val global = globalRevision.get()
        return ArtworkAcceptToken(
            sourceVersionKey = sourceVersionKey,
            cacheKey = cacheKey,
            bucket = bucket,
            record = ArtworkRecord(
                sourceVersionKey = sourceVersionKey,
                sourceRevision = revision,
                globalRevision = global
            ),
            uiRevision = uiRevision
        )
    }

    fun isCurrent(token: ArtworkAcceptToken, currentUiRevision: Long): Boolean {
        // currentUiRevision is intentionally not a hard rejection condition. artworkRevision is a
        // UI re-peek signal and may advance when an indexer prepares a better low-res thumbnail for
        // another row. Source/global record revisions are the correctness boundary; uiRevision still
        // participates in flightKey so requests created in different UI epochs do not share callbacks.
        @Suppress("UNUSED_VARIABLE")
        val observedUiRevision = currentUiRevision
        if (token.record.globalRevision != globalRevision.get()) return false
        val currentSourceRevision = sourceRevisions[token.sourceVersionKey]?.get() ?: 0L
        return token.record.sourceRevision == currentSourceRevision
    }

    fun canShareInFlight(owner: ArtworkAcceptToken?, waiter: ArtworkAcceptToken): Boolean {
        return owner?.sameDecodeIdentity(waiter) == true && isCurrent(owner, waiter.uiRevision)
    }

    fun invalidateSource(sourceVersionKey: String): Long {
        if (sourceVersionKey.isBlank()) return 0L
        return sourceRevisions
            .getOrPut(sourceVersionKey) { AtomicLong(0L) }
            .incrementAndGet()
    }

    fun invalidateSources(sourceVersionKeys: Collection<String>) {
        sourceVersionKeys.forEach { invalidateSource(it) }
    }

    fun invalidateAll(): Long = globalRevision.incrementAndGet()

    fun clear() {
        globalRevision.incrementAndGet()
        sourceRevisions.clear()
    }
}
