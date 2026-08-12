#!/usr/bin/env python3
"""Validate every documentation link README or the app points a user at resolves.

README and the app both link to files under docs/. Those links are served by the
published repository, not the working tree, so a doc that exists locally but is
untracked returns 404 to every user while every content gate still reports ok.
That is exactly what happened to docs/privacy/privacy-policy.md, which the
in-app Settings > About > Privacy policy button opens.

Exit 0 if clean, 1 if violations found.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.published_state import PublishedStateError, assert_tracked


BLOB_PREFIX = "https://github.com/SysAdminDoc/Aura/blob/main/"

# Markdown link targets and bare blob URLs that resolve to a docs/ path.
DOC_LINK_PATTERN = re.compile(
    r"(?:" + re.escape(BLOB_PREFIX) + r")?(docs/[A-Za-z0-9._/-]+\.md)"
)

SOURCE_ROOTS = ("app/src/main/java", "app/src/main/res/values")


class DocsLinkError(ValueError):
    """Raised when a referenced document would not resolve for a user."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate documentation links in README and app source resolve."
    )
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


def iter_reference_files(repo_root: Path) -> list[Path]:
    files = [repo_root / "README.md"]
    for root in SOURCE_ROOTS:
        base = repo_root / root
        if not base.is_dir():
            continue
        files.extend(sorted(base.rglob("*.kt")))
        files.extend(sorted(base.rglob("*.xml")))
    return [path for path in files if path.is_file()]


def collect_references(repo_root: Path) -> dict[str, list[str]]:
    references: dict[str, set[str]] = {}
    for path in iter_reference_files(repo_root):
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            raise DocsLinkError(f"{path} is not valid UTF-8: {exc}") from exc
        for match in DOC_LINK_PATTERN.finditer(text):
            target = match.group(1)
            references.setdefault(target, set()).add(
                str(path.relative_to(repo_root).as_posix())
            )
    return {target: sorted(sources) for target, sources in sorted(references.items())}


def validate_docs_links(repo_root: Path) -> dict[str, object]:
    references = collect_references(repo_root)
    if not references:
        raise DocsLinkError("no documentation links found; the scanner is not reading anything")

    errors: list[str] = []
    for target, sources in references.items():
        cited_by = ", ".join(sources)
        try:
            assert_tracked(repo_root, target, f"{target} (linked from {cited_by})")
        except PublishedStateError as exc:
            errors.append(str(exc))

    if errors:
        raise DocsLinkError("; ".join(errors))

    return {
        "status": "ok",
        "policyKind": "docsLinkResolution",
        "schemaVersion": 1,
        "linkedDocumentCount": len(references),
        "linkedDocuments": sorted(references),
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    try:
        result = validate_docs_links(repo_root)
    except DocsLinkError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, indent=2, sort_keys=True))
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
