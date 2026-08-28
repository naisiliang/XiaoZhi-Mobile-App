# XiaoZhi Mobile App v0.4.0

Android 小智手机助手：本地离线唤醒、本地中文语音指令识别、连续会话、手机控制和可选 AI 对话。

## v0.4.0 重点

- sherpa-onnx KWS：离线识别“小智小智”。
- sherpa-onnx Paraformer：唤醒后的中文指令也在手机本地识别。
- `VoiceCommandNormalizer`：把“请打开微信 / 打开 q q / 来首歌 / 把音乐停掉”等口语或常见 ASR 结果标准化后，再进入现有 `CommandRouter -> PhoneController`。
- 透明语音悬浮层：授权“显示在其他应用上层”后，唤醒会在桌面/其他 App 上方显示发光圆环、监听/识别/执行状态、识别文字和动态波形。
- 连续会话：第一次喊“小智小智”后可连续说多条命令，说“再见 / 退出对话 / 休息吧”退出。
- 手机控制不需要 AI API；普通聊天才使用可选 OpenAI-compatible `/v1/chat/completions`。

## 构建

推送到 GitHub 后，Actions 会下载 KWS + Paraformer 本地模型并生成：

`XiaoZhi-Mobile-v0.4.0-debug.apk`
