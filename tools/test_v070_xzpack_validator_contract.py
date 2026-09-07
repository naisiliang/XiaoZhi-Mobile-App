from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/extensions"
MANIFEST = EXTENSIONS / "ExtensionManifest.kt"
PACKAGE = EXTENSIONS / "ExtensionPackage.kt"
VALIDATOR = EXTENSIONS / "XzPackValidator.kt"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


manifest = MANIFEST.read_text(encoding="utf-8") if MANIFEST.exists() else ""
package = PACKAGE.read_text(encoding="utf-8") if PACKAGE.exists() else ""
validator = VALIDATOR.read_text(encoding="utf-8") if VALIDATOR.exists() else ""

for marker in (
    "data class ExtensionManifest",
    "ExtensionPermission",
    "UNKNOWN_PERMISSION",
    "INVALID_VERSION",
    "files",
    "duplicate object key",
):
    require(marker in manifest, f"missing xzpack manifest contract: {marker}")

for marker in (
    "data class ExtensionPackageEntry",
    "data class ExtensionPackage",
    "sha256",
):
    require(marker in package, f"missing xzpack package metadata contract: {marker}")

for marker in (
    "ZipInputStream",
    "PATH_TRAVERSAL",
    "DISALLOWED_FILE_TYPE",
    "DUPLICATE_ENTRY",
    "MANIFEST_MISSING",
    "SHA_MISMATCH",
    "CountingInputStream",
    "MessageDigest.getInstance(\"SHA-256\")",
):
    require(marker in validator, f"missing streaming xzpack validator contract: {marker}")

for forbidden in (
    "ZipFile",
    "DexClassLoader",
    "PathClassLoader",
    "ProcessBuilder",
    "Runtime.getRuntime",
    "java.lang.Process",
    "FileOutputStream",
    "extractTo",
):
    require(forbidden not in validator, f"xzpack validator must not execute or extract payloads: {forbidden}")

print("PASS: v0.7 xzpack validator contract")
