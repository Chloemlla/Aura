package com.freevibe.service

import com.freevibe.R
import com.freevibe.data.legal.providerDisclosuresBySource
import com.freevibe.data.model.ContentSource
import com.freevibe.data.model.Sound
import javax.inject.Inject
import javax.inject.Singleton

/** Provides the offline Aura Originals tone pack included with every build. */
@Singleton
class BundledContentProvider @Inject constructor() {

    fun getRingtones(): List<Sound> = RINGTONES.map(::toSound)

    fun getNotifications(): List<Sound> = NOTIFICATIONS.map(::toSound)

    fun getAlarms(): List<Sound> = ALARMS.map(::toSound)

    private fun toSound(spec: BundledSoundSpec): Sound {
        val locator = "rawresource:///${spec.resourceId}"
        return Sound(
            id = spec.id,
            source = ContentSource.BUNDLED,
            name = spec.name,
            description = "Aura Originals · Available offline",
            previewUrl = locator,
            downloadUrl = locator,
            duration = spec.duration,
            sampleRate = 22_050,
            fileType = "audio/ogg",
            tags = spec.tags,
            license = "CC0 1.0",
            uploaderName = "Aura",
            sourcePageUrl = checkNotNull(providerDisclosuresBySource[ContentSource.BUNDLED]).termsUrl,
        )
    }

    private data class BundledSoundSpec(
        val id: String,
        val name: String,
        val resourceId: Int,
        val duration: Double,
        val tags: List<String>,
    )

    private companion object {
        val RINGTONES = listOf(
            BundledSoundSpec("bundled_ringtone_01", "Crystal Chime", R.raw.aura_ringtone_crystal_chime, 11.73, listOf("ringtone", "chime", "crystal", "calm")),
            BundledSoundSpec("bundled_ringtone_02", "Bright Orbit", R.raw.aura_ringtone_bright_orbit, 11.04, listOf("ringtone", "bright", "melody", "clean")),
            BundledSoundSpec("bundled_ringtone_03", "Soft Marimba", R.raw.aura_ringtone_soft_marimba, 12.42, listOf("ringtone", "marimba", "soft", "warm")),
            BundledSoundSpec("bundled_ringtone_04", "Digital Pulse", R.raw.aura_ringtone_digital_pulse, 10.20, listOf("ringtone", "digital", "pulse", "modern")),
            BundledSoundSpec("bundled_ringtone_05", "Garden Echo", R.raw.aura_ringtone_garden_echo, 12.07, listOf("ringtone", "gentle", "echo", "calm")),
            BundledSoundSpec("bundled_ringtone_06", "Glass Bells", R.raw.aura_ringtone_glass_bells, 11.73, listOf("ringtone", "bells", "glass", "bright")),
            BundledSoundSpec("bundled_ringtone_07", "Warm Arpeggio", R.raw.aura_ringtone_warm_arpeggio, 9.86, listOf("ringtone", "synth", "arpeggio", "warm")),
            BundledSoundSpec("bundled_ringtone_08", "Music Box", R.raw.aura_ringtone_music_box, 11.38, listOf("ringtone", "music box", "gentle", "melody")),
            BundledSoundSpec("bundled_ringtone_09", "Mellow Air", R.raw.aura_ringtone_mellow_air, 14.25, listOf("ringtone", "mellow", "airy", "calm")),
            BundledSoundSpec("bundled_ringtone_10", "Xylophone Cascade", R.raw.aura_ringtone_xylophone_cascade, 9.52, listOf("ringtone", "xylophone", "cascade", "playful")),
        )

        val NOTIFICATIONS = listOf(
            BundledSoundSpec("bundled_notif_01", "Soft Pop", R.raw.aura_notification_soft_pop, 0.66, listOf("notification", "pop", "soft", "short")),
            BundledSoundSpec("bundled_notif_02", "Gentle Ding", R.raw.aura_notification_gentle_ding, 1.25, listOf("notification", "ding", "gentle", "clean")),
            BundledSoundSpec("bundled_notif_03", "Water Drop", R.raw.aura_notification_water_drop, 0.68, listOf("notification", "water", "drop", "minimal")),
            BundledSoundSpec("bundled_notif_04", "Bubble Click", R.raw.aura_notification_bubble_click, 0.62, listOf("notification", "click", "bubble", "short")),
            BundledSoundSpec("bundled_notif_05", "Bright Ping", R.raw.aura_notification_bright_ping, 1.35, listOf("notification", "ping", "bright", "clean")),
            BundledSoundSpec("bundled_notif_06", "Wooden Knock", R.raw.aura_notification_wooden_knock, 0.63, listOf("notification", "wood", "knock", "natural")),
            BundledSoundSpec("bundled_notif_07", "Chime Alert", R.raw.aura_notification_chime_alert, 1.54, listOf("notification", "chime", "alert", "melodic")),
            BundledSoundSpec("bundled_notif_08", "Subtle Beep", R.raw.aura_notification_subtle_beep, 0.58, listOf("notification", "beep", "subtle", "short")),
            BundledSoundSpec("bundled_notif_09", "Glass Tap", R.raw.aura_notification_glass_tap, 0.62, listOf("notification", "glass", "tap", "crisp")),
            BundledSoundSpec("bundled_notif_10", "Echo Blip", R.raw.aura_notification_echo_blip, 0.78, listOf("notification", "echo", "blip", "digital")),
        )

        val ALARMS = listOf(
            BundledSoundSpec("bundled_alarm_01", "Sunrise Bells", R.raw.aura_alarm_sunrise_bells, 15.96, listOf("alarm", "bells", "sunrise", "gentle")),
            BundledSoundSpec("bundled_alarm_02", "Morning Chorus", R.raw.aura_alarm_morning_chorus, 12.24, listOf("alarm", "morning", "melody", "bright")),
            BundledSoundSpec("bundled_alarm_03", "Radar Pulse", R.raw.aura_alarm_radar_pulse, 14.40, listOf("alarm", "radar", "pulse", "urgent")),
            BundledSoundSpec("bundled_alarm_04", "Ascending Chimes", R.raw.aura_alarm_ascending_chimes, 13.50, listOf("alarm", "chimes", "ascending", "wake")),
            BundledSoundSpec("bundled_alarm_05", "Classic Bells", R.raw.aura_alarm_classic_bells, 21.60, listOf("alarm", "bell", "classic", "ring")),
        )
    }
}
