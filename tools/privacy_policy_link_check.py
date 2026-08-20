#!/usr/bin/env python3
"""Validate Aura's public and in-app privacy policy link contract."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

if __package__ in (None, ""):
    # Executed as `python tools/privacy_policy_link_check.py`, where only tools/ is on
    # sys.path. Tests import this as `tools.privacy_policy_link_check`, where it is not.
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.published_state import (
    PublishedStateError,
    assert_resolves_over_http,
    assert_tracked,
)


CHECK_COMMAND = "python3 tools/privacy_policy_link_check.py"


class PrivacyPolicyLinkError(ValueError):
    """Raised when the privacy policy link contract is stale."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate Aura privacy policy link coverage.")
    parser.add_argument("--policy", default="docs/privacy/privacy-policy-link.json")
    parser.add_argument("--repo-root", default=".")
    parser.add_argument(
        "--offline",
        action="store_true",
        help="skip fetching the published policy URL; content checks still run",
    )
    return parser.parse_args()


def read_json(path: Path) -> Any:
    if not path.is_file():
        raise PrivacyPolicyLinkError(f"policy file is missing: {path}")
    return json.loads(path.read_text(encoding="utf-8"))


def read_text(repo_root: Path, relative_path: str, label: str) -> str:
    path = repo_root / relative_path
    if not path.is_file():
        raise PrivacyPolicyLinkError(f"{label} is missing: {relative_path}")
    return path.read_text(encoding="utf-8")


def read_settings_privacy_surface(repo_root: Path, relative_path: str) -> str:
    path = repo_root / relative_path
    text = read_text(repo_root, relative_path, "Settings screen")
    if path.name != "SettingsScreen.kt" or path.parent == repo_root:
        return text
    settings_sections = sorted(
        section
        for section in path.parent.glob("*.kt")
        if section.is_file() and section != path
    )
    return "\n".join([text, *(section.read_text(encoding="utf-8") for section in settings_sections)])


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise PrivacyPolicyLinkError(f"{label} must be a JSON object")
    return value


def require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise PrivacyPolicyLinkError(f"{label} must be a non-empty string")
    return value.strip()


def require_string_list(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or not value:
        raise PrivacyPolicyLinkError(f"{label} must be a non-empty list")
    values = [require_string(item, f"{label}[{index}]") for index, item in enumerate(value)]
    if len(values) != len(set(values)):
        raise PrivacyPolicyLinkError(f"{label} contains duplicate values")
    return values


def require_contains(text: str, needle: str, label: str) -> None:
    if needle not in text:
        raise PrivacyPolicyLinkError(f"{label} is missing required text: {needle}")


def require_settings_privacy_title(repo_root: Path, settings_text: str, label: str) -> None:
    if "Privacy policy" in settings_text:
        return
    require_contains(settings_text, "settings_about_privacy_title", label)
    strings_text = read_text(repo_root, "app/src/main/res/values/strings.xml", "Android strings")
    require_contains(strings_text, ">Privacy policy<", "app/src/main/res/values/strings.xml")


def normalize_words(text: str) -> str:
    return " ".join(text.lower().split())


def validate_policy(
    repo_root: Path,
    policy: dict[str, Any],
    check_urls: bool = False,
) -> dict[str, Any]:
    """Validate the link contract.

    [check_urls] asks the network whether the published policy actually resolves.
    It is off by default so the content assertions stay deterministic and offline;
    `main()` turns it on, which is where the answer matters.
    """
    if policy.get("schemaVersion") != 1:
        raise PrivacyPolicyLinkError("privacy policy link schemaVersion must be 1")
    if policy.get("policyKind") != "privacyPolicyLink":
        raise PrivacyPolicyLinkError("privacy policy link policyKind is invalid")

    public_url = require_string(policy.get("publicUrl"), "publicUrl")
    if not public_url.startswith("https://"):
        raise PrivacyPolicyLinkError("publicUrl must use HTTPS")
    if "docs/privacy/privacy-policy.md" not in public_url:
        raise PrivacyPolicyLinkError("publicUrl must point to docs/privacy/privacy-policy.md")

    policy_doc_path = require_string(policy.get("policyDoc"), "policyDoc")
    settings_screen_path = require_string(policy.get("settingsScreen"), "settingsScreen")
    fastlane_path = require_string(policy.get("fastlaneFullDescription"), "fastlaneFullDescription")
    readme_path = require_string(policy.get("readme"), "readme")
    release_dry_run_path = require_string(policy.get("releaseDryRunDoc"), "releaseDryRunDoc")

    # Content checks below only prove the file is correct locally. The app opens the
    # public URL, so the document must also be published — an untracked policy passes
    # every content assertion and still 404s for every user.
    try:
        assert_tracked(repo_root, policy_doc_path, "privacy policy")
        if check_urls:
            assert_resolves_over_http(public_url, "privacy policy link")
    except PublishedStateError as exc:
        raise PrivacyPolicyLinkError(str(exc)) from exc

    policy_text = read_text(repo_root, policy_doc_path, "privacy policy")
    settings_text = read_settings_privacy_surface(repo_root, settings_screen_path)
    fastlane_text = read_text(repo_root, fastlane_path, "Fastlane full description")
    readme_text = read_text(repo_root, readme_path, "README")
    release_dry_run_text = read_text(repo_root, release_dry_run_path, "release dry-run doc")

    require_settings_privacy_title(repo_root, settings_text, settings_screen_path)
    require_contains(settings_text, public_url, settings_screen_path)
    require_contains(fastlane_text, public_url, fastlane_path)
    require_contains(readme_text, public_url, readme_path)
    require_contains(release_dry_run_text, CHECK_COMMAND, release_dry_run_path)

    headings = require_string_list(policy.get("requiredPolicyHeadings"), "requiredPolicyHeadings")
    for heading in headings:
        require_contains(policy_text, heading, policy_doc_path)

    terms = require_string_list(policy.get("requiredPolicyTerms"), "requiredPolicyTerms")
    lowered_policy_text = normalize_words(policy_text)
    for term in terms:
        if normalize_words(term) not in lowered_policy_text:
            raise PrivacyPolicyLinkError(f"{policy_doc_path} is missing required privacy term: {term}")

    source_urls = require_string_list(policy.get("sourceUrls"), "sourceUrls")
    for url in source_urls:
        if not url.startswith("https://support.google.com/googleplay/android-developer/"):
            raise PrivacyPolicyLinkError(f"sourceUrls must use official Google Play policy URLs: {url}")

    return {
        "policyKind": policy["policyKind"],
        "schemaVersion": policy["schemaVersion"],
        "status": "ok",
        "publicUrl": public_url,
        "headingCount": len(headings),
        "requiredTermCount": len(terms),
        "sourceUrlCount": len(source_urls),
        "releaseGate": "ok",
        "inAppLink": "ok",
        # "ok" only when the published copy was actually fetched. Saying "ok" for a
        # link nobody asked about is the exact failure this gate had.
        "publicUrlResolves": "ok" if check_urls else "notChecked",
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    try:
        policy = require_object(read_json((repo_root / args.policy).resolve()), "privacy policy link policy")
        result = validate_policy(repo_root, policy, check_urls=not args.offline)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
