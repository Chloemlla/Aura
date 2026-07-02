package com.google.firebase.auth

import com.google.android.gms.tasks.Task

class FirebaseAuth private constructor() {
    val currentUser: FirebaseUser? = null

    fun signInAnonymously(): Task<AuthResult> = Task.failure()

    companion object {
        fun getInstance(): FirebaseAuth = FirebaseAuth()
    }
}

class FirebaseUser {
    val uid: String = ""
    val displayName: String? = null
    val isAnonymous: Boolean = false

    fun getIdToken(forceRefresh: Boolean): Task<GetTokenResult> = Task.failure()
}

class AuthResult {
    val user: FirebaseUser? = null
}

class GetTokenResult {
    val claims: Map<String, Any> = emptyMap()
}
