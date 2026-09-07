# Phase 5 Task 6 report — Image generation conversation tool

Date: 2026-09-08

## Scope

- Added explicit image intent resolution for create, edit, and regenerate
  requests. Edit and regenerate require an explicit prior image context.
- Added capability-gated `ImageGenerationTool` with separate generation and
  edit endpoints, private artifact staging, image signature/MIME/size
  validation, SHA-256 checked source loading, and image lineage metadata.
- Added an HTTP provider for JSON/base64 generation and multipart image edits.
  It rejects redirects and untrusted image URLs, keeps provider errors bounded
  and sanitized, and does not expose API keys in request text.
- Added image result-card support for edit and regenerate actions. Screen
  capture, Screen Vision, and MediaProjection are not inputs to this tool.

## TDD evidence

- RED: `test_v070_image_generation_contract.py` failed because the image
  generation sources were absent.
- RED: `ImageGenerationToolTest` failed to compile because the intent,
  provider, artifact, and result types were absent.
- GREEN: focused image tests passed for create, edit context transport,
  capability rejection, private PNG storage, result-card actions, and
  sanitized provider failure.
- Review-fix RED: a provider failure containing the configured API key was
  still echoed by the rejection detail.
- Review-fix GREEN: configured-key redaction now passes; same-origin image
  downloads require the original protocol, and JSON response bounds cover
  base64 expansion of the maximum decoded image size.

## Verification

- `python -B -X utf8 tools/test_v070_image_generation_contract.py` — PASS
- `gradle :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.image.ImageGenerationToolTest --no-daemon` — BUILD SUCCESSFUL
- Focused artifact/provider regression tests — BUILD SUCCESSFUL
- `gradle :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease --no-daemon` — BUILD SUCCESSFUL
- Artifact, provider, PPT, version, and Phase 0 contracts with the configured
  JDK/Kotlin/Android toolchain — PASS
- `git diff --cached --check` — PASS before commit

## Review

- Delegated review was unavailable after a bounded wait; the timeout was not
  treated as approval.
- Fresh local spec-compliance and code-quality/security review found no
  unresolved Critical or Important issue after the review fixes.
- No Level-1 frozen KWS source or parameter was changed. Device validation
  remains `DEVICE_GATE_PENDING`.

## Commit

- Recorded with the Task 6 implementation commit.
