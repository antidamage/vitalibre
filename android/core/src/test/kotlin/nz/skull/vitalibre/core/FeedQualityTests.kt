package nz.skull.vitalibre.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One synthetic recording: camera samples and the phone's motion samples, with beats at known times. */
class Recording(var samples: List<PPGSample>, var motion: List<MotionSample>, val beatTimes: List<Double>)

/** Beat onsets and relative amplitudes for a rhythm. */
class Rhythmic(val times: List<Double>, val amps: List<Double>)

object Corpus {
    const val SECONDS = 15.0
    const val FPS = 30.0
    const val NOISE_SIGMA = 0.0433 // uniform +/-0.075, the generator the older tests use

    fun regular(bpm: Double, seconds: Double = SECONDS): Rhythmic {
        val dt = 60.0 / bpm
        val t = generateSequence(0.2) { it + dt }.takeWhile { it < seconds - 0.3 }.toList()
        return Rhythmic(t, t.map { 1.0 })
    }

    /** Respiratory sinus arrhythmia: the interval swings +/-12% at 0.25 Hz. */
    fun sinusArrhythmia(bpm: Double): Rhythmic {
        val t = ArrayList<Double>()
        var x = 0.2
        while (x < SECONDS - 0.3) { t.add(x); x += 60.0 / bpm * (1 + 0.12 * sin(2 * PI * 0.25 * x)) }
        return Rhythmic(t, t.map { 1.0 })
    }

    /** Intervals drawn with a coefficient of variation near 0.25. */
    fun afLike(bpm: Double, seed: Long = 5): Rhythmic {
        val r = Random(seed)
        val mean = 60.0 / bpm
        val t = ArrayList<Double>()
        var x = 0.2
        while (x < SECONDS - 0.3) { t.add(x); x += (mean * (1 + 0.25 * r.nextGaussian())).coerceIn(0.35, 1.6) }
        return Rhythmic(t, t.map { 1.0 })
    }

    /** Every tenth beat arrives early, at 60% of the interval, and smaller. */
    fun premature(bpm: Double): Rhythmic {
        val dt = 60.0 / bpm
        val t = ArrayList<Double>()
        val a = ArrayList<Double>()
        var x = 0.2
        var n = 0
        while (x < SECONDS - 0.3) {
            n++
            if (n % 10 == 0) { t.add(x - 0.4 * dt); a.add(0.5) } else { t.add(x); a.add(1.0) }
            x += dt
        }
        return Rhythmic(t, a)
    }

    /** `pulse` scales the pulse (1.0 is the 1.2-unit swing of the older generator). */
    fun record(rhythm: Rhythmic, pulse: Double = 1.0, noiseSigma: Double = NOISE_SIGMA, seed: Long = 7): Recording {
        val rng = Random(seed)
        val n = (SECONDS * FPS).toInt()
        val samples = (0 until n).map { i ->
            val t = i / FPS
            var p = 0.0
            for ((k, b) in rhythm.times.withIndex()) {
                val u = t - b
                if (u < 0 || u > 0.7) continue
                p += rhythm.amps[k] * (exp(-((u - 0.18) / 0.07).pow(2)) + 0.35 * exp(-((u - 0.45) / 0.09).pow(2)))
            }
            val g = 60 - 1.2 * pulse * p + 0.4 * sin(t * 0.2) + noiseSigma * rng.nextGaussian()
            PPGSample(t, 210.0, g, 20.0, 0.1, 0.95, false)
        }
        // A phone resting on a table: a little sensor noise, at 100 Hz.
        val mrng = Random(seed + 1)
        val motion = (0 until (SECONDS * 100).toInt()).map { i ->
            MotionSample(i / 100.0, 0.004 * mrng.nextGaussian(), 0.004 * mrng.nextGaussian(), 0.004 * mrng.nextGaussian(),
                0.01 * mrng.nextGaussian(), 0.01 * mrng.nextGaussian(), 0.01 * mrng.nextGaussian())
        }
        return Recording(samples, motion, rhythm.times)
    }

    fun series(r: Recording) = FeedQuality.series(r.samples, r.motion, 0.0, r.samples.last().t)

    // Faults, each over [from, to) seconds of a recording.

    fun noise(r: Recording, from: Double, to: Double, sigma: Double = 0.5, seed: Long = 3): Recording {
        val rng = Random(seed)
        r.samples = r.samples.map { if (it.t in from..to) it.copy(g = it.g + sigma * rng.nextGaussian()) else it }
        return r
    }

    fun dcStep(r: Recording, at: Double, by: Double = 6.0): Recording {
        r.samples = r.samples.map { if (it.t >= at) it.copy(g = it.g + by) else it }
        return r
    }

    fun clipping(r: Recording, from: Double, to: Double): Recording {
        r.samples = r.samples.map { if (it.t in from..to) it.copy(saturated = 0.7) else it }
        return r
    }

