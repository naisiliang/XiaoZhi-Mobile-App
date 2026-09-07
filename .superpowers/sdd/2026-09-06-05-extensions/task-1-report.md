# Phase 4 Task 1 report — `.xzpack` validation

Base: `39fdc6a`
Implementation commits: `be73abe`, `298ac2b`, `4a610dd`
Branch: `recovery/v0.7.0-golden-first-full`
Worktree: `E:\app_apk\XiaoZhi-Mobile-App\.worktrees\XiaoZhi-v0.7.0-golden-first-full`

## Delivered

- `ExtensionManifest` parses a bounded strict JSON shape with required id,
  semver-like version, name, declarative permissions/capabilities, and
  per-payload SHA-256 declarations.
- Duplicate JSON keys, duplicate permissions/capabilities, unknown permissions,
  invalid ids/versions, invalid paths, and malformed digests fail closed.
- `XzPackValidator` uses streaming `ZipInputStream` validation with compressed,
  entry, total-size, entry-count, path-length, and nesting limits.
- Only the declared `.xzpack` roots and safe data/template/image extensions are
  accepted. Traversal, absolute paths, duplicate normalized entries, DEX/JAR/SO
  and script/native extensions are rejected.
- No payload is extracted, loaded, or executed. Every payload entry must be
  declared in the manifest and match its SHA-256 digest.

## RED / GREEN evidence

RED was confirmed by the new validator test failing to compile before the
implementation existed. GREEN then passed:

- `python tools/test_v070_xzpack_validator_contract.py`;
- `gradle :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.extensions.XzPackValidatorTest --console=plain`;
- all `:app:testDebugUnitTest` tests;
- `gradle :app:testDebugUnitTest assembleDebug --console=plain`.

The focused tests cover valid packages, safe directory entries, traversal,
DEX/JAR/SO/script payloads, unsupported extensions, duplicate JSON keys,
invalid versions, unknown permissions, malformed UTF-8, SHA mismatch, and
streaming entry limits.

## Regression and review gate

After the final validation hardening, the v0.7 Phase 1, Phase 2 messaging,
Phase 3 music/video, v0.6 settings/UI/security/SafeTool static contracts and
the full `test_v070_phase0_recovery_gate.py` all passed. The final Phase0 run
used the bundled Kotlin compiler shim and ended with `PASS: v0.7 Phase 0
recovery gate`.

Two delegated reviews were attempted after the task commit but were
unavailable within the review window. A fresh local spec-compliance and
code-quality/security review found no unresolved Critical or Important issue.
The Level-1 frozen KWS sources remain unchanged.

Android device/emulator verification was not available and is explicitly
`DEVICE_GATE_PENDING`.
