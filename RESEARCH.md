# Research — Aura

Date: 2026-08-11 — replaces all prior research (previous passes: 2026-08-10, 2026-07-29).
Confidence: unqualified project facts are **Verified** by direct inspection at v6.41.0
(versionCode 142, HEAD `122d431`, clean tree); external or unrun claims are labeled
**Likely**, **Assumption**, or **Needs live validation**.

## Executive Summary

Aura is a mature local-first Android personalization suite — wallpapers, live/video
wallpapers, and ringtone/notification/alarm sounds — at 417 Kotlin files, ~92.6k lines,
1,007 commits since 2026-02-21, 141 JVM test files, 82 Python release gates with 81 pytest
mirrors. The 2026-08-10 pass found the code healthy and the *publishing* layer broken; the
v6.41.0 cycle closed most of it. Confirmed fixed this pass: the community moderation
listener now gates on consent, the two backend advisories are patched, the NUL byte and
mixed line endings are repaired behind a `.gitattributes`, `docs/**` is tracked and the
in-app privacy policy returns HTTP 200, and the five live-wallpaper preference bridges write
SharedPreferences first.

**The publishing gap did not close — it moved one file to the left.** `v6.41.0` is tagged and
pushed, but the newest GitHub *Release* is still `v6.38.1` (2026-07-29), so three versions of
security fixes remain unreachable by the Obtainium users who are the entire distribution
channel. And the same `.gitignore` rule that hid `docs/` still hides `CONTRIBUTING.md` and
`ARCHITECTURE.md`: both return 404 on GitHub today, and the new `docs_link_check.py` gate
cannot see them because it only walks README plus `docs/`-prefixed links.

Beyond that, this pass moved from the release perimeter into the product. Three defects sit
directly on the paths the whole category is known to fail on, and Aura has the parts to fix
each: every wallpaper apply decodes to an in-process `Bitmap` (the documented cause of
"wallpaper silently reverted to default" elsewhere), shuffle never consults the history table
it already writes, and nothing anywhere asks the system whether Aura's live wallpaper is
still the active one.

Top opportunities in priority order:

1. Publish GitHub Releases for the tagged versions; the release gate must fail on a tag with
   no release, not just on a version with no tag.
2. Track `CONTRIBUTING.md` and `ARCHITECTURE.md`; widen the link gate past `docs/`.
3. Apply wallpapers through the streaming `WallpaperManager` path instead of a decoded bitmap.
4. Exclude recently-applied wallpapers from shuffle using the history table Aura already keeps.
5. Detect and self-heal when Aura's live wallpaper is no longer the active wallpaper.
6. Handle the Android 17 per-app memory limiter, which applies with no targetSdk gate.
7. Make the wallpaper grid's model stable to the Compose compiler and start measuring.
8. Survive an app downgrade and prove the full Room migration chain.
9. Retire the FFmpeg audio pipeline in favour of the platform media stack — it is the single
   largest lever on APK size, native-loader exposure, and the yt-dlp maintenance treadmill.
10. Restore validation-only CI so 82 gates and ~940 tests stop depending on human memory.

## Product Map

- **Core workflows.** Browse and search multi-source wallpapers, video wallpapers, and
  ringtone/notification/alarm sounds. Edit, crop, apply, download, favorite, collect,
  restore. Rotate wallpapers by schedule, screen event, weather, theme, Quick Settings tile,
  or external broadcast. Import local media, generate optional AI wallpapers, keep favorites
  offline, export and re-import the whole library.
- **Personas.** No-account privacy-conscious users; automation power users (Tasker/ADB
  broadcasts); local-media collectors needing durable organization and recovery; optional
  community contributors; FOSS users who expect unsupported services to be explicit.
