package com.freevibe.ui.screens.sounds

import androidx.annotation.StringRes
import com.freevibe.R

internal enum class SoundCollectionTone { MINIMAL, CALM, RETRO, NATURE, PUNCHY, MELODIC, SEASONAL }

internal data class SoundCollectionSpec(
    val title: String = "",
    val subtitle: String = "",
    @StringRes val titleRes: Int = 0,
    @StringRes val subtitleRes: Int = 0,
    val query: String,
    val tone: SoundCollectionTone,
)

internal fun soundCollectionsFor(tab: SoundTab): List<SoundCollectionSpec> = when (tab) {
    SoundTab.RINGTONES -> listOf(
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_minimal_rings_title,
            subtitleRes = R.string.sounds_collection_minimal_rings_body,
            query = "minimal clean ringtone tone",
            tone = SoundCollectionTone.MINIMAL,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_soft_chimes_title,
            subtitleRes = R.string.sounds_collection_soft_chimes_body,
            query = "soft chime ringtone melody",
            tone = SoundCollectionTone.CALM,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_retro_phones_title,
            subtitleRes = R.string.sounds_collection_retro_phones_body,
            query = "retro phone ringtone bell",
            tone = SoundCollectionTone.RETRO,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_nature_calls_title,
            subtitleRes = R.string.sounds_collection_nature_calls_body,
            query = "nature bird water ringtone",
            tone = SoundCollectionTone.NATURE,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_pulse_rings_title,
            subtitleRes = R.string.sounds_collection_pulse_rings_body,
            query = "electronic pulse ringtone",
            tone = SoundCollectionTone.MELODIC,
        ),
    )
    SoundTab.NOTIFICATIONS -> listOf(
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_tiny_ui_title,
            subtitleRes = R.string.sounds_collection_tiny_ui_body,
            query = "short ui notification click",
            tone = SoundCollectionTone.MINIMAL,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_glass_pings_title,
            subtitleRes = R.string.sounds_collection_glass_pings_body,
            query = "glass ping notification",
            tone = SoundCollectionTone.MELODIC,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_calm_alerts_title,
            subtitleRes = R.string.sounds_collection_calm_alerts_body,
            query = "calm soft notification chime",
            tone = SoundCollectionTone.CALM,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_nature_drops_title,
            subtitleRes = R.string.sounds_collection_nature_drops_body,
            query = "water drop wood notification",
            tone = SoundCollectionTone.NATURE,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_punchy_beeps_title,
            subtitleRes = R.string.sounds_collection_punchy_beeps_body,
            query = "punchy beep alert notification",
            tone = SoundCollectionTone.PUNCHY,
        ),
    )
    SoundTab.ALARMS -> listOf(
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_gentle_wake_title,
            subtitleRes = R.string.sounds_collection_gentle_wake_body,
            query = "gentle morning alarm chime",
            tone = SoundCollectionTone.CALM,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_classic_bells_title,
            subtitleRes = R.string.sounds_collection_classic_bells_body,
            query = "classic alarm bell ring",
            tone = SoundCollectionTone.RETRO,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_nature_morning_title,
            subtitleRes = R.string.sounds_collection_nature_morning_body,
            query = "nature morning alarm birds",
            tone = SoundCollectionTone.NATURE,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_deep_pulse_title,
            subtitleRes = R.string.sounds_collection_deep_pulse_body,
            query = "deep pulse alarm tone",
            tone = SoundCollectionTone.PUNCHY,
        ),
        SoundCollectionSpec(
            titleRes = R.string.sounds_collection_bright_rise_title,
            subtitleRes = R.string.sounds_collection_bright_rise_body,
            query = "bright melodic wake alarm",
            tone = SoundCollectionTone.MELODIC,
        ),
    )
    SoundTab.YOUTUBE,
    SoundTab.COMMUNITY,
    SoundTab.SEARCH -> emptyList()
}
