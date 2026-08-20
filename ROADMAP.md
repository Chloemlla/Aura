# Aura Roadmap

Actionable work only. Historical and completed roadmap material is archived in CHANGELOG.md; blocked work is kept in Roadmap_Blocked.md.

## Actionable Items

- [ ] P1 — Test production composables instead of look-alike route fixtures
  Why: screenshot/accessibility gates can stay green while real screens regress because debug fixtures redraw simplified UIs.
  Evidence: `debug/.../AuraRouteStateFixtures.kt`; `AuraRouteStateScreenshotTest.kt`; `tools/accessibility_release_gate_check.py`; Android Compose testing guidance.
  Touches: production screen state injection, Roborazzi tests, accessibility gate, pseudo/RTL/theme fixtures.
  Acceptance: major loading/empty/error/ready/permission states render actual production composables with fake dependencies in light/dark, compact/expanded, pseudo/RTL, and 200% font cases; Compose accessibility checks run; deleting a production symbol breaks the gate; fixture-only surfaces cannot satisfy release.
  Complexity: L

- [ ] P2 — Trim SettingsViewModel into feature-slice delegates (960 lines)
  Why: settings keeps growing across providers, rotation, community, and diagnostics, concentrating state/job ownership in one ViewModel.
  Evidence: `ui/screens/settings/SettingsViewModel.kt` (960 lines).
  Touches: `SettingsViewModel.kt`, feature delegates, tests and split gate.
  Acceptance: the file is under about 500 lines, behavior is unchanged, delegate job ownership is explicit, a split gate exists, and tests pass.
  Complexity: M

- [ ] P2 — Extend Settings search from sections to row-level anchors
  Why: the shipped section-title/description search cannot find visible controls such as OLED theme, Wi-Fi, backup, or App Check.
  Evidence: `SettingsSearch.kt`; isolated API 35 queries “theme” and “OLED.”
  Touches: settings search index, row metadata/anchors, localized resources, navigation/highlight tests.
  Acceptance: localized row labels, descriptions, and intentional aliases match; selecting a result expands and scrolls/highlights the exact row; tests cover OLED, Wi-Fi, backup, App Check, YouTube, battery saver, and no-result behavior.
  Complexity: M

- [ ] P2 — Close residual runtime localization gaps
  Why: the pseudo/RTL gate exists, but user-visible editor labels and ViewModel messages remain outside resources and outside the current scanner.
  Evidence: `WallpaperEditorScreen.kt`; `WallpaperEditorViewModel.kt`; `FavoritesViewModel.kt`; `SettingsViewModel.kt`; `tools/compose_hardcoded_string_check.py`.
  Touches: residual string resources/formatters, ViewModels/models, hardcoded-string gate, production pseudo-locale tests.
  Acceptance: identified runtime literals are resource-backed and locale-formatted, the gate scans composables plus ViewModels/models, production route tests exercise them under XA/XB, and real translations/language picker remain deferred until reviewed.
  Complexity: M

- [ ] P2 — Accept user-owned shared image and audio through bounded ingestion
  Why: Aura supports JSON sharing and image “Set as,” but not normal share/edit entry into its existing image crop and Sound Editor workflows.
  Evidence: `AndroidManifest.xml`; `MainActivity.kt`; DarkModeLiveWallpaper sharing; Ringdroid open/edit flow.
  Touches: manifest filters, external-media dispatcher, `MediaIngestion`/`ShareOutbox`, image editor/crop and Sound Editor navigation/tests.
  Acceptance: user-owned/generated `ACTION_SEND`/`ACTION_EDIT` image/audio routes to a target preview; MIME is sniffed, copy is bounded, `content://` ClipData/read grants are used, cleanup is tested, and malformed/revoked inputs recover; remote items remain link-only/disabled until the blocked per-license capability model permits them.
  Complexity: M

- [ ] P2 — Build an indexed multi-folder local wallpaper catalog
  Why: one rotation folder cannot represent collectors' existing libraries, tags, missing folders, or independent home/lock source sets.
  Evidence: current single-folder preferences/SAF path; Paperize, Peristyle, Muzei, and Fossify Gallery.
  Touches: persisted SAF grants, Room media index/tags, scanner/dedupe, local browse/search, rotation source picker/diagnostics.
  Acceptance: users can add/remove multiple SAF folders, rescan incrementally, tag/search/dedupe items, diagnose revoked/missing grants, and choose per-home/lock collections without broad storage permission.
  Complexity: L

- [ ] P3 — Add Microsoft Spotlight as an opt-in daily-image source after terms validation
  Why: a keyless daily-image source adds low-frequency breadth without another high-volume feed.
  Evidence: WallYou source registry; existing Bing/NASA/Wikimedia daily-source plumbing.
  Touches: provider registry/client, attribution/licensing, network-endpoints manifest, source toggle UI/tests.
  Acceptance: after the P0 capability registry lands, a stable endpoint and use/attribution terms pass its policy gate; Spotlight is opt-in, preserves source URL/provenance, degrades visibly, and is recorded in the endpoint manifest. Lorem Picsum is intentionally excluded because `ProviderDisclosure.kt` forbids new default sourcing.
  Complexity: M

- [ ] P3 — Optional clock/date overlay on applied/live wallpapers
  Why: Paperize issue 533 validates the niche, and Aura already has an overlay composer; it adds no background cost while off.
  Evidence: Paperize issue 533; `WallpaperEditorScreen.kt` overlay pipeline.
  Touches: editor overlay composer, live-wallpaper renderer, settings/format controls, screenshots.
  Acceptance: an opt-in overlay renders localized time/date with time-zone and 12/24-hour behavior, contrast/burn-in-safe position choices, and no background work while off; supported static/live paths have production screenshot coverage.
  Complexity: L

## Research-Driven Additions

Added 2026-08-10. See RESEARCH.md for evidence and confidence labels.

### P0

### P1

