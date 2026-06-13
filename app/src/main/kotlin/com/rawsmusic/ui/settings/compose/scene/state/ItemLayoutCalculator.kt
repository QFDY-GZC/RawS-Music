package com.rawsmusic.ui.settings.compose.scene.state

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.ui.settings.powerlist.ListZoomParams

/**
 * 布局计算器
 * 对应原版 AAItemView.measureList() / buildListFrames()
 *
 * 根据 ListZoomParams 计算 ItemLayout
 */
object ItemLayoutCalculator {
    // ==================== 基础文本大小 (对应原版 AAItemView) ====================
    private const val TITLE_BASE_SP = 22f
    private const val LINE2_BASE_SP = 18.25f
    private const val META_BASE_SP = 13.5f

    /**
     * 计算列表模式的布局
     * 对应原版 AAItemView.buildListFrames()
     *
     * @param params 缩放参数
     * @param containerWidth 容器宽度 (px)
     * @param density 屏幕密度
     */
    fun calculateListLayout(
        params: ListZoomParams,
        containerWidth: Int,
        density: Float
    ): ItemLayout {
        // 转换 dp 到 px
        val coverSize = (params.coverSizeDp * density).toInt()
        val coverMarginLeft = (params.coverMarginLeftDp * density).toInt()
        val coverMarginTop = (params.coverMarginTopDp * density).toInt()
        val coverMarginBottom = (params.coverMarginBottomDp * density).toInt()
        val textMarginLeft = (params.textMarginLeftDp * density).toInt()
        val textMarginRight = (params.textMarginRightDp * density).toInt()
        val cornerRadius = params.cornerRadiusTracksDp * density
        val titleTopOffset = (params.titleTopOffsetDp * density).toInt()

        // 计算行高
        val rowHeight = if (params.rowHeightIsSp) {
            (params.rowHeightValue * density).toInt() // sp 转 px
        } else {
            (params.rowHeightValue * density).toInt() // dp 转 px
        }

        // 计算封面位置 (垂直居中)
        val coverLeft = coverMarginLeft
        val coverTop = ((rowHeight - coverSize) / 2).coerceAtLeast(0)

        // 计算文本区域
        val textLeft = coverLeft + coverSize + textMarginLeft
        val textRight = containerWidth - textMarginRight
        val textWidth = (textRight - textLeft).coerceAtLeast(0)

        // 计算文本样式
        val textScale = params.textScale
        val titleStyle = TextStyle(
            fontSize = (TITLE_BASE_SP * textScale).sp,
            fontWeight = FontWeight.Bold
        )
        val line2Style = TextStyle(
            fontSize = (LINE2_BASE_SP * textScale).sp
        )
        val metaStyle = TextStyle(
            fontSize = (META_BASE_SP * textScale).sp,
            fontWeight = FontWeight.Bold
        )

        // 计算标题位置
        val titleLeft = textLeft
        val titleTop = coverTop + titleTopOffset

        // 计算第二行位置 (标题下方)
        val line2Left = textLeft
        val line2Top = titleTop + (TITLE_BASE_SP * textScale * density).toInt() + (6 * density).toInt()

        // 计算元数据位置
        val metaLeft: Int
        val metaTop: Int
        val metaInline: Boolean

        if (params.metaInlineFraction >= 1f) {
            // SMALL 模式：元数据内联到 line2 右侧
            metaInline = true
            metaLeft = textLeft + textWidth * 60 / 100 // 约 60% 处
            metaTop = line2Top
        } else {
            // NORMAL/ZOOMED 模式：元数据堆叠在 line2 下方
            metaInline = false
            metaLeft = textLeft
            metaTop = line2Top + (LINE2_BASE_SP * textScale * density).toInt() + (5 * density).toInt()
        }

        return ItemLayout(
            rowWidth = containerWidth,
            rowHeight = rowHeight,
            coverSize = coverSize,
            coverLeft = coverLeft,
            coverTop = coverTop,
            cornerRadius = cornerRadius,
            titleLeft = titleLeft,
            titleTop = titleTop,
            titleWidth = textWidth,
            titleStyle = titleStyle,
            line2Left = line2Left,
            line2Top = line2Top,
            line2Width = textWidth,
            line2Visible = params.line2Visible,
            line2Style = line2Style,
            metaLeft = metaLeft,
            metaTop = metaTop,
            metaWidth = textWidth,
            metaVisible = params.metaVisible,
            metaInline = metaInline,
            metaStyle = metaStyle
        )
    }

    /**
     * 计算网格模式的布局
     * 对应原版 AAItemView.buildGridFrames()
     *
     * @param columns 列数
     * @param containerWidth 容器宽度 (px)
     * @param density 屏幕密度
     */
    fun calculateGridLayout(
        columns: Int,
        containerWidth: Int,
        density: Float
    ): ItemLayout {
        val cellWidth = containerWidth / columns
        val aaMargin = (8 * density).toInt()
        val labelMargin = (18 * density).toInt()

        // 封面大小 = 单元格宽度 - 边距
        val coverSize = (cellWidth - aaMargin * 2).coerceAtLeast(1)

        // 文本区域
        val textLeft = labelMargin
        val textRight = cellWidth - labelMargin
        val textWidth = (textRight - textLeft).coerceAtLeast(1)

        // 网格模式文本缩放
        val gridTextScale = 0.65f
        val titleStyle = TextStyle(
            fontSize = (TITLE_BASE_SP * gridTextScale).sp,
            fontWeight = FontWeight.Bold
        )
        val line2Style = TextStyle(
            fontSize = (LINE2_BASE_SP * gridTextScale).sp
        )

        // 标题位置 (封面下方)
        val titleLeft = textLeft
        val titleTop = aaMargin + coverSize + (5 * density).toInt()

        // 第二行位置
        val line2Left = textLeft
        val line2Top = titleTop + (TITLE_BASE_SP * gridTextScale * density).toInt() + (3 * density).toInt()

        return ItemLayout(
            rowWidth = cellWidth,
            rowHeight = cellWidth, // 网格模式正方形
            coverSize = coverSize,
            coverLeft = aaMargin,
            coverTop = aaMargin,
            cornerRadius = 16f * density, // corners_aa_tracks_grid
            titleLeft = titleLeft,
            titleTop = titleTop,
            titleWidth = textWidth,
            titleStyle = titleStyle,
            line2Left = line2Left,
            line2Top = line2Top,
            line2Width = textWidth,
            line2Visible = true,
            line2Style = line2Style,
            metaLeft = 0,
            metaTop = 0,
            metaWidth = 0,
            metaVisible = false,
            metaInline = false,
            metaStyle = TextStyle.Default
        )
    }
}
