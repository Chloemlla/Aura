import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
GALLERY = ROOT / "app/src/main/java/com/chloemlla/aura/service/AgslShaderGallery.kt"
SETTINGS = ROOT / "app/src/main/java/com/chloemlla/aura/ui/screens/settings/SettingsSmartLiveSection.kt"
WEATHER = ROOT / "app/src/main/java/com/chloemlla/aura/service/WeatherWallpaperService.kt"


def test_curated_shader_gallery_has_static_fallback_and_no_custom_input():
    gallery = GALLERY.read_text(encoding="utf-8")
    settings = SETTINGS.read_text(encoding="utf-8")

    assert len(re.findall(r"AgslShaderPreset\(\s+id =", gallery)) == 6
    assert "Build.VERSION_CODES.TIRAMISU" in gallery
    assert "drawStaticFallback" in gallery
    assert "RuntimeShader" in gallery
    assert "TextField" not in settings
    assert "OutlinedTextField" not in settings
    assert "AgslShaderGallery.presets.map { it.id }" in settings
    assert "SettingsRadioOptionRow" in settings


def test_weather_wallpaper_reads_curated_shader_preset_key():
    weather = WEATHER.read_text(encoding="utf-8")

    assert "LIVE_WALLPAPER_SHADER_PRESET_PREF" in weather
    assert "AgslShaderGallery.sanitizeId" in weather
    assert "shaderRenderer.draw(canvas, preset)" in weather
    assert '"shader:${it.id}"' in weather
