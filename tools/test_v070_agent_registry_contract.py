from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AGENTS = ROOT / "app" / "src" / "main" / "java" / "com" / "lchuang" / "xiaozhimobile" / "extensions" / "agents"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


definition = (AGENTS / "AgentDefinition.kt").read_text(encoding="utf-8")
budget = (AGENTS / "AgentBudget.kt").read_text(encoding="utf-8")
registry = (AGENTS / "AgentRegistry.kt").read_text(encoding="utf-8")
orchestrator = (AGENTS / "AgentOrchestrator.kt").read_text(encoding="utf-8")

for path in (AGENTS / "AgentDefinition.kt", AGENTS / "AgentBudget.kt", AGENTS / "AgentRegistry.kt", AGENTS / "AgentOrchestrator.kt"):
    require(path.is_file(), f"missing Task 4 source: {path.name}")

for marker in ("data class AgentDefinition", "systemPrompt", "skills", "allowedTools", "permissions", "maxDelegationDepth", "maxToolCalls", "maxExecutionTimeMs"):
    require(marker in definition, f"AgentDefinition missing {marker}")

for marker in ("data class AgentBudget", "maxDelegationDepth", "maxToolCalls", "maxExecutionTimeMs", "maxArtifactSizeBytes"):
    require(marker in budget, f"AgentBudget missing {marker}")

for marker in ("class AgentRegistry", "CentralSafetyPolicyEngine", "requiredPermissionsFor", "小白智能体", "PPT专家", "图片设计师", "文件助手", "研究助手"):
    require(marker in registry, f"AgentRegistry missing {marker}")

for marker in ("class AgentOrchestrator", "ToolInvocation", "DELEGATION_CYCLE", "MAX_DELEGATION_DEPTH_EXCEEDED", "TOOL_BUDGET_EXCEEDED", "EXECUTION_TIMEOUT"):
    require(marker in orchestrator, f"AgentOrchestrator missing {marker}")

for forbidden in (
    "DexClassLoader",
    "PathClassLoader",
    "Class.forName",
    "ProcessBuilder",
    "Runtime.getRuntime",
    "ToolDispatcher",
    "SafeToolExecutor",
    "DeviceActionExecutor",
    "PermissionBroker",
):
    require(forbidden not in definition + budget + registry + orchestrator, f"agent path must not use {forbidden}")

require("activePath" in orchestrator and "target in path" in orchestrator, "delegation cycle guard missing")
require("ToolDecision.BLOCK" in registry and "ToolDecision.BLOCK" in orchestrator, "central safety block missing")
print("PASS: v0.7 bounded AgentRegistry and orchestration contract")
