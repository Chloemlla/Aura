import json
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


class PseudolocaleReleaseGateTest(unittest.TestCase):
    def read(self, relative_path: str) -> str:
        return (REPO_ROOT / relative_path).read_text(encoding="utf-8")

    def test_debug_builds_enable_android_pseudolocales(self):
        gradle = self.read("app/build.gradle.kts")

        self.assertIn("debug {", gradle)
        self.assertIn("isPseudoLocalesEnabled = true", gradle)
        self.assertNotIn("resourceConfigurations", gradle)

    def test_route_screenshot_gate_renders_en_xa_and_ar_xb(self):
        screenshot_test = self.read("app/src/test/java/com/chloemlla/aura/ui/qa/ProductionRouteStateScreenshotTest.kt")
        production_routes = self.read("app/src/main/java/com/chloemlla/aura/ui/qa/ProductionRouteState.kt")

        self.assertIn('qualifiers = "en-rXA-w411dp-h891dp-xhdpi"', screenshot_test)
        self.assertIn('qualifiers = "ar-rXB-w411dp-h891dp-xhdpi"', screenshot_test)
        self.assertIn("LayoutDirection.Rtl", screenshot_test)
        self.assertIn("ProductionRouteScenario", screenshot_test)
        self.assertIn("ApplicationProvider", screenshot_test)
        self.assertIn("ProductionRouteState", production_routes)
        self.assertNotIn("AuraRouteStateFixture", production_routes)

    def test_localization_policy_links_the_active_gate(self):
        policy = json.loads(self.read("docs/localization/hardcoded-string-baseline.json"))
        gate = policy["pseudolocaleReleaseGate"]

        self.assertEqual("active", gate["status"])
        self.assertEqual("debug", gate["enabledBuildType"])
        self.assertEqual(["en-XA", "ar-XB"], gate["locales"])
        self.assertIn("ProductionRouteScenario.WallpapersGridSuccess", gate["compactRouteScenarios"])
        self.assertIn("ProductionRouteScenario.SettingsProviderDisabled", gate["compactRouteScenarios"])

    def test_only_reviewed_translation_packs_exist(self):
        # Real translation packs need a human-reviewed submission (see issue #47).
        # values-zh landed via PR #48, written and reviewed by a native speaker.
        # Full-only provider strings live in app/src/full/res/values-zh instead,
        # so the FOSS flavor never packages a translation without a default.
        reviewed_packs = {"values-zh"}

        for res_root in ("app/src/main/res", "app/src/full/res"):
            values_dirs = {
                path.name
                for path in (REPO_ROOT / res_root).glob("values-*")
                if path.is_dir()
            }
            self.assertEqual(reviewed_packs, values_dirs, res_root)


if __name__ == "__main__":
    unittest.main()
