# Aura Roadmap

Actionable work only. Historical and completed roadmap material is archived in CHANGELOG.md; blocked work is kept in Roadmap_Blocked.md.

## Research-Driven Additions

### P1

- [ ] P1 — Prove and fix NewPipe search on Aura's legacy Android releases
  Why: issue #2 contains a post-fix Android 10 crash from the exact API overload that desugaring was meant to backport, so the closed label is not proof that Sounds works across Aura's supported range.
  Evidence: GitHub issue #2; `app/build.gradle.kts` core-library desugaring block; `app/src/main/java/com/chloemlla/aura/data/repository/YouTubeRepository.kt`; Android core-library desugaring and Gradle managed-device documentation.
  Touches: `app/build.gradle.kts`, `YouTubeRepository.kt`, API 26/27/29 instrumentation, release shrinker rules.
  Acceptance: full and FOSS release-minified APKs install on a headless API 26 emulator plus local API 27 and API 29 devices, open Sounds, and execute the NewPipe search entry point without `NoSuchMethodError`; the instrumented regression fails on that linkage error even when the network result is unavailable; issue #2 receives the exact build and device result before it is treated as resolved.
  Complexity: M

- [ ] P1 — Inspect nested native archives in the 16 KB release gate
  Why: the release gate reports Python and FFmpeg `.zip.so` payloads as skipped, so it cannot prove every shipped 64-bit ELF meets Android's page-size requirement.
  Evidence: `tools/native_alignment_check.py` `inspect_apk()`; `docs/distribution/native-alignment.json`; Android 16 KB page-size documentation; youtubedl-android issue #334.
  Touches: `tools/native_alignment_check.py`, `test/tools/native_alignment_check_test.py`, `docs/distribution/native-alignment.json`, release APK validation.
  Acceptance: the gate recursively inspects every ELF inside nested archives for arm64-v8a and x86_64, reports zero skipped executable payloads, fails on a 4 KB-aligned 64-bit fixture, passes a 16 KB fixture, and records the archive entry plus ABI in its output; any failing shipped payload is replaced or rebuilt before release.
  Complexity: M

### P2

- [ ] P2 — Add the fastlane store images IzzyOnDroid requires
  Why: `fastlane/metadata/android/en-US/` has no `images/` directory, so there is no icon, phone screenshot, or feature graphic for a store listing to consume. (Changelogs are current — an earlier claim that they stopped at versionCode 8 was a lexical-sort artifact; 22 exist, through 141.)
  Evidence: `ls fastlane/metadata/android/en-US/` returns only `changelogs/`, `full_description.txt`, `short_description.txt`, `title.txt`; IzzyOnDroid App Inclusion Policy requires in-repo Fastlane metadata with icon and screenshots. Screenshot capture itself stays blocked in `Roadmap_Blocked.md`.
  Touches: `fastlane/metadata/android/en-US/images/**`, `tools/store_metadata_preflight.py`.
  Acceptance: `images/icon.png` and at least four `images/phoneScreenshots/` entries exist at the required dimensions, and the preflight fails when the icon or screenshot set is absent.
  Complexity: S

- [ ] P2 — Fix the remaining service and editor reliability defects
  Why: a cluster of small independent defects that each fail silently in the exact paths users hit after process death or on decode failure.
  Evidence: `RotationTriggerService.kt:61-72` (`runBlocking` DataStore read on the main thread in the `intent == null` START_STICKY restart) and `:85-89` (`getLaunchIntentForPackage` may return null before `startForeground`); `SoundEditorViewModel.kt:651-655` (fabricates a sine waveform on decode failure with no signal to the UI) and `:592-603` (`copyUriToCache` writes non-atomically, unlike `downloadToCache` at `:542-584`); `VideoWallpapersViewModel.kt:445-446,1264-1283` (`freevibe_pixabay_video_cache` grows without bound) and `:818-821,1077` (main-thread encode of up to 120 items per load); `VoteRepository.kt:208,342` (a Firebase error reported as a real count of zero, and one that is discarded entirely).
  Touches: `RotationTriggerService.kt`, `SoundEditorViewModel.kt`, `VideoWallpapersViewModel.kt`, `VoteRepository.kt`, tests.
  Acceptance: the service restart path reads preferences off the main thread and survives a null launch intent; waveform extraction failure is surfaced rather than faked; `copyUriToCache` is temp-then-rename and reports limit failures; the video cache is bounded and its encode runs off the main thread; Firebase read errors are distinguishable from zero.
  Complexity: M

- [ ] P2 — Reconcile BatchDownloadService with its documented design
  Why: it is documented as a foreground service in ARCHITECTURE.md but is a plain `@Singleton` with an ad-hoc scope, so a long batch is killed when the process is backgrounded and `isRunning` is left true.
  Evidence: `BatchDownloadService.kt:42-43` is `@Singleton class`, not a `Service`; `:55,65,115,130` carry the `isRunning` flag; `ARCHITECTURE.md:50` lists it among services; `docs/distribution/foreground-service-declaration.json` does not declare it.
  Touches: `BatchDownloadService.kt`, manifest FGS declaration or a WorkManager migration, `docs/distribution/foreground-service-declaration.json`, ARCHITECTURE.md.
  Acceptance: batch downloads either run as a declared foreground service or as WorkManager work that survives backgrounding, progress is recoverable after process death, and the docs match the implementation.
  Complexity: M

