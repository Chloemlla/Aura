# Aura — Blocked Roadmap Items

> Items moved here from ROADMAP.md because they are blocked on external dependencies:
> owner/Firebase Console actions, physical device testing, production database access,
> or the N-1 toolchain upgrade gate.
>
> Move items back to ROADMAP.md when the blocker is resolved.

---

## Blocker: Unreleased External Dependency

- **P2 — Firebase BoM 34.13.0 -> 34.16.0 (full flavor)**
  - Blocker: Firebase's official Android release notes currently publish BoM 34.15.0;
    the requested 34.16.0 release does not exist yet.
  - Resume when 34.16.0 is published, then run the full-flavor build and community-path
    tests while confirming the FOSS flavor remains unaffected.

---

## Blocker: N-1 Toolchain Upgrade (AGP 9 / Gradle 9 / Kotlin 2.3)

N-1 itself is the largest single gate. Until it lands, these items cannot proceed:

- **N-1 — Toolchain upgrade triad** (AGP 9 + Gradle 9 + Kotlin 2.3 + Compose BOM + Hilt 2.59)
  - Scope: AGP 8.7.3 -> 9.2.x, Gradle 8.12 -> 9.5+, Kotlin 2.1.0 -> 2.3.20, KSP1 -> KSP2, Compose BOM -> 2026.05.00, Hilt 2.53.1 -> 2.59.x, Navigation 3.x, etc.
  - Risk: Memory-heavy Gradle runs on this workstation. R8 keep-rule regressions, KSP2 cache issues.
  - Gates: N-3/N-4/NX-2/NX-7 and most Next-tier items.

- **N-4 (remaining)** — WallpaperDescription scaffolding is comment-only until N-1 unlocks compileSdk 36+.

- **P0 — API 37 toolchain and target-SDK release gate** (Cycle 10)
  - Needs compileSdk 37 + AGP 8.9.0-rc01+ minimum. Blocked until N-1 completes.

- **P2 — Direct Android 17 API cleanup for shipped bridges** (Cycle 10)
  - EyeDropper and Photo Picker 9:16 shipped through reflection; direct API needs compileSdk 37.

- **P2 — OkHttp 5.3.2 -> 5.4.0**
  - Blocker: `com.squareup.okhttp3:okhttp-android:5.4.0` requires compileSdk 36 or later,
    while Aura is on compileSdk 35 and AGP 8.7.3 (whose supported maximum is 35).
  - Evidence: `:app:checkFullDebugAarMetadata` fails on the dependency's published AAR
    metadata before compilation. Resume with N-1, then re-run RateLimitInterceptor,
    redirect, Full/FOSS build, and dependency-verification checks.

- **P1 — Coil 3.4.0 -> 3.5.0**
  - Blocker: `io.coil-kt.coil3:coil-*:3.5.0` requires compileSdk 36 (AGP 8.7.3 max is 35).
  - Evidence: `:app:checkFullDebugAarMetadata` lists coil-android/coil-compose/coil-gif/
    coil-network-okhttp 3.5.0 as requiring compileSdk >= 36. Aura shipped Coil 3.4.0 (the
    compileSdk-35 ceiling) with `memoryCacheMaxSizePercentWhileInBackground` enabled; resume
    the 3.5.0 bump after N-1 (3.5.0 also makes that background-cap API stable).

- **P1 — Media3 1.9.4 -> 1.10.1**
  - Blocker: `androidx.media3:*:1.10.1` requires compileSdk 36 (AGP 8.7.3 max is 35).
  - Evidence: `:app:checkFullDebugAarMetadata` lists media3-exoplayer/-ui/-session/-common
    1.10.1 as requiring compileSdk >= 36. Aura shipped Media3 1.9.4 (the compileSdk-35 ceiling)
    with `experimentalSetDynamicSchedulingEnabled(true)` on the video players; resume the
    1.10.1 bump (Compose player composables) after N-1.

- **P2 — Video wallpaper playlists and per-video behavior profiles** (Cycle 1)
  - Depends on NX-1 GL/AGSL/ExoPlayer engine migration, which itself depends on N-1.

- **P3 — Missing integration test execution in CI** (Next audit findings)
  - Instrumented tests need CI infrastructure that N-1 build verification would establish.

