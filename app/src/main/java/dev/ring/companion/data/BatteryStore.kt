package dev.ring.companion.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One battery reading from the ring. */
data class BatterySample(val timeMs: Long, val percent: Int, val charging: Boolean)

/**
 * Logs the ring's battery level over time so the Ring tab can show how fast it drains.
 * Low resolution by design: a sample is kept only when the level or charging state changes,
 * or at most every ~20 minutes. Keeps 30 days.
 */
class BatteryStore(context: Context) {
    private val file = File(context.filesDir, "battery.json")
    private val _samples = MutableStateFlow(load())
    val samples: StateFlow<List<BatterySample>> = _samples

    suspend fun record(percent: Int, charging: Boolean) = withContext(Dispatchers.IO) {
        if (percent !in 0..100) return@withContext
        val now = System.currentTimeMillis()
        val last = _samples.value.lastOrNull()
        // Skip only if nothing changed and it's been under 20 min (keeps the file small).
        if (last != null && last.percent == percent && last.charging == charging && now - last.timeMs < 20 * 60_000L) {
            return@withContext
        }
        val cutoff = now - 30L * 24 * 3600 * 1000
        val next = (_samples.value + BatterySample(now, percent, charging)).filter { it.timeMs >= cutoff }
        _samples.value = next
        val arr = JSONArray()
        next.forEach { arr.put(JSONObject().put("t", it.timeMs).put("p", it.percent).put("c", it.charging)) }
        file.writeText(arr.toString())
    }

    private fun load(): List<BatterySample> = runCatching {
        val arr = JSONArray(file.readText())
        List(arr.length()) {
            val o = arr.getJSONObject(it)
            BatterySample(o.getLong("t"), o.getInt("p"), o.optBoolean("c", false))
        }
    }.getOrDefault(emptyList())

    companion object {
        /**
         * Average drain over discharging stretches in the last [windowMs]: returns percent-per-hour
         * (positive = draining), or null if there isn't enough discharging data.
         */
        fun drainPerHour(samples: List<BatterySample>, windowMs: Long = 4L * 24 * 3600_000): Double? {
            val now = System.currentTimeMillis()
            val recent = samples.filter { it.timeMs >= now - windowMs }.sortedBy { it.timeMs }
            var drop = 0.0
            var hours = 0.0
            for (i in 1 until recent.size) {
                val a = recent[i - 1]
                val b = recent[i]
                // Count only intervals where neither end is charging and level went down or held.
                if (a.charging || b.charging) continue
                val dh = (b.timeMs - a.timeMs) / 3600_000.0
                // Up to 48 h between readings still counts: in "only while the app is open" mode
                // readings arrive only when you open the app. A charge in between shows up as a rise
                // and is skipped below.
                if (dh <= 0 || dh > 48) continue
                val dp = a.percent - b.percent
                if (dp < 0) continue // a rise while "not charging" is noise
                drop += dp
                hours += dh
            }
            return if (hours >= 1.0 && drop > 0) drop / hours else null
        }
    }
}
