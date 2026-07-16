from __future__ import annotations

import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.foss_reproducibility_check import compare_apks, is_signature_entry


def write_apk(
    path: Path,
    payload: bytes = b"classes",
    signature: bytes = b"signature",
    timestamp: tuple[int, int, int, int, int, int] = (2026, 7, 16, 12, 0, 0),
    reverse_order: bool = False,
) -> None:
    entries = [("classes.dex", payload), ("res/raw/data.bin", b"data")]
    if reverse_order:
        entries.reverse()
    entries.append(("META-INF/AURA.RSA", signature))
    with zipfile.ZipFile(path, "w") as archive:
        for name, content in entries:
            info = zipfile.ZipInfo(name, date_time=timestamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)


class FossReproducibilityCheckTest(unittest.TestCase):
    def test_signature_entries_are_ignored(self) -> None:
        self.assertTrue(is_signature_entry("META-INF/MANIFEST.MF"))
        self.assertTrue(is_signature_entry("META-INF/AURA.RSA"))
        self.assertFalse(is_signature_entry("META-INF/services/example.Service"))

        with tempfile.TemporaryDirectory() as tmpdir:
            first = Path(tmpdir) / "first.apk"
            second = Path(tmpdir) / "second.apk"
            write_apk(first, signature=b"one")
            write_apk(second, signature=b"two")

            result = compare_apks(first, second)

        self.assertEqual("reproducible", result["status"])
        self.assertFalse(result["rawApkDigestsMatch"])
        self.assertTrue(result["comparableDigestsMatch"])

    def test_payload_difference_fails(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            first = Path(tmpdir) / "first.apk"
            second = Path(tmpdir) / "second.apk"
            write_apk(first, payload=b"one")
            write_apk(second, payload=b"two")

            result = compare_apks(first, second)

        self.assertEqual("mismatch", result["status"])
        self.assertIn("payloadSha256", result["differences"][0]["fields"])

    def test_archive_order_and_timestamp_are_part_of_digest(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            first = Path(tmpdir) / "first.apk"
            reordered = Path(tmpdir) / "reordered.apk"
            retimed = Path(tmpdir) / "retimed.apk"
            write_apk(first)
            write_apk(reordered, reverse_order=True)
            write_apk(retimed, timestamp=(2026, 7, 16, 12, 2, 0))

            order_result = compare_apks(first, reordered)
            time_result = compare_apks(first, retimed)

        self.assertEqual("mismatch", order_result["status"])
        self.assertEqual("mismatch", time_result["status"])
        self.assertIn("dateTime", time_result["differences"][0]["fields"])


if __name__ == "__main__":
    unittest.main()
