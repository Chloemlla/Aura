#!/usr/bin/env python3
"""Run Aura's release-truth gates against one exact Git commit.

The checker materializes the requested commit with ``git archive``. It never
copies the caller's working tree, ignored files, local properties, signing
keys, build outputs, or remotes. Temporary local Git metadata preserves the
selected tree's tracked paths, executable modes, and tag names so existing
published-state gates see the same offline repository facts.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path
from typing import Any

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tools.release_metadata_consistency_check import (
    ReleaseMetadataConsistencyError,
    require_string,
    resolve_required_evidence_paths,
)

POLICY_PATH = "docs/distribution/release-metadata-consistency.json"
OWNER_EVIDENCE_STATES = {"pass", "fail", "unknown"}
WORKFLOW_REFERENCE = re.compile(r"`(\.github[/\\]workflows[/\\][^`\s]+)`")


class ReleaseCleanCloneError(ValueError):
    """Raised when exact-commit release evidence is incomplete or a gate fails."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=".")
    parser.add_argument("--revision", default="HEAD")
    parser.add_argument("--policy", default=POLICY_PATH)
    parser.add_argument(
        "--gate-timeout-seconds",
        type=int,
        default=300,
        help="Maximum runtime for each tracked-input gate.",
    )
    return parser.parse_args()


def _run(
    command: list[str],
    cwd: Path,
    *,
    timeout: int = 120,
    env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            command,
            cwd=cwd,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
            env=env,
        )
    except subprocess.TimeoutExpired as exc:
        raise ReleaseCleanCloneError(
            f"command timed out after {timeout}s: {' '.join(command)}"
        ) from exc


def _git(repo_root: Path, *args: str, timeout: int = 120) -> str:
    result = _run(["git", *args], repo_root, timeout=timeout)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise ReleaseCleanCloneError(
            f"git {' '.join(args)} failed" + (f": {detail}" if detail else "")
        )
    return result.stdout.strip()


def _safe_extract(archive_path: Path, destination: Path) -> None:
    destination_root = destination.resolve()
    with tarfile.open(archive_path, "r") as archive:
        for member in archive.getmembers():
            target = (destination / member.name).resolve()
            try:
                target.relative_to(destination_root)
            except ValueError as exc:
                raise ReleaseCleanCloneError(
                    f"commit archive contains an unsafe path: {member.name}"
                ) from exc
        archive.extractall(destination, filter="data")


def materialize_revision(
    repo_root: Path, revision: str, destination: Path
) -> tuple[str, set[str]]:
    """Extract one commit and return its hash and exact tracked path set."""
    commit = _git(repo_root, "rev-parse", "--verify", f"{revision}^{{commit}}")
    tracked_output = _git(repo_root, "ls-tree", "-r", "--name-only", commit)
    tracked_paths = {
        line.replace("\\", "/") for line in tracked_output.splitlines() if line.strip()
    }
    if not tracked_paths:
        raise ReleaseCleanCloneError(f"revision {commit} contains no tracked files")
    tree_output = _git(repo_root, "ls-tree", "-r", commit)
    executable_paths = []
    for line in tree_output.splitlines():
        metadata, separator, path = line.partition("\t")
        if separator and metadata.split(maxsplit=1)[0] == "100755":
            executable_paths.append(path.replace("\\", "/"))
    tag_names = [line for line in _git(repo_root, "tag", "--list").splitlines() if line]

    archive_path = destination.parent / "source.tar"
    _git(
        repo_root,
        "archive",
        "--format=tar",
        f"--output={archive_path}",
        commit,
        timeout=300,
    )
    destination.mkdir(parents=True, exist_ok=True)
    _safe_extract(archive_path, destination)

    # Existing release gates use Git for tracked-file, executable-mode, and tag
    # checks. The archive contains only tracked paths. The temporary repository
    # has no remote, so it cannot accidentally turn a network publication check
    # into an input from outside the selected commit.
    _git(destination, "init", "--quiet")
    _git(destination, "config", "core.autocrlf", "false")
    _git(destination, "config", "user.name", "Aura Release Gate")
    _git(destination, "config", "user.email", "release-gate@example.invalid")
    _git(destination, "add", "--force", "--all", timeout=300)
    for relative_path in executable_paths:
        _git(destination, "update-index", "--chmod=+x", "--", relative_path)
    _git(destination, "commit", "--quiet", "-m", f"Exact tree {commit}", timeout=300)
    for tag_name in tag_names:
        _git(destination, "tag", tag_name)
    return commit, tracked_paths


