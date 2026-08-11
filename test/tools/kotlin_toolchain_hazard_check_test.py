from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.kotlin_toolchain_hazard_check import (
    HAZARDS,
    KotlinToolchainHazardError,
    validate_kotlin_hazards,
)


REPO_ROOT = Path(__file__).resolve().parents[2]


class KotlinToolchainHazardCheckTest(unittest.TestCase):
    def _repo_with(self, source: str) -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        target = root / "app" / "src" / "main" / "Sample.kt"
        target.parent.mkdir(parents=True)
        target.write_text(source, encoding="utf-8")
        return root

    def test_live_sources_are_free_of_hazards(self) -> None:
        result = validate_kotlin_hazards(REPO_ROOT)

        self.assertEqual("ok", result["status"])
        self.assertGreater(result["scannedFileCount"], 300)

    def test_every_hazard_names_a_replacement(self) -> None:
        for hazard in HAZARDS:
            self.assertTrue(hazard["pattern"])
            self.assertTrue(hazard["replacement"])
            self.assertTrue(hazard["reason"])

    def test_rejects_grouping_by(self) -> None:
        root = self._repo_with(
            "fun tally(xs: List<String>) = xs.groupingBy { it }.eachCount()\n"
        )

        with self.assertRaises(KotlinToolchainHazardError) as ctx:
            validate_kotlin_hazards(root)

        message = str(ctx.exception)
        self.assertIn("app/src/main/Sample.kt:1", message)
        self.assertIn(".groupingBy", message)
        self.assertIn("groupBy", message)

    def test_accepts_group_by(self) -> None:
        root = self._repo_with(
            "fun tally(xs: List<String>) = xs.groupBy { it }.mapValues { it.value.size }\n"
        )

        result = validate_kotlin_hazards(root)

        self.assertEqual("ok", result["status"])

    def test_scanner_finding_nothing_is_an_error(self) -> None:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)

        with self.assertRaises(KotlinToolchainHazardError) as ctx:
            validate_kotlin_hazards(Path(tmpdir.name))

        self.assertIn("not reading anything", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