- **Platforms and distribution.** Android 8.0+ (minSdk 26), compileSdk 35 / targetSdk 35,
  phone-first, single activity. Two Gradle modules (`:app`, `:baselineprofile`) and two
  flavors (`full`, Firebase-free `foss`). Built locally — `.github/` holds one issue template
  and no workflows. Distribution is GitHub Releases plus Obtainium; IzzyOnDroid is the stated
  near-term target and Aura is listed on neither it nor F-Droid.
- **Integrations and data flows.** Wallhaven, Bing, NASA APOD, Wikimedia POTD, Lemmy, Reddit
  Atom, Pexels, Pixabay, YouTube (NewPipe Extractor for search, yt-dlp for streams),
  Freesound/Audius/ccMixter/SoundCloud (legacy, orphaned), Open-Meteo, Stability AI (user
  key), Firebase Auth/RTDB/Storage plus seven callables, and ML Kit subject segmentation in
  the `full` flavor. The Room database sits at schema version 16 with six DAOs, DataStore
  holds settings, and six named SharedPreferences files back the synchronous reads the
  wallpaper engines make.
- **Undocumented in README.** The Content Sources table omits NASA APOD, Wikimedia POTD, and
  Lemmy, all of which have shipping clients under `data/remote/`.

## Competitive Landscape

Projects covered by the 2026-08-10 pass (WallYou, WallFlow, darkmodewallpaper, Muzei,
Paperize, Peristyle, doodle-android, ShaderEditor, workpaper-android, SlideshowWallpaper,
Mozart, ringdroid, losslesscut-android, Backdrops, Zedge) are not repeated. New entrants:

- **Peristyle** (679★) — carried forward only because issue #221 (31 comments) contains the
  single most valuable engineering artifact found this pass: `setBitmap` under memory
  pressure OOMs and the system silently reverts to the default wallpaper, and the maintainer
  fixed it by migrating to the stream-based `WallpaperManager` API. *Learn:* the diagnosis
  and the fix, verbatim. *Avoid:* nothing — take it.
- **UndeadWallpaper** (128★, v1.3.7, 2026-08-04, GPL-3.0) — gapless multi-video playlist on a
  custom OpenGL + ExoPlayer engine, per-clip zoom/offset/rotation/speed/volume, and a "smart
  start" that resumes, restarts, or picks a random frame on unlock. *Learn:* the playlist
  model and per-clip transforms; Aura's `VideoWallpaperService` plays exactly one video.
  *Avoid:* a fourth bespoke render engine — fold it into the existing service.
- **plasma-smart-video-wallpaper-reborn** (555★, 2026-08-01) — the best pause-condition
  matrix in any wallpaper project: pause on fullscreen, screen-off, lock, and a battery
  threshold that disables the blur budget as well as the video. *Learn:* treating pause
  conditions as a declared matrix rather than scattered `if` statements. *Avoid:* the
  desktop-only conditions (active window, per-monitor) that have no Android analogue.
- **UltimateRingtonePicker** (66★, v3.3.0, MIT) — enumerates system, external, and
  Music-store sounds with multi-select and favorites. *Learn:* Aura writes ringtones but
  never reads the device's existing ones, so it cannot show what is currently set or offer a
  way back. *Avoid:* adopting the library itself — it is a View-based dialog.
- **variety** (1,675★, 2026-08-05) and **Frames** (649★) — the two canonical source-plugin
  designs: a pluggable downloader registry with per-source quotas and a trash/favorite
  verdict loop, and a dashboard SDK that lets third parties ship a wallpaper app from a JSON
  catalog. *Learn:* Aura's hardcoded source list wants to become a registry — this is the
  concrete shape blocked item NX-5 should take. *Avoid:* Frames' fork-a-whole-app model.
- **cssnr/remote-wallpaper-android** (13★, 2026-08-11) — a single arbitrary remote URL polled
  on an interval. Trivial, and Aura has no equivalent: there is no WebDAV, SMB, or
  user-supplied-URL source anywhere in `data/remote/`. *Learn:* self-hosted sources fit
  local-first better than any additional third-party API. *Avoid:* nothing.
