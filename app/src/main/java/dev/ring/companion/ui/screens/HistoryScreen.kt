package dev.ring.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ring.companion.data.Measurement
import dev.ring.companion.data.WorkoutRecord
import dev.ring.companion.graph
import dev.ring.companion.ui.Bar
import dev.ring.companion.ui.BarChart
import dev.ring.companion.ui.Divider
import dev.ring.companion.ui.MiniStat
import dev.ring.companion.ui.Palette
import dev.ring.companion.ui.Panel
import dev.ring.companion.ui.RingIcons
import dev.ring.companion.ui.ScreenTitle
import dev.ring.companion.ui.formatDuration
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

@Composable
fun HistoryScreen(onOpenWorkout: (Long) -> Unit) {
    val g = LocalContext.current.graph
    val workouts by g.workouts.all.collectAsState()
    val measurements by g.measurements.all.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("History")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Palette.Surface).padding(4.dp),
        ) {
            listOf("Workouts", "Daily").forEachIndexed { i, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (tab == i) Palette.Selected else Color.Transparent)
                        .clickable(role = Role.Tab) { tab = i },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label, fontSize = 14.sp,
                        fontWeight = if (tab == i) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (tab == i) Palette.Text else Palette.Muted,
                    )
                }
            }
        }
        if (tab == 0) Workouts(workouts, onOpenWorkout) else Daily(measurements)
    }
}

@Composable
private fun Workouts(workouts: List<WorkoutRecord>, onOpen: (Long) -> Unit) {
    val zone = ZoneId.systemDefault()
    val monday = LocalDate.now(zone).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekStart = monday.atStartOfDay(zone).toInstant().toEpochMilli()
    val week = workouts.filter { it.startMs >= weekStart }

    Panel {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("This week", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.weight(1f))
            Text(
                "${monday.format(DateTimeFormatter.ofPattern("MMM d"))} – ${monday.plusDays(6).format(DateTimeFormatter.ofPattern("MMM d"))}",
                fontSize = 12.sp, color = Palette.Muted,
            )
        }
        Row {
            MiniStat(week.size.toString(), "workouts", Modifier.weight(1f))
            MiniStat(formatDuration(week.sumOf { it.activeMs }).let { if (it.count { c -> c == ':' } == 2) it.substringBeforeLast(':') else it }, "active", Modifier.weight(1f))
            MiniStat("%.1f".format(week.sumOf { it.distanceM } / 1000), "km", Modifier.weight(1f))
        }
        val perDay = (0..6).map { d ->
            val day = monday.plusDays(d.toLong())
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            week.filter { it.startMs in from until to }.sumOf { it.activeMs }
        }
        val most = (perDay.maxOrNull() ?: 0L).coerceAtLeast(1L)
        BarChart(perDay.map { if (it > 0) Bar(it.toFloat() / most, Palette.Accent) else Bar(0.06f, Palette.Line) }, 40.dp, gapPx = 18f)
        Row {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                Text(it, fontSize = 11.sp, color = Palette.Muted, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }

    if (workouts.isEmpty()) {
        Text("Finished workouts show up here.", fontSize = 14.sp, color = Palette.Muted)
        return
    }
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface)) {
        workouts.forEachIndexed { i, w ->
            if (i > 0) Divider()
            val tagColor = when (w.kind) { "Walk" -> Palette.Good; "Strength" -> Palette.Warn; else -> Palette.Accent }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 68.dp)
                    .clickable(role = Role.Button) { onOpen(w.id) }
                    .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Palette.Surface2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(w.kind.take(3).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tagColor)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(w.kind, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                    val bits = listOfNotNull(
                        dayLabel(w.startMs),
                        if (w.distanceM > 0) "%.2f km".format(w.distanceM / 1000) else null,
                        formatDuration(w.activeMs),
                    )
                    Text(bits.joinToString(" · "), fontSize = 12.sp, color = Palette.Muted)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(w.avgHr?.toString() ?: "–", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                    Text("avg bpm", fontSize = 11.sp, color = Palette.Muted)
                }
                Icon(RingIcons.Chevron, null, tint = Palette.Stale, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun Daily(measurements: List<Measurement>) {
    if (measurements.isEmpty()) {
        Text("Heart-rate and blood-oxygen readings you take show up here, grouped by day.", fontSize = 14.sp, color = Palette.Muted, lineHeight = 20.sp)
        return
    }
    val zone = ZoneId.systemDefault()
    val byDay = measurements.groupBy { Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() }.toSortedMap(reverseOrder())
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface)) {
        byDay.entries.forEachIndexed { i, (day, list) ->
            if (i > 0) Divider()
            val hr = list.filter { it.kind == Measurement.HEART_RATE }.map { it.value }
            val ox = list.filter { it.kind == Measurement.SPO2 }.map { it.value }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(day.format(DateTimeFormatter.ofPattern("EEEE, MMM d")), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
                    Text("${list.size} readings", fontSize = 12.sp, color = Palette.Muted)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (hr.isNotEmpty()) Text("${hr.min()}–${hr.max()} bpm", fontSize = 13.sp, color = Palette.Text)
                    if (ox.isNotEmpty()) Text("O₂ ${ox.min()}–${ox.max()}%", fontSize = 13.sp, color = Palette.Muted)
                }
            }
        }
    }
}

private fun dayLabel(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> if (d.isAfter(today.minusDays(7))) d.format(DateTimeFormatter.ofPattern("EEE")) else d.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}
