from __future__ import annotations

import copy
import json
import tempfile
import unittest
from pathlib import Path

from tools.provider_credential_storage_check import ProviderCredentialStorageError, validate_policy


REPO_ROOT = Path(__file__).resolve().parents[2]


def live_policy() -> dict[str, object]:
    return json.loads((REPO_ROOT / "docs/security/provider-credential-storage.json").read_text(encoding="utf-8"))


def write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


class ProviderCredentialStorageCheckTest(unittest.TestCase):
    def test_live_provider_credential_storage_policy_passes(self) -> None:
        result = validate_policy(REPO_ROOT, live_policy())

        self.assertEqual("ok", result["status"])
        self.assertEqual(6, result["credentialCount"])
        self.assertEqual(5, result["encryptedCredentialCount"])
        self.assertEqual(5, result["buildConfigCredentialCount"])
        self.assertEqual(1, result["paidSensitiveCredentialCount"])
        self.assertEqual("ok", result["stabilityCredentialStatus"])

    def test_rejects_missing_backup_exclusion(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = seed_repo(Path(tmpdir))
            policy = minimal_policy()
            write(repo / "backup.xml", "<full-backup-content />\n")

            with self.assertRaises(ProviderCredentialStorageError):
                validate_policy(repo, policy)

    def test_rejects_missing_encrypted_sharedpref_backup_exclusion(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = seed_repo(Path(tmpdir))
            policy = minimal_policy()
            write(
                repo / "backup.xml",
                '<full-backup-content><exclude domain="file" path="datastore/freevibe_prefs.preferences_pb" /></full-backup-content>\n',
            )

            with self.assertRaises(ProviderCredentialStorageError):
                validate_policy(repo, policy)

    def test_rejects_missing_device_transfer_extraction_block(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = seed_repo(Path(tmpdir))
            policy = minimal_policy()
            write(
                repo / "data-extraction.xml",
                '<data-extraction-rules><cloud-backup><exclude domain="file" path="datastore/freevibe_prefs.preferences_pb" /></cloud-backup></data-extraction-rules>\n',
            )

            with self.assertRaises(ProviderCredentialStorageError):
                validate_policy(repo, policy)

    def test_rejects_missing_datastore_preference_key(self) -> None:
        policy = copy.deepcopy(live_policy())
        policy["credentials"][0]["preferenceKey"] = "missing_api_key"  # type: ignore[index]

        with self.assertRaises(ProviderCredentialStorageError):
            validate_policy(REPO_ROOT, policy)

    def test_rejects_missing_settings_clear_control(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = seed_repo(Path(tmpdir))
            policy = minimal_policy()
            write(repo / "SettingsScreen.kt", 'Text("Pexels API Key")\n')

            with self.assertRaises(ProviderCredentialStorageError):
                validate_policy(repo, policy)

    def test_rejects_missing_keystore_wrapper(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = seed_repo(Path(tmpdir))
            policy = minimal_policy()
            write(repo / "app/src/main/java/com/chloemlla/aura/data/local/ProviderCredentialStore.kt", "class ProviderCredentialStore\n")

            with self.assertRaises(ProviderCredentialStorageError):
                validate_policy(repo, policy)

    def test_rejects_nonblank_gradle_release_default(self) -> None:
        with tempfile.TemporaryDirectory() as tmpdir:
            repo = seed_repo(Path(tmpdir))
            policy = minimal_policy()
            write(
                repo / "build.gradle.kts",
                'buildConfigField("String", "PEXELS_API_KEY", "\\"${localProps.getProperty("pexels.api.key", "sentinel")}\\"")\n',
            )

            with self.assertRaises(ProviderCredentialStorageError):
                validate_policy(repo, policy)

    def test_rejects_missing_docs_row(self) -> None:
        policy = copy.deepcopy(live_policy())
        policy["docsPath"] = "docs/security/provider-credential-storage-missing.md"

        with tempfile.TemporaryDirectory() as tmpdir:
            repo = seed_repo(Path(tmpdir))
            write(repo / "docs/security/provider-credential-storage-missing.md", "Pexels\noptionalQuotaKey\n")
            copy_live_support_files(repo)

            with self.assertRaises(ProviderCredentialStorageError):
                validate_policy(repo, policy)

    def test_rejects_stability_classification_drift(self) -> None:
        policy = copy.deepcopy(live_policy())
        stability = next(row for row in policy["credentials"] if row["id"] == "stability-ai-key")  # type: ignore[index]
        stability["classification"] = "optionalQuotaKey"  # type: ignore[index]

        with self.assertRaises(ProviderCredentialStorageError):
            validate_policy(REPO_ROOT, policy)

    def test_rejects_missing_stability_redaction_term(self) -> None:
        policy = copy.deepcopy(live_policy())
        stability = next(row for row in policy["credentials"] if row["id"] == "stability-ai-key")  # type: ignore[index]
        stability["redactionTerms"] = ["key", "api keys", "local.properties"]  # type: ignore[index]

        with self.assertRaises(ProviderCredentialStorageError):
            validate_policy(REPO_ROOT, policy)


def minimal_policy() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "policyKind": "providerCredentialStorage",
        "docsPath": "docs.md",
        "preferencesManager": "PreferencesManager.kt",
        "settingsScreen": "SettingsScreen.kt",
        "appGradle": "build.gradle.kts",
        "backupRules": "backup.xml",
        "dataExtractionRules": "data-extraction.xml",
        "diagnosticsDoc": "diagnostics.md",
        "privacyPolicy": "privacy.md",
            "dataStore": {
                "name": "freevibe_prefs",
                "filePath": "datastore/freevibe_prefs.preferences_pb",
                "backupDecision": "excludedFromCloudBackupAndDeviceTransfer",
                "atRestProtection": "legacyMigrationOnly",
                "keystoreDecision": "Legacy provider key values are removed after successful encrypted-store migration.",
            },
            "encryptedStore": {
                "sharedPreferencesName": "aura_provider_credentials",
                "filePath": "aura_provider_credentials.xml",
                "backupDecision": "excludedFromCloudBackupAndDeviceTransfer",
                "atRestProtection": "androidKeystoreAesGcm",
                "keyAlias": "aura_provider_credentials_v1",
                "cipher": "AES/GCM/NoPadding",
                "fallback": "Settings shows a provider-key storage warning and old DataStore values are retained until migration succeeds.",
            },
            "credentials": [
                {
                    "id": "pexels-api-key",
                    "provider": "Pexels",
                    "classification": "optionalQuotaKey",
                    "storage": "encryptedSharedPreferences",
                    "preferenceKey": "pexels_api_key",
                "buildConfigField": "PEXELS_API_KEY",
                "gradleProperty": "pexels.api.key",
                "settingsLabel": "Pexels API Key",
                "releaseDefault": "blank",
                "userControl": "Settings API Keys dialog saves the value; saving blank clears it.",
                "redactionTerms": ["key", "api keys", "local.properties"],
            }
        ],
    }


def seed_repo(repo: Path) -> Path:
    write(repo / "docs.md", "pexels-api-key\nPexels\noptionalQuotaKey\nAndroid Keystore\n")
    write(
        repo / "PreferencesManager.kt",
        'val PEXELS_KEY = stringPreferencesKey("pexels_api_key")\n'
        'ProviderCredentialKey.PEXELS\n'
        'providerCredential(\n'
        'readProviderCredential(\n'
        'setProviderCredential(\n'
        'dataStore.edit { it.remove(legacyKey) }\n'
        'providerCredentialStorageUnavailable\n',
    )
    write(
        repo / "app/src/main/java/com/chloemlla/aura/data/local/ProviderCredentialStore.kt",
        'AndroidKeyStore\nKeyGenParameterSpec\nKeyProperties.KEY_ALGORITHM_AES\n'
        'KeyProperties.BLOCK_MODE_GCM\nsetRandomizedEncryptionRequired(true)\n'
        'GCMParameterSpec\nAES/GCM/NoPadding\naura_provider_credentials\n'
        'aura_provider_credentials.xml\naura_provider_credentials_v1\n',
    )
    write(
        repo / "SettingsScreen.kt",
        'ProviderApiKeyDialog(\nText("Pexels API Key")\nText("Clear")\n'
        'settings_services_provider_key_storage_warning_title\n',
    )
    write(repo / "build.gradle.kts", 'buildConfigField("String", "PEXELS_API_KEY", "\\"${localProps.getProperty("pexels.api.key", "")}\\"")\n')
    write(
        repo / "backup.xml",
        '<full-backup-content><exclude domain="file" path="datastore/freevibe_prefs.preferences_pb" /><exclude domain="sharedpref" path="aura_provider_credentials.xml" /></full-backup-content>\n',
    )
    write(
        repo / "data-extraction.xml",
        '<data-extraction-rules><cloud-backup><exclude domain="file" path="datastore/freevibe_prefs.preferences_pb" /><exclude domain="sharedpref" path="aura_provider_credentials.xml" /></cloud-backup><device-transfer><exclude domain="file" path="datastore/freevibe_prefs.preferences_pb" /><exclude domain="sharedpref" path="aura_provider_credentials.xml" /></device-transfer></data-extraction-rules>\n',
    )
    write(repo / "diagnostics.md", "key\napi keys\nlocal.properties\n")
    write(repo / "privacy.md", "API keys entered by the user\n")
    return repo


def copy_live_support_files(repo: Path) -> None:
    policy = live_policy()
    for key in (
        "preferencesManager",
        "settingsScreen",
        "appGradle",
        "backupRules",
        "dataExtractionRules",
        "diagnosticsDoc",
        "privacyPolicy",
    ):
        source = REPO_ROOT / policy[key]  # type: ignore[index]
        write(repo / policy[key], source.read_text(encoding="utf-8"))  # type: ignore[index]


if __name__ == "__main__":
    unittest.main()
