package com.freevibe.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {

    @Test
    fun `blank or whitespace query shows every section`() {
        assertTrue(settingsSectionMatchesQuery("", "Storage clear cache"))
        assertTrue(settingsSectionMatchesQuery("   ", "Storage clear cache"))
    }

    @Test
    fun `query matches case-insensitively on trimmed input`() {
        assertTrue(settingsSectionMatchesQuery("STORAGE", "Storage clear cache and trim media"))
        assertTrue(settingsSectionMatchesQuery("  cache ", "Storage clear cache and trim media"))
    }

    @Test
    fun `non-matching query hides the section`() {
        assertFalse(settingsSectionMatchesQuery("bluetooth", "Storage clear cache and trim media"))
    }

    @Test
    fun `search index covers the rendered sections with distinct keys`() {
        val keys = SETTINGS_SEARCH_SECTIONS.map { it.key }
        assertEquals(11, keys.size)
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(SettingsSectionKeys.BACKUP in keys)
        assertTrue(SettingsSectionKeys.DIAGNOSTICS in keys)
    }
}
