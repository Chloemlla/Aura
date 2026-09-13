# Research — Aura

Date: 2026-09-13. This report replaces all prior research.

Confidence labels used throughout:

- **Verified:** reproduced in the repository, release artifacts, current tracker, device evidence, or a primary source.
- **Likely:** supported by several credible sources or a strong architectural inference, but not reproduced directly in Aura.
- **Assumption:** a product or business premise that still needs an owner decision.
- **Needs live validation:** the evidence is specific enough to test, but the required account, device state, contract, or counsel review is unavailable.

## Executive Summary

Aura v6.45.3 is already a broad Android personalization product, not a prototype. It combines Reddit-first wallpaper and video discovery, YouTube-first sound discovery, local and bundled media, three live-wallpaper engines, editing, scheduled rotation, community features, diagnostics, backup, and alternative-store release tooling. The current Android package is `com.freevibe`, with minSdk 26, compileSdk 36, targetSdk 35, versionCode 149, and versionName 6.45.3 (`app/build.gradle.kts:82,95-98`). The signed v6.45.3 release was published on 2026-09-13 with five APKs and a checksum manifest ([release](https://github.com/SysAdminDoc/Aura/releases/tag/v6.45.3)).

The product direction is sound. Reddit should remain the first discovery source for wallpapers and video because it supplies the variety, niche communities, and novelty that catalog APIs do not. YouTube should remain the first discovery surface for sounds and a major video source because its searchable inventory is unmatched. The required change is not to weaken that direction. It is to put those integrations on durable authentication, rights, deletion, codec, and distribution foundations.

The latest device audit established a useful baseline. On a Samsung Galaxy S22 running Android 16, the Reddit wallpaper feed loaded 60 items, detail actions and downloads worked, the Reddit video feed played in immersive mode, Aura Originals played, and YouTube sound search and playback worked. On a disposable API 29 device, a Reddit static wallpaper applied, a valid Reddit live wallpaper animated, Aura Originals applied as ringtone, notification, and alarm, and YouTube sound extraction and application worked. The same run also reproduced two important video defects: a valid eight-second fragmented Reddit MP4 was rejected because its duration probed as zero, and a YouTube download selected AV1 on a device without an AV1 decoder, producing a black live wallpaper. Both defects are already tracked in `ROADMAP.md` under “Accept valid fragmented Reddit MP4s…” and “Prevent YouTube video wallpaper installs from selecting unsupported AV1.” The accessibility evidence describes the S22 coverage and its untested TalkBack states (`docs/qa/accessibility-release-gate.json`).

The highest-value work, in priority order, is:

1. **Verified policy text, Likely release risk:** keep YouTube-first discovery, but do not ship extraction, download, conversion, or apply behavior in a distribution channel unless the use is authorized by YouTube or the media rights holder. YouTube’s Terms prohibit downloading except where the service permits it or YouTube and the rights holder authorize it. The developer policies separately prohibit undocumented API access, reverse engineering, and offline copies without approval ([Terms](https://www.youtube.com/static?template=terms), [Developer Policies](https://developers.google.com/youtube/terms/developer-policies)). Aura currently uses NewPipe and yt-dlp to search, resolve, download, convert, and apply media (`YouTubeRepository.kt`, `VideoWallpapersViewModel.kt`, `SoundYouTubeActions.kt`).
2. **Verified packaging mismatch, Likely licensing consequence:** resolve the MIT root license versus bundled GPL payloads before another release. The combined APK includes NewPipeExtractor v0.26.5 under GPL-3.0-or-later and an FFmpeg build configured with `--enable-gpl --enable-version3` (`app/build.gradle.kts:427-431`, `docs/legal/dependency-notice-overrides.json:23-27`). The project’s current legal records mark review as required, but do not close the combined-work question. The Free Software Foundation’s GPL FAQ says a combined linked work must be distributed under compatible GPL terms ([GPL FAQ](https://www.gnu.org/licenses/gpl-faq.en.html)). Counsel must decide the final distribution position.
3. **Verified:** make every release gate pass from a clean clone. `docs/distribution/release-metadata-consistency.json:11` requires `COMPLETED.md`, while that file is ignored and absent from a clone. Several distribution documents still refer to deleted `.github/workflows/*` paths even though Aura’s policy is local-only builds. A check that depends on the maintainer’s untracked working tree can produce a false green.
4. **Verified:** migrate Reddit from anonymous Atom requests to a registered OAuth client, a compliant identifying user agent, and a deletion reconciliation loop. Reddit’s current Data API guidance requires OAuth, registered access, identifiable clients, rate-limit compliance, and deletion handling; non-OAuth traffic may be blocked ([Data API Wiki](https://support.reddithelp.com/hc/en-us/articles/16160319875092-Reddit-Data-API-Wiki), [Data API Terms](https://redditinc.com/policies/data-api-terms)). Aura still fetches `.rss` endpoints without OAuth (`RedditRepository.kt:316-332`).
5. **Verified:** prevent user data loss in export and import. The public contract says favorites allow 10,000 items and collections allow 500, while production code uses lower limits and collection export silently truncates (`docs/data/export-format.json:11`, `FavoritesExporter.kt:23`, `CollectionExporter.kt:166`). The limit contract must be single-sourced and truncation must never be silent.
6. **Verified:** make Reddit galleries first-class. The parser drops preview-only gallery posts, leaving a major Reddit media type absent from both wallpaper and video discovery (`RedditRssParser.kt`). The Reddit API model exposes gallery metadata, and competing Reddit clients have repeatedly had to repair multi-image handling ([Reddit Post model](https://developers.reddit.com/docs/api/redditapi/models/classes/Post), [RedditWallpaperChanger #5](https://github.com/bwalsh0/RedditWallpaperChanger/issues/5)).
7. **Verified:** add named Reddit feed presets for different moods and rotation contexts. Aura currently stores one comma-separated wallpaper subreddit list and one video list with a twelve-subreddit cap (`PreferencesManager.kt:58,500-520`). WallFlow demonstrates saved searches and reusable source configurations ([WallFlow](https://github.com/ammargitham/WallFlow)). This expands choice without adding weaker providers.
8. **Verified:** protect local-library identity. When a SAF or filesystem item disappears, Aura clears its path rather than offering relink, which can strand favorites, collections, edits, and rotation references (`PathBackedRecordReconciler.kt`). A relink flow is a better local-first recovery model; Nothing Wallpaper Changer preserves collection and edit state while relinking missing media ([project](https://github.com/NineCSdev/nothing-wallpaper-changer)).
9. **Verified need, Likely delight gain:** add Fit Canvas choices, rotation exclusions with Undo, and a clear split between “apply an optimized copy” and “save the original.” Paperize users explicitly ask for canvas fill choices and per-image rotation exclusion ([#608](https://github.com/Anthonyy232/Paperize/issues/608), [#604](https://github.com/Anthonyy232/Paperize/issues/604)). These controls reduce surprise without complicating first use.
10. **Verified:** continue the already-roadmapped media and usability work before adding a marketplace or more catalog APIs. Player pooling, visible-window YouTube resolution, resumable downloads, live-wallpaper capability preflight, TalkBack announcements, editor reliability, translation completion, sound profiles, and video playlists are already actionable in `ROADMAP.md` and should not be duplicated.

## Product Map

### Core workflows

- **Verified:** browse, search, filter, favorite, download, edit, and apply static wallpapers to home, lock, or both (`WallpapersScreen.kt`, `WallpaperDetailScreen.kt`, `WallpaperEditorScreen.kt`, `WallpaperApplier.kt`).
- **Verified:** browse Reddit-first and multi-provider video feeds, preview loops, import local video or GIF media, crop it, and install it through Android’s live-wallpaper picker (`VideoWallpapersScreen.kt`, `VideoCropScreen.kt`, `VideoWallpaperService.kt`).
- **Verified:** discover YouTube-first sounds plus 25 offline Aura Originals, preview and trim audio, then apply it as ringtone, notification, alarm, or per-contact ringtone (`SoundsScreen.kt`, `SoundEditorScreen.kt`, `AudioTrimmer.kt`, `SoundApplier.kt`, `AuraOriginalsManifest.kt`).
- **Verified:** automate wallpaper changes by interval, clock, day/night, theme, unlock, or screen-off, with rotation history and health diagnostics (`AutoWallpaperWorker.kt`, `DailyWallpaperWorker.kt`, `RotationTriggerService.kt`, `WallpaperHistoryManager.kt`).
- **Verified:** run video, parallax, and weather live wallpapers with frame and battery controls (`VideoWallpaperService.kt`, `ParallaxWallpaperService.kt`, `WeatherWallpaperService.kt`).
- **Verified:** manage favorites and collections, export and import library data, create shared collection links and QR codes, download media, and collect crash diagnostics (`FavoritesExporter.kt`, `LibraryExporter.kt`, `CollectionExporter.kt`, `DownloadManager.kt`, `CrashDiagnosticsCollector.kt`).
- **Verified:** optionally use Firebase-backed community upload, voting, following, reporting, and moderation in the full flavor. The FOSS flavor removes those proprietary dependencies (`app/src/full`, `app/src/foss`, `functions`, `database.rules.json`, `storage.rules`).

### Current source model

| Source | Current role | Assessment |
|---|---|---|
| Reddit | Default wallpaper and video discovery plus rotation | **Verified:** best product fit, but anonymous Atom access is no longer a durable API contract (`RedditRepository.kt`, `ProviderCapability.kt`). Preserve priority and rebuild transport around OAuth. |
| YouTube | Primary sound discovery and major video source through NewPipe plus yt-dlp | **Verified:** search, extraction, conversion, and apply work on tested content. Codec selection and authorization are separate unresolved risks (`YouTubeRepository.kt`, `VideoWallpapersViewModel.kt`). |
| Aura Originals | Offline ringtone, notification, and alarm starter catalog | **Verified:** 25 packaged originals remove first-run dependence on a network (`AuraOriginalsManifest.kt`, `CHANGELOG.md`). Expand by quality and category, not volume alone. |
| Wallhaven | Searchable wallpaper catalog | **Verified:** active secondary source with optional user credential (`ProviderCapability.kt`). Useful for precision search, not the default personality of the app. |
| Pexels and Pixabay | Optional photo/video enhancement sources | **Verified:** credentials are user supplied in public releases (`README.md`, `ProviderCapability.kt`). Keep them secondary because setup friction is higher. |
| Bing, Wikimedia, NASA, Lemmy | Daily or secondary discovery sources | **Verified:** useful for variety and provenance, but too narrow to replace Reddit (`ProviderDisclosure.kt`). |
| Local and downloaded media | Offline, user-owned library and apply source | **Verified:** strategically important. Relink, exclusion, and non-destructive optimization are the main gaps. |
| Community | Optional user-generated wallpaper and sound layer | **Verified:** capable but operationally heavier because it depends on Firebase, moderation, quotas, rights, and deletion (`functions`, `docs/community-*`). |
| Dormant sound providers | Legacy attribution only | **Verified:** Freesound, SoundCloud, Audius, ccMixter, and related providers remain in disclosure or repository code although the active feed is YouTube-first (`ProviderDisclosure.kt`, `CLAUDE.md`). Their removal is already tracked. |

### Users and jobs

- **Verified:** discovery-first users want a fresh visual or sound without knowing the exact query. Zedge’s scale and user comments support the importance of browsing and curation, even where its ad and monetization model is disliked ([Zedge](https://www.zedge.net/), [community discussion](https://www.reddit.com/r/androidapps/comments/1e1xwms/)).
- **Verified:** collectors want favorites, collections, original files, provenance, exports, and reliable local recovery (`LibraryExporter.kt`, `CollectionExporter.kt`).
- **Verified:** automators want schedules, reusable source sets, prefetch, exclusions, history, and controls that survive reboot and power restrictions. WallFlow, Wallora, Paperize, and Peristyle repeatedly invest in these jobs ([WallFlow](https://github.com/ammargitham/WallFlow), [Wallora](https://github.com/thissayantan/wallora), [Paperize](https://github.com/Anthonyy232/Paperize), [Peristyle](https://github.com/Hamza417/Peristyle)).
- **Verified:** sound customizers need predictable trim points, loudness, device-sound browsing, reversible defaults, per-contact verification, and rotation pools. The roadmap already covers most of these foundations.
- **Assumption:** privacy-first sideloaders are the primary commercial audience. The no-account core, no-ad direction, local-first capabilities, FOSS flavor, checksums, and Obtainium configuration all support that positioning (`README.md`, `obtainium.json`, `docs/privacy`).

### Platforms and release posture

- **Verified:** Android 8.0 and later are supported (`app/build.gradle.kts`). Android 16 behavior and Android 17 preparation are materially relevant because targetSdk 36 is already required for Play updates as of 2026-08-31, while Android 17 changes background audio and other platform contracts ([Play target API policy](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en), [Android 17 changes](https://developer.android.com/about/versions/17/behavior-changes-all)).
- **Verified:** GitHub Releases and Obtainium are the active channels. F-Droid and IzzyOnDroid remain constrained by proprietary dependencies, source-build expectations, licensing review, and APK size (`docs/distribution/channel-strategy.md`, [F-Droid policy](https://f-droid.org/en/docs/Inclusion_Policy/), [IzzyOnDroid policy](https://izzyondroid.org/docs/general/AppInclusionPolicy/)).
- **Verified:** the app has an unusually large local quality system: JVM and Python tests, Android instrumentation, release-policy checks, reproducibility checks, dependency verification, 16 KB alignment evidence, accessibility baselines, and local release runbooks (`test`, `app/src/androidTest`, `tools`, `docs/distribution`, `docs/qa`). The weak point is that some gates validate declarations or local files rather than the clean-clone user reality.

## Competitive Landscape

| Product | What it does well | What Aura should learn | What Aura should avoid |
|---|---|---|---|
| [WallFlow](https://github.com/ammargitham/WallFlow) | Reddit and Wallhaven discovery, saved searches, favorites, cache, automatic changes, Quick Settings, Tasker, and on-device smart crop | Named Reddit feed presets, reusable search sources, and source health are a close match for Aura | Do not inherit a single-source failure mode without OAuth and health reporting |
| [Muzei](https://github.com/muzei/muzei) | Mature rotation model, dim and blur controls, tap-to-reveal, and a plugin ecosystem | Make rotation controls calm, reversible, and consistent across every live engine | Do not build a public provider SDK before Aura’s own provider contract is coherent |
| [Wallora](https://github.com/thissayantan/wallora) | Multi-provider search, category filters, history, crop, prefetch, widgets, tiles, and Tasker integration | Prefetch the next item and keep reusable automation entry points | Do not make all providers visually equal when Reddit is the deliberate default |
| [Nothing Wallpaper Changer](https://github.com/NineCSdev/nothing-wallpaper-changer) | Offline privacy posture, preprocessed next-wallpaper buffer, broken-file relink, collection pinning, battery-saver policy, and default restore | Relink missing media without losing edits or membership, and separate apply-ready media from originals | Avoid invisible background preprocessing or storage growth |
| [DarkModeLiveWallpaper](https://github.com/cvzi/darkmodewallpaper) | Different day/night media, color controls, GIF/WebP support, Photo Picker, and share targets | Aura’s theme-aware rotation and local import should remain first-class | Avoid making one switching rule the whole product |
| [Paperize](https://github.com/Anthonyy232/Paperize) | Offline folder rotation, per-screen schedules, notification controls, and focused settings | Add Fit Canvas choices and persistent per-item rotation exclusions with Undo | Its issue history shows the cost of rigid canvas behavior and feature discoverability gaps ([#608](https://github.com/Anthonyy232/Paperize/issues/608), [#604](https://github.com/Anthonyy232/Paperize/issues/604)) |
| [Peristyle](https://github.com/Hamza417/Peristyle) | Local folders, effects, home/lock separation, notification controls, and external intents | Keep external automation opt-in, parameterized, and observable | Do not accept arbitrary unvalidated URLs or paths through exported components |
| [UndeadWallpaper](https://github.com/maocide/UndeadWallpaper) | Video playlists, per-item transforms, shuffle, start positions, parallax, gapless batches, and recovery work | Refine Aura’s existing playlist roadmap item with bounded pagination, explicit start state, and decoder recovery | Avoid unbounded decoded storage or a playlist UI that hides which clip is active |
| [Prism](https://github.com/Hash-Studios/prism) | Personalized feeds, creator follows, color/tag search, deep links, previews, and cloud sync | Search by color and creator can improve discovery after core reliability work | Accounts and social sync would conflict with Aura’s no-account core |
| [Zedge](https://www.zedge.net/) | Enormous catalog, cross-media browsing, recognizable categories, and strong discovery | Users value “show me something good” more than provider count | Ads, credits, subscriptions, paywalls, and low-quality generated inventory are the clearest positioning to reject ([FAQ](https://help.zedge.net/hc/en-us/articles/360024313191-ZEDGE-for-Android-FAQ), [credits](https://help.zedge.net/hc/en-us/articles/360024595751-Purchasing-Zedge-credit-bundles)) |
| [Backdrops](https://play.google.com/store/apps/details?id=com.backdrops.wallpapers) | Handcrafted originals, community submissions, and recognizable editorial collections | Grow Aura Originals as a small, dependable quality layer around Reddit | Do not fragment basic actions across coins, Pro, and separate entitlement layers |
| [Wallpaper Engine](https://store.steampowered.com/app/431960/Wallpaper_Engine/) | Local video imports, playlists, strong previews, and desktop-to-phone transfer | Rich per-item playlist controls validate Aura’s planned video playlist | A desktop companion adds operational weight and is unnecessary for Aura’s mobile-first product ([mobile setup](https://help.wallpaperengine.io/en/mobile/setup.html)) |
| [Walli](https://play.google.com/store/apps/details?id=com.shanga.walli) and [Abstruct](https://play.google.com/store/apps/details?id=com.hampusolsson.abstruct) | Artist-led curation and polished original delivery | Preserve originals at full quality while creating an optimized apply copy | Avoid an account-dependent creator economy |
| [Seal](https://github.com/JunkFood02/Seal), [YTDLnis](https://github.com/deniscerri/ytdlnis), and [youtubedl-android](https://github.com/yausername/youtubedl-android) | More mature yt-dlp runtime, format, token, and failure handling | Treat them as extraction reliability and security reference implementations | They do not grant rights to download YouTube content; their existence does not settle Aura’s distribution policy |
| [UltimateRingtonePicker](https://github.com/DeweyReed/UltimateRingtonePicker) and [Ringdroid](https://github.com/google/ringdroid) | Device-sound selection and a proven trim interaction model | Use the device’s existing sounds and provide a route back to the original defaults | Do not revive abandoned code wholesale or replace Aura’s safer MediaStore path |

### Market conclusions

- **Verified:** Aura’s broad, integrated workflow is its advantage. Most competitors specialize in static rotation, live video, ringtone editing, or a catalog. Aura connects discovery, edit, apply, automation, backup, and diagnostics in one app.
- **Verified:** more providers are not the clearest route to happier users. Competitor issues cluster around reliability, offline behavior, battery cost, missing-file recovery, format support, controls, and unclear state. Aura should deepen Reddit, YouTube, local media, and originals before adding another generic catalog.
- **Likely:** the strongest message is “fresh media without ads, accounts, or low-quality generated filler.” Community complaints about Zedge repeatedly center on ads and generated content ([Reddit discussion](https://www.reddit.com/r/androidapps/comments/1e1xwms/)). This is useful positioning evidence, not permission to make unprovable competitor claims.
- **Likely:** user trust rises when the app says exactly what a source can do, why an action is unavailable, what was changed on the device, and how to undo it. The roadmap’s diagnostics, preflight, Undo, source-health, and original-restore work should be treated as product features, not maintenance.

## Reported Issues

### Aura tracker

- **Verified, open:** [Issue #47](https://github.com/SysAdminDoc/Aura/issues/47), “Translate Aura into your language.” Simplified Chinese arrived through [PR #48](https://github.com/SysAdminDoc/Aura/pull/48), but 182 main-resource keys still fall back to English. The existing localization roadmap items correctly own completion and gate coverage.
- **Verified, resolved:** [Issue #44](https://github.com/SysAdminDoc/Aura/issues/44), YouTube ringtone WebM/Opus ingestion. The reporter confirmed the v6.38.1 fix.
- **Verified, resolved after broader repair:** [Issue #2](https://github.com/SysAdminDoc/Aura/issues/2), the Android 10 YouTube sound crash. v6.45.0 added desugaring and later device instrumentation covered the legacy API path (`CHANGELOG.md:1593`, `NewPipeLegacySearchInstrumentedTest.kt`). It should remain in regression coverage because it affected the app’s primary sound feed.
- **Verified:** [Discussions #45](https://github.com/SysAdminDoc/Aura/discussions/45) and [#46](https://github.com/SysAdminDoc/Aura/discussions/46) have no user replies as of 2026-09-13. Discussion #45 also says the app has no translations, which became stale after PR #48.
- **Verified:** there are no open pull requests as of 2026-09-13. The tracker is too small to stand alone as product-demand evidence, so adjacent open-source issues and attributable community reports are used below.

### Reproduced in the current device audit

- **Verified:** a valid fragmented Reddit MP4 can download and then fail the minimum-duration check because `MediaMetadataRetriever` returns zero. The existing P1 roadmap item has a concrete fixture and acceptance test.
- **Verified:** YouTube video format selection can choose AV1 based on container alone. On the API 29 test device, the result installed but rendered black because no `video/av01` decoder existed. The existing P1 roadmap item must land before YouTube video apply can be called reliable across the supported API range.
- **Verified:** the S22 media paths exercised in the audit were healthy: Reddit wallpaper discovery and download, Reddit video preview, Aura Originals, YouTube sound discovery, and the relevant details/actions. `docs/qa/accessibility-release-gate.json` records the physical-device surfaces. The user’s personal wallpaper and tones were not changed during the S22 audit.
- **Needs live validation:** TalkBack, device-wide high contrast, long-run battery impact, seven-day scheduler survival, and the complete Android 16 rotation matrix were not exercised on the user’s active device. The release gate currently records those limitations rather than proving them (`docs/qa/accessibility-release-gate.json`, `docs/background-work-device-evidence.md`).

### Recurring category complaints that apply to Aura

- **Verified:** automatic rotation failures after Android upgrades, reboot, power saving, or source outages recur in competing projects ([WallFlow #110](https://github.com/ammargitham/WallFlow/issues/110), [WallFlow #113](https://github.com/ammargitham/WallFlow/issues/113), [Paperize #126](https://github.com/Anthonyy232/Paperize/issues/126)). Aura has stronger diagnostics than most competitors, but its next-item prefetch and long-run scenario matrix remain roadmap work.
- **Verified:** per-contact ringtones can silently fall back to the default when contacts are duplicated, stored on a SIM, or mapped differently after migration. The existing roadmap items cover assignment verification and portable backup ([Google Phone support](https://support.google.com/phoneapp/thread/221331571/), [Pixel report](https://www.reddit.com/r/GooglePixel/comments/18eqrae/), [Tasker workaround](https://www.reddit.com/r/tasker/comments/sbz15u/)).
- **Verified:** users want per-item fit, fill, exclusion, and manual-change behavior instead of one global rotation rule ([Paperize #608](https://github.com/Anthonyy232/Paperize/issues/608), [Paperize #604](https://github.com/Anthonyy232/Paperize/issues/604), [Paperize #591](https://github.com/Anthonyy232/Paperize/issues/591)). Aura covers crop and framing, but Fit still leaves unexplained letterboxing and rotation lacks a dedicated exclusion model.
- **Likely:** battery anxiety is as important as measured drain. Live wallpaper apps earn trust by exposing frame caps, battery-saver behavior, network posture, and pause state. Aura already has many controls; it should show effective state and measured consumption rather than promise “battery friendly” without a device profile.

### Issue-intake quality

- **Verified:** `.github/ISSUE_TEMPLATE/crash_report.yml` requires diagnostics even when a crash prevents launch, but it omits the reproduction steps, device model, Android version, Aura version, expected result, and actual result promised by the documentation. A fallback `adb bugreport` or logcat path is also absent. This creates avoidable back-and-forth on the exact defects that need device-specific evidence.

## Security, Privacy, and Reliability

### YouTube rights and distribution

- **Verified:** Aura does not use the official YouTube Data API for its core sound/video path. It searches with NewPipeExtractor and resolves or downloads streams with NewPipe and yt-dlp (`YouTubeRepository.kt:150-151,253,381,410,469`, `VideoWallpapersViewModel.kt`).
- **Verified:** YouTube’s Terms say content may not be downloaded unless the service expressly authorizes it or prior written permission exists from YouTube and, where applicable, the rights holder. The API policies also prohibit undocumented APIs, reverse engineering, and offline copies without approval ([Terms](https://www.youtube.com/static?template=terms), [Developer Policies](https://developers.google.com/youtube/terms/developer-policies)).
- **Verified:** `docs/distribution/youtube-store-risk-profile.json` controls marketing language and channel defaults, but it still treats extraction as acceptable in some channels based on store posture. A store-risk profile is not a content-rights authorization.
- **Likely:** unrestricted YouTube download, audio conversion, and wallpaper application create distribution and intellectual-property exposure. Search, attribution, and official external playback can preserve YouTube-first discovery while the owner confirms authorization. Google Play also requires apps not to encourage copyright infringement ([Play IP policy](https://support.google.com/googleplay/android-developer/answer/9888072?hl=en)).

### Reddit access, deletion, and rights

- **Verified:** Aura constructs `https://www.reddit.com/.../.rss` requests and identifies itself as an open-source wallpaper reader, without OAuth (`RedditRepository.kt:316-332`). Wallpaper and video discovery share this transport.
- **Verified:** Reddit’s current Data API guidance requires registered OAuth access, an identifiable user agent, rate-limit compliance, and deletion of removed content. It warns that non-OAuth traffic can be blocked and recommends routine deletion reconciliation ([Data API Wiki](https://support.reddithelp.com/hc/en-us/articles/16160319875092-Reddit-Data-API-Wiki), [Data API Terms](https://redditinc.com/policies/data-api-terms), [Developer Terms](https://redditinc.com/policies/developer-terms)).
- **Verified:** Reddit does not grant ownership of user media. Aura’s model currently labels parsed wallpaper licensing as “Reddit,” which is source provenance rather than a license grant (`RedditRssParser.kt`). Downloads, re-uploads, and community publication need post-level author, permalink, deletion, and rights state.
- **Needs live validation:** no repository evidence proves that Aura has a registered Reddit client, API review, commercial agreement, or written exception. The owner account is required to settle this.

### Open-source licensing

- **Verified:** the root project license is MIT (`LICENSE`). NewPipeExtractor v0.26.5 is GPL-3.0-or-later, and the packaged FFmpeg build enables GPL and version 3 (`app/build.gradle.kts:427-431`, [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), `docs/legal/dependency-notice-overrides.json`).
- **Verified:** Aura already inventories native components and source correspondence, which is good practice. The runtime pin and several legal locks disagree, however: production uses NewPipe v0.26.5 while `docs/legal/dependency-notices.lock.json:1064`, `docs/legal/dependency-notice-overrides.json:50`, and `tools/native_compliance_inventory.py:24,81` still identify v0.26.3.
- **Likely:** distributing a single combined APK under only MIT terms is not enough if GPL components form a combined work. The decision must be closed by counsel or a documented license analysis, then implemented through compatible licensing and complete corresponding source, or by removing the GPL payloads ([GPL FAQ](https://www.gnu.org/licenses/gpl-faq.en.html)).

### Data handling and user trust

- **Verified:** provider credentials are encrypted with Android Keystore AES-GCM in `ProviderCredentialStore.kt:28-100`. Some privacy and crash-diagnostics text still says Aura does not use Keystore or understates credential storage. Public claims must match code.
- **Verified:** backup policy files and data-safety rows disagree about which preferences and credential-related state are backed up (`app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `docs/privacy/data-safety.json`, `docs/privacy/privacy-policy.md`). Credential ciphertext without its device-bound key is not portable and should be explicitly excluded or handled as a known restore failure.
- **Verified:** collection export silently truncates after the import cap (`CollectionExporter.kt:166`). Favorites and collection limits also disagree with `docs/data/export-format.json`. Export must fail loudly, paginate, or produce every selected item. Silent omission is unacceptable for backup.
- **Verified:** Firebase App Check initialization failure is logged only in debug (`FreeVibeApp.kt:109-111`). If enforcement changes, release users can see cascading community failures without a durable cause. The existing roadmap item for silent failures should absorb the runtime logging, but policy status also needs to stay truthful.

### Supply chain and media safety

- **Verified:** the yt-dlp payload is pinned to 2026.07.04 and Aura has explicit reachability gates for current yt-dlp advisories (`docs/security/ytdlp-cve-policy.json`, `tools/ytdlp_cve_policy_check.py`). Keep these gates. Do not add a duplicate generic “update yt-dlp” item.
- **Verified:** FFmpeg 7.1.1 remains a large attack and size surface, and its removal or input bounding is already tracked in `ROADMAP.md`. The user controls local video inputs, so reachability matters.
- **Verified:** direct media downloads have bounded size and sniffing controls, while resumable validator-aware transfer is already tracked. Android’s guidance on untrusted provider filenames and path traversal should remain part of every import path ([filename risk](https://developer.android.com/privacy-and-security/risks/untrustworthy-contentprovider-provided-filename), [zip traversal](https://developer.android.com/privacy-and-security/risks/zip-path-traversal)).
- **Verified:** 16 KB page-size evidence exists, but five nested libwebp objects remain explicit exceptions. That work is already tracked and should land before calling the native payload fully Android 16-ready (`docs/distribution/native-alignment.json`, [Android page sizes](https://developer.android.com/guide/practices/page-sizes)).

### Reliability priorities

- **Verified:** video compatibility should be capability driven. Container checks are insufficient because MP4 may carry AV1, HEVC, or AVC, and fragmented media may confuse a single metadata retriever. Media3 Transformer and device codec queries provide the appropriate probe and compatibility path ([supported formats](https://developer.android.com/media/platform/supported-formats), [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec), [Transformer](https://developer.android.com/media/media3/transformer)).
- **Verified:** automatic work must survive process death, reboot, Doze, battery saver, revoked SAF access, corrupt candidates, network loss, provider rate limits, and daylight-saving changes. Aura has the workers and diagnostics, but current evidence does not prove this complete matrix ([background transfer options](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options), [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work)).
- **Likely:** media previews should resolve and decode only the visible window plus a small lookahead. Aura’s memory cap and existing preload roadmap work move in this direction. Research on mobile feed energy consumption supports avoiding speculative network and decode work ([study](https://arxiv.org/abs/2101.09176)).

## Architecture Assessment

### What is working

- **Verified:** the provider capability and disclosure models are the right foundation. They encode lifecycle, build flavor, distribution channel, configuration, kill switch, endpoint IDs, and attribution (`ProviderCapability.kt`, `ProviderDisclosure.kt`).
- **Verified:** media application is centralized enough to enforce target choice, history, Undo, and failure feedback (`WallpaperApplyCoordinator.kt`, `WallpaperApplier.kt`, `SoundApplier.kt`).
- **Verified:** the app has explicit full/FOSS source sets, network endpoint inventory, credential storage, Firebase rules tests, import validators, release gates, and diagnostics. These are stronger foundations than most category competitors.
- **Verified:** the media surface is product-complete enough that more feature breadth has diminishing returns. The highest-return work is now contract consistency, recovery, accessibility, performance, and compatibility.

### Recommended boundaries

1. **Provider contract:** extend the capability record with authentication mode, rights/action policy, deletion deadline, cache policy, health/backoff state, and a supported media/action matrix. UI, repository calls, store metadata, diagnostics, and release gates should derive from it. **Verified need:** these truths currently drift across Kotlin, Fastlane, README, privacy documents, and distribution JSON.
2. **Acquisition versus application:** separate source discovery and authorized acquisition from normalization and device application. An item should carry provenance, rights state, original format, acquired format, decoder result, and allowed actions before it reaches `WallpaperApplier`, `VideoWallpaperStorage`, or `SoundApplier`. **Verified need:** the AV1 failure shows that a URL/container passing acquisition is not proof of device compatibility.
3. **Reddit transport:** replace RSS-specific pagination state with a Reddit client that owns OAuth token lifecycle, listing cursors, galleries, rate limits, `edited`/`deleted` reconciliation, and canonical post/media identity. Keep cached items available with explicit stale/deleted state. **Verified need:** `PreferencesManager` and `RedditRepository` currently encode RSS paging details.
4. **Local content identity:** use a stable content record independent of path or URI. Paths become locators that may be replaced. Favorites, collections, edits, rotation exclusions, and apply history should point to identity, not a transient file path. **Verified need:** missing-path reconciliation currently clears locators rather than supporting recovery.
5. **Rotation policy:** model source preset, target, schedule, exclusions, retry/backoff, prefetch, and current/next item as explicit state. One engine should serve manual schedules, interval rotation, widget/tile actions, and future video playlists. **Likely benefit:** competitors repeatedly duplicate these controls and then diverge.
6. **Release truth:** run publication, legal, metadata, artifact, and documentation gates in an isolated clean checkout using only tracked inputs. Owner-only credentials may be a declared tri-state, but ordinary release facts cannot depend on an ignored file. **Verified need:** `COMPLETED.md` and deleted workflow references violate this boundary.

### Test gaps to close

- **Verified:** add fixture tests for OAuth token refresh, 401 retry, 429 backoff, gallery posts, crossposts, deleted media, and a cached post removed upstream.
- **Verified:** add codec fixtures for AVC, HEVC, AV1, fragmented MP4, HLS, WebM/Opus, a truncated stream, zero-duration metadata, and a valid file whose primary retriever fails.
- **Verified:** add export tests at limit minus one, limit, limit plus one, very large collections, cancellation, partial output cleanup, and import of the documented maximum.
- **Verified:** add clean-clone tests that enumerate every path named by release policy and documentation. Any reference to a deleted workflow or ignored required file must fail.
- **Verified:** continue the existing accessibility roadmap with TalkBack, 200 percent font, RTL, keyboard/switch traversal, reduced motion, contrast, and async announcements on the largest feeds and editors ([Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics), [Compose accessibility codelab](https://developer.android.com/codelabs/jetpack-compose-accessibility), [WCAG 2.2](https://www.w3.org/TR/WCAG22/)).
- **Needs live validation:** add Android 16 physical-device rotation and API 37 preview coverage when the toolchain and isolated device environment permit it. Android 17 background-audio changes need a targeted sound preview/editor pass before targetSdk 37 ([Android 17 background audio](https://developer.android.com/about/versions/17/changes/bg-audio)).

### Dependency strategy

- **Verified:** upgrades should be handled by compatibility cohort, not one library at a time. Media3 plus codec tests is one cohort. Room plus schema/migration tests is another. Compose BOM, Navigation, and accessibility snapshots form a UI cohort. OkHttp, Retrofit, and provider fixtures form a network cohort.
- **Verified:** current production versions include Compose BOM 2026.06.01, Room 2.7.2, OkHttp 5.4.0, Coil 3.5.0, Media3 1.11.0, WorkManager 2.11.2, and Glance 1.2.0-rc01 (`gradle/libs.versions.toml`). The existing dependency roadmap item already tracks the current stable or bugfix upgrade for the last three libraries. Do not create a second upgrade item.
- **Likely:** the next stable Room line and later Compose BOMs should wait for their current toolchain cohort and migration/screenshot evidence. A newer version number alone is not a product improvement ([Room releases](https://developer.android.com/jetpack/androidx/releases/room), [Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping)).

## Rejected Ideas

- **More generic wallpaper APIs:** rejected for now. Reddit, local media, Wallhaven, daily sources, community content, Pexels, and Pixabay already provide breadth. Another catalog increases credentials, terms, caching rules, and failure states without solving a demonstrated user problem.
- **Replace Reddit-first discovery:** rejected. Reddit is intentionally Aura’s strongest discovery surface. The correct response to platform change is OAuth, galleries, feed presets, attribution, deletion, and health reporting.
- **Remove YouTube-first discovery:** rejected. Keep search, source attribution, creator context, and official playback. Restrict extraction and downstream actions only where authorization is missing.
- **TikTok-style infinite short-video feed:** rejected. It raises bandwidth, decoder churn, distraction, and moderation cost. Aura’s video surface should optimize choosing a durable wallpaper, not session length. Research on short-video engagement supports caution rather than copying the mechanic ([study](https://arxiv.org/abs/2208.09577)).
- **AI-generated wallpaper as the default feed:** rejected. It weakens the Reddit-first identity and repeats a major category complaint. Keep generated wallpaper optional and clearly labeled.
- **Accounts, credits, ads, subscriptions, creator payouts, or a social marketplace:** rejected. They add support, fraud, moderation, and privacy cost while erasing Aura’s clearest contrast with Zedge.
- **A KLWP-style full visual programming system:** rejected. [KLWP](https://play.google.com/store/apps/details?id=org.kustom.wallpaper) already serves that expert niche. Aura should improve approachable presets, editors, and live effects.
- **Desktop companion synchronization:** rejected. Wallpaper Engine’s companion is useful for its existing desktop product, but Aura gains more from local import, SAF folders, and share intents.
- **Bundle Deno or Node solely for yt-dlp:** rejected. The size and maintenance cost conflicts with the APK-size strategy. The current embedded QuickJS path should be kept minimal, pinned, inventoried, and tested.
- **Request broad storage or battery-optimization exemptions:** rejected. Use Photo Picker, SAF, MediaStore, WorkManager, and clear user-triggered foreground transfers ([Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker), [SAF](https://developer.android.com/training/data-storage/shared/documents-files)).
- **Promise per-app notification sounds:** rejected. Notification channels belong to the posting app and become user-controlled after creation ([notification channels](https://developer.android.com/develop/ui/views/notifications/channels)).
- **Unlimited playlists, cache, or per-frame blurred Fit backgrounds:** rejected. Every decoded-media feature needs an explicit item count, byte budget, sampling rule, and battery fallback.
- **Open arbitrary URLs or paths through external automation:** rejected. Any automation surface must be opt-in, accept stable IDs from an allowlist, throttle calls, and log results.
- **Use an open-source downloader as proof of content rights:** rejected. NewPipe, yt-dlp, Seal, and YTDLnis are technical implementations. Their licenses do not grant rights to the media they access.

## Sources

### Project and tracker

- https://github.com/SysAdminDoc/Aura
- https://github.com/SysAdminDoc/Aura/releases/tag/v6.45.3
- https://github.com/SysAdminDoc/Aura/issues/2
- https://github.com/SysAdminDoc/Aura/issues/44
- https://github.com/SysAdminDoc/Aura/issues/47
- https://github.com/SysAdminDoc/Aura/pull/48
- https://github.com/SysAdminDoc/Aura/discussions/45
- https://github.com/SysAdminDoc/Aura/discussions/46

### Open-source products and catalogs

- https://github.com/ammargitham/WallFlow
- https://github.com/muzei/muzei
- https://github.com/thissayantan/wallora
- https://github.com/NineCSdev/nothing-wallpaper-changer
- https://github.com/cvzi/darkmodewallpaper
- https://github.com/Anthonyy232/Paperize
- https://github.com/Hamza417/Peristyle
- https://github.com/maocide/UndeadWallpaper
- https://github.com/Hash-Studios/prism
- https://github.com/JunkFood02/Seal
- https://github.com/deniscerri/ytdlnis
- https://github.com/yausername/youtubedl-android
- https://github.com/TeamNewPipe/NewPipeExtractor
- https://github.com/DeweyReed/UltimateRingtonePicker
- https://github.com/google/ringdroid
- https://github.com/offa/android-foss
- https://github.com/pcqpcq/open-source-android-apps/blob/master/categories/personalization.md
- https://github.com/F3FFO/AndroidFossApps/blob/main/APPS.md

### Commercial products and community evidence

- https://www.zedge.net/
- https://help.zedge.net/hc/en-us/articles/360024313191-ZEDGE-for-Android-FAQ
- https://help.zedge.net/hc/en-us/articles/360024595751-Purchasing-Zedge-credit-bundles
- https://play.google.com/store/apps/details?id=com.backdrops.wallpapers
- https://store.steampowered.com/app/431960/Wallpaper_Engine/
- https://help.wallpaperengine.io/en/mobile/setup.html
- https://play.google.com/store/apps/details?id=com.shanga.walli
- https://play.google.com/store/apps/details?id=com.hampusolsson.abstruct
- https://play.google.com/store/apps/details?id=org.kustom.wallpaper
- https://docs.kustom.rocks/
- https://www.reddit.com/r/androidapps/comments/1e1xwms/
- https://www.reddit.com/r/androidapps/comments/y6x9o6/
- https://www.reddit.com/r/androidapps/comments/1p90p95/
- https://www.reddit.com/r/fossdroid/comments/11vj3a4/
- https://www.reddit.com/r/GooglePixel/comments/18eqrae/
- https://www.reddit.com/r/GooglePixel/comments/1fgdg84/
- https://www.reddit.com/r/AndroidQuestions/comments/m4gden/
- https://www.reddit.com/r/tasker/comments/sbz15u/
- https://support.google.com/phoneapp/thread/221331571/
- https://news.ycombinator.com/item?id=41641704
- https://news.ycombinator.com/item?id=28791047

### Platform, policy, and standards

- https://developer.android.com/reference/android/service/wallpaper/WallpaperService
- https://developer.android.com/about/versions/16/features
- https://developer.android.com/reference/android/app/wallpaper/WallpaperDescription
- https://developer.android.com/about/versions/16/behavior-changes-all
- https://developer.android.com/about/versions/17/behavior-changes-all
- https://developer.android.com/about/versions/17/behavior-changes-17
- https://developer.android.com/about/versions/17/changes/bg-audio
- https://developer.android.com/about/versions/17/migration
- https://developer.android.com/media/platform/supported-formats
- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/media/media3/transformer
- https://developer.android.com/media/media3/transformer/transformations
- https://developer.android.com/media/media3/transformer/supported-formats
- https://developer.android.com/develop/ui/compose/accessibility/semantics
- https://developer.android.com/codelabs/jetpack-compose-accessibility
- https://developer.android.com/develop/ui/compose/testing/common-patterns
- https://www.w3.org/TR/WCAG22/
- https://developer.android.com/guide/topics/resources/app-languages
- https://developer.android.com/guide/topics/resources/pseudolocales
- https://support.google.com/googleplay/android-developer/answer/11926878?hl=en
- https://developer.android.com/developer-verification/guides
- https://developer.android.com/developer-verification/guides/faq
- https://developer.android.com/training/data-storage/shared/documents-files
- https://developer.android.com/training/data-storage/shared/photo-picker
- https://developer.android.com/develop/background-work/background-tasks/data-transfer-options
- https://developer.android.com/develop/background-work/background-tasks/uidt
- https://developer.android.com/develop/background-work/services/fgs/timeout
- https://developer.android.com/privacy-and-security/risks/untrustworthy-contentprovider-provided-filename
- https://developer.android.com/privacy-and-security/risks/zip-path-traversal
- https://developer.android.com/guide/practices/page-sizes
- https://developer.android.com/topic/performance/baselineprofiles/overview
- https://f-droid.org/en/docs/Inclusion_Policy/
- https://izzyondroid.org/docs/general/AppInclusionPolicy/

### Provider, license, security, and dependency sources

- https://www.youtube.com/static?template=terms
- https://developers.google.com/youtube/terms/developer-policies
- https://support.google.com/googleplay/android-developer/answer/9888072?hl=en
- https://redditinc.com/policies/data-api-terms
- https://redditinc.com/policies/developer-terms
- https://support.reddithelp.com/hc/en-us/articles/16160319875092-Reddit-Data-API-Wiki
- https://github.com/reddit-archive/reddit/wiki/oauth2
- https://www.reddit.com/dev/api/
- https://developers.reddit.com/docs/api/redditapi/models/classes/Post
- https://www.gnu.org/licenses/gpl-faq.en.html
- https://github.com/yt-dlp/yt-dlp/security
- https://firebase.google.com/docs/app-check
- https://firebase.google.com/docs/app-check/android/play-integrity-provider
- https://developer.android.com/jetpack/androidx/releases/media3
- https://developer.android.com/jetpack/androidx/releases/work
- https://developer.android.com/jetpack/androidx/releases/room
- https://developer.android.com/jetpack/androidx/releases/glance
- https://developer.android.com/develop/ui/compose/bom
- https://developer.android.com/develop/ui/compose/bom/bom-mapping
- https://coil-kt.github.io/coil/changelog/
- https://firebase.google.com/support/release-notes/android

### Research

- https://research.google/pubs/a-decade-of-privacy-relevant-android-app-reviews-large-scale-trends/
- https://arxiv.org/abs/2208.09577
- https://arxiv.org/abs/2101.09176
- https://arxiv.org/abs/1607.04373
- https://arxiv.org/abs/1907.04519

## Open Questions

- **Needs live validation:** does the owner have written authorization from YouTube and applicable rights holders for stream extraction, download, conversion, and reuse as wallpaper or device sound? If not, those actions need a restricted distribution policy while discovery and official playback remain available.
- **Needs live validation:** has Aura been registered as a Reddit OAuth client, reviewed by Reddit where required, or covered by a separate agreement? No client ID or approval evidence belongs in the repository, so the owner account must answer this.
- **Needs live validation:** has counsel approved the licensing model for distributing NewPipeExtractor and the GPL-enabled FFmpeg payload inside an APK whose root project license is MIT? The mechanical mismatch is verified; the final legal remedy is not.
- **Assumption:** will future releases remain GitHub/Obtainium-first, or are Google Play, IzzyOnDroid, Accrescent, and F-Droid active targets? The answer changes which YouTube actions can ship, which Firebase flavor is acceptable, and which artifact-size work is urgent.
- **Needs live validation:** after OAuth, does Reddit permit Aura’s planned cache duration, offline favorites, wallpaper application, and deletion window for this exact use? The implementation should encode the approved answer, not infer it from generic API access.
- **Needs live validation:** what battery and thermal budget is acceptable for video preview, live wallpaper, parallax segmentation, and rotation on the S22 over a seven-day run? Current functional passes do not establish a user-facing battery claim.
