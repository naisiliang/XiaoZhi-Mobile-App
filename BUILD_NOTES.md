# XiaoZhi Mobile v0.4.0 build notes

v0.4.0 builds on the fully local KWS + Paraformer ASR path from v0.3.x.

New in v0.4.0:
- deterministic `VoiceCommandNormalizer` before `CommandRouter`;
- system transparent overlay HUD via `TYPE_APPLICATION_OVERLAY`;
- overlay permission is optional and voice control continues without it;
- overlay shows wake/listen/recognize/execute/think states and recognized text;
- microphone RMS drives the overlay waveform;
- spoken phone commands and typed local-test commands converge on the same `CommandRouter -> PhoneController` execution path.

Build target: arm64-v8a, Android minSdk 26 / targetSdk 35.
