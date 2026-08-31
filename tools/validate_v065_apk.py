from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import tempfile
from pathlib import Path
from zipfile import BadZipFile, ZipFile


EXPECTED_APK_NAME = "XiaoZhi-Mobile-v0.6.5-debug.apk"
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


def validate_apk(apk_path: Path) -> dict[str, object]:
    if apk_path.name != EXPECTED_APK_NAME:
        raise ArtifactValidationError(
            f"unexpected APK filename: expected {EXPECTED_APK_NAME}, got {apk_path.name}"
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
            duplicates = sorted({name for name in names if names.count(name) > 1})
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
    except ArtifactValidationError:
        raise
    except (BadZipFile, OSError, RuntimeError, ValueError) as error:
        raise ArtifactValidationError(f"APK ZIP validation failed: {error}") from error

    return {
        "filename": apk_path.name,
        "size_bytes": size_bytes,
        "sha256": _sha256(apk_path),
        "required_entries": list(REQUIRED_ENTRIES),
    }


def validate_artifact_directory(artifact_dir: Path) -> dict[str, object]:
    if not artifact_dir.is_dir():
        raise ArtifactValidationError(f"downloaded artifact directory is missing: {artifact_dir}")
    apk_files = sorted(
        path for path in artifact_dir.iterdir() if path.is_file() and path.suffix.lower() == ".apk"
    )
    expected = artifact_dir / EXPECTED_APK_NAME
    if len(apk_files) != 1 or apk_files[0] != expected:
        actual = ", ".join(path.name for path in apk_files) or "none"
        raise ArtifactValidationError(
            f"downloaded artifact must contain exactly {EXPECTED_APK_NAME}; found {actual}"
        )
    return validate_apk(expected)


def validate_artifact_zip(artifact_zip_path: Path) -> dict[str, object]:
    if not artifact_zip_path.is_file():
        raise ArtifactValidationError(f"downloaded artifact ZIP is missing: {artifact_zip_path}")

    try:
        with ZipFile(artifact_zip_path) as archive:
            bad_entry = archive.testzip()
            if bad_entry is not None:
                raise ArtifactValidationError(f"artifact ZIP integrity failure: {bad_entry}")
            names = archive.namelist()
            duplicates = sorted({name for name in names if names.count(name) > 1})
            if duplicates:
                raise ArtifactValidationError(
                    "artifact ZIP contains duplicate entries: " + ", ".join(duplicates)
                )
            if names != [EXPECTED_APK_NAME]:
                actual = ", ".join(names) or "none"
                raise ArtifactValidationError(
                    f"downloaded artifact ZIP must contain exactly {EXPECTED_APK_NAME}; found {actual}"
                )

            with tempfile.TemporaryDirectory() as temporary_directory:
                contained_apk = Path(temporary_directory) / EXPECTED_APK_NAME
                with archive.open(EXPECTED_APK_NAME) as source, contained_apk.open("wb") as target:
                    shutil.copyfileobj(source, target)
                apk_report = validate_apk(contained_apk)
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


def require_matching_apk_reports(
    contained_report: dict[str, object], direct_report: dict[str, object]
) -> None:
    mismatched_fields = [
        field
        for field in ("filename", "size_bytes", "sha256")
        if contained_report[field] != direct_report[field]
    ]
    if mismatched_fields:
        raise ArtifactValidationError(
            "artifact ZIP contained APK and direct APK metadata mismatch: "
            + ", ".join(mismatched_fields)
        )


def write_report(report_path: Path, report: dict[str, object]) -> None:
    report_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Validate the downloaded v0.6.5 APK artifact")
    parser.add_argument("--artifact-dir", type=Path)
    parser.add_argument("--artifact-zip", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    if args.artifact_dir is None and args.artifact_zip is None:
        parser.error("one of --artifact-dir or --artifact-zip is required")
    try:
        reports: dict[str, object] = {}
        if args.artifact_zip is not None:
            reports["artifact_zip"] = validate_artifact_zip(args.artifact_zip)
        if args.artifact_dir is not None:
            reports["direct_apk"] = validate_artifact_directory(args.artifact_dir)
        if args.artifact_zip is not None and args.artifact_dir is not None:
            require_matching_apk_reports(
                reports["artifact_zip"]["apk"],
                reports["direct_apk"],
            )
        report = reports["artifact_zip"] if args.artifact_zip is not None else reports["direct_apk"]
        if args.report is not None:
            write_report(args.report, reports if len(reports) > 1 else report)
    except (ArtifactValidationError, OSError) as error:
        print(f"FAIL: {error}")
        return 1
    if args.artifact_zip is not None:
        zip_report = reports["artifact_zip"]
        contained_report = zip_report["apk"]
        print(f"PASS: artifact ZIP filename={zip_report['artifact_zip_filename']}")
        print(f"PASS: artifact ZIP size_bytes={zip_report['artifact_zip_size_bytes']}")
        print(f"PASS: artifact ZIP sha256={zip_report['artifact_zip_sha256']}")
        print(f"PASS: contained APK filename={contained_report['filename']}")
        print(f"PASS: contained APK size_bytes={contained_report['size_bytes']}")
        print(f"PASS: contained APK sha256={contained_report['sha256']}")
    if args.artifact_dir is not None:
        direct_report = reports["direct_apk"]
        print(f"PASS: APK filename={direct_report['filename']}")
        print(f"PASS: APK size_bytes={direct_report['size_bytes']}")
        print(f"PASS: APK sha256={direct_report['sha256']}")
    print(f"PASS: validated {len(REQUIRED_ENTRIES)} required APK entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
