package dev.ring.companion.ui.screens

import dev.ring.companion.firmware.FirmwareCheck.State as FwState

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ring.companion.graph
import dev.ring.companion.ring.RingSession
import dev.ring.companion.service.RingService
import dev.ring.companion.ui.Divider
import dev.ring.companion.ui.Dot
import dev.ring.companion.ui.Palette
import dev.ring.companion.ui.RingIcons
import dev.ring.companion.ui.ScreenTitle
import dev.ring.companion.ui.SectionLabel
import dev.ring.companion.ui.ToggleRow
import dev.ring.companion.ui.numberStyle
import dev.ring.companion.ui.statusColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun RingScreen(onForgotten: () -> Unit) {
    val context = LocalContext.current
    val g = context.graph
    val status by g.session.status.collectAsState()
    val device by g.session.device.collectAsState()
    val address by g.prefs.ringAddress.collectAsState()
    val auto by g.prefs.autoReconnect.state.collectAsState()
    val background by g.prefs.keepInBackground.state.collectAsState()
    val paused by g.prefs.connectionPaused.state.collectAsState()
    val app = context.applicationContext as dev.ring.companion.RingApp
    val workoutLive by g.workout.live.collectAsState()
    val fw by g.firmware.result.collectAsState()
    val fwChecking by g.firmware.checking.collectAsState()
    // Check for firmware once a day (or when the ring's version changes) while this screen is open.
    val firmware = device?.firmware ?: g.prefs.lastFirmware
    LaunchedEffect(firmware) {
        firmware?.let { g.firmware.checkIfStale(address, it) }
    }
    val screenOn by g.prefs.screenOnInWorkout.state.collectAsState()
    val moveOn by g.prefs.moveReminders.state.collectAsState()
    val interval by g.prefs.measureInterval.collectAsState()
    val batterySamples by g.battery.samples.collectAsState()
    val maxHr by g.prefs.maxHr.collectAsState()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var confirmForget by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        if (message != null) { delay(3_000); message = null }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Ground)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle("Ring")

        Column(
            Modifier.clip(RoundedCornerShape(18.dp)).background(Palette.Surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Palette.Surface2), contentAlignment = Alignment.Center) {
                    Icon(RingIcons.Ring, null, tint = Palette.Accent, modifier = Modifier.size(28.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(device?.name ?: g.prefs.ringName.value ?: "Your ring", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Dot(statusColor(status), 7.dp)
                        Text(
                            if (paused && status == RingSession.Status.Disconnected) "Disconnected by you" else when (status) {
                                RingSession.Status.Connected -> "Connected"
                                RingSession.Status.Connecting -> "Connecting…"
                                RingSession.Status.Reconnecting -> "Reconnecting…"
                                RingSession.Status.Disconnected -> "Not connected"
                            },
                            fontSize = 13.sp, color = statusColor(status),
                        )
                    }
                }
                // Live value when connected; otherwise the last logged reading, with its age.
                val lastLogged = batterySamples.lastOrNull()
                val liveBattery = device?.battery.takeIf { status == RingSession.Status.Connected }
                (liveBattery ?: lastLogged?.percent)?.let { b ->
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$b%", style = numberStyle(26, FontWeight.SemiBold, if (liveBattery != null) Palette.Text else Palette.Text2))
                        if (liveBattery == null && lastLogged != null) {
                            Text(timeAgo(lastLogged.timeMs).substringAfter("· ").let { "as of $it" }, fontSize = 11.sp, color = Palette.Faint)
                        }
                        Box(Modifier.width(44.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Line)) {
                            Box(
                                Modifier.fillMaxWidth(b / 100f).height(6.dp)
                                    .background(if (b <= 20) Palette.Warn else Palette.Good),
                            )
                        }
                    }
                }
            }
            InfoLine("Model", g.prefs.ringModel ?: g.firmware.model(address) ?: "–")
            InfoLine("Firmware", firmware ?: "–")
            FirmwareLine(fw, fwChecking, canCheck = firmware != null) {
                scope.launch { g.firmware.check(address, firmware) }
            }
            InfoLine("Address", address ?: "–")
            InfoLine("Last synced", device?.lastSyncMs?.let { timeAgo(it) } ?: "–")
            if (device != null && device?.authenticated == false && status == RingSession.Status.Connected) {
                Text(
                    "Signed-in features (Find ring) are unavailable. Measurements and workouts still work.",
                    fontSize = 12.sp, color = Palette.Faint,
                )
            }
        }

        dev.ring.companion.ui.BatteryCard(batterySamples, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(RingIcons.Vibrate, "Find ring", Modifier.weight(1f)) {
                scope.launch { message = if (g.session.findRing()) "The ring should vibrate or blink now." else "Couldn't reach the ring." }
            }
            ActionTile(RingIcons.Clock, "Sync time", Modifier.weight(1f)) {
                scope.launch { message = if (g.session.syncTime()) "Time synced." else "Couldn't reach the ring." }
            }
            when {
                paused -> ActionTile(RingIcons.Refresh, "Connect", Modifier.weight(1f)) {
                    app.resumeConnection()
                    message = "Connecting…"
                }
                status == RingSession.Status.Connected || status == RingSession.Status.Connecting ->
                    ActionTile(RingIcons.Stop, "Disconnect", Modifier.weight(1f)) {
                        if (workoutLive != null) {
                            message = "End the workout first; it needs the ring."
                        } else {
                            app.pauseConnection()
                            message = "Disconnected. Other apps can use the ring now. Tap Connect when you're done."
                        }
                    }
                else -> ActionTile(RingIcons.Refresh, "Reconnect", Modifier.weight(1f)) {
                    g.session.reconnectNow()
                    message = "Reconnecting…"
                }
            }
        }
        message?.let { Text(it, fontSize = 13.sp, color = Palette.Text2) }

        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface)) {
            SectionLabel("Options", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
            ToggleRow("Reconnect automatically", "Retries every 5 s when the ring drops", auto, { g.prefs.autoReconnect.set(it) }, false)
            Divider()
            ToggleRow("Screen on during workouts", "Uses more phone battery", screenOn, { g.prefs.screenOnInWorkout.set(it) }, true)
            Divider()
            ToggleRow(
                "Move reminders",
                "At 10 to the hour, ${g.prefs.moveStartHour}:00–${g.prefs.moveEndHour}:00, if you've barely moved. " +
                    if (background) "" else "Needs \"Always connected\".",
                moveOn, { g.prefs.moveReminders.set(it) }, true,
            )
        }

        Column(
            Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionLabel("Background readings")
            Text(
                "How often the ring measures heart rate, blood oxygen and HRV on its own. More often gives more detail, especially overnight, and uses more ring battery.",
                fontSize = 12.sp, color = Palette.Muted, lineHeight = 17.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10, 15, 20, 30, 60).forEach { m ->
                    val sel = m == interval
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (sel) Palette.Selected else Palette.Surface2)
                            .then(if (sel) Modifier.border(2.dp, Palette.Accent, RoundedCornerShape(12.dp)) else Modifier)
                            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton) {
                                g.prefs.setMeasureInterval(m)
                                scope.launch {
                                    message = if (g.session.applyMeasureInterval()) "The ring now measures every $m minutes."
                                    else "Saved. It will be sent to the ring when it connects."
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (m == 60) "1 h" else "$m min", fontSize = 13.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (sel) Palette.Text else Palette.Text2,
                        )
                    }
                }
            }
        }

        Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Palette.Surface)) {
            SectionLabel("Connection", Modifier.padding(start = 16.dp, top = 14.dp, bottom = 6.dp))
            ChoiceRow(
                "Always connected",
                "Live numbers and instant workouts. Uses more ring battery. Shows a notification.",
                selected = background,
            ) {
                g.prefs.keepInBackground.set(true)
                app.resumeRing()
            }
            Divider()
            ChoiceRow(
                "Only while the app is open",
                "Connects and syncs when you open the app, disconnects 2 minutes after you leave. Workouts stay connected.",
                selected = !background,
            ) {
                g.prefs.keepInBackground.set(false)
                if (!g.workout.isActive) RingService.stop(context)
            }
        }

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.Surface).heightIn(min = 60.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Max heart rate", fontSize = 15.sp, color = Palette.Text)
                Text("Sets your 5 heart-rate zones", fontSize = 12.sp, color = Palette.Muted)
            }
            StepButton(RingIcons.Minus, "Lower max heart rate") { g.prefs.setMaxHr(maxHr - 1) }
            Text("$maxHr", style = numberStyle(26, FontWeight.SemiBold), modifier = Modifier.padding(horizontal = 10.dp))
            StepButton(RingIcons.Plus, "Raise max heart rate") { g.prefs.setMaxHr(maxHr + 1) }
        }

        if (confirmForget) {
            Text("Forget this ring? You can pair it again anytime.", fontSize = 14.sp, color = Palette.Text2)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                dev.ring.companion.ui.SecondaryButton("Forget ring", {
                    g.workout.discard()
                    g.session.disconnect()
                    g.prefs.forgetRing()
                    RingService.stop(context)
                    onForgotten()
                }, Modifier.weight(1f), color = Palette.EndText, background = Palette.EndBg)
                dev.ring.companion.ui.SecondaryButton("Cancel", { confirmForget = false }, Modifier.weight(1f))
            }
        } else {
            Box(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) { confirmForget = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("Forget this ring", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.EndText)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row {
        Text(label, fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = Palette.Text)
    }
}

@Composable
private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .heightIn(min = 76.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = Palette.Accent, modifier = Modifier.size(22.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Palette.Surface2)
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = Palette.Text, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ChoiceRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onSelect)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, color = Palette.Text)
            Text(subtitle, fontSize = 12.sp, color = Palette.Muted, lineHeight = 17.sp)
        }
        androidx.compose.material3.RadioButton(
            selected = selected, onClick = onSelect,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = Palette.Accent, unselectedColor = Palette.Muted),
        )
    }
}

