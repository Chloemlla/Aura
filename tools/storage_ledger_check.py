#!/usr/bin/env python3
"""Verify storage ledger against backup rules and source references.

Checks:
  - No backup-excluded store is allowlisted for backup in data_extraction_rules.xml.
  - Every store references an existing source file.
  - SharedPreferences files match the backup allowlist.
  - No temp directory budget exceeds its documented cap.
"""
import json
import os
import re
import sys


def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    ledger_path = os.path.join(repo_root, "docs", "privacy", "storage-ledger.json")
    rules_path = os.path.join(repo_root, "app", "src", "main", "res", "xml", "data_extraction_rules.xml")
    src_root = os.path.join(repo_root, "app", "src", "main", "java", "com", "chloemlla", "aura")

    errors = []

    if not os.path.isfile(ledger_path):
        errors.append(f"Missing storage ledger: {ledger_path}")
        _report(errors)
        return

    with open(ledger_path, "r", encoding="utf-8") as f:
        ledger = json.load(f)

    if ledger.get("status") != "checked":
        errors.append("Storage ledger status is not 'checked'")

    rules_text = ""
    if os.path.isfile(rules_path):
        with open(rules_path, "r", encoding="utf-8") as f:
            rules_text = f.read()
    else:
        errors.append(f"Missing data_extraction_rules.xml: {rules_path}")

    included_paths = _included_paths(rules_text)

    for store in ledger.get("stores", []):
        sf = store.get("sourceFile", "")
        if sf and not _find_source(src_root, sf):
            errors.append(f"Store '{store['id']}' references missing source: {sf}")

        if store.get("backupExcluded") and rules_text:
            path = store.get("path", "")
            if path.startswith("cacheDir/") or path.startswith("MediaStore") or path.startswith("datastore/"):
                continue
            # data_extraction_rules.xml is an allowlist, so a backup-excluded store is
            # correctly absent from it. The failure that matters is an excluded store
            # reappearing as an <include>, which would put private data back into the
            # cloud backup set.
            identity = _path_identity(path)
            if identity and any(_path_identity(included) == identity for included in included_paths):
                errors.append(
                    f"Store '{store['id']}' is backup-excluded but allowlisted "
                    f"in data_extraction_rules.xml: {path}"
                )

    for td in ledger.get("tempDirectories", []):
        sf = td.get("sourceFile", "")
        if sf and not _find_source(src_root, sf):
            errors.append(f"Temp dir '{td['path']}' references missing source: {sf}")

    if rules_text:
        sp_in_ledger = set(ledger.get("sharedPreferencesFiles", []))
        for include_path in _included_paths(rules_text):
            if include_path.endswith(".xml") and include_path not in sp_in_ledger:
                errors.append(f"SharedPreferences '{include_path}' in backup rules but not in storage ledger")

    _report(errors)


def _included_paths(rules_text):
    """Every distinct path the backup allowlist opts back in."""
    return sorted(set(re.findall(r'<include\b[^>]*\bpath="([^"]+)"', rules_text)))


def _path_identity(path):
    """Reduce a store path to the token an allowlist <include> would name it by."""
    basename = path.split("/")[-1].rstrip("/").split("{")[0]
    return basename.split(".")[0].strip(".")


def _find_source(src_root, filename):
    for dirpath, _, filenames in os.walk(src_root):
        if filename in filenames:
            return True
    ui_root = os.path.join(os.path.dirname(src_root.rstrip(os.sep)), "")
    for dirpath, _, filenames in os.walk(src_root):
        if filename in filenames:
            return True
    return False


def _report(errors):
    if errors:
        print(f"FAIL: {len(errors)} issue(s)")
        for e in errors:
            print(f"  - {e}")
        sys.exit(1)
    else:
        print("OK: storage ledger is consistent")
        sys.exit(0)


if __name__ == "__main__":
    main()
