# RawS Music

<div align="center">

![Version](https://img.shields.io/badge/version-0.9.15--beta-4c8bf5?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android-3ddc84?style=flat-square)
![MinSDK](https://img.shields.io/badge/minSdk-23-ff9800?style=flat-square)
![Stack](https://img.shields.io/badge/stack-Kotlin%20%2B%20C%2B%2B-7b61ff?style=flat-square)
![License](https://img.shields.io/badge/license-Apache--2.0-1677ff?style=flat-square)
![Release](https://img.shields.io/github/v/release/QFDY-GZC/RawS-Music?include_prereleases&style=flat-square)
![Downloads](https://img.shields.io/github/downloads/QFDY-GZC/RawS-Music/total?style=flat-square)
![Stars](https://img.shields.io/github/stars/QFDY-GZC/RawS-Music?style=flat-square)
![Forks](https://img.shields.io/github/forks/QFDY-GZC/RawS-Music?style=flat-square)
![Issues](https://img.shields.io/github/issues/QFDY-GZC/RawS-Music?style=flat-square)
![Views](https://hits.seeyoufarm.com/api/count/incr/badge.svg?url=https%3A%2F%2Fgithub.com%2FQFDY-GZC%2FRawS-Music&count_bg=%233D7EFF&title_bg=%232C3440&icon=&icon_color=%23FFFFFF&title=views&edge_flat=false)
[![QQ Group](https://img.shields.io/badge/QQ群-1093312333-12B7F5?style=flat-square)](https://qm.qq.com/q/P6Qxx7XzC8)

面向本地音乐收藏、高音质播放、DSD 与 USB DAC 场景持续打磨的 Android 音乐播放器。

[项目说明](#项目说明) • [核心功能](#核心功能) • [格式与媒体库支持](#格式与媒体库支持) • [当前开源范围](#当前开源范围) • [模块结构](#模块结构) • [依赖库与框架](#依赖库与框架) • [仓库统计](#仓库统计) • [构建方式](#构建方式) • [Star History](#star-history) • [QQ群](https://qm.qq.com/q/P6Qxx7XzC8) • [赞赏](#赞赏)

</div>

> 当前仓库正在持续整理为公开版本。UI、媒体库、歌单、歌词、扫描、常规播放框架、DSP 接线与大部分应用层代码已开放；完整的 USB 独占 Native 核心暂不包含在本仓库中，后续会拆分为独立仓库并采用 GPLv3。

## 项目说明

RawS Music 是一个以本地音乐为中心的 Android 音乐播放器项目。它不以流媒体聚合为主，而是把注意力放在本地媒体库组织、播放器交互质感、歌词体验、专辑与列表视图、音频链路表达，以及外接音频设备使用体验上。

这个项目更在意“本地音乐播放器应该足够完整”这件事：不仅要把歌曲放出来，还要把扫描、入库、封面、歌词、播放状态、后台控制、曲库浏览、歌单组织、音频信息展示、DSD 与 USB DAC 场景都做成一条连贯的产品链路。

## 核心功能

| 模块 | 详细能力 |
| --- | --- |
| 媒体库系统 | 围绕歌曲、专辑、艺术家、专辑艺术家、作曲家、流派、年份、文件夹与歌单组织本地音频资源，配合索引条、PowerList 列表、专辑封面流与多级详情页完成浏览。 |
| 扫描与入库 | 提供手动扫描、增量扫描、两阶段扫描、SAF 文件夹扫描、扫描前台服务、进度反馈与稳定去重；对 CUE 分轨、标签补全、技术信息归一化、ReplayGain 字段持久化等有专门处理。 |
| 播放与队列 | 包含完整播放器页、迷你播放栏、后台服务、播放队列、上一首下一首、定位恢复、跨页状态同步、进度更新、CUE 轨道显示时间与真实时间映射。 |
| 音频处理链 | 使用 Kotlin + C++ 混合架构组织解码、缓存、AudioTrack 输出、FFmpeg 桥接、格式探测、重采样目标决策、Gapless/Decoder handoff、Crossfade、PCM 写入与环形缓冲。 |
| DSP 与音效 | 已公开路径包含图形均衡器、参数均衡器、压缩器、低音增强、高音增强、声场扩展、Stereo Widen、Surround 相关控制器与统一 DSP pipeline。 |
| 歌词系统 | 包含歌词模型、Provider Bridge、逐行与逐字时间轴、CUE 专辑整轨歌词裁切、状态栏歌词桥接、Ticker / 蓝牙歌词桥接与播放器页歌词展示。 |
| 界面与动画 | 基于 Compose、Miuix 与 backdrop 组织主界面、播放器页、沉浸式布局、专辑大图转场、液态玻璃、模糊层、迷你播放栏、列表交互与场景切换。 |
| USB 与 Hi-Res 路线 | 公开仓库保留 USB 独占上层接入、能力建模、音量控制、恢复策略、DSD 支持判定与设备信息展示；底层完整独占核心后续拆仓发布。 |
| 工具与设置 | 含媒体库设置、扫描设置、音频输出设置、USB DAC 设置、日志反馈、元数据编辑桥接、WebDAV 页面与部分备份/同步基础设施。 |

## 格式与媒体库支持

### 音频格式

以下格式已在公开代码中进入扫描、元数据识别或播放链路：

| 类别 | 说明 |
| --- | --- |
| 常规格式 | MP3、FLAC、WAV、AAC、OGG、OPUS、M4A、ALAC、WMA、APE、AIFF |
| DSD / Hi-Res | DSF、DFF，项目内部持续推进 Native DSD、PCM to DSD、USB DAC 相关路线 |
| 扩展扫描格式 | WV、TTA、TAK、MKA、MPC、CUE 在现有扫描路径中已纳入支持或识别逻辑 |
| 元数据识别扩展 | TagLib 全量桥接已覆盖 MP3、FLAC、OGG、M4A、WMA、APE、WAV、AIFF、DSD、WavPack 等更广范围的标签读取能力 |

### 元数据、封面与歌词

| 能力 | 说明 |
| --- | --- |
| 元数据读取 | 组合 MediaStore、TagLib 与 FFmpeg 补全标题、艺术家、专辑、专辑艺术家、作曲家、流派、年份、轨道号、声道、采样率、位深、码率等字段 |
| 专辑图 | 支持从媒体文件读取内嵌专辑图，并在列表、专辑页、播放器页与迷你播放栏内复用封面数据 |
| 技术信息 | 数据模型中保留 ReplayGain、峰值、文件大小、编码格式、CUE 偏移、DSD 判定与技术参数字段 |
| 歌词来源 | 支持外部歌词、内嵌歌词、Provider Bridge，以及 CUE 专辑整轨歌词按分轨裁切后的显示 |

### 曲库组织

| 维度 | 说明 |
| --- | --- |
| 基础维度 | 歌曲、专辑、艺术家、专辑艺术家、作曲家、流派、年份、文件夹 |
| 列表系统 | PowerList 列表、字母索引、分组页、详情页、选择模式、歌单内快捷操作 |
| 歌单能力 | 公开代码已包含歌单实体、歌曲入列、下一首播放、加入队列、歌单管理与排序字段 |
| CUE 专辑 | 扫描阶段可展开 CUE 分轨；播放、进度、歌词和入库 key 都会带上 CUE 偏移信息 |

## 当前开源范围

目前这个 GitHub 仓库会优先公开适合稳定维护、可单独协作、边界相对清晰的部分。

| 范围 | 状态 | 说明 |
| --- | --- | --- |
| `app/` 应用入口与页面层 | 已公开 | 包含主应用入口、主要页面、设置页、服务、帮助类与上层交互逻辑 |
| `core/common` | 已公开 | 公共模型、偏好设置、工具类、FFmpeg / TagLib 桥接与通用数据结构 |
| `core/ui` | 已公开 | 主题、组件、播放器 UI、PowerList、场景切换与页面组织能力 |
| `module/data` | 已公开 | Room 数据库、实体、DAO、仓库、偏好设置与音效参数持久化 |
| `module/player` | 已公开 | 常规播放框架、AudioTrack 输出、DSP pipeline、播放状态机、USB 上层接入与恢复策略 |
| `module/scanner` | 已公开 | 媒体扫描、两阶段扫描、SAF / WebDAV 基础、标签整理、CUE 处理与索引更新 |
| `lyric/` | 已公开 | 歌词模型、AIDL/Provider Bridge 与时间轴相关实现 |
| `backdrop/` | 已公开 | 模糊、阴影、高光、液态玻璃、RenderEffect 相关可复用视觉效果 |
| USB 独占 Native 核心 | 暂不在本仓库 | 后续拆分为独立仓库，采用 GPLv3 单独维护 |

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
├─ module/data/          # 数据库、实体、DAO、仓库、偏好设置
├─ module/player/        # 播放控制、常规输出、DSP、状态机、USB 上层接入
├─ module/scanner/       # 扫描、解析、索引更新、SAF / WebDAV 基础设施
├─ lyric/                # 歌词模型与 Provider Bridge
├─ backdrop/             # 模糊、层叠、液态玻璃风格效果
└─ docs/                 # 架构分析、设计记录与整理中的文档
```

## 依赖库与框架

| 类别 | 库 / 框架 | 用途 |
| --- | --- | --- |
| 语言与构建 | Kotlin 2.3、C++17、AGP、Gradle、CMake、NDK | 组织 Android 主工程与 Native 音频桥接 |
| UI | Jetpack Compose、Material 3、Miuix | 主界面、播放器页、列表页、设置页、图标与主题系统 |
| 图形与视觉 | backdrop、RenderEffect、Palette、Lottie | 模糊、高光、液态玻璃、动态取色、动画效果 |
| 数据层 | Room、MMKV、Gson | 媒体库数据库、设置存储、复杂对象序列化 |
| 异步与状态 | Kotlin Coroutines、Flow、Lifecycle | 扫描任务、播放状态、页面状态与后台服务协同 |
| 音频处理 | FFmpeg、AudioTrack、TagLib | 解码、元数据补全、重采样桥接、技术信息读取 |
| 网络与外部源 | OkHttp、WebDAV 基础实现 | 远程目录访问、同步与后续扩展基础 |
| 歌词能力 | Lyric model、Provider bridge、AIDL | 外部歌词桥接、状态栏歌词、逐字时间轴能力 |

## 仓库统计

| 指标 | 说明 |
| --- | --- |
| Release | 使用 GitHub Releases 发布最新构建包与版本说明 |
| Downloads | 通过 GitHub Releases 统计累计下载次数 |
| Views | 通过 seeyoufarm 统计仓库访问量 |
| Stars / Forks / Issues | 直接反映社区关注度、二次开发意愿与问题反馈量 |

你在 README 顶部看到的版本、下载、访问、Star、Fork、Issue 等徽章都会随着仓库状态自动更新。

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
- 与 USB 独占完整底层相关的源码不在当前仓库内

## 开发路线

接下来会继续推进几件事情：

1. 继续整理公开仓库的目录结构、文档和构建说明
2. 补充更多页面截图、模块边界说明与技术文档
3. 将 USB 独占 Native 核心抽离为单独仓库，并按 GPLv3 发布
4. 继续梳理 DSD、USB DAC、媒体库扫描、歌词与专辑图链路

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
  <table>
    <tr>
      <td align="center">
        <img src="docs/assets/wechat-donate-qrcode.png" alt="微信赞赏码" width="280" />
        <div>微信赞赏</div>
      </td>
      <td align="center">
        <img src="docs/assets/alipay-donate-qrcode.jpg" alt="支付宝赞赏码" width="280" />
        <div>支付宝赞赏</div>
      </td>
    </tr>
  </table>
</div>

## 致谢

- [FFmpeg](https://ffmpeg.org/)
- [TagLib](https://taglib.org/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Miuix](https://github.com/YunZiA/HyperStar)
- [backdrop](https://github.com/nickkimk/backdrop)
- [OkHttp](https://square.github.io/okhttp/)

如果这个项目刚好对你有帮助，欢迎在 GitHub 上点一个 Star。这会比一句“做得不错”更能让我知道它确实帮到了人。
