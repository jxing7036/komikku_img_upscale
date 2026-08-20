# 移植/更新指南 (Porting Guide)

本 fork(`komikku_img_upscale`)**不是独立项目**,而是 **Komikku + mihon_img_upscale 的 upscale 引擎**的合成产物。当上游两个仓库更新后,需要按本文档重新移植/合并,以同步上游修复与新功能。

---

## 0. 仓库拓扑

```
本 fork = Komikku(基座) + mihon_img_upscale 的 upscale 引擎
         ↑                     ↑
   komikku-app/komikku   HaoweiLi97/mihon_img_upscale
```

| 仓库 | 角色 | 地址 |
|---|---|---|
| `komikku` | 基座(应用主体,所有普通功能) | https://github.com/komikku-app/komikku |
| `mihon_img_upscale` | upscale 引擎来源(waifu2x/Real-CUGAN/Real-ESRGAN...) | https://github.com/HaoweiLi97/mihon_img_upscale |
| `komikku_img_upscale` | 本 fork(合成) | https://github.com/jxing7036/komikku_img_upscale |

本 fork 的 `master` 基于 Komikku 上游某 commit 分叉,叠加 upscale 引擎与本 fork 的定制。

---

## 1. 移植边界(什么来自哪个上游)

移植内容分为 **三类**,处理方式完全不同:

### 类型 A —— 原生引擎(来自 mihon_img_upscale,可直接复制覆盖)
这些文件在 mihon_img_upscale 中,与 komikku 无耦合,可整体覆盖更新:

```
app/src/main/cpp/
├── anime4k.cpp / anime4k.h
├── waifu2x.cpp / waifu2x.h
├── waifu2x_jni.cpp
├── qnn_backend.cpp / qnn_backend.h
├── waifu2x_fused_preproc.comp
├── waifu2x_fused_postproc.comp
├── shaders.h
└── CMakeLists.txt        ← ⚠️ 例外:见下方「CMakeLists 特例」

third_party/ncnn-20260113-android-vulkan/   ← ncnn SDK(5 ABI),整体覆盖

app/src/main/assets/                        ← 模型资产,整体覆盖
├── waifu2x-models/  waifu2x-models-nose/  waifu2x-models-upconv7/
├── realcugan-models/  realcugan-pro-models/
├── realesrgan-models/
├── w2xex-* / animejanai-* / span-nomosuni / sudo-ultracompact / anime4k/
```

**CMakeLists 特例**:mihon_img_upscale 的 CMakeLists 含 QNN SDK 条件编译;本 fork **已移除 NPU,统一 Vulkan**,所以覆盖后需手动把 QNN 相关段替换为固定 `MIHON_ENABLE_QNN=0`。**不要**直接覆盖 komikku 的 CMakeLists。

### 类型 B —— Kotlin 封装(来自 mihon_img_upscale,但已含本 fork 定制,需手动合并)
```
app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/
├── Waifu2x.kt            ← 引擎 JNI 封装
├── ImageEnhancer.kt      ← 优先级调度(⚠️ 已加本 fork 的排队优化,勿直接覆盖)
└── ImageEnhancementCache.kt
app/src/main/java/eu/kanade/tachiyomi/util/image/ImageFilter.kt
```
> 这些文件 mihon_img_upscale 和本 fork 都在改。更新时要**以 mihon_img_upscale 新版本为基底**,再重放本 fork 的定制:
> - `ImageEnhancer.kt`:本 fork 有「当前页优先 + 丢弃过期预载 + 抢占非当前页」的调度优化(`STALE_PRELOAD_PRUNE_BEHIND`、`preemptActiveRequestIfNotTarget`、`processRequest` 的过期检查),都在 `// KMK -->` 块内,更新时要保留。

### 类型 C —— komikku 本体 + 集成点(来自 Komikku,手动 merge)
这些是 Komikku 应用的主体文件,upscale 只是往里面"打洞"。**跟随 Komikku 上游更新时,靠 git merge**,但要注意本 fork 在其中插入了 `// KMK -->` 块:

```
app/src/main/java/eu/kanade/tachiyomi/data/coil/TachiyomiImageDecoder.kt   ← enhanced 解码
app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt          ← 增强按钮
app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt
app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt
app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonConfig.kt / WebtoonPageHolder.kt / WebtoonViewer.kt
app/src/main/java/eu/kanade/tachiyomi/ui/reader/loader/HttpPageLoader.kt / DownloadPageLoader.kt
app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt  ← waifu2x/realcugan 偏好
app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderBottomButton.kt ← 增强按钮开关
app/src/main/java/eu/kanade/presentation/reader/settings/ColorFilterPage.kt   ← 增强设置页
i18n-kmk/src/commonMain/moko-resources/  ← 增强文案(KMR)
app/build.gradle.kts                       ← ncnn CMake 接入、debug 签名 release
```

---

## 2. 两种更新场景

### 场景一:Komikku 上游有更新(最常见的日常更新)
目标:把 Komikku 的新代码合进来,同时保留 upscale 引擎与本 fork 定制。

