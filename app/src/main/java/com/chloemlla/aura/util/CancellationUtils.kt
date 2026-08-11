package com.chloemlla.aura.util

import kotlinx.coroutines.CancellationException

internal fun Throwable.rethrowIfCancelled() {
    if (this is CancellationException) throw this
}
