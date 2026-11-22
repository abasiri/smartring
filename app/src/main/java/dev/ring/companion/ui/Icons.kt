package dev.ring.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Line icons on a 24×24 grid, matching the mock-ups. */
object RingIcons {
    val Heart = stroke("Heart", "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21l7.8-7.6 1-1a5.5 5.5 0 0 0 0-7.8z")
    val HeartFilled = fill("HeartFilled", "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1-1.1a5.5 5.5 0 0 0-7.8 7.8l1 1.1L12 21l7.8-7.6 1-1a5.5 5.5 0 0 0 0-7.8z")
    val Drop = stroke("Drop", "M12 2.7l5.7 5.7a8 8 0 1 1-11.3 0z")
    val Pulse = stroke("Pulse", "M22 12h-4l-3 9L9 3l-3 9H2")
    val Moon = stroke("Moon", "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z")
    val Ring = stroke("Ring", "M5 12a7 7 0 1 0 14 0a7 7 0 1 0-14 0", "M8.5 12a3.5 3.5 0 1 0 7 0a3.5 3.5 0 1 0-7 0")
    val Calendar = stroke("Calendar", "M5 4h14a2 2 0 0 1 2 2v13a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M3 9h18", "M8 2v4", "M16 2v4")
    val Clock = stroke("Clock", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M12 7v5l3 2")
    val Play = fill("Play", "M7 4l13 8-13 8z")
    val Pause = fill("Pause", "M6 4h4v16H6z", "M14 4h4v16h-4z")
    val Stop = fill("Stop", "M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z")
    val Flag = stroke("Flag", "M5 21V4h11l-2 4 2 4H5")
    val Refresh = stroke("Refresh", "M21 4v6h-6", "M20 15a8 8 0 1 1-2-8.5L21 10")
    val Check = stroke("Check", "M5 12l5 5 9-10")
    val Back = stroke("Back", "M15 6l-6 6 6 6")
    val Chevron = stroke("Chevron", "M9 6l6 6-6 6")
    val Vibrate = stroke("Vibrate", "M9 4h6a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M3 8v8", "M21 8v8")
    val Info = stroke("Info", "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0", "M12 8v4.5", "M12 16v0.5")
    val Minus = stroke("Minus", "M6 12h12")
    val Plus = stroke("Plus", "M6 12h12", "M12 6v12")

    private fun stroke(name: String, vararg paths: String) = build(name, paths, filled = false)
    private fun fill(name: String, vararg paths: String) = build(name, paths, filled = true)

    private fun build(name: String, paths: Array<out String>, filled: Boolean): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            val nodes = PathParser().parsePathString(d).toNodes()
            if (filled) {
                b.addPath(nodes, fill = SolidColor(Color.Black))
            } else {
                b.addPath(
                    nodes,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }
        return b.build()
    }
}
