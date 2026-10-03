import Foundation

/// Phone motion at the same moments as the camera frames, gravity already removed by the platform:
/// gyro in rad/s, linear acceleration in m/s^2. Sampled at 50 Hz or better so 12 Hz tremor is visible.
struct MotionSample: Equatable {
    var t: Double
    var gx: Double, gy: Double, gz: Double
    var ax: Double, ay: Double, az: Double
}

/// What lowered the feed quality most, or `none` when it is good. Raw values are what a reading stores.
enum FeedCause: String, Codable, Equatable {
    case none, coverage, clipping, light, frames, noise, steps, motion, tremor, adjusting

    var advice: String? {
        switch self {
        case .none: return nil
        case .coverage: return "Cover the lens and flash completely"
        case .clipping: return "Press more lightly"
        case .light: return "Too dark or too bright — shade the phone from other light"
        case .frames: return "The camera is dropping frames — close other apps"
        case .noise: return "The picture is noisy — warm your hands and keep the finger still"
        case .steps, .motion: return "Keep still"
        case .tremor: return "Hand shaking — rest your forearm"
        case .adjusting: return "Hold still — the camera is adjusting"
        }
    }
}

/// How good the camera feed is, apart from the heart. Every factor is a measure of the picture or of the
/// phone, none of them uses beat intervals or pulse amplitude, so an irregular or weak pulse cannot lower it
/// (specs/ppg-vitals-app.md, "Feed quality"). The value is the minimum of the factors: one bad factor sinks it.
/// All constants are provisional until measured on a phone, and are the same on both platforms.
enum FeedQuality {
    static let windowSeconds = 3.0
    static let good = 0.7, fair = 0.4

    // 1 at the first number, 0 at the second (either direction).
    static let coverage = (good: 0.9, bad: 0.5)
    static let clipping = (good: 0.2, bad: 0.6)
    static let dcOK = 40.0...230.0
    static let dcEdge = (low: 25.0, high: 245.0)
    static let lateFrames = (good: 0.0, bad: 0.2)
    static let lateFactor = 1.5
    static let noise = (good: 0.0005, bad: 0.004)
    static let steps = (good: 0.015, bad: 0.06)
    static let movement = (good: 0.05, bad: 0.3)          // gyro rad/s, 0.1-3 Hz
    static let movementAccel = (good: 0.05, bad: 0.5)     // m/s^2, 0.1-3 Hz
    static let tremor = (good: 0.02, bad: 0.1)            // gyro rad/s, 4-12 Hz
    static let tremorAccel = (good: 0.03, bad: 0.2)       // m/s^2, 4-12 Hz
    static let adjustingScore = 0.3
    static let minMotionSamples = 40

    struct Point: Equatable {
        /// Seconds from the start of the covered run; the window is the `windowSeconds` ending here.
        var t: Double
        var value: Double
        var cause: FeedCause
    }

    static func level(_ value: Double) -> QualityLevel { value >= good ? .good : (value >= fair ? .fair : .poor) }

    /// One point per whole second of the run, `from` the first second to the last.
    static func series(samples: [PPGSample], motion: [MotionSample], runStart: Double, runEnd: Double) -> [Point] {
        let run = samples.filter { $0.t >= runStart && $0.t <= runEnd }
        guard run.count > 5 else { return [] }
        var points: [Point] = []
        let seconds = Int((runEnd - runStart).rounded(.down))
        guard seconds >= 1 else { return [] }
        for k in 1...seconds {
            let end = runStart + Double(k), start = end - windowSeconds
            points.append(evaluate(run.filter { $0.t > start && $0.t <= end },
                                   motion.filter { $0.t > start && $0.t <= end }, at: Double(k)))
        }
        return points
    }

    /// The latest window, for the prompts while a scan is running.
    static func latest(samples: [PPGSample], motion: [MotionSample]) -> Point? {
        guard let last = samples.last else { return nil }
        let start = last.t - windowSeconds
        let s = samples.filter { $0.t > start }
        guard s.count > 5 else { return nil }
        return evaluate(s, motion.filter { $0.t > start }, at: last.t)
    }

    static func mean(_ points: [Point]) -> Double { Stats.mean(points.map(\.value)) }

    static func evaluate(_ s: [PPGSample], _ m: [MotionSample], at t: Double) -> Point {
        guard s.count > 5 else { return Point(t: t, value: 0, cause: .frames) }
        var factors: [(FeedCause, Double)] = []
        factors.append((.coverage, ramp(Stats.mean(s.map(\.coverage)), good: coverage.good, bad: coverage.bad)))
        factors.append((.clipping, ramp(Stats.mean(s.map(\.saturated)), good: clipping.good, bad: clipping.bad)))
        let g = s.map(\.g), dc = Stats.mean(g)
        let light = dcOK.contains(dc) ? 1
            : dc < dcOK.lowerBound ? ramp(dc, good: dcOK.lowerBound, bad: dcEdge.low)
            : ramp(dc, good: dcOK.upperBound, bad: dcEdge.high)
        factors.append((.light, light))
        factors.append((.frames, ramp(lateShare(s.map(\.t)), good: lateFrames.good, bad: lateFrames.bad)))
        factors.append((.noise, ramp(noiseOverDC(s.map(\.t), g, dc: dc), good: noise.good, bad: noise.bad)))
        factors.append((.steps, ramp(largestStep(s, dc: dc), good: steps.good, bad: steps.bad)))
        if m.count >= minMotionSamples {
            let move = min(ramp(bandAmplitude(m, gyro: true, 0.1, 3), good: movement.good, bad: movement.bad),
                           ramp(bandAmplitude(m, gyro: false, 0.1, 3), good: movementAccel.good, bad: movementAccel.bad))
            let shake = min(ramp(bandAmplitude(m, gyro: true, 4, 12), good: tremor.good, bad: tremor.bad),
                            ramp(bandAmplitude(m, gyro: false, 4, 12), good: tremorAccel.good, bad: tremorAccel.bad))
            factors.append((.motion, move))
            factors.append((.tremor, shake))
        }
        factors.append((.adjusting, s.contains(where: \.event) ? adjustingScore : 1))
        let worst = factors.min { $0.1 < $1.1 }!
        return Point(t: t, value: worst.1, cause: worst.1 < good ? worst.0 : .none)
    }

