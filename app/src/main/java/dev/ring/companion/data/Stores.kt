package dev.ring.companion.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One heart-rate or blood-oxygen reading.
 * [source]: [MANUAL] (Measure button), [RING] (the ring's own background reading),
 * or [WORKOUT] (one-minute average during a workout).
 */
data class Measurement(val kind: String, val value: Int, val timeMs: Long, val source: String = MANUAL) {
    companion object {
        const val HEART_RATE = "hr"
        const val SPO2 = "spo2"
        /** Estimated by the ring from its background reading; not a supported feature on every ring. */
        const val HRV = "hrv"
        const val BREATHING = "breath"
        const val MANUAL = "manual"
        const val RING = "ring"
        const val WORKOUT = "workout"
    }
}

/** Keeps the last 30 days of readings in one small JSON file. */
class MeasurementStore(context: Context) {
    private val file = File(context.filesDir, "measurements.json")
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Measurement>> = _all

    suspend fun add(m: Measurement) = addAll(listOf(m))

    /** Adds readings; ones already saved (same kind and time) are skipped. */
    suspend fun addAll(list: List<Measurement>) = withContext(Dispatchers.IO) {
        val have = _all.value.mapTo(HashSet()) { it.kind to it.timeMs }
        val fresh = list.filter { (it.kind to it.timeMs) !in have }
        if (fresh.isNotEmpty()) write(_all.value + fresh)
    }

    /** Removes the workout readings between two times (used when a workout is deleted). */
    suspend fun removeWorkout(fromMs: Long, toMs: Long) = withContext(Dispatchers.IO) {
        write(_all.value.filterNot { it.source == Measurement.WORKOUT && it.timeMs in fromMs..toMs })
    }

    private fun write(list: List<Measurement>) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        val next = list.filter { it.timeMs >= cutoff }.sortedBy { it.timeMs }
        _all.value = next
        val arr = JSONArray()
        next.forEach { arr.put(JSONObject().put("k", it.kind).put("v", it.value).put("t", it.timeMs).put("s", it.source)) }
        file.writeText(arr.toString())
    }

    private fun load(): List<Measurement> = runCatching {
        val arr = JSONArray(file.readText())
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            Measurement(o.getString("k"), o.getInt("v"), o.getLong("t"), o.optString("s", Measurement.RING))
        }
    }.getOrDefault(emptyList())
}

/** A finished workout. All `*Ms` offsets are active time (pauses removed). */
data class WorkoutRecord(
    val id: Long,
    val kind: String,
    val startMs: Long,
    val endMs: Long,
    val activeMs: Long,
    val distanceM: Double,
    val steps: Int,
    /** Set when the ring was out of reach at the end: steps are counted up to this time. */
    val stepsAsOfMs: Long? = null,
    val avgHr: Int?,
    val maxHr: Int?,
    val hrSamples: List<Pair<Long, Int>>,
    val gaps: List<Pair<Long, Long>>,
    val zoneMs: List<Long>,
    val laps: List<Long>,
    val maxHrSetting: Int,
) {
    val connectedPercent: Int
        get() {
            if (activeMs <= 0) return 100
            val gap = gaps.sumOf { it.second - it.first }
            return (100 - gap * 100 / activeMs).toInt().coerceIn(0, 100)
        }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind).put("start", startMs).put("end", endMs)
        .put("active", activeMs).put("distance", distanceM).put("steps", steps)
        .apply { stepsAsOfMs?.let { put("stepsAsOf", it) } }
        .put("avgHr", avgHr ?: JSONObject.NULL).put("maxHr", maxHr ?: JSONObject.NULL)
        .put("hr", JSONArray().also { a -> hrSamples.forEach { a.put(JSONArray().put(it.first).put(it.second)) } })
        .put("gaps", JSONArray().also { a -> gaps.forEach { a.put(JSONArray().put(it.first).put(it.second)) } })
        .put("zones", JSONArray(zoneMs))
        .put("laps", JSONArray(laps))
        .put("maxHrSetting", maxHrSetting)

    /** Simple CSV for export: one row per heart-rate sample. */
    fun toCsv(): String = buildString {
        append("seconds,bpm\n")
        hrSamples.forEach { append(it.first / 1000).append(',').append(it.second).append('\n') }
    }

    companion object {
        fun fromJson(o: JSONObject): WorkoutRecord {
            fun pairs(key: String): List<Pair<Long, Long>> {
                val a = o.optJSONArray(key) ?: return emptyList()
                return List(a.length()) { a.getJSONArray(it).let { p -> p.getLong(0) to p.getLong(1) } }
            }
            val zones = o.optJSONArray("zones")
            val laps = o.optJSONArray("laps")
            return WorkoutRecord(
                id = o.getLong("id"),
                kind = o.getString("kind"),
                startMs = o.getLong("start"),
                endMs = o.getLong("end"),
                activeMs = o.getLong("active"),
                distanceM = o.optDouble("distance", 0.0),
                steps = o.optInt("steps", 0),
                stepsAsOfMs = if (o.has("stepsAsOf")) o.getLong("stepsAsOf") else null,
                avgHr = if (o.isNull("avgHr")) null else o.getInt("avgHr"),
                maxHr = if (o.isNull("maxHr")) null else o.getInt("maxHr"),
                hrSamples = pairs("hr").map { it.first to it.second.toInt() },
                gaps = pairs("gaps"),
                zoneMs = if (zones == null) List(6) { 0L } else List(zones.length()) { zones.getLong(it) },
                laps = if (laps == null) emptyList() else List(laps.length()) { laps.getLong(it) },
                maxHrSetting = o.optInt("maxHrSetting", 186),
            )
        }
    }
}

class WorkoutStore(context: Context) {
    private val dir = File(context.filesDir, "workouts").apply { mkdirs() }
    private val _all = MutableStateFlow(loadAll())
    val all: StateFlow<List<WorkoutRecord>> = _all

    fun get(id: Long): WorkoutRecord? = _all.value.firstOrNull { it.id == id }

    suspend fun save(r: WorkoutRecord) = withContext(Dispatchers.IO) {
        File(dir, "${r.id}.json").writeText(r.toJson().toString())
        _all.value = (_all.value.filter { it.id != r.id } + r).sortedByDescending { it.startMs }
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        _all.value = _all.value.filter { it.id != id }
    }

    private fun loadAll(): List<WorkoutRecord> =
        dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { WorkoutRecord.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.startMs }
}
