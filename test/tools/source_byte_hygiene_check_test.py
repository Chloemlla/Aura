from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from tools.source_byte_hygiene_check import (
    SourceByteHygieneError,
    inspect_file,
    tracked_text_files,
    validate_source_bytes,
)


REPO_ROOT = Path(__file__).resolve().parents[2]


def git(root: Path, *args: str) -> None:
    subprocess.run(
        ["git", "-C", str(root), *args],
        check=True,
        capture_output=True,
        text=True,
    )


class SourceByteHygieneCheckTest(unittest.TestCase):
    def _repo_with(self, name: str, payload: bytes, *, attributes: str = "* text=auto eol=lf\n") -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        git(root, "init", "-q")
        git(root, "config", "user.email", "gate@example.invalid")
        git(root, "config", "user.name", "Gate Fixture")
        (root / ".gitattributes").write_bytes(attributes.encode("utf-8"))
        target = root / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(payload)
        git(root, "add", "-A")
        return root

    def test_live_repository_is_clean(self) -> None:
        result = validate_source_bytes(REPO_ROOT)

        self.assertEqual("ok", result["status"])
        self.assertGreater(result["scannedFileCount"], 500)

    def test_live_repository_has_no_carriage_returns_outside_batch_files(self) -> None:
        offenders = [
            path
            for path in tracked_text_files(REPO_ROOT)
            if not path.endswith(".bat")
            and (REPO_ROOT / path).is_file()
            and b"\r" in (REPO_ROOT / path).read_bytes()
        ]

        self.assertEqual([], offenders)

    def test_rejects_a_nul_byte(self) -> None:
        root = self._repo_with("src/Guard.kt", b"val x = '\x00'\n")

        with self.assertRaises(SourceByteHygieneError) as ctx:
            validate_source_bytes(root)

        self.assertIn("NUL byte at offset", str(ctx.exception))
        self.assertIn("ripgrep", str(ctx.exception))

    def test_rejects_a_replacement_character(self) -> None:
        root = self._repo_with("src/Note.kt", "// broken � comment\n".encode("utf-8"))

        with self.assertRaises(SourceByteHygieneError) as ctx:
            validate_source_bytes(root)

        self.assertIn("U+FFFD replacement character", str(ctx.exception))

    def test_rejects_invalid_utf8(self) -> None:
        root = self._repo_with("src/Bad.kt", b"val s = \"\xff\xfe\"\n")

        with self.assertRaises(SourceByteHygieneError) as ctx:
            validate_source_bytes(root)

        self.assertIn("not valid UTF-8", str(ctx.exception))

    def test_rejects_carriage_returns_in_a_source_file(self) -> None:
        root = self._repo_with("src/Windows.kt", b"line one\r\nline two\r\n")

        with self.assertRaises(SourceByteHygieneError) as ctx:
            validate_source_bytes(root)

        self.assertIn("carriage returns", str(ctx.exception))

    def test_allows_carriage_returns_in_batch_files(self) -> None:
        problems = inspect_file(REPO_ROOT, "gradlew.bat")

        self.assertEqual([], problems)

    def test_accepts_clean_source(self) -> None:
        root = self._repo_with("src/Clean.kt", b"val greeting = \"hi\"\n")

        result = validate_source_bytes(root)

        self.assertEqual("ok", result["status"])
        self.assertGreaterEqual(result["scannedFileCount"], 1)

    def test_binary_files_are_not_scanned(self) -> None:
        root = self._repo_with(
            "assets/logo.png",
            b"\x89PNG\r\n\x1a\n\x00\x00",
            attributes="* text=auto eol=lf\n*.png binary\n",
        )

        result = validate_source_bytes(root)

        self.assertEqual("ok", result["status"])
        self.assertNotIn("assets/logo.png", tracked_text_files(root))


if __name__ == "__main__":
    unittest.main()
