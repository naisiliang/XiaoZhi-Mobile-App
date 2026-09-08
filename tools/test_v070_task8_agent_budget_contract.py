from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REGISTRY = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/extensions/agents/AgentRegistry.kt").read_text("utf-8")
ORCHESTRATOR = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/extensions/agents/AgentOrchestrator.kt").read_text("utf-8")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


require(
    "ARTIFACT_PRODUCING_TOOLS" in REGISTRY
    and "isArtifactProducingTool" in REGISTRY,
    "artifact-producing tools must be explicitly classified",
)
require(
    "artifactBytesFor(normalized, step.arguments)" in ORCHESTRATOR,
    "artifact accounting must be keyed by the normalized tool",
)
require(
    "if (AgentRegistry.isArtifactProducingTool(tool)) null else 0L" in ORCHESTRATOR,
    "missing artifact metadata must not be charged as zero for producing tools",
)

print("PASS: Task 8 agent artifact budget contract")
