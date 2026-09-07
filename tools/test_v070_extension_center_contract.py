from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
JAVA_ROOT = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile"
UI_ROOT = JAVA_ROOT / "extensions/ui"
MAIN = JAVA_ROOT / "MainActivity.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def read(path):
    require(path.exists(), f"missing required file: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


extension = read(UI_ROOT / "ExtensionCenterActivity.kt")
agent = read(UI_ROOT / "AgentCenterActivity.kt")
main = read(MAIN)
manifest = read(MANIFEST)

for marker in (
    "class ExtensionCenterActivity",
    "ACTION_OPEN_DOCUMENT",
    "contentResolver.openInputStream",
    "PluginManager",
    "importPackage",
    "NeedsPermissionConfirmation",
    "ExtensionPermission",
    "AlertDialog",
    "requiredPermissions",
    "Switch",
    "enable(",
    "disable(",
    "health",
    "extension_cards",
    "skill_cards",
    "mcp_cards",
):
    require(marker in extension, f"extension center missing marker: {marker}")

for marker in (
    "class AgentCenterActivity",
    "AgentRegistry",
    "builtIns()",
    "isEnabled",
    "enable(",
    "disable(",
    "health",
    "agent_cards",
):
    require(marker in agent, f"agent center missing marker: {marker}")

for forbidden in (
    "apiKey",
    "credentialRef",
    "systemPrompt",
    "将在后续版本接入",
    "showUnavailable(\"插件与技能\")",
    "showUnavailable(\"Agents\")",
):
    require(forbidden not in extension and forbidden not in agent and forbidden not in main,
            f"UI must not expose placeholder or secret-bearing field: {forbidden}")

require("ExtensionCenterActivity" in main, "MainActivity must launch ExtensionCenterActivity")
require("AgentCenterActivity" in main, "MainActivity must launch AgentCenterActivity")
require("startActivity(Intent(this@MainActivity, ExtensionCenterActivity::class.java))" in main,
        "plugin menu must launch ExtensionCenterActivity")
require("startActivity(Intent(this@MainActivity, AgentCenterActivity::class.java))" in main,
        "agent menu must launch AgentCenterActivity")

for activity in (".extensions.ui.ExtensionCenterActivity", ".extensions.ui.AgentCenterActivity"):
    require(f'android:name="{activity}"' in manifest, f"manifest missing non-exported activity: {activity}")

for layout_name, ids in {
    "activity_extension_center.xml": ("extension_center_root", "import_extension", "extension_cards", "skill_cards", "mcp_cards"),
    "activity_agent_center.xml": ("agent_center_root", "agent_cards"),
}.items():
    path = ROOT / "app/src/main/res/layout" / layout_name
    source = read(path)
    try:
        ET.fromstring(source)
    except ET.ParseError as error:
        raise AssertionError(f"malformed {layout_name}: {error}")
    for view_id in ids:
        require(f"@+id/{view_id}" in source, f"{layout_name} missing id: {view_id}")

require(re.search(r"ViewCompat\.setOnApplyWindowInsetsListener", extension),
        "extension center must apply WindowInsets")
require(re.search(r"ViewCompat\.setOnApplyWindowInsetsListener", agent),
        "agent center must apply WindowInsets")
require("diagnostics()" in extension, "MCP UI must use redacted diagnostics")
require("McpRegistry" in extension, "extension center must expose MCP registry state")

print("PASS: v0.7 extension and agent center contract")
