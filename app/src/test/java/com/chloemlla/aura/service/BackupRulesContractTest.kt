package com.chloemlla.aura.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Privacy contract for Android backup rules.
 *
 * The rules are an allowlist: the only `<include>` disables the platform default
 * of backing up every standard directory, so anything not listed (Room DB,
 * DataStore, all other sharedprefs, cached media) never leaves the device. These
 * tests pin the whitelist shape — the locale preference is the single allowlisted
 * surface, nothing else is, and no `<exclude>` remains because the allowlist
 * already implies it.
 */
class BackupRulesContractTest {

    @Test
    fun `android 11 backup allows only the locale preference`() {
        val backupRules = File("src/main/res/xml/backup_rules.xml").readText()

        assertEquals(
            "Locale preference must be the only allowlisted surface",
            listOf(localeIncludeSnippet),
            backupRules.includeSnippets(),
        )
        assertFalse("Allowlist must not carry stale blacklist entries", backupRules.contains("<exclude"))
    }

    @Test
    fun `android 12 data extraction allows only the locale preference on cloud and device`() {
        val dataExtractionRules = File("src/main/res/xml/data_extraction_rules.xml").readText()

        assertEquals(
            "Locale preference must be allowlisted for both cloud-backup and device-transfer",
            listOf(localeIncludeSnippet, localeIncludeSnippet),
            dataExtractionRules.includeSnippets(),
        )
        assertFalse("Allowlist must not carry stale blacklist entries", dataExtractionRules.contains("<exclude"))
    }

    private fun String.includeSnippets(): List<String> =
        Regex("""<include\s+domain="[^"]+"\s+path="[^"]+"\s*/>""")
            .findAll(this)
            .map { it.value }
            .toList()
}

private val localeIncludeSnippet = """<include domain="sharedpref" path="freevibe_locale.xml" />"""
