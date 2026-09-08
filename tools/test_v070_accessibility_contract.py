from pathlib import Path
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
ANDROID = "{http://schemas.android.com/apk/res/android}"
MANIFEST_PATH = ROOT / "app/src/main/AndroidManifest.xml"
CONFIG_PATH = ROOT / "app/src/main/res/xml/accessibility_service_config.xml"
SERVICE_PATH = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/accessibility/XiaoZhiAccessibilityService.kt"
BUILDER_PATH = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/accessibility/AccessibilitySnapshotBuilder.kt"
NODE_PATH = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/screen/ScreenNode.kt"


def require(condition, message, failures):
    if not condition:
        failures.append(message)


def read(path, failures):
    if not path.is_file():
        failures.append(f"missing required file: {path.relative_to(ROOT)}")
        return ""
    return path.read_text("utf-8")


def main():
    failures = []
    manifest_text = read(MANIFEST_PATH, failures)
    config_text = read(CONFIG_PATH, failures)
    service_text = read(SERVICE_PATH, failures)
    builder_text = read(BUILDER_PATH, failures)
    node_text = read(NODE_PATH, failures)

    if manifest_text:
        manifest = ET.fromstring(manifest_text)
        services = manifest.findall("./application/service")
        service = next(
            (
                item
                for item in services
                if item.get(ANDROID + "name")
                == ".accessibility.XiaoZhiAccessibilityService"
            ),
            None,
        )
        require(service is not None, "Accessibility service metadata is missing", failures)
        if service is not None:
            require(
                service.get(ANDROID + "permission")
                == "android.permission.BIND_ACCESSIBILITY_SERVICE",
                "Accessibility service must require the system signature binding permission",
                failures,
            )
            require(
                service.get(ANDROID + "exported") == "true",
                "Accessibility service must be exported for system binding and protected by the binding permission",
                failures,
            )
            actions = {
                action.get(ANDROID + "name")
                for action in service.findall("./intent-filter/action")
            }
            require(
                "android.accessibilityservice.AccessibilityService" in actions,
                "Accessibility service intent action is missing",
                failures,
            )
            metadata = service.find("./meta-data")
            require(
                metadata is not None
                and metadata.get(ANDROID + "name")
                == "android.accessibilityservice"
                and metadata.get(ANDROID + "resource")
                == "@xml/accessibility_service_config",
                "Accessibility service config metadata is missing",
                failures,
            )

    if config_text:
        config = ET.fromstring(config_text)
        require(
            config.get(ANDROID + "canRetrieveWindowContent") == "true",
            "Accessibility config must opt in to current-window content",
            failures,
        )
        events = config.get(ANDROID + "accessibilityEventTypes", "")
        for event_type in ("typeWindowStateChanged", "typeWindowContentChanged"):
            require(event_type in events, f"Accessibility config missing {event_type}", failures)

    production_text = "\n".join(
        text
        for path in (ROOT / "app/src/main").rglob("*")
        if path.is_file() and path.suffix.lower() in {".kt", ".xml"}
        for text in [path.read_text("utf-8", errors="ignore")]
    )
    for forbidden in (
        "Settings.Secure",
        "enabled_accessibility_services",
        "accessibility_enabled",
        "WRITE_SECURE_SETTINGS",
        "executeShellCommand",
        "pm grant",
        "appops set",
    ):
        require(
            forbidden.lower() not in production_text.lower(),
            f"programmatic Accessibility enable/bypass is forbidden: {forbidden}",
            failures,
        )

    if service_text:
        require(
            "class XiaoZhiAccessibilityService : AccessibilityService()" in service_text,
            "service must be bound and instantiated only by Android AccessibilityService",
            failures,
        )
        require(
            "ScreenContextStore" in service_text and ".publish(" in service_text,
            "window changes must publish to the Task 1 ScreenContextStore",
            failures,
        )
        require(
            "override fun onDestroy()" in service_text
            and "ScreenContextStoreProvider.instance.invalidate()" in service_text,
            "service destruction must invalidate the transient screen context",
            failures,
        )

    required_snapshot_terms = (
        "node.text",
        "node.contentDescription",
        "node.className",
        "node.isClickable",
        "node.isVisibleToUser",
        "node.getBoundsInScreen",
        "windowFingerprint",
    )
    for term in required_snapshot_terms:
        require(term in builder_text, f"semantic snapshot missing {term}", failures)
    for field in ("contentDescription", "className", "clickable", "visibleBounds"):
        require(
            re.search(rf"\bval\s+{field}\s*:", node_text) is not None,
            f"ScreenNode missing transient semantic field: {field}",
            failures,
        )
    for forbidden_field in (
        "viewIdResourceName",
        "extras",
        "paneTitle",
        "tooltipText",
    ):
        require(
            forbidden_field not in builder_text,
            f"snapshot captures data outside the Task 2 allowlist: {forbidden_field}",
            failures,
        )
    require(
        "sensitiveScreenSignals" in node_text
        and "passwordFieldPresent" in builder_text
        and "inputType" in builder_text
        and "isPassword" in builder_text,
        "snapshot must carry only non-secret password/input sensitivity metadata",
        failures,
    )
    require(
        "text = if (nodeHasPasswordField) null else nodeName(node.text)" in builder_text,
        "password field text must not be retained in the transient snapshot",
        failures,
    )

    if "override fun toString" in node_text:
        node_to_string = node_text[node_text.index("override fun toString") :]
        for raw_marker in ("text=", "contentDescription=", "className=", "children=$children"):
            require(
                raw_marker not in node_to_string,
                f"ScreenNode.toString must not expose raw snapshot data: {raw_marker}",
                failures,
            )

    accessibility_text = service_text + "\n" + builder_text
    for forbidden_sink in (
        "SharedPreferences",
        "SQLite",
        "RoomDatabase",
        "FileOutputStream",
        "openFileOutput",
        "Log.",
        "println(",
    ):
        require(
            forbidden_sink not in accessibility_text,
            f"Accessibility snapshot must remain transient: {forbidden_sink}",
            failures,
        )

    if failures:
        raise SystemExit("FAIL: v0.7 Accessibility contract\n- " + "\n- ".join(failures))
    print("PASS: v0.7 user-enabled transient Accessibility snapshot contract")


if __name__ == "__main__":
    main()
