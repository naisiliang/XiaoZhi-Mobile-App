# Phase 5 Task 7 report — Artifact/Image save, open, and share

Date: 2026-09-08

## Scope

- Added a private-first `ArtifactExportCoordinator`: ordinary artifact
  creation remains in the app-private workspace and the default path performs
  no external write.
- Added explicit SAF `ACTION_CREATE_DOCUMENT` preparation and completion via
  the user-selected content URI. Source path, size, status, and SHA-256 are
  revalidated before copying.
- Added image-only scoped MediaStore export under `Pictures/XiaoZhi` with
  `IS_PENDING` publication. Android versions without scoped MediaStore support
  return `PERMISSION_REQUIRED` instead of requesting broad storage access.
- Added FileProvider-backed `ACTION_VIEW` and `ACTION_SEND` flows with read
  grants. The provider whitelist exposes only private `artifacts/` files.
- Added `ImageExportCoordinator`, which revalidates image signatures/MIME
  before gallery, SAF, open, or share actions.

## TDD evidence

- RED: `test_v070_artifact_export_contract.py` failed because export sources,
  FileProvider manifest wiring, and the restricted paths resource were absent.
- RED: `ArtifactExportCoordinatorTest` failed to compile because the export
  coordinator, result codes, and operations boundary were absent.
- GREEN: focused tests passed for private default, explicit SAF routing,
  image-only gallery routing, content-URI open/share, invalid destination and
  source rejection, and truthful permission failure.
- Review-fix GREEN: pure URI validation uses `java.net.URI` for JVM tests;
  Android writes use the documented truncate mode `rwt`; MediaStore export
  never requests broad storage permission.

## Verification

- `python -B -X utf8 tools/test_v070_artifact_export_contract.py` — PASS
- `gradle :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.artifacts.ArtifactExportCoordinatorTest --no-daemon` — BUILD SUCCESSFUL
- `gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon` — BUILD SUCCESSFUL
- Artifact/Image, provider, PPT, version, and Phase 0 contracts with the
  configured JDK/Kotlin/Android toolchain — PASS
- `git diff --cached --check` — PASS before commit

## Review

- Delegated review was unavailable after a bounded wait; the timeout was not
  treated as approval.
- Fresh local spec-compliance and code-quality/security review found no
  unresolved Critical or Important issue after the review fix.
- No Level-1 frozen KWS source or parameter was changed. Device validation
  remains `DEVICE_GATE_PENDING`.

## Commit

- Recorded with the Task 7 implementation commit.
