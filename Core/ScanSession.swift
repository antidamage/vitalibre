import Foundation

/// One frame's channel means over the ROI, on 0...255.
struct PPGSample: Equatable {
    var t: Double
    var r: Double, g: Double, b: Double
    /// Fraction of ROI pixels at or near full scale in the green channel (the one the pulse is read from;
    /// red clips on any fingertip under the torch).
    var saturated: Double

    /// A fingertip over the lens and torch: red high, green well below it.
    var covered: Bool { r >= 80 && g <= 0.7 * r && saturated < 0.85 }
}

/// Why a scan produced no reading. Every one of these is the finger: not on the lens,
/// taken off it too early, moving enough that no beat can be found, or pressed so that the
/// pulse never rises above the light level. A rhythm that swings is not on this list — it
/// is a note on the reading instead (`Reading.note`).
enum ScanFailure: Error, Equatable {
    case notCovered
    case tooShort(seconds: Double)
    case noPulse
    case poorSignal

    var message: String {
        switch self {
        case .notCovered: return "Cover the lens and flash with your fingertip, then try again."
        case .tooShort: return "The scan was too short. Keep your finger still until it finishes."
        case .noPulse: return "No pulse found. Rest your fingertip lightly and try again."
        case .poorSignal: return "The signal was too weak to trust. Warm your hands and try again."
        }
    }
}

enum Guidance: Equatable {
    case coverLens, hold, pressLighter, keepStill, good

    var text: String {
        switch self {
        case .coverLens: return "Cover the lens and flash completely"
        case .hold: return "Hold still — reading"
        case .pressLighter: return "Press more lightly"
        case .keepStill: return "Keep still"
        case .good: return "Good signal"
        }
    }
}

struct ScanResult: Equatable {
    var heartRate: Double
    var bp: BPRange
    var quality: Double
    var level: QualityLevel
    /// How evenly the beats came. Carried onto the reading as a note when irregular; it
    /// never refuses a scan.
    var rhythm: Rhythm
    var duration: Double
    var intervals: [Double]
    var modelVersion: String
    var features: BPFeatures
    /// The model's value before calibration, kept so a cuff reading can be paired with this scan.
    var rawSystolic: Double
    var rawDiastolic: Double
    /// The starting point the raw value was built on, kept so a cuff pairing can remember it.
    var baseSystolic: Double
    var baseDiastolic: Double
    /// The whole run's filtered waveform, normalised -1...1 at `analysisRate`, for the graph kept on screen.
    var trace: [Double]
    /// Seconds from the run's start to the trace's last sample.
    var traceEnd: Double
}

/// What the orb shows while a scan is still running.
struct LiveView: Equatable {
    var trace: [Double]
    var traceEnd: Double
    var heartRate: Double?
    var bp: BPRange?
    /// Run-relative time of the most recent detected beat, for the pulse.
    var lastBeat: Double?
}

struct ScanSession {
    static let targetSeconds = 15.0
    static let minSeconds = 8.0
    static let maxSeconds = 45.0
    static let analysisRate = 60.0
    /// The first stretch after cover is discarded while exposure settles.
    static let settleSeconds = 1.0
    static let maxGapSeconds = 0.3

    private(set) var samples: [PPGSample] = []

    mutating func add(_ s: PPGSample) { samples.append(s) }

    var elapsed: Double { samples.count > 1 ? samples[samples.count - 1].t - samples[0].t : 0 }

    /// Length of the run ending now in which the finger stayed covered.
    var currentRunSeconds: Double {
        guard let last = samples.last, last.covered else { return 0 }
        var start = samples.count - 1, j = start - 1
        while j >= 0 {
            if samples[j].covered { start = j }
            else if samples[start].t - samples[j].t > Self.maxGapSeconds { break }
            j -= 1
        }
        return last.t - samples[start].t
    }

    var finished: Bool { currentRunSeconds >= Self.targetSeconds || elapsed >= Self.maxSeconds }

    var guidance: Guidance {
        guard let last = samples.last, last.covered else { return .coverLens }
        if last.saturated > 0.5 { return .pressLighter }
        let recent = samples.suffix(30)
        if recent.count >= 15 {
            let g = recent.map(\.g), spread = Stats.std(g) / max(1, Stats.mean(g))
            if spread > 0.05 { return .keepStill }
        }
        return currentRunSeconds > 6 ? .good : .hold
    }

    private struct Run { var start: Int; var end: Int; var duration: Double }

    private func longestRun() -> Run? {
        var best: Run?, start: Int?, last = 0
        for i in 0..<samples.count where samples[i].covered {
            if start == nil || samples[i].t - samples[last].t > Self.maxGapSeconds { start = i }
            last = i
            let d = samples[i].t - samples[start!].t
            if best == nil || d > best!.duration { best = Run(start: start!, end: i, duration: d) }
        }
        return best
    }

