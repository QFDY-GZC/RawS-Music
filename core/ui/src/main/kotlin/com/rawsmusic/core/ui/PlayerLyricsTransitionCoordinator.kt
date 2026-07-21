package com.rawsmusic.core.ui.widget

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Immutable player <-> lyrics transition session.
 *
 * Resolve source/target layouts and the shared-artwork candidate before the transition
 * starts.  Once a session exists, later layout/list callbacks are candidates for the next session
 * only and can never change the running route.
 */
class PlayerLyricsTransitionCoordinator {

    var activeSession by mutableStateOf<PlayerLyricsTransitionSession?>(null)
        private set

    var progress by mutableFloatStateOf(0f)
        private set

    private var playerCandidate: PlayerLyricsArtworkAnchor? = null
    private var lyricCandidate: PlayerLyricsArtworkAnchor? = null
    private var generation = 0L

    fun updatePlayerCandidate(anchor: PlayerLyricsArtworkAnchor?) {
        playerCandidate = anchor?.normalized()
    }

    fun updateLyricCandidate(anchor: PlayerLyricsArtworkAnchor?) {
        lyricCandidate = anchor?.normalized()
    }

    fun currentPlayerCandidate(): PlayerLyricsArtworkAnchor? = playerCandidate

    fun currentLyricCandidate(): PlayerLyricsArtworkAnchor? = lyricCandidate

    fun begin(
        from: PlayerSceneController.Scene,
        to: PlayerSceneController.Scene,
        interactive: Boolean
    ): PlayerLyricsTransitionSession? {
        if (!isPlayerLyricsPair(from, to)) {
            activeSession = null
            progress = 0f
            return null
        }

        val player = playerCandidate
            ?.takeIf { it.source == PlayerLyricsArtworkSource.Player }
            ?.takeIf { it.rect?.isUsable == true }
            ?.takeIf { it.stable }
        val lyric = lyricCandidate
        val artworkKey = player?.artworkKey?.takeIf { it.isNotBlank() }
            ?: lyric?.artworkKey?.takeIf { it.isNotBlank() }

        val sameArtwork = artworkKey != null &&
            player?.artworkKey == artworkKey &&
            lyric?.artworkKey == artworkKey
        val visibleLyricAnchor = lyric
            ?.takeIf { sameArtwork }
            ?.takeIf { it.isEligibleSharedArtworkCandidate() }

        val playerRect = player?.rect
        val lyricRect = visibleLyricAnchor?.rect
        val alignment = if (playerRect != null && lyricRect != null) {
            PlayerLyricsArtworkAlignment.between(playerRect, lyricRect)
        } else {
            null
        }
        val route = if (alignment != null && !artworkKey.isNullOrBlank()) {
            PlayerLyricsTransitionRoute.VisibleArtworkAligned
        } else {
            PlayerLyricsTransitionRoute.OffscreenSceneZoom
        }

        val next = PlayerLyricsTransitionSession(
            id = ++generation,
            from = from,
            to = to,
            direction = if (from == PlayerSceneController.Scene.PLAYER) {
                PlayerLyricsTransitionDirection.PlayerToLyrics
            } else {
                PlayerLyricsTransitionDirection.LyricsToPlayer
            },
            route = route,
            artworkKey = artworkKey,
            playerArtworkRect = playerRect,
            lyricArtworkRect = lyricRect,
            artworkAlignment = alignment,
            lyricVisibility = lyric?.visibility ?: PlayerLyricsArtworkVisibility.Missing,
            lyricNormalizedPosition = lyric?.normalizedViewportPosition,
            offscreenDistancePx = lyric?.offscreenDistancePx?.coerceAtLeast(0f) ?: 0f,
            scrollDirection = lyric?.scrollDirection ?: PlayerLyricsScrollDirection.Still,
            playerRadiusDp = player?.cornerRadiusDp ?: PLAYER_ARTWORK_RADIUS_DP,
            lyricRadiusDp = visibleLyricAnchor?.cornerRadiusDp ?: LYRIC_ARTWORK_RADIUS_DP,
            lyricSnapshotGeneration = lyric?.snapshotGeneration ?: 0L,
            interactive = interactive
        )
        activeSession = next
        progress = 0f
        Log.d(
            TAG,
            "begin id=${next.id} ${next.from}->${next.to} route=${next.route} " +
                "visibility=${next.lyricVisibility} normalized=${next.lyricNormalizedPosition} " +
                "scrolling=${lyric?.isScrollInProgress} stable=${lyric?.stable} " +
                "snapshot=${next.lyricSnapshotGeneration} sameArtwork=$sameArtwork " +
                "playerRect=${next.playerArtworkRect} lyricRect=${next.lyricArtworkRect}"
        )
        return next
    }

    fun updateProgress(value: Float) {
        if (activeSession == null) return
        progress = value.coerceIn(0f, 1f)
    }

    fun finish() {
        activeSession = null
        progress = 0f
    }

