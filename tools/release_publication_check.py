#!/usr/bin/env python3
"""Fail when the declared version is not actually available to users.

Aura's entire distribution channel is the GitHub Releases page: Obtainium reads
it, and the README tells people to download from it. Between 2026-07-29 and
2026-08-20 the repository declared v6.39.0, v6.40.0, and v6.41.0, tagged them,
and shipped none of them — the newest Release stayed at v6.38.1, so every user
sat several versions behind including on security fixes, while all 82 local
gates reported ok because each one reads the working tree.

`obtainium.json` sets `fallbackToOlderReleases: true`, which means that failure
is silent by design on the client: users are quietly held on the last release
that had an asset rather than being told anything is wrong.

This gate closes both halves:
  * the declared versionName has a matching git tag, and
  * that tag has a published GitHub Release.

A version is published under one of two tag shapes: the bare `v<versionName>`
the upstream repository cuts, or the run-suffixed `v<versionName>.<run>-<short
sha>` the release workflow in .github/workflows/aura-android.yml cuts on every
publish. Either satisfies the tag half. The version base still has to match
exactly, so a tag cut for 6.45.1 or 6.45.20.1 does not stand in for 6.45.2.

The release half is skipped, not failed, when GitHub cannot be reached (see
`published_state.release_published`), so an offline checkout stays usable.

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

from tools.published_state import (
    PublishedStateError,
    assert_release_published,
    is_git_repository,
    list_tags,
    release_published,
)


APP_GRADLE = "app/build.gradle.kts"
VERSION_NAME_RE = re.compile(r'versionName\s*=\s*"([^"]+)"')


class ReleasePublicationError(ValueError):
    """Raised when the declared version is not published."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate the declared version is tagged and released.",
    )
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


def declared_version(repo_root: Path) -> str:
    path = repo_root / APP_GRADLE
    if not path.is_file():
        raise ReleasePublicationError(f"missing file: {APP_GRADLE}")
    match = VERSION_NAME_RE.search(path.read_text(encoding="utf-8"))
    if not match:
        raise ReleasePublicationError(f"{APP_GRADLE} declares no versionName")
    return match.group(1)


def fork_release_tag_re(version: str) -> re.Pattern[str]:
    """The run-suffixed tag the release workflow cuts for [version].

    `v<versionName>.<GITHUB_RUN_NUMBER>-<short sha>`: the separator before the
    run number is a literal dot, so this cannot be satisfied by a longer version
    that merely starts the same way — `v6.45.20.1-abcdef1` is 6.45.20.1, not
    6.45.2. The hash is the workflow's `${GITHUB_SHA::8}`, matched loosely
    across git's abbreviated-hash range rather than pinned to exactly 8.
    """
    return re.compile(r"^v" + re.escape(version) + r"\.([0-9]+)-[0-9a-f]{7,40}$")


def publication_tag(repo_root: Path, version: str, label: str) -> str:
    """The tag publishing [version], or a failure saying that none does.

    The exact tag wins when both shapes are present, because it is the one cut
    deliberately rather than as a build side effect. Otherwise the newest run
    tag speaks for the version: every publish on the same versionName reuses
    that base, so the older run tags are strictly older releases of it.

    Outside a git checkout the exact tag is returned unchecked, for the same
    reason the rest of this module's published-state questions are skipped
    there — a release tarball cannot answer them, and the gate stays usable.
    """
    exact = f"v{version}"
    if not is_git_repository(repo_root):
        return exact
    tags = list_tags(repo_root)
    if exact in tags:
        return exact
    pattern = fork_release_tag_re(version)
    run_tags: list[tuple[int, str]] = []
    for tag in tags:
        match = pattern.match(tag)
        if match:
            run_tags.append((int(match.group(1)), tag))
    if not run_tags:
        raise PublishedStateError(
            f"{label}: no git tag {exact} (nor the release workflow's "
            f"{exact}.<run>-<sha>) exists, so the version is claimed but never released"
        )
    return max(run_tags)[1]


def validate_release_publication(repo_root: Path) -> dict[str, object]:
    version = declared_version(repo_root)
    label = f"declared version {version}"

    try:
        tag = publication_tag(repo_root, version, label)
        assert_release_published(repo_root, tag, label)
    except PublishedStateError as exc:
        raise ReleasePublicationError(str(exc)) from exc

    state = release_published(repo_root, tag)
    return {
        "status": "ok",
        "policyKind": "releasePublication",
        "schemaVersion": 1,
        "versionName": version,
        "tag": tag,
        "releasePublished": "unknown" if state is None else bool(state),
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    try:
        result = validate_release_publication(repo_root)
    except ReleasePublicationError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, indent=2, sort_keys=True))
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
