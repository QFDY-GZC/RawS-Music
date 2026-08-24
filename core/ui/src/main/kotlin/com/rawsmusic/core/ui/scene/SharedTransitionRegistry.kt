package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
/**
 * 共享元素过渡规格。
 *
 * progress 统一定义为 from -> to 的 0..1。
 */
data class SharedTransitionSpec(
    val active: Boolean = false,
    val fromSceneId: String = "",
    val toSceneId: String = "",
    val activeSceneId: String = "",
    val progress: Float = 0f,
    val transitionKey: String = "",
    val allowRememberedTarget: Boolean = false,
    // The overlay owns the cover bounds during a prepared shared transition. The
    // corresponding PowerList item must remain at its physical slot to avoid a
    // second affine transform fighting the shared cover interpolation.
    val sharedCoverOverlayOwnsAnchor: Boolean = false
) {
    fun shouldTrackScene(sceneId: String): Boolean {
        return if (active) {
            sceneId == fromSceneId || sceneId == toSceneId
        } else {
            sceneId == activeSceneId
        }
    }
}

val LocalSharedTransitionSpec = staticCompositionLocalOf {
    SharedTransitionSpec()
}

/**
 * 共享封面快照。
 * 只服务文件夹大封面 hero 转场；不要拿它做所有页面的封面飞行动画。
 */
data class SharedCoverSnapshot(
    val sceneId: String,
    val elementId: String,
    val boundsInWindow: Rect,
    val coverKey: String,
    val radiusDp: Float
)

@Stable
class SharedCoverRegistry {
    private data class LiveEntry(
        val owner: Any,
        val snapshot: SharedCoverSnapshot
    )

    private data class CollectionSession(
        val elementId: String,
        val listSceneId: String,
        val detailSceneId: String,
        val listEndpoint: SharedCoverSnapshot,
        val headerEndpoint: SharedCoverSnapshot
    )

    private val live = mutableMapOf<String, LiveEntry>()
    private var requestedSource: SharedCoverSnapshot? = null
    private var collectionSession: CollectionSession? = null
    private var preparedTransitionKey: String by mutableStateOf("")
    private var preparedPair: Pair<SharedCoverSnapshot, SharedCoverSnapshot>? by mutableStateOf(null)

    val elements: Map<String, SharedCoverSnapshot>
        get() = live.mapValues { it.value.snapshot }

    private fun key(sceneId: String, elementId: String): String {
        return "$sceneId::$elementId"
    }

    fun register(
        sceneId: String,
        elementId: String,
        owner: Any,
        snapshot: SharedCoverSnapshot
    ) {
        if (sceneId.isBlank() || elementId.isBlank()) return
        val key = key(sceneId, elementId)
        live[key] = LiveEntry(owner = owner, snapshot = snapshot)
    }

    fun unregister(sceneId: String, elementId: String, owner: Any) {
        val key = key(sceneId, elementId)
        if (live[key]?.owner === owner) live.remove(key)
    }

    fun get(sceneId: String, elementId: String): SharedCoverSnapshot? {
        return live[key(sceneId, elementId)]?.snapshot
    }

    fun freeze(sceneId: String, elementId: String) {
        // A list click starts a new holder-promotion transaction. Never let a missing or not-yet
        // measured holder silently reuse the previous collection session: that makes item B
        // return through item A's artwork. A detail-header freeze is the reverse half of the
        // existing transaction and therefore keeps its matching session.
        val session = collectionSession
        val isMatchingReturn = session != null &&
            sceneId == session.detailSceneId &&
            elementId == session.elementId
        preparedTransitionKey = ""
        preparedPair = null
        requestedSource = null
        if (!isMatchingReturn) collectionSession = null
        live[key(sceneId, elementId)]?.snapshot?.let { requestedSource = it }
    }

    fun clearFrozen() {
        preparedTransitionKey = ""
        preparedPair = null
    }

