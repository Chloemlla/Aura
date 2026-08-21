# Research — Aura
Date: 2026-08-20 — replaces all prior research (previous pass: 2026-08-11).
Confidence: unqualified project facts are **Verified** by direct inspection at v6.41.0 (versionCode 142, HEAD `070d9a8`, dirty tree — see Working-tree note); external claims are labeled **Likely**, **Assumption**, or **Needs live validation**.

## Executive Summary

Aura is a mature local-first Android personalization suite (wallpapers, live/video wallpapers, YouTube-powered ringtones/notifications/alarms, community uploads) at 257 main-source Kotlin files (~70k LOC), 142 JVM test files, 82 Python release gates, and zero TODO debt in code. Since the 2026-08-11 pass, four commits closed four of its top findings: streaming wallpaper applies (`1295544`), shuffle no-repeat (`bee5a2b`), cleartext/intent hardening (`070d9a8`), and settings preference-write cleanup (`ab1153b`); uncommitted work bounding the Pixabay video cache (`PixabayVideoCacheStore.kt`) is in the working tree. The competitive picture is unusually favorable: AlternativeTo already lists Aura as *the* OSS Zedge alternative, the commercial category's three monetization models (ads, subscriptions, credits) are the top three documented reasons users leave those apps, Nova Launcher's collapse displaced a large personalization-enthusiast audience, and features competitors' trackers beg for (custom subreddits, Lemmy source, AMOLED crush filter, battery dashboards, time-of-day rotation machinery) are already shipped in Aura. The largest risks are unchanged in kind: the release-publication gap (tag `v6.41.0` pushed, newest GitHub Release still `v6.38.1` from 2026-07-29, so Obtainium users are three versions of security fixes behind) and the 2026-09-30 Android developer-verification enforcement start. The genuinely new findings this pass are three features shipped without their promised follow-up UI — the 24H wallpaper-pack editor, the sound-profile editor (both are Settings toggles that schedule a 15-minute periodic worker which can never do anything, because no UI can create the packs/profiles the workers read), and live-wallpaper dimming wired into only one of three engines — plus a small set of reliability/positioning items (OEM ringtone-write failures, rotation prefetch, manual-apply timer reset, signing transparency, and an offline procedural generator that would neutralize Tapet's only differentiator).

Top opportunities in priority order:

1. Publish the v6.39–v6.41 GitHub Releases (existing P0 item; the gap now includes the commons-compress bound, intent hardening, and consent gating fixes).
2. Ship or gate the two dead scheduling toggles: the 24H wallpaper-pack and sound-profile workers poll empty DataStore JSON every 15 minutes forever.
3. Finish live-wallpaper dimming on the video and parallax engines (promised in `517f642`, Weather-only today).
4. Classify OEM ringtone-write failures (Samsung secure-settings `IllegalArgumentException`) instead of failing generically — ringtone-setting pain is the category's most-reported unsolved problem.
5. Prefetch the next rotation wallpaper so rotation survives a dead network at fire time.
6. Decide developer-verification enrollment before 2026-09-30 (owner action, tracked in Roadmap_Blocked.md).
7. Publish signing-cert transparency (README/fastlane SHA-256) and register the reproducible FOSS lane with IzzyOnDroid's rbtlog when submission proceeds.
8. Longer bets: offline procedural wallpaper generator (Tapet-class, charter-perfect), rotation-notification inline actions, and the already-queued FFmpeg retirement.

## Working-tree note (2026-08-20)

The prior working-tree edits are now committed. The current feed path uses a generation-gated load job so warm-cache rendering and pagination cannot issue overlapping provider requests or overwrite an accepted append.

The Sound Editor now keeps processed exports on the existing FFmpeg path, while an unprocessed
supported source can use a guarded stream-copy cut. OGG exports include `ANDROID_LOOP=true`,
and the editor checks the copied packet sequence before accepting a lossless result.

## Product Map

### Core workflows
- Browse provider/community/local wallpaper, video, and sound feeds; search/filter; preview; favorite; download (Room + MediaStore).
- Edit wallpapers (crop, tone, AMOLED crush, depth portraits, text/sticker overlays) and sounds (trim, fade, normalize, convert, gapless OGG output, and verified lossless cuts) and apply to home/lock/both or as ringtone/notification/alarm/per-contact.
- Run one of three live-wallpaper engines (video, parallax, weather/shader) with FPS caps, battery caps, touch effects, and a battery dashboard.
- Automate: interval/clock/day-night/theme rotation, rotation triggers (unlock/screen-off), 24H packs (scheduler shipped, editor missing), sound profiles (same state), scheduled backups, Tasker/tile/widget entry points.
- Share and back up: collections via link/QR/JSON, whole-library export/import, local theme packs.

