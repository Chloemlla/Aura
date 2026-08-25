# Research — Aura
Date: 2026-08-23 — replaces all prior research.

Confidence labels: **Verified** means confirmed in the current repository, tracker, release artifacts, or primary documentation. **Likely** means several credible sources point the same way but Aura has no direct measurement. **Needs live validation** means the evidence is specific enough to test but does not yet prove Aura is affected.

## Executive Summary

**Verified:** Aura v6.45.2 is already broader than the serious open source alternatives: it combines remote and local wallpaper feeds, static and three live-wallpaper engines, a sound editor and system-sound apply paths, automation, backup, import/export, diagnostics, and full/FOSS variants without accounts or payment rails (`README.md`, `app/build.gradle.kts`, `app/src/main/java/com/chloemlla/aura/`). Its highest-value direction is proof and consolidation, not more surface area. Close the unresolved legacy-Android crash, make native payload verification recursive, finish the live translation contribution already in flight, validate the exact ML Kit beta on Android 16, then reduce package weight without dropping advertised media behavior.

Top opportunities, in priority order:

1. **Verified:** Reopen the evidence behind [issue #2](https://github.com/SysAdminDoc/Aura/issues/2). The reporter reproduced the same `URLEncoder.encode(String, Charset)` `NoSuchMethodError` on Android 10 after v6.31.1 claimed to fix it, while current tests do not exercise release-minified NewPipe search on API 26 to 29 (`app/build.gradle.kts`, `app/src/main/java/com/chloemlla/aura/data/repository/YouTubeRepository.kt`).
2. **Verified:** Recursively inspect nested `.zip.so` payloads in the 16 KB gate. The current report explicitly skips the Python and FFmpeg archives, so it cannot prove every packaged 64-bit ELF is compliant (`tools/native_alignment_check.py`, `docs/distribution/native-alignment.json`).
3. **Verified:** This fork ships the Simplified Chinese locale upstream [issue #47](https://github.com/SysAdminDoc/Aura/issues/47) and [PR #48](https://github.com/SysAdminDoc/Aura/pull/48) asked for: `values-zh` covers all 1,818 main strings and 17 plurals, plus the 66 `full`-flavor keys. The platform-level gap is now closed too: upstream's v6.45.1 declares `android:localeConfig="@xml/locales_config"` listing `en` and `zh`, so the Android 13+ per-app language picker can list Aura (`app/src/main/res/values-zh/strings.xml`, `app/src/full/res/values-zh/strings.xml`, `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/locales_config.xml`).
4. **Verified:** Complete the existing service/editor reliability and silent-failure roadmap items before adding new product surfaces. Commit history repeatedly returns to service lifecycle, cancellation, bitmap/media cleanup, and feed race defects (`ROADMAP.md`, `app/src/main/java/com/chloemlla/aura/service/`, `app/src/main/java/com/chloemlla/aura/ui/`).
5. **Needs live validation:** Run the full build through all three subject-segmentation call sites on API 36. [ML Kit issue #1017](https://github.com/googlesamples/mlkit/issues/1017) reports an uncatchable native crash in Aura's exact beta1 artifact (`SmartCropDetector.kt`, `DepthPortraitComposer.kt`, `ParallaxWallpaperService.kt`).
6. **Verified:** Add a documentation-truth gate. `ARCHITECTURE.md` still names Favorites where navigation ships Library, and `CONTRIBUTING.md` states Gradle 8.12 / AGP 8.9.3 / Kotlin 2.1.0 / SDK 35-36 against a tree on Gradle 9.5.0, AGP 9.3.1, Kotlin 2.3.21, and compileSdk/targetSdk 37 (`ARCHITECTURE.md`, `CONTRIBUTING.md`, `app/build.gradle.kts`, `gradle/libs.versions.toml`).
7. **Verified:** The bundled FFmpeg module is already gone from this fork. `youtubedl-android:ffmpeg` was dropped in favour of `FfmpegDownloader`, which fetches a SHA-256-pinned ffmpeg-kit binary on first use, so the remaining bundled native payload is the yt-dlp Python archive from `youtubedl-android:library`. What is left is to retire that extractor runtime, which is what still forces `useLegacyPackaging = true` and keeps the arm64 artifact above the 30 MB IzzyOnDroid review threshold (`app/build.gradle.kts`, `FfmpegDownloader.kt`, `docs/distribution/native-alignment.json`).
8. **Verified:** Keep the existing accessibility, translation, store-metadata, signing-transparency, and recovery work ahead of marketplace or social features (`ROADMAP.md`, `Roadmap_Blocked.md`, `README.md`).

## Product Map

### Core workflows

- **Verified:** Browse, search, filter, favorite, download, and apply wallpapers from remote providers, community content, and local storage; target home, lock, or both (`WallpapersScreen.kt`, `WallpaperDetailScreen.kt`, `WallpaperApplier.kt`).
- **Verified:** Edit images and sounds, including crop, color treatment, depth portrait, overlays, trim, fades, speed, conversion, lossless cuts, and system ringtone/notification/alarm application (`WallpaperEditorScreen.kt`, `WallpaperEditorViewModel.kt`, `SoundEditorScreen.kt`, `AudioTrimmer.kt`, `SoundApplier.kt`).
- **Verified:** Run video, parallax, and weather/shader live wallpapers with battery controls, then automate changes by interval, clock, day/night, theme, unlock, or screen-off triggers (`VideoWallpaperService.kt`, `ParallaxWallpaperService.kt`, `WeatherWallpaperService.kt`, `AutoWallpaperWorker.kt`, `RotationTriggerService.kt`).
- **Verified:** Export/import favorites and libraries, share collections, schedule backups, inspect diagnostics, and recover wallpaper/rotation state after process or device events (`FavoritesExporter.kt`, `LibraryExporter.kt`, `AutoBackupWorker.kt`, `CrashDiagnosticsCollector.kt`, `VideoWallpaperRecovery.kt`, `RotationTriggerRecovery.kt`).

### User personas

- **Verified:** The current product serves privacy-first sideloaders, collectors with local libraries, users who automate home/lock personalization, sound customizers, and community contributors without requiring an account (`README.md`, `docs/privacy/`, `app/src/main/java/com/chloemlla/aura/ui/`).
- **Verified:** Maintainers and alternative-store users are a first-class persona because the repo ships full/FOSS flavors, per-ABI artifacts, checksums, Fastlane metadata, reproducibility checks, and Obtainium metadata (`app/build.gradle.kts`, `fastlane/metadata/android/`, `obtainium.json`, `tools/foss_reproducibility_check.py`).

### Platforms and distribution

- **Verified:** Aura is Android-only with package `com.chloemlla.aura`, minSdk 26, compileSdk 37, targetSdk 37, versionName 6.45.2, and versionCode 148. Release builds minify, shrink resources, and split by ABI into `armeabi-v7a`, `arm64-v8a`, and `x86_64`; this fork publishes no universal APK. ABI splits switch themselves off while a `bundle*` task is requested, because AGP 8.9+ cannot build a bundle from multi-APK shrunk resources, so `assemble*` and `bundle*` have to run as separate Gradle invocations (`app/build.gradle.kts`).
- **Verified:** Upstream's GitHub Release v6.45.0 was published on 2026-08-20 with four ABI APKs, a universal APK, and checksums; its arm64 APK is 63,481,458 bytes ([release](https://github.com/SysAdminDoc/Aura/releases/tag/v6.45.0)). Upstream has since moved to versionCode 148 (v6.45.2) without a matching GitHub Release. This fork releases the same versionCode from its own workflow with the three-ABI split above.
- **Verified:** GitHub/Obtainium is the current install path. IzzyOnDroid is planned but still depends on open store-image, signing, reproducibility, and size work (`README.md`, `obtainium.json`, `ROADMAP.md`, `Roadmap_Blocked.md`).

### Key integrations and data flows

- **Verified:** Wallpaper and media inputs include Wallhaven, Pexels, Pixabay, Bing, Reddit, Lemmy, NASA, Wikimedia, local storage, YouTube extraction, Open-Meteo, Firebase community data, and optional Stability AI in the full flavor (`app/src/main/java/com/chloemlla/aura/data/remote/`, `app/src/main/java/com/chloemlla/aura/data/repository/`, `app/src/full/`, `app/src/foss/`).
- **Verified:** Room 2.8.4 with database schema version 17 stores library state; DataStore and a limited SharedPreferences bridge store preferences; SAF and MediaStore handle user files; WorkManager handles scheduled work (`Database.kt`, `PreferencesManager.kt`, `app/src/main/java/com/chloemlla/aura/service/`, `app/build.gradle.kts`).
- **Verified:** NewPipeExtractor is the primary YouTube path and yt-dlp is the fallback. Runtime yt-dlp updates require consent, hash validation, rollback, and bounded command construction (`YouTubeRepository.kt`, `YouTubeYtDlpRequestFactory.kt`, `YtDlpUpdateManager.kt`, `YtDlpDownloadSafety.kt`).
- **Verified:** Media3 now covers standard sound encoding and can cover more crop, trim, composition, and remux work. FFmpeg remains for codec fallbacks, direct video crop/export, and yt-dlp merge/remux paths, and in this fork it is downloaded on demand and hash-verified rather than bundled (`AudioTrimmer.kt`, `VideoCropScreen.kt`, `FfmpegDownloader.kt`, `app/build.gradle.kts`).

## Competitive Landscape

- **Paperize:** **Verified:** Strong offline albums, independent static/live targets, apply-time effects, and careful apply queueing. Aura should copy its device/foldable compatibility discipline and avoid raising minSdk to Paperize's API 31 baseline because Aura promises API 26 support ([repository](https://github.com/Anthonyy232/Paperize), [issues](https://github.com/Anthonyy232/Paperize/issues)).
- **Muzei:** **Verified:** Its long-lived plugin API and recede/dimming behavior are category references. Aura should keep recede parity across all three engines, but defer a public plugin ABI until the blocked compatibility and maintenance questions are resolved ([repository](https://github.com/muzei/muzei), `Roadmap_Blocked.md`).
- **WallFlow and WallYou:** **Verified:** They combine remote feeds, local content, per-display behavior, saved searches, widgets/tiles, and smart crop. Aura already covers most of that breadth. It should learn from WallFlow's separate ML-enabled variant and avoid hiding model bloat or flavor differences ([WallFlow](https://github.com/ammargitham/WallFlow), [WallYou](https://github.com/you-apps/WallYou)).
- **Wallora:** **Verified:** Prefetch, multiple sources, fail-soft rotation, widgets, tiles, and Tasker integration fit Aura's automation model. The prefetch pattern supports Aura's existing rotation-cache item; duplicating Wallora's already-shipped integrations does not ([repository](https://github.com/thissayantan/wallora), `ROADMAP.md`).
- **Peristyle:** **Verified:** Tags and multi-folder local organization are useful, but its broad storage and battery permissions are a poor fit for Aura's permission posture. Learn the library organization, not the access model ([repository](https://github.com/Hamza417/Peristyle), `docs/privacy/`).
- **UndeadWallpaper:** **Verified:** Per-clip framing, speed, mute, shuffle, and a single gapless playback pipeline are the best implementation reference for Aura's existing video-playlist item. Aura should preserve its battery caps and bounded storage rather than copy an always-hot engine ([repository](https://github.com/maocide/UndeadWallpaper), `ROADMAP.md`).
- **Ringdroid:** **Verified:** The maintained fork offers offline waveform editing, recording, scoped-storage support, and current Android compatibility in an APK measured in hundreds of KiB. It supports removing Aura's broad FFmpeg runtime, but not dropping Aura's richer format and video workflows ([repository](https://github.com/althafvly/ringdroid), [F-Droid](https://f-droid.org/en/packages/org.thayyil.ringdroid/)).
- **Zedge:** **Verified:** It validates demand for one app spanning wallpapers, live content, and sounds, but its ads, credits, subscription, marketplace, and AI model conflict with Aura's local-first, no-payment charter. Learn cross-content navigation and attribution; avoid payment rails and account dependency ([site](https://www.zedge.net/), [FAQ](https://help.zedge.net/hc/en-us/articles/360024313191-ZEDGE-for-Android-FAQ), `README.md`).
- **Backdrops and Walli:** **Verified:** Curated originals, visible artists, follows, and playlists make provenance understandable. Aura should expose attribution clearly, but not copy locked tiers, currencies, or creator-payment infrastructure ([Backdrops](https://play.google.com/store/apps/details?id=com.backdrops.wallpapers), [Walli](https://play.google.com/store/apps/details?id=com.shanga.walli)).
- **Tapet:** **Verified:** Deterministic, on-device, exact-resolution pattern generation is its distinctive offline value. Aura's existing procedural-generator roadmap item is a good fit because it can reuse palette, AGSL, and rotation infrastructure without an AI model ([Play listing](https://play.google.com/store/apps/details?id=com.sharpregion.tapet), `ROADMAP.md`).
- **Wallpaper Engine:** **Verified:** Time, interval, login, and application-driven playlists set the scheduling benchmark. Aura should finish its existing 24H pack and video-playlist work while remaining standalone rather than requiring a PC companion ([site](https://www.wallpaperengine.io/en), [playlist documentation](https://store.steampowered.com/news/posts/?appgroupname=Wallpaper+Engine&appids=431960&enddate=1643300588&feed=steam_community_announcements)).
- **Pixel wallpaper tools:** **Verified:** Emoji, cinematic, AI, and weather effects show the platform's visual direction, but several capabilities are Pixel-gated. Aura should keep its OEM-neutral depth and weather paths and avoid tying core behavior to a single vendor service ([Pixel Help](https://support.google.com/pixelphone/answer/16517561), `DepthPortraitComposer.kt`, `WeatherWallpaperService.kt`).

## Reported Issues

- **Verified, bug:** [Issue #2](https://github.com/SysAdminDoc/Aura/issues/2) reports a Sounds crash on Samsung S9+/Android 10. The reporter installed v6.31.1 after core-library desugaring was enabled and reproduced the same NewPipe `URLEncoder.encode(String, Charset)` `NoSuchMethodError`. The issue was closed without a later successful device result. Add a release-minified API 26/27/29 compatibility gate around `YouTubeRepository.kt`.
- **Verified, satisfied:** [Issue #47](https://github.com/SysAdminDoc/Aura/issues/47) asked upstream for Simplified Chinese and [PR #48](https://github.com/SysAdminDoc/Aura/pull/48) offered 1,700 stale keys against it; upstream merged its own `values-zh` in v6.45.1. This fork carries a complete `values-zh` set at key parity with `values/` (1,818 strings, 17 plurals) plus the 66 `full`-flavor keys, an in-app picker in `SettingsLanguageSection.kt` backed by `LocaleHelper.kt`, and now the `localeConfig` declaration. What remains is a documented translation contribution path, not the translation itself.
- **Verified, resolved:** [Issue #44](https://github.com/SysAdminDoc/Aura/issues/44) reported WebM/Opus editing failure. The Media3 fix landed and the reporter confirmed it. Do not create another codec-parity item.
- **Verified, low signal:** [Discussions #45](https://github.com/SysAdminDoc/Aura/discussions/45) and [#46](https://github.com/SysAdminDoc/Aura/discussions/46) have no comments or independent demand. Keep them below tracker reports with reproductions.
- **Verified, not product demand:** Upstream's open automated dependency PRs #35 through #43 are stale bot output and conflict with this fork's pinned toolchain. They do not justify a feature item ([open pull requests](https://github.com/SysAdminDoc/Aura/pulls), `gradle/libs.versions.toml`).

## Security, Privacy, and Reliability

- **Verified:** The 16 KB native check has a proof gap, not a demonstrated shipped 64-bit failure. `inspect_apk()` in `tools/native_alignment_check.py` records nested `.zip.so` files as skipped; `docs/distribution/native-alignment.json` therefore cannot attest to every arm64-v8a and x86_64 ELF. Upstream [issue #334](https://github.com/yausername/youtubedl-android/issues/334) shows why recursive ABI-aware inspection is required.
- **Verified:** yt-dlp command and update handling is unusually defensive. The bundled 2026.07.04 payload is hash-pinned, update consent and rollback are explicit, unsafe execution/write-link/cookie/downloader options are forbidden, and the reviewed write-link advisory is fixed in the bundled version (`YtDlpUpdateManager.kt`, `YtDlpDownloadSafety.kt`, `docs/security/ytdlp-cve-policy.json`, [GHSA-6v4j-43gg-vj32](https://github.com/yt-dlp/yt-dlp/security/advisories/GHSA-6v4j-43gg-vj32)).
- **Needs live validation:** `play-services-mlkit-subject-segmentation:16.0.0-beta1` can terminate the process through native code on API 36 according to [upstream issue #1017](https://github.com/googlesamples/mlkit/issues/1017). Kotlin/Java `catch (Exception)` cannot recover from that failure. Exercise `SmartCropDetector.kt`, `DepthPortraitComposer.kt`, and `ParallaxWallpaperService.kt` before selecting or coding a fallback.
- **Verified:** Recovery coverage is broad: scheduled backups, bounded import validation, favorites/library export, wallpaper history, video-wallpaper recovery, rotation-trigger recovery, and crash diagnostics all exist (`AutoBackupWorker.kt`, `ImportPayloadValidation.kt`, `FavoritesExporter.kt`, `LibraryExporter.kt`, `WallpaperHistoryManager.kt`, `VideoWallpaperRecovery.kt`, `RotationTriggerRecovery.kt`, `CrashDiagnosticsCollector.kt`). New data formats should extend these paths instead of creating parallel stores.
- **Verified:** The current legacy-Android risk is concentrated in dependency bytecode and release transformation, not the source-level SDK declaration. Desugaring is enabled in `app/build.gradle.kts`, but no release-minified API 26 to 29 test proves the NewPipe call path behaves as intended.
- **Likely:** Package size remains a reliability and distribution concern even after the FFmpeg payload was moved to an on-demand download. The bundled yt-dlp Python archive still ships in every ABI split, and the arm64 artifact stays above IzzyOnDroid's 30 MB per-APK ceiling; smaller downloads reduce interrupted sideloads and make the IzzyOnDroid path more practical (`docs/distribution/native-alignment.json`, `FfmpegDownloader.kt`, [IzzyOnDroid policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)).
- **Verified:** On-demand FFmpeg is itself a network and integrity surface, and it is not yet reviewed. `FfmpegDownloader` fetches an ffmpeg-kit release archive from `github.com` over HTTPS and verifies a pinned SHA-256 before use, and the subprocess is routed through `ClashProxyManager` — but `github.com` appears in no entry of `docs/security/network-endpoints.json`, and that file is a scanned source root, so `tools/network_endpoint_inventory_check.py` fails on an unreviewed literal host until the endpoint is declared (`FfmpegDownloader.kt`, `ClashProxyManager.kt`, `docs/security/network-endpoints.json`).

## Architecture Assessment

- **Verified:** Flavor separation is sound. ML Kit and Stability are full-only, FOSS stubs preserve compilation, and release artifacts split by ABI (`app/build.gradle.kts`, `app/src/full/`, `app/src/foss/`). New optional heavy capabilities should follow this boundary.
- **Verified:** The UI has several oversized ownership units: `WallpapersScreen.kt` and `SoundsScreen.kt` are about 1,850 lines each; `VideoWallpapersScreen.kt` is about 1,600; `VideoWallpapersViewModel.kt` is about 1,340. Refactor only while implementing an accepted item, extracting stateful feed/editor sections with their tests rather than performing a broad rewrite.
- **Verified:** The native media boundary is still the largest packaging seam, but it has moved. `AudioTrimmer.kt` and `VideoCropScreen.kt` now reach FFmpeg through the on-demand `FfmpegDownloader` rather than a bundled module, so the residual bundled payload is the yt-dlp extractor runtime. A staged adapter around trim/crop/remux lets each retained operation move independently and keeps behavioral fixtures stable.
- **Verified:** `tools/native_alignment_check.py` is the right release gate but needs recursive archive inspection and ABI-specific fixtures in `test/tools/native_alignment_check_test.py`.
- **Verified:** Documentation has machine-checkable drift. `ARCHITECTURE.md` says Favorites where navigation ships Library, and `CONTRIBUTING.md:20` conflicts with its own `:37` build steps and with the actual toolchain (Gradle 9.5.0, AGP 9.3.1, Kotlin 2.3.21, compileSdk/targetSdk 37). Extend `tools/manifest_consistency_check.py` instead of relying on another manual checklist.
- **Verified:** Migration and upgrade work already has two clear seams: Room migrations and schema snapshots for internal state, plus `LibraryImportPlan.kt` and `ImportPayloadValidation.kt` for portable imports. Any accepted item that changes stored content should update both seams and add forward, rollback, and malformed-input fixtures rather than creating another store (`Database.kt`, `app/schemas/`, `LibraryImportPlan.kt`, `ImportPayloadValidation.kt`).
- **Verified:** Test volume is strong at the JVM and Python-gate layers — 155 JVM test files and 85 Python gates with 84 pytest mirrors — but device compatibility evidence is thin: only four Android test files cover a minSdk 26 app. The two new P1 gates target the highest-risk holes and can run in the existing fork workflow (`app/src/test/`, `test/tools/`, `app/src/androidTest/`, `.github/workflows/aura-android.yml`).
- **Verified:** The shipped Roborazzi states cover themes, sizes, RTL, loading, empty, and error variants. Existing design-token, TalkBack, and production-composable roadmap items already capture the remaining visual/accessibility work, so this pass adds no duplicate UI-polish item (`app/src/test/screenshots/`, `ProductionRouteStateScreenshotTest.kt`, `ROADMAP.md`).

## Rejected Ideas

- **Verified:** Add a second validation CI system. Rejected because this fork already runs `.github/workflows/aura-android.yml` for build and release, so the upstream "restore validation-only CI" item does not apply here; extend the existing workflow instead of adding another (`.github/workflows/aura-android.yml`).
- **Verified:** Add accounts, paid tiers, credits, creator payouts, or a social graph. Zedge, Walli, and Backdrops use these systems, but they contradict Aura's local-first, no-account, no-payment charter ([Zedge](https://www.zedge.net/), [Walli](https://play.google.com/store/apps/details?id=com.shanga.walli), [Backdrops](https://play.google.com/store/apps/details?id=com.backdrops.wallpapers), `README.md`).
- **Verified:** Add a Muzei-compatible plugin ABI now. The compatibility and long-term maintenance requirements remain explicitly blocked, while Aura already supports local, remote, widget, tile, Tasker, and broadcast paths ([Muzei](https://github.com/muzei/muzei), `Roadmap_Blocked.md`).
- **Verified:** Add multi-user or cloud synchronization. It requires an account, identity, server, conflict-resolution, and deletion model that Aura intentionally does not own; the existing decision stays blocked unless the product charter changes (`README.md`, `Roadmap_Blocked.md`).
- **Verified:** Raise minSdk to 31 to match newer competitors. That would abandon supported API 26 to 30 devices and evade issue #2 instead of fixing it (`app/build.gradle.kts`, [Paperize](https://github.com/Anthonyy232/Paperize)).
- **Needs live validation:** Replace ML Kit immediately. One upstream API 36 report is enough to require a release-device test, not enough to choose a larger or less accurate dependency before Aura reproduces the crash ([issue #1017](https://github.com/googlesamples/mlkit/issues/1017), `ROADMAP.md`).
- **Verified:** Remove the remaining native payload by deleting formats or yt-dlp workflows. The package-size benefit is real, but the accepted path must preserve advertised sound, crop, YouTube, and Reddit behavior through Media3 or the on-demand codec path (`AudioTrimmer.kt`, `VideoCropScreen.kt`, `VideoWallpapersViewModel.kt`, `FfmpegDownloader.kt`).
- **Verified:** Re-add table-stakes features already shipped: multiple remote sources, local folders, per-screen apply, rotation triggers, day/night scheduling, widgets, tiles, Tasker, backup/export, and recovery. Competitors confirm demand, but duplicates would only expand the roadmap (`README.md`, `app/src/main/java/com/chloemlla/aura/`, `ROADMAP.md`).
- **Verified:** Add cloud or network AI wallpaper generation as a core feature. It conflicts with the FOSS/local charter, adds credential and moderation burden, and duplicates an existing rejected direction; deterministic offline generation is the compatible alternative (`README.md`, `Roadmap_Blocked.md`, `ROADMAP.md`).

## Sources

### Project and tracker

- https://github.com/SysAdminDoc/Aura
- https://github.com/SysAdminDoc/Aura/issues/2
- https://github.com/SysAdminDoc/Aura/issues/44
- https://github.com/SysAdminDoc/Aura/issues/47
- https://github.com/SysAdminDoc/Aura/pull/48
- https://github.com/SysAdminDoc/Aura/releases/tag/v6.45.0

### Direct OSS competitors

- https://github.com/Anthonyy232/Paperize
- https://github.com/Anthonyy232/Paperize/issues
- https://github.com/muzei/muzei
- https://github.com/ammargitham/WallFlow
- https://github.com/you-apps/WallYou
- https://github.com/thissayantan/wallora
- https://github.com/Hamza417/Peristyle
- https://github.com/maocide/UndeadWallpaper
- https://github.com/althafvly/ringdroid
- https://f-droid.org/en/packages/org.thayyil.ringdroid/

### Commercial competitors

- https://help.zedge.net/hc/en-us/articles/360024313191-ZEDGE-for-Android-FAQ
- https://play.google.com/store/apps/details?id=com.backdrops.wallpapers
- https://play.google.com/store/apps/details?id=com.shanga.walli
- https://play.google.com/store/apps/details?id=com.sharpregion.tapet
- https://www.wallpaperengine.io/en
- https://support.google.com/pixelphone/answer/16517561

### Adjacent-domain projects

- https://github.com/ImranR98/Obtainium
- https://github.com/soupslurpr/AppVerifier
- https://github.com/JunkFood02/Seal
- https://github.com/TeamNewPipe/NewPipeExtractor/issues
- https://github.com/yausername/youtubedl-android
- https://github.com/yausername/youtubedl-android/issues/248
- https://github.com/yausername/youtubedl-android/issues/334

### Awesome-lists

- https://github.com/pcqpcq/open-source-android-apps/blob/master/categories/personalization.md
- https://github.com/Axorax/awesome-free-apps/blob/main/filter/android-only.md
- https://github.com/mobilenetworkltd/openapk/blob/main/categories/theming.md

### Community signal

- https://www.reddit.com/r/androidapps/comments/1e1xwms/
- https://www.reddit.com/r/androidapps/comments/1tq2c59/
- https://stackoverflow.com/questions/68247766/default-live-wallpaperservice-leaks-memory-in-android

### Standards and platform APIs

- https://developer.android.com/guide/topics/resources/app-languages
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/studio/write/java8-support
- https://developer.android.com/studio/test/managed-devices
- https://izzyondroid.org/docs/general/AppInclusionPolicy/
- https://f-droid.org/docs/Reproducible_Builds/
- https://www.rfc-editor.org/rfc/rfc9111

### Academic and engineering sources

- https://www.sciencedirect.com/science/article/pii/S1574119221000481
- https://www.sciencedirect.com/science/article/pii/S2210537923000744
- https://android-developers.googleblog.com/2025/03/media-processing-performance-jetpack-media3-transformer.html
- https://www.youtube.com/watch?v=7vmiYP4vNUE

### Dependency changelogs and documentation

- https://developer.android.com/media/media3/transformer
- https://developer.android.com/media/media3/transformer/transformations
- https://developer.android.com/media/media3/transformer/composition
- https://developer.android.com/jetpack/androidx/releases/media3
- https://developer.android.com/jetpack/androidx/releases/work
- https://developer.android.com/jetpack/androidx/releases/room
- https://coil-kt.github.io/coil/changelog/
- https://github.com/square/retrofit/blob/trunk/CHANGELOG.md
- https://github.com/square/okhttp/blob/master/CHANGELOG.md
- https://github.com/google/dagger/releases

### Security advisories

- https://github.com/advisories
- https://github.com/yt-dlp/yt-dlp/security
- https://github.com/yt-dlp/yt-dlp/security/advisories/GHSA-6v4j-43gg-vj32
- https://github.com/googlesamples/mlkit/issues/1017

## Open Questions

- **Owner decision, time-boxed:** Register `com.chloemlla.aura` for Android developer verification before the 2026-09-30 first-country enforcement, or rely on the advanced flow/ADB? Tracked in Roadmap_Blocked.md; the window is now ~6 weeks.
- **Needs live validation:** Does Android's dynamic-color engine re-trigger reliably on Aura's streaming apply path (Paperize #588 class)? Requires a device pass when the `WallpaperColors` item lands.
- **Needs live validation:** Does the current full and FOSS release-minified build reproduce issue #2 on API 26, API 27, or API 29 after the present desugaring and NewPipe changes?
- **Needs live validation:** Does the exact ML Kit beta crash in Aura on API 36, and if so which of `SmartCropDetector`, `DepthPortraitComposer`, and `ParallaxWallpaperService` can trigger it on affected hardware?
