package com.chloemlla.aura.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePackRecipeManagerTest {

    @Test
    fun `default shortcut recipes mirror static launcher actions and remain manual on import`() {
        val shortcuts = defaultThemeShortcutRecipes()

        assertEquals(
            listOf("shuffle_wallpaper", "rotate_now", "search_wallpapers", "downloads"),
            shortcuts.map { it.id },
        )
        assertTrue(shortcuts.all { !it.supportedOnImport })
        assertTrue(shortcuts.any { it.action == TaskerActionReceiver.ACTION_SHUFFLE_NOW })
        assertTrue(shortcuts.any { it.action == TaskerActionReceiver.ACTION_ROTATE_NOW })
        assertTrue(shortcuts.all { it.manualInstruction.contains("Long-press the Aura icon") })
    }

    @Test
    fun `theme pack recipe serializes wallpaper video sound widget and shortcut metadata`() {
        val recipe = ThemePackRecipe(
            id = "pack-1",
            name = "Night desk",
            media = listOf(
                ThemePackMediaReference(
                    role = "current_wallpaper",
                    label = "Current wallpaper",
                    locator = "https://example.com/wall.jpg",
                    mimeType = "image/jpeg",
                ),
            ),
            videoWallpaper = ThemePackMediaReference(
                role = "video_wallpaper",
                label = "Video wallpaper",
                locator = "file:///sdcard/Aura/wallpaper.mp4",
                mimeType = "video/mp4",
                requiresReselection = true,
            ),
            sounds = ThemePackSoundState(
                ringtoneUri = "content://media/ringtone/1",
                notificationUri = "content://media/notification/1",
                profileCount = 1,
            ),
            widget = ThemePackWidgetState(
                primaryTint = 0xFF336699.toInt(),
                accentTint = 0xFF00AA88.toInt(),
                dominantTint = 0xFF101010.toInt(),
                previewWallpaperUri = "https://example.com/thumb.jpg",
                shuffleCount = 4,
            ),
            shortcuts = defaultThemeShortcutRecipes(),
        )

        val parsed = parseThemePackRecipe(serializeThemePackRecipe(recipe))

        assertNotNull(parsed)
        assertEquals("Night desk", parsed!!.name)
        assertEquals("https://example.com/wall.jpg", parsed.media.single().locator)
        assertEquals("file:///sdcard/Aura/wallpaper.mp4", parsed.videoWallpaper?.locator)
        assertEquals("content://media/ringtone/1", parsed.sounds.ringtoneUri)
        assertEquals(4, parsed.widget.shuffleCount)
        assertEquals(4, parsed.shortcuts.size)
    }

    @Test
    fun `wallpaper pack remap replaces embedded local daypart slots`() {
        val original = serializePack(
            WallpaperPack(
                id = "dayparts",
                name = "Dayparts",
                slots = listOf(
                    DaypartSlot(Daypart.MORNING, "content://media/images/1", "Morning"),
                    DaypartSlot(Daypart.NIGHT, "https://example.com/night.jpg", "Night"),
                ),
            ),
        )
        val remapped = remapWallpaperPackAssetLocators(
            raw = original,
            references = listOf(
                ThemePackMediaReference(
                    role = "wallpaper_pack_morning",
                    label = "Morning",
                    locator = "content://media/images/1",
                    assetKey = "assets/morning.jpg",
                ),
            ),
            assetRemaps = mapOf("assets/morning.jpg" to "/data/user/0/com.chloemlla.aura/files/theme_packs/morning.jpg"),
        )

        val parsed = parsePack(remapped)

        assertEquals("/data/user/0/com.chloemlla.aura/files/theme_packs/morning.jpg", parsed?.slots?.first()?.wallpaperUri)
        assertEquals("https://example.com/night.jpg", parsed?.slots?.last()?.wallpaperUri)
    }

    @Test
    fun `sound profile remap replaces embedded ringtone notification and alarm locators`() {
        val original = serializeProfiles(
            listOf(
                SoundProfile(
                    id = "workday",
                    name = "Workday",
                    ringtoneUri = "content://media/audio/ringtone",
                    notificationUri = "content://media/audio/notification",
                    alarmUri = "https://example.com/alarm.mp3",
                ),
            ),
        )
        val references = listOf(
            ThemePackMediaReference(
                role = "sound_profile_workday_ringtone",
                label = "Workday ringtone",
                locator = "content://media/audio/ringtone",
                assetKey = "assets/ringtone.mp3",
            ),
            ThemePackMediaReference(
                role = "sound_profile_workday_notification",
                label = "Workday notification",
                locator = "content://media/audio/notification",
                assetKey = "assets/notification.mp3",
            ),
        )

        val parsed = parseProfiles(
            remapSoundProfileAssetLocators(
                raw = original,
                references = references,
                assetRemaps = mapOf(
                    "assets/ringtone.mp3" to "/data/theme/ringtone.mp3",
                    "assets/notification.mp3" to "/data/theme/notification.mp3",
                ),
            ),
        )

        assertEquals("/data/theme/ringtone.mp3", parsed.single().ringtoneUri)
        assertEquals("/data/theme/notification.mp3", parsed.single().notificationUri)
        assertEquals("https://example.com/alarm.mp3", parsed.single().alarmUri)
    }

    @Test
    fun `import instructions explain unsupported launcher widgets sounds and missing local assets`() {
        val recipe = ThemePackRecipe(
            id = "pack-1",
            name = "Desk",
            media = listOf(
                ThemePackMediaReference(
                    role = "wallpaper_pack_morning",
                    label = "Morning wallpaper",
                    locator = "content://media/images/1",
                    requiresReselection = true,
                ),
            ),
            videoWallpaper = ThemePackMediaReference(
                role = "video_wallpaper",
                label = "Video wallpaper",
                locator = "/sdcard/Movies/loop.mp4",
            ),
            sounds = ThemePackSoundState(ringtoneUri = "content://media/audio/1"),
            widget = ThemePackWidgetState(primaryTint = 1),
            shortcuts = defaultThemeShortcutRecipes(),
        )

        val instructions = themePackImportInstructions(recipe, assetRemaps = emptyMap())

        assertTrue(instructions.any { it.contains("Long-press the Aura icon") })
        assertTrue(instructions.any { it.contains("Re-select the video wallpaper") })
        assertTrue(instructions.any { it.contains("Aura widget") })
        assertTrue(instructions.any { it.contains("Modify system settings") })
        assertTrue(instructions.any { it.contains("Morning wallpaper") })
    }

    @Test
    fun `import instructions lead with the hints about what the import did not do`() {
        val instructions = themePackImportInstructions(
            recipe = ThemePackRecipe(id = "pack-1", name = "Desk"),
            assetRemaps = emptyMap(),
            unsupportedWallpaperSourceHint = "dropped-sources",
            enableWallpaperPackHint = "enable-pack",
            pendingSoundRecipeHint = "pending-sounds",
        )

        assertEquals(listOf("dropped-sources", "enable-pack", "pending-sounds"), instructions)
    }

    @Test
    fun `wallpaper locators are accepted only for provider hosts or files this import expanded`() {
        val importDir = createTempImportDir()
        val staged = File(importDir, "abc123_morning.jpg").apply { writeText("jpeg") }

        assertEquals(
            "https://w.wallhaven.cc/full/x.jpg",
            sanitizeWallpaperLocator("https://w.wallhaven.cc/full/x.jpg", importDir),
        )
        assertEquals(staged.path, sanitizeWallpaperLocator(staged.path, importDir))
        if (staged.path.startsWith("/")) {
            // file:// only round-trips through java.net.URI on POSIX paths; a Windows
            // dev box has no such locator to sanitize.
            assertEquals(
                "an expanded asset may also be addressed as a file URI",
                "file://${staged.path}",
                sanitizeWallpaperLocator("file://${staged.path}", importDir),
            )
        }

        assertNull("unknown remote host", sanitizeWallpaperLocator("https://evil.example/x.jpg", importDir))
        assertNull("plaintext http", sanitizeWallpaperLocator("http://w.wallhaven.cc/x.jpg", importDir))
        assertNull("host suffix must match a label boundary", sanitizeWallpaperLocator("https://notwallhaven.cc/x.jpg", importDir))
        assertNull("arbitrary device file", sanitizeWallpaperLocator("/sdcard/DCIM/private.jpg", importDir))
        assertNull(
            "traversal out of the import dir",
            sanitizeWallpaperLocator("${importDir.path}/../escaped.jpg", importDir),
        )
        assertNull("content URIs cannot be re-granted on this device", sanitizeWallpaperLocator("content://media/1", importDir))
        assertNull("blank", sanitizeWallpaperLocator("   ", importDir))
        assertNull("no import dir means no local file is in scope", sanitizeWallpaperLocator(staged.path, null))
    }

    @Test
    fun `sanitizing a pack keeps allowed slots and reports how many it dropped`() {
        val importDir = createTempImportDir()
        val staged = File(importDir, "abc123_night.jpg").apply { writeText("jpeg") }
        val raw = serializePack(
            WallpaperPack(
                id = "dayparts",
                name = "Dayparts",
                slots = listOf(
                    DaypartSlot(Daypart.MORNING, "https://w.wallhaven.cc/full/m.jpg", "Morning"),
                    DaypartSlot(Daypart.DAY, "/data/data/com.chloemlla.aura/files/secret.png", "Day"),
                    DaypartSlot(Daypart.NIGHT, staged.path, "Night"),
                ),
            ),
        )

        val sanitized = sanitizeWallpaperPackLocators(raw, importDir)

        assertEquals(1, sanitized.droppedSlotCount)
        assertEquals(
            listOf(Daypart.MORNING, Daypart.NIGHT),
            parsePack(sanitized.json)?.slots?.map { it.daypart },
        )
    }

    @Test
    fun `sanitizing leaves an already clean pack untouched`() {
        val raw = serializePack(
            WallpaperPack(
                id = "remote",
                name = "Remote",
                slots = listOf(DaypartSlot(Daypart.DAY, "https://www.bing.com/x.jpg", "Day")),
            ),
        )

        val sanitized = sanitizeWallpaperPackLocators(raw, importDir = null)

        assertEquals(0, sanitized.droppedSlotCount)
        assertEquals(raw, sanitized.json)
    }

    @Test
    fun `the pending-sound hint has a matching apply action the user can reach`() {
        // The import deliberately stores ringtone recipes without applying them and
        // tells the user to apply them (AURA-G2-18); without this wiring that hint
        // points at nothing.
        val manager = File("src/main/java/com/chloemlla/aura/service/ThemePackRecipeManager.kt").readText()
        val delegate =
            File("src/main/java/com/chloemlla/aura/ui/screens/settings/SettingsDiagnosticsDelegate.kt").readText()
        val section =
            File("src/main/java/com/chloemlla/aura/ui/screens/settings/SettingsBackupSection.kt").readText()

        assertTrue(manager.contains("suspend fun applyPendingThemePackSounds()"))
        assertTrue(manager.contains("val hasPendingSoundRecipes"))
        assertTrue(delegate.contains("themePackRecipeManager.applyPendingThemePackSounds()"))
        assertTrue(delegate.contains("themePackRecipeManager.hasPendingSoundRecipes"))
        assertTrue(
            "the action must be reachable from Settings, not just callable",
            section.contains("viewModel.applyPendingThemePackSounds()"),
        )
        assertTrue(section.contains("if (pendingThemePackSounds)"))
    }

    private fun createTempImportDir(): File =
        File.createTempFile("aura-theme-pack", "").let { probe ->
            probe.delete()
            File(probe.parentFile, "${probe.name}-import").apply { mkdirs() }
        }
}
