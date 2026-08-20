#!/usr/bin/env python3
"""Validate every documentation link the repository points a user at resolves.

README, the root-level guides, and the app all link to tracked files. Those
links are served by the published repository, not the working tree, so a doc
that exists locally but is untracked returns 404 to every user while every
content gate still reports ok. That happened to docs/privacy/privacy-policy.md
(opened by Settings > About > Privacy policy), and then again one directory up
to CONTRIBUTING.md and ARCHITECTURE.md, which a `*.md` ignore rule kept
untracked while GitHub showed no contributing guidelines at all.

The scanner therefore walks every tracked root-level markdown file, not just
README, and resolves every relative link target regardless of prefix rather
than only `docs/`-prefixed ones.

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

from tools.published_state import PublishedStateError, assert_tracked, is_tracked


BLOB_PREFIX = "https://github.com/SysAdminDoc/Aura/blob/main/"
TREE_PREFIX = "https://github.com/SysAdminDoc/Aura/tree/main/"

# Bare docs/ paths embedded in app source and markdown prose, with or without
# the published blob prefix.
DOC_LINK_PATTERN = re.compile(
    r"(?:" + re.escape(BLOB_PREFIX) + r")?(docs/[A-Za-z0-9._/-]+\.md)"
)

# Inline markdown links: [label](target). Reference-style definitions too.
MARKDOWN_LINK_PATTERN = re.compile(r"\[[^\]]*\]\(\s*<?([^)>\s]+)>?\s*(?:\"[^\"]*\")?\s*\)")

SOURCE_ROOTS = ("app/src/main/java", "app/src/main/res/values")

# Link targets that are not repository paths.
EXTERNAL_SCHEME = re.compile(r"^(?:[a-z][a-z0-9+.-]*:|//|#)", re.IGNORECASE)


class DocsLinkError(ValueError):
    """Raised when a referenced document would not resolve for a user."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate documentation links in README and app source resolve."
    )
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


def iter_root_markdown(repo_root: Path) -> list[Path]:
    """Every tracked root-level markdown file, so no published guide is unscanned."""
    return [
        path
        for path in sorted(repo_root.glob("*.md"))
        if path.is_file() and is_tracked(repo_root, path.name)
    ]


def iter_reference_files(repo_root: Path) -> list[Path]:
    files = iter_root_markdown(repo_root)
    if not any(path.name == "README.md" for path in files):
        files.append(repo_root / "README.md")
    for root in SOURCE_ROOTS:
        base = repo_root / root
        if not base.is_dir():
            continue
        files.extend(sorted(base.rglob("*.kt")))
        files.extend(sorted(base.rglob("*.xml")))
    return [path for path in files if path.is_file()]


def resolve_link(repo_root: Path, source: Path, target: str) -> str | None:
    """Map a markdown link to a repo-relative path, or None when it is not one."""
    for prefix in (BLOB_PREFIX, TREE_PREFIX):
        if target.startswith(prefix):
            target = target[len(prefix) :]
            break
    else:
        if EXTERNAL_SCHEME.match(target):
            return None
    target = target.split("#", 1)[0].split("?", 1)[0].strip()
    if not target:
        return None
    if target.startswith("/"):
        candidate = repo_root / target.lstrip("/")
    else:
        candidate = source.parent / target
    try:
        relative = candidate.resolve().relative_to(repo_root.resolve())
    except ValueError:
        return None
    return relative.as_posix()


def collect_references(repo_root: Path) -> dict[str, list[str]]:
    references: dict[str, set[str]] = {}

    def record(target: str, path: Path) -> None:
        references.setdefault(target, set()).add(
            str(path.relative_to(repo_root).as_posix())
        )

    for path in iter_reference_files(repo_root):
        try:
            text = path.read_text(encoding="utf-8")
        except UnicodeDecodeError as exc:
            raise DocsLinkError(f"{path} is not valid UTF-8: {exc}") from exc
        for match in DOC_LINK_PATTERN.finditer(text):
            record(match.group(1), path)
        if path.suffix != ".md":
            continue
        for match in MARKDOWN_LINK_PATTERN.finditer(text):
            resolved = resolve_link(repo_root, path, match.group(1))
            if resolved:
                record(resolved, path)
    return {target: sorted(sources) for target, sources in sorted(references.items())}


def validate_docs_links(repo_root: Path) -> dict[str, object]:
    references = collect_references(repo_root)
    if not references:
        raise DocsLinkError("no documentation links found; the scanner is not reading anything")

    errors: list[str] = []
    for target, sources in references.items():
        cited_by = ", ".join(sources)
        label = f"{target} (linked from {cited_by})"
        candidate = repo_root / target
        if candidate.is_dir():
            if not any(
                is_tracked(repo_root, child.relative_to(repo_root).as_posix())
                for child in sorted(candidate.rglob("*"))
                if child.is_file()
            ):
                errors.append(f"{label} is a directory with no tracked content")
            continue
        try:
            assert_tracked(repo_root, target, label)
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
