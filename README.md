# 小智手机助手 XiaoZhi Mobile v0.1.0

这是一个面向 Android 的“小智”语音助手 MVP，目标是把离线唤醒、AI 对话和手机控制放进同一个 APK。

## 已实现

- **完全本地离线唤醒**：sherpa-onnx KWS，默认唤醒词 `小智小智`
- **后台/息屏持续监听**：Android microphone foreground service + partial wakelock
- **唤醒后语音指令**：使用手机系统 SpeechRecognizer 获取一句话
- **本地直接执行，不消耗 AI Token**：
  - 播放 / 暂停音乐
  - 下一首 / 上一首
  - 音量增减 / 设置百分比
  - 打开微信、QQ音乐、网易云音乐、抖音、支付宝、淘宝、地图、Chrome 等
  - 打开手电筒 / 关闭手电筒
  - 导航到指定地点
  - 浏览器打开网址或搜索
- **AI 对话**：支持 OpenAI-compatible `/v1/chat/completions` 接口
- **TTS 语音回复**：Android TextToSpeech

## 实际工作流

```text
手机息屏/后台
    ↓
sherpa-onnx 本地 KWS（持续音频不上传）
    ↓
“小智小智”
    ↓
停止 KWS 并释放麦克风
    ↓
“小智：我在”
    ↓
手机 SpeechRecognizer 听一条指令
    ↓
┌────────────本地手机指令────────────┐
│ 播放/暂停/切歌/音量/打开App/导航等 │ → Android API 直接执行
└───────────────────────────────────┘
    ↓ 未命中本地指令
OpenAI-compatible AI API
    ↓
Android TTS 播报答案
    ↓
自动恢复“小智小智”离线监听
```

## 第一次安装后的使用步骤

1. 安装 APK，打开“**小智手机助手**”。
2. 允许麦克风、通知、相机（相机权限仅用于手电筒）。
3. 如果只测试手机控制，不需要填写 AI 接口。
4. 如需 AI 对话，填写：
   - 完整 Chat Completions URL，例如 `https://your-domain/v1/chat/completions`
   - API Key
   - 模型名称
5. 点击 **开启后台离线唤醒**。
6. 通知栏出现“小智手机助手”常驻通知后，可以熄屏。
7. 说：`小智小智`。
8. 听到“我在”后说：
   - `播放音乐`
   - `暂停音乐`
   - `下一首`
   - `打开微信`
   - `打开网易云音乐`
   - `音量大一点`
   - `音量调到 30`
   - `打开手电筒`
   - `导航到广州南站`
   - 或直接问 AI 问题。

## Android 版本与 CPU

- minSdk: Android 8.0 (API 26)
- targetSdk: API 35
- 当前构建仅打包 `arm64-v8a`，适合绝大多数现代 Android 手机。

> Android 14+ 对后台启动麦克风前台服务有限制，所以第一次/重启手机后需要先打开 App，由用户点击“开启后台离线唤醒”。服务已经启动后可以在后台和息屏状态持续监听。

## KWS 模型

工程不会把约几十 MB 的模型权重放进源码 ZIP。构建前执行：

```bash
./scripts/fetch-kws-model.sh
```

它会下载 `sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20` 并只复制 APK 需要的 chunk-16 模型文件到 `app/src/main/assets`。

关键词文件：

```text
x iǎo zh ì x iǎo zh ì @小智小智
```

## 自动生成 APK（GitHub Actions）

工程已经包含：

```text
.github/workflows/build-apk.yml
```

把整个工程推到 GitHub 后，Actions 会自动：

1. 安装 JDK 17
2. 安装 Android SDK 35
3. 安装 Gradle 8.9
4. 下载 sherpa-onnx KWS 模型
5. 编译 Debug APK
6. 生成 `XiaoZhi-Mobile-v0.1.0-debug.apk` artifact

## 本地 Android Studio 构建

安装 Android Studio + JDK 17 后：

```bash
./scripts/fetch-kws-model.sh
gradle :app:assembleDebug
```

APK 路径：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## AI 接口说明

当前 v0.1.0 使用标准 OpenAI-compatible Chat Completions：

```http
POST /v1/chat/completions
Authorization: Bearer <key>
Content-Type: application/json
```

因此可直接接 New API / One API / OpenAI-compatible 中转站。后续可再增加 `xiaozhi-esp32-server` 的原生 WebSocket/Opus 协议，让 ASR/TTS/实时打断也走小智服务端。

## 当前边界

- 离线的是 **唤醒检测**；唤醒后的语音转文字当前依赖手机系统 SpeechRecognizer，是否可完全离线取决于手机是否安装离线识别包。
- “播放/暂停/下一首”等使用 Android media key，可控制支持系统媒体会话的播放器。
- “打开某 App”优先使用已知包名，再按 Launcher 应用名称查找。
- 任意 App 内部自动点击/填写属于第二阶段，需要 Accessibility Service / UI Agent；本版本没有偷偷申请辅助功能权限。
- 重启自动恢复麦克风监听在 Android 14+ 有额外后台启动限制，本版选择用户主动开启，优先保证合规和稳定。

## 下一阶段建议

1. 接入 `xiaozhi-esp32-server` WebSocket + Opus，替代系统 SpeechRecognizer/TTS。
2. 增加 Phone MCP 工具协议，让服务端 LLM 可以动态调用手机工具。
3. 增加 Accessibility UI Agent，用于“打开微信后点击某个页面”等复杂操作。
4. 增加自定义唤醒词页面和灵敏度设置。
5. 增加开机恢复策略（参考 GPTWake 的 transparent shim / overlay 方案）。
