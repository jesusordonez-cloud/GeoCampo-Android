package mx.org.fraac.geocampo

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

object TrackingBus {
    private val _state = MutableStateFlow(LiveTrackingState())
    val state = _state.asStateFlow()

    private val _points = MutableSharedFlow<TrackPoint>(extraBufferCapacity = 64)
    val points = _points.asSharedFlow()

    fun start(trackId: Long, startedAt: Long) {
        _state.value = LiveTrackingState(isTracking = true, trackId = trackId, startedAt = startedAt)
    }

    fun push(point: TrackPoint, totalDistance: Double) {
        _state.value = _state.value.copy(lastPoint = point, distanceMeters = totalDistance)
        _points.tryEmit(point)
    }

    fun stop() {
        _state.value = LiveTrackingState()
    }
}
