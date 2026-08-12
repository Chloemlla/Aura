#!/usr/bin/env python3
"""Reject Kotlin constructs that break this project's pinned compiler.

Some standard-library calls emit anonymous classes carrying no Kotlin metadata.
Kotlin 2.1.0's incremental compiler asserts when it reads one back:

    Couldn't load KotlinClass from ...$$inlined$groupingBy$1.class;
    it may happen because class doesn't have valid Kotlin annotations

The failure is easy to miss because Gradle recovers by discarding the
incremental state and recompiling the whole source set, so the build still
reports SUCCESSFUL while taking far longer. In `app/src/test` it was not
recoverable at all: `compileFullDebugUnitTestKotlin` aborted, and no unit test
in the project could run.

Each hazard below names a drop-in replacement. Remove an entry once the
toolchain moves past the release that needs it.

Exit 0 if clean, 1 if violations found.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


HAZARDS = (
    {
        "pattern": ".groupingBy",
        "replacement": ".groupBy { ... }.mapValues { (_, group) -> group.size }",
        "reason": (
            "groupingBy emits an anonymous Grouping class with no Kotlin metadata; "
            "Kotlin 2.1.0's incremental compiler cannot read it back"
        ),
    },
)

SCAN_ROOTS = (
    "app/src/main",
    "app/src/test",
    "app/src/debug",
    "app/src/androidTest",
    "app/src/foss",
    "baselineprofile/src",
)


class KotlinToolchainHazardError(ValueError):
    """Raised when source uses a construct the pinned Kotlin compiler mishandles."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Reject Kotlin constructs that break the pinned compiler.",
    )
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


def iter_kotlin_files(repo_root: Path) -> list[Path]:
    files: list[Path] = []
    for root in SCAN_ROOTS:
        base = repo_root / root
        if base.is_dir():
            files.extend(sorted(base.rglob("*.kt")))
            files.extend(sorted(base.rglob("*.kts")))
    return files


def validate_kotlin_hazards(repo_root: Path) -> dict[str, object]:
    files = iter_kotlin_files(repo_root)
    if not files:
        raise KotlinToolchainHazardError(
            "no Kotlin sources found; the scanner is not reading anything"
        )

    violations: list[str] = []
    for path in files:
        text = path.read_text(encoding="utf-8")
        for hazard in HAZARDS:
            if hazard["pattern"] not in text:
                continue
            for number, line in enumerate(text.splitlines(), start=1):
                if hazard["pattern"] in line:
                    relative = path.relative_to(repo_root).as_posix()
                    violations.append(
                        f"{relative}:{number} uses {hazard['pattern']} — "
                        f"{hazard['reason']}. Use {hazard['replacement']}"
                    )

    if violations:
        raise KotlinToolchainHazardError("; ".join(violations))

    return {
        "status": "ok",
        "policyKind": "kotlinToolchainHazard",
        "schemaVersion": 1,
        "hazardCount": len(HAZARDS),
        "scannedFileCount": len(files),
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    try:
        result = validate_kotlin_hazards(repo_root)
    except KotlinToolchainHazardError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, indent=2, sort_keys=True))
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
