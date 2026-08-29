# GitHub Actions 构建 v0.6.3 APK

1. 将本包内容推送到 `naisiliang/XiaoZhi-Mobile-App` 的 `main` 分支。
2. GitHub Actions 会自动运行 `Build XiaoZhi Mobile APK`。
3. 工作流会安装 Android SDK 35、下载离线 KWS + Paraformer ASR 模型并运行源码校验。
4. 真正执行 `:app:assembleDebug`。
5. 成功后下载 Artifact：`XiaoZhi-Mobile-APK`。
6. 解压得到 `XiaoZhi-Mobile-v0.6.3-debug.apk`。

本版重点验收：自定义 `小白小白` 唤醒词、成功指令播报确认、播报结束后快速恢复监听。
