from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DETECTOR = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/screen/SensitiveScreenDetector.kt").read_text("utf-8")
NODE = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/screen/ScreenNode.kt").read_text("utf-8")
BUILDER = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/accessibility/AccessibilitySnapshotBuilder.kt").read_text("utf-8")
EXECUTOR = (ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/accessibility/GenericAccessibilityExecutor.kt").read_text("utf-8")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


require(
    "sensitiveScreenSignals" in NODE,
    "accessibility snapshots must retain non-secret sensitivity metadata",
)
require(
    "isPassword" in BUILDER and "inputType" in BUILDER,
    "snapshot builder must collect password and input-type signals",
)
require(
    "sensitiveScreenSignals" in EXECUTOR,
    "generic accessibility execution must pass snapshot sensitivity signals",
)
require(
    "UNKNOWN_HIGH_RISK" in DETECTOR and "isGenericAccessibilityLabel" in DETECTOR,
    "unknown opaque screens must fail closed after generic metadata is filtered",
)
require(
    "isImplementationAccessibilityLabel" in DETECTOR and "label.contains('.')" in DETECTOR,
    "implementation class names copied into role must not become semantic labels",
)

print("PASS: Task 8 accessibility sensitivity contract")
