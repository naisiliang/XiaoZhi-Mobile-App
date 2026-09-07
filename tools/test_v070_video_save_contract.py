from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VIDEO = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/video"
RESOLVER = VIDEO / "VideoIntentResolver.kt"
ADAPTER = VIDEO / "VideoAppAdapter.kt"
COORDINATOR = VIDEO / "VideoSaveCoordinator.kt"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


resolver = RESOLVER.read_text(encoding="utf-8") if RESOLVER.exists() else ""
adapter = ADAPTER.read_text(encoding="utf-8") if ADAPTER.exists() else ""
coordinator = COORDINATOR.read_text(encoding="utf-8") if COORDINATOR.exists() else ""
resolver_lower = resolver.lower()

for marker in (
    "SAVE_CURRENT",
    "SHARE_CURRENT",
    "VideoIntentResolution.Blocked",
    "DRM",
    "MITM",
    "paywall",
):
    require(marker.lower() in resolver_lower, f"missing video intent safety marker: {marker}")

for marker in (
    "interface VideoAppAdapter",
    "GenericVideoAppAdapter",
    "UiActionProposal",
    "ContextTargetKind.VIDEO",
    "VideoSaveRoute.APP_NATIVE_SAVE",
):
    require(marker in adapter, f"missing video semantic adapter contract: {marker}")

for marker in (
    "enum class VideoSaveRoute",
    "APP_NATIVE_SAVE",
    "ANDROID_SHARE",
    "PUBLIC_URL_TO_MEDIA_STORE",
    "BLOCKED",
    "class VideoSaveCoordinator",
    "VideoSaveCode.INVALID_PUBLIC_URL",
    "VideoSaveCode.INVALID_SHARE_URI",
    "https",
    "content",
):
    require(marker in coordinator, f"missing safe video save contract: {marker}")

for forbidden in (
    "AccessibilityNodeFinder",
    "ToolDispatcher",
    "performClick",
    "dispatchGesture",
    "MITM_PROXY",
    "DRM_BYPASS",
    "PAYWALL_BYPASS",
    "hiddenFile",
    "scrapePrivate",
    "file://",
):
    require(forbidden not in coordinator, f"video save coordinator must not implement bypasses: {forbidden}")

print("PASS: v0.7 safe video save/share contract")