- [ ] P1 — Make release gates assert published state, not the working tree
  Why: 76 Python gates validate local files only, so four separate P0-class failures pass green — the 404 docs, the untagged release, `workflowCount: 0`, and the 64-bit policy below. Fixing each symptom without this leaves the class open.
  Evidence: `tools/privacy_policy_link_check.py` → `releaseGate: ok` against a live 404; `tools/github_{actions_allowlist,security_workflow,workflow_permissions,workflow_secrets}_check.py` → `"status":"ok","workflowCount":0`; only `foss_reproducibility_check.py` consults git.
  Touches: shared assertion helper in `tools/`, the doc/privacy/release/distribution gates, `test/tools/`.
  Acceptance: a shared predicate layer asserts tracked-in-git, resolves-over-HTTP, tag-exists, and enforcement-mechanism-exists; deleting a tracked doc, a tag, or a workflow named by a policy makes the owning gate fail; each new predicate has a test that proves it fails before it is trusted.
  Complexity: M

- [ ] P1 — Enforce 64-bit-only and ship per-ABI splits
  Why: the released universal APK is 198 MB — 6.6× IzzyOnDroid's 30 MB per-APK ceiling and above Accrescent's 128 MiB — because 32-bit FFmpeg and Python payloads ship for ABIs nothing needs, and the gate named `require64BitOnly` skips every non-64-bit library instead of rejecting it.
  Evidence: `tools/native_alignment_check.py:246-247` (`if not library.is_64_bit: continue`); `docs/distribution/native-alignment.json` `require64BitOnly: true` with `lib/armeabi-v7a/`, `lib/x86/` in its own evidence block; `gh release view v6.38.1` asset = 198 MB; no `splits`/`abiFilters` in `app/build.gradle.kts`. Referenced as NX-8 in ARCHITECTURE.md but tracked nowhere.
  Touches: `app/build.gradle.kts` (`splits { abi { ... } }`), `tools/native_alignment_check.py`, release bundle check, `obtainium.json`, README install copy.
  Note: per-ABI splits are the fix; **dropping `armeabi-v7a` is a separate, user-facing decision**, because `minSdk 26` still admits 32-bit-only Android 8–9 devices. Splits cut the download without cutting those users. Either resolve `require64BitOnly: true` to match reality (32-bit shipped and supported) or record an explicit decision to drop 32-bit — as of 2026-08-11 the policy and the artifact disagree and the gate cannot tell.
  Acceptance: release output is per-ABI plus a universal APK, and the arm64-v8a artifact is under 30 MB; `native_alignment_check.py` verifies the *declared* ABI set against the APK and fails on any mismatch in either direction, instead of skipping non-64-bit libraries; `obtainium.json`'s `autoApkFilterByArch` is confirmed against the split asset names; `docs/distribution/native-alignment.json` no longer claims `releaseWorkflowEnforced` for a workflow that does not exist.
  Complexity: M

- [ ] P1 — Restore Android Lint, which cannot complete a run
  Why: `:app:lintAnalyzeFullDebug` aborts. Three Compose lint detectors each throw `IncompatibleClassChangeError` — `RememberInCompositionDetector` (reached from `FrequentlyChangingValueDetector`) and `AutoboxingStateCreationDetector` — so the whole run dies and none of the other checks report. No app source appears in any stack; it is a binary incompatibility between the Compose BOM 2025.06.00 lint artifacts and the AGP 8.7.3 lint API, the same class as the already-documented `NullSafeMutableLiveData` crash. Disabling detectors individually was tried and is whack-a-mole.
  Evidence: `app/build.gradle.kts:156-163` (existing workaround for the same failure class); crash stacks from `./gradlew :app:lintAnalyzeFullDebug --no-daemon`. Pre-existing: not introduced by any source change in this cycle, and not verified against the base commit because a clean lint run is what is broken.
  Touches: `gradle/libs.versions.toml` (Compose BOM / AGP), `app/build.gradle.kts` lint block.
  Acceptance: `./gradlew :app:lintFullDebug` completes and reports findings rather than aborting, with no blanket detector disables beyond the documented ones; the Definition-of-Done lint step is executable again. Most likely resolved by the AGP/compileSdk 36 bump below — verify there first.
  Complexity: M

- [ ] P1 — Split the N-1 blocker: AGP 8.9 + compileSdk 36 at targetSdk 35
  Why: `Roadmap_Blocked.md` blocks Media3, Coil, and OkHttp on the full AGP 9 / Gradle 9 / Kotlin 2.3 upgrade, but compileSdk 36 with targetSdk 35 is legal, triggers no Android 16 behavior change, and needs only an AGP 8.9.x-class bump on the current Gradle 8.12.1 / JDK 17 / Kotlin 2.1.0 stack. AGP 8.8+ is also the floor for the R8 core-count determinism fix any reproducibility claim depends on. **[Likely]** — the exact minimum AGP minor is the acceptance test.
  Evidence: `Roadmap_Blocked.md:39-58` ("AGP 8.7.3 max is 35") and `:32-33` (already cites AGP 8.9.0-rc01); `app/build.gradle.kts:73,87`; Media3 1.10.1+/Coil 3.5.0 `minCompileSdk 36`.
  Touches: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `gradle/verification-metadata.xml`, `Roadmap_Blocked.md` (rescope the Media3/Coil/OkHttp entries off N-1 onto this).
  Acceptance: compileSdk 36 with targetSdk 35 builds, `testDebugUnitTest`, `lintDebug`, and Roborazzi verify green; Media3 1.11.0 and Coil 3.5.0 resolve; no Android 16 behavior change is triggered; AGP 9 / Gradle 9 / Kotlin 2.4 stay in `Roadmap_Blocked.md`.
  Complexity: L

