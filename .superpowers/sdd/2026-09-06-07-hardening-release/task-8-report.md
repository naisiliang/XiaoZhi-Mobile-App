# Phase 6 Task 8: Whole-Branch Review and Final Handoff

## Scope and isolation

- Recovery branch: `recovery/v0.7.0-golden-first-full`.
- Recovery worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`.
- Golden non-regression reference: `324dd5a53d404490bc4a32ed1f9ce8c45671ed24`.
- Reviewed implementation HEAD: `660b3086db2eda4d6c457f1a7795b5fd4052eb73`.
- `main` and `feature/v0.7.0-smart-agent` were not checked out for editing and were
  not reset, force-checked-out, deleted, or force-pushed.
- The Level-1 frozen KWS files have no diff from the Golden reference:
  `WakePhraseCompiler.kt`, `WakePhraseManager.kt`, and `Pinyin4jProvider.kt`.

## TDD and review

- RED: the Agent artifact-budget contract exposed that executor accounting could
  rely on caller-declared artifact size and that missing measurement was not
  fail-closed.
- GREEN: `c2ceaf8` makes the executor receive a sanitized invocation, requires
  measured artifact bytes, and rejects missing, negative, throwing, or
  over-budget measurements.
- RED: lifecycle and accessibility hardening contracts failed on worker teardown
  ownership/restart races and on implementation class names copied into semantic
  accessibility roles.
- GREEN: `660b308` adds per-`AudioRecord` worker ownership, generation-checked
  restart scheduling, non-blocking worker interruption with deferred native
  cleanup, stop-only capture teardown, and fail-closed accessibility role
  filtering while preserving distinct semantic roles.
- Focused contracts and unit tests passed after the GREEN changes:
  `test_v070_task8_lifecycle_hardening_contract.py`,
  `test_v070_task8_accessibility_hardening_contract.py`,
  `test_v070_task8_agent_budget_contract.py`,
  `test_v070_agent_integration_contract.py`,
  `test_v070_task3_typed_source_contract.py`, and
  `test_v070_task2_runtime_contract.py`.
- Fresh strongest review of HEAD `660b308` reported no unresolved Critical or
  Important issue in `WakeService.kt` or `SensitiveScreenDetector.kt`. The
  preceding Agent safety review likewise reported no unresolved Critical or
  Important issue.

## Verification

- `python tools/test_v070_final_release_gate.py` passed every ordered stage at
  the reviewed HEAD: `frozen`, `historical`, `p0`, `screen`, `messaging`,
  `music-video`, `extensions`, `artifacts-image`, `security`, and `android`.
  The run used JDK 17, Android SDK 35, Gradle 8.9, and Kotlin CLI 2.0.21 with
  the direct `KOTLINC` batch entrypoint.
- `gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease
  --no-daemon --stacktrace` passed after `660b308`.
- `git diff --check` passed.
- Debug RC evidence:
  `C:\Users\ASUS\Downloads\xiaozhi-v070-task8c-evidence-20260908\XiaoZhi-Mobile-v0.7.0-rc-debug.apk`
  is `102406457` bytes with SHA-256
  `0408370a04725e6f9f665f2510d3f5a605f57a726333259157568719c38bbfde`.
  `tools/validate_v070_apk.py` passed package, version, ZIP integrity,
  Accessibility declaration, `classes.dex`, arm64 Sherpa JNI, and all 9
  required KWS/Paraformer model entries.

## Android runtime evidence

- AVD `xiaozhi_v070_api35`, serial `emulator-5554`, API 35, 1080x2220,
  density 440, was online and accepted the exact debug APK installation.
- The main chat UI, menu, and SettingsActivity were opened. The settings XML
  exposes the wake phrase, background wake switch, start, stop, and apply
  controls. Evidence includes `menu-latest.png`, `settings-runtime-final.png`,
  `settings-after-stop.xml`, and `settings-after-apply-final.xml` in the Task
  8c evidence directory.
- Settings control smoke passed: stop showed `唤醒服务已停止`; start/apply
  returned to `等待唤醒`. `dumpsys activity services` showed
  `WakeService`, `startForegroundCount=1`, `isForeground=true`, foreground id
  `1001`, type `0x00000080`, and channel `xiaozhi_wake`.
- Runtime logs showed Sherpa JNI/model initialization and no app
  `FATAL EXCEPTION` or `AndroidRuntime` crash. Focus remained
  `com.lchuang.xiaozhimobile/.SettingsActivity` during settings validation.
- Human-spoken custom wake, real voice dialogue, and TTS/ASR arbitration were
  not claimed: this emulator run has no reliable human speech input path.
  The mandatory device gate therefore remains exactly:
  `DEVICE_GATE_PENDING`.

## Remote handoff

- The normal push and exact-ref GitHub workflow dispatch are the remaining
  handoff actions for this report. They must target this recovery branch and
  an exact 40-character reviewed ref; no merge to `main` is authorized.
