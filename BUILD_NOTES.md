# Build verification notes

The source tree and build workflow are complete. This ChatGPT execution container does not contain Android SDK / Gradle / Android build-tools and its shell network cannot resolve external artifact hosts, so an APK cannot be truthfully claimed as locally compiled inside this container.

The included GitHub Actions workflow is the reproducible build path. It downloads the official KWS model and builds a signed debug APK.

## v0.2.1 wake-command fix
- Adds a 700 ms microphone handoff delay after TTS "我在" completes.
- Keeps the same wake session alive for one automatic command-recognition retry.
- Tunes recognizer silence windows for short commands such as "播放音乐".
- Shows the Android SpeechRecognizer error class in the notification for device-specific debugging.
