package nz.skull.vitalibre

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import nz.skull.vitalibre.core.OrbGeometry
import nz.skull.vitalibre.core.ScanSession
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Where the orb sits inside a canvas: centre and outer radius (unit 1.0). The orb body is smaller than its frame; the margin holds the coving. */
class OrbFrame(size: Size) {
    val c = Offset(size.width / 2, size.height / 2)
    val R: Float = min(size.width, size.height) / 2 / (1 + OrbGeometry.covingWidth.toFloat())

    fun r(unit: Double): Float = R * unit.toFloat()

    /** Point at unit-space radius and angle theta (0 = 12 o'clock, clockwise). */
    fun point(unit: Double, theta: Double) = Offset(c.x + r(unit) * sin(theta).toFloat(), c.y - r(unit) * cos(theta).toFloat())

    val topLeft get() = Offset(c.x - R, c.y - R)
    val bottomRight get() = Offset(c.x + R, c.y + R)
}

private fun circleRect(c: Offset, r: Float) = Rect(c.x - r, c.y - r, c.x + r, c.y + r)

fun annulus(f: OrbFrame, inner: Double, outer: Double): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    addOval(circleRect(f.c, f.r(outer)))
    addOval(circleRect(f.c, f.r(inner)))
}

/** A polyline arc from t0 to t1 (clockwise from 12 o'clock) at a unit radius. */
fun arcPath(f: OrbFrame, unit: Double, t0: Double, t1: Double): Path {
    val p = Path()
    val steps = max(2, (abs(t1 - t0) / (PI / 90)).toInt())
    for (i in 0..steps) {
        val pt = f.point(unit, t0 + (t1 - t0) * i / steps)
        if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
    }
    return p
}

/** Static parts of the orb: plate, concave coving, colour ring. */
fun DrawScope.drawOrbBody(f: OrbFrame, p: Palette) {
    // Plate.
    drawCircle(
        brush = Brush.radialGradient(
            listOf(p.plate.mixed(Color.White, if (p.isLight) 0f else 0.05f), p.plate.mixed(Color.Black, if (p.isLight) 0.08f else 0.4f)),
            center = Offset(f.c.x - f.R * 0.3f, f.c.y - f.R * 0.35f), radius = f.R * 1.4f,
        ),
        radius = f.R, center = f.c,
    )
    drawCoving(f, p)

    // Colour ring: angular gradient through the ring colours, inward fall-off, a 160 degree shading pass, inset shadows.
    clipPath(annulus(f, OrbGeometry.ringInner, OrbGeometry.ringOuter)) {
        withTransform({ rotate(-90f, f.c) }) {
            drawCircle(brush = Brush.sweepGradient(p.ringStops + p.ringStops[0], f.c), radius = f.R, center = f.c)
        }
        drawCircle(
            brush = Brush.radialGradient(
                colorStops = arrayOf(
                    0f to Color.Transparent,
                    OrbGeometry.ringInner.toFloat() to Color.Black.copy(alpha = 0.5f),
                    0.74f to Color.Transparent,
                    OrbGeometry.ringOuter.toFloat() to Color.Black.copy(alpha = 0.28f),
                ),
                center = f.c, radius = f.R,
            ),
            radius = f.R, center = f.c,
        )
        val a = 160.0 * PI / 180
        val dx = (sin(a) * f.R).toFloat()
        val dy = (-cos(a) * f.R).toFloat()
        drawCircle(
            brush = Brush.linearGradient(
                listOf(Color.White.copy(alpha = if (p.isLight) 0.10f else 0.16f), Color.Transparent, Color.Black.copy(alpha = 0.4f)),
                start = Offset(f.c.x - dx, f.c.y - dy), end = Offset(f.c.x + dx, f.c.y + dy),
            ),
            radius = f.R, center = f.c,
        )
        for (unit in listOf(OrbGeometry.ringInner, OrbGeometry.ringOuter)) softStroke(f.c, f.r(unit), f.R * 0.045f, Color.Black, 0.55f)
    }
    drawGridBase(f, p)
}

/** A stroke that fades outward, standing in for a blurred shadow line. */
private fun DrawScope.softStroke(c: Offset, radius: Float, width: Float, color: Color, alpha: Float) {
    val steps = 8
    for (k in 0 until steps) {
        val t = k / (steps - 1f)
        drawCircle(color.copy(alpha = alpha * (1 - t) / 3f), radius = radius, center = c, style = Stroke(width = width * (0.3f + 0.7f * t)))
    }
}

/**
 * A concave fillet from the ring's edge out into the background, with no hard lip. Concentric lines,
 * darkest at the orb and fading to nothing at the background, each shaded top-left dark to bottom-right light.
 * Alpha only, so it takes whatever the background is.
 */
