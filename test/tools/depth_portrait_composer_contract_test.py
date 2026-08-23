from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_depth_portrait_composer_uses_segmentation_with_gallery_export():
    source = read("app/src/main/java/com/chloemlla/aura/service/DepthPortraitComposer.kt")

    for token in (
        "SubjectSegmentation.getClient",
        "enableForegroundConfidenceMask",
        "DepthBackgroundStyle",
        "DepthFrameStyle",
        "segmentationApplied",
        "exportToGallery",
        "MediaStore.Images.Media.EXTERNAL_CONTENT_URI",
    ):
        assert token in source


def test_editor_exposes_depth_actions_and_parallax_handoff():
    screen = read("app/src/main/java/com/chloemlla/aura/ui/screens/editor/WallpaperEditorScreen.kt")
    view_model = read("app/src/main/java/com/chloemlla/aura/ui/screens/editor/WallpaperEditorViewModel.kt")

    for token in (
        "editor_wallpaper_depth_chip",
        "DepthPortraitControls",
        "launchLiveWallpaperPicker",
        "ParallaxWallpaperService",
        "editor_wallpaper_depth_export",
    ):
        assert token in screen

    for token in (
        "composeDepthPortrait",
        "exportDepthPortrait",
        "prepareDepthParallax",
        "pendingParallaxLaunch",
        "DepthPortraitComposer",
    ):
        assert token in view_model


def test_wallpaper_applier_accepts_generated_bitmaps_for_parallax():
    source = read("app/src/main/java/com/chloemlla/aura/service/WallpaperApplier.kt")

    assert "prepareParallaxFromBitmap" in source
    assert "Bitmap.CompressFormat.JPEG" in source
    assert "freevibe_parallax" in source
    assert "image_path" in source
