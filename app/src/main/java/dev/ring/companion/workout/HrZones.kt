package dev.ring.companion.workout

/** Five heart-rate zones as a share of max heart rate. Zone 0 = below zone 1. */
object HrZones {
    private val LOWER = doubleArrayOf(0.0, 0.50, 0.60, 0.70, 0.80, 0.90)
    val NAMES = listOf("Resting", "Warm-up", "Easy", "Aerobic", "Threshold", "Maximum")

    fun zoneOf(bpm: Int, maxHr: Int): Int {
        val pct = bpm.toDouble() / maxHr
        for (z in 5 downTo 1) if (pct >= LOWER[z]) return z
        return 0
    }

    /** Lower and upper bpm for zone [z] (1..5). */
    fun range(z: Int, maxHr: Int): IntRange {
        val lo = (LOWER[z] * maxHr).toInt()
        val hi = if (z == 5) maxHr else (LOWER[z + 1] * maxHr).toInt() - 1
        return lo..hi
    }
}
