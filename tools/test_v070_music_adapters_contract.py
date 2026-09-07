from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MEDIA = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/media"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


files = {
    "NetEaseMusicAdapter.kt": "com.netease.cloudmusic",
    "QishuiMusicAdapter.kt": "com.luna.music",
    "KugouMusicAdapter.kt": "com.kugou.android",
    "QqMusicAdapter.kt": "com.tencent.qqmusic",
}

for name, package_name in files.items():
    source_path = MEDIA / "adapters" / name
    source = source_path.read_text(encoding="utf-8") if source_path.exists() else ""
    require(source, f"missing music adapter source: {name}")
    require(package_name in source, f"missing package binding in {name}")
    require("UiActionProposal" in source or "MusicSemanticAdapter" in source,
            f"{name} must produce context-bound semantic proposals")

base = MEDIA / "MusicSemanticAdapter.kt"
base_source = base.read_text(encoding="utf-8") if base.exists() else ""
require("UiActionProposal" in base_source, "shared music adapter must use UiActionProposal")
require("ScreenContext" in base_source, "shared music adapter must consume ScreenContext")
require("ContextCandidate" in base_source, "shared music adapter must return semantic candidates")

for forbidden in (
    "AccessibilityNodeFinder",
    "AccessibilityActionDriver",
    "ToolDispatcher",
    "performClick",
    "dispatchGesture",
    "sendKeys",
    "visibleBounds",
    "clickSequence",
):
    require(forbidden not in base_source, f"music adapters must not execute raw UI actions: {forbidden}")

print("PASS: v0.7 music adapter semantic and safety contract")
