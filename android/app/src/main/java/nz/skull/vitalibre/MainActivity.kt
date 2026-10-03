package nz.skull.vitalibre

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.geometry.Rect
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var env: AppEnv
    lateinit var donationBilling: DonationBilling
        private set
    private var onGranted: (() -> Unit)? = null
    private var onDenied: (() -> Unit)? = null

    /** Debug builds only: the `simulate` launch extra exercises the scan path without a finger. */
    var simulate = false
        private set

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onGranted?.invoke() else onDenied?.invoke()
        onGranted = null; onDenied = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        simulate = BuildConfig.SIMULATION && intent?.getBooleanExtra("simulate", false) == true
        Fonts.init(assets)
        Publisher.init(this)
        donationBilling = DonationBilling(this)
        donationBilling.start()
        Sounds.init(this)
        Haptics.init(this)
        env = AppEnv(this, Prefs(this), ReadingStore(this), Measurer(this))
        setContent { RootApp(env) }
    }

    override fun onStop() {
        super.onStop()
        if (::env.isInitialized && env.measurer.isBusy) env.measurer.cancel()
    }

    override fun onResume() {
        super.onResume()
        if (::donationBilling.isInitialized) donationBilling.queryOutstanding()
    }

    override fun onDestroy() {
        if (::donationBilling.isInitialized) donationBilling.close()
        super.onDestroy()
    }

    fun ensureCamera(onGranted: () -> Unit, onDenied: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            onGranted()
        } else {
            this.onGranted = onGranted; this.onDenied = onDenied
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, null))
    }

    /** A cropped capture of what is on screen inside the orb's frame: no buttons, nothing else. */
    fun shareOrb(bounds: Rect) {
        val src = android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
        if (src.width() <= 10 || src.height() <= 10) return
        val bitmap = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
        PixelCopy.request(window, src, bitmap, { result ->
            if (result == PixelCopy.SUCCESS) shareBitmap(bitmap)
        }, Handler(Looper.getMainLooper()))
    }

    private fun shareBitmap(bitmap: Bitmap) {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "vitalibre-reading.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, null))
    }
}