- **P1 — Room 2.8.x persistence refresh**
  - Blocker: Room 2.8.4 KSP failed locally with `AbstractMethodError` in Room's kotlinx-serialization bundle serializer under Kotlin 2.1.0 / KSP 2.1.0-1.0.29.
  - Current state: Aura is on Room 2.7.2 to satisfy WorkManager 2.11.2 without taking the larger Kotlin/KSP/toolchain upgrade.
  - Resume when N-1 upgrades Kotlin/KSP. Acceptance remains: all schema versions migrate cleanly, KSP succeeds with Kotlin codegen, and favorites/downloads/collections behavior is unchanged.

### N-1-gated Next items (NX)

- **NX-1** — GL/AGSL live wallpaper engine migration (Media3 ExoPlayer + AGSL pipeline)
- **NX-2** — Lockscreen depth (subject-aware clock-tuck + lockscreen Glance widgets)
- **NX-4** — SelectedContentHolder removal (nav-graph-scoped ViewModel + Navigation 3)
- **NX-5** — Plugin/source ABI (Muzei-compatible "Aura Sources")
- **NX-7** — Favorites sync via Firestore + Google sign-in
- **NX-9** — Media3 1.10 Material3 playback composables
- **NX-13** — Predictive-back wiring (remaining 14 screens need Navigation 3)

---

## Blocker: Firebase Console / Owner Actions

These items have code shipped but require Firebase Console access, production RTDB access, or owner-only actions to complete:

- **P1 — Register Aura for Android developer verification**
  - Install guidance and the register-vs-abstain decision record are complete in
    `docs/distribution/developer-verification.md`.
  - Remaining: the release owner must complete identity verification in Android
    Developer Console, register `com.freevibe`, prove ownership with the existing
    release signing key, and confirm the package/key status before changing release
    notes from `owner-confirmation-required`.

- **N-2 (remaining)** — Firebase BoM 34 + Custom Claims admin path
  - Code + rules shipped. Remaining: deploy `database.rules.json` + grant Custom Claims to existing admins in Firebase Console.

- **P0 — Add Firebase App Check for community writes** (Cycle 1)
  - Code shipped (debug/release providers, callable handlers, Android clients, emulator tests).
  - Remaining: Firebase Console App Check registration, debug-token registration, metrics burn-in, direct-rule tightening, RTDB/Storage enforcement.

- **P1 — Source provenance panel + community report queue** (Cycle 1)
  - Code shipped (report dialogs, admin queue, callable handlers, Android clients).
  - Remaining: live callable invocation evidence, deploy evidence, direct-rule tightening.

- **P0 — Firebase rules test and deploy harness** (Cycle 9)
  - Emulator tests, CI wiring, and runbook shipped.
  - Remaining: run and archive a real production-project dry run after owner access is confirmed.

- **P0 — Community owner field normalization** (Cycle 9)
  - Owner field, indexes, Storage rules, emulator tests, delete actions shipped.
  - Remaining: legacy backfill against production RTDB export.

- **P1 — Storage rules and orphan lifecycle cleanup** (Cycle 9)
  - Storage rules, emulator tests, lifecycle policy, orphan report tool shipped.
  - Remaining: run real exported Storage/RTDB orphan reports after owner access.

- **P1 — App Check and community abuse throttling** (Cycle 9)
  - Providers, quota policies, callable handlers, Android clients shipped.
  - Remaining: Firebase Console metrics/enforcement evidence.

- **P1 — Moderation report queue and audit trail** (Cycle 9)
  - Report intake, admin queue, block system, callable handlers shipped.
  - Remaining: full callable protocol coverage, owner-approved deploy evidence.

- **P2 — Community backend operations runbook** (Cycle 9)
  - Manifest, CI gate, runbook shipped.
  - Remaining: takedown SLA packet, owner/admin deletion verification evidence, live orphan report.

- **P0 — Community upload public-data lifecycle and deletion workflow** (Cycle 4)
  - Owner indexes, delete methods, Storage rules, deletion tombstones shipped.
  - Remaining: public request copy and live backfill evidence after owner access confirmed.

- **P0 — Accountless community deletion contract** (Cycle 12)
  - Full toolchain shipped (deletion planner, lookup, review, simulator, executor, receipts, web intake, local cleanup, Auth packages).
  - Remaining: actual owner-run Auth/upload deletion and hosted URL publication.

