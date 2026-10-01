package nz.skull.vitalibre

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import nz.skull.vitalibre.core.BPCalibration
import nz.skull.vitalibre.core.BPModel
import nz.skull.vitalibre.core.BPRange
import nz.skull.vitalibre.core.Guidance
import nz.skull.vitalibre.core.PPGSample
import nz.skull.vitalibre.core.ScanOutcome
import nz.skull.vitalibre.core.ScanSession
import nz.skull.vitalibre.core.Sex
import nz.skull.vitalibre.core.UsualBP

class ScanConfig(
    val model: BPModel, val age: Int?, val sex: Sex, val usual: UsualBP?, val calibration: BPCalibration,
    val palette: Palette, val orbPx: Int, val density: Float,
)

sealed interface EngineEvent {
    class Update(
        val progress: Double, val guidance: Guidance, val liveHeartRate: Double?, val liveBP: BPRange?,
        val lastBeat: Double?, val sweepOriginMs: Double, val traceImage: ImageBitmap?,
    ) : EngineEvent

    class Finished(val outcome: ScanOutcome, val keptImage: ImageBitmap?) : EngineEvent

    /** No frames have arrived for a few seconds: the camera stopped delivering. */
    data object Stalled : EngineEvent
}

/**
 * All the signal work for a reading, on its own high-priority thread: samples in from the camera
 * thread, filtering, beat detection, the live estimate, the trace image and the final analysis.
 * The UI thread only receives finished results (events are delivered on the main thread), so a slow
 * frame can never delay a reading and a reading can never stall the screen.
 */
class ScanEngine(private val onEvent: (EngineEvent) -> Unit) {
    private val thread = HandlerThread("vitalibre-scan", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    // State below is touched only on the engine thread.
    private var session = ScanSession()
    private var config: ScanConfig? = null
    private var active = false
    private var lastUpdate = -1.0
    private var lastBeat: Double? = null
    private var lastSampleAt = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            if (SystemClock.uptimeMillis() - lastSampleAt > 3000) {
                active = false
                main.post { onEvent(EngineEvent.Stalled) }
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun begin(cfg: ScanConfig) {
        handler.post {
            session = ScanSession(); config = cfg; active = true; lastUpdate = -1.0; lastBeat = null
            lastSampleAt = SystemClock.uptimeMillis()
            handler.removeCallbacks(watchdog)
            handler.postDelayed(watchdog, 4000)
        }
    }

    fun stop() { handler.post { active = false } }

    /** Called from the camera thread. */
    fun sample(s: PPGSample) { handler.post { receive(s) } }

    private fun receive(s: PPGSample) {
        val cfg = config ?: return
        if (!active) return
        lastSampleAt = SystemClock.uptimeMillis()
        session.add(s)
        if (s.t - lastUpdate >= 0.1) {
            lastUpdate = s.t
            val run = session.currentRunSeconds
            val progress = minOf(1.0, run / ScanSession.TARGET_SECONDS)
            val origin = SystemClock.uptimeMillis() - run * 1000
            val guidance = session.guidance
            val live = session.live(cfg.model, cfg.age, cfg.sex, cfg.usual, cfg.calibration)
            var image: ImageBitmap? = null
            if (live != null) {
                // A beat time can shift slightly as the window is refiltered, so only a clearly later one counts.
                val prev = lastBeat
                val beat = live.lastBeat
                if (beat != null && (prev == null || beat > prev + 0.3)) {
                    Haptics.beat()
                    lastBeat = beat
                }
                image = renderTraceImage(cfg.orbPx, cfg.density, cfg.palette, live.trace, live.traceEnd, (SystemClock.uptimeMillis() - origin) / 1000.0, aged = true)
            }
            val update = EngineEvent.Update(progress, guidance, live?.heartRate, live?.bp, lastBeat, origin, image)
            main.post { onEvent(update) }
        }
        if (session.finished) finish(cfg)
    }

    private fun finish(cfg: ScanConfig) {
        active = false
        val outcome = session.analyse(cfg.model, cfg.age, cfg.sex, cfg.usual, cfg.calibration)
        var kept: ImageBitmap? = null
        if (outcome is ScanOutcome.Success) {
            Haptics.end(); Sounds.play(Sounds.DONE)
            kept = renderTraceImage(cfg.orbPx, cfg.density, cfg.palette, outcome.result.trace, outcome.result.traceEnd, 0.0, aged = false)
        } else {
            Haptics.fail()
        }
        main.post { onEvent(EngineEvent.Finished(outcome, kept)) }
    }
}