```bash
# 1. 切到 master,拉上游
git checkout master
git fetch origin            # origin = komikku-app/komikku

# 2. 把上游 master 合进来(git 会自动处理大部分文件;冲突集中在类型 C 的 // KMK 块)
git merge origin/master
#   → 冲突文件通常是:
#     TachiyomiImageDecoder.kt / ReaderPageImageView.kt / ReaderPreferences.kt
#     ColorFilterPage.kt / ReaderBottomButton.kt / ReaderBottomBar.kt / build.gradle.kts ...
#   逐个解决冲突,保留 // KMK --> ... // KMK <-- 块,删除上游的旧写法(对照本次 git diff)

# 3. 若上游改了 build.gradle.kts 的版本/依赖,重新核对:
#    - externalNativeBuild.cmake 的 NCNN_SDK_DIR 参数还在
#    - release buildType 的 signingConfig = debug.signingConfig 还在
#    - splits.abi 仍含 4 个 ABI
```

> **要点**:类型 A(原生引擎)在场景一中通常**不用动**,因为 Komikku 上游不包含它们。只有当 mihon_img_upscale 也更新时才需要动(见场景二)。

### 场景二:mihon_img_upscale 引擎有更新
目标:把超分引擎的新版本同步进来。

```bash
# 0. 先给 mihon_img_upscale 加 remote(只需一次)
git remote add upscale-upstream https://github.com/HaoweiLi97/mihon_img_upscale.git

# 1. 拉取并对比
git fetch upscale-upstream
git diff HEAD upscale-upstream/master -- app/src/main/cpp/ app/src/main/assets/ \
    app/src/main/java/eu/kanade/tachiyomi/util/waifu2x/ \
    app/src/main/java/eu/kanade/tachiyomi/util/image/ImageFilter.kt

# 2. 复制类型 A(原生 + 资产),直接覆盖:
cp -r <upscale>//app/src/main/cpp/*     app/src/main/cpp/
cp -r <upscale>/third_party/ncnn-*     third_party/
cp -r <upscale>/app/src/main/assets/*   app/src/main/assets/
# ⚠️ 但 CMakeLists.txt 例外:覆盖后要重新把 QNN 段改为固定 MIHON_ENABLE_QNN=0

# 3. 类型 B(Kotlin 封装):用 mihon_img_upscale 新版本做基底,重放本 fork 的 // KMK 定制
#    (主要是 ImageEnhancer.kt 的调度优化,Waifu2x.kt/ImageEnhancementCache.kt 一般可直接覆盖)

# 4. 重新编译验证
./gradlew spotlessApply
./gradlew spotlessCheck
./gradlew assembleRelease
```

---

## 3. 更新后必须做的验证清单

```bash
./gradlew spotlessApply && ./gradlew spotlessCheck
./gradlew assembleRelease
```

检查:
1. **`libwaifu2x-jni.so`** 已打进 APK(4 个 ABI)。
   ```powershell
   # 用 zip 查看 universal APK
   # 应含 lib/arm64-v8a/libwaifu2x-jni.so 等 4 个,以及 assets/ 下的模型
   ```
2. **不含 QNN 库**:APK 内不应有 `libQnnHtp*.so` / `libQnnSystem.so`。
3. **release 用 debug 签名**:
   ```powershell
   & <sdk>/build-tools/<ver>/apksigner.bat verify --print-certs app-universal-release.apk
   # 应显示 CN=Android Debug
   ```
4. **功能**:
   - 阅读器 → 设置 → 颜色滤镜页,能看到增强设置(Vulkan 后端,无 NPU 选项)
   - 阅读器底部有图像增强按钮,且可在「设置→阅读→底部按钮」开关
   - 快速翻页时,当前页优先增强(排队优化生效)

---

## 4. 已知定制与本 fork 特有改动(更新合并时要保留)

| 位置 | 定制内容 |
|---|---|
| `app/build.gradle.kts` | `release` buildType 用 `debug.signingConfig`;移除 QNN 依赖;ncnn CMake 接入 |
| `app/src/main/cpp/CMakeLists.txt` | 固定 `MIHON_ENABLE_QNN=0`(无 QNN SDK,纯 Vulkan) |
| `ColorFilterPage.kt` | 移除 NPU chip/逻辑,模型列表为全量,后端固定 Vulkan |
| `ReaderBottomButton.kt` / `ReaderBottomBar.kt` | 新增 `ImageEnhancement` 按钮,可在底部按钮设置中开关 |
| `ImageEnhancer.kt` | 排队优化:`STALE_PRELOAD_PRUNE_BEHIND`、`preemptActiveRequestIfNotTarget`、`processRequest` 过期丢弃 |
| `Waifu2x.kt` | 保留 QNN 相关方法但恒失效(不参与 UI,可留可清) |
| `i18n-kmk` | upscale 相关文案(base + zh-rCN + zh-rTW) |

> ⚠️ **anime4k assets 路径差异**:mihon_img_upscale 中 Anime4K 的 `.glsl` 在 `assets/anime4k/` 子目录;本 fork 目前平铺在 `assets/` 根目录,而 `Waifu2x.initAnime4K` 用 `open("anime4k/$name")` 读取。若 Anime4K 模型失效,请把 `.glsl` 放回 `assets/anime4k/` 子目录或同步修改读取路径。

---

## 5. 快速参考命令

```bash
# 更新 Komikku 上游
git fetch origin && git merge origin/master

# 更新 upscale 引擎
git fetch upscale-upstream
git diff HEAD upscale-upstream/master -- app/src/main/cpp app/src/main/assets

# 编译与验证
./gradlew spotlessApply && ./gradlew spotlessCheck
./gradlew assembleRelease
```
