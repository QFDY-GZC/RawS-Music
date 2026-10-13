# 构建与源码范围

[返回介绍](../README.md) · [依赖说明](DEPENDENCIES.md)

## 先了解公开范围

本仓库主项目为 Apache-2.0。完整 USB 独占播放核心目前未公开，部分原生预编译输入也未随源码仓库分发。因此，**公开仓库不是发行 APK 的完整可重现构建快照**；不能承诺克隆后直接执行 `assembleRelease` 成功。此前已公开的 USB 相关接口或代码不因此被删除。

发行包包含的功能与当前公开源码的构建范围有所不同。完整 USB 核心计划另行整理，具体公开状态以仓库公告为准，没有现成地址时请勿把计划当作已发布项目。

## 当前工具链

| 项目 | 配置 |
| :-- | :-- |
| JDK | 21 |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin | 8.10.0 |
| Kotlin / KSP | 2.3.20 / 2.3.9 |
| compileSdk / targetSdk | 37 / 37 |
| minSdk | 24 |
| NDK | 27.0.12077973 |
| CMake | 3.22.1 |
| 发行 ABI | arm64-v8a |

使用项目 Wrapper，配置自己的 Android SDK 路径；不要提交 `local.properties`、密钥、签名文件或个人设备信息。

## Windows / PowerShell

配置 JDK 和 SDK 后，可以进行 Kotlin 源码编译检查。检查仍需要 Gradle 仓库可访问、模块及相应编译输入齐全：

```powershell
$env:JAVA_HOME = '你的 JDK 21 路径'
.\gradlew.bat :app:compileReleaseKotlin --console=plain
```

仅在完整开发工作区、原生源文件与预编译输入齐全时构建和安装：

```powershell
.\gradlew.bat assembleRelease --console=plain
.\gradlew.bat installRelease --console=plain
```

输出路径为 `app/build/outputs/apk/release/app-release.apk`。`installRelease` 需要已连接且授权的 ADB 设备。

当前开发配置中的 release 使用 debug signingConfig；不要把它描述为独立的生产签名方案。自行分发应配置自己的签名、包名与升级策略，同时保留许可证和来源说明。

## 原生组件与许可

FFmpeg、TagLib、QuickJS-NG、libebur128、DSD-Nexus、UTFcpp 及 USB 路径使用的 libusb 各有独立许可和构建输入。`tools/` 中的构建脚本说明部分依赖的准备方式；仅修改版本目录并不能自动更新对应 ABI 的原生二进制。

执行构建前核对 [DEPENDENCIES](DEPENDENCIES.md) 与源码内许可，不要把研究目录、反编译产物、私人音频、调试报告或凭据打包进发行版。
