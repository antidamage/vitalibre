package nz.skull.vitalibre

import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box as LayoutBox
import nz.skull.vitalibre.core.CalibrationPoint
import nz.skull.vitalibre.core.ScanResult
import nz.skull.vitalibre.core.ScanSession
import kotlin.math.roundToInt

@Composable
fun MeasureScreen() {
    val env = LocalEnv.current
    val p = LocalPalette.current
    val m = env.measurer
    val prefs = env.prefs
    var savedId by remember { mutableStateOf<String?>(null) }
    var orbBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var calibrating by remember { mutableStateOf(false) }
    val idleOrigin = remember { SystemClock.uptimeMillis().toDouble() }
    val phase = m.phase

    val orb = when (phase) {
        is Measurer.Phase.Idle, is Measurer.Phase.Starting -> OrbInput(centre = "Start")
        is Measurer.Phase.Scanning -> OrbInput(
            centre = m.liveHeartRate?.let { "${it.roundToInt()}" } ?: "", sub = m.liveBP?.text,
            caption = if (m.liveHeartRate == null) null else "bpm", showsCamera = !m.isSimulated, scanning = true,
            progress = m.progress.toFloat(), traceImage = m.traceImage, lastBeat = m.lastBeat,
        )
        is Measurer.Phase.Analysing -> OrbInput(centre = "…")
        is Measurer.Phase.Result -> OrbInput(
            centre = "${phase.result.heartRate.roundToInt()}", sub = phase.result.bp.text, caption = "bpm",
            keptImage = m.keptImage, showsSweep = false,
        )
        is Measurer.Phase.Failed -> OrbInput(centre = "Retry", keptImage = m.keptImage)
    }

    fun tapOrb() {
        when (phase) {
            is Measurer.Phase.Idle, is Measurer.Phase.Result, is Measurer.Phase.Failed -> {
                Sounds.play(Sounds.START)
                savedId = null
                env.activity.ensureCamera(
                    onGranted = { m.start(env.activity, p, if (prefs.age > 0) prefs.age else null, prefs.sex, prefs.usual, prefs.calibration, env.activity.simulate) },
                    onDenied = { m.fail("Camera access is off. Allow it in the app settings to take a reading.") },
                )
            }
            is Measurer.Phase.Starting, is Measurer.Phase.Scanning -> { Sounds.play(Sounds.CLICK); m.cancel() }
            is Measurer.Phase.Analysing -> Unit
        }
    }

    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        OrbView(
            orb, if (phase is Measurer.Phase.Scanning) m.sweepOriginMs else idleOrigin,
            Modifier.widthIn(max = 430.dp).padding(horizontal = 8.dp)
                .onGloballyPositioned { orbBounds = it.boundsInWindow() }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { tapOrb() },
            onSize = { px, d -> m.orbPx = px; m.density = d },
            camera = {
                AndroidView({
                    (m.camera.previewView.parent as? android.view.ViewGroup)?.removeView(m.camera.previewView)
                    m.camera.previewView
                }, Modifier.fillMaxSize())
            },
        )
        Column(Modifier.padding(top = 18.dp).height(80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (phase) {
                is Measurer.Phase.Scanning ->
                    Text(m.guidance.text, color = p.readout, style = Fonts.chakra(15.sp, Fonts.Face.MEDIUM), textAlign = TextAlign.Center)
                is Measurer.Phase.Result -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    val r = phase.result
                    RingButton(if (savedId == null) "Save" else "Saved", enabled = savedId == null) {
                        Sounds.play(Sounds.CLICK); savedId = env.readings.add(r).id
                    }
                    RingButton("Calibrate") { Sounds.play(Sounds.CLICK); calibrating = true }
                    Box(Modifier.size(44.dp).clickable { env.activity.shareText(shareText(r.heartRate, r.bp.text)) }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Share, "Share as text", tint = p.clock, modifier = Modifier.size(20.dp))
                    }
                    Box(Modifier.size(44.dp).clickable { orbBounds?.let { env.activity.shareOrb(it) } }, contentAlignment = Alignment.Center) {
                        PictureGlyph(p.clock)
                    }
                }
                is Measurer.Phase.Failed ->
                    Text(phase.message, color = p.led, style = Fonts.rajdhani(17.sp, semibold = true), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp))
                else -> Unit
            }
        }
        Spacer(Modifier.weight(1f))
    }

    val shown = phase as? Measurer.Phase.Result
    if (calibrating && shown != null) {
        CalibrateDialog(Prefs.DEFAULT_SYSTOLIC, Prefs.DEFAULT_DIASTOLIC, onDismiss = { calibrating = false }) { cs, cd ->
            addCuffReading(env, shown.result, cs, cd)
            calibrating = false
        }
    }
}

