package com.chloemlla.aura.data.remote

import com.chloemlla.aura.data.model.*
import com.chloemlla.aura.data.remote.bing.BingDailyApi
import com.chloemlla.aura.data.remote.bing.BingImage
import com.chloemlla.aura.data.remote.lemmy.LemmyPostView
import com.chloemlla.aura.data.remote.nasa.NasaApodResponse
import com.chloemlla.aura.data.remote.pixabay.PixabayPhoto
import com.chloemlla.aura.data.remote.wallhaven.WallhavenWallpaper
import com.chloemlla.aura.data.remote.wikimedia.WikimediaPotdImage

// -- Wallhaven -> Wallpaper --

fun WallhavenWallpaper.toWallpaper() = Wallpaper(
    id = "wh_" + wallhavenStableId(),
    source = ContentSource.WALLHAVEN,
    thumbnailUrl = thumbs.large.ifEmpty { thumbs.original },
    fullUrl = path,
    width = dimensionX,
    height = dimensionY,
    category = category,
    tags = tags?.map { it.name } ?: emptyList(),
    colors = colors,
    // Wallhaven exposes no per-item license, so this stays blank on purpose and normalizes to
    // "Unknown" — the license gate must ask for confirmation instead of assuming a license.
    license = "",
    fileSize = fileSize,
    fileType = fileType,
    sourcePageUrl = url,
    views = views,
    favorites = favorites,
)

/** Derive a stable, non-empty id from the response id, falling back to a hash of the
 * wallpaper image/page URL so an empty id never reaches downstream (no blank-id filter exists). */
private fun WallhavenWallpaper.wallhavenStableId(): String =
    id.trim().takeIf { it.isNotEmpty() }
        ?: (path.ifBlank { url }.ifBlank { thumbs.large }).hashCode().toUInt().toString()

// -- Bing Daily -> Wallpaper --

private val BING_COPYRIGHT_REGEX = Regex("""\(([^)]+)\)""")

/** Matches the Bing entry in `ProviderDisclosure` (`licenseSummary = "Provider-defined image use"`). */
private const val BING_IMAGE_LICENSE = "Provider-defined image use"

fun BingImage.toWallpaper(bingBaseUrl: String = BingDailyApi.BASE_URL) = Wallpaper(
    id = "bing_${startDate}_${urlbase.hashCode().toUInt()}",
    source = ContentSource.BING,
    thumbnailUrl = BingDailyApi.thumbUrl(urlbase, bingBaseUrl),
    fullUrl = BingDailyApi.fullUrl(urlbase, bingBaseUrl),
    width = 3840,  // UHD
    height = 2160,
    category = "daily",
    tags = listOf("bing", "daily", "curated"),
    license = BING_IMAGE_LICENSE,
    sourcePageUrl = copyrightLink,
    uploaderName = BING_COPYRIGHT_REGEX.find(copyright)?.groupValues?.get(1)
        ?: copyright.take(80),
)

// -- NASA APOD -> Wallpaper --

/** Matches the NASA entry in `ProviderDisclosure` ("NASA media guidelines; some images have
 * third-party copyright"). Only APOD entries without a `copyright` line fall under the guidelines;
 * a third-party credit means the terms are unknown, so those keep a blank license and stay gated. */
private const val NASA_MEDIA_GUIDELINES_LICENSE = "NASA media guidelines"

fun NasaApodResponse.toWallpaper(): Wallpaper? {
    if (mediaType != "image") return null
    val imageUrl = hdUrl ?: url
    if (imageUrl.isBlank()) return null
    return Wallpaper(
        id = "nasa_apod_$date",
        source = ContentSource.NASA,
        thumbnailUrl = thumbnailUrl ?: url,
        fullUrl = imageUrl,
        width = 0,
        height = 0,
        category = "astronomy",
        tags = listOf("nasa", "apod", "astronomy", "space"),
        license = if (copyright.isNullOrBlank()) NASA_MEDIA_GUIDELINES_LICENSE else "",
        sourcePageUrl = date.takeIf { it.isNotBlank() }
            ?.let { "https://apod.nasa.gov/apod/ap${it.replace("-", "").drop(2)}.html" }
            ?: imageUrl,
        uploaderName = copyright?.trim() ?: "NASA",
    )
}

// -- Wikipedia POTD -> Wallpaper --

