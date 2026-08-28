# GitHub Actions 构建 v0.4.0 APK

1. 解压 FIX7 源码包。
2. 双击 `PUSH_TO_GITHUB_MANUAL_PROXY.bat`。
3. 你当前已验证可用的本地 HTTP/Mixed 代理端口是 `7897`，输入后回车。
4. 推送完成后打开仓库 `Actions`。
5. 等待 `Build XiaoZhi Mobile APK` 变绿。
6. 在 Artifacts 下载 `XiaoZhi-Mobile-APK`。
7. 解压得到 `XiaoZhi-Mobile-v0.4.0-debug.apk`。

## 安装后的首次设置

1. 允许麦克风、通知、相机权限。
2. 点击“授权桌面透明语音悬浮层”，在 Android 系统页面允许“小智手机助手”显示在其他应用上层。
3. 返回 App，按钮应显示“桌面透明语音悬浮层：已授权”。
4. 点击“开启后台离线唤醒”。
5. 通知栏显示全离线语音已开启后，说“小智小智”。

悬浮窗权限不授权也能使用语音控制，只是不显示桌面透明语音 HUD。
