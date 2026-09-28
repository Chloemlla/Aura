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

The release half also asks what the Release carries, because a Release that
serves no APK is as unreachable as no Release at all. The names it requires are
the ones the fork's release job writes — `Aura_android_<flavor>_<version>.apk`
per flavor, `Aura_android_<flavor>_<version>_<abi>.apk` per shipped ABI, and
`checksums.txt` — with `<version>` taken from the tag itself. The ABI list is
read out of the `splits { abi { include(...) } }` block in app/build.gradle.kts
rather than restated, so the gate and the build cannot disagree about what a
release contains. This module first encoded upstream's naming instead
(`Aura-v<versionName>-versionCode-<code>-<abi>-release.apk`, `SHA256SUMS.txt`,
a universal APK, an x86 APK), which the fork's workflow has never produced: the
asset half could not pass on any fork release, published or not.

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
    release_assets,
    release_published,
)


APP_GRADLE = "app/build.gradle.kts"
VERSION_NAME_RE = re.compile(r'versionName\s*=\s*"([^"]+)"')
VERSION_CODE_RE = re.compile(r'versionCode\s*=\s*(\d+)')

# The splits block nests no braces, so the first closing brace ends each level.
SPLITS_BLOCK_RE = re.compile(r"splits\s*\{(.*?)\}", re.DOTALL)
ABI_BLOCK_RE = re.compile(r"abi\s*\{(.*?)\}", re.DOTALL)
ABI_INCLUDE_RE = re.compile(r"include\(([^)]*)\)")
ABI_LITERAL_RE = re.compile(r'"([^"]+)"')

# The flavors the release job's `for flavor in full foss` loop builds, and the
# checksum file its `sha256sum *.apk > checksums.txt` writes beside them.
RELEASE_FLAVORS = ("full", "foss")
CHECKSUM_ASSET = "checksums.txt"


class ReleasePublicationError(ValueError):
    """Raised when the declared version is not published."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate the declared version is tagged and released.",
    )
    parser.add_argument("--repo-root", default=".")
    parser.add_argument(
        "--strict", action="store_true",
        help="Fail when remote state cannot be determined instead of reporting unknown.",
    )
    return parser.parse_args()


def declared_version_code(repo_root: Path) -> int:
    path = repo_root / APP_GRADLE
    if not path.is_file():
        raise ReleasePublicationError(f"missing file: {APP_GRADLE}")
    match = VERSION_CODE_RE.search(path.read_text(encoding="utf-8"))
    if not match:
        raise ReleasePublicationError(f"{APP_GRADLE} declares no versionCode")
    return int(match.group(1))


def shipped_abis(repo_root: Path) -> tuple[str, ...]:
    """The ABIs `splits { abi { include(...) } }` ships, in declaration order.

    Read rather than restated: this gate and the build have to agree on what a
    release contains, and a second copy of the list is what would let them
    drift apart. A file whose splits cannot be read fails closed, because
    assuming a list is how this gate came to demand assets that no build emits.
    """
    path = repo_root / APP_GRADLE
    if not path.is_file():
        raise ReleasePublicationError(f"missing file: {APP_GRADLE}")
    splits = SPLITS_BLOCK_RE.search(path.read_text(encoding="utf-8"))
    abi = ABI_BLOCK_RE.search(splits.group(1)) if splits else None
    include = ABI_INCLUDE_RE.search(abi.group(1)) if abi else None
    abis = tuple(ABI_LITERAL_RE.findall(include.group(1))) if include else ()
    if not abis:
        raise ReleasePublicationError(
            f"{APP_GRADLE} declares no ABI splits include(...), so the ABIs a "
            "release ships cannot be determined"
        )
    return abis


def expected_apk_names(repo_root: Path, version: str) -> list[str]:
    """Every APK a release of [version] has to carry.

    [version] is the resolved tag without its leading `v`. The release job names
    its assets after the tag it is about to cut, so a name built from the tree's
    versionName would describe a release nobody can download — the run number
    and short sha are only in the tag. One aggregated APK per flavor (ABI splits
    leave no universal APK, so the job promotes arm64-v8a's) plus one per shipped
    ABI.
    """
    names = [f"Aura_android_{flavor}_{version}.apk" for flavor in RELEASE_FLAVORS]
    names.extend(
        f"Aura_android_{flavor}_{version}_{abi}.apk"
        for flavor in RELEASE_FLAVORS
        for abi in shipped_abis(repo_root)
    )
    return names


def release_version(tag: str) -> str:
    """The version part of a tag: the release job's asset names drop the `v`."""
    return tag[1:] if tag.startswith("v") else tag


def validate_release_assets(
    repo_root: Path, tag: str, *, strict: bool = False,
) -> dict[str, object]:
    assets = release_assets(repo_root, tag)
    if assets is None:
        if strict:
            raise ReleasePublicationError(
                f"strict mode: cannot verify assets for {tag} (GitHub unreachable)"
            )
        return {"assetsValid": "unknown"}

    asset_names = {a["name"] for a in assets}
    required = set(expected_apk_names(repo_root, release_version(tag)))
    required.add(CHECKSUM_ASSET)

    missing = required - asset_names
    if missing:
        raise ReleasePublicationError(
            f"release {tag} is missing required assets: {', '.join(sorted(missing))}"
        )

    unexpected_apks = {
        n for n in asset_names
        if n.endswith(".apk") and n not in required
    }
    if unexpected_apks:
        raise ReleasePublicationError(
            f"release {tag} has unexpected APK assets: {', '.join(sorted(unexpected_apks))}"
        )

    return {"assetsValid": True, "assetCount": len(asset_names)}


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


def validate_release_publication(
    repo_root: Path, *, strict: bool = False,
) -> dict[str, object]:
    version = declared_version(repo_root)
    version_code = declared_version_code(repo_root)
    label = f"declared version {version}"

    try:
        tag = publication_tag(repo_root, version, label)
        assert_release_published(repo_root, tag, label)
    except PublishedStateError as exc:
        raise ReleasePublicationError(str(exc)) from exc

    state = release_published(repo_root, tag)
    asset_result = validate_release_assets(repo_root, tag, strict=strict)

    return {
        "status": "ok",
        "policyKind": "releasePublication",
        "schemaVersion": 2,
        "versionName": version,
        "versionCode": version_code,
        "tag": tag,
        "releasePublished": "unknown" if state is None else bool(state),
        **asset_result,
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    try:
        result = validate_release_publication(repo_root, strict=args.strict)
    except ReleasePublicationError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, indent=2, sort_keys=True))
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
