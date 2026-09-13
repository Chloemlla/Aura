from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CHECKER = REPO_ROOT / "tools/foreground_service_declaration_check.py"

MANIFEST = """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />

    <application>
        <service
            android:name=".service.AudioPlaybackService"
            android:foregroundServiceType="mediaPlayback" />
    </application>
</manifest>
"""

CHANNELS = """package com.chloemlla.aura.service

object NotificationChannels {
    const val MEDIA_PLAYBACK = "media_playback"
}
"""


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def declaration_packet() -> dict:
    return {
        "status": "checked",
        "services": [
            {
                "class": "com.chloemlla.aura.service.AudioPlaybackService",
                "foregroundServiceType": "mediaPlayback",
                "notificationChannel": "media_playback",
                "trigger": "User taps play on a sound preview",
                "whyNotDeferred": "Playback must stay alive while the user browses",
                "demoVideoSteps": ["Open Sounds", "Tap a sound", "Show the notification"],
            }
        ],
        "notificationChannels": [{"id": "media_playback", "name": "Sound Preview"}],
        "permissions": {
            "FOREGROUND_SERVICE": "Base permission for all foreground services",
            "FOREGROUND_SERVICE_MEDIA_PLAYBACK": "Sound preview playback",
        },
    }


def run_checker(cwd: Path) -> subprocess.CompletedProcess:
    # The checker derives its repo root from __file__, so the live case runs the
    # committed script in place and fixture cases run a copy under a temp tree.
    return subprocess.run(
        [sys.executable, str(cwd / "tools" / CHECKER.name)],
        cwd=str(cwd),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        env={**os.environ, "PYTHONIOENCODING": "utf-8"},
    )


