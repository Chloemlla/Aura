from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from tools.release_artifact_bundle_check import validate_bundle, validate_split_apk_names


def write_text(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_release_fixture(release_dir: Path) -> tuple[str, str]:
    version_name = "6.34.6"
    version_code = "133"
    apk_name = f"Aura-v{version_name}-versionCode-{version_code}-universal-release.apk"
    aab_name = f"Aura-v{version_name}-versionCode-{version_code}-play-release.aab"
    files = {
        apk_name: "apk",
        aab_name: "aab",
        "THIRD-PARTY-NOTICES.md": "third-party",
        "GOOGLE-OSS-RAW-INPUTS.zip": "raw",
        "NATIVE-COMPLIANCE.md": "native",
        "NATIVE-ALIGNMENT.json": (
            '{"status":"ok","policyKind":"nativePageAlignment","packageName":"com.chloemlla.aura",'
            '"requiredLoadSegmentAlignmentBytes":16384,"checked64BitLoadSegments":2,'
            '"seen64BitAbis":["arm64-v8a","x86_64"]}'
        ),
        "apksigner.txt": "Signer #1 certificate SHA-256 digest: apk-cert",
        "aapt-badging.txt": "package: name='com.chloemlla.aura'",
        "aab-manifest.txt": (
            'manifest package="com.chloemlla.aura" android:versionCode="133" '
            'android:versionName="6.34.6"'
        ),
        "bundletool-validate.txt": f"bundletool validate passed: {aab_name}",
        "aab-jarsigner.txt": "jar verified.",
        "aab-keytool.txt": "Certificate fingerprints:\n\t SHA256: upload-cert",
        "PLAY-APP-SIGNING-OWNER-STEPS.txt": (
            "Play App Signing owner-confirmation-required for com.chloemlla.aura. "
            "Open Play Console App integrity, compare upload key and app signing key."
        ),
    }
    for name, text in files.items():
        write_text(release_dir / name, text)

    checksum_names = [
        apk_name,
        aab_name,
        "THIRD-PARTY-NOTICES.md",
        "GOOGLE-OSS-RAW-INPUTS.zip",
        "NATIVE-COMPLIANCE.md",
        "NATIVE-ALIGNMENT.json",
    ]
    write_text(
        release_dir / "SHA256SUMS.txt",
        "\n".join(f"{sha256_file(release_dir / name)}  {name}" for name in checksum_names),
    )
    write_text(
        release_dir / "RELEASE_NOTES.md",
        f"""Aura {version_name} (versionCode {version_code})

Signed release artifacts:
- APK: {apk_name}
- APK SHA-256: {sha256_file(release_dir / apk_name)}
- AAB: {aab_name}
- AAB SHA-256: {sha256_file(release_dir / aab_name)}
- THIRD-PARTY-NOTICES.md
- GOOGLE-OSS-RAW-INPUTS.zip
- NATIVE-COMPLIANCE.md
- NATIVE-ALIGNMENT.json
- Signing certificate SHA-256: apk-cert
- Upload key certificate SHA-256: upload-cert
- Play App Signing owner steps: owner-confirmation-required in PLAY-APP-SIGNING-OWNER-STEPS.txt
- Local build receipt: smoke-test
- Build type: release, android:debuggable=false

Android developer verification:
- Package: com.chloemlla.aura
""",
    )
    return apk_name, aab_name


def add_split_apks(release_dir: Path, version_name: str, version_code: str, abis: list[str]) -> list[str]:
    """Adds per-ABI APKs to a fixture bundle and folds them into sums and notes."""
    names = [f"Aura-v{version_name}-versionCode-{version_code}-{abi}-release.apk" for abi in abis]
    for index, name in enumerate(names):
        write_text(release_dir / name, f"apk-{index}")

    checksums = (release_dir / "SHA256SUMS.txt").read_text(encoding="utf-8").rstrip("\n")
    extra = "\n".join(f"{sha256_file(release_dir / name)}  {name}" for name in names)
    write_text(release_dir / "SHA256SUMS.txt", f"{checksums}\n{extra}\n")

    notes = (release_dir / "RELEASE_NOTES.md").read_text(encoding="utf-8")
    listed = "\n".join(f"- Split APK: {name}" for name in names)
    write_text(release_dir / "RELEASE_NOTES.md", f"{notes}\n{listed}\n")
    return names


class SplitApkNameTest(unittest.TestCase):
    def test_accepts_one_apk_per_declared_abi(self) -> None:
        names = [
            "Aura-v6.34.6-versionCode-133-arm64-v8a-release.apk",
            "Aura-v6.34.6-versionCode-133-x86_64-release.apk",
        ]

        self.assertEqual([], validate_split_apk_names(names, version_name="6.34.6", version_code="133"))

    def test_rejects_a_stale_version_in_a_split_name(self) -> None:
        names = ["Aura-v6.34.5-versionCode-133-arm64-v8a-release.apk"]

        errors = validate_split_apk_names(names, version_name="6.34.6", version_code="133")

        self.assertTrue(any("does not match versionName" in error for error in errors))

    def test_rejects_an_unknown_architecture(self) -> None:
        names = ["Aura-v6.34.6-versionCode-133-mips-release.apk"]

        errors = validate_split_apk_names(names, version_name="6.34.6", version_code="133")

        self.assertTrue(any("does not name a known ABI" in error for error in errors))

    def test_rejects_the_same_abi_twice(self) -> None:
        """Two artifacts claiming one architecture; a device gets whichever is served."""
        names = [
            "Aura-v6.34.6-versionCode-133-arm64-v8a-release.apk",
            "Aura-v6.34.6-versionCode-133-arm64-v8a-release.apk",
        ]

        errors = validate_split_apk_names(names, version_name="6.34.6", version_code="133")

        self.assertTrue(any("more than once" in error for error in errors))


class ReleaseArtifactBundleCheckTest(unittest.TestCase):
    def test_accepts_a_bundle_carrying_every_split(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            release_dir = Path(tmpdir)
            apk_name, aab_name = write_release_fixture(release_dir)
            splits = add_split_apks(
                release_dir, "6.34.6", "133", ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
            )

            errors = validate_bundle(
                release_dir=release_dir,
                apk_name=apk_name,
                aab_name=aab_name,
                version_name="6.34.6",
                version_code="133",
                split_apk_names=splits,
            )

            self.assertEqual([], errors)

    def test_rejects_a_split_that_is_missing_from_the_checksums(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            release_dir = Path(tmpdir)
            apk_name, aab_name = write_release_fixture(release_dir)
            splits = add_split_apks(release_dir, "6.34.6", "133", ["arm64-v8a"])
            checksums = (release_dir / "SHA256SUMS.txt").read_text(encoding="utf-8")
            write_text(
                release_dir / "SHA256SUMS.txt",
                "\n".join(line for line in checksums.splitlines() if splits[0] not in line),
            )

            errors = validate_bundle(
                release_dir=release_dir,
                apk_name=apk_name,
                aab_name=aab_name,
                version_name="6.34.6",
                version_code="133",
                split_apk_names=splits,
            )

            self.assertTrue(any("missing entries" in error and splits[0] in error for error in errors))

    def test_rejects_a_split_the_release_notes_never_mention(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            release_dir = Path(tmpdir)
            apk_name, aab_name = write_release_fixture(release_dir)
            splits = add_split_apks(release_dir, "6.34.6", "133", ["arm64-v8a"])
            notes = (release_dir / "RELEASE_NOTES.md").read_text(encoding="utf-8")
            write_text(
                release_dir / "RELEASE_NOTES.md",
                "\n".join(line for line in notes.splitlines() if splits[0] not in line),
            )

            errors = validate_bundle(
                release_dir=release_dir,
                apk_name=apk_name,
                aab_name=aab_name,
                version_name="6.34.6",
                version_code="133",
                split_apk_names=splits,
            )

            self.assertTrue(any("missing fragment" in error and splits[0] in error for error in errors))

    def test_rejects_a_split_that_was_never_built(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            release_dir = Path(tmpdir)
            apk_name, aab_name = write_release_fixture(release_dir)

            errors = validate_bundle(
                release_dir=release_dir,
                apk_name=apk_name,
                aab_name=aab_name,
                version_name="6.34.6",
                version_code="133",
                split_apk_names=["Aura-v6.34.6-versionCode-133-arm64-v8a-release.apk"],
            )

            self.assertTrue(any("Required file not found" in error for error in errors))

    def test_accepts_complete_apk_and_aab_release_bundle(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            release_dir = Path(tmpdir)
            apk_name, aab_name = write_release_fixture(release_dir)

            errors = validate_bundle(
                release_dir=release_dir,
                apk_name=apk_name,
                aab_name=aab_name,
                version_name="6.34.6",
                version_code="133",
            )

            self.assertEqual([], errors)

    def test_rejects_missing_aab_checksum(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            release_dir = Path(tmpdir)
            apk_name, aab_name = write_release_fixture(release_dir)
            checksums = (release_dir / "SHA256SUMS.txt").read_text(encoding="utf-8")
            (release_dir / "SHA256SUMS.txt").write_text(
                "\n".join(line for line in checksums.splitlines() if aab_name not in line),
                encoding="utf-8",
            )

            errors = validate_bundle(
                release_dir=release_dir,
                apk_name=apk_name,
                aab_name=aab_name,
                version_name="6.34.6",
                version_code="133",
            )

            self.assertTrue(any("missing entries" in error and aab_name in error for error in errors))

    def test_rejects_stale_aab_name(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            release_dir = Path(tmpdir)
            apk_name, aab_name = write_release_fixture(release_dir)

            errors = validate_bundle(
                release_dir=release_dir,
                apk_name=apk_name,
                aab_name=aab_name.replace("versionCode-133", "versionCode-132"),
                version_name="6.34.6",
                version_code="133",
            )

            self.assertTrue(any("AAB name" in error for error in errors))

    def test_rejects_missing_play_app_signing_owner_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            release_dir = Path(tmpdir)
            apk_name, aab_name = write_release_fixture(release_dir)
            (release_dir / "PLAY-APP-SIGNING-OWNER-STEPS.txt").unlink()

            errors = validate_bundle(
                release_dir=release_dir,
                apk_name=apk_name,
                aab_name=aab_name,
                version_name="6.34.6",
                version_code="133",
            )

            self.assertTrue(any("PLAY-APP-SIGNING-OWNER-STEPS.txt" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
