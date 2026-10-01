#!/usr/bin/env python3
"""Build the one-time RTDB update that moves community vote markers to the private schema.

Reads a Realtime Database export and writes a multi-path update for
`firebase database:update / <output> --project <id>`:

- `/vote_markers/{uid}/{contentId}` gets each legacy voter marker.

Counts are not part of the update. The `seedLegacyVoteCounts` function creates each
`/vote_counts/{contentId}` row (and the upload row's `votes` mirror) inside a transaction
that leaves a counted row alone, so votes cast between the export and the apply are kept.
An absolute count written from an export would overwrite them.

With --drop-legacy the same update also clears the `/votes` and `/voters` roots. The tool
refuses while any legacy content in the export still has no `/vote_counts` row, so let the
seeding job run and take a fresh export first. Run that only once the app release that reads
the new paths is out, since older builds still read `/votes/{contentId}/upvotes`.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


FIREBASE_KEY_CHARS = ("/", ".", "#", "$", "[", "]")


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


def is_whole_count(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def build_vote_privacy_backfill(database_export: Any, drop_legacy: bool = False) -> dict[str, Any]:
    if not isinstance(database_export, dict):
        raise ValueError("Database export must be a JSON object")

    legacy_votes = object_root(database_export, "votes")
    legacy_voters = object_root(database_export, "voters")
    existing_counts = object_root(database_export, "vote_counts")

    voters_by_content: dict[str, set[str]] = {}
    for content_id, raw_vote in legacy_votes.items():
        if not isinstance(raw_vote, dict):
            continue
        voters = raw_vote.get("voters", {})
        if isinstance(voters, dict):
            voters_by_content.setdefault(content_id, set()).update(key for key, value in voters.items() if value is True)
    for content_id, raw_voters in legacy_voters.items():
        if isinstance(raw_voters, dict):
            voters_by_content.setdefault(content_id, set()).update(
                key for key, value in raw_voters.items() if value is True
            )

    updates: dict[str, Any] = {}
    # The seeding job gives every key under either legacy root a count row.
    content_ids = sorted(set(legacy_votes) | set(legacy_voters))
    marker_rows = 0
    missing_counts: list[str] = []
    for content_id in content_ids:
        for voter in sorted(voters_by_content.get(content_id, set())):
            safe_voter = sanitize_key(voter)
            if safe_voter:
                updates[f"vote_markers/{safe_voter}/{content_id}"] = True
                marker_rows += 1
        existing = existing_counts.get(content_id)
        if not (isinstance(existing, dict) and is_whole_count(existing.get("upvotes"))):
            missing_counts.append(content_id)

    if drop_legacy:
        if missing_counts:
            raise ValueError(
                f"{len(missing_counts)} legacy content IDs have no /vote_counts row yet "
                f"(first: {missing_counts[0]}). Let seedLegacyVoteCounts run, export again, then drop."
            )
        updates["votes"] = None
        updates["voters"] = None

    return {
        "updates": dict(sorted(updates.items())),
        "summary": {
            "voteMarkers": marker_rows,
            "missingCounts": len(missing_counts),
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
        f"wrote {output}: {summary['voteMarkers']} markers, {summary['missingCounts']} content IDs "
        f"still waiting for a count, legacy dropped: {summary['legacyDropped']}\n"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
