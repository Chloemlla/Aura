# Rotation trigger boot behavior

Aura does not use boot-completed startup for wallpaper rotation triggers. The
manifest may keep `android.permission.RECEIVE_BOOT_COMPLETED` only for the
separate ringtone restoration receiver. The machine-readable contract is
[`rotation-trigger-boot-behavior.json`](rotation-trigger-boot-behavior.json).

## Current decision

| Field | Value |
| --- | --- |
| Status | `rotationTriggersDoNotBootStart` |
| Scoped boot permission | `android.permission.RECEIVE_BOOT_COMPLETED` |
| Allowed boot receiver | `.service.RingtoneRestorationReceiver` |
| User-facing behavior | Rotation triggers resume after opening Aura. |

## Behavior

Opt-in unlock and screen-off rotation triggers use
`RotationTriggerService`, which dynamically registers `ACTION_USER_PRESENT` and
`ACTION_SCREEN_OFF` while the foreground service is running. Aura starts or
stops that service when the user changes the Settings toggles and reconciles
the service on app cold start.

After a device reboot, Aura does not start wallpaper rotation from boot. Existing
WorkManager periodic jobs can continue through platform scheduling, but
unlock/screen-off trigger listeners resume after opening Aura because there is
no rotation boot receiver.

`RingtoneRestorationReceiver` is the only allowed boot-completed receiver. It is
separate from rotation triggers and is limited to restoring user-selected
ringtone state after reboot or package replacement.

## Release gate

Local release checks must run:

- `tools/rotation_boot_permission_check.py --policy docs/rotation-trigger-boot-behavior.json --repo-root .`

The gate fails if an unreviewed boot receiver is added, if boot-completed
source terms appear in rotation trigger code, if store/privacy disclosure docs
claim rotation boot scheduling, or if the boot behavior packet loses the
current decision.

## Future boot receiver option

If Aura later needs boot restoration for rotation triggers, add a dedicated
receiver only after the release owner accepts the foreground-service policy
impact. The receiver must:

- Start `RotationTriggerService` only when the user already opted into unlock or
  screen-off triggers.
- Preserve the visible foreground notification and special-use foreground
  service declaration.
- Update Play App content, Data safety, alternative-store disclosures, release
  QA, and this packet in the same change.

## Sources

- Android `ACTION_BOOT_COMPLETED`: https://developer.android.com/reference/android/content/Intent#ACTION_BOOT_COMPLETED
- Android foreground service types: https://developer.android.com/about/versions/14/changes/fgs-types-required
- Play Console foreground service declarations: https://support.google.com/googleplay/android-developer/answer/13392821
