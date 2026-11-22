package dev.ring.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ring.companion.data.Measurement
import dev.ring.companion.workout.HrZones
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Readings further apart than this aren't joined (one missed half-hourly reading is still joined). */
private const val GAP_MS = 75 * 60_000L

private val HrvColor = androidx.compose.ui.graphics.Color(0xFF7CC4FF)
private val BreathColor = androidx.compose.ui.graphics.Color(0xFF6FD6B8)

/** A shaded stretch on the chart, e.g. a workout. */
data class ChartBand(val startMs: Long, val endMs: Long, val label: String)

/**
 * Heart rate through one day: every reading at its real time, joined by a line.
 * Background readings (about every 30 min) and per-minute workout readings sit on
 * the same line; the line breaks where there is no reading for over an hour.
 * Manual readings get a larger dot. Tap to see a reading.
 */
@Composable
fun HrDayChart(
    readings: List<Measurement>,
    dayStartMs: Long,
    bands: List<ChartBand>,
    maxHr: Int,
    modifier: Modifier = Modifier,
    /** Optional HRV readings, drawn as a small strip under the main chart on the same time axis. */
    hrv: List<Measurement> = emptyList(),
    /** Optional breathing-rate readings, drawn as a second strip under the HRV one. */
    breathing: List<Measurement> = emptyList(),
) {
    // Background bands: one per heart-rate zone in view, in the zone's color, muted.
    // Below zone 1 (resting) gets a faint grey.
    fun zoneTint(z: Int) = if (z == 0) Palette.Muted.copy(alpha = 0.06f) else Palette.Zones[z].copy(alpha = 0.14f)
    fun zoneLabelColor(z: Int) = if (z == 0) Palette.Faint else Palette.Zones[z].copy(alpha = 0.85f)
    val points = remember(readings) { readings.sortedBy { it.timeMs } }
    var selected by remember(readings) { mutableStateOf<Measurement?>(null) }
    val measurer = rememberTextMeasurer()
    val axisStyle = LabelStyle.copy(letterSpacing = 0.sp, fontSize = 10.sp, color = Palette.Faint)
    // Show the part of the day that has data: at least the last 6 hours, up to now,
    // on whole hours. Sparse mornings don't squeeze the rest into a corner.
    val hourMs = 3600_000L
    val now = System.currentTimeMillis()
    val firstMs = listOfNotNull(points.firstOrNull()?.timeMs, bands.minOfOrNull { it.startMs }, now - 6 * hourMs).min()
    val lastMs = listOfNotNull(points.lastOrNull()?.timeMs, bands.maxOfOrNull { it.endMs }, now).max()
    val fromMs = maxOf(dayStartMs, dayStartMs + ((firstMs - dayStartMs) / hourMs) * hourMs)
    val toMs = minOf(dayStartMs + 24 * hourMs, dayStartMs + ((lastMs - dayStartMs + hourMs - 1) / hourMs) * hourMs)
    val spanMs = (toMs - fromMs).coerceAtLeast(hourMs)
    val spanH = (spanMs / hourMs).toInt()
    val stepH = when { spanH <= 6 -> 1; spanH <= 12 -> 2; spanH <= 18 -> 3; else -> 6 }

    val lo = ((points.minOf { it.value } - 5) / 10) * 10
    val hi = maxOf(((points.maxOf { it.value } + 14) / 10) * 10, lo + 30)
    val clock = DateTimeFormatter.ofPattern("HH:mm")
    fun timeOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clock)

    Column(modifier) {
        Row {
            Column(Modifier.width(30.dp).height(120.dp)) {
                Text("$hi", fontSize = 10.sp, color = Palette.Faint)
                Spacer(Modifier.weight(1f))
                Text("$lo", fontSize = 10.sp, color = Palette.Faint)
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(140.dp)
                    .semantics {
                        contentDescription = "Heart rate today, ${points.size} readings, " +
                            "from ${points.minOf { it.value }} to ${points.maxOf { it.value }} bpm"
                    }
                    .pointerInput(points) {
                        detectTapGestures { tap ->
                            val chartH = size.height - 20.dp.toPx()
                            if (tap.y > chartH + 4.dp.toPx()) return@detectTapGestures
                            val t = fromMs + (tap.x / size.width * spanMs).toLong()
                            selected = points.minByOrNull { abs(it.timeMs - t) }
                                ?.takeIf { abs(it.timeMs - t) < 45 * 60_000L }
                        }
                    },
            ) {
                val chartH = size.height - 20.dp.toPx()
                fun x(ms: Long) = ((ms - fromMs).toFloat() / spanMs) * size.width
                fun y(v: Int) = chartH - ((v - lo).toFloat() / (hi - lo)) * chartH

                // Zone bands across the full width
                for (z in 0..5) {
                    val zLo = if (z == 0) lo else maxOf(lo, HrZones.range(z, maxHr).first)
                    val zHi = if (z == 5) hi else minOf(hi, (if (z == 0) HrZones.range(1, maxHr).first else HrZones.range(z, maxHr).last + 1))
                    if (zHi <= zLo) continue
                    val top = y(zHi)
                    val bottom = y(zLo)
                    drawRect(zoneTint(z), Offset(0f, top), Size(size.width, bottom - top))
                    val name = measurer.measure(HrZones.NAMES[z], axisStyle.copy(color = zoneLabelColor(z)))
                    if (bottom - top > name.size.height + 4) {
                        drawText(name, topLeft = Offset(size.width - name.size.width - 4f, top + 2f))
                    }
                }

                // Workout bands: a light veil with edge lines, so they don't clash with zone colors
                bands.forEach { b ->
                    val l = x(b.startMs).coerceIn(0f, size.width)
                    val r = x(b.endMs).coerceIn(0f, size.width).coerceAtLeast(l + 3f)
                    drawRect(Palette.Text.copy(alpha = 0.05f), Offset(l, 0f), Size(r - l, chartH))
                    drawLine(Palette.Text2.copy(alpha = 0.35f), Offset(l, 0f), Offset(l, chartH), 1f)
                    drawLine(Palette.Text2.copy(alpha = 0.35f), Offset(r, 0f), Offset(r, chartH), 1f)
                    val label = measurer.measure(b.label, axisStyle.copy(color = Palette.Text2))
                    if (r - l > label.size.width + 4 || bands.size < 3) {
                        drawText(label, topLeft = Offset((l + 3f).coerceAtMost(size.width - label.size.width), 2f))
                    }
                }

                // Time grid on whole hours
                val firstHour = ((fromMs - dayStartMs) / hourMs).toInt()
                for (h in firstHour..firstHour + spanH) {
                    if (h % stepH != 0) continue
                    val gx = x(dayStartMs + h * hourMs)
                    drawLine(
                        Palette.Line.copy(alpha = 0.6f), Offset(gx, 0f), Offset(gx, chartH), 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                    )
                    val label = measurer.measure("%02d:00".format(h), axisStyle)
                    val lx = (gx - label.size.width / 2).coerceIn(0f, size.width - label.size.width)
                    drawText(label, topLeft = Offset(lx, chartH + 6.dp.toPx()))
                }
                drawLine(Palette.Line, Offset(0f, chartH), Offset(size.width, chartH), 1.5f)

                // Line, broken where readings are over an hour apart
                val path = Path()
                points.forEachIndexed { i, m ->
                    val p = Offset(x(m.timeMs), y(m.value))
                    val joined = i > 0 && m.timeMs - points[i - 1].timeMs <= GAP_MS
                    if (joined) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                }
                drawPath(path, Palette.Heart, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

                // Dots: background and lone readings small, manual readings larger. Workout minutes stay on the line.
                points.forEachIndexed { i, m ->
                    val p = Offset(x(m.timeMs), y(m.value))
                    val alone = (i == 0 || m.timeMs - points[i - 1].timeMs > GAP_MS) &&
                        (i == points.lastIndex || points[i + 1].timeMs - m.timeMs > GAP_MS)
                    val c = Palette.Heart
                    when {
                        m.source == Measurement.MANUAL -> {
                            drawCircle(Palette.Surface, 3.5.dp.toPx(), p)
                            drawCircle(c, 3.5.dp.toPx(), p, style = Stroke(1.5.dp.toPx()))
                        }
                        m.source == Measurement.RING || alone -> drawCircle(c, 2.5.dp.toPx(), p)
                    }
                }

                selected?.let { m ->
                    val p = Offset(x(m.timeMs), y(m.value))
                    drawLine(Palette.Text2.copy(alpha = 0.5f), Offset(p.x, 0f), Offset(p.x, chartH), 1.5f)
                    drawCircle(Palette.Text, 5.dp.toPx(), p)
                    drawCircle(Palette.Heart, 3.dp.toPx(), p)
                }
            }
        }
        MiniStrip("HRV · estimated", "ms", hrv, HrvColor, fromMs, toMs, spanMs, selected?.timeMs)
        MiniStrip("Breathing · estimated", "breaths/min", breathing, BreathColor, fromMs, toMs, spanMs, selected?.timeMs)
        Spacer(Modifier.height(6.dp))
        val s = selected
        // HRV taken within 5 minutes of the selected heart-rate reading (the ring stores them together).
        fun near(list: List<Measurement>) = s?.let { sel -> list.minByOrNull { abs(it.timeMs - sel.timeMs) }?.takeIf { abs(it.timeMs - sel.timeMs) < 5 * 60_000L } }
        val sHrv = near(hrv)
        val sBreath = near(breathing)
        Text(
            if (s == null) {
                "Tap the chart to see a reading"
            } else {
                "${timeOf(s.timeMs)} · ${s.value} bpm" + (sHrv?.let { " · HRV ${it.value} ms" } ?: "") +
                    (sBreath?.let { " · ${it.value} breaths/min" } ?: "") + " · " + when (s.source) {
                    Measurement.MANUAL -> "measured by you"
                    Measurement.WORKOUT -> "workout, 1-minute average"
                    else -> "ring background reading"
                }
            },
            fontSize = 12.sp, color = if (s == null) Palette.Faint else Palette.Text2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A small line strip under the heart-rate chart, on the same time axis.
 * Breaks the line with the same rule as the heart-rate line ([GAP_MS]).
 */
@Composable
private fun MiniStrip(
    label: String,
    unit: String,
    readings: List<Measurement>,
    color: androidx.compose.ui.graphics.Color,
    fromMs: Long,
    toMs: Long,
    spanMs: Long,
    selectedMs: Long?,
) {
    val pts = readings.sortedBy { it.timeMs }.filter { it.timeMs in fromMs..toMs }
    if (pts.isEmpty()) return
    val min = pts.minOf { it.value }
    val max = pts.maxOf { it.value }
    Spacer(Modifier.height(10.dp))
    Row {
        Spacer(Modifier.width(30.dp))
        Text(label, fontSize = 11.sp, color = color, modifier = Modifier.weight(1f))
        Text("$min–$max $unit", fontSize = 11.sp, color = Palette.Faint)
    }
    Spacer(Modifier.height(4.dp))
    val pad = maxOf(2, (max - min) / 5)
    val lo = min - pad
    val hi = max + pad
    Row {
        Column(Modifier.width(30.dp).height(44.dp)) {
            Text("$hi", fontSize = 10.sp, color = Palette.Faint)
            Spacer(Modifier.weight(1f))
            Text("$lo", fontSize = 10.sp, color = Palette.Faint)
        }
        Canvas(
            Modifier
                .weight(1f)
                .height(44.dp)
                .semantics { contentDescription = "$label today, $min to $max $unit" },
        ) {
            fun x(ms: Long) = ((ms - fromMs).toFloat() / spanMs) * size.width
            fun y(v: Int) = size.height - ((v - lo).toFloat() / (hi - lo)) * size.height
            drawLine(Palette.Line, Offset(0f, size.height), Offset(size.width, size.height), 1f)
            val path = Path()
            pts.forEachIndexed { i, m ->
                val joined = i > 0 && m.timeMs - pts[i - 1].timeMs <= GAP_MS
                if (joined) path.lineTo(x(m.timeMs), y(m.value)) else path.moveTo(x(m.timeMs), y(m.value))
            }
            drawPath(path, color.copy(alpha = 0.85f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            pts.forEach { drawCircle(color, 2.5.dp.toPx(), Offset(x(it.timeMs), y(it.value))) }
            selectedMs?.let { t ->
                val sx = x(t)
                drawLine(Palette.Text2.copy(alpha = 0.5f), Offset(sx, 0f), Offset(sx, size.height), 1.5f)
            }
        }
    }
}
