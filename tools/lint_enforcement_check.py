#!/usr/bin/env python3
"""Keep Android Lint runnable, unmuzzled, and load-bearing.

Lint could not complete a run on AGP 8.7.3 at all: three Compose detectors threw
IncompatibleClassChangeError against that lint API and took the whole analysis
down, so none of the other checks reported. It stayed broken long enough that a
detector disable was added to work around it and thirteen real errors accumulated
behind it, seven of them a NoSuchMethodError on Android 8.0.

Lint is now the mechanism that catches that class of defect, which makes three
things load-bearing: the AGP floor where lint actually runs, an empty disable
list, and abortOnError. A muzzled lint reports "ok" exactly like a clean one.

The direct-call check is narrower and covers the specific defect that shipped:
`notifyColorsChanged()` is API 27 against minSdk 26, and every engine must reach
it through the guarded helper rather than calling the framework itself.

Exit 0 if clean, 1 if violations found.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


BUILD_SCRIPT = "app/build.gradle.kts"
VERSION_CATALOG = "gradle/libs.versions.toml"
COLORS_HELPER_SOURCE = "app/src/main/java/com/freevibe/service/LiveWallpaperColors.kt"
ENGINE_SOURCE_ROOT = "app/src/main/java/com/freevibe/service"

# AGP 8.9 is the first line whose bundled lint artifacts match the Compose lint
# checks in this project's BOM. Below it the run aborts instead of reporting.
MINIMUM_AGP = (8, 9)

GUARDED_HELPER = "notifyWallpaperColorsChanged"
RAW_COLORS_CALL = re.compile(r"(?<![.\w])notifyColorsChanged\s*\(")

LINT_BLOCK = re.compile(r"\blint\s*\{(.*?)\n    \}", re.DOTALL)
DISABLE_ENTRY = re.compile(r"^\s*disable\s*(\+=|=)", re.MULTILINE)
BASELINE_ENTRY = re.compile(r"^\s*baseline\s*=", re.MULTILINE)
AGP_VERSION = re.compile(r'^agp\s*=\s*"([^"]+)"', re.MULTILINE)


class LintEnforcementError(ValueError):
    """Raised when lint has been disabled, muzzled, or made unable to run."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


def read(repo_root: Path, relative_path: str) -> str:
    path = repo_root / relative_path
    if not path.is_file():
        raise LintEnforcementError(f"missing file: {relative_path}")
    return path.read_text(encoding="utf-8")


def agp_version(catalog: str) -> tuple[int, ...]:
    match = AGP_VERSION.search(catalog)
    if not match:
        raise LintEnforcementError(f"{VERSION_CATALOG} declares no agp version")
    parts = []
    for piece in match.group(1).split("-", 1)[0].split("."):
        if not piece.isdigit():
            break
        parts.append(int(piece))
    if not parts:
        raise LintEnforcementError(f"unparseable agp version: {match.group(1)}")
    return tuple(parts)


def lint_block(build_script: str) -> str:
    match = LINT_BLOCK.search(build_script)
    if not match:
        raise LintEnforcementError(
            f"{BUILD_SCRIPT} has no lint block, so abortOnError is whatever the "
            "plugin defaults to rather than something this project stated"
        )
    return match.group(1)


def unguarded_color_notifications(repo_root: Path) -> list[str]:
    """Engine files calling the framework method instead of the guarded helper."""
    root = repo_root / ENGINE_SOURCE_ROOT
    if not root.is_dir():
        raise LintEnforcementError(f"missing directory: {ENGINE_SOURCE_ROOT}")
    offenders: list[str] = []
    helper_name = Path(COLORS_HELPER_SOURCE).name
    for source_path in sorted(root.rglob("*.kt")):
        if source_path.name == helper_name:
            continue
        source = source_path.read_text(encoding="utf-8")
        for index, line in enumerate(source.splitlines(), start=1):
            if line.lstrip().startswith("//") or line.lstrip().startswith("*"):
                continue
            if RAW_COLORS_CALL.search(line):
                offenders.append(f"{source_path.relative_to(repo_root).as_posix()}:{index}")
    return offenders


def validate_lint_enforcement(repo_root: Path) -> dict[str, object]:
    errors: list[str] = []

    version = agp_version(read(repo_root, VERSION_CATALOG))
    if version[:2] < MINIMUM_AGP:
        errors.append(
            f"AGP {'.'.join(map(str, version))} is below "
            f"{'.'.join(map(str, MINIMUM_AGP))}, where lint aborts with "
            "IncompatibleClassChangeError instead of reporting findings"
        )

    build_script = read(repo_root, BUILD_SCRIPT)
    block = lint_block(build_script)

    disabled = DISABLE_ENTRY.search(block)
    if disabled:
        errors.append(
            "the lint block disables detectors; every disable here has been a "
            "workaround for a broken toolchain, not for a wrong finding"
        )
    if BASELINE_ENTRY.search(block):
        errors.append(
            "the lint block declares a baseline, which reports existing findings "
            "as clean rather than as findings"
        )
    if "abortOnError = true" not in block:
        errors.append(
            "the lint block does not set abortOnError = true, so a lint error "
            "leaves the build green"
        )

    helper_source = read(repo_root, COLORS_HELPER_SOURCE)
    if f"fun WallpaperService.Engine.{GUARDED_HELPER}" not in helper_source:
        errors.append(
            f"{COLORS_HELPER_SOURCE} no longer defines {GUARDED_HELPER}(), the one "
            "place the API 27 guard for notifyColorsChanged lives"
        )

    offenders = unguarded_color_notifications(repo_root)
    for offender in offenders:
        errors.append(
            f"{offender} calls notifyColorsChanged() directly; it is API 27 against "
            f"minSdk 26, so it must go through {GUARDED_HELPER}()"
        )

    if errors:
        raise LintEnforcementError("; ".join(errors))

    return {
        "status": "ok",
        "policyKind": "lintEnforcement",
        "schemaVersion": 1,
        "agpVersion": ".".join(map(str, version)),
        "minimumAgpForLint": ".".join(map(str, MINIMUM_AGP)),
        "detectorDisables": 0,
        "lintBaselineDeclared": False,
        "guardedColorNotificationHelper": GUARDED_HELPER,
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    try:
        result = validate_lint_enforcement(repo_root)
    except LintEnforcementError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, indent=2, sort_keys=True))
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
