from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def assert_contains(source: str, expected: str, label: str) -> None:
    assert expected in source, f"{label} missing {expected}"


def test_shared_browse_rail_keeps_release_chrome() -> None:
    source = read("app/src/main/java/com/freevibe/ui/components/BrowseRail.kt")

    for expected in (
        "data class BrowseRailItem",
        "fun BrowseRail(",
        "horizontalScroll(rememberScrollState())",
        "heightIn(min = 48.dp)",
        "shape = RoundedCornerShape(8.dp)",
        "Icons.Default.Check",
    ):
        assert_contains(source, expected, "BrowseRail")


def test_primary_media_screens_expose_matching_browse_rails() -> None:
    screens = {
        "wallpapers": read("app/src/main/java/com/freevibe/ui/screens/wallpapers/WallpapersScreen.kt"),
        "videos": read("app/src/main/java/com/freevibe/ui/screens/videowallpapers/VideoWallpapersScreen.kt"),
        "sounds": read("app/src/main/java/com/freevibe/ui/screens/sounds/SoundsScreen.kt"),
    }

    for name, source in screens.items():
        for expected in (
            "BrowseRail(",
            "browse_rail_popular",
            "browse_rail_newest",
            "browse_rail_categories",
        ):
            assert_contains(source, expected, name)

    assert_contains(screens["wallpapers"], "browse_rail_collections", "wallpapers")
    assert_contains(screens["videos"], "browse_rail_local", "videos")
    assert_contains(screens["sounds"], "browse_rail_local", "sounds")


def test_browse_rails_reuse_existing_category_collection_and_local_recovery_paths() -> None:
    root = read("app/src/main/java/com/freevibe/ui/FreeVibeRoot.kt")
    wallpapers = read("app/src/main/java/com/freevibe/ui/screens/wallpapers/WallpapersScreen.kt")
    videos = read("app/src/main/java/com/freevibe/ui/screens/videowallpapers/VideoWallpapersScreen.kt")
    sounds = read("app/src/main/java/com/freevibe/ui/screens/sounds/SoundsScreen.kt")

    for expected in ("onCategoriesClick", "onCollectionsClick"):
        assert_contains(wallpapers, expected, "wallpapers")
    for expected in (
        "onCategoriesClick = {",
        "Screen.Categories.route",
        "onCollectionsClick = {",
        "Screen.Collections.route",
    ):
        assert_contains(root, expected, "root")

    for expected in (
        "showFiltersSheet = true",
        "galleryLauncher.launch(videoWallpaperMimeTypes())",
        "secondaryAction = AuraStateAction(stringResource(R.string.video_wp_error_gallery)",
    ):
        assert_contains(videos, expected, "videos")

    for expected in (
        "soundCollectionsFor(railSoundTab).firstOrNull()",
        'createAudioPickerLauncher.launch("audio/*")',
        "secondaryAction = AuraStateAction(",
        "R.string.editor_sound_browse",
    ):
        assert_contains(sounds, expected, "sounds")
