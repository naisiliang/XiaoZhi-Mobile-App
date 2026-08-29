# XiaoZhi Mobile v0.5.0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade v0.4.0 with configurable session behavior, intelligent installed-app launch, failure fallback, and customizable desktop shortcut icon.

**Architecture:** Keep KWS/Paraformer/overlay/PhoneController from v0.4.0. Add pure session deadline logic, installed-app discovery/resolution, and a desktop shortcut icon manager. Route every voice/device action through the same CommandRouter -> PhoneController path.

**Tech Stack:** Kotlin, Android SDK 35, sherpa-onnx, Android ShortcutManager, PackageManager, SharedPreferences.

**Spec:** `docs/superpowers/specs/2026-08-29-xiaozhi-mobile-v0.5.0-design.md`

## Global Constraints

- Default session timeout: 20 seconds.
- Default wake reply: `我在`.
- Default timeout reply: `我先退下了，有问题再唤醒我`.
- Unknown-command fallback: `抱歉，我还不会这个指令，你可以换一个指令继续服务你`.
- Do not add AccessibilityService.
- KWS and command ASR remain fully local.
- APK target remains arm64-v8a.

---

### Task 1: Session settings and deadline controller

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/SessionController.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt`
- Test: `tools/test_v050_session.py`

**Interfaces:**
- Produces: `SessionController.start(timeoutSeconds)`, `touch(timeoutSeconds)`, `remainingMs()`, `isExpired()`, `stop()`.
- Produces SettingsStore properties: `wakeReply`, `timeoutReply`, `sessionTimeoutSeconds`, `appAliases`.

- [ ] Write executable Kotlin/Python regression tests for defaults and deadline behavior.
- [ ] Run tests and verify failure.
- [ ] Implement settings and controller.
- [ ] Run tests and verify pass.

### Task 2: Installed app registry and intelligent launch

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/InstalledAppRegistry.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/CommandRouter.kt`
- Test: `tools/test_v050_app_registry.py`

**Interfaces:**
- Produces: `InstalledAppRegistry.resolve(name, aliases): AppEntry?`, `discover(): List<AppEntry>`.
- `PhoneController.openApp()` uses registry first and known packages as fallback.
- `CommandRouter.looksLikeDeviceCommand(text)` identifies unsupported device commands.

- [ ] Write failing source/executable normalization tests.
- [ ] Implement alias parser, label normalizer and fuzzy score helper.
- [ ] Integrate registry into PhoneController.
- [ ] Add device-command classification.
- [ ] Verify tests.

### Task 3: Continuous-session timeout and immediate re-listen

**Files:**
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt`
- Test: `tools/test_v050_voice_flow.py`

**Interfaces:**
- WakeService reads settings dynamically.
- Local command success calls immediate listening without TTS.
- Silence loops until SessionController expiry.
- Timeout calls configured timeout speech then ends session.

- [ ] Write failing source-flow assertions.
- [ ] Replace immediate NO_SPEECH exit with deadline-aware silent re-listen.
- [ ] Use configurable wake and timeout phrases.
- [ ] Add exact unknown-command fallback behavior.
- [ ] Verify tests.

### Task 4: Default logo and custom desktop shortcut icon

**Files:**
- Create: `app/src/main/java/com/lchuang/xiaozhimobile/DesktopIconManager.kt`
- Modify: `app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/mipmap-*/ic_launcher.png`
- Create: `app/src/main/res/mipmap-*/ic_launcher_round.png`
- Test: `tools/test_v050_icon.py`

**Interfaces:**
- `DesktopIconManager.applyCustomIcon(uri): Result<String>`.
- `DesktopIconManager.restoreDefault(): Result<String>`.
- MainActivity launches `ACTION_OPEN_DOCUMENT` and refreshes preview.

- [ ] Add failing source/resource tests.
- [ ] Generate default density icons from approved logo.
- [ ] Implement shortcut manager.
- [ ] Add settings UI and image picker.
- [ ] Verify tests.

### Task 5: Version, CI, and full verification

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `.github/workflows/build-apk.yml`
- Modify: `tools/validate_project.py`
- Modify: `README.md`, `BUILD_NOTES.md`

- [ ] Set versionName 0.5.0 and output name `XiaoZhi-Mobile-v0.5.0-debug.apk`.
- [ ] Run all Python/Kotlin regression tests.
- [ ] Confirm git worktree clean after commits.
- [ ] Package GitHub-ready FIX8 ZIP.
