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
    "AgentToolExecutor" in ORCHESTRATOR
    and "maxArtifactBytes" in ORCHESTRATOR
    and "execution.artifactBytes" in ORCHESTRATOR,
    "artifact-producing tools must use the app-owned executor and measured output",
)
require(
    "toolExecutor" in ORCHESTRATOR
    and "toolExecutor" in ORCHESTRATOR[ORCHESTRATOR.find("val artifactBytes"):],
    "artifact-producing tools must fail closed when no app-owned executor is present",
)
require(
    "measuredBytes > maximum" in ORCHESTRATOR
    and "measuredBytes < 0L" in ORCHESTRATOR,
    "the measured output must be checked against the remaining artifact budget",
)
require(
    "artifactBytesFor(normalized, step.arguments)" not in ORCHESTRATOR
    and "if (AgentRegistry.isArtifactProducingTool(tool)) null else 0L" not in ORCHESTRATOR,
    "manifest artifact_size_bytes must never be the accounting source",
)

print("PASS: Task 8 agent artifact budget contract")
