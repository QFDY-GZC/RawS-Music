<div align="center">

# RawS Music

**로컬 음악, 섬세한 가사 표현, 자유로운 오디오 설정을 위한 Android 플레이어.**

[简体中文](README.md) · [English](README_EN.md) · [日本語](README_JA.md) · **한국어**

[![Release](https://img.shields.io/github/v/release/QFDY-GZC/RawS-Music?style=flat-square)](https://github.com/QFDY-GZC/RawS-Music/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/QFDY-GZC/RawS-Music/total?style=flat-square)](https://github.com/QFDY-GZC/RawS-Music/releases)
[![Stars](https://img.shields.io/github/stars/QFDY-GZC/RawS-Music?style=flat-square)](https://github.com/QFDY-GZC/RawS-Music/stargazers)
![Android](https://img.shields.io/badge/Android-7.0%2B%20%7C%20API%2024-blue?style=flat-square)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue?style=flat-square)
[![License](https://img.shields.io/badge/License-Apache--2.0-green?style=flat-square)](LICENSE)
[![README views](https://count.getloli.com/@rawsmusic-readme?theme=booru-touhoulat&padding=6&scale=0.7&pixelated=1)](https://count.getloli.com/ "카운터 테마 보기")

[안정 버전 APK](https://github.com/QFDY-GZC/RawS-Music/releases/latest) · [변경 기록](docs/release-notes-1.0.13-release.md) · [문제 보고](https://github.com/QFDY-GZC/RawS-Music/issues) · [스크린샷](docs/screenshots/README.md)

</div>

## 나만의 음악 경험

RawS Music은 로컬 라이브러리, 일본어 후리가나가 포함된 움직이는 가사, 오디오 출력과 DSP를 하나의 앱에 담았습니다. 간결한 목록으로 음악을 찾거나 앨범 색상, 흐르는 배경, 리퀴드 글라스로 재생 화면을 꾸밀 수 있습니다.

<div align="center">
<img src="docs/screenshots/library.jpg" width="220" alt="앨범 그리드 및 미니 플레이어" />
<img src="docs/screenshots/converter.jpg" width="220" alt="오디오 변환" />
<img src="docs/screenshots/transitions.jpg" width="220" alt="재생 전환 설정" />
</div>

실제 앱에서 글꼴과 외관을 조정한 화면입니다. 앨범 이미지와 음악의 권리는 각 권리자에게 있습니다. 긴 설정 화면은 [갤러리](docs/screenshots/README.md)에서 확인하세요.

## 주요 기능

| 분야 | 기능 |
| :-- | :-- |
| 라이브러리 | 곡, 앨범, 아티스트, 작곡가, 장르, 연도, 폴더 계층별 탐색. 재생 목록, 검색, 정렬, 목록/그리드 및 재생 통계. |
| 가사 | 행 및 단어 동기화, 번역, 로마자, 일본어 후리가나와 일본어 글리프. 스프링 애니메이션, 확대/축소, 내장 가사, 검색 미리 보기, 일괄 매칭 및 Lyrico 플러그인. |
| 곡 전환 | 갭리스, 짧은 수동 크로스페이드, 가사 시점과 오디오 에너지를 이용하는 자동 크로스페이드. 현재 구간만 임시 취소 가능. |
| 출력 | AudioTrack, AAudio, OpenSL ES 및 Direct / Hi-Res. 배포 APK는 지원 기기에서 USB DAC 독점 출력, PCM, DoP, Native DSD 옵션 제공. |
| DSP | 이퀄라이저, 공간 및 다이내믹 처리, 리버브 장면과 매개변수, ReplayGain, DVC, 리샘플링, 속도 및 음정 조절. |
| DSD / SACD | DSD 재생, SACD-ISO 트랙 분석 및 DST 디코딩. DSF 및 SACD-ISO 변환 대상. |
| 변환 | 일괄 대기열, 곡별 설정, 취소 및 결과 관리. 무손실/손실 형식과 메타데이터, 앨범 이미지, 가사 이전. |
| 시스템 가사 | 슈퍼 아일랜드 가사와 앨범 색상, Lyricon 연동, 차량용 가사/미디어 전달. ROM, 권한 및 수신 기기의 지원 필요. |
| 선택적 AI 도구 | 보컬 분리 및 가사 타이밍 정렬 도구. 모델이나 플러그인을 필요에 따라 준비하며 기능과 자원 요구량은 선택한 방식에 따라 달라짐. |
| 외관 / 도구 | Miuix UI, 밝은/어두운 테마, 리퀴드 글라스, 앨범 색상, 흐르는 배경, 이미지/영상 배경, 글꼴 설정, 재생 화면 켜짐 유지, WebDAV, 곡 분석 및 가사 영상 도구. |

지원 형식과 제한은 [기능 설명](docs/FEATURES.md)을 확인하세요. 상세 기술 문서는 현재 주로 중국어로 작성되어 있습니다.

## 시작하기

1. [Releases](https://github.com/QFDY-GZC/RawS-Music/releases/latest)에서 안정 APK를 다운로드하세요. **Android 7.0 / API 24 이상, arm64-v8a**가 필요합니다.
2. 오디오 또는 선택한 폴더 접근 권한을 허용하고 음악을 스캔하세요. 필요하면 WebDAV를 설정하세요.
3. 오디오 출력, 음향 효과 및 외관을 조정하세요.
4. 시스템 가사 연동은 앱 안내를 따르세요. 일부 ROM 기능에는 Shizuku가 필요하며 모든 Android에서 지원되는 것은 아닙니다.

높은 샘플레이트, Native DSD 및 하드웨어 음량은 기기와 DAC에 따라 다릅니다. 엄격한 bit-perfect 모드는 오디오 데이터를 바꾸는 처리를 우회합니다. 형식 변환이나 업샘플링이 원본의 음질을 높이지는 않습니다. AI 도구는 모델 추가 다운로드가 필요할 수 있으며 기기 자원의 영향을 받습니다.

## 문서 및 통계

- [기능과 형식](docs/FEATURES.md) · [빌드 안내](docs/BUILDING.md)
- [의존성과 감사의 말](docs/DEPENDENCIES.md) · [스크린샷](docs/screenshots/README.md)
- [기여 및 문제 보고](CONTRIBUTING.md) · [포크와 재배포 정책](FORK_POLICY.md)

`Android`, `Kotlin`, `Jetpack Compose`는 기술 스택, `local music`, `lyrics`는 사용 목적, `DSD`, `SACD`, `DSP`는 오디오 기능을 나타냅니다. Release는 최신 안정 버전입니다. Downloads는 GitHub 파일 다운로드 횟수이며 설치 인원이 아닙니다. Stars는 관심을 나타내며 성능 점수가 아닙니다. README views는 외부 이미지 요청 통계로, 고유 방문자 수가 아니며 캐시의 영향을 받습니다. Android / ABI는 현재 APK 요구 사항, License는 주 저장소 라이선스입니다.

[![Star History](https://api.star-history.com/svg?repos=QFDY-GZC/RawS-Music&type=Date)](https://star-history.com/#QFDY-GZC/RawS-Music&Date)

외부 통계 서비스가 중단되어도 앱 재생에는 영향이 없습니다.

## 라이선스, 공개 범위 및 포크

주 저장소는 [Apache-2.0](LICENSE)을 사용하며 제삼자 구성 요소에는 각자의 라이선스가 적용됩니다. **전체 USB 독점 재생 코어는 현재 공개 저장소에 포함되지 않습니다.** 배포 APK와 공개 소스의 빌드 범위가 다르므로 [빌드 안내](docs/BUILDING.md)를 확인하세요.

**원 유지 관리자가 명시적으로 확인한 경우를 제외하면 포크, 수정 빌드 및 제삼자 재배포는 독립적으로 유지되며 RawS Music이나 원 저자와 공식 소속, 협력, 승인 또는 보증 관계가 없습니다.** 변경 사항, 서비스, 지원과 약속은 해당 유지 관리자의 책임입니다. 출처 표시는 코드의 유래를 뜻하며 공식 승인을 뜻하지 않습니다.

재배포 시 라이선스를 포함하고 관련 저작권과 NOTICE를 유지하며, 수정 파일에 변경 사실을 명확히 표시하고 제삼자 라이선스를 준수해야 합니다. 공식 프로젝트를 사칭하는 근거로 출처를 사용해서는 안 됩니다. 독립된 이름, 패키지 ID, 서명 및 지원 채널은 권장 사항이며 Apache-2.0에 추가 제한을 부과하지 않습니다. [정책과 선언 예시](FORK_POLICY.md)를 참고하세요.

기여자와 [오픈 소스 프로젝트](docs/DEPENDENCIES.md)에 감사드립니다.
