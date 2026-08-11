package com.chloemlla.aura.service

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotateTileServiceContractTest {

    @Test
    fun `tile activity reflects either automatic rotation mode`() {
        assertFalse(isAutomaticRotationEnabled(legacyEnabled = false, schedulerEnabled = false))
        assertTrue(isAutomaticRotationEnabled(legacyEnabled = true, schedulerEnabled = false))
        assertTrue(isAutomaticRotationEnabled(legacyEnabled = false, schedulerEnabled = true))
    }

    @Test
    fun `tile is system bound and reuses the one-shot rotation path`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val tile = File("src/main/java/com/freevibe/service/RotateTileService.kt").readText()
        val trigger = File("src/main/java/com/freevibe/service/RotationTriggerService.kt").readText()

        assertTrue(manifest.contains("android:name=\".service.RotateTileService\""))
        assertTrue(manifest.contains("android.permission.BIND_QUICK_SETTINGS_TILE"))
        assertTrue(manifest.contains("android.service.quicksettings.action.QS_TILE"))
        assertTrue(tile.contains("RotationTriggerService.enqueueRotation(applicationContext)"))
        assertTrue(tile.contains("Tile.STATE_INACTIVE"))
        assertTrue(trigger.contains("AutoWallpaperWorker.TRIGGERED_ROTATION_KEY to true"))
    }
}
