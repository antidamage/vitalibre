package nz.skull.vitalibre

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import nz.skull.vitalibre.core.BPCalibration
import nz.skull.vitalibre.core.BPDisplay
import nz.skull.vitalibre.core.BPRange
import nz.skull.vitalibre.core.ExcludedRange
import nz.skull.vitalibre.core.ExportReading
import nz.skull.vitalibre.core.ReadingEdit
import nz.skull.vitalibre.core.ValidationExport
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

/** The numbers for a reading with stretches left out, held beside the scan's own so clearing gives the original back. */
data class EditedResult(
    val heartRate: Double, val rhythm: Rhythm, val bp: BPRange,
    val rawSystolic: Double, val rawDiastolic: Double, val usedSeconds: Double,
)

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
    /** The model's value before calibration, kept so a cuff reading can be paired with this scan. */
    val rawSystolic: Double? = null,
    val rawDiastolic: Double? = null,
    /** Whether the figure was shown when the scan was taken. Android files true; absent in an older file. */
    val bpShown: Boolean? = null,
    /** Run seconds of the first trace sample, so [feedQuality] and [excluded] line up with the graph. */
    val traceStart: Double? = null,
    /** Feed quality once a second from the second after cover, two decimals, with the cause of each. */
    val feedQuality: List<Double>? = null,
    val feedCauses: List<String>? = null,
    /** Stretches the user has left out, and the numbers without them. */
    val excluded: List<ExcludedRange>? = null,
    val edited: EditedResult? = null,
) {
    val displayHeartRate: Double get() = edited?.heartRate ?: heartRate
    val displayRhythm: Rhythm? get() = edited?.rhythm ?: rhythm
    val displayBP: BPRange get() = edited?.bp ?: bp
    val isIrregular: Boolean get() = displayRhythm == Rhythm.IRREGULAR
    /** Seconds of the run in use, when stretches are left out. */
    val usedSeconds: Double get() = edited?.usedSeconds ?: duration
    val feedMean: Double? get() = feedQuality?.takeIf { it.isNotEmpty() }?.average()

    /** Android shows a figure unless the pulse is irregular (no calibration gate). */
    val bpHidden get() = BPDisplay.hidden(displayRhythm, BPCalibration(), BPDisplay.Platform.ANDROID, "calibrated", epochMillis / 1000.0)

    /** The one note a reading can carry, or nothing. Marked, not refused: the reading is the user's either way. */
    val note: String? get() = ReadingNote.note(feedMean, displayRhythm, level)
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
                    rawSystolic = o.optDouble("rs", Double.NaN).takeIf { !it.isNaN() },
                    rawDiastolic = o.optDouble("rd", Double.NaN).takeIf { !it.isNaN() },
                    bpShown = if (o.has("bps")) o.getBoolean("bps") else null,
                    traceStart = o.optDouble("ts", Double.NaN).takeIf { !it.isNaN() },
                    feedQuality = o.optJSONArray("fq")?.let { a -> (0 until a.length()).map { a.getDouble(it) } },
                    feedCauses = o.optJSONArray("fc")?.let { a -> (0 until a.length()).map { a.getString(it) } },
                    excluded = o.optJSONArray("ex")?.let { a -> (0 until a.length()).map { val p = a.getJSONArray(it); ExcludedRange(p.getDouble(0), p.getDouble(1)) } },
                    edited = o.optJSONObject("ed")?.let { e ->
                        EditedResult(
                            e.getDouble("hr"), Rhythm.valueOf(e.getString("rhythm")),
                            BPRange(e.getInt("sl"), e.getInt("sh"), e.getInt("dl"), e.getInt("dh"), e.getInt("s"), e.getInt("d"), e.optBoolean("cal", false)),
                            e.getDouble("rs"), e.getDouble("rd"), e.getDouble("used"),
                        )
                    },
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
            r.rawSystolic?.let { o.put("rs", it) }
            r.rawDiastolic?.let { o.put("rd", it) }
            r.bpShown?.let { o.put("bps", it) }
            r.traceStart?.let { o.put("ts", it) }
            r.feedQuality?.let { f -> o.put("fq", JSONArray().apply { f.forEach { v -> put(v) } }) }
            r.feedCauses?.let { f -> o.put("fc", JSONArray().apply { f.forEach { v -> put(v) } }) }
            r.excluded?.let { ex -> o.put("ex", JSONArray().apply { ex.forEach { x -> put(JSONArray().put(x.start).put(x.end)) } }) }
            r.edited?.let { e ->
                o.put("ed", JSONObject().put("hr", e.heartRate).put("rhythm", e.rhythm.name)
                    .put("sl", e.bp.systolicLow).put("sh", e.bp.systolicHigh).put("dl", e.bp.diastolicLow).put("dh", e.bp.diastolicHigh)
                    .put("s", e.bp.systolic).put("d", e.bp.diastolic).put("cal", e.bp.calibrated)
                    .put("rs", e.rawSystolic).put("rd", e.rawDiastolic).put("used", e.usedSeconds))
            }
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
                // A recalibration re-derives the figure; the shown flag is never taken back.
                bpShown = true,
            )
            readings = readings.map { if (it.id == existing.id) refreshed else it }
            save()
            return refreshed
        }
        val r = Reading(
            epochMillis = System.currentTimeMillis(), heartRate = result.heartRate, bp = result.bp, quality = result.quality,
            level = result.level, duration = result.duration, modelVersion = result.modelVersion, saved = false, scanId = scanId,
            trace = stored(result.trace), rhythm = result.rhythm,
            rawSystolic = result.rawSystolic, rawDiastolic = result.rawDiastolic, bpShown = true,
            traceStart = result.traceStart,
            feedQuality = result.feed.map { Math.round(it.value * 100) / 100.0 },
            feedCauses = result.feed.map { it.cause.raw },
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

    /**
     * Records the user's exclusions and the numbers recomputed without them. Empty ranges, or no outcome, clear
     * both, which gives the scan's own numbers back.
     */
    fun setExclusions(id: String, ranges: List<ExcludedRange>, outcome: ReadingEdit.Outcome?) {
        readings = readings.map {
            if (it.id != id) it
            else if (ranges.isEmpty() || outcome == null) it.copy(excluded = null, edited = null)
            else it.copy(
                excluded = ranges,
                edited = EditedResult(outcome.heartRate, outcome.rhythm, outcome.bp, outcome.rawSystolic, outcome.rawDiastolic, outcome.usedSeconds),
            )
        }
        save()
    }

    private fun scans() = readings.map { ReadingNote.Scan(it.epochMillis, it.isIrregular) }

    /** Whether this is the second irregular pulse among the last three scans within 30 minutes. */
    fun irregularRepeated(r: Reading) = ReadingNote.irregularRepeated(ReadingNote.Scan(r.epochMillis, r.isIrregular), scans())

    /** The note as shown, with the escalation applied. */
    fun noteText(r: Reading): String? = ReadingNote.text(r.note, r.note == ReadingNote.IRREGULAR && irregularRepeated(r))

    /** The kept readings, oldest first, as plain text one reading per line. */
    fun exportText(showBP: (Reading) -> Boolean = { it.bpHidden == null }): String {
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
                append("${stamp.format(Date(r.epochMillis))}  HR ${Math.round(r.displayHeartRate)}")
                if (showBP(r)) append("  BP ${r.displayBP.text}")
                append("  quality ${r.level.label}")
                noteText(r)?.let { append("  $it") }
                r.edited?.let { append("  ${Math.round(it.usedSeconds)} of ${Math.round(r.duration)} s used") }
                if (r.starred) append("  starred")
            }
            lines.add(line)
        }
        return lines.joinToString("\n")
    }

    /** Every cuff comparison and every reading's raw model values, as CSV (same columns as iOS). */
    fun validationCsv(calibration: BPCalibration, appVersion: String): String =
        ValidationExport.csv(
            calibration,
            savedReadings.map { r ->
                ExportReading(
                    r.epochMillis / 1000.0, r.modelVersion, r.displayHeartRate, r.displayRhythm,
                    r.edited?.rawSystolic ?: r.rawSystolic, r.edited?.rawDiastolic ?: r.rawDiastolic,
                    r.displayBP.systolic, r.displayBP.diastolic, r.bpHidden == null,
                    r.duration, r.usedSeconds, r.feedQuality, r.feedCauses, r.note, r.excluded,
                )
            },
            appVersion, Publisher.model.version,
        )
}
