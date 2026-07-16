#!/usr/bin/env python3
"""Build and compare Aura FOSS release APKs modulo Android signatures."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any


SIGNATURE_SUFFIXES = (".DSA", ".EC", ".RSA", ".SF")


class FossReproducibilityError(ValueError):
    """Raised when the reproducibility inputs or builds are invalid."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_signature_entry(name: str) -> bool:
    normalized = name.replace("\\", "/").upper()
    if not normalized.startswith("META-INF/"):
        return False
    leaf = normalized.rsplit("/", 1)[-1]
    return leaf == "MANIFEST.MF" or leaf.endswith(SIGNATURE_SUFFIXES)


def _compressed_entry_sha256(apk: Path, info: zipfile.ZipInfo) -> str:
    with apk.open("rb") as handle:
        handle.seek(info.header_offset)
        header = handle.read(30)
        if len(header) != 30:
            raise FossReproducibilityError(f"truncated local ZIP header for {info.filename}")
        signature, *_, name_length, extra_length = struct.unpack("<IHHHHHIIIHH", header)
        if signature != 0x04034B50:
            raise FossReproducibilityError(f"invalid local ZIP header for {info.filename}")
        handle.seek(name_length + extra_length, os.SEEK_CUR)
        compressed = handle.read(info.compress_size)
        if len(compressed) != info.compress_size:
            raise FossReproducibilityError(f"truncated compressed payload for {info.filename}")
    return hashlib.sha256(compressed).hexdigest()


def apk_archive_fingerprint(apk: Path) -> dict[str, Any]:
    if not apk.is_file():
        raise FossReproducibilityError(f"APK is missing: {apk}")
    entries: list[dict[str, Any]] = []
    ignored_signatures: list[str] = []
    try:
        with zipfile.ZipFile(apk) as archive:
            for info in archive.infolist():
                if is_signature_entry(info.filename):
                    ignored_signatures.append(info.filename)
                    continue
                payload = archive.read(info)
                entries.append(
                    {
                        "name": info.filename,
                        "dateTime": list(info.date_time),
                        "compression": info.compress_type,
                        "compressedSize": info.compress_size,
                        "fileSize": info.file_size,
                        "crc32": f"{info.CRC:08x}",
                        "flagBits": info.flag_bits,
                        "createSystem": info.create_system,
                        "createVersion": info.create_version,
                        "extractVersion": info.extract_version,
                        "internalAttr": info.internal_attr,
                        "externalAttr": info.external_attr,
                        "extraSha256": hashlib.sha256(info.extra).hexdigest(),
                        "commentSha256": hashlib.sha256(info.comment).hexdigest(),
                        "compressedSha256": _compressed_entry_sha256(apk, info),
                        "payloadSha256": hashlib.sha256(payload).hexdigest(),
                    }
                )
    except (OSError, zipfile.BadZipFile, RuntimeError) as exc:
        raise FossReproducibilityError(f"cannot inspect APK {apk}: {exc}") from exc

    canonical = json.dumps(entries, separators=(",", ":"), sort_keys=True).encode("utf-8")
    return {
        "path": str(apk.resolve()),
        "rawSha256": sha256_file(apk),
        "comparableSha256": hashlib.sha256(canonical).hexdigest(),
        "entryCount": len(entries),
        "ignoredSignatureEntries": ignored_signatures,
        "entries": entries,
    }


def _entry_differences(first: list[dict[str, Any]], second: list[dict[str, Any]]) -> list[dict[str, Any]]:
    differences: list[dict[str, Any]] = []
    for index in range(max(len(first), len(second))):
        left = first[index] if index < len(first) else None
        right = second[index] if index < len(second) else None
        if left == right:
            continue
        fields = [] if left is None or right is None else sorted(
            key for key in set(left) | set(right) if left.get(key) != right.get(key)
        )
        differences.append(
            {
                "index": index,
                "firstName": left.get("name") if left else None,
                "secondName": right.get("name") if right else None,
                "fields": fields,
            }
        )
        if len(differences) >= 50:
            break
    return differences


def compare_apks(first_apk: Path, second_apk: Path) -> dict[str, Any]:
    first = apk_archive_fingerprint(first_apk)
    second = apk_archive_fingerprint(second_apk)
    matches = first["comparableSha256"] == second["comparableSha256"]
    differences = [] if matches else _entry_differences(first["entries"], second["entries"])
    for fingerprint in (first, second):
        fingerprint.pop("entries")
    return {
        "status": "reproducible" if matches else "mismatch",
        "comparison": "APK archive bytes and metadata excluding Android signature entries and signing block",
        "first": first,
        "second": second,
        "rawApkDigestsMatch": first["rawSha256"] == second["rawSha256"],
        "comparableDigestsMatch": matches,
        "differences": differences,
    }


def _git_output(repo_root: Path, *args: str) -> bytes:
    process = subprocess.run(
        ["git", "-C", str(repo_root), *args],
        check=False,
        capture_output=True,
    )
    if process.returncode != 0:
        detail = process.stderr.decode("utf-8", errors="replace").strip()
        raise FossReproducibilityError(f"git {' '.join(args)} failed: {detail}")
    return process.stdout


