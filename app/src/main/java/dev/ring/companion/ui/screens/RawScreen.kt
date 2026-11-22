package dev.ring.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ring.companion.graph
import dev.ring.companion.ring.RingSession
import dev.ring.companion.ui.Palette
import dev.ring.companion.ui.Panel
import dev.ring.companion.ui.Ring3D
import dev.ring.companion.ui.ScreenTitle
import dev.ring.companion.ui.SectionLabel
import dev.ring.companion.ui.numberStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val LIVE_LIMIT_MS = 5 * 60_000L
private const val MAX_ROWS = 40 // small rolling buffer
private const val VISIBLE_ROWS = 12

@Composable
fun RawScreen() {
    val g = LocalContext.current.graph
    val status by g.session.status.collectAsState()
    val live by g.session.live.collectAsState()
    val hr by g.session.latestHr.collectAsState()
    val activity by g.session.activity.collectAsState()
    val workout by g.workout.live.collectAsState()
    val scope = rememberCoroutineScope()

    val rows = remember { mutableStateListOf<StreamRow>() }
    var nextId by remember { mutableLongStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    var liveSince by remember { mutableLongStateOf(0L) }
    var tick by remember { mutableIntStateOf(0) }
    val arrivals = remember { ArrayDeque<Long>() }

    // Collect every frame; keep the newest MAX_ROWS.
    LaunchedEffect(Unit) {
        g.session.raw.collect { f ->
            arrivals.addLast(f.timeMs)
            while (arrivals.isNotEmpty() && f.timeMs - arrivals.first() > 5_000) arrivals.removeFirst()
            if (!paused) {
                rows.add(0, StreamRow(nextId++, f)) // newest on top
                if (rows.size > MAX_ROWS) rows.removeRange(MAX_ROWS, rows.size)
            }
        }
    }
    // 1 Hz clock for countdown / rates.
    LaunchedEffect(Unit) { while (true) { delay(1_000); tick++ } }

    // Auto-off after 5 minutes to protect the ring battery.
    LaunchedEffect(live) {
        if (live) {
            liveSince = System.currentTimeMillis()
            delay(LIVE_LIMIT_MS)
            g.session.setLive(false)
        }
    }
    // Live mode only runs while you're looking at this tab: switching tabs (screen disposed) or
    // leaving the app (screen stopped but kept) both turn it off. App scope, so it runs after we're gone.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) g.ioScope.launch { g.session.setLive(false) }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            g.ioScope.launch { g.session.setLive(false) }
        }
    }

    val connected = status == RingSession.Status.Connected
    val canToggle = connected && workout == null
    val now = remember(tick, hr, rows.size) { System.currentTimeMillis() } // refreshed every second
    val hrFresh = hr?.takeIf { now - it.timeMs < 15_000 }?.bpm
    val perSec = arrivals.count { now - it <= 5_000 } / 5.0
    val remaining = if (live) ((LIVE_LIMIT_MS - (now - liveSince)) / 1000).coerceAtLeast(0) else 0

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Raw", "Live data straight from the ring")

        // 3D ring
        Box(
            Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(18.dp)).background(Palette.Surface),
            contentAlignment = Alignment.Center,
        ) {
            Ring3D(bpm = if (live) hrFresh else null, sensorOn = live, kick = activity?.steps ?: 0, modifier = Modifier.fillMaxSize())
            Text(
                if (live) "Sensor on · drag to rotate" else "Drag to rotate",
                fontSize = 11.sp, color = Palette.Faint,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            )
        }

        // Live toggle
        Panel(spacing = 6.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Live mode", fontSize = 15.sp, color = Palette.Text)
                    Text(
                        when {
                            !connected -> "Connect the ring first."
                            workout != null -> "Off during workouts (the workout already streams heart rate)."
                            live -> "Sensor light on, streaming. Turns off in %d:%02d.".format(remaining / 60, remaining % 60)
                            else -> "Keeps the sensor light on so heart rate streams about once a second. Uses ring battery; turns off after 5 minutes."
                        },
                        fontSize = 12.sp, color = Palette.Muted, lineHeight = 17.sp,
                    )
                }
                Switch(
                    checked = live, enabled = canToggle,
                    onCheckedChange = { on -> scope.launch { g.session.setLive(on) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.Text, checkedTrackColor = Palette.Good,
                        uncheckedThumbColor = Palette.Text, uncheckedTrackColor = Palette.Line, uncheckedBorderColor = Palette.Line,
                    ),
                )
            }
        }

        // Live numbers
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Stat("Heart rate", hrFresh?.toString() ?: "–", "bpm", Modifier.weight(1f))
            Stat("Steps", "%,d".format(activity?.steps ?: 0), "today", Modifier.weight(1f))
            Stat("Frames", "%.1f".format(perSec), "per sec", Modifier.weight(1f))
        }

        // What this ring does and doesn't stream
        Panel(spacing = 4.dp) {
            SectionLabel("Sensors on this ring")
            Avail("Heart rate (optical)", true, "streams in live mode")
            Avail("Blood oxygen", true, "on request (Measure)")
            Avail("Steps / distance / calories", true, "status beacon every ~2 s")
            Avail("Accelerometer x/y/z", false, "firmware rejects the stream commands (0x0322, 0x0E04, 0x011B)")
        }

        // Stream
        Panel(spacing = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Data stream", Modifier.weight(1f))
                Text(
                    if (paused) "Resume" else "Pause",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent,
                    modifier = Modifier.clickable(role = Role.Button) { paused = !paused }.padding(horizontal = 4.dp, vertical = 6.dp),
                )
                Text(
                    "Clear",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent,
                    modifier = Modifier.clickable(role = Role.Button) { rows.clear() }.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                )
            }
            // Fixed-height window: new frames slide in at the top, old ones drop off the bottom.
            // The page doesn't grow; scroll inside the window to look back through the buffer.
            Box(Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Ground)) {
                if (rows.isEmpty()) {
                    Text("Waiting for data…", fontSize = 12.sp, color = Palette.Faint, modifier = Modifier.padding(12.dp))
                }
                // Ticker window: the newest frame expands in at the top and pushes the rest down;
                // rows past the bottom are clipped. No scroll state to fight with bursts of frames.
                Column(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true).padding(horizontal = 10.dp)) {
                    rows.take(VISIBLE_ROWS).forEach { r ->
                        key(r.id) {
                            val shown = remember { MutableTransitionState(false).apply { targetState = true } }
                            AnimatedVisibility(
                                visibleState = shown,
                                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                            ) { FrameRow(r.frame) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, unit: String, modifier: Modifier) {
    Panel(modifier, spacing = 2.dp) {
        SectionLabel(label)
        Text(value, style = numberStyle(26, FontWeight.SemiBold))
        Text(unit, fontSize = 11.sp, color = Palette.Muted)
    }
}

@Composable
private fun Avail(name: String, ok: Boolean, note: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", fontSize = 12.sp, color = if (ok) Palette.Good else Palette.Stale, modifier = Modifier.padding(end = 8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontSize = 13.sp, color = if (ok) Palette.Text else Palette.Muted)
            Text(note, fontSize = 11.sp, color = Palette.Faint)
        }
    }
}

private val TimeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

/** A frame plus a stable id so the list can animate rows sliding down. */
private data class StreamRow(val id: Long, val frame: RingSession.RawFrame)

@Composable
private fun FrameRow(f: RingSession.RawFrame) {
    val (label, color) = describe(f)
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Instant.ofEpochMilli(f.timeMs).atZone(ZoneId.systemDefault()).format(TimeFmt),
                fontSize = 10.sp, color = Palette.Faint, fontFamily = FontFamily.Monospace,
            )
            Text(
                (if (f.outgoing) " → " else " ← ") + f.channel.take(4),
                fontSize = 10.sp, color = if (f.outgoing) Palette.Warn else Palette.Accent, fontFamily = FontFamily.Monospace,
            )
            Text("  $label", fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
        }
        Text(
            f.bytes.joinToString(" ") { "%02x".format(it) }.let { if (it.length > 96) it.take(96) + "…" else it },
            fontSize = 10.sp, color = Palette.Muted, fontFamily = FontFamily.Monospace, lineHeight = 13.sp,
        )
    }
}

