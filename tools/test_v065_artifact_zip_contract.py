from __future__ import annotations

import importlib.util
import subprocess
import sys
import tempfile
import warnings
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parents[1]
VALIDATOR_PATH = ROOT / "tools/validate_v065_apk.py"
PACKAGER_PATH = ROOT / "tools/package_v065_apk.py"
EXPECTED_APK_NAME = "XiaoZhi-Mobile-v0.6.5-debug.apk"


def load_module(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_minimal_apk(path: Path, *, classes_suffix: bytes = b"") -> None:
    with ZipFile(path, "w", compression=ZIP_DEFLATED) as archive:
        for name, content in {
            "AndroidManifest.xml": b"manifest",
            "classes.dex": b"dex" + classes_suffix,
            "lib/arm64-v8a/libsherpa-onnx-jni.so": b"jni",
            "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/encoder-epoch-13-avg-2-chunk-16-left-64.onnx": b"encoder",
            "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/decoder-epoch-13-avg-2-chunk-16-left-64.onnx": b"decoder",
            "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/joiner-epoch-13-avg-2-chunk-16-left-64.onnx": b"joiner",
            "assets/sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20/tokens.txt": b"tokens",
            "assets/sherpa-onnx-paraformer-zh-small-2024-03-09/model.int8.onnx": b"asr",
            "assets/sherpa-onnx-paraformer-zh-small-2024-03-09/tokens.txt": b"tokens",
        }.items():
            archive.writestr(name, content)


def expect_failure(callable_, expected_fragment: str) -> None:
    try:
        callable_()
    except Exception as error:
        assert expected_fragment in str(error), str(error)
        return
    raise AssertionError(f"expected failure containing {expected_fragment!r}")


workflow = (ROOT / ".github/workflows/build-apk.yml").read_text("utf-8")
assert "tools/package_v065_apk.py" in workflow
assert "name: Create deterministic APK integrity ZIP" in workflow
assert "name: XiaoZhi-Mobile-APK-integrity" in workflow
assert "name: Download APK integrity ZIP" in workflow
assert "--artifact-zip artifact-zip-verification/" in workflow
assert workflow.index("--artifact-zip artifact-zip-verification/") < workflow.index(
    "--artifact-dir artifact-verification"
)

validator = load_module(VALIDATOR_PATH, "validate_v065_apk")
packager = load_module(PACKAGER_PATH, "package_v065_apk")

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    apk = root / EXPECTED_APK_NAME
    write_minimal_apk(apk)
    wrapper_one = root / "one.zip"
    wrapper_two = root / "two.zip"
    packager.create_artifact_zip(apk, wrapper_one)
    packager.create_artifact_zip(apk, wrapper_two)
    assert wrapper_one.read_bytes() == wrapper_two.read_bytes()
    report = validator.validate_artifact_zip(wrapper_one)
    assert report["artifact_zip_filename"] == "one.zip"
    assert report["apk"]["filename"] == EXPECTED_APK_NAME
print("PASS: wrapper artifact ZIP is deterministic and validates its contained APK")

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    apk = root / "direct" / EXPECTED_APK_NAME
    apk.parent.mkdir()
    write_minimal_apk(apk)
    artifact_zip = root / "artifact.zip"
    packager.create_artifact_zip(apk, artifact_zip)
    result = subprocess.run(
        [
            sys.executable,
            str(VALIDATOR_PATH),
            "--artifact-zip",
            str(artifact_zip),
            "--artifact-dir",
            str(apk.parent),
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr
print("PASS: combined validation accepts equal nested and direct APKs")

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    wrapped_apk = root / "wrapped" / EXPECTED_APK_NAME
    direct_dir = root / "direct"
    direct_apk = direct_dir / EXPECTED_APK_NAME
    wrapped_apk.parent.mkdir()
    direct_dir.mkdir()
    write_minimal_apk(wrapped_apk)
    write_minimal_apk(direct_apk, classes_suffix=b"-different-valid-fixture")
    wrapped_report = validator.validate_apk(wrapped_apk)
    direct_report = validator.validate_apk(direct_apk)
    assert wrapped_report["filename"] == direct_report["filename"] == EXPECTED_APK_NAME
    assert wrapped_report["size_bytes"] != direct_report["size_bytes"]
    assert wrapped_report["sha256"] != direct_report["sha256"]
    artifact_zip = root / "artifact.zip"
    packager.create_artifact_zip(wrapped_apk, artifact_zip)
    result = subprocess.run(
        [
            sys.executable,
            str(VALIDATOR_PATH),
            "--artifact-zip",
            str(artifact_zip),
            "--artifact-dir",
            str(direct_dir),
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    output = result.stdout + result.stderr
    assert result.returncode == 1, (
        "combined validation must reject individually valid APKs with mismatched "
        f"bytes; got exit {result.returncode}: {output}"
    )
    assert "mismatch" in output.lower(), output
print("PASS: combined validation rejects mismatched valid APKs")

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    apk = root / EXPECTED_APK_NAME
    write_minimal_apk(apk)
    wrapper = root / "duplicate.zip"
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", UserWarning)
        with ZipFile(wrapper, "w", compression=ZIP_DEFLATED) as archive:
            archive.write(apk, EXPECTED_APK_NAME)
            archive.write(apk, EXPECTED_APK_NAME)
    expect_failure(
        lambda: validator.validate_artifact_zip(wrapper),
        "duplicate",
    )
print("PASS: wrapper artifact ZIP rejects duplicate entries")

with tempfile.TemporaryDirectory() as tmp:
    wrapper = Path(tmp) / "traversal.zip"
    with ZipFile(wrapper, "w", compression=ZIP_DEFLATED) as archive:
        archive.writestr("../" + EXPECTED_APK_NAME, b"not safe")
    expect_failure(
        lambda: validator.validate_artifact_zip(wrapper),
        "exactly",
    )
print("PASS: wrapper artifact ZIP rejects path traversal entries")

with tempfile.TemporaryDirectory() as tmp:
    root = Path(tmp)
    valid = root / "valid.zip"
    with ZipFile(valid, "w", compression=ZIP_DEFLATED) as archive:
        archive.writestr(EXPECTED_APK_NAME, b"not an APK")
    corrupt = root / "corrupt.zip"
    corrupt.write_bytes(valid.read_bytes()[:-8])
    expect_failure(
        lambda: validator.validate_artifact_zip(corrupt),
        "artifact ZIP",
    )
print("PASS: corrupt downloaded artifact ZIP fails closed")

print("PASS: v0.6.5 artifact ZIP contract")
