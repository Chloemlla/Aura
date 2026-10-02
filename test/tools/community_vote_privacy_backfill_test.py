from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from tools import community_vote_privacy_backfill as backfill_tool
from tools.community_vote_privacy_backfill import build_vote_privacy_backfill


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

    def test_backfill_moves_markers_and_leaves_counts_to_the_seeding_job(self) -> None:
        result = build_vote_privacy_backfill(self.database_export())

        self.assertEqual(
            {
                "vote_markers/uid-a/WALLPAPER::COMMUNITY::cw_wall1": True,
                "vote_markers/uid-b/WALLPAPER::COMMUNITY::cw_wall1": True,
                "vote_markers/uid-d/WALLPAPER::COMMUNITY::cw_wall1": True,
                "vote_markers/uid_c/SOUND::COMMUNITY::cu_sound1": True,
            },
            result["updates"],
        )
        self.assertEqual({"voteMarkers": 4, "missingCounts": 3, "legacyDropped": False}, result["summary"])

    def test_counts_and_upload_rows_are_never_written_from_an_export(self) -> None:
        # Votes cast after the export would be overwritten by an absolute count.
        export = self.database_export()
        export["vote_counts"]["WALLPAPER::COMMUNITY::cw_wall1"] = {"upvotes": 1}

        updates = build_vote_privacy_backfill(export)["updates"]

        self.assertFalse([key for key in updates if not key.startswith("vote_markers/")])

    def test_drop_legacy_refuses_until_every_legacy_count_has_a_row(self) -> None:
        with self.assertRaisesRegex(ValueError, "3 legacy content IDs have no /vote_counts row"):
            build_vote_privacy_backfill(self.database_export(), drop_legacy=True)

        export = self.database_export()
        for content_id in ("WALLPAPER::COMMUNITY::cw_wall1", "SOUND::COMMUNITY::cu_sound1", "SOUND::FREESOUND::zero"):
            export["vote_counts"][content_id] = {"upvotes": 0}
        result = build_vote_privacy_backfill(export, drop_legacy=True)

        self.assertIsNone(result["updates"]["votes"])
        self.assertIsNone(result["updates"]["voters"])
        self.assertTrue(result["summary"]["legacyDropped"])
        self.assertEqual(0, result["summary"]["missingCounts"])

    def test_a_count_row_that_is_not_a_whole_number_still_counts_as_missing(self) -> None:
        export = {"votes": {"item": {"upvotes": 2}}, "vote_counts": {"item": {"upvotes": -1}}}

        self.assertEqual(1, build_vote_privacy_backfill(export)["summary"]["missingCounts"])

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
            self.assertTrue(written["vote_markers/uid-a/WALLPAPER::COMMUNITY::cw_wall1"])
            self.assertNotIn("summary", written)
            self.assertIn("4 markers, 3 content IDs still waiting for a count", write.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
