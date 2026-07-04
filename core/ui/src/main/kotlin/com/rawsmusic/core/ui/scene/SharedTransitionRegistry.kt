package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color

/**
 * 共享文本/普通共享元素注册表。
 * key = "$sceneId::$elementId"。
 */
@Stable
class SharedTransitionRegistry {

    data class SharedElementSnapshot(
        val sceneId: String,
        val elementId: String,
        val boundsInWindow: Rect,
        val text: String,
        val color: Color,
        val fontSizeSp: Float,
        val fontWeight: Int,
    )

    private val _elements = mutableStateMapOf<String, SharedElementSnapshot>()
    private val _frozenElements = mutableStateMapOf<String, SharedElementSnapshot>()

    val elements: Map<String, SharedElementSnapshot> get() = _elements

    private fun key(sceneId: String, elementId: String): String {
        return "$sceneId::$elementId"
    }

    fun register(sceneId: String, elementId: String, snapshot: SharedElementSnapshot) {
        if (sceneId.isBlank() || elementId.isBlank()) return
        _elements[key(sceneId, elementId)] = snapshot
    }

    fun unregister(sceneId: String, elementId: String) {
        // 只移除 live，不清 frozen。转场中来源/目标节点可能已离开 composition，但 overlay 还需要 frozen bounds。
        _elements.remove(key(sceneId, elementId))
    }

    fun get(sceneId: String, elementId: String): SharedElementSnapshot? {
        return _elements[key(sceneId, elementId)]
    }

    fun freeze(sceneId: String, elementId: String) {
        val k = key(sceneId, elementId)
        _elements[k]?.let { _frozenElements[k] = it }
    }

    fun getFrozen(sceneId: String, elementId: String): SharedElementSnapshot? {
        return _frozenElements[key(sceneId, elementId)]
    }

    fun clearFrozen() {
        _frozenElements.clear()
    }
}

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
    val transitionKey: String = ""
)

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

    private val _elements = mutableStateMapOf<String, SharedCoverSnapshot>()
    private val _frozenElements = mutableStateMapOf<String, SharedCoverSnapshot>()

    val elements: Map<String, SharedCoverSnapshot> get() = _elements

    private fun key(sceneId: String, elementId: String): String {
        return "$sceneId::$elementId"
    }

    fun register(sceneId: String, elementId: String, snapshot: SharedCoverSnapshot) {
        if (sceneId.isBlank() || elementId.isBlank()) return
        _elements[key(sceneId, elementId)] = snapshot
    }

    fun unregister(sceneId: String, elementId: String) {
        // 只移除 live，不清 frozen。转场中来源/目标节点可能已离开 composition，但 overlay 还需要 frozen bounds。
        _elements.remove(key(sceneId, elementId))
    }

    fun get(sceneId: String, elementId: String): SharedCoverSnapshot? {
        return _elements[key(sceneId, elementId)]
    }

    fun freeze(sceneId: String, elementId: String) {
        val k = key(sceneId, elementId)
        _elements[k]?.let { _frozenElements[k] = it }
    }

    fun getFrozen(sceneId: String, elementId: String): SharedCoverSnapshot? {
        return _frozenElements[key(sceneId, elementId)]
    }

    fun clearFrozen() {
        _frozenElements.clear()
    }

    fun hasPair(fromSceneId: String, toSceneId: String, elementId: String): Boolean {
        val from = getFrozen(fromSceneId, elementId) ?: get(fromSceneId, elementId)
        val to = getFrozen(toSceneId, elementId) ?: get(toSceneId, elementId)
        return from != null && to != null
    }

    fun findPairs(fromSceneId: String, toSceneId: String): List<Pair<SharedCoverSnapshot, SharedCoverSnapshot>> {
        val prefix = "$fromSceneId::"
        val ids = (_elements.keys + _frozenElements.keys)
            .asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .distinct()
            .toList()

        return ids.mapNotNull { elementId ->
            val from = getFrozen(fromSceneId, elementId) ?: get(fromSceneId, elementId)
            val to = getFrozen(toSceneId, elementId) ?: get(toSceneId, elementId)
            if (from != null && to != null) from to to else null
        }
    }
}

val LocalSharedCoverRegistry = staticCompositionLocalOf {
    SharedCoverRegistry()
}
