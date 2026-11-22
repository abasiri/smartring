package dev.ring.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ring.companion.data.BatterySample
import dev.ring.companion.data.BatteryStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Battery level over the last [days] days, with drain rate and an estimate of time left. */
@Composable
fun BatteryCard(samples: List<BatterySample>, modifier: Modifier = Modifier) {
    // Range switcher: 1 day (default) or 1 week. Remembered while the app is open.
    var days by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(1) }
    Panel(modifier, spacing = 10.dp) {
        val now = System.currentTimeMillis()
        val spanMs = days * 24 * 3600_000L
        val pts = samples.filter { it.timeMs >= now - spanMs }.sortedBy { it.timeMs }
        val current = samples.lastOrNull()?.percent
        val drain = BatteryStore.drainPerHour(samples)

        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            SectionLabel("Battery", Modifier.weight(1f))
            listOf(1 to "1D", 7 to "1W").forEach { (d, label) ->
                val sel = d == days
                Text(
                    label, fontSize = 11.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    color = if (sel) Palette.Accent else Palette.Faint,
                    modifier = Modifier
                        .clickable(role = androidx.compose.ui.semantics.Role.Tab) { days = d }
                        .padding(horizontal = 5.dp, vertical = 6.dp)
                        .semantics { contentDescription = if (d == 1) "Show last day" else "Show last week" },
                )
            }
            if (current != null) Text("$current%", style = numberStyle(22, FontWeight.SemiBold), modifier = Modifier.padding(start = 6.dp))
        }

        if (pts.size < 2) {
            Text(
                "Tracking starts once the ring reports a few readings. Keep it connected.",
                fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.height(40.dp),
            )
        } else {
            Row {
                Column(Modifier.width(30.dp).height(96.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text("100", fontSize = 10.sp, color = Palette.Faint)
                    Text("50", fontSize = 10.sp, color = Palette.Faint)
                    Text("0", fontSize = 10.sp, color = Palette.Faint)
                }
                Canvas(Modifier.weight(1f).height(96.dp)) {
                    val x0 = now - spanMs
                    fun x(t: Long) = ((t - x0).toFloat() / spanMs) * size.width
                    fun y(p: Int) = size.height - (p / 100f) * size.height
                    // 50% guide
                    drawLine(
                        Palette.Line.copy(alpha = 0.6f), Offset(0f, y(50)), Offset(size.width, y(50)), 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                    )
                    drawLine(Palette.Line, Offset(0f, size.height), Offset(size.width, size.height), 1.5f)
                    // segments: green while charging, accent while discharging
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]
                        val b = pts[i]
                        val gap = b.timeMs - a.timeMs > 48 * 3600_000L
                        if (gap) continue // don't connect across long offline gaps
                        val col = if (a.charging || b.charging) Palette.Good else Palette.Accent
                        drawLine(
                            col, Offset(x(a.timeMs), y(a.percent)), Offset(x(b.timeMs), y(b.percent)),
                            2.dp.toPx(), cap = StrokeCap.Round,
                        )
                    }
                    pts.forEach { drawCircle(if (it.charging) Palette.Good else Palette.Accent, 2.dp.toPx(), Offset(x(it.timeMs), y(it.percent))) }
                }
            }
            Row(Modifier.padding(start = 30.dp)) {
                val fmt = DateTimeFormatter.ofPattern(if (days == 1) "HH:mm" else "MMM d")
                Text(Instant.ofEpochMilli(now - spanMs).atZone(ZoneId.systemDefault()).format(fmt), style = LabelStyle.copy(letterSpacing = 0.sp), modifier = Modifier.weight(1f))
                Text("now", style = LabelStyle.copy(letterSpacing = 0.sp))
            }
        }

        // Drain summary
        val summary = when {
            drain == null -> "Not enough data yet to estimate drain."
            else -> {
                val perDay = drain * 24
                val daysLeft = current?.let { if (drain > 0) it / perDay else null }
                buildString {
                    append("Draining about %.1f%%/day".format(perDay))
                    if (daysLeft != null) {
                        append(" · ")
                        append(if (daysLeft >= 1) "~%.1f days left".format(daysLeft) else "~%d h left".format((daysLeft * 24).toInt()))
                    }
                }
            }
        }
        Text(summary, fontSize = 12.sp, color = Palette.Muted)
    }
}
