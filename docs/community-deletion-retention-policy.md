# Community Deletion Retention and Tombstone Policy

Cycle 67 defines what remains after a community sound or wallpaper upload is
deleted by its owner or by an admin rights takedown.

## Policy

| Data | Action on upload deletion | Visibility | Reason |
| --- | --- | --- | --- |
| Firebase Storage object | Delete immediately; missing object counts as already removed | Not public after delete | Removes the uploaded media from direct access paths. |
| Public upload metadata | Delete immediately from `/community_sounds` or `/community_wallpapers` | Not public after delete | Removes the item from feeds and detail lookups. |
| Private owner index | Delete immediately from `/owner_uploads/{uid}` | Owner/admin only before delete | Prevents deleted uploads from appearing in owner delete lists. |
| Private deletion tombstone | Create `/community_upload_deletions/{publicId}` | Custom-claim admin only | Keeps minimal evidence for owner delete/admin takedown troubleshooting without exposing reporter or owner history publicly. |
| Takedown receipt | Retain existing `/community_takedown_receipts/{reportId}` rows | Custom-claim admin only | Preserves rights-confirmed admin action evidence and retry state. |
| Report/resolution rows | Retain existing report and resolution rows | Custom-claim admin only | Preserves moderation evidence and prevents hiding reporter privacy behind public metadata. |
| Moderation hide row | Retain when admin takedown writes `/moderation/{contentId}` | Public boolean only | Keeps deleted content hidden if stale clients still reference its content ID. |
| Vote aggregates and voter markers | Retain until callable/admin cleanup can migrate vote privacy | Currently public under existing vote rules | Avoids client-side broad deletes that rules cannot safely authorize today; queued for account-deletion semantics. |

## Tombstone Shape

`/community_upload_deletions/{publicId}` stores:

- `publicId`
- `uploadId`
- `contentType`
- `metadataPath`
- `storagePath`
- `uploaderUid`
- `deletedByUid`
- `deletedAt`
- `reason`

Reasons:

- `OWNER_DELETE`
- `ADMIN_TAKEDOWN`

Rules make tombstones admin-readable only. Owners can create their own initial
`OWNER_DELETE` tombstone only as part of the same multi-location update that
removes public metadata and owner index rows. The rules check that the metadata
row existed before the write, belonged to the caller, carried the same
`storagePath`, and is gone after it, and that the tombstone key is the upload's
own public ID. A tombstone for an upload that never existed is refused. Admins
can create or update tombstones
during takedown cleanup, including `ADMIN_TAKEDOWN` tombstones. The recorded
Storage path must remain under the uploader-scoped `sounds/{uid}/` or
`wallpapers/{uid}/` prefix for the content type.

## Follow-Up

Vote markers now live under `/vote_markers/{uid}`, readable only by that account
and admins, and the public `/vote_counts` tree holds counts alone. The legacy
`/votes` and `/voters` trees are admin-only. Account deletion removes the whole
`/vote_markers/{uid}` and `/vote_locks/{uid}` subtrees plus any legacy markers,
and keeps the counts.

## Sources

- Firebase Realtime Database Android delete data:
  https://firebase.google.com/docs/database/android/read-and-write
- Firebase DatabaseReference API:
  https://firebase.google.com/docs/reference/android/com/google/firebase/database/DatabaseReference
- Firebase Storage Android delete files:
  https://firebase.google.com/docs/storage/android/delete-files
