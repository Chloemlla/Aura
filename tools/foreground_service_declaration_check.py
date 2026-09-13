#!/usr/bin/env python3
"""Verify foreground-service declaration packet against AndroidManifest.xml.

Checks:
  - Every manifest foreground-service permission has a reviewed declaration row.
  - Every manifest <service> with a foregroundServiceType has a declaration row.
  - Notification channels referenced in declarations exist in NotificationChannels.kt.
  - No BOOT_COMPLETED media-playback launch path exists.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET


APP_PACKAGE = "com.chloemlla.aura"
BOOT_COMPLETED_ACTION = "android.intent.action.BOOT_COMPLETED"


def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    packet_path = os.path.join(repo_root, "docs", "distribution", "foreground-service-declaration.json")
    manifest_path = os.path.join(repo_root, "app", "src", "main", "AndroidManifest.xml")
    channels_path = os.path.join(repo_root, "app", "src", "main", "java", "com", "chloemlla", "aura", "service", "NotificationChannels.kt")

    errors = []

    if not os.path.isfile(packet_path):
        errors.append(f"Missing declaration packet: {packet_path}")
        _report(errors)
        return

    with open(packet_path, "r", encoding="utf-8") as f:
        packet = json.load(f)

    if packet.get("status") != "checked":
        errors.append("Declaration packet status is not 'checked'")

    # --- Parse manifest for FGS permissions and service types ---
    manifest_fgs_permissions = set()
    manifest_fgs_services = {}

    if os.path.isfile(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest_text = f.read()

        fgs_perm_pattern = re.compile(r'android\.permission\.FOREGROUND_SERVICE(?:_[A-Z_]+)?')
        for m in fgs_perm_pattern.finditer(manifest_text):
            manifest_fgs_permissions.add(m.group(0).replace("android.permission.", ""))

        fgs_type_pattern = re.compile(r'android:foregroundServiceType="([^"]+)"')
        service_name_pattern = re.compile(r'<service[^>]*android:name="([^"]+)"[^>]*>')
        for svc_match in service_name_pattern.finditer(manifest_text):
            svc_name = svc_match.group(1)
            svc_block_start = svc_match.start()
            svc_block_end = manifest_text.find("</service>", svc_block_start)
            if svc_block_end < 0:
                svc_block_end = manifest_text.find("/>", svc_block_start)
            svc_block = manifest_text[svc_block_start:svc_block_end + 10 if svc_block_end > 0 else svc_block_start + 500]
            type_match = fgs_type_pattern.search(svc_block)
            if type_match:
                manifest_fgs_services[svc_name] = type_match.group(1)

        boot_launch = _boot_completed_media_playback_launch(
            repo_root, manifest_text, manifest_fgs_services
        )
        if boot_launch:
            errors.append(
                f"BOOT_COMPLETED receiver {boot_launch} starts the mediaPlayback "
                "foreground service — Play policy risk"
            )
    else:
        errors.append(f"Missing AndroidManifest.xml: {manifest_path}")

    # --- Parse notification channels from source ---
    declared_channels = set()
    if os.path.isfile(channels_path):
        with open(channels_path, "r", encoding="utf-8") as f:
            channels_text = f.read()
        channel_pattern = re.compile(r'const val \w+ = "([^"]+)"')
        for m in channel_pattern.finditer(channels_text):
            declared_channels.add(m.group(1))
    else:
        errors.append(f"Missing NotificationChannels.kt: {channels_path}")

    # --- Validate packet services ---
    packet_services = {s["class"]: s for s in packet.get("services", [])}
    packet_permissions = set(packet.get("permissions", {}).keys())

    for perm in manifest_fgs_permissions:
        if perm not in packet_permissions:
            errors.append(f"Manifest permission {perm} has no declaration packet row")

    for svc_name, svc_type in manifest_fgs_services.items():
        full_name = svc_name if not svc_name.startswith(".") else f"com.chloemlla.aura{svc_name}"
        if full_name not in packet_services:
            errors.append(f"Manifest service {full_name} (type={svc_type}) has no declaration packet row")
        else:
            svc_row = packet_services[full_name]
            if svc_row.get("foregroundServiceType") != svc_type:
                errors.append(f"Service {full_name} type mismatch: manifest={svc_type}, packet={svc_row.get('foregroundServiceType')}")

    for svc in packet.get("services", []):
        channel = svc.get("notificationChannel", "")
        if channel and channel not in declared_channels:
            errors.append(f"Service {svc['class']} references channel '{channel}' not found in NotificationChannels.kt")
        if not svc.get("trigger"):
            errors.append(f"Service {svc['class']} is missing trigger description")
        if not svc.get("whyNotDeferred"):
            errors.append(f"Service {svc['class']} is missing whyNotDeferred justification")
        if not svc.get("demoVideoSteps"):
            errors.append(f"Service {svc['class']} is missing demoVideoSteps")

    for ch in packet.get("notificationChannels", []):
        if ch["id"] not in declared_channels:
            errors.append(f"Packet channel '{ch['id']}' not found in NotificationChannels.kt")

    _report(errors)


def _receiver_blocks(manifest_text):
    """(android:name, block) for every <receiver>, paired or self-closing."""
    for match in re.finditer(r"<receiver\b[^>]*?(/?)>", manifest_text):
        block_end = match.end()
        if match.group(1) != "/":
            close = manifest_text.find("</receiver>", match.end())
            if close >= 0:
                block_end = close + len("</receiver>")
        block = manifest_text[match.start():block_end]
        name = re.search(r'android:name="([^"]+)"', block)
        yield (name.group(1) if name else ""), block


def _receiver_source(source_root, receiver_name):
    """Source of a receiver this app owns, or None for one it does not.

    Component names are written relative to the app package (".service.Foo"),
    fully qualified, or belong to a library — the last is not ours to read.
    """
    if receiver_name.startswith("."):
        qualified = APP_PACKAGE + receiver_name
    elif receiver_name.startswith(APP_PACKAGE + "."):
        qualified = receiver_name
    else:
        return None
    path = os.path.join(source_root, qualified.replace(".", os.sep) + ".kt")
    if not os.path.isfile(path):
        return None
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def _boot_completed_media_playback_launch(repo_root, manifest_text, fgs_services):
    """The receiver that turns the boot broadcast into a mediaPlayback FGS, if any.

    A <receiver> cannot declare which service it starts, so the manifest alone
    cannot answer this — searching it for "BOOT_COMPLETED" near "mediaPlayback"
    only ever finds the RECEIVE_BOOT_COMPLETED permission next to an unrelated
    service type, which every manifest that has both would report. The check
    instead follows each boot receiver into its own source and looks for the
    class the manifest typed as mediaPlayback; an app whose boot receiver
    enqueues a worker (the normal shape) is clear.
    """
    media_playback = [
        name for name, svc_type in fgs_services.items()
        if "mediaPlayback" in svc_type.split("|")
    ]
    if not media_playback:
        return None
    source_root = os.path.join(repo_root, "app", "src", "main", "java")
    for receiver_name, block in _receiver_blocks(manifest_text):
        if not receiver_name or BOOT_COMPLETED_ACTION not in block:
            continue
        body = _receiver_source(source_root, receiver_name)
        if body is None:
            continue
        for service in media_playback:
            simple_name = service.rsplit(".", 1)[-1]
            if re.search(rf"\b{re.escape(simple_name)}\b", body):
                return receiver_name
    return None


def _report(errors):
    if errors:
        print(f"FAIL: {len(errors)} issue(s)")
        for e in errors:
            print(f"  - {e}")
        sys.exit(1)
    else:
        print("OK: foreground-service declaration packet is consistent")
        sys.exit(0)


if __name__ == "__main__":
    main()
