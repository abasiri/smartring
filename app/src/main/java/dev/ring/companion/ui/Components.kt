package dev.ring.companion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ring.companion.ring.RingSession

@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Surface)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Palette.Muted) {
    Text(text.uppercase(), modifier = modifier, style = LabelStyle.copy(color = color))
}

@Composable
fun ScreenTitle(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Text)
        if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Palette.Muted)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) Palette.Accent else Palette.Surface2)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (enabled) Palette.OnAccent else Palette.Muted
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Text(text, color = fg, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Palette.Text,
    background: Color = Palette.Surface2,
) {
    Box(
        modifier
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color))
}

fun statusColor(s: RingSession.Status): Color = when (s) {
    RingSession.Status.Connected -> Palette.Good
    RingSession.Status.Connecting, RingSession.Status.Reconnecting -> Palette.Warn
    RingSession.Status.Disconnected -> Palette.Stale
}

/** Small pill showing the ring's connection state. */
@Composable
fun RingChip(
    status: RingSession.Status,
    name: String?,
    battery: Int?,
    onClick: () -> Unit,
    paused: Boolean = false,
) {
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.Surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Dot(statusColor(status))
        val label = when (status) {
            RingSession.Status.Connected -> name ?: "Ring"
            RingSession.Status.Connecting -> "Connecting…"
            RingSession.Status.Reconnecting -> "Reconnecting…"
            RingSession.Status.Disconnected -> if (paused) "Disconnected" else "Not connected"
        }
        Text(label, color = Palette.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (battery != null) {
            // last known level when not connected, so it doesn't vanish in on-demand mode
            Text("$battery%", color = if (status == RingSession.Status.Connected) Palette.Muted else Palette.Faint, fontSize = 13.sp)
        }
    }
}

/** Number with its unit, e.g. "64 bpm". */
@Composable
fun BigValue(value: String, unit: String?, size: Int = 44, color: Color = Palette.Text) {
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = numberStyle(size, color = color))
        if (unit != null) Text(unit, fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/** Small number + caption used in workout stat grids. */
@Composable
fun MiniStat(value: String, caption: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = numberStyle(30, FontWeight.SemiBold))
        Text(caption, fontSize = 12.sp, color = Palette.Muted)
    }
}

@Composable
fun RowScope.Cell(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.weight(1f), content = content)
}

/**
 * Bar chart. Each entry is a 0..1 height and a color; `null` draws an outlined
 * box marking a gap (ring disconnected).
 */
data class Bar(val fraction: Float, val color: Color)

@Composable
fun BarChart(bars: List<Bar?>, height: Dp, modifier: Modifier = Modifier, gapPx: Float = 3f, baseline: Boolean = false) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (bars.isEmpty()) return@Canvas
        val w = (size.width - gapPx * (bars.size - 1)) / bars.size
        bars.forEachIndexed { i, b ->
            val x = i * (w + gapPx)
            if (b == null) {
                drawRoundRect(
                    color = Palette.Line,
                    topLeft = Offset(x + 0.5f, 0.5f),
                    size = Size(w - 1f, size.height - 1f),
                    cornerRadius = CornerRadius(2f),
                    style = Stroke(width = 1.5f),
                )
            } else {
                val h = (b.fraction.coerceIn(0.03f, 1f)) * size.height
                drawRoundRect(
                    color = b.color,
                    topLeft = Offset(x, size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(3f),
                )
            }
        }
        if (baseline) drawLine(Palette.Line, Offset(0f, size.height), Offset(size.width, size.height), 2f)
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, divider: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, color = Palette.Text)
            Text(subtitle, fontSize = 12.sp, color = Palette.Muted)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Text,
                checkedTrackColor = Palette.Good,
                uncheckedThumbColor = Palette.Text,
                uncheckedTrackColor = Palette.Line,
                uncheckedBorderColor = Palette.Line,
            ),
        )
    }
}

@Composable
fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Surface2))
}

/** Circular icon button used for workout controls. */
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String?,
    contentDescription: String,
    size: Dp,
    background: Color,
    foreground: Color,
    outline: Color? = null,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .then(if (outline != null) Modifier.border(2.dp, outline, CircleShape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = contentDescription, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription, tint = foreground, modifier = Modifier.size(if (label == null) 34.dp else 22.dp))
        if (label != null) Text(label, color = foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}
