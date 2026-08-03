# Research — Aura
Date: 2026-07-29 — replaces all prior research.
Confidence: unqualified project facts are **Verified**; uncertainty is labeled
**Likely** or **Needs live validation**.

## Executive Summary

Aura 6.40.0 (versionCode 141) is a mature, local-first Android personalization suite:
it combines static and live wallpapers, sound discovery/editing, scheduling, offline
storage, and optional community services without ads, subscriptions, or a required
account. Its strongest shape is breadth backed by unusually deep local tooling; its
highest-value direction is now to make that breadth trustworthy under failure rather
than add another feed. The priority opportunities are: (1) remove the vulnerable
archive runtime and bound extraction, (2) make provider/build/channel capability truth
single-source, (3) make whole-library restore versioned, portable, and atomic, (4)
protect generated assets from reference-blind pruning, (5) close the exported
automation-gate bypass, (6) make live-video playback self-healing and soak-tested, (7)
centralize wallpaper-apply side effects, (8) generate release/channel/legal truth from
one manifest, (9) test production composables rather than look-alike fixtures, and
(10) add a scoped local-media catalog and safe user-owned media share targets.
The existing `Roadmap_Blocked.md` N-1/API gate is deadline-critical: Google Play
requires target API 36 for updates on 2026-08-31.

## Product Map

### Core workflows

- Browse/search multi-source wallpapers, live videos, ringtones, notifications, and alarms.
- Edit, crop, preview, apply, download, favorite, collect, share, and restore media.
- Rotate wallpaper by schedule, screen events, weather, dark/light mode, shortcuts, or automation.
- Import local image/video/audio, generate optional AI wallpapers, and keep selected media offline.
- Opt into Firebase community uploads/voting and export local diagnostics/support data.

### User personas

- Privacy-conscious Android users who want a no-account, no-ad personalization tool.
- Power users automating home/lock wallpapers and system sounds.
- Local-media collectors who need durable organization, editing, backup, and recovery.
- Optional community contributors; FOSS users who expect unsupported services to be explicit.

### Platforms and distribution

- Android 8.0+ (`minSdk 26`), phone-first with adaptive tablet/foldable scaffolding;
  Kotlin/Compose app plus a baseline-profile module and Firebase Node 22 functions.
- `full` and Firebase/Play-free `foss` flavors; releases are built locally, with no
  GitHub Actions workflow. The live target/compile SDK is 35.

### Key integrations and data flows

- Remote media: Wallhaven, Bing, Wikimedia, NASA, Pexels, Pixabay, Reddit RSS, Lemmy,
  YouTube/NewPipe/yt-dlp, Freesound, Jamendo, Audius, ccMixter, Klipy, and Open-Meteo.
- Local state: Room database schema version 16, DataStore, app-managed files, SAF grants, Media3/Coil caches,
  WorkManager schedules, Android `WallpaperManager`, and ringtone/contact APIs.
- Optional services: Firebase Auth/RTDB/Storage/Functions/App Check and ML Kit subject
  segmentation in the full flavor; Stability AI by user-supplied key.

## Competitive Landscape

- **Paperize** — Does independent home/lock rotation, large-folder import, scoped
  storage, and foldable fixes well. Learn its local-library UX, but avoid its v4
  settings/album reset by requiring staged, rollback-capable migration.
- **WallYou** — Makes provider breadth, health, and history visible. Learn explicit
  per-source failure states; avoid sub-15-minute remote rotation and its battery/network cost.
- **Muzei** — Has a durable versioned provider contract. Learn the lifecycle boundary,
  but do not expose third-party plugins before Aura's internal capability contract is stable.
- **UndeadWallpaper** — Rebuilds video playback after killed decoder threads and supports
  per-item playlists. Learn self-healing first; defer playlists until lifecycle soak tests pass.
- **Atmo Engine** — Integrates Android sharing, per-image crop, playlists, and effects
  cleanly. Learn its low-friction media entry points; avoid effects churn ahead of recovery.
- **Wallpaper Engine Mobile** — Offers explicit quality/FPS controls, playlists, and
  recovery guidance. Learn power-aware playback; avoid making a desktop companion mandatory.
- **Zedge** — Wins on catalog breadth and creator monetization. Preserve Aura's no-ad,
  no-coin, no-account differentiation; avoid marketplace moderation and tracking overhead.
- **Backdrops** — Shows the value of curation, provenance, and orientation-aware browsing.
  Learn editorial trust signals; avoid premium/social mechanics that conflict with local-first use.

## Security, Privacy, and Reliability

