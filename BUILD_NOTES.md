# XiaoZhi Mobile v0.6.3 build notes

New in v0.6.3:
- Fix custom Chinese wake phrases such as `小白小白` when pinyin4j emits non-standard third-tone Unicode (e.g. `ă` instead of `ǎ`).
- Normalize pinyin tone marks before KWS compilation.
- Generate sherpa-onnx compatible ppinyin tokens using initial + tone-marked final, matching the official text2token strategy for Chinese keywords.
- Validate dynamic KWS stream creation and keep the previous active wake stream on failure.
- Show the concrete custom-wake failure reason in the foreground notification for device diagnostics.
- Retain v0.6.2 spoken command confirmations and fast relisten after TTS completion.

Build target:
- Android arm64-v8a
- versionCode 10
- versionName 0.6.3
- APK output: `XiaoZhi-Mobile-v0.6.3-debug.apk`
