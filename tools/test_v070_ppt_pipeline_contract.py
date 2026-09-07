from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PPT = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/artifacts/ppt"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


required = (
    "PresentationRequest.kt",
    "SlidePlan.kt",
    "PptPlanner.kt",
    "PptRenderer.kt",
    "PptValidator.kt",
)
sources = {}
for name in required:
    path = PPT / name
    require(path.exists(), f"missing PPT pipeline file: {name}")
    sources[name] = path.read_text(encoding="utf-8")

combined = "\n".join(sources.values())
for marker in (
    "PresentationRequest",
    "SlidePlan",
    "PptPlanner",
    "PptRenderer",
    "PptValidator",
    "generateValidatedArtifact",
    "ArtifactGenerationResult",
    "ZipFile",
    "ZipOutputStream",
    "[Content_Types].xml",
    "ppt/presentation.xml",
    "slides",
    "relationships",
    "media",
    "MAX_",
    "isValid",
):
    require(marker in combined, f"PPT pipeline marker missing: {marker}")

for forbidden in ("REPLACE_EXISTING", "file.exists()", "COMPLETED = true"):
    require(forbidden not in combined, f"PPT pipeline must not fake/overwrite completion: {forbidden}")

print("PASS: v0.7 validated PPT structured pipeline contract")
