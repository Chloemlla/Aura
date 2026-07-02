from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import tools.fdroid_preflight as fdroid_preflight


REPO_ROOT = Path(__file__).resolve().parents[2]


def analyze_temp_repo(app_gradle: str) -> dict[str, object]:
    with tempfile.TemporaryDirectory() as tmpdir:
        repo = Path(tmpdir)
        app_path = repo / "app/build.gradle.kts"
        app_path.parent.mkdir(parents=True)
        app_path.write_text(app_gradle, encoding="utf-8")
        settings_path = repo / "settings.gradle.kts"
        settings_path.write_text("", encoding="utf-8")

        original_root = fdroid_preflight.ROOT
        original_app = fdroid_preflight.APP_GRADLE
        original_settings = fdroid_preflight.SETTINGS_GRADLE
        try:
            fdroid_preflight.ROOT = repo
            fdroid_preflight.APP_GRADLE = app_path
            fdroid_preflight.SETTINGS_GRADLE = settings_path
            return fdroid_preflight.analyze()
        finally:
            fdroid_preflight.ROOT = original_root
            fdroid_preflight.APP_GRADLE = original_app
            fdroid_preflight.SETTINGS_GRADLE = original_settings


class FdroidPreflightTest(unittest.TestCase):
    def test_live_repo_has_passing_foss_boundary(self) -> None:
        result = fdroid_preflight.analyze()

        self.assertEqual("ready-for-review", result["status"])
        self.assertTrue(result["fossFlavor"])
        self.assertEqual([], result["blockers"])

    def test_full_only_firebase_dependencies_do_not_block_foss(self) -> None:
        result = analyze_temp_repo(
            """
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }
}

dependencies {
    add("fullImplementation", platform("com.google.firebase:firebase-bom:34.13.0"))
    add("fullImplementation", "com.google.firebase:firebase-database")
    add("fullImplementation", "com.google.android.gms:play-services-base:18.5.0")
}
"""
        )

        self.assertEqual("ready-for-review", result["status"])
        self.assertEqual([], result["blockers"])

    def test_foss_active_firebase_dependency_blocks(self) -> None:
        result = analyze_temp_repo(
            """
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }
}

dependencies {
    implementation("com.google.firebase:firebase-database")
}
"""
        )

        self.assertEqual("blocked", result["status"])
        self.assertEqual("Firebase dependency", result["blockers"][0]["label"])

    def test_missing_foss_flavor_blocks(self) -> None:
        result = analyze_temp_repo(
            """
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
    }
}
"""
        )

        self.assertEqual("blocked", result["status"])
        self.assertFalse(result["fossFlavor"])


if __name__ == "__main__":
    unittest.main()