### Personas
- Privacy-first sideloader (no account, no ads, verifiable APK); collector (multi-folder libraries, rotation); customizer (editors, home/lock separation, per-contact sounds); community uploader (rights/AI disclosure); maintainer/distributor (reproducible, size-conscious artifacts).

### Platforms and distribution
- [Verified] Android only, minSdk 26, compile/target 35, `full` + `foss` flavors, Room v17. Signed universal APK + SHA256SUMS via GitHub Releases/Obtainium; IzzyOnDroid is the near-term store target; no CI workflows exist.
- [Verified] Release gap: `git tag` has v6.41.0; `gh release list` newest is v6.38.1 (2026-07-29). `obtainium.json` sets `fallbackToOlderReleases: true`, silently holding users at v6.38.1.

### Integrations and data flows
- Providers: Wallhaven, Pexels, Pixabay, Bing, Reddit (user-configurable up to 12 subreddits, validated), Lemmy, NASA, Wikimedia, YouTube (NewPipe v0.26.3 + yt-dlp 2026.07.04 payload), Open-Meteo, Stability AI (full flavor, off by default), Firebase community. Five legacy sound providers (Freesound/FreesoundV2/SoundCloud/Audius/ccMixter) remain constructed-but-never-called behind documented `ProviderStatus.LEGACY` disclosures; their fate is an owner decision tracked in Roadmap_Blocked.md.
- Local: Room schema v17, DataStore + six SharedPreferences bridge files (write-order gated), SAF, MediaStore, WorkManager, Media3, Coil, ML Kit subject segmentation (Play-services beta), AGSL presets.

## Competitive Landscape

