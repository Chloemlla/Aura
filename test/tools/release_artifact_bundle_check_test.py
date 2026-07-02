from __future__ import annotations

import hashlib
import tempfile
import unittest
from pathlib import Path

from tools.release_artifact_bundle_check import validate_bundle


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
            '{"status":"ok","policyKind":"nativePageAlignment","packageName":"com.freevibe",'
            '"requiredLoadSegmentAlignmentBytes":16384,"checked64BitLoadSegments":2,'
            '"seen64BitAbis":["arm64-v8a","x86_64"]}'
        ),
        "apksigner.txt": "Signer #1 certificate SHA-256 digest: apk-cert",
        "aapt-badging.txt": "package: name='com.freevibe'",
        "aab-manifest.txt": (
            'manifest package="com.freevibe" android:versionCode="133" '
            'android:versionName="6.34.6"'
        ),
        "bundletool-validate.txt": f"bundletool validate passed: {aab_name}",
        "aab-jarsigner.txt": "jar verified.",
        "aab-keytool.txt": "Certificate fingerprints:\n\t SHA256: upload-cert",
        "PLAY-APP-SIGNING-OWNER-STEPS.txt": (
            "Play App Signing owner-confirmation-required for com.freevibe. "
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
- Package: com.freevibe
""",
    )
    return apk_name, aab_name


class ReleaseArtifactBundleCheckTest(unittest.TestCase):
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