private val HTML_TAG_REGEX = Regex("<[^>]+>")

fun WikimediaPotdImage.toWallpaper(date: String): Wallpaper? {
    val fullSource = image?.source ?: return null
    if (fullSource.isBlank()) return null
    val thumbSource = thumbnail?.source ?: fullSource
    val artistName = artist?.text
        ?.replace(HTML_TAG_REGEX, "")
        ?.trim()
        ?.take(80)
        ?: "Wikimedia Commons"
    return Wallpaper(
        id = "wiki_potd_$date",
        source = ContentSource.WIKIMEDIA,
        thumbnailUrl = thumbSource,
        fullUrl = fullSource,
        width = image.width,
        height = image.height,
        category = "photography",
        tags = listOf("wikipedia", "potd", "featured", "commons"),
        // Per-file license lives in the Commons API's `extmetadata.LicenseShortName`, which
        // WikimediaPotdImage does not carry yet; until it does this stays blank and stays gated.
        license = "",
        sourcePageUrl = filePage ?: "",
        uploaderName = artistName,
    )
}

// -- Pixabay -> Wallpaper --

/** Matches the Pixabay entry in `ProviderDisclosure` (`licenseSummary = "Pixabay Content License"`). */
private const val PIXABAY_CONTENT_LICENSE = "Pixabay Content License"

fun PixabayPhoto.toWallpaper() = Wallpaper(
    id = "pb_$id",
    source = ContentSource.PIXABAY,
    thumbnailUrl = webformatUrl,
    fullUrl = largeImageUrl,
    width = imageWidth,
    height = imageHeight,
    tags = tags.split(",").map { it.trim() }.filter { it.isNotEmpty() },
    fileSize = imageSize,
    license = PIXABAY_CONTENT_LICENSE,
    sourcePageUrl = pageUrl,
    uploaderName = user,
    views = views,
    favorites = likes,
)

// -- Domain -> FavoriteEntity --

fun Wallpaper.toFavoriteEntity() = FavoriteEntity(
    id = id,
    source = source.name,
    type = "WALLPAPER",
    thumbnailUrl = thumbnailUrl,
    fullUrl = fullUrl,
    width = width,
    height = height,
    tags = tags.takeIf { it.isNotEmpty() }?.joinToString(" ||| "),
    colors = colors.takeIf { it.isNotEmpty() }?.joinToString(" ||| "),
    category = category.takeIf { it.isNotEmpty() },
    uploaderName = uploaderName.takeIf { it.isNotEmpty() },
    sourcePageUrl = sourcePageUrl.takeIf { it.isNotEmpty() },
    license = license.takeIf { it.isNotEmpty() },
    fileSize = fileSize.takeIf { it > 0 },
    fileType = fileType.takeIf { it.isNotEmpty() },
    views = views.toLong().takeIf { it > 0 },
    favoritesCount = favorites.toLong().takeIf { it > 0 },
    sourceAvailability = normalizeSourceAvailability(sourceAvailability),
    sourceAvailabilityReason = sourceAvailabilityReason.takeIf { it.isNotBlank() },
)

fun Sound.toFavoriteEntity() = FavoriteEntity(
    id = id,
    source = source.name,
    type = "SOUND",
    thumbnailUrl = "",
    fullUrl = when (source) {
        ContentSource.YOUTUBE -> sourcePageUrl.ifBlank { downloadUrl.ifBlank { previewUrl } }
        // Never persist the SoundCloud client_id: the signed URL is rebuilt from the track id
        // (still stored in this entity's id) when it needs to be streamed again.
        ContentSource.SOUNDCLOUD -> downloadUrl.ifBlank { previewUrl }.withoutQueryParam("client_id")
        else -> downloadUrl.ifBlank { previewUrl }
    },
    name = name,
    duration = duration,
    tags = tags.takeIf { it.isNotEmpty() }?.joinToString(" ||| "),
    category = null,
    uploaderName = uploaderName.takeIf { it.isNotEmpty() },
    sourcePageUrl = sourcePageUrl.takeIf { it.isNotEmpty() },
    license = license.takeIf { it.isNotEmpty() },
    fileSize = fileSize.takeIf { it > 0 },
    fileType = fileType.takeIf { it.isNotEmpty() },
    sourceAvailability = normalizeSourceAvailability(sourceAvailability),
    sourceAvailabilityReason = sourceAvailabilityReason.takeIf { it.isNotBlank() },
)

