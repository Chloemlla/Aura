package com.chloemlla.aura.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionDisclosureContractTest {

    private fun settingsSource(): String =
        File("src/main/java/com/freevibe/ui/screens/settings")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.name }
            .joinToString("\n") { it.readText() }

    @Test
    fun `recording requests microphone only after prominent rationale`() {
        val screen = File("src/main/java/com/freevibe/ui/screens/sounds/SoundsScreen.kt").readText()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(screen.contains("showRecordPermissionPrompt"))
        assertTrue(screen.contains("showRecordPermissionRecovery"))
        assertTrue(screen.contains("R.string.permission_microphone_body"))
        assertTrue(screen.contains("R.string.permission_microphone_denied_body"))
        assertTrue(screen.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertTrue(screen.contains("recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)"))
        assertTrue(strings.contains("permission_microphone_body"))
        assertTrue(strings.contains("permission_microphone_denied_body"))
    }

    @Test
    fun `settings permissions have request and denial recovery prompts`() {
        val screen = settingsSource()
        val strings = File("src/main/res/values/strings.xml").readText()

        assertTrue(screen.contains("SettingsPermissionPrompt.DAILY_NOTIFICATION_REQUEST"))
        assertTrue(screen.contains("SettingsPermissionPrompt.DAILY_NOTIFICATION_RECOVERY"))
        assertTrue(screen.contains("SettingsPermissionPrompt.WEATHER_LOCATION_REQUEST"))
        assertTrue(screen.contains("SettingsPermissionPrompt.WEATHER_LOCATION_RECOVERY"))
        assertTrue(screen.contains("R.string.permission_notification_body"))
        assertTrue(screen.contains("R.string.permission_notification_denied_body"))
        assertTrue(screen.contains("R.string.permission_location_body"))
        assertTrue(screen.contains("R.string.permission_location_denied_body"))
        assertTrue(strings.contains("permission_notification_body"))
        assertTrue(strings.contains("permission_location_body"))
    }

    @Test
    fun `settings does not jump to notification settings directly after a denied runtime request`() {
        val screen = settingsSource()

        assertFalse(
            screen.contains(
                """
                } else {
                    setDailyWallpaperEnabled(false)
                    openNotificationSettings()
                }
                """.trimIndent(),
            ),
        )
    }
}
