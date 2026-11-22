package dev.ring.companion.data

import android.content.Context
import dev.ring.companion.protocol.Ycbt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One night of sleep. The ring may split a night into several records when you
 * wake up; records less than two hours apart are joined, and the time between
 * them counts as awake.
 */
data class SleepNight(val startMs: Long, val endMs: Long, val stages: List<Ycbt.SleepStage>) {
    private fun total(stage: Int) = stages.filter { it.stage == stage }.sumOf { it.seconds.toLong() }

    val deepSec get() = total(Ycbt.Stage.DEEP)
    val lightSec get() = total(Ycbt.Stage.LIGHT)
    val remSec get() = total(Ycbt.Stage.REM)
    val awakeSec get() = total(Ycbt.Stage.AWAKE)
    val asleepSec get() = deepSec + lightSec + remSec

    /** The morning this night belongs to. */
    fun wakeDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(endMs).atZone(zone).toLocalDate()

    /** Set when [withHeartRate] moved the start or end of the night. */
    data class Adjustment(val onsetMs: Long?, val wakeMs: Long?, val sleepingBpm: Int, val awakeBpm: Int?)

    /**
     * The ring decides sleep mostly from movement, so lying still in bed counts as asleep.
     * Its half-hourly heart-rate readings can show you were already awake: a reading near the
     * end of the night well above your sleeping rate (and no later reading back at that rate)
     * moves the wake time to that reading; the same at the start for lying awake before sleep.
     * Time cut off is counted as awake. The ring's own record is kept unchanged.
     */
    fun withHeartRate(readings: List<Pair<Long, Int>>): Pair<SleepNight, Adjustment?> {
        val inNight = readings.filter { it.first in startMs..endMs }.sortedBy { it.first }
        if (inNight.size < 4) return this to null
        // Resting level while asleep: the lower quarter of the night's readings. (The median
        // is pulled up by REM and light sleep, when heart rate runs higher.)
        val sorted = inNight.map { it.second }.sorted()
        val sleeping = sorted[sorted.size / 4]
        // Waking: clearly above the resting level, more than REM usually raises it.
        fun awake(bpm: Int) = bpm >= sleeping * 1.3 && bpm >= sleeping + 15
        // Start of the night is stricter: early REM can push heart rate up too.
        fun awakeAtStart(bpm: Int) = bpm >= sleeping * 1.4 && bpm >= sleeping + 20
        fun asleep(bpm: Int) = bpm <= sleeping + 3

        val window = 90 * 60_000L
        // Wake: earliest awake reading in the last 90 min with no fully-asleep reading after it.
        val tail = inNight.filter { it.first >= endMs - window }
        val wake = tail.firstOrNull { r -> awake(r.second) && tail.none { it.first > r.first && asleep(it.second) } }
        // Onset: last awake reading in the first 90 min with no fully-asleep reading before it.
        val head = inNight.filter { it.first <= startMs + window }
        val onset = head.lastOrNull { r -> awakeAtStart(r.second) && head.none { it.first < r.first && asleep(it.second) } }
        if (wake == null && onset == null) return this to null

        val from = onset?.first ?: startMs
        val to = wake?.first ?: endMs
        if (to - from < 30 * 60_000L) return this to null
        // Relabel, then join neighbouring pieces of the same stage (the ring's short stages
        // after the wake time would otherwise show as a row of separate awake blocks).
        val adjusted = stages.flatMap { st -> relabel(st, from, to) }.fold(mutableListOf<Ycbt.SleepStage>()) { acc, st ->
            val last = acc.lastOrNull()
            if (last != null && last.stage == st.stage && last.endMs == st.startMs) {
                acc[acc.lastIndex] = last.copy(seconds = last.seconds + st.seconds)
            } else {
                acc += st
            }
            acc
        }
        return SleepNight(startMs, endMs, adjusted) to
            Adjustment(onset?.first, wake?.first, sleeping, (wake ?: onset)?.second)
    }

