# Rotation Exclusions

Rotation exclusions let someone remove an item from automatic wallpaper
selection without deleting it or hiding it from a feed. The item stays visible
and can still be opened, downloaded, edited, shared, or applied manually.

## User Controls

The wallpaper detail menu and each video card include **Exclude from rotation**.
Downloaded wallpapers, local catalog items, and wallpaper history expose the
same action. The confirmation includes **Undo**. Once an item is excluded, the
action changes to **Restore to rotation**.

Settings > Wallpaper rotation > Rotation exclusions opens the persistent
manager. It can restore one item or restore every item.

## Matching Rules

One item can appear in several parts of Aura. The stored identity is designed
to match those copies without depending on a file path.

| Item | Persistent identity |
|---|---|
| Reddit or another provider | Media type, provider, and provider item ID |
| Favorite, collection item, cache row, history row, or download | The original provider identity when available |
| Local catalog item | SHA-256 content hash |
| Wallpaper pack or legacy locator-only item | SHA-256 fingerprint of the locator |

Aura stores only the locator fingerprint in the exclusion database. It does
not store a raw local path there. Local catalog files use their content hash,
so moving a file and relinking it does not restore an unwanted item to
rotation.

## Automatic Apply Paths

The shared exclusion filter is used by scheduled rotation, one-shot rotation
triggers, Quick Settings, widgets, dark and light wallpaper switching, night
variants, local-folder rotation, favorites, collections, cached provider feeds,
and 24-hour wallpaper packs.

If every candidate in a source is excluded, Aura does not fall back to an
excluded item. WorkManager records a recoverable result and stops retrying that
persistent choice. The widget explains that an item can be restored in
Settings.

## Backup and Privacy

Portable library backups include the media identity, optional content hash,
display title, safe HTTPS thumbnail, one-way locator fingerprint, and exclusion
time. They never include a raw exclusion path. Imports are validated, bounded
to 10,000 exclusions, de-duplicated, and committed in the same Room transaction
as the rest of the library plan.

Android cloud backup and device transfer still exclude Aura's Room database.
Rotation exclusions move between devices only when the user explicitly exports
and imports a portable library backup.