- **[Verified] Vulnerable archive runtime:** `fullReleaseRuntimeClasspath` resolves
  `org.apache.commons:commons-compress:1.12` through youtubedl-android 0.18.1
  (`app/build.gradle.kts`, `gradle/verification-metadata.xml`). That release is affected
  by multiple published archive denial-of-service flaws, and the updater path in
  `service/YtDlpUpdateManager.kt` has no app-level entry-count, expanded-byte, or
  compression-ratio budget. Constrain to a fixed release and stage bounded extraction.
- **[Verified] Exported automation bypass:** `MainActivity.handleShortcutSideEffects()`
  enqueues `ROTATE_NOW`/`SHUFFLE_NOW` without `ExternalAutomationGate`, while
  `TaskerActionReceiver` enforces opt-in and throttling. Because `MainActivity` is
  exported, an explicit intent can bypass the documented gate.
- **[Verified] Provider truth diverges from behavior:** `ProviderDisclosure.kt` marks
  Wikimedia and Reddit as dormant/legacy, while `WallpaperRepository.getDiscover()`,
  `WallpaperBrowseViewModel`, and `VideoWallpapersViewModel` still fetch them and
  `redditProviderEnabled` defaults true. `SourceAvailabilityPolicy.kt` also converts
  every Reddit 403 into permanent discontinuation; RFC 9110 does not make 403 permanent
  (410 is the explicit likely-permanent signal).
- **[Verified] Restore can partially mutate data:** `LibraryExporter.importLibrary()`
  parses but never checks `LibraryExportFile.version`, then mutates favorites,
  collections, search history, and DataStore sequentially. Later failure leaves a
  partial restore; local/AI file locators are silently dropped by HTTPS-only mapping.
- **[Verified] Generated files can be deleted while referenced:**
  `AiWallpaperRepository.pruneOldFilesInternal()` deletes every PNG beyond the newest
  50 without consulting favorites, collections, history, dark/light slots, packs, or
  widget state. `WallpaperApplyActions.toggleFavorite()` immediately deletes AI files
  on unfavorite, even if another record still points to them.
- **[Verified] Distribution policy contradicts metadata:**
  `docs/distribution/youtube-store-risk-profile.json` lists “YouTube-first” and
  “powered by yt-dlp” as cautious Play positioning, while
  `fastlane/metadata/android/en-US/full_description.txt` uses both. A channel-specific
  runtime capability and metadata gate is needed; absence of a documented rights basis
  must fail closed for Play.
- **[Likely] Live-video recovery is incomplete:** `VideoWallpaperService.kt` has no
  `MediaPlayer` error listener, forward-progress watchdog, bounded rebuild supervisor,
  or last-frame fallback. UndeadWallpaper 1.3.6 and WallYou issue 266 show the same
  OEM sleep/wake and long-running failure class; Aura needs an isolated soak harness.
- **[Verified] Destructive UX is inconsistent:** whole collection deletion
  (`CollectionsScreen.kt`) and downloaded-file deletion (`DownloadsScreen.kt`,
  `DownloadManager.kt`) are immediate, while the narrower operation of removing one
  collection item already offers Undo.

## Architecture Assessment

- **Provider boundary:** Replace scattered disclosure, network, preference, flavor, and
  UI decisions with one typed capability registry covering lifecycle, build/channel
  availability, configuration, permission, health, retry semantics, and attribution.
- **Persistence boundary:** Introduce an import plan/preflight and commit coordinator
  spanning one Room transaction plus staged DataStore/file changes. Add an asset-reference
  index so pruning, export, trash, and restore share ownership rules.
- **Apply boundary:** Normal browsing records history, undo, learning, night-variant
  state, feedback, and widget refresh in `WallpaperApplyActions`; AI/editor/crop paths
  call `WallpaperApplier` directly. One coordinator should apply an explicit side-effect
  policy exactly once for every entry path.
- **Live-wallpaper boundary:** Put player state, surface state, progress heartbeat,
  retry/backoff, and diagnostics behind a testable engine supervisor.
  Keep playlist/effect work dependent on this boundary.
- **UI/test boundary:** The debug-only `AuraRouteStateFixtures.kt` recreates screen-like
  UI, and `AuraRouteStateScreenshotTest` renders those replicas. Production routes need
  injectable state so screenshot/accessibility gates fail when real composables regress.
  The suite is JVM-heavy and has only two source files under `app/src/androidTest`.
- **Verified UI gaps:** On an isolated API 35 emulator, Settings search returned no
  result for row labels such as “theme”/“OLED” because `SettingsSearch.kt` indexes only
  section title/description. At default font scale, `SoundDetailScreen.kt` wrapped or
  clipped actions/microcopy, and `SoundUiTokens.kt` uses YouTube red on white below the
  4.5:1 normal-text target. `WallpaperEditorScreen.kt` enables Apply while its source is
  loading; slider changes can be lost because `setSourceBitmap()` does not replay them.
