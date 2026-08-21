from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import tools.fdroid_preflight as fdroid_preflight


REPO_ROOT = Path(__file__).resolve().parents[2]


def analyze_temp_repo(
    app_gradle: str,
    *,
    include_foss_contract: bool = True,
    include_update_consent: bool = True,
    foss_route_source: str = "fun generatedWallpaperRoute(navController: Any) = Unit\n",
    main_strings_extra: str = "",
    main_profile_extra: str = "",
) -> dict[str, object]:
    with tempfile.TemporaryDirectory() as tmpdir:
        repo = Path(tmpdir)
        app_path = repo / "app/build.gradle.kts"
        app_path.parent.mkdir(parents=True)
        app_path.write_text(app_gradle, encoding="utf-8")
        settings_path = repo / "settings.gradle.kts"
        settings_path.write_text("", encoding="utf-8")
        if include_foss_contract:
            for relative in fdroid_preflight.FOSS_ONLY_STABILITY_FILES:
                path = repo / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("full-only fixture\n", encoding="utf-8")
            for relative in fdroid_preflight.FOSS_PROVIDER_SHIM_FILES:
                path = repo / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("provider-neutral FOSS fixture\n", encoding="utf-8")
            navigation_path = repo / "app/src/main/java/com/freevibe/ui/FreeVibeRoot.kt"
            navigation_path.parent.mkdir(parents=True, exist_ok=True)
            navigation_path.write_text(
                "generatedWallpaperRoute(navController)\n",
                encoding="utf-8",
            )
            full_route_path = repo / "app/src/full/java/com/freevibe/ui/GeneratedWallpaperRoute.kt"
            full_route_path.write_text(
                "composable(Screen.AiWallpaper.route)\nAiWallpaperScreen(\n",
                encoding="utf-8",
            )
            foss_route_path = repo / "app/src/foss/java/com/freevibe/ui/GeneratedWallpaperRoute.kt"
            foss_route_path.parent.mkdir(parents=True, exist_ok=True)
            foss_route_path.write_text(
                foss_route_source,
                encoding="utf-8",
            )
            wallpapers_path = repo / "app/src/main/java/com/freevibe/ui/screens/wallpapers/WallpapersScreen.kt"
            wallpapers_path.parent.mkdir(parents=True, exist_ok=True)
            wallpapers_path.write_text(
                "!BuildConfig.FOSS_BUILD && generatedContentProviderEnabled\nif (showGeneratedContentEntry)\n",
                encoding="utf-8",
            )
            services_path = repo / "app/src/main/java/com/freevibe/ui/screens/settings/SettingsServicesSection.kt"
            services_path.parent.mkdir(parents=True, exist_ok=True)
            services_path.write_text(
                "GeneratedWallpaperProviderSettings(\nproviderKey = generatedWallpaperProviderKey\n",
                encoding="utf-8",
            )
            full_settings_path = repo / "app/src/full/java/com/freevibe/ui/screens/settings/GeneratedWallpaperProviderSettings.kt"
            full_settings_path.write_text(
                "settings_services_stability_key_title\n"
                "GeneratedWallpaperDisclosureDialog(\n"
                "setGeneratedWallpaperProviderKey\n",
                encoding="utf-8",
            )
            foss_settings_path = repo / "app/src/foss/java/com/freevibe/ui/screens/settings/GeneratedWallpaperProviderSettings.kt"
            foss_settings_path.parent.mkdir(parents=True, exist_ok=True)
            foss_settings_path.write_text(
                "fun GeneratedWallpaperProviderSettings() = Unit\n",
                encoding="utf-8",
            )
        if include_update_consent:
            manager_path = repo / "app/src/main/java/com/freevibe/service/YtDlpUpdateManager.kt"
            manager_path.parent.mkdir(parents=True, exist_ok=True)
            manager_path.write_text(
                "YtDlpUpdateConsent\nconsent: YtDlpUpdateConsent\nREPOSITORY_CHECKS_BYPASS_CONFIRMED\n",
                encoding="utf-8",
            )
            settings_source_path = repo / "app/src/main/java/com/freevibe/ui/screens/settings/SettingsSoundSection.kt"
            settings_source_path.parent.mkdir(parents=True, exist_ok=True)
            settings_source_path.write_text(
                "showYtDlpConsent\nAlertDialog\nYtDlpUpdateConsent.REPOSITORY_CHECKS_BYPASS_CONFIRMED\n",
                encoding="utf-8",
            )
            strings_path = repo / "app/src/main/res/values/strings.xml"
            strings_path.parent.mkdir(parents=True, exist_ok=True)
            strings_path.write_text(
                "settings_ytdlp_consent_body settings_ytdlp_consent_warning F-Droid repository\n"
                + main_strings_extra,
                encoding="utf-8",
            )
        if main_profile_extra:
            profile_path = repo / "app/src/main/generated/baselineProfiles/baseline-prof.txt"
            profile_path.parent.mkdir(parents=True, exist_ok=True)
            profile_path.write_text(main_profile_extra, encoding="utf-8")

        original_root = fdroid_preflight.ROOT
        original_app = fdroid_preflight.APP_GRADLE
        original_settings = fdroid_preflight.SETTINGS_GRADLE
        original_manager = fdroid_preflight.YTDLP_MANAGER
        original_ytdlp_settings = fdroid_preflight.YTDLP_SETTINGS
        original_strings = fdroid_preflight.STRINGS_XML
        original_root_navigation = fdroid_preflight.ROOT_NAVIGATION
        original_wallpapers_screen = fdroid_preflight.WALLPAPERS_SCREEN
        original_services_screen = fdroid_preflight.SERVICES_SCREEN
        original_full_generated_route = fdroid_preflight.FULL_GENERATED_ROUTE
        original_foss_generated_route = fdroid_preflight.FOSS_GENERATED_ROUTE
        original_full_generated_settings = fdroid_preflight.FULL_GENERATED_SETTINGS
        original_foss_generated_settings = fdroid_preflight.FOSS_GENERATED_SETTINGS
        original_full_strings_xml = fdroid_preflight.FULL_STRINGS_XML
        try:
            fdroid_preflight.ROOT = repo
            fdroid_preflight.APP_GRADLE = app_path
            fdroid_preflight.SETTINGS_GRADLE = settings_path
            fdroid_preflight.YTDLP_MANAGER = repo / "app/src/main/java/com/freevibe/service/YtDlpUpdateManager.kt"
            fdroid_preflight.YTDLP_SETTINGS = repo / "app/src/main/java/com/freevibe/ui/screens/settings/SettingsSoundSection.kt"
            fdroid_preflight.STRINGS_XML = repo / "app/src/main/res/values/strings.xml"
            fdroid_preflight.ROOT_NAVIGATION = repo / "app/src/main/java/com/freevibe/ui/FreeVibeRoot.kt"
            fdroid_preflight.WALLPAPERS_SCREEN = repo / "app/src/main/java/com/freevibe/ui/screens/wallpapers/WallpapersScreen.kt"
            fdroid_preflight.SERVICES_SCREEN = repo / "app/src/main/java/com/freevibe/ui/screens/settings/SettingsServicesSection.kt"
            fdroid_preflight.FULL_GENERATED_ROUTE = repo / "app/src/full/java/com/freevibe/ui/GeneratedWallpaperRoute.kt"
            fdroid_preflight.FOSS_GENERATED_ROUTE = repo / "app/src/foss/java/com/freevibe/ui/GeneratedWallpaperRoute.kt"
            fdroid_preflight.FULL_GENERATED_SETTINGS = repo / "app/src/full/java/com/freevibe/ui/screens/settings/GeneratedWallpaperProviderSettings.kt"
            fdroid_preflight.FOSS_GENERATED_SETTINGS = repo / "app/src/foss/java/com/freevibe/ui/screens/settings/GeneratedWallpaperProviderSettings.kt"
            fdroid_preflight.FULL_STRINGS_XML = repo / "app/src/full/res/values/strings.xml"
            return fdroid_preflight.analyze()
        finally:
            fdroid_preflight.ROOT = original_root
            fdroid_preflight.APP_GRADLE = original_app
            fdroid_preflight.SETTINGS_GRADLE = original_settings
            fdroid_preflight.YTDLP_MANAGER = original_manager
            fdroid_preflight.YTDLP_SETTINGS = original_ytdlp_settings
            fdroid_preflight.STRINGS_XML = original_strings
            fdroid_preflight.ROOT_NAVIGATION = original_root_navigation
            fdroid_preflight.WALLPAPERS_SCREEN = original_wallpapers_screen
            fdroid_preflight.SERVICES_SCREEN = original_services_screen
            fdroid_preflight.FULL_GENERATED_ROUTE = original_full_generated_route
            fdroid_preflight.FOSS_GENERATED_ROUTE = original_foss_generated_route
            fdroid_preflight.FULL_GENERATED_SETTINGS = original_full_generated_settings
            fdroid_preflight.FOSS_GENERATED_SETTINGS = original_foss_generated_settings
            fdroid_preflight.FULL_STRINGS_XML = original_full_strings_xml