// -- FavoriteEntity -> Domain --

/** Parse a persisted source string into a [ContentSource], failing loudly on unknown values
 * instead of silently downgrading a favorite/cache entry to a wrong known source. */
internal fun String.parseContentSource(): ContentSource =
    try {
        ContentSource.valueOf(this)
    } catch (_: IllegalArgumentException) {
        error("Unknown ContentSource '$this'; refusing to silently downgrade content identity")
    }

fun FavoriteEntity.toWallpaper() = Wallpaper(
    id = id,
    source = source.parseContentSource(),
    thumbnailUrl = thumbnailUrl,
    fullUrl = offlinePath.ifBlank { fullUrl },
    width = width,
    height = height,
    tags = tags?.split(" ||| ")?.filter { it.isNotEmpty() } ?: emptyList(),
    colors = colors?.split(" ||| ")?.filter { it.isNotEmpty() } ?: emptyList(),
    category = category ?: "",
    uploaderName = uploaderName ?: "",
    sourcePageUrl = sourcePageUrl ?: "",
    license = license ?: "",
    fileSize = fileSize ?: 0L,
    fileType = fileType ?: "",
    views = views?.toInt() ?: 0,
    favorites = favoritesCount?.toInt() ?: 0,
    sourceAvailability = normalizeSourceAvailability(sourceAvailability),
    sourceAvailabilityReason = sourceAvailabilityReason ?: "",
)

fun FavoriteEntity.toSound(): Sound {
    val restoredSource = source.parseContentSource()
    val restoredSourcePageUrl = when {
        !sourcePageUrl.isNullOrBlank() -> sourcePageUrl
        restoredSource == ContentSource.YOUTUBE && fullUrl.isYouTubePageUrl() -> fullUrl
        else -> ""
    }
    val restoredDirectUrl = if (restoredSource == ContentSource.YOUTUBE) "" else fullUrl

    return Sound(
        id = id,
        source = restoredSource,
        name = name,
        previewUrl = restoredDirectUrl,
        downloadUrl = restoredDirectUrl,
        duration = duration,
        tags = tags?.split(" ||| ")?.filter { it.isNotEmpty() } ?: emptyList(),
        license = license ?: "",
        uploaderName = uploaderName ?: "",
        sourcePageUrl = restoredSourcePageUrl ?: "",
        fileSize = fileSize ?: 0L,
        fileType = fileType ?: "",
        sourceAvailability = normalizeSourceAvailability(sourceAvailability),
        sourceAvailabilityReason = sourceAvailabilityReason ?: "",
    )
}

private fun String.isYouTubePageUrl(): Boolean =
    contains("youtube.com", ignoreCase = true) || contains("youtu.be", ignoreCase = true)

/** Remove a query parameter from a URL, preserving the base and any other parameters. */
private fun String.withoutQueryParam(name: String): String {
    val queryStart = indexOf('?')
    if (queryStart < 0) return this
    val base = substring(0, queryStart)
    val keptQuery = substring(queryStart + 1)
        .split('&')
        .asSequence()
        .filter { it.isNotBlank() }
        .filterNot { it.substringBefore('=') == name }
        .joinToString("&")
    return if (keptQuery.isEmpty()) base else "$base?$keptQuery"
}

// -- Lemmy -> Wallpaper --

private val IMAGE_URL_REGEX = Regex("""(?i)\.(jpe?g|png|webp|gif|avif|heic)(\?.*)?$""")

fun LemmyPostView.toWallpaper(): Wallpaper? {
    val imageUrl = post.url ?: return null
    if (!IMAGE_URL_REGEX.containsMatchIn(imageUrl)) return null
    if (post.nsfw) return null
    return Wallpaper(
        id = "lemmy_${post.id}",
        source = ContentSource.LEMMY,
        thumbnailUrl = post.thumbnailUrl ?: imageUrl,
        fullUrl = imageUrl,
        width = 0,
        height = 0,
        category = "community",
        tags = listOf("lemmy", "community"),
        sourcePageUrl = post.apId.ifBlank { "https://lemmy.world/post/${post.id}" },
        uploaderName = creator.displayName ?: creator.name,
        views = counts.score,
        favorites = counts.upvotes,
    )
}

