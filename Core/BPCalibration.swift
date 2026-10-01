import Foundation

/// One paired reading: what the model said for a scan, and what a cuff said at the same time.
struct CalibrationPoint: Codable, Equatable {
    var rawSystolic: Double, rawDiastolic: Double
    var cuffSystolic: Double, cuffDiastolic: Double
    var date: Date
    /// Phone model identifier the pair was taken on, e.g. "iPhone18,2"; nil for older points.
    var device: String? = nil

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

    var offset: (systolic: Double, diastolic: Double) {
        guard !points.isEmpty else { return (0, 0) }
        let s = Stats.mean(points.map { $0.cuffSystolic - $0.rawSystolic })
        let d = Stats.mean(points.map { $0.cuffDiastolic - $0.rawDiastolic })
        let cap = Self.maxOffset
        return (max(-cap, min(cap, s)), max(-cap, min(cap, d)))
    }

    /// Half-widths for the displayed range. `fallback` is the model's population figure.
    func halfWidths(fallback: (systolic: Double, diastolic: Double)) -> (systolic: Double, diastolic: Double) {
        guard points.count >= Self.minPointsForNarrowing else { return fallback }
        let off = offset
        let s = Stats.std(points.map { $0.cuffSystolic - $0.rawSystolic - off.systolic })
        let d = Stats.std(points.map { $0.cuffDiastolic - $0.rawDiastolic - off.diastolic })
        return (max(Self.minHalfWidth.systolic, min(fallback.systolic, 1.64 * s)),
                max(Self.minHalfWidth.diastolic, min(fallback.diastolic, 1.64 * d)))
    }

    mutating func add(_ p: CalibrationPoint) {
        guard CalibrationPoint.isPlausible(systolic: p.cuffSystolic, diastolic: p.cuffDiastolic) else { return }
        points.append(p)
        if points.count > Self.maxPoints { points.removeFirst(points.count - Self.maxPoints) }
    }
}
