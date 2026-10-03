import XCTest
@testable import VitaLibreCore

extension SeededRNG {
    mutating func gaussian() -> Double {
        let u1 = max(1e-12, next()), u2 = next()
        return (-2 * log(u1)).squareRoot() * cos(2 * Double.pi * u2)
    }
}

/// One synthetic recording: camera samples and the phone's motion samples, with beats at known times.
struct Recording {
    var samples: [PPGSample]
    var motion: [MotionSample]
}

struct Rhythmic { var times: [Double]; var amps: [Double] }

/// The same corpus as the Kotlin core's `FeedQualityTests`, so the two implementations are held to one standard.
enum Corpus {
    static let seconds = 15.0, fps = 30.0, noiseSigma = 0.0433

    static func regular(_ bpm: Double) -> Rhythmic {
        let dt = 60 / bpm
        var t: [Double] = [], x = 0.2
        while x < seconds - 0.3 { t.append(x); x += dt }
        return Rhythmic(times: t, amps: t.map { _ in 1 })
    }

    static func sinusArrhythmia(_ bpm: Double) -> Rhythmic {
        var t: [Double] = [], x = 0.2
        while x < seconds - 0.3 { t.append(x); x += 60 / bpm * (1 + 0.12 * sin(2 * Double.pi * 0.25 * x)) }
        return Rhythmic(times: t, amps: t.map { _ in 1 })
    }

    static func afLike(_ bpm: Double, seed: UInt64 = 5) -> Rhythmic {
        var r = SeededRNG(seed: seed)
        let mean = 60 / bpm
        var t: [Double] = [], x = 0.2
        while x < seconds - 0.3 { t.append(x); x += min(1.6, max(0.35, mean * (1 + 0.25 * r.gaussian()))) }
        return Rhythmic(times: t, amps: t.map { _ in 1 })
    }

    static func premature(_ bpm: Double) -> Rhythmic {
        let dt = 60 / bpm
        var t: [Double] = [], a: [Double] = [], x = 0.2, n = 0
        while x < seconds - 0.3 {
            n += 1
            if n % 10 == 0 { t.append(x - 0.4 * dt); a.append(0.5) } else { t.append(x); a.append(1) }
            x += dt
        }
        return Rhythmic(times: t, amps: a)
    }

    static func record(_ rhythm: Rhythmic, pulse: Double = 1, seed: UInt64 = 7) -> Recording {
        var rng = SeededRNG(seed: seed)
        let samples = (0..<Int(seconds * fps)).map { i -> PPGSample in
            let t = Double(i) / fps
            var p = 0.0
            for (k, b) in rhythm.times.enumerated() {
                let u = t - b
                guard u >= 0, u <= 0.7 else { continue }
                p += rhythm.amps[k] * (exp(-pow((u - 0.18) / 0.07, 2)) + 0.35 * exp(-pow((u - 0.45) / 0.09, 2)))
            }
            let g = 60 - 1.2 * pulse * p + 0.4 * sin(t * 0.2) + noiseSigma * rng.gaussian()
            return PPGSample(t: t, r: 210, g: g, b: 20, saturated: 0.1, coverage: 0.95, event: false)
        }
        var mrng = SeededRNG(seed: seed + 1)
        let motion = (0..<Int(seconds * 100)).map { i in
            MotionSample(t: Double(i) / 100, gx: 0.004 * mrng.gaussian(), gy: 0.004 * mrng.gaussian(), gz: 0.004 * mrng.gaussian(),
                         ax: 0.01 * mrng.gaussian(), ay: 0.01 * mrng.gaussian(), az: 0.01 * mrng.gaussian())
        }
        return Recording(samples: samples, motion: motion)
    }

    static func series(_ r: Recording) -> [FeedQuality.Point] {
        FeedQuality.series(samples: r.samples, motion: r.motion, runStart: 0, runEnd: r.samples.last!.t)
    }

    static func noise(_ r: Recording, _ from: Double, _ to: Double, sigma: Double = 0.5) -> Recording {
        var rng = SeededRNG(seed: 3), r = r
        r.samples = r.samples.map { s in var s = s; if s.t >= from && s.t <= to { s.g += sigma * rng.gaussian() }; return s }
        return r
    }

    static func dcStep(_ r: Recording, at: Double, by: Double = 6) -> Recording {
        var r = r
        r.samples = r.samples.map { s in var s = s; if s.t >= at { s.g += by }; return s }
        return r
    }

