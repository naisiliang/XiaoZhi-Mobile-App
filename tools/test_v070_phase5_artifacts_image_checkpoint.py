from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
GOLDEN = "324dd5a53d404490bc4a32ed1f9ce8c45671ed24"
CHECKPOINT_REPORT = ROOT / ".superpowers/sdd/2026-09-06-06-artifacts-image/task-9-report.md"

FROZEN_PATHS = (
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseCompiler.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/WakePhraseManager.kt",
    "app/src/main/java/com/lchuang/xiaozhimobile/Pinyin4jProvider.kt",
)

PREDECESSOR_GATES = (
    "tools/test_v070_artifact_core_contract.py",
    "tools/test_v070_artifact_generators_contract.py",
    "tools/test_v070_structured_artifact_contract.py",
    "tools/test_v070_ppt_pipeline_contract.py",
    "tools/test_v070_artifact_versions_contract.py",
    "tools/test_v070_image_generation_contract.py",
    "tools/test_v070_artifact_export_contract.py",
    "tools/test_v070_agent_integration_contract.py",
)

SOURCE_MARKERS = {
    "app/src/main/java/com/lchuang/xiaozhimobile/artifacts/generators/TextArtifactGenerator.kt": (
        "object TextArtifactValidator",
        "fun readUtf8",
        "fun validate",
        "CharsetDecoder",
        "Charsets.UTF_8",
    ),
    "app/src/main/java/com/lchuang/xiaozhimobile/artifacts/generators/CsvArtifactGenerator.kt": (
        "object CsvArtifactValidator",
        "fun parse",
        "fun validate",
        "MAX_ROWS",
        "MAX_COLUMNS",
    ),
    "app/src/main/java/com/lchuang/xiaozhimobile/artifacts/generators/ZipArtifactGenerator.kt": (
        "object ZipArtifactValidator",
        "ZipFile",
        "ZipArtifactPathPolicy",
        "canonicalPath",
        "duplicate ZIP entry path",
    ),
    "app/src/main/java/com/lchuang/xiaozhimobile/artifacts/generators/DocxArtifactGenerator.kt": (
        "object DocxArtifactValidator",
        "[Content_Types].xml",
        "_rels/.rels",
        "word/document.xml",
        "isValid",
    ),
    "app/src/main/java/com/lchuang/xiaozhimobile/artifacts/generators/XlsxArtifactGenerator.kt": (
        "object XlsxArtifactValidator",
        "xl/workbook.xml",
        "xl/_rels/workbook.xml.rels",
        "xl/worksheets/sheet1.xml",
        "isValid",
    ),
    "app/src/main/java/com/lchuang/xiaozhimobile/artifacts/generators/PdfArtifactGenerator.kt": (
        "object PdfArtifactValidator",
        "%PDF-",
        "startxref",
        "%%EOF",
        "isValid",
    ),
    "app/src/main/java/com/lchuang/xiaozhimobile/artifacts/ppt/PptValidator.kt": (
        "object PptValidator",
        "ppt/presentation.xml",
        "ppt/slides",
        "relationships",
        "media",
        "isValid",
    ),
    "app/src/main/java/com/lchuang/xiaozhimobile/image/ImageArtifact.kt": (
        "object ImageArtifactValidator",
        "PNG_SIGNATURE",
        "GIF87_SIGNATURE",
        "WEBP_SIGNATURE",
        "fun validate",
        "MAX_IMAGE_BYTES",
    ),
}

TEST_MARKERS = {
    "app/src/test/java/com/lchuang/xiaozhimobile/artifacts/ArtifactGeneratorsTest.kt": (
        "textGeneratorWritesActualUtf8AndCompletesOnlyAfterValidation",
        "markdownGeneratorUsesMarkdownMimeAndRealTextBytes",
        "csvGeneratorQuotesCellsAndValidatorParsesTheWrittenFile",
        "zipGeneratorRejectsTraversalAndCreatesSafeArchive",
        "validatorsRejectMalformedUtf8AndZipTraversal",
    ),
    "app/src/test/java/com/lchuang/xiaozhimobile/artifacts/StructuredArtifactGeneratorsTest.kt": (
        "docxGeneratorCreatesValidatedOpcPackage",
        "xlsxGeneratorCreatesValidatedWorkbookAndSheet",
        "pdfGeneratorCreatesHeaderCrossReferenceAndNonEmptyPages",
        "structuredValidatorsRejectPlainTextExtensionSpoofingAndCorruptPackages",
    ),
    "app/src/test/java/com/lchuang/xiaozhimobile/artifacts/PptPipelineTest.kt": (
        "rendererCreatesValidatedPptxWithSlidesRelationshipsAndMedia",
        "plannerRejectsUnsafeOrInvalidMediaAndValidatorRejectsSpoofedPptx",
    ),
    "app/src/test/java/com/lchuang/xiaozhimobile/image/ImageGenerationToolTest.kt": (
        "resolverCreatesImageIntentWithoutScreenContext",
        "resolverKeepsImageContextForEditAndRegenerate",
        "toolStoresRealPngPrivatelyAndCarriesContextForEdit",
        "providerFailureIsSanitizedAndDoesNotCreateAnArtifact",
    ),
    "app/src/test/java/com/lchuang/xiaozhimobile/artifacts/ArtifactCoreTest.kt": (
        "importCopiesExternalFileWithoutOverwritingOriginal",
        "savingAnOlderVersionDoesNotReplaceTheCurrentVersion",
        "repositoryRejectsExternalOrMismatchedArtifacts",
    ),
    "app/src/test/java/com/lchuang/xiaozhimobile/artifacts/ArtifactVersionManagerTest.kt": (
        "editCreatesNewVersionAndKeepsPreviousVersionImmutable",
        "restoreCopiesAValidPriorVersionIntoANewCurrentVersion",
        "restoreRefusesAHistoryFileWhoseDigestNoLongerMatches",
    ),
}

