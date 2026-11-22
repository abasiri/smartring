package dev.ring.companion.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ring.companion.graph
import dev.ring.companion.ring.RingSession
import dev.ring.companion.ui.Bar
import dev.ring.companion.ui.BarChart
import dev.ring.companion.ui.Dot
import dev.ring.companion.ui.LabelStyle
import dev.ring.companion.ui.MiniStat
import dev.ring.companion.ui.Palette
import dev.ring.companion.ui.RingIcons
import dev.ring.companion.ui.RoundButton
import dev.ring.companion.ui.formatDuration
import dev.ring.companion.ui.formatKm
import dev.ring.companion.ui.formatPace
import dev.ring.companion.ui.numberStyle
import dev.ring.companion.workout.HrZones
import dev.ring.companion.workout.WorkoutController
import kotlinx.coroutines.launch

@Composable
fun WorkoutScreen(onFinished: (Long?) -> Unit) {
    val g = LocalContext.current.graph
    val live by g.workout.live.collectAsState()
    val screenOn by g.prefs.screenOnInWorkout.state.collectAsState()
    val scope = rememberCoroutineScope()

    val view = LocalView.current
    DisposableEffect(screenOn) {
        view.keepScreenOn = screenOn
        onDispose { view.keepScreenOn = false }
    }

    val w = live ?: run {
        Box(Modifier.fillMaxSize().background(Palette.Ground))
        return
    }
    val ringOk = w.ringStatus == RingSession.Status.Connected

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .padding(horizontal = 20.dp)
            .padding(top = 20.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (w.paused) "Paused · " else "") + w.kind.label.uppercase(),
                fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp,
                color = if (w.paused) Palette.Warn else Palette.Text, modifier = Modifier.weight(1f),
            )
            StatusPill("Ring", if (ringOk) Palette.Good else Palette.Warn, warn = !ringOk)
            if (w.gpsActive) {
                Spacer(Modifier.size(8.dp))
                StatusPill("GPS", if (w.gpsFix) Palette.Good else Palette.Warn, warn = false)
            }
        }

        if (!ringOk) ReconnectBanner(w.reconnectAttempt, w.kind.label.lowercase())

        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(formatDuration(w.activeMs), style = numberStyle(if (ringOk) 64 else 56, FontWeight.SemiBold))
            Text("ELAPSED", style = LabelStyle.copy(fontSize = 12.sp))
        }

        HeartPanel(w, ringOk)

        val stats = buildList {
            if (w.gpsActive) {
                add(formatKm(w.distanceM) to "km")
                add(formatPace(w.paceSecPerKm) to "pace /km")
            }
            add((w.cadence?.toString() ?: "–") to "steps /min")
            add((w.avgHr?.toString() ?: "–") to "avg bpm")
            add((w.maxHr?.toString() ?: "–") to "max bpm")
            add("%,d".format(w.steps) to "steps")
        }.take(6)
        stats.chunked(3).forEach { row ->
            Row(Modifier.padding(horizontal = 4.dp)) {
                row.forEach { (v, c) -> MiniStat(v, c, Modifier.weight(1f)) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (ringOk) {
                RoundButton(RingIcons.Flag, "Lap", "Mark lap", 76.dp, Palette.Surface2, Palette.Text) { g.workout.lap() }
            } else {
                RoundButton(RingIcons.Refresh, "Retry", "Retry ring connection now", 76.dp, Palette.WarnBg, Color(0xFFF5C76A), Palette.WarnLine) {
                    g.session.reconnectNow()
                }
            }
            RoundButton(
                if (w.paused) RingIcons.Play else RingIcons.Pause, null,
                if (w.paused) "Resume workout" else "Pause workout",
                96.dp, Palette.Accent, Palette.OnAccent,
            ) { g.workout.togglePause() }
            HoldToEnd { scope.launch { onFinished(g.workout.finish()) } }
        }
        Text(
            "Hold End to finish" + if (screenOn) " · Screen stays on" else "",
            fontSize = 12.sp, color = Palette.Faint, modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun HeartPanel(w: WorkoutController.Live, ringOk: Boolean) {
    val stale = w.hrAgeMs == null || w.hrAgeMs > 20_000 || !ringOk
    val zoneColor = Palette.Zones[w.zone]
    Column(
        Modifier.clip(RoundedCornerShape(22.dp)).background(Palette.Surface).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    if (stale) RingIcons.Heart else RingIcons.HeartFilled, null,
                    tint = if (stale) Palette.Stale else Palette.Heart,
                    modifier = Modifier.size(22.dp).align(Alignment.CenterVertically),
                )
                Text(w.hr?.toString() ?: "––", style = numberStyle(104, color = if (stale) Palette.Stale else Palette.Text))
                Text("bpm", fontSize = 16.sp, color = if (stale) Palette.Stale else Palette.Muted, modifier = Modifier.padding(bottom = 10.dp))
            }
            if (stale) {
                Pill(if (ringOk) "Waiting" else "No signal", Palette.Surface2, Palette.Muted)
            } else if (w.zone > 0) {
                Pill("Zone ${w.zone}", zoneColor, Color(0xFF14181F))
            }
        }

        if (stale) {
            Text(
                when {
                    w.hrAgeMs == null -> "Waiting for the first reading. It can take up to 30 s."
                    else -> "Last reading ${formatDuration(w.hrAgeMs)} ago"
                },
                fontSize = 13.sp, color = Palette.Muted,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (z in 1..5) {
                    Box(
                        Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(3.dp))
                            .background(Palette.Zones[z].copy(alpha = if (z == w.zone) 1f else 0.35f)),
                    )
                }
            }
            if (w.zone > 0) {
                val r = HrZones.range(w.zone, w.maxHrSetting)
                Row {
                    Text("${HrZones.NAMES[w.zone]} · ${r.first}–${r.last} bpm", fontSize = 12.sp, color = Palette.Muted, modifier = Modifier.weight(1f))
                    Text("${formatDuration(w.zoneMs[w.zone])} in zone", fontSize = 12.sp, color = Palette.Muted)
                }
            }
        }

        BarChart(
            w.recent.map { v ->
                v?.let { Bar((it - 90) / 100f, Palette.Zones[HrZones.zoneOf(it, w.maxHrSetting)]) } ?: Bar(0f, Color.Transparent)
            },
            height = 48.dp, gapPx = 2f,
        )
        Text("Last 10 minutes", fontSize = 11.sp, color = Palette.Faint)
    }
}

