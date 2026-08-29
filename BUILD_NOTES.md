# XiaoZhi Mobile v0.6.4 build notes

New in v0.6.4:
- Preserve/freeze the working v0.6.3 custom wake phrase stack.
- Add explicit post-wake conversation states and truthful overlay status.
- Speak command-completion + continuation prompts, then reopen ASR only after TTS completion and a 120–180 ms guard.
- Add panel close button and double-tap exit while keeping outside-panel touch-through.
- Add local intelligent exit detection plus no-tools AI semantic exit fallback.
- Add natural Chinese media-volume parsing and verified Android `STREAM_MUSIC` read-back with `FLAG_SHOW_UI`.
- Add short duplicate-device-command suppression.

Build target:
- Android arm64-v8a
- versionCode 11
- versionName 0.6.4
- APK output: `XiaoZhi-Mobile-v0.6.4-debug.apk`
