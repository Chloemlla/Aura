from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.published_state import (
    PublishedStateError,
    assert_tag_exists,
    assert_tracked,
    is_git_repository,
    is_tracked,
    tag_exists,
)


REPO_ROOT = Path(__file__).resolve().parents[2]


def git(root: Path, *args: str) -> None:
    subprocess.run(
        ["git", "-C", str(root), *args],
        check=True,
        capture_output=True,
        text=True,
    )


class PublishedStateTest(unittest.TestCase):
    def _scratch_repo(self) -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        git(root, "init", "-q")
        git(root, "config", "user.email", "gate@example.invalid")
        git(root, "config", "user.name", "Gate Fixture")
        return root

    def test_live_repository_is_a_git_checkout(self) -> None:
        self.assertTrue(is_git_repository(REPO_ROOT))

    def test_live_privacy_policy_is_tracked(self) -> None:
        self.assertTrue(is_tracked(REPO_ROOT, "docs/privacy/privacy-policy.md"))

    def test_untracked_file_is_rejected(self) -> None:
        root = self._scratch_repo()
        (root / "docs").mkdir()
        (root / "docs" / "policy.md").write_text("# policy\n", encoding="utf-8")

        self.assertFalse(is_tracked(root, "docs/policy.md"))
        with self.assertRaises(PublishedStateError) as ctx:
            assert_tracked(root, "docs/policy.md", "policy")

        self.assertIn("not tracked in git", str(ctx.exception))
        self.assertIn("404", str(ctx.exception))

    def test_tracked_file_is_accepted(self) -> None:
        root = self._scratch_repo()
        (root / "docs").mkdir()
        (root / "docs" / "policy.md").write_text("# policy\n", encoding="utf-8")
        git(root, "add", "docs/policy.md")

        assert_tracked(root, "docs/policy.md", "policy")
        self.assertTrue(is_tracked(root, "docs/policy.md"))

    def test_missing_file_is_rejected(self) -> None:
        root = self._scratch_repo()

        with self.assertRaises(PublishedStateError) as ctx:
            assert_tracked(root, "docs/absent.md", "policy")

        self.assertIn("is missing", str(ctx.exception))

    def test_ignored_file_is_rejected_even_though_it_exists(self) -> None:
        root = self._scratch_repo()
        (root / ".gitignore").write_text("*.md\n", encoding="utf-8")
        (root / "docs").mkdir()
        (root / "docs" / "policy.md").write_text("# policy\n", encoding="utf-8")
        git(root, "add", "-A")

        with self.assertRaises(PublishedStateError):
            assert_tracked(root, "docs/policy.md", "policy")

    def test_non_git_directory_is_skipped(self) -> None:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        (root / "docs").mkdir()
        (root / "docs" / "policy.md").write_text("# policy\n", encoding="utf-8")

        self.assertFalse(is_git_repository(root))
        assert_tracked(root, "docs/policy.md", "policy")

    def test_missing_tag_is_rejected(self) -> None:
        root = self._scratch_repo()
        (root / "file.txt").write_text("x\n", encoding="utf-8")
        git(root, "add", "file.txt")
        git(root, "commit", "-qm", "seed")

        self.assertFalse(tag_exists(root, "v9.9.9"))
        with self.assertRaises(PublishedStateError) as ctx:
            assert_tag_exists(root, "v9.9.9", "release")

        self.assertIn("claimed but never released", str(ctx.exception))

    def test_present_tag_is_accepted(self) -> None:
        root = self._scratch_repo()
        (root / "file.txt").write_text("x\n", encoding="utf-8")
        git(root, "add", "file.txt")
        git(root, "commit", "-qm", "seed")
        git(root, "tag", "v1.2.3")

        assert_tag_exists(root, "v1.2.3", "release")
        self.assertTrue(tag_exists(root, "v1.2.3"))


if __name__ == "__main__":
    unittest.main()
