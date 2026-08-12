from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.npm_override_policy_check import (
    NpmOverridePolicyError,
    validate_overrides,
)


REPO_ROOT = Path(__file__).resolve().parents[2]
LIVE_POLICY = REPO_ROOT / "docs" / "security" / "npm-override-policy.json"


BASE_POLICY = {
    "schemaVersion": 1,
    "policyKind": "npmOverridePolicy",
    "manifest": "functions/package.json",
    "lockfile": "functions/package-lock.json",
    "reviewedOn": "2026-08-10",
    "overrides": [
        {
            "package": "protobufjs",
            "minimumSafeVersion": "7.6.5",
            "advisories": ["GHSA-j3f2-48v5-ccww"],
            "reason": "test fixture",
        }
    ],
}


def build_repo(
    *,
    policy: dict | None = None,
    manifest_override: str | None = "7.6.5",
    lock_version: str | None = "7.6.5",
) -> tuple[tempfile.TemporaryDirectory, Path, Path]:
    tmpdir = tempfile.TemporaryDirectory()
    root = Path(tmpdir.name)
    (root / "functions").mkdir()
    (root / "docs" / "security").mkdir(parents=True)

    overrides = {} if manifest_override is None else {"protobufjs": manifest_override}
    (root / "functions" / "package.json").write_text(
        json.dumps({"name": "fixture", "overrides": overrides}), encoding="utf-8"
    )
    packages = {"": {"name": "fixture"}}
    if lock_version is not None:
        packages["node_modules/protobufjs"] = {"version": lock_version}
    (root / "functions" / "package-lock.json").write_text(
        json.dumps({"lockfileVersion": 3, "packages": packages}), encoding="utf-8"
    )

    policy_path = root / "docs" / "security" / "npm-override-policy.json"
    policy_path.write_text(json.dumps(policy or BASE_POLICY), encoding="utf-8")
    return tmpdir, root, policy_path


class NpmOverridePolicyCheckTest(unittest.TestCase):
    def test_live_policy_passes(self) -> None:
        result = validate_overrides(REPO_ROOT, LIVE_POLICY)

        self.assertEqual("ok", result["status"])
        self.assertEqual("npmOverridePolicy", result["policyKind"])
        self.assertGreaterEqual(result["overrideCount"], 4)

    def test_live_policy_covers_every_shipped_override(self) -> None:
        manifest = json.loads(
            (REPO_ROOT / "functions" / "package.json").read_text(encoding="utf-8")
        )
        policy = json.loads(LIVE_POLICY.read_text(encoding="utf-8"))
        policed = {entry["package"] for entry in policy["overrides"]}

        self.assertEqual(set(manifest["overrides"]), policed)

    def test_accepts_a_pin_above_the_floor(self) -> None:
        tmpdir, root, policy = build_repo(manifest_override="7.7.0", lock_version="7.7.0")
        with tmpdir:
            result = validate_overrides(root, policy)

        self.assertEqual("ok", result["status"])

    def test_rejects_a_manifest_pin_below_the_floor(self) -> None:
        tmpdir, root, policy = build_repo(manifest_override="7.6.4", lock_version="7.6.4")
        with tmpdir:
            with self.assertRaises(NpmOverridePolicyError) as ctx:
                validate_overrides(root, policy)

        self.assertIn("below the advisory floor", str(ctx.exception))
        self.assertIn("GHSA-j3f2-48v5-ccww", str(ctx.exception))

    def test_rejects_a_lockfile_resolving_below_the_floor(self) -> None:
        tmpdir, root, policy = build_repo(manifest_override="7.6.5", lock_version="7.6.4")
        with tmpdir:
            with self.assertRaises(NpmOverridePolicyError) as ctx:
                validate_overrides(root, policy)

        self.assertIn("lockfile resolves 7.6.4", str(ctx.exception))

    def test_rejects_a_nested_lockfile_entry_below_the_floor(self) -> None:
        tmpdir, root, policy = build_repo()
        with tmpdir:
            lockfile = root / "functions" / "package-lock.json"
            data = json.loads(lockfile.read_text(encoding="utf-8"))
            data["packages"]["node_modules/other/node_modules/protobufjs"] = {"version": "7.6.4"}
            lockfile.write_text(json.dumps(data), encoding="utf-8")

            with self.assertRaises(NpmOverridePolicyError) as ctx:
                validate_overrides(root, policy)

        self.assertIn("7.6.4", str(ctx.exception))

    def test_rejects_an_override_missing_from_the_manifest(self) -> None:
        tmpdir, root, policy = build_repo(manifest_override=None)
        with tmpdir:
            with self.assertRaises(NpmOverridePolicyError) as ctx:
                validate_overrides(root, policy)

        self.assertIn("the manifest does not declare", str(ctx.exception))

    def test_rejects_an_unpoliced_manifest_override(self) -> None:
        tmpdir, root, policy = build_repo()
        with tmpdir:
            manifest = root / "functions" / "package.json"
            data = json.loads(manifest.read_text(encoding="utf-8"))
            data["overrides"]["semver"] = "7.7.2"
            manifest.write_text(json.dumps(data), encoding="utf-8")

            with self.assertRaises(NpmOverridePolicyError) as ctx:
                validate_overrides(root, policy)

        self.assertIn("absent from the policy", str(ctx.exception))

    def test_rejects_a_range_operator_instead_of_an_exact_pin(self) -> None:
        tmpdir, root, policy = build_repo(manifest_override="^7.6.5")
        with tmpdir:
            with self.assertRaises(NpmOverridePolicyError) as ctx:
                validate_overrides(root, policy)

        self.assertIn("exact dotted version", str(ctx.exception))

    def test_rejects_an_override_citing_no_advisory(self) -> None:
        policy = json.loads(json.dumps(BASE_POLICY))
        policy["overrides"][0]["advisories"] = []
        tmpdir, root, policy_path = build_repo(policy=policy)
        with tmpdir:
            with self.assertRaises(NpmOverridePolicyError) as ctx:
                validate_overrides(root, policy_path)

        self.assertIn("must cite at least one advisory", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
