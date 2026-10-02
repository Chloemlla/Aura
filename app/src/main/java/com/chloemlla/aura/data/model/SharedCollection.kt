package com.chloemlla.aura.data.model

const val SHARED_COLLECTION_VERSION = 1

/** Server limits for a collection link. A bigger collection still shares as a file. */
const val MAX_SHARED_COLLECTION_ITEMS = 250
const val MAX_SHARED_COLLECTION_DOCUMENT_BYTES = 512 * 1024

private const val MAX_SHARED_COLLECTION_NAME = 80
private val SHARED_COLLECTION_WHITESPACE_REGEX = Regex("\\s+")
private val SHARED_COLLECTION_CONTROL_REGEX = Regex("[\\u0000-\\u001F\\u007F]")

data class SharedCollectionInput(
    val document: String,
    val collectionName: String,
)

fun buildSharedCollectionCallablePayload(input: SharedCollectionInput): Map<String, Any> {
    require(input.document.isNotBlank()) { "Collection document is required" }
    require(input.document.toByteArray(Charsets.UTF_8).size <= MAX_SHARED_COLLECTION_DOCUMENT_BYTES) {
        "Collection is too large to share as a link"
    }
    return mapOf(
        "version" to SHARED_COLLECTION_VERSION,
        "document" to input.document,
        "collectionName" to input.collectionName
            .replace(SHARED_COLLECTION_CONTROL_REGEX, " ")
            .replace(SHARED_COLLECTION_WHITESPACE_REGEX, " ")
            .trim()
            .take(MAX_SHARED_COLLECTION_NAME),
    )
}