class ForegroundServiceDeclarationCheckTest(unittest.TestCase):
    def _fixture(self) -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        write_text(root / "tools" / CHECKER.name, CHECKER.read_text(encoding="utf-8"))
        write_text(root / "app/src/main/AndroidManifest.xml", MANIFEST)
        write_text(
            root / "app/src/main/java/com/chloemlla/aura/service/NotificationChannels.kt",
            CHANNELS,
        )
        self.write_packet(root, declaration_packet())
        return root

    def write_packet(self, root: Path, packet: dict) -> None:
        write_text(
            root / "docs/distribution/foreground-service-declaration.json",
            json.dumps(packet, indent=2),
        )

    def write_manifest(self, root: Path, text: str) -> None:
        write_text(root / "app/src/main/AndroidManifest.xml", text)

    def add_boot_receiver(self, root: Path, simple_name: str, body: str) -> None:
        """Declare a BOOT_COMPLETED receiver and give it a source file.

        The manifest cannot say which service a receiver starts, so the checker
        reads the receiver's own source. A fixture without that file would pass
        for the wrong reason, so both halves are added together.
        """
        write_text(
            root / f"app/src/main/java/com/chloemlla/aura/service/{simple_name}.kt",
            f"package com.chloemlla.aura.service\n\n{body}\n",
        )
        manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.write_manifest(
            root,
            manifest.replace(
                "</application>",
                "    <receiver android:name=\".service.%s\">\n"
                "        <intent-filter>\n"
                '            <action android:name="android.intent.action.BOOT_COMPLETED" />\n'
                "        </intent-filter>\n"
                "    </receiver>\n"
                "</application>" % simple_name,
            ),
        )

    def test_live_repository_passes(self) -> None:
        result = run_checker(REPO_ROOT)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn(
            "OK: foreground-service declaration packet is consistent", result.stdout
        )

    def test_fixture_baseline_passes(self) -> None:
        result = run_checker(self._fixture())

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_finds_notification_channels_in_renamed_package(self) -> None:
        # Pins NotificationChannels.kt to com/chloemlla/aura. If the path reverts to
        # the pre-rename com/freevibe package this reports the file as missing.
        result = run_checker(self._fixture())

        self.assertNotIn("Missing NotificationChannels.kt", result.stdout)

    def test_rejects_missing_notification_channels_source(self) -> None:
        root = self._fixture()
        (root / "app/src/main/java/com/chloemlla/aura/service/NotificationChannels.kt").unlink()

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("Missing NotificationChannels.kt", result.stdout)

    def test_rejects_manifest_permission_missing_from_packet(self) -> None:
        root = self._fixture()
        packet = declaration_packet()
        del packet["permissions"]["FOREGROUND_SERVICE_MEDIA_PLAYBACK"]
        self.write_packet(root, packet)

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn(
            "Manifest permission FOREGROUND_SERVICE_MEDIA_PLAYBACK has no declaration packet row",
            result.stdout,
        )

    def test_rejects_service_type_drift(self) -> None:
        root = self._fixture()
        packet = declaration_packet()
        packet["services"][0]["foregroundServiceType"] = "dataSync"
        self.write_packet(root, packet)

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("type mismatch", result.stdout)

    def test_rejects_unknown_notification_channel(self) -> None:
        root = self._fixture()
        packet = declaration_packet()
        packet["services"][0]["notificationChannel"] = "missing_channel"
        self.write_packet(root, packet)

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("not found in NotificationChannels.kt", result.stdout)

    def test_rejects_packet_status_not_checked(self) -> None:
        root = self._fixture()
        packet = declaration_packet()
        packet["status"] = "draft"
        self.write_packet(root, packet)

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("status is not 'checked'", result.stdout)

    def test_rejects_boot_completed_receiver_linked_to_media_playback(self) -> None:
        root = self._fixture()
        self.add_boot_receiver(
            root,
            "BootPlaybackReceiver",
            "class BootPlaybackReceiver : BroadcastReceiver() {\n"
            "    override fun onReceive(context: Context, intent: Intent) {\n"
            "        context.startForegroundService(\n"
            "            Intent(context, AudioPlaybackService::class.java)\n"
            "        )\n"
            "    }\n"
            "}",
        )

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn(
            "BOOT_COMPLETED receiver .service.BootPlaybackReceiver starts the "
            "mediaPlayback foreground service",
            result.stdout,
        )

    def test_accepts_boot_permission_alongside_an_unrelated_media_playback_service(self) -> None:
        # RECEIVE_BOOT_COMPLETED and an unrelated mediaPlayback service type are not a
        # boot launch path. A whole-manifest search for the two strings reports one.
        root = self._fixture()
        manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.write_manifest(
            root,
            manifest.replace(
                '<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
                '<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />\n'
                '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />',
            ),
        )
        self.add_boot_receiver(
            root,
            "RingtoneRestorationReceiver",
            "class RingtoneRestorationReceiver : BroadcastReceiver() {\n"
            "    override fun onReceive(context: Context, intent: Intent) {\n"
            "        WorkManager.getInstance(context).enqueueUniqueWork(\n"
            '            "ringtone_restoration",\n'
            "            ExistingWorkPolicy.REPLACE,\n"
            "            OneTimeWorkRequestBuilder<RingtoneRestorationWorker>().build(),\n"
            "        )\n"
            "    }\n"
            "}",
        )

        result = run_checker(root)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("Play policy risk", result.stdout)

    def test_reads_a_fully_qualified_boot_receiver(self) -> None:
        # The manifest writes component names relative to the package, but a
        # fully qualified one is equally valid and has to resolve to the same file.
        root = self._fixture()
        self.add_boot_receiver(
            root,
            "BootPlaybackReceiver",
            "class BootPlaybackReceiver : BroadcastReceiver() {\n"
            "    val target = AudioPlaybackService::class.java\n"
            "}",
        )
        manifest = (root / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.write_manifest(
            root,
            manifest.replace(
                'android:name=".service.BootPlaybackReceiver"',
                'android:name="com.chloemlla.aura.service.BootPlaybackReceiver"',
            ),
        )

        result = run_checker(root)

        self.assertEqual(1, result.returncode)
        self.assertIn("Play policy risk", result.stdout)


if __name__ == "__main__":
    unittest.main()
