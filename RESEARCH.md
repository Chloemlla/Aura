# Research — Aura
Date: 2026-08-10 — replaces all prior research (previous pass: 2026-07-29).
Confidence: unqualified project facts are **Verified** by direct inspection at v6.40.0
(versionCode 141); external or unrun claims are labeled **Likely** or **Needs live validation**.

## Executive Summary

Aura 6.40.0 is a mature local-first Android personalization suite (420 Kotlin files, 940 JVM
tests, 76 Python release gates, 377 gate tests green). The 2026-07-29 pass drove a large
correctness program to completion: the archive-extraction bound, the exported-automation
bypass, the apply coordinator, versioned atomic restore, reference-counted generated-asset
pruning, live-video self-healing, and the cross-engine soak harness all landed in v6.39.0 and
v6.40.0. The code is in good shape.

**The failure has moved from the code to everything around it.** Two full versions shipped in
the changelog were never tagged or released, so no user has any of that work. Every published
documentation URL — including the privacy policy the app itself opens — is a 404. Four
security gates pass while checking zero files. The shipped APK is 198 MB against a 30 MB
store ceiling, because the gate named "64-bit only" never checks for 32-bit libraries. The
common root cause is that Aura's compliance suite validates the **working tree**, never the
**published artifact**, and nothing in the repo can tell the difference.

Top opportunities in priority order:

1. Tag and release v6.40.0 — two versions of security fixes are stranded.
2. Repair the `*.md` gitignore rule; 11 README links and the in-app privacy policy 404.
3. Patch two live CVEs in the deployed Cloud Functions backend (`protobufjs`, `body-parser`).
4. Stop `VoteRepository` opening a Firebase socket before the user opts into community.
5. Make compliance gates assert against published state (git-tracked, live URL), not the tree.
6. Enforce 64-bit-only + per-ABI splits: 198 MB → store-listable, Obtainium-friendly.
7. Add `.gitattributes` and a source-byte gate — a NUL byte currently hides a security guard.
8. Split the N-1 blocker: upgrade to AGP 8.9 + compileSdk 36, which unblocks 8 libraries
   without needing to migrate to Kotlin 2.4.
9. Publish `WallpaperColors` from the live-wallpaper engines so Material You stops guessing.
10. Ship a Rotation Health screen — every competitor's rotation is broken and observable
    scheduling exists nowhere in the category.

## Product Map

- **Core workflows.** Browse/search multi-source wallpapers, video wallpapers, and
  ringtone/notification/alarm sounds. Edit, crop, apply, download, favorite, collect, and
  restore. Rotate wallpapers by schedule, screen event, weather, theme, tile, or broadcast.
  Import local media, generate optional AI wallpapers, keep favorites offline.
- **Personas.** No-account privacy-conscious users; automation power users; local-media
  collectors needing durable organization and recovery; optional community contributors;
  FOSS users expecting unsupported services to be explicit.
- **Platforms and distribution.** Android 8.0+ (`minSdk 26`), compile/target SDK 35, phone-first.
  `full` and Firebase-free `foss` flavors. Built locally — there is no CI; `.github/workflows/`
  was deliberately removed (commit "Remove GitHub Actions workflows — local builds only").
  Distribution is GitHub Releases + Obtainium; IzzyOnDroid is the stated near-term target.
- **Integrations.** Wallhaven, Bing, Pexels, Pixabay, Reddit Atom, YouTube via
  NewPipe + yt-dlp, Freesound (legacy), Open-Meteo, Stability AI (user key), and Firebase
  Auth/RTDB/Storage/Functions + ML Kit in the `full` flavor.

## Competitive Landscape

Eight projects from the 2026-07-29 pass are unchanged and not repeated. New entrants:

- **ShaderEditor** (1083★, 2.36.2, 2026-04-21) — on-device GLSL authoring with sensor,
  battery, and backbuffer uniforms, texture import, and low-battery auto-disable. *Learn:*
  the sensor/battery uniform set and the render-resolution control. *Avoid:* user-authored
  shader text — `AgslShaderGallery.kt` deliberately fixes the catalog, and exposing arbitrary
  shaders would contradict that decision and reintroduce a compile-failure attack surface.
