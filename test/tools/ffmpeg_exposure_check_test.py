from __future__ import annotations

import json
import re
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY = REPO_ROOT / "docs" / "security" / "ffmpeg-exposure.json"
MEDIA_INGESTION = REPO_ROOT / "app" / "src" / "main" / "java" / "com" / "freevibe" / "service" / "MediaIngestion.kt"
NATIVE_COMPLIANCE = REPO_ROOT / "docs" / "legal" / "native-compliance.lock.json"

ALLOWLIST_RE = re.compile(r'FFMPEG_SAFE_EXTENSIONS\s*=\s*setOf\(([^)]+)\)')


class FfmpegExposureCheckTest(unittest.TestCase):
    def test_policy_exists(self) -> None:
        self.assertTrue(POLICY.is_file(), f"{POLICY} must exist")

    def test_policy_names_enforcement_files(self) -> None:
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        for path in policy["containerAllowlistEnforcement"]:
            self.assertTrue(
                (REPO_ROOT / path).is_file(),
                f"enforcement file {path} must exist",
            )

    def test_code_allowlist_matches_policy(self) -> None:
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        expected = set(policy["containerAllowlist"])

        code = MEDIA_INGESTION.read_text(encoding="utf-8")
        match = ALLOWLIST_RE.search(code)
        self.assertIsNotNone(match, "FFMPEG_SAFE_EXTENSIONS must exist in MediaIngestion.kt")
        code_exts = {s.strip().strip('"') for s in match.group(1).split(",")}
        self.assertEqual(expected, code_exts)

    def test_policy_version_matches_native_lock(self) -> None:
        policy = json.loads(POLICY.read_text(encoding="utf-8"))
        if not NATIVE_COMPLIANCE.is_file():
            self.skipTest("native-compliance.lock.json not found")
        lock = json.loads(NATIVE_COMPLIANCE.read_text(encoding="utf-8"))
        lock_versions = set()
        for abi in lock.get("abiEvidence", {}).values():
            for lib in abi.get("libraries", []):
                ver = lib.get("FFmpeg version")
                if ver:
                    lock_versions.add(ver)
        if lock_versions:
            self.assertIn(
                policy["bundledVersion"],
                lock_versions,
                "policy bundledVersion must match what native-compliance.lock.json records",
            )


if __name__ == "__main__":
    unittest.main()
