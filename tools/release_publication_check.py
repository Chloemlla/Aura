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
    assert_tag_exists,
    release_assets,
    release_published,
)


APP_GRADLE = "app/build.gradle.kts"
VERSION_NAME_RE = re.compile(r'versionName\s*=\s*"([^"]+)"')
VERSION_CODE_RE = re.compile(r'versionCode\s*=\s*(\d+)')

REQUIRED_ABIS = ("arm64-v8a", "armeabi-v7a", "universal", "x86", "x86_64")
CHECKSUM_ASSET = "SHA256SUMS.txt"


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


def expected_apk_names(version: str, version_code: int) -> list[str]:
    return [
        f"Aura-v{version}-versionCode-{version_code}-{abi}-release.apk"
        for abi in REQUIRED_ABIS
    ]


def validate_release_assets(
    repo_root: Path, tag: str, version: str, version_code: int, *, strict: bool = False,
) -> dict[str, object]:
    assets = release_assets(repo_root, tag)
    if assets is None:
        if strict:
            raise ReleasePublicationError(
                f"strict mode: cannot verify assets for {tag} (GitHub unreachable)"
            )
        return {"assetsValid": "unknown"}

    asset_names = {a["name"] for a in assets}
    required = set(expected_apk_names(version, version_code))
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


def validate_release_publication(
    repo_root: Path, *, strict: bool = False,
) -> dict[str, object]:
    version = declared_version(repo_root)
    version_code = declared_version_code(repo_root)
    tag = f"v{version}"
    label = f"declared version {version}"

    try:
        assert_tag_exists(repo_root, tag, label)
        assert_release_published(repo_root, tag, label)
    except PublishedStateError as exc:
        raise ReleasePublicationError(str(exc)) from exc

    state = release_published(repo_root, tag)
    asset_result = validate_release_assets(
        repo_root, tag, version, version_code, strict=strict,
    )

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