- **WallFlow** (463★, stale since 2024-04) — ML subject **detection** driving auto-crop at
  rotation time, plus saved-search auto-download queues. *Learn:* detection-driven crop on
  rotation; Aura has ML Kit segmentation but only for parallax. *Avoid:* its abandonment
  pattern — issue #85 ("Change now doesn't work", 37 comments, open since 2024-03) is the
  single best-documented instance of the category-wide rotation failure.
- **workpaper-android** (MIT, v2.10.0) — day-of-week × time-of-day rule matrix with
  per-rule filter binding and a widget showing the *next* wallpaper. *Learn:* rules beat
  intervals. *Avoid:* its "apply immediately vs force immediately" split, which is a UI
  workaround for an unobservable scheduler rather than a fix.
- **SlideshowWallpaper** (79★) — implements slideshow **as a `WallpaperService`**, not a
  WorkManager job, so OEM background-kill cannot stop it. *Learn:* this architecture is the
  actual answer to the rotation-reliability complaint class. *Avoid:* making it the only
  path — it costs a persistent engine.
- **Mozart** (Apache-2.0) — renders arbitrary Jetpack Compose UI as a live wallpaper.
  *Learn:* Aura already owns a Compose design system; clock/weather/overlay wallpapers could
  be composables instead of a second GL/AGSL pipeline (relevant to blocked NX-1).
- **ringdroid (althafvly fork)** (66★, v3.1.0, 2026-08-01) — the only maintained OSS Android
  ringtone editor. *Learn:* in-editor recording, its one capability Aura lacks. *Avoid:*
  nothing — Aura's editor is strictly superior otherwise (fade, normalize, convert).
- **losslesscut-android** (MIT, v20260810) — stream-copy audio/video cutting with no
  re-encode. *Learn:* Aura's `AudioTrimmer` always re-encodes through FFmpeg; a lossless
  cut mode is a real missing mode. *Avoid:* replacing the re-encode path — fade and
  normalize require it.
- **Backdrops** (5M+ installs) — **paywalls auto-shuffle itself.** *Learn:* the category
  monetizes rotation, and every free implementation is unreliable; *reliable* rotation is
  the differentiator, not rotation. *Avoid:* its coin currency and locked AMOLED packs.

**Field observation:** there is no maintained, full-featured OSS Android audio/ringtone
editor. A search of GitHub `topic:ringtone`, the F-Droid index (4,168 packages), and the
IzzyOnDroid index (1,396 packages) returns only ringdroid and single-purpose converters.
Aura's Sound Editor has effectively no competitor — this is its most defensible surface and
is currently under-invested relative to the wallpaper side.

## Security, Privacy, and Reliability

- **[Verified] Two live CVEs in the deployed backend.** `functions/package-lock.json`
  resolves `protobufjs` **7.6.4** (GHSA-j3f2-48v5-ccww, `.proto` option-parsing DoS, fixed
  7.6.5) and `body-parser` **1.20.5** (GHSA-v422-hmwv-36x6, silent size-limit bypass DoS,
  fixed 1.20.6). Both confirmed against api.osv.dev. `protobufjs` is held at the vulnerable
  version by an explicit `overrides` entry in `functions/package.json` that was added *as a
  security pin* — the pin became the vulnerability. `form-data` and `uuid` are pinned the
  same way and are correct today; they will rot identically without a recheck mechanism.
- **[Verified] Network before consent.** `VoteRepository.kt:173-189` attaches a Firebase RTDB
  `ValueEventListener` in its `init` block with no `isCommunityAccessEnabled()` gate — every
  other entry point in the file has one (`:199`, `:215`, `:317`, `:328`, `:356`). Community
  access defaults to **false**. The class is `@Singleton` and is injected into
  `VideoWallpapersViewModel.kt:438` and `SettingsViewModel.kt:106`, so opening Videos or
  Settings opens an RTDB socket for a user who never opted in. `moderationListener` is stored
  at `:185` and is **never removed anywhere in the file**.
- **[Verified] Every published document is a 404.** `.gitignore:34-35` (`*.md` + `!README.md`)
  excludes all markdown; `git ls-files docs/` returns 36 files, **zero** of them `.md`. All 11
  `docs/*.md` links in README are dead, and `SettingsPermissionsAboutSection.kt:30` opens
  `.../blob/main/docs/privacy/privacy-policy.md`, confirmed HTTP 404. `tools/privacy_policy_link_check.py`
  reports `releaseGate: ok` because it validates the local file. A missing privacy policy is a
  hard blocker for IzzyOnDroid, F-Droid, and Accrescent.
