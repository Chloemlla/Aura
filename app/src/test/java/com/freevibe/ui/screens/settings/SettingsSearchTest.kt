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

    @Test
    fun `row search matches localized copy and intentional aliases`() {
        val row = SettingsSearchRow(
            key = "smart:automatic-dark-oled",
            sectionKey = SettingsSectionKeys.SMART,
            sectionTitle = "Smart live wallpaper",
            title = "Automatic dark/OLED variant",
            subtitle = "Use a darker image when the system theme changes",
            aliases = setOf("oled", "theme", "dark mode"),
        )

        assertTrue(settingsSearchRowMatchesQuery("OLED", row))
        assertTrue(settingsSearchRowMatchesQuery("  theme ", row))
        assertTrue(settingsSearchRowMatchesQuery("system theme", row))
        assertFalse(settingsSearchRowMatchesQuery("bluetooth", row))
    }

    @Test
    fun `row search supports the settings controls called out by the roadmap`() {
        val rows = listOf(
            SettingsSearchRow("wifi", SettingsSectionKeys.WALLPAPERS, "Wallpapers", "Wi-Fi only", "Skip cellular fetches", setOf("wifi", "network")),
            SettingsSearchRow("backup", SettingsSectionKeys.BACKUP, "Library Backup", "Scheduled favorites backup", "Keep favorites recoverable", setOf("restore")),
            SettingsSearchRow("app-check", SettingsSectionKeys.SERVICES, "Advanced external services", "Enable external Community source", "Firebase-backed feeds", setOf("app check", "integrity")),
            SettingsSearchRow("youtube", SettingsSectionKeys.SOUNDS, "Sounds", "Enable YouTube features", "Shows YouTube sound search", setOf("extractor")),
            SettingsSearchRow("battery", SettingsSectionKeys.VIDEO, "Video", "Automatic motion guard", "Battery Saver and FPS", setOf("battery saver")),
        )

        assertTrue(settingsSearchRowMatchesQuery("wifi", rows[0]))
        assertTrue(settingsSearchRowMatchesQuery("backup", rows[1]))
        assertTrue(settingsSearchRowMatchesQuery("app check", rows[2]))
        assertTrue(settingsSearchRowMatchesQuery("youtube", rows[3]))
        assertTrue(settingsSearchRowMatchesQuery("battery saver", rows[4]))
    }
}
