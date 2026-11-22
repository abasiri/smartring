package dev.ring.companion.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ring.companion.data.Measurement
import dev.ring.companion.data.SleepNight
import dev.ring.companion.graph
import dev.ring.companion.protocol.Ycbt
import dev.ring.companion.ring.RingSession
import dev.ring.companion.ui.LabelStyle
import dev.ring.companion.ui.Palette
import dev.ring.companion.ui.Panel
import dev.ring.companion.ui.RingIcons
import dev.ring.companion.ui.ScreenTitle
import dev.ring.companion.ui.SectionLabel
import dev.ring.companion.ui.numberStyle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** Stage colors, shared by every sleep chart. */
object SleepColors {
    val Deep = Color(0xFF6B7BFF)
    val Light = Color(0xFF7CC4FF)
    val Rem = Color(0xFFC79BFF)
    val Awake = Color(0xFFF5B342)

    fun of(stage: Int) = when (stage) {
        Ycbt.Stage.DEEP -> Deep
        Ycbt.Stage.REM -> Rem
        Ycbt.Stage.AWAKE -> Awake
        else -> Light
    }
}

/** Lanes top to bottom, as on a hypnogram: awake at the top, deep at the bottom. */
private val Lanes = listOf(Ycbt.Stage.AWAKE, Ycbt.Stage.REM, Ycbt.Stage.LIGHT, Ycbt.Stage.DEEP)
/** Unknown stage codes are drawn as light sleep. */
private fun laneOf(stage: Int) = Lanes.indexOf(stage).let { if (it < 0) 2 else it }
private fun laneName(stage: Int) = when (stage) {
    Ycbt.Stage.DEEP -> "Deep"
    Ycbt.Stage.REM -> "REM"
    Ycbt.Stage.AWAKE -> "Awake"
    else -> "Light"
}

fun formatSleep(seconds: Long): String {
    val m = (seconds + 30) / 60
    return if (m < 60) "${m}m" else "${m / 60}h ${"%02d".format(m % 60)}m"
}

private val Clock = DateTimeFormatter.ofPattern("HH:mm")
private fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(Clock)

