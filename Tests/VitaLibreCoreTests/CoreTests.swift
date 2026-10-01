import XCTest
@testable import VitaLibreCore

/// A synthetic finger PPG: raw green falls at each systole (blood absorbs green),
/// with a dicrotic notch, on a DC level, plus noise and slow drift.
func syntheticSamples(bpm: Double, seconds: Double, fps: Double = 30, noise: Double = 0.15,
                      covered: Bool = true, seed: UInt64 = 7) -> [PPGSample] {
    var rng = SeededRNG(seed: seed)
    let f = bpm / 60
    return (0..<Int(seconds * fps)).map { i in
        let t = Double(i) / fps
        let phase = (t * f).truncatingRemainder(dividingBy: 1)
        let systole = exp(-pow((phase - 0.18) / 0.07, 2))
        let dicrotic = 0.35 * exp(-pow((phase - 0.45) / 0.09, 2))
        let pulse = systole + dicrotic
        let g = 60 - 1.2 * pulse + 0.4 * sin(t * 0.2) + noise * (rng.next() - 0.5)
        return PPGSample(t: t, r: covered ? 210 : 40, g: covered ? g : 35, b: 20, saturated: 0.1)
    }
}

struct SeededRNG {
    var state: UInt64
    init(seed: UInt64) { state = seed &* 6364136223846793005 &+ 1442695040888963407 }
    mutating func next() -> Double {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return Double(state >> 11) / Double(1 << 53)
    }
}

func testModel() -> BPModel {
    BPModel(version: "test", validated: false, baseSystolic: 120, baseDiastolic: 78,
            ageSystolicPerYear: 0.5, ageDiastolicPerYear: 0.15, referenceAge: 30,
            maleSystolicOffset: 2, maleDiastolicOffset: 1,
            terms: [.init(feature: "heartRate", mean: 70, scale: 12, systolic: 100, diastolic: 100)],
            maxSystolicAdjust: 8, maxDiastolicAdjust: 5, halfWidthSystolic: 14, halfWidthDiastolic: 9)
}

final class GeometryTests: XCTestCase {
    func testRingIsOneAndAHalfTimesTheReferenceChannel() {
        XCTAssertEqual(OrbGeometry.ringWidth, 1.5 * OrbGeometry.referenceChannelWidth, accuracy: 1e-12)
        XCTAssertEqual(OrbGeometry.ringWidth, 0.228, accuracy: 1e-9)
    }

    func testBandRadii() {
        XCTAssertEqual(OrbGeometry.ringOuter, 0.966, accuracy: 1e-9)
        XCTAssertEqual(OrbGeometry.ringInner, 0.738, accuracy: 1e-9)
        XCTAssertEqual(OrbGeometry.domeOuter, 0.685, accuracy: 1e-9)
        XCTAssertLessThan(OrbGeometry.domeOuter, OrbGeometry.ringInner)
    }

    func testSweepIsOneRevolutionPerReading() {
        let period = ScanSession.targetSeconds * 1000
        XCTAssertEqual(OrbGeometry.sweepPeriodMs, period)
        XCTAssertEqual(OrbGeometry.sweepAngle(atMs: 0), 0, accuracy: 1e-12)
        XCTAssertEqual(OrbGeometry.sweepAngle(atMs: period / 4), .pi / 2, accuracy: 1e-12)
        XCTAssertEqual(OrbGeometry.sweepAngle(atMs: period / 2), .pi, accuracy: 1e-12)
        XCTAssertEqual(OrbGeometry.sweepAngle(atMs: period), 0, accuracy: 1e-12)
        XCTAssertEqual(OrbGeometry.sweepAngle(atMs: period * 1.25), .pi / 2, accuracy: 1e-9)
        XCTAssertEqual(OrbGeometry.sweepAngle(atMs: -period / 4), 3 * .pi / 2, accuracy: 1e-9)
    }

