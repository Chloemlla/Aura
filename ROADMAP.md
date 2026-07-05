# Aura Roadmap

This file tracks actionable work only. Completed work is removed; history lives in
git and `CHANGELOG.md`. Work blocked by owner actions, Firebase Console access,
physical-device validation, or toolchain gates belongs in `Roadmap_Blocked.md`.

## Current State

- Version: v6.34.6 / versionCode 133.
- Stack: Kotlin 2.1.0, AGP 8.7.3, Gradle 8.12, Compose Material 3, Hilt, Room,
  WorkManager, Media3, Coil 2.7.0, Firebase, NewPipe Extractor, yt-dlp.
- Distribution: local builds only. GitHub Actions workflows have been removed.
- Recent roadmap features already in code and removed from this file: personal
  microphone-to-ringtone recording, Wikipedia POTD, Lemmy wallpapers,
  no-repeat wallpaper rotation, time-of-day sound profiles, 24H wallpaper packs,
  alarm shuffle, sound metadata badges, video technical summaries, live wallpaper
  icon dim/reveal, direct-boot live wallpaper flags, ringtone restoration, whole
  library export/import services, manifest consistency tooling, local AAB dry-run
  evidence tooling, and Settings feature-owned decomposition.

## P3

### On-device wallpaper style learning

Aura has deterministic quality ranking and style preferences, but no learned
on-device taste model.

Acceptance:
- Apply, favorite, and skip signals stay local.
- Discover ranking adapts after enough interactions.
- Users can reset learned preferences in Settings.

## Research-Driven Additions

### P2

### P1

### P2
