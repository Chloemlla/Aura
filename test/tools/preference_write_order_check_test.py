from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.preference_write_order_check import (
    BRIDGE_FUNCTIONS,
    PREFERENCES_MANAGER,
    SETTINGS_VIEW_MODEL,
    PreferenceWriteOrderError,
    extract_function_body,
    validate_preference_write_order,
)


REPO_ROOT = Path(__file__).resolve().parents[2]


class PreferenceWriteOrderCheckTest(unittest.TestCase):
    def _stage(self, manager: str, view_model: str | None = None) -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        for relative, content in (
            (PREFERENCES_MANAGER, manager),
            (
                SETTINGS_VIEW_MODEL,
                view_model
                if view_model is not None
                else (REPO_ROOT / SETTINGS_VIEW_MODEL).read_text(encoding="utf-8"),
            ),
        ):
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
        return root

    def _live_manager(self) -> str:
        return (REPO_ROOT / PREFERENCES_MANAGER).read_text(encoding="utf-8")

    def test_live_bridges_write_shared_preferences_first(self) -> None:
        result = validate_preference_write_order(REPO_ROOT)

        self.assertEqual("ok", result["status"])
        self.assertEqual(len(BRIDGE_FUNCTIONS), result["bridgeCount"])

    def test_every_bridge_writes_both_stores(self) -> None:
        manager = self._live_manager()
        for name in BRIDGE_FUNCTIONS:
            body = extract_function_body(manager, name)
            self.assertRegex(
                body,
                r"writeLiveWallpaperFlag|weatherWallpaperPrefs\(\)|getSharedPreferences",
                f"{name} must write SharedPreferences",
            )
            self.assertIn("set(Keys.", body, f"{name} must write DataStore")

    def test_rejects_datastore_written_before_shared_preferences(self) -> None:
        manager = self._live_manager().replace(
            "        writeLiveWallpaperFlag(REDUCE_ANIMATIONS_PREF, enabled)\n"
            "        set(Keys.REDUCE_ANIMATIONS, enabled)",
            "        set(Keys.REDUCE_ANIMATIONS, enabled)\n"
            "        writeLiveWallpaperFlag(REDUCE_ANIMATIONS_PREF, enabled)",
            1,
        )

        with self.assertRaises(PreferenceWriteOrderError) as ctx:
            validate_preference_write_order(self._stage(manager))

        self.assertIn("writes DataStore before SharedPreferences", str(ctx.exception))

    def test_rejects_a_bridge_that_stops_writing_shared_preferences(self) -> None:
        manager = self._live_manager().replace(
            "        writeLiveWallpaperFlag(ADAPTIVE_TINT_ENABLED_PREF, enabled)\n", "", 1
        )

        with self.assertRaises(PreferenceWriteOrderError) as ctx:
            validate_preference_write_order(self._stage(manager))

        self.assertIn("no longer writes SharedPreferences", str(ctx.exception))

    def test_rejects_settings_view_model_touching_shared_preferences(self) -> None:
        view_model = (
            "class SettingsViewModel {\n"
            '    fun x() { context.getSharedPreferences("freevibe_weather_wp", 0) }\n'
            "}\n"
        )

        with self.assertRaises(PreferenceWriteOrderError) as ctx:
            validate_preference_write_order(self._stage(self._live_manager(), view_model))

        self.assertIn("SettingsViewModel touches SharedPreferences", str(ctx.exception))

    def test_rejects_a_removed_bridge(self) -> None:
        manager = self._live_manager().replace("fun setReduceAnimations", "fun setReduceAnimationsRenamed", 1)

        with self.assertRaises(PreferenceWriteOrderError) as ctx:
            validate_preference_write_order(self._stage(manager))

        self.assertIn("no longer declares setReduceAnimations", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