- [ ] P1 — Refresh the dependencies already available at compileSdk 35
  Why: Compose BOM 2026.06.01, Navigation 2.9.8, Paging 3.5.0, DataStore 1.2.1, kotlinx-coroutines 1.11.0, Firebase BoM 34.17.0, NewPipeExtractor v0.26.4, and Roborazzi 1.71.0 all clear compileSdk 35 and are blocked by nothing; Aura is 7 Roborazzi minors and 4 Firebase minors behind with no recorded reason.
  Evidence: `gradle/libs.versions.toml`; NewPipeExtractor v0.26.3 → v0.26.4 (2026-07-20); Compose BOM 2026.06.01 and Navigation 2.9.8 both resolve at `minCompileSdk 35`. Glance 1.2.0-rc01 is an orphaned RC — 1.2.0 never shipped stable.
  Modifies two entries in `Roadmap_Blocked.md`: the N-1 triad (`:25-26`) bundles "Compose BOM -> 2026.05.00" into the AGP 9 / Gradle 9 / Kotlin 2.3 upgrade, but Compose does not force a compileSdk bump and can move independently — remove it from N-1's scope. The "P2 Firebase BoM 34.13.0 -> 34.16.0" blocker (`:13-17`, "34.16.0 does not exist yet") is **resolved**: 34.17.0 is published, so that item moves back here.
  Touches: `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`, Roborazzi goldens, `Roadmap_Blocked.md`.
  Acceptance: each upgrade lands with unit tests, lint, and Roborazzi green; Compose BOM is struck from the N-1 scope line and the Firebase BoM item is deleted from `Roadmap_Blocked.md`; any library that cannot move records its blocker there instead; the Glance RC is resolved to a stable line or its risk is documented.
  Complexity: M

  Update 2026-08-20: current targets are Compose BOM 2026.08.00 (mesh gradients; pausable composition is on by default since BOM 2025.12.00), material3 1.4.0 stable (Expressive — adopt tokens selectively, the wholesale look conflicts with the design charter), NewPipeExtractor v0.26.5 (2026-08-15), Roborazzi 1.70.0, Firebase BoM 34.17.0 (published, confirmed). Glance 1.2.0 still never shipped stable; 1.2.0-rc01 remains the newest usable line.

- [ ] P1 — Bound yt-dlp downloads before writing and audit CVE-exposed flags
  Why: the two `YoutubeDL.execute` branches pass no `--max-filesize` and the size limit is enforced only after the file is fully written to `filesDir`, so a long video writes gigabytes then fails; separately, the bundled payload predates five 2026 yt-dlp advisories (four HIGH command-injection).
  Evidence: `VideoWallpapersViewModel.kt:706-735` vs `VideoWallpaperStorage.kt:137-155`; the OkHttp branches at `:743-766` are correctly capped; `youtubedl-android` 0.18.1 (2025-11-16); CVE-2026-55404, GHSA-69qj-pvh9-c5wg, CVE-2026-26331, CVE-2026-50574, CVE-2026-50023. Complements the device-blocked yt-dlp extraction item in `Roadmap_Blocked.md`.
  Touches: `VideoWallpapersViewModel.kt`, `VideoWallpaperStorage.kt`, `YouTubeRepository.kt`, `docs/security/ytdlp-cve-policy.json`.
  Acceptance: both yt-dlp branches pass an explicit size cap and the HLS path no longer needs 2× the file size; a gate asserts Aura passes none of `--exec`, `--write-link`, `--netrc-cmd`, or an aria2c downloader; the CVE policy doc records the audited flag set.
  Complexity: M

  Note 2026-08-20: the bundled 2026.07.04 payload post-dates every 2026 advisory fix (all fixed by 2026.06.09), so no emergency payload bump is needed — the size caps and the flag gate are the remaining work.

- [ ] P1 — Publish `WallpaperColors` from the live-wallpaper engines
  Why: none of the wallpaper services implement `onComputeColors()`, so the system derives Material You theming from nothing while an Aura live wallpaper is active — the most-reported complaint class across darkmodewallpaper and Muzei.
  Evidence: no `onComputeColors`/`WallpaperColors` anywhere in `app/src/main/java/com/freevibe/service/`; darkmodewallpaper #115/#203, Muzei #744; Aura already has `ColorExtractor`/`WallpaperPalette`.
  Touches: `VideoWallpaperService.kt`, `ParallaxWallpaperService.kt`, `WeatherWallpaperService.kt`, `ColorExtractor.kt`, settings toggle, soak contract test.
  Acceptance: each engine returns `WallpaperColors` derived from the current frame or source bitmap, recomputed on source change and not per frame; a setting suppresses publication for users who do not want launcher recoloring; the soak harness asserts no extra bitmap retention.
  Complexity: M

- [ ] P1 — Stop the wallpaper editor orphaning bitmaps and losing composed state
  Why: each filter render replaces `editedBitmap` without recycling the displaced one (up to ~67 MB at `MAX_EDIT_LONG_EDGE = 4096`, and an `OutOfMemoryError` catch already exists as evidence), any slider silently discards a composed depth portrait, and apply/export/parallax render from a snapshot captured before the coroutine launches.
  Evidence: `WallpaperEditorViewModel.kt:642-648`, `:214-216`, `:280-290`, `:661`, `:601-602` vs `:273-302`, `:240`, `:313`, `:341`, `:829-837`.
  Touches: `WallpaperEditorViewModel.kt`, editor tests.
  Acceptance: displaced bitmaps are recycled exactly once with no double-recycle; composing a depth portrait then moving a slider either preserves the composition or tells the user it was replaced; apply/export/parallax render from current state and the recycle helper matches the bitmaps it rendered.
  Complexity: M

- [ ] P1 — Ship a Rotation Health screen
  Why: auto-rotation silently stopping is the single most-reported failure across every competitor, and no app in the category exposes scheduler state; Backdrops paywalls the feature everyone ships broken.
  Evidence: WallYou #230/#239/#259/#266, WallFlow #85 (37 comments, open since 2024-03), darkmodewallpaper #196; Aura already ships an equivalent Video Battery Dashboard and a worker ledger.
  Touches: `SettingsDiagnosticsSection.kt`, `AutoWallpaperWorker.kt`, `DailyWallpaperWorker.kt`, `RotationTriggerService.kt`, `WorkInfo` diagnostics, string resources.
  Acceptance: one screen shows last fire time, next scheduled fire, WorkManager state and stop reason, boot-receiver-fired status, battery-optimization exemption status, and last error, with a test-fire action; values come from real `WorkInfo`, and the screen is covered by a production-composable state test.
  Complexity: M

  Note 2026-08-20: WorkManager 2.12.0-rc01 adds `WorkMetricsInfo` execution metrics and an event-listener API — adopt when stable to feed this screen real per-run data.

### P2

