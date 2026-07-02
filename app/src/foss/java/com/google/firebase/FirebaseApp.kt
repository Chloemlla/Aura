package com.google.firebase

import android.content.Context

class FirebaseApp private constructor() {
    companion object {
        fun initializeApp(context: Context): FirebaseApp? = null
    }
}
