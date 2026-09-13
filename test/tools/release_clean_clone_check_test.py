from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.release_clean_clone_check import (
    ReleaseCleanCloneError,
    materialize_revision,
    validate_contract,
)


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def policy() -> dict[str, object]:
    return {
        "versionName": "1.2.3",
        "versionCode": 7,
        "requiredEvidencePaths": ["required.txt", "changelog-{versionCode}.txt"],
        "trackedInputGates": [
            {"id": "fixture-gate", "command": ["tools/fixture_gate.py"]}
        ],
        "ownerOnlyEvidence": [
            {
                "id": "signing",
                "status": "unknown",
                "reason": "The fixture has no owner keystore.",
            }
        ],
    }


def complete_fixture(repo: Path) -> set[str]:
    write(repo / "required.txt", "required\n")
    write(repo / "changelog-7.txt", "release\n")
    write(repo / "tools/fixture_gate.py", "raise SystemExit(0)\n")
    write(repo / "docs/guide.md", "# Guide\n")
    return {
        "required.txt",
        "changelog-7.txt",
        "tools/fixture_gate.py",
        "docs/guide.md",
    }


class ReleaseCleanCloneCheckTest(unittest.TestCase):
    def test_contract_accepts_tracked_evidence_and_owner_unknown(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            tracked = complete_fixture(repo)

            result = validate_contract(repo, policy(), tracked)

            self.assertEqual(
                ["required.txt", "changelog-7.txt"], result["evidencePaths"]
            )
            self.assertEqual("unknown", result["ownerOnlyEvidence"][0]["status"])

    def test_existing_but_untracked_required_evidence_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            tracked = complete_fixture(repo)
            tracked.remove("required.txt")

            with self.assertRaisesRegex(
                ReleaseCleanCloneError, "required release evidence is not tracked"
            ):
                validate_contract(repo, policy(), tracked)

    def test_deleting_required_tracked_evidence_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            tracked = complete_fixture(repo)
            (repo / "required.txt").unlink()

            with self.assertRaisesRegex(
                ReleaseCleanCloneError, "required tracked release evidence is missing"
            ):
                validate_contract(repo, policy(), tracked)

    def test_document_reference_to_absent_workflow_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            tracked = complete_fixture(repo)
            write(
                repo / "docs/guide.md",
                "Run `.github/workflows/missing.yml` before release.\n",
            )

            with self.assertRaisesRegex(
                ReleaseCleanCloneError, "references absent tracked file"
            ):
                validate_contract(repo, policy(), tracked)

    def test_document_reference_to_tracked_workflow_passes(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            tracked = complete_fixture(repo)
            workflow = ".github/workflows/local.yml"
            write(repo / workflow, "name: local\n")
            write(repo / "docs/guide.md", f"Run `{workflow}` before release.\n")
            tracked.add(workflow)

            result = validate_contract(repo, policy(), tracked)

            self.assertEqual(1, result["checkedDocumentationFiles"])

    def test_owner_only_evidence_requires_a_tri_state(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = Path(tmpdir)
            tracked = complete_fixture(repo)
            invalid = policy()
            invalid["ownerOnlyEvidence"][0]["status"] = "waived"  # type: ignore[index]

            with self.assertRaisesRegex(ReleaseCleanCloneError, "pass, fail, or unknown"):
                validate_contract(repo, invalid, tracked)

    def test_materialization_uses_the_commit_not_working_tree_files(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            repo = root / "repo"
            repo.mkdir()
            commands = (
                ["git", "init", "--quiet"],
                ["git", "config", "user.name", "Fixture"],
                ["git", "config", "user.email", "fixture@example.invalid"],
            )
            for command in commands:
                subprocess.run(command, cwd=repo, check=True, capture_output=True)
            write(repo / "tracked.txt", "committed\n")
            subprocess.run(
                ["git", "add", "tracked.txt"], cwd=repo, check=True, capture_output=True
            )
            subprocess.run(
                ["git", "commit", "--quiet", "-m", "fixture"],
                cwd=repo,
                check=True,
                capture_output=True,
            )
            expected_commit = subprocess.run(
                ["git", "rev-parse", "HEAD"],
                cwd=repo,
                check=True,
                capture_output=True,
                text=True,
            ).stdout.strip()
            write(repo / "tracked.txt", "working tree edit\n")
            write(repo / "maintainer-only.txt", "must not leak\n")

            commit, tracked = materialize_revision(repo, "HEAD", root / "isolated")

            self.assertEqual(expected_commit, commit)
            self.assertEqual("committed\n", (root / "isolated/tracked.txt").read_text())
            self.assertFalse((root / "isolated/maintainer-only.txt").exists())
            self.assertEqual({"tracked.txt"}, tracked)


if __name__ == "__main__":
    unittest.main()
