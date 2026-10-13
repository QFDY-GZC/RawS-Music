<div align="center">

# RawS Music

**ローカル音楽、豊かな歌詞表示、調整可能な音声処理を楽しむ Android プレーヤー。**

[简体中文](README.md) · [English](README_EN.md) · **日本語** · [한국어](README_KO.md)

[![Release](https://img.shields.io/github/v/release/QFDY-GZC/RawS-Music?style=flat-square)](https://github.com/QFDY-GZC/RawS-Music/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/QFDY-GZC/RawS-Music/total?style=flat-square)](https://github.com/QFDY-GZC/RawS-Music/releases)
[![Stars](https://img.shields.io/github/stars/QFDY-GZC/RawS-Music?style=flat-square)](https://github.com/QFDY-GZC/RawS-Music/stargazers)
![Android](https://img.shields.io/badge/Android-7.0%2B%20%7C%20API%2024-blue?style=flat-square)
![ABI](https://img.shields.io/badge/ABI-arm64--v8a-blue?style=flat-square)
[![License](https://img.shields.io/badge/License-Apache--2.0-green?style=flat-square)](LICENSE)
[![README views](https://count.getloli.com/@rawsmusic-readme?theme=booru-touhoulat&padding=6&scale=0.7&pixelated=1)](https://count.getloli.com/ "カウンターのテーマ")

[安定版 APK](https://github.com/QFDY-GZC/RawS-Music/releases/latest) · [更新履歴](docs/release-notes-1.0.13-release.md) · [不具合報告](https://github.com/QFDY-GZC/RawS-Music/issues) · [スクリーンショット](docs/screenshots/README.md)

</div>

## 音楽を、自分のスタイルで

RawS Music は、ローカルライブラリ、ふりがな付きの動く歌詞、音声出力設定、DSP をひとつのアプリにまとめています。シンプルなリストで音楽を探したり、ジャケットの色、流れる背景、リキッドガラスで再生画面をカスタマイズしたりできます。

<div align="center">
<img src="docs/screenshots/library.jpg" width="220" alt="ライブラリとミニプレーヤー" />
<img src="docs/screenshots/converter.jpg" width="220" alt="音声変換" />
<img src="docs/screenshots/transitions.jpg" width="220" alt="再生時のトランジション" />
</div>

画像はフォントと外観をカスタマイズした実際のアプリです。ジャケットと音楽の権利は各権利者に帰属します。設定の全体像は[ギャラリー](docs/screenshots/README.md)をご覧ください。

## 主な機能

| 分野 | 機能 |
| :-- | :-- |
| ライブラリ | 曲、アルバム、アーティスト、作曲家、ジャンル、年、フォルダー階層。プレイリスト、検索、並べ替え、リスト／グリッド、再生統計。 |
| 歌詞 | 行・単語単位の同期、翻訳、ローマ字、ふりがな、日本語字形。スプリングアニメーション、ピンチ拡大縮小、埋め込み歌詞、検索プレビュー、一括取得、Lyrico プラグイン。 |
| 曲の接続 | ギャップレス、手動の短いクロスフェード、歌詞時刻と音声エネルギーを使う自動クロスフェード。現在の区間だけ一時的にキャンセル可能。 |
| 音声出力 | AudioTrack、AAudio、OpenSL ES、Direct / Hi-Res。配布 APK は、対応機器で USB DAC 排他出力、PCM、DoP、Native DSD を提供。 |
| 音声処理 | イコライザー、空間・ダイナミクス処理、リバーブのシーンとパラメーター、ReplayGain、DVC、リサンプリング、速度・ピッチ調整。 |
| DSD / SACD | DSD 再生、SACD-ISO の曲解析、DST デコード。DSF と SACD-ISO への変換。 |
| 変換 | 一括キュー、曲ごとの設定、キャンセル、結果管理。可逆・非可逆形式、メタデータ・ジャケット・歌詞の移行。 |
| システム連携 | スーパーアイランドの歌詞とジャケット色、Lyricon、車載向け歌詞・メディア情報送信。ROM、権限、受信側の対応が必要。 |
| AI ツール（任意） | ボーカル分離と歌詞のタイミング調整。必要に応じてモデルやプラグインを取得し、機能と負荷は選択した方式に依存。 |
| 外観・ツール | Miuix、明暗テーマ、リキッドガラス、ジャケット色、流れる背景、画像／動画背景、フォント設定、再生画面のスリープ防止、WebDAV、楽曲解析、歌詞動画。 |

対応形式と制限は[機能説明](docs/FEATURES.md)に記載しています。詳細な技術文書は現在、中国語を中心に記述しています。

## 使い始める

1. [Releases](https://github.com/QFDY-GZC/RawS-Music/releases/latest) から安定版 APK を取得します。**Android 7.0 / API 24 以上、arm64-v8a** が必要です。
2. 音声ファイルまたは選択したフォルダーへのアクセスを許可し、ライブラリをスキャンします。必要に応じて WebDAV を設定します。
3. 音声出力、音声効果、外観を調整します。
4. システム歌詞連携はアプリの案内に従って設定してください。一部は Shizuku が必要で、すべての Android ROM で使える機能ではありません。

高いサンプルレート、Native DSD、ハードウェア音量は端末と DAC に依存します。厳密な bit-perfect モードでは音声データを変更する処理を回避します。変換やアップサンプリングで元音源の品質が向上するわけではありません。AI ツールにはモデルの追加ダウンロードが必要な場合があります。

## 文書と統計

- [機能・形式](docs/FEATURES.md) · [ビルド方法](docs/BUILDING.md)
- [依存ライブラリと謝辞](docs/DEPENDENCIES.md) · [ギャラリー](docs/screenshots/README.md)
- [貢献・不具合報告](CONTRIBUTING.md) · [派生プロジェクトの方針](FORK_POLICY.md)

`Android`、`Kotlin`、`Jetpack Compose` は技術、`local music`、`lyrics` は用途、`DSD`、`SACD`、`DSP` は音声機能を示します。Release は最新の安定版、Downloads は GitHub 配布ファイルの取得回数で、インストール人数ではありません。Stars は関心の指標であり性能評価ではありません。README views は第三者の画像リクエスト計数で、ユニーク訪問者数ではなくキャッシュの影響を受けます。Android / ABI は配布 APK の要件、License は主リポジトリのライセンスです。

[![Star History](https://api.star-history.com/svg?repos=QFDY-GZC/RawS-Music&type=Date)](https://star-history.com/#QFDY-GZC/RawS-Music&Date)

外部の統計サービスが停止しても、アプリの再生には影響しません。

## ライセンスと派生版

主リポジトリは [Apache-2.0](LICENSE) です。第三者の構成要素にはそれぞれのライセンスが適用されます。**USB 排他再生コア全体は現在、公開リポジトリに含まれていません。** 配布 APK と公開ソースのビルド範囲は異なります。[ビルド説明](docs/BUILDING.md)を確認してください。

**元の保守者が明示的に確認した場合を除き、Fork、改変版、第三者の再配布は独立したプロジェクトであり、RawS Music と作者との公式な所属、提携、承認、推薦関係はありません。** 改変、サービス、サポート、保証は派生版の保守者が責任を負います。出典表記は由来を示すもので、公式の推薦を意味しません。

再配布では、ライセンスの同梱、関連する著作権・NOTICE の維持、変更したファイルへの明確な変更表示、第三者ライセンスの遵守が必要です。公式を装うための名前使用は認められていません。独自の名称、パッケージ ID、署名、問い合わせ先は推奨事項であり、Apache-2.0 に追加の制限を課すものではありません。[方針と声明テンプレート](FORK_POLICY.md)をご覧ください。

貢献者および[依存プロジェクト](docs/DEPENDENCIES.md)の皆さまに感謝します。
