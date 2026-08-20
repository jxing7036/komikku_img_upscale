# Komikku Image Upscale (komikku_img_upscale)

> ## 本仓库说明 / About this fork
>
> 这是 **[Komikku](https://github.com/komikku-app/komikku)** 的个人定制 fork,在其基础上整合了 **AI 图像超分(upscale)** 能力——把 [mihon_img_upscale](https://github.com/HaoweiLi97/mihon_img_upscale) 的 waifu2x / Real-CUGAN / Real-ESRGAN / W2xEX / SPAN / Anime4K 实时增强引擎移植到了 Komikku。
>
> **上游仓库(Upstreams)**
> - Komikku(本 fork 的基座): <https://github.com/komikku-app/komikku>
> - mihon_img_upscale(upscale 功能来源): <https://github.com/HaoweiLi97/mihon_img_upscale>
>
> **本 fork 相对上游 Komikku 的主要差异**
> - 新增阅读器内 **AI 图像增强**(waifu2x / Real-CUGAN / Real-ESRGAN 等模型)
> - 原生引擎: `app/src/main/cpp/` + ncnn Vulkan SDK(`third_party/ncnn-20260113-android-vulkan`)
> - Kotlin 封装: `app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/` 与 `util/image/`
> - 阅读器增强设置页、底部可开关的增强按钮、优先级调度(当前页优先、丢弃过期预载)
> - 已移除高通 NPU(QNN)后端,统一使用 Vulkan GPU

---

## Upscale 移植说明

### 功能来源
AI 图像增强(upscale)引擎移植自 **mihon_img_upscale**(<https://github.com/HaoweiLi97/mihon_img_upscale>),它本身是基于 Mihon 的 fork,使用 **ncnn** 在移动 GPU(Vulkan)上跑 waifu2x / Real-CUGAN / Real-ESRGAN / W2xEX / SPAN / Anime4K 等超分模型,并针对漫画长图做了分块(tiling)、灰度/Alpha 特判、优先级调度等工程优化。

### 移植方式
将 mihon_img_upscale 的 upscale 相关部分移植到 Komikku,并按 Komikku 的代码规范接入(`// KMK -->` 标记、`i18n-kmk` 文案)。包含:

1. **原生层**(新增)
   - `app/src/main/cpp/` — waifu2x.cpp/h、waifu2x_jni.cpp、anime4k.cpp/h、融合 Vulkan shader、CMakeLists
   - `third_party/ncnn-20260113-android-vulkan/` — ncnn SDK(5 个 ABI)
2. **模型资产**(新增)
   - `app/src/main/assets/` — waifu2x / Real-CUGAN / Real-ESRGAN / W2xEX / SPAN / Anime4K 等模型
3. **Kotlin 封装**(新增)
   - `util/waifu2x/` — `Waifu2x.kt`(JNI 封装)、`ImageEnhancer.kt`(优先级调度)、`ImageEnhancementCache.kt`(磁盘缓存)
   - `util/image/ImageFilter.kt` — 墨迹滤镜
4. **集成点**(修改,均带 `// KMK -->` 标记)
   - Coil 解码器 `TachiyomiImageDecoder.kt`(enhanced 请求、缓存优先、on-the-fly 增强)
   - 阅读器管线 `ReaderPageImageView`、`PagerPageHolder`、`Webtoon*`、`HttpPageLoader`/`DownloadPageLoader`、`ReaderActivity`
   - 设置 `ReaderPreferences`、`ColorFilterPage`、`ReaderBottomButton`(增强按钮可开关)
   - `i18n-kmk` 文案(`KMR`)

### 构建方法
```bash
# 前置:Android SDK + JDK 17;local.properties 配置 sdk.dir
# ncnn SDK 已随仓库放在 third_party/,无需额外下载

./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew assembleRelease   # release 已配置 debug 签名,可直接安装
```

产物在 `app/build/outputs/apk/release/`:
- `app-universal-release.apk` — 通用包(含全部 ABI,推荐)
- `app-arm64-v8a-release.apk` / `app-armeabi-v7a-release.apk` / `app-x86_64-release.apk` / `app-x86-release.apk`

---

<div align="center">

<a href="https://komikku-app.github.io">
  <img width=200px height=200px src="./.github/readme-images/app-icon.png"/>
</a><br/>
<a href="https://trendshift.io/repositories/13696" target="_blank"><img src="https://trendshift.io/api/badge/repositories/13696" alt="komikku-app%2Fkomikku | Trendshift" style="width: 250px; height: 55px;" width="250" height="55"/></a>
 <h1 align="center"> Komikku </h1>

| Releases | Preview |
|----------|---------|
| <div align="center"> [![GitHub downloads](https://img.shields.io/github/downloads/komikku-app/komikku/latest/total?label=Latest%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/komikku-app/komikku/releases/latest) [![GitHub downloads](https://img.shields.io/github/downloads/komikku-app/komikku/total?label=Total%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/komikku-app/komikku/releases) [![Stable build](https://img.shields.io/github/actions/workflow/status/komikku-app/komikku/build_release.yml?labelColor=27303D&label=Stable&labelColor=06599d&color=043b69)](https://github.com/komikku-app/komikku/actions/workflows/build_release.yml) | <div align="center"> [![GitHub downloads](https://img.shields.io/github/downloads/komikku-app/komikku-preview/latest/total?label=Latest%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/komikku-app/komikku-preview/releases/latest) [![GitHub downloads](https://img.shields.io/github/downloads/komikku-app/komikku-preview/total?label=Total%20Downloads&labelColor=27303D&color=0D1117&logo=github&logoColor=FFFFFF&style=flat)](https://github.com/komikku-app/komikku-preview/releases) [![Preview build](https://img.shields.io/github/actions/workflow/status/komikku-app/komikku-preview/build_app.yml?labelColor=27303D&label=Preview&labelColor=2c2c47&color=1c1c39)](https://github.com/komikku-app/komikku-preview/actions/workflows/build_app.yml) |

*Requires Android 8.0 or higher.*

[![Discord](https://img.shields.io/discord/1242381704459452488.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/85jB7V5AJR)
[![CI](https://img.shields.io/github/actions/workflow/status/komikku-app/komikku/build_push.yml?labelColor=27303D&label=CI)](https://github.com/komikku-app/komikku/actions/workflows/build_push.yml)
[![License: Apache-2.0](https://img.shields.io/github/license/komikku-app/komikku?labelColor=27303D&color=0877d2)](/LICENSE)
[![Translation status](https://img.shields.io/weblate/progress/komikku-app?labelColor=27303D&color=946300)](https://hosted.weblate.org/engage/komikku-app/)

## Download

[![Stable](https://img.shields.io/github/release/komikku-app/komikku.svg?maxAge=3600&label=Stable&labelColor=06599d&color=043b69)](https://github.com/komikku-app/komikku/releases/latest)
[![Preview](https://img.shields.io/github/v/release/komikku-app/komikku-preview.svg?maxAge=3600&label=Preview&labelColor=2c2c47&color=1c1c39)](https://github.com/komikku-app/komikku-preview/releases/latest)

*Requires Android 8.0 or higher.*

[![Sponsor me on GitHub](https://custom-icon-badges.demolab.com/badge/-Sponsor-ea4aaa?style=for-the-badge&logo=heart&logoColor=white)](https://github.com/sponsors/cuong-tran "Sponsor me on GitHub")

<div align="left">
A free and open source manga reader which is based off TachiyomiSY & Mihon/Tachiyomi. This fork is meant to provide new & useful features while regularly take features/updates from Mihon or other forks like SY, J2K and Neko...

![screenshots of app](./.github/readme-images/screens.png)

<div align="left">

## Features

### Komikku's unique features:
- `Suggestions` automatically showing source-website's recommendations / suggestions / related to current entry for all sources.
- `Hidden categories` to hide yours things from *nosy* people.
- `Auto theme color` based on each entry's cover for entry View & Reader.
- `App custom theme` with `Color palettes` for endless color lover.
- `Bulk-favorite` multiple entries all at once.
- Source & Language icon on Library & various places. (Some language flags are not really accurate)
- `Feed` now supports **all** sources, with more items (20 for now).
- Fast browsing (for who with large library experiencing slow loading)
- Grouped entries in Update tab (inspired by J2K).
- Update notification with manga cover.
- Auto `2-way sync` progress with trackers.
- Chips for `Saved search` in source browse
- `Panorama cover` showing wide cover in full.
- `Merge multiple` library entries together at same time.
- `Range-selection` for Migration.
- Ability to `enable/disable repo`, with icon.
- `Update Error` screen & migrating them away.
- `to-be-updated` screen: which entries are going to be checked with smart-update?
- `Search for sources` & Quick NSFW sources filter in Extensions, Browse & Migration screen.
- `Feed` backup/restore/sync/re-order.
- Long-click to add/remove single entry to/from library, everywhere.
- Docking Read/Resume button to left/right.
- In-app progress banner shows Library syncing / Backup restoring / Library updating progress.
- Auto-install app update.
- Configurable interval to refresh entries from downloaded storage.
- Forked from SY so everything from SY.
- Always up-to-date with Mihon & SY
- More app themes & better UI, improvements...


<details>
  <summary>Features from Mihon / Tachiyomi</summary>

#### All up-to-date features from Mihon / Tachiyomi (original), include:

* Online reading from a variety of sources
* Local reading of downloaded content
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support: [MyAnimeList](https://myanimelist.net/), [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://mangaupdates.com), [Shikimori](https://shikimori.one), [Bangumi](https://bgm.tv/)
* Categories to organize your library
* Light and dark themes
* Schedule updating your library for new chapters
* Create backups locally to read offline or to your desired cloud service
* Continue reading button in library

</details>

<details>
  <summary>Features from Tachiyomi SY</summary>

#### All features from TachiyomiSY:
* Feed tab, where you can easily view the latest entries or saved search from multiple sources at same time.
* Automatic webtoon detection, allowing the reader to switch to webtoon mode automatically when viewing one
* Manga recommendations, uses MAL and Anilist, as well as Neko Similar Manga for Mangadex manga (Thanks to Az, She11Shocked, Carlos, and Goldbattle)
* Lewd filter, hide the lewd manga in your library when you want to
* Tracking filter, filter your tracked manga so you can see them or see non-tracked manga, made by She11Shocked
* Search tracking status in library, made by She11Shocked
* Custom categories for sources, liked the pinned sources, but you can make your own versions and put any sources in them
* Manga info edit
* Manga Cover view + share and save
* Dynamic Categories, view the library in multiple ways
* Smart background for reading modes like LTR or Vertical, changes the background based on the page color
* Force disable webtoon zoom
* Hentai features enable/disable, in advanced settings
* Quick clean titles
* Source migration, migrate all your manga from one source to another
* Saving searches
* Autoscroll
* Page preload customization
* Customize image cache size
* Batch import of custom sources and featured extensions
* Advanced source settings page, searching, enable/disable all
* Click tag for local search, long click tag for global search
* Merge multiple of the same manga from different sources
* Drag and drop library sorting
* Library search engine, includes exclude, quotes as absolute, and a bunch of other ways to search
* New E-Hentai/ExHentai features, such as language settings and watched list settings
* Enhanced views for internal and integrated sources
* Enhanced usability for internal and delegated sources

Custom sources:
* E-Hentai/ExHentai

Additional features for some extensions, features include custom description, opening in app, batch add to library, and a bunch of other things based on the source:
* 8Muses (EroMuse)
* Mangadex
* NHentai
* Puruin
* LANraragi

</details>

## Issues, Feature Requests and Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to change.

<details><summary>Issues</summary>

[Website](https://komikku-app.github.io/)

1. **Before reporting a new issue, take a look at the [FAQ](https://komikku-app.github.io/docs/faq/general), the [changelog](https://github.com/komikku-app/komikku/releases) and the already opened [issues](https://github.com/komikku-app/komikku/issues).**
2. If you are unsure, ask here: [![Discord](https://img.shields.io/discord/1242381704459452488.svg?label=&labelColor=6A7EC2&color=7389D8&logo=discord&logoColor=FFFFFF)](https://discord.gg/85jB7V5AJR)

</details>

<details><summary>Bugs</summary>

* Include version (More → About → Version)
 * If not latest, try updating, it may have already been solved
 * Preview version is equal to the number of commits as seen on the main page
* Include steps to reproduce (if not obvious from description)
* Include screenshot (if needed)
* If it could be device-dependent, try reproducing on another device (if possible)
* Don't group unrelated requests into one issue

Use the [issue forms](https://github.com/komikku-app/komikku/issues/new/choose) to submit a bug.

</details>

<details><summary>Feature Requests</summary>

* Write a detailed issue, explaining what it should do or how.
* Include screenshot (if needed).
</details>

<details><summary>Contributing</summary>

See [CONTRIBUTING.md](./CONTRIBUTING.md).
</details>

<details><summary>Code of Conduct</summary>

See [CODE_OF_CONDUCT.md](./CODE_OF_CONDUCT.md).
</details>

<div align="center">

### Credits

Thank you to all the people who have contributed!

<a href="https://github.com/komikku-app/komikku/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=komikku-app/komikku" alt="Komikku app contributors" title="Komikku app contributors" width="800"/>
</a>

![Visitor Count](https://count.getloli.com/get/@komikku-app?theme=capoo-2)

### Disclaimer

The developer(s) of this application does not have any affiliation with the content providers available, and this application hosts zero content.

<div align="left">

## License

    Copyright 2015 Javier Tomás

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.

---

## Updating this fork (更新移植)

当上游两个仓库有更新时,需要重新移植/合并。**完整的分步方法见 [`PORTING_GUIDE.md`](./PORTING_GUIDE.md)**,按以下场景选择:

- **Komikku 上游更新** → `git fetch origin && git merge origin/master`,手动解决 `// KMK` 集成点冲突(见指南第 2 节「场景一」)。
- **mihon_img_upscale 引擎更新** → 复制 `cpp/`、`assets/`、`third_party/ncnn`,重放本 fork 定制(见指南第 2 节「场景二」)。

更新后务必按指南第 3 节「验证清单」检查:`.so` 已打包、无 QNN 库、debug 签名、增强功能可用。
