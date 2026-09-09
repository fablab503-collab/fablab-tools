package com.fablab503.velotrack.recording

import com.fablab503.velotrack.model.RideState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide ride state shared between [RecordingService] and the UI.
 * The service writes; activities collect. Everything runs in one process, so no binder is needed.
 */
object RideSession {
    private val _state = MutableStateFlow(RideState())
    val state: StateFlow<RideState> get() = _state

    /** True while RecordingService is alive (set by the service in onCreate/onDestroy). */
    @Volatile
    var serviceRunning: Boolean = false

    fun update(transform: (RideState) -> RideState) = _state.update(transform)

    fun reset() {
        _state.value = RideState()
    }
}
