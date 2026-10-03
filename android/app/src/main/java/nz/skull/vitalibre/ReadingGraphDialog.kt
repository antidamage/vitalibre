package nz.skull.vitalibre

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nz.skull.vitalibre.core.ScanSession
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A reading's graph, full screen: the whole run at a scale where a single beat can be read.
 *
 * One finger scrolls it, two fingers pinch it, and a tap anywhere goes back — or Done, which is what
 * an accessibility service activates, because a tap-only surface is not a way out for everyone. The
 * pan and the zoom are driven from one transform gesture rather than a scroll container, because the
 * zoom has to keep the part of the graph under the fingers where it is, and that needs the offset in
 * hand.
 */
@Composable
fun ReadingGraphDialog(reading: Reading, onDismiss: () -> Unit) {
    val p = LocalPalette.current
    val trace = reading.trace ?: emptyList()
    val seconds = trace.size / ScanSession.ANALYSIS_RATE
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val pxPerSecond = with(LocalDensity.current) { TraceLine.POINTS_PER_SECOND.dp.toPx() }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(0f) }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(p.backgroundGradient)
                .pointerInput(Unit) { detectTapGestures { onDismiss() } },
        ) {
            // The graph itself is inset 22 dp each side (the canvas below), so the scrollable
            // width is the dialog's less those 44 dp. Measuring the dialog instead would leave
            // the last 44 dp — about 0.7 s at 60 dp/s — impossible to scroll into view.
            val viewport = with(LocalDensity.current) { (maxWidth - 44.dp).toPx() }
            fun contentWidth(s: Float) = max(viewport, (seconds * pxPerSecond * s).toFloat())
            val shape = InstrumentShape()
            // Read here: inside the Column below, the receiver is the column's, and `maxHeight`
            // would not resolve.
            val graphHeight = maxHeight * 0.4f

            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 22.dp).padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${reading.heartRate.roundToInt()}", color = p.readout, style = Fonts.rajdhani(42.sp))
                        Text("BPM", color = p.readoutSecondary, style = Fonts.chakra(11.sp, Fonts.Face.MEDIUM), modifier = Modifier.padding(bottom = 8.dp).weight(1f))
                        Text("Done", color = p.led, style = Fonts.chakra(14.sp, Fonts.Face.MEDIUM), modifier = Modifier.padding(bottom = 8.dp).pressable({ onDismiss() }))
                    }
                    Text(df.format(Date(reading.epochMillis)), color = p.readoutSecondary, style = Fonts.rajdhani(16.sp))
                    Text("Blood pressure estimate ${reading.bp.text} mmHg", color = p.readoutSecondary, style = Fonts.rajdhani(16.sp))
                    reading.note?.let { Text(it, color = p.led, style = Fonts.chakra(12.sp, Fonts.Face.MEDIUM)) }
                }
                Spacer(Modifier.weight(1f))
                Canvas(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp).height(graphHeight)
                        .background(p.surface, shape).border(1.dp, p.edge, shape)
                        .pointerInput(Unit) {
                            // One handler for all three: a drag scrolls, a two-finger squeeze zooms about
                            // the point between the fingers, and a press that does not move goes back.
                            // A second tap detector here would compete for the same touches, and Compose
                            // cancels a transform gesture once another detector has consumed them.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var moved = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.none { it.pressed }) break
                                    val centroid = event.calculateCentroid(useCurrent = true)
                                    val zoom = event.calculateZoom()
                                    val pan = event.calculatePan()
                                    if (zoom != 1f || pan.getDistance() > 0.5f) {
                                        val oldContent = contentWidth(scale)
                                        val fraction = if (oldContent > 1f) (offset + centroid.x) / oldContent else 0f
                                        scale = (scale * zoom).coerceIn(TraceLine.MIN_ZOOM, TraceLine.MAX_ZOOM)
                                        val content = contentWidth(scale)
                                        // The graph follows the finger: a drag left shows later samples, so the
                                        // offset grows as the pan goes negative.
                                        offset = (fraction * content - centroid.x - pan.x).coerceIn(0f, max(0f, content - viewport))
                                        moved = true
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                                if (!moved) onDismiss()
                            }
                        },
                ) {
                    translate(-offset) { drawFlatTrace(trace, p, Size(contentWidth(scale), size.height)) }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().padding(bottom = 26.dp), contentAlignment = Alignment.Center) {
                    Text("Pinch to zoom, drag to scroll, tap to close", color = p.readoutSecondary,
                        style = Fonts.rajdhani(13.sp), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
