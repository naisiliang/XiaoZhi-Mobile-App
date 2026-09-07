from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
INTENT = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/media/MusicIntent.kt"
RESOLVER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/media/MusicIntentResolver.kt"
ADAPTER = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/media/MusicAppAdapter.kt"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


intent = INTENT.read_text(encoding="utf-8") if INTENT.exists() else ""
resolver = RESOLVER.read_text(encoding="utf-8") if RESOLVER.exists() else ""
adapter = ADAPTER.read_text(encoding="utf-8") if ADAPTER.exists() else ""

for marker in (
    "enum class MusicIntentType",
    "OPEN",
    "PLAY",
    "PAUSE",
    "PREVIOUS",
    "NEXT",
    "SEARCH",
    "SELECT_FIRST",
    "SELECT_SECOND",
    "data class MusicSearchQuery",
):
    require(marker in intent, f"missing typed music intent marker: {marker}")

require("fun resolve(command: String): MusicIntentResolution" in resolver,
        "music intent resolver must expose a typed resolve entry point")
for marker in (
    "MusicIntentResolution.NeedsClarification",
    "MusicIntentResolution.Unsupported",
    "MusicIntentResolution.Empty",
    "SONG_AND_ARTIST",
    "ARTIST_AND_SONG",
):
    require(marker in resolver, f"missing music resolver safety/parse marker: {marker}")

for marker in (
    "interface MusicAppAdapter",
    "fun propose(",
    "data class Proposed",
    "UiActionProposal",
):
    require(marker in adapter, f"missing generic music adapter contract: {marker}")

for forbidden in (
    "AccessibilityNodeFinder",
    "AccessibilityActionDriver",
    "ToolDispatcher",
    "performClick",
    "dispatchGesture",
    "clickSequence",
):
    require(forbidden not in adapter, f"adapter must not execute raw UI actions: {forbidden}")

print("PASS: v0.7 typed music intent and generic adapter contract")