- **Vanderwaals** (32★, AGPL-3.0) — on-device MobileNetV4 embeddings ranking a personal feed
  with no server. *Learn:* the ceiling for Aura's existing on-device style learning.
  *Avoid:* shipping a model — the APK is already 198 MB.
- **Lively** (19.4k★, desktop) — issue #137 (25 comments) is the clearest statement of the
  category's #1 want: change the wallpaper based on *conditions*, not an interval. *Learn:*
  rules beat intervals, and a wallpaper should be able to declare its own settings schema.
  *Avoid:* wallpaper-as-arbitrary-web-page; it is remote code execution in a render process.

**Field observations.** (1) F-Droid's Theming/live-wallpaper shelf is roughly twenty apps
deep and contains no ringtone app besides Ringdroid; Aura is currently the highest-starred
FOSS Android ringtone project under GitHub `topic:ringtone`, and that topic is nearly empty.
(2) `offa/android-foss` lists exactly one wallpaper app and has no live-wallpaper or ringtone
section — an open PR slot. (3) Every commercial competitor paywalls rotation or scheduling in
some form (Backdrops, Abstruct SHIFT, Tapet advanced scheduling), which is why nobody has
made the free implementations reliable. Wallcraft's paid "Double" home-vs-lock wallpaper is
already free in Aura via `WallpaperApplier`'s `FLAG_SYSTEM`/`FLAG_LOCK` split.

## Security, Privacy, and Reliability

- **[Verified] Three released-in-changelog versions have no GitHub Release.** `git ls-remote
  --tags origin` shows `v6.41.0` at `122d431`; `gh release list` returns `v6.38.1` as latest
  (2026-07-29). CHANGELOG documents v6.39.0, v6.40.0, and v6.41.0. Obtainium resolves from
  Releases, and `obtainium.json` sets `fallbackToOlderReleases: true` and
  `verifyLatestTagAndReleaseAreSame: false`, so users silently stay on v6.38.1 — which
  predates the consent fix, the advisory patches, and the byte-hygiene repair. The tracked
  P0 item covers the tag half; the publish half is still open.
- **[Verified] Two contributor-facing documents 404.** `git check-ignore -v` shows
  `.gitignore:36 *.md` matching both `CONTRIBUTING.md` and `ARCHITECTURE.md`; neither is
  tracked; both return HTTP 404 at `github.com/SysAdminDoc/Aura/blob/main/`. So does
  `docs/plugins/`, which `CONTRIBUTING.md` links. GitHub therefore shows no contributing
  guidelines on new issues and PRs. `tools/docs_link_check.py` cannot catch this: its
  `SOURCE_ROOTS` are `README.md`, `app/src/main/java`, and `app/src/main/res/values`, and
  `DOC_LINK_PATTERN` only matches `docs/`-prefixed targets.
- **[Verified] `CONTRIBUTING.md` describes a roadmap that no longer exists.** It instructs
  contributors to "open issues against existing items by their ID", to add "sources in the
  Appendix", and to read the "How to read this document" section for
  Now/Next/Later/Under-Consideration/Rejected tier thresholds. `ROADMAP.md` has no IDs, no
  Appendix, no such section, and uses P0–P3.
- **[Verified] Every wallpaper apply goes through `setBitmap`.** `WallpaperApplier.kt:83` and
  `:102` are the only apply calls in the codebase; `setStream` appears nowhere. Each path
  decodes the source into an in-process `Bitmap` first — a 64 MB `readCapped` payload decodes
  to far more in ARGB_8888. Peristyle #221 documents this exact shape producing an OOM after
  which the system reverts to the default wallpaper, with the stream API as the fix. Aura
  already has an `OutOfMemoryError` catch in the editor as independent evidence that decode
  pressure is real here. `setStream` cannot take a crop rect, so the bitmap path must remain
  for edited and cropped images — but the rotation path, which is the unattended one, does
  not need it.
