package dev.ring.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ring.companion.data.Measurement
import dev.ring.companion.graph
import dev.ring.companion.ring.RingSession
import dev.ring.companion.ui.ActivityRingsCard
import dev.ring.companion.ui.HrDayChart
import dev.ring.companion.ui.RingGoal
import dev.ring.companion.ui.Bar
import dev.ring.companion.ui.BarChart
import dev.ring.companion.ui.BigValue
import dev.ring.companion.ui.LabelStyle
import dev.ring.companion.ui.Palette
import dev.ring.companion.ui.Panel
import dev.ring.companion.ui.PrimaryButton
import dev.ring.companion.ui.RingChip
import dev.ring.companion.ui.RingIcons
import dev.ring.companion.ui.ScreenTitle
import dev.ring.companion.ui.SectionLabel
import dev.ring.companion.workout.HrZones
import dev.ring.companion.workout.WorkoutKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TodayScreen(
    onStartWorkout: (WorkoutKind) -> Unit,
    onMeasure: (String) -> Unit,
    onOpenRing: () -> Unit,
    onResumeWorkout: () -> Unit,
    onOpenSleep: () -> Unit,
) {
    val g = LocalContext.current.graph
    val status by g.session.status.collectAsState()
    val device by g.session.device.collectAsState()
    val activity by g.session.activity.collectAsState()
    val measurements by g.measurements.all.collectAsState()
    val maxHr by g.prefs.maxHr.collectAsState()
    val live by g.workout.live.collectAsState()
    val workouts by g.workouts.all.collectAsState()
    val nights by g.sleep.nights.collectAsState()
    var kind by rememberSaveable { mutableStateOf(WorkoutKind.Run) }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val startOfDay = today.atStartOfDay(zone).toInstant().toEpochMilli()
    val lastHr = measurements.lastOrNull { it.kind == Measurement.HEART_RATE }
    val lastSpo2 = measurements.lastOrNull { it.kind == Measurement.SPO2 }
    // Latest estimates from the ring's background reading, if from the last 2 hours.
    val recent = System.currentTimeMillis() - 2 * 3600_000L
    val lastHrv = measurements.lastOrNull { it.kind == Measurement.HRV }?.takeIf { it.timeMs >= recent }
    val lastBreath = measurements.lastOrNull { it.kind == Measurement.BREATHING }?.takeIf { it.timeMs >= recent }
    val todayHrv = measurements.filter { it.kind == Measurement.HRV && it.timeMs >= startOfDay }
    val todayHr = measurements.filter { it.kind == Measurement.HEART_RATE && it.timeMs >= startOfDay }

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
                ScreenTitle("Today", today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")))
            }
            val paused by g.prefs.connectionPaused.state.collectAsState()
            val lastBattery by g.battery.samples.collectAsState()
            RingChip(status, device?.name, device?.battery ?: lastBattery.lastOrNull()?.percent, onOpenRing, paused)
        }

        if (live != null) {
            PrimaryButton("Back to your ${live!!.kind.label.lowercase()}", onResumeWorkout, Modifier.fillMaxWidth(), RingIcons.Play)
        } else {
            Panel(spacing = 12.dp) {
                SectionLabel("Start a workout")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WorkoutKind.entries.forEach { k ->
                        val selected = k == kind
                        Box(
                            Modifier
                                .weight(1f)
                                .heightIn(min = 40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (selected) Palette.Selected else Palette.Surface2)
                                .then(if (selected) Modifier.border(2.dp, Palette.Accent, RoundedCornerShape(12.dp)) else Modifier)
                                .clickable(role = Role.RadioButton) { kind = k },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                k.label, fontSize = 14.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (selected) Palette.Text else Palette.Text2,
                            )
                        }
                    }
                }
                PrimaryButton("Start ${kind.label.lowercase()}", { onStartWorkout(kind) }, Modifier.fillMaxWidth(), RingIcons.Play)
            }
        }

        val exerciseMin = (workouts.filter { it.startMs >= startOfDay }.sumOf { it.activeMs } +
            (live?.activeMs ?: 0L)) / 60_000L
        ActivityRingsCard(
            listOf(
                RingGoal("Move", activity?.calories ?: 0, g.prefs.moveGoalKcal, "kcal", Palette.Heart),
                RingGoal("Exercise", exerciseMin.toInt(), g.prefs.exerciseGoalMin, "min", Palette.Good),
                RingGoal("Steps", activity?.steps ?: 0, g.prefs.stepGoal, "", Palette.Accent),
            ),
            Modifier.fillMaxWidth(),
        )

        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(
                Modifier.weight(1f).fillMaxHeight(), RingIcons.Heart, Palette.Heart, "Heart rate",
                value = lastHr?.value?.toString() ?: "–", unit = "bpm",
                caption = lastHr?.let { timeAgo(it.timeMs) } ?: "No reading yet",
                action = "Measure" to { onMeasure(Measurement.HEART_RATE) },
                extra = listOfNotNull(
                    lastHrv?.let { "HRV ${it.value} ms" },
                    lastBreath?.let { "${it.value} breaths/min" },
                ).joinToString(" · ").ifEmpty { null },
            )
            Tile(
                Modifier.weight(1f).fillMaxHeight(), RingIcons.Drop, Palette.Accent, "Blood O₂",
                value = lastSpo2?.value?.toString() ?: "–", unit = "%",
                caption = lastSpo2?.let { timeAgo(it.timeMs) } ?: "No reading yet",
                action = "Measure" to { onMeasure(Measurement.SPO2) },
            )
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(
                Modifier.weight(1f).fillMaxHeight(), RingIcons.Pulse, Palette.Good, "Distance",
                value = activity?.let { "%.1f".format(it.distanceM / 1000.0) } ?: "–", unit = "km",
                caption = if (activity == null) "Waiting for ring" else "Counted by the ring", action = null,
            )
            // Only a night that ended today counts as "last night".
            val lastNight = nights.lastOrNull()?.takeIf { it.wakeDate() == today }
                ?.withHeartRate(measurements.filter { it.kind == Measurement.HEART_RATE }.map { it.timeMs to it.value })?.first
            val asleepMin = lastNight?.let { (it.asleepSec + 30) / 60 }
            Tile(
                Modifier.weight(1f).fillMaxHeight(), RingIcons.Moon, SleepColors.Rem, "Last night",
                value = asleepMin?.let { "${it / 60}:%02d".format(it % 60) } ?: "–", unit = "h",
                caption = lastNight?.let { "Deep ${formatSleep(it.deepSec)}" }
                    ?: if (nights.isNotEmpty()) "Nothing recorded yet" else "Wear it to bed",
                action = "Open" to onOpenSleep,
            )
        }

        Panel {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Heart rate today", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, modifier = Modifier.weight(1f))
                if (todayHr.isNotEmpty()) {
                    Text("${todayHr.minOf { it.value }}–${todayHr.maxOf { it.value }} bpm", fontSize = 12.sp, color = Palette.Muted)
                }
            }
            if (todayHr.isEmpty()) {
                Text("Your ring's readings through the day show up here.", fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.padding(vertical = 18.dp))
            } else {
                HrDayChart(
                    todayHr, startOfDay,
                    bands = workouts.filter { it.endMs >= startOfDay }.map {
                        dev.ring.companion.ui.ChartBand(it.startMs, it.endMs, it.kind)
                    },
                    maxHr = maxHr,
                    hrv = todayHrv,
                    breathing = measurements.filter { it.kind == Measurement.BREATHING && it.timeMs >= startOfDay },
                )
            }
        }

        if (status != RingSession.Status.Connected) {
            Text(
                "Measurements and workouts need the ring connected. Tap the ring status at the top to reconnect.",
                fontSize = 13.sp, color = Palette.Faint, lineHeight = 19.sp,
            )
        }
    }
}

