#!/usr/bin/env python3
"""Predicates about what a release actually publishes, not what the tree contains.

Aura's release gates read the working tree. That is a weaker claim than it looks:
a file can satisfy every content check while being absent from the published
repository, so a gate reports `ok` for a document that returns 404 to users. The
in-app Settings > About > Privacy policy button opened such a URL for months.

These helpers answer the published-state questions the content checks cannot:
is this path tracked in git, does this git tag exist, and does a GitHub Release
actually exist for it. The first two are offline and deterministic.

The release check is not, because only GitHub knows what it serves, and a tag
is not a release: Obtainium reads Releases, so three tagged versions sat
unreachable behind a v6.38.1 Release while every local gate reported ok. It is
therefore tri-state — published, definitively absent, or undeterminable — and
only a definitive absence fails a gate. An offline checkout, a missing `gh`, or
an unauthenticated one yields "unknown" and is skipped rather than guessed at,
so the gate never invents a verdict it cannot support.
"""
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path


class PublishedStateError(ValueError):
    """Raised when the working tree and the published repository disagree."""


def _git(repo_root: Path, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(repo_root), *args],
        capture_output=True,
        text=True,
        check=False,
    )


def is_git_repository(repo_root: Path) -> bool:
    result = _git(repo_root, "rev-parse", "--is-inside-work-tree")
    return result.returncode == 0 and result.stdout.strip() == "true"


def is_tracked(repo_root: Path, relative_path: str) -> bool:
    """True when git tracks the path, i.e. the published repository serves it."""
    result = _git(repo_root, "ls-files", "--error-unmatch", "--", relative_path)
    return result.returncode == 0


def assert_tracked(repo_root: Path, relative_path: str, label: str) -> None:
    """Raise when a path a user is pointed at would not exist on the remote.

    Skipped outside a git checkout (release tarballs, vendored copies) so the
    gate stays usable where the question cannot be asked.
    """
    if not is_git_repository(repo_root):
        return
    target = repo_root / relative_path
    if not target.is_file():
        raise PublishedStateError(f"{label} is missing at {relative_path}")
    if not is_tracked(repo_root, relative_path):
        raise PublishedStateError(
            f"{label} exists locally but is not tracked in git, so {relative_path} "
            "would 404 for anyone following the published link"
        )


def tag_exists(repo_root: Path, tag: str) -> bool:
    result = _git(repo_root, "rev-parse", "--verify", "--quiet", f"refs/tags/{tag}")
    return result.returncode == 0


def assert_tag_exists(repo_root: Path, tag: str, label: str) -> None:
    """Raise when a version the project claims to have shipped was never tagged."""
    if not is_git_repository(repo_root):
        return
    if not tag_exists(repo_root, tag):
        raise PublishedStateError(
            f"{label}: no git tag {tag} exists, so the version is claimed but never released"
        )


def release_published(repo_root: Path, tag: str) -> bool | None:
    """True/False when GitHub can be asked, None when it cannot.

    None covers every reason the question is unanswerable here — no `gh` on
    PATH, no authentication, no network. Callers must not treat it as a pass or
    a failure; it means "not checked".
    """
    if shutil.which("gh") is None:
        return None
    result = subprocess.run(
        ["gh", "release", "view", tag, "--json", "tagName"],
        cwd=str(repo_root),
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode == 0:
        try:
            return json.loads(result.stdout).get("tagName") == tag
        except json.JSONDecodeError:
            return None
    # Only gh's own "release not found" is a definitive no. Every other failure
    # — auth, no remote, rate limit, transport — is an unknown, and reporting an
    # unknown as a missing release would fail builds for the wrong reason.
    if "release not found" in result.stderr.lower():
        return False
    return None


def assert_release_published(repo_root: Path, tag: str, label: str) -> None:
    """Raise when a tag exists but no GitHub Release serves it to users."""
    if release_published(repo_root, tag) is False:
        raise PublishedStateError(
            f"{label}: git tag {tag} exists but no published GitHub Release serves it, "
            "so Obtainium and every direct download stay on the previous version"
        )
