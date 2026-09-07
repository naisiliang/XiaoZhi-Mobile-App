# Phase 5 Task 1 report — Artifact core model and private workspace

Date: 2026-09-08

## Scope

- Added `Artifact` metadata with artifactId, sessionId, mimeType, displayName, privatePath, size, SHA-256, createdAt, sourceAgent, status, and version lineage.
- Added `ArtifactVersion` immutable lineage records and parent-version validation.
- Added `ArtifactWorkspace` with app-private storage, canonical containment checks, traversal-safe IDs/extensions, bounded temp files, atomic no-overwrite moves, and safe cleanup.
- Added `ArtifactRepository` with a pure-Kotlin in-memory constructor for deterministic tests and a private SQLite metadata store for Android. External file import copies into the private workspace and never overwrites the original.
- Added truthful size/digest checks, current-version metadata, version listing, immutable duplicate-version handling, and transactional artifact/version metadata writes with foreign-key-safe deletion.

## TDD evidence

- RED: `test_v070_artifact_core_contract.py` failed because the four artifact core files did not exist.
- RED: `ArtifactCoreTest` failed to compile because `ArtifactWorkspace`, `Artifact`, `ArtifactVersion`, and `ArtifactRepository` were absent.
- GREEN: private path, traversal, external-copy, metadata, digest, version-lineage, and original-protection tests passed.
- Review-fix RED: saving an older version after v2 exposed a possible current-record overwrite before duplicate-version failure.
- Review-fix GREEN: idempotent existing-version detection and atomic metadata/version persistence passed; SQLite foreign keys are enabled and removal deletes child versions first.

## Verification

- `python tools/test_v070_artifact_core_contract.py` — PASS
- `gradle :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.artifacts.ArtifactCoreTest --console=plain` — BUILD SUCCESSFUL
- `gradle :app:testDebugUnitTest :app:assembleDebug --console=plain` — BUILD SUCCESSFUL
- All extension/security static contracts and Phase 1/reviewer/sensitive-Vision gates — PASS
- `python tools/test_v070_phase0_recovery_gate.py` with the configured Kotlin/JDK/Android toolchain — `PASS: v0.7 Phase 0 recovery gate`
- `git diff --check` — PASS

## Review

- Two delegated read-only review requests were made after the implementation commit; both timed out after a bounded 60-second window and were closed. The timeout was not treated as approval.
- Fresh local spec-compliance and code-quality/security review found no unresolved Critical or Important issue.
- No Level-1 frozen KWS file or frozen KWS parameter was changed. Device validation remains `DEVICE_GATE_PENDING`.

## Commits

- `7b0c3e9 feat: add private artifact core`
- `3ce0be1 fix: preserve artifact version lineage`
