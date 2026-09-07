from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
GENERATORS = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/artifacts/generators"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


required = (
    "ArtifactGenerationSupport.kt",
    "StructuredArtifactSupport.kt",
    "DocxArtifactGenerator.kt",
    "XlsxArtifactGenerator.kt",
    "PdfArtifactGenerator.kt",
)
sources = {}
for name in required:
    path = GENERATORS / name
    require(path.exists(), f"missing structured artifact file: {name}")
    sources[name] = path.read_text(encoding="utf-8")

combined = "\n".join(sources.values())
for marker in (
    "generateValidatedArtifact",
    "registerCompleted",
    "DocxArtifactValidator",
    "XlsxArtifactValidator",
    "PdfArtifactValidator",
    "ZipFile",
    "ZipOutputStream",
    "[Content_Types].xml",
    "_rels/.rels",
    "document.xml",
    "workbook.xml",
    "sheet1.xml",
    "%PDF-",
    "startxref",
    "MAX_",
    "isValid",
):
    require(marker in combined, f"structured artifact marker missing: {marker}")

for forbidden in ("REPLACE_EXISTING", "file.exists()", "COMPLETED = true"):
    require(forbidden not in combined, f"structured artifact must not fake/overwrite completion: {forbidden}")

print("PASS: v0.7 DOCX/XLSX/PDF generator and validator contract")
