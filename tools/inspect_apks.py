#!/usr/bin/env python3
"""Check the actual API URL literals in the debug and release APK dex files."""

from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path


RELEASE_API_BASE_URL = b"https://api.todomath.com"


def dex_contents(apk_path: Path) -> bytes:
    with zipfile.ZipFile(apk_path) as apk:
        dex_files = [name for name in apk.namelist() if name == "classes.dex" or name.startswith("classes") and name.endswith(".dex")]
        if not dex_files:
            raise RuntimeError(f"{apk_path} contains no dex files")
        return b"\n".join(apk.read(name) for name in dex_files)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--debug-apk", required=True, type=Path)
    parser.add_argument("--release-apk", required=True, type=Path)
    parser.add_argument("--api-base-url", required=True)
    args = parser.parse_args()

    expected_debug = args.api_base_url.encode("utf-8")
    debug_dex = dex_contents(args.debug_apk)
    release_dex = dex_contents(args.release_apk)

    checks = {
        "debug contains configured loopback address": expected_debug in debug_dex,
        "debug excludes the production TodoSchool address": RELEASE_API_BASE_URL not in debug_dex,
        "release contains the production TodoSchool address": RELEASE_API_BASE_URL in release_dex,
        "release excludes the debug loopback address": expected_debug not in release_dex,
    }
    for name, passed in checks.items():
        print(f"{'PASS' if passed else 'FAIL'} {name}")
    return 0 if all(checks.values()) else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, RuntimeError, zipfile.BadZipFile) as error:
        print(f"APK inspection failed: {error}", file=sys.stderr)
        raise SystemExit(2)
