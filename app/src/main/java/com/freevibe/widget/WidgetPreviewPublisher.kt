package com.freevibe.widget

import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import androidx.collection.intSetOf
import androidx.annotation.RequiresApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.freevibe.BuildConfig

internal object WidgetPreviewPublisher {
    private const val PREFS_NAME = "widget_preview_state"
    private const val KEY_PUBLISHED_VERSION = "published_version"

    internal fun shouldPublish(
        sdkInt: Int,
        publishedVersion: Int,
        currentVersion: Int,
    ): Boolean = sdkInt >= Build.VERSION_CODES.VANILLA_ICE_CREAM && publishedVersion != currentVersion

    suspend fun publishIfNeeded(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val publishedVersion = prefs.getInt(KEY_PUBLISHED_VERSION, -1)
        if (!shouldPublish(Build.VERSION.SDK_INT, publishedVersion, BuildConfig.VERSION_CODE)) return

        publishApi35(context, prefs)
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private suspend fun publishApi35(
        context: Context,
        prefs: android.content.SharedPreferences,
    ) {
        val result = GlanceAppWidgetManager(context).setWidgetPreviews(
            receiver = FreeVibeWidgetReceiver::class,
            widgetCategories = intSetOf(
                AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN,
                AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD,
            ),
        )
        if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) {
            prefs.edit().putInt(KEY_PUBLISHED_VERSION, BuildConfig.VERSION_CODE).apply()
        }
    }
}
