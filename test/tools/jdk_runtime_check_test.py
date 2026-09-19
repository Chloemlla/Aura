from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.jdk_runtime_check import (
    JdkRuntimePolicyError,
    check_build_gradle,
    check_runbooks,
    validate_jdk_runtime_policy,
)


REPO_ROOT = Path(__file__).resolve().parents[2]


class JdkRuntimeCheckTest(unittest.TestCase):
    def test_live_jdk_runtime_policy_passes(self) -> None:
        result = validate_jdk_runtime_policy(REPO_ROOT, "app/build.gradle.kts")

        self.assertEqual("ok", result["status"])
        self.assertEqual(21, result["requiredMajor"])

    def test_live_build_gradle_declares_jdk_21(self) -> None:
        declared = check_build_gradle(REPO_ROOT, "app/build.gradle.kts")
        self.assertEqual(21, declared)

    def test_live_runbooks_have_no_jbr_references(self) -> None:
        issues = check_runbooks(REPO_ROOT)
        self.assertEqual([], issues, f"stale JBR references found: {issues}")

    def test_rejects_missing_require_in_build_gradle(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            build = Path(tmpdir) / "build.gradle.kts"
            build.write_text("android { compileSdk = 36 }\n", encoding="utf-8")
            with self.assertRaises(JdkRuntimePolicyError):
                check_build_gradle(Path(tmpdir), "build.gradle.kts")

    def test_rejects_wrong_jdk_major_in_build_gradle(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            build = Path(tmpdir) / "build.gradle.kts"
            build.write_text(
                'require(jdkMajor == 25) { "wrong" }\n', encoding="utf-8"
            )
            with self.assertRaises(JdkRuntimePolicyError):
                check_build_gradle(Path(tmpdir), "build.gradle.kts")

    def test_rejects_jbr_reference_in_runbook(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            root = Path(tmpdir)
            build = root / "app" / "build.gradle.kts"
            build.parent.mkdir(parents=True)
            build.write_text(
                'val jdkMajor = 21\nrequire(jdkMajor == 21) { "need 21" }\n',
                encoding="utf-8",
            )
            docs = root / "docs" / "distribution"
            docs.mkdir(parents=True)
            (docs / "release-signing.md").write_text(
                "Use Android Studio's bundled JBR on Windows.\n", encoding="utf-8"
            )
            (docs / "release-dry-run.md").write_text("ok\n", encoding="utf-8")
            (docs / "supply-chain.md").write_text("ok\n", encoding="utf-8")

            with self.assertRaises(JdkRuntimePolicyError) as ctx:
                validate_jdk_runtime_policy(root, "app/build.gradle.kts")
            self.assertIn("JBR", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
