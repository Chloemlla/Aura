import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools import native_alignment_check


def minimal_elf64(load_alignments):
    header = bytearray(64)
    header[:4] = b"\x7fELF"
    header[4] = 2
    header[5] = 1
    phoff = 64
    phentsize = 56
    phnum = len(load_alignments)
    struct.pack_into("<Q", header, 32, phoff)
    struct.pack_into("<H", header, 54, phentsize)
    struct.pack_into("<H", header, 56, phnum)
    program_headers = bytearray(phentsize * phnum)
    for index, alignment in enumerate(load_alignments):
        base = index * phentsize
        struct.pack_into("<I", program_headers, base, native_alignment_check.PT_LOAD)
        struct.pack_into("<Q", program_headers, base + 8, index * 4096)
        struct.pack_into("<Q", program_headers, base + 16, index * 4096)
        struct.pack_into("<Q", program_headers, base + 48, alignment)
    return bytes(header + program_headers)


def minimal_elf32(load_alignments):
    header = bytearray(52)
    header[:4] = b"\x7fELF"
    header[4] = 1
    header[5] = 1
    phoff = 52
    phentsize = 32
    phnum = len(load_alignments)
    struct.pack_into("<I", header, 28, phoff)
    struct.pack_into("<H", header, 42, phentsize)
    struct.pack_into("<H", header, 44, phnum)
    program_headers = bytearray(phentsize * phnum)
    for index, alignment in enumerate(load_alignments):
        base = index * phentsize
        struct.pack_into("<I", program_headers, base, native_alignment_check.PT_LOAD)
        struct.pack_into("<I", program_headers, base + 4, index * 4096)
        struct.pack_into("<I", program_headers, base + 8, index * 4096)
        struct.pack_into("<I", program_headers, base + 28, alignment)
    return bytes(header + program_headers)


def write_apk(entries):
    temp_dir = tempfile.TemporaryDirectory()
    apk_path = Path(temp_dir.name) / "release.apk"
    with zipfile.ZipFile(apk_path, "w") as archive:
        for name, data in entries.items():
            archive.writestr(name, data)
    return temp_dir, apk_path


class NativeAlignmentCheckTest(unittest.TestCase):
    def test_accepts_16kb_aligned_64_bit_load_segments(self):
        temp_dir, apk_path = write_apk(
            {
                "lib/arm64-v8a/libok.so": minimal_elf64([16384, 65536]),
                "lib/armeabi-v7a/liblegacy.so": minimal_elf32([4096]),
            }
        )
        self.addCleanup(temp_dir.cleanup)

        libraries = native_alignment_check.inspect_apk(apk_path)
        result = native_alignment_check.validate_libraries(
            libraries,
            required_alignment=16384,
            required_abis={"arm64-v8a"},
            expected_abis={"arm64-v8a", "armeabi-v7a"},
            require_64_bit_only=False,
        )

        self.assertEqual(result["checked64BitLoadSegments"], 2)
        self.assertEqual(result["seen64BitAbis"], ["arm64-v8a"])
        self.assertEqual(result["seenAbis"], ["arm64-v8a", "armeabi-v7a"])
        self.assertEqual(result["apkVariant"], "universal")

    def test_rejects_4kb_aligned_64_bit_load_segment(self):
        temp_dir, apk_path = write_apk(
            {
                "lib/arm64-v8a/libbad.so": minimal_elf64([4096]),
            }
        )
        self.addCleanup(temp_dir.cleanup)

        libraries = native_alignment_check.inspect_apk(apk_path)
        with self.assertRaisesRegex(native_alignment_check.NativeAlignmentError, "p_align 4096"):
            native_alignment_check.validate_libraries(
                libraries,
                required_alignment=16384,
                required_abis={"arm64-v8a"},
                expected_abis={"arm64-v8a"},
                require_64_bit_only=True,
            )

    def test_rejects_missing_required_64_bit_abi(self):
        temp_dir, apk_path = write_apk(
            {
                "lib/armeabi-v7a/liblegacy.so": minimal_elf32([4096]),
            }
        )
        self.addCleanup(temp_dir.cleanup)

        libraries = native_alignment_check.inspect_apk(apk_path)
        with self.assertRaisesRegex(native_alignment_check.NativeAlignmentError, "missing required 64-bit ABIs"):
            native_alignment_check.validate_libraries(
                libraries,
                required_alignment=16384,
                required_abis={"arm64-v8a"},
                expected_abis={"arm64-v8a", "armeabi-v7a"},
                require_64_bit_only=False,
            )

    def test_rejects_an_abi_the_artifact_should_not_carry(self):
        temp_dir, apk_path = write_apk(
            {
                "lib/arm64-v8a/libok.so": minimal_elf64([16384]),
                "lib/riscv64/libsurprise.so": minimal_elf64([16384]),
            }
        )
        self.addCleanup(temp_dir.cleanup)

        libraries = native_alignment_check.inspect_apk(apk_path)
        with self.assertRaisesRegex(native_alignment_check.NativeAlignmentError, "unexpected ABIs"):
            native_alignment_check.validate_libraries(
                libraries,
                required_alignment=16384,
                required_abis={"arm64-v8a"},
                expected_abis={"arm64-v8a"},
                require_64_bit_only=True,
            )

    def test_rejects_a_universal_apk_that_lost_abis(self):
        """Packaging dropped two ABIs. Content alone cannot tell this from a split."""
        temp_dir, apk_path = write_apk(
            {
                "lib/arm64-v8a/libok.so": minimal_elf64([16384]),
                "lib/x86_64/libok.so": minimal_elf64([16384]),
            }
        )
        self.addCleanup(temp_dir.cleanup)

        libraries = native_alignment_check.inspect_apk(apk_path)
        with self.assertRaisesRegex(native_alignment_check.NativeAlignmentError, "missing expected ABIs"):
            native_alignment_check.validate_libraries(
                libraries,
                required_alignment=16384,
                required_abis={"arm64-v8a", "x86_64"},
                expected_abis={"arm64-v8a", "armeabi-v7a", "x86", "x86_64"},
                require_64_bit_only=False,
            )

    def test_a_split_is_accepted_without_the_full_64_bit_set(self):
        temp_dir, apk_path = write_apk(
            {
                "lib/arm64-v8a/libok.so": minimal_elf64([16384]),
            }
        )
        self.addCleanup(temp_dir.cleanup)

        libraries = native_alignment_check.inspect_apk(apk_path)
        result = native_alignment_check.validate_libraries(
            libraries,
            required_alignment=16384,
            required_abis={"arm64-v8a", "x86_64"},
            expected_abis={"arm64-v8a"},
            require_64_bit_only=False,
            variant="split:arm64-v8a",
        )

        self.assertEqual(result["apkVariant"], "split:arm64-v8a")

    def test_a_32_bit_split_carries_no_16kb_obligation(self):
        temp_dir, apk_path = write_apk(
            {
                "lib/armeabi-v7a/liblegacy.so": minimal_elf32([4096]),
            }
        )
        self.addCleanup(temp_dir.cleanup)

        libraries = native_alignment_check.inspect_apk(apk_path)
        result = native_alignment_check.validate_libraries(
            libraries,
            required_alignment=16384,
            required_abis={"arm64-v8a", "x86_64"},
            expected_abis={"armeabi-v7a"},
            require_64_bit_only=False,
            variant="split:armeabi-v7a",
        )

        self.assertEqual(result["apkVariant"], "split:armeabi-v7a")
        self.assertEqual(result["checked64BitLoadSegments"], 0)

    def test_a_split_carrying_the_wrong_abi_fails(self):
        """The name says arm64; the payload says otherwise."""
        temp_dir, apk_path = write_apk(
            {
                "lib/x86_64/libok.so": minimal_elf64([16384]),
            }
        )
        self.addCleanup(temp_dir.cleanup)

        libraries = native_alignment_check.inspect_apk(apk_path)
        with self.assertRaisesRegex(native_alignment_check.NativeAlignmentError, "unexpected ABIs"):
            native_alignment_check.validate_libraries(
                libraries,
                required_alignment=16384,
                required_abis={"arm64-v8a", "x86_64"},
                expected_abis={"arm64-v8a"},
                require_64_bit_only=False,
                variant="split:arm64-v8a",
            )


