from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import tempfile
from collections import Counter
from pathlib import Path
from zipfile import BadZipFile, ZipFile


EXPECTED_RC_APK_NAME = "XiaoZhi-Mobile-v0.7.0-rc-debug.apk"
EXPECTED_PACKAGE_NAME = "com.lchuang.xiaozhimobile"
# The recovery branch deliberately retains the v0.6.5 package metadata while
# the external artifact name identifies the v0.7.0 RC train.
EXPECTED_VERSION_NAME = "0.6.5"
ACCESSIBILITY_SERVICE_CLASS = "XiaoZhiAccessibilityService"
ACCESSIBILITY_SERVICE_ACTION = "android.accessibilityservice.AccessibilityService"
ACCESSIBILITY_BIND_PERMISSION = "android.permission.BIND_ACCESSIBILITY_SERVICE"

REQUIRED_ENTRIES = (
    "AndroidManifest.xml",
    "classes.dex",
    "lib/arm64-v8a/libsherpa-onnx-jni.so",
    "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/encoder-epoch-13-avg-2-chunk-16-left-64.onnx",
    "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/decoder-epoch-13-avg-2-chunk-16-left-64.onnx",
    "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/joiner-epoch-13-avg-2-chunk-16-left-64.onnx",
    "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/tokens.txt",
    "assets/sherpa-onnx-paraformer-zh-small-2024-03-09/model.int8.onnx",
    "assets/sherpa-onnx-paraformer-zh-small-2024-03-09/tokens.txt",
)


class ArtifactValidationError(ValueError):
    pass


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _find_aapt2(explicit: Path | None) -> Path | None:
    candidates: list[Path] = []
    if explicit is not None:
        candidates.append(explicit)
    for variable in ("AAPT2", "AAPT2_PATH"):
        value = os.environ.get(variable)
        if value:
            candidates.append(Path(value))
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(variable)
        if not value:
            continue
        build_tools = Path(value) / "build-tools"
        if build_tools.is_dir():
            for version in sorted(build_tools.iterdir(), reverse=True):
                candidates.extend((version / "aapt2", version / "aapt2.exe"))

    path_from_path = shutil.which("aapt2") or shutil.which("aapt2.exe")
    if path_from_path:
        candidates.append(Path(path_from_path))
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    return None


