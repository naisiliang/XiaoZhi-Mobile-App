from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
SETTINGS = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsActivity.kt"
MAIN = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/MainActivity.kt"
STORE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/SettingsStore.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_settings.xml"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def method_body(source, name):
    match = re.search(rf"\bfun\s+{re.escape(name)}\s*\([^)]*\)\s*\{{", source, re.S)
    require(match is not None, f"missing method: {name}")
    depth = 1
    index = match.end()
    while depth and index < len(source):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
        index += 1
    require(depth == 0, f"unbalanced method: {name}")
    return source[match.end() : index - 1]


def main():
    settings = SETTINGS.read_text(encoding="utf-8")
    main_activity = MAIN.read_text(encoding="utf-8")
    store = STORE.read_text(encoding="utf-8")
    layout = LAYOUT.read_text(encoding="utf-8")

    required_ids = (
        "smart_ui_enabled",
        "accessibility_status",
        "open_accessibility_settings",
        "vision_consent_status",
        "vision_capture_status",
        "refresh_screen_intelligence",
    )
    for view_id in required_ids:
        require(f"@+id/{view_id}" in layout, f"missing Phase 1 settings control: {view_id}")

    require(re.search(r"\bvar\s+smartUiEnabled\b", store), "SettingsStore smart UI toggle is missing")

    for marker in (
        "AccessibilityManager",
        "AccessibilityServiceInfo",
        "getEnabledAccessibilityServiceList",
        "XiaoZhiAccessibilityService::class.java.name",
        "ACTION_ACCESSIBILITY_SETTINGS",
        "startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))",
    ):
        require(marker in settings, f"missing user-controlled Accessibility status/entry: {marker}")

    for marker in (
        "smartUiEnabled",
        "accessibilityStatus",
        "visionConsentStatus",
        "visionCaptureStatus",
        "renderScreenIntelligenceStatus",
        "refresh_screen_intelligence",
    ):
        require(marker in settings, f"missing Settings screen-intelligence wiring: {marker}")

    load = method_body(settings, "loadSettings")
    persist = method_body(settings, "persistSettings")
    on_resume = method_body(settings, "onResume")
    require("settings.smartUiEnabled" in load, "smart UI toggle must load from SettingsStore")
    require("settings.smartUiEnabled" in persist, "smart UI toggle must persist to SettingsStore")
    require("renderScreenIntelligenceStatus()" in on_resume, "Settings must refresh permission status on resume")
    require("renderScreenIntelligenceStatus()" in settings, "Settings must render Vision and Accessibility status")

    for forbidden in (
        "Settings.Secure",
        "enabled_accessibility_services",
        "accessibility_enabled",
        "Runtime.getRuntime().exec",
        "su ",
    ):
        require(forbidden not in settings, f"Settings must not bypass system permission controls: {forbidden}")

    for marker in (
        "private fun renderScreenIntelligenceStatus()",
        "settings.smartUiEnabled",
        "renderStatus()",
        "onResume",
    ):
        require(marker in main_activity, f"MainActivity status/card integration is missing: {marker}")

    require("Vision" in settings and "当前会话" in settings, "Vision status must be explicit and session-scoped")
    require(
        "不保存截图" in settings or "不保存原始截图" in settings,
        "Vision status must disclose transient/no-persistence behavior",
    )
    print("PASS: v0.7 Phase 1 screen-intelligence UI/status gate")


if __name__ == "__main__":
    main()
