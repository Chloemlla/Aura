from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path

from tools.release_manifest import (
    ReleaseManifestError,
    read_manifest,
    validate,
    write_derived,
)

REPO_ROOT = Path(__file__).resolve().parents[2]

MIRRORED_PATHS = (
    "app/build.gradle.kts",
    "app/src/main/java/com/chloemlla/aura/data/local/Database.kt",
    "app/src/main/java/com/chloemlla/aura/data/legal/ProviderCapability.kt",
    "README.md",
    "CHANGELOG.md",
    "docs/distribution/release-metadata-consistency.json",
    "docs/distribution/youtube-store-risk-profile.json",
)


def mirror_repo(destination: Path) -> None:
    """Copies just the files the manifest reads, plus the current changelog."""
    for relative in MIRRORED_PATHS:
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(REPO_ROOT / relative, target)
    manifest = read_manifest(REPO_ROOT)
    changelog_dir = destination / "fastlane/metadata/android/en-US/changelogs"
    changelog_dir.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(
        REPO_ROOT / "fastlane/metadata/android/en-US/changelogs" / f"{manifest['versionCode']}.txt",
        changelog_dir / f"{manifest['versionCode']}.txt",
    )


class ReleaseManifestTest(unittest.TestCase):
    def test_live_repository_has_no_release_manifest_drift(self) -> None:
        result = validate(REPO_ROOT)

        self.assertEqual("ok", result["status"])

    def test_manifest_reads_facts_from_code_not_from_docs(self) -> None:
        manifest = read_manifest(REPO_ROOT)

        gradle = (REPO_ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn(f'versionName = "{manifest["versionName"]}"', gradle)
        self.assertIn(f"versionCode = {manifest['versionCode']}", gradle)
        self.assertGreater(manifest["roomSchemaVersion"], 0)
        # The registry, not this file, decides which sources are Play-excluded.
        self.assertIn("YOUTUBE", manifest["playExcludedSources"])

    def test_stale_policy_version_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            mirror_repo(repo)
            policy_path = repo / "docs/distribution/release-metadata-consistency.json"
            policy = json.loads(policy_path.read_text(encoding="utf-8"))
            policy["versionName"] = "0.0.1"
            policy_path.write_text(json.dumps(policy, indent=2), encoding="utf-8")

            with self.assertRaises(ReleaseManifestError) as raised:
                validate(repo)

            self.assertIn("versionName", str(raised.exception))

    def test_stale_readme_badge_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            mirror_repo(repo)
            readme_path = repo / "README.md"
            manifest = read_manifest(repo)
            readme_path.write_text(
                readme_path.read_text(encoding="utf-8").replace(
                    f"version-{manifest['versionName']}-blue", "version-0.0.1-blue"
                ),
                encoding="utf-8",
            )

            with self.assertRaises(ReleaseManifestError) as raised:
                validate(repo)

            self.assertIn("version badge", str(raised.exception))

    def test_missing_fastlane_changelog_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            mirror_repo(repo)
            manifest = read_manifest(repo)
            (repo / "fastlane/metadata/android/en-US/changelogs" / f"{manifest['versionCode']}.txt").unlink()

            with self.assertRaises(ReleaseManifestError) as raised:
                validate(repo)

            self.assertIn("missing changelog", str(raised.exception))

    def test_missing_changelog_section_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            mirror_repo(repo)
            (repo / "CHANGELOG.md").write_text("# Changelog\n", encoding="utf-8")

            with self.assertRaises(ReleaseManifestError) as raised:
                validate(repo)

            self.assertIn("CHANGELOG.md", str(raised.exception))

    def test_youtube_on_play_without_owner_evidence_fails(self) -> None:
        """The YouTube risk check is mandatory, not advisory."""
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            mirror_repo(repo)
            capability_path = repo / "app/src/main/java/com/chloemlla/aura/data/legal/ProviderCapability.kt"
            capability_path.write_text(
                capability_path.read_text(encoding="utf-8").replace(
                    "channels = GITHUB_ONLY,", "channels = ALL_CHANNELS,"
                ),
                encoding="utf-8",
            )

            with self.assertRaises(ReleaseManifestError) as raised:
                validate(repo)

            self.assertIn("owner-approved evidence", str(raised.exception))

    def test_write_makes_a_drifted_repo_consistent_again(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            mirror_repo(repo)
            manifest = read_manifest(repo)
            (repo / "fastlane/metadata/android/en-US/changelogs" / f"{manifest['versionCode']}.txt").unlink()
            policy_path = repo / "docs/distribution/release-metadata-consistency.json"
            policy = json.loads(policy_path.read_text(encoding="utf-8"))
            policy["versionCode"] = 1
            policy_path.write_text(json.dumps(policy, indent=2), encoding="utf-8")

            write_derived(repo, changelog_body="Test release.", highlights="test highlights.")

            self.assertEqual("ok", validate(repo)["status"])

    def test_generated_changelog_carries_the_version_and_highlights(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            mirror_repo(repo)
            manifest = read_manifest(repo)
            changelog = repo / "fastlane/metadata/android/en-US/changelogs" / f"{manifest['versionCode']}.txt"
            changelog.unlink()

            write_derived(repo, changelog_body="Body text.", highlights="highlight text.")

            written = changelog.read_text(encoding="utf-8")
            # Both are store-preflight requirements; the generator supplies them so a
            # release cannot forget one.
            self.assertIn(manifest["versionName"], written)
            self.assertIn("Recent highlights:", written)
            self.assertIn("Body text.", written)


if __name__ == "__main__":
    unittest.main()
