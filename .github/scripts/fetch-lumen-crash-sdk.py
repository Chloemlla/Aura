#!/usr/bin/env python3
"""Materialize lumen-crash Maven coordinates from Project-Lumen GitHub Release assets.

Version selection:
  - "latest" (default): newest main auto-release under tag lumen-crash-v*
  - explicit version: e.g. 0.1.0-8e73f18d or 0.1.0
  - env override: LUMEN_CRASH_SDK_VERSION
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


DEFAULT_OWNER = "Chloemlla"
DEFAULT_REPO = "Project-Lumen"
DEFAULT_VERSION_FILE = Path("lumen-crash.version")
DEFAULT_RESOLVED_VERSION_FILE = Path("lumen-crash.resolved.version")
DEFAULT_LOCAL_MAVEN = Path("local-maven")

DEFAULT_VERIFICATION_METADATA = Path("gradle/verification-metadata.xml")

# Gradle dependency verification XML namespace
NS = {"dv": "https://schema.gradle.org/dependency-verification"}

TAG_PREFIX = "lumen-crash-v"
# main auto-release: 0.1.0-8e73f18d
MAIN_AUTO_VERSION_RE = re.compile(r"^\d+\.\d+\.\d+-[0-9a-f]{7,40}$", re.IGNORECASE)
# optional pure semver stable tag
STABLE_VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")


def read_version_policy(path: Path) -> str:
    if not path.is_file():
        return "latest"
    for line in path.read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if text and not text.startswith("#"):
            return text
    return "latest"


def auth_headers(accept: str = "*/*") -> dict[str, str]:
    headers = {
        "Accept": accept,
        "User-Agent": "aura-lumen-crash-bootstrap",
    }
    token = (
        os.environ.get("LUMEN_CRASH_READ_PACKAGES_TOKEN")
        or os.environ.get("GH_TOKEN")
        or os.environ.get("GITHUB_TOKEN")
        or ""
    ).strip()
    if token:
        headers["Authorization"] = f"Bearer {token}"
        headers["X-GitHub-Api-Version"] = "2022-11-28"
    return headers


def http_get(url: str, accept: str = "*/*") -> bytes:
    request = urllib.request.Request(url, headers=auth_headers(accept))
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        raise SystemExit(f"HTTP {error.code} for {url}: {body}") from error


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def version_from_tag(tag_name: str) -> str | None:
    if not tag_name.startswith(TAG_PREFIX):
        return None
    return tag_name[len(TAG_PREFIX) :]


def is_main_auto_version(version: str) -> bool:
    return bool(MAIN_AUTO_VERSION_RE.match(version))


def list_lumen_crash_releases(owner: str, repo: str) -> list[dict]:
    """Return lumen-crash releases newest-first via GitHub Releases API."""
    releases: list[dict] = []
    page = 1
    while page <= 5:
        url = (
            f"https://api.github.com/repos/{owner}/{repo}/releases"
            f"?per_page=100&page={page}"
        )
        payload = json.loads(http_get(url, accept="application/vnd.github+json").decode("utf-8"))
        if not payload:
            break
        for item in payload:
            tag = str(item.get("tag_name") or "")
            version = version_from_tag(tag)
            if not version:
                continue
            if item.get("draft"):
                continue
            releases.append(
                {
                    "tag_name": tag,
                    "version": version,
                    "published_at": item.get("published_at") or item.get("created_at") or "",
                    "prerelease": bool(item.get("prerelease")),
                }
            )
        if len(payload) < 100:
            break
        page += 1
    # API is usually newest-first; keep stable sort by published_at desc as fallback.
    releases.sort(key=lambda item: item["published_at"], reverse=True)
    return releases


def resolve_version(policy: str, owner: str, repo: str, prefer_main_auto: bool = True) -> str:
    env_override = (os.environ.get("LUMEN_CRASH_SDK_VERSION") or "").strip()
    if env_override:
        print(f"Using LUMEN_CRASH_SDK_VERSION override: {env_override}")
        return env_override

    policy = (policy or "latest").strip()
    if policy and policy.lower() != "latest":
        print(f"Using explicit version policy: {policy}")
        return policy

    releases = list_lumen_crash_releases(owner, repo)
    if not releases:
        raise SystemExit(f"No lumen-crash releases found in {owner}/{repo}")

    if prefer_main_auto:
        main_auto = [item for item in releases if is_main_auto_version(item["version"])]
        if main_auto:
            chosen = main_auto[0]
            print(
                "Resolved latest main auto-release: "
                f"{chosen['version']} (tag {chosen['tag_name']}, published {chosen['published_at']})"
            )
            return chosen["version"]

    chosen = releases[0]
    print(
        "Resolved latest lumen-crash release: "
        f"{chosen['version']} (tag {chosen['tag_name']}, published {chosen['published_at']})"
    )
    return chosen["version"]


def write_resolved_version(path: Path, version: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(version.strip() + "\n", encoding="utf-8")
    print(f"Wrote resolved version to {path}")


def materialize(version: str, owner: str, repo: str, local_maven: Path) -> None:
    tag = f"{TAG_PREFIX}{version}"
    base = f"https://github.com/{owner}/{repo}/releases/download/{tag}"

    required = [
        f"lumen-crash-{version}.aar",
        f"lumen-crash-{version}.pom",
    ]
    optional = [
        f"lumen-crash-{version}.module",
        f"lumen-crash-{version}-sources.jar",
        "checksums.txt",
        "sdk-manifest.json",
    ]

    # Keep only the currently resolved version tree so stale coordinates do not linger.
    package_root = local_maven / "com" / "chloemlla" / "lumen" / "lumen-crash"
    if package_root.exists():
        shutil.rmtree(package_root)
    target_dir = package_root / version
    target_dir.mkdir(parents=True, exist_ok=True)

    downloaded: dict[str, bytes] = {}
    for name in required:
        data = http_get(f"{base}/{name}")
        downloaded[name] = data
        (target_dir / name).write_bytes(data)

    for name in optional:
        try:
            data = http_get(f"{base}/{name}")
        except SystemExit as error:
            print(f"Optional asset skipped: {name} ({error})")
            continue
        downloaded[name] = data
        (target_dir / name).write_bytes(data)

    aar_name = required[0]
    if not downloaded[aar_name].startswith(b"PK"):
        raise SystemExit(f"{aar_name} does not look like a ZIP/AAR (missing PK header)")

    if "checksums.txt" in downloaded:
        expected: dict[str, str] = {}
        for line in downloaded["checksums.txt"].decode("utf-8").splitlines():
            parts = line.split()
            if len(parts) >= 2:
                expected[parts[-1]] = parts[0].lower()
        for name in required:
            digest = sha256_bytes(downloaded[name])
            if expected.get(name) and expected[name] != digest:
                raise SystemExit(
                    f"Checksum mismatch for {name}: expected {expected[name]}, got {digest}"
                )

    if "sdk-manifest.json" in downloaded:
        manifest = json.loads(downloaded["sdk-manifest.json"].decode("utf-8"))
        if manifest.get("version") != version:
            raise SystemExit(
                f"sdk-manifest version mismatch: expected {version}, got {manifest.get('version')}"
            )
        coords = manifest.get("maven", {}).get("coordinates")
        expected_coords = f"com.chloemlla.lumen:lumen-crash:{version}"
        if coords and coords != expected_coords:
            raise SystemExit(f"Unexpected maven coordinates in manifest: {coords}")

    print(f"Materialized com.chloemlla.lumen:lumen-crash:{version} into {target_dir}")


def update_verification_metadata(meta_path: Path, version: str, local_maven: Path) -> None:
    """Add or update SHA256 checksums for lumen-crash artifacts in verification-metadata.xml."""
    if not meta_path.is_file():
        print(f"Verification metadata not found at {meta_path}, skipping update")
        return

    # Collect checksums from local-maven
    artifacts: list[dict[str, str]] = []
    for ext in ("aar", "pom", "module"):
        name = f"lumen-crash-{version}.{ext}"
        artifact_path = local_maven / "com" / "chloemlla" / "lumen" / "lumen-crash" / version / name
        if artifact_path.is_file():
            artifacts.append({
                "name": name,
                "sha256": sha256_bytes(artifact_path.read_bytes()),
            })

    if not artifacts:
        print(f"No lumen-crash artifacts found in {local_maven} for version {version}, skipping metadata update")
        return

    ET.register_namespace("", "https://schema.gradle.org/dependency-verification")
    tree = ET.parse(meta_path)
    root = tree.getroot()

    # Find or create the <components> element
    components = root.find("dv:components", NS)
    if components is None:
        components = ET.SubElement(root, "dv:components")

    # Remove existing lumen-crash component entries for any version
    for comp in list(components.findall("dv:component", NS)):
        group = comp.get("group", "")
        name = comp.get("name", "")
        if group == "com.chloemlla.lumen" and name == "lumen-crash":
            components.remove(comp)

    # Add fresh component entry for the resolved version
    comp = ET.SubElement(components, "dv:component")
    comp.set("group", "com.chloemlla.lumen")
    comp.set("name", "lumen-crash")
    comp.set("version", version)
    for art in artifacts:
        artifact_elem = ET.SubElement(comp, "dv:artifact")
        artifact_elem.set("name", art["name"])
        sha = ET.SubElement(artifact_elem, "dv:sha256")
        sha.set("value", art["sha256"])
        sha.set("origin", "Generated by fetch-lumen-crash-sdk.py")

    tree.write(meta_path, encoding="utf-8", xml_declaration=True)
    print(f"Updated {meta_path} with {len(artifacts)} lumen-crash artifact checksums (version {version})")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version-file", type=Path, default=DEFAULT_VERSION_FILE)
    parser.add_argument(
        "--resolved-version-file",
        type=Path,
        default=DEFAULT_RESOLVED_VERSION_FILE,
    )
    parser.add_argument("--local-maven", type=Path, default=DEFAULT_LOCAL_MAVEN)
    parser.add_argument("--verification-metadata", type=Path, default=DEFAULT_VERIFICATION_METADATA)
    parser.add_argument("--owner", default=DEFAULT_OWNER)
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--version", default="")
    parser.add_argument(
        "--prefer-any-lumen-crash-tag",
        action="store_true",
        help="When resolving latest, pick the newest lumen-crash tag even if it is pure semver.",
    )
    args = parser.parse_args()

    policy = args.version.strip() or read_version_policy(args.version_file)
    version = resolve_version(
        policy=policy,
        owner=args.owner,
        repo=args.repo,
        prefer_main_auto=not args.prefer_any_lumen_crash_tag,
    )

    write_resolved_version(args.resolved_version_file, version)
    materialize(version, args.owner, args.repo, args.local_maven)
    update_verification_metadata(args.verification_metadata, version, args.local_maven)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())