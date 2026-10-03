package nz.skull.vitalibre.core

import kotlin.math.max
import kotlin.math.min

/**
 * A stretch of a reading's run, in seconds from the start of the covered run, that the user has marked as
 * not to be used. The stretch stays on the graph and in the file; only the numbers stop counting it.
 */
data class ExcludedRange(val start: Double, val end: Double) {
    val length: Double get() = max(0.0, end - start)
}

/** A reading's numbers recomputed with stretches left out, from the stored trace. */
object ReadingEdit {
    const val MIN_KEPT_SECONDS = ScanSession.MIN_SECONDS

    /** A beat this close to a discarded stretch is dropped too: its foot and reflected peak sit around it. */
    const val BEAT_MARGIN = 0.25

    class Outcome(
        val heartRate: Double, val rhythm: Rhythm, val bp: BPRange,
        val rawSystolic: Double, val rawDiastolic: Double, val usedSeconds: Double,
    )

    sealed interface Result {
        data class Ok(val outcome: Outcome) : Result
        data object TooLittleSignalLeft : Result
        data object NoPulse : Result
    }

    /** Ranges sorted, clipped to the trace, and merged where they touch. */
    fun merged(ranges: List<ExcludedRange>, from: Double, to: Double): List<ExcludedRange> {
        val out = ArrayList<ExcludedRange>()
        for (r in ranges.map { ExcludedRange(max(from, it.start), min(to, it.end)) }.filter { it.length > 0 }.sortedBy { it.start }) {
            val last = out.lastOrNull()
            if (last != null && r.start <= last.end) out[out.size - 1] = ExcludedRange(last.start, max(last.end, r.end))
            else out.add(r)
        }
        return out
    }

    /**
     * [trace] is the stored filtered waveform at [ScanSession.ANALYSIS_RATE], [traceStart] the run second of
     * its first sample. Beats are found on the whole trace (cutting first would add filter edges), those
     * inside or beside a discarded stretch are dropped, and intervals are taken only inside one kept stretch.
     * With nothing excluded the caller shows the stored result instead of calling this.
     */
    fun recompute(
        trace: DoubleArray, traceStart: Double, excluded: List<ExcludedRange>,
        model: BPModel, age: Int?, sex: Sex, usual: UsualBP?, calibration: BPCalibration,
    ): Result {
        val fs = ScanSession.ANALYSIS_RATE
        if (trace.size <= fs.toInt()) return Result.TooLittleSignalLeft
        val lo = traceStart
        val hi = traceStart + (trace.size - 1) / fs
        val cuts = merged(excluded, lo, hi)
        val used = (hi - lo) - cuts.sumOf { it.length }
        if (used < MIN_KEPT_SECONDS) return Result.TooLittleSignalLeft

        val stretches = ArrayList<Pair<Double, Double>>()
        var from = lo
        for (c in cuts) { if (c.start > from) stretches.add(from to c.start); from = c.end }
        if (from < hi) stretches.add(from to hi)

        val beats = BeatDetector.detect(trace, fs)
        val groups = List(stretches.size) { ArrayList<Beat>() }
        for (b in beats) {
            val t = traceStart + b.time
            val i = stretches.indexOfFirst { (a, z) ->
                t >= a + (if (a > lo) BEAT_MARGIN else 0.0) && t <= z - (if (z < hi) BEAT_MARGIN else 0.0)
            }
            if (i >= 0) groups[i].add(b)
        }
        val hr = when (val r = HeartRate.estimateGroups(groups)) {
            is HeartRateResult.Ok -> r.estimate
            is HeartRateResult.Fail -> return Result.NoPulse
        }
        val kept = ArrayList<Double>()
        for (i in trace.indices) {
            val t = traceStart + i / fs
            if (stretches.any { t >= it.first && t <= it.second }) kept.add(trace[i])
        }
        val feats = BPEstimator.features(trace, groups.flatten(), hr, fs, groups, kept.toDoubleArray())
        val raw = BPEstimator.raw(feats, model, age, sex, usual)
        val bp = BPEstimator.estimate(feats, model, age, sex, usual, calibration)
        return Result.Ok(Outcome(hr.bpm, hr.rhythm, bp, raw.first, raw.second, used))
    }
}
