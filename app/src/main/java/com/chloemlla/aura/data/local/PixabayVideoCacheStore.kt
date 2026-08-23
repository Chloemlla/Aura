package com.chloemlla.aura.data.local

import android.content.Context

/** Persistence boundary for the Pixabay feed cache; UI code must not own SharedPreferences. */
class PixabayVideoCacheStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    fun readString(key: String): String? = preferences.getString(key, null)

    fun writeString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    fun readLong(key: String, defaultValue: Long = 0L): Long =
        preferences.getLong(key, defaultValue)

    fun writeLong(key: String, value: Long) {
        preferences.edit().putLong(key, value).apply()
    }

    private companion object {
        const val PREFS_NAME = "freevibe_pixabay_video_cache"
    }
}