- **[Verified] Shuffle can repeat immediately.** `AutoWallpaperWorker` injects
  `WallpaperHistoryManager` and calls `record()` after applying (`:210`), but
  `pickScheduledWallpaper(wallpapers, shuffle)` never reads it, and
  `WallpaperHistoryManager` exposes `getRecent`, `mostRecent`, and `secondMostRecent` that
  nothing in the rotation path consumes. Peristyle #115 ("avoid duplicate wallpaper in random
  mode", 53 comments) is the most-commented issue found in the entire category survey.
- **[Verified] Aura never asks whether its live wallpaper is still active.**
  `WallpaperManager.getWallpaperInfo()` and the `WallpaperInfo` type appear nowhere in
  `app/src/main/java`. Aura ships three `WallpaperService` implementations and cannot
  distinguish "running" from "replaced by another app or dropped by the system". Muzei #874
  (16 comments, 2026-07-01) reports precisely this on Android 17 / Pixel 10 after reboot.
- **[Verified] Android 17 applies a per-app memory limiter to all apps.** Confirmed against
  developer.android.com/about/versions/17/behavior-changes-all: the limit is derived from
  device RAM and applies **regardless of targetSdkVersion**, with detection via
  `ApplicationExitInfo.getDescription()` containing `MemoryLimiter:AnonSwap`. Aura's exposure
  is concentrated and already documented: the editor's `MAX_EDIT_LONG_EDGE = 4096` renders,
  the orphaned-bitmap defect already tracked in ROADMAP, the 64 MB apply ceiling, and three
  long-lived wallpaper engines holding bitmap layers. `CrashDiagnosticsCollector.kt` is the
  natural place to surface it and does not read `ApplicationExitInfo` today.
- **[Verified] A downgrade bricks the app.** `AppModule.kt:241-250` builds the Room database
  with `addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)` and a foreign-keys callback, and
  nothing else — no `fallbackToDestructiveMigrationOnDowngrade`. Installing an older Aura APK
  (an ordinary Obtainium and `adb install -r` action, and README documents the ADB path)
  leaves Room unable to open the file, so the app crashes on every launch and the user's only
  recovery is clearing app data, which destroys favorites, collections, and history.
- **[Verified] Migration coverage is two hops out of fifteen.** `DatabaseMigrations.kt`
  declares `MIGRATION_1_2` through `MIGRATION_15_16`. `DatabaseMigrationTest.kt` exercises
  only `migrate8To9` and `migrate14To16`. Exported schemas start at 9 by deliberate policy
  (`room_schema_history_check.py --supported-export-start 9`, and a hand-written
  `VERSION_8_SCHEMA` covers the 8→9 hop), so the floor is intentional — but migrations 1→8
  and 9→14 have no test at all, and no test runs the full chain end to end.
- **[Verified] Failures that reach the user as nothing at all.**
  `VoteRepository.kt:407` implements `onCancelled(error: DatabaseError) {}` with no log, so a
  permission-denied or disconnect on the vote listener is invisible while the UI shows stale
  votes. Seven `startActivity` calls are wrapped in empty catches —
  `FreeVibeWidget.kt:352,381,407` (the widget has no other feedback channel),
  `ContactPickerScreen.kt:448`, `SoundDetailScreen.kt:564,582`,
  `WallpaperDetailScreen.kt:620-630` — so the user taps and nothing happens.
  `VideoWallpaperService.kt:126-134` and `:248-256` swallow display-metrics and
  `MediaMetadataRetriever` failures, letting stale or zero dimensions through into the
  scaling math with no diagnostic. Broader context: 62 of 358 catch blocks in
  `app/src/main` are empty, but 42 of those are `recycle`/`release`/`close` teardown where
  swallowing is correct, and a shared `rethrowIfCancelled()` helper is used 113 times across
  27 files, so cancellation is handled properly. These are the genuine residue.
