<div align="center">

# RawS Music

**An Android player for local music, expressive lyrics and configurable audio.**

[简体中文](README.md) · **English** · [日本語](README_JA.md) · [한국어](README_KO.md)

[![Release](https://img.shields.io/github/v/release/QFDY-GZC/RawS-Music?style=flat-square)](https://github.com/QFDY-GZC/RawS-Music/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/QFDY-GZC/RawS-Music/total?style=flat-square)](https://github.com/QFDY-GZC/RawS-Music/releases)
[![Stars](https://img.shields.io/github/stars/QFDY-GZC/RawS-Music?style=flat-square)](https://github.com/QFDY-GZC/RawS-Music/stargazers)
![Android](https://img.shields.io/badge/Android-7.0%2B%20%7C%20API%2024-blue?style=flat-square)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue?style=flat-square)
[![License](https://img.shields.io/badge/License-Apache--2.0-green?style=flat-square)](LICENSE)
[![README views](https://count.getloli.com/@rawsmusic-readme?theme=booru-touhoulat&padding=6&scale=0.7&pixelated=1)](https://count.getloli.com/ "View counter themes")

[Stable APK](https://github.com/QFDY-GZC/RawS-Music/releases/latest) · [Changelog](docs/release-notes-1.0.13-release.md) · [Issues](https://github.com/QFDY-GZC/RawS-Music/issues) · [Screenshots](docs/screenshots/README.md)

</div>

## Your music, your controls

RawS Music brings a local library, animated lyrics with Japanese readings, audio output settings and DSP into one app. Browse a compact list or customize the player with artwork colors, flowing backgrounds and liquid glass.

<div align="center">
<img src="docs/screenshots/library.jpg" width="220" alt="Artwork library and mini player" />
<img src="docs/screenshots/converter.jpg" width="220" alt="Audio conversion" />
<img src="docs/screenshots/transitions.jpg" width="220" alt="Playback transitions" />
</div>

Screenshots show the real app with customized fonts and appearance. Album artwork and music belong to their respective rights holders. See the [gallery](docs/screenshots/README.md) for complete settings screenshots.

## Features

| Area | Capabilities |
| :-- | :-- |
| Library | Songs, albums, artists, composers, genres, years, folders and folder hierarchies; playlists, search, sorting, list/grid layouts and playback statistics. |
| Lyrics | Line and word timing, translations, romanization, Japanese furigana and localized glyphs; spring transitions, pinch scaling, embedded lyrics, search previews, batch matching and Lyrico plugins. |
| Transitions | Gapless playback, short manual crossfades and intelligent automatic crossfades using lyric timing and audio energy; temporary cancellation for the current segment. |
| Output | AudioTrack, AAudio, OpenSL ES and Direct / Hi-Res paths; released APKs include USB DAC exclusive output, PCM, DoP and Native DSD options where supported. |
| DSP | Equalization, spatial and dynamic processing, reverb scenes and parameters, ReplayGain, DVC, resampling, speed and pitch controls. |
| DSD / SACD | DSD playback, SACD-ISO track parsing and DST decoding; DSF and SACD-ISO conversion targets. |
| Conversion | Batch queues, per-track parameters, cancellation and results; lossless and lossy formats with metadata, artwork and lyric migration. |
| System lyrics | Super Island lyrics and artwork colors, Lyricon bridging, car-related lyric/media delivery; support depends on the ROM, permissions and receiver. |
| Optional AI tools | Vocal separation and lyric alignment tools; models or plugins are prepared on demand, with capabilities and resource needs depending on the selected option. |
| Appearance / tools | Miuix UI, light/dark themes, liquid glass, artwork colors, flowing or custom image/video backgrounds, font and lyric controls, player screen keep-awake, WebDAV, track analysis and lyric video tools. |

See [feature details](docs/FEATURES.md) for formats and limits. The detailed technical documents currently use Chinese, with English identifiers and commands.

## Get started

1. Download a stable APK from [Releases](https://github.com/QFDY-GZC/RawS-Music/releases/latest). Current builds require **Android 7.0 / API 24 or newer and arm64-v8a**.
2. Grant audio or selected-folder access and scan your music. Configure WebDAV if needed.
3. Choose an output path, then adjust audio effects and appearance.
4. Follow the app instructions for system lyric integrations. Some ROM integrations require Shizuku and are not universal Android features.

High sample rates, Native DSD and hardware volume depend on the device and DAC. Strict bit-perfect mode bypasses processing that changes audio data. Conversion or upsampling cannot improve the original source quality. Optional AI tools may require model downloads and depend on device resources.

## Documentation

- [Features and formats](docs/FEATURES.md)
- [Build requirements and public source scope](docs/BUILDING.md)
- [Dependencies and acknowledgements](docs/DEPENDENCIES.md)
- [Screenshot gallery](docs/screenshots/README.md)
- [Contributing and bug reports](CONTRIBUTING.md)
- [Fork and redistribution policy](FORK_POLICY.md)

## Tags and statistics

`Android`, `Kotlin` and `Jetpack Compose` describe the platform and stack. `local music` and `lyrics` describe usage; `DSD`, `SACD` and `DSP` describe audio capabilities subject to documented limits.

Release badges show the latest stable release. Downloads count GitHub release asset downloads, **not installations**. Stars indicate community interest, not a benchmark. The third-party README image counter counts requests, **not unique visitors**, and can be affected by caching. Android/ABI badges describe the current APK; the license badge refers to the main repository, not every bundled component.

[![Star History](https://api.star-history.com/svg?repos=QFDY-GZC/RawS-Music&type=Date)](https://star-history.com/#QFDY-GZC/RawS-Music&Date)

Statistics are supplied by external services; their availability does not affect playback.

## License, source scope and forks

The main repository uses [Apache-2.0](LICENSE). Third-party components retain their own licenses. **The complete USB exclusive playback core is currently excluded from the public repository.** A released APK and this source snapshot have different build scopes; consult [BUILDING](docs/BUILDING.md).

**Unless explicitly confirmed by the original maintainer, forks, modified builds and third-party distributions are independently maintained and have no official affiliation, partnership, authorization or endorsement from RawS Music or its author.** Their maintainers are responsible for their changes, services, support and promises. Attribution identifies provenance and does not imply endorsement.

Redistributors must comply with applicable licenses: include the license, preserve relevant copyright and NOTICE attribution, prominently mark modified files and satisfy third-party license obligations. Attribution does not authorize impersonating the official project. Distinct names, package IDs, signatures and support channels are recommended maintenance practices, not additional Apache-2.0 restrictions. See [FORK_POLICY](FORK_POLICY.md) for details and a disclosure template.

Thanks to our contributors and the projects listed in [Dependencies](docs/DEPENDENCIES.md).
