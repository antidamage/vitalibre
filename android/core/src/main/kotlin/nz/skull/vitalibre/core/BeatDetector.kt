package nz.skull.vitalibre.core

import kotlin.math.abs
import kotlin.math.max

data class Beat(val index: Int, val time: Double, val amplitude: Double)

/**
 * Elgendi's two-moving-average detector (PLoS ONE 2013): a short average (systolic-peak width)
 * against a long one (beat width) plus an offset, with one peak taken per block.
 */
object BeatDetector {
    const val W1_SECONDS = 0.111
    const val W2_SECONDS = 0.667
    const val BETA = 0.02

    /** `y` is the band-passed, upward-positive signal sampled at `fs`. */
    fun detect(y: DoubleArray, fs: Double): List<Beat> {
        if (y.size <= fs.toInt()) return emptyList()
        val w1 = max(1, Math.round(W1_SECONDS * fs).toInt())
        val w2 = max(2, Math.round(W2_SECONDS * fs).toInt())
        val squared = DoubleArray(y.size) { val c = max(0.0, y[it]); c * c }
        val peakMA = Filters.movingAverage(squared, w1)
        val beatMA = Filters.movingAverage(squared, w2)
        val offset = BETA * Stats.mean(squared)

        val beats = mutableListOf<Beat>()
        var start = -1
        fun close(end: Int) {
            if (start >= 0 && end - start >= w1) {
                var best = start
                for (i in start until end) if (y[i] > y[best]) best = i
                beats.add(Beat(best, refined(y, best) / fs, y[best]))
            }
            start = -1
        }
        for (i in y.indices) {
            if (peakMA[i] > beatMA[i] + offset) {
                if (start < 0) start = i
            } else {
                close(i)
            }
        }
        close(y.size)
        return beats
    }

    /** Parabolic interpolation through the peak and its two neighbours. */
    private fun refined(y: DoubleArray, i: Int): Double {
        if (i <= 0 || i >= y.size - 1) return i.toDouble()
        val a = y[i - 1]
        val b = y[i]
        val c = y[i + 1]
        val d = a - 2 * b + c
        return if (abs(d) < 1e-12) i.toDouble() else i + 0.5 * (a - c) / d
    }
}

sealed interface HeartRateFailure {
    data class TooFewBeats(val accepted: Int) : HeartRateFailure
}

/**
 * How evenly the beats came. A note about a reading, never a verdict on it: a rhythm that swings is
 * reported, not refused. A finger camera cannot tell an irregular rhythm from a poor signal, and it
 * does not try to diagnose either.
 */
enum class Rhythm { STEADY, IRREGULAR }

class HeartRateEstimate(
    val bpm: Double,
    val intervals: DoubleArray,
    val acceptedBeats: Int,
    /** Standard deviation over the mean of the accepted intervals: the same spread the estimator carries as `intervalCV`. */
    val variation: Double,
    val rhythm: Rhythm,
)

sealed interface HeartRateResult {
    data class Ok(val estimate: HeartRateEstimate) : HeartRateResult
    data class Fail(val failure: HeartRateFailure) : HeartRateResult
}

object HeartRate {
    /** The range a fingertip can show: 30-240 bpm. Wider than the 40-180 the first build used, so a pause or a run of fast beats reads as a beat rather than as a dropout. */
    const val MIN_INTERVAL = 0.25
    const val MAX_INTERVAL = 2.0
    val amplitudeBand = 0.5..2.0

    /** Enough beats to take a median of. A count, not a duration: how long the scan ran says nothing about whether the rate in it can be trusted. */
    const val MIN_ACCEPTED = 8

    /** The interval spread at which a rhythm is called irregular: about three times ordinary respiratory variation (near 0.03). */
    const val IRREGULAR_VARIATION = 0.10

    fun estimate(beats: List<Beat>): HeartRateResult = estimateGroups(listOf(beats))

    /**
     * Intervals are taken only between beats of the same group, never across a gap: a group is a stretch
     * of signal the user kept, and an interval spanning a discarded stretch would be false.
     */
    fun estimateGroups(groups: List<List<Beat>>): HeartRateResult {
        // Drop beats whose amplitude is far from the window's median: that is the detector's confidence
        // in the beat, not its timing.
        val all = groups.flatten()
        val medAmp = Stats.median(DoubleArray(all.size) { all[it].amplitude })
        val accepted = mutableListOf<Double>()
        for (group in groups) {
            val kept = group.filter { medAmp > 0 && it.amplitude / medAmp in amplitudeBand }
            for (i in 1 until kept.size) {
                val dt = kept[i].time - kept[i - 1].time
                if (dt < MIN_INTERVAL || dt > MAX_INTERVAL) continue
                accepted.add(dt)
            }
        }
        if (accepted.size < MIN_ACCEPTED) return HeartRateResult.Fail(HeartRateFailure.TooFewBeats(accepted.size))
        // Nothing from here on refuses a window for its rhythm, and nothing is dropped for sitting far
        // from its neighbours. A rate that swings during the scan is measured: `variation` says by how
        // much and `rhythm` whether it is worth a note. A recording that might hold an arrhythmia is a
        // recording to keep and mark, not one to abandon.
        val arr = accepted.toDoubleArray()
        val variation = Stats.std(arr) / max(1e-9, Stats.mean(arr))
        return HeartRateResult.Ok(
            HeartRateEstimate(
                60 / Stats.median(arr), arr, accepted.size + 1, variation,
                if (variation >= IRREGULAR_VARIATION) Rhythm.IRREGULAR else Rhythm.STEADY,
            ),
        )
    }
}
