# XiaoZhi Mobile v0.5.0 build notes

New in v0.5.0:
- configurable wake reply, session timeout (default 20 seconds), and timeout-exit reply;
- `SessionController` deadline-driven continuous conversation;
- local commands immediately resume listening without spoken acknowledgement delay;
- exact unsupported-command fallback while keeping the session active;
- `InstalledAppRegistry` + `AppNameMatcher` for launcher-app discovery, fuzzy matching, and user aliases;
- default APK icon from the approved blue/pink logo;
- user-selected image -> pinned/dynamic custom desktop shortcut icon, with restore-default action.

Existing v0.4.0 behavior is preserved:
- sherpa-onnx KWS + local Paraformer ASR;
- transparent `TYPE_APPLICATION_OVERLAY` voice HUD;
- local `VoiceCommandNormalizer -> CommandRouter -> PhoneController` execution path.

Build target: arm64-v8a, Android minSdk 26 / targetSdk 35.
