from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ADAPTER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/messaging/MessagingToolAdapter.kt"
REGISTRY = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/tools/ToolRegistry.kt"
AI = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/AiOrchestrator.kt"
POLICY = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/safety/ToolPolicyRegistry.kt"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


adapter = ADAPTER.read_text(encoding="utf-8") if ADAPTER.exists() else ""
registry = REGISTRY.read_text(encoding="utf-8") if REGISTRY.exists() else ""
ai = AI.read_text(encoding="utf-8")
policy = POLICY.read_text(encoding="utf-8")

for marker in (
    'const val TOOL_NAME = "send_text_message"',
    '"packageName" to "string"',
    '"contactReference" to "string"',
    '"body" to "string"',
    'fun resolve(invocation: ToolInvocation)',
    'MessagingToolResolution.Rejected("MESSAGE_UNSUPPORTED_ARGUMENT")',
    'MessagingToolResolution.Rejected("MESSAGE_UNSUPPORTED_PACKAGE")',
):
    require(marker in adapter, f"missing messaging adapter contract: {marker}")

for marker in (
    'fun definitions(): List<AiToolDefinition>',
    'fun definitionFor(name: String): AiToolDefinition?',
    'MessagingToolAdapter().definition()',
):
    require(marker in registry, f"missing tool registry contract: {marker}")

require('ToolRegistry.definitions()' in ai, "AI planner must consume the central tool registry")
require('send_text_message" to ToolPolicyResult(ToolDecision.CONFIRM)' in policy,
        "messaging tool must remain CONFIRM in the central policy")
require('MessagingRequest' in adapter, "adapter must hand off a typed request")
require('UiActionProposal' not in adapter, "messaging adapter must not create UI action proposals")
require('ToolDispatcher' not in adapter, "messaging adapter must not dispatch or execute tools")
require('sendText(' not in adapter, "messaging adapter must not expose a lower-level send executor")
require('Accessibility' not in adapter, "messaging adapter must not call Accessibility APIs")
require('MessageSendRequest' not in adapter, "extensions must not receive a lower-level send request")

for forbidden in ("send_image", "send_file", "send_voice", "manage_group"):
    require(forbidden not in adapter, f"unsupported messaging capability declared: {forbidden}")

print("PASS: v0.7 guarded ordinary-text messaging tool contract")