fun DrawScope.drawCoving(f: OrbFrame, p: Palette) {
    val inner = OrbGeometry.ringOuter
    val outer = OrbGeometry.outerRadius + OrbGeometry.covingWidth
    val steps = 40
    val width = (outer - inner) / steps
    for (i in 0 until steps) {
        val t = i.toFloat() / (steps - 1)
        val depth = (1 - t).pow(2.2f)
        val radius = inner + (i + 0.5) * width
        val shade = Brush.linearGradient(
            listOf(
                Color.Black.copy(alpha = ((if (p.isLight) 0.26f else 0.75f) * depth)),
                Color.Black.copy(alpha = ((if (p.isLight) 0.05f else 0.18f) * depth)),
                Color.White.copy(alpha = ((if (p.isLight) 0.85f else 0.16f) * depth).coerceAtMost(1f)),
            ),
            start = f.topLeft, end = f.bottomRight,
        )
        drawCircle(shade, radius = f.r(radius), center = f.c, style = Stroke(width = f.R * width.toFloat() * 1.4f))
    }
}

/** The circular grid inside the ring at its resting brightness; part of the static body. */
fun DrawScope.drawGridBase(f: OrbFrame, p: Palette) {
    val ink = p.gridInk
    val base = OrbGeometry.gridBaseAlpha.toFloat()
    val spokes = (360 / OrbGeometry.spokeStepDegrees).toInt()
    for (i in 0 until spokes) {
        val theta = i * OrbGeometry.spokeStepDegrees * PI / 180
        drawLine(ink.copy(alpha = base), f.point(OrbGeometry.ringInner, theta), f.point(OrbGeometry.ringOuter, theta), strokeWidth = 0.8f * density)
    }
    for (radius in OrbGeometry.gridCircleRadii) {
        drawCircle(ink.copy(alpha = base * 1.3f), radius = f.r(radius), center = f.c, style = Stroke(0.7f * density))
    }
}

/** Only the part of the grid that lights up just behind the sweep, drawn over the base each frame. */
fun DrawScope.drawGridLit(f: OrbFrame, p: Palette, sweep: Double, lit: Float) {
    if (lit <= 0f) return
    val ink = p.gridInk
    val base = OrbGeometry.gridBaseAlpha.toFloat()
    val peak = OrbGeometry.gridMaxAlpha.toFloat()
    val reach = 30.0 * PI / 180
    val step = OrbGeometry.spokeStepDegrees * PI / 180
    val first = Math.floor((sweep - reach) / step).toInt()
    val last = Math.floor(sweep / step).toInt()
    for (k in first..last) {
        val theta = k * step
        var behind = (sweep - theta) % (2 * PI)
        if (behind < 0) behind += 2 * PI
        if (behind >= reach) continue
        val near = (1 - behind / reach).toFloat()
        drawLine(ink.copy(alpha = (peak - base) * near * lit), f.point(OrbGeometry.ringInner, theta), f.point(OrbGeometry.ringOuter, theta), strokeWidth = 0.8f * density)
    }
    for (radius in OrbGeometry.gridCircleRadii) {
        drawPath(arcPath(f, radius, sweep - reach, sweep), ink.copy(alpha = (peak - base * 1.3f) * lit), style = Stroke(0.9f * density))
    }
}

/** Read-out colours by the trace's height (its radius), in one hue family. Dark: near-black maroon through deep red to bright red. Light: the same shift in blue/green. */
fun traceColour(amplitude: Double, light: Boolean): Color {
    val stops = if (light) {
        listOf(Color(0xFF06262B), Color(0xFF0B5F63), Color(0xFF129C8E), Color(0xFF4FE3C1))
    } else {
        listOf(Color(0xFF4A0709), Color(0xFF9E0F16), Color(0xFFE11D25), Color(0xFFFF5A4F))
    }
    val t = ((amplitude + 1) / 2).coerceIn(0.0, 1.0) * (stops.size - 1)
    val i = min(stops.size - 2, t.toInt())
    return stops[i].mixed(stops[i + 1], (t - i).toFloat())
}

/**
 * The PPG trace around the ring. `end` is seconds since the trace's zero; angle is time on the sweep's clock,
 * so the newest sample sits under the head and (while `aged`) the last revolution fades behind it. `pulse`
 * (0..1) brightens the whole trace on a beat.
 */
