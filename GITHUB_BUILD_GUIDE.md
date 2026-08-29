# GitHub Actions 构建 v0.6.4 APK

1. 将 FIX14 包内容推送到 `naisiliang/XiaoZhi-Mobile-App` 的 `main` 分支。
2. GitHub Actions 自动运行 `Build XiaoZhi Mobile APK`。
3. 工作流安装 Android SDK 35、下载离线 KWS + Paraformer ASR 模型并运行源码校验。
4. 真正执行 `:app:assembleDebug`。
5. 成功后下载 Artifact：`XiaoZhi-Mobile-APK`。
6. 解压得到 `XiaoZhi-Mobile-v0.6.4-debug.apk`。

## 实机验收顺序

1. **先回归自定义唤醒**：`小白小白` 必须保持正常。
2. `打开微信`：实际打开后应播报完成结果与继续提示，播报结束后不重新唤醒即可继续下一条指令。
3. 点悬浮面板 `×` 退出；再次唤醒后双击面板退出；面板外必须能正常点击手机界面。
4. 语音 `退出 / 退下 / 没什么事了 / 先这样吧` 应结束助手会话；`退出微信 / 退出登录` 不应退出助手。
5. 测试 `把手机音量调到最大 / 百分之七十 / 一半 / 静音 / 大一点 / 小一点`，只允许媒体音量变化，播报应基于 Android 实际回读值。
