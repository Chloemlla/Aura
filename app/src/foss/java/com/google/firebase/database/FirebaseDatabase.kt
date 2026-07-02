package com.google.firebase.database

import com.google.android.gms.tasks.Task

class FirebaseDatabase private constructor() {
    val reference: DatabaseReference = DatabaseReference()

    companion object {
        fun getInstance(): FirebaseDatabase = FirebaseDatabase()
    }
}

open class Query {
    open fun get(): Task<DataSnapshot> = Task.failure()
    open fun limitToLast(limit: Int): Query = this
    open fun orderByChild(path: String): Query = this
    open fun equalTo(value: String): Query = this
    open fun addValueEventListener(listener: ValueEventListener) = Unit
    open fun removeEventListener(listener: ValueEventListener) = Unit
}

class DatabaseReference : Query() {
    fun child(path: String): DatabaseReference = this
    fun setValue(value: Any?): Task<Void?> = Task.failure()
    fun removeValue(): Task<Void?> = Task.failure()
    fun updateChildren(update: Map<String, Any?>): Task<Void?> = Task.failure()
    override fun limitToLast(limit: Int): DatabaseReference = this
    override fun orderByChild(path: String): DatabaseReference = this
    override fun equalTo(value: String): DatabaseReference = this
}

class DataSnapshot {
    val key: String? = null
    val children: Iterable<DataSnapshot> = emptyList()

    fun exists(): Boolean = false
    fun child(path: String): DataSnapshot = this
    fun <T> getValue(valueType: Class<T>): T? = null
}

class DatabaseError {
    val message: String = "Firebase Database is unavailable in the FOSS build."
    fun toException(): Exception = IllegalStateException(message)
}

interface ValueEventListener {
    fun onDataChange(snapshot: DataSnapshot)
    fun onCancelled(error: DatabaseError)
}
