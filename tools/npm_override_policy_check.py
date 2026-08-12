#!/usr/bin/env python3
"""Validate the functions npm overrides stay at or above their advisory floors.

Every entry in ``functions/package.json`` ``overrides`` pins a transitive
dependency to close a published advisory. A pin is a ceiling as well as a
floor, so an override added for security can itself become the vulnerable
version once a later advisory lands on it. This gate keeps the declared floor,
the manifest pin, and the resolved lockfile version in agreement.

Exit 0 if clean, 1 if violations found.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


DEFAULT_POLICY = "docs/security/npm-override-policy.json"

VERSION_PATTERN = re.compile(r"^\d+(?:\.\d+)*$")


class NpmOverridePolicyError(ValueError):
    """Raised when npm override policy validation fails."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate functions npm overrides against their advisory floors.",
    )
    parser.add_argument("--policy", default=DEFAULT_POLICY)
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


def load_json(path: Path, label: str) -> Any:
    if not path.is_file():
        raise NpmOverridePolicyError(f"{label} is missing at {path}")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise NpmOverridePolicyError(f"{label} is not valid JSON: {exc}") from exc


def parse_version(raw: str, label: str) -> tuple[int, ...]:
    value = raw.strip()
    if not VERSION_PATTERN.match(value):
        raise NpmOverridePolicyError(
            f"{label} must be an exact dotted version with no range operator, got {raw!r}"
        )
    return tuple(int(part) for part in value.split("."))


def compare_versions(left: tuple[int, ...], right: tuple[int, ...]) -> int:
    width = max(len(left), len(right))
    padded_left = left + (0,) * (width - len(left))
    padded_right = right + (0,) * (width - len(right))
    if padded_left < padded_right:
        return -1
    if padded_left > padded_right:
        return 1
    return 0


def resolved_lock_versions(lockfile: dict[str, Any], package: str) -> list[str]:
    suffix = f"node_modules/{package}"
    versions: list[str] = []
    for key, entry in (lockfile.get("packages") or {}).items():
        if key == suffix or key.endswith(f"/{suffix}"):
            version = entry.get("version")
            if isinstance(version, str):
                versions.append(version)
    return versions


def validate_policy(policy: Any) -> list[dict[str, Any]]:
    if not isinstance(policy, dict):
        raise NpmOverridePolicyError("policy must be a JSON object")
    if policy.get("policyKind") != "npmOverridePolicy":
        raise NpmOverridePolicyError("policyKind must be 'npmOverridePolicy'")
    entries = policy.get("overrides")
    if not isinstance(entries, list) or not entries:
        raise NpmOverridePolicyError("policy must declare a non-empty overrides list")
    for entry in entries:
        if not isinstance(entry, dict):
            raise NpmOverridePolicyError("each override entry must be an object")
        for field in ("package", "minimumSafeVersion", "reason"):
            if not isinstance(entry.get(field), str) or not entry[field].strip():
                raise NpmOverridePolicyError(
                    f"override entry {entry.get('package')!r} must declare a non-empty {field}"
                )
        advisories = entry.get("advisories")
        if not isinstance(advisories, list) or not advisories:
            raise NpmOverridePolicyError(
                f"override {entry['package']!r} must cite at least one advisory"
            )
    return entries


def validate_overrides(repo_root: Path, policy_path: Path) -> dict[str, Any]:
    policy = load_json(policy_path, "npm override policy")
    entries = validate_policy(policy)

    manifest_path = repo_root / policy.get("manifest", "functions/package.json")
    lockfile_path = repo_root / policy.get("lockfile", "functions/package-lock.json")
    manifest = load_json(manifest_path, "functions manifest")
    lockfile = load_json(lockfile_path, "functions lockfile")

    manifest_overrides = manifest.get("overrides")
    if not isinstance(manifest_overrides, dict):
        raise NpmOverridePolicyError("functions manifest must declare an overrides object")

    errors: list[str] = []
    checked: list[dict[str, str]] = []

    declared = {entry["package"] for entry in entries}
    unpoliced = sorted(set(manifest_overrides) - declared)
    if unpoliced:
        errors.append(
            "overrides present in the manifest but absent from the policy: "
            + ", ".join(unpoliced)
        )

    for entry in entries:
        package = entry["package"]
        floor = parse_version(entry["minimumSafeVersion"], f"{package} minimumSafeVersion")

        pinned_raw = manifest_overrides.get(package)
        if not isinstance(pinned_raw, str):
            errors.append(f"{package}: policy requires an override the manifest does not declare")
            continue
        pinned = parse_version(pinned_raw, f"{package} manifest override")
        if compare_versions(pinned, floor) < 0:
            errors.append(
                f"{package}: manifest pins {pinned_raw} below the advisory floor "
                f"{entry['minimumSafeVersion']} ({', '.join(entry['advisories'])})"
            )

        resolved = resolved_lock_versions(lockfile, package)
        if not resolved:
            errors.append(f"{package}: lockfile resolves no version for the pinned override")
            continue
        for version in resolved:
            if compare_versions(parse_version(version, f"{package} lockfile"), floor) < 0:
                errors.append(
                    f"{package}: lockfile resolves {version} below the advisory floor "
                    f"{entry['minimumSafeVersion']}"
                )
        checked.append(
            {
                "package": package,
                "minimumSafeVersion": entry["minimumSafeVersion"],
                "manifestOverride": pinned_raw,
                "resolved": sorted(set(resolved)),
            }
        )

    if errors:
        raise NpmOverridePolicyError("; ".join(errors))

    return {
        "status": "ok",
        "policyKind": "npmOverridePolicy",
        "schemaVersion": policy.get("schemaVersion", 1),
        "manifest": str(Path(policy.get("manifest", "functions/package.json")).as_posix()),
        "reviewedOn": policy.get("reviewedOn"),
        "overrideCount": len(checked),
        "overrides": checked,
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    policy_path = Path(args.policy)
    if not policy_path.is_absolute():
        policy_path = repo_root / policy_path
    try:
        result = validate_overrides(repo_root, policy_path)
    except NpmOverridePolicyError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, indent=2, sort_keys=True))
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
