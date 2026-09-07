# Phase 5 Task 3 report — DOCX, XLSX, and PDF artifacts

Date: 2026-09-08

## Scope

- Added real DOCX and XLSX OPC packages with content types, package
  relationships, document/workbook XML, worksheet data, escaped user text, and
  structural validators.
- Added a real PDF writer with catalog/pages tree, page/content/font objects,
  non-empty page streams, cross-reference table, trailer, and EOF marker.
- Reused the private staged-generation path so all three formats are size
  bounded, validated from the written file, and registered as completed only
  after validation.
- Added secure XML parser features that reject DOCTYPE and external entities,
  reused ZIP path/duplicate/CRC/resource checks, and added aggregate memory
  budgets before constructing large XML/PDF buffers.
- Kept `app/build.gradle.kts` dependency-free for these formats; no heavy
  document runtime was added.

## TDD evidence

- RED: `test_v070_structured_artifact_contract.py` failed because the
  structured generator sources were absent.
- RED: `StructuredArtifactGeneratorsTest` failed to compile because the DOCX,
  XLSX, and PDF generator/validator types were absent.
- GREEN: four focused tests passed, covering OPC entries and MIME metadata,
  workbook/sheet structure, PDF header/xref/non-empty pages, and rejection of
  plain-text extension spoofing.
- Review-fix RED/GREEN: compiler failures exposed Android API incompatibility,
  a missing namespace constant, a PDF byte-length bug, and a duplicate helper;
  the minimal fixes compiled and the focused suite passed.
- Review hardening: aggregate XML/PDF budgets were added after local review
  identified per-field/per-page-only limits that could construct oversized
  in-memory objects before the final size check.

## Verification

- `python tools/test_v070_structured_artifact_contract.py` — PASS
- `gradle :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.artifacts.StructuredArtifactGeneratorsTest --console=plain` — BUILD SUCCESSFUL (4 tests)
- `gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --console=plain` — BUILD SUCCESSFUL
- `python tools/test_v070_phase0_recovery_gate.py` with the configured
  JDK/Kotlin/Android toolchain — `PASS: v0.7 Phase 0 recovery gate`
- All v0.7 static contracts — PASS
- Debug APK: 15,495,033 bytes
- Release unsigned APK: 13,756,694 bytes
- No new DOCX/XLSX/PDF runtime dependency in `app/build.gradle.kts`.
- `git diff --cached --check` — PASS before commit

## Review

- Two delegated read-only review requests were made after implementation; both
  remained running through a bounded wait and were then closed. The timeout was
  not treated as approval.
- Fresh local spec-compliance and code-quality/security review found no
  unresolved Critical or Important issue.
- No Level-1 frozen KWS source or parameter was changed. Device validation
  remains `DEVICE_GATE_PENDING`.

## Commit

- `4820279 feat: add validated docx xlsx and pdf artifacts`
