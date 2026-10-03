package nz.skull.vitalibre.core

import kotlin.math.max
import kotlin.math.min

/** One frame's channel means over the ROI, on 0..255. */
data class PPGSample(
    val t: Double,
    val r: Double, val g: Double, val b: Double,
    /** Fraction of ROI pixels at or near full scale in the green channel (red clips on any fingertip under the torch). */
    val saturated: Double,
) {
    /** A fingertip over the lens and torch: red high, green well below it. */
    val covered: Boolean get() = r >= 80 && g <= 0.7 * r && saturated < 0.85
}

/**
 * Why a scan produced no reading. Every one of these is the finger: not on the lens, taken off it too
 * early, moving enough that no beat can be found, or pressed so that the pulse never rises above the
 * light level. A rhythm that swings is not on this list — it is a note on the reading instead.
 */
sealed interface ScanFailure {
    val message: String

    data object NotCovered : ScanFailure {
        override val message = "Cover the lens and flash with your fingertip, then try again."
    }

    data class TooShort(val seconds: Double) : ScanFailure {
        override val message = "The scan was too short. Keep your finger still until it finishes."
    }

    data object NoPulse : ScanFailure {
        override val message = "No pulse found. Rest your fingertip lightly and try again."
    }

    data object PoorSignal : ScanFailure {
        override val message = "The signal was too weak to trust. Warm your hands and try again."
    }
}

enum class Guidance(val text: String) {
    COVER_LENS("Cover the lens and flash completely"),
    HOLD("Hold still — reading"),
    PRESS_LIGHTER("Press more lightly"),
    KEEP_STILL("Keep still"),
    GOOD("Good signal"),
}

class ScanResult(
    val heartRate: Double,
    val bp: BPRange,
    val quality: Double,
    val level: QualityLevel,
    /** How evenly the beats came. Carried onto the reading as a note when irregular; it never refuses a scan. */
    val rhythm: Rhythm,
    val duration: Double,
    val intervals: DoubleArray,
    val modelVersion: String,
    val features: BPFeatures,
    /** The model's value before calibration, kept so a cuff reading can be paired with this scan. */
    val rawSystolic: Double,
    val rawDiastolic: Double,
    /** The starting point the raw value was built on, kept so a cuff pairing can remember it. */
    val baseSystolic: Double,
    val baseDiastolic: Double,
    /** The whole run's filtered waveform, normalised -1..1 at the analysis rate, for the graph kept on screen. */
    val trace: DoubleArray,
    /** Seconds from the run's start to the trace's last sample. */
    val traceEnd: Double,
) {
    fun withBP(bp: BPRange) = ScanResult(heartRate, bp, quality, level, rhythm, duration, intervals, modelVersion, features,
        rawSystolic, rawDiastolic, baseSystolic, baseDiastolic, trace, traceEnd)
}

/** What the orb shows while a scan is still running. */
class LiveView(
    val trace: DoubleArray,
    val traceEnd: Double,
    val heartRate: Double?,
    val bp: BPRange?,
    /** Run-relative time of the most recent detected beat, for the pulse. */
    val lastBeat: Double?,
)

sealed interface ScanOutcome {
    data class Success(val result: ScanResult) : ScanOutcome
    data class Failure(val failure: ScanFailure) : ScanOutcome
}

class ScanSession(initial: List<PPGSample> = emptyList()) {
    private val samples = ArrayList<PPGSample>(initial)

    fun add(s: PPGSample) { samples.add(s) }

    /** A copy for analysis on another thread. */
    fun snapshot() = ScanSession(samples)

    val elapsed: Double get() = if (samples.size > 1) samples.last().t - samples.first().t else 0.0

    /** Length of the run ending now in which the finger stayed covered. */
    val currentRunSeconds: Double
        get() {
            val last = samples.lastOrNull() ?: return 0.0
            if (!last.covered) return 0.0
            var start = samples.size - 1
            var j = start - 1
            while (j >= 0) {
                if (samples[j].covered) start = j
                else if (samples[start].t - samples[j].t > MAX_GAP_SECONDS) break
                j--
            }
            return last.t - samples[start].t
        }

    val finished: Boolean get() = currentRunSeconds >= TARGET_SECONDS || elapsed >= MAX_SECONDS

    val guidance: Guidance
        get() {
            val last = samples.lastOrNull()
            if (last == null || !last.covered) return Guidance.COVER_LENS
            if (last.saturated > 0.5) return Guidance.PRESS_LIGHTER
            val recent = samples.takeLast(30)
            if (recent.size >= 15) {
                val g = DoubleArray(recent.size) { recent[it].g }
                val spread = Stats.std(g) / max(1.0, Stats.mean(g))
                if (spread > 0.05) return Guidance.KEEP_STILL
            }
            return if (currentRunSeconds > 6) Guidance.GOOD else Guidance.HOLD
        }

    private class Run(val start: Int, val end: Int, val duration: Double)

    private fun longestRun(): Run? {
        var best: Run? = null
        var start = -1
        var last = 0
        for (i in samples.indices) {
            if (!samples[i].covered) continue
            if (start < 0 || samples[i].t - samples[last].t > MAX_GAP_SECONDS) start = i
            last = i
            val d = samples[i].t - samples[start].t
            if (best == null || d > best.duration) best = Run(start, i, d)
        }
        return best
    }

    class Window(val filtered: DoubleArray, val raw: DoubleArray, val start: Double)

