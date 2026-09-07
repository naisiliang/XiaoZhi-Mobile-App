from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
IMAGE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/image"
CONVERSATION = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/conversation"
RESOURCES = ROOT / "app/src/main/res"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


required = (
    "ImageIntentResolver.kt",
    "ImageGenerationTool.kt",
    "ImageArtifact.kt",
)
sources = {}
for name in required:
    path = IMAGE / name
    require(path.exists(), f"missing image generation source: {name}")
    sources[name] = path.read_text(encoding="utf-8")

combined = "\n".join(sources.values())
for marker in (
    "ImageIntentResolver",
    "ImageGenerationTool",
    "ImageArtifact",
    "ProviderCapability.IMAGE_GENERATION",
    "supports",
    "sourceImageBytes",
    "sanitize",
    "generateValidatedArtifact",
    "ImageArtifactValidator",
):
    require(marker in combined, f"image generation marker missing: {marker}")

for forbidden in (
    "ScreenContext",
    "ScreenVision",
    "MediaProjection",
    "captureScreen",
    "data:image/png;base64",
):
    require(forbidden not in combined, f"image generation must not receive screen context or embed image data: {forbidden}")

card = CONVERSATION / "ArtifactResultCard.kt"
adapter = CONVERSATION / "ConversationAdapter.kt"
layout = RESOURCES / "layout/item_artifact_result.xml"
for path in (card, adapter, layout):
    require(path.exists(), f"missing image conversation card integration: {path.name}")
for path, markers in (
    (card, ("fromImageArtifact", "REGENERATE")),
    (adapter, ("ArtifactCardAction.REGENERATE", "artifact_regenerate")),
    (layout, ("artifact_regenerate",)),
):
    source = path.read_text(encoding="utf-8")
    for marker in markers:
        require(marker in source, f"image result card marker missing: {marker}")

print("PASS: v0.7 image generation capability/context separation contract")