- **Offline gap:** `FavoriteEntity.offlinePath` is populated and mapped for full media,
  but `FavoritesScreen.kt` always renders `fav.thumbnailUrl`, defeating offline thumbnails.
- **Localization readiness:** `supportsRtl` and a pseudo/RTL release gate already exist,
  so duplicating that work would be wrong. The net gap is narrower: runtime-visible
  literals remain in `WallpaperEditorScreen.kt`, `WallpaperEditorViewModel.kt`,
  `FavoritesViewModel.kt`, and `SettingsViewModel.kt`, outside the current
  `compose_hardcoded_string_check.py` scope. Extract those before accepting real
  translations; widget/live-wallpaper localization remains in `Roadmap_Blocked.md`.
- **Release/documentation truth:** `app/build.gradle.kts` is 6.38.1/139 and the
  database schema is version 16, while release metadata, `README.md`, and `CLAUDE.md`
  still contain 6.38.0/138, schema version 14, or “Favorites” navigation claims. The release consistency check
  fails; the generated full-release OSS notice set also has 309 entries against a
  302-entry lock. Generate these surfaces from one manifest and gate drift.
- **Upgrade strategy:** Keep compileSdk-dependent Room/Media3/OkHttp/Coil/Compose work
  in the existing N-1 blocker, but treat the 2026-08-31 target-36 deadline as P0 release
  risk. WorkManager 2.11.2, Retrofit 3.0.0, yt-dlp 2026.07.04, Jackson 2.18.9, and
  Commons IO 2.16.1 need no separate upgrade item based on current primary advisories.
- **Category coverage:** Security, accessibility, residual i18n readiness, observability, testing, docs,
  packaging/distribution, offline resilience, and migration have actionable work below.
  Plugin ABI, sync/multi-user, device-only large-screen evidence, real translations, and
  the toolchain upgrade already live in `Roadmap_Blocked.md`; no duplicate was added.
  Android is the mobile product; separate iOS/desktop expansion is intentionally rejected.

## Rejected Ideas

- **Lorem Picsum as a new provider** — Aura's own `ProviderDisclosure.kt` says it is a
  dormant placeholder source that new default sourcing should avoid; remove it from the
  existing Spotlight item.
- **Ads, coins, streaks, creator marketplace, or mandatory accounts** — Zedge and
  Backdrops validate revenue/catalog breadth, but the operational, moderation, privacy,
  and philosophy costs outweigh fit.
- **Mandatory cloud/WebDAV sync or collaborative multi-user libraries** — Aura's
  no-account local-first position is a product advantage; the optional sync item is
  already blocked pending an explicit owner decision.
- **Unrestricted plugins, web pages, executables, or arbitrary shaders** — Muzei/Lively
  show the power and security/compatibility cost. Stabilize the existing blocked source
  ABI and internal capability model first.
- **`MANAGE_EXTERNAL_STORAGE` for local libraries** — Peristyle's convenience does not
  justify broad storage access; retain SAF/Photo Picker grants.
- **Persistent sub-15-minute remote rotation** — WallYou maintainers cite WorkManager,
  battery, and bandwidth constraints; Aura's event/local paths already cover fast changes.
- **Desktop companion, iOS port, or Wallpaper Engine parity** — materially expands the
  platform and support contract without solving current Android reliability gaps.
- **Custom SAM/MediaPipe segmentation or on-device LLM personalization** — Aura already
  has ML Kit subject segmentation; no measured Aura failure or training signal justifies
  model size, thermal cost, and maintenance.
- **Silent OLED recoloring** — published power savings are panel/content dependent and
  altering user art violates intent; keep existing explicit AMOLED controls.
- **Video playlists now** — validated by UndeadWallpaper and Wallpaper Engine, but already
  tracked behind the blocked GL/Media3 engine migration; recovery and soak evidence come first.
- **ProfilingManager, source-FPS frame hints, and embedded Photo Picker migration now**
  — useful after the target-36 toolchain lands or battery/jank evidence exists; current
  diagnostics, user FPS caps, and the reflective picker fallback are adequate, while
  the Jetpack embedded picker remains alpha.
- **Ultra HDR preservation** — **[Needs live validation]** until the complete Android
  wallpaper decode/edit/apply path is proven to preserve gain-map output on real devices.

## Sources

### Open-source and adjacent projects