    fun cancel() {
        activeSession = null
        progress = 0f
    }

    companion object {
        private const val TAG = "PlayerLyricsTransition"

        const val VELOCITY_THRESHOLD_DP_PER_SECOND = 500f
        const val COMMIT_PROGRESS = 0.30f
        const val CANCEL_VELOCITY_FORWARD_LIMIT = 0.20f
        const val COMMIT_VELOCITY_FORWARD_LIMIT = 0.80f

        // Accept candidates only while -0.967 < normalizedPosition < 0.967.
        const val SHARED_ARTWORK_POSITION_LIMIT = 0.967f
        const val SHARED_ARTWORK_POSITION_DEAD_ZONE = 0.033f

        const val PLAYER_ARTWORK_RADIUS_DP = 28f
        const val LYRIC_ARTWORK_RADIUS_DP = 24f

        fun isPlayerLyricsPair(
            from: PlayerSceneController.Scene,
            to: PlayerSceneController.Scene
        ): Boolean {
            return (from == PlayerSceneController.Scene.PLAYER &&
                to == PlayerSceneController.Scene.LYRIC) ||
                (from == PlayerSceneController.Scene.LYRIC &&
                    to == PlayerSceneController.Scene.PLAYER)
        }

        /**
         * Commit by signed direction velocity first, then by strict progress greater than 0.30.
         */
        fun shouldCommit(
            progress: Float,
            velocityPxPerSecond: Float,
            density: Float,
            expectedVelocitySign: Int
        ): Boolean {
            val safeDensity = density.coerceAtLeast(0.1f)
            val velocityDp = velocityPxPerSecond / safeDensity
            val signedVelocity = velocityDp * expectedVelocitySign.coerceIn(-1, 1)
            return when {
                signedVelocity >= VELOCITY_THRESHOLD_DP_PER_SECOND -> true
                signedVelocity <= -VELOCITY_THRESHOLD_DP_PER_SECOND -> false
                else -> progress.coerceIn(0f, 1f) > COMMIT_PROGRESS
            }
        }

        /**
         * Forward release velocity only near the cancelled source or committed target.
         * The result is normalized progress/second for PlayerSceneController.
         */
        fun settleRatioVelocity(
            progress: Float,
            commit: Boolean,
            velocityPxPerSecond: Float,
            travelDistancePx: Float,
            expectedVelocitySign: Int
        ): Float {
            val p = progress.coerceIn(0f, 1f)
            val shouldForward = if (commit) {
                p > COMMIT_VELOCITY_FORWARD_LIMIT
            } else {
                p < CANCEL_VELOCITY_FORWARD_LIMIT
            }
            if (!shouldForward || travelDistancePx <= 1f) return 0f
            val signed = velocityPxPerSecond * expectedVelocitySign.coerceIn(-1, 1)
            return (signed / travelDistancePx).coerceIn(-12f, 12f)
        }

        fun absoluteLyricsFraction(
            session: PlayerLyricsTransitionSession,
            transitionProgress: Float
        ): Float {
            val p = transitionProgress.coerceIn(0f, 1f)
            return if (session.direction == PlayerLyricsTransitionDirection.PlayerToLyrics) {
                p
            } else {
                1f - p
            }
        }
    }
}

data class PlayerLyricsTransitionSession(
    val id: Long,
    val from: PlayerSceneController.Scene,
    val to: PlayerSceneController.Scene,
    val direction: PlayerLyricsTransitionDirection,
    val route: PlayerLyricsTransitionRoute,
    val artworkKey: String?,
    val playerArtworkRect: PlayerLyricsRect?,
    val lyricArtworkRect: PlayerLyricsRect?,
    val artworkAlignment: PlayerLyricsArtworkAlignment?,
    val lyricVisibility: PlayerLyricsArtworkVisibility,
    val lyricNormalizedPosition: Float?,
    val offscreenDistancePx: Float,
    val scrollDirection: PlayerLyricsScrollDirection,
    val playerRadiusDp: Float,
    val lyricRadiusDp: Float,
    val lyricSnapshotGeneration: Long,
    val interactive: Boolean
)

enum class PlayerLyricsTransitionDirection {
    PlayerToLyrics,
    LyricsToPlayer
}

enum class PlayerLyricsTransitionRoute {
    /** A real visible artwork item exists; align both complete scenes around its frozen geometry. */
    VisibleArtworkAligned,

    /** No real shared item exists; use a centered scene zoom/crossfade with exactly zero offset. */
    OffscreenSceneZoom
}

enum class PlayerLyricsArtworkSource {
    Player,
    LyricFixed,
    LyricScrolling,
    LyricTitleOnly
}

enum class PlayerLyricsArtworkVisibility {
    Visible,
    AboveViewport,
    BelowViewport,
    Missing
}

enum class PlayerLyricsScrollDirection {
    Up,
    Down,
    Still
}

