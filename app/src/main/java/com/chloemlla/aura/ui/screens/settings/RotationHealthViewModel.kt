package com.chloemlla.aura.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chloemlla.aura.di.IoDispatcher
import com.chloemlla.aura.service.RotationHealthReader
import com.chloemlla.aura.service.RotationHealthSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * State for the Rotation Health screen.
 *
 * Deliberately its own ViewModel rather than more of `SettingsViewModel`, which
 * is already 1,000 lines and carries a tracked item to split it. Rotation health
 * shares no state with the rest of settings, so hanging it there would only make
 * that split harder.
 */
@HiltViewModel
class RotationHealthViewModel @Inject constructor(
    private val reader: RotationHealthReader,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _snapshot = MutableStateFlow<RotationHealthSnapshot?>(null)

    /** Null until the first read completes, which is what the screen shows as loading. */
    val snapshot: StateFlow<RotationHealthSnapshot?> = _snapshot.asStateFlow()

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _testFireRequested = MutableStateFlow(false)

    /** Set once a manual run is enqueued, so the screen can say so. */
    val testFireRequested: StateFlow<Boolean> = _testFireRequested.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _refreshing.value = true
        // WorkManager's unique-work query is a blocking future and the preference
        // reads touch DataStore, so none of this belongs on the main thread.
        _snapshot.value = withContext(ioDispatcher) { reader.read() }
        _refreshing.value = false
    }

    /**
     * Runs a rotation now and re-reads.
     *
     * The re-read is not the result of the run — the worker has not finished by
     * then — it just moves the state to RUNNING so the screen stops looking idle
     * while something is happening.
     */
    fun runNow() = viewModelScope.launch {
        withContext(ioDispatcher) { reader.runNow() }
        _testFireRequested.value = true
        refresh()
    }
}
