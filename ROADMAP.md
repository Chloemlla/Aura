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

- [ ] P0 — Tag and publish v6.40.0; gate on release existing
  Why: CHANGELOG documents v6.39.0 and v6.40.0 (2026-07-29) but `git tag` and `gh release list` stop at v6.38.1, so no user has the bounded archive extraction, the automation-gate fix, or the apply coordinator; Obtainium reads GitHub Releases.
  Evidence: `CHANGELOG.md:5,28`; `gh release list` latest = v6.38.1 (2026-07-29); `obtainium.json`; commit "Remove GitHub Actions workflows — local builds only".
  Touches: release build + signing, `tools/release_artifact_bundle_check.py`, `tools/release_manifest.py`, a new tag/release gate.
  Acceptance: v6.40.0 is tagged and released with the signed universal APK and `SHA256SUMS.txt`; a gate fails when `versionName` in `app/build.gradle.kts` has no matching git tag and published release.
  Complexity: S

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
  Note: per-ABI splits are the fix; **dropping `armeabi-v7a` is a separate, user-facing decision**, because `minSdk 26` still admits 32-bit-only Android 8–9 devices. Splits cut the download without cutting those users. Either resolve `require64BitOnly: true` to match reality (32-bit shipped and supported) or record an explicit decision to drop 32-bit — today the policy and the artifact disagree and the gate cannot tell.
  Acceptance: release output is per-ABI plus a universal APK, and the arm64-v8a artifact is under 30 MB; `native_alignment_check.py` verifies the *declared* ABI set against the APK and fails on any mismatch in either direction, instead of skipping non-64-bit libraries; `obtainium.json`'s `autoApkFilterByArch` is confirmed against the split asset names; `docs/distribution/native-alignment.json` no longer claims `releaseWorkflowEnforced` for a workflow that does not exist.
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

- [ ] P1 — Bound yt-dlp downloads before writing and audit CVE-exposed flags
  Why: the two `YoutubeDL.execute` branches pass no `--max-filesize` and the size limit is enforced only after the file is fully written to `filesDir`, so a long video writes gigabytes then fails; separately, the bundled payload predates five 2026 yt-dlp advisories (four HIGH command-injection).
  Evidence: `VideoWallpapersViewModel.kt:706-735` vs `VideoWallpaperStorage.kt:137-155`; the OkHttp branches at `:743-766` are correctly capped; `youtubedl-android` 0.18.1 (2025-11-16); CVE-2026-55404, GHSA-69qj-pvh9-c5wg, CVE-2026-26331, CVE-2026-50574, CVE-2026-50023. Complements the device-blocked yt-dlp extraction item in `Roadmap_Blocked.md`.
  Touches: `VideoWallpapersViewModel.kt`, `VideoWallpaperStorage.kt`, `YouTubeRepository.kt`, `docs/security/ytdlp-cve-policy.json`.
  Acceptance: both yt-dlp branches pass an explicit size cap and the HLS path no longer needs 2× the file size; a gate asserts Aura passes none of `--exec`, `--write-link`, `--netrc-cmd`, or an aria2c downloader; the CVE policy doc records the audited flag set.
  Complexity: M

- [ ] P1 — Publish `WallpaperColors` from the live-wallpaper engines
  Why: none of the wallpaper services implement `onComputeColors()`, so the system derives Material You theming from nothing while an Aura live wallpaper is active — the most-reported complaint class across darkmodewallpaper and Muzei.
  Evidence: no `onComputeColors`/`WallpaperColors` anywhere in `app/src/main/java/com/freevibe/service/`; darkmodewallpaper #115/#203, Muzei #744; Aura already has `ColorExtractor`/`WallpaperPalette`.
  Touches: `VideoWallpaperService.kt`, `ParallaxWallpaperService.kt`, `WeatherWallpaperService.kt`, `ColorExtractor.kt`, settings toggle, soak contract test.
  Acceptance: each engine returns `WallpaperColors` derived from the current frame or source bitmap, recomputed on source change and not per frame; a setting suppresses publication for users who do not want launcher recoloring; the soak harness asserts no extra bitmap retention.
  Complexity: M

