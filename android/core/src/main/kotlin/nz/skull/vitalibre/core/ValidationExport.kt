package nz.skull.vitalibre.core

import java.time.Instant
import java.util.Locale

/** What the export needs of one reading; the app maps its own model onto this. */
data class ExportReading(
    val epochSeconds: Double,
    val modelVersion: String,
    val heartRate: Double,
    val rhythm: Rhythm?,
    val rawSystolic: Double?, val rawDiastolic: Double?,
    val shownSystolic: Int, val shownDiastolic: Int,
    val showBP: Boolean,
    val duration: Double, val usedSeconds: Double,
    val feedQuality: List<Double>?, val feedCauses: List<String>?,
    val note: String?,
    val excluded: List<ExcludedRange>?,
)

/**
 * The paired validation export: every cuff comparison and every reading's raw model values, as CSV, so the
 * app's error against a named cuff can be worked out outside the app. Same columns as the iOS export.
 */
object ValidationExport {
    const val CALIBRATION_HEADER = "type,date,device,app_version,model_version,cuff_systolic,cuff_diastolic,raw_systolic,raw_diastolic,base_systolic,base_diastolic,cuff_name"
    const val READING_HEADER = "type,date,model_version,heart_rate,rhythm,raw_systolic,raw_diastolic,shown_systolic,shown_diastolic,duration_s,used_s,feed_mean,note,excluded_ranges,feed_series,feed_causes"

    private fun iso(epochSeconds: Double) = Instant.ofEpochSecond(Math.floor(epochSeconds).toLong()).toString()

    private fun field(s: String) =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    private fun num(v: Double?, places: Int = 1) = v?.let { String.format(Locale.US, "%.${places}f", it) } ?: ""

    /** [includeRaw] false leaves every model blood-pressure value (raw, base, shown) out; Android always includes them. */
    fun csv(calibration: BPCalibration, readings: List<ExportReading>, appVersion: String, modelVersion: String, includeRaw: Boolean = true): String {
        val lines = arrayListOf("# VitaLibre validation export. Estimates only. Not a medical device.", CALIBRATION_HEADER)
        for (p in calibration.points.sortedBy { it.epochSeconds }) {
            lines.add(listOf(
                "calibration", iso(p.epochSeconds), p.device ?: "", appVersion, modelVersion,
                num(p.cuffSystolic, 0), num(p.cuffDiastolic, 0), if (includeRaw) num(p.rawSystolic) else "", if (includeRaw) num(p.rawDiastolic) else "",
                if (includeRaw) num(p.baseSystolic) else "", if (includeRaw) num(p.baseDiastolic) else "", field(p.cuffName ?: ""),
            ).joinToString(","))
        }
        lines.add("")
        lines.add(READING_HEADER)
        for (r in readings.sortedBy { it.epochSeconds }) {
            val ranges = (r.excluded ?: emptyList()).joinToString(";") { num(it.start) + "-" + num(it.end) }
            val feedMean = r.feedQuality?.takeIf { it.isNotEmpty() }?.average()
            lines.add(listOf(
                "reading", iso(r.epochSeconds), r.modelVersion, num(r.heartRate),
                r.rhythm?.name?.lowercase() ?: "", if (includeRaw && r.showBP) num(r.rawSystolic) else "", if (includeRaw && r.showBP) num(r.rawDiastolic) else "",
                if (r.showBP && includeRaw) "${r.shownSystolic}" else "", if (r.showBP && includeRaw) "${r.shownDiastolic}" else "",
                num(r.duration), num(r.usedSeconds), num(feedMean, 2), field(r.note ?: ""), field(ranges),
                (r.feedQuality ?: emptyList()).joinToString(";") { num(it, 2) },
                (r.feedCauses ?: emptyList()).joinToString(";"),
            ).joinToString(","))
        }
        return lines.joinToString("\n")
    }
}