def _copy_tracked_tree(repo_root: Path, destination: Path) -> None:
    paths = _git_output(repo_root, "ls-files", "-z").decode("utf-8").split("\0")
    for relative in filter(None, paths):
        posix_path = PurePosixPath(relative)
        if posix_path.is_absolute() or ".." in posix_path.parts:
            raise FossReproducibilityError(f"unsafe tracked path: {relative}")
        source = repo_root.joinpath(*posix_path.parts)
        target = destination.joinpath(*posix_path.parts)
        if not source.is_file():
            raise FossReproducibilityError(f"tracked source file is missing: {relative}")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def _copy_sdk_location(repo_root: Path, destination: Path) -> None:
    local_properties = repo_root / "local.properties"
    if not local_properties.is_file():
        return
    sdk_lines = [
        line for line in local_properties.read_text(encoding="utf-8").splitlines()
        if line.strip().startswith(("sdk.dir=", "ndk.dir="))
    ]
    if sdk_lines:
        (destination / "local.properties").write_text("\n".join(sdk_lines) + "\n", encoding="utf-8")


def _find_foss_release_apk(build_root: Path) -> Path:
    candidates = sorted((build_root / "app/build/outputs/apk/foss/release").glob("*.apk"))
    if len(candidates) != 1:
        raise FossReproducibilityError(
            f"expected one FOSS release APK, found {len(candidates)} under {build_root}"
        )
    return candidates[0]


def _run_isolated_build(
    repo_root: Path,
    build_root: Path,
    output_apk: Path,
    source_date_epoch: str,
    offline: bool,
) -> None:
    _copy_tracked_tree(repo_root, build_root)
    _copy_sdk_location(repo_root, build_root)
    wrapper = build_root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    command = [
        str(wrapper),
        ":app:clean",
        ":app:assembleFossRelease",
        "-Paura.reproducibleFossBuild=true",
        "--no-daemon",
        "--no-build-cache",
        "--max-workers=1",
        "--stacktrace",
    ]
    if offline:
        command.append("--offline")
    environment = os.environ.copy()
    environment.update({"SOURCE_DATE_EPOCH": source_date_epoch, "TZ": "UTC"})
    process = subprocess.run(
        command,
        cwd=build_root,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
        errors="replace",
    )
    if process.returncode != 0:
        tail = "\n".join((process.stdout + "\n" + process.stderr).splitlines()[-120:])
        raise FossReproducibilityError(f"isolated FOSS release build failed:\n{tail}")
    output_apk.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(_find_foss_release_apk(build_root), output_apk)


def build_and_compare(
    repo_root: Path,
    output_dir: Path,
    allow_dirty: bool = False,
    offline: bool = False,
) -> dict[str, Any]:
    repo_root = repo_root.resolve()
    if not allow_dirty:
        status = _git_output(repo_root, "status", "--porcelain", "--untracked-files=all")
        if status.strip():
            raise FossReproducibilityError("tracked source tree must be clean; commit or stash changes first")
    source_date_epoch = _git_output(repo_root, "log", "-1", "--format=%ct").decode("ascii").strip()
    if not source_date_epoch.isdigit():
        raise FossReproducibilityError("could not resolve SOURCE_DATE_EPOCH from the current commit")

    output_dir = output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    first_apk = output_dir / "foss-release-build-1.apk"
    second_apk = output_dir / "foss-release-build-2.apk"
    with tempfile.TemporaryDirectory(prefix="aura-foss-rb-") as temp:
        temp_root = Path(temp)
        _run_isolated_build(repo_root, temp_root / "source-1", first_apk, source_date_epoch, offline)
        _run_isolated_build(repo_root, temp_root / "source-2", second_apk, source_date_epoch, offline)

    result = compare_apks(first_apk, second_apk)
    result.update(
        {
            "sourceDateEpoch": source_date_epoch,
            "isolatedBuildRoots": 2,
            "serializedWorkers": 1,
            "signingDisabled": True,
        }
    )
    return result


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--first-apk")
    parser.add_argument("--second-apk")
    parser.add_argument("--build-twice", action="store_true")
    parser.add_argument("--output-dir", default="build/reproducibility")
    parser.add_argument("--allow-dirty", action="store_true")
    parser.add_argument("--offline", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        if args.build_twice:
            if args.first_apk or args.second_apk:
                raise FossReproducibilityError("--build-twice cannot be combined with APK paths")
            result = build_and_compare(
                repo_root=Path(args.repo_root),
                output_dir=Path(args.output_dir),
                allow_dirty=args.allow_dirty,
                offline=args.offline,
            )
        else:
            if not args.first_apk or not args.second_apk:
                raise FossReproducibilityError(
                    "provide --build-twice or both --first-apk and --second-apk"
                )
            result = compare_apks(Path(args.first_apk), Path(args.second_apk))
    except (FossReproducibilityError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["status"] == "reproducible" else 1


if __name__ == "__main__":
    raise SystemExit(main())
