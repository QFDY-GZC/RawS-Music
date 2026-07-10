# RawS Music

<div align="center">

![Version](https://img.shields.io/badge/version-0.9.01--beta1-4c8bf5?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android-3ddc84?style=flat-square)
![MinSDK](https://img.shields.io/badge/minSdk-23-ff9800?style=flat-square)
![Stack](https://img.shields.io/badge/stack-Kotlin%20%2B%20C%2B%2B-7b61ff?style=flat-square)
![License](https://img.shields.io/badge/license-Apache--2.0-1677ff?style=flat-square)
![Release](https://img.shields.io/github/v/release/QFDY-GZC/RawS-Music?include_prereleases&style=flat-square)
![Downloads](https://img.shields.io/github/downloads/QFDY-GZC/RawS-Music/total?style=flat-square)
![Stars](https://img.shields.io/github/stars/QFDY-GZC/RawS-Music?style=flat-square)
![Forks](https://img.shields.io/github/forks/QFDY-GZC/RawS-Music?style=flat-square)
![Issues](https://img.shields.io/github/issues/QFDY-GZC/RawS-Music?style=flat-square)

面向本地音乐收藏、高音质播放与细致播放器体验持续打磨的 Android 音乐播放器。

[项目简述](#项目简述) | [核心功能](#核心功能) | [格式与媒体库支持](#格式与媒体库支持) | [开源范围](#当前开源范围) | [模块结构](#模块结构) | [技术栈](#技术栈) | [构建](#构建方式) | [贡献](#贡献)

</div>

> 当前仓库正持续整理为公开版本。UI、媒体库、歌单、歌词、扫描、常规播放框架、DSP 接线及大部分应用层代码均已开放；完整 USB 独占 Native 核心会拆分到独立 GPLv3 仓库维护。

## 项目简述

RawS Music 是面向本地音频收藏的 Android 音乐播放器。它不以流媒体聚合为目标，而是围绕媒体库组织、播放器交互、歌词、专辑封面、技术信息和 Hi-Res / DSD / USB DAC 等高音质播放场景持续完善体验。

项目主体采用 Kotlin 与 Jetpack Compose，结合 C++、FFmpeg、TagLib 和 AudioTrack 处理解码、元数据读取、技术信息补全及播放链路控制。除音质和播放控制外，项目也着重打磨专辑图加载、页面转场、沉浸式播放、逐字歌词、列表浏览效率和外接音频设备的使用体验。

## 核心功能

| 模块 | 详细能力 |
| --- | --- |
| 媒体库系统 | 歌曲、专辑、艺术家、专辑艺术家、作曲家、流派、年份、文件夹与歌单浏览；包含字母索引、PowerList、多级详情页和选择模式。 |
| 扫描与入库 | 手动、增量、两阶段与 SAF 文件夹扫描；前台扫描服务、进度反馈、稳定去重、标签补全、技术信息归一化和 ReplayGain 字段持久化。 |
| CUE 分轨 | 扫描阶段展开 CUE 分轨；播放进度、歌词、入库 key 与显示时间都保留轨道偏移信息。 |
| 播放与队列 | 完整播放器页、迷你播放栏、后台服务、播放队列、上一首/下一首、定位恢复、跨页状态同步和进度更新。 |
| 音频处理链 | Kotlin + C++ 组织 FFmpeg 桥接、格式探测、AudioTrack 输出、重采样决策、Gapless / Decoder handoff、Crossfade、PCM 写入和环形缓冲。 |
| DSP 与音效 | 图形均衡器、参数均衡器、压缩器、低音/高音增强、声场扩展、Stereo Widen、Surround 控制及统一 DSP pipeline。 |
| 歌词系统 | 歌词模型、Provider Bridge、逐行/逐字时间轴、CUE 整轨歌词裁切、状态栏歌词、蓝牙歌词桥接与播放器歌词页。 |
| 专辑图 | 内嵌图、外置图与文件夹封面解析；列表/播放器分层缓存、Coil 列表渲染、默认专辑图和 200ms 淡入表现。 |
| 界面与动画 | Compose、Miuix 与 backdrop 组织主界面、沉浸式播放器、液态玻璃、模糊层、专辑图转场、迷你播放栏和场景切换。 |
| USB 与 Hi-Res 路线 | 保留 USB 独占上层接入、能力建模、音量控制、恢复策略、DSD 支持判定和设备信息展示；完整底层核心将独立发布。 |

## 格式与媒体库支持

### 音频格式

| 类别 | 说明 |
| --- | --- |
| 常规格式 | MP3、FLAC、WAV、AAC、OGG、OPUS、M4A、ALAC、WMA、APE、AIFF。 |
| DSD / Hi-Res | DSF、DFF，以及围绕 Native DSD、PCM to DSD 与 USB DAC 的持续开发路线。 |
| 扩展扫描格式 | WV、TTA、TAK、MKA、MPC 与 CUE 已进入现有扫描、识别或解析路径。 |
| 标签识别 | 组合 MediaStore、TagLib 与 FFmpeg，补全标题、艺术家、专辑、作曲家、流派、年份、轨道号、声道、采样率、位深和码率。 |

### 元数据、封面与歌词

| 能力 | 说明 |
| --- | --- |
| 元数据 | 支持技术信息、ReplayGain、峰值、文件大小、编码格式、CUE 偏移和 DSD 判定等字段。 |
| 专辑图 | 支持内嵌图、外置图、文件夹图与播放器内置默认专辑图，并在列表、专辑页、播放器和迷你栏复用。 |
| 歌词来源 | 支持外部歌词、内嵌歌词与 Provider Bridge；CUE 专辑整轨歌词可按分轨裁切显示。 |
| 曲库组织 | 支持歌曲、专辑、艺术家、专辑艺术家、作曲家、流派、年份、文件夹和歌单维度。 |

## 当前开源范围

| 范围 | 状态 | 说明 |
| --- | --- | --- |
| `app/` | 已公开 | 应用入口、主要页面、设置、服务、帮助类与上层交互。 |
| `core/common` | 已公开 | 公共模型、偏好、工具、FFmpeg / TagLib 桥接与通用数据结构。 |
| `core/ui` | 已公开 | 主题、通用组件、播放器 UI、PowerList、场景切换和视觉效果。 |
| `module/data` | 已公开 | Room 数据库、实体、DAO、仓库和参数持久化。 |
| `module/player` | 已公开 | 常规播放、AudioTrack 输出、DSP、播放状态机与 USB 上层接入。 |
| `module/scanner` | 已公开 | 媒体扫描、两阶段扫描、SAF、标签整理、CUE 处理和索引更新。 |
| `lyric/` | 已公开 | 歌词模型、AIDL / Provider Bridge 与时间轴实现。 |
| `backdrop/` | 已公开 | 模糊、阴影、高光、液态玻璃与 RenderEffect 相关实现。 |
| USB 独占 Native 核心 | 暂不在本仓库 | 后续拆分为独立 GPLv3 仓库。 |

公开仓库会持续清理实验文件和内部分析产物，但不会将尚未拆分完成的 USB 独占底层直接混入 Apache-2.0 主仓库。

## 许可证说明

- `RawS-Music` 主仓库采用 Apache-2.0。
- 后续独立发布的 USB 独占核心计划采用 GPLv3。
- 第三方依赖与引用项目遵循各自许可证。

详见根目录 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

## 模块结构

```text
RawS-Music/
|- app/                  # 应用入口、页面、设置、服务与上层交互
|- core/common/          # 公共模型、桥接、基础工具和偏好
|- core/ui/              # 主题、组件、播放器 UI、列表与场景实现
|- module/data/          # 数据库、实体、DAO、仓库
|- module/player/        # 播放控制、常规输出、DSP 与播放基础设施
|- module/scanner/       # 扫描、解析、索引更新和同步
|- lyric/                # 歌词模型与 Provider Bridge
|- backdrop/             # 模糊、层叠和液态玻璃效果
`- docs/                 # 架构与设计文档
```

## 技术栈

| 类别 | 库 / 框架 | 用途 |
| --- | --- | --- |
| 语言与构建 | Kotlin、C++17、Gradle、CMake、Android NDK | Android 工程与 Native 音频桥接。 |
| UI | Jetpack Compose、Material 3、Miuix | 主界面、播放器、列表、设置和主题。 |
| 图形与视觉 | backdrop、RenderEffect、Canvas、Palette | 模糊、高光、液态玻璃和动态取色。 |
| 数据层 | Room、MMKV、Gson | 媒体库数据库、设置与对象序列化。 |
| 异步与状态 | Kotlin Coroutines、Flow、Lifecycle | 扫描、播放、页面状态与后台服务协作。 |
| 音频处理 | FFmpeg、AudioTrack、TagLib | 解码、标签读取、重采样和技术信息。 |
| 网络与歌词 | OkHttp、WebDAV 基础实现、Lyric Provider Bridge | 远程目录、后续同步与歌词桥接。 |

## 构建方式

### 环境要求

- Android Studio 最新稳定版
- JDK 21
- Android SDK，`compileSdk 37`，`minSdk 23`，`targetSdk 34`
- Android NDK
- CMake 3.22.1

### 克隆与安装

```powershell
git clone https://github.com/QFDY-GZC/RawS-Music.git
cd RawS-Music
.\gradlew.bat installRelease
```

仅验证编译可使用：

```powershell
.\gradlew.bat assembleDebug
```

部分 Native 能力依赖正确配置的 NDK 与 CMake。完整 USB 独占 Native 核心不在当前公开仓库中。

## 开发路线

1. 继续整理公开仓库目录、文档、构建说明与模块边界。
2. 持续改进媒体库扫描、CUE、专辑图、歌词和播放器交互。
3. 补充页面说明、截图和面向贡献者的技术文档。
4. 将 USB 独占核心拆分到单独的 GPLv3 仓库。

## 贡献

欢迎通过 Issue 或 Pull Request 参与改进。尤其欢迎以下方向的反馈：

- 媒体库、扫描、CUE 与专辑图加载体验。
- 播放器交互、动画、歌词和信息展示。
- 音频格式兼容、技术信息、DSP 与外接设备支持。
- 文档、本地化、构建流程与模块边界。

## 社群

- QQ 群：`1093312333`
- [RawS Music 交流群](https://qm.qq.com/q/P6Qxx7XzC8)

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=QFDY-GZC/RawS-Music&type=Date)](https://star-history.com/#QFDY-GZC/RawS-Music&Date)

## 访问统计

<div align="center">
  <img src="https://hits.seeyoufarm.com/api/count/incr/badge.svg?url=https%3A%2F%2Fgithub.com%2FQFDY-GZC%2FRawS-Music&count_bg=%233D7EFF&title_bg=%232C3440&icon=&icon_color=%23FFFFFF&title=views&edge_flat=false" alt="RawS Music 仓库访问统计" />
</div>

## 赞赏

如果项目对你有帮助，欢迎通过微信赞赏支持后续开发。

<div align="center">
  <img src="docs/assets/wechat-donate-qrcode.png" alt="RawS Music 微信赞赏码" width="320" />
</div>

## 致谢

- [FFmpeg](https://ffmpeg.org/)
- [TagLib](https://taglib.org/)
- [Coil](https://coil-kt.github.io/coil/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Miuix](https://github.com/YunZiA/HyperStar)
- [backdrop](https://github.com/nickkimk/backdrop)
- [OkHttp](https://square.github.io/okhttp/)

如果 RawS Music 对你有帮助，欢迎在 GitHub 上点一个 Star。
