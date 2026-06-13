package com.rawsmusic.ui.settings.compose.scene.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex

/**
 * 场景选择器
 * 对应原版 ComposePlayerDemo 的 SceneSelector
 *
 * 显示 SMALL/NORMAL/ZOOMED 三个场景按钮
 */
@Composable
fun SceneSelector(
    currentScene: ListZoomIndex,
    onSceneSelected: (ListZoomIndex) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ListZoomIndex.entries.forEach { scene ->
            val isActive = scene == currentScene
            SceneButton(
                scene = scene,
                isActive = isActive,
                onClick = { onSceneSelected(scene) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 场景按钮
 */
@Composable
private fun SceneButton(
    scene: ListZoomIndex,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isActive) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.02f)
                        )
                    )
                }
            )
            .border(
                width = if (isActive) 1.dp else 0.dp,
                color = Color.White.copy(alpha = if (isActive) 0.3f else 0f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = when (scene) {
                ListZoomIndex.SMALL -> "小"
                ListZoomIndex.NORMAL -> "标准"
                ListZoomIndex.ZOOMED -> "大"
            },
            color = if (isActive) Color.White else Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * 列数选择器
 * 对应原版的列数选择按钮
 */
@Composable
fun ColumnSelector(
    currentColumns: Int,
    onColumnSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        listOf(1, 2, 3, 4).forEach { columns ->
            val isActive = columns == currentColumns
            ColumnButton(
                columns = columns,
                isActive = isActive,
                onClick = { onColumnSelected(columns) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 列数按钮
 */
@Composable
private fun ColumnButton(
    columns: Int,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isActive) {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.05f)
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.06f),
                            Color.White.copy(alpha = 0.02f)
                        )
                    )
                }
            )
            .border(
                width = if (isActive) 1.dp else 0.dp,
                color = Color.White.copy(alpha = if (isActive) 0.3f else 0f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${columns}列",
            color = if (isActive) Color.White else Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
        )
    }
}