class FdroidPreflightTest(unittest.TestCase):
    def test_live_repo_has_passing_foss_boundary(self) -> None:
        result = fdroid_preflight.analyze()

        self.assertEqual("ready-for-review", result["status"])
        self.assertTrue(result["fossFlavor"])
        self.assertEqual([], result["blockers"])
        self.assertTrue(result["stabilityFossBoundary"])
        self.assertTrue(result["binaryUpdateConsent"])

    def test_missing_stability_boundary_blocks(self) -> None:
        result = analyze_temp_repo(
            """
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }
}
""",
            include_foss_contract=False,
        )

        self.assertEqual("blocked", result["status"])
        self.assertFalse(result["stabilityFossBoundary"])

    def test_missing_binary_update_consent_blocks(self) -> None:
        result = analyze_temp_repo(
            """
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }
}
""",
            include_update_consent=False,
        )

        self.assertEqual("blocked", result["status"])
        self.assertTrue(result["stabilityFossBoundary"])
        self.assertFalse(result["binaryUpdateConsent"])

    def test_foss_route_referencing_provider_ui_blocks(self) -> None:
        result = analyze_temp_repo(
            """
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }
}
""",
            foss_route_source="fun generatedWallpaperRoute() = AiWallpaperScreen()\n",
        )

        self.assertEqual("blocked", result["status"])
        self.assertFalse(result["stabilityFossBoundary"])

    def test_provider_ui_strings_in_main_resources_block(self) -> None:
        result = analyze_temp_repo(
            """
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }
}
""",
            main_strings_extra='<string name="ai_header_title">Generate</string>\n',
        )

        self.assertEqual("blocked", result["status"])
        self.assertFalse(result["stabilityFossBoundary"])

    def test_provider_metadata_in_main_baseline_profile_blocks(self) -> None:
        result = analyze_temp_repo(
            """
android {
    flavorDimensions += "distribution"
    productFlavors {
        create("full") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }
}
""",
            main_profile_extra="Lcom/freevibe/data/remote/stability/StabilityAiApi;\n",
        )

        self.assertEqual("blocked", result["status"])
        self.assertFalse(result["stabilityFossBoundary"])

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
