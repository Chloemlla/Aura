package com.chloemlla.aura.data.repository

import kotlinx.coroutines.CancellationException

enum class YouTubeExtractionEngine {
    NEWPIPE,
    YT_DLP,
}

enum class YouTubeExtractionMode {
    HEALTHY,
    BACKUP_ACTIVE,
    UNAVAILABLE,
}

data class YouTubeExtractionStatus(
    val mode: YouTubeExtractionMode = YouTubeExtractionMode.HEALTHY,
    val activeEngine: YouTubeExtractionEngine? = null,
    val failedEngine: YouTubeExtractionEngine? = null,
    val detail: String? = null,
)

internal data class YouTubeFailoverResult<T>(
    val value: T?,
    val engine: YouTubeExtractionEngine?,
    val primaryEngine: YouTubeExtractionEngine,
    val primaryError: Throwable?,
    val fallbackError: Throwable?,
) {
    val usedFallback: Boolean get() = value != null && engine != null && engine != primaryEngine

    fun toExtractionStatus(): YouTubeExtractionStatus = when {
        value == null -> YouTubeExtractionStatus(
            mode = YouTubeExtractionMode.UNAVAILABLE,
            failedEngine = primaryEngine,
            detail = listOfNotNull(primaryError, fallbackError)
                .joinToString("; ") { error ->
                    "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
                }
                .takeIf { it.isNotBlank() },
        )

        usedFallback -> YouTubeExtractionStatus(
            mode = YouTubeExtractionMode.BACKUP_ACTIVE,
            activeEngine = engine,
            failedEngine = primaryEngine,
            detail = primaryError?.let { error ->
                "${error.javaClass.simpleName}: ${error.message.orEmpty()}"
            },
        )

        else -> YouTubeExtractionStatus(
            mode = YouTubeExtractionMode.HEALTHY,
            activeEngine = engine,
        )
    }
}

internal class YouTubeExtractorReturnedNoResult(engine: YouTubeExtractionEngine) :
    IllegalStateException("${engine.displayName()} returned no usable result")

class YouTubeExtractionUnavailableException(
    primaryError: Throwable?,
    fallbackError: Throwable?,
) : IllegalStateException(
    "YouTube changed something. Both extractors failed; update the YouTube extractor or try again later.",
    fallbackError ?: primaryError,
)

internal suspend fun <T> executeYouTubeFailover(
    primaryEngine: YouTubeExtractionEngine,
    fallbackEngine: YouTubeExtractionEngine,
    primary: suspend () -> T?,
    fallback: suspend () -> T?,
    isUsable: (T) -> Boolean = { true },
): YouTubeFailoverResult<T> {
    val primaryAttempt = runYouTubeEngine(primaryEngine, primary, isUsable)
    if (primaryAttempt.value != null) {
        return YouTubeFailoverResult(
            value = primaryAttempt.value,
            engine = primaryEngine,
            primaryEngine = primaryEngine,
            primaryError = null,
            fallbackError = null,
        )
    }

    val fallbackAttempt = runYouTubeEngine(fallbackEngine, fallback, isUsable)
    return YouTubeFailoverResult(
        value = fallbackAttempt.value,
        engine = fallbackEngine.takeIf { fallbackAttempt.value != null },
        primaryEngine = primaryEngine,
        primaryError = primaryAttempt.error,
        fallbackError = fallbackAttempt.error,
    )
}

private data class YouTubeEngineAttempt<T>(
    val value: T? = null,
    val error: Throwable? = null,
)

private suspend fun <T> runYouTubeEngine(
    engine: YouTubeExtractionEngine,
    block: suspend () -> T?,
    isUsable: (T) -> Boolean,
): YouTubeEngineAttempt<T> = try {
    val value = block()
    if (value != null && isUsable(value)) {
        YouTubeEngineAttempt(value = value)
    } else {
        YouTubeEngineAttempt(error = YouTubeExtractorReturnedNoResult(engine))
    }
} catch (error: Throwable) {
    if (error is CancellationException) throw error
    YouTubeEngineAttempt(error = error)
}

fun YouTubeExtractionEngine.displayName(): String = when (this) {
    YouTubeExtractionEngine.NEWPIPE -> "NewPipe"
    YouTubeExtractionEngine.YT_DLP -> "yt-dlp"
}