- [ ] P2 — Fix concurrent `load()`/`loadMore()` in VideoWallpapersViewModel
  Why: the warm-cache path clears `isLoading` while the network load is still in flight, so the screen's auto-fill effect starts a second job that queries every provider for the same page, and the first job then replaces `items` wholesale and discards everything the second appended.
  Evidence: `VideoWallpapersViewModel.kt:817-831,554-558,801-802,1042-1048,1103`; trigger at `VideoWallpapersScreen.kt:565-567`. Distinct from the delegate split in `Roadmap_Blocked.md`, which stays blocked — this is the bug that split would otherwise carry forward.
  Touches: `VideoWallpapersViewModel.kt`, `VideoWallpapersScreen.kt`, ViewModel tests.
  Acceptance: only one feed job runs at a time or pagination state advances atomically; a test drives a warm-cache cold start plus an immediate `loadMore` and asserts no duplicate provider requests and no lost appended items; `previewResolveInFlight` is cleared alongside `streamUrls` and `_resolvedIds` on reset.
  Complexity: M

- [ ] P2 — Give the Sound Editor gapless ringtone output
  Why: an OGG tagged `ANDROID_LOOP=true` loops gaplessly as an Android ringtone; without it every trimmed ringtone has a silence gap on repeat. Aura already exports OGG and never writes the tag, and no Android app — free or paid — currently ships loop-seam preview or a lossless cut.
  Evidence: no `ANDROID_LOOP` anywhere in `app/src/`; `AudioTrimmer.kt` always re-encodes through FFmpeg; losslesscut-android; HN 44935850 ("it's so dang hard to install a custom ringtone").
  Touches: `AudioTrimmer.kt`, `SoundEditorViewModel.kt`, `SoundEditorScreen.kt`, string resources, editor tests.
  Acceptance: OGG export writes `ANDROID_LOOP=true`; a loop-seam preview plays end→start on repeat; a lossless stream-copy cut mode is offered when no fade/normalize is applied and is verified byte-identical in the copied region.
  Complexity: M

- [ ] P2 — Make per-contact ringtones survive Do Not Disturb
  Why: Aura writes `CUSTOM_RINGTONE` but does nothing about DND or priority senders, so the feature is a no-op in the most common phone state; the widely-shared workaround (silent default + per-contact real ringtones) is a one-tap preset Aura could own.
  Evidence: no `INTERRUPTION_FILTER`, `NotificationManager.Policy`, or `isNotificationPolicyAccessGranted` anywhere in `app/src/main/java`; `ContactRingtoneService.kt`; SOSRing; HN 44935850.
  Touches: `ContactRingtoneService.kt`, `ContactPickerScreen.kt`, permissions/about section, string resources.
  Acceptance: assigning a contact ringtone detects DND, explains the interaction, and offers to mark the contact a priority sender via the platform flow; a "VIP-only ringing" preset sets a silent default plus chosen contacts; behavior when policy access is denied is explicit and tested.
  Complexity: M

- [ ] P2 — Strip Stability AI from the `foss` flavor and gate binary-update consent
  Why: the `foss` source set stubs only `com/google/*`, so the Stability AI integration compiles into the FOSS artifact and collides head-on with IzzyOnDroid's stated policy against apps for accessing generative-AI platforms; separately, both IzzyOnDroid and F-Droid require runtime binary downloads to be explicit opt-in with a stated warning.
  Evidence: `app/build.gradle.kts:98` (`STABILITY_AI_KEY` unconditional in `defaultConfig`); `app/src/foss/java/com/google/**` only; `YtDlpUpdateManager.kt:56-57` reachable from `SettingsViewModel.kt:517`; IzzyOnDroid App Inclusion Policy.
  Touches: `app/build.gradle.kts` source sets, `aigenerate/**`, `StabilityAiApi.kt`, `YtDlpUpdateManager.kt`, settings copy, `tools/fdroid_preflight.py`.
  Acceptance: the FOSS artifact contains no Stability AI code or key field and the AI entry point is absent from its UI; the yt-dlp update is opt-in with copy stating the user is bypassing repository checks; `fdroid_preflight.py` asserts both.
  Complexity: M

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
  Why: it is documented as a foreground service in both CLAUDE.md and ARCHITECTURE.md but is a plain `@Singleton` with an ad-hoc scope, so a long batch is killed when the process is backgrounded and `isRunning` is left true.
  Evidence: `BatchDownloadService.kt:41-44,74,113-116,127,140`; CLAUDE.md Key Files; ARCHITECTURE.md.
  Touches: `BatchDownloadService.kt`, manifest FGS declaration or a WorkManager migration, `docs/distribution/foreground-service-declaration.json`, CLAUDE.md, ARCHITECTURE.md.
  Acceptance: batch downloads either run as a declared foreground service or as WorkManager work that survives backgrounding, progress is recoverable after process death, and the docs match the implementation.
  Complexity: M

### P3

- [ ] P3 — Emit a CycloneDX SBOM from the resolved dependency graph
  Why: the EU Cyber Resilience Act requires a machine-readable SBOM of at least top-level dependencies from 2027-12-11; Aura's readiness doc defers this to N-1, but the CycloneDX Gradle plugin works on the current toolchain and reads the resolved graph, so the `commons-io`/`jackson`/`commons-compress` constraints appear correctly.
  Evidence: `docs/distribution/sbom-readiness.json` (`status: deferredUntilN1ToolchainUpgrade`, `futureSbomArtifacts`); `app/build.gradle.kts` constraints block; CycloneDX Gradle plugin.
  Touches: `app/build.gradle.kts` or a convention plugin, `tools/sbom_readiness_check.py`, release artifact bundle.
  Acceptance: a release task emits `SBOM.cyclonedx.json` covering the release runtime graph plus native payloads; the pinned constraint versions appear as resolved; the artifact is published with the release and checked by the bundle gate.
  Complexity: M

- [ ] P3 — Strengthen dependency verification with trusted PGP keys
  Why: `gradle/verification-metadata.xml` exists with 1364 components but sets `verify-signatures=false`, so it is checksum-only and must be rewritten on every version bump — which is why it drifts; trusted keys survive upgrades and Gradle now reports key rotation separately from new dependencies.
  Evidence: `gradle/verification-metadata.xml:4-5`; the file is also CRLF-in-index (see the byte-hygiene item); JitPack `NewPipeExtractor` and a prerelease `youtubedl-android` are exactly the risk profile verification exists for.
  Touches: `gradle/verification-metadata.xml`, `tools/gradle_wrapper_check.py` or a new verification gate.
  Acceptance: signature verification is enabled with trusted keys for signed artifacts and checksums retained only for unsigned ones; a clean-clone build verifies; the regeneration command is documented.
  Complexity: M

