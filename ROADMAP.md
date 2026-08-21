# Aura Roadmap

Actionable work only. Historical and completed roadmap material is archived in CHANGELOG.md; blocked work is kept in Roadmap_Blocked.md.

## Actionable Items

## Research-Driven Additions

Added 2026-08-10. See RESEARCH.md for evidence and confidence labels.

### P0

### P1

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

- [ ] P1 — Retire FFmpeg from the sound editor using the platform media stack
  Why: FFmpeg and Python are the bulk of the 198 MB artifact, they force `useLegacyPackaging = true` (compressed `.so` extracted at install, roughly doubling on-device native storage and working against the uncompressed packaging 16 KB guidance asks for), and they are the reason a yt-dlp CVE treadmill reaches the *editing* path at all. Media3 now offers muxers and speed/pitch transforms that cover trim, convert, and speed without a native toolchain. This is the largest single lever on APK size and native-loader exposure.
  Evidence: `AudioTrimmer.kt` re-encodes every export through FFmpeg reached by reflection on youtubedl-android's static fields (ARCHITECTURE.md "External"); `app/build.gradle.kts:165-168` `useLegacyPackaging = true` with no comment, required by youtubedl-android's `extractNativeLibs` contract; `docs/distribution/native-alignment.json` lists `libffmpeg.zip.so` and `libpython.zip.so` for four ABIs; Media3 release notes for `OggMuxer`, `WavMuxer`, and `EditedMediaItem` speed with pitch preservation. Blocked until the tracked compileSdk 36 item lands — the Media3 releases carrying these need it.
  Touches: `AudioTrimmer.kt`, `SoundEditorViewModel.kt`, `app/build.gradle.kts` packaging, `tools/native_alignment_check.py`, `docs/distribution/native-alignment.json`.
  Acceptance: trim, convert, and speed run through the platform media stack with byte-comparable output on a fixture corpus; FFmpeg is retained only for the operations that genuinely require it, with each one named in the docs; if the video-crop path is the last FFmpeg consumer, that is recorded explicitly; APK size before and after is measured, and whether `useLegacyPackaging` can be turned off is answered either way.
  Complexity: XL

  Update 2026-08-20: per-ABI splits landed and measured 199 MB universal → 60.5 MB arm64-v8a, 54.2 MB armeabi-v7a, 59.2 MB x86, 63.4 MB x86_64. That is a 3.3× cut but still twice IzzyOnDroid's 30 MB per-APK ceiling, so **this item is now the only remaining lever on store eligibility** — the residual bulk is the FFmpeg and Python payload and nothing else. Splitting was never going to reach 30 MB alone; the measurement is recorded in `docs/distribution/native-alignment.json` under `abiSplitEvidence`.

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
