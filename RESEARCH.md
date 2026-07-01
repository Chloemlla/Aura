# Research - Aura

## Executive Summary
Aura is a mature Android personalization app (Verified: Kotlin/Compose Android app, minSdk 26, targetSdk 35, v6.34.6 in `app/build.gradle.kts`) combining wallpapers, video/live wallpapers, sounds/ringtones, editors, scheduled rotation, community uploads, local backup, and diagnostics without ads or required accounts. Its strongest current shape is a privacy-forward open-source Zedge alternative with unusually broad media coverage; the highest-value direction is to harden trust and recovery surfaces before expanding novelty features. Top opportunities, in priority order: patch Firebase Functions production dependency advisories; harden optional provider-key storage; purge stale workflow-era release docs; add OEM battery/recovery guidance to diagnostics; add pseudolocale/RTL release gates; normalize provider rate-limit/backoff behavior; continue the existing adaptive shell, source-health console, Photo Picker, FOSS flavor, universal search, and ViewModel split roadmap items.

## Product Map
- Core workflows: browse/search/apply wallpapers; browse/apply video/live wallpapers; search/preview/edit/apply ringtones, notifications, and alarms; import/export local library data; diagnose provider, crash, background-work, and automation failures.
- User personas: privacy-minded Android users replacing ad-heavy wallpaper/ringtone apps; power users who schedule local/remote rotation; creators/moderators using community upload/report flows; maintainers shipping signed local releases to GitHub/Obtainium/Izzy.
- Platforms and distribution: Android app with Compose Material 3, Hilt, Room, WorkManager, Media3, Glance, Firebase, NewPipe, yt-dlp; signed local APK/AAB release lane; GitHub Releases and Obtainium supported; Izzy candidate; F-Droid mainline blocked by non-free Firebase/Google/ML Kit dependencies.
- Key integrations and data flows: Wallhaven, Bing, Pexels, Pixabay, Reddit, NASA APOD, Wikimedia POTD, Lemmy, Openverse/Freesound/SoundCloud/Audius/ccMixter, Open-Meteo, Stability AI, YouTube/NewPipe/yt-dlp, Firebase Auth/RTDB/Storage/Functions/App Check, Room, DataStore, MediaStore/SAF, WorkManager, and local JSON backup/import.

## Competitive Landscape
- Zedge: does massive cross-media catalog, ringtones, live wallpapers, AI creation, stickers, and rotation well. Aura should learn from unified search/catalog depth and creator flows; avoid ads, subscriptions, tracking pressure, and opaque monetization.
- Wall You: does MD3 multi-source wallpaper browsing, automatic changer, favorites, history, filters, F-Droid/Izzy distribution, and Weblate translation well. Aura should learn from lightweight source aggregation and translation operations; avoid accepting non-free-source ambiguity without clear anti-feature disclosures.
- Peristyle: does local-first folders, tags, lossless filters, auto wallpaper per screen, live-wallpaper picker, external automation trigger, no tracking, and reproducible-build positioning well. Aura should learn from local folder/tag ergonomics and automation copy; avoid shrinking into wallpaper-only simplicity because Aura's sound/video breadth is its differentiator.
- WallFlow: does Wallhaven/Reddit, tablets/wide screens, saved searches, local wallpapers, auto changer, history, and optional on-device smart crop well. Aura should learn from multi-pane tablet support and saved-search automation; avoid Plus-style fragmentation unless a FOSS/full flavor boundary is required for distribution.
- Paperize: does fully offline dynamic wallpaper rotation, local folders, home/lock/both targeting, AVIF/WebP-style media breadth, and on-device storage well. Aura should learn from account-free local rotation and import clarity; avoid becoming local-only because remote providers are core to Aura's value.
- Muzei: does live-wallpaper rotation, dim/reveal behavior, gallery/photo sources, and the canonical Android source/plugin mental model well. Aura should learn from source boundaries and calm live-wallpaper presentation; keep the plugin ABI blocked until the existing N-1/toolchain gate is resolved.
- Wallpaper Engine / Tapet / Shader Wallpaper: show demand for playlists, local video/GIF import, generated wallpapers, shader previews, and power controls. Aura should learn from preview-first live wallpaper controls; avoid executable/workshop-style user content and untrusted shader input.
- Noice / Ringdroid: adjacent sound apps show users value soundscape mixing, waveform editing, local recording, and explicit ringtone/alarm/notification export. Aura should deepen its existing sound editor and profile flows; avoid bloating into a general DAW or meditation app.

## Security, Privacy, and Reliability
- Verified: `npm --prefix functions audit --omit=dev` reports production dependency advisories in Firebase Functions: high `form-data` CRLF injection plus moderate `protobufjs` and `uuid` issues. `functions/package.json` already targets Node 22, so the Firebase Admin v14 path is plausible but must handle breaking ESM/legacy namespace removals.
- Verified: optional provider keys and the paid Stability AI key are sanitized then stored in app-private DataStore (`app/src/main/java/com/freevibe/data/local/PreferencesManager.kt`, `docs/security/provider-credential-storage.json`). Backup rules exclude the DataStore file, but at-rest protection remains `appPrivateDataStoreNoKeystore`.
- Verified: `app/src/main/res/xml/backup_rules.xml` and `data_extraction_rules.xml` exclude Room DB, DataStore, offline/generated media, community identity/votes, and live-wallpaper files from cloud backup and device transfer, matching Android backup guidance for sensitive/large/device-specific data.
- Verified: top-level and distribution docs still contain workflow-era GitHub Actions/repository-secret release references while recent commits removed workflows and stabilized local release gates.
- Verified: `SourceMetrics.kt`, `SettingsDiagnosticsSection.kt`, and `BackgroundWorkDiagnosticsReader.kt` expose useful failure state, but recovery remains split across provider rows, support bundles, and background-work dialogs; OEM battery restrictions still need user-facing brand guidance.
- Missing guardrails: no committed production dependency audit gate for `functions`; no encrypted migration for user-entered provider secrets; no pseudolocale/RTL gate despite all visible strings now being centralized.
- Recovery and rollback needs: Functions upgrades need emulator/callable regression tests before deploy; provider-key encryption needs one-way migration with corrupt-keystore fallback; background diagnostics need explicit "what to do next" copy for common OEM/Data Saver/WorkManager states.

