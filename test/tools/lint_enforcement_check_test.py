from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.lint_enforcement_check import (
    BUILD_SCRIPT,
    COLORS_HELPER_SOURCE,
    ENGINE_SOURCE_ROOT,
    VERSION_CATALOG,
    LintEnforcementError,
    agp_version,
    unguarded_color_notifications,
    validate_lint_enforcement,
)


REPO_ROOT = Path(__file__).resolve().parents[2]

CATALOG = '''[versions]
agp = "8.9.3"
kotlin = "2.1.0"
'''

CLEAN_BUILD_SCRIPT = """android {
    compileSdk = 36

    lint {
        // No detector disables.
        warningsAsErrors = false
        abortOnError = true
    }
}
"""

HELPER = """package com.chloemlla.aura.service

internal fun WallpaperService.Engine.notifyWallpaperColorsChanged() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        notifyColorsChanged()
    }
}
"""

GOOD_ENGINE = """package com.chloemlla.aura.service

class WeatherWallpaperService : WallpaperService() {
    inner class Engine {
        fun publish() {
            if (changed) notifyWallpaperColorsChanged()
        }
    }
}
"""


class LintEnforcementCheckTest(unittest.TestCase):
    def _stage(
        self,
        *,
        catalog: str = CATALOG,
        build_script: str = CLEAN_BUILD_SCRIPT,
        helper: str = HELPER,
        engine: str = GOOD_ENGINE,
        baseline: str | None = None,
    ) -> Path:
        root = Path(tempfile.mkdtemp())
        (root / VERSION_CATALOG).parent.mkdir(parents=True, exist_ok=True)
        (root / VERSION_CATALOG).write_text(catalog, encoding="utf-8")
        (root / BUILD_SCRIPT).parent.mkdir(parents=True, exist_ok=True)
        (root / BUILD_SCRIPT).write_text(build_script, encoding="utf-8")
        (root / COLORS_HELPER_SOURCE).parent.mkdir(parents=True, exist_ok=True)
        (root / COLORS_HELPER_SOURCE).write_text(helper, encoding="utf-8")
        (root / ENGINE_SOURCE_ROOT / "WeatherWallpaperService.kt").write_text(
            engine, encoding="utf-8"
        )
        if baseline is not None:
            (root / "app/lint-baseline.xml").write_text(baseline, encoding="utf-8")
        return root

    def test_the_live_repository_passes(self) -> None:
        result = validate_lint_enforcement(REPO_ROOT)
        self.assertEqual("ok", result["status"])
        self.assertEqual(0, result["detectorDisables"])
        # The Android 17 upgrade's findings are absorbed by a committed baseline;
        # what the gate holds is that the file exists and is reviewable.
        self.assertTrue(result["lintBaselineDeclared"])
        self.assertEqual("lint-baseline.xml", result["lintBaselinePath"])
        self.assertGreater(result["lintBaselineIssues"], 0)

    def test_a_clean_staged_repository_passes(self) -> None:
        result = validate_lint_enforcement(self._stage())
        self.assertEqual("ok", result["status"])
        self.assertEqual("8.9.3", result["agpVersion"])

    def test_an_agp_that_cannot_run_lint_fails(self) -> None:
        root = self._stage(catalog='[versions]\nagp = "8.7.3"\n')
        with self.assertRaises(LintEnforcementError) as caught:
            validate_lint_enforcement(root)
        self.assertIn("IncompatibleClassChangeError", str(caught.exception))

    def test_reintroducing_a_detector_disable_fails(self) -> None:
        root = self._stage(
            build_script=CLEAN_BUILD_SCRIPT.replace(
                "        abortOnError = true",
                '        disable += "NullSafeMutableLiveData"\n        abortOnError = true',
            )
        )
        with self.assertRaises(LintEnforcementError) as caught:
            validate_lint_enforcement(root)
        self.assertIn("disables detectors", str(caught.exception))

    def test_a_declared_but_uncommitted_baseline_fails(self) -> None:
        root = self._stage(
            build_script=CLEAN_BUILD_SCRIPT.replace(
                "        abortOnError = true",
                '        baseline = file("lint-baseline.xml")\n        abortOnError = true',
            )
        )
        with self.assertRaises(LintEnforcementError) as caught:
            validate_lint_enforcement(root)
        self.assertIn("is not committed", str(caught.exception))

    def test_an_empty_committed_baseline_fails(self) -> None:
        root = self._stage(
            build_script=CLEAN_BUILD_SCRIPT.replace(
                "        abortOnError = true",
                '        baseline = file("lint-baseline.xml")\n        abortOnError = true',
            ),
            baseline='<?xml version="1.0" encoding="UTF-8"?>\n<issues format="6" />\n',
        )
        with self.assertRaises(LintEnforcementError) as caught:
            validate_lint_enforcement(root)
        self.assertIn("records no issues", str(caught.exception))

    def test_a_committed_baseline_with_findings_passes(self) -> None:
        root = self._stage(
            build_script=CLEAN_BUILD_SCRIPT.replace(
                "        abortOnError = true",
                '        baseline = file("lint-baseline.xml")\n        abortOnError = true',
            ),
            baseline=(
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<issues format="6">\n'
                '    <issue id="UseKtx" message="x" />\n'
                "</issues>\n"
            ),
        )

        result = validate_lint_enforcement(root)

        self.assertEqual("ok", result["status"])
        self.assertTrue(result["lintBaselineDeclared"])
        self.assertEqual(1, result["lintBaselineIssues"])

    def test_dropping_abort_on_error_fails(self) -> None:
        root = self._stage(
            build_script=CLEAN_BUILD_SCRIPT.replace("        abortOnError = true\n", "")
        )
        with self.assertRaises(LintEnforcementError) as caught:
            validate_lint_enforcement(root)
        self.assertIn("abortOnError", str(caught.exception))

    def test_a_missing_lint_block_fails(self) -> None:
        root = self._stage(build_script="android {\n    compileSdk = 36\n}\n")
        with self.assertRaises(LintEnforcementError) as caught:
            validate_lint_enforcement(root)
        self.assertIn("no lint block", str(caught.exception))

    def test_the_defect_this_gate_exists_for_fails(self) -> None:
        """An engine calling the API 27 method directly is the shipped crash."""
        root = self._stage(
            engine=GOOD_ENGINE.replace(
                "notifyWallpaperColorsChanged()", "notifyColorsChanged()"
            )
        )
        with self.assertRaises(LintEnforcementError) as caught:
            validate_lint_enforcement(root)
        self.assertIn("minSdk 26", str(caught.exception))

    def test_deleting_the_guarded_helper_fails(self) -> None:
        root = self._stage(helper="package com.chloemlla.aura.service\n")
        with self.assertRaises(LintEnforcementError) as caught:
            validate_lint_enforcement(root)
        self.assertIn("notifyWallpaperColorsChanged", str(caught.exception))


