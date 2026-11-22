package dev.ring.companion.ring

import android.util.Log
import dev.ring.companion.data.Measurement
import dev.ring.companion.data.MeasurementStore
import dev.ring.companion.data.SleepStore
import dev.ring.companion.protocol.Ycbt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Copies what the ring stored on its own (sleep, background heart rate) into the app.
 * Runs shortly after each connect and every 30 minutes while connected.
 * Records stay on the ring; already-saved ones are skipped.
 */
class HistorySync(
    private val scope: CoroutineScope,
    private val session: RingSession,
    private val sleep: SleepStore,
    private val measurements: MeasurementStore,
    private val intervalMin: () -> Int,
    private val workoutActive: () -> Boolean = { false },
) {
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing
    private val lock = Mutex()

    fun start() {
        scope.launch {
            session.connectedEvents.collect {
                delay(4_000) // let setup and the first live-data request finish
                sync()
            }
        }
        // The ring takes its background readings on clock multiples of the interval (e.g. :00,
        // :15, :30, :45). Sync a few minutes after each so new readings show up promptly.
        scope.launch {
            while (true) {
                delay(msUntilNextSlot())
                if (!workoutActive() && session.status.value == RingSession.Status.Connected) sync()
            }
        }
    }

    fun syncNow() { scope.launch { sync() } }

    @Volatile private var lastSyncMs = 0L

    /** Called when the app comes to the screen: sync if the last one is more than 5 minutes old. */
    fun syncIfStale() {
        if (!workoutActive() && session.status.value == RingSession.Status.Connected && System.currentTimeMillis() - lastSyncMs > 5 * 60_000L) syncNow()
    }

    private suspend fun sync() {
        // Requesting stored history puts the ring out of workout-streaming mode, which kills
        // live heart rate. Never sync during a workout; it resumes right after.
        if (workoutActive()) return
        if (!lock.tryLock()) return
        _syncing.value = true
        try {
            session.fetchHistory(Ycbt.History.SLEEP)?.let { bytes ->
                val records = Ycbt.decodeSleepHistory(bytes)
                Log.i(TAG, "sleep: ${records.size} records")
                sleep.merge(records)
                sleep.markSynced()
            }
            session.fetchHistory(Ycbt.History.HEART)?.let { bytes ->
                val readings = Ycbt.decodeHeartHistory(bytes)
                measurements.addAll(readings.map { (t, bpm) -> Measurement(Measurement.HEART_RATE, bpm, t, Measurement.RING) })
            }
            session.fetchHistory(Ycbt.History.COMBINED)?.let { bytes ->
                val list = Ycbt.decodeCombinedHistory(bytes)
                measurements.addAll(
                    list.flatMap { r ->
                        listOfNotNull(
                            r.spo2.takeIf { it in 70..100 }?.let { Measurement(Measurement.SPO2, it, r.timeMs, Measurement.RING) },
                            r.heartRate.takeIf { it in 30..230 }?.let { Measurement(Measurement.HEART_RATE, it, r.timeMs, Measurement.RING) },
                            r.hrvMs.takeIf { it in 5..250 }?.let { Measurement(Measurement.HRV, it, r.timeMs, Measurement.RING) },
                            r.breathsPerMin.takeIf { it in 4..40 }?.let { Measurement(Measurement.BREATHING, it, r.timeMs, Measurement.RING) },
                        )
                    },
                )
            }
            lastSyncMs = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "history sync failed", e)
        } finally {
            _syncing.value = false
            lock.unlock()
        }
    }

    /** Time until 3 minutes after the next reading slot (local clock). */
    private fun msUntilNextSlot(now: Long = System.currentTimeMillis()): Long {
        val slot = intervalMin().coerceIn(5, 120) * 60_000L
        val offset = 3 * 60_000L
        val zoneOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val local = now + zoneOffset
        val next = ((local - offset) / slot + 1) * slot + offset
        return next - local
    }

    companion object { private const val TAG = "HistorySync" }
}
