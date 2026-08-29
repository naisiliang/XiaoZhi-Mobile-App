# XiaoZhi Mobile App v0.5.0

Android 小智手机助手：本地离线唤醒、本地中文语音识别、透明桌面语音悬浮层、连续会话、手机控制和可选 AI 对话。

## v0.5.0 重点

- 连续会话超时可配置，默认 20 秒；超时后自动播报用户自定义退出话术并返回“小智小智”待唤醒。
- 唤醒后的第一句回复可由用户在 App 内自定义。
- 本地控制执行后不再等待长 TTS 确认，直接重新进入监听。
- `InstalledAppRegistry` 扫描手机真实可启动应用，支持名称、包含关系、模糊匹配和用户别名。
- 无法执行的手机指令统一回复：`抱歉，我还不会这个指令，你可以换一个指令继续服务你`，并保持当前会话。
- 默认应用图标使用蓝粉渐变 Logo；用户可从相册选择图片创建/更新自定义桌面快捷图标，并恢复默认 Logo。
- sherpa-onnx KWS + Paraformer ASR 继续完全在手机本地运行。
- 手机控制仍统一走 `VoiceCommandNormalizer -> CommandRouter -> PhoneController`。

## App 别名

设置页每行一个，例如：

```text
B站=哔哩哔哩
小破站=哔哩哔哩
夸克浏览器=夸克
```

## 构建

推送到 GitHub 后，Actions 会下载 KWS + Paraformer 本地模型并生成：

`XiaoZhi-Mobile-v0.5.0-debug.apk`