@Composable
private fun ReconnectBanner(attempt: Int, kind: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.WarnBg)
            .border(1.dp, Palette.WarnLine, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(RingIcons.Refresh, null, tint = Palette.Warn, modifier = Modifier.size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Reconnecting to your ring…", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.WarnText)
            Text(
                "Your $kind keeps recording. Heart rate comes back on its own when the ring reconnects.",
                fontSize = 13.sp, color = Palette.WarnSub, lineHeight = 19.sp,
            )
            Text("Attempt ${attempt + 1} · trying every 5 s", fontSize = 12.sp, color = Color(0xFFB8A57E))
        }
    }
}

@Composable
private fun StatusPill(text: String, dot: Color, warn: Boolean) {
    Row(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (warn) Palette.WarnBg else Palette.Surface)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Dot(dot, 7.dp)
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (warn) Color(0xFFF5C76A) else Palette.Text2)
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg)
    }
}

/** End button that needs a 1-second press, so a stray tap can't stop the workout. */
@Composable
private fun HoldToEnd(onEnd: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Palette.EndBg)
            .border(2.dp, Palette.EndLine, CircleShape)
            .semantics {
                contentDescription = "End workout. Press and hold."
                onClick(label = "End workout") { onEnd(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val anim = scope.launch {
                        progress.animateTo(1f, tween(1_000, easing = LinearEasing))
                        onEnd()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) {
                        anim.cancel()
                        scope.launch { progress.animateTo(0f, tween(200)) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (progress.value > 0f) {
                val s = 4.dp.toPx()
                drawArc(
                    Palette.EndText, -90f, 360f * progress.value, false,
                    Offset(s / 2, s / 2), Size(size.width - s, size.height - s),
                    style = Stroke(s, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(RingIcons.Stop, null, tint = Palette.EndText, modifier = Modifier.size(20.dp))
            Text("End", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.EndText)
        }
    }
}