- **[Verified] Two shipped versions were never released.** CHANGELOG documents v6.39.0 and
  v6.40.0 (both 2026-07-29); `git tag` and `gh release list` both stop at **v6.38.1**.
  Obtainium reads GitHub Releases, so no user has the bounded archive extraction, the
  automation-gate fix, or the apply coordinator.
- **[Verified] A gate named "64-bit only" does not check for 32-bit libraries.**
  `tools/native_alignment_check.py:246-247` does `if not library.is_64_bit: continue`, then
  only asserts required 64-bit ABIs are *present*. `docs/distribution/native-alignment.json`
  sets `require64BitOnly: true` and its own evidence block lists `lib/armeabi-v7a/` and
  `lib/x86/` FFmpeg + Python payloads in the checked APK. The released
  `Aura-v6.38.1-...-universal-release.apk` is **198 MB** — 6.6× IzzyOnDroid's 30 MB per-APK
  ceiling and above Accrescent's 128 MiB. `app/build.gradle.kts` declares no `splits` or
  `abiFilters`. That policy file also carries `status: "releaseWorkflowEnforced"`, naming a
  workflow that no longer exists.
- **[Verified] Four security gates check nothing.** `tools/github_{actions_allowlist,security_workflow,workflow_permissions,workflow_secrets}_check.py`
  each return `"status": "ok", "workflowCount": 0` after the workflows were deliberately
  deleted. 41 files still reference `.github/workflows`.
- **[Verified] Source-byte corruption, no `.gitattributes`.**
  `service/AuraOriginalsDownloader.kt` contains a raw NUL at offset 11170 (a `'\u0000'`
  written as a literal byte inside the path-traversal guard); ripgrep reports *"binary file
  matches"* and refuses to show the file, hiding that guard from every modern search tool.
  `VoteRepository.kt` carries 3× U+FFFD. `git ls-files --eol` reports **14 tracked files with
  mixed line endings** (including `build.gradle.kts`, `settings.gradle.kts`,
  `DatabaseMigrations.kt`) and 3 CRLF-in-index — among them
  `gradle/verification-metadata.xml` and `docs/legal/dependency-notices.lock.json`, the two
  files the reproducibility and license gates hash.
- **[Verified] yt-dlp payload is 9 months stale.** `youtubedl-android` 0.18.1 (2025-11-16)
  predates five yt-dlp advisories fixed in 2026.06.09/2026.07.04 — four HIGH, including
  command injection via `--write-link` (CVE-2026-55404), `--exec` (GHSA-69qj-pvh9-c5wg),
  and `--netrc-cmd` (CVE-2026-26331). Actual exposure depends on which flags Aura passes and
  whether the runtime update path in `YtDlpUpdateManager.kt` is reachable in practice; that
  update is the mitigation, and it is currently reachable only from Settings
  (`SettingsViewModel.kt:517`). **[Needs live validation]** on device.
- **[Verified] Unbounded writes before the size check.** `VideoWallpapersViewModel.kt:706-735`
  runs `YoutubeDL.execute` with no `--max-filesize`; `VideoWallpaperStorage.kt:137-155`
  validates only *after* the file is fully written to `filesDir` (not cache, so it is not
  system-reclaimable). The HLS branch writes then copies, needing 2× the size at once. The
  OkHttp branches are correctly capped.
