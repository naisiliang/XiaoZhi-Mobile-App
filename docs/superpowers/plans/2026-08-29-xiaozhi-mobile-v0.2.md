# XiaoZhi Mobile v0.2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a GitHub-buildable Android APK with local wake word, local phone commands, and configurable AI chat fallback.

**Architecture:** Native Kotlin Android app with a microphone foreground service. sherpa-onnx handles local KWS; Android platform APIs handle local commands; general queries use an OpenAI-compatible HTTP endpoint.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Gradle 8.9, compile/target SDK 35, sherpa-onnx 1.13.4.

**Spec:** `docs/superpowers/specs/2026-08-29-xiaozhi-mobile-design.md`

## Global Constraints
- minSdk 26, targetSdk 35.
- arm64-v8a first release.
- Wake phrase audio stays local.
- Local controls execute before AI fallback.
- GitHub Actions must upload a real APK artifact.

---

### Task 1: Validate project contract
**Files:** `tools/validate_project.py`
- [x] Add static project checks for version, ABI, foreground service, wake phrase and workflow output.
- [x] Run validator and observe failures for the v0.1 contract.
- [x] Update project contract to v0.2 and rerun until all checks pass.

### Task 2: KWS configuration
**Files:** `app/src/main/java/com/lchuang/xiaozhimobile/WakeService.kt`
- [x] Set `modelingUnit = "cjkchar"` for the selected Chinese/English KWS model.
- [x] Confirm wake model file names match official model documentation.

### Task 3: Reproducible APK CI
**Files:** `.github/workflows/build-apk.yml`, `scripts/fetch-kws-model.sh`
- [x] Install SDK 35/build-tools 35.0.0 and Gradle 8.9.
- [x] Run source validator.
- [x] Download KWS model.
- [x] Assemble debug APK.
- [x] Upload `XiaoZhi-Mobile-v0.2.0-debug.apk` as Actions artifact.

### Task 4: Repository handoff
**Files:** `PUSH_TO_GITHUB.ps1`, `PUSH_TO_GITHUB.bat`, `GITHUB_BUILD_GUIDE.md`
- [x] Bind upload helper to `naisiliang/XiaoZhi-Mobile-App`.
- [x] Clone rather than force-push, preserving normal Git history.
- [x] Copy project, commit changed files and push main.
- [x] Document APK artifact download and phone install steps.
