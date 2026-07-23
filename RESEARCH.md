# Research - Aura
Date: 2026-07-16 — replaces all prior research (previous pass: 2026-06, v6.34.6 era; most of its Now-tier items shipped in v6.34.x-v6.35.x).

## Executive Summary
Aura is a mature open-source Android personalization app (Kotlin 2.1.0/Compose M3, minSdk 26, targetSdk 35, v6.36.0 with Reddit-RSS-first wallpapers, immersive video paging, content-first Sounds, Reddit-only default sources, and metered-data warnings). Its wedge is real and current: every 2025-2026 Zedge review channel screams ads/credits/AI-slop, the closest OSS analog (WallFlow) is abandoned with its Reddit source broken since 2026-05, and no maintained OSS Compose ringtone editor exists. The highest-value direction is defense before novelty: (1) survive Google developer verification (Sept 2026 regional enforcement) which threatens the GitHub/Obtainium channel itself; (2) keep yt-dlp current after patching the Jackson floor; (3) harden the two fragile runtime pipelines — YouTube extraction (poToken/SABR arms race, breaks every 4-8 weeks) and the video-wallpaper decode path (device-verified BufferQueue storm) — then take the achievable-now dependency wins (Media3 1.8, Glance 1.2 previews, OkHttp 5.x) and the loudest demand features (time-of-day/theme-aware scheduling, OLED dark variants, user-added subreddits, sound-editor precision).

## Product Map
- Core workflows: browse/search/apply wallpapers (Reddit-first by default; Wallhaven/Pexels/Pixabay/Bing opt-in); video/live wallpapers (video, weather+AGSL shaders, ML-Kit parallax/depth); YouTube-first ringtones/notifications/alarms with trim/fade/normalize/convert and per-contact assignment; scheduled rotation (interval, unlock/screen-off triggers, darken); local library export/import; community uploads/votes/moderation (Firebase, full flavor only).
- Personas: privacy-minded Zedge refugees; power users scheduling rotation + Tasker automation; creators/moderators; the maintainer shipping local signed releases to GitHub/Obtainium (IzzyOnDroid candidate via foss flavor).
- Platforms/distribution: single Android app, full (Firebase/ML Kit) + foss flavors; local builds only (GitHub Actions removed 2026-06-26); F-Droid mainline blocked by Firebase in full flavor.
- Key integrations: 14 remote providers (docs/security/network-endpoints.json), NewPipe Extractor v0.26.3 + yt-dlp-android 0.18.1 (both current as of 2026-07), Media3 1.8.0, Room 2.7.2 with schema v14, WorkManager, Glance widget, Open-Meteo, Stability AI (optional key).