- [ ] P3 — Add a wallpaper position lock and launcher-parallax suppression
  Why: launcher-driven zoom and scroll parallax move applied wallpapers off the framing the user chose, and users explicitly ask for a lock; Aura's crop and editor work is undone by it.
  Evidence: WallYou #289 ("Force the wallpapers to be non-movable"), darkmodewallpaper #87 (14 comments), #218, WallFlow #25, doodle-android #93; `WallpaperApplier.kt`.
  Touches: `WallpaperApplier.kt`, live-wallpaper engines' `onOffsetsChanged`, settings toggle, string resources.
  Acceptance: an opt-in setting applies wallpapers sized so the launcher cannot pan or zoom them, live engines ignore offset changes when it is on, and the behavior is documented as launcher-dependent where the platform cannot guarantee it.
  Complexity: M


### Added 2026-08-11

Evidence, confidence labels, and sources in RESEARCH.md (2026-08-11 pass). Items verified
against v6.41.0 / versionCode 142 at `122d431`.

#### P1

- [ ] P1 — Detect and recover when Aura's live wallpaper is no longer active
  Why: Aura ships three `WallpaperService` implementations and never asks the system which wallpaper is running, so a service dropped after reboot, replaced by another app, or killed by an OEM manager is indistinguishable from a working one — the user sees a stock wallpaper and Aura's settings still read "on". Muzei reports this exact shape on Android 17 / Pixel 10 after reboot.
  Evidence: no `getWallpaperInfo` or `WallpaperInfo` anywhere in `app/src/main/java`; `VideoWallpaperService.kt`, `ParallaxWallpaperService.kt`, `WeatherWallpaperService.kt`; `RingtoneRestorationReceiver.kt` already proves the post-boot restoration pattern for sounds; Muzei #874 (16 comments, 2026-07-01). Build this into the Rotation Health screen tracked above rather than a second surface.
  Touches: `LiveWallpaperReceiptStore.kt`, `SettingsDiagnosticsSection.kt`, the three wallpaper services, `RingtoneRestorationReceiver.kt` (BOOT_COMPLETED pattern), string resources.
  Acceptance: Aura compares `getWallpaperInfo()?.packageName` against its own on resume and after `BOOT_COMPLETED`/`MY_PACKAGE_REPLACED`, records the result, and shows an explicit "your Aura live wallpaper is no longer active" state with a one-tap re-apply; the check never runs on a render thread; a test covers active, replaced-by-third-party, and static-wallpaper cases.
  Complexity: M

- [ ] P1 — Survive an app downgrade and prove the whole Room migration chain
  Why: the database is built with migrations and nothing else, so installing an older Aura APK — an ordinary Obtainium rollback, and README documents the `adb install -r` path — leaves Room unable to open the file and the app crashes on every launch, recoverable only by clearing app data, which destroys favorites, collections, and history. Separately, 15 migrations are declared and 2 are tested.
  Evidence: `AppModule.kt:241-250` (`addMigrations(*DatabaseMigrations.ALL_MIGRATIONS)` plus a foreign-keys callback, no downgrade handling); `DatabaseMigrations.kt` declares `MIGRATION_1_2` … `MIGRATION_15_16`; `DatabaseMigrationTest.kt` exercises only `migrate8To9` and `migrate14To16`. The exported-schema floor of 9 is deliberate policy (`room_schema_history_check.py --supported-export-start 9`) and is not what this item changes.
  Touches: `di/AppModule.kt`, `app/src/androidTest/java/com/freevibe/data/local/DatabaseMigrationTest.kt`, `tools/room_schema_history_check.py`.
  Acceptance: a downgrade opens the database without crashing and either migrates down or resets with an explicit user-visible warning and a pointer to backup/restore — never a silent wipe; a test builds a v1 database from the hand-written schema and runs the full 1→16 chain, plus each intermediate hop that has an exported schema; the gate fails when a declared migration has no test.
  Complexity: M

- [ ] P1 — Handle the Android 17 per-app memory limiter
  Why: Android 17 imposes a RAM-derived memory ceiling on **all** apps regardless of targetSdk, and Aura is the exact profile it targets — a 4096 px editor render path with a known bitmap-orphaning defect, a 64 MB apply ceiling, and three long-lived wallpaper engines holding bitmap layers. As of 2026-08-11, a limiter kill is indistinguishable from any other death, and the diagnostics bundle users paste into crash reports would not mention it.
  Evidence: developer.android.com/about/versions/17/behavior-changes-all — applies to all apps; detection via `ApplicationExitInfo.getDescription()` containing `MemoryLimiter:AnonSwap`; `CrashDiagnosticsCollector.kt:101` builds the bundle and reads no `ApplicationExitInfo`; `WallpaperEditorViewModel.kt` `MAX_EDIT_LONG_EDGE = 4096` and the orphaned-bitmap item tracked above.
  Touches: `CrashDiagnosticsCollector.kt`, `FreeVibeApp.kt`, `WallpaperEditorViewModel.kt`, `docs/support/crash-diagnostics.md`, tests.
  Acceptance: the diagnostics bundle reports the last `ApplicationExitInfo` reason and description, naming a memory-limiter kill explicitly when present; a memory-limiter exit is counted and surfaced in Diagnostics; the editor's peak allocation is measured and bounded against a recorded ceiling; `docs/support/crash-diagnostics.md` documents the new field.
  Complexity: M

- [ ] P1 — Make the wallpaper grid model stable and start measuring recomposition
  Why: `Wallpaper` carries two `List<String>` fields and no `@Immutable`, while `Sound` directly below it has the annotation. It is the model rendered in every cell of the busiest screens in the app, so the Compose compiler treats those items as unstable and recomposes them whenever a parent does — and with no compiler metrics configured, the cost is invisible. Aura already has Macrobenchmark to prove the delta.
  Evidence: `data/model/Models.kt:33-54` (`Wallpaper`, `tags: List<String>`, `colors: List<String>`, no annotation) vs `:58` (`Sound`, `@Immutable`); 10 `@Immutable`/`@Stable` in the whole codebase; no `composeCompiler { }` block in `app/build.gradle.kts`; `WallpapersScreen.kt` 1,848 lines, `VideoWallpapersScreen.kt` 1,605.
  Touches: `data/model/Models.kt`, `app/build.gradle.kts`, `baselineprofile/src/main/java/.../GridScrollBenchmark.kt`, a stability configuration file.
  Acceptance: `composeCompiler` emits metrics and reports to a build directory and a stability configuration file is checked in; `Wallpaper` and every other model rendered in a list is reported stable; `GridScrollBenchmark` frame timings are recorded before and after in the same commit; a gate fails when a list-rendered model is reported unstable.
  Complexity: M

