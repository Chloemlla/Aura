package com.freevibe.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.freevibe.data.model.FitCanvasMode
import com.freevibe.data.model.FitCanvasPreferences
import com.freevibe.data.model.FitCanvasStyle
import com.freevibe.data.model.WALLPAPER_PRESENTATION_FILL
import com.freevibe.data.model.WALLPAPER_PRESENTATION_FIT
import com.freevibe.service.VIDEO_WALLPAPER_DEFAULT_CANVAS_COLOR_PREF
import com.freevibe.service.VIDEO_WALLPAPER_DEFAULT_CANVAS_MODE_PREF
import com.freevibe.service.VIDEO_WALLPAPER_DEFAULT_SCALE_MODE_PREF
import com.freevibe.service.VIDEO_WALLPAPER_PREFS_NAME
import com.freevibe.service.VIDEO_WALLPAPER_SCALE_MODE_FIT
import com.freevibe.service.readDefaultVideoWallpaperPresentation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PreferencesManagerFitCanvasTest {
    @Test
    fun `fit canvas choices persist export and mirror the video picker defaults`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = PreferencesManager(context)
        val staticStyle = FitCanvasStyle(FitCanvasMode.CUSTOM_COLOR, 0x00123456)
        val videoStyle = FitCanvasStyle(FitCanvasMode.BLURRED_EDGE, 0x00654321)

        manager.setStaticFitCanvasPreferences(WALLPAPER_PRESENTATION_FIT, staticStyle)
        manager.setVideoFitCanvasPreferences(WALLPAPER_PRESENTATION_FIT, videoStyle)

        assertEquals(WALLPAPER_PRESENTATION_FIT, manager.staticWallpaperPresentation.first())
        assertEquals(FitCanvasMode.CUSTOM_COLOR.preferenceValue, manager.staticFitCanvasMode.first())
        assertEquals(0xFF123456.toInt(), manager.staticFitCanvasColor.first())
        assertEquals(WALLPAPER_PRESENTATION_FIT, manager.videoWallpaperPresentation.first())
        assertEquals(FitCanvasMode.BLURRED_EDGE.preferenceValue, manager.videoFitCanvasMode.first())
        assertEquals(0xFF654321.toInt(), manager.videoFitCanvasColor.first())

        val snapshot = manager.fitCanvasPreferencesSnapshot()
        assertEquals(FitCanvasMode.CUSTOM_COLOR, snapshot.staticStyle.mode)
        assertEquals(FitCanvasMode.BLURRED_EDGE, snapshot.videoStyle.mode)

        val mirrored = readDefaultVideoWallpaperPresentation(context)
        assertEquals(VIDEO_WALLPAPER_SCALE_MODE_FIT, mirrored.scaleMode)
        assertEquals(FitCanvasMode.BLURRED_EDGE, mirrored.canvasStyle.mode)
        assertEquals(0xFF654321.toInt(), mirrored.canvasStyle.customColor)

        val raw = context.getSharedPreferences(VIDEO_WALLPAPER_PREFS_NAME, Context.MODE_PRIVATE)
        assertEquals(WALLPAPER_PRESENTATION_FIT, raw.getString(VIDEO_WALLPAPER_DEFAULT_SCALE_MODE_PREF, null))
        assertEquals(
            FitCanvasMode.BLURRED_EDGE.preferenceValue,
            raw.getString(VIDEO_WALLPAPER_DEFAULT_CANVAS_MODE_PREF, null),
        )
        assertEquals(0xFF654321.toInt(), raw.getInt(VIDEO_WALLPAPER_DEFAULT_CANVAS_COLOR_PREF, 0))

        manager.restoreFitCanvasPreferences(FitCanvasPreferences())
        assertEquals(WALLPAPER_PRESENTATION_FILL, manager.videoWallpaperPresentation.first())
    }
}
