package nz.skull.vitalibre

import android.os.Handler
import android.os.Looper
import nz.skull.vitalibre.core.PPGSample
import kotlin.math.exp
import kotlin.random.Random

/**
 * A synthetic fingertip, only started by the `simulate` launch extra, for exercising the whole scan
 * path (engine, orb, result) without a finger. The orb says "simulated" while it runs, so it cannot
 * be mistaken for a reading.
 */
class SimulatedSource(private val onSample: (PPGSample) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var frame = 0
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val t = frame / 30.0
            frame++
            val phase = (t * BPM / 60) % 1.0
            val pulse = exp(-(((phase - 0.18) / 0.07).let { it * it })) + 0.35 * exp(-(((phase - 0.45) / 0.09).let { it * it }))
            onSample(PPGSample(t, 210.0, 60 - 1.2 * pulse + Random.nextDouble(-0.08, 0.08), 20.0, 0.1))
            handler.postDelayed(this, 33)
        }
    }

    fun start() { frame = 0; running = true; handler.post(tick) }
    fun stop() { running = false; handler.removeCallbacks(tick) }

    companion object { const val BPM = 72.0 }
}