- **[Verified] Native libraries ship compressed.** `app/build.gradle.kts:165-168` sets
  `useLegacyPackaging = true` with no explanatory comment. It is a real requirement of
  youtubedl-android, whose README asks for `android:extractNativeLibs="true"` — but it means
  the `.so` payloads are compressed in the APK and extracted at install, roughly doubling
  on-device native storage on top of a 198 MB artifact, and it is contrary to the
  uncompressed-and-aligned packaging Google's 16 KB page-size guidance asks for.
  `tools/native_alignment_check.py` validates ELF `PT_LOAD` alignment and passes; it never
  inspects the zip storage method, so nothing in the repo records this trade.
- **[Verified] The write-order gate enumerates what it guards.**
  `tools/preference_write_order_check.py` holds a hand-written nine-name `BRIDGE_FUNCTIONS`
  tuple and additionally asserts only that `SettingsViewModel.kt` contains no
  `getSharedPreferences`. A tenth bridge added tomorrow is unpoliced by construction. Scale
  of the surface it does not see: 55 `getSharedPreferences` call sites across 30 files and
  six distinct preference files. The underlying split-brain has been fixed at least four
  times across releases (`e2c0252` → `e6b117b` → `79b6177` → `63ddc94`), which is what a
  gate scoped by a list rather than by a rule produces.
- **[Verified] No CI runs anything.** `.github/` contains one issue template. All five
  workflows were deleted in `ec73ea7` (2026-06-26). The 82 gates, their 81 pytest mirrors,
  ~940 JVM tests, three instrumented tests, and the Roborazzi suite all execute only when a
  human remembers, and four security gates report `"status": "ok", "workflowCount": 0`.
  Validation-only CI is explicitly permitted by the operator's own standing rules; releasing
  binaries from CI is not.
- **[Verified] Parallax and smart crop rest on an abandoned beta.**
  `app/build.gradle.kts:349` pins `com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1`,
  published 2023-11-06 and never promoted. `SmartCropDetector` and `ParallaxWallpaperService`
  both depend on it, and the `foss` flavor stubs it out entirely — so two advertised features
  are absent from the artifact IzzyOnDroid would ship, which the README feature table does
  not say.
- **[Verified] No secrets are committed.** Keys are read from a gitignored `local.properties`
  into `BuildConfig` and default to `""`. `google-services.json` is committed, which is
  normal — it makes App Check and the RTDB/Storage rules the only real access control, and
  both exist.
- **[Verified] No lint baseline exists**, and the only global suppression
  (`NullSafeMutableLiveData`) is documented with a rationale. No `@Ignore`, `@Disabled`, or
  skipped tests anywhere across Kotlin, Node, and Python suites. No `runBlocking` or
  `GlobalScope` in real source. This is a genuinely disciplined codebase; the findings above
  are the exceptions, not the texture.

## Architecture Assessment

- **Module boundary — none exists.** `settings.gradle.kts` declares `:app` and
  `:baselineprofile`. All 256 main-source Kotlin files live in one module, 86 of them in a
  single `service/` package that mixes live-wallpaper engines, WorkManager jobs, exporters,
  codecs, diagnostics, and the yt-dlp manager. `app/build.gradle.kts` has been touched by 237
  commits. There are no enforced boundaries between UI, data, and service layers, no
  incremental-build isolation, and `gradle.properties` is four lines with no build cache, no
  parallel execution, and no configuration cache (already tracked). A `:core:data` /
  `:core:service` extraction is the structural precondition for both build speed and the
  ViewModel-size gates that currently exist as file-length assertions.
- **Compose stability is unmeasured and one model is provably unstable.**
  `data/model/Models.kt:33` declares `Wallpaper` with two `List<String>` fields
  (`tags`, `colors`) and no `@Immutable`, while `Sound` immediately below at `:58` *is*
  `@Immutable`. `Wallpaper` is the model rendered in every grid cell on the busiest screens
  in the app, and the Compose compiler infers it unstable, so those items recompose whenever
  a parent does. Only 10 `@Immutable`/`@Stable` annotations exist across the whole codebase,
  and no `composeCompiler { }` block configures metrics, reports, or a stability
  configuration file — so nobody can see the cost. Everything else here is healthy: 199
  `collectAsStateWithLifecycle` against 4 plain `collectAsState` (all four are Coil
  `painter.state`, which is correct).