GRADLE_TEST_CLASSES = (
    "com.lchuang.xiaozhimobile.artifacts.ArtifactCoreTest",
    "com.lchuang.xiaozhimobile.artifacts.ArtifactGeneratorsTest",
    "com.lchuang.xiaozhimobile.artifacts.StructuredArtifactGeneratorsTest",
    "com.lchuang.xiaozhimobile.artifacts.PptPipelineTest",
    "com.lchuang.xiaozhimobile.artifacts.ArtifactVersionManagerTest",
    "com.lchuang.xiaozhimobile.artifacts.ArtifactExportCoordinatorTest",
    "com.lchuang.xiaozhimobile.image.ImageGenerationToolTest",
)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def read(relative_path):
    path = ROOT / relative_path
    require(path.is_file(), f"missing checkpoint file: {relative_path}")
    return path.read_text(encoding="utf-8")


def verify_source_markers():
    for relative_path, markers in SOURCE_MARKERS.items():
        source = read(relative_path)
        for marker in markers:
            require(marker in source, f"real validator marker missing: {relative_path}: {marker}")

    workspace = read("app/src/main/java/com/lchuang/xiaozhimobile/artifacts/ArtifactWorkspace.kt")
    repository = read("app/src/main/java/com/lchuang/xiaozhimobile/artifacts/ArtifactRepository.kt")
    require("allocateVersionFile" in workspace, "workspace must allocate versioned private files")
    require("moveIntoWorkspace" in workspace, "workspace must move staged files into private storage")
    require("FileInputStream" in repository, "repository must copy external input before registering it")
    require("copyBounded" in repository, "external import must be bounded before registration")
    for forbidden in ("REPLACE_EXISTING", "reset --hard"):
        require(forbidden not in workspace + repository, f"original-file protection marker forbidden: {forbidden}")


def verify_test_markers():
    for relative_path, markers in TEST_MARKERS.items():
        source = read(relative_path)
        for marker in markers:
            require(marker in source, f"real structure/original protection test missing: {relative_path}: {marker}")

    for class_name in GRADLE_TEST_CLASSES:
        package_path, test_name = class_name.rsplit(".", 1)
        relative_path = "app/src/test/java/" + package_path.replace(".", "/") + "/" + test_name + ".kt"
        require((ROOT / relative_path).is_file(), f"Gradle test class source missing: {class_name}")


def verify_frozen_sources():
    subprocess.run(["git", "cat-file", "-e", f"{GOLDEN}^{{commit}}"], cwd=ROOT, check=True)
    for relative_path in FROZEN_PATHS:
        result = subprocess.run(
            ["git", "diff", "--quiet", GOLDEN, "--", relative_path],
            cwd=ROOT,
            check=False,
        )
        require(result.returncode == 0, f"Level-1 frozen source changed: {relative_path}")


def run_predecessor_gates():
    for relative_path in PREDECESSOR_GATES:
        print(f"RUN: {relative_path}", flush=True)
        subprocess.run([sys.executable, "-B", "-X", "utf8", relative_path], cwd=ROOT, check=True)


def verify_checkpoint_report():
    report = read(str(CHECKPOINT_REPORT.relative_to(ROOT)).replace("\\", "/"))
    for marker in (
        "PHASE5_ARTIFACTS_IMAGE_CHECKPOINT_READY",
        "Task 9: Phase 5 checkpoint",
        "DEVICE_GATE_PENDING",
        "Golden/P0 regression",
    ):
        require(marker in report, f"checkpoint report marker missing: {marker}")


def main():
    verify_checkpoint_report()
    verify_source_markers()
    verify_test_markers()
    verify_frozen_sources()
    run_predecessor_gates()
    print("PASS: v0.7 Phase 5 artifacts/image checkpoint contract", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (AssertionError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"FAIL: {error}") from error
