# XiaoZhi Mobile App v0.3.0

Android voice assistant prototype with fully local wake word and local command speech recognition.

## Core path

- Offline wake word: `小智小智` via sherpa-onnx KWS
- Offline command ASR: `sherpa-onnx-paraformer-zh-small-2024-03-09`
- Local commands: media play/pause/next/previous, volume, flashlight, app launch, navigation
- Optional AI chat: OpenAI-compatible `/v1/chat/completions`
- Android TTS for voice replies

## Why v0.3.0

v0.2.x used Android `SpeechRecognizer` after wake-up. Some phones returned `ERROR_CLIENT`. v0.3.0 no longer uses Android `SpeechRecognizer` in the wake-to-command path.

## Build

Push to GitHub. The included workflow downloads the KWS and local ASR models, then builds `XiaoZhi-Mobile-v0.3.0-debug.apk`.
