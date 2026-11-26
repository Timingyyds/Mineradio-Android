# Mineradio · Android 客户端源码

Mineradio 音乐播放器的 Android 端源码，当前版本 **2.2.2**（versionCode 20204）。

界面是一套完整的横屏 H5 应用，跑在 Capacitor 的 WebView 里；播放器本体、音源解析、
壁纸引擎、桌面萌宠这些「系统级」能力由 Kotlin / Java 侧的 Capacitor 插件补齐。
启动后直接进入横屏主界面，没有另做一套原生 UI。

> 本仓库只包含源码。**不含任何签名密钥、账号凭据或网盘分享地址**，
> 自行构建需要在本地准备签名（见下文「构建」）。

---

## 目录结构

| 路径 | 内容 |
|---|---|
| `public/` | 横屏 H5 界面主体：`index.html`、`css/`、`js/modules/`、`js/ported/`、`folia-visualizer/` |
| `public/js/modules/` | 界面逻辑，按域拆成 `00-state`、`01-scene`、`02-visual`、`03-beat`、`04-shelf`、`05-playback`、`06-lyrics`、`07-fx`、`08-account`、`10-shell` 等目录 |
| `public/js/index-loader.js` | 模块加载器，按顺序把上面的模块挂到全局运行时 |
| `android/app/src/main/java/com/mineradio/android/` | Capacitor 插件（`FoliaCore`、`FoliaBridge`、`MineradioPlugins`、`MineradioSystem`、`MpkgWallpaper` 等） |
| `android/app/src/main/java/com/mineradio/android/wallpaper/` | 动态壁纸服务、MPKG 场景解析与 GL 渲染 (GLES20/GLES30)、Live2D 萌宠 |
| `android/app/src/main/assets/` | 材质、字体、着色器 (`*.frag`)、Live2D 模型、手势识别模型 |
| `android/app/libs/arm64-v8a/` | 壁纸场景与 Live2D 的原生库（仅 arm64-v8a） |
| `scripts/` | 打包与资源处理脚本（`build-public.mjs`、`port-node-module.cjs` 等） |
| `capacitor.config.json` | 应用 id `Mineradio.timing`、`webDir: public`、WebView 相关开关 |

## 功能构成

- **播放**：网易云、QQ 音乐、酷狗、汽水、Spotify 的音源解析与在线切换，播放队列、音量/音质、音频缓存
- **视觉**：粒子与着色器视觉、3D 歌单架、Sonic 工作台、音频频谱地形
- **歌词**：逐行/逐字歌词、歌词舞台、桌面歌词（`desktop-lyrics.html`）
- **壁纸引擎**：MPKG 场景壁纸解析与渲染、在线壁纸库、桌面萌宠（Live2D）
- **插件系统**：`12-plugin-runtime.js` 提供插件运行时，`13-online-plugin-store.js` 是在线插件面板

## 在线插件目录（需自行配置）

在线插件面板通过一个网盘「文件夹分享」地址拉列表，代码里 **不内置任何地址**：

```js
// public/js/modules/13-online-plugin-store.js
var OFFICIAL_PLUGIN_FOLDER = '';   // 官方插件目录，留空 = 未配置
var ONLINE_PLUGIN_FOLDER   = '';   // 第三方插件目录，留空 = 未配置
```

留空时面板会直接显示「未配置插件目录」，不会发任何网络请求。
要启用，把你自己维护的分享文件夹地址填进去即可。

## 构建

```bash
# 1. 前端
npm install
npm run build          # 由 scripts/build-public.mjs 处理 public/ 资源
npx cap sync android

# 2. Android
cd android
./gradlew assembleDebug      # 或 assembleRelease
```

要求：JDK 17+、Android SDK（compileSdk / NDK 见根 `build.gradle`、`android/variables.gradle`），
首次构建需联网拉 Gradle 与依赖。ABI 固定 `arm64-v8a`，`minSdkVersion` 见 `variables.gradle`。

### 签名

`android/app/build.gradle` 里预留了签名入口，按以下优先级查找密钥文件：

1. 环境变量 `DSH_KEYSTORE` 指向的路径
2. 源码根目录 `.keys/dsh-release.keystore`
3. `android/app/dsh-release.keystore`

密码 / 别名可用 `DSH_STOREPASS`、`DSH_KEYALIAS`、`DSH_KEYPASS` 覆盖。
仓库不提供任何密钥文件，请自己生成：

```bash
keytool -genkeypair -v -keystore android/app/dsh-release.keystore \
  -alias dshkey -keyalg RSA -keysize 2048 -validity 10000
```

## 许可

GPL-3.0-only，见 [LICENSE](./LICENSE)。第三方组件与出处见 [NOTICE.md](./NOTICE.md)，
隐私说明见 [PRIVACY.md](./PRIVACY.md)。