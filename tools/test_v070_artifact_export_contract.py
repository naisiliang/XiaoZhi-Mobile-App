from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/artifacts"
IMAGE = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/image"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
XML = ROOT / "app/src/main/res/xml/file_paths.xml"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


artifact = ARTIFACTS / "ArtifactExportCoordinator.kt"
image = IMAGE / "ImageExportCoordinator.kt"
for path in (artifact, image):
    require(path.exists(), f"missing artifact export source: {path.name}")

combined = artifact.read_text(encoding="utf-8") + "\n" + image.read_text(encoding="utf-8")
for marker in (
    "ArtifactExportCoordinator",
    "privateDefault",
    "ACTION_CREATE_DOCUMENT",
    "MediaStore.Images.Media",
    "ACTION_VIEW",
    "ACTION_SEND",
    "FileProvider",
    "PERMISSION_REQUIRED",
):
    require(marker in combined, f"artifact export marker missing: {marker}")

manifest = MANIFEST.read_text(encoding="utf-8")
require("androidx.core.content.FileProvider" in manifest, "manifest must expose a FileProvider for private sharing")
require("@xml/file_paths" in manifest, "FileProvider must use the restricted artifact paths resource")
paths = XML.read_text(encoding="utf-8")
require('path="artifacts/"' in paths, "FileProvider must whitelist only private artifact files")

for forbidden in (
    "WRITE_EXTERNAL_STORAGE",
    "MANAGE_EXTERNAL_STORAGE",
    "requestPermissions",
):
    require(forbidden not in combined + "\n" + manifest, f"export must not request broad storage access: {forbidden}")

print("PASS: v0.7 artifact/image explicit SAF MediaStore open/share contract")