    fun prepareTransition(
        transitionKey: String,
        from: SharedCoverSnapshot,
        to: SharedCoverSnapshot
    ) {
        val existing = collectionSession
        val isReverse = existing != null &&
            from.elementId == existing.elementId &&
            from.sceneId == existing.detailSceneId &&
            to.sceneId == existing.listSceneId

        val stableFrom: SharedCoverSnapshot
        val stableTo: SharedCoverSnapshot
        if (isReverse) {
            val reverseSession = checkNotNull(existing)
            stableFrom = from.copy(
                coverKey = reverseSession.headerEndpoint.coverKey.ifBlank { from.coverKey }
            )
            // PowerList copies the destination holder's complete layout record when the
            // collection transaction starts and reuses that exact record for the reverse leg.
            // A retained Compose scene can report a transient second coordinate while it is
            // promoted from the spare slot; accepting that live measurement makes the overlay
            // land there and then jump again when the real holder resumes its original layout.
            stableTo = reverseSession.listEndpoint.copy(
                coverKey = to.coverKey.ifBlank { reverseSession.listEndpoint.coverKey },
                radiusDp = to.radiusDp,
            )
            collectionSession = reverseSession
        } else {
            stableFrom = from
            stableTo = to
            collectionSession = CollectionSession(
                elementId = from.elementId,
                listSceneId = from.sceneId,
                detailSceneId = to.sceneId,
                listEndpoint = from,
                headerEndpoint = to
            )
        }
        preparedTransitionKey = transitionKey
        preparedPair = stableFrom to stableTo
        requestedSource = null
    }

    fun getPreparedPair(transitionKey: String): Pair<SharedCoverSnapshot, SharedCoverSnapshot>? {
        return preparedPair.takeIf { transitionKey.isNotBlank() && preparedTransitionKey == transitionKey }
    }

    fun isPrepared(transitionKey: String): Boolean {
        return transitionKey.isNotBlank() &&
            preparedTransitionKey == transitionKey &&
            preparedPair != null
    }

    fun isPreparedElement(transitionKey: String, sceneId: String, elementId: String): Boolean {
        val pair = getPreparedPair(transitionKey) ?: return false
        return (pair.first.sceneId == sceneId && pair.first.elementId == elementId) ||
            (pair.second.sceneId == sceneId && pair.second.elementId == elementId)
    }

    fun captureLiveCollectionReturnSource(fromSceneId: String, toSceneId: String): Boolean {
        val session = collectionSession ?: return false
        if (session.detailSceneId != fromSceneId || session.listSceneId != toSceneId) return false
        val alreadyFrozen = requestedSource?.takeIf {
            it.sceneId == fromSceneId && it.elementId == session.elementId
        }
        if (alreadyFrozen != null) return true
        val current = live[key(fromSceneId, session.elementId)]?.snapshot ?: return false
        // Capture the physical source before SceneTransitionHost starts moving either page. This is
        // the Compose equivalent of PowerListSharedItemTransition retaining its concrete View/v0.
        requestedSource = current
        return true
    }

    fun findPairs(
        fromSceneId: String,
        toSceneId: String,
        allowRememberedTarget: Boolean = false,
        requireLiveTarget: Boolean = false,
    ): List<Pair<SharedCoverSnapshot, SharedCoverSnapshot>> {
        val session = collectionSession
        if (allowRememberedTarget && session != null &&
            session.detailSceneId == fromSceneId && session.listSceneId == toSceneId
        ) {
            // Reverse shared navigation must start from a concrete current detail holder. Reference's
            // PowerListSharedItemTransition owns a View/v0 record; it cannot resurrect a header that
            // has already scrolled completely offscreen. Prefer the explicit Back-time freeze so
            // later layout callbacks cannot move the source endpoint underneath the overlay.
            val frozenFrom = requestedSource?.takeIf {
                it.sceneId == fromSceneId && it.elementId == session.elementId
            }
            val liveFrom = live[key(fromSceneId, session.elementId)]?.snapshot
            val from = frozenFrom ?: liveFrom ?: return emptyList()
            val liveTarget = live[key(toSceneId, session.elementId)]?.snapshot
            if (requireLiveTarget && liveTarget == null) return emptyList()
            return listOf(from to (liveTarget ?: session.listEndpoint))
        }

        val from = requestedSource
            ?.takeIf { it.sceneId == fromSceneId }
            ?: return emptyList()
        val to = live[key(toSceneId, from.elementId)]?.snapshot ?: return emptyList()
        return listOf(from to to)
    }
}

val LocalSharedCoverRegistry = staticCompositionLocalOf {
    SharedCoverRegistry()
}
