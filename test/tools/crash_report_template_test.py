from __future__ import annotations

import unittest
from pathlib import Path

try:
    import yaml
except ImportError:
    yaml = None


REPO_ROOT = Path(__file__).resolve().parents[2]
TEMPLATE = REPO_ROOT / ".github" / "ISSUE_TEMPLATE" / "crash_report.yml"


class CrashReportTemplateTest(unittest.TestCase):
    def test_template_exists(self) -> None:
        self.assertTrue(TEMPLATE.is_file())

    @unittest.skipIf(yaml is None, "PyYAML not installed")
    def test_template_parses_as_valid_yaml(self) -> None:
        data = yaml.safe_load(TEMPLATE.read_text(encoding="utf-8"))
        self.assertIsInstance(data, dict)
        self.assertIn("body", data)

    def test_diagnostics_not_required(self) -> None:
        text = TEMPLATE.read_text(encoding="utf-8")
        self.assertIn("diagnostics-bundle", text)
        lines = text.splitlines()
        in_diagnostics = False
        for line in lines:
            if "id: diagnostics-bundle" in line:
                in_diagnostics = True
            if in_diagnostics and "required:" in line:
                self.assertIn("false", line.lower(),
                              "diagnostics bundle must not be required (no-launch fallback)")
                break
        else:
            if in_diagnostics:
                self.fail("diagnostics-bundle section has no required: field")

    def test_logcat_fallback_exists(self) -> None:
        text = TEMPLATE.read_text(encoding="utf-8")
        self.assertIn("logcat-fallback", text,
                       "template must offer adb logcat as a no-launch fallback")
        self.assertIn("adb logcat", text)

    def test_collects_environment_fields(self) -> None:
        text = TEMPLATE.read_text(encoding="utf-8")
        for field in ("aura-version", "android-version", "device-model",
                      "install-channel", "repro-steps", "survives-restart"):
            self.assertIn(f"id: {field}", text, f"template must collect {field}")


if __name__ == "__main__":
    unittest.main()
