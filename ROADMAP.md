# Aura Roadmap

This file tracks actionable work only. Completed work is removed; history lives in
git and `CHANGELOG.md`. Work blocked by owner actions, Firebase Console access,
physical-device validation, or toolchain gates belongs in `Roadmap_Blocked.md`.

## Current State

- Version: v6.35.0 / versionCode 134.
- Stack: Kotlin 2.1.0, AGP 8.7.3, Gradle 8.12, Compose Material 3, Hilt, Room,
  WorkManager, Media3, Coil 2.7.0, Firebase, NewPipe Extractor, yt-dlp.
- Distribution: local builds only. GitHub Actions workflows have been removed.
- 2026-07-15 deep audit shipped ~45 fixes (see CHANGELOG v6.35.0). Items below
  under "2026-07-15 audit backlog" were found by that audit but deferred.

## 2026-07-15 audit backlog

- [ ] P2 — Enforce (or delete) the unused MediaIngestion image-flow policies
  Why: `MediaIngestionImageFlow.AUTO_ROTATION / LOCAL_APPLY / EDITOR` policies —
  including the AVIF minSdk-34 gate and its user-facing rejection copy — are
  consulted only by the community-upload flow; local apply/editor paths fail with
  a generic decode error instead of the intended explanation. Misleading dead policy.
  Where: app/src/main/java/com/freevibe/service/MediaIngestion.kt, WallpaperApplier.kt,
  WallpaperEditorViewModel.kt, WallpaperCropViewModel.kt
- [ ] P2 — Localize ViewModel-layer feedback messages posted through ApplyFeedbackBus/applySuccess
  Why: "Applied to home screen", "Reverted to previous wallpaper", "Recording failed…",
  "Hidden from this feed", and similar strings are hardcoded English inside ViewModels
  and action delegates that have no composition context. FreeVibeRoot's copies were
  localized this pass; the VM layer needs @ApplicationContext-based resources.
  Where: ui/screens/wallpapers/WallpaperApplyActions.kt, WallpaperStyleActions.kt,
  ui/screens/sounds/SoundCommunityActions.kt, SoundsViewModel.kt,
  ui/screens/aigenerate/AiWallpaperViewModel.kt
- [ ] P2 — Rotation trigger recovery can be blocked silently on Android 12+
  Why: `FreeVibeApp.reconcileRotationTriggers()` calls `startForegroundService` from a
  background-restarted process, which can throw ForegroundServiceStartNotAllowedException
  (swallowed, debug-only log). Rotate-on-unlock then stays dead until the app is opened.
  Needs a WorkManager-based re-arm or user-visible degraded state. (The null-intent
  sticky-restart case was fixed this pass.)
  Where: app/src/main/java/com/freevibe/FreeVibeApp.kt, service/RotationTriggerService.kt
- [ ] P3 — Community sound delete from the detail screen has no failure feedback
  Why: `deleteCommunitySound(s); onBack()` navigates immediately; the detail-scoped
  ViewModel and snackbar die with the screen, so a failed Firebase delete is silent.
  Needs the result routed through a bus that survives navigation (ApplyFeedbackBus
  pattern) or deferred navigation.
  Where: app/src/main/java/com/freevibe/ui/screens/sounds/SoundDetailScreen.kt
- [ ] P3 — Library hub "Backup & restore" lands on the Settings root
  Why: the row navigates to Settings with no anchor; the user must hunt for the backup
  section. Settings has no section-anchor/scroll mechanism yet — add a nav argument
  plus scroll-to-section support.
  Where: app/src/main/java/com/freevibe/ui/FreeVibeRoot.kt (library hub wiring),
  ui/screens/settings/SettingsScreen.kt
- [ ] P3 — Reddit video title parsing uses fragile parallel-regex index alignment
  Why: `titles.getOrNull(i + 1)` pairs two independently-derived lists; currently dead
  code (`redditEnabled = false` hardcoded) but a landmine if the source is re-enabled.
  Where: app/src/main/java/com/freevibe/ui/screens/videowallpapers/VideoWallpapersViewModel.kt
- [ ] P3 — LibraryExporter export format includes downloads that import intentionally skips
  Why: exported `downloads` reference device-local paths and are deliberately not
  imported on the target device (documented in code). Either drop them from the export
  or re-download by source URL on import so the format matches behavior.
  Where: app/src/main/java/com/freevibe/service/LibraryExporter.kt

## P3

## Research-Driven Additions

### P2

### P1
