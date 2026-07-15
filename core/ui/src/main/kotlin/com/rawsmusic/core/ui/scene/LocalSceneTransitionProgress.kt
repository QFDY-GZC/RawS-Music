package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * PowerList 场景转场期间由宿主绘制固定背景，页面根布局不要再绘制整屏背景。
 */
val LocalSceneBackgroundFrozen = staticCompositionLocalOf { false }
