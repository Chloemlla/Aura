from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.compose_stability_check import (
    BUILD_SCRIPT,
    MODELS_SOURCE,
    STABILITY_CONFIG,
    UI_SOURCE_ROOT,
    ComposeStabilityError,
    declared_models,
    validate_compose_stability,
)


REPO_ROOT = Path(__file__).resolve().parents[2]

STABLE_MODEL = """package com.freevibe.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class Wallpaper(
    val id: String,
)
"""

UNSTABLE_MODEL = """package com.freevibe.data.model

data class Wallpaper(
    val id: String,
    val tags: List<String> = emptyList(),
)
"""

GRID_SCREEN = """package com.freevibe.ui.screens.wallpapers

import com.freevibe.data.model.Wallpaper

fun WallpapersScreen() {
    LazyVerticalGrid {
        items(wallpapers) { wallpaper: Wallpaper -> WallpaperCard(wallpaper) }
    }
}
"""

BUILD_SCRIPT_SOURCE = """android {
    composeCompiler {
        metricsDestination.set(layout.buildDirectory.dir("compose/metrics"))
        reportsDestination.set(layout.buildDirectory.dir("compose/reports"))
        stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose-stability.conf"))
    }
}
"""


class ComposeStabilityCheckTest(unittest.TestCase):
    def _stage(
        self,
        models: str = STABLE_MODEL,
        build_script: str = BUILD_SCRIPT_SOURCE,
        stability_config: str = "kotlin.collections.List\n",
        screen: str | None = GRID_SCREEN,
    ) -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        for relative, content in (
            (MODELS_SOURCE, models),
            (BUILD_SCRIPT, build_script),
            (STABILITY_CONFIG, stability_config),
        ):
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        if screen is not None:
            screen_path = root / UI_SOURCE_ROOT / "screens" / "wallpapers" / "WallpapersScreen.kt"
            screen_path.parent.mkdir(parents=True, exist_ok=True)
            screen_path.write_text(screen, encoding="utf-8")
        return root

    def test_the_live_repository_passes(self) -> None:
        result = validate_compose_stability(REPO_ROOT)

        self.assertEqual("ok", result["status"])
        self.assertIn("Wallpaper", result["listRenderedModels"])
        self.assertIn("Sound", result["listRenderedModels"])

    def test_an_annotated_model_rendered_in_a_grid_is_accepted(self) -> None:
        result = validate_compose_stability(self._stage())

        self.assertEqual("ok", result["status"])
        self.assertEqual(["Wallpaper"], result["listRenderedModels"])

    def test_the_defect_this_gate_exists_for_fails(self) -> None:
        """Wallpaper without @Immutable, rendered in every cell of the busiest screen."""
        root = self._stage(models=UNSTABLE_MODEL)

        with self.assertRaises(ComposeStabilityError) as caught:
            validate_compose_stability(root)

        self.assertIn("Wallpaper", str(caught.exception))
        self.assertIn("recomposes", str(caught.exception))

    def test_a_model_no_list_renders_is_not_held_to_the_rule(self) -> None:
        result = validate_compose_stability(self._stage(models=UNSTABLE_MODEL, screen=None))

        self.assertEqual("ok", result["status"])
        self.assertEqual([], result["listRenderedModels"])

    def test_switching_off_the_compiler_reports_fails(self) -> None:
        """The reports are how a regression becomes visible; losing them is the regression."""
        root = self._stage(build_script="android {\n}\n")

        with self.assertRaises(ComposeStabilityError) as caught:
            validate_compose_stability(root)

        self.assertIn("composeCompiler", str(caught.exception))

    def test_dropping_only_the_metrics_destination_still_fails(self) -> None:
        root = self._stage(
            build_script=BUILD_SCRIPT_SOURCE.replace(
                'metricsDestination.set(layout.buildDirectory.dir("compose/metrics"))\n', ""
            )
        )

        with self.assertRaises(ComposeStabilityError) as caught:
            validate_compose_stability(root)

        self.assertIn("metricsDestination", str(caught.exception))

    def test_a_missing_stability_config_fails(self) -> None:
        root = self._stage()
        (root / STABILITY_CONFIG).unlink()

        with self.assertRaises(ComposeStabilityError) as caught:
            validate_compose_stability(root)

        self.assertIn(STABILITY_CONFIG, str(caught.exception))

    def test_an_empty_stability_config_fails(self) -> None:
        root = self._stage(stability_config="   \n")

        with self.assertRaises(ComposeStabilityError):
            validate_compose_stability(root)


class DeclaredModelsTest(unittest.TestCase):
    def test_an_annotation_above_other_annotations_is_still_seen(self) -> None:
        """`@Immutable` then `@Entity` then the class is how the Room models read."""
        source = """@Immutable
@Entity(
    tableName = "favorites",
    primaryKeys = ["id", "source", "type"],
)
data class FavoriteEntity(
    val id: String,
)
"""

        self.assertEqual({"FavoriteEntity": True}, declared_models(source))

    def test_an_unannotated_class_is_reported_unannotated(self) -> None:
        source = """@Entity(tableName = "downloads")
data class DownloadEntity(
    val id: String,
)
"""

        self.assertEqual({"DownloadEntity": False}, declared_models(source))

    def test_a_generic_data_class_is_still_found(self) -> None:
        source = "data class SearchResult<T>(\n    val items: List<T>,\n)\n"

        self.assertEqual({"SearchResult": False}, declared_models(source))

    def test_an_annotation_on_an_unrelated_class_does_not_leak_forward(self) -> None:
        source = """@Immutable
data class Sound(
    val id: String,
)

// Ten lines of separation is more than the preamble window.
//
//
//
//
//
//
data class WallpaperPair(
    val home: String,
)
"""

        self.assertEqual({"Sound": True, "WallpaperPair": False}, declared_models(source))


if __name__ == "__main__":
    unittest.main()
