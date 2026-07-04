package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 场景过渡进度的 CompositionLocal。
 *
 * 在 SceneTransitionHost 的过渡渲染期间提供，值范围 0.0~1.0。
 * 下游 Composable 通过 `LocalSceneTransitionProgress.current` 读取，
 * 用于驱动文本颜色插值等动画效果。
 *
 * 非过渡期间默认值为 0.0。
 */
val LocalSceneTransitionProgress = staticCompositionLocalOf { 0f }

/**
 * 共享元素过渡注册表的 CompositionLocal。
 *
 * 由 SceneTransitionHost 提供，下游 Composable 通过 register/unregister
 * 注册参与共享元素过渡的文本。
 */
val LocalSharedTransitionRegistry = staticCompositionLocalOf { SharedTransitionRegistry() }

/**
 * PowerList 场景转场期间由宿主绘制固定背景，页面根布局不要再绘制整屏背景。
 */
val LocalSceneBackgroundFrozen = staticCompositionLocalOf { false }
