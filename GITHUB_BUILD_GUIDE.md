# 上传到 GitHub 并生成 APK

目标仓库：`https://github.com/naisiliang/XiaoZhi-Mobile-App.git`

## 最简单方法（Windows）

1. 解压本项目到普通英文或中文目录均可。
2. Windows 安装 Git for Windows。
3. 双击 `PUSH_TO_GITHUB.bat`。
4. 如果 GitHub 弹出浏览器登录，登录并授权。
5. 上传完成后打开仓库的 **Actions** 页面。
6. 进入最新的 `Build XiaoZhi Mobile APK`。
7. 等工作流显示绿色 `✓`。
8. 页面底部 **Artifacts** 下载 `XiaoZhi-Mobile-APK`。
9. 解压后得到 `XiaoZhi-Mobile-v0.3.1-debug.apk`，发送到安卓手机安装。

## APK 首次安装后

1. 允许麦克风权限。
2. Android 13+ 允许通知权限。
3. 点击 `开启后台离线唤醒`。
4. 保留通知栏“小智手机助手”前台服务。
5. 说：`小智小智`。
6. 听到“我在”后说：`播放音乐`、`下一首`、`打开微信`、`打开手电筒` 等。

## 当前 v0.3.1 行为

- “小智小智”由 sherpa-onnx KWS 在本机检测。
- 唤醒后的整句语音识别优先使用 Android 设备侧 SpeechRecognizer；设备不支持时可能使用系统在线识别。
- 播放/暂停/上下首、音量、手电筒、打开 App、导航属于本地控制，不需要 AI Token。
- 普通聊天需要在 App 中配置 OpenAI-compatible `/v1/chat/completions` API。

## 如果 Actions 失败

把 GitHub Actions 红色失败页面里的完整日志复制给 ChatGPT，我们继续修，直到工作流成功生成 APK。

## GitHub connection timeout on Windows

If `PUSH_TO_GITHUB.bat` reports `Failed to connect to github.com port 443`, the Android project is not the cause. Git for Windows cannot reach GitHub on the current network path.

The FIX2 uploader first tries direct access and then automatically probes Windows proxy settings plus common local proxy ports, including 7890, 7897, 10809, 10808, 1080, 7891, and 7893.

If automatic detection still fails:

1. Start your proxy application and enable **System Proxy** or **TUN mode**.
2. Find its **HTTP/Mixed Port**.
3. Double-click `PUSH_TO_GITHUB_MANUAL_PROXY.bat`.
4. Enter the port, for example `7890` or `7897`.

The uploader applies the proxy only to its own `git ls-remote`, `git clone`, and `git push` commands. It does not change global Git proxy settings.
