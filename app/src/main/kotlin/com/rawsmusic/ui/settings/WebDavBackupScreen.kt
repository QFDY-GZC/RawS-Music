package com.rawsmusic.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.WebDavBackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WebDavBackupScreen(onBack: () -> Unit) {
    
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var isBackingUp by remember { mutableStateOf(false) }
    var isRestoring by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("") }
    var lastBackupTime by remember { mutableStateOf(AppPreferences.WebDav.lastUrl) }

    val isConfigured = AppPreferences.WebDav.url.isNotBlank()

    SettingsPage(title = "WebDAV 备份", onBack = onBack) {
        SettingsCard {
            Text(
                "备份与恢复",
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onBackground,
                fontFamily = appFontFamily()
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "将歌单和听歌统计数据备份到 WebDAV 服务器，或在其他设备上恢复。",
                fontSize = 14.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontFamily = appFontFamily()
            )

            if (!isConfigured) {
                Spacer(Modifier.height(16.dp))
                Text(
                    "⚠ 未配置 WebDAV，请先在侧边栏「WebDAV」中配置连接信息。",
                    fontSize = 13.sp,
                    color = Color.White,
                    fontFamily = appFontFamily()
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .then(
                            if (isConfigured && !isBackingUp) {
                                Modifier.padding(0.dp)
                            } else Modifier
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextButton(
                        onClick = {
                            if (isBackingUp) return@TextButton
                            isBackingUp = true
                            statusMessage = ""
                            scope.launch {
                                val result = WebDavBackupManager.backup(context)
                                isBackingUp = false
                                statusMessage = result.getOrElse { "备份失败：${it.message}" }
                            }
                        },
                        enabled = isConfigured && !isBackingUp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isBackingUp) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MiuixTheme.colorScheme.primary
                            )
                            Spacer(Modifier.padding(start = 8.dp))
                        }
                        Text(
                            if (isBackingUp) "备份中…" else "备份到 WebDAV",
                            color = if (isConfigured && !isBackingUp) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                            fontSize = 15.sp,
                            fontFamily = appFontFamily()
                        )
                    }
                }

                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TextButton(
                        onClick = {
                            if (isRestoring) return@TextButton
                            isRestoring = true
                            statusMessage = ""
                            scope.launch {
                                val result = WebDavBackupManager.restore(context)
                                isRestoring = false
                                statusMessage = result.getOrElse { "恢复失败：${it.message}" }
                            }
                        },
                        enabled = isConfigured && !isRestoring,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isRestoring) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MiuixTheme.colorScheme.primary
                            )
                            Spacer(Modifier.padding(start = 8.dp))
                        }
                        Text(
                            if (isRestoring) "恢复中…" else "从 WebDAV 恢复",
                            color = if (isConfigured && !isRestoring) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.outline,
                            fontSize = 15.sp,
                            fontFamily = appFontFamily()
                        )
                    }
                }
            }
        }

        if (statusMessage.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            SettingsCard {
                Text(
                    statusMessage,
                    fontSize = 14.sp,
                    color = if (statusMessage.contains("成功")) Color(0xFF2E7D32) else Color(0xFFC62828),
                    fontFamily = appFontFamily()
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SettingsCard {
            SectionHeader("WebDAV 配置信息")
            Spacer(Modifier.height(4.dp))
            ConfigInfoRow("地址", AppPreferences.WebDav.url.ifBlank { "未配置" })
            ConfigInfoRow("用户名", AppPreferences.WebDav.username.ifBlank { "未配置" })
            ConfigInfoRow("认证方式", when (AppPreferences.WebDav.authMode) {
                1 -> "Basic"
                2 -> "Digest"
                else -> "自动"
            })
        }
    }
}

@Composable
private fun ConfigInfoRow(label: String, value: String) {
    
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            fontFamily = appFontFamily()
        )
        Text(
            value,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onBackground,
            fontFamily = appFontFamily()
        )
    }
}
