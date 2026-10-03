package nz.skull.vitalibre

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import nz.skull.vitalibre.core.FoldBand
import nz.skull.vitalibre.core.FoldDrive
import nz.skull.vitalibre.core.FoldPull

/** What the fold's line owns: its own height. The band's numbers live in `core/FoldBand.kt`. */
object FoldMetrics {
    /** The divider's own height: a caption, a gap, and the sunken line. */
    val rest = 30.dp
}

/**
 * The page the fold lives on: one scrolling column with the whole measure screen in it, the day's
 * readings under its line.
 *
 * The first 80dp of upward pull is caught by the band (`core/FoldBand.kt`) instead of moving the
 * page 1:1 — nearly the same at first, then not at all — before the band breaks, the page catches
 * up to where the finger is (220ms, the dashboard's curve) and scrolls freely. Brought back to its
 * edge the pull re-locks, so the next one meets the band again. Because it is the page's own offset
 * the band fights, the whole screen above the line slides up with it.
 */
@Composable
fun FoldPage(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
    panel: @Composable () -> Unit,
    upper: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val state = rememberScrollState()

    // The pull lives outside Compose state: the band is applied to the scroll state directly, so
    // nothing here has to recompose to move the page.
    val pull = remember { FoldPull() }
    var fed by remember { mutableStateOf(0f) }
    var open by remember { mutableStateOf(false) }

    // The system's own animation switch is the reduced-motion signal on Android.
    val motion = android.provider.Settings.Global.getFloat(
        LocalContext.current.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
    )
    val reduce = motion == 0f

    val bandPx = with(density) { FoldBand.TRAVEL.toFloat() }
    val restPx = with(density) { FoldMetrics.rest.toPx() }

    // Coming back to the fold's edge re-locks it: the next pull meets the band again.
    LaunchedEffect(state) {
        snapshotFlow { state.value }.collect { value ->
            if (value == 0 && open) { open = false; fed = 0f; pull.reset() }
        }
    }

    val connection = remember(bandPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                if (open) return Offset.Zero
                // While the band holds, the page's place is the band's, whichever way the finger
                // goes: it eats the whole event and moves by the band's allowance alone.
                val before = FoldBand.held(fed.toDouble())
                fed = (fed - dy).coerceIn(0f, bandPx)
                when (val drive = pull.move(up = fed.toDouble())) {
                    is FoldDrive.Held -> state.dispatchRawDelta((drive.offset - before).toFloat())
                    is FoldDrive.Broke -> {
                        open = true
                        state.dispatchRawDelta((FoldBand.held(fed.toDouble()) - before).toFloat())
                        val catchUp = drive.catchUp.toFloat()
                        scope.launch {
                            if (reduce) state.scrollBy(catchUp)
                            else state.animateScrollBy(catchUp, tween(220, easing = CubicBezierEasing(0.2f, 0.9f, 0.3f, 1.15f)))
                        }
                    }
                    else -> Unit
                }
                return Offset(0f, dy)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (open) return Velocity.Zero
                if (fed <= 0f) return Velocity.Zero
                // Released inside the band: the page goes back to rest over 180ms, ease-out, and
                // the release's own fling goes with it. There is no resistance on the way back.
                scope.launch {
                    if (reduce) state.scrollTo(0) else state.animateScrollTo(0, tween(180, easing = EaseOut))
                }
                fed = 0f
                pull.reset()
                return available
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val room = maxHeight - FoldMetrics.rest
        Column(
            Modifier
                .fillMaxSize()
                .nestedScroll(connection)
                .verticalScroll(state),
        ) {
            Column(Modifier.fillMaxWidth().height(room), verticalArrangement = Arrangement.Center) {
                upper()
            }
            FoldDivider(label, count, open) {
                if (open) {
                    open = false; fed = 0f; pull.reset()
                    scope.launch { if (reduce) state.scrollTo(0) else state.animateScrollTo(0) }
                } else {
                    fed = 0f; open = true; pull.reset()
                    val top = with(density) { (room + FoldMetrics.rest).roundToPx() }
                    val target = minOf(state.maxValue, top)
                    scope.launch { if (reduce) state.scrollTo(target) else state.animateScrollTo(target) }
                }
            }
            panel()
        }
    }
}

/**
 * The fold's line: the caption, the count of what it guards, a triangle pointing the way it opens,
 * and the sunken bevel in the theme's accent. It rests on the bottom of the screen area, just above
 * the bottom bar, and rides up with the page when the fold is pulled open.
 */
@Composable
private fun FoldDivider(label: String, count: Int, open: Boolean, onTap: () -> Unit) {
    val p = LocalPalette.current
    Column(
        Modifier
            .fillMaxWidth()
            .height(FoldMetrics.rest)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTap() }
            .semantics { stateDescription = if (open) "open" else "closed" },
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
        // sunken bevel (advanced-fold.css), scaled up for a screen where the accent's own 18% edge
        // would not read.
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line.copy(alpha = if (p.isLight) 0.9f else 0.45f)))
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
