package com.chloemlla.aura.data.model

import java.util.Locale

/**
 * Only these say the item itself is gone: an explicit removal, or the HTTP codes
 * that mean "this resource no longer exists".
 */
private val REMOTE_REMOVED_REGEX = Regex(
    pattern = """\b(?:HTTP\s*)?(?:404|410)\b|content not found|not found|gone|removed|deleted""",
    option = RegexOption.IGNORE_CASE,
)

/**
 * Codes that mean "not right now": auth/permission, throttling, and server or
 * transport trouble. RFC 9110 is explicit that 403 is about *this* request, not
 * about the resource ceasing to exist, so none of these may permanently disable
 * an item.
 */
private val REMOTE_TRANSIENT_REGEX = Regex(
    pattern = """\b(?:HTTP\s*)?(?:401|403|408|425|429|5\d{2})\b|timeout|timed out|unreachable|""" +
        """connection reset|failed to connect|no route to host|unable to resolve host|rate limit""",
    option = RegexOption.IGNORE_CASE,
)

/** How durable a fetch failure is. */
enum class SourceAvailabilityVerdict {
    /** The remote item is gone. Safe to mark the saved record unavailable. */
    PERMANENT,

    /**
     * The item may well be fine; this attempt was refused, throttled, or could
     * not reach the server. Never persist an unavailable state for these.
     */
    TRANSIENT,

    /** Nothing in the failure identifies it either way. */
    UNKNOWN,
}

/**
 * A failure classified for persistence, with the user-facing reason to store
 * when — and only when — the verdict is [SourceAvailabilityVerdict.PERMANENT].
 */
data class SourceAvailabilityAssessment(
    val verdict: SourceAvailabilityVerdict,
    val reason: String?,
) {
    /** True when the saved record should be flagged unavailable on disk. */
    val isPermanent: Boolean get() = verdict == SourceAvailabilityVerdict.PERMANENT
}

fun assessSourceAvailability(
    source: ContentSource,
    failure: Throwable?,
): SourceAvailabilityAssessment = assessSourceAvailability(source.name, failure?.message)

fun assessSourceAvailability(
    source: String,
    message: String?,
): SourceAvailabilityAssessment {
    val sourceName = source.uppercase(Locale.ROOT)
    val raw = message.orEmpty()

    // Removal wins over transience: a response can mention both, and "gone" is
    // the more specific claim.
    if (REMOTE_REMOVED_REGEX.containsMatchIn(raw)) {
        return SourceAvailabilityAssessment(
            verdict = SourceAvailabilityVerdict.PERMANENT,
            reason = permanentReasonFor(sourceName, raw),
        )
    }
    if (REMOTE_TRANSIENT_REGEX.containsMatchIn(raw)) {
        return SourceAvailabilityAssessment(SourceAvailabilityVerdict.TRANSIENT, null)
    }
    return SourceAvailabilityAssessment(SourceAvailabilityVerdict.UNKNOWN, null)
}

/**
 * The reason to persist for a genuinely removed item.
 *
 * Returns null for anything transient, so callers cannot accidentally persist an
 * unavailable state for a 403 or a timeout.
 */
fun sourceUnavailableReasonForFailure(
    source: ContentSource,
    failure: Throwable?,
): String? = assessSourceAvailability(source, failure).takeIf { it.isPermanent }?.reason

fun sourceUnavailableReasonForFailure(
    source: String,
    failure: Throwable?,
): String? = assessSourceAvailability(source, failure?.message).takeIf { it.isPermanent }?.reason

fun sourceUnavailableReasonForMessage(
    source: String,
    message: String?,
): String? = assessSourceAvailability(source, message).takeIf { it.isPermanent }?.reason

private fun permanentReasonFor(sourceName: String, raw: String): String =
    when (sourceName) {
        ContentSource.REDDIT.name -> "Source post is unavailable or removed"
        ContentSource.PEXELS.name -> "Pexels media is unavailable or removed"
        ContentSource.PIXABAY.name -> "Pixabay media is unavailable or removed"
        ContentSource.YOUTUBE.name -> "YouTube media is unavailable or removed"
        ContentSource.COMMUNITY.name -> "Community upload is unavailable or removed"
        ContentSource.FREESOUND.name -> "Freesound media is unavailable or removed"
        ContentSource.SOUNDCLOUD.name -> "SoundCloud media is unavailable or removed"
        else -> "Source content is unavailable or removed"
    }.let { reason ->
        if (raw.contains("410")) "$reason (gone)" else reason
    }
