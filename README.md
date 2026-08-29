# XiaoZhi Mobile App v0.6.3

Android 本地语音助手：离线唤醒、离线 Paraformer ASR、连续会话、任意已安装 App 控制、地图导航、AI 安全工具调用。

## v0.6.3 重点

- 修复 `小白小白` 等自定义中文唤醒词：兼容 pinyin4j 第三声 Unicode 错码，并按 sherpa-onnx 官方 ppinyin 规则生成 `声母 + 带声调韵母` token。
- 默认 `小智小智` 仍使用稳定 bundled KWS stream。
- 自定义 KWS 创建失败时保留旧唤醒词，并在通知栏显示具体失败原因。
- 成功执行本地指令或 AI 安全工具后会播报结果，例如 `已打开微信`、`音量已调大`，TTS 完成后约 120ms 恢复监听。
- 支持完整已安装 App Registry、位置/附近搜索、高德/百度/系统地图。
- 支持 AI Base URL、Chat Completions / Responses 自动检测、8 轮会话记忆和安全工具白名单。

## 构建

GitHub Actions 目标产物：

`XiaoZhi-Mobile-v0.6.3-debug.apk`

架构：arm64-v8a；compile/target SDK 35；minSdk 26。

> 直接安装版使用 `QUERY_ALL_PACKAGES` 以实现尽可能完整的 App 发现。若未来发布 Google Play，需要单独评估商店政策合规版本。
