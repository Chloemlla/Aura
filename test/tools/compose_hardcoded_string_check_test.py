import json
import tempfile
import unittest
from pathlib import Path

from tools import compose_hardcoded_string_check


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def write_strings(repo_root: Path) -> None:
    write_text(
        repo_root / "app/src/main/res/values/strings.xml",
        "<resources><string name=\"app_name\">Aura</string></resources>",
    )


def write_screen(repo_root: Path, text: str = "Existing title") -> None:
    (repo_root / "app/src/full/java/com/chloemlla/aura/ui").mkdir(parents=True, exist_ok=True)
    write_text(
        repo_root / "app/src/main/java/com/chloemlla/aura/ui/ExampleScreen.kt",
        "\n".join(
            [
                "package com.chloemlla.aura.ui",
                "",
                "import androidx.compose.material3.Text",
                "",
                "@Composable",
                "fun ExampleScreen() {",
                f"    Text(\"{text}\")",
                "}",
            ]
        ),
    )


def write_state_source(repo_root: Path, text: str = "Existing error") -> None:
    write_text(
        repo_root / "app/src/main/java/com/chloemlla/aura/ui/ExampleViewModel.kt",
        "\n".join(
            [
                "package com.chloemlla.aura.ui",
                "data class ExampleState(val error: String? = null)",
                "fun control() = FilterControl(\"Existing filter\")",
                f"val state = ExampleState(error = \"{text}\")",
            ]
        ),
    )


