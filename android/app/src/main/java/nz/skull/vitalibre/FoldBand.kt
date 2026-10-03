package nz.skull.vitalibre

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** What the fold owns, shared so the screen it sits on can size it without repeating these numbers. */
object FoldMetrics {
    /** The divider's own height: a caption, a gap, and the sunken line. */
    val rest = 30.dp

    /** Less than a row of its content and there is nothing worth opening into. */
    val minReveal = 44.dp

    /**
     * The room the fold holds at the foot of the screen: its own line plus the space the panel opens
     * into. It is a height in the layout, held open whether the fold is open or shut, so opening moves
     * nothing and the panel can never reach the controls above it.
     */
    val room = 150.dp

    /** What the panel may open into: that room, less the line. */
    val maxReveal = room - rest
}

/**
 * The fold: a line that rests just above the bottom menu, pulled up to reveal what it guards.
 *
 * The mechanism is the dashboard's advanced fold (nova-ha-dashboard/specs/advanced-fold.md) on a phone
 * screen: 80dp of upward travel is caught by a quadratic band, d(p) = 28(1 - (1 - p/80)^2), which is
 * nearly 1:1 at first and moves nothing by 80dp. Released inside the band the region springs back over
 * 180ms, ease-out; at 80dp it breaks and follows the finger 1:1 from there. Pulling back down to nothing
 * re-locks it, with no resistance on the way back. With nothing past the line it does not open, and a
 * tap opens or closes it — which is also how it is reachable without a drag.
 */
@Composable
fun FoldBand(label: String, count: Int, maxReveal: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reveal = remember { Animatable(0f) }
    var grab by remember { mutableStateOf(0f) }
    var pull by remember { mutableStateOf(0f) }
    var broken by remember { mutableStateOf(false) }

    // The system's own animation switch is the reduced-motion signal on Android.
    val motion = android.provider.Settings.Global.getFloat(
        LocalContext.current.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    )
    val reduce = motion == 0f

    val bandPx = with(density) { 80.dp.toPx() }
    val givePx = with(density) { 28.dp.toPx() }
    val maxPx = with(density) { maxReveal.toPx() }
    val open = reveal.value > 0f
    // It needs something to show and somewhere to show it.
    val canOpen = count > 0 && maxPx >= with(density) { FoldMetrics.minReveal.toPx() }

    // If the day's log empties while it is open — the day rolls over — it closes itself.
    LaunchedEffect(count) {
        if (count == 0 && reveal.value > 0f) { broken = false; pull = 0f; reveal.snapTo(0f) }
    }

    fun slideTo(target: Float) {
        val t = target.coerceIn(0f, maxPx)
        if (reduce) scope.launch { reveal.snapTo(t) } else
            scope.launch { reveal.animateTo(t, tween(220, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.15f))) }
    }

    fun release() {
        if (broken) {
            if (reveal.value <= 0f) { broken = false; pull = 0f }
        } else {
            broken = false; pull = 0f
            if (reduce) scope.launch { reveal.snapTo(0f) } else scope.launch { reveal.animateTo(0f, tween(180, easing = EaseOut)) }
        }
    }

    fun toggle() {
        if (!canOpen) return
        Sounds.play(Sounds.CLICK)
        val target = if (open) 0f else maxPx
        broken = target > 0f
        pull = 0f
        slideTo(target)
    }

    Column(modifier.fillMaxWidth()) {
        if (open) {
            Box(Modifier.fillMaxWidth().height(with(density) { reveal.value.toDp() }).background(p.surface)) { content() }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .height(FoldMetrics.rest)
                .pointerInput(count, maxPx) {
                    var travel = 0f
                    detectVerticalDragGestures(
                        onDragStart = { grab = reveal.value; travel = 0f },
                        onDragEnd = { release() },
                        onDragCancel = { release() },
                    ) { change, dragAmount ->
                        change.consume()
                        travel -= dragAmount
                        if (broken || reveal.value > 0f) {
                            // Open: it tracks the finger both ways, with the cap as the limit.
                            scope.launch { reveal.snapTo((grab + travel).coerceIn(0f, maxPx)) }
                            if (grab + travel <= 0f) { broken = false; pull = 0f }
                        } else if (canOpen) {
                            pull = maxOf(0f, travel)
                            if (pull >= bandPx) {
                                broken = true
                                slideTo(pull)
                            } else {
                                val f = pull / bandPx
                                scope.launch { reveal.snapTo(givePx * (1f - (1f - f) * (1f - f))) }
                            }
                        }
                    }
                }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { toggle() },
        ) {
            Spacer(Modifier.weight(1f))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label.uppercase(), color = if (open) p.led else p.readoutSecondary, style = Fonts.chakra(11.sp, Fonts.Face.MEDIUM), letterSpacing = 1.8.sp)
                if (count > 0) {
                    Text("$count", color = p.led, style = Fonts.rajdhani(12.sp, semibold = true), modifier = Modifier.padding(start = 7.dp))
                }
                FoldTriangle(if (open) p.led else p.readoutSecondary, if (open) 180f else 0f, Modifier.padding(start = 7.dp))
            }
            Spacer(Modifier.height(4.dp))
            // The line in the theme's accent, with the accent's lit edge under it: the dashboard's
            // sunken bevel (advanced-fold.css), scaled up for a screen where the accent's own 18%
            // edge would not read.
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line.copy(alpha = if (p.isLight) 0.9f else 0.45f)))
        }
    }
}

/** The divider's solid flattened triangle, pointing at what the fold guards. */
@Composable
private fun FoldTriangle(color: Color, rotation: Float, modifier: Modifier = Modifier) {
    Canvas(modifier.size(7.dp, 5.dp).rotate(rotation)) {
        drawPath(
            Path().apply {
                moveTo(size.width / 2f, 0f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            },
            color,
        )
    }
}
