package com.freevibe.service

/**
 * Recognises a kill by Android 17's per-app memory limiter.
 *
 * Android 17 applies a RAM-derived memory ceiling to **every** app, regardless of
 * `targetSdk`, and Aura is the profile it aims at: a 4096 px editor render path,
 * a 64 MB apply ceiling, and three long-lived wallpaper engines each holding
 * bitmap layers. The platform records such a kill through the ordinary
 * `ApplicationExitInfo` history, so without naming it a limiter kill is
 * indistinguishable from any other death in the bundle a user pastes into a bug
 * report — which is the whole problem.
 *
 * Detection is on the free-text description, which is what the platform
 * documents. The reason code is deliberately not part of the match: the platform
 * makes no promise about which code it pairs the description with, and a match
 * that guessed wrong would go quietly false rather than loudly.
 */
internal object AndroidMemoryLimiter {

    /** Prefix the platform writes into `ApplicationExitInfo.getDescription()`. */
    const val DESCRIPTION_MARKER = "MemoryLimiter"

    /** The documented variant: the process was killed over anonymous-swap pressure. */
    const val ANON_SWAP_VARIANT = "MemoryLimiter:AnonSwap"

    /** Plain-language name for the bundle, so a reader does not have to know the marker. */
    const val EXPLANATION = "Android 17 per-app memory limiter"

    fun isMemoryLimiterExit(description: String?): Boolean =
        description != null && description.contains(DESCRIPTION_MARKER, ignoreCase = true)

    /**
     * A suffix naming the limiter, or an empty string for an ordinary exit, so a
     * caller can append it to an exit line unconditionally.
     */
    fun annotate(description: String?): String =
        if (isMemoryLimiterExit(description)) " <- $EXPLANATION" else ""

    fun countMemoryLimiterExits(descriptions: List<String?>): Int =
        descriptions.count { isMemoryLimiterExit(it) }
}

/**
 * What the wallpaper editor can be holding at its worst moment, and the ceiling
 * that has to stay below.
 *
 * The Android 17 limiter turns "roughly how much does the editor allocate" from a
 * performance question into a survival one, and the answer was previously implied
 * by a single dimension constant with no arithmetic attached to it. Recording the
 * ceiling here means raising the dimension cap fails a test rather than shipping.
 */
internal object WallpaperEditorMemoryBudget {

    /** Longest edge the editor decodes to. Mirrors `WallpaperEditorViewModel`. */
    const val MAX_EDIT_LONG_EDGE = 4096

    /** `ARGB_8888`, which is what every editor surface decodes and renders into. */
    const val BYTES_PER_PIXEL = 4

    /**
     * Bitmaps alive at the same instant during a filter render: the source being
     * read and the result being written. A third would mean the displaced bitmap
     * is being orphaned rather than recycled.
     */
    const val CONCURRENT_BITMAPS = 2

    /**
     * The recorded ceiling: 4096x4096 ARGB_8888 is 64 MiB, two of them 128 MiB.
     * A device at the limiter's floor gets roughly this much headroom in total, so
     * this is the point past which the editor is gambling with the process.
     */
    const val PEAK_ALLOCATION_CEILING_BYTES = 128L * 1024 * 1024

    /** Bytes one decoded bitmap of these dimensions occupies. */
    fun bitmapBytes(width: Int, height: Int): Long =
        width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong() * BYTES_PER_PIXEL

    /** Worst-case bytes live at once for a source of these dimensions. */
    fun peakAllocationBytes(
        width: Int,
        height: Int,
        concurrentBitmaps: Int = CONCURRENT_BITMAPS,
    ): Long = bitmapBytes(width, height) * concurrentBitmaps.coerceAtLeast(0)

    /** Worst case the dimension cap admits: a square image at the long-edge limit. */
    fun worstCasePeakBytes(): Long = peakAllocationBytes(MAX_EDIT_LONG_EDGE, MAX_EDIT_LONG_EDGE)

    fun withinCeiling(bytes: Long): Boolean = bytes <= PEAK_ALLOCATION_CEILING_BYTES

    fun describe(): String =
        "editor peak ${formatMebibytes(worstCasePeakBytes())} of " +
            "${formatMebibytes(PEAK_ALLOCATION_CEILING_BYTES)} ceiling " +
            "(${MAX_EDIT_LONG_EDGE}px long edge, $CONCURRENT_BITMAPS bitmaps live)"

    private fun formatMebibytes(bytes: Long): String = "${bytes / (1024 * 1024)} MiB"
}
