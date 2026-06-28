# Research - Aura

## Executive Summary

Aura is a mature Android personalization app (v6.34.3, 209 production Kotlin files, 100 JVM test files, 2 instrumented test files) that combines static wallpapers, video/live wallpapers, ringtones, sounds, local editing, scheduled rotation, community voting, and backup/transfer in one ad-free package. The strongest current shape is still "open-source Zedge without ads or subscription pressure," but recent commits moved the project into a more serious trust/distribution phase: local-only builds replaced GitHub Actions, Settings was decomposed, whole-library backup landed, Lemmy and 24H wallpaper packs were added, and accessibility tests started targeting real Aura screens. Highest-value direction: make the local release/tooling story true, finish localization/accessibility, then improve large-screen/offline local library workflows without adding accounts or cloud lock-in.

Top opportunities in priority order:
- Repair local-only release policy gates after workflow removal.
- Finish Compose string extraction and localization gates already tracked in `ROADMAP.md`.
- Replace remaining synthetic accessibility checks with real screen checks already tracked in `ROADMAP.md`.
- Add adaptive tablet/foldable layouts before the blocked Android 17 device smoke pass.
- Build the unified local Library hub and universal on-device search already tracked in `ROADMAP.md`.
- Add source health/retry diagnostics so provider failures degrade visibly.
- Polish Android Photo Picker imports with embedded/customized picker behavior where supported.
- Continue the FOSS flavor boundary and local Play-ready AAB lane already tracked in `ROADMAP.md`.

## Product Map

- Core workflows: browse/search/apply wallpapers, video wallpapers, ringtones, notification sounds, alarms, community uploads, favorites, downloads, collections, local imports, backups, diagnostics, and live-wallpaper effects.
- User personas: ad-free Zedge replacement seekers, privacy-focused Android users, AMOLED/live-wallpaper users, ringtone/sound users, automation users, community contributors, and de-Googled/F-Droid-adjacent users.
- Platforms and distribution: Android minSdk 26, targetSdk 35, package `com.freevibe`; GitHub Releases and Obtainium are primary, IzzyOnDroid is plausible, F-Droid is blocked until Firebase/Play Services are isolated.
- Key integrations and data flows: Wallhaven, Pexels, Pixabay, Bing, YouTube via NewPipe/yt-dlp, Lemmy, Wikipedia/NASA-style daily sources, Open-Meteo, Firebase RTDB/Storage/Functions/App Check, Room, DataStore, SAF, MediaStore, WorkManager, Glance, Media3, ML Kit, FFmpeg, and local JSON export/import.

## Competitive Landscape

- Zedge: broad wallpapers/ringtones/content marketplace and AI ringtone/content pushes. Aura should learn from the single discovery surface across visual and sound media, but avoid ads, credits, subscription pressure, creator monetization complexity, and low-signal search.
- Paperize: offline-first wallpaper rotation, folder albums, and broad local format support. Aura should learn from zero-permission local albums and no-repeat rotation behavior; avoid becoming wallpaper-only or dropping older Android support.
- WallYou: privacy-first multi-source wallpaper aggregation with active releases and community translation workflow. Aura should learn from rapid source iteration, Weblate-style translation readiness, and simple Material 3 source controls; avoid scraping brittle providers as first-party sources.
- Muzei: the canonical Android live-wallpaper source/plugin model. Aura should learn from its source API, artwork provider boundaries, and dim/reveal behavior; avoid release-cadence gaps and an art-only product boundary.
- Peristyle: fast-moving local wallpaper manager with tags, folder support, effects, and Tasker-facing automation. Aura should learn from user-visible local organization and external automation polish; avoid device-specific auto-wallpaper desync.
- Wallpaper Engine: strongest commercial live-wallpaper product with playlists, per-wallpaper behavior, FPS controls, and desktop-to-mobile transfer. Aura should learn from battery-facing live-wallpaper controls and portable packs; avoid desktop dependency and heavy 3D formats.
- Vanderwaals: on-device taste learning for wallpaper ranking. Aura should learn from local preference signals and resettable personalization; avoid opaque model dependencies until the existing deterministic ranking and FOSS boundary are stable.
- Noice/Ringdroid/RandTune: sound-specific apps prove demand for recording, editing, scheduled/randomized sounds, and sleep/ambient niches. Aura should use recording/editor/randomizer lessons while avoiding a full ambient mixer that duplicates a well-served app category.

## Security, Privacy, and Reliability

- Verified: `.github/workflows` is gone, but release, privacy, accessibility, background-work, SBOM, and GitHub-security docs/tools still reference `.github/workflows/*.yml` (`README.md`, `docs/distribution/release-dry-run.md`, `docs/distribution/supply-chain.md`, `tools/*workflow*_check.py`, `tools/accessibility_release_gate_check.py`). This can make local verification fail after the local-build-only policy change.
- Verified: `README.md` still says tag releases use repository secrets and GitHub workflow artifacts even though `ROADMAP.md` says workflows were removed.
- Verified: `app/build.gradle.kts` stays on compileSdk/targetSdk 35. Google Play target requirements and Android 16/17 APIs make the existing N-1/toolchain gate a release-planning risk rather than a cosmetic upgrade.
- Verified: live-wallpaper XML files contain TODOs for Android 16 `WallpaperDescription`/instance APIs, correctly blocked in `Roadmap_Blocked.md` until compileSdk 36+.
- Verified: Photo Picker compatibility shims exist in `AuraPickVisualMedia.kt`, `PhotoPickerCustomization.kt`, `WallpapersScreen.kt`, and `CollectionsScreen.kt`; direct Android 17 APIs remain blocked, but import UX can still be improved around current scoped picker flows.
- Verified: high-risk media dependencies are actively tracked: NewPipeExtractor v0.26.3, youtubedl-android 0.18.1, FFmpeg/native notices, and CVE-constrained transitive Jackson/Commons IO overrides.
- Missing guardrail: no single local source-health surface summarizes provider failures, last success, retries, disabled provider state, and offline fallback. `SourceMetrics.kt` exists, but the diagnostics surface is not a user-facing recovery console.

