package nz.skull.vitalibre

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nz.skull.vitalibre.core.BPCalibration
import nz.skull.vitalibre.core.CalibrationPoint
import nz.skull.vitalibre.core.Sex
import nz.skull.vitalibre.core.UsualBP
import org.json.JSONArray
import org.json.JSONObject

/** User preferences, on the device only. */
class Prefs(context: Context) {
    companion object {
        const val DEFAULT_SYSTOLIC = 120
        const val DEFAULT_DIASTOLIC = 80
    }

    private val sp = context.getSharedPreferences("vitalibre", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(ThemeMode.entries.firstOrNull { it.name == sp.getString("themeMode", "DARK") } ?: ThemeMode.DARK)
        private set
    /** 0 = not given. */
    var age by mutableStateOf(sp.getInt("age", 0))
        private set
    var sex by mutableStateOf(Sex.entries.firstOrNull { it.name == sp.getString("sex", "UNSPECIFIED") } ?: Sex.UNSPECIFIED)
        private set
    /** Usual resting blood pressure from a medical record; 0 = not set. */
    var usualSystolic by mutableStateOf(sp.getInt("usualSystolic", 0).takeIf { it > 0 } ?: DEFAULT_SYSTOLIC)
        private set
    var usualDiastolic by mutableStateOf(sp.getInt("usualDiastolic", 0).takeIf { it > 0 } ?: DEFAULT_DIASTOLIC)
        private set
    /**
     * The pickers start on 120/80, near the average, but the value only counts once the person has confirmed
     * it with Done, so a default is never mistaken for a measurement. A value saved before confirmation
     * existed was chosen by the person.
     */
    var usualConfirmed by mutableStateOf(
        if (sp.contains("usualConfirmed")) sp.getBoolean("usualConfirmed", false)
        else sp.getInt("usualSystolic", 0) > 0 && sp.getInt("usualDiastolic", 0) > 0,
    )
        private set
    /** Epoch millis when the first-load screen was confirmed; 0 = show it. */
    var onboardedAt by mutableStateOf(sp.getLong("onboardedAt", 0L))
        private set
    var calibration by mutableStateOf(readCalibration())
        private set
    /** The flash state of the last good reading on this device: true = on, false = off, null = not known yet. */
    var workingFlash: Boolean? = sp.getInt("workingFlash", -1).let { if (it < 0) null else it == 1 }
        private set

    val usual: UsualBP?
        get() {
            val u = UsualBP(usualSystolic, usualDiastolic)
            return if (usualConfirmed && u.isPlausible) u else null
        }

    fun changeTheme(v: ThemeMode) { themeMode = v; sp.edit().putString("themeMode", v.name).apply() }
    fun changeAge(v: Int) { age = v; sp.edit().putInt("age", v).apply() }
    fun changeSex(v: Sex) { sex = v; sp.edit().putString("sex", v.name).apply() }
    fun changeUsualSystolic(v: Int) { usualSystolic = v; sp.edit().putInt("usualSystolic", v).apply() }
    fun changeUsualDiastolic(v: Int) { usualDiastolic = v; sp.edit().putInt("usualDiastolic", v).apply() }
    fun rememberFlash(on: Boolean?) {
        workingFlash = on
        sp.edit().putInt("workingFlash", when (on) { null -> -1; true -> 1; false -> 0 }).apply()
    }
    fun confirmUsual() { usualConfirmed = true; sp.edit().putBoolean("usualConfirmed", true).apply() }

    /** Clears the cuff calibrations and the confirmed typical pressure; the pickers go back to 120/80. */
    fun resetAllCalibration() {
        resetCalibration()
        usualConfirmed = false; usualSystolic = DEFAULT_SYSTOLIC; usualDiastolic = DEFAULT_DIASTOLIC
        sp.edit().putBoolean("usualConfirmed", false).putInt("usualSystolic", DEFAULT_SYSTOLIC).putInt("usualDiastolic", DEFAULT_DIASTOLIC).apply()
    }

    fun setOnboarded(done: Boolean) {
        onboardedAt = if (done) System.currentTimeMillis() else 0L
        sp.edit().putLong("onboardedAt", onboardedAt).apply()
    }

    fun addCalibration(point: CalibrationPoint) = writeCalibration(calibration.with(point))
    fun resetCalibration() = writeCalibration(BPCalibration())

    private fun writeCalibration(c: BPCalibration) {
        calibration = c
        val arr = JSONArray()
        for (p in c.points) {
            arr.put(JSONObject().put("rs", p.rawSystolic).put("rd", p.rawDiastolic).put("cs", p.cuffSystolic)
                .put("cd", p.cuffDiastolic).put("t", p.epochSeconds).put("device", p.device ?: JSONObject.NULL)
                .put("bs", p.baseSystolic ?: JSONObject.NULL).put("bd", p.baseDiastolic ?: JSONObject.NULL))
        }
        sp.edit().putString("bpCalibration", arr.toString()).apply()
    }

    private fun readCalibration(): BPCalibration {
        val raw = sp.getString("bpCalibration", null) ?: return BPCalibration()
        return try {
            val arr = JSONArray(raw)
            BPCalibration((0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                CalibrationPoint(o.getDouble("rs"), o.getDouble("rd"), o.getDouble("cs"), o.getDouble("cd"), o.getDouble("t"),
                    if (o.isNull("device")) null else o.getString("device"),
                    if (!o.has("bs") || o.isNull("bs")) null else o.getDouble("bs"),
                    if (!o.has("bd") || o.isNull("bd")) null else o.getDouble("bd"))
            })
        } catch (e: Exception) {
            BPCalibration()
        }
    }
}
