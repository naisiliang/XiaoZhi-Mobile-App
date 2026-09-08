# Phase 6 Task 8: Whole-Branch Review and Final Handoff

## Scope and isolation

- Recovery branch: `recovery/v0.7.0-golden-first-full`.
- Recovery worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`.
- Golden non-regression reference: `324dd5a53d404490bc4a32ed1f9ce8c45671ed24`.
- Reviewed implementation HEAD: `660b3086db2eda4d6c457f1a7795b5fd4052eb73`.
- Exact reviewed/build ref after the test-harness fix: `7b15012ff1179f42f68755b9c6bfa31e96a524ef`.
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
- The first remote exact-ref run (`34212035812`) exposed a pre-existing CI
  harness omission: the FIX04 PhoneController Kotlin harnesses did not compile
  `MusicIntent.kt` and `MediaSessionFallback.kt`. The minimal test-only fix was
  committed as `7b15012` and passed the complete local FIX04 gate.
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
- The exact-ref GitHub Actions run `34213273783` (run number `22`) completed
  successfully with `reviewed_ref=7b15012ff1179f42f68755b9c6bfa31e96a524ef`.
  Its artifacts were: RC APK `10051008894`, APK integrity `10051019990`,
  APK verification `10051026242`, RC verification `10051026657`, and the
  Alpha1 APK `10051013417`. The downloaded RC artifact contained the exact
  APK below; the CI verification report and the local validator agree on its
  size and SHA-256.
- Downloaded GitHub RC APK:
  `C:\Users\ASUS\Downloads\xiaozhi-v070-task8c-evidence-20260908\github-rc-artifact-10051008894\XiaoZhi-Mobile-v0.7.0-rc-debug.apk`
  is `101831818` bytes with SHA-256
  `53a15e70191585c003097dc96b7702a897bceab4d28f748c18894b8eb4c72420`.

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

- Normal push completed for `recovery/v0.7.0-golden-first-full`, and the exact
  40-character workflow dispatch and artifact round-trip completed successfully
  for reviewed ref `7b15012ff1179f42f68755b9c6bfa31e96a524ef`.
- The post-CI report/progress update is documentation-only; the reviewed
  product source remains exactly the successful `7b15012` ref. No merge to
  `main` is authorized.
