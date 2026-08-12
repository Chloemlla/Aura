from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.docs_link_check import DocsLinkError, collect_references, validate_docs_links


REPO_ROOT = Path(__file__).resolve().parents[2]


def git(root: Path, *args: str) -> None:
    subprocess.run(
        ["git", "-C", str(root), *args],
        check=True,
        capture_output=True,
        text=True,
    )


class DocsLinkCheckTest(unittest.TestCase):
    def _scratch_repo(self, readme: str, *, track_doc: bool) -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        git(root, "init", "-q")
        git(root, "config", "user.email", "gate@example.invalid")
        git(root, "config", "user.name", "Gate Fixture")
        (root / "README.md").write_text(readme, encoding="utf-8")
        (root / "docs" / "privacy").mkdir(parents=True)
        (root / "docs" / "privacy" / "privacy-policy.md").write_text("# policy\n", encoding="utf-8")
        git(root, "add", "README.md")
        if track_doc:
            git(root, "add", "docs/privacy/privacy-policy.md")
        return root

    def test_live_documentation_links_resolve(self) -> None:
        result = validate_docs_links(REPO_ROOT)

        self.assertEqual("ok", result["status"])
        self.assertGreaterEqual(result["linkedDocumentCount"], 11)
        self.assertIn("docs/privacy/privacy-policy.md", result["linkedDocuments"])

    def test_live_scan_covers_readme_and_app_source(self) -> None:
        references = collect_references(REPO_ROOT)

        self.assertIn("docs/privacy/privacy-policy.md", references)
        cited_by = set()
        for sources in references.values():
            cited_by.update(sources)
        self.assertIn("README.md", cited_by)
        self.assertTrue(
            any(source.endswith(".kt") for source in cited_by),
            "app source links should be scanned, not just README",
        )

    def test_relative_markdown_link_to_untracked_doc_is_rejected(self) -> None:
        root = self._scratch_repo(
            "See [policy](docs/privacy/privacy-policy.md).\n", track_doc=False
        )

        with self.assertRaises(DocsLinkError) as ctx:
            validate_docs_links(root)

        self.assertIn("not tracked in git", str(ctx.exception))

    def test_blob_url_to_untracked_doc_is_rejected(self) -> None:
        root = self._scratch_repo(
            "See https://github.com/SysAdminDoc/Aura/blob/main/docs/privacy/privacy-policy.md\n",
            track_doc=False,
        )

        with self.assertRaises(DocsLinkError) as ctx:
            validate_docs_links(root)

        self.assertIn("docs/privacy/privacy-policy.md", str(ctx.exception))

    def test_tracked_doc_is_accepted(self) -> None:
        root = self._scratch_repo(
            "See [policy](docs/privacy/privacy-policy.md).\n", track_doc=True
        )

        result = validate_docs_links(root)

        self.assertEqual("ok", result["status"])
        self.assertEqual(1, result["linkedDocumentCount"])

    def test_link_to_a_missing_doc_is_rejected(self) -> None:
        root = self._scratch_repo("See [gone](docs/absent.md).\n", track_doc=True)

        with self.assertRaises(DocsLinkError) as ctx:
            validate_docs_links(root)

        self.assertIn("is missing", str(ctx.exception))

    def test_scanner_finding_nothing_is_an_error(self) -> None:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        (root / "README.md").write_text("no links here\n", encoding="utf-8")

        with self.assertRaises(DocsLinkError) as ctx:
            validate_docs_links(root)

        self.assertIn("not reading anything", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