def _require_object_list(value: Any, label: str) -> list[dict[str, Any]]:
    if not isinstance(value, list) or not value:
        raise ReleaseCleanCloneError(f"{label} must be a non-empty list")
    rows: list[dict[str, Any]] = []
    for index, row in enumerate(value):
        if not isinstance(row, dict):
            raise ReleaseCleanCloneError(f"{label}[{index}] must be an object")
        rows.append(row)
    return rows


def _require_command(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or not value:
        raise ReleaseCleanCloneError(f"{label} must be a non-empty string list")
    command: list[str] = []
    for index, part in enumerate(value):
        if not isinstance(part, str) or not part.strip():
            raise ReleaseCleanCloneError(f"{label}[{index}] must be a non-empty string")
        command.append(part.strip())
    return command


def _normalized_repo_path(value: str, label: str) -> str:
    normalized = Path(value.replace("\\", "/"))
    if normalized.is_absolute() or ".." in normalized.parts:
        raise ReleaseCleanCloneError(f"{label} must stay inside the repository: {value}")
    return normalized.as_posix()


def validate_workflow_references(repo_root: Path, tracked_paths: set[str]) -> int:
    """Reject active docs that name workflow files absent from the commit."""
    checked = 0
    errors: list[str] = []
    for path in sorted((repo_root / "docs").rglob("*.md")):
        if not path.is_file():
            continue
        relative = path.relative_to(repo_root).as_posix()
        if relative not in tracked_paths:
            continue
        checked += 1
        text = path.read_text(encoding="utf-8")
        for match in WORKFLOW_REFERENCE.finditer(text):
            referenced = match.group(1).replace("\\", "/").rstrip(".,;:")
            if referenced not in tracked_paths:
                errors.append(f"{relative} references absent tracked file {referenced}")
    if errors:
        raise ReleaseCleanCloneError("; ".join(errors))
    return checked


def validate_contract(
    repo_root: Path, policy: dict[str, Any], tracked_paths: set[str]
) -> dict[str, Any]:
    """Validate tracked evidence, executable gates, and owner-only tri-state rows."""
    try:
        evidence_paths = resolve_required_evidence_paths(policy)
    except ReleaseMetadataConsistencyError as exc:
        raise ReleaseCleanCloneError(str(exc)) from exc

    for relative in evidence_paths:
        if relative not in tracked_paths:
            raise ReleaseCleanCloneError(
                f"required release evidence is not tracked in the selected commit: {relative}"
            )
        if not (repo_root / relative).is_file():
            raise ReleaseCleanCloneError(
                f"required tracked release evidence is missing from the archive: {relative}"
            )

    gates = _require_object_list(policy.get("trackedInputGates"), "trackedInputGates")
    gate_ids: set[str] = set()
    normalized_gates: list[dict[str, Any]] = []
    for index, gate in enumerate(gates):
        gate_id = require_string(gate.get("id"), f"trackedInputGates[{index}].id")
        if gate_id in gate_ids:
            raise ReleaseCleanCloneError(f"trackedInputGates contains duplicate id: {gate_id}")
        gate_ids.add(gate_id)
        command = _require_command(
            gate.get("command"), f"trackedInputGates[{index}].command"
        )
        if command[0] != "-m":
            script = _normalized_repo_path(
                command[0], f"trackedInputGates[{index}].command[0]"
            )
            if script not in tracked_paths or not (repo_root / script).is_file():
                raise ReleaseCleanCloneError(
                    f"tracked gate {gate_id} names an untracked or missing script: {script}"
                )
        normalized_gates.append({"id": gate_id, "command": command})

    owner_rows = _require_object_list(policy.get("ownerOnlyEvidence"), "ownerOnlyEvidence")
    owner_ids: set[str] = set()
    owner_results: list[dict[str, str]] = []
    for index, row in enumerate(owner_rows):
        owner_id = require_string(row.get("id"), f"ownerOnlyEvidence[{index}].id")
        if owner_id in owner_ids:
            raise ReleaseCleanCloneError(f"ownerOnlyEvidence contains duplicate id: {owner_id}")
        owner_ids.add(owner_id)
        status = require_string(row.get("status"), f"ownerOnlyEvidence[{index}].status")
        if status not in OWNER_EVIDENCE_STATES:
            raise ReleaseCleanCloneError(
                f"ownerOnlyEvidence {owner_id} status must be pass, fail, or unknown"
            )
        reason = require_string(row.get("reason"), f"ownerOnlyEvidence[{index}].reason")
        owner_results.append({"id": owner_id, "status": status, "reason": reason})

    checked_docs = validate_workflow_references(repo_root, tracked_paths)
    return {
        "evidencePaths": evidence_paths,
        "gates": normalized_gates,
        "ownerOnlyEvidence": owner_results,
        "checkedDocumentationFiles": checked_docs,
    }


def run_tracked_gates(
    repo_root: Path, gates: list[dict[str, Any]], timeout: int
) -> list[dict[str, str]]:
    env = os.environ.copy()
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    results: list[dict[str, str]] = []
    for gate in gates:
        gate_id = str(gate["id"])
        command = [sys.executable, *gate["command"]]
        completed = _run(command, repo_root, timeout=timeout, env=env)
        if completed.returncode != 0:
            output = "\n".join(
                part.strip() for part in (completed.stdout, completed.stderr) if part.strip()
            )
            tail = "\n".join(output.splitlines()[-30:])
            raise ReleaseCleanCloneError(
                f"tracked-input gate {gate_id} failed with exit "
                f"{completed.returncode}:\n{tail}"
            )
        results.append({"id": gate_id, "status": "pass"})
    return results


def validate_revision(
    repo_root: Path,
    revision: str,
    policy_path: str,
    timeout: int,
) -> dict[str, Any]:
    repo_root = repo_root.resolve()
    with tempfile.TemporaryDirectory(prefix="aura-release-truth-") as temp_dir:
        temp_root = Path(temp_dir)
        source_root = temp_root / "source"
        commit, tracked_paths = materialize_revision(repo_root, revision, source_root)
        relative_policy = _normalized_repo_path(policy_path, "policy")
        if relative_policy not in tracked_paths:
            raise ReleaseCleanCloneError(
                f"release truth policy is not tracked in {commit}: {relative_policy}"
            )
        policy = json.loads((source_root / relative_policy).read_text(encoding="utf-8"))
        if not isinstance(policy, dict):
            raise ReleaseCleanCloneError("release truth policy must be a JSON object")
        contract = validate_contract(source_root, policy, tracked_paths)
        gate_results = run_tracked_gates(source_root, contract["gates"], timeout)
        owner_counts = {
            state: sum(
                1 for row in contract["ownerOnlyEvidence"] if row["status"] == state
            )
            for state in sorted(OWNER_EVIDENCE_STATES)
        }
        return {
            "status": "ok",
            "revision": commit,
            "trackedFileCount": len(tracked_paths),
            "requiredEvidenceCount": len(contract["evidencePaths"]),
            "checkedDocumentationFiles": contract["checkedDocumentationFiles"],
            "trackedInputGates": gate_results,
            "ownerOnlyEvidence": contract["ownerOnlyEvidence"],
            "ownerOnlyEvidenceCounts": owner_counts,
        }


def main() -> int:
    args = parse_args()
    if args.gate_timeout_seconds < 1:
        print("error: --gate-timeout-seconds must be positive", file=sys.stderr)
        return 2
    try:
        result = validate_revision(
            Path(args.repo_root),
            args.revision,
            args.policy,
            args.gate_timeout_seconds,
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
