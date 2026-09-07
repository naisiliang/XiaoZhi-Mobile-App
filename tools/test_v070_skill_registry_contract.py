from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SKILLS = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/extensions/skills"
DEFINITION = SKILLS / "SkillDefinition.kt"
REGISTRY = SKILLS / "SkillRegistry.kt"
RUNNER = SKILLS / "SkillRunner.kt"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


definition = DEFINITION.read_text(encoding="utf-8") if DEFINITION.exists() else ""
registry = REGISTRY.read_text(encoding="utf-8") if REGISTRY.exists() else ""
runner = RUNNER.read_text(encoding="utf-8") if RUNNER.exists() else ""

for marker in (
    "data class SkillDefinition",
    "triggers",
    "workflow",
    "allowedTools",
    "toolBudget",
    "prompt",
    "DeclarativeJsonParser",
):
    require(marker in definition, f"missing declarative skill definition contract: {marker}")

for marker in (
    "class SkillRegistry",
    "UNKNOWN_TOOL",
    "TOOL_NOT_ALLOWED",
    "BUDGET_EXCEEDED",
    "enabledIds",
):
    require(marker in registry, f"missing guarded skill registry contract: {marker}")

for marker in (
    "class SkillRunner",
    "ToolInvocation",
    "TOOL_BUDGET_EXCEEDED",
    "step.arguments",
    "{input}",
):
    require(marker in runner, f"missing declarative skill runner contract: {marker}")

forbidden = (
    "ToolDispatcher",
    "SafeToolExecutor",
    "DeviceActionExecutor",
    "DexClassLoader",
    "PathClassLoader",
    "Class.forName",
    "ProcessBuilder",
    "Runtime.getRuntime",
    "PermissionBroker",
)
for value in forbidden:
    require(value not in runner, f"skill runner must emit requests, not execute or grant authority: {value}")

require("prompt" not in runner, "skill prompt content must not expand workflow permissions")
print("PASS: v0.7 declarative SkillRegistry and ToolInvocation contract")