/** Pairs a cuff reading with the scan on screen and refreshes the shown estimate. */
fun addCuffReading(env: AppEnv, result: ScanResult, cuffSystolic: Int, cuffDiastolic: Int) {
    env.prefs.addCalibration(CalibrationPoint(result.rawSystolic, result.rawDiastolic, cuffSystolic.toDouble(), cuffDiastolic.toDouble(),
        System.currentTimeMillis() / 1000.0, android.os.Build.MODEL, result.baseSystolic, result.baseDiastolic))
    env.measurer.recalibrate(env.prefs.calibration, if (env.prefs.age > 0) env.prefs.age else null, env.prefs.sex, env.prefs.usual)
}

private fun shareText(hr: Double, bp: String) =
    "Heart rate ${hr.roundToInt()} bpm. Blood pressure estimate $bp mmHg. Estimates only, not a medical device. VitaLibre, " +
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date()) + "."

@Composable
private fun PictureGlyph(color: androidx.compose.ui.graphics.Color) {
    Canvas(Modifier.size(20.dp)) {
        drawRoundRect(color, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()), style = Stroke(1.8.dp.toPx()))
        drawCircle(color, 2.2.dp.toPx(), Offset(size.width * 0.3f, size.height * 0.32f))
        drawLine(color, Offset(size.width * 0.12f, size.height * 0.85f), Offset(size.width * 0.45f, size.height * 0.5f), 1.8.dp.toPx())
        drawLine(color, Offset(size.width * 0.45f, size.height * 0.5f), Offset(size.width * 0.7f, size.height * 0.75f), 1.8.dp.toPx())
        drawLine(color, Offset(size.width * 0.7f, size.height * 0.75f), Offset(size.width * 0.82f, size.height * 0.62f), 1.8.dp.toPx())
    }
}

/** Pairs a cuff reading with the scan on screen. */
@Composable
fun CalibrateDialog(startSystolic: Int, startDiastolic: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    val p = LocalPalette.current
    var sys by remember { mutableStateOf(startSystolic.coerceIn(70, 250)) }
    var dia by remember { mutableStateOf(startDiastolic.coerceIn(40, 150)) }
    FullDialog(onDismiss) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("CALIBRATE", color = p.clock, style = Fonts.chakra(15.sp, Fonts.Face.MEDIUM), letterSpacing = 3.sp)
                Text("Cancel", color = p.clock, style = Fonts.chakra(14.sp, Fonts.Face.MEDIUM), modifier = Modifier.clickable { onDismiss() })
            }
            Row(Modifier.fillMaxWidth().panel(14).padding(16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("SYSTOLIC", color = p.readoutSecondary, style = Fonts.rajdhani(12.sp, true), letterSpacing = 1.5.sp)
                    MenuPicker("$sys", (70..250).map { "$it" to it }, { sys = it })
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("DIASTOLIC", color = p.readoutSecondary, style = Fonts.rajdhani(12.sp, true), letterSpacing = 1.5.sp)
                    MenuPicker("$dia", (40..150).map { "$it" to it }, { dia = it })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                RingButton("Save", enabled = CalibrationPoint.isPlausible(sys.toDouble(), dia.toDouble())) { onSave(sys, dia) }
            }
        }
    }
}
