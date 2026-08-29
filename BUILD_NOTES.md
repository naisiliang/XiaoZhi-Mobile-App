# XiaoZhi Mobile v0.6.0 build notes

New in v0.6.0:
- complete installed-app discovery and structured launch diagnostics;
- direct-install `QUERY_ALL_PACKAGES` visibility for broader launcher coverage;
- one-shot foreground location only, Amap/Baidu/system navigation and nearby search;
- runtime custom assistant name and sherpa-onnx wake phrase compilation via pinyin4j;
- Android system TTS voice selection, preview, speech rate and pitch;
- AI Base URL normalization, AUTO / Chat Completions / Responses modes and endpoint test;
- session-scoped 8-turn AI conversation memory;
- native tool calling plus strict JSON fallback planning;
- strict `SafeToolExecutor` allowlist before any AI-planned phone action;
- local-first routing: deterministic device commands stay AI-free, ambiguous commands may use AI planning;
- settings/diagnostics UI for app visibility, KWS phrase, location, TTS and AI endpoint status.

Preserved from v0.5.0:
- configurable session timeout (default 20 seconds), wake reply and timeout exit phrase;
- immediate re-listen after local device commands;
- transparent overlay HUD;
- default blue/pink launcher logo and custom desktop shortcut icon;
- sherpa-onnx KWS + local Paraformer ASR.

Security constraints:
- no AccessibilityService;
- no background location;
- AI cannot delete data, send messages, pay/transfer, install/uninstall, read private data, run shell, or submit arbitrary Android intents/URIs/packages;
- API keys are local settings only and must never be committed/logged.

Build target: arm64-v8a, Android minSdk 26 / targetSdk 35, Java 17.