    /// The upward-positive green channel of the longest covered run, resampled and band-passed.
    func filteredWindow(fromSecondsBack back: Double? = nil) -> (filtered: [Double], raw: [Double], start: Double)? {
        guard let run = longestRun() else { return nil }
        var slice = Array(samples[run.start...run.end])
        let tStart = slice[0].t + Self.settleSeconds
        slice = slice.filter { $0.t >= tStart }
        if let back, let last = slice.last { slice = slice.filter { $0.t >= last.t - back } }
        guard slice.count > 10 else { return nil }
        let raw = Filters.resample(times: slice.map(\.t), values: slice.map(\.g), rate: Self.analysisRate)
        let filtered = Filters.bandpass(raw.map { -$0 }, fs: Self.analysisRate)
        return (filtered, raw, slice[0].t - samples[run.start].t)
    }

    /// Covers the whole run so far (up to one revolution of the orb), or the last `seconds`.
    func liveTrace(seconds: Double? = nil) -> [Double] {
        guard let w = filteredWindow(fromSecondsBack: seconds) else { return [] }
        return Self.normalised(w.filtered)
    }

    static func normalised(_ y: [Double]) -> [Double] {
        let scale = max(1e-6, Stats.percentile(y.map(abs), 95))
        return y.map { max(-1, min(1, $0 / scale)) }
    }

    /// The trace, a running heart rate and blood pressure, and the last beat, from what has
    /// been recorded so far. Looser than the final analysis (a few beats are enough) because
    /// it is only a read-out while the scan runs; the result still comes from `analyse`.
    func live(model: BPModel, age: Int?, sex: Sex, usual: UsualBP?, calibration: BPCalibration) -> LiveView? {
        guard let w = filteredWindow(), w.filtered.count > Int(2 * Self.analysisRate) else { return nil }
        let fs = Self.analysisRate
        let beats = BeatDetector.detect(w.filtered, fs: fs)
        let trace = Self.normalised(w.filtered)
        let end = w.start + Double(trace.count - 1) / fs
        var view = LiveView(trace: trace, traceEnd: end, heartRate: nil, bp: nil, lastBeat: beats.last.map { w.start + $0.time })
        let intervals = zip(beats.dropFirst(), beats).map { $0.time - $1.time }
            .filter { $0 >= HeartRate.minInterval && $0 <= HeartRate.maxInterval }
        guard intervals.count >= 3 else { return view }
        let recent = Array(intervals.suffix(10))
        let bpm = 60 / Stats.median(recent)
        view.heartRate = bpm
        if intervals.count >= 5 {
            let spread = Stats.std(recent) / max(1e-9, Stats.mean(recent))
            let hr = HeartRateEstimate(bpm: bpm, intervals: recent, acceptedBeats: recent.count + 1,
                                       variation: spread,
                                       rhythm: spread >= HeartRate.irregularVariation ? .irregular : .steady)
            let f = BPEstimator.features(filtered: w.filtered, beats: beats, hr: hr, fs: fs)
            view.bp = BPEstimator.estimate(f, model: model, age: age, sex: sex, usual: usual, calibration: calibration)
        }
        return view
    }

    func analyse(model: BPModel, age: Int?, sex: Sex, usual: UsualBP? = nil,
                 calibration: BPCalibration = BPCalibration()) -> Result<ScanResult, ScanFailure> {
        guard let run = longestRun() else { return .failure(.notCovered) }
        guard run.duration >= Self.minSeconds else { return .failure(.tooShort(seconds: run.duration)) }
        guard let w = filteredWindow() else { return .failure(.notCovered) }
        let fs = Self.analysisRate
        let beats = BeatDetector.detect(w.filtered, fs: fs)
        switch HeartRate.estimate(beats: beats) {
        case .failure: return .failure(.noPulse)
        case .success(let hr):
            let q = SignalQuality.measure(filtered: w.filtered, raw: w.raw, beats: beats, fs: fs)
            // The one quality failure left: a pulse that never rises above the light level.
            // There is nothing in that to read a rate from, and it is a finger problem — too
            // light, too heavy, off the lens. A merely poor shape is kept and noted instead
            // (`Reading.note`); refusing it was how a recording that might hold an
            // arrhythmia got thrown away, because a weak signal and an unsteady rhythm come
            // out of the same three indices and the app does not pretend to tell them apart.
            guard q.perfusionIndex >= SignalQuality.minPerfusionIndex else { return .failure(.poorSignal) }
            let feats = BPEstimator.features(filtered: w.filtered, beats: beats, hr: hr, fs: fs)
            let raw = BPEstimator.raw(feats, model: model, age: age, sex: sex, usual: usual)
            return .success(ScanResult(heartRate: hr.bpm,
                                       bp: BPEstimator.estimate(feats, model: model, age: age, sex: sex, usual: usual, calibration: calibration),
                                       quality: q.score, level: q.level, rhythm: hr.rhythm, duration: run.duration,
                                       intervals: hr.intervals, modelVersion: model.version, features: feats,
                                       rawSystolic: raw.systolic, rawDiastolic: raw.diastolic,
                                       baseSystolic: BPEstimator.baseline(model: model, age: age, sex: sex, usual: usual).systolic,
                                       baseDiastolic: BPEstimator.baseline(model: model, age: age, sex: sex, usual: usual).diastolic,
                                       trace: Self.normalised(w.filtered),
                                       traceEnd: w.start + Double(w.filtered.count - 1) / fs))
        }
    }
}
