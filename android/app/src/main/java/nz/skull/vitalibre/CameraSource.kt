package nz.skull.vitalibre

import android.content.Context
import android.graphics.Color
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.Looper
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import nz.skull.vitalibre.core.PPGSample
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Rear camera with the torch on, reducing each frame to channel means over a central ROI. Exposure and
 * white balance lock once a fingertip has covered the lens for a second, and release when it is lifted.
 * The torch is checked twice a second and turned back on if the system switches it off.
 */
class CameraSource(private val context: Context) {
    private companion object {
        const val TOO_BRIGHT = 232.0
        const val TOO_DIM = 150.0
        const val WINDOW_SECONDS = 3.0   // frames used to judge the pulse
        const val MIN_SAMPLES = 60
        const val OFF_SETTLE = 10.0      // seconds of cover with the flash off before it is judged and the flash may be tried
        const val WEAK = 20.0            // pulse quality below this is "too flat"
        const val MARGIN = 5.0           // the flash must be at least this much worse to be switched off again
        const val TRIAL_COMPARE_AT = 4.0 // seconds the flash is tried before deciding (the last 3 s are compared)
        const val NO_COVER_AFTER = 10.0  // seconds with no finger recognised before the flash is tried
        const val DARK_SCENE = 12.0      // mean channel value below this with the flash off: nothing is lit at all
        const val DARK_HOLD = 1.0        // seconds of that darkness before the flash is switched on
    }

