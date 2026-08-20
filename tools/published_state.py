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
import urllib.error
import urllib.request
from pathlib import Path


HTTP_TIMEOUT_SECONDS = 5.0


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


def url_resolves(url: str, timeout: float = HTTP_TIMEOUT_SECONDS) -> bool | None:
    """True/False when the host answers, None when the question cannot be asked.

    Tri-state for the same reason [release_published] is: only the server knows,
    and a gate that turned "no network" into "broken link" would fail builds for
    the wrong reason. Only a definitive 404 or 410 is a False.

    A HEAD is tried first because it is what a link check needs; some hosts
    answer HEAD with 403 or 405 while serving the document perfectly well, so
    those fall through to a GET rather than being reported as a dead link.
    """
    for method in ("HEAD", "GET"):
        try:
            # Request() itself rejects a malformed URL, so it belongs inside the
            # guard: a bad string in a policy file is an unanswerable question,
            # not a crash in the gate that asked it.
            request = urllib.request.Request(url, method=method)
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return 200 <= response.status < 400
        except urllib.error.HTTPError as exc:
            if exc.code in (404, 410):
                return False
            if method == "GET":
                # Any other status is the server declining to answer this
                # question, not evidence about whether the document exists.
                return None
        except (urllib.error.URLError, OSError, ValueError):
            return None
    return None


def assert_resolves_over_http(url: str, label: str, timeout: float = HTTP_TIMEOUT_SECONDS) -> None:
    """Raise when a URL the app or the docs send users to is definitively gone.

    Content checks prove a document is correct in the tree. They cannot prove the
    published copy exists, which is how the in-app privacy policy button opened a
    404 for months while every gate reported ok.
    """
    if url_resolves(url, timeout=timeout) is False:
        raise PublishedStateError(
            f"{label}: {url} returns 404, so every user following that link reaches nothing"
        )


def assert_enforcement_mechanism(
    repo_root: Path,
    claim: str,
    mechanism_paths: list[str],
    label: str,
) -> None:
    """Raise when a policy claims something enforces it but names nothing real.

    `docs/distribution/native-alignment.json` carried `releaseWorkflowEnforced`
    for a year after the workflows it referred to were deleted. A status string
    is not a mechanism: the file it names has to exist, and has to be tracked, or
    the claim is decoration.
    """
    if not is_git_repository(repo_root):
        return
    if not mechanism_paths:
        raise PublishedStateError(
            f"{label} claims '{claim}' but names no mechanism, so nothing enforces it"
        )
    for relative_path in mechanism_paths:
        target = repo_root / relative_path
        if not target.exists():
            raise PublishedStateError(
                f"{label} claims '{claim}' through {relative_path}, which does not exist"
            )
        if target.is_file() and not is_tracked(repo_root, relative_path):
            raise PublishedStateError(
                f"{label} claims '{claim}' through {relative_path}, which is untracked, "
                "so the published repository has no such mechanism"
            )