    static func clipping(_ r: Recording, _ from: Double, _ to: Double) -> Recording {
        var r = r
        r.samples = r.samples.map { s in var s = s; if s.t >= from && s.t <= to { s.saturated = 0.7 }; return s }
        return r
    }

    static func droppedFrames(_ r: Recording, _ from: Double, _ to: Double, share: Double = 0.4) -> Recording {
        var rng = SeededRNG(seed: 9), r = r
        r.samples = r.samples.filter { !($0.t >= from && $0.t <= to && rng.next() < share) }
        return r
    }

    static func partialCover(_ r: Recording, _ from: Double, _ to: Double) -> Recording {
        var r = r
        r.samples = r.samples.map { s in var s = s; if s.t >= from && s.t <= to { s.coverage = 0.4 }; return s }
        return r
    }

    static func exposureStep(_ r: Recording, at: Double) -> Recording {
        var r = r
        r.samples = r.samples.map { s in var s = s; if abs(s.t - at) < 0.02 { s.event = true }; return s }
        return r
    }

    static func shake(_ r: Recording, _ from: Double, _ to: Double, hz: Double, gyro: Double, accel: Double) -> Recording {
        var r = r
        r.motion = r.motion.map { m in
            var m = m
            if m.t >= from && m.t <= to { let s = sin(2 * Double.pi * hz * m.t); m.gx += gyro * s; m.ay += accel * s }
            return m
        }
        return r
    }
}

final class FeedQualityTests: XCTestCase {
    private func clean(_ r: Rhythmic, pulse: Double = 1) -> [FeedQuality.Point] {
        Corpus.series(Corpus.record(r, pulse: pulse)).filter { $0.t >= 3 }
    }

    func testACleanFeedIsGood() {
        let s = clean(Corpus.regular(72))
        XCTAssertTrue(s.allSatisfy { $0.value >= FeedQuality.good && $0.cause == .none }, "\(s)")
    }

    func testPhysiologyDoesNotMoveTheIndex() {
        let base = FeedQuality.mean(clean(Corpus.regular(72)))
        let variants: [(String, Rhythmic)] = [
            ("sinus arrhythmia", Corpus.sinusArrhythmia(72)), ("AF-like", Corpus.afLike(75)),
            ("premature beats", Corpus.premature(72)), ("HR 45", Corpus.regular(45)),
            ("HR 120", Corpus.regular(120)), ("HR 180", Corpus.regular(180)),
        ]
        for (name, r) in variants {
            let s = clean(r)
            XCTAssertLessThan(abs(FeedQuality.mean(s) - base), 0.1, name)
            XCTAssertTrue(s.allSatisfy { $0.value >= FeedQuality.good }, "\(name): \(s.map(\.value))")
        }
    }

    func testPulseStrengthDoesNotMoveTheIndex() {
        let base = FeedQuality.mean(clean(Corpus.regular(72)))
        for amp in [0.1, 0.25, 0.5, 1.0] {
            XCTAssertLessThan(abs(FeedQuality.mean(clean(Corpus.regular(72), pulse: amp)) - base), 0.1, "pulse \(amp)")
        }
    }

    private func assertFault(_ name: String, _ cause: FeedCause, _ fault: (Recording) -> Recording,
                             file: StaticString = #filePath, line: UInt = #line) {
        let base = FeedQuality.mean(clean(Corpus.regular(72)))
        let s = Corpus.series(fault(Corpus.record(Corpus.regular(72)))).filter { $0.t >= 7 && $0.t <= 11 }
        let worst = s.min { $0.value < $1.value }!
        XCTAssertLessThanOrEqual(worst.value, base - 0.4, "\(name): \(s.map(\.value))", file: file, line: line)
        XCTAssertEqual(worst.cause, cause, name, file: file, line: line)
    }

    func testNoiseLowersIt() { assertFault("noise", .noise) { Corpus.noise($0, 6, 9) } }
    func testAMotionStepLowersIt() { assertFault("dc step", .steps) { Corpus.dcStep($0, at: 7.5) } }
    func testClippingLowersIt() { assertFault("clipping", .clipping) { Corpus.clipping($0, 6, 9) } }
    func testDroppedFramesLowerIt() { assertFault("dropped frames", .frames) { Corpus.droppedFrames($0, 6, 9) } }
    func testPartialCoverLowersIt() { assertFault("partial cover", .coverage) { Corpus.partialCover($0, 6, 9) } }
    func testAnExposureStepLowersIt() { assertFault("exposure event", .adjusting) { Corpus.exposureStep($0, at: 7.5) } }
    func testTremorLowersIt() { assertFault("tremor", .tremor) { Corpus.shake($0, 6, 9, hz: 8, gyro: 0.2, accel: 0.4) } }
    func testMovementLowersIt() { assertFault("movement", .motion) { Corpus.shake($0, 6, 9, hz: 1, gyro: 0.6, accel: 1.0) } }

