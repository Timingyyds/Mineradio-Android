# NOTICE

Mineradio Android 客户端使用了以下第三方项目、库或服务。各项目版权归其原作者所有。

## 第三方库与运行时

- **Node.js Mobile / libnode** —— Node.js 运行时（应用内后端）
- **Live2D Cubism SDK** —— 桌面萌宠模型渲染
- **TarsosDSP** —— 音频 FFT 与节奏分析
- **Three.js** —— 粒子与 3D 视觉
- **GSAP** —— 界面动效
- **music-tempo** —— 节拍检测
- **mpg123-decoder** —— MP3 解码
- **NeteaseCloudMusicApi** —— 网易云音乐接口参考实现

## 社区贡献与移植

- **Cuefield AutoMix planner/runtime**：为本地实验性测试而移植，
  来自 [SLYysl/cuefield-mineradio](https://github.com/SLYysl/cuefield-mineradio)（GPL-3.0）。
  该仓库中可选的远端反馈组件未被引入。
- **汽水音乐 Passport Web 扫码认证桥接**：聚焦移植，
  来自 [Wx2yZx/Mineradio-Qishui-QR-Login](https://github.com/Wx2yZx/Mineradio-Qishui-QR-Login)（GPL-3.0-only）。
  仅接入官方二维码创建/轮询、安全签名宿主、会话保持与二次校验路径；
  Mineradio 保留自己的曲库、歌单、权益与播放适配层。
  随附的字节跳动/汽水音乐 Web 安全运行时资源版权归其各自权利人所有，
  仅用于与用户本人的官方账号会话互通。

## 第三方服务

Mineradio 可能与网易云音乐、QQ 音乐、酷狗音乐、汽水音乐、Spotify 等第三方音乐服务
进行**用户自有账号**相关的本地客户端交互。

Mineradio 不是任何音乐平台的官方客户端，也不隶属于上述任何平台。
请遵守对应平台的用户协议、版权规则与会员权益规则。