- **P0 — Firebase deletion orchestrator and web request runbook** (Cycle 12)
  - Tooling shipped. Remaining: trusted production dry run, web request page publication.

- **P1 — Owner indexes and Storage deletion handles** (Cycle 12)
  - New upload handling shipped. Remaining: run planner against fresh production RTDB export.

- **P1 — Vote, follow, profile, and moderation deletion semantics** (Cycle 12)
  - Policy, tooling, block system, callable handlers shipped.
  - Remaining: owner-approved production execution evidence, hosted URL publication.

- **P2 — Community data receipt/export surface** (Cycle 12)
  - Identity panel, deletion code, toolchain shipped.
  - Remaining: owned upload IDs, follow/vote counts, export output, live hosted URL.

- **P0 — IP takedown/report queue** (Cycle 13)
  - Report intake, admin actions, block system, callable handlers shipped.
  - Remaining: full callable protocol coverage, owner-approved deploy evidence.

- **P1 — Community upload rights attestation** (Cycle 13)
  - License chips, rights confirmation, source URL, detail display shipped.
  - Remaining: legacy/backfill coverage and callable upload finalization.

---

## Blocker: Physical Device / Emulator

These items require adb-connected device or Android 17 emulator testing:

- **P1 — Android 16 job-quota device evidence**
  - Source audit, complete worker ledger, WorkInfo stop-reason diagnostics, and the Android 16 capture packet are implemented.
  - Remaining: capture TOP-started and foreground-service-concurrent quota behavior on a connected Android 16+ device, including compat overrides, jobscheduler/services output, copied support bundle, and override reset evidence.

- **P0 — yt-dlp stable-channel live extraction validation**
  - The official 2026.07.04 payload, SHA-256 policy gate, packaged-APK proof, minimum-version guard, and rollback/validation unit tests shipped in v6.36.0.
  - Remaining: exercise the stable update plus a real YouTube extraction on an installable device build and confirm Settings reports the validated active version.
  - Blocker: the connected phone has Aura signed by a different key, so installing this build would require uninstalling the user's app/data; foreground device automation is not permitted during this session.

- **P1 — YouTube PO-token live-provider validation**
  - The reviewed bgutil 1.3.1 plugin, SHA-256 install guard, credential-free HTTPS provider setting, yt-dlp request options, explicit extractor failover, and degraded Sounds state shipped in v6.36.0.
  - Remaining: configure a reachable self-hosted HTTPS bgutil endpoint and prove search/playback on a video that fails without a PO token.
  - Blocker: no external provider endpoint is configured, and the connected phone has Aura signed by a different key; replacing it would require uninstalling the user's app/data.

- **P1 — Video SurfaceView BufferQueue device validation**
  - Saved Android 16 logs identified PlayerView zoom resizing a decoded 1280x720 stream to a 4117x2316 SurfaceView, followed by Qualcomm output-port configuration failures and two concurrent BufferQueue timeout streams. v6.36.0 now keeps both surfaces at fixed view bounds, moves crop scaling into the codec, and stops the feed player before immersive playback begins.
  - Remaining: capture two minutes of Videos feed and immersive playback logcat on an installable device build and confirm there is no sustained `dequeueBuffer` timeout or codec-config failure stream.
  - Blocker: the connected phone has Aura signed by a different key, so installing this build would require uninstalling the user's app/data; foreground device automation is not permitted during this session.

- **P1 — Media3 1.8.0 playback device validation**
  - ExoPlayer, HLS, sessions, and UI dependencies resolve and build against compileSdk 35, with the full JVM, lint, APK, and Roborazzi matrix green.
  - Remaining: smoke sound playback, video preview, immersive paging, HLS playback, and video wallpaper apply on an installable device build.
  - Blocker: the connected phone has Aura signed by a different key, so installing this build would require uninstalling the user's app/data; foreground device automation is not permitted during this session.

- **P1 — Baseline Profile + Macrobenchmark** (Cycle 1)
  - Harness shipped 2026-06-04. Remaining: physical-device profile generation + metrics comparison.
  - Blocker: `adb devices` returns no attached devices.

- **P1 — Android 17 Contact Picker** (Cycles 4/10)
  - Permission minimization shipped. Remaining: API 37 picker smoke + clear-ringtone validation after Android 17 toolchain.

