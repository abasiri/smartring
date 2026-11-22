package dev.ring.companion.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.ring.companion.R

/** Colors from the design mock-ups. Dark only: built for glancing at mid-run. */
object Palette {
    val Ground = Color(0xFF0F141B)
    val Surface = Color(0xFF18202B)
    val Surface2 = Color(0xFF222C3A)
    val Selected = Color(0xFF2C3A4E)
    val Line = Color(0xFF2C3747)
    val NavBar = Color(0xFF131A24)
    val Text = Color(0xFFEEF2F6)
    val Text2 = Color(0xFFC3CDD9)
    val Muted = Color(0xFF9AA7B8)
    val Faint = Color(0xFF7D8A9C)
    val Stale = Color(0xFF5E6B7D)
    val Accent = Color(0xFF7CC4FF)
    val OnAccent = Color(0xFF0B1A2A)
    val Good = Color(0xFF4FD1A5)
    val Warn = Color(0xFFF5B342)
    val WarnBg = Color(0xFF2A2312)
    val WarnLine = Color(0xFF5C4718)
    val WarnText = Color(0xFFFBE3B0)
    val WarnSub = Color(0xFFD9C9A6)
    val Heart = Color(0xFFFF8A80)
    val EndBg = Color(0xFF2A1B1E)
    val EndLine = Color(0xFF5A2A2E)
    val EndText = Color(0xFFFF9A92)
    val Bar = Color(0xFF3A4A60)

    /** Zone 0 (resting) to zone 5 (maximum). */
    val Zones = listOf(Bar, Color(0xFF8FA3B8), Color(0xFF5FB7E8), Color(0xFF4FD1A5), Color(0xFFF5B342), Color(0xFFF2665E))
}

val Barlow = FontFamily(
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

/** Big condensed numbers for stats. */
fun numberStyle(size: Int, weight: FontWeight = FontWeight.Bold, color: Color = Palette.Text) = TextStyle(
    fontFamily = Barlow,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = size.sp,
    color = color,
    fontFeatureSettings = "tnum",
)

val LabelStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp, color = Palette.Muted)

@Composable
fun RingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.OnAccent,
            background = Palette.Ground,
            surface = Palette.Surface,
            onBackground = Palette.Text,
            onSurface = Palette.Text,
            surfaceVariant = Palette.Surface2,
            outline = Palette.Line,
        ),
        content = content,
    )
}

fun formatDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun formatPace(secPerKm: Int?): String = secPerKm?.let { "%d:%02d".format(it / 60, it % 60) } ?: "–:––"

fun formatKm(m: Double): String = "%.2f".format(m / 1000.0)