- [ ] P2 — Gate contributor docs against build and schema truth
  Why: `CONTRIBUTING.md:20` asks for JDK 17 and SDK 35 while `:37` claims Gradle 8.12, AGP 8.9.3, Kotlin 2.1.0, and "compiles against SDK 36 but still targets 35" — this fork builds with Gradle 9.5.0, AGP 9.3.1, Kotlin 2.3.21, a JDK 21 CI runner (bytecode target 17), and compileSdk/targetSdk 37. ARCHITECTURE.md still names Favorites where navigation ships Library.
  Evidence: `CONTRIBUTING.md:20,37` versus `gradle/wrapper/gradle-wrapper.properties:3`, `gradle/libs.versions.toml`, `app/build.gradle.kts:93,106-112,175-176`, and `.github/workflows/aura-android.yml:54`; `ARCHITECTURE.md:11,25`; `app/src/main/java/com/chloemlla/aura/data/local/Database.kt:43` declares schema version 17; `tools/manifest_consistency_check.py` reads only the sections listed in `CURRENT_STATE_HEADERS_*`, so none of this is checked today.
  Touches: `README.md`, `CONTRIBUTING.md`, `ARCHITECTURE.md`, `tools/manifest_consistency_check.py`, `test/tools/manifest_consistency_check_test.py`.
  Acceptance: the three documents state the current navigation names, Room schema version, min/compile/target SDK, and Java/JDK requirements; the consistency gate derives those values from source/build files and fails a fixture containing each stale value.
  Complexity: S

- [ ] P2 — Retire the yt-dlp extractor runtime so the bundled native payload can go
  Why: `youtubedl-android:ffmpeg` is already gone from this fork — `FfmpegDownloader` fetches a hash-pinned ffmpeg-kit binary on demand instead — but `youtubedl-android:library` still bundles the Python/yt-dlp payload in every ABI split, still forces `useLegacyPackaging = true`, and still keeps arm64 above IzzyOnDroid's 30 MB per-APK ceiling.
  Evidence: `app/build.gradle.kts:409` declares only `youtubedl-android:library` and the `jniLibs` block at `:218-223` names the extractor runtime as the reason legacy packaging cannot be disabled; `docs/distribution/native-alignment.json` still lists `libpython.zip.so` (and stale `libffmpeg.zip.so` entries) as skipped archive payloads with `arm64UnderIzzyOnDroidCeiling: false`; `FfmpegDownloader.kt`; `AudioTrimmer.kt`; `VideoCropScreen.kt`; Android Media3 Transformer documentation; youtubedl-android issue #248.
  Touches: `app/build.gradle.kts`, `YouTubeRepository.kt`, `YouTubeYtDlpRequestFactory.kt`, `AudioTrimmer.kt`, `VideoCropScreen.kt`, Reddit/video acquisition paths, `docs/distribution/native-alignment.json`, codec and release-size fixtures.
  Acceptance: YouTube and Reddit acquisition no longer require the bundled extractor runtime, or that runtime is also fetched on demand under the same hash-pinned, consent-gated path; every advertised sound export and verified lossless-cut case still passes through Media3 or the on-demand codec path; no release APK contains `libpython.zip.so` or `libffmpeg.zip.so`, `useLegacyPackaging` can be turned off, full and FOSS arm64 APKs are below 30 MiB, and the native-alignment policy is regenerated so its skipped-payload list and ceiling verdict match the shipped artifact.
  Complexity: L

- [ ] P2 — Codify the design system as tokens and gate it
  Why: the "rectangular 4–12 dp radii, no pill / oval / fully-rounded backdrops" rule is written in ARCHITECTURE.md and CLAUDE.md and enforced by nothing — corner radii are literal numbers at 250+ call sites, and the rule is already broken in shipped code. It is the only major documented project rule with no gate behind it, in a repo with 85 gates.
  Evidence: `VideoWallpapersScreen.kt:884` uses `RoundedCornerShape(50)`, a full pill; `WallpapersScreen.kt:1271` uses 24 dp; 214 uses of `RoundedCornerShape(8)` plus strays at 1, 2, 4, 5, 6, 10, 12; `ui/theme/` contains only `Theme.kt` with colour tokens and no shape or spacing source; 111 hardcoded `Color(0x…)` literals across eight UI files; `test/tools/` has no design gate.
  Touches: `ui/theme/` (new shape and spacing token files), the eight UI files with colour literals, the two shape violations, a new `tools/design_token_check.py` and its test.
  Acceptance: shape and spacing tokens live in `ui/theme/` and the two violations are corrected or explicitly waived with a recorded reason; a gate rejects literal `RoundedCornerShape(n)` outside the token file and any radius above the documented ceiling; colour literals outside `Theme.kt` and the source-tone tables are rejected or registered; the gate fails when a pill radius is reintroduced.
  Complexity: M

