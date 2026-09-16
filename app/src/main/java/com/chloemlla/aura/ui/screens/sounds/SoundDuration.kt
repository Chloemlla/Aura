package com.chloemlla.aura.ui.screens.sounds

internal fun formatSoundFeedDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0.0) return "0:00"
    if (seconds < 1.0) return "<1s"
    val total = seconds.toInt()
    val minutes = total / 60
    val remainingSeconds = total % 60
    return "$minutes:${remainingSeconds.toString().padStart(2, '0')}"
}

internal fun formatSoundDetailDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0.0) return "0s"
    if (seconds < 1.0) return "<1s"
    val total = seconds.toInt()
    val minutes = total / 60
    val remainingSeconds = total % 60
    return if (minutes > 0) "${minutes}m ${remainingSeconds}s" else "${remainingSeconds}s"
}
