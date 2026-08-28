# XiaoZhi Mobile Android v0.2 Design

## Goal
Deliver an installable Android APK that can continuously detect the wake phrase “小智小智” on-device, accept a spoken command, execute common phone controls locally, and fall back to an OpenAI-compatible chat endpoint for general conversation.

## Architecture
- `WakeService`: microphone foreground service, KWS lifecycle, wake-to-command orchestration, TTS feedback.
- sherpa-onnx KWS: fully local wake phrase detection using the zh/en 3M model.
- Android `SpeechRecognizer`: one-utterance ASR after wake; prefers on-device recognition when supported.
- `CommandRouter`: deterministic local command matching before any AI request.
- `PhoneController`: media keys, media volume, flashlight, app launching, browser and geo intents.
- `AiClient`: non-streaming OpenAI-compatible `/v1/chat/completions` fallback.

## v0.2 Acceptance Criteria
- Build target Android API 35, min API 26, arm64-v8a.
- APK contains the downloaded sherpa-onnx KWS model.
- Foreground microphone service can be started explicitly from the visible app.
- Wake phrase is `小智小智` and KWS audio is not sent to the AI endpoint.
- Local controls include play/pause/next/previous, volume, flashlight, common app launch and navigation.
- General chat works when an OpenAI-compatible endpoint is configured.
- GitHub Actions produces `XiaoZhi-Mobile-v0.2.0-debug.apk` as an artifact.

## Known Platform Limits
- Android background activity-launch restrictions can prevent some app-opening commands on some OEM/Android versions while the assistant UI is not visible.
- Android system SpeechRecognizer offline availability varies by device and installed language packs.
- Model redistribution licensing must be checked before public/commercial distribution; private build/testing is separated from that decision.
