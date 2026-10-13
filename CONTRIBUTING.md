# 贡献与反馈

[简体中文](README.md) · [English](README_EN.md) · [日本語](README_JA.md) · [한국어](README_KO.md)

欢迎提交可复现的问题、文档改善与代码修复。先检查已有 Issues，二改版本的问题请优先提交给二改维护者。

## 有用的问题报告

- 应用版本与版本来源；设备型号、Android / ROM 版本。
- 可复现步骤、预期表现和实际表现；能否稳定复现。
- 音频问题：本地或网络文件、格式、采样率、位深、输出协议；USB 问题补充 DAC 型号、输出模式、音量模式。
- 歌词问题：歌词格式、是否内嵌、是否有注音/翻译、字体与缩放设置。
- 崩溃或卡顿：大致发生时间、应用导出的日志、相关截图。提交前删去日志中的账号、凭据、私人路径或服务地址。
- 仅提供有权分享的测试片段或最小样本；不要上传整个私人曲库。

如果录屏会改变问题表现，请直接说明，并使用截图与日志。不能稳定复现的情况也可以反馈，不必为了报告而重复高风险操作。

## 提交修改

1. 阅读 [BUILDING](docs/BUILDING.md) 与公开源码范围，再选择要修改的模块。
2. 每次修改解决一个清楚的问题，描述触发条件、行为变化和验证结果。
3. 不提交生成文件、个人配置、密钥、音频素材、反编译研究产物或未获授权的组件。
4. 保留已有版权与许可证；新增依赖时更新 [DEPENDENCIES](docs/DEPENDENCIES.md) 和应用关于页。
5. 为业务修改执行对应的编译、相关测试和设备验证；文档修改检查相对链接、截图路径与多语言一致性。

## English

Include the app version and source, device/ROM, reproducible steps, expected and actual behavior, and relevant logs with private data removed. For audio issues, include the file format and output path; for USB, add the DAC and volume/output modes. Report fork-specific issues to that fork's maintainer. Share only samples you are entitled to distribute.

Keep changes focused, preserve copyright and license notices, update dependency acknowledgements when necessary, and describe what you verified. Read the public-source limitations in [BUILDING](docs/BUILDING.md) before attempting a complete APK build. Fork distribution guidance is in [FORK_POLICY](FORK_POLICY.md).
