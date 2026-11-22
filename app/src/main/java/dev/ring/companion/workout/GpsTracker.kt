package dev.ring.companion.workout

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Distance and pace from the phone's GPS (the ring has no GPS).
 * Ignores fixes worse than 25 m and jitter under 3 m.
 */
@SuppressLint("MissingPermission")
class GpsTracker(context: Context) {
    data class State(val distanceM: Double = 0.0, val paceSecPerKm: Int? = null, val hasFix: Boolean = false)

    private val lm = context.getSystemService(LocationManager::class.java)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private var last: Location? = null
    private var lastFixMs = 0L
    private var counting = true
    private val window = ArrayDeque<Pair<Long, Double>>() // (time, total distance)

    private val listener = LocationListener { loc -> onLocation(loc) }

    fun start(): Boolean {
        reset()
        return try {
            lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, listener, Looper.getMainLooper())
            lm != null
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        try { lm?.removeUpdates(listener) } catch (_: Exception) {}
    }

    /** While paused, movement is not added to the distance. */
    fun setCounting(on: Boolean) {
        counting = on
        last = null
    }

    fun refreshFixState() {
        val hasFix = System.currentTimeMillis() - lastFixMs < 10_000
        if (hasFix != _state.value.hasFix) _state.value = _state.value.copy(hasFix = hasFix)
    }

    private fun reset() {
        last = null
        lastFixMs = 0
        window.clear()
        _state.value = State()
    }

    private fun onLocation(loc: Location) {
        if (loc.accuracy > 25f) return
        lastFixMs = System.currentTimeMillis()
        var total = _state.value.distanceM
        val prev = last
        if (counting && prev != null) {
            val d = prev.distanceTo(loc).toDouble()
            if (d >= 3.0) {
                total += d
                last = loc
            }
        } else {
            last = loc
        }

        val now = lastFixMs
        window.addLast(now to total)
        while (window.size > 1 && now - window.first().first > 30_000) window.removeFirst()
        val first = window.first()
        val dDist = total - first.second
        val dSec = (now - first.first) / 1000.0
        val pace = if (dDist > 15 && dSec > 5) (dSec / (dDist / 1000.0)).toInt() else null

        _state.value = State(total, pace, true)
    }
}