- **The design system is documented but not codified.** `ui/theme/` contains exactly one
  file. Color tokens exist in `Theme.kt`; there is no shape or spacing token source. Corner
  radii are literal numbers at 250+ call sites — 225 uses of `RoundedCornerShape(8)` plus
  strays at 1, 2, 4, 5, 6, 10, 12, 24, and 50 dp. `VideoWallpapersScreen.kt:884` uses
  `RoundedCornerShape(50)`, a full pill, against an explicit "no pill / oval /
  fully-rounded backdrops" rule recorded in ARCHITECTURE.md and CLAUDE.md; and
  `WallpapersScreen.kt:1268` uses 24 dp against a documented 4–12 dp system. 102 hardcoded
  `Color(0x…)` literals sit in UI files. `test/tools/` has 81 gates and not one of them
  covers the design system, which is the only major documented rule with no gate behind it.
- **Refactor candidates.** Three screen files exceed 1,500 lines and are ungated:
  `WallpapersScreen.kt` (1,848), `SoundsScreen.kt` (1,844), `VideoWallpapersScreen.kt`
  (1,605). `FreeVibeRoot.kt` is 1,216 lines of NavHost. On the ViewModel side the 500-line
  contract gate covers only Wallpapers and Sounds; `VideoWallpapersViewModel.kt` (1,319, the
  split is blocked for lack of behavioral coverage), `SettingsViewModel.kt` (996, tracked),
  `WallpaperEditorViewModel.kt` (945), and `SoundEditorViewModel.kt` are all past it.
  `PreferencesManager.kt` is a 44 KB preference god-object every feature reaches into.
- **Test boundary.** 141 JVM test files is strong, but `androidTest` holds three files
  (`DatabaseMigrationTest`, `LiveWallpaperSoakInstrumentedTest`,
  `AccessibilityReleaseGateTest`), there are **zero** `testTag` modifiers anywhere in main
  source, and no CI executes any of it. Compose UI testing has no anchors to attach to, which
  is a precondition the already-tracked "test production composables" item will hit
  immediately.
- **Accessibility.** The interactive-element audit from the prior pass still holds and is not
  re-raised. What remains unaddressed is announcement and traversal, not labeling: three
  `liveRegion` usages in an app whose primary surfaces are async grids, downloads, and
  playback; seven `heading` semantics; zero `traversalIndex` and zero `isTraversalGroup`.