- [ ] P2 — Surface the failures that currently reach the user as nothing
  Why: a cluster of independent silent failures on paths where the user has just tapped something and nothing else can tell them it did not work.
  Evidence: `VoteRepository.kt:407` — `onCancelled(error: DatabaseError) {}`, so a permission-denied or disconnect leaves stale votes with no log; seven `startActivity` calls in empty catches at `AuraWidget.kt:352,381,407` (the widget has no other feedback channel), `ContactPickerScreen.kt:448`, `SoundDetailScreen.kt:564,582`, `WallpaperDetailScreen.kt:620-630`; `VideoWallpaperService.kt:126-134` and `:248-256` swallow display-metrics and `MediaMetadataRetriever` failures so stale or zero dimensions enter the scaling math. Distinct from the tracked "remaining service and editor reliability defects" item, which covers `RotationTriggerService`, `SoundEditorViewModel`, `VideoWallpapersViewModel`, and `VoteRepository.kt:208,342`.
  Touches: `VoteRepository.kt`, `AuraWidget.kt`, `ContactPickerScreen.kt`, `SoundDetailScreen.kt`, `WallpaperDetailScreen.kt`, `VideoWallpaperService.kt`, string resources, tests.
  Acceptance: `onCancelled` logs and marks the vote state degraded; every `startActivity` failure produces user-visible feedback appropriate to its surface, and the widget path uses a widget-visible state rather than a Toast; the two `VideoWallpaperService` swallows log and fall back to a defined value instead of a stale one; tests cover an `ActivityNotFoundException` on each surface.
  Complexity: S

- [ ] P2 — Browse the device's own sounds and offer a way back to the stock ringtone
  Why: Aura writes ringtones but never reads them — it cannot show what is currently set, cannot let the user pick from sounds already on the device, and captures only its *own* last-applied URI, so there is no path back to the OEM default from inside the app. Applying a ringtone is effectively irreversible, and the category's only maintained editor was abandoned for six years, so this shelf is uncontested.
  Evidence: `RingtoneManager.TYPE_*` appears only in `SoundApplier.kt:65-67`, `RingtoneShuffleWorker.kt:65,87`, and `RingtoneRestorationReceiver.kt:53-55` — all write or restore-Aura's-own-value paths; no `RingtoneManager.getCursor()` anywhere; no revert string in `strings.xml`; UltimateRingtonePicker; ringdroid #16, open since 2015-12-10.
  Touches: `SoundApplier.kt`, `SoundsScreen.kt` or a new device-sounds surface, `PreferencesManager.kt` (capture the pre-Aura URI on first apply), `RingtoneRestorationReceiver.kt`, string resources, tests.
  Acceptance: a device-sounds view lists and previews system and user sounds per type and marks the one currently set; the pre-Aura URI for each of the three types is captured before the first overwrite and never overwritten again; a "restore original" action returns each type to that URI and reports honestly when the original is gone; tests cover first apply, repeat apply, and a missing original.
  Complexity: M

- [ ] P2 — Ship an opt-in in-app update check
  Why: the entire distribution channel is sideload. Users who do not run Obtainium have no way to learn a new version exists, and the current gap — three versions published in the changelog and none reachable — is exactly the case where they would want to know. One HTTPS request to the releases endpoint with no identifiers is compatible with the no-tracking charter as long as it is off by default.
  Evidence: no update check anywhere in `app/src/main/java` (the only `releases/latest` references are static links in `LicensesScreen.kt:70,76,82`); `obtainium.json`; README's install section documents manual SHA-256 verification and `adb install -r`.
  Touches: a new update-check service, `SettingsPermissionsAboutSection.kt`, `PreferencesManager.kt`, `ProviderNetworkPolicy.kt` / `network_endpoint_inventory_check.py`, `docs/privacy/data-safety.md`, string resources, tests.
  Acceptance: an opt-in check compares the installed versionCode against the newest published Release, links to it, and shows the release notes; it is off by default, respects data-saver and metered-network posture, never auto-downloads or auto-installs, and is declared in the endpoint inventory and the data-safety doc; a test covers no-release, same-version, newer-version, and network-failure.
  Complexity: S

- [ ] P2 — Add StrictMode and LeakCanary to debug builds
  Why: the two recurring defect classes in this repo's history are main-thread preference and disk reads, and orphaned bitmaps and media players — precisely the two things these tools catch automatically, and neither is present. The `runBlocking` DataStore read on the main thread and the editor bitmap orphaning both reached shipped code and were found by reading, not by tooling.
  Evidence: no `StrictMode` and no `leakcanary` anywhere in `app/src/main/java`, `app/build.gradle.kts`, or `gradle/libs.versions.toml`; the tracked editor-bitmap and `RotationTriggerService.kt:61-72` items; `SoundEditorViewModel.kt:520-532` nests six empty catches around MediaPlayer teardown.
  Touches: `AuraApp.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`.
  Acceptance: debug builds install a thread policy (disk and network reads/writes) and a VM policy (leaked closables, activity leaks) that log rather than crash, and LeakCanary is a `debugImplementation` only; release and FOSS release artifacts contain neither, asserted by the APK scan; the existing known violations are enumerated in `CLAUDE.md` so new ones are distinguishable.
  Complexity: S

