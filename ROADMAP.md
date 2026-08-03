# Aura Roadmap

This file contains incomplete, actionable work only. Completed work lives in git and
`CHANGELOG.md`; externally blocked work belongs in `Roadmap_Blocked.md`.

## Research-Driven Additions

### P0

### P1

- [ ] P1 — Test production composables instead of look-alike route fixtures
  Why: screenshot/accessibility gates can stay green while real screens regress because debug fixtures redraw simplified UIs.
  Evidence: `debug/.../AuraRouteStateFixtures.kt`; `AuraRouteStateScreenshotTest.kt`; `tools/accessibility_release_gate_check.py`; Android Compose testing guidance.
  Touches: production screen state injection, Roborazzi tests, accessibility gate, pseudo/RTL/theme fixtures.
  Acceptance: major loading/empty/error/ready/permission states render actual production composables with fake dependencies in light/dark, compact/expanded, pseudo/RTL, and 200% font cases; Compose accessibility checks run; deleting a production symbol breaks the gate; fixture-only surfaces cannot satisfy release.
  Complexity: L

### P2

- [ ] P2 — Trim SettingsViewModel into feature-slice delegates (960 lines)
  Why: settings keeps growing across providers, rotation, community, and diagnostics, concentrating state/job ownership in one ViewModel.
  Evidence: `ui/screens/settings/SettingsViewModel.kt` (960 lines).
  Touches: `SettingsViewModel.kt`, feature delegates, tests and split gate.
  Acceptance: the file is under about 500 lines, behavior is unchanged, delegate job ownership is explicit, a split gate exists, and tests pass.
  Complexity: M

- [ ] P2 — Extend Settings search from sections to row-level anchors
  Why: the shipped section-title/description search cannot find visible controls such as OLED theme, Wi-Fi, backup, or App Check.
  Evidence: `SettingsSearch.kt`; isolated API 35 queries “theme” and “OLED.”
  Touches: settings search index, row metadata/anchors, localized resources, navigation/highlight tests.
  Acceptance: localized row labels, descriptions, and intentional aliases match; selecting a result expands and scrolls/highlights the exact row; tests cover OLED, Wi-Fi, backup, App Check, YouTube, battery saver, and no-result behavior.
  Complexity: M

- [ ] P2 — Close residual runtime localization gaps
  Why: the pseudo/RTL gate exists, but user-visible editor labels and ViewModel messages remain outside resources and outside the current scanner.
  Evidence: `WallpaperEditorScreen.kt`; `WallpaperEditorViewModel.kt`; `FavoritesViewModel.kt`; `SettingsViewModel.kt`; `tools/compose_hardcoded_string_check.py`.
  Touches: residual string resources/formatters, ViewModels/models, hardcoded-string gate, production pseudo-locale tests.
  Acceptance: identified runtime literals are resource-backed and locale-formatted, the gate scans composables plus ViewModels/models, production route tests exercise them under XA/XB, and real translations/language picker remain deferred until reviewed.
  Complexity: M

- [ ] P2 — Accept user-owned shared image and audio through bounded ingestion
  Why: Aura supports JSON sharing and image “Set as,” but not normal share/edit entry into its existing image crop and Sound Editor workflows.
  Evidence: `AndroidManifest.xml`; `MainActivity.kt`; DarkModeLiveWallpaper sharing; Ringdroid open/edit flow.
  Touches: manifest filters, external-media dispatcher, `MediaIngestion`/`ShareOutbox`, image editor/crop and Sound Editor navigation/tests.
  Acceptance: user-owned/generated `ACTION_SEND`/`ACTION_EDIT` image/audio routes to a target preview; MIME is sniffed, copy is bounded, `content://` ClipData/read grants are used, cleanup is tested, and malformed/revoked inputs recover; remote items remain link-only/disabled until the blocked per-license capability model permits them.
  Complexity: M

- [ ] P2 — Build an indexed multi-folder local wallpaper catalog
  Why: one rotation folder cannot represent collectors' existing libraries, tags, missing folders, or independent home/lock source sets.
  Evidence: current single-folder preferences/SAF path; Paperize, Peristyle, Muzei, and Fossify Gallery.
  Touches: persisted SAF grants, Room media index/tags, scanner/dedupe, local browse/search, rotation source picker/diagnostics.
  Acceptance: users can add/remove multiple SAF folders, rescan incrementally, tag/search/dedupe items, diagnose revoked/missing grants, and choose per-home/lock collections without broad storage permission.
  Complexity: L

### P3

- [ ] P3 — Add Microsoft Spotlight as an opt-in daily-image source after terms validation
  Why: a keyless daily-image source adds low-frequency breadth without another high-volume feed.
  Evidence: WallYou source registry; existing Bing/NASA/Wikimedia daily-source plumbing.
  Touches: provider registry/client, attribution/licensing, network-endpoints manifest, source toggle UI/tests.
  Acceptance: after the P0 capability registry lands, a stable endpoint and use/attribution terms pass its policy gate; Spotlight is opt-in, preserves source URL/provenance, degrades visibly, and is recorded in the endpoint manifest. Lorem Picsum is intentionally excluded because `ProviderDisclosure.kt` forbids new default sourcing.
  Complexity: M

- [ ] P3 — Optional clock/date overlay on applied/live wallpapers
  Why: Paperize issue 533 validates the niche, and Aura already has an overlay composer; it adds no background cost while off.
  Evidence: Paperize issue 533; `WallpaperEditorScreen.kt` overlay pipeline.
  Touches: editor overlay composer, live-wallpaper renderer, settings/format controls, screenshots.
  Acceptance: an opt-in overlay renders localized time/date with time-zone and 12/24-hour behavior, contrast/burn-in-safe position choices, and no background work while off; supported static/live paths have production screenshot coverage.
  Complexity: L
