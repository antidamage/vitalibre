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
                val cam = p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                if (!cam.cameraInfo.hasFlashUnit()) {
                    p.unbindAll()
                    done(Result.failure(IllegalStateException("This device has no flash, so a reading can't be taken.")))
                    return@addListener
                }
                wantTorch = true
                cam.cameraControl.enableTorch(true)
                done(Result.success(Unit))
            } catch (e: Exception) {
                done(Result.failure(IllegalStateException("The camera could not start: ${e.message}")))
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        onSample = null
        wantTorch = false
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
            if (coverStart < 0) coverStart = s.t
            if (!locked && s.t - coverStart > 1.0) main.post { setLock(true) }
        } else if (s.t - lastCovered > 0.5) {
            coverStart = -1.0
            if (locked) main.post { setLock(false) }
        }
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
            var sumR = 0L; var sumG = 0L; var sumB = 0L; var hot = 0; var n = 0
            var y = y0
            while (y < y0 + side) {
                var x = x0
                while (x < x0 + side) {
                    val idx = y * rowStride + x * pixelStride
                    val r = buf.get(idx).toInt() and 0xFF
                    sumR += r
                    sumG += buf.get(idx + 1).toInt() and 0xFF
                    sumB += buf.get(idx + 2).toInt() and 0xFF
                    if (r >= 250) hot++
                    n++
                    x += 2
                }
                y += 2
            }
            if (n == 0) return
            val s = PPGSample(t, sumR.toDouble() / n, sumG.toDouble() / n, sumB.toDouble() / n, hot.toDouble() / n)
            manage(s)
            onSample?.invoke(s)
        } finally {
            image.close()
        }
    }
}
