package com.chloemlla.aura.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
