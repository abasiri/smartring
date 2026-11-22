package dev.ring.companion.workout

import dev.ring.companion.data.Measurement
import dev.ring.companion.data.MeasurementStore
import dev.ring.companion.data.Prefs
import dev.ring.companion.data.WorkoutRecord
import dev.ring.companion.data.WorkoutStore
import dev.ring.companion.protocol.Ycbt
import dev.ring.companion.ring.RingSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class WorkoutKind(val label: String, val sportType: Int, val usesGps: Boolean) {
    Run("Run", Ycbt.SportType.RUN, true),
    Walk("Walk", Ycbt.SportType.WALK, true),
    Ride("Ride", Ycbt.SportType.RIDE, true),
    Strength("Strength", Ycbt.SportType.STRENGTH, false),
}

/** Records one workout at a time: timer, heart rate, zones, steps, GPS, ring drop-outs. */
class WorkoutController(
    private val scope: CoroutineScope,
    private val session: RingSession,
    private val gps: GpsTracker,
    private val prefs: Prefs,
    private val store: WorkoutStore,
    private val measurements: MeasurementStore,
) {
    data class Live(
        val kind: WorkoutKind,
        val paused: Boolean,
        val activeMs: Long,
        val hr: Int?,
        val hrAgeMs: Long?,
        val avgHr: Int?,
        val maxHr: Int?,
        val zone: Int,
        val zoneMs: List<Long>,
        val recent: List<Int?>,
        val distanceM: Double,
        val paceSecPerKm: Int?,
        val steps: Int,
        val cadence: Int?,
        val ringStatus: RingSession.Status,
        val reconnectAttempt: Int,
        val gpsActive: Boolean,
        val gpsFix: Boolean,
        val maxHrSetting: Int,
    )

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live

    private var jobs = mutableListOf<Job>()

    // recording state
    private var kind = WorkoutKind.Run
    private var startMs = 0L
    private var activeMs = 0L
    private var paused = false
    private var gpsActive = false
    private val samples = mutableListOf<Pair<Long, Int>>()
    /** Same readings keyed by clock time, for the day's heart-rate chart. */
    private val clockSamples = mutableListOf<Pair<Long, Int>>()
    private val gaps = mutableListOf<Pair<Long, Long>>()
    private var gapStart: Long? = null
    private val laps = mutableListOf<Long>()
    private val zoneMs = LongArray(6)
    private var lastHr: Int? = null
    private var lastHrAt = 0L
    private var lastNudgeAt = 0L
    private var nudgeCount = 0
    /**
     * Steps are the ring's day total read at the start and end of each active stretch
     * (start→pause, resume→end). Between those, every report updates [StepSegment.lastTotal],
     * so if the ring goes away mid-workout the count up to its last report is kept.
     */
    private class StepSegment(val startTotal: Int, now: Long) {
        var lastTotal = startTotal
        var lastMs = now
        var closed = false
        val steps get() = lastTotal - startTotal
    }
    private val segments = mutableListOf<StepSegment>()
    /** Started or resumed, waiting for the first total to open a segment. */
    private var needSegment = false
    private val steps get() = segments.sumOf { it.steps }
    private val stepsAsOfMs get() = segments.lastOrNull()?.lastMs
    private val stepWindow = ArrayDeque<Pair<Long, Int>>()

    val isActive: Boolean get() = _live.value != null

    fun start(k: WorkoutKind, gpsAllowed: Boolean) {
        if (isActive) return
        kind = k
        startMs = System.currentTimeMillis()
        activeMs = 0
        paused = false
        samples.clear(); clockSamples.clear(); gaps.clear(); laps.clear(); zoneMs.fill(0)
        gapStart = null; lastHr = null; lastHrAt = 0; lastNudgeAt = startMs; nudgeCount = 0
        segments.clear(); needSegment = true; stepWindow.clear()
        gpsActive = k.usesGps && gpsAllowed && gps.start()

        jobs += scope.launch { session.startWorkoutMode(k.sportType) }
        jobs += scope.launch {
            session.heartRate.collect { s ->
                if (paused) return@collect
                lastHr = s.bpm
                lastHrAt = s.timeMs
                samples += activeMs to s.bpm
                clockSamples += s.timeMs to s.bpm
                publish()
            }
        }
        jobs += scope.launch { session.readSteps(sport = true)?.let { onStepTotal(it, fresh = true) } }
        jobs += scope.launch {
            session.activity.collect { a -> if (a != null) onStepTotal(a.steps, fresh = false) }
        }
        jobs += scope.launch {
            session.connectedEvents.collect {
                session.resendWorkoutMode(kind.sportType)
                if (paused) session.pauseWorkoutMode(kind.sportType)
            }
        }
        jobs += scope.launch { ticker() }
        publish()
    }

    fun togglePause() {
        if (!isActive) return
        paused = !paused
        gps.setCounting(!paused)
        if (paused) {
            // Close this stretch with a fresh total (falls back to the last report).
            val open = segments.lastOrNull()?.takeIf { !it.closed }
            needSegment = false
            scope.launch {
                session.readSteps(sport = true)?.let { t -> open?.let { applyTotal(it, t, fresh = true) } }
                open?.closed = true
                session.pauseWorkoutMode(kind.sportType)
                publish()
            }
        } else {
            needSegment = true
            scope.launch {
                session.resumeWorkoutMode(kind.sportType)
                session.readSteps(sport = true)?.let { onStepTotal(it, fresh = true) }
            }
        }
        publish()
    }

    /** A step total from the ring (day total). Opens, extends or splits the current stretch. */
    private fun onStepTotal(total: Int, fresh: Boolean) {
        val now = System.currentTimeMillis()
        if (paused) return
        if (needSegment) {
            segments += StepSegment(total, now)
            needSegment = false
        } else {
            val seg = segments.lastOrNull()?.takeIf { !it.closed } ?: return
            if (total < seg.lastTotal) {
                // The ring's day total went back to zero at midnight: start a new stretch from 0.
                seg.closed = true
                segments += StepSegment(0, now).also { it.lastTotal = total }
            } else {
                applyTotal(seg, total, fresh)
            }
        }
        stepWindow.addLast(now to steps)
        while (stepWindow.size > 1 && now - stepWindow.first().first > 60_000) stepWindow.removeFirst()
    }

    /**
     * Fresh reads (asked for and answered) are trusted. Passing reports are checked against
     * the time since the last one, so steps taken while the phone was away still count.
     */
    private fun applyTotal(seg: StepSegment, total: Int, fresh: Boolean) {
        val now = System.currentTimeMillis()
        val delta = total - seg.lastTotal
        if (delta < 0) return
        val limit = STEP_SLACK + MAX_STEPS_PER_SEC * ((now - seg.lastMs) / 1000)
        if (fresh || delta <= limit) {
            seg.lastTotal = total
            seg.lastMs = now
        }
    }

    fun lap() {
        if (isActive && !paused) laps += activeMs
    }

    /** Stops recording, saves the workout and returns its id (null if nothing to save). */
    suspend fun finish(): Long? {
        if (!isActive) return null
        // Final step total, read before the ring leaves workout mode. If the ring can't be
        // reached, the count stops at its last report and the summary says when that was.
        val finalTotal = if (!paused) session.readSteps(sport = true) else null
        segments.lastOrNull()?.takeIf { !it.closed }?.let { seg -> finalTotal?.let { applyTotal(seg, it, fresh = true) } }
        val stepsComplete = paused || finalTotal != null || segments.isEmpty()
        android.util.Log.i("Workout", "steps=$steps final=$finalTotal segments=" +
            segments.joinToString { "${it.startTotal}..${it.lastTotal}" })
        jobs.forEach { it.cancel() }
        jobs.clear()
        gapStart?.let { gaps += it to activeMs }
        if (gpsActive) gps.stop()
        val distance = gps.state.value.distanceM
        scope.launch { session.stopWorkoutMode(kind.sportType) }
        _live.value = null

        if (activeMs < 10_000) return null
        val bpms = samples.map { it.second }
        val record = WorkoutRecord(
            id = startMs,
            kind = kind.label,
            startMs = startMs,
            endMs = System.currentTimeMillis(),
            activeMs = activeMs,
            distanceM = if (gpsActive) distance else 0.0,
            steps = steps,
            stepsAsOfMs = if (stepsComplete) null else stepsAsOfMs,
            avgHr = bpms.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            maxHr = bpms.maxOrNull(),
            hrSamples = samples.toList(),
            gaps = gaps.toList(),
            zoneMs = zoneMs.toList(),
            laps = laps.toList(),
            maxHrSetting = prefs.maxHr.value,
        )
        store.save(record)
        // One reading per minute (the minute's average) goes into the day's heart-rate history.
        measurements.addAll(
            clockSamples.groupBy { it.first / 60_000 }.map { (minute, list) ->
                Measurement(Measurement.HEART_RATE, list.map { it.second }.average().toInt(), minute * 60_000 + 30_000, Measurement.WORKOUT)
            },
        )
        return record.id
    }

    /** Throws the workout away without saving. */
    fun discard() {
        if (!isActive) return
        jobs.forEach { it.cancel() }
        jobs.clear()
        if (gpsActive) gps.stop()
        scope.launch { session.stopWorkoutMode(kind.sportType) }
        _live.value = null
    }

    private suspend fun ticker() {
        var prev = System.currentTimeMillis()
        while (true) {
            delay(1_000)
            val now = System.currentTimeMillis()
            val dt = now - prev
            prev = now
            if (paused) { publish(); continue }
            activeMs += dt

            val connected = session.status.value == RingSession.Status.Connected
            if (!connected && gapStart == null) gapStart = activeMs
            if (connected && gapStart != null) {
                gaps += gapStart!! to activeMs
                gapStart = null
            }

            val hr = lastHr
            if (hr != null && now - lastHrAt < 15_000) {
                zoneMs[HrZones.zoneOf(hr, prefs.maxHr.value)] += dt
            }
            // Heart rate went quiet while the ring is connected: ask for a fresh sample.
            // Heart rate went quiet. First nudge just re-opens the sensor; if it stays quiet
            // the ring has likely left workout-streaming mode (e.g. after any other request),
            // so re-send the whole workout sequence.
            if (connected && now - lastHrAt > 20_000 && now - lastNudgeAt > 15_000) {
                lastNudgeAt = now
                nudgeCount++
                val full = nudgeCount >= 2
                scope.launch { if (full) session.resendWorkoutMode(kind.sportType) else session.nudgeHeartRate() }
            }
            if (now - lastHrAt < 20_000) nudgeCount = 0
            gps.refreshFixState()
            publish()
        }
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        val maxHr = prefs.maxHr.value
        val bpms = samples.map { it.second }
        val hr = lastHr
        val cadence = stepWindow.takeIf { it.size > 1 }?.let { w ->
            val dSteps = w.last().second - w.first().second
            val dMin = (w.last().first - w.first().first) / 60_000.0
            if (dMin > 0.2) (dSteps / dMin).toInt() else null
        }
        // Last 10 minutes in 15-second buckets (40 bars).
        val bucket = 15_000L
        val end = activeMs
        val recent = List(40) { i ->
            val from = end - (40 - i) * bucket
            val to = from + bucket
            samples.filter { it.first in from until to }.map { it.second }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        }
        val g = gps.state.value
        _live.value = Live(
            kind = kind,
            paused = paused,
            activeMs = activeMs,
            hr = hr,
            hrAgeMs = if (hr != null) now - lastHrAt else null,
            avgHr = bpms.takeIf { it.isNotEmpty() }?.average()?.toInt(),
            maxHr = bpms.maxOrNull(),
            zone = hr?.let { HrZones.zoneOf(it, maxHr) } ?: 0,
            zoneMs = zoneMs.toList(),
            recent = recent,
            distanceM = g.distanceM,
            paceSecPerKm = g.paceSecPerKm,
            steps = steps,
            cadence = cadence,
            ringStatus = session.status.value,
            reconnectAttempt = session.attempt.value,
            gpsActive = gpsActive,
            gpsFix = g.hasFix,
            maxHrSetting = maxHr,
        )
    }

    private companion object {
        /** Faster than any runner; a bigger jump for the time passed is bad data, not steps. */
        const val MAX_STEPS_PER_SEC = 5
        const val STEP_SLACK = 50
    }
}
