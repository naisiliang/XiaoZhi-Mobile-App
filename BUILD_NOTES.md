# XiaoZhi Mobile v0.3.0 build notes

v0.3.0 removes Android `SpeechRecognizer` from the wake-to-command path because some Android ROMs return `ERROR_CLIENT` from a foreground microphone service.

The new local path is:

`KWS (sherpa-onnx) -> local microphone capture -> Paraformer local ASR (sherpa-onnx) -> CommandRouter -> Android control`

The optional OpenAI-compatible API is used only for non-local conversational queries.
