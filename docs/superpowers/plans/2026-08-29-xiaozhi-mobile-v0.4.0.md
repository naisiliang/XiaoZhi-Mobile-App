# XiaoZhi Mobile v0.4.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make spoken commands route reliably to the same Android phone-control layer as typed commands, and show a transparent system overlay HUD during the wake/listen/recognize/execute/continuous-conversation lifecycle.

**Architecture:** Keep `WakeService` as the owner of KWS, offline Paraformer ASR, continuous-session state, TTS, and notifications. Add a pure `VoiceCommandNormalizer` between ASR output and `CommandRouter`, plus an `AssistantOverlayController`/`AssistantOverlayView` pair managed by `WakeService`; the overlay is optional and gracefully disabled when `SYSTEM_ALERT_WINDOW` is not granted.

**Tech Stack:** Kotlin, Android SDK 35, `WindowManager.TYPE_APPLICATION_OVERLAY`, Canvas custom View, sherpa-onnx v1.13.4, Python source regression tests, Kotlin/JVM normalizer smoke test, GitHub Actions Gradle build.

**Spec:** `docs/superpowers/specs/2026-08-29-xiaozhi-mobile-v0.4.0-overlay-design.md`

## Global Constraints

- Android minSdk remains 26 and targetSdk/compileSdk remain 35.
- APK remains arm64-v8a for this iteration.
- KWS and command ASR remain fully local via sherpa-onnx.
- Spoken and typed phone-control commands must converge on the same `CommandRouter -> PhoneController` path.
- Overlay permission is optional; denial must not break voice operation.
- Do not add AccessibilityService, screen capture, or WebView.
- Continuous conversation remains active until exit phrase or silence timeout.

---

### Task 1: Voice Command Normalization

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/VoiceCommandNormalizer.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt`
- Test: `tools/test_voice_command_normalizer.py`

**Interfaces:**
- Produces: `object VoiceCommandNormalizer { fun normalize(raw: String): String }`
- `WakeService.processUtterance(rawText)` will call `normalize(rawText)` before routing.

- [ ] **Step 1: Write a failing executable Kotlin/JVM test harness from Python** covering `请 打开 微 信。 -> 打开微信`, `打开 q q -> 打开qq`, `打开扣扣 -> 打开qq`, `来首歌 -> 播放音乐`, `把音乐停掉 -> 停止音乐`.
- [ ] **Step 2: Run the test and verify it fails because `VoiceCommandNormalizer.kt` does not exist.**
- [ ] **Step 3: Implement minimal deterministic normalization** for whitespace/punctuation, common ASR variants, and requested natural-language aliases.
- [ ] **Step 4: Expand `CommandRouter` synonyms only where the normalized canonical phrases still need coverage.**
- [ ] **Step 5: Run the normalizer test and existing v0.3.1 behavior tests.**

### Task 2: Transparent Overlay HUD and Permission Flow

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayView.kt`
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/AssistantOverlayController.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt`
- Test: `tools/test_overlay_source.py`

**Interfaces:**
- Produces: `AssistantOverlayController(context)` with `canDraw(): Boolean`, `show()`, `hide()`, `update(title: String, status: String, heard: String = "")`, `updateAudioLevel(level: Float)`, `release()`.
- `AssistantOverlayView.setContent(...)` and `setAudioLevel(...)` own visual state only.

- [ ] **Step 1: Write failing source tests** for `SYSTEM_ALERT_WINDOW`, `TYPE_APPLICATION_OVERLAY`, `Settings.canDrawOverlays`, overlay permission UI, non-focusable/non-touchable flags, waveform custom View, and graceful permission checks.
- [ ] **Step 2: Run tests and verify failure.**
- [ ] **Step 3: Add manifest permission and MainActivity permission button** using `ACTION_MANAGE_OVERLAY_PERMISSION` and `package:` URI.
- [ ] **Step 4: Implement `AssistantOverlayView`** as a transparent full-screen custom Canvas view with pulsing blue-purple ring, title, status, recognized text, and animated waveform.
- [ ] **Step 5: Implement `AssistantOverlayController`** using `WindowManager.TYPE_APPLICATION_OVERLAY` and no-focus/no-touch flags; catch permission/window exceptions and continue safely.
- [ ] **Step 6: Run overlay source tests.**

### Task 3: Integrate Overlay and Normalized Routing into WakeService

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt`
- Test: `tools/test_v040_voice_flow.py`

**Interfaces:**
- Consumes: `VoiceCommandNormalizer.normalize(raw)` and `AssistantOverlayController`.
- Preserves: `CommandRouter.handle(normalized)` and `PhoneController` as the sole local execution path.

- [ ] **Step 1: Write failing flow tests** asserting normalization happens before `router.handle`, raw recognized text is surfaced, overlay shows on wake, listening/recognizing/executing states are updated, RMS drives waveform, and overlay hides on session end/destroy.
- [ ] **Step 2: Run tests and verify failure.**
- [ ] **Step 3: Instantiate overlay controller in `onCreate` and release in `onDestroy`.**
- [ ] **Step 4: Show overlay on wake and synchronize `我在听 / 正在识别 / 我听到 / 正在执行 / 正在思考` states.**
- [ ] **Step 5: Feed command-capture RMS values into overlay waveform.**
- [ ] **Step 6: Route `raw -> normalized -> CommandRouter` and use normalized text for exit detection/local execution while preserving raw text for diagnostics.**
- [ ] **Step 7: Keep overlay visible across successful local/AI turns and hide it only when the conversation session ends or service stops.**
- [ ] **Step 8: Run flow tests plus all prior tests.**

### Task 4: Version, Build, Validation, and Distribution Package

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/build-apk.yml`
- Modify: `tools/validate_project.py`
- Modify: `README.md`
- Modify: `BUILD_NOTES.md`
- Create/Update: `docs/superpowers/specs/2026-08-29-xiaozhi-mobile-v0.4.0-overlay-design.md`

**Interfaces:**
- Produces artifact name: `XiaoZhi-Mobile-v0.4.0-debug.apk`.

- [ ] **Step 1: Copy the approved v0.4.0 design spec into the repository docs path.**
- [ ] **Step 2: Bump `versionCode` to 5 and `versionName` to `0.4.0`.**
- [ ] **Step 3: Update GitHub Actions rename/upload path to `XiaoZhi-Mobile-v0.4.0-debug.apk`.**
- [ ] **Step 4: Extend project validation to check overlay permission/controller/view, normalizer, unified routing, v0.4.0 version, and artifact name.**
- [ ] **Step 5: Run every source/test script and the Kotlin/JVM normalizer test.**
- [ ] **Step 6: Review git diff for unrelated changes and package a FIX7 ZIP for user push.**
