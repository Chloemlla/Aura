# Local media relink

Aura treats a library identity and a device file locator as separate things. A
favorite, collection entry, history row, edit, rotation exclusion, or wallpaper
target can still be useful when Android can no longer open the original file.
Aura keeps that record visible instead of deleting its path.

## What users see

Downloads and Favorites show a Relink action beside unavailable local media.
The local wallpaper catalog in Settings shows the same action for individual
images. A folder whose Android document permission was revoked also offers
Repair folder.

Aura reports four local states:

- Available means Android can read a non-empty file.
- Missing means the locator no longer resolves.
- Permission revoked means Android rejected access to the document or folder.
- Corrupt means the file is empty, unreadable, or cannot provide required media
  metadata.

The remote provider state is tracked separately. A Reddit post or YouTube item
can disappear while its saved local copy remains healthy, and the reverse can
also happen.

## Individual relink

The system file picker is limited to the expected media family. Aura still
checks the selected bytes instead of trusting a filename or provider MIME type.
Images must decode with valid dimensions. Sounds and non-GIF videos must expose
a positive duration. Audio-only containers cannot be used as video, and video
containers cannot be used as sound.

When the original technical record is available, Aura compares SHA-256, byte
size, dimensions, duration, and media family. An exact digest is accepted even
if older metadata is incomplete. A likely mismatch shows the differing fields
and a Use anyway action. Dismissing that warning or cancelling the picker does
not change the library.

A successful relink is one Room transaction. It replaces the locator while
preserving the stable ID, name, tags, added date, favorite state, collections,
wallpaper history, rotation exclusion, and home or lock target. Empty locators
restored from a portable backup are filled only for records with the same stable
ID and source. They never update unrelated blank rows. Aura also refuses to
overwrite a different catalog item that already owns the selected locator.

Wallpaper and sound document URIs must retain a readable Android permission.
Video wallpaper replacements are copied to Aura's private
`files/media_originals/` directory. The copy is checked by size and SHA-256
before Room receives its locator. A replacement with different bytes drops the
old optimized apply copy so Aura cannot reuse an incompatible derivative.

## Folder repair

Folder repair asks for a new Android document-tree grant and scans the selected
folder. It automatically matches only exact SHA-256 content. Filename, size,
and dimensions are used to choose between duplicate candidates, but they never
replace the digest requirement. Corrupt candidates and locators already owned
by another catalog identity are left alone.

One repair run considers at most 500 old items. Every candidate is used once.
The folder walk reads full candidate bytes only when the reported size could
match that batch, unless either side lacks a size. This keeps a large photo
library from being hashed in full for every repair.
Matched items keep their stable identity, tags, added date, target, exclusions,
and associations. The old folder record stays visible with a remaining count
until every item has been repaired. Items without a recorded digest can still
use the individual Relink action.

## Backup behavior

Library backup format 3 includes locator-free local wallpaper metadata and up
to 100 recent wallpaper history rows. Favorites format 2 carries a portable
local identity and optional SHA-256. Collections and exclusions use the same
portable identity, so their links meet again after restore.

Backups do not contain local file paths, content-provider URIs, downloaded
media bytes, provider keys, or Android document grants. Restored local items are
grouped by their home, lock, or both target and remain visible as unavailable.
The user can repair the group with the original folder or relink one file at a
time.
