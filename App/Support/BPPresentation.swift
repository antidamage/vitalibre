import Foundation

/// What the screens say about blood pressure, from the publisher's policy and the person's calibration.
/// The rules themselves are in Core (`BPDisplay`, `BPMargin`); this only feeds them the app's state.
enum BPPresentation {
    static var validDays: Int { Publisher.policy.bpCalibrationValidDays }
    static var mode: String { Publisher.policy.iosBpDisplay }

    /// Whether a figure taken now would be eligible for display: a current cuff calibration, and the switch on.
    static func eligible(_ prefs: Preferences, now: Date = Date()) -> Bool {
        BPDisplay.hidden(rhythm: nil, calibration: prefs.calibration, platform: .ios, mode: mode,
                         validDays: validDays, now: now) == nil
    }

    /// The small print under a figure.
    static func margin(_ prefs: Preferences, model: BPModel) -> String {
        let age = prefs.age > 0 ? prefs.age : nil
        let base = BPEstimator.baseline(model: model, age: age, sex: prefs.sex, usual: prefs.usual)
        return BPMargin.text(model: model, calibration: prefs.calibration, base: base,
                             legacy: (model.baseSystolic, model.baseDiastolic))
    }

    /// "Calibrated 3 Oct, expires 2 Nov", or nil with no calibration.
    static func expiryLine(_ prefs: Preferences) -> String? {
        guard let taken = prefs.calibration.newestDate, let expiry = prefs.calibration.expiry(validDays: validDays) else { return nil }
        let style = Date.FormatStyle(date: .abbreviated, time: .omitted)
        return expiry > Date()
            ? "Calibrated \(taken.formatted(style)), blood pressure is shown until \(expiry.formatted(style))."
            : "Calibration ran out on \(expiry.formatted(style)). Blood pressure is hidden until you calibrate again."
    }
}

extension Reading {
    /// Why this reading's blood pressure is not shown, or nil to show it. The figure shows only if the scan was
    /// taken with a current calibration, the calibration is still current, and the pulse is not irregular.
    func bpHidden(_ prefs: Preferences, now: Date = Date()) -> BPHidden? {
        if let h = BPDisplay.hidden(rhythm: displayRhythm, calibration: prefs.calibration, platform: .ios,
                                    mode: BPPresentation.mode, validDays: BPPresentation.validDays, now: now) { return h }
        return bpShown == true ? nil : .needsCalibration
    }
}
