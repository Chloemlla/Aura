#!/usr/bin/env python3
"""Report Aura's current F-Droid mainline readiness without building APKs."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP_GRADLE = ROOT / "app" / "build.gradle.kts"
SETTINGS_GRADLE = ROOT / "settings.gradle.kts"
YTDLP_MANAGER = ROOT / "app" / "src" / "main" / "java" / "com" / "freevibe" / "service" / "YtDlpUpdateManager.kt"
YTDLP_SETTINGS = ROOT / "app" / "src" / "main" / "java" / "com" / "freevibe" / "ui" / "screens" / "settings" / "SettingsSoundSection.kt"
STRINGS_XML = ROOT / "app" / "src" / "main" / "res" / "values" / "strings.xml"
ROOT_NAVIGATION = ROOT / "app" / "src" / "main" / "java" / "com" / "freevibe" / "ui" / "FreeVibeRoot.kt"
WALLPAPERS_SCREEN = ROOT / "app" / "src" / "main" / "java" / "com" / "freevibe" / "ui" / "screens" / "wallpapers" / "WallpapersScreen.kt"
SERVICES_SCREEN = ROOT / "app" / "src" / "main" / "java" / "com" / "freevibe" / "ui" / "screens" / "settings" / "SettingsServicesSection.kt"
FULL_GENERATED_ROUTE = ROOT / "app" / "src" / "full" / "java" / "com" / "freevibe" / "ui" / "GeneratedWallpaperRoute.kt"
FOSS_GENERATED_ROUTE = ROOT / "app" / "src" / "foss" / "java" / "com" / "freevibe" / "ui" / "GeneratedWallpaperRoute.kt"
FULL_GENERATED_SETTINGS = ROOT / "app" / "src" / "full" / "java" / "com" / "freevibe" / "ui" / "screens" / "settings" / "GeneratedWallpaperProviderSettings.kt"
FOSS_GENERATED_SETTINGS = ROOT / "app" / "src" / "foss" / "java" / "com" / "freevibe" / "ui" / "screens" / "settings" / "GeneratedWallpaperProviderSettings.kt"
FULL_STRINGS_XML = ROOT / "app" / "src" / "full" / "res" / "values" / "strings.xml"


@dataclass(frozen=True)
class Finding:
    file: str
    line: int
    label: str
    text: str


BLOCKERS = (
    (
        "Google Services Gradle plugin",
        re.compile(r"com\.google\.gms\.google-services"),
    ),
    (
        "Firebase dependency",
        re.compile(r"com\.google\.firebase:firebase-[A-Za-z0-9_.-]+|firebase-bom"),
    ),
    (
        "Google Play Services dependency",
        re.compile(r"com\.google\.android\.gms:play-services-[A-Za-z0-9_.-]+"),
    ),
)

FOSS_ONLY_STABILITY_FILES = (
    "app/src/full/java/com/chloemlla/aura/config/BuildFlavorConfig.kt",
    "app/src/full/java/com/chloemlla/aura/data/remote/stability/StabilityAiApi.kt",
    "app/src/full/java/com/chloemlla/aura/data/legal/GeneratedWallpaperProviderDisclosure.kt",
    "app/src/full/java/com/chloemlla/aura/data/local/GeneratedWallpaperCredentialBinding.kt",
    "app/src/full/java/com/chloemlla/aura/data/repository/StabilityGeneratedWallpaperBackend.kt",
    "app/src/full/java/com/chloemlla/aura/di/GeneratedWallpaperModule.kt",
    "app/src/full/java/com/chloemlla/aura/ui/GeneratedWallpaperRoute.kt",
    "app/src/full/java/com/chloemlla/aura/ui/screens/aigenerate/AiWallpaperScreen.kt",
    "app/src/full/java/com/chloemlla/aura/ui/screens/aigenerate/AiWallpaperViewModel.kt",
    "app/src/full/java/com/chloemlla/aura/ui/screens/aigenerate/GeneratedWallpaperCommunityUploadDialog.kt",
    "app/src/full/java/com/chloemlla/aura/ui/screens/settings/GeneratedWallpaperProviderSettings.kt",
    "app/src/full/res/values/strings.xml",
)

FOSS_PROVIDER_SHIM_FILES = (
    "app/src/foss/java/com/chloemlla/aura/data/legal/GeneratedWallpaperProviderDisclosure.kt",
    "app/src/foss/java/com/chloemlla/aura/data/local/GeneratedWallpaperCredentialBinding.kt",
    "app/src/foss/java/com/chloemlla/aura/ui/GeneratedWallpaperRoute.kt",
    "app/src/foss/java/com/chloemlla/aura/ui/screens/settings/GeneratedWallpaperProviderSettings.kt",
)

PROHIBITED_MAIN_STABILITY_FILES = (
    "app/src/main/java/com/chloemlla/aura/data/remote/stability/StabilityAiApi.kt",
    "app/src/main/java/com/chloemlla/aura/ui/screens/aigenerate/AiWallpaperScreen.kt",
    "app/src/main/java/com/chloemlla/aura/ui/screens/aigenerate/AiWallpaperViewModel.kt",
    "app/src/main/java/com/chloemlla/aura/ui/screens/aigenerate/GeneratedWallpaperCommunityUploadDialog.kt",
)


def read_lines(path: Path) -> list[str]:
    if not path.exists():
        return []
    return path.read_text(encoding="utf-8").splitlines()


def scan_blockers(path: Path) -> list[Finding]:
    findings: list[Finding] = []
    for line_no, line in enumerate(read_lines(path), start=1):
        stripped = line.strip()
        if stripped.startswith("//") or stripped.startswith("#"):
            continue
        if stripped.startswith(
            (
                "fullImplementation(",
                "fullDebugImplementation(",
                "fullReleaseImplementation(",
                "debugFullImplementation(",
                'add("fullImplementation"',
                'add("fullDebugImplementation"',
                'add("fullReleaseImplementation"',
                'add("debugFullImplementation"',
            )
        ):
            continue
        for label, pattern in BLOCKERS:
            if pattern.search(line):
                findings.append(
                    Finding(
                        file=str(path.relative_to(ROOT)).replace("\\", "/"),
                        line=line_no,
                        label=label,
                        text=stripped,
                    )
                )
    return findings


def has_product_flavors(path: Path) -> bool:
    return any("productFlavors" in line for line in read_lines(path))


def has_foss_flavor(path: Path) -> bool:
    return any('create("foss")' in line or "create('foss')" in line for line in read_lines(path))


def scan_foss_stability_boundary() -> list[Finding]:
    findings: list[Finding] = []
    gradle_lines = read_lines(APP_GRADLE)
    full_line = next(
        (index for index, line in enumerate(gradle_lines) if 'create("full")' in line or "create('full')" in line),
        None,
    )
    foss_line = next(
        (index for index, line in enumerate(gradle_lines) if 'create("foss")' in line or "create('foss')" in line),
        None,
    )
    key_lines = [
        (index, line)
        for index, line in enumerate(gradle_lines)
        if "STABILITY_AI_KEY" in line
    ]
    if key_lines and (full_line is None or foss_line is None or any(
        not (full_line <= index < foss_line) for index, _ in key_lines
    )):
        line_no, _ = key_lines[0]
        findings.append(
            Finding(
                file="app/build.gradle.kts",
                line=line_no + 1,
                label="Stability FOSS boundary",
                text="STABILITY_AI_KEY must be declared only inside the full flavor",
            )
        )

    for relative in FOSS_ONLY_STABILITY_FILES:
        path = ROOT / relative
        if not path.exists():
            findings.append(
                Finding(
                    file=relative,
                    line=1,
                    label="Stability FOSS boundary",
                    text="Stability implementation must live in the full source set",
                )
            )

    for relative in PROHIBITED_MAIN_STABILITY_FILES:
        path = ROOT / relative
        if path.exists():
            findings.append(
                Finding(
                    file=relative,
                    line=1,
                    label="Stability FOSS boundary",
                    text="Provider implementation and UI must not be compiled from the main source set",
                )
            )

    for relative in FOSS_PROVIDER_SHIM_FILES:
        if not (ROOT / relative).is_file():
            findings.append(
                Finding(
                    file=relative,
                    line=1,
                    label="Stability FOSS boundary",
                    text="FOSS provider-neutral shim is missing",
                )
            )

    main_root = ROOT / "app" / "src" / "main"
    if main_root.exists():
        for pattern in ("*.kt", "*.xml", "*.txt"):
            for path in main_root.rglob(pattern):
                for line_no, line in enumerate(read_lines(path), start=1):
                    if "stability" in line.lower():
                        findings.append(
                            Finding(
                                file=str(path.relative_to(ROOT)).replace("\\", "/"),
                                line=line_no,
                                label="Stability FOSS boundary",
                                text="Provider-specific code, resources, and profile metadata must live in the full source set",
                            )
                        )

    foss_root = ROOT / "app" / "src" / "foss"
    if foss_root.exists():
        for path in foss_root.rglob("*.kt"):
            for line_no, line in enumerate(read_lines(path), start=1):
                if any(marker in line.lower() for marker in ("stability", "aiwallpaperscreen", "stability_ai_key")):
                    findings.append(
                        Finding(
                            file=str(path.relative_to(ROOT)).replace("\\", "/"),
                            line=line_no,
                            label="Stability FOSS boundary",
                            text=line.strip(),
                        )
                    )

    required_contracts = (
        (
            ROOT_NAVIGATION,
            (
                "generatedWallpaperRoute(navController)",
            ),
            "Main navigation must delegate the generated-wallpaper route to the flavor source set",
        ),
        (
            FULL_GENERATED_ROUTE,
            (
                "composable(Screen.AiWallpaper.route)",
                "AiWallpaperScreen(",
            ),
            "Full navigation must register the generated-wallpaper route",
        ),
        (
            FOSS_GENERATED_ROUTE,
            (
                "generatedWallpaperRoute",
                "= Unit",
            ),
            "FOSS navigation must provide a no-op generated-wallpaper route",
        ),
        (
            WALLPAPERS_SCREEN,
            (
                "!BuildConfig.FOSS_BUILD && generatedContentProviderEnabled",
                "if (showGeneratedContentEntry)",
            ),
            "FOSS wallpaper actions must omit the AI generation entry point",
        ),
        (
            SERVICES_SCREEN,
            (
                "GeneratedWallpaperProviderSettings(",
                "providerKey = generatedWallpaperProviderKey",
            ),
            "Main settings must delegate generated-provider controls to the flavor source set",
        ),
        (
            FULL_GENERATED_SETTINGS,
            (
                "settings_services_stability_key_title",
                "GeneratedWallpaperDisclosureDialog(",
                "setGeneratedWallpaperProviderKey",
            ),
            "Full settings must expose generated-provider controls",
        ),
    )
    for path, markers, message in required_contracts:
        source = "\n".join(read_lines(path))
        missing = [marker for marker in markers if marker not in source]
        if missing:
            findings.append(
                Finding(
                    file=str(path.relative_to(ROOT)).replace("\\", "/"),
                    line=1,
                    label="Stability FOSS boundary",
                    text=f"{message}; missing marker(s): {', '.join(missing)}",
                )
            )

    forbidden_contracts = (
        (
            ROOT_NAVIGATION,
            ("AiWallpaperScreen", "composable(Screen.AiWallpaper.route)", "BuildConfig.FOSS_BUILD"),
            "Main navigation must not compile the generated-provider destination",
        ),
        (
            SERVICES_SCREEN,
            ("BuildConfig.FOSS_BUILD", "showStabilityKey", "settings_services_stability", "screens.aigenerate"),
            "Main settings must not compile generated-provider controls",
        ),
        (
            FOSS_GENERATED_ROUTE,
            ("AiWallpaperScreen", "Screen.AiWallpaper", "Stability"),
            "FOSS navigation must not reference generated-provider UI",
        ),
        (
            FOSS_GENERATED_SETTINGS,
            ("Stability", "settings_services_generated", "settings_services_stability", "GeneratedWallpaperDisclosureDialog"),
            "FOSS settings must not reference generated-provider UI",
        ),
        (
            STRINGS_XML,
            ("Stability", '<string name="ai_', '<string name="settings_services_generated', '<string name="settings_services_stability'),
            "Provider UI strings must live in full-only resources",
        ),
    )
    for path, markers, message in forbidden_contracts:
        source = "\n".join(read_lines(path))
        present = [marker for marker in markers if marker in source]
        if present:
            findings.append(
                Finding(
                    file=str(path.relative_to(ROOT)).replace("\\", "/"),
                    line=1,
                    label="Stability FOSS boundary",
                    text=f"{message}; forbidden marker(s): {', '.join(present)}",
                )
            )
    return findings


def scan_binary_update_consent() -> list[Finding]:
    findings: list[Finding] = []
    manager = "\n".join(read_lines(YTDLP_MANAGER))
    required_manager_markers = (
        "YtDlpUpdateConsent",
        "consent: YtDlpUpdateConsent",
        "REPOSITORY_CHECKS_BYPASS_CONFIRMED",
    )
    for marker in required_manager_markers:
        if marker not in manager:
            findings.append(
                Finding(
                    file=str(YTDLP_MANAGER.relative_to(ROOT)).replace("\\", "/"),
                    line=1,
                    label="Runtime binary update consent",
                    text=f"Missing update consent marker: {marker}",
                )
            )

    settings = "\n".join(read_lines(YTDLP_SETTINGS))
    required_settings_markers = (
        "showYtDlpConsent",
        "AlertDialog",
        "YtDlpUpdateConsent.REPOSITORY_CHECKS_BYPASS_CONFIRMED",
    )
    for marker in required_settings_markers:
        if marker not in settings:
            findings.append(
                Finding(
                    file=str(YTDLP_SETTINGS.relative_to(ROOT)).replace("\\", "/"),
                    line=1,
                    label="Runtime binary update consent",
                    text=f"Missing confirmation UI marker: {marker}",
                )
            )

    strings = "\n".join(read_lines(STRINGS_XML))
    for marker in (
        "settings_ytdlp_consent_body",
        "settings_ytdlp_consent_warning",
        "F-Droid",
        "repository",
    ):
        if marker not in strings:
            findings.append(
                Finding(
                    file=str(STRINGS_XML.relative_to(ROOT)).replace("\\", "/"),
                    line=1,
                    label="Runtime binary update consent",
                    text=f"Missing user-facing update warning marker: {marker}",
                )
            )
    return findings


def analyze() -> dict[str, object]:
    boundary_findings = scan_foss_stability_boundary()
    consent_findings = scan_binary_update_consent()
    blockers = scan_blockers(APP_GRADLE) + boundary_findings + consent_findings
    product_flavors = has_product_flavors(APP_GRADLE)
    foss_flavor = has_foss_flavor(APP_GRADLE)
    status = "blocked" if blockers or not product_flavors or not foss_flavor else "ready-for-review"
    notes: list[str] = []

    if not product_flavors:
        notes.append("No productFlavors block found; Aura currently has one full-feature app variant.")
    elif not foss_flavor:
        notes.append("No foss product flavor found; F-Droid mainline needs a Firebase-free build target.")
    if blockers:
        if scan_blockers(APP_GRADLE):
            notes.append("FOSS-active Gradle configuration includes Firebase and/or Google Play Services markers.")
        if boundary_findings:
            notes.append("Stability AI code or its key is not isolated to the full source set.")
        if consent_findings:
            notes.append("Runtime yt-dlp replacement downloads are missing an explicit repository-bypass confirmation.")
    if status == "blocked":
        notes.append("Do not open an F-Droid mainline metadata PR until these blockers are removed or isolated.")
    else:
        notes.append("FOSS flavor boundary is present, Stability AI is full-only, and Firebase/Play Services dependencies are isolated to full-only configurations.")
        notes.append("Runtime yt-dlp replacement downloads require an explicit repository-bypass confirmation.")

    return {
        "status": status,
        "decision": "full-only-for-now" if status == "blocked" else "foss-review-ready",
        "productFlavors": product_flavors,
        "fossFlavor": foss_flavor,
        "blockers": [asdict(item) for item in blockers],
        "stabilityFossBoundary": not boundary_findings,
        "binaryUpdateConsent": not consent_findings,
        "notes": notes,
        "scanned": [
            str(APP_GRADLE.relative_to(ROOT)).replace("\\", "/"),
            str(SETTINGS_GRADLE.relative_to(ROOT)).replace("\\", "/"),
        ],
    }


def print_text(report: dict[str, object]) -> None:
    print(f"F-Droid mainline status: {report['status']}")
    print(f"Distribution decision: {report['decision']}")
    for note in report["notes"]:
        print(f"- {note}")
    blockers = report["blockers"]
    if blockers:
        print("\nBlockers:")
        for item in blockers:
            print(f"- {item['file']}:{item['line']} [{item['label']}] {item['text']}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--json", action="store_true", help="Print machine-readable JSON.")
    parser.add_argument(
        "--expect-blocked",
        action="store_true",
        help="Exit 0 only when the current tree is blocked for F-Droid mainline.",
    )
    parser.add_argument(
        "--expect-pass",
        action="store_true",
        help="Exit 0 only when the current tree has a FOSS-ready flavor boundary.",
    )
    args = parser.parse_args()

    report = analyze()
    if args.json:
        print(json.dumps(report, indent=2, sort_keys=True))
    else:
        print_text(report)

    blocked = report["status"] == "blocked"
    if args.expect_blocked:
        return 0 if blocked else 1
    if args.expect_pass:
        return 0 if not blocked else 1
    return 2 if blocked else 0


if __name__ == "__main__":
    raise SystemExit(main())
