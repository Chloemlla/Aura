from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SOUNDS_PACKAGE = ROOT / "app/src/main/java/com/chloemlla/aura/ui/screens/sounds"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def test_sound_viewmodels_stay_under_feature_boundary_limit() -> None:
    view_models = sorted(SOUNDS_PACKAGE.glob("*ViewModel.kt"))
    assert view_models, "No sound ViewModel files found"

    oversized = {
        path.name: len(read(path).splitlines())
        for path in view_models
        if len(read(path).splitlines()) > 500
    }

    assert oversized == {}


def test_sound_root_viewmodel_delegates_feature_state() -> None:
    source = read(SOUNDS_PACKAGE / "SoundsViewModel.kt")

    for token in (
        "SoundBrowseViewModel(",
        "SoundBrowseQueries(",
        "SoundCommunityFeed(",
        "SoundYouTubeActions(",
        "SoundPlaybackActions(",
        "SoundApplyActions(",
        "SoundCommunityActions(",
        "SoundSelectionResolver(",
        "browse.selectTab",
        "youtubeActions.searchYouTube",
        "playback.togglePlayback",
        "community.uploadSound",
    ):
        assert token in source

    assert "fun loadSounds(" not in source
    assert "fun fetchTopHits(" not in source
    assert "fun runYouTubeSearch(" not in source


def test_sound_feature_helpers_are_split_from_hilt_root() -> None:
    expected_files = (
        "SoundBrowseViewModel.kt",
        "SoundBrowseQueries.kt",
        "SoundCommunityFeed.kt",
        "SoundYouTubeActions.kt",
        "SoundPlaybackActions.kt",
        "SoundApplyActions.kt",
        "SoundCommunityActions.kt",
        "SoundSelectionResolver.kt",
        "SoundIdentity.kt",
        "SoundsState.kt",
    )

    for file_name in expected_files:
        assert (SOUNDS_PACKAGE / file_name).exists(), f"{file_name} missing"

    browse = read(SOUNDS_PACKAGE / "SoundBrowseViewModel.kt")
    queries = read(SOUNDS_PACKAGE / "SoundBrowseQueries.kt")
    community_feed = read(SOUNDS_PACKAGE / "SoundCommunityFeed.kt")
    youtube = read(SOUNDS_PACKAGE / "SoundYouTubeActions.kt")
    state = read(SOUNDS_PACKAGE / "SoundsState.kt")
    identity = read(SOUNDS_PACKAGE / "SoundIdentity.kt")

    for token in (
        "fun loadMore(",
        "fun refresh(",
        "fun selectTab(",
        "fun currentDownloadType(",
        "private fun loadSounds(",
    ):
        assert token in browse

    assert "fun buildQueries(" in queries
    assert "fun bundledSoundsFor(" in queries
    assert "fun loadCommunityTab(" in community_feed

    for token in (
        "fun searchYouTube(",
        "fun importYouTubeUrl(",
        "fun loadDefaultYouTube(",
        "fun shouldRefreshYouTubePreview(",
        "suspend fun loadSimilar(",
    ):
        assert token in youtube

    assert "data class SoundsUiState" in state
    assert "enum class SoundTab" in state
    assert "fun matchesSoundIdentity(" in identity
    assert "fun Sound.youtubeVideoId(" in identity
