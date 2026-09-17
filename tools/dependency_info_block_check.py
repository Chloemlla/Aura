#!/usr/bin/env python3
"""Validate that dependenciesInfo is disabled in release artifacts."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


class DependencyInfoBlockError(ValueError):
    pass


INCLUDE_IN_APK = re.compile(r"includeInApk\s*=\s*(true|false)")
INCLUDE_IN_BUNDLE = re.compile(r"includeInBundle\s*=\s*(true|false)")


def validate_dependency_info_block(build_gradle: Path) -> dict[str, object]:
    text = build_gradle.read_text(encoding="utf-8")
    if "dependenciesInfo" not in text:
        raise DependencyInfoBlockError(
            f"{build_gradle} must contain a dependenciesInfo block disabling the blob"
        )
    apk_match = INCLUDE_IN_APK.search(text)
    bundle_match = INCLUDE_IN_BUNDLE.search(text)
    if not apk_match or apk_match.group(1) != "false":
        raise DependencyInfoBlockError("includeInApk must be false")
    if not bundle_match or bundle_match.group(1) != "false":
        raise DependencyInfoBlockError("includeInBundle must be false")
    return {"includeInApk": False, "includeInBundle": False, "status": "ok"}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-gradle", default="app/build.gradle.kts")
    args = parser.parse_args()
    try:
        result = validate_dependency_info_block(Path(args.build_gradle))
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
