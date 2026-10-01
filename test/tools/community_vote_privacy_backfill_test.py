from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools import community_vote_privacy_backfill as backfill_tool
from tools.community_vote_privacy_backfill import build_vote_privacy_backfill, community_upload_root


class CommunityVotePrivacyBackfillTest(unittest.TestCase):
    def database_export(self) -> dict:
        return {
            "votes": {
                "WALLPAPER::COMMUNITY::cw_wall1": {"upvotes": 3, "voters": {"uid-a": True, "uid-b": True}},
                "SOUND::COMMUNITY::cu_sound1": {"upvotes": 1, "voters": {"uid/c": True}},
                "WALLPAPER::WALLHAVEN::abc": {"upvotes": 2},
                "SOUND::FREESOUND::zero": {"upvotes": 0},
            },
            "voters": {
                "WALLPAPER::COMMUNITY::cw_wall1": {"uid-a": True, "uid-d": True},
                "SOUND::FREESOUND::zero": {"uid-e": "not-a-marker"},
            },
            "vote_counts": {
                "WALLPAPER::WALLHAVEN::abc": {"upvotes": 5},
            },
            "community_wallpapers": {
                "wall1": {"storagePath": "wallpapers/uid-z/wall1.jpg", "votes": 0},
            },
            "community_sounds": {},
        }

    def test_backfill_moves_counts_and_markers_without_losing_votes(self) -> None:
        result = build_vote_privacy_backfill(self.database_export())

        self.assertEqual(
            {
                "community_wallpapers/wall1/votes": 3,
                "vote_counts/SOUND::COMMUNITY::cu_sound1/upvotes": 1,
                "vote_counts/WALLPAPER::COMMUNITY::cw_wall1/upvotes": 3,
                "vote_counts/WALLPAPER::WALLHAVEN::abc/upvotes": 5,
                "vote_markers/uid-a/WALLPAPER::COMMUNITY::cw_wall1": True,
                "vote_markers/uid-b/WALLPAPER::COMMUNITY::cw_wall1": True,
                "vote_markers/uid-d/WALLPAPER::COMMUNITY::cw_wall1": True,
                "vote_markers/uid_c/SOUND::COMMUNITY::cu_sound1": True,
            },
            result["updates"],
        )
        self.assertEqual(
            {"voteCounts": 3, "voteMarkers": 4, "uploadVoteFields": 1, "legacyDropped": False},
            result["summary"],
        )

    def test_count_never_drops_below_distinct_voters(self) -> None:
        result = build_vote_privacy_backfill(
            {"votes": {"item": {"upvotes": 1, "voters": {"a": True, "b": True}}}, "voters": {"item": {"c": True}}}
        )

        self.assertEqual(3, result["updates"]["vote_counts/item/upvotes"])

    def test_drop_legacy_clears_both_legacy_roots(self) -> None:
        result = build_vote_privacy_backfill(self.database_export(), drop_legacy=True)

        self.assertIsNone(result["updates"]["votes"])
        self.assertIsNone(result["updates"]["voters"])
        self.assertTrue(result["summary"]["legacyDropped"])

    def test_upload_mirror_skips_missing_rows_and_mismatched_prefixes(self) -> None:
        self.assertEqual(("community_sounds", "abc"), community_upload_root("SOUND::COMMUNITY::cu_abc"))
        self.assertEqual(("community_wallpapers", "abc"), community_upload_root("WALLPAPER::COMMUNITY::cw_abc"))
        self.assertIsNone(community_upload_root("SOUND::COMMUNITY::cw_abc"))
        self.assertIsNone(community_upload_root("WALLPAPER::WALLHAVEN::cw_abc"))

        result = build_vote_privacy_backfill(self.database_export())
        self.assertNotIn("community_sounds/sound1/votes", result["updates"])

    def test_rejects_non_object_roots(self) -> None:
        with self.assertRaises(ValueError):
            build_vote_privacy_backfill([])
        with self.assertRaises(ValueError):
            build_vote_privacy_backfill({"votes": []})

    def test_main_writes_update_file_ready_for_firebase_cli(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            export_path = Path(temp) / "export.json"
            output_path = Path(temp) / "out" / "update.json"
            export_path.write_text(json.dumps(self.database_export()), encoding="utf-8")
            argv = ["prog", "--database-export", str(export_path), "--output", str(output_path)]
            with mock.patch("sys.argv", argv), mock.patch("sys.stdout.write") as write:
                self.assertEqual(0, backfill_tool.main())

            written = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(3, written["vote_counts/WALLPAPER::COMMUNITY::cw_wall1/upvotes"])
            self.assertNotIn("summary", written)
            self.assertIn("3 counts, 4 markers, 1 upload vote fields", write.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
