package com.rawsmusic.ui.settings.compose.scene.state

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 网格布局计算器
 * 对应原版 GridEngine (294行)
 *
 * 计算网格模式下的布局参数
 */
object GridLayoutCalculator {
    // ==================== 常量 ====================

    /** 网格标签高度 (对应原版 GridListEngine.gridLabelHeightPx) */
    private const val GRID_LABEL_HEIGHT_DP = 46f

    /** 网格文本缩放 (对应原版 AAItemView.GRID_TEXT_SCALE) */
    private const val GRID_TEXT_SCALE = 0.65f

    /** 网格元数据缩放 (对应原版 AAItemView.GRID_META_SCALE) */
    private const val GRID_META_SCALE = 0.85f

    /** 网格封面边距 (对应原版 ItemTrackAAImage_scene_grid) */
    private const val GRID_COVER_MARGIN_DP = 8f

    /** 网格标签边距 (对应原版 ItemTrackTitle/Line2_scene_grid) */
    private const val GRID_LABEL_MARGIN_DP = 18f

    /** 网格圆角 (对应原版 corners_aa_tracks_grid) */
    private const val GRID_CORNER_RADIUS_DP = 16f

    /**
     * 计算网格布局
     * 对应原版 GridEngine.positionItem()
     *
     * @param columns 列数
     * @param itemIndex 项目索引
     * @param containerWidth 容器宽度 (px)
     * @param density 屏幕密度
     * @return 网格布局数据
     */
    fun calculateGridLayout(
        columns: Int,
        itemIndex: Int,
        containerWidth: Int,
        density: Float
    ): GridLayout {
        val cellWidth = containerWidth / columns
        val coverMargin = (GRID_COVER_MARGIN_DP * density).toInt()
        val labelMargin = (GRID_LABEL_MARGIN_DP * density).toInt()
        val cornerRadius = GRID_CORNER_RADIUS_DP * density

        // 封面大小 = 单元格宽度 - 边距
        val coverSize = (cellWidth - coverMargin * 2).coerceAtLeast(1)

        // 文本区域
        val textLeft = labelMargin
        val textRight = cellWidth - labelMargin
        val textWidth = (textRight - textLeft).coerceAtLeast(1)

        // 计算行和列
        val row = itemIndex / columns
        val col = itemIndex % columns

        // 计算位置
        val left = col * cellWidth
        val top = row * (cellWidth + (GRID_LABEL_HEIGHT_DP * density).toInt())

        // 文本样式
        val titleStyle = TextStyle(
            fontSize = (22f * GRID_TEXT_SCALE).sp,
            fontWeight = FontWeight.Bold
        )
        val line2Style = TextStyle(
            fontSize = (18.25f * GRID_TEXT_SCALE).sp
        )

        // 标题位置 (封面下方)
        val titleLeft = textLeft
        val titleTop = coverMargin + coverSize + (5 * density).toInt()

        // 第二行位置
        val line2Left = textLeft
        val line2Top = titleTop + (22f * GRID_TEXT_SCALE * density).toInt() + (3 * density).toInt()

        return GridLayout(
            cellWidth = cellWidth,
            cellHeight = cellWidth, // 网格模式正方形
            coverSize = coverSize,
            coverLeft = coverMargin,
            coverTop = coverMargin,
            cornerRadius = cornerRadius,
            titleLeft = titleLeft,
            titleTop = titleTop,
            titleWidth = textWidth,
            titleStyle = titleStyle,
            line2Left = line2Left,
            line2Top = line2Top,
            line2Width = textWidth,
            line2Style = line2Style,
            left = left,
            top = top
        )
    }

    /**
     * 计算网格总高度
     * 对应原版 GridEngine.calculateTotalHeight()
     *
     * @param itemCount 项目总数
     * @param columns 列数
     * @param containerWidth 容器宽度 (px)
     * @param density 屏幕密度
     * @return 总高度 (px)
     */
    fun calculateTotalHeight(
        itemCount: Int,
        columns: Int,
        containerWidth: Int,
        density: Float
    ): Int {
        if (itemCount <= 0) return 0
        val rows = (itemCount + columns - 1) / columns
        val cellWidth = containerWidth / columns
        val labelHeight = (GRID_LABEL_HEIGHT_DP * density).toInt()
        return rows * (cellWidth + labelHeight)
    }

    /**
     * 计算可见行范围
     * 对应原版 GridEngine 的可见行计算
     *
     * @param scrollY 滚动位置 (px)
     * @param viewportHeight 视口高度 (px)
     * @param columns 列数
     * @param containerWidth 容器宽度 (px)
     * @param density 屏幕密度
     * @return 可见行范围 (firstRow, lastRow)
     */
    fun calculateVisibleRows(
        scrollY: Int,
        viewportHeight: Int,
        columns: Int,
        containerWidth: Int,
        density: Float
    ): Pair<Int, Int> {
        val cellWidth = containerWidth / columns
        val labelHeight = (GRID_LABEL_HEIGHT_DP * density).toInt()
        val rowHeight = cellWidth + labelHeight

        val firstRow = (scrollY / rowHeight).coerceAtLeast(0)
        val visibleRows = (viewportHeight + rowHeight - 1) / rowHeight + 2
        val lastRow = firstRow + visibleRows - 1

        return Pair(firstRow, lastRow)
    }
}

/**
 * 网格布局数据类
 * 对应原版 GridEngine 的布局计算结果
 */
data class GridLayout(
    /** 单元格宽度 (px) */
    val cellWidth: Int,
    /** 单元格高度 (px) */
    val cellHeight: Int,
    /** 封面大小 (px) */
    val coverSize: Int,
    /** 封面左边距 (px) */
    val coverLeft: Int,
    /** 封面顶部位置 (px) */
    val coverTop: Int,
    /** 封面圆角 (px) */
    val cornerRadius: Float,
    /** 标题左边距 (px) */
    val titleLeft: Int,
    /** 标题顶部位置 (px) */
    val titleTop: Int,
    /** 标题宽度 (px) */
    val titleWidth: Int,
    /** 标题样式 */
    val titleStyle: TextStyle,
    /** 第二行左边距 (px) */
    val line2Left: Int,
    /** 第二行顶部位置 (px) */
    val line2Top: Int,
    /** 第二行宽度 (px) */
    val line2Width: Int,
    /** 第二行样式 */
    val line2Style: TextStyle,
    /** 在容器中的左边距 (px) */
    val left: Int,
    /** 在容器中的顶部位置 (px) */
    val top: Int
)
