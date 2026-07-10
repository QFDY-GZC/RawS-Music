# RawS Music

<div align="center">

![Version](https://img.shields.io/badge/version-0.9.01--beta1-4c8bf5?style=flat-square)
![Platform](https://img.shields.io/badge/platform-Android-3ddc84?style=flat-square)
![MinSDK](https://img.shields.io/badge/minSdk-23-ff9800?style=flat-square)
![Stack](https://img.shields.io/badge/stack-Kotlin%20%2B%20C%2B%2B-7b61ff?style=flat-square)
![License](https://img.shields.io/badge/license-Apache--2.0-1677ff?style=flat-square)
![Stars](https://img.shields.io/github/stars/QFDY-GZC/RawS-Music?style=flat-square)

An Android music player focused on local music collections, high-quality playback, and a carefully refined player experience.

[Overview](#overview) | [Features](#features) | [Open Source Scope](#open-source-scope) | [Modules](#module-structure) | [Build](#building) | [Roadmap](#roadmap) | [Contributing](#contributing)

</div>

> RawS Music is being prepared as a public project. The UI, media library, lyrics, scanner, standard playback framework, and most application-layer code are available here. The complete USB exclusive native core is intentionally maintained separately and is not included in this repository.

## Overview

RawS Music is an Android music player built around local audio playback. It is not intended to be a streaming aggregation service. The project focuses on local media organization, playback interaction, lyrics, album browsing, external audio devices, and a more expressive visual presentation of the audio pipeline.

The goal is simple: make listening to local music feel complete. This includes sound and playback controls, album artwork, page transitions, immersive playback, lyric details, efficient library browsing, and the experience of connecting external audio hardware.

## Features

| Area | Description |
| --- | --- |
| Local library | Browse songs, albums, artists, folders, and playlists with continuous improvements to scanning, indexing, and navigation. |
| Playback | Full player screen, mini-player bar, immersive player, queue controls, background playback, and position synchronization. |
| Lyrics | Lyric models, provider bridge, line-level and word-level display foundations, status-bar lyrics, and player lyrics pages. |
| Audio pipeline | FFmpeg bridge, standard playback framework, DSP wiring, and selected native audio processing components. |
| Artwork | Embedded and external artwork resolution, layered caching, animated presentation, and an optional built-in default album image for tracks without artwork. |
| UI system | Jetpack Compose, Miuix, custom backdrop effects, liquid-glass inspired surfaces, and scene-based transitions. |
| Architecture | A modular Android project split across UI, data, scanning, playback, lyrics, and visual-effect modules. |

## Open Source Scope

The repository prioritizes components that can be maintained, reviewed, and developed independently.

| Scope | Status | Description |
| --- | --- | --- |
| `app/` | Public | Application entry point, major screens, settings, services, and high-level interaction logic. |
| `core/common` | Public | Shared models, preferences, bridges, and utility classes. |
| `core/ui` | Public | Theme, reusable UI components, player and list UI, and scene organization. |
| `module/data` | Public | Database, entities, DAOs, and repositories. |
| `module/scanner` | Public | Media scanning, metadata processing, indexing, and synchronization infrastructure. |
| `lyric/` | Public | Lyric models and provider bridge. |
| `backdrop/` | Public | Reusable blur, layering, and glass-like visual effects. |
| USB exclusive upper layer | Being organized | Product-level structures remain public while the native boundary is being separated. |
| USB exclusive native core | Not included | Planned as a separate repository under GPLv3. |

The public repository is still being cleaned up. Experimental files, scripts, and documentation may continue to move as the project boundary becomes clearer.

## License

- `RawS-Music`: Apache-2.0
- Future USB exclusive native core repository: planned GPLv3
- Third-party dependencies: governed by their respective licenses

This separation keeps the general-purpose player available for open collaboration while allowing the hardware-coupled USB exclusive core to evolve and be reviewed independently.

See [LICENSE](LICENSE) and [NOTICE](NOTICE) for more information.

## Module Structure

```text
RawS-Music/
├─ app/                  # Application entry point, screens, settings, services
├─ core/common/          # Shared models, bridges, utilities, preferences
├─ core/ui/              # Themes, components, player UI, lists, scene transitions
├─ module/data/          # Database, entities, DAOs, repositories
├─ module/player/        # Playback control, output, DSP wiring, audio infrastructure
├─ module/scanner/       # Scanning, parsing, indexing, synchronization
├─ lyric/                # Lyric models and provider bridge
├─ backdrop/             # Blur, layering, and liquid-glass style effects
└─ docs/                 # Architecture notes and design documentation
```

## Technology Stack

| Category | Technology |
| --- | --- |
| Languages | Kotlin, C++17 |
| UI | Jetpack Compose, Miuix |
| Native and audio bridge | CMake, Android NDK, FFmpeg |
| Data | Room, LitePal |
| Asynchronous work | Kotlin Coroutines, Flow |
| Graphics | RenderEffect, Canvas, backdrop |
| Project shape | Multi-module Android project |

## Building

### Requirements

- Latest stable Android Studio
- JDK 21
- Android SDK
- Android NDK
- CMake 3.22.1

### Clone and Install

```powershell
git clone https://github.com/QFDY-GZC/RawS-Music.git
cd RawS-Music
.\gradlew.bat installRelease
```

To verify compilation without installing to a device:

```powershell
.\gradlew.bat assembleDebug
```

Some native features require a correctly configured local NDK and CMake environment. The complete USB exclusive native core is not part of the current public repository.

## Roadmap

1. Continue organizing the public repository, documentation, and build instructions.
2. Add more page-level documentation, screenshots, and module boundary notes.
3. Move the USB exclusive core into a separate repository and publish it under GPLv3.
4. Separate internal experiments and analysis tooling from the main maintenance boundary.

## Contributing

Issues and pull requests are welcome. Feedback is especially useful in these areas:

- Media library, scanning, and artwork loading
- Player interaction, visual details, and animation behavior
- Lyrics, metadata presentation, and localization
- Documentation, build flow, and module boundaries

Until the USB exclusive core is separated, lower-level hardware topics will be documented through design notes, logs, interfaces, and boundary documents.

## Community

- QQ group: `1093312333`
- [RawS Music Community](https://qm.qq.com/q/P6Qxx7XzC8)

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=QFDY-GZC/RawS-Music&type=Date)](https://star-history.com/#QFDY-GZC/RawS-Music&Date)

## Acknowledgements

- [FFmpeg](https://ffmpeg.org/)
- [Coil](https://coil-kt.github.io/coil/)
- [Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Miuix](https://github.com/YunZiA/HyperStar)
- [backdrop](https://github.com/nickkimk/backdrop)

