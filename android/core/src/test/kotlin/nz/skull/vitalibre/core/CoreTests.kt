package nz.skull.vitalibre.core

import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** A synthetic finger PPG: raw green falls at each systole, with a dicrotic notch, plus noise and drift. */
fun syntheticSamples(bpm: Double, seconds: Double, fps: Double = 30.0, noise: Double = 0.15, covered: Boolean = true): List<PPGSample> {
    val rng = Random(7)
    val f = bpm / 60
    return (0 until (seconds * fps).toInt()).map { i ->
        val t = i / fps
        val phase = (t * f) % 1.0
        val systole = exp(-((phase - 0.18) / 0.07).pow(2))
        val dicrotic = 0.35 * exp(-((phase - 0.45) / 0.09).pow(2))
        val g = 60 - 1.2 * (systole + dicrotic) + 0.4 * sin(t * 0.2) + noise * (rng.nextDouble() - 0.5)
        PPGSample(t, if (covered) 210.0 else 40.0, if (covered) g else 35.0, 20.0, 0.1)
    }
}

fun testModel() = BPModel(
    "test", false, 120.0, 78.0, 0.5, 0.15, 30.0, 2.0, 1.0,
    listOf(BPTerm("heartRate", 70.0, 12.0, 100.0, 100.0)), 8.0, 5.0, 14.0, 9.0,
)

private fun near(expected: Double, actual: Double, tol: Double, msg: String = "") =
    assertTrue(abs(expected - actual) <= tol, "$msg expected $expected but was $actual")

class GeometryTests {
    @Test fun ringIsOneAndAHalfTimesTheReferenceChannel() {
        near(1.5 * OrbGeometry.referenceChannelWidth, OrbGeometry.ringWidth, 1e-12)
        near(0.228, OrbGeometry.ringWidth, 1e-9)
    }

    @Test fun bandRadii() {
        near(0.966, OrbGeometry.ringOuter, 1e-9)
        near(0.738, OrbGeometry.ringInner, 1e-9)
        near(0.685, OrbGeometry.domeOuter, 1e-9)
    }

    @Test fun sweepIsOneRevolutionPerReading() {
        val period = ScanSession.TARGET_SECONDS * 1000
        assertEquals(period, OrbGeometry.sweepPeriodMs)
        near(0.0, OrbGeometry.sweepAngle(0.0), 1e-12)
        near(PI / 2, OrbGeometry.sweepAngle(period / 4), 1e-12)
        near(PI, OrbGeometry.sweepAngle(period / 2), 1e-12)
        near(0.0, OrbGeometry.sweepAngle(period), 1e-12)
        near(PI / 2, OrbGeometry.sweepAngle(period * 1.25), 1e-9)
        near(3 * PI / 2, OrbGeometry.sweepAngle(-period / 4), 1e-9)
    }

    @Test fun arcRadiusStaysInsideTheRing() {
        var a = -3.0
        while (a <= 3.0) {
            val r = OrbGeometry.arcRadius(a)
            assertTrue(r > OrbGeometry.ringInner && r < OrbGeometry.ringOuter)
            a += 0.25
        }
    }
}

class FilterTests {
    @Test fun bandpassKeepsPulseAndRemovesDrift() {
        val fs = 60.0
        val x = DoubleArray((20 * fs).toInt()) { i ->
            val t = i / fs
            sin(2 * PI * 1.2 * t) + 3 * sin(2 * PI * 0.05 * t)
        }
        val y = Filters.bandpass(x, fs)
        val mid = y.copyOfRange((5 * fs).toInt(), (15 * fs).toInt())
        near(1 / Math.sqrt(2.0), Stats.std(mid), 0.12)
    }

    @Test fun filtfiltIsZeroPhase() {
        val fs = 60.0
        val x = DoubleArray((20 * fs).toInt()) { sin(2 * PI * 1.0 * it / fs) }
        val y = Filters.bandpass(x, fs)
        val mid = (10 * fs).toInt()
        val peak = (mid until mid + 60).maxByOrNull { y[it] }!!
        near(10.25, peak / fs, 0.03)
    }

    @Test fun resampleUniform() {
        val r = Filters.resample(doubleArrayOf(0.0, 0.03, 0.07, 0.10), doubleArrayOf(0.0, 3.0, 7.0, 10.0), 100.0)
        assertEquals(11, r.size)
        near(5.0, r[5], 1e-9)
    }
}

class DetectorTests {
    private fun heartRate(bpm: Double): Double? {
        val s = syntheticSamples(bpm, 20.0)
        val raw = Filters.resample(DoubleArray(s.size) { s[it].t }, DoubleArray(s.size) { s[it].g }, 60.0)
        val y = Filters.bandpass(DoubleArray(raw.size) { -raw[it] }, 60.0)
        val r = HeartRate.estimate(BeatDetector.detect(y, 60.0))
        return (r as? HeartRateResult.Ok)?.estimate?.bpm
    }

