# XiaoZhi Mobile v0.6.2 build notes

New in v0.6.2:
- Fix the real-device mismatch where the text fields could contain “小白 / 小白小白” while the running KWS service still listened for “小智小智”.
- Starting offline wake now always synchronizes the saved wake phrase with an already-running WakeService.
- “Save all settings” also synchronizes the wake phrase when the service is running.
- The UI now shows the actual runtime KWS wake phrase (`activeWakePhrase`) instead of only echoing the edit field.
- Successful local phone commands now speak a short completion confirmation, then resume listening 120 ms after TTS finishes.
- Successful AI safe-tool actions use the same completion-confirmation flow.
- App launch confirmations now say “已打开…”; media/volume/map confirmations use completed-action wording.

Preserved:
- v0.6.1 stable bundled `createStream()` startup for the default “小智小智”; custom phrases still use sherpa-onnx runtime KWS.
- installed-app discovery and launch diagnostics;
- Amap/Baidu/system navigation and one-shot foreground location;
- TTS voice selection/preview/rate/pitch;
- AI Base URL auto mode, 8-turn session memory, safe tool allowlist;
- 20-second configurable continuous session and transparent overlay.

Security constraints remain unchanged: no AccessibilityService, no background location, and no AI payment/delete/message/install/shell capabilities.

Build target: arm64-v8a, Android minSdk 26 / targetSdk 35, Java 17.
