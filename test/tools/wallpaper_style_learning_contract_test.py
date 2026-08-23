from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
MODEL = ROOT / "app/src/main/java/com/chloemlla/aura/service/WallpaperStyleLearning.kt"
RANKER = ROOT / "app/src/main/java/com/chloemlla/aura/ui/screens/wallpapers/WallpaperFeedQuality.kt"
VIEWMODEL = ROOT / "app/src/main/java/com/chloemlla/aura/ui/screens/wallpapers/WallpapersViewModel.kt"
STYLE_ACTIONS = ROOT / "app/src/main/java/com/chloemlla/aura/ui/screens/wallpapers/WallpaperStyleActions.kt"
APPLY = ROOT / "app/src/main/java/com/chloemlla/aura/ui/screens/wallpapers/WallpaperApplyActions.kt"
SCREEN = ROOT / "app/src/main/java/com/chloemlla/aura/ui/screens/wallpapers/WallpapersScreen.kt"
SETTINGS = ROOT / "app/src/main/java/com/chloemlla/aura/ui/screens/settings/SettingsWallpaperSection.kt"
PREFS = ROOT / "app/src/main/java/com/chloemlla/aura/data/local/PreferencesManager.kt"


def test_wallpaper_style_learning_stays_local_and_has_threshold():
    model = MODEL.read_text(encoding="utf-8")
    prefs = PREFS.read_text(encoding="utf-8")

    assert "WallpaperStyleLearningProfile" in model
    assert "MIN_SIGNALS_FOR_RANKING = 3" in model
    assert "wallpaper_style_learning_json" in prefs
    assert "clearWallpaperStyleLearning" in prefs
    assert "Firebase" not in model
    assert "Retrofit" not in model
    assert "OkHttp" not in model


def test_wallpaper_style_learning_records_apply_favorite_skip_and_resets():
    ranker = RANKER.read_text(encoding="utf-8")
    viewmodel = VIEWMODEL.read_text(encoding="utf-8")
    style_actions = STYLE_ACTIONS.read_text(encoding="utf-8")
    apply_actions = APPLY.read_text(encoding="utf-8")
    screen = SCREEN.read_text(encoding="utf-8")
    settings = SETTINGS.read_text(encoding="utf-8")

    assert "styleLearningProfile.scoreFor(wallpaper)" in ranker
    assert "WallpaperStyleLearningSignal.APPLIED" in apply_actions
    assert "WallpaperStyleLearningSignal.FAVORITED" in apply_actions
    # Signal recording lives in the WallpaperStyleActions delegate (mutex-guarded);
    # the Hilt root only forwards to it.
    assert "WallpaperStyleLearningSignal.SKIPPED" in style_actions
    assert "styleActions.skipWallpaper" in viewmodel
    assert "skipWallpaper(wallpaper)" in screen
    assert "resetWallpaperStyleLearning" in settings
