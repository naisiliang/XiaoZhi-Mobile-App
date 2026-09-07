from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AGENTS = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/extensions/agents"
SAFETY = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/safety/ToolPolicyRegistry.kt"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


registry = (AGENTS / "AgentRegistry.kt").read_text(encoding="utf-8")
orchestrator = (AGENTS / "AgentOrchestrator.kt").read_text(encoding="utf-8")
safety = SAFETY.read_text(encoding="utf-8")

for marker in (
    "PPT_TOOLS",
    "IMAGE_TOOLS",
    "FILE_TOOLS",
    "RESEARCH_TOOLS",
    "ppt_create",
    "ppt_add_asset",
    "image_generate",
    "image_edit",
    "image_regenerate",
    "file_create_docx",
    "file_create_xlsx",
    "research_search",
    "ExtensionPermission.IMAGE_GENERATION",
):
    require(marker in registry, f"AgentRegistry missing domain ownership marker: {marker}")

for marker in (
    "ARTIFACT_BUDGET_EXCEEDED",
    "maxArtifactSizeBytes",
    "artifact_size_bytes",
    "artifactBytes",
):
    require(marker in orchestrator, f"AgentOrchestrator missing artifact budget marker: {marker}")

for marker in (
    '"image_generate" to ToolPolicyResult(ToolDecision.ALLOW)',
    '"image_save" to ToolPolicyResult(ToolDecision.CONFIRM)',
    '"file_create_docx" to ToolPolicyResult(ToolDecision.ALLOW)',
    '"file_save" to ToolPolicyResult(ToolDecision.CONFIRM)',
    '"ppt_create" to ToolPolicyResult(ToolDecision.ALLOW)',
):
    require(marker in safety, f"central safety policy missing agent tool: {marker}")

for forbidden in ("DexClassLoader", "PathClassLoader", "Class.forName", "ProcessBuilder", "Runtime.getRuntime"):
    require(forbidden not in registry + orchestrator, f"agent integration must not load executable code: {forbidden}")

print("PASS: v0.7 Agent domain ownership and composite artifact budget contract")
