package nz.skull.vitalibre

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.sp
import nz.skull.vitalibre.core.ExcludedRange
import nz.skull.vitalibre.core.FeedQuality
import nz.skull.vitalibre.core.ScanSession
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * The saved graph on a flat surface: the orb's own trace, unwound.
 *
 * The orb wraps its trace around the ring; this is the same filtered waveform on a straight axis. One
 * painter serves both places a reading's graph appears — the band under its row and the full-screen
 * view — and it is drawn with the orb's own treatment: one hairline over two narrow additive strokes
 * (2.6 at 10%, 1.8 at 16%), butt caps, coloured by height in the same per-mode ramp. Segments run
 * sample to sample with nothing smoothed or overshot, so what is on screen is the data that arrived.
 */
object TraceLine {
    /** How wide the expanded graph is drawn, in dp per second of the run: a 15 s reading is 900 dp, about three phone widths, which is what makes a single beat legible. The band uses the row's width instead. */
    const val POINTS_PER_SECOND = 60f

    /** The band's height under a reading. */
    const val BAND_HEIGHT = 44

    /** One stroke per this many samples: a smooth line without a stroke per sample on a 900-sample graph. */
    const val SAMPLES_PER_SEGMENT = 3

    /** The zoom the expanded view opens at, and its range. */
    const val MIN_ZOOM = 0.5f
    const val MAX_ZOOM = 8f

    /** Where sample `i` sits in `size`. */
    fun point(trace: List<Double>, size: Size, i: Int): Offset {
        val step = size.width / max(1, trace.size - 1)
        val middle = size.height / 2
        val reach = max(1f, size.height / 2 - 1f)
        return Offset(i * step, middle - (trace[i].coerceIn(-1.0, 1.0).toFloat() * reach))
    }

    /** The run in strokes, each carrying the colour of its own height. */
    fun segments(trace: List<Double>, size: Size, light: Boolean): List<Pair<Path, Color>> {
        if (trace.size <= 1 || size.width <= 1f || size.height <= 1f) return emptyList()
        val out = ArrayList<Pair<Path, Color>>(trace.size / SAMPLES_PER_SEGMENT + 1)
        var i = 0
        while (i < trace.size - 1) {
            val j = min(trace.size - 1, i + SAMPLES_PER_SEGMENT)
            val path = Path()
            val start = point(trace, size, i)
            path.moveTo(start.x, start.y)
            for (k in i + 1..j) {
                val p = point(trace, size, k)
                path.lineTo(p.x, p.y)
            }
            var sum = 0.0
            for (k in i..j) sum += trace[k]
            out.add(path to traceColour(sum / (j - i + 1), light))
            i = j
        }
        return out
    }
}

/**
 * The graph, drawn across `graphSize` rather than the canvas, so the caller can hand it a width wider
 * than the viewport and translate it. The zero line comes with it, so a trace that sits high or low
 * reads as high or low.
 */
fun DrawScope.drawFlatTrace(trace: List<Double>, p: Palette, graphSize: Size = this.size) {
    val segs = TraceLine.segments(trace, graphSize, p.isLight)
    if (segs.size <= 1) return
    drawLine(p.line.copy(alpha = 0.6f), Offset(0f, graphSize.height / 2), Offset(graphSize.width, graphSize.height / 2), strokeWidth = 0.5f * density)
    for ((w, a) in listOf(2.6f to 0.10f, 1.8f to 0.16f)) {
        for ((path, colour) in segs) {
            drawPath(path, colour.copy(alpha = a), style = Stroke(w * density, cap = StrokeCap.Butt, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
        }
    }
    for ((path, colour) in segs) {
        drawPath(path, colour, style = Stroke(1f * density, cap = StrokeCap.Butt, join = StrokeJoin.Round))
    }
}

/**
 * What the saved reading's graph draws over the waveform: the feed-quality line, the seconds it calls poor,
 * the stretches the user has left out, and the stretch being dragged. All times are seconds from the start
 * of the covered run, the axis the stored trace, the feed series and the exclusions share (feed point `k`
 * is the window ending `k + 1` s into the run).
 */
data class TraceOverlay(
    val traceStart: Double,
    val feed: List<Double>?,
    val excluded: List<ExcludedRange> = emptyList(),
    val selection: ExcludedRange? = null,
)

/** Draws [o] across [graphSize], where [seconds] of trace span the whole width. */
fun DrawScope.drawTraceOverlay(o: TraceOverlay, graphSize: Size, seconds: Double, p: Palette, measurer: TextMeasurer) {
    if (seconds <= 0) return
    val h = graphSize.height
    fun x(t: Double) = ((t - o.traceStart) / seconds * graphSize.width).toFloat()

    // Seconds along the foot, a label every five.
    var s = Math.ceil(o.traceStart).toInt()
    while (s <= o.traceStart + seconds) {
        val px = x(s.toDouble())
        val len = if (s % 5 == 0) 6f else 3f
        drawLine(p.readoutSecondary.copy(alpha = 0.5f), Offset(px, h), Offset(px, h - len * density), strokeWidth = 0.5f * density)
        if (s % 5 == 0) {
            drawText(measurer, "${s}s", Offset(px + 3 * density, h - 20 * density),
                style = androidx.compose.ui.text.TextStyle(color = p.readoutSecondary, fontSize = 10.sp))
        }
        s++
    }

    o.feed?.let { feed ->
        // Seconds the index calls below fair are shaded as a suggestion. Nothing is left out for the user.
        feed.forEachIndexed { k, v ->
            if (v < FeedQuality.FAIR) {
                val t1 = (k + 1).toDouble()
                drawRect(p.led.copy(alpha = 0.12f), Offset(x(t1 - 1), 0f), Size(x(t1) - x(t1 - 1), h), style = Fill)
            }
        }
        // The index itself: thin, half opacity, 0 at the foot and 1 at the top.
        val line = Path()
        feed.forEachIndexed { k, v ->
            val pt = Offset(x((k + 1).toDouble()), h - 2 * density - v.coerceIn(0.0, 1.0).toFloat() * (h - 4 * density))
            if (k == 0) line.moveTo(pt.x, pt.y) else line.lineTo(pt.x, pt.y)
        }
        drawPath(line, p.readout.copy(alpha = 0.5f), style = Stroke(1f * density))
    }

    for (r in o.excluded) {
        val a = x(r.start); val b = x(r.end)
        drawRect(p.background.copy(alpha = 0.72f), Offset(a, 0f), Size(b - a, h), style = Fill)
        for (edge in listOf(a, b)) {
            drawLine(p.led.copy(alpha = 0.6f), Offset(edge, 0f), Offset(edge, h), strokeWidth = 1f * density,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * density, 3f * density)))
        }
    }

    o.selection?.let { sel ->
        drawRect(p.led.copy(alpha = 0.25f), Offset(x(sel.start), 0f), Size(x(sel.end) - x(sel.start), h), style = Fill)
    }
}

/**
 * A reading's saved graph as the horizontal band under its row. Decorative: the row's own button
 * carries the label and the tap.
 */
@Composable
fun TraceBand(trace: List<Double>, modifier: Modifier = Modifier, height: Int = TraceLine.BAND_HEIGHT) {
    val p = LocalPalette.current
    val shape = InstrumentShape(8.dp)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .background(p.background.copy(alpha = 0.55f), shape)
            .clip(shape),
    ) { drawFlatTrace(trace, p) }
}
