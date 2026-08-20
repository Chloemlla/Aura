from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.published_state import release_published
from tools.release_publication_check import (
    APP_GRADLE,
    ReleasePublicationError,
    declared_version,
    validate_release_publication,
)


REPO_ROOT = Path(__file__).resolve().parents[2]


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

    def test_live_declared_version_is_tagged_and_released(self) -> None:
        result = validate_release_publication(REPO_ROOT)

        self.assertEqual("ok", result["status"])
        self.assertEqual(f"v{declared_version(REPO_ROOT)}", result["tag"])

    def test_reads_the_declared_version(self) -> None:
        root = self._scratch_repo("1.2.3", tag=True)

        self.assertEqual("1.2.3", declared_version(root))

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
        state = release_published(REPO_ROOT, f"v{declared_version(REPO_ROOT)}")

        # None means gh is unavailable in this environment, which is a legitimate
        # skip; False would mean the declared version is genuinely unpublished.
        if state is None:
            self.skipTest("gh unavailable; published-release state not checkable here")
        self.assertTrue(state)


if __name__ == "__main__":
    unittest.main()
