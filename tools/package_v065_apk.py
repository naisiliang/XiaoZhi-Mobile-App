from __future__ import annotations

import argparse
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo


EXPECTED_APK_NAME = "XiaoZhi-Mobile-v0.6.5-debug.apk"
DETERMINISTIC_TIMESTAMP = (1980, 1, 1, 0, 0, 0)


def create_artifact_zip(apk_path: Path, output_path: Path) -> None:
    if apk_path.name != EXPECTED_APK_NAME:
        raise ValueError(f"unexpected APK filename: expected {EXPECTED_APK_NAME}, got {apk_path.name}")
    if not apk_path.is_file():
        raise ValueError(f"APK file is missing: {apk_path}")

    apk_bytes = apk_path.read_bytes()
    entry = ZipInfo(EXPECTED_APK_NAME, date_time=DETERMINISTIC_TIMESTAMP)
    entry.compress_type = ZIP_DEFLATED
    entry.create_system = 3
    entry.external_attr = 0o600 << 16
    with ZipFile(output_path, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        archive.writestr(entry, apk_bytes)


def main() -> int:
    parser = argparse.ArgumentParser(description="Create the deterministic v0.6.5 APK integrity wrapper")
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    create_artifact_zip(args.apk, args.output)
    print(f"PASS: created deterministic APK wrapper {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
