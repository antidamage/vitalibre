package nz.skull.vitalibre

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import nz.skull.vitalibre.core.MotionSample

/**
 * The phone's own movement while a scan runs: rotation rate (rad/s) and linear acceleration (m/s^2, gravity
 * removed by the platform), so the feed-quality index can tell a shaking hand from a fault in the picture.
 * Both sensors run at the fastest rate; a combined sample is emitted with every gyroscope event, carrying
 * the latest acceleration, which is 100 Hz or better on current phones (4-12 Hz tremor needs at least 25 Hz).
 * Sensor events are stamped on the SystemClock.elapsedRealtimeNanos clock; [cameraEpochNanos] is the camera's
 * clock zero on that same clock (CameraSource.clockEpochNanos). Samples that arrive before it is known are
 * held (up to a few seconds) and sent once it is, so the first seconds of the scan are not lost.
 * No permission is needed for either sensor at this rate on a phone; if one is missing there is no motion
 * and the index simply leaves the motion factors out.
 */
class MotionSource(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private var thread: HandlerThread? = null

    /** Called on the sensor thread for each sample, with `t` on the camera's clock. */
    @Volatile var onSample: ((MotionSample) -> Unit)? = null
    @Volatile var cameraEpochNanos: (() -> Long?)? = null

    private class Raw(val nanos: Long, val g: FloatArray, val a: FloatArray)
    private val pending = ArrayList<Raw>()
    private val latestAccel = FloatArray(3)
    private var haveAccel = false

    val isAvailable get() = gyro != null

    fun start() {
        if (gyro == null) return
        pending.clear(); haveAccel = false
        val th = HandlerThread("vitalibre-motion").also { it.start() }
        thread = th
        val h = Handler(th.looper)
        manager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST, h)
        if (accel != null) manager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST, h)
    }

    fun stop() {
        manager.unregisterListener(this)
        onSample = null
        thread?.quitSafely(); thread = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            latestAccel[0] = e.values[0]; latestAccel[1] = e.values[1]; latestAccel[2] = e.values[2]; haveAccel = true
            return
        }
        if (e.sensor.type != Sensor.TYPE_GYROSCOPE) return
        val raw = Raw(e.timestamp, e.values.copyOf(3), if (haveAccel) latestAccel.copyOf() else FloatArray(3))
        val epoch = cameraEpochNanos?.invoke()
        if (epoch == null) {
            pending.add(raw)
            if (pending.size > 1500) pending.removeAt(0)   // about 5 s at 300 Hz; older than any scan needs
            return
        }
        if (pending.isNotEmpty()) { pending.forEach { emit(it, epoch) }; pending.clear() }
        emit(raw, epoch)
    }

    private fun emit(r: Raw, epoch: Long) {
        val t = (r.nanos - epoch) / 1e9
        if (t < 0) return
        onSample?.invoke(MotionSample(t, r.g[0].toDouble(), r.g[1].toDouble(), r.g[2].toDouble(),
            r.a[0].toDouble(), r.a[1].toDouble(), r.a[2].toDouble()))
    }
}
