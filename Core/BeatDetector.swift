import Foundation

struct Beat: Equatable {
    var index: Int
    /// Seconds from the start of the analysed window, refined to sub-sample.
    var time: Double
    var amplitude: Double
}

/// Elgendi's two-moving-average detector (PLoS ONE 2013): a short average
/// (systolic-peak width) against a long one (beat width) plus an offset, with
/// one peak taken per block where the short average is higher.
enum BeatDetector {
    static let w1Seconds = 0.111
    static let w2Seconds = 0.667
    static let beta = 0.02

    /// `y` is the band-passed, upward-positive signal sampled at `fs`.
    static func detect(_ y: [Double], fs: Double) -> [Beat] {
        guard y.count > Int(fs) else { return [] }
        let w1 = max(1, Int((w1Seconds * fs).rounded())), w2 = max(2, Int((w2Seconds * fs).rounded()))
        let squared = y.map { v -> Double in let c = max(0, v); return c * c }
        let peakMA = Filters.movingAverage(squared, window: w1)
        let beatMA = Filters.movingAverage(squared, window: w2)
        let offset = beta * Stats.mean(squared)

        var beats: [Beat] = []
        var start: Int? = nil
        func close(at end: Int) {
            guard let s = start, end - s >= w1 else { start = nil; return }
            var best = s
            for i in s..<end where y[i] > y[best] { best = i }
            beats.append(Beat(index: best, time: refined(y, best) / fs, amplitude: y[best]))
            start = nil
        }
        for i in 0..<y.count {
            if peakMA[i] > beatMA[i] + offset { if start == nil { start = i } }
            else { close(at: i) }
        }
        close(at: y.count)
        return beats
    }

    /// Parabolic interpolation through the peak and its two neighbours.
    private static func refined(_ y: [Double], _ i: Int) -> Double {
        guard i > 0, i < y.count - 1 else { return Double(i) }
        let a = y[i - 1], b = y[i], c = y[i + 1], d = a - 2 * b + c
        return abs(d) < 1e-12 ? Double(i) : Double(i) + 0.5 * (a - c) / d
    }
}

enum HeartRateFailure: Error, Equatable {
    case tooFewBeats(Int)
    case unstable
}

struct HeartRateEstimate: Equatable {
    var bpm: Double
    var intervals: [Double]
    var acceptedBeats: Int
}

enum HeartRate {
    static let minInterval = 0.33, maxInterval = 1.5
    static let maxDeviationFromRunning = 0.30
    static let amplitudeBand = 0.5...2.0
    static let minAccepted = 8
    static let minAcceptedShare = 0.7
    static let maxHalfDrift = 0.15

    static func estimate(beats: [Beat]) -> Result<HeartRateEstimate, HeartRateFailure> {
        // Drop beats whose amplitude is far from the window's median.
        let medAmp = Stats.median(beats.map(\.amplitude))
        let kept = beats.filter { medAmp > 0 && amplitudeBand.contains($0.amplitude / medAmp) }
        var accepted: [Double] = []
        var inBand = 0
        for i in 1..<max(1, kept.count) {
            let dt = kept[i].time - kept[i - 1].time
            guard dt >= minInterval, dt <= maxInterval else { continue }
            inBand += 1
            if accepted.count >= 3 {
                let running = Stats.median(Array(accepted.suffix(10)))
                if abs(dt - running) / running > maxDeviationFromRunning { continue }
            }
            accepted.append(dt)
        }
        guard accepted.count >= minAccepted else { return .failure(.tooFewBeats(accepted.count)) }
        // A rate that shifted mid-scan looks like a run of outliers; refuse rather than report half the scan.
        if Double(accepted.count) < minAcceptedShare * Double(inBand) { return .failure(.unstable) }
        let half = accepted.count / 2
        let first = Stats.median(Array(accepted[0..<half])), second = Stats.median(Array(accepted[half...]))
        if abs(first - second) / max(first, second) > maxHalfDrift { return .failure(.unstable) }
        return .success(HeartRateEstimate(bpm: 60 / Stats.median(accepted), intervals: accepted,
                                          acceptedBeats: accepted.count + 1))
    }
}