## Competitive Landscape
- Zedge (commercial, MWM portfolio): catalog depth + brand. Learn: unified search expectations, per-category sounds. Avoid: ads/credits/AI-slop flood — verified top complaints 2025-2026 (marlvel.ai review analysis, PissedConsumer billing threads). Aura's README should state the contrast plainly.
- WallFlow (abandoned 2024-08, Reddit source broken 2026-05, issue #113): direct evidence the Reddit+Wallhaven auto-changer niche is unserved. Harvest its orphaned asks: custom sources (#106), blur post-processing (#105), auto backup (#68). Avoid: single-maintainer Wallhaven coupling without provider health checks.
- Peristyle (v9.7.2, 2026-07, exemplary triage): learn — embedded no-key Wallhaven client, random-effects generator, per-apply crop toggle, reproducible-builds-as-release-gate. Avoid: nothing notable.
- Paperize (v4.0.0-alpha rewrite 2025-12): learn — screen-off-only swaps, landscape skip, theme-dependent wallpaper demand (#516), time-of-day scheduling demand (#447). Avoid: big-bang rewrite that wiped settings and regressed scheduling ("v3.2.1 is better", #521) — add any LWP-engine apply path incrementally (NX-1 is the gated vehicle).
- Muzei (modernizing, low feature velocity): learn — top-voted asks are multiple simultaneous sources (#367, +16), download-current (#669), 3D parallax (#649), pluggable effects (#368). Aura already covers most; the effects pipeline is the residual gap. Plugin ABI stays blocked (NX-5).
- UndeadWallpaper (new 2025-08, video LWP): learn — "silent decoder death recovery" and Doze IllegalStateException armor for MediaCodec under OEM battery killers; video-page parallax with intensity slider. Directly applicable to Aura's VideoWallpaperService and the device-verified buffer storm.
- Revived Ringdroid fork (F-Droid v3.0.1, 2026-05) + proprietary Play cutters: the editor feature bar is waveform zoom to ms precision, numeric trim entry, selectable fade curves, volume boost, export format/bitrate. No OSS Compose editor exists — Aura can own this.
- Iconify (root theming): learn — depth-wallpaper (subject segmentation) demand is loud (Samsung One UI community threads beg for lock-screen depth). Aura's DepthPortraitComposer is the non-root answer; lockscreen clock-tuck remains NX-2 (blocked).

## Security, Privacy, and Reliability
- P0 — jackson-databind 2.17.3 pin is vulnerable: CVE-2026-54512/54513 (RCE, affects >=2.10 <2.18.8) + CVE-2026-54515 (<2.18.9). The pin lives in app/build.gradle.kts constraints (added for yt-dlp's transitive 2.11.1). Raise to >=2.18.9. Verified (HeroDevs/SentinelOne advisories).
- P0 — yt-dlp 2026.07.04 patched CVE-2026-55404 (`--write-link` output injection). Aura now vendors that official asset with its published SHA-256 and rejects stable-channel updates below the floor; a live extraction/update smoke remains device-gated.
- P0 (distribution) — Google developer verification: enforcement 2026-09-30 in BR/ID/SG/TH, global 2027. Unregistered installs blocked on certified devices except ADB and the one-time "advanced flow". F-Droid calls it existential. Registering the existing self-signed cert identity is identity verification, NOT code signing (no signing change; the repo's no-code-signing rule is unaffected). Console registration is owner-gated; the actionable parts are install-path docs + a decision record in docs/distribution/developer-verification.md. Verified (Google blog 2026-03, help center, The New Stack).
- P1 — Device-verified (SM-S908U1/Android 16, 2026-07-16 session logcat): the video browse/immersive SurfaceView emitted a continuous BufferQueue dequeue-timeout storm (~30-50/s, 1,739/session, dequeuedCount 21) with QC2V4l2Codec "Failed to set resolution and buffer size"/"not a supported pixel format" config failures. The captured sequence identified PlayerView zoom resizing a decoded 1280x720 stream from a stable 1080x2316 surface to 4117x2316, while the feed and immersive players could own two surfaces concurrently. v6.36.0 now keeps fixed view bounds, delegates crop scaling to Media3, and releases the feed player before opening immersive playback; a clean two-minute device log remains gated.
- P1 — YouTube extraction fragility: NewPipe's hotfix trail shows YouTube breaks extraction every 4-8 weeks; v0.26.3's SABR fix is declared temporary; poTokens are effectively mandatory for reliable yt-dlp video-bound requests. v6.36.0 now ships the reviewed bgutil provider plugin, an optional HTTPS provider path, explicit NewPipe-to-yt-dlp search/audio failover, and a user-visible both-engines-failed state. Live provider validation remains external-endpoint/device-gated. Verified (NewPipe/yt-dlp release notes, Po-Token guide).
- P1 — Persistence growth review: Reddit RSS cursor metadata now uses one atomically updated 64-entry rolling value and migrates away every legacy dynamic page key. Remaining: SoundFeedCache writes one SharedPreferences key per distinct search query, skipped-on-read but never deleted, and is manually constructed in SoundsViewModel.kt:69, defeating its @Singleton lock.
- P2 — Efficiency leftovers from the same review: the discover Reddit-throttle retry re-runs the full Wallhaven/Pixabay/Pexels/Bing fan-out (WallpaperBrowseViewModel.scheduleRedditRetry); the Reddit cache-hit path re-derives the pagination cursor from filtered media instead of the raw Atom tail (overlapping re-fetch); VideoPreviewCache.prebuffer runs uselessly on HLS manifests and Coil-rendered GIFs.
- Android 16 background policy (all apps, no compileSdk bump needed): job quotas tighten while an FGS runs or the app is TOP; abandoned JobParameters get STOP_REASON_TIMEOUT_ABANDONED with frequency penalties. Aura's audit now covers all WorkManager jobs, confirms there are no direct JobParameters owners or long-running workers, exposes WorkInfo stop reasons, and leaves only device capture blocked. Verified (Android 16 behavior-changes docs).
- ffmpeg: Aura uses yausername's bundled ffmpeg 0.18.1, not the retired FFmpegKit — but FFmpegKit's 2025 retirement (binaries pulled, CVEs unpatched) shows the pattern; the wrapper's bus factor is ~2 releases/yr.
- Recovery gaps: extraction failures surface as generic errors — Sounds needs an explicit "YouTube changed something, degraded mode" state; rotate-on-unlock silent death on Android 12+ is already a ROADMAP item.

## Architecture Assessment
- The 2026-07-15/16 overhaul shipped as v6.36.0 after a multi-pass audit and on-device QA; the CHANGELOG now keeps its discovery work separate from the v6.35.1 device-fix release.
- Dead code accumulating: topHits plumbing is permanently empty across 5 sounds files after the SoundTopHitsLoader deletion; RedditApi is still DI-provided (AppModule.kt:118) though RedditRepository now uses OkHttp+RedditRssParser; committed baseline profiles reference deleted fetchTopHits symbols (inert but stale).
- Boundaries are otherwise healthy post-split (500-line ViewModel gate, delegate pattern). Systemic hazard patterns are documented in CLAUDE.md (loadJob ownership, lastApplied*Uri, timeout-recorded-as-success).
- Test infra is strong (111 unit-test files, Roborazzi + pseudolocale goldens, 348 Python tool gates), but gates only catch what they encode — the 2026-07-16 session caught two gate-breaking omissions (deleted-file contract, endpoint doc sync) only via full-suite runs; always run the whole tools suite pre-commit.
- Dependency verification: gradle/verification-metadata.xml exists in the uncommitted tree (SHA-256, no PGP); confirm enablement semantics and add wrapper checksum validation to close the supply-chain loop.
- i18n: single values/ dir + generated pseudolocales; real locales remain intentionally deferred (CLAUDE.md gate note). VM-layer feedback i18n is already a ROADMAP item.

## Rejected Ideas
- Per-app notification sound assignment (community demand, One UI regression) — third-party apps cannot set other apps' notification-channel sounds without system/root privileges. Source: Google support thread 274798689.
- Zedge-as-a-source scraping (WallYou does it) — bot-block arms race + takedown exposure for the app positioned as the clean Zedge alternative; WallYou already carries that maintenance churn (v15.0 bypass).
- Expanding AI wallpaper generation — community signal runs the other way ("AI slop" is a top Zedge complaint); keep the existing optional Stability AI screen, add labeling/filtering instead.
- Big-bang live-wallpaper-engine rewrite as the rotation mechanism — Paperize v4 cautionary tale (settings wipe, "v3.2.1 is better" #521); NX-1 (blocked) is the incremental vehicle.
- Muzei-compatible plugin ABI now — already blocked as NX-5; Kabegame's plugin-from-repo model is noted for when NX-5 unblocks.
- photopicker-compose 1.0.0-alpha01 adoption — CLAUDE.md already gates this on the toolchain (2026-07-05 note); the alpha assumes newer toolchain.
- Multiple simultaneous weighted sources (Muzei #367) — Discover already merges enabled providers with style-learning rerank; residual value is covered by the user-added-subreddits roadmap item.
- General DAW/soundscape features (Noice-style) — scope creep beyond ringtone editing.

## Sources
Competitors/OSS:
- https://github.com/Hamza417/Peristyle
- https://github.com/you-apps/WallYou
- https://github.com/ammargitham/WallFlow
- https://github.com/Anthonyy232/Paperize
- https://github.com/muzei/muzei
- https://github.com/maocide/UndeadWallpaper
- https://github.com/Mahmud0808/Iconify
- https://github.com/patzly/doodle-android
- https://f-droid.org/en/packages/org.thayyil.ringdroid/
- https://github.com/offa/android-foss
Sounds/extraction:
- https://github.com/TeamNewPipe/NewPipeExtractor/releases
- https://github.com/TeamNewPipe/NewPipe/releases
- https://github.com/yt-dlp/yt-dlp/releases/tag/2026.07.04
- https://github.com/yt-dlp/yt-dlp/wiki/Po-Token-Guide
- https://github.com/yausername/youtubedl-android/releases
Platform/deps/security:
- https://android-developers.googleblog.com/2026/03/android-developer-verification.html
- https://support.google.com/android-developer-console/answer/16561738
- https://www.androidauthority.com/android-sideloading-changes-timeline-3679204/
- https://thenewstack.io/f-droid-says-googles-android-developer-verification-plan-is-an-existential-threat-to-alternative-app-stores/
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://github.com/androidx/media/releases/tag/1.8.0
- https://coil-kt.github.io/coil/upgrading_to_coil3/
- https://developer.android.com/develop/ui/compose/glance/generated-previews
- https://www.herodevs.com/vulnerability-directory/cve-2026-54513
- https://square.github.io/okhttp/changelogs/changelog/
- https://izzyondroid.org/docs/general/AppInclusionPolicy/
- https://izzyondroid.org/about/security/ReproducibleBuilds/
Community signal:
- https://zedge.pissedconsumer.com/review.html
- https://marlvel.ai/apps/zedge-wallpapers-ringtones
- https://us.community.samsung.com/t5/Suggestions/One-UI-community-needs-a-Depth-effect-on-the-Lock-Screen/td-p/3443291

## Open Questions
- Developer verification: does the owner register the self-signed cert identity with Google (preserves 2027 install viability; identity registration only, no code signing) or stay unregistered (ADB/advanced-flow audience only)? Owner decision; shapes the install docs.
- Roadmap_Blocked "Baseline Profile + Macrobenchmark" lists blocker "adb devices returns no attached devices" — an SM-S908U1 (Android 16) was attached and used for QA on 2026-07-15/16, so that item is unblockable whenever the device is connected.
- Media3 1.8.0 resolves and compiles against Aura's compileSdk 35 toolchain. Glance 1.2.0 / Room 2.8.x AAR minCompileSdk values still need a resolve-check before those separate bumps (Room 2.8 is also Kotlin/KSP-gated).
- Does YtDlpUpdateManager's runtime update channel actually deliver upstream binaries on the ~2-week cadence, and is the QuickJS/EJS runtime active in the 0.18.1 integration? Needs a live device check during the extraction-resilience work.
