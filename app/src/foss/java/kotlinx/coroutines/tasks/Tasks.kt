package kotlinx.coroutines.tasks

import com.google.android.gms.tasks.Task

suspend fun <T> Task<T>.await(): T {
    exception?.let { throw it }
    return result
}