- **Localization.** 1,690 strings are extracted and `isPseudoLocalesEnabled = true` on debug
  is real (verified — README's claim is accurate). But `res/` contains no `values-<locale>/`
  directory at all, the manifest declares no `android:localeConfig`, so the Android 13+
  per-app language picker cannot appear, and there is no Weblate, Crowdin, or any documented
  path for a translator to contribute. `MediaIngestion.kt:488-494` builds user-facing text
  with hardcoded English conjunctions (`" or "`, `", or "`) — a concrete instance for the
  already-tracked residual-localization item.
- **Diagnostics.** `CrashDiagnosticsCollector`, `SourceMetrics`, `BackgroundWorkReceiptStore`,
  `LiveWallpaperReceiptStore`, and a `BackgroundWorkDiagnosticsReader` give this app better
  self-observability than anything else in the category, and there is deliberately no
  analytics SDK. Missing on the development side only: no `StrictMode` policy (which would
  have caught the main-thread preference reads that keep recurring) and no LeakCanary (which
  would have caught the editor bitmap orphaning before a user did).
- **Roadmap integrity.** `ROADMAP.md:53` gates the Microsoft Spotlight item on "the P0
  capability registry", which is not an item in `ROADMAP.md` or `Roadmap_Blocked.md`.
  `roadmap_hygiene_check.py` only forbids `- [x]` lines, and
  `manifest_consistency_check.py`'s duplicate-title detector matches a bold `**Title**` form
  this file does not use, so neither gate can see a dangling dependency.
- **Category coverage.** New work below covers reliability, data safety, security hardening,
  observability, testing, docs, distribution, i18n, accessibility, design system, dev
  experience, and dependency strategy. Deliberately not re-raised: *offline resilience* —
  offline favorites render from the managed local file and the wallpaper cache is a real Room
  table; *multi-user* — rejected in prior passes and unchanged; *mobile/desktop ports* —
  Android is the product; *plugin ABI* — blocked as NX-5, though the competitive section
  above now gives it a concrete shape; *secrets handling* — audited clean.

## Rejected Ideas

- **Raise targetSdk to 36 before 2026-08-31.** Google Play requires API 36 for new apps and
  updates from that date, but Aura does not distribute on Play — GitHub Releases, Obtainium,
  and IzzyOnDroid are unaffected. The prior pass's rejection stands. What *is* newly true is
  that the repo's Play-lane compliance artifacts (`play_app_content_packet_check.py`, the
  `bundleFullRelease` dry run, `store_metadata_preflight.py`) describe a lane that becomes
  unpublishable on that date; that is a docs-honesty question, not an engineering one.
- **Compose UI 1.12 / Material 3 Expressive.** Requires compileSdk 37 and AGP 9.2 — two full
  toolchain generations past the already-tracked compileSdk 36 step, and gated behind the N-1
  blocker rather than alongside it.
- **Kotlin Multiplatform / iOS / desktop (Splashy).** Contradicts the phone-first charter and
  would fork every one of the 82 Android-specific release gates.
- **Android TV / leanback surface (Projectivy plugin).** No TV code, no TV layouts, no TV
  distribution channel; a second product wearing the same name.
- **WebView-backed wallpapers (Plash, Lively).** Rendering arbitrary remote HTML/JS on a
  `WallpaperService` surface is remote code execution in a long-lived process — the same
  reason prior passes rejected Godot bundle import and unrestricted plugins.
- **Ship an on-device ranking model (Vanderwaals MobileNetV4).** Aura already has on-device
  style learning from apply/favorite/hide signals; adding a model to a 198 MB APK inverts the
  size priority this roadmap is trying to fix.
- **Wallcraft-style "Double" home-vs-lock wallpaper.** Already shipped —
  `WallpaperApplier.kt:79-81,98-100` handles `FLAG_SYSTEM`, `FLAG_LOCK`, and both.
- **AGSL `RuntimeColorFilter` / `RuntimeXfermode` (API 36).** Genuine new capability for
  `AgslShaderGallery` and the weather tint path, but it is an aesthetic increment gated on
  the same compileSdk bump as items with real user impact; revisit after that lands.
- **Gyroscope or rotation-vector parallax.** `ParallaxWallpaperService` uses
  `TYPE_ACCELEROMETER`; a fused sensor would be smoother, but the engine's known problems are
  bitmap lifetime and battery, not tilt fidelity.
- **User-authored shaders, Godot/Workshop import, icon packs, watch faces, SLSA provenance,
  per-SIM ringtones, "Ringtone V2", mandatory cloud sync, ads/coins/accounts,
  `MANAGE_EXTERNAL_STORAGE`, sub-15-minute rotation, Isolated Projects** — unchanged from the
  2026-07-29 and 2026-08-10 passes; re-validated, still rejected.

## Sources

