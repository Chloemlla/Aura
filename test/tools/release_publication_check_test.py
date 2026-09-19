from __future__ import annotations

import re
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from unittest.mock import patch

from tools.published_state import PublishedStateError, release_published
from tools.release_publication_check import (
    APP_GRADLE,
    CHECKSUM_ASSET,
    REQUIRED_ABIS,
    ReleasePublicationError,
    declared_version,
    expected_apk_names,
    publication_tag,
    validate_release_assets,
    validate_release_publication,
)


REPO_ROOT = Path(__file__).resolve().parents[2]

# The release workflow's `${GITHUB_RUN_NUMBER}-${GITHUB_SHA::8}` suffix.
FORK_SUFFIX_RE = re.compile(r"\.[0-9]+-[0-9a-f]{7,40}$")


def release_base(tag: str) -> str:
    """The version a tag names, without the workflow's run-and-hash suffix."""
    return FORK_SUFFIX_RE.sub("", tag[1:] if tag.startswith("v") else tag)


def git(root: Path, *args: str) -> None:
    subprocess.run(["git", "-C", str(root), *args], check=True, capture_output=True, text=True)


class ReleasePublicationCheckTest(unittest.TestCase):
    def _scratch_repo(self, version: str = "9.9.9", *, tag: bool) -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        git(root, "init", "-q")
        git(root, "config", "user.email", "gate@example.invalid")
        git(root, "config", "user.name", "Gate Fixture")
        gradle = root / APP_GRADLE
        gradle.parent.mkdir(parents=True, exist_ok=True)
        gradle.write_text(
            f'android {{\n    versionCode = 1\n    versionName = "{version}"\n}}\n',
            encoding="utf-8",
        )
        git(root, "add", APP_GRADLE)
        git(root, "commit", "-qm", "fixture")
        if tag:
            git(root, "tag", f"v{version}")
        return root

    def _scratch_repo_tagged(self, version: str, *tags: str) -> Path:
        """A scratch repo that carries exactly the named tags and no other."""
        root = self._scratch_repo(version, tag=False)
        for name in tags:
            git(root, "tag", name)
        return root

    def test_live_declared_version_is_tagged_and_released(self) -> None:
        version = declared_version(REPO_ROOT)
        result = validate_release_publication(REPO_ROOT)

        self.assertEqual("ok", result["status"])
        # Either tag shape is accepted, but the version the tag names has to be
        # the declared one rather than some neighbouring release.
        self.assertEqual(version, release_base(str(result["tag"])))

    def test_reads_the_declared_version(self) -> None:
        root = self._scratch_repo("1.2.3", tag=True)

        self.assertEqual("1.2.3", declared_version(root))

    def test_accepts_the_release_workflow_tag_scheme(self) -> None:
        """The fork publishes v<versionName>.<run>-<sha>, not the bare tag."""
        root = self._scratch_repo_tagged("6.45.2", "v6.45.2.148-abcdef1")

        result = validate_release_publication(root)

        self.assertEqual("ok", result["status"])
        self.assertEqual("v6.45.2.148-abcdef1", result["tag"])

    def test_prefers_the_exact_tag_over_the_workflow_tag(self) -> None:
        root = self._scratch_repo_tagged("6.45.2", "v6.45.2.148-abcdef1", "v6.45.2")

        self.assertEqual("v6.45.2", validate_release_publication(root)["tag"])

    def test_newest_workflow_tag_speaks_for_the_version(self) -> None:
        """Every publish on one versionName reuses the base, so the newest run wins."""
        root = self._scratch_repo_tagged(
            "6.45.2", "v6.45.2.148-abcdef1", "v6.45.2.200-abcdef2"
        )

        self.assertEqual("v6.45.2.200-abcdef2", validate_release_publication(root)["tag"])

    def test_rejects_a_workflow_tag_for_a_different_version(self) -> None:
        root = self._scratch_repo_tagged("6.45.2", "v6.45.1.148-abcdef1")

        with self.assertRaises(ReleasePublicationError) as ctx:
            validate_release_publication(root)

        self.assertIn("no git tag v6.45.2", str(ctx.exception))

    def test_rejects_a_longer_version_that_shares_the_prefix(self) -> None:
        """A prefix test would read v6.45.20.1 as 6.45.2; the base must match exactly."""
        root = self._scratch_repo_tagged("6.45.2", "v6.45.20.1-abcdef1")

        with self.assertRaises(ReleasePublicationError) as ctx:
            validate_release_publication(root)

        self.assertIn("no git tag v6.45.2", str(ctx.exception))

    def test_rejects_a_workflow_tag_without_a_hex_hash(self) -> None:
        root = self._scratch_repo_tagged("6.45.2", "v6.45.2.148-release")

        with self.assertRaises(ReleasePublicationError) as ctx:
            validate_release_publication(root)

        self.assertIn("no git tag v6.45.2", str(ctx.exception))

    def test_rejects_a_workflow_tag_with_no_published_release(self) -> None:
        """A tag is not a Release, whichever shape the tag takes."""
        root = self._scratch_repo_tagged("6.45.2", "v6.45.2.148-abcdef1")

        with mock.patch("tools.published_state.release_published", return_value=False):
            with self.assertRaises(ReleasePublicationError) as ctx:
                validate_release_publication(root)

        self.assertIn("no published GitHub Release", str(ctx.exception))

    def test_a_workflow_tag_passes_when_the_release_cannot_be_checked(self) -> None:
        """An unreachable GitHub stays an unknown for the fork's tag shape too."""
        root = self._scratch_repo_tagged("6.45.2", "v6.45.2.148-abcdef1")

        result = validate_release_publication(root)

        self.assertEqual("ok", result["status"])
        self.assertEqual("unknown", result["releasePublished"])

    def test_rejects_a_version_that_was_never_tagged(self) -> None:
        root = self._scratch_repo(tag=False)

        with self.assertRaises(ReleasePublicationError) as ctx:
            validate_release_publication(root)

        self.assertIn("no git tag v9.9.9", str(ctx.exception))

    def test_rejects_a_gradle_file_without_a_version(self) -> None:
        root = self._scratch_repo(tag=True)
        (root / APP_GRADLE).write_text("android {\n}\n", encoding="utf-8")

        with self.assertRaises(ReleasePublicationError) as ctx:
            validate_release_publication(root)

        self.assertIn("declares no versionName", str(ctx.exception))

    def test_a_tagged_version_passes_when_the_release_cannot_be_checked(self) -> None:
        """An unreachable GitHub is an unknown, never a reported failure."""
        root = self._scratch_repo(tag=True)

        result = validate_release_publication(root)

        self.assertEqual("ok", result["status"])
        self.assertEqual("unknown", result["releasePublished"])

    def test_unknown_is_distinct_from_absent(self) -> None:
        """A scratch repo has no GitHub remote, so the answer must be None."""
        root = self._scratch_repo(tag=True)

        self.assertIsNone(release_published(root, "v9.9.9"))

    def test_live_repository_reports_a_real_published_release(self) -> None:
        version = declared_version(REPO_ROOT)
        try:
            tag = publication_tag(REPO_ROOT, version, f"declared version {version}")
        except PublishedStateError:
            # No tag at all is the failure the live gate test above reports; this
            # test is about the release behind a tag that does exist.
            self.skipTest(f"the live repository has tagged no release of {version}")

        state = release_published(REPO_ROOT, tag)

        # None means gh is unavailable in this environment, which is a legitimate
        # skip; False would mean the declared version is genuinely unpublished.
        if state is None:
            self.skipTest("gh unavailable; published-release state not checkable here")
        self.assertTrue(state)

    def test_assets_unknown_when_github_unreachable(self) -> None:
        root = self._scratch_repo(tag=True)
        result = validate_release_assets(root, "v9.9.9", "9.9.9", 1)
        self.assertEqual("unknown", result["assetsValid"])

    def test_strict_fails_when_github_unreachable(self) -> None:
        root = self._scratch_repo(tag=True)
        with self.assertRaises(ReleasePublicationError):
            validate_release_assets(root, "v9.9.9", "9.9.9", 1, strict=True)

    def test_rejects_missing_assets(self) -> None:
        root = self._scratch_repo(tag=True)
        partial_assets = [{"name": "SHA256SUMS.txt"}]
        with patch("tools.release_publication_check.release_assets", return_value=partial_assets):
            with self.assertRaises(ReleasePublicationError) as ctx:
                validate_release_assets(root, "v9.9.9", "9.9.9", 1)
            self.assertIn("missing required assets", str(ctx.exception))

    def test_rejects_unexpected_apk(self) -> None:
        root = self._scratch_repo(tag=True)
        full = [{"name": n} for n in expected_apk_names("9.9.9", 1)]
        full.append({"name": CHECKSUM_ASSET})
        full.append({"name": "Aura-v9.9.9-versionCode-1-rogue-release.apk"})
        with patch("tools.release_publication_check.release_assets", return_value=full):
            with self.assertRaises(ReleasePublicationError) as ctx:
                validate_release_assets(root, "v9.9.9", "9.9.9", 1)
            self.assertIn("unexpected APK", str(ctx.exception))

    def test_accepts_complete_asset_set(self) -> None:
        root = self._scratch_repo(tag=True)
        full = [{"name": n} for n in expected_apk_names("9.9.9", 1)]
        full.append({"name": CHECKSUM_ASSET})
        with patch("tools.release_publication_check.release_assets", return_value=full):
            result = validate_release_assets(root, "v9.9.9", "9.9.9", 1)
            self.assertTrue(result["assetsValid"])
            self.assertEqual(6, result["assetCount"])

    def test_schema_version_is_two(self) -> None:
        result = validate_release_publication(REPO_ROOT)
        self.assertEqual(2, result["schemaVersion"])


if __name__ == "__main__":
    unittest.main()
