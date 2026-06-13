package com.rawsmusic.ui.settings.compose.scene.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.rawsmusic.ui.settings.compose.scene.container.PlayerScene

/**
 * 场景参数数据类
 * 对应原版 UnifiedPlayerContainer.SceneParams
 *
 * 每个 View 可以为每个场景注册不同的属性
 * 当场景切换时，容器会插值这些属性
 */
data class SceneParams(
    /** 透明度 */
    val alpha: Float = 1f,
    /** X 轴缩放 */
    val scaleX: Float = 1f,
    /** Y 轴缩放 */
    val scaleY: Float = 1f,
    /** X 轴平移 (px) */
    val translationX: Float = 0f,
    /** Y 轴平移 (px) */
    val translationY: Float = 0f,
    /** 旋转角度 (度) */
    val rotation: Float = 0f,
    /** X 轴旋转 */
    val rotationX: Float = 0f,
    /** Y 轴旋转 */
    val rotationY: Float = 0f,
    /** 圆角半径 (px) */
    val cornerRadius: Float = 0f,
    /** 可见性 */
    val visible: Boolean = true,
    /** Alpha 乘数 */
    val alphaMultiplier: Float = 1f
) {
    companion object {
        /** 默认参数 */
        val DEFAULT = SceneParams()

        /** 不可见 */
        val INVISIBLE = SceneParams(alpha = 0f, visible = false)

        /** 完全透明 */
        val TRANSPARENT = SceneParams(alpha = 0f)

        /** 缩小 */
        val SCALED_DOWN = SceneParams(scaleX = 0.8f, scaleY = 0.8f, alpha = 0f)

        /** 放大 */
        val SCALED_UP = SceneParams(scaleX = 1.2f, scaleY = 1.2f)
    }
}

/**
 * 场景参数注册表
 * 对应原版 UnifiedPlayerContainer 的 SceneParams 注册系统
 *
 * 管理所有 View 的场景参数注册
 */
@Stable
class SceneParamsRegistry {
    /**
     * 注册表：View ID -> Scene -> SceneParams
     */
    private val registry = mutableStateMapOf<String, MutableMap<PlayerScene, SceneParams>>()

    /**
     * 注册场景参数
     *
     * @param viewId View 的唯一标识
     * @param scene 目标场景
     * @param params 场景参数
     */
    fun register(viewId: String, scene: PlayerScene, params: SceneParams) {
        val sceneMap = registry.getOrPut(viewId) { mutableMapOf() }
        sceneMap[scene] = params
    }

    /**
     * 批量注册场景参数
     *
     * @param viewId View 的唯一标识
     * @param params 场景参数映射
     */
    fun registerAll(viewId: String, params: Map<PlayerScene, SceneParams>) {
        val sceneMap = registry.getOrPut(viewId) { mutableMapOf() }
        sceneMap.putAll(params)
    }

    /**
     * 获取场景参数
     *
     * @param viewId View 的唯一标识
     * @param scene 目标场景
     * @return 场景参数，如果未注册则返回默认值
     */
    fun get(viewId: String, scene: PlayerScene): SceneParams {
        return registry[viewId]?.get(scene) ?: SceneParams.DEFAULT
    }

    /**
     * 检查是否已注册
     *
     * @param viewId View 的唯一标识
     * @param scene 目标场景
     * @return 是否已注册
     */
    fun isRegistered(viewId: String, scene: PlayerScene): Boolean {
        return registry[viewId]?.containsKey(scene) == true
    }

    /**
     * 移除注册
     *
     * @param viewId View 的唯一标识
     * @param scene 目标场景，如果为 null 则移除所有场景
     */
    fun unregister(viewId: String, scene: PlayerScene? = null) {
        if (scene == null) {
            registry.remove(viewId)
        } else {
            registry[viewId]?.remove(scene)
        }
    }

    /**
     * 清空所有注册
     */
    fun clear() {
        registry.clear()
    }

    /**
     * 获取所有已注册的 View ID
     */
    fun getRegisteredViewIds(): Set<String> = registry.keys.toSet()

    /**
     * 获取指定 View 的所有已注册场景
     */
    fun getRegisteredScenes(viewId: String): Set<PlayerScene> {
        return registry[viewId]?.keys ?: emptySet()
    }
}

/**
 * 场景参数插值器
 * 对应原版 UnifiedPlayerContainer 的 ratio-driven 动画
 *
 * 在两个 SceneParams 之间插值
 */
object SceneParamsInterpolator {
    /**
     * 在两个 SceneParams 之间插值
     *
     * @param from 源参数
     * @param to 目标参数
     * @param fraction 插值比例 (0..1)
     * @return 插值后的参数
     */
    fun lerp(from: SceneParams, to: SceneParams, fraction: Float): SceneParams {
        val t = fraction.coerceIn(0f, 1f)
        return SceneParams(
            alpha = lerpFloat(from.alpha, to.alpha, t),
            scaleX = lerpFloat(from.scaleX, to.scaleX, t),
            scaleY = lerpFloat(from.scaleY, to.scaleY, t),
            translationX = lerpFloat(from.translationX, to.translationX, t),
            translationY = lerpFloat(from.translationY, to.translationY, t),
            rotation = lerpFloat(from.rotation, to.rotation, t),
            rotationX = lerpFloat(from.rotationX, to.rotationX, t),
            rotationY = lerpFloat(from.rotationY, to.rotationY, t),
            cornerRadius = lerpFloat(from.cornerRadius, to.cornerRadius, t),
            visible = if (t < 0.5f) from.visible else to.visible,
            alphaMultiplier = lerpFloat(from.alphaMultiplier, to.alphaMultiplier, t)
        )
    }

    private fun lerpFloat(from: Float, to: Float, fraction: Float): Float {
        return from + (to - from) * fraction
    }
}

/**
 * Compose 版本的 SceneParams Modifier
 *
 * 使用 graphicsLayer 应用场景参数
 */
fun Modifier.sceneParams(params: SceneParams): Modifier {
    return this.graphicsLayer {
        alpha = params.alpha * params.alphaMultiplier
        scaleX = params.scaleX
        scaleY = params.scaleY
        translationX = params.translationX
        translationY = params.translationY
        rotationZ = params.rotation
        rotationX = params.rotationX
        rotationY = params.rotationY
        // cornerRadius 在 Compose 中需要通过 clip 实现
    }
}

/**
 * 记住 SceneParamsRegistry
 */
@Composable
fun rememberSceneParamsRegistry(): SceneParamsRegistry {
    return remember { SceneParamsRegistry() }
}
