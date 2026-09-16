# Aura Originals: Curation Guide

Aura includes 25 original tones so the main Sounds tabs are useful without a
network connection. The first pack contains 10 ringtones, 10 notifications,
and 5 alarms. It adds about 431 KiB to the source resources before APK
compression.

Every tone is synthesized by
[`tools/generate_aura_originals.py`](../tools/generate_aura_originals.py). The
script is deterministic and uses standard waveform synthesis, so it does not
sample recordings or copy a third-party melody. FFmpeg encodes the generated
PCM audio as mono Ogg Vorbis.

## Regenerate the pack

Run this from the repository root with Python and FFmpeg on `PATH`:

```powershell
python tools\generate_aura_originals.py
```

The script replaces only `aura_*.ogg` files in `app/src/main/res/raw`. Review
the generated files, then run the bundled-content and local-media tests:

```powershell
.\gradlew.bat :app:testFullDebugUnitTest --tests "com.chloemlla.aura.service.BundledContentProviderTest" --tests "com.chloemlla.aura.service.LocalMediaLocatorTest"
```

## Acceptance checks

- Each item must start with a clean attack and end without an audible cut.
- Ringtones should remain recognizable on a small phone speaker.
- Notifications must stay short enough for repeated chat alerts.
- Alarms should become noticeable without using clipped or harsh output.
- Resource locators must preview, download, and apply without an HTTP request.
- Names and tags must describe what the tone sounds like.

`BundledContentProviderTest` checks the 10, 10, and 5 category counts, unique
IDs, CC0 metadata, resource existence, and Ogg headers. Device release testing
still includes listening at a safe volume and applying one tone from each tab.

## Licensing

The generated audio files are dedicated under CC0 1.0. See
[`aura-originals-license.md`](aura-originals-license.md). The generator source
remains covered by the repository's MIT license.

## Future additions

Additions should widen the sound palette without turning the APK into a large
media archive. A downloadable expansion can use
`app/src/main/assets/aura_originals_manifest.json` once it has reviewed URLs,
hashes, sizes, and provenance. The manifest stays empty until such a pack has
real files and device evidence.