data class PlayerLyricsArtworkAnchor(
    val source: PlayerLyricsArtworkSource,
    val artworkKey: String?,
    val rect: PlayerLyricsRect?,
    val visibility: PlayerLyricsArtworkVisibility,
    val visibleFraction: Float,
    val offscreenDistancePx: Float,
    val scrollDirection: PlayerLyricsScrollDirection,
    val isScrollInProgress: Boolean,
    val stable: Boolean,
    val cornerRadiusDp: Float,
    val updatedAtUptimeMs: Long,
    val normalizedViewportPosition: Float? = null,
    val snapshotGeneration: Long = 0L
) {
    fun normalized(): PlayerLyricsArtworkAnchor = copy(
        visibleFraction = visibleFraction.coerceIn(0f, 1f),
        offscreenDistancePx = offscreenDistancePx.coerceAtLeast(0f)
    )

    /**
     * Do not use a 90% visible-area rule or a time-based stale-candidate grace period.
     * A scrolling item is eligible only while its current normalized layout position is inside
     * the open (-0.967, 0.967) interval. A fixed header is already a stable visible item.
     */
    fun isEligibleSharedArtworkCandidate(): Boolean {
        if (source == PlayerLyricsArtworkSource.LyricTitleOnly) return false
        if (visibility != PlayerLyricsArtworkVisibility.Visible) return false
        if (isScrollInProgress || !stable || rect?.isUsable != true) return false
        return when (source) {
            PlayerLyricsArtworkSource.LyricScrolling -> {
                val position = normalizedViewportPosition ?: return false
                position > -PlayerLyricsTransitionCoordinator.SHARED_ARTWORK_POSITION_LIMIT &&
                    position < PlayerLyricsTransitionCoordinator.SHARED_ARTWORK_POSITION_LIMIT
            }
            PlayerLyricsArtworkSource.LyricFixed -> true
            PlayerLyricsArtworkSource.Player,
            PlayerLyricsArtworkSource.LyricTitleOnly -> false
        }
    }

    fun sharedArtworkCandidateDistance(): Float {
        val position = normalizedViewportPosition ?: return 0f
        return (
            (abs(position) - PlayerLyricsTransitionCoordinator.SHARED_ARTWORK_POSITION_DEAD_ZONE) /
                PlayerLyricsTransitionCoordinator.SHARED_ARTWORK_POSITION_LIMIT
            ).coerceAtLeast(0f)
    }
}

/**
 * Frozen scene transforms derived from the real player and lyric artwork rectangles.
 *
 * Derive the target zoom from the source/target item height ratio and
 * their centers.  Compose uses an equivalent top-left matrix: scale by the same height ratio and
 * translate so the complete source rectangle lands exactly on the target rectangle.
 */
data class PlayerLyricsArtworkAlignment(
    val playerToLyricScale: Float,
    val playerToLyricTranslationX: Float,
    val playerToLyricTranslationY: Float,
    val lyricToPlayerScale: Float,
    val lyricToPlayerTranslationX: Float,
    val lyricToPlayerTranslationY: Float
) {
    companion object {
        fun between(
            playerRect: PlayerLyricsRect,
            lyricRect: PlayerLyricsRect
        ): PlayerLyricsArtworkAlignment? {
            if (!playerRect.isUsable || !lyricRect.isUsable) return null
            val playerToLyricScale = lyricRect.height / playerRect.height
            val lyricToPlayerScale = playerRect.height / lyricRect.height
            if (!playerToLyricScale.isFinite() || !lyricToPlayerScale.isFinite()) return null
            return PlayerLyricsArtworkAlignment(
                playerToLyricScale = playerToLyricScale,
                playerToLyricTranslationX = lyricRect.left - playerRect.left * playerToLyricScale,
                playerToLyricTranslationY = lyricRect.top - playerRect.top * playerToLyricScale,
                lyricToPlayerScale = lyricToPlayerScale,
                lyricToPlayerTranslationX = playerRect.left - lyricRect.left * lyricToPlayerScale,
                lyricToPlayerTranslationY = playerRect.top - lyricRect.top * lyricToPlayerScale
            )
        }
    }
}

data class PlayerLyricsRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val centerX: Float get() = (left + right) * 0.5f
    val centerY: Float get() = (top + bottom) * 0.5f
    val isUsable: Boolean get() = width >= 8f && height >= 8f

    fun lerpTo(other: PlayerLyricsRect, fraction: Float): PlayerLyricsRect {
        val f = fraction.coerceIn(0f, 1f)
        return PlayerLyricsRect(
            left = left + (other.left - left) * f,
            top = top + (other.top - top) * f,
            right = right + (other.right - right) * f,
            bottom = bottom + (other.bottom - bottom) * f
        )
    }

    fun approximatelyEquals(other: PlayerLyricsRect, tolerancePx: Float = 1.5f): Boolean {
        return abs(left - other.left) <= tolerancePx &&
            abs(top - other.top) <= tolerancePx &&
            abs(right - other.right) <= tolerancePx &&
            abs(bottom - other.bottom) <= tolerancePx
    }
}
