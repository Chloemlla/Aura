#!/usr/bin/env python3
"""Validate Aura's provider credential storage policy."""

from __future__ import annotations

import argparse
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

REQUIRED_CREDENTIAL_FIELDS = {
    "id",
    "provider",
    "classification",
    "storage",
    "preferenceKey",
    "buildConfigField",
    "gradleProperty",
    "settingsLabel",
    "releaseDefault",
    "atRestPolicy",
    "backupPolicy",
    "restorePolicy",
    "deletionPolicy",
    "exportPolicy",
    "userControl",
    "redactionTerms",
}
SUPPORTED_CLASSIFICATIONS = {"optionalQuotaKey", "publicClientId", "paidSensitiveSecret"}
SUPPORTED_STORAGE = {"encryptedSharedPreferences", "buildConfigOnly"}
STABILITY_CREDENTIAL_ID = "stability-ai-key"
STABILITY_REQUIREMENTS = {
    "provider": "Stability AI",
    "classification": "paidSensitiveSecret",
    "storage": "encryptedSharedPreferences",
    "preferenceKey": "stability_ai_key",
    "buildConfigField": "STABILITY_AI_KEY",
    "gradleProperty": "stability.ai.key",
    "settingsLabel": "Stability AI API Key",
}


class ProviderCredentialStorageError(ValueError):
    """Raised when the provider credential storage policy is stale."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate Aura provider credential storage policy.")
    parser.add_argument("--policy", default="docs/security/provider-credential-storage.json")
    parser.add_argument("--repo-root", default=".")
    return parser.parse_args()


def read_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def require_object(value: Any, label: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise ProviderCredentialStorageError(f"{label} must be a JSON object")
    return value


def require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ProviderCredentialStorageError(f"{label} must be a non-empty string")
    return value.strip()


def require_nullable_string(value: Any, label: str) -> str | None:
    if value is None:
        return None
    return require_string(value, label)


def require_string_list(value: Any, label: str) -> list[str]:
    if not isinstance(value, list) or not value:
        raise ProviderCredentialStorageError(f"{label} must be a non-empty list")
    values = [require_string(item, f"{label}[{index}]") for index, item in enumerate(value)]
    if len(values) != len(set(values)):
        raise ProviderCredentialStorageError(f"{label} contains duplicate values")
    return values


def read_text(repo_root: Path, relative_path: str) -> str:
    path = repo_root / relative_path
    if not path.is_file():
        raise ProviderCredentialStorageError(f"required file is missing: {relative_path}")
    return path.read_text(encoding="utf-8")


def read_settings_surface_text(repo_root: Path, relative_path: str) -> str:
    path = repo_root / relative_path
    text = read_text(repo_root, relative_path)
    if path.name != "SettingsScreen.kt":
        return text
    settings_dirs = (
        path.parent,
        repo_root / "app/src/full/java/com/chloemlla/aura/ui/screens/settings",
    )
    section_text = "\n".join(
        section.read_text(encoding="utf-8")
        for settings_dir in settings_dirs
        if settings_dir.is_dir()
        for section in sorted(settings_dir.glob("*.kt"))
        if section.is_file() and section != path
    )
    strings_text = "\n".join(
        strings_path.read_text(encoding="utf-8")
        for strings_path in (
            repo_root / "app/src/main/res/values/strings.xml",
            repo_root / "app/src/full/res/values/strings.xml",
        )
        if strings_path.is_file()
    )
    return f"{text}\n{section_text}\n{strings_text}"


def read_preferences_surface_text(repo_root: Path, relative_path: str) -> str:
    sources = [read_text(repo_root, relative_path)]
    full_binding = repo_root / "app/src/full/java/com/chloemlla/aura/data/local/GeneratedWallpaperCredentialBinding.kt"
    if full_binding.is_file():
        sources.append(full_binding.read_text(encoding="utf-8"))
    return "\n".join(sources)


def parse_backup_scopes(xml_text: str, label: str) -> dict[str, ET.Element]:
    """Return the rule element backing each backup section of a rules file."""
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise ProviderCredentialStorageError(f"{label} is not well-formed XML: {exc}") from exc
    if root.tag == "full-backup-content":
        return {"legacy": root}
    if root.tag == "data-extraction-rules":
        cloud = root.find("cloud-backup")
        transfer = root.find("device-transfer")
        if cloud is None or transfer is None:
            raise ProviderCredentialStorageError(f"{label} must cover cloud backup and device transfer")
        return {"cloud": cloud, "deviceTransfer": transfer}
    raise ProviderCredentialStorageError(f"{label} has unsupported root element {root.tag}")


def scope_rules(scope: ET.Element, tag: str) -> list[tuple[str, str]]:
    rules: list[tuple[str, str]] = []
    for element in scope:
        if element.tag != tag:
            continue
        domain = (element.get("domain") or "").strip()
        path = (element.get("path") or "").strip()
        if domain and path:
            rules.append((domain, path))
    return rules


def rule_covers_path(rule_path: str, target_path: str) -> bool:
    if rule_path == "." or rule_path == target_path:
        return True
    return target_path.startswith(rule_path.rstrip("/") + "/")


def scope_excludes_path(scope: ET.Element, domain: str, path: str) -> bool:
    """A path is excluded by an <exclude> that covers it, or by a scope that lists
    <include> rules for other paths only: any <include> turns the section into an
    allowlist, so everything it does not name stays off the backup."""
    if any(
        rule_domain == domain and rule_covers_path(rule_path, path)
        for rule_domain, rule_path in scope_rules(scope, "exclude")
    ):
        return True
    includes = scope_rules(scope, "include")
    if not includes:
        return False
    return not any(
        rule_domain == domain and rule_covers_path(rule_path, path)
        for rule_domain, rule_path in includes
    )


def validate_backup_exclusion(xml_text: str, domain: str, path: str, label: str) -> None:
    for section, scope in parse_backup_scopes(xml_text, label).items():
        if not scope_excludes_path(scope, domain, path):
            raise ProviderCredentialStorageError(
                f"{label} {section} must not back up {domain}:{path}: "
                f"exclude it, or use <include> elements that do not cover it"
            )


def validate_gradle_default(app_gradle_text: str, credential: dict[str, Any]) -> None:
    build_config = credential["buildConfigField"]
    gradle_property = credential["gradleProperty"]
    if not build_config:
        return
    if f'buildConfigField("String", "{build_config}"' not in app_gradle_text:
        raise ProviderCredentialStorageError(f"app Gradle is missing BuildConfig field {build_config}")
    expected = f'localProps.getProperty("{gradle_property}", "")'
    if expected not in app_gradle_text:
        raise ProviderCredentialStorageError(f"app Gradle must default {gradle_property} to blank")


def validate_policy(repo_root: Path, policy: dict[str, Any]) -> dict[str, Any]:
    if policy.get("schemaVersion") != 2:
        raise ProviderCredentialStorageError("provider credential storage schemaVersion must be 2")
    if policy.get("policyKind") != "providerCredentialStorage":
        raise ProviderCredentialStorageError("provider credential storage policyKind is invalid")

    docs_path = require_string(policy.get("docsPath"), "docsPath")
    preferences_manager_path = require_string(policy.get("preferencesManager"), "preferencesManager")
    application_path = require_string(policy.get("application"), "application")
    settings_screen_path = require_string(policy.get("settingsScreen"), "settingsScreen")
    app_gradle_path = require_string(policy.get("appGradle"), "appGradle")
    backup_rules_path = require_string(policy.get("backupRules"), "backupRules")
    data_extraction_rules_path = require_string(policy.get("dataExtractionRules"), "dataExtractionRules")
    diagnostics_doc_path = require_string(policy.get("diagnosticsDoc"), "diagnosticsDoc")
    diagnostics_source_path = require_string(policy.get("diagnosticsSource"), "diagnosticsSource")
    privacy_policy_path = require_string(policy.get("privacyPolicy"), "privacyPolicy")
    library_export_source_path = require_string(policy.get("libraryExportSource"), "libraryExportSource")
    library_export_contract_path = require_string(policy.get("libraryExportContract"), "libraryExportContract")
    data_store = require_object(policy.get("dataStore"), "dataStore")
    data_store_file = require_string(data_store.get("filePath"), "dataStore.filePath")
    at_rest = require_string(data_store.get("atRestProtection"), "dataStore.atRestProtection")
    keystore_decision = require_string(data_store.get("keystoreDecision"), "dataStore.keystoreDecision")
    if at_rest != "legacyMigrationOnly":
        raise ProviderCredentialStorageError("dataStore.atRestProtection must document legacy migration only")
    if "remove" not in keystore_decision.lower() or "migration" not in keystore_decision.lower():
        raise ProviderCredentialStorageError("dataStore.keystoreDecision must document legacy removal after migration")
    encrypted_store = require_object(policy.get("encryptedStore"), "encryptedStore")
    encrypted_store_file = require_string(encrypted_store.get("filePath"), "encryptedStore.filePath")
    encrypted_prefs_name = require_string(encrypted_store.get("sharedPreferencesName"), "encryptedStore.sharedPreferencesName")
    key_alias = require_string(encrypted_store.get("keyAlias"), "encryptedStore.keyAlias")
    cipher = require_string(encrypted_store.get("cipher"), "encryptedStore.cipher")
    encrypted_protection = require_string(encrypted_store.get("atRestProtection"), "encryptedStore.atRestProtection")
    recovery_marker = require_string(encrypted_store.get("recoveryMarker"), "encryptedStore.recoveryMarker")
    restore_decision = require_string(encrypted_store.get("restoreDecision"), "encryptedStore.restoreDecision")
    fallback = require_string(encrypted_store.get("fallback"), "encryptedStore.fallback")
    if encrypted_protection != "androidKeystoreAesGcm":
        raise ProviderCredentialStorageError("encryptedStore.atRestProtection must be androidKeystoreAesGcm")
    if cipher != "AES/GCM/NoPadding":
        raise ProviderCredentialStorageError("encryptedStore.cipher must be AES/GCM/NoPadding")
    if not all(term in restore_decision.lower() for term in ("clear", "re-entry", "temporary")):
        raise ProviderCredentialStorageError("encryptedStore.restoreDecision must cover clearing, re-entry, and temporary failure")
    if "re-entry" not in fallback.lower() or "migration" not in fallback.lower():
        raise ProviderCredentialStorageError("encryptedStore.fallback must cover re-entry and legacy migration")

    docs_text = read_text(repo_root, docs_path)
    preferences_manager_text = read_preferences_surface_text(repo_root, preferences_manager_path)
    application_text = read_text(repo_root, application_path)
    settings_screen_text = read_settings_surface_text(repo_root, settings_screen_path)
    app_gradle_text = read_text(repo_root, app_gradle_path)
    backup_rules_text = read_text(repo_root, backup_rules_path)
    data_extraction_rules_text = read_text(repo_root, data_extraction_rules_path)
    diagnostics_doc_text = read_text(repo_root, diagnostics_doc_path).lower()
    diagnostics_source_text = read_text(repo_root, diagnostics_source_path)
    privacy_policy_text = read_text(repo_root, privacy_policy_path).lower()
    library_export_text = "\n".join(
        (
            read_text(repo_root, library_export_source_path),
            read_text(repo_root, library_export_contract_path),
        )
    )

    validate_backup_exclusion(backup_rules_text, "file", data_store_file, backup_rules_path)
    validate_backup_exclusion(data_extraction_rules_text, "file", data_store_file, data_extraction_rules_path)
    validate_backup_exclusion(backup_rules_text, "sharedpref", encrypted_store_file, backup_rules_path)
    validate_backup_exclusion(data_extraction_rules_text, "sharedpref", encrypted_store_file, data_extraction_rules_path)
    if "api keys entered by the user" not in privacy_policy_text:
        raise ProviderCredentialStorageError("privacy policy must disclose user-entered API key storage")
    for term in ("android keystore", "excluded from cloud backup", "re-enter"):
        if term not in privacy_policy_text:
            raise ProviderCredentialStorageError(f"privacy policy is missing credential recovery term: {term}")
    if (
            "ProviderApiKeyDialog(" not in settings_screen_text
            or "settings_apikey_clear" not in settings_screen_text
            or ">Clear<" not in settings_screen_text
            or "settings_services_provider_key_storage_warning_title" not in settings_screen_text
            or "settings_services_provider_key_reentry_title" not in settings_screen_text
    ):
        raise ProviderCredentialStorageError("Settings screen must expose Clear, temporary failure, and re-entry UI")
    validate_encrypted_store_source(
        preferences_manager_text=preferences_manager_text,
        repo_root=repo_root,
        preferences_manager_path=preferences_manager_path,
        encrypted_prefs_name=encrypted_prefs_name,
        encrypted_store_file=encrypted_store_file,
        key_alias=key_alias,
        cipher=cipher,
        recovery_marker=recovery_marker,
    )

    credentials_raw = policy.get("credentials")
    if not isinstance(credentials_raw, list) or not credentials_raw:
        raise ProviderCredentialStorageError("credentials must be a non-empty list")

    seen_ids: set[str] = set()
    encrypted_count = 0
    build_config_count = 0
    paid_sensitive_count = 0
    stability_credential: dict[str, Any] | None = None
    for index, raw_credential in enumerate(credentials_raw):
        credential = require_object(raw_credential, f"credentials[{index}]")
        missing = sorted(REQUIRED_CREDENTIAL_FIELDS - set(credential))
        if missing:
            raise ProviderCredentialStorageError(f"credentials[{index}] missing fields: {', '.join(missing)}")
        credential_id = require_string(credential["id"], f"credentials[{index}].id")
        if credential_id in seen_ids:
            raise ProviderCredentialStorageError(f"duplicate credential id: {credential_id}")
        seen_ids.add(credential_id)
        provider = require_string(credential["provider"], f"{credential_id}.provider")
        classification = require_string(credential["classification"], f"{credential_id}.classification")
        if classification not in SUPPORTED_CLASSIFICATIONS:
            raise ProviderCredentialStorageError(f"{credential_id}.classification is unsupported")
        storage = require_string(credential["storage"], f"{credential_id}.storage")
        if storage not in SUPPORTED_STORAGE:
            raise ProviderCredentialStorageError(f"{credential_id}.storage is unsupported")
        preference_key = require_nullable_string(credential["preferenceKey"], f"{credential_id}.preferenceKey")
        credential["buildConfigField"] = require_nullable_string(credential["buildConfigField"], f"{credential_id}.buildConfigField")
        credential["gradleProperty"] = require_nullable_string(credential["gradleProperty"], f"{credential_id}.gradleProperty")
        settings_label = require_nullable_string(credential["settingsLabel"], f"{credential_id}.settingsLabel")
        release_default = require_string(credential["releaseDefault"], f"{credential_id}.releaseDefault")
        at_rest_policy = require_string(credential["atRestPolicy"], f"{credential_id}.atRestPolicy")
        backup_policy = require_string(credential["backupPolicy"], f"{credential_id}.backupPolicy")
        restore_policy = require_string(credential["restorePolicy"], f"{credential_id}.restorePolicy")
        deletion_policy = require_string(credential["deletionPolicy"], f"{credential_id}.deletionPolicy")
        export_policy = require_string(credential["exportPolicy"], f"{credential_id}.exportPolicy")
        user_control = require_string(credential["userControl"], f"{credential_id}.userControl")
        redaction_terms = require_string_list(credential["redactionTerms"], f"{credential_id}.redactionTerms")
        settings_exposure = credential.get("settingsExposure")
        if settings_exposure is not None and settings_exposure != "legacyHidden":
            raise ProviderCredentialStorageError(
                f"{credential_id}.settingsExposure must be legacyHidden when present"
            )

        for term in (credential_id, provider, classification):
            if term not in docs_text:
                raise ProviderCredentialStorageError(f"{docs_path} is missing {term}")
        if "exclud" not in backup_policy.lower() and "not part" not in backup_policy.lower():
            raise ProviderCredentialStorageError(f"{credential_id}.backupPolicy must explicitly exclude the credential")
        if "exclud" not in export_policy.lower():
            raise ProviderCredentialStorageError(f"{credential_id}.exportPolicy must explicitly exclude the credential")

        if storage == "encryptedSharedPreferences":
            encrypted_count += 1
            if not preference_key:
                raise ProviderCredentialStorageError(f"{credential_id} encrypted rows require legacy preferenceKey")
            if f'stringPreferencesKey("{preference_key}")' not in preferences_manager_text:
                raise ProviderCredentialStorageError(f"PreferencesManager missing legacy key {preference_key}")
            if provider_credential_source_marker(preference_key) not in preferences_manager_text:
                raise ProviderCredentialStorageError(f"PreferencesManager missing encrypted mapping for {preference_key}")
            if settings_label and settings_label not in settings_screen_text:
                raise ProviderCredentialStorageError(f"Settings screen missing label for {credential_id}")
            if not settings_label and settings_exposure != "legacyHidden":
                raise ProviderCredentialStorageError(
                    f"{credential_id} encrypted rows require a Settings label unless marked legacyHidden"
                )
            if settings_exposure == "legacyHidden" and "no settings field" not in user_control.lower():
                raise ProviderCredentialStorageError(
                    f"{credential_id} legacyHidden rows must document that no Settings field is exposed"
                )
            if settings_exposure == "legacyHidden":
                validate_legacy_hidden_retirement(
                    credential_id=credential_id,
                    preferences_manager_text=preferences_manager_text,
                    application_text=application_text,
                )
        elif preference_key:
            raise ProviderCredentialStorageError(f"{credential_id} buildConfigOnly rows must not set preferenceKey")

        if credential["buildConfigField"]:
            build_config_count += 1
            if not credential["gradleProperty"]:
                raise ProviderCredentialStorageError(f"{credential_id} BuildConfig rows require gradleProperty")
            if release_default != "blank":
                raise ProviderCredentialStorageError(f"{credential_id} releaseDefault must be blank")
            validate_gradle_default(app_gradle_text, credential)

        if classification == "paidSensitiveSecret":
            paid_sensitive_count += 1
            if "android keystore" not in docs_text.lower():
                raise ProviderCredentialStorageError(f"{docs_path} must document Android Keystore protection")
        if credential_id == STABILITY_CREDENTIAL_ID:
            stability_credential = credential

        for term in redaction_terms:
            if term.lower() not in diagnostics_doc_text:
                raise ProviderCredentialStorageError(f"{diagnostics_doc_path} is missing redaction term {term}")
        for forbidden in filter(None, (preference_key, credential["buildConfigField"])):
            if forbidden in library_export_text:
                raise ProviderCredentialStorageError(
                    f"library export source must not reference provider credential field {forbidden}"
                )

    validate_stability_credential(stability_credential)
    for marker in ("At rest", "Backup and transfer", "Restore", "Deletion", "Export"):
        if marker not in docs_text:
            raise ProviderCredentialStorageError(f"{docs_path} is missing policy heading {marker}")
    for marker in (
        "Provider credential storage:",
        "unreadable ciphertext removed",
        "encrypted values excluded from backup and export",
    ):
        if marker not in diagnostics_source_text:
            raise ProviderCredentialStorageError(f"{diagnostics_source_path} is missing {marker}")

    return {
        "policyKind": policy["policyKind"],
        "schemaVersion": policy["schemaVersion"],
        "credentialCount": len(credentials_raw),
        "encryptedCredentialCount": encrypted_count,
        "buildConfigCredentialCount": build_config_count,
        "paidSensitiveCredentialCount": paid_sensitive_count,
        "stabilityCredentialStatus": "ok",
        "dataStoreFile": data_store_file,
        "encryptedStoreFile": encrypted_store_file,
        "status": "ok",
    }


def provider_credential_source_marker(preference_key: str) -> str:
    mapping = {
        "wallhaven_api_key": "ProviderCredentialKey.WALLHAVEN",
        "pexels_api_key": "ProviderCredentialKey.PEXELS",
        "pixabay_api_key": "ProviderCredentialKey.PIXABAY",
        "freesound_api_key": "ProviderCredentialKey.FREESOUND",
        "stability_ai_key": 'ProviderCredentialKey("stability_ai_key")',
    }
    if preference_key not in mapping:
        raise ProviderCredentialStorageError(f"missing ProviderCredentialKey mapping for {preference_key}")
    return mapping[preference_key]


def validate_legacy_hidden_retirement(
    *,
    credential_id: str,
    preferences_manager_text: str,
    application_text: str,
) -> None:
    required_preferences_markers = (
        "suspend fun retireLegacyProviderCredentials()",
        "providerCredentialStore.clear(ProviderCredentialKey.FREESOUND)",
        "dataStore.edit { it.remove(Keys.FREESOUND_KEY) }",
    )
    for marker in required_preferences_markers:
        if marker not in preferences_manager_text:
            raise ProviderCredentialStorageError(
                f"{credential_id} legacyHidden credential is missing retirement cleanup: {marker}"
            )
    if ".retireLegacyProviderCredentials()" not in application_text:
        raise ProviderCredentialStorageError(
            f"{credential_id} legacyHidden credential cleanup must run at app start"
        )


def validate_encrypted_store_source(
    *,
    preferences_manager_text: str,
    repo_root: Path,
    preferences_manager_path: str,
    encrypted_prefs_name: str,
    encrypted_store_file: str,
    key_alias: str,
    cipher: str,
    recovery_marker: str,
) -> None:
    store_text = read_text(repo_root, "app/src/main/java/com/chloemlla/aura/data/local/ProviderCredentialStore.kt")
    for required in (
        "AndroidKeyStore",
        "KeyGenParameterSpec",
        "KeyProperties.KEY_ALGORITHM_AES",
        "KeyProperties.BLOCK_MODE_GCM",
        "setRandomizedEncryptionRequired(true)",
        "GCMParameterSpec",
        cipher,
        encrypted_prefs_name,
        encrypted_store_file,
        key_alias,
        recovery_marker,
        "ProviderCredentialReadResult.ReentryRequired",
        "ProviderCredentialKeyMissingException",
        "ProviderCredentialKeyInvalidatedException",
        "recoverUnreadableCredentials",
        "fun clearAll()",
    ):
        if required not in store_text:
            raise ProviderCredentialStorageError(f"ProviderCredentialStore.kt missing {required}")
    for required in (
        "providerCredential(",
        "readProviderCredential(",
        "setProviderCredential(",
        "dataStore.edit { it.remove(legacyKey) }",
        "providerCredentialStorageUnavailable",
        "providerCredentialReentryRequired",
        "providerCredentialReentryKeys",
        "retryProviderCredentials",
    ):
        if required not in preferences_manager_text:
            raise ProviderCredentialStorageError(f"{preferences_manager_path} missing {required}")


def validate_stability_credential(credential: dict[str, Any] | None) -> None:
    if credential is None:
        raise ProviderCredentialStorageError(f"{STABILITY_CREDENTIAL_ID} credential row is required")
    for field, expected in STABILITY_REQUIREMENTS.items():
        if credential.get(field) != expected:
            raise ProviderCredentialStorageError(
                f"{STABILITY_CREDENTIAL_ID}.{field} must be {expected}"
            )
    if credential.get("releaseDefault") != "blank":
        raise ProviderCredentialStorageError(f"{STABILITY_CREDENTIAL_ID}.releaseDefault must be blank")
    redaction_terms = credential.get("redactionTerms")
    if not isinstance(redaction_terms, list) or "stability.ai.key" not in redaction_terms:
        raise ProviderCredentialStorageError(f"{STABILITY_CREDENTIAL_ID} must redact stability.ai.key")
    user_control = credential.get("userControl")
    if not isinstance(user_control, str) or "Clear" not in user_control:
        raise ProviderCredentialStorageError(f"{STABILITY_CREDENTIAL_ID} must document explicit Clear control")


def main() -> int:
    args = parse_args()
    repo_root = Path(args.repo_root)
    try:
        policy = require_object(read_json(repo_root / args.policy), "provider credential storage policy")
        result = validate_policy(repo_root, policy)
    except (OSError, ValueError, ET.ParseError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
