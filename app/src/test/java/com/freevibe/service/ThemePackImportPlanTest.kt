package com.freevibe.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemePackImportPlanTest {

    private val morningAsset = "assets/wallpaper-pack-morning-abc.jpg"
    private val ringtoneAsset = "assets/sound-ringtone-def.mp3"
    private val videoAsset = "assets/video-wallpaper-123.mp4"
    private val assets = mapOf(
        morningAsset to "/data/user/0/com.freevibe/files/theme_packs/import-1/a_morning.jpg",
        ringtoneAsset to "/data/user/0/com.freevibe/files/theme_packs/import-1/b_ringtone.mp3",
        videoAsset to "/data/user/0/com.freevibe/files/theme_packs/import-1/c_loop.mp4",
    )

    @Test
    fun `a valid pack plans every setting with embedded files pointed at the import`() {
        val plan = planThemePackImport(validRecipe(), assets)

        val pack = parsePack(plan.wallpaperPackJson!!)!!
        assertEquals(assets[morningAsset], pack.slots.first { it.daypart == Daypart.MORNING }.wallpaperUri)
        assertEquals("https://example.com/night.jpg", pack.slots.first { it.daypart == Daypart.NIGHT }.wallpaperUri)
        assertEquals(assets[ringtoneAsset], parseProfiles(plan.soundProfilesJson!!).single().ringtoneUri)
        assertEquals(assets[ringtoneAsset], plan.ringtoneUri)
        assertEquals("content://media/internal/audio/media/7", plan.notificationUri)
        assertNull(plan.alarmUri)
        assertEquals(assets[videoAsset], plan.videoPath)
        assertEquals(0, plan.widget!!.shuffleCount)
        assertEquals(6, plan.importedItemCount)
    }

    @Test
    fun `a bad sound profile recipe at the end rejects the whole pack`() {
        assertRejected(validRecipe().copy(soundProfilesJson = "{\"not\": \"a list\""), "sound profiles")
    }

    @Test
    fun `a reference to a file the archive does not hold rejects the pack`() {
        assertRejected(planFor(validRecipe(), assets - videoAsset), "missing the file")
    }

    @Test
    fun `two slots for the same daypart reject the pack`() {
        val recipe = validRecipe().copy(
            wallpaperPackJson = serializePack(
                WallpaperPack(
                    id = "dayparts",
                    name = "Dayparts",
                    slots = listOf(
                        DaypartSlot(Daypart.MORNING, "https://example.com/a.jpg"),
                        DaypartSlot(Daypart.MORNING, "https://example.com/b.jpg"),
                    ),
                ),
            ),
        )

        assertRejected(recipe, "two Morning slots")
    }

    @Test
    fun `locations Aura cannot open reject the pack wherever they appear`() {
        assertRejected(validRecipe().copy(sounds = ThemePackSoundState(ringtoneUri = "javascript:alert(1)")), "ringtone")
        assertRejected(
            validRecipe().copy(
                wallpaperPackJson = serializePack(
                    WallpaperPack(id = "p", name = "P", slots = listOf(DaypartSlot(Daypart.DAY, "../../shared_prefs/x.xml"))),
                ),
            ),
            "Day wallpaper",
        )
        assertRejected(
            validRecipe().copy(
                soundProfilesJson = serializeProfiles(
                    listOf(SoundProfile(id = "night", name = "Night", alarmUri = "intent://scan#Intent;end")),
                ),
            ),
            "Night sound",
        )
        assertRejected(
            validRecipe().copy(
                media = validRecipe().media + ThemePackMediaReference(
                    role = "current_wallpaper",
                    label = "Current wallpaper",
                    locator = "file://host/share/wall.jpg",
                ),
            ),
            "Current wallpaper",
        )
    }

    @Test
    fun `duplicate roles and profile ids, bad hours, targets and versions reject the pack`() {
        val recipe = validRecipe()
        assertRejected(recipe.copy(media = recipe.media + recipe.media.first()), "twice")
        assertRejected(
            recipe.copy(
                soundProfilesJson = serializeProfiles(
                    listOf(SoundProfile(id = "a", name = "A"), SoundProfile(id = "a", name = "B")),
                ),
            ),
            "twice",
        )
        assertRejected(
            recipe.copy(soundProfilesJson = serializeProfiles(listOf(SoundProfile(id = "a", name = "A", endHour = 25)))),
            "hours",
        )
        assertRejected(
            recipe.copy(wallpaperPackJson = serializePack(WallpaperPack(id = "p", name = "P", target = "CEILING"))),
            "unknown target",
        )
        assertRejected(recipe.copy(version = THEME_PACK_VERSION + 1), "not supported yet")
        assertRejected(recipe.copy(version = 0), "not valid")
    }

    @Test
    fun `locator rules accept what Aura stores and refuse the rest`() {
        listOf(
            "https://example.com/wall.jpg",
            "http://example.com/wall.jpg",
            "content://media/external/images/media/12",
            "android.resource://com.freevibe/raw/chime",
            "rawresource://bundled/chime",
            "file:///storage/emulated/0/Pictures/a.jpg",
            "/data/user/0/com.freevibe/files/theme_packs/import-1/a.jpg",
            "/storage/emulated/0/Ringtones/odd:name.mp3",
        ).forEach { assertTrue(it, isAcceptableThemePackLocator(it)) }

        listOf(
            "",
            " https://example.com/a.jpg",
            "relative/path.jpg",
            "/data/../data/user/0/x",
            "file:///sdcard/../data/x",
            "file://host/x",
            "javascript:alert(1)",
            "data:image/png;base64,AAAA",
            "https:",
            "content://media/\u0000x",
        ).forEach { assertFalse(it, isAcceptableThemePackLocator(it)) }
    }

    @Test
    fun `an AI wallpaper saved with a single-slash file locator imports`() {
        // AiWallpaperRepository stores `File.toURI().toString()`, which is `file:/data/...`.
        val generated = "file:/data/user/0/com.freevibe/files/ai_wallpapers/generated-1.png"
        val recipe = validRecipe().copy(
            media = validRecipe().media + ThemePackMediaReference(
                role = "current_wallpaper",
                label = "Current wallpaper",
                locator = generated,
            ),
        )

        val plan = planThemePackImport(recipe, assets)

        assertEquals(assets[videoAsset], plan.videoPath)
        assertTrue(isAcceptableThemePackLocator(generated))
        listOf("file:/", "file:/data/../etc/x", "FILE://host/x").forEach { assertFalse(it, isAcceptableThemePackLocator(it)) }
    }

    private fun planFor(recipe: ThemePackRecipe, assetsByKey: Map<String, String>): () -> ThemePackImportPlan =
        { planThemePackImport(recipe, assetsByKey) }

    private fun assertRejected(recipe: ThemePackRecipe, messagePart: String) =
        assertRejected(planFor(recipe, assets), messagePart)

    private fun assertRejected(plan: () -> ThemePackImportPlan, messagePart: String) {
        val error = runCatching { plan() }.exceptionOrNull()
        assertTrue("expected a validation error, got $error", error is ThemePackValidationException)
        assertTrue("${error?.message} should mention $messagePart", error!!.message!!.contains(messagePart))
    }

    private fun validRecipe() = ThemePackRecipe(
        id = "pack-1",
        name = "Desk",
        media = listOf(
            ThemePackMediaReference(
                role = "wallpaper_pack_morning",
                label = "Morning wallpaper",
                locator = "content://media/external/images/media/1",
                assetKey = morningAsset,
            ),
            ThemePackMediaReference(
                role = "sound_ringtone",
                label = "Ringtone",
                locator = "content://media/external/audio/media/2",
                assetKey = ringtoneAsset,
            ),
        ),
        videoWallpaper = ThemePackMediaReference(
            role = "video_wallpaper",
            label = "Video wallpaper",
            locator = "/storage/emulated/0/Movies/loop.mp4",
            assetKey = videoAsset,
        ),
        sounds = ThemePackSoundState(
            ringtoneUri = "content://media/external/audio/media/2",
            notificationUri = "content://media/internal/audio/media/7",
        ),
        widget = ThemePackWidgetState(primaryTint = 0xFF336699.toInt(), shuffleCount = -3),
        wallpaperPackJson = serializePack(
            WallpaperPack(
                id = "dayparts",
                name = "Dayparts",
                slots = listOf(
                    DaypartSlot(Daypart.MORNING, "content://media/external/images/media/1"),
                    DaypartSlot(Daypart.NIGHT, "https://example.com/night.jpg"),
                ),
            ),
        ),
        soundProfilesJson = serializeProfiles(
            listOf(
                SoundProfile(
                    id = "workday",
                    name = "Workday",
                    ringtoneUri = "content://media/external/audio/media/2",
                    startHour = 9,
                    endHour = 17,
                ),
            ),
        ),
    )
}