- https://github.com/Anthonyy232/Paperize
- https://github.com/Anthonyy232/Paperize/releases/tag/v4.0.0
- https://github.com/Anthonyy232/Paperize/issues/533
- https://github.com/muzei/muzei
- https://github.com/muzei/muzei/blob/main/muzei-api/module.md
- https://github.com/you-apps/WallYou
- https://github.com/you-apps/WallYou/issues/266
- https://github.com/Hamza417/Peristyle
- https://github.com/maocide/UndeadWallpaper/releases/tag/v1.3.6
- https://github.com/saad-khan-rind/NOSAtmosphereEffect
- https://github.com/althafvly/ringdroid
- https://github.com/cvzi/darkmodewallpaper
- https://github.com/rocksdanister/lively/issues/137
- https://github.com/MM2-0/Kvaesitso/blob/main/docs/docs/user-guide/concepts/plugins.md
- https://github.com/FossifyOrg/Gallery
- https://github.com/patzly/doodle-android/issues/53
- https://github.com/topics/live-wallpaper
- https://f-droid.org/en/categories/wallpaper/
- https://github.com/fmhy/FMHY/wiki/Mobile

### Commercial products and community signal

- https://www.wallpaperengine.io/android/en
- https://help.wallpaperengine.io/en/mobile/faq.html
- https://help.wallpaperengine.io/en/mobile/workshop.html
- https://store.steampowered.com/app/431960/Wallpaper_Engine/
- https://play.google.com/store/apps/details?id=net.zedge.android
- https://help.zedge.net/hc/en-us/articles/360024313191-ZEDGE-for-Android-FAQ
- https://www.walliapp.com/support/en/playlists-walli-help/
- https://play.google.com/store/apps/details?id=com.backdrops.wallpapers
- https://www.reddit.com/r/androidapps/comments/1qq0mup/got_a_good_wallpaper_app_that_doesnt_allow_ai/
- https://www.reddit.com/r/androidapps/comments/1q56v45/what_app_is_the_best_for_wallpapers/
- https://www.reddit.com/r/androidapps/comments/1t7xa1j/app_to_change_wallpaper_from_online_sources_muzei/
- https://www.reddit.com/r/androidapps/comments/1p90p95/any_app_like_zedge_zedge_now_just_has_waaaay_to/
- https://www.reddit.com/r/wallpaperengine/comments/1urhbe9/problems_with_android_app/

### Standards and platform APIs

- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/training/sharing
- https://developer.android.com/privacy-and-security/risks/android-exported
- https://developer.android.com/guide/components/intents-filters
- https://developer.android.com/develop/ui/compose/accessibility/testing
- https://developer.android.com/training/testing/ui-tests/screenshot
- https://developer.android.com/media/optimize/performance/frame-rate
- https://developer.android.com/identity/data/autobackup
- https://developer.android.com/guide/topics/resources/pseudolocales
- https://developer.android.com/guide/topics/resources/app-languages
- https://www.w3.org/TR/WCAG22/
- https://www.rfc-editor.org/rfc/rfc9110.html
- https://support.google.com/googleplay/android-developer/answer/9888072

### Dependencies and security

- https://commons.apache.org/proper/commons-compress/security.html
- https://osv.dev/vulnerability/GHSA-4g9r-vxhx-9pgx
- https://developer.android.com/jetpack/androidx/releases/navigation
- https://developer.android.com/jetpack/androidx/releases/room
- https://developer.android.com/jetpack/androidx/releases/media3
- https://developer.android.com/jetpack/androidx/releases/datastore
- https://coil-kt.github.io/coil/changelog/
- https://raw.githubusercontent.com/square/okhttp/master/CHANGELOG.md
- https://github.com/TeamNewPipe/NewPipeExtractor/releases/tag/v0.26.4
- https://github.com/yt-dlp/yt-dlp/releases/tag/2026.07.04
- https://firebase.google.com/support/release-notes/android

### Research

- https://openaccess.thecvf.com/content_ICCV_2019/html/Howard_Searching_for_MobileNetV3_ICCV_2019_paper.html
- https://opg.optica.org/jdt/abstract.cfm?uri=jdt-12-5-483
- https://oss.cs.fau.de/wp-content/uploads/2025/03/vamos_2025.pdf

## Open Questions

- **[Needs live validation]** Should a whole-library backup embed managed local/AI
  assets in one larger archive, or export a JSON manifest plus explicit sidecar/skipped
  records? The current JSON cannot make `file://` locators portable.
- **[Needs live validation]** What owner-approved rights/policy evidence, if any,
  authorizes YouTube extraction in a Play-distributed build? Without it, the channel
  capability must default off even though GitHub/Obtainium builds can retain it.
