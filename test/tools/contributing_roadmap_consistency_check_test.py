from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.contributing_roadmap_consistency_check import (
    CONTRIBUTING,
    ROADMAP,
    ContributingRoadmapError,
    validate_contributing_roadmap,
)


REPO_ROOT = Path(__file__).resolve().parents[2]

ROADMAP_FIXTURE = """# Aura Roadmap

- [ ] P1 — Do the thing
  Why: because
  Evidence: `some/file.kt`
  Touches: `some/file.kt`
  Acceptance: the thing is done
  Complexity: S
"""

GUIDE_FIXTURE = """# Contributing

## Roadmap

Items are grouped by priority, **P0** through **P3**.

```
- [ ] P2 — Short title
  Why: the reason
  Evidence: checkable sources
  Touches: files
  Acceptance: observable result
  Complexity: S/M/L/XL
```

Blocked work lives in Roadmap_Blocked.md.
"""


class ContributingRoadmapConsistencyCheckTest(unittest.TestCase):
    def _stage(self, guide: str, roadmap: str = ROADMAP_FIXTURE) -> Path:
        tmpdir = tempfile.TemporaryDirectory()
        self.addCleanup(tmpdir.cleanup)
        root = Path(tmpdir.name)
        (root / CONTRIBUTING).write_text(guide, encoding="utf-8")
        (root / ROADMAP).write_text(roadmap, encoding="utf-8")
        return root

    def test_live_guide_describes_the_live_roadmap(self) -> None:
        result = validate_contributing_roadmap(REPO_ROOT)

        self.assertEqual("ok", result["status"])
        self.assertIn("P0", result["roadmapTiers"])
        self.assertEqual(
            ["Acceptance", "Complexity", "Evidence", "Touches", "Why"],
            result["templateFields"],
        )

    def test_accepts_a_matching_pair(self) -> None:
        result = validate_contributing_roadmap(self._stage(GUIDE_FIXTURE))

        self.assertEqual("ok", result["status"])

    def test_rejects_a_tier_outside_the_scheme(self) -> None:
        guide = GUIDE_FIXTURE.replace("**P3**", "**P9**")

        with self.assertRaises(ContributingRoadmapError) as ctx:
            validate_contributing_roadmap(self._stage(guide))

        self.assertIn("P9", str(ctx.exception))

    def test_rejects_a_roadmap_tier_the_guide_never_documents(self) -> None:
        guide = GUIDE_FIXTURE.replace("**P0** through **P3**", "**P0** through **P1**")
        roadmap = ROADMAP_FIXTURE.replace("- [ ] P1 —", "- [ ] P3 —")

        with self.assertRaises(ContributingRoadmapError) as ctx:
            validate_contributing_roadmap(self._stage(guide, roadmap))

        self.assertIn("P3", str(ctx.exception))

    def test_an_empty_tier_is_not_a_violation(self) -> None:
        """The guide documents the scheme, not which tiers happen to be populated."""
        result = validate_contributing_roadmap(self._stage(GUIDE_FIXTURE))

        self.assertEqual("ok", result["status"])
        self.assertEqual(["P1"], result["roadmapTiers"])

    def test_rejects_retired_roadmap_vocabulary(self) -> None:
        guide = GUIDE_FIXTURE + "\nOpen issues against existing items by their ID.\n"

        with self.assertRaises(ContributingRoadmapError) as ctx:
            validate_contributing_roadmap(self._stage(guide))

        self.assertIn("by their ID", str(ctx.exception))

    def test_rejects_a_guide_that_drops_a_template_field(self) -> None:
        guide = GUIDE_FIXTURE.replace("  Evidence: checkable sources\n", "")

        with self.assertRaises(ContributingRoadmapError) as ctx:
            validate_contributing_roadmap(self._stage(guide))

        self.assertIn("Evidence", str(ctx.exception))

    def test_rejects_a_guide_that_hides_blocked_work(self) -> None:
        guide = GUIDE_FIXTURE.replace("Blocked work lives in Roadmap_Blocked.md.", "")

        with self.assertRaises(ContributingRoadmapError) as ctx:
            validate_contributing_roadmap(self._stage(guide))

        self.assertIn("Roadmap_Blocked.md", str(ctx.exception))

    def test_rejects_an_unreadable_roadmap(self) -> None:
        with self.assertRaises(ContributingRoadmapError) as ctx:
            validate_contributing_roadmap(self._stage(GUIDE_FIXTURE, "# Aura Roadmap\n"))

        self.assertIn("not reading anything", str(ctx.exception))


if __name__ == "__main__":
    unittest.main()
