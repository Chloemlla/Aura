package com.google.firebase.functions

import com.google.android.gms.tasks.Task

class FirebaseFunctions private constructor() {
    fun getHttpsCallable(name: String): HttpsCallableReference = HttpsCallableReference(name)
    fun getHttpsCallable(name: String, options: HttpsCallableOptions): HttpsCallableReference =
        HttpsCallableReference(name)

    companion object {
        fun getInstance(): FirebaseFunctions = FirebaseFunctions()
    }
}

class HttpsCallableReference(private val name: String) {
    fun call(data: Map<String, Any>): Task<HttpsCallableResult> =
        Task.failure(
            FirebaseFunctionsException(
                code = FirebaseFunctionsException.Code.UNAVAILABLE,
                detailMessage = "Firebase Functions are unavailable in the FOSS build.",
                details = name,
            ),
        )
}

class HttpsCallableResult(val data: Any?)

class HttpsCallableOptions private constructor() {
    class Builder {
        fun setLimitedUseAppCheckTokens(enabled: Boolean): Builder = this
        fun build(): HttpsCallableOptions = HttpsCallableOptions()
    }
}

class FirebaseFunctionsException(
    val code: Code,
    detailMessage: String?,
    val details: Any? = null,
) : Exception(detailMessage) {
    enum class Code {
        OK,
        CANCELLED,
        UNKNOWN,
        INVALID_ARGUMENT,
        DEADLINE_EXCEEDED,
        NOT_FOUND,
        ALREADY_EXISTS,
        PERMISSION_DENIED,
        RESOURCE_EXHAUSTED,
        FAILED_PRECONDITION,
        ABORTED,
        OUT_OF_RANGE,
        UNIMPLEMENTED,
        INTERNAL,
        UNAVAILABLE,
        DATA_LOSS,
        UNAUTHENTICATED,
    }
}
