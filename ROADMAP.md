# Aura Roadmap

This file tracks actionable work only. Completed work is removed; history lives in
git and `CHANGELOG.md`. Work blocked by owner actions, Firebase Console access,
physical-device validation, or toolchain gates belongs in `Roadmap_Blocked.md`.

## Current State

- Version: v6.36.0 / versionCode 136.
- Stack: Kotlin 2.1.0, AGP 8.7.3, Gradle 8.12, Compose Material 3, Hilt, Room,
  WorkManager, Media3 1.9.4, Coil 3.4.0, Firebase, NewPipe Extractor, yt-dlp.
- Distribution: local builds only. GitHub Actions workflows have been removed.
- 2026-07-15 deep audit shipped ~45 fixes (see CHANGELOG v6.35.0). Items below
  under "2026-07-15 audit backlog" were found by that audit but deferred.

## 2026-07-15 audit backlog

## P3

## Research-Driven Additions

Added 2026-07-16 from the research pass recorded in RESEARCH.md (same date). Items
assume the uncommitted 2026-07-15/16 UX-overhaul tree. Nothing here duplicates the
2026-07-15 audit backlog above or Roadmap_Blocked.md.

### P0

### P1

### P2

### P3

### 2026-07-22 pass

Added from the research pass recorded in RESEARCH.md (2026-07-22). Verified against the
committed v6.36.0 tree; nothing here duplicates the 2026-07-16 additions above or
Roadmap_Blocked.md. Toolchain-gated dependency bumps (OkHttp 5.4, Room 2.8.x, Compose BOM,
Kotlin/AGP) are intentionally NOT listed here — they remain under the N-1 blocker.

#### P2

- [ ] P2 — Split VideoWallpapersViewModel into delegates (1318 lines) per the CLAUDE.md pattern
  Why: last ViewModel violating the <500-line delegate rule; the pattern (loadJob ownership,
  lastApplied*Uri, timeout-as-failure) is easy to break in a monolith of this size.
  Evidence: app/src/main/java/com/freevibe/ui/screens/videowallpapers/VideoWallpapersViewModel.kt (1318 lines); precedent SoundBrowseViewModel / WallpaperSearchActions + test/tools/*_split_test.py gates.
  Touches: ui/screens/videowallpapers/VideoWallpapersViewModel.kt + new delegate files, test/tools/ new split gate
  Acceptance: file under ~500 lines; behavior unchanged (browse/apply/immersive paging); a
  new Python split contract gate mirrors the existing ones; all tests green.
  Complexity: M

- [ ] P2 — Trim SettingsViewModel into feature-slice delegates (960 lines)
  Why: second-largest ViewModel; settings surface keeps growing (community feeds, rotation,
  diagnostics) and delegate ownership is where loadJob/state bugs hide.
  Evidence: app/src/main/java/com/freevibe/ui/screens/settings/SettingsViewModel.kt (960 lines).
  Touches: ui/screens/settings/SettingsViewModel.kt + delegate files, test/tools/ split gate
  Acceptance: file under ~500 lines; settings behavior unchanged; split gate added; tests green.
  Complexity: M

#### P3

- [ ] P3 — Resolve the four orphaned legacy sound repositories (wire as opt-in or delete)
  Why: FreesoundRepository/AudiusRepository/CcMixterRepository/SoundCloudRepository are not
  DI-wired or called; they are latent maintenance and imply source coverage that does not
  exist. WallYou's breadth signal argues for re-wiring; the wedge argues for deletion.
  Evidence: RESEARCH.md 2026-07-22; classes under app/src/main/java/com/freevibe/data/remote/* and repositories, only self-package references; CLAUDE.md notes them kept "for future compatibility" (design tension — flag before deleting).
  Touches: data/remote/{freesound,audius,ccmixter,soundcloud}/*, matching repositories, DI, sound tab enum (if re-wiring)
  Acceptance: either the repos are removed with tests/docs updated, or one is re-wired as an
  opt-in sound source behind a setting with a passing integration test; no orphaned classes remain.
  Complexity: M

- [ ] P3 — Add Microsoft Spotlight + Picsum "picture of the day" wallpaper sources
  Why: cheap breadth parity with WallYou using free, keyless endpoints; low battery/privacy
  cost, opt-in like existing providers.
  Evidence: WallYou source registry (App.kt) https://github.com/you-apps/WallYou ; Aura already ships NASA APOD + Wikimedia POTD, so the provider-source plumbing exists.
  Touches: data/remote provider classes, provider registry/catalog, docs/security/network-endpoints.json, source toggle UI
  Acceptance: both sources appear as opt-in providers, fetch and render in the wallpaper grid,
  and are recorded in the endpoints manifest; provider-policy contract gate passes.
  Complexity: M

- [ ] P3 — Optional clock/date overlay on applied/live wallpapers
  Why: recurring niche ask (Paperize #533); opt-in, no background cost when off.
  Evidence: Paperize #533 https://github.com/Anthonyy232/Paperize/issues ; existing wallpaper editor overlay pipeline (WallpaperEditorScreen).
  Touches: editor overlay composer, live wallpaper renderer, settings toggle
  Acceptance: an opt-in overlay renders time/date on the wallpaper with position/format
  options; off by default; screenshot/Roborazzi coverage for the overlay state.
  Complexity: L

### 2026-07-22 UX pass

Added from the in-app screen audit recorded in RESEARCH.md (2026-07-22, "UX & State
Handling"). Verified against the committed v6.36.0 tree; none duplicate the dependency/
architecture items above or the device-gated accessibility items in Roadmap_Blocked.md.

#### P2

#### P3

- [ ] P3 — Add settings search / jump-to-section
  Why: Settings has 10+ domain sections and no search; Backup, Diagnostics, and API-key
  management are scroll-buried despite existing section anchors.
  Evidence: RESEARCH.md 2026-07-22; ui/screens/settings/SettingsSectionNavigation.kt (anchors exist, no search); no search field found under ui/screens/settings.
  Touches: SettingsScreen top bar (search field), section-index/anchor scroll, string index of section titles
  Acceptance: typing in a settings search field filters/jumps to matching sections; keyboard
  and TalkBack usable; a test asserts a query scrolls to the right section.
  Complexity: M