- [ ] P2 — Play more than one clip in the video live wallpaper
  Why: `VideoWallpaperService` plays exactly one video. A playlist with per-clip framing is the top-requested capability in the video-wallpaper category, and the whole rotation machinery Aura already owns — scheduler, day/night, collections — has no video equivalent.
  Evidence: no playlist or queue concept in `VideoWallpaperService.kt`; UndeadWallpaper v1.3.7 (per-clip zoom/offset/rotation/speed, shuffle, smart start); Lively #137 (25 comments) on condition-driven change. Depends on the tracked video-cache-bounding and main-thread-encode fixes; do those first.
  Touches: `VideoWallpaperService.kt`, `VideoWallpaperStorage.kt`, `PreferencesManager.kt`, video settings UI, the soak harness, string resources.
  Acceptance: an ordered or shuffled clip list advances at a configured boundary with no black frame at the seam; per-clip fit/crop and mute are preserved; the existing FPS cap, low-battery cap, and `onVisibilityChanged` pause govern the whole playlist, not just the first clip; total decoded storage stays bounded; the soak harness runs the playlist path and asserts nothing survives `onDestroy`.
  Complexity: L

  Note 2026-08-23: compileSdk 37 and Media3 1.11.0 are already shipped, so the old dependency gate is gone. Keep this sequenced after the existing video-cache and main-thread encode reliability work; use Media3 preload first and retain the custom engine only if a measured black-frame test still fails.

- [ ] P2 — Declare a locale config and document a translation contribution path
  Why: this fork already ships `values-zh` for all 1,818 strings and 17 plurals plus an in-app language picker, but the manifest still declares no `localeConfig`, so Android 13+ cannot offer Aura in the system per-app language picker, and there is still no documented way for a translator to add the next locale.
  Evidence: `ls -d app/src/main/res/values*/` returns `values/` and `values-zh/` (plus `app/src/full/res/values-zh/`); no `localeConfig` in `AndroidManifest.xml` and no `res/xml/locales_config.xml`; language selection is app-private through `LocaleHelper.kt` and `SettingsLanguageSection.kt`; no Weblate, Crowdin, or Transifex configuration in the repo; `CONTRIBUTING.md` mentions locales only in a `Locale.ROOT` code-style note. Complements the tracked "residual runtime localization gaps" item, which covers the remaining hardcoded literals — including `MediaIngestion.kt:488-494`, which builds English `" or "` / `", or "` conjunctions in user-facing text.
  Touches: `AndroidManifest.xml`, `res/xml/locales_config.xml`, `CONTRIBUTING.md`, a hosting configuration, `tools/` gate.
  Acceptance: `android:localeConfig` is declared and lists every shipped locale including `zh`; the language appears in Android's per-app language picker, verified on device or emulator; the in-app picker and the system picker agree on the active locale; `CONTRIBUTING.md` documents how to submit a translation; a gate fails when a locale directory exists but is missing from the locale config, or vice versa.
  Complexity: M

  Note 2026-08-23: upstream issue #47 and PR #48 requested the Simplified Chinese locale this fork already carries. If that work is taken upstream, reconcile against the current 1,818-key main set plus the 66-key `full` set rather than PR #48's stale 1,700 keys, and keep `values-zh` listed in the locale config.

- [ ] P2 — Give TalkBack announcements and a controlled reading order
  Why: the interactive-element audit recorded clean labels on 2026-08-11; the missing layer is *announcements*. Three `liveRegion` usages cover an app whose primary surfaces are async grids, a download queue, and audio playback, so a screen-reader user gets no notification when results arrive, a download finishes, or playback state changes. Reading order is entirely unmanaged.
  Evidence: `liveRegion` 3 occurrences, `heading` 9, `traversalIndex` 0, `isTraversalGroup` 0 across `app/src/main/java`; 50 `AuraStateCard` usages across 17 of 86 screen files show where async state transitions already exist and go unannounced.
  Touches: `SharedComponents.kt` (`AuraStateCard`), `DownloadsScreen.kt`, `SoundDetailScreen.kt`, the three feed screens, `app/src/androidTest/.../AccessibilityReleaseGateTest.kt`, `tools/accessibility_release_gate_check.py`.
  Acceptance: loading→ready, loading→error, and empty transitions announce politely once and do not re-announce on recomposition; download completion and playback state changes announce; feed sections are traversal groups with a defined order; the accessibility gate asserts a live region exists on each async surface it already covers.
  Complexity: M

- [ ] P2 — Preflight live-wallpaper capability and provide a truthful static fallback
  Why: `AndroidManifest.xml:37-40` marks live wallpaper optional, but `LiveWallpaperLauncher.kt:15-35` only tries direct/chooser intents and reports a generic failure; `WallpaperApplier.isSupported()` covers static wallpaper operations, not live-wallpaper feature/service availability. This is distinct from the existing P1 item that detects a live wallpaper that was active and later disappeared.
  Evidence: `AndroidManifest.xml:37-40`, `LiveWallpaperLauncher.kt:15-35`, `WallpaperApplier.kt:225-228`; Android `WallpaperManager`/live-wallpaper APIs; the UndeadWallpaper community thread consulted on 2026-08-11 reports OEM devices that disable live wallpapers.
  Touches: `LiveWallpaperLauncher.kt`, video/parallax entry points, `VideoWallpapersScreen.kt`, `WallpaperDetailScreen.kt`, capability tests, strings.
  Acceptance: before launch, Aura checks `PackageManager.FEATURE_LIVE_WALLPAPER`, resolves the requested service and action, and distinguishes unsupported, unavailable, and security-denied states; image sources offer static apply when valid, video-only sources explain the limitation; no path claims success after an unresolvable intent; tests cover no feature, missing service, security failure, and static fallback.
  Complexity: S

