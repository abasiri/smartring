package dev.ring.companion.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One goal ring: a value toward a target. */
data class RingGoal(val label: String, val value: Int, val goal: Int, val unit: String, val color: Color) {
    val progress: Float get() = if (goal <= 0) 0f else value.toFloat() / goal
}

/**
 * Concentric goal rings, outermost first. A ring past 100 % keeps going around
 * and the overlap is drawn slightly darker so a second lap stays visible.
 */
@Composable
fun ActivityRings(goals: List<RingGoal>, size: Dp = 148.dp, stroke: Dp = 16.dp, gap: Dp = 4.dp) {
    val animated = goals.map { g ->
        val p by animateFloatAsState(g.progress.coerceIn(0f, 2f), tween(900), label = g.label)
        p
    }
    Canvas(
        Modifier
            .size(size)
            .semantics {
                contentDescription = goals.joinToString(", ") { "${it.label} ${it.value} of ${it.goal} ${it.unit}" }
            },
    ) {
        val sw = stroke.toPx()
        val step = sw + gap.toPx()
        goals.forEachIndexed { i, g ->
            val inset = sw / 2 + i * step
            val box = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val style = Stroke(sw, cap = StrokeCap.Round)
            drawArc(g.color.copy(alpha = 0.18f), 0f, 360f, false, topLeft, box, style = Stroke(sw))
            val p = animated[i]
            if (p > 0f) {
                drawArc(g.color, -90f, 360f * p.coerceAtMost(1f), false, topLeft, box, style = style)
                if (p > 1f) {
                    val lap = Color(
                        red = g.color.red * 0.8f, green = g.color.green * 0.8f, blue = g.color.blue * 0.8f,
                    )
                    drawArc(lap, -90f, 360f * (p - 1f), false, topLeft, box, style = style)
                }
            }
        }
    }
}

/** Rings on the left, one line per goal on the right. */
@Composable
fun ActivityRingsCard(goals: List<RingGoal>, modifier: Modifier = Modifier) {
    Panel(modifier, spacing = 0.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(contentAlignment = Alignment.Center) { ActivityRings(goals) }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                goals.forEach { g ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        SectionLabel(g.label, color = g.color)
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("%,d".format(g.value), style = numberStyle(26, FontWeight.SemiBold))
                            Text("/ %,d %s".format(g.goal, g.unit).trimEnd(), fontSize = 13.sp, color = Palette.Muted)
                        }
                    }
                }
            }
        }
    }
}