- **Zedge** — 20M+ MAU, ~1.3M subscriptions, now pivoting to selling creator content as AI training data ("DataSeeds.AI") and pushing AI generation. Complaint profile: heaviest ad load in the category, credit-pack denominations deliberately misaligned with item prices, billing disputes, a botched ringtone-section removal, and hijacking the system ringtone picker. Learn: cross-content discovery and creator attribution as visible product features. Avoid: everything else. Aura's "no payment rails at all" and "uploads are never sold as training data" are direct, documentable counter-positions.
- **Wallpapers by Google (Pixel)** — free, ad-free bar for stock UX: cinematic 2D→3D parallax, on-device AI generation, daily rotation — but Pixel-gated. Aura already ships depth parallax from any photo on any OEM device; that comparison is worth stating in the README.
- **Tapet** — offline procedural generation at exact screen resolution with palette controls (paywalled) is its entire differentiator. An Aura procedural generator seeded by Material You/user palettes would neutralize it and is charter-perfect (offline, deterministic, no AI, feeds rotation). Distinct from the previously rejected R-1 AI generation.
- **Walli / Backdrops** — artist curation + revenue share; both drifting toward heavier monetization (Backdrops added a coin shop in Oct 2025; reviews now complain of "AI slop" replacing artist work). Learn: artist attribution costs nothing and buys trust. Avoid: locked-tier content and dual currencies.
- **Wallpaper Engine (Android)** — free companion, no ads/tracking; its praised feature is time-of-day playlists — exactly what Aura's shipped-but-editorless 24H packs would be. Useless standalone (PC required); Aura should stay standalone.
- **Paperize** (same Kotlin/Compose/M3 stack) — offline albums, per-screen sources, wide decode support, apply-time effects. Its tracker documents two defects Aura should fix preemptively: manual change not resetting the rotation timer (#591) and Android's dynamic-color engine failing to re-trigger on programmatic apply (#588).
- **Peristyle** — local-first manager; recent releases added swipe gestures on live wallpaper and delete-from-notification. Its crash fix for delayed storage mounts after reboot is a warning for Aura's boot-time rotation paths.
- **WallYou / WallFlow** — Wallhaven-client benchmarks; their trackers beg for features Aura ships (multi-subreddit, Lemmy, position lock is queued). WallFlow's TFLite smart crop parallels Aura's shipped SmartCropDetector. Both show constant source-rot firefighting — Aura's provider-policy seam is the right defense. Note: the "Villain" Wallhaven client cited in some comparisons does not exist as a findable project; WallFlow is the real benchmark.
- **Muzei** — plugin API outlived its host app; recede mode (dim/blur + double-tap reveal) is the category reference for Aura's dimming work. The plugin ABI remains correctly blocked in Roadmap_Blocked.md.
- **Doodle Android** — the battery-model reference: render-only-on-input, tilt+swipe parallax, zoom-on-unlock, direct-boot support. Its "power-efficient animations + auto dark mode" framing is the positioning language that wins the Material You audience.
- **UndeadWallpaper** — per-clip zoom/offset/speed/volume, gapless playlists via one GL+ExoPlayer pipeline, one-shot freeze-frame mode; the design reference for the queued video-playlist item (Media3 1.11's preload APIs are the intended mechanism for that feature).
- **Wallora** — closest philosophical twin (MIT, multi-source); notable for rotation prefetch (next wallpaper cached for instant apply) and on-unlock triggers. Aura has the triggers; prefetch is the gap.
- **Seal / NewPipe** — the yt-dlp/extraction references. Seal embeds metadata/thumbnails in extracted audio and keeps yt-dlp updatable at runtime (Aura does, hash-pinned + opt-in, which IzzyOnDroid requires). NewPipe's 2026 SABR breakage (fixed in extractor 0.26.3, which Aura pins) shows a 6–10 week breakage cadence — the dependency-refresh path is availability work, not hygiene.
- **Ringdroid (althafvly fork)** — the only maintained OSS ringtone editor; v2.7.5 (2025-11) crossed the scoped-storage minefield up to Android 16. Aura's editor already exceeds it; the uncontested shelf is device-sound browsing + restore-original (already queued) and OEM-quirk handling (new this pass).
- **Atmo Engine** — Nothing-OS-style unlock transition (blur sharpens on unlock) built on the same shader primitives Aura owns. Noted as a differentiator candidate; deliberately not queued (battery and always-active-engine cost; revisit after NX-1).

## Security, Privacy, and Reliability

- [Verified] **Release gap is the top trust issue**: v6.39.0–v6.41.0 (including the bounded archive extraction, automation-intent gating, cleartext config, and moderation-consent fixes) are unreachable by every Obtainium user. Existing P0 item; evidence refreshed 2026-08-20 (`gh release list` newest = v6.38.1).
- [Verified] **Two dead toggles schedule perpetual no-op work**: `SettingsWallpaperSection.kt:249` and `SettingsSoundSection.kt:198` enable 15-minute periodic workers (`WallpaperPackManager.kt`, `SoundProfileManager.kt:82-93` — defers with "no sound profiles defined") whose DataStore JSON no UI can write. Battery cost with zero user value, and a shipped promise the product cannot keep. New items queued.
- [Verified] **OEM ringtone writes fail generically**: `SoundApplier.kt:70,109` call `RingtoneManager.setActualDefaultRingtoneUri` with no OEM-failure classification; Samsung devices throw `IllegalArgumentException` ("cannot keep your settings in the secure settings") on notification-sound writes per Samsung developer-forum reports. New item queued.
- [Verified] **yt-dlp CVE posture is current**: the bundled 2026.07.04 payload post-dates all four 2026 advisories (CVE-2026-26331 `--netrc-cmd` injection, CVE-2026-50023 filename-sanitization bypass, CVE-2026-50574 aria2c file write — all fixed by 2026.06.09). The remaining tracked work (size caps before write, flag-set gate asserting no `--exec`/`--netrc-cmd`/aria2c) stands; no emergency payload bump needed.
- [Verified] **Non-issues confirmed this pass**: `AudioPlaybackService.onGetSession` rejects untrusted controllers; the adaptive icon carries a `<monochrome>` layer (Android 16 QPR2 auto-theming safe); `freevibe.jks` and `local.properties` are gitignored and untracked; custom subreddits (12, validated), Lemmy, the AMOLED crush filter, battery dashboards, and AI-content labeling/filtering are all already shipped — several are features competitors' trackers still request.
- [Verified] **Dynamic color re-trigger** ([Likely] platform flakiness, Paperize #588): Android sometimes fails to recompute Material You colors on programmatic applies. Aura's queued `WallpaperColors` engine item covers live engines; static applies should be spot-checked on device when that item lands.
- [Verified] Android 16 job quotas, Android 17 memory limiter, background-audio hardening, Room downgrade, and live-wallpaper liveness are all already tracked (ROADMAP.md / Roadmap_Blocked.md); nothing new to add there.
- [Likely] **Developer verification timeline**: first enforcement 2026-09-30 (BR/ID/SG/TH), global 2027; ADB exempt; the power-user "advanced flow" ships via Play Services and can be tightened at any time. The register-vs-abstain decision is owner-gated (Roadmap_Blocked.md) and is now ~6 weeks from first enforcement.

## Architecture Assessment

- Boundaries are healthy: provider policy/disclosure seam, apply coordinator (`fb53812`), delegate-split ViewModels under a 500-line gate, soak-tested live engines. The oversized files are now concentrated in Compose screens: `WallpapersScreen.kt` (1,848), `SoundsScreen.kt` (1,844), `VideoWallpapersScreen.kt` (1,602), `WallpaperDetailScreen.kt` (1,308), `FreeVibeRoot.kt` (1,215), `SettingsDialogs.kt` (959). Screen decomposition should follow the queued production-composable test item — splitting untested 1,800-line screens first would repeat the reason the VideoWallpapersViewModel split was blocked.
- Video feed concurrency is now guarded in the ViewModel. Warm-cache display remains immediate, while the active generation owns provider pagination until its final state merge. Resetting orientation or search also clears pending preview-resolution IDs with the URL cache.
- Toolchain reality check (updates the blocked N-1 scope): AGP 9.x ships built-in Kotlin (the standalone KGP must be removed), needs Gradle 9.1+, and Hilt must be ≥ 2.59.2 (2.59 shipped broken, dagger#5099). Room 3.0.1 is stable, KSP-only, with package renames — the blocked "Room 2.8.x refresh" target is superseded by a real migration. Kotlin stable is 2.4.x with K1 removed. Notes added to Roadmap_Blocked.md.
- The queued dependency item targets these, all of which resolve at the current compileSdk 36: Compose BOM 2026.08.00 (mesh gradients; pausable composition default since 2025.12.00), material3 1.4.0 stable (Expressive — adopt tokens selectively; wholesale adoption conflicts with the documented 4–12dp/no-pill design charter), NewPipeExtractor v0.26.5 (2026-08-15), Roborazzi 1.70.0, Firebase BoM 34.17.0.
- The compileSdk 36 platform pass is now landed for the Android 16 APIs that had direct owners: `WallpaperDescription` instance payloads for live wallpapers, `RuntimeColorFilter`/`RuntimeXfermode` in the AGSL bitmap pipeline, and `Notification.ProgressStyle` for downloads. Media3 1.11.0 preload APIs, Coil 3.5.0, and embedded photo picker direct APIs remain separate feature work.
- targetSdk 36 (later, separate from compileSdk): predictive back on by default (`onBackPressed` dead), edge-to-edge opt-out removed, large-screen orientation/resize flags ignored on sw≥600dp. Noted on the blocked API-37 item.
- Test/docs gaps: unchanged from 2026-08-11 (3 androidTest files, no CI, no testTags — all queued). New: `docs/distribution/release-dry-run.md` still references 6.34.6; folded into the queued release-manifest consistency item.

## Rejected Ideas

- **Material 3 Expressive wholesale adoption** — conflicts with the documented neutral/rectangular design charter (ARCHITECTURE.md; the queued design-token gate). Adopt motion/typography tokens selectively instead. Source: material3 1.4.0 release notes.
- **Sub-15-minute rotation intervals via AlarmManager** (WallYou #229) — contradicts the battery charter and WorkManager posture for marginal value.
- **Unlock-transition engine (Atmo-style) and zoom-on-unlock** — requires an always-active engine per effect; revisit only after NX-1 consolidates live rendering. Source: NOSAtmosphereEffect, doodle-android.
- **WebDAV/Nextcloud/SMB sources** — the queued user-supplied HTTPS source item covers the need with less protocol surface; add WebDAV only if users ask after it ships. Source: NCarousel.
- **Audio metadata/thumbnail embedding in saved ringtones** (Seal/mutagen) — MediaStore rows already carry title/type; embedding art in a ringtone file has no consumer on-device.
- **Ringtone/wallpaper marketplace, accounts, sync, AI-generation expansion** — unchanged from prior passes; anti-AI-slop sentiment (Backdrops reviews, Wallhaven's praised AI ban) reinforces keeping generation opt-in, labeled, and filterable.
- **Muzei-API compatibility layer / plugin ABI** — still correctly blocked on the ownership/security contract (Roadmap_Blocked.md NX-5).
- **"Fossify Wallpapers" as a reference** — no such app exists (org has zero wallpaper repos); do not cite it. Same for the "Villain" Wallhaven client.

## Sources

### OSS competitors and adjacent projects
- https://github.com/muzei/muzei
- https://github.com/you-apps/WallYou
- https://github.com/ammargitham/WallFlow
- https://github.com/Anthonyy232/Paperize
- https://github.com/Anthonyy232/Paperize/issues/588
- https://github.com/Anthonyy232/Paperize/issues/591
- https://github.com/Hamza417/Peristyle
- https://github.com/thissayantan/wallora
- https://github.com/patzly/doodle-android
- https://github.com/maocide/UndeadWallpaper
- https://github.com/cvzi/darkmodewallpaper
- https://github.com/saad-khan-rind/NOSAtmosphereEffect
- https://github.com/AlynxZhou/alynx-live-wallpaper
- https://github.com/althafvly/ringdroid
- https://github.com/JunkFood02/Seal
- https://github.com/TeamNewPipe/NewPipe
- https://github.com/TeamNewPipe/NewPipeExtractor/releases
- https://f-droid.org/en/categories/wallpaper/
- https://github.com/offa/android-foss
- https://alternativeto.net/software/zedge/

### Commercial and community signal
- https://www.stocktitan.net/sec-filings/ZDGE/8-k-zedge-inc-reports-material-event-5745c4ed5741.html
- https://unstar.app/blog/zedge-walli-backdrops-vellum-wlppr-wallpaper-apps-ranked-2026
- https://zedge.pissedconsumer.com/review.html
- https://www.complaintsboard.com/zedge-wallpapers-b149194
- https://r1.community.samsung.com/t5/galaxy-s/ringtone-app/td-p/30258588
- https://forum.developer.samsung.com/t/ringtonemanager-setactualdefaultringtoneuri-not-working-for-some-samsu/30610
- https://xdaforums.com/t/zedge-alternative.4226753/
- https://store.google.com/intl/en/ideas/articles/pixel-custom-wallpaper/
- https://play.google.com/store/apps/details?id=com.sharpregion.tapet
- https://play.google.com/store/apps/details?id=io.wallpaperengine.weclient
- https://9to5google.com/2025/09/08/nova-launcher-shutting-down/
- https://hmmr.online/posts/wallpaper-sources/
- https://github.com/nyas1/Material-You-app-list

### Distribution
- https://izzyondroid.org/docs/general/AppInclusionPolicy/
- https://izzyondroid.org/about/security/ReproducibleBuilds/
- https://codeberg.org/IzzyOnDroid/rbtlog
- https://developer.android.com/developer-verification
- https://android.gadgethacks.com/news/google-android-developer-verification-rollout-explained-policy-impact-and-backlash/
- https://forum.f-droid.org/t/google-will-require-developer-verification-to-install-android-apps-including-sideloading/33123
- https://github.com/ImranR98/Obtainium

### Platform, dependencies, security
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://android-developers.googleblog.com/2026/06/Android-17.html
- https://developer.android.com/jetpack/androidx/releases/compose-material3
- https://android-developers.googleblog.com/2025/12/whats-new-in-jetpack-compose-december.html
- https://android-developers.googleblog.com/2026/08/jetpack-compose-august-2026-release.html
- https://android-developers.googleblog.com/2026/03/media3-110-is-out.html
- https://github.com/androidx/media/releases
- https://coil-kt.github.io/coil/changelog/
- https://android-developers.googleblog.com/2026/03/room-30-modernizing-room.html
- https://blog.jetbrains.com/kotlin/2026/01/update-your-projects-for-agp9/
- https://github.com/google/dagger/issues/5099
- https://developer.android.com/jetpack/androidx/releases/work
- https://developer.android.com/jetpack/androidx/releases/glance
- https://www.sentinelone.com/vulnerability-database/cve-2026-26331/
- https://advisories.gitlab.com/pypi/yt-dlp/CVE-2026-50023/
- https://github.com/yt-dlp/yt-dlp/security/advisories/GHSA-79w7-vh3h-8g4j
- https://github.com/advisories/GHSA-735f-pc8j-v9w8
- https://developers.google.com/ml-kit/vision/subject-segmentation

## Open Questions

- [Owner decision, time-boxed] Register `com.freevibe` for Android developer verification before the 2026-09-30 first-country enforcement, or rely on the advanced flow/ADB? Tracked in Roadmap_Blocked.md; the window is now ~6 weeks.
- [Owner decision] Publish the release backlog as v6.41.0, or fold the in-flight working-tree fixes into a v6.42.0 and release that? Either way the tag-without-release gate must land with it.
- [Needs live validation] Does Android's dynamic-color engine re-trigger reliably on Aura's streaming apply path (Paperize #588 class)? Requires a device pass when the `WallpaperColors` item lands.