- [ ] P2 — Make direct media downloads validator-aware and resumable
  Why: `DownloadManager.downloadFile()` always issues an unconditional GET, starts a new temp file at byte zero, and deletes it after interruption; `DownloadProgress` is process-local and `DownloadEntity` stores only completed MediaStore rows. Size caps prevent oversized writes but do not prevent a mobile user from paying for the same interrupted 64 MiB transfer repeatedly. This complements, rather than duplicates, the existing BatchDownloadService item: that item fixes job lifetime, while this one fixes per-file transport.
  Evidence: `app/src/main/java/com/chloemlla/aura/service/DownloadManager.kt:114-185`, `app/src/main/java/com/chloemlla/aura/data/model/Models.kt:150-159`; RFC 9111 sections on incomplete/partial responses and validation; OkHttp’s cache/client API; cssnr/remote-wallpaper-android issue #26 requesting HTTP caching.
  Touches: `DownloadManager.kt`, `Models.kt`, `Database.kt`/Room migration, `DownloadEntity`/DAO, `DownloadsScreen.kt`, transport tests with a local HTTP server, cleanup/diagnostics.
  Acceptance: a stable download identity persists temp path, URL, byte count, size, and ETag/Last-Modified when available; retries send `Range` plus `If-Range` only with a matching validator and accept continuation only for a valid `206`; `200`, validator mismatch, range mismatch, or changed length safely truncates and restarts; completion remains temp-then-atomic MediaStore publication; process death resumes or clearly marks a recoverable failure; size/sniffing caps apply to the aggregate bytes; tests cover 206 resume, 200 restart, 412/validator change, cancellation, stale-temp cleanup, and no duplicate MediaStore rows.
  Complexity: M

- [ ] P2 — Ship the 24H wallpaper-pack editor its Settings toggle already promises
  Why: the toggle schedules `WallpaperPackWorker` every 15 minutes, but no UI can create or edit a pack, so the worker polls DataStore JSON that is always empty — perpetual no-op battery work shipped as a feature; time-of-day playlists are also Wallpaper Engine's most-praised capability.
  Evidence: commit `2025c41` ("editor UI for defining individual slots is a follow-up"); `SettingsWallpaperSection.kt:249`; `WallpaperPackManager.kt` (worker parses `prefs.wallpaperPackJson` that nothing writes); Wallpaper Engine Android time-of-day playlists.
  Touches: a pack editor surface (settings section or dedicated screen), `WallpaperPackManager.kt`, `SettingsViewModel.kt`, `PreferencesManager.kt`, string resources, tests.
  Acceptance: users can create, edit, and delete packs with wallpapers assigned per daypart (morning/day/evening/night) and per target (home/lock/both); the worker is enqueued only when an enabled pack has at least one slot and is cancelled when the last one is removed; with no pack defined the toggle explains what to do instead of scheduling empty work; tests cover empty-pack gating and slot resolution across the overnight wrap.
  Complexity: M

- [ ] P2 — Ship the sound-profile editor its Settings toggle already promises
  Why: same defect class as the pack editor — the toggle schedules `SoundProfileWorker` every 15 minutes and the worker defers with "no sound profiles defined" forever, because no UI can create a profile.
  Evidence: commit `3bfb2d7` ("Profile editor UI for defining individual profiles is a follow-up"); `SettingsSoundSection.kt:198`; `SoundProfileManager.kt:82-93` (empty-profile deferral each run).
  Touches: a profile editor surface, `SoundProfileManager.kt`, `SettingsViewModel.kt`, `PreferencesManager.kt`, string resources, tests.
  Acceptance: users can create named profiles mapping ringtone/notification/alarm URIs to start/end hours, enable/disable each, and delete them; the worker is enqueued only when at least one enabled profile exists; profile application records into the existing `lastApplied*Uri` restoration data so boot restoration does not stomp it; tests cover empty gating, overlapping windows, and the overnight wrap.
  Complexity: M

- [ ] P2 — Finish live-wallpaper dimming on the video and parallax engines
  Why: `LiveWallpaperDimming` (dim + double-tap reveal) shipped wired into `WeatherWallpaperService` only, with the other two engines named as follow-up wiring that never happened; the Settings toggle reads as engine-agnostic, so on video/parallax it is a silent no-op.
  Evidence: commit `517f642` ("reusable by VideoWallpaperService and ParallaxWallpaperService (left as follow-up wiring)"); grep shows no dimming reference in `VideoWallpaperService.kt` or `ParallaxWallpaperService.kt`; Muzei recede mode is the category reference.
  Touches: `VideoWallpaperService.kt`, `ParallaxWallpaperService.kt`, `LiveWallpaperDimming.kt`, the live-wallpaper soak harness, string resources.
  Acceptance: dim level and double-tap reveal behave identically on all three engines; re-dim after reveal follows the one-shot delayed-frame pattern CLAUDE.md documents for `WeatherWallpaperService.scheduleDraw()`; the soak harness runs the dimmed path and asserts no extra bitmap retention; until parity lands the toggle copy names the engines it affects.
  Complexity: S

- [ ] P2 — Classify OEM ringtone-write failures instead of failing generically
  Why: `SoundApplier` calls `RingtoneManager.setActualDefaultRingtoneUri` with no OEM-failure handling, and Samsung devices are documented throwing `IllegalArgumentException` ("cannot keep your settings in the secure settings") on notification-sound writes — the user sees a generic failure for a known, explainable device behavior in the app's core action.
  Evidence: `SoundApplier.kt:70,109`; Samsung developer-forum reports of the secure-settings exception on Galaxy devices; Samsung community threads on tones not persisting after updates.
  Touches: `SoundApplier.kt`, `ContactRingtoneService.kt`, error string resources, `SettingsDiagnosticsSection.kt` or the diagnostics bundle.
  Acceptance: the secure-settings failure class is caught and distinguished from missing `WRITE_SETTINGS`; the user gets device-specific guidance including a one-tap route to the system sound picker as fallback; the failure class is counted in diagnostics; a test covers the `IllegalArgumentException` path for each of the three sound types.
  Complexity: S