- [ ] P1 — Retire FFmpeg from the sound editor using the platform media stack
  Why: FFmpeg and Python are the bulk of the 198 MB artifact, they force `useLegacyPackaging = true` (compressed `.so` extracted at install, roughly doubling on-device native storage and working against the uncompressed packaging 16 KB guidance asks for), and they are the reason a yt-dlp CVE treadmill reaches the *editing* path at all. Media3 now offers muxers and speed/pitch transforms that cover trim, convert, and speed without a native toolchain. This is the largest single lever on APK size and native-loader exposure.
  Evidence: `AudioTrimmer.kt` re-encodes every export through FFmpeg reached by reflection on youtubedl-android's static fields (ARCHITECTURE.md "External"); `app/build.gradle.kts:165-168` `useLegacyPackaging = true` with no comment, required by youtubedl-android's `extractNativeLibs` contract; `docs/distribution/native-alignment.json` lists `libffmpeg.zip.so` and `libpython.zip.so` for four ABIs; Media3 release notes for `OggMuxer`, `WavMuxer`, and `EditedMediaItem` speed with pitch preservation. Blocked until the tracked compileSdk 36 item lands — the Media3 releases carrying these need it.
  Touches: `AudioTrimmer.kt`, `SoundEditorViewModel.kt`, `app/build.gradle.kts` packaging, `tools/native_alignment_check.py`, `docs/distribution/native-alignment.json`.
  Acceptance: trim, convert, and speed run through the platform media stack with byte-comparable output on a fixture corpus; FFmpeg is retained only for the operations that genuinely require it, with each one named in the docs; if the video-crop path is the last FFmpeg consumer, that is recorded explicitly; APK size before and after is measured, and whether `useLegacyPackaging` can be turned off is answered either way.
  Complexity: XL

#### P2

- [ ] P2 — Restore validation-only CI
  Why: 82 Python gates, 81 pytest mirrors, ~940 JVM tests, three instrumented tests, and the Roborazzi suite all run only when a human remembers, which is how three unreleased versions and two 404 documents survived a release. Four security gates additionally report `"status": "ok", "workflowCount": 0` because they audit workflows that no longer exist. Validation CI is explicitly permitted; releasing binaries from CI is not, and must stay out.
  Evidence: `.github/` contains only `ISSUE_TEMPLATE/crash_report.yml`; all five workflows deleted in `ec73ea7` (2026-06-26); `tools/github_{actions_allowlist,security_workflow,workflow_permissions,workflow_secrets}_check.py` all report `workflowCount: 0` while 41 files still reference `.github/workflows`. Complements — does not replace — the tracked "gates assert published state" item.
  Touches: `.github/workflows/verify.yml`, `tools/github_actions_allowlist_check.py` and the three sibling gates, `docs/distribution/*.json` entries claiming `releaseWorkflowEnforced`.
  Acceptance: one workflow runs `assembleDebug`, `testDebugUnitTest`, `lintDebug` once lint is repaired, the pytest gate suite, and `verifyRoborazziFullDebug` on push and PR, and builds or publishes no release artifact; the four workflow-auditing gates fail on `workflowCount: 0` instead of passing; any policy file naming `releaseWorkflowEnforced` either points at a real mechanism or is corrected.
  Complexity: M

- [ ] P2 — Codify the design system as tokens and gate it
  Why: the "rectangular 4–12 dp radii, no pill / oval / fully-rounded backdrops" rule is written in ARCHITECTURE.md and CLAUDE.md and enforced by nothing — corner radii are literal numbers at 250+ call sites, and the rule is already broken in shipped code. It is the only major documented project rule with no gate behind it, in a repo with 82 gates.
  Evidence: `VideoWallpapersScreen.kt:884` uses `RoundedCornerShape(50)`, a full pill; `WallpapersScreen.kt:1268` uses 24 dp; 225 uses of `RoundedCornerShape(8)` plus strays at 1, 2, 4, 5, 6, 10, 12; `ui/theme/` contains only `Theme.kt` with colour tokens and no shape or spacing source; 102 hardcoded `Color(0x…)` literals across seven UI files; `test/tools/` has no design gate.
  Touches: `ui/theme/` (new shape and spacing token files), the seven UI files with colour literals, the two shape violations, a new `tools/design_token_check.py` and its test.
  Acceptance: shape and spacing tokens live in `ui/theme/` and the two violations are corrected or explicitly waived with a recorded reason; a gate rejects literal `RoundedCornerShape(n)` outside the token file and any radius above the documented ceiling; colour literals outside `Theme.kt` and the source-tone tables are rejected or registered; the gate fails when a pill radius is reintroduced.
  Complexity: M

- [ ] P2 — Surface the failures that currently reach the user as nothing
  Why: a cluster of independent silent failures on paths where the user has just tapped something and nothing else can tell them it did not work.
  Evidence: `VoteRepository.kt:407` — `onCancelled(error: DatabaseError) {}`, so a permission-denied or disconnect leaves stale votes with no log; seven `startActivity` calls in empty catches at `FreeVibeWidget.kt:352,381,407` (the widget has no other feedback channel), `ContactPickerScreen.kt:448`, `SoundDetailScreen.kt:564,582`, `WallpaperDetailScreen.kt:620-630`; `VideoWallpaperService.kt:126-134` and `:248-256` swallow display-metrics and `MediaMetadataRetriever` failures so stale or zero dimensions enter the scaling math. Distinct from the tracked "remaining service and editor reliability defects" item, which covers `RotationTriggerService`, `SoundEditorViewModel`, `VideoWallpapersViewModel`, and `VoteRepository.kt:208,342`.
  Touches: `VoteRepository.kt`, `FreeVibeWidget.kt`, `ContactPickerScreen.kt`, `SoundDetailScreen.kt`, `WallpaperDetailScreen.kt`, `VideoWallpaperService.kt`, string resources, tests.
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
  Touches: `FreeVibeApp.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/verification-metadata.xml`.
  Acceptance: debug builds install a thread policy (disk and network reads/writes) and a VM policy (leaked closables, activity leaks) that log rather than crash, and LeakCanary is a `debugImplementation` only; release and FOSS release artifacts contain neither, asserted by the APK scan; the existing known violations are enumerated in `CLAUDE.md` so new ones are distinguishable.
  Complexity: S

