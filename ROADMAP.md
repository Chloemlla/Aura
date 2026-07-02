# Aura Roadmap

This file tracks actionable work only. Completed work is removed; history lives in
git and `CHANGELOG.md`. Work blocked by owner actions, Firebase Console access,
physical-device validation, or toolchain gates belongs in `Roadmap_Blocked.md`.

## Current State

- Version: v6.34.6 / versionCode 133.
- Stack: Kotlin 2.1.0, AGP 8.7.3, Gradle 8.12, Compose Material 3, Hilt, Room,
  WorkManager, Media3, Coil 2.7.0, Firebase, NewPipe Extractor, yt-dlp.
- Distribution: local builds only. GitHub Actions workflows have been removed.
- Recent roadmap features already in code and removed from this file: personal
  microphone-to-ringtone recording, Wikipedia POTD, Lemmy wallpapers,
  no-repeat wallpaper rotation, time-of-day sound profiles, 24H wallpaper packs,
  alarm shuffle, sound metadata badges, video technical summaries, live wallpaper
  icon dim/reveal, direct-boot live wallpaper flags, ringtone restoration, whole
  library export/import services, manifest consistency tooling, local AAB dry-run
  evidence tooling, and Settings feature-owned decomposition.

## P1

### Universal on-device search

Search is split by content type and does not query saved/local content from one
entry point.

Acceptance:
- A single search entry returns segmented results for wallpapers, videos, sounds,
  collections, downloads, favorites, and local files.
- Saved/local results work offline.
- Provider/network results are clearly labeled and disabled offline.
- Search history stays local and can be cleared.

## P2

### FOSS build flavor boundary

Aura still has one full-feature app variant with Firebase, Google Services,
Play Services ML Kit, and App Check in the main dependency graph.

Acceptance:
- A `foss` product flavor builds without Firebase or Play Services dependencies.
- Community features are compile-time absent or replaced by clear local-only
  unavailable states.
- Parallax/subject features fall back without Play Services ML Kit.
- `tools/fdroid_preflight.py --expect-pass` succeeds for the FOSS variant.

### Consistent browse rails

Wallpapers, Videos, Sounds, Ringtones, and Notifications still use different
browse skeletons.

Acceptance:
- Each primary media surface exposes consistent Popular, Newest, Categories, and
  Collections/Local rails.
- Provider-specific filters live under refine controls.
- Empty/error states identify the affected source without hiding local content.

### Depth/portrait wallpaper composer

Subject segmentation, smart crop, parallax, weather effects, and dual wallpaper
exist, but there is no combined portrait/depth composer.

Acceptance:
- A user can select a wallpaper, segment the subject, place it over a styled or
  blurred background, and apply/export the result.
- Optional shape/frame and parallax depth presets reuse existing services.
- Failure paths fall back to the original wallpaper with user-visible feedback.

### Wallpaper editor sticker and text overlays

Crop, smart crop, blur, and color/filter editing exist, but no local layer editor
for text/stickers exists.

Acceptance:
- Users can add text and local sticker layers, move/scale/rotate them, undo
  edits, and apply/export through the existing wallpaper pipeline.
- Final render matches preview inside crop bounds.
- No remote sticker store or account surface is added.

### Local theme pack recipe

Wallpaper/video wallpaper, sounds, widgets, and launcher shortcut recipes are
not grouped into a portable local pack.

Acceptance:
- Users can save and import a local pack with wallpaper/video references,
  optional sounds, widget tint/preview metadata, and launcher shortcut recipe
  data.
- Export/import uses JSON plus local assets where permitted.
- Unsupported launcher actions degrade to clear local instructions.

### Local Play-ready AAB dry-run lane

GitHub workflow AAB logic is no longer the source of truth. Local release tooling
still needs a first-class bundle dry run for Play readiness.

Acceptance:
- Local release dry run produces a signed `.aab` alongside the GitHub/Obtainium
  APK artifact.
- Bundle metadata, versionCode/versionName, signing lineage, SHA-256, and Play
  App Signing owner steps are checked by local tools/docs.
- APK release behavior remains unchanged.

### HEIF/AVIF ingestion and metadata scrub policy

Format detection exists, but accepted formats and metadata stripping are not
defined by one source-backed matrix across auto-rotation, local apply, editor,
and community upload flows.