    func testArcRadiusStaysInsideTheRing() {
        for a in stride(from: -3.0, through: 3.0, by: 0.25) {
            let r = OrbGeometry.arcRadius(amplitude: a)
            XCTAssertGreaterThan(r, OrbGeometry.ringInner)
            XCTAssertLessThan(r, OrbGeometry.ringOuter)
        }
    }

    func testGridCirclesInsideRing() {
        let radii = OrbGeometry.gridCircleRadii
        XCTAssertFalse(radii.isEmpty)
        XCTAssertTrue(radii.allSatisfy { $0 > OrbGeometry.ringInner && $0 < OrbGeometry.ringOuter })
    }
}

final class FilterTests: XCTestCase {
    func testBandpassKeepsPulseAndRemovesDrift() {
        let fs = 60.0
        let x = (0..<Int(20 * fs)).map { i -> Double in
            let t = Double(i) / fs
            return sin(2 * .pi * 1.2 * t) + 3 * sin(2 * .pi * 0.05 * t)
        }
        let y = Filters.bandpass(x, fs: fs)
        let mid = Array(y[Int(5 * fs)..<Int(15 * fs)])
        XCTAssertEqual(Stats.std(mid), 1 / sqrt(2), accuracy: 0.12)
    }

    func testFiltfiltIsZeroPhase() {
        let fs = 60.0
        let x = (0..<Int(20 * fs)).map { sin(2 * .pi * 1.0 * Double($0) / fs) }
        let y = Filters.bandpass(x, fs: fs)
        let mid = Int(10 * fs)
        // Peak of a 1 Hz sine at t = 10.25 s must not move.
        let peak = (mid..<mid + 60).max { y[$0] < y[$1] }!
        XCTAssertEqual(Double(peak) / fs, 10.25, accuracy: 0.03)
    }

    func testResampleUniform() {
        let t = [0.0, 0.03, 0.07, 0.10], v = [0.0, 3.0, 7.0, 10.0]
        let r = Filters.resample(times: t, values: v, rate: 100)
        XCTAssertEqual(r.count, 11)
        XCTAssertEqual(r[5], 5, accuracy: 1e-9)
    }
}

final class DetectorTests: XCTestCase {
    func heartRate(bpm: Double, seconds: Double = 20) -> Double? {
        let s = syntheticSamples(bpm: bpm, seconds: seconds)
        let raw = Filters.resample(times: s.map(\.t), values: s.map(\.g), rate: 60)
        let y = Filters.bandpass(raw.map { -$0 }, fs: 60)
        let beats = BeatDetector.detect(y, fs: 60)
        if case .success(let hr) = HeartRate.estimate(beats: beats) { return hr.bpm }
        return nil
    }

    func testKnownRates() {
        for bpm in [48.0, 60, 72, 90, 110, 140] {
            let est = heartRate(bpm: bpm)
            XCTAssertNotNil(est, "no estimate at \(bpm)")
            XCTAssertEqual(est ?? 0, bpm, accuracy: 2.0, "at \(bpm)")
        }
    }

    func testTooFewBeats() {
        let beats = (0..<4).map { Beat(index: $0 * 60, time: Double($0), amplitude: 1) }
        XCTAssertEqual(HeartRate.estimate(beats: beats), .failure(.tooFewBeats(3)))
    }

    func testIntervalsOutsideBandDropped() {
        // Beats every 0.2 s (300 bpm): every interval is below 0.33 s.
        let beats = (0..<30).map { Beat(index: $0, time: Double($0) * 0.2, amplitude: 1) }
        if case .success = HeartRate.estimate(beats: beats) { XCTFail("accepted 300 bpm") }
    }

    func testDriftingRhythmRejected() {
        var t = 0.0
        let beats = (0..<24).map { i -> Beat in
            t += i < 12 ? 0.6 : 1.0
            return Beat(index: i, time: t, amplitude: 1)
        }
        XCTAssertEqual(HeartRate.estimate(beats: beats), .failure(.unstable))
    }

    func testOutlierAmplitudeDropped() {
        var beats = (0..<20).map { Beat(index: $0, time: Double($0) * 0.8, amplitude: 1) }
        beats[10].amplitude = 9
        guard case .success(let hr) = HeartRate.estimate(beats: beats) else { return XCTFail() }
        XCTAssertEqual(hr.bpm, 75, accuracy: 0.5)
    }
}

