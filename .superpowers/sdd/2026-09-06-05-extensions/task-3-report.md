# Task 3 report — declarative SkillRegistry

## Scope

Implemented the FULL_TRAIN Task 3 declarative skill registry and runner on
top of the Task 1 package validator and Task 2 private extension manager.
The implementation is limited to declarative `SkillDefinition` data and
`ToolInvocation` emission; it does not execute arbitrary code or dispatch
through a second executor.

Base before Task 3: `09ebaaf`.

## Delivered

- `extensions/skills/SkillDefinition.kt`
  - strict bounded JSON parsing for id, version, name, triggers, workflow,
    arguments, allowed tools, tool budget, and prompt data;
  - duplicate-key, malformed-input, size, count, identifier, and budget
    validation;
  - compatibility aliases for the documented singular/plural and snake-case
    field spellings.
- `extensions/skills/SkillRegistry.kt`
  - registration, enable/disable, removal, duplicate and unknown-tool checks;
  - workflow allowlist and budget enforcement;
  - central safety-policy enforcement even when a caller supplies a custom
    known-tool set;
  - locale-stable trigger matching.
- `extensions/skills/SkillRunner.kt`
  - bounded input and run-time budget checks;
  - disabled/not-found/not-triggered handling;
  - conversion of an allowed declarative workflow into typed
    `ToolInvocation` values with input substitution;
  - prompts remain data and cannot grant tool permissions or create an extra
    invocation.

## TDD evidence

RED was confirmed first: the focused `SkillRegistryTest` source did not
compile because the Task 3 types were absent. GREEN was reached with the
smallest declarative implementation, followed by focused regression tests for
the new registry and its Task 1/2 predecessors.

Focused contract checks:

```text
python tools/test_v070_skill_registry_contract.py
python tools/test_v070_xzpack_validator_contract.py
python tools/test_v070_plugin_manager_contract.py
```

Focused Kotlin tests and the complete unit/build gate passed:

```text
gradle :app:testDebugUnitTest assembleDebug --console=plain
```

The final Golden/P0 regression also passed:

```text
python tools/test_v070_phase0_recovery_gate.py
PASS: v0.7 Phase 0 recovery gate
```

The phase gate included the frozen KWS source guard, v0.6.5 Golden/release
gate, historical behavior tests, alpha1 contracts, and the P0 recovery
contracts. Frozen Level-1 KWS sources were unchanged.

## Review evidence

Two fresh delegated review attempts were made after implementation; both
became unavailable by timeout. A fresh local spec-compliance and
code-quality/security review was completed instead. It caught and corrected
default-locale trigger lowercasing and a central safety-policy bypass in the
custom known-tool path. No unresolved Critical or Important issue remains.

Implementation commits:

- `0fd55ef` — `feat: add declarative skill registry`
- `9cc44ca` — `fix: enforce central skill tool policy`

Device/emulator validation remains `DEVICE_GATE_PENDING`; no Android device
or emulator was available, so no device result is claimed.
