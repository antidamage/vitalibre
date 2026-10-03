import Foundation

/// Why a blood-pressure figure is not shown. `nil` from `BPDisplay.hidden` means show it.
enum BPHidden: Equatable {
    /// iOS shows a figure only while a cuff calibration is current.
    case needsCalibration
    /// An irregular pulse corrupts the interval features, so the reading carries heart rate and no BP.
    case irregular
    /// `iosBpDisplay` is "never".
    case disabled

    var line: String {
        switch self {
        case .needsCalibration: return "Blood pressure is hidden until you calibrate with a cuff reading."
        case .irregular: return "Blood pressure is not shown when the pulse is irregular."
        case .disabled: return "Blood pressure is not shown on this device."
        }
    }
}

/// The rules for when a blood-pressure figure is shown (specs/ppg-vitals-app.md, "Blood pressure display").
/// The figure is always computed and stored; this only decides what the screen, the share text and the
/// export carry.
enum BPDisplay {
    enum Platform { case ios, android }

    static func hidden(rhythm: Rhythm?, calibration: BPCalibration, platform: Platform, mode: String,
                       validDays: Int = BPCalibration.defaultValidDays, now: Date = Date()) -> BPHidden? {
        if rhythm == .irregular { return .irregular }
        guard platform == .ios else { return nil }
        if mode == "never" { return .disabled }
        return calibration.isCurrent(now: now, validDays: validDays) ? nil : .needsCalibration
    }
}

/// The small print under a figure: how far it can be out.
enum BPMargin {
    static let minPointsForCuffMargin = BPCalibration.minPointsForNarrowing

    static func text(model: BPModel, calibration: BPCalibration, base: BPCalibration.Base, legacy: BPCalibration.Base) -> String {
        let w = calibration.halfWidths(fallback: (model.halfWidthSystolic, model.halfWidthDiastolic), base: base, legacy: legacy)
        let s = Int(w.systolic.rounded()), d = Int(w.diastolic.rounded())
        // The cuff spread is what is shown only when it is narrower than the population error on both; if it is
        // wider, the shown width is the population error and saying "90% of your readings" would be false.
        if let m = calibration.cuffSpread(base: base, legacy: legacy),
           m.systolic <= model.halfWidthSystolic, m.diastolic <= model.halfWidthDiastolic {
            return "±\(s) / ±\(d) mmHg: about 90% of your \(calibration.count) cuff comparisons fall inside. Measured on the same readings, so a little optimistic."
        }
        return "Average error about ±\(s) / ±\(d) mmHg; single readings can differ more."
    }
}
