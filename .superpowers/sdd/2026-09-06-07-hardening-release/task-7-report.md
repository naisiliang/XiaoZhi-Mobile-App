# Phase 6 Task 7: Exact GitHub RC Release Gate

## TDD and implementation

- RED: the new release-gate contract failed because the exact v0.7.0 gate and
  RC APK validator did not exist.
- GREEN: added `tools/test_v070_final_release_gate.py` with explicit ordered
  stages for Frozen Source Guard, historical behavior, P0, Screen Intelligence,
  Messaging, Music/Video, Extensions, Artifacts/Image, Security, and Android
  project validation. Each stage checks that its test exists and executes it
  through the current Python interpreter.
- RED: the local ASR source contract exposed that the main composer had no
  source-level indication of the offline Sherpa command-recognition path.
- GREEN: added a non-visual `contentDescription` to the existing composer;
  no layout or interaction behavior changed.
- RED: the first historical run reached the old v0.6.2 command-confirmation
  contract, which requires the superseded `safeToolExecutor.plan` path.
  This was an incompatible historical test selection, not a product failure.
- GREEN: aligned the final historical set with the recovery Phase 0/Golden
  compatible tests and retained the v0.6.5 release gate plus v0.6.3/v0.6.4
  wake, exit, volume, and command-flow regressions.
- RED: the existing v0.6.5 workflow contract rejected the new workflow because
  its capture-watchdog commands no longer appeared before the v0.6.5 release
  gate.
- GREEN: retained the legacy workflow compatibility commands while adding the
  dispatch-only RC path.

## Release implementation

- `.github/workflows/build-apk.yml` now requires an exact 40-character
  `reviewed_ref`, checks that `git rev-parse HEAD` equals it, installs Android
  API 35/build-tools, Gradle 8.9, and Kotlin CLI 2.0.21, fetches the official
  offline KWS and Paraformer assets, runs the ordered final gate, runs unit
  tests, and assembles the Debug APK.
- The dispatch path copies exactly
  `XiaoZhi-Mobile-v0.7.0-rc-debug.apk`, validates it before upload, uploads and
  downloads the same named artifact, compares bytes with `cmp`, and validates
  the downloaded artifact again with the recorded size and SHA-256.
- `tools/validate_v070_apk.py` validates ZIP integrity and duplicate entries,
  `AndroidManifest.xml` through `aapt2` when binary, package/version,
  AccessibilityService declaration, `classes.dex`, arm64 Sherpa JNI, and all
  KWS/Paraformer model assets. It also supports exact artifact-directory and
  artifact-ZIP validation.

## Verification

- Contract and static checks passed:
  `test_v070_final_release_gate_contract.py`,
  `test_v070_apk_validator_contract.py`,
  `test_v070_alpha1_session_wiring.py`, `test_local_asr_source.py`,
  `test_v065_validator_contract.py`, both workflow YAML parsing and Golden
  frozen-source checks.
- The following final-gate stages each passed locally with the configured
  JDK 17/Kotlin 2.0.21/Android SDK toolchain: `frozen`, `historical`, `p0`,
  `screen`, `messaging`, `music-video`, `extensions`, `artifacts-image`,
  `security`, and `android`.
- `gradle :app:testDebugUnitTest --no-daemon --stacktrace` passed.
- `gradle :app:assembleDebug --no-daemon --stacktrace` passed.
- Exact RC evidence directory:
  `C:\Users\ASUS\Downloads\xiaozhi-v070-task7-evidence-20260908`.
  The APK is 102400775 bytes with SHA-256
  `0a85477021f133a8e0404798a8575d29e608423b0d7273258a38755bffdaf7b6`.
  The validator reported package `com.lchuang.xiaozhimobile`, preserved
  Golden `versionName=0.6.5`, and validated all 9 required APK entries.
- The combined all-stage invocation was attempted, but Windows host memory
  pressure left a Kotlin CLI self-check hung inside a duplicated historical
  v0.6.5 subprocess. It was interrupted and its orphan process was cleaned;
  every stage was then run independently to completion, with no test failure.

## Review result

- A bounded delegated review was requested for commit `15f76fd` against
  `945f642`, but it timed out without a result and was not treated as approval.
- Fresh local spec-compliance, quality, and security review found no
  unresolved Critical or Important issue. The review confirmed that the
  Level-1 KWS sources remain unchanged against Golden commit
  `324dd5a53d404490bc4a32ed1f9ce8c45671ed24`.
- `DEVICE_GATE_PENDING`: the emulator evidence from Task 6 verified the
  model-included runtime and stable `WakeService`, but the emulator cannot
  produce a human-spoken custom `小白小白` phrase. Real spoken custom-wake,
  voice-dialogue, and TTS/ASR arbitration acceptance remain unclaimed.