- **P1 — 200% font, display-size, and contrast audit** (Cycle 5)
  - Needs manual screenshots at 200% font, Accessibility Scanner contrast/touch-target results.

- **P2 — Widget and live-wallpaper accessibility/localization coverage** (Cycle 5)
  - Needs widget actions TalkBack pass, keyguard placement, launcher picker inspection.

- **P0 — Store listing metadata preflight** (Cycle 8) (remaining)
  - Text validation shipped. Remaining: screenshot/feature-graphic asset requirement.

- **P1 — Screenshot and feature-graphic pipeline** (Cycle 8)
  - Framework shipped. Remaining: actual 4+ phone screenshots and feature graphic capture.

- **P1 — Android 17 large-screen/adaptive-layout smoke** (Cycle 10)
  - Needs tablet/foldable/landscape emulator screenshots.

- **P1 — Android 17 background-audio hardening regression suite** (Cycle 10)
  - Needs device logcat/dumpsys checks for AudioHardening entries.

- **P1 — Target-37 privacy/security compatibility preflight** (Cycle 10)
  - Needs Android 17 network smoke across all providers.

- **P1 — Rotation trigger reliability tests** (Cycle 14)
  - Needs device testing under various standby buckets, expedited quota exhaustion.

- **P1 — Background network and data-saver posture** (Cycle 14)
  - Remaining: run and archive the real capture packet on device.

- **P2 — Battery/vitals regression lab** (Cycle 14)
  - Needs physical device dumpsys/batterystats outputs.

---

## Blocker: Content Curation / Human Judgment

- **N-5 (remaining)** — Aura Originals bundled CC0 sound pack
  - Infrastructure + manifest schema shipped. Remaining: moderator review pass to curate 200-500 CC0 sound entries into `assets/aura_originals_manifest.json`.

- **P3 — Fate of the four orphaned legacy sound repositories** (Freesound/Audius/CcMixter/SoundCloud)
  - Blocker: these repository classes are confirmed orphaned (no references outside their own
    packages), but CLAUDE.md records a deliberate owner decision to keep them "for old saved
    metadata and future compatibility." Deleting them overrides that documented decision;
    re-wiring one as an opt-in source is a product/scope call. Both directions need owner
    judgment, not an autonomous edit. Resolve by either confirming deletion or picking a source
    to re-wire, then move back to ROADMAP.md.

---

## Blocker: Dependent on Other Blocked Items

- **P2 — Source deletion and takedown reconciliation** (Cycle 3)
  - Room metadata, UI badges, gone classifier shipped.
  - Remaining: provider catalog reload pruning (needs callable/deploy) + community moderation integration (needs report queue deploy).

- **P2 — Source-deleted and rights-revoked local states** (Cycle 13)
  - Unavailable-source state and remote-gone classifier shipped.
  - Remaining: provider catalog pruning tied to deploy/callable work.

- **P1 — Preserve item-level license and provenance through durable flows** (Cycle 17)
  - Entity fields and export preservation shipped.
  - Remaining: action-capability fields (restrict download/share/edit based on license terms) need design + Room migration + UI gating.

- **P3 — Tag OkHttp calls for source diagnostics after the next OkHttp upgrade**
  - Current state: Aura exposes `SourceMetrics`, but request-purpose attribution is still spread across repositories.
  - Blocker: roadmap item explicitly depends on newer OkHttp call-tag/interceptor behavior after the next OkHttp upgrade.
  - Resume when OkHttp is upgraded beyond the current pinned stack and repository request builders can be updated/tested together.

---

## Roadmap file hygiene

`ROADMAP.md` is actionable only. Blocked work stays in this file, and duplicate
blocked-roadmap variants are normalized to this exact filename:
`Roadmap_Blocked.md`.

---

## How to unblock

| Blocker | Action needed |
|---------|--------------|
| N-1 Toolchain | Run AGP 9 / Gradle 9 / Kotlin 2.3 upgrade, verify assembleDebug/test/lint |
| Firebase Console | Owner logs into Firebase Console, deploys rules, registers App Check, grants Custom Claims |
| Physical device | Connect an Android device via adb (or start an Android 17 emulator with working adb) |
| Content curation | Owner/moderator reviews and selects CC0 sounds for the Aura Originals manifest |