final class SessionTests: XCTestCase {
    func run(_ s: [PPGSample]) -> Result<ScanResult, ScanFailure> {
        var session = ScanSession()
        s.forEach { session.add($0) }
        return session.analyse(model: testModel(), age: nil, sex: .unspecified)
    }

    func testCleanScanGivesResult() {
        guard case .success(let r) = run(syntheticSamples(bpm: 72, seconds: 16)) else { return XCTFail() }
        XCTAssertEqual(r.heartRate, 72, accuracy: 2)
        XCTAssertNotEqual(r.level, .poor)
        XCTAssertLessThan(r.bp.systolicLow, r.bp.systolicHigh)
    }

    func testUncoveredIsAnError() {
        XCTAssertEqual(run(syntheticSamples(bpm: 72, seconds: 16, covered: false)), .failure(.notCovered))
    }

    func testTooShortIsAnError() {
        guard case .failure(.tooShort) = run(syntheticSamples(bpm: 72, seconds: 5)) else { return XCTFail() }
    }

    func testNoiseOnlyIsNeverANumber() {
        let s = syntheticSamples(bpm: 72, seconds: 16, noise: 40)
        if case .success(let r) = run(s) { XCTAssertNotEqual(r.level, .poor); XCTAssertEqual(r.heartRate, 72, accuracy: 6) }
    }

    func testFinishesAtTarget() {
        var session = ScanSession()
        var stoppedAt: Double?
        for s in syntheticSamples(bpm: 72, seconds: 30) {
            session.add(s)
            if session.finished { stoppedAt = s.t; break }
        }
        XCTAssertEqual(stoppedAt ?? 0, ScanSession.targetSeconds, accuracy: 0.1)
    }

    func testGuidanceWhenUncovered() {
        var session = ScanSession()
        syntheticSamples(bpm: 72, seconds: 2, covered: false).forEach { session.add($0) }
        XCTAssertEqual(session.guidance, .coverLens)
    }
}

final class EstimatorTests: XCTestCase {
    func features(hr: Double) -> BPFeatures {
        BPFeatures(heartRate: hr, intervalCV: 0.03, crestFraction: 0.3, skewness: 0.6, reflectionIndex: 0.15)
    }

    func testAdjustmentIsCapped() {
        let hi = BPEstimator.estimate(features(hr: 200), model: testModel(), age: nil, sex: .unspecified)
        let lo = BPEstimator.estimate(features(hr: 30), model: testModel(), age: nil, sex: .unspecified)
        XCTAssertEqual(hi.systolic, 128)
        XCTAssertEqual(lo.systolic, 112)
        XCTAssertEqual(hi.diastolic, 83)
        XCTAssertEqual(lo.diastolic, 73)
    }

    func testRangeIsAlwaysARange() {
        let r = BPEstimator.estimate(features(hr: 70), model: testModel(), age: 45, sex: .male)
        XCTAssertEqual(r.systolicHigh - r.systolicLow, 28)
        XCTAssertEqual(r.diastolicHigh - r.diastolicLow, 18)
        XCTAssertTrue(r.text.contains("–"), "uncalibrated stays a range")
    }

    func testAgeAndSexMoveThePrior() {
        let base = BPEstimator.estimate(features(hr: 70), model: testModel(), age: nil, sex: .unspecified)
        let older = BPEstimator.estimate(features(hr: 70), model: testModel(), age: 70, sex: .unspecified)
        XCTAssertGreaterThan(older.systolic, base.systolic)
    }
}

final class ProjectRuleTests: XCTestCase {
    var root: URL { URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent() }

    func testBundledModelMatchesFallback() throws {
        let data = try Data(contentsOf: root.appendingPathComponent("App/Resources/bp-model.json"))
        XCTAssertEqual(try BPModel.load(from: data), BPModel.prior1)
    }

