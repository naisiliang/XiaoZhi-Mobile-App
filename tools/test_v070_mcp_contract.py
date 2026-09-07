from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MCP = ROOT / "app" / "src" / "main" / "java" / "com" / "lchuang" / "xiaozhimobile" / "extensions" / "mcp"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(message)


paths = {
    name: MCP / f"{name}.kt"
    for name in ("McpServerConfig", "McpToolInspector", "McpRegistry", "McpClient")
}
sources = {name: path.read_text(encoding="utf-8") for name, path in paths.items()}
all_source = "\n".join(sources.values())

for name, path in paths.items():
    require(path.is_file(), f"missing MCP source: {name}.kt")

for marker in ("enum class McpServerType", "declaredPermissions", "credentialRef", "validationError", "redacted"):
    require(marker in sources["McpServerConfig"], f"server config missing {marker}")

for marker in ("class McpToolInspector", "McpLocalCapability", "McpToolBinding", "PERMISSION_ESCALATION", "CentralSafetyPolicyEngine"):
    require(marker in sources["McpToolInspector"], f"tool inspector missing {marker}")

for marker in ("class McpRegistry", "no server is auto-discovered", "installToolSchema", "attachClient", "isEnabled"):
    require(marker in sources["McpRegistry"], f"MCP registry missing {marker}")

for marker in ("interface McpTransport", "serverType", "tools/call", "McpCredentialProvider", "McpCallResult", "Timeout", "Unavailable"):
    require(marker in sources["McpClient"], f"MCP client missing {marker}")

require("ToolInvocation" in sources["McpClient"], "MCP calls must use typed ToolInvocation")
require("ToolDecision.BLOCK" in sources["McpClient"], "MCP client must honor CentralSafetyPolicy BLOCK")
require("ToolDecision.CONFIRM" in sources["McpClient"], "MCP client must stop for confirmation")
require("REDACTED" in sources["McpServerConfig"], "MCP diagnostics must redact credentials")
require("transport.serverType != server.type" in sources["McpClient"], "transport type boundary missing")
require("responseId?.raw?.toLongOrNull() != expectedId" in sources["McpClient"], "JSON-RPC response id validation missing")
require("androidPermissions" in sources["McpToolInspector"], "Android permission escalation guard missing")

for forbidden in (
    "ProcessBuilder",
    "Runtime.getRuntime",
    "DexClassLoader",
    "PathClassLoader",
    "Class.forName",
    "ToolDispatcher",
    "SafeToolExecutor",
    "DeviceActionExecutor",
    "System.load",
):
    require(forbidden not in all_source, f"MCP path must not use {forbidden}")

print("PASS: v0.7 MCP registry, inspection, transport, and safety contract")
