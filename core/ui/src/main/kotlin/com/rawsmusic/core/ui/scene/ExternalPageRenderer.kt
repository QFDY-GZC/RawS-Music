package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.Composable

/**
 * 外部页面渲染器接口。
 *
 * 用于 app 模块向 core:ui 的 ComposeNavHost 注册页面渲染能力。
 * 解决 core:ui 无法引用 app 模块类（如 AppPreferences）的问题。
 */
interface ExternalPageRenderer {
    /**
     * 渲染指定场景的页面。
     * @param scene 目标场景
     * @param onBack 返回回调
     * @param argument 导航参数（如专辑名称等）
     * @return true 表示已处理，false 表示未处理（走默认占位）
     */
    @Composable
    fun RenderPage(scene: NavScene, onBack: () -> Unit, argument: String): Boolean
}