@Composable
fun SleepScreen() {
    val g = LocalContext.current.graph
    val rawNights by g.sleep.nights.collectAsState()
    val syncing by g.history.syncing.collectAsState()
    val lastSync by g.sleep.lastSyncMs.collectAsState()
    val status by g.session.status.collectAsState()
    val measurements by g.measurements.all.collectAsState()
    val goalSec = g.prefs.sleepGoalMin * 60L
    // Nights with wake/onset corrected by heart rate (see SleepNight.withHeartRate).
    val adjusted = remember(rawNights, measurements) {
        SleepNight.adjustAll(rawNights, measurements.filter { it.kind == Measurement.HEART_RATE }.map { it.timeMs to it.value })
    }
    val nights = adjusted.map { it.first }

    var selected by rememberSaveable { mutableIntStateOf(-1) }
    val index = if (nights.isEmpty()) -1 else if (selected in nights.indices) selected else nights.lastIndex
    val night = nights.getOrNull(index)

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ScreenTitle("Sleep", night?.let { nightTitle(it) } ?: "Detected by your ring")
            }
            SyncButton(syncing, enabled = status == RingSession.Status.Connected) { g.history.syncNow() }
        }

        if (night == null) {
            EmptySleep(lastSync, status == RingSession.Status.Connected)
            return@Column
        }

        NightPicker(
            canBack = index > 0, canForward = index < nights.lastIndex,
            onBack = { selected = index - 1 }, onForward = { selected = index + 1 },
            label = night.wakeDate().format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
        )

        Summary(night, goalSec, adjusted.getOrNull(index)?.second)

        val hr = measurements.filter { it.kind == Measurement.HEART_RATE && it.timeMs in night.startMs..night.endMs }
        Panel(spacing = 12.dp) {
            SectionLabel("Through the night")
            Hypnogram(night)
            Legend()
            if (hr.size >= 2) {
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Heart rate", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.weight(1f))
                    Text("lowest ${hr.minOf { it.value }} bpm", fontSize = 12.sp, color = Palette.Muted)
                }
                OvernightLine(night, hr.map { it.timeMs to it.value }, Palette.Heart, "Heart rate", "bpm", markLowest = true)
            }
        }

        val hrv = measurements.filter { it.kind == Measurement.HRV && it.timeMs in night.startMs..night.endMs }
        val breath = measurements.filter { it.kind == Measurement.BREATHING && it.timeMs in night.startMs..night.endMs }
        if (hrv.isNotEmpty() || breath.isNotEmpty()) {
            Panel(spacing = 10.dp) {
                SectionLabel("Recovery · estimated")
                if (hrv.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("HRV", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.weight(1f))
                        Text("average ${hrv.map { it.value }.average().toInt()} ms", fontSize = 12.sp, color = Palette.Muted)
                    }
                    if (hrv.size >= 2) OvernightLine(night, hrv.map { it.timeMs to it.value }, SleepColors.Light, "HRV", "ms", markLowest = false)
                }
                if (breath.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text("Breathing", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.weight(1f))
                        Text("average ${breath.map { it.value }.average().toInt()} per min", fontSize = 12.sp, color = Palette.Muted)
                    }
                    if (breath.size >= 2) OvernightLine(night, breath.map { it.timeMs to it.value }, SleepColors.Rem, "Breathing rate", "per minute", markLowest = false)
                }
                Text(
                    "Your ring estimates these from a short reading every ${g.prefs.measureInterval.value} minutes. Compare nights with each other, not with other devices.",
                    fontSize = 12.sp, color = Palette.Faint, lineHeight = 17.sp,
                )
            }
        }

        Breakdown(night)

        Week(nights, index, goalSec, onSelect = { selected = it })

        Text(
            "Your ring detects sleep and its stages on its own, using movement and heart rate. " +
                "The app copies the results whenever it connects.",
            fontSize = 12.sp, color = Palette.Faint, lineHeight = 18.sp,
        )
    }
}

private fun nightTitle(n: SleepNight): String {
    val today = LocalDate.now()
    return when (n.wakeDate()) {
        today -> "Last night"
        today.minusDays(1) -> "The night before last"
        else -> "Night ending ${n.wakeDate().format(DateTimeFormatter.ofPattern("MMM d"))}"
    }
}

@Composable
private fun SyncButton(syncing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .clickable(enabled = enabled && !syncing, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(RingIcons.Refresh, null, tint = if (enabled) Palette.Accent else Palette.Stale, modifier = Modifier.size(16.dp))
        Text(
            if (syncing) "Syncing…" else "Sync",
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Palette.Text else Palette.Stale,
        )
    }
}