## Architecture Assessment
- Module boundaries: `WallpapersScreen.kt` (~1968 lines), `SoundsScreen.kt` (~1904), and `SoundsViewModel.kt` (~1133) remain too large; existing roadmap split items are still correct and should stay ahead of feature growth.
- Adaptive UI: `WindowSizeClass`, `NavigationSuiteScaffold`, `NavigationRail`, and `ListDetailPaneScaffold` are absent from app source. The existing adaptive-shell roadmap item is evidence-backed by Android's current adaptive app guidance and WallFlow's tablet support.
- Localization: only `app/src/main/res/values` exists; no `values-*` resources or pseudolocale build config are present. The hardcoded-string baseline is empty, so pseudolocale/RTL testing is the next correct step before real translation work.
- Distribution: `docs/distribution/alt-store-metadata.json` correctly marks F-Droid mainline blocked by Firebase/Google Services/Play Services ML Kit, and `Roadmap_Blocked.md` already holds N-1/plugin/API-37/device/Firebase-console blockers. Do not duplicate those in the active roadmap.
- Testing gaps: automated JVM and Python gates are broad, and accessibility fixtures now cover real routes, but only two `app/src/androidTest/java` files exist and physical-device/emulator work remains blocked. New security/dependency work should add local reproducible tests instead of depending on CI.
- Documentation gaps: README/ARCHITECTURE/CONTRIBUTING/distribution docs should describe local releases only, and source-of-truth release commands should not point maintainers back to deleted workflows.

## Rejected Ideas
- New plugin/source ABI now: rejected because `Roadmap_Blocked.md` already parks NX-5 behind N-1/toolchain work and Muzei parity does not justify bypassing that dependency.
- Direct Android 17 Photo Picker/WallpaperDescription/API 37 cleanup now: rejected because direct APIs are blocked by compileSdk/toolchain gates; keep current reflection bridges and existing Photo Picker roadmap item.
- F-Droid mainline submission now: rejected because `docs/distribution/alt-store-metadata.json` and existing roadmap already identify the FOSS flavor boundary as prerequisite.
- Zedge/WallpaperCave scraping as first-party sources: rejected because API-less scraping adds policy and reliability risk; provider plugins or reviewed APIs are safer.
- Account-based favorites sync: rejected because it conflicts with Aura's no-account default and is already blocked in `Roadmap_Blocked.md` as NX-7.
- Real translation packs/Weblate rollout now: rejected because no pseudolocale/RTL gate exists yet and translation requires human language review; add test readiness first.
- Duplicate adaptive shell, source health console, embedded Photo Picker, universal search, AGSL gallery, style learning, FOSS flavor, or ViewModel split items: rejected because they already exist in `ROADMAP.md`.

## Sources
### OSS and Adjacent Projects
- https://github.com/you-apps/WallYou
- https://github.com/Anthonyy232/Paperize
- https://github.com/Hamza417/Peristyle
- https://github.com/ammargitham/WallFlow
- https://github.com/muzei/muzei
- https://github.com/dimitris-nik/ShaderWallpaper
- https://github.com/google/ringdroid
- https://f-droid.org/en/packages/com.github.ashutoshgngwr.noice/

### Commercial and Community Signal
- https://play.google.com/store/apps/details?id=net.zedge.android
- https://www.wallpaperengine.io/android
- https://backdrops.io/
- https://play.google.com/store/apps/details?id=com.sharpregion.tapet
- https://www.reddit.com/r/fossdroid/comments/1fym2hz/open_source_wallpaper_changer_from_internal/
- https://dontkillmyapp.com/samsung

### Android Platform and Distribution
- https://developer.android.com/develop/ui/compose/build-adaptive-apps
- https://developer.android.com/jetpack/androidx/releases/compose-material3-adaptive
- https://developer.android.com/training/data-storage/shared/photo-picker/embedded
- https://developer.android.com/develop/background-work/background-tasks/persistent
- https://developer.android.com/jetpack/androidx/releases/work
- https://developer.android.com/identity/data/autobackup
- https://developer.android.com/guide/topics/resources/localization
- https://developer.android.com/guide/topics/resources/pseudolocales
- https://developer.android.com/develop/ui/compose/accessibility/testing
- https://f-droid.org/docs/Inclusion_Policy/

### Policy, Security, and Dependencies
- https://f-droid.org/en/docs/Anti-Features/
- https://izzyondroid.org/docs/general/AppInclusionPolicy/
- https://github.com/advisories/GHSA-hmw2-7cc7-3qxx
- https://github.com/protobufjs/protobuf.js/security/advisories/GHSA-f38q-mgvj-vph7
- https://github.com/advisories/GHSA-w5hq-g745-h8pq
- https://firebase.google.com/support/release-notes/admin/node

## Open Questions
None that block prioritization or implementation from public/local evidence.
