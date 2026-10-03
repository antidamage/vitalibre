package nz.skull.vitalibre.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Phone motion at the same moments as the camera frames, gravity already removed by the platform:
 * gyro in rad/s, linear acceleration in m/s^2. Sampled at 50 Hz or better so 12 Hz tremor is visible.
 */
data class MotionSample(
    val t: Double,
    val gx: Double, val gy: Double, val gz: Double,
    val ax: Double, val ay: Double, val az: Double,
)

/** What lowered the feed quality most, or NONE when it is good. The raw value is what a reading stores. */
enum class FeedCause(val raw: String, val advice: String?) {
    NONE("none", null),
    COVERAGE("coverage", "Cover the lens and flash completely"),
    CLIPPING("clipping", "Press more lightly"),
    LIGHT("light", "Too dark or too bright — shade the phone from other light"),
    FRAMES("frames", "The camera is dropping frames — close other apps"),
    NOISE("noise", "The picture is noisy — warm your hands and keep the finger still"),
    STEPS("steps", "Keep still"),
    MOTION("motion", "Keep still"),
    TREMOR("tremor", "Hand shaking — rest your forearm"),
    ADJUSTING("adjusting", "Hold still — the camera is adjusting");

    companion object {
        fun fromRaw(raw: String): FeedCause = entries.firstOrNull { it.raw == raw } ?: NONE
    }
}

/**
 * How good the camera feed is, apart from the heart. Every factor is a measure of the picture or of the
 * phone; none uses beat intervals or pulse amplitude, so an irregular or weak pulse cannot lower it
 * (specs/ppg-vitals-app.md, "Feed quality"). The value is the minimum of the factors: one bad factor sinks it.
 * All constants are provisional until measured on a phone, and are the same on both platforms.
 */
object FeedQuality {
    const val WINDOW_SECONDS = 3.0
    const val GOOD = 0.7
    const val FAIR = 0.4

    // Each pair: the value that scores 1, and the value that scores 0 (either direction).
    val COVERAGE = Pair(0.9, 0.5)
    val CLIPPING = Pair(0.2, 0.6)
    val DC_OK = 40.0..230.0
    const val DC_EDGE_LOW = 25.0
    const val DC_EDGE_HIGH = 245.0
    val LATE_FRAMES = Pair(0.0, 0.2)
    const val LATE_FACTOR = 1.5
    val NOISE = Pair(0.0005, 0.004)
    val STEPS = Pair(0.015, 0.06)
    val MOVEMENT = Pair(0.05, 0.3)          // gyro rad/s, 0.1-3 Hz
    val MOVEMENT_ACCEL = Pair(0.05, 0.5)    // m/s^2, 0.1-3 Hz
    val TREMOR = Pair(0.02, 0.1)            // gyro rad/s, 4-12 Hz
    val TREMOR_ACCEL = Pair(0.03, 0.2)      // m/s^2, 4-12 Hz
    const val ADJUSTING_SCORE = 0.3
    const val MIN_MOTION_SAMPLES = 40

    /** The noise band, as fractions of the frame rate. */
    const val NOISE_BAND_LOW = 0.3
    const val NOISE_BAND_HIGH = 0.47

    /** `t` is seconds from the start of the covered run; the window is the [WINDOW_SECONDS] ending there. */
    data class Point(val t: Double, val value: Double, val cause: FeedCause)

    fun level(value: Double): QualityLevel = if (value >= GOOD) QualityLevel.GOOD else if (value >= FAIR) QualityLevel.FAIR else QualityLevel.POOR

    /** One point per whole second of the run. */
    fun series(samples: List<PPGSample>, motion: List<MotionSample>, runStart: Double, runEnd: Double): List<Point> {
        val run = samples.filter { it.t >= runStart && it.t <= runEnd }
        if (run.size <= 5) return emptyList()
        val seconds = (runEnd - runStart).toInt()
        if (seconds < 1) return emptyList()
        return (1..seconds).map { k ->
            val end = runStart + k
            val start = end - WINDOW_SECONDS
            evaluate(run.filter { it.t > start && it.t <= end }, motion.filter { it.t > start && it.t <= end }, k.toDouble())
        }
    }

    /** The latest window, for the prompts while a scan is running. */
    fun latest(samples: List<PPGSample>, motion: List<MotionSample>): Point? {
        val last = samples.lastOrNull() ?: return null
        val start = last.t - WINDOW_SECONDS
        val s = samples.filter { it.t > start }
        if (s.size <= 5) return null
        return evaluate(s, motion.filter { it.t > start }, last.t)
    }

    fun mean(points: List<Point>): Double = if (points.isEmpty()) 0.0 else points.sumOf { it.value } / points.size

