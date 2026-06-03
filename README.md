# RawSMusic

<div align="center">

![Version](https://img.shields.io/badge/version-1.1.0--alpha1-blue.svg)
![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![MinSDK](https://img.shields.io/badge/minSDK-24-orange.svg)
![License](https://img.shields.io/badge/license-GPL--3.0-red.svg)

**一款追求极致音质与视觉体验的 Android 音乐播放器**

[功能特性](#功能特性) • [技术架构](#技术架构) • [截图预览](#截图预览) • [编译构建](#编译构建)

</div>

---

## 功能特性

### 🎵 音频播放
- **多格式支持**：MP3, FLAC, WAV, AAC, OGG, OPUS, APE, M4A, WMA 等主流格式
- **Hi-Res Audio**：支持 24bit/192kHz 及更高采样率
- **USB DAC**：独占模式支持，自动检测 USB 音频设备
- **蓝牙延迟补偿**：实时检测蓝牙链路延迟，自动同步歌词

### 🎛️ DSP 音效引擎
- **参数均衡器 (PEQ)**：10 段可调参数均衡器，支持自定义频点
- **立体声扩展**：增强声场宽度
- **交叉馈送 (Crossfeed)**：模拟扬声器听感，减少耳机疲劳
- **前置放大**：自动增益补偿
- **ARM NEON 优化**：SIMD 向量化处理，CPU 占用极低

### 📝 歌词系统
- **多格式解析**：LRC, KRC, QRC, KRC2
- **逐字歌词**：平滑插值动画，Choreographer 帧级同步
- **双语显示**：原文 + 翻译双行显示
- **状态栏歌词**：通知栏实时显示当前歌词
- **防断行保护**：智能处理歌词换行，避免语义断裂

### 🎨 液态玻璃 UI
- **Backdrop 模糊引擎**：26dp 高斯模糊 + Vibrancy 鲜艳度增强
- **透镜效果 (Lens)**：色散模拟，产生棱镜彩虹边缘
- **三层叠加导航栏**：参考 AndroidLiquidGlass 实现
- **弹性拖动动画**：DampedDrag 阻尼动画，物理感十足
- **深色/浅色主题**：完整 Material You 配色方案

### ⏰ 实用功能
- **睡眠定时器**：定时停止播放
- **播完当前停止**：当前歌曲结束后自动停止
- **播放速度调节**：0.5x ~ 2.0x 变速播放
- **WebDAV 支持**：远程音乐库访问
- **媒体库管理**：歌曲、专辑、艺术家分类

---

## 技术架构

```
RawSMusic/
├── app/                    # 主应用模块
│   ├── src/main/cpp/       # Native C++ 代码
│   │   ├── dsp_engine.cpp  # DSP 音效引擎 (NEON 优化)
│   │   ├── ffmpeg_bridge.cpp # FFmpeg 解码桥接
│   │   └── usb_audio_engine.cpp # USB DAC 引擎
│   └── src/main/kotlin/    # Kotlin 业务代码
├── core/
│   ├── common/             # 公共工具类
│   └── ui/                 # UI 组件库
│       └── widget/         # 自定义 View (液态玻璃胶囊栏等)
├── module/
│   ├── data/               # 数据层 (Room + LitePal)
│   ├── player/             # 播放控制器
│   └── scanner/            # 媒体扫描器
├── lyric/                  # 歌词解析与渲染
├── backdrop/               # 液态玻璃效果库
└── tools/                  # 构建工具
    └── ffmpeg_build/       # FFmpeg 编译脚本
```

### 核心技术栈

| 类别 | 技术 |
|------|------|
| 语言 | Kotlin, C++17 |
| UI 框架 | Jetpack Compose, ViewBinding |
| 音频解码 | FFmpeg 6.0 |
| 音频输出 | AudioTrack (含 Exclusive Mode) |
| 数据库 | Room, LitePal |
| 依赖注入 | 手动 DI |
| 异步 | Kotlin Coroutines, Flow |
| 图形 | RenderEffect, Canvas, backdrop 库 |
| SIMD | ARM NEON Intrinsics |

---

## 截图预览

> 截图待补充

---

## 编译构建

### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高
- JDK 17
- NDK r25c
- CMake 3.22.1

### 构建步骤

```bash
# 1. 克隆仓库
git clone https://github.com/your-username/RawSMusic.git
cd RawSMusic

# 2. 编译 FFmpeg (可选，项目已包含预编译库)
# cd tools/ffmpeg_build
# ./build.sh

# 3. 构建 APK
./gradlew assembleDebug
```

### 签名配置

在 `app/build.gradle.kts` 中配置签名：

```kotlin
android {
    signingConfigs {
        create("release") {
            storeFile = file("your-keystore.jks")
            storePassword = "your-store-password"
            keyAlias = "your-key-alias"
            keyPassword = "your-key-password"
        }
    }
}
```

---

## 性能优化

### DSP 引擎 NEON 向量化

```cpp
// BiQuad IIR 滤波器 NEON 优化
void BiQuad::processStereoNeon(float* left, float* right, size_t frames) {
    float32x4_t b0 = vdupq_n_f32(m_b0);
    float32x4_t b1 = vdupq_n_f32(m_b1);
    // ... SIMD 批量处理
}
```

### 启动优化
- 蓝牙检测异步化：3 秒 → 0 秒
- 协程延迟初始化
- ViewBinding 按需加载

---

## 致谢

- [FFmpeg](https://ffmpeg.org/) - 音视频解码
- [backdrop](https://github.com/nickkimk/backdrop) - 液态玻璃效果
- [AndroidLiquidGlass](https://github.com/nickkimk/AndroidLiquidGlass) - UI 参考
- [Coil](https://coil-kt.github.io/coil/) - 图片加载

---

## 许可证

```
RawSMusic - Android Music Player
Copyright (C) 2024 RawSMusic Team

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.
```

---

<div align="center">

**⭐ 如果这个项目对你有帮助，请给一个 Star ⭐**

</div>
