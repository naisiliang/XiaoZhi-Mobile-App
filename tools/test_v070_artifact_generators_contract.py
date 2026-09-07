from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/artifacts/generators"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


required = (
    "ArtifactGenerationSupport.kt",
    "TextArtifactGenerator.kt",
    "CsvArtifactGenerator.kt",
    "ZipArtifactGenerator.kt",
)
sources = {}
for name in required:
    path = ARTIFACTS / name
    require(path.exists(), f"missing generator/validator file: {name}")
    sources[name] = path.read_text(encoding="utf-8")

combined = "\n".join(sources.values())
for marker in (
    "ArtifactGenerationResult.Completed",
    "TextArtifactValidator",
    "CsvArtifactValidator",
    "ZipArtifactValidator",
    "UTF-8",
    "CharsetDecoder",
    "ZipOutputStream",
    "ZipFile",
    "MAX_",
    "registerCompleted",
):
    require(marker in combined, f"artifact generator/validator marker missing: {marker}")

for marker in ("..", "canonical", "duplicate", "entry", "size"):
    require(marker.lower() in combined.lower(), f"ZIP safety marker missing: {marker}")

for forbidden in ("REPLACE_EXISTING", "file.exists()", "COMPLETED = true"):
    require(forbidden not in combined, f"generator must not fake/overwrite completion: {forbidden}")

print("PASS: v0.7 text/markdown/csv/zip generator and validator contract")
