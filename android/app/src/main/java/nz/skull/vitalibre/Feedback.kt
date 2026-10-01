package nz.skull.vitalibre

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The dashboard's UX sounds, bundled as copies and keyed by their dashboard names. Dark theme's
 * selections: unlockDial light-mechanical-click, dialClick and lockDial soft-mechanical-click,
 * reminderConfirm chime-motion-tracker.
 */
object Sounds {
    const val START = "light-mechanical-click"
    const val CLICK = "soft-mechanical-click"
    const val DONE = "chime-motion-tracker"

    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()

    fun init(context: Context) {
        val sp = SoundPool.Builder().setMaxStreams(3)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
        for (name in listOf(START, CLICK, DONE)) {
            try {
                context.assets.openFd("Sounds/$name.mp3").use { ids[name] = sp.load(it, 1) }
            } catch (e: Exception) {
                // A missing sound is silent rather than fatal.
            }
        }
        pool = sp
    }

    fun play(name: String, volume: Float = 0.6f) {
        val id = ids[name] ?: return
        pool?.play(id, volume, volume, 1, 0, 1f)
    }
}

/** Subtle haptics for a scan: a light tap to begin, a softer tap on each beat, and a more defined pair at the end. */
object Haptics {
    private var vibrator: Vibrator? = null

    fun init(context: Context) {
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun shot(ms: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val amp = if (v.hasAmplitudeControl()) amplitude else VibrationEffect.DEFAULT_AMPLITUDE
        v.vibrate(VibrationEffect.createOneShot(ms, amp))
    }

    fun start() = shot(18, 70)
    fun beat() = shot(10, 35)
    fun end() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 70, 45), intArrayOf(0, 160, 0, 220), -1))
    }
    fun fail() = shot(60, 120)
    fun click() = shot(8, 45)
}
