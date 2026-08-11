from __future__ import annotations

import shutil
import tempfile
import unittest
from pathlib import Path

from tools.community_identity_laziness_check import (
    CommunityIdentityLazinessError,
    iter_init_block_bodies,
    validate_community_identity_laziness,
    validate_moderation_listener_consent,
)


REPO_ROOT = Path(__file__).resolve().parents[2]


class CommunityIdentityLazinessCheckTest(unittest.TestCase):
    def test_live_community_identity_laziness_passes(self) -> None:
        result = validate_community_identity_laziness(REPO_ROOT)

        self.assertEqual("ok", result["status"])
        self.assertGreater(result["lazyEnsureSignedInCalls"], 0)

    def test_rejects_startup_sign_in(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = copy_required_tree(Path(tmpdir))
            app = repo / "app/src/main/java/com/freevibe/FreeVibeApp.kt"
            app.write_text(app.read_text(encoding="utf-8") + "\nfun eager() { ensureSignedIn() }\n", encoding="utf-8")

            with self.assertRaises(CommunityIdentityLazinessError):
                validate_community_identity_laziness(repo)

    def test_rejects_missing_lazy_write_path(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = copy_required_tree(Path(tmpdir))
            upload_repo = repo / "app/src/main/java/com/freevibe/data/repository/UploadRepository.kt"
            upload_repo.write_text(upload_repo.read_text(encoding="utf-8").replace("ensureSignedIn(", "currentUserId("), encoding="utf-8")

            with self.assertRaises(CommunityIdentityLazinessError):
                validate_community_identity_laziness(repo)


def copy_required_tree(destination: Path) -> Path:
    paths = [
        "app/src/main/java/com/freevibe/FreeVibeApp.kt",
        "app/src/main/java/com/freevibe/service/CommunityIdentityProvider.kt",
        "app/src/main/java/com/freevibe/ui/screens/settings/SettingsScreen.kt",
        *[
            "app/src/main/java/com/freevibe/data/repository/" + name
            for name in (
                "UploadRepository.kt",
                "WallpaperUploadRepository.kt",
                "VoteRepository.kt",
                "CommunityReportRepository.kt",
                "CreatorProfileRepository.kt",
                "CommunityBlockRepository.kt",
            )
        ],
    ]
    for relative_path in paths:
        source = REPO_ROOT / relative_path
        target = destination / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    return destination


class ModerationListenerConsentTest(unittest.TestCase):
    VOTE_REPOSITORY = "app/src/main/java/com/freevibe/data/repository/VoteRepository.kt"

    def _stage(self, source: str) -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        target = root / self.VOTE_REPOSITORY
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source, encoding="utf-8")
        return root

    def _live_source(self) -> str:
        return (REPO_ROOT / self.VOTE_REPOSITORY).read_text(encoding="utf-8")

    def test_live_moderation_listener_is_consent_gated(self) -> None:
        self.assertGreater(validate_moderation_listener_consent(REPO_ROOT), 0)

    def test_no_init_block_attaches_a_firebase_listener(self) -> None:
        bodies = iter_init_block_bodies(self._live_source())

        self.assertGreater(len(bodies), 0)
        for body in bodies:
            self.assertNotIn("addValueEventListener", body)

    def test_rejects_a_listener_attached_from_an_init_block(self) -> None:
        source = self._live_source().replace(
            "    init {",
            "    init {\n        moderationRef?.addValueEventListener(listener)",
            1,
        )

        with self.assertRaises(CommunityIdentityLazinessError) as ctx:
            validate_moderation_listener_consent(self._stage(source))

        self.assertIn("init block", str(ctx.exception))

    def test_rejects_dropping_a_consent_preference(self) -> None:
        source = self._live_source().replace("communityGuidelinesAccepted", "alwaysTrue")

        with self.assertRaises(CommunityIdentityLazinessError) as ctx:
            validate_moderation_listener_consent(self._stage(source))

        self.assertIn("communityGuidelinesAccepted", str(ctx.exception))

    def test_rejects_removing_listener_teardown(self) -> None:
        source = self._live_source().replace("removeEventListener", "keepEventListener")

        with self.assertRaises(CommunityIdentityLazinessError) as ctx:
            validate_moderation_listener_consent(self._stage(source))

        self.assertIn("remove the moderation listener", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