    /// The forkability rule: publisher identifiers live in publisher/config, never in app code.
    func testNoPublisherIdentifiersInAppSource() throws {
        let cfgData = try Data(contentsOf: root.appendingPathComponent("publisher/config/store.config.json"))
        let cfg = try JSONSerialization.jsonObject(with: cfgData) as! [String: Any]
        var secrets: [String] = [cfg["bundleId"] as! String]
        for p in cfg["donations"] as! [[String: Any]] { secrets.append(p["productId"] as! String) }
        for key in ["supportEmail", "privacyURL", "sourceURL"] {
            if let v = cfg[key] as? String, !v.isEmpty { secrets.append(v) }
        }
        let appDir = root.appendingPathComponent("App")
        let files = FileManager.default.enumerator(at: appDir, includingPropertiesForKeys: nil)!
        for case let url as URL in files where url.pathExtension == "swift" {
            let text = try String(contentsOf: url)
            for s in secrets { XCTAssertFalse(text.contains(s), "\(url.lastPathComponent) contains publisher value \(s)") }
        }
    }

    func testAppSourceNeverImportsCore() throws {
        let files = FileManager.default.enumerator(at: root.appendingPathComponent("App"), includingPropertiesForKeys: nil)!
        for case let url as URL in files where url.pathExtension == "swift" {
            XCTAssertFalse(try String(contentsOf: url).contains("import VitaLibreCore"), url.lastPathComponent)
        }
    }
}


final class CalibrationTests: XCTestCase {
    func features() -> BPFeatures {
        BPFeatures(heartRate: 70, intervalCV: 0.03, crestFraction: 0.3, skewness: 0.6, reflectionIndex: 0.15)
    }

    func point(rawS: Double, rawD: Double, cuffS: Double, cuffD: Double) -> CalibrationPoint {
        CalibrationPoint(rawSystolic: rawS, rawDiastolic: rawD, cuffSystolic: cuffS, cuffDiastolic: cuffD, date: Date())
    }

    func testOneCuffReadingMovesTheEstimateOntoIt() {
        let raw = BPEstimator.raw(features(), model: testModel(), age: nil, sex: .unspecified)
        var cal = BPCalibration()
        cal.add(point(rawS: raw.systolic, rawD: raw.diastolic, cuffS: 141, cuffD: 91))
        let r = BPEstimator.estimate(features(), model: testModel(), age: nil, sex: .unspecified, calibration: cal)
        XCTAssertEqual(r.systolic, 141)
        XCTAssertEqual(r.diastolic, 91)
        XCTAssertEqual(r.systolicHigh - r.systolicLow, 28, "one point does not narrow the range")
        XCTAssertEqual(r.text, "141 / 91", "calibrated shows a single figure per component")
    }

    func testRangeNarrowsToObservedErrorAfterThreePoints() {
        var cal = BPCalibration()
        for (rs, cs) in [(118.0, 130.0), (121.0, 132.0), (119.0, 129.0)] {
            cal.add(point(rawS: rs, rawD: 78, cuffS: cs, cuffD: 84))
        }
        let w = cal.halfWidths(fallback: (14, 9), base: (systolic: 120, diastolic: 78), legacy: (systolic: 120, diastolic: 78))
        XCTAssertLessThan(w.systolic, 14)
        XCTAssertGreaterThanOrEqual(w.systolic, BPCalibration.minHalfWidth.systolic)
        XCTAssertEqual(cal.offset(base: (systolic: 120, diastolic: 78), legacy: (systolic: 120, diastolic: 78)).systolic, 11.0, accuracy: 0.001)
    }

    func testImplausibleCuffReadingsAreRejected() {
        var cal = BPCalibration()
        cal.add(point(rawS: 118, rawD: 76, cuffS: 60, cuffD: 40))
        cal.add(point(rawS: 118, rawD: 76, cuffS: 120, cuffD: 118))
        cal.add(point(rawS: 118, rawD: 76, cuffS: 300, cuffD: 90))
        XCTAssertEqual(cal.count, 0)
    }

