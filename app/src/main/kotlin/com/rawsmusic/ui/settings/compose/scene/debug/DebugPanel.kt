package com.rawsmusic.ui.settings.compose.scene.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.ui.settings.compose.scene.state.ComposeListState
import com.rawsmusic.ui.settings.compose.scene.state.DualEngineState
import com.rawsmusic.ui.settings.compose.scene.state.SceneParamsRegistry

/**
 * 调试面板
 * 显示场景系统的状态信息
 */
@Composable
fun DebugPanel(
    listState: ComposeListState,
    dualEngineState: DualEngineState? = null,
    sceneParamsRegistry: SceneParamsRegistry? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.1f),
                        Color.White.copy(alpha = 0.05f)
                    )
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Text(
            text = "调试面板",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 列表状态
        DebugSection("列表状态") {
            DebugRow("当前场景", listState.currentScene.name)
            DebugRow("源场景", listState.sourceScene.name)
            DebugRow("目标场景", listState.targetScene.name)
            DebugRow("过渡进度", "%.1f%%".format(listState.transitionProgress * 100f))
            DebugRow("是否过渡中", if (listState.isTransitioning) "是" else "否")
            DebugRow("缩放因子", "%.3f".format(listState.transitionScaleFactor))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 缩放参数
        DebugSection("缩放参数") {
            DebugRow("封面大小", "${listState.currentParams.coverSizeDp.toInt()}dp")
            DebugRow("行高", "${listState.currentParams.rowHeightValue.toInt()}dp")
            DebugRow("文本缩放", "%.2fx".format(listState.currentParams.textScale))
            DebugRow("圆角", "${listState.currentParams.cornerRadiusTracksDp.toInt()}dp")
            DebugRow("第二行可见", if (listState.currentParams.line2Visible) "是" else "否")
            DebugRow("元数据可见", if (listState.currentParams.metaVisible) "是" else "否")
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 手势状态
        DebugSection("手势状态") {
            DebugRow("是否捏合中", if (listState.isPinching) "是" else "否")
            DebugRow("捏合速度", "%.1f dp/s".format(listState.pinchVelocity))
            DebugRow("待确认网格", if (listState.pendingGridZoom) "是" else "否")
            DebugRow("网格进度", "%.1f%%".format(listState.gridZoomProgress * 100f))
            DebugRow("边界弹性", if (listState.boundaryElasticActive) "是" else "否")
            DebugRow("弹性缩放", "%.3f".format(listState.boundaryElasticScale))
        }

        // 双引擎状态
        if (dualEngineState != null) {
            Spacer(modifier = Modifier.height(8.dp))

            DebugSection("双引擎状态") {
                DebugRow("是否双引擎过渡", if (dualEngineState.isDualEngineTransition) "是" else "否")
                DebugRow("源引擎列数", "${dualEngineState.sourceColumns}")
                DebugRow("目标引擎列数", "${dualEngineState.targetColumns}")
                DebugRow("过渡进度", "%.1f%%".format(dualEngineState.transitionProgress * 100f))
                DebugRow("缩放因子", "%.3f".format(dualEngineState.transitionScaleFactor))
                DebugRow("是否放大", if (dualEngineState.isZoomIn) "是" else "否")
            }
        }

        // SceneParams 注册表
        if (sceneParamsRegistry != null) {
            Spacer(modifier = Modifier.height(8.dp))

            DebugSection("SceneParams 注册表") {
                val viewIds = sceneParamsRegistry.getRegisteredViewIds()
                DebugRow("注册的 View 数量", "${viewIds.size}")
                viewIds.forEach { viewId ->
                    val scenes = sceneParamsRegistry.getRegisteredScenes(viewId)
                    DebugRow(viewId, scenes.joinToString(", ") { it.name })
                }
            }
        }
    }
}

/**
 * 调试区域
 */
@Composable
private fun DebugSection(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        color = Color.White.copy(alpha = 0.8f),
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium
    )

    Spacer(modifier = Modifier.height(4.dp))

    content()
}

/**
 * 调试行
 */
@Composable
private fun DebugRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp
        )
        Text(
            text = value,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}