@Composable
private fun NightPicker(canBack: Boolean, canForward: Boolean, onBack: () -> Unit, onForward: () -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepButton(RingIcons.Back, "Earlier night", canBack, onBack)
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text2, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        StepButton(RingIcons.Chevron, "Later night", canForward, onForward)
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) Palette.Text else Palette.Stale, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Summary(n: SleepNight, goalSec: Long, adj: SleepNight.Adjustment? = null) {
    Panel(spacing = 6.dp) {
        SectionLabel("Asleep")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val m = (n.asleepSec + 30) / 60
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${m / 60}", style = numberStyle(56))
                Text("h", fontSize = 16.sp, color = Palette.Muted, modifier = Modifier.padding(start = 2.dp, end = 8.dp, bottom = 8.dp))
                Text("%02d".format(m % 60), style = numberStyle(56))
                Text("m", fontSize = 16.sp, color = Palette.Muted, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
            }
        }
        Text(
            "${clock(adj?.onsetMs ?: n.startMs)} – ${clock(adj?.wakeMs ?: n.endMs)} · in bed ${formatSleep((n.endMs - n.startMs) / 1000)}",
            fontSize = 13.sp, color = Palette.Muted,
        )
        if (adj != null) {
            val parts = listOfNotNull(
                adj.onsetMs?.let { "fell asleep ~${clock(it)} (ring: ${clock(n.startMs)})" },
                adj.wakeMs?.let { "woke ~${clock(it)} (ring: ${clock(n.endMs)})" },
            )
            Text(
                "Adjusted using heart rate: " + parts.joinToString(", ") +
                    ". Your heart rate was ${adj.awakeBpm} bpm vs about ${adj.sleepingBpm} asleep.",
                fontSize = 12.sp, color = Palette.Faint, lineHeight = 17.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        val frac = (n.asleepSec.toFloat() / goalSec).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Line)) {
            Box(Modifier.fillMaxWidth(frac).height(6.dp).background(SleepColors.Light))
        }
        val diff = n.asleepSec - goalSec
        Text(
            when {
                diff >= 0 -> "Goal ${formatSleep(goalSec)} · reached"
                else -> "${formatSleep(-diff)} short of your ${formatSleep(goalSec)} goal"
            },
            fontSize = 12.sp, color = Palette.Muted,
        )
    }
}

