#!/usr/bin/env python3
"""Reject byte-level damage that tooling introduces and review does not catch.

Three classes of damage have reached this repository's tracked source:

* A raw NUL byte inside a Kotlin char literal in AuraOriginalsDownloader.kt.
  Kotlin compiled it, and git's binary heuristic only samples the first 8000
  bytes so `git diff` still rendered it, but ripgrep — what editors, review
  tooling, and agents actually search with — reported "binary file matches" and
  refused to display the file. The line it hid was a path-traversal guard.
* Three U+FFFD replacement characters in a VoteRepository.kt comment, the
  residue of a non-UTF-8 round trip.
* Fourteen tracked files with mixed line endings. Release gates hash tracked
  text and tools/foss_reproducibility_check.py diffs git-tracked inputs across
  two checkouts, so a line-ending flip changes a digest with no source change.

.gitattributes prevents the third going forward; this gate proves all three
stay fixed and fails loudly if any returns.

Exit 0 if clean, 1 if violations found.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


# Constructed from the code point on purpose: embedding the literal would make this
# file trip its own check, and self-exempting a detector is how detectors go blind.
REPLACEMENT_CHARACTER = chr(0xFFFD)

# Line endings are a per-path contract: .gitattributes pins Windows batch to
# CRLF because cmd.exe can mis-parse LF continuation lines.
CRLF_ALLOWED_SUFFIXES = (".bat",)


class SourceByteHygieneError(ValueError):
    """Raised when tracked text carries bytes that break tooling."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate tracked text files are free of NUL bytes, replacement "
        "characters, invalid UTF-8, and stray carriage returns.",
    )
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


"""Extensions that are source by definition, whatever git guessed.

git classifies a file as binary when it finds a NUL in the first 8000 bytes, so
relying on its verdict alone would skip exactly the files most in need of the
NUL check. AuraOriginalsDownloader.kt escaped notice the other way round — its
NUL sat at offset 11170, past git's sampling window, so git called it text while
ripgrep called it binary.
"""
SOURCE_SUFFIXES = (
    ".kt",
    ".kts",
    ".java",
    ".py",
    ".ts",
    ".cjs",
    ".mjs",
    ".js",
    ".json",
    ".xml",
    ".md",
    ".txt",
    ".yml",
    ".yaml",
    ".gradle",
    ".properties",
    ".pro",
    ".cfg",
    ".toml",
)


def tracked_text_files(repo_root: Path) -> list[str]:
    """Tracked paths to scan: git-classified text plus anything source-shaped."""
    result = subprocess.run(
        ["git", "-C", str(repo_root), "ls-files", "--eol"],
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        raise SourceByteHygieneError("git ls-files failed; not a git checkout")

    paths: list[str] = []
    for line in result.stdout.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        flags = parts[0].split()
        if not flags:
            continue
        path = parts[-1].strip()
        if flags[0] == "i/-text" and not path.endswith(SOURCE_SUFFIXES):
            continue
        paths.append(path)
    return paths


def inspect_file(repo_root: Path, relative_path: str) -> list[str]:
    path = repo_root / relative_path
    if not path.is_file():
        return []
    raw = path.read_bytes()
    problems: list[str] = []

    nul_index = raw.find(b"\x00")
    if nul_index != -1:
        problems.append(
            f"{relative_path}: NUL byte at offset {nul_index}; ripgrep treats the whole "
            "file as binary and will not show its contents. Write the escape "
            + repr("\u0000")
            + " instead of a literal byte"
        )

    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        problems.append(f"{relative_path}: not valid UTF-8 ({exc})")
        return problems

    replacements = text.count(REPLACEMENT_CHARACTER)
    if replacements:
        line = next(
            (
                index
                for index, value in enumerate(text.splitlines(), start=1)
                if REPLACEMENT_CHARACTER in value
            ),
            0,
        )
        problems.append(
            f"{relative_path}: {replacements} U+FFFD replacement character(s), first on "
            f"line {line}; the file survived a non-UTF-8 round trip and lost characters"
        )

    if b"\r" in raw and not relative_path.endswith(CRLF_ALLOWED_SUFFIXES):
        problems.append(
            f"{relative_path}: carriage returns in a file .gitattributes pins to LF; "
            "renormalize it or release digests over tracked text will drift"
        )

    return problems


def validate_source_bytes(repo_root: Path) -> dict[str, object]:
    paths = tracked_text_files(repo_root)
    if not paths:
        raise SourceByteHygieneError("no tracked text files found; the scanner is not reading anything")

    problems: list[str] = []
    for relative_path in paths:
        problems.extend(inspect_file(repo_root, relative_path))

    if problems:
        raise SourceByteHygieneError("; ".join(problems))

    return {
        "status": "ok",
        "policyKind": "sourceByteHygiene",
        "schemaVersion": 1,
        "scannedFileCount": len(paths),
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    try:
        result = validate_source_bytes(repo_root)
    except SourceByteHygieneError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, indent=2, sort_keys=True))
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