- **[Verified] Settings writes violate the codified ordering rule.**
  `SettingsViewModel.kt:928-964` — five DataStore→SharedPreferences bridges write DataStore
  first. `PreferencesManager.kt:510-517` codifies the inverse with rationale ("Write
  SharedPreferences FIRST … even if the suspending DataStore write gets cancelled"), because
  `WeatherWallpaperService.kt:204-261` reads SharedPreferences only. Cancelling
  `viewModelScope` by leaving Settings strands the live wallpaper on the old value while the
  toggle reads as changed.
- **[Verified] Minor security hardening gaps.** `res/xml/network_security_config.xml` is an
  empty `<network-security-config />`; its presence makes the manifest's
  `usesCleartextTraffic="false"` (`AndroidManifest.xml:64`) inert, leaving only the
  targetSdk≥28 platform default. `MainActivity.kt:144-166` accepts `ACTION_ATTACH_DATA`
  `intent.data` with any scheme (only checking `type.startsWith("image/")`) and routes it to
  the crop screen, while the adjacent `isAllowedLaunchUrl` (`:93-101`) enforces HTTPS-only.
- **[Verified] Live-wallpaper engines publish no colors.** No `onComputeColors()` or
  `WallpaperColors` anywhere in `service/`. When an Aura live wallpaper is active the system
  derives Material You theming from nothing, which matches the darkmodewallpaper #115/#203
  and Muzei #744 complaint class.

## Architecture Assessment

- **Verification boundary (the systemic finding).** 76 Python gates + 74 mirror tests all
  validate the working tree. Only `foss_reproducibility_check.py` consults git. Nothing
  asserts that a file is *tracked*, that a URL *resolves*, that a release *exists*, or that a
  policy's named enforcement mechanism *still exists*. Four of the five findings above are
  instances of that single gap, not independent bugs. The fix is a shared assertion layer —
  "published state" predicates — used by the doc, privacy, release, and distribution gates.
- **Toolchain boundary — the N-1 blocker is over-scoped.** `Roadmap_Blocked.md:39-58` blocks
  the upgrade to Media3 1.10.1, Coil 3.5.0, and OkHttp 5.4.0 until N-1 lands (an upgrade to
  AGP 9 / Gradle 9 / Kotlin 2.3), because "AGP 8.7.3 max is 35". But **compileSdk 36 with targetSdk 35 is legal** and triggers
  no Android 16 behavior change; it needs only an AGP 8.9.x-class bump on the existing Gradle
  8.12.1 / JDK 17 / Kotlin 2.1.0 stack. That intermediate step targets an upgrade to Media3 1.11.0, Coil
  3.5.0, core 1.18.0, activity 1.13.0, and (with the Room KSP issue separately resolved) the
  rest, while the genuinely expensive upgrade to AGP 9 / Gradle 9 / Kotlin 2.4 — where K1 is removed
  and Compose compiler flags become hard errors — remains deferred until later. Upgrading to AGP 8.8+ is also the
  floor for the R8 core-count determinism fix that any reproducibility claim depends on.
  **[Likely]** — validate by running the bump; the exact minimum AGP minor is the acceptance test.
- **Upgrade targets reachable *today* at compileSdk 35.** Upgrading to Compose BOM 2026.06.01, Navigation
  and upgrade to Paging 3.5.0, DataStore 1.2.1, coroutines 1.11.0, Firebase BoM 34.17.0 —
  each requires no compileSdk change;
  upgrade targets NewPipeExtractor v0.26.4 and Roborazzi 1.71.0 also clear compileSdk 35 and are not blocked
  by anything in `Roadmap_Blocked.md`. Aura is 7 Roborazzi minors and 4 Firebase minors behind
  with no tracked reason. Glance 1.2.0-rc01 is a permanently orphaned RC — 1.2.0 never shipped
  stable; 1.1.1 is the stable line.
- **Refactor candidates.** Screen files are ungated and three exceed 1500 lines:
  `WallpapersScreen.kt` (1794), `SoundsScreen.kt` (1777), `VideoWallpapersScreen.kt` (1550).
  The 500-line contract gate covers only the Wallpapers and Sounds ViewModels;
  `VideoWallpapersViewModel.kt` (1239), `SettingsViewModel.kt` (914),
  `WallpaperEditorViewModel.kt` (871), and `SoundEditorViewModel.kt` (732) are all past it —
  the first two are already tracked, the editors are not.
- **Editor state boundary.** `WallpaperEditorViewModel.kt` orphans the displaced
  full-resolution bitmap on every filter render (`:642-648`, up to ~67 MB at
  `MAX_EDIT_LONG_EDGE = 4096`; the `catch (_: OutOfMemoryError)` at `:661` is evidence this
  already happens), silently discards a composed depth portrait when any slider moves
  (`:601-602` re-renders from `originalBitmap`), and renders apply/export/parallax from a
  snapshot captured *before* the coroutine launches (`:240`, `:313`, `:341`).
- **Test boundary.** 940 JVM tests across 141 files is strong, but only 3 files exist under
  `androidTest` and there is no CI to run them. 10 Roborazzi goldens cover debug fixtures
  rather than production composables (already tracked). `VideoWallpapersViewModelTest`
  constructs no ViewModel, which is why the delegate split is blocked.
- **Doc generation.** `tools/release_manifest.py` already emits the single source of truth
  (`roomSchemaVersion: 16`, `versionName: 6.40.0`), but README.md:178 and CLAUDE.md still
  claim a stale schema number. The manifest exists; the docs are not generated from it.
- **Note for the next researcher.** `tools/manifest_consistency_check.py` scans README, CLAUDE,
  ROADMAP, and **this file** for version-shaped strings and fails when they disagree with
  `gradle/libs.versions.toml`. It distinguishes current-state from forward-looking claims only
  by an `ASPIRATIONAL_SKIP` regex applied **per line** (`upgrade to`, `migrate to`, `targets`,
  `requires`, `needs`, `until`, `planned`, …). Any sentence naming an upgrade target must carry
  one of those words on the same physical line, or the release gate breaks.
- **Category coverage.** New work below covers security, privacy, reliability, observability,
  testing, docs, distribution/packaging, dependency/upgrade strategy, and build performance.
  Deliberately **not** re-raised: *accessibility* — the interactive-element audit came back
  clean (the four `contentDescription = null` `IconButton`s at `SharedComponents.kt:204`,
  `DownloadsScreen.kt:215,309`, `SoundDetailScreen.kt:786` all carry `semantics { onClick(label) }`
  at 48 dp, and `SoundUiTokens.kt:27-37` now clears 4.5:1), and the remaining gaps are already
  tracked (production-composable gate in ROADMAP; 200% font and widget/TalkBack passes in
  `Roadmap_Blocked.md`); *i18n* — 1690 strings are extracted and the pseudo/RTL gate is live,
  with the scanner-scope gap already tracked; *offline/resilience* — offline favorites now
  render from the managed local file (v6.40.0) and Room migrations v1→v16 are complete and
  registered with no destructive fallback; *multi-user and plugin ABI* — rejected or blocked
  (NX-5); *mobile* — Android is the product. No duplicate was added for any of these.
- **Distribution readiness.** `fastlane/metadata/android/en-US/changelogs/` stops at `8.txt`
  against versionCode 141, and there is no `images/` directory — IzzyOnDroid requires icon and
  screenshots in-repo. The `foss` flavor stubs only `com/google/*`; **Stability AI is not
  stripped** (`STABILITY_AI_KEY` is an unconditional `defaultConfig` field), which collides
  head-on with IzzyOnDroid's stated policy against apps for accessing generative-AI platforms.

## Rejected Ideas

- **User-authored shaders (ShaderEditor)** — `AgslShaderGallery.kt` and its CLAUDE.md note
  deliberately fix the catalog and forbid user shader text; reversing that reintroduces a
  compile-failure and content surface the project already decided against.
- **Godot / Wallpaper Engine workshop import** (Godot-Android-Live-Wallpaper) — running
  third-party interactive bundles is arbitrary code execution in a wallpaper process; the
  prior pass already rejected unrestricted plugins for the same reason.
- **Icon packs and watch faces** (Zedge monetizes both) — no code, asset, or distribution
  overlap with Aura's wallpaper/sound pipelines; a separate product wearing the same name.
- **SLSA provenance / cosign attestation for local builds** — caps at SLSA L1 without a
  trusted builder, and is a signing ceremony tied to an identity, which the project's
  no-code-signing rule excludes. Reproducible builds give third parties equivalent assurance
  with no key; pursue that instead (needs AGP 8.8+ first).
- **targetSdk 36 or 37 now** — the 2026-08-31 Play deadline is a Play *distribution* rule and
  does not reach GitHub/Obtainium/IzzyOnDroid. Raising targetSdk buys mandatory edge-to-edge,
  mandatory predictive back, and large-screen orientation lockout for no distribution gain.
  compileSdk 36 alone captures the entire dependency benefit.
- **"Ringtone V2" / `Ringtone.Builder`** — does not exist in the public SDK at 36, 36.1, 37.0,
  or 37.1; `android.media.Ringtone` has gained no public method since API 31. Any such work is
  hidden/`@FlaggedApi` in AOSP. Do not plan against it.
- **Per-SIM ringtones** — `RingtoneManager.setRingtoneUri(Context, Uri, PhoneAccountHandle)`
  is API **37**, so this is gated behind the full toolchain upgrade, not actionable now.
- **Isolated Projects for build speed** — incubating in Gradle 9.7 and explicitly not
  recommended for production; Aura is on Gradle 8.12.1 and the configuration-cache
  prerequisite is not met.
- **Mandatory cloud sync, ads/coins/accounts, `MANAGE_EXTERNAL_STORAGE`, desktop/iOS ports,
  sub-15-minute rotation, custom segmentation models, silent OLED recoloring** — unchanged
  from the 2026-07-29 pass; re-validated, still rejected.

## Sources

### Competing and adjacent OSS projects
- https://github.com/markusfisch/ShaderEditor
- https://github.com/ammargitham/WallFlow/issues/85
- https://github.com/ammargitham/WallFlow/issues/113
- https://github.com/Jarvay/workpaper-android
- https://github.com/Doubi88/SlideshowWallpaper
- https://github.com/creativedrewy/Mozart
- https://github.com/althafvly/ringdroid
- https://github.com/tazztone/losslesscut-android
- https://github.com/JackRushante/SOSRing
- https://github.com/cvzi/WallpaperExport
- https://github.com/yellowbluesky/PixivforMuzei3
- https://github.com/TheOathMan/Godot-Android-Live-Wallpaper
- https://github.com/b-lam/Resplash

### Community signal
- https://github.com/you-apps/WallYou/issues/266
- https://github.com/you-apps/WallYou/issues/289
- https://github.com/you-apps/WallYou/issues/292
- https://github.com/cvzi/darkmodewallpaper/issues/115
- https://github.com/cvzi/darkmodewallpaper/issues/203
- https://github.com/muzei/muzei/issues/367
- https://github.com/muzei/muzei/issues/744
- https://news.ycombinator.com/item?id=44935850
- https://news.ycombinator.com/item?id=45657059
- https://forum.f-droid.org/t/ringtone-maker-app/22600

### Platform and standards
- https://developer.android.com/about/versions/16/behavior-changes-16
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/google/play/requirements/target-sdk
- https://developer.android.com/developer-verification/guides/faq
- https://developer.android.com/topic/performance/power/power-details
- https://developer.android.com/develop/background-work/services/fgs/changes
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/build/releases/gradle-plugin
- https://developer.android.com/build/dependency-verification

### Distribution policy
- https://izzyondroid.org/docs/general/AppInclusionPolicy/
- https://izzyondroid.org/docs/reproducibleBuilds/EstablishApp/
- https://f-droid.org/docs/Inclusion_Policy/
- https://f-droid.org/en/docs/Anti-Features/
- https://f-droid.org/docs/Reproducible_Builds/
- https://accrescent.app/docs/guide/publish/requirements.html
- https://github.com/ImranR98/Obtainium
- https://github.com/privacyguides/verified-apps-android

### Dependencies and advisories
- https://github.com/advisories/GHSA-j3f2-48v5-ccww
- https://github.com/advisories/GHSA-v422-hmwv-36x6
- https://api.osv.dev/v1/query
- https://github.com/yt-dlp/yt-dlp/releases
- https://github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide
- https://github.com/TeamNewPipe/NewPipeExtractor/releases
- https://developer.android.com/jetpack/androidx/releases/media3
- https://developer.android.com/jetpack/androidx/releases/glance
- https://github.com/coil-kt/coil/blob/main/CHANGELOG.md
- https://kotlinlang.org/docs/whatsnew24.html
- https://docs.gradle.org/9.7.0/release-notes.html
- https://github.com/CycloneDX/cyclonedx-gradle-plugin
- https://arxiv.org/pdf/2607.01890

## Open Questions

- **[Blocks release]** Was the v6.39.0/v6.40.0 release intentionally withheld, or did the
  release step simply never run after the workflows were deleted? The answer determines
  whether the fix is "cut the release" or "add a local release gate that fails when
  `versionName` has no matching tag".
- **[Blocks store submission]** Should the `foss` flavor drop Stability AI entirely to satisfy
  IzzyOnDroid's generative-AI-platform policy, or is IzzyOnDroid listing being abandoned in
  favor of GitHub + Obtainium only? Every distribution item below depends on this.
- **[Needs owner decision]** Android developer verification: enrol at $25 with government ID,
  or decline as NewPipe has publicly done and accept that users must complete the 24-hour
  advanced flow. Nothing in the codebase can engineer around this, and it reaches
  GitHub/Obtainium users, unlike every Play deadline in this report.
- **[Needs live validation]** Which yt-dlp flags does Aura actually pass? Exposure to the four
  HIGH command-injection advisories depends on `--exec`, `--write-link`, `--netrc-cmd`, and
  aria2c usage, and on whether the runtime update path succeeds on a real device.
