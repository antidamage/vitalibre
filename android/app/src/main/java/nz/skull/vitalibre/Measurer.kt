package nz.skull.vitalibre

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.LifecycleOwner
import nz.skull.vitalibre.core.BPCalibration
import nz.skull.vitalibre.core.BPEstimator
import nz.skull.vitalibre.core.BPRange
import nz.skull.vitalibre.core.Guidance
import nz.skull.vitalibre.core.ScanOutcome
import nz.skull.vitalibre.core.ScanResult
import nz.skull.vitalibre.core.Sex
import nz.skull.vitalibre.core.UsualBP

/**
 * The screen's view of a scan. It owns no signal work: the camera hands samples to the scan engine
 * (its own high-priority thread) and this class only receives the engine's finished updates, so the
 * UI thread does nothing but show them.
 */
class Measurer(context: Context) {
    sealed interface Phase {
        data object Idle : Phase
        data object Starting : Phase
        data object Scanning : Phase
        data object Analysing : Phase
        data class Result(val result: ScanResult) : Phase
        data class Failed(val message: String) : Phase
    }

    val camera = CameraSource(context)
    private val engine = ScanEngine({ handle(it) }, { camera.decisionPending })
    private var simulated: SimulatedSource? = null

    var phase by mutableStateOf<Phase>(Phase.Idle)
        private set
    var progress by mutableDoubleStateOf(0.0)
        private set
    var guidance by mutableStateOf(Guidance.COVER_LENS)
        private set
    var liveHeartRate by mutableStateOf<Double?>(null)
        private set
    var liveBP by mutableStateOf<BPRange?>(null)
        private set
    var lastBeat by mutableStateOf<Double?>(null)
        private set
    var traceImage by mutableStateOf<ImageBitmap?>(null)
        private set
    /** The finished reading's graph, kept on screen until the next reading starts. */
    var keptImage by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Zero of the sweep on the uptime clock (ms): the moment the finger covered the lens. */
    var sweepOriginMs by mutableDoubleStateOf(SystemClock.uptimeMillis().toDouble())
        private set
    var isSimulated by mutableStateOf(false)
        private set

    /** Set by the orb once it has been laid out, so the engine can render the trace at the right size. */
    var orbPx = 0
    var density = 1f

    val isBusy get() = phase is Phase.Starting || phase is Phase.Scanning || phase is Phase.Analysing

    fun start(owner: LifecycleOwner, palette: Palette, age: Int?, sex: Sex, usual: UsualBP?, calibration: BPCalibration, simulate: Boolean = false) {
        if (isBusy) return
        progress = 0.0; liveHeartRate = null; liveBP = null; lastBeat = null; traceImage = null; keptImage = null
        guidance = Guidance.COVER_LENS
        sweepOriginMs = SystemClock.uptimeMillis().toDouble()
        isSimulated = simulate
        phase = Phase.Starting
        engine.begin(ScanConfig(Publisher.model, age, sex, usual, calibration, palette, orbPx.coerceAtLeast(64), density))
        if (simulate) {
            val src = SimulatedSource { engine.sample(it) }
            simulated = src
            src.start()
            phase = Phase.Scanning
            Haptics.start()
            return
        }
        camera.onSample = { engine.sample(it) }
        camera.start(owner) { result ->
            result.onSuccess {
                sweepOriginMs = SystemClock.uptimeMillis().toDouble()
                phase = Phase.Scanning
                Haptics.start()
            }.onFailure { fail(it.message ?: "The camera could not start.") }
        }
    }

    fun cancel() {
        stopSources()
        phase = Phase.Idle
    }

    fun fail(message: String) {
        stopSources()
        phase = Phase.Failed(message)
        Haptics.fail()
    }

    private fun stopSources() {
        engine.stop()
        camera.stop()
        simulated?.stop(); simulated = null
    }

    /** Re-derives the shown blood pressure after a new calibration point. */
    fun recalibrate(calibration: BPCalibration, age: Int?, sex: Sex, usual: UsualBP?) {
        val r = (phase as? Phase.Result)?.result ?: return
        phase = Phase.Result(r.withBP(BPEstimator.estimate(r.features, Publisher.model, age, sex, usual, calibration)))
    }

    /** Engine events arrive on the main thread. */
    private fun handle(e: EngineEvent) {
        when (e) {
            is EngineEvent.Update -> {
                if (phase !is Phase.Scanning) return
                progress = e.progress; guidance = e.guidance
                liveHeartRate = e.liveHeartRate; liveBP = e.liveBP; lastBeat = e.lastBeat
                sweepOriginMs = e.sweepOriginMs
                e.traceImage?.let { traceImage = it }
            }
            is EngineEvent.Stalled -> {
                if (phase is Phase.Scanning) fail("The camera stopped sending images. Try again.")
            }
            is EngineEvent.Finished -> {
                if (phase !is Phase.Scanning) return
                stopSources()
                when (val o = e.outcome) {
                    is ScanOutcome.Success -> {
                        progress = 1.0; keptImage = e.keptImage; traceImage = null
                        phase = Phase.Result(o.result)
                    }
                    is ScanOutcome.Failure -> phase = Phase.Failed(o.failure.message)
                }
            }
        }
    }
}
