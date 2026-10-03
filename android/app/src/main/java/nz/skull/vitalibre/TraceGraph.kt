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
