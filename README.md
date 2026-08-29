# XiaoZhi Mobile App v0.6.0

Android 小智手机助手：本地离线唤醒、本地中文语音识别、透明桌面语音悬浮层、连续会话、任意已安装 App 发现/启动诊断、地图导航/附近搜索，以及受严格白名单约束的 AI 安全工具调用。

## v0.6.0 重点

- **任意 App 控制**：`QUERY_ALL_PACKAGES + Launcher Query + Installed Applications` 合并建立本机应用索引，支持用户别名、已知别名、精确/包含/模糊匹配，并返回可诊断的启动失败原因。
- **地图与位置**：支持高德、百度和系统地图；可说“用高德导航到广州南站”“附近帮我找商场”。只在用户主动使用附近搜索时读取一次前台位置，不申请后台定位。
- **自定义唤醒名字**：默认助手名“小智”、唤醒短语“小智小智”；用户可改成“小白 / 小白小白”等，保存后运行时重新编译 sherpa-onnx 关键词，不需要重新安装 APK。
- **系统 TTS 声音**：可选择设备上的中文 Voice、试听、调整语速和音调。
- **AI Base URL**：只填 Base URL，例如 `https://api.example.com`，程序可自动检测 Chat Completions / Responses，并提供“测试 AI 接口”按钮。
- **8 轮会话记忆**：同一次连续唤醒会话内保留最近 8 个完整用户/助手回合，超时、主动退出或服务停止后清空。
- **安全 AI 工具**：AI 只可规划 `open_app / navigate / search_nearby / open_web / media / volume / flashlight` 等白名单操作；真正执行必须经过本机 `SafeToolExecutor`。禁止删除、发消息、支付转账、安装卸载、读取隐私数据、Shell 和任意 Intent/URI。
- v0.5.0 的可配置 20 秒会话超时、退出话术、唤醒回复、立即重新监听、透明悬浮层、自定义桌面快捷图标全部保留。
- sherpa-onnx KWS + Paraformer ASR 继续完全在手机本地运行，持续麦克风音频不会发送给 AI。

## 第一次安装/升级后的设置

1. 安装 APK。
2. 允许麦克风、通知、相机权限；按需要授权透明悬浮层。
3. 只有需要“附近找商场/医院/加油站”等功能时，再授权前台位置权限。
4. 设置助手名字和唤醒短语，点击“保存并应用唤醒词”。
5. 选择/试听系统 TTS 声音，并设置语速/音调。
6. 配置 Base URL、API Key、模型和 API 模式，点击“测试 AI 接口”。
7. 开启后台离线唤醒。

## App 诊断

设置页可查看：

- 已发现应用数量；
- 应用显示名 / package / 发现来源；
- 最近一次 App 匹配解释；
- 当前 KWS 唤醒短语；
- AI 接口 HTTP / 模式 / 模型 / 延迟 / 错误摘要。

本仓库 v0.6.0 是**直接安装版**，为了实现尽可能完整的 App 发现使用 `QUERY_ALL_PACKAGES`。未来如果发布 Google Play，需要单独做商店合规版本并重新评估该权限。

## 构建

推送到 GitHub 后，Actions 会下载 KWS + Paraformer 本地模型并生成：

`XiaoZhi-Mobile-v0.6.0-debug.apk`
