package com.rawsmusic.helper

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.LifecycleOwner
import com.rawsmusic.core.ui.widget.DynamicCoverBackgroundHost
import com.rawsmusic.core.ui.widget.ImmersiveBackgroundHost

/**
 * 背景管理器。
 *
 * 封面取色和封面驱动背景已移除，主界面使用 Miuix/默认主题背景。
 */
class CoverBackgroundManager(
    private val lifecycleOwner: LifecycleOwner,
    private val playBgView: DynamicCoverBackgroundHost,
    private val lyricBgView: DynamicCoverBackgroundHost,
    private val backgroundView: DynamicCoverBackgroundHost,
    private val immersiveBackground: ImmersiveBackgroundHost,
    private val mainPersistentCover: ImmersiveBackgroundHost?,
    val layerState: CoverBackgroundLayerState,
    private val updateDefaultBackgroundEnabled: (Boolean) -> Unit,
    private val updateImmersiveCover: (String?) -> Unit,
    private val applyLyricColors: () -> Unit
) {

    fun loadCoverBackground(albumArtPath: String) {
        clearCoverDrivenBackground()
        applyDefaultColors()
    }

    fun applyDefaultColors() {
        applyDefaultBackground()
        applyLyricColors()
    }

    /**
     * 应用默认背景：亮色模式白底黑字，暗色模式纯黑底白字
     * 强制覆盖所有沉浸/封面背景层
     */
    fun applyDefaultBackground() {
        val isLight = !com.rawsmusic.core.ui.theme.ThemeManager.isDarkMode(lifecycleOwner as android.content.Context)

        updateDefaultBackgroundEnabled(true)
        clearCoverDrivenBackground()
        layerState.rootBackgroundColor = if (isLight) Color.WHITE else Color.BLACK
        com.rawsmusic.core.ui.theme.ThemeManager.isLightBackground = isLight
    }

    private fun clearCoverDrivenBackground() {
        playBgView.clearArtwork()
        lyricBgView.clearArtwork()
        backgroundView.clearArtwork()
        immersiveBackground.clear()
        mainPersistentCover?.clear()
        updateImmersiveCover(null)
        listOf(backgroundView, playBgView, lyricBgView).forEach { host ->
            host.setThemeLightMode(false)
        }
        layerState.backgroundVisible = false
        layerState.backgroundAlpha = 0f
        layerState.playBackgroundVisible = false
        layerState.playBackgroundAlpha = 0f
        layerState.lyricBackgroundVisible = false
        layerState.lyricBackgroundAlpha = 0f
        layerState.immersiveVisible = false
        layerState.immersiveAlpha = 0f
        layerState.mainPersistentCoverVisible = false
        layerState.mainPersistentCoverAlpha = 0f
        layerState.mainScrimVisible = false
        layerState.mainScrimAlpha = 0f
        layerState.playScrimVisible = false
        layerState.playScrimAlpha = 0f
    }
}

class CoverBackgroundLayerState {
    var rootBackgroundColor by mutableIntStateOf(Color.TRANSPARENT)

    var backgroundVisible by mutableStateOf(true)
    var backgroundAlpha by mutableFloatStateOf(1f)

    var playBackgroundVisible by mutableStateOf(false)
    var playBackgroundAlpha by mutableFloatStateOf(0f)

    var lyricBackgroundVisible by mutableStateOf(false)
    var lyricBackgroundAlpha by mutableFloatStateOf(0f)

    var immersiveVisible by mutableStateOf(true)
    var immersiveAlpha by mutableFloatStateOf(0f)

    var mainPersistentCoverVisible by mutableStateOf(true)
    var mainPersistentCoverAlpha by mutableFloatStateOf(1f)

    var mainScrimVisible by mutableStateOf(true)
    var mainScrimAlpha by mutableFloatStateOf(1f)

    var playScrimVisible by mutableStateOf(false)
    var playScrimAlpha by mutableFloatStateOf(0f)
}