    func testUsualBloodPressureReplacesThePrior() {
        let usual = UsualBP(systolic: 130, diastolic: 84)
        let base = BPEstimator.raw(features(), model: testModel(), age: 70, sex: .male, usual: usual)
        // heartRate 70 is the test model's mean, so the adjustment is zero and the usual value passes through.
        XCTAssertEqual(base.systolic, 130, accuracy: 1e-9)
        XCTAssertEqual(base.diastolic, 84, accuracy: 1e-9)
        let r = BPEstimator.estimate(features(), model: testModel(), age: 70, sex: .male, usual: usual)
        XCTAssertEqual(r.text, "130 / 84")
    }

    func testImplausibleUsualIsIgnored() {
        let r = BPEstimator.raw(features(), model: testModel(), age: nil, sex: .unspecified, usual: UsualBP(systolic: 30, diastolic: 20))
        XCTAssertEqual(r.systolic, 120, accuracy: 1e-9)
    }

    func testChangingTheStartingPointDoesNotDoubleCountAnOldPairing() {
        // Paired when the starting point was 118/76 (raw 122/80: a +4/+4 pulse adjustment) against a cuff of 126/84.
        var cal = BPCalibration()
        cal.add(CalibrationPoint(rawSystolic: 122, rawDiastolic: 80, cuffSystolic: 126, cuffDiastolic: 84, date: Date(),
                                 baseSystolic: 118, baseDiastolic: 76))
        // Now a typical pressure of 120/80 is set; the same pulse adjustment (+4) must still give 126/84, not 128/88.
        let usual = UsualBP(systolic: 120, diastolic: 80)
        let f = BPFeatures(heartRate: 70 + 12 * 0.04, intervalCV: 0.03, crestFraction: 0.3, skewness: 0.6, reflectionIndex: 0.15)
        let adj = BPEstimator.raw(f, model: testModel(), age: nil, sex: .unspecified, usual: usual).systolic - 120
        let r = BPEstimator.estimate(f, model: testModel(), age: nil, sex: .unspecified, usual: usual, calibration: cal)
        XCTAssertEqual(Double(r.systolic), 126 + (adj - 4), accuracy: 1.0)
    }

    func testOffsetIsCapped() {
        var cal = BPCalibration()
        cal.add(point(rawS: 60, rawD: 40, cuffS: 200, cuffD: 120))
        XCTAssertEqual(cal.offset(base: (systolic: 120, diastolic: 78), legacy: (systolic: 120, diastolic: 78)).systolic, BPCalibration.maxOffset)
    }
}

final class LiveViewTests: XCTestCase {
    func testLiveReadoutTracksTheRateAndFindsBeats() {
        var session = ScanSession()
        syntheticSamples(bpm: 72, seconds: 10).forEach { session.add($0) }
        let live = session.live(model: testModel(), age: nil, sex: .unspecified, usual: nil, calibration: BPCalibration())
        XCTAssertNotNil(live)
        XCTAssertEqual(live?.heartRate ?? 0, 72, accuracy: 3)
        XCTAssertNotNil(live?.bp)
        XCTAssertNotNil(live?.lastBeat)
        XCTAssertLessThanOrEqual(live?.lastBeat ?? 99, live?.traceEnd ?? 0)
    }

    func testNothingLiveBeforeTheSignalSettles() {
        var session = ScanSession()
        syntheticSamples(bpm: 72, seconds: 2.5).forEach { session.add($0) }
        XCTAssertNil(session.live(model: testModel(), age: nil, sex: .unspecified, usual: nil, calibration: BPCalibration()))
    }

    func testFinalResultCarriesTheWholeTrace() {
        var session = ScanSession()
        syntheticSamples(bpm: 72, seconds: 16).forEach { session.add($0) }
        guard case .success(let r) = session.analyse(model: testModel(), age: nil, sex: .unspecified) else { return XCTFail() }
        XCTAssertGreaterThan(r.trace.count, 600)
        XCTAssertTrue(r.trace.allSatisfy { abs($0) <= 1 })
        XCTAssertGreaterThan(r.traceEnd, 12)
    }
}