Acceptance:
- A single policy defines supported image formats per flow.
- HEIF/AVIF are accepted and safely transcoded or rejected with actionable UI
  copy.
- Metadata/location stripping is tested for community uploads.

### Split WallpapersViewModel into feature-scoped ViewModels

`WallpapersViewModel.kt` still owns tab state, Discover cache, search,
find-similar, match-my-theme, color search, category filtering, community
favorites, daily pick, random wallpaper, and pagination.

Acceptance:
- No wallpaper ViewModel exceeds 500 lines.
- Discover, search/tab, and detail/action state are isolated.
- Existing wallpaper tests pass after the split.

### Split Sounds browse and playback modules

Sounds browse, YouTube resolution, playback, community upload/report/block,
downloads, editor routing, and diagnostics remain tightly coupled.

Acceptance:
- Browse/feed, playback/progress, upload/community, and YouTube resolution state
  are split into feature-owned modules.
- Playback continues across tab changes.
- Existing sound tests pass and add a tab-switch/playback contract test.

## P3

### Curated AGSL shader gallery

`AgslEffectPipeline.kt` is scaffolded, but no user-selectable shader gallery is
available.

Acceptance:
- Five to ten curated shader presets can be selected as live-wallpaper
  backgrounds.
- Android 12 and below receive a static fallback.
- User-authored shader input is not exposed.

### On-device wallpaper style learning

Aura has deterministic quality ranking and style preferences, but no learned
on-device taste model.

Acceptance:
- Apply, favorite, and skip signals stay local.
- Discover ranking adapts after enough interactions.
- Users can reset learned preferences in Settings.

## Research-Driven Additions

### P1

- [ ] P1 - Adaptive large-screen shell
  Why: Aura has no `WindowSizeClass`, navigation-rail, or list-detail adaptive layout usage, while Android tablet/foldable support is now a platform expectation.
  Evidence: `FreeVibeRoot.kt`, grep for `WindowSizeClass`/`NavigationSuiteScaffold`/`ListDetailPaneScaffold`, Android adaptive UI docs, `Roadmap_Blocked.md` Android 17 large-screen smoke item.
  Touches: `app/src/main/java/com/freevibe/ui/FreeVibeRoot.kt`, primary media screens, detail routes, screenshot fixtures.
  Acceptance: Compact devices keep the existing bottom navigation; expanded widths use a rail or permanent navigation surface; at least Wallpapers and Sounds support list-detail or stable two-pane behavior; screenshot fixtures cover compact and expanded layouts.
  Complexity: L

- [ ] P1 - Source health diagnostics console
  Why: Aura has many network providers and source metrics, but users cannot see which provider failed, when it last succeeded, or what fallback is active.
  Evidence: `SourceMetrics.kt`, `SettingsDiagnosticsSection.kt`, `ProviderNetworkPolicy.kt`, `docs/security/network-endpoints.json`, competitor auto-rotation reliability issues, dontkillmyapp.com.
  Touches: `SourceMetrics.kt`, repositories, `SettingsDiagnosticsSection.kt`, `docs/security/network-endpoints.json`, provider tests.
  Acceptance: Settings diagnostics shows per-provider last success, last failure, disabled/degraded state, retry action, and offline/local fallback status without exposing secrets.
  Complexity: M

### P2

- [ ] P2 - Embedded Photo Picker import polish
  Why: Aura already uses scoped picker flows and compileSdk-compatible shims, but Android's newer picker customization can make local wallpaper/collection import faster and more private where supported.
  Evidence: `AuraPickVisualMedia.kt`, `PhotoPickerCustomization.kt`, `WallpapersScreen.kt`, `CollectionsScreen.kt`, Android Photo Picker docs.
  Touches: `AuraPickVisualMedia.kt`, `PhotoPickerCustomization.kt`, `WallpapersScreen.kt`, `CollectionsScreen.kt`, media ingestion tests.
  Acceptance: Supported devices open a customized/embedded picker for wallpaper and collection imports; unsupported devices fall back to the current picker; no broad storage permission is introduced.
  Complexity: M

### P1

