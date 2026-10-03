package nz.skull.vitalibre

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nz.skull.vitalibre.core.BPRange
import nz.skull.vitalibre.core.QualityLevel
import nz.skull.vitalibre.core.ReadingNote
import nz.skull.vitalibre.core.Rhythm
import nz.skull.vitalibre.core.ScanResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class Reading(
    val id: String = UUID.randomUUID().toString(),
    val epochMillis: Long,
    val heartRate: Double,
    val bp: BPRange,
    val quality: Double,
    val level: QualityLevel,
    val duration: Double,
    val modelVersion: String,
    val starred: Boolean = false,
    /** The scan's filtered waveform, normalised -1..1 at the analysis rate, rounded to three decimals — the graph the reading keeps. Absent in a file written before graphs were kept, and then the reading simply has no graph. */
    val trace: List<Double>? = null,
    /** How evenly the beats came. Absent in a file written before the rhythm was kept, and `steady` is the honest reading of one: every reading in such a file passed the stability test that was in place then. */
    val rhythm: Rhythm? = null,
    /** Kept, i.e. listed in Readings. A reading taken today and not kept lives in the day's log on Measure only. */
    val saved: Boolean = true,
    /** The scan this reading came from, which is what stops one scan being filed twice. Absent in a file written before the log existed. */
    val scanId: String? = null,
) {
    /** The one note a reading can carry, or nothing. Marked, not refused: the reading is the user's either way. */
    val note: String?
        get() = if (level == QualityLevel.POOR || rhythm == Rhythm.IRREGULAR) ReadingNote.LOW_QUALITY_OR_ARRHYTHMIA else null
}

/**
 * The user's own readings, one JSON file in app-private storage. Never read by anything but this app, never transmitted.
 *
 * The file holds two kinds of reading: the ones the user kept, which Readings lists, and the ones taken today but not
 * kept, which only the session fold on Measure shows. A finished scan is filed straight away, so a reading is never
 * lost by not saving it. A file written before the log existed has no `saved` field, and nothing in it was ever
 * dropped, so a missing field reads as kept.
 */
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
                    id = o.getString("id"), epochMillis = o.getLong("t"), heartRate = o.getDouble("hr"),
                    bp = BPRange(o.getInt("sl"), o.getInt("sh"), o.getInt("dl"), o.getInt("dh"), o.getInt("s"), o.getInt("d"), o.optBoolean("cal", false)),
                    quality = o.getDouble("q"), level = QualityLevel.valueOf(o.getString("level")), duration = o.getDouble("dur"),
                    modelVersion = o.getString("model"), starred = o.optBoolean("star", false), saved = o.optBoolean("saved", true),
                    scanId = o.optString("scan").ifBlank { null },
                    trace = o.optJSONArray("trace")?.let { a -> (0 until a.length()).map { a.getDouble(it) } },
                    rhythm = o.optString("rhythm").ifBlank { null }?.let { Rhythm.valueOf(it) },
                )
            }.sortedByDescending { it.epochMillis }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save() {
        // The day is over for anything from an earlier one that was not kept.
        readings = readings.filter { it.saved || isToday(it.epochMillis) }
        val arr = JSONArray()
        for (r in readings) {
            val o = JSONObject().put("id", r.id).put("t", r.epochMillis).put("hr", r.heartRate)
                .put("sl", r.bp.systolicLow).put("sh", r.bp.systolicHigh)
                .put("dl", r.bp.diastolicLow).put("dh", r.bp.diastolicHigh)
                .put("s", r.bp.systolic).put("d", r.bp.diastolic).put("cal", r.bp.calibrated)
                .put("q", r.quality).put("level", r.level.name).put("dur", r.duration).put("model", r.modelVersion)
                .put("star", r.starred).put("saved", r.saved)
            // Written only when the reading has one, so a file written before the day's log existed
            // still reads on a build that has it and the other way round.
            r.scanId?.let { o.put("scan", it) }
            r.trace?.let { t -> o.put("trace", JSONArray().apply { t.forEach { v -> put(v) } }) }
            r.rhythm?.let { o.put("rhythm", it.name) }
            arr.put(o)
        }
        val tmp = File(file.parentFile, "readings.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun isToday(ms: Long): Boolean {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ms }
        return now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    }

    /** What Readings lists: the readings the user kept. */
    val savedReadings: List<Reading> get() = readings.filter { it.saved }

    /** Today's log, kept or not, newest first. */
    val todaysReadings: List<Reading> get() = readings.filter { isToday(it.epochMillis) }

    fun reading(id: String?): Reading? = readings.firstOrNull { it.id == id }

    /** The reading filed for a scan, if one has been. */
    fun readingForScan(scanId: String?): Reading? = scanId?.let { id -> readings.firstOrNull { it.scanId == id } }

    /**
     * Files a finished scan in today's log, not kept, so nothing is lost by not saving it.
     *
     * Keyed by the scan, which is what makes it safe to call again: a return to Measure, a Save, or a
     * recalibration lands on the one reading that scan already has and refreshes its estimate, instead
     * of adding a second copy.
     */
    fun file(result: ScanResult, scanId: String): Reading {
        val existing = readingForScan(scanId)
        if (existing != null) {
            val refreshed = existing.copy(
                heartRate = result.heartRate, bp = result.bp, quality = result.quality, level = result.level,
                rhythm = result.rhythm,
            )
            readings = readings.map { if (it.id == existing.id) refreshed else it }
            save()
            return refreshed
        }
        val r = Reading(
            epochMillis = System.currentTimeMillis(), heartRate = result.heartRate, bp = result.bp, quality = result.quality,
            level = result.level, duration = result.duration, modelVersion = result.modelVersion, saved = false, scanId = scanId,
            trace = stored(result.trace), rhythm = result.rhythm,
        )
        readings = listOf(r) + readings
        save()
        return r
    }

    /**
     * The graph as it is kept: three decimals, which is finer than a phone screen can show and about
     * half the JSON of the raw doubles (a 15 s run is some 6 KB rather than 12). The rate is not
     * reduced: the expanded view's zoom has to show what arrived.
     */
    private fun stored(trace: DoubleArray): List<Double> = trace.map { Math.round(it * 1000) / 1000.0 }

    fun setSaved(id: String?, saved: Boolean) {
        if (id == null) return
        readings = readings.map { if (it.id == id) it.copy(saved = saved) else it }
        save()
    }

    fun toggleSaved(id: String) {
        val r = reading(id) ?: return
        setSaved(id, !r.saved)
    }

    fun toggleStar(id: String) {
        readings = readings.map { if (it.id == id) it.copy(starred = !it.starred) else it }
        save()
    }

    fun delete(id: String) {
        readings = readings.filter { it.id != id }
        save()
    }

    /** The kept readings, oldest first, as plain text one reading per line. */
    fun exportText(): String {
        val kept = savedReadings.sortedBy { it.epochMillis }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        val lines = mutableListOf(
            "VitaLibre readings (${kept.size})",
            "Heart rate in bpm; blood pressure in mmHg. Values are estimates. Not a medical device.",
            "",
        )
        for (r in kept) {
            // The note travels with the reading: a shared line that dropped it would be quieter than
            // the app, and the caveat is the part that matters.
            val line = buildString {
                append("${stamp.format(Date(r.epochMillis))}  HR ${Math.round(r.heartRate)}  BP ${r.bp.text}  quality ${r.level.label}")
                r.note?.let { append("  $it") }
                if (r.starred) append("  starred")
            }
            lines.add(line)
        }
        return lines.joinToString("\n")
    }
}