- [ ] P2 — Prefetch the next rotation wallpaper
  Why: `AutoWallpaperWorker` fetches from the provider at fire time, so a dead or metered-blocked network at the trigger means a skipped rotation; prefetching the next candidate after each successful rotation makes remote-source rotation as reliable as local, and Wallora demonstrates the pattern.
  Evidence: `AutoWallpaperWorker.kt` provider fetch in `doWork`; Wallora README (prefetch cache for instant apply); WallFlow's open offline-mode request.
  Touches: `AutoWallpaperWorker.kt`, `DailyWallpaperWorker.kt`, a bounded prefetch cache (or `OfflineFavoritesManager` reuse), rotation diagnostics, tests.
  Acceptance: after each successful rotation the next candidate downloads to a bounded cache (count and byte budget) respecting metered/data-saver posture; at fire time a cached candidate applies without network and the cache refills afterward; cache misses fall back to the current fetch path; local-source rotation is unchanged; diagnostics report prefetch hit/miss; tests cover hit, miss, budget eviction, and metered deferral.
  Complexity: M

### P3

- [ ] P3 — Emit a CycloneDX SBOM from the resolved dependency graph
  Why: the EU Cyber Resilience Act requires a machine-readable SBOM of at least top-level dependencies from 2027-12-11; Aura's readiness doc defers this to N-1, but the CycloneDX Gradle plugin works on the current toolchain and reads the resolved graph, so the `commons-io`/`jackson`/`commons-compress` constraints appear correctly.
  Evidence: `docs/distribution/sbom-readiness.json` (`status: deferredUntilN1ToolchainUpgrade`, `futureSbomArtifacts`); `app/build.gradle.kts` constraints block; CycloneDX Gradle plugin.
  Touches: `app/build.gradle.kts` or a convention plugin, `tools/sbom_readiness_check.py`, release artifact bundle.
  Acceptance: a release task emits `SBOM.cyclonedx.json` covering the release runtime graph plus native payloads; the pinned constraint versions appear as resolved; the artifact is published with the release and checked by the bundle gate.
  Complexity: M

- [ ] P3 — Reintroduce dependency verification with trusted PGP keys
  Why: this fork removed `gradle/verification-metadata.xml` in `df3a661d` because the checksum-only file broke the build on every version bump, so dependency verification is currently off entirely. Trusted keys survive upgrades and Gradle reports key rotation separately from new dependencies, which is what makes the file maintainable rather than a per-bump rewrite.
  Evidence: `gradle/verification-metadata.xml` is absent from the tree; `df3a661d` ("remove verification-metadata.xml to resolve CI dependency verification failures") and the earlier `a481abab`/`e6e6c310` trusted-artifact patches show the drift pattern; JitPack `NewPipeExtractor` and a prerelease `youtubedl-android` are exactly the risk profile verification exists for.
  Touches: `gradle/verification-metadata.xml`, `tools/gradle_wrapper_check.py` or a new verification gate, `.github/workflows/aura-android.yml`.
  Acceptance: verification is re-enabled with signature verification and trusted keys for signed artifacts, checksums retained only for unsigned ones; a clean-clone build and the CI build both verify; the regeneration command is documented; a gate fails when the file is missing or reverts to checksum-only.
  Complexity: M

- [ ] P3 — Add a wallpaper position lock and launcher-parallax suppression
  Why: launcher-driven zoom and scroll parallax move applied wallpapers off the framing the user chose, and users explicitly ask for a lock; Aura's crop and editor work is undone by it.
  Evidence: WallYou #289 ("Force the wallpapers to be non-movable"), darkmodewallpaper #87 (14 comments), #218, WallFlow #25, doodle-android #93; `WallpaperApplier.kt`.
  Touches: `WallpaperApplier.kt`, live-wallpaper engines' `onOffsetsChanged`, settings toggle, string resources.
  Acceptance: an opt-in setting applies wallpapers sized so the launcher cannot pan or zoom them, live engines ignore offset changes when it is on, and the behavior is documented as launcher-dependent where the platform cannot guarantee it.
  Complexity: M

- [ ] P3 — Add UI test anchors
  Why: there are zero `testTag` modifiers in the entire main source tree, so there is nothing for a Compose UI test to attach to. The tracked "test production composables instead of look-alike route fixtures" item will hit this on its first day, and four `androidTest` files is the current ceiling.
  Evidence: `grep -rn "testTag" app/src/main/java` returns 0; `app/src/androidTest` holds four files; `ProductionRouteStateScreenshotTest.kt` is the sole Roborazzi entry point.
  Touches: `SharedComponents.kt`, the five bottom-nav screens, `ui/navigation/Screen.kt`, a tag constants file.
  Acceptance: a single constants object defines tags for the five nav destinations, the primary list on each, and the shared state card; tags are applied via a helper that compiles out of release builds or is asserted absent from the release APK; the accessibility and screenshot suites select by tag rather than by text.
  Complexity: S

