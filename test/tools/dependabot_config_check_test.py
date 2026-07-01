from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.dependabot_config_check import DependabotConfigError, validate_dependabot_config


REPO_ROOT = Path(__file__).resolve().parents[2]
LIVE_CONFIG = REPO_ROOT / ".github" / "dependabot.yml"


def write_config(text: str) -> tuple[tempfile.TemporaryDirectory[str], Path]:
    tmpdir = tempfile.TemporaryDirectory()
    config = Path(tmpdir.name) / "dependabot.yml"
    config.write_text(text, encoding="utf-8")
    return tmpdir, config


class DependabotConfigCheckTest(unittest.TestCase):
    def test_live_dependabot_config_is_absent(self) -> None:
        result = validate_dependabot_config(LIVE_CONFIG)

        self.assertEqual("ok", result["status"])
        self.assertEqual("absent", result["mode"])
        self.assertEqual(0, result["updateCount"])
        self.assertEqual([], result["updates"])

    def test_rejects_any_dependabot_config(self) -> None:
        tmpdir, config = write_config(
            """
version: 2
updates:
  - package-ecosystem: "gradle"
    directory: "/"
""".strip()
            + "\n",
        )
        self.addCleanup(tmpdir.cleanup)

        with self.assertRaises(DependabotConfigError):
            validate_dependabot_config(config)


if __name__ == "__main__":
    unittest.main()
