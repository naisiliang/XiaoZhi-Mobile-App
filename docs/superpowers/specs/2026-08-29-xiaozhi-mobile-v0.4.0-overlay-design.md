# XiaoZhi Mobile v0.4.0 — 透明语音悬浮层设计规范

日期：2026-08-29

## 目标

在现有 v0.3.1 基础上升级：

1. 让 sherpa-onnx 识别出的语音指令稳定走到与“本地手机控制测试”完全相同的 PhoneController 执行路径。
2. 说“小智小智”后，在桌面和其他 App 上方出现透明系统悬浮层。
3. 悬浮层实时显示“我在听 / 正在识别 / 正在执行 / 我听到的文字”等状态。
4. 保留连续会话：唤醒一次后可以连续说多条指令；说“再见 / 退出对话 / 休息吧 / 不用了”或超时后退出。

## 架构

### 保留现有组件

- `WakeService`：前台麦克风服务、KWS、本地 Paraformer ASR、连续会话、TTS。
- `CommandRouter`：本地意图与手机指令路由。
- `PhoneController`：打开 App、媒体控制、音量、手电筒、导航。
- `MainActivity`：设置页与本地控制测试页。

### 新增 `VoiceCommandNormalizer`

放在 Paraformer 输出和 `CommandRouter` 之间。

职责：

- 去除多余空格、标点、大小写差异；
- 将 `q q` 统一成 `qq`；
- 将 `扣扣` 统一成 `qq`；
- 将 `威信 / 微星` 等常见 ASR 错词统一成 `微信`；
- 将 `播放一下音乐 / 放音乐 / 来首歌` 统一为播放音乐意图；
- 将 `把音乐停掉 / 关掉音乐` 统一为停止音乐意图；
- 将 `帮我打开微信 / 进入微信` 统一为打开微信意图。

原始识别文字保留，用于悬浮层和故障诊断。

### 新增 `AssistantOverlayController`

由 `WakeService` 持有，通过 Android `WindowManager` 管理一个 `TYPE_APPLICATION_OVERLAY` 悬浮窗口。

职责：

- 检测到唤醒词时显示；
- 监听、识别、执行、播报、连续会话等待时更新状态；
- 会话结束后隐藏；
- 使用 `FLAG_NOT_FOCUSABLE` 和 `FLAG_NOT_TOUCH_MODAL`，不抢占当前 App 焦点；
- 没有悬浮窗权限时不崩溃，退回通知栏状态显示。

### 新增 `AssistantOverlayView`

使用原生 Android View，不使用 WebView。

视觉目标：

- 全屏透明背景；
- 中间偏上位置显示紧凑语音 HUD；
- 蓝紫色发光圆环；
- 主文案：`你好，有什么可以帮你？`
- 状态：`我在听… / 正在识别… / 正在执行…`
- 小字显示：`我听到：打开微信`
- 下方显示动态音频波形；
- 手机桌面或其他 App 继续清晰可见。

## 悬浮窗权限

Manifest 增加：

`android.permission.SYSTEM_ALERT_WINDOW`

MainActivity 增加按钮：

`授权桌面透明语音悬浮层`

行为：

- 已授权：显示“已授权”；
- 未授权：打开 Android 的“显示在其他应用上层”系统授权页；
- 未授权悬浮窗不影响离线语音助手本身继续工作。

## 语音状态流程

### 待机

- 前台服务运行；
- KWS 等待“小智小智”；
- 悬浮层隐藏。

### 唤醒

- `conversationActive = true`
- 悬浮层立即出现；
- 显示 `你好，有什么可以帮你？`
- 状态 `我在听…`
- TTS 播放“我在”；
- 之后开始本地指令录音。

### 本地听取

- 波形根据麦克风 RMS 动态变化；
- 显示 `我在听…`。

### 本地识别

- 显示 `正在识别…`
- Paraformer 转文字；
- 显示 `我听到：<原始识别文字>`。

### 标准化与路由

处理链：

`原始 ASR -> VoiceCommandNormalizer -> CommandRouter -> PhoneController`

