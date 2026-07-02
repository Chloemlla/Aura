package com.google.firebase.appcheck

class FirebaseAppCheck private constructor() {
    fun installAppCheckProviderFactory(factory: Any) = Unit

    companion object {
        fun getInstance(): FirebaseAppCheck = FirebaseAppCheck()
    }
}
