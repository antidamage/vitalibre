import Foundation

/// One second-order section, direct form II transposed.
struct Biquad {
    var b0, b1, b2, a1, a2: Double

    /// 2nd-order Butterworth via the bilinear transform.
    static func lowpass(cutoff: Double, fs: Double) -> Biquad {
        let k = tan(.pi * cutoff / fs), norm = 1 / (1 + sqrt(2) * k + k * k)
        return Biquad(b0: k * k * norm, b1: 2 * k * k * norm, b2: k * k * norm,
                      a1: 2 * (k * k - 1) * norm, a2: (1 - sqrt(2) * k + k * k) * norm)
    }

    static func highpass(cutoff: Double, fs: Double) -> Biquad {
        let k = tan(.pi * cutoff / fs), norm = 1 / (1 + sqrt(2) * k + k * k)
        return Biquad(b0: norm, b1: -2 * norm, b2: norm,
                      a1: 2 * (k * k - 1) * norm, a2: (1 - sqrt(2) * k + k * k) * norm)
    }

    func run(_ x: [Double]) -> [Double] {
        var z1 = 0.0, z2 = 0.0
        return x.map { v in
            let y = b0 * v + z1
            z1 = b1 * v - a1 * y + z2
            z2 = b2 * v - a2 * y
            return y
        }
    }
}

enum Filters {
    /// Zero-phase filtering: forward then backward, so peak times are not
    /// shifted. Odd reflection at both ends keeps the edge transient small.
    static func filtfilt(_ x: [Double], _ sections: [Biquad]) -> [Double] {
        guard x.count > 8 else { return x }
        let pad = min(x.count - 1, 120)
        var padded: [Double] = []
        padded.reserveCapacity(x.count + 2 * pad)
        for i in stride(from: pad, through: 1, by: -1) { padded.append(2 * x[0] - x[i]) }
        padded.append(contentsOf: x)
        for i in 1...pad { padded.append(2 * x[x.count - 1] - x[x.count - 1 - i]) }
        var y = padded
        for _ in 0..<2 {
            for s in sections { y = s.run(y) }
            y.reverse()
        }
        return Array(y[pad..<(pad + x.count)])
    }

    /// Band-pass 0.5-5 Hz by default (high-pass then low-pass, both 2nd order).
    static func bandpass(_ x: [Double], low: Double = 0.5, high: Double = 5.0, fs: Double) -> [Double] {
        let mean = x.reduce(0, +) / Double(max(1, x.count))
        let centred = x.map { $0 - mean }
        return filtfilt(centred, [.highpass(cutoff: low, fs: fs), .lowpass(cutoff: high, fs: fs)])
    }

    /// Linear-interpolated resample onto a uniform grid. `times` must be ascending.
    static func resample(times: [Double], values: [Double], rate: Double) -> [Double] {
        guard times.count > 1, times.count == values.count else { return values }
        let t0 = times[0], t1 = times[times.count - 1]
        let n = Int((t1 - t0) * rate) + 1
        var out = [Double](); out.reserveCapacity(n)
        var j = 0
        for i in 0..<n {
            let t = t0 + Double(i) / rate
            while j < times.count - 2 && times[j + 1] < t { j += 1 }
            let span = times[j + 1] - times[j]
            let f = span > 0 ? min(1, max(0, (t - times[j]) / span)) : 0
            out.append(values[j] + f * (values[j + 1] - values[j]))
        }
        return out
    }

    static func movingAverage(_ x: [Double], window: Int) -> [Double] {
        guard window > 1, !x.isEmpty else { return x }
        var prefix = [0.0]; prefix.reserveCapacity(x.count + 1)
        for v in x { prefix.append(prefix[prefix.count - 1] + v) }
        let half = window / 2
        return (0..<x.count).map { i in
            let lo = max(0, i - half), hi = min(x.count, i + half + 1)
            return (prefix[hi] - prefix[lo]) / Double(hi - lo)
        }
    }
}

enum Stats {
    static func mean(_ x: [Double]) -> Double { x.isEmpty ? 0 : x.reduce(0, +) / Double(x.count) }

    static func median(_ x: [Double]) -> Double {
        guard !x.isEmpty else { return 0 }
        let s = x.sorted(), m = s.count / 2
        return s.count % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2
    }

    static func std(_ x: [Double]) -> Double {
        guard x.count > 1 else { return 0 }
        let m = mean(x)
        return sqrt(x.reduce(0) { $0 + ($1 - m) * ($1 - m) } / Double(x.count))
    }

    static func skewness(_ x: [Double]) -> Double {
        let s = std(x)
        guard x.count > 2, s > 1e-12 else { return 0 }
        let m = mean(x)
        return x.reduce(0) { $0 + pow(($1 - m) / s, 3) } / Double(x.count)
    }

    static func percentile(_ x: [Double], _ p: Double) -> Double {
        guard !x.isEmpty else { return 0 }
        let s = x.sorted()
        return s[min(s.count - 1, max(0, Int(p / 100 * Double(s.count - 1) + 0.5)))]
    }

    static func correlation(_ a: [Double], _ b: [Double]) -> Double {
        let n = min(a.count, b.count)
        guard n > 2 else { return 0 }
        let ma = mean(Array(a[0..<n])), mb = mean(Array(b[0..<n]))
        var sab = 0.0, saa = 0.0, sbb = 0.0
        for i in 0..<n {
            let da = a[i] - ma, db = b[i] - mb
            sab += da * db; saa += da * da; sbb += db * db
        }
        return saa > 0 && sbb > 0 ? sab / sqrt(saa * sbb) : 0
    }
}
