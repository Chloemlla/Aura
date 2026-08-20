package com.freevibe.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import com.freevibe.R
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WallpaperClockOverlayTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(WALLPAPER_CLOCK_OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun labelUsesTheDeviceTimeAndDateFormats() {
        val now = Date(1_725_000_000_000L)
        val time = android.text.format.DateFormat.getTimeFormat(context).format(now)
        val date = android.text.format.DateFormat.getDateFormat(context).format(now)

        assertEquals(time, wallpaperClockOverlayLabel(context, WallpaperClockOverlayMode.TIME, now))
        assertEquals(date, wallpaperClockOverlayLabel(context, WallpaperClockOverlayMode.DATE, now))
        assertEquals(
            context.getString(R.string.wallpaper_clock_overlay_time_date, time, date),
            wallpaperClockOverlayLabel(context, WallpaperClockOverlayMode.TIME_AND_DATE, now),
        )
    }

    @Test
    fun disabledOverlayLeavesBitmapOwnedByCaller() {
        val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)

        val result = bitmapWithWallpaperClockOverlay(context, bitmap)

        assertSame(bitmap, result)
    }

    @Test
    fun enabledOverlayCopiesBitmapAndDrawsAtEachSafePosition() {
        val prefs = context.getSharedPreferences(WALLPAPER_CLOCK_OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(WALLPAPER_CLOCK_OVERLAY_ENABLED_PREF, true)
            .putString(WALLPAPER_CLOCK_OVERLAY_MODE_PREF, WallpaperClockOverlayMode.TIME.preferenceValue)
            .commit()

        WallpaperClockOverlayPosition.entries.forEach { position ->
            val bitmap = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            drawWallpaperClockOverlay(
                context = context,
                canvas = canvas,
                now = Date(1_725_000_000_000L),
                config = WallpaperClockOverlayConfig(
                    enabled = true,
                    mode = WallpaperClockOverlayMode.TIME,
                    position = position,
                ),
            )

            val copy = bitmapWithWallpaperClockOverlay(context, bitmap)
            assertNotSame(bitmap, copy)
            assertTrue(copy.width == bitmap.width && copy.height == bitmap.height)
            copy.recycle()
            bitmap.recycle()
        }
    }
}
