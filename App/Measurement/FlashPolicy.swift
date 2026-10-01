import Foundation

/// Decides, from the samples alone, whether a reading needs the flash.
///
/// A fingertip over the lens gives a flat signal in poor light, so the lighting is judged by how strongly a
/// pulse-rate rhythm stands out from the noise (`pulseQuality`): with the flash off and the signal settled,
/// a weak rhythm means the flash is tried, and if it does not make the rhythm at least `margin` better the
/// flash goes back off and that preference is remembered. A scene with nothing lit at all skips the wait.
///
/// This mirrors `regulateLight` in the Android `CameraSource`: the same constants and the same decisions,
/// so both platforms come to the same answer for the same signal.
struct FlashPolicy {
    /// How the flash is being used in this scan.
    enum Light { case off, onTrial, onKept, offKept }

    /// What the torch should do after a sample.
    enum Action {
        case none
        /// Switch the flash to `on`. Either way the exposure lock is released: the lighting is changing.
        case setTorch(Bool)
    }

    // Thresholds, as numbers rather than rules of thumb so they can be tuned without reading the code.
    static let windowSeconds = 3.0   // frames used to judge the pulse
    static let minSamples = 60       // samples the judgement needs before it means anything
    static let offSettle = 10.0      // seconds of cover with the flash off before it is judged
    static let weak = 20.0           // pulse quality below this is "too flat"
    static let margin = 5.0          // the flash must be at least this much worse to be switched off again
    static let trialCompareAt = 4.0  // seconds the flash is tried before the decision (the last 3 s compared)
    static let noCoverAfter = 10.0   // seconds with no finger recognised before the flash is tried
    static let darkScene = 12.0      // mean channel value below this with the flash off: nothing is lit
    static let darkHold = 1.0        // seconds of that darkness before the flash is switched on

    private(set) var light = Light.off
    /// True while the flash is on for a reading.
    private(set) var torchWanted = false
    /// The decision is still in progress, so the reading in hand is not final.
    private(set) var decisionPending = false
    /// True/false once the flash usage is settled, nil while it is still being decided.
    var flashUsed: Bool? {
        switch light {
        case .onKept: return true
        case .off, .offKept: return false
        case .onTrial: return nil
        }
    }

    private var startedAt = -1.0
    private var coverSince = -1.0
    private var trialAt = -1.0
    private var lastCovered = -10.0
    private var darkSince = -1.0
    private var qualityOff = 0.0
    private var quality = 0.0
    private var lastQualityAt = -10.0
    private var times: [Double] = []
    private var greens: [Double] = []

    /// Begins a scan, starting from the remembered flash state when there is one. A remembered state is used
    /// straight away and not re-tested; with no memory the flash starts off and is switched on only if the
    /// signal turns out too flat.
    mutating func start(remembered: Bool?) {
        light = .off; torchWanted = false; decisionPending = false
        startedAt = -1; coverSince = -1; trialAt = -1; lastCovered = -10; darkSince = -1
        qualityOff = 0; quality = 0; lastQualityAt = -10; times = []; greens = []
        switch remembered {
        case .some(true): light = .onKept; torchWanted = true
        case .some(false): light = .offKept
        case .none: break
        }
    }

    mutating func stop() {
        torchWanted = false
        decisionPending = false
    }

    /// Records a sample and says what the torch should do.
    mutating func regulate(_ s: PPGSample) -> Action {
        if startedAt < 0 { startedAt = s.t }
        if s.covered {
            lastCovered = s.t
            if coverSince < 0 { coverSince = s.t }
            times.append(s.t)
            greens.append(s.g)
            while times.count > 1, s.t - times[0] > Self.windowSeconds {
                times.removeFirst()
                greens.removeFirst()
            }
        } else {
            coverSince = -1
            times = []; greens = []
        }
        // A scene with nothing lit has nothing to read: while the flash is off, a second of near-black means
        // the flash is needed now rather than after the settle window.
        if light == .off || light == .offKept {
            if (s.r + s.g + s.b) / 3 < Self.darkScene {
                if darkSince < 0 { darkSince = s.t }
            } else {
                darkSince = -1
            }
        }
        let darkScene = darkSince >= 0 && s.t - darkSince >= Self.darkHold

        // The flash off gets `offSettle` seconds of cover to settle before it is judged. Once the flash is on
        // the person has settled already, so it is compared after a short trial.
        let settled: Bool
        switch light {
        case .off: settled = times.count >= Self.minSamples && coverSince >= 0 && s.t - coverSince >= Self.offSettle
        case .onTrial: settled = times.count >= Self.minSamples && s.t - trialAt >= Self.trialCompareAt
        case .onKept, .offKept: settled = false
        }
        if settled, s.t - lastQualityAt >= 0.5 { quality = pulseQuality(); lastQualityAt = s.t }

        switch light {
        case .off, .offKept:
            let off = light == .off
            let neverCovered = off && s.t - startedAt > Self.noCoverAfter
                && (lastCovered < 0 || s.t - lastCovered > Self.noCoverAfter)
            let flat = off && settled && quality < Self.weak
            // A remembered "flash off" is left alone unless the scene is dark enough that there is nothing to
            // see at all; the no-finger and flat-signal rules apply to a flash that was not remembered.
            guard neverCovered || flat || darkScene else { return .none }
            qualityOff = settled ? quality : 0
            light = .onTrial; trialAt = s.t; torchWanted = true; decisionPending = true
            coverSince = -1; darkSince = -1; times = []; greens = []
            return .setTorch(true)
        case .onTrial:
            guard settled else { return .none }
            decisionPending = false
            if quality + Self.margin < qualityOff {
                light = .offKept; torchWanted = false
                return .setTorch(false)
            }
            light = .onKept
            return .none
        case .onKept:
            return .none
        }
    }

    /// How strongly a pulse-rate rhythm (0.7-3 Hz) stands out from the noise: the strongest band power over the
    /// median power of 3-10 Hz, on the green mean with its slow drift removed. Scale-free, so it can compare
    /// two lightings. Roughly 1-6 for noise alone; hundreds or more for a clean pulse.
    private func pulseQuality() -> Double {
        let n = times.count
        if n < Self.minSamples { return 0 }
        let span = times[n - 1] - times[0]
        if span < 1e-6 { return 0 }
        let fs = Double(n - 1) / span
        let half = max(2, Int(fs * 0.5))
        var x = [Double](repeating: 0, count: n)
        for i in 0..<n {
            var m = 0.0
            var c = 0
            for j in max(0, i - half)...min(n - 1, i + half) { m += greens[j]; c += 1 }
            x[i] = (greens[i] - m / Double(c)) * (0.5 - 0.5 * cos(2 * Double.pi * Double(i) / Double(n - 1)))
        }
        func power(_ f: Double) -> Double {
            var re = 0.0
            var im = 0.0
            for i in 0..<n {
                let a = 2 * Double.pi * f * (times[i] - times[0])
                re += x[i] * cos(a); im -= x[i] * sin(a)
            }
            return re * re + im * im
        }
        var band = 0.0
        var f = 0.7
        while f <= 3.0 { band = max(band, power(f)); f += 0.1 }
        var noise: [Double] = []
        f = 3.0
        while f <= min(10.0, fs / 2 - 0.5) { noise.append(power(f)); f += 0.25 }
        if noise.isEmpty { return 0 }
        noise.sort()
        let med = noise[noise.count / 2]
        return med > 1e-9 ? band / med : 1e6
    }
}
