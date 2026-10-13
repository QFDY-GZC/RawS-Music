# 依赖与开源致谢

[返回介绍](../README.md) · [构建说明](BUILDING.md)

核对范围：`app`、`core/common`、`core/ui`、`module/data`、`module/player`、`module/scanner`、`lyric/model`、`lyric/bridge/provider`、`backdrop` 的 Gradle 配置、实际调用、原生 CMake 输入及打包素材。研究用目录不作为应用依赖统计。以下列出直接使用和内置组件，以及与其紧密相关的传递依赖；不是完整的传递依赖 SBOM。

## Android 与 Kotlin

| 组件 / 当前配置 | 用途 | 许可与来源 |
| :-- | :-- | :-- |
| Kotlin 2.3.20 | 编译及标准库 | [Apache-2.0 · JetBrains](https://github.com/JetBrains/kotlin) |
| Coroutines 1.7.3 | 异步调度与 Flow | [Apache-2.0](https://github.com/Kotlin/kotlinx.coroutines) |
| Serialization 1.6.2 | 歌词模型和结构化序列化 | [Apache-2.0](https://github.com/Kotlin/kotlinx.serialization) |
| AndroidX / Compose BOM 2025.10.01 / Material 3 | 界面、生命周期、媒体、文件访问、Palette；BOM 本身不提供运行时代码 | [Apache-2.0 · AndroidX](https://developer.android.com/jetpack/androidx) |
| NavigationEvent Compose 1.1.1 | 返回事件与预测返回 | [Apache-2.0](https://developer.android.com/jetpack/androidx/releases/navigationevent) |
| Room 2.7.1 | 曲库数据库 | [Apache-2.0](https://developer.android.com/jetpack/androidx/releases/room) |
| MMKV 1.3.3 | 偏好与持久化 | [BSD-3-Clause；附带第三方声明 · Tencent](https://github.com/Tencent/MMKV) |
| Gson 2.10.1 | JSON 数据 | [Apache-2.0 · Google](https://github.com/google/gson) |
| OkHttp 4.12.0 / Okio（传递） | 网络请求与 I/O | [Apache-2.0 · Square](https://github.com/square/okhttp) |
| Dexter 6.2.3 | 现有权限请求逻辑 | [Apache-2.0 · Karumi](https://github.com/Karumi/Dexter) |
| ONNX Runtime Android 1.26.0 | AI 推理 JNI 与反射入口；部分运行时按需准备 | [MIT · Microsoft](https://github.com/microsoft/onnxruntime) |

AndroidX 的具体版本由版本目录、直接声明及 Gradle 最终解析共同决定，不能用 BOM 版本代替每个库的版本。ONNX 的反射调用不是未使用依赖；移除它会破坏可选推理路径。

## 界面、颜色与资源

| 组件 / 当前配置 | 用途 | 许可与来源 |
| :-- | :-- | :-- |
| Miuix 0.9.2（UI / Icons / Blur / Preference / Squircle） | 控件、弹窗、形状及模糊 | [Apache-2.0](https://github.com/compose-miuix-ui/miuix) |
| Backdrop（本地模块） | 液态玻璃与背景采样 | [Apache-2.0 · Kyant](https://github.com/Kyant0/AndroidLiquidGlass) |
| Haze / Haze Blur 2.0.0-alpha03 | 界面模糊与颜色效果 | [Apache-2.0 · Chris Banes](https://github.com/chrisbanes/haze) |
| RenderScript Intrinsics Replacement Toolkit 0.8.1（Vexo 打包） | 封面颜色背景的模糊处理 | [Apache-2.0 · Google](https://github.com/android/renderscript-intrinsics-replacement-toolkit) |
| Material Color Utilities（内置 Material 1.13.0 Java 子集） | HCT / CAM16 配色计算 | [Apache-2.0 · Google](https://github.com/material-components/material-components-android/tree/1.13.0/lib/java/com/google/android/material/color/utilities) |
| Lottie / Lottie Compose 6.3.0 | 动画素材播放 | [Apache-2.0 · Airbnb](https://github.com/airbnb/lottie-android) |
| Meteocons | 天气动画 | [MIT · Bas Milius](https://github.com/basmilius/meteocons) |
| Noto Animated Emoji | 动画表情 | [CC-BY-4.0 · Google](https://googlefonts.github.io/noto-emoji-animation/) |

Material Color Utilities 的来源说明见 `core/ui/src/main/java/com/google/android/material/color/utilities/UPSTREAM.md`，本地 Apache 文本见 `core/ui/third-party/material-color/LICENSE`。使用这些颜色工具不意味着使用了 Material Views 的完整组件库。

## 系统歌词与插件

| 组件 / 当前配置 | 用途 | 许可与来源 |
| :-- | :-- | :-- |
| Shizuku API / Provider 13.1.5 | 授权系统适配路径 | [Apache-2.0](https://github.com/RikkaApps/Shizuku-API) |
| Android Hidden API Bypass 6.1 | ROM 兼容访问 | [Apache-2.0 · LSPosed](https://github.com/LSPosed/AndroidHiddenApiBypass) |
| HyperNotification / Focus API 1.4 | 超级岛通知集成 | [Apache-2.0 · xzakota](https://github.com/xzakota/HyperNotification) |
| Lyricon model / provider（本地模块） | 歌词模型及桥接；文件保留原版权头 | [Apache-2.0 · Proify / Tomakino](https://github.com/tomakino/lyricon) |
| Lyrico 插件集成 | 插件清单、歌词源与主机协议 | [Apache-2.0 · Replica0110](https://github.com/Replica0110/Lyrico) |
| QuickJS-NG（内置 C） | JavaScript 插件执行 | [MIT](https://github.com/quickjs-ng/quickjs) |

外部歌词插件本身不自动适用主项目的许可证，需要分别核对各插件作者的许可与数据来源。

## 原生音频组件

| 组件 | 用途 | 当前源码或构建的许可依据 |
| :-- | :-- | :-- |
| FFmpeg | 解码及离线转码 | [LGPL-2.1-or-later](https://ffmpeg.org/legal.html)；当前转码脚本禁用 GPL / version3，改用其他编译选项时须重新核对 |
| TagLib | 标签、封面及歌词元数据 | [TagLib](https://taglib.org/)；内置头部标明 LGPL-2.1 或 MPL-1.1 双许可，不能直接套用新版上游的不同许可证版本 |
| UTFcpp 4.1.1 | TagLib Unicode 处理 | [BSL-1.0](https://github.com/nemtrif/utfcpp)，本地 `app/src/main/cpp/utfcpp-4.1.1/LICENSE` |
| libebur128 | 响度测量 | [MIT](https://github.com/jiixyj/libebur128)，附带 `queue.h` 为 BSD-3-Clause；本地 `COPYING` / `COPYING.queue` |
| DSD-Nexus | SACD 读取及 DST 解码子集 | [LGPL-2.1-or-later](https://github.com/wichers/dsd-nexus)，来源见 `app/src/main/cpp/third_party/dsd-nexus/UPSTREAM.md` |
| libusb | 发行包 USB 传输路径 | [LGPL-2.1-or-later](https://libusb.info/)，完整 USB 独占实现当前未公开 |

AudioTrack、AAudio、OpenSL ES 是 Android 平台 API，不是名为“FFmpeg + AudioTrack”的第三方库，不能合并署名为 Google / Apache-2.0。上表是组件级说明，完整再分发义务以各组件随附许可和实际二进制构建为准。

## 本轮整理

- 关于页移除 JAudioTagger，改为实际使用的 TagLib；将旧 Navigation 描述改为 NavigationEvent，并修正 FFmpeg 作者和许可。
- 补齐界面、持久化、网络、插件运行时、系统歌词、原生音频与动画资源致谢；项目链接可从关于页打开。
- 清理未被生产模块引用的版本目录条目：ViewPager2、CardView、SwipeRefreshLayout、Preference KTX、Navigation Fragment/UI、SmoothBottomBar、Interpolator，以及孤立的 DataStore 版本。
- 保留实际使用的 Runtime LiveData、Dexter、Haze Blur、模糊 Toolkit、ONNX Runtime 等，未因只查导入语句而删除反射或原生路径。
- 清理的是未用的目录声明，它们此前未加入应用依赖图，**不宣称本次操作显著减小 APK**。

关于页列表为组件级致谢，不替代许可证全文、NOTICE 或完整分发合规材料。新增或升级依赖时应同时更新此文档及关于页。