### Competing and adjacent projects
- https://github.com/Hamza417/Peristyle/issues/221
- https://github.com/Hamza417/Peristyle/issues/115
- https://github.com/Hamza417/Peristyle/issues/241
- https://github.com/maocide/UndeadWallpaper
- https://github.com/luisbocanegra/plasma-smart-video-wallpaper-reborn
- https://github.com/DeweyReed/UltimateRingtonePicker
- https://github.com/varietywalls/variety
- https://github.com/jahirfiquitiva/Frames
- https://github.com/cssnr/remote-wallpaper-android
- https://github.com/avinaxhroy/Vanderwaals
- https://github.com/rocksdanister/lively/issues/137
- https://github.com/redwarp/gif-wallpaper
- https://github.com/enricocid/VectorifyDaHome
- https://github.com/BlackyHawky/Clock

### Community signal
- https://github.com/muzei/muzei/issues/874
- https://github.com/muzei/muzei/issues/367
- https://github.com/ammargitham/WallFlow/issues/85
- https://github.com/ammargitham/WallFlow/issues/113
- https://github.com/Anthonyy232/Paperize/issues/207
- https://github.com/Anthonyy232/Paperize/issues/345
- https://github.com/Anthonyy232/Paperize/issues/190
- https://github.com/Anthonyy232/Paperize/issues/310
- https://github.com/you-apps/WallYou/issues/266
- https://github.com/google/ringdroid/issues/16
- https://forum.f-droid.org/t/ringtone-maker-app/22600
- https://news.ycombinator.com/item?id=46115862
- https://news.ycombinator.com/item?id=41641704

### Platform and standards
- https://developer.android.com/about/versions/17/behavior-changes-all
- https://developer.android.com/about/versions/16/features
- https://developer.android.com/reference/android/app/wallpaper/WallpaperDescription
- https://developer.android.com/reference/android/app/WallpaperManager
- https://developer.android.com/develop/ui/views/haptics/custom-haptic-effects
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/topic/performance/compose-performance
- https://developer.android.com/training/data-storage/room/migrating-db-versions
- https://developer.android.com/guide/topics/resources/app-languages

### Dependencies and distribution
- https://developer.android.com/jetpack/androidx/releases/media3
- https://developer.android.com/jetpack/androidx/releases/glance
- https://github.com/coil-kt/coil/blob/main/CHANGELOG.md
- https://github.com/yausername/youtubedl-android
- https://github.com/TeamNewPipe/NewPipeExtractor/releases
- https://f-droid.org/2026/02/24/open-letter-opposing-developer-verification.html
- https://izzyondroid.org/docs/general/AppInclusionPolicy/
- https://github.com/offa/android-foss
- https://weblate.org/en/hosting/
- https://github.com/advisories/GHSA-xq3m-2v4x-88gg
- https://api.osv.dev/v1/query

## Open Questions

- **[Blocks release]** Is the release step being skipped deliberately, or did it simply never
  get re-run after the workflows were deleted? Three tagged-or-changelogged versions with no
  Release, and 97 versionCode bumps against 45 tags, point at a missing habit rather than a
  decision — but the fix differs: cut the releases, versus add a gate that fails when a tag
  has no published Release.
- **[Blocks store submission]** F-Droid published an open letter on 2026-02-24 calling
  Android's developer-verification program an existential threat to alternative stores, and
  IzzyOnDroid has the same structural exposure. Does Aura still target IzzyOnDroid, and does
  that change the enrol-versus-abstain decision already recorded in
  `docs/distribution/developer-verification.md`? Every distribution item depends on the
  answer, and nothing in the codebase can resolve it.
- **[Needs owner decision]** The `foss` flavor stubs ML Kit, so parallax wallpapers and smart
  crop are absent from the artifact IzzyOnDroid would ship, and Stability AI is still
  compiled in (already tracked). Should the FOSS build be presented as a reduced edition with
  its own documented feature table, or should feature parity be pursued with a non-GMS
  segmentation path?
- **[Needs live validation]** Does an Aura downgrade actually produce the Room crash loop
  predicted above, and does the Android 17 memory limiter fire on the editor's 4096 px render
  path on a real device? Both are code-level certainties about the *absence* of a guard; the
  user-visible severity needs a device to confirm.
</content>
</invoke>