/** Human label for a frame, by channel and (for YCBT) the type word. */
private fun describe(f: RingSession.RawFrame): Pair<String, androidx.compose.ui.graphics.Color> {
    val b = f.bytes
    fun u8(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else 0
    fun u24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
    return when {
        f.channel.startsWith("fea1") -> "Beacon · %d steps · %d m · %d kcal".format(u24(1), u24(4), u24(7)) to Palette.Good
        f.channel.startsWith("2a37") -> "Std HR · %d bpm".format(u8(1)) to Palette.Heart
        f.channel.startsWith("ae0") -> "Jieli RCSP" to Palette.Muted
        // YCBT: outgoing shows as be940001; incoming notifications arrive on ...0001 / ...0003
        (f.channel.startsWith("be94") || f.channel == "0001" || f.channel == "0003") && b.size >= 2 -> {
            val type = (u8(0) shl 8) or u8(1)
            val p = 4 // payload offset
            // Short replies (7 bytes) carry just a status byte: 00 ok, fc not supported, fb rejected.
            val status = if (!f.outgoing && b.size == 7) when (u8(p)) {
                0x00 -> " · ok"; 0xFC -> " · not supported"; 0xFB -> " · rejected"; else -> " · status %02x".format(u8(p))
            } else ""
            when (type) {
                0x0226 -> "Sleep status$status (SmartHealth's request)" to Palette.Faint
                0x0601 -> "Heart rate · ${u8(p)} bpm" to Palette.Heart
                0x0602 -> "Blood O₂ · ${u8(p)}%" to Palette.Accent
                0x060D -> "Workout · HR ${u8(p)}" to Palette.Heart
                0x0600 -> "Activity · ${u8(p) or (u8(p + 1) shl 8)} steps" to Palette.Good
                0x032F -> (if (f.outgoing) "Sensor ${if (u8(p) == 1) "ON" else "OFF"}" else "Sensor ack") to Palette.Warn
                0x0309 -> (if (f.outgoing) "Live data ${if (u8(p) == 1) "ON" else "OFF"}" else "Live data ack") to Palette.Warn
                0x030C -> "Workout mode" to Palette.Warn
                0x0100 -> "Time sync" to Palette.Text2
                0x0200 -> "Device info" to Palette.Text2
                0x0203 -> "Device name" to Palette.Text2
                0x010C -> "Reading interval" to Palette.Text2
                0x0580 -> "History end" to Palette.Text2
                0x0513 -> "Sleep records" to Palette.Text2
                0x0515 -> "Heart-rate records" to Palette.Text2
                0x0518 -> "Background readings" to Palette.Text2
                else -> if (type shr 8 == 0x05) "History 0x%04X$status".format(type) to Palette.Text2
                        else "Type 0x%04X$status".format(type) to Palette.Text2
            }
        }
        else -> f.channel to Palette.Text2
    }
}
