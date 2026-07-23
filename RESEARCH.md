# Research — Aura
Date: 2026-07-22 — replaces all prior research (previous pass: 2026-07-16, same v6.36.0 tree).

## Executive Summary
Aura (`com.freevibe`, v6.36.0 / versionCode 136) is a mature, privacy-forward open-source
Zedge alternative: multi-source wallpapers (Reddit-RSS-first, Wallhaven/Pexels/Pixabay/Bing
opt-in), video + weather/AGSL live wallpapers, ML-Kit parallax/depth, YouTube-first
ringtone/notification/alarm editing, scheduled rotation (interval + unlock/screen-off
triggers + metered-aware), community uploads (Firebase, full flavor), and local
backup/import — no ads, no required account. Kotlin 2.1.0 / AGP 8.7.3 / Compose M3, foss +
full flavors, local builds only. The codebase is clean and heavily audited (0 TODO/FIXME in
main source, 123 unit + 72 Python contract gates).

Its defensible wedge is a combination no single OSS project spans: WallYou-class source
breadth + Paperize-class scheduling + UndeadWallpaper-class video engine + Ringdroid-class
audio trimming, all account-free and ad-free. The 2026-07-16 pass and prior audits already
shipped nearly all table-stakes and common asks. Because the ROADMAP is drained and the
codebase is current-minus-one-major on tooling, **the highest-value direction now is
low-risk dependency modernization that reinforces the battery/RAM wedge, plus finishing the
two remaining god-object refactors** — not new features, most of which would strain the very
"no battery drain / no privacy surface" promise users switch to Aura for.

