# 安全说明 · 密钥公开声明

**⚠️ 请先阅读本文件再使用本仓库。**

本仓库**包含完整的密钥材料**，包括发布签名密钥与运行时加密密钥。
这是作者的明确决定，目的是让任何人都能构建出与本项目正式版**完全一致**的 APK。

---

## 一、本仓库包含哪些密钥

### 1. 发布签名密钥（⚠️ 影响最大）

| 位置 | 内容 |
|---|---|
| `key.jks` | Release 签名密钥库 |
| `app/build.gradle` | `storePassword` / `keyPassword` / `keyAlias` 明文写在签名配置里 |

**这意味着什么：**

签名密钥是 Android 用来判断「这个安装包是否出自同一个开发者」的唯一依据。
密钥公开后，**任何人都可以签出与本应用同包名（`com.mineradio.app`）、同签名的 APK**，
而 Android 会把它识别为本应用的**合法升级包**。

请特别注意：

- 这**不是**「反编译能看到的东西」。密钥原本只存在于开发者本地，
  不像运行时密钥那样已经打包进 APK。公开它是**新增**了一份外界原本拿不到的材料。
- 这一旦公开就**无法收回**。Git 的历史会被镜像、fork、缓存，
  即使之后删除文件，历史提交里依然存在。
- 想止损只能**轮换密钥**，但轮换后已安装的旧版本无法直接升级，用户需要卸载重装。

如果你只是想学习本项目的实现，**不需要**用到这个密钥；
建议 clone 后自行替换成你自己的密钥再构建。

### 2. 运行时资源加密口令

客户端有一套资源加密机制（MENC 格式：`MENC(4) + IV(16) + AES-256-CBC/PKCS5`），
密钥由 `SHA-256(passphrase)` 派生。该口令出现在 4 处，**均为真实值**：

| 文件 | 作用 |
|---|---|
| `app/build.gradle` | 构建期加密 `mineradio_src/` 与 `nodejs-project-src/` |
| `app/src/main/java/.../AssetDecryptor.kt` | Java 层解密回退 |
| `app/src/main/nodejs-project-src/start.js` | Node 侧 `Module._extensions['.js']` 解密钩子 |
| `app/src/main/cpp/mr_decrypt.c` | C 层分片存储（`ASSET_PASS_A` XOR `ASSET_MASK_A`） |

这属于**客户端内置密钥**，本来就在 APK 里、反编译也能拿到，
公开它只是省去了别人逆向的功夫。**风险远低于签名密钥。**

### 3. 内置 RSA 私钥

`app/src/main/cpp/mr_private_key.h` 以「5 个分片 + 字节置换」的方式存储一个 PKCS#8 RSA 私钥
（`MR_SHARD_1..5` / `MR_PERM_1..5`），运行时由 `mr_recover_key()` 还原，
用于解密一把 AES 密钥。

同样是客户端内置材料，性质与第 2 项相同。

---

## 二、本仓库**不含**的内容

以下内容未被提交，`.gitignore` 已排除：

- `local.properties`（本机 Android SDK 路径，因人而异）
- 各平台登录 Cookie 与 token（`.cookie` / `.qq-cookie` / `.qishui-cookie` 等）
- `keystore.properties`（本仓库使用 `app/build.gradle` 内联签名配置，不需要该文件）

---

## 三、如果你要基于本仓库发布自己的版本

1. **不要复用本仓库的签名密钥** —— 生成你自己的，并妥善保管
2. **替换资源加密口令**，并保证构建脚本、Kotlin、Node、C++ **四处一致**，
   否则运行时会解密失败
3. **用你自己的 RSA 密钥**重新生成 `mr_private_key.h` 分片，
   并确保与 `mr_decrypt.c` 中 `mr_recover_key()` 的还原逻辑匹配
4. 确认 `local.properties`、Cookie 与 token 文件没有被提交

---

## 四、免责说明

本项目仅用于技术学习与交流。所有第三方音乐平台的解析能力均来自公开接口，
不存储、不分发任何受版权保护的音频内容。
请遵守各平台服务条款与当地法律法规，不要用于商业用途。
下载的内容请在 24 小时内删除。
