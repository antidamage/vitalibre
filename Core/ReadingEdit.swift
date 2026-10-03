import Foundation

/// A stretch of a reading's run, in seconds from the start of the covered run, that the user has marked as
/// not to be used. The stretch stays on the graph and in the file; only the numbers stop counting it.
struct ExcludedRange: Codable, Equatable {
    var start: Double
    var end: Double
    var length: Double { max(0, end - start) }
}

/// A reading's numbers recomputed with stretches left out, from the stored trace.
enum ReadingEdit {
    static let minKeptSeconds = ScanSession.minSeconds
    /// A beat this close to a discarded stretch is dropped too: its foot and reflected peak sit around it.
    static let beatMargin = 0.25

    struct Outcome: Equatable {
        var heartRate: Double
        var rhythm: Rhythm
        var bp: BPRange
        var rawSystolic: Double
        var rawDiastolic: Double
        var usedSeconds: Double
    }

    enum Failure: Error, Equatable { case tooLittleSignalLeft, noPulse }

    /// Ranges sorted, clipped to the trace, and merged where they touch.
    static func merged(_ ranges: [ExcludedRange], from lo: Double, to hi: Double) -> [ExcludedRange] {
        var out: [ExcludedRange] = []
        for r in ranges.map({ ExcludedRange(start: max(lo, $0.start), end: min(hi, $0.end)) })
            .filter({ $0.length > 0 }).sorted(by: { $0.start < $1.start }) {
            if var last = out.last, r.start <= last.end { last.end = max(last.end, r.end); out[out.count - 1] = last }
            else { out.append(r) }
        }
        return out
    }

    /// `trace` is the stored filtered waveform at `ScanSession.analysisRate`, `traceStart` the run second
    /// of its first sample. Beats are found on the whole trace (cutting first would add filter edges),
    /// those inside or beside a discarded stretch are dropped, and intervals are taken only inside one kept
    /// stretch. With nothing excluded the caller shows the stored result instead of calling this.
    static func recompute(trace: [Double], traceStart: Double, excluded: [ExcludedRange],
                          model: BPModel, age: Int?, sex: Sex, usual: UsualBP?,
                          calibration: BPCalibration) -> Result<Outcome, Failure> {
        let fs = ScanSession.analysisRate
        guard trace.count > Int(fs) else { return .failure(.tooLittleSignalLeft) }
        let lo = traceStart, hi = traceStart + Double(trace.count - 1) / fs
        let cuts = merged(excluded, from: lo, to: hi)
        let used = (hi - lo) - cuts.reduce(0) { $0 + $1.length }
        guard used >= minKeptSeconds else { return .failure(.tooLittleSignalLeft) }

        // Kept stretches: what lies between the cuts.
        var stretches: [(Double, Double)] = []
        var from = lo
        for c in cuts { if c.start > from { stretches.append((from, c.start)) }; from = c.end }
        if from < hi { stretches.append((from, hi)) }

        let beats = BeatDetector.detect(trace, fs: fs)
        var groups = [[Beat]](repeating: [], count: stretches.count)
        for b in beats {
            let t = traceStart + b.time
            guard let i = stretches.firstIndex(where: { t >= $0.0 + beatMargin * ($0.0 > lo ? 1 : 0) &&
                                                         t <= $0.1 - beatMargin * ($0.1 < hi ? 1 : 0) }) else { continue }
            groups[i].append(b)
        }
        guard case .success(let hr) = HeartRate.estimate(groups: groups) else { return .failure(.noPulse) }

        var keptSamples: [Double] = []
        for (i, v) in trace.enumerated() {
            let t = traceStart + Double(i) / fs
            if stretches.contains(where: { t >= $0.0 && t <= $0.1 }) { keptSamples.append(v) }
        }
        let feats = BPEstimator.features(filtered: trace, beats: groups.flatMap { $0 }, hr: hr, fs: fs,
                                         groups: groups, kept: keptSamples)
        let raw = BPEstimator.raw(feats, model: model, age: age, sex: sex, usual: usual)
        let bp = BPEstimator.estimate(feats, model: model, age: age, sex: sex, usual: usual, calibration: calibration)
        return .success(Outcome(heartRate: hr.bpm, rhythm: hr.rhythm, bp: bp,
                                rawSystolic: raw.systolic, rawDiastolic: raw.diastolic, usedSeconds: used))
    }
}
