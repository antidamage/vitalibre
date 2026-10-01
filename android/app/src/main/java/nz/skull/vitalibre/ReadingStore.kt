package nz.skull.vitalibre

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nz.skull.vitalibre.core.BPRange
import nz.skull.vitalibre.core.QualityLevel
import nz.skull.vitalibre.core.ScanResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class Reading(
    val id: String = UUID.randomUUID().toString(),
    val epochMillis: Long,
    val heartRate: Double,
    val bp: BPRange,
    val quality: Double,
    val level: QualityLevel,
    val duration: Double,
    val modelVersion: String,
    val starred: Boolean = false,
)

/** The user's own readings, one JSON file in app-private storage. Never read by anything but this app, never transmitted. */
class ReadingStore(context: Context) {
    private val file = File(context.filesDir, "readings.json")

    var readings by mutableStateOf(load())
        private set

    private fun load(): List<Reading> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Reading(
                    o.getString("id"), o.getLong("t"), o.getDouble("hr"),
                    BPRange(o.getInt("sl"), o.getInt("sh"), o.getInt("dl"), o.getInt("dh"), o.getInt("s"), o.getInt("d"), o.optBoolean("cal", false)),
                    o.getDouble("q"), QualityLevel.valueOf(o.getString("level")), o.getDouble("dur"), o.getString("model"), o.optBoolean("star", false),
                )
            }.sortedByDescending { it.epochMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (r in readings) {
            arr.put(
                JSONObject().put("id", r.id).put("t", r.epochMillis).put("hr", r.heartRate)
                    .put("sl", r.bp.systolicLow).put("sh", r.bp.systolicHigh).put("dl", r.bp.diastolicLow).put("dh", r.bp.diastolicHigh)
                    .put("s", r.bp.systolic).put("d", r.bp.diastolic).put("cal", r.bp.calibrated)
                    .put("q", r.quality).put("level", r.level.name).put("dur", r.duration).put("model", r.modelVersion).put("star", r.starred),
            )
        }
        val tmp = File(file.parentFile, "readings.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    fun add(result: ScanResult): Reading {
        val r = Reading(epochMillis = System.currentTimeMillis(), heartRate = result.heartRate, bp = result.bp, quality = result.quality,
            level = result.level, duration = result.duration, modelVersion = result.modelVersion)
        readings = listOf(r) + readings
        save()
        return r
    }

    fun toggleStar(id: String) {
        readings = readings.map {
            if (it.id == id) Reading(it.id, it.epochMillis, it.heartRate, it.bp, it.quality, it.level, it.duration, it.modelVersion, !it.starred) else it
        }
        save()
    }

    fun delete(id: String) {
        readings = readings.filter { it.id != id }
        save()
    }

    /** Every reading, oldest first, as plain text one reading per line. */
    fun exportText(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val lines = mutableListOf(
            "VitaLibre readings (${readings.size})",
            "Heart rate in bpm; blood pressure in mmHg. Values are estimates. Not a medical device.",
            "",
        )
        for (r in readings.sortedBy { it.epochMillis }) {
            lines.add("${stamp.format(Date(r.epochMillis))}  HR ${Math.round(r.heartRate)}  BP ${r.bp.text}  quality ${r.level.label}${if (r.starred) "  starred" else ""}")
        }
        return lines.joinToString("\n")
    }
}
