#!/usr/bin/env python3
"""Validate the final release artifact bundle before upload or publication."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


RAW_GOOGLE_OSS_INPUT_ARCHIVE = "GOOGLE-OSS-RAW-INPUTS.zip"

REQUIRED_STATIC_FILES = {
    "THIRD-PARTY-NOTICES.md",
    RAW_GOOGLE_OSS_INPUT_ARCHIVE,
    "NATIVE-COMPLIANCE.md",
    "NATIVE-ALIGNMENT.json",
    "SHA256SUMS.txt",
    "RELEASE_NOTES.md",
    "apksigner.txt",
    "aapt-badging.txt",
    "aab-manifest.txt",
    "bundletool-validate.txt",
    "aab-jarsigner.txt",
    "aab-keytool.txt",
    "PLAY-APP-SIGNING-OWNER-STEPS.txt",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate an Aura release artifact directory."
    )
    parser.add_argument("--release-dir", default="release")
    parser.add_argument("--apk-name", required=True)
    parser.add_argument("--aab-name", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True)
    return parser.parse_args()


def read_text(path: Path) -> str:
    if not path.is_file():
        raise FileNotFoundError(f"Required file not found: {path}")
    data = path.read_bytes()
    for encoding in ("utf-8-sig", "utf-16"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def parse_sha256sums(path: Path) -> dict[str, str]:
    entries: dict[str, str] = {}
    for line_number, raw_line in enumerate(read_text(path).splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        match = re.fullmatch(r"([0-9a-fA-F]{64})\s+\*?(.+)", line)
        if not match:
            raise ValueError(f"{path}:{line_number}: invalid SHA256SUMS entry")
        digest, file_name = match.groups()
        if file_name in entries:
            raise ValueError(f"{path}:{line_number}: duplicate checksum for {file_name}")
        entries[file_name] = digest.lower()
    return entries


def require_non_empty(path: Path) -> None:
    if not path.is_file():
        raise FileNotFoundError(f"Required file not found: {path}")
    if path.stat().st_size <= 0:
        raise ValueError(f"Required file is empty: {path}")


def validate_native_alignment(path: Path, *, package_name: str) -> list[str]:
    errors: list[str] = []
    try:
        payload = json.loads(read_text(path))
    except (FileNotFoundError, json.JSONDecodeError) as exc:
        return [str(exc)]
    if payload.get("status") != "ok":
        errors.append("NATIVE-ALIGNMENT.json status must be ok")
    if payload.get("policyKind") != "nativePageAlignment":
        errors.append("NATIVE-ALIGNMENT.json policyKind must be nativePageAlignment")
    if payload.get("packageName") != package_name:
        errors.append(f"NATIVE-ALIGNMENT.json packageName must be {package_name}")
    if payload.get("requiredLoadSegmentAlignmentBytes") != 16384:
        errors.append("NATIVE-ALIGNMENT.json requiredLoadSegmentAlignmentBytes must be 16384")
    checked_segments = payload.get("checked64BitLoadSegments")
    if not isinstance(checked_segments, int) or checked_segments <= 0:
        errors.append("NATIVE-ALIGNMENT.json must include checked64BitLoadSegments > 0")
    seen_abis = payload.get("seen64BitAbis")
    if not isinstance(seen_abis, list) or not {"arm64-v8a", "x86_64"} <= set(seen_abis):
        errors.append("NATIVE-ALIGNMENT.json must include arm64-v8a and x86_64")
    return errors


def validate_aab_manifest(path: Path, *, package_name: str, version_name: str, version_code: str) -> list[str]:
    errors: list[str] = []
    try:
        manifest = read_text(path)
    except FileNotFoundError as exc:
        return [str(exc)]
    required_fragments = {
        package_name: "AAB manifest package name",
        "versionCode": "AAB manifest versionCode field",
        version_code: "AAB manifest versionCode value",
        "versionName": "AAB manifest versionName field",
        version_name: "AAB manifest versionName value",
    }
    for fragment, label in required_fragments.items():
        if fragment not in manifest:
            errors.append(f"aab-manifest.txt missing {label}: {fragment}")
    return errors


def validate_bundletool_receipt(path: Path, *, aab_name: str) -> list[str]:
    try:
        receipt = read_text(path)
    except FileNotFoundError as exc:
        return [str(exc)]
    errors: list[str] = []
    if aab_name not in receipt:
        errors.append("bundletool-validate.txt must name the checked AAB")
    if "bundletool validate passed" not in receipt.lower():
        errors.append("bundletool-validate.txt must record bundletool validate passed")
    return errors


def validate_aab_signature_receipts(
    *,
    jarsigner_path: Path,
    keytool_path: Path,
    owner_steps_path: Path,
    package_name: str,
) -> list[str]:
    errors: list[str] = []
    try:
        jarsigner = read_text(jarsigner_path).lower()
        if "jar verified" not in jarsigner:
            errors.append("aab-jarsigner.txt must record jar verified")
        if "jar is unsigned" in jarsigner:
            errors.append("aab-jarsigner.txt must not contain unsigned output")
    except FileNotFoundError as exc:
        errors.append(str(exc))

    try:
        keytool = read_text(keytool_path)
        if "SHA256:" not in keytool and "SHA-256" not in keytool:
            errors.append("aab-keytool.txt missing upload key SHA-256 fingerprint")
    except FileNotFoundError as exc:
        errors.append(str(exc))

    try:
        owner_steps = read_text(owner_steps_path)
        required_owner_fragments = [
            "Play App Signing",
            "App integrity",
            "upload key",
            "app signing key",
            "owner-confirmation-required",
            package_name,
        ]
        for fragment in required_owner_fragments:
            if fragment not in owner_steps:
                errors.append(f"PLAY-APP-SIGNING-OWNER-STEPS.txt missing fragment: {fragment}")
    except FileNotFoundError as exc:
        errors.append(str(exc))
    return errors


def validate_bundle(
    *,
    release_dir: Path,
    apk_name: str,
    aab_name: str,
    version_name: str,
    version_code: str,
) -> list[str]:
    errors: list[str] = []
    expected_files = set(REQUIRED_STATIC_FILES)
    expected_files.add(apk_name)
    expected_files.add(aab_name)

    if not release_dir.is_dir():
        return [f"Release directory not found: {release_dir}"]

    for file_name in sorted(expected_files):
        path = release_dir / file_name
        try:
            require_non_empty(path)
        except (FileNotFoundError, ValueError) as exc:
            errors.append(str(exc))

    apk_path = release_dir / apk_name
    aab_path = release_dir / aab_name
    expected_apk_fragment = f"versionCode-{version_code}-universal-release.apk"
    if not apk_name.startswith(f"Aura-v{version_name}-") or expected_apk_fragment not in apk_name:
        errors.append(
            f"APK name {apk_name} does not match versionName {version_name} "
            f"and versionCode {version_code}"
        )
    expected_aab_fragment = f"versionCode-{version_code}-play-release.aab"
    if not aab_name.startswith(f"Aura-v{version_name}-") or expected_aab_fragment not in aab_name:
        errors.append(
            f"AAB name {aab_name} does not match versionName {version_name} "
            f"and versionCode {version_code}"
        )

    checksum_path = release_dir / "SHA256SUMS.txt"
    try:
        checksums = parse_sha256sums(checksum_path)
        expected_checksum_files = {
            apk_name,
            aab_name,
            "THIRD-PARTY-NOTICES.md",
            RAW_GOOGLE_OSS_INPUT_ARCHIVE,
            "NATIVE-COMPLIANCE.md",
            "NATIVE-ALIGNMENT.json",
        }
        missing = sorted(expected_checksum_files - set(checksums))
        extra = sorted(set(checksums) - expected_checksum_files)
        if missing:
            errors.append("SHA256SUMS.txt missing entries: " + ", ".join(missing))
        if extra:
            errors.append("SHA256SUMS.txt has unexpected entries: " + ", ".join(extra))
        for file_name in sorted(expected_checksum_files & set(checksums)):
            actual = sha256_file(release_dir / file_name)
            if checksums[file_name] != actual:
                errors.append(
                    f"SHA256 mismatch for {file_name}: "
                    f"expected {checksums[file_name]}, got {actual}"
                )
    except (FileNotFoundError, ValueError) as exc:
        errors.append(str(exc))

    notes_path = release_dir / "RELEASE_NOTES.md"
    try:
        release_notes = read_text(notes_path)
        required_note_fragments = [
            f"Aura {version_name} (versionCode {version_code})",
            apk_name,
            aab_name,
            "APK SHA-256:",
            "AAB SHA-256:",
            "THIRD-PARTY-NOTICES.md",
            RAW_GOOGLE_OSS_INPUT_ARCHIVE,
            "NATIVE-COMPLIANCE.md",
            "NATIVE-ALIGNMENT.json",
            "Signing certificate SHA-256:",
            "Upload key certificate SHA-256:",
            "Play App Signing owner steps:",
            "Local build receipt:",
            "Build type: release, android:debuggable=false",
            "Package: com.chloemlla.aura",
        ]
        for fragment in required_note_fragments:
            if fragment not in release_notes:
                errors.append(f"RELEASE_NOTES.md missing fragment: {fragment}")
        valued_note_labels = [
            "APK SHA-256",
            "AAB SHA-256",
            "Signing certificate SHA-256",
            "Upload key certificate SHA-256",
            "Local build receipt",
        ]
        for label in valued_note_labels:
            if not re.search(rf"^- {re.escape(label)}:\s+\S+", release_notes, re.MULTILINE):
                errors.append(f"RELEASE_NOTES.md has blank value for: {label}")
        if apk_path.is_file():
            apk_digest = sha256_file(apk_path)
            if apk_digest not in release_notes:
                errors.append("RELEASE_NOTES.md missing APK SHA-256 value")
        if aab_path.is_file():
            aab_digest = sha256_file(aab_path)
            if aab_digest not in release_notes:
                errors.append("RELEASE_NOTES.md missing AAB SHA-256 value")
    except FileNotFoundError as exc:
        errors.append(str(exc))

    try:
        apksigner = read_text(release_dir / "apksigner.txt")
        if "certificate SHA-256 digest:" not in apksigner:
            errors.append("apksigner.txt missing signer SHA-256 digest")
    except FileNotFoundError as exc:
        errors.append(str(exc))

    try:
        aapt_badging = read_text(release_dir / "aapt-badging.txt")
        if "application-debuggable" in aapt_badging:
            errors.append("aapt-badging.txt marks the APK debuggable")
    except FileNotFoundError as exc:
        errors.append(str(exc))

    errors.extend(validate_native_alignment(release_dir / "NATIVE-ALIGNMENT.json", package_name="com.chloemlla.aura"))
    errors.extend(
        validate_aab_manifest(
            release_dir / "aab-manifest.txt",
            package_name="com.chloemlla.aura",
            version_name=version_name,
            version_code=version_code,
        )
    )
    errors.extend(validate_bundletool_receipt(release_dir / "bundletool-validate.txt", aab_name=aab_name))
    errors.extend(
        validate_aab_signature_receipts(
            jarsigner_path=release_dir / "aab-jarsigner.txt",
            keytool_path=release_dir / "aab-keytool.txt",
            owner_steps_path=release_dir / "PLAY-APP-SIGNING-OWNER-STEPS.txt",
            package_name="com.chloemlla.aura",
        )
    )

    return errors


def main() -> int:
    args = parse_args()
    release_dir = Path(args.release_dir)
    errors = validate_bundle(
        release_dir=release_dir,
        apk_name=args.apk_name,
        aab_name=args.aab_name,
        version_name=args.version_name,
        version_code=args.version_code,
    )
    if errors:
        print("Release artifact bundle validation failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print(
        json.dumps(
            {
                "aab": args.aab_name,
                "apk": args.apk_name,
                "releaseDir": str(release_dir),
                "status": "ok",
                "versionCode": args.version_code,
                "versionName": args.version_name,
            },
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
