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
        screenshot_test = self.read("app/src/test/java/com/freevibe/ui/screens/fixtures/AuraRouteStateScreenshotTest.kt")
        fixtures = self.read("app/src/debug/java/com/freevibe/ui/screens/fixtures/AuraRouteStateFixtures.kt")

        self.assertIn('qualifiers = "en-rXA-w411dp-h891dp-xhdpi"', screenshot_test)
        self.assertIn('qualifiers = "ar-rXB-w411dp-h891dp-xhdpi"', screenshot_test)
        self.assertIn("englishXaPseudo", screenshot_test)
        self.assertIn("arabicXbPseudo", screenshot_test)
        self.assertIn("LayoutDirection.Rtl", screenshot_test)
        self.assertIn("onNodeWithText(textTransform(fixture.primaryAssertionText())).assertExists()", screenshot_test)
        self.assertIn("LocalFixtureTextTransform provides textTransform", screenshot_test)
        self.assertIn("LocalFixtureTextTransform", fixtures)
        self.assertIn("fixtureText(", fixtures)

    def test_localization_policy_links_the_active_gate(self):
        policy = json.loads(self.read("docs/localization/hardcoded-string-baseline.json"))
        gate = policy["pseudolocaleReleaseGate"]

        self.assertEqual("active", gate["status"])
        self.assertEqual("debug", gate["enabledBuildType"])
        self.assertEqual(["en-XA", "ar-XB"], gate["locales"])
        self.assertIn("AuraRouteFixture.WallpapersGridSuccess", gate["compactRouteFixtures"])
        self.assertIn("AuraRouteFixture.SettingsProviderDisabled", gate["compactRouteFixtures"])

    def test_no_real_translation_pack_was_added(self):
        values_dirs = {
            path.name
            for path in (REPO_ROOT / "app/src/main/res").glob("values-*")
            if path.is_dir()
        }

        self.assertEqual(set(), values_dirs)


if __name__ == "__main__":
    unittest.main()