    fun droppedFrames(r: Recording, from: Double, to: Double, share: Double = 0.4, seed: Long = 9): Recording {
        val rng = Random(seed)
        r.samples = r.samples.filter { !(it.t in from..to && rng.nextDouble() < share) }
        return r
    }

    fun partialCover(r: Recording, from: Double, to: Double): Recording {
        r.samples = r.samples.map { if (it.t in from..to) it.copy(coverage = 0.4) else it }
        return r
    }

    fun exposureStep(r: Recording, at: Double): Recording {
        r.samples = r.samples.map { if (abs(it.t - at) < 0.02) it.copy(event = true) else it }
        return r
    }

    fun shake(r: Recording, from: Double, to: Double, hz: Double, gyro: Double, accel: Double): Recording {
        r.motion = r.motion.map {
            if (it.t !in from..to) it else {
                val s = sin(2 * PI * hz * it.t)
                it.copy(gx = it.gx + gyro * s, ay = it.ay + accel * s)
            }
        }
        return r
    }

    /** Mean of the series from the first full window on. */
    fun settledMean(points: List<FeedQuality.Point>) = FeedQuality.mean(points.filter { it.t >= 3 })
}

class FeedQualityTests {
    private fun clean(rhythm: Rhythmic, pulse: Double = 1.0) = Corpus.series(Corpus.record(rhythm, pulse)).filter { it.t >= 3 }

    @Test fun aCleanFeedIsGood() {
        val s = clean(Corpus.regular(72.0))
        assertTrue(s.all { it.value >= FeedQuality.GOOD && it.cause == FeedCause.NONE }, s.toString())
    }

    /** The point of the index: the heart can do what it likes and the feed score does not move. */
    @Test fun physiologyDoesNotMoveTheIndex() {
        val base = FeedQuality.mean(clean(Corpus.regular(72.0)))
        val variants = mapOf(
            "sinus arrhythmia" to Corpus.sinusArrhythmia(72.0),
            "AF-like" to Corpus.afLike(75.0),
            "premature beats" to Corpus.premature(72.0),
            "HR 45" to Corpus.regular(45.0),
            "HR 120" to Corpus.regular(120.0),
            "HR 180" to Corpus.regular(180.0),
        )
        for ((name, r) in variants) {
            val s = clean(r)
            assertTrue(abs(FeedQuality.mean(s) - base) < 0.1, "$name moved the index to ${FeedQuality.mean(s)} from $base")
            assertTrue(s.all { it.value >= FeedQuality.GOOD }, "$name dropped below good: ${s.map { it.value }}")
        }
    }

    @Test fun pulseStrengthDoesNotMoveTheIndex() {
        val base = FeedQuality.mean(clean(Corpus.regular(72.0)))
        for (amp in listOf(0.1, 0.25, 0.5, 1.0)) {
            val s = clean(Corpus.regular(72.0), amp)
            assertTrue(abs(FeedQuality.mean(s) - base) < 0.1, "pulse $amp moved the index to ${FeedQuality.mean(s)} from $base")
        }
    }

    private fun assertFault(name: String, cause: FeedCause, fault: (Recording) -> Recording) {
        val base = FeedQuality.mean(clean(Corpus.regular(72.0)))
        val s = Corpus.series(fault(Corpus.record(Corpus.regular(72.0)))).filter { it.t in 7.0..11.0 }
        val worst = s.minBy { it.value }
        assertTrue(worst.value <= base - 0.4, "$name only lowered the index to ${worst.value} (clean $base): ${s.map { it.value }}")
        assertEquals(cause, worst.cause, "$name named ${worst.cause}")
    }

    @Test fun noiseLowersIt() = assertFault("noise", FeedCause.NOISE) { Corpus.noise(it, 6.0, 9.0) }
    @Test fun aMotionStepLowersIt() = assertFault("dc step", FeedCause.STEPS) { Corpus.dcStep(it, 7.5) }
    @Test fun clippingLowersIt() = assertFault("clipping", FeedCause.CLIPPING) { Corpus.clipping(it, 6.0, 9.0) }
    @Test fun droppedFramesLowerIt() = assertFault("dropped frames", FeedCause.FRAMES) { Corpus.droppedFrames(it, 6.0, 9.0) }
    @Test fun partialCoverLowersIt() = assertFault("partial cover", FeedCause.COVERAGE) { Corpus.partialCover(it, 6.0, 9.0) }
    @Test fun anExposureStepLowersIt() = assertFault("exposure event", FeedCause.ADJUSTING) { Corpus.exposureStep(it, 7.5) }
    @Test fun tremorLowersIt() = assertFault("tremor", FeedCause.TREMOR) { Corpus.shake(it, 6.0, 9.0, hz = 8.0, gyro = 0.2, accel = 0.4) }
    @Test fun movementLowersIt() = assertFault("movement", FeedCause.MOTION) { Corpus.shake(it, 6.0, 9.0, hz = 1.0, gyro = 0.6, accel = 1.0) }

