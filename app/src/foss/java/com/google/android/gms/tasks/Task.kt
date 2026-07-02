package com.google.android.gms.tasks

open class Task<TResult>(
    private val value: TResult? = null,
    private val failure: Exception? = null,
) {
    val isSuccessful: Boolean get() = failure == null
    @Suppress("UNCHECKED_CAST")
    val result: TResult
        get() = failure?.let { throw it } ?: value as TResult
    val exception: Exception? get() = failure

    fun addOnSuccessListener(listener: (TResult) -> Unit): Task<TResult> {
        if (failure == null && value != null) listener(value)
        return this
    }

    fun addOnFailureListener(listener: (Exception) -> Unit): Task<TResult> {
        failure?.let(listener)
        return this
    }

    fun addOnCanceledListener(listener: () -> Unit): Task<TResult> = this

    fun addOnCompleteListener(listener: (Task<TResult>) -> Unit): Task<TResult> {
        listener(this)
        return this
    }

    companion object {
        fun <TResult> success(value: TResult? = null): Task<TResult> = Task(value = value)
        fun <TResult> failure(error: Exception = fossUnavailable()): Task<TResult> = Task(failure = error)
    }
}

internal fun fossUnavailable(): IllegalStateException =
    IllegalStateException("This Google/Firebase feature is unavailable in the FOSS build.")
