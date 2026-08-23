from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
WALLPAPER_PACKAGE = ROOT / "app/src/main/java/com/chloemlla/aura/ui/screens/wallpapers"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_wallpaper_viewmodels_stay_under_feature_boundary_limit() -> None:
    view_models = sorted(WALLPAPER_PACKAGE.glob("*ViewModel.kt"))
    assert view_models, "No wallpaper ViewModel files found"

    oversized = {
        path.name: len(read(path).splitlines())
        for path in view_models
        if len(read(path).splitlines()) > 500
    }

    assert oversized == {}


def test_wallpaper_root_viewmodel_delegates_feature_state() -> None:
    source = read(WALLPAPER_PACKAGE / "WallpapersViewModel.kt")

    for token in (
        "WallpaperBrowseViewModel(",
        "WallpaperSearchActions(",
        "WallpaperApplyActions(",
        "WallpaperCommunityActions(",
        "browse.loadWallpapers",
        "searchActions.findSimilar",
        "applyActions.applyWallpaper",
        "community.uploadCommunityWallpaper",
    ):
        assert token in source

    assert "fun loadWallpapers(" not in source
    assert "fun fetchTopVoted(" not in source
    assert "fun reportSourceUrl(" not in source


def test_wallpaper_feature_helpers_are_split_from_hilt_root() -> None:
    expected_files = (
        "WallpaperBrowseViewModel.kt",
        "WallpaperSearchActions.kt",
        "WallpaperApplyActions.kt",
        "WallpaperCommunityActions.kt",
        "WallpaperIdentity.kt",
        "WallpapersState.kt",
    )

    for file_name in expected_files:
        assert (WALLPAPER_PACKAGE / file_name).exists(), f"{file_name} missing"

    browse = read(WALLPAPER_PACKAGE / "WallpaperBrowseViewModel.kt")
    state = read(WALLPAPER_PACKAGE / "WallpapersState.kt")
    identity = read(WALLPAPER_PACKAGE / "WallpaperIdentity.kt")

    for token in (
        "fun loadWallpapers(",
        "fun fetchTopVoted(",
        "fun isProviderDisabledTab(",
        "suspend fun loadUserStyles(",
    ):
        assert token in browse

    assert "data class WallpapersUiState" in state
    assert "enum class WallpaperTab" in state
    assert "fun reportSourceUrl(" in identity
    assert "fun resolveWallpaperVoteCount(" in identity
