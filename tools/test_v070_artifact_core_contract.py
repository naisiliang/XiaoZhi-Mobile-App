from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "app/src/main/java/com/lchuang/xiaozhimobile/artifacts"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


required_files = (
    "Artifact.kt",
    "ArtifactWorkspace.kt",
    "ArtifactRepository.kt",
    "ArtifactVersion.kt",
)
sources = {}
for name in required_files:
    path = ARTIFACTS / name
    require(path.exists(), f"missing artifact core file: {name}")
    sources[name] = path.read_text(encoding="utf-8")

combined = "\n".join(sources.values())
for marker in (
    "artifactId",
    "sessionId",
    "mimeType",
    "displayName",
    "privatePath",
    "size",
    "sha256",
    "createdAt",
    "sourceAgent",
    "version",
):
    require(marker in combined, f"artifact metadata/lineage marker missing: {marker}")

for marker in (
    "filesDir",
    "isAbsolute",
    "canonicalFile",
    "createTempFile",
    "ATOMIC_MOVE",
    "File.separator",
):
    require(marker in combined, f"private workspace safety marker missing: {marker}")

for marker in (
    "SQLiteOpenHelper",
    "CREATE TABLE",
    "session_id",
    "source_agent",
    "sha256",
    "saveArtifactWithVersion",
    "setForeignKeyConstraintsEnabled",
    "insertOrThrow",
):
    require(marker in sources["ArtifactRepository.kt"], f"artifact metadata DB marker missing: {marker}")

for marker in ("MessageDigest", "SHA-256", "maxBytes", "copyBounded"):
    require(marker in combined, f"artifact size/digest bound marker missing: {marker}")

for forbidden in ("REPLACE_EXISTING", "DexClassLoader", "ProcessBuilder", "Runtime.getRuntime"):
    require(forbidden not in combined, f"artifact core must not overwrite or execute external content: {forbidden}")

print("PASS: v0.7 artifact core private workspace and metadata contract")
