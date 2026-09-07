from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EXTENSIONS = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/extensions"
REPOSITORY = EXTENSIONS / "ExtensionRepository.kt"
MANAGER = EXTENSIONS / "PluginManager.kt"


def require(condition, message):
    if not condition:
        raise SystemExit(message)


repository = REPOSITORY.read_text(encoding="utf-8") if REPOSITORY.exists() else ""
manager = MANAGER.read_text(encoding="utf-8") if MANAGER.exists() else ""

for marker in (
    "context.filesDir",
    "File.createTempFile",
    "MAX_COMPRESSED_PACKAGE_BYTES",
    "replaceAtomically",
    "state.properties",
    "package.xzpack",
):
    require(marker in repository, f"missing app-private extension repository contract: {marker}")

for marker in (
    "importPackage(input: InputStream)",
    "NeedsPermissionConfirmation",
    "confirmedPermissions",
    "addedPermissions",
    "runtimeRegistry.register",
    "runtimeRegistry.unregister",
    "reloadStoredExtensions",
):
    require(marker in manager, f"missing guarded plugin lifecycle contract: {marker}")

for forbidden in (
    "DexClassLoader",
    "PathClassLoader",
    "Class.forName",
    "ProcessBuilder",
    "Runtime.getRuntime",
    "loadLibrary",
    "execute(" ,
    "Shell",
):
    require(forbidden not in repository and forbidden not in manager,
            f"extension manager must not execute arbitrary package code: {forbidden}")

print("PASS: v0.7 plugin manager private storage and confirmation contract")
