#!/usr/bin/env python3
"""Keep live-wallpaper preference bridges in the data layer, in the right order.

WeatherWallpaperService and VideoWallpaperService read their settings from
SharedPreferences only — a WallpaperService cannot practically subscribe to
DataStore. Anything that writes both stores must therefore write SharedPreferences
first: if the suspending DataStore write is cancelled between the two, the
runtime keeps the old value while the UI reads as changed.

SettingsViewModel used to own five of these bridges and wrote DataStore first,
so backing out of Settings mid-write stranded the live wallpaper permanently.
The bridges now live in PreferencesManager, which is what this gate holds in
place.

Exit 0 if clean, 1 if violations found.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


PREFERENCES_MANAGER = "app/src/main/java/com/chloemlla/aura/data/local/PreferencesManager.kt"
SETTINGS_VIEW_MODEL = "app/src/main/java/com/chloemlla/aura/ui/screens/settings/SettingsViewModel.kt"
UI_SOURCE_ROOT = "app/src/main/java/com/chloemlla/aura/ui"

SHARED_PREF_WRITE = re.compile(
    r"(writeLiveWallpaperFlags?|writeAllLiveWallpaperFlags|weatherWallpaperPrefs\(\)"
    r"|getSharedPreferences)"
)
UI_SHARED_PREF_WRITE = re.compile(
    r"getSharedPreferences[\s\S]{0,300}?\.edit\s*\(\s*\)",
)
DATASTORE_WRITE = re.compile(r"\bset\(Keys\.")
FUNCTION_DECLARATION = re.compile(r"(?:suspend\s+)?fun\s+([A-Za-z_]\w*)\s*\(")

# An expression body ends where the next declaration begins. Without this, a
# one-line `fun a() = set(Keys.X, v)` sitting directly above a block-bodied
# bridge absorbs that bridge's body and is misreported as a wrong-order bridge.
DECLARATION_START = re.compile(
    r"\s*(?:\}|@\w|/\*|\*|//"
    r"|(?:(?:public|private|internal|protected|suspend|inline|override|open)\s+)*"
    r"(?:fun|val|var|class|object|companion|init)\b)"
)

# Bridges that must keep bridging. Discovery below catches any *new* bridge that
# writes in the wrong order; this floor catches the opposite regression — an
# existing bridge that is renamed or quietly stops writing SharedPreferences,
# which discovery alone cannot see because it stops being a bridge at all.
REQUIRED_BRIDGES = (
    "setLiveWallpaperDimEnabled",
    "setLiveWallpaperColorsEnabled",
    "setAdaptiveTintEnabled",
    "setAdaptiveTintIntensity",
    "setReduceAnimations",
    "setLiveWallpaperShaderPreset",
    "setVideoFpsLimit",
    "setVideoPlaybackSpeed",
    "setVideoFpsOverlayEnabled",
    "setVideoAutoBatterySaver",
)


class PreferenceWriteOrderError(ValueError):
    """Raised when a preference bridge is misplaced or writes in the wrong order."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Validate live-wallpaper preference bridges live in PreferencesManager "
        "and write SharedPreferences before DataStore.",
    )
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


def read(repo_root: Path, relative_path: str) -> str:
    path = repo_root / relative_path
    if not path.is_file():
        raise PreferenceWriteOrderError(f"missing file: {relative_path}")
    return path.read_text(encoding="utf-8")


def extract_function_body(text: str, name: str) -> str:
    """Return the body of `fun name(...)`, whether block- or expression-bodied."""
    match = re.search(rf"fun {re.escape(name)}\s*\(", text)
    if not match:
        raise PreferenceWriteOrderError(f"PreferencesManager no longer declares {name}")
    cursor = match.end()
    depth = 1
    while cursor < len(text) and depth:
        if text[cursor] == "(":
            depth += 1
        elif text[cursor] == ")":
            depth -= 1
        cursor += 1
    rest = text[cursor:]
    brace = rest.find("{")
    equals = rest.find("=")
    if brace == -1 or (equals != -1 and equals < brace):
        lines = rest.split("\n")
        body = [lines[0]]
        for line in lines[1:]:
            if not line.strip() or DECLARATION_START.match(line):
                break
            body.append(line)
        return "\n".join(body)
    depth = 0
    for position in range(brace, len(rest)):
        if rest[position] == "{":
            depth += 1
        elif rest[position] == "}":
            depth -= 1
            if depth == 0:
                return rest[brace : position + 1]
    raise PreferenceWriteOrderError(f"could not parse the body of {name}")


def discover_bridge_functions(manager: str) -> tuple[str, ...]:
    """Find every PreferencesManager function that bridges both preference stores."""
    bridges: list[str] = []
    for match in FUNCTION_DECLARATION.finditer(manager):
        name = match.group(1)
        try:
            body = extract_function_body(manager, name)
        except PreferenceWriteOrderError:
            continue
        if SHARED_PREF_WRITE.search(body) and DATASTORE_WRITE.search(body):
            bridges.append(name)
    return tuple(bridges)


def validate_preference_write_order(repo_root: Path) -> dict[str, object]:
    manager = read(repo_root, PREFERENCES_MANAGER)
    view_model = read(repo_root, SETTINGS_VIEW_MODEL)

    errors: list[str] = []

    if "getSharedPreferences" in view_model:
        errors.append(
            "SettingsViewModel touches SharedPreferences directly; live-wallpaper bridges "
            "belong in PreferencesManager so the write order is enforced in one place"
        )

    ui_root = repo_root / UI_SOURCE_ROOT
    ui_sources = sorted(ui_root.rglob("*.kt")) if ui_root.is_dir() else ()
    for source_path in ui_sources:
        source = source_path.read_text(encoding="utf-8")
        if "getSharedPreferences" in source:
            errors.append(
                f"{source_path.relative_to(repo_root)} touches SharedPreferences directly; "
                "route persistence through the data or service layer"
            )

    discovered = discover_bridge_functions(manager)
    for name in REQUIRED_BRIDGES:
        if name in discovered:
            continue
        # Distinguish "renamed or deleted" from "still declared but stopped bridging".
        extract_function_body(manager, name)
        errors.append(f"{name} no longer writes SharedPreferences for the live-wallpaper runtime")

    checked = 0
    for name in discovered:
        body = extract_function_body(manager, name)
        shared = SHARED_PREF_WRITE.search(body)
        datastore = DATASTORE_WRITE.search(body)
        if not shared:
            errors.append(f"{name} no longer writes SharedPreferences for the live-wallpaper runtime")
            continue
        if not datastore:
            errors.append(f"{name} no longer writes DataStore")
            continue
        if shared.start() > datastore.start():
            errors.append(
                f"{name} writes DataStore before SharedPreferences; a cancelled coroutine "
                "would strand the live wallpaper on the old value"
            )
        checked += 1

    if errors:
        raise PreferenceWriteOrderError("; ".join(errors))

    return {
        "status": "ok",
        "policyKind": "preferenceWriteOrder",
        "schemaVersion": 1,
        "bridgeCount": checked,
    }


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root).resolve()
    try:
        result = validate_preference_write_order(repo_root)
    except PreferenceWriteOrderError as exc:
        print(json.dumps({"status": "fail", "error": str(exc)}, indent=2, sort_keys=True))
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
