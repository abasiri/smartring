package dev.ring.companion.ui

import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A 3D smart ring drawn in software: a band (torus with a flat, wide cross-section), lit and
 * depth-sorted, rendered as one triangle batch per frame.
 *
 * - Drag to rotate; it keeps spinning a little after you let go, then settles into a slow idle spin.
 * - [bpm]: the ring pulses on each heartbeat at this rate.
 * - [sensorOn]: the inner sensor window glows green, like the real optical sensor light.
 * - [kick]: changes to this value (e.g. the step count) give the ring a small spin.
 */
@Composable
fun Ring3D(bpm: Int?, sensorOn: Boolean, kick: Int, modifier: Modifier = Modifier) {
    val bpmNow by rememberUpdatedState(bpm)
    val sensorNow by rememberUpdatedState(sensorOn)

    // Rotation state (radians) and angular velocity (radians/second).
    var yaw by remember { mutableFloatStateOf(0.6f) }
    var pitch by remember { mutableFloatStateOf(-0.55f) }
    var yawVel by remember { mutableFloatStateOf(0.35f) }
    var pitchVel by remember { mutableFloatStateOf(0f) }
    var time by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableFloatStateOf(0f) }

    // Spin kick when steps change.
    LaunchedEffect(kick) { if (kick != 0) yawVel += 1.2f }

    // Frame loop: integrate rotation with damping toward a slow idle spin.
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
                last = now
                time += dt
                if (dragging == 0f) {
                    yaw += yawVel * dt
                    pitch += pitchVel * dt
                    val idle = 0.35f
                    yawVel += (idle - yawVel) * (1f - exp(-dt * 1.2f))
                    pitchVel *= exp(-dt * 2.5f)
                    // ease pitch back toward a nice viewing angle
                    pitch += (-0.55f - pitch) * (1f - exp(-dt * 0.6f))
                }
            }
        }
    }

    val mesh = remember { RingMesh() }
    val paint = remember { Paint().apply { isAntiAlias = true } }

    Canvas(
        modifier
            .semantics { contentDescription = "3D view of the ring" + (bpmNow?.let { ", pulsing at $it beats per minute" } ?: "") }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { dragging = 1f; yawVel = 0f; pitchVel = 0f },
                    onDragEnd = { dragging = 0f },
                    onDragCancel = { dragging = 0f },
                ) { change, d ->
                    change.consume()
                    val k = 0.012f
                    yaw += d.x * k
                    pitch = (pitch + d.y * k).coerceIn(-1.4f, 1.4f)
                    yawVel = d.x * k * 40f
                    pitchVel = d.y * k * 40f
                }
            },
    ) {
        // Heartbeat envelope: a sharp rise and decay each beat.
        val beatPeriod = bpmNow?.takeIf { it in 30..220 }?.let { 60f / it }
        val beat = if (beatPeriod != null) {
            val ph = (time % beatPeriod) / beatPeriod
            exp(-ph * 9f) // 1 at the beat, fading out
        } else 0f
        val scale = 1f + 0.035f * beat
        val glow = if (sensorNow) 0.55f + 0.45f * (if (beatPeriod != null) beat else (0.5f + 0.5f * sin(time * 3f))) else 0f

        mesh.build(yaw, pitch, scale, glow, size.width, size.height)
        drawIntoCanvas { c ->
            val nc: AndroidCanvas = c.nativeCanvas
            nc.drawVertices(
                AndroidCanvas.VertexMode.TRIANGLES, mesh.vertCount * 2, mesh.verts, 0,
                null, 0, mesh.colors, 0, null, 0, 0, paint,
            )
        }
    }
}

/** Builds the lit, depth-sorted triangle list for the ring band. Buffers are reused each frame. */
private class RingMesh {
    private val nu = 128         // segments around the ring (fine enough for the sensor detail)
    private val nv = 18          // segments around the band cross-section
    private val R = 1.0f         // ring radius
    private val thick = 0.11f    // radial half-thickness
    private val width = 0.36f    // axial half-width (band width)

    // Inner-side layout, in (arc length along the ring, axial position) units, sensor at arc 0.
    private val sensorB = 0.02f      // sensor circle center (axial)
    private val sensorIn = 0.075f    // black center radius
    private val sensorOut = 0.125f   // white ring outer radius
    private val ledA = -0.26f        // green LED cluster center (arc)
    private val ledB = -0.09f        // green LED cluster center (axial)
    private val ledR = 0.085f

