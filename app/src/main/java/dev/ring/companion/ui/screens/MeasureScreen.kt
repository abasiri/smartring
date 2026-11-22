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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ring.companion.data.Measurement
import dev.ring.companion.graph
import dev.ring.companion.protocol.Ycbt
import dev.ring.companion.ring.RingSession
import dev.ring.companion.ui.Palette
import dev.ring.companion.ui.PrimaryButton
import dev.ring.companion.ui.RingIcons
import dev.ring.companion.ui.SecondaryButton
import dev.ring.companion.ui.SectionLabel
import dev.ring.companion.ui.numberStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Phase { Measuring, Done, Failed }

@Composable
fun MeasureScreen(kind: String, onClose: () -> Unit) {
    val g = LocalContext.current.graph
    val isHr = kind == Measurement.HEART_RATE
    val expectedMs = if (isHr) 30_000L else 50_000L
    val timeoutMs = if (isHr) 50_000L else 75_000L
    val color = if (isHr) Palette.Heart else Palette.Accent
    val title = if (isHr) "heart rate" else "blood oxygen"
    val unit = if (isHr) "bpm" else "%"

    var phase by remember { mutableStateOf(Phase.Measuring) }
    var result by remember { mutableStateOf<Int?>(null) }
    var run by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val latest by g.session.latestHr.collectAsState()
    val status by g.session.status.collectAsState()
    var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(run) {
        phase = Phase.Measuring
        result = null
        startedAt = System.currentTimeMillis()
        launch {
            while (phase == Phase.Measuring) {
                elapsed = System.currentTimeMillis() - startedAt
                delay(250)
            }
        }
        val type = if (isHr) Ycbt.MeasureType.HEART_RATE else Ycbt.MeasureType.SPO2
        val v = g.session.measure(type, timeoutMs)
        if (v != null) {
            g.measurements.add(Measurement(kind, v, System.currentTimeMillis()))
            result = v
            phase = Phase.Done
        } else {
            phase = Phase.Failed
        }
    }

    val liveBpm = latest?.takeIf { isHr && it.timeMs >= startedAt }?.bpm
    val progress = when (phase) {
        Phase.Done -> 1f
        else -> (elapsed.toFloat() / expectedMs).coerceIn(0f, 0.95f)
    }
    val left = ((expectedMs - elapsed) / 1000).coerceAtLeast(0)

    Box(Modifier.fillMaxSize().background(Palette.Ground)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Row(
                Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClose),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(RingIcons.Back, null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
                Text("Today", color = Palette.Accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when (phase) {
                        Phase.Measuring -> "Measuring $title"
                        Phase.Done -> "Your $title"
                        Phase.Failed -> "No reading"
                    },
                    fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
                )
                Text(
                    when (phase) {
                        Phase.Measuring -> "Keep your hand still and relaxed. The light inside the ring is the sensor."
                        Phase.Done -> "Saved to today."
                        Phase.Failed -> if (status != RingSession.Status.Connected)
                            "The ring isn't connected. Reconnect it from the Ring tab and try again."
                        else "Push the ring snugly onto your finger, keep still, and try again."
                    },
                    fontSize = 15.sp, color = Palette.Muted, lineHeight = 22.sp,
                )
            }

            Box(Modifier.align(Alignment.CenterHorizontally).size(240.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 12.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(Palette.Surface2, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    drawArc(
                        if (phase == Phase.Failed) Palette.Stale else color, -90f, 360f * progress, false,
                        Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    SectionLabel(
                        when (phase) {
                            Phase.Measuring -> "Reading"
                            Phase.Done -> "Result"
                            Phase.Failed -> "Try again"
                        },
                    )
                    val shown = result ?: liveBpm
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            shown?.toString() ?: "–",
                            style = numberStyle(72, color = if (phase == Phase.Done) Palette.Text else Palette.Text2),
                        )
                        Text(unit, fontSize = 15.sp, color = Palette.Muted, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    if (phase == Phase.Measuring) {
                        Text(
                            if (left > 0) "about $left s left" else "almost there",
                            fontSize = 14.sp, color = Palette.Muted,
                        )
                    }
                }
            }

            if (phase == Phase.Measuring) {
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface)) {
                    StepRow("Sensor on", done = elapsed > 1_500, first = true)
                    StepRow("Pulse found", done = liveBpm != null || elapsed > expectedMs / 2, note = if (liveBpm != null) "Good signal" else null)
                    StepRow("Settling on a reading", done = false, active = true)
                }
            }

            Spacer(Modifier.weight(1f))
            when (phase) {
                Phase.Measuring -> SecondaryButton("Stop measuring", onClose, Modifier.fillMaxWidth())
                Phase.Done -> PrimaryButton("Done", onClose, Modifier.fillMaxWidth())
                Phase.Failed -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Try again", { run++ }, Modifier.weight(2f), enabled = status == RingSession.Status.Connected)
                    SecondaryButton("Close", onClose, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StepRow(text: String, done: Boolean, first: Boolean = false, active: Boolean = false, note: String? = null) {
    Column {
        if (!first) dev.ring.companion.ui.Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                when {
                    done -> Icon(RingIcons.Check, null, tint = Palette.Good, modifier = Modifier.size(20.dp))
                    active -> Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.Heart))
                    else -> Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFF3A4658)))
                }
            }
            Text(text, fontSize = 15.sp, color = if (done || active) Palette.Text else Palette.Muted, modifier = Modifier.weight(1f))
            if (note != null) Text(note, fontSize = 13.sp, color = Palette.Good)
        }
    }
}
