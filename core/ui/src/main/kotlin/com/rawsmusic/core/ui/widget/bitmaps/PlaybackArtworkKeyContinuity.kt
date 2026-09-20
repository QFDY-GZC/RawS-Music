package com.rawsmusic.core.ui.widget.bitmaps

import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived proof that a file-version artwork key changed without changing the artwork bytes.
 *
 * Metadata-only tag rewrites replace the source file and therefore legitimately change
 * fileSize/dateModified. Playback artwork keys include that version stamp so an ordinary external
 * file mutation still invalidates embedded art. The metadata editor is one of the few callers that
 * can prove the mutation touched text tags only, so it publishes an exact old-key -> new-key lease
 * here before repository/current-song state is emitted.
 *
 * The lease is deliberately multi-consumer: portrait/landscape/player/lyrics transition owners may
 * observe the same committed song independently. It is also exact-pair and short-lived, so a later
 * cover mutation gets a different key and cannot inherit the continuity decision.
 */
object PlaybackArtworkKeyContinuity {
    private const val DEFAULT_LEASE_MS = 15_000L
    private const val MAX_ENTRIES = 32

    enum class RewriteKind {
        MetadataOnly,
        ArtworkChanged,
    }

    private data class Lease(
        val previousKey: String,
        val expiresAtMs: Long,
        val kind: RewriteKind,
    )

    private val byCommittedKey = ConcurrentHashMap<String, Lease>()

    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    fun markMetadataOnlyRewrite(previousKey: String?, committedKey: String?) {
        val previous = previousKey?.takeIf { it.isNotBlank() } ?: return
        val committed = committedKey?.takeIf { it.isNotBlank() } ?: return
        if (previous == committed) return
        val now = nowMs()
        pruneExpired(now)
        byCommittedKey[committed] = Lease(
            previousKey = previous,
            expiresAtMs = now + DEFAULT_LEASE_MS,
            kind = RewriteKind.MetadataOnly,
        )
        trimToBound()
    }

    /**
     * The file version changed because artwork bytes were intentionally rewritten.
     *
     * This is not a cache-preservation proof: the new key must still be decoded. It only proves
     * that the currently attached player holder belongs to the same logical song and should stay
     * mounted while the replacement wrapper is prepared, just like Reference keeps ArtworkItemNode and
     * lets ArtworkImageNode swap its wrapper locally.
     */
    fun markArtworkRewrite(previousKey: String?, committedKey: String?) {
        val previous = previousKey?.takeIf { it.isNotBlank() } ?: return
        val committed = committedKey?.takeIf { it.isNotBlank() } ?: return
        if (previous == committed) return
        val now = nowMs()
        pruneExpired(now)
        byCommittedKey[committed] = Lease(
            previousKey = previous,
            expiresAtMs = now + DEFAULT_LEASE_MS,
            kind = RewriteKind.ArtworkChanged,
        )
        trimToBound()
    }

    fun rewriteKind(previousKey: String?, committedKey: String?): RewriteKind? {
        val previous = previousKey?.takeIf { it.isNotBlank() } ?: return null
        val committed = committedKey?.takeIf { it.isNotBlank() } ?: return null
        if (previous == committed) return RewriteKind.MetadataOnly
        val now = nowMs()
        val lease = byCommittedKey[committed] ?: return null
        if (lease.expiresAtMs < now) {
            byCommittedKey.remove(committed, lease)
            return null
        }
        return lease.kind.takeIf { lease.previousKey == previous }
    }

    fun preservesVisual(previousKey: String?, committedKey: String?): Boolean {
        val previous = previousKey?.takeIf { it.isNotBlank() } ?: return false
        val committed = committedKey?.takeIf { it.isNotBlank() } ?: return false
        if (previous == committed) return true
        return rewriteKind(previous, committed) == RewriteKind.MetadataOnly
    }

    private fun pruneExpired(now: Long) {
        byCommittedKey.entries.forEach { entry ->
            if (entry.value.expiresAtMs < now) {
                byCommittedKey.remove(entry.key, entry.value)
            }
        }
    }

    private fun trimToBound() {
        while (byCommittedKey.size > MAX_ENTRIES) {
            val oldest = byCommittedKey.entries.minByOrNull { it.value.expiresAtMs } ?: return
            byCommittedKey.remove(oldest.key, oldest.value)
        }
    }

    internal fun clearForTests() {
        byCommittedKey.clear()
    }
}