class UnguardedColorNotificationsTest(unittest.TestCase):
    def _stage_engine(self, body: str) -> Path:
        root = Path(tempfile.mkdtemp())
        (root / COLORS_HELPER_SOURCE).parent.mkdir(parents=True, exist_ok=True)
        (root / COLORS_HELPER_SOURCE).write_text(HELPER, encoding="utf-8")
        (root / ENGINE_SOURCE_ROOT / "Engine.kt").write_text(body, encoding="utf-8")
        return root

    def test_the_helpers_own_call_is_not_an_offender(self) -> None:
        root = self._stage_engine(GOOD_ENGINE)
        self.assertEqual([], unguarded_color_notifications(root))

    def test_the_guarded_helper_name_does_not_match_the_raw_call(self) -> None:
        """`notifyWallpaperColorsChanged()` must not be read as the raw call."""
        root = self._stage_engine("fun a() { notifyWallpaperColorsChanged() }\n")
        self.assertEqual([], unguarded_color_notifications(root))

    def test_a_qualified_call_on_another_object_is_not_an_offender(self) -> None:
        root = self._stage_engine("fun a() { other.notifyColorsChanged() }\n")
        self.assertEqual([], unguarded_color_notifications(root))

    def test_a_commented_out_call_is_not_an_offender(self) -> None:
        root = self._stage_engine("fun a() {\n    // notifyColorsChanged()\n}\n")
        self.assertEqual([], unguarded_color_notifications(root))

    def test_a_bare_call_is_reported_with_its_line(self) -> None:
        root = self._stage_engine("fun a() {\n    notifyColorsChanged()\n}\n")
        offenders = unguarded_color_notifications(root)
        self.assertEqual(1, len(offenders))
        self.assertTrue(offenders[0].endswith(":2"))


class AgpVersionTest(unittest.TestCase):
    def test_a_release_version_parses(self) -> None:
        self.assertEqual((8, 9, 3), agp_version('agp = "8.9.3"'))

    def test_a_prerelease_suffix_is_dropped(self) -> None:
        self.assertEqual((8, 9, 0), agp_version('agp = "8.9.0-rc01"'))

    def test_a_missing_version_raises(self) -> None:
        with self.assertRaises(LintEnforcementError):
            agp_version('kotlin = "2.1.0"')

    def test_another_keys_version_is_not_mistaken_for_agp(self) -> None:
        with self.assertRaises(LintEnforcementError):
            agp_version('[versions]\nnotagp = "8.9.3"\n')


if __name__ == "__main__":
    unittest.main()
