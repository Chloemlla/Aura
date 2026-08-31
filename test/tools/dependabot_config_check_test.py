from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.dependabot_config_check import DependabotConfigError, validate_dependabot_config


REPO_ROOT = Path(__file__).resolve().parents[2]
LIVE_CONFIG = REPO_ROOT / ".github" / "dependabot.yml"

EXPECTED_UPDATES = [
    ("github-actions", "/"),
    ("gradle", "/"),
    ("npm", "/"),
    ("npm", "/functions"),
]


def write_config(text: str) -> tuple[tempfile.TemporaryDirectory[str], Path]:
    tmpdir = tempfile.TemporaryDirectory()
    config = Path(tmpdir.name) / "dependabot.yml"
    config.write_text(text, encoding="utf-8")
    return tmpdir, config


class DependabotConfigCheckTest(unittest.TestCase):
    def test_live_dependabot_config_is_present_and_valid(self) -> None:
        result = validate_dependabot_config(LIVE_CONFIG)

        self.assertEqual("ok", result["status"])
        self.assertEqual(4, result["updateCount"])
        self.assertEqual(
            EXPECTED_UPDATES,
            [(row["packageEcosystem"], row["directory"]) for row in result["updates"]],
        )

    def test_rejects_missing_dependabot_config(self) -> None:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        missing = Path(tmpdir.name) / "dependabot.yml"

        with self.assertRaises(DependabotConfigError):
            validate_dependabot_config(missing)

    def test_rejects_incomplete_dependabot_config(self) -> None:
        tmpdir, config = write_config(
            """
version: 2
updates:
  - package-ecosystem: "gradle"
    directory: "/"
    target-branch: "main"
    schedule:
      interval: "weekly"
      day: "monday"
      timezone: "America/New_York"
      time: "10:00"
    open-pull-requests-limit: 5
    commit-message:
      prefix: "deps"
    labels:
      - "dependencies"
      - "security"
""".strip()
            + "\n",
        )
        self.addCleanup(tmpdir.cleanup)

        with self.assertRaises(DependabotConfigError):
            validate_dependabot_config(config)


if __name__ == "__main__":
    unittest.main()
