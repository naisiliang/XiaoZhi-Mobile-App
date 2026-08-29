# GitHub Actions 构建 v0.6.2 APK

1. 解压本工程。
2. 双击 `PUSH_TO_GITHUB_MANUAL_PROXY.bat`；需要代理时输入已验证的 HTTP/Mixed Port（此前可用 7897）。
3. 确认出现 `Manual proxy override is active`。
4. 推送成功后打开仓库 Actions。
5. 进入最新 `Build XiaoZhi Mobile APK`。
6. 等待 Validate / model fetch / Build debug APK / Rename / Upload artifact 全部变绿。
7. 下载 `XiaoZhi-Mobile-APK` Artifact。
8. 解压得到 `XiaoZhi-Mobile-v0.6.2-debug.apk`。

手机验收重点：
- 运行中把“小智小智”改为“小白小白”后，点击“保存并应用唤醒词”、再次点“开启后台离线唤醒”或“保存全部设置”都应自动同步。
- 页面“当前实际 KWS 唤醒短语”应显示真正正在监听的词。
- 执行“打开微信/高德/音量调大/下一首”等成功指令后，应先语音确认完成，再快速进入下一轮监听。
