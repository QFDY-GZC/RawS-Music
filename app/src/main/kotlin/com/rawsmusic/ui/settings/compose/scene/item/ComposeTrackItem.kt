package com.rawsmusic.ui.settings.compose.scene.item

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState
import com.rawsmusic.ui.settings.compose.scene.state.ItemLayoutCalculator
import com.rawsmusic.ui.settings.compose.scene.state.lerpItemLayout

/**
 * 纯 Compose 版本的列表项
 * 替代原版 AAItemView
 *
 * 实现双槽位布局计算和渲染
 */
@Composable
fun ComposeTrackItem(
    title: String,
    artist: String,
    duration: String,
    state: ComposeListState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density

    // 计算两组布局（双引擎核心）
    val sourceLayout = remember(state.sourceParams, density) {
        ItemLayoutCalculator.calculateListLayout(
            params = state.sourceParams,
            containerWidth = 1080, // 默认宽度，实际由容器决定
            density = density
        )
    }

    val targetLayout = remember(state.targetParams, density, state.isTransitioning) {
        if (state.isTransitioning) {
            ItemLayoutCalculator.calculateListLayout(
                params = state.targetParams,
                containerWidth = 1080,
                density = density
            )
        } else {
            sourceLayout
        }
    }

    // 插值后的布局
    val renderLayout = remember(sourceLayout, targetLayout, state.transitionProgress, state.isTransitioning) {
        if (state.isTransitioning) {
            lerpItemLayout(sourceLayout, targetLayout, state.transitionProgress)
        } else {
            sourceLayout
        }
    }

    // 使用 Layout composable 实现自定义布局
    val measurePolicy = MeasurePolicy { measurables, constraints ->
        // 使用 renderLayout 的参数进行测量和布局
        val rowWidth = renderLayout.rowWidth.coerceAtLeast(constraints.minWidth)
        val rowHeight = renderLayout.rowHeight.coerceAtLeast(constraints.minHeight)

        // 测量所有子元素
        val placeables = measurables.mapIndexed { index, measurable ->
            when (index) {
                0 -> {
                    // 封面
                    measurable.measure(
                        Constraints.fixed(
                            width = renderLayout.coverSize.coerceAtLeast(1),
                            height = renderLayout.coverSize.coerceAtLeast(1)
                        )
                    )
                }
                1 -> {
                    // 标题
                    measurable.measure(
                        Constraints.fixedWidth(
                            width = renderLayout.titleWidth.coerceAtLeast(1)
                        )
                    )
                }
                2 -> {
                    // 第二行
                    if (renderLayout.line2Visible) {
                        measurable.measure(
                            Constraints.fixedWidth(
                                width = renderLayout.line2Width.coerceAtLeast(1)
                            )
                        )
                    } else {
                        measurable.measure(Constraints.fixed(0, 0))
                    }
                }
                3 -> {
                    // 元数据
                    if (renderLayout.metaVisible && !renderLayout.metaInline) {
                        measurable.measure(
                            Constraints.fixedWidth(
                                width = renderLayout.metaWidth.coerceAtLeast(1)
                            )
                        )
                    } else {
                        measurable.measure(Constraints.fixed(0, 0))
                    }
                }
                else -> {
                    measurable.measure(constraints)
                }
            }
        }

        layout(rowWidth, rowHeight) {
            // 放置封面
            placeables.getOrNull(0)?.placeRelative(
                x = renderLayout.coverLeft,
                y = renderLayout.coverTop
            )

            // 放置标题
            placeables.getOrNull(1)?.placeRelative(
                x = renderLayout.titleLeft,
                y = renderLayout.titleTop
            )

            // 放置第二行
            if (renderLayout.line2Visible) {
                placeables.getOrNull(2)?.placeRelative(
                    x = renderLayout.line2Left,
                    y = renderLayout.line2Top
                )
            }

            // 放置元数据
            if (renderLayout.metaVisible && !renderLayout.metaInline) {
                placeables.getOrNull(3)?.placeRelative(
                    x = renderLayout.metaLeft,
                    y = renderLayout.metaTop
                )
            }
        }
    }

    Layout(
        content = {
            // 封面
            TrackCover(
                size = renderLayout.coverSize,
                cornerRadius = renderLayout.cornerRadius
            )

            // 标题
            Text(
                text = title,
                style = renderLayout.titleStyle,
                color = Color.White,
                maxLines = 1
            )

            // 第二行 (艺术家)
            if (renderLayout.line2Visible) {
                Text(
                    text = artist,
                    style = renderLayout.line2Style,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }

            // 元数据 (时长)
            if (renderLayout.metaVisible && !renderLayout.metaInline) {
                Text(
                    text = duration,
                    style = renderLayout.metaStyle,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 1
                )
            }
        },
        modifier = modifier,
        measurePolicy = measurePolicy
    )
}

/**
 * 封面组件
 */
@Composable
private fun TrackCover(
    size: Int,
    cornerRadius: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val sizeDp = (size / density).dp
    val cornerRadiusDp = (cornerRadius / density).dp

    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(RoundedCornerShape(cornerRadiusDp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color(0xFF8B5E3C),
                        Color(0xFF3C5E8B)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(cornerRadiusDp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "♪",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = (sizeDp.value * 0.3f).sp
        )
    }
}
