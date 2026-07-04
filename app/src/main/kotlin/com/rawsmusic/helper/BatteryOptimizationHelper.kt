package com.rawsmusic.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class BatteryOptimizationHelper(
    private val activity: Activity,
    private val onVisibilityChanged: (Boolean) -> Unit = {}
) {
    var isShowing by mutableStateOf(false)
        private set

    fun promptWhitelistForUsbExclusive() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val powerManager = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(activity.packageName)) return

        activity.runOnUiThread {
            isShowing = true
            onVisibilityChanged(true)
        }
    }

    fun dismiss() {
        if (!isShowing) return
        isShowing = false
        onVisibilityChanged(false)
    }

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${activity.packageName}")
                )
            } else {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            }
            activity.startActivity(intent)
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(activity, "无法打开电池优化设置", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun confirmOpenSettings() {
        openBatteryOptimizationSettings()
        dismiss()
    }
}

@Composable
fun BatteryOptimizationOverlay(
    helper: BatteryOptimizationHelper,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = helper.isShowing,
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x99000000))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { helper.dismiss() }
                ),
            contentAlignment = Alignment.Center
        ) {
            AnimatedVisibility(
                visible = helper.isShowing,
                enter = fadeIn(tween(140)) + scaleIn(tween(180), initialScale = 0.95f),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.98f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xF21B1816))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {}
                        )
                        .padding(horizontal = 22.dp, vertical = 20.dp)
                ) {
                    Text(
                        text = "后台播放保护",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "USB 独占模式需要持续运行。\n\n为避免系统在后台暂停播放，请将本应用加入电池优化白名单（\"不限制后台行为\"）。",
                        color = Color.White.copy(alpha = 0.78f),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                    RowActionBar(
                        positive = "前往设置",
                        negative = "暂不",
                        onPositive = { helper.confirmOpenSettings() },
                        onNegative = { helper.dismiss() }
                    )
                }
            }
        }
    }
}

@Composable
private fun RowActionBar(
    positive: String,
    negative: String,
    onPositive: () -> Unit,
    onNegative: () -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = negative,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 14.sp,
            modifier = Modifier
                .clickable(onClick = onNegative)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = positive,
            color = Color(0xFF4CAF50),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clickable(onClick = onPositive)
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}
