from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.dependency_info_block_check import (
    DependencyInfoBlockError,
    validate_dependency_info_block,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
LIVE_BUILD_GRADLE = REPO_ROOT / "app" / "build.gradle.kts"


class DependencyInfoBlockCheckTest(unittest.TestCase):
    def test_live_dependency_info_block_is_disabled(self) -> None:
        result = validate_dependency_info_block(LIVE_BUILD_GRADLE)
        self.assertEqual("ok", result["status"])
        self.assertFalse(result["includeInApk"])
        self.assertFalse(result["includeInBundle"])

    def test_rejects_missing_block(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "build.gradle.kts"
            path.write_text("android { compileSdk = 36 }\n", encoding="utf-8")
            with self.assertRaises(DependencyInfoBlockError):
                validate_dependency_info_block(path)

    def test_rejects_enabled_apk(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "build.gradle.kts"
            path.write_text(
                "dependenciesInfo { includeInApk = true\n includeInBundle = false }",
                encoding="utf-8",
            )
            with self.assertRaises(DependencyInfoBlockError):
                validate_dependency_info_block(path)

    def test_rejects_enabled_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            path = Path(tmpdir) / "build.gradle.kts"
            path.write_text(
                "dependenciesInfo { includeInApk = false\n includeInBundle = true }",
                encoding="utf-8",
            )
            with self.assertRaises(DependencyInfoBlockError):
                validate_dependency_info_block(path)


if __name__ == "__main__":
    unittest.main()
