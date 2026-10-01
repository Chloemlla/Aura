#!/usr/bin/env python3
"""Build the one-time RTDB update that moves community votes to the private schema.

Reads a Realtime Database export and writes a multi-path update for
`firebase database:update / <output> --project <id>`:

- `/vote_counts/{contentId}/upvotes` gets the public count.
- `/vote_markers/{uid}/{contentId}` gets each legacy voter marker.
- Community upload rows get their `votes` field set to the same count.

With --drop-legacy the same update also clears the `/votes` and `/voters` roots.
Run that only once the app release that reads the new paths is out, since older
builds still read `/votes/{contentId}/upvotes`.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


FIREBASE_KEY_CHARS = ("/", ".", "#", "$", "[", "]")
COMMUNITY_UPLOAD_VOTE_KEY = re.compile(r"^(SOUND|WALLPAPER)::COMMUNITY::(cu|cw)_([A-Za-z0-9_-]{1,200})$")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Build the one-time vote privacy backfill update for Aura community RTDB data."
    )
    parser.add_argument("--database-export", required=True, help="JSON export containing the community RTDB roots.")
    parser.add_argument("--output", required=True, help="Path for the multi-path update JSON.")
    parser.add_argument(
        "--drop-legacy",
        action="store_true",
        help="Also clear the legacy /votes and /voters roots in the same update.",
    )
    return parser.parse_args()


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def sanitize_key(value: str) -> str:
    safe = value.strip()
    for char in FIREBASE_KEY_CHARS:
        safe = safe.replace(char, "_")
    return safe


def object_root(database_export: dict[str, Any], root: str) -> dict[str, Any]:
    value = database_export.get(root, {})
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise ValueError(f"{root} must be a JSON object when present")
    return value


def whole_count(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return 0
    return max(0, int(value))


def community_upload_root(content_id: str) -> tuple[str, str] | None:
    match = COMMUNITY_UPLOAD_VOTE_KEY.fullmatch(content_id)
    if not match:
        return None
    content_type, prefix, upload_id = match.groups()
    if content_type == "SOUND" and prefix == "cu":
        return "community_sounds", upload_id
    if content_type == "WALLPAPER" and prefix == "cw":
        return "community_wallpapers", upload_id
    return None


def build_vote_privacy_backfill(database_export: Any, drop_legacy: bool = False) -> dict[str, Any]:
    if not isinstance(database_export, dict):
        raise ValueError("Database export must be a JSON object")

    legacy_votes = object_root(database_export, "votes")
    legacy_voters = object_root(database_export, "voters")
    existing_counts = object_root(database_export, "vote_counts")

    voters_by_content: dict[str, set[str]] = {}
    legacy_counts: dict[str, int] = {}
    for content_id, raw_vote in legacy_votes.items():
        if not isinstance(raw_vote, dict):
            continue
        legacy_counts[content_id] = whole_count(raw_vote.get("upvotes"))
        voters = raw_vote.get("voters", {})
        if isinstance(voters, dict):
            voters_by_content.setdefault(content_id, set()).update(key for key, value in voters.items() if value is True)
    for content_id, raw_voters in legacy_voters.items():
        if isinstance(raw_voters, dict):
            voters_by_content.setdefault(content_id, set()).update(
                key for key, value in raw_voters.items() if value is True
            )

    updates: dict[str, Any] = {}
    content_ids = sorted(set(legacy_counts) | set(voters_by_content))
    count_rows = 0
    marker_rows = 0
    upload_rows = 0
    for content_id in content_ids:
        existing = existing_counts.get(content_id)
        existing_upvotes = whole_count(existing.get("upvotes")) if isinstance(existing, dict) else 0
        voters = voters_by_content.get(content_id, set())
        upvotes = max(legacy_counts.get(content_id, 0), existing_upvotes, len(voters))
        if upvotes > 0:
            updates[f"vote_counts/{content_id}/upvotes"] = upvotes
            count_rows += 1
        for voter in sorted(voters):
            safe_voter = sanitize_key(voter)
            if safe_voter:
                updates[f"vote_markers/{safe_voter}/{content_id}"] = True
                marker_rows += 1

        upload = community_upload_root(content_id)
        if upload is None or upvotes <= 0:
            continue
        root, upload_id = upload
        row = object_root(database_export, root).get(upload_id)
        if isinstance(row, dict) and row.get("storagePath"):
            updates[f"{root}/{upload_id}/votes"] = max(whole_count(row.get("votes")), upvotes)
            upload_rows += 1

    if drop_legacy:
        updates["votes"] = None
        updates["voters"] = None

    return {
        "updates": dict(sorted(updates.items())),
        "summary": {
            "voteCounts": count_rows,
            "voteMarkers": marker_rows,
            "uploadVoteFields": upload_rows,
            "legacyDropped": drop_legacy,
        },
    }


def main() -> int:
    args = parse_args()
    backfill = build_vote_privacy_backfill(read_json(Path(args.database_export)), drop_legacy=args.drop_legacy)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(backfill["updates"], indent=2, sort_keys=True) + "\n", encoding="utf-8")
    summary = backfill["summary"]
    sys.stdout.write(
        f"wrote {output}: {summary['voteCounts']} counts, {summary['voteMarkers']} markers, "
        f"{summary['uploadVoteFields']} upload vote fields, legacy dropped: {summary['legacyDropped']}\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
