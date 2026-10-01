package nz.skull.vitalibre.core

import kotlin.math.max
import kotlin.math.min

/** One paired reading: what the model said for a scan, and what a cuff said at the same time. */
data class CalibrationPoint(
    val rawSystolic: Double, val rawDiastolic: Double,
    val cuffSystolic: Double, val cuffDiastolic: Double,
    val epochSeconds: Double,
    /** Phone model the pair was taken on; null for older points. */
    val device: String? = null,
) {
    companion object {
        fun isPlausible(systolic: Double, diastolic: Double): Boolean =
            systolic in 70.0..250.0 && diastolic in 40.0..150.0 && systolic > diastolic + 10
    }
}

/**
 * Personal calibration against a cuff. The offset is the mean gap between cuff and model; with three
 * or more points the range narrows to the error actually seen against this person's cuff.
 */
data class BPCalibration(val points: List<CalibrationPoint> = emptyList()) {
    val count get() = points.size

    val offset: Pair<Double, Double>
        get() {
            if (points.isEmpty()) return 0.0 to 0.0
            val s = points.map { it.cuffSystolic - it.rawSystolic }.average()
            val d = points.map { it.cuffDiastolic - it.rawDiastolic }.average()
            return max(-MAX_OFFSET, min(MAX_OFFSET, s)) to max(-MAX_OFFSET, min(MAX_OFFSET, d))
        }

    /** Half-widths for the displayed range. `fallback` is the model's population figure. */
    fun halfWidths(fallbackSystolic: Double, fallbackDiastolic: Double): Pair<Double, Double> {
        if (points.size < MIN_POINTS_FOR_NARROWING) return fallbackSystolic to fallbackDiastolic
        val (os, od) = offset
        val s = Stats.std(DoubleArray(points.size) { points[it].cuffSystolic - points[it].rawSystolic - os })
        val d = Stats.std(DoubleArray(points.size) { points[it].cuffDiastolic - points[it].rawDiastolic - od })
        return max(MIN_HALF_WIDTH_SYSTOLIC, min(fallbackSystolic, 1.64 * s)) to
            max(MIN_HALF_WIDTH_DIASTOLIC, min(fallbackDiastolic, 1.64 * d))
    }

    fun with(point: CalibrationPoint): BPCalibration {
        if (!CalibrationPoint.isPlausible(point.cuffSystolic, point.cuffDiastolic)) return this
        return BPCalibration((points + point).takeLast(MAX_POINTS))
    }

    companion object {
        const val MAX_OFFSET = 60.0
        const val MIN_POINTS_FOR_NARROWING = 3
        const val MIN_HALF_WIDTH_SYSTOLIC = 6.0
        const val MIN_HALF_WIDTH_DIASTOLIC = 4.0
        const val MAX_POINTS = 30
    }
}