def _run_aapt2(aapt2: Path, command: list[str], apk_path: Path) -> str:
    try:
        result = subprocess.run(
            [str(aapt2), *command, str(apk_path)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=45,
            check=False,
        )
    except (OSError, subprocess.SubprocessError) as error:
        raise ArtifactValidationError(f"aapt2 manifest inspection failed: {error}") from error
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise ArtifactValidationError(f"aapt2 manifest inspection failed: {detail}")
    return result.stdout


def _validate_text_manifest(manifest: bytes) -> dict[str, str] | None:
    text = manifest.decode("utf-8", errors="ignore")
    package_match = re.search(r"\bpackage\s*=\s*['\"]([^'\"]+)['\"]", text)
    version_match = re.search(r"\bversionName\s*=\s*['\"]([^'\"]+)['\"]", text)
    if not package_match or not version_match:
        return None
    return {
        "package": package_match.group(1),
        "versionName": version_match.group(1),
        "manifestText": text,
    }


def _validate_manifest(manifest: bytes, apk_path: Path, aapt2_path: Path | None) -> dict[str, str]:
    metadata = _validate_text_manifest(manifest)
    if metadata is None:
        aapt2 = _find_aapt2(aapt2_path)
        if aapt2 is None:
            raise ArtifactValidationError(
                "binary AndroidManifest.xml requires aapt2; pass --aapt2 or configure ANDROID_HOME"
            )
        badging = _run_aapt2(aapt2, ["dump", "badging"], apk_path)
        package_match = re.search(
            r"package:\s+name='([^']+)'[^\n]*versionName='([^']*)'",
            badging,
        )
        if not package_match:
            raise ArtifactValidationError("aapt2 badging did not expose package/versionName")
        tree = _run_aapt2(
            aapt2,
            ["dump", "xmltree", "--file", "AndroidManifest.xml"],
            apk_path,
        )
        metadata = {
            "package": package_match.group(1),
            "versionName": package_match.group(2),
            "manifestText": tree,
        }

    if metadata["package"] != EXPECTED_PACKAGE_NAME:
        raise ArtifactValidationError(
            f"unexpected package name: expected {EXPECTED_PACKAGE_NAME}, got {metadata['package']}"
        )
    if metadata["versionName"] != EXPECTED_VERSION_NAME:
        raise ArtifactValidationError(
            f"unexpected versionName: expected {EXPECTED_VERSION_NAME}, got {metadata['versionName']}"
        )
    manifest_text = metadata["manifestText"]
    for marker in (
        ACCESSIBILITY_SERVICE_CLASS,
        ACCESSIBILITY_SERVICE_ACTION,
        ACCESSIBILITY_BIND_PERMISSION,
    ):
        if marker not in manifest_text:
            raise ArtifactValidationError(f"APK missing AccessibilityService declaration marker: {marker}")
    return {
        "package": metadata["package"],
        "versionName": metadata["versionName"],
        "accessibilityService": ACCESSIBILITY_SERVICE_CLASS,
    }


def validate_apk(apk_path: Path, *, aapt2: Path | None = None) -> dict[str, object]:
    if apk_path.name != EXPECTED_RC_APK_NAME:
        raise ArtifactValidationError(
            f"unexpected APK filename: expected {EXPECTED_RC_APK_NAME}, got {apk_path.name}"
        )
    if not apk_path.is_file():
        raise ArtifactValidationError(f"APK file is missing: {apk_path}")

    size_bytes = apk_path.stat().st_size
    if size_bytes <= 0:
        raise ArtifactValidationError("APK byte size must be nonzero")

    try:
        with ZipFile(apk_path) as archive:
            bad_entry = archive.testzip()
            if bad_entry is not None:
                raise ArtifactValidationError(f"APK ZIP integrity failure: {bad_entry}")
            names = archive.namelist()
            duplicates = sorted(name for name, count in Counter(names).items() if count > 1)
            if duplicates:
                raise ArtifactValidationError(
                    "APK ZIP contains duplicate entries: " + ", ".join(duplicates)
                )
            name_set = set(names)
            for required in REQUIRED_ENTRIES:
                if required not in name_set:
                    raise ArtifactValidationError(f"APK missing required entry: {required}")
                if archive.getinfo(required).file_size <= 0:
                    raise ArtifactValidationError(f"APK required entry is empty: {required}")
            if not any(name.startswith("lib/arm64-v8a/") for name in names):
                raise ArtifactValidationError("APK missing arm64-v8a library directory")
            manifest = archive.read("AndroidManifest.xml")
            manifest_report = _validate_manifest(manifest, apk_path, aapt2)
    except ArtifactValidationError:
        raise
    except (BadZipFile, KeyError, OSError, RuntimeError, ValueError) as error:
        raise ArtifactValidationError(f"APK ZIP validation failed: {error}") from error

    return {
        "filename": apk_path.name,
        "size_bytes": size_bytes,
        "sha256": _sha256(apk_path),
        "manifest": manifest_report,
        "required_entries": list(REQUIRED_ENTRIES),
    }


def _check_expected(report: dict[str, object], expected_size: int | None, expected_sha256: str | None) -> None:
    if expected_size is not None and report["size_bytes"] != expected_size:
        raise ArtifactValidationError(
            f"APK size mismatch: expected {expected_size}, got {report['size_bytes']}"
        )
    if expected_sha256 is not None and str(report["sha256"]).lower() != expected_sha256.lower():
        raise ArtifactValidationError(
            f"APK SHA-256 mismatch: expected {expected_sha256}, got {report['sha256']}"
        )


def validate_artifact_directory(
    artifact_dir: Path,
    *,
    aapt2: Path | None = None,
    expected_size: int | None = None,
    expected_sha256: str | None = None,
) -> dict[str, object]:
    if not artifact_dir.is_dir():
        raise ArtifactValidationError(f"downloaded artifact directory is missing: {artifact_dir}")
    apk_files = sorted(
        path for path in artifact_dir.iterdir() if path.is_file() and path.suffix.lower() == ".apk"
    )
    expected = artifact_dir / EXPECTED_RC_APK_NAME
    if len(apk_files) != 1 or apk_files[0] != expected:
        actual = ", ".join(path.name for path in apk_files) or "none"
        raise ArtifactValidationError(
            f"downloaded artifact must contain exactly {EXPECTED_RC_APK_NAME}; found {actual}"
        )
    report = validate_apk(expected, aapt2=aapt2)
    _check_expected(report, expected_size, expected_sha256)
    return report


def validate_artifact_zip(artifact_zip_path: Path, *, aapt2: Path | None = None) -> dict[str, object]:
    if not artifact_zip_path.is_file():
        raise ArtifactValidationError(f"downloaded artifact ZIP is missing: {artifact_zip_path}")
    try:
        with ZipFile(artifact_zip_path) as archive:
            bad_entry = archive.testzip()
            if bad_entry is not None:
                raise ArtifactValidationError(f"artifact ZIP integrity failure: {bad_entry}")
            names = archive.namelist()
            duplicates = sorted(name for name, count in Counter(names).items() if count > 1)
            if duplicates:
                raise ArtifactValidationError(
                    "artifact ZIP contains duplicate entries: " + ", ".join(duplicates)
                )
            if names != [EXPECTED_RC_APK_NAME]:
                actual = ", ".join(names) or "none"
                raise ArtifactValidationError(
                    f"downloaded artifact ZIP must contain exactly {EXPECTED_RC_APK_NAME}; found {actual}"
                )
            with tempfile.TemporaryDirectory() as temporary_directory:
                contained_apk = Path(temporary_directory) / EXPECTED_RC_APK_NAME
                with archive.open(EXPECTED_RC_APK_NAME) as source, contained_apk.open("wb") as target:
                    shutil.copyfileobj(source, target)
                apk_report = validate_apk(contained_apk, aapt2=aapt2)
    except ArtifactValidationError:
        raise
    except (BadZipFile, KeyError, OSError, RuntimeError, ValueError, EOFError) as error:
        raise ArtifactValidationError(f"artifact ZIP validation failed: {error}") from error

    return {
        "artifact_zip_filename": artifact_zip_path.name,
        "artifact_zip_size_bytes": artifact_zip_path.stat().st_size,
        "artifact_zip_sha256": _sha256(artifact_zip_path),
        "apk": apk_report,
    }


def write_report(report_path: Path, report: dict[str, object]) -> None:
    import json

    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate the v0.7.0 RC APK")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--apk", type=Path)
    source.add_argument("--artifact-dir", type=Path)
    source.add_argument("--artifact-zip", type=Path)
    parser.add_argument("--aapt2", type=Path)
    parser.add_argument("--expected-size", type=int)
    parser.add_argument("--expected-sha256")
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    try:
        if args.apk is not None:
            report: dict[str, object] = validate_apk(args.apk, aapt2=args.aapt2)
            _check_expected(report, args.expected_size, args.expected_sha256)
        elif args.artifact_dir is not None:
            report = validate_artifact_directory(
                args.artifact_dir,
                aapt2=args.aapt2,
                expected_size=args.expected_size,
                expected_sha256=args.expected_sha256,
            )
        else:
            report = validate_artifact_zip(args.artifact_zip, aapt2=args.aapt2)
        if args.report is not None:
            write_report(args.report, report)
    except (ArtifactValidationError, OSError) as error:
        print(f"FAIL: {error}")
        return 1

    if "apk" in report:
        apk_report = report["apk"]
        print(f"PASS: artifact ZIP filename={report['artifact_zip_filename']}")
        print(f"PASS: artifact ZIP size_bytes={report['artifact_zip_size_bytes']}")
        print(f"PASS: artifact ZIP sha256={report['artifact_zip_sha256']}")
    else:
        apk_report = report
    print(f"PASS: APK filename={apk_report['filename']}")
    print(f"PASS: APK size_bytes={apk_report['size_bytes']}")
    print(f"PASS: APK sha256={apk_report['sha256']}")
    print(f"PASS: package={apk_report['manifest']['package']}")
    print(f"PASS: versionName={apk_report['manifest']['versionName']}")
    print(f"PASS: validated {len(REQUIRED_ENTRIES)} required APK entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
