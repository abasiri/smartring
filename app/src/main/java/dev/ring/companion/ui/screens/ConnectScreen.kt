package dev.ring.companion.ui.screens

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ring.companion.ble.BleScanner
import dev.ring.companion.ble.ScanHit
import dev.ring.companion.graph
import dev.ring.companion.ring.RingSession
import dev.ring.companion.service.RingService
import dev.ring.companion.ui.Palette
import dev.ring.companion.ui.RingIcons
import dev.ring.companion.ui.SectionLabel
import kotlinx.coroutines.delay

@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    val context = LocalContext.current
    val g = context.graph
    val hits = remember { mutableStateMapOf<String, ScanHit>() }
    var showAll by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<ScanHit?>(null) }
    var failed by remember { mutableStateOf(false) }
    val status by g.session.status.collectAsState()
    val attempt by g.session.attempt.collectAsState()

    val scanning = chosen == null
    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect // scanning while connecting makes some phones drop the link
        BleScanner(context).scan().collect { hit ->
            val prev = hits[hit.address]
            hits[hit.address] = if (prev != null && hit.name == null) hit.copy(name = prev.name) else hit
        }
    }

    // After picking a ring: wait for it to connect, give up after a few tries.
    LaunchedEffect(chosen, status, attempt) {
        val pick = chosen ?: return@LaunchedEffect
        if (status == RingSession.Status.Connected) {
            g.prefs.saveRing(pick.address, g.session.device.value?.name ?: pick.name)
            if (g.prefs.keepInBackground.value) RingService.start(context)
            onConnected()
        } else if (attempt >= 3) {
            g.session.disconnect()
            failed = true
            chosen = null
        }
    }

    val rings = hits.values.filter { it.looksLikeRing }.sortedByDescending { it.rssi }
    val others = hits.values.filter { !it.looksLikeRing && it.name != null }.sortedByDescending { it.rssi }
    val shown = if (showAll) rings + others else rings

    Box(Modifier.fillMaxSize().background(Palette.Ground)) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(top = 40.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pair your ring", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
                    Text(
                        "Wear the ring and keep it close to your phone. Pairing takes a few seconds.",
                        fontSize = 15.sp, color = Palette.Muted, lineHeight = 22.sp,
                    )
                }
            }
            item { Radar(searching = chosen == null) }
            item {
                Text(
                    if (chosen != null) "Connecting to ${chosen?.name ?: "ring"}…" else "Searching nearby…",
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                    fontSize = 14.sp, color = Palette.Muted,
                )
            }
            if (failed) {
                item {
                    Text(
                        "Couldn't connect. Close the SmartHealth app on this phone, then tap Connect again.",
                        fontSize = 14.sp, color = Palette.EndText, lineHeight = 20.sp,
                    )
                }
            }
            item { SectionLabel(if (showAll) "Nearby devices" else "Rings found", Modifier.padding(top = 8.dp)) }
            items(shown, key = { it.address }) { hit ->
                DeviceRow(
                    hit = hit,
                    connecting = chosen?.address == hit.address,
                    onConnect = {
                        failed = false
                        chosen = hit
                        g.session.connect(hit.address)
                    },
                )
            }
            item {
                Text(
                    if (showAll) "Show rings only" else "Showing rings only · ${others.size} other devices hidden",
                    modifier = Modifier
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button) { showAll = !showAll }
                        .padding(vertical = 12.dp),
                    fontSize = 13.sp, color = if (showAll) Palette.Accent else Palette.Faint,
                )
            }
            item {
                Row(
                    Modifier
                        .padding(top = 8.dp, bottom = 32.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color1E2530)
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(RingIcons.Info, null, tint = Palette.Warn, modifier = Modifier.size(20.dp))
                    Text(
                        "Ring not showing up? Close the SmartHealth app first. The ring only talks to one app at a time.",
                        fontSize = 13.sp, color = Palette.Text2, lineHeight = 19.sp,
                    )
                }
            }
        }
    }
}

private val Color1E2530 = androidx.compose.ui.graphics.Color(0xFF1E2530)

@Composable
private fun Radar(searching: Boolean) {
    var pulse by remember { mutableStateOf(0) }
    LaunchedEffect(searching) {
        while (searching) { delay(900); pulse = (pulse + 1) % 3 }
    }
    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        listOf(200, 148, 96).forEachIndexed { i, d ->
            val lit = searching && pulse == 2 - i
            Box(
                Modifier
                    .size(d.dp)
                    .clip(CircleShape)
                    .background(if (i == 2) Palette.Surface else androidx.compose.ui.graphics.Color.Transparent)
                    .border(1.dp, if (lit) Palette.Accent.copy(alpha = 0.5f) else Palette.Line, CircleShape),
            )
        }
        Icon(RingIcons.Ring, null, tint = Palette.Accent, modifier = Modifier.size(44.dp))
    }
}

@Composable
private fun DeviceRow(hit: ScanHit, connecting: Boolean, onConnect: () -> Unit) {
    val bars = when {
        hit.rssi > -60 -> 4
        hit.rssi > -70 -> 3
        hit.rssi > -80 -> 2
        else -> 1
    }
    val distance = when (bars) { 4 -> "right next to you"; 3 -> "close by"; 2 -> "a few meters away"; else -> "far away" }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .then(if (bars >= 3) Modifier.border(1.dp, Color33425A, RoundedCornerShape(16.dp)) else Modifier)
            .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 1..4) {
                Box(
                    Modifier.width(4.dp).height((2 + i * 4).dp).clip(RoundedCornerShape(1.dp))
                        .background(if (i <= bars) (if (bars >= 3) Palette.Good else Palette.Muted) else Color33425A),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(hit.name ?: "Unnamed device", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
            Text((if (hit.looksLikeRing) "Smart ring · " else "") + distance, fontSize = 12.sp, color = Palette.Muted)
        }
        Box(
            Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (bars >= 3 && hit.looksLikeRing) Palette.Accent else Palette.Surface2)
                .clickable(enabled = !connecting, role = Role.Button, onClick = onConnect)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (connecting) "Connecting…" else "Connect",
                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = if (bars >= 3 && hit.looksLikeRing) Palette.OnAccent else Palette.Text,
            )
        }
    }
}

private val Color33425A = androidx.compose.ui.graphics.Color(0xFF33425A)
