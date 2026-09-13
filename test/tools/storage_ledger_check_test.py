from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CHECKER = REPO_ROOT / "tools/storage_ledger_check.py"

RULES = """<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <include domain="sharedpref" path="freevibe_locale.xml" />
    </cloud-backup>
    <device-transfer>
        <include domain="sharedpref" path="freevibe_locale.xml" />
    </device-transfer>
</data-extraction-rules>
"""


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def storage_ledger() -> dict:
    return {
        "status": "checked",
        "stores": [
            {
                "id": "room_database",
                "path": "databases/freevibe.db",
                "backupExcluded": True,
                "sourceFile": "Database.kt",
            },
            {
                "id": "mediastore_images",
                "path": "MediaStore Images (external)",
                "backupExcluded": False,
                "sourceFile": "DownloadManager.kt",
            },
        ],
        "tempDirectories": [
            {"path": "cacheDir/trimmed", "sourceFile": "AudioTrimmer.kt"},
        ],
        "sharedPreferencesFiles": ["freevibe_locale.xml"],
    }


def run_checker(cwd: Path) -> subprocess.CompletedProcess:
    # The checker derives its repo root from __file__, so the live case runs the
    # committed script in place and fixture cases run a copy under a temp tree.
    return subprocess.run(
        [sys.executable, str(cwd / "tools" / CHECKER.name)],
        cwd=str(cwd),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )


class StorageLedgerCheckTest(unittest.TestCase):
    def _fixture(self) -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        write_text(root / "tools" / CHECKER.name, CHECKER.read_text(encoding="utf-8"))
        write_text(root / "app/src/main/res/xml/data_extraction_rules.xml", RULES)
        write_text(
            root / "app/src/main/java/com/chloemlla/aura/data/local/Database.kt",
            "package com.chloemlla.aura.data.local\n",
        )
        write_text(
            root / "app/src/main/java/com/chloemlla/aura/service/AudioTrimmer.kt",
            "package com.chloemlla.aura.service\n",
        )
        write_text(
            root / "app/src/main/java/com/chloemlla/aura/service/DownloadManager.kt",
            "package com.chloemlla.aura.service\n",
        )
        self.write_ledger(root, storage_ledger())
        return root

    def write_ledger(self, root: Path, ledger: dict) -> None:
        write_text(root / "docs/privacy/storage-ledger.json", json.dumps(ledger, indent=2))

    def write_rules(self, root: Path, text: str) -> None:
        write_text(root / "app/src/main/res/xml/data_extraction_rules.xml", text)

    def test_live_repository_passes(self) -> None:
        result = run_checker(REPO_ROOT)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("OK: storage ledger is consistent", result.stdout)

    def test_fixture_baseline_passes(self) -> None:
        result = run_checker(self._fixture())

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_finds_sources_under_renamed_package(self) -> None:
        # Pins source lookup to app/src/main/java/com/chloemlla/aura. If the path
        # reverts to the pre-rename com/freevibe package every source is missing.
        result = run_checker(self._fixture())

        self.assertNotIn("references missing source", result.stdout)

    def test_rejects_missing_source_file(self) -> None:
        root = self._fixture()
        (root / "app/src/main/java/com/chloemlla/aura/data/local/Database.kt").unlink()

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("references missing source: Database.kt", result.stdout)

    def test_rejects_allowlisted_backup_excluded_store(self) -> None:
        root = self._fixture()
        self.write_rules(
            root,
            RULES.replace(
                '<include domain="sharedpref" path="freevibe_locale.xml" />',
                '<include domain="database" path="freevibe.db" />',
                1,
            ),
        )

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("backup-excluded but allowlisted", result.stdout)
        self.assertIn("room_database", result.stdout)

    def test_excluded_database_is_not_confused_with_allowlisted_locale_prefs(self) -> None:
        # freevibe.db (excluded) and freevibe_locale.xml (allowlisted) share a prefix
        # but are different stores, so the allowlist entry must not flag the database.
        root = self._fixture()

        result = run_checker(root)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("room_database", result.stdout)

    def test_rejects_ledger_status_not_checked(self) -> None:
        root = self._fixture()
        ledger = storage_ledger()
        ledger["status"] = "draft"
        self.write_ledger(root, ledger)

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("status is not 'checked'", result.stdout)

    def test_rejects_missing_backup_rules_file(self) -> None:
        root = self._fixture()
        (root / "app/src/main/res/xml/data_extraction_rules.xml").unlink()

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("Missing data_extraction_rules.xml", result.stdout)

    def test_rejects_allowlisted_prefs_missing_from_ledger(self) -> None:
        root = self._fixture()
        ledger = storage_ledger()
        ledger["sharedPreferencesFiles"] = []
        self.write_ledger(root, ledger)

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn(
            "SharedPreferences 'freevibe_locale.xml' in backup rules but not in storage ledger",
            result.stdout,
        )

    def test_reports_allowlisted_prefs_once_across_backup_domains(self) -> None:
        # The fixture allowlists the same prefs file under both cloud-backup and
        # device-transfer; that is one gap, not two lines of noise.
        root = self._fixture()
        ledger = storage_ledger()
        ledger["sharedPreferencesFiles"] = []
        self.write_ledger(root, ledger)

        result = run_checker(root)

        self.assertEqual(
            1,
            result.stdout.count("in backup rules but not in storage ledger"),
            result.stdout,
        )

    def test_rejects_missing_temp_directory_source(self) -> None:
        root = self._fixture()
        (root / "app/src/main/java/com/chloemlla/aura/service/AudioTrimmer.kt").unlink()

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("references missing source: AudioTrimmer.kt", result.stdout)


if __name__ == "__main__":
    unittest.main()
