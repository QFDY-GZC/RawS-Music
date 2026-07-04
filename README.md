# RawS Music

<div align="center">

![Version](https://img.shields.io/badge/version-0.9.01--beta1-4c8bf5?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android-3ddc84?style=flat-square)
![MinSDK](https://img.shields.io/badge/minSdk-23-ff9800?style=flat-square)
![Stack](https://img.shields.io/badge/stack-Kotlin%20%2B%20C%2B%2B-7b61ff?style=flat-square)
![License](https://img.shields.io/badge/license-Apache--2.0-1677ff?style=flat-square)
![Stars](https://img.shields.io/github/stars/QFDY-GZC/RawS-Music?style=flat-square)
![Views](https://hits.seeyoufarm.com/api/count/incr/badge.svg?url=https%3A%2F%2Fgithub.com%2FQFDY-GZC%2FRawS-Music&count_bg=%233D7EFF&title_bg=%232C3440&icon=&icon_color=%23FFFFFF&title=views&edge_flat=false)
[![QQ Group](https://img.shields.io/badge/QQ群-1093312333-12B7F5?style=flat-square)](https://qm.qq.com/q/P6Qxx7XzC8)

面向本地音乐收藏、高音质播放与播放器界面质感持续打磨的 Android 音乐播放器。

[项目说明](#项目说明) • [主要能力](#主要能力) • [当前开源范围](#当前开源范围) • [模块结构](#模块结构) • [构建方式](#构建方式) • [Star History](#star-history) • [QQ群](https://qm.qq.com/q/P6Qxx7XzC8) • [赞赏](#赞赏)

</div>

> 当前仓库正在持续整理为公开版本。UI、媒体库、歌词、扫描、常规播放框架和大部分应用层代码已开放；完整的 USB 独占核心暂不包含在本仓库中，后续会拆分为独立仓库并采用 GPLv3。

## 项目说明

RawS Music 是一个以本地音乐播放为中心的 Android 音乐播放器项目，重点不在流媒体聚合，而在本地媒体库组织、播放器交互体验、歌词系统、专辑视图、外接音频设备支持方向，以及较完整的音频链路可视化表达。

这个项目的目标很直接：把“听本地音乐”这件事做得更完整一些。它既关注播放器本身的音质与播放控制，也关注专辑封面、页面过渡、沉浸式播放界面、歌词细节、媒体库浏览效率，以及外部音频设备接入时的使用体验。

和很多只做基础播放功能的播放器不同，RawS Music 长期在几个方向上同时推进：

- 本地媒体库、文件夹、专辑、艺术家与播放列表的组织效率
- 高质感播放器界面、沉浸式布局与细节动画
- 歌词显示、逐字同步、状态栏歌词与相关信息展示
- Kotlin 与 C++ 混合架构下的音频处理、FFmpeg 桥接与 DSP 能力
- 面向 Hi-Res、DSD、USB DAC 场景的产品路线

## 主要能力

| 方向 | 说明 |
| --- | --- |
| 本地媒体库 | 围绕歌曲、专辑、艺术家、文件夹与列表视图组织本地音频资源，持续优化扫描、索引与浏览效率 |
| 播放体验 | 提供完整播放器界面、迷你播放栏、沉浸式播放页、队列控制、后台播放与进度同步 |
| 歌词系统 | 包含歌词模型、Bridge、逐字/逐行显示基础能力，以及面向状态栏歌词与播放器歌词页的接入 |
| 音频处理 | 项目包含 FFmpeg 桥接、常规播放框架、DSP 接线与部分 Native 音频处理能力 |
| 界面系统 | 基于 Compose、Miuix 与自定义 backdrop / liquid glass 风格组件持续打磨播放器体验 |
| 工程结构 | 采用模块化组织，按 UI、数据、扫描、播放、歌词与视觉效果拆分，便于持续演进与后续公开整理 |

## 当前开源范围

目前这个 GitHub 仓库会优先公开适合稳定维护、可单独协作、边界相对清晰的部分。

| 范围 | 状态 | 说明 |
| --- | --- | --- |
| `app/` 应用入口与页面层 | 已公开 | 包含主应用入口、主要页面、设置页、服务与上层交互逻辑 |
| `core/common` | 已公开 | 公共模型、偏好设置、桥接层、工具类等 |
| `core/ui` | 已公开 | 主题、通用组件、播放器与列表相关 UI、场景组织能力 |
| `module/data` | 已公开 | 数据库、实体、DAO、仓库等数据层代码 |
| `module/scanner` | 已公开 | 媒体扫描、元数据整理、索引更新与相关基础设施 |
| `lyric/` | 已公开 | 歌词模型与 Provider Bridge |
| `backdrop/` | 已公开 | 与界面玻璃感、模糊、层叠效果有关的可复用实现 |
| USB 独占上层接入与设置相关代码 | 持续整理 | 公开仓库会保留必要的产品层结构，但不会在当前阶段附带完整底层内核 |
| USB 独占 Native 核心 | 暂不在本仓库 | 计划拆分为独立仓库，采用 GPLv3 许可证单独维护 |

如果你现在看到公开仓库里只有部分内容，这是预期状态。这个仓库会按模块持续补充、整理和清理历史实验文件，但不会把尚未拆分完成的 USB 独占核心直接混放进 Apache-2.0 主仓库。

## 许可证说明

当前许可证策略如下：

- 本仓库 `RawS-Music`：采用 Apache-2.0
- 后续独立发布的 USB 独占核心仓库：计划采用 GPLv3
- 第三方依赖与引用项目：继续遵循各自原始许可证

这样拆分的目的，是把适合公开协作的通用播放器主体与仍在快速迭代、硬件耦合较强的 USB 独占核心分开维护，方便后续分别演进、审查和发布。

更多说明见根目录 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。

## 模块结构

```text
RawS-Music/
├─ app/                  # 应用入口、页面、设置、服务与上层交互
├─ core/common/          # 公共模型、桥接、基础工具、偏好设置
├─ core/ui/              # 主题、组件、播放器 UI、列表与场景相关实现
├─ module/data/          # 数据库、实体、DAO、仓库
├─ module/player/        # 播放控制、常规输出、DSP 接线与播放基础设施
├─ module/scanner/       # 扫描、解析、索引更新、同步相关能力
├─ lyric/                # 歌词模型与 Provider Bridge
├─ backdrop/             # 模糊、层叠、液态玻璃风格效果
└─ docs/                 # 架构分析、设计记录与整理中的文档
```

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 语言 | Kotlin, C++17 |
| UI | Jetpack Compose, Miuix |
| Native / 音频桥接 | CMake, NDK, FFmpeg |
| 数据层 | Room, LitePal |
| 异步 | Kotlin Coroutines, Flow |
| 图形与视觉 | RenderEffect, Canvas, backdrop |
| 工程形态 | 多模块 Android 工程 |

## 构建方式

### 环境要求

- Android Studio 最新稳定版
- JDK 21
- Android SDK
- Android NDK
- CMake 3.22.1

### 克隆与安装

```powershell
git clone https://github.com/QFDY-GZC/RawS-Music.git
cd RawS-Music
.\gradlew.bat installRelease
```

如果你只想先验证能否编译，也可以使用：

```powershell
.\gradlew.bat assembleDebug
```

说明：

- 部分 Native 相关能力依赖本地 NDK / CMake 环境
- 当前公开仓库处于持续整理阶段，个别目录、脚本与文档仍会继续调整
- 与 USB 独占完整底层相关的发布内容不在当前仓库内

## 开发路线

接下来会继续推进几件事情：

1. 继续整理公开仓库的目录结构、文档和构建说明
2. 补充公开版本下更完整的页面说明、截图与模块边界文档
3. 将 USB 独占核心抽离为单独仓库，并按 GPLv3 发布
4. 逐步把实验性质较强、仅用于内部分析的内容与主仓库维护边界分开

## 贡献

欢迎通过 Issue 或 Pull Request 参与改进。当前阶段尤其欢迎以下方向的反馈：

- 媒体库、扫描与封面加载体验
- 播放器界面、交互细节与动画表现
- 歌词系统、信息展示与本地化
- 项目文档、构建流程与模块边界说明

在 USB 独占核心尚未拆分完成之前，相关底层问题会优先以设计说明、日志、接口边界和文档形式整理。

## 社群

- QQ 群：`1093312333`
- 点击加入群聊：[RawS Music 交流群](https://qm.qq.com/q/P6Qxx7XzC8)

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=QFDY-GZC/RawS-Music&type=Date)](https://star-history.com/#QFDY-GZC/RawS-Music&Date)

## 赞赏

如果这个项目对你有帮助，或者你愿意支持它继续往下打磨，可以通过下面的赞赏码支持开发。

<div align="center">
  <img src="docs/assets/wechat-donate-qrcode.png" alt="RawS Music 赞赏码" width="320" />
</div>

## 致谢

- [FFmpeg](https://ffmpeg.org/)
- [Coil](https://coil-kt.github.io/coil/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Miuix](https://github.com/YunZiA/HyperStar)
- [backdrop](https://github.com/nickkimk/backdrop)

如果这个项目刚好对你有帮助，欢迎在 GitHub 上点一个 Star。这会比一句“做得不错”更能让我知道它确实帮到了人。