    /** The live image, hosted by the orb's dome. Created once so the preview surface survives screen changes. */
    val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
        setBackgroundColor(Color.BLACK)
    }

    /** Called for every frame on the camera thread, which runs at raised priority; never touches the UI. */
    @Volatile var onSample: ((PPGSample) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY); r.run() }, "vitalibre-camera")
    }
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    @Volatile private var wantTorch = false
    private var t0 = -1L
    private var locked = false
    private var coverStart = -1.0
    private var lastCovered = -10.0
    private var lastTorchCheck = -10.0
    private var frames = 0
    private var lastLog = 0.0
    private var evIndex = 0
    private var lastEv = -10.0
    private var stableSince = -1.0
    private var hotRed = 0.0

    /**
     * The flash starts OFF. It is tried once if the pulse is weak, and kept only if it is better:
     * OFF -> ON_TRIAL (compared after it settles) -> ON_KEPT, or back to OFF_KEPT. No further switching.
     */
    private enum class Light { OFF, ON_TRIAL, ON_KEPT, OFF_KEPT }
    private var light = Light.OFF
    private var startedAt = -1.0
    private var coverSince = -1.0
    private var trialAt = -1.0
    private var darkSince = -1.0
    private var qualityOff = 0.0
    private val times = ArrayList<Double>()     // recent frame times and green means while covered
    private val greens = ArrayList<Double>()
    private var quality = 0.0

    /** The flash state that worked on this device last time. Set before start(); null means discover it. */
    @Volatile var remembered: Boolean? = null

    /** The flash state this scan settled on: true on, false off, null while the flash trial is undecided. */
    val flashUsed: Boolean?
        get() = when (light) { Light.ON_KEPT -> true; Light.OFF, Light.OFF_KEPT -> false; Light.ON_TRIAL -> null }

    /** True while the flash trial is running, so the scan does not finish before the decision. */
    @Volatile var decisionPending = false
        private set
    private var lastQualityAt = -10.0

    fun start(owner: LifecycleOwner, done: (Result<Unit>) -> Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                            .build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                p.unbindAll()
                t0 = -1L; locked = false; coverStart = -1.0; lastCovered = -10.0; lastTorchCheck = -10.0
                evIndex = 0; lastEv = -10.0; stableSince = -1.0; light = Light.OFF; startedAt = -1.0; coverSince = -1.0; trialAt = -1.0; qualityOff = 0.0; times.clear(); greens.clear(); quality = 0.0; lastQualityAt = -10.0; decisionPending = false; darkSince = -1.0
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                // With no memory the flash starts OFF and is switched on only if the signal turns out too flat (see
                // regulateLight). A remembered state is used straight away and not re-tested.
                when (remembered) {
                    true -> { light = Light.ON_KEPT; wantTorch = true }
                    false -> { light = Light.OFF_KEPT; wantTorch = false }
                    null -> wantTorch = false
                }
                cam.cameraControl.enableTorch(wantTorch)
                cam.cameraControl.setExposureCompensationIndex(0)
                done(Result.success(Unit))
            } catch (e: Exception) {
                done(Result.failure(IllegalStateException("The camera could not start: ${e.message}")))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        onSample = null
        wantTorch = false
        decisionPending = false
        main.post {
            camera?.cameraControl?.enableTorch(false)
            setLock(false)
            provider?.unbindAll()
            camera = null
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun setLock(lock: Boolean) {
        val c = camera ?: return
        val opts = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, lock)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, lock)
            .build()
        Camera2CameraControl.from(c.cameraControl).setCaptureRequestOptions(opts)
        locked = lock
    }

    private fun manage(s: PPGSample) {
        // The flash must stay lit for the whole reading.
        if (wantTorch && s.t - lastTorchCheck >= 0.5) {
            lastTorchCheck = s.t
            val cam = camera
            if (cam != null && cam.cameraInfo.torchState.value != TorchState.ON) main.post { cam.cameraControl.enableTorch(true) }
        }
        if (s.covered) {
            lastCovered = s.t
        } else if (s.t - lastCovered > 0.5) {
            stableSince = -1.0
            if (locked) main.post { setLock(false) }
        }
        regulateLight(s)
        regulateExposure(s)
    }

    /**
     * The flash starts OFF on every device: room light through the fingertip often gives a cleaner, quicker
     * pulse than a flash held against the lens. Once the finger has covered the lens and settled, the pulse
     * quality is measured. If it is weak (or no finger is recognised within NO_COVER_AFTER seconds, e.g. a
     * dim room) the flash is tried; after it and the exposure have settled the quality is measured again,
     * and whichever lighting gave the stronger pulse is kept. Switching happens at most twice per scan.
     */
    private fun regulateLight(s: PPGSample) {
        val cam = camera ?: return
        if (startedAt < 0) startedAt = s.t
        if (s.covered) {
            if (coverSince < 0) coverSince = s.t
            times.add(s.t); greens.add(s.g)
            while (times.size > 1 && s.t - times[0] > WINDOW_SECONDS) { times.removeAt(0); greens.removeAt(0) }
        } else {
            coverSince = -1.0
            times.clear(); greens.clear()
        }
        // A scene with nothing lit has nothing to read: while the flash is off, a second of near-black means
        // the flash is needed now rather than after the settle window.
        if (light == Light.OFF || light == Light.OFF_KEPT) {
            if ((s.r + s.g + s.b) / 3 < DARK_SCENE) {
                if (darkSince < 0) darkSince = s.t
            } else {
                darkSince = -1.0
            }
        }
        val darkScene = darkSince >= 0 && s.t - darkSince >= DARK_HOLD
        // Flash off gets OFF_SETTLE seconds of cover to settle before it is judged. Once the flash is on the
        // person has settled already, so it is compared after a short TRIAL_COMPARE_AT trial.
        val settled = times.size >= MIN_SAMPLES && when (light) {
            Light.OFF -> coverSince >= 0 && s.t - coverSince >= OFF_SETTLE
            Light.ON_TRIAL -> s.t - trialAt >= TRIAL_COMPARE_AT
            else -> false
        }
        if (settled && s.t - lastQualityAt >= 0.5) { quality = pulseQuality(); lastQualityAt = s.t }
        when (light) {
            Light.OFF, Light.OFF_KEPT -> {
                // A remembered "flash off" is left alone unless the scene is dark enough that there is nothing
                // to see at all; the no-finger and flat-signal rules apply to a flash that was not remembered.
                val off = light == Light.OFF
                val neverCovered = off && s.t - startedAt > NO_COVER_AFTER && (lastCovered < 0 || s.t - lastCovered > NO_COVER_AFTER)
                val flat = off && settled && quality < WEAK
                if (neverCovered || flat || darkScene) {
                    qualityOff = if (settled) quality else 0.0
                    light = Light.ON_TRIAL; trialAt = s.t; wantTorch = true; stableSince = -1.0; coverSince = -1.0; decisionPending = true
                    times.clear(); greens.clear(); darkSince = -1.0
                    main.post { cam.cameraControl.enableTorch(true); setLock(false) }
                }
            }
            Light.ON_TRIAL -> {
                if (settled) {
                    decisionPending = false
                    if (quality + MARGIN < qualityOff) {
                        light = Light.OFF_KEPT; wantTorch = false; stableSince = -1.0
                        main.post { cam.cameraControl.enableTorch(false); setLock(false) }
                    } else {
                        light = Light.ON_KEPT
                    }
                }
            }
            else -> Unit
        }
    }

    /**
     * How strongly a pulse-rate rhythm (0.7-3 Hz) stands out from the noise: the strongest band power over
     * the median power of 3-10 Hz, on the green mean with its slow drift removed. Scale-free, so it can
     * compare two lightings. Roughly 1-6 for noise alone; hundreds or more for a clean pulse.
     */
    private fun pulseQuality(): Double {
        val n = times.size
        if (n < MIN_SAMPLES) return 0.0
        val fs = (n - 1) / (times[n - 1] - times[0])
        val half = maxOf(2, (fs * 0.5).toInt())
        val x = DoubleArray(n)
        for (i in 0 until n) {
            var m = 0.0
            var c = 0
            for (j in maxOf(0, i - half)..minOf(n - 1, i + half)) { m += greens[j]; c++ }
            x[i] = (greens[i] - m / c) * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)))
        }
        fun power(f: Double): Double {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val a = 2 * Math.PI * f * (times[i] - times[0])
                re += x[i] * Math.cos(a); im -= x[i] * Math.sin(a)
            }
            return re * re + im * im
        }
        var band = 0.0
        var f = 0.7
        while (f <= 3.0) { band = maxOf(band, power(f)); f += 0.1 }
        val noise = ArrayList<Double>()
        f = 3.0
        while (f <= minOf(10.0, fs / 2 - 0.5)) { noise.add(power(f)); f += 0.25 }
        if (noise.isEmpty()) return 0.0
        noise.sort()
        val med = noise[noise.size / 2]
        return if (med > 1e-9) band / med else 1e6
    }

    /**
     * Automatic exposure judges overall brightness, and a fingertip under the torch looks mid-grey
     * while the red channel is clipped, which flattens the pulse. So once the finger covers the lens,
     * exposure compensation is stepped down until red sits below clipping, then exposure is locked.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun regulateExposure(s: PPGSample) {
        val cam = camera ?: return
        if (!s.covered) { stableSince = -1.0; return }
        if (light == Light.OFF || light == Light.OFF_KEPT) return
        if (locked || s.t - lastEv < 0.35) return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) {
            if (stableSince < 0) stableSince = s.t
        } else {
            val range = state.exposureCompensationRange
            val step = state.exposureCompensationStep.toDouble().let { if (it > 0) it else 1.0 }
            val unit = maxOf(1, Math.round(0.5 / step).toInt())          // about half an EV per move
            val move = if (s.r > 245) unit * 2 else unit
            if (s.r > TOO_BRIGHT && evIndex > range.lower) {
                evIndex = maxOf(range.lower, evIndex - move); lastEv = s.t; stableSince = -1.0
                main.post { cam.cameraControl.setExposureCompensationIndex(evIndex) }
            } else if (s.r < TOO_DIM && evIndex < 0) {
                evIndex = minOf(0, evIndex + unit); lastEv = s.t; stableSince = -1.0
                main.post { cam.cameraControl.setExposureCompensationIndex(evIndex) }
            } else if (stableSince < 0) {
                stableSince = s.t
            }
        }
        if (stableSince >= 0 && s.t - stableSince > 1.0) main.post { setLock(true) }
    }

    private fun analyze(image: ImageProxy) {
        try {
            val ts = image.imageInfo.timestamp
            if (t0 < 0) t0 = ts
            val t = (ts - t0) / 1e9
            val plane = image.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = image.width
            val h = image.height
            val side = (min(w, h) * 0.4).toInt()
            val x0 = (w - side) / 2
            val y0 = (h - side) / 2
            var sumR = 0L; var sumG = 0L; var sumB = 0L; var hot = 0; var redHot = 0; var n = 0
            var y = y0
            while (y < y0 + side) {
                var x = x0
                while (x < x0 + side) {
                    val idx = y * rowStride + x * pixelStride
                    val r = buf.get(idx).toInt() and 0xFF
                    val g = buf.get(idx + 1).toInt() and 0xFF
                    sumR += r
                    sumG += g
                    sumB += buf.get(idx + 2).toInt() and 0xFF
                    // Clipping that matters is in green, the channel the pulse is read from. Red clips on any fingertip under the torch.
                    if (g >= 250) hot++
                    if (r >= 250) redHot++
                    n++
                    x += 2
                }
                y += 2
            }
            if (n == 0) return
            val s = PPGSample(t, sumR.toDouble() / n, sumG.toDouble() / n, sumB.toDouble() / n, hot.toDouble() / n)
            hotRed = redHot.toDouble() / n
            android.util.Log.v("VLraw", "%.4f,%.3f,%.3f,%.3f".format(t, s.r, s.g, s.b)) // TEMP: raw channel means for offline analysis
            manage(s)
            // TEMP diagnostics: once a second, what the camera sees and what the cover check decides.
            frames++
            if (t - lastLog >= 1.0) {
                android.util.Log.d("VLcam", "t=%.1f fps=%.1f r=%.0f g=%.0f b=%.0f redClip=%.2f greenClip=%.2f covered=%b ev=%d locked=%b light=%s q=%.0f qOff=%.0f torch=%s".format(
                    t, frames / (t - lastLog), s.r, s.g, s.b, hotRed, s.saturated, s.covered, evIndex, locked, light, quality, qualityOff, camera?.cameraInfo?.torchState?.value))
                lastLog = t; frames = 0
            }
            onSample?.invoke(s)
        } finally {
            image.close()
        }
    }
}
