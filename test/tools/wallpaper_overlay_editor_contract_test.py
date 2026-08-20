from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def test_overlay_state_and_renderer_are_local_only():
    source = read("app/src/main/java/com/freevibe/ui/screens/editor/WallpaperEditorViewModel.kt")

    for token in [
        "data class WallpaperOverlayLayer",
        "enum class WallpaperOverlayType",
        "enum class WallpaperSticker",
        "overlayLayers: List<WallpaperOverlayLayer>",
        "selectedOverlayId",
        "canUndoOverlay",
        "addTextOverlay",
        "addStickerOverlay",
        "undoOverlayEdit",
        "renderWallpaperOverlays",
        "drawTextOverlay",
        "drawStickerOverlay",
    ]:
        assert token in source

    assert "remote sticker" not in source.lower()
    assert "account" not in source.lower()


def test_apply_export_and_parallax_use_rendered_overlay_bitmap():
    source = read("app/src/main/java/com/freevibe/ui/screens/editor/WallpaperEditorViewModel.kt")

    assert "renderBitmapForOutput(defaultOverlayText)" in source
    assert "renderBitmapForOutputAsync(defaultOverlayText: String)" in source
    assert source.count("renderBitmapForOutputAsync(") >= 4
    # The rendered bitmap is checked against the state the render read *and* the
    # state as it is now. A filter render finishing during the write puts a
    # different bitmap on screen, so the snapshot alone would free something the
    # editor is still painting.
    assert "recycleRenderedBitmap(bitmap, snapshot, _state.value)" in source
    assert source.count("recycleRenderedBitmap(bitmap, snapshot, _state.value)") >= 3
    assert "wallpaperApplier.applyFromBitmap(bitmap, target)" in source
    assert "depthPortraitComposer.exportToGallery(bitmap)" in source
    assert "wallpaperApplier.prepareParallaxFromBitmap(bitmap" in source


def test_overlay_preview_and_controls_are_wired():
    source = read("app/src/main/java/com/freevibe/ui/screens/editor/WallpaperEditorScreen.kt")
    strings = read("app/src/main/res/values/strings.xml")

    for token in [
        "R.string.editor_wallpaper_layers_chip",
        "WallpaperEditorPreview",
        "OverlayLayerPreview",
        "WallpaperLayerControls",
        "detectDragGestures",
        "viewModel::moveOverlay",
        "viewModel::addTextOverlay",
        "viewModel.addStickerOverlay",
        "viewModel::undoOverlayEdit",
        "viewModel::exportEditedWallpaper",
    ]:
        assert token in source

    for key in [
        "editor_wallpaper_layers_chip",
        "editor_wallpaper_layers_add_text",
        "editor_wallpaper_layers_add_sticker",
        "editor_wallpaper_layers_scale",
        "editor_wallpaper_layers_rotation",
    ]:
        assert key in strings