- [ ] P1 — Fix Settings preference write ordering and gate it
  Why: five DataStore→SharedPreferences bridges write in the opposite order to the rule codified with rationale in `PreferencesManager`, and the consumers read SharedPreferences only — so leaving Settings mid-write strands the live wallpaper on the old value while the toggle reads as changed.
  Evidence: `SettingsViewModel.kt:928-932,933-938,939-943,944-948,959-964` vs `PreferencesManager.kt:510-517`; consumers at `WeatherWallpaperService.kt:204,230,256,261`.
  Touches: `PreferencesManager.kt`, `SettingsViewModel.kt`, new contract gate + test.
  Acceptance: every SharedPreferences bridge lives in `PreferencesManager` and writes SharedPreferences first; a gate forbids `getSharedPreferences` in `ui/screens/settings/`; a cancellation test proves the runtime value survives.
  Complexity: S

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

- [ ] P2 — Turn on Gradle build performance flags
  Why: `gradle.properties` sets only `-Xmx2048m` — no build cache, no parallel, no configuration cache — on a workstation where CLAUDE.md already records Gradle runs exhausting memory.
  Evidence: `gradle.properties` (4 lines); Gradle 8.12.1 supports all three. Isolated Projects is deliberately excluded — incubating in 9.7 and not recommended for production.
  Touches: `gradle.properties`, a clean-build timing note.
  Acceptance: `org.gradle.caching`, `org.gradle.parallel`, and `org.gradle.configuration-cache` are enabled with any incompatible task recorded; `assembleDebug` and `testDebugUnitTest` pass from a clean and a warm cache; before/after timings are recorded.
  Complexity: S

- [ ] P2 — Generate README and CLAUDE.md version facts from the release manifest
  Why: `tools/release_manifest.py` already emits `roomSchemaVersion: 16` and `versionName: 6.40.0`, but README and CLAUDE.md still claim Room v14, and the consistency gate passes because it never reads them.
  Evidence: `tools/release_manifest.py` output; `README.md:178`; `CLAUDE.md:244,330`; `tools/release_metadata_consistency_check.py` → `status: ok`.
  Touches: `tools/release_metadata_consistency_check.py`, README, CLAUDE.md, `docs/distribution/release-metadata-consistency.json`.
  Acceptance: schema version, versionName, versionCode, and tab/navigation claims in README are checked against the manifest; the gate fails on drift; the current Room v14 claims are corrected to v16.
  Complexity: S

- [ ] P2 — Bring fastlane metadata up to the shipped version
  Why: `changelogs/` stops at `8.txt` against versionCode 141 and there is no `images/` directory, so the IzzyOnDroid metadata requirement cannot be met even before screenshots exist.
  Evidence: `fastlane/metadata/android/en-US/changelogs/` (highest `8.txt`); no `images/`; IzzyOnDroid App Inclusion Policy. The screenshot/feature-graphic capture itself stays blocked in `Roadmap_Blocked.md`.
  Touches: `fastlane/metadata/android/en-US/**`, `tools/store_metadata_preflight.py`, release checklist.
  Acceptance: a changelog exists for the current versionCode and is generated from CHANGELOG at release time; the icon is in place; the preflight fails when the current versionCode has no changelog entry.
  Complexity: S

- [ ] P2 — Close the residual manifest and intent hardening gaps
  Why: an empty `network_security_config.xml` makes the manifest's `usesCleartextTraffic="false"` inert, leaving only the platform default; and `ACTION_ATTACH_DATA` accepts `intent.data` with any scheme while the adjacent launch path enforces HTTPS-only.
  Evidence: `res/xml/network_security_config.xml` (`<network-security-config />`), referenced from `AndroidManifest.xml:60` with `usesCleartextTraffic="false"` at `:64`; `MainActivity.kt:144-166` vs `isAllowedLaunchUrl` at `:93-101`.
  Touches: `network_security_config.xml`, `MainActivity.kt`, `tools/cleartext_release_check.py`, tests.
  Acceptance: the config declares an explicit `base-config` with cleartext disabled and the gate asserts it; `ACTION_ATTACH_DATA` accepts only `content://` URIs whose read grant is held, rejecting `file://` and unknown authorities with user-visible feedback.
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