- [ ] P2 — Play more than one clip in the video live wallpaper
  Why: `VideoWallpaperService` plays exactly one video. A playlist with per-clip framing is the top-requested capability in the video-wallpaper category, and the whole rotation machinery Aura already owns — scheduler, day/night, collections — has no video equivalent.
  Evidence: no playlist or queue concept in `VideoWallpaperService.kt`; UndeadWallpaper v1.3.7 (per-clip zoom/offset/rotation/speed, shuffle, smart start); Lively #137 (25 comments) on condition-driven change. Depends on the tracked video-cache-bounding and main-thread-encode fixes; do those first.
  Touches: `VideoWallpaperService.kt`, `VideoWallpaperStorage.kt`, `PreferencesManager.kt`, video settings UI, the soak harness, string resources.
  Acceptance: an ordered or shuffled clip list advances at a configured boundary with no black frame at the seam; per-clip fit/crop and mute are preserved; the existing FPS cap, low-battery cap, and `onVisibilityChanged` pause govern the whole playlist, not just the first clip; total decoded storage stays bounded; the soak harness runs the playlist path and asserts nothing survives `onDestroy`.
  Complexity: L

  Note 2026-08-20: Media3 1.11.0 adds `ExoPlayer.setPreloadConfiguration()` and `DefaultPreloadManager` — the intended mechanism for the gapless seam. Sequence after the compileSdk 36 item, which unlocks Media3 1.10+.

- [ ] P2 — Make translation possible: locale config plus a contribution path
  Why: 1,690 strings are extracted, the pseudolocale and RTL gates are live, and the result is unreachable — `res/` has no `values-<locale>/` directory, the manifest declares no `localeConfig` so the Android 13+ per-app language picker cannot appear, and there is no documented way for a translator to contribute. The extraction work is done and is currently producing nothing.
  Evidence: `ls -d app/src/main/res/values*/` returns only `values/`; no `localeConfig` in `AndroidManifest.xml`; no Weblate, Crowdin, or Transifex configuration in the repo; `CONTRIBUTING.md` mentions locales only in a `Locale.ROOT` code-style note. Complements the tracked "residual runtime localization gaps" item, which covers the remaining hardcoded literals — including `MediaIngestion.kt:488-494`, which builds English `" or "` / `", or "` conjunctions in user-facing text.
  Touches: `AndroidManifest.xml`, `res/xml/locales_config.xml`, `CONTRIBUTING.md`, a hosting configuration, `tools/` gate.
  Acceptance: `android:localeConfig` is declared and lists every shipped locale; adding a `values-<locale>/` directory makes the language appear in Android's per-app language picker, verified on device or emulator; `CONTRIBUTING.md` documents how to submit a translation; a gate fails when a locale directory exists but is missing from the locale config, or vice versa.
  Complexity: M

- [ ] P2 — Give TalkBack announcements and a controlled reading order
  Why: the interactive-element audit recorded clean labels on 2026-08-11; the missing layer is *announcements*. Three `liveRegion` usages cover an app whose primary surfaces are async grids, a download queue, and audio playback, so a screen-reader user gets no notification when results arrive, a download finishes, or playback state changes. Reading order is entirely unmanaged.
  Evidence: `liveRegion` 3 occurrences, `heading` 7, `traversalIndex` 0, `isTraversalGroup` 0 across `app/src/main/java`; 48 `AuraStateCard` usages across 16 of 79 screen files show where async state transitions already exist and go unannounced.
  Touches: `SharedComponents.kt` (`AuraStateCard`), `DownloadsScreen.kt`, `SoundDetailScreen.kt`, the three feed screens, `app/src/androidTest/.../AccessibilityReleaseGateTest.kt`, `tools/accessibility_release_gate_check.py`.
  Acceptance: loading→ready, loading→error, and empty transitions announce politely once and do not re-announce on recomposition; download completion and playback state changes announce; feed sections are traversal groups with a defined order; the accessibility gate asserts a live region exists on each async surface it already covers.
  Complexity: M

#### P3

- [ ] P3 — Add UI test anchors
  Why: there are zero `testTag` modifiers in the entire main source tree, so there is nothing for a Compose UI test to attach to. The tracked "test production composables instead of look-alike route fixtures" item will hit this on its first day, and three `androidTest` files is the current ceiling.
  Evidence: `grep -rn "testTag" app/src/main/java` returns 0; `app/src/androidTest` holds three files; `AuraRouteStateScreenshotTest.kt` is the sole Roborazzi entry point.
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
  Evidence: `app/proguard-rules.pro:2-3` keeps `com.freevibe.data.remote.**` and all its members; `:9,25,26,29-32` do the same for `retrofit2`, `org.schabi.newpipe.extractor`, `org.mozilla.javascript`, `com.yausername`, `org.apache.commons.compress`, `org.apache.commons.io`; Retrofit, Moshi, and commons-* all ship consumer rules; Moshi KSP codegen needs only the generated adapters kept.
  Touches: `app/proguard-rules.pro`, release verification.
  Acceptance: each remaining keep names a class or a narrow member set with a comment stating what breaks without it; a release build passes the JVM suite, the Roborazzi suite, and a manual pass over every provider; dex method count and APK size before and after are recorded.
  Complexity: S

