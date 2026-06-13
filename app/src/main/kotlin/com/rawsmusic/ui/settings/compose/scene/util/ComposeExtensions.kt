package com.rawsmusic.ui.settings.compose.scene.util

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import com.rawsmusic.ui.settings.compose.scene.state.SceneParams

/**
 * Compose 扩展函数
 * 对应原版的扩展函数
 *
 * 提供各种扩展函数
 */

/**
 * 应用场景参数
 *
 * @param params 场景参数
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
    }
}

/**
 * 应用透明度
 *
 * @param alpha 透明度 (0..1)
 */
fun Modifier.applyAlpha(alpha: Float): Modifier {
    return this.alpha(alpha.coerceIn(0f, 1f))
}

/**
 * 应用缩放
 *
 * @param scale 缩放比例
 */
fun Modifier.applyScale(scale: Float): Modifier {
    return this.scale(scale.coerceIn(0.1f, 10f))
}

/**
 * 应用平移
 *
 * @param x X 轴平移 (px)
 * @param y Y 轴平移 (px)
 */
fun Modifier.applyTranslation(x: Float, y: Float): Modifier {
    return this.graphicsLayer {
        translationX = x
        translationY = y
    }
}

/**
 * 应用旋转
 *
 * @param degrees 旋转角度 (度)
 */
fun Modifier.applyRotation(degrees: Float): Modifier {
    return this.graphicsLayer {
        rotationZ = degrees
    }
}

/**
 * 应用 X 轴旋转
 *
 * @param degrees 旋转角度 (度)
 */
fun Modifier.applyRotationX(degrees: Float): Modifier {
    return this.graphicsLayer {
        rotationX = degrees
    }
}

/**
 * 应用 Y 轴旋转
 *
 * @param degrees 旋转角度 (度)
 */
fun Modifier.applyRotationY(degrees: Float): Modifier {
    return this.graphicsLayer {
        rotationY = degrees
    }
}

/**
 * 应用变换原点
 *
 * @param pivotX X 轴原点 (0..1)
 * @param pivotY Y 轴原点 (0..1)
 */
fun Modifier.applyTransformOrigin(pivotX: Float, pivotY: Float): Modifier {
    return this.graphicsLayer {
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(pivotX, pivotY)
    }
}

/**
 * 应用中心变换原点
 */
fun Modifier.applyCenterTransformOrigin(): Modifier {
    return applyTransformOrigin(0.5f, 0.5f)
}

/**
 * 应用左上角变换原点
 */
fun Modifier.applyTopLeftTransformOrigin(): Modifier {
    return applyTransformOrigin(0f, 0f)
}

/**
 * 应用右上角变换原点
 */
fun Modifier.applyTopRightTransformOrigin(): Modifier {
    return applyTransformOrigin(1f, 0f)
}

/**
 * 应用左下角变换原点
 */
fun Modifier.applyBottomLeftTransformOrigin(): Modifier {
    return applyTransformOrigin(0f, 1f)
}

/**
 * 应用右下角变换原点
 */
fun Modifier.applyBottomRightTransformOrigin(): Modifier {
    return applyTransformOrigin(1f, 1f)
}

/**
 * 应用可见性
 *
 * @param visible 是否可见
 */
fun Modifier.applyVisibility(visible: Boolean): Modifier {
    return if (visible) {
        this.alpha(1f)
    } else {
        this.alpha(0f)
    }
}

/**
 * 应用条件修饰符
 *
 * @param condition 条件
 * @param modifier 修饰符
 */
fun Modifier.applyIf(condition: Boolean, modifier: Modifier.() -> Modifier): Modifier {
    return if (condition) {
        this.modifier()
    } else {
        this
    }
}

/**
 * 应用条件修饰符（带默认值）
 *
 * @param condition 条件
 * @param trueModifier 条件为真时的修饰符
 * @param falseModifier 条件为假时的修饰符
 */
fun Modifier.applyIfElse(
    condition: Boolean,
    trueModifier: Modifier.() -> Modifier,
    falseModifier: Modifier.() -> Modifier
): Modifier {
    return if (condition) {
        this.trueModifier()
    } else {
        this.falseModifier()
    }
}

/**
 * 链式应用修饰符
 *
 * @param modifiers 修饰符列表
 */
fun Modifier.applyAll(vararg modifiers: Modifier.() -> Modifier): Modifier {
    var result = this
    modifiers.forEach { modifier ->
        result = result.modifier()
    }
    return result
}

/**
 * 安全应用修饰符
 *
 * @param modifier 修饰符
 * @param default 默认修饰符
 */
fun Modifier.applySafe(modifier: Modifier.() -> Modifier, default: Modifier.() -> Modifier): Modifier {
    return try {
        this.modifier()
    } catch (e: Exception) {
        this.default()
    }
}
