#!/usr/bin/env python3
"""Validate that documented JDK references agree with the build-file requirement."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


REQUIRED_JDK_MAJOR = 21

RUNBOOK_FILES = [
    "docs/distribution/release-signing.md",
    "docs/distribution/release-dry-run.md",
    "docs/distribution/supply-chain.md",
]

JAVA_HOME_PATTERN = re.compile(
    r"""\$env:JAVA_HOME\s*=\s*["']([^"']+)["']"""
    r"""|JAVA_HOME\s*=\s*["']([^"']+)["']""",
)

JBR_PATTERN = re.compile(r"\bJBR\b|bundled JBR|Android Studio.{0,20}jbr", re.IGNORECASE)

JDK_REQUIRE_PATTERN = re.compile(
    r"""require\s*\(\s*jdkMajor\s*==\s*(\d+)\s*\)""",
)


class JdkRuntimePolicyError(ValueError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate JDK runtime policy.")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--build-gradle", default="app/build.gradle.kts")
    return parser.parse_args()


def check_build_gradle(repo: Path, build_gradle: str) -> int:
    path = repo / build_gradle
    text = path.read_text(encoding="utf-8")
    match = JDK_REQUIRE_PATTERN.search(text)
    if not match:
        raise JdkRuntimePolicyError(
            f"{build_gradle} must contain a require(jdkMajor == {REQUIRED_JDK_MAJOR}) preflight check"
        )
    declared = int(match.group(1))
    if declared != REQUIRED_JDK_MAJOR:
        raise JdkRuntimePolicyError(
            f"{build_gradle} requires JDK {declared} but policy expects {REQUIRED_JDK_MAJOR}"
        )
    return declared


def check_runbooks(repo: Path) -> list[dict[str, Any]]:
    issues: list[dict[str, Any]] = []
    for rel in RUNBOOK_FILES:
        path = repo / rel
        if not path.exists():
            issues.append({"file": rel, "problem": "missing"})
            continue
        text = path.read_text(encoding="utf-8")
        for lineno, line in enumerate(text.splitlines(), start=1):
            jbr = JBR_PATTERN.search(line)
            if jbr:
                issues.append({
                    "file": rel,
                    "line": lineno,
                    "problem": f"stale JBR reference: {jbr.group(0)!r}",
                })
    return issues


def validate_jdk_runtime_policy(repo: Path, build_gradle: str) -> dict[str, Any]:
    declared = check_build_gradle(repo, build_gradle)
    issues = check_runbooks(repo)
    if issues:
        detail = "; ".join(
            f"{i['file']}:{i.get('line', '?')}: {i['problem']}" for i in issues
        )
        raise JdkRuntimePolicyError(f"JDK runtime policy violations: {detail}")
    return {
        "requiredMajor": declared,
        "runbooksChecked": len(RUNBOOK_FILES),
        "status": "ok",
    }


def main() -> int:
    args = parse_args()
    try:
        result = validate_jdk_runtime_policy(Path(args.repo_root), args.build_gradle)
    except (OSError, ValueError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