- [ ] P1 - Harden provider credential storage
  Why: Optional provider keys include a paid Stability AI key; DataStore is backup-excluded but still documented as app-private storage without Keystore-backed at-rest protection.
  Evidence: `PreferencesManager.kt`, `docs/security/provider-credential-storage.json`, `app/src/main/res/xml/backup_rules.xml`, Android Auto Backup docs.
  Touches: `app/src/main/java/com/freevibe/data/local/PreferencesManager.kt`, Settings API-key dialogs, provider repositories, redaction tests, credential storage policy docs.
  Acceptance: User-entered provider keys migrate from plain DataStore to Android Keystore-backed encrypted storage or an equivalent tested encrypted wrapper; old DataStore key values are removed after migration; corrupt/locked Keystore fallback is user-visible and non-crashing; backup exclusions and diagnostics redaction remain tested.
  Complexity: L

- [ ] P1 - Purge workflow-era release documentation
  Why: README, architecture, contribution, and distribution docs still reference GitHub workflow artifacts/secrets even though Aura now ships from local-only release gates.
  Evidence: `README.md`, `ARCHITECTURE.md`, `CONTRIBUTING.md`, `docs/distribution/*`, commits `ec73ea7` and `274e876`, F-Droid/Izzy inclusion guidance.
  Touches: `README.md`, `ARCHITECTURE.md`, `CONTRIBUTING.md`, `docs/distribution/*`, release metadata consistency tests.
  Acceptance: Active docs describe local signed APK/AAB release commands as the only build/release path; `rg "GitHub Actions|verify.yml|release.yml|repository secrets|workflow artifacts"` returns only historical/disabled-policy references or tests that explicitly validate workflows are absent.
  Complexity: S

- [ ] P1 - Add OEM battery recovery guidance to diagnostics
  Why: Scheduled wallpaper, ringtone shuffle, weather, and bundled-content work depend on WorkManager and foreground/background behavior that OEM battery managers often suppress.
  Evidence: `BackgroundWorkDiagnosticsReader.kt`, `CrashDiagnosticsCollector.kt`, `docs/background-work-network-posture.json`, Android WorkManager docs, dontkillmyapp.com Samsung guidance.
  Touches: `BackgroundWorkDiagnosticsReader.kt`, `CrashDiagnosticsCollector.kt`, `SettingsDiagnosticsSection.kt`, `strings.xml`, background-work tests, support bundle text.
  Acceptance: Background diagnostics show manufacturer-aware recovery guidance for Data Saver, battery optimization, metered network, missing WorkInfo, and long-enqueued work; support bundles include the same action hints; JVM tests cover Samsung/Pixel/generic hint selection without requiring a device.
  Complexity: M

### P2

- [ ] P2 - Add pseudolocale and RTL release gates
  Why: Aura centralized visible strings but still has only default `values` resources and no automated expanded-text or RTL smoke path before real translations.
  Evidence: `app/src/main/res/values/strings.xml`, `docs/localization/hardcoded-string-baseline.json`, Android localization docs, Android pseudolocale docs, Compose accessibility test docs.
  Touches: `app/build.gradle.kts`, accessibility route fixtures, screenshot/Robolectric tests, `docs/localization/hardcoded-string-baseline.json`, `strings.xml`.
  Acceptance: Debug builds enable pseudolocales; compact route fixtures render under English XA and AR XB pseudolocales; tests fail on clipped primary navigation/action text or obvious LTR-only assumptions; no real human translation pack is required.
  Complexity: M

- [ ] P2 - Normalize provider backoff and fallback policy
  Why: Aura has many remote providers, but several endpoint records still have no host-specific backoff or cache fallback policy, causing uneven source degradation behavior.
  Evidence: `docs/security/network-endpoints.json`, `ProviderNetworkPolicy.kt`, `SourceMetrics.kt`, Wall You and WallFlow multi-source patterns, Android WorkManager retry guidance.
  Touches: provider repositories, `RateLimitInterceptor.kt`, `ProviderNetworkPolicy.kt`, `SourceMetrics.kt`, `docs/security/network-endpoints.json`, provider tests.
  Acceptance: Every network provider has a documented timeout/rate-limit/backoff/cache-fallback policy; 429/Retry-After, timeout, DNS, and disabled-provider cases have tests; source diagnostics can display the active policy without exposing provider keys.
  Complexity: L