- [ ] P3 — Add a user-supplied URL or self-hosted wallpaper source
  Why: Aura has eight third-party feeds and no way for a user to point it at their own — no WebDAV, no SMB, no arbitrary URL. For a local-first app whose charter is not depending on anyone's marketplace, that is the missing source, and it is the only one that cannot rot, rate-limit, or change its terms.
  Evidence: no WebDAV, SMB, or custom-endpoint client under `data/remote/`; `ProviderCapability.kt` already models `LOCAL` and `ProviderConfiguration.REQUIRED_KEY`, so the policy layer can express it; cssnr/remote-wallpaper-android; WallFlow #113 ("Reddit stopped working", open, in an app whose maintainer stopped pushing in 2024) is the counter-example.
  Touches: a new provider client and repository, `ProviderCapability.kt`, `ProviderDisclosure.kt`, `ProviderNetworkPolicy.kt`, `tools/network_endpoint_inventory_check.py`, settings UI, tests.
  Acceptance: a user can register one or more HTTPS endpoints returning an image or an image list, with optional basic auth stored through `ProviderCredentialStore`; the source is opt-in, off by default, declared in the disclosure layer so its provenance is recorded, and cleartext is refused; failure states are visible and per-endpoint; a test covers a single image, a listing, an unreachable host, and a non-image response.
  Complexity: M

- [ ] P3 — Narrow the R8 keep rules
  Why: nine wildcard keeps preserve entire packages — including Aura's whole network layer — that the libraries' own consumer rules already cover, which defeats obfuscation of the app's own DTOs and adds dex the shrinker could remove. Small next to the native payload, but free.
  Evidence: `app/proguard-rules.pro:2-3` keeps `com.chloemlla.aura.data.remote.**` and all its members; `:9,25,26,29-32` do the same for `retrofit2`, `org.schabi.newpipe.extractor`, `org.mozilla.javascript`, `com.yausername`, `org.apache.commons.compress`, `org.apache.commons.io`; Retrofit, Moshi, and commons-* all ship consumer rules; Moshi KSP codegen needs only the generated adapters kept.
  Touches: `app/proguard-rules.pro`, release verification.
  Acceptance: each remaining keep names a class or a narrow member set with a comment stating what breaks without it; a release build passes the JVM suite, the Roborazzi suite, and a manual pass over every provider; dex method count and APK size before and after are recorded.
  Complexity: S

- [ ] P3 — Record the ML Kit dependency risk and decide a fallback
  Why: parallax wallpapers, smart crop, and depth portraits rest on a Play-services beta artifact published 2023-11-06 and never promoted to stable. If it is withdrawn or crashes, three advertised features stop working in the `full` flavor — and they are already absent from `foss`, which the README feature table does not mention, in the very artifact IzzyOnDroid would ship.
  Evidence: `app/build.gradle.kts:345-349` pins the `play-services-mlkit-subject-segmentation` beta as `fullImplementation` with a comment noting no bundled artifact exists; `SmartCropDetector.kt`, `DepthPortraitComposer.kt`, `ParallaxWallpaperService.kt`; `app/src/foss/java/com/google/mlkit/vision/segmentation/subject/SubjectSegmentation.kt` is a stub; README's feature table does not distinguish the flavors.
  Touches: `docs/distribution/` (a dependency-risk record), README feature table, `tools/fdroid_preflight.py`, `SmartCropDetector.kt`, `DepthPortraitComposer.kt`, `ParallaxWallpaperService.kt`.
  Acceptance: a record names the artifact, its 2023 publish date, the three features that depend on it, and the chosen response if it is withdrawn or crashes; all three features degrade visibly rather than silently when segmentation is unavailable, and tests cover those paths; the README states which features the FOSS build omits; the preflight asserts the README statement matches the `foss` source set.
  Complexity: S

  Note 2026-08-23: upstream issue googlesamples/mlkit#1017 reports an uncatchable API 36 SIGSEGV in the exact beta1 artifact. Before choosing a fallback, run the full release build through `SmartCropDetector`, `DepthPortraitComposer`, and `ParallaxWallpaperService` on API 36; if reproduced, prevent inference on affected devices until a patched artifact or tested replacement is available. Confidence: Needs live validation in Aura.

- [ ] P3 — Ship a haptic pattern alongside a ringtone
  Why: Android 16 added envelope-based vibration builders that describe amplitude and frequency curves and abstract away device capability, and Aura already owns both the sound editor and the apply path. Current compileSdk 37 exposes the APIs, so the remaining work is device-capability fallback and integration.
  Evidence: no `VibrationEffect`, `BasicEnvelopeBuilder`, or `WaveformEnvelopeBuilder` anywhere in `app/src/main/java`; `SoundApplier.kt` and `ContactRingtoneService.kt` are the apply surfaces; developer.android.com custom-haptic-effects.
  Touches: `SoundApplier.kt`, `SoundEditorScreen.kt`, `ContactRingtoneService.kt`, `PreferencesManager.kt`, theme-pack recipe schema, string resources.
  Acceptance: a small preset set of vibration patterns can be previewed in the editor and stored with a sound; the pattern is applied where the platform allows and the limitation is stated where it does not; devices without envelope support fall back to a simple waveform and say so; patterns round-trip through theme-pack export and import.
  Complexity: M

