#!/usr/bin/env python3
"""Keep list-rendered models stable, and keep the compiler telling us about them.

`Wallpaper` shipped for months carrying two `List<String>` fields and no
`@Immutable`, directly beside a `Sound` that had one. It is the model in every
cell of the busiest screens in the app, so the Compose compiler treated those
cells as unstable and recomposed them whenever a parent did — and with no
compiler metrics configured, none of that was visible.

This gate holds two things in place. Every model a Compose screen renders in a
list carries a stability annotation, and the build keeps emitting the reports
that would show a regression. Both are source-level and offline: the compiler's
own report is the richer answer, but it only exists after a build, and a gate
that can only run after a build is a gate that stops running.

Exit 0 if clean, 1 if violations found.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


MODELS_SOURCE = "app/src/main/java/com/freevibe/data/model/Models.kt"
BUILD_SCRIPT = "app/build.gradle.kts"
STABILITY_CONFIG = "compose-stability.conf"
UI_SOURCE_ROOT = "app/src/main/java/com/freevibe/ui"

DATA_CLASS = re.compile(r"^data class (\w+)\(", re.MULTILINE)
STABILITY_ANNOTATION = re.compile(r"@(?:Immutable|Stable)\b")

# How a model reaches a list. `items(...)` and `itemsIndexed(...)` are the Lazy
# APIs; a typed lambda parameter is how a cell composable receives one element.
LIST_RENDER_HINTS = ("items(", "itemsIndexed(", "LazyColumn", "LazyRow", "LazyVerticalGrid")

REQUIRED_BUILD_SETTINGS = (
    "composeCompiler",
    "metricsDestination",
    "reportsDestination",
    "stabilityConfigurationFiles",
)


class ComposeStabilityError(ValueError):
    """Raised when a list-rendered model is unstable or the metrics are switched off."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate Compose stability annotations on list-rendered models.",
    )
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


def read(repo_root: Path, relative_path: str) -> str:
    path = repo_root / relative_path
    if not path.is_file():
        raise ComposeStabilityError(f"missing file: {relative_path}")
    return path.read_text(encoding="utf-8")


def declared_models(models_source: str) -> dict[str, bool]:
    """Every `data class` in Models.kt, mapped to whether it is annotated stable.

    The annotation may sit above other annotations — `@Immutable` then `@Entity`
    then the declaration — so the few lines before each class are what is
    inspected rather than only the line immediately above.
    """
    models: dict[str, bool] = {}
    lines = models_source.splitlines()
    for index, line in enumerate(lines):
        match = re.match(r"data class (\w+)[(<]", line)
        if not match:
            continue
        preamble = "\n".join(lines[max(0, index - 6): index])
        models[match.group(1)] = bool(STABILITY_ANNOTATION.search(preamble))
    return models


def models_rendered_in_lists(repo_root: Path, model_names: set[str]) -> set[str]:
    """Models named inside a file that also renders a Compose list.

    Deliberately coarse. A model named in a screen that renders lists is treated
    as list-rendered, because the cost of a wrong "yes" is one annotation and the
    cost of a wrong "no" is the defect this gate exists for.
    """
    ui_root = repo_root / UI_SOURCE_ROOT
    if not ui_root.is_dir():
        return set()
    rendered: set[str] = set()
    for source_path in sorted(ui_root.rglob("*.kt")):
        source = source_path.read_text(encoding="utf-8")
        if not any(hint in source for hint in LIST_RENDER_HINTS):
            continue
        for name in model_names:
            if re.search(rf"\b{re.escape(name)}\b", source):
                rendered.add(name)
    return rendered


def validate_compose_stability(repo_root: Path) -> dict[str, object]:
    errors: list[str] = []

    models = declared_models(read(repo_root, MODELS_SOURCE))
    if not models:
        raise ComposeStabilityError(f"no data classes found in {MODELS_SOURCE}")

    rendered = models_rendered_in_lists(repo_root, set(models))
    unstable = sorted(name for name in rendered if not models[name])
    for name in unstable:
        errors.append(
            f"{name} is rendered in a Compose list but carries no @Immutable or @Stable, "
            "so every cell holding one recomposes whenever its parent does"
        )

    build_script = read(repo_root, BUILD_SCRIPT)
    for setting in REQUIRED_BUILD_SETTINGS:
        if setting not in build_script:
            errors.append(
                f"{BUILD_SCRIPT} no longer configures {setting}; without it the compiler "
                "stops reporting which models it considers unstable"
            )

    config_path = repo_root / STABILITY_CONFIG
    if not config_path.is_file():
        errors.append(f"missing {STABILITY_CONFIG}, which the build script points at")
    elif not config_path.read_text(encoding="utf-8").strip():
        errors.append(f"{STABILITY_CONFIG} is empty")

    if errors:
        raise ComposeStabilityError("; ".join(errors))

    return {
        "status": "ok",
        "policyKind": "composeStability",
        "schemaVersion": 1,
        "declaredModelCount": len(models),
        "listRenderedModelCount": len(rendered),
        "listRenderedModels": sorted(rendered),
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    try:
        result = validate_compose_stability(repo_root)
    except ComposeStabilityError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, indent=2, sort_keys=True))
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