private val PaddingTile = androidx.compose.foundation.layout.PaddingValues(14.dp)

@Composable
private fun TileLabel(icon: ImageVector, tint: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        SectionLabel(text)
    }
}

@Composable
private fun Tile(
    modifier: Modifier,
    icon: ImageVector,
    tint: Color,
    label: String,
    value: String,
    unit: String,
    caption: String,
    action: Pair<String, () -> Unit>?,
    extra: String? = null,
) {
    Panel(modifier, PaddingTile, spacing = 4.dp) {
        // Same label-row height with or without an action link, so tiles side by side line up.
        Row(Modifier.height(32.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { TileLabel(icon, tint, label) }
            if (action != null) {
                Text(
                    action.first,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClick = action.second)
                        .padding(horizontal = 2.dp, vertical = 6.dp),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent,
                )
            }
        }
        BigValue(value, unit)
        Text(caption, fontSize = 12.sp, color = Palette.Muted)
        if (extra != null) Text(extra, fontSize = 12.sp, color = Palette.Faint)
    }
}

fun timeAgo(ms: Long): String {
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    val mins = (System.currentTimeMillis() - ms) / 60_000
    val clock = t.format(DateTimeFormatter.ofPattern("HH:mm"))
    return when {
        mins < 1 -> "$clock · just now"
        mins < 60 -> "$clock · $mins min ago"
        mins < 24 * 60 -> "$clock · ${mins / 60} h ago"
        else -> t.format(DateTimeFormatter.ofPattern("MMM d, HH:mm"))
    }
}
