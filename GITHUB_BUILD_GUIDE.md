# GitHub Actions 构建 v0.6.0 APK

1. 解压本工程。
2. 双击 `PUSH_TO_GITHUB_MANUAL_PROXY.bat`；如果需要代理，输入已验证的 HTTP/Mixed Port（此前验证可用的是 7897）。
3. 手动代理模式必须先看到 `Manual proxy override is active`，再继续 clone/push。
4. 推送成功后打开仓库 Actions。
5. 进入最新 `Build XiaoZhi Mobile APK`。
6. 等待 `Validate source tree`、`Fetch offline wake + ASR models`、`Build debug APK`、`Rename APK`、`Upload artifact` 全部变绿。
7. 下载 `XiaoZhi-Mobile-APK` Artifact。
8. 解压得到 `XiaoZhi-Mobile-v0.6.0-debug.apk`。

## 手机上首次设置

- 安装 APK；
- 允许麦克风/通知/相机；
- 授权“显示在其他应用上层”；
- 如果要用“附近”搜索，再单独允许前台位置；
- 设置助手名字/唤醒短语并点击“保存并应用唤醒词”；
- 选择并试听 TTS 声音；
- 填 Base URL / API Key / 模型 / API 模式，然后点“测试 AI 接口”；
- 开启后台离线唤醒。

说明：v0.6.0 直接安装版使用 `QUERY_ALL_PACKAGES` 来改善“打开任意已安装 App”的覆盖率；未来 Google Play 版本需单独做权限合规审查。
