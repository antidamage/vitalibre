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
    /** The starting point (age prior or typical resting pressure) the raw value was built on; null for older points, which used the model's own base. */
    val baseSystolic: Double? = null,
    val baseDiastolic: Double? = null,
    /** Which cuff the reading came from, free text, so a comparison can name its reference. */
    val cuffName: String? = null,
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

    /** Seconds since the epoch of the newest paired cuff reading, or null with none. */
    val newestEpochSeconds: Double? get() = points.maxOfOrNull { it.epochSeconds }

    /**
     * A calibration is current while its newest paired cuff reading is at most [validDays] old. Only a
     * paired reading counts: it is the only thing that measures the model against a reference.
     */
    fun isCurrent(nowEpochSeconds: Double, validDays: Int = DEFAULT_VALID_DAYS): Boolean {
        val d = newestEpochSeconds ?: return false
        return nowEpochSeconds - d <= validDays * 86400.0
    }

    fun expiryEpochSeconds(validDays: Int = DEFAULT_VALID_DAYS): Double? = newestEpochSeconds?.plus(validDays * 86400.0)

    /**
     * What each pairing says the estimate should shift by, measured against the CURRENT starting point. A pairing
     * stores the raw value (starting point plus the pulse adjustment) it was made on; if the starting point has
     * changed since (a typical pressure was set, say), the old gap would otherwise be counted on top of a baseline
     * that already includes it.
     */
    private fun gaps(base: Pair<Double, Double>, legacy: Pair<Double, Double>): Pair<DoubleArray, DoubleArray> = Pair(
        DoubleArray(points.size) { val p = points[it]; p.cuffSystolic - base.first - (p.rawSystolic - (p.baseSystolic ?: legacy.first)) },
        DoubleArray(points.size) { val p = points[it]; p.cuffDiastolic - base.second - (p.rawDiastolic - (p.baseDiastolic ?: legacy.second)) },
    )

    fun offset(base: Pair<Double, Double>, legacy: Pair<Double, Double>): Pair<Double, Double> {
        if (points.isEmpty()) return 0.0 to 0.0
        val g = gaps(base, legacy)
        return max(-MAX_OFFSET, min(MAX_OFFSET, g.first.average())) to max(-MAX_OFFSET, min(MAX_OFFSET, g.second.average()))
    }

    /** 1.64 x the residual SD against this person's cuff, or null before three pairings. Not capped or floored. */
    fun cuffSpread(base: Pair<Double, Double>, legacy: Pair<Double, Double>): Pair<Double, Double>? {
        if (points.size < MIN_POINTS_FOR_NARROWING) return null
        val g = gaps(base, legacy)
        return 1.64 * Stats.std(g.first) to 1.64 * Stats.std(g.second)
    }

    /** Half-widths for the displayed range. `fallback` is the model's population figure. */
    fun halfWidths(fallbackSystolic: Double, fallbackDiastolic: Double, base: Pair<Double, Double>, legacy: Pair<Double, Double>): Pair<Double, Double> {
        if (points.size < MIN_POINTS_FOR_NARROWING) return fallbackSystolic to fallbackDiastolic
        val g = gaps(base, legacy)
        val s = Stats.std(g.first)
        val d = Stats.std(g.second)
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

        /** How long a paired cuff reading keeps blood pressure on display (iOS). `policy.json` can override it. */
        const val DEFAULT_VALID_DAYS = 30
    }
}
