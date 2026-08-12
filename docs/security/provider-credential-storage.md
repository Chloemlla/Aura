# Provider Credential Storage

This runbook classifies Aura provider credentials and records the Android
Keystore-backed storage decision. The machine-readable source is
`docs/security/provider-credential-storage.json`; the guard is
`tools/provider_credential_storage_check.py`.

## Decision

User-entered provider credentials are stored in `aura_provider_credentials`
SharedPreferences encrypted with an Android Keystore AES-GCM key. Legacy
DataStore values are migrated on first read/write and removed after successful
encrypted-store writes. Provider keys remain optional, public release defaults
are blank, diagnostics redact them, and both the legacy DataStore file and the
encrypted SharedPreferences file are excluded from Android cloud backup and
device transfer.

If Android Keystore is locked, corrupt, or unavailable, Aura keeps running,
retains unmigrated DataStore values until migration can succeed, and shows a
Settings warning instead of crashing or silently discarding provider keys.

## Storage Surface

- Encrypted SharedPreferences: `aura_provider_credentials.xml`.
- Android Keystore alias: `aura_provider_credentials_v1`.
- Cipher envelope: `AES/GCM/NoPadding` with per-write random IV.
- Legacy DataStore name: `freevibe_prefs`.
- Legacy DataStore file: `datastore/freevibe_prefs.preferences_pb`.
- Android 11 Auto Backup: excluded in `backup_rules.xml`.
- Android 12+ cloud backup and device transfer: excluded in
  `data_extraction_rules.xml`.
- Diagnostics: support bundles and source diagnostics redact provider query,
  header, assignment, and `local.properties` credential shapes before sharing.

## Credentials

| ID | Provider | Classification | Runtime storage | Release default | User control |
| --- | --- | --- | --- | --- | --- |
| `wallhaven-api-key` | Wallhaven | `optionalQuotaKey` | Encrypted key `wallhaven_api_key`; migrates legacy DataStore key `wallhaven_api_key`; no bundled BuildConfig field. | Blank. | Settings > API Keys > Wallhaven API Key; Clear or save blank to remove. |
| `pexels-api-key` | Pexels | `optionalQuotaKey` | Encrypted key `pexels_api_key`, defaulting to `BuildConfig.PEXELS_API_KEY`; migrates legacy DataStore key `pexels_api_key`. | Blank in public release workflow. | Settings > API Keys > Pexels API Key; Clear or save blank to remove. |
| `pixabay-api-key` | Pixabay | `optionalQuotaKey` | Encrypted key `pixabay_api_key`, defaulting to `BuildConfig.PIXABAY_API_KEY`; migrates legacy DataStore key `pixabay_api_key`. | Blank in public release workflow. | Settings > API Keys > Pixabay API Key; Clear or save blank to remove. |
| `freesound-api-key` | Freesound | `optionalQuotaKey` | Encrypted key `freesound_api_key`, defaulting to `BuildConfig.FREESOUND_API_KEY`; migrates legacy DataStore key `freesound_api_key`. | Blank in public release workflow. | Settings > API Keys > Freesound API Key; Clear or save blank to remove. |
| `soundcloud-client-id` | SoundCloud | `publicClientId` | BuildConfig-only `SOUNDCLOUD_CLIENT_ID`; no DataStore key. | Blank in public release workflow. | No Settings field; blank public default makes the dormant source return no results. |
| `stability-ai-key` | Stability AI | `paidSensitiveSecret` | Encrypted key `stability_ai_key`, defaulting to `BuildConfig.STABILITY_AI_KEY`; migrates legacy DataStore key `stability_ai_key`. | Blank in public release workflow. | Settings > API Keys > Stability AI API Key and generated wallpaper key field; Clear or save blank to remove. |

## Guard

Run:

```powershell
py -3 tools\provider_credential_storage_check.py --policy docs\security\provider-credential-storage.json --repo-root .
```

The guard fails if a credential row is missing from this runbook, if an
encrypted credential mapping or legacy DataStore migration key is missing from
`PreferencesManager`, if the Android Keystore AES-GCM wrapper drifts, if a
Settings label, explicit Clear action, or Keystore warning is missing, if
Gradle release defaults drift away from blank provider values, if backup
exclusions disappear, or if diagnostics/privacy docs stop describing redaction
and device storage.

The guard also treats `stability-ai-key` as the paid-sensitive sentinel row. It
fails if Stability stops being an encrypted `paidSensitiveSecret`, if the
`STABILITY_AI_KEY` / `stability.ai.key` release default is no longer blank, if
`stability.ai.key` is missing from redaction coverage, or if the explicit Clear
control is no longer documented.
