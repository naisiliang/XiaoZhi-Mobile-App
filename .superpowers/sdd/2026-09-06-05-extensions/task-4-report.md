# Task 4 report — bounded AgentRegistry and orchestration

## Scope

Implemented FULL_TRAIN Phase 4 Task 4 as a declarative, bounded agent layer.
An Agent contains model/prompt/skills/tools/permissions metadata and explicit
delegation, tool-call, execution-time, and artifact-size limits. The
orchestrator produces typed `ToolInvocation` values only; the existing
app-owned dispatcher remains the only execution and confirmation path.

Base before Task 4: `63bde0f`.

## Delivered

- `extensions/agents/AgentDefinition.kt`
  - bounded strict JSON parsing for agent metadata, workflow steps,
    permissions, skill IDs, delegation targets, and all budget fields;
  - UTF-8 JSON, string, list, argument, identifier, version, duplicate-key,
    and malformed-input protection through the shared declarative parser;
  - compatibility aliases for documented camelCase/snake_case fields.
- `extensions/agents/AgentBudget.kt`
  - hard bounds for delegation depth, tool calls, execution time, and artifact
    size, with safe defaults and spec-name aliases.
- `extensions/agents/AgentRegistry.kt`
  - first-party built-ins: 小白智能体, PPT专家, 图片设计师, 文件助手,
    研究助手;
  - registration, enable/disable/remove, known-tool validation, central
    safety-policy validation, sensitive-tool permission checks, and bounded
    direct-constructed metadata;
  - PPT/image/file/research domain agents have no Accessibility,
    phone-control, or messaging permission/tool by default.
- `extensions/agents/AgentOrchestrator.kt`
  - declarative workflow-to-`ToolInvocation` planning;
  - global and per-agent tool budgets, maximum delegation depth, execution
    deadlines, input bounds, disabled/not-found handling, and active-path
    cycle detection;
  - rechecks CentralSafetyPolicy and declared permissions at run time, so
    mutated or stale metadata fails closed.

## TDD and regression evidence

RED was confirmed first: the new focused test class failed to compile because
the four Task 4 production types were absent. GREEN was reached with the
bounded declarative implementation. Focused tests then exposed only test
expectation issues around retained failure traces and fake-clock sequencing;
those expectations were corrected. A subsequent local review identified and
fixed the final-deadline completion edge and direct-definition delegation-list
bound.

Focused static and Kotlin checks passed:

```text
python tools/test_v070_agent_registry_contract.py
gradle :app:testDebugUnitTest --tests com.lchuang.xiaozhimobile.extensions.agents.AgentRegistryTest --tests com.lchuang.xiaozhimobile.extensions.skills.SkillRegistryTest --tests com.lchuang.xiaozhimobile.extensions.PluginManagerTest --tests com.lchuang.xiaozhimobile.extensions.XzPackValidatorTest --console=plain
```

Final full verification passed after the last code change:

```text
gradle :app:testDebugUnitTest assembleDebug --console=plain
BUILD SUCCESSFUL

python tools/test_v070_phase0_recovery_gate.py
PASS: v0.7 Phase 0 recovery gate
```

The Phase 0 gate included the v0.6.5 Golden/release gate, frozen KWS source
guard, historical behavior, alpha1 contracts, and all P0 recovery contracts.
No Level-1 frozen KWS file was modified.

## Review evidence

Fresh delegated spec and security review attempts were made after the final
implementation; all became unavailable by timeout. The local fresh review
checked the same required contract and found no unresolved Critical or
Important issue. In particular, unknown tools default to denial, dangerous
tools cannot be reintroduced through a caller-supplied known set, messaging/
phone/accessibility tools require declared permission, prompts never create
invocations, and no arbitrary code loader/process/executor is present.

Implementation commits:

- `3f4eebd` — `feat: add bounded agent orchestration`
- `24fee56` — `fix: harden agent execution bounds`

Device/emulator validation remains `DEVICE_GATE_PENDING`; no Android device
or emulator was available, so no device result is claimed.