    /** Splits a stage at [from]/[to]; parts outside [from, to) become awake. */
    private fun relabel(st: Ycbt.SleepStage, from: Long, to: Long): List<Ycbt.SleepStage> {
        val out = mutableListOf<Ycbt.SleepStage>()
        var t = st.startMs
        val end = st.endMs
        for (cut in listOf(from, to)) {
            if (cut in (t + 1) until end) {
                out += piece(st.stage, t, cut, from, to)
                t = cut
            }
        }
        out += piece(st.stage, t, end, from, to)
        return out.filter { it.seconds > 0 }
    }

    private fun piece(stage: Int, a: Long, b: Long, from: Long, to: Long) =
        Ycbt.SleepStage(if (a >= from && a < to) stage else Ycbt.Stage.AWAKE, a, ((b - a) / 1000).toInt())

    companion object {
        private const val JOIN_GAP_MS = 2 * 3600 * 1000L

        /** Applies [withHeartRate] to each night using the saved heart-rate readings. */
        fun adjustAll(nights: List<SleepNight>, heartRate: List<Pair<Long, Int>>): List<Pair<SleepNight, Adjustment?>> =
            nights.map { it.withHeartRate(heartRate) }

        fun fromRecords(records: List<Ycbt.SleepRecord>): List<SleepNight> {
            val nights = mutableListOf<SleepNight>()
            for (r in records.sortedBy { it.startMs }) {
                val stages = r.stages.ifEmpty {
                    // No breakdown in this record: show it as light sleep.
                    listOf(Ycbt.SleepStage(Ycbt.Stage.LIGHT, r.startMs, ((r.endMs - r.startMs) / 1000).toInt()))
                }
                val last = nights.lastOrNull()
                if (last != null && r.startMs - last.endMs in 0..JOIN_GAP_MS) {
                    val gap = ((r.startMs - last.endMs) / 1000).toInt()
                    val bridge = if (gap > 0) listOf(Ycbt.SleepStage(Ycbt.Stage.AWAKE, last.endMs, gap)) else emptyList()
                    nights[nights.lastIndex] = SleepNight(last.startMs, maxOf(last.endMs, r.endMs), last.stages + bridge + stages)
                } else if (last == null || r.startMs >= last.endMs) {
                    nights += SleepNight(r.startMs, r.endMs, stages)
                }
                // Overlapping records (same night sent twice) are skipped.
            }
            return nights
        }
    }
}

/** Sleep records downloaded from the ring, kept for 60 days. */
class SleepStore(context: Context) {
    private val file = File(context.filesDir, "sleep.json")
    private var records: List<Ycbt.SleepRecord> = load()
    private val _nights = MutableStateFlow(SleepNight.fromRecords(records))
    val nights: StateFlow<List<SleepNight>> = _nights

    private val _lastSyncMs = MutableStateFlow(0L)
    val lastSyncMs: StateFlow<Long> = _lastSyncMs

    fun markSynced() { _lastSyncMs.value = System.currentTimeMillis() }

    suspend fun merge(fresh: List<Ycbt.SleepRecord>) = withContext(Dispatchers.IO) {
        val have = records.mapTo(HashSet()) { it.startMs }
        val add = fresh.filter { it.startMs !in have }
        if (add.isEmpty()) return@withContext
        val cutoff = System.currentTimeMillis() - 60L * 24 * 3600 * 1000
        records = (records + add).filter { it.endMs >= cutoff }.sortedBy { it.startMs }
        _nights.value = SleepNight.fromRecords(records)
        file.writeText(toJson(records).toString())
    }

    private fun toJson(list: List<Ycbt.SleepRecord>) = JSONArray().apply {
        list.forEach { r ->
            put(
                JSONObject().put("s", r.startMs).put("e", r.endMs).put(
                    "st",
                    JSONArray().apply { r.stages.forEach { put(JSONArray().put(it.stage).put(it.startMs).put(it.seconds)) } },
                ),
            )
        }
    }

    private fun load(): List<Ycbt.SleepRecord> = runCatching {
        val arr = JSONArray(file.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val st = o.getJSONArray("st")
            Ycbt.SleepRecord(
                o.getLong("s"), o.getLong("e"),
                List(st.length()) { j -> st.getJSONArray(j).let { Ycbt.SleepStage(it.getInt(0), it.getLong(1), it.getInt(2)) } },
            )
        }
    }.getOrDefault(emptyList())
}
