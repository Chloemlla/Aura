package com.chloemlla.aura.data.repository

internal data class RedditRssMediaEntry(
    val id: String,
    val title: String,
    val author: String,
    val subreddit: String,
    val sourcePageUrl: String,
    val thumbnailUrl: String,
    val mediaUrl: String,
    val width: Int,
    val height: Int,
) {
    val isAnimated: Boolean
        get() = mediaUrl.substringBefore('?').lowercase().let { url ->
            url.endsWith(".gif") || url.endsWith(".mp4") || url.endsWith(".webm") ||
                url.contains("v.redd.it/")
    }
}

/**
 * A page from Reddit's public Atom surface.
 *
 * Reddit does not emit a rel=next link, but the listing endpoint accepts the final
 * fullname (`t3_...`) as an `after` cursor. Keep the cursor from the final raw Atom
 * entry rather than the final media entry so text/link posts do not make the next
 * request overlap the tail of this page.
 */
internal data class RedditRssPage(
    val entries: List<RedditRssMediaEntry>,
    val nextAfter: String?,
    val rawEntryCount: Int,
)

internal fun parseRedditRssMedia(
    xml: String,
    fallbackSubreddit: String,
): List<RedditRssMediaEntry> = parseRedditRssPage(xml, fallbackSubreddit).entries

internal fun parseRedditRssPage(
    xml: String,
    fallbackSubreddit: String,
): RedditRssPage {
    val rawEntries = REDDIT_RSS_ENTRY_REGEX.findAll(xml)
        .map { it.groupValues[1] }
        .toList()
    val entries = rawEntries
        .mapNotNull { rawEntry -> parseRedditRssEntry(rawEntry, fallbackSubreddit) }
        .distinctBy { it.id }
    val nextAfter = rawEntries.lastOrNull()
        ?.let(REDDIT_RSS_ID_REGEX::find)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
        ?.takeIf { it.matches(REDDIT_FULLNAME_REGEX) }
    return RedditRssPage(
        entries = entries,
        nextAfter = nextAfter,
        rawEntryCount = rawEntries.size,
    )
}

private fun parseRedditRssEntry(
    rawEntry: String,
    fallbackSubreddit: String,
): RedditRssMediaEntry? {
    val decodedEntry = decodeRedditRssEntities(rawEntry)
    val content = REDDIT_RSS_CONTENT_REGEX.find(decodedEntry)?.groupValues?.getOrNull(1).orEmpty()
    val links = REDDIT_RSS_URL_REGEX.findAll(content)
        .map { decodeRedditRssEntities(it.groupValues[1]) }
        .toList()
    val directMedia = links.firstOrNull(::isRedditDirectMediaUrl)
    val thumbnail = REDDIT_RSS_THUMBNAIL_REGEX.find(decodedEntry)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::decodeRedditRssEntities)
        .orEmpty()
    // Gallery/link posts frequently expose only a 140px preview in Atom. That is
    // useful as decoration but must never become the full/apply URL; keep only posts
    // with a direct original or directly playable Reddit media URL.
    val mediaUrl = directMedia ?: return null
    val title = REDDIT_RSS_TITLE_REGEX.find(decodedEntry)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::decodeRedditRssEntities)
        ?.trim()
        .orEmpty()
    if (title.isBlank()) return null
    val id = REDDIT_RSS_ID_REGEX.find(decodedEntry)
        ?.groupValues
        ?.getOrNull(1)
        ?.removePrefix("t3_")
        ?.trim()
        .orEmpty()
    if (id.isBlank()) return null
    val sourcePageUrl = REDDIT_RSS_POST_LINK_REGEX.find(decodedEntry)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::decodeRedditRssEntities)
        .orEmpty()
    val author = REDDIT_RSS_AUTHOR_REGEX.find(decodedEntry)
        ?.groupValues
        ?.getOrNull(1)
        ?.let(::decodeRedditRssEntities)
        ?.removePrefix("/u/")
        .orEmpty()
    val subreddit = REDDIT_RSS_CATEGORY_REGEX.find(decodedEntry)
        ?.groupValues
        ?.getOrNull(1)
        ?.ifBlank { fallbackSubreddit }
        ?.removePrefix("r/")
        ?: fallbackSubreddit.removePrefix("r/")
    val resolution = REDDIT_RSS_RESOLUTION_REGEX.find(title)
    return RedditRssMediaEntry(
        id = id,
        title = title,
        author = author,
        subreddit = subreddit,
        sourcePageUrl = sourcePageUrl,
        thumbnailUrl = thumbnail.ifBlank { mediaUrl },
        mediaUrl = mediaUrl,
        width = resolution?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0,
        height = resolution?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0,
    )
}

private fun isRedditDirectMediaUrl(url: String): Boolean {
    val normalized = url.substringBefore('?').lowercase()
    if (normalized.contains("preview.redd.it/") || normalized.contains("external-preview.redd.it/")) return false
    return normalized.contains("i.redd.it/") ||
        normalized.contains("v.redd.it/") ||
        normalized.endsWith(".jpg") || normalized.endsWith(".jpeg") ||
        normalized.endsWith(".png") || normalized.endsWith(".webp") ||
        normalized.endsWith(".gif") || normalized.endsWith(".mp4") ||
        normalized.endsWith(".webm")
}

private fun decodeRedditRssEntities(value: String): String {
    var decoded = value
    repeat(3) {
        decoded = decoded
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&#32;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
    }
    return decoded
}

private val REDDIT_RSS_ENTRY_REGEX = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
private val REDDIT_RSS_CONTENT_REGEX = Regex("<content[^>]*>(.*?)</content>", RegexOption.DOT_MATCHES_ALL)
private val REDDIT_RSS_URL_REGEX = Regex("(?:href|src)=\"((?:https?):" + "//[^\"]+)\"", RegexOption.IGNORE_CASE)
private val REDDIT_RSS_THUMBNAIL_REGEX = Regex("<media:thumbnail[^>]*url=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
private val REDDIT_RSS_TITLE_REGEX = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL)
private val REDDIT_RSS_ID_REGEX = Regex("<id>([^<]+)</id>")
private val REDDIT_FULLNAME_REGEX = Regex("t3_[a-zA-Z0-9]+")
private val REDDIT_RSS_POST_LINK_REGEX = Regex("<link[^>]*href=\"((?:https?):" + "//www\\.reddit\\.com/r/[^\"]+/comments/[^\"]+)\"")
private val REDDIT_RSS_AUTHOR_REGEX = Regex("<author>.*?<name>(.*?)</name>.*?</author>", RegexOption.DOT_MATCHES_ALL)
private val REDDIT_RSS_CATEGORY_REGEX = Regex("<category[^>]*term=\"([^\"]+)\"")
private val REDDIT_RSS_RESOLUTION_REGEX = Regex("(\\d{3,5})\\s*[xX×]\\s*(\\d{3,5})")