- [ ] P3 — Record the ML Kit dependency risk and decide a fallback
  Why: parallax wallpapers and smart crop both rest on a Play-services beta artifact published 2023-11-06 and never promoted to stable. If it is withdrawn, two advertised features stop working in the `full` flavor — and they are already absent from `foss`, which the README feature table does not mention, in the very artifact IzzyOnDroid would ship.
  Evidence: `app/build.gradle.kts:345-349` pins the `play-services-mlkit-subject-segmentation` beta as `fullImplementation` with a comment noting no bundled artifact exists; `SmartCropDetector.kt`, `ParallaxWallpaperService.kt`; `app/src/foss/java/com/google/mlkit/vision/segmentation/subject/SubjectSegmentation.kt` is a stub; README's feature table does not distinguish the flavors.
  Touches: `docs/distribution/` (a dependency-risk record), README feature table, `tools/fdroid_preflight.py`, `SmartCropDetector.kt`.
  Acceptance: a record names the artifact, its 2023 publish date, the two features that depend on it, and the chosen response if it is withdrawn; both features degrade visibly rather than silently when segmentation is unavailable, and a test covers that path; the README states which features the FOSS build omits; the preflight asserts the README statement matches the `foss` source set.
  Complexity: S

- [ ] P3 — Ship a haptic pattern alongside a ringtone
  Why: Android 16 added envelope-based vibration builders that describe amplitude and frequency curves and abstract away device capability. No app in this category — free or paid — pairs a custom vibration with a custom ringtone, and Aura already owns both the sound editor and the apply path. Gated on the tracked compileSdk 36 item.
  Evidence: no `VibrationEffect`, `BasicEnvelopeBuilder`, or `WaveformEnvelopeBuilder` anywhere in `app/src/main/java`; `SoundApplier.kt` and `ContactRingtoneService.kt` are the apply surfaces; developer.android.com custom-haptic-effects.
  Touches: `SoundApplier.kt`, `SoundEditorScreen.kt`, `ContactRingtoneService.kt`, `PreferencesManager.kt`, theme-pack recipe schema, string resources.
  Acceptance: a small preset set of vibration patterns can be previewed in the editor and stored with a sound; the pattern is applied where the platform allows and the limitation is stated where it does not; devices without envelope support fall back to a simple waveform and say so; patterns round-trip through theme-pack export and import.
  Complexity: M

- [ ] P3 — Claim the distribution and discovery surfaces that are currently empty
  Why: Aura is the highest-starred FOSS Android ringtone project under GitHub `topic:ringtone`, a topic that is nearly empty, and the F-Droid ringtone shelf holds one abandoned fork. It is on no awesome-list, and `offa/android-foss` has a one-entry wallpaper section and no live-wallpaper or ringtone section at all. This is the cheapest reach available and it needs no code.
  Evidence: `offa/android-foss` wallpaper section lists one app; `vvolas/Awesome-Live-Wallpaper` is Android-specific and dead since 2016; `w3teal/awesome-ringtone` does not list Aura; F-Droid's RFP queue shows live unserved wallpaper demand. Depends on the two P0 items above — a submission that links a 404 contributing guide or resolves to a stale release is worse than none.
  Touches: no app code; README topics, external PRs, `docs/distribution/channel-strategy.md`.
  Acceptance: `docs/distribution/channel-strategy.md` records which lists were submitted to and when, with links; GitHub topics are set; submissions happen only after the current release is published and the documentation links resolve; the IzzyOnDroid decision recorded in the open questions is settled before an inclusion request is filed.
  Complexity: S

### Additional Research-Driven Additions (2026-08-11)

#### P2

- [ ] P2 — Preflight live-wallpaper capability and provide a truthful static fallback
  Why: `AndroidManifest.xml:37-40` marks live wallpaper optional, but `LiveWallpaperLauncher.kt:15-35` only tries direct/chooser intents and reports a generic failure; `WallpaperApplier.isSupported()` covers static wallpaper operations, not live-wallpaper feature/service availability. This is distinct from the existing P1 item that detects a live wallpaper that was active and later disappeared.
  Evidence: `AndroidManifest.xml:37-40`, `LiveWallpaperLauncher.kt:15-35`, `WallpaperApplier.kt:225-228`; Android `WallpaperManager`/live-wallpaper APIs; the UndeadWallpaper community thread consulted on 2026-08-11 reports OEM devices that disable live wallpapers.
  Touches: `LiveWallpaperLauncher.kt`, video/parallax entry points, `VideoWallpapersScreen.kt`, `WallpaperDetailScreen.kt`, capability tests, strings.
  Acceptance: before launch, Aura checks `PackageManager.FEATURE_LIVE_WALLPAPER`, resolves the requested service and action, and distinguishes unsupported, unavailable, and security-denied states; image sources offer static apply when valid, video-only sources explain the limitation; no path claims success after an unresolvable intent; tests cover no feature, missing service, security failure, and static fallback.
  Complexity: S

- [ ] P2 — Make direct media downloads validator-aware and resumable
  Why: `DownloadManager.downloadFile()` always issues an unconditional GET, starts a new temp file at byte zero, and deletes it after interruption; `DownloadProgress` is process-local and `DownloadEntity` stores only completed MediaStore rows. Size caps prevent oversized writes but do not prevent a mobile user from paying for the same interrupted 64 MiB transfer repeatedly. This complements, rather than duplicates, the existing BatchDownloadService item: that item fixes job lifetime, while this one fixes per-file transport.
  Evidence: `app/src/main/java/com/freevibe/service/DownloadManager.kt:114-185`, `app/src/main/java/com/freevibe/data/model/Models.kt:150-159`; RFC 9111 sections on incomplete/partial responses and validation; OkHttp’s cache/client API; cssnr/remote-wallpaper-android issue #26 requesting HTTP caching.
  Touches: `DownloadManager.kt`, `Models.kt`, `Database.kt`/Room migration, `DownloadEntity`/DAO, `DownloadsScreen.kt`, transport tests with a local HTTP server, cleanup/diagnostics.
  Acceptance: a stable download identity persists temp path, URL, byte count, size, and ETag/Last-Modified when available; retries send `Range` plus `If-Range` only with a matching validator and accept continuation only for a valid `206`; `200`, validator mismatch, range mismatch, or changed length safely truncates and restarts; completion remains temp-then-atomic MediaStore publication; process death resumes or clearly marks a recoverable failure; size/sniffing caps apply to the aggregate bytes; tests cover 206 resume, 200 restart, 412/validator change, cancellation, stale-temp cleanup, and no duplicate MediaStore rows.
  Complexity: M

### Added 2026-08-20

Evidence and confidence labels in RESEARCH.md (2026-08-20 pass). Items verified against v6.41.0 / versionCode 142 at `070d9a8` plus the uncommitted working tree.

#### P2

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

#### P3

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
