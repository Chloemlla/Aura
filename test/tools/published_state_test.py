from __future__ import annotations

import http.server
import subprocess
import tempfile
import threading
import unittest
from pathlib import Path

from tools.published_state import (
    PublishedStateError,
    assert_enforcement_mechanism,
    assert_resolves_over_http,
    assert_tag_exists,
    assert_tracked,
    is_git_repository,
    is_tracked,
    tag_exists,
    url_resolves,
)


REPO_ROOT = Path(__file__).resolve().parents[2]


class _StubHandler(http.server.BaseHTTPRequestHandler):
    """Answers whatever status the path asks for, so every branch is reachable."""

    def _status_for_path(self) -> int:
        try:
            return int(self.path.strip("/"))
        except ValueError:
            return 200

    def do_HEAD(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler naming
        self.send_response(self._status_for_path())
        self.send_header("Content-Length", "0")
        self.end_headers()

    do_GET = do_HEAD

    def log_message(self, *args: object) -> None:
        pass


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


class UrlResolutionTest(unittest.TestCase):
    """A link check that never fails on a dead link is decoration, so prove it fails."""

    def setUp(self) -> None:
        self.server = http.server.HTTPServer(("127.0.0.1", 0), _StubHandler)
        thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def test_a_served_document_resolves(self) -> None:
        self.assertTrue(url_resolves(f"{self.base}/200"))
        assert_resolves_over_http(f"{self.base}/200", "policy")

    def test_a_404_fails_the_gate(self) -> None:
        self.assertFalse(url_resolves(f"{self.base}/404"))
        with self.assertRaises(PublishedStateError):
            assert_resolves_over_http(f"{self.base}/404", "policy")

    def test_a_410_is_also_definitively_gone(self) -> None:
        with self.assertRaises(PublishedStateError):
            assert_resolves_over_http(f"{self.base}/410", "policy")

    def test_a_server_error_is_unknown_rather_than_a_dead_link(self) -> None:
        # The document may be perfectly fine; the server declined to say. Failing
        # here would break builds over someone else's outage.
        self.assertIsNone(url_resolves(f"{self.base}/500"))
        assert_resolves_over_http(f"{self.base}/500", "policy")

    def test_a_host_that_cannot_be_reached_is_unknown(self) -> None:
        self.assertIsNone(url_resolves("http://127.0.0.1:1/gone", timeout=0.5))
        assert_resolves_over_http("http://127.0.0.1:1/gone", "policy", timeout=0.5)

    def test_a_malformed_url_is_unknown_rather_than_an_exception(self) -> None:
        self.assertIsNone(url_resolves("not-a-url"))


class EnforcementMechanismTest(unittest.TestCase):
    """A policy that says something enforces it must name a mechanism that exists."""

    def _scratch_repo(self) -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        git(root, "init", "-q")
        git(root, "config", "user.email", "gate@example.invalid")
        git(root, "config", "user.name", "Gate Fixture")
        return root

    def test_a_tracked_mechanism_satisfies_the_claim(self) -> None:
        root = self._scratch_repo()
        (root / "gate.py").write_text("pass\n", encoding="utf-8")
        git(root, "add", "gate.py")
        git(root, "commit", "-qm", "seed")

        assert_enforcement_mechanism(root, "localGateEnforced", ["gate.py"], "policy")

    def test_claiming_enforcement_while_naming_nothing_fails(self) -> None:
        root = self._scratch_repo()

        with self.assertRaises(PublishedStateError):
            assert_enforcement_mechanism(root, "releaseWorkflowEnforced", [], "policy")

    def test_a_deleted_mechanism_fails(self) -> None:
        """The native-alignment case: the workflow was removed, the claim was not."""
        root = self._scratch_repo()
        (root / "gate.py").write_text("pass\n", encoding="utf-8")
        git(root, "add", "gate.py")
        git(root, "commit", "-qm", "seed")

        with self.assertRaises(PublishedStateError):
            assert_enforcement_mechanism(
                root,
                "releaseWorkflowEnforced",
                [".github/workflows/release.yml"],
                "policy",
            )

    def test_an_untracked_mechanism_fails(self) -> None:
        root = self._scratch_repo()
        (root / "gate.py").write_text("pass\n", encoding="utf-8")

        with self.assertRaises(PublishedStateError):
            assert_enforcement_mechanism(root, "localGateEnforced", ["gate.py"], "policy")

    def test_the_predicate_does_not_read_the_status_string(self) -> None:
        """Deciding *whether* a status makes a claim belongs to the caller.

        The predicate answers only "is this mechanism real", so it fails for a
        missing file no matter what the status says.
        """
        root = self._scratch_repo()

        with self.assertRaises(PublishedStateError):
            assert_enforcement_mechanism(root, "advisory", ["gate.py"], "policy")

    def test_outside_a_git_checkout_the_question_is_skipped(self) -> None:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)

        assert_enforcement_mechanism(Path(tmpdir.name), "localGateEnforced", [], "policy")


if __name__ == "__main__":
    unittest.main()