    @Test fun aFaultIsNotBlamedOnTheHeart() {
        // A noisy stretch on an irregular rhythm is still named a noise fault.
        val r = Corpus.noise(Corpus.record(Corpus.afLike(75.0)), 6.0, 9.0)
        val worst = Corpus.series(r).filter { it.t in 7.0..11.0 }.minBy { it.value }
        assertEquals(FeedCause.NOISE, worst.cause)
    }

    @Test fun withoutMotionSensorsTheFeedStillScores() {
        val r = Corpus.record(Corpus.regular(72.0))
        r.motion = emptyList()
        assertTrue(Corpus.series(r).filter { it.t >= 3 }.all { it.value >= FeedQuality.GOOD })
    }
}

class ExclusionTests {
    private val model = testModel()

    private fun analyse(rhythm: Rhythmic, mutate: (Recording) -> Unit = {}): ScanResult {
        val rec = Corpus.record(rhythm)
        mutate(rec)
        val session = ScanSession()
        rec.samples.forEach(session::add)
        rec.motion.forEach(session::addMotion)
        val out = session.analyse(model, null, Sex.UNSPECIFIED)
        return (out as ScanOutcome.Success).result
    }

    private fun recompute(r: ScanResult, cuts: List<ExcludedRange>) =
        ReadingEdit.recompute(r.trace, r.traceStart, cuts, model, null, Sex.UNSPECIFIED, null, BPCalibration())

    @Test fun theResultCarriesItsFeedSeries() {
        val r = analyse(Corpus.regular(72.0))
        assertTrue(r.feed.isNotEmpty())
        assertTrue(r.feed.all { it.value in 0.0..1.0 })
    }

    @Test fun excludingAnInjectedFaultKeepsTheRateTrue() {
        // Seven to ten seconds is replaced by a burst of noise that would otherwise add false beats.
        val bad = analyse(Corpus.regular(72.0)) { Corpus.noise(it, 7.0, 10.0, sigma = 1.5) }
        val fixed = recompute(bad, listOf(ExcludedRange(6.5, 10.5)))
        assertTrue(fixed is ReadingEdit.Result.Ok, fixed.toString())
        val o = (fixed as ReadingEdit.Result.Ok).outcome
        assertTrue(abs(o.heartRate - 72.0) <= 2.0, "rate ${o.heartRate}")
        assertEquals(Rhythm.STEADY, o.rhythm)
        assertTrue(abs(o.usedSeconds - ((bad.traceEnd - bad.traceStart) - 4.0)) < 0.2)
    }

    @Test fun anIntervalIsNeverTakenAcrossACut() {
        fun beats(from: Double, n: Int) = (0 until n).map { Beat((it * 60).toInt(), from + it, 1.0) }
        val a = beats(0.0, 6)          // 0..5 s
        val b = beats(6.8, 6)          // 6.8..11.8 s: the cut leaves 1.8 s between 5.0 and 6.8
        val r = HeartRate.estimateGroups(listOf(a, b)) as HeartRateResult.Ok
        assertEquals(10, r.estimate.intervals.size)
        assertTrue(r.estimate.intervals.none { abs(it - 1.8) < 0.01 })
        // Run as one group, the same beats would count the false 1.8 s interval.
        val joined = HeartRate.estimate(a + b) as HeartRateResult.Ok
        assertTrue(joined.estimate.intervals.any { abs(it - 1.8) < 0.01 })
    }

    @Test fun tooLittleSignalLeftIsRefused() {
        val r = analyse(Corpus.regular(72.0))
        assertEquals(ReadingEdit.Result.TooLittleSignalLeft, recompute(r, listOf(ExcludedRange(2.0, 11.0))))
    }

    @Test fun overlappingRangesAreMergedAndClipped() {
        val m = ReadingEdit.merged(listOf(ExcludedRange(5.0, 7.0), ExcludedRange(6.0, 9.0), ExcludedRange(-3.0, 0.5), ExcludedRange(20.0, 25.0)), 1.0, 14.0)
        assertEquals(listOf(ExcludedRange(5.0, 9.0)), m)
    }

    @Test fun guidanceNamesATremor() {
        val rec = Corpus.shake(Corpus.record(Corpus.regular(72.0)), 4.0, 10.0, hz = 8.0, gyro = 0.2, accel = 0.4)
        val session = ScanSession()
        for (s in rec.samples.filter { it.t <= 8.0 }) session.add(s)
        for (m in rec.motion.filter { it.t <= 8.0 }) session.addMotion(m)
        assertEquals(FeedCause.TREMOR, session.guidance.cause)
    }
}