## Architecture Assessment

- Settings decomposition succeeded: `SettingsScreen.kt` is now 498 lines and section files own feature slices. Remaining hotspots are browse surfaces: `WallpapersScreen.kt` 2033 lines, `SoundsScreen.kt` 1977, `WallpaperDetailScreen.kt` 1397, `SoundsViewModel.kt` 1247, `VideoWallpapersScreen.kt` 1243, and `VideoWallpapersViewModel.kt` 931.
- `ROADMAP.md` correctly tracks the next decomposition work for `WallpapersViewModel.kt` and Sounds modules; add UI-screen decomposition only after those state boundaries are stable.
- `WindowSizeClass`, `NavigationSuiteScaffold`, and `ListDetailPaneScaffold` are absent from app code. Large-screen/adaptive implementation can start now; the blocked device smoke pass should validate it later.
- Localization remains mixed. Many screens now use resources, but `SoundsScreen.kt` still has user-visible literal strings and the active roadmap item should stay high priority until the hardcoded-string gate is clean.
- Testing is broad for JVM contracts and Firebase/tool policies, but instrumented coverage is thin and physical-device validation remains blocked for baseline profile, Android 17 audio, adaptive layouts, widget/live-wallpaper a11y, and background scheduler evidence.
- Distribution docs and checks need a local-release architecture update: workflow-era docs, JSON policies, and validators should be converted to local command receipts and GitHub Release upload steps.

## Rejected Ideas

- Zedge-style ads, credits, subscription pressure, or creator payouts: rejected because Aura's product value is ad-free local-first personalization.
- Zedge/WallpaperCave scraping as a first-party source: rejected because API-less scraping is brittle and policy-risky; source plugins are a better future boundary.
- Full KLWP/Wallpaper Engine scripting: rejected because Aura is a curated personalization app, not a general live-wallpaper authoring environment.
- Cloud album sync as a core feature: rejected because SAF, Photo Picker, local folders, and account-free export/import fit the repo philosophy better.
- Ambient sleep-sound mixer: rejected because Noice already owns that niche and Aura's unique value is cross-media personalization.
- Per-contact notification sounds: rejected because Android notification channels are app-owned; Aura can set ringtones but cannot globally override every messaging app's per-contact notification behavior.
- Adding duplicate N-1, Firebase Console, physical-device, plugin ABI, or WallpaperDescription items: rejected because they already belong in `Roadmap_Blocked.md`.

## Sources

OSS competitors:
- https://github.com/Anthonyy232/Paperize
- https://github.com/you-apps/WallYou
- https://github.com/muzei/muzei
- https://github.com/Hamza417/Peristyle
- https://github.com/ammargitham/WallFlow
- https://github.com/patzly/doodle-android
- https://github.com/avinaxhroy/Vanderwaals
- https://github.com/markusfisch/ShaderEditor
- https://github.com/TeamNewPipe/NewPipeExtractor/releases
- https://github.com/JunkFood02/youtubedl-android/releases

Commercial and community signal:
- https://www.zedge.net/
- https://www.pissedconsumer.com/company/zedge/customer-service.html
- https://www.wallpaperengine.io/android/en
- https://help.wallpaperengine.io/en/mobile/pairing.html
- https://dontkillmyapp.com/
- https://f-droid.org/en/packages/com.github.ashutoshgngwr.noice/

Platform and dependencies:
- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/about/versions/16/features
- https://developer.android.com/about/versions/17/changes/bg-audio
- https://developer.android.com/reference/android/app/wallpaper/WallpaperDescription
- https://developer.android.com/training/data-storage/shared/photopicker
- https://developer.android.com/jetpack/androidx/releases/room
- https://developer.android.com/build/releases/agp-preview
- https://developer.android.com/jetpack/androidx/releases/media3
- https://coil-kt.github.io/coil/upgrading_to_coil3/
- https://firebase.google.com/support/release-notes/android

Security:
- https://osv.dev/vulnerability/CVE-2024-47554
- https://nvd.nist.gov/vuln/detail/CVE-2024-47554
- https://osv.dev/vulnerability/CVE-2021-29425
- https://nvd.nist.gov/vuln/detail/CVE-2021-29425

## Open Questions

- Should local release evidence replace artifact attestations with signed local receipts, GitHub Release checksums only, or a local provenance format?
- Should adaptive layouts preserve five bottom-nav tabs on tablets or switch to navigation rail plus list-detail panes?
- Should source health diagnostics live in Settings > Diagnostics only, or also surface inline on each media tab?
