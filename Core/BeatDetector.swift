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
}

/// How evenly the beats came. A note about a reading, never a verdict on it: a rhythm
/// that swings is reported, not refused. A finger camera cannot tell an irregular rhythm
/// from a poor signal, and it does not try to diagnose either.
enum Rhythm: String, Codable, Equatable { case steady, irregular }

struct HeartRateEstimate: Equatable {
    var bpm: Double
    var intervals: [Double]
    var acceptedBeats: Int
    /// Standard deviation over the mean of the accepted intervals: the same spread the
    /// blood-pressure estimator carries as `intervalCV`.
    var variation: Double
    var rhythm: Rhythm
}

enum HeartRate {
    /// The range a fingertip can show: 30-240 bpm. Wider than the 40-180 the first build
    /// used, so a pause or a run of fast beats reads as a beat rather than as a dropout.
    static let minInterval = 0.25, maxInterval = 2.0
    static let amplitudeBand = 0.5...2.0
    /// Enough beats to take a median of. A count, not a duration: how long the scan ran
    /// says nothing about whether the rate in it can be trusted.
    static let minAccepted = 8
    /// The interval spread at which a rhythm is called irregular: about three times
    /// ordinary respiratory variation (near 0.03), and well short of what a rhythm that
    /// genuinely swings gives.
    static let irregularVariation = 0.10

    static func estimate(beats: [Beat]) -> Result<HeartRateEstimate, HeartRateFailure> { estimate(groups: [beats]) }

    /// Intervals are taken only between beats of the same group, never across a gap: a group is a
    /// stretch of signal the user kept, and an interval spanning a discarded stretch would be false.
    static func estimate(groups: [[Beat]]) -> Result<HeartRateEstimate, HeartRateFailure> {
        // Drop beats whose amplitude is far from the window's median: that is the
        // detector's confidence in the beat, not its timing.
        let medAmp = Stats.median(groups.flatMap { $0 }.map(\.amplitude))
        var accepted: [Double] = []
        for group in groups {
            let kept = group.filter { medAmp > 0 && amplitudeBand.contains($0.amplitude / medAmp) }
            for i in 1..<max(1, kept.count) {
                let dt = kept[i].time - kept[i - 1].time
                guard dt >= minInterval, dt <= maxInterval else { continue }
                accepted.append(dt)
            }
        }
        guard accepted.count >= minAccepted else { return .failure(.tooFewBeats(accepted.count)) }
        // Nothing from here on refuses a window for its rhythm, and nothing is dropped for
        // sitting far from its neighbours. A rate that swings during the scan is measured:
        // `variation` says by how much and `rhythm` whether it is worth a note. That was
        // the point of the change (Adeline, 2026-10-04): a recording that might hold an
        // arrhythmia is a recording to keep and mark, not one to abandon.
        let variation = Stats.std(accepted) / max(1e-9, Stats.mean(accepted))
        return .success(HeartRateEstimate(bpm: 60 / Stats.median(accepted), intervals: accepted,
                                          acceptedBeats: accepted.count + 1, variation: variation,
                                          rhythm: variation >= irregularVariation ? .irregular : .steady))
    }
}