/** Stage timeline: one lane per stage, blocks where you were in it, thin steps joining them. */
@Composable
private fun Hypnogram(n: SleepNight) {
    val measurer = rememberTextMeasurer()
    val labelStyle = LabelStyle.copy(letterSpacing = 0.sp, fontSize = 11.sp)
    val span = (n.endMs - n.startMs).coerceAtLeast(1)
    val description = "Sleep stages from ${clock(n.startMs)} to ${clock(n.endMs)}: " +
        Lanes.joinToString(", ") { "${laneName(it)} ${formatSleep(n.stages.filter { s -> s.stage == it }.sumOf { s -> s.seconds.toLong() })}" }

    Row {
        Column(Modifier.width(48.dp)) {
            Lanes.forEach { lane ->
                Box(Modifier.height(30.dp), contentAlignment = Alignment.CenterStart) {
                    Text(laneName(lane), fontSize = 11.sp, color = SleepColors.of(lane), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Canvas(
            Modifier
                .weight(1f)
                .height(30.dp * Lanes.size + 22.dp)
                .semantics { contentDescription = description },
        ) {
            val laneH = 30.dp.toPx()
            val chartH = laneH * Lanes.size
            fun x(ms: Long) = ((ms - n.startMs).toFloat() / span) * size.width

            // Lane guides
            for (i in 0..Lanes.size) {
                drawLine(Palette.Line.copy(alpha = 0.5f), Offset(0f, i * laneH), Offset(size.width, i * laneH), 1f)
            }
            // Joins between consecutive stages
            n.stages.zipWithNext().forEach { (a, b) ->
                val xa = x(b.startMs)
                val ya = laneOf(a.stage) * laneH + laneH / 2
                val yb = laneOf(b.stage) * laneH + laneH / 2
                drawLine(Palette.Muted.copy(alpha = 0.35f), Offset(xa, ya), Offset(xa, yb), 1.5f)
            }
            // Blocks
            val pad = 5.dp.toPx()
            n.stages.forEach { s ->
                val lane = laneOf(s.stage)
                val left = x(s.startMs)
                val w = (x(s.endMs) - left).coerceAtLeast(2f)
                drawRoundRect(
                    SleepColors.of(s.stage),
                    topLeft = Offset(left, lane * laneH + pad),
                    size = Size(w, laneH - pad * 2),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
            // Time axis: start, each full hour that fits, end
            val y = chartH + 6.dp.toPx()
            val startLabel = measurer.measure(clock(n.startMs), labelStyle)
            val endLabel = measurer.measure(clock(n.endMs), labelStyle)
            drawText(startLabel, topLeft = Offset(0f, y))
            drawText(endLabel, topLeft = Offset(size.width - endLabel.size.width, y))
            val zone = ZoneId.systemDefault()
            var hour = Instant.ofEpochMilli(n.startMs).atZone(zone).withMinute(0).withSecond(0).withNano(0).plusHours(1)
            val step = if (span > 8 * 3600_000L) 2L else 1L
            while (hour.toInstant().toEpochMilli() < n.endMs) {
                val hx = x(hour.toInstant().toEpochMilli())
                val label = measurer.measure(hour.format(DateTimeFormatter.ofPattern("HH")), labelStyle)
                val lx = hx - label.size.width / 2
                if (lx > startLabel.size.width + 8 && lx + label.size.width < size.width - endLabel.size.width - 8) {
                    drawLine(Palette.Line, Offset(hx, chartH), Offset(hx, chartH + 4.dp.toPx()), 1.5f)
                    drawText(label, topLeft = Offset(lx, y))
                }
                hour = hour.plusHours(step)
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(start = 48.dp)) {
        listOf(Ycbt.Stage.DEEP, Ycbt.Stage.LIGHT, Ycbt.Stage.REM, Ycbt.Stage.AWAKE).forEach {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(SleepColors.of(it)))
                Text(laneName(it), fontSize = 11.sp, color = Palette.Muted)
            }
        }
    }
}

/** Background heart-rate readings the ring took during the night, on the same time axis. */
/** One reading series on the night's time axis. Highest (or lowest) point gets a bigger dot. */
@Composable
private fun OvernightLine(
    n: SleepNight,
    points: List<Pair<Long, Int>>,
    color: Color,
    name: String,
    unit: String,
    markLowest: Boolean,
) {
    val lo = points.minOf { it.second } - 4
    val hi = points.maxOf { it.second } + 4
    val span = (n.endMs - n.startMs).coerceAtLeast(1)
    Row {
        Column(Modifier.width(48.dp).height(56.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("$hi", fontSize = 10.sp, color = Palette.Faint)
            Text("$lo", fontSize = 10.sp, color = Palette.Faint)
        }
        Canvas(
            Modifier
                .weight(1f)
                .height(56.dp)
                .semantics { contentDescription = "$name overnight, ${points.minOf { it.second }} to ${points.maxOf { it.second }} $unit" },
        ) {
            fun px(p: Pair<Long, Int>) = Offset(
                ((p.first - n.startMs).toFloat() / span) * size.width,
                size.height - ((p.second - lo).toFloat() / (hi - lo)) * size.height,
            )
            val path = Path()
            points.forEachIndexed { i, p -> px(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
            drawPath(path, color.copy(alpha = 0.8f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            val mark = if (markLowest) points.minBy { it.second } else points.maxBy { it.second }
            points.forEach { drawCircle(color, if (it == mark) 4.dp.toPx() else 2.5.dp.toPx(), px(it)) }
        }
    }
}

@Composable
private fun Breakdown(n: SleepNight) {
    val rows = listOf(
        Triple(Ycbt.Stage.DEEP, n.deepSec, "Body repair and recovery"),
        Triple(Ycbt.Stage.LIGHT, n.lightSec, "Most of a normal night"),
        Triple(Ycbt.Stage.REM, n.remSec, "Dreaming; memory and mood"),
        Triple(Ycbt.Stage.AWAKE, n.awakeSec, "Short wake-ups are normal"),
    )
    val total = rows.sumOf { it.second }.coerceAtLeast(1)
    Panel(spacing = 12.dp) {
        SectionLabel("Stages")
        // One bar split by share of the night
        Row(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(6.dp))) {
            rows.filter { it.second > 0 }.forEach { (stage, sec, _) ->
                Box(Modifier.weight(sec.toFloat()).height(12.dp).background(SleepColors.of(stage)))
            }
        }
        rows.forEach { (stage, sec, hint) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(SleepColors.of(stage)))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(laneName(stage), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                    Text(hint, fontSize = 12.sp, color = Palette.Muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(formatSleep(sec), style = numberStyle(22, FontWeight.SemiBold))
                    Text("${(sec * 100 / total)}%", fontSize = 12.sp, color = Palette.Muted)
                }
            }
        }
    }
}

/** Seven nights ending with the selected one, stacked by stage, with the goal as a dashed line. */
@Composable
private fun Week(nights: List<SleepNight>, selected: Int, goalSec: Long, onSelect: (Int) -> Unit) {
    val end = nights[selected].wakeDate()
    val days = (6 downTo 0).map { end.minusDays(it.toLong()) }
    val byDay = days.map { d -> nights.indexOfLast { it.wakeDate() == d }.takeIf { it >= 0 } }
    val shown = byDay.filterNotNull().map { nights[it] }
    val avg = if (shown.isEmpty()) 0 else shown.sumOf { it.asleepSec } / shown.size
    val top = maxOf(goalSec + 3600, shown.maxOfOrNull { it.asleepSec } ?: 0L).toFloat()

    Panel(spacing = 12.dp) {
        Row(verticalAlignment = Alignment.Bottom) {
            SectionLabel("7 nights", Modifier.weight(1f))
            Text("average ${formatSleep(avg)}", fontSize = 12.sp, color = Palette.Muted)
        }
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            days.forEachIndexed { i, d ->
                val idx = byDay[i]
                val n = idx?.let { nights[it] }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (idx == selected) Palette.Surface2 else Color.Transparent)
                        .clickable(enabled = idx != null, role = Role.Button) { idx?.let(onSelect) }
                        .semantics {
                            contentDescription = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()) +
                                (n?.let { ", ${formatSleep(it.asleepSec)}" } ?: ", no data")
                        }
                        .padding(horizontal = 3.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Canvas(Modifier.weight(1f).fillMaxWidth()) {
                        // goal line
                        val gy = size.height - (goalSec / top) * size.height
                        drawLine(
                            Palette.Muted.copy(alpha = 0.5f), Offset(-4.dp.toPx(), gy), Offset(size.width + 4.dp.toPx(), gy), 1.5f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                        )
                        if (n == null) {
                            drawRoundRect(Palette.Line, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx()), CornerRadius(2f))
                            return@Canvas
                        }
                        var y = size.height
                        listOf(Ycbt.Stage.DEEP to n.deepSec, Ycbt.Stage.LIGHT to n.lightSec, Ycbt.Stage.REM to n.remSec).forEach { (stage, sec) ->
                            val h = (sec / top) * size.height
                            if (h > 0) {
                                drawRect(SleepColors.of(stage), Offset(0f, y - h), Size(size.width, h))
                                y -= h
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(1),
                        fontSize = 11.sp, fontWeight = if (idx == selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (idx == selected) Palette.Text else Palette.Muted,
                    )
                }
            }
        }
        Text("Dashed line: your ${formatSleep(goalSec)} goal. Tap a night to see it.", fontSize = 12.sp, color = Palette.Faint)
    }
}

@Composable
private fun EmptySleep(lastSync: Long, connected: Boolean) {
    Panel(spacing = 10.dp) {
        Icon(RingIcons.Moon, null, tint = SleepColors.Rem, modifier = Modifier.size(32.dp))
        Text("No sleep recorded yet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
        Text(
            "Wear the ring to bed. It detects sleep on its own, even when your phone is away. " +
                "In the morning, open the app and your night shows up here.",
            fontSize = 14.sp, color = Palette.Muted, lineHeight = 20.sp,
        )
        Text(
            when {
                !connected -> "Connect the ring to check for new nights."
                lastSync > 0 -> "Checked the ring at ${clock(lastSync)}. Nothing stored yet."
                else -> "Checking the ring…"
            },
            fontSize = 12.sp, color = Palette.Faint,
        )
    }
}
