package com.google.firebase.appcheck.debug

class DebugAppCheckProviderFactory private constructor() {
    companion object {
        fun getInstance(): DebugAppCheckProviderFactory = DebugAppCheckProviderFactory()
    }
}
