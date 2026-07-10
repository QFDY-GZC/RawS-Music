package com.rawsmusic.core.ui.scene.pages

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val PROJECT_URL = "https://github.com/QFDY-GZC/RawS-Music"

@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val colors = themeColors()
    val versionName = remember(context) { context.rawSMusicVersionName() }

    SettingsPage(title = "关于", onBack = onBack) {
        AboutHeroCard(versionName = versionName, colors = colors)

        SectionHeader("核心功能")
        SettingsCard {
            FeatureItem("高解析本地播放", "面向本地音乐库的播放体验，支持歌曲、专辑、艺术家、文件夹、队列和日常推荐等常用入口。", colors)
            CardDivider(colors)
            FeatureItem("FFmpeg 解码与高兼容音频输出", "使用 FFmpeg 解析多种音频格式，并通过原生播放链路输出，兼顾格式兼容性和播放稳定性。", colors)
            CardDivider(colors)
            FeatureItem("USB DAC 独占输出", "为外接 USB DAC 准备的独占输出路径，支持常规 DSP 处理，也可在完美比特模式下保持直通。", colors)
            CardDivider(colors)
            FeatureItem("Native DSP 与音效系统", "包含参量均衡器、前级增益、低音/高音、压缩器、交叉馈送、立体声扩展和空间/环绕相关音效。", colors)
            CardDivider(colors)
            FeatureItem("歌词与状态栏歌词", "支持内嵌/本地歌词、逐字歌词、翻译、罗马音，以及 Lyricon 词幕、Flyme 状态栏歌词、Lyric Getter 和蓝牙车载歌词桥接。", colors)
            CardDivider(colors)
            FeatureItem("高清专辑图与沉浸播放界面", "优先读取当前音频内嵌封面，支持高清专辑图、动态背景、共享元素动画和沉浸式播放页。", colors)
            CardDivider(colors)
            FeatureItem("Miuix / Monet 外观与备份", "提供 Miuix 风格界面、Material You 动态取色、全局/歌词字体设置，以及 WebDAV 备份相关能力。", colors)
        }

        SectionHeader("项目")
        SettingsCard {
            LinkItem("项目主页", PROJECT_URL, colors) { uriHandler.openUri(PROJECT_URL) }
            CardDivider(colors)
            InfoItem("开发者", "QFDY-GZC", colors)
            CardDivider(colors)
            InfoItem("定位", "专注本地音乐、高音质输出和高度可定制界面的 Android 音乐播放器", colors)
        }

        SectionHeader("开源组件")
        SettingsCard {
            OpenSourceItem("FFmpeg", "音频解码与格式解析", colors)
            CardDivider(colors)
            OpenSourceItem("libusb", "USB DAC 访问与独占输出基础能力", colors)
            CardDivider(colors)
            OpenSourceItem("TagLib / JAudioTagger", "音频标签、元数据与封面读取", colors)
            CardDivider(colors)
            OpenSourceItem("Miuix", "MIUI 风格 Compose UI 组件与主题能力", colors)
            CardDivider(colors)
            OpenSourceItem("Lyricon", "状态栏歌词与结构化歌词显示能力", colors)
            CardDivider(colors)
            OpenSourceItem("AndroidX / Kotlin Coroutines", "应用基础架构、Compose 与异步任务支持", colors)
        }
    }
}

@Composable
private fun AboutHeroCard(versionName: String, colors: ThemeColors) {
    SettingsCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(colors.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text("R", color = colors.onPrimaryContainer, fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = appFontFamily())
            }
            Spacer(Modifier.height(14.dp))
            Text("RawS Music", color = colors.onSurface, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, fontFamily = appFontFamily())
            Text("版本 $versionName", color = colors.secondaryText, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp), fontFamily = appFontFamily())
            Text(
                "为本地音乐、高清封面、状态栏歌词、USB DAC 和 Native DSP 打造的音乐播放器。",
                color = colors.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 14.dp, start = 8.dp, end = 8.dp), fontFamily = appFontFamily()
            )
        }
    }
}

@Composable
private fun FeatureItem(title: String, description: String, colors: ThemeColors) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 7.dp).size(7.dp).clip(CircleShape).background(colors.primary))
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.onSurface, fontFamily = appFontFamily())
            Text(description, fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp), fontFamily = appFontFamily())
        }
    }
}

@Composable
private fun LinkItem(title: String, description: String, colors: ThemeColors, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.onSurface, fontFamily = appFontFamily())
            Text(description, fontSize = 12.sp, color = colors.secondaryText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp), fontFamily = appFontFamily())
        }
        Text("›", color = colors.secondaryText, fontSize = 26.sp, modifier = Modifier.padding(start = 10.dp), fontFamily = appFontFamily())
    }
}

@Composable
private fun InfoItem(title: String, description: String, colors: ThemeColors) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(title, fontSize = 15.sp, color = colors.onSurface, fontFamily = appFontFamily())
        Text(description, fontSize = 13.sp, lineHeight = 19.sp, color = colors.secondaryText, modifier = Modifier.padding(top = 3.dp), fontFamily = appFontFamily())
    }
}

@Composable
private fun OpenSourceItem(name: String, description: String, colors: ThemeColors) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface, fontFamily = appFontFamily())
        Text(description, fontSize = 13.sp, lineHeight = 19.sp, color = colors.secondaryText, modifier = Modifier.padding(top = 3.dp), fontFamily = appFontFamily())
    }
}

@Composable
private fun CardDivider(colors: ThemeColors) {
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outline.copy(alpha = 0.12f)))
    Spacer(Modifier.height(12.dp))
}

private fun Context.rawSMusicVersionName(): String {
    return runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "未知版本"
    }.getOrDefault("未知版本")
}