    @Test fun knownRates() {
        for (bpm in listOf(48.0, 60.0, 72.0, 90.0, 110.0, 140.0)) {
            val est = heartRate(bpm)
            assertNotNull(est, "no estimate at $bpm")
            near(bpm, est, 2.0, "at $bpm")
        }
    }

    @Test fun tooFewBeats() {
        val beats = (0 until 4).map { Beat(it * 60, it.toDouble(), 1.0) }
        assertEquals(HeartRateResult.Fail(HeartRateFailure.TooFewBeats(3)), HeartRate.estimate(beats))
    }

    @Test fun intervalsOutsideBandDropped() {
        val beats = (0 until 30).map { Beat(it, it * 0.2, 1.0) }
        assertFalse(HeartRate.estimate(beats) is HeartRateResult.Ok, "accepted 300 bpm")
    }

    @Test fun driftingRhythmRejected() {
        var t = 0.0
        val beats = (0 until 24).map { i -> t += if (i < 12) 0.6 else 1.0; Beat(i, t, 1.0) }
        assertEquals(HeartRateResult.Fail(HeartRateFailure.Unstable), HeartRate.estimate(beats))
    }

    @Test fun outlierAmplitudeDropped() {
        val beats = (0 until 20).map { Beat(it, it * 0.8, if (it == 10) 9.0 else 1.0) }
        val r = HeartRate.estimate(beats) as? HeartRateResult.Ok ?: fail("no estimate")
        near(75.0, r.estimate.bpm, 0.5)
    }
}

class SessionTests {
    private fun run(s: List<PPGSample>): ScanOutcome {
        val session = ScanSession()
        s.forEach(session::add)
        return session.analyse(testModel(), null, Sex.UNSPECIFIED)
    }

    @Test fun cleanScanGivesResult() {
        val r = (run(syntheticSamples(72.0, 16.0)) as? ScanOutcome.Success ?: fail("no result")).result
        near(72.0, r.heartRate, 2.0)
        assertNotEquals(QualityLevel.POOR, r.level)
        assertTrue(r.bp.systolicLow < r.bp.systolicHigh)
    }

    @Test fun uncoveredIsAnError() {
        assertEquals(ScanOutcome.Failure(ScanFailure.NotCovered), run(syntheticSamples(72.0, 16.0, covered = false)))
    }

    @Test fun tooShortIsAnError() {
        assertTrue((run(syntheticSamples(72.0, 5.0)) as? ScanOutcome.Failure)?.failure is ScanFailure.TooShort)
    }

    @Test fun finishesAtTarget() {
        val session = ScanSession()
        var stoppedAt: Double? = null
        for (s in syntheticSamples(72.0, 30.0)) {
            session.add(s)
            if (session.finished) { stoppedAt = s.t; break }
        }
        near(ScanSession.TARGET_SECONDS, stoppedAt ?: 0.0, 0.1)
    }

    @Test fun guidanceWhenUncovered() {
        val session = ScanSession()
        syntheticSamples(72.0, 2.0, covered = false).forEach(session::add)
        assertEquals(Guidance.COVER_LENS, session.guidance)
    }

    @Test fun liveReadoutTracksTheRateAndFindsBeats() {
        val session = ScanSession()
        syntheticSamples(72.0, 10.0).forEach(session::add)
        val live = session.live(testModel(), null, Sex.UNSPECIFIED, null, BPCalibration())
        assertNotNull(live)
        near(72.0, live.heartRate ?: 0.0, 3.0)
        assertNotNull(live.bp)
        assertNotNull(live.lastBeat)
        assertTrue((live.lastBeat ?: 99.0) <= live.traceEnd)
    }

    @Test fun nothingLiveBeforeTheSignalSettles() {
        val session = ScanSession()
        syntheticSamples(72.0, 2.5).forEach(session::add)
        assertNull(session.live(testModel(), null, Sex.UNSPECIFIED, null, BPCalibration()))
    }

    @Test fun finalResultCarriesTheWholeTrace() {
        val r = (run(syntheticSamples(72.0, 16.0)) as ScanOutcome.Success).result
        assertTrue(r.trace.size > 600)
        assertTrue(r.trace.all { abs(it) <= 1 })
        assertTrue(r.traceEnd > 12)
    }
}

class EstimatorTests {
    private fun features(hr: Double) = BPFeatures(hr, 0.03, 0.3, 0.6, 0.15)

