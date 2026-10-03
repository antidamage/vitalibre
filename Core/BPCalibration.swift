import Foundation

/// One paired reading: what the model said for a scan, and what a cuff said at the same time.
struct CalibrationPoint: Codable, Equatable {
    var rawSystolic: Double, rawDiastolic: Double
    var cuffSystolic: Double, cuffDiastolic: Double
    var date: Date
    /// Phone model identifier the pair was taken on, e.g. "iPhone18,2"; nil for older points.
    var device: String? = nil
    /// The starting point (age prior or typical resting pressure) the raw value was built on. nil for older
    /// points, which were taken on the model's own base.
    var baseSystolic: Double? = nil
    var baseDiastolic: Double? = nil
    /// Which cuff the reading came from, free text, so a comparison can name its reference. nil when not given.
    var cuffName: String? = nil

    static func isPlausible(systolic: Double, diastolic: Double) -> Bool {
        (70...250).contains(systolic) && (40...150).contains(diastolic) && systolic > diastolic + 10
    }
}

/// Personal calibration against a cuff. The offset is the mean gap between cuff and
/// model; with three or more points the range narrows to the error actually seen
/// against this person's cuff instead of the published population error.
struct BPCalibration: Codable, Equatable {
    var points: [CalibrationPoint] = []

    static let maxOffset = 60.0
    static let minPointsForNarrowing = 3
    static let minHalfWidth = (systolic: 6.0, diastolic: 4.0)
    static let maxPoints = 30

    var count: Int { points.count }

    /// How long a paired cuff reading keeps blood pressure on display (iOS). `policy.json` can override it.
    static let defaultValidDays = 30

    /// The date the newest paired cuff reading was taken, or nil with none.
    var newestDate: Date? { points.map(\.date).max() }

    /// A calibration is current while its newest paired cuff reading is at most `validDays` old. Only a
    /// paired reading counts: it is the only thing that measures the model against a reference.
    func isCurrent(now: Date = Date(), validDays: Int = BPCalibration.defaultValidDays) -> Bool {
        guard let d = newestDate else { return false }
        return now.timeIntervalSince(d) <= Double(validDays) * 86400
    }

    func expiry(validDays: Int = BPCalibration.defaultValidDays) -> Date? {
        newestDate.map { $0.addingTimeInterval(Double(validDays) * 86400) }
    }

    typealias Base = (systolic: Double, diastolic: Double)

    /// What each pairing says the estimate should shift by, measured against the CURRENT starting point.
    /// A pairing stores the raw value (starting point plus the pulse adjustment) it was made on; if the
    /// starting point has changed since (a typical pressure was set, say), the old gap would otherwise be
    /// counted on top of a baseline that already includes it.
    private func gaps(base: Base, legacy: Base) -> (s: [Double], d: [Double]) {
        (points.map { $0.cuffSystolic - base.systolic - ($0.rawSystolic - ($0.baseSystolic ?? legacy.systolic)) },
         points.map { $0.cuffDiastolic - base.diastolic - ($0.rawDiastolic - ($0.baseDiastolic ?? legacy.diastolic)) })
    }

    func offset(base: Base, legacy: Base) -> (systolic: Double, diastolic: Double) {
        guard !points.isEmpty else { return (0, 0) }
        let g = gaps(base: base, legacy: legacy)
        let cap = Self.maxOffset
        return (max(-cap, min(cap, Stats.mean(g.s))), max(-cap, min(cap, Stats.mean(g.d))))
    }

    /// 1.64 x the residual SD against this person's cuff, or nil before three pairings. Not capped or floored.
    func cuffSpread(base: Base, legacy: Base) -> (systolic: Double, diastolic: Double)? {
        guard points.count >= Self.minPointsForNarrowing else { return nil }
        let g = gaps(base: base, legacy: legacy)
        return (1.64 * Stats.std(g.s), 1.64 * Stats.std(g.d))
    }

    /// Half-widths for the displayed range. `fallback` is the model's population figure.
    func halfWidths(fallback: (systolic: Double, diastolic: Double), base: Base, legacy: Base) -> (systolic: Double, diastolic: Double) {
        guard points.count >= Self.minPointsForNarrowing else { return fallback }
        let g = gaps(base: base, legacy: legacy)
        let s = Stats.std(g.s), d = Stats.std(g.d)
        return (max(Self.minHalfWidth.systolic, min(fallback.systolic, 1.64 * s)),
                max(Self.minHalfWidth.diastolic, min(fallback.diastolic, 1.64 * d)))
    }

    mutating func add(_ p: CalibrationPoint) {
        guard CalibrationPoint.isPlausible(systolic: p.cuffSystolic, diastolic: p.cuffDiastolic) else { return }
        points.append(p)
        if points.count > Self.maxPoints { points.removeFirst(points.count - Self.maxPoints) }
    }
}