class ComposeHardcodedStringCheckTest(unittest.TestCase):
    def test_accepts_current_baseline(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            baseline_path = repo_root / "docs/localization/hardcoded-string-baseline.json"
            write_strings(repo_root)
            write_screen(repo_root)
            compose_hardcoded_string_check.write_baseline(repo_root, baseline_path)

            result = compose_hardcoded_string_check.validate_baseline(repo_root, baseline_path)

            self.assertEqual(result["status"], "ok")
            self.assertEqual(result["baselineEntries"], 1)
            self.assertEqual(result["currentFindings"], 1)

    def test_rejects_new_hardcoded_text_literal(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            baseline_path = repo_root / "docs/localization/hardcoded-string-baseline.json"
            write_strings(repo_root)
            write_screen(repo_root)
            compose_hardcoded_string_check.write_baseline(repo_root, baseline_path)
            write_screen(repo_root, "New visible copy")

            with self.assertRaisesRegex(
                compose_hardcoded_string_check.ComposeHardcodedStringError,
                "baseline drifted",
            ):
                compose_hardcoded_string_check.validate_baseline(repo_root, baseline_path)

    def test_rejects_count_increase_for_existing_literal(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            baseline_path = repo_root / "docs/localization/hardcoded-string-baseline.json"
            write_strings(repo_root)
            write_screen(repo_root)
            compose_hardcoded_string_check.write_baseline(repo_root, baseline_path)
            write_text(
                repo_root / "app/src/main/java/com/chloemlla/aura/ui/ExampleScreen.kt",
                "\n".join(
                    [
                        "package com.chloemlla.aura.ui",
                        "import androidx.compose.material3.Text",
                        "@Composable",
                        "fun ExampleScreen() {",
                        "    Text(\"Existing title\")",
                        "    Text(\"Existing title\")",
                        "}",
                    ]
                ),
            )

            with self.assertRaisesRegex(
                compose_hardcoded_string_check.ComposeHardcodedStringError,
                "countChanges",
            ):
                compose_hardcoded_string_check.validate_baseline(repo_root, baseline_path)

    def test_rejects_missing_migration_plan(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            baseline_path = repo_root / "docs/localization/hardcoded-string-baseline.json"
            write_strings(repo_root)
            write_screen(repo_root)
            baseline = compose_hardcoded_string_check.write_baseline(repo_root, baseline_path)
            baseline.pop("migrationPlan")
            baseline_path.write_text(json.dumps(baseline), encoding="utf-8")

            with self.assertRaisesRegex(
                compose_hardcoded_string_check.ComposeHardcodedStringError,
                "migrationPlan",
            ):
                compose_hardcoded_string_check.validate_baseline(repo_root, baseline_path)

    def test_write_preserves_policy_metadata(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            baseline_path = repo_root / "docs/localization/hardcoded-string-baseline.json"
            write_strings(repo_root)
            write_screen(repo_root)
            baseline_path.parent.mkdir(parents=True, exist_ok=True)
            baseline_path.write_text(
                json.dumps(
                    {
                        "pseudolocaleReleaseGate": {"status": "active"},
                        "migrationPlan": {"nextSteps": ["Keep this policy"]},
                    }
                ),
                encoding="utf-8",
            )

            baseline = compose_hardcoded_string_check.write_baseline(repo_root, baseline_path)

            self.assertEqual(baseline["pseudolocaleReleaseGate"]["status"], "active")
            self.assertEqual(baseline["migrationPlan"]["nextSteps"], ["Keep this policy"])

    def test_scans_viewmodel_state_and_editor_control_literals(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            baseline_path = repo_root / "docs/localization/hardcoded-string-baseline.json"
            write_strings(repo_root)
            write_screen(repo_root)
            write_state_source(repo_root)
            baseline = compose_hardcoded_string_check.write_baseline(repo_root, baseline_path)

            findings = {(entry["sink"], entry["text"]) for entry in baseline["baseline"]}
            self.assertIn(("error", "Existing error"), findings)
            self.assertIn(("FilterControl", "Existing filter"), findings)

    def test_accepts_baseline_with_self_consistent_ids(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            baseline_path = repo_root / "docs/localization/hardcoded-string-baseline.json"
            write_strings(repo_root)
            write_screen(repo_root)
            write_state_source(repo_root)
            baseline = compose_hardcoded_string_check.write_baseline(repo_root, baseline_path)

            for entry in baseline["baseline"]:
                self.assertEqual(
                    entry["id"],
                    compose_hardcoded_string_check.compute_finding_id(
                        entry["path"], entry["sink"], entry["text"]
                    ),
                )

            result = compose_hardcoded_string_check.validate_baseline(repo_root, baseline_path)

            self.assertEqual(result["status"], "ok")

    def test_reports_stale_baseline_ids_as_key_regeneration(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            baseline_path = repo_root / "docs/localization/hardcoded-string-baseline.json"
            write_strings(repo_root)
            write_screen(repo_root)
            write_state_source(repo_root)
            baseline = compose_hardcoded_string_check.write_baseline(repo_root, baseline_path)
            entries = baseline["baseline"]
            self.assertEqual(len(entries), 3)
            for index, entry in enumerate(entries[:2]):
                entry["id"] = f"{index + 1:016x}"
            baseline_path.write_text(json.dumps(baseline), encoding="utf-8")

            with self.assertRaises(compose_hardcoded_string_check.ComposeHardcodedStringError) as caught:
                compose_hardcoded_string_check.validate_baseline(repo_root, baseline_path)

            message = str(caught.exception)
            self.assertIn("baseline keys are stale", message)
            self.assertIn("Regenerate the baseline keys", message)
            self.assertIn("not a signal that new hardcoded strings need extraction", message)
            self.assertNotIn("baseline drifted", message)
            payload = json.loads(message[message.index("{") :])
            self.assertEqual(payload["staleBaselineIdCount"], 2)
            self.assertEqual(payload["baselineEntryCount"], 3)
            self.assertEqual(len(payload["staleBaselineIds"]), 2)
            for stale_entry in payload["staleBaselineIds"]:
                self.assertEqual(
                    stale_entry["expectedId"],
                    compose_hardcoded_string_check.compute_finding_id(
                        stale_entry["path"], stale_entry["sink"], stale_entry["text"]
                    ),
                )

    def test_reports_new_literal_as_drift_not_stale_ids(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            repo_root = Path(temp_dir)
            baseline_path = repo_root / "docs/localization/hardcoded-string-baseline.json"
            write_strings(repo_root)
            write_screen(repo_root)
            baseline = compose_hardcoded_string_check.write_baseline(repo_root, baseline_path)
            for entry in baseline["baseline"]:
                self.assertEqual(
                    entry["id"],
                    compose_hardcoded_string_check.compute_finding_id(
                        entry["path"], entry["sink"], entry["text"]
                    ),
                )
            write_screen(repo_root, "New visible copy")

            with self.assertRaises(compose_hardcoded_string_check.ComposeHardcodedStringError) as caught:
                compose_hardcoded_string_check.validate_baseline(repo_root, baseline_path)

            message = str(caught.exception)
            self.assertIn("baseline drifted", message)
            self.assertIn("--mode write after intentional extraction", message)
            self.assertNotIn("stale", message)
            payload = json.loads(message[message.index("{") :])
            self.assertEqual(
                [entry["text"] for entry in payload["newHardcodedStrings"]],
                ["New visible copy"],
            )
            self.assertEqual(
                [entry["text"] for entry in payload["removedOrExtractedStrings"]],
                ["Existing title"],
            )
            self.assertEqual(payload["countChanges"], [])

    def test_live_baseline_is_valid(self):
        repo_root = Path(__file__).resolve().parents[2]
        baseline_path = repo_root / "docs/localization/hardcoded-string-baseline.json"
        if not baseline_path.exists():
            self.skipTest("live baseline is generated by the implementation batch")

        result = compose_hardcoded_string_check.validate_baseline(repo_root, baseline_path)

        self.assertEqual(result["status"], "ok")


if __name__ == "__main__":
    unittest.main()