    // MARK: Factors

    /// 1 at `good`, 0 at `bad`, linear between; works whichever is larger.
    static func ramp(_ x: Double, good: Double, bad: Double) -> Double {
        max(0, min(1, (x - bad) / (good - bad)))
    }

    /// Share of frames that arrived after a gap longer than `lateFactor` times the median gap.
    static func lateShare(_ t: [Double]) -> Double {
        guard t.count > 3 else { return 0 }
        let dts = zip(t.dropFirst(), t).map { $0 - $1 }
        let med = Stats.median(dts)
        guard med > 0 else { return 1 }
        return Double(dts.filter { $0 > lateFactor * med }.count) / Double(dts.count)
    }

    /// The noise band, as fractions of the frame rate.
    static let noiseBandLow = 0.3, noiseBandHigh = 0.47

    /// Noise in the green series over its mean, from the band between 30% and 47% of the frame rate (9-14 Hz
    /// at 30 fps). A pulse has almost no energy there (a systolic upstroke 70 ms wide is down by a factor
    /// of several hundred at 9 Hz), and a second difference does not manage that: at a fast heart rate the
    /// pulse's own curvature fills most frames. White noise has the same power at every frequency, so the
    /// mean power in the band is its variance. A Hann window keeps the slow drift and the pulse from leaking
    /// into the band. Computed at the real frame times, so dropped frames show up in `lateShare` first.
    static func noiseOverDC(_ t: [Double], _ g: [Double], dc: Double) -> Double {
        let n = g.count
        guard n > 8, dc > 0 else { return 0 }
        let med = Stats.median(zip(t.dropFirst(), t).map { $0 - $1 })
        guard med > 0 else { return 0 }
        let fps = 1 / med
        let mean = Stats.mean(g)
        let w = (0..<n).map { 0.5 * (1 - cos(2 * Double.pi * Double($0) / Double(n - 1))) }
        let sumW2 = w.reduce(0) { $0 + $1 * $1 }
        var total = 0.0, bins = 0
        var f = noiseBandLow * fps
        while f <= noiseBandHigh * fps + 1e-9 {
            var re = 0.0, im = 0.0
            for i in 0..<n {
                let ph = 2 * Double.pi * f * t[i]
                re += w[i] * (g[i] - mean) * cos(ph)
                im += w[i] * (g[i] - mean) * sin(ph)
            }
            total += (re * re + im * im) / sumW2
            bins += 1
            f += 0.5
        }
        return (total / Double(bins)).squareRoot() / dc
    }

    /// The largest jump between consecutive half-second means over the mean: a shifted finger, an
    /// exposure change. A pulse moves half-second means by well under the "good" level.
    static func largestStep(_ s: [PPGSample], dc: Double) -> Double {
        guard dc > 0, let t0 = s.first?.t else { return 0 }
        var sums: [Int: (Double, Int)] = [:]
        for x in s { let k = Int((x.t - t0) / 0.5); let v = sums[k] ?? (0, 0); sums[k] = (v.0 + x.g, v.1 + 1) }
        let means = sums.keys.sorted().compactMap { k -> Double? in
            let v = sums[k]!; return v.1 >= 3 ? v.0 / Double(v.1) : nil
        }
        guard means.count >= 2 else { return 0 }
        return zip(means.dropFirst(), means).map { abs($0 - $1) }.max()! / dc
    }

    /// The strongest sinusoid between `lo` and `hi` Hz, as an RMS amplitude, summed in quadrature over
    /// the three axes. Direct transform at 0.25 Hz steps: the window is short and the bins few.
    static func bandAmplitude(_ m: [MotionSample], gyro: Bool, _ lo: Double, _ hi: Double) -> Double {
        let axes: [[Double]] = gyro ? [m.map(\.gx), m.map(\.gy), m.map(\.gz)] : [m.map(\.ax), m.map(\.ay), m.map(\.az)]
        let times = m.map(\.t)
        var best = 0.0
        var f = lo
        while f <= hi + 1e-9 {
            var power = 0.0
            for a in axes {
                let mu = Stats.mean(a)
                var re = 0.0, im = 0.0
                for (i, v) in a.enumerated() {
                    let ph = 2 * Double.pi * f * times[i]
                    re += (v - mu) * cos(ph); im += (v - mu) * sin(ph)
                }
                let amp = 2 * (re * re + im * im).squareRoot() / Double(a.count)
                power += amp * amp / 2
            }
            best = max(best, power.squareRoot())
            f += 0.25
        }
        return best
    }
}
