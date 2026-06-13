package com.rawsmusic.ui.settings.compose.scene.state

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp

/**
 * 列表项布局数据类
 * 对应原版 AAItemView 的 SceneFrames / SceneChildFrame
 *
 * 包含计算后的布局参数，用于渲染列表项
 */
data class ItemLayout(
    // ==================== 整体尺寸 ====================
    /** 行宽度 (px) */
    val rowWidth: Int,
    /** 行高度 (px) */
    val rowHeight: Int,

    // ==================== 封面 ====================
    /** 封面大小 (px) */
    val coverSize: Int,
    /** 封面左边距 (px) */
    val coverLeft: Int,
    /** 封面顶部位置 (px) - 居中计算后 */
    val coverTop: Int,
    /** 封面圆角 (px) */
    val cornerRadius: Float,

    // ==================== 标题 ====================
    /** 标题左边距 (px) */
    val titleLeft: Int,
    /** 标题顶部位置 (px) */
    val titleTop: Int,
    /** 标题宽度 (px) */
    val titleWidth: Int,
    /** 标题样式 */
    val titleStyle: TextStyle,

    // ==================== 第二行 (艺术家/专辑) ====================
    /** 第二行左边距 (px) */
    val line2Left: Int,
    /** 第二行顶部位置 (px) */
    val line2Top: Int,
    /** 第二行宽度 (px) */
    val line2Width: Int,
    /** 第二行是否可见 */
    val line2Visible: Boolean,
    /** 第二行样式 */
    val line2Style: TextStyle,

    // ==================== 元数据 (时长/比特率) ====================
    /** 元数据左边距 (px) */
    val metaLeft: Int,
    /** 元数据顶部位置 (px) */
    val metaTop: Int,
    /** 元数据宽度 (px) */
    val metaWidth: Int,
    /** 元数据是否可见 */
    val metaVisible: Boolean,
    /** 元数据是否内联 (SMALL 模式下与 line2 同行) */
    val metaInline: Boolean,
    /** 元数据样式 */
    val metaStyle: TextStyle
) {
    companion object {
        /**
         * 创建空布局 (用于初始化)
         */
        fun empty() = ItemLayout(
            rowWidth = 0,
            rowHeight = 0,
            coverSize = 0,
            coverLeft = 0,
            coverTop = 0,
            cornerRadius = 0f,
            titleLeft = 0,
            titleTop = 0,
            titleWidth = 0,
            titleStyle = TextStyle.Default,
            line2Left = 0,
            line2Top = 0,
            line2Width = 0,
            line2Visible = false,
            line2Style = TextStyle.Default,
            metaLeft = 0,
            metaTop = 0,
            metaWidth = 0,
            metaVisible = false,
            metaInline = false,
            metaStyle = TextStyle.Default
        )
    }
}

/**
 * 两组布局之间的插值
 * 对应原版 ViewPositioner.applyDualSlot() 的位置插值逻辑
 */
fun lerpItemLayout(from: ItemLayout, to: ItemLayout, fraction: Float): ItemLayout {
    val f = fraction.coerceIn(0f, 1f)
    return ItemLayout(
        rowWidth = lerpInt(from.rowWidth, to.rowWidth, f),
        rowHeight = lerpInt(from.rowHeight, to.rowHeight, f),
        coverSize = lerpInt(from.coverSize, to.coverSize, f),
        coverLeft = lerpInt(from.coverLeft, to.coverLeft, f),
        coverTop = lerpInt(from.coverTop, to.coverTop, f),
        cornerRadius = lerpFloat(from.cornerRadius, to.cornerRadius, f),
        titleLeft = lerpInt(from.titleLeft, to.titleLeft, f),
        titleTop = lerpInt(from.titleTop, to.titleTop, f),
        titleWidth = lerpInt(from.titleWidth, to.titleWidth, f),
        titleStyle = if (f < 0.5f) from.titleStyle else to.titleStyle,
        line2Left = lerpInt(from.line2Left, to.line2Left, f),
        line2Top = lerpInt(from.line2Top, to.line2Top, f),
        line2Width = lerpInt(from.line2Width, to.line2Width, f),
        line2Visible = if (f < 0.5f) from.line2Visible else to.line2Visible,
        line2Style = if (f < 0.5f) from.line2Style else to.line2Style,
        metaLeft = lerpInt(from.metaLeft, to.metaLeft, f),
        metaTop = lerpInt(from.metaTop, to.metaTop, f),
        metaWidth = lerpInt(from.metaWidth, to.metaWidth, f),
        metaVisible = if (f < 0.5f) from.metaVisible else to.metaVisible,
        metaInline = if (f < 0.5f) from.metaInline else to.metaInline,
        metaStyle = if (f < 0.5f) from.metaStyle else to.metaStyle
    )
}

private fun lerpInt(from: Int, to: Int, fraction: Float): Int {
    return (from + (to - from) * fraction).toInt()
}

private fun lerpFloat(from: Float, to: Float, fraction: Float): Float {
    return from + (to - from) * fraction
}
