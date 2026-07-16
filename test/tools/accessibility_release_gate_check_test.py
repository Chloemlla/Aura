from __future__ import annotations

import json
import shutil
import tempfile
import unittest
from pathlib import Path

from tools.accessibility_release_gate_check import (
    AccessibilityReleaseGateError,
    validate_accessibility_release_gate,
)


REPO_ROOT = Path(__file__).resolve().parents[2]


class AccessibilityReleaseGateCheckTest(unittest.TestCase):
    def test_live_accessibility_release_gate_passes(self) -> None:
        result = validate_accessibility_release_gate(REPO_ROOT, "docs/qa/accessibility-release-gate.json")

        self.assertEqual("ok", result["status"])
        self.assertEqual("accessibilityReleaseGate", result["policyKind"])
        self.assertGreaterEqual(result["scenarioCount"], 6)
        self.assertGreaterEqual(result["executedSurfaceCount"], 6)

    def test_rejects_missing_automated_api(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = copy_required_tree(Path(tmpdir))
            test_path = repo / "app/src/androidTest/java/com/freevibe/ui/accessibility/AccessibilityReleaseGateTest.kt"
            test_path.write_text(test_path.read_text(encoding="utf-8").replace("enableAccessibilityChecks", ""), encoding="utf-8")

            with self.assertRaises(AccessibilityReleaseGateError):
                validate_accessibility_release_gate(repo, "docs/qa/accessibility-release-gate.json")

    def test_rejects_missing_manual_scenario(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = copy_required_tree(Path(tmpdir))
            policy_path = repo / "docs/qa/accessibility-release-gate.json"
            policy = json.loads(policy_path.read_text(encoding="utf-8"))
            policy["manualScenarios"] = [
                row for row in policy["manualScenarios"] if row["id"] != "sounds-editor"
            ]
            policy_path.write_text(json.dumps(policy), encoding="utf-8")

            with self.assertRaises(AccessibilityReleaseGateError):
                validate_accessibility_release_gate(repo, "docs/qa/accessibility-release-gate.json")

    def test_rejects_compose_test_dependencies_without_supported_bom(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = copy_required_tree(Path(tmpdir))
            catalog_path = repo / "gradle/libs.versions.toml"
            catalog_path.write_text(
                catalog_path.read_text(encoding="utf-8").replace(
                    'compose-bom = "2025.06.00"',
                    'compose-bom = "2024.12.01"',
                ),
                encoding="utf-8",
            )

            with self.assertRaises(AccessibilityReleaseGateError):
                validate_accessibility_release_gate(repo, "docs/qa/accessibility-release-gate.json")

    def test_rejects_missing_executed_surface(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = copy_required_tree(Path(tmpdir))
            policy_path = repo / "docs/qa/accessibility-release-gate.json"
            policy = json.loads(policy_path.read_text(encoding="utf-8"))
            policy["automatedGate"]["executedSurfaces"] = [
                row for row in policy["automatedGate"]["executedSurfaces"] if row["id"] != "wallpaper-editor"
            ]
            policy_path.write_text(json.dumps(policy), encoding="utf-8")

            with self.assertRaises(AccessibilityReleaseGateError):
                validate_accessibility_release_gate(repo, "docs/qa/accessibility-release-gate.json")

    def test_rejects_direct_primitive_only_test(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = copy_required_tree(Path(tmpdir))
            test_path = repo / "app/src/androidTest/java/com/freevibe/ui/accessibility/AccessibilityReleaseGateTest.kt"
            test_path.write_text(
                test_path.read_text(encoding="utf-8") + "\n@Suppress(\"unused\") fun primitiveOnly() { SettingsToggle() }\n",
                encoding="utf-8",
            )

            with self.assertRaises(AccessibilityReleaseGateError):
                validate_accessibility_release_gate(repo, "docs/qa/accessibility-release-gate.json")


def copy_required_tree(destination: Path) -> Path:
    paths = [
        "app/build.gradle.kts",
        "gradle/libs.versions.toml",
        "docs/qa/accessibility-release-gate.json",
        "app/src/debug/java/com/freevibe/ui/screens/fixtures/AuraRouteStateFixtures.kt",
        "app/src/androidTest/java/com/freevibe/ui/accessibility/AccessibilityReleaseGateTest.kt",
    ]
    for relative_path in paths:
        source = REPO_ROOT / relative_path
        target = destination / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    return destination


if __name__ == "__main__":
    unittest.main()
