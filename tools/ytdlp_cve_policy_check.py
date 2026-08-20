#!/usr/bin/env python3
"""Validate Aura's effective bundled yt-dlp version and CVE reachability policy."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path
from typing import Any


class YtDlpCvePolicyError(ValueError):
    """Raised when the yt-dlp CVE policy is missing or stale."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate Aura yt-dlp CVE reachability policy.")
    parser.add_argument("--policy", default="docs/security/ytdlp-cve-policy.json")
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


def read_json(path: Path) -> Any:
    if not path.is_file():
        raise YtDlpCvePolicyError(f"JSON file is missing: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise YtDlpCvePolicyError(f"{label} must be a JSON object")
    return value


def require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise YtDlpCvePolicyError(f"{label} must be a non-empty string")
    return value.strip()


def require_string_list(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or not value:
        raise YtDlpCvePolicyError(f"{label} must be a non-empty list")
    values = [require_string(item, f"{label}[{index}]") for index, item in enumerate(value)]
    if len(values) != len(set(values)):
        raise YtDlpCvePolicyError(f"{label} contains duplicate values")
    return values


def require_object_list(value: Any, label: str) -> list[dict[str, Any]]:
    if not isinstance(value, list) or not value:
        raise YtDlpCvePolicyError(f"{label} must be a non-empty list")
    return [require_object(item, f"{label}[{index}]") for index, item in enumerate(value)]


def parse_yt_dlp_version(value: str, label: str) -> tuple[int, int, int]:
    parts = value.split(".")
    if len(parts) != 3 or not all(part.isdecimal() for part in parts):
        raise YtDlpCvePolicyError(f"{label} must use YYYY.MM.DD format: {value}")
    year, month, day = (int(part) for part in parts)
    if not (2000 <= year <= 2999 and 1 <= month <= 12 and 1 <= day <= 31):
        raise YtDlpCvePolicyError(f"{label} is not a plausible date version: {value}")
    return year, month, day


def iter_ytdlp_versions(value: Any) -> list[str]:
    versions: list[str] = []
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "yt-dlp version":
                versions.append(require_string(child, "yt-dlp version"))
            else:
                versions.extend(iter_ytdlp_versions(child))
    elif isinstance(value, list):
        for child in value:
            versions.extend(iter_ytdlp_versions(child))
    return versions


def bundled_ytdlp_version(lock: dict[str, Any]) -> str:
    versions = sorted(set(iter_ytdlp_versions(lock)))
    if not versions:
        raise YtDlpCvePolicyError("native compliance lock does not record a yt-dlp version")
    if len(versions) != 1:
        raise YtDlpCvePolicyError(f"native compliance lock has multiple yt-dlp versions: {versions}")
    return versions[0]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bundled_ytdlp_version_from_payload(path: Path) -> str:
    if not path.is_file():
        raise YtDlpCvePolicyError(f"bundled yt-dlp payload is missing: {path}")
    try:
        with zipfile.ZipFile(path) as archive:
            version_text = archive.read("yt_dlp/version.py").decode("utf-8", "replace")
    except (KeyError, zipfile.BadZipFile) as exc:
        raise YtDlpCvePolicyError(
            f"bundled yt-dlp payload does not contain yt_dlp/version.py: {path}"
        ) from exc
    match = re.search(r"__version__\s*=\s*['\"]([^'\"]+)['\"]", version_text)
    if not match:
        raise YtDlpCvePolicyError("bundled yt-dlp payload does not declare __version__")
    return match.group(1)


def version_in_affected_range(version: str, introduced: str, fixed: str) -> bool:
    current = parse_yt_dlp_version(version, "yt-dlp version")
    first_affected = parse_yt_dlp_version(introduced, "affectedVersionRange.introduced")
    first_fixed = parse_yt_dlp_version(fixed, "affectedVersionRange.fixed")
    return first_affected <= current < first_fixed


def source_files(repo_root: Path, source_roots: list[str]) -> list[Path]:
    files: list[Path] = []
    for root in source_roots:
        root_path = repo_root / root
        if not root_path.exists():
            raise YtDlpCvePolicyError(f"scan source root is missing: {root}")
        candidates = [root_path] if root_path.is_file() else root_path.rglob("*")
        for path in candidates:
            if path.is_file() and path.suffix in {".java", ".kt", ".kts"}:
                files.append(path)
    return sorted(files)


def forbidden_option_hits(repo_root: Path, source_roots: list[str], forbidden_options: list[str]) -> list[str]:
    hits: list[str] = []
    for path in source_files(repo_root, source_roots):
        text = path.read_text(encoding="utf-8", errors="ignore")
        for line_number, line in enumerate(text.splitlines(), start=1):
            for option in forbidden_options:
                if option in line:
                    hits.append(f"{path.relative_to(repo_root)}:{line_number}: {option}")
    return hits


def validate_required_call_sites(repo_root: Path, call_sites: list[dict[str, Any]]) -> list[str]:
    validated: list[str] = []
    for index, raw_site in enumerate(call_sites):
        site_id = require_string(raw_site.get("id"), f"requiredYtDlpCallSites[{index}].id")
        relative_path = require_string(raw_site.get("path"), f"requiredYtDlpCallSites[{index}].path")
        required_terms = require_string_list(
            raw_site.get("requiredTerms"),
            f"requiredYtDlpCallSites[{index}].requiredTerms",
        )
        path = repo_root / relative_path
        if not path.is_file():
            raise YtDlpCvePolicyError(f"{site_id} source file is missing: {relative_path}")
        text = path.read_text(encoding="utf-8", errors="ignore")
        for term in required_terms:
            if term not in text:
                raise YtDlpCvePolicyError(f"{relative_path} is missing required yt-dlp term: {term}")
        validated.append(site_id)
    return validated


def validate_download_bounds(repo_root: Path, policy: dict[str, Any]) -> list[str]:
    """Every media download must be bounded before a byte is written.

    The size ceiling used to be checked only after the file was fully written,
    so a long video wrote gigabytes to the device and was then rejected. The
    helper carrying `--max-filesize` has to exist, and every download call site
    has to route through it — a forbidden-option scan cannot see an option that
    was simply never passed.
    """
    validated: list[str] = []

    helper = require_object(policy.get("downloadBoundsHelper"), "downloadBoundsHelper")
    helper_path = require_string(helper.get("path"), "downloadBoundsHelper.path")
    path = repo_root / helper_path
    if not path.is_file():
        raise YtDlpCvePolicyError(f"download bounds helper is missing: {helper_path}")
    helper_text = path.read_text(encoding="utf-8", errors="ignore")
    for term in require_string_list(
        helper.get("requiredTerms"), "downloadBoundsHelper.requiredTerms"
    ):
        if term not in helper_text:
            raise YtDlpCvePolicyError(
                f"{helper_path} no longer passes the required download option: {term}"
            )

    for index, raw_site in enumerate(
        require_object_list(policy.get("downloadCallSites"), "downloadCallSites")
    ):
        site_id = require_string(raw_site.get("id"), f"downloadCallSites[{index}].id")
        relative_path = require_string(
            raw_site.get("path"), f"downloadCallSites[{index}].path"
        )
        site_path = repo_root / relative_path
        if not site_path.is_file():
            raise YtDlpCvePolicyError(f"{site_id} source file is missing: {relative_path}")
        text = site_path.read_text(encoding="utf-8", errors="ignore")
        for term in require_string_list(
            raw_site.get("requiredTerms"), f"downloadCallSites[{index}].requiredTerms"
        ):
            if term not in text:
                raise YtDlpCvePolicyError(
                    f"{relative_path} downloads media without bounding it first "
                    f"(missing {term})"
                )
        # Every execute in a download site must be preceded by the bounds call,
        # so adding a third branch that forgets it fails here.
        executes = text.count("YoutubeDL.getInstance().execute")
        bounded = text.count("applyYtDlpDownloadBounds")
        if bounded < executes:
            raise YtDlpCvePolicyError(
                f"{relative_path} has {executes} yt-dlp executions but only {bounded} "
                "bounded download(s); every download must pass a size cap"
            )
        validated.append(site_id)
    return validated


def validate_policy(repo_root: Path, policy_path: Path) -> dict[str, Any]:
    policy = require_object(read_json(policy_path), "policy")
    schema_version = policy.get("schemaVersion")
    if schema_version not in (1, 2):
        raise YtDlpCvePolicyError("schemaVersion must be 1 or 2")

    policy_kind = require_string(policy.get("policyKind"), "policyKind")
    if policy_kind not in ("ytdlpNetrcCommandCveReachability", "ytdlpCveReachability"):
        raise YtDlpCvePolicyError("policyKind must be ytdlpNetrcCommandCveReachability or ytdlpCveReachability")

    if schema_version == 1:
        if require_string(policy.get("cve"), "cve") != "CVE-2026-26331":
            raise YtDlpCvePolicyError("cve must be CVE-2026-26331")
        tracked_cves = [policy["cve"]]
        advisory = require_string(policy.get("advisory"), "advisory")
    else:
        tracked_cve_entries = require_object_list(policy.get("trackedCves"), "trackedCves")
        tracked_cves = [require_string(entry.get("cve"), f"trackedCves[{i}].cve") for i, entry in enumerate(tracked_cve_entries)]
        advisory = tracked_cve_entries[0].get("advisory", "") if tracked_cve_entries else ""

    affected_range = require_object(policy.get("affectedVersionRange"), "affectedVersionRange")
    introduced = require_string(affected_range.get("introduced"), "affectedVersionRange.introduced")
    fixed = require_string(affected_range.get("fixed"), "affectedVersionRange.fixed")
    minimum_safe = require_string(policy.get("minimumSafeYtDlpVersion"), "minimumSafeYtDlpVersion")
    if minimum_safe != fixed:
        raise YtDlpCvePolicyError("minimumSafeYtDlpVersion must match affectedVersionRange.fixed")

    lock_path = repo_root / require_string(policy.get("nativeComplianceLockPath"), "nativeComplianceLockPath")
    lock = require_object(read_json(lock_path), "native compliance lock")
    payload_relative = policy.get("bundledPayloadPath")
    payload_sha256: str | None = None
    if payload_relative is None:
        ytdlp_version = bundled_ytdlp_version(lock)
    else:
        payload_path = repo_root / require_string(payload_relative, "bundledPayloadPath")
        expected_sha256 = require_string(policy.get("bundledPayloadSha256"), "bundledPayloadSha256").lower()
        if len(expected_sha256) != 64 or any(char not in "0123456789abcdef" for char in expected_sha256):
            raise YtDlpCvePolicyError("bundledPayloadSha256 must be a lowercase SHA-256 digest")
        payload_sha256 = sha256(payload_path)
        if payload_sha256 != expected_sha256:
            raise YtDlpCvePolicyError(
                f"bundled yt-dlp payload SHA-256 mismatch: expected {expected_sha256}, got {payload_sha256}"
            )
        require_string(policy.get("bundledPayloadSourceUrl"), "bundledPayloadSourceUrl")
        ytdlp_version = bundled_ytdlp_version_from_payload(payload_path)
    affected = version_in_affected_range(ytdlp_version, introduced, fixed)

    source_roots = require_string_list(policy.get("scanSourceRoots"), "scanSourceRoots")
    forbidden_options = require_string_list(policy.get("forbiddenOptions"), "forbiddenOptions")
    call_sites = validate_required_call_sites(
        repo_root,
        require_object_list(policy.get("requiredYtDlpCallSites"), "requiredYtDlpCallSites"),
    )
    hits = forbidden_option_hits(repo_root, source_roots, forbidden_options)
    if hits:
        raise YtDlpCvePolicyError(
            "forbidden yt-dlp option is reachable in Aura source:\n" + "\n".join(hits)
        )
    download_sites = validate_download_bounds(repo_root, policy)

    status = "affected_not_reachable" if affected else "fixed_or_unaffected"
    return {
        "status": status,
        "trackedCves": tracked_cves,
        "advisory": advisory,
        "bundledYtDlpVersion": ytdlp_version,
        "bundledPayloadSha256": payload_sha256,
        "minimumSafeYtDlpVersion": minimum_safe,
        "forbiddenOptions": forbidden_options,
        "validatedCallSites": call_sites,
        "boundedDownloadSites": download_sites,
        "scannedSourceRoots": source_roots,
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    policy_path = (repo_root / args.policy).resolve()
    try:
        result = validate_policy(repo_root, policy_path)
    except YtDlpCvePolicyError as exc:
        print(f"ytdlp-cve-policy: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