    fun evaluate(s: List<PPGSample>, m: List<MotionSample>, t: Double): Point {
        if (s.size <= 5) return Point(t, 0.0, FeedCause.FRAMES)
        val factors = ArrayList<Pair<FeedCause, Double>>()
        factors.add(FeedCause.COVERAGE to ramp(Stats.mean(DoubleArray(s.size) { s[it].coverage }), COVERAGE.first, COVERAGE.second))
        factors.add(FeedCause.CLIPPING to ramp(Stats.mean(DoubleArray(s.size) { s[it].saturated }), CLIPPING.first, CLIPPING.second))
        val g = DoubleArray(s.size) { s[it].g }
        val dc = Stats.mean(g)
        val light = when {
            dc in DC_OK -> 1.0
            dc < DC_OK.start -> ramp(dc, DC_OK.start, DC_EDGE_LOW)
            else -> ramp(dc, DC_OK.endInclusive, DC_EDGE_HIGH)
        }
        factors.add(FeedCause.LIGHT to light)
        factors.add(FeedCause.FRAMES to ramp(lateShare(DoubleArray(s.size) { s[it].t }), LATE_FRAMES.first, LATE_FRAMES.second))
        factors.add(FeedCause.NOISE to ramp(noiseOverDC(DoubleArray(s.size) { s[it].t }, g, dc), NOISE.first, NOISE.second))
        factors.add(FeedCause.STEPS to ramp(largestStep(s, dc), STEPS.first, STEPS.second))
        if (m.size >= MIN_MOTION_SAMPLES) {
            val move = min(ramp(bandAmplitude(m, true, 0.1, 3.0), MOVEMENT.first, MOVEMENT.second),
                ramp(bandAmplitude(m, false, 0.1, 3.0), MOVEMENT_ACCEL.first, MOVEMENT_ACCEL.second))
            val shake = min(ramp(bandAmplitude(m, true, 4.0, 12.0), TREMOR.first, TREMOR.second),
                ramp(bandAmplitude(m, false, 4.0, 12.0), TREMOR_ACCEL.first, TREMOR_ACCEL.second))
            factors.add(FeedCause.MOTION to move)
            factors.add(FeedCause.TREMOR to shake)
        }
        factors.add(FeedCause.ADJUSTING to if (s.any { it.event }) ADJUSTING_SCORE else 1.0)
        val worst = factors.minBy { it.second }
        return Point(t, worst.second, if (worst.second < GOOD) worst.first else FeedCause.NONE)
    }

    /** 1 at [good], 0 at [bad], linear between; works whichever is larger. */
    fun ramp(x: Double, good: Double, bad: Double): Double = max(0.0, min(1.0, (x - bad) / (good - bad)))

    /** Share of frames that arrived after a gap longer than [LATE_FACTOR] times the median gap. */
    fun lateShare(t: DoubleArray): Double {
        if (t.size <= 3) return 0.0
        val dts = DoubleArray(t.size - 1) { t[it + 1] - t[it] }
        val med = Stats.median(dts)
        if (med <= 0) return 1.0
        return dts.count { it > LATE_FACTOR * med }.toDouble() / dts.size
    }

    /**
     * Noise in the green series over its mean, from the band between 30% and 47% of the frame rate (9-14 Hz
     * at 30 fps). A pulse has almost no energy there (a systolic upstroke 70 ms wide is down by a factor
     * of several hundred at 9 Hz), and a second difference does not manage that: at a fast heart rate the
     * pulse's own curvature fills most frames. White noise has the same power at every frequency, so the
     * mean power in the band is its variance. A Hann window keeps the slow drift and the pulse from leaking
     * into the band. Computed at the real frame times, so dropped frames show up in [lateShare] first.
     */
    fun noiseOverDC(t: DoubleArray, g: DoubleArray, dc: Double): Double {
        val n = g.size
        if (n <= 8 || dc <= 0) return 0.0
        val dts = DoubleArray(n - 1) { t[it + 1] - t[it] }
        val med = Stats.median(dts)
        if (med <= 0) return 0.0
        val fps = 1 / med
        val lo = NOISE_BAND_LOW * fps
        val hi = NOISE_BAND_HIGH * fps
        val mean = Stats.mean(g)
        val w = DoubleArray(n) { 0.5 * (1 - cos(2 * PI * it / (n - 1))) }
        val sumW2 = w.sumOf { it * it }
        var total = 0.0
        var bins = 0
        var f = lo
        while (f <= hi + 1e-9) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val ph = 2 * PI * f * t[i]
                re += w[i] * (g[i] - mean) * cos(ph)
                im += w[i] * (g[i] - mean) * sin(ph)
            }
            total += (re * re + im * im) / sumW2
            bins++
            f += 0.5
        }
        return sqrt(total / bins) / dc
    }

    /** The largest jump between consecutive half-second means over the mean: a shifted finger, an exposure change. */
    fun largestStep(s: List<PPGSample>, dc: Double): Double {
        if (dc <= 0 || s.isEmpty()) return 0.0
        val t0 = s.first().t
        val sums = HashMap<Int, Pair<Double, Int>>()
        for (x in s) {
            val k = ((x.t - t0) / 0.5).toInt()
            val v = sums[k] ?: Pair(0.0, 0)
            sums[k] = Pair(v.first + x.g, v.second + 1)
        }
        val means = sums.keys.sorted().mapNotNull { k -> sums[k]!!.let { if (it.second >= 3) it.first / it.second else null } }
        if (means.size < 2) return 0.0
        return means.zipWithNext { a, b -> abs(b - a) }.max() / dc
    }

    /** The strongest sinusoid between [lo] and [hi] Hz as an RMS amplitude, summed in quadrature over the three axes. */
    fun bandAmplitude(m: List<MotionSample>, gyro: Boolean, lo: Double, hi: Double): Double {
        val axes = if (gyro) listOf(m.map { it.gx }, m.map { it.gy }, m.map { it.gz })
        else listOf(m.map { it.ax }, m.map { it.ay }, m.map { it.az })
        val times = m.map { it.t }
        val means = axes.map { a -> a.average() }
        var best = 0.0
        var f = lo
        while (f <= hi + 1e-9) {
            var power = 0.0
            for ((ai, a) in axes.withIndex()) {
                var re = 0.0
                var im = 0.0
                for (i in a.indices) {
                    val ph = 2 * PI * f * times[i]
                    re += (a[i] - means[ai]) * cos(ph)
                    im += (a[i] - means[ai]) * sin(ph)
                }
                val amp = 2 * sqrt(re * re + im * im) / a.size
                power += amp * amp / 2
            }
            best = max(best, sqrt(power))
            f += 0.25
        }
        return best
    }
}
