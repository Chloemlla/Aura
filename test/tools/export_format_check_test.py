from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path

from tools.export_format_check import ExportFormatError, validate_export_format

REPO_ROOT = Path(__file__).resolve().parents[2]
SPEC = "docs/data/export-format.json"


def fixture(destination: Path) -> dict[str, object]:
    shutil.copytree(
        REPO_ROOT / "app/src/main/java/com/chloemlla/aura/service",
        destination / "app/src/main/java/com/chloemlla/aura/service",
    )
    spec_path = destination / SPEC
    spec_path.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(REPO_ROOT / SPEC, spec_path)
    return json.loads(spec_path.read_text(encoding="utf-8"))


class ExportFormatCheckTest(unittest.TestCase):
    def test_live_export_contract_passes(self) -> None:
        result = validate_export_format(REPO_ROOT, SPEC)

        self.assertEqual("ok", result["status"])
        self.assertEqual(3, result["formatCount"])
        self.assertGreaterEqual(result["limitCount"], 15)

    def test_unknown_limit_key_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            spec = fixture(root)
            spec["formats"]["favorites"]["limitKeys"].append("MADE_UP_LIMIT")  # type: ignore[index]
            (root / SPEC).write_text(json.dumps(spec), encoding="utf-8")

            with self.assertRaisesRegex(ExportFormatError, "unknown limit key"):
                validate_export_format(root, SPEC)

    def test_undocumented_contract_constant_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            spec = fixture(root)
            spec["documentedLimitKeys"].remove("MAX_FAVORITES")  # type: ignore[union-attr]
            (root / SPEC).write_text(json.dumps(spec), encoding="utf-8")

            with self.assertRaisesRegex(ExportFormatError, "undocumented transfer limits"):
                validate_export_format(root, SPEC)

    def test_silent_truncation_policy_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            spec = fixture(root)
            spec["formats"]["favorites"]["importValidation"].append(  # type: ignore[index]
                "Extra rows are silently dropped"
            )
            (root / SPEC).write_text(json.dumps(spec), encoding="utf-8")

            with self.assertRaisesRegex(ExportFormatError, "silent truncation"):
                validate_export_format(root, SPEC)

    def test_runtime_item_truncation_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            fixture(root)
            source = root / "app/src/main/java/com/chloemlla/aura/service/CollectionExporter.kt"
            source.write_text(
                source.read_text(encoding="utf-8")
                + "\nval truncated = items.take(LibraryTransferContract.MAX_COLLECTION_ITEMS)\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ExportFormatError, "truncates a transfer"):
                validate_export_format(root, SPEC)


if __name__ == "__main__":
    unittest.main()
