# Ruling 4 — Spec-Alignment Task A10: truthful TaskProgressTracker foundation

Design Spec §37 and Stage 1 require TaskProgressTracker.

**Contract**
- Supported task states: `QUEUED`, `RUNNING`, `WAITING_USER`, `WAITING_PERMISSION`, `WAITING_CONFIRMATION`, `COMPLETED`, `FAILED`, `CANCELLED`, `BLOCKED`, `INTERRUPTED`.
- Visible progress must come from real state / real phase / real item counts. Do not expose an AI-invented arbitrary percent as authoritative progress.
- This is the foundation only; long-running Artifact/Agent cancellation integration remains later-stage scope.