    @Test fun adjustmentIsCapped() {
        val hi = BPEstimator.estimate(features(200.0), testModel(), null, Sex.UNSPECIFIED)
        val lo = BPEstimator.estimate(features(30.0), testModel(), null, Sex.UNSPECIFIED)
        assertEquals(128, hi.systolic); assertEquals(112, lo.systolic)
        assertEquals(83, hi.diastolic); assertEquals(73, lo.diastolic)
    }

    @Test fun uncalibratedIsAlwaysARange() {
        val r = BPEstimator.estimate(features(70.0), testModel(), 45, Sex.MALE)
        assertEquals(28, r.systolicHigh - r.systolicLow)
        assertEquals(18, r.diastolicHigh - r.diastolicLow)
        assertTrue(r.text.contains("–"))
    }

    @Test fun ageMovesThePrior() {
        val base = BPEstimator.estimate(features(70.0), testModel(), null, Sex.UNSPECIFIED)
        val older = BPEstimator.estimate(features(70.0), testModel(), 70, Sex.UNSPECIFIED)
        assertTrue(older.systolic > base.systolic)
    }

    @Test fun usualBloodPressureReplacesThePrior() {
        val usual = UsualBP(130, 84)
        val raw = BPEstimator.raw(features(70.0), testModel(), 70, Sex.MALE, usual)
        near(130.0, raw.first, 1e-9); near(84.0, raw.second, 1e-9)
        assertEquals("130 / 84", BPEstimator.estimate(features(70.0), testModel(), 70, Sex.MALE, usual).text)
    }

    @Test fun implausibleUsualIsIgnored() {
        near(120.0, BPEstimator.raw(features(70.0), testModel(), null, Sex.UNSPECIFIED, UsualBP(30, 20)).first, 1e-9)
    }
}

class CalibrationTests {
    private fun features() = BPFeatures(70.0, 0.03, 0.3, 0.6, 0.15)
    private fun point(rs: Double, rd: Double, cs: Double, cd: Double) = CalibrationPoint(rs, rd, cs, cd, 0.0)

    @Test fun oneCuffReadingMovesTheEstimateOntoIt() {
        val raw = BPEstimator.raw(features(), testModel(), null, Sex.UNSPECIFIED)
        val cal = BPCalibration().with(point(raw.first, raw.second, 141.0, 91.0))
        val r = BPEstimator.estimate(features(), testModel(), null, Sex.UNSPECIFIED, calibration = cal)
        assertEquals(141, r.systolic); assertEquals(91, r.diastolic)
        assertEquals(28, r.systolicHigh - r.systolicLow)
        assertEquals("141 / 91", r.text)
    }

    @Test fun rangeNarrowsToObservedErrorAfterThreePoints() {
        var cal = BPCalibration()
        for ((rs, cs) in listOf(118.0 to 130.0, 121.0 to 132.0, 119.0 to 129.0)) cal = cal.with(point(rs, 78.0, cs, 84.0))
        val w = cal.halfWidths(14.0, 9.0)
        assertTrue(w.first < 14 && w.first >= BPCalibration.MIN_HALF_WIDTH_SYSTOLIC)
        near(11.0, cal.offset.first, 0.001)
    }

    @Test fun implausibleCuffReadingsAreRejected() {
        var cal = BPCalibration()
        cal = cal.with(point(118.0, 76.0, 60.0, 40.0))
        cal = cal.with(point(118.0, 76.0, 120.0, 118.0))
        cal = cal.with(point(118.0, 76.0, 300.0, 90.0))
        assertEquals(0, cal.count)
    }

    @Test fun offsetIsCapped() {
        assertEquals(BPCalibration.MAX_OFFSET, BPCalibration().with(point(60.0, 40.0, 200.0, 120.0)).offset.first)
    }
}

/** The bundled model file and the in-code fallback must be the same numbers. */
class ProjectRuleTests {
    private val root = File("../..").canonicalFile

    @Test fun bundledModelMatchesFallback() {
        val text = File(root, "App/Resources/bp-model.json").readText()
        assertEquals(BPModel.prior1, BPModel.fromJson(text))
    }

    @Test fun noPublisherIdentifiersInAppSource() {
        val cfg = MiniJson.parse(File(root, "publisher/config/store.config.json").readText()) as Map<*, *>
        val secrets = mutableListOf(cfg["bundleId"] as String)
        for (p in cfg["donations"] as List<*>) secrets.add((p as Map<*, *>)["productId"] as String)
        // The Android application id is the publisher's bundle id; it is set in the build file, not in source.
        File(root, "android/app/src").walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            for (s in secrets) assertFalse(text.contains(s), "${f.name} contains publisher value $s")
        }
    }
}
