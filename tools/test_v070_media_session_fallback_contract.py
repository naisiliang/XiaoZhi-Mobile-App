from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FALLBACK = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/media/MediaSessionFallback.kt"
PHONE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/PhoneController.kt"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


fallback = FALLBACK.read_text(encoding="utf-8") if FALLBACK.exists() else ""
phone = PHONE.read_text(encoding="utf-8")

for marker in (
    "data class ActiveMediaSession",
    "enum class MediaKeyAction",
    "class MediaSessionFallback",
    "MediaSessionFallbackCode.DELEGATE_TO_ADAPTER",
    "MediaSessionFallbackCode.NO_ACTIVE_SESSION",
    "MediaSessionFallbackCode.UNSUPPORTED_INTENT",
    "MediaSessionFallbackCode.DISPATCH_FAILED",
    "fun execute(",
):
    require(marker in fallback, f"missing media-session fallback contract: {marker}")

require("fun mediaKeyDispatcher()" in phone,
        "PhoneController must expose the existing Media Key path as fallback dispatcher")
for marker in (
    "KEYCODE_MEDIA_PLAY",
    "KEYCODE_MEDIA_PAUSE",
    "KEYCODE_MEDIA_NEXT",
    "KEYCODE_MEDIA_PREVIOUS",
    "fun mediaPlay()",
    "fun mediaPause()",
    "fun mediaNext()",
    "fun mediaPrevious()",
):
    require(marker in phone, f"Golden media key behavior must remain present: {marker}")

for forbidden in (
    "AccessibilityNodeFinder",
    "AccessibilityActionDriver",
    "ToolDispatcher",
    "performClick",
    "dispatchGesture",
):
    require(forbidden not in fallback, f"media fallback must not execute UI actions: {forbidden}")

print("PASS: v0.7 media-session fallback and Golden Media Key contract")
