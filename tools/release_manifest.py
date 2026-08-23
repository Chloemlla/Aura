#!/usr/bin/env python3
"""Derive every version-shaped release fact from one source and check or write it.

Before this existed the same version was restated by hand in
``app/build.gradle.kts``, the README badge, the release-metadata policy JSON, the
Fastlane changelog directory, and a hardcoded literal inside a unit test — so a
version bump silently left four of them stale, and the stale-fixture failures
only surfaced during a release.

``app/build.gradle.kts`` is the single source of truth for versionName and
versionCode, ``Database.kt`` for the Room schema version, and the provider
capability registry for per-channel provider defaults. Everything else is
derived from those and either verified (``--mode check``) or rewritten
(``--mode write``).
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

GRADLE_PATH = "app/build.gradle.kts"
DATABASE_PATH = "app/src/main/java/com/chloemlla/aura/data/local/Database.kt"
CAPABILITY_PATH = "app/src/main/java/com/chloemlla/aura/data/legal/ProviderCapability.kt"
README_PATH = "README.md"
CHANGELOG_PATH = "CHANGELOG.md"
POLICY_PATH = "docs/distribution/release-metadata-consistency.json"
YOUTUBE_RISK_PATH = "docs/distribution/youtube-store-risk-profile.json"
FASTLANE_CHANGELOG_DIR = "fastlane/metadata/android/en-US/changelogs"

README_VERSION_BADGE = re.compile(
    r"(!\[Version\]\(https://img\.shields\.io/badge/version-)([^-]+)(-blue\))"
)


class ReleaseManifestError(RuntimeError):
    """Raised when a derived artifact disagrees with the manifest."""


def read_text(repo_root: Path, relative: str) -> str:
    path = repo_root / relative
    if not path.is_file():
        raise ReleaseManifestError(f"Required file not found: {relative}")
    return path.read_text(encoding="utf-8")


def read_json(repo_root: Path, relative: str) -> dict[str, Any]:
    return json.loads(read_text(repo_root, relative))


def _require_match(pattern: str, text: str, label: str) -> str:
    match = re.search(pattern, text)
    if not match:
        raise ReleaseManifestError(f"Could not read {label}")
    return match.group(1)


def read_manifest(repo_root: Path) -> dict[str, Any]:
    """The authoritative facts, read straight from code."""
    gradle = read_text(repo_root, GRADLE_PATH)
    database = read_text(repo_root, DATABASE_PATH)
    capabilities = read_text(repo_root, CAPABILITY_PATH)

    version_name = _require_match(r'versionName\s*=\s*"([^"]+)"', gradle, "versionName")
    version_code = int(_require_match(r"versionCode\s*=\s*(\d+)", gradle, "versionCode"))
    package_name = _require_match(r'applicationId\s*=\s*"([^"]+)"', gradle, "applicationId")
    schema_version = int(_require_match(r"version\s*=\s*(\d+),", database, "Room schema version"))

    # Sources the capability registry keeps off the Play channel. Aura's own
    # YouTube risk profile is the reason this exists, so it is derived rather
    # than restated. Parsed per registry entry: a single regex across the whole
    # file would pair the first source with a later entry's channel set.
    play_excluded = []
    for block in capabilities.split("ProviderCapability(")[1:]:
        source_match = re.search(r"source = ContentSource\.(\w+),", block)
        if not source_match:
            continue
        channels_match = re.search(r"channels = (\w+),", block)
        if channels_match and channels_match.group(1) == "GITHUB_ONLY":
            play_excluded.append(source_match.group(1))
    play_excluded = sorted(set(play_excluded))

    return {
        "packageName": package_name,
        "versionName": version_name,
        "versionCode": version_code,
        "roomSchemaVersion": schema_version,
        "playExcludedSources": play_excluded,
    }


def check_readme_badge(repo_root: Path, manifest: dict[str, Any]) -> list[str]:
    readme = read_text(repo_root, README_PATH)
    match = README_VERSION_BADGE.search(readme)
    if not match:
        return [f"{README_PATH}: version badge not found"]
    if match.group(2) != manifest["versionName"]:
        return [
            f"{README_PATH}: version badge is {match.group(2)}, "
            f"expected {manifest['versionName']}"
        ]
    return []


def check_changelog(repo_root: Path, manifest: dict[str, Any]) -> list[str]:
    changelog = read_text(repo_root, CHANGELOG_PATH)
    heading = f"## v{manifest['versionName']}"
    if heading not in changelog:
        return [f"{CHANGELOG_PATH}: missing a '{heading}' section"]
    return []


def check_policy(repo_root: Path, manifest: dict[str, Any]) -> list[str]:
    policy = read_json(repo_root, POLICY_PATH)
    problems: list[str] = []
    for key in ("packageName", "versionName", "versionCode"):
        if policy.get(key) != manifest[key]:
            problems.append(
                f"{POLICY_PATH}: {key} is {policy.get(key)!r}, expected {manifest[key]!r}"
            )
    return problems


def check_fastlane_changelog(repo_root: Path, manifest: dict[str, Any]) -> list[str]:
    path = repo_root / FASTLANE_CHANGELOG_DIR / f"{manifest['versionCode']}.txt"
    if not path.is_file():
        return [
            f"{FASTLANE_CHANGELOG_DIR}/{manifest['versionCode']}.txt: missing changelog "
            f"for versionCode {manifest['versionCode']}"
        ]
    if not path.read_text(encoding="utf-8").strip():
        return [f"{FASTLANE_CHANGELOG_DIR}/{manifest['versionCode']}.txt: is empty"]
    return []


def check_youtube_risk(repo_root: Path, manifest: dict[str, Any]) -> list[str]:
    """The YouTube store-risk check is mandatory, not advisory.

    The registry decides which sources may ship on Play. If YouTube ever stops
    being excluded there, the risk profile must have been re-reviewed first.
    """
    profile = read_json(repo_root, YOUTUBE_RISK_PATH)
    problems: list[str] = []
    if "YOUTUBE" not in manifest["playExcludedSources"]:
        approval = profile.get("ownerApprovedEvidence")
        if not approval:
            problems.append(
                f"{YOUTUBE_RISK_PATH}: YouTube is no longer excluded from the Play channel, "
                "but the profile records no owner-approved evidence"
            )
    return problems


def validate(repo_root: Path) -> dict[str, Any]:
    manifest = read_manifest(repo_root)
    problems: list[str] = []
    problems += check_policy(repo_root, manifest)
    problems += check_readme_badge(repo_root, manifest)
    problems += check_changelog(repo_root, manifest)
    problems += check_fastlane_changelog(repo_root, manifest)
    problems += check_youtube_risk(repo_root, manifest)
    if problems:
        raise ReleaseManifestError("release manifest drift: " + "; ".join(problems))
    return {"status": "ok", **manifest}


def write_derived(
    repo_root: Path,
    changelog_body: str | None = None,
    highlights: str | None = None,
) -> dict[str, Any]:
    """Rewrites every derived artifact from the manifest."""
    manifest = read_manifest(repo_root)

    policy_path = repo_root / POLICY_PATH
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    policy["packageName"] = manifest["packageName"]
    policy["versionName"] = manifest["versionName"]
    policy["versionCode"] = manifest["versionCode"]
    policy_path.write_text(
        json.dumps(policy, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n"
    )

    readme_path = repo_root / README_PATH
    readme = readme_path.read_text(encoding="utf-8")
    updated = README_VERSION_BADGE.sub(
        lambda m: m.group(1) + manifest["versionName"] + m.group(3), readme
    )
    if updated != readme:
        readme_path.write_text(updated, encoding="utf-8", newline="\n")

    changelog_path = repo_root / FASTLANE_CHANGELOG_DIR / f"{manifest['versionCode']}.txt"
    if changelog_body is not None or not changelog_path.is_file():
        # The store preflight requires the versionName inside the body, so the
        # heading is generated rather than left to whoever writes the notes.
        heading = f"Aura v{manifest['versionName']}"
        body = heading if not changelog_body else heading + "\n\n" + changelog_body.strip()
        # The store preflight also requires a "Recent highlights:" line, so the
        # generator supplies one rather than trusting each release to remember.
        if "Recent highlights:" not in body:
            body += "\n\nRecent highlights: " + (highlights or f"see Aura v{manifest['versionName']}.")
        changelog_path.parent.mkdir(parents=True, exist_ok=True)
        changelog_path.write_text(body.strip() + "\n", encoding="utf-8", newline="\n")

    return {"status": "written", **manifest}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("check", "write"), default="check")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument(
        "--changelog-body",
        default=None,
        help="Fastlane changelog text for the current versionCode (write mode).",
    )
    parser.add_argument(
        "--highlights",
        default=None,
        help="Text for the required Recent highlights line (write mode).",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    try:
        if args.mode == "write":
            result = write_derived(repo_root, args.changelog_body, args.highlights)
        else:
            result = validate(repo_root)
    except ReleaseManifestError as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
