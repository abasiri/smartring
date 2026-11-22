package dev.ring.companion.ui.screens

import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.ring.companion.data.WorkoutRecord
import dev.ring.companion.graph
import dev.ring.companion.ui.Bar
import dev.ring.companion.ui.BarChart
import dev.ring.companion.ui.LabelStyle
import dev.ring.companion.ui.MiniStat
import dev.ring.companion.ui.Palette
import dev.ring.companion.ui.Panel
import dev.ring.companion.ui.PrimaryButton
import dev.ring.companion.ui.RingIcons
import dev.ring.companion.ui.SecondaryButton
import dev.ring.companion.ui.formatDuration
import dev.ring.companion.ui.formatKm
import dev.ring.companion.ui.formatPace
import dev.ring.companion.workout.HrZones
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SummaryScreen(id: Long, justFinished: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val g = context.graph
    val all by g.workouts.all.collectAsState()
    val r = all.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    if (r == null) {
        Box(Modifier.fillMaxSize().background(Palette.Ground))
        return
    }

    Column(Modifier.fillMaxSize().background(Palette.Ground)) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!justFinished) {
                Row(
                    Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onDone),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(RingIcons.Back, null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
                    Text("History", color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Header(r, justFinished)
            Totals(r)
            HeartChart(r)
            Zones(r)
            Row(
                Modifier.padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(RingIcons.Ring, null, tint = if (r.gaps.isEmpty()) Palette.Good else Palette.Warn, modifier = Modifier.size(18.dp))
                Text(connectionLine(r), fontSize = 13.sp, color = Palette.Muted)
            }
        }
        Row(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp, top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (confirmDelete) {
                SecondaryButton("Delete workout", {
                    scope.launch {
                        g.workouts.delete(r.id)
                        g.measurements.removeWorkout(r.startMs, r.endMs)
                        onDone()
                    }
                }, Modifier.weight(2f), color = Palette.EndText, background = Palette.EndBg)
                SecondaryButton("Keep", { confirmDelete = false }, Modifier.weight(1f))
            } else {
                PrimaryButton(if (justFinished) "Save ${r.kind.lowercase()}" else "Done", onDone, Modifier.weight(2f))
                SecondaryButton("Export", {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_SUBJECT, "${r.kind} ${dateLine(r)}")
                        putExtra(Intent.EXTRA_TEXT, r.toCsv())
                    }
                    context.startActivity(Intent.createChooser(send, "Export heart rate"))
                }, Modifier.weight(1f))
                SecondaryButton("Delete", { confirmDelete = true }, Modifier.weight(1f), color = Palette.EndText)
            }
        }
    }
}

@Composable
private fun Header(r: WorkoutRecord, justFinished: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (justFinished) Text("${r.kind.uppercase()} COMPLETE", style = LabelStyle.copy(fontSize = 12.sp, color = Palette.Good))
        Text(titleFor(r), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        Text(dateLine(r), fontSize = 13.sp, color = Palette.Muted)
    }
}

@Composable
private fun Totals(r: WorkoutRecord) {
    val items = buildList {
        add(formatDuration(r.activeMs) to "time")
        if (r.distanceM > 0) {
            add(formatKm(r.distanceM) to "km")
            add(formatPace((r.activeMs / 1000 / (r.distanceM / 1000)).toInt().takeIf { r.distanceM > 50 }) to "avg pace /km")
        }
        add((r.avgHr?.toString() ?: "–") to "avg bpm")
        add((r.maxHr?.toString() ?: "–") to "max bpm")
        add("%,d".format(r.steps) to "steps")
    }
    Panel(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp), spacing = 14.dp) {
        items.chunked(3).forEach { row ->
            Row {
                row.forEach { (v, c) -> MiniStat(v, c, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun HeartChart(r: WorkoutRecord) {
    Panel(spacing = 8.dp) {
        Text("Heart rate", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
        if (r.hrSamples.isEmpty()) {
            Text("No heart-rate readings were recorded.", fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.padding(vertical = 12.dp))
            return@Panel
        }
        val n = 46
        val slot = (r.activeMs / n).coerceAtLeast(1)
        val bars = List(n) { i ->
            val from = i * slot
            val to = from + slot
            val inGap = r.gaps.any { (a, b) -> a < to && b > from && (b - a) >= slot / 2 }
            val vals = r.hrSamples.filter { it.first in from until to }.map { it.second }
            when {
                vals.isNotEmpty() -> vals.average().toInt().let { Bar((it - 90) / 90f, Palette.Zones[HrZones.zoneOf(it, r.maxHrSetting)]) }
                inGap -> null
                else -> Bar(0f, Color.Transparent)
            }
        }
        BarChart(bars, 70.dp, gapPx = 2f, baseline = true)
        Row {
            listOf(0L, r.activeMs / 3, r.activeMs * 2 / 3).forEach {
                Text(formatDuration(it), style = LabelStyle.copy(letterSpacing = 0.sp), modifier = Modifier.weight(1f))
            }
            Text(formatDuration(r.activeMs), style = LabelStyle.copy(letterSpacing = 0.sp))
        }
    }
}

@Composable
private fun Zones(r: WorkoutRecord) {
    val total = r.zoneMs.drop(1).sum()
    if (total <= 0) return
    val max = r.zoneMs.drop(1).max().coerceAtLeast(1)
    Panel(spacing = 8.dp) {
        Text("Time in zones", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
        for (z in 1..5) {
            val ms = r.zoneMs.getOrElse(z) { 0L }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Z$z", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Palette.Text2, modifier = Modifier.width(26.dp))
                Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Surface2)) {
                    Box(
                        Modifier.fillMaxWidth(ms.toFloat() / max).height(12.dp).clip(RoundedCornerShape(3.dp))
                            .background(Palette.Zones[z]),
                    )
                }
                Text(formatDuration(ms), fontSize = 12.sp, color = Palette.Text, modifier = Modifier.width(46.dp))
                Text("${ms * 100 / total}%", fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.width(34.dp))
            }
        }
    }
}

private fun titleFor(r: WorkoutRecord): String {
    val hour = Instant.ofEpochMilli(r.startMs).atZone(ZoneId.systemDefault()).hour
    val part = when (hour) { in 5..11 -> "Morning"; in 12..16 -> "Afternoon"; in 17..21 -> "Evening"; else -> "Night" }
    return "$part ${r.kind.lowercase()}"
}

fun dateLine(r: WorkoutRecord): String {
    val z = ZoneId.systemDefault()
    val s = Instant.ofEpochMilli(r.startMs).atZone(z)
    val e = Instant.ofEpochMilli(r.endMs).atZone(z)
    val hm = DateTimeFormatter.ofPattern("HH:mm")
    return "${s.format(DateTimeFormatter.ofPattern("EEE, MMM d"))} · ${s.format(hm)} – ${e.format(hm)}"
}

private fun connectionLine(r: WorkoutRecord): String {
    val stepsNote = r.stepsAsOfMs?.let {
        " · Steps counted until " + Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")) +
            ", when the ring was last in reach"
    }.orEmpty()
    if (r.gaps.isEmpty()) return "Ring stayed connected the whole time$stepsNote"
    val longest = r.gaps.maxOf { it.second - it.first }
    val n = r.gaps.size
    return "Ring connected ${r.connectedPercent}% of the time · $n " +
        (if (n == 1) "gap" else "gaps") + ", longest ${formatDuration(longest)}$stepsNote"
}
