package com.rawsmusic.core.ui.scene.pages

import androidx.compose.runtime.Composable
import com.rawsmusic.core.ui.scene.NavScene

@Composable
fun SettingsRootPage(
    onBack: () -> Unit,
    onNavigate: (NavScene) -> Unit
) {
    SettingsPage(title = "设置", onBack = onBack) {
        SectionHeader("媒体库")
        SettingsCard {
            SettingsNavigationEntry(
                title = "扫描设置",
                description = "音乐文件夹、重新扫描、短曲过滤、播放次数和进度恢复",
                onClick = { onNavigate(NavScene.SCAN_SETTINGS) }
            )
        }

        SectionHeader("外观")
        SettingsCard {
            SettingsNavigationEntry(
                title = "外观设置",
                description = "主题模式、界面色彩与显示风格",
                onClick = { onNavigate(NavScene.APPEARANCE) }
            )
            SettingsNavigationEntry(
                title = "播放器界面",
                description = "默认背景、沉浸模式、常驻封面、音频可视化",
                onClick = { onNavigate(NavScene.PLAYER_INTERFACE) }
            )
            SettingsNavigationEntry(
                title = "专辑图",
                description = "画质、高清封面、封面下载与动画",
                onClick = { onNavigate(NavScene.ALBUM_ART_SETTINGS) }
            )
            SettingsNavigationEntry(
                title = "全局字体",
                description = "字体大小、字重、斜体与全局显示",
                onClick = { onNavigate(NavScene.GLOBAL_FONT_SETTINGS) }
            )
        }

        SectionHeader("音频")
        SettingsCard {
            SettingsNavigationEntry(
                title = "音频设置",
                description = "采样率、位深、输出模式与重采样",
                onClick = { onNavigate(NavScene.AUDIO_SETTINGS) }
            )
            SettingsNavigationEntry(
                title = "音频效果",
                description = "均衡器、压缩器、低音增强、空间音效",
                onClick = { onNavigate(NavScene.AUDIO_EFFECTS) }
            )
            SettingsNavigationEntry(
                title = "USB DAC",
                description = "USB 独占、DAC 状态、PCM 输出与 DSD",
                onClick = { onNavigate(NavScene.USB_DAC_SETTINGS) }
            )
        }

        SectionHeader("歌词")
        SettingsCard {
            SettingsNavigationEntry(
                title = "歌词管理",
                description = "歌词源、歌词字体设置与歌词显示",
                onClick = { onNavigate(NavScene.LYRIC_MANAGEMENT) }
            )
            SettingsNavigationEntry(
                title = "状态栏歌词",
                description = "Flyme、三星、蓝牙、Lyric Getter",
                onClick = { onNavigate(NavScene.STATUS_BAR_LYRIC) }
            )
            SettingsNavigationEntry(
                title = "歌词字体",
                description = "歌词字体大小、字重与显示样式",
                onClick = { onNavigate(NavScene.LYRIC_FONT_SETTINGS) }
            )
        }

        SectionHeader("数据")
        SettingsCard {
            SettingsNavigationEntry(
                title = "WebDAV 备份",
                description = "备份与恢复歌单、统计数据和应用配置",
                onClick = { onNavigate(NavScene.WEBDAV_BACKUP) }
            )
            SettingsNavigationEntry(
                title = "日志分析",
                description = "查看运行日志与导出问题信息",
                onClick = { onNavigate(NavScene.LOG_VIEWER) }
            )
        }

        SectionHeader("其他")
        SettingsCard {
            SettingsNavigationEntry(
                title = "关于 RawS Music",
                description = "版本、核心功能、项目主页与开源组件",
                onClick = { onNavigate(NavScene.ABOUT) }
            )
        }
    }
}
