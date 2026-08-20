package com.freevibe.service

import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * Bounds every yt-dlp download before a byte is written.
 *
 * The size ceiling used to be enforced only after the file had been written to
 * `filesDir` in full, so a long video wrote gigabytes to a phone and was then
 * rejected. `--max-filesize` makes yt-dlp refuse the format up front instead, so
 * the ceiling costs nothing to enforce.
 *
 * Apply this to every request that downloads media. Requests that only resolve
 * metadata do not write media and do not need it.
 */
internal fun applyYtDlpDownloadBounds(
    request: YoutubeDLRequest,
    maxBytes: Long = MAX_VIDEO_WALLPAPER_BYTES,
) {
    request.addOption("--max-filesize", maxBytes.toString())
    // A single item is always what the caller asked for; without this a playlist
    // URL would expand into an unbounded number of downloads, each individually
    // under the size cap.
    request.addOption("--no-playlist")
}