    func testAFaultIsNotBlamedOnTheHeart() {
        let r = Corpus.noise(Corpus.record(Corpus.afLike(75)), 6, 9)
        let worst = Corpus.series(r).filter { $0.t >= 7 && $0.t <= 11 }.min { $0.value < $1.value }!
        XCTAssertEqual(worst.cause, .noise)
    }

    func testWithoutMotionSensorsTheFeedStillScores() {
        var r = Corpus.record(Corpus.regular(72))
        r.motion = []
        XCTAssertTrue(Corpus.series(r).filter { $0.t >= 3 }.allSatisfy { $0.value >= FeedQuality.good })
    }
}

final class ExclusionTests: XCTestCase {
    private let model = testModel()

    private func analyse(_ rhythm: Rhythmic, mutate: (Recording) -> Recording = { $0 }) -> ScanResult {
        let rec = mutate(Corpus.record(rhythm))
        var session = ScanSession()
        rec.samples.forEach { session.add($0) }
        rec.motion.forEach { session.addMotion($0) }
        guard case .success(let r) = session.analyse(model: model, age: nil, sex: .unspecified) else {
            XCTFail("analysis failed"); fatalError()
        }
        return r
    }

    private func recompute(_ r: ScanResult, _ cuts: [ExcludedRange]) -> Result<ReadingEdit.Outcome, ReadingEdit.Failure> {
        ReadingEdit.recompute(trace: r.trace, traceStart: r.traceStart, excluded: cuts, model: model, age: nil,
                              sex: .unspecified, usual: nil, calibration: BPCalibration())
    }

    func testTheResultCarriesItsFeedSeries() {
        let r = analyse(Corpus.regular(72))
        XCTAssertFalse(r.feed.isEmpty)
        XCTAssertTrue(r.feed.allSatisfy { (0...1).contains($0.value) })
    }

    func testExcludingAnInjectedFaultKeepsTheRateTrue() throws {
        let bad = analyse(Corpus.regular(72)) { Corpus.noise($0, 7, 10, sigma: 1.5) }
        let o = try recompute(bad, [ExcludedRange(start: 6.5, end: 10.5)]).get()
        XCTAssertEqual(o.heartRate, 72, accuracy: 2)
        XCTAssertEqual(o.rhythm, .steady)
        XCTAssertEqual(o.usedSeconds, (bad.traceEnd - bad.traceStart) - 4, accuracy: 0.2)
    }

    func testAnIntervalIsNeverTakenAcrossACut() throws {
        func beats(_ from: Double, _ n: Int) -> [Beat] { (0..<n).map { Beat(index: $0 * 60, time: from + Double($0), amplitude: 1) } }
        let a = beats(0, 6), b = beats(6.8, 6)
        let grouped = try HeartRate.estimate(groups: [a, b]).get()
        XCTAssertEqual(grouped.intervals.count, 10)
        XCTAssertTrue(grouped.intervals.allSatisfy { abs($0 - 1.8) > 0.01 })
        let joined = try HeartRate.estimate(beats: a + b).get()
        XCTAssertTrue(joined.intervals.contains { abs($0 - 1.8) < 0.01 })
    }

    func testTooLittleSignalLeftIsRefused() {
        let r = analyse(Corpus.regular(72))
        XCTAssertEqual(recompute(r, [ExcludedRange(start: 2, end: 11)]), .failure(.tooLittleSignalLeft))
    }

    func testOverlappingRangesAreMergedAndClipped() {
        let m = ReadingEdit.merged([ExcludedRange(start: 5, end: 7), ExcludedRange(start: 6, end: 9),
                                    ExcludedRange(start: -3, end: 0.5), ExcludedRange(start: 20, end: 25)], from: 1, to: 14)
        XCTAssertEqual(m, [ExcludedRange(start: 5, end: 9)])
    }

    func testGuidanceNamesATremor() {
        let rec = Corpus.shake(Corpus.record(Corpus.regular(72)), 4, 10, hz: 8, gyro: 0.2, accel: 0.4)
        var session = ScanSession()
        rec.samples.filter { $0.t <= 8 }.forEach { session.add($0) }
        rec.motion.filter { $0.t <= 8 }.forEach { session.addMotion($0) }
        XCTAssertEqual(session.guidance, .feed(.tremor))
    }
}

