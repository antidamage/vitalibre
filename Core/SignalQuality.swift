import Foundation

enum QualityLevel: String, Codable { case poor, fair, good }

struct SignalQuality: Equatable {
    var skewness: Double
    var templateCorrelation: Double
    /// Percent, from the raw channel's AC/DC. Gates but does not rank.
    var perfusionIndex: Double

    static let minPerfusionIndex = 0.05

    /// 0...1. Skewness and template correlation rank; perfusion only gates.
    var score: Double {
        guard perfusionIndex >= Self.minPerfusionIndex else { return 0 }
        let skew = max(0, min(1, skewness / 0.6))
        let corr = max(0, min(1, (templateCorrelation - 0.5) / 0.4))
        return 0.4 * skew + 0.6 * corr
    }

    var level: QualityLevel { score >= 0.6 ? .good : (score >= 0.35 ? .fair : .poor) }

    /// `filtered` is upward-positive at `fs`; `raw` is the unfiltered channel over the same window.
    static func measure(filtered: [Double], raw: [Double], beats: [Beat], fs: Double) -> SignalQuality {
        let ac = Stats.percentile(filtered, 95) - Stats.percentile(filtered, 5)
        let dc = abs(Stats.mean(raw))
        let pi = dc > 0 ? ac / dc * 100 : 0
        return SignalQuality(skewness: Stats.skewness(filtered),
                             templateCorrelation: templateCorrelation(filtered, beats: beats, fs: fs),
                             perfusionIndex: pi)
    }

    /// Mean Pearson r of each beat's segment against the average segment.
    static func templateCorrelation(_ y: [Double], beats: [Beat], fs: Double) -> Double {
        let pre = Int(0.25 * fs), post = Int(0.45 * fs)
        let segs = beats.compactMap { b -> [Double]? in
            b.index - pre >= 0 && b.index + post < y.count ? Array(y[(b.index - pre)...(b.index + post)]) : nil
        }
        guard segs.count >= 3, let len = segs.first?.count else { return 0 }
        var template = [Double](repeating: 0, count: len)
        for s in segs { for i in 0..<len { template[i] += s[i] / Double(segs.count) } }
        return Stats.mean(segs.map { Stats.correlation($0, template) })
    }
}