Top opportunities, priority order (all verified actionable at compileSdk 35 unless noted):
1. Coil 3.2.0 → 3.5.0 + enable background memory-cache capping (`memoryCacheMaxSizePercentWhileInBackground`) — direct background-RAM win for a wallpaper app.
2. Media3 1.8.0 → 1.10.1 + `experimentalSetDynamicSchedulingEnabled()` — power-efficient video-wallpaper playback loop.
3. Split `VideoWallpapersViewModel` (1318 lines) and trim `SettingsViewModel` (960) per the established delegate pattern — the last two god-objects.
4. Robolectric WorkManager rotation-reliability harness (reboot re-arm, doze deferral) — closes the biggest test gap without a device; pre-empts the failure class that dominates WallYou's tracker.
5. Validate user-entered subreddit names in community feed config (currently unchecked).
6. Decide the fate of the 4 orphaned legacy sound repositories (wire as opt-in sources or delete).
7. Parity source breadth: Microsoft Spotlight + Picsum "picture of the day" (trivial free APIs).
8. Clock/date wallpaper overlay (Paperize #533) — niche, opt-in.
9. UX-quality fixes (2026-07-22 screen audit): label + add semantics to load-more spinners;
   add undo to hide/downvote; make partial-load errors persistent with retry; settings search.

## Product Map
- Core workflows: browse/search/apply wallpapers (Reddit-first, others opt-in); apply video &
  live/weather/AGSL wallpapers with parallax/depth; browse + trim/fade/normalize/convert
  YouTube-sourced ringtones/notifications/alarms with per-contact assignment; schedule
  rotation (interval, unlock/screen-off triggers, metered-aware, darken); export/import local
  library; community upload/vote/moderate (full flavor only).
- Personas: privacy-minded Zedge refugees; power users scheduling rotation + Tasker
  automation (ROTATE_NOW/SHUFFLE_NOW broadcasts); creators/moderators; the maintainer shipping
  local signed releases to GitHub/Obtainium (IzzyOnDroid candidate via foss flavor).
- Platforms/distribution: single Android app (minSdk 26, targetSdk/compileSdk 35), full
  (Firebase/ML Kit) + foss flavors; local builds only (GitHub Actions removed 2026-06-26);
  F-Droid mainline blocked by Firebase in the full flavor.
- Key integrations: ~14 remote providers (docs/security/network-endpoints.json), NewPipe
  Extractor + youtubedl-android (yt-dlp 2026.07.04, bgutil PO-token provider), Media3 1.8.0,
  Room 2.7.2 schema v14, WorkManager 2.11.2, Glance 1.2.0-rc01 widget, Open-Meteo, Stability
  AI (optional key).

## Competitive Landscape
- **WallYou** (you-apps, v15.2 2026-06-19, active): widest OSS source coverage — 15 sources
  incl. a Zedge API source, NASA/Wikipedia/Bing/Spotlight/Pixel "picture of the day", Lemmy.
  Learn: breadth via trivial free POTD APIs. Avoid: its auto-changer reliability failures
  dominate the tracker (reboot persistence #230, changer stalls #266/#239) — Aura already
  guards these (RotationTriggerRecovery), so keep them regression-tested.
- **Paperize** (Anthonyy232, code active 2026-07-16 but release v3.2.1 stale): strongest
  auto-changer. Open asks Aura hasn't done: Google Photos source #531, per-app suppression
  #444, clock overlay #533 (theme-reactive swap #516 is already parity — Aura matches system
  light/dark). Avoid: shipping a build that regresses below a known-good (#521 "v3.2.1 is
  better") — pin a known-good.
- **Muzei** (romannurik, feature-frozen since API 3.4.2 2024-06): reference extensible
  source-plugin API + multi-source blending. Aura's Discover already blends enabled providers;
  the plugin ABI is Aura's NX-5 (blocked). Learn: don't let flagship requests rot a decade.
- **Peristyle** (Hamza417, v9.7.3 2026-07-18, exemplary): non-destructive real-time lossless
  filter editing + first-class local tags. Learn: lossless in-place filter edits. Avoid: its
  local-only scope — exactly the discovery gap Aura fills.
- **UndeadWallpaper** (maocide, v1.3.6 2026-06-22, active): current best FOSS video-wallpaper
  engine — OpenGL+ExoPlayer, gapless batched playlist, scroll parallax, per-video
  zoom/speed/volume, doze/sleep recovery. Reference architecture if Aura's engine needs
  hardening (aligns with the blocked device-verified BufferQueue work).
- **althafvly/ringdroid** (v3.0.1 2026-05-16, active fork; google/ringdroid archived):
  reference for a scoped-storage-safe waveform trimmer + RingtoneManager assignment. Aura
  already ships this; use as a correctness reference, not a target.
- **Commercial (Zedge/Walli/Backdrops/Wallpaper Engine)**: every 2025-2026 review channel
  screams ads, credits, and "AI slop"; Wallpaper Engine mobile leaks NSFW into "all ages"
  and can't browse Workshop on-device. This is Aura's entire wedge — guard it; every cloud/
  motion/AI feature added must be explicit opt-in with visible battery+data cost.

## Security, Privacy, and Reliability
- Verified (no new vulnerabilities found this pass): jackson-databind is pinned to 2.18.9 in
  `app/build.gradle.kts:184` (yt-dlp's transitive 2.11.1 is the reason; 2.18.9 clears
  CVE-2026-54512/54513/54515). yt-dlp 2026.07.04 (CVE-2026-55404 `--write-link` fix) is
  vendored with a SHA-256 floor gate.
- Verified: metered-data safety is real — `AutoWallpaperWorker.kt:333-335`
  (`requiresWiFiOnly -> NetworkType.UNMETERED`) plus a PreferencesManager "hold rotation until
  Wi-Fi/unmetered" pref; diagnostics surface deferral reasons
  (`BackgroundWorkDiagnosticsReader.kt`). Earlier research flagged this as a gap — it is NOT.
- Verified: rotation triggers (`RotationTriggerService.kt`, `RotationTriggerRecovery.kt`)
  cover unlock/screen-off with a recovery re-arm path; the Android 12+ silent-death case was
  fixed in v6.36.0 (commit c09f620). An earlier "screen-off trigger" gap is NOT a gap.
- Reliability risk (Likely): user-configurable community feed subreddits (added v6.36.0, in
  Settings) are stored without name-format/existence validation — a malformed entry silently
  yields an empty feed. Reddit config path off `SoundsViewModel`/Settings.
- Missing guardrail (Likely): instrumentation coverage is thin (2 `app/src/androidTest`
  files); there is no automated test that rotation re-arms after reboot or defers under doze.
  Much of this is testable at the Robolectric level via `WorkManagerTestInitHelper` +
  `TestDriver` without a device, and would guard the exact failure class WallYou bleeds on.
- Systemic hazard patterns (documented in CLAUDE.md, mitigations exist but not universalized):
  timeouts recorded as success in `SourceMetrics` (throw inside the measure block);
  `lastApplied*Uri` must be written by every system-sound-apply path or the boot receiver
  stomps it; cancelled load jobs' `finally { isLoading=false }` clobbering their replacement
  (guard `if (loadJob === thisJob)`); delegate-split ViewModels losing loadJob ownership.
  New feature code must follow these — worth a contract-gate test as splits proceed.

## Architecture Assessment
- Remaining god-objects (refactor candidates, verified line counts): UI screens are large but
  delegated (`WallpapersScreen` 1857, `SoundsScreen` 1832, `VideoWallpapersScreen` 1593). The
  two ViewModels that still violate the CLAUDE.md <500-line delegate pattern are
  `VideoWallpapersViewModel.kt` (1318) and `SettingsViewModel.kt` (960). These are the correct
  next splits; the established pattern (`SoundBrowseViewModel`, `WallpaperSearchActions` +
  matching `test/tools/*_split_test.py` gate) is the template.
- Dead/orphaned code: `FreesoundRepository`, `AudiusRepository`, `CcMixterRepository`,
  `SoundCloudRepository` are not DI-wired and not called by any active UI (only self-package
  references). CLAUDE.md keeps them "for future compatibility." This is a standing decision to
  resolve: either re-wire them as opt-in sources (the WallYou breadth signal supports it) or
  delete them (they are latent maintenance + a false sense of source coverage).
- Tooling is current-minus-one-major; migrating the AGP 8.7.3 → 9.x jump is the single
  riskiest step in the stack (Gradle 9.5, R8, config-cache, built-in Kotlin). A safe
  intermediate to upgrade to targets Kotlin 2.4.10 + AGP 8.13.x + Gradle 8.x (which per
  release notes requires Kotlin ≤ 2.3, R8 8.13.19) and would migrate to compileSdk 36 without
  crossing to AGP 9 — which requires the full N-1 migration and would unblock the N-1-gated
  OkHttp and Room upgrades. This refines the blocked N-1 item; stage the toolchain bump on its
  own branch so a build regression is unambiguously attributable.
- Low-risk, non-toolchain dependency wins available NOW at compileSdk 35 (all additive):
  Coil 3.2.0 → 3.5.0 (which requires minSdk 23; adds background memory-cache capping +
  in-flight request dedupe), Media3 1.8.0 → 1.10.1 (Compose player composables, dynamic
  scheduling). Each must pass `:app:checkFullDebugAarMetadata` at compileSdk 35 — if a target
  requires 36 it moves under the blocked N-1 gate (this is how OkHttp, which needs the upgrade
  to compileSdk 36, was already found blocked until that toolchain migration).
- i18n: single `values/` dir + generated pseudolocales (`en_XA`, `ar_XB`) with Roborazzi
  goldens and a release gate; real locales are intentionally deferred (CLAUDE.md). VM-layer
  feedback i18n is already covered by prior work. No action beyond keeping the gate green.
- Test/docs gaps: unit + contract-gate coverage is strong; instrumentation and on-device
  playback/background-work coverage is the weak axis (much already tracked as device-blocked).

### UX & State Handling (2026-07-22 screen audit)
Overall UX quality is high — most lists have explicit empty/loading/error composables
(`AuraStateCard`/`WallpaperStateCard` + shimmer), a real 6-step onboarding
(`ui/screens/onboarding/OnboardingScreen.kt`), centralized strings, and broad accessibility
(`contentDescription`, `heading()`, `liveRegion`, 48dp targets, waveform semantics). Verified
residual gaps worth fixing:
- Load-more pagination shows a bare `CircularProgressIndicator` with no text and no semantics
  (`WallpapersScreen.kt:1169`, `SoundsScreen.kt:~997`, `VideoWallpapersScreen.kt:~627`) — a
  screen reader announces nothing and sighted users can't distinguish "fetching" from "hung".
- Hide/downvote fires immediately with no undo (`SoundsScreen.kt:693`,
  `VideoWallpapersScreen.kt:1295-1299`), unlike delete-favorite which has a snackbar undo
  (`FavoritesScreen.kt:152-177`). A reversible soft-hide should offer undo, not vanish silently.
- Partial-load errors are transient: when a list already has items and a refresh fails, the
  error surfaces only as a disappearing banner (`WallpapersScreen.kt:296-305`,
  `SoundsScreen.kt:381-389`) with no lingering retry affordance.
- Settings has no search and 10+ domain sections; Backup, Diagnostics, and API-key management
  are scroll-buried despite existing section anchors (`SettingsSectionNavigation.kt`).
- "Removed from collection" shows a toast (`CollectionsScreen.kt:421`) but no undo, unlike the
  favorite-removal path — a small consistency gap.
- Not gaps (verified handled): Downloads has an explicit empty state
  (`DownloadsScreen.kt:116-121`); metered/wifi-only and screen-off rotation triggers exist
  (see above).

## Rejected Ideas
- Screen-off rotation trigger as new work (Paperize #126) — already shipped
  (`RotationTriggerService`); not a gap.
- Wifi-only/metered download awareness as new work (Muzei #97) — already shipped
  (`AutoWallpaperWorker` UNMETERED constraint).
- Theme-reactive dark/light wallpaper swap as new work (Paperize #516) — Aura already matches
  system light/dark.
- Music-reactive / now-playing wallpaper (Muzei #128) — reintroduces continuous
  audio-session listening + battery/privacy surface that contradicts the wedge. Rejected
  unless demand hardens; the app should not listen to media sessions by default.
- Google Photos album as a source (Paperize #531) — requires the Google Photos Library API
  (OAuth, Google review, non-free), contradicting the no-account/foss-flavor philosophy.
  Device-folder-as-source is the privacy-preserving substitute (verify local import already
  covers it before proposing anything new).
- Muzei-compatible plugin ABI now (Muzei #367/#368) — already parked as blocked NX-5 behind
  the toolchain gate.
- On-device TFLite smart-crop (WallFlow Plus) — Aura already has ML-Kit segmentation
  (parallax/depth); extending it to crop is a low-priority enhancement, not a gap, and leans
  against the battery wedge. Under consideration only.
- Real translation packs / Weblate — deferred by CLAUDE.md until human language review is
  planned; pseudolocale/RTL gates are the correct current state.
- Zedge-as-a-source scraping (WallYou ships it) — bot-block arms race + takedown exposure for
  the app positioned as the clean Zedge alternative.

## Sources
### OSS competitors / analogous projects
- https://github.com/ammargitham/WallFlow/issues
- https://github.com/Hamza417/Peristyle
- https://github.com/Anthonyy232/Paperize/issues
- https://github.com/you-apps/WallYou/issues
- https://github.com/muzei/muzei/issues
- https://github.com/maocide/UndeadWallpaper
- https://github.com/althafvly/ringdroid
### Dependency / platform
- https://developer.android.com/jetpack/androidx/releases/media3
- https://android-developers.googleblog.com/2026/03/media3-110-is-out.html
- https://coil-kt.github.io/coil/changelog/
- https://github.com/square/okhttp/blob/master/CHANGELOG.md
- https://developer.android.com/jetpack/androidx/releases/room
- https://developer.android.com/jetpack/androidx/releases/glance
- https://developer.android.com/develop/ui/compose/bom/bom-mapping
- https://developer.android.com/build/releases/gradle-plugin
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/reference/android/app/wallpaper/WallpaperDescription
- https://developer.android.com/about/versions/17/features
- https://developer.android.com/developer-verification
- https://github.com/yt-dlp/yt-dlp/issues/13037
### Commercial / community signal
- https://unstar.app/blog/zedge-walli-backdrops-vellum-wlppr-wallpaper-apps-ranked-2026
- https://checkthat.ai/brands/wallpaper-engine/reviews
- https://www.nerdoutonbusiness.com/p/the-wallpaper-app-that-can-t-break-its-revenue-ceiling
- https://f-droid.org/packages/net.redwarp.gifwallpaper/
- https://news.ycombinator.com/item?id=41308038

## Open Questions
- Does Media3 1.10.1 / Coil 3.5.0 publish AAR metadata compatible with compileSdk 35, or do
  they now require 36 (like OkHttp 5.4)? Resolvable only by running
  `:app:checkFullDebugAarMetadata` — the deciding gate for whether items 1-2 are actionable
  now or move under N-1.
- Product decision (needs owner judgment): keep, re-wire, or delete the four orphaned legacy
  sound repositories. Affects source-breadth positioning vs. maintenance surface.