/** "Up to date · checked 22:50 · Check", or an update notice with how to install it. */
@Composable
private fun FirmwareLine(
    r: dev.ring.companion.firmware.FirmwareCheck.Result?,
    checking: Boolean,
    canCheck: Boolean,
    onCheck: () -> Unit,
) {
    val checked = r?.let { java.time.Instant.ofEpochMilli(it.checkedMs).atZone(java.time.ZoneId.systemDefault()) }
        ?.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm"))
    val (text, color) = when {
        checking -> "Checking…" to Palette.Muted
        r == null -> "Not checked yet" to Palette.Muted
        r.state == FwState.Available -> "Version ${r.latest} available" to Palette.Accent
        r.state == FwState.Required -> "Version ${r.latest} available (recommended)" to Palette.Warn
        r.state == FwState.UpToDate -> "Up to date" to Palette.Good
        r.state == FwState.NoInfo -> "Can't tell (model unknown)" to Palette.Muted
        else -> "Couldn't check (no internet?)" to Palette.Muted
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Updates", fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.weight(1f))
            Text(text, fontSize = 13.sp, color = color)
            if (canCheck && !checking) {
                Text(
                    "Check",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent,
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onCheck)
                        .padding(vertical = 4.dp),
                )
            }
        }
        if (r != null && (r.state == FwState.Available || r.state == FwState.Required)) {
            Text(
                "Install it with the SmartHealth app: tap Disconnect here first, then update there. " +
                    "Close SmartHealth before you tap Connect again.",
                fontSize = 12.sp, color = Palette.Text2, lineHeight = 17.sp,
            )
        }
        if (checked != null && !checking) Text("Checked $checked", fontSize = 11.sp, color = Palette.Faint)
    }
}
