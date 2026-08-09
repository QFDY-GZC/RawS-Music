package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * PowerList 场景转场期间由宿主绘制固定背景，页面根布局不要再绘制整屏背景。
 */
val LocalSceneBackgroundFrozen = staticCompositionLocalOf { false }

data class SceneChromeAlpha(
    val alphabetIndex: Float = 1f,
    val topMenu: Float = 1f,
    val detachAlphabetIndex: Boolean = false,
)

/**
 * 返回主界面时，列表浮层独立于页面缩放收尾，避免跟随根布局突然消失。
 */
val LocalSceneChromeAlpha = staticCompositionLocalOf { SceneChromeAlpha() }
