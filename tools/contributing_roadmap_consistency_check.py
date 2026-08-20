#!/usr/bin/env python3
"""Keep CONTRIBUTING.md's description of the roadmap true to ROADMAP.md.

CONTRIBUTING.md spent several releases telling contributors to file issues
"against existing items by their ID", to add sources to an "Appendix", and to
read a "How to read this document" section for Now/Next/Later tier thresholds.
None of those existed: the roadmap uses P0-P3 priorities and a fixed six-field
item template. A contributor following the guide could not file a conforming
issue, and no gate noticed because the two files were never compared.

This checks the guide against the roadmap it documents:
  * every priority tier the guide names is one the roadmap actually uses,
  * every field in the guide's item template appears in real roadmap items,
  * the guide does not resurrect a retired roadmap vocabulary.

Exit 0 if clean, 1 if violations found.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


CONTRIBUTING = "CONTRIBUTING.md"
ROADMAP = "ROADMAP.md"
BLOCKED_ROADMAP = "Roadmap_Blocked.md"

ROADMAP_ITEM = re.compile(r"^-\s*\[\s\]\s*(P\d+)\s*—", re.MULTILINE)
GUIDE_TIER = re.compile(r"\*\*(P\d+)\*\*")

# The priority scheme the roadmap is allowed to use.
VALID_TIERS = ("P0", "P1", "P2", "P3")

# The six fields every roadmap item carries. The guide publishes this template,
# so each field has to be one the roadmap really uses.
TEMPLATE_FIELDS = ("Why", "Evidence", "Touches", "Acceptance", "Complexity")

# Vocabulary that was removed from the roadmap. If the guide names any of it
# again, the guide is describing a document that does not exist.
RETIRED_VOCABULARY = (
    ("by their ID", "roadmap items have no ID scheme"),
    ("in the Appendix", "the roadmap has no Appendix"),
    ("How to read this document", "the roadmap has no such section"),
    ("Under Consideration", "the roadmap does not use Now/Next/Later tiers"),
)


class ContributingRoadmapError(ValueError):
    """Raised when the contributing guide misdescribes the roadmap."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate CONTRIBUTING.md describes the roadmap that exists.",
    )
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


def read(repo_root: Path, relative_path: str) -> str:
    path = repo_root / relative_path
    if not path.is_file():
        raise ContributingRoadmapError(f"missing file: {relative_path}")
    return path.read_text(encoding="utf-8")


def roadmap_tiers(roadmap: str) -> set[str]:
    return set(ROADMAP_ITEM.findall(roadmap))


def roadmap_fields(roadmap: str) -> set[str]:
    found = set()
    for field in TEMPLATE_FIELDS:
        if re.search(rf"^\s+{field}:", roadmap, re.MULTILINE):
            found.add(field)
    return found


def validate_contributing_roadmap(repo_root: Path) -> dict[str, object]:
    guide = read(repo_root, CONTRIBUTING)
    roadmap = read(repo_root, ROADMAP)

    errors: list[str] = []

    tiers = roadmap_tiers(roadmap)
    if not tiers:
        raise ContributingRoadmapError(
            f"{ROADMAP} has no priority-tagged items; the scanner is not reading anything"
        )

    for tier in sorted(tiers - set(VALID_TIERS)):
        errors.append(
            f"{ROADMAP} uses tier {tier}, which is outside the documented "
            f"{VALID_TIERS[0]}-{VALID_TIERS[-1]} scheme"
        )

    named_tiers = set(GUIDE_TIER.findall(guide))
    if not named_tiers:
        errors.append(
            f"{CONTRIBUTING} names no priority tier, so it does not tell a contributor "
            "how the roadmap is ordered"
        )
    for tier in sorted(named_tiers - set(VALID_TIERS)):
        errors.append(
            f"{CONTRIBUTING} documents tier {tier}, which is not part of the "
            f"{VALID_TIERS[0]}-{VALID_TIERS[-1]} scheme the roadmap uses"
        )
    # The guide states the range as its endpoints, so a tier in active use must
    # fall inside what the guide names rather than be listed literally.
    if named_tiers <= set(VALID_TIERS) and named_tiers:
        lowest, highest = min(named_tiers), max(named_tiers)
        for tier in sorted(tiers):
            if not lowest <= tier <= highest:
                errors.append(
                    f"{ROADMAP} uses tier {tier}, which falls outside the "
                    f"{lowest}-{highest} range {CONTRIBUTING} documents"
                )

    present = roadmap_fields(roadmap)
    for field in TEMPLATE_FIELDS:
        if field not in present:
            errors.append(
                f"{CONTRIBUTING} publishes a template field '{field}:' that no "
                f"{ROADMAP} item uses"
            )
            continue
        if not re.search(rf"^\s*{field}:", guide, re.MULTILINE):
            errors.append(
                f"{CONTRIBUTING} omits the '{field}:' field that every {ROADMAP} item carries"
            )

    for phrase, reason in RETIRED_VOCABULARY:
        if phrase.lower() in guide.lower():
            errors.append(f"{CONTRIBUTING} still refers to '{phrase}' but {reason}")

    if BLOCKED_ROADMAP not in guide:
        errors.append(
            f"{CONTRIBUTING} does not point contributors at {BLOCKED_ROADMAP}, so blocked "
            "work looks unqueued"
        )

    if errors:
        raise ContributingRoadmapError("; ".join(errors))

    return {
        "status": "ok",
        "policyKind": "contributingRoadmapConsistency",
        "schemaVersion": 1,
        "roadmapTiers": sorted(tiers),
        "documentedTiers": sorted(named_tiers),
        "templateFields": sorted(present),
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    try:
        result = validate_contributing_roadmap(repo_root)
    except ContributingRoadmapError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, indent=2, sort_keys=True))
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
