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
    private val sp = context.getSharedPreferences("vitalibre", Context.MODE_PRIVATE)

    var themeMode by mutableStateOf(ThemeMode.entries.firstOrNull { it.name == sp.getString("themeMode", "DARK") } ?: ThemeMode.DARK)
        private set
    /** 0 = not given. */
    var age by mutableStateOf(sp.getInt("age", 0))
        private set
    var sex by mutableStateOf(Sex.entries.firstOrNull { it.name == sp.getString("sex", "UNSPECIFIED") } ?: Sex.UNSPECIFIED)
        private set
    /** Usual resting blood pressure from a medical record; 0 = not set. */
    var usualSystolic by mutableStateOf(sp.getInt("usualSystolic", 0))
        private set
    var usualDiastolic by mutableStateOf(sp.getInt("usualDiastolic", 0))
        private set
    /** Epoch millis when the first-load screen was confirmed; 0 = show it. */
    var onboardedAt by mutableStateOf(sp.getLong("onboardedAt", 0L))
        private set
    var calibration by mutableStateOf(readCalibration())
        private set

    val usual: UsualBP?
        get() {
            val u = UsualBP(usualSystolic, usualDiastolic)
            return if (usualSystolic > 0 && usualDiastolic > 0 && u.isPlausible) u else null
        }

    fun changeTheme(v: ThemeMode) { themeMode = v; sp.edit().putString("themeMode", v.name).apply() }
    fun changeAge(v: Int) { age = v; sp.edit().putInt("age", v).apply() }
    fun changeSex(v: Sex) { sex = v; sp.edit().putString("sex", v.name).apply() }
    fun changeUsualSystolic(v: Int) { usualSystolic = v; sp.edit().putInt("usualSystolic", v).apply() }
    fun changeUsualDiastolic(v: Int) { usualDiastolic = v; sp.edit().putInt("usualDiastolic", v).apply() }
    fun setOnboarded(done: Boolean) {
        onboardedAt = if (done) System.currentTimeMillis() else 0L
        sp.edit().putLong("onboardedAt", onboardedAt).apply()
    }

    fun addCalibration(point: CalibrationPoint) = writeCalibration(calibration.with(point))
    fun resetCalibration() = writeCalibration(BPCalibration())
    fun removeCalibration(index: Int) = writeCalibration(BPCalibration(calibration.points.filterIndexed { i, _ -> i != index }))

    private fun writeCalibration(c: BPCalibration) {
        calibration = c
        val arr = JSONArray()
        for (p in c.points) {
            arr.put(JSONObject().put("rs", p.rawSystolic).put("rd", p.rawDiastolic).put("cs", p.cuffSystolic)
                .put("cd", p.cuffDiastolic).put("t", p.epochSeconds).put("device", p.device ?: JSONObject.NULL))
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
                    if (o.isNull("device")) null else o.getString("device"))
            })
        } catch (e: Exception) {
            BPCalibration()
        }
    }
}