- [ ] P3 — Claim the distribution and discovery surfaces that are currently empty
  Why: Aura is the highest-starred FOSS Android ringtone project under GitHub `topic:ringtone`, a topic that is nearly empty, and the F-Droid ringtone shelf holds one abandoned fork. It is on no awesome-list, and `offa/android-foss` has a one-entry wallpaper section and no live-wallpaper or ringtone section at all. This is the cheapest reach available and it needs no code.
  Evidence: `offa/android-foss` wallpaper section lists one app; `vvolas/Awesome-Live-Wallpaper` is Android-specific and dead since 2016; `w3teal/awesome-ringtone` does not list Aura; F-Droid's RFP queue shows live unserved wallpaper demand.
  Touches: no app code; README topics, external PRs, `docs/distribution/channel-strategy.md`.
  Acceptance: `docs/distribution/channel-strategy.md` records which lists were submitted to and when, with links; GitHub topics are set; submissions happen only after the Fastlane-image, signing-transparency, and reproducibility prerequisites in this roadmap are complete; an IzzyOnDroid inclusion request waits for the owner decision recorded in `Roadmap_Blocked.md`.
  Complexity: S

- [ ] P3 — Restart the rotation countdown on manual wallpaper changes
  Why: a manual apply does not touch the periodic schedule (`ExistingPeriodicWorkPolicy.UPDATE` keeps the existing cadence and the apply coordinator never reschedules), so rotation can overwrite a user's deliberate choice moments after they made it — a documented complaint class in Paperize.
  Evidence: `WallpaperApplyCoordinator.kt` (no rescheduling); `AutoWallpaperWorker.kt:307` (`ExistingPeriodicWorkPolicy.UPDATE`); Paperize #591.
  Touches: `WallpaperApplyCoordinator.kt`, `AutoWallpaperWorker.kt` scheduling companion, settings copy, tests.
  Acceptance: a manual apply from any surface (detail, shuffle, widget, tile, external broadcast) restarts the rotation countdown, governed by an on-by-default "restart timer on manual change" setting; rotation diagnostics show the recomputed next-fire time; a test proves the next fire moves after a manual apply and does not move when the setting is off.
  Complexity: S

- [ ] P3 — Add Undo and Skip actions to the rotation notification
  Why: the daily-rotation notification is display-only, so recovering from an unwanted rotated wallpaper requires opening the app, finding history, and undoing — while Aura already owns a working undo path; Peristyle and Paperize both ship notification-level controls.
  Evidence: `DailyWallpaperWorker.kt` thumbnail notification with no actions; existing undo via `WallpaperHistoryManager`/`ApplyFeedbackBus`; Peristyle 9.7.5 delete-from-notification; Paperize pause/resume.
  Touches: `DailyWallpaperWorker.kt`, `AutoWallpaperWorker.kt`, a notification action receiver, `WallpaperHistoryManager.kt`, string resources, tests.
  Acceptance: the rotation notification offers Undo (restores the previous wallpaper through the existing history path) and Skip/Next; actions work with the app process dead; the notification can be silenced per channel without disabling rotation; tests cover undo-restores-previous and skip-advances.
  Complexity: M

- [ ] P3 — Publish signing-cert transparency and register the reproducible FOSS lane
  Why: AppVerifier-style verification and IzzyOnDroid's reproducible-build badge both key off a published signing certificate digest and a reproducible recipe; Aura already prints the cert SHA-256 in release notes and has `tools/foss_reproducibility_check.py`, but README/fastlane carry no digest and no rbtlog registration exists.
  Evidence: IzzyOnDroid reproducible-builds page and `AllowedAPKSigningKeys` practice; codeberg.org/IzzyOnDroid/rbtlog; release-signing runbook prints the digest only into release notes.
  Touches: `README.md`, `fastlane/metadata/`, `docs/distribution/release-signing.md`, a small gate asserting the published digest matches the keystore, rbtlog registration when the IzzyOnDroid submission proceeds.
  Acceptance: the release signing certificate SHA-256 appears in README and fastlane metadata and a gate fails when it drifts from the actual release keystore; the reproducible FOSS recipe is registered with rbtlog once IzzyOnDroid submission is decided; stale attestation claims in docs are corrected to the local-build reality.
  Complexity: S

- [ ] P3 — Offline procedural wallpaper generator
  Why: Tapet's entire paid differentiator is offline procedural generation at exact screen resolution with palette control; Aura owns palette extraction, Material You seeds, an AGSL pipeline, and rotation, so a deterministic on-device generator neutralizes it while fitting the charter exactly (offline, no AI, no provider). Distinct from the rejected R-1 AI generation: no model, no network, reproducible from a seed.
  Evidence: Tapet Play listing (premium palettes/patterns); Waller gradient generator and Shader Editor demand on F-Droid; `ColorExtractor`/`WallpaperPalette`, `AgslShaderGallery.kt`, and the rotation source picker as existing infrastructure.
  Touches: a new generator service (pattern families seeded by palette + RNG seed), `ContentSource` enum, WallpapersScreen entry point, rotation source picker, `ProviderDisclosure.kt` (local provenance), tests.
  Acceptance: users generate wallpapers offline at exact screen resolution from a chosen palette (including the current Material You palette) and pattern family, then save/apply/favorite them; a "Generated" rotation source produces a fresh image per rotation with no network; output carries provenance metadata distinct from AI and provider content; generation is deterministic given a seed, and tests assert determinism and resolution.
  Complexity: L
