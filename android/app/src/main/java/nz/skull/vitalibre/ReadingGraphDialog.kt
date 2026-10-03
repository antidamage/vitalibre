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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import nz.skull.vitalibre.core.ExcludedRange
import nz.skull.vitalibre.core.ReadingEdit
import nz.skull.vitalibre.core.ScanSession
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A reading's graph, full screen: the whole run at a scale where a single beat can be read, with the camera
 * feed's quality drawn over it on the same time axis.
 *
 * One finger scrolls it, two fingers pinch it, and a tap anywhere goes back, or Done, which is what an
 * accessibility service activates, because a tap-only surface is not a way out for everyone. The pan and the
 * zoom are driven from one transform gesture rather than a scroll container, because the zoom has to keep the
 * part of the graph under the fingers where it is, and that needs the offset in hand.
 *
 * "Leave out stretches" turns the one-finger drag into a selection instead of a scroll: drag across a stretch
 * to leave it out of the numbers, tap a left-out stretch to put it back. Pinch zoom still works. A left-out
 * stretch stays on the graph, dimmed, and in the file; nothing is deleted and nothing is left out automatically.
 */
@Composable
fun ReadingGraphDialog(readingId: String, onDismiss: () -> Unit) {
    val env = LocalEnv.current
    val p = LocalPalette.current
    val store = env.readings
    val prefs = env.prefs
    val reading = store.reading(readingId)
    if (reading == null) { onDismiss(); return }
    val trace = reading.trace ?: emptyList()
    val traceStart = reading.traceStart ?: ScanSession.SETTLE_SECONDS
    val spanSeconds = max(1, trace.size - 1) / ScanSession.ANALYSIS_RATE
    val seconds = trace.size / ScanSession.ANALYSIS_RATE
    val df = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    val pxPerSecond = with(LocalDensity.current) { TraceLine.POINTS_PER_SECOND.dp.toPx() }
    val measurer = rememberTextMeasurer()
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(0f) }
    var editing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<ExcludedRange?>(null) }
    val editingNow by rememberUpdatedState(editing)
    val readingNow by rememberUpdatedState(reading)

    /**
     * Recomputes the reading without these stretches. If too little is left, or no pulse can be found in what is
     * left, nothing changes and the person is told why.
     */
    fun apply(ranges: List<ExcludedRange>) {
        val r = readingNow
        val t = r.trace ?: return
        val end = traceStart + (t.size - 1) / ScanSession.ANALYSIS_RATE
        val merged = ReadingEdit.merged(ranges, traceStart, end)
        if (merged.isEmpty()) { store.setExclusions(r.id, emptyList(), null); message = null; return }
        val age = if (prefs.age > 0) prefs.age else null
        when (val out = ReadingEdit.recompute(t.toDoubleArray(), traceStart, merged, Publisher.model, age, prefs.sex, prefs.usual, prefs.calibration)) {
            is ReadingEdit.Result.Ok -> { store.setExclusions(r.id, merged, out.outcome); message = null }
            is ReadingEdit.Result.TooLittleSignalLeft ->
                message = "Too little signal left: at least ${ReadingEdit.MIN_KEPT_SECONDS.toInt()} s is needed. The previous result stays."
            is ReadingEdit.Result.NoPulse -> message = "No pulse could be found in what is left. The previous result stays."
        }
    }

    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(p.backgroundGradient)
                .pointerInput(Unit) { detectTapGestures { if (!editingNow) onDismiss() } },
        ) {
            // The graph itself is inset 22 dp each side (the canvas below), so the scrollable
            // width is the dialog's less those 44 dp. Measuring the dialog instead would leave
            // the last 44 dp, about 0.7 s at 60 dp/s, impossible to scroll into view.
            val viewport = with(LocalDensity.current) { (maxWidth - 44.dp).toPx() }
            fun contentWidth(s: Float) = max(viewport, (seconds * pxPerSecond * s).toFloat())
            /** Seconds from the start of the covered run at a point `x` px across the visible graph. */
            fun runTime(x: Float): Double {
                val w = contentWidth(scale)
                return traceStart + (offset + x).coerceIn(0f, w) / w * spanSeconds
            }
            val shape = InstrumentShape()
            // Read here: inside the Column below, the receiver is the column's, and `maxHeight`
            // would not resolve.
            val graphHeight = maxHeight * 0.4f
            val overlay = TraceOverlay(traceStart, reading.feedQuality, reading.excluded ?: emptyList(), selection)

            Column(Modifier.fillMaxSize()) {
                Column(Modifier.padding(horizontal = 22.dp).padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${reading.displayHeartRate.roundToInt()}", color = p.readout, style = Fonts.rajdhani(42.sp))
                        Text("BPM", color = p.readoutSecondary, style = Fonts.chakra(11.sp, Fonts.Face.MEDIUM), modifier = Modifier.padding(bottom = 8.dp).weight(1f))
                        Text("Done", color = p.led, style = Fonts.chakra(14.sp, Fonts.Face.MEDIUM), modifier = Modifier.padding(bottom = 8.dp).pressable({ onDismiss() }))
                    }
                    Text(df.format(Date(reading.epochMillis)), color = p.readoutSecondary, style = Fonts.rajdhani(16.sp))
                    val hidden = reading.bpHidden
                    if (hidden != null) {
                        Text(hidden.line, color = p.readoutSecondary, style = Fonts.rajdhani(14.sp))
                    } else {
                        Text("Blood pressure estimate ${reading.displayBP.text} mmHg", color = p.readoutSecondary, style = Fonts.rajdhani(16.sp))
                        Text(BPPresentation.margin(prefs), color = p.readoutSecondary, style = Fonts.rajdhani(12.sp))
                        Text(Publisher.bpCaveat, color = p.readoutSecondary, style = Fonts.rajdhani(12.sp))
                    }
                    store.noteText(reading)?.let { Text(it, color = p.led, style = Fonts.chakra(12.sp, Fonts.Face.MEDIUM)) }
                    if (reading.edited != null) {
                        Text("${reading.usedSeconds.roundToInt()} of ${reading.duration.roundToInt()} s used", color = p.readout, style = Fonts.chakra(12.sp, Fonts.Face.MEDIUM))
                    }
                }
                Spacer(Modifier.weight(1f))
                Canvas(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp).height(graphHeight)
                        .background(p.surface, shape).border(1.dp, p.edge, shape)
                        .pointerInput(editing) {
                            // One handler for all of it: a drag scrolls (or, leaving stretches out, selects), a
                            // two-finger squeeze zooms about the point between the fingers, and a press that does
                            // not move goes back (or, leaving stretches out, puts a left-out stretch back). A
                            // second detector here would compete for the same touches, and Compose cancels a
                            // transform gesture once another detector has consumed them.
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                var moved = false
                                var multi = false
                                var dragged = false
                                val startT = runTime(down.position.x)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.none { it.pressed }) break
                                    val fingers = event.changes.count { it.pressed }
                                    if (fingers >= 2) { multi = true; selection = null }
                                    if (!editing || multi) {
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
                                    } else {
                                        val x = event.changes[0].position.x
                                        if (dragged || abs(x - down.position.x) > viewConfiguration.touchSlop) {
                                            dragged = true
                                            val t = runTime(x)
                                            selection = ExcludedRange(min(startT, t), max(startT, t))
                                        }
                                    }
                                    event.changes.forEach { it.consume() }
                                }
                                if (editing && !multi) {
                                    val sel = selection
                                    selection = null
                                    if (dragged) {
                                        if (sel != null && sel.length >= 0.3) apply((readingNow.excluded ?: emptyList()) + sel)
                                    } else {
                                        val ranges = readingNow.excluded ?: emptyList()
                                        val i = ranges.indexOfFirst { it.start <= startT && startT <= it.end }
                                        if (i >= 0) apply(ranges.filterIndexed { j, _ -> j != i })
                                    }
                                } else if (!editing && !moved) {
                                    onDismiss()
                                }
                            }
                        },
                ) {
                    translate(-offset) {
                        val graphSize = Size(contentWidth(scale), size.height)
                        drawFlatTrace(trace, p, graphSize)
                        drawTraceOverlay(overlay, graphSize, spanSeconds, p, measurer)
                    }
                }
                Column(Modifier.padding(horizontal = 22.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(if (editing) "Finish leaving out" else "Leave out stretches", color = p.led, style = Fonts.chakra(13.sp, Fonts.Face.MEDIUM),
                            modifier = Modifier.pressable({ Sounds.play(Sounds.CLICK); editing = !editing; message = null; selection = null }))
                        if (reading.excluded?.isNotEmpty() == true) {
                            Text("Put all back", color = p.readoutSecondary, style = Fonts.chakra(13.sp, Fonts.Face.MEDIUM),
                                modifier = Modifier.pressable({ Sounds.play(Sounds.CLICK); apply(emptyList()) }))
                        }
                        Box(Modifier.weight(1f))
                        if (reading.feedQuality != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.width(18.dp).height(1.dp).background(p.readout.copy(alpha = 0.5f)))
                                Text("Camera feed quality", color = p.readoutSecondary, style = Fonts.rajdhani(12.sp))
                            }
                        }
                    }
                    Text(
                        if (editing) "Drag across a stretch to leave it out of the numbers. Tap a dimmed stretch to put it back."
                        else "Shaded seconds are where the camera feed was poor. Leaving them out is your choice; nothing is deleted.",
                        color = p.readoutSecondary, style = Fonts.rajdhani(12.sp),
                    )
                    message?.let { Text(it, color = p.led, style = Fonts.rajdhani(13.sp, true)) }
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth().padding(bottom = 26.dp), contentAlignment = Alignment.Center) {
                    Text(if (editing) "Pinch to zoom, drag to select" else "Pinch to zoom, drag to scroll, tap to close", color = p.readoutSecondary,
                        style = Fonts.rajdhani(13.sp), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
