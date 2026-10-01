package nz.skull.vitalibre

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import nz.skull.vitalibre.core.OrbGeometry

/** What the orb shows. The Measure screen builds one from the Measurer. */
class OrbInput(
    val centre: String = "Start",
    val sub: String? = null,
    val caption: String? = null,
    val showsCamera: Boolean = false,
    val scanning: Boolean = false,
    val progress: Float = 0f,
    /** The live trace, rendered off the UI thread. */
    val traceImage: ImageBitmap? = null,
    /** The finished reading's graph; stays until the next reading starts. */
    val keptImage: ImageBitmap? = null,
    /** Run-relative time of the latest beat; drives the pulse. */
    val lastBeat: Double? = null,
    val showsSweep: Boolean = true,
)

/**
 * The status orb with the wide ring. Everything that does not move is rendered once into an image;
 * each frame draws only the lit grid, the trace image, the sweep and the progress arc, in its own
 * layer, so the animation never re-records the rest of the screen. `originMs` is the sweep's zero on
 * the uptime clock.
 */
@Composable
fun OrbView(
    input: OrbInput, originMs: Double, modifier: Modifier = Modifier,
    onSize: (px: Int, density: Float) -> Unit = { _, _ -> },
    camera: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    BoxWithConstraints(modifier.aspectRatio(1f)) {
        val density = LocalDensity.current
        val sizePx = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(2)
        val bodyR = sizePx / 2f / (1 + OrbGeometry.covingWidth.toFloat())
        val domePx = 2 * bodyR * OrbGeometry.domeOuter.toFloat()
        val domeDp = with(density) { domePx.toDp() }
        LaunchedEffect(sizePx) { onSize(sizePx, density.density) }

        val body = remember(sizePx, p) {
            renderLayer(sizePx, density.density) { f -> drawOrbBody(f, p); drawDomeFace(f, p) }
        }
        val front = remember(sizePx, input.showsCamera) {
            renderLayer(sizePx, density.density) { f -> drawDomeFrontStatic(f, input.showsCamera) }
        }

        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawImage(body) }

        if (camera != null) {
            Box(Modifier.size(domeDp).align(Alignment.Center).clip(CircleShape).alpha(if (input.showsCamera) 1f else 0f)) { camera() }
        }

        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawImage(front) }

        var nowMs by remember { mutableDoubleStateOf(SystemClock.uptimeMillis().toDouble()) }
        LaunchedEffect(Unit) {
            while (true) androidx.compose.runtime.withFrameMillis { nowMs = SystemClock.uptimeMillis().toDouble() }
        }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) {
            val f = OrbFrame(size)
            val now = (nowMs - originMs) / 1000.0
            val angle = OrbGeometry.sweepAngle(now * 1000)
            val strength = if (input.scanning) 1f else 0.55f
            if (input.showsSweep) drawGridLit(f, p, angle, strength)
            if (input.scanning) {
                input.traceImage?.let { img ->
                    drawImage(img)
                    // On a beat the whole trace flares, then fades back out.
                    val pulse = pulseFactor(now, input.lastBeat)
                    if (pulse > 0.02f) drawImage(img, alpha = 0.7f * pulse, blendMode = BlendMode.Plus)
                }
            } else {
                input.keptImage?.let { drawImage(it) }
            }
            if (input.showsSweep) drawSweep(f, p, angle, strength)
            drawProgress(f, p, input.progress)
        }

        CentreText(input, domePx)
    }
}

@Composable
private fun CentreText(input: OrbInput, domePx: Float) {
    val p = LocalPalette.current
    val density = LocalDensity.current
    val over = input.showsCamera
    fun sp(px: Float) = with(density) { px.toSp() }
    val shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = if (over) 0.8f else 0f), blurRadius = if (over) 8f else 0f)
    Column(
        Modifier.fillMaxSize().padding(horizontal = with(density) { (domePx * 0.08f).toDp() }).semantics {
            contentDescription = listOfNotNull(input.centre, input.sub, input.caption).filter { it.isNotEmpty() }.joinToString(", ")
        },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        if (input.centre.isNotEmpty()) {
            val big = if (input.sub == null && input.caption == null) 0.27f else 0.34f
            Text(input.centre, color = if (over) Color.White else p.readout, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
                style = Fonts.chakra(sp(domePx * big), Fonts.Face.LIGHT).copy(shadow = shadow), textAlign = TextAlign.Center)
        }
        input.caption?.let {
            Text(it.uppercase(), color = if (over) Color.White.copy(alpha = 0.85f) else p.readoutSecondary, maxLines = 1,
                style = Fonts.rajdhani(sp(domePx * 0.075f), semibold = true).copy(shadow = shadow), letterSpacing = 1.5.sp)
        }
        input.sub?.let {
            Text(it, color = if (over) Color.White else p.readout, maxLines = 1, softWrap = false,
                style = Fonts.chakra(sp(domePx * 0.105f), Fonts.Face.MEDIUM).copy(shadow = shadow))
        }
    }
}