如果原始文本与标准化文本不同，可同时显示：

`标准化：打开微信`

执行前显示：

`正在执行：打开微信`

### 本地控制成功

- 执行 PhoneController；
- TTS 简短确认；
- 悬浮层保持；
- 自动继续监听下一句；
- 不需要再次说“小智小智”。

### 普通 AI 问题

若没有匹配本地控制且配置了 AI：

- 显示 `正在思考…`
- 请求现有 AI Client；
- TTS 回答；
- 自动继续听下一句。

没配置 AI：

- 提示 AI 聊天未配置；
- 仍继续会话，不立即退出。

### 退出会话

说以下任一：

- 再见
- 退出对话
- 结束对话
- 休息吧
- 拜拜
- 不用了

或连续会话静默超时：

- 悬浮层淡出 / 隐藏；
- 回到只监听“小智小智”的待机状态。

## 本地语音指令必须覆盖

- `播放音乐 / 放音乐 / 播放一下音乐 / 来首歌`
- `暂停音乐 / 停一下音乐`
- `停止音乐 / 停止播放 / 把音乐停掉 / 关掉音乐`
- `下一首 / 下一曲`
- `上一首 / 上一曲`
- `打开微信 / 帮我打开微信 / 进入微信`
- `打开QQ / 打开 q q / 打开扣扣`
- `打开网易云音乐 / 打开QQ音乐`
- `音量大一点 / 音量小一点`
- `打开手电筒 / 关闭手电筒`
- 保留现有导航指令。

## App 启动原则

必须复用当前“本地手机控制测试”已经验证成功的 `PhoneController.openApp()`。

禁止另写一套“语音专用打开 App”逻辑。

因此：

`语音 -> 标准化 -> CommandRouter -> PhoneController`

与：

`本地输入框 -> CommandRouter -> PhoneController`

最终执行路径完全一致。

## 诊断信息

悬浮层和通知栏需要能显示：

- `我在听…`
- `正在识别…`
- `我听到：<raw>`
- `标准化：<normalized>`（当发生变化时）
- `正在执行：<intent>`
- App 未找到 / 执行失败等结果

这样后续能快速区分“ASR 识错”和“控制层失败”。

## 权限与安全

- 悬浮层不抢焦点，不阻挡底层 App 操作；
- 待机 KWS 时不显示悬浮层；
- 悬浮窗权限必须由用户主动在 Android 设置中授权；
- v0.4.0 不引入 AccessibilityService；
- 不需要屏幕截图；
- KWS 和指令 ASR 继续在本地 sherpa-onnx 执行；
- 持续监听原始音频不上传服务器。

## 预计修改文件

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt`
- `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt`
- `app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt`
- 新增 `VoiceCommandNormalizer.kt`
- 新增 `AssistantOverlayController.kt`
- 新增 `AssistantOverlayView.kt`
- `app/build.gradle.kts` -> v0.4.0
- `.github/workflows/build-apk.yml` -> `XiaoZhi-Mobile-v0.4.0-debug.apk`
- `tools/` 增加针对语音标准化、悬浮窗权限、版本与连续会话的回归测试。

## 验收标准

1. 本地测试输入框继续能正常打开微信/QQ和控制音乐。
2. `小智小智 -> 打开微信` 必须走与本地测试相同的 PhoneController 路径并成功打开微信。
3. `打开QQ / 播放音乐 / 停止音乐` 不需要 AI 接口即可执行。
4. 授予悬浮窗权限后，唤醒时桌面/其他 App 上显示透明语音悬浮层。
5. 悬浮层显示监听、识别、执行状态和识别到的文字。
6. 打开微信后悬浮层仍可保持连续会话。
7. 下一条语音指令不需要再次喊“小智小智”。
8. 说“再见”后悬浮层消失并返回 KWS 待机。
9. 没有悬浮窗权限时语音功能仍正常，不崩溃。
10. GitHub Actions 成功生成 arm64 APK，并保留现有 KWS + Paraformer 离线模型。
