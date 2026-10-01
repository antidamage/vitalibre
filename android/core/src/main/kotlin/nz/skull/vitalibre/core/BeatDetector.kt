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
    data object Unstable : HeartRateFailure
}

class HeartRateEstimate(val bpm: Double, val intervals: DoubleArray, val acceptedBeats: Int)

sealed interface HeartRateResult {
    data class Ok(val estimate: HeartRateEstimate) : HeartRateResult
    data class Fail(val failure: HeartRateFailure) : HeartRateResult
}

object HeartRate {
    const val MIN_INTERVAL = 0.33
    const val MAX_INTERVAL = 1.5
    const val MAX_DEVIATION_FROM_RUNNING = 0.30
    val amplitudeBand = 0.5..2.0
    const val MIN_ACCEPTED = 8
    const val MIN_ACCEPTED_SHARE = 0.7
    const val MAX_HALF_DRIFT = 0.15

    fun estimate(beats: List<Beat>): HeartRateResult {
        val medAmp = Stats.median(DoubleArray(beats.size) { beats[it].amplitude })
        val kept = beats.filter { medAmp > 0 && it.amplitude / medAmp in amplitudeBand }
        val accepted = mutableListOf<Double>()
        var inBand = 0
        for (i in 1 until kept.size) {
            val dt = kept[i].time - kept[i - 1].time
            if (dt < MIN_INTERVAL || dt > MAX_INTERVAL) continue
            inBand++
            if (accepted.size >= 3) {
                val running = Stats.median(accepted.takeLast(10).toDoubleArray())
                if (abs(dt - running) / running > MAX_DEVIATION_FROM_RUNNING) continue
            }
            accepted.add(dt)
        }
        if (accepted.size < MIN_ACCEPTED) return HeartRateResult.Fail(HeartRateFailure.TooFewBeats(accepted.size))
        // A rate that shifted mid-scan looks like a run of outliers; refuse rather than report half the scan.
        if (accepted.size < MIN_ACCEPTED_SHARE * inBand) return HeartRateResult.Fail(HeartRateFailure.Unstable)
        val half = accepted.size / 2
        val first = Stats.median(accepted.subList(0, half).toDoubleArray())
        val second = Stats.median(accepted.subList(half, accepted.size).toDoubleArray())
        if (abs(first - second) / max(first, second) > MAX_HALF_DRIFT) return HeartRateResult.Fail(HeartRateFailure.Unstable)
        val arr = accepted.toDoubleArray()
        return HeartRateResult.Ok(HeartRateEstimate(60 / Stats.median(arr), arr, accepted.size + 1))
    }
}
