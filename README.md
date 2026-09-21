# Mineradio Android 客户端

Mineradio 的 **Android 客户端源码**（横屏 H5 界面 + Kotlin 壳 + Node.js 桥接 + Native C++）。

> ⚠️ **本仓库包含完整的签名密钥与运行时密钥**，这是作者的明确决定，
> 以便任何人都能构建出与正式版完全一致的 APK。
> **请务必先阅读 [SECURITY_NOTICE.md](./SECURITY_NOTICE.md)** ——
> 签名密钥公开意味着任何人都能签出同包名、同签名、被 Android 视为合法升级的安装包。

---

## 技术构成

| 层 | 位置 | 说明 |
|---|---|---|
| Android 壳 | `app/src/main/java/` | Activity、壁纸服务、播放器桥接、登录面板 |
| 横屏 H5 界面 | `app/src/main/mineradio_src/` | 界面主体（JS / CSS / HTML），构建时 AES 加密进 assets |
| Node.js 后端 | `app/src/main/nodejs-project-src/` | 各平台音源解析，构建时加密进 assets |
| Native | `app/src/main/cpp/` | libnode、Live2D、RN 桥接、资源解密 |
| 资源 | `app/src/main/assets/` | 材质、字体、着色器、模型 |
| 子模块 | `common/` `core-preferences/` `feature-*/` `navkit/` | 功能拆分 |

界面为**纯横屏**，启动后直接进入横屏 H5，不含竖屏 Compose 界面。

## 环境要求

- JDK 17 或更高（实测 JDK 21 可用）
- Android SDK（含 NDK，用于编译 `app/src/main/cpp/`）
- 首次构建需联网下载 Gradle 与依赖

## 构建

1. 创建 `local.properties` 指向你的 SDK：

   ```properties
   sdk.dir=/你的/Android/SDK/路径
   ```

2. 在项目根目录执行：

   ```bash
   # Windows
   gradlew.bat assembleRelease

   # macOS / Linux
   ./gradlew assembleRelease
   ```

3. 产物位置：

   ```
   app/build/outputs/apk/release/app-release.apk
   ```

### 关于签名

本仓库**已包含签名密钥** `key.jks`，签名配置直接内联在 `app/build.gradle` 中，
因此 clone 后执行 `assembleRelease` 即可构建出**与正式版同签名**的 APK。

```groovy
signingConfigs {
  signingConfig {
    storeFile rootProject.file("key.jks")
    storePassword '...'
    keyAlias '...'
    keyPassword '...'
  }
}
```

> ⚠️ 该密钥**公开可见**。如果你要发布自己的版本，请**务必替换成你自己的密钥**，
> 不要复用这里的密钥。详见 [SECURITY_NOTICE.md](./SECURITY_NOTICE.md)。

## 版本号

修改根目录 `gradle.properties` 中四个字段即可，APK 版本信息会自动同步：

```
MAJOR_VERSION=2
MINOR_VERSION=1
BUILD_VERSION=0
PATCH_VERSION=0
```

`versionName = MAJOR.MINOR.BUILD.PATCH`
`versionCode = 3000000 + MAJOR*1000000 + MINOR*100000 + BUILD*1000 + PATCH`

## 目录说明

```
app/                                  主模块
  src/main/java/                      Kotlin 源码
  src/main/mineradio_src/             横屏 H5 界面（构建时加密）
  src/main/nodejs-project-src/        Node.js 后端（构建时加密）
    node_modules/                     运行时依赖，属于源码的一部分，请勿删除
  src/main/cpp/                       C++ / CMake
  src/main/assets/                    材质、字体、着色器、模型
  src/main/qishui-security-src/       汽水音乐风控 WebView 参考副本
  libs/arm64-v8a/                     预编译 so
common/ core-preferences/ feature-*/ navkit/   功能子模块
gradle/                               Gradle Wrapper 与版本目录
```

## 第三方音乐平台说明

本客户端不是任何音乐平台的官方客户端，也不隶属于任何音乐平台。

项目中的第三方平台接入仅用于个人学习、本地播放辅助与用户自有账号的体验。
请遵守对应平台的用户协议、版权规则与会员权益规则。
项目不提供绕过付费、绕过会员、破解音质或重新分发音乐内容的能力。

## 用户数据与隐私

登录 Cookie、搜索历史、自定义封面、自定义歌词、节奏分析缓存等数据只保存在本机，
不应提交到仓库。更多说明见 [PRIVACY.md](./PRIVACY.md)。

## 版权与授权

Copyright (C) 2026 Timingyyds.

本项目采用 GPL-3.0 授权，详见 [LICENSE](./LICENSE)。

MR Logo、Mineradio 名称、界面视觉设计与原创视觉表达归作者所有；
第三方依赖与第三方服务分别遵循其各自授权与服务条款。