class ExpectedAbisForApkTest(unittest.TestCase):
    """The artifact's name is the declaration; its contents are the claim under test."""

    DECLARED = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}

    def test_a_split_name_expects_exactly_that_abi(self):
        variant, expected = native_alignment_check.expected_abis_for_apk(
            "app-full-arm64-v8a-release.apk", self.DECLARED
        )

        self.assertEqual("split:arm64-v8a", variant)
        self.assertEqual({"arm64-v8a"}, expected)

    def test_x86_does_not_swallow_x86_64(self):
        variant, expected = native_alignment_check.expected_abis_for_apk(
            "app-full-x86_64-release.apk", self.DECLARED
        )

        self.assertEqual("split:x86_64", variant)
        self.assertEqual({"x86_64"}, expected)

    def test_a_universal_name_expects_every_declared_abi(self):
        variant, expected = native_alignment_check.expected_abis_for_apk(
            "app-full-universal-release.apk", self.DECLARED
        )

        self.assertEqual("universal", variant)
        self.assertEqual(self.DECLARED, expected)

    def test_an_unsplit_build_is_treated_as_universal(self):
        variant, expected = native_alignment_check.expected_abis_for_apk(
            "app-full-release.apk", self.DECLARED
        )

        self.assertEqual("universal", variant)
        self.assertEqual(self.DECLARED, expected)

    def test_a_64_bit_only_policy_rejects_a_32_bit_library(self):
        """The old gate skipped these outright, so the claim could never be false."""
        temp_dir, apk_path = write_apk(
            {
                "lib/arm64-v8a/libok.so": minimal_elf64([16384]),
                "lib/armeabi-v7a/liblegacy.so": minimal_elf32([4096]),
            }
        )
        self.addCleanup(temp_dir.cleanup)

        libraries = native_alignment_check.inspect_apk(apk_path)
        with self.assertRaisesRegex(native_alignment_check.NativeAlignmentError, "64-bit only"):
            native_alignment_check.validate_libraries(
                libraries,
                required_alignment=16384,
                required_abis={"arm64-v8a"},
                expected_abis={"arm64-v8a", "armeabi-v7a"},
                require_64_bit_only=True,
            )

    def test_skips_zip_payloads_named_so(self):
        temp_dir, apk_path = write_apk(
            {
                "lib/arm64-v8a/libarchive.zip.so": b"PK\x03\x04archive",
                "lib/arm64-v8a/libok.so": minimal_elf64([16384]),
            }
        )
        self.addCleanup(temp_dir.cleanup)

        skipped = []
        libraries = native_alignment_check.inspect_apk(apk_path, skipped_archive_entries=skipped)

        self.assertEqual(skipped, ["lib/arm64-v8a/libarchive.zip.so"])
        self.assertEqual([library.apk_entry for library in libraries], ["lib/arm64-v8a/libok.so"])


if __name__ == "__main__":
    unittest.main()