    /** The upward-positive green channel of the longest covered run, resampled and band-passed. */
    fun filteredWindow(fromSecondsBack: Double? = null): Window? {
        val run = longestRun() ?: return null
        var slice = samples.subList(run.start, run.end + 1).toList()
        val tStart = slice[0].t + SETTLE_SECONDS
        slice = slice.filter { it.t >= tStart }
        if (fromSecondsBack != null && slice.isNotEmpty()) {
            val end = slice.last().t
            slice = slice.filter { it.t >= end - fromSecondsBack }
        }
        if (slice.size <= 10) return null
        val raw = Filters.resample(DoubleArray(slice.size) { slice[it].t }, DoubleArray(slice.size) { slice[it].g }, ANALYSIS_RATE)
        val filtered = Filters.bandpass(DoubleArray(raw.size) { -raw[it] }, ANALYSIS_RATE)
        return Window(filtered, raw, slice[0].t - samples[run.start].t)
    }

    /**
     * The trace, a running heart rate and blood pressure, and the last beat, from what has been
     * recorded so far. Looser than the final analysis (a few beats are enough) because it is only a
     * read-out while the scan runs; the result still comes from [analyse].
     */
    fun live(model: BPModel, age: Int?, sex: Sex, usual: UsualBP?, calibration: BPCalibration): LiveView? {
        val w = filteredWindow() ?: return null
        if (w.filtered.size <= (2 * ANALYSIS_RATE).toInt()) return null
        val fs = ANALYSIS_RATE
        val beats = BeatDetector.detect(w.filtered, fs)
        val trace = normalised(w.filtered)
        val end = w.start + (trace.size - 1) / fs
        val lastBeat = beats.lastOrNull()?.let { w.start + it.time }
        val intervals = beats.zipWithNext { a, b -> b.time - a.time }
            .filter { it >= HeartRate.MIN_INTERVAL && it <= HeartRate.MAX_INTERVAL }
        if (intervals.size < 3) return LiveView(trace, end, null, null, lastBeat)
        val recent = intervals.takeLast(10).toDoubleArray()
        val bpm = 60 / Stats.median(recent)
        var bp: BPRange? = null
        if (intervals.size >= 5) {
            val spread = Stats.std(recent) / max(1e-9, Stats.mean(recent))
            val hr = HeartRateEstimate(bpm, recent, recent.size + 1, spread,
                if (spread >= HeartRate.IRREGULAR_VARIATION) Rhythm.IRREGULAR else Rhythm.STEADY)
            bp = BPEstimator.estimate(BPEstimator.features(w.filtered, beats, hr, fs), model, age, sex, usual, calibration)
        }
        return LiveView(trace, end, bpm, bp, lastBeat)
    }

    fun analyse(model: BPModel, age: Int?, sex: Sex, usual: UsualBP? = null, calibration: BPCalibration = BPCalibration()): ScanOutcome {
        val run = longestRun() ?: return ScanOutcome.Failure(ScanFailure.NotCovered)
        if (run.duration < MIN_SECONDS) return ScanOutcome.Failure(ScanFailure.TooShort(run.duration))
        val w = filteredWindow() ?: return ScanOutcome.Failure(ScanFailure.NotCovered)
        val fs = ANALYSIS_RATE
        val beats = BeatDetector.detect(w.filtered, fs)
        when (val hrResult = HeartRate.estimate(beats)) {
            is HeartRateResult.Fail -> return ScanOutcome.Failure(ScanFailure.NoPulse)
            is HeartRateResult.Ok -> {
                val hr = hrResult.estimate
                val q = SignalQuality.measure(w.filtered, w.raw, beats, fs)
                // The one quality failure left: a pulse that never rises above the light level. There is
                // nothing in that to read a rate from, and it is a finger problem — too light, too heavy,
                // off the lens. A merely poor shape is kept and noted instead: refusing it was how a
                // recording that might hold an arrhythmia got thrown away, because a weak signal and an
                // unsteady rhythm come out of the same three indices.
                if (q.perfusionIndex < SignalQuality.MIN_PERFUSION_INDEX) return ScanOutcome.Failure(ScanFailure.PoorSignal)
                val feats = BPEstimator.features(w.filtered, beats, hr, fs)
                val raw = BPEstimator.raw(feats, model, age, sex, usual)
                return ScanOutcome.Success(
                    ScanResult(
                        hr.bpm, BPEstimator.estimate(feats, model, age, sex, usual, calibration), q.score, q.level, hr.rhythm,
                        run.duration, hr.intervals, model.version, feats, raw.first, raw.second,
                        BPEstimator.baseline(model, age, sex, usual).first, BPEstimator.baseline(model, age, sex, usual).second,
                        normalised(w.filtered), w.start + (w.filtered.size - 1) / fs,
                    ),
                )
            }
        }
    }

    companion object {
        const val TARGET_SECONDS = 15.0
        const val MIN_SECONDS = 8.0
        const val MAX_SECONDS = 45.0
        const val ANALYSIS_RATE = 60.0

        /** The first stretch after cover is discarded while exposure settles. */
        const val SETTLE_SECONDS = 1.0
        const val MAX_GAP_SECONDS = 0.3

        fun normalised(y: DoubleArray): DoubleArray {
            val scale = max(1e-6, Stats.percentile(Stats.abs(y), 95.0))
            return DoubleArray(y.size) { max(-1.0, min(1.0, y[it] / scale)) }
        }
    }
}
