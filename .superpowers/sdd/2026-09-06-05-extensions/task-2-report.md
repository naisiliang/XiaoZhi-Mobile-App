# Phase 4 Task 2 report — private extension storage and manager

Base: `658c279`
Implementation commits: `e491e15`, `1f32562`
Branch: `recovery/v0.7.0-golden-first-full`
Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

## Delivered

- `ExtensionRepository` stores the raw, already-validated archive and state
  only below an app-private root. Imports are bounded while being spooled and
  are never extracted into user-visible storage.
- Archive replacement uses temporary files and a disabled-state-first order;
  a failed archive or final-state move cannot reactivate unconfirmed content on
  restart.
- `PluginManager` validates the staged archive before installation, imports it
  disabled by default, and exposes the complete declared permission set for
  user confirmation.
- Enabling requires an exact match for the manifest permission set. An update
  that adds permissions clears the old confirmation and runtime registration
  until the new set is confirmed.
- Disable and remove both clear the app-owned runtime registry. Persisted state
  is revalidated on manager reload; invalid or mismatched archives stay
  disabled.
- The runtime registry accepts declarative manifest metadata only. No DEX/JAR/
  native/script loader, shell, or arbitrary package execution path exists.

## RED / GREEN evidence

RED was confirmed by the new lifecycle test failing to compile before the
repository/manager implementation existed. GREEN then passed:

- `python tools/test_v070_plugin_manager_contract.py`;
- `gradle :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.extensions.PluginManagerTest --console=plain`;
- all `:app:testDebugUnitTest` tests;
- `gradle :app:testDebugUnitTest assembleDebug --console=plain`.

The lifecycle tests cover default-disabled import, exact permission review,
permission-expanding updates, persisted enabled/disabled state, runtime
unregistration on disable/remove, and malformed/traversal packages not being
stored.

## Regression and review gate

After the final fail-closed replacement hardening, the xzpack validator,
Phase 1 screen, Phase 2 messaging, Phase 3 music/video, v0.6 settings/UI/
security/SafeTool contracts and the full `test_v070_phase0_recovery_gate.py`
all passed. The required Kotlin compiler shim was included for the historical
checks. The final Phase0 run ended with `PASS: v0.7 Phase 0 recovery gate`.

Two delegated Task2 reviews were attempted after the implementation commit but
were unavailable within the review window. A fresh local spec-compliance and
code-quality/security review found no unresolved Critical or Important issue.
Level-1 KWS frozen sources remain unchanged.

Android device/emulator verification was not available and remains
`DEVICE_GATE_PENDING`.