    private val gx = FloatArray((nu + 1) * (nv + 1))
    private val gy = FloatArray(gx.size)
    private val gz = FloatArray(gx.size)
    private val inner = BooleanArray(gx.size)
    private val uAng = FloatArray(gx.size)   // signed angle from the sensor, -PI..PI
    private val axial = FloatArray(gx.size)  // -1..1 across the band width

    private val faces = nu * nv * 2
    private val faceDepth = FloatArray(faces)
    private val faceOrder = IntArray(faces) { it } // kept between frames so re-sorting is cheap
    private val faceIdx = IntArray(faces * 3)
    private val faceColor = IntArray(faces)

    val vertCount = faces * 3
    val verts = FloatArray(vertCount * 2)
    val colors = IntArray(vertCount)

    private fun idx(i: Int, j: Int) = i * (nv + 1) + j

    fun build(yaw: Float, pitch: Float, scale: Float, glow: Float, w: Float, h: Float) {
        val cy = cos(yaw); val sy = sin(yaw)
        val cp = cos(pitch); val sp = sin(pitch)

        // Vertices: band cross-section is a rounded rectangle-ish ellipse (superellipse looks flatter).
        for (i in 0..nu) {
            val u = (i.toFloat() / nu) * 2f * PI.toFloat()
            val cu = cos(u); val su = sin(u)
            for (j in 0..nv) {
                val v = (j.toFloat() / nv) * 2f * PI.toFloat()
                val cv = cos(v); val sv = sin(v)
                // superellipse (n=4) cross-section: flatter faces, rounded edges, like a real band
                val ex = sign(cv) * abs(cv).pow(0.5f)
                val ez = sign(sv) * abs(sv).pow(0.5f)
                var r = R + thick * ex
                // raised sensor bump on the inner side
                val su0 = if (u > PI.toFloat()) u - 2f * PI.toFloat() else u
                if (ex < 0f) {
                    val da = su0 * R; val db = width * ez - sensorB
                    val dd = sqrt(da * da + db * db)
                    if (dd < sensorOut) r -= 0.03f * (1f - dd / sensorOut)
                }
                var x = r * cu
                var y = r * su
                var z = width * ez
                // ring lies flat-ish: the band axis is z. Rotate: yaw around Y, pitch around X.
                val x1 = x * cy + z * sy
                val z1 = -x * sy + z * cy
                val y2 = y * cp - z1 * sp
                val z2 = y * sp + z1 * cp
                val k = idx(i, j)
                gx[k] = x1 * scale; gy[k] = y2 * scale; gz[k] = z2 * scale
                inner[k] = ex < 0f
                uAng[k] = su0
                axial[k] = ez
            }
        }

        // Faces with lighting.
        val lx = -0.35f; val ly = -0.55f; val lz = 0.76f // light direction (toward viewer, upper-left)
        var f = 0
        for (i in 0 until nu) for (j in 0 until nv) {
            val a = idx(i, j); val b = idx(i + 1, j); val c = idx(i + 1, j + 1); val d = idx(i, j + 1)
            for (tri in 0..1) {
                val p0 = a
                val p1 = if (tri == 0) b else c
                val p2 = if (tri == 0) c else d
                // normal
                val ux = gx[p1] - gx[p0]; val uy = gy[p1] - gy[p0]; val uz = gz[p1] - gz[p0]
                val vx = gx[p2] - gx[p0]; val vy = gy[p2] - gy[p0]; val vz = gz[p2] - gz[p0]
                var nx = uy * vz - uz * vy
                var ny = uz * vx - ux * vz
                var nz = ux * vy - uy * vx
                val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                nx /= nl; ny /= nl; nz /= nl
                if (nz < 0f) { nx = -nx; ny = -ny; nz = -nz } // two-sided lighting
                val diff = max(0f, nx * lx + ny * ly + nz * lz)
                // specular (Blinn): half vector between light and view (0,0,1)
                var hx = lx; var hy = ly; var hz = lz + 1f
                val hl = sqrt(hx * hx + hy * hy + hz * hz); hx /= hl; hy /= hl; hz /= hl
                val spec = max(0f, nx * hx + ny * hy + nz * hz).pow(28f)
                val shade = 0.18f + 0.72f * diff

                // Rim (fresnel-ish) highlight: faces seen edge-on catch more light, reads as gloss.
                val rim = (1f - nz).pow(3f)
                val isInner = inner[p0] && inner[p1] && inner[p2]
                val uc = (uAng[p0] + uAng[p1] + uAng[p2]) / 3f
                val ezc = (axial[p0] + axial[p1] + axial[p2]) / 3f
                var rr: Float; var gg: Float; var bb: Float

                if (!isInner) {
                    // Outer shell: glossy black. A broad sheen + a sharp hotspot + a soft "studio light"
                    // reflection on faces that point up, + a cool rim. That's what reads as polish.
                    val nh = max(0f, nx * hx + ny * hy + nz * hz)
                    val sheen = nh.pow(9f) * 115f
                    val hot = nh.pow(70f) * 255f
                    val env = ((ny - 0.15f) / 0.6f).coerceIn(0f, 1f).let { it * it } * 85f
                    rr = 8f + 22f * shade + sheen + hot + env + 55f * rim
                    gg = 8f + 23f * shade + sheen + hot + env + 57f * rim
                    bb = 10f + 27f * shade + sheen + hot + env * 1.08f + 66f * rim
                } else {
                    val a = uc * R
                    val b = ezc * width
                    val ds = sqrt(a * a + (b - sensorB) * (b - sensorB))
                    val dl = sqrt((a - ledA) * (a - ledA) + (b - ledB) * (b - ledB))
                    when {
                        ds < sensorIn -> { // sensor center: black lens
                            rr = 8f + 20f * shade + 200f * spec; gg = rr; bb = rr + 4f
                        }
                        ds < sensorOut -> { // white ring around it
                            rr = 150f + 90f * shade + 60f * spec; gg = rr; bb = rr + 4f
                        }
                        dl < ledR -> { // green LEDs
                            val on = glow
                            rr = 15f + 20f * shade + 40f * on
                            gg = 70f + 40f * shade + 185f * on
                            bb = 35f + 20f * shade + 90f * on
                        }
                        abs(uc) < 1.15f -> { // clear resin over the circuit board
                            // coarse blocks: a few chips, the odd gold pad, mostly dark board
                            val cell = hash(((uc + 4f) * 9f).toInt(), ((ezc + 2f) * 2.5f).toInt())
                            when (cell % 9) {
                                0, 1 -> { rr = 46f; gg = 48f; bb = 54f }  // chip
                                2 -> { rr = 92f; gg = 74f; bb = 40f }     // gold pad
                                else -> { rr = 16f; gg = 27f; bb = 22f }  // board
                            }
                            val k = 0.45f + 0.55f * shade
                            rr = rr * k + 180f * spec; gg = gg * k + 180f * spec; bb = bb * k + 185f * spec
                            // soft green halo from the LEDs while the sensor is on
                            if (glow > 0f && dl < 0.30f) {
                                val h = glow * (1f - dl / 0.30f)
                                rr += 20f * h; gg += 120f * h; bb += 50f * h
                            }
                        }
                        else -> { // rest of the inner shell: dark
                            rr = 14f + 30f * shade + 150f * spec; gg = rr; bb = rr + 3f
                        }
                    }
                }
                faceColor[f] = argb(255, rr, gg, bb)
                faceIdx[f * 3] = p0; faceIdx[f * 3 + 1] = p1; faceIdx[f * 3 + 2] = p2
                faceDepth[f] = (gz[p0] + gz[p1] + gz[p2]) / 3f
                f++
            }
        }

        // Painter's algorithm: far (low z) first.
        sortByDepth()

        // Project to screen.
        val cam = 4.2f
        val fscale = minOf(w, h) * 0.95f
        val cx = w / 2f; val cyS = h / 2f
        var vi = 0
        for (o in 0 until faces) {
            val fi = faceOrder[o]
            val col = faceColor[fi]
            for (t in 0..2) {
                val p = faceIdx[fi * 3 + t]
                val persp = fscale / (cam - gz[p])
                verts[vi * 2] = cx + gx[p] * persp
                verts[vi * 2 + 1] = cyS - gy[p] * persp
                colors[vi] = col
                vi++
            }
        }
    }

    // Insertion sort is fast here: the order barely changes between frames.
    private fun sortByDepth() {
        for (i in 1 until faces) {
            val key = faceOrder[i]
            val kd = faceDepth[key]
            var j = i - 1
            while (j >= 0 && faceDepth[faceOrder[j]] > kd) {
                faceOrder[j + 1] = faceOrder[j]
                j--
            }
            faceOrder[j + 1] = key
        }
    }

    private fun sign(x: Float) = if (x < 0f) -1f else 1f
    private fun hash(i: Int, j: Int) = ((i * 73856093) xor (j * 19349663)) and 0x7fffffff
    private fun argb(a: Int, r: Float, g: Float, b: Float): Int =
        (a shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)
}
