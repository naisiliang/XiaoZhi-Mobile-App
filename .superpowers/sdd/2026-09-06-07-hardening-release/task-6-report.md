# Phase 6 Task 6: Final Responsive UI Evidence

## Evidence scope

- APK under test: `app/build/outputs/apk/debug/app-debug.apk`, built from
  recovery HEAD `c95521b4549c70ee49015a7fb788ab3822206030` after the official
  Sherpa KWS and Paraformer assets were fetched locally.
- Device: API 35 Google APIs x86_64 emulator `xiaozhi_v070_api35`, serial
  `emulator-5554`, 1080x2220 physical display at density 440.
- Evidence directory:
  `C:\Users\ASUS\Downloads\xiaozhi-v070-task6-evidence-20260908`
- The 24 valid captures are chat/history/settings at 360x800, 393x873,
  411x914, and 600x960 dp, each at fontScale 1.0 and 1.3. History and
  settings were reached through the app's own main-menu navigation; the
  `SettingsActivity` 600x960/fontScale 1.3 capture was re-taken after the
  activity became top-resumed to exclude the earlier transition frame.

## Boundary checks

- Parsed all 24 UI hierarchy XML files against their declared display bounds:
  `TOTAL_FILES=24`, `TOTAL_OUTSIDE=0`.
- Chat header and composer interactive children remain within the viewport at
  every size/font combination. The quick-action row is intentionally a
  `HorizontalScrollView`; its clipped fourth chip is inside that scroll
  viewport, not an unbounded layout overflow.
- The IME evidence is
  `main-360x800-fs1.3-ime.png` and its hierarchy. The input field is bounded
  by `[160,1239][795,1362]` and the send control by
  `[795,1240][935,1361]`; both remain above the visible keyboard boundary.
- History title and settings title remain below the top system inset, and the
  settings content is carried by a vertical `ScrollView` on the small display.
- Source contracts passed:
  `python tools/test_v070_recovery_ui_contract.py`,
  `python tools/test_v070_recovery_history_ui.py`, and
  `python tools/test_v070_recovery_settings_parity.py`.

## Visual comparison and runtime evidence

- The chat captures preserve the reference structure in
  `references/xiaobai_chat_ui_reference.png`: assistant header/avatar,
  conversation surface, quick actions, and bottom composer. No scaffold list
  row or debug subtitle is present.
- The model-included debug APK installed successfully. After startup the main
  activity remained top-resumed and the foreground `WakeService` remained
  alive with the offline-wake notification. The previous Sherpa native model
  load/exit-255 failure did not recur after the required model assets were
  present.
- No human-spoken custom phrase was available in the emulator. Real-device
  spoken `小白小白` wake, post-wake voice dialogue, and audio/TTS arbitration
  therefore remain an acceptance gate rather than a claimed pass.

DEVICE_GATE_PENDING

## Review result

Task 6 changed no Level-1 frozen KWS source and introduced no production-code
change. Fresh local spec, quality, and security review found no unresolved
Critical or Important issue. The evidence and this report are ready for the
Task 6 documentation commit.