fun DrawScope.drawTrace(f: OrbFrame, p: Palette, trace: DoubleArray, end: Double, now: Double, aged: Boolean, pulse: Float) {
    if (trace.size <= 3) return
    val rate = ScanSession.ANALYSIS_RATE
    val period = OrbGeometry.sweepPeriodMs / 1000
    fun time(i: Int) = end - (trace.size - 1 - i) / rate
    fun pt(i: Int) = f.point(OrbGeometry.arcRadius(trace[i]), OrbGeometry.sweepAngle(time(i) * 1000))
    class Seg(val path: Path, val colour: Color, val alpha: Float)
    val segs = ArrayList<Seg>()
    val step = 3
    var i = 0
    while (i < trace.size - 1) {
        val j = min(trace.size - 1, i + step)
        val path = Path()
        val a = pt(i)
        path.moveTo(a.x, a.y)
        for (k in i + 1..j) { val q = pt(k); path.lineTo(q.x, q.y) }
        var sum = 0.0
        for (k in i..j) sum += trace[k]
        val mean = sum / (j - i + 1)
        val age = if (aged) ((now - time(j)) / period).coerceIn(0.0, 1.0) else 0.0
        val b = pt(j)
        // A wrap back to the start of the circle would draw a line across the ring; skip it.
        if (hypot(a.x - b.x, a.y - b.y) < f.R * 0.5f) segs.add(Seg(path, traceColour(mean, p.isLight), (1 - 0.78 * age).toFloat()))
        i = j
    }
    val boost = 1 + 0.9f * pulse
    val crest = if (p.isLight) Color(0xFF1FD6B8) else Color(0xFFFF2B2B)
    // Glow: a few widening, fainter strokes stand in for a blur.
    for ((w, a) in listOf(7f to 0.18f, 4.5f to 0.30f)) {
        for (s in segs) drawPath(s.path, s.colour.copy(alpha = min(1f, a * s.alpha * boost)), style = Stroke(w * density * (1 + 0.6f * pulse), cap = StrokeCap.Round), blendMode = BlendMode.Plus)
    }
    for (s in segs) {
        drawPath(s.path, s.colour.mixed(crest, 0.6f * pulse).copy(alpha = min(1f, s.alpha * (0.85f + 0.15f * pulse))),
            style = Stroke((1.4f + 0.8f * pulse) * density, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    // A lit crest on each local maximum, at least 0.35 s apart.
    var lastCrest = -1000
    val gap = (0.35 * rate).toInt()
    for (k in 2 until trace.size - 2) {
        if (trace[k] > 0.5 && trace[k] >= trace[k - 1] && trace[k] > trace[k + 1] && k - lastCrest > gap) {
            lastCrest = k
            drawCircle(crest.copy(alpha = 0.95f), radius = (2.4f + 1.2f * pulse) * density, center = pt(k))
        }
    }
}

/** Core line in the background colour, with a bright overlay glow trailing it. */
fun DrawScope.drawSweep(f: OrbFrame, p: Palette, angle: Double, strength: Float) {
    val head = OrbGeometry.sweepHeadDegrees * PI / 180
    val lead = OrbGeometry.sweepLeadDegrees * PI / 180
    val glow = p.background.mixed(Color.White, 0.82f)

    // A wedge drawn at the gradient's zero (3 o'clock), then rotated into place.
    fun wedge(width: Double, grow: Double = 0.0): Path {
        val z = PI / 2
        val outer = arcPath(f, OrbGeometry.ringOuter + grow, z - width, z)
        val back = arcPath(f, OrbGeometry.ringInner - grow, z, z - width)
        return Path().apply { addPath(outer); addPath(back); close() }
    }
    val rotation = Math.toDegrees(angle - PI / 2).toFloat()
    withTransform({ rotate(rotation, f.c) }) {
        drawPath(wedge(head), brush = trailBrush(f, glow, head, strength), blendMode = BlendMode.Overlay)
        drawPath(wedge(lead), glow.copy(alpha = 0.9f * strength), blendMode = BlendMode.Overlay)
        drawPath(wedge(head * 0.8, 0.02), brush = trailBrush(f, glow, head, strength * 0.55f), blendMode = BlendMode.Plus)
    }
    drawLine(p.background.copy(alpha = 0.95f), f.point(OrbGeometry.ringInner, angle), f.point(OrbGeometry.ringOuter, angle), strokeWidth = 2f * density)
}

/** Fades from nothing at the tail of the wedge to full at its head (the head is at gradient angle 0 = 3 o'clock). */
private fun trailBrush(f: OrbFrame, glow: Color, head: Double, strength: Float): Brush {
    val frac = (head / (2 * PI)).toFloat()
    return Brush.sweepGradient(
        colorStops = arrayOf(
            0f to glow.copy(alpha = 0.95f * strength),
            0.0001f to Color.Transparent,
            (1 - frac) to Color.Transparent,
            1f to glow.copy(alpha = 0.95f * strength),
        ),
        center = f.c,
    )
}

/** Machined face used when no camera is showing: the knob face gradient and a fine knurl. */
fun DrawScope.drawDomeFace(f: OrbFrame, p: Palette) {
    val r = f.r(OrbGeometry.domeOuter)
    val colors = if (p.lightFace) listOf(Color(0.86f, 0.86f, 0.86f), Color(0.97f, 0.97f, 0.97f), Color(0.78f, 0.78f, 0.78f))
    else listOf(Color(0.26f, 0.26f, 0.26f), Color(0.13f, 0.13f, 0.13f), Color.Black)
    drawCircle(
        brush = Brush.radialGradient(colors, center = Offset(f.c.x - r * 0.14f, f.c.y - r * 0.30f), radius = r * 1.25f),
        radius = r, center = f.c,
    )
    val ink = if (p.lightFace) Color.Black else Color.White
    for (i in 0 until 180) {
        val theta = i * PI / 90
        val strong = if (p.lightFace) 0.05f else 0.07f
        val faint = if (p.lightFace) 0.02f else 0.025f
        drawLine(ink.copy(alpha = if (i % 2 == 0) strong else faint), f.point(OrbGeometry.domeOuter * 0.90, theta), f.point(OrbGeometry.domeOuter * 0.985, theta), strokeWidth = 0.5f * density)
    }
}

/** Drawn over the camera preview: vignette, inner shadow, bezel and the unlock band. Static. */
fun DrawScope.drawDomeFrontStatic(f: OrbFrame, camera: Boolean) {
    val r = f.r(OrbGeometry.domeOuter)
    if (camera) {
        drawCircle(
            brush = Brush.radialGradient(colorStops = arrayOf(0f to Color.Transparent, 0.62f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f)), center = f.c, radius = r),
            radius = r, center = f.c,
        )
    }
    // Inner shadow where the dome sits in its well.
    clipPath(Path().apply { addOval(circleRect(f.c, r)) }) { softStroke(f.c, r, f.R * 0.05f, Color.Black, 0.55f) }
    // Bezel: dark charcoal.
    drawCircle(
        Brush.linearGradient(listOf(Color(0.22f, 0.22f, 0.22f), Color(0.06f, 0.06f, 0.06f), Color.Black), start = f.topLeft, end = f.bottomRight),
        radius = r - density, center = f.c, style = Stroke(2f * density),
    )
    // Unlock band: a thin dark charcoal ring between dome and colour ring.
    drawPath(
        annulus(f, OrbGeometry.unlockInner, OrbGeometry.ringInner),
        Brush.linearGradient(listOf(Color(0.16f, 0.16f, 0.16f), Color(0.05f, 0.05f, 0.05f), Color(0.11f, 0.11f, 0.11f)), start = f.topLeft, end = f.bottomRight),
    )
    for (unit in listOf(OrbGeometry.unlockInner, OrbGeometry.ringInner)) {
        drawCircle(Color.Black.copy(alpha = 0.45f), radius = f.r(unit), center = f.c, style = Stroke(0.8f * density))
    }
}

/** Progress lights up along the band, from 12 o'clock, only as usable signal accrues. */
fun DrawScope.drawProgress(f: OrbFrame, p: Palette, progress: Float) {
    if (progress <= 0.001f) return
    val mid = (OrbGeometry.unlockInner + OrbGeometry.ringInner) / 2
    val path = arcPath(f, mid, 0.0, 2 * PI * min(1f, progress))
    drawPath(path, p.led.copy(alpha = 0.35f), style = Stroke(f.R * 0.05f, cap = StrokeCap.Round))
    drawPath(path, p.led.mixed(Color.White, 0.3f), style = Stroke(f.R * 0.016f, cap = StrokeCap.Round))
}

/** Renders a drawing once into an image, so per-frame work is a single image draw. */
fun renderLayer(px: Int, density: Float, draw: DrawScope.(OrbFrame) -> Unit): ImageBitmap {
    val bmp = ImageBitmap(px, px)
    CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), Size(px.toFloat(), px.toFloat())) {
        draw(OrbFrame(size))
    }
    return bmp
}

/** The PPG trace as an image (rendered off the UI thread by the scan engine). */
fun renderTraceImage(px: Int, density: Float, p: Palette, trace: DoubleArray, end: Double, now: Double, aged: Boolean): ImageBitmap =
    renderLayer(px, density) { f -> drawTrace(f, p, trace, end, now, aged, 0f) }

/** Pulse factor: 1 on a beat, falling away with a 0.3 s time constant. */
fun pulseFactor(now: Double, lastBeat: Double?): Float = lastBeat?.let { exp(-(now - it) / 0.3).coerceIn(0.0, 1.0).toFloat() } ?: 0f
